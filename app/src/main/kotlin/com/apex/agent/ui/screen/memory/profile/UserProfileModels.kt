package com.apex.agent.ui.screen.memory.profile

import kotlinx.serialization.Serializable

// ─────────────────────────────────────────────────────────────────────────────
// 用户画像 —— 数据模型与纯函数（零 Android / Compose 依赖，JVM 可直测）
//
// 定位：区别于聊天事实账本（ChatFact，对话内容层面的自动沉淀），画像
// 回答「这个用户是谁」——跨会话长期稳定的五维度结构化特征（技术栈 /
// 编码风格 / 偏好 / 关注领域 / 工作习惯），由两条途径写入：
//  1. 「从编码对话提炼」—— ProfileDistiller 扫描编码会话存档，LLM 结构
//     化蒸馏（更新优先 + 写入门禁）；
//  2. 手动添加 / 编辑（记忆页画像分区）。
//
// 消费：CodeViewModel 发送路径按置信度取 Top-N 注入编码会话上下文
//（additionalSystemContext 的 sessionExtras 通道，与角色经验卡同位）。
// ─────────────────────────────────────────────────────────────────────────────

/** 画像维度（五维固定序 —— 分组展示与注入都以它为准）。 */
enum class ProfileAspect(val key: String) {
    TECH_STACK("tech-stack"),
    CODING_STYLE("coding-style"),
    PREFERENCES("preferences"),
    DOMAINS("domains"),
    HABITS("habits");

    companion object {
        /** key 容错解析（大小写 / 空白 / 连字符变体归一；未知 → null）。 */
        fun fromKey(raw: String?): ProfileAspect? {
            val normalized = raw?.trim()?.lowercase()?.replace('_', '-') ?: return null
            return entries.firstOrNull { it.key == normalized }
        }
    }
}

/**
 * 单条画像条目。
 *
 * @param content 第三人称陈述，建议 ≤ 40 字（超长由蒸馏门禁截断）
 * @param confidence 置信度 0.3 ~ 1.0（蒸馏钳制；手动录入默认 1.0）
 * @param evidence 来源摘要（蒸馏时来自对话，≤ 20 字；手动可为空）
 * @param source distilled（AI 提炼）| manual（用户手录）
 */
@Serializable
data class UserProfileItem(
    val id: String,
    val aspect: String,
    val content: String,
    val confidence: Float,
    val evidence: String? = null,
    val source: String = SOURCE_DISTILLED,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
) {
    companion object {
        const val SOURCE_DISTILLED = "distilled"
        const val SOURCE_MANUAL = "manual"
    }
}

/** 画像分区的一个展示条目（一维度一卡，条目按置信度降序）。 */
data class ProfileAspectEntry(
    val aspect: ProfileAspect,
    val items: List<UserProfileItem>,
)

// ═══ 蒸馏协议（LLM 输出形状 —— 防御式解析的契约层）════════════════════════

/** 单个提炼动作：new（全新条目）| update（演进既有条目，old 逐字复制）。 */
@Serializable
data class DistillAction(
    val op: String = "",
    val aspect: String = "",
    val content: String = "",
    val confidence: Float = 0.5f,
    val evidence: String? = null,
    val old: String? = null,
)

/** 蒸馏响应载荷：skip（无信号）或 actions 列表。 */
@Serializable
data class DistillPayload(
    val skip: Boolean = false,
    val actions: List<DistillAction> = emptyList(),
)

/** 蒸馏落账报告（VM 转提示文案；测试断言口径）。 */
data class DistillReport(
    val added: Int = 0,
    val updated: Int = 0,
    val skipped: Int = 0,
    /** LLM 无有效输出（skip / 解析失败 / 空响应）。 */
    val noSignal: Boolean = false,
)

// ═══ 展示与注入纯函数 ═══════════════════════════════════════════════════════

/**
 * 全量条目 → 分维度展示条目（固定五维序，空维度不占位 —— 空态由分区
 * 整体文案表达，与 toChatMemoryEntries 同款口径）。
 */
fun List<UserProfileItem>.toProfileAspectEntries(): List<ProfileAspectEntry> =
    ProfileAspect.entries.mapNotNull { aspect ->
        filter { ProfileAspect.fromKey(it.aspect) == aspect }
            .sortedByDescending { it.confidence }
            .takeIf { it.isNotEmpty() }
            ?.let { ProfileAspectEntry(aspect, it) }
    }

/** 注入上限（编码会话每轮携带的画像条数 —— 与角色经验卡 top-3 同量级）。 */
const val PROFILE_INJECT_LIMIT = 8

/** 注入取条：全量按置信度降序取 Top [PROFILE_INJECT_LIMIT]。 */
fun List<UserProfileItem>.topForInjection(): List<UserProfileItem> =
    sortedByDescending { it.confidence }.take(PROFILE_INJECT_LIMIT)

/** 维度中文标签（注入段与蒸馏提示词共用 —— 面向 LLM，不走资源）。 */
fun ProfileAspect.zhLabel(): String = when (this) {
    ProfileAspect.TECH_STACK -> "技术栈"
    ProfileAspect.CODING_STYLE -> "编码风格"
    ProfileAspect.PREFERENCES -> "偏好"
    ProfileAspect.DOMAINS -> "关注领域"
    ProfileAspect.HABITS -> "工作习惯"
}

/**
 * 条目 → 编码会话注入段（additionalSystemContext 通道）。
 *
 * 空列表返回 null（调用方据此跳过 —— 没有画像时不注入空段）。
 */
fun List<UserProfileItem>.toSystemContextSection(): String? {
    if (isEmpty()) return null
    val lines = map { item ->
        val aspect = ProfileAspect.fromKey(item.aspect)
        val label = aspect?.zhLabel() ?: item.aspect
        "- [$label] ${item.content}（置信度 ${(item.confidence * 100).toInt()}%）"
    }
    return buildString {
        appendLine("## 用户画像（跨会话稳定特征，按置信度排序）")
        appendLine("以下是对该用户的长期画像，用于个性化编码辅助：默认技术栈、示例语言、讲解深度向画像靠拢；与当前对话冲突时以对话为准。")
        lines.forEach { appendLine(it) }
    }.trimEnd()
}

// ═══ LLM 输出容错解析 ══════════════════════════════════════════════════════

/**
 * 从 LLM 原始输出提取 JSON 对象文本（剥 Markdown 围栏 / 前后废话 /
 * 找首个花括号平衡块）。找不到平衡块返回 null —— 调用方按无信号处理。
 */
fun extractJsonObject(raw: String): String? {
    var text = raw.trim()
    // 剥代码围栏（json 标记或裸围栏）。
    if (text.startsWith("```")) {
        text = text.removePrefix("```json").removePrefix("```JSON").removePrefix("```")
        val fenceEnd = text.lastIndexOf("```")
        if (fenceEnd >= 0) text = text.substring(0, fenceEnd)
        text = text.trim()
    }
    val start = text.indexOf('{')
    if (start < 0) return null
    var depth = 0
    var inString = false
    var escaped = false
    for (i in start until text.length) {
        val c = text[i]
        when {
            escaped -> escaped = false
            inString -> when (c) {
                '\\' -> escaped = true
                '"' -> inString = false
            }
            c == '"' -> inString = true
            c == '{' -> depth++
            c == '}' -> {
                depth--
                if (depth == 0) return text.substring(start, i + 1)
            }
        }
    }
    return null
}
