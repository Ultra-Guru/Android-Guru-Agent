package com.apex.agent.di

import android.content.Context
import com.apex.agent.core.code.experience.RoleExperienceStore
import com.apex.agent.core.code.experience.RoleExperienceTracker
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * # 角色经验卡 DI（v1.9 跨会话经验积累）
 *
 * 两件单例（CodeLongTaskModule 同款装配纪律）：
 *
 * - [RoleExperienceStore]：`filesDir/role_experience/` 的每角色一卷
 *   JSON 存储（原子写 + 内存缓存 + 有界裁剪）；
 * - [RoleExperienceTracker]：AgentEvent 流聚合器，**注入独立的
 *   SupervisorJob + IO scope** —— fire-and-forget 落卷绝不阻塞 VM
 *   主线程，单卷失败不传染兄弟落库；
 *
 * 召回（RoleExperienceRecall）是纯函数对象，无状态不需要 DI；
 * Git 角色技能引擎（GitRoleSkillEngine）是静态目录对象，同样免 DI。
 *
 * 与 CodeLongTaskModule 分开成模块：经验卡是独立演进域（存储/打分/
 * 注入策略都可能单独迭代），DI 边界跟着域边界走。
 */
@Module
@InstallIn(SingletonComponent::class)
object RoleExperienceModule {

    /** 经验卡落卷专用 scope（Supervisor：单卷失败不取消兄弟落卷）。 */
    @Qualifier
    @Retention(AnnotationRetention.BINARY)
    annotation class ExperiencePersistScope

    @Provides
    @Singleton
    fun provideRoleExperienceStore(@ApplicationContext context: Context): RoleExperienceStore =
        RoleExperienceStore(File(context.filesDir, "role_experience"))

    @Provides
    @Singleton
    @ExperiencePersistScope
    fun provideExperiencePersistScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Provides
    @Singleton
    fun provideRoleExperienceTracker(
        store: RoleExperienceStore,
        @ExperiencePersistScope persistScope: CoroutineScope
    ): RoleExperienceTracker = RoleExperienceTracker(store, persistScope)
}
