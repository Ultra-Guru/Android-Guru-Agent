package com.apex.agent.core.engine

import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import com.apex.agent.core.tools.catalog.ToolRequestBudget

/**
 * # 工具计划诊断（真机 P0 修复：预算丢弃不再静默）
 *
 * [ToolRequestBudget.RequestToolPlan.droppedByBudget] 此前在全仓**无任何
 * 消费者**——CORE 集越过请求预算后 `Write / WebSearch / ToolSearch` 等关键
 * 工具被每轮静默丢弃，模型「拿不到函数却被告知去用」，却无人能从日志里
 * 看出来。这里把预算截断落为 warn（[THROTTLE_MS] 节流，避免每轮迭代刷屏）。
 *
 * 与 [EngineToolPlanner] 分文件：规划器保持纯函数，日志副作用收口在此。
 */
internal object EnginePlanDiagnostics {

    private const val THROTTLE_MS = 30_000L

    @Volatile
    private var lastDropLogAt = 0L

    /** 预算截断留痕（空丢弃零开销）。@param now 假钟注入点（测试纪律）。 */
    fun reportDropped(
        plan: ToolRequestBudget.RequestToolPlan,
        now: () -> Long = System::currentTimeMillis
    ) {
        if (plan.droppedByBudget.isEmpty()) return
        val ts = now()
        if (ts - lastDropLogAt < THROTTLE_MS) return
        lastDropLogAt = ts
        AppLogger.instance.warn(
            LogCategory.TOOL, "EngineToolPlanner",
            "tools dropped by request budget: ${plan.droppedByBudget.size} " +
                "(kept=${plan.tools.size}, bytes=${plan.totalBytes}) → " +
                plan.droppedByBudget.take(12).joinToString(",")
        )
    }
}
