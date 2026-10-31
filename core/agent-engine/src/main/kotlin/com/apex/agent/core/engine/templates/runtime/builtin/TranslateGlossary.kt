package com.apex.agent.core.engine.templates.runtime.builtin

/**
 * ═══ 内置模板行为 · 专业翻译 —— 术语表与语言工具（PR 7）═══
 *
 * 翻译行为的确定性分析层：
 * - **领域术语表**：五个领域（技术/医学/法律/金融/通用）的中英对照
 *   真实术语，命中即给出建议译法 —— 术语翻译从查表开始，不是靠
 *   大模型回忆；
 * - **书写系统探测**：CJK/拉丁/西里尔/阿拉伯/假名字符普查 + 混合度；
 * - **源语言猜测**：脚本判定 + 常见功能词启发（德/法/日/韩/西/俄）；
 * - **目标语言归一**：中英文别名 → 规范码；
 * - **术语一致性检查**：同一源术语在译文里出现多种译法 → 违规。
 *
 * 纪律：全部纯函数；术语表按领域分组初始化（object 惰性加载一次）。
 */

/** 术语表条目（术语 + 各目标语言译法）。 */
data class GlossaryEntry(
    /** 源术语（中文或英文，表内主键）。 */
    val term: String,
    /** 译法映射（语言码 → 译法；至少含 en 或 zh 之一）。 */
    val translations: Map<String, String>,
    /** 注解（使用语境提示，喂提示词）。 */
    val note: String = ""
) {
    /** 目标语言译法（无则 null）。 */
    fun translationFor(targetLanguage: String): String? = translations[targetLanguage]

    /** 反向查询键（英→中方向用中文键）。 */
    val chineseTerm: String? get() = translations["zh"]

    /** 英文形态（术语本身或 en 译法）。 */
    val englishTerm: String get() = translations["en"] ?: term
}

/** 领域枚举（模板 domain 变量归一后的取值）。 */
enum class GlossaryDomain(val label: String, val aliases: List<String>) {
    TECHNICAL("技术", listOf("tech", "it", "软件", "计算机", "编程")),
    MEDICAL("医学", listOf("医疗", "医", "health", "medicine", "药学")),
    LEGAL("法律", listOf("法务", "law", "legal", "合同法")),
    FINANCE("金融", listOf("财务", "finance", "证券", "投资")),
    GENERAL("通用", emptyList());

    companion object {
        /** 归一（未知回落 GENERAL）。 */
        fun normalize(input: String): GlossaryDomain {
            val lowered = input.trim().lowercase()
            return entries.firstOrNull { domain ->
                domain.label == input.trim() || lowered in domain.aliases
            } ?: GENERAL
        }
    }
}

/** 术语命中（位置计数 + 建议译法）。 */
data class GlossaryHit(
    val entry: GlossaryEntry,
    /** 出现次数。 */
    val count: Int,
    /** 建议译法（按目标语言方向）。 */
    val suggested: String
)

/** 一致性违规（同一术语多种译法）。 */
data class ConsistencyIssue(
    val sourceTerm: String,
    /** 译文中出现的不同译法。 */
    val variants: List<String>
)

/**
 * 领域术语表 + 语言工具（纯函数集合）。
 */
object TranslateGlossary {

    // ═══ 术语表 ═══

