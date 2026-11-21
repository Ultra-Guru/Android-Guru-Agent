package com.apex.agent.core.llm.share

import com.apex.agent.core.llm.ModelCapabilities
import com.apex.agent.core.llm.ModelProfile
import com.apex.agent.core.llm.ProviderConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ModelConfigExporter] 导出测试。
 *
 * 断言四件事：
 * 1. 回环：export → [ModelConfigImporter.import]（AUTO 识别）零信息丢失
 *    （provider / base_url / headers / 模型 id·名称·窗口·能力位）；
 * 2. 密钥纪律：导出文本**不含** api_key 键（密钥永不落导出）；
 * 3. 归属过滤：他家 Provider 的档案与空 modelId 档案不混入；
 * 4. 空内容保护：无 baseUrl 且无有效模型 → 空串（无可导出内容）。
 */
class ModelConfigExporterTest {

    private fun provider(
        baseUrl: String = "https://api.deepseek.com/v1",
        headers: Map<String, String> = emptyMap(),
    ) = ProviderConfig(
        id = "prov-1",
        displayName = "DeepSeek 官方",
        baseUrl = baseUrl,
        apiKeys = listOf("sk-secret-never-export"),
        defaultHeaders = headers,
    )

    private fun profile(
        modelId: String,
        name: String = modelId,
        providerId: String = "prov-1",
        contextWindow: Int = 64_000,
        caps: ModelCapabilities = ModelCapabilities(toolCalling = true),
    ) = ModelProfile(
        id = "pf-$modelId",
        name = name,
        providerId = providerId,
        modelId = modelId,
        contextWindow = contextWindow,
        capabilities = caps,
    )

    // ═══ 回环（export → 自家 import 零信息丢失）═══

    @Test
    fun `round trip preserves provider base url and headers`() {
        val json = ModelConfigExporter.export(
            provider(headers = mapOf("X-Custom" to "v1")),
            listOf(profile("deepseek-chat"))
        )
        val cfg = ModelConfigImporter.import(json)
        assertEquals("DeepSeek 官方", cfg.providerName)
        assertEquals("https://api.deepseek.com/v1", cfg.baseUrl)
        assertEquals(mapOf("X-Custom" to "v1"), cfg.headers)
    }

    @Test
    fun `round trip preserves model entries`() {
        val json = ModelConfigExporter.export(
            provider(),
            listOf(
                profile(
                    "deepseek-chat", name = "对话档",
                    contextWindow = 64_000,
                    caps = ModelCapabilities(vision = false, toolCalling = true, reasoning = false)
                ),
                profile(
                    "deepseek-reasoner", name = "推理档",
                    contextWindow = 128_000,
                    caps = ModelCapabilities(vision = true, toolCalling = false, reasoning = true)
                ),
            )
        )
        val cfg = ModelConfigImporter.import(json)
        assertEquals(2, cfg.models.size)
        val first = cfg.models[0]
        assertEquals("deepseek-chat", first.modelId)
        assertEquals("对话档", first.displayName)
        assertEquals(64_000, first.contextWindow)
        assertEquals(false, first.vision)
        assertEquals(true, first.toolCalling)
        assertEquals(false, first.reasoning)
        val second = cfg.models[1]
        assertEquals("deepseek-reasoner", second.modelId)
        assertEquals("推理档", second.displayName)
        assertEquals(true, second.vision)
        assertEquals(false, second.toolCalling)
        assertEquals(true, second.reasoning)
    }

    @Test
    fun `exported json is detectable as json by auto format`() {
        val json = ModelConfigExporter.export(provider(), listOf(profile("m-1")))
        assertEquals(ModelConfigFormat.JSON, ModelConfigText.detect(json))
    }

    // ═══ 密钥纪律 ═══

    @Test
    fun `api key never appears in export`() {
        val json = ModelConfigExporter.export(
            provider().copy(apiKeys = listOf("sk-live-super-secret")),
            listOf(profile("m-1"))
        )
        assertFalse("导出文本绝不能包含密钥明文", json.contains("sk-live-super-secret"))
        assertNull("导入回读不得带出密钥", ModelConfigImporter.import(json).apiKey)
    }

    // ═══ 归属过滤 ═══

    @Test
    fun `foreign provider profiles are excluded`() {
        val json = ModelConfigExporter.export(
            provider(),
            listOf(
                profile("own-model"),
                profile("other-model", providerId = "prov-2")
            )
        )
        val cfg = ModelConfigImporter.import(json)
        assertEquals(listOf("own-model"), cfg.models.map { it.modelId })
    }

    @Test
    fun `blank model id profiles are skipped`() {
        val json = ModelConfigExporter.export(
            provider(),
            listOf(profile(""), profile("real-model"))
        )
        val cfg = ModelConfigImporter.import(json)
        assertEquals(listOf("real-model"), cfg.models.map { it.modelId })
    }

    // ═══ 空内容保护 ═══

    @Test
    fun `no base url and no models yields blank export`() {
        val json = ModelConfigExporter.export(
            provider(baseUrl = ""),
            listOf(profile(""))
        )
        assertEquals("", json)
    }

    @Test
    fun `blank profile name falls back to model id`() {
        val json = ModelConfigExporter.export(
            provider(),
            listOf(profile("m-1", name = ""))
        )
        val cfg = ModelConfigImporter.import(json)
        assertEquals("m-1", cfg.models[0].displayName)
    }

    @Test
    fun `blank provider name falls back to provider id`() {
        val json = ModelConfigExporter.export(
            provider().copy(displayName = ""),
            listOf(profile("m-1"))
        )
        val cfg = ModelConfigImporter.import(json)
        assertEquals("prov-1", cfg.providerName)
    }

    @Test
    fun `output is deterministic for same input`() {
        val p = provider()
        val list = listOf(profile("a"), profile("b"))
        assertEquals(
            "同一输入恒等输出（幂等，可安全重放）",
            ModelConfigExporter.export(p, list),
            ModelConfigExporter.export(p, list)
        )
    }
}
