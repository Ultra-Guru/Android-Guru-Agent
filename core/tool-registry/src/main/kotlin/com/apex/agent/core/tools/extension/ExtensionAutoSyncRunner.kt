package com.apex.agent.core.tools.extension

/**
 * # ExtensionAutoSyncRunner —— 声明式清单自动同步的纯 JVM 执行核（PR-E）
 *
 * 方向④「扩展系统生产化加固」的决策内核：给一份 spec 清单，逐个 resolve
 * 并与锁文件比对 —— **只评估、不安装**（启动期自动行为绝不悄悄换版本），
 * 产出漂移/失败/新增三类报告并落 lastSync。通知与设置门在 app 层包装
 * （本类对 Android 零依赖，纯 JVM 可测）。
 *
 * 报告语义：
 * - `drifted`：账本已锁、源指纹变化 —— 防悄悄换版本的核心信号；
 * - `failed`：resolve 失败（源失效/网络/语法）—— 需要人看；
 * - `fresh`：账本没有的 spec（新条目）—— 仅计数，安装由用户显式发起。
 */
class ExtensionAutoSyncRunner(
    private val installer: ExtensionInstaller,
    private val registry: ExtensionRegistry
) {

    data class Report(
        val ok: Int,
        val fresh: List<String>,
        val drifted: List<String>,
        val failed: List<String>
    ) {
        val hasIssues: Boolean get() = drifted.isNotEmpty() || failed.isNotEmpty()

        /** 一行人读摘要（通知/Doctor 共用）。 */
        fun summary(): String = buildString {
            append("ok=$ok fresh=${fresh.size} drift=${drifted.size} failed=${failed.size}")
            if (drifted.isNotEmpty()) append(" · 漂移: " + drifted.take(3).joinToString(", "))
            if (failed.isNotEmpty()) append(" · 失败: " + failed.take(3).joinToString(", "))
        }
    }

    /**
     * 评估一份声明式清单（不安装）。评估结果落 lastSync（无论有无问题 ——
     * 「最近一次同步成功无漂移」也是有效的生产状态信号）。
     */
    suspend fun run(specs: List<String>): Report {
        val drifted = mutableListOf<String>()
        val failed = mutableListOf<String>()
        var fresh = 0
        for (spec in specs) {
            val manifest = installer.resolve(spec)
            if (manifest.isFailure) {
                failed += "$spec → ${manifest.exceptionOrNull()?.message?.take(120)}"
                continue
            }
            val m = manifest.getOrThrow()
            val locked = registry.getLock(m.id)
            val installed = registry.get(m.id) != null
            when {
                locked != null && locked.manifestHash != m.manifestHash ->
                    drifted += "${m.id}（锁 v${locked.version}，源 v${m.version}）"
                !installed -> fresh += 1   // 账本没有 → 全新条目（安装由用户显式发起）
                else -> { /* 与锁一致或已装未锁：ok */ }
            }
        }
        val report = Report(ok = specs.size - drifted.size - failed.size, fresh = fresh, drifted = drifted, failed = failed)
        registry.recordLastSync(
            ExtensionRegistry.LastSyncEntry(
                time = System.currentTimeMillis(),
                ok = report.ok,
                drifted = report.drifted,
                failed = report.failed
            )
        )
        return report
    }
}
