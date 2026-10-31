package com.apex.agent.ui.screen.code

import com.apex.agent.core.code.stream.StreamEntry

/**
 * # CodeOutputMode — Coding 工位输出展示模式（双档）
 *
 * ## 设计立场（用户需求：「模仿 Rikka Hub 的输出方式，再做一种，可切换」）
 *
 * 两档共享同一份 [com.apex.agent.core.code.stream.CodeStreamSnapshot] 渲染
 * 通道与消息模型（不维护两套列表），只差**展示策略**：
 *
 * - [STREAMING]（默认，Rikka 风格）：逐字/逐胶囊实时渲染——结论气泡流式
 *   追加 + 光标、思考链流式展开、实时代码窗、终端尾窗随输出贴尾。即
 *   capsule-stream 既有行为，零变化。
 * - [COMPLETE]（整段模式）：生成中**只显示加载进度卡**（呼吸指示 + 已用
 *   时 + 已调用工具数），本轮运行的过程条目（思考/胶囊/尾窗/结论）全部
 *   静默；run 收束后一次性渲染完整时间轴并滚动一次到底。对应规格书
 *   「完整模式：生成完成后一次性展示，生成中显示加载态」。
 *
 * ## 切换语义（规格书交互规则）
 *
 * 「切换只影响下一条消息或完成后的消息」——渲染档在**每轮 run 开始时
 * 捕获**（[CodeOutputModeOps.captureRunOutputMode]），运行中改设置不打断
 * 当前轮的展示风格；收束后下一轮按新档渲染。两档的终态渲染完全一致
 * （完成后都是完整内容 + 折叠胶囊），差异只存在于生成过程中。
 *
 * ## 引擎零参与
 *
 * 纯 UI 展示开关：胶囊时间轴会话（CodeStreamSession）与引擎事件双通道
 * 照常消费全部事件（长任务追踪/落盘/深水区观察不受影响），完整模式只是
 * 渲染侧的「静默遮罩」——切换不丢任何会话数据。
 */
enum class CodeOutputMode(val persistenceName: String) {

    /** 流式模式：实时逐字显示 + 实时滚动跟随（Rikka 风格，既有行为）。 */
    STREAMING("streaming"),

    /** 整段模式：生成中显示加载进度卡，完成后一次性渲染全部条目。 */
    COMPLETE("complete");

    companion object {
        /** 持久化字符串 → 档位（空/未知值回退 STREAMING）。 */
        fun fromName(name: String?): CodeOutputMode? =
            entries.firstOrNull { it.persistenceName == name?.trim()?.lowercase() }
    }
}

/** AgentSettings.codeOutputMode 原始字符串 → 档位（容错兜底 STREAMING）。 */
internal fun String?.toCodeOutputMode(): CodeOutputMode =
    CodeOutputMode.fromName(this) ?: CodeOutputMode.STREAMING

/**
 * 完整模式生成中的**可见条目切片**：保留最近一条用户输入（含）之前的全部
 * 历史——即「上一轮及更早的终态内容照常显示，本轮运行的过程条目全部静默」。
 *
 * run 边界判定：每次 runEngine 都经 CodeStreamSession.beginRun 追加一条
 * [StreamEntry.UserEntry]，故「最后一条 UserEntry」即本轮起点（含）；
 * 其后的思考/胶囊/状态/结论全部属于在飞轮次，由加载进度卡代为占位。
 *
 * 防御性兜底：无 UserEntry（理论上 run 中不可能）时原样返回全部条目
 * （fail-open——宁可漏静默也不吞历史）。
 */
internal fun completeModeVisibleEntries(entries: List<StreamEntry>): List<StreamEntry> {
    val lastUserIndex = entries.indexOfLast { it is StreamEntry.UserEntry }
    // toList 防御性拷贝：subList 是底层列表的视图，session 侧重建快照时
    // 的结构性变更会让陈旧视图抛 ConcurrentModification——切片必须独立。
    return if (lastUserIndex < 0) entries.toList() else entries.subList(0, lastUserIndex + 1).toList()
}
