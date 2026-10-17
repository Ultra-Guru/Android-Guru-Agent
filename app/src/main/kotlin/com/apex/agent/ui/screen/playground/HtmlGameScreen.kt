package com.apex.agent.ui.screen.playground

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * ═══════════════════════════════════════════════════════════════
 *  HtmlGameScreen —— HTML 游戏插件的街机屏（WebView 宿主）
 * ═══════════════════════════════════════════════════════════════
 *
 * 游乐场插件化的运行时终点：目录里点开一张游戏卡 → 本屏全幅接管 →
 * WebView 加载该插件入口（assets 内置或 filesDir 安装，同一 file:// 契约）。
 *
 * 接线沿 HtmlPreviewDialog 验证过的同款纪律：
 *  - JS + DOM storage 开启（游戏逻辑与 localStorage 战绩都依赖它）；
 *  - `allowFileAccess`：file:// 入口可加载（assets / 应用私有目录两形态同构）；
 *  - file:// 导航仅放行**同插件目录**内页面（防越出插件目录读其它文件）；
 *  - 不 clearCache —— 会话内 WebView 内核复用，二次进游戏 <100ms 量级；
 *  - reloadTick 手动重载（顶栏刷新钮）；BackHandler → onExit（统一返回语义）。
 *
 * 生命周期：AndroidView 的 onRelease 在离开组合时 destroy WebView ——
 * 游戏插件不留后台实例（与「切抽屉页即停帧」的字节风暴同款免费暂停哲学）。
 */
@Composable
fun HtmlGameScreen(
    game: PlaygroundGame,
    onExit: () -> Unit,
    modifier: Modifier = Modifier
) {
    var reloadTick by remember { mutableIntStateOf(0) }

    Column(modifier = modifier.fillMaxSize()) {
        GameTopBar(
            title = game.displayName,
            engineBadge = game.engineBadge,
            onBack = onExit,
            onReload = { reloadTick++ }
        )
        Box(modifier = Modifier.fillMaxSize()) {
            GameWebView(
                entryUrl = game.entryUrl,
                reloadTick = reloadTick
            )
        }
    }
    BackHandler(onBack = onExit)
}

/** 顶栏：返回 + 游戏名 + 引擎徽章 + 重载。 */
@Composable
private fun GameTopBar(
    title: String,
    engineBadge: String,
    onBack: () -> Unit,
    onReload: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "退出游戏",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(22.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (engineBadge.isNotBlank()) {
                    Text(
                        text = engineBadge,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            IconButton(onClick = onReload) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "重载游戏",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/** WebView 宿主：入口 URL 直载（assets / filesDir 同构的 file:// 契约）。 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun GameWebView(
    entryUrl: String,
    reloadTick: Int
) {
    // 插件目录前缀（file:// 导航白名单）：入口 URL 去掉最后的 index.html
    val dirPrefix = remember(entryUrl) {
        entryUrl.substringBeforeLast('/') + "/"
    }

    // key(entryUrl, reloadTick)：换游戏 / 手动重载 = 整机重建
    // （旧实例经 onRelease destroy —— 插件不留后台实例）
    key(entryUrl, reloadTick) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    // 游戏插件需要 JS + DOM storage（逻辑 / localStorage 战绩）
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    // file:// 入口（assets 与 filesDir 安装两形态同构）
                    settings.allowFileAccess = true
                    settings.cacheMode = WebSettings.LOAD_DEFAULT
                    // 分屏/后台窗口恢复无闪烁重绘
                    @Suppress("DEPRECATION")
                    settings.offscreenPreRaster = true
                    // 游戏自带触觉与音效逻辑（无自动播放需求，保守关闭手势豁免）
                    settings.mediaPlaybackRequiresUserGesture = true
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            val url = request?.url ?: return false
                            val scheme = url.scheme ?: return false
                            if (!scheme.equals("file", true)) {
                                // http(s) 外链放行（游戏理论上零请求；留 CDN 兜底）
                                return false
                            }
                            // file:// 导航：仅放行同插件目录（多文件插件可互引）
                            return !url.toString().startsWith(dirPrefix, ignoreCase = true)
                        }
                    }
                    webChromeClient = WebChromeClient()
                }
            },
            update = { webView ->
                webView.loadUrl(entryUrl)
            },
            onRelease = { webView ->
                webView.destroy()
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}
