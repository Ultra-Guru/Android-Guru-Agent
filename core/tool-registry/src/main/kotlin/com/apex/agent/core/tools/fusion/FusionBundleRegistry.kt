package com.apex.agent.core.tools.fusion

import com.apex.agent.core.tools.builtin.AtomicFileWrite
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File

/**
 * # FusionBundle 注册表
 *
 * 存储与生命周期管理：`filesDir/fusion-bundles/<id>.json` 一包一文件。
 * 模式与 [com.apex.agent.core.tools.skill.SkillRegistry] 同源：
 * - synchronized 单锁 + LinkedHashMap 内存快照（id → manifest）；
 * - 安装/卸载后 `changes` SharedFlow 广播（UI 与聚合数据源自动刷新）；
 * - id 路径安全校验（防穿越落盘）；
 * - 原子写（tmp + rename，复用 [AtomicFileWrite]）；
 * - bundled 预装保护（不可卸载，版本升级幂等替换）。
 *
 * 注册表**只管包本身的持久化**；包内技能/MCP 的安装连接由
 * [FusionBundleActivator] 编排（职责分离 —— 注册表是仓库，激活器是调度）。
 */
class FusionBundleRegistry(
    private val bundlesDir: File,
    private val logger: FusionLogSink = FusionLogSink { _, _ -> }
) {
    private val lock = Any()
    private val installed = LinkedHashMap<String, FusionBundleManifest>()
    private val installedAt = HashMap<String, Long>()

    /** 安装/卸载变更通知（订阅者：UI 控制器、FusionList 工具快照）。 */
    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
    val changes: SharedFlow<Unit> = _changes.asSharedFlow()

    init {
        runCatching { bundlesDir.mkdirs() }
        loadFromDisk()
    }

    /** 磁盘热对账：扫描目录重建快照（外部的删除/手放文件都能被吸收）。 */
    private fun loadFromDisk() {
        val files = bundlesDir.listFiles { f -> f.extension == "json" } ?: return
        for (file in files) {
            val manifest = runCatching {
                FusionBundleManifest.parse(file.readText())
            }.getOrNull() ?: run {
                logger.warn(TAG, "跳过非法包文件 ${file.name}（解析或校验失败）")
                continue
            }
            installed[manifest.id] = manifest
            installedAt[manifest.id] = file.lastModified()
        }
    }

    /**
     * 安装包（从 manifest JSON 字符串）。校验失败返回失败 Result（不落盘）。
     * 已有同 id 且版本相同 → 幂等成功（返回既有 manifest）；版本更新 → 覆盖。
     */
    fun install(manifestJson: String): Result<FusionBundleManifest> {
        val result = synchronized(lock) {
            try {
                val manifest = FusionBundleManifest.parse(manifestJson)
                    ?: return Result.failure(
                        Exception("包 manifest 非法：schema 必须为 fusion-bundle-v1 且 id/技能引用/MCP 引用校验通过")
                    )
                val existing = installed[manifest.id]
                if (existing != null && existing.version == manifest.version) {
                    return Result.success(existing) // 幂等
                }
                val bundleFile = File(bundlesDir, "${manifest.id}.json")
                AtomicFileWrite.writeText(bundleFile, manifestJson)
                installed[manifest.id] = manifest
                installedAt[manifest.id] = System.currentTimeMillis()
                Result.success(manifest)
            } catch (e: Exception) {
                Result.failure(Exception("FusionBundle 安装失败: ${e.message}"))
            }
        }
        if (result.isSuccess) notifyChanged()
        return result
    }

    /**
     * 内置预装（APK 随发）：幂等 + 版本守卫 —— 已有同版本跳过，升级替换并
     * 保留 bundled 标记。bundled=true 的包 [uninstall] 拒绝卸载。
     */
    fun installBundled(manifest: FusionBundleManifest, manifestJson: String): Result<Unit> {
        synchronized(lock) {
            val existing = installed[manifest.id]
            if (existing != null && existing.version == manifest.version &&
                existing.bundled
            ) {
                return Result.success(Unit)
            }
            val marked = if (manifest.bundled) manifest else manifest.copy(bundled = true)
            val file = File(bundlesDir, "${marked.id}.json")
            val jsonToWrite = if (manifest.bundled) manifestJson else {
                // 重新序列化带上 bundled 标记（源 JSON 忘写时兜底）
                FusionBundleManifest.json.encodeToString(
                    FusionBundleManifest.serializer(), marked
                )
            }
            AtomicFileWrite.writeText(file, jsonToWrite)
            installed[marked.id] = marked
            installedAt[marked.id] = System.currentTimeMillis()
        }
        notifyChanged()
        return Result.success(Unit)
    }

    /** 卸载：bundled 包返回 false（与 SkillRegistry 同防线）；成功广播变更。 */
    fun uninstall(bundleId: String): Boolean {
        synchronized(lock) {
            val manifest = installed[bundleId] ?: return false
            if (manifest.bundled) return false
            installed.remove(bundleId)
            installedAt.remove(bundleId)
            File(bundlesDir, "$bundleId.json").delete()
        }
        notifyChanged()
        return true
    }

    /** 快照查询（null = 未安装）。 */
    fun get(bundleId: String): FusionBundleManifest? = synchronized(lock) { installed[bundleId] }

    /** 全量快照（按名称排序，UI 稳定序）。 */
    fun list(): List<FusionBundleManifest> = synchronized(lock) {
        installed.values.sortedBy { it.name.lowercase() }
    }

    /** 目录摘要（FusionList 工具 + UI 列表行数据）。 */
    fun digests(): List<FusionBundleDigest> = list().map { m ->
        FusionBundleDigest(
            id = m.id,
            name = m.name,
            version = m.version,
            description = m.description,
            scope = m.scope,
            bundled = m.bundled,
            trustLevel = m.trustLevel,
            category = m.category,
            skillCount = m.skills.size,
            mcpServerCount = m.mcpServers.size,
            toolHintCount = m.toolActivationHints.size,
            engineCount = m.runtime.engines.size
        )
    }

    private fun notifyChanged() {
        _changes.tryEmit(Unit)
    }

    companion object {
        private const val TAG = "FusionBundleRegistry"
    }
}

/**
 * 注册表日志出口（core 不依赖 core:logging 的同款约定 —— 见
 * SkillHotReloadLogSink；app 层 DI 映射到 AppLogger，纯 JVM 单测静默）。
 */
fun interface FusionLogSink {
    fun warn(tag: String, message: String)
}
