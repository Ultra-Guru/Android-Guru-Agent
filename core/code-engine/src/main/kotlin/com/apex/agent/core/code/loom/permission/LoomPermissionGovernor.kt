package com.apex.agent.core.code.loom.permission

import com.apex.agent.core.code.loom.LoomInvocation
import java.io.File

/**
 * # Loom Permission Governor — 织造线权限治理器
 *
 * 纯决策层（不挂起、不发事件——问答与回执由回合机接线）：按
 *
 * ```
 * 会话批准记忆 → 显式规则（命令规则优先于工具规则） → 模态兜底
 * ```
 *
 * 的层级给出 [LoomPermissionDecision]。
 *
 * ## 模态兜底矩阵
 *
 * | 模态 | READ | WRITE·工作区内 | WRITE·越界 | 敏感 |
 * |---|---|---|---|---|
 * | OBSERVE | 放行 | 问 | 问 | 问 |
 * | FABRIC | 放行 | 放行 | 问 | 问 |
 * | UNBOUND | 放行 | 放行 | 放行 | 问 |
 *
 * ## 边界语义
 *
 * 路径参数经 [isWithinWorkspace] 判定：宿主路径以工作区根为前缀；
 * PRoot guest 挂载路径（/workspace 前缀）换算回宿主根再判。路径语义
 * 缺失（web / 查询 / mcp）按「工作区内」处理（模态已表达会话基调）。
 *
 * ## 会话批准记忆
 *
 * 用户批准一次（「本会话允许」）即登记（工具 id 或 命令前缀），同会话
 * 内同类调用直通——直到 [clearApprovals]（新会话）。
 *
 * ## 规则模式串
 *
 * 见 [matchesPattern]：无星 = 全等（命令模式带前缀语义）、`tail*` 前缀、
 * `*head` 后缀、`pre*suf` 双段、`*mid*` 包含。
 */
