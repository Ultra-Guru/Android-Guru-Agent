package com.apex.agent.core.tools.fusion.shrimp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * # Shrimp Task Manager —— 工具引擎（16 工具全目录）
 *
 * 上游（cjo4m06/mcp-shrimp-task-manager v1.0.21，MIT）的 MCP 工具面
 * 逐名对齐（docs/tools.md 权威清单）：
 *
 * | 类别 | 工具 |
 * |---|---|
 * | 规划 | plan_task / analyze_task / split_tasks |
 * | 执行 | execute_task / update_task / complete_task / delete_task |
 * | 验证 | verify_task / reflect_task / query_task / list_tasks |
 * | 记忆 | get_task_detail / clear_all_tasks（自动备份） |
 * | 研究 | research_mode / init_project_rules |
 * | 思维 | process_thought |
 *
 * 设备端形态：纯 Kotlin 进程内实现 —— 任务模型（依赖 DAG + 反向链接
 * + 洞察账本）经 [ShrimpTaskStore] 持久化（JSON 原子写 + 备份链），
 * 全部工具零子进程零网络。工具返回结构化 JSON（status / 数据 /
 * guidance 三段），错误同样结构化（status=error + message）—— agent
 * 可读可自纠。
 */
class ShrimpTaskEngine(private val store: ShrimpTaskStore) {

    /** 工具派发入口（transport 的 tools/call 直连此处）。 */
    fun callTool(name: String, args: JsonObject): JsonObject = when (name) {
        "plan_task" -> planTask(args)
        "update_task" -> updateTask(args)
        "execute_task" -> executeTask(args)
        "complete_task" -> completeTask(args)
        "list_tasks" -> listTasks()
        "delete_task" -> deleteTask(args)
        "split_tasks" -> splitTasks(args)
        "analyze_task" -> analyzeTask(args)
        "verify_task" -> verifyTask(args)
        "reflect_task" -> reflectTask(args)
        "query_task" -> queryTask(args)
        "get_task_detail" -> getTaskDetail(args)
        "clear_all_tasks" -> clearAllTasks()
        "init_project_rules" -> initProjectRules(args)
        "research_mode" -> researchMode(args)
        "process_thought" -> processThought(args)
        else -> errorResult("未知工具: $name（可用工具见 tools/list，共 ${ShrimpToolCatalog.size()} 个）")
    }

    // ═══ 规划族 ═══════════════════════════════════════════════════════

    /**
     * plan_task：开启一轮综合任务规划 —— 归档已完成任务、记录计划上下文、
     * 返回规划工作流指引（分解 → 依赖 → 复杂度 → update_task 回填）。
     */
    private fun planTask(args: JsonObject): JsonObject {
        val description = args.str("description")
        if (description.isBlank()) return errorResult("plan_task 需要 description（本轮规划的任务目标）")
        val list = store.current()
        val archived = list.tasks.filter { it.status == ShrimpTask.STATUS_COMPLETED }
        val carried = list.tasks.map {
            if (it.status == ShrimpTask.STATUS_COMPLETED) it.copy(status = ShrimpTask.STATUS_ARCHIVED) else it
        }
        val updated = list.copy(tasks = carried, planContext = description)
        store.save(updated)
        return ok(
            "plan_context" to description,
            "carried_over" to carried.size,
            "archived_completed" to archived.size,
            "active_tasks" to renderTaskSummaries(updated.tasks.filter { it.status != ShrimpTask.STATUS_ARCHIVED }),
            "project_rules" to list.rules,
            "guidance" to PLAN_GUIDANCE
        )
    }

