package com.apex.agent.plugin.host.functional

import com.apex.agent.core.tools.AgentTool
import com.apex.agent.core.tools.DuplicateToolIdPolicy
import com.apex.agent.core.tools.ToolExecutor
import com.apex.agent.core.tools.ToolRegistry
import com.apex.agent.plugin.api.PluginKind
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * # 统一插件注册表（Plugin System v2 门面）
 *
 * 双类型插件体系的**宿主侧唯一入口**：市场 UI / 斜杠菜单 / 启动接线都
 * 只认本类，不感知形态差异（[PluginKind]）。
 *
 * ```
 *                       ┌────────────────────────────┐
 *  市场 / 斜杠 / 服务 ──▶│   UnifiedPluginRegistry    │
 *                       └────┬───────────────┬───────┘
 *              FUNCTIONAL    │               │        APK
 *        ┌───────────────────▼──┐   ┌────────▼──────────────┐
 *        │ FunctionalPluginStore │   │ ApkPluginSource       │
 *        │ (plugin.json 目录)    │   │ (PluginManager 门面)  │
 *        │ MacroToolAdapter      │   │ AIDL / 签名门         │
 *        └──────────────────────┘   └───────────────────────┘
 * ```
 *
 * ## 职责
 * - **目录**：[catalog] 把两类插件合并成统一条目流（APK 侧只收「已加载」
 *   ——发现扫描是 PackageManager IPC，由市场浏览页按需自调）；
 * - **激活 / 去激活**（仅功能性插件）：工具宏以 **REJECT 策略**注册进
 *   [ToolRegistry]——功能性插件是数据，永不 REPLACE 二进制能力（对照
 *   APK 插件同签名信任下的 REPLACE 语义）；禁用 / 卸载 / 升级时按注册
 *   快照精确回收，不挖别人的工具位；
 * - **启停装卸**：对 [FunctionalPluginStore] 的带状态补偿封装（改标记 →
 *   激活 / 去激活 → 刷新目录，三步一体，调用方不感知中间态）；
 * - **斜杠指令**：[functionalSlashCommands] 输出已激活插件的指令贡献
 *   快照（[changes] 作菜单重建信号）。
 *
 * ## 线程模型
 * 公开方法非 suspend（内部注册表自带同步；目录快照是 StateFlow 原子替换）。
 * 调用方纪律：IO 型操作（install / uninstall / refresh）在 Dispatchers.IO
 * 上调（对齐 MarketViewModel 对 PluginManager 的既有约定）；启停切换可
 * 在主线程（纯内存 + 标记文件小写入）。
 *
 * ## 失败语义
 * 每一步失败都如实呈现：激活拒绝（工具 id 冲突）记入条目 [UnifiedPluginEntry.detail]
 * 并照常激活其余贡献物（单贡献物隔离）；store 操作失败原样上抛返回值，
 * 绝不静默吞掉（对齐 AGENTS.md 防御式 IO——异常折叠但留痕可归因）。
 *
 * 纯 JVM（无 Android 依赖）——测试用手写 fake（[ApkPluginSource] /
 * [ToolExecutor]），对齐仓库「无 mock 框架」纪律。生产装配见 app 层
 * PluginModule（store 根目录 / 日志桥 / [MacroStepExecutor] 执行器）。
 */
