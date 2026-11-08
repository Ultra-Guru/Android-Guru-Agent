package com.apex.agent.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.apex.agent.R
import com.apex.agent.ui.glass.PartSurface
import com.apex.agent.ui.screen.code.skinContextFor
import com.apex.agent.ui.screen.code.toCodeOutputStyle
import com.apex.agent.ui.theme.CodingPart
import com.apex.agent.ui.theme.LocalPartSkins
import com.apex.agent.ui.theme.LocalSkinContext
import com.apex.agent.ui.theme.PartSkin
import com.apex.agent.ui.theme.PartSkinOverrides
import com.apex.agent.ui.theme.PartSkinPreset
import com.apex.agent.ui.theme.UiStyle

/**
 * # Part Skin Section — 「部件外观」设置分区（PartSkin spec §9）
 *
 * 入口：设置 → 工作台 → Coding 工位 → 「流水式输出UI」下方。
 *
 * 页面结构：
 * 1. 顶部**实时预览卡**（FR-14）：一张流式思考卡样例，随下方选择即时换皮
 *    —— 本地 CompositionLocalProvider 包住预览，选择落盘 → agentSettings
 *    流回灌 → 重组即见，不用来回进出 Coding 屏；
 * 2. **预设行**（§7.2：预设是动作而非持久态——点按生成的覆盖表写入
 *    partSkins 稀疏表；「恢复跟随全局」= 清空，缺席即跟随 §4 新默认）；
 * 3. **光晕强度**滑杆（FR-13：0 = 纯白霜面，1 = 完整彩光）；
 * 4. **分组列表**（3 组对应 §4 注册表）：每行部件名 + 当前皮肤下拉，
 *    选项经允许矩阵（§5）过滤 —— 不允许的组合根本不出现在选项里；
 * 5. 底部说明：run 边界切换语义（FR-8）。
 *
 * 无障碍：下拉 label 即部件名、字段值即当前皮肤；预设按钮 contentDescription
 * 含名称；全部文案走 stringResource（不硬编码中文）。
 */