    val TECHNICAL_TERMS: List<GlossaryEntry> = listOf(
        GlossaryEntry("协程", mapOf("en" to "coroutine"), "并发原语，非线程"),
        GlossaryEntry("幂等", mapOf("en" to "idempotent"), "重复执行结果不变"),
        GlossaryEntry("回滚", mapOf("en" to "rollback"), "事务/发布回退"),
        GlossaryEntry("缓存穿透", mapOf("en" to "cache penetration"), "查不存在的键绕过缓存"),
        GlossaryEntry("缓存雪崩", mapOf("en" to "cache avalanche"), "缓存集体失效打崩数据库"),
        GlossaryEntry("熔断", mapOf("en" to "circuit breaking"), "故障隔离模式"),
        GlossaryEntry("降级", mapOf("en" to "degradation"), "服务降级"),
        GlossaryEntry("灰度发布", mapOf("en" to "canary release"), "小流量渐进发布"),
        GlossaryEntry("负载均衡", mapOf("en" to "load balancing"), ""),
        GlossaryEntry("反向代理", mapOf("en" to "reverse proxy"), ""),
        GlossaryEntry("微服务", mapOf("en" to "microservices"), ""),
        GlossaryEntry("容器编排", mapOf("en" to "container orchestration"), ""),
        GlossaryEntry("服务网格", mapOf("en" to "service mesh"), ""),
        GlossaryEntry("观察性", mapOf("en" to "observability"), "指标/日志/链路三位一体"),
        GlossaryEntry("链路追踪", mapOf("en" to "distributed tracing"), ""),
        GlossaryEntry("不可变基础设施", mapOf("en" to "immutable infrastructure"), ""),
        GlossaryEntry("声明式配置", mapOf("en" to "declarative configuration"), ""),
        GlossaryEntry("类型推断", mapOf("en" to "type inference"), ""),
        GlossaryEntry("空安全", mapOf("en" to "null safety"), ""),
        GlossaryEntry("结构化并发", mapOf("en" to "structured concurrency"), ""),
        GlossaryEntry("竞态条件", mapOf("en" to "race condition"), ""),
        GlossaryEntry("死锁", mapOf("en" to "deadlock"), ""),
        GlossaryEntry("乐观锁", mapOf("en" to "optimistic locking"), ""),
        GlossaryEntry("悲观锁", mapOf("en" to "pessimistic locking"), ""),
        GlossaryEntry("脏读", mapOf("en" to "dirty read"), "事务隔离术语"),
        GlossaryEntry("幻读", mapOf("en" to "phantom read"), "事务隔离术语"),
        GlossaryEntry("热点路径", mapOf("en" to "hot path"), "性能关键代码路径"),
        GlossaryEntry("内存泄漏", mapOf("en" to "memory leak"), ""),
        GlossaryEntry("句柄", mapOf("en" to "handle"), "系统资源引用"),
        GlossaryEntry("中间件", mapOf("en" to "middleware"), ""),
        GlossaryEntry("接口契约", mapOf("en" to "interface contract"), ""),
        GlossaryEntry("向后兼容", mapOf("en" to "backward compatibility"), ""),
        GlossaryEntry("破坏性变更", mapOf("en" to "breaking change"), ""),
        GlossaryEntry("增量更新", mapOf("en" to "incremental update"), ""),
        GlossaryEntry("全量同步", mapOf("en" to "full synchronization"), ""),
        GlossaryEntry("边缘计算", mapOf("en" to "edge computing"), ""),
        GlossaryEntry("冷启动", mapOf("en" to "cold start"), ""),
        GlossaryEntry("预热", mapOf("en" to "warm-up"), ""),
        GlossaryEntry("心跳", mapOf("en" to "heartbeat"), "存活探测"),
        GlossaryEntry("断点续传", mapOf("en" to "resumable transfer"), ""),
        GlossaryEntry("编解码器", mapOf("en" to "codec"), ""),
        GlossaryEntry("序列化", mapOf("en" to "serialization"), ""),
        GlossaryEntry("反序列化", mapOf("en" to "deserialization"), ""),
        GlossaryEntry("软删除", mapOf("en" to "soft delete"), ""),
        GlossaryEntry("脏标记", mapOf("en" to "dirty flag"), "")
    )

