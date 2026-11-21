package com.apex.agent.core.code.gitskill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Git 角色专属技能目录 + 引擎测试。
 *
 * 覆盖：9 套技能完整性与唯一性、runbook 结构完整性（阶段/命令/验证/
 * 护栏非空）、归属判定（Git 角色 + 副本跟随）、开关语义（缺席=开、
 * 稀疏存储、未知 id 忽略）、触发词匹配（分数/短语加权/同分稳定序/
 * 关闭技能不推荐）、注入段构建（目录行/护栏/空集空串）、runbook 渲染。
 */
class GitRoleSkillCatalogTest {

    // ═══ 目录完整性 ═══

    @Test
    fun `catalog has exactly nine skills with unique ids`() {
        assertEquals(9, GitRoleSkillEngine.ALL.size)
        assertEquals(9, GitRoleSkillEngine.ALL.map { it.id }.toSet().size)
    }

    @Test
    fun `catalog covers all user specified skills`() {
        val expected = setOf(
            "git-advanced-workflows", "git-advanced", "git-workflows",
            "github-actions-advanced", "sync-repos", "relay-merge",
            "gh-cherry-pick", "github-multi-repo", "github-release-management"
        )
        assertEquals(expected, GitRoleSkillEngine.ALL.map { it.id }.toSet())
    }

    @Test
    fun `every skill has structural runbook`() {
        GitRoleSkillEngine.ALL.forEach { skill ->
            assertTrue("skill ${skill.id} name blank", skill.name.isNotBlank())
            assertTrue("skill ${skill.id} summary blank", skill.summary.isNotBlank())
            assertTrue("skill ${skill.id} description blank", skill.description.length >= 40)
            assertTrue("skill ${skill.id} triggers empty", skill.triggers.isNotEmpty())
            assertTrue("skill ${skill.id} phases < 3", skill.phases.size >= 3)
            skill.phases.forEach { phase ->
                assertTrue("phase ${phase.name} verify blank", phase.verify.isNotBlank())
                assertTrue("phase ${phase.name} goal blank", phase.goal.isNotBlank())
            }
            assertTrue("skill ${skill.id} guards empty", skill.guards.isNotEmpty())
            assertTrue("skill ${skill.id} whenToUse blank", skill.whenToUse.isNotBlank())
        }
    }

    @Test
    fun `community sourced skills carry upstream repos`() {
        // 用户指定的四个社区源逐一标注（归属透明）。
        val withSource = GitRoleSkillEngine.ALL.mapNotNull { it.sourceRepo }
        assertEquals(
            setOf(
                "https://github.com/sickn33/antigravity-awesome-skills",
                "https://github.com/AutumnsGrove/ClaudeSkills",
                "https://github.com/MilkClouds/my-dev-playbook",
                "https://github.com/dEitY719/gh-flow-skills",
                "https://github.com/DreamLab-AI/agentbox"
            ),
            withSource.toSet()
        )
        GitRoleSkillEngine.ALL.forEach { skill ->
            assertTrue(
                "skill ${skill.id} sourceNote blank",
                skill.sourceNote.isNotBlank()
            )
        }
    }

    @Test
    fun `command templates contain no template interpolation traps`() {
        // 命令模板里的 `${{ }}` 必须以转义形态存在（编译期保障）——
        // 这里锁语义：github-actions 技能的 concurrency 命令真实可达。
        val actions = GitRoleSkillEngine.byId("github-actions-advanced")!!
        assertTrue(
            actions.phases.any { p ->
                p.commands.any { it.contains("github.ref") }
            }
        )
    }

    // ═══ 归属判定 ═══

    @Test
    fun `skills apply to builtin git role and its duplicates`() {
        assertTrue(
            GitRoleSkillEngine.skillsApplyToRole(
                roleId = "builtin_coding_git",
                roleDefinition = "Git power user: branching strategy…"
            )
        )
        // 「另存为」副本：id 是 role_<ts>，definition 原样携带前缀。
        assertTrue(
            GitRoleSkillEngine.skillsApplyToRole(
                roleId = "role_1761000000",
                roleDefinition = "Git power user: branching strategy…（定制版）"
            )
        )
        assertFalse(
            GitRoleSkillEngine.skillsApplyToRole(
                roleId = "builtin_coding_kotlin",
                roleDefinition = "Kotlin specialist: idiomatic null-safety…"
            )
        )
        // 前缀必须是行首含义（包含但不前导的不算 —— 防误判）。
        assertFalse(
            GitRoleSkillEngine.skillsApplyToRole(
                roleId = "role_x",
                roleDefinition = "作为 Git power user 的粉丝……"
            )
        )
    }

    // ═══ 开关语义（默认全开）═══

    @Test
    fun `absence means enabled by default`() {
        GitRoleSkillEngine.ALL.forEach { skill ->
            assertTrue(GitRoleSkillEngine.isEnabled(skill, emptyMap()))
        }
        assertTrue(GitRoleSkillEngine.enabledSkills(emptyMap()).size == 9)
    }

    @Test
    fun `explicit false disables only that skill`() {
        val toggles = mapOf("sync-repos" to false)
        assertFalse(GitRoleSkillEngine.isEnabled(GitRoleSkillEngine.byId("sync-repos")!!, toggles))
        assertTrue(GitRoleSkillEngine.isEnabled(GitRoleSkillEngine.byId("git-advanced")!!, toggles))
        assertEquals(8, GitRoleSkillEngine.enabledSkills(toggles).size)
    }

