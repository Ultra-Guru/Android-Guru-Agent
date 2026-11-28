package com.apex.agent.ui.screen.settings

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.TrackChanges
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Subject
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Rule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Update
import androidx.compose.runtime.Immutable
import com.apex.agent.R

/**
 * 设置中心稳定页面注册表(Spec 01 · PR1 · FR-101/102/103)。
 *
 * **page.id 与分类解耦**:`id` 永不随分类重排变化,分组([SettingsGroup])只是
 * 导航展示属性——重排分组不改 ID,已打开标签/收藏/深链/页内状态全部存活。
 * 路由统一为 `settings/{page.id}`;旧两段式 route 经 [SETTINGS_PAGE_ALIASES]
 * (SettingsNavAliases.kt)解析到 page.id,静默升级。
 *
 * 页级元数据(生效时机/作用域)是 Spec 01 §7 徽标体系的数据源;页内锚点
 * `sectionIds` 供页内目录与搜索定位(PR3 升级为完整 [SettingsSection] 对象)。
 *
 * PR1 边界:纯数据层 + 路由按 ID 分发;抽屉仍渲染旧 [SettingsNavTree] 分类
 * (PR2 换新分组);页面内容按 §4.3 语义映射到现有分区,物理拆合在 PR4。
 */
enum class Effective { IMMEDIATE, NEXT_RUN, NEXT_SESSION, RESTART }

enum class Scope { PROFILE, GLOBAL, DEVICE, ACCOUNT }

@Immutable
data class SettingsPage(
    val id: String,
    val groupId: String,
    @StringRes val titleRes: Int,
    @StringRes val summaryRes: Int,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    /** 搜索别名(中英/字段名/历史名;显示文案必须走资源,此为搜索数据)。 */
    val keywords: List<String>,
    /** 页内锚点(稳定 sectionId;PR3 升级为 SettingsSection 完整元数据)。 */
    val sectionIds: List<String>,
    val scope: Scope,
    val effective: Effective,
    val danger: Boolean = false,
)

@Immutable
data class SettingsGroup(
    val groupId: String,
    @StringRes val titleRes: Int,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val pageIds: List<String>,
)

/** 全部页面(§4.3;typography 併入 theme —— language/fontScale 同页)。 */
object SettingsPages {

