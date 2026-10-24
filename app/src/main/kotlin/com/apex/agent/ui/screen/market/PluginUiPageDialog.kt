package com.apex.agent.ui.screen.market

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
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
import com.apex.agent.R

/**
 * # 功能性插件 UI 页面（WebView 渲染）
 *
 * 插件 ui 贡献点的渲染面（plugin.json `contributions.ui[]`）：
 * 全屏 Dialog + WebView 装载 `file://<插件目录>/<entry>`——与游乐场
 * HTML 游戏（HtmlGameScreen）同一哲学：**HTML 是插件 UI 的统一创作
 * 载体**，市场 JSON 通道分发、零签名门槛。
 *
 * 安全边界（与游乐场同口径）：
 * - 入口文件由 ViewModel 侧 canonical 复核（不逃逸插件目录）；
 * - WebView 只放行 `file://` scheme —— http(s) 外链资源一律拦截
 *   （离线自包含是 ui 贡献的契约，意外外联是数据面越权）；
 * - 无 JS 桥（不 addJavascriptInterface）—— 页面无宿主 API 面。
 *
 * @param title 顶栏标题（贡献 displayTitle）
 * @param entryFile 已复核的入口文件（file:// 装载）
 * @param onDismiss 关闭回调
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PluginUiPageDialog(
    title: String,
    entryFile: java.io.File,
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
                // ── 顶栏：返回 / 标题 / 重载 ──
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

                // ── WebView：file:// only，外链拦截 ──
                val entryUrl = remember(entryFile) {
                    android.net.Uri.fromFile(entryFile).toString()
                }
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.allowFileAccess = true
                            // 插件页面是自包含 HTML：file URL 读同级 file 资源
                            // （css/js）需要这两项；外链 http(s) 由下方客户端拦截。
                            settings.allowFileAccessFromFileURLs = true
                            settings.allowUniversalAccessFromFileURLs = false
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView,
                                    request: WebResourceRequest
                                ): Boolean {
                                    val scheme = request.url.scheme ?: return true
                                    // 白名单：file（本插件目录内）；其余（http/https/
                                    // intent 等）全部拦截 —— 离线自包含契约。
                                    return scheme.equals("file", ignoreCase = true).not()
                                }
                            }
                            loadUrl(entryUrl)
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
