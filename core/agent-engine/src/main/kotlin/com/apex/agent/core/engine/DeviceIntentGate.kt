package com.apex.agent.core.engine

import java.util.concurrent.ConcurrentHashMap

/**
 * ═══ DCP 统一风险门（S03 语义分级）═══
 *
 * 结构化意图不走文本子串匹配（那是 [CommandPermissionGate] 给自由文本的
 * 兜底）；本门直接读 manifest 的 `risk` + `reversible` 语义分级：
 *
 * 分级表（按序短路）：
 *  1. 用户规则（按 intentId 匹配，尾缀星号通配）—— DENY 恒拒、ASK 恒问，
 *     **BYPASS 下仍生效**（用户手写规则是显式意志，不能被模式静默吞掉，
 *     对齐 PermissionDecider.decideBypass 的哲学）；
 *  2. PLAN 模式 —— 写意图直接 Deny（保住规划阶段零副作用）；dry_run /
 *     READ / LOW 只读放行；
 *  3. CRITICAL 或 `reversible=false`（含 purge=true 变体）—— **恒 Ask，
 *     BYPASS 也不例外**；`allow_session` 对不可逆意图不生效（只放
 *     `allow_once`，防会话级放行后连删 20 个包）；
 *  4. HIGH（可逆）—— Ask；
 *  5. MEDIUM / LOW / READ —— BYPASS 放行；DEFAULT / ACCEPT_EDITS 放行
 *     （MEDIUM 及以下的风险交给 executor 侧的 token / preflight 把关）。
 *
 * 自由文本命令**不进本门**：ShellExecute / TerminalExec 继续走旧
 * CommandPermissionGate，零回归（`rm -rf /sdcard/x` 照样弹确认）。
 *
 * 纯 Kotlin（零 Android / 零 IO）：ask / rules / mode / now 全部函数注入，
 * JVM 单测手写 fake 穷举全矩阵。
 */
