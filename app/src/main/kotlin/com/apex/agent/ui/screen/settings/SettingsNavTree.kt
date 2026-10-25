package com.apex.agent.ui.screen.settings

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Rule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Subject
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material.icons.outlined.ViewCompact
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.TrackChanges
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.ui.graphics.vector.ImageVector
import com.apex.agent.R

/**
 * 设置导航树（两级分层抽屉的数据源）。
 *
 * 结构完全对齐旧三页签的全部功能分区，无功能增删：
 * - 一级 [SettingsCategory] = 抽屉第一列的大分类（模型配置 / Agent 配置 / 界面外观 / 数据与备份）；
 * - 二级 [SettingsLeaf] = 分类抽屉展开后的具体页面入口（如模型配置 → 配置 / 参数 / 推理 …）。
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
            SettingsLeaf("model/rate-limit", R.string.settings_nav_model_rate_limit, Icons.Outlined.Speed),
            SettingsLeaf("model/prompt", R.string.settings_nav_model_prompt, Icons.Outlined.EditNote),
            SettingsLeaf("model/advanced", R.string.settings_nav_model_advanced, Icons.Outlined.ViewCompact),
            SettingsLeaf("model/providers-roles", R.string.settings_nav_model_providers, Icons.Outlined.Hub),
        )
    ),
    SettingsCategory(
        route = "agent",
        titleRes = R.string.settings_nav_cat_agent,
        icon = Icons.Filled.Psychology,
        children = listOf(
            SettingsLeaf("agent/execution", R.string.settings_nav_agent_execution, Icons.Outlined.Settings),
            SettingsLeaf("agent/roles", R.string.settings_nav_agent_roles, Icons.Outlined.Psychology),
            SettingsLeaf("agent/permissions", R.string.settings_nav_agent_permissions, Icons.Outlined.Rule),
            SettingsLeaf("agent/rules", R.string.settings_nav_agent_rules, Icons.AutoMirrored.Outlined.ListAlt),
            SettingsLeaf("agent/hooks", R.string.settings_nav_agent_hooks, Icons.Outlined.Extension),
            SettingsLeaf("agent/presets", R.string.settings_nav_agent_presets, Icons.Outlined.TrackChanges),
            SettingsLeaf("agent/subagent", R.string.settings_nav_agent_subagent, Icons.Outlined.SmartToy),
            SettingsLeaf("agent/goal-loop", R.string.settings_nav_agent_goal_loop, Icons.Outlined.TrackChanges),
            SettingsLeaf("agent/coding", R.string.settings_nav_agent_coding, Icons.Outlined.Build),
            SettingsLeaf("agent/github", R.string.settings_nav_agent_github, Icons.Outlined.Hub),
            SettingsLeaf("agent/compression", R.string.settings_nav_agent_compression, Icons.Outlined.Storage),
            SettingsLeaf("agent/vision", R.string.settings_nav_agent_vision, Icons.Outlined.Visibility),
        )
    ),
    SettingsCategory(
        route = "terminal",
        titleRes = R.string.settings_nav_cat_terminal,
        icon = Icons.Filled.Terminal,
        // T96 终端大重构后收窄：配色/扩展键两叶随旧交互终端退役
        children = listOf(
            SettingsLeaf("terminal/appearance", R.string.settings_nav_terminal_appearance, Icons.Outlined.Palette),
            SettingsLeaf("terminal/policy", R.string.settings_nav_terminal_policy, Icons.Outlined.Rule),
        )
    ),
    SettingsCategory(
        route = "interface",
        titleRes = R.string.settings_nav_cat_interface,
        icon = Icons.Outlined.Palette,
        children = listOf(
            SettingsLeaf("interface/appearance", R.string.settings_nav_interface_appearance, Icons.Outlined.Palette),
            SettingsLeaf("interface/chat", R.string.settings_nav_interface_chat, Icons.Outlined.ViewCompact),
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
