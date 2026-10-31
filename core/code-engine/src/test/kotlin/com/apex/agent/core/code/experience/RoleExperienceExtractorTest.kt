package com.apex.agent.core.code.experience

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RoleExperienceExtractor] 确定性规则引擎测试。
 *
 * 覆盖：场景分类（中英关键词/多标签上限/空输入）、工具序列压缩（相邻
 * 折叠/非相邻保留/硬上限）、注意点规则 R1-R7 逐条触发与叠加、错误类别
 * 建议表、extract 全流程字段组装。
 *
 * 核心断言口径：**同输入同输出**（确定性），不依赖时钟/环境。
 */
class RoleExperienceExtractorTest {

    private fun raw(
        goal: String = "修复登录页崩溃",
        outcome: String = "COMPLETED",
        iterations: Int = 3,
        toolCallCount: Int = 5,
        toolFailures: Int = 0,
        orderedTools: List<String> = listOf("CodeRead", "CodeEdit", "CodeEdit", "CodeCheck"),
        toolCounts: Map<String, Int> = mapOf("CodeRead" to 1, "CodeEdit" to 2, "CodeCheck" to 1),
        filesTouchedCount: Int = 1,
        errorMessage: String? = null
    ) = ExperienceRawInput(
        roleId = "builtin_coding_git",
        goal = goal,
        workspaceName = "Alpha",
        outcome = outcome,
        iterations = iterations,
        toolCallCount = toolCallCount,
        toolFailures = toolFailures,
        orderedTools = orderedTools,
        toolCounts = toolCounts,
        filesTouchedCount = filesTouchedCount,
        durationMs = 60_000L,
        errorMessage = errorMessage,
        clock = { 1234L }
    )

    // ═══ 场景分类 ═══

    @Test
    fun `scenario tags match bilingual keywords`() {
        assertEquals(listOf("bug_fix"), RoleExperienceExtractor.scenarioTagsOf("修复登录页崩溃"))
        assertEquals(listOf("bug_fix"), RoleExperienceExtractor.scenarioTagsOf("Fix the crash in login"))
        assertEquals(listOf("git_workflow"), RoleExperienceExtractor.scenarioTagsOf("git rebase 修一下分支"))
        assertEquals(listOf("docs"), RoleExperienceExtractor.scenarioTagsOf("更新 README 文档"))
    }

    @Test
    fun `scenario tags capped at two with stable order`() {
        // 同时命中 bug_fix + test + refactor → 取枚举声明序前 2（bug_fix → refactor）。
        val tags = RoleExperienceExtractor.scenarioTagsOf("修复 test 覆盖并重构模块")
        assertEquals(2, tags.size)
        assertEquals("bug_fix", tags.first())
        assertEquals("refactor", tags[1])
    }

    @Test
    fun `scenario tags empty for blank or unmatched goal`() {
        assertTrue(RoleExperienceExtractor.scenarioTagsOf("   ").isEmpty())
        assertTrue(RoleExperienceExtractor.scenarioTagsOf("随便聊聊天气").isEmpty())
    }

    @Test
    fun `feature tag not triggered by bare add`() {
        // 裸 add（英文太常见）不该打 FEATURE —— 关键词表刻意排除。
        assertTrue(
            RoleExperienceExtractor.scenarioTagsOf("add a comment here")
                .none { it == "feature" }
        )
    }

    // ═══ 工具序列压缩 ═══

    @Test
    fun `adjacent duplicate tools collapse with count`() {
        val seq = RoleExperienceExtractor.compressToolSequence(
            listOf("Shell", "Shell", "Shell", "Read", "Edit", "Edit")
        )
        assertEquals(listOf("Shell×3", "Read", "Edit×2"), seq)
    }

    @Test
    fun `non adjacent round trips preserve order`() {
        val seq = RoleExperienceExtractor.compressToolSequence(
            listOf("Read", "Edit", "Read", "Check")
        )
        assertEquals(listOf("Read", "Edit", "Read", "Check"), seq)
    }

    @Test
    fun `tool sequence hard capped`() {
        val long = (1..60).map { "Tool$it" }
        assertEquals(
            RoleExperience.TOOL_SEQUENCE_MAX,
            RoleExperienceExtractor.compressToolSequence(long).size
        )
    }

    // ═══ 注意点规则引擎 ═══

    @Test
    fun `R1 failure note includes error line and category advice`() {
        val notes = RoleExperienceExtractor.notesFor(
            raw(outcome = "FAILED", errorMessage = "编译失败：Unresolved reference: LoginVm\n堆栈省略")
        )
        assertTrue(notes.contains("上次失败"))
        assertTrue(notes.contains("编译失败"))
        assertTrue(notes.contains("小步编译验证"))
    }