    val MEDICAL_TERMS: List<GlossaryEntry> = listOf(
        GlossaryEntry("高血压", mapOf("en" to "hypertension"), ""),
        GlossaryEntry("低血压", mapOf("en" to "hypotension"), ""),
        GlossaryEntry("糖尿病", mapOf("en" to "diabetes mellitus"), ""),
        GlossaryEntry("预后", mapOf("en" to "prognosis"), ""),
        GlossaryEntry("禁忌症", mapOf("en" to "contraindication"), ""),
        GlossaryEntry("适应症", mapOf("en" to "indication"), ""),
        GlossaryEntry("不良反应", mapOf("en" to "adverse reaction"), ""),
        GlossaryEntry("副作用", mapOf("en" to "side effect"), ""),
        GlossaryEntry("给药途径", mapOf("en" to "route of administration"), ""),
        GlossaryEntry("剂量", mapOf("en" to "dosage"), ""),
        GlossaryEntry("疗程", mapOf("en" to "course of treatment"), ""),
        GlossaryEntry("临床试验", mapOf("en" to "clinical trial"), ""),
        GlossaryEntry("双盲", mapOf("en" to "double-blind"), "试验设计术语"),
        GlossaryEntry("安慰剂", mapOf("en" to "placebo"), ""),
        GlossaryEntry("随机对照试验", mapOf("en" to "randomized controlled trial"), "RCT"),
        GlossaryEntry("并发症", mapOf("en" to "complication"), ""),
        GlossaryEntry("鉴别诊断", mapOf("en" to "differential diagnosis"), ""),
        GlossaryEntry("影像学", mapOf("en" to "imaging"), ""),
        GlossaryEntry("病理切片", mapOf("en" to "pathology slide"), ""),
        GlossaryEntry("抗生素", mapOf("en" to "antibiotic"), ""),
        GlossaryEntry("抗病毒", mapOf("en" to "antiviral"), ""),
        GlossaryEntry("抗凝", mapOf("en" to "anticoagulation"), ""),
        GlossaryEntry("炎症", mapOf("en" to "inflammation"), ""),
        GlossaryEntry("慢性病", mapOf("en" to "chronic disease"), ""),
        GlossaryEntry("急性发作", mapOf("en" to "acute episode"), ""),
        GlossaryEntry("复查", mapOf("en" to "follow-up examination"), ""),
        GlossaryEntry("处方", mapOf("en" to "prescription"), ""),
        GlossaryEntry("依从性", mapOf("en" to "compliance"), "服药依从"),
        GlossaryEntry("缓解", mapOf("en" to "remission"), ""),
        GlossaryEntry("复发", mapOf("en" to "relapse"), ""),
        GlossaryEntry("转移", mapOf("en" to "metastasis"), "肿瘤语境"),
        GlossaryEntry("活检", mapOf("en" to "biopsy"), ""),
        GlossaryEntry("麻醉", mapOf("en" to "anesthesia"), ""),
        GlossaryEntry("缝合", mapOf("en" to "suturing"), ""),
        GlossaryEntry("置换术", mapOf("en" to "replacement surgery"), "关节置换"),
        GlossaryEntry("康复训练", mapOf("en" to "rehabilitation training"), ""),
        GlossaryEntry("血常规", mapOf("en" to "complete blood count"), "CBC"),
        GlossaryEntry("空腹血糖", mapOf("en" to "fasting blood glucose"), ""),
        GlossaryEntry("糖化血红蛋白", mapOf("en" to "glycated hemoglobin"), "HbA1c"),
        GlossaryEntry("核磁共振", mapOf("en" to "magnetic resonance imaging"), "MRI")
    )

