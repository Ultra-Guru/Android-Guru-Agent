package com.apex.agent.tools

import com.apex.agent.core.tools.BrowserRolePolicy
import com.apex.agent.core.tools.ToolMetadata
import com.apex.agent.ui.screen.settings.SettingsRepository
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * # 浏览器角色授权面（v2.1.0 评审 B2 的应用级装配）
 *
 * 职责（与评审 P0-3 两级体系对齐）：
 * - **应用级策略**：读 [SettingsRepository.agentSettings] 的
 *   `browserRoleRestrictionEnabled`（默认开=严格）/ `browserSensitiveAllowed`
 *   （敏感 tier，用户显式授权）——每次 [currentGrants] 现取，设置热生效；
 * - **模板授予**：模板工坊「浏览器自动化专家」（内置模板）激活时经
 *   [grantForSession] 把 toolRole 并入（自定义/导入模板**不可**自授——可信来源
 *   约束在调用点校验 isBuiltIn）；会话结束/撤销经 [clearTemplateGrant]。
 *
 * 进程级单例（object）：RoleGate / 目录 roleFilter / CapabilityReport 的 Browser 行
 * 全部经 [currentGrants] 现取——**单设备单活动会话口径**（方案「运行时隔离」节）：
 * 授予集是对当前活动会话生效的快照；多会话并行各自的授权隔离属 ToolRegistry 层
 * 未来工作（docs/browser-runtime.md 已声明）。
 */
@Singleton
class BrowserRoleAuthInitializer @Inject constructor(
    settings: SettingsRepository,
) {
    init {
        BrowserRoleAuth.attach { settings.agentSettings.value }
    }
}

object BrowserRoleAuth {

    @Volatile
    private var settingsReader: (() -> com.apex.agent.ui.screen.settings.AgentSettings?)? = null

    /** 模板授予（内置专家模板激活时并入；仅 browser-automation 一个来源）。 */
    private val templateGrant = AtomicReference<Set<String>>(emptySet())

    fun attach(reader: () -> com.apex.agent.ui.screen.settings.AgentSettings?) {
        settingsReader = reader
    }

    /** 模板激活授予（调用点必须校验模板 isBuiltIn——可信来源约束）。 */
    fun grantForSession(toolRole: String) {
        if (toolRole.isNotBlank()) templateGrant.set(setOf(toolRole))
    }

    /** 模板撤销 / 会话结束回收。 */
    fun clearTemplateGrant() {
        templateGrant.set(emptySet())
    }

    /**
     * 当前授予集（每次现取：设置开关热生效 + 模板授予叠加）。
     * 设置未挂接（极早启动/测试）按严格默认处理（fail-closed）。
     */
    fun currentGrants(): Set<String> {
        val s = runCatching { settingsReader?.invoke() }.getOrNull()
            ?: return BrowserRolePolicy.defaultGrants(restrictionEnabled = true)
        val sensitive = if (s.browserSensitiveAllowed) {
            setOf(BrowserRolePolicy.ROLE_BROWSER_SENSITIVE)
        } else emptySet()
        return BrowserRolePolicy.defaultGrants(s.browserRoleRestrictionEnabled) + sensitive + templateGrant.get()
    }

    /** 目录/计划共用的可见性判定（与 EngineToolPlanner.isToolVisibleInRoles 同口径）。 */
    fun isVisible(metadata: ToolMetadata): Boolean {
        val required = metadata.requiredRoles
        if (required.isEmpty()) return true
        return currentGrants().containsAll(required)
    }
}
