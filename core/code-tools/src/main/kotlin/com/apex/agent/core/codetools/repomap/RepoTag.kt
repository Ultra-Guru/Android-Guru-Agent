package com.apex.agent.core.codetools.repomap

/**
 * # Repo-Map 标签模型（Aider repomap 的 Kotlin 移植，纯 JVM）
 *
 * Aider 用 tree-sitter 查询抽取两类标签：
 * - `name.definition.*` → def（定义点，带行号，参与骨架渲染）
 * - `name.reference.*` → ref（引用点，只计数，参与图的边权）
 *
 * 本移植用正则语言轮廓（[TagExtractor]）替代 tree-sitter（Android JVM 无
 * 可用的原生绑定），语义对齐：def 带行号、ref 只计出现次数。
 */
enum class RepoTagKind { DEF, REF }

/**
 * 单个标签：[identifier] 在 [relPath]（工作区相对路径）中的出现。
 *
 * @param line 1 基行号；DEF 必填（骨架渲染的「关注行」），REF 恒 -1
 *   （Aider 的 pygments 兜底路径同样不提供 ref 行号，排序只用得到 def 行）。
 */
data class RepoTag(
    val identifier: String,
    val kind: RepoTagKind,
    val relPath: String,
    val line: Int
)

/**
 * 一个文件抽取出的全部标签（def + 引用计数）。
 *
 * [referenceCounts] 是 ident → 该文件内出现次数 —— 等价于 Aider 的
 * `references[ident].append(rel_fname)` 逐次追加后 `Counter()` 的结果，
 * 但避免了大仓库下的 list 爆炸。
 */
class FileTags(
    val relPath: String,
    val definitions: List<RepoTag>,
    val referenceCounts: Map<String, Int>
) {
    fun isEmpty(): Boolean = definitions.isEmpty() && referenceCounts.isEmpty()
}
