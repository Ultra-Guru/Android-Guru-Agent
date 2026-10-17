package com.apex.agent.changelog

import android.content.Context
import android.content.SharedPreferences

/**
 * 更新日志已读状态（changelog 通道 v1）—— 独立 prefs 文件，不进 AgentSettings：
 * 弹窗纪律是「版本到达」语义，与用户偏好（JSON 演进、云备份面）解耦，
 * 字段增删永不触碰 agent_settings_v2 的 schema。
 *
 * 三把钥匙：
 * - [KEY_SEEN] —— 已读的「本版日志」versionCode：升级/热更后首启
 *   （effectiveVersionCode > seen）弹「本次更新内容」，关闭即落 seen；
 * - [KEY_SEEN_AVAILABLE] —— 已读的「新版本可用」versionCode：远端领先时
 *   弹「发现新版本」。与 [KEY_SEEN] 分离是刻意的 —— 关掉「发现新版本」
 *   不应吞掉升级后的「本次更新内容」（两者版本口径不同）；
 * - [KEY_BASELINE] —— 首启基线：全新安装不打扰（直接记当前版本为已读）。
 *   通道上线首启（老用户升级到带本模块的版本）同样走基线初始化 ——
 *   第一个弹窗从下一版起生效，避免「半路杀出」的突兀感。
 *
 * 版本口径：max(包 versionCode, 已应用热更目标) —— 与
 * UpdateCenter.hotStore.effectiveVersionCode 同源，增量/全量/热更三通道统一。
 */
class SeenChangelogStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * 首启基线初始化。首次调用（无基线标记）写入 seen = 当前版本并返回
     * true（= 本次启动不弹「本版日志」，全新安装/通道首启均此语义）；
     * 此后恒返回 false。
     */
    fun ensureBaseline(currentVersionCode: Int): Boolean {
        if (prefs.getBoolean(KEY_BASELINE, false)) return false
        prefs.edit()
            .putBoolean(KEY_BASELINE, true)
            .putInt(KEY_SEEN, currentVersionCode)
            .apply()
        return true
    }

    /** 已读的「本版日志」versionCode（0 = 基线前的理论初值）。 */
    fun seenVersionCode(): Int = prefs.getInt(KEY_SEEN, 0)

    /** 关闭「本次更新内容」弹窗后落已读（幂等，单调只增）。 */
    fun markSeen(versionCode: Int) {
        if (versionCode > seenVersionCode()) {
            prefs.edit().putInt(KEY_SEEN, versionCode).apply()
        }
    }

    /** 已读的「新版本可用」versionCode（0 = 从未就此提醒）。 */
    fun seenAvailableVersionCode(): Int = prefs.getInt(KEY_SEEN_AVAILABLE, 0)

    /** 关闭「发现新版本」弹窗后落已读（每远端版本至多提醒一次）。 */
    fun markAvailableSeen(versionCode: Int) {
        if (versionCode > seenAvailableVersionCode()) {
            prefs.edit().putInt(KEY_SEEN_AVAILABLE, versionCode).apply()
        }
    }

    private companion object {
        const val PREFS = "apex_changelog_prefs"
        const val KEY_SEEN = "seen_version_code"
        const val KEY_SEEN_AVAILABLE = "seen_available_version_code"
        const val KEY_BASELINE = "baseline_initialized"
    }
}