class UnifiedPluginRegistry(
    private val store: FunctionalPluginStore,
    private val apkSource: ApkPluginSource,
    private val toolRegistry: ToolRegistry,
    @MacroStepExecutor private val stepExecutor: ToolExecutor,
    private val scope: CoroutineScope? = null,
    private val logger: ((level: FunctionalPluginStore.Level, message: String) -> Unit)? = null
) {

    /** 当前已激活的功能性插件：插件 id → 它注册进 ToolRegistry 的工具快照（去激活的回收清单）。 */
    private val activatedTools = ConcurrentHashMap<String, List<AgentTool>>()

    /** 激活过程中被 REJECT 的工具冲突记录（插件 id → 冲突工具 id 列表），进目录 detail。 */
    private val activationConflicts = ConcurrentHashMap<String, MutableList<String>>()

    private val _catalog = MutableStateFlow<List<UnifiedPluginEntry>>(emptyList())

    /** 统一插件目录（市场「已安装 · 插件」页数据源）。 */
    val catalog: StateFlow<List<UnifiedPluginEntry>> = _catalog.asStateFlow()

    /** 变更信号（任何装卸 / 启停 / 激活去激活递增）——斜杠菜单等快照消费者的重建 key。 */
    private val _changes = MutableStateFlow(0L)

    val changes: StateFlow<Long> = _changes.asStateFlow()

    init {
        // APK 侧加载态变化（绑定回调 / 进程死亡 / 卸载）→ 目录即时对账。
        // scope 为注入的专用后台 scope（SupervisorJob + IO，见 app 层装配）；
        // 测试传 null 跳过（目录由 refresh / 激活路径显式重建）。冷启动时
        // loadedPlugins 已是终值，collect 只对后续变化生效。
        scope?.launch {
            apkSource.loadedApkPlugins.collect { refresh() }
        }
    }

    // ── 目录 ─────────────────────────────────────────────────────────

    /**
     * 重建统一目录（功能性插件目录扫描 + APK 已加载快照合并）。幂等。
     * 扫描是磁盘 IO——调用方应在 IO 调度器上执行（见类 KDoc 线程模型）。
     */
    fun refresh() {
        val functional = store.list().map { stored ->
            val manifest = stored.manifest
            if (manifest == null) {
                UnifiedPluginEntry(
                    id = stored.dirName,
                    kind = PluginKind.FUNCTIONAL,
                    name = stored.displayName,
                    description = "",
                    version = "-",
                    state = UnifiedPluginState.BROKEN,
                    enabled = stored.enabled,
                    detail = stored.brokenReason
                )
            } else {
                val active = activatedTools.containsKey(manifest.id)
                UnifiedPluginEntry(
                    id = manifest.id,
                    kind = PluginKind.FUNCTIONAL,
                    name = manifest.displayName,
                    description = manifest.description,
                    version = manifest.displayVersion,
                    state = when {
                        active -> UnifiedPluginState.ACTIVE
                        stored.brokenReason != null -> UnifiedPluginState.BROKEN
                        else -> UnifiedPluginState.INSTALLED
                    },
                    enabled = stored.enabled,
                    detail = activationConflicts[manifest.id]?.takeIf { it.isNotEmpty() }
                        ?.joinToString(prefix = "工具 id 冲突被拒：", separator = ", ")
                        ?: stored.brokenReason
                )
            }
        }
        val apk = apkSource.loadedApkPlugins.value.map { (pkg, label) ->
            UnifiedPluginEntry(
                id = pkg,
                kind = PluginKind.APK,
                name = label,
                description = "",
                version = "-",
                state = UnifiedPluginState.ACTIVE
            )
        }
        _catalog.value = functional + apk
    }

    // ── 激活 / 去激活（功能性插件） ──────────────────────────────────

    /**
     * 启动接线：激活全部「已启用且数据合规」的功能性插件（幂等——已激活
     * 的跳过）。由宿主前台服务在 IO 调度器上调用（对齐 APK 插件
     * discoverPlugins().forEach(loadPlugin) 的自动加载哲学：安装即生效）。
     *
     * @return 本次新激活的插件 id 列表（诊断 / 日志用）。
     */
    fun activateEnabledFunctionalPlugins(): List<String> {
        val activated = mutableListOf<String>()
        for (stored in store.list()) {
            val manifest = stored.activatableManifest ?: continue
            if (activatedTools.containsKey(manifest.id)) continue
            if (activateInternal(manifest.id, manifest)) activated += manifest.id
        }
        if (activated.isNotEmpty()) {
            refresh()
            info("startup activated ${activated.size} functional plugins: ${activated.joinToString()}")
        }
        return activated
    }

    /**
     * 单插件激活（启停开关打开 / 安装后的自动激活）。数据不合规 / 已
     * 激活 / manifest 缺失 → false（如实反馈，不静默）。
     */
    fun activateFunctional(id: String): Boolean {
        if (activatedTools.containsKey(id)) return true
        val manifest = store.manifestOf(id) ?: return false
        // 运行期复核（manifest 可能被热通道改写）：身份 + 贡献点形状双门。
        val stored = store.list().firstOrNull { it.dirName == id }
        if (stored?.brokenReason != null || stored?.manifest?.id != id) return false
        val ok = activateInternal(id, manifest)
        refresh()
        return ok
    }

    /**
     * 去激活：按注册快照精确 unregister 本插件注册的工具（禁用 / 卸载 /
     * 升级前回收），幂等。绝不 unregister 非本插件注册的同 id 工具位
     * （REJECT 策略保证注册时就没覆盖过任何人的工具）。
     */
    fun deactivateFunctional(id: String) {
        val tools = activatedTools.remove(id) ?: return
        tools.forEach { tool -> toolRegistry.unregister(tool.id) }
        activationConflicts.remove(id)
        refresh()
        info("functional plugin '$id' deactivated (${tools.size} tools unregistered)")
    }

    // ── 启停 / 装卸（带状态补偿的一体化操作） ───────────────────────

    /**
     * 启停切换：标记文件 + 激活/去激活 + 目录刷新三步一体。
     * @return false = 插件不存在（未知 id）。
     */
    fun setFunctionalEnabled(id: String, enabled: Boolean): Boolean {
        if (!store.setEnabled(id, enabled)) return false
        if (enabled) activateFunctional(id) else deactivateFunctional(id)
        bump()
        return true
    }

    /**
     * 安装（或升级）功能性插件：store 落盘 → 默认启用态下立即激活 → 刷新。
     * 返回 store 的 [FunctionalPluginStore.InstallResult]（拒绝原因原样上抛）。
     */
    fun installFunctionalPlugin(manifestJson: String): FunctionalPluginStore.InstallResult {
        val result = store.install(manifestJson)
        when (result) {
            is FunctionalPluginStore.InstallResult.Installed -> {
                activateFunctional(result.manifest.id)
                bump()
            }
            is FunctionalPluginStore.InstallResult.Upgraded -> {
                // 升级 = 旧贡献物全部回收后按新 manifest 重放（REJECT 快照
                // 先 unregister，避免旧工具残留在注册表里冒充新版语义）。
                deactivateFunctional(result.manifest.id)
                activateFunctional(result.manifest.id)
                bump()
            }
            is FunctionalPluginStore.InstallResult.Unchanged,
            is FunctionalPluginStore.InstallResult.Rejected -> Unit
        }
        return result
    }

    /**
     * 卸载：去激活（工具回收）→ 目录删除 → 刷新。
     * @return false = 插件不存在 / 目录删除失败（如实反馈）。
     */
    fun uninstallFunctionalPlugin(id: String): Boolean {
        deactivateFunctional(id)
        val ok = store.uninstall(id)
        refresh()
        bump()
        return ok
    }

    // ── 斜杠指令贡献 ─────────────────────────────────────────────────

    /**
     * 已激活功能性插件的斜杠指令快照（斜杠菜单插件类目数据源）。
     * 只读内存态（activatedTools 的 keyset + manifest 重读），无 IO。
     */
    fun functionalSlashCommands(): List<FunctionalSlashCommand> {
        val commands = mutableListOf<FunctionalSlashCommand>()
        for (pluginId in activatedTools.keys) {
            val manifest = store.manifestOf(pluginId) ?: continue
            manifest.contributions.slashCommands.forEach { contribution ->
                commands += FunctionalSlashCommand(
                    commandId = contribution.id,
                    pluginId = pluginId,
                    pluginName = manifest.displayName,
                    title = contribution.displayTitle,
                    description = contribution.description.orEmpty(),
                    prompt = contribution.prompt
                )
            }
        }
        return commands.sortedBy { it.pluginName }
    }

    // ── 私有实现 ─────────────────────────────────────────────────────

    /**
     * 激活核心：逐贡献物构造 [MacroToolAdapter] 注册（REJECT）。单个工具
     * id 冲突 → 记入 [activationConflicts] 跳过，其余照常（单贡献物隔离，
     * 不因一个冲突废掉整个插件）。全失败（无任何贡献物注册成功）→ false。
     */
    private fun activateInternal(id: String, manifest: PluginManifest): Boolean {
        val registered = mutableListOf<AgentTool>()
        val conflicts = mutableListOf<String>()
        manifest.contributions.tools.forEach { contribution ->
            val tool = MacroToolAdapter(
                pluginId = id,
                contribution = contribution,
                scope = manifest.scope.orEmpty(),
                executor = stepExecutor,
                registry = toolRegistry
            )
            // REJECT：功能性插件永不覆盖既有工具位（数据 ≠ 代码；APK 插件
            // 的 REPLACE 是同签名信任的特权，见类 KDoc 信任边界对照）。
            // REJECT 的返回值无法区分成功/冲突（两者都返回 null）——注册后
            // 身份比对是唯一可靠判据。
            toolRegistry.register(tool, DuplicateToolIdPolicy.REJECT)
            if (toolRegistry.getTool(tool.id) === tool) {
                registered += tool
            } else {
                conflicts += tool.id
                warn("functional plugin '$id' tool '${tool.id}' rejected — id already registered")
            }
        }
        activatedTools[id] = registered
        if (conflicts.isNotEmpty()) activationConflicts[id] = conflicts
        info(
            "functional plugin '$id' activated: ${registered.size} tools registered" +
                (if (conflicts.isEmpty()) "" else ", ${conflicts.size} rejected (id conflict)")
        )
        return registered.isNotEmpty() || manifest.contributions.slashCommands.isNotEmpty()
    }

    private fun bump() {
        _changes.value = _changes.value + 1L
    }

    private fun info(message: String) {
        logger?.invoke(FunctionalPluginStore.Level.INFO, message)
    }

    private fun warn(message: String) {
        logger?.invoke(FunctionalPluginStore.Level.WARN, message)
    }
}
