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
 * 3. agent 人设 → [FusionPromptInjector.apply]（引擎 system prompt 的
 *    Session Context 段注入 —— patchConfig 读改写，零新接缝）。
 *
 * 三个端口都是小接口（app 层 DI 适配真实子系统，单测手写 fake）——
 * 激活器本身对 SkillRegistry/McpManager/ApexAgentEngine **零编译依赖**，
 * 聚合语义可独立验证。
 *
 * 容错口径（聚合不脆弱）：单个组件失败进 [FusionActivation.skipped]，
 * 整体激活仍成功（连接器已连上、技能已装上、提示已注入的部分照常可用）；
 * manifest 不存在才是失败。
 */
class FusionBundleActivator(
    private val registry: FusionBundleRegistry,
    private val skillInstaller: FusionSkillInstaller,
    private val mcpConnector: FusionMcpConnector,
    private val promptInjector: FusionPromptInjector
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

        val installedSkills = mutableListOf<String>()
        val connectedServers = mutableListOf<String>()
        val skipped = mutableListOf<String>()

        // ── 1. 技能：先查再装（幂等），装完入列 ──────────────────────────
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

        // ── 2. MCP 服务器：autoConnect 的逐台连接 ────────────────────────
        for (ref in manifest.mcpServers) {
            if (!ref.autoConnect) {
                skipped.add("mcp:${ref.serverId}(声明为手动启动)")
                continue
            }
            val serverConfig = ref.toMcpServerConfig()
            mcpConnector.ensureConnected(serverConfig)
                .onSuccess { connectedServers.add(ref.serverId) }
                .onFailure {
                    skipped.add("mcp:${ref.serverId}(${it.message ?: "连接失败"})")
                }
        }

        // ── 3. agent 人设：注入 system prompt（patchConfig 读改写）──────
        val activationPrompt = buildActivationPrompt(manifest)
        promptInjector.apply(activationPrompt)

        activeBundle = manifest

        return Result.success(
            FusionActivation(
                bundleId = manifest.id,
                bundleName = manifest.name,
                persona = manifest.agent.persona,
                activationPrompt = activationPrompt,
                installedSkills = installedSkills,
                connectedServers = connectedServers,
                toolHints = manifest.toolActivationHints,
                skipped = skipped
            )
        )
    }

    /** 去激活：恢复引擎提示词（技能/MCP 留在各自体系里 —— 用户可在市场单独关停）。 */
    fun deactivate(): Result<Unit> {
        if (activeBundle == null) return Result.success(Unit)
        return try {
            promptInjector.restore()
            activeBundle = null
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(Exception("去激活失败: ${e.message}"))
        }
    }

    /**
     * 组装激活提示词：人设 + 路由 + 旗舰工具 + 证据纪律。
     * 结构化分段（## FusionBundle Agent / ## 目标路由 / ## 旗舰工具 /
     * ## 证据纪律）—— 与 EnginePrompts 的段落风格对齐。
     */
    internal fun buildActivationPrompt(manifest: FusionBundleManifest): String {
        val segments = mutableListOf<String>()
        segments.add(
            "## FusionBundle Agent：${manifest.name}" +
                (if (manifest.agent.persona.isNotBlank()) "（${manifest.agent.persona}）" else "")
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
        segments.add(
            "### 证据纪律\n" +
                "每个结论都给出证据与限制：工具结果里的 findings/evidence/limitations " +
                "字段如实转述；不确定就标注不确定，绝不编造分析结果。"
        )
        return segments.joinToString("\n\n")
    }
}

/** 激活报告（UI 展示 + 引擎注入 + agent 工具返回共用）。 */
data class FusionActivation(
    val bundleId: String,
    val bundleName: String,
    val persona: String,
    val activationPrompt: String,
    val installedSkills: List<String>,
    val connectedServers: List<String>,
    val toolHints: List<String>,
    /** 部分失败的组件（skill:xxx / mcp:xxx + 原因）—— 聚合容错的可观测面。 */
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
}

/** 引擎提示注入端口：适配 ApexAgentEngine.patchConfig 读改写。 */
interface FusionPromptInjector {
    /** 注入（或更新）激活段；实现方负责保留既有 additionalSystemContext。 */
    fun apply(segment: String)

    /** 恢复注入前的原始值。 */
    fun restore()
}
