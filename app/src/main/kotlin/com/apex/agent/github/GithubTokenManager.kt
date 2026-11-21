package com.apex.agent.github

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * GitHub 连接与偏好管理（加密存储 + 状态流）。
 *
 * ## 默认仓库契约（v3 S3，S4 设置页已按此契约接入）
 * - [defaultRepo]：`""` 未设置 | `"owner"` 用户主页 | `"owner/repo"` 仓库
 *  （落盘恒为 [GithubRepoNormalizer.canonical] 归一化后的值，永不存用户原始输入）；
 * - [saveDefaultRepo]：suspend 契约 —— 输入任意形态（完整链接 / SSH / 裸
 *  owner / owner/repo），归一化失败返回 null（不落盘），成功返回 canonical；
 * - [clearDefaultRepo]：回未设置；
 * - [saveDefaultRepoCanonical]：非挂起变体（连接对话框路径 —— 对话框已自行
 *  归一化，直存 canonical，与 [saveToken] 同步写盘风格一致）。
 *
 * 默认仓库是偏好而非凭据：[disconnect] 清凭据但保留该值（换账号后可在
 * 设置页一键清除，避免误断开连后丢配置）。
 */
@Singleton
class GithubTokenManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /**
     * 存储后端（Git 审计 P2-9 fail-closed）：EncryptedSharedPreferences 初始化
     * 失败（keystore 损坏/厂商 ROM 异常）时**不再降级明文落盘**——旧实现把
     * Token 静默写进明文 prefs（仅一条 WARN）。新实现降级为**纯内存**
     * 存储：本会话内 GitHub 连接照常可用，但 Token 不落任何盘、重启后
     * 需重新连接。启动时顺带把历史明文降级留下的 token 迁回加密存储并
     * 删除明文文件（[migrateLegacyPlaintext]）。
     */
    private val prefs: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context, "github_secure_prefs", masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            ).also { migrateLegacyPlaintext(it) }
        } catch (e: Exception) {
            runCatching {
                AppLogger.instance.warn(
                    LogCategory.SYSTEM, "GithubTokenManager",
                    "EncryptedSharedPreferences 初始化失败（${e.javaClass.simpleName}: ${e.message}）" +
                        "——Git 审计 P2-9：Token 改为纯内存存储（不落盘），重启后需重新连接 GitHub"
                )
            }
            InMemorySharedPreferences()
        }
    }

    /**
     * 历史明文迁移：旧版本在加密存储初始化失败时把 token 写进了明文
     * github_prefs_fallback——存在即迁回加密存储并删除明文文件。
     */
    private fun migrateLegacyPlaintext(secure: SharedPreferences) {
        runCatching {
            val legacy = context.getSharedPreferences("github_prefs_fallback", Context.MODE_PRIVATE)
            val legacyToken = legacy.getString(KEY_TOKEN, null)
            if (!legacyToken.isNullOrBlank()) {
                secure.edit()
                    .putString(KEY_TOKEN, legacyToken)
                    .putString(KEY_USERNAME, legacy.getString(KEY_USERNAME, null) ?: "")
                    .putLong(KEY_CONNECTED_AT, legacy.getLong(KEY_CONNECTED_AT, 0))
                    .apply()
                runCatching {
                    File(context.applicationInfo.dataDir, "shared_prefs/github_prefs_fallback.xml").delete()
                }
                AppLogger.instance.info(
                    LogCategory.SYSTEM, "GithubTokenManager",
                    "已将明文降级的旧 GitHub Token 迁回加密存储并清除明文残留"
                )
            }
        }
    }

    private val _connectionState = MutableStateFlow(loadState())
    val connectionState: StateFlow<GithubConnectionState> = _connectionState.asStateFlow()

    /**
     * 默认工作仓库（独立流，见类 KDoc 契约）：空串 = 未设置。
     * 启动时从加密存储加载；[GithubConnectionState] 不携带该字段（S4 仅消费本流）。
     */
    private val _defaultRepo = MutableStateFlow(loadDefaultRepo())
    val defaultRepo: StateFlow<String> = _defaultRepo.asStateFlow()

    fun saveToken(token: String, username: String?) {
        // Git 审计 P2-12：trim（粘贴录入常见首尾空白/换行——原样落库会让
        // 认证头带上不可见字符，且比较口径漂移）
        val trimmed = token.trim()
        prefs.edit()
            .putString(KEY_TOKEN, trimmed)
            .putString(KEY_USERNAME, username?.trim() ?: "")
            .putLong(KEY_CONNECTED_AT, System.currentTimeMillis())
            .apply()
        _connectionState.value = GithubConnectionState(true, trimmed, username, System.currentTimeMillis())
    }

    fun getToken(): String? = prefs.getString(KEY_TOKEN, null)
    fun getUsername(): String? = prefs.getString(KEY_USERNAME, null)?.ifBlank { null }
    fun isConnected(): Boolean = !getToken().isNullOrBlank()

    fun disconnect() {
        // 默认仓库是偏好非凭据：凭据清空时保留（换号/误断开不丢配置）
        val preservedRepo = runCatching { prefs.getString(KEY_DEFAULT_REPO, null).orEmpty() }
            .getOrDefault("")
        prefs.edit().clear().apply()
        if (preservedRepo.isNotBlank()) {
            runCatching { prefs.edit().putString(KEY_DEFAULT_REPO, preservedRepo).apply() }
        }
        _connectionState.value = GithubConnectionState(false)
    }

    // ═══ 默认仓库（v3 S3 契约实现，见类 KDoc）═══

    /**
     * 保存默认仓库（suspend 契约）：任意形态输入 → 归一化。
     * @return canonical（`owner` / `owner/repo`）；输入无法识别时 null（不落盘）。
     */
    suspend fun saveDefaultRepo(input: String): String? = withContext(Dispatchers.IO) {
        val ref = GithubRepoNormalizer.normalize(input)
            ?: return@withContext null
        val canonical = GithubRepoNormalizer.canonical(ref)
        persistDefaultRepo(canonical)
        canonical
    }

    /** 清除默认仓库（回到未设置）。 */
    fun clearDefaultRepo() {
        persistDefaultRepo("")
    }

    /**
     * 保存**已归一化**的默认仓库（连接对话框路径：对话框已实时归一化反馈，
     * 确认时透传 canonical）。仍经 [GithubRepoNormalizer] 往返校验防御。
     * @return false = 入参非 canonical 形态（拒绝落盘，调用方可回退提示）。
     */
    fun saveDefaultRepoCanonical(canonical: String): Boolean {
        val ref = GithubRepoNormalizer.normalize(canonical) ?: return false
        if (GithubRepoNormalizer.canonical(ref) != canonical) return false
        persistDefaultRepo(canonical)
        return true
    }

    /** 落盘 + 流更新（防御式：IO 异常折叠 + 留痕，状态流仍尽力同步）。 */
    private fun persistDefaultRepo(canonical: String) {
        val error = runCatching {
            prefs.edit().putString(KEY_DEFAULT_REPO, canonical).apply()
        }.exceptionOrNull()
        if (error != null) {
            runCatching {
                AppLogger.instance.warn(
                    LogCategory.SYSTEM, "GithubTokenManager",
                    "默认仓库落盘失败（${error.javaClass.simpleName}: ${error.message}）"
                )
            }
        }
        _defaultRepo.value = canonical
    }

    private fun loadDefaultRepo(): String =
        runCatching { prefs.getString(KEY_DEFAULT_REPO, null).orEmpty() }
            .getOrDefault("")

    // v2：共享单例 client（旧实现每次 validateToken 都 new 一个带独立连接池的
    // OkHttpClient，且非 2xx 分支不关 response —— 连接泄漏）
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    suspend fun validateToken(token: String): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://api.github.com/user")
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "ApexAgent/1.0")
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return@withContext null
                    val loginRegex = Regex("\"login\"\\s*:\\s*\"([^\"]+)\"")
                    loginRegex.find(body)?.groupValues?.get(1)
                } else null
            }
        } catch (e: Exception) { null }
    }

    private fun loadState(): GithubConnectionState {
        val token = prefs.getString(KEY_TOKEN, null)
        return GithubConnectionState(
            isConnected = !token.isNullOrBlank(),
            token = token,
            username = prefs.getString(KEY_USERNAME, null)?.ifBlank { null },
            connectedAt = prefs.getLong(KEY_CONNECTED_AT, 0)
        )
    }

    companion object {
        private const val KEY_TOKEN = "github_token"
        private const val KEY_USERNAME = "github_username"
        private const val KEY_CONNECTED_AT = "github_connected_at"
        private const val KEY_DEFAULT_REPO = "github_default_repo"
    }
}

