package com.apex.agent.di

import android.content.Context
import com.apex.agent.core.engine.DeviceGateEffect
import com.apex.agent.core.engine.DeviceGateMode
import com.apex.agent.core.engine.DeviceGateRule
import com.apex.agent.core.engine.DeviceIntentGate
import com.apex.agent.core.engine.UserQuestionGateway
import com.apex.agent.core.tools.builtin.DeviceAuditQueryTool
import com.apex.agent.core.tools.device.CapabilityProbe
import com.apex.agent.core.tools.device.DeviceOpsPort
import com.apex.agent.core.tools.device.IntentCatalog
import com.apex.agent.permission.PermissionEffect
import com.apex.agent.permission.PermissionMode
import com.apex.agent.permission.PermissionRule
import com.apex.agent.tools.DeviceAuditQueryHost
import com.apex.agent.tools.DeviceAuditSink
import com.apex.agent.tools.DeviceCapabilityProbe
import com.apex.agent.tools.DeviceOpsExecutor
import com.apex.agent.tools.DevicePathPolicy
import com.apex.agent.tools.DevicePreflight
import com.apex.agent.tools.DeviceTokenSigner
import com.apex.agent.tools.DeviceTrash
import com.apex.agent.tools.FileOpsRunner
import com.apex.agent.tools.ShizukuDeviceChannel
import com.apex.agent.tools.ToolAuditLogger
import com.apex.agent.ui.screen.settings.SettingsRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

/**
 * ═══ DCP 设备控制面 DI（S01–S08 装配）═══
 *
 * 独立于 [ToolModule]（主注册文件贴近 1200 行预算，SRP 拆分纪律同
 * [ToolModuleDeviceRegistration]）。全部实现零构造期快照：feature flag /
 * 权限模式 / 规则经 lambda 每次调用即时读取（对齐 PermissionMode /
 * CommandPolicyStore 的实时读取惯例）。
 */
@Module
@InstallIn(SingletonComponent::class)
object DeviceControlModule {

    /** 意图清单：app 层读 assets（device 目录下的 intents.json）→ core 解析。 */
    @Provides
    @Singleton
    fun provideIntentCatalog(@ApplicationContext context: Context): IntentCatalog {
        val json = runCatching {
            context.assets.open("device/intents.json").bufferedReader().use { it.readText() }
        }.getOrNull()
        return if (json != null) IntentCatalog.load(json) else IntentCatalog.empty()
    }

    /** Shizuku argv 直调通道（S08：不回退本地 shell 冒充）。 */
    @Provides
    @Singleton
    fun provideDeviceChannel(): ShizukuDeviceChannel = ShizukuDeviceChannel()

    /** 能力探针（S01：PrivilegeDetector + ShizukuCommandExecutor 的薄适配）。 */
    @Provides
    @Singleton
    fun provideCapabilityProbe(
        catalog: IntentCatalog,
        channel: ShizukuDeviceChannel,
        @ApplicationContext context: Context
    ): CapabilityProbe = DeviceCapabilityProbe(
        catalog = catalog,
        channel = channel,
        selfUid = { android.os.Process.myUid() },
        shizukuVersion = {
            // Shizuku.getVersion 是 API 10+ 的服务端版本；拿不到如实报 null。
            runCatching { rikka.shizuku.Shizuku.getVersion().toInt() }.getOrNull()
        }
    )

    /** 令牌签名器（S04：HMAC 密钥持久化在 app 私有目录）。 */
    @Provides
    @Singleton
    fun provideDeviceTokenSigner(@ApplicationContext context: Context): DeviceTokenSigner =
        DeviceTokenSigner(File(File(context.filesDir, "dcp/keys"), "token.key"))

    /** 统一风险门（S03：ask / rules / mode 全函数注入，实时读取）。 */
    @Provides
    @Singleton
    fun provideDeviceIntentGate(
        gateway: UserQuestionGateway,
        settingsRepository: SettingsRepository
    ): DeviceIntentGate = DeviceIntentGate(
        ask = { question -> gateway.ask(question) },
        rules = {
            settingsRepository.agentSettings.value.permissionRules
                .map { it.toGateRule() }
        },
        mode = {
            when (settingsRepository.agentSettings.value.permissionMode) {
                PermissionMode.BYPASS -> DeviceGateMode.BYPASS
                PermissionMode.DEFAULT -> DeviceGateMode.DEFAULT
                PermissionMode.ACCEPT_EDITS -> DeviceGateMode.ACCEPT_EDITS
                PermissionMode.PLAN -> DeviceGateMode.PLAN
            }
        }
    )

    private fun PermissionRule.toGateRule(): DeviceGateRule = DeviceGateRule(
        pattern = pattern,
        effect = when (effect) {
            PermissionEffect.ALLOW -> DeviceGateEffect.ALLOW
            PermissionEffect.ASK -> DeviceGateEffect.ASK
            PermissionEffect.DENY -> DeviceGateEffect.DENY
        }
    )

    /** 唯一写执行点（S05/S06/S07 编排：preflight → 守卫 → gate → token → 执行 → verify → 审计）。 */
    @Provides
    @Singleton
    fun provideDeviceOpsPort(
        @ApplicationContext context: Context,
        catalog: IntentCatalog,
        channel: ShizukuDeviceChannel,
        capability: CapabilityProbe,
        gate: DeviceIntentGate,
        signer: DeviceTokenSigner,
        auditLogger: ToolAuditLogger,
        settingsRepository: SettingsRepository
    ): DeviceOpsPort {
        val pathPolicy = DevicePathPolicy(
            extraAllowedRoots = listOf(
                context.filesDir.absolutePath,
                context.getExternalFilesDir(null)?.absolutePath ?: ""
            ).filter { it.isNotEmpty() }
        )
        val trash = DeviceTrash(channel)
        val preflight = DevicePreflight(
            channel = channel,
            catalog = catalog,
            clock = System::currentTimeMillis,
            sign = signer::sign,
            selfPackage = context.packageName
        )
        val fileOps = FileOpsRunner(channel, trash, pathPolicy, System::currentTimeMillis)
        return DeviceOpsExecutor(
            catalog = catalog,
            channel = channel,
            preflight = preflight,
            capability = capability,
            gate = gate,
            pathPolicy = pathPolicy,
            trash = trash,
            fileOps = fileOps,
            audit = DeviceAuditSink { event -> auditLogger.log(event) },
            selfPackage = context.packageName,
            enabled = { settingsRepository.agentSettings.value.dcpEnabled }
        )
    }

    /** 审计查询宿主（S10：只读过滤，不做权限门）。 */
    @Provides
    @Singleton
    fun provideDeviceAuditQueryHost(auditLogger: ToolAuditLogger): DeviceAuditQueryTool.Host =
        DeviceAuditQueryHost(auditLogger)
}
