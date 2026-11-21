package com.apex.agent.ui.screen.code

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apex.agent.R
import com.apex.agent.core.engine.AutonomyLevel
import com.apex.agent.core.engine.goal.GoalPhase
import com.apex.agent.core.engine.goal.GoalRuntimeState
import com.apex.agent.core.engine.goal.GoalSpec
import com.apex.agent.core.engine.goal.GoalStatus

/**
 * 目标设定弹层的提交结果（表单 → VM.startGoalModeGoal 的载荷）。
 * initialPrompt = 打开弹层时的预填草稿原样（目标启动后的首条提示）。
 */
internal data class GoalSetupResult(
    val statement: String,
    val criteria: String,
    val maxRounds: Int,
    val autonomy: AutonomyLevel,
    val preClarify: Boolean,
    val constraints: String = "",
    val nonGoals: String = "",
    val successMetrics: String = "",
    val knownRisks: String = "",
    val initialPrompt: String = ""
)

/**
 * # S1 — GOAL 目标设定弹层（v4 扩展 GoalSpec 全字段）
 *
 * 打开路径（弹层状态在 uiState.showGoalSetup / goalSetupDraft）：
 * 1. 切到 GOAL 模式且尚无活动目标（setMode → onEnterGoalMode 单点）；
 * 2. GOAL 模式下首条消息发送被拦截（maybeGoalFirstSend，草稿预填目标陈述）；
 * 3. Plan 确认卡「切换到 Goal」图标（switchToGoalFromPlan，计划目标预填）。
 *
 * 字段：目标陈述 / 可验证完成条件（多行 = 逐条验收清单）/ 最大验收轮次
 * （3/5/8/12/20 chips）+ **v4 新增**：自主级别（复用级联菜单同一套
 * [AutonomyOptionRow] 组件，GoalSpec 拍平快照）/ 前置澄清开关（仅完全
 * 自主显示）/ 高级字段折叠区（约束 / 非目标 / 成功指标 / 已知风险，全可选）。
 * 「开始」→ onStart 回调（VM.startGoalModeGoal：startGoal + 首条提示发送
 * + 关闭弹层清草稿 + 自主级别回写 Goal 模式记忆）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GoalSetupSheet(
    initialText: String,
    defaultMaxRounds: Int,
    defaultAutonomy: AutonomyLevel,
    defaultPreClarify: Boolean,
    onDismiss: () -> Unit,
    onStart: (GoalSetupResult) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // ── 表单状态 ──
    var statement by remember(initialText) { mutableStateOf(initialText) }
    var criteria by remember { mutableStateOf("") }
    var statementTouched by remember { mutableStateOf(false) }
    var criteriaTouched by remember { mutableStateOf(false) }
    // 非预设值（设置页滑杆可出 10 之类）保持为当前值，无 chip 选中
    var maxRounds by remember {
        mutableStateOf(defaultMaxRounds.coerceIn(1, ROUND_PRESETS.last()))
    }
    // v4：自主级别 + 前置澄清（本地态，开始时随 GoalSpec 拍平提交）
    var autonomy by remember { mutableStateOf(defaultAutonomy) }
    var preClarify by remember { mutableStateOf(defaultPreClarify) }
    // v4：高级可选字段（默认折叠，全空可跳过）
    var advancedExpanded by remember { mutableStateOf(false) }
    var constraints by remember { mutableStateOf("") }
    var nonGoals by remember { mutableStateOf("") }
    var successMetrics by remember { mutableStateOf("") }
    var knownRisks by remember { mutableStateOf("") }
    var helpExpanded by remember { mutableStateOf(false) }

    val statementValid = statement.isNotBlank()
    val criteriaValid = criteria.isNotBlank()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 640.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── 标题 ──
            Text(
                text = stringResource(R.string.goal_setup_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            // ── 目标陈述（做什么）──
            OutlinedTextField(
                value = statement,
                onValueChange = { statement = it; statementTouched = true },
                label = { Text(stringResource(R.string.goal_statement_label)) },
                placeholder = { Text(stringResource(R.string.goal_statement_placeholder)) },
                isError = statementTouched && !statementValid,
                supportingText = {
                    if (statementTouched && !statementValid) {
                        Text(stringResource(R.string.goal_setup_empty_statement))
                    }
                },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth()
            )

            // ── 完成条件（可验证；多行 = 逐条验收清单）──
            OutlinedTextField(
                value = criteria,
                onValueChange = { criteria = it; criteriaTouched = true },
                label = { Text(stringResource(R.string.goal_criteria_label)) },
                placeholder = { Text(stringResource(R.string.goal_criteria_placeholder)) },
                isError = criteriaTouched && !criteriaValid,
                supportingText = {
                    if (criteriaTouched && !criteriaValid) {
                        Text(stringResource(R.string.goal_setup_empty_criteria))
                    } else {
                        Text(stringResource(R.string.goal_criteria_hint))
                    }
                },
                minLines = 3,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth()
            )

            // ── 最大验收轮次（预设 chips；每轮 = 一次收尾 + 一次快速模型验收）──
            Text(
                text = stringResource(R.string.goal_max_rounds),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RoundChip(3, maxRounds) { maxRounds = it }
                RoundChip(5, maxRounds) { maxRounds = it }
                RoundChip(8, maxRounds) { maxRounds = it }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RoundChip(12, maxRounds) { maxRounds = it }
                RoundChip(20, maxRounds) { maxRounds = it }
            }

            // ── v4 自主级别（复用级联菜单同一套组件；GoalSpec 拍平快照）──
            Text(
                text = stringResource(R.string.autonomy_level_label),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            AutonomyLevelPanel(
                current = autonomy,
                preClarify = preClarify,
                compact = false,
                showHeader = false,
                onSelect = { autonomy = it },
                onTogglePreClarify = { preClarify = it }
            )

            // ── v4 高级字段折叠区（约束 / 非目标 / 成功指标 / 已知风险）──
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { advancedExpanded = !advancedExpanded }
                    .padding(vertical = 2.dp)
            ) {
                Text(
                    text = stringResource(R.string.goal_advanced_section),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (advancedExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(18.dp)
                )
            }
            if (advancedExpanded) {
                OutlinedTextField(
                    value = constraints,
                    onValueChange = { constraints = it },
                    label = { Text(stringResource(R.string.goal_field_constraints)) },
                    minLines = 1,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = nonGoals,
                    onValueChange = { nonGoals = it },
                    label = { Text(stringResource(R.string.goal_field_non_goals)) },
                    minLines = 1,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = successMetrics,
                    onValueChange = { successMetrics = it },
                    label = { Text(stringResource(R.string.goal_field_metrics)) },
                    minLines = 1,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = knownRisks,
                    onValueChange = { knownRisks = it },
                    label = { Text(stringResource(R.string.goal_field_risks)) },
                    minLines = 1,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // ── 帮助折叠行（工作方式一图流）──
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { helpExpanded = !helpExpanded }
                    .padding(vertical = 2.dp)
            ) {
                Text(
                    text = stringResource(R.string.goal_how_it_works),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (helpExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(18.dp)
                )
            }
            if (helpExpanded) {
                Text(
                    text = stringResource(R.string.goal_how_it_works_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ── 开始（点击即校验：空字段标红 + supportingText 提示）──
            Button(
                onClick = {
                    statementTouched = true
                    criteriaTouched = true
                    if (statementValid && criteriaValid) {
                        onStart(
                            GoalSetupResult(
                                statement = statement.trim(),
                                criteria = criteria.trim(),
                                maxRounds = maxRounds,
                                autonomy = autonomy,
                                preClarify = preClarify,
                                constraints = constraints.trim(),
                                nonGoals = nonGoals.trim(),
                                successMetrics = successMetrics.trim(),
                                knownRisks = knownRisks.trim(),
                                initialPrompt = initialText
                            )
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    // UI-012：48dp 触区红线
                    .sizeIn(minHeight = 48.dp)
            ) {
                Text(stringResource(R.string.goal_start))
            }
            Spacer(modifier = Modifier.size(8.dp))
        }
    }
}

/** 轮次预设 chip。 */
@Composable
private fun RoundChip(
    preset: Int,
    current: Int,
    onSelect: (Int) -> Unit
) {
    FilterChip(
        selected = current == preset,
        onClick = { onSelect(preset) },
        label = { Text(preset.toString()) }
    )
}

