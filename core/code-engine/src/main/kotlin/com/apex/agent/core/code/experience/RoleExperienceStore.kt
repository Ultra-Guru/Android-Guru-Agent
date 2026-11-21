package com.apex.agent.core.code.experience

import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * # 角色经验存储 —— 每角色一卷的 JSON 持久化
 *
 * ## 磁盘布局
 *
 * ```
 * <baseDir>/                      DI 注入（App: filesDir/role_experience）
 *   ├── builtin_coding_git.json   一角色一卷（RoleExperienceBook）
 *   ├── builtin_coding_kotlin.json
 *   └── role_1761….json           自定义角色同权同路径
 *         ↑ 写入中 temp（崩溃残留，装载视为不存在）
 * ```
 *
 * ## 与 LongTaskStore 同款的三层并发模型（刻意同构，降低维护心智）
 *
 * 1. **内存缓存**：`@Volatile` 不可变 Map 快照 + 写时复制 —— 读侧
 *    （[snapshotBlocking]）永远不加锁不挂起；
 * 2. **写串行锁**：读改写类操作（append / delete / clear / bumpHits /
 *    prune）在协程 Mutex 内串行；
 * 3. **惰性装载**：`ensureLoaded` 单次全目录扫描（角色数 ≤ 二十量级，
 *    单卷 ≤ [MAX_PER_ROLE] 条，扫描开销可忽略）。
 *
 * ## 崩溃安全与防御式 IO
 *
 * - **原子写**：tmp → flush → fsync → rename（同分区原子）；
 * - **读侧**：损坏卷跳过 + warn（一条坏卷不该瘫痪全部角色的经验）；
 * - **写侧**：IOException/序列化失败 → warn + 不上抛（内存缓存已更新，
 *    下次全量重写自然追平 —— 经验是「尽力持久」的增强数据）；
 * - **路径安全**：roleId 白名单 `[A-Za-z0-9_-]`（内置 `builtin_coding_*`
 *    与自定义 `role_<ts>` 天然满足），`../` 穿越在 [safeFileName] 拒绝。
 *
 * ## 有界性（存储不无限膨胀）
 *
 * [append] 即时裁剪：超过 [MAX_PER_ROLE] 条时按「强化分最低优先淘汰」
 * 排序（hitCount 升序 → createdAt 升序）丢最弱 —— 被反复验证的经验
 * 留得最久。启动侧不再另跑 prune（append 即时裁剪已封顶单卷体积，
 * 与 LongTaskStore 的启动 prune 语义不同：那边记录天然更大、这边单卡
 * ≤ 1KB 量级）。
 */
class RoleExperienceStore(private val baseDir: File) {

    private val json = Json {
        ignoreUnknownKeys = true   // 旧卷读到未来字段不炸（前向兼容）
        encodeDefaults = true      // 默认值显式落盘（diff 友好）
    }

    /** 写路径串行锁（读改写的原子性保证）。 */
    private val mutex = Mutex()

    /** 内存缓存：角色 id → 卷快照（写时复制整体替换发布）。 */
    @Volatile
    private var cache: Map<String, RoleExperienceBook> = emptyMap()

    /** 惰性装载的监视器锁 + 完成旗标。 */
    private val loadLock = Any()

    @Volatile
    private var loaded = false

    init {
        // 构造即建目录（幂等）：DI 在 Application 级构造本类；失败不抛
        // （只读场景照样可用，首次写盘时 writeAtomic 会再 warn）。
        runCatching { baseDir.mkdirs() }
    }

    // ═══════════════════════ 写 ═══════════════════════

