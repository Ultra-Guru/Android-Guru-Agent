package com.apex.agent.ui.screen.code.neon

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ═══ 霓虹球状态仓（Neon Orb Store）═══
 *
 * Coding 输入栏霓虹球 16 项功能聚合的单一可信状态源，模式对齐
 * `ChatToolkitStore`（#197 小圆环）：StateFlow 暴露 + SharedPreferences
 * 持久化，组装逻辑全部委托纯函数 [buildNeonSessionContext]
 * （NeonOrbFeatures.kt —— 零 Android 依赖，单测在那边覆盖）。
 *
 * 消费链：NeonOrbButton（面板开关）→ 本仓状态 → CodeViewModel.runEngine
 * 每轮发送前 `buildSessionContext()` 拼进引擎 additionalSystemContext。
 */
@Singleton
class NeonOrbStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("apex_neon_orb", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(restore())
    val state: StateFlow<NeonOrbState> = _state.asStateFlow()

    /** 当前状态快照（非挂起读取）。 */
    val current: NeonOrbState get() = _state.value

    // ── 开关类（13 项）══════════════════════════════════

    fun setTimeInjection(enabled: Boolean) = mutate { it.copy(timeInjection = enabled) }
    fun setWebSearch(enabled: Boolean) = mutate { it.copy(webSearch = enabled) }
    fun setDeepReasoning(enabled: Boolean) = mutate { it.copy(deepReasoning = enabled) }
    fun setGoalEcho(enabled: Boolean) = mutate { it.copy(goalEcho = enabled) }
    fun setAntiHallucination(enabled: Boolean) = mutate { it.copy(antiHallucination = enabled) }
    fun setConcise(enabled: Boolean) = mutate { it.copy(concise = enabled) }
    fun setTeachMode(enabled: Boolean) = mutate { it.copy(teachMode = enabled) }
    fun setNumberedSteps(enabled: Boolean) = mutate { it.copy(numberedSteps = enabled) }
    fun setNoEmoji(enabled: Boolean) = mutate { it.copy(noEmoji = enabled) }
    fun setRichComments(enabled: Boolean) = mutate { it.copy(richComments = enabled) }
    fun setDiffFirst(enabled: Boolean) = mutate { it.copy(diffFirst = enabled) }
    fun setRiskWarnings(enabled: Boolean) = mutate { it.copy(riskWarnings = enabled) }
    fun setSafeCommands(enabled: Boolean) = mutate { it.copy(safeCommands = enabled) }

    // ── 单选档（3 项）══════════════════════════════════

    fun setOutputFormat(format: NeonOutputFormat) = mutate { it.copy(outputFormat = format) }
    fun setLanguageLock(lock: NeonLanguageLock) = mutate { it.copy(languageLock = lock) }
    fun setTone(tone: NeonTone) = mutate { it.copy(tone = tone) }

    /** 一键全关（面板「全部关闭」动作）。 */
    fun resetAll() = mutate { NeonOrbState() }

    // ── 组装（发送时刻实时取值）════════════════════════

    /** 霓虹球注入段（全关 = 空串，零负担）。 */
    fun buildSessionContext(): String = buildNeonSessionContext(_state.value)

    /**
     * 与小圆环上下文合并（CodeViewModel.runEngine 单点消费）：
     * 两段都有内容时以空行分隔，避免段落粘连。
     */
    fun mergeWith(other: String): String {
        val mine = buildSessionContext()
        return when {
            mine.isEmpty() -> other
            other.isEmpty() -> mine
            else -> other + "\n\n" + mine
        }
    }

    // ── 持久化 ═════════════════════════════════════════

    private fun mutate(transform: (NeonOrbState) -> NeonOrbState) {
        val next = transform(_state.value)
        _state.value = next
        prefs.edit()
            .putBoolean(KEY_TIME, next.timeInjection)
            .putBoolean(KEY_SEARCH, next.webSearch)
            .putString(KEY_FORMAT, next.outputFormat.name)
            .putBoolean(KEY_DEEP, next.deepReasoning)
            .putBoolean(KEY_GOAL_ECHO, next.goalEcho)
            .putBoolean(KEY_ANTI_HALLUCINATION, next.antiHallucination)
            .putString(KEY_LANG, next.languageLock.name)
            .putBoolean(KEY_CONCISE, next.concise)
            .putBoolean(KEY_TEACH, next.teachMode)
            .putBoolean(KEY_STEPS, next.numberedSteps)
            .putBoolean(KEY_NO_EMOJI, next.noEmoji)
            .putString(KEY_TONE, next.tone.name)
            .putBoolean(KEY_COMMENTS, next.richComments)
            .putBoolean(KEY_DIFF, next.diffFirst)
            .putBoolean(KEY_RISK, next.riskWarnings)
            .putBoolean(KEY_SAFE_CMD, next.safeCommands)
            .apply()
    }

    private fun restore(): NeonOrbState = NeonOrbState(
        timeInjection = prefs.getBoolean(KEY_TIME, false),
        webSearch = prefs.getBoolean(KEY_SEARCH, false),
        outputFormat = enumOrDefault(prefs.getString(KEY_FORMAT, null), NeonOutputFormat.NONE),
        deepReasoning = prefs.getBoolean(KEY_DEEP, false),
        goalEcho = prefs.getBoolean(KEY_GOAL_ECHO, false),
        antiHallucination = prefs.getBoolean(KEY_ANTI_HALLUCINATION, false),
        languageLock = enumOrDefault(prefs.getString(KEY_LANG, null), NeonLanguageLock.OFF),
        concise = prefs.getBoolean(KEY_CONCISE, false),
        teachMode = prefs.getBoolean(KEY_TEACH, false),
        numberedSteps = prefs.getBoolean(KEY_STEPS, false),
        noEmoji = prefs.getBoolean(KEY_NO_EMOJI, false),
        tone = enumOrDefault(prefs.getString(KEY_TONE, null), NeonTone.OFF),
        richComments = prefs.getBoolean(KEY_COMMENTS, false),
        diffFirst = prefs.getBoolean(KEY_DIFF, false),
        riskWarnings = prefs.getBoolean(KEY_RISK, false),
        safeCommands = prefs.getBoolean(KEY_SAFE_CMD, false)
    )

    private inline fun <reified T : Enum<T>> enumOrDefault(
        raw: String?,
        default: T
    ): T = raw?.let { name -> enumValues<T>().firstOrNull { it.name == name } } ?: default

    private companion object {
        private const val KEY_TIME = "neon_time_injection"
        private const val KEY_SEARCH = "neon_web_search"
        private const val KEY_FORMAT = "neon_output_format"
        private const val KEY_DEEP = "neon_deep_reasoning"
        private const val KEY_GOAL_ECHO = "neon_goal_echo"
        private const val KEY_ANTI_HALLUCINATION = "neon_anti_hallucination"
        private const val KEY_LANG = "neon_language_lock"
        private const val KEY_CONCISE = "neon_concise"
        private const val KEY_TEACH = "neon_teach_mode"
        private const val KEY_STEPS = "neon_numbered_steps"
        private const val KEY_NO_EMOJI = "neon_no_emoji"
        private const val KEY_TONE = "neon_tone"
        private const val KEY_COMMENTS = "neon_rich_comments"
        private const val KEY_DIFF = "neon_diff_first"
        private const val KEY_RISK = "neon_risk_warnings"
        private const val KEY_SAFE_CMD = "neon_safe_commands"
    }
}
