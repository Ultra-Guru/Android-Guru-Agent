package com.apex.agent.core.engine.templates.playbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内置剧本库一致性测试（出厂表自洽锁）：
 * id 规范与唯一、幕结构、变量声明覆盖引用、触发表齐备、侦察绑定合法、
 * 幕推进协议契约措辞一致（master 声明 ↔ 判定器解析）。
 */
class BuiltinPlaybooksTest {

    @Test
    fun `内置五套 id 规范且唯一`() {
        assertEquals(5, BuiltinPlaybooks.ALL.size)
        val ids = BuiltinPlaybooks.ALL.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        for (id in ids) {
            assertTrue("id 不合规范: $id", PLAYBOOK_ID_PATTERN.matches(id))
            assertTrue("内置前缀缺失: $id", id.startsWith("builtin_playbook_"))
        }
    }

    @Test
    fun `每套剧本至少一幕且幕 id 剧本内唯一`() {
        for (playbook in BuiltinPlaybooks.ALL) {
            assertTrue("${playbook.id} 无幕", playbook.stages.isNotEmpty())
            val stageIds = playbook.stages.map { it.id }
            assertEquals("${playbook.id} 幕 id 重复", stageIds.size, stageIds.toSet().size)
            for (stage in playbook.stages) {
                assertTrue("幕子提示词空白: ${playbook.id}/${stage.id}", stage.subPrompt.isNotBlank())
            }
        }
    }

    @Test
    fun `引用变量全部有声明（防渲染漏参）`() {
        for (playbook in BuiltinPlaybooks.ALL) {
            val declared = playbook.variables.map { it.name.lowercase() }.toSet()
            val referenced = playbook.referencedVariables()
            assertTrue(
                "${playbook.id} 引用了未声明变量: ${referenced - declared}",
                referenced.all { it in declared }
            )
        }
    }

    @Test
    fun `可自动挂载剧本触发表齐备（无触发表 = 只能手动）`() {
        // COMPOSED 的 Git 提交剧本也必须有触发表（用户拍板首发五套全部可自动识别）。
        for (playbook in BuiltinPlaybooks.ALL) {
            assertTrue("${playbook.id} 缺触发表", playbook.triggers.isNotEmpty())
            for (rule in playbook.triggers) {
                assertTrue("${playbook.id} 规则无信号", rule.keywords.isNotEmpty() || rule.regexes.isNotEmpty())
                assertTrue("${playbook.id} 规则分数非正", rule.score > 0)
            }
        }
    }

    @Test
    fun `侦察绑定合法（null 或目录内 id）`() {
        for (playbook in BuiltinPlaybooks.ALL) {
            for (stage in playbook.stages) {
                assertTrue(
                    "${playbook.id}/${stage.id} 绑定了未注册侦察 '${stage.reconId}'",
                    stage.reconId == null || stage.reconId in PlaybookReconCatalog.ALL_IDS
                )
            }
        }
    }

    @Test
    fun `幕推进协议契约措辞一致（master 声明解析器认知的标记形态）`() {
        for (playbook in BuiltinPlaybooks.ALL) {
            if (playbook.mode == PlaybookExecutionMode.SEQUENCED) {
                assertTrue(
                    "${playbook.id} master 未声明幕章节头协议",
                    playbook.master.contains("【幕k·")
                )
                // 末幕验收必须包含完成标记要求（判定器收尾通道的契约面）。
                val lastStage = playbook.stages.last()
                assertTrue(
                    "${playbook.id} 末幕验收未要求完成标记",
                    lastStage.acceptance.any { it.contains("完成") }
                )
            }
        }
    }

    @Test
    fun `标记格式与幕标题一致`() {
        val playbook = BuiltinPlaybooks.ALL.first()
        assertEquals(
            "【幕1·${playbook.stages.first().title}】",
            PlaybookDirectiveBuilder.STAGE_HEADER_FORMAT.format(1, playbook.stages.first().title)
        )
        assertTrue(playbook.stageMarker(0).startsWith("幕1·"))
    }

    @Test
    fun `isKnownBuiltInId 判定与导入防御口径一致`() {
        assertTrue(BuiltinPlaybooks.isKnownBuiltInId(BuiltinPlaybooks.ALL.first().id))
        assertFalse(BuiltinPlaybooks.isKnownBuiltInId("user_playbook_x"))
        assertFalse(BuiltinPlaybooks.isKnownBuiltInId(""))
    }
}
