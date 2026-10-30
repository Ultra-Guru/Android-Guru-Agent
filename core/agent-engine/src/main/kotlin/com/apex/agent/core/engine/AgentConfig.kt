package com.apex.agent.core.engine

/**
 * Agent 执行模式。
 *
 * Agent 模式移除（PR 3/6）：Agent 聊天屏档位（CHAT / AGENT / LOOP）与
 * v1/v2 存量档位（SPEC / REFLECTION / HUMAN_ASSIST / CUSTOM）已删 ——
 * 本软件为纯 Coding 模式软件，只保留编码工位三档：
 * - [BUILD]：边想边做（ReAct 主循环）
 * - [PLAN]：先规划确认后执行
 * - [GOAL]：目标驱动持续验收（goal 包）
 *
 * 存量配置兼容：持久化的 mode 字符串经 `AgentMode.valueOf` 解析处均有
 * runCatching 回退（CodeViewModel 启动消毒到 BUILD、LoomKitAdapter
 * getOrDefault(BUILD)），删除的档位名解析失败自动落到 BUILD，无迁移需求。
 */
enum class AgentMode(val displayName: String, val description: String) {
    /**
     * 构建模式（Coding 屏）：边想边做，实时执行
     */
    BUILD("Build", "边想边做，实时执行"),

    /**
     * 规划模式（Coding 屏）：先制定完整计划，用户确认后再执行
     */
    PLAN("Plan", "先制定完整计划，确认后再执行"),

    /**
     * 目标模式（Coding 屏，v3）：设定**可验证的完成条件**（如「所有测试
     * 通过」）后持续工作——每轮自然收尾时由快速模型（FAST 角色）验收，
     * 未达标自动注入差距说明续跑，直到目标达成或轮次耗尽（外部状态由
     * [com.apex.agent.core.engine.goal.GoalModeCoordinator] 持有，引擎只挂
     * 每轮验收钩子，见 goal 包）。
     */
    GOAL("Goal", "目标驱动：设定可验证条件，持续工作直至达成")
}

/**
 * 思考深度等级（#168 起六档）
 * 控制Agent在每次决策前的推理深度。
 *
 * 各档的完整执行画像（提示词 + 参数 + 迭代/验证/压缩/输出策略）见
 * [com.apex.agent.core.engine.thinking.ThinkingProfile]；AUTO 档的逐轮
 * 动态选档由 [com.apex.agent.core.engine.thinking.ThinkingModeController]
 * 驱动。
 */
enum class ThinkingLevel(val level: Int, val description: String) {
    /** 不思考，直接行动 */
    NONE(0, "直接执行，不生成推理过程"),
    
    /** 浅思考：1-2句简短推理 */
    LIGHT(1, "简短分析后立即行动"),
    
    /** 标准思考：正常推理链 */
    STANDARD(2, "分析任务→选择方案→执行"),
    
    /** 深思考：多方案比较 */
    DEEP(3, "多方案对比→风险评估→最优选择"),
    
    /** 极深思考：完整思维链+自我质疑 */
    MAXIMUM(4, "完整推理链+自我反思+多轮验证"),

    /**
     * 自动档（#168）：按任务复杂度（文本长度/多步指示词/代码含量/风险词/
     * 错误史/迭代深水区）逐轮动态选档，委托
     * [com.apex.agent.core.engine.thinking.AdaptiveThinkingSelector]。
     */
    AUTO(5, "自动：按任务复杂度动态选档");
    
    /**
     * 转换为 system prompt 中的思考指令。
     *
     * #168 起指令正文与执行策略统一建模在
     * [com.apex.agent.core.engine.thinking.ThinkingProfile]（单一事实源）；
     * 本方法直接委托画像表——无画像路径的兼容回退与主路径**字面同源**，
     * 不再维护一份会漂移的副本文本。
     */
    fun toPromptInstruction(): String =
        com.apex.agent.core.engine.thinking.ThinkingProfile.forLevel(this).promptInstruction
}

/**
 * Agent配置
 */
