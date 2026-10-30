package com.apex.agent.di

import android.content.Context
import com.apex.agent.github.GithubTokenManager
import com.apex.agent.platform.TerminalExec.ExecEngine
import com.apex.agent.platform.jiguan.JiguanHostDependencies
import com.apex.agent.platform.privilege.PrivilegeManager
import com.apex.agent.platform.terminal.proot.PRootCapabilitySource
import com.apex.agent.platform.terminal.proot.PRootHostEnvironment
import com.apex.agent.tools.PrivilegedCommandSpawner
import com.apex.agent.tools.ProotCommandSpawner
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

/**
 * # JiguanModule —— 机关（全能系统级 Agent 终端 V3.0）的 app 层装配
 *
 * 只做一件事：构造 ExecEngine（与 FusionModule 的 REA 桥**同一语义**：PRoot
 * Ubuntu 优先 → su > Shizuku > local-sh 回落）与 [PrivilegeManager]
 * （AppModule 既有单例）打包成 [JiguanHostDependencies]，交给
 * platform:jiguan-host 的 Hilt 绑定（JiguanRuntime / JiguanOrchestrator 在
 * 平台模块出单例 —— 本模块不重复装配引擎）。
 */
@Module
@InstallIn(SingletonComponent::class)
object JiguanModule {

    @Provides
    @Singleton
    fun provideJiguanHostDependencies(
        @ApplicationContext context: Context,
        hostEnvironment: PRootHostEnvironment,
        rootfsBaseDir: File,
        capabilitySource: PRootCapabilitySource,
        githubTokenManager: GithubTokenManager,
        privilegeManager: PrivilegeManager,
    ): JiguanHostDependencies = JiguanHostDependencies(
        execEngine = ExecEngine(
            ProotCommandSpawner(
                hostEnvironment = hostEnvironment,
                rootfsDir = rootfsBaseDir,
                isRootfsReady = { File(rootfsBaseDir, "current").exists() },
                defaultWorkspaceDir = File(context.filesDir, "linux/workspaces/default"),
                persistentHomeDir = File(context.filesDir, "linux/home"),
                fallback = PrivilegedCommandSpawner(),
                capabilities = capabilitySource::invoke,
                gitHubTokenProvider = { githubTokenManager.getToken() },
            ),
        ),
        privilege = privilegeManager,
        filesDir = context.filesDir,
        workspaceDir = File(context.filesDir, "linux/workspaces/default"),
    )
}
