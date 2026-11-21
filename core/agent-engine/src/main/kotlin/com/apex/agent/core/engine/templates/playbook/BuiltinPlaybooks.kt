package com.apex.agent.core.engine.templates.playbook

import com.apex.agent.core.engine.templates.TemplateVariable

/**
 * ═══ 内置任务剧本库（5 套，中文正文）═══
 *
 * 与 BuiltinPromptTemplates 同纪律：出厂单源、内置不可改（注册表拒绝
 * save/delete）、reseedBuiltIns(force=true) 可还原、导入永不伪造内置。
 * id 全部满足 PLAYBOOK_ID_PATTERN。
 *
 * 幕推进协议契约（master 里声明、PlaybookAdvanceJudge 解析，两端措辞
 * 必须一致）：每轮输出以「【幕k·幕标题】」章节头开始；本幕产出满足验收
 * 后以下一幕章节头继续；全部幕完成输出「《剧本完成》」。
 */
object BuiltinPlaybooks {

    val ALL: List<PlaybookDefinition> = listOf(
        codeReview(),
        testCases(),
        bugFix(),
        refactor(),
        commitMessage()
    )

    /** 已知内置剧本 id 集合（导入防御与 isBuiltIn 规范化判定）。 */
    val BUILTIN_IDS: Set<String> = ALL.map { it.id }.toSet()

    /** 是否为已知内置剧本 id。 */
    fun isKnownBuiltInId(id: String): Boolean = id in BUILTIN_IDS

    // ═══ 出厂定义 ═══

    private fun codeReview() = PlaybookDefinition(
        id = "builtin_playbook_code_review",
        name = "代码审查剧本",
        description = "侦察前置的分幕评审：预检侦察→正确性架构→安全性能→分级结论",
        tags = listOf("代码", "评审", "质量"),
        isBuiltIn = true,
        variables = listOf(
            TemplateVariable("language", "代码语言", required = false, defaultValue = "未指定"),
            TemplateVariable("code", "待评审代码", required = true)
        ),
        triggers = listOf(
            TriggerRule(
                keywords = listOf("审查", "评审", "review", "帮我看看", "看看这段", "有什么问题", "帮我检查"),
                score = 4
            ),
            TriggerRule(
                keywords = listOf("这段代码", "问题吗", "漏洞", "隐患"),
                regexes = listOf("""(review|审查|评审).{0,16}(代码|code|实现|改动)"""),
                score = 2
            )
        ),
        master = """
            你是「代码审查剧本」驱动的资深评审 agent。评审对象代码语言：{{language}}。

            硬约束：
            1. 只评审真实存在的事实——每条发现必须带位置（行号或函数名）与证据（引用原文），禁止编造；
            2. 严重度分级：🔴 阻断（正确性/安全，必须处理）/ 🟡 重要（显著影响质量）/ 🟢 次要（风格整洁）；
            3. 不顺手改代码：本剧本只产出评审结论，修复走用户确认。

            幕推进协议（必须遵守）：
            - 每轮输出以章节头「【幕k·幕标题】」开始（幕标题与当前幕完全一致）；
            - 本幕产出满足验收后，下一轮以「【幕k+1·下一幕标题】」继续；
            - 末幕产出末尾输出「《剧本完成》」。
        """.trimIndent(),
        stages = listOf(
            PlaybookStage(
                id = "recon",
                title = "预检侦察",
                reconId = "code_review_heuristics",
                subPrompt = """
                    本幕任务：汇总侦察附录中的确定性发现，逐条核对后形成预检报告。

                    要求：
                    1. 侦察附录的每条发现给出「成立 / 误报」判定（附一句理由）——启发式规则有误报率，你是核对人不是转发器；
                    2. 列出值得在后续幕深查的 Top 清单（按严重度排序）；
                    3. 若侦察附录为空，明确说明未命中启发式规则，并从形态信号（文件大小/结构）给出观察项。
                """.trimIndent(),
                acceptance = listOf("侦察发现逐条判定", "待深查清单", "严重度排序")
            ),
            PlaybookStage(
                id = "correctness",
                title = "正确性与架构",
                subPrompt = """
                    本幕任务：深查正确性与架构问题。

                    检查维度：逻辑错误、边界条件、空值/异常路径、并发与状态一致性、错误处理、分层与职责归属。
                    每条发现 = 严重度标记 + 位置 + 证据 + 具体修复建议。
                """.trimIndent(),
                acceptance = listOf("发现带位置与证据", "修复建议可执行", "无发现时明确说明并给出依据")
            ),
            PlaybookStage(
                id = "security_perf",
                title = "安全与性能",
                subPrompt = """
                    本幕任务：深查安全与性能问题。

                    安全维度：注入、越权、敏感数据暴露、凭据硬编码、不安全反序列化。
                    性能维度：算法复杂度、重复计算、内存泄漏风险、IO 与主线程阻塞。
                    发现格式与上一幕一致；不适用维度明确写「不适用」及原因。
                """.trimIndent(),
                acceptance = listOf("安全与性能两维度各自覆盖", "不适用维度说明原因")
            ),
            PlaybookStage(
                id = "verdict",
                title = "结论与修复清单",
                subPrompt = """
                    本幕任务：汇总全部幕的发现，输出最终评审结论。

                    输出结构：
                    1. 总体结论：通过 / 有条件通过 / 需修改（一句话理由）；
                    2. 问题清单：按 🔴阻断 → 🟡重要 → 🟢次要 分级，每条含位置、原因与修复建议；
                    3. 关键修复示例：仅对 🔴 项给出修正代码片段。
                    末尾输出「《剧本完成》」。
                """.trimIndent(),
                acceptance = listOf("明确结论行", "三级分级清单", "《剧本完成》标记")
            )
        )
    )

