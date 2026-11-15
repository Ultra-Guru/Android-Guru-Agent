package com.apex.agent.ui.screen.glass

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.apex.agent.R
import com.apex.agent.ui.glass.GlassRecipe

/**
 * ═══════════════════════════════════════════════════════════════
 *  RecipeParamPanel + RecipeExporter —— P-3 可调可导出（Gate-0 G0-4/G0-5）
 * ═══════════════════════════════════════════════════════════════
 *
 * D-9 的教训：「满意后再提炼为正式组件」这一步此前不存在 —— 标本参数只
 * 能看不能带走。本文件补上闭环的两端：
 *  - [RecipeParamPanel]：每个参数（blur / tint / lens / 色散 / edge /
 *    specular / elevation / scrim / corner / glow）滑杆实时调，改动直接
 *    驱动并排台的玻璃样本（同一 [GlassRecipe] 实例）；
 *  - [RecipeExportDialog]：一键导出 recipe.exportKotlin()（可直接粘进
 *    GlassRecipeBook 的代码块）或 recipe.exportJson()，复制即用 ——
 *    粘回 GlassRecipeBook 后，实验室与正式 GlassStyle 同步变化
 *    （GlassStyle 九档自 Book 生成），「导出 → 粘贴 → 全局生效」闭环。
 */

/** 单条参数滑杆：名称 + 当前值 + Slider（0..max 步进 0.01 精度）。 */
@Composable
private fun ParamSlider(
    label: String,
    value: Float,
    max: Float,
    format: (Float) -> String,
    onChange: (Float) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = format(value),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = value.coerceIn(0f, max),
            onValueChange = { onChange(it.coerceIn(0f, max)) },
            valueRange = 0f..max
        )
    }
}

/**
 * 配方参数台：全部维度滑杆 + 色散开关（P-3）。
 * 改动通过 [onChange] 回写并排台的 recipe 实例 —— 夜 / 日两栏同参联动。
 */
@Composable
internal fun RecipeParamPanel(
    recipe: GlassRecipe,
    onChange: (GlassRecipe) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = stringResource(R.string.glasslab_param_title),
            style = MaterialTheme.typography.labelLarge,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold
        )
        ParamSlider(
            label = stringResource(R.string.glasslab_param_blur),
            value = recipe.blurRadiusDp, max = 32f,
            format = { "${it.toInt()}dp" },
            onChange = { onChange(recipe.copy(blurRadiusDp = it)) }
        )
        ParamSlider(
            label = stringResource(R.string.glasslab_param_tint),
            value = recipe.tintAlpha, max = 1f,
            format = { "%.2f".format(it) },
            onChange = { onChange(recipe.copy(tintAlpha = it)) }
        )
        ParamSlider(
            label = stringResource(R.string.glasslab_param_lens_height),
            value = recipe.lensHeightDp, max = 16f,
            format = { "${it.toInt()}dp" },
            onChange = { onChange(recipe.copy(lensHeightDp = it)) }
        )
        ParamSlider(
            label = stringResource(R.string.glasslab_param_lens_amount),
            value = recipe.lensAmountDp, max = 32f,
            format = { "${it.toInt()}dp" },
            onChange = { onChange(recipe.copy(lensAmountDp = it)) }
        )
        ParamSlider(
            label = stringResource(R.string.glasslab_param_edge),
            value = recipe.edgeAlpha, max = 0.6f,
            format = { "%.2f".format(it) },
            onChange = { onChange(recipe.copy(edgeAlpha = it)) }
        )
        ParamSlider(
            label = stringResource(R.string.glasslab_param_specular),
            value = recipe.specularAlpha, max = 0.4f,
            format = { "%.2f".format(it) },
            onChange = { onChange(recipe.copy(specularAlpha = it)) }
        )
        ParamSlider(
            label = stringResource(R.string.glasslab_param_elevation),
            value = recipe.elevationDp, max = 16f,
            format = { "${it.toInt()}dp" },
            onChange = { onChange(recipe.copy(elevationDp = it)) }
        )
        ParamSlider(
            label = stringResource(R.string.glasslab_param_scrim),
            value = recipe.scrimAlpha, max = 1f,
            format = { "%.2f".format(it) },
            onChange = { onChange(recipe.copy(scrimAlpha = it)) }
        )
        ParamSlider(
            label = stringResource(R.string.glasslab_param_corner),
            value = recipe.cornerDp, max = 48f,
            format = { "${it.toInt()}dp" },
            onChange = { onChange(recipe.copy(cornerDp = it)) }
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.glasslab_param_chroma),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = recipe.chromaticAberration,
                onCheckedChange = { onChange(recipe.copy(chromaticAberration = it)) }
            )
        }
    }
}

/**
 * 导出对话框（P-3）：Kotlin 代码块（粘进 GlassRecipeBook）与 JSON 双页，
 * 复制到剪贴板即用。文本区限高滚动（长配方不撑爆对话框）。
 */
@Composable
internal fun RecipeExportDialog(
    recipe: GlassRecipe,
    onDismiss: () -> Unit
) {
    var showJson by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val code = if (showJson) recipe.exportJson() else recipe.exportKotlin()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.glasslab_export_title, recipe.id),
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(
                        if (showJson) R.string.glasslab_export_json_hint
                        else R.string.glasslab_export_kotlin_hint
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = code,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .verticalScroll(rememberScrollState())
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(code))
            }) {
                Text(stringResource(R.string.glasslab_export_copy))
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(onClick = { showJson = !showJson }) {
                    Text(
                        stringResource(
                            if (showJson) R.string.glasslab_export_show_kotlin
                            else R.string.glasslab_export_show_json
                        )
                    )
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.glasslab_export_close))
                }
            }
        }
    )
}
