package com.apex.agent.di

import android.content.Context
import com.apex.agent.core.engine.*
import com.apex.agent.core.engine.compression.ArchivingCompressor
import com.apex.agent.core.engine.compression.ContextCompressor
import com.apex.agent.core.engine.compression.HybridCompressor
import com.apex.agent.core.engine.compression.ToolOutputTruncator
import com.apex.agent.core.engine.goal.FastModelGoalVerifier
import com.apex.agent.core.engine.goal.GoalModeCoordinator
import com.apex.agent.core.llm.LlmClient
import com.apex.agent.core.llm.runtime.ModelRuntime
import com.apex.agent.ui.screen.settings.SettingsRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AgentModule {

    @Provides
    @Singleton
    fun providePrivilegeInfoProvider(): PrivilegeInfoProvider {
        return AndroidPrivilegeInfoProvider()
    }

    /**
     * Tool System v3：环境能力信息提供者（ToolEnvironmentState 快照 →
     * system prompt Live Environment 段，与环境门同源）。
     */
    @Provides
    @Singleton
    fun provideEnvironmentInfoProvider(
        environmentState: com.apex.agent.core.tools.ToolEnvironmentState
    ): EnvironmentInfoProvider {
        return AndroidEnvironmentInfoProvider(environmentState)
    }

    @Provides
    @Singleton
    fun provideConversationMemory(
        @ApplicationContext context: Context
    ): ConversationMemory {
        return SharedPrefsConversationMemory(context)
    }

    /**
     * 上下文压缩器（与 AgentConfig 同源：从设置中心读取压缩阈值与工具输出上限，
     * 设置变更需重启应用生效）。
     */
    @Provides
    @Singleton
    fun provideContextArchive(@ApplicationContext context: Context): FileContextArchive {
        // #396：会话级上下文归档（被压缩裁剪的原始消息）；引擎生命周期
        // 边界（新会话/恢复会话）由 ApexAgentEngine 负责清空。
        return FileContextArchive(java.io.File(context.filesDir, "context_archive"))
    }

    @Provides
    @Singleton
    fun provideContextCompressor(
        llmClient: LlmClient,
        modelRuntime: ModelRuntime,
        repo: SettingsRepository,
        contextArchive: FileContextArchive
    ): ContextCompressor {
        val agent = repo.agentSettings.value
        val maxChars = agent.maxToolOutputLength.coerceIn(200, 100_000)
        // #396：归档装饰器包裹 —— 压缩前被裁剪消息先进归档（无损可检索），
        // 再从活跃历史移除；并在头部 upsert 一条 [CONTEXT ARCHIVE] 检索单。
        return ArchivingCompressor(
            delegate = HybridCompressor(
                llmClient = llmClient,
                toolTruncator = ToolOutputTruncator(
                    maxChars = maxChars,
                    headChars = (maxChars * 0.6f).toInt().coerceAtLeast(100),
                    tailChars = (maxChars * 0.3f).toInt().coerceAtLeast(50)
                ),
                maxContextTokens = agent.maxContextTokens,
                threshold = agent.compressionThreshold,
                // T72 §十：第 3 层 LLM 摘要走 SUMMARY 角色（路由 + 降级）
                modelRuntime = modelRuntime
            ),
            archive = contextArchive
        )
    }

    @Provides
    @Singleton
    fun provideGoalCoordinator(
        modelRuntime: ModelRuntime,
        settingsRepository: SettingsRepository
    ): GoalModeCoordinator = GoalModeCoordinator(
        verifier = FastModelGoalVerifier(modelRuntime),
        verifyEnabled = { settingsRepository.agentSettings.value.goalVerifierEnabled }
    )

}
