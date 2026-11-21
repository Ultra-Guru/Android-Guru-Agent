package com.apex.agent.di

import android.content.Context
import com.apex.agent.platform.terminal.runtime.TerminalRuntime
import com.apex.agent.platform.terminal.searxng.SearxngCommandPort
import com.apex.agent.platform.terminal.searxng.SearxngInstaller
import com.apex.agent.platform.terminal.searxng.SearxngLifecycleCoordinator
import com.apex.agent.platform.terminal.searxng.SearxngProotCommandPort
import com.apex.agent.platform.terminal.searxng.SearxngSettingsWriter
import com.apex.agent.platform.terminal.ubuntu.lifecycle.UbuntuLifecycleCoordinator
import com.apex.agent.searxng.SearxngHttpClient
import com.apex.agent.searxng.SearxngProvider
import com.apex.agent.searxng.SearxngSettingsStore
import com.apex.agent.platform.terminal.proot.LinuxExecutionContextFactory
import com.apex.agent.platform.terminal.proot.ProotExecutor
import com.apex.agent.core.tools.builtin.LocalSearchSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

/**
 * ═══ SearXNG 本地搜索 · DI 装配（SearXNG Local Search Spec v1.0 §4/§8）═══
 *
 * 装配关系：
 * ```
 * SearxngSettingsStore(SharedPreferences) ──┐
 * LinuxExecutionContextFactory + ProotExecutor ─▶ SearxngProotCommandPort ─▶ Installer/Writer
 * TerminalRuntime + UbuntuLifecycleCoordinator(READY 门禁) + SearxngHttpClient(healthz)
 *   ──▶ SearxngLifecycleCoordinator(@Singleton)
 *        └─▶ SearxngProvider ──▶ LocalSearchSource（ToolModule 的 WebSearchTool 链首）
 * ```
 *
 * 生命周期：Coordinator 为进程级 @Singleton，内部 scope 自持（SupervisorJob +
 * Default —— watchdog/watcher/start 序列宿主）。ApexCoreService 承载 reconcile/
 * enable/disable 的调用入口（Spec §8「UI 只发 action，Service 内协程调 Coordinator」）。
 */
@Module
@InstallIn(SingletonComponent::class)
object SearxngModule {

    @Provides
    @Singleton
    fun provideSearxngSettingsStore(
        @ApplicationContext context: Context
    ): SearxngSettingsStore = SearxngSettingsStore(context)

    @Provides
    @Singleton
    fun provideSearxngCommandPort(
        contextFactory: LinuxExecutionContextFactory,
        executor: ProotExecutor
    ): SearxngCommandPort = SearxngProotCommandPort(contextFactory, executor)

    @Provides
    @Singleton
    fun provideSearxngInstaller(
        commands: SearxngCommandPort
    ): SearxngInstaller = SearxngInstaller(commands)

    @Provides
    @Singleton
    fun provideSearxngSettingsWriter(
        commands: SearxngCommandPort,
        intent: SearxngSettingsStore
    ): SearxngSettingsWriter = SearxngSettingsWriter(commands, intent)

    @Provides
    @Singleton
    fun provideSearxngHttpClient(
        intent: SearxngSettingsStore
    ): SearxngHttpClient = SearxngHttpClient(port = { intent.port() })

    @Provides
    @Singleton
    fun provideSearxngLifecycleCoordinator(
        runtime: TerminalRuntime,
        installer: SearxngInstaller,
        settingsWriter: SearxngSettingsWriter,
        intent: SearxngSettingsStore,
        httpClient: SearxngHttpClient,
        ubuntu: UbuntuLifecycleCoordinator
    ): SearxngLifecycleCoordinator = SearxngLifecycleCoordinator(
        runtime = runtime,
        installer = installer,
        settingsWriter = settingsWriter,
        intent = intent,
        healthProbe = { httpClient.healthz() },
        ubuntuReady = {
            ubuntu.stateFlow.value.phase == UbuntuLifecycleCoordinator.Phase.READY
        },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    )

    @Provides
    @Singleton
    fun provideLocalSearchSource(
        coordinator: SearxngLifecycleCoordinator,
        httpClient: SearxngHttpClient
    ): LocalSearchSource = SearxngProvider(coordinator, httpClient)
}
