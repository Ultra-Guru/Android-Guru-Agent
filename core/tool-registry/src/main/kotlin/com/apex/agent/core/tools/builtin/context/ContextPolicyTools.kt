package com.apex.agent.core.tools.builtin.context

import com.apex.agent.core.engine.ContextPreset
import com.apex.agent.core.engine.ContextPolicyState
import com.apex.agent.core.tools.ToolAnnotations
import com.apex.agent.core.tools.ToolCategory
import com.apex.agent.core.tools.ToolErrorCode
import com.apex.agent.core.tools.ToolMetadata
import com.apex.agent.core.tools.ToolResult
import com.apex.agent.core.tools.ToolRisk
import com.apex.agent.core.tools.builtin.BaseTool
import com.apex.agent.core.tools.toolSchema

/**
 * `ContextPolicyGet` — 上下文策略查询（P99）。
 *
 * 返回当前档位 / 最近轮保护层数 / 压缩阈值基数 / 会话是否已压缩 /
 * 历史 token 估算 / 逻辑线标识。数据源经构造 lambda 注入（app 侧接引擎
 * `currentContextPolicyState()`——同源不另起计数）。
 *
 * **无关闭开关**：压缩前抢救（SessionContextArchive）保护恒生效，
 * 本工具只读不写。
 */
class ContextPolicyGet(
    /** 状态源（null = 引擎未挂载——fail-open 说明而非报错）。 */
    private val state: () -> ContextPolicyState?
) : BaseTool(
    id = "ContextPolicyGet",
    name = "Context Policy Get",
    description = """
        Read the current context-governance policy: preset (or host default),
        keep-last-N turns, compression threshold base tokens, whether this
        conversation has been compressed, estimated history tokens and the
        active logic line. Read-only. Use before ContextPolicySet to see the
        current state and allowed presets.
    """.trimIndent(),
    declaredSchema = toolSchema {}
) {
    override fun buildMetadata(): ToolMetadata = ToolMetadata.meta(id) {
        category(ToolCategory.CONTEXT)
        risk(ToolRisk.LOW)
        tag("context", "policy", "compression", "governance")
        annotations(ToolAnnotations.readOnly())
    }

    override suspend fun executeStructured(arguments: String): ToolResult {
        val s = state()
            ?: return ToolResult.ok(
                "context policy pipeline not wired in this environment — " +
                    "host defaults apply (see engine config); ContextPolicySet unavailable"
            )
        return ToolResult.ok(
            buildString {
                appendLine("preset: ${s.preset?.name ?: "host_default"}")
                appendLine("keepLastN: ${s.keepLastN}")
                appendLine("maxTokens: ${s.maxTokens}")
                appendLine("compressed: ${s.compressed}")
                appendLine("tokenEstimate: ~${s.tokenEstimate}")
                appendLine("line: ${s.line}")
                appendLine("exportCapable: ${s.exportCapable} (PromptDump)")
                appendLine("allowed presets: ${ContextPreset.ALLOWED.joinToString("/")}")
            }
        )
    }
}

/**
 * `ContextPolicySet` — 上下文策略调节（P99）。
 *
 * **白名单档位**（preset ∈ [ContextPreset.ALLOWED]，成对绑定 keepLastN +
 * maxTokens；不提供任意覆写）；**逐次确认**（ToolRisk.MEDIUM 非只读 →
 * RiskAwareToolGate.requiresPrompt）；写入走构造 lambda（app 侧接
 * `SettingsRepository.updateAgentSettings`，原子 tmp+renameTo）；**下轮生效**。
 * 错误契约：非法档位 → 列全允许值的可操作错误（不裸异常）。
 */
class ContextPolicySet(
    /** 写入通道（app 侧：settingsRepository.updateAgentSettings { copy(contextPolicyPreset = …) }）。 */
    private val write: (preset: Int, reason: String?) -> Boolean
) : BaseTool(
    id = "ContextPolicySet",
    name = "Context Policy Set",
    description = """
        Set the context-governance preset (whitelist: 3/5/8/10/15/20/30/50/100).
        Binds keep-last-N turns and the compression threshold base as a pair.
        Takes effect NEXT turn (current prompt unchanged). Use ContextPolicyGet
        first to see allowed values. Cannot disable pre-compression rescue
        protection.
    """.trimIndent(),
    declaredSchema = toolSchema {
        integer("preset", required = true, description = "Whitelist preset value: 3/5/8/10/15/20/30/50/100 (0 = host default; keepLastN and maxTokens bind as a pair)")
        string("reason", description = "Why this preset fits the current task (recorded in audit, values never logged)")
    }
) {
    override fun buildMetadata(): ToolMetadata = ToolMetadata.meta(id) {
        category(ToolCategory.CONTEXT)
        risk(ToolRisk.MEDIUM)
        tag("context", "policy", "compression", "governance", "write")
        // 非只读：RiskAwareToolGate.requiresPrompt = MEDIUM 且非 readOnlyHint → 逐次确认
    }

    override suspend fun executeStructured(arguments: String): ToolResult {
        val args = when (val parsed = ToolArguments.of(arguments)) {
            is ToolArguments.ParseOutcome.Ok -> parsed.args
            is ToolArguments.ParseOutcome.Bad -> return parsed.result
        }
        val raw = args.optionalInt("preset")
            ?: return ToolResult.invalid("preset", "preset is required", "use one of ${ContextPreset.ALLOWED.joinToString('/')}")
        if (raw == 0) {
            // 0 = 宿主默认（清除自定义档位）
            val ok = write(0, args.stringWithDefault("reason", ""))
            return if (ok) ToolResult.ok("preset cleared → host defaults (next turn)") else writeFailed()
        }
        val preset = ContextPreset.from(raw)
            ?: return ToolResult.invalid(
                "preset",
                "Unsupported preset $raw. Allowed: ${ContextPreset.ALLOWED.joinToString("/")}. Use ContextPolicyGet() first.",
                "pick one of ${ContextPreset.ALLOWED.joinToString('/')}"
            )
        val reason = args.stringWithDefault("reason", "")
        val ok = write(preset.keepLastN, reason)
        return if (ok) {
            ToolResult.ok(
                buildString {
                    appendLine("preset: ${preset.name} → keepLastN=${preset.keepLastN}, maxTokens=${preset.maxTokens}")
                    appendLine("takes effect NEXT turn (current prompt unchanged)")
                    appendLine("pre-compression rescue protection: always on (not disableable)")
                }
            )
        } else writeFailed()
    }

    private fun writeFailed(): ToolResult = ToolResult.fail(
        ToolErrorCode.EXECUTION_FAILED,
        "failed to persist preset (settings write failed — see logs); policy unchanged"
    )

}
