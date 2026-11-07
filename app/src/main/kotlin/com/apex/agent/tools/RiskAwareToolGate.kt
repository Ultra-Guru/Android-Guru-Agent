package com.apex.agent.tools

import com.apex.agent.core.engine.AgentQuestion
import com.apex.agent.core.engine.AgentQuestionOption
import com.apex.agent.core.engine.UserQuestionGateway
import com.apex.agent.core.tools.AgentTool
import com.apex.agent.core.tools.GateDecision
import com.apex.agent.core.tools.SessionToolDecision
import com.apex.agent.core.tools.ToolCategory
import com.apex.agent.core.tools.ToolExecutionGate
import com.apex.agent.core.tools.ToolMetadata
import com.apex.agent.core.tools.ToolPermissionManager
import com.apex.agent.core.tools.ToolRisk
import java.util.concurrent.ConcurrentHashMap

/**
 * # Tool System v2 — App 端风险门
 *
 * [ToolPermissionManager]（core，纯 JVM 状态机）+ 用户确认对话框的组合，
 * 注入 [DefaultToolExecutor] 后即生效：
 *
 * - **确认面（S-03 恢复风险判定）**：默认档（DEFAULT）下副作用工具需
 *   确认 —— sensitiveAction（付费/逐次同意类）、HIGH 风险（删除/卸载/
 *   force-stop 族）、MEDIUM 风险且非只读（写文件 / git 提交 / 改状态）
 *   都会弹窗；只读 MEDIUM 与 LOW 静默放行。2026-10 曾把 HIGH/MEDIUM
 *   弹窗全部移除并默认 BYPASS（新装机即全自动），S-03 修复：默认档回到
 *   DEFAULT、弹窗面恢复；BYPASS 用户不进本门（PermissionModeGate 对
 *   AllowExplicit 直通），显式开启后行为与旧版一致。ShellExecute 的
 *   高危命令仍由 CommandPermissionGate 在命令级拦截。
 * - "仅允许一次" → 本次放行但不记录（下次再问）；
 * - "本会话允许" → 状态机记 ALLOWED_SESSION，之后静默放行；
 * - 拒绝（用户显式点击，或弹窗被主动取消）→ 状态机记 DENIED_SESSION，
 *   后续调用直接拒绝并给模型可执行指引（换方案，勿重试同一工具）；
 * - 确认超时（#215）→ 超时未决：状态机不动（下次调用重新询问，
 *   本会话不被静默封禁），本次调用明确失败并附「确认超时」语义 ——
 *   绝不把超时静默记成会话级拒绝；
 * - `selfGated` 工具（ShellExecute 已预置）跳过本门——它们自带更细粒度
 *   的命令级确认，双重弹窗只会骚扰用户。
 * - **#336 凭据脱敏**：落审计（tool-audit.jsonl + Logcat）与确认弹窗的
 *   参数摘要先经 [SensitiveArgRedactor.redact] 做字段级掩码（api_key /
 *   token 等凭证值折叠为 `sk-a…3456` 形），sms_send 的 message、phone
 *   等非凭证字段保持明文（用户必须能看到将发送什么短信）。
 * - **#342 付费工具强制逐次确认**：sms_send 不提供「本会话允许」选项，
 *   既有会话级放行记录同样不生效（详见 [NO_SESSION_ALLOW_TOOLS]），
 *   叠加宿主侧 SmsRateLimiter 硬限额双保险。
 * - **#F-⑯**：每次门决策（弹窗结果 / 会话命中）经 [audit] 输出结构化
 *   JSONL 审计（tool / decision / durationMs）——「agent 在 X 场景走了什么
 *   通道、用户批没批」自此可对外举证。
 *
 * 交互闭环留在 app 层是刻意的：core:tool-registry 不依赖引擎问题桥
 * （依赖方向约束），任何宿主（测试、headless 运行时）注入自己的确认
 * 回调即可复用同一状态机。
 */
