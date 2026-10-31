package com.apex.agent.core.tools.fusion

import com.apex.agent.core.tools.mcp.McpServerConfig
import com.apex.agent.core.tools.skill.SkillManifest

/**
 * # FusionBundle 激活器 —— 聚合的调度中枢
 *
 * 「聚而不同」的执行面：激活一个包 = 把包内声明的实体**分别**注入既有体系：
 * 1. 技能 → [FusionSkillInstaller.ensureInstalled]（SkillRegistry 原样安装，
 *    其依赖校验、autoSetup、SkillHotReloader 热注册全部自然生效）；
 * 2. MCP 服务器 → [FusionMcpConnector.ensureConnected]（McpManager 原样连接，
 *    其代数防竞态、McpToolRegistrar 一等工具化、看门狗全部自然生效）；
 * 3. 功能性插件 → [FusionPluginInstaller.ensureInstalled]（FunctionalPluginStore
 *    原样安装，其 id 命名规范/命名空间保留/版本门/贡献点形状全部自然生效）；
 * 4. agent 人设 → [FusionPromptInjector.apply]（引擎 system prompt 的
 *    Session Context 段注入 —— patchConfig 读改写，零新接缝）。
 *
 * 端口都是小接口（app 层 DI 适配真实子系统，单测手写 fake）——
 * 激活器本身对 SkillRegistry/McpManager/UnifiedPluginRegistry/ApexAgentEngine
 * **零编译依赖**，聚合语义可独立验证。
 *
 * 容错口径（聚合不脆弱）：单个组件失败进 [FusionActivation.skipped]，
 * 整体激活仍成功（连接器已连上、技能已装上、提示已注入的部分照常可用）；
 * manifest 不存在才是失败。
 */
