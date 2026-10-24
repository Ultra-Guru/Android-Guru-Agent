package com.apex.agent.platform.memory.di

import android.content.Context
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import com.apex.agent.platform.memory.migrate.LegacyCsMemMigrator
import com.apex.agent.platform.memory.ports.ModelRuntimeChatModel
import com.apex.agent.platform.memory.ports.PrivilegeActionPerformer
import com.apex.agent.platform.memory.ports.PrivilegeSceneCapture
import com.apex.memkit.core.KitLogger
import com.apex.memkit.core.MemKit
import com.apex.memkit.core.MemKitConfig
import com.apex.memkit.core.store.FileStorageBackend
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * 记忆库（apex-kit-memory）Hilt 接线 —— 拆库后的宿主侧 DI 补位。
 *
 * 库本身零 DI 依赖（纯 Kotlin JVM）；本 Module 把库门面 [MemKit] 连同
 * 三个平台端口（[PrivilegeSceneCapture] / [PrivilegeActionPerformer] /
 * [ModelRuntimeChatModel]）注册进宿主 Hilt 图。存储目录
 * `<filesDir>/memory_kit/`，经 [FileStorageBackend] 原子文档持久化。
 *
 * 生命周期说明：@Provides 副作用模式（同 McpModule.provideMcpManager 的
 * ensureAndConnect 前例）—— 单例首次解析时顺带执行旧存储一次性迁移
 * （[LegacyCsMemMigrator.migrateIfNeeded]，幂等 + meta 标志守门）；库加载
 * 与迁移合计为几 KB 级 JSON 读盘（旧 KnowledgeGraphStore 构造器同款
 * 同步纪律），经 IO 调度器执行。
 */
@Module
@InstallIn(SingletonComponent::class)
object MemoryKitModule {

    /** 宿主应用级作用域（写入管道 / 后台蒸馏的宿主生命周期）。 */
    @Provides
    @Singleton
    fun provideMemoryScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Provides
    @Singleton
    fun provideMemKit(
        @ApplicationContext context: Context,
        sceneCapture: PrivilegeSceneCapture,
        actionPerformer: PrivilegeActionPerformer,
        chatModel: ModelRuntimeChatModel,
        scope: CoroutineScope,
    ): MemKit = runBlocking {
        withContext(Dispatchers.IO) {
            val kit = MemKit.create(
                config = MemKitConfig(),
                storage = FileStorageBackend(File(context.filesDir, "memory_kit")),
                capture = sceneCapture,
                performer = actionPerformer,
                chatModel = chatModel,
                logger = HostKitLogger,
                clock = { System.currentTimeMillis() },
                scope = scope,
            )
            kit.start()
            // 首次解析时顺带迁移旧存储（幂等；无旧数据时为一次 meta 写）
            runCatching { LegacyCsMemMigrator(kit, context).migrateIfNeeded() }
                .onFailure {
                    AppLogger.instance.warn(LogCategory.CS_MEM, TAG, "旧记忆迁移执行失败（忽略）：${it.message}")
                }
            kit
        }
    }
}

/** 库日志端口 → 宿主日志中枢的映射（warn 无 throwable 位，折叠进消息）。 */
private object HostKitLogger : KitLogger {
    override fun log(level: KitLogger.Level, tag: String, message: String, error: Throwable?) {
        when (level) {
            KitLogger.Level.DEBUG -> AppLogger.instance.debug(LogCategory.CS_MEM, tag, message)
            KitLogger.Level.INFO -> AppLogger.instance.info(LogCategory.CS_MEM, tag, message)
            KitLogger.Level.WARN -> AppLogger.instance.warn(
                LogCategory.CS_MEM, tag,
                if (error != null) "$message（${error.message}）" else message,
            )

            KitLogger.Level.ERROR -> AppLogger.instance.error(LogCategory.CS_MEM, tag, message, error)
        }
    }
}

private const val TAG = "MemoryKitModule"
