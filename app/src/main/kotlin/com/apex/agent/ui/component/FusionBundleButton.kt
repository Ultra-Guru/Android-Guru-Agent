package com.apex.agent.ui.component

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.apex.agent.R
import com.apex.agent.fusion.FusionBundleController

/**
 * # FusionBundle 入口按钮（输入栏工具栏）
 *
 * 「聚变包」入口：点开 [FusionBundlePickerSheet] 选择复合技能包（Skill +
 * MCP + 工具 + agent 工作流的聚合体）—— 选中即激活，该体系的 agent
 * 直接可用。
 *
 * 视觉规格与同行按钮（Slash/Attach/Brain）统一：48dp 触区红线（UI-012）+
 * `primary.copy(0.12f)` 圆底（AttachButton 同款）；激活态以 tertiary 色
 * 点亮图标（与斜杠按钮的渐变身份区分 —— 聚变 = 多层叠加，用 Layers 图标）。
 *
 * @param isActive 当前是否有激活的包（调用方 collect 控制器流，按钮零订阅）
 */
@Composable
fun FusionBundleButton(
    isActive: Boolean,
    onOpenPicker: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
        // UI-012：48dp 触区红线（与输入行全列按钮统一 48 系）
        modifier = modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
    ) {
        IconButton(
            onClick = onOpenPicker,
            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Layers,
                contentDescription = stringResource(
                    if (isActive) R.string.fusion_cd_bundle_active
                    else R.string.fusion_cd_bundle
                ),
                tint = if (isActive) {
                    MaterialTheme.colorScheme.tertiary
                } else {
                    MaterialTheme.colorScheme.primary
                },
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

/** EntryPoint：从 Application 取 FusionBundleController 单例。 */
@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface FusionBundleEntryPoint {
    fun fusionBundleController(): FusionBundleController
}

/** Compose 中获取 FusionBundle 控制器单例（随 Application 上下文记忆化）。 */
@Composable
fun rememberFusionBundleController(): FusionBundleController {
    val context = androidx.compose.ui.platform.LocalContext.current
    return androidx.compose.runtime.remember(context) {
        dagger.hilt.android.EntryPointAccessors.fromApplication(
            context.applicationContext,
            FusionBundleEntryPoint::class.java
        ).fusionBundleController()
    }
}
