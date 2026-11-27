package com.apex.agent.tools

import com.apex.agent.core.tools.builtin.DeviceAuditQueryTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ═══ DCP 审计查询宿主（S10）═══
 *
 * app 层包装 [ToolAuditLogger.recentEntries]：按 intentId / 时间 / 决策
 * 过滤最近 DeviceOps 审计段（读取在 IO 调度器，jsonl 已被轮转压在 128KB 内）。
 * 只读不决策（审计不做权限门 —— S10 非目标纪律）。
 */
class DeviceAuditQueryHost(
    private val logger: ToolAuditLogger
) : DeviceAuditQueryTool.Host {

    override suspend fun query(
        intentId: String?,
        sinceMillis: Long?,
        decision: String?,
        limit: Int
    ): List<DeviceAuditQueryTool.Entry> = withContext(Dispatchers.IO) {
        logger.recentEntries(limit = 2000)
            .asSequence()
            .filter { it.tool == "DeviceOps" }
            .filter { intentId == null || it.intentId == intentId }
            .filter { sinceMillis == null || it.ts >= sinceMillis }
            .filter { decision == null || it.decision == decision }
            .take(limit)
            .map {
                DeviceAuditQueryTool.Entry(
                    ts = it.ts,
                    decision = it.decision,
                    intentId = it.intentId,
                    risk = it.risk,
                    privilegeTier = it.privilegeTier,
                    uid = it.uid,
                    dryRun = it.dryRun,
                    confirmToken = it.confirmToken,
                    reversible = it.reversible,
                    rollbackHint = it.rollbackHint,
                    auditId = it.auditId,
                    command = it.command,
                    success = it.success
                )
            }
            .toList()
    }
}
