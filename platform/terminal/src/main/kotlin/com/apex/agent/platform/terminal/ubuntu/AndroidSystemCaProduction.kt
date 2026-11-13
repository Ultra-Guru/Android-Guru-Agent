package com.apex.agent.platform.terminal.ubuntu

import java.io.File

/**
 * T99 审计修复：生产 DI 的 Android 系统 CA 推断端口。
 *
 * AOSP `SystemCertificateSource`（frameworks/base/core/java/android/security/net/config/SystemCertificateSource.java）
 * 的运行时语义：
 *  - 生效目录：`/apex/com.android.conscrypt/cacerts`（Android 14+ / `system.certs.enabled` 未启用时）
 *    否则 `$ANDROID_ROOT/etc/security/cacerts`。
 *  - 撤销目录：`Environment.getUserConfigDirectory(UserHandle.myUserId())/cacerts-removed`，
 *    即 `/data/misc/user/<userId>/cacerts-removed`（userId = uid / 100_000）。
 *
 * 默认 fail-closed：只有能定位到「已生效系统锚点目录」**且**「已知且可读的撤销目录」时，
 * `RootfsConfigurator.trustedAndroidSystemCas` 才会导入该存储的锚点（`androidSystemCaRemovedDirs`
 * 默认 `List(n){null}` → 每个存储一律跳过，是本仓库对「未知撤销来源」的诚实选择）。
 * 因此本端口在生产 DI 中被注入，把上述 AOSP 已知路径交给配置器使用；目录不可读时仍返回 null
 * （未知来源 → 不导入），与单测 fail-closed 行为保持一致。
 */
internal object AndroidSystemCaProduction {
    /** 生产 DI：与 [RootfsConfigurator] 默认 `androidSystemCaDirs` 一致的 AOSP 已知候选。 */
    fun knownSystemDirs(): List<File> {
        val apex = File("/apex/com.android.conscrypt/cacerts")
        if (apex.isDirectory && apex.listFiles()?.isNotEmpty() == true) return listOf(apex)
        val androidRoot = System.getenv("ANDROID_ROOT")?.trim()?.takeIf { it.isNotEmpty() }
        val fallback = File(androidRoot ?: "/system", "etc/security/cacerts")
        return listOf(fallback, File("/system/etc/security/cacerts")).distinct()
    }

    /**
     * 生产 DI：每个系统目录对应的撤销目录。
     * 只有目录「存在且可读」时才注入；不可读（例如 `/data/misc/user/<id>` 不存在）时
     * 返回 null，让上游按未知来源 fail-closed。
     */
    fun knownRemovedDirs(systemDirs: List<File> = knownSystemDirs()): List<File?> =
        removedDirsFor(userConfigDir = resolveAndroidUserConfigDir(), systemDirs = systemDirs)

    internal fun removedDirsFor(userConfigDir: File, systemDirs: List<File>): List<File?> =
        List(systemDirs.size) {
            val removed = File(userConfigDir, "cacerts-removed")
            if (removed.isDirectory && removed.canRead()) removed else null
        }

    internal fun currentUid(lines: List<String>): Long? =
        lines.firstOrNull { it.startsWith("Uid:") }
            ?.split(Regex("\\s+"))
            ?.getOrNull(1)
            ?.toLongOrNull()
            ?.takeIf { it >= 0 }

    /** `Environment.getUserConfigDirectory(UserHandle.myUserId())` 的 /proc 派生。 */
    private fun resolveAndroidUserConfigDir(): File {
        val uid = currentUid(
            runCatching { File("/proc/self/status").readLines() }.getOrDefault(emptyList())
        ) ?: return File("/data/misc/user/0")
        return File("/data/misc/user/${userIdFromUid(uid)}")
    }

    internal fun userIdFromUid(uid: Long): Long? =
        if (uid < 0) null else uid / 100_000
}
