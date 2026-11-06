package com.apex.agent.ui.screen.memory.profile

import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * # 用户画像存储 —— 单卷 JSON 持久化
 *
 * ## 磁盘布局
 *
 * ```
 * <baseDir>/            DI 注入（App: filesDir/user_profile）
 *   └── profile.json    全局唯一卷（ProfileBook）—— 画像是跨工作区的
 *         ↑ 写入中 .tmp（崩溃残留，装载视为不存在）
 * ```
 *
 * ## 与 RoleExperienceStore 同款的三层并发模型（刻意同构）
 *
 * 1. **内存缓存**：`@Volatile` 不可变快照 + 写时复制 —— 读侧
 *    （[snapshotBlocking]）永远不加锁不挂起（CodeViewModel 发送路径
 *    主线程安全调用）；
 * 2. **写串行锁**：读改写类操作在协程 Mutex 内串行；
 * 3. **惰性装载**：`ensureLoaded` 单次读卷。
 *
 * ## 崩溃安全与防御式 IO（对齐仓库口径）
 *
 * - **原子写**：tmp → flush → fsync → rename；rename 失败退化 copyTo；
 * - **读侧**：坏卷改名 .corrupt 留档 + warn，返回空账本（画像丢失
 *   可由再次蒸馏重建，不该瘫痪记忆页）；
 * - **写侧**：IOException / 序列化失败 → warn 不上抛（内存已更新，
 *    下次全量重写自然追平 —— 画像是「尽力持久」的增强数据）。
 *
 * ## 有界性
 *
 * 每维度上限 [MAX_PER_ASPECT] 条，总量上限 [MAX_TOTAL] —— [apply]
 * 与 [upsert] 即时裁剪：超限时按（置信度升序 → updatedAt 升序）丢
 * 最弱，被反复确信的高置信画像留得最久。
 */
class UserProfileStore(private val baseDir: File) {

    private val json = Json {
        ignoreUnknownKeys = true   // 旧卷读到未来字段不炸（前向兼容）
        encodeDefaults = true      // 默认值显式落盘（diff 友好）
    }

    /** 写路径串行锁。 */
    private val mutex = Mutex()

    /** 内存缓存：画像账本快照（写时复制整体替换发布）。 */
    @Volatile
    private var cache: ProfileBook = ProfileBook()

    /** 惰性装载监视器 + 完成旗标。 */
    private val loadLock = Any()

    @Volatile
    private var loaded = false

    init {
        runCatching { baseDir.mkdirs() }
    }

    // ═══ 写 ═══

    /**
     * 新增或编辑一条（id 命中 → 编辑覆盖；未命中 → 追加后即时有界裁剪）。
     *
     * @return 裁剪后的全量条目（供 VM 刷新 / 测试断言）；IO 失败不抛。
     */
    suspend fun upsert(item: UserProfileItem): List<UserProfileItem> = mutex.withLock {
        ensureLoaded()
        val current = cache.items
        val merged = current
            .filterNot { it.id == item.id }
            .let { it + item }
            .dropWeakestPerAspect()
        publish(merged)
        writeAtomic(merged)
        merged
    }

    /** 删除一条（未知 id 静默幂等）。 */
    suspend fun delete(itemId: String) = mutex.withLock {
        ensureLoaded()
        if (cache.items.none { it.id == itemId }) return@withLock
        val merged = cache.items.filterNot { it.id == itemId }
        publish(merged)
        writeAtomic(merged)
    }

    /** 清空全部画像（记忆页「清空」入口；UI 二次确认后调用）。 */
    suspend fun clear() = mutex.withLock {
        ensureLoaded()
        if (cache.items.isEmpty()) return@withLock
        publish(emptyList())
        writeAtomic(emptyList())
    }

    /**
     * 蒸馏动作落账（new / update 语义，ProfileDistiller 调用）。
     *
     * 门禁在此执行（代码侧钳制，不信任 LLM 输出形状）：
     *  - aspect 未知 / content 空 → 跳过计数；
     *  - update：old 与既有条目 content 逐字（trim 后）匹配才生效，
     *    找不到目标 → 跳过（宁可漏更不可错改）；
     *  - new：content 截断到 [MAX_CONTENT_CHARS]，confidence 钳制
     *    [MIN_CONFIDENCE]..[MAX_CONFIDENCE]，id 服务端不信任（重新生成）；
     *  - 单次动作总量上限 [MAX_ACTIONS_PER_DISTILL]。
     *
     * @return 落账报告（added / updated / skipped）
     */
    suspend fun apply(actions: List<DistillAction>, now: Long): DistillReport =
        mutex.withLock {
            ensureLoaded()
            var added = 0
            var updated = 0
            var skipped = 0
            var items = cache.items
            for (action in actions.take(MAX_ACTIONS_PER_DISTILL)) {
                val aspect = ProfileAspect.fromKey(action.aspect)
                val content = action.content.trim()
                if (aspect == null || content.isEmpty()) {
                    skipped++
                    continue
                }
                when (action.op.trim().lowercase()) {
                    "update" -> {
                        val target = action.old?.trim()
                        val index = items.indexOfFirst { it.content == target }
                        if (target.isNullOrEmpty() || index < 0) {
                            skipped++
                        } else {
                            items = items.mapIndexed { i, existing ->
                                if (i == index) existing.copy(
                                    aspect = aspect.key,
                                    content = content.take(MAX_CONTENT_CHARS),
                                    confidence = action.confidence.coerceIn(MIN_CONFIDENCE, MAX_CONFIDENCE),
                                    evidence = action.evidence?.trim()?.take(MAX_EVIDENCE_CHARS),
                                    updatedAt = now
                                ) else existing
                            }
                            updated++
                        }
                    }
                    else -> {
                        // new（未知 op 一律按 new 处理 —— 内容保住，只损失语义精度）
                        val confidence = action.confidence.coerceIn(MIN_CONFIDENCE, MAX_CONFIDENCE)
                        items = items + UserProfileItem(
                            id = "profile_${now}_${added}_${action.content.hashCode().toUInt()}",
                            aspect = aspect.key,
                            content = content.take(MAX_CONTENT_CHARS),
                            confidence = confidence,
                            evidence = action.evidence?.trim()?.take(MAX_EVIDENCE_CHARS),
                            source = UserProfileItem.SOURCE_DISTILLED,
                            createdAt = now,
                            updatedAt = now
                        )
                        added++
                    }
                }
            }
            val bounded = items.dropWeakestPerAspect()
            skipped += items.size - bounded.size
            publish(bounded)
            writeAtomic(bounded)
            DistillReport(added = added, updated = updated, skipped = skipped)
        }

