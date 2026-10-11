package com.apex.agent.plugin.host.functional

import com.apex.agent.core.tools.AgentTool
import com.apex.agent.core.tools.ToolAnnotations
import com.apex.agent.core.tools.ToolCategory
import com.apex.agent.core.tools.ToolExecutor
import com.apex.agent.core.tools.ToolMetadata
import com.apex.agent.core.tools.ToolRegistry
import com.apex.agent.core.tools.ToolRisk
import com.apex.agent.plugin.api.PluginContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * # 宏工具适配器（功能性插件工具贡献 → AgentTool）
 *
 * 功能性插件（Plugin System v2）的 tools 贡献点是「宏」：对既有宿主工具的
 * **声明式组合**。本适配器把一个 [ToolContribution] 变成注册进
 * [ToolRegistry] 的一等工具——执行即顺序跑宏步骤，数据在步间经变量域流动：
 *
 * ```
 * {"id":"briefing/run", "steps":[
 *   {"tool":"browser_navigate", "args":{"url":"{{input.site}}"}, "output":"page"},
 *   {"tool":"browser_extract_content", "output":"body"},
 *   {"tool":"agent_message_user", "args":{"message":"今日要闻：{{body}}"}}
 * ]}
 * ```
 *
 * ## 变量规则
 * - `{{input}}`：调用入参的原始 JSON 文本；
 * - `{{input.field}}`：入参顶层字段（primitive → 字面值；对象/数组 → 紧凑 JSON）；
 * - `{{name}}`：某一步 `output` 捕获的完整工具输出（体积钳制见下）；
 * - 插值只发生在**步骤 args 顶层字符串值**里；嵌套对象 / 非字符串值原样透传。
 * - 未解析的变量 = 该步诚实失败（fail closed：绝不静默替换成空串——那会把
 *   半截参数发给宿主工具，错误被延迟且难以归因）。
 *
 * ## 信任边界（与 APK 插件 [com.apex.agent.plugin.host.PluginManager] 同哲学）
 * - **风险钳制**：宏工具风险 = max(MEDIUM, 各步骤已注册工具的风险)——宏是
 *   组合体，风险不低于任何被组合步骤与 MEDIUM 下限（对齐 APK 插件工具的
 *   `max(推断, MEDIUM, 宿主现有)` 口径）。未注册的步骤工具按 MEDIUM 参与下限
 *   （执行期命中再如实失败）——因为 MCP / 插件工具是热注册的，激活时刻的
 *   注册表不代表执行时刻；
 * - **readOnly 强制 false**：宏可组合写操作，权限决策链不为其开只读静默通道；
 * - **openWorld = true**：对齐插件 / MCP 工具口径——外部导入的数据组合出的
 *   输出按不可信内容处理；
 * - **永不 REPLACE 内置工具**：功能性插件是数据，覆盖二进制能力是 APK 插件
 *   （同签名信任）才有的权利——注册策略由 [FunctionalPluginRuntime] 以
 *   REJECT 落地，冲突即跳过 + WARN。
 *
 * ## 执行纪律
 * - 步骤经注入的 [ToolExecutor] 管线执行（查找建议 / schema 校验 / 错误折叠
 *   全部继承）——注意**门控（gate）不在此层**：外层宏工具调用的风险门是安全
 *   边界（宏风险已按步骤最大值钳制，用户确认一次「危险宏」= 已确认其全部
 *   高危步骤），内部步骤不再二次弹窗；
 * - 任一步输出以 "Error" 开头 → 短路失败（v1 字符串协议，SafeAgentTool 同款
 *   判定），错误信息携带步号与工具 id，模型可直接归因重试；
 * - 变量体积钳制：[PluginContract.MACRO_VARIABLE_MAX_BYTES]，保头 3KB + 尾
 *   1KB（首部结论 + 尾部状态是现实工作流最有信息量的两段）；
 * - 全程 [Dispatchers.IO]（对齐 APK 插件的 PluginAgentTool 线程模型）。
 */
