package com.apex.agent.ui.screen.code

/**
 * # CodeOutputStyle — Coding 工位输出风格（双风格）
 *
 * ## 设计立场（用户需求：「模仿 Rikka Hub 的输出方式，再做一种，与我原来的可以切换」）
 *
 * 与 [CodeOutputMode]（流式 / 整段 = **揭示时机**维度）正交，本枚举承载
 * **视觉形态**维度——两风格共享同一份
 * [com.apex.agent.core.code.stream.CodeStreamSnapshot] 渲染通道与消息
 * 模型（不维护两套列表），只差展示皮肤：
 *
 * - [TIMELINE]（默认，经典胶囊时间轴）：既有行为零变化——胶囊流水 /
 *   同族聚合防刷屏 / 实时代码窗 / 内联终端尾窗（capsule-stream 规格书）。
 * - [RIKKA]（Rikka 风格·聊天式）：模仿 Rikka Hub 的聊天输出——
 *   用户右对齐实色气泡、结论 Markdown 气泡 + 流式光标、思考过程折叠卡
 *   （流式展开跟随 / 收束自动折叠）、**工具调用聚合块**（连续工具调用
 *   收进一张卡：流式中「正在调用工具…」+ 实时行，收束后折叠为
 *   「已调用 N 个工具」+ 内置 / 技能 / MCP 三分类 chips，可展开每次
 *   调用明细——工具名 / 参数摘要 / 状态 / 耗时，点击进详情弹层看
 *   diff / 终端全文）、验证轮次卡 / Git 可视化卡 / 运行统计卡同风格化。
 *
 * ## 切换语义（与输出方式档同款 run 粒度快照）
 *
 * 「切换只影响下一条消息或完成后的消息」——风格在**每轮 run 开始时
 * 捕获**（[CodeOutputStyleOps.captureRunOutputStyle]），运行中改设置
 * 不打断当前轮的展示皮肤；idle 期经设置流**实时同步**
 * （[CodeOutputStyleOps.observeOutputStyle]，切完回屏立即生效）。
 * 两风格共享同一套溯源数据（引擎事件携带 skill / mcp / builtin
 * 分类）——技能星芒 / MCP 拼图标识在两风格下同源。
 *
 * ## 引擎零参与
 *
 * 纯 UI 展示开关：CodeStreamSession 与引擎事件双通道照常消费全部事件
 * （长任务追踪 / 落盘 / 深水区观察不受影响），风格切换只是渲染组件
 * 的分派选择——切换不丢任何会话数据。
 */
enum class CodeOutputStyle(val persistenceName: String) {

    /** 经典胶囊时间轴（既有行为，默认）。 */
    TIMELINE("timeline"),

    /** Rikka 风格聊天式输出（气泡 + 工具调用聚合块）。 */
    RIKKA("rikka");

    companion object {
        /** 持久化字符串 → 风格（空/未知值回退 TIMELINE）。 */
        fun fromName(name: String?): CodeOutputStyle? =
            entries.firstOrNull { it.persistenceName == name?.trim()?.lowercase() }
    }
}

/** AgentSettings.codeOutputStyle 原始字符串 → 风格（容错兜底 TIMELINE）。 */
internal fun String?.toCodeOutputStyle(): CodeOutputStyle =
    CodeOutputStyle.fromName(this) ?: CodeOutputStyle.TIMELINE
