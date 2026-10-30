package com.apex.agent.platform.jiguan

import android.content.Context
import com.apex.agent.core.jiguan.JiguanOrchestrator
import com.apex.agent.core.jiguan.JiguanRuntime
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

/**
 * # 机关宿主 Hilt 装配
 *
 * 把 [JiguanHostFactory] 的装配产物挂进 DI 图。ExecEngine / PrivilegeManager
 * 由调用方经 [JiguanHostDependencies] 注入（app 层装配 —— ExecEngine 需要
 * app 包内的 ProotCommandSpawner，平台模块不可反向依赖 app，镜像 REA 的
 * 桥接哲学）。app 的 JiguanModule @Provides 本接口并调 factory。
 *
 * 之所以在平台模块出 Hilt 绑定（而不是全在 app）：runtime / orchestrator
 * 的绑定稳定性（app 只装配依赖，不重复装配引擎），未来 Theocracy 等宿主
 * 复用同一绑定面。
 */
@Module
@InstallIn(SingletonComponent::class)
object JiguanHostModule {

    @Provides
    @Singleton
    fun provideJiguanRuntime(deps: JiguanHostDependencies): JiguanRuntime =
        JiguanHostFactory.create(
            execEngine = deps.execEngine,
            privilege = deps.privilege,
            filesDir = deps.filesDir,
            workspaceDir = deps.workspaceDir,
        )

    @Provides
    @Singleton
    fun provideJiguanOrchestrator(runtime: JiguanRuntime): JiguanOrchestrator =
        JiguanHostFactory.orchestrator(runtime)
}