@Composable
internal fun PartSkinSection(agent: AgentSettings, onUpdate: (AgentSettings) -> Unit) {
    SectionCard(
        title = stringResource(R.string.settings_v3_parts_title),
        icon = Icons.Outlined.Palette,
        subtitle = stringResource(R.string.settings_v3_parts_subtitle)
    ) {
        // ── 当前快照（容错解析：未知 key / 非法组合 / glow 越界全部收编）──
        val overrides = remember(agent.partSkins, agent.partSkinGlow) {
            PartSkinOverrides.fromPersisted(agent.partSkins, agent.partSkinGlow)
        }
        val uiStyle = UiStyle.fromKey(agent.uiStyle)
        val outputStyle = agent.codeOutputStyle.toCodeOutputStyle()
        val ctx = remember(outputStyle, uiStyle, overrides.glow) {
            skinContextFor(outputStyle, uiStyle, overrides.glow)
        }

        // ═══ 1. 实时预览卡（FR-14）：本地 provider 包住样例思考卡 ═══
        Text(
            text = stringResource(R.string.settings_v3_parts_preview_label),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        CompositionLocalProvider(
            LocalPartSkins provides overrides,
            LocalSkinContext provides ctx
        ) {
            PartSurface(
                part = CodingPart.STREAM_THINKING,
                active = true,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Psychology,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.code_stream_thinking_running),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = stringResource(R.string.settings_v3_parts_preview_body),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
        Spacer(Modifier.height(10.dp))

        // ═══ 2. 预设行（动作化：生成覆盖表写入稀疏表；默认/恢复跟随 = 清空）═══
        Text(
            text = stringResource(R.string.settings_v3_parts_preset_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            PresetButton(
                label = stringResource(R.string.settings_v3_parts_preset_default),
                modifier = Modifier.weight(1f)
            ) { onUpdate(agent.copy(partSkins = emptyMap())) }
            PresetButton(
                label = stringResource(R.string.settings_v3_parts_preset_pale),
                modifier = Modifier.weight(1f)
            ) { applyPreset(agent, onUpdate, PartSkinPreset.PALE_ALL) }
        }
        Spacer(Modifier.height(6.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            PresetButton(
                label = stringResource(R.string.settings_v3_parts_preset_classic),
                modifier = Modifier.weight(1f)
            ) { applyPreset(agent, onUpdate, PartSkinPreset.CLASSIC_ALL) }
            PresetButton(
                label = stringResource(R.string.settings_v3_parts_preset_minimal),
                modifier = Modifier.weight(1f)
            ) { applyPreset(agent, onUpdate, PartSkinPreset.MINIMAL_ALL) }
        }
        Spacer(Modifier.height(6.dp))
        OutlinedButton(
            onClick = { onUpdate(agent.copy(partSkins = emptyMap())) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.settings_v3_parts_reset_follow))
        }
        Spacer(Modifier.height(10.dp))

        // ═══ 3. 光晕强度滑杆（FR-13）═══
        // 任何 Part 解析为 PALE_LIQUID 即可操作（默认思考卡即浅白 → 常开）。
        val anyPale = remember(overrides, ctx) {
            CodingPart.entries.any { overrides.resolve(it, ctx) == PartSkin.PALE_LIQUID }
        }
        SliderRow(
            label = stringResource(R.string.settings_v3_parts_glow),
            value = overrides.glow,
            range = 0f..1f,
            description = stringResource(R.string.settings_v3_parts_glow_desc),
            onValueChange = { onUpdate(agent.copy(partSkinGlow = it)) },
            fmt = { glow -> String.format(java.util.Locale.US, "%.2f", glow) }
        )
        Spacer(Modifier.height(10.dp))

        // ═══ 4. 分组列表（3 组对应 §4 注册表；选项经允许矩阵过滤）═══
        PartGroup(
            title = stringResource(R.string.settings_v3_parts_group_stream),
            parts = listOf(
                CodingPart.STREAM_USER_BUBBLE,
                CodingPart.STREAM_ASSISTANT_BUBBLE,
                CodingPart.STREAM_THINKING,
                CodingPart.STREAM_TOOL_CAPSULE,
                CodingPart.STREAM_VERIFY_CYCLE,
                CodingPart.STREAM_STATUS_LINE,
                CodingPart.STREAM_SYSTEM_LINE,
                CodingPart.STREAM_ERROR,
                CodingPart.STREAM_STOP,
                CodingPart.STREAM_FILE_CHIPS,
                CodingPart.STREAM_GIT_OP,
                CodingPart.STREAM_RUN_STATS,
                CodingPart.STREAM_GENERATING
            ),
            overrides = overrides,
            agent = agent,
            onUpdate = onUpdate
        )
        PartGroup(
            title = stringResource(R.string.settings_v3_parts_group_input),
            parts = listOf(
                CodingPart.INPUT_BAR,
                CodingPart.INPUT_QUEUE_CARD,
                CodingPart.INPUT_QUESTION_CARD,
                CodingPart.INPUT_PLAN_CONFIRM,
                CodingPart.OVERLAY_ERROR_BAR,
                CodingPart.OVERLAY_OFFLINE_BANNER,
                CodingPart.OVERLAY_UPDATE_BANNER,
                CodingPart.OVERLAY_TOOL_DETAIL_SHEET
            ),
            overrides = overrides,
            agent = agent,
            onUpdate = onUpdate
        )
        PartGroup(
            title = stringResource(R.string.settings_v3_parts_group_terminal),
            parts = listOf(
                CodingPart.TERMINAL_PANEL,
                CodingPart.CODE_DIFF,
                CodingPart.CODE_STREAMING_WINDOW
            ),
            overrides = overrides,
            agent = agent,
            onUpdate = onUpdate
        )

        // ═══ 5. 底部说明（FR-8 切换语义）═══
        DescriptionText(stringResource(R.string.settings_v3_parts_note))
    }
}

/** 预设按钮（等宽胶囊行；语义 = 动作而非持久态）。 */
@Composable
private fun PresetButton(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.semantics { contentDescription = label }
    ) {
        Text(label, maxLines = 1)
    }
}

/**
 * 预设动作落地：`preset.generateOverrides()` 的覆盖表序列化为稀疏
 * `partSkins`（Part.id → skin.key）。「全部浅白」只写允许集 —— 终端 /
 * 代码窗等 NEON 独占部件绝不入表（§11.1）。
 */
private fun applyPreset(
    agent: AgentSettings,
    onUpdate: (AgentSettings) -> Unit,
    preset: PartSkinPreset
) {
    val serialized = preset.generateOverrides()
        .entries.associate { (part, skin) -> part.id to skin.key }
    onUpdate(agent.copy(partSkins = serialized))
}

/** 一个分组：组标题 + 组内部件的逐行皮肤下拉。 */
@Composable
private fun PartGroup(
    title: String,
    parts: List<CodingPart>,
    overrides: PartSkinOverrides,
    agent: AgentSettings,
    onUpdate: (AgentSettings) -> Unit
) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp)
    )
    parts.forEach { part ->
        // 选项 = 跟随 + 允许矩阵（§5）过滤后的皮肤（不允许的组合不出现）
        val options = buildList {
            add(PartSkin.FOLLOW.key to stringResource(R.string.part_skin_follow))
            if (part.allows(PartSkin.PALE_LIQUID)) {
                add(PartSkin.PALE_LIQUID.key to stringResource(R.string.part_skin_pale_liquid))
            }
            if (part.allows(PartSkin.CLASSIC_GLASS)) {
                add(PartSkin.CLASSIC_GLASS.key to stringResource(R.string.part_skin_classic_glass))
            }
            if (part.allows(PartSkin.FLAT)) {
                add(PartSkin.FLAT.key to stringResource(R.string.part_skin_flat))
            }
            if (part.allows(PartSkin.SOLID)) {
                add(PartSkin.SOLID.key to stringResource(R.string.part_skin_solid))
            }
            if (part.allows(PartSkin.NEON_TERMINAL)) {
                add(PartSkin.NEON_TERMINAL.key to stringResource(R.string.part_skin_neon_terminal))
            }
        }
        val selectedKey = overrides.map[part]?.key ?: PartSkin.FOLLOW.key
        DropdownRow(
            label = stringResource(partLabelRes(part)),
            options = options,
            selected = selectedKey,
            onSelected = { key ->
                // 稀疏语义（FR-10）：「跟随」= 移除键（缺席 = 跟随上级）
                if (key == PartSkin.FOLLOW.key) {
                    onUpdate(agent.copy(partSkins = agent.partSkins - part.id))
                } else {
                    onUpdate(agent.copy(partSkins = agent.partSkins + (part.id to key)))
                }
            }
        )
    }
}

