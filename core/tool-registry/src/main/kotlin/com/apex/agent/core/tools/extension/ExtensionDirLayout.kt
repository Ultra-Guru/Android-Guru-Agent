package com.apex.agent.core.tools.extension

import java.io.File

/**
 * # ExtensionDirLayout —— ubuntu 域扩展的宿主目录布局与原子发布
 *
 * `<root>/<extId>/` 下三件套：
 * - `pkg/`     已发布实体（原子 rename 的落点，对外唯一可见形态）；
 * - `tmp-<时间戳>/` 安装暂存（npm install / git clone 先落这里，成功才 publish）；
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

    /**
     * 规范化归属校验的 profile 文件句柄（**公共安全助手** —— ExtensionRun /
     * 适配器写 profile / Doctor 三处共用同一套检查，禁止各调用点自写路径拼接）：
     * relativePath 允许一级子目录（如 `dsh/profile.json`），但禁止 `..`；
     * canonical 化后必须仍落在该扩展的 extensionDir 之内（防 bind 挂载/符号
     * 链接造成的 canonical 漂移 —— PRoot 环境下 plain startsWith 不可靠）。
     *
     * @return 文件句柄（可能尚不存在，调用方决定读/写）；路径逃逸 → failure
     */
    fun profileFile(extId: String, relativePath: String): Result<File> = runCatching {
        safeId(extId)
        require(!relativePath.contains("..")) { "profile 路径含 .. 序列：'$relativePath'" }
        val dir = profileDir(extId).apply { mkdirs() }
        val file = File(dir, relativePath.trim('/'))
        val dirCanonical = dir.canonicalFile
        val fileCanonical = file.canonicalFile
        require(
            fileCanonical == dirCanonical ||
                fileCanonical.path.startsWith(dirCanonical.path + File.separator)
        ) { "profile 路径逃逸出扩展目录：${fileCanonical.path}" }
        file
    }

    /**
     * pkg 内的规范化文件句柄（ExtensionRun 的 entry 校验用，同 [profileFile] 语义）：
     * canonical 化后必须仍落在 pkg 目录内 —— entry 来自安装期 profile 元数据，
     * 但元数据本身可能被篡改，运行前以文件系统真相复核。
     */
    fun pkgFile(extId: String, relativePath: String): Result<File> = runCatching {
        safeId(extId)
        val pkg = pkgDir(extId)
        require(pkg.isDirectory) { "扩展未安装或未发布：$extId" }
        val file = File(pkg, relativePath.removePrefix("/"))
        val pkgCanonical = pkg.canonicalFile
        val fileCanonical = file.canonicalFile
        require(
            fileCanonical == pkgCanonical ||
                fileCanonical.path.startsWith(pkgCanonical.path + File.separator)
        ) { "entry 路径逃逸出 pkg 目录：${fileCanonical.path}" }
        file
    }

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
