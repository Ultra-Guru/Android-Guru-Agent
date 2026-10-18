package com.apex.agent.core.code.loom.swarm

import com.apex.agent.core.code.loom.LoomThreadPhase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * # Loom Swarm Tools — 子线程四工具（引擎本地面）
 *
 * 主模型可见的委派工具面（不进宿主共享注册表——由织造引擎组装进
 * 自己的工具目录，经 [LoomLocalTool] 协议由引擎本地路由执行）：
 *
 * | 工具 | 语义 |
 * |---|---|
 * | loom_thread_start | 起飞一条子线程（label + 首条任务），返回线程 id |
 * | loom_thread_send | 推进子线程到本飞收口，返回最后正式回复 |
 * | loom_thread_status | 快照（单条 / 全部）——阶段、轮数、截断回执 |
 * | loom_thread_observe | 不推进，摘录子线程近期织物 |
 * | loom_thread_stop | 中止并退役一条子线程 |
 *
 * ## 参数口径
 *
 * 参数以 JSON 字符串进出（与宿主工具面同构）；输出为中文人话 + 结构化
 * 字段混合——模型可读、也可被 `loom_thread_send` 的 `expect` 条件消费。
 */
object LoomSwarmCatalog {

    /** 子线程工具 id 前缀（子线程面排除 + 引擎本地路由判定）。 */
    const val TOOL_PREFIX = "loom_"

    /** 全部本域工具 id。 */
    val ALL_IDS = listOf(
        "loom_thread_start",
        "loom_thread_send",
        "loom_thread_status",
        "loom_thread_observe",
        "loom_thread_stop"
    )

    /** 起飞。 */
    const val START = "loom_thread_start"

    /** 推进。 */
    const val SEND = "loom_thread_send"

    /** 快照。 */
    const val STATUS = "loom_thread_status"

    /** 观测。 */
    const val OBSERVE = "loom_thread_observe"

    /** 停止。 */
    const val STOP = "loom_thread_stop"
}

/** 引擎本地工具协议（字符串进出——引擎路由层包装为流式）。 */
interface LoomLocalTool {
    /** 工具 id。 */
    val id: String

    /** 一行说明（提示词目录渲染）。 */
    val description: String

    /** 参数 JSON Schema。 */
    val parametersSchema: String

    /** 执行（同步语义——引擎包成单帧流）。 */
    suspend fun execute(argumentsJson: String): String
}

/** 只读参数视图。 */
private class Args(private val obj: JsonObject?) {
    fun string(key: String): String? = obj?.get(key)
        ?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
}

/** 参数解析（防御：坏 JSON 折叠空表）——顶层入口（嵌套工具类可直接调）。 */
private fun parseArgs(json: String): Args = Args(
    runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull()
)

/** 子线程四工具组装（挂到 [hub] 上）。 */
class LoomSwarmTools(private val hub: LoomSwarmHub) {

    /** 全部工具实例（引擎组装本地目录用）。 */
    fun all(): List<LoomLocalTool> = listOf(
        ThreadStartTool(hub),
        ThreadSendTool(hub),
        ThreadStatusTool(hub),
        ThreadObserveTool(hub),
        ThreadStopTool(hub)
    )

    private open class BaseTool(
        override val id: String,
        override val description: String,
        override val parametersSchema: String
    ) : LoomLocalTool

    private class ThreadStartTool(private val hub: LoomSwarmHub) : BaseTool(
        id = LoomSwarmCatalog.START,
        description = "起飞一条子线程执行可隔离的子任务（资料收集/独立探索）。返回线程 id。",
        parametersSchema = "{\"type\":\"object\",\"properties\":{\"label\":{\"type\":\"string\",\"description\":\"子任务一句话标签\"},\"task\":{\"type\":\"string\",\"description\":\"交给子线程的首条任务指令\"}},\"required\":[\"label\",\"task\"]}"
    ) {
        override suspend fun execute(argumentsJson: String): String {
            val args = parseArgs(argumentsJson)
            val label = args.string("label")?.takeIf { it.isNotBlank() } ?: "未命名子任务"
            val task = args.string("task")?.takeIf { it.isNotBlank() }
                ?: return "参数缺失：task（首条任务指令必填）"
            return try {
                val id = hub.spawn(label)
                // 起飞即送首条任务（一条 send 落地——模型拿到即结果）
                val reply = hub.send(id, task)
                "子线程 $id（$label）首飞完成：\n$reply"
            } catch (e: LoomSwarmHub.SwarmBusyException) {
                "子线程并发已达上限——请先用 loom_thread_status 收口闲置线程"
            }
        }
    }

