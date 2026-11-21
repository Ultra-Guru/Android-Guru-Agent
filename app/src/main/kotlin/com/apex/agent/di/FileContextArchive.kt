package com.apex.agent.di

import com.apex.agent.core.llm.LlmMessage
import com.apex.agent.core.llm.ToolCall
import com.apex.agent.core.tools.builtin.context.ContextRecord
import com.apex.agent.core.tools.builtin.context.SessionContextArchive
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/**
 * # 文件式上下文归档（#396 · [SessionContextArchive] 的生产实现）
 *
 * 压缩装饰器（ArchivingCompressor）把被裁剪的原始消息写进这里，检索
 * 工具（ContextSearch / ContextFetch）从这里读全文。落盘单文件
 * `context_archive/session_archive.json`（原子写），进程内缓存读写同锁。
 *
 * ## 合并语义
 *
 * 同一工具结果可能先后以「原文」与「被截断版」两次进归档（先压缩裁剪、
 * 后就地截断的情形）——按 `toolCallId` 去重**保留更长的原文**，其余消息
 * 结构性去重。条数封顶 [MAX_RECORDS]（丢最旧）+ 字节封顶 [MAX_ARCHIVE_BYTES]
 * （#862：归档存未截断原文，单条可达 100k 字符，纯条数上限挡不住数十 MB
 * 的归档文件）：一次压缩通常裁几十至几百条，2000 条 / 4MB 已覆盖超长
 * 会话的多轮压缩。
 *
 * ## 锁纪律（#862）
 *
 * [appendArchived] 锁内只做 merge 与缓存置换；JSON 编码与写盘在锁外
 * 执行（[persist] 以 [writeEpoch] 守卫：编码期间有新写入/clear 进来则
 * 本快照过时、放弃落盘——后继快照必含本批消息，不丢数据）。检索工具
 * （同锁的 [archivedRecords] / [size]）不再被数十 MB 级序列化阻塞。
 *
 * ## 会话生命周期
 *
 * 引擎 `clearHistory()` / `restoreHistory()` 经接口调用 [clear]；
 * 存储页「清空会话历史」同样接线（防孤儿归档）。
 *
 * IO 纪律：全方法同步阻塞（调用方在 IO 线程 / 工具执行线程），
 * 读失败降级为空归档（坏档隔离），写失败留痕不抛（归档是尽力而为的
 * 增强层，绝不允许把压缩主流程带崩）。
 */
class FileContextArchive(private val baseDir: File) : SessionContextArchive {

    @Serializable
    private data class StoredToolCall(val id: String, val name: String, val arguments: String)

