package com.apex.agent.plugin.host

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.apex.agent.core.tools.AgentTool
import com.apex.agent.core.tools.ToolAnnotations
import com.apex.agent.core.tools.ToolCategory
import com.apex.agent.core.tools.ToolMetadata
import com.apex.agent.core.tools.ToolRegistry
import com.apex.agent.core.tools.ToolRisk
import com.apex.agent.plugin.api.IApexPlugin
import com.apex.agent.plugin.api.IApexPluginHost
import com.apex.agent.plugin.api.PluginContract
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 插件管理器
 * 负责发现、加载、管理插件APK
 *
 * ## v2 修复
 * - **bindService 返回值**：旧实现忽略返回值——绑定失败（插件被禁用/服务名错误）时
 *   无感知、无日志，ServiceConnection 对象从此泄漏。现在失败即记录并回收。
 * - **ServiceConnection 泄漏**：旧实现 `onServiceDisconnected`（插件进程死亡时回调）
 *   只从 loaded map 移除，之后 unloadPlugin 因 map 无条目提前 return，真正的
 *   unbind 永远不会发生——绑定泄漏到进程结束。现在 connection 独立登记在
 *   [connections]，卸载时按包名反查解绑，无论插件进程是否死亡。
 * - **registerPluginTools**：v2 曾记"未实现"日志（彼时 AIDL 未冻结、仅返回
 *   旧桩标记）；**v3 起真实注册**：attachHost 注入宿主桥 → 解析 getToolsJson →
 *   以 [PluginAgentTool] 桥接进 [ToolRegistry]（REPLACE 覆盖宿主同 id 工具）。
 *   卸载/插件进程死亡时降级为 [HostFallbackTool]（宿主直调），不挖空注册表。
 * - **API 33+ 弃用**：queryIntentServices 改用 ResolveInfoFlags 变体（旧行为保留）。
 *
 * ## v1.5.0 退役插件拦截门
 * 浏览器自动化已内置为宿主大功能（:platform:browser-agent，24 个 browser_*
 * 工具），旧网页自动化插件 APK（metadata.id = [RETIRED_WEB_AUTOMATION_PLUGIN_ID]）
 * 不再需要。已侧载旧插件的用户若不卸载，其 15 工具旧清单会在 REPLACE 语义下
 * 覆盖内置 24 工具（v1.1.0+ 的 pressKey/hover/drag/extractContent/executeJs/
 * getCookies/locate 等被挖掉，Agent 能力静默降级）——故在注册早期（metadata
 * 解析后、版本门之前）直接拒绝：日志警告引导卸载 + 解绑，不注册任何工具，
 * 与签名门同款 fail-closed 语义。
 *
 * ## v4 信任边界（第二轮加固）
 * 任意三方 App 声明 action `com.apex.agent.plugin.PLUGIN` 即被发现并绑定、
 * 工具风险由自报 id 字符串推断（`helper_query` → LOW/readOnly → 双门全过）
 * ——这是 P0 级注入面。现在加载链上有三道防线，全部 fail closed：
 * 1. **签名门（[isSignatureTrusted]）**：绑定回调时比对插件包与宿主自身的
 *    签名（GET_SIGNING_CERTIFICATES，API 26-27 回退 GET_SIGNATURES）。
 *    不一致（或校验本身失败）→ 拒绝注册工具 + 解绑。宿主与插件须同
 *    keystore 构建（同机 debug 构建天然满足）；非同签名插件默认禁用工具
 *    注册并日志警告——宿主内置工具不受影响（browser_* 留在注册表里，
 *    网页自动化经宿主直调照常工作）。
 *    未在插件 service 上加 signature 级自定义 permission：插件 APK 与宿主
 *    不保证同 keystore 产出（CI 只构建 :app），加权限会把"不注册工具"变成
 *    "绑定即失败"，故障面更大且无额外安全收益（签名门已覆盖同一威胁）。
 * 2. **版本门（[parseMinHostVersion]）**：注册前消费 getMetadataJson 的
 *    minHostVersion，与 [PluginContract.HOST_API_VERSION] 比对，不满足则
 *    拒绝注册 + 日志引导升级宿主（此前该字段零消费）。
 * 3. **metadata 钳制（[pluginToolMetadata]）**：即便通过了签名与版本门，
 *    插件工具的风险等级取 max(id 推断, MEDIUM, 宿主现有同 id 工具)，
 *    readOnlyHint 强制 false——REPLACE 覆盖内置工具时继承宿主分类与更严
 *    风险，防止"覆盖 Read 后降级成 LOW/只读"的绕过路径。
 */
