package com.apex.agent.ui.screen.playground

import android.content.Context
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import java.io.File

/**
 * ═══════════════════════════════════════════════════════════════
 *  游乐场目录 —— 插件游戏的发现 / 安装 / 卸载
 * ═══════════════════════════════════════════════════════════════
 *
 * 两个来源统一合并成一份目录（对齐 UnifiedPluginRegistry 的门面哲学）：
 *  - **内置**：`assets/playground/games/<id>/game.json + index.html`
 *    —— 随 APK 分发，不可卸载；
 *  - **安装**：`filesDir/playground/games/<id>/` —— 运行时导入的
 *    自包含 HTML（单文件即插件），可卸载。
 *
 * 安装语义（诚实工程）：
 *  - 导入的 HTML 里若有 `<title>` 就当展示名，没有就退回文件名；
 *  - id 冲突（与内置或已装重复）：**内置优先、安装侧拒绝覆盖**
 *    —— 与功能性插件 REJECT 策略同款理由：数据不冒充内置能力；
 *  - 坏输入（空内容 / 非 HTML / 写盘失败）：如实返回失败原因，不假成功。
 *
 * 防御式 IO：任何形状都吞得下 —— 异常折叠为失败结果 + AppLogger
 * 留痕，不向上抛（对齐 AGENTS.md 架构原则）。
 */

/** 导入结果：调用方按档位给用户反馈，不猜失败原因。 */
sealed interface InstallResult {
    /** 成功：返回生成的插件 id。 */
    data class Installed(val id: String) : InstallResult

    /** id 与内置 / 已装游戏冲突（拒绝覆盖，数据不冒充内置）。 */
    data class Conflict(val id: String) : InstallResult

    /** 内容不是可识别的 HTML（空 / 无标签）。 */
    object NotHtml : InstallResult

    /** 写盘失败（目录建不出 / 落盘异常，已回滚半成品）。 */
    object IoError : InstallResult
}

class PlaygroundCatalog(private val context: Context) {

    /** 内置游戏在 assets 下的根路径。 */
    private val assetsRoot = "playground/games"

    /** 安装游戏的 filesDir 根目录。 */
    private val installRoot: File
        get() = File(context.filesDir, "playground/games")

    // ═════════════════════════════════════════════════════════
    //  目录
    // ═════════════════════════════════════════════════════════

    /** 合并目录：内置在前，安装侧去重（id 撞内置的不出现）。 */
    fun loadGames(): List<PlaygroundGame> {
        val builtin = loadBuiltinGames()
        val installed = loadInstalledGames().filter { g ->
            builtin.none { it.manifest.id == g.manifest.id }
        }
        return builtin + installed
    }

    /** 扫描 assets：每个子目录 = 一个内置游戏（game.json 缺失则如实跳过并留痕）。 */
    private fun loadBuiltinGames(): List<PlaygroundGame> = runCatching {
        val ids = context.assets.list(assetsRoot).orEmpty().filter { it.isNotBlank() }
        ids.mapNotNull { id -> loadBuiltinGame(id) }
    }.getOrElse { e ->
        AppLogger.instance.warn(LogCategory.UI, TAG, "内置游戏目录扫描失败：${e.message}")
        emptyList()
    }

