package com.apex.agent.core.tools.pluginhost

import kotlinx.serialization.Serializable

/**
 * # Native Plugin Host 2.0 — 统一插件核心模型（Phase 1）
 *
 * 把 Claude / OpenCode V1 / V2 / DSH / MCP / FUNCTIONAL / APK 各生态的插件
 * 元数据统一成一张 [PluginDescriptor] 描述符 —— 它回答四个问题：
 *
 * 1. **这是什么生态的插件**（[ecosystem] + [abi]）；
 * 2. **从哪来**（[source]，保留升级/回滚/卸载所需的来源信息）；
 * 3. **怎么跑**（[runtimeRequirements] + [entrypoints]）；
 * 4. **提供什么能力**（[declaredCapabilities]，供兼容性分析引擎判定）。
 *
 * 与既有体系的关系（AGENTS.md「薄包装不重写」纪律）：
 * - [com.apex.agent.core.tools.extension.ExtensionManifest] 仍是**安装账本**
 *   的主键模型（resolve→inspect→install 三步闸门不动）；
 * - 本描述符是**运行时视图**：安装完成后由 Plugin Host 加载器产出，
 *   驱动 Worker 启动、能力注册与生命周期管理；
 * - 二者经 [fromExtensionManifest] 单向映射，不建平行账本。
 */
@Serializable
data class PluginDescriptor(
    /** 稳定插件 id（与 ExtensionRegistry 账本 id 同源，如 `opencode.web-scraper`）。 */
    val id: String,
    val name: String,
    val version: String? = null,
    /** 插件生态 + ABI 版本（V1/V2 边界由加载器探测，不允许按包名猜）。 */
    val ecosystem: PluginEcosystem,
    val abi: PluginAbi = PluginAbi(),
    /** 来源（npm / git / local / url / catalog …，升级与卸载回溯用）。 */
    val source: PluginSource = PluginSource(),
    /** 插件入口（node 入口 / kotlin 类 / mcp 命令 …）。 */
    val entrypoints: List<PluginEntrypoint> = emptyList(),
    /** 依赖树（node_modules 包 / apt 包 / 宿主能力 …）。 */
    val dependencies: List<PluginDependency> = emptyList(),
    /** 插件声明提供的能力（工具/钩子/事件/存储…）。 */
    val declaredCapabilities: Set<PluginCapability> = emptySet(),
    /** 运行时需求（node 版本 / bun / python…，空 = 宿主原生 Kotlin）。 */
    val runtimeRequirements: Set<PluginRuntimeKind> = emptySet(),
    /** 原始清单保留（未知字段不丢弃 —— 官方协议升级时旧解析器不崩）。 */
    val rawManifestJson: String? = null
) {
    /** 宿主原生 Kotlin 插件（无外部运行时需求）。 */
    val isNativeRuntime: Boolean get() = runtimeRequirements.isEmpty()

    companion object {
        /**
         * 从安装账本的 ExtensionManifest 映射运行时描述符。
         *
         * 账本类型 → 生态的对应是**事实映射**（不是猜测）：claude_plugin →
         * CLAUDE；opencode_plugin → OPENCODE（V1/V2 待加载器探测，先标
         * [PluginAbi] 版本为 null）；dsh_plugin → DSH；mcp_connector → MCP；
         * apk_package → APK；skill / functional_plugin → 无运行时语义，
         * 返回 null（它们不进入 Plugin Host 生命周期，由各自原生注册表管理）。
         */
        fun fromExtensionManifest(
            manifest: com.apex.agent.core.tools.extension.ExtensionManifest
        ): PluginDescriptor? {
            val ecosystem = PluginEcosystem.fromExtensionType(manifest.type) ?: return null
            return PluginDescriptor(
                id = manifest.id,
                name = manifest.name,
                version = manifest.version,
                ecosystem = ecosystem,
                source = PluginSource(kind = manifest.sourceKind, location = manifest.spec),
                declaredCapabilities = PluginEcosystemMatrix.typicalCapabilities(ecosystem),
                runtimeRequirements = PluginEcosystemMatrix.typicalRuntimes(ecosystem)
            )
        }
    }
}

/**
 * 插件生态。OPENCODE 显式分 V1/V2 —— 二者是不同 ABI（SDK 形状、导出
 * 协议、事件集都不同），不允许把所有 opencode 插件解释成一种格式。
 */
