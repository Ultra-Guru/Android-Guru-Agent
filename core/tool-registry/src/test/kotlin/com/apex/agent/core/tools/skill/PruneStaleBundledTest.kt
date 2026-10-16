package com.apex.agent.core.tools.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Hub 生态迁移 —— [SkillRegistry.pruneStaleBundled] 单元测试。
 *
 * 场景：APK 内置技能从 75 收敛到 13 个核心，其余迁往官方技能仓库
 * （apex-skill-hub）。升级用户设备上残留的旧内置技能 bundled=true、
 * [SkillRegistry.uninstall] 恒 false（防「卸载后复活」防线），会永久
 * 卡在已安装列表——pruneStaleBundled 按 assets 白名单反向清理。
 *
 * 锁定四个不变量：
 * 1. 只动 bundled 条目：社区安装（bundled=false）的即使不在白名单也保留；
 * 2. 白名单内的内置技能保留（幸存者）；
 * 3. 白名单外的内置技能被清理：内存态 + `<id>.json` + `.disabled` sidecar
 *    + `<id>/` 资源目录四清；
 * 4. 空集调用（全新安装/白名单为空）不误伤社区技能。
 *
 * 纯 JVM（JUnit4 + TemporaryFolder），与同包 BundledSkillsTest 同构。
 */
class PruneStaleBundledTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var skillsDir: File
    private lateinit var registry: SkillRegistry

    @Before
    fun setUp() {
        skillsDir = tmp.newFolder()
        registry = SkillRegistry(skillsDir)
    }

    // ═══ helpers ═══════════════════════════════════════════════════════

    /** 最小可安装的 prompt 型 manifest。 */
    private fun manifestJson(id: String, bundled: Boolean): String = """
        {
          "schema": "apex-skill-v1",
          "id": "$id",
          "name": "$id",
          "version": "1.0.0",
          "description": "$id 描述",
          "author": "Apex",
          "license": "MIT",
          "bundled": $bundled,
          "promptInjection": "【$id】\n\n方法论内容。"
        }
    """.trimIndent()

    private fun installedIds(): Set<String> =
        registry.getInstalled().map { it.manifest.id }.toSet()

    // ═══════════════════════════════════════════════════════════
    // 1 + 2 + 3：混合场景 —— 清离场者、留幸存者、不动社区
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `prune removes stale bundled keeps survivors and community skills untouched`() {
        // 升级前设备状态：5 台内置（含 2 台已迁走的）+ 1 台社区安装的同名域技能
        registry.installBundled(
            listOf(
                manifestJson("CodeReview", bundled = true),     // 幸存（coding 核心）
                manifestJson("Debugging", bundled = true),       // 幸存（coding 核心）
                manifestJson("CookingMaster", bundled = true),  // 已迁往 hub → 待清理
                manifestJson("TravelPlanner", bundled = true),  // 已迁往 hub → 待清理
                manifestJson("KnowledgeQa", bundled = true)     // 幸存（agent 核心）
            )
        )
        registry.install(manifestJson("HubInstalledExtra", bundled = false)) // 社区/仓库安装

        // 其中一台离场者被用户禁用过（.disabled sidecar 有残留 id）
        registry.setEnabled("CookingMaster", false)

        val pruned = registry.pruneStaleBundled(
            setOf("CodeReview", "Debugging", "KnowledgeQa")   // 当前 assets 白名单
        )

        assertEquals(2, pruned)
        assertEquals(
            setOf("CodeReview", "Debugging", "KnowledgeQa", "HubInstalledExtra"),
            installedIds()
        )
        // 离场者四清：manifest 文件也被删除
        assertFalse(File(skillsDir, "cooking-master.json").exists())
        assertFalse(File(skillsDir, "travel-planner.json").exists())
        // 幸存者文件保留
        assertTrue(File(skillsDir, "code-review.json").exists())
        // 社区技能文件保留
        assertTrue(File(skillsDir, "hub-installed-extra.json").exists())
    }

    @Test
    fun `prune cleans resource directory and disabled sidecar residue`() {
        registry.installBundled(listOf(manifestJson("OldLifeSkill", bundled = true)))
        registry.setEnabled("OldLifeSkill", false)

        // 模拟 ZIP 安装产生的资源目录
        val resDir = File(skillsDir, "OldLifeSkill").apply {
            mkdirs()
            File(this, "references.md").writeText("# refs")
        }

        val pruned = registry.pruneStaleBundled(emptySet())

        assertEquals(1, pruned)
        assertFalse(resDir.exists())
        // .disabled sidecar 里不再有残留 id（文件为空或仅剩其它 id）
        val sidecar = File(skillsDir, ".disabled")
        if (sidecar.exists()) {
            assertTrue(sidecar.readLines().none { it.trim() == "OldLifeSkill" })
        }
        assertTrue(installedIds().isEmpty())
    }

    // ═══════════════════════════════════════════════════════════
    // 4：全新安装 / 白名单为空 —— 社区技能不误伤
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `prune with empty whitelist on fresh install does not touch community skills`() {
        registry.install(manifestJson("CommunitySkill", bundled = false))
        registry.install(manifestJson("HubSkill", bundled = false))

        val pruned = registry.pruneStaleBundled(emptySet())

        assertEquals(0, pruned)
        assertEquals(setOf("CommunitySkill", "HubSkill"), installedIds())
    }

    @Test
    fun `prune is idempotent across repeated migrations`() {
        registry.installBundled(listOf(manifestJson("gone", bundled = true)))
        assertEquals(1, registry.pruneStaleBundled(emptySet()))
        // 二次迁移（App 再次启动）：无内置残留可清
        assertEquals(0, registry.pruneStaleBundled(emptySet()))
        assertTrue(installedIds().isEmpty())
    }

    // ═══════════════════════════════════════════════════════════
    // 卸载防线语义互补：prune 之后按普通技能从 hub 重装可自由卸载
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `reinstalled hub skill after prune is uninstallable as normal skill`() {
        registry.installBundled(listOf(manifestJson("CookingMaster", bundled = true)))
        registry.pruneStaleBundled(emptySet())

        // 从官方仓库重新安装（bundled=false）
        registry.install(manifestJson("CookingMaster", bundled = false))

        // 普通技能：可禁用、可卸载（不再受 bundled 防线约束）
        registry.setEnabled("CookingMaster", false)
        assertTrue(registry.uninstall("CookingMaster"))
        assertTrue(installedIds().isEmpty())
        assertFalse(File(skillsDir, "cooking-master.json").exists())
    }
}
