package com.apex.agent.core.engine.templates.runtime.builtin

import com.apex.agent.core.engine.templates.runtime.TemplateSeverity

/**
 * ═══ 内置模板行为 · BUG 报告 —— 分诊引擎（PR 18 深化 II）═══
 *
 * BUG 报告行为的第二层确定性引擎：[BugReportAnalyzer] 管「严重度多高、
 * 复现质量多好」，本模块管「工单怎么排优先级、像不像已知问题、
 * 按假设类别怎么排查、回归测试怎么写」——把缺陷管理的处置纪律
 * 代码化，报告生成时直接带上分诊结论。
 *
 * 能力族（全部纯函数、全部确定性）：
 * 1. **P0-P4 优先级模型**：严重度 × 影响频率 × 影响范围三维合成
 *    工单优先级（P0 立刻修 / P1 本迭代 / P2 排期 / P3 顺手修 /
 *    P4 记录观察）；
 * 2. **环境指纹**：环境串解析成结构化指纹（平台/版本段/设备族），
 *    与已知问题模式库匹配（如特定系统版本的已知兼容问题）；
 * 3. **症状签名**：症状 + 复现关键步骤 → 归一化签名串（查重提示：
 *    同签名的历史工单值得先查）；
 * 4. **根因排查树**：每类假设（空指针/并发/资源泄漏/编码/配置…）
 *    的确定性排查步骤清单（排查指令形态，不是泛泛建议）；
 * 5. **升级规则**：什么条件下必须升级（安全类/数据类/阻断级 +
 *     复现质量不足的组合）；
 * 6. **回归测试指引**：按假设类别生成「先写失败测试」的具体形态
 *    （测试命名/断言目标/边界条件）。
 *
 * 防御式纪律：空输入/无法解析的环境串全部有退化路径，绝不抛。
 */

/** 工单优先级（P0 最高）。 */
enum class TriagePriority(val label: String, val sla: String) {
    P0("紧急", "立刻处理：热修通道"),
    P1("高", "本迭代内修复"),
    P2("中", "正常排期（下个迭代）"),
    P3("低", "顺手修 / 优化窗口"),
    P4("观察", "记录观察，暂不排期");

    companion object {
        /** 从严重度 + 分值反查（报告聚合用）。 */
        fun fromComposite(score: Int): TriagePriority = when {
            score >= 80 -> P0
            score >= 60 -> P1
            score >= 40 -> P2
            score >= 20 -> P3
            else -> P4
        }
    }
}

/** 三维分诊输入（由五要素派生）。 */
data class TriageDimensions(
    /** 严重度权重（BLOCKER=100/IMPORTANT=70/MINOR=40/INFO=20）。 */
    val severityWeight: Int,
    /** 影响频率（0-100：每次必现=100 / 经常=70 / 偶发=40 / 仅一次=20）。 */
    val frequencyWeight: Int,
    /** 影响范围（0-100：全量用户=100 / 部分用户=60 / 单用户=30）。 */
    val scopeWeight: Int
) {
    init {
        require(severityWeight in 0..100) { "severityWeight out of range" }
        require(frequencyWeight in 0..100) { "frequencyWeight out of range" }
        require(scopeWeight in 0..100) { "scopeWeight out of range" }
    }
}

/** 分诊结论。 */
data class TriageVerdict(
    val priority: TriagePriority,
    /** 综合分（0-100）。 */
    val compositeScore: Int,
    /** 判定依据（人读，喂提示词与报告）。 */
    val rationale: String,
    /** 是否必须升级（安全/数据/阻断组合）。 */
    val requiresEscalation: Boolean,
    /** 升级理由（requiresEscalation 时非空）。 */
    val escalationReason: String
)

/** 环境指纹。 */
data class EnvironmentFingerprint(
    /** 平台（android/ios/web/server/unknown）。 */
    val platform: String,
    /** 主版本段（如 Android 15 → "15"）。 */
    val osMajor: String,
    /** 设备族（如 Pixel / Xiaomi / Samsung / generic）。 */
    val deviceFamily: String,
    /** 应用版本（如 1.4.9）。 */
    val appVersion: String,
    /** 已知问题命中清单。 */
    val knownIssueHits: List<KnownIssueHit>
) {
    /** 指纹串（查重与报告展示）。 */
    fun signature(): String = "$platform/$osMajor/$deviceFamily/app-$appVersion"
}

