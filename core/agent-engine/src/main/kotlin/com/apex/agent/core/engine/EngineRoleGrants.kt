package com.apex.agent.core.engine

import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory

/**
 * # 进程级动态角色授予源（真机 P0 修复：browser_* 工具从未到达模型）
 *
 * [AgentConfig.grantedRoles] 是引擎构造期的**不可变快照**，而授予真源
 * （设置开关 `browserRoleRestrictionEnabled` / 模板工坊「浏览器自动化专家」
 * 激活）是运行时热变的。此前 DI 从未填充 `grantedRoles`（恒空集），计划层
 * [EngineToolPlanner] 据此把全部 41 个 `browser_*` 工具整行剔除——而目录层
 * （ToolSearch/ToolList）与调用层（RoleGate）读的是 BrowserRoleAuth 热值
 * 放行。三层真源分裂 → 用户关掉限制 / 激活专家模板都无效，模型永远看不见
 * 浏览器工具，提示词却教它调用 → 400 / 烧迭代 / 「我无法浏览网页」。
 *
 * 修复：app DI 装配时 [install] 一个读 BrowserRoleAuth 现值的 provider；
 * 计划层每轮 [resolve] 取现值。[AgentConfig.inheritDynamicRoleGrants]=false
 * （子代理工厂）或 provider 未注册（纯 JVM 测试 / 未接线）时回退快照 ——
 * 存量行为零变化。
 */
object EngineRoleGrants {

    @Volatile
    private var provider: (() -> Set<String>)? = null

    /** 注册动态授予源；传 null 注销（测试隔离 / 进程收尾用）。 */
    fun install(source: (() -> Set<String>)?) {
        provider = source
    }

    /** 本轮计划层应使用的授予集（每轮现取——模板激活 / 设置切换即时生效）。 */
    fun resolve(config: AgentConfig): Set<String> {
        if (!config.inheritDynamicRoleGrants) return config.grantedRoles
        val source = provider ?: return config.grantedRoles
        return try {
            source()
        } catch (e: Exception) {
            // 防御式：授予源异常不得打断主循环，回退快照并留痕。
            AppLogger.instance.warn(
                LogCategory.ENGINE, "EngineRoleGrants",
                "role grants provider failed, falling back to config snapshot: ${e.message}"
            )
            config.grantedRoles
        }
    }
}
