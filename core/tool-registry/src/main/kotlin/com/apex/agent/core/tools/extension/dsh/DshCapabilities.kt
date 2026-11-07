package com.apex.agent.core.tools.extension.dsh

import kotlinx.serialization.Serializable

/**
 * # DshCapabilities —— DSH 插件能力模型 v2（Native Plugin Host 的统一能力面）
 *
 * 两阶段能力识别（DSHA 借鉴）的**调和产物**：
 * - Stage 1 静态分析（[com.apex.agent.core.tools.extension.dsh.DshArtifactAnalyzer]）
 *   → Declared（声明面：不执行插件代码）；
 * - Stage 2 动态验证（[com.apex.agent.core.tools.extension.dsh.DshPluginIntrospector]）
 *   → Observed（真实注册面：沙箱 shim 加载插件后捕获）；
 * - Reconciliation：静态说有而动态没观察到的 = 未证实，**一律不可用**（红线：
 *   不凭 package.json/静态分析注册虚构工具）。
 *
 * 诚实契约（brief 红线 11）：每一条能力都带 [DshToolCapability.available] /
 * [DshSkillCapability.available] / [DshUiCapability.available] 与不可用原因；
 * 消费方（市场 UI / DshToolRegistrar / Doctor）**必须**按 available 过滤，
 * 永不把 unsupported 的能力标成可用。
 *
 * 序列化纪律：ignoreUnknownKeys + 全字段缺省 —— DSH 处于 developer preview，
 * 宿主向前兼容（旧 capabilities.json 在新宿主可读，新字段不炸旧读方）。
 */