    @Test
    fun `toggle store is sparse for default-on`() {
        // 关 → 写 false；开 → 移除键（默认态 = 稀疏存储）。
        var toggles: Map<String, Boolean> = emptyMap()
        toggles = GitRoleSkillEngine.withToggled(toggles, "relay-merge", false)
        assertEquals(mapOf("relay-merge" to false), toggles)
        toggles = GitRoleSkillEngine.withToggled(toggles, "relay-merge", true)
        assertTrue(toggles.isEmpty())
    }

    @Test
    fun `unknown skill id toggle is ignored`() {
        val toggles = GitRoleSkillEngine.withToggled(emptyMap(), "no-such-skill", false)
        assertTrue(toggles.isEmpty())
    }

    // ═══ 触发词匹配（「看情况用哪个」）═══

    @Test
    fun `match scores route task text to right skills`() {
        val matches = GitRoleSkillEngine.matchScores("把这两个仓库的提交同步一下，保留原作者")
        assertTrue(matches.isNotEmpty())
        assertEquals("sync-repos", matches.first().skill.id)
        assertTrue(matches.first().hits.any { it.contains("同步") || it.contains("仓库") })

        val ci = GitRoleSkillEngine.matchScores("加固 GitHub Actions 工作流：触发去重与缓存")
        assertEquals("github-actions-advanced", ci.first().skill.id)

        val conflict = GitRoleSkillEngine.matchScores("解决变基产生的合并冲突")
        assertTrue(conflict.any { it.skill.id == "git-advanced" })
        assertTrue(conflict.any { it.skill.id == "git-advanced-workflows" })
    }

    @Test
    fun `phrase triggers score higher than single words`() {
        val matches = GitRoleSkillEngine.matchScores("push 被拒了，推送受阻，试试中继")
        assertEquals("relay-merge", matches.first().skill.id)
        // 「推送受阻」短语命中应有 +0.5 加权（score > 命中词数）。
        assertTrue(matches.first().score > matches.first().hits.size)
    }

    @Test
    fun `disabled skills never appear in recommendations`() {
        val toggles = mapOf("sync-repos" to false)
        val matches = GitRoleSkillEngine.matchScores("同步两个仓库保留作者", toggles)
        assertTrue(matches.none { it.skill.id == "sync-repos" })
    }

    @Test
    fun `irrelevant task gets empty recommendation`() {
        assertTrue(GitRoleSkillEngine.matchScores("帮我写一首诗").isEmpty())
        assertTrue(GitRoleSkillEngine.matchScores("   ").isEmpty())
    }

    @Test
    fun `same score breaks tie by id for reproducibility`() {
        // 任务同时命中多技能时排序确定（同分按 id 字典序）。
        val a = GitRoleSkillEngine.matchScores("修复 rebase 冲突")
        val b = GitRoleSkillEngine.matchScores("修复 rebase 冲突")
        assertEquals(a.map { it.skill.id }, b.map { it.skill.id })
    }

    // ═══ 注入段 ═══

    @Test
    fun `definition hint lists catalog lines and guards`() {
        val hint = GitRoleSkillEngine.definitionHintFor(GitRoleSkillEngine.ALL)
        assertTrue(hint.contains("Git 专属技能"))
        assertTrue(hint.contains("9 套"))
        // 目录行：每套技能一行（id + 摘要）。
        GitRoleSkillEngine.ALL.forEach { skill ->
            assertTrue(hint.contains(skill.id))
        }
        // 护栏逐条在场（破坏性操作批准线）。
        assertTrue(hint.contains("绝不自动执行 push --force"))
        assertTrue(hint.contains("安全红线"))
    }

    @Test
    fun `definition hint empty when all disabled`() {
        val allOff = GitRoleSkillEngine.ALL.associate { it.id to false }
        assertEquals("", GitRoleSkillEngine.definitionHintFor(GitRoleSkillEngine.enabledSkills(allOff)))
        assertTrue(GitRoleSkillEngine.enabledSkills(allOff).isEmpty())
    }

    @Test
    fun `hint respects toggled subset`() {
        val toggles = mapOf("relay-merge" to false, "gh-cherry-pick" to false)
        val enabled = GitRoleSkillEngine.enabledSkills(toggles)
        val hint = GitRoleSkillEngine.definitionHintFor(enabled)
        assertTrue(hint.contains("7 套"))
        assertFalse(hint.contains("relay-merge —"))
    }

    // ═══ runbook 渲染 ═══

    @Test
    fun `renderRunbook produces full phase pipeline`() {
        val skill = GitRoleSkillEngine.byId("git-workflows")!!
        val runbook = GitRoleSkillEngine.renderRunbook(skill)
        assertTrue(runbook.contains("### 🧭 提交纪律与安全流程"))
        assertTrue(runbook.contains("阶段1 · 只读侦察"))
        assertTrue(runbook.contains("验证："))
        assertTrue(runbook.contains("护栏："))
        assertTrue(runbook.contains("适用判据："))
        // 命令模板在场（真实可执行序列）。
        assertTrue(runbook.contains("git status"))
        assertTrue(runbook.contains("git add -p"))
    }

    @Test
    fun `byId returns null for unknown`() {
        assertNull(GitRoleSkillEngine.byId("ghost-skill"))
        assertNotNull(GitRoleSkillEngine.byId("git-advanced-workflows"))
    }
}
