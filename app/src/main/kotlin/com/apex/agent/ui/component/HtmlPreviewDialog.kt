package com.apex.agent.ui.component

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.apex.agent.R
import java.io.File

/**
 * 应用内 HTML 预览对话框（Agent 产物即时可视化）。
 *
 * 背景：Agent 常产出 HTML 单页（报告 / 可视化 / 原型 / Canvas 演示）。旧路径只有
 * FileProvider → 外部浏览器（冷启动 1-3s + 跳出 App）。本组件用**应用内 WebView**：
 * - 应用进程内的 WebView 内核随首次打开完成预热，本地文件渲染 <100ms 量级；
 * - `loadDataWithBaseURL(父目录)` 加载内容 —— 相对路径资源（./style.css、img/）
 *   经 baseUrl 正常解析，且无 file:// scheme 白名单问题；
 * - 允许 JS / DOM storage（绝大多数生成物是交互式页面）；
 * - 外链 http(s) 放行（CDN 资源可用），file:// 跨目录导航拦截（防沙箱逃逸）。
 *
 * 性能要点（“加载速度要快”）：
 * - 不 clearCache、不 clearFormData —— 会话内内核复用；
 * - cacheMode = LOAD_DEFAULT：CDN 资源命中缓存直接回显；
 * - offscreenPreRaster：分屏/后台窗口恢复无重绘闪烁。
 *
 * v1.8.0 布局检查器：工具条新增 Layers 开关 —— 开启后对页面可见元素按
 * display 类型着色描边（flex 蓝 / grid 紫 / 定位橙 / 块绿），肉眼即可
 * 看穿页面骨架。实现为 [INSPECTOR_ON_JS] / [INSPECTOR_OFF_JS] 两个
 * 幂等脚本（只改 outline，不改布局盒模型，不产生重排）；开关状态经
 * [rememberUpdatedState] 传入 WebViewClient，页面加载完成即自动补描。
 */
@Composable
fun HtmlPreviewDialog(
    filePath: String,
    onDismiss: () -> Unit
) {
    val file = remember(filePath) { File(filePath) }
    val title = remember(filePath) { file.name }
    var reloadTick by remember { mutableIntStateOf(0) }
    var inspectorOn by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Surface(
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier.fillMaxSize()
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // ── 标题栏：文件名 + 布局检查器 + 刷新 + 关闭 ──
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    val inspectorCd = stringResource(R.string.html_preview_inspector)
                    IconToggleButton(
                        checked = inspectorOn,
                        onCheckedChange = { inspectorOn = it }
                    ) {
                        Icon(
                            Icons.Outlined.Layers,
                            contentDescription = inspectorCd,
                            tint = if (inspectorOn) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    val refreshCd = stringResource(R.string.html_preview_refresh)
                    IconButton(onClick = { reloadTick++ }) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = refreshCd,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    val closeCd = stringResource(R.string.html_preview_close)
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = closeCd,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // ── 检查器图例（开启时显示；颜色与注入脚本的描边色一致）──
                if (inspectorOn) {
                    InspectorLegendRow()
                }

                // ── WebView 内容区（reloadTick 变化触发 update 重载；key(filePath)
                // 保证切换预览文件时重建 WebView —— client 捕获的 base 目录同步刷新）──
                androidx.compose.runtime.key(filePath) {
                    HtmlWebView(
                        file = file,
                        reloadTick = reloadTick,
                        inspectorOn = inspectorOn,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
    // 系统返回键关闭预览（Dialog 默认已支持；显式声明防御 usePlatformDefaultWidth
    // 下部分厂商 ROM 的焦点转移异常）。
    BackHandler(onBack = onDismiss)
}

/** 图例行：色点 + 标签 ×4（Flex / Grid / 定位 / 块）。 */
@Composable
private fun InspectorLegendRow() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
    ) {
        LegendDot(INSPECTOR_COLOR_FLEX, stringResource(R.string.html_preview_legend_flex))
        LegendDot(INSPECTOR_COLOR_GRID, stringResource(R.string.html_preview_legend_grid))
        LegendDot(INSPECTOR_COLOR_POSITIONED, stringResource(R.string.html_preview_legend_positioned))
        LegendDot(INSPECTOR_COLOR_BLOCK, stringResource(R.string.html_preview_legend_block))
    }
}

@Composable
private fun LegendDot(colorHex: Long, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(Color(colorHex))
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp)
        )
    }
}

