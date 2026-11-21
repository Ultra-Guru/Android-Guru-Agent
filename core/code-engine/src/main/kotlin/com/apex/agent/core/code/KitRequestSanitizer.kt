package com.apex.agent.core.code

import com.apex.agent.core.engine.task.DanglingToolCallRepair
import com.apex.agent.core.llm.LlmMessage
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory

/**
 * # Kit Request Sanitizer — kit 三线请求消毒（悬空 toolCall 配对自愈）
 *
 * **缺陷链路**（2026-11-01 实机诊断包截图复现，标准线）：
 * kit 引擎在 `session.append(Assistant(toolCalls))` 之后、ToolResult 补齐
 * 之前被硬取消（用户点停止 → VM `runJob.cancel()` 直接打断工具流收集，
 * 库内 `executeToolBatch` 的「中止补齐占位结果」收尾来不及执行）→
 * 会话（内存态 + 落盘）留下无配对的 `Assistant.toolCalls` → 用户再次
 * 发消息 / 点「继续」时，库引擎从**内存态** session 直接装配请求（不重走
 * `KitMemory.load`，且库内 `repairDanglingResults` 仅在引擎实例首跑执行）
 * → OpenAI 兼容端点 400（"tool_calls must be followed by tool messages"）
 * → 库容错按 REQUEST_REJECTION 降级为无工具纯文本重发（消息列表未动，
 * 配对仍破坏）→ 二次 400 → `Failed` 致命错误，会话级报废。
 *
 * **修复策略**（与深潜线 `ApexAgentEngine.buildMessages` 逐请求修补同款，
 * 但接到 kit 适配器的模型客户端端口——三条 kit 线所有出站请求的必经点）：
 * 每次请求前对已转换的 [LlmMessage] 列表跑 [DanglingToolCallRepair.repair]
 * 纯函数归一化——无悬空零开销（单趟扫描），有则就地合成/迁移/丢弃并在
 * 引擎侧留痕日志。无论库引擎内存态处于何种中断残局（硬取消 / 进程被杀 /
 * 压缩窗口切半），出站请求的配对协议恒成立。
 */
internal object KitRequestSanitizer {

    /**
     * 请求出站消毒：返回可直接发给 ModelRuntime 的干净消息列表。
     *
     * @param messages kit 协议消息转换后的宿主消息列表（原列表不被修改）
     * @param lineTag 留痕日志的调用方标签（如 "StandardKitAdapter"）
     */
    fun sanitize(messages: List<LlmMessage>, lineTag: String): List<LlmMessage> {
        val report = DanglingToolCallRepair.repair(messages)
        if (!report.hasRepairs) return messages
        AppLogger.instance.warn(
            LogCategory.ENGINE, lineTag,
            "request sanitized (dangling toolCall pairing repaired): " +
                "${report.repairedCallIds.size} synthesized, " +
                "${report.movedCallIds.size} moved back, " +
                "${report.droppedToolResultIds.size} orphan toolResults dropped, " +
                "${report.strippedCallIds.size} duplicate declarations stripped",
            tags = arrayOf("dangling-repair")
        )
        return report.repairedHistory
    }
}

/**
 * 读取 + 归一化：悬空修补（[DanglingToolCallRepair]）有动作时同步回写
 * 落盘记忆，返回可直接发送的干净历史。无悬空零开销、幂等可重入。
 *
 * 标准线原有实现（StandardKitAdapter 私有扩展）上收共享：织造/信使线的
 * `KitMemory.load` 此前是纯 load（无归一化）——同一缺陷在重启恢复路径上
 * 三线齐漏，现在统一走本入口。
 */
internal fun CodeConversationMemory.loadNormalized(
    tag: String = "KitMemory"
): List<LlmMessage> {
    val history = load()
    val report = DanglingToolCallRepair.repair(history)
    if (!report.hasRepairs) return history
    save(report.repairedHistory)
    AppLogger.instance.warn(
        LogCategory.ENGINE, tag,
        "kit-line history normalized on load: " +
            "${report.repairedCallIds.size} synthesized, " +
            "${report.movedCallIds.size} moved back, " +
            "${report.droppedToolResultIds.size} orphan toolResults dropped, " +
            "${report.strippedCallIds.size} duplicate declarations stripped",
        tags = arrayOf("dangling-repair")
    )
    return report.repairedHistory
}