@Singleton
class PluginManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val toolRegistry: ToolRegistry,
    /**
     * 宿主桥（app 的 PluginHostBridge 经 Hilt @Binds 提供）：插件经
     * [IApexPluginHost.executeHostTool] 回调宿主能力——browser_* 的实际逻辑
     * 在宿主进程 BrowserEngine 上，插件只声明与分发工具清单。
     */
    private val hostBridge: IApexPluginHost
) {

    private val _loadedPlugins = MutableStateFlow<Map<String, LoadedPlugin>>(emptyMap())
    val loadedPlugins: StateFlow<Map<String, LoadedPlugin>> = _loadedPlugins.asStateFlow()

    /** 包名 → 活跃 ServiceConnection（含插件进程已死亡但绑定仍在的，卸载时统一解绑）。 */
    private val connections = ConcurrentHashMap<String, ServiceConnection>()

    /**
     * 包名 → 该插件注册进 [ToolRegistry] 的工具描述符快照。
     * 卸载/插件死亡时据此降级为 [HostFallbackTool]（描述符原样保留，execute
     * 改走宿主直调）——插件工具与宿主内置工具同 id（REPLACE 覆盖），直接
     * unregister 会把工具位挖空，这是恢复安全网的数据来源。
     */
    private val pluginTools = ConcurrentHashMap<String, List<PluginToolDescriptorData>>()

    /** 签名门拒绝时延迟解绑用（onServiceConnected binder 回调内直接解绑有竞态，post 到主线程收尾）。 */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 插件工具清单解析（getToolsJson → 描述符）。宽松配置：字段缺失回退占位值。 */
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    data class LoadedPlugin(
        val packageName: String,
        val name: String,
        val connection: ServiceConnection,
        val binder: IBinder
    )

    /**
     * 发现已安装的Apex插件
     */
    fun discoverPlugins(): List<PluginInfo> {
        val intent = Intent(PluginContract.ACTION_PLUGIN)
        val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.queryIntentServices(
                intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.queryIntentServices(intent, PackageManager.MATCH_ALL)
        }

        return resolveInfos.mapNotNull { ri ->
            val si = ri.serviceInfo ?: return@mapNotNull null
            runCatching {
                PluginInfo(
                    packageName = si.packageName,
                    serviceName = si.name,
                    label = si.loadLabel(context.packageManager).toString()
                )
            }.getOrNull()
        }
    }

    // ── APK 插件 UI 入口（ui_entries meta-data，签名门同源）──────────

    /**
     * APK 插件声明的 UI 入口（service meta-data [PluginContract.UI_ENTRIES_META_DATA]）。
     * 解析失败 / 未声明返回空列表——UI 入口是可选贡献，永不影响插件工具链。
     */
    fun apkPluginUiEntries(packageName: String): List<PluginUiEntry> = runCatching {
        val pm = context.packageManager
        val intent = Intent(PluginContract.ACTION_PLUGIN).setPackage(packageName)
        val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentServices(
                intent,
                PackageManager.ResolveInfoFlags.of(
                    (PackageManager.MATCH_ALL or PackageManager.GET_META_DATA).toLong()
                )
            )
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentServices(intent, PackageManager.GET_META_DATA)
        }
        val si = resolveInfos.firstOrNull()?.serviceInfo ?: return emptyList()
        val raw = si.metaData?.getString(PluginContract.UI_ENTRIES_META_DATA) ?: return emptyList()
        parseUiEntries(raw)
    }.getOrDefault(emptyList())

    /**
     * 打开插件 UI 入口：startActivity 到插件自有 Activity（action 由插件
     * 声明，插件内自由渲染 Compose/WebView）。**签名门前置**——UI 入口是
     * 原生代码面，与工具注册同一信任边界；不可信签名返回 false 由调用方
     * 如实反馈（绝不静默吞掉跳转失败）。
     */
    fun openApkPluginUi(packageName: String, entry: PluginUiEntry): Boolean {
        if (!isSignatureTrusted(packageName)) {
            Log.w(TAG, "open plugin UI refused — signature mismatch: $packageName")
            return false
        }
        return runCatching {
            val intent = Intent(entry.action)
                .setPackage(packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        }.onFailure { Log.w(TAG, "open plugin UI failed for $packageName: ${it.message}") }
            .getOrDefault(false)
    }

    /** ui_entries meta-data JSON 解析：`[{"title":..,"action":..}]`，坏条目跳过。 */
    private fun parseUiEntries(raw: String): List<PluginUiEntry> = runCatching {
        (json.parseToJsonElement(raw) as? JsonArray)?.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val action = obj["action"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            if (title.isBlank() || action.isBlank()) return@mapNotNull null
            PluginUiEntry(title = title, action = action)
        } ?: emptyList()
    }.getOrDefault(emptyList())

    /**
     * 加载插件
     */
    fun loadPlugin(info: PluginInfo) {
        if (connections.containsKey(info.packageName)) return  // 已在加载/已加载

        val intent = Intent(PluginContract.ACTION_PLUGIN).apply {
            setClassName(info.packageName, info.serviceName)
        }

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                // 信任门 1（签名）：不一致 → 不注册工具、不进 loaded 表，post 解绑。
                if (!isSignatureTrusted(info.packageName)) {
                    Log.w(
                        TAG,
                        "plugin ${info.packageName} signature mismatch — tool registration refused " +
                            "and connection released (safe default: only plugins signed with the host " +
                            "key may inject tools; rebuild/install the plugin with the host keystore)"
                    )
                    mainHandler.post { unloadPlugin(info.packageName) }
                    return
                }
                _loadedPlugins.value = _loadedPlugins.value + (info.packageName to LoadedPlugin(
                    packageName = info.packageName,
                    name = info.label,
                    connection = this,
                    binder = binder
                ))

                // 将插件的工具注册到全局ToolRegistry（信任门 2：minHostVersion；
                // 信任门 3：metadata 钳制，均在 registerPluginTools 内）
                registerPluginTools(info.packageName, binder)
            }

            override fun onServiceDisconnected(name: ComponentName) {
                // 插件进程死亡：从已加载表移除，但绑定仍在——保留在 connections，
                // unloadPlugin 时仍可正确解绑（旧实现此处直接丢失 unbind 机会 → 泄漏）
                _loadedPlugins.value = _loadedPlugins.value - info.packageName
                // binder 已失效：插件工具立刻降级为宿主直调（同 id 不挖空）。
                // BIND_AUTO_CREATE 会重启插件进程，onServiceConnected 再次回调时
                // registerPluginTools 以 REPLACE 语义恢复插件直连版本。
                demotePluginToolsToHostFallback(info.packageName)
            }
        }

        connections[info.packageName] = connection
        val bound = runCatching {
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)

        if (!bound) {
            // 绑定失败（插件被禁用/服务组件名变更）：回收 connection，避免泄漏
            Log.w(TAG, "bindService failed for ${info.packageName} — 插件被禁用或服务不可用?")
            connections.remove(info.packageName)
        }
    }

    /**
     * 卸载插件
     */
    fun unloadPlugin(packageName: String) {
        // 先降级工具再解绑：unbindService 不会回调 onServiceDisconnected；demote 幂等
        // （pluginTools 已移除则空操作），此处顺序仅防御插件进程恰在此刻死亡的竞态。
        demotePluginToolsToHostFallback(packageName)
        connections.remove(packageName)?.let { conn ->
            runCatching { context.unbindService(conn) }
                .onFailure { Log.w(TAG, "unbindService $packageName: ${it.message}") }
        }
        _loadedPlugins.value = _loadedPlugins.value - packageName
    }

    /**
     * 把插件声明的工具真实注册进 [ToolRegistry]（v3 起落地，v4 加信任门）。
     *
     * 流程：binder 还原 [IApexPlugin] → **版本门**（getMetadataJson 的
     * minHostVersion 对比 [PluginContract.HOST_API_VERSION]，不满足拒绝注册
     * 并日志引导，也不注入宿主桥）→ attachHost 注入宿主桥（插件经
     * [IApexPluginHost] 回调宿主能力，如 browser_* 的 BrowserEngine 执行逻辑）→
     * 解析 getToolsJson → 逐个注册 [PluginAgentTool]（同 id 以 REPLACE 覆盖
     * 宿主内置工具，正是"网页自动化定位成插件"的载体；覆盖瞬间捕获宿主现有
     * metadata 作信任锚点）。attachHost 统一在此处调用且只调一次——插件
     * 进程重启重连时随本方法重新注入。
     */
    private fun registerPluginTools(pkg: String, binder: IBinder) {
        val plugin = IApexPlugin.Stub.asInterface(binder)
        runCatching {
            // 信任门 2（版本）：getMetadataJson 拿不到 / 非法 JSON → 拒绝（fail
            // closed）；字段缺失按 0（无要求，老插件兼容）。
            val metadataJson = plugin.getMetadataJson()

            // 退役插件门（v1.5.0）：旧网页自动化插件直接拒绝——功能已内置为宿主
            // 大功能，旧壳的 15 工具清单 REPLACE 覆盖会挖掉内置 24 工具。拒绝
            // 语义与签名门一致：不注册任何工具 + post 解绑，日志引导卸载。
            if (parsePluginId(metadataJson) == RETIRED_WEB_AUTOMATION_PLUGIN_ID) {
                Log.w(
                    TAG,
                    "plugin $pkg retired: 浏览器自动化已内置为宿主大功能，旧插件不再需要，建议卸载"
                )
                mainHandler.post { unloadPlugin(pkg) }
                return
            }

            val minHostVersion = parseMinHostVersion(metadataJson)
            if (minHostVersion == null) {
                Log.w(
                    TAG,
                    "plugin $pkg metadata not parseable — tool registration refused: $metadataJson"
                )
                return
            }
            if (minHostVersion > PluginContract.HOST_API_VERSION) {
                Log.w(
                    TAG,
                    "plugin $pkg requires host api >= $minHostVersion " +
                        "(host: ${PluginContract.HOST_API_VERSION}) — tool registration refused; " +
                        "upgrade the host app to load this plugin"
                )
                return
            }

            // 宿主桥 binder 传递：PluginHostBridge 即 IApexPluginHost.Stub（Binder 子类），
            // 插件侧 IApexPluginHost.Stub.asInterface() 自行还原为本地实现或代理。
            plugin.attachHost(hostBridge as IBinder)
            val toolsJson = plugin.getToolsJson()
            val descriptors = parseTools(toolsJson)
            val registered = descriptors.map { d ->
                // 覆盖瞬间捕获宿主同 id 工具的 metadata —— 降级/重连时的信任锚点
                // （风险取更严者、分类继承），防止插件把 Read 之类降成 LOW。
                val hostMetadata = toolRegistry.getTool(d.id)?.metadata
                val entry = d.copy(hostMetadata = hostMetadata)
                toolRegistry.register(PluginAgentTool(pkg, plugin, entry))
                entry
            }
            pluginTools[pkg] = registered
            Log.i(
                TAG,
                "plugin $pkg connected: registered ${registered.size} tools into ToolRegistry " +
                    "(${registered.joinToString { it.id }})"
            )
        }.onFailure {
            Log.w(TAG, "register plugin tools failed for $pkg: ${it.message}")
        }
    }

    /**
     * 插件不可用（卸载 / 进程死亡）时，把它的工具降级为宿主直调 fallback：
     * 描述符（id/name/description/parametersSchema）保留插件版原样，execute
     * 不再跨进程，直接经 [IApexPluginHost.executeHostTool] 调宿主实现。
     *
     * 这是"插件工具与宿主内置 browser_* 同 id（REPLACE 覆盖）"设计的回收安全网：
     * 只 unregister 会把工具位挖空；降级后工具依旧可用——BrowserAgentTools
     * 本就在宿主进程执行，插件只是声明/分发壳，两条路径殊途同归。
     */
    private fun demotePluginToolsToHostFallback(pkg: String) {
        val descriptors = pluginTools.remove(pkg) ?: return
        descriptors.forEach { d ->
            runCatching { toolRegistry.register(HostFallbackTool(d, hostBridge)) }
                .onFailure { Log.w(TAG, "demote tool ${d.id} to host fallback for $pkg: ${it.message}") }
        }
        Log.i(
            TAG,
            "plugin $pkg unavailable: ${descriptors.size} tools demoted to host fallback"
        )
    }

    // ── 信任门实现 ──────────────────────────────────────────────────────

    /**
     * 签名门：插件包签名集合与宿主自身签名集合完全一致才算可信。校验异常
     * （包已卸载 / PackageManager 拒绝）按不可信处理——门内失败 = 拒绝。
     */
    private fun isSignatureTrusted(pkg: String): Boolean = runCatching {
        val host = signatureFingerprints(context.packageName)
        val plugin = signatureFingerprints(pkg)
        host.isNotEmpty() && host == plugin
    }.getOrDefault(false)

    /** 包的签名指纹集合（Signature.toCharsString 十六进制串；无签名返回空集）。 */
    @Suppress("DEPRECATION") // getPackageInfo(String, Int) 自 API 33 被 GetPackageInfoRequest 变体取代（旧行为保留，minSdk 26 不引入新依赖）
    private fun signatureFingerprints(pkg: String): Set<String> {
        val pm = context.packageManager
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
                ?.apkContentsSigners
        } else {
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures
        }
        return signatures?.mapNotNull { it.toCharsString() }?.toSet() ?: emptySet()
    }

    /**
     * 版本门：解析插件 getMetadataJson 的 minHostVersion。
     * 非法 JSON / 非对象返回 null（调用方拒绝注册）；字段缺失按 0（无要求）。
     */
    private fun parseMinHostVersion(metadataJson: String): Int? = runCatching {
        val obj = json.parseToJsonElement(metadataJson) as? JsonObject
            ?: return@runCatching null
        obj["minHostVersion"]?.jsonPrimitive?.intOrNull ?: 0
    }.getOrNull()

    /**
     * 退役插件门：解析插件 getMetadataJson 的 id 字段。
     * 与 [parseMinHostVersion] 同口径——非法 JSON / 非对象 / 字段缺失返回 null
     * （不可解析的 metadata 本就会被版本门拒绝，此处 null 不等于任何退役 id）。
     */
    private fun parsePluginId(metadataJson: String): String? = runCatching {
        val obj = json.parseToJsonElement(metadataJson) as? JsonObject
            ?: return@runCatching null
        obj["id"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()

    /**
     * 解析插件 getToolsJson()：`[{"id":..,"name":..,"description":..,"parametersSchema":..}]`。
     * parametersSchema 是字符串字段（值为 JSON 文本本身）。字段缺失跳过该条目；
     * 整串非法回退空列表——绝不让插件的数据形态炸掉宿主注册流程。
     */
    private fun parseTools(toolsJson: String): List<PluginToolDescriptorData> = runCatching {
        val root = json.parseToJsonElement(toolsJson)
        (root as? JsonArray)?.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            PluginToolDescriptorData(
                id = id,
                name = obj["name"]?.jsonPrimitive?.contentOrNull ?: id,
                description = obj["description"]?.jsonPrimitive?.contentOrNull ?: id,
                parametersSchema = obj["parametersSchema"]?.jsonPrimitive?.contentOrNull
                    ?: EMPTY_TOOL_SCHEMA
            )
        } ?: emptyList()
    }.getOrDefault(emptyList())

    private companion object {
        const val TAG = "PluginManager"

        /** v1.5.0 退役的网页自动化插件 id（浏览器自动化已内置为宿主大功能，拦截防旧 15 工具清单覆盖内置 24 工具）。 */
        const val RETIRED_WEB_AUTOMATION_PLUGIN_ID = "com.apex.agent.plugin.webautomation"
    }
}