class FusionBundleActivator(
    private val registry: FusionBundleRegistry,
    private val skillInstaller: FusionSkillInstaller,
    private val mcpConnector: FusionMcpConnector,
    private val promptInjector: FusionPromptInjector,
    private val pluginInstaller: FusionPluginInstaller = FusionPluginInstaller.UNAVAILABLE,
    private val hubInstaller: FusionHubInstaller = FusionHubInstaller.UNAVAILABLE,
    private val policyPort: FusionPolicyPort = FusionPolicyPort.UNAVAILABLE
) {

    /** 当前激活的包（null = 无）。@Volatile：UI 线程读、IO 协程写。 */
    @Volatile
    var activeBundle: FusionBundleManifest? = null
        private set

    /**
     * 激活包：安装技能 + 连接 MCP + 注入人设，返回激活报告。
     *
     * @param bundleId 包 id
     * @param scope 工位（"agent" | "coding"）—— 与包 scope 不符时失败
     *   （防 Coding 屏激活 agent 包之类的错位；"all" 包任意工位放行）
     */
    suspend fun activate(bundleId: String, scope: String = "agent"): Result<FusionActivation> {
        val manifest = registry.get(bundleId)
            ?: return Result.failure(Exception("FusionBundle '$bundleId' 未安装（可用 FusionList 查看已装包）"))
        if (manifest.scope != "all" && manifest.scope != scope) {
            return Result.failure(
                Exception("包 '$bundleId' 作用域为 ${manifest.scope}，当前工位 $scope 不适用")
            )
        }

        val skipped = mutableListOf<String>()

        // ── 阶段一：安装编排（内联技能 + 仓库 picks + 插件 + MCP 配置写盘）
        // —— 「用户要安装的时候调用」：激活前自动把缺的成员装齐（幂等）。
        val installed = installMembersInternal(manifest, skipped, connectMcp = true)

        // ── 阶段二：agent 人设：注入 system prompt（patchConfig 读改写；兼容性：
        // 注入异常折叠进 skipped 不弃激活 —— 技能/MCP 已就绪部分照常可用）──────
        val activationPrompt = buildActivationPrompt(manifest)
        val promptApplied = runCatching { promptInjector.apply(activationPrompt) }
        promptApplied.onFailure {
            skipped.add("prompt:工作流注入失败(${it.message ?: it::class.simpleName})")
        }

        // ── 阶段三：策略注册（因果规则硬轨生效）——互斥/预算过滤进激活集，
        // alwaysOn 常驻（pin 免淘汰）；注册失败不弃激活（软轨路由总纲已注入）。
        runCatching {
            policyPort.registerPolicies(manifest)
            val onIds = manifest.policies.alwaysOn.filter { it in installed.skills }
            if (onIds.isNotEmpty()) policyPort.pinAlwaysOn(onIds)
        }.onFailure {
            skipped.add("policies:策略注册失败(${it.message ?: it::class.simpleName})")
        }

        activeBundle = manifest

        return Result.success(
            FusionActivation(
                bundleId = manifest.id,
                bundleName = manifest.name,
                persona = manifest.agent.persona,
                activationPrompt = activationPrompt,
                installedSkills = installed.skills,
                connectedServers = installed.connected,
                installedPlugins = installed.plugins,
                toolHints = manifest.toolActivationHints,
                skipped = skipped
            )
        )
    }

    /**
     * **安装成员（不激活）**：把包内全部成员装到位 —— 内联技能装进
     * SkillRegistry、仓库 picks 从 hub 下载安装、功能性插件落盘激活、
     * 内联/引用 MCP 配置写入注册表。**不连接服务器、不注入人设、不改
     * activeBundle** —— 激活（[activate]）才做后三件事。
     *
     * 这是「专门安装 skills / MCP / 插件的聚合」的用户入口：装完即可
     * 在各自体系（技能开关 / MCP 市场 / 插件目录）看到成员，激活与否
     * 交由用户后续决定。
     *
     * @return 安装报告（成功清单 + skipped 失败明细，与激活报告同词表）
     */
    suspend fun installMembers(bundleId: String, scope: String = "agent"): Result<FusionInstallReport> {
        val manifest = registry.get(bundleId)
            ?: return Result.failure(Exception("FusionBundle '$bundleId' 未安装（可用 FusionList 查看已装包）"))
        if (manifest.scope != "all" && manifest.scope != scope) {
            return Result.failure(
                Exception("包 '$bundleId' 作用域为 ${manifest.scope}，当前工位 $scope 不适用")
            )
        }
        val skipped = mutableListOf<String>()
        val installed = installMembersInternal(manifest, skipped, connectMcp = false)
        return Result.success(
            FusionInstallReport(
                bundleId = manifest.id,
                bundleName = manifest.name,
                installedSkills = installed.skills,
                configuredServers = installed.configured,
                connectedServers = installed.connected,
                installedPlugins = installed.plugins,
                skipped = skipped
            )
        )
    }

    /** 安装编排的内部载体（activate 与 installMembers 共用）。 */
    private data class MemberInstallResult(
        val skills: List<String>,
        val configured: List<String>,
        val connected: List<String>,
        val plugins: List<String>
    )

    private suspend fun installMembersInternal(
        manifest: FusionBundleManifest,
        skipped: MutableList<String>,
        connectMcp: Boolean
    ): MemberInstallResult {
        val installedSkills = mutableListOf<String>()
        val configuredServers = mutableListOf<String>()
        val connectedServers = mutableListOf<String>()
        val installedPlugins = mutableListOf<String>()

        // ── 1. 内联技能：先查再装（幂等），装完入列 ──────────────────────
        for (ref in manifest.skills) {
            if (skillInstaller.isInstalled(ref.skillId)) {
                installedSkills.add(ref.skillId)
                continue
            }
            val manifestJson = FusionBundleManifest.json.encodeToString(
                SkillManifest.serializer(), ref.manifest
            )
            skillInstaller.ensureInstalled(manifestJson)
                .onSuccess { installedSkills.add(ref.skillId) }
                .onFailure {
                    skipped.add("skill:${ref.skillId}(${it.message ?: "安装失败"})")
                }
        }

        // ── 2. 仓库引用技能（skillPicks）：经 hub 端口按需下载安装 ─────────
        for (skillId in manifest.skillPicks) {
            if (skillInstaller.isInstalled(skillId)) {
                installedSkills.add(skillId)
                continue
            }
            hubInstaller.installSkill(skillId)
                .onSuccess { installedSkills.add(skillId) }
                .onFailure {
                    skipped.add("skill:$skillId(${it.message ?: "仓库安装失败"})")
                }
        }

        // ── 3. 内联 MCP：配置写盘（幂等）+ connectMcp 且 autoConnect 时逐台连接
        // （失败重试一次 —— 瞬态网络抖动 / 子进程启动慢不再直接弃包）────────
        for (ref in manifest.mcpServers) {
            val config = ref.toMcpServerConfig()
            if (ref.autoConnect && connectMcp) {
                val first = mcpConnector.ensureConnected(config)
                val attempt = if (first.isSuccess) first else {
                    kotlinx.coroutines.delay(MCP_RETRY_DELAY_MS)
                    mcpConnector.ensureConnected(config)
                }
                attempt
                    .onSuccess {
                        connectedServers.add(ref.serverId)
                        configuredServers.add(ref.serverId)
                    }
                    .onFailure {
                        skipped.add("mcp:${ref.serverId}(${it.message ?: "连接失败"})")
                    }
            } else {
                mcpConnector.ensureConfigured(config)
                    .onSuccess {
                        configuredServers.add(ref.serverId)
                        if (!ref.autoConnect) {
                            skipped.add("mcp:${ref.serverId}(声明为手动启动)")
                        }
                    }
                    .onFailure {
                        skipped.add("mcp:${ref.serverId}(${it.message ?: "配置写入失败"})")
                    }
            }
        }

        // ── 4. 仓库引用 MCP（mcpPicks）：经 hub 端口解析目录配置写入
        // （不在此连接 —— 引用形态是「装配置」，启动留给 activate 连接
        // 阶段语义或用户市场手动启动）────────────────────────────────────
        for (serverId in manifest.mcpPicks) {
            hubInstaller.installMcp(serverId)
                .onSuccess { configuredServers.add(serverId) }
                .onFailure {
                    skipped.add("mcp:$serverId(${it.message ?: "仓库安装失败"})")
                }
        }

        // ── 5. 功能性插件：先查再装（幂等）——安装校验全部落在端口侧
        // （FunctionalPluginStore 的 fail closed 链：id 规范/命名空间/版本门/
        // 贡献点形状），失败如实进 skipped 不弃整个包。
        for (ref in manifest.plugins) {
            if (pluginInstaller.isInstalled(ref.pluginId)) {
                installedPlugins.add(ref.pluginId)
                continue
            }
            pluginInstaller.ensureInstalled(ref.manifestJson())
                .onSuccess { installedPlugins.add(ref.pluginId) }
                .onFailure {
                    skipped.add("plugin:${ref.pluginId}(${it.message ?: "安装失败"})")
                }
        }

        return MemberInstallResult(
            skills = installedSkills,
            configured = configuredServers,
            connected = connectedServers,
            plugins = installedPlugins
        )
    }

    /**
     * 去激活：恢复引擎提示词（技能/MCP 留在各自体系里 —— 用户可在市场单独关停）。 */
    fun deactivate(): Result<Unit> {
        if (activeBundle == null) return Result.success(Unit)
        return try {
            promptInjector.restore()
            policyPort.unregisterPolicies(activeBundle!!.id)
            activeBundle = null
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(Exception("去激活失败: ${e.message}"))
        }
    }

    companion object {
        /** MCP 连接重试间隔（毫秒）—— 首试失败后的短暂冷却，容掉服务器启动/网络抖动。 */
        internal const val MCP_RETRY_DELAY_MS = 400L
    }

    /**
     * 组装激活提示词：人设 + 强制使用协议 + 路由 + 旗舰工具 + 证据纪律。
     * 结构化分段（## FusionBundle Agent / ## 强制使用协议 / ## 目标路由 /
     * ## 旗舰工具 / ## 证据纪律）—— 与 EnginePrompts 的段落风格对齐。
     *
     * 「强制使用协议」是用户钦定的行为契约：经输入栏王冠入口显式选用的
     * 体系，agent 必须优先且强制使用其资源完成任务，不得静默绕过
     * （与发送侧 /fusion:<id> 路由的【强制使用要求】双保险）。
     */
    internal fun buildActivationPrompt(manifest: FusionBundleManifest): String {
        val segments = mutableListOf<String>()
        segments.add(
            "## FusionBundle Agent：${manifest.name}" +
                (if (manifest.agent.persona.isNotBlank()) "（${manifest.agent.persona}）" else "")
        )
        segments.add(
            "### 强制使用协议\n" +
                "用户已显式选用本体系（FUSION 工具聚合 —— 技能 + MCP + 工具的聚合体）。" +
                "执行用户任务时必须优先且强制使用本包聚合的资源：包内技能的方法论、" +
                "其 MCP 服务器的工具与旗舰工具；不得静默绕过本体系改用其他工具完成" +
                "同一任务。仅当某资源确实不可用时，如实说明原因并给出降级证据后才能换路径。"
        )
        if (manifest.agent.activationPrompt.isNotBlank()) {
            segments.add(manifest.agent.activationPrompt.trim())
        }
        if (manifest.agent.routingHints.isNotBlank()) {
            segments.add("### 目标路由\n${manifest.agent.routingHints.trim()}")
        }
        if (manifest.toolActivationHints.isNotEmpty()) {
            segments.add(
                "### 旗舰工具（优先关注，其余经 ToolSearch 渐进披露）\n" +
                    manifest.toolActivationHints.joinToString("\n") { "- $it" }
            )
        }
        if (manifest.runtime.engines.isNotEmpty()) {
            val optionalEngines = manifest.runtime.engines.filter { it.optional }
            if (optionalEngines.isNotEmpty()) {
                segments.add(
                    "### 可选引擎（BYO 模型：缺失时对应工具会给出降级证据与安装指引）\n" +
                        optionalEngines.joinToString("\n") {
                            "- ${it.name}：${it.purpose}${if (it.installHint.isNotBlank()) "（${it.installHint}）" else ""}"
                        }
                )
            }
        }
        // 任务路由（软轨）：triggers 的 when 场景 → skillId 对照表 + 互斥组。
        // 结构化约束（互斥/预算）已由 SkillPolicyIndex 硬执行，这里把「因果」
        // 语义交给模型 —— 按任务对表选技能，配合 SkillActivate 激活。
        if (manifest.policies.triggers.any { it.scenario.isNotBlank() } ||
            manifest.policies.mutuallyExclusive.isNotEmpty()
        ) {
            val routeLines = manifest.policies.triggers
                .filter { it.scenario.isNotBlank() }
                .map { t -> "- 任务${t.scenario} → 优先激活技能 ${t.skillId}" }
            if (routeLines.isNotEmpty()) {
                segments.add(
                    "### 任务路由\n" +
                        routeLines.joinToString("\n") +
                        "\n（命中场景时先用 SkillActivate 激活对应技能再执行任务）"
                )
            }
            val exclusiveLines = manifest.policies.mutuallyExclusive.map { group ->
                "- ${group.joinToString(" 与 ")} 互斥：同时最多激活一个"
            }
            if (exclusiveLines.isNotEmpty()) {
                segments.add("### 互斥约束\n" + exclusiveLines.joinToString("\n"))
            }
        }
        segments.add(
            "### 证据纪律\n" +
                "每个结论都给出证据与限制：工具结果里的 findings/evidence/limitations " +
                "字段如实转述；不确定就标注不确定，绝不编造分析结果。"
        )
        return segments.joinToString("\n\n")
    }
}

