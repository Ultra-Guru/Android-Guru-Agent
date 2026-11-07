package com.apex.agent.core.tools.extension

import com.apex.agent.core.tools.builtin.AtomicFileWrite
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.Serializable
import java.io.File

/**
 * # ExtensionRegistry —— 扩展账本（`<filesDir>/extensions/registry.json`）
 *
 * **只记来源账，不管实体**：本注册表记录「通过统一闸门装了什么扩展、来自
 * 哪个 spec、什么版本、哪个指纹」，扩展实体本身落在各原生注册表
 * （SkillRegistry / McpManager / …）——与 FusionBundle 的「聚而不同」同一
 * 纪律，绝不建第三平行注册体系。锁文件（lock.json，PR-B）同目录演进。
 *
 * 模式与 [com.apex.agent.core.tools.fusion.FusionBundleRegistry] 同源：
 * synchronized 单锁 + 内存快照 + 原子写（tmp+rename）+ changes 广播。
 */
class ExtensionRegistry(
    /** 账本目录（app 层给 `<filesDir>/extensions`）。 */
    private val dir: File
) {
    @Serializable
    data class Record(
        val id: String,
        val type: String,
        val spec: String,
        val name: String,
        val version: String,
        val runtime: String,
        val sourceKind: String,
        val trustLevel: String = "community",
        val manifestHash: String = "",
        val installedAt: Long,
        /** 安装期说明（幂等/已存在等 notes 的留存）。 */
        val notes: List<String> = emptyList(),
        /**
         * 来源钉定描述（[com.apex.agent.core.tools.extension.PinnedSource.toDescriptor]，
         * 如 "npm:1.2.3+sha512-…" / "git@abc123def456"）—— 真实版本与不可变
         * 来源信息入库（2026-11 DSH Native Host；空 = 旧账或无可钉定生态）。
         */
        val pinnedSource: String = ""
    )

    @Serializable
    private data class Ledger(val extensions: List<Record> = emptyList())

    private val lock = Any()
    private val records = LinkedHashMap<String, Record>()

    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 16)

    /** 安装/卸载变更通知（工具快照、UI、doctor 订阅）。 */
    val changes: SharedFlow<Unit> = _changes.asSharedFlow()

    private val file: File get() = File(dir, "registry.json")

    init {
        runCatching { dir.mkdirs() }
        loadFromDisk()
    }

    private fun loadFromDisk() {
        if (!file.isFile) return
        val ledger = runCatching {
            ExtensionRegistryJson.json.decodeFromString(Ledger.serializer(), file.readText())
        }.getOrElse { return }
        synchronized(lock) {
            ledger.extensions.forEach { records[it.id] = it }
        }
    }

    private fun persistLocked() {
        val ledger = Ledger(records.values.toList())
        runCatching {
            AtomicFileWrite.writeText(
                file,
                ExtensionRegistryJson.json.encodeToString(Ledger.serializer(), ledger)
            )
        }
    }

    /** 全部已记录扩展（列表工具数据源）。 */
    fun all(): List<Record> = synchronized(lock) { records.values.toList() }

    fun get(id: String): Record? = synchronized(lock) { records[id] }

    fun contains(id: String): Boolean = synchronized(lock) { records.containsKey(id) }

    /** 记录一条安装账（幂等：同 id 覆盖 —— 覆盖即升级语义）。 */
    fun record(entry: Record) {
        synchronized(lock) {
            records[entry.id] = entry
            persistLocked()
        }
        _changes.tryEmit(Unit)
    }

    /** 移除账目（返回是否确有此账）。 */
    fun remove(id: String): Boolean {
        val removed = synchronized(lock) {
            records.remove(id) != null
        }
        if (removed) {
            persistLockedSafely()
            _changes.tryEmit(Unit)
        }
        return removed
    }

    private fun persistLockedSafely() = synchronized(lock) { persistLocked() }

    // ═══ 锁文件（lock.json）—— 声明式同步的版本钉扎（brief：防悄悄换版本）═══

    /** 锁条目：sync 安装成功即锁；resolve 指纹 ≠ 锁 = 版本漂移。 */
    @Serializable
    data class LockEntry(
        val id: String,
        val manifestHash: String,
        val version: String,
        val spec: String,
        val lockedAt: Long
    )

    @Serializable
    private data class LockFile(val locks: List<LockEntry> = emptyList())

    private val lockFile: File get() = File(dir, "lock.json")

    /** 锁快照（内存缓存 + 落盘）。 */
    private val locks = LinkedHashMap<String, LockEntry>()

    init {
        loadLocks()
    }

    private fun loadLocks() {
        if (!lockFile.isFile) return
        val parsed = runCatching {
            ExtensionRegistryJson.json.decodeFromString(LockFile.serializer(), lockFile.readText())
        }.getOrElse { return }
        synchronized(lock) { parsed.locks.forEach { locks[it.id] = it } }
    }

    private fun persistLocksLocked() {
        runCatching {
            synchronized(lock) {
                AtomicFileWrite.writeText(
                    lockFile,
                    ExtensionRegistryJson.json.encodeToString(
                        LockFile.serializer(),
                        LockFile(locks.values.toList())
                    )
                )
            }
        }
    }

    /** 钉版（sync 安装成功后调用；同 id 覆盖 = 重新钉版）。 */
    fun lock(entry: LockEntry) {
        synchronized(lock) {
            locks[entry.id] = entry
            persistLocksLocked()
        }
    }

    fun getLock(id: String): LockEntry? = synchronized(lock) { locks[id] }

    fun allLocks(): List<LockEntry> = synchronized(lock) { locks.values.toList() }

    fun unlock(id: String): Boolean = synchronized(lock) {
        val removed = locks.remove(id) != null
        if (removed) persistLocksLocked()
        removed
    }
}

/** 账本 JSON 编解码器（Registry 与测试共用）。 */
internal object ExtensionRegistryJson {
    val json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }
}
