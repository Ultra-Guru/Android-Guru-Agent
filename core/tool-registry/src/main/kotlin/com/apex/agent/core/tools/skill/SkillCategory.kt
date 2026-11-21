package com.apex.agent.core.tools.skill

/**
 * #206 技能分类体系 —— 24 域单一事实源。
 *
 * ## 为什么需要
 * 旧世界里 `SkillManifest.category` 是自由字符串，历史取值混用了三套词表：
 * ToolCategory 枚举名（AGENT/PRODUCTIVITY/UTILITY —— 那是**工具**的分类）、
 * TemplateCategory（PRODUCTIVITY）与市场硬编码 chip 列表（10 个名字里 6 个
 * 是死过滤器）。三方互相错位，用户在市场页点 SHELL 永远筛不出任何技能。
 *
 * ## 现在的约定
 * - [SkillManifest.category] 的合法取值 = [SkillCategory.key]（小写连字符）；
 * - 旧值（AGENT/PRODUCTIVITY/UTILITY）经 [of] 返回 null → 归入「未分类」兜底，
 *   不再互相猜；安装源（ClawHub/ModelScope）落库时以 [sanitize] 归一；
 * - 市场 chips、已安装分组、目录摘要的域标签全部从本枚举派生 —— 不再有
 *   任何硬编码分类列表。
 */
enum class SkillCategory(
    /** manifest.category 的合法取值（小写，单词或连字符）。 */
    val key: String,
    /** 中文标签（数据缺省；UI 正式展示用 app 层 string 资源）。 */
    val zhLabel: String,
    /** 英文标签（数据缺省；同上）。 */
    val enLabel: String,
    /** 展示顺序（分组渲染稳定排序）。 */
    val order: Int
) {
    CAREER("career", "职场进阶", "Career", 10),
    KNOWLEDGE("knowledge", "百科知识", "Knowledge", 20),
    LIFESTYLE("lifestyle", "生活技能", "Lifestyle", 30),
    EMOTIONAL("emotional", "情感心理", "Emotional", 40),
    SOCIAL("social", "社交礼仪", "Social", 50),
    DATA("data", "数据分析", "Data", 60),
    BUSINESS("business", "商业思维", "Business", 70),
    FINANCE("finance", "财务理财", "Finance", 80),
    TECH("tech", "数码科技", "Tech", 90),
    LANGUAGE("language", "语言学习", "Language", 100),
    HEALTH("health", "健康养生", "Health", 110),
    FITNESS("fitness", "运动健身", "Fitness", 120),
    EDUCATION("education", "学习方法", "Education", 130),
    PARENTING("parenting", "家庭育儿", "Parenting", 140),
    HOME("home", "宠物家居", "Home & Pets", 150),
    TRAVEL("travel", "旅行户外", "Travel", 160),
    CREATIVE("creative", "创意写作", "Creative", 170),
    WRITING("writing", "专业写作", "Writing", 180),
    ENTERTAINMENT("entertainment", "娱乐休闲", "Entertainment", 190),
    PRODUCTIVITY("productivity", "效率工具", "Productivity", 200),
    COMMUNICATION("communication", "沟通表达", "Communication", 210),
    SAFETY("safety", "安全应急", "Safety", 220),
    DIGITAL("digital", "数字生活", "Digital", 230),
    CODING("coding", "编程开发", "Coding", 240);

    companion object {
        /** 全部域，按展示顺序。 */
        fun inDisplayOrder(): List<SkillCategory> = entries.sortedBy { it.order }

        /**
         * 自由字符串 → 域。未知值（含旧 ToolCategory 名 AGENT/PRODUCTIVITY/UTILITY
         * 与空值）返回 null —— 调用方归入「未分类」，不猜。
         */
        fun of(key: String?): SkillCategory? =
            key?.trim()?.lowercase()?.let { k -> entries.firstOrNull { it.key == k } }

        /**
         * 安装源归一：合法 key 原样通过；旧词表/未知值归 null（未分类）。
         * 远程安装的 manifest category 不可信，落库前过这道闸。
         */
        fun sanitize(key: String?): String? = of(key)?.key

        /** 数据标签（无 string 资源环境的兜底展示，如日志/调试页）。 */
        fun labelFor(category: SkillCategory?, langZh: Boolean): String =
            category?.let { if (langZh) it.zhLabel else it.enLabel } ?: "未分类"
    }
}
