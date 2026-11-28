package com.apex.agent.core.tools.catalog

import com.apex.agent.core.llm.ToolDefinition
import com.apex.agent.core.tools.AgentTool
import com.apex.agent.core.tools.ToolRegistry

/**
 * # Tool System v4 — Request tool plan (budgeted, provider-safe)
 *
 * Single assembly point for "which tools go into THIS request".
 *
 * Fixes three failure classes at once:
 * 1. **Payload explosion** — every request used to carry all ~110 schemas
 *    (~70–100 KB). The plan clamps count ([MAX_TOOLS]) and serialized size
 *    ([MAX_TOTAL_BYTES]);
 * 2. **Invalid provider names** — dotted ids are mapped through
 *    [ToolNameSanitizer]; the plan also carries the reverse map so the
 *    engine resolves model-echoed names back to registry ids;
 * 3. **Broken schemas / giant descriptions** — [ToolSchemaSanitizer] repairs
 *    and clamps every entry.
 *
 * Ordering is deterministic (sorted by provider name) — provider-side prompt
 * caches key on the tools array, so stable order = cheaper requests
 * (rikkahub-agent lesson).
 *
 * # 两级预算（真机 P0 修复：CORE 工具曾被静默丢弃）
 *
 * CORE 集从 v4 初版 41 个长到 75 个，而 `MAX_TOOLS=64` 自初版未动 ——
 * 名字序第 65 位起（`TerminalObserve/Run/Snapshot/Wait`、`Time`、
 * `ToolList/Open/Search`、`WebFetch/WebSearch`、`Write`）每轮请求被静默
 * 丢弃且无人记录：模型拿不到写文件 / 联网 / 工具发现函数，提示词却仍在
 * 教它用 `ToolSearch` → 「agent 什么都做不成」。
 *
 * 现行语义：**优先级条目（CORE / forced）无条件进入请求**（它们是设计上
 * 的常驻集，不该受扩展预算挤压）；**扩展条目**（会话激活 + 已连接服务 +
 * exposeAll 长尾）共享 [MAX_EXTENSION_TOOLS] 条数与 [MAX_TOTAL_BYTES] 字节
 * 预算。`droppedByBudget` 由引擎侧 EnginePlanDiagnostics 落日志，不再静默。
 */
object ToolRequestBudget {

    /**
     * 扩展条目（非 CORE / 非 forced）每请求的条数上限。CORE 不占此额度。
     * 48 = 覆盖 ToolActivationStore.maxActive(24) + 已连接服务（GitHub 10 /
     * MCP 检索）后仍有余量；exposeAll 长尾按名字序截断。
     */
    const val MAX_EXTENSION_TOOLS = 48

    /** 兼容常量：单请求工具条数上限的口径（扩展条目受此约束，CORE 豁免）。 */
    const val MAX_TOOLS = MAX_EXTENSION_TOOLS

    /** 扩展条目序列化后的字节预算（CORE 豁免 —— 常驻集按设计全量下发）。 */
    const val MAX_TOTAL_BYTES = 48_000

    /**
     * @param tools the assembled request list (provider-safe names,
     *        sanitized schemas, clamped descriptions, name-sorted);
     * @param providerNameToId reverse map for execution routing;
     * @param visibleRegistryIds registry ids listed in the system prompt;
     * @param totalBytes serialized size of the tools array;
     * @param droppedByBudget ids excluded after the budget filled
     *        (empty in normal operation — catalog covers them).
     */
    data class RequestToolPlan(
        val tools: List<ToolDefinition>,
        val providerNameToId: Map<String, String>,
        val visibleRegistryIds: Set<String>,
        val totalBytes: Int,
        val droppedByBudget: List<String>
    )

    /**
     * Default plan: CORE ∪ session-activated ∪ connected-service tools
     * (∪ everything when [exposeAll]); legacy aliases never ship;
     * budget-clamped; catalog meta-tools guaranteed present.
     *
     * @param coreOnly degradation level 1: pure CORE set, no session
     *        activations, no exposeAll — used by the engine after a
     *        provider rejected the previous tool payload.
     * @param serviceToolIds ids of tools whose backing service is CONNECTED
     *        (github_* when a token is configured, McpSearch* when the
     *        builtin search server is up, …). They join the request with
     *        CORE-tier visibility so the system prompt's "Connected Services"
     *        claims always match the actual tools array (P0 fix). Unknown ids
     *        are ignored during assembly.
     */
    fun planDefault(
        registry: ToolRegistry,
        activation: ToolActivationStore,
        exposeAll: Boolean = false,
        coreOnly: Boolean = false,
        serviceToolIds: Set<String> = emptySet()
    ): RequestToolPlan {
        val all = registry.getAllTools()
        val byId = all.associateBy { it.id }
        val candidateIds = buildSet {
            all.forEach { tool ->
                if (!ToolTierPolicy.isLegacyAlias(tool.id)) add(tool.id)
            }
            when {
                coreOnly -> retainAll(ToolTierPolicy.CORE_TOOL_IDS)
                !exposeAll ->
                    retainAll(ToolTierPolicy.CORE_TOOL_IDS + activation.snapshot() + serviceToolIds)
                // exposeAll: keep the full (non-legacy) set
            }
        }
        return assemble(all, byId, candidateIds, priorityIds = ToolTierPolicy.CORE_TOOL_IDS)
    }

