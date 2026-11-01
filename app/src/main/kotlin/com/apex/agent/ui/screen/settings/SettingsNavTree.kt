package com.apex.agent.ui.screen.settings

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Rule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Subject
import androidx.compose.material.icons.outlined.TrackChanges
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Update
import androidx.compose.ui.graphics.vector.ImageVector
import com.apex.agent.R

/**
 * 设置导航树（两级分层抽屉的数据源）。
 *
 * 结构（v3 归类合并：小而散的叶子并入同类页；v3.1 重排：外部集成类
 * 三叶自工作台析出独立「集成与扩展」分类，提示词页的高级选项并入参数页）：
 * - 一级 [SettingsCategory] = 抽屉第一列的大分类（模型配置 / 工作台 /
 *   集成与扩展 / 终端 / 界面外观 / 数据与备份）；
 * - 二级 [SettingsLeaf] = 分类抽屉展开后的具体页面入口（如模型配置 →
 *   档案 / 参数 / 推理 …）；
 * - 合并页：上下文 ← 上下文压缩；网络 ← 限速友好；参数 ← 高级选项
 *   （结构化输出/自定义头是请求参数而非提示词，自提示词页迁入）；
 *   外观 ← 对话显示（同类设置归为一页，不再一个开关一页）。
 *
 * route 命名「分类/叶子」两段式（如 `model/generation`），全局唯一且稳定，
 * 供页面路由、浏览器式标签（PR2）与 rememberSaveable 持久化复用。
 */
data class SettingsLeaf(
    val route: String,
    @StringRes val titleRes: Int,
    val icon: ImageVector
)

data class SettingsCategory(
    val route: String,
    @StringRes val titleRes: Int,
    val icon: ImageVector,
    val children: List<SettingsLeaf>
)

/** 设置抽屉第一列：大分类 → 二级叶子（顺序即抽屉展示顺序）。 */
val SettingsNavTree: List<SettingsCategory> = listOf(
    SettingsCategory(
        route = "model",
        titleRes = R.string.settings_nav_cat_model,
        icon = Icons.Filled.SmartToy,
        children = listOf(
            SettingsLeaf("model/setup", R.string.settings_nav_model_setup, Icons.Outlined.Settings),
            SettingsLeaf("model/generation", R.string.settings_nav_model_generation, Icons.Outlined.Tune),
            SettingsLeaf("model/reasoning", R.string.settings_nav_model_reasoning, Icons.Outlined.Psychology),
            SettingsLeaf("model/context", R.string.settings_nav_model_context, Icons.Outlined.Subject),
            SettingsLeaf("model/tools", R.string.settings_nav_model_tools, Icons.Outlined.Build),
            SettingsLeaf("model/network", R.string.settings_nav_model_network, Icons.Outlined.Cloud),
            SettingsLeaf("model/prompt", R.string.settings_nav_model_prompt, Icons.Outlined.EditNote),
            SettingsLeaf("model/providers-roles", R.string.settings_nav_model_providers, Icons.Outlined.Hub),
        )
    ),
    SettingsCategory(
        route = "workspace",
        titleRes = R.string.settings_nav_cat_workspace,
        icon = Icons.Filled.Psychology,
        children = listOf(
            SettingsLeaf("workspace/coding", R.string.settings_nav_workspace_coding, Icons.Outlined.Build),
            SettingsLeaf("workspace/subagent", R.string.settings_nav_workspace_subagent, Icons.Outlined.SmartToy),
            SettingsLeaf("workspace/permissions", R.string.settings_nav_workspace_permissions, Icons.Outlined.Rule),
            SettingsLeaf("workspace/rules", R.string.settings_nav_workspace_rules, Icons.AutoMirrored.Outlined.ListAlt),
            SettingsLeaf("workspace/hooks", R.string.settings_nav_workspace_hooks, Icons.Outlined.Extension),
            SettingsLeaf("workspace/goal", R.string.settings_nav_workspace_goal, Icons.Outlined.TrackChanges),
        )
    ),
    SettingsCategory(
        route = "integrations",
        titleRes = R.string.settings_nav_cat_integrations,
        icon = Icons.Filled.Extension,
        // v3.1 重排：GitHub（外部服务）/ 本地搜索（检索能力）/ 记忆（知识
        // 扩展）与工作台（会话行为）语义不同类 —— 独立成类，工作台瘦身为
        // 纯会话行为六叶。route 前缀随分类更新，旧 route 标签降级见
        // SettingsPageRouter 的未知 route 兜底。
        children = listOf(
            SettingsLeaf("integrations/github", R.string.settings_nav_workspace_github, Icons.Outlined.Hub),
            // 本地搜索（原独立「搜索」分类仅此一叶 —— v3 曾并入工作台）
            SettingsLeaf("integrations/search", R.string.settings_nav_search_local, Icons.Outlined.Search),
            SettingsLeaf("integrations/memory", R.string.settings_nav_workspace_memory, Icons.Outlined.Psychology),
        )
    ),
    SettingsCategory(
        route = "terminal",
        titleRes = R.string.settings_nav_cat_terminal,
        icon = Icons.Filled.Terminal,
        // T97 Ubuntu 大重构后收窄：policy 叶 = Agent 侧执行门禁；
        // service 叶 = Ubuntu 终端运行时宿主（ApexCoreService 保活）
        children = listOf(
            SettingsLeaf("terminal/policy", R.string.settings_nav_terminal_policy, Icons.Outlined.Rule),
            SettingsLeaf("terminal/service", R.string.settings_nav_terminal_service, Icons.Outlined.Settings),
        )
    ),
    SettingsCategory(
        route = "interface",
        titleRes = R.string.settings_nav_cat_interface,
        icon = Icons.Outlined.Palette,
        children = listOf(
            SettingsLeaf("interface/appearance", R.string.settings_nav_interface_appearance, Icons.Outlined.Palette),
            SettingsLeaf("interface/notifications", R.string.settings_nav_interface_notifications, Icons.Outlined.Notifications),
        )
    ),
    SettingsCategory(
        route = "data",
        titleRes = R.string.settings_nav_cat_data,
        icon = Icons.Filled.Backup,
        children = listOf(
            SettingsLeaf("data/backup", R.string.settings_nav_data_backup, Icons.Filled.Backup),
            SettingsLeaf("data/update", R.string.settings_nav_data_update, Icons.Outlined.Update),
        )
    ),
)

/** 按叶子 route 查找所属大分类（标签页/抽屉高亮用）；未知 route 返回 null。 */
fun categoryOfLeaf(route: String): SettingsCategory? =
    SettingsNavTree.firstOrNull { cat -> cat.children.any { it.route == route } }

/** 按叶子 route 查找叶子定义（标题/图标渲染用）；未知 route 返回 null。 */
fun leafOf(route: String): SettingsLeaf? =
    SettingsNavTree.firstNotNullOfOrNull { cat -> cat.children.firstOrNull { it.route == route } }

/** 首个叶子（进入设置页时抽屉默认展开分类的高亮参考）。 */
val FirstSettingsLeafRoute: String = SettingsNavTree.first().children.first().route
