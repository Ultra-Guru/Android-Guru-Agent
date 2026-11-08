package com.apex.agent.core.tools.mcp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * MCP服务器管理器
 * 管理所有已配置的MCP连接
 *
 * ## 并发模型（v2 修复）
 * - 旧实现只有 suspend 写路径互斥（Mutex），而 `getConnectedServers()/getConfigs()/getAllTools()`
 *   是非同步裸读——UI 线程与 IO 线程并发读写 HashMap 会触发 ConcurrentModificationException 闪退。
 *   现统一用 Java 监视器锁（[lock]）保护所有可变集合，读路径返回快照，零 CME 风险。
 * - 旧实 `connect()` 在 Mutex 内执行网络初始化（最长 ~70s），期间 add/remove/disconnect 全部排队。
 *   现在**网络 IO 在锁外**执行，锁只保护集合的瞬时一致性。
 * - 旧实现重复 connect 会直接覆盖旧 client 造成连接泄漏；现在覆盖前先 `shutdown()` 旧实例。
 *
 * ## 持久化（v2 修复）
 * - 旧实现手工字符串拼 JSON，name/url/apiKey 未转义：含 `"` 或 `\` 的配置会把 mcp_servers.json
 *   写坏，下次启动 loadConfigs 解析失败并被 catch-all 静默吞掉 → **全部 MCP 配置无声丢失**。
 *   现改用 kotlinx.serialization 对 `List<McpServerConfig>` 做真正的序列化，天然转义。
 * - 落盘走「临时文件 + 原子重命名」，进程中途被杀不会留下半截文件。
 *
 * ## enabled 语义（v2 新增）
 * - `enabled=false` 的服务器：不出现在 `/` 斜杠菜单、不会被 Agent 工具看到（[getEnabledConfigs]）；
 *   禁用时主动断开其活跃连接。
 * - 修复旧实现 `enabled` 字段"只写不读"的空转状态。
 *
 * ## 连接生命周期代数（v3 修复：connect 与断开意图的竞态）
 * - `connect()` 的握手最长可达 180s（沙箱 npx 冷启动）；期间用户调用
 *   `disconnect` / `setEnabled(false)` / `removeServer` 时，旧实现只能移除
 *   「旧」 client —— 在途连接成功后直接 `put` 回 `clients`，用户刚表达的
 *   断开意图被静默丢弃，且 McpSupervisor 继续看护它。现在每台服务器持有
 *   连接代数：断开/禁用/移除时 `generation++`，connect 在注册前校验代数
 *   与配置状态，过期连接立即关闭并如实报错。
 *
 * ## 持久化线程（v3 修复：主线程磁盘 I/O）
 * - `addServer` 等写路径虽是 suspend，但旧实现内部在调用方线程同步完成
 *   JSON 序列化 + 临时文件写 + rename —— 市场页全部在主线程直调，掉帧/
 *   ANR 风险。现在落盘统一切 `Dispatchers.IO`。
 *
 * ## 内置服务器（BUILTIN 传输）
 * - core 不依赖 app 层实现：宿主（app 的 McpModule）在构造时把
 *   `服务器名 → transport 工厂` 注入 [builtinTransports]，connect 时按名取出传给
 *   [McpClient]。core 内部默认构造（空 map）行为不变，向后兼容。
 * - App 预置的内置服务器条目用 [ensureBuiltinServer] 幂等写入：同名用户自建
 *   条目（HTTP/SSE/STDIO）绝不被动持；用户对 `enabled` 的偏好跨升级保留。
 *
 * ## 沙箱 stdio（Issue #149）
 * - 宿主可注入 [sandboxProcessLauncher]（app 层 PRoot Ubuntu 沙箱实现）；
 *   配置项 `runInSandbox=true` 的 STDIO 服务器在连接时改走沙箱 launcher，
 *   在内嵌 rootfs 里解析执行 npx/python 等命令。未注入或配置未开启时，
 *   行为与旧版完全一致（宿主直接 fork），core 内部默认构造零变化。
 *
 * ## 沙箱预置（Issue #163）
 * - `runInSandbox=true` 打通了三入口启用路径：市场页添加对话框的沙箱开关、
 *   `McpConnect` 工具的 `run_in_sandbox` 参数、配置导入的 `runInSandbox`
 *   字段 —— 该字段不再处于「存在但无任何启用路径」的空转状态。
 * - App 预置的沙箱 STDIO 条目（官方 reference servers，见
 *   [SANDBOX_PRESET_SERVERS]）用 [ensureSandboxServer] 幂等写入：同名用户
 *   自建的宿主条目（runInSandbox=false）绝不被动持；用户对 `enabled` 的
 *   偏好跨升级保留；默认 enabled=false，只预置不连接。
 * - 沙箱连接的握手/请求超时按 [requestTimeoutFor] 放宽：npx 首次冷启动
 *   要下载包，60s 会在 initialize 就误判超时，放宽到 180s 兑底。
 *
 * ## GitHub 令牌桥（v3 S3 / G8）
 * - 配置的 `headers` / `env` 值里可写占位符 [GITHUB_TOKEN_PLACEHOLDER]
 *   （字面 `${GITHUB_TOKEN}`）——官方 GitHub 远程 MCP 预置（app 层
 *   OfficialGithubMcp）用它引用已连接的 PAT，**真 token 永不进入明文
 *   配置 / mcp_servers.json 落盘**（占位符原样落盘）；
 * - [connect] 时若任一值命中占位符 → 从宿主注入的 [gitHubTokenProvider]
 *   取 PAT 做子串替换（只存在于本次连接的内存 config 副本，每求每取——
 *   token 轮换后重连即生效）；未注入 provider 或未连接 GitHub → 连接
 *   失败并给出可行动文案（「GitHub 未连接，无法注入令牌」）。
 */
class McpManager(
    private val configDir: File,
    /** 内置 MCP 服务器注册表：服务器名 → transport 工厂（每次连接新实例）。 */
    private val builtinTransports: Map<String, () -> McpTransportHandle> = emptyMap(),
    /**
     * PRoot 沙箱进程启动器（Issue #149）：`runInSandbox=true` 的 STDIO
     * 服务器连接时注入 [McpClient]。null = 不支持沙箱（此类配置按旧版
     * 宿主直接 fork 处理，通常随后在握手时报「命令不存在」）。
     */
    private val sandboxProcessLauncher: McpProcessLauncher? = null,
    /**
     * #205 沙箱就绪探针（存在性探测，不是模拟）：ENV_CHECK 阶段把 rootfs
     * 真实状态写进事件 detail —— 「rootfs 未就绪」在拉起进程之前就可见，
     * 而不是等到 launcher 报错才知。null = 宿主未提供（不检查，行为与旧版一致）。
     */
    private val sandboxReadinessProbe: (() -> Boolean)? = null,
    /**
     * 诊断日志出口（HookRegistry 同款模式）：core:tool-registry 无
     * core:logging 依赖（刻意保持纯 JVM），getAllTools 等聚合路径的
     * 异常/失败现场经此回调外送。宿主注入 AppLogger，测试注入捕获列表；
     * 默认 no-op —— 既有构造点（app/di/McpModule）零改动兼容。
     */
    private val errorLog: (String) -> Unit = {},
    /**
     * GitHub PAT 供应器（v3 S3 / G8 令牌桥）：headers/env 值里的
     * [GITHUB_TOKEN_PLACEHOLDER] 占位符在 [connect] 时经此取真值填充。
     * null = 宿主未注入（含占位符的配置连接时报「未注入」——与未连接
     * 同文案）；既有构造点零改动兼容。
     */
    private val gitHubTokenProvider: (() -> String?)? = null,
    /**
     * Git 审计 P0-3：令牌桥白名单——允许解析 [GITHUB_TOKEN_PLACEHOLDER]
     * 的服务器名集合（默认仅官方 github-official 预置）。白名单外的
     * 配置（含恶意市场安装的 .mcp.json 写入
     * `Authorization: Bearer ${GITHUB_TOKEN}` + 自建 URL 的形态）一律
     * **拒绝连接**——占位符解析对任意服务器放行等于把 PAT 发给任意主机。
     */
    private val gitHubTokenBridgeServers: Set<String> = DEFAULT_GITHUB_TOKEN_BRIDGE_SERVERS,
    /** 令牌桥白名单的 URL host 集合（防官方服务器名 + 手改 URL 的组合）。 */
    private val gitHubTokenBridgeHosts: Set<String> = DEFAULT_GITHUB_TOKEN_BRIDGE_HOSTS
) {
    private val clients = LinkedHashMap<String, McpClient>()
    private val configs = LinkedHashMap<String, McpServerConfig>()

    /**
     * #488：getAllTools 并发聚合限流器——同一时刻最多
     * [AGGREGATION_CONCURRENCY] 台服务器在途 listTools。
     */
    private val aggregationLimiter = Semaphore(AGGREGATION_CONCURRENCY)

    /**
     * 连接代数（v3 修复）：服务器名 → 当前代。disconnect / setEnabled(false) /
     * removeServer 时代数 +1；connect 注册前校验代数未变，防止在途连接
     * 覆盖用户的断开意图。锁保护，快照读写。
     */
    private val generations = HashMap<String, Long>()

    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    /** 配置/连接状态变更通知（市场页、斜杠菜单订阅后自动刷新，无需手动轮询）。 */
    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
    val changes: SharedFlow<Unit> = _changes.asSharedFlow()

    /**
     * 会话生命周期监听（v4：MCP 工具一键注册进 ToolRegistry）。
     *
     * 连接成功 → [onServerConnected]；断开/移除/禁用 → [onServerDisconnected]。
     * [McpToolRegistrar] 订阅后，远程工具自动变成 first-class AgentTool。
     */
    interface SessionListener {
        fun onServerConnected(serverName: String)
        fun onServerDisconnected(serverName: String)
    }

    private val sessionListeners = mutableListOf<SessionListener>()

    /** 订阅会话生命周期（幂等；重复注册同一监听器会被忽略）。 */
    fun addSessionListener(listener: SessionListener) {
        synchronized(lock) {
            if (listener !in sessionListeners) sessionListeners += listener
        }
    }

    private fun fireConnected(serverName: String) {
        synchronized(lock) { sessionListeners.toList() }.forEach {
            runCatching { it.onServerConnected(serverName) }
        }
    }

    private fun fireDisconnected(serverName: String) {
        synchronized(lock) { sessionListeners.toList() }.forEach {
            runCatching { it.onServerDisconnected(serverName) }
        }
    }

    init {
        runCatching { configDir.mkdirs() }
        loadConfigs()
    }

    /**
     * 代数自增（断开/禁用/移除时调用）—— 在途 connect 检测到代数变化后
     * 不得注册新连接。
     */
    private fun bumpGenerationLocked(name: String) {
        generations[name] = (generations[name] ?: 0L) + 1L
    }

    /**
     * 添加MCP服务器配置
     */
    suspend fun addServer(config: McpServerConfig): Result<Unit> = withContext(Dispatchers.IO) {
        // v3 修复：落盘切 IO —— 市场页主线程直调 addServer 时，JSON 序列化 +
        // 临时文件写 + rename 不再钉死主线程。
        val error = synchronized(lock) {
            runCatching {
                configs[config.name] = config
                saveConfigsLocked()
            }.exceptionOrNull()
        }
        if (error != null) return@withContext Result.failure(error)
        notifyChanged()
        Result.success(Unit)
    }

    /** 启用/禁用服务器配置（禁用时主动断开活跃连接）。 */
    suspend fun setEnabled(name: String, enabled: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        val staleClient: McpClient?
        val error = synchronized(lock) {
            val existing = configs[name]
                ?: return@withContext Result.failure(
                    Exception("服务器「$name」尚未配置（可能刚被删除，请刷新后重试）")
                )
            // v3 修复（竞态）：禁用打断在途连接的注册资格（代数 +1）。
            if (!enabled) bumpGenerationLocked(name)
            staleClient = if (enabled) null else clients.remove(name)
            runCatching {
                configs[name] = existing.copy(enabled = enabled)
                saveConfigsLocked()
            }.exceptionOrNull()
        }
        staleClient?.let {
            runCatching { it.shutdown() }
            fireDisconnected(name)
        }
        if (error != null) return@withContext Result.failure(error)
        notifyChanged()
        Result.success(Unit)
    }

    /**
     * 连接MCP服务器（网络 IO 在锁外执行，只对配置做一致性检查）。
     *
     * #197 `startupListener`：可选的真实启动事件监听 —— ENV 检查、spawn
     * （pid/argv）、initialize 握手、initialized 通知各阶段以真实事件回调，
     * 供市场页的启动进度弹窗消费（null = 零开销，既有调用点不变）。
     */
    suspend fun connect(
        name: String,
        startupListener: McpStartupListener? = null
    ): Result<McpCapabilities> {
        val config = synchronized(lock) { configs[name] }
            ?: return Result.failure(
                Exception("服务器「$name」尚未配置（可能刚被删除，请刷新后重试）")
            )

        // P0 修复（"本地 MCP 起不来"第一因）：旧实现只在**握手成功之后**校验
        // enabled —— 市场安装一律写 enabled=false，于是用户点「启动」时进度
        // 弹窗真实闪过 ENV_CHECK/spawn/initialize，随后刚 fork 的进程被关掉，
        // 报 "was disconnected or disabled during connect"。现在在 ENV_CHECK
        // 之前就快速失败，不浪费一次进程启动，并给可行动指引。
        if (!config.enabled) {
            return Result.failure(
                Exception(
                    "服务器 '$name' 已安装但未启用：请先在市场「已安装」页打开启用开关，再点启动"
                )
            )
        }

        // v3 S3（G8 令牌桥）+ Git 审计 P0-3 白名单：headers/env 值含
        // ${GITHUB_TOKEN} 占位符时，先校验服务器/主机白名单（fail-closed），
        // 再从宿主注入的 provider 取 PAT 填充到本次连接的内存副本（占位符
        // 原样落盘，真 token 永不进 mcp_servers.json）。GitHub 未连接 →
        // 诚实失败并给出可行动指引，不拿占位符串去撞 401。
        val resolvedConfig = when (val resolution = resolveGithubTokenPlaceholders(name, config)) {
            is TokenResolution.Resolved -> resolution.config
            is TokenResolution.NotConnected -> return Result.failure(
                Exception(
                    "GitHub 未连接，无法注入令牌 — 服务器 '$name' 的配置引用了 " +
                        "$GITHUB_TOKEN_PLACEHOLDER 占位符，请先连接 GitHub「Coding 屏 " +
                        "GitHub 图标 / 设置 → GitHub」后再启用"
                )
            )
            is TokenResolution.Rejected -> return Result.failure(Exception(resolution.reason))
        }

        // #197 真实事件：环境检查（配置形态 + 沙箱就绪态；rootfs 检查同
        // ProotMcpProcessLauncher 的门径 —— 存在性探测，不是模拟）。
        // #205：沙箱就绪探针注入后就绪态写入 detail（未注入时不猜测）。
        startupListener?.onStartupEvent(
            McpStartupEvent(
                serverName = name,
                stage = McpStartupStage.ENV_CHECK,
                detail = when (config.transport) {
                    McpTransport.BUILTIN -> "内置服务器（进程内）"
                    McpTransport.STDIO -> buildString {
                        append("stdio 本地进程")
                        if (config.runInSandbox) {
                            append(" · PRoot 沙箱")
                            sandboxReadinessProbe?.let { probe ->
                                append(if (probe()) " · rootfs 就绪" else " · rootfs 未就绪（连接将失败，请先安装 Ubuntu 环境）")
                            }
                        }
                    }
                    McpTransport.HTTP, McpTransport.SSE -> "远端 ${config.transport} · ${config.url}"
                }
            )
        )

        // 先关闭旧连接，避免旧实现「直接覆盖导致连接泄漏」的问题
        synchronized(lock) { clients.remove(name) }?.let { runCatching { it.shutdown() } }

        // v3 修复（竞态）：握手前记录当前代数 —— 握手期间用户断开/禁用/
        // 移除该服务器时代数 +1，注册前校验发现代数变化即放弃注册。
        val connectGeneration = synchronized(lock) { generations[name] ?: 0L }

        // BUILTIN 传输按名取注入的工厂；HTTP/SSE/STDIO 传 null（工厂参数不参与）。
        // Issue #149：runInSandbox=true 的 STDIO 配置改走宿主注入的沙箱 launcher
        // （app 层 PRoot Ubuntu 实现）；其余情况传 null → McpClient 回退 JvmProcessLauncher。
        // Issue #163：沙箱连接同时放宽 STDIO 请求超时（npx 冷启动要下载包）。
        // v3 S3：config 用占位符已解析的副本（headers/env 里的 ${GITHUB_TOKEN}
        // → 真 PAT；未含占位符时与原配置同引用，零拷贝零行为变化）。
        val client = McpClient(
            resolvedConfig,
            builtinTransportFactory = builtinTransports[name],
            processLauncher = if (resolvedConfig.runInSandbox) sandboxProcessLauncher else null,
            stdioRequestTimeoutMs = requestTimeoutFor(resolvedConfig),
            startupListener = startupListener
        )
        val initResult = client.initialize()

        if (initResult.isSuccess) {
            // v3 修复（竞态兜底）：并发 connect 同一服务器时，后到者胜出，输家
            // 连接立即关闭；握手期间用户表达断开/禁用/移除意图（代数已变）时，
            // 在途连接同样不注册、立即关闭 —— 旧实现静默把用户刚断开的
            // 服务器拉回连接态并交给 Supervisor 看护。
            val stillCurrent = synchronized(lock) {
                (generations[name] ?: 0L) == connectGeneration &&
                    configs[name]?.enabled == true
            }
            if (stillCurrent) {
                val loser = synchronized(lock) { clients.put(name, client) }
                loser?.let { runCatching { it.shutdown() } }
                notifyChanged()
                fireConnected(name)
            } else {
                runCatching { client.shutdown() }
                // #338：文案中文化并说明可行动原因 —— 旧英文文案暗示「竞态」，
                // 而市场安装（enabled=false）后直接 connect 的用户看到的就是它。
                return Result.failure(
                    Exception(
                        "服务器「$name」在连接过程中被禁用或断开；若反复出现，" +
                            "请到已安装页检查开关状态后重试"
                    )
                )
            }
        } else {
            // STDIO 服务器在构造阶段就已 fork 出子进程；握手失败若不收尾就是进程泄漏
            runCatching { client.shutdown() }
        }
        return initResult
    }

    /**
     * 获取所有可用MCP工具（对 clients 快照迭代，锁内零网络调用）。
     *
     * #488 修复（v5 并发聚合）：旧实现串行逐台 listTools——延迟相加，
     * 任一慢源/挂起源把后面所有服务器的工具一并拖死。现在：
     * - **有界并发**：每台服务器的 listTools 在独立子协程中并行执行，
     *   [AGGREGATION_CONCURRENCY] 信号量限流——几十台服务器也不会同时
     *   打满连接与文件描述符；
     * - **单源预算**：每台独立 [AGGREGATION_BUDGET_MS] 超时（withTimeoutOrNull），
     *   慢源超时降级为「该台零工具 + timeout 留痕」，健康源照常返回，
     *   互不拖累；预算只在真正开始请求后才计时（信号量排队时间不计入）；
     * - **稳定顺序**：结果按快照顺序（LinkedHashMap 注册序）拼接，
     *   并发完成先后不影响工具顺序；
     * - 留痕与取消纪律不变：Result 失败与抛出异常经 [errorLog] 外送
     *   （不吞）；调用方取消（非预算产生的 CancellationException）照常
     *   重抛——预算超时被 withTimeoutOrNull 折叠为 null 返回值，不会与
     *   调用方取消混淆。
     */
    suspend fun getAllTools(): List<McpToolDef> {
        val snapshot = synchronized(lock) { clients.entries.map { it.key to it.value } }
        if (snapshot.isEmpty()) return emptyList()
        return coroutineScope {
            snapshot.map { (serverName, client) ->
                async { aggregateServerToolsWithBudget(serverName, client) }
            }.awaitAll().flatten()
        }
    }

    /**
     * [getAllTools] 的单台聚合：限流并发 + 预算超时 + 失败留痕。
     *
     * 超时/失败/异常均折叠为该台的空清单（其余服务器不受影响），
     * 唯一重抛的是调用方自己的取消（CancellationException）。
     */
    private suspend fun aggregateServerToolsWithBudget(
        serverName: String,
        client: McpClient
    ): List<McpToolDef> = aggregationLimiter.withPermit {
        try {
            val result = withTimeoutOrNull(AGGREGATION_BUDGET_MS) { client.listTools() }
            if (result == null) {
                errorLog(
                    "McpManager.getAllTools: server '$serverName' listTools timeout " +
                        "after ${AGGREGATION_BUDGET_MS}ms (aggregation budget) — " +
                        "source skipped, other servers unaffected"
                )
                return@withPermit emptyList()
            }
            result.fold(
                onSuccess = { it },
                onFailure = { e ->
                    errorLog(
                        "McpManager.getAllTools: server '$serverName' listTools failed: " +
                            "${e.message ?: e::class.simpleName}"
                    )
                    emptyList()
                }
            )
        } catch (e: CancellationException) {
            // 只可能是调用方取消（预算超时已折叠为 null，不会走到这里）——重抛
            throw e
        } catch (e: Exception) {
            errorLog(
                "McpManager.getAllTools: server '$serverName' listTools threw: " +
                    "${e.message ?: e::class.simpleName}"
            )
            emptyList()
        }
    }

    /**
     * 列出单台服务器的工具（v4：[McpToolRegistrar] 逐台注册 first-class 工具用）。
     * 服务器未连接时返回空列表——调用方据此跳过注册。
     */
    suspend fun listServerTools(serverName: String): Result<List<McpToolDef>> {
        // P1 修复（工具整体消失）：旧实现把"未连接/发现失败"一律折叠成
        // emptyList，[McpToolRegistrar] 把空清单当"服务器不再提供工具"，
        // 于是一次 tools/list 超时就把该服务器已注册的一等工具全部注销。
        // 现在失败与"空清单"是两件事，调用方据此决定是否保留既有注册。
        val client = synchronized(lock) { clients[serverName] }
            // #354 ④：文案经 MarketViewModel 的「工具发现失败：…」直达用户 ——
            // 旧英文句子混在中文提示里，且没说用户能做什么。
            ?: return Result.failure(
                Exception("服务器「$serverName」未连接，无法列出工具；请先在该服务器的设置里连接后重试")
            )
        return client.listTools()
    }

    /**
     * 调用MCP工具
     */
    suspend fun callTool(serverName: String, toolName: String, arguments: String): Result<McpToolResult> {
        val client = synchronized(lock) { clients[serverName] }
            // #354 ④：同上 —— 工具调用失败会原样进模型上下文与用户提示。
            ?: return Result.failure(
                Exception("服务器「$serverName」未连接，无法调用工具「$toolName」；请先连接该服务器")
            )
        return client.callTool(toolName, arguments)
    }

    /**
     * 断开连接
     */
    suspend fun disconnect(name: String) {
        // v3 修复（竞态）：代数 +1 使在途 connect 的注册前置校验失败。
        synchronized(lock) { bumpGenerationLocked(name) }
        val client = synchronized(lock) { clients.remove(name) }
        client?.let {
            runCatching { it.shutdown() }
            notifyChanged()
            fireDisconnected(name)
        }
    }


    /**
     * 获取已连接服务器列表（快照读，线程安全）。
     */
    fun getConnectedServers(): List<String> = synchronized(lock) { clients.keys.toList() }

    /**
     * 已连接服务器的使用指引快照（MCP 规范 initialize 响应的 `instructions`
     * 字段；未上报的服务器不出现在结果里）。
     *
     * 消费方：标准任务循环引擎（经适配层）——服务器指引注入系统提示词的
     * `<mcp_instructions>` 段（仅当该服务器工具在当前工具面上，防幻觉指引）。
     */
    fun getServerInstructions(): Map<String, String> = synchronized(lock) {
        clients.entries.mapNotNull { (name, client) ->
            runCatching { client.serverInstructions }.getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { name to it }
        }.toMap()
    }

    /**
     * 健康探针（[McpSupervisor] 看门狗用）：已连接且底层传输仍存活。
     *
     * - 未连接 → false；
     * - HTTP/SSE 无状态传输 → 恒 true（探针无分辨力，也无需恢复）；
     * - STDIO → 子进程真实存活（[McpClient.isTransportAlive]）；
     * - BUILTIN → 进程内实现自报。
     */
    fun isClientHealthy(name: String): Boolean {
        val client = synchronized(lock) { clients[name] } ?: return false
        return runCatching { client.isTransportAlive() }.getOrDefault(false)
    }

    /**
     * 获取所有配置（快照读，线程安全）。
     */
    fun getConfigs(): List<McpServerConfig> = synchronized(lock) { configs.values.toList() }

    /**
     * 获取所有**已启用**的配置——斜杠菜单与 Agent 工具的可见性依据。
     */
    fun getEnabledConfigs(): List<McpServerConfig> =
        synchronized(lock) { configs.values.filter { it.enabled } }

    /**
     * 幂等预置一台内置 MCP 服务器（App 启动时调用，如内置 GitHub）。
     *
     * - 无同名条目 → 写入 [config]；
     * - 同名条目已是 BUILTIN → 按传入定义刷新，但**保留用户 enabled 偏好**
     *   （升级后新增字段/默认值得以下发，用户手动禁用的不会被重新打开）；
     * - 同名条目是用户自建（HTTP/SSE/STDIO）→ 不动它（绝不劫持用户配置）。
     */
    suspend fun ensureBuiltinServer(config: McpServerConfig): Result<Unit> {
        val error = synchronized(lock) {
            val existing = configs[config.name]
            if (existing != null && existing.transport != McpTransport.BUILTIN) {
                return Result.failure(
                    Exception("服务器 '${config.name}' 已被用户自建为 ${existing.transport}，不覆盖用户配置")
                )
            }
            val merged = existing?.let { config.copy(enabled = it.enabled) } ?: config
            runCatching {
                configs[config.name] = merged
                saveConfigsLocked()
            }.exceptionOrNull()
        }
        if (error != null) return Result.failure(error)
        notifyChanged()
        return Result.success(Unit)
    }

    /**
     * 删除配置（同时断开活跃连接）。
     */
    suspend fun removeServer(name: String) = withContext(Dispatchers.IO) {
        // v3 修复（竞态）：代数 +1 拦截在途 connect；落盘切 IO 线程。
        synchronized(lock) { bumpGenerationLocked(name) }
        val client = synchronized(lock) { clients.remove(name) }
        client?.let {
            runCatching { it.shutdown() }
            fireDisconnected(name)
        }
        synchronized(lock) {
            configs.remove(name)
            runCatching { saveConfigsLocked() }
        }
        notifyChanged()
    }

    /**
     * 幂等预置一台**沙箱** STDIO MCP 服务器（Issue #163，App 启动时调用，
     * 预置清单见 [SANDBOX_PRESET_SERVERS]）。
     *
     * 语义仿 [ensureBuiltinServer]，但面向 `runInSandbox=true` 的 STDIO 条目
     * （BUILTIN 校验会拒绝沙箱配置，所以另开方法）：
     * - 无同名条目 → 写入 [config]（预置默认 enabled=false，只预置不连接）；
     * - 同名条目已是沙箱条目 → 按传入定义刷新 command/args，但**保留用户
     *   enabled 偏好**（用户手动启用的不会被重新关掉，反之亦然）；
     * - 同名条目是用户自建（runInSandbox=false 的宿主 STDIO/远端条目）→
     *   不动它并返回 failure（防劫持：绝不把用户配置悄悄改写成沙箱形态）。
     */
    suspend fun ensureSandboxServer(config: McpServerConfig): Result<Unit> {
        val error = synchronized(lock) {
            val existing = configs[config.name]
            if (existing != null && !existing.runInSandbox) {
                return Result.failure(
                    Exception("服务器 '${config.name}' 已被用户自建为非沙箱条目（runInSandbox=false），不覆盖用户配置")
                )
            }
            val merged = existing?.let { config.copy(enabled = it.enabled) } ?: config
            runCatching {
                configs[config.name] = merged
                saveConfigsLocked()
            }.exceptionOrNull()
        }
        if (error != null) return Result.failure(error)
        notifyChanged()
        return Result.success(Unit)
    }

    /**
     * GitHub 令牌占位符解析结果（Git 审计 P0-3 引入三分态）。
     */
    private sealed class TokenResolution {
        /** 无占位符（零拷贝原样）或已解析注入的配置副本。 */
        data class Resolved(val config: McpServerConfig) : TokenResolution()

        /** 含占位符但 GitHub 未连接——调用方给「先连接 GitHub」文案。 */
        object NotConnected : TokenResolution() {
            override fun toString(): String = "NotConnected"
        }

        /** 含占位符但服务器/主机不在令牌桥白名单——fail-closed 拒绝连接。 */
        data class Rejected(val reason: String) : TokenResolution()
    }

    /**
     * 令牌桥白名单判定（Git 审计 P0-3，internal 供单测穷举）：
     * 服务器名与 URL host 都必须命中白名单。host 解析防御式——URI 异常、
     * 空 url（STDIO 内置形态）一律 false。
     */
    internal fun githubTokenBridgeAllowed(serverName: String, url: String): Boolean {
        if (serverName !in gitHubTokenBridgeServers) return false
        val host = runCatching { java.net.URI(url).host }.getOrNull()
        return host != null && host in gitHubTokenBridgeHosts
    }

    /**
     * GitHub 令牌占位符解析（v3 S3 / G8 令牌桥 + Git 审计 P0-3 白名单）：
     *
     * - headers / env 值均不含 [GITHUB_TOKEN_PLACEHOLDER] → 原样返回（零拷贝）；
     * - 含占位符 → 白名单校验（[githubTokenBridgeAllowed]）：服务器名 +
     *   URL host 双命中才继续；否则 [TokenResolution.Rejected]（fail-closed
     *   拒绝连接——恶意配置写 `Authorization: Bearer ${GITHUB_TOKEN}` +
     *   自建 URL 即可在 connect 时拿到 PAT 的旧通路就此关闭）；
     * - 白名单命中 → 从 [gitHubTokenProvider] 取 PAT（null/空白 = 未连接）
     *   做子串替换，返回仅存于本次连接的内存副本。
     *
     * provider 读取侧防御式：抛异常折叠为未连接（不把宿主异常穿透到连接层）。
     */
    private fun resolveGithubTokenPlaceholders(
        serverName: String,
        config: McpServerConfig
    ): TokenResolution {
        val hasPlaceholder = config.headers.values.any { it.contains(GITHUB_TOKEN_PLACEHOLDER) } ||
            config.env.values.any { it.contains(GITHUB_TOKEN_PLACEHOLDER) }
        if (!hasPlaceholder) return TokenResolution.Resolved(config)
        if (!githubTokenBridgeAllowed(serverName, config.url)) {
            errorLog(
                "McpManager.connect('$serverName'): config references " +
                    "$GITHUB_TOKEN_PLACEHOLDER but the server is NOT in the GitHub token " +
                    "bridge allowlist (Git audit P0-3) — connection rejected"
            )
            return TokenResolution.Rejected(
                "服务器 '$serverName' 的配置引用了 $GITHUB_TOKEN_PLACEHOLDER 占位符，但它不在 " +
                    "GitHub 令牌桥白名单内（仅官方 github-official 预置支持）。" +
                    "为防凭据被第三方 MCP 配置窃取，占位符解析已拒绝该连接：请删除该占位符，" +
                    "或使用官方预置（市场 → GitHub 官方远程 MCP）"
            )
        }
        val token = runCatching { gitHubTokenProvider?.invoke() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: run {
                errorLog(
                    "McpManager.connect('$serverName'): config references " +
                        "$GITHUB_TOKEN_PLACEHOLDER but no GitHub PAT is available"
                )
                return TokenResolution.NotConnected
            }
        return TokenResolution.Resolved(
            config.copy(
                headers = config.headers.mapValues { (_, v) ->
                    v.replace(GITHUB_TOKEN_PLACEHOLDER, token)
                },
                env = config.env.mapValues { (_, v) ->
                    v.replace(GITHUB_TOKEN_PLACEHOLDER, token)
                }
            )
        )
    }

    private fun saveConfigsLocked() {
        val file = File(configDir, "mcp_servers.json")
        val jsonStr = json.encodeToString(configs.values.toList())
        // 原子写：先写临时文件再重命名，进程中途被杀不会损坏配置
        val tmp = File(configDir, "mcp_servers.json.tmp")
        tmp.writeText(jsonStr)
        if (!tmp.renameTo(file)) {
            // 部分文件系统跨 inode rename 失败时退化为直接写
            file.writeText(jsonStr)
            tmp.delete()
        }
    }

    private fun loadConfigs() {
        val file = File(configDir, "mcp_servers.json")
        if (!file.exists()) return
        try {
            val content = file.readText()
            if (content.isBlank()) return
            val loaded = json.decodeFromString<List<McpServerConfig>>(content)
            synchronized(lock) {
                loaded.forEach { configs[it.name] = it }
            }
        } catch (e: Exception) {
            // 配置损坏时保留默认空态并尝试备份，绝不再静默吞掉现场
            runCatching {
                file.copyTo(File(configDir, "mcp_servers.json.corrupt"), overwrite = true)
            }
        }
    }

    private fun notifyChanged() {
        _changes.tryEmit(Unit)
    }

    companion object {
        /**
         * GitHub PAT 占位符（v3 S3 / G8 令牌桥）：写在配置 headers/env 值里，
         * [connect] 时经 [McpManager] 的 gitHubTokenProvider 解析为真 PAT。
         * Git 审计 P0-3 起仅白名单服务器可解析（默认官方 github-official
         * 预置；自建配置写占位符将被拒绝连接）。注意 Kotlin 源码内写法：
         * "\${GITHUB_TOKEN}"（$ 转义防模板插值）。
         */
        const val GITHUB_TOKEN_PLACEHOLDER = "\${GITHUB_TOKEN}"

        /** 令牌桥白名单默认服务器名（app 层 OfficialGithubMcp.SERVER_NAME 同源）。 */
        const val DEFAULT_GITHUB_TOKEN_BRIDGE_SERVER = "github-official"

        /** 令牌桥白名单默认 host（官方远程 MCP 端点；api.github.com 预留）。 */
        val DEFAULT_GITHUB_TOKEN_BRIDGE_SERVERS = setOf(DEFAULT_GITHUB_TOKEN_BRIDGE_SERVER)
        val DEFAULT_GITHUB_TOKEN_BRIDGE_HOSTS = setOf("api.githubcopilot.com", "api.github.com")

        /**
         * #488：getAllTools 单台服务器的聚合预算（毫秒）。
         *
         * 聚合是「尽力而为的全景快照」而不是单台命令：一次工具清单请求
         * 正常在百毫秒量级，5s 已覆盖沙箱 npx 热启动后的常规响应；超过
         * 预算的慢源降级为空清单 + timeout 留痕，绝不拖住健康源
         * （单台诊断可走 [listServerTools]，无聚合预算）。
         */
        const val AGGREGATION_BUDGET_MS = 5_000L

        /**
         * #488：getAllTools 并发聚合上限——超过的请求在信号量处排队
         * （排队不计时，真正开始请求后才进入预算窗口）。
         */
        const val AGGREGATION_CONCURRENCY = 8

        /**
         * 沙箱 STDIO 连接的握手/请求超时（Issue #163）。
         *
         * npx 首次冷启动要下载包（-y 时先拉 tarball 再装依赖再起进程），
         * 移动网络下 60s 走不完 —— 与其让用户在 initialize 阶段就撞超时，
         * 不如给沙箱连接单独放宽到 180s；仅作用于 runInSandbox=true 的
         * STDIO 配置，宿主 fork 与 HTTP/SSE 行为不变。
         */
        const val SANDBOX_REQUEST_TIMEOUT_MS = 180_000L

        /**
         * 按配置选择 STDIO 请求超时（纯函数，Issue #163 抽出便于单测）：
         * 沙箱 STDIO → [SANDBOX_REQUEST_TIMEOUT_MS]；其余（含 HTTP/SSE ——
         * 它们不走 STDIO 超时参数）→ [McpClient.HOST_STDIO_REQUEST_TIMEOUT_MS]。
         */
        fun requestTimeoutFor(config: McpServerConfig): Long =
            if (config.runInSandbox && config.transport == McpTransport.STDIO) {
                SANDBOX_REQUEST_TIMEOUT_MS
            } else {
                McpClient.HOST_STDIO_REQUEST_TIMEOUT_MS
            }

        /**
         * 沙箱预置清单（Issue #163）—— 官方 reference servers，node 纯 JS
         * 实现，arm64 兼容（无原生模块，npx 在 PRoot Ubuntu 内直接可跑）。
         *
         * - `fs-sandbox`：沙箱内文件系统读写（/workspace 作用域）；
         * - `memory-sandbox`：知识图谱记忆（原定 git-sandbox —— 但
         *   `@modelcontextprotocol/server-git` 已从 npm 下架（registry 返回
         *   Not Found），换成同厂官方 server-memory）；
         * - `everything-sandbox`：官方测试服务器（覆盖全部 MCP 能力面：
         *   工具/资源/提示词/采样，验证沙箱链路用）。
         *
         * 全部 enabled=false：只预置不连接 —— rootfs 未就绪也先写入，连接
         * 失败发生在用户主动启用时，ProotMcpProcessLauncher 已有引导性报错。
         */
        val SANDBOX_PRESET_SERVERS: List<McpServerConfig> = listOf(
            McpServerConfig(
                name = "fs-sandbox",
                transport = McpTransport.STDIO,
                command = "npx",
                args = listOf("-y", "@modelcontextprotocol/server-filesystem", "/workspace"),
                runInSandbox = true,
                enabled = false,
                scope = "coding"
            ),
            McpServerConfig(
                name = "memory-sandbox",
                transport = McpTransport.STDIO,
                command = "npx",
                args = listOf("-y", "@modelcontextprotocol/server-memory"),
                runInSandbox = true,
                enabled = false,
                scope = "agent"
            ),
            McpServerConfig(
                name = "everything-sandbox",
                transport = McpTransport.STDIO,
                command = "npx",
                args = listOf("-y", "@modelcontextprotocol/server-everything"),
                runInSandbox = true,
                enabled = false,
                scope = "coding"
            )
        )
    }
}