    val LEGAL_TERMS: List<GlossaryEntry> = listOf(
        GlossaryEntry("不可抗力", mapOf("en" to "force majeure"), ""),
        GlossaryEntry("违约", mapOf("en" to "breach of contract"), ""),
        GlossaryEntry("违约金", mapOf("en" to "liquidated damages"), ""),
        GlossaryEntry("缔约过失", mapOf("en" to "culpa in contrahendo"), "缔约阶段过失责任"),
        GlossaryEntry("定金", mapOf("en" to "deposit"), "与订金法律含义不同"),
        GlossaryEntry("订金", mapOf("en" to "advance payment"), "预付款性质"),
        GlossaryEntry("违约责任", mapOf("en" to "liability for breach"), ""),
        GlossaryEntry("担保", mapOf("en" to "guarantee"), ""),
        GlossaryEntry("连带责任", mapOf("en" to "joint and several liability"), ""),
        GlossaryEntry("排他许可", mapOf("en" to "exclusive license"), ""),
        GlossaryEntry("普通许可", mapOf("en" to "non-exclusive license"), ""),
        GlossaryEntry("知识产权", mapOf("en" to "intellectual property"), ""),
        GlossaryEntry("竞业禁止", mapOf("en" to "non-compete"), ""),
        GlossaryEntry("保密义务", mapOf("en" to "confidentiality obligation"), ""),
        GlossaryEntry("知情同意", mapOf("en" to "informed consent"), ""),
        GlossaryEntry("举证责任", mapOf("en" to "burden of proof"), ""),
        GlossaryEntry("诉讼时效", mapOf("en" to "limitation period"), ""),
        GlossaryEntry("仲裁条款", mapOf("en" to "arbitration clause"), ""),
        GlossaryEntry("管辖法院", mapOf("en" to "court of jurisdiction"), ""),
        GlossaryEntry("合同解除", mapOf("en" to "termination of contract"), ""),
        GlossaryEntry("合同终止", mapOf("en" to "expiration of contract"), "与解除区分"),
        GlossaryEntry("善意第三人", mapOf("en" to "bona fide third party"), ""),
        GlossaryEntry("瑕疵担保", mapOf("en" to "warranty against defects"), ""),
        GlossaryEntry("不当得利", mapOf("en" to "unjust enrichment"), ""),
        GlossaryEntry("显失公平", mapOf("en" to "obvious unfairness"), "合同可撤销事由"),
        GlossaryEntry("格式条款", mapOf("en" to "standard terms"), ""),
        GlossaryEntry("要约", mapOf("en" to "offer"), ""),
        GlossaryEntry("承诺", mapOf("en" to "acceptance"), "合同订立术语"),
        GlossaryEntry("要约邀请", mapOf("en" to "invitation to offer"), ""),
        GlossaryEntry("缔约方", mapOf("en" to "contracting party"), ""),
        GlossaryEntry("甲方", mapOf("en" to "Party A"), "合同语境"),
        GlossaryEntry("乙方", mapOf("en" to "Party B"), "合同语境"),
        GlossaryEntry("生效日期", mapOf("en" to "effective date"), ""),
        GlossaryEntry("副本", mapOf("en" to "counterpart"), "合同份数语境"),
        GlossaryEntry("签字盖章", mapOf("en" to "signature and seal"), "")
    )