    val all: List<SettingsPage> = listOf(
        // ═══ model:模型与连接(7 页)═══
        SettingsPage(
            id = "model.connection", groupId = "model",
            titleRes = R.string.settings_page_connection, summaryRes = R.string.settings_summary_connection,
            icon = Icons.Outlined.Settings,
            keywords = listOf("模型", "连接", "API", "Key", "档案", "服务商", "provider", "profile", "setup"),
            sectionIds = listOf("profiles", "provider", "apiKey", "modelId", "test", "capabilities", "importExport", "providersRoles"),
            scope = Scope.PROFILE, effective = Effective.IMMEDIATE
        ),
        SettingsPage(
            id = "model.generation", groupId = "model",
            titleRes = R.string.settings_nav_model_generation, summaryRes = R.string.settings_summary_generation,
            icon = Icons.Outlined.Tune,
            keywords = listOf("温度", "采样", "参数", "temperature", "topP", "高级", "structured"),
            sectionIds = listOf("sampling", "limits", "advanced"),
            scope = Scope.PROFILE, effective = Effective.NEXT_RUN
        ),
        SettingsPage(
            id = "model.reasoning", groupId = "model",
            titleRes = R.string.settings_nav_model_reasoning, summaryRes = R.string.settings_summary_reasoning,
            icon = Icons.Outlined.Psychology,
            keywords = listOf("推理", "思考", "reasoning", "effort", "thinking"),
            sectionIds = listOf("effort", "thinkingBudget"),
            scope = Scope.PROFILE, effective = Effective.NEXT_RUN
        ),
        SettingsPage(
            id = "model.context", groupId = "model",
            titleRes = R.string.settings_nav_model_context, summaryRes = R.string.settings_summary_context,
            icon = Icons.Outlined.Subject,
            keywords = listOf("上下文", "窗口", "context", "profile", "预留"),
            sectionIds = listOf("window", "reserved"),
            scope = Scope.PROFILE, effective = Effective.NEXT_RUN
        ),
        SettingsPage(
            id = "model.tools", groupId = "model",
            titleRes = R.string.settings_nav_model_tools, summaryRes = R.string.settings_summary_tools,
            icon = Icons.Outlined.Build,
            keywords = listOf("工具", "tools", "并行", "超时", "webSearch", "choice"),
            sectionIds = listOf("enable", "choice", "parallel", "timeout", "webSearch"),
            scope = Scope.PROFILE, effective = Effective.NEXT_RUN
        ),
        SettingsPage(
            id = "model.network", groupId = "model",
            titleRes = R.string.settings_nav_model_network, summaryRes = R.string.settings_summary_network,
            icon = Icons.Outlined.Cloud,
            keywords = listOf("网络", "超时", "重试", "network", "timeout", "retry", "streaming"),
            sectionIds = listOf("timeouts", "retry", "streaming"),
            scope = Scope.PROFILE, effective = Effective.NEXT_RUN
        ),
        SettingsPage(
            id = "model.prompt", groupId = "model",
            titleRes = R.string.settings_nav_model_prompt, summaryRes = R.string.settings_summary_prompt,
            icon = Icons.Outlined.EditNote,
            keywords = listOf("提示词", "系统提示", "prompt", "prefix", "预设"),
            sectionIds = listOf("prefix", "preset"),
            scope = Scope.PROFILE, effective = Effective.NEXT_RUN
        ),
        // ═══ agent:代理与安全(7 页)═══
        SettingsPage(
            id = "agent.coding", groupId = "agent",
            titleRes = R.string.settings_nav_workspace_coding, summaryRes = R.string.settings_summary_coding,
            icon = Icons.Outlined.Build,
            keywords = listOf("编码", "工位", "coding", "输出", "并行度", "MCP", "隔离"),
            sectionIds = listOf("outputStyle", "outputMode", "mcpScope", "parallelism", "thinkingLogic", "executionMode"),
            scope = Scope.GLOBAL, effective = Effective.NEXT_RUN
        ),
        SettingsPage(
            id = "agent.subagent", groupId = "agent",
            titleRes = R.string.settings_nav_workspace_subagent, summaryRes = R.string.settings_summary_subagent,
            icon = Icons.Outlined.SmartToy,
            keywords = listOf("子代理", "预算", "subagent", "自定义类型"),
            sectionIds = listOf("budget", "customTypes"),
            scope = Scope.GLOBAL, effective = Effective.RESTART
        ),
        SettingsPage(
            id = "agent.goal", groupId = "agent",
            titleRes = R.string.settings_nav_workspace_goal, summaryRes = R.string.settings_summary_goal,
            icon = Icons.Outlined.TrackChanges,
            keywords = listOf("目标", "验收", "goal", "轮次"),
            sectionIds = listOf("verifier", "maxRounds"),
            scope = Scope.GLOBAL, effective = Effective.NEXT_RUN
        ),
        SettingsPage(
            id = "agent.permissions", groupId = "agent",
            titleRes = R.string.settings_nav_workspace_permissions, summaryRes = R.string.settings_summary_permissions,
            icon = Icons.Outlined.Rule,
            keywords = listOf("权限", "工具", "permission", "bypass", "安全", "截屏", "录屏", "屏幕", "secure"),
            sectionIds = listOf("mode", "rules", "secureScreen"),
            scope = Scope.GLOBAL, effective = Effective.RESTART
        ),
        SettingsPage(
            id = "agent.policy", groupId = "agent",
            titleRes = R.string.settings_nav_terminal_policy, summaryRes = R.string.settings_summary_policy,
            icon = Icons.Outlined.Rule,
            keywords = listOf("命令", "黑白名单", "终端", "policy", "策略"),
            sectionIds = listOf("blacklist", "whitelist"),
            scope = Scope.DEVICE, effective = Effective.IMMEDIATE
        ),
        SettingsPage(
            id = "agent.rules", groupId = "agent",
            titleRes = R.string.settings_nav_workspace_rules, summaryRes = R.string.settings_summary_rules,
            icon = Icons.AutoMirrored.Outlined.ListAlt,
            keywords = listOf("规则", "行为", "rules", "项目规则"),
            sectionIds = listOf("globalRules", "projectRules"),
            scope = Scope.GLOBAL, effective = Effective.NEXT_RUN
        ),
        SettingsPage(
            id = "agent.hooks", groupId = "agent",
            titleRes = R.string.settings_nav_workspace_hooks, summaryRes = R.string.settings_summary_hooks,
            icon = Icons.Outlined.Extension,
            keywords = listOf("钩子", "hooks", "生命周期"),
            sectionIds = listOf("hooks"),
            scope = Scope.GLOBAL, effective = Effective.IMMEDIATE
        ),
        // ═══ appearance:外观与交互(5 页;typography 併入 theme)═══
        SettingsPage(
            id = "appearance.theme", groupId = "appearance",
            titleRes = R.string.settings_page_theme, summaryRes = R.string.settings_summary_theme,
            icon = Icons.Outlined.Palette,
            keywords = listOf("主题", "风格", "色板", "字号", "语言", "theme", "palette", "font", "language"),
            sectionIds = listOf("uiStyle", "themeMode", "dynamicColor", "palette", "fontScale", "language"),
            scope = Scope.GLOBAL, effective = Effective.NEXT_SESSION
        ),
        SettingsPage(
            id = "appearance.chat", groupId = "appearance",
            titleRes = R.string.settings_page_chat, summaryRes = R.string.settings_summary_chat,
            icon = Icons.Outlined.EditNote,
            keywords = listOf("聊天", "时间戳", "发送键", "timestamps", "sendKey"),
            sectionIds = listOf("timestamps", "sendKey"),
            scope = Scope.GLOBAL, effective = Effective.IMMEDIATE
        ),
        SettingsPage(
            id = "appearance.skins", groupId = "appearance",
            titleRes = R.string.settings_page_skins, summaryRes = R.string.settings_summary_skins,
            icon = Icons.Outlined.Palette,
            keywords = listOf("皮肤", "部件", "玻璃", "skins", "partskin", "光晕"),
            sectionIds = listOf("applyMode", "groups", "presets", "glow"),
            scope = Scope.GLOBAL, effective = Effective.NEXT_SESSION
        ),
        SettingsPage(
            id = "appearance.background", groupId = "appearance",
            titleRes = R.string.settings_page_background, summaryRes = R.string.settings_summary_background,
            icon = Icons.Outlined.Palette,
            keywords = listOf("背景", "壁纸", "background", "纱罩"),
            sectionIds = listOf("pick", "dim"),
            scope = Scope.DEVICE, effective = Effective.IMMEDIATE
        ),
        SettingsPage(
            id = "appearance.notifications", groupId = "appearance",
            titleRes = R.string.settings_nav_interface_notifications, summaryRes = R.string.settings_summary_notifications,
            icon = Icons.Outlined.Notifications,
            keywords = listOf("通知", "完成提醒", "notifications", "渠道"),
            sectionIds = listOf("taskComplete", "systemChannel"),
            scope = Scope.DEVICE, effective = Effective.IMMEDIATE
        ),
        // ═══ services:扩展与服务(5 页)═══
        SettingsPage(
            id = "services.github", groupId = "services",
            titleRes = R.string.settings_nav_workspace_github, summaryRes = R.string.settings_summary_github,
            icon = Icons.Outlined.Hub,
            keywords = listOf("GitHub", "仓库", "token", "连接"),
            sectionIds = listOf("status", "defaultRepo", "disconnect"),
            scope = Scope.ACCOUNT, effective = Effective.IMMEDIATE
        ),
        SettingsPage(
            id = "services.search", groupId = "services",
            titleRes = R.string.settings_nav_search_local, summaryRes = R.string.settings_summary_search,
            icon = Icons.Outlined.Search,
            keywords = listOf("搜索", "本地", "SearXNG", "searxng", "引擎"),
            sectionIds = listOf("switch", "engines", "diagnostics", "clear"),
            scope = Scope.DEVICE, effective = Effective.IMMEDIATE
        ),
        SettingsPage(
            id = "services.memory", groupId = "services",
            titleRes = R.string.settings_nav_workspace_memory, summaryRes = R.string.settings_summary_memory,
            icon = Icons.Outlined.Psychology,
            keywords = listOf("记忆", "摄取", "memory", "capture"),
            sectionIds = listOf("capture"),
            scope = Scope.GLOBAL, effective = Effective.IMMEDIATE
        ),
        SettingsPage(
            id = "services.extensions", groupId = "services",
            titleRes = R.string.settings_page_extensions, summaryRes = R.string.settings_summary_extensions,
            icon = Icons.Outlined.Extension,
            keywords = listOf("扩展", "同步", "extensions", "market", "autoSync"),
            sectionIds = listOf("autoSync", "marketLinks"),
            scope = Scope.DEVICE, effective = Effective.RESTART
        ),
        SettingsPage(
            id = "services.keepAlive", groupId = "services",
            titleRes = R.string.settings_nav_terminal_service, summaryRes = R.string.settings_summary_keepAlive,
            icon = Icons.Outlined.Storage,
            keywords = listOf("保活", "后台", "服务", "keepalive", "service"),
            sectionIds = listOf("keepAlive"),
            scope = Scope.DEVICE, effective = Effective.IMMEDIATE
        ),
        // ═══ system:数据与系统(3 页)═══
        SettingsPage(
            id = "system.backup", groupId = "system",
            titleRes = R.string.settings_nav_data_backup, summaryRes = R.string.settings_summary_backup,
            icon = Icons.Outlined.Backup,
            keywords = listOf("备份", "恢复", "backup", "导出", "导入"),
            sectionIds = listOf("export", "import", "result"),
            scope = Scope.DEVICE, effective = Effective.RESTART,
            danger = true
        ),
        SettingsPage(
            id = "system.update", groupId = "system",
            titleRes = R.string.settings_nav_data_update, summaryRes = R.string.settings_summary_update,
            icon = Icons.Outlined.Update,
            keywords = listOf("更新", "版本", "update", "镜像"),
            sectionIds = listOf("autoCheck", "mirror"),
            scope = Scope.DEVICE, effective = Effective.IMMEDIATE
        ),
        SettingsPage(
            id = "system.diagnostics", groupId = "system",
            titleRes = R.string.settings_page_diagnostics, summaryRes = R.string.settings_summary_diagnostics,
            icon = Icons.Outlined.Tune,
            keywords = listOf("诊断", "压缩", "限速", "高级", "diagnostics", "compression", "engine"),
            sectionIds = listOf("compression", "rateLimit", "workspace", "onboarding"),
            scope = Scope.GLOBAL, effective = Effective.RESTART
        ),
    )

