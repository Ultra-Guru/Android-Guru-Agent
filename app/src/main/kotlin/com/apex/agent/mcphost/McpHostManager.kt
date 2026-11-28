package com.apex.agent.mcphost

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import com.apex.agent.core.tools.ToolExecutor
import com.apex.agent.core.tools.ToolRegistry
import com.apex.agent.github.InMemorySharedPreferences
import com.apex.agent.platform.mcphost.McpHostBridge
import com.apex.agent.platform.mcphost.McpHostConfig
import com.apex.agent.platform.mcphost.McpHostConfigStore
import com.apex.agent.platform.mcphost.McpHostServer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import javax.inject.Provider

/**
 * # 逆向 MCP Host 管理器（app 层接线）
 *
 * 持有 [McpHostServer] 生命周期与配置持久化：
 * - 非敏感配置（开关/端口/白名单…）经 SharedPreferences +
 *   kotlinx.serialization JSON 持久化；
 * - **token 加密存储（#818 / S-07 提前落地）**：Bearer Token 迁入
 *   [EncryptedSharedPreferences]（`mcp_host_secure_prefs`，与 vault /
 *   GitHub token 同款信封）；明文 JSON 的 token 字段**恒置空落盘**。
 *   加密初始化失败（keystore 损坏/厂商 ROM 异常）fail-closed 降级为
 *   纯内存（本会话可用，重启后重新生成——绝不回退明文落盘）；
 * - 首启自动生成 token；旧版本明文 JSON 残留的 token 启动时自动迁移
 *   进加密存储并抹除明文；
 * - token 引导是惰性异步的（IO 协程 + 首次访问才构造加密存储）——
 *   本单例经 [com.apex.agent.di.McpHostModule] 在 ApexApp onCreate
 *   主线程触发创建，构造路径零 Keystore/Tink 开销（对齐 #861 启动税
 *   方向）；
 * - `enabled=true` 时自动 start（token 引导完成后）；端口或绑定面
 *   （allowLan）变更自动重启；
 * - serverInfo.version 注入 BuildConfig.VERSION_NAME（S-04：消除与
 *   versionName 的硬编码漂移）；
 * - 不触碰 McpManager/McpModule（内置 MCP transport 由并行工作流维护）。
 *
 * ## token 并发模型
 * token 的全部读改写（启动引导 / [regenerateToken] / [updateConfig] 落盘）
 * 经 [tokenLock] 串行；`_config.token` 是运行期唯一真相源投影，UI 透传
 * 的 token 字段一律不采信。引导完成前 server 即使已起也是空 token
 * fail-closed（拒绝所有请求），毫秒级窗口无安全损失。
 *
 * 绑定见 McpHostModule（@Provides @Singleton，无 @Inject 构造绑定避免重复）。
 */
