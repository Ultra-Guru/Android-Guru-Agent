package com.apex.agent.platform.browser

import com.apex.agent.core.tools.AgentTool
import com.apex.agent.core.tools.StreamingAgentTool
import com.apex.agent.core.tools.ToolStreamEvent
import com.apex.browser.engine.DiffResult
import com.apex.browser.core.Disambiguation
import com.apex.browser.core.DomParser
import com.apex.browser.core.READINESS_SETTLED
import com.apex.browser.core.ReadinessStatus
import com.apex.browser.core.TaskActionType
import com.apex.browser.core.TaskStep
import com.apex.browser.core.TaskValidator
import com.apex.browser.engine.BrowserEngine
import com.apex.browser.engine.BrowserTracer
import com.apex.browser.engine.cancelNavigation
import com.apex.browser.engine.cancelTask
import com.apex.browser.engine.docEvents
import com.apex.browser.engine.navigateAsync
import com.apex.browser.engine.navigationState
import com.apex.browser.engine.releaseNavigation
import com.apex.browser.engine.runTask
import com.apex.browser.engine.taskState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.inject.Inject

/**
 * v2.1.0 异步导航协议 + BrowserTask 工具集（评审 B4；消费 apex-kit-browser 2.1.0）。
 *
 * 与 [BrowserAgentTools] 的分工：本工具集暴露**协议面**——异步导航句柄
 * （navigationId/docId 分离）、双状态查询、事件增量游标、任务编排（一次提交
 * N 步自主推进，模型往返 N→1）。同步阻塞工具语义零变更（BrowserNavigate 等保留）。
 *
 * 守卫纪律与既有工具集一致：WAITING_HUMAN 期间结构化 SYSTEM_LOCKED（任务引擎
 * 在引擎侧同样逐步复查人工接管——人工优先）。
 */
