package com.apex.agent.core.code

import com.apex.agent.core.code.standard.StandardLogicMode
import com.apex.agent.core.code.thinking.CodeThinkingLevel
import com.apex.agent.core.engine.AgentEngine
import com.apex.agent.core.engine.AgentMode
import com.apex.agent.core.engine.AutonomyLevel
import java.io.File

/**
 * SPE-03 P1-1：四线统一运行态快照 —— 切换思考逻辑线后整包重放目标线，
 * 替代散落的一两行 update\* 补写（旧口径只补 mode+档位，其余通道靠
 * 四线同步 API 的副作用链，无契约保障）。
 *
 * 可空通道语义 =「不重放」：null 字段在 [CodeEngineFacade.applyRuntimeState]
 * 中被跳过（保留目标线现有缓存），调用方按需增量重放；非空字段语义与
 * 对应 update\* 通道逐字相同（如 sessionExtras = "" 即清空）。
 *
 * [replayAutonomy] 独立成标志位而非以 autonomy==null 判缺席：
 * `updateAutonomy(null, …)` 本身是合法指令（PLAN 模式清除自主级别），
 * 与「不重放该通道」语义不可共用同一可空位。
 */
data class EngineRuntimeState(
    /** 思考档位（七档；AUTO 由 VM 发送前预检解析后传入具体档）。 */
    val thinkingLevel: CodeThinkingLevel,
    /** 执行模式（BUILD/PLAN；null = 不重放）。 */
    val mode: AgentMode? = null,
    /** 自主级别（null = 清除指令，仅当 [replayAutonomy] 时生效）。 */
    val autonomy: AutonomyLevel? = null,
    /** 前置澄清开关（与 autonomy 同通道）。 */
    val preClarify: Boolean = false,
    /** 是否重放自主级别通道（false = 跳过，见类 KDoc）。 */
    val replayAutonomy: Boolean = false,
    /** 全局规则（null = 不重放；空串 = 清空）。 */
    val globalRules: String? = null,
    /** 会话附加段（null = 不重放；空串 = 清空）。 */
    val sessionExtras: String? = null,
    /** 角色人设（definition + 自定义提示词；null = 不重放）。 */
    val rolePersona: Pair<String, String?>? = null,
    /** 强制工具集（null = 不重放）。 */
    val forcedToolIds: Set<String>? = null,
    /** 强制工具模式下是否全量暴露其余工具（与 forcedToolIds 同通道）。 */
    val exposeAllTools: Boolean = false
)

/**
 * # Code Engine Facade — Coding 屏引擎对外契约（四思考逻辑线共用）
 *
 * Coding 屏的「思考逻辑」有四条引擎线（见 [StandardLogicMode]）：
 * - **深潜**（[CodeAgentEngine]，自研七档思考）；
 * - **标准**（`StandardKitAdapter` → standard-loop 库，业界标准 Agent 任务循环）；
 * - **织造**（`LoomKitAdapter` → loom-weave 库，经纬交织范式）；
 * - **信使**（`CourierKitAdapter` → courier-dispatch 库，驿传调度范式）。
 *
 * 四条线都实现本接口，[CodeViewModel] 只面向本接口编程，
 * [com.apex.agent.core.code.standard.DualLogicCodeEngine] 按
 * [StandardLogicMode] 把调用路由到当前激活引擎——VM 永远不知道
 * 「背后换引擎了」，胶囊时间轴 / 长任务追踪 / 权限问答闭环
 * （全部消费 AgentEvent）自然四线通用。
 *
 * 方法清单 = `CodeViewModel` 实际使用的 `codeEngineImpl.*` 调用面
 * （签名与 [CodeAgentEngine] 逐一对齐，深潜线零改动即实现）。
 *
 * 自适应决策说明已从本接口移除（SPE-03 P1-4 统一决策来源）：
 * UI 一律读 `CodeUiState.adaptiveDecision`（VM 侧四线统一预检/
 * 升降档的唯一回显通道），引擎侧不再各自暴露可能为 null 的
 * 决策快照——旧口径 kit 三线恒 null，新调用方易误判「本轮无决策」。
 */
interface CodeEngineFacade : AgentEngine {

    /**
     * 切换激活的编码工作区：绑定 per-workspace 记忆 + 重放该工作区
     * 历史 + 刷新 JIT 上下文。门面会把本调用同步到**四条**引擎线
     * （各自独立记忆通道，切回时现场不丢）。
     */
    fun setActiveWorkspace(
        workspaceId: String,
        name: String,
        root: File,
        activeFile: String? = null
    )

    /** 用户在编辑器里切换文件/选区时刷新上下文（不切工作区）。 */
    fun setActiveFile(activeFile: String?)

    /** 每轮发送前刷新 JIT 上下文（VM 在 execute 前调用）。 */
    fun prepareForTask()

