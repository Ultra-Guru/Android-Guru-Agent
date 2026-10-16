package com.apex.agent.platform.browser.di

import android.content.Context
import com.apex.browser.chrome.BrowserOverlay
import com.apex.browser.chrome.bridge.ApexChromeWiring
import com.apex.browser.chrome.bridge.ApexChromeWiringFactory
import com.apex.browser.chrome.BrowserOverlayFactory
import com.apex.browser.engine.BrowserEngine
import com.apex.browser.engine.di.BrowserEngineFactory
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
}