    /**
     * 追加一条经验（即时有界裁剪，见类 KDoc）。
     *
     * @return 裁剪后的卷（供 Tracker 日志/测试断言）；IO 失败不抛（内存
     *   已更新，见防御式 IO 口径）。
     * @throws IllegalArgumentException roleId 非法（路径穿越防御，fail-fast
     *   在任何内存变更之前 —— 不会留下半套状态）。
     */
    suspend fun append(experience: RoleExperience): List<RoleExperience> {
        require(isValidRoleId(experience.roleId)) {
            "illegal role id for experience book: ${experience.roleId}"
        }
        return mutex.withLock {
            ensureLoaded()
            val roleId = experience.roleId
            val current = cache[roleId] ?: RoleExperienceBook(roleId = roleId)
            // 时间序追加（新经验在尾）；裁剪丢「最弱」（强化分最低且最旧）。
            val merged = (current.experiences + experience)
                .dropWeakest(keep = MAX_PER_ROLE)
            val book = current.copy(experiences = merged)
            publish(roleId, book)
            writeAtomic(roleId, book)
            merged
        }
    }

    /** 删除一条经验（未知 id 静默幂等）。 */
    suspend fun delete(roleId: String, experienceId: String) = mutex.withLock {
        ensureLoaded()
        val current = cache[roleId] ?: return@withLock
        if (current.experiences.none { it.id == experienceId }) return@withLock
        val book = current.copy(experiences = current.experiences.filterNot { it.id == experienceId })
        publish(roleId, book)
        writeAtomic(roleId, book)
    }

    /** 清空一卷（模板工坊「清空经验」入口）。 */
    suspend fun clear(roleId: String) = mutex.withLock {
        ensureLoaded()
        val current = cache[roleId] ?: return@withLock
        val book = current.copy(experiences = emptyList())
        publish(roleId, book)
        writeAtomic(roleId, book)
    }

    /**
     * 强化信号回写：被召回注入过的经验 hitCount+1、lastUsedAt=now。
     *
     * 单写者纪律：由召回注入方（Controller）在注入成功后调用；批量接口
     * 一次落盘一卷（同卷多经验一起 bump，IO 放大 1 次而非 N 次）。
     */
    suspend fun bumpHits(roleId: String, experienceIds: List<String>, now: Long) = mutex.withLock {
        ensureLoaded()
        val current = cache[roleId] ?: return@withLock
        val ids = experienceIds.toSet()
        if (current.experiences.none { it.id in ids }) return@withLock
        val book = current.copy(
            experiences = current.experiences.map { exp ->
                if (exp.id in ids) exp.copy(hitCount = exp.hitCount + 1, lastUsedAt = now)
                else exp
            }
        )
        publish(roleId, book)
        writeAtomic(roleId, book)
    }

    // ═══════════════════════ 读 ═══════════════════════

    /** 按角色取经验列表（时间序，新在尾；未装载角色 → 空）。 */
    suspend fun list(roleId: String): List<RoleExperience> {
        ensureLoaded()
        return cache[roleId]?.experiences.orEmpty()
    }

    /**
     * 主线程同步快照（LongTaskStore.snapshotBlocking 同款契约）：
     * 首次调用做一次小目录扫描，之后纯内存读 —— **绝不含 runBlocking**。
     * 召回注入（runEngine 发送路径）与模板工坊 UI 均可安全调用。
     */
    fun snapshotBlocking(roleId: String): List<RoleExperience> {
        ensureLoaded()
        return cache[roleId]?.experiences.orEmpty()
    }

    /** 全部角色 id（诊断/迁移用；不含空卷）。 */
    fun roleIdsBlocking(): List<String> {
        ensureLoaded()
        return cache.values.map { it.roleId }.sorted()
    }

    // ═══════════════════════ 内部机制 ═══════════════════════

    /** 发布新快照到内存缓存（锁内调用）。 */
    private fun publish(roleId: String, book: RoleExperienceBook) {
        val next = cache.toMutableMap()
        if (book.experiences.isEmpty()) next.remove(roleId) else next[roleId] = book
        cache = next
    }

