package com.apex.agent.ui.component

import android.util.Log
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Api
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Link
import androidx.compose.ui.graphics.vector.ImageVector
import com.apex.agent.core.tools.connector.ConnectorRegistry
import com.apex.agent.core.tools.mcp.McpManager
import com.apex.agent.core.tools.skill.SkillMenuProvider
import com.apex.agent.core.tools.skill.SkillRegistry
import com.apex.agent.plugin.host.PluginManager
import com.apex.agent.plugin.host.functional.UnifiedPluginRegistry
import com.apex.agent.ui.language.LanguageManager
import com.apex.agent.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 斜杠指令菜单的统一数据源（成品版 v2）
 *
 * 从 SkillRegistry / McpManager / PluginManager / ConnectorRegistry 实时聚合菜单数据。
 *
 * ## v2 修复
 * - **异步构建**：旧实现在字段初始化器里同步执行 `buildMenu()`（SkillRegistry 懒加载
 *   全目录扫盘 + McpManager 读盘 + discoverPlugins 跨进程 PackageManager 查询），
 *   而本类在首屏进入聊天页时于主线程被 Hilt 构造 → 首帧卡顿/ANR。现在初始值是
 *   空菜单，首次构建放到后台协程。
 * - **变更自动刷新**：旧实现只在插件加载/手动 refresh 时重建——市场页安装技能、
 *   开关 MCP、增删连接器后回到聊天页，菜单仍是旧快照。现在聚合各注册表的
 *   changes 流 + 插件 loadedPlugins，任一变化自动重建（300ms 去抖合并洪峰）。
 * - **插件发现缓存**：discoverPlugins 是跨进程 IPC + loadLabel 资源加载，
 *   旧实现每次 buildMenu 全量执行。现在 10 秒 TTL 缓存。
 * - **MCP enabled 过滤**：禁用的 MCP 服务器不再出现在菜单（旧实现全量展示）。
 * - **连接器真数据源**：从 [ConnectorRegistry] 读取启用的连接器，替代硬编码示例。
 * - **状态结构化**：技能安装状态由 [com.apex.agent.core.tools.skill.SkillMenuItem.installed]
 *   显式携带，替代旧 `label.contains("未安装")` 字符串协议。
 */
