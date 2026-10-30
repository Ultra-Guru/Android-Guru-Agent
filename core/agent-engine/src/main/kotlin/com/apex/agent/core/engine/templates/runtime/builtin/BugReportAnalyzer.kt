package com.apex.agent.core.engine.templates.runtime.builtin

import com.apex.agent.core.engine.templates.runtime.TemplateFinding
import com.apex.agent.core.engine.templates.runtime.TemplateFindingCategory
import com.apex.agent.core.engine.templates.runtime.TemplateSeverity

/**
 * ═══ 内置模板行为 · BUG 报告 —— 严重度与质量分析器（PR 10）═══
 *
 * BUG 报告行为的分析层：从「症状/复现步骤/期望/实际/环境」五要素
 * 里做确定性判读：
 * 1. **严重度推断**：症状关键词 → 严重度（崩溃/数据丢失/安全 → 阻断）；
 * 2. **复现质量评分**：步骤数/确定性词汇/具体度（文件、行号、命令）；
 * 3. **环境解析**：设备/系统/版本模式抽取成结构化字段；
 * 4. **假设排序**：症状关键词 → 可能原因类别的启发排序（供 LLM 展开）；
 * 5. **矛盾检测**：期望与实际相同、复现步骤为空等结构性问题。
 */

/** 严重度评估结果。 */
data class SeverityAssessment(
    val severity: TemplateSeverity,
    /** 判定依据（关键词命中）。 */
    val evidence: List<String>,
    /** 分级说明（喂提示词）。 */
    val rationale: String
)

/** 复现质量评估。 */
data class ReproQuality(
    /** 0..100。 */
    val score: Int,
    val stepCount: Int,
    val hasDeterministicMarkers: Boolean,
    val hasConcreteArtifacts: Boolean,
    /** 改进建议。 */
    val suggestions: List<String>
)

/** 环境解析结果。 */
data class EnvironmentParse(
    val device: String?,
    val osVersion: String?,
    val appVersion: String?,
    /** 未识别的剩余信息（原样带回）。 */
    val remainder: String
)

/** 可能原因假设。 */
data class Hypothesis(
    val category: String,
    /** 优先级（1 最高）。 */
    val priority: Int,
    val rationale: String
)

/** 报告质量问题（结构性矛盾）。 */
data class BugReportQualityIssue(
    val kind: Kind,
    val detail: String
) {
    enum class Kind(val label: String) {
        EMPTY_REPRO("复现步骤为空"),
        SAME_EXPECTED_ACTUAL("期望与实际相同"),
        ONE_WORD_SYMPTOM("症状描述过短"),
        NO_ENVIRONMENT("缺少环境信息"),
        TEMPORAL_VAGUE("时间描述含糊")
    }
}

/**
 * BUG 报告分析器（纯函数）。
 */
object BugReportAnalyzer {

    // ═══ 严重度推断 ═══

    /** 症状 → 严重度关键词表（命中即升级）。 */
    private val SEVERITY_KEYWORDS: List<Triple<TemplateSeverity, String, String>> = listOf(
        Triple(TemplateSeverity.BLOCKER, "崩溃", "进程崩溃/闪退"),
        Triple(TemplateSeverity.BLOCKER, "闪退", "进程崩溃"),
        Triple(TemplateSeverity.BLOCKER, "crash", "进程崩溃"),
        Triple(TemplateSeverity.BLOCKER, "数据丢失", "不可恢复的数据损失"),
        Triple(TemplateSeverity.BLOCKER, "丢失", "数据丢失语义（需确认范围）"),
        Triple(TemplateSeverity.BLOCKER, "无法启动", "核心功能完全不可用"),
        Triple(TemplateSeverity.BLOCKER, "白屏", "界面完全不可用"),
        Triple(TemplateSeverity.BLOCKER, "死机", "系统无响应"),
        Triple(TemplateSeverity.BLOCKER, "注入", "安全漏洞语义"),
        Triple(TemplateSeverity.BLOCKER, "越权", "安全漏洞语义"),
        Triple(TemplateSeverity.BLOCKER, "泄露", "数据/隐私泄露"),
        Triple(TemplateSeverity.BLOCKER, "密码", "凭据相关（需确认是否暴露）"),
        Triple(TemplateSeverity.IMPORTANT, "错误", "功能性错误（非崩溃）"),
        Triple(TemplateSeverity.IMPORTANT, "失败", "操作失败"),
        Triple(TemplateSeverity.IMPORTANT, "异常", "捕获异常路径"),
        Triple(TemplateSeverity.IMPORTANT, "卡顿", "性能显著劣化"),
        Triple(TemplateSeverity.IMPORTANT, "缓慢", "性能劣化"),
        Triple(TemplateSeverity.IMPORTANT, "超时", "超时语义"),
        Triple(TemplateSeverity.IMPORTANT, "不一致", "数据一致性问题"),
        Triple(TemplateSeverity.IMPORTANT, "乱码", "编码问题"),
        Triple(TemplateSeverity.IMPORTANT, "乱序", "顺序性问题"),
        Triple(TemplateSeverity.MINOR, "样式", "视觉问题"),
        Triple(TemplateSeverity.MINOR, "对齐", "布局问题"),
        Triple(TemplateSeverity.MINOR, "文案", "文字问题"),
        Triple(TemplateSeverity.MINOR, "提示", "提示信息问题"),
        Triple(TemplateSeverity.MINOR, "拼写", "文字问题")
    )