class MacroToolAdapter(
    private val pluginId: String,
    private val contribution: ToolContribution,
    private val scope: String,
    private val executor: ToolExecutor,
    private val registry: ToolRegistry
) : AgentTool {

    override val id: String get() = contribution.id

    override val name: String get() = contribution.displayName

    override val description: String get() = contribution.description.ifBlank { id }

    override val parametersSchema: String
        get() = contribution.parametersSchema ?: EMPTY_TOOL_SCHEMA

    /**
     * 信任钳制后的元数据（构造期一次固化——激活时刻注册表快照的风险
     * 锚点；热注册的新工具不回填，但执行期命中未知工具会诚实失败）。
     */
    override val metadata: ToolMetadata = clampedMetadata()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun execute(arguments: String): String = withContext(Dispatchers.IO) {
        // 防线 0：安装期已限步数，运行期再钳一道（manifest 可能经热通道被改写）。
        if (contribution.steps.isEmpty()) {
            return@withContext "Error: macro '$id' has no steps (broken plugin data)"
        }
        if (contribution.steps.size > PluginContract.MACRO_MAX_STEPS) {
            return@withContext "Error: macro '$id' exceeds ${PluginContract.MACRO_MAX_STEPS} steps"
        }
        val input = runCatching { json.parseToJsonElement(arguments.ifBlank { "{}" }) }
            .getOrNull() as? JsonObject
            ?: return@withContext "Error: macro '$id' arguments must be a JSON object: $arguments"

        val vars = HashMap<String, String>()
        val trace = StringBuilder()
        var lastOutput = ""
        contribution.steps.forEachIndexed { index, step ->
            val stepArgs = interpolateArgs(step.args, input, vars)
                ?: return@withContext stepError(
                    index, step.tool,
                    "无法解析插值变量（检查 {{input.field}} 引用与上一步 output 声明）"
                )
            val result = runCatching { executor.execute(step.tool, stepArgs) }
                .getOrElse { "Error: step executor threw: ${it.message ?: it::class.simpleName}" }
            if (result.startsWith("Error")) {
                return@withContext stepError(index, step.tool, result.removePrefix("Error:").trim())
            }
            trace.append("step ").append(index + 1).append(' ').append(step.tool)
                .append(": ok (").append(result.length).append(" chars)")
                .append('\n')
            if (!step.output.isNullOrBlank()) {
                vars[step.output.trim()] = clampVariable(result)
            }
            lastOutput = result
        }
        buildString {
            append("OK: 功能性插件 '").append(pluginId).append("' 工具 '").append(id)
            append("' 完成 ").append(contribution.steps.size).append(" 步。\n")
            append(lastOutput)
            append('\n').append("— 宏执行轨迹 —\n").append(trace.toString().trimEnd())
        }
    }

    // ── 变量插值 ──────────────────────────────────────────────────────

    /**
     * 步骤 args 插值：顶层字符串值内的 `{{变量}}` 逐个解析替换；其余值
     * （primitive / 嵌套对象 / 数组）原样保留。存在未解析变量 → null
     * （调用方折成该步诚实失败）。
     */
    private fun interpolateArgs(
        args: JsonObject?,
        input: JsonObject,
        vars: Map<String, String>
    ): String? {
        if (args == null) return "{}"
        val resolved = LinkedHashMap<String, JsonElement>(args.size)
        for ((key, value) in args) {
            val primitive = value as? JsonPrimitive
            if (primitive != null && primitive.isString) {
                val text = primitive.content
                if (!text.contains(VARIABLE_TOKEN)) {
                    resolved[key] = value
                    continue
                }
                val substituted = substituteVariables(text, input, vars) ?: return null
                resolved[key] = JsonPrimitive(substituted)
            } else {
                resolved[key] = value
            }
        }
        return JsonObject(resolved).toString()
    }

    /** 单字符串的 `{{...}}` 全量替换；任何未解析引用 → null。 */
    private fun substituteVariables(
        text: String,
        input: JsonObject,
        vars: Map<String, String>
    ): String? {
        var ok = true
        val result = VARIABLE_PATTERN.replace(text) { match ->
            val resolved = resolveVariable(match.groupValues[1].trim(), input, vars)
            if (resolved == null) {
                ok = false
                match.value
            } else {
                resolved
            }
        }
        return if (ok) result else null
    }

    /** 变量名解析：`input` / `input.field` / 步骤输出变量。 */
    private fun resolveVariable(name: String, input: JsonObject, vars: Map<String, String>): String? {
        return when {
            name == "input" -> clampVariable(json.encodeToString(JsonObject.serializer(), input))
            name.startsWith("input.") -> {
                val field = name.removePrefix("input.")
                if (field.isEmpty()) return null
                input[field]?.let { element ->
                    when (element) {
                        is JsonPrimitive -> element.contentOrNull ?: element.toString()
                        is JsonObject, is JsonArray -> element.toString()
                    }
                }
            }
            else -> vars[name]
        }
    }

    // ── 钳制 / 错误格式化 ─────────────────────────────────────────────

    /** 风险钳制元数据（见类 KDoc「信任边界」）。 */
    private fun clampedMetadata(): ToolMetadata {
        val inferred = ToolMetadata.infer(id)
        val stepRisk = contribution.steps
            .mapNotNull { step -> registry.metadataOf(step.tool)?.risk }
            .maxOrNull()
        val risk = maxOf(inferred.risk, ToolRisk.MEDIUM, stepRisk ?: ToolRisk.LOW)
        return ToolMetadata(
            id = id,
            category = ToolCategory.PLUGIN,
            risk = risk,
            tags = inferred.tags + listOf("plugin", "functional"),
            annotations = ToolAnnotations(
                readOnlyHint = false,
                destructiveHint = inferred.annotations.destructiveHint,
                idempotentHint = false,
                openWorldHint = true
            ),
            scope = ToolMetadata.normalizeScope(scope)
        )
    }

    /** 步失败文案（步号 1 起计，携带工具 id 与根因——模型可直接归因）。 */
    private fun stepError(index: Int, tool: String, reason: String): String =
        "Error: 功能性插件宏 '$id' 第 ${index + 1} 步（工具 '$tool'）失败: $reason"

    /**
     * 变量体积钳制（[PluginContract.MACRO_VARIABLE_MAX_BYTES] 字符）：超限保
     * 头 3072 + 尾 1024，中段折叠为字节计数标记——首部结论与尾部状态是
     * 现实工具输出里信息密度最高的两段（网页正文摘要 / 执行状态尾行）。
     */
    private fun clampVariable(value: String): String {
        val limit = PluginContract.MACRO_VARIABLE_MAX_BYTES
        if (value.length <= limit) return value
        val head = value.substring(0, limit - TAIL_KEEP_CHARS)
        val tail = value.substring(value.length - TAIL_KEEP_CHARS)
        return head + "\n…[变量超长已折叠，原文 " + value.length + " 字符]…\n" + tail
    }

    private companion object {
        /** 插值 token（快速预检，避免无占位符字符串走正则）。 */
        const val VARIABLE_TOKEN = "{{"

        /** `{{name}}` 插值模式（name 允许字母数字 / 点 / 下划线 / 中划线）。 */
        val VARIABLE_PATTERN: Regex = Regex("\\{\\{\\s*([a-zA-Z0-9_.-]+)\\s*\\}\\}")

        /** 与 APK 插件工具一致的空 schema 占位（宽松导入 = 不校验）。 */
        const val EMPTY_TOOL_SCHEMA = """{"type":"object","properties":{}}"""

        /** 钳制尾部保留字符数（头 3KB + 尾 1KB = 4KB 上限）。 */
        const val TAIL_KEEP_CHARS = 1024
    }
}
