package com.apex.agent.core.llm.share

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** 单个导入的模型条目（能力位 null = 未声明，UI 落盘时用 [com.apex.agent.core.llm.ModelCapabilityHeuristics] 推断合并）。 */
data class ImportedModel(
    val modelId: String,
    val displayName: String,
    val contextWindow: Int? = null,
    val vision: Boolean? = null,
    val toolCalling: Boolean? = null,
    val reasoning: Boolean? = null,
)

/** 解析成功的导入载荷 —— UI 把它落成 Provider + ModelProfile。 */
data class ImportedModelConfig(
    val providerName: String,
    val baseUrl: String,
    val apiKey: String?,
    val headers: Map<String, String>,
    val models: List<ImportedModel>,
)

/**
 * 导入失败分型（对齐 [ShareCodecError] 的「值语义分型 + 异常桥」模式）。
 *
 * - [ParseFailure]      —— 文本层坏（[ModelConfigTextException] 桥接，message 透传）；
 * - [NothingRecognized] —— 文本合法但没有 base_url / api_key / model 任何一项，
 *                          通常是用户粘错了内容（贴了段无关文本 / 无关 JSON）。
 */
sealed class ModelConfigImportException(message: String) : Exception(message) {
    /** 文本解析失败（携带原始 [ModelConfigTextException] 供 UI 透传人读消息）。 */
    class ParseFailure(val error: ModelConfigTextException) :
        ModelConfigImportException(error.message ?: "parse failed")

    /** 文本合法但没有任何可识别的模型配置字段。 */
    class NothingRecognized(message: String) : ModelConfigImportException(message)
}

/**
 * # 模型配置导入器（JsonElement 树 → 导入载荷）
 *
 * 用户需求：模型配置支持直接粘贴 Python / JSON / YAML / TOML 导入。
 * [ModelConfigText] 负责把文本解析成 JsonElement；本对象负责**语义识别**：
 * 从五花八门的键名拼写里认出 base_url / api_key / models，聚合成
 * [ImportedModelConfig]。
 *
 * ## 容错哲学
 * 现实里的配置来源极其混杂（OpenAI 官方文档、LiteLLM 的 config.yaml、各种
 * SDK 示例代码），键名拼写无规范可言 —— 所以全部查找都过 [normalize]
 * （小写 + 去 `-` / `_` / 空格），`Base-URL` / `base_url` / `baseUrl` 同等
 * 对待；找不到的字段静默缺省（null / 空列表），**绝不**因为多一个陌生键
 * 而失败 —— 失败只留给两种硬情形：文本解析不动（ParseFailure）、三大
 * 识别键一个都没有（NothingRecognized）。
 *
 * ## 查找策略
 * - 根对象先查，再进一层深的容器对象（provider / config / client / settings
 *   这类包装键，LiteLLM / OpenAI SDK 的配置常多包一层）；
 * - 模型清单三形态：单值 `model`、列表 `models` / `model_list`、OpenAI
 *   `GET /models` 响应的 `data` 数组；
 * - per-model 的 litellm_params 里出现 api_key / api_base 而顶层缺失时，
 *   取第一个非空的补位（LiteLLM 每个模型可带独立端点凭证）。
 */
object ModelConfigImporter {

    // ── 候选键表（全部以 normalize 后的形态比对）──────────────────────
    private val BASE_URL_KEYS = listOf(
        "baseurl", "apibase", "apibaseurl", "apiendpoint", "endpoint",
        "endpointurl", "url", "server", "serverurl", "openaiapiurl"
    )
    private val API_KEY_KEYS = listOf(
        "apikey", "key", "token", "secret", "bearer", "authorization", "sk"
    )
    private val MODEL_LIST_KEYS = listOf("models", "modellist")
    private val SINGLE_MODEL_KEYS = listOf("model", "modelid", "modelname")
    private val PROVIDER_NAME_KEYS = listOf("provider", "providername", "name", "displayname", "title")
    private val HEADER_KEYS = listOf("headers", "extraheaders", "defaultheaders", "customheaders")
    private val CONTAINER_KEYS = listOf(
        "provider", "config", "llm", "api", "client", "openai",
        "settings", "options", "defaults", "connection", "connections"
    )
    private val ENTRY_MODEL_ID_KEYS = listOf("id", "model", "modelid", "modelname")
    private val ENTRY_DISPLAY_KEYS = listOf("name", "displayname", "modelname")
    private val CONTEXT_KEYS = listOf(
        "contextwindow", "contextlength", "maxcontexttokens", "maxcontext", "maxinputtokens", "context"
    )
    private val VISION_KEYS = listOf("vision", "imageinput", "imageinputs")
    private val TOOL_KEYS = listOf("toolcalling", "tools", "functioncalling")
    private val REASONING_KEYS = listOf("reasoning", "thinking")