    /**
     * update_task：整表回填结构化任务列表（上游核心写路径 —— agent 规划
     * 推理的产物落库）。五道防线：描述非空 / 引用存在 / 无自引用 /
     * 无重复前置 / 无环（拓扑校验）。
     */
    private fun updateTask(args: JsonObject): JsonObject {
        val rawTasks = args.objList("tasks")
        if (rawTasks.isEmpty()) return errorResult("update_task 需要 tasks 数组（本轮完整任务清单）")
        val now = System.currentTimeMillis()
        val parsed = mutableListOf<ShrimpTask>()
        rawTasks.forEachIndexed { i, raw ->
            val desc = raw.str("description").trim()
            if (desc.isBlank()) return errorResult("任务 ${i + 1} 缺少 description")
            val prereqs = raw.intList("prerequisites").distinct()
            parsed.add(
                ShrimpTask(
                    id = i + 1,
                    description = desc,
                    prerequisites = prereqs,
                    complexity = raw.str("complexity"),
                    analysis = raw.str("analysis"),
                    implementationGuide = raw.str("implementation_guide").ifBlank { raw.str("implementationGuide") },
                    verificationSteps = raw.str("verification_steps").ifBlank { raw.str("verificationSteps") },
                    notes = raw.str("notes"),
                    relatedFiles = raw.strList("related_files").ifEmpty { raw.strList("relatedFiles") },
                    summary = raw.str("summary"),
                    createdAt = now,
                    updatedAt = now
                )
            )
        }
        // 防线：引用存在 + 无自引用
        val ids = parsed.map { it.id }.toSet()
        for (t in parsed) {
            for (p in t.prerequisites) {
                if (p == t.id) return errorResult("任务 ${t.id} 的 prerequisites 引用了自己")
                if (p !in ids) return errorResult("任务 ${t.id} 的前置 $p 不存在（任务 id 为 1..${parsed.size}）")
            }
        }
        // 防线：无环（DFS 三色标记）
        if (hasCycle(parsed)) return errorResult("任务依赖存在环 —— 请修正 prerequisites（依赖必须是 DAG）")
        val list = store.current()
        store.save(list.copy(tasks = parsed))
        return ok(
            "saved" to parsed.size,
            "tasks" to renderTaskSummaries(parsed),
            "guidance" to "已保存 ${parsed.size} 个任务。逐个 execute_task 推进；完成即 complete_task 并给验证证据；有新洞察随手 reflect_task。"
        )
    }

    /** split_tasks：把复杂任务拆成有序子任务链（原任务归档、依赖正确转接）。 */
    private fun splitTasks(args: JsonObject): JsonObject {
        val index = args.intOrNull("index") ?: return errorResult("split_tasks 需要 index（要拆分的任务序号）")
        val subs = args.objList("subtasks")
        if (subs.isEmpty()) return errorResult("split_tasks 需要 subtasks 数组（每个含 description，可选 complexity/implementation_guide/verification_steps）")
        val list = store.current()
        val target = list.tasks.find { it.id == index }
            ?: return errorResult("任务 $index 不存在（当前 ${list.tasks.size} 个任务）")
        if (target.status == ShrimpTask.STATUS_COMPLETED) {
            return errorResult("任务 $index 已完成 —— 无需拆分")
        }
        val now = System.currentTimeMillis()
        var nextId = (list.tasks.maxOfOrNull { it.id } ?: 0)
        val newTasks = mutableListOf<ShrimpTask>()
        var prevId: Int? = null
        for (sub in subs) {
            nextId += 1
            val prereq = (target.prerequisites + listOfNotNull(prevId)).distinct()
            newTasks.add(
                ShrimpTask(
                    id = nextId,
                    description = sub.str("description").trim(),
                    prerequisites = prereq,
                    complexity = sub.str("complexity"),
                    implementationGuide = sub.str("implementation_guide").ifBlank { sub.str("implementationGuide") },
                    verificationSteps = sub.str("verification_steps").ifBlank { sub.str("verificationSteps") },
                    notes = "由任务 $index 拆分而来",
                    createdAt = now,
                    updatedAt = now
                )
            )
            prevId = nextId
        }
        if (newTasks.any { it.description.isBlank() }) {
            return errorResult("subtasks 中存在缺少 description 的条目")
        }
        // 原任务归档；依赖原任务的后继改挂最后一个子任务
        val lastSubId = newTasks.last().id
        val reLinked = list.tasks.map { t ->
            when {
                t.id == target.id -> t.copy(
                    status = ShrimpTask.STATUS_ARCHIVED,
                    notes = t.notes + "已拆分为子任务 ${newTasks.map { it.id }}",
                    updatedAt = now
                )
                t.prerequisites.contains(target.id) -> t.copy(
                    prerequisites = (t.prerequisites - target.id + lastSubId).distinct(),
                    updatedAt = now
                )
                else -> t
            }
        }
        val merged = (reLinked + newTasks).sortedBy { it.id }
        store.save(list.copy(tasks = merged))
        return ok(
            "split_from" to index,
            "created" to renderTaskSummaries(newTasks),
            "archived_original" to true,
            "guidance" to "原任务 $index 已归档，${newTasks.size} 个子任务按序成链（各自依赖前置子任务）。继续 execute_task 逐个推进。"
        )
    }

