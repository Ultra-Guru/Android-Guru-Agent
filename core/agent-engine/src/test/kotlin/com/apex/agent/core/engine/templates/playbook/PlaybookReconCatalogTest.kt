package com.apex.agent.core.engine.templates.playbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 幕侦察目录测试：绑定合法、输入缺失空报告（防御式）、异常不上抛、
 * 确定性冒烟（同输入两次一致）、格式行数上限。
 */
class PlaybookReconCatalogTest {

    private val sampleCode = """
        fun compute(values: List<Int>): Int {
            var total = 0
            for (v in values) {
                if (v > 0) {
                    total += v * 2
                } else {
                    total -= v
                }
            }
            return total
        }
    """.trimIndent()

    @Test
    fun `代码审查侦察产出实质行`() {
        val report = PlaybookReconCatalog.run(
            PlaybookReconCatalog.RECON_CODE_REVIEW,
            mapOf(PlaybookReconCatalog.KEY_CODE to sampleCode, PlaybookReconCatalog.KEY_LANGUAGE to "kotlin")
        )
        assertEquals(PlaybookReconCatalog.RECON_CODE_REVIEW, report.reconId)
        assertTrue("侦察无产出", report.lines.isNotEmpty())
        assertTrue(report.lines.all { it.isNotBlank() })
    }

    @Test
    fun `同输入两次结果一致（确定性冒烟）`() {
        val inputs = mapOf(PlaybookReconCatalog.KEY_CODE to sampleCode, PlaybookReconCatalog.KEY_LANGUAGE to "kotlin")
        assertEquals(
            PlaybookReconCatalog.run(PlaybookReconCatalog.RECON_CODE_REVIEW, inputs),
            PlaybookReconCatalog.run(PlaybookReconCatalog.RECON_CODE_REVIEW, inputs)
        )
    }

    @Test
    fun `缺代码输入返回空报告（防御式）`() {
        for (reconId in PlaybookReconCatalog.ALL_IDS) {
            assertTrue(PlaybookReconCatalog.run(reconId, emptyMap()).isEmpty)
        }
    }

    @Test
    fun `未知侦察 id 返回空报告`() {
        assertTrue(PlaybookReconCatalog.run("no_such_recon", mapOf("code" to "fun a() {}")).isEmpty)
        assertTrue(PlaybookReconCatalog.run(null, emptyMap()).isEmpty)
    }

    @Test
    fun `Bug 分诊侦察产优先级行`() {
        val report = PlaybookReconCatalog.run(
            PlaybookReconCatalog.RECON_BUG_TRIAGE,
            mapOf(PlaybookReconCatalog.KEY_TASK to "用户反馈打开应用就崩溃")
        )
        assertTrue(report.lines.first().contains("分诊优先级"))
    }

    @Test
    fun `测试用例侦察产被测函数行`() {
        val report = PlaybookReconCatalog.run(
            PlaybookReconCatalog.RECON_TEST_CASES,
            mapOf(
                PlaybookReconCatalog.KEY_CODE to sampleCode,
                PlaybookReconCatalog.KEY_LANGUAGE to "kotlin",
                PlaybookReconCatalog.KEY_FRAMEWORK to "JUnit"
            )
        )
        assertTrue(report.lines.isNotEmpty())
        assertTrue(report.lines.any { it.contains("被测函数") || it.contains("等价类") })
    }

    @Test
    fun `附录行数不超过上限`() {
        val report = PlaybookReconCatalog.run(
            PlaybookReconCatalog.RECON_CODE_REVIEW,
            mapOf(PlaybookReconCatalog.KEY_CODE to sampleCode)
        )
        // 目录上限 = 12 条发现 + 3 条复杂度 Top（构建器侧还会再截到 10）。
        assertTrue("行数超限: ${report.lines.size}", report.lines.size <= 15)
    }
}
