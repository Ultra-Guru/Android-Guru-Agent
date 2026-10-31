package com.apex.agent.ui.screen.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.apex.agent.core.code.subagent.SubAgentSettings
import com.apex.agent.core.llm.*
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import com.apex.agent.permission.PermissionMode
import com.apex.agent.permission.PermissionRule
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 设置中心持久化层（单一可信源）。
 *
 * 取代原先散落在 SettingsViewModel / LlmModule 中的零散 SharedPreferences 读写，
 * 统一以 JSON 序列化保存：
 *  - 模型 Profile 列表（[ModelProfile]）
 *  - Provider 列表（[ProviderConfig]）
 *  - 多模型角色映射（[ModelRoleConfig]）
 *  - Agent 运行参数（[AgentSettings]）
 *
 * 首次启动注入内置 Provider + 默认 Profile，并把旧版散装 Key（llm_base_url 等）
 * 迁移为一个 Custom Profile，保证老用户配置不丢。
 */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("apex_settings", Context.MODE_PRIVATE)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    /**
     * API Key 的加密存储（AES-256-GCM + AES-256-SIV，由 Android Keystore 主密钥保护）。
     *
     * 背景：旧实现把 Provider 的 `apiKeys` 连同其它字段一起明文 JSON 落盘在
     * `apex_settings` 里 —— 只要设备被 root 或做一次 adb backup，Key 就裸奔。
     * 现在 Key **只**存在于此处，内存中保留一份副本供 [LlmConfig] 构建使用。
     */
    private val securePrefs: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context, PREF_SECURE, masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // 极少数设备 Keystore 初始化失败：退化为普通 SP，宁可不加密也绝不让配置丢失。
            // #257：降级不再静默 —— 留一条 WARN（诊断与安全审计可见），
            // 与 GithubTokenManager / EncryptedPrefsVaultStore 的告警口径对齐。
            runCatching {
                AppLogger.instance.warn(
                    LogCategory.SYSTEM, "SettingsRepository",
                    "EncryptedSharedPreferences 初始化失败，LLM API Key 将以明文存储" +
                        "（${e.javaClass.simpleName}: ${e.message}）—— 建议在系统设置中" +
                        "清除应用数据后重新配置"
                )
            }
            context.getSharedPreferences(PREF_SECURE_FALLBACK, Context.MODE_PRIVATE)
        }
    }

    private val _profiles = MutableStateFlow(emptyList<ModelProfile>())
    val profiles: StateFlow<List<ModelProfile>> = _profiles.asStateFlow()

    private val _providers = MutableStateFlow(emptyList<ProviderConfig>())
    val providers: StateFlow<List<ProviderConfig>> = _providers.asStateFlow()

    private val _roles = MutableStateFlow(ModelRoleConfig())
    val roles: StateFlow<ModelRoleConfig> = _roles.asStateFlow()

    private val _agentSettings = MutableStateFlow(AgentSettings())
    val agentSettings: StateFlow<AgentSettings> = _agentSettings.asStateFlow()

    init {
        // 所有状态流声明完成后，统一执行加载 / 种子 / 迁移，并立即持久化，
        // 避免字段初始化顺序导致迁移结果被覆盖。
        var provs = readProviders() ?: ModelProfileDefaults.builtInProviders
        var profs = (readProfiles(provs) ?: ModelProfileDefaults.defaultProfiles(provs)).toMutableList()
        provs = migrateAndHydrateApiKeys(provs)
        migrateLegacyConfig(profs, provs) { newProvs -> provs = newProvs }
        provs = migrateAndHydrateApiKeys(provs)
        _providers.value = provs
        _profiles.value = profs
        _roles.value = readRoles(profs) ?: ModelProfileDefaults.defaultRoles(profs)
        _agentSettings.value = readAgentSettings()
        persistProviders()
        persistProfiles()
        persistRoles()
        persistAgentSettings()
    }

    // ── 查询辅助 ───────────────────────────────────────────────
    fun getProfile(id: String): ModelProfile? = _profiles.value.firstOrNull { it.id == id }
    fun getProvider(id: String): ProviderConfig? = _providers.value.firstOrNull { it.id == id }

    fun defaultProfile(): ModelProfile {
        val list = _profiles.value
        return list.firstOrNull { it.isDefault }
            ?: list.firstOrNull()
            ?: ModelProfile(id = "fallback", name = "未配置", providerId = "", modelId = "")
    }

    fun defaultProvider(): ProviderConfig? = getProvider(defaultProfile().providerId)

    // ── API Key：加密存储 ───────────────────────────────────────
    /** 读取某个 Provider 的 API Key（密文；无则返回空串而非 null，便于直接判空）。 */
    fun getProviderApiKey(providerId: String): String {
        if (providerId.isBlank()) return ""
        return securePrefs.getString(KEY_PROVIDER_KEY + providerId, null)
            ?: prefs.getString(KEY_PROVIDER_KEY + providerId, null).orEmpty()
    }

    /**
     * 写入某个 Provider 的 API Key —— 用户手输的唯一入口。
     *
     * 同时把内存副本的 `apiKeys` 同步更新，保证 [defaultLlmConfig] /
     * ModelRuntime 在**不重启 App**的前提下立刻用上新 Key。
     */
    fun setProviderApiKey(providerId: String, apiKey: String) {
        if (providerId.isBlank()) return
        val trimmed = apiKey.trim()
        securePrefs.edit().putString(KEY_PROVIDER_KEY + providerId, trimmed).apply()
        // 清理历史遗留的明文副本
        if (prefs.contains(KEY_PROVIDER_KEY + providerId)) {
            prefs.edit().remove(KEY_PROVIDER_KEY + providerId).apply()
        }
        val idx = _providers.value.indexOfFirst { it.id == providerId }
        if (idx >= 0) {
            val list = _providers.value.toMutableList()
            list[idx] = list[idx].copy(
                apiKeys = if (trimmed.isBlank()) emptyList() else listOf(trimmed)
            )
            _providers.value = list
        }
    }

    /**
     * 明文 Key → 加密存储迁移 + 启动时回填。
     *
     * - Provider 列表里若还带着明文 `apiKeys`（旧版本落盘），写入加密区；
     * - 随后无论 Key 来自加密区还是已迁移，都回填到内存副本（供请求使用）；
     * - 返回的列表不含明文 Key —— 真正落盘时还会由 [providersForPersist] 二次剥离。
     */
    private fun migrateAndHydrateApiKeys(providers: List<ProviderConfig>): List<ProviderConfig> =
        providers.map { prov ->
            prov.apiKeys.firstOrNull { it.isNotBlank() }?.let { plain ->
                securePrefs.edit().putString(KEY_PROVIDER_KEY + prov.id, plain).apply()
                if (prefs.contains(KEY_PROVIDER_KEY + prov.id)) {
                    prefs.edit().remove(KEY_PROVIDER_KEY + prov.id).apply()
                }
            }
            val stored = getProviderApiKey(prov.id).trim()
            prov.copy(apiKeys = if (stored.isBlank()) emptyList() else listOf(stored))
        }

    /** 由「默认模型 Profile + 其 Provider」派生运行时 [LlmConfig]，供引擎 / 测试连接使用。 */
    fun defaultLlmConfig(): LlmConfig {
        val profile = defaultProfile()
        val provider = getProvider(profile.providerId)
        return LlmConfig.fromProfile(profile, provider)
    }

    // ── Profile 增改 ───────────────────────────────────────────
    fun upsertProfile(profile: ModelProfile) {
        val list = _profiles.value.toMutableList()
        val idx = list.indexOfFirst { it.id == profile.id }
        if (idx >= 0) list[idx] = profile else list.add(profile)
        _profiles.value = list
        persistProfiles()
    }

    fun deleteProfile(id: String) {
        // 不允许删除最后一个
        if (_profiles.value.size <= 1) return
        val wasDefault = _profiles.value.firstOrNull { it.id == id }?.isDefault == true
        val list = _profiles.value.filter { it.id != id }.toMutableList()
        if (wasDefault) list[0] = list[0].copy(isDefault = true)
        _profiles.value = list
        // 清理角色映射中对该 Profile 的引用
        var r = _roles.value
        fun String.orPrimary() = if (this == id) (list.firstOrNull()?.id ?: "") else this
        r = r.copy(
            primaryProfileId = r.primaryProfileId.orPrimary(),
            visionProfileId = r.visionProfileId.orPrimary(),
            reasoningProfileId = r.reasoningProfileId.orPrimary(),
            fastProfileId = r.fastProfileId.orPrimary(),
            summaryProfileId = r.summaryProfileId.orPrimary(),
        )
        _roles.value = r
        persistProfiles()
        persistRoles()
    }

    fun duplicateProfile(id: String) {
        val src = getProfile(id) ?: return
        val copy = src.copy(
            id = "profile_${System.currentTimeMillis()}",
            name = "${src.name} (副本)",
            isDefault = false
        )
        upsertProfile(copy)
    }

    fun setDefaultProfile(id: String) {
        _profiles.value = _profiles.value.map { it.copy(isDefault = it.id == id) }
        persistProfiles()
    }

    // ── Provider 增改 ──────────────────────────────────────────
    fun upsertProvider(provider: ProviderConfig) {
        // Key 一旦出现在 incoming 里就立即入加密区，绝不随 Provider 一起明文落盘
        provider.apiKeys.firstOrNull { it.isNotBlank() }?.let { setProviderApiKey(provider.id, it) }
        val key = getProviderApiKey(provider.id)
        val sanitized = provider.copy(
            apiKeys = if (key.isBlank()) emptyList() else listOf(key)
        )
        val list = _providers.value.toMutableList()
        val idx = list.indexOfFirst { it.id == sanitized.id }
        if (idx >= 0) list[idx] = sanitized else list.add(sanitized)
        _providers.value = list
        persistProviders()
    }

    fun deleteProvider(id: String) {
        val prov = getProvider(id) ?: return
        if (prov.isBuiltIn) return
        // 把挂载在该 Provider 下的 Profile 改为「无 Provider」（避免悬空引用）
        _profiles.value = _profiles.value.map {
            if (it.providerId == id) it.copy(providerId = "") else it
        }
        _providers.value = _providers.value.filter { it.id != id }
        // 连同存储的 Key 一起清掉（不含 Key 的残留密文就是密钥泄漏面）
        securePrefs.edit().remove(KEY_PROVIDER_KEY + id).apply()
        prefs.edit().remove(KEY_PROVIDER_KEY + id).apply()
        persistProviders()
        persistProfiles()
    }

    // ── 角色映射 ───────────────────────────────────────────────
    fun updateRoles(block: ModelRoleConfig.() -> ModelRoleConfig) {
        _roles.value = _roles.value.block()
        persistRoles()
    }

    // ── Agent 设置 ─────────────────────────────────────────────
    fun updateAgentSettings(block: AgentSettings.() -> AgentSettings) {
        _agentSettings.value = _agentSettings.value.block()
        persistAgentSettings()
    }

    // ── 持久化 ─────────────────────────────────────────────────
    private fun persistProfiles() =
        prefs.edit().putString(KEY_PROFILES, json.encodeToString(_profiles.value)).apply()

    /** Provider 的落盘副本：剥离全部 API Key（Key 只在加密区，见 [securePrefs]）。 */
    private fun providersForPersist(): List<ProviderConfig> =
        _providers.value.map { it.copy(apiKeys = emptyList()) }

    private fun persistProviders() =
        prefs.edit().putString(KEY_PROVIDERS, json.encodeToString(providersForPersist())).apply()

    private fun persistRoles() =
        prefs.edit().putString(KEY_ROLES, json.encodeToString(_roles.value)).apply()

    private fun persistAgentSettings() =
        prefs.edit().putString(KEY_AGENT, json.encodeToString(_agentSettings.value)).apply()

    // ── 加载 / 种子 / 迁移 ──────────────────────────────────────
    private fun readProviders(): List<ProviderConfig>? {
        val raw = prefs.getString(KEY_PROVIDERS, null) ?: return null
        return runCatching { json.decodeFromString<List<ProviderConfig>>(raw) }.getOrNull()
    }

    private fun readProfiles(providers: List<ProviderConfig>): List<ModelProfile>? {
        val raw = prefs.getString(KEY_PROFILES, null) ?: return null
        return runCatching { json.decodeFromString<List<ModelProfile>>(raw) }.getOrNull()
            ?: ModelProfileDefaults.defaultProfiles(providers)
    }

    private fun readRoles(profiles: List<ModelProfile>): ModelRoleConfig? {
        val raw = prefs.getString(KEY_ROLES, null) ?: return null
        return runCatching { json.decodeFromString<ModelRoleConfig>(raw) }.getOrNull()
            ?: ModelProfileDefaults.defaultRoles(profiles)
    }

    private fun readAgentSettings(): AgentSettings {
        val raw = prefs.getString(KEY_AGENT, null)
        if (raw != null) {
            runCatching { json.decodeFromString<AgentSettings>(raw) }.getOrNull()
                ?.let { return migratePermissionDefaultBypass(it) }
        }
        // 兼容旧版散装 Key（仅保留仍在消费的 keepAlive；Agent 模式散装键
        // 已随模式移除，ignoreUnknownKeys 吞掉存量 JSON 里的 Agent 字段）
        return migratePermissionDefaultBypass(
            AgentSettings(keepAlive = prefs.getBoolean("agent_keep_alive", true))
        )
    }

    /**
     * 迁移旧版散装配置（llm_base_url 等）为一个 Custom Profile + Provider，并设为默认。
     * 通过 [onProvidersChanged] 回调把新增的 Provider 回传给调用方（init 中赋值给可变变量），
     * 避免在字段初始化顺序未确定的情况下直接改写 [_providers]。
     */
    private fun migrateLegacyConfig(
        profiles: MutableList<ModelProfile>,
        providers: List<ProviderConfig>,
        onProvidersChanged: (List<ProviderConfig>) -> Unit
    ) {
        val legacyKey = prefs.getString("llm_api_key", null)
        if (legacyKey.isNullOrBlank()) return
        if (prefs.getBoolean(KEY_LEGACY_MIGRATED, false)) return

        val baseUrl = prefs.getString("llm_base_url", "") ?: ""
        val model = prefs.getString("llm_model", "") ?: ""
        val temperature = prefs.getFloat("llm_temperature", 0.7f)
        val effort = ReasoningEffort.fromName(prefs.getString("llm_reasoning_effort", null))

        val customProvider = ProviderConfig(
            id = "migrated",
            displayName = "Migrated (旧配置)",
            baseUrl = baseUrl,
            apiKeys = listOf(legacyKey),
            isBuiltIn = false,
        )
        val migratedProfile = ModelProfile(
            id = "profile_migrated",
            name = "Migrated Config",
            providerId = customProvider.id,
            modelId = model,
            temperature = temperature,
            reasoningEffort = effort,
            isDefault = true,
        )
        // 旧的默认 Profile 取消默认
        val idx = profiles.indexOfFirst { it.isDefault }
        if (idx >= 0) profiles[idx] = profiles[idx].copy(isDefault = false)
        profiles.add(migratedProfile)
        onProvidersChanged(providers + customProvider)
        prefs.edit().putBoolean(KEY_LEGACY_MIGRATED, true).apply()
    }

    companion object {
        private const val KEY_PROFILES = "model_profiles_v2"
        private const val KEY_PROVIDERS = "model_providers_v2"
        private const val KEY_ROLES = "model_roles_v2"
        private const val KEY_AGENT = "agent_settings_v2"
        private const val KEY_LEGACY_MIGRATED = "legacy_migrated_v2"

        /** 加密 Preference 文件名 */
        private const val PREF_SECURE = "apex_secure_settings"
        /** Keystore 不可用时的退化文件名 */
        private const val PREF_SECURE_FALLBACK = "apex_secure_settings_fallback"
        /** API Key 前缀键名（拼接 providerId） */
        private const val KEY_PROVIDER_KEY = "provider_api_key_"
    }
}


