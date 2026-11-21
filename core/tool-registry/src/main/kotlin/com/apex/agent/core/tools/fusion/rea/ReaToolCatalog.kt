package com.apex.agent.core.tools.fusion.rea

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * # REA 工具目录模型
 *
 * FusionBundle 首发内置包「REA · Reverse Engineer Anything」的 MCP 工具面。
 * 上游（morluto/rea 6.5.0，MIT）实测 tools/list = **207 个工具**；本目录
 * 逐名对齐（同名同域），能力面不缩水 —— 差异只在执行层：
 *
 * | 分层 | 语义 | 与上游对比 |
 * |---|---|---|
 * | [ReaTier.ON_DEVICE] | 纯 Kotlin 深实现（ASAR/ELF/PE/Mach-O/APK/AXML/dex/证据系统） | 上游同类工具无需引擎即可跑 —— 我们同样设备直跑 |
 * | [ReaTier.DEVICE_SHELL] | 本机 shell 执行（adb 族映射到设备本地 am/pm/dumpsys/logcat） | 上游需 PC + adb；我们跑在设备上 —— 原生更强 |
 * | [ReaTier.SANDBOX_BRIDGE] | PRoot Ubuntu 引擎桥（objdump/rizin/jadx/apktool/binwalk...） | 上游 BYO 引擎哲学一致：装了就能用，缺失降级给证据 |
 * | [ReaTier.WEB_FETCH] | 静态网络抓取分析（OkHttp 拉取 + 静态 JS 分析） | 上游经 CDP；我们静态子集 + 内置浏览器生态可后续桥接 |
 * | [ReaTier.PROVIDER_GATED] | 供应商门控（Hopper 分析桥 / 运行时 attach / 浏览器场景） | 上游同样需要外部供应商；缺失时结构化降级证据 |
 *
 * 工具描述保留上游英文原文首句（battle-tested 的模型契约），执行器按
 * 分层路由（[ReaEngine]）。参数面为家族级紧凑 schema（旗舰工具精确 schema）。
 */
enum class ReaDomain(val label: String) {
    SESSION("会话与证据"),
    ARTIFACT("归档与包清点"),
    NATIVE("原生二进制布局"),
    APPLE("Apple 平台"),
    MANAGED(".NET 托管"),
    ANDROID("Android 与 APK"),
    ADB("设备直连"),
    JS_ELECTRON("JavaScript 与 Electron"),
    WEB("网站与网络"),
    ENGINES("引擎桥"),
    HOPPER("原生分析器桥"),
    PROCESS("进程捕获与对账"),
    FIRMWARE("固件"),
    PROJECTIONS("应用图谱投影")
}

/** 执行分层（见 [ReaToolCatalog] KDoc 对照表）。 */
enum class ReaTier { ON_DEVICE, DEVICE_SHELL, SANDBOX_BRIDGE, WEB_FETCH, PROVIDER_GATED }

/** 单个工具规格：名称 + 域 + 分层 + 描述 + 紧凑参数面。 */
data class ReaToolSpec(
    val name: String,
    val domain: ReaDomain,
    val tier: ReaTier,
    val description: String,
    val args: List<ReaArg> = emptyList()
)

/** 紧凑参数声明（渲染 MCP inputSchema 用）。 */
data class ReaArg(
    val name: String,
    val type: String = "string",
    val required: Boolean = false,
    val description: String = ""
)

/** 目录对象：查询 + MCP tools/list 渲染 + 分层统计。 */
object ReaToolCatalog {

    /** 全量目录（数据表在 ReaToolCatalogData.kt —— 单文件预算拆分）。 */
    val ALL: List<ReaToolSpec> by lazy { buildReaCatalog() }

    private val byName: Map<String, ReaToolSpec> by lazy { ALL.associateBy { it.name } }

    fun find(name: String): ReaToolSpec? = byName[name]

    fun size(): Int = ALL.size

    /** 分层统计（binary_session / doctor 输出与 docs 能力矩阵同源）。 */
    fun tierCounts(): Map<ReaTier, Int> = ALL.groupingBy { it.tier }.eachCount()

    /** 域统计。 */
    fun domainCounts(): Map<ReaDomain, Int> = ALL.groupingBy { it.domain }.eachCount()

    /**
     * 渲染 MCP tools/list 的 tools 数组（name/description/inputSchema）。
     * 上游契约：tools/list 是权威完整清单 —— 207 个工具全量返回，客户端
     * 侧预算与渐进披露自行裁剪（AGA 引擎的 ToolRequestBudget 同款哲学）。
     */
    fun renderToolsList(): JsonArray = buildJsonArray {
        for (spec in ALL) {
            add(buildJsonObject {
                put("name", spec.name)
                put("description", spec.description)
                putJsonObject("inputSchema") {
                    put("type", "object")
                    put("properties", renderProperties(spec))
                    if (spec.args.any { it.required }) {
                        put("required", buildJsonArray {
                            spec.args.filter { it.required }.forEach { add(JsonPrimitive(it.name)) }
                        })
                    }
                }
            })
        }
    }

    private fun renderProperties(spec: ReaToolSpec): JsonObject = buildJsonObject {
        for (arg in spec.args) {
            put(arg.name, buildJsonObject {
                put("type", arg.type)
                if (arg.description.isNotBlank()) put("description", arg.description)
            })
        }
    }
}

/** 数据表构建助手（ReaToolCatalogData.kt 使用）。 */
internal fun spec(
    name: String,
    domain: ReaDomain,
    tier: ReaTier,
    description: String,
    args: List<ReaArg> = emptyList()
): ReaToolSpec = ReaToolSpec(name, domain, tier, description.trim(), args)

internal fun arg(
    name: String,
    type: String = "string",
    required: Boolean = false,
    description: String = ""
): ReaArg = ReaArg(name, type, required, description)

// ── 家族级紧凑参数面（数据表复用）────────────────────────────────────────

internal val ARGS_PATH = listOf(
    arg("path", required = true, description = "Local absolute path of the target")
)

internal val ARGS_PATH_OUT = listOf(
    arg("path", required = true, description = "Local absolute path of the input"),
    arg("output", required = false, description = "Local absolute path for results or extraction")
)

internal val ARGS_ADDRESS = listOf(
    arg("address", required = true, description = "Hexadecimal analysis address")
)

internal val ARGS_SYMBOL_OR_ADDRESS = listOf(
    arg("symbol", required = false, description = "Procedure symbol name"),
    arg("address", required = false, description = "Hexadecimal analysis address")
)

internal val ARGS_QUERY = listOf(
    arg("query", required = true, description = "Search query (literal by default)")
)

internal val ARGS_SERIAL = listOf(
    arg("serial", required = false, description = "Device serial (default: first attached device)")
)

internal val ARGS_RECORDS = listOf(
    arg("a", required = true, description = "First Evidence record or path"),
    arg("b", required = true, description = "Second Evidence record or path")
)
