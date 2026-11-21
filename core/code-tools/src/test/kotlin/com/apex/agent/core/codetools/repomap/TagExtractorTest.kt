package com.apex.agent.core.codetools.repomap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TagExtractor 契约测试：定义抽取（Kotlin/Python/JS 代表三族语法）、
 * 引用计数、注释剔除（行注释/块注释续行/字符串内 URL 防误伤）。
 */
class TagExtractorTest {

    private val extractor = TagExtractor()

    // ── 定义抽取 ────────────────────────────────────────────────────

    @Test
    fun `kotlin defs capture fun class object val with lines`() {
        val code = """
            package demo

            class Engine {
                fun start() = 1
                val maxSpeed = 100
            }

            object Registry
        """.trimIndent()
        val tags = extractor.extract("src/Engine.kt", code)
        val names = tags.definitions.map { it.identifier }
        assertTrue(names.containsAll(listOf("Engine", "start", "maxSpeed", "Registry")))
        // 行号是 1 基：class Engine 在第 3 行
        assertEquals(3, tags.definitions.first { it.identifier == "Engine" }.line)
        assertEquals(4, tags.definitions.first { it.identifier == "start" }.line)
    }

    @Test
    fun `kotlin generic and receiver funs are captured`() {
        val code = "fun <T> decode(input: List<T>): T = input.first()\nfun String.shout() = uppercase()"
        val tags = extractor.extract("Util.kt", code)
        val names = tags.definitions.map { it.identifier }
        assertTrue(names.contains("decode"))
        assertTrue(names.contains("shout"))
    }

    @Test
    fun `python defs and classes`() {
        val code = """
            class Worker:
                def run(self):
                    pass

            async def main():
                pass
        """.trimIndent()
        val tags = extractor.extract("worker.py", code)
        val names = tags.definitions.map { it.identifier }
        assertTrue(names.contains("Worker"))
        assertTrue(names.contains("run"))
        assertTrue(names.contains("main")) // async def 也命中 def 前缀
    }

    @Test
    fun `javascript functions consts and types`() {
        val code = """
            function parse(input) {}
            const answer = 42
            type Result = { ok: boolean }
            interface Api {}
        """.trimIndent()
        val tags = extractor.extract("api.ts", code)
        val names = tags.definitions.map { it.identifier }
        assertTrue(names.containsAll(listOf("parse", "answer", "Result", "Api")))
    }

    @Test
    fun `unsupported extension yields empty tags`() {
        val tags = extractor.extract("notes.txt", "hello world")
        assertTrue(tags.isEmpty())
        assertFalse(extractor.supports("notes.txt"))
        assertTrue(extractor.supports("App.kt"))
        assertTrue(extractor.supports("lib/Engine.java"))
        assertTrue(extractor.supports("script.go"))
    }

    // ── 引用计数 ────────────────────────────────────────────────────

    @Test
    fun `references count occurrences per identifier`() {
        val code = """
            class Engine
            val a = Engine()
            val b = Engine() + Engine()
            val c = unrelated
        """.trimIndent()
        val tags = extractor.extract("Main.kt", code)
        // Engine：定义 1 次 + 引用 3 次 = 4 次出现
        assertEquals(4, tags.referenceCounts["Engine"])
        assertEquals(1, tags.referenceCounts["unrelated"])
    }

    @Test
    fun `language keywords and noise tokens are not references`() {
        val code = "val x = 1\nval y = it\nval z = ok"
        val tags = extractor.extract("Kw.kt", code)
        assertNull(tags.referenceCounts["val"]) // 关键字
        assertNull(tags.referenceCounts["it"])  // 通用噪音
        assertNull(tags.referenceCounts["ok"])  // 通用噪音
        assertNotNull(tags.referenceCounts["x"])
    }

    // ── 注释剔除 ────────────────────────────────────────────────────

    @Test
    fun `line comments are excluded from defs and refs`() {
        val code = """
            // fun commentedOut() {}
            class Real
            // val nope = 1
        """.trimIndent()
        val tags = extractor.extract("C.kt", code)
        val names = tags.definitions.map { it.identifier }
        assertTrue(names.contains("Real"))
        assertFalse(names.contains("commentedOut"))
        assertNull(tags.referenceCounts["nope"])
    }

    @Test
    fun `block comment continuation lines are skipped`() {
        val code = """
            /**
             * fun ghost() {}
             */
            class Present
        """.trimIndent()
        val tags = extractor.extract("Doc.kt", code)
        val names = tags.definitions.map { it.identifier }
        assertTrue(names.contains("Present"))
        assertFalse(names.contains("ghost"))
    }

    @Test
    fun `multiplication and urls survive comment stripping`() {
        val code = """
            val area = width * height
            val site = "https://example.com/path"
        """.trimIndent()
        val tags = extractor.extract("Mul.kt", code)
        // 乘法行不能被当注释整行剔除：width/height 仍被计数
        assertEquals(1, tags.referenceCounts["width"])
        assertEquals(1, tags.referenceCounts["height"])
        // 字符串里的 URL 不截断行：example 与 path 计为引用
        assertEquals(1, tags.referenceCounts["example"])
    }

    @Test
    fun `python hash comments are stripped but not string hashes`() {
        val code = "x = 1 # trailing\ny = \"a # b\"\n"
        val tags = extractor.extract("p.py", code)
        assertNull(tags.referenceCounts["trailing"])
        assertEquals(1, tags.referenceCounts["y"])
    }

    @Test
    fun `empty content yields empty tags`() {
        assertTrue(extractor.extract("Empty.kt", "").isEmpty())
    }
}
