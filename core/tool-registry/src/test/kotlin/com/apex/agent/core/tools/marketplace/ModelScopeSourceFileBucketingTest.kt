package com.apex.agent.core.tools.marketplace

import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #354 ③ 回归：`listSkills` 的文件归属从 O(技能数 × 文件数) 改为单遍分桶。
 *
 * 旧实现为每个技能目录全量重扫整棵树（`tree.filter { it.startsWith("$dir/") }`），
 * 上百技能 × 数千文件时是几十万次前缀比较。分桶必须**结果等价**，因此这里
 * 钉住三类容易出错的边界：
 *
 * - 兄弟目录前缀重叠（`skills/a` 与 `skills/ab`）不能互相串味；
 * - 嵌套技能目录（`skills/a` 与 `skills/a/b`）归给**最具体**的那一个 ——
 *   旧实现会让外层技能把内层技能的文件一并装走；
 * - `skills/` 下的游离文件（不属于任何含 SKILL.md 的目录）不入任何技能。
 */
class ModelScopeSourceFileBucketingTest {

    private fun fakeHttpClient(treeJson: String): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val isTreeCall = chain.request().url.encodedPath.contains("/git/trees/")
                val (body, type) = if (isTreeCall) {
                    treeJson to "application/json"
                } else {
                    "---\nname: X\ndescription: d\n---\nbody" to "text/markdown"
                }
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("fake")
                    .body(body.toResponseBody(type.toMediaType()))
                    .build()
            }
            .build()

    private fun tree(vararg paths: String): String =
        """{"tree": [${paths.joinToString(",") { """{"type":"blob","path":"$it"}""" }}]}"""

    @Test
    fun `兄弟目录前缀重叠时文件不串味`() = runTest {
        val json = tree(
            "skills/a/SKILL.md",
            "skills/a/ref/x.md",
            "skills/ab/SKILL.md",
            "skills/ab/ref/y.md"
        )
        val source = ModelScopeSource(fakeHttpClient(json))

        val skills = source.listSkills().getOrThrow().associateBy { it.id }

        assertEquals(listOf("skills/a/SKILL.md", "skills/a/ref/x.md"), skills.getValue("a").files)
        assertEquals(listOf("skills/ab/SKILL.md", "skills/ab/ref/y.md"), skills.getValue("ab").files)
    }

    @Test
    fun `嵌套技能目录归给最具体的一层`() = runTest {
        val json = tree(
            "skills/outer/SKILL.md",
            "skills/outer/docs/readme.md",
            "skills/outer/inner/SKILL.md",
            "skills/outer/inner/data/z.md"
        )
        val source = ModelScopeSource(fakeHttpClient(json))

        val skills = source.listSkills().getOrThrow().associateBy { it.id }

        // 外层只拿自己的文件；内层技能不会被外层装走。
        assertEquals(
            listOf("skills/outer/SKILL.md", "skills/outer/docs/readme.md"),
            skills.getValue("outer").files
        )
        assertEquals(
            listOf("skills/outer/inner/SKILL.md", "skills/outer/inner/data/z.md"),
            skills.getValue("inner").files
        )
    }

    @Test
    fun `skills 下的游离文件不属于任何技能`() = runTest {
        val json = tree(
            "skills/README.md",
            "skills/solo/SKILL.md"
        )
        val source = ModelScopeSource(fakeHttpClient(json))

        val skills = source.listSkills().getOrThrow()

        assertEquals(1, skills.size)
        assertEquals("solo", skills[0].id)
        assertEquals(listOf("skills/solo/SKILL.md"), skills[0].files)
        assertFalse(skills[0].files.any { it == "skills/README.md" })
    }

    @Test
    fun `技能清单仍按目录名排序且每条只出现一次`() = runTest {
        val json = tree(
            "skills/b/SKILL.md",
            "skills/a/SKILL.md",
            "skills/a/x.md"
        )
        val source = ModelScopeSource(fakeHttpClient(json))

        val skills = source.listSkills().getOrThrow()

        assertEquals(listOf("a", "b"), skills.map { it.id })
        assertTrue(skills.all { it.files.isNotEmpty() })
    }
}
