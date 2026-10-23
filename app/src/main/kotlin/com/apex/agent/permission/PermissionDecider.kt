package com.apex.agent.permission

/**
 * ═══ 业界标准式权限模式（Issue #155）—— 决策引擎 ═══
 *
 * 纯 Kotlin 决策核心：零 Android / 零网络 / 零 IO 依赖，
 * 输入（模式 + 规则 + 工具上下文）完全确定输出，
 * 同一输入永远得到同一决策——这使它可以在 JVM 单测里穷举全部矩阵，
 * 也使 [PermissionModeGate] 的交互闭环（询问 / 记忆 / 组合）可以被
 * 独立于决策逻辑验证。
 *
 * 决策表（按序短路，命中即返回）：
 *
 *  1. BYPASS 模式 → 全自动放行，但保留两条窄口径安全线（[decideBypass]）：
 *     DENY / ASK 规则仍参与（用户手写的规则是显式意志，不能被模式
 *     静默吞掉）；sensitiveAction（付费/逐次同意类）保持 Ask——
 *     ALLOW 规则命中视为用户已表态，放行；
 *  2. PLAN 模式 → 只读 [PermissionDecision.AllowDefault]，
 *     非只读 [PermissionDecision.Deny]（中文原因）——规则不可越级，
 *     保住规划阶段零副作用的硬承诺；
 *  3. 规则按序首匹配 → ALLOW 映射 [PermissionDecision.AllowExplicit]、
 *     ASK 映射 [PermissionDecision.Ask]、DENY 映射 [PermissionDecision.Deny]；
 *  4. ACCEPT_EDITS 模式默认 → 只读 AllowExplicit；编辑写类
 *     （见 [EDIT_LIKE_PREFIXES]）AllowExplicit；技能/MCP 常规能力
 *     （见 [ROUTINE_CAPABILITY_PREFIXES]，2026-10 用户反馈：skill 与
 *     mcp 全部不再确认）AllowExplicit；其余 Ask；
 *  5. DEFAULT 模式默认 → 基础工具自动放行
 *     [PermissionDecision.AllowDefault]：只读、普通编辑写、以及
 *     「破坏但可幂等重放」的工具（整文件覆写类，见下）都不再打扰；
 *     仅**真正不可逆的删除类**（destructive 且非幂等——delete /
 *     uninstall / force-stop 族）与敏感动作（sensitiveAction）
 *     才 Ask。用户反馈「每个工具都要确认太烦」——基础工具的
 *     静默放行 + 高危单点拦截才是默认该有的手感。
 *
 *     「删除类」判定口径 = destructiveHint && !idempotentHint：
 *     一次性不可逆动作（删完即没了，重放只会再删一次空气）；
 *     而 Write 等覆写写虽然 destructiveHint=true，但幂等
 *     （重放收敛），可由再次写入恢复，不算删除类。
 */
/**
 * 一次待决工具调用的上下文快照。
 *
 * @param toolId 工具 id（规则匹配的键）。
 * @param readOnlyHint 工具注解：是否只读（不改环境）——来自
 *   [ToolMetadata][com.apex.agent.core.tools.ToolMetadata] 的
 *   [annotations][com.apex.agent.core.tools.ToolMetadata.annotations].readOnlyHint。
 * @param destructiveHint 工具注解：成功时效果是否破坏性 / 不可逆。
 * @param sensitiveAction 工具注解：是否需要用户逐次明示同意的敏感动作
 *   （如读取通知）——敏感但未必破坏性，同样值得一次询问。
 * @param idempotentHint 工具注解：同一调用重复执行环境状态不变。
 *   与 [destructiveHint] 组合出「删除类」判定（破坏且非幂等 = 真不可逆）
 *   ——整文件覆写类工具（Write 等）虽破坏但幂等，不算删除类。
 */
data class PermissionContext(
    val toolId: String,
    val readOnlyHint: Boolean,
    val destructiveHint: Boolean,
    val sensitiveAction: Boolean,
    val idempotentHint: Boolean = false
)

/**
 * 决策引擎输出的四态决策。
 *
 * 「显式」与「默认」放行的区分是组合器的编排依据：
 *  - [AllowExplicit]：规则或模式**显式**承诺放行——用户意志已表达
 *    （点过规则、选过模式、答过授权），可跳过后续风险门，避免双弹窗；
 *  - [AllowDefault]：模式**默认**放行（DEFAULT / PLAN 下的只读工具）——
 *    没有任何用户意志参与，仍应交给后续门（如 RiskAwareToolGate）
 *    继续把关（只读但高风险的工具，如读取通知，仍会被问一次）；
 *  - [Deny]：拒绝，[reason] 面向模型（中文，含可执行指引）；
 *  - [Ask]：需要用户确认——由门层弹授权对话框（含会话记忆）。
 */
