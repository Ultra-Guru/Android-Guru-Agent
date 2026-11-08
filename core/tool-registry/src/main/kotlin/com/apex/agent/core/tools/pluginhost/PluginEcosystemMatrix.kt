package com.apex.agent.core.tools.pluginhost

/**
 * # 生态能力支持矩阵（Phase 0 审计结论 —— 兼容性分析的单一事实源）
 *
 * 「能安装」不等于「能运行」：本矩阵逐生态记录宿主对每种**能力槽位**的
 * 真实支持状态，是 [PluginCompatibilityAnalyzer] 的判定依据。矩阵随
 * Plugin Host 2.0 各阶段 PR 落地而更新（PLANNED → PARTIAL/SUPPORTED），
 * **禁止**在能力未实现时标 SUPPORTED（spec 禁止事项 #11 的矩阵化落地）。
 *
 * 状态语义（对齐 spec §10.2）：
 * - SUPPORTED —— 官方 API 主要能力完整兼容（有测试锁）；
 * - PARTIAL —— 核心能力可用，部分非核心功能经转换/降级；
 * - PLANNED —— 架构已纳入（Phase 路线图明确），尚未实现 —— 分析报告
 *   必须如实展示「装了也用不上」，不允许静默吞掉；
 * - UNSUPPORTED —— 平台性不可行（Android 无终端/桌面宿主），或官方
 *   协议在 PRoot 环境验证失败；
 * - UNKNOWN —— 生态格式未审计完毕（如 DSH 的真实协议细节）。
 */
enum class CapabilitySupport {
    SUPPORTED, PARTIAL, PLANNED, UNSUPPORTED, UNKNOWN
}

/** 矩阵单元格：状态 + 人读原因 + 落地阶段标记。 */
data class EcosystemCapabilityStatus(
    val capability: PluginCapability,
    val support: CapabilitySupport,
    /** 为什么是这个状态（兼容性报告直接引用，不臆造）。 */
    val reason: String,
    /** 计划落地的 Phase（PLANNED 项的路线图锚点；已落地项为 null）。 */
    val plannedPhase: Int? = null
)

/**
 * 生态 × 能力 矩阵（Phase 0 审计基线，随各阶段 PR 更新）。
 */
object PluginEcosystemMatrix {

    // ── Claude Code 插件 ─────────────────────────────────────────────────
    // 审计结论（aa104f64 基线）：ClaudePluginImporter 已转换 skills /
    // commands / agents；hooks / .mcp.json / .lsp.json 未接入（Phase 5）。
    private val claude: Map<PluginCapability, EcosystemCapabilityStatus> = mapOf(
        PluginCapability.SKILLS to EcosystemCapabilityStatus(
            PluginCapability.SKILLS, CapabilitySupport.SUPPORTED,
            "ClaudePluginImporter 已把 SKILL.md + 资源目录转换入 SkillRegistry（ExtensionEcosystemsTest 锁）"
        ),
        PluginCapability.COMMANDS to EcosystemCapabilityStatus(
            PluginCapability.COMMANDS, CapabilitySupport.SUPPORTED,
            "commands 目录下的 .md 已映射为 FusionBundle 斜杠指令"
        ),
        PluginCapability.AGENTS to EcosystemCapabilityStatus(
            PluginCapability.AGENTS, CapabilitySupport.PARTIAL,
            "agents 目录合并为宿主人设/提示词；完整 Claude Subagent 生命周期未实现"
        ),
        PluginCapability.CONTEXT to EcosystemCapabilityStatus(
            PluginCapability.CONTEXT, CapabilitySupport.PARTIAL,
            "人设与提示词经 FusionBundle 注入；官方 prompt 模板变量仅部分映射"
        ),
        PluginCapability.HOOKS to EcosystemCapabilityStatus(
            PluginCapability.HOOKS, CapabilitySupport.PLANNED,
            "hooks.json 事件调度未实现（导入器当前跳过该文件）", plannedPhase = 5
        ),
        PluginCapability.MCP_SERVERS to EcosystemCapabilityStatus(
            PluginCapability.MCP_SERVERS, CapabilitySupport.PLANNED,
            "插件内 .mcp.json 未被导入链路解析（Phase 5 接入既有 McpManager）", plannedPhase = 5
        ),
        PluginCapability.LSP to EcosystemCapabilityStatus(
            PluginCapability.LSP, CapabilitySupport.PLANNED,
            ".lsp.json 未处理；语言服务器宿主属独立子系统", plannedPhase = 5
        ),
        PluginCapability.TOOLS to EcosystemCapabilityStatus(
            PluginCapability.TOOLS, CapabilitySupport.UNSUPPORTED,
            "Claude 插件官方格式不声明动态工具（经 skills/hooks/MCP 间接提供）"
        ),
        PluginCapability.TUI to EcosystemCapabilityStatus(
            PluginCapability.TUI, CapabilitySupport.UNSUPPORTED,
            "Android 无终端 UI 宿主"
        ),
        PluginCapability.GUI to EcosystemCapabilityStatus(
            PluginCapability.GUI, CapabilitySupport.UNSUPPORTED,
            "Android 无桌面 GUI 宿主（官方桌面专属属性忽略并如实报告）"
        )
    )

