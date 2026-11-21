package com.apex.agent.core.code.standard

import com.apex.agent.core.code.CodeContextSnapshot
import com.apex.agent.core.code.CodeEngineFacade
import com.apex.agent.core.code.thinking.CodeThinkingLevel
import com.apex.agent.core.engine.AgentEngine
import com.apex.agent.core.engine.AgentEvent
import com.apex.agent.core.engine.AgentMode
import com.apex.agent.core.engine.AutonomyLevel
import com.apex.agent.core.engine.UserInput
import com.apex.agent.core.engine.UserQuestionGateway
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import java.io.File
import kotlinx.coroutines.flow.Flow

/**
 * # Dual Logic Code Engine — Coding 屏四思考逻辑路由门面
 *
 * Coding 屏的 [AgentEngine] 注入点从双引擎升级为**四引擎路由**：
 *
 * ```
 * CodeViewModel ──► DualLogicCodeEngine ──┬──► CodeAgentEngine（深潜线）
 *              （右上角切换）              ├──► StandardKitAdapter（标准线）
 *                                          ├──► LoomEngine（织造线）
 *                                          └──► CourierEngine（信使线）
 * ```
 *
 * ## 路由规则
 *
 * | 调用 | 路由目标 |
 * |---|---|
 * | execute / abort / submitUserInput / cancelUserInput | 当前激活引擎 |
 * | setActiveWorkspace / clearConversation | **四条线同步**（各自独立记忆通道） |
 * | updateGlobalRules / updateSessionExtras / updateForcedTools / updateMode | 四条线同步（廉价缓存写入） |
 * | prepareForTask / updateThinkingLevel / thinkingLevel / 仪表读数 | 当前激活引擎 |
 * | submitPlanConfirmation | 当前激活引擎（四条线各自的人控门互不串扰） |
 * | applyRuntimeState | 四线同步通道广播 + 档位路由当前线（见 [CodeEngineFacade.applyRuntimeState]） |
 *
 * ## 现场隔离
 *
 * 四条线各有独立会话记忆（深潜 code_memory / 标准 code_memory_standard /
 * 织造 code_memory_loom / 信使 code_memory_courier）——切到另一条线时旧线
 * 现场保留，切回来继续。用户「新会话」清空四条线（意图 = 全新开始，残留
 * 半截现场反而困惑）。
 *
 * 线程契约：[switchLogic] 仅赋两个 volatile 字段（引擎引用 + 显式逻辑
 * 状态，SPE-03 P1-2 同一赋值点）+ 同步四线轻量缓存；运行中切换由 VM 侧
 * uiState.isRunning 拦截（发送前判断）。切线后档位等运行态由 VM 经
 * [applyRuntimeState] 整包重放（SPE-03 P1-1，见 CodeLogicModeOps）。
 */