/** 轮次预设（3/5/8/12/20；末值兼作 goalMaxRounds 脏值钳制上界）。 */
private val ROUND_PRESETS = listOf(3, 5, 8, 12, 20)

/**
 * # S1 — GOAL 目标状态卡（v4 目标驱动状态机展示）
 *
 * 渲染策略：**只要存在目标（无论 ACTIVE/ACHIEVED/STOPPED）就显示**——切走
 * 模式也显示，用户可随时停止/重启。结构：
 * - 头行 Flag + 目标陈述 + 阶段行（阶段徽标 + 轮次进度）+ 操作按钮；
 * - 自主级别徽标行（GoalSpec 快照；完全自主附前置澄清状态）；
 * - **逐条验收清单**（完成条件按行拆分，每行 ✓/✗/· 状态 + 完成度统计，
 *   长清单限高滚动）；
 * - 下一步提示（按阶段状态机：前置澄清 → 规格就绪 → 执行 → 验收 →
 *   达成 / 阻塞）；
 * - 最近验收结论 + 规格详情折叠区（约束 / 非目标 / 指标 / 风险）。
 *
 * [pendingQuestion]：执行中挂起结构化提问时（完全自主 + 前置澄清 + 首轮）
 * 展示层叠加「前置澄清」阶段（引擎阶段仍为 EXECUTING，见
 * GoalModeCoordinator.markExecuting KDoc）。
 */