class BrowserAsyncTools @Inject constructor(
    private val engine: BrowserEngine,
    private val tracer: BrowserTracer,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun argStr(args: String, key: String): String? =
        runCatching { json.decodeFromString<JsonObject>(args)[key]?.jsonPrimitive?.content }.getOrNull()

    private fun argInt(args: String, key: String, default: Int = 0): Int =
        argStr(args, key)?.toIntOrNull() ?: default

    private fun argBool(args: String, key: String, default: Boolean = false): Boolean =
        argStr(args, key)?.toBooleanStrictOrNull() ?: default

    private fun guard(): String? = try {
        engine.assertAgentControl()
        null
    } catch (e: BrowserEngine.HandoffLockedException) {
        kotlinx.serialization.json.buildJsonObject {
            put("status", "SYSTEM_LOCKED")
            put("reason", e.message ?: "human handoff in progress")
            put("hint", "用户正在浮窗中人工操作，自动化已锁定。等待 BrowserShow 展开确认用户完成，或稍后重试。")
        }.toString()
    }

    // ═══ 1. BrowserTask：一次提交 N 步，运行时自主推进 ═══

    val task = object : StreamingAgentTool {
        override val id = "BrowserTask"
        override val name = "BrowserTask"
        override val description =
            "提交浏览器任务序列（navigate/wait_for/fill/click/press_key/scroll/extract），**一次调用由浏览器运行时自主推进并返回压缩结果**——" +
                "把 N 次模型往返压成 1 次。步骤按 CSS selector 直操作（无需先 snapshot 拿 ref）；" +
                "selector 多命中时暂停检查点返回候选 ref 交你决策（disambiguation=first 可跳过）；" +
                "预校验失败零副作用；不承诺回滚。mode=background 提交后台（返回 taskId，用 BrowserDocState 查询）。" +
                "纪律：确定性连续操作用本工具；需要判断的节点拆成单步工具或接受检查点。"
        override val parametersSchema = """{
            "type":"object",
            "properties":{
                "steps":{"type":"array","maxItems":12,"description":"步骤序列（预校验失败整体拒绝，零副作用）","items":{
                    "type":"object",
                    "properties":{
                        "type":{"type":"string","enum":["navigate","wait_for","fill","click","press_key","scroll","extract"]},
                        "url":{"type":"string","description":"navigate 的目标网址"},
                        "selector":{"type":"string","description":"fill/click/extract(text) 的 CSS selector（直操作不经 ref）"},
                        "text":{"type":"string","description":"fill 的文本（空串=清空；append=true 追加；press_enter=true 回车）"},
                        "key":{"type":"string","description":"press_key 的键名（enter/escape/tab/…）"},
                        "wait_mode":{"type":"string","description":"wait_for 条件：selector|text|url|element_gone|text_gone|network_idle|selector_count|dom_stable"},
                        "wait_value":{"type":"string","description":"wait_for 条件值（selector_count 形如 'li.item >= 10'）"},
                        "timeout_ms":{"type":"integer","description":"步骤超时毫秒（navigate 默认 15000，wait 默认 10000）"},
                        "extract_mode":{"type":"string","description":"extract：article|tables|links|meta|text（text 需配 selector）"},
                        "disambiguation":{"type":"string","enum":["checkpoint","first"],"description":"selector 多命中处置，默认 checkpoint 暂停返回候选"},
                        "expected_doc_id":{"type":"string","description":"可选前置校验：当前文档不一致则 STALE_DOCUMENT（快照过期检测闭环）"},
                        "delta_y":{"type":"integer","description":"scroll 像素（0=滚到底部）"}
                    },
                    "required":["type"]
                }},
                "mode":{"type":"string","enum":["wait","background"],"description":"wait=阻塞到任务结束（推荐）；background=立即返回 taskId"},
                "total_timeout_ms":{"type":"integer","description":"整批总超时毫秒，默认 90000，上限 600000"}
            },
            "required":["steps"]
        }"""
        override suspend fun execute(arguments: String): String {
            guard()?.let { return it }
            val steps = parseSteps(arguments)
                ?: return "Error: steps 参数不合法（动作对象数组，每项至少含 type）"
            // 预校验前置暴露（引擎侧也会整体拒绝——这里先给可诊断错误省一次往返）
            val errors = TaskValidator.validate(steps)
            if (errors.isNotEmpty()) return "任务未执行（预校验失败，零副作用）：\n" + errors.joinToString("\n")
            val mode = if (argStr(arguments, "mode")?.lowercase() == "background") "background" else "wait"
            val total = argInt(arguments, "total_timeout_ms", 90_000).coerceIn(5_000, 600_000).toLong()
            val outcome = engine.runTask(steps, mode = mode, totalTimeoutMs = total)
            return outcome.summarize()
        }

        /**
         * v2.1.2 流水任务卡：步级事件经 preview 块实时流入时间轴（人看，不进模型），
         * 权威输出=Complete 携带的最终 summarize。事件源=engine.taskEvents
         * （replay 128,先订阅后执行,按 taskId 过滤,订阅晚于发射不丢）。
         */
        override fun executeStream(arguments: String): Flow<ToolStreamEvent> = channelFlow {
            guard()?.let { send(ToolStreamEvent.Error(it)); close(); return@channelFlow }
            val steps = parseSteps(arguments)
            if (steps == null) { send(ToolStreamEvent.Error("Error: steps 参数不合法")); close(); return@channelFlow }
            val errors = TaskValidator.validate(steps)
            if (errors.isNotEmpty()) {
                send(ToolStreamEvent.Complete("任务未执行（预校验失败，零副作用）：
" + errors.joinToString("
")))
                close(); return@channelFlow
            }
            val mode = if (argStr(arguments, "mode")?.lowercase() == "background") "background" else "wait"
            val total = argInt(arguments, "total_timeout_ms", 90_000).coerceIn(5_000, 600_000).toLong()
            if (mode == "background") {
                val outcome = engine.runTask(steps, mode = mode, totalTimeoutMs = total)
                send(ToolStreamEvent.Complete(outcome.summarize())); close(); return@channelFlow
            }
            val taskId = "t" + java.util.UUID.randomUUID().toString().substring(0, 8)
            fun cardLine(e: com.apex.browser.core.TaskStepEvent): String {
                val mark = when (e.phase) {
                    com.apex.browser.core.TaskStepPhase.STARTED -> "▶"
                    com.apex.browser.core.TaskStepPhase.OK -> "✓"
                    com.apex.browser.core.TaskStepPhase.FAILED -> "✗"
                    com.apex.browser.core.TaskStepPhase.CHECKPOINT -> "⏸"
                }
                return "$mark [${e.stepIndex + 1}/${e.totalSteps}] ${e.type}  ${e.detail.take(100)}"
            }
            // 先订阅（replay 补早发事件），再启动任务
            val eventsJob = launch {
                engine.taskEvents.filter { it.taskId == taskId }.collect { e ->
                    send(ToolStreamEvent.Output(cardLine(e), preview = true))
                }
            }
            var finalText = ""
            val runJob = launch {
                val outcome = engine.runTask(steps, mode = mode, totalTimeoutMs = total, taskIdHint = taskId)
                finalText = outcome.summarize()
            }
            runJob.join()
            eventsJob.cancel()
            send(ToolStreamEvent.Complete(finalText))
            close()
        }
    }

    /** steps JSON 数组 → TaskStep（任一项非法整体判非法——序列错位比失败更糟）。 */
    private fun parseSteps(arguments: String): List<TaskStep>? = runCatching {
        val arr = json.decodeFromString<JsonObject>(arguments)["steps"] as? JsonArray ?: return null
        arr.map { el ->
            val o = el as? JsonObject ?: return null
            fun s(k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.content
            fun b(k: String) = s(k)?.toBooleanStrictOrNull() ?: false
            val type = when (s("type")?.lowercase()) {
                "navigate" -> TaskActionType.NAVIGATE
                "wait_for" -> TaskActionType.WAIT_FOR
                "fill" -> TaskActionType.FILL
                "click" -> TaskActionType.CLICK
                "press_key" -> TaskActionType.PRESS_KEY
                "scroll" -> TaskActionType.SCROLL
                "extract" -> TaskActionType.EXTRACT
                else -> return null
            }
            TaskStep(
                type = type,
                url = s("url") ?: "",
                selector = s("selector") ?: "",
                text = s("text") ?: "",
                waitMode = s("wait_mode") ?: "",
                waitValue = s("wait_value") ?: "",
                timeoutMs = (s("timeout_ms")?.toLongOrNull() ?: 0),
                extractMode = s("extract_mode") ?: "article",
                disambiguation = if (s("disambiguation")?.lowercase() == "first") Disambiguation.FIRST else Disambiguation.CHECKPOINT,
                expectedDocId = s("expected_doc_id") ?: "",
                deltaY = s("delta_y")?.toIntOrNull() ?: 0,
                append = b("append"),
                pressEnter = b("press_enter"),
            )
        }
    }.getOrNull()

    // ═══ 2. BrowserNavigateAsync：立即返回句柄 ═══

    val navigateAsync = object : AgentTool {
        override val id = "BrowserNavigateAsync"
        override val name = "BrowserNavigateAsync"
        override val description =
            "发起异步导航并**立即返回** navigation_id（不阻塞等加载）。之后可用 BrowserDocState 等待 dom_ready/selector/network_idle、" +
                "BrowserDocEvents 增量拉事件、BrowserDocControl(cancel) 取消。期间你可先做其他独立工具调用（模型与浏览器并行）。" +
                "同 tab 再次发起导航会 SUPERSEDED 取代前一次（旧句柄进入终态，不得再消费）。"
        override val parametersSchema = """{
            "type":"object",
            "properties":{
                "url":{"type":"string","description":"目标网址"},
                "new_tab":{"type":"boolean","description":"是否新标签打开，默认 false"},
                "wait_for":{"type":"string","description":"等价 wait_until=selector：等待该 CSS 选择器出现"},
                "wait_until":{"type":"string","enum":["dom_ready","selector","network_idle"],"description":"就绪目标，默认 dom_ready"},
                "timeout_ms":{"type":"integer","description":"提交/就绪各阶段超时毫秒，默认 15000"}
            },
            "required":["url"]
        }"""
        override suspend fun execute(arguments: String): String {
            guard()?.let { return it }
            val url = argStr(arguments, "url") ?: return "Error: 缺少 url 参数"
            val waitUntil = when {
                argStr(arguments, "wait_for") != null -> "selector"
                else -> argStr(arguments, "wait_until")?.lowercase() ?: "dom_ready"
            }
            val handle = engine.navigateAsync(
                url = url,
                newTab = argBool(arguments, "new_tab"),
                waitForSelector = argStr(arguments, "wait_for"),
                timeoutMs = argInt(arguments, "timeout_ms", 15_000).coerceIn(1_000, 60_000).toLong(),
                waitUntil = waitUntil,
            )
            return "已发起导航：navigation_id=${handle.navigationId} tab=${handle.tabId}（status=STARTED，加载后台推进）\n" +
                "用 BrowserDocState(navigation_id=\"${handle.navigationId}\", mode=\"wait\") 等待就绪；" +
                "期间可先做其他独立调用。"
        }
    }

    // ═══ 3. BrowserDocState：双状态查询 + wait 挂起 ═══

    val docState = object : AgentTool {
        override val id = "BrowserDocState"
        override val name = "BrowserDocState"
        override val description =
            "查询/等待导航任务或后台任务状态（双状态机：navigation_status=导航怎么样 / readiness_status=条件满足没；" +
                "页面 COMMITTED 但 readiness TIMEOUT 是合法组合——等待超时≠页面失败）。" +
                "mode=wait 挂起到目标信号（后台任务查 taskId）。CANCELLED/SUPERSEDED/TAB_CLOSED/EXPIRED 均为结构化终态。"
        override val parametersSchema = """{
            "type":"object",
            "properties":{
                "navigation_id":{"type":"string","description":"导航 id（n*）或任务 id（t*）"},
                "mode":{"type":"string","enum":["poll","wait"],"description":"poll=立即返回当前状态；wait=挂起到目标（默认 poll）"},
                "until":{"type":"string","enum":["dom_ready","selector_match","network_idle","any_settled"],"description":"wait 的目标信号，默认 any_settled"},
                "selector":{"type":"string","description":"until=selector_match 时的选择器（供诊断显示）"},
                "timeout_ms":{"type":"integer","description":"wait 超时毫秒，默认 15000，上限 60000"}
            },
            "required":["navigation_id"]
        }"""
        override suspend fun execute(arguments: String): String {
            guard()?.let { return it }
            val id = argStr(arguments, "navigation_id") ?: return "Error: 缺少 navigation_id"
            // 后台任务走任务注册表
            if (id.startsWith("t")) {
                val outcome = engine.taskState(id)
                    ?: return "任务 $id 不存在或已被回收"
                return outcome.summarize()
            }
            val mode = argStr(arguments, "mode")?.lowercase() ?: "poll"
            val timeout = argInt(arguments, "timeout_ms", 15_000).coerceIn(500, 60_000).toLong()
            val untilName = argStr(arguments, "until")?.lowercase() ?: "any_settled"
            fun predicate(readiness: ReadinessStatus?): Boolean = when (untilName) {
                "dom_ready" -> readiness == ReadinessStatus.DOM_READY
                "selector_match" -> readiness == ReadinessStatus.SELECTOR_MATCHED
                "network_idle" -> readiness == ReadinessStatus.NETWORK_IDLE
                else -> readiness in READINESS_SETTLED
            }
            if (mode != "wait") {
                return renderState(engine.navigationState(id), waited = false)
            }
            // wait：宿主侧轮询导航状态（本地内存读，开销可忽略；终态/TIMEOUT 及时返回）
            val deadline = System.currentTimeMillis() + timeout
            var state = engine.navigationState(id)
            while (System.currentTimeMillis() < deadline) {
                state = engine.navigationState(id) ?: return "导航 $id 不存在或已被释放"
                if (predicate(state.readinessStatus) || state.isTerminal) break
                kotlinx.coroutines.delay(200)
            }
            return renderState(state, waited = true)
        }

        private fun renderState(s: com.apex.browser.core.NavigationState?, waited: Boolean): String {
            if (s == null) return "导航不存在或已被释放（EXPIRED 超过 5 分钟的终态会被回收）"
            val sb = StringBuilder()
            sb.appendLine("navigation_id=${s.navigationId} tab=${s.tabId}")
            sb.appendLine("navigation_status=${s.navigationStatus} readiness_status=${s.readinessStatus}")
            s.docId?.let { sb.appendLine("doc_id=$it（后续动作可用 expected_doc_id=\"$it\" 做 STALE 校验）") }
            s.detail?.let { sb.appendLine("detail: ${it.take(160)}") }
            if (waited && !s.isTerminal && s.readinessStatus !in READINESS_SETTLED) {
                sb.append("⏱ 等待超时——注意：页面可能仍在正常加载（TIMEOUT≠失败），可再次 wait 或先做其他事。")
            }
            if (s.isTerminal) sb.append("终态（${s.navigationStatus}）：此句柄不再可消费，重新发起导航。")
            return sb.toString().trim()
        }
    }

    // ═══ 4. BrowserDocEvents：增量事件游标 ═══

    val docEvents = object : AgentTool {
        override val id = "BrowserDocEvents"
        override val name = "BrowserDocEvents"
        override val description =
            "按 since_seq 增量读取导航/文档事件（低成本轮询）。返回 next_seq 供下次续读；" +
                "truncated=true=有事件被环形覆盖（丢失，必须知晓）；evicted=true=该文档事件桶已被淘汰（太老了）。" +
                "与 BrowserDocState(wait) 配合：wait 等信号，events 拉细节。"
        override val parametersSchema = """{
            "type":"object",
            "properties":{
                "navigation_id":{"type":"string","description":"导航 id 或 doc_id"},
                "since_seq":{"type":"integer","description":"上次读到的 seq（首次传 0）"},
                "limit":{"type":"integer","description":"最多返回几条，默认 50"}
            },
            "required":["navigation_id"]
        }"""
        override suspend fun execute(arguments: String): String {
            val id = argStr(arguments, "navigation_id") ?: return "Error: 缺少 navigation_id"
            val since = argInt(arguments, "since_seq", 0).coerceAtLeast(0).toLong()
            val limit = argInt(arguments, "limit", 50).coerceIn(1, 128)
            val page = engine.docEvents(id, sinceSeq = since, limit = limit)
            val sb = StringBuilder()
            if (page.evicted) {
                sb.appendLine("⚠ EVICTED：$id 的事件桶已被淘汰（只保留最近 3 个文档）——历史不可回溯。")
            } else {
                if (page.truncated) sb.appendLine("⚠ TRUNCATED：since_seq=$since 与现存最早事件(${page.oldestSeq})之间有丢失。")
                if (page.events.isEmpty()) sb.appendLine("(暂无新事件)")
                page.events.forEach { e ->
                    sb.appendLine("[${e.seq}] ${e.type} ${e.detail.take(100)}")
                }
            }
            sb.append("next_seq=${page.nextSeq} has_more=${page.hasMore}")
            return sb.toString().trim()
        }
    }

    // ═══ 5. BrowserDocControl：取消 / 释放 ═══

    val docControl = object : AgentTool {
        override val id = "BrowserDocControl"
        override val name = "BrowserDocControl"
        override val description =
            "控制导航/任务句柄：action=cancel 取消在途导航或后台任务（等待者全部唤醒，状态 CANCELLED）；" +
                "action=release 显式释放已终态的句柄（回收状态与事件桶）。在途导航不可 release（先 cancel）。" +
                "注意：cancel 停止的是等待与状态推进，已发出的页面加载由后续导航取代。"
        override val parametersSchema = """{
            "type":"object",
            "properties":{
                "id":{"type":"string","description":"导航 id（n*）或任务 id（t*）"},
                "action":{"type":"string","enum":["cancel","release"],"description":"默认 cancel"}
            },
            "required":["id"]
        }"""
        override suspend fun execute(arguments: String): String {
            val id = argStr(arguments, "id") ?: return "Error: 缺少 id"
            val action = argStr(arguments, "action")?.lowercase() ?: "cancel"
            return when {
                action == "cancel" && id.startsWith("t") ->
                    if (engine.cancelTask(id, "cancelled by model")) "已取消任务 $id"
                    else "任务 $id 不存在或已终态"
                action == "cancel" ->
                    if (engine.cancelNavigation(id, "cancelled by model")) "已取消导航 $id（等待者已唤醒）"
                    else "导航 $id 不存在或已终态"
                action == "release" ->
                    if (engine.releaseNavigation(id)) "已释放 $id（状态与事件桶已回收）"
                    else "无法释放：$id 不存在或仍在途（先 cancel 再 release）"
                else -> "Error: 未知 action=$action（cancel|release）"
            }
        }
    }

    fun all(): List<AgentTool> = listOf(task, navigateAsync, docState, docEvents, docControl)
        .map { TracedTool(it, engine, tracer) }

    /** trace 包装（同 BrowserAgentTools 纪律；BrowserTask 结果摘要截断防爆 trace）。 */
    private class TracedTool(
        private val delegate: AgentTool,
        private val engine: BrowserEngine,
        private val tracer: BrowserTracer,
    ) : AgentTool {
        override val id get() = delegate.id
        override val name get() = delegate.name
        override val description get() = delegate.description
        override val parametersSchema get() = delegate.parametersSchema

        override suspend fun execute(arguments: String): String {
            val start = System.currentTimeMillis()
            val logged = if (delegate.id == "BrowserTask") arguments.take(300) else arguments.take(200)
            return try {
                val result = delegate.execute(arguments)
                tracer.record(delegate.id, logged, result.take(300),
                    System.currentTimeMillis() - start, engine.activeTab()?.url, engine.currentState.name)
                result
            } catch (e: Throwable) {
                tracer.record(delegate.id, logged, "ERROR: ${e.message?.take(160)}",
                    System.currentTimeMillis() - start, engine.activeTab()?.url, engine.currentState.name)
                throw e
            }
        }
    }

    companion object {
        /** FOCUSED/DIFF 模式的策略解析（BrowserSnapshot 增量模式共用）。 */
        fun strategyFor(focus: String?): DomParser.SnapshotStrategy = when (focus?.lowercase()) {
            "form" -> DomParser.SnapshotStrategy.FORM_FIELDS
            "content" -> DomParser.SnapshotStrategy.CONTENT_SUMMARY
            else -> DomParser.SnapshotStrategy.INTERACTIVE_ONLY
        }

        /** DIFF 结果 → 模型文本（Stale 给出可执行指引）。 */
        fun renderDiff(result: DiffResult): String = when (result) {
            is DiffResult.Ok -> "文档 ${result.currentDocId} 增量变化：\n" + result.diff.render()
            is DiffResult.Stale -> "⚠ ${result.reason}"
        }
    }
}