/** getToolsJson 条目（与 plugin-api 的 PluginToolDescriptor 字段对齐）。 */
private data class PluginToolDescriptorData(
    val id: String,
    val name: String,
    val description: String,
    val parametersSchema: String,
    /** 注册瞬间宿主注册表同 id 工具的 metadata（REPLACE 场景的信任锚点，可能为 null）。 */
    val hostMetadata: ToolMetadata? = null
)

/** 插件未提供 parametersSchema 时的占位（宽松 schema 导入下等价于"不校验"）。 */
private const val EMPTY_TOOL_SCHEMA = """{"type":"object","properties":{}}"""

/**
 * 插件工具的信任边界 metadata（[PluginAgentTool] / [HostFallbackTool] 共用）：
 * - **风险钳制**：max(id 推断, MEDIUM, 宿主现有) —— 插件自报的良性 id
 *   （`helper_query` 之类 → LOW）不再把风险压到确认链之外；REPLACE 覆盖
 *   宿主工具时取宿主与插件推断中更严者（覆盖 Read 不能把 HIGH/中风险
 *   降成 LOW）。
 * - **readOnlyHint 强制 false**：插件是跨进程第三方代码，PermissionDecider 的
 *   PLAN/DEFAULT 只读静默放行通道对其关闭，一律走确认链。
 * - **destructiveHint 按 id 保留推断**（write/delete 类语义对提示文案仍有价值）。
 * - **REPLACE 时分类继承宿主**（browser_* 保持 BROWSER 分组，提示与菜单不
 *   因插件接管而重组；openWorld 等注解也继承宿主，运行策略（超时/限流）
 *   不因插件接管漂移）；插件自有工具归 PLUGIN 分类、openWorld=true
 *   （跨进程第三方输出按不可信内容处理，对齐 McpAgentTool 口径）。
 */
