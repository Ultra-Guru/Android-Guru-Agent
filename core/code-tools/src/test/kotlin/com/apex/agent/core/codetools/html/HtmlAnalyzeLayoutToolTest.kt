package com.apex.agent.core.codetools.html

import com.apex.agent.core.codetools.CodeWorkspaceRoots
import com.apex.agent.core.tools.ToolErrorCode
import com.apex.agent.core.tools.ToolResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * HtmlAnalyzeLayout 工具契约：路径解析 / 沙箱拒绝 / 报告渲染 / 参数分支。
 */
class HtmlAnalyzeLayoutToolTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun roots(root: File?): CodeWorkspaceRoots = CodeWorkspaceRoots { root }

    private val sampleHtml = """
        <!DOCTYPE html>
        <html lang="zh">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <title>演示页</title>
        </head>
        <body>
          <header><nav>导航</nav></header>
          <main class="flex">
            <aside class="w-1/3">侧栏</aside>
            <article class="w-2/3">正文</article>
          </main>
          <footer>页脚</footer>
        </body>
        </html>
    """.trimIndent()

    @Test
    fun `analyzes html file and returns report`() = runTest {
        val root = tmp.newFolder("ws")
        File(root, "page.html").writeText(sampleHtml)
        val tool = HtmlAnalyzeLayoutTool(roots(root))
        val result = tool.executeSafe("""{"path":"page.html"}""")
        assertTrue(result.isSuccess)
        val out = result.output ?: ""
        assertTrue(out.contains("页面骨架"))
        assertTrue(out.contains("body >"))
        assertTrue(out.contains("[flex]"))
    }

    @Test
    fun `relative and absolute inside root both resolve`() = runTest {
        val root = tmp.newFolder("ws")
        File(root, "sub").mkdirs()
        File(root, "sub/a.html").writeText(sampleHtml)
        val tool = HtmlAnalyzeLayoutTool(roots(root))
        assertTrue(tool.executeSafe("""{"path":"sub/a.html"}""").isSuccess)
        assertTrue(tool.executeSafe("""{"path":"${File(root, "sub/a.html").absolutePath}"}""").isSuccess)
    }

    @Test
    fun `missing file returns not found`() = runTest {
        val root = tmp.newFolder("ws")
        val tool = HtmlAnalyzeLayoutTool(roots(root))
        val result = tool.executeSafe("""{"path":"nope.html"}""")
        assertFalse(result.isSuccess)
        assertTrue(result.error?.code == ToolErrorCode.NOT_FOUND)
    }

    @Test
    fun `path escape rejected`() = runTest {
        val root = tmp.newFolder("ws")
        val outside = tmp.newFolder("outside")
        File(outside, "secret.html").writeText(sampleHtml)
        val tool = HtmlAnalyzeLayoutTool(roots(root))
        val result = tool.executeSafe("""{"path":"../outside/secret.html"}""")
        assertFalse(result.isSuccess)
        assertTrue(result.error?.code == ToolErrorCode.SANDBOX_VIOLATION)
    }

    @Test
    fun `non html extension rejected`() = runTest {
        val root = tmp.newFolder("ws")
        File(root, "notes.txt").writeText("hi")
        val tool = HtmlAnalyzeLayoutTool(roots(root))
        val result = tool.executeSafe("""{"path":"notes.txt"}""")
        assertFalse(result.isSuccess)
        assertTrue(result.error?.code == ToolErrorCode.INVALID_ARGUMENT)
    }

    @Test
    fun `include_tree false omits layout tree section`() = runTest {
        val root = tmp.newFolder("ws")
        File(root, "page.html").writeText(sampleHtml)
        val tool = HtmlAnalyzeLayoutTool(roots(root))
        val withTree = tool.executeSafe("""{"path":"page.html"}""").output ?: ""
        val noTree = tool.executeSafe("""{"path":"page.html","include_tree":false}""").output ?: ""
        assertTrue(withTree.contains("布局树"))
        assertFalse(noTree.contains("布局树"))
        assertFalse(noTree.contains("include_tree"))
    }

    @Test
    fun `no active root fails honestly`() = runTest {
        val tool = HtmlAnalyzeLayoutTool(roots(null))
        val result = tool.executeSafe("""{"path":"page.html"}""")
        assertFalse(result.isSuccess)
        assertTrue(result.error?.code == ToolErrorCode.EXECUTION_FAILED)
    }
}
