package com.apex.agent.platform.terminal.tmux

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Tmux 会话元数据持久化（对齐 ubuntu/BootstrapStateStore 的模式）。
 *
 * 持久化内容：
 *  - 已登记的命名会话（name → createdAt / workingDirectory / lastAttachedAt）；
 *  - 最近一次活动会话名（宿主重启后恢复 Agent 的默认目标）。
 *
 * 恢复语义（对齐 T76 §18）：App 重启后 [load] 返回上次登记。tmux server 本身
 * 可能已随 rootfs 进程树消亡（见 TERMUX_CAPABILITY_MATRIX §10.4：PRoot 下 tmux
 * server 与会话同生命周期），因此登记 ≠ 存活 —— [TmuxSessionManager.listSessions]
 * 永远以 tmux ls 的**真实输出**为权威，本 store 只提供「上次叫什么」的软提示，
 * 绝不假报会话存活。
 *
 * 原子写（temp + rename + 损坏隔离）：对齐 RootfsMetadataStore / BootstrapStateStore
 * 的同款纪律 —— 损坏的元数据文件被静默吞掉会让 Agent 拿到「零会话」的假象；
 * 这里隔离保留现场（rename 成 .corrupt）并返回 null，让上层诚实上报。
 */
class TmuxSessionStore(
    private val storeFile: File
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val mutex = Mutex()

    init { storeFile.parentFile?.mkdirs() }

    /** 单条会话元数据登记（不含存活状态 —— 存活以 tmux ls 为权威）。 */
    @Serializable
    data class SessionRecord(
        val name: String,
        val createdAt: Long,
        val workingDirectory: String? = null,
        val lastAttachedAt: Long? = null
    )

    @Serializable
    data class StoreRecord(
        val schemaVersion: Int = CURRENT_SCHEMA,
        val sessions: List<SessionRecord> = emptyList(),
        val lastActiveSession: String? = null,
        val updatedAt: Long? = null
    )

    suspend fun save(record: StoreRecord): Result<Unit> = mutex.withLock {
        runCatching {
            val tmp = File(storeFile.parentFile, "${storeFile.name}.tmp")
            tmp.writeText(json.encodeToString(record))
            if (!tmp.renameTo(storeFile)) {
                tmp.copyTo(storeFile, overwrite = true)
                tmp.delete()
            }
        }
    }

    suspend fun load(): StoreRecord? = mutex.withLock {
        if (!storeFile.exists()) return@withLock null
        runCatching {
            json.decodeFromString<StoreRecord>(storeFile.readText())
        }.onFailure {
            // 损坏隔离（对齐 BootstrapStateStore T81 U-5）：静默吞损坏会让 Agent
            // 误以为「无历史会话」；隔离保留现场，返回 null 由上层诚实上报。
            val quarantine = File(storeFile.parentFile, storeFile.name + ".corrupt")
            runCatching { storeFile.renameTo(quarantine) }
        }.getOrNull()
    }

    suspend fun delete(): Result<Unit> = mutex.withLock {
        runCatching { storeFile.delete(); Unit }
    }

    suspend fun exists(): Boolean = mutex.withLock { storeFile.exists() }

    companion object {
        const val CURRENT_SCHEMA = 1
    }
}
