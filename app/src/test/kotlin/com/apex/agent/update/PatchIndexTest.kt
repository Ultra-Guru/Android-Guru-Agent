package com.apex.agent.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PatchIndex] 单元测试 —— 跨版本补丁链解析的图逻辑全覆盖。
 *
 * 覆盖：合法索引解析 / 未知字段前向兼容 / 畸形输入折叠 null /
 * 线性链解析 / 首尾短路 / 断链回退 / 环形防护 / 变体过滤 / 体积汇总。
 */
class PatchIndexTest {

    private fun indexJson(patches: String): String = """
        {
          "generatedAt": "2026-09-30T15:04:25Z",
          "latestTag": "v1.4.4.5",
          "patches": [$patches]
        }
    """.trimIndent()

    private fun entry(
        variant: String = "arm64",
        from: String,
        to: String,
        url: String = "https://example.com/patch_${variant}_${from}_to_${to}.vcdiff",
        size: Long = 1024L,
        sha: String? = "aa" + "b".repeat(62)
    ): String {
        val shaPart = sha?.let { """"sha256": "$it",""" } ?: ""
        return """
            {"variant": "$variant", "fromTag": "$from", "toTag": "$to",
             $shaPart "url": "$url", "sizeBytes": $size}
        """.trimIndent().replace(", }", "}").replace(",}", "}")
    }

    // ── 解析 ─────────────────────────────────────────────────────────────

    @Test
    fun `parses well-formed index`() {
        val model = PatchIndex.parse(
            indexJson(entry(from = "v1.4.4.4", to = "v1.4.4.5") + "," +
                entry(variant = "universal", from = "v1.4.4.4", to = "v1.4.4.5"))
        )
        assertNotNull(model)
        assertEquals(2, model!!.patches.size)
        assertEquals("v1.4.4.5", model.latestTag)
    }

    @Test
    fun `unknown fields are tolerated`() {
        // schema 演进：新增字段不崩老客户端
        val json = """
            {"versionName": "1.4.4.5", "extraField": {"a": 1},
             "patches": [{"variant": "arm64", "fromTag": "v1", "toTag": "v2",
                          "url": "u", "sizeBytes": 5, "sha256": null, "newField": true}]}
        """.trimIndent()
        val model = PatchIndex.parse(json)
        assertNotNull(model)
        assertEquals(1, model!!.patches.size)
        assertNull(model.patches[0].sha256)
    }

    @Test
    fun `malformed json folds to null`() {
        assertNull(PatchIndex.parse("{ not json"))
        assertNull(PatchIndex.parse(""))
        // 缺必填字段的条目 → 整体折叠 null（防御式 IO 纪律）
        assertNull(PatchIndex.parse("""{"patches": [{"fromTag": "v1"}]}"""))
    }

    // ── 链解析 ───────────────────────────────────────────────────────────

    @Test
    fun `resolves linear chain across three versions`() {
        val model = PatchIndex.parse(
            indexJson(
                entry(from = "v1.4.4.2", to = "v1.4.4.3", size = 100) + "," +
                    entry(from = "v1.4.4.3", to = "v1.4.4.4", size = 200) + "," +
                    entry(from = "v1.4.4.4", to = "v1.4.4.5", size = 300)
            )
        )!!
        val chain = PatchIndex.resolveChain(model, "1.4.4.2", "v1.4.4.5", "arm64")
        assertNotNull(chain)
        assertEquals(3, chain!!.steps.size)
        assertEquals("v1.4.4.3", chain.steps[0].toTag)
        assertEquals("v1.4.4.4", chain.steps[1].toTag)
        assertEquals(600L, chain.totalBytes)
    }

    @Test
    fun `same version resolves to null`() {
        val model = PatchIndex.parse(
            indexJson(entry(from = "v1", to = "v2"))
        )!!
        assertNull(PatchIndex.resolveChain(model, "1", "v1", "arm64"))
    }

    @Test
    fun `broken chain returns null`() {
        // 本地版本在索引中无后继（链中间断裂）
        val model = PatchIndex.parse(
            indexJson(entry(from = "v1.4.4.4", to = "v1.4.4.5"))
        )!!
        assertNull(PatchIndex.resolveChain(model, "1.4.4.2", "v1.4.4.5", "arm64"))
    }

    @Test
    fun `cycle guard prevents infinite walk`() {
        // 恶意/损坏索引：v1→v2→v1 环
        val model = PatchIndex.parse(
            indexJson(
                entry(from = "v1", to = "v2") + "," + entry(from = "v2", to = "v1")
            )
        )!!
        assertNull(PatchIndex.resolveChain(model, "1", "v9", "arm64"))
    }

    @Test
    fun `variant mismatch is filtered out`() {
        val model = PatchIndex.parse(
            indexJson(entry(variant = "universal", from = "v1", to = "v2"))
        )!!
        // 设备是 arm64，但索引只有 universal 补丁 → 无链可用
        assertNull(PatchIndex.resolveChain(model, "1", "v2", "arm64"))
        // universal 设备按自身变体可正常解析
        assertNotNull(PatchIndex.resolveChain(model, "1", "v2", "universal"))
    }

    @Test
    fun `blank url entries are skipped`() {
        val model = PatchIndex.parse(
            """
                {"patches": [{"variant": "arm64", "fromTag": "v1", "toTag": "v2",
                              "url": "", "sizeBytes": 1}]}
            """.trimIndent()
        )!!
        assertNull(PatchIndex.resolveChain(model, "1", "v2", "arm64"))
    }