/**
 * 安装成员报告（[FusionBundleActivator.installMembers] 返回）：
 * 与 [FusionActivation] 同词表但不含人设/激活态 —— 「只装不激活」的可观测面。
 */
data class FusionInstallReport(
    val bundleId: String,
    val bundleName: String,
    val installedSkills: List<String>,
    /** 配置写盘的 MCP（内联 autoConnect=false 与仓库 mcpPicks）—— 未连接。 */
    val configuredServers: List<String>,
    /** 激活路径已连接的 MCP（installMembers 恒空 —— 连接留给激活）。 */
    val connectedServers: List<String>,
    val installedPlugins: List<String>,
    val skipped: List<String>
)

/** 激活报告（UI 展示 + 引擎注入 + agent 工具返回共用）。 */
data class FusionActivation(
    val bundleId: String,
    val bundleName: String,
    val persona: String,
    val activationPrompt: String,
    val installedSkills: List<String>,
    val connectedServers: List<String>,
    /** 激活落位的功能性插件 id（plugins[] 聚合段）。 */
    val installedPlugins: List<String> = emptyList(),
    val toolHints: List<String>,
    /** 部分失败的组件（skill:xxx / mcp:xxx / plugin:xxx + 原因）—— 聚合容错的可观测面。 */
    val skipped: List<String>
)

