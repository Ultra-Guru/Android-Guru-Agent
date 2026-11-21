package com.apex.agent.di

import android.content.Context
import android.util.Log
import com.apex.agent.core.engine.templates.playbook.PlaybookRegistry
import com.apex.agent.core.tools.SafeAgentTool
import com.apex.agent.core.tools.ToolRegistry
import com.apex.agent.tools.PlaybookTool
import com.apex.agent.ui.screen.code.PlaybookSessionStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 任务剧本 DI —— PlaybookRegistry 首次接线到 app 层（TemplateModule 同款
 * 纪律：单例自持 SupervisorJob + IO 播种，异常只留痕不外抛）。
 *
 * 落盘 `<filesDir>/playbooks/playbooks.json`；生产端构造后异步播种内置
 * 五套（幂等缺则补），onChanged 回调驱动收集方刷新。
 *
 * **工具通道挂载**（PR-C）：同一异步段把 PlaybookTool 注册进既有
 * ToolRegistry 单例——ToolModule 贴预算红线不能碰；注册挂在
 * PlaybookRegistry 供给链上（CodeViewModel 构造必经），时序与 Coding
 * 工位首次进入对齐（首轮 run 前完成；registryVersion 自增驱动 UI 刷新）。
 */
@Module
@InstallIn(SingletonComponent::class)
object PlaybookModule {

    @Provides
    @Singleton
    fun providePlaybookRegistry(
        @ApplicationContext context: Context,
        toolRegistry: ToolRegistry,
        sessionStore: PlaybookSessionStore
    ): PlaybookRegistry {
        val registry = PlaybookRegistry(
            storageDir = File(context.filesDir, "playbooks")
        )
        val seedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        seedScope.launch {
            runCatching { registry.reseedBuiltIns() }
                .onFailure { Log.w("PlaybookModule", "seed built-in playbooks failed: ${it.message}") }
            runCatching { toolRegistry.register(SafeAgentTool(PlaybookTool(registry, sessionStore))) }
                .onFailure { Log.w("PlaybookModule", "register PlaybookTool failed: ${it.message}") }
        }
        return registry
    }
}
