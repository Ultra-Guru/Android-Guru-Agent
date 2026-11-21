package com.apex.agent.core.tools.extension.adapters

import com.apex.agent.core.tools.extension.ExtensionDirLayout
import com.apex.agent.core.tools.extension.ExtensionJson
import com.apex.agent.core.tools.extension.ExtensionSpecParser
import com.apex.agent.core.tools.extension.NodeInstallPort
import com.apex.agent.core.tools.extension.PinnedSource
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    suspend fun npmInstall(extId: String, pkg: String): Result<File> =
        npmInstallPinned(extId, pkg).map { it.first }

    /**
     * npm 包安装（带来源钉定）：publish 后回读安装产物 package.json 的真实
     * 版本 + package-lock 的 tarball integrity —— 账本钉定数据源（2026-11，
     * 修正四：版本不得只存在于展示文字）。
     */
    suspend fun npmInstallPinned(extId: String, pkg: String): Result<Pair<File, PinnedSource>> {
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
        val published = layout.atomicPublish(extId, tmp).getOrElse { return Result.failure(it) }
        val pinned = readNpmPinned(published, pkg)
        return Result.success(published to pinned)
    }

    /** GitHub 仓库克隆：`git clone --depth 1 <url> <tmp>/src`。 */
    suspend fun gitClone(extId: String, ownerRepo: String): Result<File> =
        gitClonePinned(extId, ownerRepo).map { it.first }

    /** GitHub 克隆（带钉定）：clone 后 `git rev-parse HEAD` 取不可变 commit SHA。 */
    suspend fun gitClonePinned(extId: String, ownerRepo: String): Result<Pair<File, PinnedSource>> {
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
        val published = layout.atomicPublish(extId, tmp).getOrElse { return Result.failure(it) }
        // publish 后 .git 在 published/src 内 —— rev-parse 于该目录执行
        val sha = port.exec(
            command = listOf("git", "rev-parse", "HEAD"),
            cwd = File(published, "src"),
            timeoutMs = 30_000
        ).getOrNull()?.stdout?.trim()?.takeIf { it.isNotEmpty() }
        return Result.success(published to PinnedSource(version = null, revision = sha))
    }

    /** 回读安装产物的真实版本与 integrity（读取失败 = PinnedSource 空，诚实降级）。 */
    private fun readNpmPinned(published: File, pkg: String): PinnedSource {
        // scoped 包名（@scope/name）的 '/' 在 File(parent, child) 里按路径解析，跨平台成立
        val pkgJson = File(published, "node_modules/$pkg/package.json")
        val version = runCatching {
            ExtensionJson.json.parseToJsonElement(pkgJson.readText()).jsonObject["version"]
                ?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
        }.getOrNull()
        val integrity = runCatching {
            val lock = File(published, "package-lock.json")
            if (!lock.isFile) return@runCatching null
            val packages = ExtensionJson.json.parseToJsonElement(lock.readText()).jsonObject["packages"]?.jsonObject
            packages?.get("node_modules/$pkg")?.jsonObject?.get("integrity")
                ?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
        }.getOrNull()
        return PinnedSource(version = version, integrity = integrity)
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