    // ── OpenCode 插件（V1/V2 同槽位矩阵，ABI 探测在加载器侧）────────────
    // 审计结论：安装链路（npm/github/local → PRoot）已通；ExtensionRun
    // 只做 node 入口执行，Plugin SDK 上下文/hooks/工具注册未实现（Phase 4）。
    private val opencodeCommon: Map<PluginCapability, EcosystemCapabilityStatus> = mapOf(
        PluginCapability.TOOLS to EcosystemCapabilityStatus(
            PluginCapability.TOOLS, CapabilitySupport.PLANNED,
            "插件工具注册到 Agent ToolRegistry 的桥未实现（ExtensionRun 仅执行 node 入口）", plannedPhase = 4
        ),
        PluginCapability.HOOKS to EcosystemCapabilityStatus(
            PluginCapability.HOOKS, CapabilitySupport.PLANNED,
            "官方 SDK Hooks 生命周期未实现", plannedPhase = 4
        ),
        PluginCapability.EVENTS to EcosystemCapabilityStatus(
            PluginCapability.EVENTS, CapabilitySupport.PLANNED,
            "插件事件订阅/分发未实现", plannedPhase = 4
        ),
        PluginCapability.STORAGE to EcosystemCapabilityStatus(
            PluginCapability.STORAGE, CapabilitySupport.PLANNED,
            "ctx.storage 桥接未实现", plannedPhase = 4
        ),
        PluginCapability.CONTEXT to EcosystemCapabilityStatus(
            PluginCapability.CONTEXT, CapabilitySupport.PLANNED,
            "插件上下文注入未实现", plannedPhase = 4
        ),
        PluginCapability.TUI to EcosystemCapabilityStatus(
            PluginCapability.TUI, CapabilitySupport.UNSUPPORTED,
            "OpenCode TUI 扩展依赖终端宿主，Android 平台不可行"
        ),
        PluginCapability.GUI to EcosystemCapabilityStatus(
            PluginCapability.GUI, CapabilitySupport.UNSUPPORTED,
            "OpenCode GUI 扩展依赖桌面宿主，Android 平台不可行"
        )
    )

    // ── DSH 插件 ──────────────────────────────────────────────────────────
    private val dsh: Map<PluginCapability, EcosystemCapabilityStatus> = mapOf(
        PluginCapability.TOOLS to EcosystemCapabilityStatus(
            PluginCapability.TOOLS, CapabilitySupport.UNKNOWN,
            "DSH 真实插件协议未完成审计（dsh-* 前缀族仅安装链路已通）", plannedPhase = 6
        ),
        PluginCapability.HOOKS to EcosystemCapabilityStatus(
            PluginCapability.HOOKS, CapabilitySupport.UNKNOWN,
            "DSH 钩子协议未审计", plannedPhase = 6
        ),
        PluginCapability.EVENTS to EcosystemCapabilityStatus(
            PluginCapability.EVENTS, CapabilitySupport.UNKNOWN,
            "DSH 事件协议未审计", plannedPhase = 6
        )
    )

    // ── MCP 连接器 ────────────────────────────────────────────────────────
    // 审计结论：McpManager + McpToolRegistrar 已是一等工具注册（Tool System v4）。
    private val mcp: Map<PluginCapability, EcosystemCapabilityStatus> = mapOf(
        PluginCapability.TOOLS to EcosystemCapabilityStatus(
            PluginCapability.TOOLS, CapabilitySupport.SUPPORTED,
            "McpToolRegistrar 把远端工具注册为一等 AgentTool（v4 目录 + 渐进披露）"
        ),
        PluginCapability.STORAGE to EcosystemCapabilityStatus(
            PluginCapability.STORAGE, CapabilitySupport.PARTIAL,
            "MCP resources 只读桥已具备；写语义按协议能力如实报告"
        )
    )

