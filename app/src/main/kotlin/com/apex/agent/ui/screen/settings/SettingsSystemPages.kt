package com.apex.agent.ui.screen.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Update
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.apex.agent.R
import com.apex.agent.update.DownloadMirror
import com.apex.agent.update.MirrorPrefs

/**
 * 设置中心「通知」「应用更新」与「记忆摄取」叶子（扩展阶段）。
 *
 * 通知（interface/notifications）：任务完成通知（v1.4.4 #4）此前分散在
 * Agent 配置叶子 —— 本页聚合全部通知开关，并附系统通知渠道深链
 * （渠道级重要度/免打扰只能走系统设置，App 内只给入口）。循环运行通知
 * 已随 LOOP/cron 模式移除。
 *
 * 更新（data/update）：自动检查开关此前只活在关于页更新面板（入口深），
 * 下载镜像更是藏在更新对话框里 —— 本页把两项搬进设置中心，与关于页
 * 同源同效（autoUpdateCheck 走 AgentSettings 单一可信源；镜像走
 * MirrorPrefs 同一持久化键）。
 *
 * 记忆（workspace/memory）：#218 聊天记忆自动摄取开关（Agent 聊天分区
 * 删除后迁入本文件；记忆功能主仓库保留，开关双引擎共享）。
 */
@Composable
internal fun NotificationsPage(agent: AgentSettings, onAgent: (AgentSettings) -> Unit) {
    val ctx = LocalContext.current
    SettingsLeafColumn {
        SectionCard(
            title = stringResource(R.string.settings_notifications_section),
            icon = Icons.Default.Notifications
        ) {
            // v1.4.4 #4：任务完成通知（EventApplier Complete 钩子；前台静音）。
            SwitchRow(
                stringResource(R.string.settings_task_notify_title), agent.taskCompletionNotify,
                description = stringResource(R.string.settings_task_notify_desc)
            ) { on -> onAgent(agent.copy(taskCompletionNotify = on)) }
        }
        SectionCard(
            title = stringResource(R.string.settings_notif_channel_section),
            icon = Icons.Default.Notifications
        ) {
            Text(
                stringResource(R.string.settings_notif_channel_desc),
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = androidx.compose.material3.MaterialTheme.colorScheme.outline
            )
            Button(
                onClick = {
                    runCatching {
                        ctx.startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.settings_notif_channel_open)) }
        }
    }
}

/**
 * 记忆摄取分区（workspace/memory 叶）：#218 聊天记忆自动摄取总开关。
 * 记忆观察者为双引擎共享单例（深潜线 + 标准线同源），关闭 = 新会话不
 * 落盘 + 召回静默；已有记忆去记忆页管理（记忆功能主仓库保留）。
 */
@Composable
internal fun MemoryCaptureSection(agent: AgentSettings, onAgent: (AgentSettings) -> Unit) {
    SettingsLeafColumn {
        SectionCard(
            title = stringResource(R.string.settings_memory_section),
            icon = Icons.Outlined.Psychology
        ) {
            SwitchRow(stringResource(R.string.settings_memory_capture_title), agent.chatMemoryCapture,
                description = stringResource(R.string.settings_memory_capture_desc)) {
                onAgent(agent.copy(chatMemoryCapture = it))
            }
        }
    }
}

@Composable
internal fun UpdatePage(agent: AgentSettings, onAgent: (AgentSettings) -> Unit) {
    val ctx = LocalContext.current
    // 镜像档位：本地态 + MirrorPrefs 落盘（与关于页更新对话框同一存储键）
    var mirror by remember { mutableStateOf(MirrorPrefs.load(ctx)) }

    val options = buildList {
        add(DownloadMirror.AUTO to stringResource(R.string.settings_update_mirror_auto))
        DownloadMirror.NODES.forEach { m ->
            add(m to stringResource(mirrorLabel(m)))
        }
    }

    SettingsLeafColumn {
        SectionCard(
            title = stringResource(R.string.settings_update_section),
            icon = Icons.Default.Update
        ) {
            // 开 = 启动静默检查 + 进关于页自动检查；关 = 后台零检查零下载。
            SwitchRow(
                stringResource(R.string.settings_update_auto), agent.autoUpdateCheck,
                description = stringResource(R.string.settings_update_auto_desc)
            ) { on -> onAgent(agent.copy(autoUpdateCheck = on)) }
            DropdownRow(
                label = stringResource(R.string.settings_update_mirror),
                options = options,
                selected = mirror,
                description = stringResource(R.string.settings_update_mirror_desc)
            ) { next ->
                MirrorPrefs.save(ctx, next)
                mirror = next
            }
        }
    }
}

/** 镜像枚举 → 本地化短标签（节点域名不做翻译，只翻译档位语义）。 */
private fun mirrorLabel(m: DownloadMirror): Int = when (m) {
    DownloadMirror.DIRECT -> R.string.settings_update_mirror_direct
    DownloadMirror.GHFAST -> R.string.settings_update_mirror_ghfast
    DownloadMirror.GHPROXY_NET -> R.string.settings_update_mirror_ghproxy_net
    DownloadMirror.GHPROXY_COM -> R.string.settings_update_mirror_ghproxy_com
    DownloadMirror.AUTO -> R.string.settings_update_mirror_auto
}
