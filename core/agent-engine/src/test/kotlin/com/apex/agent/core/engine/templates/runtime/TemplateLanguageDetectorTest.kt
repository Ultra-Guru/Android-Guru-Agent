package com.apex.agent.core.engine.templates.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR 3 工具箱测试 · 编程语言探测。
 *
 * 断言值全部来自对探测器的正向/反向设计核对（不是臆造的分数），
 * 只断言「探测结论 + 置信度档位」，不断言具体浮点分值（实现调参会漂）。
 */
class TemplateLanguageDetectorTest {

    @Test
    fun `blank and tiny inputs are unknown`() {
        assertEquals(TemplateLanguageDetection.unknown().detected, TemplateLanguageDetector.detect("").detected)
        assertNull(TemplateLanguageDetector.detect("   ").detected)
        assertNull(TemplateLanguageDetector.detect("int x;").detected) // 短于阈值
        assertFalse(TemplateLanguageDetection.unknown().isConfident)
    }

    @Test
    fun `kotlin snippet is detected confidently`() {
        val code = """
            data class User(val name: String, val age: Int)
            fun greet(user: User): String {
                val prefix = if (user.age > 18) "成年" else "未成年"
                return prefix + user.name
            }
        """.trimIndent()
        val detection = TemplateLanguageDetector.detect(code)
        assertEquals("kotlin", detection.detected)
        assertTrue(detection.isConfident)
        assertTrue(detection.candidates.isNotEmpty())
        assertTrue(detection.candidates[0].evidence.isNotEmpty())
    }

    @Test
    fun `java snippet is detected`() {
        val code = """
            public class Main {
                public static void main(String[] args) {
                    System.out.println("hello");
                }
            }
        """.trimIndent()
        assertEquals("java", TemplateLanguageDetector.detect(code).detected)
    }

    @Test
    fun `python snippet is detected`() {
        val code = """
            def process(items):
                total = 0
                for it in items:
                    total += it
                return total

            if __name__ == "__main__":
                process([1, 2, 3])
        """.trimIndent()
        assertEquals("python", TemplateLanguageDetector.detect(code).detected)
    }

    @Test
    fun `typescript beats javascript when types present`() {
        val code = """
            interface User { name: string; age: number }
            type Result = User | null;
            function find(users: User[]): Result {
                return users[0] ?? null;
            }
        """.trimIndent()
        assertEquals("typescript", TemplateLanguageDetector.detect(code).detected)
    }

    @Test
    fun `go and rust snippets are detected`() {
        val go = """
            package main

            import "fmt"

            func main() {
                msg := "hello"
                fmt.Println(msg)
            }
        """.trimIndent()
        assertEquals("go", TemplateLanguageDetector.detect(go).detected)

        val rust = """
            fn main() {
                let mut total: i32 = 0;
                for i in 0..10 {
                    total += i;
                }
                println!("{}", total);
            }
        """.trimIndent()
        assertEquals("rust", TemplateLanguageDetector.detect(rust).detected)
    }

    @Test
    fun `sql and shell are detected`() {
        val sql = """
            SELECT id, name FROM users
            WHERE age > 18
            ORDER BY created_at DESC
            LIMIT 20;
        """.trimIndent()
        assertEquals("sql", TemplateLanguageDetector.detect(sql).detected)

        val shell = """
            #!/bin/bash
            set -euo pipefail
            for f in ./*.txt; do
                echo processing file
            done
        """.trimIndent()
        assertEquals("shell", TemplateLanguageDetector.detect(shell).detected)
    }

    @Test
    fun `prose is not misdetected as code`() {
        val prose = "今天天气不错，我们一起去公园散步，顺便聊一聊系统的设计与实现方案。"
        val detection = TemplateLanguageDetector.detect(prose)
        // 散文不应高置信判定为某门语言（可能是 unknown，或低置信候选）
        assertFalse(detection.isConfident)
    }

    @Test
    fun `family mapping groups jvm and web languages`() {
        assertEquals("jvm-c-family", TemplateLanguageDetector.familyOf("kotlin"))
        assertEquals("jvm-c-family", TemplateLanguageDetector.familyOf("java"))
        assertEquals("native-c-family", TemplateLanguageDetector.familyOf("c"))
        assertEquals("web-family", TemplateLanguageDetector.familyOf("typescript"))
        assertEquals("python", TemplateLanguageDetector.familyOf("python"))
        assertNull(TemplateLanguageDetector.familyOf(null))
    }

    @Test
    fun `language profiles expose display names`() {
        assertEquals("Kotlin", TemplateLanguageProfile.displayNameOf("kotlin"))
        assertEquals("未知", TemplateLanguageProfile.displayNameOf(null))
        // 未知 id 原样返回（防御式，不炸不吞）
        assertEquals("brainfuck", TemplateLanguageProfile.displayNameOf("brainfuck"))
        assertTrue(TemplateLanguageProfile.ALL.containsKey("cpp"))
    }
}
