package com.apex.agent.ui.screen.code

import com.apex.agent.core.code.playbook.CodeTaskClassifier
import com.apex.agent.core.code.playbook.PlaybookMatch
import com.apex.agent.core.engine.templates.playbook.AutoMountMode
import com.apex.agent.core.engine.templates.playbook.BuiltinPlaybooks
import com.apex.agent.core.engine.templates.playbook.PlaybookAdvanceJudge
import com.apex.agent.core.engine.templates.playbook.PlaybookDefinition
import com.apex.agent.core.engine.templates.playbook.PlaybookDirectiveBuilder
import com.apex.agent.core.engine.templates.playbook.PlaybookReconCatalog
import com.apex.agent.core.engine.templates.playbook.PlaybookRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * # Code Playbook Controller — 任务剧本控制器（task-playbooks PR-B）
 *
 * 模板工坊 agentic 升级的 app 侧单点接线：发送前任务识别（双门）→ 建议/
 * 挂载 → 指令段产出（并入 sessionExtras 管道）→ 幕推进判定 → 状态迁移。
 * 与 CodeRoleController 同款形态（设置流 + 引擎 accessor + VM lazy 暴露），
 * 引擎侧零接口变更——指令经既有 sessionExtras 通道进 additionalSystemContext
 * （深潜/标准/织造/信使四条线统一继承）。
 *
 * 生效时序契约：
 * - [prepareForRun] 在 prepareEngineContext 的 extras 组装**之前**调用：
 *   识别/挂载决策先行，指令段随本轮 extras 进上下文；
 * - [onAdvanceJudged] 消费 `AgentEvent.ResponseComplete(fullText)`：
 *   幕推进后 Ops 侧经 [recomposedExtras] 重推整串 extras + prepareForTask
 *   热重织（maybeEscalateOnDeepWater 同款手法，本轮后续迭代即生效）；
 * - 剧本卸载在下一轮发送自然失效（prepareForRun 不再产出指令段）。
 *
 * 诚实边界：建议条永不静默转 AUTO（档位是用户设置）；「本次不用」只在
 * 进程内生效（ dismissedIds 不持久化——下个会话给剧本第二次机会）。
 */
