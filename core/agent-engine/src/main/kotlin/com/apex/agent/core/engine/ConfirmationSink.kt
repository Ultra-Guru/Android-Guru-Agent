package com.apex.agent.core.engine

/**
 * Engine capability for resuming plan/spec confirmation gates.
 *
 * [com.apex.agent.core.engine.orchestrator.DefaultTaskOrchestrator] wraps a
 * delegate [AgentEngine] for PLAN / SPEC / REFLECTION / HUMAN_ASSIST / CUSTOM
 * modes. When the UI confirms or rejects a generated plan/spec, the
 * orchestrator forwards the decision to its delegate — previously done via
 * `javaClass.getMethod(...)` reflection, which is fragile (renames break at
 * runtime instead of compile time) and invisible to the type system.
 *
 * Engines that expose confirmation gates (notably [ApexAgentEngine]) implement
 * this interface; the orchestrator now forwards through a plain
 * `delegate as? ConfirmationSink` check.
 */
interface ConfirmationSink {

    /**
     * User confirmed or rejected the plan awaiting confirmation
     * ([AgentEvent.PlanAwaitingConfirmation]).
     *
     * #340：Boolean 回执 —— true = 已送达挂起等待；false = 无挂起等待或
     * 已被 abort/超时收场，调用方（VM）据此给出「未送达」提示（#214 同型）。
     */
    fun submitPlanConfirmation(confirmed: Boolean): Boolean

    /**
     * User confirmed or rejected the spec awaiting confirmation
     * ([AgentEvent.SpecAwaitingConfirmation]).
     *
     * #340：Boolean 回执，语义同 [submitPlanConfirmation]。
     */
    fun submitSpecConfirmation(confirmed: Boolean): Boolean
}
