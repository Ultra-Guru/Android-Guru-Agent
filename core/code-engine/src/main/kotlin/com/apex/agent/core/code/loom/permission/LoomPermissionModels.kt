package com.apex.agent.core.code.loom.permission

/**
 * # Loom Permission Models — 织造线权限数据模型
 *
 * 权限三件套：**模态**（会话基调）× **效应**（工具读写画像）× **规则**
 * （显式三元组）。决策由 [LoomPermissionGovernor] 依「会话批准记忆 →
 * 显式规则 → 模态兜底」的层级给出。
 *
 * ## 模态语义
 *
 * | 模态 | 读效应 | 工作区内写 | 越界写 / 敏感 | 对应宿主设置 |
 * |---|---|---|---|---|
 * | [LoomAccessMode.OBSERVE] | 放行 | 询问 | 询问 | PLAN / 巡视 |
 * | [LoomAccessMode.FABRIC] | 放行 | 放行 | 询问 | DEFAULT（默认） |
 * | [LoomAccessMode.UNBOUND] | 放行 | 放行 | 放行（敏感仍问） | BYPASS / 全自动 |
 *
 * ## 规则模式串
 *
 * - 工具 id 模式：`CodeRead`、`CodeGit*`、`mcp__github__*`（不含空格）；
 * - 命令模式（ShellExecute 专用）：含空格即命令模式（`git push*`、
 *   `rm -rf *`）——匹配命令行前缀/glob。
 */
/** 会话级访问模态。 */
enum class LoomAccessMode {
    /** 只读巡视：一切写效应都要问。 */
    OBSERVE,

    /** 布面内自由织：工作区内读写放行，越界写与敏感操作要问。 */
    FABRIC,

    /** 无界放行：仅敏感操作仍问。 */
    UNBOUND
}

/** 工具效应（读写画像）。 */
enum class LoomToolEffect {
    /** 只读（读文件 / 搜索 / 查询 / 观测）。 */
    READ,

    /** 写效应（改文件 / 执行命令 / 提交推送 / 改状态）。 */
    WRITE
}

/** 规则效应三元组。 */
enum class LoomRuleEffect { ALLOW, ASK, DENY }

/** 显式规则（模式串见文件级 KDoc）。 */
data class LoomPermissionRule(
    val pattern: String,
    val effect: LoomRuleEffect
)

/** 权限决策。 */
sealed interface LoomPermissionDecision {
    /** 放行。 */
    data object Allow : LoomPermissionDecision

    /** 询问（附人话理由——问答弹窗一行）。 */
    data class Ask(val reason: String) : LoomPermissionDecision

    /** 拒绝（附理由——织入 warn 回执）。 */
    data class Deny(val reason: String) : LoomPermissionDecision
}

/** 设置层权限快照（引擎每轮任务前拉取——改设置即时生效）。 */
data class LoomPermissionSnapshot(
    val mode: LoomAccessMode = LoomAccessMode.FABRIC,
    val rules: List<LoomPermissionRule> = emptyList(),
    val commandRules: List<LoomPermissionRule> = emptyList()
)

/** 权限快照源端口（宿主接线：SettingsRepository → 引擎）。 */
fun interface LoomPermissionSource {
    fun snapshot(): LoomPermissionSnapshot

    companion object {
        /** 缺省源（纯模态兜底——子线程/测试用）。 */
        val NONE: LoomPermissionSource = LoomPermissionSource { LoomPermissionSnapshot() }
    }
}

/**
 * 一次待判定的调用画像（治理器输入——由引擎从 [com.apex.agent.core.code.loom.LoomInvocation]
 * 派生：路径抽取 + 命令行抽取）。
 */
data class LoomCallProfile(
    /** 工具 id。 */
    val toolId: String,
    /** 命令行（ShellExecute 类；无则 null——命令规则匹配用）。 */
    val commandLine: String? = null,
    /** 涉及路径（文件类参数；空 = 无路径语义）。 */
    val paths: List<String> = emptyList()
)