    val FINANCE_TERMS: List<GlossaryEntry> = listOf(
        GlossaryEntry("对冲", mapOf("en" to "hedging"), ""),
        GlossaryEntry("市盈率", mapOf("en" to "price-to-earnings ratio"), "P/E"),
        GlossaryEntry("市净率", mapOf("en" to "price-to-book ratio"), "P/B"),
        GlossaryEntry("流动性", mapOf("en" to "liquidity"), ""),
        GlossaryEntry("杠杆", mapOf("en" to "leverage"), ""),
        GlossaryEntry("保证金", mapOf("en" to "margin"), "交易语境"),
        GlossaryEntry("止损", mapOf("en" to "stop-loss"), ""),
        GlossaryEntry("止盈", mapOf("en" to "take-profit"), ""),
        GlossaryEntry("做多头", mapOf("en" to "long position"), ""),
        GlossaryEntry("做空头", mapOf("en" to "short position"), ""),
        GlossaryEntry("交割", mapOf("en" to "delivery"), "期货语境"),
        GlossaryEntry("平仓", mapOf("en" to "position closing"), ""),
        GlossaryEntry("爆仓", mapOf("en" to "forced liquidation"), ""),
        GlossaryEntry("标的资产", mapOf("en" to "underlying asset"), ""),
        GlossaryEntry("衍生品", mapOf("en" to "derivative"), ""),
        GlossaryEntry("票面利率", mapOf("en" to "coupon rate"), ""),
        GlossaryEntry("到期收益率", mapOf("en" to "yield to maturity"), "YTM"),
        GlossaryEntry("信用利差", mapOf("en" to "credit spread"), ""),
        GlossaryEntry("首次公开募股", mapOf("en" to "initial public offering"), "IPO"),
        GlossaryEntry("定向增发", mapOf("en" to "private placement"), ""),
        GlossaryEntry("回购", mapOf("en" to "buyback"), "股份回购"),
        GlossaryEntry("股息", mapOf("en" to "dividend"), ""),
        GlossaryEntry("复权", mapOf("en" to "adjusted price"), ""),
        GlossaryEntry("涨跌停", mapOf("en" to "daily price limit"), ""),
        GlossaryEntry("净现值", mapOf("en" to "net present value"), "NPV"),
        GlossaryEntry("内部收益率", mapOf("en" to "internal rate of return"), "IRR"),
        GlossaryEntry("资产负债率", mapOf("en" to "debt-to-asset ratio"), ""),
        GlossaryEntry("应收账款", mapOf("en" to "accounts receivable"), ""),
        GlossaryEntry("流动比率", mapOf("en" to "current ratio"), ""),
        GlossaryEntry("审计意见", mapOf("en" to "audit opinion"), ""),
        GlossaryEntry("或有负债", mapOf("en" to "contingent liability"), ""),
        GlossaryEntry("摊余成本", mapOf("en" to "amortized cost"), ""),
        GlossaryEntry("公允价值", mapOf("en" to "fair value"), ""),
        GlossaryEntry("递延所得税", mapOf("en" to "deferred tax"), ""),
        GlossaryEntry("商誉减值", mapOf("en" to "goodwill impairment"), "")
    )

    val GENERAL_TERMS: List<GlossaryEntry> = listOf(
        GlossaryEntry("截止日期", mapOf("en" to "deadline"), ""),
        GlossaryEntry("时间表", mapOf("en" to "timeline"), ""),
        GlossaryEntry("里程碑", mapOf("en" to "milestone"), ""),
        GlossaryEntry("利益相关方", mapOf("en" to "stakeholder"), ""),
        GlossaryEntry("执行摘要", mapOf("en" to "executive summary"), ""),
        GlossaryEntry("最佳实践", mapOf("en" to "best practice"), ""),
        GlossaryEntry("经验教训", mapOf("en" to "lessons learned"), ""),
        GlossaryEntry("风险评估", mapOf("en" to "risk assessment"), ""),
        GlossaryEntry("问责", mapOf("en" to "accountability"), ""),
        GlossaryEntry("透明度", mapOf("en" to "transparency"), ""),
        GlossaryEntry("可扩展性", mapOf("en" to "scalability"), ""),
        GlossaryEntry("可行性", mapOf("en" to "feasibility"), ""),
        GlossaryEntry("基准测试", mapOf("en" to "benchmark"), ""),
        GlossaryEntry("落地页", mapOf("en" to "landing page"), ""),
        GlossaryEntry("用户画像", mapOf("en" to "user persona"), ""),
        GlossaryEntry("留存率", mapOf("en" to "retention rate"), ""),
        GlossaryEntry("转化率", mapOf("en" to "conversion rate"), ""),
        GlossaryEntry("净推荐值", mapOf("en" to "net promoter score"), "NPS"),
        GlossaryEntry("北极星指标", mapOf("en" to "north star metric"), ""),
        GlossaryEntry("最小可行产品", mapOf("en" to "minimum viable product"), "MVP"),
        GlossaryEntry("迭代", mapOf("en" to "iteration"), ""),
        GlossaryEntry("复盘", mapOf("en" to "retrospective"), ""),
        GlossaryEntry("对齐", mapOf("en" to "alignment"), "协作语境"),
        GlossaryEntry("拉通", mapOf("en" to "coordinate across teams"), "中式管理词，意译"),
        GlossaryEntry("赋能", mapOf("en" to "empower"), ""),
        GlossaryEntry("抓手", mapOf("en" to "lever"), "中式管理词，意译"),
        GlossaryEntry("颗粒度", mapOf("en" to "granularity"), ""),
        GlossaryEntry("闭环", mapOf("en" to "closed loop"), ""),
        GlossaryEntry("组合拳", mapOf("en" to "combined approach"), "意译"),
        GlossaryEntry("护城河", mapOf("en" to "moat"), "商业竞争语境")
    )

