package com.apex.agent.ui.screen.code.history

import com.apex.agent.ui.screen.code.session.CodeSessionSnapshot
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

// ─────────────────────────────────────────────────────────────────────────────
// Coding 历史对话归档仓库（v1.4.10「Coding 历史对话」）
//
// 痛点：旧实现的「新会话」按钮 = 物理删除当前工作区的会话快照 + 引擎记忆
// （clearConversation → codeSessionStore.clear），对话一旦换新就永久丢失，
// 既没有历史列表可回看，也没有恢复入口。
//
// 本仓库补上这一层：**新会话前先把当前对话完整归档**（UI 快照 + 双思考
// 逻辑的引擎记忆文件），历史面板可列出 / 恢复 / 删除：
//
// 存储：baseDir/<ws 目录>/<会话 id>/
//   - ui.json        —— CodeSessionSnapshot（消息/待办/时间轴检查点）
//   - deep.json      —— 深潜线引擎记忆原文件（code_memory/ws_<id>.json 快照）
//   - standard.json  —— 标准线引擎记忆原文件（code_memory_standard/ws_<id>.json）
//
// 会话 id = 归档时刻的 epoch 毫秒（13 位定长字符串，字典序即时间序）。
//
// 恢复语义：引擎记忆以**原文件拷贝**回写（CodeConversationMemory 的
// bindWorkspace 会重置进程内缓存，setActiveWorkspace 重放后由引擎自身
// 重新落盘收敛）；UI 快照经调用方写回 CodeSessionStore 后走既有
// restoreSessionSnapshot 通道。两条线（深潜/标准）记忆同时恢复——切换
// 思考逻辑后现场同样完整。
//
// IO 约定（对齐 CodeSessionStore）：同步阻塞，调用方自行切 Dispatchers.IO；
// 原子写；坏档隔离改名 .corrupt。
// ─────────────────────────────────────────────────────────────────────────────

/** 历史列表条目（ui.json 的轻量投影视，供 Sheet 渲染）。 */
data class CodeHistoryEntry(
    /** 会话 id（归档时刻 epoch 毫秒字符串，兼做目录名）。 */
    val id: String,
    val workspaceId: String,
    /** 列表标题：首条 USER 消息预览（空串 = 无用户消息，UI 本地化兜底）。 */
    val title: String,
    /** 归档时刻（epoch 毫秒）。 */
    val updatedAt: Long,
    /** 消息条数。 */
    val messageCount: Int
)

/**
 * @param baseDir 归档根目录（建议 filesDir/code_history）
 * @param deepMemoryDir 深潜线引擎记忆目录（filesDir/code_memory）
 * @param standardMemoryDir 标准线引擎记忆目录（filesDir/code_memory_standard）
 * @param clock 假钟注入（会话 id = 归档时刻毫秒；测试用可掳时钟，不睡墙钟）
 */