@Serializable
data class DshCapabilities(
    val schemaVersion: Int = SCHEMA_VERSION,
    /** 插件身份（真实版本回填自安装产物，非 spec 占位）。 */
    val plugin: PluginIdentity = PluginIdentity(),
    /** 兼容性判定（DshCompatibilityResolver 产出）。 */
    val compatibility: Compatibility = Compatibility(),
    /** 工具能力面（observed 为准；静态声明的-only 工具 available=false）。 */
    val tools: List<ToolCapability> = emptyList(),
    /** 技能能力面（静态文件可转换；动态 Provider 如实不可用）。 */
    val skills: List<SkillCapability> = emptyList(),
    /** UI 能力面（仅自包含静态 HTML 可用；slots/React 一律不支持）。 */
    val ui: List<UiCapability> = emptyList(),
    /** 服务级不可用清单（缺哪个 Cordis service、影响哪些工具）。 */
    val unavailable: List<UnavailableService> = emptyList(),
    /** 分析元信息（评分/指纹/版本 —— 重新分析可解释差异）。 */
    val analyzedAt: Long = 0L,
    val analyzerVersion: String = ANALYZER_VERSION,
    val inputFingerprint: String = ""
) {
    /** 可用工具数（市场能力计数 / registrar 注册范围）。 */
    val availableTools: List<ToolCapability> get() = tools.filter { it.available }

    /** 可用性汇总（人读一行，inspect/市场卡片共用）。 */
    fun summaryLine(): String =
        "tools ${availableTools.size}/${tools.size}, skills ${skills.count { it.available }}/${skills.size}, " +
            "ui ${ui.count { it.available }}/${ui.size}, unavailable ${unavailable.size}"

    companion object {
        const val SCHEMA_VERSION = 2

        /** 宿主分析器版本（算法/脚本变更即 bump —— capabilities 里留痕）。 */
        const val ANALYZER_VERSION = "1.0.0"
    }

    /** 插件身份：version 必须是安装产物的真实版本（绝不接受 0.0.0 占位入库）。 */
    @Serializable
    data class PluginIdentity(
        val id: String = "",
        val name: String = "",
        val version: String = "",
        /** 来源钉定信息（npm 精确版本+integrity / GitHub commit SHA）。 */
        val pinnedSource: String = "",
        /** 运行时形态标记（恒 "dsh-cordis" —— 类别见 DshArtifactAnalyzer.PackageClass）。 */
        val runtime: String = "dsh-cordis",
        /** 运行时版本（@deepseek-ai/dsh 依赖族解析结果）。 */
        val runtimeVersion: String = ""
    )

    /** 兼容性判定（级别 + 服务清单 —— unavailable[] 的上游依据）。 */
    @Serializable
    data class Compatibility(
        /** full / partial / minimal / incompatible（[Level] 常量）。 */
        val level: String = Level.PARTIAL,
        /** 插件声明依赖的 Cordis services（静态 inject + 动态探测并集）。 */
        val requiredServices: List<String> = emptyList(),
        /** 宿主 shim 实际可提供的服务。 */
        val supportedServices: List<String> = emptyList(),
        /** 声明了但宿主不提供的服务（unavailable[] 一一对应）。 */
        val unsupportedServices: List<String> = emptyList()
    ) {
        /** 兼容级别常量（诚实分档 —— 市场按级展示，不模糊化）。 */
        object Level {
            const val FULL = "full"
            const val PARTIAL = "partial"
            const val MINIMAL = "minimal"
            const val INCOMPATIBLE = "incompatible"
        }
    }

    /**
     * 工具能力。**execution 面的事实**：execute 是插件进程内的 JS 闭包，
     * 不能序列化搬运 —— capabilities 只携带元数据（schema/description），
     * 调用时由 Worker/冷执行器在同一插件加载上下文中真实执行（红线 7）。
     */
    @Serializable
    data class ToolCapability(
        /** 注册名（DSH ctx.tools.register 的 name —— 宿主桥工具 id 由此派生）。 */
        val name: String = "",
        val description: String = "",
        /** DSH parameters 自有 Schema → JSON Schema 白名单转换结果（null=转换失败）。 */
        val parametersSchema: String? = null,
        /** output.schema 原样保留（元数据展示；宿主不做输出强校验）。 */
        val outputSchema: String? = null,
        /** persistent-worker / cold-start（P0 门槛决定默认模式，插件级可覆盖）。 */
        val executionMode: String = MODE_COLD_START,
        /** 是否真实可用（observed 捕获 + schema 可转换 + 服务齐备）。 */
        val available: Boolean = false,
        /** 不可用原因（available=false 时必填 —— 市场逐工具展示）。 */
        val unavailableReason: String? = null
    ) {
        companion object {
            const val MODE_PERSISTENT = "persistent-worker"
            const val MODE_COLD_START = "cold-start"
        }
    }

    /** 技能能力：static-file 可经 DshSkillAdapter 转换；dynamic-provider 本期如实不可用。 */
    @Serializable
    data class SkillCapability(
        val kind: String = KIND_STATIC_FILE,
        val skillId: String = "",
        val title: String = "",
        val description: String = "",
        /** 包内相对路径（static-file）。 */
        val sourcePath: String? = null,
        val available: Boolean = false,
        val unavailableReason: String? = null
    ) {
        companion object {
            const val KIND_STATIC_FILE = "static-file"
            const val KIND_DYNAMIC_PROVIDER = "dynamic-provider"
        }
    }

    /** UI 能力：只有自包含静态 HTML 独立入口可用（无 DSH Client 依赖）。 */
    @Serializable
    data class UiCapability(
        /** 包内相对入口路径。 */
        val entry: String = "",
        val title: String = "",
        val selfContained: Boolean = false,
        val available: Boolean = false,
        val unavailableReason: String? = null
    )

    /** 服务级不可用（Cordis service 缺失 —— 影响面 = 依赖它的工具清单）。 */
    @Serializable
    data class UnavailableService(
        val service: String = "",
        val reason: String = "",
        /** 受影响工具名（能力图 P5 的影响分析数据源）。 */
        val affectedTools: List<String> = emptyList()
    )
}
