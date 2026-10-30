package com.apex.agent.platform.jiguan

import com.apex.agent.core.jiguan.AppKnowledge
import com.apex.agent.core.jiguan.JiguanKnowledgeStore
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * # 知识文件仓（Universal Memory RAG 的持久层）
 *
 * `filesDir/jiguan/knowledge.json`：JSON 数组文档（无 @Serializable —— 与
 * core 引擎的零编译器插件纪律对齐，手工编解码）。原子写纪律：tmp +
 * renameTo，rename 失败直写目标兜底（仓库 AGENTS.md 惯例）。单写者互斥。
 */
class KnowledgeFileStore(
    private val file: File,
) : JiguanKnowledgeStore {

    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun all(): List<AppKnowledge> = withContext(Dispatchers.IO) {
        mutex.withLock { readAllLocked() }
    }

    override suspend fun save(entry: AppKnowledge) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = readAllLocked().toMutableList()
            current.removeAll { it.appPackage == entry.appPackage && it.title == entry.title }
            current += entry
            writeAllLocked(current)
        }
    }

    override suspend fun delete(appPackage: String, title: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = readAllLocked()
            val next = current.filterNot { it.appPackage == appPackage && it.title == title }
            if (next.size != current.size) writeAllLocked(next)
        }
    }

    // ── 编解码 ───────────────────────────────────────────────────

    private fun readAllLocked(): List<AppKnowledge> {
        if (!file.isFile) return emptyList()
        return runCatching {
            val array = json.parseToJsonElement(file.readText()) as? JsonArray ?: return emptyList()
            array.mapNotNull { element ->
                val obj = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
                AppKnowledge(
                    appPackage = obj.str("package") ?: return@mapNotNull null,
                    title = obj.str("title") ?: return@mapNotNull null,
                    category = obj.str("category") ?: "recipe",
                    content = obj.str("content") ?: "",
                    keywords = obj.str("keywords") ?: "",
                    successCount = obj.num("successCount")?.toInt() ?: 0,
                    createdAt = obj.num("createdAt") ?: 0L,
                    updatedAt = obj.num("updatedAt") ?: 0L,
                )
            }
        }.getOrElse {
            AppLogger.instance.warn(LogCategory.SYSTEM, TAG, "知识仓读取失败（按空处理）: ${it.message}")
            emptyList()
        }
    }

    private fun writeAllLocked(entries: List<AppKnowledge>) {
        runCatching {
            file.parentFile?.mkdirs()
            val array = JsonArray(entries.map { entry ->
                JsonObject(
                    buildMap {
                        put("package", JsonPrimitive(entry.appPackage))
                        put("title", JsonPrimitive(entry.title))
                        put("category", JsonPrimitive(entry.category))
                        put("content", JsonPrimitive(entry.content))
                        put("keywords", JsonPrimitive(entry.keywords))
                        put("successCount", JsonPrimitive(entry.successCount))
                        put("createdAt", JsonPrimitive(entry.createdAt))
                        put("updatedAt", JsonPrimitive(entry.updatedAt))
                    },
                )
            })
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(array.toString())
            if (!tmp.renameTo(file)) {
                // rename 失败兜底：直写目标（原子性降级但数据不丢）。
                file.writeText(array.toString())
                runCatching { tmp.delete() }
            }
        }.onFailure {
            AppLogger.instance.warn(LogCategory.SYSTEM, TAG, "知识仓写入失败: ${it.message}")
        }
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.num(key: String): Long? =
        (this[key] as? JsonPrimitive)?.longOrNull

    private companion object {
        const val TAG = "JiguanKB"
    }
}