private fun pluginToolMetadata(id: String, hostMetadata: ToolMetadata?): ToolMetadata {
    val inferred = ToolMetadata.infer(id)
    val risk = maxOf(
        inferred.risk,
        ToolRisk.MEDIUM,
        hostMetadata?.risk ?: ToolRisk.LOW
    )
    return ToolMetadata(
        id = id,
        category = hostMetadata?.category ?: ToolCategory.PLUGIN,
        risk = risk,
        tags = inferred.tags + listOf("plugin"),
        annotations = ToolAnnotations(
            readOnlyHint = false,
            destructiveHint = inferred.annotations.destructiveHint,
            idempotentHint = false,
            openWorldHint = hostMetadata?.annotations?.openWorldHint ?: true
        )
    )
}

/**
 * 插件工具的 [AgentTool] 适配器：元数据经 [pluginToolMetadata] 信任钳制
 * （风险 ≥ MEDIUM、非只读），execute 经 binder IPC 转发进插件进程
 * （[Dispatchers.IO] 上执行，RemoteException/插件侧死亡转成模型可读的
 * 错误字符串而非异常上抛）。
 */
private class PluginAgentTool(
    private val pluginPackage: String,
    private val plugin: IApexPlugin,
    private val descriptor: PluginToolDescriptorData
) : AgentTool {
    override val id get() = descriptor.id
    override val name get() = descriptor.name
    override val description get() = descriptor.description
    override val parametersSchema get() = descriptor.parametersSchema
    override val metadata: ToolMetadata =
        pluginToolMetadata(descriptor.id, descriptor.hostMetadata)

    override suspend fun execute(arguments: String): String = withContext(Dispatchers.IO) {
        runCatching { plugin.executeTool(descriptor.id, arguments) }
            .getOrElse { "Error: plugin '$pluginPackage' tool execution failed: ${it.message}" }
    }
}

