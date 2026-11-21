package com.apex.agent.core.llm.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [ModelConfigImporter] 单元测试（多语言模型配置导入 · Task 5）。
 *
 * 覆盖矩阵：键名规范化（camelCase / snake_case / 大写连字符）、一层深容器
 * （client / openai / settings）、LiteLLM model_list + litellm_params、OpenAI
 * /models data 响应形态、per-model 凭证兜底、headers 提取（非字符串值跳过）、
 * 服务商名推导（baseUrl host）、失败分型（ParseFailure / NothingRecognized）。
 */
class ModelConfigImporterTest {

    // ── 键名规范化 ────────────────────────────────────────────────

    @Test
    fun keyNormalizationVariantsAllResolve() {
        for (text in listOf(
            """{"baseUrl": "https://a.example.com/v1", "apiKey": "sk-1"}""",
            """{"base_url": "https://a.example.com/v1", "api_key": "sk-1"}""",
            """{"BASE-URL": "https://a.example.com/v1", "API KEY": "sk-1"}""",
            """{"baseURL": "https://a.example.com/v1", "api-key": "sk-1"}""",
        )) {
            val cfg = ModelConfigImporter.import(text)
            assertEquals("https://a.example.com/v1", cfg.baseUrl)
            assertEquals("sk-1", cfg.apiKey)
        }
    }

    @Test
    fun apiKeyCandidateKeys() {
        for (key in listOf("api_key", "key", "token", "secret", "bearer")) {
            val cfg = ModelConfigImporter.import("""{"base_url": "https://a.example.com/v1", "$key": "sk-x"}""")
            assertEquals("candidate key \"$key\" should resolve", "sk-x", cfg.apiKey)
        }
    }

    // ── 一层深容器 ───────────────────────────────────────────────

    @Test
    fun baseUrlFromNestedClientObject() {
        val cfg = ModelConfigImporter.import(
            """
            {
              "client": {"base_url": "https://api.example.com/v1", "api_key": "sk-nested"},
              "model": "gpt-4o"
            }
            """.trimIndent()
        )
        assertEquals("https://api.example.com/v1", cfg.baseUrl)
        assertEquals("sk-nested", cfg.apiKey)
        assertEquals(1, cfg.models.size)
        assertEquals("gpt-4o", cfg.models[0].modelId)
    }

    @Test
    fun baseUrlFromOpenAiSdkStyleNesting() {
        val cfg = ModelConfigImporter.import(
            """
            {
              "openai": {"api_key": "sk-1", "base_url": "https://api.openai.com/v1"},
              "default_headers": {"x-title": "apex"}
            }
            """.trimIndent()
        )
        assertEquals("https://api.openai.com/v1", cfg.baseUrl)
        assertEquals("sk-1", cfg.apiKey)
    }

    // ── LiteLLM 形态 ────────────────────────────────────────────

    @Test
    fun litellmModelListWithParams() {
        val text = """
            model_list:
              - model_name: gpt-4o
                litellm_params:
                  model: openai/gpt-4o
                  api_base: https://api.example.com/v1
                  api_key: sk-litellm
              - model_name: claude-3
                litellm_params:
                  model: anthropic/claude-3
        """.trimIndent()
        val cfg = ModelConfigImporter.import(text)
        // per-model 兜底：顶层无 base_url / api_key 时取 litellm_params 的
        assertEquals("https://api.example.com/v1", cfg.baseUrl)
        assertEquals("sk-litellm", cfg.apiKey)
        assertEquals(2, cfg.models.size)
        assertEquals("openai/gpt-4o", cfg.models[0].modelId)
        assertEquals("gpt-4o", cfg.models[0].displayName)
        assertEquals("anthropic/claude-3", cfg.models[1].modelId)
    }

    // ── OpenAI /models 响应形态 ──────────────────────────────────

    @Test
    fun openAiModelsEndpointDataShape() {
        val cfg = ModelConfigImporter.import(
            """
            {
              "object": "list",
              "data": [
                {"id": "gpt-4o", "object": "model", "owned_by": "openai"},
                {"id": "gpt-4o-mini", "object": "model", "owned_by": "openai"}
              ]
            }
            """.trimIndent()
        )
        assertEquals(2, cfg.models.size)
        assertEquals("gpt-4o", cfg.models[0].modelId)
        assertEquals("gpt-4o-mini", cfg.models[1].modelId)
        assertEquals("", cfg.baseUrl)
        assertNull(cfg.apiKey)
    }

    // ── 模型能力 + 上下文 ───────────────────────────────────────