    /**
     * Forced plan ("调用函数" selection): ONLY the forced tools ship and the
     * engine requests `tool_choice = required` (or the specific function),
     * so the model MUST call one of them — the user-visible semantics of the
     * function selector in v4. 用户显式选中的工具一律优先级条目（不受扩展
     * 预算截断）。
     */
    fun planForced(
        registry: ToolRegistry,
        forcedIds: Set<String>
    ): RequestToolPlan {
        val all = registry.getAllTools()
        val byId = all.associateBy { it.id }
        // Keep registered tools only; preserve the caller's order.
        val candidateIds = forcedIds.filter { it in byId }
        val missing = forcedIds - candidateIds.toSet()
        val plan = assemble(
            all, byId, LinkedHashSet(candidateIds), priorityIds = candidateIds.toSet()
        )
        return if (missing.isEmpty()) plan else plan.copy(
            droppedByBudget = plan.droppedByBudget + missing.map { "$it (unknown id)" }
        )
    }

    // ── assembly ───────────────────────────────────────────────

    /**
     * 装配：先决定准入（优先级条目无条件；扩展条目按条数/字节预算），再按
     * provider 名字序输出（prompt-cache 友好，且与旧版顺序口径一致）。
     *
     * @param priorityIds 无条件准入的 id 集（CORE / forced）；其余为扩展条目。
     */
    private fun assemble(
        all: List<AgentTool>,
        byId: Map<String, AgentTool>,
        candidateIds: Set<String>,
        priorityIds: Set<String> = emptySet()
    ): RequestToolPlan {
        // Name mapping covers every candidate (deterministic, collision-safe).
        val nameMap = ToolNameSanitizer.buildMapping(candidateIds)

        // Stable final order: sorted by provider name (prompt-cache friendly).
        val ordered = candidateIds
            .mapNotNull { id -> byId[id]?.let { id to it } }
            .sortedBy { (id, _) -> nameMap[id] }

        // Pre-build every entry once (sanitize + clamp + size).
        val entries = LinkedHashMap<String, ToolDefinition>(ordered.size)
        val entryBytes = HashMap<String, Int>(ordered.size)
        for ((id, tool) in ordered) {
            val providerName = nameMap.getValue(id)
            val schema = ToolSchemaSanitizer.sanitize(tool.parametersSchema)
            val description = ToolSchemaSanitizer.clampDescription(tool.description)
            entries[id] = ToolDefinition(
                name = providerName,
                description = description,
                parameters = schema
            )
            entryBytes[id] = schema.length + description.length + providerName.length + 48
        }

        // Admission pass 1 — priority entries: never budget-dropped.
        val admitted = LinkedHashSet<String>(ordered.size)
        for ((id, _) in ordered) {
            if (id in priorityIds) admitted += id
        }

        // Admission pass 2 — extension entries: count- and byte-clamped.
        val dropped = mutableListOf<String>()
        var extensionCount = 0
        var extensionBytes = 0
        for ((id, _) in ordered) {
            if (id in priorityIds) continue
            val bytes = entryBytes.getValue(id)
            val atCountCap = extensionCount >= MAX_EXTENSION_TOOLS
            val overBytes = admitted.isNotEmpty() && extensionBytes + bytes > MAX_TOTAL_BYTES
            if (atCountCap || overBytes) {
                dropped += id
                continue
            }
            admitted += id
            extensionCount++
            extensionBytes += bytes
        }

        val out = mutableListOf<ToolDefinition>()
        val reverse = linkedMapOf<String, String>()
        val visible = linkedSetOf<String>()
        var totalBytes = 0
        for ((id, _) in ordered) {
            if (id !in admitted) continue
            val entry = entries.getValue(id)
            out += entry
            reverse[entry.name] = id
            visible += id
            totalBytes += entryBytes.getValue(id)
        }

        return RequestToolPlan(
            tools = out,
            providerNameToId = reverse,
            visibleRegistryIds = visible,
            totalBytes = totalBytes,
            droppedByBudget = dropped
        )
    }
}