/** 部件显示名（§4 注册表 → 字符串资源；a11y：不硬编码中文）。 */
private fun partLabelRes(part: CodingPart): Int = when (part) {
    CodingPart.STREAM_USER_BUBBLE -> R.string.part_name_stream_user_bubble
    CodingPart.STREAM_ASSISTANT_BUBBLE -> R.string.part_name_stream_assistant_bubble
    CodingPart.STREAM_THINKING -> R.string.part_name_stream_thinking
    CodingPart.STREAM_TOOL_CAPSULE -> R.string.part_name_stream_tool_capsule
    CodingPart.STREAM_VERIFY_CYCLE -> R.string.part_name_stream_verify_cycle
    CodingPart.STREAM_STATUS_LINE -> R.string.part_name_stream_status_line
    CodingPart.STREAM_SYSTEM_LINE -> R.string.part_name_stream_system_line
    CodingPart.STREAM_ERROR -> R.string.part_name_stream_error
    CodingPart.STREAM_STOP -> R.string.part_name_stream_stop
    CodingPart.STREAM_FILE_CHIPS -> R.string.part_name_stream_file_chips
    CodingPart.STREAM_GIT_OP -> R.string.part_name_stream_git_op
    CodingPart.STREAM_RUN_STATS -> R.string.part_name_stream_run_stats
    CodingPart.STREAM_GENERATING -> R.string.part_name_stream_generating
    CodingPart.INPUT_BAR -> R.string.part_name_input_bar
    CodingPart.INPUT_QUEUE_CARD -> R.string.part_name_input_queue_card
    CodingPart.INPUT_QUESTION_CARD -> R.string.part_name_input_question_card
    CodingPart.INPUT_PLAN_CONFIRM -> R.string.part_name_input_plan_confirm
    CodingPart.OVERLAY_ERROR_BAR -> R.string.part_name_overlay_error_bar
    CodingPart.OVERLAY_OFFLINE_BANNER -> R.string.part_name_overlay_offline_banner
    CodingPart.OVERLAY_UPDATE_BANNER -> R.string.part_name_overlay_update_banner
    CodingPart.OVERLAY_TOOL_DETAIL_SHEET -> R.string.part_name_overlay_tool_detail_sheet
    CodingPart.TERMINAL_PANEL -> R.string.part_name_terminal_panel
    CodingPart.CODE_DIFF -> R.string.part_name_code_diff
    CodingPart.CODE_STREAMING_WINDOW -> R.string.part_name_code_streaming_window
}
