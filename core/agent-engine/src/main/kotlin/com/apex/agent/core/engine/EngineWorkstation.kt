package com.apex.agent.core.engine

/**
 * ═══ 双工位（Workstation）绝对分离 ═══
 *
 * #197 把模式拆成两屏：Agent 屏（CHAT / AGENT / LOOP，全能智能体工位）与
 * Coding 屏（BUILD / PLAN / GOAL，编码工位）。工具面当时已机械隔离
 * （[EngineToolPlanner] 前缀黑名单 + scope 隔离），但系统提示词仍由共享的
 * [EnginePrompts.buildSystemPrompt] 一体构建 —— coding 会话沿用 agent 的
 * 「AI AGENT」身份行、聊天开放性 / 情感调频 / 首轮问候等聊天 UX 段，
 * agent 会话又拿到 coding 专属的任务路由推荐（推荐的工具实际已被过滤，
 * 声明与能力面不符）。
 *
 * 本枚举把「工位」显式建模为模式的一等属性，供提示词层按工位分轨：
 *  - **身份行分轨**：AGENT 工位自称「AI AGENT」（生活/知识/设备任务）；
 *    CODING 工位自称「CODING agent」（真实项目工作区里的编码智能体）；
 *  - **聊天 UX 段分轨**：Conversational Openness / First-Turn Greeting /
 *    Emotional Attunement / Vague Request 是聊天屏的交互规格，仅
 *    AGENT 工位注入 —— coding 会话是任务向的，不携带闲聊姿态；
 *  - **能力声明同源**：任务路由等能力性段落从本轮实际可见工具派生
 *    （见 [EnginePrompts]），不再硬编码可能已被过滤的工具名。
 *
 * 存量枚举（SPEC / REFLECTION / HUMAN_ASSIST / CUSTOM）从 v1/v2 单工位
 * 时代延续而来，历史上活跃于 Agent 屏 —— 保守映射到 AGENT 工位（仅老
 * 配置反序列化会命中，不出现在任何模式选择 UI）。
 */
enum class EngineWorkstation(val label: String) {
    /** Agent 聊天屏工位：全能智能体（生活 / 知识 / 系统 / 设备），非编程。 */
    AGENT("Agent"),

    /** Coding 编码屏工位：真实项目工作区里的编码智能体。 */
    CODING("Coding");

    companion object {
        /**
         * 按模式推导工位（模式 → 工位的单一映射，提示词层唯一判定入口）。
         *
         * 保守缺省 AGENT：未知/存量模式不改变其历史聊天屏行为。
         */
        fun of(mode: AgentMode): EngineWorkstation = mode.workstation
    }
}

/** 模式 → 工位推导（[EngineWorkstation.of] 的属性糖，测试与调用方直用）。 */
val AgentMode.workstation: EngineWorkstation
    get() = when (this) {
        AgentMode.CHAT, AgentMode.AGENT, AgentMode.LOOP,
        AgentMode.SPEC, AgentMode.REFLECTION, AgentMode.HUMAN_ASSIST, AgentMode.CUSTOM ->
            EngineWorkstation.AGENT
        AgentMode.BUILD, AgentMode.PLAN, AgentMode.GOAL -> EngineWorkstation.CODING
    }