class CodePlaybookController(
    /** 自动挂载档位供给（"off"/"suggest"/"auto"；宿主接设置流快照，测试可注入常量）。 */
    private val autoMountRaw: () -> String,
    private val registry: PlaybookRegistry,
    private val classifier: CodeTaskClassifier = CodeTaskClassifier()
) {

    // ═══ 状态 ═══

    /** 建议条待决项。 */
    data class Suggestion(
        val playbook: PlaybookDefinition,
        val reason: String,
        val score: Int
    )

    /** 挂载中的剧本会话（chip 展示口径）。 */
    data class MountedSession(
        val playbook: PlaybookDefinition,
        val stageIndex: Int,
        val completedStages: List<Int>,
        /** 当前幕侦察附录行（幕激活时计算，幕推进时刷新）。 */
        val reconLines: List<String>
    )

    /** 幕推进判定结果（Ops 层发系统消息 + 热重织用）。 */
    data class AdvanceOutcome(
        val kind: PlaybookAdvanceJudge.AdvanceKind,
        val reason: String,
        val playbookName: String,
        val newStageIndex: Int
    )

    data class UiState(
        val suggest: Suggestion? = null,
        val mounted: MountedSession? = null,
        /** 「本次不用」的剧本 id（进程内）。 */
        val dismissedIds: Set<String> = emptySet()
    ) {
        val hasSuggestion: Boolean get() = suggest != null
        val hasMounted: Boolean get() = mounted != null
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** 最近一次非剧本 extras 基串（幕推进热重织时重拼整串用）。 */
    @Volatile
    private var lastBaseExtras: String? = null

    /** 最近一轮的任务目标（幕推进重推时上下文门重估用）。 */
    @Volatile
    private var lastGoal: String = ""

    /** 最近一轮的工作区在场信号。 */
    @Volatile
    private var lastHasWorkspace: Boolean = false

    // ═══ 发送前（prepareEngineContext 调用）═══

    /**
     * 发送前识别 + 挂载决策 + 指令段产出。
     *
     * @param baseExtras 已组装好的非剧本 extras 串（缓存供幕推进重拼）
     * @param goal 本轮任务目标（识别输入）
     * @param hasWorkspace 工作区在场（上下文门）
     * @return 拼好剧本段的完整 extras（未挂载 = 原串）
     */
    fun prepareForRun(baseExtras: String, goal: String, hasWorkspace: Boolean): String {
        lastBaseExtras = baseExtras
        lastGoal = goal
        lastHasWorkspace = hasWorkspace
        val mounted = _state.value.mounted
        val directive = if (mounted != null) {
            directiveFor(mounted)
        } else {
            evaluateAutoMount(goal, hasWorkspace)
            null
        }
        return directive?.let { "$baseExtras\n\n$it" } ?: baseExtras
    }

    /** 档位解析 + 识别 + 建议/静默挂载（未挂载时每次发送重估）。 */
    private fun evaluateAutoMount(goal: String, hasWorkspace: Boolean) {
        val mode = parseAutoMount(autoMountRaw())
        if (mode == AutoMountMode.OFF) return
        val candidates = registry.list().ifEmpty { BuiltinPlaybooks.ALL }
        val match = classifier.classify(goal, candidates, hasWorkspace = hasWorkspace)
        if (!match.hit) {
            // 未命中：清上一轮建议（建议条只在命中轮展示）。
            if (_state.value.suggest != null) {
                _state.value = _state.value.copy(suggest = null)
            }
            return
        }
        val playbook = resolvePlaybook(match.playbookId!!) ?: return
        if (playbook.id in _state.value.dismissedIds) return
        when (mode) {
            AutoMountMode.AUTO -> mountInternal(playbook)
            AutoMountMode.SUGGEST -> _state.value = _state.value.copy(
                suggest = Suggestion(playbook, match.reason, match.score)
            )
            AutoMountMode.OFF -> Unit
        }
    }

    // ═══ 动作（UI 入口）═══

    /** 建议确认 → 挂载。 */
    fun acceptSuggestion() {
        val suggestion = _state.value.suggest ?: return
        mountInternal(suggestion.playbook)
    }

    /** 「本次不用」（进程内记住，本轮建议清空）。 */
    fun dismissSuggestion() {
        val suggestion = _state.value.suggest ?: return
        _state.value = _state.value.copy(
            suggest = null,
            dismissedIds = _state.value.dismissedIds + suggestion.playbook.id
        )
    }

    /** 手动挂载（chip 更换 / 剧本清单入口）。 */
    fun mount(playbookId: String): Boolean {
        val playbook = resolvePlaybook(playbookId) ?: return false
        mountInternal(playbook)
        return true
    }

    /** 卸载（chip 动作；下一轮发送自然失效）。 */
    fun unmount() {
        _state.value = _state.value.copy(mounted = null)
    }

    /** 幕进度 sheet 手动跳幕（校验范围）。 */
    fun jumpToStage(stageIndex: Int): Boolean {
        val mounted = _state.value.mounted ?: return false
        if (stageIndex !in mounted.playbook.stages.indices) return false
        val completed = (0 until stageIndex).toList()
        _state.value = _state.value.copy(
            mounted = mounted.copy(
                stageIndex = stageIndex,
                completedStages = completed,
                reconLines = reconFor(mounted.playbook, stageIndex)
            )
        )
        return true
    }

    // ═══ 幕推进（事件侧）═══

    /**
     * 消费回包全文做幕推进判定（非挂载态返回 null）。判定只迁状态；
     * 系统消息与引擎热重织由 Ops 层执行。
     */
    fun onAdvanceJudged(fullText: String): AdvanceOutcome? {
        val mounted = _state.value.mounted ?: return null
        val decision = PlaybookAdvanceJudge.judge(fullText, mounted.playbook, mounted.stageIndex)
        if (decision.kind == PlaybookAdvanceJudge.AdvanceKind.STAYED) {
            return AdvanceOutcome(
                kind = decision.kind,
                reason = decision.reason,
                playbookName = mounted.playbook.name,
                newStageIndex = mounted.stageIndex
            )
        }
        val nextIndex = decision.nextStageIndex
        _state.value = _state.value.copy(
            mounted = mounted.copy(
                stageIndex = nextIndex,
                completedStages = (mounted.completedStages + listOf(mounted.stageIndex)).distinct().sorted(),
                reconLines = reconFor(mounted.playbook, nextIndex)
            )
        )
        return AdvanceOutcome(
            kind = decision.kind,
            reason = decision.reason,
            playbookName = mounted.playbook.name,
            newStageIndex = nextIndex
        )
    }

    /** 幕推进后重拼完整 extras（Ops 持锁外调用，纯内存）。 */
    fun recomposedExtras(): String? {
        val base = lastBaseExtras ?: return null
        val mounted = _state.value.mounted ?: return base
        return "$base\n\n${directiveFor(mounted)}"
    }

    // ═══ 内部机械 ═══

    private fun mountInternal(playbook: PlaybookDefinition) {
        _state.value = _state.value.copy(
            suggest = null,
            mounted = MountedSession(
                playbook = playbook,
                stageIndex = 0,
                completedStages = emptyList(),
                reconLines = reconFor(playbook, 0)
            )
        )
        // 使用计数走注册表通道（incrementUsage 是 suspend——记账回调由宿主
        // VM 接 viewModelScope 异步执行，失败不阻断挂载）。
        onMountRecorded?.invoke(playbook.id)
    }

    /** 挂载记账回调（宿主接 suspend incrementUsage，见 CodeViewModel）。 */
    var onMountRecorded: ((String) -> Unit)? = null

    private fun directiveFor(mounted: MountedSession): String =
        PlaybookDirectiveBuilder.build(
            playbook = mounted.playbook,
            stageIndex = mounted.stageIndex,
            completedStageIndexes = mounted.completedStages,
            reconLines = mounted.reconLines
        )

    private fun reconFor(playbook: PlaybookDefinition, stageIndex: Int): List<String> {
        val stage = playbook.stageAt(stageIndex) ?: return emptyList()
        val inputs = buildMap {
            for (variable in playbook.variables) {
                val value = variable.defaultValue.takeIf { it.isNotBlank() } ?: continue
                put(variable.name, value)
            }
            put(PlaybookReconCatalog.KEY_TASK, lastGoal)
        }
        return PlaybookReconCatalog.run(stage.reconId, inputs).lines
    }

    private fun resolvePlaybook(playbookId: String): PlaybookDefinition? {
        registry.get(playbookId)?.let { return it }
        return BuiltinPlaybooks.ALL.firstOrNull { it.id == playbookId }
    }

    private fun parseAutoMount(raw: String): AutoMountMode = when (raw.lowercase()) {
        "off" -> AutoMountMode.OFF
        "auto" -> AutoMountMode.AUTO
        else -> AutoMountMode.SUGGEST
    }
}