    /** 领域 → 术语表。 */
    fun termsFor(domain: GlossaryDomain): List<GlossaryEntry> = when (domain) {
        GlossaryDomain.TECHNICAL -> TECHNICAL_TERMS
        GlossaryDomain.MEDICAL -> MEDICAL_TERMS
        GlossaryDomain.LEGAL -> LEGAL_TERMS
        GlossaryDomain.FINANCE -> FINANCE_TERMS
        GlossaryDomain.GENERAL -> GENERAL_TERMS
    }

    /**
     * 术语命中扫描：双向（中→目标语言 / 英→目标语言）。
     *
     * 匹配规则：
     * - 中文术语：精确子串（按出现次数计数）；
     * - 英文术语：词边界匹配（大小写不敏感）；
     * - 目标语言与源同语（如中→中）时跳过（无翻译意义）。
     */
    fun matchTerms(text: String, domain: GlossaryDomain, targetLanguage: String): List<GlossaryHit> {
        if (text.isBlank()) return emptyList()
        val isTargetChinese = targetLanguage == "zh"
        val hits = mutableListOf<GlossaryHit>()
        val domainTerms = termsFor(domain)
        val generalTerms = if (domain == GlossaryDomain.GENERAL) emptyList() else GENERAL_TERMS
        for (entry in domainTerms + generalTerms) {
            val zhTerm = entry.chineseTerm ?: entry.term.takeIf { containsCjk(it) }
            val enTerm = entry.englishTerm
            val zhCount = zhTerm?.let { countOccurrences(text, it) } ?: 0
            val enCount = countWordOccurrences(text, enTerm)
            val totalCount = zhCount + enCount
            if (totalCount <= 0) continue
            // 与目标语言同语的建议跳过（中→中无意义）
            if (isTargetChinese && zhCount > 0 && enCount == 0) continue
            val suggested = when {
                entry.translationFor(targetLanguage) != null -> entry.translationFor(targetLanguage)!!
                isTargetChinese -> zhTerm ?: continue
                else -> enTerm
            }
            // 只在「建议译法与被命中的原文形态相同」时跳过（同语翻译无意义）
            val matchedForm = if (zhCount > 0) (zhTerm ?: enTerm) else enTerm
            if (suggested == matchedForm) continue
            hits.add(GlossaryHit(entry, totalCount, suggested))
        }
        return hits.sortedByDescending { it.count }
    }

    private fun countOccurrences(text: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var index = text.indexOf(needle)
        while (index >= 0) {
            count++
            index = text.indexOf(needle, index + needle.length)
        }
        return count
    }

    private fun countWordOccurrences(text: String, word: String): Int {
        if (word.isEmpty()) return 0
        val pattern = Regex("""\b${Regex.escape(word)}\b""", RegexOption.IGNORE_CASE)
        return pattern.findAll(text).count()
    }

    // ═══ 一致性检查 ═══

    /** 规范译法的常见变体（一致性检查用：同一术语的等价写法清单）。 */
    private val TRANSLATION_VARIANTS: Map<String, List<String>> = mapOf(
        "rollback" to listOf("roll back"),
        "canary release" to listOf("canary deployment"),
        "circuit breaking" to listOf("circuit breaker", "circuit break"),
        "idempotent" to listOf("idempotence", "idempotency"),
        "load balancing" to listOf("load-balancing"),
        "observability" to listOf("observability capability"),
        "force majeure" to emptyList(),
        "race condition" to listOf("data race")
    )

