package com.apex.agent.ui.chatkit

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/** 结构化输出格式选项。 */
enum class OutputFormat(val label: String) {
    NONE("关闭"),
    JSON("JSON"),
    XML("XML"),
    MARKDOWN_TABLE("Markdown Table"),
    YAML("YAML"),
    CUSTOM("自定义 Schema"),
}

/** 用户规则（可直接编写或从 .md 导入）。 */
@Serializable
data class ChatRule(
    val id: String,
    val title: String,
    val content: String,
    val enabled: Boolean = true,
)

/**
 * 对话输入框"迷你小圆环"工具菜单的单一可信状态源（SharedPreferences 持久化）。
 *
 * 五项能力：网络搜索 / 时间感知 / **强制函数调用（v4）** / 结构化输出 / 用户规则。
 * 每次发送消息前由 ViewModel 调 [buildSessionContext] 组装注入 system prompt
 * （经 `AgentConfig.additionalSystemContext`），并调 [forcedToolIds] +
 * [exposeAllTools] 驱动引擎的 v4 工具计划。
 *
 * v4 语义变更（根因修复）：旧版「函数调用」是**白名单**（不选=发全部~110个
 * 工具 schema，直接把请求撑爆→「直接发送就报错」；选了=只有这几个能用）。
 * 新版不选 = 默认 CORE 工具集 + 目录（全部能力可 ToolSearch/ToolOpen 按需
 * 加载）；选中 = **强制调用**（tool_choice=required/具体函数，被选中的函数
 * 必须被模型调用）。存续同一 SharedPreferences key，旧数据无缝升级。
 */