@Serializable
enum class PluginEcosystem {
    /** Claude Code 插件（skills/commands/agents/hooks/.mcp.json/.lsp.json…）。 */
    CLAUDE,

    /** OpenCode V1 插件（早期 SDK：导出函数式 API）。 */
    OPENCODE_V1,

    /** OpenCode V2 插件（现行 SDK：Plugin.define + ctx 上下文 + hooks）。 */
    OPENCODE_V2,

    /** DSH 插件（dsh-* npm 前缀族）。 */
    DSH,

    /** MCP 连接器（STDIO/HTTP 服务进程，非 JS 模块插件）。 */
    MCP,

    /** 功能性插件（plugin.json 数据声明，宿主原生解释）。 */
    FUNCTIONAL,

    /** APK 代码插件（AIDL 跨进程，同签名门禁）。 */
    APK;

    companion object {
        /** 账本扩展类型 → 生态（skill/functional 无 Plugin Host 运行时语义 → null）。 */
        fun fromExtensionType(type: String): PluginEcosystem? = when (type) {
            com.apex.agent.core.tools.extension.ExtensionTypes.CLAUDE_PLUGIN -> CLAUDE
            com.apex.agent.core.tools.extension.ExtensionTypes.OPENCODE_PLUGIN -> OPENCODE_V2
            com.apex.agent.core.tools.extension.ExtensionTypes.DSH_PLUGIN -> DSH
            com.apex.agent.core.tools.extension.ExtensionTypes.MCP_CONNECTOR -> MCP
            com.apex.agent.core.tools.extension.ExtensionTypes.APK_PACKAGE -> APK
            else -> null
        }
    }
}

/**
 * 插件 ABI：生态内协议版本 + 模块格式。未知字段一律 null（诚实边界：
 * 「未探测」与「探测失败」都不允许伪装成某个已知版本）。
 */
@Serializable
data class PluginAbi(
    /** 生态内 ABI 版本（OpenCode 的 v1/v2、Claude 的 plugin manifest 版本…）。 */
    val version: String? = null,
    /** 模块格式（esm / cjs / ts / declarative …，null = 未声明）。 */
    val moduleFormat: String? = null
)

/** 插件来源（保留升级 / 回滚 / 卸载回溯所需的全部信息）。 */
@Serializable
data class PluginSource(
    /** 来源类别（与 ExtensionManifest.sourceKind 同词汇表）。 */
    val kind: String = com.apex.agent.core.tools.extension.SourceKinds.LOCAL,
    /** 定位串（npm 名 / git URL / 本地路径 / spec 原文）。 */
    val location: String = "",
    /** 锁定版本（git tag / commit / npm version range）。 */
    val pinnedRef: String? = null
)

/** 插件入口。 */
@Serializable
data class PluginEntrypoint(
    /** 入口类别（node-module / script / kotlin-class / mcp-command …）。 */
    val kind: String,
    /** 相对插件根的入口路径或符号名（经根目录约束检查后使用）。 */
    val path: String,
    /** 入口格式（esm / cjs / ts / shell / json …）。 */
    val format: String? = null
)

/** 插件依赖项。 */
@Serializable
data class PluginDependency(
    /** 依赖名（npm 包名 / apt 包名 / 宿主能力名）。 */
    val name: String,
    /** 版本约束（semver range / 精确版本；null = 未声明）。 */
    val versionSpec: String? = null,
    /** 该依赖运行在哪层（node / bun / apt / host …）。 */
    val runtime: String = PluginRuntimeKind.NODE.wireName,
    /** 可选依赖缺失时降级而不是失败。 */
    val optional: Boolean = false
)

/**
 * 插件能力面 —— 兼容性分析的最小单位。
 *
 * 注意与生态解耦：Claude 的 Hooks 与 OpenCode 的 Hooks 是**不同协议**，
 * 但对宿主而言都是「事件驱动的生命周期介入」这一能力槽位。统一的是
 * 能力槽位，不是协议本身（各生态适配器负责翻译）。
 */
@Serializable
enum class PluginCapability {
    /** 动态工具注册（进入 Agent ToolRegistry）。 */
    TOOLS,

    /** 事件钩子（Agent 生命周期介入：PreToolUse/PostToolUse/SessionStart…）。 */
    HOOKS,