    private fun testCases() = PlaybookDefinition(
        id = "builtin_playbook_test_cases",
        name = "测试用例剧本",
        description = "等价类先行的分幕测试：等价类边界→用例表→代码与验证",
        tags = listOf("测试", "单元测试"),
        isBuiltIn = true,
        variables = listOf(
            TemplateVariable("framework", "测试框架", required = false, defaultValue = "JUnit"),
            TemplateVariable("code", "被测代码", required = true),
            TemplateVariable("language", "代码语言", required = false, defaultValue = "未指定")
        ),
        triggers = listOf(
            TriggerRule(
                keywords = listOf("单测", "单元测试", "测试用例", "写测试", "覆盖率"),
                score = 4
            ),
            TriggerRule(
                keywords = listOf("测试"),
                regexes = listOf("""(unit\s*test|test\s*case)""", """测试.{0,8}(覆盖|用例|代码)"""),
                score = 2
            )
        ),
        master = """
            你是「测试用例剧本」驱动的测试工程 agent。被测代码语言：{{language}}，测试框架：{{framework}}。

            硬约束：
            1. 每个测试只验证一个行为，命名说明意图；
            2. 先分析后写码：等价类没列全不许进入写码幕；
            3. 测试不依赖网络与真实文件系统（必要时手写 fake）。

            幕推进协议（必须遵守）：
            - 每轮输出以章节头「【幕k·幕标题】」开始（幕标题与当前幕完全一致）；
            - 本幕产出满足验收后，下一轮以「【幕k+1·下一幕标题】」继续；
            - 末幕产出末尾输出「《剧本完成》」。
        """.trimIndent(),
        stages = listOf(
            PlaybookStage(
                id = "equivalence",
                title = "等价类与边界分析",
                reconId = "test_cases_analyzer",
                subPrompt = """
                    本幕任务：分析被测代码的等价类与边界。

                    要求：
                    1. 逐参数列出等价类（正常值/边界值/异常值），与侦察附录对照补漏；
                    2. 标注边界值（空/零/极值/溢出/并发时序）；
                    3. 输出「分析→用例」的映射预告（下一幕每条用例须归属某个等价类）。
                """.trimIndent(),
                acceptance = listOf("逐参数等价类", "边界值标注", "用例映射预告")
            ),
            PlaybookStage(
                id = "case_table",
                title = "用例表",
                subPrompt = """
                    本幕任务：产出完整用例表。

                    每条用例 = 用例名（语义命名）/ 归属等价类 / 前置条件 / 步骤 / 期望结果。
                    要求覆盖：正常路径、边界值、异常路径三类都不得缺席；条数以覆盖全部等价类为准，不为凑数堆用例。
                """.trimIndent(),
                acceptance = listOf("三类路径全覆盖", "每条用例归属等价类", "单行为断言")
            ),
            PlaybookStage(
                id = "code_and_verify",
                title = "测试代码与验证指引",
                subPrompt = """
                    本幕任务：把用例表落成 {{framework}} 测试代码。

                    要求：
                    1. 完整可粘贴的测试文件（含 import 与类骨架）；
                    2. 给出运行命令；无法在本机运行时写明需要的环境与手写 fake 清单；
                    3. 末尾输出「《剧本完成》」。
                """.trimIndent(),
                acceptance = listOf("完整测试文件", "运行命令或环境说明", "《剧本完成》标记")
            )
        )
    )