/**
 * 宿主直调 fallback：插件卸载/死亡后顶替其工具位（[PluginManager] 注册），
 * 描述符保留插件版原样（id/name/description/parametersSchema 不变，模型
 * 感知不到切换），execute 不再跨进程，直接经 [IApexPluginHost.executeHostTool]
 * 调宿主实现——BrowserAgentTools 就在宿主进程，功能与插件模式等价。
 * metadata 同样经 [pluginToolMetadata] 钳制（描述符携带注册瞬间捕获的宿主
 * metadata 锚点，风险不因降级而放松）。
 */
private class HostFallbackTool(
    private val descriptor: PluginToolDescriptorData,
    private val host: IApexPluginHost
) : AgentTool {
    override val id get() = descriptor.id
    override val name get() = descriptor.name
    override val description get() = descriptor.description
    override val parametersSchema get() = descriptor.parametersSchema
    override val metadata: ToolMetadata =
        pluginToolMetadata(descriptor.id, descriptor.hostMetadata)

    override suspend fun execute(arguments: String): String = withContext(Dispatchers.IO) {
        runCatching { host.executeHostTool(descriptor.id, arguments) }
            .getOrElse { "Error: host fallback tool '${descriptor.id}' execution failed: ${it.message}" }
    }
}

data class PluginInfo(
    val packageName: String,
    val serviceName: String,
    val label: String
)

/** APK 插件 UI 入口（ui_entries meta-data 条目）：title 展示 / action 跳转。 */
data class PluginUiEntry(
    val title: String,
    val action: String
)
