package com.apex.agent.platform.memory.migrate

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import com.apex.memkit.core.MemKit
import com.apex.memkit.core.model.ChatFact
import com.apex.memkit.core.model.ChatTurn
import com.apex.memkit.core.model.EpisodeRecord
import com.apex.memkit.core.model.EpisodeStatus
import com.apex.memkit.core.model.FingerprintAlias
import com.apex.memkit.core.model.GraphEntity
import com.apex.memkit.core.model.GraphRelation
import com.apex.memkit.core.model.MacroAction
import com.apex.memkit.core.model.MacroSkill
import com.apex.memkit.core.model.MacroStep
import com.apex.memkit.core.model.NodeRole
import com.apex.memkit.core.store.NodeDictEntry
import com.apex.memkit.core.store.addGraphRelations
import com.apex.memkit.core.store.enqueueTurn
import com.apex.memkit.core.store.replaceAllFacts
import com.apex.memkit.core.store.upsertGraphEntities
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.contentOrNull

/**
 * 旧记忆存储 → apex-memory-kit 的一次性搬迁器。
 *
 * 三个旧源（存在才搬，搬完改名 `.imported` 留档防重入）：
 * 1. Room `cs_mem_graph.db`（旧 cs-mem：episodes / nodes / fsm_macros /
 *    migration_map —— 边表不搬：新模型按指纹集合存情景，空间边派生不持久化）；
 * 2. `<filesDir>/mcp_memory/memory.json`（旧知识图谱 → 新 graph.json 同构直迁）；
 * 3. `<filesDir>/chat_memory/ledger.json`（旧聊天账本 → 新 chat.json；待蒸馏
 *    队列一并保留 —— 进程切换不丢轮次）。
 *
 * 旧 FSM 宏的 `actionParams`（JSON 形态 `{"x":100,"y":200}` / `{"text":"…"}`）
 * 解析为类型化 [MacroAction]；任何一条转换失败即整宏丢弃（不可回放的宏
 * 没有迁移价值，日志留痕）。旧宏入口是单指纹，按原语义迁为单元素入口
 * 集合（新蒸馏的宏才有集合入口）。
 *
 * 幂等：meta 标志位 `legacyImported` 守门；坏库 / 坏文件折叠为跳过该源。
 */
