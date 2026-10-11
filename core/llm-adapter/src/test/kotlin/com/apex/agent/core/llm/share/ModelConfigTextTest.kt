package com.apex.agent.core.llm.share

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [ModelConfigText] 单元测试（多语言模型配置导入 · Task 5）。
 *
 * 仓库测试纪律：JUnit4、无 mock、断言值先读源码核实。
 * 覆盖矩阵：四格式解析（JSON 严格 / Python 字面量 + 代码形状 / YAML 块结构 +
 * flow / TOML 表 + 数组）与识别（detect）、以及失败路径（空文本 / 乱码 /
 * 不支持结构给出带行号的明确错误）。
 */
class ModelConfigTextTest {

    // ── JSON ─────────────────────────────────────────────────────

    @Test
    fun jsonFlatObjectParsesAndDetects() {
        val text = """{"base_url": "https://api.example.com/v1", "api_key": "sk-1", "model": "gpt-4o"}"""
        assertEquals(ModelConfigFormat.JSON, ModelConfigText.detect(text))
        val obj = ModelConfigText.parse(text).jsonObject
        assertEquals("https://api.example.com/v1", obj["base_url"]!!.jsonPrimitive.content)
        assertEquals("sk-1", obj["api_key"]!!.jsonPrimitive.content)
        assertEquals("gpt-4o", obj["model"]!!.jsonPrimitive.content)
    }