@Singleton
class ChatToolkitStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("apex_chat_toolkit", Context.MODE_PRIVATE)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _webSearchEnabled = MutableStateFlow(prefs.getBoolean(KEY_WEB_SEARCH, false))
    val webSearchEnabled: StateFlow<Boolean> = _webSearchEnabled.asStateFlow()

    /** 网络搜索通道提示（Coding 工位通道菜单写入；buildSessionContext 据此注入调用指引）。 */
    private val _searchChannelHint = MutableStateFlow(SearchChannelHint.fromStored(prefs.getString(KEY_SEARCH_CHANNEL, null)))
    val searchChannelHint: StateFlow<SearchChannelHint?> = _searchChannelHint.asStateFlow()

    private val _timeEnabled = MutableStateFlow(prefs.getBoolean(KEY_TIME, false))
    val timeEnabled: StateFlow<Boolean> = _timeEnabled.asStateFlow()

    private val _selectedFunctionIds = MutableStateFlow(
        prefs.getStringSet(KEY_FUNCTIONS, emptySet()) ?: emptySet()
    )
    val selectedFunctionIds: StateFlow<Set<String>> = _selectedFunctionIds.asStateFlow()

    private val _outputFormat = MutableStateFlow(
        runCatching { OutputFormat.valueOf(prefs.getString(KEY_FORMAT, OutputFormat.NONE.name)!!) }
            .getOrDefault(OutputFormat.NONE)
    )
    val outputFormat: StateFlow<OutputFormat> = _outputFormat.asStateFlow()

    private val _customSchema = MutableStateFlow(prefs.getString(KEY_SCHEMA, "") ?: "")
    val customSchema: StateFlow<String> = _customSchema.asStateFlow()

    private val _rules = MutableStateFlow(readRules())
    val rules: StateFlow<List<ChatRule>> = _rules.asStateFlow()

    // ── 开关 ─────────────────────────────────────────────────
    fun setWebSearchEnabled(enabled: Boolean) {
        _webSearchEnabled.value = enabled
        prefs.edit().putBoolean(KEY_WEB_SEARCH, enabled).apply()
    }

    /**
     * 网络搜索通道提示（持久化）：null = 未选通道（沿用 legacy 内置指引）。
     * 与 webSearchEnabled 解耦——MCP 通道激活时内置开关为 false，但提示
     * 仍在，[buildSessionContext] 据此注入 MCP 工具的调用指引。
     */
    fun setSearchChannelHint(hint: SearchChannelHint?) {
        _searchChannelHint.value = hint
        prefs.edit().putString(KEY_SEARCH_CHANNEL, SearchChannelHint.encode(hint)).apply()
    }

    fun setTimeEnabled(enabled: Boolean) {
        _timeEnabled.value = enabled
        prefs.edit().putBoolean(KEY_TIME, enabled).apply()
    }

    /** 函数调用（v4：强制语义）：圈选的工具本轮**必须**被调用（tool_choice）。 */
    fun setSelectedFunctionIds(ids: Set<String>) {
        _selectedFunctionIds.value = ids
        prefs.edit().putStringSet(KEY_FUNCTIONS, ids).apply()
    }

    fun toggleFunction(id: String) {
        val cur = _selectedFunctionIds.value
        setSelectedFunctionIds(if (id in cur) cur - id else cur + id)
    }

    fun clearFunctions() = setSelectedFunctionIds(emptySet())

    /** v4 — 全量工具模式（电源用户：暴露全部非 legacy 工具，仍受预算钳制）。 */
    private val _exposeAllTools = MutableStateFlow(prefs.getBoolean(KEY_EXPOSE_ALL, false))
    val exposeAllTools: StateFlow<Boolean> = _exposeAllTools.asStateFlow()

    fun setExposeAllTools(enabled: Boolean) {
        _exposeAllTools.value = enabled
        prefs.edit().putBoolean(KEY_EXPOSE_ALL, enabled).apply()
    }

    fun setOutputFormat(format: OutputFormat) {
        _outputFormat.value = format
        prefs.edit().putString(KEY_FORMAT, format.name).apply()
    }

    fun setCustomSchema(schema: String) {
        _customSchema.value = schema
        prefs.edit().putString(KEY_SCHEMA, schema).apply()
    }

    // ── 规则 CRUD ────────────────────────────────────────────
    fun upsertRule(rule: ChatRule) {
        val list = _rules.value.toMutableList()
        val idx = list.indexOfFirst { it.id == rule.id }
        if (idx >= 0) list[idx] = rule else list.add(rule)
        _rules.value = list
        persistRules()
    }

    fun deleteRule(id: String) {
        _rules.value = _rules.value.filter { it.id != id }
        persistRules()
    }

    fun setRuleEnabled(id: String, enabled: Boolean) {
        _rules.value = _rules.value.map { if (it.id == id) it.copy(enabled = enabled) else it }
        persistRules()
    }

    fun enabledRules(): List<ChatRule> = _rules.value.filter { it.enabled }

    // ── 发送前组装 ───────────────────────────────────────────

    /**
     * 组装注入 system prompt 的会话上下文（"## Session Context" 段落内容）。
     * 时间信息在调用时刻生成，保证每条消息携带实时时间。
     */
    fun buildSessionContext(): String = buildString {
        if (_timeEnabled.value) {
            val now = Date()
            val tz = TimeZone.getDefault()
            // #479：注入 prompt 的时间戳必须用固定 Locale —— 模型要解析它，
            // 而不是给人看。Locale.getDefault() 在泰语（佛历 2569）、阿拉伯语
            // （阿拉伯-印度数字 ٠٩）等区域会产出模型读不懂的年份/数字，
            // 直接毁掉「以此时钟为准」这条指令。中文星期另用 Locale.CHINA。
            val dateTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(now)
            val dateCn = SimpleDateFormat("yyyy年M月d日 EEEE", Locale.CHINA).format(now)
            appendLine("### 当前时间（实时注入）")
            appendLine("当前时间：$dateTime (${tz.id})")
            appendLine("当前日期：$dateCn")
            appendLine("回答与日期/星期/倒计时/时效性相关的问题时，必须以上述时间为准。")
            appendLine()
        }
        if (_webSearchEnabled.value || _searchChannelHint.value != null) {
            appendLine("### 网络搜索（强制）")
            when (_searchChannelHint.value?.kind) {
                SearchChannelHint.Kind.MCP -> {
                    appendLine("用户已启用本地搜索 MCP「${_searchChannelHint.value?.displayName}」。")
                    appendLine("涉及实时信息、新闻、天气、价格、文档、不确定的事实时，必须优先调用该服务器的")
                    appendLine("搜索工具（注册名形如 mcp__<server>__web_search，可用 ToolSearch/工具列表检索），")
                    appendLine("需要读取网页全文时调用其读取工具（如 mcp__<server>__web_read）。")
                    appendLine("搜索失败时如实说明，不要静默回退到训练知识猜测。")
                }
                SearchChannelHint.Kind.SEARXNG -> {
                    appendLine("用户已启用 SearXNG 本地元搜索（WebSearch 工具的链首源，本地服务已在运行）。")
                    appendLine("涉及实时信息、新闻、天气、价格、文档、不确定的事实时，必须先调用 WebSearch 获取")
                    appendLine("最新结果（必要时 WebFetch 读取全文）再作答——本地源优先于公共引擎，")
                    appendLine("禁止仅凭训练知识猜测。引用搜索结果中的关键信息时，注明来源标题与链接。")
                }
                else -> {
                    appendLine("用户已开启网络搜索（当前 Provider 支持时已同时启用模型内置联网搜索）。")
                    appendLine("若本轮响应已附带模型内置搜索的引用来源（**Sources:** 列表），直接基于其结果作答并保留来源标注；")
                    appendLine("否则，涉及实时信息、新闻、天气、价格、文档、不确定的事实时，必须先调用 WebSearch 工具")
                    appendLine("获取最新结果（必要时 WebFetch 读取全文）再作答，禁止仅凭训练知识猜测。")
                    appendLine("引用搜索结果中的关键信息时，注明来源标题与链接。")
                }
            }
            appendLine()
        }
        // v4：强制函数调用说明（引擎会同步发 tool_choice=required/具体函数）
        val forced = _selectedFunctionIds.value
        if (forced.isNotEmpty()) {
            appendLine("### 强制函数调用（用户指定）")
            appendLine("用户本轮指定了必须使用的函数（tool_choice 已设为强制）：")
            forced.sorted().forEach { appendLine("- $it") }
            appendLine("必须按任务需要调用上述函数；仅当它们执行失败时才说明原因并结束。")
            appendLine()
        }
        val enabled = enabledRules()
        if (enabled.isNotEmpty()) {
            appendLine("### 全局规则（必须遵守）")
            enabled.forEachIndexed { i, rule ->
                appendLine("${i + 1}. 【${rule.title}】${rule.content}")
            }
            appendLine()
        }
        when (_outputFormat.value) {
            OutputFormat.NONE -> Unit
            OutputFormat.JSON -> appendLine(
                "### 结构化输出（强制）\n" +
                    "请严格以 JSON 格式输出最终结果：不要包含任何额外文字、Markdown 代码块标记或前言后语，" +
                    "输出必须能被 JSON 解析器直接解析。"
            )
            OutputFormat.XML -> appendLine(
                "### 结构化输出（强制）\n" +
                    "请严格以 XML 格式输出最终结果：根元素为 <response>，不要包含任何额外文字或前言后语。"
            )
            OutputFormat.MARKDOWN_TABLE -> appendLine(
                "### 结构化输出（强制）\n" +
                    "请严格以 Markdown 表格输出最终结果：首行为表头、次行为分隔行，不要包含表格外文字。"
            )
            OutputFormat.YAML -> appendLine(
                "### 结构化输出（强制）\n" +
                    "请严格以 YAML 格式输出最终结果，不要包含任何额外文字或 Markdown 代码块标记。"
            )
            OutputFormat.CUSTOM -> {
                val schema = _customSchema.value.trim()
                if (schema.isNotEmpty()) {
                    appendLine("### 结构化输出（强制 · 自定义 Schema）")
                    appendLine("请严格按以下 Schema 输出结果，不要包含任何额外文字、Markdown 代码块标记或前言后语：")
                    appendLine(schema)
                }
            }
        }
    }.trim()

    /**
     * v4 — 强制调用的工具 id 集（空 = 默认模式：CORE + 会话激活，无强制）。
     */
    fun forcedToolIds(): Set<String> = _selectedFunctionIds.value

    /** v4 — 全量工具模式。 */
    fun exposeAllToolsEnabled(): Boolean = _exposeAllTools.value

    // ── 持久化 ───────────────────────────────────────────────
    private fun persistRules() =
        prefs.edit().putString(KEY_RULES, json.encodeToString(_rules.value)).apply()

    private fun readRules(): List<ChatRule> {
        val raw = prefs.getString(KEY_RULES, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<ChatRule>>(raw) }.getOrDefault(emptyList())
    }

    companion object {
        private const val KEY_WEB_SEARCH = "toolkit_web_search"
        private const val KEY_SEARCH_CHANNEL = "toolkit_search_channel"
        private const val KEY_TIME = "toolkit_time"
        private const val KEY_FUNCTIONS = "toolkit_functions"
        private const val KEY_FORMAT = "toolkit_output_format"
        private const val KEY_SCHEMA = "toolkit_custom_schema"
        private const val KEY_RULES = "toolkit_rules"
        private const val KEY_EXPOSE_ALL = "toolkit_expose_all"
    }
}

