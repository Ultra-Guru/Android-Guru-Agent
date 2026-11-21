package com.apex.agent.platform.terminal.runtime

import com.apex.agent.platform.terminal.policy.PrivilegeLevel

/**
 * P71: ExecutionBackend — PTY-Spawn 级接缝（方案 C，PR #75 计划 §3）。
 *
 * 核心洞察：Terminal Core 的全部下游（泵/VT/观察/等待/写入/信号/缩放）只依赖
 * "一个真实 PTY master fd + nativeSessionId"。会话差异只在 forkpty 的 child
 * exec 什么 argv、带什么 env、落在什么 cwd。因此接缝放在唯一的 spawn 点：
 *
 * ```
 * SessionManager.create(SpawnSpec) → nativeCreateSessionArgv → forkpty
 *     LINUX: execv(libproot.so, [proot, "-r", rootfs, …, "--", "/bin/bash", "-i"])
 * ```
 *
 * 这是 P71 引入的唯一新抽象；P73 把它接进 TerminalRuntime.create(backend=…)。
 * Android 本地 Shell 后端已随 Ubuntu-only 收敛移除（生产注册表仅 linux-ubuntu；
 * 朴素 shell spawn 作为测试夹具保留在测试源集）。
 */
interface ExecutionBackend {
    /** 稳定标识（Agent 可读）："linux-ubuntu" | … */
    val id: String

    /** 后端类别：Linux 用户空间（AndroidShell 已移除，仅存 Linux 一类）。 */
    val runtimeType: BackendRuntimeType

    /**
     * 后端可用性探测（Agent 能力发现的依据，P73 接入 TerminalBackends 工具）。
     * 必须廉价、无副作用 —— 每次调用都应反映当前真实状态。
     */
    suspend fun availability(): BackendAvailability

    /**
     * 把会话创建请求翻译成可 spawn 的 [SpawnSpec]。
     * 失败（rootfs 未就绪、二进制缺失…）返回带 [BackendAvailability] 的失败。
     */
    suspend fun prepare(request: SessionSpawnRequest): Result<SpawnSpec>
}

/** 后端类别（AndroidShell 后端移除后仅剩 Linux 用户空间一类；保留枚举作为契约面）。 */
enum class BackendRuntimeType { LINUX }

/** availability() 的结果 —— 三态 + 引导信息。 */
sealed class BackendAvailability {
    /** 可立即创建会话。 */
    object Ready : BackendAvailability()

    /** rootfs 未安装/未就绪（Agent 可引导安装，P72 接入下载进度）。 */
    data class NeedsRootfs(val state: String = "not_provisioned", val progress: Float? = null) : BackendAvailability()

    /** 后端本身不可用（proot 二进制缺失/损坏、架构不匹配…）。 */
    data class Failed(val reason: String) : BackendAvailability()
}

/** SessionManager.create 的后端无关请求。 */
data class SessionSpawnRequest(
    /** guest 语义路径（由后端翻译成 -w）。 */
    val cwd: String,
    val rows: Int,
    val cols: Int,
    /** 调用方显式 env（进入 -E guest env）。 */
    val env: Map<String, String> = emptyMap(),
    val privilege: PrivilegeLevel = PrivilegeLevel.NORMAL,
    /**
     * T75: LINUX 后端的 workspace id（null/blank → "default"）。合法 id 懒创建
     * （workspace-per-task 零摩擦）。
     */
    val workspaceId: String? = null
)

/**
 * forkpty spawn 的完整规格 —— [com.apex.agent.platform.terminal.pty.NativePty.nativeCreateSessionArgv]
 * 的直接入参。
 */
data class SpawnSpec(
    /** argv[0] = 可执行文件。LINUX: [libproot.so,"-r",…,"--","/bin/bash","-i"]。 */
    val argv: List<String>,
    /** 传给 native 的显式 env（叠加在 C++ 安全默认值之上）。 */
    val env: Map<String, String>,
    /**
     * forkpty child 的 host 侧 chdir 目标。
     * LINUX: proot 忽略 host cwd（以 -w 为准），此处只需一个必然存在的目录（rootfs 根）。
     */
    val cwd: String,
    /** true = request.cwd 是 guest 路径（已通过 argv 的 -w 表达，host cwd 仅作安全落点）。 */
    val cwdIsGuestPath: Boolean,
    /**
     * T73: 展示语义的 shell 路径（进 TerminalSession.shell / SessionCreated 事件）。
     * null → argv[0]；LINUX = "/bin/bash"（argv[0] 是 libproot.so 路径，对
     * Agent/UI 无意义）。
     */
    val shellDisplay: String? = null,
    /**
     * T73: 展示语义的 cwd（进 TerminalSession.initialCwd / SessionCreated 事件）。
     * null → spec.cwd（host 路径）；LINUX 由 metadata.guestCwd 承担。
     */
    val cwdDisplay: String? = null,
    /** 进 SessionRecord 的后端元数据（T73 已持久化：SessionRecord schema v2）。 */
    val metadata: BackendSessionMetadata = BackendSessionMetadata(backendId = "linux-ubuntu")
)

/** SpawnSpec 的会话元数据（持久化/诊断用，不参与 spawn 本身）。 */
data class BackendSessionMetadata(
    val backendId: String,
    val rootfsId: String? = null,
    /** T75: workspace id（懒创建后的规范化 id）。 */
    val workspaceId: String? = null,
    /** host 侧 workspace 目录（bind 到 guest /workspace）。 */
    val workspaceDir: String? = null,
    val binds: List<String> = emptyList(),
    /** guest 初始 cwd（LINUX: -w 的值）。 */
    val guestCwd: String? = null
)

/** 后端注册表 —— TerminalRuntime/工具层按 id 查找（P73 DI 接线）。 */
class ExecutionBackendRegistry(private val backends: List<ExecutionBackend>) {

    private val byId: Map<String, ExecutionBackend> =
        linkedMapOf<String, ExecutionBackend>().apply { backends.forEach { put(it.id, it) } }

    fun get(id: String): ExecutionBackend? = byId[id]

    /** 默认后端 —— 注册顺序首位（Ubuntu-only 收敛后即 linux-ubuntu）。 */
    val default: ExecutionBackend
        get() = backends.first()

    fun list(): List<ExecutionBackend> = byId.values.toList()

    companion object {
        fun of(vararg backends: ExecutionBackend): ExecutionBackendRegistry =
            ExecutionBackendRegistry(backends.toList())
    }
}