/** 已知问题模式命中。 */
data class KnownIssueHit(
    val patternId: String,
    val title: String,
    val advice: String
)

/** 根因排查节点。 */
data class InvestigationStep(
    val order: Int,
    val action: String,
    /** 期望观察（判定标准）。 */
    val expectedObservation: String
)

/** 回归测试指引。 */
data class RegressionTestGuide(
    /** 建议测试名（语言中立形态）。 */
    val testName: String,
    /** 断言目标。 */
    val assertionTarget: String,
    /** 必须覆盖的边界条件。 */
    val boundaryConditions: List<String>
)

/**
 * 分诊引擎主体（纯函数集合）。
 */
object BugReportTriageEngine {

    // ═══ 1) P0-P4 优先级模型 ═══

    /** 三维权重（严重度主导，频率与范围修正）。 */
    private const val W_SEVERITY = 0.55
    private const val W_FREQUENCY = 0.25
    private const val W_SCOPE = 0.20

    /** 严重度 → 权重。 */
    fun severityWeightOf(severity: TemplateSeverity): Int = when (severity) {
        TemplateSeverity.BLOCKER -> 100
        TemplateSeverity.IMPORTANT -> 70
        TemplateSeverity.MINOR -> 40
        TemplateSeverity.INFO -> 20
    }

    /**
     * 频率推断：复现步骤文本里的频率词 → 权重；无频率词按「每次必现」
     * 的乐观假设（缺陷报告的保守姿态：宁可高估）。
     */
    fun inferFrequency(reproduce: String): Int {
        val text = reproduce.lowercase()
        return when {
            text.contains("偶现") || text.contains("偶尔") || text.contains("sometimes") ||
                text.contains("概率") || text.contains("偶发") -> 40
            text.contains("经常") || text.contains("频繁") || text.contains("often") ||
                text.contains("大部分") -> 70
            text.contains("仅一次") || text.contains("只出现一次") || text.contains("once") -> 20
            else -> 100
        }
    }

    /**
     * 范围推断：环境与症状里的范围词 → 权重；无范围词按「部分用户」。
     */
    fun inferScope(symptom: String, actual: String): Int {
        val text = "$symptom $actual"
        return when {
            text.contains("所有用户") || text.contains("全部用户") || text.contains("everyone") ||
                text.contains("全量") -> 100
            text.contains("仅我") || text.contains("只有我") || text.contains("个别") ||
                text.contains("特定设备") -> 30
            else -> 60
        }
    }

    /**
     * 优先级判定：三维加权合成 + 升级规则（安全类/数据类语义直接 P0）。
     */
    fun triage(
        severity: TemplateSeverity,
        symptom: String,
        actual: String,
        reproduce: String
    ): TriageVerdict {
        val dims = TriageDimensions(
            severityWeight = severityWeightOf(severity),
            frequencyWeight = inferFrequency(reproduce),
            scopeWeight = inferScope(symptom, actual)
        )
        val composite = (
            dims.severityWeight * W_SEVERITY +
                dims.frequencyWeight * W_FREQUENCY +
                dims.scopeWeight * W_SCOPE
            ).toInt().coerceIn(0, 100)
        val priority = TriagePriority.fromComposite(composite)

        // 升级规则：安全/数据语义无条件 P0
        val securityOrData = symptom + actual
        val escalation = severity == TemplateSeverity.BLOCKER && (
            securityOrData.contains("注入") || securityOrData.contains("越权") ||
                securityOrData.contains("泄露") || securityOrData.contains("数据丢失") ||
                securityOrData.contains("密码")
            )
        val finalPriority = if (escalation) TriagePriority.P0 else priority
        val finalScore = if (escalation) 100 else composite
        return TriageVerdict(
            priority = finalPriority,
            compositeScore = finalScore,
            rationale = "严重度 ${dims.severityWeight} × 频率 ${dims.frequencyWeight} × " +
                "范围 ${dims.scopeWeight} → 综合 $finalScore" +
                (if (escalation) "（安全/数据语义触发无条件 P0）" else ""),
            requiresEscalation = escalation,
            escalationReason = if (escalation) {
                "安全类/数据类缺陷无条件走 P0 热修通道：" +
                    "先止血（回滚/开关），再根因"
            } else {
                ""
            }
        )
    }

    // ═══ 2) 环境指纹与已知问题库 ═══