    /**
     * 严重度推断：症状 + 实际行为里关键词命中取最高；无命中回落
     * IMPORTANT（功能性问题的保守默认）。
     */
    fun assessSeverity(symptom: String, actual: String): SeverityAssessment {
        val corpus = listOf(symptom, actual).filter { it.isNotBlank() }
        if (corpus.isEmpty()) {
            return SeverityAssessment(
                TemplateSeverity.IMPORTANT,
                emptyList(),
                "无症状信息，按功能性问题保守定级"
            )
        }
        var best: Triple<TemplateSeverity, String, String>? = null
        val evidence = mutableListOf<String>()
        for (entry in SEVERITY_KEYWORDS) {
            val (severity, keyword, note) = entry
            val hitIn = corpus.firstOrNull { it.contains(keyword, ignoreCase = true) }
            if (hitIn != null) {
                evidence.add("「$keyword」→ $note")
                if (best == null || severity.weight > best!!.first.weight) {
                    best = entry
                }
            }
        }
        val winner = best
        return if (winner == null) {
            SeverityAssessment(
                TemplateSeverity.IMPORTANT,
                evidence,
                "无严重度关键词命中，按功能性问题定级（重要）"
            )
        } else {
            SeverityAssessment(
                winner.first,
                evidence,
                "命中「${winner.second}」→ ${winner.third}"
            )
        }
    }

    // ═══ 复现质量 ═══

    private val DETERMINISTIC_MARKERS = listOf(
        "每次", "必现", "100%", "总是", "一定", "always", "deterministic", "稳定复现"
    )
    private val VAGUE_MARKERS = listOf("偶尔", "有时", "随机", "偶现", "sometimes", "大概率")

    private val CONCRETE_ARTIFACTS = listOf(
        Regex("""\.\w{1,5}\b"""),          // 文件名扩展
        Regex("""第\s*\d+\s*行|:\d+"""),   // 行号
        Regex("""/\w+"""),                 // 路径
        Regex("""\b(log|日志|堆栈|stack|trace)\b""", RegexOption.IGNORE_CASE),
        Regex("""`[^`]+`"""),              // 反引号代码
        Regex("""\d+\.\d+""")              // 版本号
    )