data class AgentConfig(
    /** 执行模式 */
    val mode: AgentMode = AgentMode.BUILD,
    
    /** 思考深度 */
    val thinkingLevel: ThinkingLevel = ThinkingLevel.STANDARD,
    
    /**
     * 最大迭代轮数（防失控兜底）。**≤ 0 = 无上限**（用户显式要求：一个任务
     * 的工具调用次数不设上限——失控防线交给循环检测 + 用户手动停止，
     * 见 [EngineResilienceGuard] 与 loopDetector）。经
     * [ThinkingModeController.effectiveMaxIterations] 统一消费哨兵。
     */
    val maxIterations: Int = 25,

    /**
     * 单轮并行执行上限：模型一次返回多个 tool_calls 时，引擎同轮最多
     * 并行执行几个（1 = 顺序执行，历史默认）。仅影响**执行**并发——
     * 请求参数侧的 parallel_tool_calls 开关在 ModelProfile（两者正交）。
     * 并行时事件按「发言权」协议顺序放行（UI 单卡时间线不撕裂），历史
     * ToolResult 按原始顺序落盘（配对安全），见 EngineToolExecution.kt。
     */
    val toolCallParallelism: Int = 1,

    /**
     * 单轮工具调用数上限（超出部分延迟到下一轮执行，附系统说明）：
     * 0 = 不限制。来源 ModelProfile.maxToolCalls（此前是设置页 dead 字段，
     * 这里补上引擎侧真接线——防弱模型一轮喷射几十个调用把请求打爆）。
     */
    val maxToolCallsPerTurn: Int = 0,

    /** 最大context tokens（超出触发压缩）*/
    val maxContextTokens: Int = 128000,
    
    /** 压缩触发阈值（占maxContextTokens的比例）*/
    val compressionThreshold: Float = 0.8f,
    
    /** 保留最近N轮不压缩 */
    val preserveRecentTurns: Int = 5,
    
    /** 工具输出最大长度（超出截断）*/
    val maxToolOutputLength: Int = 2000,
    
    /** 是否流式输出 */
    val streaming: Boolean = true,
    
    /** 温度 */
    val temperature: Float = 0.7f,
    
    /** 模型名称覆盖 */
    val modelOverride: String? = null,

    /**
     * 反思轮数（历史遗留字段，引擎已不再消费）：REFLECTION 档位随 Agent
     * 模式移除（PR 3/6）已删，本字段仅为 AgentSetup 工具与设置页的存量
     * 消费方保留，待 PR 4/6（AgentSetupTools）/ 5/6（设置页）随消费方删除。
     */
    val reflectionRounds: Int = 1,

    /**
     * 自定义附加指令（历史遗留字段，引擎已不再消费）：CUSTOM 档位随 Agent
     * 模式移除（PR 3/6）已删，注入点同步移除；字段为 ModePreset 预设链路
     * 的存量消费方保留，待 PR 5/6（设置域）随消费方删除。
     */
    val customInstruction: String? = null,

    /**
     * 追加到 system prompt 末尾的动态上下文（任意模式生效）。
     *
     * 由 UI 层在每次发送前组装（如当前时间注入、用户规则、结构化输出指令、
     * 联网搜索强制指令），原样拼入 "## Session Context" 段落。
     */
    val additionalSystemContext: String = "",

    /**
     * **v4 强制函数调用**（"调用函数"菜单的强制语义）：
     *
     * 非空时本轮请求**只**暴露这些工具，且 `tool_choice = required`
     * （单选时为具体函数）——被选中的函数**必须**被模型调用。
     *
     * 空 = 默认模式：CORE 工具集 + 会话激活的工具（v4 渐进披露），
     * 无 tool_choice 强制。旧字段 `enabledToolIds`（白名单收窄）已废弃：
     * 它是"全量发送拖垮请求 + 手动圈选才能用"这个根因问题的载体。
     */
    val forcedToolIds: Set<String> = emptySet(),

    /**
     * **#147 子代理工具集限制**：非空时本轮请求**只**暴露这些工具（与
     * [forcedToolIds] 同走 planForced 预算路径），但**不**附带
     * `tool_choice = required` —— 子代理的最终轮需要能输出纯文本结论，
     * 而 forced 语义会迫使模型每轮调用工具（严格 Provider 上将永远到不了
     * 结论轮，跑到迭代上限后以 Error 收场）。
     *
     * 两者同时非空时 [forcedToolIds] 优先。空 = 不限制（默认计划）。
     */
    val allowedToolIds: Set<String> = emptySet(),

    /**
     * **v4 全量模式**：true 时向模型暴露注册表内全部（非 legacy）工具
     * （仍受 [com.apex.agent.core.tools.catalog.ToolRequestBudget] 预算钳制）。
     * 默认 false = CORE + 激活集。电源用户/调试用。
     */
    val exposeAllTools: Boolean = false,

    // ═══ Agent 角色（人设层 · 可选，默认值 = 原有行为零变化）═══
    // app 层「设置 → Agent 角色」激活的角色经 AgentModule（启动快照）/
    // AgentChatViewModel（patchConfig 运行时热切换）拍平到这里 —— 引擎保持
    // 纯字符串消费，不感知 AgentRole 数据模型（模块边界防腐）。

    /** agent 自称（身份行 "You are X"）；空 = 默认 "Apex Agent"。 */
    val agentName: String = "",

    /** agent 对用户的称呼（如 "老板"/"Boss"）；空 = 不注入称呼约束。 */
    val userTitle: String = "",

    /** 角色定义（这个 agent 是谁、擅长什么、边界在哪）。 */
    val roleDefinition: String = "",

    /** 用户自定义提示词（原样拼入 Agent Role 段，最自由的一层）。 */
    val rolePrompt: String = "",

    /** 语气风格键（"" | professional | friendly | humorous | concise）。 */
    val roleStyle: String = "",

    /** 回复语言约束（"" 跟随用户输入 | "zh" | "en"）。 */
    val roleLanguage: String = "",

    /**
     * #197 技能作用域（"agent" | "coding" | "all"）：Prompt 型 Skill 注入的
     * 过滤依据——agent 屏引擎只注入 agent/all 聊天技能，coding 引擎只注入
     * coding/all 编码技能，两个工位的提示词层完全独立（市场分级同源）。
     */
    val skillScope: String = "all",

    /**
     * v3 工具作用域隔离开关（默认开）：MCP / 技能复合工具按注册时携带的
     * scope（市场分级同源字段）在工具计划层隔离——agent 工位看不到
     * coding 级 MCP 工具，反之亦然。false = 回退 v2 全局可见行为。
     * 设置层（AgentSettings.mcpScopeIsolation）热更新，经 patchConfig 生效。
     */
    val mcpScopeIsolation: Boolean = true
) {
    companion object {
        /** 快速模式：Build + 无思考 */
        val QUICK = AgentConfig(
            mode = AgentMode.BUILD,
            thinkingLevel = ThinkingLevel.NONE,
            maxIterations = 10
        )
        
        /** 标准模式：Build + 标准思考 */
        val STANDARD = AgentConfig(
            mode = AgentMode.BUILD,
            thinkingLevel = ThinkingLevel.STANDARD
        )
        
        /** 谨慎模式：Plan + 深思考 */
        val CAREFUL = AgentConfig(
            mode = AgentMode.PLAN,
            thinkingLevel = ThinkingLevel.DEEP
        )
    }
}