    // ═══ 读 ═══

    /** 全量条目（维度展示分组用 [toProfileAspectEntries]）。 */
    suspend fun list(): List<UserProfileItem> {
        ensureLoaded()
        return cache.items
    }

    /**
     * 主线程同步快照（RoleExperienceStore.snapshotBlocking 同款契约）：
     * 首次调用读一次卷，之后纯内存读 —— 绝不含 runBlocking。
     * CodeViewModel 发送路径（画像注入）可安全调用。
     */
    fun snapshotBlocking(): List<UserProfileItem> {
        ensureLoaded()
        return cache.items
    }

    // ═══ 内部机制 ═══

    private fun publish(items: List<UserProfileItem>) {
        cache = ProfileBook(items = items)
    }

    /** 惰性装载（双检锁；坏卷隔离留档，以空账本起步）。 */
    private fun ensureLoaded() {
        if (loaded) return
        synchronized(loadLock) {
            if (loaded) return
            val file = File(baseDir, FILE_NAME)
            runCatching {
                val raw = file.readText(Charsets.UTF_8)
                if (raw.isNotBlank()) {
                    cache = json.decodeFromString(ProfileBook.serializer(), raw)
                }
            }.onFailure { e ->
                if (file.exists()) {
                    val corrupt = File(baseDir, "$FILE_NAME.corrupt")
                    runCatching { file.copyTo(corrupt, overwrite = true); file.delete() }
                }
                AppLogger.instance.warn(
                    LogCategory.ENGINE, TAG,
                    "画像卷装载失败（以空账本起步）：${e.message}"
                )
            }
            loaded = true
        }
    }

    /** 原子写全量（tmp → fsync → rename；失败 warn 不上抛）。 */
    private fun writeAtomic(items: List<UserProfileItem>) {
        val target = File(baseDir, FILE_NAME)
        val tmp = File(baseDir, "$FILE_NAME.tmp")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(json.encodeToString(
                    ProfileBook.serializer(), ProfileBook(items = items)
                ).toByteArray())
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
                "画像卷写盘失败（内存已更新，下次重写追平）：${e.message}"
            )
        } catch (e: SerializationException) {
            tmp.delete()
            AppLogger.instance.warn(
                LogCategory.ENGINE, TAG,
                "画像卷序列化失败（内存已更新）：${e.message}"
            )
        }
    }

    companion object {
        const val TAG = "UserProfileStore"
        private const val FILE_NAME = "profile.json"

        /** 每维度上限（有界存储）。 */
        const val MAX_PER_ASPECT = 12

        /** 总量上限（五维 × 每维上限的收敛值）。 */
        const val MAX_TOTAL = 60

        /** 单条 content 上限（蒸馏门禁截断线）。 */
        const val MAX_CONTENT_CHARS = 60

        /** evidence 摘要上限。 */
        const val MAX_EVIDENCE_CHARS = 24

        /** 置信度钳制区间。 */
        const val MIN_CONFIDENCE = 0.3f
        const val MAX_CONFIDENCE = 1.0f

        /** 单次蒸馏动作总量上限（与提示词声明的 8 对齐，代码侧再封顶）。 */
        const val MAX_ACTIONS_PER_DISTILL = 8
    }
}

/** 卷形状（version 字段留给未来迁移，当前恒 1）。 */
@Serializable
data class ProfileBook(
    val version: Int = 1,
    val items: List<UserProfileItem> = emptyList()
)

/**
 * 有界裁剪：总量超 [UserProfileStore.MAX_TOTAL] 丢全局最弱；单维度超
 * [UserProfileStore.MAX_PER_ASPECT] 丢该维度最弱。「最弱」= 置信度升序
 * → updatedAt 升序（低置信且最旧的先走）。返回保持原相对序。
 */
internal fun List<UserProfileItem>.dropWeakestPerAspect(): List<UserProfileItem> {
    var result = this
    if (result.size > UserProfileStore.MAX_TOTAL) {
        val weakest = result.asSequence()
            .sortedWith(compareBy({ it.confidence }, { it.updatedAt }))
            .take(result.size - UserProfileStore.MAX_TOTAL)
            .map { it.id }
            .toSet()
        result = result.filterNot { it.id in weakest }
    }
    ProfileAspect.entries.forEach { aspect ->
        val ofAspect = result.filter { ProfileAspect.fromKey(it.aspect) == aspect }
        if (ofAspect.size > UserProfileStore.MAX_PER_ASPECT) {
            val evict = ofAspect.asSequence()
                .sortedWith(compareBy({ it.confidence }, { it.updatedAt }))
                .take(ofAspect.size - UserProfileStore.MAX_PER_ASPECT)
                .map { it.id }
                .toSet()
            result = result.filterNot { it.id in evict }
        }
    }
    return result
}
