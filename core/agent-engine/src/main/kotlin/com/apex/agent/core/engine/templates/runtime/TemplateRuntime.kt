package com.apex.agent.core.engine.templates.runtime

import com.apex.agent.core.engine.templates.BuiltinPromptTemplates
import com.apex.agent.core.engine.templates.PromptTemplate
import com.apex.agent.core.engine.templates.PromptTemplateRegistry

/**
 * ═══ 模板行为运行时 —— 编排器（PR 4）═══
 *
 * 模板切换的完整业务闭环（不只是提示词注入）：
 *
 * ```
 * activate(id, overrides)      激活：解析模板（内置强制出厂定义）+ 合并变量
 *   ↓                            + 绑定行为（内置 → 代码行为；自定义 → 兜底行为）
 * prepareTurn(activation)      组装轮次：PREPARE → ANALYZE → COMPOSE 三阶段
 *   ↓                            （每阶段防御式包裹 + 执行轨迹留痕）
 * —— 宿主把 prompt 发给聊天引擎 ——（行为层不持有大模型，薄包装纪律）
 *   ↓
 * processResponse(activation)  回包处理：VALIDATE → REPAIR（可选）→ RENDER
 *   ↓
 * deactivate(activation)       收尾（使用计数、状态清理）
 * ```
 *
 * 并发模型：编排器无共享可变状态（激活对象是不可变快照），可安全并发；
 * 行为实现侧若持有状态须自兜底。异常纪律：**任何阶段的异常都被折叠**为
 * 该阶段 ok=false + 轨迹留痕，绝不上抛（聊天主流程永不被模板功能打挂）。
 */