    /** analyze_task：回填复杂度与需求分析（缺参时返回分析模板指引）。 */
    private fun analyzeTask(args: JsonObject): JsonObject {
        val index = args.intOrNull("index") ?: return errorResult("analyze_task 需要 index")
        val complexity = args.str("complexity").lowercase()
        val analysis = args.str("analysis")
        if (complexity.isBlank() || analysis.isBlank()) {
            return ok(
                "index" to index,
                "guidance" to "分析后带参回填：complexity（low/medium/high —— 依据依赖数/边界情况/未知面）与 analysis（需求分析：要满足什么、风险在哪、验证口径）。分析要点：1) 该任务输出是什么、谁消费；2) 依赖哪些前置；3) 最难的点与验证方式。"
            )
        }
        if (complexity !in setOf("low", "medium", "high")) {
            return errorResult("complexity 须为 low | medium | high（收到 '$complexity'）")
        }
        val list = store.current()
        val updated = list.tasks.map {
            if (it.id == index) it.copy(complexity = complexity, analysis = analysis, updatedAt = System.currentTimeMillis()) else it
        }
        if (updated == list.tasks) return errorResult("任务 $index 不存在")
        store.save(list.copy(tasks = updated))
        return ok("index" to index, "complexity" to complexity, "saved" to true)
    }

    // ═══ 执行族 ═══════════════════════════════════════════════════════

    /** execute_task：取指定任务进入执行 —— 前置校验 + 全上下文（规则/洞察/指南）。 */
    private fun executeTask(args: JsonObject): JsonObject {
        val index = args.intOrNull("index") ?: return errorResult("execute_task 需要 index（要执行的任务序号）")
        val list = store.current()
        val task = list.tasks.find { it.id == index }
            ?: return errorResult("任务 $index 不存在（当前 ${list.tasks.size} 个任务）")
        if (task.status == ShrimpTask.STATUS_COMPLETED) {
            return ok("index" to index, "task_status" to "completed", "message" to "任务已完成 —— 无需重复执行；可 get_task_detail 查看验证证据")
        }
        if (task.status == ShrimpTask.STATUS_ARCHIVED) {
            return errorResult("任务 $index 已归档（被拆分或清档）")
        }
        val unmet = task.prerequisites.filter { p ->
            list.tasks.find { it.id == p }?.let { it.status != ShrimpTask.STATUS_COMPLETED } ?: true
        }
        if (unmet.isNotEmpty()) {
            return errorResult(
                "任务 $index 的前置未完成：$unmet —— 先完成前置任务，或 update_task 修正依赖；违反依赖顺序执行是上游明令禁止的反模式"
            )
        }
        val now = System.currentTimeMillis()
        val updated = list.tasks.map {
            if (it.id == index) it.copy(status = ShrimpTask.STATUS_IN_PROGRESS, updatedAt = now) else it
        }
        store.save(list.copy(tasks = updated))
        val relatedInsights = list.tasks.flatMap { it.insights }.takeLast(5)
        return ok(
            "index" to index,
            "task" to renderTaskDetail(task, list),
            "project_rules" to list.rules,
            "recent_insights" to JsonArray(relatedInsights.map { JsonPrimitive(it) }),
            "guidance" to "按 implementation_guide 推进；完成前逐条走 verification_steps；产出证据（命令与输出摘要）随 complete_task 的 notes 回填。"
        )
    }

