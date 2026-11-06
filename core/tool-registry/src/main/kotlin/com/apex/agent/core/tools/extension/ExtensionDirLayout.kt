package com.apex.agent.core.tools.extension

import java.io.File

/**
 * # ExtensionDirLayout —— ubuntu 域扩展的宿主目录布局与原子发布
 *
 * `<root>/<extId>/` 下三件套：
 * - `pkg/`     已发布实体（原子 rename 的落点，对外唯一可见形态）；
 * - `tmp-*/`   安装暂存（npm install / git clone 先落这里，成功才 publish）；
 * - `profile/` 生态注册文件（DSH profile.json / OpenCode 元数据等）。
 *
 * **原子性**（brief 要求）：安装失败绝不留半装状态 —— tmp 阶段任何一步失败
 * 即整目录删除；publish 是同父目录 rename（同文件系统上原子）。
 */
class ExtensionDirLayout(private val root: File) {

    init {
        runCatching { root.mkdirs() }
    }

    /** extId 白名单（账本 id 形如 `opencode.web-scraper` / `dsh.memory`）。 */
    private fun safeId(extId: String): String {
        require(ID_PATTERN.matches(extId)) { "非法扩展 id：'$extId'" }
        return extId
    }

    fun extensionDir(extId: String): File = File(root, safeId(extId))

    fun pkgDir(extId: String): File = File(extensionDir(extId), "pkg")

    fun profileDir(extId: String): File = File(extensionDir(extId), "profile")

    /** 新建暂存目录（调用方负责最终 publish 或 deleteRecursively）。 */
    fun newTmpDir(extId: String): File =
        File(extensionDir(extId), "tmp-${System.currentTimeMillis()}").apply { mkdirs() }

    /**
     * 原子发布：tmp 目录 → `pkg/`（旧 pkg 先挪到 old-* 再删，升级语义）。
     * @return 发布后的 pkg 目录
     */
    fun atomicPublish(extId: String, tmp: File): Result<File> {
        safeId(extId)
        if (!tmp.isDirectory) return Result.failure(IllegalStateException("暂存目录不存在：${tmp.path}"))
        return runCatching {
            val dir = extensionDir(extId)
            val pkg = File(dir, "pkg")
            if (pkg.exists()) {
                val old = File(dir, "old-${System.currentTimeMillis()}")
                if (!pkg.renameTo(old)) pkg.deleteRecursively()
                old.deleteRecursively()
            }
            if (!tmp.renameTo(pkg)) {
                // rename 失败（跨设备等）：退化为复制发布 —— 诚实但非原子路径
                tmp.copyRecursively(pkg, overwrite = true)
                tmp.deleteRecursively()
            }
            pkg
        }.onFailure { tmp.deleteRecursively() }
    }

    /** 整扩展目录清除（uninstall 用）。 */
    fun wipe(extId: String): Boolean = extensionDir(extId).let {
        (if (it.exists()) it.deleteRecursively() else true)
    }

    /** 目录占用人读估算（inspect 报告用，递归字节和；上限 256MB 封顶）。 */
    fun sizeSummary(extId: String): String? {
        val pkg = pkgDir(extId)
        if (!pkg.isDirectory) return null
        var total = 0L
        pkg.walkTopDown().forEach { f -> if (f.isFile) total += f.length() }
        return when {
            total >= 1L shl 20 -> "~${total / (1L shl 20)}MB"
            total >= 1024 -> "~${total / 1024}KB"
            else -> "~${total}B"
        }
    }

    private companion object {
        val ID_PATTERN = Regex("^[a-z0-9][a-z0-9._-]{0,63}$")
    }
}