class DeviceIntentGate(
    private val ask: suspend (AgentQuestion) -> AgentAnswer,
    private val rules: () -> List<DeviceGateRule> = { emptyList() },
    private val mode: () -> DeviceGateMode = { DeviceGateMode.DEFAULT },
    private val now: () -> Long = System::currentTimeMillis
) {

    /** 会话级放行记忆（reversible=true 的意图才允许进这里）。 */
    private val sessionAllowed = ConcurrentHashMap.newKeySet<String>()

    /**
     * 对一次意图调用做完整决策（含用户交互）。
     *
     * @param intentId manifest 意图 id
     * @param risk manifest 声明的风险级
     * @param reversible manifest 声明的可逆性（purge=true 时调用方应传 false）
     * @param dryRun true = 只跑只读 preflight（门按只读放行，不弹确认）
     * @param description Ask 弹窗里展示给用户的影响面描述（null = 无 diff）
     * @return Allow / Ask（已得到用户答复并计入会话记忆）/ Deny
     */
    suspend fun decide(
        intentId: String,
        risk: DeviceGateRisk,
        reversible: Boolean,
        dryRun: Boolean,
        description: String? = null
    ): DeviceGateDecision {
        // 1. 用户规则最优先（BYPASS 也不豁免 —— 显式意志）。
        val matched = rules().firstOrNull { it.matches(intentId) }
        if (matched != null) {
            return when (matched.effect) {
                DeviceGateEffect.DENY -> DeviceGateDecision.Deny(
                    "意图 $intentId 命中了拒绝规则（模式：${matched.pattern}）。" +
                        "请勿重试同一意图；如确需执行，请在「设置 → 工具权限」中调整该条规则。"
                )
                // ASK 规则：可逆意图给会话放行选项；不可逆仅 allow_once（防会话级放行）。
                DeviceGateEffect.ASK -> {
                    val allowed = askUser(intentId, description, allowSession = reversible)
                    if (allowed) DeviceGateDecision.Allow
                    else DeviceGateDecision.Deny(DENIED_BY_USER)
                }
                DeviceGateEffect.ALLOW -> DeviceGateDecision.Allow
            }
        }

        // 2. PLAN：只读承诺 —— 写意图恒拒；dry_run / 只读放行。
        if (mode() == DeviceGateMode.PLAN) {
            return if (dryRun || risk == DeviceGateRisk.READ) {
                DeviceGateDecision.Allow
            } else {
                DeviceGateDecision.Deny(
                    "PLAN 模式为只读规划模式，禁止执行会修改环境的设备意图（$intentId）。" +
                        "可先 dry_run 预演（只读），或请用户切换权限模式后再执行"
                )
            }
        }

        // 3. 不可逆 / CRITICAL：恒 Ask（BYPASS 也不豁免）；dry_run 只读放行。
        val irreversible = !reversible || risk == DeviceGateRisk.CRITICAL
        if (irreversible) {
            if (dryRun) return DeviceGateDecision.Allow
            val decision = askUser(intentId, description, allowSession = false)
            return if (decision == true) DeviceGateDecision.Allow
            else DeviceGateDecision.Deny(DENIED_BY_USER)
        }

        // 4. 会话记忆（仅可逆意图）：本会话允许过的不再问。
        if (reversible && intentId in sessionAllowed) {
            return DeviceGateDecision.Allow
        }

        // 5. HIGH（可逆）：Ask（S03 分级表：HIGH 恒问，BYPASS 也不例外；
        //    allow_session 生效 —— 可逆动作可会话级放行）。
        if (risk == DeviceGateRisk.HIGH) {
            val decision = askUser(intentId, description, allowSession = true)
            return if (decision) DeviceGateDecision.Allow
            else DeviceGateDecision.Deny(DENIED_BY_USER)
        }

        // 6. BYPASS：MEDIUM / LOW / READ 可逆意图放行（不可逆与 HIGH 已在前面拦下）。
        if (mode() == DeviceGateMode.BYPASS) {
            return DeviceGateDecision.Allow
        }

        // 7. DEFAULT / ACCEPT_EDITS：MEDIUM 及以下放行（风险交给 executor 侧
        //    的 token / preflight 把关）。
        return DeviceGateDecision.Allow
    }

    /** 弹一次确认；返回 true=放行，false=拒绝（allow_session 计入会话记忆）。 */
    private suspend fun askUser(
        intentId: String,
        description: String?,
        allowSession: Boolean
    ): Boolean {
        val question = AgentQuestion(
            title = "危险设备操作需要确认",
            description = buildString {
                append("意图：$intentId")
                description?.let {
                    append("\n\n")
                    append(it)
                }
                if (!allowSession) {
                    append("\n\n⚠ 不可逆操作：仅本次执行有效，不提供会话级放行")
                }
                append("\n\n来源：DeviceOps（结构化设备操作，已通过只读预检验证影响面）")
            },
            options = buildList {
                add(
                    AgentQuestionOption(
                        id = "allow_once",
                        label = "允许一次",
                        description = "仅本次允许执行该设备操作"
                    )
                )
                if (allowSession) {
                    add(
                        AgentQuestionOption(
                            id = "allow_session",
                            label = "本会话允许",
                            description = "本次会话中相同意图不再询问（仅可逆操作提供）"
                        )
                    )
                }
                add(
                    AgentQuestionOption(
                        id = "deny",
                        label = "拒绝",
                        description = "不执行该设备操作",
                        recommended = true
                    )
                )
            },
            allowCustom = false,
            allowSkip = false
        )
        val answer = ask(question)
        return when (answer.selectedOptionId) {
            "allow_once" -> true
            "allow_session" -> {
                // 防御分支：不可逆意图的弹窗不提供本会话选项；迟到/伪造的
                // allow_session 答案按拒绝处理（fail-safe，不放行）。
                if (allowSession) {
                    sessionAllowed.add(intentId)
                    true
                } else {
                    false
                }
            }
            else -> false
        }
    }

    private companion object {
        private const val DENIED_BY_USER =
            "用户拒绝执行该设备操作。请不要重试同一意图，改用更安全或可逆的方案（如 package.disable_user 优先于卸载），并告知用户原因。"
    }
}

/** 决策结果（纯语义；用户交互已在 decide 内完成）。 */
sealed class DeviceGateDecision {
    object Allow : DeviceGateDecision()
    data class Deny(val reason: String) : DeviceGateDecision()
}

/**
 * 权限模式（core 侧枚举 —— app 层 PermissionMode 到此的映射由 DI 完成；
 * 语义与 com.apex.agent.permission.PermissionMode 一一对应）。
 */
enum class DeviceGateMode { BYPASS, DEFAULT, ACCEPT_EDITS, PLAN }

/** 设备意图风险级（core 侧枚举 —— manifest 的 risk 值映射到此）。 */
enum class DeviceGateRisk { READ, LOW, MEDIUM, HIGH, CRITICAL }

/** 用户规则（app 层 PermissionRule 的 core 侧形态；匹配语义同 PermissionRuleMatcher）。 */
data class DeviceGateRule(
    val pattern: String,
    val effect: DeviceGateEffect
) {
    /** 尾缀单星号通配（`package.` 尾星命中一切 package.* 意图）；其余精确匹配。 */
    fun matches(intentId: String): Boolean {
        if (pattern.isBlank()) return false
        if (pattern == "*") return true
        return if (pattern.endsWith("*")) {
            val prefix = pattern.dropLast(1)
            prefix.isNotEmpty() && intentId.startsWith(prefix)
        } else {
            pattern == intentId
        }
    }
}

/** 规则效应。 */
enum class DeviceGateEffect { ALLOW, ASK, DENY }
