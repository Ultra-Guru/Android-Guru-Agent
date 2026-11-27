package com.apex.agent.core.tools.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ═══ S02 意图清单 —— 单一事实源不变量测试 ═══
 *
 * 直接加载仓库真实 intents.json（app/src/main/assets/device 目录）：
 * **新增 intent 自动进本测试**（穷举断言，不逐条手写）；与
 * scripts/check_intents.py 同口径，双保险。
 *
 * 资源读取不用反射 —— 按工作目录候选路径直读文件（Gradle 单测工作目录
 * = 模块根；本地 kotlinc 合编 = 仓库根）。
 */
class IntentCatalogTest {

    private fun loadReal(): IntentCatalog {
        val candidates = listOf(
            File("app/src/main/assets/device/intents.json"),
            File("../app/src/main/assets/device/intents.json")
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("找不到 intents.json（候选：${candidates.map { it.path } }）")
        return IntentCatalog.load(file.readText())
    }

    @Test
    fun `真实清单加载成功且无校验问题`() {
        val catalog = loadReal()
        assertTrue(
            "loadIssues 应为空，实际：${catalog.loadIssues}",
            catalog.loadIssues.isEmpty()
        )
        assertTrue(
            "validate() 应无问题，实际：${catalog.validate()}",
            catalog.validate().isEmpty()
        )
    }

    @Test
    fun `覆盖下限 package 至少 10 条 file 8 system 6 probe 4`() {
        val catalog = loadReal()
        val byFamily = catalog.all().groupBy { it.id.substringBefore('.') }
        assertTrue("package.* ≥10，实际 ${byFamily["package"]?.size}", (byFamily["package"]?.size ?: 0) >= 10)
        assertTrue("file.* ≥8，实际 ${byFamily["file"]?.size}", (byFamily["file"]?.size ?: 0) >= 8)
        assertTrue("system.* ≥6，实际 ${byFamily["system"]?.size}", (byFamily["system"]?.size ?: 0) >= 6)
        assertTrue("probe.* ≥4，实际 ${byFamily["probe"]?.size}", (byFamily["probe"]?.size ?: 0) >= 4)
    }

    @Test
    fun `id 全局唯一且都是小写点分名`() {
        val catalog = loadReal()
        val ids = catalog.all().map { it.id }
        assertEquals("id 数与去重后一致", ids.size, ids.toSet().size)
        ids.forEach { id ->
            assertTrue("id 格式非法：$id", Regex("^[a-z0-9_]+(\\.[a-z0-9_]+)+$").matches(id))
        }
    }

    @Test
    fun `不可逆意图必有非空 preflight verify 与 rollbackHint`() {
        val catalog = loadReal()
        val irreversible = catalog.all().filter { !it.reversible }
        assertTrue("清单应包含不可逆意图（卸载族）", irreversible.isNotEmpty())
        irreversible.forEach { intent ->
            assertTrue("${intent.id} 缺 preflight", intent.preflight.isNotEmpty())
            assertTrue("${intent.id} 缺 verify", intent.verify.isNotEmpty())
            assertFalse("${intent.id} rollbackHint 为空", intent.rollbackHint.isNullOrBlank())
        }
    }

    @Test
    fun `每个意图的占位符都在 params 或 internal 中闭合`() {
        val catalog = loadReal()
        catalog.all().forEach { intent ->
            val declared = intent.paramNames + intent.internalParams
            intent.usedPlaceholders.forEach { ph ->
                assertTrue("${intent.id} 的占位符 {$ph} 未声明（params=${intent.paramNames} internal=${intent.internalParams}）", ph in declared)
            }
        }
    }

    @Test
    fun `argv 首元素全部在可执行白名单内`() {
        val catalog = loadReal()
        catalog.all().forEach { intent ->
            assertTrue(
                "${intent.id} 的 argv[0]=${intent.argv.first()} 不在白名单",
                intent.argv.first() in IntentCatalog.EXECUTABLE_WHITELIST
            )
            intent.purgeArgv.firstOrNull()?.let {
                assertTrue("purgeArgv[0]=$it 不在白名单", it in IntentCatalog.PURGE_EXECUTABLE_WHITELIST)
            }
        }
    }

    @Test
    fun `criticalIds 等于不可逆意图集合`() {
        val catalog = loadReal()
        val expected = catalog.all().filter { !it.reversible }.map { it.id }.toSet()
        assertEquals(expected, catalog.criticalIds())
    }

    @Test
    fun `supportsFor 按档位过滤 deniesFor 给开通指引`() {
        val catalog = loadReal()
        val supports = catalog.supportsFor(DevicePrivilege.NORMAL_SHELL)
        val denies = catalog.deniesFor(DevicePrivilege.NORMAL_SHELL)
        // NORMAL_SHELL：支持 probe.*（requiredPrivilege=NORMAL_SHELL）
        assertTrue(supports.any { it.startsWith("probe.") })
        // SHIZUKU 意图在 NORMAL_SHELL 下进 denies 且文案含可执行步骤
        val denyShizuku = denies.entries.filter { it.key == "package.uninstall" }
        assertEquals(1, denyShizuku.size)
        assertTrue("denies 文案应含 Shizuku 开通步骤", denyShizuku[0].value.contains("Shizuku"))
        // ROOT 档位全量支持（清单内没有 requiredPrivilege=ROOT 的意图）
        assertTrue(catalog.supportsFor(DevicePrivilege.ROOT).size == catalog.all().size)
    }

    @Test
    fun `坏 intent 被跳过并记 issue 而不是整表失败`() {
        val json = """
            {
              "schema": "apex-device-intents/v1",
              "defaults": {"requiredPrivilege": "SHIZUKU", "confirmWindowMs": 300000, "outputBudgetBytes": 1048576},
              "intents": [
                {
                  "id": "package.good",
                  "argv": ["pm", "list", "packages"],
                  "risk": "READ",
                  "reversible": true,
                  "blastRadius": "只读"
                },
                {
                  "id": "package.bad",
                  "argv": [],
                  "risk": "NOT_A_RISK",
                  "reversible": true,
                  "blastRadius": "x"
                }
              ]
            }
        """.trimIndent()
        val catalog = IntentCatalog.load(json)
        assertEquals("好 intent 保留", listOf("package.good"), catalog.all().map { it.id })
        assertTrue("坏 intent 应记 issue", catalog.loadIssues.isNotEmpty())
    }

    @Test
    fun `整体 JSON 损坏时回退空表而不是崩溃`() {
        val catalog = IntentCatalog.load("not a json {{{")
        assertTrue(catalog.all().isEmpty())
        assertTrue(catalog.loadIssues.isNotEmpty())
    }

    @Test
    fun `defaults 解析与缺省回退`() {
        val catalog = loadReal()
        assertEquals(300_000L, catalog.snapshot.defaults.confirmWindowMs)
        assertEquals(1_048_576L, catalog.snapshot.defaults.outputBudgetBytes)
        assertEquals("apex-device-intents/v1", catalog.snapshot.schema)
        val fallback = IntentCatalog.load("""{"schema":"apex-device-intents/v1"}""")
        assertNull("缺 intents 段回退空表", fallback.byId("package.list"))
        assertEquals(DevicePrivilege.SHIZUKU, fallback.snapshot.defaults.requiredPrivilege)
    }

    @Test
    fun `uninstall_for_user 的 argv 渲染含 --user 且不经 shell`() {
        val catalog = loadReal()
        val intent = catalog.byId("package.uninstall_for_user")
        assertNotNull(intent)
        val argv = ArgvRenderer.render(
            intent!!.argv,
            mapOf("user" to "0", "package" to "com.example.preinstalled")
        )
        assertEquals(
            listOf("pm", "uninstall", "-k", "--user", "0", "com.example.preinstalled"),
            argv
        )
        assertFalse("argv 不含 sh -c 包装", argv.contains("sh") || argv.contains("-c"))
    }

    @Test
    fun `byId 查无返回 null 而不抛异常`() {
        val catalog = loadReal()
        assertNull(catalog.byId("no.such.intent"))
        assertNotNull(catalog.byId("package.uninstall"))
    }
}