class LoomPermissionGovernor(
    private val permissionSource: LoomPermissionSource,
    private val riskBook: LoomToolRiskBook = LoomToolRiskBook.defaultBook()
) {

    /** 会话批准记忆（工具 id / 命令前缀）。 */
    private val approvedTools = mutableSetOf<String>()
    private val approvedCommands = mutableSetOf<String>()

    // ═══════════════════════════ 决策 ═══════════════════════════

    /**
     * 判定一次调用。
     *
     * @param invocation 调用（参数用于路径/命令抽取）
     * @param workspaceRoot 工作区根（边界判定；null = 无边界语义全按界内）
     */
    fun decide(invocation: LoomInvocation, workspaceRoot: File?): LoomPermissionDecision {
        val profile = profileOf(invocation)
        val snapshot = permissionSource.snapshot()
        val commandLine = profile.commandLine

        // 1. 会话批准记忆（命令按前缀、工具按全等）
        if (approvedTools.contains(invocation.name)) {
            return LoomPermissionDecision.Allow
        }
        if (commandLine != null &&
            approvedCommands.any { commandLine.trim().startsWith(it) }
        ) {
            return LoomPermissionDecision.Allow
        }

        // 2. 显式规则（命令规则优先——命令行比工具名更具体）；后规则覆盖前规则
        if (commandLine != null) {
            val rule = snapshot.commandRules.lastOrNull { matchesPattern(it.pattern, commandLine) }
            if (rule != null) return rule.toDecision(commandLine)
        }
        snapshot.rules.lastOrNull { matchesPattern(it.pattern, invocation.name) }?.let { rule ->
            return rule.toDecision(invocation.name)
        }

        // 3. 模态兜底（敏感恒问——含 UNBOUND：不可逆操作不因「全自动」静默放行）
        val effect = riskBook.effectOf(invocation.name)
        val sensitive = riskBook.isSensitive(invocation.name)
        val withinWorkspace =
            workspaceRoot == null || isWithinWorkspace(profile, workspaceRoot)
        return when {
            sensitive -> LoomPermissionDecision.Ask("敏感操作：" + invocation.name)
            snapshot.mode == LoomAccessMode.UNBOUND -> LoomPermissionDecision.Allow
            effect == LoomToolEffect.READ -> LoomPermissionDecision.Allow
            snapshot.mode == LoomAccessMode.OBSERVE ->
                LoomPermissionDecision.Ask("当前为只读巡视模态，写操作需确认")
            withinWorkspace -> LoomPermissionDecision.Allow // FABRIC · 工作区内写
            else -> LoomPermissionDecision.Ask("写入越出工作区边界")
        }
    }

    /** 风险一行（问答弹窗文案用）。 */
    fun describeRisk(invocation: LoomInvocation): String = when {
        riskBook.isSensitive(invocation.name) -> "敏感操作"
        riskBook.effectOf(invocation.name) == LoomToolEffect.WRITE -> "写操作"
        else -> "只读操作"
    }

    // ═══════════════════════════ 会话批准记忆 ═══════════════════════════

    /** 登记一次会话级批准（问答弹窗「本会话允许」）。 */
    fun rememberApproval(invocation: LoomInvocation) {
        val commandLine = profileOf(invocation).commandLine
        if (commandLine != null) {
            approvedCommands.add(commandPrefix(commandLine))
        } else {
            approvedTools.add(invocation.name)
        }
    }

    /** 清空批准记忆（新会话 / clearConversation）。 */
    fun clearApprovals() {
        approvedTools.clear()
        approvedCommands.clear()
    }

    // ═══════════════════════════ 画像与边界 ═══════════════════════════

    /** 调用画像派生（路径 + 命令行抽取）。 */
    internal fun profileOf(invocation: LoomInvocation): LoomCallProfile {
        val command = invocation.arg("command") ?: invocation.arg("cmd")
        val paths = PATH_ARG_NAMES.flatMap { name ->
            invocation.args.filter { it.name == name }.map { it.value }
        }.distinct()
        return LoomCallProfile(
            toolId = invocation.name,
            commandLine = command,
            paths = paths
        )
    }

    /**
     * 工作区边界判定：全部路径都在界内才算界内（任一越界 → 越界）。
     *
     * guest 挂载路径（GUEST_MOUNT 前缀）换算回宿主根再判；相对路径按
     * 工作区根解析（code_* 工具的相对口径以根为基准）。
     */
    internal fun isWithinWorkspace(profile: LoomCallProfile, root: File): Boolean {
        if (profile.paths.isEmpty()) return true
        val canonicalRoot = runCatching { root.canonicalFile }.getOrDefault(root.absoluteFile)
        return profile.paths.all { raw ->
            val host = toHostPath(raw, canonicalRoot)
            val canonical = runCatching { host.canonicalFile }.getOrDefault(host.absoluteFile)
            canonical == canonicalRoot || isAncestor(canonicalRoot, canonical)
        }
    }

    /** guest 挂载路径 → 宿主路径（非挂载路径按相对/绝对原样解析）。 */
    private fun toHostPath(raw: String, root: File): File = when {
        raw.startsWith(GUEST_MOUNT + "/") -> File(root, raw.removePrefix(GUEST_MOUNT))
        raw == GUEST_MOUNT -> root
        raw.startsWith("/") -> File(raw) // 宿主绝对路径（沙箱内）
        else -> File(root, raw) // 相对路径以工作区根为基准
    }

    /** 祖先判定（canonical 逐级向上）。 */
    private fun isAncestor(ancestor: File, descendant: File): Boolean {
        var cur: File? = descendant
        while (cur != null) {
            if (cur == ancestor) return true
            cur = cur.parentFile
        }
        return false
    }

    /** 命令前缀（批准记忆登记粒度：首两词——git push / rm -rf 级）。 */
    private fun commandPrefix(commandLine: String): String =
        commandLine.trim().split(Regex("\\s+")).take(2).joinToString(" ")

    /** 规则效应 → 决策。 */
    private fun LoomPermissionRule.toDecision(subject: String): LoomPermissionDecision =
        when (effect) {
            LoomRuleEffect.ALLOW -> LoomPermissionDecision.Allow
            LoomRuleEffect.ASK -> LoomPermissionDecision.Ask("规则要求确认：" + pattern)
            LoomRuleEffect.DENY ->
                LoomPermissionDecision.Deny("规则拒绝：" + pattern + "（命中：" + subject + "）")
        }

    companion object {
        /** 路径类参数名（code_* / 文件工具的约定口径）。 */
        private val PATH_ARG_NAMES = listOf(
            "path", "file", "root", "dir", "target", "destination", "dest"
        )

        /** PRoot Ubuntu 会话中工作区统一挂载点（与 CodeAgentEngine 口径一致）。 */
        internal const val GUEST_MOUNT = "/workspace"

        /**
         * 规则模式匹配：
         * - 无星：全等；模式含空格（命令模式）另享前缀语义（`git push`
         *   命中 `git push origin main`——完整词边界）；
         * - `tail*` 前缀 / `*head` 后缀 / `pre*suf` 双段 / `*mid*` 包含。
         */
        internal fun matchesPattern(pattern: String, subject: String): Boolean {
            val p = pattern.trim()
            val s = subject.trim()
            if (p.isEmpty() || s.isEmpty()) return false
            if (!p.contains('*')) {
                if (s == p) return true
                if (p.contains(' ')) {
                    return s.length > p.length && s.startsWith(p) && s[p.length] == ' '
                }
                return false
            }
            if (p.length >= 2 && p.startsWith("*") && p.endsWith("*")) {
                return s.contains(p.substring(1, p.length - 1))
            }
            if (p.startsWith("*")) return s.endsWith(p.drop(1).trimStart())
            if (p.endsWith("*")) return s.startsWith(p.dropLast(1).trimEnd())
            val star = p.indexOf('*')
            val prefix = p.substring(0, star)
            val suffix = p.substring(star + 1)
            return s.length >= prefix.length + suffix.length &&
                s.startsWith(prefix) && s.endsWith(suffix)
        }
    }
}