    @Test
    fun `duplicate from keeps first entry`() {
        // 早期索引的重复写入：保留首条（向后兼容）
        val model = PatchIndex.parse(
            indexJson(
                entry(from = "v1", to = "v2", size = 10) + "," +
                    entry(from = "v1", to = "v3", size = 20)
            )
        )!!
        val chain = PatchIndex.resolveChain(model, "1", "v2", "arm64")
        assertNotNull(chain)
        assertEquals(10L, chain!!.totalBytes)
    }

    @Test
    fun `negative sizes fold to zero in total`() {
        val model = PatchIndex.parse(
            indexJson(entry(from = "v1", to = "v2", size = -5))
        )!!
        val chain = PatchIndex.resolveChain(model, "1", "v2", "arm64")
        assertNotNull(chain)
        assertEquals(0L, chain!!.totalBytes)
    }

    // ── v1.4.5：BFS 最短路径 + 多基底直达边 ────────────────────────────

    @Test
    fun `direct edge preferred over two-hop adjacent path`() {
        // 多基底发布：本地版既有「相邻边 → 下一版 → 目标」两跳路径，
        // 也有「直达边 → 目标」一跳路径 —— BFS 必须选直达
        val model = PatchIndex.parse(
            indexJson(
                entry(from = "v1.4.4.21", to = "v1.4.4.22", size = 100) + "," +
                    entry(from = "v1.4.4.22", to = "v1.4.5", size = 100) + "," +
                    entry(from = "v1.4.4.21", to = "v1.4.5", size = 120)
            )
        )!!
        val chain = PatchIndex.resolveChain(model, "1.4.4.21", "v1.4.5", "arm64")
        assertNotNull(chain)
        assertEquals(1, chain!!.steps.size)
        assertEquals("v1.4.4.21", chain.steps[0].fromTag)
        assertEquals("v1.4.5", chain.steps[0].toTag)
        assertEquals(120L, chain.totalBytes)
    }

    @Test
    fun `direct edge wins even when listed after adjacent edges`() {
        // 条目顺序无关性：直达边出现在索引末尾也要被选中；
        // 同向双直达边（100/130）→ 回溯取体积小的那条
        val model = PatchIndex.parse(
            indexJson(
                entry(from = "v1.4.4.22", to = "v1.4.5", size = 100) + "," +
                    entry(from = "v1.4.4.23", to = "v1.4.5", size = 100) + "," +
                    entry(from = "v1.4.4.22", to = "v1.4.4.23", size = 100) + "," +
                    entry(from = "v1.4.4.22", to = "v1.4.5", size = 130)
            )
        )!!
        val chain = PatchIndex.resolveChain(model, "1.4.4.22", "v1.4.5", "arm64")
        assertNotNull(chain)
        assertEquals(1, chain!!.steps.size)
        assertEquals(100L, chain.totalBytes)
    }

    @Test
    fun `blank target falls back to latestTag`() {
        // 清单缺 tag（旧 schema）：回退索引 latestTag
        val model = PatchIndex.parse(
            indexJson(entry(from = "v1.4.4.4", to = "v1.4.4.5"))
        )!!
        val chain = PatchIndex.resolveChain(model, "1.4.4.4", "", "arm64")
        assertNotNull(chain)
        assertEquals(1, chain!!.steps.size)
        assertEquals("v1.4.4.5", chain.steps[0].toTag)
    }

    @Test
    fun `greedy min size backtrack among same hops`() {
        // 同为两跳的两条路径：v1→a→v3（100+100）与 v1→b→v3（10+10）
        // —— 回溯贪心选每段最小前驱边，走 b 路径
        val model = PatchIndex.parse(
            indexJson(
                entry(from = "v1", to = "vA", size = 100) + "," +
                    entry(from = "vA", to = "v3", size = 100) + "," +
                    entry(from = "v1", to = "vB", size = 10) + "," +
                    entry(from = "vB", to = "v3", size = 10)
            )
        )!!
        val chain = PatchIndex.resolveChain(model, "1", "v3", "arm64")
        assertNotNull(chain)
        assertEquals(2, chain!!.steps.size)
        assertEquals("vB", chain.steps[0].toTag)
        assertEquals(20L, chain.totalBytes)
    }

    @Test
    fun `chains beyond max length are rejected`() {
        // 70 段线性链 > MAX_CHAIN_LENGTH(64) → 折叠 null（防御环形/巨型索引）
        val entries = (0 until 70).joinToString(",") { i ->
            entry(from = "v$i", to = "v${i + 1}", size = 10)
        }
        val model = PatchIndex.parse(indexJson(entries))!!
        assertNull(PatchIndex.resolveChain(model, "0", "v70", "arm64"))
        // 64 段以内的链正常解析（tag 前缀 v + 本地 versionName 拼合）
        val withinEntries = (0 until 10).joinToString(",") { i ->
            entry(from = "va$i", to = "va${i + 1}", size = 10)
        }
        val model2 = PatchIndex.parse(indexJson(withinEntries))!!
        val chain = PatchIndex.resolveChain(model2, "a0", "va10", "arm64")
        assertNotNull(chain)
        assertEquals(10, chain!!.steps.size)
    }
}