    /** complete_task：标记完成（带证据备注）并建议下一个可执行任务。 */
    private fun completeTask(args: JsonObject): JsonObject {
        val index = args.intOrNull("index") ?: return errorResult("complete_task 需要 index")
        val notes = args.str("notes")
        val list = store.current()
        val task = list.tasks.find { it.id == index }
            ?: return errorResult("任务 $index 不存在")
        if (task.status == ShrimpTask.STATUS_COMPLETED) {
            return ok("index" to index, "task_status" to "completed", "message" to "已是完成态")
        }
        val now = System.currentTimeMillis()
        val updated = list.tasks.map {
            if (it.id == index) {
                it.copy(
                    status = ShrimpTask.STATUS_COMPLETED,
                    notes = if (notes.isBlank()) it.notes else (if (it.notes.isBlank()) notes else it.notes + "\n" + notes),
                    updatedAt = now
                )
            } else it
        }
        store.save(list.copy(tasks = updated))
        val next = updated.filter { it.status == ShrimpTask.STATUS_PENDING }
            .firstOrNull { t -> t.prerequisites.all { p -> updated.find { it.id == p }?.status == ShrimpTask.STATUS_COMPLETED } }
        return ok(
            "index" to index,
            "task_status" to "completed",
            "progress" to progressOf(updated),
            "next_task" to (next?.let { renderTaskSummaries(listOf(it))[0] } ?: JsonPrimitive("")),
            "guidance" to if (next != null) "建议下一个：任务 ${next.id}（${next.description.take(60)}）" else "全部任务已完成 —— reflect_task 沉淀复盘，或 list_tasks 核对总账"
        )
    }

    /** delete_task：删除任务并清理他处引用。 */
    private fun deleteTask(args: JsonObject): JsonObject {
        val index = args.intOrNull("index") ?: return errorResult("delete_task 需要 index")
        val list = store.current()
        if (list.tasks.none { it.id == index }) return errorResult("任务 $index 不存在")
        val now = System.currentTimeMillis()
        val cleaned = list.tasks
            .filter { it.id != index }
            .map { t ->
                if (t.prerequisites.contains(index)) {
                    t.copy(prerequisites = t.prerequisites - index, updatedAt = now)
                } else t
            }
        store.save(list.copy(tasks = cleaned))
        return ok("deleted" to index, "remaining" to cleaned.size)
    }

    // ═══ 验证与查询族 ═════════════════════════════════════════════════

    /** list_tasks：全部任务 + 状态统计 + 进度。 */
    private fun listTasks(): JsonObject {
        val list = store.current()
        return ok(
            "plan_context" to list.planContext,
            "tasks" to renderTaskSummaries(list.tasks),
            "progress" to progressOf(list.tasks)
        )
    }

    /** verify_task：记录完成验证证据（证据入账，三态判定留给 agent 如实填）。 */
    private fun verifyTask(args: JsonObject): JsonObject {
        val index = args.intOrNull("index") ?: return errorResult("verify_task 需要 index")
        val result = args.str("result")
        val list = store.current()
        val task = list.tasks.find { it.id == index }
            ?: return errorResult("任务 $index 不存在")
        if (task.status != ShrimpTask.STATUS_COMPLETED) {
            return errorResult("任务 $index 尚未完成（当前 ${task.status}）—— 先 complete_task 再验证")
        }
        val now = System.currentTimeMillis()
        val entry = if (result.isBlank()) "[verify] 待补证据" else "[verify] $result"
        val updated = list.tasks.map {
            if (it.id == index) it.copy(notes = if (it.notes.isBlank()) entry else it.notes + "\n" + entry, updatedAt = now) else it
        }
        store.save(list.copy(tasks = updated))
        return ok(
            "index" to index,
            "recorded" to entry,
            "guidance" to "验证口径：对照 verification_steps 逐条核对，证据写入 result（命令 + 输出摘要）；没验证的项如实标注，验证不通过就回 execute_task 修复 —— 不许假验证。"
        )
    }

    /** reflect_task：追加反思洞察（什么有效/什么没效/下次怎么改）。 */
    private fun reflectTask(args: JsonObject): JsonObject {
        val index = args.intOrNull("index") ?: return errorResult("reflect_task 需要 index")
        val insights = args.strList("insights").ifEmpty { listOf(args.str("insight")).filter { it.isNotBlank() } }
        if (insights.isEmpty()) return errorResult("reflect_task 需要 insights（数组或单条 insight）")
        val list = store.current()
        val task = list.tasks.find { it.id == index }
            ?: return errorResult("任务 $index 不存在")
        val now = System.currentTimeMillis()
        val updated = list.tasks.map {
            if (it.id == index) it.copy(insights = it.insights + insights, updatedAt = now) else it
        }
        store.save(list.copy(tasks = updated))
        return ok(
            "index" to index,
            "insights" to JsonArray((task.insights + insights).map { JsonPrimitive(it) }),
            "guidance" to "洞察已入账（execute_task 会带回最近洞察 —— 风格一致性由此保持）。三问补全更佳：什么有效（保持）/ 什么没效（停止）/ 下次怎么做（具体动作）。"
        )
    }