    /** 解析并识别导入载荷。失败抛 [ModelConfigImportException] 分型异常。 */
    fun import(text: String, format: ModelConfigFormat = ModelConfigFormat.AUTO): ImportedModelConfig {
        val root = try {
            ModelConfigText.parse(text, format)
        } catch (e: ModelConfigTextException) {
            throw ModelConfigImportException.ParseFailure(e)
        }

        // 查找作用域：根对象（或数组）+ 一层深的容器对象
        val scopes = mutableListOf<JsonObject>()
        if (root is JsonObject) scopes.add(root)
        if (root is JsonArray) {
            // 顶层直接是数组（贴了段模型清单）：包一层走统一路径
            scopes.add(JsonObject(mapOf("models" to root)))
        }
        collectContainerScopes(root, scopes)

        var baseUrl = firstString(scopes, BASE_URL_KEYS).orEmpty()
        var apiKey = firstString(scopes, API_KEY_KEYS)
        val providerName = firstString(scopes, PROVIDER_NAME_KEYS).orEmpty()
        val headers = firstHeaders(scopes)

        val collector = ModelCollector()
        collectModels(scopes, collector)

        // per-model 兜底：LiteLLM 的 litellm_params 常自带 api_key / api_base
        if (apiKey.isNullOrBlank()) apiKey = collector.perModelApiKey
        if (baseUrl.isBlank()) baseUrl = collector.perModelBaseUrl.orEmpty()

        if (baseUrl.isBlank() && apiKey.isNullOrBlank() && collector.models.isEmpty()) {
            throw ModelConfigImportException.NothingRecognized(
                "no model config recognized (need at least one of base_url / api_key / model)"
            )
        }

        return ImportedModelConfig(
            providerName = providerName.ifBlank { deriveProviderName(baseUrl) },
            baseUrl = baseUrl,
            apiKey = apiKey?.takeIf { it.isNotBlank() },
            headers = headers,
            models = collector.models,
        )
    }

    /** 收集状态：模型列表 + per-model 凭证兜底（LiteLLM 形态）。 */
    private class ModelCollector {
        val models = mutableListOf<ImportedModel>()
        var perModelApiKey: String? = null
        var perModelBaseUrl: String? = null
    }

    // ── 作用域收集 ─────────────────────────────────────────────────

    /** 把一层深的容器对象（含 connections 数组）纳入查找作用域。 */
    private fun collectContainerScopes(root: JsonElement, out: MutableList<JsonObject>) {
        val rootObj = root as? JsonObject ?: return
        for (ck in CONTAINER_KEYS) {
            when (val child = rootObj.entries.firstOrNull { normalize(it.key) == ck }?.value) {
                is JsonObject -> out.add(child)
                is JsonArray -> {
                    // connections 之类列表：取第一个带 baseUrl 候选键的条目
                    child.filterIsInstance<JsonObject>()
                        .firstOrNull { entry -> firstString(listOf(entry), BASE_URL_KEYS) != null }
                        ?.let { out.add(it) }
                }
                else -> { /* 标量 / null 容器键（provider: openai 这类）：无子对象可进 */ }
            }
        }
    }