class TemplateRuntime(
    private val registry: PromptTemplateRegistry,
    /** 行为目录（id → 行为）；内置模板缺行为时回落兜底行为（防御式）。 */
    private val behaviors: Map<String, AgentTemplateBehavior> = emptyMap(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val logger: (String) -> Unit = {}
) {

    // ═══ 激活 ═══

    /**
     * 激活模板：解析定义 → 合并变量 → 绑定行为。
     *
     * - 内置模板：定义强制取 [BuiltinPromptTemplates] 出厂表（注册表里的
     *   磁盘副本只贡献运行元数据）——「内置不可更改」在运行时侧的落点；
     * - 必填变量缺失 → ok=false + missingRequired 清单（不进入轮次）；
     * - 未知模板 id → ok=false + TEMPLATE_NOT_FOUND。
     */
    suspend fun activate(
        templateId: String,
        overrides: Map<String, String>,
        options: TemplateRuntimeOptions = TemplateRuntimeOptions()
    ): TemplateActivation {
        val startedAt = clock()
        val template = resolveTemplate(templateId)
        if (template == null) {
            logger("TemplateRuntime: activate failed, template not found '$templateId'")
            return TemplateActivation(
                ok = false,
                template = null,
                variables = TemplateVariableReport.empty(),
                behavior = null,
                manifest = null,
                failureReason = REASON_TEMPLATE_NOT_FOUND,
                options = options,
                activatedAt = startedAt
            )
        }
        val variables = mergeVariables(template, overrides)
        val missing = template.variables
            .filter { it.required }
            .map { it.name }
            .filter { variables.value(it).isNullOrBlank() }
        if (missing.isNotEmpty()) {
            logger("TemplateRuntime: activate '$templateId' blocked, missing required: $missing")
            return TemplateActivation(
                ok = false,
                template = snapshotOf(template),
                variables = variables,
                behavior = null,
                manifest = null,
                missingRequired = missing,
                failureReason = REASON_MISSING_REQUIRED,
                options = options,
                activatedAt = startedAt
            )
        }
        val behavior = behaviorFor(template)
        val activation = TemplateActivation(
            ok = true,
            template = snapshotOf(template),
            variables = variables,
            behavior = behavior,
            manifest = behavior.manifest(),
            options = options,
            activatedAt = startedAt
        )
        // 使用计数走注册表通道（内置/自定义统一：渲染命中即计数）
        runCatching { registry.incrementUsage(templateId) }
            .onFailure { logger("TemplateRuntime: incrementUsage failed: ${it.message}") }
        logger(
            "TemplateRuntime: activated '$templateId' behavior=${behavior.behaviorId} " +
                "vars=${variables.userProvidedNames().size}/${template.variables.size}"
        )
        return activation
    }

    /**
     * 解析模板定义：内置 id 一律取出厂定义（防改写语义贯穿到运行时）；
     * 其余走注册表（自定义模板）。
     */
    private fun resolveTemplate(templateId: String): PromptTemplate? {
        BuiltinPromptTemplates.ALL.firstOrNull { it.id == templateId }?.let { return it }
        return registry.get(templateId)
    }

    /**
     * 变量合并（优先级：用户 overrides > 模板默认值 > 缺省）。
     */
    internal fun mergeVariables(
        template: PromptTemplate,
        overrides: Map<String, String>
    ): TemplateVariableReport {
        val assignments = template.variables.map { declared ->
            val userValue = overrides[declared.name]
            when {
                !userValue.isNullOrBlank() ->
                    TemplateVariableAssignment(declared.name, userValue.trim(), TemplateVariableSource.USER)
                declared.defaultValue.isNotBlank() ->
                    TemplateVariableAssignment(declared.name, declared.defaultValue, TemplateVariableSource.DEFAULT)
                else ->
                    TemplateVariableAssignment(declared.name, "", TemplateVariableSource.MISSING)
            }
        }
        return TemplateVariableReport(assignments)
    }

    /** 行为绑定：目录命中 → 代码行为；未命中（自定义/防御）→ 兜底行为。 */
    internal fun behaviorFor(template: PromptTemplate): AgentTemplateBehavior =
        behaviors[template.id] ?: TemplateBehaviorBase.fallbackFor(template)

    // ═══ 轮次组装 ═══

    /**
     * 组装轮次（PREPARE → ANALYZE → COMPOSE）：产出最终发往引擎的提示词。
     * 失败（未激活/阶段全崩）→ request=null + failure 说明（防御式不抛）。
     */
    suspend fun prepareTurn(activation: TemplateActivation): TemplateTurnOutcome {
        if (!activation.ok || activation.behavior == null || activation.template == null) {
            return TemplateTurnOutcome(
                request = null,
                trace = TemplateExecutionTrace(activation.template?.id ?: "unknown"),
                failure = activation.failureReason ?: "activation not ready"
            )
        }
        val context = contextOf(activation)
        val behavior = activation.behavior
        val recorder = PhaseRecorder(activation.template.id, clock())

        val prepared = runPhase(recorder, TemplatePhaseKind.PREPARE) { behavior.prepare(context) }
            ?: return failedOutcome(recorder, "prepare 阶段崩溃")
        val analysis = runPhase(recorder, TemplatePhaseKind.ANALYZE) { behavior.analyze(context, prepared) }
            ?: TemplateAnalysis.empty()
        val request = runPhase(recorder, TemplatePhaseKind.COMPOSE) {
            behavior.composePrompt(context, prepared, analysis)
        } ?: return failedOutcome(recorder, "compose 阶段崩溃")
        return TemplateTurnOutcome(
            request = request,
            trace = recorder.build(clock())
        )
    }

    // ═══ 回包处理 ═══

    /**
     * 回包处理（VALIDATE → REPAIR → RENDER）：
     * 校验 → （autoRepair 且有可修复违规时）修复 → 重校验残留 → 渲染报告。
     * 回包为空 / 激活不可用 → 空报告 + 违规清单（防御式不抛）。
     *
     * @param request 对应轮次的请求（宿主留存；null 时用占位请求，行为侧
     *   只拿它读元数据，不拿它当提示词正文）
     */
    suspend fun processResponse(
        activation: TemplateActivation,
        rawResponse: String,
        request: TemplateTurnRequest? = null
    ): TemplateRenderedReport {
        if (!activation.ok || activation.behavior == null || activation.template == null) {
            return TemplateRenderedReport.fallback(
                rawResponse,
                listOf(TemplateContractViolation(TemplateContractViolationKind.EMPTY_OUTPUT, detail = "激活不可用"))
            )
        }
        val context = contextOf(activation)
        val behavior = activation.behavior
        val effectiveRequest = request ?: TemplateTurnRequest(
            prompt = "(提示词已由宿主派发，此处为回包处理占位)",
            behaviorId = behavior.behaviorId,
            metadata = mapOf("stage" to "processResponse")
        )
        val recorder = PhaseRecorder(activation.template.id, clock())

        if (rawResponse.isBlank()) {
            return TemplateRenderedReport.fallback(
                rawResponse,
                listOf(
                    TemplateContractViolation(
                        TemplateContractViolationKind.EMPTY_OUTPUT,
                        detail = "回包为空"
                    )
                )
            )
        }

        val firstReport = runPhase(recorder, TemplatePhaseKind.VALIDATE) {
            behavior.validateOutput(rawResponse, context, effectiveRequest)
        } ?: TemplateValidationReport(
            violations = listOf(
                TemplateContractViolation(TemplateContractViolationKind.EMPTY_OUTPUT, detail = "校验阶段崩溃")
            )
        )

        var current = rawResponse
        val repairs = mutableListOf<String>()
        if (context.options.autoRepair && firstReport.hasRepairable) {
            current = runPhase(recorder, TemplatePhaseKind.REPAIR) {
                behavior.repairOutput(rawResponse, firstReport, context)
            } ?: rawResponse
            if (current != rawResponse) {
                repairs.add("已自动修复 ${firstReport.violations.count { it.repairable }} 处格式问题")
            }
        }

        val rendered = runPhase(recorder, TemplatePhaseKind.RENDER) {
            behavior.renderReport(current, context, effectiveRequest)
        } ?: TemplateRenderedReport.fallback(current, firstReport.violations)

        // 重校验残留违规：修复后的新违规 + 「补丁类」违规（修复只是垫了壳，
        // 内容缺口仍需用户追问 —— 不能因为补了章节壳就装作达标）。
        val afterReport = runCatching {
            behavior.validateOutput(current, context, effectiveRequest)
        }.getOrNull()
        val freshViolations = afterReport?.violations ?: emptyList()
        val gapViolations = firstReport.violations.filter { it.kind in CONTENT_GAP_KINDS }
        val residual = (freshViolations + gapViolations)
            .distinctBy { it.kind to (it.section ?: "") }
            .filter { it.kind != TemplateContractViolationKind.PREAMBLE && it.kind != TemplateContractViolationKind.EPILOGUE }

        return rendered.copy(
            repairs = repairs,
            residualViolations = residual,
            trace = recorder.build(clock())
        )
    }

    // ═══ 收尾 ═══

    /** 收尾：目前仅留痕（使用计数已在激活时记录；预留清理钩子）。 */
    fun deactivate(activation: TemplateActivation) {
        logger(
            "TemplateRuntime: deactivate '${activation.template?.id}' " +
                "(ok=${activation.ok}, phases=${activation.manifest?.supportedPhases?.size ?: 0})"
        )
    }

    /** 工坊能力预览：全部已注册行为的清单（内置目录 + 兜底说明）。 */
    fun behaviorManifests(): List<TemplateBehaviorManifest> =
        behaviors.values.map { it.manifest() }.sortedBy { it.behaviorId }

    // ═══ 内部机械 ═══

    private fun contextOf(activation: TemplateActivation): TemplateExecutionContext =
        TemplateExecutionContext(
            template = activation.template ?: TemplateTemplateSnapshot(
                id = "unknown",
                name = "unknown",
                content = "",
                declaredVariableNames = emptyList(),
                categoryLabel = "UNKNOWN",
                isBuiltIn = false
            ),
            variables = activation.variables,
            options = activation.options,
            clock = clock,
            logger = logger
        )

    /** 阶段执行（防御式包裹 + 轨迹留痕）；崩溃返回 null 不上抛。 */
    private suspend fun <T> runPhase(
        recorder: PhaseRecorder,
        phase: TemplatePhaseKind,
        block: suspend () -> T
    ): T? {
        val start = clock()
        return try {
            val result = block()
            recorder.record(phase, true, clock() - start, phaseNote(phase, result))
            result
        } catch (t: Throwable) {
            logger("TemplateRuntime: phase $phase failed: ${t::class.simpleName}: ${t.message}")
            recorder.record(phase, false, clock() - start, t.message.orEmpty())
            null
        }
    }

    private fun phaseNote(phase: TemplatePhaseKind, result: Any?): String = when (result) {
        is TemplatePreparedInput -> "输入 ${result.primaryInput.length} 字符 / 截断 ${result.truncations.size} 项"
        is TemplateAnalysis -> "命中 ${result.findings.size} 条规则${result.detectedLanguage?.let { " / 语言 $it" } ?: ""}"
        is TemplateTurnRequest -> "prompt ${result.promptChars} 字符 / ${result.enrichmentNotes.size} 条注入说明"
        is TemplateValidationReport -> "${result.violations.size} 处违规 / ${result.parsedSections.size} 个章节"
        is String -> "${result.length} 字符"
        else -> ""
    }

    private fun failedOutcome(
        recorder: PhaseRecorder,
        reason: String
    ): TemplateTurnOutcome = TemplateTurnOutcome(
        request = null,
        trace = recorder.build(clock()),
        failure = reason
    )

    /** 轮次内轨迹累积器（单轮次内顺序记录；轮次之间互不影响）。 */
    private class PhaseRecorder(
        private val templateId: String,
        private val startedAt: Long
    ) {
        private val phases = mutableListOf<TemplatePhaseTrace>()

        fun record(phase: TemplatePhaseKind, ok: Boolean, durationMs: Long, note: String = "") {
            phases.add(TemplatePhaseTrace(phase, ok, durationMs, note))
        }

        fun build(finishedAt: Long): TemplateExecutionTrace =
            TemplateExecutionTrace(templateId, phases.toList(), startedAt, finishedAt)
    }

    /** 快照构建：注册表模型 → 运行时防腐快照（内置标记一并传递）。 */
    private fun snapshotOf(template: PromptTemplate): TemplateTemplateSnapshot =
        TemplateTemplateSnapshot(
            id = template.id,
            name = template.name,
            content = template.content,
            declaredVariableNames = template.variables.map { it.name },
            categoryLabel = TemplateBehaviorBase.categoryLabelOf(template),
            isBuiltIn = BuiltinPromptTemplates.isKnownBuiltInId(template.id)
        )

    companion object {
        const val REASON_TEMPLATE_NOT_FOUND = "TEMPLATE_NOT_FOUND"
        const val REASON_MISSING_REQUIRED = "MISSING_REQUIRED_VARIABLES"

        /**
         * 「内容缺口」违规族：修复阶段只能垫壳/标注，不能凭空补内容 ——
         * 这些违规修复后仍保留在 residualViolations 引导用户追问。
         */
        val CONTENT_GAP_KINDS: Set<TemplateContractViolationKind> = setOf(
            TemplateContractViolationKind.MISSING_SECTION,
            TemplateContractViolationKind.SECTION_EMPTY,
            TemplateContractViolationKind.LIST_TOO_SHORT,
            TemplateContractViolationKind.MISSING_VERDICT,
            TemplateContractViolationKind.EMPTY_OUTPUT,
            TemplateContractViolationKind.OFF_TOPIC
        )
    }
}

/**
 * 激活结果（不可变快照）。
 *
 * @param ok 是否成功激活
 * @param template 模板快照（失败于未知 id 时为 null）
 * @param variables 变量合并报告
 * @param behavior 绑定的行为（失败时 null）
 * @param manifest 行为清单（失败时 null）
 * @param missingRequired 缺失的必填变量名（仅 MISSING_REQUIRED 场景非空）
 * @param failureReason 失败原因码（见 [TemplateRuntime] companion）
 * @param options 运行选项快照
 * @param activatedAt 激活时间戳
 */
data class TemplateActivation(
    val ok: Boolean,
    val template: TemplateTemplateSnapshot?,
    val variables: TemplateVariableReport,
    val behavior: AgentTemplateBehavior?,
    val manifest: TemplateBehaviorManifest?,
    val missingRequired: List<String> = emptyList(),
    val failureReason: String? = null,
    val options: TemplateRuntimeOptions = TemplateRuntimeOptions(),
    val activatedAt: Long = 0
)

/**
 * 轮次组装结果。
 *
 * @param request 组装出的轮次请求（null = 失败）
 * @param trace 执行轨迹（六阶段已执行部分的留痕）
 * @param failure 失败说明（成功时 null）
 */
data class TemplateTurnOutcome(
    val request: TemplateTurnRequest?,
    val trace: TemplateExecutionTrace,
    val failure: String? = null
) {
    val ok: Boolean get() = request != null
}
