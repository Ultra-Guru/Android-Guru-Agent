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
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
 * #822 主线程解耦：@Providers 不再内联旧存储全量迁移（Room 库逐行读、
 * JSON 全量解析曾把 Application.onCreate 钉死在主线程）—— 创建仅含
 * 几 KB 级 JSON 读盘（库构造契约，几毫秒级），**迁移移交
 * [MemoryKitInitializer.initializeAsync]** 在 IO 协程执行；宿主 App
 * 入口只调 initializeAsync，不再 @Inject 直接触发单例解析。
 */
@Module
@InstallIn(SingletonComponent::class)
object MemoryKitModule {

    /** 宿主应用级作用域（写入管道 / 后台蒸馏 / 迁移的宿主生命周期）。 */
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
            kit
        }
    }
}

/**
 * 记忆库后台初始化器（#822）—— 冷启动主线程解耦的宿主入口。
 *
 * `initializeAsync()` 在 IO 协程解析 [MemKit] 单例（Lazy 注入：单例
 * 首次解析被推迟到本协程，主线程 @Inject 不再触发）并顺带执行旧存储
 * 一次性迁移（幂等 + meta 标志守门）。ApexApp.onCreate 调用本方法后
 * 即返回；任何消费方（工具 / 记忆页 / MCP transport / Worker）在此
 * 之后再解析单例时，创建已在后台完成或由其自身线程承担几 KB 级读盘。
 */
@Singleton
class MemoryKitInitializer @Inject constructor(
    private val memKitLazy: Lazy<MemKit>,
    private val scope: CoroutineScope,
    @ApplicationContext private val context: Context,
) {
    /** 后台初始化（幂等；重复调用由 Hilt 单例与迁移 meta 标志兜底）。 */
    fun initializeAsync() {
        scope.launch {
            runCatching {
                val kit = memKitLazy.get()
                LegacyCsMemMigrator(kit, context).migrateIfNeeded()
            }.onFailure {
                AppLogger.instance.warn(
                    LogCategory.CS_MEM, TAG,
                    "记忆库后台初始化/迁移失败（忽略，下个启动重试）：${it.message}",
                )
            }
        }
    }

    private companion object {
        const val TAG = "MemoryKitInit"
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