    /** 在作用域列表里按候选键序找第一个非空字符串值。 */
    private fun firstString(scopes: List<JsonObject>, keys: List<String>): String? {
        for (scope in scopes) {
            for (key in keys) {
                val v = scope.entries.firstOrNull { normalize(it.key) == key }?.value
                    ?: continue
                val s = v.stringValue() ?: continue
                if (s.isNotBlank()) return s
            }
        }
        return null
    }

    /** 首个命中的 headers 类键 → 字符串 Map（非字符串值跳过）。 */
    private fun firstHeaders(scopes: List<JsonObject>): Map<String, String> {
        for (scope in scopes) {
            for (key in HEADER_KEYS) {
                val v = scope.entries.firstOrNull { normalize(it.key) == key }?.value ?: continue
                val obj = v as? JsonObject ?: continue
                val map = LinkedHashMap<String, String>()
                obj.forEach { (k, hv) -> hv.stringValue()?.let { map[k] = it } }
                if (map.isNotEmpty()) return map
            }
        }
        return emptyMap()
    }

    // ── 模型收集 ──────────────────────────────────────────────────

    /** 三形态收集：列表（models / model_list / data）在前，单值（model）垫底。 */
    private fun collectModels(scopes: List<JsonObject>, out: ModelCollector) {
        for (scope in scopes) {
            val normalized = normalizedEntries(scope)
            for (key in MODEL_LIST_KEYS) {
                val v = normalized[key] ?: continue
                if (v is JsonArray) v.forEach { entry -> parseModelEntry(entry, out) }
            }
            // OpenAI GET /models 响应：{"object":"list","data":[{id:...}]}。
            // 要求全部条目都是带 id/model 键的对象 —— 防误吞无关的 data 字段。
            val data = normalized["data"]
            if (data is JsonArray && data.isNotEmpty() &&
                data.all { it is JsonObject && hasAnyKey(it, ENTRY_MODEL_ID_KEYS) }
            ) {
                data.forEach { entry -> parseModelEntry(entry, out) }
            }
        }
        if (out.models.isEmpty()) {
            for (scope in scopes) {
                val normalized = normalizedEntries(scope)
                for (key in SINGLE_MODEL_KEYS) {
                    val v = normalized[key] ?: continue
                    val s = v.stringValue() ?: continue
                    if (s.isNotBlank()) {
                        out.models.add(ImportedModel(modelId = s, displayName = s))
                        return
                    }
                }
            }
        }
    }

    /** 单个列表条目（裸字符串或对象）→ [ImportedModel]；LiteLLM 形态在此展开。 */
    private fun parseModelEntry(entry: JsonElement, out: ModelCollector) {
        if (entry is JsonPrimitive && entry !is JsonNull) {
            val s = entry.contentOrNull.orEmpty()
            if (s.isNotBlank()) out.models.add(ImportedModel(modelId = s, displayName = s))
            return
        }
        if (entry !is JsonObject) return
        val norm = normalizedEntries(entry)

        // LiteLLM model_list 条目：model_name（显示名）+ litellm_params{model, api_base, api_key}
        val litellm = norm["litellmparams"] as? JsonObject
        if (litellm != null) {
            val litellmNorm = normalizedEntries(litellm)
            val modelId = firstStringOf(litellmNorm, listOf("model", "modelid", "modelname"))
                .ifBlank { firstStringOf(norm, listOf("modelname", "model", "id")) }
            if (modelId.isBlank()) return
            recordPerModelCredentials(litellmNorm, out)
            out.models.add(
                ImportedModel(
                    modelId = modelId,
                    displayName = firstStringOf(norm, ENTRY_DISPLAY_KEYS).ifBlank { modelId },
                    contextWindow = firstIntOf(norm, CONTEXT_KEYS),
                    vision = firstBoolOf(norm, VISION_KEYS),
                    toolCalling = firstBoolOf(norm, TOOL_KEYS),
                    reasoning = reasoningOf(norm),
                )
            )
            return
        }

        val modelId = firstStringOf(norm, ENTRY_MODEL_ID_KEYS)
        if (modelId.isBlank()) return
        out.models.add(
            ImportedModel(
                modelId = modelId,
                displayName = firstStringOf(norm, ENTRY_DISPLAY_KEYS).ifBlank { modelId },
                contextWindow = firstIntOf(norm, CONTEXT_KEYS),
                vision = firstBoolOf(norm, VISION_KEYS),
                toolCalling = firstBoolOf(norm, TOOL_KEYS),
                reasoning = reasoningOf(norm),
            )
        )
    }

