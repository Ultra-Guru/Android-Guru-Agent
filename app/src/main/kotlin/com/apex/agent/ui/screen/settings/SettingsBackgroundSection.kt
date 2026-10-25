package com.apex.agent.ui.screen.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.apex.agent.R
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory

/**
 * ═══ 自定义背景（interface/appearance 叶子页第二分区）═══
 *
 * 导入自己的图片作为全局背景（SAF OpenDocument + takePersistableUriPermission
 * 持久授权 —— 重启后仍可读，chaos-crash-audit C-09 同款纪律）。渲染与
 * 专属优化见 ui/theme/BackgroundImage.kt：
 * - 降采样解码（48MP 照片不爆内存）
 * - 亮度自适应纱罩（图片越亮/越暗自动加深/变浅，文字可读性有下限）
 * - 一图一解码（换图才重解码，切页零 IO）
 *
 * 纱罩强度滑杆叠加在自适应基数之上（0.1..0.8）——用户只调「感觉」，
 * 对比度下限交给自适应。
 */
@Composable
internal fun BackgroundSection(agent: AgentSettings, onAgent: (AgentSettings) -> Unit) {
    val context = LocalContext.current
    val hasBackground = agent.backgroundImageUri.isNotBlank()

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            // 持久授权：不 take 的 content URI 重启即失效（历史坑 C-09）。
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }.onFailure {
                runCatching {
                    AppLogger.instance.warn(
                        LogCategory.SYSTEM, "BackgroundSection",
                        "背景图持久授权失败（${it.javaClass.simpleName}: ${it.message}）" +
                            "—— 部分机型重启后可能需重新选择"
                    )
                }
            }
            onAgent(agent.copy(backgroundImageUri = uri.toString()))
        }
    }

    SectionCard(
        title = stringResource(R.string.settings_background_section),
        icon = Icons.Outlined.Wallpaper,
        initiallyExpanded = hasBackground
    ) {
        DescriptionText(stringResource(R.string.settings_background_desc))
        if (hasBackground) {
            DescriptionText(stringResource(R.string.settings_background_active))
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(onClick = { picker.launch(arrayOf("image/*")) }) {
                Text(
                    if (hasBackground) stringResource(R.string.settings_background_replace)
                    else stringResource(R.string.settings_background_pick)
                )
            }
            if (hasBackground) {
                OutlinedButton(onClick = { onAgent(agent.copy(backgroundImageUri = "")) }) {
                    Text(stringResource(R.string.settings_background_clear))
                }
            }
        }
        if (hasBackground) {
            SliderRow(
                label = stringResource(R.string.settings_background_dim),
                value = agent.backgroundDim,
                range = 0.1f..0.8f,
                steps = 6,
                description = stringResource(R.string.settings_background_dim_desc),
                onValueChange = { onAgent(agent.copy(backgroundDim = it)) },
                fmt = { "${(it * 100).toInt()}%" }
            )
        }
    }
}
