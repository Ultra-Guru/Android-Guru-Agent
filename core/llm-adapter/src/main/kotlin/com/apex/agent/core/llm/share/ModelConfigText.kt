package com.apex.agent.core.llm.share

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/** 模型配置文本的源格式（[ModelConfigText.parse] 的 AUTO 会先识别再解析）。 */
enum class ModelConfigFormat { AUTO, JSON, PYTHON, YAML, TOML }

/**
 * 模型配置文本解析失败（人读消息，尽力携带行号）。
 *
 * 与 [ShareDecodeException] 同层定位：底层文本 → JsonElement 的纯解析错误，
 * 与导入语义（[ModelConfigImporter]）无关 —— 语义层通过
 * [ModelConfigImportException.ParseFailure] 把本异常桥接到 UI。
 */
class ModelConfigTextException(message: String) : Exception(message)

/**
 * # 模型配置文本解析器（JSON / Python / YAML / TOML → JsonElement）
 *
 * 用户需求：模型配置支持直接粘贴 Python、JSON 等语言的配置导入。
 * 现实中「一份模型配置」散落在各种形态里 —— JSON 文件、Python 字典字面量、
 * `OpenAI(api_key=...)` 客户端代码、LiteLLM 的 YAML、pyproject 风格的 TOML。
 * 本对象把四种文本统一解析成 kotlinx JsonElement 树，语义映射（base_url /
 * api_key / models 的识别）交给 [ModelConfigImporter] —— 解析与语义分层，
 * 加第五种格式时新增一个同包解析器文件并在 [parseWith] 挂上即可。
 *
 * ## 设计取舍
 * - **零外部依赖**：仓库纪律是 kotlinx.serialization JSON only。PyYAML /
 *   toml4j 不进依赖，这里全部手写子集解析器（Python / YAML / TOML 各自
 *   独立成文件：[PythonTextParser] / [YamlTextParser] / [TomlTextParser]）
 *   —— 模型配置是浅层结构（两三层嵌套 + 标量），不需要完整语言实现；不
 *   支持的结构（YAML 锚点 / 多行块标量、TOML 多行字符串）抛带行号的明确
 *   错误，让用户改写而不是静默丢数据。
 * - **JSON 走 kotlinx 官方解析**（严格模式）：JSON 是唯一有官方解析器的格式，
 *   注释 / 尾逗号等「JSON5」内容按 JSON 解析失败 → 自动落 Python 字典解析
 *   （Python 字面量语法是 JSON 的严格超集，天然兜底）。
 * - **AUTO 兜底链**：识别（[detect]）是启发式的，猜错不致命 —— 主格式失败后
 *   按 JSON → Python → YAML → TOML 顺序再试，全部失败才抛首个格式的原始错误。
 *
 * 线程安全：object 单例、全部无状态纯函数。
 */
object ModelConfigText {

    /** 严格 JSON：模型配置不该容忍注释 / 尾逗号（那是 Python 分支的活）。 */
    private val json = Json

    // ── 识别用的启发式正则（见 detect 的判定顺序注释）──────────────────
    private val PY_IMPORT_LINE = Regex("""(?m)^\s*(import|from)\s""")
    private val PY_CALL_LIKE = Regex("""[A-Za-z_]\w*\s*\(""")
    private val PY_ASSIGN_DICT = Regex("""=\s*\{""")
    private val TOML_TABLE = Regex("""(?m)^\[+[^]\n]+\]+\s*$""")
    private val TOML_ASSIGN = Regex("""(?m)^[ \t]*[A-Za-z0-9_"'\-][^=\n]*=""")
    private val YAML_INT = Regex("""^-?\d+$""")
    private val YAML_FLOAT =
        Regex("""^-?(\d+\.\d*|\.\d+)([eE][+-]?\d+)?$|^-?\d+[eE][+-]?\d+$""")

