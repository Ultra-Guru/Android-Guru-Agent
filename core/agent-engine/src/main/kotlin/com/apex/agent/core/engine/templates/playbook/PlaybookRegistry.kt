package com.apex.agent.core.engine.templates.playbook

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** 剧本导入模式（语义与模板库一致）。 */
enum class PlaybookImportMode {
    /** 合并： incoming 覆盖同 id 的自定义剧本；内置剧本永不替换。 */
    MERGE,

    /** 替换： 清空后整库替换为 incoming（内置移除，可 reseedBuiltIns 还原）。 */
    REPLACE
}

/** 导入结果（防御式：解析失败 ok=false + error 留痕，不抛）。 */
data class PlaybookImportResult(
    val ok: Boolean,
    val importedCount: Int,
    val skippedBuiltInCount: Int,
    val skippedInvalidCount: Int,
    val error: String? = null
)

/**
 * ═══ 任务剧本注册表 ═══
 *
 * 与 [com.apex.agent.core.engine.templates.PromptTemplateRegistry] 同款
 * 纪律（逐条克隆，单一事实源 = [BuiltinPlaybooks] 出厂表）：
 *
 * ```
 * <storageDir>/
 *   ├── playbooks.json         整库单文件（PlaybookLibrary 信封）
 *   ├── playbooks.json.tmp     写入中 temp（崩溃残留，首载清理）
 *   └── playbooks.json.corrupt 损坏备份（加载失败时隔离）
 * ```
 *
 * - **原子写**：tmp → fsync → rename（rename 失败回退 copyTo + delete）；
 * - **防御式读**：损坏 JSON → 备份后空库启动，绝不抛；
 * - **并发模型**：@Volatile 不可变快照 + 写 Mutex 串行 + 惰性双检加载；
 * - **内置不可更改**：save 对已知内置 id 抛 IllegalArgumentException；
 *   磁盘上被篡改的内置条目加载时定义字段回归出厂定义（自愈只修内存，
 *   下次写盘追平）；删除拒绝；导入永远丢弃内置 id。
 */
