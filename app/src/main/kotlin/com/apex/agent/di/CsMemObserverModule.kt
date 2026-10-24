package com.apex.agent.di

import com.apex.agent.core.engine.ExecutionMemoryObserver
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 将 [ExecutionMemoryObserver] 接口绑定到记忆库适配实现 [MemoryObserverAdapter]。
 *
 * 必须单独放在 abstract @Module 中：Hilt 不允许同一个 @Module 同时包含
 * abstract 绑定方法（@Binds）与非静态提供方法（@Provides，需位于 object）。
 *
 * 拆库后适配器直通 apex-kit-memory 的 MemKit 门面（Job 会话关联 /
 * appPackage 单源贯穿），引擎侧 [ExecutionMemoryObserver] 契约零改动。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CsMemObserverModule {

    @Binds
    @Singleton
    abstract fun bindExecutionMemoryObserver(
        impl: MemoryObserverAdapter
    ): ExecutionMemoryObserver
}