    /**
     * 术语一致性：同一源术语的规范译法与常见变体在译文中的出现情况。
     * 同一术语在译文里出现两种以上写法 → 违规（术语必须前后统一）。
     */
    fun checkConsistency(
        source: String,
        output: String,
        domain: GlossaryDomain,
        targetLanguage: String
    ): List<ConsistencyIssue> {
        val issues = mutableListOf<ConsistencyIssue>()
        for (hit in matchTerms(source, domain, targetLanguage)) {
            val entry = hit.entry
            val canonical = entry.translationFor(targetLanguage)
                ?: if (targetLanguage == "zh") entry.chineseTerm else entry.englishTerm
            if (canonical == null) continue
            val forms = (listOf(canonical) + (TRANSLATION_VARIANTS[canonical.lowercase()] ?: emptyList()))
                .distinct()
            val found = forms.filter { form ->
                if (containsCjk(form)) output.contains(form)
                else Regex("""\b${Regex.escape(form)}\b""", RegexOption.IGNORE_CASE)
                    .containsMatchIn(output)
            }
            if (found.size > 1) {
                issues.add(ConsistencyIssue(entry.term, found))
            }
        }
        return issues
    }

    // ═══ 书写系统与语言 ═══

    /** 书写系统普查结果。 */
    data class ScriptCensus(
        val cjk: Int,
        val latin: Int,
        val cyrillic: Int,
        val arabic: Int,
        val kana: Int,
        val hangul: Int
    ) {
        /** 主体书写系统（无有效字符返回 null）。 */
        val dominant: String? get() {
            val entries = listOf(
                "cjk" to cjk, "latin" to latin, "cyrillic" to cyrillic,
                "arabic" to arabic, "kana" to kana, "hangul" to hangul
            ).filter { it.second > 0 }
            if (entries.isEmpty()) return null
            return entries.maxByOrNull { it.second }?.first
        }

        /** 混合度（0..1：次多字符占比）。 */
        val mixtureRatio: Double
            get() {
                val total = cjk + latin + cyrillic + arabic + kana + hangul
                if (total == 0) return 0.0
                val sorted = listOf(cjk, latin, cyrillic, arabic, kana, hangul).sortedDescending()
                val secondary = sorted.getOrNull(1) ?: 0
                return secondary.toDouble() / total
            }
    }

    /** 字符普查（只统计字母/字母系字符，忽略数字标点空白）。 */
    fun censusScript(text: String): ScriptCensus {
        var cjk = 0
        var latin = 0
        var cyrillic = 0
        var arabic = 0
        var kana = 0
        var hangul = 0
        for (ch in text) {
            val block = Character.UnicodeBlock.of(ch) ?: continue
            when (block) {
                Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS,
                Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A,
                Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION -> cjk++
                Character.UnicodeBlock.BASIC_LATIN,
                Character.UnicodeBlock.LATIN_1_SUPPLEMENT,
                Character.UnicodeBlock.LATIN_EXTENDED_A -> if (ch.isLetter()) latin++
                Character.UnicodeBlock.CYRILLIC -> cyrillic++
                Character.UnicodeBlock.ARABIC -> arabic++
                Character.UnicodeBlock.HIRAGANA,
                Character.UnicodeBlock.KATAKANA -> kana++
                Character.UnicodeBlock.HANGUL_SYLLABLES,
                Character.UnicodeBlock.HANGUL_JAMO -> hangul++
                else -> Unit
            }
        }
        return ScriptCensus(cjk, latin, cyrillic, arabic, kana, hangul)
    }

    /** 各语言常见功能词（源语言猜测用）。 */
    private val LANGUAGE_MARKERS: Map<String, List<String>> = mapOf(
        "de" to listOf(" der ", " die ", " das ", " und ", " nicht ", " ist ", " mit "),
        "fr" to listOf(" le ", " la ", " les ", " et ", " est ", " des ", " une "),
        "es" to listOf(" el ", " la ", " los ", " y ", " es ", " una ", " con "),
        "ru" to listOf(" и ", " не ", " что ", " на ", " это "),
        "ja" to listOf("の", "です", "ます", "した", "して"),
        "ko" to listOf("입니다", "합니다", "하는", "이는"),
        "pt" to listOf(" o ", " a ", " os ", " e ", " não "),
        "it" to listOf(" il ", " la ", " che ", " non ", " una ")
    )