class PlaybookRegistry(
    private val storageDir: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val logger: (String) -> Unit = {},
    val onChanged: (() -> Unit)? = null
) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        prettyPrint = false
    }

    /** 写路径串行锁。 */
    private val mutex = Mutex()

    /** 内存缓存：不可变快照（LinkedHashMap 保插入序）。 */
    @Volatile
    private var cache: Map<String, PlaybookDefinition> = LinkedHashMap()

    /** 惰性加载双检锁 + 完成旗标。 */
    private val loadLock = Any()

    @Volatile
    private var loaded = false

    init {
        runCatching { storageDir.mkdirs() }
            .onFailure { logger("PlaybookRegistry: mkdirs failed: ${it.message}") }
    }

    // ═══ 读 ═══

    /** 按 id 查询（非法 id 返回 null）。 */
    fun get(id: String): PlaybookDefinition? {
        if (!PLAYBOOK_ID_PATTERN.matches(id)) return null
        ensureLoaded()
        return cache[id]
    }

    /** 列出剧本（内置播种序 + 后续保存序）。 */
    fun list(): List<PlaybookDefinition> {
        ensureLoaded()
        return cache.values.toList()
    }

    /** 导出整库为 pretty JSON（含内置剧本）。 */
    fun exportLibrary(): String {
        ensureLoaded()
        val pretty = Json { encodeDefaults = true; prettyPrint = true }
        return pretty.encodeToString(PlaybookLibrary.serializer(), PlaybookLibrary(cache.values.toList()))
    }

    // ═══ 写 ═══

    /**
     * 保存（upsert by id）。内置 id 一律拒绝（定制走「另存为副本」）；
     * 自定义 id 的 isBuiltIn 规范化为 false。磁盘写失败只留痕不抛。
     */
    suspend fun save(playbook: PlaybookDefinition) {
        validateForSave(playbook)
        if (BuiltinPlaybooks.isKnownBuiltInId(playbook.id)) {
            throw IllegalArgumentException(
                "内置剧本不可更改（id='${playbook.id}'）；可复制为自定义副本后修改"
            )
        }
        var changed = false
        mutex.withLock {
            ensureLoaded()
            val existing = cache[playbook.id]
            val now = clock()
            val toStore = playbook.copy(
                isBuiltIn = false,
                createdAt = when {
                    existing != null -> existing.createdAt
                    playbook.createdAt > 0 -> playbook.createdAt
                    else -> now
                },
                updatedAt = now
            )
            cache = LinkedHashMap(cache).apply { put(toStore.id, toStore) }
            changed = true
            persistLocked()
        }
        if (changed) onChanged?.invoke()
    }

    /** 删除：内置拒绝（返回 false）；不存在返回 false；成功 true。 */
    suspend fun delete(id: String): Boolean {
        var removed = false
        mutex.withLock {
            ensureLoaded()
            val existing = cache[id] ?: return false
            if (existing.isBuiltIn) {
                logger("PlaybookRegistry: refuse to delete built-in playbook '$id'")
                return false
            }
            cache = LinkedHashMap(cache).apply { remove(id) }
            removed = true
            persistLocked()
        }
        if (removed) onChanged?.invoke()
        return removed
    }

    /**
     * 播种内置剧本（幂等）：缺则补；force=true 时把已存在的内置条目
     * 整体还原为出厂定义。返回写入条数。
     */
    suspend fun reseedBuiltIns(force: Boolean = false): Int {
        var seeded = 0
        mutex.withLock {
            ensureLoaded()
            val next = LinkedHashMap(cache)
            for (builtin in BuiltinPlaybooks.ALL) {
                val existing = next[builtin.id]
                when {
                    existing == null -> {
                        next[builtin.id] = builtin
                        seeded++
                    }
                    force && existing != builtin -> {
                        next[builtin.id] = builtin
                        seeded++
                    }
                    else -> Unit
                }
            }
            if (seeded > 0) {
                cache = next
                persistLocked()
            }
        }
        if (seeded > 0) onChanged?.invoke()
        return seeded
    }

    /**
     * 导入剧本库（防御式）。已知内置 id 一律丢弃（杜绝导入伪造内置）；
     * 其余条目落为自定义；非法条目跳过 + 计数留痕。
     */
    suspend fun importLibrary(payload: String, mode: PlaybookImportMode): PlaybookImportResult {
        val library = try {
            json.decodeFromString(PlaybookLibrary.serializer(), payload)
        } catch (e: Exception) {
            logger("PlaybookRegistry: import parse failed (${e::class.simpleName}: ${e.message})")
            return PlaybookImportResult(ok = false, 0, 0, 0, error = e.message)
        }
        var imported = 0
        var skippedBuiltIn = 0
        var skippedInvalid = 0
        mutex.withLock {
            ensureLoaded()
            val now = clock()
            val next = if (mode == PlaybookImportMode.REPLACE) LinkedHashMap() else LinkedHashMap(cache)
            for (incoming in library.playbooks) {
                when {
                    BuiltinPlaybooks.isKnownBuiltInId(incoming.id) -> {
                        skippedBuiltIn++
                        logger("PlaybookRegistry: import skipped built-in id '${incoming.id}'")
                    }
                    !isValidPlaybook(incoming) -> {
                        skippedInvalid++
                        logger("PlaybookRegistry: import skipped invalid playbook '${incoming.id}'")
                    }
                    else -> {
                        next[incoming.id] = incoming.copy(
                            isBuiltIn = false,
                            createdAt = if (incoming.createdAt > 0) incoming.createdAt else now,
                            updatedAt = if (incoming.updatedAt > 0) incoming.updatedAt else now
                        )
                        imported++
                    }
                }
            }
            cache = next
            if (mode == PlaybookImportMode.REPLACE || imported > 0) {
                persistLocked()
            }
        }
        if (mode == PlaybookImportMode.REPLACE || imported > 0) {
            onChanged?.invoke()
        }
        return PlaybookImportResult(ok = true, imported, skippedBuiltIn, skippedInvalid)
    }

    /** 渲染/挂载计数自增（只动 usageCount，不碰 updatedAt）。 */
    suspend fun incrementUsage(playbookId: String) {
        mutex.withLock {
            ensureLoaded()
            val existing = cache[playbookId] ?: return
            cache = LinkedHashMap(cache).apply {
                put(playbookId, existing.copy(usageCount = existing.usageCount + 1))
            }
            persistLocked()
        }
    }

    // ═══ 校验 ═══

    private fun validateForSave(playbook: PlaybookDefinition) {
        require(PLAYBOOK_ID_PATTERN.matches(playbook.id)) {
            "illegal playbook id '${playbook.id}' (expected [a-z0-9_-]{1,64})"
        }
        require(playbook.name.isNotBlank()) { "playbook name must not be blank (id='${playbook.id}')" }
        require(playbook.master.isNotBlank()) { "playbook master must not be blank (id='${playbook.id}')" }
        require(playbook.stages.isNotEmpty()) { "playbook must have at least one stage (id='${playbook.id}')" }
        require(playbook.stages.all { it.id.isNotBlank() && it.subPrompt.isNotBlank() }) {
            "playbook stages must have non-blank id and subPrompt (id='${playbook.id}')"
        }
    }

    /** 导入条目校验（非法跳过而非抛）。 */
    private fun isValidPlaybook(playbook: PlaybookDefinition): Boolean =
        PLAYBOOK_ID_PATTERN.matches(playbook.id) &&
            playbook.name.isNotBlank() &&
            playbook.master.isNotBlank() &&
            playbook.stages.isNotEmpty() &&
            playbook.stages.all { it.id.isNotBlank() && it.subPrompt.isNotBlank() }

    // ═══ 持久化 ═══

    /**
     * 首次访问同步扫描加载（双检 + 监视器锁）。内置播种不在本层做
     * （reseedBuiltIns 保持显式契约）：生产端由 DI 模块构造后异步播种。
     */
    private fun ensureLoaded() {
        if (loaded) return
        synchronized(loadLock) {
            if (loaded) return
            val target = File(storageDir, FILE_NAME)
            if (target.exists()) {
                try {
                    val library = json.decodeFromString(PlaybookLibrary.serializer(), target.readText())
                    val normalized = LinkedHashMap<String, PlaybookDefinition>()
                    for (p in library.playbooks) {
                        if (!isValidPlaybook(p)) {
                            logger("PlaybookRegistry: skip invalid playbook in store '${p.id}'")
                            continue
                        }
                        val builtinDef = BUILTIN_DEFINITIONS[p.id]
                        if (builtinDef != null) {
                            // 内置自愈：定义字段强制回归出厂定义，只保留三项运行元数据。
                            val healed = builtinDef.copy(
                                createdAt = p.createdAt,
                                updatedAt = p.updatedAt,
                                usageCount = p.usageCount
                            )
                            if (healed != p) {
                                logger("PlaybookRegistry: heal tampered built-in '${p.id}' back to factory definition")
                            }
                            normalized[p.id] = healed
                        } else {
                            normalized[p.id] = p.copy(isBuiltIn = false)
                        }
                    }
                    cache = normalized
                } catch (e: Exception) {
                    logger(
                        "PlaybookRegistry: playbooks.json corrupt, backing up and starting empty " +
                            "(${e::class.simpleName}: ${e.message})"
                    )
                    runCatching {
                        val backup = File(storageDir, CORRUPT_FILE_NAME)
                        if (backup.exists()) backup.delete()
                        target.renameTo(backup)
                    }.onFailure {
                        logger("PlaybookRegistry: corrupt backup move failed: ${it.message}")
                    }
                    cache = LinkedHashMap()
                }
            } else {
                cache = LinkedHashMap()
            }
            cleanupTempResidue()
            loaded = true
        }
    }

    /** 原子写盘（持锁调用）：tmp → fsync → rename；失败只留痕不抛。 */
    private fun persistLocked() {
        val target = File(storageDir, FILE_NAME)
        val tmp = File(storageDir, TMP_FILE_NAME)
        try {
            val payload = json.encodeToString(
                PlaybookLibrary.serializer(),
                PlaybookLibrary(playbooks = cache.values.toList())
            )
            runCatching { storageDir.mkdirs() }
            FileOutputStream(tmp).use { out ->
                out.write(payload.toByteArray())
                out.flush()
                out.fd.sync()
            }
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        } catch (e: IOException) {
            tmp.delete()
            logger("PlaybookRegistry: persist failed (${e.message}); in-memory cache stays authoritative")
        } catch (e: IllegalArgumentException) {
            // kotlinx SerializationException 是 IllegalArgumentException 子类
            tmp.delete()
            logger("PlaybookRegistry: persist serialize failed (${e.message})")
        }
    }

    /** 清理半写 temp 残留（仅首载执行）。 */
    private fun cleanupTempResidue() {
        val tmp = File(storageDir, TMP_FILE_NAME)
        if (tmp.exists()) {
            val ok = tmp.delete()
            logger("PlaybookRegistry: cleaned half-written temp file (deleted=$ok)")
        }
    }

    private companion object {
        const val FILE_NAME = "playbooks.json"
        const val TMP_FILE_NAME = "playbooks.json.tmp"
        const val CORRUPT_FILE_NAME = "playbooks.json.corrupt"

        /** 出厂定义表：内置自愈与不可更改判定的单一事实源。 */
        val BUILTIN_DEFINITIONS: Map<String, PlaybookDefinition> =
            BuiltinPlaybooks.ALL.associateBy { it.id }
    }
}