    private class ThreadSendTool(private val hub: LoomSwarmHub) : BaseTool(
        id = LoomSwarmCatalog.SEND,
        description = "向子线程追加消息并推进到收口（跑完一飞）。返回子线程的最后正式回复。",
        parametersSchema = "{\"type\":\"object\",\"properties\":{\"thread_id\":{\"type\":\"string\"},\"message\":{\"type\":\"string\"},\"expect\":{\"type\":\"string\",\"description\":\"可选条件（如 status == ok）——满足返回 true，否则 false 附回执\"}},\"required\":[\"thread_id\",\"message\"]}"
    ) {
        override suspend fun execute(argumentsJson: String): String {
            val args = parseArgs(argumentsJson)
            val threadId = args.string("thread_id")?.takeIf { it.isNotBlank() }
                ?: return "参数缺失：thread_id"
            val message = args.string("message")?.takeIf { it.isNotBlank() }
                ?: return "参数缺失：message"
            val expect = args.string("expect")
            return try {
                val reply = hub.send(threadId, message)
                if (expect.isNullOrBlank()) {
                    reply
                } else {
                    val context = mapOf(
                        "status" to "ok",
                        "reply" to reply,
                        "thread_id" to threadId
                    )
                    val verdict = if (LoomCondition.evaluate(expect, context)) "true" else "false"
                    "条件「$expect」判定：$verdict\n$reply"
                }
            } catch (e: LoomSwarmHub.SwarmNoSuchThreadException) {
                "子线程不存在：$threadId（用 loom_thread_status 查看在册线程）"
            } catch (e: LoomSwarmHub.SwarmBusyException) {
                "子线程 $threadId 正在织造——稍后用 loom_thread_status 轮询再送"
            }
        }
    }

    private class ThreadStatusTool(private val hub: LoomSwarmHub) : BaseTool(
        id = LoomSwarmCatalog.STATUS,
        description = "查看子线程快照（阶段/轮数/截断回执）。不传 thread_id 列出全部。",
        parametersSchema = "{\"type\":\"object\",\"properties\":{\"thread_id\":{\"type\":\"string\"}}}"
    ) {
        override suspend fun execute(argumentsJson: String): String {
            val args = parseArgs(argumentsJson)
            val id = args.string("thread_id")?.takeIf { it.isNotBlank() }
            val snapshots = hub.status(id)
            if (snapshots.isEmpty()) return "（没有在册子线程）"
            return snapshots.joinToString("\n\n") { s ->
                buildString {
                    append("线程 ").append(s.threadId).append("（").append(s.label).append("）：")
                        .append(phaseText(s.phase))
                    append(" · 已织 ").append(s.rounds).append(" 回合")
                    if (!s.lastReply.isBlank()) {
                        append("\n最近回执：").append(s.lastReply.take(300))
                    }
                    s.failure?.let { append("\n失败原因：").append(it) }
                }
            }
        }

        private fun phaseText(phase: LoomThreadPhase): String = when (phase) {
            LoomThreadPhase.IDLE -> "待命"
            LoomThreadPhase.WEAVING -> "织造中"
            LoomThreadPhase.SETTLED -> "已收口"
            LoomThreadPhase.FAILED -> "失败"
            LoomThreadPhase.ABORTED -> "已中止"
        }
    }

    private class ThreadObserveTool(private val hub: LoomSwarmHub) : BaseTool(
        id = LoomSwarmCatalog.OBSERVE,
        description = "不推进地摘录子线程近期织物（用户轮/助手轮按序裁剪）。",
        parametersSchema = "{\"type\":\"object\",\"properties\":{\"thread_id\":{\"type\":\"string\"}},\"required\":[\"thread_id\"]}"
    ) {
        override suspend fun execute(argumentsJson: String): String {
            val args = parseArgs(argumentsJson)
            val threadId = args.string("thread_id")?.takeIf { it.isNotBlank() }
                ?: return "参数缺失：thread_id"
            return try {
                hub.observe(threadId)
            } catch (e: LoomSwarmHub.SwarmNoSuchThreadException) {
                "子线程不存在：$threadId"
            }
        }
    }

    private class ThreadStopTool(private val hub: LoomSwarmHub) : BaseTool(
        id = LoomSwarmCatalog.STOP,
        description = "中止并退役一条子线程（用完即收——释放并发额度）。",
        parametersSchema = "{\"type\":\"object\",\"properties\":{\"thread_id\":{\"type\":\"string\"},\"confirm\":{\"type\":\"boolean\"}},\"required\":[\"thread_id\"]}"
    ) {
        override suspend fun execute(argumentsJson: String): String {
            val args = parseArgs(argumentsJson)
            val threadId = args.string("thread_id")?.takeIf { it.isNotBlank() }
                ?: return "参数缺失：thread_id"
            return if (hub.stop(threadId)) {
                "子线程 $threadId 已中止退役"
            } else {
                "子线程不存在：$threadId"
            }
        }
    }
}
