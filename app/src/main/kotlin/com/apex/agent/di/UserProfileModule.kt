package com.apex.agent.di

import android.content.Context
import com.apex.agent.platform.memory.ports.ModelRuntimeChatModel
import com.apex.agent.ui.screen.code.session.CodeSessionStore
import com.apex.agent.ui.screen.memory.profile.ProfileDistiller
import com.apex.agent.ui.screen.memory.profile.UserProfileStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import com.apex.memkit.core.ChatModel
import java.io.File
import javax.inject.Singleton

/**
 * # 用户画像 DI（五维长期特征 —— 记忆页画像分区 + 编码会话注入）
 *
 * 两件单例：
 *
 * - [UserProfileStore]：`filesDir/user_profile/profile.json` 单卷存储
 *   （RoleExperienceStore 同款：原子写 + 内存缓存 + 有界裁剪 +
 *   snapshotBlocking 主线程安全读）；
 * - [ProfileDistiller]：「从编码对话提炼」管线 —— 跨工作区扫描
 *   CodeSessionStore 快照 → ChatModel 端口（memkit ChatModel，DI 图中
 *   由 ModelRuntimeChatModel 绑定实现：SUMMARY 角色 + 低温）→ 写入
 *   门禁落账。
 *
 * 与 RoleExperienceModule 分开成模块：画像是独立演进域（维度 / 蒸馏
 * 协议 / 注入策略都可能单独迭代），DI 边界跟着域边界走。
 */
@Module
@InstallIn(SingletonComponent::class)
object UserProfileModule {

    @Provides
    @Singleton
    fun provideUserProfileStore(@ApplicationContext context: Context): UserProfileStore =
        UserProfileStore(File(context.filesDir, "user_profile"))

    /** ChatModel 端口适配（蒸馏只依赖接口 —— 测试注入 fake，生产接 SUMMARY 便宜线）。 */
    @Provides
    @Singleton
    fun provideChatModelPort(impl: ModelRuntimeChatModel): ChatModel = impl

    @Provides
    @Singleton
    fun provideProfileDistiller(
        chatModel: ChatModel,
        store: UserProfileStore,
        sessionStore: CodeSessionStore,
    ): ProfileDistiller = ProfileDistiller(chatModel, store, sessionStore)
}