    /** litellm_params 里的 api_key / api_base：顶层缺失时的兜底来源（取首个非空）。 */
    private fun recordPerModelCredentials(litellmNorm: Map<String, JsonElement>, out: ModelCollector) {
        if (out.perModelApiKey == null) {
            out.perModelApiKey = API_KEY_KEYS.firstNotNullOfOrNull { key -> litellmNorm[key]?.stringValue() }
        }
        if (out.perModelBaseUrl == null) {
            out.perModelBaseUrl = BASE_URL_KEYS.firstNotNullOfOrNull { key -> litellmNorm[key]?.stringValue() }
        }
    }

    /** reasoning 位：布尔键优先；reasoning_effort 存在且不为 off 视为 true。 */
    private fun reasoningOf(norm: Map<String, JsonElement>): Boolean? {
        firstBoolOf(norm, REASONING_KEYS)?.let { return it }
        val effort = norm["reasoningeffort"]?.stringValue() ?: return null
        return effort.isNotBlank() && !effort.equals("off", ignoreCase = true)
    }

    // ── 工具 ─────────────────────────────────────────────────────

    /** 键名规范化：小写 + 去 `-` / `_` / 空格（含首尾空白）。 */
    private fun normalize(key: String): String =
        key.trim().lowercase().filterNot { it == '-' || it == '_' || it == ' ' }

    /** 对象 → {规范化键: 值}（保留原值；键重复时后者覆盖）。 */
    private fun normalizedEntries(obj: JsonObject): Map<String, JsonElement> {
        val m = LinkedHashMap<String, JsonElement>()
        obj.forEach { (k, v) -> m[normalize(k)] = v }
        return m
    }

    private fun hasAnyKey(obj: JsonObject, keys: List<String>): Boolean {
        val norm = normalizedEntries(obj)
        return keys.any { norm.containsKey(it) }
    }

    private fun firstStringOf(norm: Map<String, JsonElement>, keys: List<String>): String {
        for (key in keys) {
            val s = norm[key]?.stringValue() ?: continue
            if (s.isNotBlank()) return s
        }
        return ""
    }

    private fun firstIntOf(norm: Map<String, JsonElement>, keys: List<String>): Int? {
        for (key in keys) {
            val v = norm[key] ?: continue
            val p = v as? JsonPrimitive ?: continue
            if (p is JsonNull) continue
            val i = p.contentOrNull?.toIntOrNull()
            if (i != null && i > 0) return i
        }
        return null
    }

    /** 布尔识别：接受 true/false 字面量与 "true"/"yes"/"on" 等字符串形态。 */
    private fun firstBoolOf(norm: Map<String, JsonElement>, keys: List<String>): Boolean? {
        for (key in keys) {
            val v = norm[key] ?: continue
            if (v !is JsonPrimitive || v is JsonNull) continue
            if (!v.isString) return v.contentOrNull == "true"
            when (v.contentOrNull?.lowercase()) {
                "true", "yes", "on" -> return true
                "false", "no", "off" -> return false
                else -> continue
            }
        }
        return null
    }

    /** JsonElement → 非空字符串（仅字符串原始值；数字 / 布尔 / 对象 / 数组 / null → null）。 */
    private fun JsonElement.stringValue(): String? {
        if (this !is JsonPrimitive || this is JsonNull || !isString) return null
        return contentOrNull?.takeIf { it.isNotBlank() }
    }

    /** 服务商名缺省推导：baseUrl host（如 api.siliconflow.cn 原样作为名称）。 */
    private fun deriveProviderName(baseUrl: String): String {
        if (baseUrl.isBlank()) return "Imported"
        val host = runCatching { java.net.URI(baseUrl).host }.getOrNull()
        return host?.takeIf { it.isNotBlank() } ?: "Imported"
    }
}