class CodeHistoryStore(
    private val baseDir: File,
    private val deepMemoryDir: File,
    private val standardMemoryDir: File,
    private val clock: () -> Long = System::currentTimeMillis
) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    init {
        runCatching { baseDir.mkdirs() }
    }

    /**
     * 归档当前对话（新会话 / 恢复其它会话前调用）。
     *
     * @return 会话 id；归档失败（IO 异常等）返回 null（调用方留痕，不阻断清空）。
     */
    fun archive(workspaceId: String, snapshot: CodeSessionSnapshot): String? {
        val sessionId = clock().toString()
        val dir = File(sessionDirFor(workspaceId), sessionId)
        return try {
            dir.mkdirs()
            writeAtomic(
                File(dir, UI_FILE),
                json.encodeToString(CodeSessionSnapshot.serializer(), snapshot)
            )
            // 引擎记忆原文件拷贝（存在才拷；缺失 = 该会话引擎侧无历史）
            copyIfExists(memoryFileFor(deepMemoryDir, workspaceId), File(dir, DEEP_FILE))
            copyIfExists(memoryFileFor(standardMemoryDir, workspaceId), File(dir, STANDARD_FILE))
            sessionId
        } catch (e: IOException) {
            runCatching { dir.deleteRecursively() }
            null
        }
    }

    /**
     * 列出工作区的历史会话（新→旧）。
     *
     * 单条 ui.json 损坏时隔离并跳过（不让一个坏档炸掉整个列表）。
     */
    fun entries(workspaceId: String): List<CodeHistoryEntry> {
        val wsDir = sessionDirFor(workspaceId)
        val dirs = wsDir.listFiles { f -> f.isDirectory } ?: return emptyList()
        return dirs.mapNotNull { dir ->
            val id = dir.name
            if (id.isEmpty() || !id.all { it.isDigit() }) return@mapNotNull null
            val ui = File(dir, UI_FILE)
            val snapshot = try {
                json.decodeFromString(CodeSessionSnapshot.serializer(), ui.readText(Charsets.UTF_8))
            } catch (e: SerializationException) {
                quarantine(dir)
                null
            } catch (e: IOException) {
                null
            }
            snapshot ?: return@mapNotNull null
            CodeHistoryEntry(
                id = id,
                workspaceId = workspaceId,
                title = snapshot.messages
                    .firstOrNull { it.role.equals("USER", ignoreCase = true) }
                    ?.text?.lineSequence()?.firstOrNull { l -> l.isNotBlank() }
                    ?.take(64)?.trim()
                    ?: "",
                updatedAt = if (snapshot.updatedAt > 0) snapshot.updatedAt else id.toLongOrNull() ?: 0L,
                messageCount = snapshot.messages.size
            )
        }.sortedByDescending { it.id }
    }

    /**
     * 纯读取归档快照（导出 Markdown 等只读消费方）。
     *
     * 与 [restoreInto] 的关键差异：**零副作用** —— 不回写引擎记忆、不动
     * 活位，只解析 ui.json。损坏/缺失返回 null（调用方自行提示）。
     */
    fun readSnapshot(workspaceId: String, sessionId: String): CodeSessionSnapshot? {
        val ui = File(sessionDirFor(workspaceId), "$sessionId/$UI_FILE")
        return try {
            json.decodeFromString(CodeSessionSnapshot.serializer(), ui.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 恢复：把归档的引擎记忆写回两条线的记忆文件，返回 UI 快照。
     *
     * @return ui.json 解析得到的快照（调用方写回 CodeSessionStore）；
     *         归档缺失或损坏返回 null。
     */
    fun restoreInto(workspaceId: String, sessionId: String): CodeSessionSnapshot? {
        val dir = File(sessionDirFor(workspaceId), sessionId)
        val ui = File(dir, UI_FILE)
        val snapshot = try {
            json.decodeFromString(CodeSessionSnapshot.serializer(), ui.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            return null
        }
        // 双线引擎记忆回写：归档里没有对应文件 = 目标文件删除（该会话引擎侧为空）
        restoreMemoryFile(File(dir, DEEP_FILE), memoryFileFor(deepMemoryDir, workspaceId))
        restoreMemoryFile(File(dir, STANDARD_FILE), memoryFileFor(standardMemoryDir, workspaceId))
        return snapshot
    }

    /** 删除单条历史会话（幂等：不存在时静默成功）。 */
    fun delete(workspaceId: String, sessionId: String) {
        File(sessionDirFor(workspaceId), sessionId).deleteRecursively()
    }

    /** 工作区被删除时清理其全部历史。 */
    fun clearWorkspace(workspaceId: String) {
        sessionDirFor(workspaceId).deleteRecursively()
    }

    // ── 内部 ─────────────────────────────────────────────────────────

    /** 工作区历史目录：baseDir/ws_<清洗后 id>（清洗口径与 CodeSessionStore 一致）。 */
    private fun sessionDirFor(workspaceId: String): File =
        File(baseDir, "ws_" + sanitizeName(workspaceId))

    /**
     * 引擎记忆文件定位（与 CodeConversationMemory 同款命名：
     * `ws_<safe>.json`，safe = 非 [A-Za-z0-9._-] 全替换下划线）。
     */
    private fun memoryFileFor(memoryDir: File, workspaceId: String): File =
        File(memoryDir, "ws_" + workspaceId.replace(UNSAFE_MEMORY_CHARS, "_") + ".json")

    private fun copyIfExists(src: File, dest: File) {
        if (!src.exists()) return
        src.copyTo(dest, overwrite = true)
    }

    /** 归档记忆文件 → 活位：有则覆盖拷贝，无则删活位（引擎重放时按空历史处理）。 */
    private fun restoreMemoryFile(archived: File, live: File) {
        runCatching {
            if (archived.exists()) {
                archived.copyTo(live, overwrite = true)
            } else {
                live.delete()
            }
        }
    }

    private fun quarantine(dir: File) {
        runCatching {
            val corrupt = File(dir.parentFile, dir.name + ".corrupt")
            if (!dir.renameTo(corrupt)) {
                dir.copyTo(corrupt, overwrite = true)
                dir.deleteRecursively()
            }
        }
    }

    private fun writeAtomic(file: File, raw: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        try {
            tmp.writeText(raw, Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        } catch (e: IOException) {
            tmp.delete()
            file.writeText(raw, Charsets.UTF_8)
        }
    }

    /** 文件名清洗（与 CodeSessionStore 同款：安全字符集 + 超长截断加散列）。 */
    private fun sanitizeName(workspaceId: String): String {
        val cleaned = workspaceId.replace(UNSAFE_DIR_CHARS, "_")
        val base = cleaned.ifEmpty { "default" }
        if (base.length <= MAX_FILE_STEM) return base
        val hash = Integer.toHexString(base.hashCode()).padStart(8, '0').takeLast(8)
        return base.take(MAX_FILE_STEM - 8) + hash
    }

    private companion object {
        const val UI_FILE = "ui.json"
        const val DEEP_FILE = "deep.json"
        const val STANDARD_FILE = "standard.json"
        const val MAX_FILE_STEM = 64
        /** 工作区目录名清洗（对齐 CodeSessionStore）。 */
        val UNSAFE_DIR_CHARS = Regex("[^A-Za-z0-9_-]")
        /** 引擎记忆文件名清洗（对齐 CodeConversationMemory，允许点）。 */
        val UNSAFE_MEMORY_CHARS = Regex("[^A-Za-z0-9._-]")
    }
}