    /**
     * 启发式识别格式（trim 后判）：
     *
     * 1. `{` 开头 → 严格 JSON 试解析：成功 JSON，失败 Python（Python 字典常用
     *    单引号 / 尾逗号 / 裸键）；
     * 2. `[` 开头 → 三选：严格 JSON（数组）→ TOML 表头（`[table]` / `[[x]]`
     *    行首形状，JSON 解不动时优先 TOML 再考虑 Python）→ Python（列表字面量）；
     * 3. Python 代码标记（import / from 行、`Ident(` 调用、`= {` 字典赋值）
     *    → PYTHON —— 代码形状只有 Python 有，优先于 TOML 的 `k = v`；
     * 4. TOML 表头 `[x]` / `[a.b]` / 顶层 `k = v` → TOML（纯标量赋值两种语言
     *    同形，按更常见的 TOML 配置文件口径处理，解析结果两种都兼容）；
     * 5. 其余（`key: value` 行）→ YAML。
     */
    fun detect(text: String): ModelConfigFormat {
        val t = text.trim()
        if (t.isEmpty()) return ModelConfigFormat.YAML
        if (t.startsWith("{")) {
            return if (runCatching { json.parseToJsonElement(t) }.isSuccess) {
                ModelConfigFormat.JSON
            } else {
                ModelConfigFormat.PYTHON
            }
        }
        if (t.startsWith("[")) {
            return when {
                runCatching { json.parseToJsonElement(t) }.isSuccess -> ModelConfigFormat.JSON
                // 行首 [x] / [[x]] 形状是 TOML 表头（Python 列表字面量不会带表头）
                TOML_TABLE.containsMatchIn(t) -> ModelConfigFormat.TOML
                else -> ModelConfigFormat.PYTHON
            }
        }
        if (PY_IMPORT_LINE.containsMatchIn(t) ||
            PY_CALL_LIKE.containsMatchIn(t) ||
            PY_ASSIGN_DICT.containsMatchIn(t)
        ) return ModelConfigFormat.PYTHON
        if (TOML_TABLE.containsMatchIn(t) || TOML_ASSIGN.containsMatchIn(t)) {
            return ModelConfigFormat.TOML
        }
        return ModelConfigFormat.YAML
    }

    /**
     * 按指定格式解析（AUTO = 先识别，失败后按 JSON → Python → YAML → TOML
     * 兜底）。任何失败抛 [ModelConfigTextException]（带行号的尽量给行号）。
     */
    fun parse(text: String, format: ModelConfigFormat = ModelConfigFormat.AUTO): JsonElement {
        if (text.isBlank()) throw ModelConfigTextException("input text is empty")
        var lastFallbackError: ModelConfigTextException? = null
        return when (format) {
            ModelConfigFormat.AUTO -> {
                val primary = detect(text)
                val primaryError = try {
                    return parseWith(text, primary)
                } catch (e: ModelConfigTextException) {
                    e
                }
                for (f in listOf(
                    ModelConfigFormat.JSON, ModelConfigFormat.PYTHON,
                    ModelConfigFormat.YAML, ModelConfigFormat.TOML
                )) {
                    if (f == primary) continue
                    try {
                        return parseWith(text, f)
                    } catch (e: ModelConfigTextException) {
                        // 识别猜错格式很常见（Python 标量赋值长得像 TOML），
                        // 逐个兜底重试；全部失败时用主格式的原始错误上报
                        lastFallbackError = e
                    }
                }
                throw ModelConfigTextException(
                    "could not parse input as ${primary.name.lowercase()} " +
                        "(${primaryError.message}); other formats also failed"
                )
            }
            else -> parseWith(text, format)
        }
    }

    private fun parseWith(text: String, format: ModelConfigFormat): JsonElement = when (format) {
        // kotlinx 的 SerializationException 折叠成统一分型（调用方只认
        // ModelConfigTextException，不认库内部异常类型）
        ModelConfigFormat.JSON -> try {
            json.parseToJsonElement(text.trim())
        } catch (e: Exception) {
            throw ModelConfigTextException("json error: ${e.message?.take(160) ?: "invalid JSON"}")
        }
        ModelConfigFormat.PYTHON -> PythonTextParser.parse(text)
        ModelConfigFormat.YAML -> YamlTextParser.parse(text)
        ModelConfigFormat.TOML -> TomlTextParser.parse(text)
        ModelConfigFormat.AUTO -> throw ModelConfigTextException("internal: AUTO must be resolved before parseWith")
    }

    /** 数字字面量 → JsonPrimitive（int 优先，含 . / e 才落 double）。 */
    internal fun numberJson(raw: String): JsonPrimitive {
        if (raw.contains('.') || raw.contains('e') || raw.contains('E')) {
            return raw.toDoubleOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(raw)
        }
        return raw.toLongOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(raw)
    }

    /** YAML 裸标量 → 按类型规则落到 null / bool / int / float / string。 */
    internal fun typedScalar(raw: String): JsonElement = when (raw) {
        "", "~", "null", "Null", "NULL" -> JsonNull
        "true", "True", "TRUE", "yes", "Yes", "YES", "on", "On", "ON" -> JsonPrimitive(true)
        "false", "False", "FALSE", "no", "No", "NO", "off", "Off", "OFF" -> JsonPrimitive(false)
        else -> when {
            // OrNull：超 Long/Double 范围的病态标量按原字符串落 JSON，
            // 而非让整个导入失败（与上方裸标量分支同款防御）
            YAML_INT.matches(raw) -> raw.toLongOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(raw)
            YAML_FLOAT.matches(raw) -> raw.toDoubleOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(raw)
            else -> JsonPrimitive(raw)
        }
    }
}