    /**
     * 复现质量评分（0..100）：
     * - 步骤数（1 步 30 / 2+ 步 40，上限 40）；
     * - 确定性词汇（+25）或含糊词汇（-10）；
     * - 具体工件（行号/路径/日志/代码引用，+35 封顶）。
     */
    fun assessReproQuality(reproduce: String): ReproQuality {
        if (reproduce.isBlank()) {
            return ReproQuality(0, 0, false, false, listOf("复现步骤为空——这是报告质量的硬伤，必须补"))
        }
        val steps = countSteps(reproduce)
        var score = 0
        val suggestions = mutableListOf<String>()

        score += when {
            steps >= 2 -> 40
            steps == 1 -> 30
            else -> 10
        }
        if (steps == 0) suggestions.add("复现步骤建议编号列出（1. 2. 3.），每步一个动作")

        val deterministic = DETERMINISTIC_MARKERS.any { reproduce.contains(it, ignoreCase = true) }
        val vague = VAGUE_MARKERS.any { reproduce.contains(it, ignoreCase = true) }
        score += if (deterministic) 25 else 0
        if (vague) {
            score -= 10
            suggestions.add("出现含糊频率词（偶尔/随机）：补充出现频率与触发条件")
        }
        if (!deterministic && !vague) suggestions.add("补充复现频率（每次/偶现）与样本量")

        val artifactHits = CONCRETE_ARTIFACTS.count { it.containsMatchIn(reproduce) }
        val artifactBonus = (artifactHits * 15).coerceAtMost(35)
        score += artifactBonus
        if (artifactHits == 0) suggestions.add("附上具体工件：报错行号、文件路径、日志片段或代码引用")

        return ReproQuality(
            score = score.coerceIn(0, 100),
            stepCount = steps,
            hasDeterministicMarkers = deterministic,
            hasConcreteArtifacts = artifactHits > 0,
            suggestions = suggestions
        )
    }

    /** 步骤计数：编号行 / 换行分段。 */
    internal fun countSteps(reproduce: String): Int {
        val numbered = Regex("""(?m)^\s*(?:\d{1,2}[.)、]|[①②③④⑤⑥⑦⑧⑨⑩])\s""").findAll(reproduce).count()
        if (numbered > 0) return numbered
        val lines = reproduce.lines().filter { it.isNotBlank() }
        return when {
            lines.size == 1 && reproduce.contains(Regex("""然后|接着|其次|再""")) ->
                reproduce.split(Regex("""然后|接着|其次|再""")).count { it.isNotBlank() }
            else -> lines.size
        }
    }

    // ═══ 环境解析 ═══

    /**
     * 环境解析：设备/系统/版本模式抽取。
     */
    fun parseEnvironment(environment: String): EnvironmentParse {
        if (environment.isBlank()) return EnvironmentParse(null, null, null, "")
        // 设备双通道：品牌名直接命中；否则取「设备/机型」标签后的值
        val device = Regex(
            """(Pixel|Samsung|Xiaomi|Redmi|Huawei|OnePlus|iPhone|iPad|emulator|模拟器)[\w\d ]*""",
            RegexOption.IGNORE_CASE
        ).find(environment)?.value?.trim()
            ?: Regex("""(?:设备|机型)[:：]\s*([^\s,，;；]+)""").find(environment)?.groupValues?.get(1)?.trim()
        val os = Regex("""(Android|iOS|Windows|Linux|macOS|HarmonyOS)\s*(\d+(?:\.\d+)*)?""", RegexOption.IGNORE_CASE)
            .find(environment)?.value
        val version = Regex("""(?:版本|version|v|build)\s*[:：]?\s*([\d][\w.\-]*)""", RegexOption.IGNORE_CASE)
            .find(environment)?.groupValues?.get(0)
        val known = listOfNotNull(device, os, version).joinToString("|")
        return EnvironmentParse(
            device = device?.trim(),
            osVersion = os?.trim(),
            appVersion = version?.trim(),
            remainder = environment.replace(known, "").trim(' ', '，', ',', '；', ';')
        )
    }

    // ═══ 假设排序 ═══

    /** 症状关键词 → 原因类别启发表。 */
    private val HYPOTHESIS_TABLE: List<Pair<List<String>, String>> = listOf(
        listOf("崩溃", "闪退", "crash", "空指针", "NPE") to "空值/生命周期：对象在无效状态被访问（空指针、生命周期越界、竞态初始化）",
        listOf("卡顿", "缓慢", "超时", "ANR", "性能") to "主线程/IO：耗时操作占用主线程、重复计算、锁竞争或 IO 放大",
        listOf("乱码", "编码", "显示异常") to "编码/区域：字符集假设不一致、区域格式未本地化",
        listOf("不一致", "同步", "丢失", "重复") to "并发/状态：竞态写入、缓存与存储不一致、事务边界不完整",
        listOf("无法启动", "白屏", "黑屏") to "初始化：依赖注入/配置缺失、启动路径异常、首帧渲染阻塞",
        listOf("权限", "拒绝", "授权") to "权限：运行时权限未申请/未处理拒绝分支、权限声明缺失",
        listOf("网络", "请求失败", "断网", "重试") to "网络韧性：超时与重试策略、弱网/断网分支、错误码映射",
        listOf("升级", "更新", "迁移", "老版本") to "数据迁移：旧数据结构未兼容、schema 迁移缺步、默认值缺失"
    )