/**
 * 模型侧激活结果的观察口（core 层接口，app 层控制面实现）。
 *
 * 语义：`FusionActivate` 工具激活/去激活成功后回灌 —— UI 横幅、持久化
 * 偏好与「输入栏按钮路径」保持同一激活面（两条入口，一个状态源）。
 * 默认空实现：单测与无 UI 场景零负担。
 */
interface FusionActivationSink {
    /** 模型侧激活成功（UI 同步 activeBundle + 持久化）。 */
    fun onActivated(activation: FusionActivation) {}

    /** 模型侧去激活成功（UI 清除 + 持久化移除）。 */
    fun onDeactivated(bundleId: String) {}
}

// ── 三个编排端口（app 层 DI 适配真实子系统；单测手写 fake）──────────────

/** 技能安装端口：适配 SkillRegistry.install / isInstalled。 */
interface FusionSkillInstaller {
    /** 技能是否已安装（幂等激活的第一道判断）。 */
    fun isInstalled(skillId: String): Boolean

    /** 安装技能 manifest JSON（失败语义走 Result）。 */
    suspend fun ensureInstalled(manifestJson: String): Result<Unit>
}

/** MCP 连接端口：适配 McpManager.ensureBuiltinServer + connect。 */
interface FusionMcpConnector {
    /** 确保服务器配置存在并已连接（BUILTIN 预置幂等 / 远程配置原样落盘）。 */
    suspend fun ensureConnected(config: McpServerConfig): Result<Unit>

