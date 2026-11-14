package com.apex.agent.ui.screen.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.Badge
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.apex.agent.ui.screen.code.SkinActivation
import com.apex.agent.ui.screen.code.skinContextFor
import com.apex.agent.ui.screen.code.toCodeOutputStyle
import com.apex.agent.ui.theme.CodingPart
import com.apex.agent.ui.theme.DevicePerfTier
import com.apex.agent.ui.theme.LocalParentSkin
import com.apex.agent.ui.theme.LocalPartSkins
import com.apex.agent.ui.theme.LocalSkinContext
import com.apex.agent.ui.theme.PartSkin
import com.apex.agent.ui.theme.PartSkinOverrides
import com.apex.agent.ui.theme.PartSkinPreset
import com.apex.agent.ui.theme.SkinApplyMode
import com.apex.agent.ui.theme.SkinApplyPolicy
import com.apex.agent.ui.theme.SkinGroup
import com.apex.agent.ui.theme.UiStyle

/**
 * # Part Skin Section — 「部件外观」设置分区（PartSkin spec §9，v1.2/v1.3）
 *
 * 入口：设置 → 工作台 → Coding 工位 → 「流水式输出UI」下方。
 *
 * 页面结构（自上而下）：
 * 0. **外观生效方式**（v1.3）：三档 Radio（AUTO / IMMEDIATE / ON_NEW_CHAT）
 *    + 本机分级徽标 + 一句解释；LOW 档「立即」置灰（resolvePolicy 双兜底）；
 * 0.5. **⏳ 待生效提示条**（v1.2）：`pending != null` 时显示，文案分策略
 *    （ON_NEW_CHAT → 「立即新建对话并应用」/「撤销」；IMMEDIATE + 运行中
 *    → 「任务进行中，结束后自动生效」，只留「撤销」）；
 * 1. **实时预览卡**（FR-14 + FR-23 预览隔离）：独立
 *    `LocalPartSkins provides pending ?: active` 包住，标注「仅预览 · 当前
 *    对话不变」，**绝不写回 active**；
 * 2. **组级切换**（FR-18）：7 行 `@g1`…`@g7` 整组下拉 + 组内计数，组行
 *    可展开看成员逐项覆盖（默认只展开 G1/G3/G4）；
 * 3. **预设行**（动作化：生成的覆盖表写入 partSkins 稀疏表；「恢复跟随
 *    全局（74 项）」= 清空含组键的全部稀疏表）；
 * 4. **光晕强度**滑杆（FR-13；H-3：anyPale 计算后用 `if (anyPale)` 真消费，
 *    基于 pending ?: active，保证预览与滑杆同步）；
 * 5. **底部说明**（分策略文案）。
 *
 * 覆盖优先级（FR-17）：部件行显式值 > 组行 `@gN` > 预设 > 全局；部件行选
 * 「跟随」= 移除该键（回落组值）；组行选「跟随」= 移除组键。
 *
 * 无障碍：每行 contentDescription 含部件名/组名/当前值/待生效状态；全部
 * 文案走 stringResource（不硬编码中文，i18n 门禁 values/values-zh 同名同数）。
 */
