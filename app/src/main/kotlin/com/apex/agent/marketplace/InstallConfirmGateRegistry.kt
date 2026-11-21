package com.apex.agent.marketplace

import com.apex.agent.marketplace.MarketInstallManager.ManifestPreview

/**
 * 安装确认门禁注册表（#347 P2 修复：双 ViewModel 互踩）。
 *
 * ## 背景与旧缺陷
 *
 * 市场安装确认门禁此前是 [MarketInstallManager] 上的单个 `@Volatile var`：
 * 市场页按导航条目创建 ViewModel，返回栈复入（A 未销毁又建 B）时——
 * - B 的 init **无条件覆盖** gate（A 的注入丢失）；
 * - B 的 onCleared **无条件置 null**（存活的 A 与所有自持 SupervisorJob
 *   scope 的单例控制器 —— Hub/mcpSo/Registry/Operit —— 的后续安装
 *   **全部绕过"市场不可信内容确认门禁"**，promptInjection 未目检直接
 *   落盘并进入下一轮系统提示词）。
 *
 * ## 语义
 *
 * - 身份注册表（非覆盖变量）：后注册者成为当前生效门禁（弹窗归最上层
 *   的 ViewModel，与返回栈语义一致）；
 * - **按身份注销**：ViewModel 销毁只拆自己注册的那一份；当前生效门禁
 *   被注销时自动回退到仍存活的次新注册者（A/B 场景下 B 销毁 → A 回位）；
 * - 注册按身份幂等（同实例重复注册不重复入表）；
 * - 无注册者时 [current] 为 null = 无门禁（单测/直连路径，旧行为）。
 *
 * `synchronized` 保证主线程注册/注销与 IO 线程安装读的可见性
 * （等价替换旧 `@Volatile` 语义）。
 *
 * 独立成类（而非 MarketInstallManager 的内联字段）是为了 JVM 单测
 * 可直测互踩场景（见 InstallConfirmGateRegistryTest）—— Manager 本身
 * 绑定 Android/Hilt 依赖无法纯 JVM 构造。
 */
internal class InstallConfirmGateRegistry {

    private val gates = mutableListOf<(suspend (ManifestPreview) -> Boolean)>()

    /** 当前生效的确认门禁（最后注册者）；无注册者时 null = 无门禁。 */
    val current: (suspend (ManifestPreview) -> Boolean)?
        get() = synchronized(gates) { gates.lastOrNull() }

    /** 注册门禁（ViewModel init 调用）。按身份幂等；注册即成为当前生效门禁。 */
    fun register(gate: (suspend (ManifestPreview) -> Boolean)) {
        synchronized(gates) {
            if (gates.none { it === gate }) gates += gate
        }
    }

    /**
     * 按身份注销门禁（ViewModel onCleared 调用）—— 只拆 [gate] 自己那一份：
     * 被注销者是当前生效门禁时自动回退到仍存活的次新注册者，不影响其他
     * 存活 ViewModel 的门禁。null 为无操作（未注册过任何实例时的安全默认）。
     */
    fun unregister(gate: (suspend (ManifestPreview) -> Boolean)?) {
        if (gate == null) return
        synchronized(gates) {
            gates.removeAll { it === gate }
        }
    }
}
