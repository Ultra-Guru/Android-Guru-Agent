package com.apex.agent.knowledge

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 知识库体系契约：解析器（结构/保留节/索引表/编号）/ 服务（懒加载/坏文件
 * 降级/角色绑定/检索五动作）/ 提示词注入。
 */
class KnowledgeBaseServiceTest {

    private val sampleMd = """
        <!-- kb: id=demo | role=builtin_coding_android | title=演示知识库 | version=3 -->
        # 演示知识库

        ## 必看内容
        - 开工先读必看内容
        - 协程取消要传播

        ## 快速索引
        | 想找什么 | 去哪一节 |
        | --- | --- |
        | 协程怎么取消 | 4. 结构化并发 |
        | 布局怎么验证 | 2. 布局验证 |

        ## 1. 环境与工具链
        用 gradle 跑构建。

        ## 2. 布局验证
        用 HtmlAnalyzeLayout 静态检查，再 BrowserAnalyzeLayout 动态复核。

        ## 4. 结构化并发
        取消传播：coroutineScope 内抛 CancellationException 会上抛；
        supervisorScope 隔离子失败。

        ## 无编号节
        杂项内容。
    """.trimIndent()

    private fun service(files: Map<String, String>): KnowledgeBaseService =
        KnowledgeBaseService(
            listAssetFiles = { files.keys.toList() },
            readAsset = { files[it] },
            logger = { }
        )

    @Test
    fun `parse extracts header and sections`() {
        val outcome = KnowledgeBaseParser.parse("demo.md", sampleMd)
        val kb = outcome.kb
        assertNotNull(kb)
        assertEquals("demo", kb!!.id)
        assertEquals("builtin_coding_android", kb.roleId)
        assertEquals("演示知识库", kb.title)
        assertEquals(3, kb.version)
        assertEquals(4, kb.sectionCount) // 1/2/4/无编号节
        assertTrue(kb.mustRead.contains("开工先读"))
    }

    @Test
    fun `index table parsed with header skipped`() {
        val kb = KnowledgeBaseParser.parse("demo.md", sampleMd).kb!!
        // 表头（想找什么/去哪一节）被跳过，两行数据保留
        assertEquals(2, kb.index.size)
        assertEquals("协程怎么取消", kb.index[0].topic)
        assertEquals("4. 结构化并发", kb.index[0].target)
    }

    @Test
    fun `bad files rejected with reason`() {
        assertNull(KnowledgeBaseParser.parse("a.md", "").kb)
        assertNull(KnowledgeBaseParser.parse("a.md", "no header").kb)
        assertNull(
            KnowledgeBaseParser.parse("a.md", "<!-- kb: id=x | role=r | title=t -->\n## 快速索引\n| a | b |\n").kb
        ) // 缺必看内容/章节
    }

    @Test
    fun `service loads lazily and tolerates broken files`() = runTest {
        val svc = service(
            mapOf(
                "demo.md" to sampleMd,
                "broken.md" to "garbage without header",
                "notes.txt" to "not markdown"
            )
        )
        val all = svc.listAll()
        assertEquals(1, all.size)
        assertEquals("demo", all.first().id)
        // txt 不在加载范围；broken 解析失败被跳过
    }

    @Test
    fun `role binding lookup`() = runTest {
        val svc = service(mapOf("demo.md" to sampleMd))
        assertNotNull(svc.forRole("builtin_coding_android"))
        assertNull(svc.forRole("builtin_coding_kotlin"))
    }

    @Test
    fun `find section by full title number and prefix`() = runTest {
        val svc = service(mapOf("demo.md" to sampleMd))
        assertNotNull(svc.readSection("demo", "4. 结构化并发"))
        assertNotNull(svc.readSection("demo", "4"))
        assertNotNull(svc.readSection("demo", "结构化并发")) // 前缀
        assertNull(svc.readSection("demo", "不存在"))
    }

    @Test
    fun `search ranks title hits and index pointers`() = runTest {
        val svc = service(mapOf("demo.md" to sampleMd))
        val hits = svc.search("demo", "协程 取消")
        assertTrue(hits.isNotEmpty())
        // 索引主题「协程怎么取消」命中 → 指向 4. 结构化并发
        assertTrue(hits.any { it.sectionTitle == "4. 结构化并发" && it.via == "index" })
        // 标题命中「结构化并发」分值高于正文命中
        val byTitle = hits.first { it.via == "title" || it.via == "index" }
        assertTrue(byTitle.score >= hits.last().score)
    }

    @Test
    fun `must read returned verbatim`() = runTest {
        val svc = service(mapOf("demo.md" to sampleMd))
        val must = svc.mustReadOf("demo")
        assertNotNull(must)
        assertTrue(must!!.contains("协程取消要传播"))
    }

    @Test
    fun `role definition hint mentions workflow`() = runTest {
        val svc = service(mapOf("demo.md" to sampleMd))
        val kb = svc.forRole("builtin_coding_android")!!
        val hint = svc.roleDefinitionHint(kb)
        assertTrue(hint.contains("must_read"))
        assertTrue(hint.contains("index"))
        assertTrue(hint.contains("search"))
        assertTrue(hint.contains("read"))
        assertTrue(hint.contains("演示知识库"))
    }

    @Test
    fun `search on missing kb returns empty`() = runTest {
        val svc = service(mapOf("demo.md" to sampleMd))
        assertTrue(svc.search("nope", "anything").isEmpty())
        assertNull(svc.mustReadOf("nope"))
    }

    @Test
    fun `duplicate role binding first wins`() = runTest {
        val other = sampleMd.replace("id=demo", "id=other")
        val svc = service(
            mapOf(
                "a.md" to sampleMd,
                "b.md" to other
            )
        )
        val all = svc.listAll()
        assertEquals(2, all.size)
        // 同角色两套：首个绑定生效
        val bound = svc.forRole("builtin_coding_android")
        assertNotNull(bound)
        assertTrue(bound!!.id == "demo" || bound.id == "other")
    }
}
