package com.apex.agent.core.code.standard

/**
 * # Standard Logic Mode — Coding 模式思考逻辑档位（四线）
 *
 * Coding 模式的「完成任务逻辑」有四个并列引擎，用户在 Coding 屏右上角切换：
 *
 * - [DEEP_DIVE]（深潜）：**项目自研**的思考逻辑——七档思考阶梯
 *   （[com.apex.agent.core.code.thinking.CodeThinkingLevel]）+ AUTO 预检选档 +
 *   深水区升级 + 旋钮补偿，全部骑在 `ApexAgentEngine` 的 BUILD/PLAN 循环上。
 *   这是 v1.2 起的既有行为，本枚举只是给它一个正式名字；
 * - [STANDARD]（标准）：**业界标准 Agent 任务循环**——会话消息模型、
 *   Agent 画像（构建者/规划师/通用/探索/调研）、权限三态门（allow/ask/deny +
 *   模式兜底 + 规则 + 会话记忆）、隔离上下文子代理委派、上下文压缩、
 *   工具面（read/write/edit/bash/glob/grep/todo/task）映射到既有注册表
 *   （code_* / ShellExecute / git / web / skill / mcp__*）。
 *   由独立库模块实现（com.apex.standardkit:standard-loop，经
 *   [StandardKitAdapter] 接线）；
 * - [LOOM]（织造）：**经纬交织范式**——思考链为经线（deliberate 块 +
 *   原生 reasoning 双通道）、工具调用为纬线（in-band XML 协议：use 调用块 /
 *   outcome 随机短码回执）、记忆为布面（digest 摘要织入压缩）、循环为梭
 *   （流式回合机 + 串行批次 + 防环收敛 + 布样人控门 + 子线程群 + 织造包 +
 *   运行中转向）。由独立库模块实现（com.apex.loomkit:loom-weave，经
 *   [com.apex.agent.core.code.loom.LoomKitAdapter] 接线）——自有循环
 *   语义，零宿主循环依赖；
 * - [COURIER]（信使）：**驿传调度范式**——用户输入先落收发匣（steer 插队 /
 *   queue 排队 / 控制件自成边界，投递幂等）、一趟 = 一条助手消息（逻辑趟
 *   vs 物理尝试，无本地调用即收束 needsContinuation）、同趟多工具**并行
 *   纤程**、勘合门三态法则（`*`/`?` 通配则例 + 后例覆盖 + always 记忆 +
 *   复合命令逐段核验 + 拒付缺陷隧道）、档房结构化卷宗压缩（近期逐字保留 +
 *   70/50/35 收缩阶梯）、示廊常设指令价值增量公告、下站子驿委派
 *   （courier_task / courier_question / courier_todos 本地工具面）。由独立
 *   库模块实现（com.apex.courierkit:courier-dispatch，经
 *   [com.apex.agent.core.code.courier.CourierKitAdapter] 接线）。
 *
 * ## 四引擎的关系
 *
 * | 维度 | 深潜 | 标准 | 织造 | 信使 |
 * |---|---|---|---|---|
 * | 循环宿主 | ApexAgentEngine BUILD/PLAN | standard-loop 库回合循环 | loom-weave 库梭回合机 | courier-dispatch 库趟次步进机 |
 * | 工具协议 | 原生函数调用 | 原生函数调用 | in-band XML（原生调用桥接翻译） | 原生函数调用（终趟关调用留定义） |
 * | 思考深度 | 七档 + AUTO 自适应 | 画像预算（档位 → 回合倍率） | 档位 → 回合倍率 + 努力取向 + 家族偏好 | 档位 → 画像基线 + 趟次预算 |
 * | 权限门 | 主执行器组合门 | 库内权限门 + 仅环境门执行器 | 织造权限门 + 仅环境门执行器 | 勘合门（通配则例 + always 记忆）+ 仅环境门执行器 |
 * | 子代理 | CodeTask → SubAgentRunner | task → 库内派发器 | loom_thread_* 子线程群 | courier_task 子驿会话（下站前哨） |
 * | 会话记忆 | code_memory | code_memory_standard | code_memory_loom | code_memory_courier |
 * | 事件协议 | AgentEvent | AgentEvent（同一协议） | AgentEvent（同一协议） | AgentEvent（同一协议） |
 *
 * ## 持久化
 *
 * `AgentSettings.codeThinkingLogic`："" / "deep_dive" = 深潜（默认，历史行为）；
 * "standard" = 标准；"loom" = 织造；"courier" = 信使。恢复逻辑见 [fromName]
 * （未知值回退深潜，脏值不致崩溃）。
 */
enum class StandardLogicMode(
    /** 持久化名（小写稳定，跨版本兼容）。 */
    val persistenceName: String,
    /** 是否标准任务循环引擎。 */
    val isStandard: Boolean,
    /** 是否织造引擎。 */
    val isLoom: Boolean,
    /** 是否信使引擎。 */
    val isCourier: Boolean
) {
    /** 深潜（自研思考逻辑，v1.2 既有行为）。 */
    DEEP_DIVE("deep_dive", isStandard = false, isLoom = false, isCourier = false),

    /** 标准（业界标准 Agent 任务循环）。 */
    STANDARD("standard", isStandard = true, isLoom = false, isCourier = false),

    /** 织造（经纬交织范式：in-band 协议 + 梭回合机 + 子线程群）。 */
    LOOM("loom", isStandard = false, isLoom = true, isCourier = false),

    /** 信使（驿传调度范式：收发匣 + 趟次步进机 + 勘合门 + 下站前哨）。 */
    COURIER("courier", isStandard = false, isLoom = false, isCourier = true);

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
                        LOOM.persistenceName, "weave" -> LOOM
                        COURIER.persistenceName, "express" -> COURIER
                        else -> null
                    }
                }
    }
}