class RiskAwareToolGate(
    private val gateway: UserQuestionGateway,
    private val audit: ToolAuditLogger? = null,
    // #208：弹窗文案源（默认中文兜底供 JVM 单测；生产由 DI 注入资源版）。
    private val strings: GateDialogStrings = GateDialogStrings.DefaultCn
) : ToolExecutionGate {

    private val manager = ToolPermissionManager(
        confirm = { _, _ -> false } // never used — check() drives the FSM directly
    )

    /**
     * 各工具最近一次「确认超时」时刻（#215 边界补齐）：超时仍不写
     * DENIED_SESSION（不是会话拒绝），但短冷却内同工具不再弹窗 ——
     * 防止模型立刻重发再挂一个 300s 等待窗（用户实测：mcp_list 超时后
     * 模型 1s 内重发，连挂 4 次、纯等 20 分钟才被环路检测捕获）。
     */
    private val lastConfirmTimeoutAt = ConcurrentHashMap<String, Long>()

    override suspend fun check(tool: AgentTool, arguments: String): GateDecision {
        if (tool.id in manager.selfGatedToolIds) return GateDecision.Allow

        val metadata = tool.metadata
        // S-03：恢复风险判定（见 requiresPrompt）—— 默认档（DEFAULT）下
        // sensitiveAction / HIGH / MEDIUM-副作用工具进入确认面；上游
        // PermissionModeGate 的模式语义不变（BYPASS 全放行不进本门、
        // PLAN 只读、删除类在权限门先问），ShellExecute（selfGated）的
        // 高危命令仍由 CommandPermissionGate 在命令级拦截。
        if (!requiresPrompt(metadata)) return GateDecision.Allow

        return when (manager.decisionFor(tool.id)) {
            SessionToolDecision.ALLOWED_SESSION -> {
                // #342：永不提供会话级放行的工具（sms_send）即使存在历史或
                // 设置界面写入的 ALLOWED_SESSION 记录，也重新逐次确认——
                // 付费副作用不允许静默连发。
                if (tool.id in NO_SESSION_ALLOW_TOOLS) {
                    promptUser(tool, metadata, arguments)
                } else {
                    audit?.log(ToolAuditLogger.Event(tool = tool.id, decision = "allow", detail = "session"))
                    GateDecision.Allow
                }
            }
            SessionToolDecision.DENIED_SESSION -> {
                audit?.log(ToolAuditLogger.Event(tool = tool.id, decision = "deny", detail = "session"))
                GateDecision.Deny(
                    "user previously denied '${tool.id}' this session; do not retry it — " +
                        "choose a different approach and tell the user why"
                )
            }
            SessionToolDecision.UNDECIDED -> {
                // ═══ 确认超时短冷却（快速失败，不再挂第二个 300s 弹窗）═══
                // 冷却期内同工具直接拒绝并给「先问用户/换路」指引；过期后
                // 恢复询问 —— 保持 #215「超时未决 ≠ 会话拒绝」语义，不是封禁。
                val lastTimeoutAt = lastConfirmTimeoutAt[tool.id]
                if (lastTimeoutAt != null &&
                    System.currentTimeMillis() - lastTimeoutAt < CONFIRM_TIMEOUT_COOLDOWN_MS
                ) {
                    audit?.log(ToolAuditLogger.Event(
                        tool = tool.id, decision = "deny", detail = "confirm timeout cooldown"
                    ))
                    GateDecision.Deny(
                        "confirmation for '${tool.id}' timed out recently — the user likely " +
                            "missed the dialog; do NOT call this tool again right now. " +
                            "Explicitly ask the user for a decision, or pick a different " +
                            "approach; the permission prompt can be re-offered later"
                    )
                } else {
                    promptUser(tool, metadata, arguments)
                }
            }
        }
    }

    private suspend fun promptUser(
        tool: AgentTool,
        metadata: ToolMetadata,
        arguments: String
    ): GateDecision {
        val startedAt = System.currentTimeMillis()
        val answer = gateway.ask(buildQuestion(metadata, arguments))
        val durationMs = System.currentTimeMillis() - startedAt
        return when {
            // #215 超时未决：不写 DENIED_SESSION —— 本会话该工具不被静默封禁，
            // 下次调用重新询问；本次调用明确失败并附「确认超时」语义，
            // 模型不会把无响应误读为同意（失败卡片对用户可见，不静默）。
            // 合并注：超时同样进审计（durationMs 记等待全程），决策值 confirm_timeout
            // 与 allow_session / denied_by_user 同为自由字符串，语义互不重叠。
            answer.timedOut -> {
                // 记入短冷却：模型若无视指引立刻重发，快速失败而非再挂 300s 窗
                lastConfirmTimeoutAt[tool.id] = System.currentTimeMillis()
                audit?.log(ToolAuditLogger.Event(
                    tool = tool.id, decision = "confirm_timeout", durationMs = durationMs,
                    // #336：审计 command 先过字段级脱敏再截断，防 api_key 明文落盘
                    command = SensitiveArgRedactor.redact(arguments).take(200),
                    detail = "ask timed out, not denied"
                ))
                GateDecision.Deny(
                    "confirmation for '${tool.id}' timed out " +
                        "(${ASK_TIMEOUT_MS / 1000}s, ${categoryLabel(metadata.category)}/${riskLabel(metadata.risk)}); " +
                        "treat it as unanswered, not denied — but do NOT immediately re-call " +
                        "this tool: the user likely missed the dialog and a retry would hang " +
                        "for another ${ASK_TIMEOUT_MS / 1000}s. Ask the user for an explicit " +
                        "decision first, or pick a safer alternative; the prompt can be " +
                        "re-offered later in this session"
                )
            }
            answer.selectedOptionId == "allow_session" -> {
                manager.allowForSession(tool.id)
                audit?.log(ToolAuditLogger.Event(
                    tool = tool.id, decision = "allow_session", durationMs = durationMs,
                    command = SensitiveArgRedactor.redact(arguments).take(200),
                    detail = "user approved for session"
                ))
                GateDecision.Allow
            }
            answer.selectedOptionId == "allow_once" -> {
                // 不记录 —— 下次调用重新询问（真正的"一次"）。
                audit?.log(ToolAuditLogger.Event(
                    tool = tool.id, decision = "allow_once", durationMs = durationMs,
                    command = SensitiveArgRedactor.redact(arguments).take(200),
                    detail = "user approved once"
                ))
                GateDecision.Allow
            }
            else -> {
                manager.denyForSession(tool.id)
                audit?.log(ToolAuditLogger.Event(
                    tool = tool.id, decision = "denied_by_user", durationMs = durationMs,
                    command = SensitiveArgRedactor.redact(arguments).take(200),
                    detail = if (answer.skipped) "question timeout/skip" else "user denied"
                ))
                GateDecision.Deny(
                    "user denied execution of '${tool.id}' " +
                        "(${categoryLabel(metadata.category)}/${riskLabel(metadata.risk)}); " +
                        "do not retry, propose an alternative and explain to the user"
                )
            }
        }
    }

    private fun buildQuestion(metadata: ToolMetadata, arguments: String): AgentQuestion {
        // 定性档位（危险/平衡/无危险）而非数字评分 —— 用户能直接映射到「该不该放行」
        val riskHint = when (metadata.risk) {
            ToolRisk.HIGH -> "危险：破坏性或不可逆操作"
            ToolRisk.MEDIUM -> "平衡：会修改数据或状态"
            ToolRisk.LOW -> "无危险"
        }
        return AgentQuestion(
            // 风险确认（S-03 恢复后的确认面）：标题按语义命名
            title = "敏感操作需要确认：${metadata.id}",
            description = "$riskHint（${categoryLabel(metadata.category)}）\n" +
                // #336：弹窗参数摘要先脱敏再截断——api_key 等凭证值折叠，
                // sms_send 的 message / phone 保持明文（用户须看到将发什么）。
                "参数摘要：${SensitiveArgRedactor.redact(arguments).take(200).replace('\n', ' ')}",
            options = buildList {
                // #342：付费副作用工具（sms_send）不提供会话级放行——每条
                // 短信都必须逐次确认，防「一次授权 + 无限连发」。
                if (metadata.id !in NO_SESSION_ALLOW_TOOLS) {
                    add(AgentQuestionOption(
                        id = "allow_session",
                        label = strings.allowSessionLabel(),
                        description = strings.allowSessionDesc()
                    ))
                }
                add(AgentQuestionOption(
                    id = "allow_once",
                    label = strings.allowOnceLabel(),
                    description = strings.allowOnceDesc()
                ))
                add(AgentQuestionOption(
                    id = "deny",
                    label = strings.denyLabel(),
                    description = strings.denyDesc(),
                    recommended = true
                ))
            },
            allowCustom = false,
            allowSkip = false,
            timeoutMs = ASK_TIMEOUT_MS
        )
    }

    /** 会话级强制放行（设置界面 / 测试用）：同时清除确认超时冷却。 */
    fun allowForSession(toolId: String) {
        manager.allowForSession(toolId)
        lastConfirmTimeoutAt.remove(toolId)
    }

    /**
     * 仍需弹窗的工具（S-03 恢复风险判定）：
     *
     * ① 显式声明敏感动作的工具（sensitiveAction —— AutoGPT 语义的会话内
     *    人审，如浏览器凭据读取、付费动作）；
     * ② HIGH 风险工具（删除 / 卸载 / force-stop 族 —— 不可逆动作必问）；
     * ③ MEDIUM 风险且非只读的工具（写文件 / git 提交 / 改状态等副作用）。
     *
     * 2026-10 曾按用户反馈「常规使用零打扰」把 HIGH/MEDIUM 弹窗全部移除、
     * 并把默认档翻成 BYPASS（S-03 修复对象：新装机即全自动 + 存量静默提权）。
     * S-03 后默认档回到 DEFAULT —— 本门重新承担「副作用工具的默认确认面」；
     * 想要零打扰的用户显式切 BYPASS（PermissionModeGate 对 AllowExplicit
     * 直通，不再进本门），语义与旧版一致。
     *
     * 只读的 MEDIUM 工具（查询类）不弹 —— 确认预算留给真正改环境的动作。
     */
    private fun requiresPrompt(metadata: ToolMetadata): Boolean =
        metadata.annotations.sensitiveAction ||
            metadata.risk == ToolRisk.HIGH ||
            (metadata.risk == ToolRisk.MEDIUM && !metadata.annotations.readOnlyHint)

    /** 会话级强制拒绝（设置界面 / 测试用）。 */
    fun denyForSession(toolId: String) = manager.denyForSession(toolId)

    /** 新会话：清除全部会话决定（含确认超时冷却）。 */
    fun resetSession() {
        manager.reset()
        lastConfirmTimeoutAt.clear()
    }

    /** 当前会话决定快照（调试 / 设置界面展示）。 */
    fun sessionSnapshot(): Map<String, SessionToolDecision> = manager.snapshot()

    private fun categoryLabel(category: ToolCategory): String = category.label
    private fun riskLabel(risk: ToolRisk): String = risk.label

    private companion object {
        const val ASK_TIMEOUT_MS = 5 * 60 * 1000L

        /**
         * 确认超时后的同工具快速失败冷却窗：过期后弹窗恢复可问
         * （用户可能已回到屏幕前）。不记 DENIED_SESSION，语义是「稍后再问」。
         */
        const val CONFIRM_TIMEOUT_COOLDOWN_MS = 2 * 60 * 1000L

        /**
         * #342：永不提供会话级放行的工具 id——单一事实源已提升至
         * [NO_SESSION_ALLOW_TOOLS]（permission 包）供权限门与风险门共用；
         * 本引用保留向后兼容（内部消费点未改）。修正注：工具 id 已
         * PascalCase 化（SmsSend），旧 snake_case 条目从不命中——#342
         * 逐次确认实际已失效一整段，两态并存防回归。
         * （合并注：THIRD_PARTY_PREFIXES / isThirdPartyDynamic 随 2026-10
         * 确认面收敛移除；S-03 恢复风险判定后第三方 MEDIUM-副作用工具经
         * 通用风险通道进入确认面，无需独立判定器。）
         */
        val NO_SESSION_ALLOW_TOOLS = com.apex.agent.permission.NO_SESSION_ALLOW_TOOLS
    }
}
