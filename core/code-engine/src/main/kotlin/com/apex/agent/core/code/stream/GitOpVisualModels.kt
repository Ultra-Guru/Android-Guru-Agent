package com.apex.agent.core.code.stream

import kotlinx.serialization.Serializable

/**
 * # Git Op Visual Models — 流水式输出的 Git 操作可视化数据模型
 *
 * 「生活中的 Git 不该是假的」：agent 调用 code_git_* 工具时，用户在胶囊
 * 时间轴上看到的应是一颗通用胶囊 + **结构化可视化卡**（分支徽章 / 增删行
 * 计数 / 提交哈希 / ahead-behind），而不是只有一行 stderr 风格的摘要。
 *
 * 数据流：`ToolCallComplete(code_git_*)` → [GitOpParser] 解析工具输出 →
 * [GitOpSummary] → `StreamEntry.GitOpEntry`（声明嵌在 CodeStreamModels.kt
 * 的 sealed 层次内）入轴 → UI `GitOpVisualCard` 渲染。解析全部防御式
 * （任何形状都吞得下，解析失败返回 null —— 通用胶囊仍在，可视化卡不出现
 * 而已，绝不向上抛）。
 *
 * @param op 操作类型（决定卡片的主体布局）。
 * @param headline 主行文案（≤40 字：分支名 / 提交主题 / 动作结果）。
 * @param branch 分支名（status/branch 可有）。
 * @param ahead / behind 与 upstream 的领先/落后（status 可有）。
 * @param staged / unstaged / untracked 分组计数（status 可有）。
 * @param conflicts 合并冲突数（status 可有，>0 时卡片着警示色）。
 * @param clean 工作区是否干净（status）。
 * @param commitHash / commitSubject 提交短哈希与主题（commit）。
 * @param remainingChanges 提交后剩余未提交变更数（commit）。
 * @param filesChanged / additions / deletions diff 统计（diff）。
 * @param branchAction / branchName 分支动作与对象（branch）。
 * @param commitCount 历史条数（log）。
 * @param note 附加说明行（降级提示等，可空）。
 */
@Serializable
data class GitOpSummary(
    val op: GitOpType,
    val headline: String,
    val branch: String? = null,
    val ahead: Int = 0,
    val behind: Int = 0,
    val staged: Int = 0,
    val unstaged: Int = 0,
    val untracked: Int = 0,
    val conflicts: Int = 0,
    val clean: Boolean = false,
    val commitHash: String? = null,
    val commitSubject: String? = null,
    val remainingChanges: Int? = null,
    val filesChanged: Int? = null,
    val additions: Int? = null,
    val deletions: Int? = null,
    val branchAction: String? = null,
    val branchName: String? = null,
    val commitCount: Int? = null,
    val note: String? = null
) {
    /** 可视化卡的操作类型。 */
    enum class GitOpType { STATUS, COMMIT, DIFF, BRANCH, LOG }
}

