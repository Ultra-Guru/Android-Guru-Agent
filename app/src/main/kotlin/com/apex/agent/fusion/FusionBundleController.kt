package com.apex.agent.fusion

import android.content.Context
import android.content.SharedPreferences
import com.apex.agent.core.engine.AgentEngine
import com.apex.agent.core.engine.ApexAgentEngine
import com.apex.agent.core.tools.fusion.FusionActivation
import com.apex.agent.core.tools.fusion.FusionBundleActivator
import com.apex.agent.core.tools.fusion.FusionBundleDigest
import com.apex.agent.core.tools.fusion.FusionBundleManifest
import com.apex.agent.core.tools.fusion.FusionBundleRegistry
import com.apex.agent.core.tools.fusion.FusionMcpConnector
import com.apex.agent.core.tools.fusion.FusionPromptInjector
import com.apex.agent.core.tools.fusion.FusionSkillInstaller
import com.apex.agent.core.tools.mcp.McpServerConfig
import com.apex.agent.core.tools.mcp.McpTransport
import com.apex.agent.core.tools.skill.SkillRegistry
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * # FusionBundle 控制器 —— 聊天输入框入口的后端
 *
 * UI（输入栏 FusionBundleButton + PickerSheet）与 agent 工具族
 * （FusionList / FusionActivate）共用的单一控制面：
 * - [bundles]：已装包清单（registry + changes 流自动刷新）；
 * - [activeBundle]：当前激活的包（null = 无）；
 * - [activate] / [deactivate]：编排三端口（技能装 SkillRegistry、MCP 连
 *   McpManager、人设注入引擎 additionalSystemContext —— 全部既有管道）。
 *
 * 「聚而不同」在此落地：控制器不持有任何平行注册表 —— 它只做调度与
 * 状态广播，实体全部活在既有体系里。
 */
@Singleton
class FusionBundleController @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: Context,
    private val registry: FusionBundleRegistry,
    private val skillRegistry: SkillRegistry,
    private val mcpManager: com.apex.agent.core.tools.mcp.McpManager,
    // 注入接口（AgentModule 只暴露 AgentEngine）；具体类型经 as? 安全下行 ——
    // 运行时唯一实现即聊天屏引擎实例，转型失败时人设注入进 skipped（聚合容错）
    private val agentEngine: AgentEngine
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences("fusion_bundle", Context.MODE_PRIVATE)

    private val _bundles = MutableStateFlow<List<FusionBundleDigest>>(emptyList())
    val bundles: StateFlow<List<FusionBundleDigest>> = _bundles.asStateFlow()

    private val _activeBundle = MutableStateFlow<FusionBundleManifest?>(null)
    val activeBundle: StateFlow<FusionBundleManifest?> = _activeBundle.asStateFlow()

    val activator: FusionBundleActivator = FusionBundleActivator(
        registry = registry,
        skillInstaller = SkillInstallerPort(skillRegistry),
        mcpConnector = McpConnectorPort(mcpManager),
        promptInjector = EnginePromptPort(agentEngine)
    )

    /** 注册表只读暴露（fusion_* 工具族与 UI 共用同一数据源）。 */
    val registryForTools: FusionBundleRegistry get() = registry

    init {
        refreshBundles()
        // registry 变化（安装/卸载/内置升级）→ 清单自动刷新
        scope.launch {
            registry.changes.collect { refreshBundles() }
        }
        // 跨进程存活：上次激活的包后台重激活（幂等 —— 技能已装、服务器已连
        // 则只重注人设；失败静默，用户点按钮可重试）
        val lastActive = prefs.getString(KEY_ACTIVE_BUNDLE, null)
        if (lastActive != null) {
            scope.launch {
                activator.activate(lastActive)
                    .onSuccess { _activeBundle.value = registry.get(lastActive) }
                    .onFailure {
                        AppLogger.instance.warn(
                            LogCategory.SYSTEM, TAG,
                            "重启后重激活 FusionBundle '$lastActive' 失败: ${it.message}"
                        )
                    }
            }
        }
    }

    private fun refreshBundles() {
        _bundles.value = registry.digests()
    }

    /** 激活（UI 按钮 / FusionActivate 工具共用）。 */
    suspend fun activate(bundleId: String): Result<FusionActivation> {
        val r = activator.activate(bundleId)
        r.onSuccess {
            _activeBundle.value = registry.get(bundleId)
            prefs.edit().putString(KEY_ACTIVE_BUNDLE, bundleId).apply()
            refreshBundles()
        }
        return r
    }

    /** 去激活。 */
    fun deactivate(): Result<Unit> {
        val r = activator.deactivate()
        prefs.edit().remove(KEY_ACTIVE_BUNDLE).apply()
        _activeBundle.value = null
        return r
    }

    companion object {
        private const val TAG = "FusionBundle"
        private const val KEY_ACTIVE_BUNDLE = "active_bundle_id"
    }
}

// ── 三端口的 app 层适配（core 接口 → 真实子系统）────────────────────────

/** 技能安装端口 → SkillRegistry.install / get。 */
private class SkillInstallerPort(
    private val skills: SkillRegistry
) : FusionSkillInstaller {
    override fun isInstalled(skillId: String): Boolean = skills.get(skillId) != null

    override suspend fun ensureInstalled(manifestJson: String): Result<Unit> =
        skills.install(manifestJson).map { Unit }
}

/** MCP 连接端口 → McpManager（BUILTIN 幂等预置 / 其他形态 addServer + connect）。 */
private class McpConnectorPort(
    private val manager: com.apex.agent.core.tools.mcp.McpManager
) : FusionMcpConnector {
    override suspend fun ensureConnected(config: McpServerConfig): Result<Unit> {
        if (config.transport == McpTransport.BUILTIN) {
            // 幂等预置（用户禁用不劫持）→ 连接
            manager.ensureBuiltinServer(config).onFailure { return Result.failure(it) }
            return manager.connect(config.name).map { Unit }
        }
        // STDIO/HTTP：写入配置（同 = 更新）→ 连接
        manager.addServer(config).onFailure { return Result.failure(it) }
        return manager.connect(config.name).map { Unit }
    }
}

/** 引擎人设注入端口 → ApexAgentEngine.patchConfig 读改写（additionalSystemContext）。 */
private class EnginePromptPort(
    private val engine: AgentEngine
) : FusionPromptInjector {
    /** 注入前的原始值（去激活恢复用）。 */
    private var original: String? = null

    override fun apply(segment: String) {
        val apex = engine as? ApexAgentEngine
            ?: return // 非 Apex 实现时人设注入静默降级（聚合容错面）
        if (original == null) {
            original = apex.currentConfig().additionalSystemContext
        }
        val base = original.orEmpty()
        val merged = if (base.isBlank()) segment else "$base\n\n$segment"
        apex.patchConfig { it.copy(additionalSystemContext = merged) }
    }

    override fun restore() {
        val apex = engine as? ApexAgentEngine ?: return
        val base = original
        original = null
        apex.patchConfig { it.copy(additionalSystemContext = base.orEmpty()) }
    }
}
