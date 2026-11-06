package com.apex.agent.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * #270：同款模式/角色选择器胶囊的弱描边。
 *
 * 这是一个视觉职责而不是通用「0.6」数值等级：基色随 MaterialTheme 变化，
 * alpha 只弱化选择器边框。切勿把 0.6f 的 disabled 状态或动画包络套入此入口。
 */
@Composable
fun selectorOutlineColor(): Color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