    private fun loadBuiltinGame(id: String): PlaygroundGame? {
        val manifestText = runCatching {
            context.assets.open("$assetsRoot/$id/${GameManifestParser.MANIFEST_FILE}")
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrNull()
        val manifest = manifestText?.let(GameManifestParser::parse)
        if (manifest == null) {
            AppLogger.instance.warn(LogCategory.UI, TAG, "内置游戏 $id 缺少合法 game.json —— 目录如实跳过")
            return null
        }
        return PlaygroundGame(
            manifest = manifest.copy(id = manifest.id.ifBlank { id }),
            source = GameSource.BUILTIN,
            entryUrl = "file:///android_asset/$assetsRoot/$id/index.html"
        )
    }

    /** 扫描安装目录：子目录 = 已装游戏；manifest 由导入时生成。 */
    private fun loadInstalledGames(): List<PlaygroundGame> = runCatching {
        val root = installRoot
        if (!root.isDirectory) return emptyList()
        root.listFiles().orEmpty()
            .filter { it.isDirectory }
            .mapNotNull { dir -> loadInstalledGame(dir) }
    }.getOrElse { e ->
        AppLogger.instance.warn(LogCategory.UI, TAG, "安装游戏目录扫描失败：${e.message}")
        emptyList()
    }

    private fun loadInstalledGame(dir: File): PlaygroundGame? {
        val manifestText = File(dir, GameManifestParser.MANIFEST_FILE).takeIf { it.isFile }
            ?.let { runCatching { it.readText(Charsets.UTF_8) }.getOrNull() }
        val manifest = manifestText?.let(GameManifestParser::parse)
        if (manifest == null || !File(dir, "index.html").isFile) {
            AppLogger.instance.warn(LogCategory.UI, TAG, "安装游戏 ${dir.name} 形状不完整 —— 目录如实跳过")
            return null
        }
        return PlaygroundGame(
            manifest = manifest.copy(id = manifest.id.ifBlank { dir.name }),
            source = GameSource.INSTALLED,
            entryUrl = "file://${File(dir, "index.html").absolutePath}",
            installDir = dir
        )
    }

    // ═════════════════════════════════════════════════════════
    //  安装 / 卸载
    // ═════════════════════════════════════════════════════════

    /**
     * 导入单文件 HTML 游戏插件。
     *
     * @param html 完整 HTML 文本（非空、含 `<` 才视为 HTML —— 诚实失败不装空壳）。
     * @param fileName 原文件名（派生插件 id 与兜底展示名）。
     */
    fun installHtmlGame(html: String, fileName: String): InstallResult {
        val trimmed = html.trim()
        if (trimmed.isEmpty() || !trimmed.contains('<')) {
            AppLogger.instance.warn(LogCategory.UI, TAG, "导入失败：内容不是 HTML")
            return InstallResult.NotHtml
        }
        val id = deriveId(fileName)
        // 冲突门：撞内置或已装 → 拒绝（数据不覆盖内置能力）
        if (isKnownId(id)) {
            AppLogger.instance.warn(LogCategory.UI, TAG, "导入失败：id「$id」与内置或已装游戏冲突")
            return InstallResult.Conflict(id)
        }
        val dir = File(installRoot, id)
        return runCatching {
            if (!dir.isDirectory && !dir.mkdirs()) return InstallResult.IoError
            // 原子写：tmp + rename（对齐仓库持久化纪律）
            writeAtomically(File(dir, "index.html"), trimmed)
            writeAtomically(File(dir, GameManifestParser.MANIFEST_FILE), buildManifestJson(id, trimmed, fileName))
            InstallResult.Installed(id)
        }.getOrElse { e ->
            AppLogger.instance.warn(LogCategory.UI, TAG, "导入写盘失败：${e.message}")
            runCatching { dir.deleteRecursively() }
            InstallResult.IoError
        }
    }

    /** 卸载安装游戏（内置不可卸载 —— 目录上就不给卸载入口）。 */
    fun uninstall(game: PlaygroundGame): Boolean {
        if (game.source != GameSource.INSTALLED) return false
        val dir = game.installDir ?: return false
        return runCatching { dir.deleteRecursively() }
            .getOrElse { e ->
                AppLogger.instance.warn(LogCategory.UI, TAG, "卸载失败：${e.message}")
                false
            }
    }

    // ═════════════════════════════════════════════════════════
    //  内部工具
    // ═════════════════════════════════════════════════════════

    /** id 是否与内置 / 已装冲突。 */
    private fun isKnownId(id: String): Boolean =
        runCatching { context.assets.list(assetsRoot).orEmpty().contains(id) }.getOrDefault(false) ||
            loadInstalledGames().any { it.manifest.id == id }

    /** 文件名 → 插件 id：去扩展名、非法字符归连字符、压缩、保底序号。 */
    private fun deriveId(fileName: String): String {
        val base = fileName.substringBeforeLast('.')
            .map { c -> if (c.isLetterOrDigit() && c.code < 128) c else '-' }
            .joinToString("")
            .split('-')
            .filter { it.isNotBlank() }
            .joinToString("-")
            .lowercase()
        return base.ifBlank { "game-${System.currentTimeMillis() % 100000}" }
    }

    /** 导入游戏的 manifest：`<title>` 当展示名（无则文件名），来源如实标注。 */
    private fun buildManifestJson(id: String, html: String, fileName: String): String {
        val title = Regex("<title[^>]*>([^<]+)</title>", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)?.trim()?.take(40)
        val name = title?.takeIf { it.isNotBlank() } ?: fileName.substringBeforeLast('.').take(40)
        val tagline = "导入的 HTML 游戏插件 · ${html.length} 字符 · 单文件自包含"
        return """
            {
              "id": "$id",
              "name": ${jsonEscape(name)},
              "tagline": ${jsonEscape(tagline)},
              "version": 1,
              "engine": "imported-html",
              "apis": [],
              "controls": "见游戏内说明"
            }
        """.trimIndent()
    }

    private fun jsonEscape(raw: String): String =
        raw.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").let { "\"$it\"" }

    /** 原子写：tmp + renameTo，rename 失败直写目标兜底。 */
    private fun writeAtomically(target: File, content: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(content, Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
            target.writeText(content, Charsets.UTF_8)
            tmp.delete()
        }
    }

    private companion object {
        const val TAG = "PlaygroundCatalog"
    }
}