    /**
     * 仅确保配置就绪（写盘/预置），**不连接** —— installMembers（只装不激活）
     * 路径用。默认委托 [ensureConnected]（既有 fake 零改动兼容；真实端口
     * 覆写为纯配置语义）。
     */
    suspend fun ensureConfigured(config: McpServerConfig): Result<Unit> = ensureConnected(config)
}

/** 引擎提示注入端口：适配 ApexAgentEngine.patchConfig 读改写。 */
interface FusionPromptInjector {
    /** 注入（或更新）激活段；实现方负责保留既有 additionalSystemContext。 */
    fun apply(segment: String)

    /** 恢复注入前的原始值。 */
    fun restore()
}

/**
 * 聚合策略端口（第六端口，**策略路由**的硬轨执行面）：激活时注册包策略
 * （互斥/预算/触发词进 SkillPolicyIndex）+ alwaysOn 常驻 pin；去激活注销。
 * [UNAVAILABLE] 缺省实现：策略仅软轨（路由总纲已注入提示词），硬约束不生效。
 */
interface FusionPolicyPort {
    /** 注册包策略（SkillPolicyIndex.register —— 全激活路径的互斥/预算过滤源）。 */
    fun registerPolicies(manifest: FusionBundleManifest)

    /** alwaysOn 常驻成员入激活集并 pin（免 FIFO 淘汰）。 */
    fun pinAlwaysOn(skillIds: List<String>)