    @Test
    fun capabilitiesAndContextWindowFromModelEntries() {
        val cfg = ModelConfigImporter.import(
            """
            {
              "base_url": "https://a.example.com/v1",
              "models": [
                {"id": "gpt-4o", "vision": true, "tool_calling": false, "reasoning_effort": "high", "context_window": 128000},
                {"id": "text-only"}
              ]
            }
            """.trimIndent()
        )
        val first = cfg.models[0]
        assertEquals(true, first.vision)
        assertEquals(false, first.toolCalling)
        assertEquals(true, first.reasoning) // reasoning_effort != off
        assertEquals(128000, first.contextWindow)
        // 未声明的能力位保持 null（UI 侧再与启发式推断合并）
        assertNull(cfg.models[1].vision)
        assertNull(cfg.models[1].toolCalling)
        assertNull(cfg.models[1].reasoning)
        assertNull(cfg.models[1].contextWindow)
    }

    @Test
    fun modelsListOfBareStrings() {
        val cfg = ModelConfigImporter.import("""{"models": ["gpt-4o", "claude-3-5"]}""")
        assertEquals(2, cfg.models.size)
        assertEquals("gpt-4o", cfg.models[0].modelId)
        assertEquals("gpt-4o", cfg.models[0].displayName)
    }

    // ── 服务商名 / headers ───────────────────────────────────────

    @Test
    fun providerNameDerivedFromBaseUrlHost() {
        val cfg = ModelConfigImporter.import(
            """{"base_url": "https://api.siliconflow.cn/v1", "models": ["Qwen/Qwen2.5-72B-Instruct"]}"""
        )
        assertEquals("api.siliconflow.cn", cfg.providerName)
    }

    @Test
    fun providerNameExplicitKeyWins() {
        val cfg = ModelConfigImporter.import(
            """{"provider": "DeepSeek", "base_url": "https://api.deepseek.com/v1", "model": "deepseek-chat"}"""
        )
        assertEquals("DeepSeek", cfg.providerName)
    }

    @Test
    fun headersExtractionSkipsNonStringValues() {
        val cfg = ModelConfigImporter.import(
            """
            {
              "base_url": "https://a.example.com/v1",
              "headers": {"X-Region": "cn-east-1", "X-Count": 3, "X-Obj": {"a": 1}}
            }
            """.trimIndent()
        )
        assertEquals(mapOf("X-Region" to "cn-east-1"), cfg.headers)
    }

    @Test
    fun defaultImportedNameWhenNothingElseKnown() {
        val cfg = ModelConfigImporter.import("""{"model": "some-model"}""")
        assertEquals("Imported", cfg.providerName)
    }

    // ── Python 代码端到端 ────────────────────────────────────────

    @Test
    fun pythonOpenAiSnippetEndToEnd() {
        val text = """
            from openai import OpenAI

            client = OpenAI(api_key="sk-1", base_url="https://api.example.com/v1")
            models = ["gpt-4o", "gpt-4o-mini"]
        """.trimIndent()
        val cfg = ModelConfigImporter.import(text)
        assertEquals("https://api.example.com/v1", cfg.baseUrl)
        assertEquals("sk-1", cfg.apiKey)
        assertEquals(2, cfg.models.size)
    }

    // ── 失败分型 ────────────────────────────────────────────────

    @Test
    fun nothingRecognizedWhenNoConfigKeys() {
        try {
            ModelConfigImporter.import("""{"foo": "bar", "count": 3}""")
            fail("valid JSON without any config key must be NothingRecognized")
        } catch (expected: ModelConfigImportException.NothingRecognized) {
            assertTrue(expected.message!!.contains("base_url"))
        }
    }

    @Test
    fun parseFailureWrapsTextException() {
        try {
            ModelConfigImporter.import("totally not a config !!!")
            fail("garbage input must surface as ParseFailure")
        } catch (expected: ModelConfigImportException.ParseFailure) {
            assertTrue(expected.message!!.isNotBlank())
            assertTrue(expected.error.message!!.isNotBlank())
        }
    }

    @Test
    fun explicitFormatIsRespected() {
        // 显式 YAML：带 # 的 JSON 注释形状会失败，YAML 口径下同文本合法
        try {
            ModelConfigImporter.import("""{"a": 1} # trailing""", ModelConfigFormat.JSON)
            fail("strict JSON with trailing comment must fail")
        } catch (expected: ModelConfigImportException.ParseFailure) {
            assertTrue(expected.message!!.contains("json"))
        }
    }
}