/**
 * 工具权限默认档迁移（2026-10 用户反馈「默认就是开启所有工具，agent
 * 可以直接使用」）：旧默认 DEFAULT（写类/删除类工具逐个弹「请求执行」
 * 确认）→ 新默认 BYPASS（全自动：工具/MCP/技能/shell 命令全放行，
 * 仅 DENY 规则仍生效）。
 *
 * - 仅翻转恰好停在旧默认 DEFAULT 的存量用户（持久化 JSON 全字段落盘，
 *   无法区分「显式选了 DEFAULT」与「从未动过」——统一翻一次，想收紧
 *   的用户可在 设置 → 工具权限 切回）；
 * - 显式选过 ACCEPT_EDITS / PLAN / BYPASS 的用户模式不动，仅落迁移标记；
 * - [AgentSettings.permissionBypassMigrated] 置 true 后幂等短路（init 的
 *   persistAgentSettings 立即回写，升级只发生一次）。
 *
 * 文件级 internal（非成员 private）：纯函数、零 Android 依赖，单测
 * （PermissionDefaultBypassMigrationTest）直接调用。
 */
internal fun migratePermissionDefaultBypass(settings: AgentSettings): AgentSettings {
    if (settings.permissionBypassMigrated) return settings
    return if (settings.permissionMode == PermissionMode.DEFAULT) {
        settings.copy(permissionMode = PermissionMode.BYPASS, permissionBypassMigrated = true)
    } else {
        settings.copy(permissionBypassMigrated = true)
    }
}