    /**
     * 惰性装载（双检锁：loaded 翻转后永不再扫）。
     *
     * 坏卷隔离：单卷 JSON 损坏 → 跳过 + warn，其余卷照常（读侧防御式
     * IO 口径）。`.tmp` 崩溃残留直接无视（视为不存在）。
     */
    private fun ensureLoaded() {
        if (loaded) return
        synchronized(loadLock) {
            if (loaded) return
            runCatching {
                val files = baseDir.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray()
                val loadedBooks = HashMap<String, RoleExperienceBook>(files.size)
                for (file in files) {
                    val roleId = file.name.removeSuffix(".json")
                    if (!isValidRoleId(roleId)) continue
                    runCatching {
                        val text = file.readText()
                        if (text.isBlank()) return@runCatching
                        val book = json.decodeFromString<RoleExperienceBook>(text)
                        // 卷主与文件名不一致（手工改名/坏数据）→ 以文件名为准
                        // 重建卷主（经验卡体内的 roleId 也一并对齐，防串卷）。
                        if (book.roleId != roleId) {
                            loadedBooks[roleId] = book.copy(
                                roleId = roleId,
                                experiences = book.experiences.map { it.copy(roleId = roleId) }
                            )
                        } else {
                            loadedBooks[roleId] = book
                        }
                    }.onFailure {
                        AppLogger.instance.warn(
                            LogCategory.ENGINE, TAG,
                            "经验卷装载失败（跳过该卷）：${file.name} — ${it.message}"
                        )
                    }
                }
                cache = loadedBooks
            }.onFailure {
                AppLogger.instance.warn(
                    LogCategory.ENGINE, TAG,
                    "经验目录扫描失败（以空集起步）：${it.message}"
                )
            }
            loaded = true
        }
    }

    /**
     * 原子写一卷：tmp → flush → fsync → rename；rename 失败回退
     * copyTo + delete；仍失败 warn 不上抛（内存缓存已发布，下次重写追平）。
     */
    private fun writeAtomic(roleId: String, book: RoleExperienceBook) {
        val target = File(baseDir, safeFileName(roleId))
        val tmp = File(baseDir, "$roleId.json.tmp")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(json.encodeToString(RoleExperienceBook.serializer(), book).toByteArray())
                out.flush()
                out.fd.sync()
            }
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        } catch (e: IOException) {
            tmp.delete()
            AppLogger.instance.warn(
                LogCategory.ENGINE, TAG,
                "经验卷写盘失败（内存已更新，下次重写追平）：$roleId — ${e.message}"
            )
        }
    }

    companion object {
        const val TAG = "RoleExperienceStore"

        /** 单卷经验上限（有界存储：淘汰最弱，见类 KDoc）。 */
        const val MAX_PER_ROLE = 120

        /** roleId 白名单（内置/自定义 id 天然满足；拒绝路径穿越）。 */
        val ROLE_ID_PATTERN = Regex("^[A-Za-z0-9_-]+$")

        fun isValidRoleId(roleId: String): Boolean = roleId.matches(ROLE_ID_PATTERN)
    }

    /** 非法 roleId 在写路径 fail-fast（我方代码 bug），读路径静默空集。 */
    private fun safeFileName(roleId: String): String {
        require(isValidRoleId(roleId)) { "illegal role id for experience book: $roleId" }
        return "$roleId.json"
    }
}

/** 有界裁剪：超 keep 条时按强化分（hitCount 升序 → createdAt 升序）丢最弱。 */
internal fun List<RoleExperience>.dropWeakest(keep: Int): List<RoleExperience> {
    if (size <= keep) return this
    // 排序键：(hitCount, createdAt) 升序 = 最弱在前；drop 掉最弱 size-keep 条。
    // 注意保持原时间序返回（排序只用于挑选淘汰者，不改变留存序）。
    val evictCount = size - keep
    val weakest = asSequence()
        .sortedWith(compareBy({ it.hitCount }, { it.createdAt }))
        .take(evictCount)
        .map { it.id }
        .toSet()
    return filterNot { it.id in weakest }
}