    private fun bugFix() = PlaybookDefinition(
        id = "builtin_playbook_bug_fix",
        name = "Bug 定位修复剧本",
        description = "取证先行的分幕排障：复现取证→根因假设→最小修复→回归防复发",
        tags = listOf("修复", "排障"),
        isBuiltIn = true,
        variables = listOf(
            TemplateVariable("symptom", "症状描述", required = false, defaultValue = "见任务输入"),
            TemplateVariable("language", "代码语言", required = false, defaultValue = "未指定")
        ),
        triggers = listOf(
            TriggerRule(
                keywords = listOf("修复", "报错", "崩溃", "exception", "stacktrace", "堆栈", "排错", "排障", "bug", "anr"),
                score = 4
            ),
            TriggerRule(
                keywords = listOf("异常", "报错", "不对", "失败", "定位"),
                regexes = listOf("""修复.{0,12}(bug|缺陷|问题|错误)""", """(定位|排查).{0,8}(原因|根因|问题)"""),
                score = 2
            )
        ),
        master = """
            你是「Bug 定位修复剧本」驱动的排障 agent。症状：{{symptom}}。

            硬约束：
            1. 取证优先：没有证据不许给结论——每个根因假设必须指明验证方法与证伪信号；
            2. 最小修复：只改根因相关的最小范围，不顺手重构、不动无关代码；
            3. 诚实边界：证据不足以定位时明确说「需要补充什么」，不猜。

            幕推进协议（必须遵守）：
            - 每轮输出以章节头「【幕k·幕标题】」开始（幕标题与当前幕完全一致）；
            - 本幕产出满足验收后，下一轮以「【幕k+1·下一幕标题】」继续；
            - 末幕产出末尾输出「《剧本完成》」。
        """.trimIndent(),
        stages = listOf(
            PlaybookStage(
                id = "evidence",
                title = "复现取证",
                reconId = "bug_triage",
                subPrompt = """
                    本幕任务：建立可复现的证据基线。

                    要求：
                    1. 整理症状/环境/复现步骤（无法复现时列出缺失信息清单）；
                    2. 用读日志/读文件工具取证：堆栈、日志关键行、相关代码段（引用原文+位置）；
                    3. 结合侦察附录的分级与排查建议，给出初步影响面评估。
                """.trimIndent(),
                acceptance = listOf("复现路径或缺失清单", "日志/堆栈证据引用", "影响面评估")
            ),
            PlaybookStage(
                id = "hypotheses",
                title = "根因假设排序",
                subPrompt = """
                    本幕任务：基于证据提出根因假设并排序。

                    每个假设 = 机制解释（为什么会产出这个症状）+ 验证方法（跑什么/看什么）+ 证伪信号（看到什么说明假设错）。
                    要求 ≥2 个假设；按「与证据吻合度 × 验证成本」排序并给出理由；指明首选假设。
                """.trimIndent(),
                acceptance = listOf("至少两个假设", "每假设有验证方法与证伪信号", "排序理由")
            ),
            PlaybookStage(
                id = "minimal_fix",
                title = "最小修复",
                subPrompt = """
                    本幕任务：对首选假设实施最小修复（假设在上一幕被验证或用户已确认）。

                    要求：
                    1. 修复 diff/代码片段 + 一句话说明「为什么这是根因而非症状」；
                    2. 不顺手改无关代码；必要的连带改动逐条列出理由；
                    3. 若验证阶段证伪了首选假设，先回假设幕补充取证，不带猜修复。
                """.trimIndent(),
                acceptance = listOf("根因说明", "最小改动范围", "连带改动理由")
            ),
            PlaybookStage(
                id = "regression",
                title = "回归验证与防复发",
                subPrompt = """
                    本幕任务：验证修复并防复发。

                    要求：
                    1. 验证步骤（复现路径逐步重跑）+ 预期观察；
                    2. 回归测试建议（一个能锁住本 bug 的测试用例骨架）；
                    3. 同类风险扫描：代码库里是否还有同款模式（给出搜索线索）；
                    4. 末尾输出「《剧本完成》」。
                """.trimIndent(),
                acceptance = listOf("验证步骤", "回归测试建议", "同类风险线索", "《剧本完成》标记")
            )
        )
    )