    /** 已知问题模式库（平台/版本段 → 已知缺陷与建议）。 */
    private data class KnownIssuePattern(
        val patternId: String,
        val platform: String,
        val osMajorContains: String?,
        val deviceFamilyContains: String?,
        val title: String,
        val advice: String
    )

    private val KNOWN_ISSUES: List<KnownIssuePattern> = listOf(
        KnownIssuePattern(
            "known.android-8-background-limit", "android", "8", null,
            "Android 8 后台服务限制",
            "targetSdk 26+ 后台启动服务受限于 JobScheduler；核对 startForegroundService 调用链"
        ),
        KnownIssuePattern(
            "known.android-13-notification-permission", "android", "13", null,
            "Android 13 通知运行时权限",
            "POST_NOTIFICATIONS 需运行时申请；未授权时通知静默失败（无崩溃）"
        ),
        KnownIssuePattern(
            "known.android-14-fg-service-type", "android", "14", null,
            "Android 14 前台服务必须声明类型",
            "foregroundServiceType 缺失时 startForeground 抛异常 —— 检查清单表声明"
        ),
        KnownIssuePattern(
            "known.android-15-edge-to-edge", "android", "15", null,
            "Android 15 强制边到边",
            "targetSdk 35 默认 edge-to-edge；未适配的旧布局会顶入状态栏区域"
        ),
        KnownIssuePattern(
            "known.pixel-thermal-throttle", "android", null, "pixel",
            "Pixel 系列温控降频",
            "长时间高负载卡顿先排查温控（dumpsys thermalservice），再查应用层"
        ),
        KnownIssuePattern(
            "known.xiaomi-permission-autostart", "android", null, "xiaomi",
            "小米 自启动管理拦截",
            "MIUI 自杀式杀后台：引导用户开自启动白名单，区分系统行为与应用缺陷"
        ),
        KnownIssuePattern(
            "known.samsung-battery-optimization", "android", null, "samsung",
            "三星 电池优化延迟推送",
            "One UI 电量优化会推迟心跳与推送；适配 doze 窗口或引导豁免"
        ),
        KnownIssuePattern(
            "known.ios-17-webkit-gesture", "ios", "17", null,
            "iOS 17 WebView 手势回归",
            "WebKit 手势识别变化：回退到 click 判定或升级 WKWebView 配置"
        )
    )

    /** 平台探测词表。 */
    private val PLATFORM_HINTS: List<Pair<String, String>> = listOf(
        "android" to "android", "安卓" to "android", "miui" to "android", "harmony" to "android",
        "ios" to "ios", "iphone" to "ios", "ipad" to "ios",
        "web" to "web", "浏览器" to "web", "chrome" to "web",
        "server" to "server", "服务端" to "server", "linux" to "server",
        "macos" to "server", "windows" to "server"
    )

    /** 设备族词表。 */
    private val DEVICE_FAMILIES: List<Pair<String, String>> = listOf(
        "pixel" to "Pixel", "小米" to "Xiaomi", "xiaomi" to "Xiaomi", "redmi" to "Xiaomi",
        "samsung" to "Samsung", "三星" to "Samsung", "galaxy" to "Samsung",
        "huawei" to "Huawei", "华为" to "Huawei", "honor" to "Huawei",
        "oppo" to "Oppo", "vivo" to "Vivo", "oneplus" to "OnePlus", "一加" to "OnePlus",
        "iphone" to "iPhone", "ipad" to "iPad"
    )

    /**
     * 环境指纹：平台/版本段/设备族抽取 + 已知问题库匹配。
     * 解析不出平台的串（如"测试机"）退化为 unknown 指纹，不命中任何已知问题。
     */
    fun fingerprint(environment: String): EnvironmentFingerprint {
        val text = environment.lowercase()
        val platform = PLATFORM_HINTS.firstOrNull { text.contains(it.first) }?.second ?: "unknown"
        val osMajor = Regex("""(?:android|ios|os|version|v)\s*\.?\s*(\d{1,2})""")
            .findAll(text)
            .map { it.groupValues[1] }
            .firstOrNull { it.toIntOrNull() in 1..20 } ?: ""
        val deviceFamily = DEVICE_FAMILIES.firstOrNull { text.contains(it.first) }?.second ?: "generic"
        val appVersion = Regex("""(?:app|version|版本|ver)\s*[:：]?\s*v?(\d+(?:\.\d+){1,3})""")
            .findAll(environment)
            .map { it.groupValues[1] }
            .firstOrNull() ?: ""
        val hits = KNOWN_ISSUES.filter { pattern ->
            pattern.platform == platform &&
                (pattern.osMajorContains == null || pattern.osMajorContains == osMajor) &&
                (pattern.deviceFamilyContains == null ||
                    deviceFamily.lowercase().contains(pattern.deviceFamilyContains))
        }.map { KnownIssueHit(it.patternId, it.title, it.advice) }
        return EnvironmentFingerprint(
            platform = platform,
            osMajor = osMajor,
            deviceFamily = deviceFamily,
            appVersion = appVersion,
            knownIssueHits = hits
        )
    }