    /** 去激活注销（索引移除 + pin 清除）。 */
    fun unregisterPolicies(bundleId: String)

    companion object {
        /** 未接线端口：注册/pin 静默直通（策略仅软轨生效）。 */
        val UNAVAILABLE: FusionPolicyPort = object : FusionPolicyPort {
            override fun registerPolicies(manifest: FusionBundleManifest) {}
            override fun pinAlwaysOn(skillIds: List<String>) {}
            override fun unregisterPolicies(bundleId: String) {}
        }
    }
}

/**
 * 功能性插件安装端口：适配 UnifiedPluginRegistry.installFunctionalPlugin。
 *
 * manifest JSON 的校验与落盘全部在端口实现侧（FunctionalPluginStore 的
 * fail closed 链）——激活器只编排，与技能/MCP 端口同一职责口径。
 * [UNAVAILABLE] 是缺省实现（单测 / 插件体系未接线的场景）：一切安装
 * 如实失败进 skipped，不静默假装成功。
 */
interface FusionPluginInstaller {
    /** 插件是否已安装（幂等激活的第一道判断）。 */
    fun isInstalled(pluginId: String): Boolean

    /** 安装插件 manifest JSON（失败语义走 Result）。 */
    suspend fun ensureInstalled(manifestJson: String): Result<Unit>

    companion object {
        /** 未接线端口：恒未安装 + 安装失败（诚实降级，不静默吞掉）。 */
        val UNAVAILABLE: FusionPluginInstaller = object : FusionPluginInstaller {
            override fun isInstalled(pluginId: String): Boolean = false
            override suspend fun ensureInstalled(manifestJson: String): Result<Unit> =
                Result.failure(Exception("插件端口未接线（宿主未装配 UnifiedPluginRegistry）"))
        }
    }
}

/**
 * 官方仓库安装端口（第五端口，**安装型聚合**的执行面）：把
 * `skillPicks`/`mcpPicks` 的引用 id 解析为真实安装 —— 技能从
 * apex-skill-hub 下载 manifest 装进 SkillRegistry，MCP 从 apex-mcp-hub
 * 目录解析配置写入注册表。
 *
 * 网络与仓库协议全部落在端口实现侧（app 层复用 HubSource 既有客户端）；
 * 激活器只编排（与四端口同一职责口径）。[UNAVAILABLE] 缺省实现：引用
 * 成员如实进 skipped（引用形态的包在未接线宿主上仍可激活内联成员）。
 */
interface FusionHubInstaller {
    /** 从官方技能仓库按 id 安装（下载 manifest → SkillRegistry.install）。 */
    suspend fun installSkill(skillId: String): Result<Unit>

    /** 从官方 MCP 仓库按目录名安装（解析条目 → addServer 配置写盘，不连接）。 */
    suspend fun installMcp(serverId: String): Result<Unit>

    companion object {
        /** 未接线端口：引用成员恒安装失败（诚实降级，不静默吞掉）。 */
        val UNAVAILABLE: FusionHubInstaller = object : FusionHubInstaller {
            override suspend fun installSkill(skillId: String): Result<Unit> =
                Result.failure(Exception("仓库端口未接线（无法安装技能 '$skillId'）"))

            override suspend fun installMcp(serverId: String): Result<Unit> =
                Result.failure(Exception("仓库端口未接线（无法安装 MCP '$serverId'）"))
        }
    }
}
