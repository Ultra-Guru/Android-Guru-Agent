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
        val notes: List<String> = emptyList()
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
}

/** 账本 JSON 编解码器（Registry 与测试共用）。 */
internal object ExtensionRegistryJson {
    val json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }
}
