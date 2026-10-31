package com.apex.agent.plugin.api

/**
 * # 插件契约（宿主 ↔ 插件共享常量）
 *
 * AIDL（[IApexPlugin] / [IApexPluginHost]）只定义跨进程方法形状；版本与发现
 * 协议的**数值**统一锚定在这里——宿主与插件两侧引用同一常量，避免各自
 * 硬拷贝漂移（此前宿主侧从不消费 minHostVersion，插件侧散落硬编码 action
 * 字符串，本文件整体是死代码——现已激活为契约单一事实源）。
 *
 * 版本演进纪律：HOST_API_VERSION 只增不减（语义化主版本），插件用
 * [PluginMetadata.minHostVersion] 声明其所需的最低宿主 API 版本；宿主在
 * 注册插件工具前比对（PluginManager），不满足则拒绝注册并日志引导。
 */
object PluginContract {

    /**
     * 宿主插件 API 版本。绑定协议（attachHost 宿主桥）/ 工具清单 JSON 形状
     * 的当前版本——不满足插件声明 minHostVersion 的旧宿主不加载该插件。
     *
     * v2 双类型体系（[PluginKind]）：功能性插件（FUNCTIONAL）与 APK 插件
     * （APK）共用该版本号与 minHostVersion 门——两种形态演进（贡献点 schema、
     * 宏语言扩展）都只增不减，宿主按同一门拒绝过新的插件。
     */
    const val HOST_API_VERSION: Int = 2

    /** 插件发现 action（宿主 PackageManager queryIntentServices 与插件 service intent-filter 双侧约定）。 */
    const val ACTION_PLUGIN: String = "com.apex.agent.plugin.PLUGIN"

    /**
     * APK 插件 UI 入口声明（service meta-data 键，宿主 PackageManager 读取）：
     * 值为 JSON 数组 `[{"title":"控制台","action":"com.vendor.plugin.OPEN_UI"}]`
     * —— title 为市场插件行「打开页面」文案，action 为插件自有 Activity 的
     * intent action（宿主 startActivity 跳转，插件内自由渲染 Compose/WebView）。
     *
     * 信任门：入口只在与宿主同签名（[PluginManager] 签名门同源校验）时可见
     * ——UI 入口是插件的原生代码面，与工具注册同一信任边界。
     */
    const val UI_ENTRIES_META_DATA: String = "com.apex.agent.plugin.UI_ENTRIES"

    // ── Plugin System v2：功能性插件（FUNCTIONAL）契约常量 ──────────────
    // APK 插件的载体是 APK 自身（service intent-filter + metadata JSON）；
    // 功能性插件的载体是文件系统上的一个 manifest —— 数值锚定在这里，
    // 宿主（FunctionalPluginStore）与文档 / 工具生成器共享单一事实源。

    /** 功能性插件 manifest 文件名（安装目录 <filesDir>/plugins/functional/<id>/ 下的入口文件）。 */
    const val FUNCTIONAL_MANIFEST_FILE: String = "plugin.json"

    /** 功能性插件安装根目录名（filesDir 之下）。 */
    const val FUNCTIONAL_PLUGINS_DIR: String = "plugins/functional"

    /** 功能性插件禁用标记文件名（同目录存在即禁用；对齐技能 .disabled 语义）。 */
    const val FUNCTIONAL_DISABLED_MARKER: String = ".disabled"

    /**
     * 功能性插件宏工具的步数上限。宏是对既有宿主工具的组合调用——步数
     * 越多，单次工具调用的等待时长与 token 消耗越不可预期；16 步已覆盖
     * 「导航→抽取→整理→汇报」级别的现实工作流，超出按安装期校验拒绝
     * （fail closed：坏 manifest 不进安装目录）。
     */
    const val MACRO_MAX_STEPS: Int = 16

    /**
     * 宏步骤间变量（output 捕获的工具输出）的体积钳制。工具输出可能极大
     * （网页正文 / 文件内容），不钳制的变量会经插值把整个输出灌进下一步
     * 参数，token 爆炸 + 参数超限双双中招。截断保头尾（前 3KB + 后 1KB，
     * 中间折叠标记），保住「首部结论 + 尾部状态」的现实可用性。
     */
    const val MACRO_VARIABLE_MAX_BYTES: Int = 4 * 1024

    /**
     * 功能性插件 id 的合法形态：小写字母数字与点（至少一段，每段 1+ 字符）。
     * 形如 `functional.daily-report` / `com.vendor.kit`。命名规范是信任边界的
     * 第一层：功能性插件是用户/市场导入的数据，id 是它与内置能力（二进制
     * 工具、宿主目录）的全部命名关联——非法 id（大写、斜杠、空串、纯符号）
     * 在安装期直接拒绝，防止用 id 伪装目录穿越或冒充内置工具族。
     */
    val FUNCTIONAL_ID_PATTERN: Regex = Regex("^[a-z0-9]+(\\.[a-z0-9-]+)+$")
}

/**
 * 插件契约：所有插件APK必须实现的接口
 * 通过AIDL暴露给主APK
 */
interface ApexPluginService {
    fun getMetadata(): PluginMetadata
    fun getTools(): List<PluginToolDescriptor>
    suspend fun executeTool(toolId: String, arguments: String): String
    fun onActivate()
    fun onDeactivate()
}

data class PluginMetadata(
    val id: String,
    val name: String,
    val version: Int,
    val versionName: String,
    val minHostVersion: Int,
    val description: String
)

data class PluginToolDescriptor(
    val id: String,
    val name: String,
    val description: String,
    val parametersSchema: String
)