    /** query_task：按状态/关键词过滤。 */
    private fun queryTask(args: JsonObject): JsonObject {
        val status = args.str("status").lowercase()
        val keyword = args.str("keyword")
        val list = store.current()
        val matched = list.tasks.filter { t ->
            (status.isBlank() || t.status == status) &&
                (keyword.isBlank() || listOf(t.description, t.summary, t.notes).any { it.contains(keyword, ignoreCase = true) })
        }
        return ok(
            "matched" to matched.size,
            "tasks" to renderTaskSummaries(matched)
        )
    }

    /** get_task_detail：单个任务全貌（含前置描述与后继引用）。 */
    private fun getTaskDetail(args: JsonObject): JsonObject {
        val index = args.intOrNull("index") ?: return errorResult("get_task_detail 需要 index")
        val list = store.current()
        val task = list.tasks.find { it.id == index }
            ?: return errorResult("任务 $index 不存在")
        return ok("task" to renderTaskDetail(task, list))
    }

    /** clear_all_tasks：自动备份后清空（保留项目规则）。 */
    private fun clearAllTasks(): JsonObject {
        val backupName = store.backup()
        store.save(store.current().copy(tasks = emptyList()))
        return ok(
            "cleared" to true,
            "backup" to backupName,
            "backups" to JsonArray(store.backups().map { JsonPrimitive(it) }),
            "guidance" to "已备份并清空任务（项目规则保留）。恢复：把备份文件内容经 update_task 回填即可。"
        )
    }

    // ═══ 研究与思维族 ═════════════════════════════════════════════════

    /** init_project_rules：项目规则入库（execute 上下文常驻）。 */
    private fun initProjectRules(args: JsonObject): JsonObject {
        val rules = args.str("rules")
        if (rules.isBlank()) return errorResult("init_project_rules 需要 rules（项目标准：代码风格/测试要求/提交纪律等）")
        val list = store.current()
        store.save(list.copy(rules = rules))
        return ok(
            "saved" to true,
            "rules" to rules,
            "guidance" to "规则已入库 —— 每次 execute_task 都会带回（所有执行保持在同一标准下，上游风格一致性的基石）。"
        )
    }

    /** research_mode：进入系统化研究模式（问题 → 取证 → 对比 → 结论 → 回填计划）。 */
    private fun researchMode(args: JsonObject): JsonObject {
        val query = args.str("query")
        if (query.isBlank()) return errorResult("research_mode 需要 query（要研究的问题，如「GraphQL vs REST 怎么选」）")
        val list = store.current()
        return ok(
            "query" to query,
            "project_rules" to list.rules,
            "guidance" to RESEARCH_GUIDANCE
        )
    }

    /** process_thought：思维链入环（会话内缓冲，不落盘 —— 上游内部使用语义）。 */
    private fun processThought(args: JsonObject): JsonObject {
        val thought = args.str("thought")
        if (thought.isBlank()) return errorResult("process_thought 需要 thought（当前推理步）")
        val count = store.appendThought(thought)
        return ok(
            "thought_number" to count,
            "guidance" to "思维步已记录（会话内保留最近 $THOUGHT_LIMIT_HINT 步）。继续推理；结构定型后把结论整理成任务清单经 update_task 落库。"
        )
    }

    // ═══ 渲染与校验助手 ═══════════════════════════════════════════════

    /** 拓扑环检测（DFS 三色：0 未访 / 1 在栈 / 2 完成）。 */
    private fun hasCycle(tasks: List<ShrimpTask>): Boolean {
        val color = IntArray(tasks.size + 1)
        fun dfs(id: Int): Boolean {
            color[id] = 1
            for (p in tasks.first { it.id == id }.prerequisites) {
                if (color[p] == 1) return true
                if (color[p] == 0 && dfs(p)) return true
            }
            color[id] = 2
            return false
        }
        for (t in tasks) if (color[t.id] == 0 && dfs(t.id)) return true
        return false
    }