@OptIn(kotlinx.coroutines.FlowPreview::class)
@Singleton
class SlashMenuProvider @Inject constructor(
    private val skills: SkillMenuProvider,
    private val skillRegistry: SkillRegistry,
    private val mcpManager: McpManager,
    private val pluginManager: PluginManager,
    // Plugin System v2：功能性插件的斜杠指令贡献（已激活插件快照）。
    private val unifiedPluginRegistry: UnifiedPluginRegistry,
    private val connectorRegistry: ConnectorRegistry,
    // i18n：菜单分组/提示文案按当前语言取词（组合外场景）
    private val languageManager: LanguageManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // v2：初始为空菜单，首建移到后台协程（构造在主线程发生，绝不能扫盘/IPC）
    private val _menu = MutableStateFlow(SlashMenuData(emptyList()))
    val menu: StateFlow<SlashMenuData> = _menu.asStateFlow()

    // 插件发现缓存（跨进程 PackageManager IPC + loadLabel 资源加载都很贵）
    @Volatile private var pluginCache: List<com.apex.agent.plugin.host.PluginInfo> = emptyList()
    @Volatile private var pluginCacheAt = 0L

    init {
        // 首次构建：后台执行（旧实现在主线程构造期同步跑）
        scope.launch { rebuild() }

        // 聚合各注册表的变更流：技能安装/开关、MCP 配置/连接状态、连接器增删开关。
        // 任一变化 → 重建菜单；300ms 去抖把洪峰（如批量安装）合并为一次重建。
        combine(
            skillRegistry.changes,
            mcpManager.changes,
            connectorRegistry.changes
        ) { _, _, _ -> Unit }
            .debounce(300)
            .onEach { rebuild() }
            .launchIn(scope)

        // 插件加载/卸载时自动刷新菜单（PluginManager.loadedPlugins 为 StateFlow）
        pluginManager.loadedPlugins
            .onEach {
                pluginCacheAt = 0L // 插件加载状态变化，强制下次重建时重新发现
                rebuild()
            }
            .launchIn(scope)

        // Plugin System v2：功能性插件启停/装卸/激活去激活 → 斜杠指令贡献
        // 变化（changes 递增计数）→ 菜单重建（与去抖洪峰合并到同一次重建）。
        unifiedPluginRegistry.changes
            .drop(1) // StateFlow 首帧是初值 0，跳过避免无谓重建
            .debounce(300)
            .onEach { rebuild() }
            .launchIn(scope)
    }

    /** 在 MCP 连接状态变化后手动触发全量刷新。 */
    fun refresh() {
        scope.launch { rebuild() }
    }

    private suspend fun rebuild() {
        val data = withContext(Dispatchers.Default) { buildMenu() }
        _menu.value = data
    }

    private fun buildMenu(): SlashMenuData {
        return SlashMenuData(
            categories = listOf(
                buildSkillsCategory(),
                buildMcpCategory(),
                buildPluginsCategory(),
                buildConnectorsCategory()
            )
        )
    }

    // ── Skills：只展示「已安装且已启用」的技能 ──
    // Hub 生态门控（产品要求）：斜杠菜单的 skills 分组 = 市场安装 + 开启，
    // 二者缺一不可——未安装的目录条目不出现（去市场装），装了没开的也不出现
    // （去市场/已安装页开）。旧版还并列 5 个 legacy 内置模板（未安装也展示、
    // 状态 NOT_INSTALLED），现在全部移除——模板安装走市场页，菜单只留真货。
    // #197：逐项携带工位作用域（manifest.scope；缺省 all）——消费方按屏过滤。
    // per-scope 启停：逐项携带生效工位集合（enabledScopes = manifest 声明 ∩
    // 双工位启用）——Agent 屏与 Coding 屏的 / 菜单各归各（修「两屏同步显示」），
    // 市场里只在 Coding 分级开启的技能不再出现在 Agent 屏菜单。
    // 就绪门禁：必填配置未填齐的技能不进菜单（SkillMenuProvider 过滤，
    // 配置保存后 registry 发 changes 自动重建）。
    private fun buildSkillsCategory(): SlashMenuCategory {
        val active = skills.getActiveSkills()
        val scopeById = skillRegistry.getInstalled()
            .associate { it.manifest.id to it.manifest.scope }
        val items = active.map { skill ->
            SlashMenuItem(
                label = skill.label,
                command = skill.command,
                description = skill.description,
                status = SlashItemStatus.READY,
                scope = scopeById[skill.id] ?: "all",
                enabledScopes = skill.enabledScopes
            )
        }
        return SlashMenuCategory(
            id = "skills",
            title = "Skills",
            icon = Icons.Default.Extension,
            items = items,
            badge = if (active.isNotEmpty()) "${active.size}" else null,
            hint = if (active.isEmpty()) languageManager.getString(R.string.chat_slash_no_skills) else null
        )
    }

    // ── MCP：只展示「已安装且正在运行（已连接）」的服务器 ──
    // Hub 生态门控（产品要求）：本地 MCP 需要启动才能用——未安装/未启动的
    // 不出现在斜杠菜单（去市场 MCP 页安装并启动）。禁用条目天然不连，无需
    // 单独排除。菜单随 mcpManager.changes 自动重建（connect/disconnect 均
    // 发射变更），启动完成后条目即时出现。
    // #197：逐项携带工位作用域（config.scope）——Agent 屏/Coding 屏各自过滤。
    private fun buildMcpCategory(): SlashMenuCategory {
        val connected = mcpManager.getConnectedServers().toSet()
        val running = mcpManager.getEnabledConfigs().filter { it.name in connected }
        val items = running.map { cfg ->
            SlashMenuItem(
                label = cfg.name,
                command = "/mcp:${cfg.name} ",
                description = cfg.url.ifBlank { cfg.endpointSummary() },
                status = SlashItemStatus.CONNECTED,
                scope = cfg.scope
            )
        }
        return SlashMenuCategory(
            id = "mcp",
            title = languageManager.getString(R.string.chat_slash_mcp_servers),
            icon = Icons.Default.Api,
            items = items,
            // #454：显式 Locale —— 土耳其语等区域下数字/文本格式与本文件
            // 其余 %s/%d 模式稳定一致。
            badge = if (items.isNotEmpty()) String.format(
                java.util.Locale.US,
                languageManager.getString(R.string.chat_slash_mcp_connected),
                items.size
            ) else null,
            hint = if (items.isEmpty()) languageManager.getString(R.string.chat_slash_mcp_none_running) else null
        )
    }

    // ── 插件：Plugin System v2 双形态合并类目 ──
    // 功能性插件：已激活插件的斜杠指令贡献（functionalSlashCommands 快照，
    // 无 IPC / 无扫盘——纯内存读）+ unified changes 流驱动重建；
    // APK 插件：只展示已加载（正在运行）的——发现但未加载的 NOT_INSTALLED
    // 条目不再进菜单（用户需求：「没有启动的不要在输入框 / 按钮里显示」；
    // 旧版把它们列为可选指令，点了也没在运行）。
    private fun buildPluginsCategory(): SlashMenuCategory {
        // 功能性插件指令贡献：插件名前缀区分来源（同一类目内双形态并列）。
        val functionalItems = unifiedPluginRegistry.functionalSlashCommands().map { command ->
            SlashMenuItem(
                label = command.title,
                command = "/plugin:${command.commandId} ",
                description = command.pluginName +
                    (if (command.description.isNotBlank()) " · " + command.description else ""),
                status = SlashItemStatus.READY
            )
        }
        val discovered = discoverPluginsCached()
        val loaded = pluginManager.loadedPlugins.value.keys
        // 只保留已加载（运行中）的 APK 插件；未加载的走市场 → 插件页加载
        val apkItems = discovered.mapNotNull { info ->
            if (info.packageName !in loaded) return@mapNotNull null
            SlashMenuItem(
                label = info.label,
                command = "/plugin:${info.packageName} ",
                description = info.packageName,
                status = SlashItemStatus.CONNECTED
            )
        }
        val items = functionalItems + apkItems
        return SlashMenuCategory(
            id = "plugins",
            title = languageManager.getString(R.string.chat_slash_plugins),
            icon = Icons.Default.Build,
            items = items,
            hint = if (items.isEmpty()) languageManager.getString(R.string.chat_slash_plugins_empty)
            else languageManager.getString(R.string.chat_slash_plugins_partial)
        )
    }

    /** 插件发现缓存：discoverPlugins 是跨进程 IPC，旧实现每次重建菜单都全量查询。 */
    private fun discoverPluginsCached(): List<com.apex.agent.plugin.host.PluginInfo> {
        val now = System.currentTimeMillis()
        val cached = pluginCache
        if (cached.isNotEmpty() && (now - pluginCacheAt) < PLUGIN_CACHE_TTL_MS) {
            return cached
        }
        return try {
            val fresh = pluginManager.discoverPlugins()
            pluginCache = fresh
            pluginCacheAt = now
            fresh
        } catch (e: Exception) {
            Log.w("SlashMenu", "discoverPlugins failed: ${e.message}")
            cached
        }
    }

    // ── 连接器：v2 从 ConnectorRegistry 读取真实数据（含自定义添加），仅展示启用的 ──
    private fun buildConnectorsCategory(): SlashMenuCategory {
        val enabled = connectorRegistry.getEnabled()
        val items = enabled.map { def ->
            SlashMenuItem(
                label = def.name,
                command = "/connector:${def.id} ",
                description = def.type + if (def.endpoint.isNotBlank()) " · ${def.endpoint}" else "",
                status = SlashItemStatus.EXTERNAL
            )
        }
        return SlashMenuCategory(
            id = "connectors",
            title = languageManager.getString(R.string.chat_slash_connectors),
            icon = Icons.Default.Link,
            items = items,
            hint = if (items.isEmpty()) languageManager.getString(R.string.chat_slash_connectors_empty) else null
        )
    }

    companion object {
        private const val PLUGIN_CACHE_TTL_MS = 10_000L
    }
}

