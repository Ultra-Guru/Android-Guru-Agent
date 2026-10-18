package com.apex.agent.platform.browser.di

import android.content.Context
import com.apex.browser.chrome.BrowserOverlay
import com.apex.browser.chrome.InAppChromeHost
import com.apex.browser.chrome.InAppChromeHostFactory
import com.apex.browser.chrome.LiveViewOverlay
import com.apex.browser.chrome.LiveViewOverlayFactory
import com.apex.browser.chrome.bridge.ApexChromeWiring
import com.apex.browser.chrome.bridge.ApexChromeWiringFactory
import com.apex.browser.chrome.BrowserOverlayFactory
import com.apex.browser.engine.BookmarkStore
import com.apex.browser.engine.BookmarkStoreFactory
import com.apex.browser.engine.BrowserEngine
import com.apex.browser.engine.DownloadCenter
import com.apex.browser.engine.DownloadCenterFactory
import com.apex.browser.engine.EngineDisplayController
import com.apex.browser.engine.EngineDisplayControllerFactory
import com.apex.browser.engine.di.BrowserEngineFactory
import com.apex.agent.platform.browser.BrowserSessionTools
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 浏览器库（apex-browser-kit）Hilt 接线 —— 拆库后的宿主侧 DI 补位。
 * v1.5.0 随浏览器大功能内置化从 app 迁入 :platform:browser-agent。
 *
 * 库本身零 DI 依赖（F1：Hilt + Android Library 组合会强绑下游宿主），单例由
 * 库内工厂 object 提供；本 Module 把工厂产物注册进宿主 Hilt 图，宿主原有的
 * `@Inject BrowserEngine / BrowserOverlay` 注入点（ApexCoreService /
 * ToolModule / CyberNeonBallManager）零改动。
 *
 * 三件套与拆库前的 @Singleton 语义一致：
 * - [BrowserEngine]：无头自动化引擎（ToolModule 的 BrowserAgentTools 消费）
 * - [ApexChromeWiring]：引擎 ↔ chrome 接线器（浮窗链式 client / 弹窗路由）
 * - [BrowserOverlay]：完整浏览器浮窗（WAITING_HUMAN 时自动展开）
 * - [LiveViewOverlay]（v1.4.0）：透明实时页面悬浮窗——AGENT_DRIVING 自动弹出，
 *   点悬浮球/浏览器功能页「查看实时页面」切换；GHOST 无头宿主保 WebView 测量
 * - [InAppChromeHost]（v1.5.1）：App 内嵌浏览器宿主——BrowserScreen 浏览器页签
 *   把完整浏览器嵌进 App 自己的窗口（零悬浮窗权限）；与两个浮窗经
 *   InAppHostingArbiter 互斥（in-app 承载期间浮窗让位）
 *
 * 视觉挂钩（BrowserVisualHook）：霓虹球等宿主视觉装饰仍走
 * CyberNeonBallManager 自己订阅 BrowserUiCallback 的旧链路（行为零变化）；
 * 需要把球挂进 Overlay 生命周期时，改为给 provideBrowserOverlay 传
 * HostVisualHook（见库 README「DI 接法」）。
 *
 * Hilt 跨模块说明：@Module 经 KSP 聚合进 app 的 SingletonComponent，
 * app 侧无需 import 或注册，注入点零改动。
 */
@Module
@InstallIn(SingletonComponent::class)
object BrowserKitModule {

    @Provides
    @Singleton
    fun provideBrowserEngine(@ApplicationContext ctx: Context): BrowserEngine =
        BrowserEngineFactory.get(ctx)

    @Provides
    @Singleton
    fun provideApexChromeWiring(
        @ApplicationContext ctx: Context,
        engine: BrowserEngine,
    ): ApexChromeWiring = ApexChromeWiringFactory.get(ctx, engine)

    @Provides
    @Singleton
    fun provideBrowserOverlay(
        @ApplicationContext ctx: Context,
        engine: BrowserEngine,
        wiring: ApexChromeWiring,
    ): BrowserOverlay = BrowserOverlayFactory.get(ctx, engine, wiring)

    /** v1.4.0：实时页面悬浮窗（透明卡片，AGENT_DRIVING 自动弹出）。 */
    @Provides
    @Singleton
    fun provideLiveViewOverlay(
        @ApplicationContext ctx: Context,
        engine: BrowserEngine,
    ): LiveViewOverlay = LiveViewOverlayFactory.get(ctx, engine)

    /** v1.5.1：App 内嵌浏览器宿主（BrowserScreen 浏览器页签的承载控制器）。 */
    @Provides
    @Singleton
    fun provideInAppChromeHost(
        engine: BrowserEngine,
        wiring: ApexChromeWiring,
    ): InAppChromeHost = InAppChromeHostFactory.get(engine, wiring)

    /* ── v1.7.0 会话增强组件（库内单例工厂；与 ApexChromeWiring 内部取的是同一实例，
       书星/面板/下载台账与 Agent 工具共享同一份状态——这是「工厂单例 + DI 直通」
       双入口不分裂的关键）── */

    /** v1.7.0：书签持久化仓库（chrome 书签面板 + BrowserBookmarks 工具共用）。 */
    @Provides
    @Singleton
    fun provideBookmarkStore(@ApplicationContext ctx: Context): BookmarkStore =
        BookmarkStoreFactory.get(ctx)

    /** v1.7.0：下载台账中心（跨重启可查 / cancel / retry；BrowserDownloads 工具消费）。 */
    @Provides
    @Singleton
    fun provideDownloadCenter(
        @ApplicationContext ctx: Context,
        engine: BrowserEngine,
    ): DownloadCenter = DownloadCenterFactory.get(ctx, engine)

    /** v1.7.0：显示控制器（文字缩放 / 夜间模式 / 清除浏览数据；chrome 显示设置面板共用）。 */
    @Provides
    @Singleton
    fun provideEngineDisplayController(engine: BrowserEngine): EngineDisplayController =
        EngineDisplayControllerFactory.get(engine)

    /** v1.7.0：会话级浏览器工具集（9 个新工具，ToolModule 注册进 registry）。 */
    @Provides
    @Singleton
    fun provideBrowserSessionTools(
        engine: BrowserEngine,
        tracer: com.apex.browser.engine.BrowserTracer,
        downloads: DownloadCenter,
        bookmarks: BookmarkStore,
        display: EngineDisplayController,
    ): BrowserSessionTools = BrowserSessionTools(engine, tracer, downloads, bookmarks, display)
}
