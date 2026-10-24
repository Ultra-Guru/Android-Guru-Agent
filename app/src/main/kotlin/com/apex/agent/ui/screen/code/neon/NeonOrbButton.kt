package com.apex.agent.ui.screen.code.neon

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Difference
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.GppGood
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SentimentNeutral
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.ShortText
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apex.agent.R

/**
 * ═══ 霓虹球 mini 图标 + 功能聚合面板（Coding 输入栏）═══
 *
 * 输入栏工具行的霓虹球入口：呼吸辉光小球（未激活 = 静谧灰蓝微光；
 * 激活 = 赛博青→洋红霓虹），点击展开 16 项功能聚合面板（三组：
 * 会话增强 / 输出口径 / 编码偏好）。全部开关经 [NeonOrbStore] 持久化，
 * 每轮发送真实注入 system prompt（见 NeonOrbFeatures.kt 组装器）。
 *
 * 视觉语言与浏览器悬浮球 NeonRingView 同源（0xFF00F0FF 赛博青基色）；
 * 触区遵循 UI-012 48dp 红线（与斜杠/GitHub/小圆环/发送全行一致）。
 */
@Composable
fun NeonOrbButton(
    store: NeonOrbStore,
    modifier: Modifier = Modifier
) {
    val state by store.state.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }
    var formatExpanded by remember { mutableStateOf(false) }
    var langExpanded by remember { mutableStateOf(false) }
    var toneExpanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        // ── 霓虹球 mini 图标 ─────────────────────────────
        NeonOrbIcon(
            active = state.anyActive,
            activeCount = state.activeCount,
            contentDescription = stringResource(R.string.neon_cd_orb),
            onClick = { menuOpen = !menuOpen }
        )

        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)),
            tonalElevation = 4.dp,
            shadowElevation = 8.dp,
            modifier = Modifier.width(304.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(vertical = 4.dp)
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // ── 面板头：标题 + 激活计数 ─────────────────
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = stringResource(R.string.neon_panel_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    if (state.anyActive) {
                        Text(
                            text = stringResource(R.string.neon_active_count, state.activeCount),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.neon_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                )

                // ── 组一：会话增强（6 项）──────────────────
                NeonSectionHeader(text = stringResource(R.string.neon_group_session))
                NeonToggleRow(
                    icon = Icons.Default.Schedule,
                    label = stringResource(R.string.neon_feat_time_injection),
                    checked = state.timeInjection,
                    onClick = { store.setTimeInjection(!state.timeInjection) }
                )
                NeonToggleRow(
                    icon = Icons.Default.Search,
                    label = stringResource(R.string.neon_feat_web_search),
                    checked = state.webSearch,
                    onClick = { store.setWebSearch(!state.webSearch) }
                )
                // 结构化输出（单选档：JSON/XML/YAML/表格/TOML）
                NeonExclusiveRow(
                    icon = Icons.Default.DataObject,
                    label = if (state.outputFormat == NeonOutputFormat.NONE) {
                        stringResource(R.string.neon_feat_structured_output)
                    } else {
                        stringResource(
                            R.string.neon_feat_structured_output_fmt,
                            neonFormatLabel(state.outputFormat)
                        )
                    },
                    expanded = formatExpanded,
                    onClick = { formatExpanded = !formatExpanded }
                )
                AnimatedVisibility(
                    visible = formatExpanded,
                    enter = expandVertically(tween(200)),
                    exit = shrinkVertically(tween(160))
                ) {
                    Column {
                        NeonOutputFormat.entries.forEach { fmt ->
                            NeonOptionRow(
                                label = neonFormatLabel(fmt),
                                selected = state.outputFormat == fmt,
                                onClick = {
                                    store.setOutputFormat(
                                        if (state.outputFormat == fmt) NeonOutputFormat.NONE else fmt
                                    )
                                    formatExpanded = false
                                }
                            )
                        }
                    }
                }
                NeonToggleRow(
                    icon = Icons.Default.Psychology,
                    label = stringResource(R.string.neon_feat_deep_reasoning),
                    checked = state.deepReasoning,
                    onClick = { store.setDeepReasoning(!state.deepReasoning) }
                )
                NeonToggleRow(
                    icon = Icons.Default.Flag,
                    label = stringResource(R.string.neon_feat_goal_echo),
                    checked = state.goalEcho,
                    onClick = { store.setGoalEcho(!state.goalEcho) }
                )
                NeonToggleRow(
                    icon = Icons.Default.GppGood,
                    label = stringResource(R.string.neon_feat_anti_hallucination),
                    checked = state.antiHallucination,
                    onClick = { store.setAntiHallucination(!state.antiHallucination) }
                )

                // ── 组二：输出口径（6 项）──────────────────
                NeonSectionHeader(text = stringResource(R.string.neon_group_style))
                NeonToggleRow(
                    icon = Icons.Default.ShortText,
                    label = stringResource(R.string.neon_feat_concise),
                    checked = state.concise,
                    onClick = { store.setConcise(!state.concise) }
                )
                NeonToggleRow(
                    icon = Icons.Default.School,
                    label = stringResource(R.string.neon_feat_teach_mode),
                    checked = state.teachMode,
                    onClick = { store.setTeachMode(!state.teachMode) }
                )
                NeonToggleRow(
                    icon = Icons.Default.FormatListNumbered,
                    label = stringResource(R.string.neon_feat_numbered_steps),
                    checked = state.numberedSteps,
                    onClick = { store.setNumberedSteps(!state.numberedSteps) }
                )
                NeonToggleRow(
                    icon = Icons.Default.SentimentNeutral,
                    label = stringResource(R.string.neon_feat_no_emoji),
                    checked = state.noEmoji,
                    onClick = { store.setNoEmoji(!state.noEmoji) }
                )
                // 语言锁定（单选档）
                NeonExclusiveRow(
                    icon = Icons.Default.Language,
                    label = if (state.languageLock == NeonLanguageLock.OFF) {
                        stringResource(R.string.neon_feat_language_lock)
                    } else {
                        stringResource(
                            R.string.neon_feat_language_lock_fmt,
                            neonLangLabel(state.languageLock)
                        )
                    },
                    expanded = langExpanded,
                    onClick = { langExpanded = !langExpanded }
                )
                AnimatedVisibility(
                    visible = langExpanded,
                    enter = expandVertically(tween(200)),
                    exit = shrinkVertically(tween(160))
                ) {
                    Column {
                        NeonLanguageLock.entries.forEach { lock ->
                            NeonOptionRow(
                                label = neonLangLabel(lock),
                                selected = state.languageLock == lock,
                                onClick = { store.setLanguageLock(lock); langExpanded = false }
                            )
                        }
                    }
                }
                // 语气人设（单选档）
                NeonExclusiveRow(
                    icon = Icons.Default.RecordVoiceOver,
                    label = if (state.tone == NeonTone.OFF) {
                        stringResource(R.string.neon_feat_tone)
                    } else {
                        stringResource(R.string.neon_feat_tone_fmt, neonToneLabel(state.tone))
                    },
                    expanded = toneExpanded,
                    onClick = { toneExpanded = !toneExpanded }
                )
                AnimatedVisibility(
                    visible = toneExpanded,
                    enter = expandVertically(tween(200)),
                    exit = shrinkVertically(tween(160))
                ) {
                    Column {
                        NeonTone.entries.forEach { tone ->
                            NeonOptionRow(
                                label = neonToneLabel(tone),
                                selected = state.tone == tone,
                                onClick = { store.setTone(tone); toneExpanded = false }
                            )
                        }
                    }
                }

                // ── 组三：编码偏好（4 项）──────────────────
                NeonSectionHeader(text = stringResource(R.string.neon_group_coding))
                NeonToggleRow(
                    icon = Icons.Default.Notes,
                    label = stringResource(R.string.neon_feat_rich_comments),
                    checked = state.richComments,
                    onClick = { store.setRichComments(!state.richComments) }
                )
                NeonToggleRow(
                    icon = Icons.Default.Difference,
                    label = stringResource(R.string.neon_feat_diff_first),
                    checked = state.diffFirst,
                    onClick = { store.setDiffFirst(!state.diffFirst) }
                )
                NeonToggleRow(
                    icon = Icons.Default.WarningAmber,
                    label = stringResource(R.string.neon_feat_risk_warnings),
                    checked = state.riskWarnings,
                    onClick = { store.setRiskWarnings(!state.riskWarnings) }
                )
                NeonToggleRow(
                    icon = Icons.Default.Shield,
                    label = stringResource(R.string.neon_feat_safe_commands),
                    checked = state.safeCommands,
                    onClick = { store.setSafeCommands(!state.safeCommands) }
                )

                // ── 尾部：一键全关（有激活项才显示）────────
                if (state.anyActive) {
                    TextButton(
                        onClick = { store.resetAll() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp)
                    ) {
                        Text(stringResource(R.string.neon_reset_all))
                    }
                }
            }
        }
    }
}

