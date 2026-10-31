package com.apex.agent.platform.terminal.searxng

import java.io.File

/**
 * ═══ SearXNG 本地搜索 · 状态与契约模型（SearXNG Local Search Spec v1.0 §5/§6）═══
 *
 * 本包实现「设置页开关 → 启动/停止 PRoot Ubuntu 内的 SearXNG 守护进程」的完整
 * 生命周期。职责切分（Spec §4 组件表）：
 *  - [SearxngLifecycleCoordinator]：状态机、进程持有、健康检查、自愈、意图持久化；
 *  - [SearxngInstaller]：依赖安装（apt/git/venv/pip）、幂等判定、阶段进度打点；
 *  - [SearxngSettingsWriter]：settings.yml 生成与幂等写入（host 侧直写 rootfs）；
 *  - 搜索解析（SearxngProvider）在 app 模块（需要 OkHttp），经
 *    core:tool-registry 的链首插点接入 WebSearchTool 回退链。
 *
 * 模块边界：本包位于 platform:terminal（纯 JVM 可测，无 OkHttp/Android 依赖）。
 * HTTP 健康检查与意图持久化以函数端口注入（生产 DI 适配 app 单例；JVM 测试注入
 * fake）—— 与 UbuntuLifecycleCoordinator 的函数端口模式同款（T81 禁令：不建第
 * 二套 TerminalRuntime/Provisioner 抽象）。
 */

/** 本地 SearXNG 实例的唯一网络契约（Spec §7：bind 127.0.0.1，固定 8888）。 */
object SearxngContract {
    /** 监听地址（host 侧可达 —— PRoot 与宿主共享网络栈）。 */
    const val BIND_ADDRESS = "127.0.0.1"

    /** 固定端口（Spec §6 Vault 键 searxng_port：固定 8888，预留扩展）。 */
    const val DEFAULT_PORT = 8888

    /** guest 侧安装根目录（rootfs 内 —— host 物理路径 = <rootfs>/opt/searxng）。 */
    const val GUEST_INSTALL_DIR = "/opt/searxng"

    /** SearXNG 源码目录（git clone 目标）。 */
    const val GUEST_SRC_DIR = "$GUEST_INSTALL_DIR/src"

    /** venv 目录。 */
    const val GUEST_VENV_DIR = "$GUEST_INSTALL_DIR/.venv"

    /** settings.yml 路径（SEARXNG_SETTINGS_PATH 指向）。 */
    const val GUEST_SETTINGS_FILE = "$GUEST_INSTALL_DIR/searxng.yml"

    /** 就绪判定端点（Spec §9）。 */
    const val HEALTHZ_PATH = "/healthz"

    /** 就绪判定超时（Spec N2：30s）。 */
    const val STARTUP_TIMEOUT_MS = 30_000L

    /** 周期健康检查间隔（Spec N2：15s）。 */
    const val WATCHDOG_INTERVAL_MS = 15_000L

    /** 单次健康检查超时（Spec N2：3s）。 */
    const val HEALTH_CHECK_TIMEOUT_MS = 3_000L

    /** 连续失败阈值（Spec §9：3 次 → Restarting）。 */
    const val WATCHDOG_FAILURE_THRESHOLD = 3

    /** headless 会话的 workspaceId（同时充当 ProcessPolicy 语义 tag —— Spec §6）。 */
    const val SESSION_WORKSPACE_ID = "searxng"
}

/**
 * v1 引擎目录（Spec §11：固定 6 个，避免 UI 复杂化；开放问题 3 拍板「固定 6」）。
 * 键 = SearXNG settings.yml 的 engine name（keep_only 名单）；值仅供 UI 展示。
 */
val SEARXNG_ENGINE_CATALOG: List<SearxngEngineInfo> = listOf(
    SearxngEngineInfo("google", "Google"),
    SearxngEngineInfo("bing", "Bing"),
    SearxngEngineInfo("duckduckgo", "DuckDuckGo"),
    SearxngEngineInfo("brave", "Brave"),
    SearxngEngineInfo("wikipedia", "Wikipedia"),
    SearxngEngineInfo("arxiv", "arXiv")
)

/** 默认全开的引擎（Spec §7：默认 google/bing/duckduckgo）。 */
val SEARXNG_DEFAULT_ENGINES: Set<String> = setOf("google", "bing", "duckduckgo")

/** 引擎目录项。 */
data class SearxngEngineInfo(
    /** SearXNG engine name（settings.yml 键）。 */
    val id: String,
    /** UI 展示名。 */
    val label: String
)