    /**
     * 源语言猜测：脚本主导 + 功能词计分。
     * 返回 (语言码, 置信说明)；无法判断返回 null。
     */
    fun guessSourceLanguage(text: String): Pair<String, String>? {
        val census = censusScript(text)
        return when (census.dominant) {
            "cjk" -> {
                if (census.kana > 0 && census.kana >= text.length / 50) {
                    "ja" to "假名占比显著"
                } else {
                    "zh" to "CJK 主体且无假名"
                }
            }
            "kana" -> "ja" to "假名主体"
            "hangul" -> "ko" to "韩文字母主体"
            "cyrillic" -> "ru" to "西里尔主体"
            "arabic" -> "ar" to "阿拉伯字母主体"
            "latin" -> {
                val padded = " $text ".lowercase()
                val best = LANGUAGE_MARKERS.entries
                    .map { (language, markers) -> language to markers.count { padded.contains(it) } }
                    .filter { it.second > 0 }
                    .maxByOrNull { it.second }
                if (best != null && best.second >= 2) {
                    best.first to "功能词命中 ${best.second} 个"
                } else {
                    "en" to "拉丁主体无显著其它语言特征"
                }
            }
            else -> null
        }
    }

    /** 目标语言别名归一表。 */
    private val TARGET_LANGUAGE_ALIASES: Map<String, String> = buildMap {
        put("中文", "zh"); put("汉语", "zh"); put("中文（简体）", "zh"); put("简体中文", "zh")
        put("chinese", "zh"); put("zh", "zh"); put("mandarin", "zh"); put("普通话", "zh")
        put("英文", "en"); put("英语", "en"); put("english", "en"); put("en", "en")
        put("日语", "ja"); put("日文", "ja"); put("japanese", "ja"); put("ja", "ja")
        put("韩语", "ko"); put("韩文", "ko"); put("korean", "ko"); put("ko", "ko")
        put("德语", "de"); put("german", "de"); put("de", "de")
        put("法语", "fr"); put("french", "fr"); put("fr", "fr")
        put("俄语", "ru"); put("russian", "ru"); put("ru", "ru")
        put("西班牙语", "es"); put("spanish", "es"); put("es", "es")
        put("葡萄牙语", "pt"); put("portuguese", "pt"); put("pt", "pt")
        put("意大利语", "it"); put("italian", "it"); put("it", "it")
        put("阿拉伯语", "ar"); put("arabic", "ar"); put("ar", "ar")
    }

    /**
     * 目标语言归一（未知返回 null，行为侧提示假设）。
     */
    fun normalizeTargetLanguage(input: String): String? {
        val key = input.trim().lowercase()
        return TARGET_LANGUAGE_ALIASES[key] ?: TARGET_LANGUAGE_ALIASES[input.trim()]
    }

    /** 语言展示名。 */
    fun displayNameOf(code: String): String = when (code) {
        "zh" -> "中文"; "en" -> "英文"; "ja" -> "日语"; "ko" -> "韩语"; "de" -> "德语"
        "fr" -> "法语"; "ru" -> "俄语"; "es" -> "西班牙语"; "pt" -> "葡萄牙语"
        "it" -> "意大利语"; "ar" -> "阿拉伯语"; else -> code
    }

    /** RTL（从右到左）语言判定。 */
    fun isRightToLeft(code: String): Boolean = code == "ar" || code == "he" || code == "fa"

    /** 文本是否含 CJK 字符。 */
    fun containsCjk(text: String): Boolean {
        for (ch in text) {
            val block = Character.UnicodeBlock.of(ch) ?: continue
            if (block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
                block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
            ) return true
        }
        return false
    }
}