    /** 事件订阅（插件监听宿主事件总线）。 */
    EVENTS,

    /** 插件私有存储（key-value，随卸载清理）。 */
    STORAGE,

    /** 上下文注入（插件向会话注入系统上下文）。 */
    CONTEXT,

    /** Skills（渐进披露的按需能力包）。 */
    SKILLS,

    /** 斜杠指令（宿主命令面）。 */
    COMMANDS,

    /** 子代理人设（subagent profile）。 */
    AGENTS,

    /** MCP 服务器声明（经宿主 McpManager 接管连接）。 */
    MCP_SERVERS,

    /** LSP 语言服务器声明。 */
    LSP,

    /** 终端 UI 扩展（Android 无终端宿主 —— 诚实标记不支持）。 */
    TUI,

    /** 桌面 GUI 扩展（Android 无桌面宿主 —— 诚实标记不支持）。 */
    GUI
}

/** 运行时种类（Worker 启动方式的判定依据）。 */
@Serializable
enum class PluginRuntimeKind(val wireName: String) {
    /** 宿主原生 Kotlin（同进程/同应用域，无 Worker）。 */
    NATIVE_KOTLIN("native-kotlin"),

    /** Node.js（Ubuntu/PRoot 沙箱内）。 */
    NODE("node"),

    /** Bun（OpenCode 官方运行时；Android 可用性须实际探测，不允许与 Node 混同）。 */
    BUN("bun"),

    /** Python（沙箱内 python3）。 */
    PYTHON("python"),

    /** Shell 脚本（沙箱内 sh/bash）。 */
    SHELL("shell"),

    /** MCP 服务进程（STDIO/HTTP，经 McpManager 管理）。 */
    MCP_PROCESS("mcp-process");
}

/**
 * 统一生命周期状态机（Phase 1 定义，Phase 3 的 PluginLifecycleManager 驱动）。
 *
 * 正常流：DISCOVERED → RESOLVED → INSPECTED → INSTALLED → PREPARED →
 * LOADING → ACTIVE → STOPPING → STOPPED。
 * 异常态：FAILED（加载/执行失败）、INCOMPATIBLE（兼容性分析不过）、
 * BLOCKED（权限未授予）、CRASHED（Worker 崩溃且未恢复）、DISABLED（用户禁用）。
 */
@Serializable
enum class PluginLifecycleState {
    DISCOVERED, RESOLVED, INSPECTED, INSTALLED, PREPARED, LOADING, ACTIVE,
    STOPPING, STOPPED, FAILED, INCOMPATIBLE, BLOCKED, CRASHED, DISABLED;

    /** 是否为终态（卸载/停用后不再流转）。 */
    val isTerminal: Boolean get() = this == STOPPED || this == FAILED ||
        this == INCOMPATIBLE || this == DISABLED

    /** 状态流转是否合法（防状态机被乱序调用打穿）。 */
    fun canTransitionTo(next: PluginLifecycleState): Boolean = when (this) {
        DISCOVERED -> next == RESOLVED || next == INCOMPATIBLE || next == FAILED
        RESOLVED -> next == INSPECTED || next == INCOMPATIBLE || next == FAILED
        INSPECTED -> next == INSTALLED || next == INCOMPATIBLE || next == FAILED
        INSTALLED -> next == PREPARED || next == DISABLED || next == FAILED
        PREPARED -> next == LOADING || next == DISABLED || next == FAILED || next == INCOMPATIBLE
        LOADING -> next == ACTIVE || next == FAILED || next == INCOMPATIBLE
        ACTIVE -> next == STOPPING || next == CRASHED || next == FAILED || next == DISABLED
        STOPPING -> next == STOPPED || next == FAILED
        STOPPED -> next == PREPARED || next == DISABLED
        CRASHED -> next == LOADING || next == FAILED || next == DISABLED
        // FAILED / INCOMPATIBLE / BLOCKED / DISABLED 允许重试、补授权或重新启用
        FAILED -> next == PREPARED || next == DISABLED
        INCOMPATIBLE -> next == DISABLED
        // BLOCKED = 权限未授予：授权后回 PREPARED 重走加载，或显式禁用
        BLOCKED -> next == PREPARED || next == DISABLED
        DISABLED -> next == PREPARED || next == STOPPED
    }
}
