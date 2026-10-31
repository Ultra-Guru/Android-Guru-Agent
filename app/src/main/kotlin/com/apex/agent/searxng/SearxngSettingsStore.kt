package com.apex.agent.searxng

import android.content.Context
import com.apex.agent.platform.terminal.searxng.SEARXNG_DEFAULT_ENGINES
import com.apex.agent.platform.terminal.searxng.SearxngIntentPort
import java.security.SecureRandom

/**
 * [SearxngIntentPort] 的生产实现 —— SharedPreferences 持久化（键语义 = Spec §6
 * Vault 键表）。
 *
 * 为什么不走金库（[com.apex.agent.vault.VaultStore]，对 Spec §6 的实现注记）：
 *  - **BootReceiver 同步快读**：BroadcastReceiver 无 Hilt 通道，开机路径需要
 *    零依赖构造即可读 searxng_enabled（与 CoreServiceGate.keepAliveEnabled 的
 *    直读 prefs 同款纪律）；金库整库 JSON + 加密层不满足该约束；
 *  - **secret_key 非用户凭据**：settings.yml 的 server.secret_key 是本机随机数
 *    （防跨实例签名伪造），不是用户资产 —— 进金库会污染用户可见的密钥列表。
 *
 * 事务语义（Spec §8）：意图写入与 action 派发为同一事务 —— setEnabled 落盘
 * （apply 异步但进程内立即可读，commit 语义满足「先写 Vault 再发 action」）。
 */
class SearxngSettingsStore(context: Context) : SearxngIntentPort {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── searxng_enabled ──────────────────────────────────────────────

    override fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    override fun setEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, value).commit()
    }

    // ── searxng_engines ──────────────────────────────────────────────

    override fun engines(): Set<String> {
        val stored = prefs.getStringSet(KEY_ENGINES, null)
        // 空集合视为「未配置」→ 默认引擎（与 SearxngSettingsWriter 归一规则一致；
        // StringSet 无法区分「空集」与「缺省」—— 故空集当缺省处理）
        return stored?.takeIf { it.isNotEmpty() } ?: SEARXNG_DEFAULT_ENGINES
    }

    override fun setEngines(engines: Set<String>) {
        prefs.edit().putStringSet(KEY_ENGINES, engines).commit()
    }

    // ── searxng_port（固定 8888，预留扩展）──────────────────────────

    override fun port(): Int = prefs.getInt(KEY_PORT, DEFAULT_PORT)

    // ── searxng_secret ───────────────────────────────────────────────

    override fun secretKey(): String {
        prefs.getString(KEY_SECRET, null)?.takeIf { it.length == SECRET_LEN }?.let { return it }
        val generated = generateSecret()
        prefs.edit().putString(KEY_SECRET, generated).commit()
        return generated
    }

    companion object {
        private const val PREFS_NAME = "searxng_prefs"
        private const val KEY_ENABLED = "searxng_enabled"
        private const val KEY_ENGINES = "searxng_engines"
        private const val KEY_PORT = "searxng_port"
        private const val KEY_SECRET = "searxng_secret"

        /** 端口缺省（SearxngContract.DEFAULT_PORT 的本地镜像 —— 避免模块环依赖的字面量重复）。 */
        private const val DEFAULT_PORT = 8888

        /** secret_key 长度（hex 字符数 —— SearXNG 建议 ≥32 字符）。 */
        private const val SECRET_LEN = 64

        /** BootReceiver 快读通道（无 DI 的同步入口；与 CoreServiceGate 同款模式）。 */
        fun isEnabledRaw(context: Context): Boolean =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, false)

        private fun generateSecret(): String {
            val bytes = ByteArray(SECRET_LEN / 2)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
