package com.apex.agent.core.tools.pluginhost.worker

import java.io.File
import kotlinx.serialization.Serializable

/**
 * # WorkerProcessPort —— 长驻插件进程的生成端口（core 无 Android 依赖）
 *
 * 既有 [com.apex.agent.core.tools.extension.NodeInstallPort] 是**一次性**
 * 执行（spawn → 收干 → 等退出），而 Plugin Worker 需要长驻进程 + 双向
 * 流式 stdin/stdout。core 侧只定义端口契约，真实实现分两层：
 *
 * - JVM 测试 / CI：[LocalProcessWorkerPort]（ProcessBuilder 直启 node）；
 * - Android app：Phase 6 接线时经 ProotCommandSpawner 通道实现
 *   （命令与 cwd 的 host→guest 路径映射由 app 层负责，core 不感知）。
 *
 * 安全契约：argv 由宿主白名单构造（RuntimeProvider.buildCommand），
 * 用户输入只进入消息负载（JSON 转义），永不拼进命令行。
 */
interface WorkerProcessPort {

    /** 生成一个长驻 Worker 进程。 */
    fun spawn(spec: SpawnSpec): Result<Process>

    /**
     * 进程规格：白名单 argv + 工作目录 + 启动环境（最小集合 —— 不继承
     * 宿主环境变量，token 类只经 [env] 显式注入）。
     */
    @Serializable
    data class SpawnSpec(
        val argv: List<String>,
        val workingDir: String,
        val env: Map<String, String> = emptyMap()
    ) {
        init {
            require(argv.isNotEmpty()) { "argv must not be empty" }
        }

        companion object {
            fun of(command: List<String>, cwd: File, env: Map<String, String> = emptyMap()) =
                SpawnSpec(argv = command, workingDir = cwd.canonicalFile.path, env = env)
        }
    }
}

/**
 * JVM 直启实现（ProcessBuilder）—— CI 集成测试与桌面调试用。
 *
 * Android app **不得**使用本实现（app 进程内直启 node 没有 PRoot 隔离）；
 * app 侧经沙箱通道实现同款端口。
 */
class LocalProcessWorkerPort : WorkerProcessPort {

    override fun spawn(spec: WorkerProcessPort.SpawnSpec): Result<Process> = runCatching {
        val builder = ProcessBuilder(spec.argv)
            .directory(File(spec.workingDir))
            .redirectErrorStream(false)
        // 最小环境：PATH 保障 node 可解析（沙箱/app 实现另有环境策略）
        builder.environment().apply {
            if (get("PATH") == null) put("PATH", System.getenv("PATH") ?: "/usr/bin:/bin")
            putAll(spec.env)
        }
        builder.start()
    }
}

/**
 * # RuntimeProvider —— Node/Bun 运行时探测与启动命令构造（spec §4.4）
 *
 * Node 与 Bun 是**两个不可混同的运行时**（spec 禁止事项 #3）：
 * - 探测分离（各自 `--version`，失败即缺失，不回退另一个冒充）；
 * - 启动命令分离（bun 原生跑 TS；node 跑 TS 需要 transpile —— 本期
 *   如实报告不支持，而不是偷偷用 node 执行 TS）；
 * - 探测结果进入 [com.apex.agent.core.tools.pluginhost.PluginEnvironmentSnapshot]
 *   供兼容性分析引擎评级。
 */
interface RuntimeProvider {
    /** 运行时种类。 */
    val kind: com.apex.agent.core.tools.pluginhost.PluginRuntimeKind

    /** 探测运行时版本（经沙箱执行通道；null = 不可用）。 */
    suspend fun probe(): String?

    /**
     * 构造 Worker 启动命令（白名单 argv：可执行 + 引导脚本路径）。
     * [bootstrapScript] 必须是 [workingDir] 可达的绝对路径（host/guest
     * 路径语义由实现方保证）。
     */
    fun buildCommand(bootstrapScript: File, workingDir: File): List<String>
}

/** 探测超时（版本查询是毫秒级操作，15s 覆盖冷启动沙箱余量）。 */
private const val PROBE_TIMEOUT_MS = 15_000L

/** Node.js 运行时提供方。 */
class NodeRuntimeProvider(
    private val port: com.apex.agent.core.tools.extension.NodeInstallPort,
    private val probeCwd: File = File(".")
) : RuntimeProvider {

    override val kind = com.apex.agent.core.tools.pluginhost.PluginRuntimeKind.NODE

    override suspend fun probe(): String? {
        val outcome = port.exec(
            command = listOf("node", "--version"),
            cwd = probeCwd.canonicalFile,
            timeoutMs = PROBE_TIMEOUT_MS
        ).getOrNull() ?: return null
        if (!outcome.success) return null
        return outcome.stdout.trim().ifEmpty { null }
    }

    override fun buildCommand(bootstrapScript: File, workingDir: File) =
        listOf("node", bootstrapScript.canonicalFile.path)
}

/** Bun 运行时提供方（探测失败如实报告缺失，不以 node 冒充）。 */
class BunRuntimeProvider(
    private val port: com.apex.agent.core.tools.extension.NodeInstallPort,
    private val probeCwd: File = File(".")
) : RuntimeProvider {

    override val kind = com.apex.agent.core.tools.pluginhost.PluginRuntimeKind.BUN

    override suspend fun probe(): String? {
        val outcome = port.exec(
            command = listOf("bun", "--version"),
            cwd = probeCwd.canonicalFile,
            timeoutMs = PROBE_TIMEOUT_MS
        ).getOrNull() ?: return null
        if (!outcome.success) return null
        return outcome.stdout.trim().ifEmpty { null }
    }

    override fun buildCommand(bootstrapScript: File, workingDir: File) =
        listOf("bun", bootstrapScript.canonicalFile.path)
}