    val byId: Map<String, SettingsPage> = all.associateBy { it.id }

    val ids: Set<String> = byId.keys

    /** 分组内全部 sectionId 全局唯一性校验数据源(测试与注册检查用)。 */
    val allSectionIds: List<String> = all.flatMap { it.sectionIds }
}

/** 六个导航分组(§4.2;quick 动态区由 PR2 的 SettingsNavPrefs 合成,不在此列)。 */
object SettingsNavGroups {

    val all: List<SettingsGroup> = listOf(
        SettingsGroup(
            groupId = "model", titleRes = R.string.settings_group_model,
            icon = Icons.Outlined.SmartToy,
            pageIds = SettingsPages.all.filter { it.groupId == "model" }.map { it.id }
        ),
        SettingsGroup(
            groupId = "agent", titleRes = R.string.settings_group_agent,
            icon = Icons.Outlined.Shield,
            pageIds = SettingsPages.all.filter { it.groupId == "agent" }.map { it.id }
        ),
        SettingsGroup(
            groupId = "appearance", titleRes = R.string.settings_group_appearance,
            icon = Icons.Outlined.Palette,
            pageIds = SettingsPages.all.filter { it.groupId == "appearance" }.map { it.id }
        ),
        SettingsGroup(
            groupId = "services", titleRes = R.string.settings_group_services,
            icon = Icons.Outlined.Hub,
            pageIds = SettingsPages.all.filter { it.groupId == "services" }.map { it.id }
        ),
        SettingsGroup(
            groupId = "system", titleRes = R.string.settings_group_system,
            icon = Icons.Outlined.Storage,
            pageIds = SettingsPages.all.filter { it.groupId == "system" }.map { it.id }
        ),
    )
}
