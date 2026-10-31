package com.apex.agent.changelog

/**
 * 弹窗决策器（changelog 通道 v1）—— 纯函数、零 Android 依赖，输入三源
 * （远端清单/内置副本/本地版本口径）输出三态，UI 直接按类型渲染。
 *
 * 优先级与语义：
 * 1. **[Decision.NewVersionAvailable]**：远端 latest > 本地有效版本 且
 *    未就该版本提醒过 —— 弹「发现新版本」（日志 + 前往下载页）。与
 *    ApexRoot 顶部 UpdateBanner 互补：浮窗轻提醒，本弹窗重内容（整版日志）。
 *    命中时**不再叠加**「本版日志」弹窗 —— 用户已在被引导去更新，本版
 *    （即将过时的）日志不抢屏；且 seen 与 seenAvailable 两把钥匙分离，
 *    升级后的「本次更新内容」不会被此弹窗吞掉；
 * 2. **[Decision.WhatsNew]**：本地有效版本 > 已读 且基线已初始化（=
 *    升级/热更后的首启）—— 弹「本次更新内容」。内容源优先远端
 *    （热更用户的 APK 内置副本落后于数据层），回退内置，都缺则不弹
 *    （绝不展示版本错位的日志）；
 * 3. **[Decision.None]**：其余（已读且无新版 / 离线且无内置 / 全新安装
 *    基线 / 远端清单 schema 缺 latest）。
 *
 * v2.2 跨版本聚合（「手动日志发行时 PR 日志丢失」根治）：
 * 两档决策都改经 [ChangelogAggregator] 合并版本区间内的全部 entry ——
 * * WhatsNew：聚合 (已读, 本地有效]，跨多版本升级时中间版本的手动日志
 *   与 PR 功能变更一条不丢；远端清单元数据落后（latest 已领先本地）时
 *   区间内条目仍可用（旧逻辑要求 latest 恰好等于本地版本，远端一旦
 *   领先就只能靠内置副本）；
 * * NewVersionAvailable：聚合 (本地有效, 远端最新]，预告即将获得的全部
 *   更新而非仅最后一个版本。
 * 单版本区间退化为原样返回 —— 与旧体验完全兼容。
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
            // 聚合 (本地有效, 远端最新]：预告跨多版本升级可获得的全部更新
            val entry = ChangelogAggregator.aggregate(
                manifest = remote,
                fromExclusive = effectiveVersionCode,
                toInclusive = remoteLatest.versionCode
            ) ?: remoteLatest
            return Decision.NewVersionAvailable(entry)
        }
        if (baselineInitialized && effectiveVersionCode > seenVersionCode) {
            // 聚合 (已读, 本地有效]：跨多版本升级时中间版本日志一条不丢。
            // 远端优先（区间的元数据齐全且新），回退内置 APK 副本，都缺则不弹
            val entry = ChangelogAggregator.aggregate(
                manifest = remote,
                fromExclusive = seenVersionCode,
                toInclusive = effectiveVersionCode
            ) ?: ChangelogAggregator.aggregate(
                manifest = embedded,
                fromExclusive = seenVersionCode,
                toInclusive = effectiveVersionCode
            ) ?: return Decision.None
            return Decision.WhatsNew(entry)
        }
        return Decision.None
    }
}