    private fun refactor() = PlaybookDefinition(
        id = "builtin_playbook_refactor",
        name = "重构方案剧本",
        description = "度量先行的分幕重构：现状测绘→方案回滚→分批执行",
        tags = listOf("重构", "结构"),
        isBuiltIn = true,
        variables = listOf(
            TemplateVariable("goal", "重构目标", required = false, defaultValue = "改善结构与可维护性"),
            TemplateVariable("language", "代码语言", required = false, defaultValue = "未指定")
        ),
        triggers = listOf(
            TriggerRule(
                keywords = listOf("重构", "refactor", "拆分", "解耦", "技术债"),
                score = 4
            ),
            TriggerRule(
                keywords = listOf("优化结构", "代码太长", "god", "太乱"),
                score = 2
            )
        ),
        master = """
            你是「重构方案剧本」驱动的重构 agent。重构目标：{{goal}}。

            硬约束：
            1. 度量先行：先拿数据再开方案——不凭感觉宣布「结构不好」；
            2. 行为保持：重构不改可观察行为，每一步必须可独立验证、可回滚；
            3. 小步提交：方案必须切成可独立落地的批次。

            幕推进协议（必须遵守）：
            - 每轮输出以章节头「【幕k·幕标题】」开始（幕标题与当前幕完全一致）；
            - 本幕产出满足验收后，下一轮以「【幕k+1·下一幕标题】」继续；
            - 末幕产出末尾输出「《剧本完成》」。
        """.trimIndent(),
        stages = listOf(
            PlaybookStage(
                id = "survey",
                title = "现状测绘",
                reconId = "refactor_plan",
                subPrompt = """
                    本幕任务：测绘代码现状。

                    要求：
                    1. 结合侦察附录（复杂度/重复/结构度量）给出问题清单，逐条标注证据；
                    2. 补充度量覆盖不到的维度：耦合方向、依赖层次、命名一致性；
                    3. 明确「本次重构不动」的范围（防扩散）。
                """.trimIndent(),
                acceptance = listOf("度量证据清单", "补充维度观察", "不动范围声明")
            ),
            PlaybookStage(
                id = "plan",
                title = "方案与回滚",
                subPrompt = """
                    本幕任务：产出重构方案。

                    每个步骤 = 改动内容 + 动机（对应现状测绘哪条问题）+ 验证方法 + 回滚方式；
                    步骤按依赖排序；给出整体风险等级（高/中/低）与最大风险点的缓解措施。
                """.trimIndent(),
                acceptance = listOf("步骤带动机与回滚", "依赖排序", "风险与缓解")
            ),
            PlaybookStage(
                id = "batches",
                title = "分批执行",
                subPrompt = """
                    本幕任务：把方案切成执行批次。

                    每批 = 改动点清单 + 验证命令 + 完成判据；批与批之间可暂停等用户确认；
                    第一批必须是「低风险可独立验证」的批次；末尾输出「《剧本完成》」。
                """.trimIndent(),
                acceptance = listOf("批次含验证命令", "批间可停", "《剧本完成》标记")
            )
        )
    )

    private fun commitMessage() = PlaybookDefinition(
        id = "builtin_playbook_commit_message",
        name = "Git 提交信息剧本",
        description = "单轮合成：变更解读 → conventional commit 生成（含格式验收）",
        tags = listOf("git", "提交"),
        isBuiltIn = true,
        mode = PlaybookExecutionMode.COMPOSED,
        triggers = listOf(
            TriggerRule(
                keywords = listOf("提交信息", "commit message", "commit 信息", "提交说明", "commit msg"),
                score = 4,
                contextGate = TriggerContextGate.WORKSPACE_PRESENT
            ),
            TriggerRule(
                keywords = listOf("帮我提交", "写个提交", "commit"),
                score = 2,
                contextGate = TriggerContextGate.WORKSPACE_PRESENT
            )
        ),
        master = """
            你是「Git 提交信息剧本」驱动的提交助理。

            硬约束：
            1. 信息必须来自真实变更——先跑 git diff / git log 取证，禁止凭任务描述臆造变更内容；
            2. Conventional Commits：<type>(<scope>): <subject>，type ∈ feat/fix/refactor/docs/test/chore；
            3. 主题行 ≤50 字、祈使语气、结尾无句号；正文解释「为什么」而非罗列「改了什么」。

            输出结构（单轮合成，全部产出）：
            1. 变更解读：diff 的要点归纳（3 条以内）；
            2. 提交信息：最终 conventional commit 文本（可直接复制）；
            3. 格式自检：主题行长度/type 归属/语气逐项打勾。
        """.trimIndent(),
        stages = listOf(
            PlaybookStage(
                id = "compose",
                title = "变更解读与提交信息",
                subPrompt = """
                    本幕任务：取证并生成提交信息。

                    要求：先跑 git diff --staged（无暂存则 git diff）与 git log -5 对齐仓库既有提交风格；
                    按 master 的输出结构给出变更解读、提交信息与格式自检；
                    末尾输出「《剧本完成》」。
                """.trimIndent(),
                acceptance = listOf("真实 diff 取证", "conventional 格式", "格式自检")
            )
        )
    )
}