@Singleton
class LegacyCsMemMigrator @Inject constructor(
    private val memKit: MemKit,
    @ApplicationContext private val context: Context,
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    data class Report(
        var episodes: Int = 0,
        var nodes: Int = 0,
        var macros: Int = 0,
        var macrosDropped: Int = 0,
        var aliases: Int = 0,
        var graphEntities: Int = 0,
        var graphRelations: Int = 0,
        var chatFacts: Int = 0,
        var chatQueueTurns: Int = 0,
    ) {
        fun describe(): String = buildString {
            append("情景 $episodes / 节点 $nodes / 宏 $macros（弃 $macrosDropped）/ 别名 $aliases / ")
            append("图谱实体 $graphEntities / 关系 $graphRelations / 聊天事实 $chatFacts / 队列 $chatQueueTurns")
        }
    }

    /** 执行一次性迁移（幂等；App 启动后台线程调用）。 */
    suspend fun migrateIfNeeded(): Report {
        val report = Report()
        if (memKit.store.metaFlag(FLAG) != null) return report
        runCatching { migrateLegacyRoomDb(report) }
            .onFailure { AppLogger.instance.warn(LogCategory.CS_MEM, TAG, "旧 cs-mem Room 库迁移失败（跳过该源）：${it.message}") }
        runCatching { migrateLegacyGraph(report) }
            .onFailure { AppLogger.instance.warn(LogCategory.CS_MEM, TAG, "旧知识图谱迁移失败（跳过该源）：${it.message}") }
        runCatching { migrateLegacyLedger(report) }
            .onFailure { AppLogger.instance.warn(LogCategory.CS_MEM, TAG, "旧聊天账本迁移失败（跳过该源）：${it.message}") }
        memKit.store.setMetaFlag(FLAG, "done")
        memKit.requestFlush()
        AppLogger.instance.info(LogCategory.CS_MEM, TAG, "旧记忆迁移完成：${report.describe()}")
        return report
    }

    // ── 源 1：旧 Room cs_mem_graph.db ═══════════════════════════════

    private suspend fun migrateLegacyRoomDb(report: Report) {
        val dbFile = File(context.filesDir, "databases/cs_mem_graph.db").takeIf { it.isFile }
            ?: File(context.filesDir, "../databases/cs_mem_graph.db").takeIf { it.isFile }
            ?: return
        val db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            migrateEpisodes(db, report)
            migrateNodes(db, report)
            migrateMacros(db, report)
            migrateAliases(db, report)
        } finally {
            runCatching { db.close() }
            runCatching { dbFile.renameTo(File(dbFile.parentFile, dbFile.name + ".imported")) }
        }
    }

    private suspend fun migrateEpisodes(db: SQLiteDatabase, report: Report) {
        val rows = query(db, "SELECT episode_id, goal, app_package, app_version, status, started_at, finished_at, total_actions FROM episodes")
        for (r in rows) {
            val id = r[0] as? String ?: continue
            memKit.store.addEpisode(
                EpisodeRecord(
                    episodeId = id,
                    goal = r[1] as? String ?: "(未知目标)",
                    appPackage = r[2] as? String,
                    appVersion = r[3] as? String,
                    status = EpisodeStatus.safe(r[4] as? String),
                    startedAt = r[6] as? Long ?: 0L,
                    finishedAt = null,
                    actionCount = (r[7] as? Long)?.toInt() ?: 0,
                    manual = id.startsWith("manual_"),
                ),
            )
            report.episodes++
        }
    }

    private suspend fun migrateNodes(db: SQLiteDatabase, report: Report) {
        val rows = query(
            db,
            "SELECT fingerprint, role, text_hint, resource_id, class_name, app_package, app_version, " +
                "first_seen_at, last_seen_at, occurrence_count, energy FROM nodes",
        )
        val entries = rows.mapNotNull { r ->
            val fp = r[0] as? String ?: return@mapNotNull null
            NodeDictEntry(
                fingerprint = fp,
                role = NodeRole.safe(r[1] as? String),
                text = r[2] as? String,
                resourceId = r[3] as? String,
                className = r[4] as? String,
                appPackage = r[5] as? String,
                appVersion = r[6] as? String,
                firstSeenAt = r[7] as? Long ?: 0L,
                lastSeenAt = r[8] as? Long ?: 0L,
                occurrences = (r[9] as? Long)?.toInt() ?: 1,
                energy = (r[10] as? Double)?.toFloat() ?: 1.0f,
            )
        }
        memKit.store.importNodes(entries)
        report.nodes = entries.size
    }

    private suspend fun migrateMacros(db: SQLiteDatabase, report: Report) {
        val rows = query(
            db,
            "SELECT skill_id, name, goal, app_package, initial_fingerprint, terminal_fingerprint, transitions_json, " +
                "source_episode_id, success_count, failure_count, is_crystallized, last_executed_at, energy FROM fsm_macros",
        )
        for (r in rows) {
            val skillId = r[0] as? String ?: continue
            val initial = r[4] as? String
            val terminal = r[5] as? String
            val transitionsJson = r[6] as? String
            if (initial.isNullOrBlank() || terminal.isNullOrBlank() || transitionsJson.isNullOrBlank()) continue
            val steps = parseLegacyTransitions(transitionsJson)
            if (steps.isNullOrEmpty()) {
                report.macrosDropped++
                AppLogger.instance.debug(LogCategory.CS_MEM, TAG, "旧宏 $skillId 动作无法类型化 —— 丢弃")
                continue
            }
            memKit.store.saveMacro(
                MacroSkill(
                    skillId = skillId,
                    name = r[1] as? String ?: skillId,
                    goal = r[2] as? String ?: "",
                    appPackage = r[3] as? String,
                    entryFingerprints = setOf(initial),
                    exitFingerprint = terminal,
                    steps = steps,
                    stats = com.apex.memkit.core.model.MacroStats(
                        successCount = (r[8] as? Long)?.toInt() ?: 0,
                        failureCount = (r[9] as? Long)?.toInt() ?: 0,
                        energy = ((r[12] as? Double)?.toFloat() ?: 1.0f).coerceIn(0f, 10f),
                        crystallized = (r[10] as? Long) == 1L,
                        lastExecutedAt = r[11] as? Long ?: 0L,
                    ),
                    sourceEpisodeId = r[7] as? String,
                    createdAt = 0L,
                ),
            )
            report.macros++
        }
    }

    // 解析器住在 companion object（纯函数静态位，单测直调零构造）。

    private suspend fun migrateAliases(db: SQLiteDatabase, report: Report) {
        val rows = query(
            db,
            "SELECT old_fingerprint, new_fingerprint, match_score, from_version, to_version, created_at FROM migration_map",
        )
        for (r in rows) {
            val old = r[0] as? String ?: continue
            val new = r[1] as? String ?: continue
            memKit.store.addAlias(
                FingerprintAlias(
                    oldFingerprint = old,
                    newFingerprint = new,
                    score = ((r[2] as? Double)?.toFloat() ?: 0.7f).coerceIn(0f, 1f),
                    fromVersion = r[3] as? String,
                    toVersion = r[4] as? String,
                    createdAt = r[5] as? Long ?: 0L,
                ),
            )
            report.aliases++
        }
    }

    private fun query(db: SQLiteDatabase, sql: String): List<Array<Any?>> = runCatching {
        val out = ArrayList<Array<Any?>>()
        db.rawQuery(sql, null).use { cursor ->
            val cols = cursor.columnCount
            while (cursor.moveToNext()) {
                out.add(Array<Any?>(cols) { i ->
                    when (cursor.getType(i)) {
                        Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(i)
                        Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(i)
                        Cursor.FIELD_TYPE_STRING -> cursor.getString(i)
                        Cursor.FIELD_TYPE_BLOB -> null
                        else -> null
                    }
                })
            }
        }
        out
    }.getOrDefault(emptyList())

    // ── 源 2：旧知识图谱（<filesDir>/mcp_memory/memory.json）══════════

    private suspend fun migrateLegacyGraph(report: Report) {
        val file = File(context.filesDir, "mcp_memory/memory.json")
        if (!file.isFile) return
        val text = file.readText()
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: run {
            runCatching { file.renameTo(File(file.parentFile, "memory.json.corrupt")) }
            return
        }
        val entities = runCatching {
            root["entities"]?.jsonArray?.map { el ->
                val o = el.jsonObject
                GraphEntity(
                    name = o["name"]?.jsonPrimitive?.contentOrNull ?: return@map null,
                    entityType = o["entityType"]?.jsonPrimitive?.contentOrNull ?: "",
                    observations = o["observations"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
                )
            }?.filterNotNull() ?: emptyList()
        }.getOrDefault(emptyList())
        val relations = runCatching {
            root["relations"]?.jsonArray?.map { el ->
                val o = el.jsonObject
                GraphRelation(
                    from = o["from"]?.jsonPrimitive?.contentOrNull ?: return@map null,
                    to = o["to"]?.jsonPrimitive?.contentOrNull ?: return@map null,
                    relationType = o["relationType"]?.jsonPrimitive?.contentOrNull ?: "",
                )
            }?.filterNotNull() ?: emptyList()
        }.getOrDefault(emptyList())
        memKit.store.upsertGraphEntities(entities)
        memKit.store.addGraphRelations(relations)
        report.graphEntities = entities.size
        report.graphRelations = relations.size
        runCatching { file.renameTo(File(file.parentFile, "memory.json.imported")) }
    }

    // ── 源 3：旧聊天账本（<filesDir>/chat_memory/ledger.json）════════

    private suspend fun migrateLegacyLedger(report: Report) {
        val file = File(context.filesDir, "chat_memory/ledger.json")
        if (!file.isFile) return
        val root = runCatching { json.parseToJsonElement(file.readText()).jsonObject }.getOrNull() ?: run {
            runCatching { file.renameTo(File(file.parentFile, "ledger.json.corrupt")) }
            return
        }
        val records = runCatching {
            root["records"]?.jsonArray?.map { el ->
                val o = el.jsonObject
                val kind = ChatFact.Kind.safe(o["kind"]?.jsonPrimitive?.contentOrNull)
                val content = o["content"]?.jsonPrimitive?.contentOrNull ?: return@map null
                ChatFact(
                    id = o["id"]?.jsonPrimitive?.contentOrNull ?: "",
                    content = content,
                    kind = kind,
                    source = ChatFact.Source.safe(o["source"]?.jsonPrimitive?.contentOrNull),
                    importance = o["importance"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 0.5f,
                    credibility = o["credibility"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 1f,
                    createdAt = o["createdAt"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
                    lastAccessedAt = o["lastAccessedAt"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
                    accessCount = o["accessCount"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
                )
            }?.filterNotNull() ?: emptyList()
        }.getOrDefault(emptyList())
        if (records.isNotEmpty()) {
            memKit.store.replaceAllFacts(records.filter { it.id.isNotBlank() })
            report.chatFacts = records.size
        }
        val pending = runCatching {
            root["pendingTurns"]?.jsonArray?.map { el ->
                val o = el.jsonObject
                ChatTurn(
                    userText = o["userText"]?.jsonPrimitive?.contentOrNull ?: "",
                    assistantText = o["assistantText"]?.jsonPrimitive?.contentOrNull ?: "",
                    at = o["at"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
                )
            }?.filter { it.userText.isNotBlank() } ?: emptyList()
        }.getOrDefault(emptyList())
        pending.forEach { memKit.store.enqueueTurn(it) }
        report.chatQueueTurns = pending.size
        runCatching { file.renameTo(File(file.parentFile, "ledger.json.imported")) }
    }

    companion object {
        private const val TAG = "LegacyMigrator"
        private const val FLAG = "legacyImported"

        /** 解析器自持 Json（与实例字段同配置；纯函数路径，无实例状态）。 */
        private val parseJson = Json { ignoreUnknownKeys = true; isLenient = true }

        /**
         * 旧 transitions_json：[{fromState, actionType, actionParams(JSON), toState}]
         * → 类型化步骤。纯函数（companion 静态位，单测直调零构造）。任何一条
         * 转换失败即整宏丢弃（null）—— 不允许半迁移。
         */
        internal fun parseLegacyTransitions(transitionsJson: String): List<MacroStep>? {
            val arr = runCatching { parseJson.parseToJsonElement(transitionsJson).jsonArray }.getOrNull() ?: return null
            val steps = ArrayList<MacroStep>(arr.size)
            for (element in arr) {
                val obj = runCatching { element.jsonObject }.getOrNull() ?: return null
                val from = obj["fromState"]?.jsonPrimitive?.contentOrNull ?: return null
                val to = obj["toState"]?.jsonPrimitive?.contentOrNull ?: return null
                val type = obj["actionType"]?.jsonPrimitive?.contentOrNull ?: return null
                val params = obj["actionParams"]?.jsonPrimitive?.contentOrNull ?: "{}"
                val action = parseLegacyAction(type, params) ?: return null
                steps.add(
                    MacroStep(
                        action = action,
                        anchorFingerprints = setOf(from),
                        nextAnchorFingerprints = setOf(to),
                    ),
                )
            }
            return steps
        }

        private fun parseLegacyAction(type: String, params: String): MacroAction? = runCatching {
            val p = parseJson.parseToJsonElement(params).jsonObject
            when (type.lowercase()) {
                "click", "tap", "uitap" -> MacroAction.Tap(p["x"]?.jsonPrimitive?.int ?: return null, p["y"]?.jsonPrimitive?.int ?: return null)
                "swipe", "uiswipe" -> MacroAction.Swipe(
                    p["x1"]?.jsonPrimitive?.int ?: return null,
                    p["y1"]?.jsonPrimitive?.int ?: return null,
                    p["x2"]?.jsonPrimitive?.int ?: return null,
                    p["y2"]?.jsonPrimitive?.int ?: return null,
                    p["duration"]?.jsonPrimitive?.long ?: 300L,
                )

                "inputtext", "input" -> MacroAction.Input(p["text"]?.jsonPrimitive?.contentOrNull ?: return null)
                "back" -> MacroAction.Back
                "home" -> MacroAction.Home
                else -> null
            }
        }.getOrNull()
    }
}