    @Test
    fun jsonNestedOpenAiStyleShape() {
        val text = """
            {
              "provider": {
                "name": "My Provider",
                "base_url": "https://api.example.com/v1",
                "api_key": "sk-1"
              },
              "models": [
                {"id": "gpt-4o", "vision": true},
                {"id": "gpt-4o-mini"}
              ]
            }
        """.trimIndent()
        val obj = ModelConfigText.parse(text).jsonObject
        val provider = obj["provider"]!!.jsonObject
        assertEquals("My Provider", provider["name"]!!.jsonPrimitive.content)
        val models = obj["models"]!!.jsonArray
        assertEquals(2, models.size)
        assertEquals(true, models[0].jsonObject["vision"]!!.jsonPrimitive.boolean)
        assertEquals("gpt-4o-mini", models[1].jsonObject["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun jsonModelsEndpointDataList() {
        val text = """
            {
              "object": "list",
              "data": [
                {"id": "gpt-4o", "object": "model", "owned_by": "openai"},
                {"id": "gpt-4o-mini", "object": "model", "owned_by": "openai"}
              ]
            }
        """.trimIndent()
        val data = ModelConfigText.parse(text).jsonObject["data"]!!.jsonArray
        assertEquals(2, data.size)
        assertEquals("gpt-4o", data[0].jsonObject["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun jsonWithCommentsIsRejectedInStrictMode() {
        val text = """
            {
              // 单行注释（JSON 严格模式必须拒绝，注释容忍走 Python 分支）
              "base_url": "https://api.example.com/v1"
            }
        """.trimIndent()
        try {
            ModelConfigText.parse(text, ModelConfigFormat.JSON)
            fail("strict JSON must reject comments")
        } catch (expected: ModelConfigTextException) {
            assertTrue(expected.message!!.isNotBlank())
        }
    }

    // ── Python ───────────────────────────────────────────────────

    @Test
    fun pythonDictWithSingleQuotesTrailingCommaAndComments() {
        val text = """
            {
                'base_url': 'https://api.example.com/v1',
                'api_key': 'sk-123',
                'temperature': 0.7,
                'stream': True,
                'timeout': None,
                'models': ['gpt-4o', 'gpt-4o-mini',],  # 尾逗号 + 行注释
            }
        """.trimIndent()
        assertEquals(ModelConfigFormat.PYTHON, ModelConfigText.detect(text))
        val obj = ModelConfigText.parse(text).jsonObject
        assertEquals("https://api.example.com/v1", obj["base_url"]!!.jsonPrimitive.content)
        assertEquals(0.7, obj["temperature"]!!.jsonPrimitive.double, 1e-9)
        assertEquals(true, obj["stream"]!!.jsonPrimitive.boolean)
        assertTrue(obj["timeout"] is JsonNull)
        assertEquals(2, obj["models"]!!.jsonArray.size)
    }

    @Test
    fun pythonOpenAiClientCallKwargs() {
        val text = """
            from openai import OpenAI

            client = OpenAI(api_key="sk-1", base_url="https://api.example.com/v1")
        """.trimIndent()
        assertEquals(ModelConfigFormat.PYTHON, ModelConfigText.detect(text))
        val obj = ModelConfigText.parse(text).jsonObject
        assertEquals("sk-1", obj["api_key"]!!.jsonPrimitive.content)
        assertEquals("https://api.example.com/v1", obj["base_url"]!!.jsonPrimitive.content)
    }

    @Test
    fun pythonConfigDictAssignmentTakesTheDict() {
        val text = """
            # 模型配置
            config = {
                "base_url": "https://api.example.com/v1",
                "model": "deepseek-chat",
            }
        """.trimIndent()
        val obj = ModelConfigText.parse(text).jsonObject
        assertEquals("https://api.example.com/v1", obj["base_url"]!!.jsonPrimitive.content)
        assertEquals("deepseek-chat", obj["model"]!!.jsonPrimitive.content)
    }

    @Test
    fun pythonBareIdentifierKeysAndValues() {
        val text = "{base_url: 'https://api.example.com/v1', retries: 3, debug: True}"
        val obj = ModelConfigText.parse(text, ModelConfigFormat.PYTHON).jsonObject
        assertEquals("https://api.example.com/v1", obj["base_url"]!!.jsonPrimitive.content)
        assertEquals(3L, obj["retries"]!!.jsonPrimitive.long)
        assertEquals(true, obj["debug"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun pythonMultipleScalarAssignmentsBuildSyntheticObject() {
        val text = """
            BASE_URL = "https://api.example.com/v1"
            API_KEY = "sk-9"
            MODEL = "gpt-4o"
        """.trimIndent()
        val obj = ModelConfigText.parse(text, ModelConfigFormat.PYTHON).jsonObject
        assertEquals("https://api.example.com/v1", obj["BASE_URL"]!!.jsonPrimitive.content)
        assertEquals("sk-9", obj["API_KEY"]!!.jsonPrimitive.content)
        assertEquals("gpt-4o", obj["MODEL"]!!.jsonPrimitive.content)
    }

    // ── YAML ──────────────────────────────────────────────────────

    @Test
    fun yamlNestedMapWithModelList() {
        val text = """
            provider:
              name: MyProvider
              base_url: https://api.example.com/v1
            api_key: sk-1
            models:
              - id: gpt-4o
                vision: true
              - id: o3-mini
                reasoning: yes
        """.trimIndent()
        assertEquals(ModelConfigFormat.YAML, ModelConfigText.detect(text))
        val obj = ModelConfigText.parse(text).jsonObject
        val provider = obj["provider"]!!.jsonObject
        assertEquals("MyProvider", provider["name"]!!.jsonPrimitive.content)
        assertEquals("https://api.example.com/v1", provider["base_url"]!!.jsonPrimitive.content)
        val models = obj["models"]!!.jsonArray
        assertEquals(2, models.size)
        assertEquals(true, models[0].jsonObject["vision"]!!.jsonPrimitive.boolean)
        // YAML 1.1 的 yes/no 也是布尔
        assertEquals(true, models[1].jsonObject["reasoning"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun yamlLiteLlmModelListShape() {
        val text = """
            model_list:
              - model_name: gpt-4o
                litellm_params:
                  model: openai/gpt-4o
                  api_base: https://api.example.com/v1
                  api_key: sk-1
              - model_name: claude-3
                litellm_params:
                  model: anthropic/claude-3
        """.trimIndent()
        val obj = ModelConfigText.parse(text).jsonObject
        val list = obj["model_list"]!!.jsonArray
        assertEquals(2, list.size)
        val first = list[0].jsonObject
        assertEquals("gpt-4o", first["model_name"]!!.jsonPrimitive.content)
        val params = first["litellm_params"]!!.jsonObject
        assertEquals("openai/gpt-4o", params["model"]!!.jsonPrimitive.content)
        assertEquals("sk-1", params["api_key"]!!.jsonPrimitive.content)
    }

    @Test
    fun yamlQuotedScalarsCommentsAndTypedScalars() {
        val text = """
            # 顶部注释
            name: "Qwen Plus"  # 行内注释
            desc: 'It''s a model'
            count: 42
            ratio: 1.5
            enabled: false
            empty: ~
        """.trimIndent()
        val obj = ModelConfigText.parse(text).jsonObject
        assertEquals("Qwen Plus", obj["name"]!!.jsonPrimitive.content)
        assertEquals("It's a model", obj["desc"]!!.jsonPrimitive.content)
        assertEquals(42L, obj["count"]!!.jsonPrimitive.long)
        assertEquals(1.5, obj["ratio"]!!.jsonPrimitive.double, 1e-9)
        assertEquals(false, obj["enabled"]!!.jsonPrimitive.boolean)
        assertTrue(obj["empty"] is JsonNull)
    }

    @Test
    fun yamlSimpleFlowCollections() {
        val text = """
            models: [gpt-4o, gpt-4o-mini]
            limits: {max_tokens: 4096, vision: true}
        """.trimIndent()
        val obj = ModelConfigText.parse(text).jsonObject
        assertEquals(2, obj["models"]!!.jsonArray.size)
        val limits = obj["limits"]!!.jsonObject
        assertEquals(4096L, limits["max_tokens"]!!.jsonPrimitive.long)
        assertEquals(true, limits["vision"]!!.jsonPrimitive.boolean)
    }

    // ── TOML ─────────────────────────────────────────────────────

    @Test
    fun tomlTableWithModelsArrayOfInlineTables() {
        val text = """
            [provider]
            name = "My Provider"
            base_url = "https://api.example.com/v1"
            api_key = 'sk-1'

            models = [
                { id = "gpt-4o", vision = true },
                { id = "o3-mini", reasoning = true },
            ]
        """.trimIndent()
        assertEquals(ModelConfigFormat.TOML, ModelConfigText.detect(text))
        val obj = ModelConfigText.parse(text).jsonObject
        val provider = obj["provider"]!!.jsonObject
        assertEquals("My Provider", provider["name"]!!.jsonPrimitive.content)
        assertEquals("sk-1", provider["api_key"]!!.jsonPrimitive.content)
        val models = provider["models"]!!.jsonArray
        assertEquals(2, models.size)
        assertEquals(true, models[0].jsonObject["vision"]!!.jsonPrimitive.boolean)
        assertEquals("o3-mini", models[1].jsonObject["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun tomlDottedTableAndArrayOfTables() {
        val text = """
            title = "My Config"

            [a.b]
            endpoint = "https://api.example.com/v1"

            [[models]]
            id = "gpt-4o"

            [[models]]
            id = "o3-mini"
        """.trimIndent()
        val obj = ModelConfigText.parse(text).jsonObject
        assertEquals("My Config", obj["title"]!!.jsonPrimitive.content)
        val ab = (obj["a"] as JsonObject)["b"]!!.jsonObject
        assertEquals("https://api.example.com/v1", ab["endpoint"]!!.jsonPrimitive.content)
        val models = obj["models"]!!.jsonArray
        assertEquals(2, models.size)
        assertEquals("gpt-4o", models[0].jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("o3-mini", models[1].jsonObject["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun tomlUnderscoredIntegersAndFloats() {
        val text = """
            [limits]
            context_window = 1_000_000
            temperature = 0.7
            created = 2024-01-01
        """.trimIndent()
        val limits = ModelConfigText.parse(text).jsonObject["limits"]!!.jsonObject
        assertEquals(1000000L, limits["context_window"]!!.jsonPrimitive.long)
        assertEquals(0.7, limits["temperature"]!!.jsonPrimitive.double, 1e-9)
        // 日期按字符串透传（模型配置里日期无数值语义）
        assertEquals("2024-01-01", limits["created"]!!.jsonPrimitive.content)
    }

    // ── 错误路径 ─────────────────────────────────────────────────

    @Test
    fun emptyTextThrowsClearError() {
        try {
            ModelConfigText.parse("   \n  ")
            fail("blank input must be rejected")
        } catch (expected: ModelConfigTextException) {
            assertTrue(expected.message!!.contains("empty", ignoreCase = true))
        }
    }

    @Test
    fun garbageTextThrows() {
        try {
            ModelConfigText.parse("hello world this is not a config")
            fail("garbage must be rejected")
        } catch (expected: ModelConfigTextException) {
            assertTrue(expected.message!!.isNotBlank())
        }
    }

    @Test
    fun yamlBlockScalarThrowsClearError() {
        val text = """
            description: |
              line one
              line two
        """.trimIndent()
        try {
            ModelConfigText.parse(text, ModelConfigFormat.YAML)
            fail("block scalars must be rejected with a clear message")
        } catch (expected: ModelConfigTextException) {
            assertTrue(expected.message!!.contains("block scalars"))
        }
    }

    @Test
    fun autoFallsBackWhenDetectionGuessesWrong() {
        // 单引号 Python 字典：识别为 JSON 会失败，AUTO 应落 Python 兜底链
        val text = "{'base_url': 'https://api.example.com/v1'}"
        val obj = ModelConfigText.parse(text).jsonObject
        assertEquals("https://api.example.com/v1", obj["base_url"]!!.jsonPrimitive.content)
    }

    @Test
    fun detectCoversAllFormats() {
        assertEquals(
            ModelConfigFormat.TOML,
            ModelConfigText.detect("[provider]\nbase_url = \"https://a.com\"")
        )
        assertEquals(
            ModelConfigFormat.TOML,
            ModelConfigText.detect("base_url = \"https://a.com\"")
        )
        assertEquals(
            ModelConfigFormat.PYTHON,
            ModelConfigText.detect("from openai import OpenAI\nclient = OpenAI()")
        )
        assertEquals(
            ModelConfigFormat.YAML,
            ModelConfigText.detect("base_url: https://a.com")
        )
        assertEquals(
            ModelConfigFormat.JSON,
            ModelConfigText.detect("[1, 2, 3]")
        )
    }
}
