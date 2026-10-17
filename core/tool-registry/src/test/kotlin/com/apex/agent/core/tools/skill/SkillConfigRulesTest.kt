package com.apex.agent.core.tools.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SkillConfigRules] 纯函数规则单元测试（就绪门禁：必填字段齐备判定）。
 */
class SkillConfigRulesTest {

    private fun manifest(
        vararg fields: UserConfigField,
        scope: String = "all"
    ): SkillManifest =
        SkillManifest(
            id = "t", name = "t", version = "1.0.0", description = "t",
            scope = scope,
            configuration = SkillConfiguration(userConfig = fields.toList())
        )

    @Test
    fun `no declared config is configured`() {
        assertTrue(SkillConfigRules.isConfigured(manifest(), emptyMap()))
        assertTrue(SkillConfigRules.missingRequiredFields(manifest(), emptyMap()).isEmpty())
    }

    @Test
    fun `required field without value or default blocks readiness`() {
        val m = manifest(UserConfigField(key = "api_key", type = "string", description = "API Key"))
        assertTrue("未填值 → 未配置好", !SkillConfigRules.isConfigured(m, emptyMap()))
        assertEquals(
            listOf("api_key"),
            SkillConfigRules.missingRequiredFields(m, emptyMap()).map { it.key }
        )
    }

    @Test
    fun `stored value satisfies required field`() {
        val m = manifest(UserConfigField(key = "api_key", type = "string", description = "API Key"))
        assertTrue(SkillConfigRules.isConfigured(m, mapOf("api_key" to "sk-1")))
    }

    @Test
    fun `manifest default satisfies required field out of the box`() {
        val m = manifest(
            UserConfigField(key = "region", type = "string", default = "cn-north-1", description = "区域")
        )
        assertTrue("有默认值 → 开箱即用", SkillConfigRules.isConfigured(m, emptyMap()))
    }

    @Test
    fun `optional field never blocks readiness`() {
        val m = manifest(
            UserConfigField(key = "hint", type = "string", required = false, description = "可选")
        )
        assertTrue(SkillConfigRules.isConfigured(m, emptyMap()))
    }

    @Test
    fun `blank stored value does not satisfy required field`() {
        val m = manifest(UserConfigField(key = "api_key", type = "string", description = "API Key"))
        assertTrue("空白值不算已填", !SkillConfigRules.isConfigured(m, mapOf("api_key" to "  ")))
    }

    @Test
    fun `mixed fields readiness follows the strictest missing one`() {
        val m = manifest(
            UserConfigField(key = "ok", type = "string", default = "x", description = "有默认"),
            UserConfigField(key = "missing", type = "string", description = "缺值"),
            UserConfigField(key = "opt", type = "string", required = false, description = "可选")
        )
        val missing = SkillConfigRules.missingRequiredFields(m, emptyMap())
        assertEquals(listOf("missing"), missing.map { it.key })
    }
}