@Composable
internal fun PartSkinSection(
    agent: AgentSettings,
    onUpdate: (AgentSettings) -> Unit,
    activation: SkinActivation,
    isRunning: Boolean,
    onApplyNowViaNewChat: () -> Unit,
    onDiscardPending: () -> Unit
) {
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
        // 预览隔离的数据面（FR-23）：pending 优先，绝不写回 active
        val previewOverrides = activation.pending?.partSkins ?: activation.active.partSkins

        // ═══ 0. 外观生效方式（v1.3，置顶）═══
        ApplyModeCard(
            agent = agent,
            onUpdate = onUpdate,
            activation = activation
        )

        // ═══ 0.5 ⏳ 待生效提示条（v1.2，文案分策略）═══
        PendingBanner(
            activation = activation,
            isRunning = isRunning,
            onApplyNow = onApplyNowViaNewChat,
            onDiscard = onDiscardPending
        )

        // ═══ 1. 实时预览卡（FR-14 + FR-23 预览隔离）═══
        Text(
            text = stringResource(R.string.settings_v3_parts_preview_label),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = stringResource(R.string.settings_v3_parts_preview_only),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
        Spacer(Modifier.height(6.dp))
        CompositionLocalProvider(
            LocalPartSkins provides previewOverrides,
            LocalSkinContext provides ctx,
            LocalParentSkin provides null
        ) {
            PartSurface(
                part = CodingPart.SETTINGS_PREVIEW_CARD,
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

        // ═══ 2. 组级切换（FR-18）：7 行整组下拉 + 可展开成员明细 ═══
        SkinGroup.entries.forEach { group ->
            GroupCard(
                group = group,
                overrides = overrides,
                previewOverrides = previewOverrides,
                ctx = ctx,
                activation = activation,
                agent = agent,
                onUpdate = onUpdate
            )
        }

        // ═══ 3. 预设行（动作化；「恢复跟随」清空全部 74 项 + 7 组键）═══
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
            ) { onUpdate(agent.copy(partSkins = agent.partSkins.filterKeys { it.startsWith("@") })) }
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

        // ═══ 4. 光晕强度滑杆（FR-13；H-3：anyPale 真消费）═══
        // 基于 pending ?: active 计算，保证预览与滑杆同步；任何 Part 解析为
        // PALE_LIQUID 即可操作（默认思考卡即浅白 → 常开）。
        val anyPale = remember(previewOverrides, ctx) {
            CodingPart.entries.any { previewOverrides.resolve(it, ctx) == PartSkin.PALE_LIQUID }
        }
        if (anyPale) {
            SliderRow(
                label = stringResource(R.string.settings_v3_parts_glow),
                value = previewOverrides.glow,
                range = 0f..1f,
                description = stringResource(R.string.settings_v3_parts_glow_desc),
                onValueChange = { onUpdate(agent.copy(partSkinGlow = it)) },
                fmt = { glow -> String.format(java.util.Locale.US, "%.2f", glow) }
            )
        }

        // ═══ 5. 底部说明（分策略文案）═══
        DescriptionText(
            stringResource(
                if (activation.effectivePolicy == SkinApplyPolicy.IMMEDIATE) {
                    R.string.settings_v3_parts_note_immediate
                } else {
                    R.string.settings_v3_parts_note_new_chat
                }
            )
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════
// 0. 外观生效方式（v1.3 §9-0）
// ═══════════════════════════════════════════════════════════════════════

/**
 * 三档 Radio + 本机分级徽标。切换本身写 `AgentSettings.skinApplyMode`，
 * **不触发换皮**（只改后续提升点）。LOW 档 IMMEDIATE 置灰（FR-29）——
 * 状态机侧 resolvePolicy 再硬拒一次（NFR-16 双兜底，UI 置灰不是防线）。
 */
@Composable
private fun ApplyModeCard(
    agent: AgentSettings,
    onUpdate: (AgentSettings) -> Unit,
    activation: SkinActivation
) {
    val tier = activation.tier
    Text(
        text = stringResource(R.string.settings_v3_parts_apply_mode_title),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    SkinApplyMode.entries.forEach { mode ->
        val label = stringResource(mode.labelRes)
        // LOW 硬拒的 UI 半边（NFR-16 状态机侧 resolvePolicy 再兜一次）
        val enabled = mode != SkinApplyMode.IMMEDIATE || tier != DevicePerfTier.LOW
        RadioRow(
            label = label,
            selected = agent.skinApplyMode == mode.key,
            enabled = enabled,
            description = when {
                mode == SkinApplyMode.IMMEDIATE && tier == DevicePerfTier.LOW ->
                    stringResource(R.string.settings_v3_parts_low_locked_hint)
                mode == SkinApplyMode.IMMEDIATE && tier == DevicePerfTier.MID ->
                    stringResource(R.string.settings_v3_parts_mid_immediate_warning)
                else -> null
            },
            onSelect = { onUpdate(agent.copy(skinApplyMode = mode.key)) }
        )
    }
    // 本机分级徽标 + 一句解释（AUTO 档解析结果）
    TierBadge(tier = tier, autoPolicy = resolveAutoPolicyLabel(tier))
}

/** AUTO 在本机分级下的解析结果（徽标解释文案）。 */
@Composable
private fun resolveAutoPolicyLabel(tier: DevicePerfTier): String =
    stringResource(
        if (tier == DevicePerfTier.HIGH) {
            R.string.settings_v3_parts_mode_immediate
        } else {
            R.string.settings_v3_parts_mode_new_chat
        }
    )

/** 本机分级徽标：高性能 / 中等 / 低内存 + 一句解释。 */
@Composable
private fun TierBadge(tier: DevicePerfTier, autoPolicy: String) {
    val tierLabel = stringResource(
        when (tier) {
            DevicePerfTier.HIGH -> R.string.settings_v3_parts_tier_high
            DevicePerfTier.MID -> R.string.settings_v3_parts_tier_mid
            DevicePerfTier.LOW -> R.string.settings_v3_parts_tier_low
        }
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Badge { Text(tierLabel) }
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.settings_v3_parts_tier_hint_fmt, tierLabel, autoPolicy),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
    }
}

/** 单选行（三档生效方式共用；enabled=false 即 LOW 档置灰）。 */
@Composable
private fun RadioRow(
    label: String,
    selected: Boolean,
    enabled: Boolean = true,
    description: String? = null,
    onSelect: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onSelect() }
            .semantics { contentDescription = label }
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, enabled = enabled, onClick = onSelect)
            Spacer(Modifier.width(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.outline
            )
        }
        if (description != null) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(start = 44.dp)
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
// 0.5 待生效提示条（v1.2 §7.7 / v1.3 §9-0.5）
// ═══════════════════════════════════════════════════════════════════════

/**
 * `pending != null` 时显示：
 *  - ON_NEW_CHAT → 「N 项将在新对话生效」+「立即新建对话并应用」/「撤销」；
 *  - IMMEDIATE（运行中挂起）→ 「任务进行中，N 项将在结束后自动生效」，
 *    只留「撤销」（不显示「新建对话」，避免诱导打断任务）；
 *  - IMMEDIATE idle → stage() 已立即提升，本条一闪即消（hasPending=false）。
 */
@Composable
private fun PendingBanner(
    activation: SkinActivation,
    isRunning: Boolean,
    onApplyNow: () -> Unit,
    onDiscard: () -> Unit
) {
    if (!activation.hasPending) return
    val pendingCount = activation.changedParts.size
    val immediate = activation.effectivePolicy == SkinApplyPolicy.IMMEDIATE
    val message = if (immediate) {
        // IMMEDIATE：改动在收束后的第一个 idle 点自动提升（H-6 挂起中）
        stringResource(R.string.settings_v3_parts_running_deferred_fmt, pendingCount)
    } else {
        stringResource(R.string.settings_v3_parts_pending_fmt, pendingCount)
    }
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Schedule,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            // IMMEDIATE 挂起中不显示「新建对话」按钮（避免诱导打断任务）
            if (!immediate) {
                TextButton(onClick = onApplyNow) {
                    Text(stringResource(R.string.settings_v3_parts_apply_new_chat))
                }
            }
            TextButton(onClick = onDiscard) {
                Text(stringResource(R.string.settings_v3_parts_discard_pending))
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
// 2. 组级切换（FR-18）：整组下拉 + 可展开成员明细
// ═══════════════════════════════════════════════════════════════════════

/**
 * 一个组卡：组标题（组名 + 成员计数）+ 整组皮肤下拉 + 展开开关；展开后
 * 逐成员下拉（允许矩阵过滤）+ ⏳ 待生效徽标 + H-4 嵌套风险提示。
 * 默认只展开 G1/G3/G4（§13 风险对策：74 项设置页过长）。
 */
@Composable
private fun GroupCard(
    group: SkinGroup,
    overrides: PartSkinOverrides,
    previewOverrides: PartSkinOverrides,
    ctx: com.apex.agent.ui.theme.SkinContext,
    activation: SkinActivation,
    agent: AgentSettings,
    onUpdate: (AgentSettings) -> Unit
) {
    var expanded by remember { mutableStateOf(group in DEFAULT_EXPANDED_GROUPS) }
    val members = group.parts
    val groupLabel = stringResource(group.titleRes)
    val pendingInGroup = members.count { it in activation.changedParts }
    Column(Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .semantics {
                    contentDescription = "$groupLabel, ${members.size}, " +
                        (if (pendingInGroup > 0) "pending $pendingInGroup" else "")
                }
        ) {
            Text(
                text = groupLabel,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "${members.size}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
            if (pendingInGroup > 0) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "⏳ $pendingInGroup",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
            Spacer(Modifier.width(4.dp))
            Text(
                text = if (expanded) "▾" else "▸",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline
            )
        }
        // 整组皮肤下拉：选项 = 跟随（整组）+ 组内任一成员允许的皮肤并集
        val groupOptions = buildList {
            add(PartSkin.FOLLOW.key to stringResource(R.string.settings_v3_parts_group_follow))
            groupOptionSkins.forEach { skin ->
                if (members.any { it.allows(skin) }) {
                    add(skin.key to stringResource(skin.labelRes))
                }
            }
        }
        DropdownRow(
            label = groupLabel,
            options = groupOptions,
            selected = overrides.groups[group]?.key ?: PartSkin.FOLLOW.key,
            onSelected = { key ->
                // 组行「跟随」= 移除组键（组内成员回落各自回退链）
                if (key == PartSkin.FOLLOW.key) {
                    onUpdate(agent.copy(partSkins = agent.partSkins - group.key))
                } else {
                    onUpdate(agent.copy(partSkins = agent.partSkins + (group.key to key)))
                }
            }
        )
        AnimatedVisibility(visible = expanded) {
            Column(Modifier.fillMaxWidth()) {
                members.forEach { part ->
                    PartRow(
                        part = part,
                        overrides = overrides,
                        previewOverrides = previewOverrides,
                        ctx = ctx,
                        activation = activation,
                        agent = agent,
                        onUpdate = onUpdate
                    )
                }
            }
        }
    }
}

/** 单个部件行：下拉（允许矩阵过滤）+ ⏳ 徽标 + H-4 嵌套提示。 */
@Composable
private fun PartRow(
    part: CodingPart,
    overrides: PartSkinOverrides,
    previewOverrides: PartSkinOverrides,
    ctx: com.apex.agent.ui.theme.SkinContext,
    activation: SkinActivation,
    agent: AgentSettings,
    onUpdate: (AgentSettings) -> Unit
) {
    val options = buildList {
        add(PartSkin.FOLLOW.key to stringResource(R.string.part_skin_follow))
        partOptionSkins.forEach { skin ->
            if (part.allows(skin)) add(skin.key to stringResource(skin.labelRes))
        }
    }
    val selectedKey = overrides.map[part]?.key ?: PartSkin.FOLLOW.key
    val pendingBadge = part in activation.changedParts
    // H-4 提示：父为玻璃且显式选容器级玻璃/实色 → 可能产生嵌套容器（不禁止但告知）
    val nestedWarning = if (
        part.parentId != null &&
        selectedKey in setOf(PartSkin.PALE_LIQUID.key, PartSkin.CLASSIC_GLASS.key, PartSkin.SOLID.key)
    ) {
        stringResource(R.string.settings_v3_parts_nested_warning)
    } else {
        null
    }
    DropdownRow(
        label = stringResource(partLabelRes(part)) + (if (pendingBadge) " ⏳" else ""),
        options = options,
        selected = selectedKey,
        description = nestedWarning,
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
 * `partSkins`（Part.id → skin.key；组键保留——预设不越组）。「全部浅白」
 * 只写允许集 —— 终端 / 代码窗等 NEON 独占部件绝不入表（§11.1）。
 */
private fun applyPreset(
    agent: AgentSettings,
    onUpdate: (AgentSettings) -> Unit,
    preset: PartSkinPreset
) {
    val groupKeys = agent.partSkins.filterKeys { it.startsWith("@") }
    val serialized = preset.generateOverrides()
        .entries.associate { (part, skin) -> part.id to skin.key }
    onUpdate(agent.copy(partSkins = groupKeys + serialized))
}

/** 组行可选皮肤（展示序：浅白 → 经典 → 扁平 → 实色 → 霓虹 → 嵌入）。 */
private val groupOptionSkins = listOf(
    PartSkin.PALE_LIQUID,
    PartSkin.CLASSIC_GLASS,
    PartSkin.FLAT,
    PartSkin.SOLID,
    PartSkin.NEON_TERMINAL,
    PartSkin.EMBEDDED
)

/** 部件行可选皮肤（同上展示序；EMBEDDED 仅嵌入族/卡片族可见）。 */
private val partOptionSkins = groupOptionSkins

/** 默认展开的组（§13：设置页 74 项过长，默认只展开 G1/G3/G4）。 */
private val DEFAULT_EXPANDED_GROUPS = setOf(SkinGroup.G1, SkinGroup.G3, SkinGroup.G4)

/** 皮肤显示名（§5 → 字符串资源；a11y：不硬编码中文）。 */
private val PartSkin.labelRes: Int
    get() = when (this) {
        PartSkin.FOLLOW -> R.string.part_skin_follow
        PartSkin.PALE_LIQUID -> R.string.part_skin_pale_liquid
        PartSkin.CLASSIC_GLASS -> R.string.part_skin_classic_glass
        PartSkin.FLAT -> R.string.part_skin_flat
        PartSkin.SOLID -> R.string.part_skin_solid
        PartSkin.NEON_TERMINAL -> R.string.part_skin_neon_terminal
        PartSkin.EMBEDDED -> R.string.part_skin_embedded
    }

/** 生效方式显示名（v1.3 三档）。 */
private val SkinApplyMode.labelRes: Int
    get() = when (this) {
        SkinApplyMode.AUTO -> R.string.settings_v3_parts_mode_auto
        SkinApplyMode.IMMEDIATE -> R.string.settings_v3_parts_mode_immediate
        SkinApplyMode.ON_NEW_CHAT -> R.string.settings_v3_parts_mode_new_chat
    }

/** 组显示名（§4 分组 → 字符串资源）。 */
private val SkinGroup.titleRes: Int
    get() = when (this) {
        SkinGroup.G1 -> R.string.settings_v3_parts_group_g1
        SkinGroup.G2 -> R.string.settings_v3_parts_group_g2
        SkinGroup.G3 -> R.string.settings_v3_parts_group_g3
        SkinGroup.G4 -> R.string.settings_v3_parts_group_g4
        SkinGroup.G5 -> R.string.settings_v3_parts_group_g5
        SkinGroup.G6 -> R.string.settings_v3_parts_group_g6
        SkinGroup.G7 -> R.string.settings_v3_parts_group_g7
    }

/** 部件显示名（§4 注册表 74 项 → 字符串资源；a11y：不硬编码中文）。 */
internal fun partLabelRes(part: CodingPart): Int = when (part) {
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
    CodingPart.STREAM_THINKING_HEADER -> R.string.part_name_stream_thinking_header
    CodingPart.STREAM_THINKING_BODY -> R.string.part_name_stream_thinking_body
    CodingPart.STREAM_ASSISTANT_MARKDOWN -> R.string.part_name_stream_assistant_markdown
    CodingPart.STREAM_CODE_BLOCK -> R.string.part_name_stream_code_block
    CodingPart.STREAM_TOOL_ROW -> R.string.part_name_stream_tool_row
    CodingPart.STREAM_TOOL_CHIP -> R.string.part_name_stream_tool_chip
    CodingPart.STREAM_FILE_CHIP -> R.string.part_name_stream_file_chip
    CodingPart.STREAM_EXPAND_CONTROL -> R.string.part_name_stream_expand_control
    CodingPart.STREAM_USER_AVATAR -> R.string.part_name_stream_user_avatar
    CodingPart.STREAM_ASSISTANT_IDENTITY -> R.string.part_name_stream_assistant_identity
    CodingPart.STREAM_TIMESTAMP -> R.string.part_name_stream_timestamp
    CodingPart.STREAM_STREAM_CURSOR -> R.string.part_name_stream_stream_cursor
    CodingPart.STREAM_RETRY_BUTTON -> R.string.part_name_stream_retry_button
    CodingPart.STREAM_STATS_ROW -> R.string.part_name_stream_stats_row
    CodingPart.INPUT_BAR -> R.string.part_name_input_bar
    CodingPart.INPUT_QUEUE_CARD -> R.string.part_name_input_queue_card
    CodingPart.INPUT_QUESTION_CARD -> R.string.part_name_input_question_card
    CodingPart.INPUT_PLAN_CONFIRM -> R.string.part_name_input_plan_confirm
    CodingPart.INPUT_TEXT_FIELD -> R.string.part_name_input_text_field
    CodingPart.INPUT_SEND_BUTTON -> R.string.part_name_input_send_button
    CodingPart.INPUT_TOOL_ROW -> R.string.part_name_input_tool_row
    CodingPart.INPUT_SLASH_MENU -> R.string.part_name_input_slash_menu
    CodingPart.INPUT_SKILL_CHIP_FIELD -> R.string.part_name_input_skill_chip_field
    CodingPart.INPUT_TODO_PANEL -> R.string.part_name_input_todo_panel
    CodingPart.OVERLAY_ERROR_BAR -> R.string.part_name_overlay_error_bar
    CodingPart.OVERLAY_OFFLINE_BANNER -> R.string.part_name_overlay_offline_banner
    CodingPart.OVERLAY_UPDATE_BANNER -> R.string.part_name_overlay_update_banner
    CodingPart.OVERLAY_TOOL_DETAIL_SHEET -> R.string.part_name_overlay_tool_detail_sheet
    CodingPart.OVERLAY_DIALOG -> R.string.part_name_overlay_dialog
    CodingPart.OVERLAY_SHEET -> R.string.part_name_overlay_sheet
    CodingPart.OVERLAY_TOAST -> R.string.part_name_overlay_toast
    CodingPart.OVERLAY_IMAGE_LIGHTBOX -> R.string.part_name_overlay_image_lightbox
    CodingPart.OVERLAY_HTML_PREVIEW -> R.string.part_name_overlay_html_preview
    CodingPart.OVERLAY_GITHUB_MCP_DIALOG -> R.string.part_name_overlay_github_mcp_dialog
    CodingPart.OVERLAY_FEEDBACK -> R.string.part_name_overlay_feedback
    CodingPart.OVERLAY_RELIABILITY_TIER -> R.string.part_name_overlay_reliability_tier
    CodingPart.TERMINAL_PANEL -> R.string.part_name_terminal_panel
    CodingPart.CODE_DIFF -> R.string.part_name_code_diff
    CodingPart.CODE_STREAMING_WINDOW -> R.string.part_name_code_streaming_window
    CodingPart.TERMINAL_LINE -> R.string.part_name_terminal_line
    CodingPart.TERMINAL_PROMPT -> R.string.part_name_terminal_prompt
    CodingPart.CODE_DIFF_HEADER -> R.string.part_name_code_diff_header
    CodingPart.CODE_LANGUAGE_ICON -> R.string.part_name_code_language_icon
    CodingPart.SHELL_TOP_BAR -> R.string.part_name_shell_top_bar
    CodingPart.SHELL_WORKSPACE_BAR -> R.string.part_name_shell_workspace_bar
    CodingPart.SHELL_DRAWER -> R.string.part_name_shell_drawer
    CodingPart.SHELL_NAV_ITEM -> R.string.part_name_shell_nav_item
    CodingPart.SHELL_NEW_CHAT_BUTTON -> R.string.part_name_shell_new_chat_button
    CodingPart.SHELL_HISTORY_BUTTON -> R.string.part_name_shell_history_button
    CodingPart.SHELL_CONTEXT_RING -> R.string.part_name_shell_context_ring
    CodingPart.SHELL_BRAND_LOGO -> R.string.part_name_shell_brand_logo
    CodingPart.SETTINGS_SECTION_CARD -> R.string.part_name_settings_section_card
    CodingPart.SETTINGS_DROPDOWN_ROW -> R.string.part_name_settings_dropdown_row
    CodingPart.SETTINGS_SLIDER_ROW -> R.string.part_name_settings_slider_row
    CodingPart.SETTINGS_SWITCH_ROW -> R.string.part_name_settings_switch_row
    CodingPart.SETTINGS_PREVIEW_CARD -> R.string.part_name_settings_preview_card
    CodingPart.SETTINGS_PRESET_BUTTON -> R.string.part_name_settings_preset_button
    CodingPart.PAGE_ABOUT_CARD -> R.string.part_name_page_about_card
    CodingPart.PAGE_GLASS_LAB_SAMPLE -> R.string.part_name_page_glass_lab_sample
    CodingPart.PAGE_HISTORY_CARD -> R.string.part_name_page_history_card
    CodingPart.PAGE_LONG_TASK_CARD -> R.string.part_name_page_long_task_card
}
