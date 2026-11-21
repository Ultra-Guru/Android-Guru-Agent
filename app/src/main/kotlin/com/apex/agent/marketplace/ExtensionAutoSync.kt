package com.apex.agent.marketplace

import android.content.Context
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import com.apex.agent.core.tools.extension.ExtensionAutoSyncRunner
import com.apex.agent.core.tools.extension.ExtensionInstaller
import com.apex.agent.core.tools.extension.ExtensionRegistry
import com.apex.agent.notify.ApexNotifications
import com.apex.agent.ui.screen.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.cancel

/**
 * # ExtensionAutoSync —— 启动期声明式清单自动同步（市场完全重构 PR-E）
 *
 * 方向④「扩展系统生产化加固」的 app 层包装：设置开关
 * （[SettingsRepository.AgentSettings.extensionAutoSync]）开启且声明式清单
 * （`<filesDir>/extensions/extensions.json`，ExtensionSync 同格式）存在时，
 * 每次进程启动跑一遍评估核（[ExtensionAutoSyncRunner]：resolve 全部 spec +
 * 锁漂移比对，**只评估不安装**）—— 漂移/失败经系统通知提醒（通知权限未授权
 * 时静默，lastSync 已落账本可经 ExtensionDoctor 查询）。
 *
 * 设置读取在协程内（DI 装配期 SettingsRepository 的 StateFlow 可能尚未就绪）。
 */
@Singleton
class ExtensionAutoSync @Inject constructor(
    @ApplicationContext private val context: Context,
    private val installer: ExtensionInstaller,
    private val registry: ExtensionRegistry,
    private val settingsRepository: SettingsRepository,
    private val notifications: ApexNotifications
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val runner by lazy { ExtensionAutoSyncRunner(installer, registry) }

    /** 启动钩子（ExtensionModule @Provides 副作用调用；幂等）。 */
    fun start() {
        scope.launch {
            runCatching { evaluate() }.onFailure {
                AppLogger.instance.warn(LogCategory.PLUGIN, "ExtensionAutoSync", "启动同步评估失败: ${it.message}")
            }
        }
    }

    /** 评估核（返回通知文案；null = 无事发生/开关关闭/清单缺失）。 */
    suspend fun evaluate(): String? {
        val settings = settingsRepository.agentSettings.value
        if (!settings.extensionAutoSync) return null
        val manifestFile = File(context.filesDir, "extensions/extensions.json")
        if (!manifestFile.isFile) return null
        val specs = runCatching {
            val obj = com.apex.agent.core.tools.extension.ExtensionJson.json
                .parseToJsonElement(manifestFile.readText()).jsonObjectSafe()
            (obj["extensions"] as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                ?: error("顶层缺少 extensions 数组")
        }.getOrElse {
            AppLogger.instance.warn(LogCategory.PLUGIN, "ExtensionAutoSync", "清单解析失败: ${it.message}")
            return null
        }
        if (specs.isEmpty()) return null

        val report = runner.run(specs)
        AppLogger.instance.info(LogCategory.PLUGIN, "ExtensionAutoSync", "启动同步评估: ${report.summary()}")
        if (!report.hasIssues) return null

        val text = "扩展自动同步发现问题（${report.summary()}）—— " +
            "漂移请到聚合中心重新安装或 ExtensionSync update=true 重钉；失败详见 ExtensionDoctor"
        notifyIfPossible(text)
        return text
    }

    /** 通知权限未授权时静默（lastSync 已落账本，Doctor 可查）。 */
    private fun notifyIfPossible(text: String) {
        if (!notifications.canPost(context)) {
            AppLogger.instance.info(LogCategory.PLUGIN, "ExtensionAutoSync", "通知权限未授权 —— 漂移/失败仅记录 lastSync")
            return
        }
        runCatching { notifications.notifyGeneral(context, "扩展自动同步", text) }
    }

    fun onCleared() = scope.cancel()

    private fun kotlinx.serialization.json.JsonElement.jsonObjectSafe(): kotlinx.serialization.json.JsonObject =
        this as? kotlinx.serialization.json.JsonObject
            ?: error("manifest 顶层必须是 JSON 对象")
}