/**
 * 网络搜索通道提示（2026-11「agent 要会调用」：通道激活后模型侧的指名
 * 指引）——通道菜单写入 [ChatToolkitStore]，[ChatToolkitStore.buildSessionContext]
 * 按此把「该调哪个搜索工具」注入 system prompt。
 *
 * 不直接引用 Code 屏的 WebSearchChannel 枚举（chatkit 不反向依赖 code 屏），
 * 经 [encode]/[fromStored] 字符串编解码落盘（进程重启恢复）。
 */
data class SearchChannelHint(
    val kind: Kind,
    /** MCP 通道的服务器展示名（与 mcp__<name>__ 工具前缀同源）。 */
    val displayName: String? = null
) {
    enum class Kind { BUILTIN, SEARXNG, MCP }

    companion object {
        private const val MCP_PREFIX = "mcp:"

        fun encode(hint: SearchChannelHint?): String? = when (hint?.kind) {
            Kind.BUILTIN -> "builtin"
            Kind.SEARXNG -> "searxng"
            Kind.MCP -> MCP_PREFIX + hint.displayName.orEmpty()
            null -> null
        }

        fun fromStored(raw: String?): SearchChannelHint? = when {
            raw == null -> null
            raw == "builtin" -> SearchChannelHint(Kind.BUILTIN)
            raw == "searxng" -> SearchChannelHint(Kind.SEARXNG)
            raw.startsWith(MCP_PREFIX) -> SearchChannelHint(Kind.MCP, raw.removePrefix(MCP_PREFIX))
            else -> null
        }
    }
}