    @Test
    fun `R1 advice categories route by keyword`() {
        // 类别表逐族抽测（网络/权限/未找到/JSON）。
        assertTrue(
            RoleExperienceExtractor.notesFor(raw(outcome = "FAILED", errorMessage = "network unreachable"))
                .contains("探测连通")
        )
        assertTrue(
            RoleExperienceExtractor.notesFor(raw(outcome = "FAILED", errorMessage = "permission denied"))
                .contains("权限链路")
        )
        assertTrue(
            RoleExperienceExtractor.notesFor(raw(outcome = "FAILED", errorMessage = "File not found: cfg"))
                .contains("列目录确认路径")
        )
        assertTrue(
            RoleExperienceExtractor.notesFor(raw(outcome = "FAILED", errorMessage = "bad json parse"))
                .contains("schema 校验")
        )
    }

    @Test
    fun `R1 failure without error text gets generic advice`() {
        val notes = RoleExperienceExtractor.notesFor(raw(outcome = "FAILED", errorMessage = null))
        assertTrue(notes.contains("无错误详情"))
    }

    @Test
    fun `R2 high tool failure rate warns`() {
        val notes = RoleExperienceExtractor.notesFor(
            raw(toolCallCount = 10, toolFailures = 5)
        )
        assertTrue(notes.contains("工具失败率 50%"))
    }

    @Test
    fun `R2 not triggered below threshold or small sample`() {
        // 失败率 20% → 不触发。
        assertTrue(
            !RoleExperienceExtractor.notesFor(raw(toolCallCount = 10, toolFailures = 2))
                .contains("工具失败率")
        )
        // 样本 < 3 → 不触发（1/1 = 100% 也不告警）。
        assertTrue(
            !RoleExperienceExtractor.notesFor(raw(toolCallCount = 2, toolFailures = 2))
                .contains("工具失败率")
        )
    }

    @Test
    fun `R3 clean success path is reusable`() {
        val notes = RoleExperienceExtractor.notesFor(raw(toolCallCount = 6, toolFailures = 0))
        assertTrue(notes.contains("一次通过"))
    }

    @Test
    fun `R4 dominant tools are named`() {
        val notes = RoleExperienceExtractor.notesFor(
            raw(
                toolCallCount = 10,
                toolCounts = mapOf("ShellExecute" to 7, "CodeEdit" to 3),
                orderedTools = (1..7).map { "ShellExecute" } + listOf("CodeEdit", "CodeEdit", "CodeEdit")
            )
        )
        assertTrue(notes.contains("主力工具"))
        assertTrue(notes.contains("ShellExecute(7)"))
    }

    @Test
    fun `R5 long task suggests todo breakdown`() {
        val notes = RoleExperienceExtractor.notesFor(raw(iterations = 9))
        assertTrue(notes.contains("TODO 拆步"))
    }

    @Test
    fun `R6 broad change surface warns`() {
        val notes = RoleExperienceExtractor.notesFor(raw(filesTouchedCount = 6))
        assertTrue(notes.contains("改动面广"))
    }

    @Test
    fun `R7 lightweight shortcut`() {
        val notes = RoleExperienceExtractor.notesFor(
            raw(iterations = 1, toolCallCount = 2)
        )
        assertTrue(notes.contains("轻量任务"))
    }

    @Test
    fun `notes bounded to max chars`() {
        val notes = RoleExperienceExtractor.notesFor(
            raw(
                outcome = "FAILED",
                iterations = 20,
                toolCallCount = 50,
                toolFailures = 30,
                filesTouchedCount = 9,
                errorMessage = "x".repeat(2000)
            )
        )
        assertTrue(notes.length <= RoleExperience.NOTES_MAX_CHARS)
    }

    // ═══ extract 全流程 ═══

    @Test
    fun `extract assembles structured card from raw input`() {
        val exp = RoleExperienceExtractor.extract(
            raw(
                goal = "修复登录页崩溃：复现并定位根因",
                orderedTools = listOf("Read", "Edit", "Edit", "Check"),
                toolCounts = mapOf("Read" to 1, "Edit" to 2, "Check" to 1)
            )
        )
        assertEquals("builtin_coding_git", exp.roleId)
        assertEquals("修复登录页崩溃：复现并定位根因", exp.scenario)
        assertEquals(listOf("bug_fix"), exp.scenarioTags)
        assertEquals(listOf("Read", "Edit×2", "Check"), exp.toolSequence)
        assertEquals("COMPLETED", exp.outcome)
        assertEquals(1234L, exp.createdAt)
        assertEquals(1, exp.hitCount)
        assertEquals(0L, exp.lastUsedAt)
        assertTrue(exp.id.startsWith("exp_1234_"))
    }

    @Test
    fun `extract truncates long scenario to cap`() {
        val exp = RoleExperienceExtractor.extract(
            raw(goal = "很长的任务描述".repeat(40))
        )
        assertEquals(RoleExperience.SCENARIO_MAX_CHARS, exp.scenario.length)
    }

    @Test
    fun `deterministic extraction is reproducible`() {
        // 同输入两次提炼 → 字段级完全一致（除 id 计数器自增外）。
        val a = RoleExperienceExtractor.extract(raw())
        val b = RoleExperienceExtractor.extract(raw())
        assertEquals(a.copy(id = ""), b.copy(id = ""))
    }
}