    /** 全局规则（设置层 AgentSettings.globalRules 的引擎侧缓存同步）。 */
    fun updateGlobalRules(rules: String)

    /**
     * 执行模式（BUILD/PLAN 双档）：标准引擎把 PLAN 映射为「规划师画像 +
     * 计划确认人控门」，BUILD 映射为「构建者画像」。
     */
    fun updateMode(mode: AgentMode)

    /** PLAN 模式计划确认/驳回（勾选与重排按原 index 口径）。#340：Boolean 回执 = 是否送达挂起等待。 */
    fun submitPlanConfirmation(
        confirmed: Boolean,
        enabledSteps: List<Int>? = null,
        order: List<Int>? = null
    ): Boolean

    /**
     * v4 三档自主级别（提问策略，与执行模式正交）：null = 清除指令
     * （PLAN 模式 / 未设置，行为零变化）。深潜线经 patchConfig 落
     * AgentConfig（EnginePrompts 注入 Autonomy 段）；kit 三线各自包装进
     * sessionExtras 透传（无自由 system context 通道）。默认空实现：
     * 未知实现方（测试替身等）零改动。
     */
    fun updateAutonomy(level: AutonomyLevel?, preClarify: Boolean) {}

    /** 「小圆环」会话上下文附加段（coding 工位独占）。 */
    fun updateSessionExtras(extras: String?)

    /**
     * v6 Coding 专家模板人设通道：roleDefinition（专家定义段）+ rolePrompt
     * （用户自定义提示词，可空）。深潜线经 delegate.patchConfig 落到
     * AgentConfig 人设字段；标准线注入系统提示词专家段。空串 = 清除
     * （回落引擎默认画像）。
     */
    fun updateRolePersona(roleDefinition: String, rolePrompt: String?)

    /** v4 强制函数调用（forcedToolIds 非空 = 只暴露选中工具且 required）。 */
    fun updateForcedTools(forcedToolIds: Set<String>, exposeAll: Boolean)

    /**
     * 思考档位（七档）：标准引擎把它映射为**回合预算倍率**与推理努力取向
     * （NONE/LIGHT 收紧、MAXIMUM 以上放开），语义见 standard-loop 库
     * `AgentLoopEngine.updateThinkingLevel`。
     */
    fun updateThinkingLevel(level: CodeThinkingLevel)

    /** 当前思考档位（UI 回显用）。 */
    fun thinkingLevel(): CodeThinkingLevel

    /**
     * SPE-03 P1-1：一次性重放运行态快照（默认实现拆解为既有 update\* 通道，
     * 四条线零新增实现负担；四线同步通道在 [DualLogicCodeEngine] 广播，
     * 档位路由当前激活线）。
     *
     * 调用时机：① 切线后对目标线重放（消除档位缓存滞留，切完即读即一致，
     * 无需等下一次发送）；② 发送前整包收口（prepareEngineContext 语义化）。
     * 可空通道跳过语义见 [EngineRuntimeState] KDoc。
     */
    fun applyRuntimeState(state: EngineRuntimeState) {
        updateThinkingLevel(state.thinkingLevel)
        state.mode?.let { updateMode(it) }
        if (state.replayAutonomy) {
            updateAutonomy(state.autonomy, state.preClarify)
        }
        state.globalRules?.let { updateGlobalRules(it) }
        state.sessionExtras?.let { updateSessionExtras(it) }
        state.rolePersona?.let { (definition, prompt) -> updateRolePersona(definition, prompt) }
        state.forcedToolIds?.let { updateForcedTools(it, state.exposeAllTools) }
    }

    /** 清空当前工作区的对话历史（新会话）。 */
    fun clearConversation()

    /** 当前会话历史条数（UI 徽标用）。 */
    fun historyCount(): Int

    /** 当前工作区快照（UI 用）。 */
    fun currentWorkspace(): CodeAgentEngine.WorkspaceInfo?

    /** 当前上下文 token 估算（上下文仪表盘）。 */
    fun currentTokenCount(): Int

    /** 上下文窗口预算（上下文仪表盘）。 */
    fun maxContextTokens(): Int

    /**
     * 当前会话上下文快照（上下文查看工具的数据源，见 [CodeContextSnapshot]）。
     *
     * 默认实现 = 仅统计的降级快照（entries 为空）：深潜线 override 提供
     * 完整消息序列与提示注入段；标准/织造/信使三条 kit 线的引擎未开放
     * 消息导出 API，沿用默认（UI 显示降级说明，统计仍真实）。
     */
    fun contextSnapshot(): CodeContextSnapshot = CodeContextSnapshot(
        entries = emptyList(),
        globalRules = null,
        sessionExtras = null,
        tokenUsed = currentTokenCount(),
        tokenMax = maxContextTokens()
    )
}
