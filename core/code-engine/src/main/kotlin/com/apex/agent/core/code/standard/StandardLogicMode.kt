package com.apex.agent.core.code.standard

/**
 * # Standard Logic Mode — Coding 模式思考逻辑双引擎
 *
 * Coding 模式的「完成任务逻辑」有两个并列引擎，用户在 Coding 屏右上角切换：
 *
 * - [DEEP_DIVE]（深潜）：**项目自研**的思考逻辑——七档思考阶梯
 *   （[com.apex.agent.core.code.thinking.CodeThinkingLevel]）+ AUTO 预检选档 +
 *   深水区升级 + 旋钮补偿，全部骑在 `ApexAgentEngine` 的 BUILD/PLAN 循环上。
 *   这是 v1.2 起的既有行为，本枚举只是给它一个正式名字；
 * - [STANDARD]（标准）：**业界标准 Agent 任务循环**——会话消息模型、
 *   Agent 画像（构建者/规划师/通用/探索/调研）、权限三态门（allow/ask/deny +
 *   模式兜底 + 规则 + 会话记忆）、隔离上下文子代理委派、上下文压缩、
 *   工具面（read/write/edit/bash/glob/grep/todo/task）映射到既有注册表
 *   （code_* / shell_execute / git / web / skill / mcp__*）。
 *   由独立库模块实现（com.apex.agentkit:agent-loop，经
 *   [StandardKitAdapter] 接线），不重写、不影响深潜引擎。
 *
 * ## 两引擎的关系
 *
 * | 维度 | 深潜 | 标准 |
 * |---|---|---|
 * | 循环宿主 | ApexAgentEngine BUILD/PLAN | agent-loop 库自有回合循环 |
 * | 思考深度 | 七档 + AUTO 自适应 | 画像自带预算（档位映射为回合预算倍率） |
 * | 工具调用 | v4 渐进披露 + 风险门 | 标准权限门 + 会话记忆 + 命令通配 |
 * | 子代理 | code_task → SubAgentRunner | task → 库内派发器（同构子引擎） |
 * | 会话记忆 | code_memory（共享通道） | code_memory_standard（独立通道，互不污染） |
 * | 事件协议 | AgentEvent | AgentEvent（同一协议，胶囊时间轴零改动） |
 *
 * ## 持久化
 *
 * `AgentSettings.codeThinkingLogic`："" / "deep_dive" = 深潜（默认，历史行为）；
 * "standard" = 标准。恢复逻辑见 [fromName]（未知值回退深潜，脏值不致崩溃）。
 */
enum class StandardLogicMode(
    /** 持久化名（小写稳定，跨版本兼容）。 */
    val persistenceName: String,
    /** 是否标准任务循环引擎。 */
    val isStandard: Boolean
) {
    /** 深潜（自研思考逻辑，v1.2 既有行为）。 */
    DEEP_DIVE("deep_dive", isStandard = false),

    /** 标准（业界标准 Agent 任务循环）。 */
    STANDARD("standard", isStandard = true);

    companion object {
        /**
         * 持久化字符串解析（大小写不敏感；空/未知 → null 由调用方兜底深潜）。
         *
         * 同时接受历史值 "custom"/"apex"（早期实验名）一律折叠为深潜，
         * 防止脏值把用户无声切进新引擎。
         */
        fun fromName(name: String?): StandardLogicMode? =
            name?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
                ?.let { raw ->
                    when (raw) {
                        DEEP_DIVE.persistenceName, "deep", "apex", "custom" -> DEEP_DIVE
                        STANDARD.persistenceName, "std" -> STANDARD
                        else -> null
                    }
                }
    }
}
