package com.apex.agent.di

import android.content.Context
import com.apex.agent.knowledge.KnowledgeBaseService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * #197+ · 知识库体系 DI —— KnowledgeBaseService 接线（v1.8.0）。
 *
 * 服务只拿两个纯函数（列资产 / 读资产）；本模块用 Context.assets
 * 实现，把 IO 边界钉在 DI 层 —— 服务本体保持纯 JVM 可测。
 *
 * 资产根：assets/knowledge/（每套知识库一个 .md，kb 头声明
 * id / role 绑定 / title / version）。加载为懒扫描 + 坏文件跳过
 * （见 [KnowledgeBaseService] 注释）。
 */
@Module
@InstallIn(SingletonComponent::class)
object KnowledgeModule {

    @Provides
    @Singleton
    fun provideKnowledgeBaseService(
        @ApplicationContext context: Context
    ): KnowledgeBaseService = KnowledgeBaseService(
        listAssetFiles = {
            runCatching {
                context.assets.list(KB_DIR)?.toList().orEmpty()
            }.getOrElse { emptyList() }
        },
        readAsset = { name ->
            runCatching {
                context.assets.open("$KB_DIR/$name").use { stream ->
                    stream.readBytes().toString(Charsets.UTF_8)
                }
            }.getOrNull()
        }
    )

    private const val KB_DIR = "knowledge"
}