/**
 * 生命周期状态机（Spec §5）。
 *
 * ```
 * Off ──开启──▶ Installing ──▶ Starting ──▶ Ready ⇄ Restarting
 *                  │              │            │        │
 *                  └── Failed(retryable) ◀─────┴────────┘
 * 任意 ──关闭──▶ Stopping ──▶ Off
 * ```
 *
 * INV4：开关 UI 状态由本状态流驱动，不从持久层直接读。
 */
sealed interface SearxngState {

    /** 未启用。 */
    data object Off : SearxngState

    /**
     * 首次安装中（带 progress 与 stage 文案；Spec F2/N1 —— 阶段进度更新间隔 ≤1s，
     * 进度值 0f..1f 为阶段加权估计，供 UI 确定进度条使用）。
     */
    data class Installing(val progress: Float, val stage: String) : SearxngState

    /** 进程已拉起，等待 healthz（UI 显示不确定进度 + 「启动中…」）。 */
    data object Starting : SearxngState

    /** watchdog 触发重启中（UI 同 Starting）。 */
    data class Restarting(val attempt: Int) : SearxngState

    /** 可用（UI 绿点 + 「运行中」；address 供排查展示，Spec F12）。 */
    data class Ready(val address: String) : SearxngState

    /** 失败（含 reason 与 retryable；Spec §12 失败矩阵）。 */
    data class Failed(val reason: String, val retryable: Boolean) : SearxngState

    /** 关闭中（UI 短暂 loading）。 */
    data object Stopping : SearxngState
}

/** 阶段化安装失败（Installer 归类 —— Failed.reason 的结构化来源，Spec §12）。 */
enum class SearxngInstallStage(val progressWeight: Float, val label: String) {
    /** apt 依赖安装（python3-venv/python3-pip/git）。 */
    APT(0.25f, "apt packages"),
    /** git clone SearXNG 源码。 */
    CLONE(0.45f, "git clone"),
    /** venv 创建。 */
    VENV(0.55f, "python venv"),
    /** pip install -r requirements.txt。 */
    PIP(0.95f, "pip install"),
    /** settings.yml 写入 + import 冒烟。 */
    VERIFY(1.0f, "verify install")
}

/** 一次性 guest 命令执行结果（ProotExecutor.BoundedExecution 的端口视图）。 */
data class SearxngExecResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean
) {
    val ok: Boolean get() = exitCode == 0 && !timedOut
}

/**
 * guest 命令执行端口（生产：ProotExecutor + LinuxExecutionContextFactory 适配；
 * JVM 测试：fake）。安装/探测命令全部经此端口 —— coordinator 不直接触碰 proot
 * 细节（函数端口纪律，与 UbuntuLifecycleCoordinator 同款）。
 */
interface SearxngCommandPort {

    /**
     * 在 Ubuntu 沙箱内执行一次性命令（非交互、stdout/stderr 分离、有界输出）。
     * [env] 为 guest 侧追加环境变量。
     */
    suspend fun exec(
        command: String,
        timeoutMs: Long,
        env: Map<String, String> = emptyMap()
    ): Result<SearxngExecResult>

    /**
     * 当前活跃 rootfs 的 host 侧物理目录（guest / = 该目录）。
     * null = rootfs 不可用（Ubuntu 未就绪）。
     */
    suspend fun rootfsDir(): File?
}

/**
 * 用户意图持久化端口（Spec §6 Vault 键表的语义承载 —— 生产实现为 app 侧
 * SharedPreferences 存储；BootReceiver 需无 DI 同步快读，故不走金库加密层，
 * secret_key 为本机随机数而非用户凭据，不占金库 UI）。
 */
interface SearxngIntentPort {
    /** searxng_enabled：用户意图（与运行时状态解耦，Spec §2 术语表）。 */
    fun isEnabled(): Boolean
    fun setEnabled(value: Boolean)

    /** searxng_engines：启用引擎名单（默认 [SEARXNG_DEFAULT_ENGINES]）。 */
    fun engines(): Set<String>
    fun setEngines(engines: Set<String>)

    /** searxng_port：固定 8888（预留扩展）。 */
    fun port(): Int = SearxngContract.DEFAULT_PORT

    /**
     * searxng_secret：settings.yml 的 server.secret_key。首次调用生成 64 位
     * hex 随机数并持久化复用（Spec §6：secret_key 由存储生成并复用）。
     */
    fun secretKey(): String
}