    @Serializable
    private data class StoredMessage(
        val role: String,
        val content: String,
        val toolCallId: String? = null,
        val toolCalls: List<StoredToolCall> = emptyList()
    ) {
        fun toLlmMessage(): LlmMessage = when (role) {
            "system" -> LlmMessage.System(content)
            "user" -> LlmMessage.User(content)
            "assistant" -> LlmMessage.Assistant(
                content = content,
                toolCalls = toolCalls.map { ToolCall(it.id, it.name, it.arguments) }
            )
            "tool" -> LlmMessage.ToolResult(toolCallId = toolCallId.orEmpty(), content = content)
            else -> LlmMessage.User(content)
        }

        companion object {
            fun from(msg: LlmMessage): StoredMessage = when (msg) {
                is LlmMessage.System -> StoredMessage(role = "system", content = msg.content)
                is LlmMessage.User -> StoredMessage(role = "user", content = msg.content)
                is LlmMessage.Assistant -> StoredMessage(
                    role = "assistant",
                    content = msg.content,
                    toolCalls = msg.toolCalls.map { StoredToolCall(it.id, it.name, it.arguments) }
                )
                is LlmMessage.ToolResult -> StoredMessage(
                    role = "tool",
                    content = msg.content,
                    toolCallId = msg.toolCallId
                )
            }
        }
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = ListSerializer(StoredMessage.serializer())
    private val lock = Any()

    /** 进程内缓存（null = 未加载，首次读取时落盘加载）。 */
    @Volatile
    private var cache: List<StoredMessage>? = null

    /**
     * 写入代数（#862）：锁内递增，锁外 persist 完成编码后核对——不一致即
     * 本快照已过时（并发 append / clear 领先），放弃落盘。@Volatile：
     * persist 在锁外读它。
     */
    @Volatile
    private var writeEpoch: Long = 0L

    private val file: File
        get() = File(baseDir, ARCHIVE_FILE)

    init {
        runCatching { baseDir.mkdirs() }
    }

    override fun appendArchived(messages: List<LlmMessage>) {
        if (messages.isEmpty()) return
        // #862：锁内仅 merge + 缓存置换；编码与写盘移出锁（见类 KDoc 锁纪律）
        var merged: List<StoredMessage>
        var epoch: Long
        synchronized(lock) {
            val base = ensureCache().toMutableList()
            merged = enforceByteBudget(
                merge(base, messages.map { StoredMessage.from(it) }).takeLast(MAX_RECORDS)
            )
            cache = merged
            epoch = ++writeEpoch
        }
        persist(merged, epoch)
    }

    override fun archivedRecords(): List<ContextRecord> = synchronized(lock) {
        ensureCache().mapIndexed { index, stored ->
            ContextRecord(index, stored.role, expand(stored), 0L)
        }
    }

    override fun size(): Int = synchronized(lock) { ensureCache().size }

    override fun clear() {
        synchronized(lock) {
            cache = emptyList()
            // #862：clear 也推代数——在途 persist 的旧快照不得在 delete 后
            // rename 复活归档
            ++writeEpoch
            runCatching { file.delete() }
                .onFailure { e ->
                    AppLogger.instance.warn(
                        LogCategory.SYSTEM, "FileContextArchive",
                        "归档文件删除失败（缓存已清，下次写入会覆盖）: ${e.message}"
                    )
                }
        }
    }

    // ── 内部 ─────────────────────────────────────────────────────────

    /** 展示态 content：assistant 的工具调用补 [tool_call] 标记行（与工具层口径一致）。 */
    private fun expand(stored: StoredMessage): String {
        if (stored.role != "assistant" || stored.toolCalls.isEmpty()) return stored.content
        val callLines = stored.toolCalls.joinToString("\n") { "[tool_call] ${it.name}" }
        return if (stored.content.isBlank()) callLines else stored.content + "\n" + callLines
    }

    /**
     * 合并去重：同 toolCallId 的 ToolResult 保留更长原文；其余按结构性
     * 相等去重（先到的形态进档即定稿，后到的等价副本直接丢弃）。
     */
    private fun merge(
        base: List<StoredMessage>,
        incoming: List<StoredMessage>
    ): List<StoredMessage> {
        if (incoming.isEmpty()) return base
        val result = base.toMutableList()
        for (msg in incoming) {
            if (msg.role == "tool") {
                val idx = result.indexOfFirst {
                    it.role == "tool" && it.toolCallId == msg.toolCallId
                }
                if (idx < 0) {
                    result += msg
                } else if (msg.content.length > result[idx].content.length) {
                    result[idx] = msg
                }
            } else if (msg !in result) {
                result += msg
            }
        }
        return result
    }

    private fun ensureCache(): List<StoredMessage> {
        cache?.let { return it }
        val loaded: List<StoredMessage> = if (!file.exists()) {
            emptyList()
        } else {
            try {
                json.decodeFromString(serializer, file.readText(Charsets.UTF_8))
            } catch (e: SerializationException) {
                quarantine()
                emptyList()
            } catch (e: IOException) {
                emptyList()
            }
        }
        cache = loaded
        return loaded
    }

    /**
     * 字节预算（#862）：在条数封顶之外再叠加字节封顶——归档存未截断原文
     * （单条可达 100k 字符），2000 条纯条数上限可被顶到数十 MB，导致每
     * 次压缩事件的全量序列化 + 整文件重写都数十 MB 级。超预算从最旧丢起
     * （保留更新的条目）；单条即超预算时也保留最新一条，绝不清空归档。
     * 预算按 UTF-8 估算（内容 + 工具调用三字段 + 结构开销），不做真实
     * 序列化测长（那正是要移出锁外的高成本操作）。
     */
    private fun enforceByteBudget(messages: List<StoredMessage>): List<StoredMessage> {
        var total = 0L
        for (m in messages) total += estimatedBytes(m)
        if (total <= MAX_ARCHIVE_BYTES) return messages
        // 从最新往回累加到预算线，保留该后缀（更旧的丢弃）
        var acc = 0L
        var keepFrom = messages.size
        for (i in messages.indices.reversed()) {
            val size = estimatedBytes(messages[i])
            if (acc + size > MAX_ARCHIVE_BYTES) break
            acc += size
            keepFrom = i
        }
        return if (keepFrom < messages.size) {
            messages.subList(keepFrom, messages.size).toList()
        } else {
            messages.takeLast(1)
        }
    }

    /** 单条归档记录的 UTF-8 体积估算（字段长 + 结构开销）。 */
    private fun estimatedBytes(m: StoredMessage): Long {
        var n = 64L + m.role.length + m.content.toByteArray(Charsets.UTF_8).size
        for (c in m.toolCalls) {
            n += 48L + c.id.length + c.name.length +
                c.arguments.toByteArray(Charsets.UTF_8).size
        }
        return n
    }

    /** 坏档隔离：改名 .corrupt 留排查线索（与 CodeSessionStore 同款策略）。 */
    private fun quarantine() {
        runCatching {
            val corrupt = File(file.parentFile, file.name + ".corrupt")
            if (!file.renameTo(corrupt)) {
                file.copyTo(corrupt, overwrite = true)
                file.delete()
            }
        }
    }

    /**
     * 原子写：tmp + rename，失败降级直写；全部失败留痕（不抛）。
     *
     * #862：编码与写盘在锁外执行；[epoch] 是快照拍摄时的写入代数，落盘
     * 前核对——过时快照（并发 append / clear 已领先）放弃落盘，后继
     * persist 的快照必含本批消息，不丢数据。
     */
    private fun persist(messages: List<StoredMessage>, epoch: Long) {
        try {
            val raw = json.encodeToString(serializer, messages)
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(raw, Charsets.UTF_8)
            if (writeEpoch != epoch) {
                // 快照过时：更新版本的快照已在途/已落盘，本份作废
                runCatching { tmp.delete() }
                return
            }
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        } catch (e: IOException) {
            if (writeEpoch != epoch) return
            runCatching { file.writeText(json.encodeToString(serializer, messages), Charsets.UTF_8) }
                .onFailure {
                    AppLogger.instance.warn(
                        LogCategory.SYSTEM, "FileContextArchive",
                        "归档落盘失败（内存缓存仍然可用，本进程内检索不受影响）: ${e.message}"
                    )
                }
        }
    }

    private companion object {
        const val ARCHIVE_FILE = "session_archive.json"
        /** 归档条数封顶（丢最旧）：覆盖超长会话的多轮压缩累积。 */
        const val MAX_RECORDS = 2000
        /**
         * 归档字节封顶（#862）：UTF-8 估算口径，约 2000 条 × 2KB 均值。
         * 超限从最旧丢起——防止归档存未截断原文时每次压缩事件都做数十 MB
         * 级的锁内序列化 + 整文件重写。
         */
        const val MAX_ARCHIVE_BYTES: Long = 4L * 1024 * 1024
    }
}