    // ── 功能性插件（plugin.json 数据声明）────────────────────────────────
    private val functional: Map<PluginCapability, EcosystemCapabilityStatus> = mapOf(
        PluginCapability.TOOLS to EcosystemCapabilityStatus(
            PluginCapability.TOOLS, CapabilitySupport.SUPPORTED,
            "宏工具经 MacroToolAdapter 注册（数据永不 REPLACE 内置工具，51 项契约锁）"
        ),
        PluginCapability.COMMANDS to EcosystemCapabilityStatus(
            PluginCapability.COMMANDS, CapabilitySupport.SUPPORTED,
            "斜杠指令贡献已实现（plugin-system-v2）"
        ),
        PluginCapability.CONTEXT to EcosystemCapabilityStatus(
            PluginCapability.CONTEXT, CapabilitySupport.PARTIAL,
            "提示词注入已实现；作用域受 coding/agent 工位隔离约束"
        )
    )

    // ── APK 代码插件（AIDL）──────────────────────────────────────────────
    private val apk: Map<PluginCapability, EcosystemCapabilityStatus> = mapOf(
        PluginCapability.TOOLS to EcosystemCapabilityStatus(
            PluginCapability.TOOLS, CapabilitySupport.SUPPORTED,
            "PluginAgentTool 经 AIDL 注册（同签名门禁 + REPLACE 语义）"
        )
    )

    private val matrices: Map<PluginEcosystem, Map<PluginCapability, EcosystemCapabilityStatus>> = mapOf(
        PluginEcosystem.CLAUDE to claude,
        PluginEcosystem.OPENCODE_V1 to opencodeCommon,
        PluginEcosystem.OPENCODE_V2 to opencodeCommon,
        PluginEcosystem.DSH to dsh,
        PluginEcosystem.MCP to mcp,
        PluginEcosystem.FUNCTIONAL to functional,
        PluginEcosystem.APK to apk
    )

    /** 查矩阵：生态未声明该能力 → null（生态格式里根本没有这个槽位）。 */
    fun statusOf(ecosystem: PluginEcosystem, capability: PluginCapability): EcosystemCapabilityStatus? =
        matrices[ecosystem]?.get(capability)

    /**
     * 生态典型能力面（fromExtensionManifest 的默认声明集）。
     *
     * 刻意剔除 UNSUPPORTED 槽位：TUI/GUI 是生态「可声明但 Android 平台
     * 不可行」的槽位 —— 未显式声明它们的插件不应被误判 F；显式声明
     * （描述符 declaredCapabilities 含 TUI/GUI）才会触发 F 路径。
     */
    fun typicalCapabilities(ecosystem: PluginEcosystem): Set<PluginCapability> =
        matrices[ecosystem]?.values
            ?.filter { it.support != CapabilitySupport.UNSUPPORTED }
            ?.map { it.capability }
            ?.toSet()
            ?: emptySet()

    /** 生态典型运行时需求（Worker 启动方式判定）。 */
    fun typicalRuntimes(ecosystem: PluginEcosystem): Set<PluginRuntimeKind> = when (ecosystem) {
        // 审计基线：skills/commands/agents 为宿主侧转换（无沙箱运行时需求）；
        // hooks（shell 执行）与 .mcp.json（MCP 进程）的运行时需求由 Phase 5
        // 的加载器按插件实际声明的能力条件注入，不在生态层一刀切。
        PluginEcosystem.CLAUDE -> emptySet()
        PluginEcosystem.OPENCODE_V1, PluginEcosystem.OPENCODE_V2, PluginEcosystem.DSH ->
            // 官方运行环境以 Bun 为重要基础，Android 侧 Node 为已验证路径；
            // 二者不可混同（RuntimeProvider 分别探测，Phase 2 落地）。
            // 注：两个候选并非「同时必需」——任一可用即具备执行前提，
            // 缺失方在报告中如实降级说明。
            setOf(PluginRuntimeKind.NODE, PluginRuntimeKind.BUN)
        PluginEcosystem.MCP -> setOf(PluginRuntimeKind.MCP_PROCESS)
        PluginEcosystem.FUNCTIONAL, PluginEcosystem.APK -> setOf(PluginRuntimeKind.NATIVE_KOTLIN)
    }
}
