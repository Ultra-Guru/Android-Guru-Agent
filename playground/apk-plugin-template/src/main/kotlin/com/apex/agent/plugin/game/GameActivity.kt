package com.apex.agent.plugin.game

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout

/**
 * 游戏壳 Activity —— 全屏 WebView 运行 `assets/game.html`。
 *
 * 沙箱纪律与宿主游乐场 HtmlGameScreen 同款：
 *  - JS + DOM storage 开（游戏逻辑与 localStorage 战绩依赖它）；
 *  - 零网络请求预期：http(s) 外链一律拦截（比宿主更严 —— 游戏插件
 *    本体必须自包含）；
 *  - 系统返回键直接结束 Activity（游戏自身处理返回手势语义）。
 */
@SuppressLint("SetJavaScriptEnabled")
class GameActivity : Activity() {

    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            // 自包含插件：file:// 入口可载，外链一律拦截
            settings.allowFileAccess = true
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            setBackgroundColor(Color.TRANSPARENT)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    // 零网络纪律：非 file:// 一律拦截（自包含插件没有外链需求）
                    val scheme = request?.url?.scheme
                    return scheme != null && !scheme.equals("file", true)
                }
            }
            webChromeClient = WebChromeClient()
        }

        val root = FrameLayout(this)
        root.addView(webView)
        setContentView(root)

        webView.loadUrl("file:///android_asset/game.html")
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
