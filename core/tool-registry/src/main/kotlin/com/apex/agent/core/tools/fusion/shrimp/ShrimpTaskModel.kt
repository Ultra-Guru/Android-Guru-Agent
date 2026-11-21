package com.apex.agent.core.tools.fusion.shrimp

import com.apex.agent.core.tools.builtin.AtomicFileWrite
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * # Shrimp Task Manager —— 数据模型与持久化存储
 *
 * 上游（cjo4m06/mcp-shrimp-task-manager v1.0.21，MIT）是「为 AI 编程
 * 助手设计的结构化任务管理系统」：任务规划（依赖 DAG + 反向链接）、
 * 执行追踪、完成验证、反思洞察、任务拆分、项目规则、研究模式与思维
 * 处理 —— 强调思维链、反思与风格一致性，复杂任务自动分解、执行追踪
 * 与完整性验证。
 *
 * 本文件：任务/任务列表数据模型（kotlinx.serialization）+ 文件存储
 * （tasks.json 原子写 + clear 时的自动备份链）。引擎
 * （[ShrimpTaskEngine]）与传输（[ShrimpBuiltinMcpTransport]）在同包。
 */
@Serializable
data class ShrimpTask(
    /** 任务序号（1 起，列表内唯一 —— 上游 backlink 引用键）。 */
    val id: Int,
    /** 任务描述（要做什么 —— 原子工作单元）。 */
    val description: String = "",
    /** 前置任务 id 列表（依赖 DAG 的反向链接；全部完成才可执行）。 */
    val prerequisites: List<Int> = emptyList(),
    /** pending | in_progress | completed | archived。 */
    val status: String = STATUS_PENDING,
    /** 复杂度：low | medium | high（agent 分析后回填）。 */
    val complexity: String = "",
    /** 需求分析（agent 的 analyze_task 产出）。 */
    val analysis: String = "",
    /** 实现指南（怎么做的操作手册）。 */
    val implementationGuide: String = "",
    /** 验证步骤（怎么确认完成）。 */
    val verificationSteps: String = "",
    /** 备注（执行记录 / 验证证据等追加文本）。 */
    val notes: String = "",
    /** 反思洞察（reflect_task 逐条追加）。 */
    val insights: List<String> = emptyList(),
    /** 相关文件（确切路径清单）。 */
    val relatedFiles: List<String> = emptyList(),
    /** 三词摘要标签（上游风格一致性要求 —— 索引用）。 */
    val summary: String = "",
    val createdAt: Long = 0,
    val updatedAt: Long = 0
) {
    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_IN_PROGRESS = "in_progress"
        const val STATUS_COMPLETED = "completed"
        const val STATUS_ARCHIVED = "archived"
    }
}

/** 任务列表整体（含项目规则与计划上下文）。 */
@Serializable
data class ShrimpTaskList(
    val tasks: List<ShrimpTask> = emptyList(),
    /** 项目规则（init_project_rules 的产出，execute 上下文常驻）。 */
    val rules: String = "",
    /** 当前计划上下文（plan_task 的目标描述）。 */
    val planContext: String = "",
    val version: Int = 1
)

/**
 * 文件存储：`tasks.json`（原子写）+ 备份链（`tasks_backup_<ts>.json`）。
 *
 * 线程模型：全部 synchronized（MCP 连接可能并发到达 —— 与
 * FusionBundleRegistry 同防线口径）。首次访问懒加载，之后内存缓存
 * 写透。思维链（process_thought）遵循上游「会话内部使用」语义 ——
 * 内存环形缓冲，不落盘。
 */
class ShrimpTaskStore(private val dir: File) {

    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()

    private var cached: ShrimpTaskList? = null

    /** 当前任务列表（懒加载 + 缓存写透）。 */
    fun current(): ShrimpTaskList = synchronized(lock) {
        cached ?: run {
            val file = File(dir, TASKS_FILE)
            val loaded = if (file.isFile) {
                runCatching { json.decodeFromString(ShrimpTaskList.serializer(), file.readText()) }
                    .getOrDefault(ShrimpTaskList())
            } else {
                ShrimpTaskList()
            }
            ShrimpTaskList(tasks = loaded.tasks, rules = loaded.rules, planContext = loaded.planContext)
                .also { cached = it }
        }
    }

    /** 整体保存（原子写）。 */
    fun save(list: ShrimpTaskList) = synchronized(lock) {
        dir.mkdirs()
        AtomicFileWrite.writeText(File(dir, TASKS_FILE), json.encodeToString(ShrimpTaskList.serializer(), list))
        cached = list
    }

    /** 清空前备份（上游 clear_all_tasks 的自动备份语义），返回备份文件名。 */
    fun backup(): String = synchronized(lock) {
        val file = File(dir, TASKS_FILE)
        val name = "tasks_backup_${System.currentTimeMillis()}.json"
        if (file.isFile) {
            dir.mkdirs()
            File(dir, name).writeText(file.readText())
        }
        name
    }

    /** 备份文件清单（按时间倒序 —— 恢复指引用）。 */
    fun backups(): List<String> = synchronized(lock) {
        dir.listFiles { f -> f.name.startsWith("tasks_backup_") }
            ?.map { it.name }
            ?.sortedDescending()
            ?: emptyList()
    }

    // ── 思维链环形缓冲（process_thought —— 上游会话内语义，不落盘）────

    private val thoughts = ArrayDeque<String>()

    @Synchronized
    fun appendThought(thought: String): Int {
        thoughts.addLast(thought)
        while (thoughts.size > THOUGHT_LIMIT) thoughts.removeFirst()
        return thoughts.size
    }

    @Synchronized
    fun recentThoughts(): List<String> = thoughts.toList()

    private companion object {
        const val TASKS_FILE = "tasks.json"
        const val THOUGHT_LIMIT = 20
    }
}
