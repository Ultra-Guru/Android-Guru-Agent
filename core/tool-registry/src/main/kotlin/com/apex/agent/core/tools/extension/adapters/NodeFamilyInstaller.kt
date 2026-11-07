package com.apex.agent.core.tools.extension.adapters

import com.apex.agent.core.tools.extension.ExtensionDirLayout
import com.apex.agent.core.tools.extension.ExtensionSpecParser
import com.apex.agent.core.tools.extension.NodeInstallPort
import java.io.File

/**
 * # NodeFamilyInstaller —— OpenCode / DSH 共用的 Node 系安装基座
 *
 * 白名单命令构造（安全契约，brief 明确要求）：npm 包名必须过
 * [ExtensionSpecParser.isNpmName]、仓库必须过 [ExtensionSpecParser.githubRepoOf]，
 * **用户输入永不进 shell 串** —— 本类输出的 argv 全部由白名单片段拼装。
 *
 * 安装走 [ExtensionDirLayout] 的 tmp → 原子 publish 链，失败零残留。
 */
class NodeFamilyInstaller(
    private val port: NodeInstallPort,
    private val layout: ExtensionDirLayout,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS
) {

    /** npm 包安装：`npm install --prefix <tmp> --no-audit --no-fund <pkg>`。 */
    suspend fun npmInstall(extId: String, pkg: String): Result<File> {
        if (!ExtensionSpecParser.isNpmName(pkg)) {
            return Result.failure(IllegalArgumentException("npm 包名不合法（白名单外）：'$pkg'"))
        }
        val tmp = layout.newTmpDir(extId)
        val outcome = port.exec(
            command = listOf("npm", "install", "--prefix", tmp.absolutePath, "--no-audit", "--no-fund", "--", pkg),
            cwd = tmp,
            timeoutMs = timeoutMs
        ).getOrElse { runCatching { tmp.deleteRecursively() }; return Result.failure(it) }
        if (!outcome.success) {
            tmp.deleteRecursively()
            return Result.failure(
                IllegalStateException("npm install 失败（exit ${outcome.exitCode}）：${(outcome.stderr.ifBlank { outcome.stdout }).take(300)}")
            )
        }
        return layout.atomicPublish(extId, tmp)
    }

    /** GitHub 仓库克隆：`git clone --depth 1 <url> <tmp>/src`。 */
    suspend fun gitClone(extId: String, ownerRepo: String): Result<File> {
        val repo = ExtensionSpecParser.githubRepoOf(ownerRepo)
            ?: return Result.failure(IllegalArgumentException("GitHub 仓库不合法（白名单外）：'$ownerRepo'"))
        val tmp = layout.newTmpDir(extId)
        val target = File(tmp, "src")
        val outcome = port.exec(
            command = listOf("git", "clone", "--depth", "1", "https://github.com/$repo.git", target.absolutePath),
            cwd = tmp,
            timeoutMs = timeoutMs
        ).getOrElse { runCatching { tmp.deleteRecursively() }; return Result.failure(it) }
        if (!outcome.success) {
            tmp.deleteRecursively()
            return Result.failure(
                IllegalStateException("git clone 失败（exit ${outcome.exitCode}）：${(outcome.stderr.ifBlank { outcome.stdout }).take(300)}")
            )
        }
        // 仓库根即发布体（pkg = tmp 本体语义不同 —— clone 已在 tmp/src，发布整 tmp）
        return layout.atomicPublish(extId, tmp)
    }

    /** 本地文件/目录安装（OpenCode 单文件插件）：复制进 tmp → publish。 */
    suspend fun installLocalPath(extId: String, path: String, allowedExtensions: Set<String>): Result<File> {
        val src = File(path)
        if (!src.exists()) return Result.failure(IllegalArgumentException("本地路径不存在：$path"))
        if (src.isFile && src.extension.lowercase() !in allowedExtensions) {
            return Result.failure(
                IllegalArgumentException("只允许 ${allowedExtensions.joinToString("/")} 文件，收到：${src.name}")
            )
        }
        val tmp = layout.newTmpDir(extId)
        return runCatching {
            val dst = File(tmp, src.name)
            if (src.isDirectory) src.copyRecursively(dst) else src.copyTo(dst)
            layout.atomicPublish(extId, tmp).getOrThrow()
        }.onFailure { tmp.deleteRecursively() }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 300_000L
    }
}
