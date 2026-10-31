package com.apex.agent.ui.screen.browser

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.apex.agent.R
import com.apex.agent.ui.glass.GlassButton
import com.apex.agent.ui.glass.GlassCard
import com.apex.agent.ui.theme.statusWarning
import com.apex.browser.chrome.BrowserChrome
import com.apex.browser.chrome.BrowserEngineGateway
import com.apex.browser.chrome.InAppChromeHost
import com.apex.browser.engine.BrowserEngine

// ═══════════════════════════════════════════════════════════════════════════
//  App 内嵌浏览器页签（v1.5.1）——「内置浏览器到底有没有」的正面回答
// ═══════════════════════════════════════════════════════════════════════════

/*
 * 浏览器页签：把 apex-kit-browser 的完整浏览器嵌进 App 自己的窗口 ——
 * [BrowserChrome]（地址栏即进度条 / 标签条与总览 / 页内查找 / 下载面板 /
 * JS 弹窗 / 网页权限浮层）+ 引擎活动 WebView（[InAppChromeHost] 承载）。
 *
 * 零悬浮窗权限：本页在 Activity 组合树内，SYSTEM_ALERT_WINDOW 未授予也
 * 完整可用（旧实现所有用户可见面都是系统悬浮窗，无权限时静默降级 =
 * 用户「根本看不见浏览器」）。悬浮球 / 实时页面浮窗 / 全屏接管浮窗在
 * 权限授予时按原有行为叠加（库侧 InAppHostingArbiter 保证互斥：本页
 * 承载期间浮窗让位，离开本页或 App 退后台时按状态机恢复）。
 *
 * 承载生命周期：页面进入组合 attach（换挂 WebView + resync chrome 数据
 * 源）；离开组合 detach（WebView 归还引擎无头驱动，自动化坐标不丢）；
 * Activity ON_PAUSE 让位 / ON_RESUME 回位 —— 用户切去别的 App 时实时
 * 卡片可弹出跟看，回到本页继续内嵌浏览。
 *
 * 与 Agent 的关系：Agent 的 34 个 browser_* 工具驱动同一引擎/同一组
 * WebView —— Agent 驾驶时本页就是「查看实时页面」的全屏形态（零拷贝同
 * 一实例）；「接管」走 WAITING_HUMAN 显式握手（顶部横幅 + Agent 工具
 * 锁定），交还后 Agent 继续。
 */