@Composable
internal fun GoalStatusCard(
    state: GoalRuntimeState,
    pendingQuestion: Boolean,
    onStop: () -> Unit,
    onResume: () -> Unit,
    modifier: Modifier = Modifier
) {
    var criteriaExpanded by remember { mutableStateOf(false) }
    var detailsExpanded by remember { mutableStateOf(false) }

    // 展示层阶段：首轮执行中挂起提问（前置澄清批量问题）→ 前置澄清
    val displayPhase = if (
        state.phase == GoalPhase.EXECUTING &&
        pendingQuestion &&
        state.roundsDone == 0 &&
        state.spec.preClarify &&
        AutonomyLevel.fromName(state.spec.autonomyLevel) == AutonomyLevel.FULLY
    ) GoalPhase.CLARIFYING else state.phase

    val cardCd = stringResource(R.string.goal_cd_goal_status)

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .semantics { contentDescription = cardCd }
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            // ── 头行：Flag + 陈述 + 阶段徽标 + 操作 ──
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Flag,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = state.spec.statement,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = statusLineOf(state, displayPhase),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                when (state.status) {
                    GoalStatus.ACTIVE -> StatusAction(
                        label = stringResource(R.string.goal_stop),
                        icon = Icons.Default.Stop,
                        onClick = onStop
                    )
                    GoalStatus.STOPPED -> StatusAction(
                        label = stringResource(R.string.goal_resume),
                        icon = Icons.Default.Replay,
                        onClick = onResume
                    )
                    GoalStatus.ACHIEVED -> Unit
                }
            }

            // ── 自主级别徽标行（GoalSpec 快照；完全自主附前置澄清状态）──
            AutonomyLevel.fromName(state.spec.autonomyLevel)?.let { autonomy ->
                val badge = stringResource(R.string.goal_autonomy_badge, autonomyShortName(autonomy)) +
                    if (autonomy == AutonomyLevel.FULLY && state.spec.preClarify) {
                        stringResource(R.string.autonomy_pre_clarify_on_suffix)
                    } else ""
                Text(
                    text = badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            // ── 逐条验收清单（完成条件按行拆分；✓/✗/· 状态 + 完成度）──
            val criteriaLines = state.spec.acceptanceCriteria.lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .take(GoalSpec.CRITERIA_LINES_LIMIT)
            if (criteriaLines.isNotEmpty()) {
                val results = state.lastCheck?.criteriaResults
                val passed = results?.count { it } ?: 0
                Text(
                    text = stringResource(
                        R.string.goal_criteria_progress,
                        if (results != null) passed else 0,
                        criteriaLines.size
                    ) + if (results == null) {
                        " · " + stringResource(R.string.goal_last_check_none)
                    } else "",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Column(
                    modifier = Modifier
                        .heightIn(max = 168.dp)
                        .verticalScroll(rememberScrollState())
                        .clickable { criteriaExpanded = !criteriaExpanded }
                ) {
                    criteriaLines.forEachIndexed { index, line ->
                        val verdict = results?.getOrNull(index)
                        val symbol = when {
                            verdict == null -> "·"
                            verdict -> "✓"
                            else -> "✗"
                        }
                        Row(
                            verticalAlignment = Alignment.Top,
                            modifier = Modifier.padding(top = 2.dp)
                        ) {
                            Text(
                                text = symbol,
                                style = MaterialTheme.typography.labelSmall,
                                color = when {
                                    verdict == null -> MaterialTheme.colorScheme.outline
                                    verdict -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.error
                                },
                                modifier = Modifier.width(14.dp)
                            )
                            Text(
                                text = line,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = if (criteriaExpanded) Int.MAX_VALUE else 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            // ── 下一步提示（阶段状态机口径）──
            Text(
                text = nextStepOf(displayPhase),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 2.dp)
            )

            // ── 最近验收（✓/✗ + 理由；无验收 = 尚未验收）──
            val check = state.lastCheck
            Text(
                text = if (check == null) {
                    stringResource(R.string.goal_last_check_none)
                } else {
                    // ✓/✗ 为文本符号（非 emoji）+ 语义色已有 outline 基调
                    (if (check.achieved) "✓ " else "✗ ") + check.reason
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )

            // ── 规格详情折叠区（约束 / 非目标 / 成功指标 / 已知风险）──
            val spec = state.spec
            if (spec.constraints.isNotBlank() || spec.nonGoals.isNotBlank() ||
                spec.successMetrics.isNotBlank() || spec.knownRisks.isNotBlank()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { detailsExpanded = !detailsExpanded }
                        .padding(top = 2.dp)
                ) {
                    Text(
                        text = stringResource(R.string.goal_spec_details),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        imageVector = if (detailsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(14.dp)
                    )
                }
                if (detailsExpanded) {
                    DetailLine(R.string.goal_field_constraints, spec.constraints)
                    DetailLine(R.string.goal_field_non_goals, spec.nonGoals)
                    DetailLine(R.string.goal_field_metrics, spec.successMetrics)
                    DetailLine(R.string.goal_field_risks, spec.knownRisks)
                }
            }
        }
    }
}

/** 规格详情单行（空字段省略）。 */
@Composable
private fun DetailLine(labelRes: Int, content: String) {
    if (content.isBlank()) return
    Text(
        text = stringResource(labelRes) + "：" + content,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp)
    )
}

/** 状态卡操作按钮（图标 + 文案，48dp 触区）。 */
@Composable
private fun StatusAction(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    TextButton(
        onClick = onClick,
        // UI-012：48dp 触区红线
        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(2.dp))
        Text(label, maxLines = 1)
    }
}

/** 阶段徽标行：ACTIVE 用阶段 + 轮次进度，终态只显文案。 */
@Composable
private fun statusLineOf(state: GoalRuntimeState, phase: GoalPhase): String = when (state.status) {
    GoalStatus.ACTIVE ->
        if (phase == GoalPhase.EXECUTING || phase == GoalPhase.VERIFYING) {
            phaseLabel(phase) + " · " + stringResource(
                R.string.goal_rounds_progress,
                state.roundsDone,
                state.spec.maxRounds
            )
        } else {
            phaseLabel(phase)
        }
    GoalStatus.ACHIEVED -> stringResource(R.string.goal_status_achieved)
    GoalStatus.STOPPED -> stringResource(R.string.goal_status_stopped)
}

/** 阶段本地化标签。 */
@Composable
private fun phaseLabel(phase: GoalPhase): String = stringResource(
    when (phase) {
        GoalPhase.CLARIFYING -> R.string.goal_phase_clarifying
        GoalPhase.SPEC_READY -> R.string.goal_phase_spec_ready
        GoalPhase.EXECUTING -> R.string.goal_phase_executing
        GoalPhase.VERIFYING -> R.string.goal_phase_verifying
        GoalPhase.DONE -> R.string.goal_phase_done
        GoalPhase.BLOCKED -> R.string.goal_phase_blocked
    }
)

/** 阶段对应的下一步提示。 */
@Composable
private fun nextStepOf(phase: GoalPhase): String = stringResource(
    when (phase) {
        GoalPhase.CLARIFYING -> R.string.goal_next_clarifying
        GoalPhase.SPEC_READY -> R.string.goal_next_spec_ready
        GoalPhase.EXECUTING -> R.string.goal_next_executing
        GoalPhase.VERIFYING -> R.string.goal_next_verifying
        GoalPhase.DONE -> R.string.goal_next_done
        GoalPhase.BLOCKED -> R.string.goal_next_blocked
    }
)
