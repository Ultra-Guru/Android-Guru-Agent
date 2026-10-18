package com.apex.agent.ui.screen.market

import com.apex.agent.core.tools.marketplace.HubSource
import com.apex.agent.core.tools.marketplace.MarketNames
import com.apex.agent.core.tools.marketplace.NativeMcpEquivalents
import com.apex.agent.core.tools.mcp.McpManager
import com.apex.agent.core.tools.mcp.McpSandboxRuntimeProbe.Verdict
import com.apex.agent.core.tools.mcp.McpServerConfig
import com.apex.agent.core.tools.mcp.McpTransport
import com.apex.agent.github.GithubTokenManager
import com.apex.agent.marketplace.MarketInstallManager
import com.apex.agent.marketplace.ProotSandboxCommandRunner
import com.apex.agent.marketplace.RegistryMcpInstaller
import com.apex.agent.marketplace.SandboxRuntimeProbe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ═══ 官方 Hub 仓库控制器（市场 · Skills「官方仓库」源 + MCP 目录）═══
 *
 * 从 [MarketViewModel] 拆出的独立状态域（God-file 预算 + 单一职责）：
 * - **技能仓库**（apex-skill-hub）：目录浏览 + 单技能 manifest 下载安装
 *   （走 [MarketInstallManager.installSkillFromJson] 统一管道）；
 * - **MCP 仓库**（apex-mcp-hub）：目录浏览 + 一键安装（安装决策树见
 *   [installMcp]，安装 ≠ 启动 —— enabled=false，启动走市场「启动」按钮
 *   的真实连接管线）；
 * - **沙箱环境体检**：MCP 页签切入时探测 rootfs / nodejs 运行时
 *   （[probeSandboxRuntime]），缺失时 UI 顶部渲染引导卡，nodejs 缺失
 *   可**一键 apt 安装**（[installSandboxRuntime]）—— 旧版只报
 *   「进程在响应前退出」，用户拿着错误文案去终端手敲命令。
 *
 * 安装完成后经 [refreshMarket] 通知宿主 VM 刷新快照（已安装徽标/列表联动）。
 * 自持 SupervisorJob scope（单例，与 App 进程同生命周期；安装失败不殊及宿主）。
 *
 * ## MCP 安装决策树（MCP 市场诚实化 —— 旧实现只写配置的「假安装」根治）
 * 1. **原生等价拦截**（[NativeMcpEquivalents]）：目录条目在内置进程内
 *    服务器里有等价实现（如 SequentialThinking → thinking）时拒绝安装，
 *    文案指向「已配置服务器」的内置行 —— 真内置不卖安装；
 * 2. **重复检查**（#346 归一化比较键）：跨源同名/变体拦截；
 * 3. **远端条目**（HTTP/SSE）：写配置即真实可用（无本地依赖），行为不变；
 * 4. **STDIO 条目 · 环境门禁**（[SandboxRuntimeProbe]）：rootfs 未装 /
 *    运行时缺失（node/uvx/python）→ **诚实失败**（缺什么、怎么补，
 *    nodejs 缺失同时点亮顶部的一键安装卡），不落必败配置；
 * 5. **STDIO · npx 条目 · 真实预装**：运行时就绪时在沙箱内真实执行
 *    `npm install -g <包>`（复用 [ProotSandboxCommandRunner]，与
 *    Registry 安装同源 —— 绕开 `npx -y` 首次冷启动撞 180s 握手超时，
 *    Issue #163 的教训）；配置写入失败回滚沙箱卸载；
 * 6. **STDIO · uvx/python 条目**：运行时就绪时写配置，成功文案注明
 *    「首启下载依赖」（uvx 无预装通道，诚实告知而不是假装装好了）。
 *
 * Android 宿主没有 node —— STDIO 条目一律升级 `runInSandbox=true`
 * （与精选目录 installCatalogEntry 的既有语义一致），宿主直跑必败不装。
 */
