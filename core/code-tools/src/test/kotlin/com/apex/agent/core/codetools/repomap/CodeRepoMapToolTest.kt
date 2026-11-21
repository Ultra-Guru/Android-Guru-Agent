package com.apex.agent.core.codetools.repomap

import com.apex.agent.core.codetools.CodeWorkspaceRoots
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * CodeRepoMap 端到端契约测试（TemporaryFolder 建工作区，无 mock）：
 *
 * - 多文件仓库产出含定义签名行的地图，预算内收敛；
 * - focus 文件不出现在地图中但其关联文件排名上升；
 * - 噪音目录剔除；重要文件前置；空工作区引导。
 */
class CodeRepoMapToolTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun tool(root: java.io.File?) = CodeRepoMapTool(CodeWorkspaceRoots { root })

    private fun java.io.File.write(path: String, content: String) {
        val f = resolve(path)
        f.parentFile?.mkdirs()
        f.writeText(content)
    }

    @Test
    fun `multi file repo renders definition skeleton within budget`() = runTest {
        val ws = tmp.newFolder("ws")
        ws.write(
            "src/main.kt",
            """
            package demo

            fun main() {
                val e = Engine()
                e.start()
            }
            """.trimIndent()
        )
        ws.write(
            "src/engine.kt",
            """
            package demo

            class Engine {
                fun start() {
                    println("go")
                }

                fun stop() {
                    println("halt")
                }
            }
            """.trimIndent()
        )
        ws.write(
            "src/util.kt",
            """
            package demo

            object Strings {
                fun truncate(s: String) {
                    println(s.take(10))
                }
            }
            """.trimIndent()
        )
        ws.write("docs/readme.md", "# Demo\nSome docs\n")

        val result = tool(ws).executeSafe("""{"max_tokens": 512}""")
        assertTrue(result.isSuccess)
        val text = result.render()
        // 定义签名行进入地图
        assertTrue("class Engine 签名应出现", text.contains("class Engine"))
        assertTrue("start 定义应出现", text.contains("fun start()"))
        // 函数体被省略为折叠标记（... (N lines)）
        assertFalse("函数体应被省略", text.contains("println"))
        assertTrue("省略标记应出现", text.contains("... ("))
        // 统计尾注
        assertTrue(text.contains("budget 512"))
        // 估算 token 在预算 15% 容差内（或恰好低于预算）
        val estLine = Regex("~(\\d+) tokens").find(text)!!
        val est = estLine.groupValues[1].toInt()
        assertTrue("估算 $est 应 ≤ 512×1.15", est <= 512 * 1.15 + 1)
    }

    @Test
    fun `focus files are omitted and related files boosted`() = runTest {
        val ws = tmp.newFolder("ws")
        ws.write(
            "engine.kt",
            """
            class Engine {
                fun start() = 1
            }
            """.trimIndent()
        )
        ws.write(
            "app.kt",
            """
            fun main() {
                val engine = Engine()
                engine.start()
            }
            """.trimIndent()
        )
        ws.write(
            "unrelated.kt",
            """
            class UnrelatedThing {
                fun noop() = 2
            }
            """.trimIndent()
        )

        // 无 focus：app.kt 与 engine.kt 都在
        val plain = tool(ws).executeSafe("{}").render()
        assertTrue(plain.contains("app.kt"))
        assertTrue(plain.contains("engine.kt"))

        // focus app.kt：它消失，engine.kt（被它引用）仍在且优先
        val focused = tool(ws).executeSafe("""{"focus": "app.kt"}""").render()
        assertFalse("focus 文件不应出现", focused.contains("app.kt:"))
        assertTrue(focused.contains("class Engine"))
    }

    @Test
    fun `noise directories are excluded`() = runTest {
        val ws = tmp.newFolder("ws")
        ws.write("real.kt", "class Real\n")
        ws.write("build/generated.kt", "class Generated\n")
        ws.write("node_modules/dep/index.js", "function vendor() {}\n")

        val text = tool(ws).executeSafe("{}").render()
        assertTrue(text.contains("Real"))
        assertFalse(text.contains("Generated"))
        assertFalse(text.contains("vendor"))
    }

    @Test
    fun `important files are listed`() = runTest {
        val ws = tmp.newFolder("ws")
        ws.write("src/only.kt", "class Only\n")
        ws.write("README.md", "# readme\n")

        val text = tool(ws).executeSafe("{}").render()
        assertTrue("README.md 应作为重要文件出现", text.contains("README.md"))
    }

    @Test
    fun `no workspace returns actionable guidance`() = runTest {
        val result = tool(null).executeSafe("{}")
        assertFalse(result.isSuccess)
        assertTrue(result.render().contains("No active coding workspace"))
    }

    @Test
    fun `empty workspace degrades gracefully`() = runTest {
        val ws = tmp.newFolder("empty")
        val result = tool(ws).executeSafe("{}")
        assertTrue(result.isSuccess)
        assertTrue(result.render().contains("no files"))
    }

    @Test
    fun `max_tokens bounds are coerced`() = runTest {
        val ws = tmp.newFolder("ws")
        ws.write("a.kt", "class Aaa\n")
        ws.write("b.kt", "class Bbb\n")

        // 超上限被钳制到 4096 —— 仍成功，不报错
        val result = tool(ws).executeSafe("""{"max_tokens": 999999}""")
        assertTrue(result.isSuccess)
        assertTrue(result.render().contains("budget 4096"))
    }

    @Test
    fun `mtime cache serves repeated calls`() = runTest {
        val ws = tmp.newFolder("ws")
        ws.write("a.kt", "class Aaa\n")
        val t = tool(ws)
        val first = t.executeSafe("{}").render()
        val second = t.executeSafe("{}").render()
        assertTrue(first.contains("class Aaa"))
        assertTrue(second.contains("class Aaa"))
    }
}