class McpHostManager(
    @ApplicationContext context: Context,
    registry: ToolRegistry,
    // Provider 注入：Hilt 惰性解析执行器（v3 管线 + SecretRedactingExecutor 包装，
    // 注入 ToolExecutor 类型即可拿到完整门控链）
    private val executorProvider: Provider<ToolExecutor>
) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("mcp_host_prefs", Context.MODE_PRIVATE)

    /**
     * token 加密存储（#818）：惰性构造（首次访问才初始化 Keystore + Tink
     * —— 构造路径在 IO 协程内，主线程零成本）；初始化失败 fail-closed
     * 降级纯内存（[InMemorySharedPreferences]），不落明文。
     */
    private val securePrefs: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context, SECURE_PREFS_NAME, masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            runCatching {
                AppLogger.instance.warn(
                    LogCategory.TOOL, "McpHostManager",
                    "EncryptedSharedPreferences 初始化失败（${e.javaClass.simpleName}: ${e.message}）" +
                        "——#818 fail-closed：token 改为纯内存存储（不落盘），重启后重新生成"
                )
            }
            InMemorySharedPreferences()
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** 配置持久化（JSON 单键；token 字段恒置空 —— 真实存储在 [securePrefs]）。 */
    private val store: McpHostConfigStore = object : McpHostConfigStore {
        override fun load(): McpHostConfig {
            val raw = prefs.getString(KEY_CONFIG, null) ?: return McpHostConfig()
            return runCatching { json.decodeFromString(McpHostConfig.serializer(), raw) }
                .getOrElse {
                    AppLogger.instance.warn(
                        LogCategory.TOOL, "McpHostManager",
                        "config decode failed, fallback to defaults: ${it.message}"
                    )
                    McpHostConfig()
                }
        }

        override fun save(config: McpHostConfig) {
            prefs.edit()
                .putString(
                    KEY_CONFIG,
                    json.encodeToString(
                        McpHostConfig.serializer(),
                        config.copy(token = "")  // #818：token 永不落明文 JSON
                    )
                )
                .apply()
        }
    }

    private val scope = com.apex.agent.di.AppScopes.io("McpHostManager")

    /** token 读改写串行锁（见类 KDoc 并发模型；锁内无挂起点）。 */
    private val tokenLock = Any()

    /** 引导前的明文快照（legacy token 迁移源）。 */
    private val initialConfig: McpHostConfig = store.load()

    /** 运行态配置：token 先空（fail-closed），IO 协程引导后填充。 */
    private val _config = MutableStateFlow(initialConfig.copy(token = ""))
    val config: StateFlow<McpHostConfig> = _config.asStateFlow()

    /** 服务运行态 / 会话 / 审计（未启动时为空值流）。 */
    val isRunning: StateFlow<Boolean> get() = server.isRunning
    /** 真实监听地址（来自 ServerSocket 绑定参数；未监听为 null）。UI 不反推 allowLan。 */
    val bindHost: StateFlow<String?> get() = server.bindHost
    val sessions: StateFlow<List<com.apex.agent.platform.mcphost.HostSessionInfo>> get() = server.sessions
    val auditLog: StateFlow<List<com.apex.agent.platform.mcphost.HostAuditEntry>> get() = server.auditLog

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val bridge = McpHostBridge(
        registry = registry,
        executor = executorProvider.get(),
        configProvider = { _config.value }
    )

    private val server = McpHostServer(
        configProvider = { _config.value },
        bridge = bridge,
        scope = scope,
        // S-04：serverInfo.version 与 versionName 单一事实源（曾 1.4.4 vs 1.5 漂移）
        serverVersion = com.apex.agent.BuildConfig.VERSION_NAME
    )

    init {
        // token 引导（IO 协程）：读加密存储 → 迁移旧明文残留 → 首启生成 →
        // 回填运行态；enabled 时随后自启（构造路径零加密开销，见类 KDoc）。
        scope.launch {
            synchronized(tokenLock) {
                val token = loadOrSeedToken()
                if (_config.value.token != token) {
                    _config.value = _config.value.copy(token = token)
                }
            }
            if (_config.value.enabled) start()
        }
    }

    /**
     * token 引导三态（锁内调用）：
     * 1. 加密存储已有 → 直接采信；明文 JSON 若有历史残留一并抹除；
     * 2. 加密存储为空、旧明文 JSON 有（升级路径）→ 迁入加密存储 + 抹除
     *    明文 —— 迁移失败（加密存储不可写）同样抹明文：内存态本会话
     *    继续可用，重启后重新生成（与 [com.apex.agent.github.GithubTokenManager]
     *    的 fail-closed 降级同款口径，绝不回退明文落盘）；
     * 3. 两处皆空（首启）→ SecureRandom 生成 32 位随机串写入加密存储；
     *    写入失败仅内存（重启重新生成，fail-closed）。
     */
    private fun loadOrSeedToken(): String {
        val secure = runCatching { securePrefs.getString(KEY_TOKEN, null).orEmpty() }
            .getOrDefault("")
        val legacy = initialConfig.token
        return when {
            secure.isNotEmpty() -> {
                // 加密侧为准：明文残留（历史版本写入）就地抹除
                if (legacy.isNotEmpty()) store.save(_config.value)
                secure
            }
            legacy.isNotEmpty() -> {
                val persisted = runCatching {
                    securePrefs.edit().putString(KEY_TOKEN, legacy).apply()
                }.isSuccess
                // 无论迁移成败都抹明文：成 → 加密侧唯一副本；败 → 内存态
                // 继续可用，但明文不再留存（重启后重新生成，fail-closed）
                store.save(_config.value)
                if (persisted) {
                    AppLogger.instance.info(
                        LogCategory.TOOL, "McpHostManager",
                        "MCP Host token 已由明文 JSON 迁入加密存储并抹除明文残留（#818）"
                    )
                } else {
                    AppLogger.instance.warn(
                        LogCategory.TOOL, "McpHostManager",
                        "MCP Host token 加密迁移写入失败——本会话内存可用，明文残留已抹除，" +
                            "重启后将重新生成（fail-closed）"
                    )
                }
                legacy
            }
            else -> {
                val seeded = McpHostConfig.generateToken()
                val persisted = runCatching {
                    securePrefs.edit().putString(KEY_TOKEN, seeded).apply()
                }.isSuccess
                if (!persisted) {
                    AppLogger.instance.warn(
                        LogCategory.TOOL, "McpHostManager",
                        "MCP Host token 生成后未能写入加密存储——本会话可用，重启后重新生成"
                    )
                }
                seeded
            }
        }
    }

    /** 启动服务（幂等；端口绑定失败记录 lastError 不抛出）。 */
    fun start() {
        try {
            server.start()
            _lastError.value = null
            AppLogger.instance.info(
                LogCategory.TOOL, "McpHostManager",
                "reverse MCP host listening on port ${_config.value.effectivePort}"
            )
        } catch (e: Exception) {
            _lastError.value = e.message ?: e::class.simpleName
            AppLogger.instance.error(
                LogCategory.TOOL, "McpHostManager",
                "start failed on port ${_config.value.effectivePort}: ${e.message}"
            )
        }
    }

    /** 停止服务（幂等）。 */
    fun stop() {
        server.stop()
        AppLogger.instance.info(LogCategory.TOOL, "McpHostManager", "reverse MCP host stopped")
    }

    /** 重新生成 token（UI 确认对话框后调用；已连接会话下次请求即失效）。 */
    fun regenerateToken(): String {
        val newToken = McpHostConfig.generateToken()
        synchronized(tokenLock) {
            val persisted = runCatching {
                securePrefs.edit().putString(KEY_TOKEN, newToken).apply()
            }.isSuccess
            if (!persisted) {
                AppLogger.instance.warn(
                    LogCategory.TOOL, "McpHostManager",
                    "新 token 未能写入加密存储——仅本会话生效，重启后回到旧值"
                )
            }
            _config.value = _config.value.copy(token = newToken)
        }
        return newToken
    }

    /**
     * 更新配置并同步生命周期：
     * - enabled off → stop；on → start；
     * - 端口或绑定面（allowLan）变化且运行中 → 重启生效（绑定地址是
     *   ServerSocket 构造参数，无法热切换）；
     * - token/白名单/黑名单/限速/写类暴露开关 → 即时生效（configProvider
     *   每请求读取）。
     *
     * token 字段不采信入参（唯一真相源 = 内存态 + [securePrefs]，见类
     * KDoc 并发模型）。
     */
    fun updateConfig(newConfig: McpHostConfig) {
        val old = _config.value
        val normalized = newConfig.copy(
            port = McpHostConfig.sanitizePort(newConfig.port),
            rateLimitPerMinute = McpHostConfig.sanitizeRateLimit(newConfig.rateLimitPerMinute),
            token = old.token
        )
        synchronized(tokenLock) {
            store.save(normalized)
            _config.value = normalized
        }

        val portChanged = old.effectivePort != normalized.effectivePort
        val bindChanged = old.allowLan != normalized.allowLan
        val wasRunning = server.isRunning.value
        when {
            !normalized.enabled && wasRunning -> stop()
            normalized.enabled && !wasRunning -> start()
            normalized.enabled && wasRunning && (portChanged || bindChanged) -> {
                stop()
                start()
            }
            else -> Unit
        }
    }

    /** 白名单分类切换（UI FilterChip 点击）。 */
    fun toggleCategory(categoryName: String) {
        val current = _config.value
        val next = if (current.allowedCategories.any { it.equals(categoryName, true) }) {
            current.copy(allowedCategories = current.allowedCategories.filterNot {
                it.equals(categoryName, true)
            })
        } else {
            current.copy(allowedCategories = current.allowedCategories + categoryName)
        }
        updateConfig(next)
    }

    companion object {
        private const val KEY_CONFIG = "mcp_host_config_json"

        /** token 加密存储键（[SECURE_PREFS_NAME] 内）。 */
        private const val KEY_TOKEN = "mcp_host_bearer_token"

        /**
         * token 加密存储文件名（check_backup_exclusions.py 凭据语义口径：
         * 名含 secure —— 已在 data_extraction_rules / legacy_backup_rules
         * 三通道排除，密文随备份迁移到新机必触 KeyStore 失配）。
         */
        private const val SECURE_PREFS_NAME = "mcp_host_secure_prefs"
    }
}
