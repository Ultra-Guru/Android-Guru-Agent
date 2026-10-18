package com.apex.agent.ui.screen.market

import com.apex.agent.core.tools.marketplace.MarketNames
import com.apex.agent.core.tools.marketplace.McpSoSource
import com.apex.agent.core.tools.marketplace.NativeMcpEquivalents
import com.apex.agent.core.tools.mcp.McpManager
import com.apex.agent.core.tools.mcp.McpSandboxRuntimeProbe.Verdict
import com.apex.agent.core.tools.mcp.McpTransport
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
 * ═══ mcp.so 社区目录控制器（市场 · MCP 页签）═══
 *
 * 从 [MarketViewModel] 拆出的独立状态域（与 [MarketHubController] 同构，
 * God-file 预算纪律）：
 * - **目录浏览**：`mcp.so/servers` 分页拉取（60 条/页，默认热门排序），
 *   「加载更多」翻页；返回不足一页即视为末页；
 * - **一键安装**（真化）：拉详情页 mcpServers 配置 → [McpSoSource.fetchServerConfig]
 *   统一解析 → 安装决策树（见 [installInternal]）—— 远端直写配置，
 *   STDIO 走环境门禁 + npx 真实预装，不再「只落 JSON 的假安装」。
 *
 * 错误契约：目录/安装失败均折叠进 [McpSoUiState.error] 文案（Result.failure
 * 消息），绝不上抛；安装成功经 [refreshMarket] 通知宿主 VM 刷新快照
 *（已装徽标 / 已配置服务器列表联动）。自持 SupervisorJob scope，
 * 安装失败不殊及宿主。
 */
