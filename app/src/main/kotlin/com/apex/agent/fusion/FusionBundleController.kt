package com.apex.agent.fusion

import android.content.Context
import android.content.SharedPreferences
import com.apex.agent.core.tools.fusion.FusionActivation
import com.apex.agent.core.tools.fusion.FusionBundleActivator
import com.apex.agent.core.tools.fusion.FusionInstallReport
import com.apex.agent.core.tools.fusion.FusionBundleDigest
import com.apex.agent.core.tools.fusion.FusionBundleManifest
import com.apex.agent.core.tools.fusion.FusionBundleRegistry
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * # FusionBundle 控制器 —— 聊天输入框入口的后端
 *
 * UI（输入栏 FusionBundleButton + PickerSheet）与 agent 工具族
 * （FusionList / FusionActivate）共用的单一控制面：
 * - [bundles]：已装包清单（registry + changes 流自动刷新）；
 * - [activeBundle]：当前激活的包（null = 无）；
 * - [activate] / [deactivate]：经 [activator] 编排三端口（技能装
 *   SkillRegistry、MCP 连 McpManager、人设注入引擎 additionalSystemContext
 *   —— 全部既有管道）。
 *
 * DI 破环注记：控制器**不**直接依赖 AgentEngine —— 否则
 * ToolRegistry → 本控制器 → AgentEngine → ToolRegistry 成 Dagger 环。
 * 激活器由 FusionModule 以 `Lazy<AgentEngine>` 端口延迟装配（见
 * [com.apex.agent.di.FusionModule]）。
 *
 * 「聚而不同」在此落地：控制器不持有任何平行注册表 —— 它只做调度与
 * 状态广播，实体全部活在既有体系里。
 */