/** 浏览器页签：空态引导（HIDDEN 且无标签）或完整内嵌浏览器。 */
@Composable
fun InAppBrowserPane(
    gateway: BrowserEngineGateway,
    inAppHost: InAppChromeHost,
    sessionState: BrowserEngine.BrowserSessionState,
    tabsEmpty: Boolean,
    overlayGranted: Boolean,
    onOpenBrowser: () -> Unit,
    onHandBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // ── 空态：会话从未开启（HIDDEN 且无标签）→ 引导卡 + 悬浮窗权限提示 ──
    if (sessionState == BrowserEngine.BrowserSessionState.HIDDEN && tabsEmpty) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.Top),
        ) {
            InAppHeroCard(onOpenBrowser = onOpenBrowser)
            if (!overlayGranted) OverlayPermissionCard()
        }
        return
    }

    // ── 完整浏览器：WebView 容器（组合树内兄弟层）+ BrowserChrome ──
    var container by remember { mutableStateOf<FrameLayout?>(null) }
    var headerPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    // 页面区留白：chrome 头部实测高度（首帧回调即校正）；初始估计对齐
    // ChromeBar+余量（与库侧 BrowserOverlay 同款首帧策略，仅一帧误差）
    val topInsetDp = with(density) {
        (if (headerPx > 0) headerPx else 64.dp.toPx().toInt()).toDp()
    }

    // 承载生命周期（全主线程）：进入组合 attach → 离开 detach；
    // Activity 暂停让位（浮窗可接管实时画面）/ 恢复回位。
    LaunchedEffect(container) {
        container?.let { inAppHost.attach(it) }
    }
    DisposableEffect(Unit) {
        onDispose { inAppHost.detach() }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { inAppHost.pause() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { inAppHost.resume() }

    Box(modifier = modifier.fillMaxSize()) {
        // WebView 承载容器：chrome 头部以下留白（真实页面，与浮窗共用同一实例）
        AndroidView(
            factory = { ctx -> FrameLayout(ctx).also { container = it } },
            modifier = Modifier
                .fillMaxSize()
                .padding(top = topInsetDp),
        )
        // 完整浏览器外观：transparentPageArea = 真实 WebView 在组合树外
        // （上方 AndroidView 兄弟层），页区透出页面而非纯色底。
        BrowserChrome(
            gateway = gateway,
            handoffBanner = { InAppHandoffBanner(state = sessionState, onHandBack = onHandBack) },
            onChromeHeightChanged = { px -> headerPx = px },
            transparentPageArea = true,
            pageSlot = {}, // WebView 由上方容器承载，页区不重复渲染
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════════
//  区块组件
// ═══════════════════════════════════════════════════════════════════════════

/** 空态引导卡：打开浏览器（一键开会话：复用/新建标签 + 进入人工接管）。 */
@Composable
private fun InAppHeroCard(onOpenBrowser: () -> Unit) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                Icons.Outlined.Public,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                stringResource(R.string.browser_screen_inapp_hero_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(R.string.browser_screen_inapp_hero_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            GlassButton(
                text = stringResource(R.string.browser_screen_inapp_open),
                onClick = onOpenBrowser,
                modifier = Modifier.fillMaxWidth(),
                accent = MaterialTheme.colorScheme.primary,
                leadingIcon = Icons.Outlined.Add,
            )
        }
    }
}

/**
 * 悬浮窗权限引导卡（v1.5.1）：浏览器本页**无需**任何权限即可用；但悬浮球 /
 * 实时页面浮窗 / 后台接管浮窗走系统悬浮窗（SYSTEM_ALERT_WINDOW），未授予
 * 时那些入口静默降级——旧版用户「什么都看不见」的直接根源，这里不再静默。
 */
@Composable
fun OverlayPermissionCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    GlassCard(modifier = modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PaneCardHeader(
                icon = Icons.Outlined.Layers,
                title = stringResource(R.string.browser_screen_overlay_title),
                tint = statusWarning(),
            )
            Text(
                stringResource(R.string.browser_screen_overlay_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            GlassButton(
                text = stringResource(R.string.browser_screen_overlay_grant),
                onClick = {
                    runCatching {
                        // 直达本应用悬浮窗授权页（与 OnboardingPermissionSteps/
                        // PermissionsScreen 同款惯例：API 30+ 字面值常量）
                        val pkg = Uri.parse("package:${context.packageName}")
                        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            Intent("android.settings.action.MANAGE_APP_OVERLAY_SETTINGS", pkg)
                        } else {
                            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg)
                        }
                        context.startActivity(intent)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = Icons.Outlined.Layers,
            )
        }
    }
}

/**
 * 人工接管横幅（显式握手的人机界面，注入 BrowserChrome 的 handoffBanner 槽）：
 * WAITING_HUMAN 时提示 Agent 工具已锁定，并给出「交还 Agent」出口。
 */
@Composable
private fun InAppHandoffBanner(
    state: BrowserEngine.BrowserSessionState,
    onHandBack: () -> Unit,
) {
    if (state != BrowserEngine.BrowserSessionState.WAITING_HUMAN) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(statusWarning().copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.CheckCircle,
            contentDescription = null,
            tint = statusWarning(),
            modifier = Modifier.height(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            stringResource(R.string.browser_screen_inapp_handoff),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        TextButton(
            onClick = onHandBack,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 10.dp, vertical = 2.dp,
            ),
        ) {
            Text(
                stringResource(R.string.browser_screen_hand_back),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** 区块卡头：小图标 + 标题（图标色可注入，权限卡用警示色）。 */
@Composable
private fun PaneCardHeader(
    icon: ImageVector,
    title: String,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = tint)
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