sealed class PermissionDecision {
    object AllowExplicit : PermissionDecision() {
        override fun toString(): String = "AllowExplicit"
    }

    object AllowDefault : PermissionDecision() {
        override fun toString(): String = "AllowDefault"
    }

    data class Deny(val reason: String) : PermissionDecision()

    object Ask : PermissionDecision() {
        override fun toString(): String = "Ask"
    }
}

/**
 * 纯函数决策引擎（无状态，线程安全）。
 */
object PermissionDecider {

    /**
     * 编辑写类工具的 id 前缀集合（公开常量：ACCEPT_EDITS 模式的判定依据，
     * 也是设置 UI 提示文案与单测断言的单一事实源）。
     *
     * 覆盖 Coding 模式的 code_ 写工具族与 Agent 模式的传统文件写工具族：
     * CodeEdit / CodeWrite / CodeGitCommit / CodeGitBranch /
     * Write / Edit / DeleteFile / CopyMoveFile。
     * 注意 CodeGitStatus / CodeGitDiff / CodeGitLog 等只读 git 工具
     * 不在列——它们靠 readOnlyHint 在判定顺序的第一层就被放行。
     */
    val EDIT_LIKE_PREFIXES: List<String> = listOf(
        "CodeEdit",
        "CodeWrite",
        "CodeGitCommit",
        "CodeGitBranch",
        "Write",
        "Edit",
        "DeleteFile",
        "CopyMoveFile"
    )

    /**
     * 判断工具 id 是否属于编辑写类（前缀匹配 [EDIT_LIKE_PREFIXES] 任一）。
     */
    fun isEditLike(toolId: String): Boolean =
        EDIT_LIKE_PREFIXES.any { toolId.startsWith(it) }

    /**
     * 技能 / MCP 常规能力工具的 id 前缀（2026-10 用户反馈：skill 与 mcp
     * 全部不再确认使用）。
     *
     * 使用技能与 MCP 能力是 Agent 的常规动作（技能执行 / MCP 调用），
     * ACCEPT_EDITS 下与编辑写类同口径自动放行（DEFAULT 下 mutating 非
     * 删除类本就默认放行 + RiskAwareToolGate 确认面收敛后静默）。
     * 规则仍可越级收紧（ASK/DENY 首匹配优先于模式默认）；PLAN 模式的
     * 只读承诺不豁免（非只读照旧拒绝）。
     *
     * ⚠️ 前缀双态并存：技能/MCP 工具 id 已 PascalCase 化（SkillSearch /
     * SkillInstall…），MCP 一等工具保持 mcp__ 前缀——snake_case 旧前缀
     * 一并保留（动态注册的旧式 id / 外部宿主兼容），命中任一即视为常规能力。
     */
    val ROUTINE_CAPABILITY_PREFIXES: List<String> = listOf("skill_", "Skill", "mcp_", "mcp__")

    /** 判断工具 id 是否属于技能/MCP 常规能力族（前缀匹配任一）。 */
    fun isRoutineCapability(toolId: String): Boolean =
        ROUTINE_CAPABILITY_PREFIXES.any { toolId.startsWith(it) }

    /**
     * 删除类判定（DEFAULT 模式询问口径的单一事实源）：破坏且非幂等
     * ——delete / uninstall / force-stop / signal 族的一次性不可逆动作。
     * 幂等的覆写写（Write 等破坏但可重放恢复）不算删除类。
     */
    private fun isDeleteLike(ctx: PermissionContext): Boolean =
        ctx.destructiveHint && !ctx.idempotentHint

    /**
     * 决策主入口：模式 + 规则 + 上下文 → 四态决策。
     *
     * 求值顺序见文件头决策表；BYPASS 与 PLAN 在规则之前短路，
     * 规则在 ACCEPT_EDITS / DEFAULT 模式默认之前短路（首匹配优先）。
     */
    fun decide(
        mode: PermissionMode,
        rules: List<PermissionRule>,
        ctx: PermissionContext
    ): PermissionDecision = when (mode) {
        // 全自动：模式默认不打扰，但用户规则（DENY/ASK）与敏感动作保留
        PermissionMode.BYPASS -> decideBypass(rules, ctx)

        // 只读规划：非只读直接拒绝，规则不可越级
        PermissionMode.PLAN -> if (ctx.readOnlyHint) {
            PermissionDecision.AllowDefault
        } else {
            PermissionDecision.Deny(planDenyReason(ctx.toolId))
        }

        // 其余模式：先看规则（首匹配优先，可越级），再看模式默认
        else -> decideWithRules(mode, rules, ctx)
    }

