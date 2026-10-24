package com.apex.agent.core.engine

import com.apex.agent.core.llm.ToolCall

/**
 * ═══ 工具参数增量发射（参数流式可视化的引擎半边）═══
 *
 * 用户需求：「调用写文件/编辑文件工具时，能实时看到代码不断写入」。
 * 模型流式生成 tool_calls 时 arguments 逐帧到达 —— [ApexAgentEngine] 的
 * ReAct 循环把这些分片喂给 [StreamingToolCallAccumulator]（旧管线只累积
 * 不上抛，UI 要等流结束的 ToolCallStart 才拿到全量参数）。
 *
 * 本扩展在累积的同时发射 [AgentEvent.ToolCallArgsDelta]：每个分片一条，
 * 消费端（Coding 胶囊时间轴）按 callId 幂等合并、容错提取半截 JSON 里的
 * 文件内容，即可渲染「正在写入」的实时代码窗。
 *
 * 放独立文件的原因：ApexAgentEngine.kt 已贴死 1200 行文件预算 —— 主循环
 * 只做 1:1 换行（`acc.append(...)` → `appendFragmentAndEmitDelta(...)`），
 * 全部新逻辑落在本文件（与 EngineToolExecution 同款「扩展迁出」模式）。
 */
internal suspend fun appendFragmentAndEmitDelta(
    acc: StreamingToolCallAccumulator,
    fragment: ToolCall,
    emit: suspend (AgentEvent) -> Unit
) {
    // 1) 先累积（单一真源：acc.build() 产出的全量参数与旧管线零差异）
    acc.append(fragment.name, fragment.arguments)
    // 2) 再上抛增量。名与参数全空的分片没有信息量，不上抛（每帧必发的
    //    provider 会在会话侧堆积零字节事件）；仅带名字的首片也上抛 ——
    //    消费端据此提前建「正在生成参数」的等待态胶囊，工具意图先于
    //    参数可见。名字晚到的 provider（首片名空）由消费端缓冲兜底。
    if (fragment.name.isBlank() && fragment.arguments.isEmpty()) return
    emit(
        AgentEvent.ToolCallArgsDelta(
            callId = acc.id,
            toolName = acc.name,
            delta = fragment.arguments
        )
    )
}