    private fun progressOf(tasks: List<ShrimpTask>): JsonObject = buildJsonObject {
        val total = tasks.count { it.status != ShrimpTask.STATUS_ARCHIVED }
        val done = tasks.count { it.status == ShrimpTask.STATUS_COMPLETED }
        put("total", total)
        put("completed", done)
        put("pending", tasks.count { it.status == ShrimpTask.STATUS_PENDING })
        put("in_progress", tasks.count { it.status == ShrimpTask.STATUS_IN_PROGRESS })
        put("percent", if (total == 0) 0 else done * 100 / total)
    }

    private fun renderTaskSummaries(tasks: List<ShrimpTask>): JsonArray = buildJsonArray {
        tasks.forEach { t ->
            add(buildJsonObject {
                put("id", t.id)
                put("description", t.description)
                put("status", t.status)
                put("prerequisites", JsonArray(t.prerequisites.map { JsonPrimitive(it) }))
                put("complexity", t.complexity)
                put("summary", t.summary)
            })
        }
    }

    private fun renderTaskDetail(task: ShrimpTask, list: ShrimpTaskList): JsonObject = buildJsonObject {
        put("id", task.id)
        put("description", task.description)
        put("status", task.status)
        put("prerequisites", JsonArray(task.prerequisites.map { JsonPrimitive(it) }))
        put("prerequisite_descriptions", JsonArray(task.prerequisites.mapNotNull { p ->
            list.tasks.find { it.id == p }?.let { JsonPrimitive("${it.id}: ${it.description}") }
        }))
        put("dependents", JsonArray(list.tasks.filter { it.prerequisites.contains(task.id) }.map { JsonPrimitive(it.id) }))
        put("complexity", task.complexity)
        put("analysis", task.analysis)
        put("implementation_guide", task.implementationGuide)
        put("verification_steps", task.verificationSteps)
        put("notes", task.notes)
        put("insights", JsonArray(task.insights.map { JsonPrimitive(it) }))
        put("related_files", JsonArray(task.relatedFiles.map { JsonPrimitive(it) }))
        put("summary", task.summary)
    }

    private fun ok(vararg fields: Pair<String, Any?>): JsonObject = buildJsonObject {
        put("status", "ok")
        for ((k, v) in fields) {
            when (v) {
                is String -> put(k, v)
                is Int -> put(k, v)
                is Boolean -> put(k, v)
                is JsonObject -> put(k, v)
                is JsonArray -> put(k, v)
                is JsonPrimitive -> put(k, v)
            }
        }
    }

    private fun errorResult(message: String): JsonObject = buildJsonObject {
        put("status", "error")
        put("message", message)
    }

    private fun JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull ?: ""

    private fun JsonObject.intOrNull(key: String): Int? =
        (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.strList(key: String): List<String> =
        (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()

    private fun JsonObject.intList(key: String): List<Int> =
        (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull } ?: emptyList()

    private fun JsonObject.objList(key: String): List<JsonObject> =
        (this[key] as? JsonArray)?.filterIsInstance<JsonObject>() ?: emptyList()

    private companion object {
        const val THOUGHT_LIMIT_HINT = 20

        val PLAN_GUIDANCE = """
            规划工作流（Shrimp 上游方法论）：1) 分解 —— 把目标拆成原子任务（一个任务一个可验证的交付物，不是一层目录）；2) 依赖 —— prerequisites 显式声明（能并行就别串行）；3) 每个任务补全六件：description（做什么）、complexity（low/medium/high）、analysis（为什么这样做）、implementation_guide（怎么做的操作手册）、verification_steps（怎么确认完成）、summary（三词标签）。4) 复杂任务先 split_tasks 或直接在清单里拆好；5) 推理过程用 process_thought 外化；6) 定型后整表经 update_task 落库。
        """.trimIndent().replace("\n", " ")

        val RESEARCH_GUIDANCE = """
            研究工作流（Shrimp 上游 research_mode）：1) 界定问题 —— 明确要决策什么、约束是什么；2) 取证 —— WebSearch 检索候选方案（官方文档/基准/已知陷阱），每个结论带来源；3) 对比 —— 至少两维对比（能力/成本/风险/迁移性），用 process_thought 分步推演；4) 结论 —— 明确推荐 + 何时该换（失效条件）；5) 回填 —— 结论作为 analysis 写入相关任务（或 plan_task 开新计划），研究不落任务等于白研究。
        """.trimIndent().replace("\n", " ")
    }
}