    // ═══ 3) 症状签名 ═══

    /**
     * 症状签名：症状 + 实际行为 → 归一化签名（去空白/去数字/去标点后
     * 取前 60 字符）——同签名的工单大概率是同一缺陷，值得先查重再排查。
     */
    fun symptomSignature(symptom: String, actual: String): String {
        val merged = "$symptom｜$actual"
            .replace(Regex("""\s+"""), "")
            .replace(Regex("""[0-9０-９]+"""), "#")
            .replace(Regex("""[，。；：、！？,.;:!?()（）\[\]【】"'“”｜|·—-]"""), "")
            .lowercase()
        return merged.take(60).ifBlank { "empty-signature" }
    }

    /** 签名是否值得提示查重（非空且长度达标）。 */
    fun signatureIsQueryable(signature: String): Boolean =
        signature != "empty-signature" && signature.length >= MIN_SIGNATURE_LENGTH

    // ═══ 4) 根因排查树 ═══

    /** 假设类别 → 排查步骤（确定性指令形态）。 */
    private val INVESTIGATION_TREES: Map<String, List<InvestigationStep>> = mapOf(
        "空指针 / 未初始化" to listOf(
            InvestigationStep(1, "提取崩溃栈顶帧定位到具体对象", "拿到空对象的字段名与所在行"),
            InvestigationStep(2, "回溯该对象的赋值路径（构造/恢复/异步回填）", "找到未赋值或被置空的分支"),
            InvestigationStep(3, "检查生命周期：对象是否早于使用被释放", "确认 use-after-release 或未初始化")
        ),
        "并发 / 竞态" to listOf(
            InvestigationStep(1, "确认共享可变状态与访问线程清单", "列出全部读写点"),
            InvestigationStep(2, "在读写点加日志打点复现", "观察到交错访问的时序证据"),
            InvestigationStep(3, "审查锁/原子化的保护范围是否覆盖全部访问点", "找到未受保护的窗口")
        ),
        "资源泄漏" to listOf(
            InvestigationStep(1, "用内存/句柄画像工具抓增长曲线", "确认泄漏对象类型与增长速率"),
            InvestigationStep(2, "回溯该资源的分配点与释放路径配对", "找到缺失释放或异常路径逃逸"),
            InvestigationStep(3, "审查 try-finally / use{} 的覆盖完整性", "异常路径全部有释放保障")
        ),
        "编码 / 字符集" to listOf(
            InvestigationStep(1, "十六进制转储乱码样本", "确认实际字节序列"),
            InvestigationStep(2, "核对链路各环节的字符集声明（存储/传输/渲染）", "找到未声明或声明不一致的环节"),
            InvestigationStep(3, "统一为 UTF-8 并补往返测试", "乱码样本往返后无损")
        ),
        "配置 / 环境" to listOf(
            InvestigationStep(1, "对比问题环境与正常环境的配置差异", "锁定差异项"),
            InvestigationStep(2, "在问题环境单独回放该项配置", "复现/消除随该项变化"),
            InvestigationStep(3, "补配置校验与启动期防呆", "非法配置早失败并明示")
        ),
        "边界 / 溢出" to listOf(
            InvestigationStep(1, "确定输入的边界值域与实际越界值", "拿到触发值"),
            InvestigationStep(2, "审查边界判定的比较符方向与差一错误", "定位 off-by-one 或符号错误"),
            InvestigationStep(3, "补边界等价类测试", "边界两侧全覆盖")
        )
    )