data class GithubConnectionState(
    val isConnected: Boolean = false,
    val token: String? = null,
    val username: String? = null,
    val connectedAt: Long = 0
)

/**
 * 纯内存 SharedPreferences（Git 审计 P2-9）：加密存储初始化失败时的
 * fail-closed 降级——接口语义完整但**零落盘**，进程死亡即消失。
 * 仅承载 GithubTokenManager 的五个键；其余方法给出最小实现。
 */
internal class InMemorySharedPreferences : SharedPreferences {
    private val values = LinkedHashMap<String, Any?>()
    private val lock = Any()

    private fun get(key: String?): Any? = synchronized(lock) { values[key] }

    override fun getAll(): MutableMap<String, *> = synchronized(lock) { LinkedHashMap(values) }
    override fun getString(key: String?, defValue: String?): String? = get(key) as? String ?: defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String> =
        @Suppress("UNCHECKED_CAST")
        (get(key) as? Set<String>)?.toMutableSet() ?: defValues ?: mutableSetOf()
    override fun getInt(key: String?, defValue: Int): Int = (get(key) as? Int) ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = (get(key) as? Long) ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = (get(key) as? Float) ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = (get(key) as? Boolean) ?: defValue
    override fun contains(key: String?): Boolean = synchronized(lock) { values.containsKey(key) }
    override fun edit(): SharedPreferences.Editor = MemoryEditor()
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    private inner class MemoryEditor : SharedPreferences.Editor {
        private val pending = LinkedHashMap<String, Any?>()
        private val removals = mutableSetOf<String>()
        private var clearAll = false

        override fun putString(key: String?, value: String?) = put(key, value)
        override fun putStringSet(key: String?, values: MutableSet<String>?) = put(key, values)
        override fun putInt(key: String?, value: Int) = put(key, value)
        override fun putLong(key: String?, value: Long) = put(key, value)
        override fun putFloat(key: String?, value: Float) = put(key, value)
        override fun putBoolean(key: String?, value: Boolean) = put(key, value)
        private fun put(key: String?, value: Any?): SharedPreferences.Editor {
            key?.let { pending[it] = value }
            return this
        }

        override fun remove(key: String?): SharedPreferences.Editor {
            key?.let { removals += it }
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            clearAll = true
            return this
        }

        override fun commit(): Boolean {
            synchronized(lock) {
                if (clearAll) values.clear()
                removals.forEach { values.remove(it) }
                pending.forEach { (k, v) -> if (v != null) values[k] = v else values.remove(k) }
            }
            return true
        }

        override fun apply() {
            commit()
        }
    }
}