// ═══ 数据模型 ═══

enum class SlashItemStatus {
    READY,        // 可用
    CONNECTED,    // 已连接 / 已加载
    OFFLINE,      // 已配置但当前离线
    NOT_INSTALLED,// 未安装 / 未加载
    EXTERNAL      // 外部服务（连接器）
}

data class SlashMenuItem(
    val label: String,
    val command: String,
    val description: String = "",
    val status: SlashItemStatus = SlashItemStatus.READY,
    /** #197 工位作用域（"agent"/"coding"/"all"）：消费屏按此过滤可见项。 */
    val scope: String = "all",
    /**
     * per-scope 启停：生效工位集合（"agent"/"coding" 子集；manifest 声明 ∩
     * 双工位启用）。forScope 双重过滤：工位作用域匹配 且 该工位已启用 ——
     * Agent 屏与 Coding 屏的 / 菜单各归各。默认双工位（MCP/插件/连接器等
     * 无 per-scope 启停概念的条目）。
     */
    val enabledScopes: Set<String> = setOf("agent", "coding")
)

data class SlashMenuCategory(
    val id: String,
    val title: String,
    val icon: ImageVector,
    val items: List<SlashMenuItem>,
    val badge: String? = null,
    val hint: String? = null
) {
    /**
     * #197 按工位过滤：保留 scope=all 与匹配项。
     * per-scope 启停双重过滤：还需该工位在 [SlashMenuItem.enabledScopes] 里
     * （市场里只在另一分级开启的技能不再混进本屏菜单）。
     */
    fun forScope(scope: String): SlashMenuCategory = copy(
        items = items.filter { item ->
            (item.scope == "all" || item.scope == scope) &&
                (scope == "all" || scope in item.enabledScopes)
        }
    )
}

