package com.apex.agent.core.tools.extension.dsh

import com.apex.agent.core.tools.extension.NodeInstallPort
import java.io.File

/**
 * # DshHostScripts —— shim/分析器脚本的沙箱部署与钉版 bootstrap
 *
 * core 资源（`src/main/resources/dsh-host/` 下四脚本）在首次使用时部署到沙箱
 * 扩展根 `<extRoot>/.dsh-host/scripts-v<版本>/`——**版本化目录**保证脚本升级
 * 后旧内容不被复用；部署幂等（已存在即跳过）。
 *
 * analyzer 运行时（acorn）独立 bootstrap：钉版 `npm install --prefix`，marker
 * 文件记录已装版本（dphmoblie 纪律：运行时依赖钉版，不漂移）。
 */
object DshHostScripts {

    /** 脚本版本（JS 语义变更即 bump —— 目录名变 = 全量重部署）。 */
    const val SCRIPTS_VERSION = "1"

    /** acorn 钉版（AST 分析器；升级须经 dsh-compat.md 验证流程）。 */
    const val ACORN_PINNED_VERSION = "8.12.1"

    private val SCRIPT_FILES = listOf(
        "dsh-shim.js", "dsh-introspect.js", "dsh-run-tool.js", "dsh-analyze.js"
    )

    fun scriptsDir(extensionRoot: File): File =
        File(extensionRoot, ".dsh-host/scripts-v$SCRIPTS_VERSION")

    fun analyzerDir(extensionRoot: File): File = File(extensionRoot, ".dsh-host/analyzer")

    /** 部署脚本（幂等）。@return 脚本目录（含 dsh-introspect.js 等）。 */
    fun deploy(extensionRoot: File): Result<File> = runCatching {
        val dir = scriptsDir(extensionRoot)
        dir.mkdirs()
        for (name in SCRIPT_FILES) {
            val target = File(dir, name)
            if (target.isFile && target.length() > 0) continue
            val source = DshHostScripts::class.java.getResourceAsStream("/dsh-host/$name")
                ?: throw IllegalStateException("core 资源缺失：/dsh-host/$name")
            source.use { input -> target.outputStream().use { input.copyTo(it) } }
        }
        dir
    }

    /**
     * analyzer 运行时就绪检查 + 钉版安装（幂等）。npm install 白名单构造
     * （包名+钉版字面量，无用户输入）。@return analyzerDir（含 node_modules/acorn）
     */
    suspend fun ensureAnalyzerRuntime(
        port: NodeInstallPort,
        extensionRoot: File,
        cwd: File
    ): Result<File> = runCatching {
        val dir = analyzerDir(extensionRoot)
        val marker = File(dir, ".acorn-$ACORN_PINNED_VERSION")
        val acornDir = File(dir, "node_modules/acorn")
        if (marker.isFile && acornDir.isDirectory) return@runCatching dir
        dir.mkdirs()
        dir.listFiles()?.forEach { if (it.name != "node_modules") it.deleteRecursively() }
        val outcome = port.exec(
            command = listOf("npm", "install", "--no-audit", "--no-fund", "--prefix", dir.absolutePath, "acorn@$ACORN_PINNED_VERSION"),
            cwd = cwd,
            timeoutMs = 180_000
        ).getOrElse { throw IllegalStateException("acorn bootstrap 执行失败：${it.message}") }
        if (!outcome.success) {
            throw IllegalStateException("acorn bootstrap 失败(exit ${outcome.exitCode})：${outcome.stderr.take(400)}")
        }
        if (!acornDir.isDirectory) {
            throw IllegalStateException("acorn bootstrap 后未发现 node_modules/acorn —— npm 输出：${outcome.stdout.take(200)}")
        }
        marker.writeText(ACORN_PINNED_VERSION)
        dir
    }

    /** analyzer 是否已就绪（不执行安装 —— Doctor/inspect 的依赖报告用）。 */
    fun analyzerReady(extensionRoot: File): Boolean =
        File(analyzerDir(extensionRoot), "node_modules/acorn").isDirectory
}