/** WebView 宿主：读文件 → loadDataWithBaseURL（快 + 相对资源可用）。 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun HtmlWebView(
    file: File,
    reloadTick: Int,
    inspectorOn: Boolean,
    modifier: Modifier = Modifier
) {
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    // baseUrl = 父目录：相对引用（./x.js、images/y.png、子页面 a 链接）可解析。
    val baseUrl = remember(file) {
        file.parentFile?.absolutePath?.let { "file://$it/" } ?: "about:blank"
    }
    // 开关的“最新值”视图：WebViewClient 回调（非组合域）读取时拿到当前状态。
    val currentInspector by rememberUpdatedState(inspectorOn)
    // 装载守卫：update 回调可能因父级重组被多次调用，同一 (file, tick) 只装载一次
    // —— 否则检查器开关这类无关重组会触发整页重载（闪烁 + 轮廓丢失）。
    var lastLoadKey by remember { mutableStateOf<Pair<String, Int>?>(null) }
    // WebView 实例引用：工厂建一次，开关效应与页面完成回调共用。
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    Box(modifier = modifier) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    // 交互式生成物需要 JS + DOM storage。
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    // 本地文件 + CDN 混合内容：允许两者（生成物常见 <script src="https://cdn...">）。
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    settings.allowFileAccess = true
                    settings.loadsImagesAutomatically = true
                    settings.cacheMode = WebSettings.LOAD_DEFAULT
                    // 窗口恢复时直接用离屏栅格化位图，避免闪烁重绘。
                    @Suppress("DEPRECATION")
                    settings.offscreenPreRaster = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            loading = false
                            // 页面就绪即补描（覆盖“开关先于加载完成”的时序）。
                            if (currentInspector) {
                                view?.evaluateJavascript(INSPECTOR_ON_JS, null)
                            }
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            val url = request?.url ?: return false
                            val scheme = url.scheme ?: return false
                            if (!scheme.equals("file", true)) {
                                // http(s) 外链：放行（CDN 资源 / 跳转链接）。
                                return false
                            }
                            // file:// 导航：仅放行同目录内页面（多页生成物可互相跳转），
                            // 跨目录拦截（防越出预览目录读取沙箱其它文件）。
                            return !url.toString().startsWith(baseUrl, ignoreCase = true)
                        }
                    }
                    webChromeClient = WebChromeClient()
                }.also { w -> webViewRef = w }
            },
            update = { webView ->
                val loadKey = file.absolutePath to reloadTick
                if (lastLoadKey != loadKey) {
                    lastLoadKey = loadKey
                    // 初次组合与手动刷新（reloadTick 变化）时（重新）加载。
                    loading = true
                    loadError = null
                    val html = runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
                    if (html == null) {
                        loadError = file.absolutePath
                        loading = false
                    } else {
                        webView.loadDataWithBaseURL(baseUrl, html, "text/html", "utf-8", null)
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // 开关即时生效：对当前已渲染页面执行描边/还原。
        // 页面尚未就绪时为无害 no-op（about:blank 上脚本返回 no-body/restored:0），
        // 真正的补描由 WebViewClient.onPageFinished 兜底。
        LaunchedEffect(inspectorOn) {
            val target = webViewRef
            if (target == null) return@LaunchedEffect
            if (inspectorOn) {
                target.evaluateJavascript(INSPECTOR_ON_JS, null)
            } else {
                target.evaluateJavascript(INSPECTOR_OFF_JS, null)
            }
        }
    }
}

// ───────────────────────── 布局检查器注入脚本 ─────────────────────────
// 幂等描边：仅修改 outline（不占布局、不触发重排；outline 不影响盒模型）。
// touched 台账记录 (元素, 原outline)，关闭时精确还原，绝不清空页面自带 outline。

private const val INSPECTOR_COLOR_FLEX = 0xFF3B82F6     // 蓝
private const val INSPECTOR_COLOR_GRID = 0xFFA855F7     // 紫
private const val INSPECTOR_COLOR_POSITIONED = 0xFFF97316 // 橙
private const val INSPECTOR_COLOR_BLOCK = 0xFF22C55E    // 绿

private const val INSPECTOR_ON_JS = """
(function(){
  if(!document.body) return 'no-body';
  var prev=window.__apexInspectorTouched||[];
  for(var i=0;i<prev.length;i++){try{prev[i][0].style.outline=prev[i][1];}catch(e){}}
  var touched=[];
  var all=document.body.querySelectorAll('*');
  var n=0;
  for(var i=0;i<all.length&&n<1500;i++){
    var el=all[i];var tag=el.tagName.toLowerCase();
    if(tag==='script'||tag==='style'||tag==='noscript')continue;
    var s=getComputedStyle(el);
    if(s.display==='none'||s.visibility==='hidden')continue;
    var r=el.getBoundingClientRect();
    if(r.width<2&&r.height<2)continue;
    var color;
    if(s.display==='flex'||s.display==='inline-flex')color='#3b82f6';
    else if(s.display==='grid'||s.display==='inline-grid')color='#a855f7';
    else if(s.position!=='static')color='#f97316';
    else if(s.display==='block'||s.display==='inline-block'||s.display==='list-item')color='#22c55e';
    else continue;
    touched.push([el,el.style.outline]);
    try{el.style.outline='1.5px solid '+color;}catch(e){}
    n++;
  }
  window.__apexInspectorTouched=touched;
  return 'outlined:'+n;
})()
"""

private const val INSPECTOR_OFF_JS = """
(function(){
  var t=window.__apexInspectorTouched||[];
  for(var i=0;i<t.length;i++){try{t[i][0].style.outline=t[i][1];}catch(e){}}
  window.__apexInspectorTouched=[];
  return 'restored:'+t.length;
})()
"""
