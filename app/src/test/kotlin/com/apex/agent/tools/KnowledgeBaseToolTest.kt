package com.apex.agent.tools

import com.apex.agent.knowledge.KnowledgeBaseService
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * KnowledgeBase 工具层契约（v2）：
 * - kb 参数按动作区分必需性：list 无需 kb；search 省略 kb 走跨库全局检索；
 *   must_read / index / read 缺 kb 给出可行动报错。
 * - 全局检索渲染：来源库标注 + 汇总排序 + 跨库计数。
 */
class KnowledgeBaseToolTest {

    private val kbAlpha = """
        <!-- kb: id=alpha | role=builtin_coding_kotlin | title=Kotlin库 | version=1 -->
        # Kotlin库

        ## 必看内容
        - 协程取消要传播

        ## 快速索引
        | 想找什么 | 去哪一节 |
        | --- | --- |
        | 协程怎么取消 | 2. 协程取消 |

        ## 1. 基础
        基础内容。

        ## 2. 协程取消
        取消传播：coroutineScope 内抛 CancellationException。
    """.trimIndent()

    private val kbBeta = """
        <!-- kb: id=beta | role=builtin_coding_android | title=Android库 | version=1 -->
        # Android库

        ## 必看内容
        - 布局先静态后动态

        ## 1. 布局验证
        用 HtmlAnalyzeLayout 静态检查。
    """.trimIndent()

    private fun tool(files: Map<String, String>): KnowledgeBaseTool {
        val svc = KnowledgeBaseService(
            listAssetFiles = { files.keys.toList() },
            readAsset = { files[it] },
            logger = { }
        )
        return KnowledgeBaseTool(svc)
    }

    @Test
    fun `list works without kb argument`() = runTest {
        val t = tool(mapOf("alpha.md" to kbAlpha, "beta.md" to kbBeta))
        val out = t.execute("""{"action":"list"}""")
        assertFalse(out.startsWith("Error"))
        assertTrue(out.contains("alpha"))
        assertTrue(out.contains("beta"))
    }

    @Test
    fun `search without kb does global search with source labels`() = runTest {
        val t = tool(mapOf("alpha.md" to kbAlpha, "beta.md" to kbBeta))
        val out = t.execute("""{"action":"search","query":"协程"}""")
        assertFalse(out.startsWith("Error"))
        assertTrue(out.contains("跨库全局检索"))
        assertTrue(out.contains("[alpha]"))
    }

    @Test
    fun `global search no hit gives actionable hint`() = runTest {
        val t = tool(mapOf("alpha.md" to kbAlpha))
        val out = t.execute("""{"action":"search","query":"不存在的词"}""")
        assertTrue(out.contains("没有命中"))
        assertTrue(out.contains("action=list"))
    }

    @Test
    fun `search with kb stays scoped to that volume`() = runTest {
        val t = tool(mapOf("alpha.md" to kbAlpha, "beta.md" to kbBeta))
        val out = t.execute("""{"action":"search","kb":"beta","query":"布局"}""")
        assertFalse(out.startsWith("Error"))
        assertTrue(out.contains("「Android库」检索"))
    }

    @Test
    fun `must_read without kb reports missing argument`() = runTest {
        val t = tool(mapOf("alpha.md" to kbAlpha))
        val out = t.execute("""{"action":"must_read"}""")
        assertTrue(out.startsWith("Error"))
        assertTrue(out.contains("缺少 kb"))
    }

    @Test
    fun `read without kb reports missing argument`() = runTest {
        val t = tool(mapOf("alpha.md" to kbAlpha))
        val out = t.execute("""{"action":"read","section":"1"}""")
        assertTrue(out.startsWith("Error"))
        assertTrue(out.contains("缺少 kb"))
    }
}
