package com.apex.agent.ui.screen.market

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.webkit.WebViewAssetLoader
import com.apex.agent.R
import java.io.ByteArrayInputStream
import java.io.File

/**
 * # 功能性插件 UI 页面（WebView 渲染 · #818 资产域口径）
 *
 * 插件 ui 贡献点的渲染面（plugin.json `contributions.ui[]`）：
 * 全屏 Dialog + WebView 装载 **https 资产域** URL —— 与游乐场 HTML 游戏
 * 同一哲学：HTML 是插件 UI 的统一创作载体，市场 JSON 通道分发、零签名门槛。
 *
 * 安全边界（v1.5.75 起根除 file:// 域，#818）：
 * - **资产域映射**：静态资源经 [WebViewAssetLoader] 挂载到
 *   `https://appassets.androidplatform.net/assets/…`（[PluginUiAssetGuard]
 *   做插件目录 canonical 边界复核）—— file:// scheme 全关
 *   （allowFileAccess / allowFileAccessFromFileURLs /
 *   allowUniversalAccessFromFileURLs / allowContentAccess 均 false）。
 *   旧实现 `allowFileAccessFromFileURLs = true` 时页面 XHR 可读**任意**
 *   file:// URL（`shouldOverrideUrlLoading` 只拦导航不管 XHR）—— 市场零
 *   签名门槛下即「读一个 XML 拿走 MCP Host token」的完整攻击链；
 * - **子资源请求全量拦截**（shouldInterceptRequest）：非资产域请求
 *   （外链 http(s) / file / content / …）以 403 空响应阻断，不透传
 *   网络栈 —— 离线自包含是 ui 贡献的契约，意外外联是数据面越权；
 * - **导航白名单**：仅资产域 https 可导航，其余 scheme 一律拦截；
 * - 无 JS 桥（不 addJavascriptInterface）—— 页面无宿主 API 面。
 *
 * @param title 顶栏标题（贡献 displayTitle）
 * @param rootDir 插件根目录（canonical；[PluginUiAssetGuard] 边界复核基准）
 * @param entryFile 已复核的入口文件（资产域装载）
 * @param onDismiss 关闭回调
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PluginUiPageDialog(
    title: String,
    rootDir: File,
    entryFile: File,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // ── 顶栏：返回 / 标题 / 关闭 ──
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.fusion_composer_close)
                        )
                    }
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.fusion_composer_close))
                    }
                }

                // ── WebView：资产域唯一放行面，其余 scheme 全拦截 ──
                val entryUrl = remember(rootDir, entryFile) {
                    PluginUiAssetGuard.entryUrl(rootDir, entryFile)
                }
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            // #818：file 域四开关全关 —— 插件静态资源不走
                            // file://，走 https 资产域（AssetLoader 映射）。
                            settings.allowFileAccess = false
                            settings.allowFileAccessFromFileURLs = false
                            settings.allowUniversalAccessFromFileURLs = false
                            settings.allowContentAccess = false
                            val assetLoader = WebViewAssetLoader.Builder()
                                .addPathHandler(PluginUiAssetGuard.ASSET_PATH_PREFIX) { path ->
                                    // 资产域请求的唯一出口：插件目录边界复核
                                    // 通过才开流；逃逸/不存在 → null（AssetLoader
                                    // 兜底 404，页面拿到的是干净失败）。
                                    PluginUiAssetGuard.resolve(path, rootDir)
                                        ?.takeIf { it.isFile }
                                        ?.let { file ->
                                            WebResourceResponse(
                                                pluginUiMimeOf(file.name), null, file.inputStream()
                                            )
                                        }
                                }
                                .build()
                            webViewClient = object : WebViewClient() {
                                override fun shouldInterceptRequest(
                                    view: WebView,
                                    request: WebResourceRequest
                                ): WebResourceResponse? {
                                    val url = request.url
                                    // 白名单：资产域 https（入口 + 相对资源）
                                    if (url.scheme.equals("https", true) &&
                                        PluginUiAssetGuard.isAssetHost(url.host)
                                    ) {
                                        return assetLoader.intercept(url)
                                    }
                                    // 离线自包含契约：其余一切请求（外链/子资源/
                                    // 任意 scheme）以 403 空响应阻断 —— XHR 不走
                                    // shouldOverrideUrlLoading，必须在此拦截。
                                    return pluginUiBlockedResponse()
                                }

                                override fun shouldOverrideUrlLoading(
                                    view: WebView,
                                    request: WebResourceRequest
                                ): Boolean {
                                    val url = request.url
                                    // 白名单：资产域 https（页面内导航）；
                                    // 其余（http 外链 / intent / file 等）全部拦截。
                                    return !(url.scheme.equals("https", true) &&
                                        PluginUiAssetGuard.isAssetHost(url.host))
                                }
                            }
                            // 入口 URL 理论恒非空（ViewModel + Guard 双重复核），
                            // 防御式兜底空白页 —— 不向下抛。
                            loadUrl(entryUrl ?: "about:blank")
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
            }
        }
    }
}

/** 资产域外请求的统一阻断响应（403 + 空体，零信息面）。 */
private fun pluginUiBlockedResponse(): WebResourceResponse = WebResourceResponse(
    "text/plain", "utf-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0))
)

/** 插件静态资源 MIME 映射（未知后缀按二进制流兜底，WebView 嗅探不生效）。 */
private fun pluginUiMimeOf(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
    "html", "htm" -> "text/html"
    "css" -> "text/css"
    "js", "mjs" -> "application/javascript"
    "json" -> "application/json"
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "svg" -> "image/svg+xml"
    "ico" -> "image/x-icon"
    "woff" -> "font/woff"
    "woff2" -> "font/woff2"
    "ttf" -> "font/ttf"
    "otf" -> "font/otf"
    "mp3" -> "audio/mpeg"
    "mp4" -> "video/mp4"
    "wav" -> "audio/wav"
    "txt" -> "text/plain"
    "xml" -> "application/xml"
    else -> "application/octet-stream"
}