/**
 * 运行参数（Agent 模式移除后的收敛版：只保留 Coding 工位与通用层仍在
 * 消费的字段；Agent 聊天专属字段——执行模式/思考档位/预设/角色激活/
 * 重试与循环防护/反思规划——已随模式删除，存量 JSON 由
 * ignoreUnknownKeys 静默吞掉）。
 *
 * 字段中已真正打通到引擎配置（[com.apex.agent.core.engine.AgentConfig]，
 * DI 一次性快照，设置变更需重启应用生效）的有：
 * toolCallParallelism / maxContextTokens / compressionThreshold /
 * preserveRecentTurns / maxToolOutputLength / rateLimit* / subagent /
 * permissionMode / permissionRules / coding*（streaming / temperature
 * 则来自选中的模型 Profile）。
 */
@kotlinx.serialization.Serializable
data class AgentSettings(
    /**
     * v1.5 Coding 屏思考逻辑（双引擎路由）："" / "deep_dive" = 深潜
     * （自研七档思考，v1.2 既有行为）；"standard" = 标准（业界标准 Agent
     * 任务循环：画像/权限门/子代理/压缩）。Coding 屏右上角选择器写入，
     * CodeViewModel init 恢复（DualLogicCodeEngine.switchLogic 运行时路由）。
     */
    val codeThinkingLogic: String = "",
    /**
     * v1.2 Coding 模式思考档位（持久化）："" = 未设置（回退 STANDARD）；
     * "auto" = AUTO 自适应；其余 = ThinkingLevel 枚举名小写
     * （none/light/standard/deep/maximum/ultracode/apexcode）。
     * Coding 页选择器写入，CodeViewModel init 恢复（updateThinkingLevel
     * 双通道运行时生效，无需重启）。
     */
    val codeThinkingLevel: String = "",
    /**
     * #197 Coding 屏执行模式："" / "build" = BUILD（边想边做）；
     * "plan" = PLAN（先出完整计划，确认后执行）；
     * "goal" = GOAL（目标验收循环）。CodeViewModel init 恢复 + setMode
     * 双通道运行时生效。
     */
    val codeExecutionMode: String = "",
    /**
     * ═══ 工具循环 v2（引擎已真接线）═══
     *
     * 单轮并行执行上限：模型一次返回多个 tool_calls 时，同轮最多几个**同时
     * 执行**（1..8，1 = 顺序执行）。请求参数侧的 parallel_tool_calls 开关
     * 在 ModelProfile（两者正交：那个控制模型**能不能**一轮发多个调用，
     * 这个控制引擎**怎么执行**它们）。高并发下终端类工具输出会交错，
     * 追求确定性逐个执行请保持 1。
     */
    val toolCallParallelism: Int = 1,

    /** 后台服务保活（ApexCoreService = TerminalRuntime 生命周期宿主；#236）。 */
    val keepAlive: Boolean = true,

    // 视觉 / 截图（通用模型能力，视觉管线消费）
    val visionEnabled: Boolean = true,
    val screenshotQuality: String = "auto",   // auto | low | medium | high
    val maxScreenshots: Int = 3,

    /**
     * v1.4.4 #4：任务完成通知（✅已接线——引擎完成钩子）。App 在后台时
     * 任务/轮次跑完发系统通知（高优先级渠道，5 分钟自动消失，前台静音）；
     * 关闭则永不打扰。
     */
    val taskCompletionNotify: Boolean = true,

    /**
     * 自动更新总开关（用户反馈：不想自动更新时应用仍在后台检查/下载）。
     *
     * 关闭后：
     * - App 启动不再静默检查更新（ApexRoot 门控）→ 无新版本浮窗；
     * - 关于页进入也不再自动检查（UpdatePanel 门控；手动「检查更新」
     *   按钮仍可用 —— 显式操作不受限）；
     * - 所有下载本就只由用户按钮触发，检查关掉即后台零流量。
     * 默认开（保持既有行为）。
     */
    val autoUpdateCheck: Boolean = true,

    /**
     * #218：聊天记忆自动摄取总开关（默认开，保持既有行为；记忆功能主仓库
     * 保留，设置 → 工作台 → 记忆）。
     *
     * 关闭后：对话内容不再自动沉淀到画像/近况/里程碑（含 LLM 蒸馏与
     * 启发式捕获），召回注入同步静默 —— 已有记忆不受影响，可在记忆页
     * 查看/删除。cs-mem UI 轨迹记忆（无障碍）不受此开关管辖（那是
     * 运行时操作轨迹，另一条链路）。
     */
    val chatMemoryCapture: Boolean = true,

    // ── 上下文压缩（对应引擎配置，重启应用/新会话后生效）──
    // Issue #222：本字段不再是水位条/压缩门的直接分母 —— 生效值改为当前默认
    // 模型 Profile 的 contextWindow（ModelProfile.effectiveContextWindow，切换
    // 模型时热更新）；此处仅作 Profile 窗口字段缺失/非法（≤0）时的回退值
    // （防分母塌 0）。
    val maxContextTokens: Int = 128_000,
    val compressionThreshold: Float = 0.8f,   // 0.5..0.95
    val preserveRecentTurns: Int = 5,
    val maxToolOutputLength: Int = 2000,

    // ── 界面 ──
    val themeMode: String = "system",         // system | dark | light
    val dynamicColor: Boolean = false,
    // 预设主题配色（mint | amber | coral | violet | ocean | rose | forest |
    // cyan | sunset | gold | crimson | lavender | magenta | lime | sakura |
    // matcha | peach | plum）；Dynamic Color 开启时被壁纸取色覆盖。
    // 见 ui/theme/ThemePalettes.kt。
    val accentPalette: String = "mint",
    /**
     * 全局界面风格（minimal | liquid_glass）—— 与深浅模式正交的外观维度：
     *  - minimal（默认）：极简黑白 —— 纯灰阶配色 + 扁平表面（玻璃体系
     *    整体短路），Dynamic Color / accentPalette 不参与；
     *  - liquid_glass：液态玻璃 —— 完整玻璃材质管线 + 18 套预设强调色。
     * 见 ui/theme/UiStyle.kt；设置中心「外观」分区切换，立即生效。
     */
    val uiStyle: String = "minimal",
    /**
     * 自定义背景图（SAF 持久授权的 content URI 字符串；空 = 不启用）。
     * 专属优化（见 ui/theme/BackgroundImage.kt）：降采样解码（大图不爆内存）+
     * 亮度自适应纱罩（按图片明暗自动加深/变浅保证文字可读）+ 按 URI 缓存
     * （一次解码全 App 复用）。设置入口：界面外观 → 自定义背景。
     */
    val backgroundImageUri: String = "",
    /** 背景纱罩强度 0.1..0.8（在自适应基数上的手动叠加；0 = 无纱罩交给自适应）。 */
    val backgroundDim: Float = 0.4f,
    val fontScale: Float = 1.0f,              // 0.8..1.4
    val showTimestamps: Boolean = true,

    // ── 语言 / 输入行为（界面层即时消费；语言切换由 MainActivity recreate 生效）──
    val language: String = "system",          // system | zh | en
    val sendKeyBehavior: String = "send",      // send | newline（Coding 输入框 IME 行为）

    // ── 新手引导 ──
    // 首次启动展示 Onboarding（欢迎/能力/权限/模型配置四页）；
    // 老版本升级用户也会看到一次（ignoreUnknownKeys 反序列化缺省 false），
    // 属预期行为 —— 引导页本身也承担新功能布道。
    val onboardingCompleted: Boolean = false,

    // ── 新手引导 v2（权限全量引导 + 工作区选择）──
    // 已完成的引导版本；低于引导页 OnboardingFlow.CURRENT_VERSION 时首启
    // 重新展示（v2 语义：老用户升级后重看一次，获知新权限步骤与工作区
    // 选择 —— onboardingCompleted 重看语义的版本化延续，字段缺省 0 对
    // 存量 JSON 向后兼容）。
    val onboardingVersion: Int = 0,
    // 工作区范围（引导第 4 页选择）："all" = 操控所有公共存储（默认）；
    // "folder" = 指定 SAF 文件夹。folderUri 是 takePersistableUriPermission
    // 持久化授权后的 tree URI 字符串（chaos-crash-audit C-09：不持久化的
    // content URI 重启即失效），folderName 仅显示用（从 documentId 解析）。
    // 引擎侧接线（文件工具按此收敛沙箱）见 docs/onboarding-permissions.md
    // 的 Phase 2 路线 —— 本 PR 先记录选择 + 就绪页回显。
    val workspaceScope: String = "all",
    val workspaceFolderUri: String = "",
    val workspaceFolderName: String = "",

    // ═══ Coding 专家角色（人设层 · 运行时可热切换，无需重启）═══
    // agentRoles 只存自定义角色；内置专家模板（AgentRole.CODING_EXPERTS，
    // 升级即最新且不可删）运行时合成。codeActiveRoleId 悬空/被删 →
    // activeCodingRole() 诚实回落全栈置顶。操作助手见 AgentRole.kt
    // （withRoleUpserted/withRoleRemoved/withCodingRoleActivated）。
    val agentRoles: List<AgentRole> = emptyList(),
    val codeActiveRoleId: String = AgentRole.BUILTIN_CODING_FULL_STACK_ID,

    // ═══ 工具权限（v1.0 #155 业界标准式权限模式；默认档全开）═══
    // 引擎执行器门控：模式（BYPASS/DEFAULT/ACCEPT_EDITS/PLAN）+ 按序首匹配
    // 规则三元组（ALLOW/ASK/DENY，pattern 支持尾缀星号通配，覆盖 mcp__
    // 动态 id）。决策器见 PermissionDecider，门见 PermissionModeGate。
    // 2026-10 用户反馈（默认全开）：agent 默认就应能直接使用全部工具 /
    // MCP / 技能 / shell 命令，不该逐个弹「请求执行」确认 —— 默认档由
    // DEFAULT 改为 BYPASS（全自动），存量 DEFAULT 用户经
    // [permissionBypassMigrated] 一次性迁移；需要确认预算的用户可在
    // 设置 → 工作台 → 权限 切回其他档位。
    val permissionMode: PermissionMode = PermissionMode.BYPASS,
    val permissionRules: List<PermissionRule> = emptyList(),

    // 默认档迁移标记（见上字段）：存量 JSON 缺省 false → 首启执行一次
    // [migratePermissionDefaultBypass] 并置 true（与 onboardingVersion 同款
    // 版本化迁移惯例，字段缺省值对旧 JSON 向后兼容）。
    val permissionBypassMigrated: Boolean = false,

    // ═══ 行为规则（v1.1 #164 Rules 规则系统）═══
    // 全局规则：设置层持久化的自由文本，对所有工作区生效，注入系统提示词
    // "## Global Rules" 段（coding 模式经 additionalSystemContext 通道注入）。
    // 项目规则（AGENTS.md/CLAUDE.md/.cursorrules）不持久化，由 RulesProvider
    // 每轮从工作区即时发现。默认空串 = 无规则，向后兼容零迁移。
    val globalRules: String = "",

    // ═══ v3 子代理全面完善（预算/自定义类型可配置）═══
    // 此前全部硬编码（3 并发/300s/15 轮/8K），零可配置。接线：ToolModule
    // 构造 SubAgentRunner 时注入快照 provider；标准线库内派发器
    // 同款消费。自定义类型由 CodeTask 的 custom 类型 + custom_name 按名解析。
    val subagent: SubAgentSettings = SubAgentSettings(),

    // ═══ v3 GOAL 目标模式（Coding 屏 GOAL 模式默认值）═══
    // goalMaxRounds：新目标的默认验收轮次上限（创建对话框预填）。
    // goalVerifierEnabled：快速模型验收开关（关闭后信任主模型自评，省 token）。
    val goalMaxRounds: Int = 8,
    val goalVerifierEnabled: Boolean = true,

    // ═══ v3 Coding 工位增强 ═══
    // codingToolSurface：Coding 屏工具面——"full"（默认，CORE+激活全量）|
    // "core"（仅 code_/git/github/terminal 核心编码工具，降噪聚焦）。
    // codingReadGuard：标准线 read-before-edit 硬约束开关（默认开；关闭后
    // 旧工作流免读直改，风险自担）。
    // codingAutoRules：AGENTS.md/CLAUDE.md/.cursorrules 自动发现注入开关
    // （关闭后仅全局规则生效，项目规则被忽略）。
    // mcpScopeIsolation：MCP/技能复合工具按市场分级在引擎侧隔离的开关
    // （默认开；关闭后回到 v2 的全局可见行为）。
    val codingToolSurface: String = "full",
    // codeOutputMode：Coding 屏输出展示档——"streaming"（默认，Rikka 风格
    // 流式：逐字/逐胶囊实时渲染）| "complete"（整段模式：生成中仅加载进度
    // 卡，run 收束后一次性渲染完整时间轴）。UI 渲染层开关（引擎/会话零
    // 参与），档位在每轮 run 开始时捕获（运行中改设置不打断当前轮）。
    // 见 ui/screen/code/CodeOutputMode.kt。
    val codeOutputMode: String = "streaming",
    val codingReadGuard: Boolean = true,
    val codingAutoRules: Boolean = true,
    val mcpScopeIsolation: Boolean = true,

    // ═══ 模型限速友好（只针对某些用户：有 RPM/TPM/日次数配额的厂商）═══
    // 默认全关 = 历史行为零变化。开启后的三根支柱：
    //  - rateLimitFriendly：总开关（设置 → 模型配置 → 限速友好）；
    //  - rateLimitMinIntervalMs：请求节流 —— 所有 LLM 请求（含压缩摘要/
    //    子代理/验收）发出前在 RateLimitGovernor 排队，相邻起点间隔不小于
    //    该值（llm-adapter 全局单点，无旁路）；
    //  - rateLimitRichPayload：每请求信息密度 —— 工具输出预算 ×2、保留
    //    近轮 +3、压缩阈值上浮（更晚压缩）—— 单请求携带更多上下文，
    //    用更少的请求完成同样的任务（AgentModule/CodeModule 拍平进引擎）。
    // 建议组合（设置页一键应用）：并行工具执行 ≥4（一轮多工具 = 少几轮
    // 请求）+ 上述两项。
    val rateLimitFriendly: Boolean = false,
    val rateLimitMinIntervalMs: Int = 4000,    // 0..60000（0 = 不节流间隔）
    val rateLimitRichPayload: Boolean = true,
)