    /**
     * 假设排序：症状 + 实际行为关键词命中（多命中并列按表序）。
     * 无命中给通用三假设（代码/数据/环境）。
     */
    fun rankHypotheses(symptom: String, actual: String): List<Hypothesis> {
        val corpus = listOf(symptom, actual).filter { it.isNotBlank() }
        val hits = mutableListOf<Pair<Int, String>>()
        for ((index, entry) in HYPOTHESIS_TABLE.withIndex()) {
            val (keywords, category) = entry
            if (corpus.any { text -> keywords.any { text.contains(it, ignoreCase = true) } }) {
                hits.add(index to category)
            }
        }
        return if (hits.isEmpty()) {
            listOf(
                Hypothesis("代码逻辑：边界条件/异常分支处理不完整", 1, "通用首查：最近改动与异常分支"),
                Hypothesis("数据状态：脏数据/旧格式数据触发未预期路径", 2, "通用次查：输入数据形态"),
                Hypothesis("环境差异：系统版本/权限/资源差异", 3, "通用末查：环境矩阵对比")
            )
        } else {
            hits.mapIndexed { rank, (index, category) ->
                Hypothesis(category, rank + 1, "症状命中启发表第 ${index + 1} 类关键词")
            }
        }
    }

    // ═══ 结构性质量问题 ═══

    /**
     * 结构性质量检查（期望/实际/复现/症状/环境五要素的形态矛盾）。
     */
    fun qualityIssues(
        symptom: String,
        reproduce: String,
        expected: String,
        actual: String,
        environment: String
    ): List<BugReportQualityIssue> {
        val issues = mutableListOf<BugReportQualityIssue>()
        if (reproduce.isBlank()) {
            issues.add(BugReportQualityIssue(BugReportQualityIssue.Kind.EMPTY_REPRO, "复现步骤为空"))
        }
        val expectedTrim = expected.trim()
        val actualTrim = actual.trim()
        if (expectedTrim.isNotBlank() && expectedTrim == actualTrim) {
            issues.add(BugReportQualityIssue(BugReportQualityIssue.Kind.SAME_EXPECTED_ACTUAL, "期望与实际完全相同——缺陷定义不成立或填写错误"))
        }
        if (symptom.trim().length <= 3) {
            issues.add(BugReportQualityIssue(BugReportQualityIssue.Kind.ONE_WORD_SYMPTOM, "症状描述过短（≤3 字），无法定位方向"))
        }
        if (environment.isBlank()) {
            issues.add(BugReportQualityIssue(BugReportQualityIssue.Kind.NO_ENVIRONMENT, "缺少环境信息（设备/系统/版本）"))
        }
        if (Regex("""最近|刚才|之前一直""").containsMatchIn(actual) && !actual.contains(Regex("""\d"""))) {
            issues.add(BugReportQualityIssue(BugReportQualityIssue.Kind.TEMPORAL_VAGUE, "时间描述含糊（「最近/之前」），补充首次出现时间与关联变更"))
        }
        return issues
    }

    /** 质量问题 → 发现（供分析聚合）。 */
    fun findingsFromIssues(issues: List<BugReportQualityIssue>): List<TemplateFinding> =
        issues.map { issue ->
            TemplateFinding(
                ruleId = "bugreport.quality",
                category = TemplateFindingCategory.COMPLETENESS,
                severity = if (issue.kind == BugReportQualityIssue.Kind.EMPTY_REPRO ||
                    issue.kind == BugReportQualityIssue.Kind.SAME_EXPECTED_ACTUAL
                ) TemplateSeverity.IMPORTANT else TemplateSeverity.MINOR,
                title = "[${issue.kind.label}] ${issue.detail}",
                suggestion = "在生成报告时明确标注该缺口，引导补充信息",
                confidence = 1.0
            )
        }
}