data class SlashMenuData(
    val categories: List<SlashMenuCategory>
) {
    /**
     * #197 按工位作用域过滤菜单：Agent 屏传 "agent"（聊天技能 + agent 工位
     * MCP），Coding 屏传 "coding"（开发技能 + coding 工位 MCP）；scope=all
     * 的项两边都保留。空类目（过滤后无条目）整段剔除（hint 保留的类目除外）。
     */
    fun forScope(scope: String): SlashMenuData = copy(
        categories = categories
            .map { it.forScope(scope) }
            .filter { it.items.isNotEmpty() || it.hint != null }
    )
}

// ═══ Compose 侧获取入口 ═══

/**
 * [SlashMenuProvider] 的 Hilt EntryPoint：本类是 @Singleton 普通类而非 ViewModel，
 * Composable 中不能（也不应）经 hiltViewModel() 获取——旧写法
 * `slashMenuProvider: SlashMenuProvider = hiltViewModel()` 会让 ViewModelProvider
 * 反射创建本类（构造器带 5 个依赖）必然抛 RuntimeException，属闪退缺陷。
 * 经此 EntryPoint 直接从 SingletonComponent 取真实单例。
 */
@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface SlashMenuEntryPoint {
    fun slashMenuProvider(): SlashMenuProvider
}

/** Compose 中获取斜杠菜单单例（随 Application 上下文记忆化）。 */
@androidx.compose.runtime.Composable
fun rememberSlashMenuProvider(): SlashMenuProvider {
    val context = androidx.compose.ui.platform.LocalContext.current
    return androidx.compose.runtime.remember(context) {
        dagger.hilt.android.EntryPointAccessors.fromApplication(
            context.applicationContext,
            SlashMenuEntryPoint::class.java
        ).slashMenuProvider()
    }
}
