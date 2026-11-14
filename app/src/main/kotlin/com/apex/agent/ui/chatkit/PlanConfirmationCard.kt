package com.apex.agent.ui.chatkit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.apex.agent.R
import com.apex.agent.core.engine.ExecutionPlan
import com.apex.agent.ui.glass.PartSurface
import com.apex.agent.ui.theme.CodingPart

/**
 * #169 计划确认卡（人控；v4 升级三规格区块 + Goal 流转入口）：
 *
 * - **三规格区块**（对应计划的三份规格呈现）：计划目标（goal）/ 规划
 *   依据（reasoning，可折叠）/ 执行步骤（每步 Checkbox 默认勾选 +
 *   上移/下移重排，未勾选步骤灰色划线）；
 * - 头行右侧 **Flag 图标**（[onSwitchToGoal]）：带此计划切到 Goal 模式
 *   （VM 侧驳回挂起计划 + 目标陈述预填计划目标，见 switchToGoalFromPlan）；
 * - 「开始执行方案」保持在卡片最末（v4 文案，点击后由调用方切 Build
 *   执行），未勾选任何步骤时禁用（引擎侧对空集另有防御回退，见
 *   PlanGraph.applyAdjustments）。
 *
 * 提交语义：onConfirm 回传（勾选的原 index 集，展示顺序的原 index 清单）；
 * 引擎 Phase 3.5 用 PlanGraph 应用筛选/重排并做 dependsOn 拓扑校验 ——
 * 用户顺序若与依赖冲突，拓扑排序自动纠正并附警告。
 */
@Composable
internal fun PlanConfirmationCard(
    plan: ExecutionPlan,
    onConfirm: (enabledSteps: List<Int>, order: List<Int>) -> Unit,
    onReject: () -> Unit,
    onSwitchToGoal: (() -> Unit)? = null
) {
    // 人控状态：勾选集 + 展示顺序（均存「原 step.index」；确认时整体回传，
    // UI 不预演拓扑结果 —— 锁定前的最终顺序由引擎决定）。
    val enabledIds = remember(plan) {
        mutableStateMapOf<Int, Boolean>().apply { plan.steps.forEach { put(it.index, true) } }
    }
    val orderIds = remember(plan) {
        mutableStateListOf<Int>().apply { addAll(plan.steps.map { it.index }) }
    }
    val anyEnabled = orderIds.any { enabledIds[it] == true }
    var reasoningExpanded by remember { mutableStateOf(false) }
    val switchGoalCd = stringResource(R.string.plan_switch_to_goal_cd)

    // v1.7 部件皮肤（PartSkin spec）：计划确认卡走 PartSurface(input.plan_confirm)
    // —— 新默认 PALE_LIQUID 浅白玻璃（secondary 描边淘汰，语义色只上徽标/
    // 文字，FR-11）；单层容器（FR-12），16dp 内容 padding 由 contentPadding 贡献。
    PartSurface(
        part = CodingPart.INPUT_PLAN_CONFIRM,
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = stringResource(R.string.chat_confirm_plan_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            // 规划期只读徽标（#169）：Agent 处于只读规划阶段，尚未执行任何操作
            Surface(
                color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.14f),
                shape = RoundedCornerShape(6.dp)
            ) {
                Text(
                    text = stringResource(R.string.plan_planning_readonly),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                )
            }
            // v4：带此计划切到 Goal 模式（驳回挂起计划 + 目标陈述预填）
            if (onSwitchToGoal != null) {
                IconButton(
                    onClick = onSwitchToGoal,
                    // UI-012：48dp 触区红线
                    modifier = Modifier
                        .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        .semantics { contentDescription = switchGoalCd }
                ) {
                    Icon(
                        Icons.Default.Flag,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.plan_adjust_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))

        // ═══ v4 三规格区块 ═══
        // ① 计划目标（做什么）
        if (plan.goal.isNotBlank()) {
            SpecSectionLabel(text = stringResource(R.string.plan_section_goal))
            Text(
                text = plan.goal,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(6.dp))
        }
        // ② 规划依据（为什么这样规划，可折叠）
        if (plan.reasoning.isNotBlank()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { reasoningExpanded = !reasoningExpanded }
            ) {
                Text(
                    text = stringResource(R.string.plan_section_reasoning),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (reasoningExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
            if (reasoningExpanded) {
                Text(
                    text = plan.reasoning,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
        }
        // ③ 执行步骤（勾选 / 重排）
        SpecSectionLabel(text = stringResource(R.string.plan_section_steps))

        orderIds.forEachIndexed { displayPos, stepId ->
            val step = plan.steps.firstOrNull { it.index == stepId } ?: return@forEachIndexed
            val checked = enabledIds[stepId] == true
            val stepEnableDesc = stringResource(R.string.plan_step_enable)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 1.dp)
            ) {
                Checkbox(
                    checked = checked,
                    onCheckedChange = { enabledIds[stepId] = it },
                    modifier = Modifier
                        .size(32.dp)
                        .semantics { contentDescription = stepEnableDesc }
                )
                Text(
                    text = "${displayPos + 1}.",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (checked) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = step.description,
                    style = MaterialTheme.typography.bodySmall,
                    // 未勾选步骤：灰色 + 划线（执行时将被跳过）
                    color = if (checked) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    textDecoration = if (checked) null else TextDecoration.LineThrough,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = {
                        if (displayPos > 0) orderIds.add(displayPos - 1, orderIds.removeAt(displayPos))
                    },
                    enabled = displayPos > 0,
                    // UI-012：48dp 触区红线（原 28dp）
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                ) {
                    Icon(
                        Icons.Default.KeyboardArrowUp,
                        stringResource(R.string.plan_move_up),
                        Modifier.size(18.dp)
                    )
                }
                IconButton(
                    onClick = {
                        if (displayPos < orderIds.lastIndex) orderIds.add(displayPos + 1, orderIds.removeAt(displayPos))
                    },
                    enabled = displayPos < orderIds.lastIndex,
                    // UI-012：48dp 触区红线（原 28dp）
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                ) {
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        stringResource(R.string.plan_move_down),
                        Modifier.size(18.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        // 「开始执行方案」保持在卡片最末（点击后由调用方切 Build 执行）；空选禁用
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onReject) { Text(stringResource(R.string.chat_cancel)) }
            Button(
                enabled = anyEnabled,
                onClick = {
                    onConfirm(orderIds.filter { enabledIds[it] == true }, orderIds.toList())
                }
            ) { Text(stringResource(R.string.plan_start_execution)) }
        }
    }
}

/** 规格区块小标题（三规格区块共用）。 */
@Composable
private fun SpecSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