// ═══ 霓虹球本体（Canvas 辉光小球）═══

/** 赛博青（与浏览器悬浮球 NeonRingView 基色同源）。 */
private val NeonCyan = Color(0xFF00F0FF)

/** 激活态洋红（青→洋红渐变的远端色，霓虹感来源）。 */
private val NeonMagenta = Color(0xFFB44DFF)

/**
 * 呼吸辉光霓虹球：外圈双层辉光（径向渐变，随 [pulse] 呼吸）+ 球体
 * （青核渐变）+ 高光点。未激活时辉光收敛为 22% 暗物质观感；激活时
 * 全亮并叠洋红流光。右上角徽标显示激活功能数。
 */
@Composable
private fun NeonOrbIcon(
    active: Boolean,
    activeCount: Int,
    contentDescription: String,
    onClick: () -> Unit
) {
    val transition = rememberInfiniteTransition(label = "neonOrbPulse")
    val pulse by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "neonOrbGlow"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            // UI-012：48dp 触区红线（与输入栏工具行其余按钮一致）
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .clickable(onClick = onClick)
    ) {
        // 辉光小球本体（视觉 ~20dp，居中于 48dp 触区）
        Canvas(modifier = Modifier.size(20.dp)) {
            val radius = size.minDimension / 2f
            val center = Offset(radius, radius)
            val glow = if (active) pulse else pulse * 0.35f + 0.1f

            // 外层辉光（洋红，激活态才显形 —— 青→洋红双色霓虹）
            if (active) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            NeonMagenta.copy(alpha = 0.30f * glow),
                            Color.Transparent
                        ),
                        center = center,
                        radius = radius * 1.85f
                    ),
                    radius = radius * 1.85f
                )
            }
            // 主辉光（赛博青）
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        NeonCyan.copy(alpha = (if (active) 0.40f else 0.16f) * glow),
                        Color.Transparent
                    ),
                    center = center,
                    radius = radius * 1.5f
                ),
                radius = radius * 1.5f
            )
            // 球体（偏心高光渐变 —— 玻璃球质感）
            drawCircle(
                brush = Brush.radialGradient(
                    colors = if (active) {
                        listOf(
                            Color(0xFFD6FEFF),
                            NeonCyan,
                            Color(0xFF0A5E6E)
                        )
                    } else {
                        listOf(
                            Color(0xFF9AA7B4),
                            Color(0xFF5B6875),
                            Color(0xFF39434E)
                        )
                    },
                    center = Offset(radius * 0.62f, radius * 0.58f),
                    radius = radius * 1.15f
                ),
                radius = radius
            )
            // 高光点
            drawCircle(
                color = Color.White.copy(alpha = if (active) 0.9f else 0.45f),
                radius = radius * 0.16f,
                center = Offset(radius * 0.62f, radius * 0.55f)
            )
        }

        // 激活徽标（右上角小计数）
        if (active && activeCount > 0) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .padding(start = 28.dp, top = 4.dp)
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(NeonCyan)
            ) {
                Text(
                    text = if (activeCount > 9) "9+" else activeCount.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF06272B),
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

// ═══ 面板行组件 ═══

/** 分组小节标题。 */
@Composable
private fun NeonSectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 2.dp)
    )
}

