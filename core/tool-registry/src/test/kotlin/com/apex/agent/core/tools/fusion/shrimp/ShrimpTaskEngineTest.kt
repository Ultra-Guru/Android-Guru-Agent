package com.apex.agent.core.tools.fusion.shrimp

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * # Shrimp Task Manager 引擎与传输测试
 *
 * 覆盖面（16 工具全目录）：
 * - 规划链：plan_task 归档已完成 + update_task 六件套落库（五防线）；
 * - 依赖纪律：前置未全 execute_task 被拒；环/自引用/悬空引用 update 被拒；
 * - 执行链：execute（规则与洞察随身）→ complete（证据 + 下一个建议）→
 *   verify（未完成先拒绝）→ reflect（洞察追加）；
 * - 记忆链：list/query/get_task_detail 进度与过滤；split 依赖转接；
 * - 清档链：clear_all_tasks 自动备份 + 规则保留；
 * - 持久化：跨引擎实例（= 跨 MCP 连接）任务数据存活；
 * - 传输：JSON-RPC 三方法 + 16 工具 tools/list + tools/call 真跑。
 */
class ShrimpTaskEngineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newEngine(dirName: String = "shrimp"): Pair<ShrimpTaskEngine, ShrimpTaskStore> {
        val store = ShrimpTaskStore(tmp.newFolder(dirName))
        return ShrimpTaskEngine(store) to store
    }

    private fun args(json: String): JsonObject =
        Json.parseToJsonElement(json).jsonObject

    private fun JsonObject.status(): String = this["status"]!!.jsonPrimitive.content

    private fun JsonObject.message(): String = this["message"]?.jsonPrimitive?.contentOrNull ?: ""

    // ═══ 规划链 ═══════════════════════════════════════════════════════

    @Test
    fun `plan_task 记录目标并归档已完成`() {
        val (engine, _) = newEngine()
        // 先落两个任务并完成其一
        engine.callTool("update_task", args("""{"tasks":[{"description":"t1"},{"description":"t2","prerequisites":[1]}]}"""))
        engine.callTool("complete_task", args("""{"index":1,"notes":"done"}"""))
        val r = engine.callTool("plan_task", args("""{"description":"新一轮目标"}"""))
        assertEquals("ok", r.status())
        assertEquals("新一轮目标", r["plan_context"]!!.jsonPrimitive.content)
        assertEquals(1, r["archived_completed"]!!.jsonPrimitive.content.toInt())
        // t2 保持 pending（未完成不归档）
        val tasks = r["active_tasks"]!!.jsonArray
        assertEquals(1, tasks.size)
        assertEquals("pending", tasks[0].jsonObject["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun `plan_task 缺 description 报结构化错误`() {
        val (engine, _) = newEngine()
        val r = engine.callTool("plan_task", args("{}"))
        assertEquals("error", r.status())
        assertTrue(r.message().contains("description"))
    }

    @Test
    fun `update_task 六件套整表落库`() {
        val (engine, store) = newEngine()
        val r = engine.callTool(
            "update_task",
            args(
                """
                {"tasks":[
                  {"description":"调研选型","complexity":"low","analysis":"先定数据库","implementation_guide":"检索对比","verification_steps":"结论有来源","summary":"调研 数据库 选型"},
                  {"description":"实现存储层","prerequisites":[1],"complexity":"medium","implementation_guide":"写 DAO","verification_steps":"测试绿","related_files":["db/AppDatabase.kt"],"summary":"实现 存储 DAO"}
                ]}
                """.trimIndent()
            )
        )
        assertEquals("ok", r.status())
        assertEquals(2, r["saved"]!!.jsonPrimitive.content.toInt())
        val persisted = store.current()
        assertEquals(2, persisted.tasks.size)
        assertEquals(listOf(1), persisted.tasks[1].prerequisites)
        assertEquals("db/AppDatabase.kt", persisted.tasks[1].relatedFiles[0])
        assertEquals("调研 数据库 选型", persisted.tasks[0].summary)
        assertEquals("low", persisted.tasks[0].complexity)
    }

    @Test
    fun `update_task 五防线拒绝非法清单`() {
        val (engine, _) = newEngine()
        // 空 tasks
        assertEquals("error", engine.callTool("update_task", args("""{"tasks":[]}""")).status())
        // 缺 description
        assertEquals(
            "error",
            engine.callTool("update_task", args("""{"tasks":[{"complexity":"low"}]}""")).status()
        )
        // 悬空引用
        assertEquals(
            "error",
            engine.callTool("update_task", args("""{"tasks":[{"description":"a"},{"description":"b","prerequisites":[9]}]}""")).status()
        )
        // 自引用
        assertEquals(
            "error",
            engine.callTool("update_task", args("""{"tasks":[{"description":"a"},{"description":"b","prerequisites":[2]}]}""")).status()
        )
        // 环：2 依赖 1，1 依赖 2
        assertEquals(
            "error",
            engine.callTool(
                "update_task",
                args("""{"tasks":[{"description":"a","prerequisites":[2]},{"description":"b","prerequisites":[1]}]}""")
            ).status()
        )
    }

    // ═══ 执行链与依赖纪律 ═════════════════════════════════════════════

    @Test
    fun `execute_task 前置未全被拒且全则进执行带上下文`() {
        val (engine, _) = newEngine()
        engine.callTool("init_project_rules", args("""{"rules":"测试先行"}"""))
        engine.callTool(
            "update_task",
            args("""{"tasks":[{"description":"a","implementation_guide":"先建骨架"},{"description":"b","prerequisites":[1],"implementation_guide":"照 a 的产物做"}]}""")
        )
        // b 的前置 a 未完成 → 拒
        val blocked = engine.callTool("execute_task", args("""{"index":2}"""))
        assertEquals("error", blocked.status())
        assertTrue(blocked.message().contains("前置未完成"))
        // a 可执行 → in_progress + 规则随身 + 指南随身
        val r = engine.callTool("execute_task", args("""{"index":1}"""))
        assertEquals("ok", r.status())
        assertEquals("测试先行", r["project_rules"]!!.jsonPrimitive.content)
        assertEquals("先建骨架", r["task"]!!.jsonObject["implementation_guide"]!!.jsonPrimitive.content)
    }

    @Test
    fun `complete_task 证据入账并建议下一个可执行任务`() {
        val (engine, _) = newEngine()
        engine.callTool(
            "update_task",
            args("""{"tasks":[{"description":"a"},{"description":"b","prerequisites":[1]},{"description":"c"}]}""")
        )
        engine.callTool("execute_task", args("""{"index":1}"""))
        val r = engine.callTool("complete_task", args("""{"index":1,"notes":"跑通冒烟"}"""))
        assertEquals("ok", r.status())
        assertEquals(33, r["progress"]!!.jsonObject["percent"]!!.jsonPrimitive.content.toInt())
        // 下一个建议：b（a 完成 → 解锁）而非 c
        assertEquals(2, r["next_task"]!!.jsonObject["id"]!!.jsonPrimitive.content.toInt())
        // 证据入账
        val detail = engine.callTool("get_task_detail", args("""{"index":1}"""))
        assertTrue(detail["task"]!!.jsonObject["notes"]!!.jsonPrimitive.content.contains("跑通冒烟"))
        // 重复完成幂等
        assertEquals("ok", engine.callTool("complete_task", args("""{"index":1}""")).status())
    }

    @Test
    fun `verify_task 未完成先拒绝且完成后证据入账`() {
        val (engine, _) = newEngine()
        engine.callTool("update_task", args("""{"tasks":[{"description":"a","verification_steps":"跑测试"}]}"""))
        val early = engine.callTool("verify_task", args("""{"index":1,"result":"x"}"""))
        assertEquals("error", early.status())
        engine.callTool("complete_task", args("""{"index":1}"""))
        val r = engine.callTool("verify_task", args("""{"index":1,"result":"./gradlew test 全绿"}"""))
        assertEquals("ok", r.status())
        assertTrue(r["recorded"]!!.jsonPrimitive.content.contains("全绿"))
    }

    @Test
    fun `reflect_task 洞察追加并随身带回 execute`() {
        val (engine, _) = newEngine()
        engine.callTool("update_task", args("""{"tasks":[{"description":"a"},{"description":"b"}]}"""))
        engine.callTool("complete_task", args("""{"index":1}"""))
        val r = engine.callTool(
            "reflect_task",
            args("""{"index":1,"insights":["依赖注入先定作用域","列表态要快照测试"]}""")
        )
        assertEquals("ok", r.status())
        assertEquals(2, r["insights"]!!.jsonArray.size)
        // 洞察随 execute 带回（风格一致性）
        val exec = engine.callTool("execute_task", args("""{"index":2}"""))
        val recent = exec["recent_insights"]!!.jsonArray
        assertEquals(2, recent.size)
        assertTrue(recent[0].jsonPrimitive.content.contains("依赖注入"))
    }

    // ═══ 记忆与查询链 ═════════════════════════════════════════════════

    @Test
    fun `list 与 query 与 detail 全貌`() {
        val (engine, _) = newEngine()
        engine.callTool(
            "update_task",
            args("""{"tasks":[{"description":"搭骨架","summary":"骨架 初始化 工程"},{"description":"写测试","prerequisites":[1]}]}""")
        )
        engine.callTool("complete_task", args("""{"index":1}"""))
        val list = engine.callTool("list_tasks", args("{}"))
        assertEquals(2, list["tasks"]!!.jsonArray.size)
        assertEquals(50, list["progress"]!!.jsonObject["percent"]!!.jsonPrimitive.content.toInt())
        // query 按状态
        val pending = engine.callTool("query_task", args("""{"status":"pending"}"""))
        assertEquals(1, pending["matched"]!!.jsonPrimitive.content.toInt())
        // query 按关键词（summary 命中）
        val byKeyword = engine.callTool("query_task", args("""{"keyword":"骨架"}"""))
        assertEquals(1, byKeyword["matched"]!!.jsonPrimitive.content.toInt())
        // detail：前置描述 + 后继
        val detail = engine.callTool("get_task_detail", args("""{"index":2}"""))
        val task = detail["task"]!!.jsonObject
        assertEquals("搭骨架", task["prerequisite_descriptions"]!!.jsonArray[0].jsonPrimitive.content.substringAfter(": "))
        assertEquals(1, task["prerequisites"]!!.jsonArray.size)
    }

    @Test
    fun `split_tasks 子任务成链且后继依赖转接`() {
        val (engine, _) = newEngine()
        engine.callTool(
            "update_task",
            args("""{"tasks":[{"description":"大任务"},{"description":"后继","prerequisites":[1]}]}""")
        )
        val r = engine.callTool(
            "split_tasks",
            args("""{"index":1,"subtasks":[{"description":"子1"},{"description":"子2"}]}""")
        )
        assertEquals("ok", r.status())
        assertEquals(2, r["created"]!!.jsonArray.size)
        val list = engine.callTool("list_tasks", args("{}"))
        val tasks = list["tasks"]!!.jsonArray.map { it.jsonObject }
        // 原任务归档；子1 依赖 []，子2 依赖 [子1]；后继改挂子2
        val original = tasks.first { it["id"]!!.jsonPrimitive.content == "1" }
        assertEquals("archived", original["status"]!!.jsonPrimitive.content)
        val sub1 = tasks.first { it["description"]!!.jsonPrimitive.content == "子1" }
        val sub2 = tasks.first { it["description"]!!.jsonPrimitive.content == "子2" }
        val successor = tasks.first { it["description"]!!.jsonPrimitive.content == "后继" }
        assertEquals(0, sub1["prerequisites"]!!.jsonArray.size)
        assertEquals(sub1["id"]!!.jsonPrimitive.content, sub2["prerequisites"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals(sub2["id"]!!.jsonPrimitive.content, successor["prerequisites"]!!.jsonArray[0].jsonPrimitive.content)
    }

    @Test
    fun `delete_task 清理他处引用`() {
        val (engine, _) = newEngine()
        engine.callTool(
            "update_task",
            args("""{"tasks":[{"description":"a"},{"description":"b","prerequisites":[1]}]}""")
        )
        val r = engine.callTool("delete_task", args("""{"index":1}"""))
        assertEquals("ok", r.status())
        assertEquals(1, r["remaining"]!!.jsonPrimitive.content.toInt())
        val b = engine.callTool("get_task_detail", args("""{"index":2}"""))
        assertEquals(0, b["task"]!!.jsonObject["prerequisites"]!!.jsonArray.size)
    }

    @Test
    fun `clear_all_tasks 自动备份且规则保留`() {
        val dir = tmp.newFolder("clear")
        val store = ShrimpTaskStore(dir)
        val engine = ShrimpTaskEngine(store)
        engine.callTool("init_project_rules", args("""{"rules":"规范X"}"""))
        engine.callTool("update_task", args("""{"tasks":[{"description":"a"}]}"""))
        val r = engine.callTool("clear_all_tasks", args("{}"))
        assertEquals("ok", r.status())
        val backupName = r["backup"]!!.jsonPrimitive.content
        assertTrue(backupName.startsWith("tasks_backup_"))
        // 备份文件真实存在且含原任务
        val backupFile = File(dir, store.backups().first())
        assertTrue(backupFile.isFile)
        assertTrue(backupFile.readText().contains(""""description":"a""""))
        // 清空但规则保留
        val after = store.current()
        assertEquals(0, after.tasks.size)
        assertEquals("规范X", after.rules)
        assertEquals(1, store.backups().size)
    }

    // ═══ 研究与思维链 ═════════════════════════════════════════════════

    @Test
    fun `research_mode 返回研究工作流与规则`() {
        val (engine, _) = newEngine()
        engine.callTool("init_project_rules", args("""{"rules":"规范Y"}"""))
        val r = engine.callTool("research_mode", args("""{"query":"GraphQL vs REST"}"""))
        assertEquals("ok", r.status())
        assertEquals("GraphQL vs REST", r["query"]!!.jsonPrimitive.content)
        assertEquals("规范Y", r["project_rules"]!!.jsonPrimitive.content)
        assertTrue(r["guidance"]!!.jsonPrimitive.content.contains("取证"))
        // 缺 query 报错
        assertEquals("error", engine.callTool("research_mode", args("{}")).status())
    }

    @Test
    fun `analyze_task 模板指引与复杂度校验`() {
        val (engine, _) = newEngine()
        engine.callTool("update_task", args("""{"tasks":[{"description":"a"}]}"""))
        // 缺参 → 模板指引（不是错误）
        val template = engine.callTool("analyze_task", args("""{"index":1}"""))
        assertEquals("ok", template.status())
        assertTrue(template["guidance"]!!.jsonPrimitive.content.contains("complexity"))
        // 非法复杂度
        assertEquals(
            "error",
            engine.callTool("analyze_task", args("""{"index":1,"complexity":"huge","analysis":"x"}""")).status()
        )
        // 合法回填
        val r = engine.callTool(
            "analyze_task",
            args("""{"index":1,"complexity":"high","analysis":"三处状态同步"}""")
        )
        assertEquals("ok", r.status())
    }

    @Test
    fun `process_thought 思维链入环与环形上限`() {
        val (engine, store) = newEngine()
        val r = engine.callTool("process_thought", args("""{"thought":"先拆数据层"}"""))
        assertEquals("ok", r.status())
        assertEquals(1, r["thought_number"]!!.jsonPrimitive.content.toInt())
        // 环形上限 20
        repeat(30) { engine.callTool("process_thought", args("""{"thought":"步$it"}""")) }
        assertEquals(20, store.recentThoughts().size)
        assertTrue(store.recentThoughts().last().contains("步29"))
        // 不落盘（上游会话内语义）
        assertFalse(File(tmp.root, "shrimp").resolve("thoughts.json").isFile)
    }

    @Test
    fun `未知工具结构化错误`() {
        val (engine, _) = newEngine()
        assertEquals("error", engine.callTool("nope", args("{}")).status())
    }

    // ═══ 持久化（跨连接 = 跨引擎实例）═════════════════════════════════

    @Test
    fun `任务数据跨引擎实例持久（新 MCP 连接可见）`() {
        val store = ShrimpTaskStore(tmp.newFolder("persist"))
        val first = ShrimpTaskEngine(store)
        first.callTool("init_project_rules", args("""{"rules":"规范Z"}"""))
        first.callTool("update_task", args("""{"tasks":[{"description":"a"},{"description":"b","prerequisites":[1]}]}"""))
        first.callTool("complete_task", args("""{"index":1,"notes":"证据"}"""))
        // 新连接（新 transport → 新引擎，共享 store）
        val second = ShrimpTaskEngine(store)
        val list = second.callTool("list_tasks", args("{}"))
        assertEquals(2, list["tasks"]!!.jsonArray.size)
        assertEquals(50, list["progress"]!!.jsonObject["percent"]!!.jsonPrimitive.content.toInt())
        assertEquals("规范Z", store.current().rules)
        val detail = second.callTool("get_task_detail", args("""{"index":1}"""))
        assertTrue(detail["task"]!!.jsonObject["notes"]!!.jsonPrimitive.content.contains("证据"))
    }

    // ═══ 传输（JSON-RPC 三方法）═══════════════════════════════════════

    @Test
    fun `传输 initialize 协议握手`() = runTest {
        val transport = ShrimpBuiltinMcpTransport(ShrimpTaskStore(tmp.newFolder("t1")))
        val r = transport.send(1, """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}""")!!
        assertEquals("2.0", r["jsonrpc"]!!.jsonPrimitive.content)
        val result = r["result"]!!.jsonObject
        assertEquals("2024-11-05", result["protocolVersion"]!!.jsonPrimitive.content)
        assertEquals("shrimp-fusion-builtin", result["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `传输 tools_list 全目录 16 工具`() = runTest {
        val transport = ShrimpBuiltinMcpTransport(ShrimpTaskStore(tmp.newFolder("t2")))
        val r = transport.send(2, """{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}""")!!
        val tools = r["result"]!!.jsonObject["tools"]!!.jsonArray
        assertEquals(16, tools.size)
        val names = tools.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        // 上游 docs/tools.md 逐名对齐
        assertEquals(
            listOf(
                "plan_task", "update_task", "execute_task", "complete_task",
                "list_tasks", "delete_task", "split_tasks", "analyze_task",
                "verify_task", "reflect_task", "query_task", "get_task_detail",
                "clear_all_tasks", "init_project_rules", "research_mode", "process_thought"
            ),
            names
        )
        // inputSchema 契约：required 收紧（plan_task 的 description）
        val plan = tools[0].jsonObject
        assertTrue(plan["description"]!!.jsonPrimitive.content.isNotBlank())
        assertEquals(
            listOf("description"),
            plan["inputSchema"]!!.jsonObject["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        )
    }

    @Test
    fun `传输 tools_call 真跑与错误面`() = runTest {
        val transport = ShrimpBuiltinMcpTransport(ShrimpTaskStore(tmp.newFolder("t3")))
        val call = transport.send(
            3,
            """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"plan_task","arguments":{"description":"目标"}}}"""
        )!!
        val text = call["result"]!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(text.contains("\"status\":\"ok\""))
        assertTrue(text.contains("目标"))
        // 未知方法 → JSON-RPC error
        val unknown = transport.send(4, """{"jsonrpc":"2.0","id":4,"method":"resources/list","params":{}}""")!!
        assertNotNull(unknown["error"])
        // 通知无响应
        assertEquals(null, transport.send(null, """{"jsonrpc":"2.0","method":"tools/list"}"""))
    }

    @Test
    fun `内置服务器配置形态（scope all 全工位）`() {
        val config = ShrimpBuiltinMcpServer.config()
        assertEquals("shrimp", config.name)
        assertEquals("all", config.scope)
    }
}
