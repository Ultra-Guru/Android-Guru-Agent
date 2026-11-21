package com.apex.agent.ui.screen.market

import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * # 插件 UI 页资产域路径守卫（#818：WebView file:// 域根除）
 *
 * 功能性插件 UI 页（[PluginUiPageDialog]）的静态资源经
 * androidx.webkit `WebViewAssetLoader` 以 **https 资产域**
 * （`https://appassets.androidplatform.net/assets/…`）映射回插件目录 ——
 * file:// 域彻底关闭后，页面 XHR/fetch 只能命中本守卫校验过的路径。
 *
 * 本对象承载「资产域 URL ↔ 插件目录文件」的纯映射逻辑：
 * - **入口 URL 构造**（[entryUrl]）：插件目录内 entry 文件 → 资产域 URL
 *   （相对路径分段百分号编码，空格/中文/特殊字符全兼容）；
 * - **子资源解析**（[resolve]）：资产域 path（WebView 已解码）→ 插件目录
 *   内文件。canonical 边界复核 —— `..` 段、绝对段注入、symlink 逃逸
 *   一律折叠为 null（请求被上层以 404 兜底），对齐 AGENTS.md 防御式 IO。
 *
 * 与 WebView 装载细节（AssetLoader 装配 / MIME / 流打开）解耦 —— 纯
 * java.io / java.net，JVM 直测（PluginUiAssetGuardTest）。
 */
object PluginUiAssetGuard {

    /** WebViewAssetLoader 默认资产域（官方固定值，勿改 —— 页面内相对引用同域生效）。 */
    const val ASSET_DOMAIN = "appassets.androidplatform.net"

    /** 资产域内的挂载前缀（addPathHandler 的 mount 点）。 */
    const val ASSET_PATH_PREFIX = "/assets/"

    /**
     * 入口装载 URL：插件目录内 entry 文件 → `https://<域>/assets/<相对路径>`。
     *
     * @param rootDir 插件根目录（canonical，[PluginUiTarget.rootDir]）
     * @param entryFile 已复核的入口文件（canonical，必须位于 rootDir 内）
     * @return 资产域 URL；entry 不在 rootDir 内（防御性复核失败）返回 null
     */
    fun entryUrl(rootDir: File, entryFile: File): String? = runCatching {
        val root = rootDir.canonicalFile
        val entry = entryFile.canonicalFile
        if (!entry.path.startsWith(root.path + File.separator)) return null
        val relative = entry.path.removePrefix(root.path + File.separator)
        if (relative.isEmpty()) return null
        "https://$ASSET_DOMAIN$ASSET_PATH_PREFIX${encodePath(relative)}"
    }.getOrNull()

    /**
     * 资产域子资源解析（WebView 侧已百分号解码的 path）：
     * `/assets/<相对路径>` → 插件目录内 canonical 文件。
     *
     * 拒绝面（全部返回 null）：
     * - 非 `/assets/` 前缀（挂载点外的路径不可能来自合法页面资源）；
     * - 相对路径为空（目录本身，不是文件资源）；
     * - `..` 段（路径穿越 —— canonical 复核前的显式拦截，日志可归因）；
     * - canonical 后逃逸插件根目录（symlink / 绝对段注入）。
     *
     * @param path 请求 path（形如 `/assets/ui/index.html`，已解码）
     * @param rootDir 插件根目录
     * @return 目录内文件（存在性与 isFile 由调用方复核 —— WebView 需要流）；
     *         校验拒绝返回 null
     */
    fun resolve(path: String, rootDir: File): File? = runCatching {
        if (!path.startsWith(ASSET_PATH_PREFIX)) return null
        val relative = path.removePrefix(ASSET_PATH_PREFIX)
        if (relative.isEmpty()) return null
        // 显式 .. 段拦截：canonical 兜底之前先给出确定性拒绝（不依赖文件系统
        // 状态），同时让「目录穿越尝试」在调用方日志里有明确语义。
        if (relative.split('/').any { it == ".." }) return null
        val root = rootDir.canonicalFile
        val target = File(root, relative).canonicalFile
        if (!target.path.startsWith(root.path + File.separator)) null else target
    }.getOrNull()

    /** 资产域 URL 的 host 判定（导航白名单 / 子资源拦截共用口径）。 */
    fun isAssetHost(host: String?): Boolean = host.equals(ASSET_DOMAIN, ignoreCase = true)

    // ── 编码工具（纯 java.net，JVM 可测）─────────────────────────────────

    /** 相对路径 → 资产域 URL path 段（`/` 分段，逐段百分号编码）。 */
    internal fun encodePath(relative: String): String =
        relative.split('/').joinToString("/") { segment ->
            // URLEncoder 是 form 编码（空格 → +）：path 语义里换回 %20
            URLEncoder.encode(segment, StandardCharsets.UTF_8.name()).replace("+", "%20")
        }

    /** 资产域 path 段 → 相对路径（逐段解码；坏编码折叠为原文段，不向上抛）。 */
    internal fun decodePath(encoded: String): String =
        encoded.split('/').joinToString("/") { segment ->
            runCatching { URLDecoder.decode(segment, StandardCharsets.UTF_8.name()) }
                .getOrDefault(segment)
        }
}
