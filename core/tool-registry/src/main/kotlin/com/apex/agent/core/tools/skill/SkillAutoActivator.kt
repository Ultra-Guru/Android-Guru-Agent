package com.apex.agent.core.tools.skill

/**
 * 技能自动装备器 —— 用户消息与技能标签的字面命中 → 零成本预激活。
 *
 * ## 定位（三路激活中的「自动」路）
 *
 * 1. 模型路：`skill_activate(skill_id)`（LLM 读了技能目录后自主决定）；
 * 2. 用户路：斜杠指令 `/skill:<id>`（显式装备）；
 * 3. **自动路（本类）**：发送前扫描用户消息，命中已安装且启用技能的
 *    tags / id / name 关键词时直接激活——LLM 一次调用都不用花，
 *    方法论已就位。这是对 operit「工具包需要用户手动装」生态的差异化：
 *    全领域技能 + 零成本自动装备 = 用户无感知的能力路由。
 *
 * ## 命中规则（刻意保守）
 *
 * - 只认**字面子串**命中（不做分词 / 模糊 / 向量）：中文 tags 本就是
 *   高区分度短词（「菜谱」「穿搭」「简历」），子串命中误报率低；
 * - 关键词长度 ≥ 2（单字词如「做」误报率不可接受）；
 * - 每条消息最多装备 [maxActivations] 个（默认 2）：命中 5 个技能时
 *   全注入反而稀释——取命中词最长（区分度最高）的前两个；
 * - 技能必须处于 installed + enabled 状态（禁用的技能不自动装备）；
 * - 已激活的技能跳过（幂等，不重复占 FIFO 名额）。
 *
 * 纯 JVM、无 Android 依赖；由 app 层（AgentChatViewModel 发送路径）调用。
 */
class SkillAutoActivator(
    private val skillRegistry: SkillRegistry,
    private val activationStore: SkillActivationStore,
    /** 单条消息最多自动装备的技能数。 */
    private val maxActivations: Int = 2
) {

    /**
     * 扫描用户消息并自动装备命中技能。
     *
     * @param scope 发送方工位（"agent" / "coding"；null = 不限）。per-scope
     *   启停后自动装备同样按工位隔离：只在 Coding 市场开启的技能不会被
     *   Agent 屏消息误装备。就绪门禁：必填配置未填齐的技能不参与自动装备。
     * @return 本次新激活的技能 manifest 列表（FIFO 淘汰挤出者不计入；
     *   空列表 = 无命中或全部已激活）。调用方可用于日志 / UI 提示。
     */
    fun autoEquip(userText: String, scope: String? = null): List<SkillManifest> {
        val text = userText.trim()
        if (text.length < MIN_TEXT_LENGTH) return emptyList()

        // 候选打分：关键词命中一次记 (关键词长度)——越长越有区分度
        data class Candidate(val manifest: SkillManifest, val keywordLen: Int)

        val candidates = mutableListOf<Candidate>()
        for (installed in skillRegistry.getInstalled()) {
            if (!installed.enabled) continue
            // 工位隔离：技能生效集合不含发送方工位则不参与（见类 KDoc）
            if (scope != null && scope !in skillRegistry.effectiveScopesOf(installed.manifest.id)) continue
            // 就绪门禁：未配置好的技能不自动装备
            if (!skillRegistry.isConfigured(installed.manifest.id)) continue
            val manifest = installed.manifest
            if (activationStore.isActive(manifest.id)) continue
            val bestKeyword = keywordsOf(manifest)
                .filter { it.length >= MIN_KEYWORD_LENGTH && text.contains(it) }
                .maxByOrNull { it.length } ?: continue
            candidates.add(Candidate(manifest, bestKeyword.length))
        }
        if (candidates.isEmpty()) return emptyList()

        val picked = candidates
            .sortedWith(compareByDescending<Candidate> { it.keywordLen }.thenBy { it.manifest.id })
            .take(maxActivations)
            .map { it.manifest }

        val activatedIds = activationStore.activateAll(picked.map { it.id })
        if (activatedIds.isEmpty()) return emptyList()
        val activatedSet = activatedIds.toSet()
        return picked.filter { it.id in activatedSet }
    }

    /** 参与匹配的关键词：tags + id（连字符转空格的词段）+ name。 */
    private fun keywordsOf(manifest: SkillManifest): List<String> {
        val words = mutableListOf<String>()
        words.addAll(manifest.tags)
        // id 词段（travel-planner → travel / planner）：英文消息命中用
        words.addAll(manifest.id.split('-', '_').filter { it.length >= MIN_KEYWORD_LENGTH })
        if (manifest.name.length >= MIN_KEYWORD_LENGTH) words.add(manifest.name)
        return words
    }

    private companion object {
        /** 单字关键词误报率不可接受。 */
        private const val MIN_KEYWORD_LENGTH = 2
        /** 过短消息（纯「好」「行」）不扫描。 */
        private const val MIN_TEXT_LENGTH = 2
    }
}
