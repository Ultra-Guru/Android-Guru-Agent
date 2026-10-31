package com.apex.agent.core.engine.thinking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ThinkingSignalAnalyzer]（两选档器的共享计分核心）单测。
 *
 * 断言四件事：
 * 1. 长度/多步/代码/风险四维度的检测、权重与标签文案（理由串的可解释契约）；
 * 2. 引擎专有维度（midDimension）的插入位：代码/命令之后、风险词之前；
 * 3. 短平快标注与理由拼装格式（无信号 / 调整段拼接）；
 * 4. 纯函数确定性。
 */
class ThinkingSignalAnalyzerTest {

    private fun analyze(text: String, mid: ((String) -> ThinkingSignalAnalyzer.Signal?)? = null) =
        ThinkingSignalAnalyzer.analyze(text, mid)

    // ═══ 维度 1：文本长度 ═══

    @Test
    fun `short text scores no length signal`() {
        val a = analyze("看下这个")
        assertTrue("短文本不应有长度信号：${a.labels}", a.labels.none { it.contains("文本") })
        assertEquals(0, a.score)
    }

    @Test
    fun `text over 500 scores long text plus two`() {
        val a = analyze("x".repeat(501))
        assertEquals(listOf("长文本+2"), a.labels)
        assertEquals(2, a.score)
    }

    @Test
    fun `text over 1500 scores heavy plus three replacing plus two`() {
        val a = analyze("x".repeat(1501))
        assertEquals(listOf("超长文本+3"), a.labels)
        assertEquals(3, a.score)
    }

    @Test
    fun `length boundaries are exclusive`() {
        assertEquals(0, analyze("x".repeat(500)).score)
        assertEquals(2, analyze("x".repeat(1500)).score)
    }

    // ═══ 维度 2：多步指示词 ═══

    @Test
    fun `chinese indicator hits with label`() {
        val a = analyze("先做A然后做B")
        assertEquals("多步指示(先)+2", a.labels.single())
    }

    @Test
    fun `english indicator matches case insensitive`() {
        val a = analyze("FIRST do this")
        assertEquals("多步指示(first)+2", a.labels.single())
    }

    // ═══ 维度 3：代码 / 命令信号 ═══

    @Test
    fun `code fence has priority over prompt and command word`() {
        val a = analyze("跑下 \$ 和 git status，代码：```kotlin\nfun x() {}\n```")
        assertEquals("代码/命令(```)+2", a.labels.single { it.startsWith("代码/命令") })
    }

    @Test
    fun `shell prompt beats command word`() {
        val a = analyze("\$ git status")
        assertEquals("代码/命令(\$ )+2", a.labels.single { it.startsWith("代码/命令") })
    }

    @Test
    fun `command word detected case insensitive`() {
        val a = analyze("用 Gradle 构建一下")
        assertEquals("代码/命令(gradle)+2", a.labels.single())
    }

    @Test
    fun `plain text has no code signal`() {
        assertNull(ThinkingSignalAnalyzer.codeSignalOf("今天天气不错"))
    }

    // ═══ 维度 4：风险词 ═══

    @Test
    fun `risk word scored and exposed for deep floor`() {
        val a = analyze("帮我 rm -rf 清理")
        assertEquals("风险词(rm -rf)+3", a.labels.single())
        assertEquals("rm -rf", a.riskWord)
        assertEquals(3, a.score)
    }

    @Test
    fun `no risk word yields null field`() {
        assertNull(analyze("随便看看").riskWord)
    }

    // ═══ 引擎专有维度插入位 ═══

    @Test
    fun `mid dimension slots between code and risk labels`() {
        val mid: (String) -> ThinkingSignalAnalyzer.Signal? = { _ ->
            ThinkingSignalAnalyzer.Signal(1, "编码意图+1")
        }
        val a = analyze("跑 git status 删除缓存", mid)
        assertEquals(3, a.labels.size)
        assertTrue("编码意图应在代码/命令之后、风险词之前：${a.labels}", a.labels.indexOf("编码意图+1") > a.labels.indexOf("代码/命令(git)+2"))
        assertTrue(a.labels.indexOf("编码意图+1") < a.labels.indexOf("风险词(删除)+3"))
        assertEquals(2 + 1 + 3, a.score)
    }

    @Test
    fun `null mid dimension keeps native four dimension order`() {
        val a = analyze("跑 git status 然后删除")
        assertEquals(listOf("多步指示(然后)+2", "代码/命令(git)+2", "风险词(删除)+3"), a.labels)
    }

    // ═══ 短平快标注 ═══

    @Test
    fun `quiet short text gets quick label`() {
        assertEquals("短平快(<80字符)", ThinkingSignalAnalyzer.quickLabel("现在几点了", factorCount = 0))
    }

    @Test
    fun `quick label suppressed by factors or length`() {
        assertNull(ThinkingSignalAnalyzer.quickLabel("现在几点了", factorCount = 1))
        assertNull(ThinkingSignalAnalyzer.quickLabel("x".repeat(80), factorCount = 0))
    }

    // ═══ 理由拼装 ═══

    @Test
    fun `reason with no factors shows no-signal placeholder`() {
        val reason = ThinkingSignalAnalyzer.buildReason(emptyList(), 0, "LIGHT", emptyList())
        assertEquals("无信号 → 评分 0 → LIGHT", reason)
    }

    @Test
    fun `reason joins factors and adjustments`() {
        val reason = ThinkingSignalAnalyzer.buildReason(
            listOf("长文本+2", "风险词(删除)+3"),
            5,
            "DEEP",
            listOf("风险词保底DEEP", "错误恢复升一档")
        )
        assertEquals("长文本+2、风险词(删除)+3 → 评分 5 → DEEP（风险词保底DEEP；错误恢复升一档）", reason)
    }

    // ═══ 纯函数确定性 ═══

    @Test
    fun `analyzer is deterministic for identical input`() {
        val text = "先分析然后执行 git 操作，注意删除风险"
        assertEquals(analyze(text), analyze(text))
    }
}
