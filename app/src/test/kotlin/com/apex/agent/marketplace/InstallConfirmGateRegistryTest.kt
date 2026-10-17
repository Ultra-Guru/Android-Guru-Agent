package com.apex.agent.marketplace

import com.apex.agent.marketplace.MarketInstallManager.ManifestPreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #347：安装确认门禁注册表回归锁（双 ViewModel 互踩）。
 *
 * 旧实现是 MarketInstallManager 上的单个 `@Volatile var installConfirmGate`：
 * 市场页按导航条目创建 ViewModel，返回栈复入（A 未销毁又建 B）时——
 * - B 的 init **无条件覆盖** gate（A 的注入丢失）；
 * - B 的 onCleared **无条件置 null** —— 存活的 A 及所有自持 SupervisorJob
 *   scope 的单例控制器（Hub/McpSo/Registry/Operit）的后续安装**全部绕过
 *   "市场不可信内容确认门禁"**。
 *
 * [InstallConfirmGateRegistry] 的语义锁：
 * 1. issue 主场景：A 注册 → B 注册（B 生效）→ B 注销 → **A 回位（不是 null）**；
 * 2. 按身份注销：结构相同的另一实例不受影响；
 * 3. 注册按身份幂等（同实例注册两次，注销一次即清空）；
 * 4. 无注册者时 current 为 null（单测/直连旧行为）；null 注销为无操作。
 *
 * 纯 JVM：捕获变量的 lambda 保证实例唯一性（非捕获 lambda 可能被编译器
 * 单例化，不用于身份断言）。
 */
class InstallConfirmGateRegistryTest {

    private fun gate(
        answer: Boolean,
        counter: MutableList<Int>
    ): (suspend (ManifestPreview) -> Boolean) = { counter += 1; answer }

    private fun preview(): ManifestPreview = ManifestPreview(
        id = "demo",
        name = "Demo",
        version = "1.0",
        description = "",
        author = "",
        license = "",
        category = null,
        tags = emptyList(),
        trustLevel = "community",
        toolCount = 1,
        hasPromptInjection = false,
        missingDependencies = emptyList(),
        requirements = emptyList(),
        privilegeLevel = "none",
        isInstalled = false
    )

    // ── issue 主场景 ──────────────────────────────────────────────────

    @Test
    fun `B 销毁后 A 的门禁回位而不是被静默解除`() {
        val registry = InstallConfirmGateRegistry()
        val aCalls = mutableListOf<Int>()
        val bCalls = mutableListOf<Int>()
        val gateA = gate(true, aCalls)
        val gateB = gate(true, bCalls)

        // A 创建（init 注册）→ 市场页复入 B 创建（B 注册，成为生效门禁）
        registry.register(gateA)
        registry.register(gateB)
        assertSame("后注册者生效（弹窗归最上层 VM）", gateB, registry.current)

        // B 弹出（onCleared 按身份注销）—— 旧实现在这里把全局 gate 置 null
        registry.unregister(gateB)

        // ★ issue 断言：存活的 A 的门禁必须回位（安装仍被门禁，不静默放行）
        assertSame("B 销毁后 A 的门禁必须回位", gateA, registry.current)
    }

    @Test
    fun `A 注册 B 覆盖 B 销毁 A 销毁后门禁归空`() {
        val registry = InstallConfirmGateRegistry()
        val aCalls = mutableListOf<Int>()
        val bCalls = mutableListOf<Int>()
        val gateA = gate(true, aCalls)
        val gateB = gate(true, bCalls)

        registry.register(gateA)
        registry.register(gateB)
        registry.unregister(gateB)
        assertSame(gateA, registry.current)

        // A 也销毁后（返回栈清空）—— 无门禁（旧行为兼容：不再有可弹窗的宿主）
        registry.unregister(gateA)
        assertNull("全部宿主销毁后 current 为 null", registry.current)
    }

    // ── 身份语义 ──────────────────────────────────────────────────────

    @Test
    fun `按身份注销不误伤结构相同的其他实例`() {
        val registry = InstallConfirmGateRegistry()
        // 两个结构完全一致的 gate（都返回 false、各持计数器）——身份不同
        val aCalls = mutableListOf<Int>()
        val bCalls = mutableListOf<Int>()
        val gateA = gate(false, aCalls)
        val gateB = gate(false, bCalls)

        registry.register(gateA)
        registry.register(gateB)
        registry.unregister(gateA)

        assertSame("注销 A 不得误伤 B", gateB, registry.current)
        assertEquals("A 未被调用", 0, aCalls.size)
    }

    @Test
    fun `注册按身份幂等（同实例重复注册只入表一次）`() {
        val registry = InstallConfirmGateRegistry()
        val aCalls = mutableListOf<Int>()
        val gateA = gate(true, aCalls)

        registry.register(gateA)
        registry.register(gateA) // 重复注册（同实例）
        registry.unregister(gateA) // 一次注销即清空 —— 若入表两次则会残留

        assertNull("幂等注册 + 单次注销 = 无残留", registry.current)
    }

    // ── 默认态与 null 安全 ────────────────────────────────────────────

    @Test
    fun `初始无门禁 current 为 null`() {
        assertNull(InstallConfirmGateRegistry().current)
    }

    @Test
    fun `注销 null 是无操作`() {
        val registry = InstallConfirmGateRegistry()
        val aCalls = mutableListOf<Int>()
        val gateA = gate(true, aCalls)
        registry.register(gateA)

        registry.unregister(null)

        assertSame("null 注销不得清掉已注册门禁", gateA, registry.current)
    }

    // ── 生效门禁可被调用（安装管道的实际消费方式）────────────────────

    @Test
    fun `生效门禁可挂起调用并返回结果`() {
        val registry = InstallConfirmGateRegistry()
        val aCalls = mutableListOf<Int>()
        val bCalls = mutableListOf<Int>()
        val gateA = gate(true, aCalls)
        val gateB = gate(false, bCalls)
        registry.register(gateA)
        registry.register(gateB)

        val current = registry.current
        assertNotNull(current)
        val outcome = kotlinx.coroutines.runBlocking { current!!.invoke(preview()) }

        assertTrue("生效门禁是 B（返回 false）", !outcome)
        assertEquals("B 被调用一次", 1, bCalls.size)
        assertEquals("A 未被调用", 0, aCalls.size)
    }
}