    /**
     * 排查树查询：假设类别关键词命中（如「并发」「泄漏」）→ 步骤清单；
     * 未命中返回空（LLM 展开时自行编排）。
     */
    fun investigationTreeFor(hypothesisCategory: String): List<InvestigationStep> {
        for ((key, steps) in INVESTIGATION_TREES) {
            val keyWords = key.split(" / ", " /", "/ ", "/")
            if (keyWords.any { hypothesisCategory.contains(it) }) return steps
        }
        return emptyList()
    }

    /** 全部可用的排查树类别（报告展示 + 测试断言）。 */
    fun investigationCategories(): List<String> = INVESTIGATION_TREES.keys.toList()

    // ═══ 5) 升级规则 ═══

    /**
     * 升级判定补充通道：复现质量过低 + 严重度阻断的组合 ——「很严重但
     * 描述不清」的工单要升级给高级支持补现场，而不是躺在待办里。
     */
    fun needsFieldEscalation(
        severity: TemplateSeverity,
        reproScore: Int
    ): Pair<Boolean, String> = when {
        severity == TemplateSeverity.BLOCKER && reproScore < 40 ->
            true to "阻断级缺陷但复现质量不足（$reproScore/100）：升级补现场（日志/录屏/设备指纹）"
        severity == TemplateSeverity.IMPORTANT && reproScore < 25 ->
            true to "重要级缺陷复现质量过低（$reproScore/100）：先补复现步骤再排期"
        else -> false to ""
    }

    // ═══ 6) 回归测试指引 ═══

    /** 假设类别 → 回归测试指引（先写失败测试的形态建议）。 */
    private val REGRESSION_GUIDES: Map<String, RegressionTestGuide> = mapOf(
        "空指针 / 未初始化" to RegressionTestGuide(
            testName = "should not crash when {field} is missing during {scenario}",
            assertionTarget = "对象缺失时给出降级路径而非崩溃（断言异常不被抛出）",
            boundaryConditions = listOf("字段完全缺失", "字段为空串", "字段为延迟回填（异步未完成）")
        ),
        "并发 / 竞态" to RegressionTestGuide(
            testName = "should stay consistent under concurrent {operation}",
            assertionTarget = "并发 N 次操作后不变量成立（计数/集合大小/状态机）",
            boundaryConditions = listOf("单线程基线", "2 线程交错", "高并发压测（如 100 次）", "中途取消")
        ),
        "资源泄漏" to RegressionTestGuide(
            testName = "should release {resource} after {exception-scenario}",
            assertionTarget = "异常路径后资源计数归零（弱引用可回收 / 句柄数回落）",
            boundaryConditions = listOf("正常路径", "异常路径", "重复打开关闭", "中途取消")
        ),
        "编码 / 字符集" to RegressionTestGuide(
            testName = "should round-trip {text} without corruption",
            assertionTarget = "写入后读回与原文逐字节一致",
            boundaryConditions = listOf("纯中文", "中英混排", "emoji 与代理对", "空串与超长串")
        ),
        "配置 / 环境" to RegressionTestGuide(
            testName = "should fail fast with clear message on invalid {config}",
            assertionTarget = "非法配置在启动期抛出并携带可读说明",
            boundaryConditions = listOf("配置缺失", "配置格式错", "配置越界", "合法边界值")
        ),
        "边界 / 溢出" to RegressionTestGuide(
            testName = "should handle {input} at boundary {value}",
            assertionTarget = "边界值与边界两侧 ±1 的行为都符合预期",
            boundaryConditions = listOf("最小值", "最小值-1", "最大值", "最大值+1", "0 与空集合")
        )
    )

    /** 回归指引查询（未命中返回 null）。 */
    fun regressionGuideFor(hypothesisCategory: String): RegressionTestGuide? {
        for ((key, guide) in REGRESSION_GUIDES) {
            val keyWords = key.split(" / ", " /", "/ ", "/")
            if (keyWords.any { hypothesisCategory.contains(it) }) return guide
        }
        return null
    }

    // ═══ 常量 ═══

    private const val MIN_SIGNATURE_LENGTH = 8

    /** 规则目录（工坊能力预览与测试断言锚点）。 */
    val RULE_IDS: List<String> = listOf(
        "triage.priority-composite",
        "triage.escalation-security-data",
        "triage.escalation-field-repro",
        "triage.known-issue-hit",
        "triage.signature-duplicate-hint",
        "triage.investigation-tree",
        "triage.regression-guide"
    )
}