@Singleton
class FusionBundleController @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: Context,
    private val registry: FusionBundleRegistry,
    /** 三端口编排中枢（FusionActivate 工具与 UI 共用；由 FusionModule 装配）。 */
    val activator: FusionBundleActivator,
    /** 引擎懒引用（dagger.Lazy 断环：ToolRegistry→Controller→引擎→ToolRegistry）——
     *  Fusion Catalog 目录段写入 additionalSystemContext 用。qualifier 对齐
     *  FusionModule 的 @Named("code")（Agent 模式移除后唯一引擎绑定）。 */
    @javax.inject.Named("code")
    private val engineLazy: dagger.Lazy<com.apex.agent.core.engine.AgentEngine>
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences("fusion_bundle", Context.MODE_PRIVATE)

    private val _bundles = MutableStateFlow<List<FusionBundleDigest>>(emptyList())
    val bundles: StateFlow<List<FusionBundleDigest>> = _bundles.asStateFlow()

    private val _activeBundle = MutableStateFlow<FusionBundleManifest?>(null)
    val activeBundle: StateFlow<FusionBundleManifest?> = _activeBundle.asStateFlow()

    /** 注册表只读暴露（FusionList/FusionInfo 工具族与 UI 共用同一数据源）。 */
    val registryForTools: FusionBundleRegistry get() = registry

    /** 暖启幂等闸（[ensureWarmedUp] 只执行一次后台重激活）。 */
    private val warmedUp = AtomicBoolean(false)

    init {
        refreshBundles()
        // registry 变化（安装/卸载/内置升级）→ 清单自动刷新 + 目录段同步
        scope.launch {
            registry.changes.collect {
                refreshBundles()
                refreshFusionCatalog()
            }
        }
    }

    /**
     * 暖启：恢复上次激活的包（幂等 —— 技能已装、服务器已连则只重注人设；
     * 失败静默，用户点按钮可重试）。
     *
     * 刻意不在构造期执行：构造发生在 ToolRegistry 装配中段，此刻解析
     * `Lazy<AgentEngine>` 会重入未完成的注册表提供方；UI/模型侧首次触达
     * （组件装配完成后）才是安全时机。
     */
    fun ensureWarmedUp() {
        if (!warmedUp.compareAndSet(false, true)) return
        refreshFusionCatalog()
        val lastActive = prefs.getString(KEY_ACTIVE_BUNDLE, null) ?: return
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

    private fun refreshBundles() {
        _bundles.value = registry.digests()
    }

    /** 激活（UI 按钮 / FusionActivate 工具共用；scope 透传工位 —— all 包
     *  双工位可激活，域包错位在 activator 层拒绝）。 */
    suspend fun activate(
        bundleId: String,
        scope: String = "agent"
    ): Result<FusionActivation> {
        val r = activator.activate(bundleId, scope)
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

    /**
     * 安装成员（只装不激活）：把包内技能 / MCP 配置 / 功能插件全部装到位
     * （仓库引用成员经 hub 按需下载）——「专门安装 skills / MCP / 插件的
     * 聚合」的用户入口。安装语义对齐激活报告词表，供 UI 反馈。
     */
    suspend fun installMembers(bundleId: String): Result<FusionInstallReport> =
        activator.installMembers(bundleId)

    /**
     * 模型侧（FusionActivate 工具）激活回灌：与按钮路径同一激活面 ——
     * UI 横幅随模型侧激活点亮、重启后重激活持久化同步。
     */
    fun onModelActivation(activation: FusionActivation) {
        _activeBundle.value = registry.get(activation.bundleId)
        prefs.edit().putString(KEY_ACTIVE_BUNDLE, activation.bundleId).apply()
    }

    /** 模型侧去激活回灌（[onModelActivation] 的对偶）。 */
    fun onModelDeactivation() {
        prefs.edit().remove(KEY_ACTIVE_BUNDLE).apply()
        _activeBundle.value = null
    }

    /**
     * Fusion Catalog 目录段（**常驻提示词**——与 Skill Catalog 同级别的
     * 一等公民待遇）：已装聚合的一行式清单写入引擎 additionalSystemContext，
     * 模型「天生知道」有哪些聚合可用（激活用 FusionActivate / 装成员用
     * FusionInstallMembers），不再需要一次 FusionList 探测调用。
     *
     * 标记包围（<!-- FUSION_CATALOG -->...）幂等刷新：变更时移除旧段重插，
     * 与聚合激活的人设注入（EnginePromptPort 读改写）互不踩踏。
     */
    private fun refreshFusionCatalog() {
        val apex = engineLazy.get() as? com.apex.agent.core.engine.ApexAgentEngine ?: return
        val digests = registry.digests()
        val section = if (digests.isEmpty()) "" else buildString {
            appendLine("<!-- FUSION_CATALOG -->")
            appendLine("## Fusion Catalog (${digests.size} packs installed)")
            appendLine("Compound packs (skills + MCP + plugins + workflow) are installed but NOT active by default.")
            appendLine("When the user's task matches a pack below, call FusionActivate(id) to turn the whole system on;")
            appendLine("FusionInstallMembers(id) installs members without activating.")
            digests.forEach { d ->
                appendLine("- ${d.id}「${d.name}」: ${d.description.take(80)}（技能×${d.skillCount} MCP×${d.mcpServerCount}）")
            }
            append("<!-- /FUSION_CATALOG -->")
        }
        runCatching {
            apex.patchConfig { cfg ->
                val cleaned = cfg.additionalSystemContext
                    .replace(Regex("<!-- FUSION_CATALOG -->[\\s\\S]*?<!-- /FUSION_CATALOG -->\\n?"), "")
                    .trim()
                cfg.copy(
                    additionalSystemContext =
                    if (section.isBlank()) cleaned else listOf(section, cleaned).filter { it.isNotBlank() }.joinToString("\n\n")
                )
            }
        }
    }

    companion object {
        private const val TAG = "FusionBundle"
        private const val KEY_ACTIVE_BUNDLE = "active_bundle_id"
    }
}