@Singleton
class MarketHubController @Inject constructor(
    private val hubSource: HubSource,
    private val installManager: MarketInstallManager,
    private val mcpManager: McpManager,
    // 内置 github MCP 配置对话框的账号连接入口（token 验证/保存复用全局单例）
    val githubTokens: GithubTokenManager,
    /** MCP 市场诚实化：沙箱运行时探测（安装门禁第 4 步 + 页签体检）。 */
    private val runtimeProbe: SandboxRuntimeProbe,
    /** MCP 市场诚实化：沙箱一次性命令执行器（npm 预装 + nodejs 一键安装）。 */
    private val sandboxRunner: ProotSandboxCommandRunner
) {

    /**
     * 沙箱环境体检结果（null = 一切就绪，不渲染）。
     *
     * @param rootfsMissing true = Ubuntu rootfs 未装（引导去终端页，无一键动作）
     * @param missingCommand 缺失的运行时命令（nodejs 家族 / python 家族）
     */
    data class SandboxRuntimeUi(
        val rootfsMissing: Boolean,
        val missingCommand: String?
    )

    /** Hub 双目录的 UI 状态（技能 + MCP + 沙箱体检）。 */
    data class HubUiState(
        // ── 技能仓库 ──
        val skills: List<HubSource.HubSkillEntry> = emptyList(),
        val skillsLoading: Boolean = false,
        val skillsError: String? = null,
        val installingSkillId: String? = null,
        // ── MCP 仓库 ──
        val mcps: List<HubSource.HubMcpEntry> = emptyList(),
        val mcpsLoading: Boolean = false,
        val mcpsError: String? = null,
        val installingMcpName: String? = null,
        // ── 沙箱环境体检（MCP 页签顶部引导卡）──
        val runtimeStatus: SandboxRuntimeUi? = null,
        val runtimeInstalling: Boolean = false,
        /** 体检/一键安装的结果反馈（成功绿色语义，失败附 stderr 尾部）。 */
        val runtimeNotice: String? = null
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _uiState = MutableStateFlow(HubUiState())
    val uiState: StateFlow<HubUiState> = _uiState.asStateFlow()

    /** 安装完成信号（宿主 VM 收集后 refresh()，刷新已装徽标与列表）。 */
    private val _refreshMarket = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val refreshMarket = _refreshMarket.asSharedFlow()

    /** 拉取技能仓库目录（已有数据或加载中则跳过；force = 下拉刷新语义）。 */
    fun loadSkills(force: Boolean = false) {
        val current = _uiState.value
        if (!force && (current.skillsLoading || current.skills.isNotEmpty())) return
        scope.launch {
            _uiState.update { it.copy(skillsLoading = true, skillsError = null) }
            hubSource.listSkills().fold(
                onSuccess = { entries -> _uiState.update { it.copy(skills = entries) } },
                onFailure = { e ->
                    _uiState.update { it.copy(skills = emptyList(), skillsError = e.message) }
                }
            )
            _uiState.update { it.copy(skillsLoading = false) }
        }
    }

    /**
     * 安装一个 Hub 技能：下载 manifest → 统一安装管道。
     * 行级 busy（installingSkillId）防并发下载。
     *
     * per-scope 默认启用工位 = 条目声明的 scope（agent/coding；all = 双开）——
     * 与市场分级过滤同源（HubSkillEntry.visibleToScope）。
     */
    fun installSkill(entry: HubSource.HubSkillEntry) {
        if (_uiState.value.installingSkillId != null) return
        scope.launch {
            _uiState.update { it.copy(installingSkillId = entry.id) }
            try {
                hubSource.downloadSkillManifest(entry).fold(
                    onSuccess = { manifestJson ->
                        val defaultScope = entry.scope.takeIf { it == "agent" || it == "coding" }
                        installManager.installSkillFromJson(manifestJson, defaultScope = defaultScope)
                    },
                    onFailure = { Result.failure(it) }
                ).fold(
                    onSuccess = { _refreshMarket.tryEmit(Unit) },
                    onFailure = { e -> _uiState.update { it.copy(skillsError = e.message) } }
                )
            } finally {
                _uiState.update { it.copy(installingSkillId = null) }
                _refreshMarket.tryEmit(Unit)
            }
        }
    }

    /** 拉取 MCP 仓库目录（配置内联，单次请求即完整目录）。 */
    fun loadMcpServers(force: Boolean = false) {
        val current = _uiState.value
        if (!force && (current.mcpsLoading || current.mcps.isNotEmpty())) return
        scope.launch {
            _uiState.update { it.copy(mcpsLoading = true, mcpsError = null) }
            hubSource.listMcpServers().fold(
                onSuccess = { entries -> _uiState.update { it.copy(mcps = entries) } },
                onFailure = { e ->
                    _uiState.update { it.copy(mcps = emptyList(), mcpsError = e.message) }
                }
            )
            _uiState.update { it.copy(mcpsLoading = false) }
        }
    }

    // ═══ 沙箱环境体检 + 一键安装（npx 启动 100% 失败的根治闭环）═══

    /**
     * 沙箱环境体检：以 nodejs 家族为代表探测（纯文件系统检查，毫秒级）。
     *
     * rootfs 未装 / npx 不可见 → 顶部引导卡（rootfs 引导去终端页；nodejs
     * 缺失给一键安装按钮）。一切就绪 → 状态清空不渲染。安装门禁
     * （installMcpInternal 第 4 步）命中时也会写入同一状态。
     */
    fun probeSandboxRuntime() {
        if (_uiState.value.runtimeInstalling) return
        scope.launch {
            val verdict = withContext(Dispatchers.IO) { runtimeProbe.probe("npx") }
            _uiState.update {
                it.copy(
                    runtimeStatus = when (verdict) {
                        Verdict.ROOTFS_MISSING -> SandboxRuntimeUi(rootfsMissing = true, missingCommand = null)
                        Verdict.RUNTIME_MISSING -> SandboxRuntimeUi(rootfsMissing = false, missingCommand = "npx")
                        Verdict.READY -> null
                    }
                )
            }
        }
    }

    /**
     * 一键安装沙箱运行时（当前体检指向的缺失命令）：nodejs 家族 →
     * `apt-get update && apt-get install -y nodejs npm`；python 家族 →
     * `apt-get install -y python3`。真实在 PRoot 内执行（分钟级），结果
     * 反馈进 [HubUiState.runtimeNotice]；成功后重新体检熄灭引导卡。
     */
    fun installSandboxRuntime() {
        if (_uiState.value.runtimeInstalling) return
        val command = _uiState.value.runtimeStatus?.missingCommand ?: "npx"
        val fix = runtimeFixCommand(command)
        if (fix == null) {
            _uiState.update {
                it.copy(
                    runtimeNotice = "「$command」没有一键安装通道（仅支持 nodejs 家族 / python3）——" +
                        "请到终端的 Ubuntu 环境手动安装"
                )
            }
            return
        }
        scope.launch {
            _uiState.update { it.copy(runtimeInstalling = true, runtimeNotice = null) }
            try {
                val result = withContext(Dispatchers.IO) {
                    sandboxRunner.run(command = fix, timeoutMs = RUNTIME_INSTALL_TIMEOUT_MS)
                }
                if (!result.success) {
                    _uiState.update {
                        it.copy(
                            runtimeNotice = "沙箱内安装失败（退出码 ${result.exitCode}）：" +
                                result.stderr.takeLast(400).trim().ifEmpty { "无 stderr 输出" }
                        )
                    }
                } else {
                    // 装完复检：用启动真实会用的命令探，而不是安装的包名
                    val verdict = withContext(Dispatchers.IO) { runtimeProbe.probe(command) }
                    _uiState.update {
                        it.copy(
                            runtimeStatus = when (verdict) {
                                Verdict.READY -> null
                                Verdict.ROOTFS_MISSING ->
                                    SandboxRuntimeUi(rootfsMissing = true, missingCommand = null)
                                Verdict.RUNTIME_MISSING ->
                                    SandboxRuntimeUi(rootfsMissing = false, missingCommand = command)
                            },
                            runtimeNotice = if (verdict == Verdict.READY) {
                                "运行时已装进沙箱 —— 现在可以安装 npx 型 MCP 服务器了"
                            } else {
                                "apt 报告成功但「$command」仍不可见 —— 请到终端环境手动排查"
                            }
                        )
                    }
                }
            } finally {
                _uiState.update { it.copy(runtimeInstalling = false) }
            }
        }
    }

    /** 关闭体检/安装结果反馈（用户点「知道了」）。 */
    fun dismissRuntimeNotice() {
        _uiState.update { it.copy(runtimeNotice = null) }
    }

    /**
     * 安装一台 Hub MCP 服务器（决策树见类 KDoc；安装 ≠ 启动 → enabled 恒 false）。
     *
     * npx 条目的真实预装最长 5 分钟（npm 下载耗时真实存在），行级 busy
     * （installingMcpName）期间卡片显示「安装中」—— 这正是「真实安装」
     * 与旧版「瞬间落配置」的可感知差别。
     */
    fun installMcp(entry: HubSource.HubMcpEntry) {
        if (_uiState.value.installingMcpName != null) return
        scope.launch {
            _uiState.update { it.copy(installingMcpName = entry.name) }
            try {
                installMcpInternal(entry).fold(
                    onSuccess = { _refreshMarket.tryEmit(Unit) },
                    onFailure = { e -> _uiState.update { it.copy(mcpsError = e.message) } }
                )
                _refreshMarket.tryEmit(Unit)
            } finally {
                _uiState.update { it.copy(installingMcpName = null) }
            }
        }
    }

    /** 安装决策树的实现体（IO 线程；见类 KDoc 的六步清单）。 */
    private suspend fun installMcpInternal(entry: HubSource.HubMcpEntry): Result<String> =
        withContext(Dispatchers.IO) {
            // ① 原生等价拦截：真内置不卖安装（SequentialThinking → thinking 等）。
            // 仅 STDIO 条目拦 —— npx 变体在本机是严格劣解；远端同名条目
            // 本机真实可用，不拦（与卡片层渲染同口径）。
            if (entry.transport.equals("STDIO", ignoreCase = true)) {
                NativeMcpEquivalents.builtinFor(entry.name, entry.command, entry.args)
                    ?.let { builtinId ->
                        return@withContext Result.failure(
                            Exception(
                                "「${entry.name}」无需安装 —— App 已内置原生实现（$builtinId，" +
                                    "进程内零依赖），到「已配置服务器」直接启动 $builtinId 即可"
                            )
                        )
                    }
            }
            // ② 重复检查（#346 归一化比较键）
            val existing = mcpManager.getConfigs().firstOrNull {
                MarketNames.isSameServer(it.name, entry.name)
            }
            if (existing != null) {
                return@withContext Result.failure(
                    Exception(MarketNames.duplicateMessage(existing.name, entry.name))
                )
            }
            val baseConfig = toServerConfig(entry)
            // ③ 远端条目：无本地依赖，写配置即真实可用
            if (baseConfig.transport != McpTransport.STDIO) {
                return@withContext mcpManager.addServer(baseConfig).map {
                    "已添加远端 MCP 服务器：${baseConfig.name}（${baseConfig.url}）" +
                        "—— 安装 ≠ 启动，请在已配置列表中连接"
                }
            }
            // Android 宿主没有 node：STDIO 条目一律升级沙箱运行（宿主直跑必败不装）
            val config = if (baseConfig.runInSandbox) baseConfig
            else baseConfig.copy(runInSandbox = true)
            // ④ 环境门禁：rootfs / 运行时缺失 → 诚实失败，不落必败配置
            val verdict = runtimeProbe.probe(entry.command.orEmpty())
            if (verdict != Verdict.READY) {
                // 同步点亮页签顶部的体检引导卡（rootfs 引导 / 一键装 nodejs）
                _uiState.update {
                    it.copy(
                        runtimeStatus = when (verdict) {
                            Verdict.ROOTFS_MISSING ->
                                SandboxRuntimeUi(rootfsMissing = true, missingCommand = null)
                            Verdict.RUNTIME_MISSING ->
                                SandboxRuntimeUi(
                                    rootfsMissing = false,
                                    missingCommand = entry.command.orEmpty().ifBlank { "npx" }
                                )
                            Verdict.READY -> null
                        }
                    )
                }
                val hint = runtimeProbe.installHint(entry.command.orEmpty())
                    ?: "参考该服务器的官方安装说明"
                return@withContext Result.failure(
                    Exception(
                        when (verdict) {
                            Verdict.ROOTFS_MISSING ->
                                "「${entry.name}」需要在 Ubuntu 沙箱内运行，但沙箱尚未安装 ——" +
                                    "请先到「终端」页安装 Ubuntu（TerminalUbuntuInstall）后再来安装"
                            else ->
                                "沙箱内未找到「${entry.command.orEmpty()}」—— 可点市场顶部的" +
                                    "「一键安装运行时」直接补齐（$hint）；或点卡片上的「桌面配置」" +
                                    "复制 JSON 到电脑端使用"
                        }
                    )
                )
            }
            // ⑤ npx 条目：真实 npm install -g 预装（免冷启动），写失败回滚
            val npmPackage = extractNpmPackage(entry.command, entry.args)
            if (npmPackage != null) {
                if (!RegistryMcpInstaller.isSafeNpmIdentifier(npmPackage)) {
                    return@withContext Result.failure(
                        Exception("npm 包名形态非法（$npmPackage）—— 已拒绝自动安装，请核实目录数据")
                    )
                }
                val install = sandboxRunner.run(
                    command = listOf("npm", "install", "-g", npmPackage),
                    timeoutMs = RegistryMcpInstaller.NPM_INSTALL_TIMEOUT_MS
                )
                if (!install.success) {
                    return@withContext Result.failure(
                        Exception(
                            "沙箱内 npm install 失败（退出码 ${install.exitCode}）：" +
                                install.stderr.takeLast(400).trim().ifEmpty { "无 stderr 输出" }
                        )
                    )
                }
                val added = mcpManager.addServer(config)
                if (added.isFailure) {
                    // 回滚：不留「沙箱装了包但配置没落」的半残状态（取消语义保真）
                    var rollbackConfirmed = false
                    try {
                        rollbackConfirmed = sandboxRunner.run(
                            command = listOf("npm", "uninstall", "-g", npmPackage),
                            timeoutMs = RegistryMcpInstaller.NPM_UNINSTALL_TIMEOUT_MS
                        ).success
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // 回滚尽力而为：失败不掩盖主错误
                    }
                    return@withContext Result.failure(
                        Exception(
                            "配置写入失败" +
                                (if (rollbackConfirmed) "（npm 包已从沙箱卸载）" else "（回滚未确认）") +
                                "：" + (added.exceptionOrNull()?.message ?: "未知错误")
                        )
                    )
                }
                return@withContext Result.success(
                    "已安装 ${config.name}（npm 包 $npmPackage 已真实预装进沙箱，启动免冷启动）" +
                        "—— 安装 ≠ 启动，请在已配置列表中连接"
                )
            }
            // ⑥ uvx/python 等条目：运行时就绪，写配置（首启下载由运行时自管）
            mcpManager.addServer(config).map {
                "已添加 ${config.name}（首次启动时 ${entry.command} 会下载依赖，可能需要数分钟）" +
                    "—— 安装 ≠ 启动，请在已配置列表中连接"
            }
        }

    /**
     * 从 STDIO 命令行提取 npm 包名（npx 家族专用）：
     * 跳过命令本身与 `-`/`--` 旗标，第一个非旗标 token 即包名（保留
     * `@version` 尾缀 —— 预装与启动同版本，Issue #163 的版本锁定教训）。
     * 非 npx 家族（uvx/python/自定义二进制）返回 null。
     */
    internal fun extractNpmPackage(command: String?, args: List<String>): String? {
        val cmd = command?.trim()?.lowercase() ?: return null
        if (cmd !in NPM_COMMANDS) return null
        for (token in args) {
            val t = token.trim()
            if (t.isEmpty() || t.startsWith("-")) continue
            return t
        }
        return null
    }

    companion object {
        /** 目录条目 → 注册表配置（纯函数，便于单测；安装 ≠ 启动 → enabled 恒 false）。 */
        fun toServerConfig(entry: HubSource.HubMcpEntry): McpServerConfig = McpServerConfig(
            name = entry.name,
            url = entry.url,
            transport = when (entry.transport.uppercase()) {
                "STDIO" -> McpTransport.STDIO
                "SSE" -> McpTransport.SSE
                else -> McpTransport.HTTP
            },
            command = entry.command,
            args = entry.args,
            env = entry.env,
            runInSandbox = entry.runInSandbox,
            enabled = false,
            scope = entry.scope
        )

        /** 「首参是 npm 包名」语义的命令家族（与 NativeMcpEquivalents 同口径）。 */
        private val NPM_COMMANDS = setOf("npx", "npm", "node", "bunx")

        /** 沙箱运行时一键安装的预算（apt update + install，分钟级）。 */
        internal const val RUNTIME_INSTALL_TIMEOUT_MS = 600_000L

        /**
         * 缺失命令 → 一键安装的沙箱命令行（纯函数，单测钉死）：
         * - nodejs 家族（npx/npm/node/bunx）→ apt 装 nodejs + npm；
         * - python 家族 → apt 装 python3；
         * - 其余（uv 等 pip 形态）→ null（无可靠一键通道，文案引导手动装）。
         *
         * apt-get update 先行：rootfs 的 /var/lib/apt/lists 通常是空的，
         * 直接 install 会报「Unable to locate package」。
         */
        internal fun runtimeFixCommand(command: String): List<String>? {
            val name = command.trim().substringAfterLast('/').lowercase()
            return when {
                name in setOf("npx", "npm", "node", "bunx") -> listOf(
                    "sh", "-c", "apt-get update -qq && apt-get install -y -qq nodejs npm"
                )
                name in setOf("python", "python3") -> listOf(
                    "sh", "-c", "apt-get update -qq && apt-get install -y -qq python3"
                )
                else -> null
            }
        }
    }
}
