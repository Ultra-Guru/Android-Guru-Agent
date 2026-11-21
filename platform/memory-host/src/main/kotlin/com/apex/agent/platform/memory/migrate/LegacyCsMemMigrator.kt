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
 * 旧记忆存储 → apex-kit-memory 的一次性搬迁器。
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
 *
 * #821 列序契约：行取值一律**按列名**（[QueryRow.byName]），不再依赖
 * SELECT 列序与数组下标对齐 —— 旧实现的 `r[5]/r[6]` 错位一列把
 * finished_at 读进 startedAt、真实 started_at 从未被读取，迁移后时间轴
 * 错乱。各源的列名清单以伴营常量（如 [EPISODE_COLUMNS]）钉死，契约
 * 测试直接锁「列名 → 字段」映射，列序变更不再静默错位。
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
        val rows = query(db, "SELECT ${EPISODE_COLUMNS.joinToString()} FROM episodes")
        for (r in rows) {
            val id = r.byName("episode_id") as? String ?: continue
            memKit.store.addEpisode(
                episodeRecord(id, r),
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
            val fp = r.byName("fingerprint") as? String ?: return@mapNotNull null
            NodeDictEntry(
                fingerprint = fp,
                role = NodeRole.safe(r.byName("role") as? String),
                text = r.byName("text_hint") as? String,
                resourceId = r.byName("resource_id") as? String,
                className = r.byName("class_name") as? String,
                appPackage = r.byName("app_package") as? String,
                appVersion = r.byName("app_version") as? String,
                firstSeenAt = r.byName("first_seen_at") as? Long ?: 0L,
                lastSeenAt = r.byName("last_seen_at") as? Long ?: 0L,
                occurrences = (r.byName("occurrence_count") as? Long)?.toInt() ?: 1,
                energy = (r.byName("energy") as? Double)?.toFloat() ?: 1.0f,
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
            val skillId = r.byName("skill_id") as? String ?: continue
            val initial = r.byName("initial_fingerprint") as? String
            val terminal = r.byName("terminal_fingerprint") as? String
            val transitionsJson = r.byName("transitions_json") as? String
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
                    name = r.byName("name") as? String ?: skillId,
                    goal = r.byName("goal") as? String ?: "",
                    appPackage = r.byName("app_package") as? String,
                    entryFingerprints = setOf(initial),
                    exitFingerprint = terminal,
                    steps = steps,
                    stats = com.apex.memkit.core.model.MacroStats(
                        successCount = (r.byName("success_count") as? Long)?.toInt() ?: 0,
                        failureCount = (r.byName("failure_count") as? Long)?.toInt() ?: 0,
                        energy = ((r.byName("energy") as? Double)?.toFloat() ?: 1.0f).coerceIn(0f, 10f),
                        crystallized = (r.byName("is_crystallized") as? Long) == 1L,
                        lastExecutedAt = r.byName("last_executed_at") as? Long ?: 0L,
                    ),
                    sourceEpisodeId = r.byName("source_episode_id") as? String,
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
            val old = r.byName("old_fingerprint") as? String ?: continue
            val new = r.byName("new_fingerprint") as? String ?: continue
            memKit.store.addAlias(
                FingerprintAlias(
                    oldFingerprint = old,
                    newFingerprint = new,
                    score = ((r.byName("match_score") as? Double)?.toFloat() ?: 0.7f).coerceIn(0f, 1f),
                    fromVersion = r.byName("from_version") as? String,
                    toVersion = r.byName("to_version") as? String,
                    createdAt = r.byName("created_at") as? Long ?: 0L,
                ),
            )
            report.aliases++
        }
    }

    /** 行包装：列名 → 值（取值按列名，不依赖 SELECT 列序 —— #821）。 */
    class QueryRow(internal val values: Map<String, Any?>) {
        fun byName(name: String): Any? = values[name]
    }

    private fun query(db: SQLiteDatabase, sql: String): List<QueryRow> = runCatching {
        val out = ArrayList<QueryRow>()
        db.rawQuery(sql, null).use { cursor ->
            val names = (0 until cursor.columnCount).map { cursor.getColumnName(it) }
            while (cursor.moveToNext()) {
                val row = HashMap<String, Any?>(names.size)
                names.forEachIndexed { i, name ->
                    row[name] = when (cursor.getType(i)) {
                        Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(i)
                        Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(i)
                        Cursor.FIELD_TYPE_STRING -> cursor.getString(i)
                        Cursor.FIELD_TYPE_BLOB -> null
                        else -> null
                    }
                }
                out.add(QueryRow(row))
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

        /** episodes 表列名清单（与 SELECT 列序一致；契约测试锁映射）。 */
        internal val EPISODE_COLUMNS = listOf(
            "episode_id", "goal", "app_package", "app_version", "status",
            "started_at", "finished_at", "total_actions",
        )

        /**
         * episodes 行 → [EpisodeRecord]（纯函数，#821 字段值契约测试直调）：
         * started_at ← started_at 列（真实开始时间）、finished_at ← finished_at
         * 列（旧实现错读下一列且恒丢 —— 本函数即修复落点）。
         */
        internal fun episodeRecord(id: String, r: QueryRow): EpisodeRecord = EpisodeRecord(
            episodeId = id,
            goal = r.byName("goal") as? String ?: "(未知目标)",
            appPackage = r.byName("app_package") as? String,
            appVersion = r.byName("app_version") as? String,
            status = EpisodeStatus.safe(r.byName("status") as? String),
            startedAt = r.byName("started_at") as? Long ?: 0L,
            finishedAt = r.byName("finished_at") as? Long,
            actionCount = (r.byName("total_actions") as? Long)?.toInt() ?: 0,
            manual = id.startsWith("manual_"),
        )

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