    /**
     * BYPASS（全自动）分支：全放行是模式的显式承诺，但保留两条窄口径
     * 安全线（2026-10 修正：默认档全员 BYPASS 后，旧实现连这两条也一并
     * 吞掉，与模式文案「拒绝规则仍生效」的承诺相悖）：
     *
     * 1. 规则首匹配仍参与——DENY 照拒、ASK 照问。用户在全自动档手写的
     *    规则是唯一安全网（拒 SmsSend / 拒 mcp__*），静默失效等于欺骗；
     * 2. sensitiveAction 工具（付费 / 逐次明示同意类，如 SmsSend）保持
     *    Ask——全自动的承诺不含「替用户花钱」；显式 ALLOW 规则命中时
     *    视为用户已为该工具表态，放行（用户意志优先于保守缺省）。
     */
    private fun decideBypass(rules: List<PermissionRule>, ctx: PermissionContext): PermissionDecision {
        val matched = rules.firstOrNull { PermissionRuleMatcher.matches(it.pattern, ctx.toolId) }
        if (matched != null) {
            return when (matched.effect) {
                PermissionEffect.DENY ->
                    PermissionDecision.Deny(ruleDenyReason(matched, ctx.toolId))
                PermissionEffect.ASK -> PermissionDecision.Ask
                PermissionEffect.ALLOW -> PermissionDecision.AllowExplicit
            }
        }
        // 无规则命中：敏感动作（付费 / 逐次明示同意类）保持询问，
        // 其余全自动放行（模式的显式承诺）
        return if (ctx.sensitiveAction) {
            PermissionDecision.Ask
        } else {
            PermissionDecision.AllowExplicit
        }
    }

    /** 规则优先分支（BYPASS / PLAN 已在外层短路，不会进入本函数）。 */
    private fun decideWithRules(
        mode: PermissionMode,
        rules: List<PermissionRule>,
        ctx: PermissionContext
    ): PermissionDecision {
        val matched = rules.firstOrNull { PermissionRuleMatcher.matches(it.pattern, ctx.toolId) }
        if (matched != null) {
            return when (matched.effect) {
                PermissionEffect.ALLOW -> PermissionDecision.AllowExplicit
                PermissionEffect.ASK -> PermissionDecision.Ask
                PermissionEffect.DENY -> PermissionDecision.Deny(ruleDenyReason(matched, ctx.toolId))
            }
        }
        return when (mode) {
            PermissionMode.ACCEPT_EDITS -> when {
                // 只读工具：模式显式承诺放行
                ctx.readOnlyHint -> PermissionDecision.AllowExplicit
                // 编辑写类（CodeEdit / Write / git 提交…）：自动放行
                isEditLike(ctx.toolId) -> PermissionDecision.AllowExplicit
                // 技能/MCP 常规能力（skill_* / mcp_*）：使用即常规动作，不再确认
                isRoutineCapability(ctx.toolId) -> PermissionDecision.AllowExplicit
                // 其余写操作（shell、网络写…）：仍要问
                else -> PermissionDecision.Ask
            }

            // DEFAULT：基础工具自动放行（只读 / 普通编辑写 / 幂等覆写），
            // 仅删除类（破坏且非幂等）与敏感动作 Ask——用户意志默认
            // 信任 Agent 的常规操作，把确认预算留给真正危险的动作
            PermissionMode.DEFAULT -> when {
                ctx.readOnlyHint -> PermissionDecision.AllowDefault
                isDeleteLike(ctx) || ctx.sensitiveAction -> PermissionDecision.Ask
                else -> PermissionDecision.AllowDefault
            }

            // 理论不可达：BYPASS / PLAN 已在 decide 外层处理
            else -> PermissionDecision.Ask
        }
    }

    /** PLAN 模式拒绝非只读工具的面向模型文案。 */
    private fun planDenyReason(toolId: String): String =
        "PLAN 模式为只读规划模式，禁止执行会修改环境的工具（$toolId）。" +
            "请改用只读工具完成调研，或请用户切换权限模式（如 ACCEPT_EDITS）后再执行写操作"

    /** DENY 规则命中时的面向模型文案（指明是哪条规则，便于用户排查）。 */
    private fun ruleDenyReason(rule: PermissionRule, toolId: String): String =
        "工具 $toolId 命中了拒绝规则（模式：${rule.pattern}）。" +
            "请勿重试同一工具；如确需执行，请在「设置 → 工具权限」中调整该条规则"
}
