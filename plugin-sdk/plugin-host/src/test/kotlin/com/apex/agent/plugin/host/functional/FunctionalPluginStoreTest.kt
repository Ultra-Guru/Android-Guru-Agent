package com.apex.agent.plugin.host.functional

import com.apex.agent.plugin.api.PluginContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [FunctionalPluginStore] 纯 JVM 单测（Plugin System v2）。
 *
 * 锁死的诚实契约（对齐 WorkflowStoreTest 的防假成功纪律）：
 * 1. install 成功 ⇔ 目录与 manifest 真实落盘（list 能读回全字段）；
 * 2. 一切拒绝（[FunctionalPluginStore.InstallResult.Rejected]）携带人话
 *    理由——id 规范 / 宿主命名空间 / 版本门 / 贡献点形状逐门覆盖；
 * 3. 升级语义：同 id 仅 version 更高覆盖；启停态（.disabled）跨升级保留；
 * 4. BROKEN 隔离：损坏 manifest 折成 StoredPlugin(brokenReason)，不炸 list；
 * 5. 原子写无 .tmp 残留；uninstall 路径穿越拒绝。
 */
class FunctionalPluginStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 单测试实例共享同一 root（懒创建一次）——多段断言复用同目录不冲突。 */
    private val root by lazy { tmp.newFolder("plugins") }

    private fun store(): FunctionalPluginStore = FunctionalPluginStore(root)

    private val validManifest = """
        {
          "id": "functional.daily-briefing",
          "name": "每日简报",
          "version": 1,
          "versionName": "1.0.0",
          "minHostVersion": 1,
          "description": "组合工具生成每日简报",
          "contributions": {
            "tools": [{
              "id": "briefing/run",
              "description": "生成今日简报",
              "steps": [{"tool": "browser_navigate", "args": {"url": "{{input.site}}"}}]
            }],
            "slashCommands": [{"id": "briefing", "prompt": "生成简报"}]
          }
        }
    """.trimIndent()

    // ═══════════════ install → list 往返（落盘真实性） ═══════════════

    @Test
    fun `install 成功后 manifest 真实落盘且 list 能读回`() {
        val store = store()

        val result = store.install(validManifest)

        assertTrue("应成功：$result", result is FunctionalPluginStore.InstallResult.Installed)
        val files = tmp.root.resolve("plugins/functional.daily-briefing").listFiles()!!
            .map { it.name }
        assertTrue(files.contains(PluginContract.FUNCTIONAL_MANIFEST_FILE))
        // 原子写：无 tmp 残留。
        assertTrue(files.none { it.endsWith(".tmp") })

        val stored = store.list().single()
        val manifest = stored.manifest!!
        assertEquals("functional.daily-briefing", manifest.id)
        assertEquals("每日简报", manifest.name)
        assertEquals("briefing/run", manifest.contributions.tools.single().id)
        assertEquals("briefing", manifest.contributions.slashCommands.single().id)
        assertTrue(stored.enabled)
        assertNull(stored.brokenReason)
    }

    @Test
    fun `install 非法 JSON 拒绝且带理由`() {
        val result = store().install("{broken json")

        assertTrue(result is FunctionalPluginStore.InstallResult.Rejected)
        assertTrue((result as FunctionalPluginStore.InstallResult.Rejected).reason.contains("parseable"))
    }

    // ═══════════════ 身份校验门（fail closed 逐门覆盖） ═══════════════

    @Test
    fun `id 命名规范门`() {
        listOf(
            """{"id":"UPPER.Case"}""" to "naming",
            """{"id":"slash/name"}""" to "naming",
            """{"id":"nodot"}""" to "naming",
            """{"id":"a.b/../escape"}""" to "naming"
        ).forEach { (raw, expectKeyword) ->
            val result = store().install(raw)
            assertTrue("应拒绝：$raw", result is FunctionalPluginStore.InstallResult.Rejected)
            val reason = (result as FunctionalPluginStore.InstallResult.Rejected).reason
            assertTrue("理由应含 $expectKeyword：$reason", reason.contains(expectKeyword))
        }
    }

    @Test
    fun `宿主命名空间保留门`() {
        val result = store().install("""{"id":"com.apex.agent.fake","version":1}""")

        assertTrue(result is FunctionalPluginStore.InstallResult.Rejected)
        assertTrue(
            (result as FunctionalPluginStore.InstallResult.Rejected).reason.contains("reserved")
        )
    }

    @Test
    fun `版本门拒绝过新的插件`() {
        val result = store().install(
            """{"id":"functional.future","version":1,"minHostVersion":999}"""
        )

        assertTrue(result is FunctionalPluginStore.InstallResult.Rejected)
        assertTrue((result as FunctionalPluginStore.InstallResult.Rejected).reason.contains("minHostVersion"))
    }

    // ═══════════════ 贡献点形状校验门 ═══════════════

    @Test
    fun `贡献点形状门`() {
        listOf(
            // 空 steps 的工具宏。
            """{"id":"a.b","contributions":{"tools":[{"id":"t1","steps":[]}]}}""",
            // 步内工具 id 为空。
            """{"id":"a.b","contributions":{"tools":[{"id":"t1","steps":[{"tool":""}]}]}}""",
            // 工具 id 重复。
            """{"id":"a.b","contributions":{"tools":[{"id":"t1","steps":[{"tool":"x"}]},{"id":"t1","steps":[{"tool":"y"}]}]}}""",
            // 斜杠指令 id 重复。
            """{"id":"a.b","contributions":{"slashCommands":[{"id":"c"},{"id":"c"}]}}""",
            // 斜杠指令 id 形状非法（大写）。
            """{"id":"a.b","contributions":{"slashCommands":[{"id":"Bad_ID"}]}}"""
        ).forEach { raw ->
            val result = store().install(raw)
            assertTrue("应拒绝：$raw", result is FunctionalPluginStore.InstallResult.Rejected)
        }
    }

    @Test
    fun `宏步数上限门`() {
        val steps = (1..PluginContract.MACRO_MAX_STEPS + 1).joinToString(",") { """{"tool":"t$it"}""" }
        val result = store().install(
            """{"id":"a.b","contributions":{"tools":[{"id":"t","steps":[$steps]}]}}"""
        )

        assertTrue(result is FunctionalPluginStore.InstallResult.Rejected)
        assertTrue(
            (result as FunctionalPluginStore.InstallResult.Rejected).reason.contains("steps")
        )
        // 恰好在限内的步数应通过。
        val inLimit = (1..PluginContract.MACRO_MAX_STEPS).joinToString(",") { """{"tool":"t$it"}""" }
        assertTrue(
            store().install(
                """{"id":"a.b","contributions":{"tools":[{"id":"t","steps":[$inLimit]}]}}"""
            ) is FunctionalPluginStore.InstallResult.Installed
        )
    }

    // ═══════════════ 升级语义（版本比较 + 启停态保留） ═══════════════

    @Test
    fun `同版本重装不覆盖`() {
        val store = store()
        store.install(validManifest)

        val result = store.install(validManifest)

        assertTrue(result is FunctionalPluginStore.InstallResult.Unchanged)
    }

    @Test
    fun `低版本降级不覆盖`() {
        val store = store()
        store.install(validManifest) // version 1

        val downgrade = validManifest.replace("\"version\": 1", "\"version\": 0")
        val result = store.install(downgrade)

        assertTrue(result is FunctionalPluginStore.InstallResult.Unchanged)
        assertEquals(1, store.list().single().manifest?.version)
    }

    @Test
    fun `高版本升级覆盖且启停态保留`() {
        val store = store()
        store.install(validManifest)
        // 用户手动禁用后再升级——禁用态必须保留（安装动作不触碰启停标记）。
        store.setEnabled("functional.daily-briefing", false)

        val upgrade = validManifest.replace("\"version\": 1", "\"version\": 2")
        val result = store.install(upgrade)

        assertTrue(result is FunctionalPluginStore.InstallResult.Upgraded)
        assertEquals(1, (result as FunctionalPluginStore.InstallResult.Upgraded).fromVersion)
        val stored = store.list().single()
        assertEquals(2, stored.manifest?.version)
        assertFalse("启停态应保留", stored.enabled)
    }

    // ═══════════════ 启停 / 卸载 ═══════════════

    @Test
    fun `启停标记幂等且未知 id 拒绝`() {
        val store = store()
        store.install(validManifest)
        val id = "functional.daily-briefing"

        assertTrue(store.setEnabled(id, false))
        assertFalse(store.list().single().enabled)
        // 幂等：重复禁用仍成功。
        assertTrue(store.setEnabled(id, false))
        assertTrue(store.setEnabled(id, true))
        assertTrue(store.list().single().enabled)

        assertFalse(store.setEnabled("functional.unknown", true))
    }

    @Test
    fun `uninstall 删除目录且幂等`() {
        val store = store()
        store.install(validManifest)
        val id = "functional.daily-briefing"

        assertTrue(store.uninstall(id))
        assertTrue(store.list().isEmpty())
        assertFalse("幂等：未知 id 返回 false", store.uninstall(id))
    }

    @Test
    fun `uninstall 路径穿越拒绝`() {
        val store = store()
        store.install(validManifest)

        // 构造穿越参数：不是 root 直接子目录的路径一律拒绝。
        assertFalse(store.uninstall("functional.daily-briefing/.."))
        assertFalse(store.uninstall("."))
        assertFalse(store.uninstall(".."))
    }

    // ═══════════════ BROKEN 隔离（防御式 IO） ═══════════════

    @Test
    fun `损坏 manifest 折成 BROKEN 不炸 list`() {
        val root = tmp.newFolder("plugins")
        val store = FunctionalPluginStore(root)
        val dir = root.resolve("functional.broken").apply { mkdirs() }
        dir.resolve(PluginContract.FUNCTIONAL_MANIFEST_FILE).writeText("{corrupted")

        val storedList = store.list()

        assertEquals(1, storedList.size)
        val broken = storedList.single()
        assertNull(broken.manifest)
        assertNotNull(broken.brokenReason)
        assertTrue(broken.brokenReason!!.contains("解析"))
        assertNull("BROKEN 插件不可激活", broken.activatableManifest)
    }

    @Test
    fun `manifest 缺失的半截目录折成 BROKEN`() {
        val root = tmp.newFolder("plugins")
        val store = FunctionalPluginStore(root)
        root.resolve("functional.half").mkdirs()

        val stored = store.list().single()

        assertNull(stored.manifest)
        assertTrue(stored.brokenReason!!.contains("缺失"))
    }

    @Test
    fun `运行期被改坏的 manifest 复核为 BROKEN`() {
        val store = store()
        store.install(validManifest)
        // 模拟热通道 / 用户手改：磁盘数据不再符合宿主信任假设（占用保留命名空间）。
        val manifestFile = tmp.root.resolve(
            "plugins/functional.daily-briefing/${PluginContract.FUNCTIONAL_MANIFEST_FILE}"
        )
        manifestFile.writeText("""{"id":"com.apex.agent.hijack","version":2}""")

        val stored = store.list().single()

        assertNotNull(stored.manifest)
        assertTrue("身份复核应 BROKEN", stored.brokenReason!!.contains("reserved"))
    }

    @Test
    fun `list 按展示名排序且忽略根目录散落文件`() {
        val root = tmp.newFolder("plugins")
        val store = FunctionalPluginStore(root)
        root.resolve("stray.txt").writeText("not a plugin")
        store.install(validManifest)
        store.install(
            """{"id":"functional.aaa","name":"AAA","version":1,
               "contributions":{"slashCommands":[{"id":"a"}]}}""".trimIndent().replace("\n", "")
        )

        val names = store.list().map { it.displayName }

        assertEquals(listOf("AAA", "每日简报"), names)
    }
}