@Singleton
class MarketMcpSoController @Inject constructor(
    private val mcpSoSource: McpSoSource,
    private val mcpManager: McpManager,
    /** MCP 市场诚实化：沙箱运行时探测（STDIO 安装门禁）。 */
    private val runtimeProbe: SandboxRuntimeProbe,
    /** MCP 市场诚实化：沙箱一次性命令执行器（npm install -g 真实预装）。 */
    private val sandboxRunner: ProotSandboxCommandRunner
) {
    /** mcp.so 目录的 UI 状态。 */
    data class McpSoUiState(
        val servers: List<McpSoSource.McpSoEntry> = emptyList(),
        val loading: Boolean = false,
        val loadingMore: Boolean = false,
        val error: String? = null,
        /** 下一页页码（1 起；0 = 未加载过）。 */
        val nextPage: Int = 0,
        val hasMore: Boolean = true,
        /** 行级 busy：正在拉配置安装的条目 slug。 */
        val installingSlug: String? = null
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _uiState = MutableStateFlow(McpSoUiState())
    val uiState: StateFlow<McpSoUiState> = _uiState.asStateFlow()

    /** 安装完成信号（宿主 VM 收集后刷新已装徽标与配置列表）。 */
    private val _refreshMarket = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val refreshMarket = _refreshMarket.asSharedFlow()

    /** 首屏加载（已有数据或加载中则跳过；force = 下拉刷新语义）。 */
    fun loadFirst(force: Boolean = false) {
        val current = _uiState.value
        if (!force && (current.loading || current.servers.isNotEmpty())) return
        scope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            mcpSoSource.listServers(page = 1).fold(
                onSuccess = { entries ->
                    _uiState.update {
                        it.copy(
                            servers = entries,
                            // 空目录页（首页即末页）或不足一页（60 条）均末页；
                            // P2-2：空页是合法末页，不再被上游误报为错误
                            hasMore = if (entries.isEmpty()) false
                                else entries.size >= PAGE_SIZE,
                            nextPage = 2
                        )
                    }
                },
                onFailure = { e ->
                    _uiState.update { it.copy(servers = emptyList(), error = e.message) }
                }
            )
            _uiState.update { it.copy(loading = false) }
        }
    }

    /** 加载下一页（追加；已是末页或加载中则跳过）。 */
    fun loadMore() {
        val current = _uiState.value
        if (current.loading || current.loadingMore || !current.hasMore) return
        val page = current.nextPage
        if (page < 1) return
        scope.launch {
            _uiState.update { it.copy(loadingMore = true, error = null) }
            mcpSoSource.listServers(page = page).fold(
                onSuccess = { entries ->
                    _uiState.update { state ->
                        val existing = state.servers.map { it.slug }.toSet()
                        val fresh = entries.filter { it.slug !in existing }
                        state.copy(
                            servers = state.servers + fresh,
                            // P2-2：翻到整数倍页边界后下一页是空目录页 ——
                            // 空且 success 即末页（hasMore=false），不再误报错误；
                            // 非空页保持整页（60 条）判定
                            hasMore = if (entries.isEmpty()) false
                                else entries.size >= PAGE_SIZE,
                            nextPage = page + 1
                        )
                    }
                },
                onFailure = { e ->
                    // 翻页失败不清空已加载列表，仅提示
                    _uiState.update { it.copy(error = e.message) }
                }
            )
            _uiState.update { it.copy(loadingMore = false) }
        }
    }

    /**
     * 安装一台 mcp.so 服务器（真化安装决策树）：
     *
     * 1. **名字级内置等价拦截**（fetch 前免网络往返）：目录名命中内置
     *    （如「Sequential Thinking」→ thinking）→ 拒绝安装，真内置不卖安装；
     * 2. **重复检查**（#346 归一化比较键）：跨源同名/变体拦截；
     * 3. **拉取详情页配置**：无标准 mcpServers 块 → 引导去详情页；
     * 4. **配置级内置等价拦截**（fetch 后按命令行查，比名字更准）；
     * 5. **远端条目**：写配置即真实可用（无本地依赖）；
     * 6. **STDIO 环境门禁**：rootfs 未装 / 运行时缺失 → 诚实失败
     *    （缺什么怎么补），不落必败配置；
     * 7. **STDIO · npx 条目**：沙箱内真实 `npm install -g <包>`（预装与
     *    启动同版本），配置写入失败回滚卸载 —— 与官方 Hub / Registry
     *    同源（旧实现只落 JSON 的「假安装」根治）；
     * 8. **STDIO · uvx/python 条目**：运行时就绪时写配置，文案注明
     *    「首启下载依赖」。
     *
     * 行级 busy（installingSlug）防并发。
     */
    fun installServer(entry: McpSoSource.McpSoEntry) {
        if (_uiState.value.installingSlug != null) return
        scope.launch {
            _uiState.update { it.copy(installingSlug = entry.slug) }
            try {
                installInternal(entry).fold(
                    onSuccess = { _refreshMarket.tryEmit(Unit) },
                    onFailure = { e -> _uiState.update { it.copy(error = e.message) } }
                )
                _refreshMarket.tryEmit(Unit)
            } finally {
                _uiState.update { it.copy(installingSlug = null) }
            }
        }
    }

    /** 安装决策树实现体（IO 线程；见 [installServer] KDoc 的八步清单）。 */
    private suspend fun installInternal(entry: McpSoSource.McpSoEntry): Result<Unit> =
        withContext(Dispatchers.IO) {
            // ① 名字级内置等价拦截（fetch 前免网络往返）
            NativeMcpEquivalents.builtinForName(entry.name)?.let { builtinId ->
                return@withContext Result.failure(
                    Exception(
                        "「${entry.name}」无需安装 —— App 已内置原生实现（$builtinId，" +
                            "进程内零依赖），到「当前工位已配置服务器」直接启动 $builtinId 即可"
                    )
                )
            }
            // ② 重复检查（#346：归一化比较键，跨源/大小写/分隔符变体折叠）
            val existing = mcpManager.getConfigs().firstOrNull {
                MarketNames.isSameServer(it.name, entry.name)
            }
            if (existing != null) {
                return@withContext Result.failure(
                    Exception(MarketNames.duplicateMessage(existing.name, entry.name))
                )
            }
            // ③ 详情页配置（无标准配置块 → 引导文案由 fetchServerConfig 给出）
            val config = mcpSoSource.fetchServerConfig(entry).getOrElse { e ->
                return@withContext Result.failure(Exception(e.message ?: "配置拉取失败"))
            }
            // ④ 配置级内置等价拦截（按命令行查，比目录名更准；仅 STDIO）
            if (config.transport == McpTransport.STDIO) {
                NativeMcpEquivalents.builtinFor(config.name, config.command, config.args)
                    ?.let { builtinId ->
                        return@withContext Result.failure(
                            Exception(
                                "「${entry.name}」无需安装 —— App 已内置原生实现（$builtinId，" +
                                    "进程内零依赖），到「当前工位已配置服务器」直接启动 $builtinId 即可"
                            )
                        )
                    }
            }
            // ⑤ 远端条目：无本地依赖，写配置即真实可用
            if (config.transport != McpTransport.STDIO) {
                return@withContext mcpManager.addServer(config).map { Unit }
            }
            // ⑥ STDIO 环境门禁：rootfs / 运行时缺失 → 诚实失败，不落必败配置
            val verdict = runtimeProbe.probe(config.command.orEmpty())
            if (verdict != Verdict.READY) {
                val hint = runtimeProbe.installHint(config.command.orEmpty())
                    ?: "参考该服务器的官方安装说明"
                return@withContext Result.failure(
                    Exception(
                        when (verdict) {
                            Verdict.ROOTFS_MISSING ->
                                "「${entry.name}」需要在 Ubuntu 沙箱内运行，但沙箱尚未安装 ——" +
                                    "请先到「终端」页安装 Ubuntu（TerminalUbuntuInstall）后再来安装"
                            else ->
                                "沙箱内未找到「${config.command.orEmpty()}」—— 请先在终端的" +
                                    "Ubuntu 环境里安装运行时（$hint）后再安装"
                        }
                    )
                )
            }
            // ⑦ npx 条目：真实 npm install -g 预装（免冷启动），写失败回滚
            val npmPackage = preinstallPackage(config.command, config.args)
            if (npmPackage != null) {
                if (!RegistryMcpInstaller.isSafeNpmIdentifier(npmPackage)) {
                    return@withContext Result.failure(
                        Exception(
                            "npm 包名形态非法（$npmPackage）—— 已拒绝自动安装，" +
                                "请到详情页核实配置：" + entry.detailUrl
                        )
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
                                install.stderr.takeLast(400).trim().ifEmpty { "无 stderr 输出" } +
                                "——请确认 Ubuntu 已安装 nodejs npm（apt install -y nodejs npm）"
                        )
                    )
                }
                val added = mcpManager.addServer(config)
                if (added.isFailure) {
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
                return@withContext Result.success(Unit)
            }
            // ⑧ uvx/python 等条目：运行时就绪，写配置（首启下载由运行时自管）
            mcpManager.addServer(config).map { Unit }
        }

    private companion object {
        /** mcp.so 每页固定 60 条（不足即末页判定依据）。 */
        const val PAGE_SIZE = 60
    }

    companion object {
        /**
         * 从 STDIO 配置的命令行提取待预装的 npm 包名（npx 家族专用，
         * 与 MarketHubController.extractNpmPackage 同语义）：
         * 跳过命令本身与 `-`/`--` 旗标，第一个非旗标 token 即包名（保留
         * `@version` 尾缀 —— 预装与启动同版本）。非 npx 家族返回 null。
         */
        internal fun preinstallPackage(command: String?, args: List<String>): String? {
            val cmd = command?.trim()?.lowercase() ?: return null
            if (cmd !in NPM_FAMILY) return null
            for (token in args) {
                val t = token.trim()
                if (t.isEmpty() || t.startsWith("-")) continue
                return t
            }
            return null
        }

        /** 「首参是 npm 包名」语义的命令家族（与 NativeMcpEquivalents 同口径）。 */
        private val NPM_FAMILY = setOf("npx", "npm", "node", "bunx")
    }
}
