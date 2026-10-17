package com.apex.agent.changelog

/**
 * 弹窗决策器（changelog 通道 v1）—— 纯函数、零 Android 依赖，输入三源
 * （远端清单/内置副本/本地版本口径）输出三态，UI 直接按类型渲染。
 *
 * 优先级与语义：
 * 1. **[Decision.NewVersionAvailable]**：远端 latest > 本地有效版本 且
 *    未就该版本提醒过 —— 弹「发现新版本」（日志 + 前往下载页）。新版本
 *    提醒的唯一通道（顶部常驻浮窗横幅已移除，2026-10 用户反馈）。
 *    命中时**不再叠加**「本版日志」弹窗 —— 用户已在被引导去更新，本版
 *    （即将过时的）日志不抢屏；且 seen 与 seenAvailable 两把钥匙分离，
 *    升级后的「本次更新内容」不会被此弹窗吞掉；
 * 2. **[Decision.WhatsNew]**：本地有效版本 > 已读 且基线已初始化（=
 *    升级/热更后的首启）—— 弹「本次更新内容」。内容源优先远端同版
 *    （热更用户的 APK 内置副本落后于数据层），回退内置同版，都缺则
 *    不弹（绝不展示版本错位的日志）；
 * 3. **[Decision.None]**：其余（已读且无新版 / 离线且无内置 / 全新安装
 *    基线 / 远端清单 schema 缺 latest）。
 */
object ChangelogGate {

    sealed interface Decision {
        /** 远端有更新版本 —— 携带新版日志与下载出口。 */
        data class NewVersionAvailable(val entry: ChangelogEntry) : Decision

        /** 本地刚升级/热更且未读 —— 携带本版日志。 */
        data class WhatsNew(val entry: ChangelogEntry) : Decision

        /** 无需弹窗。 */
        data object None : Decision
    }

    /**
     * @param remote 远端清单（null = 离线/失败/自动检查关闭）
     * @param embedded APK 内置副本（null = 老构建/缺失）
     * @param effectiveVersionCode 本地有效版本（max(包, 已应用热更目标)）
     * @param seenVersionCode 已读的本版 versionCode
     * @param seenAvailableVersionCode 已就其提醒过的远端 versionCode
     * @param baselineInitialized 首启基线是否已初始化（false = 首启，不弹本版日志）
     */
    fun decide(
        remote: ChangelogManifest?,
        embedded: ChangelogManifest?,
        effectiveVersionCode: Int,
        seenVersionCode: Int,
        seenAvailableVersionCode: Int,
        baselineInitialized: Boolean
    ): Decision {
        val remoteLatest = remote?.latest
        if (remoteLatest != null &&
            remoteLatest.versionCode > effectiveVersionCode &&
            remoteLatest.versionCode > seenAvailableVersionCode
        ) {
            return Decision.NewVersionAvailable(remoteLatest)
        }
        if (baselineInitialized && effectiveVersionCode > seenVersionCode) {
            val entry = remoteLatest?.takeIf { it.versionCode == effectiveVersionCode }
                ?: embedded?.latest?.takeIf { it.versionCode == effectiveVersionCode }
                ?: return Decision.None
            return Decision.WhatsNew(entry)
        }
        return Decision.None
    }
}
