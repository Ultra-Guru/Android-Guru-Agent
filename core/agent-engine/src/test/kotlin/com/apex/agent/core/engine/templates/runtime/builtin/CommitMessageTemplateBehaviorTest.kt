package com.apex.agent.core.engine.templates.runtime.builtin

import com.apex.agent.core.engine.templates.runtime.TemplateExecutionContext
import com.apex.agent.core.engine.templates.runtime.TemplateSeverity
import com.apex.agent.core.engine.templates.runtime.TemplateTemplateSnapshot
import com.apex.agent.core.engine.templates.runtime.TemplateTurnRequest
import com.apex.agent.core.engine.templates.runtime.TemplateVariableAssignment
import com.apex.agent.core.engine.templates.runtime.TemplateVariableReport
import com.apex.agent.core.engine.templates.runtime.TemplateVariableSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * PR 6 测试 · 提交信息生成行为。
 *
 * 覆盖：diff 状态机解析（多文件/重命名/二进制/截断防御）、类型打分、
 * 破坏性三通道、scope 推导、提交信息校验（格式/长度/折行/语气/中文）、
 * 修复器、行为全链路与端到端。
 */
class CommitMessageTemplateBehaviorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val behavior = CommitMessageTemplateBehavior()

    private fun context(changes: String, style: String = ""): TemplateExecutionContext {
        val assignments = buildList {
            add(TemplateVariableAssignment("changes", changes, TemplateVariableSource.USER))
            add(
                TemplateVariableAssignment(
                    "style", style,
                    if (style.isBlank()) TemplateVariableSource.MISSING else TemplateVariableSource.USER
                )
            )
        }
        return TemplateExecutionContext(
            template = TemplateTemplateSnapshot(
                id = "builtin_commit_message",
                name = "提交信息生成",
                content = "请为下面的代码变更生成一条 Git 提交信息。",
                declaredVariableNames = listOf("changes", "style"),
                categoryLabel = "PRODUCTIVITY",
                isBuiltIn = true
            ),
            variables = TemplateVariableReport(assignments)
        )
    }

    private suspend fun analyzeFor(changes: String) = behavior.analyze(
        context(changes), behavior.prepare(context(changes))
    )

    private fun requestStub() = TemplateTurnRequest(prompt = "stub", behaviorId = "builtin_commit_message")

    private val sampleDiff = """
        diff --git a/app/src/main/kotlin/com/demo/Login.kt b/app/src/main/kotlin/com/demo/Login.kt
        index 1111111..2222222 100644
        --- a/app/src/main/kotlin/com/demo/Login.kt
        +++ b/app/src/main/kotlin/com/demo/Login.kt
        @@ -10,4 +10,7 @@ class Login {
             fun submit() {
        -        validate()
        +        validate()
        +        audit()
        +        track()
             }
        }
        diff --git a/app/src/test/kotlin/com/demo/LoginTest.kt b/app/src/test/kotlin/com/demo/LoginTest.kt
        new file mode 100644
        --- /dev/null
        +++ b/app/src/test/kotlin/com/demo/LoginTest.kt
        @@ -0,0 +1,5 @@
        +class LoginTest {
        +    fun auditCalled() {}
        +    fun trackCalled() {}
        +}
    """.trimIndent()

    // ═══ diff 解析 ═══

    @Test
    fun `unified diff parses files stats and new file flags`() {
        val summary = CommitMessageDiffAnalyzer.parseUnifiedDiff(sampleDiff)
        assertTrue(summary.isDiffLike)
        assertEquals(2, summary.fileCount)
        val login = summary.files.first { it.displayPath.endsWith("Login.kt") }
        assertEquals(3, login.insertions)
        assertEquals(1, login.deletions)
        assertEquals(1, login.hunks)
        val test = summary.files.first { it.displayPath.endsWith("LoginTest.kt") }
        assertTrue(test.isNew)
        assertTrue(test.isPureAddition)
        assertEquals(7, summary.totalInsertions)
        assertEquals(1, summary.totalDeletions)
    }

    @Test
    fun `rename and binary hints are captured`() {
        val diff = """
            diff --git a/old.txt b/new.txt
            similarity index 90%
            rename from old.txt
            rename to new.txt
            diff --git a/logo.png b/logo.png
            index 333..444 100644
            Binary files a/logo.png and b/logo.png differ
        """.trimIndent()
        val summary = CommitMessageDiffAnalyzer.parseUnifiedDiff(diff)
        val renamed = summary.files.first { it.isRename }
        assertEquals("new.txt", renamed.displayPath)
        assertTrue(summary.files.any { it.isBinary })
    }

    @Test
    fun `prose input degrades defensively without diff grammar`() {
        val summary = CommitMessageDiffAnalyzer.parseUnifiedDiff("我们调整了登录逻辑，增加了审计埋点。")
        assertFalse(summary.isDiffLike)
        assertTrue(summary.isEmpty)
    }

    @Test
    fun `hunk-only truncated diff still counts lines`() {
        val diff = "@@ -1,2 +1,3 @@\n context\n-old\n+new\n+extra"
        val summary = CommitMessageDiffAnalyzer.parseUnifiedDiff(diff)
        assertTrue(summary.isDiffLike)
        assertEquals(1, summary.fileCount)
        assertEquals(2, summary.totalInsertions)
        assertEquals(1, summary.totalDeletions)
        assertTrue(summary.notes.isNotEmpty())
    }

    // ═══ 分类 ═══

    @Test
    fun `type classification votes by file evidence`() {
        val summary = CommitMessageDiffAnalyzer.parseUnifiedDiff(sampleDiff)
        val candidates = CommitMessageDiffAnalyzer.classifyChanges(summary)
        assertTrue(candidates.isNotEmpty())
        // 有新增源文件 → feat 应进前二候选
        assertTrue(candidates.take(2).any { it.type == "feat" })
        // 候选有证据与置信度
        assertTrue(candidates.first().evidence.isNotEmpty())
        assertTrue(candidates.first().confidence > 0.0)
    }

    @Test
    fun `pure docs change classifies as docs`() {
        val diff = """
            diff --git a/docs/api.md b/docs/api.md
            --- a/docs/api.md
            +++ b/docs/api.md
            @@ -1,2 +1,4 @@
            # API
            +新增端点说明
        """.trimIndent()
        val summary = CommitMessageDiffAnalyzer.parseUnifiedDiff(diff)
        val top = CommitMessageDiffAnalyzer.classifyChanges(summary).first()
        assertEquals("docs", top.type)
    }

    @Test
    fun `ci workflow change classifies as ci`() {
        val diff = """
            diff --git a/.github/workflows/ci.yml b/.github/workflows/ci.yml
            --- a/.github/workflows/ci.yml
            +++ b/.github/workflows/ci.yml
            @@ -3,2 +3,3 @@
            jobs:
            +  lint:
        """.trimIndent()
        val summary = CommitMessageDiffAnalyzer.parseUnifiedDiff(diff)
        assertEquals("ci", CommitMessageDiffAnalyzer.classifyChanges(summary).first().type)
    }

    // ═══ 破坏性变更 ═══

    @Test
    fun `breaking change detects removed public api`() {
        val diff = """
            diff --git a/api.kt b/api.kt
            --- a/api.kt
            +++ b/api.kt
            @@ -2,3 +2,1 @@
            class Api {
            -    public fun legacyHook()
            -    fun helper()
            }
        """.trimIndent()
        val summary = CommitMessageDiffAnalyzer.parseUnifiedDiff(diff)
        val signals = CommitMessageDiffAnalyzer.detectBreakingChanges(diff, summary)
        assertTrue(signals.any { it.kind == BreakingChangeSignal.Kind.REMOVED_PUBLIC_API })
    }

    @Test
    fun `breaking change detects explicit marker and contract files`() {
        val marker = CommitMessageDiffAnalyzer.detectBreakingChanges(
            "feat(api)!: 变更响应结构",
            CommitMessageDiffAnalyzer.parseUnifiedDiff("")
        )
        assertTrue(marker.any { it.kind == BreakingChangeSignal.Kind.EXPLICIT_MARKER })

        val contractDiff = """
            diff --git a/proto/user.proto b/proto/user.proto
            --- a/proto/user.proto
            +++ b/proto/user.proto
            @@ -1,2 +1,2 @@
            message User {
            -  string name = 1;
            +  string full_name = 1;
        """.trimIndent()
        val signals = CommitMessageDiffAnalyzer.detectBreakingChanges(
            contractDiff, CommitMessageDiffAnalyzer.parseUnifiedDiff(contractDiff)
        )
        assertTrue(signals.any { it.kind == BreakingChangeSignal.Kind.CONTRACT_CHANGE })
    }

    // ═══ scope ═══

    @Test
    fun `scope inference picks dominant directory`() {
        val summary = CommitMessageDiffAnalyzer.parseUnifiedDiff(sampleDiff)
        assertEquals("com/demo", CommitMessageDiffAnalyzer.inferScope(summary, sampleDiff))
    }

    @Test
    fun `scope falls back to kebab words in prose`() {
        val scope = CommitMessageDiffAnalyzer.inferScope(
            CommitMessageDiffAnalyzer.parseUnifiedDiff(""),
            "调整 user-profile 模块与 user-profile 缓存"
        )
        assertEquals("user-profile", scope)
    }

    // ═══ 提交信息校验 ═══

    @Test
    fun `validator accepts well formed message`() {
        val message = """
            feat(login): 提交后新增审计与埋点

            - 审计事件补齐
            - 埋点统一走 track()
            - 补充 LoginTest 覆盖
        """.trimIndent()
        val issues = CommitMessageDiffAnalyzer.validateCommitMessage(message)
        assertTrue(issues.none { it.kind == CommitMessageIssue.Kind.BAD_HEADER_FORMAT })
        assertTrue(issues.none { it.kind == CommitMessageIssue.Kind.SUBJECT_TOO_LONG })
    }

    @Test
    fun `validator flags format length tense and chinese suffix`() {
        val badHeader = "Fixed 登录提交问题（进行了修改）"
        val issues = CommitMessageDiffAnalyzer.validateCommitMessage(badHeader)
        assertTrue(issues.any { it.kind == CommitMessageIssue.Kind.BAD_HEADER_FORMAT })

        val longSubject = "fix: " + "修复".repeat(30) + "登录时的边界问题"
        assertTrue(
            CommitMessageDiffAnalyzer.validateCommitMessage(longSubject)
                .any { it.kind == CommitMessageIssue.Kind.SUBJECT_TOO_LONG }
        )

        val pastTense = "fix: added audit hook to login flow\n\n正文说明。"
        assertTrue(
            CommitMessageDiffAnalyzer.validateCommitMessage(pastTense)
                .any { it.kind == CommitMessageIssue.Kind.PAST_TENSE_SUBJECT }
        )

        val chineseRedundant = "fix: 进行了登录修复\n\n正文。"
        assertTrue(
            CommitMessageDiffAnalyzer.validateCommitMessage(chineseRedundant)
                .any { it.kind == CommitMessageIssue.Kind.CHINESE_REDUNDANT_SUFFIX }
        )
    }

    @Test
    fun `validator flags period and unwrapped body`() {
        val trailing = "fix: 修复登录。"
        assertTrue(
            CommitMessageDiffAnalyzer.validateCommitMessage(trailing)
                .any { it.kind == CommitMessageIssue.Kind.SUBJECT_ENDS_WITH_PERIOD }
        )
        val longBody = "fix: 修复登录\n\n" + "很长的正文行".repeat(20)
        assertTrue(
            CommitMessageDiffAnalyzer.validateCommitMessage(longBody)
                .any { it.kind == CommitMessageIssue.Kind.BODY_LINE_TOO_LONG }
        )
        val noBody = "fix: 修复登录"
        assertTrue(
            CommitMessageDiffAnalyzer.validateCommitMessage(noBody)
                .any { it.kind == CommitMessageIssue.Kind.EMPTY_BODY }
        )
    }

    // ═══ 修复器 ═══

    @Test
    fun `repair normalizes uppercase type and strips period`() {
        val (repaired, notes) = CommitMessageDiffAnalyzer.repairCommitMessage(
            "FIX: 修复登录问题。\n\n正文说明。"
        )
        assertTrue(repaired.startsWith("fix:"))
        assertFalse(repaired.firstLineEndsWithPeriod())
        assertTrue(notes.isNotEmpty())
    }

    private fun String.firstLineEndsWithPeriod(): Boolean =
        lines().first().endsWith("。") || lines().first().endsWith(".")

    @Test
    fun `repair wraps long body lines`() {
        val body = "fix: 修复登录\n\n" + "这是一个非常长的正文句子需要被折行处理。".repeat(6)
        val (repaired, _) = CommitMessageDiffAnalyzer.repairCommitMessage(body)
        val overlong = repaired.lines().drop(1).filter { it.length > 72 }
        assertTrue(overlong.isEmpty())
    }

    @Test
    fun `repair converts past tense to imperative`() {
        val (repaired, _) = CommitMessageDiffAnalyzer.repairCommitMessage(
            "fix: added audit hook\n\nBody text."
        )
        assertTrue(repaired.firstLine().contains("add audit"))
    }

    private fun String.firstLine(): String = lines().first()

    @Test
    fun `wrap line prefers latin space boundary`() {
        val wrapped = CommitMessageDiffAnalyzer.wrapLine(
            "word1 word2 word3 word4 word5 word6 word7 word8 word9 word10 word11", 30
        )
        assertTrue(wrapped.size >= 2)
        assertTrue(wrapped.all { it.length <= 30 })
        // 词未被硬切（首行以完整词结尾）
        assertTrue(wrapped.first().split(" ").none { it.length <= 2 && it != "to" })
    }

    // ═══ 行为全链路 ═══

    @Test
    fun `analyze produces diff findings and hints`() = runTest {
        val analysis = analyzeFor(sampleDiff)
        val ruleIds = analysis.findings.map { it.ruleId }
        assertTrue(ruleIds.contains("commit.diff-parsed"))
        assertTrue(ruleIds.contains("commit.type-candidate"))
        assertTrue(analysis.enrichedHints.any { it.contains("提交风格") })
        assertTrue(analysis.enrichedHints.any { it.contains("scope 建议") })
        assertEquals(2, analysis.metadata["fileCount"]?.toIntOrNull())
    }

    @Test
    fun `analyze flags breaking changes as blocker`() = runTest {
        val breakingDiff = """
            diff --git a/core/api.kt b/core/api.kt
            --- a/core/api.kt
            +++ b/core/api.kt
            @@ -2,3 +2,1 @@
            class Api {
            -    public fun legacyHook()
            }
        """.trimIndent()
        val analysis = analyzeFor(breakingDiff)
        val breaking = analysis.findings.filter { it.ruleId == "commit.breaking-change" }
        assertTrue(breaking.isNotEmpty())
        assertTrue(breaking.all { it.severity == TemplateSeverity.BLOCKER })
    }

    @Test
    fun `compose injects change digest appendix`() = runTest {
        val ctx = context(sampleDiff)
        val prepared = behavior.prepare(ctx)
        val analysis = behavior.analyze(ctx, prepared)
        val request = behavior.composePrompt(ctx, prepared, analysis)
        assertTrue(request.prompt.contains("确定性分析附录"))
        assertTrue(request.prompt.contains("已解析统一 diff"))
        assertTrue(request.prompt.contains("类型判定首选"))
    }

    @Test
    fun `validate extracts fenced message and reports its issues`() {
        val raw = """
            ## 提交信息
            ```
            FIXED 登录问题
            ```

            ## 变更解读
            调整提交链路。
        """.trimIndent()
        val report = behavior.validateOutput(raw, context("x"), requestStub())
        // 围栏里的大写类型 + 无冒号 → 违规
        assertTrue(report.violations.any { it.detail.contains("首行不符合") || it.detail.contains("类型") })
    }

    @Test
    fun `repair fixes fenced commit message in place`() {
        val raw = """
            ## 提交信息
            ```
            FIX: 修复登录。
            ```

            ## 变更解读
            说明。
        """.trimIndent()
        val report = behavior.validateOutput(raw, context("x"), requestStub())
        val repaired = behavior.repairOutput(raw, report, context("x"))
        assertTrue(repaired.contains("fix: 修复登录"))
    }

    @Test
    fun `render adds normalized message section and summary`() {
        val raw = """
            ## 提交信息
            ```
            feat(login): 提交后新增审计与埋点

            - 审计事件补齐
            - 埋点统一
            ```

            ## 变更解读
            登录链路增强。
        """.trimIndent()
        val rendered = behavior.renderReport(raw, context("x"), requestStub())
        assertTrue(rendered.sections.any { it.title == "规范化提交信息" })
        assertEquals("feat", rendered.summary["commitType"])
        assertEquals("login", rendered.summary["commitScope"])
        assertEquals("true", rendered.summary["subjectOk"])
    }

    // ═══ 草稿生成器 ═══

    @Test
    fun `draft generator composes conventional message from facts`() {
        val summary = CommitMessageDiffAnalyzer.parseUnifiedDiff(sampleDiff)
        val draft = CommitDraftGenerator.generate(summary, "feat", "com/demo", emptyList())
        assertTrue(draft.header.startsWith("feat(com/demo): "))
        assertTrue(draft.header.length <= 72)
        assertTrue(draft.bodyLines.isNotEmpty())
        assertTrue(draft.bodyLines.first().startsWith("- "))
        assertTrue(draft.footer == null)
        // 完整文本通过校验（header 格式 + 主题长度）
        val issues = CommitMessageDiffAnalyzer.validateCommitMessage(draft.toMessage())
        assertTrue(issues.none { it.kind == CommitMessageIssue.Kind.BAD_HEADER_FORMAT })
        assertTrue(issues.none { it.kind == CommitMessageIssue.Kind.SUBJECT_TOO_LONG })
    }

    @Test
    fun `draft generator forces footer on breaking change`() {
        val breakingDiff = """
            diff --git a/core/api.kt b/core/api.kt
            --- a/core/api.kt
            +++ b/core/api.kt
            @@ -2,3 +2,1 @@
            class Api {
            -    public fun legacyHook()
            }
        """.trimIndent()
        val summary = CommitMessageDiffAnalyzer.parseUnifiedDiff(breakingDiff)
        val breaking = CommitMessageDiffAnalyzer.detectBreakingChanges(breakingDiff, summary)
        val draft = CommitDraftGenerator.generate(summary, "refactor", "core", breaking)
        assertTrue(draft.header.contains("refactor(core)!:"))
        assertNotNull(draft.footer)
        assertTrue(draft.footer!!.startsWith("BREAKING CHANGE:"))
    }

    @Test
    fun `draft subject is truncated to budget`() {
        val summary = DiffSummary(
            files = listOf(
                DiffFileChange(
                    oldPath = "a/really/long/path/module/with/many/segments/ComponentName.kt",
                    newPath = "a/really/long/path/module/with/many/segments/ComponentName.kt",
                    insertions = 10, deletions = 0, hunks = 1
                )
            ),
            hunkHeaders = listOf("@@ -1 +1 @@"),
            notes = emptyList(),
            isDiffLike = true
        )
        val subject = CommitDraftGenerator.subjectFor(summary, "feat", false)
        assertTrue(subject.length <= CommitMessageDiffAnalyzer.SUBJECT_MAX_CHARS)
    }

    @Test
    fun `file grouping orders by impact`() {
        val summary = CommitMessageDiffAnalyzer.parseUnifiedDiff(sampleDiff)
        val groups = CommitDraftGenerator.groupFiles(summary)
        assertTrue(groups.size >= 2)
        assertTrue(groups.first().files.isNotEmpty())
    }

    @Test
    fun `analyze injects deterministic draft into hints`() = runTest {
        val analysis = analyzeFor(sampleDiff)
        assertTrue(analysis.enrichedHints.any { it.contains("代码侧草稿") })
        assertTrue(analysis.enrichedHints.any { it.contains("feat") })
    }

    // ═══ 端到端 ═══

    @Test
    fun `full turn through runtime`() = runTest {
        val dir = tmp.newFolder("commit-registry")
        val registry = com.apex.agent.core.engine.templates.PromptTemplateRegistry(dir)
        registry.reseedBuiltIns()
        val runtime = com.apex.agent.core.engine.templates.runtime.TemplateRuntime(
            registry = registry,
            behaviors = mapOf(behavior.behaviorId to behavior)
        )
        val activation = runtime.activate(
            "builtin_commit_message",
            mapOf("changes" to sampleDiff, "style" to "conventional commits")
        )
        assertTrue(activation.ok)
        val outcome = runtime.prepareTurn(activation)
        assertTrue(outcome.ok)
        assertTrue(outcome.request!!.prompt.contains("确定性分析附录"))
        val report = runtime.processResponse(
            activation,
            """
            ## 提交信息
            ```
            feat(login): 提交后新增审计与埋点

            - 审计与埋点补齐
            ```

            ## 变更解读
            登录链路增强，附带测试。
            """.trimIndent(),
            outcome.request
        )
        assertEquals("feat", report.summary["commitType"])
        assertTrue(report.trace!!.allOk)
    }
}