/** 开关行（13 个布尔功能共用）。 */
@Composable
private fun NeonToggleRow(
    icon: ImageVector,
    label: String,
    checked: Boolean,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 12.dp, end = 8.dp, top = 2.dp, bottom = 2.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (checked) NeonCyan else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = if (checked) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** 单选档入口行（点开选档，标签回显当前档位）。 */
@Composable
private fun NeonExclusiveRow(
    icon: ImageVector,
    label: String,
    expanded: Boolean,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 2.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (expanded) NeonCyan else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        if (expanded) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = NeonCyan,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/** 单选档选项行（选中 ✓；「关闭/跟随」档可点回）。 */
@Composable
private fun NeonOptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 40.dp, end = 12.dp, top = 7.dp, bottom = 7.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = if (selected) NeonCyan else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        if (selected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = NeonCyan,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

// ═══ 枚举 → i18n 文案（枚举 label 为 core 侧兜底词，UI 层本地化）═══

@Composable
private fun neonFormatLabel(fmt: NeonOutputFormat): String = when (fmt) {
    NeonOutputFormat.NONE -> stringResource(R.string.neon_fmt_none)
    NeonOutputFormat.JSON -> "JSON"
    NeonOutputFormat.XML -> "XML"
    NeonOutputFormat.YAML -> "YAML"
    NeonOutputFormat.MARKDOWN_TABLE -> stringResource(R.string.neon_fmt_md_table)
    NeonOutputFormat.TOML -> "TOML"
}

@Composable
private fun neonLangLabel(lock: NeonLanguageLock): String = when (lock) {
    NeonLanguageLock.OFF -> stringResource(R.string.neon_lang_follow)
    NeonLanguageLock.CHINESE -> stringResource(R.string.neon_lang_zh)
    NeonLanguageLock.ENGLISH -> stringResource(R.string.neon_lang_en)
}

@Composable
private fun neonToneLabel(tone: NeonTone): String = when (tone) {
    NeonTone.OFF -> stringResource(R.string.neon_tone_default)
    NeonTone.PROFESSIONAL -> stringResource(R.string.neon_tone_professional)
    NeonTone.FRIENDLY -> stringResource(R.string.neon_tone_friendly)
    NeonTone.DIRECT -> stringResource(R.string.neon_tone_direct)
}