class DualLogicCodeEngine(
    /** 深潜线（自研思考逻辑）。 */
    private val deepDive: CodeEngineFacade,
    /** 标准线（标准任务循环）。 */
    private val standard: CodeEngineFacade,
    /** 织造线（经纬交织范式）。 */
    private val loom: CodeEngineFacade? = null,
    /** 信使线（驿传调度范式）。 */
    private val courier: CodeEngineFacade? = null,
    /**
     * #395 同款中止收口（四线统一）：宿主侧工具门（权限门/风险门/提问工具）
     * 在工具 execute() 内部挂起在自己的 deferred 上——深潜线原本由
     * ApexAgentEngine.abort 内部收口，kit 三线（标准/织造/信使）无同等
     * 接线，中止后提问卡残留 UI（用户点「继续」把答案投给已死任务）。
     * 在本统一路由层前置收口，四线一次覆盖；幂等（深潜线内层二次调用
     * 时 deferred 已清，返回 false 零副作用）。
     */
    private val userQuestionGateway: UserQuestionGateway? = null
) : AgentEngine, CodeEngineFacade {

    init {
        // SPE-03 P1-2：DI 误配防护——四线引用两两互异（引用相等反推旧口径下，
        // 两条线指向同一实例会静默路由错线；显式状态口径下仍会混淆审计与
        // kit 可用性判断，构造期即暴露而非运行期静默）。
        require(deepDive !== standard) { "deepDive 与 standard 不能共用同一实例" }
        loom?.let { l ->
            require(l !== deepDive && l !== standard) { "loom 线不能与深潜/标准线共用实例" }
        }
        courier?.let { c ->
            require(c !== deepDive && c !== standard && (loom == null || c !== loom)) {
                "courier 线不能与其他任何线共用实例"
            }
        }
    }

    /** 当前激活引擎引用（路由用）。 */
    @Volatile
    private var active: CodeEngineFacade = deepDive

    /**
     * 当前思考逻辑（SPE-03 P1-2：显式状态字段，与 [active] 在
     * [switchLogic] 同一赋值点同步写入——替代旧「引用相等 when 反推」，
     * DI 误配/新增第五条线不再改变正确性；持久化由 VM 侧负责）。
     */
    @Volatile
    private var activeMode: StandardLogicMode = StandardLogicMode.DEEP_DIVE

    /** 当前思考逻辑（显式状态直读，不再反推）。 */
    fun activeLogic(): StandardLogicMode = activeMode

    /**
     * 切换思考逻辑。
     *
     * @return true = 切换成功；false = 目标线未装配（DI 缺线——不切换）
     */
    fun switchLogic(mode: StandardLogicMode): Boolean {
        val target = when (mode) {
            StandardLogicMode.DEEP_DIVE -> deepDive
            StandardLogicMode.STANDARD -> standard
            StandardLogicMode.LOOM -> loom ?: return false
            StandardLogicMode.COURIER -> courier ?: return false
        }
        if (active === target) return true
        active = target
        activeMode = mode // 与 active 同一赋值点（P1-2：避免双字段漂移）
        AppLogger.instance.info(
            LogCategory.SYSTEM, TAG,
            "Coding thinking-logic switched to ${mode.persistenceName}"
        )
        return true
    }

    /** 标准线是否正在运行（VM 发送前检查，防运行中切换丢现场）。 */
    fun isStandardEngineRunning(): Boolean = standard === active

    /** 织造线是否装配（UI 可据此隐藏未接线档位）。 */
    fun isLoomEngineAvailable(): Boolean = loom != null

    /** 信使线是否装配（UI 可据此隐藏未接线档位）。 */
    fun isCourierEngineAvailable(): Boolean = courier != null

    // ═══════════════════════════ AgentEngine（当前激活引擎）═════════════════════════

    override fun execute(input: String): Flow<AgentEvent> = active.execute(input)

    override fun execute(input: UserInput): Flow<AgentEvent> = active.execute(input)

    override suspend fun abort() {
        // 先收口宿主侧挂起提问，再路由引擎中止：工具门挂起的 deferred 若不
        // 唤醒，硬取消后提问卡残留在 UI 上（见构造参数 KDoc）。
        userQuestionGateway?.abortActiveQuestion()
        active.abort()
    }

    override fun submitUserInput(answer: String) = active.submitUserInput(answer)

    override fun cancelUserInput() = active.cancelUserInput()

    // ═══════════════════════════ 四线同步 API ═══════════════════════════

    override fun setActiveWorkspace(
        workspaceId: String,
        name: String,
        root: File,
        activeFile: String?
    ) {
        deepDive.setActiveWorkspace(workspaceId, name, root, activeFile)
        standard.setActiveWorkspace(workspaceId, name, root, activeFile)
        loom?.setActiveWorkspace(workspaceId, name, root, activeFile)
        courier?.setActiveWorkspace(workspaceId, name, root, activeFile)
    }

    override fun clearConversation() {
        deepDive.clearConversation()
        standard.clearConversation()
        loom?.clearConversation()
        courier?.clearConversation()
    }

    override fun updateGlobalRules(rules: String) {
        deepDive.updateGlobalRules(rules)
        standard.updateGlobalRules(rules)
        loom?.updateGlobalRules(rules)
        courier?.updateGlobalRules(rules)
    }

    override fun updateSessionExtras(extras: String?) {
        deepDive.updateSessionExtras(extras)
        standard.updateSessionExtras(extras)
        loom?.updateSessionExtras(extras)
        courier?.updateSessionExtras(extras)
    }

    /** v6 专家模板人设（三线同步——切线后人设不丢）。 */
    override fun updateRolePersona(roleDefinition: String, rolePrompt: String?) {
        deepDive.updateRolePersona(roleDefinition, rolePrompt)
        standard.updateRolePersona(roleDefinition, rolePrompt)
        loom?.updateRolePersona(roleDefinition, rolePrompt)
        courier?.updateRolePersona(roleDefinition, rolePrompt)
    }

    override fun updateForcedTools(forcedToolIds: Set<String>, exposeAll: Boolean) {
        deepDive.updateForcedTools(forcedToolIds, exposeAll)
        standard.updateForcedTools(forcedToolIds, exposeAll)
        loom?.updateForcedTools(forcedToolIds, exposeAll)
        courier?.updateForcedTools(forcedToolIds, exposeAll)
    }

    override fun updateMode(mode: AgentMode) {
        deepDive.updateMode(mode)
        standard.updateMode(mode)
        loom?.updateMode(mode)
        courier?.updateMode(mode)
    }

    /** v4 三档自主级别（四线同步——与 updateMode 同口径，切线后提问策略不丢）。 */
    override fun updateAutonomy(level: AutonomyLevel?, preClarify: Boolean) {
        deepDive.updateAutonomy(level, preClarify)
        standard.updateAutonomy(level, preClarify)
        loom?.updateAutonomy(level, preClarify)
        courier?.updateAutonomy(level, preClarify)
    }

    // ═══════════════════════════ 当前激活引擎直通 ═══════════════════════════

    override fun setActiveFile(activeFile: String?) = active.setActiveFile(activeFile)

    override fun prepareForTask() = active.prepareForTask()

    override fun submitPlanConfirmation(
        confirmed: Boolean,
        enabledSteps: List<Int>?,
        order: List<Int>?
    ): Boolean = active.submitPlanConfirmation(confirmed, enabledSteps, order)

    override fun updateThinkingLevel(level: CodeThinkingLevel) =
        active.updateThinkingLevel(level)

    override fun thinkingLevel(): CodeThinkingLevel = active.thinkingLevel()

    override fun historyCount(): Int = active.historyCount()

    override fun currentWorkspace(): com.apex.agent.core.code.CodeAgentEngine.WorkspaceInfo? =
        active.currentWorkspace()

    override fun currentTokenCount(): Int = active.currentTokenCount()

    override fun maxContextTokens(): Int = active.maxContextTokens()

    /** 上下文查看路由到当前激活线（深潜线全量；kit 线为统计降级快照）。 */
    override fun contextSnapshot(): CodeContextSnapshot = active.contextSnapshot()

    companion object {
        private const val TAG = "DualLogicCodeEngine"
    }
}
