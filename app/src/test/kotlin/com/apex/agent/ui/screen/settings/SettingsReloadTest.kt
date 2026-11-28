package com.apex.agent.ui.screen.settings

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #853 回归 —— [parseReloadDomains]（SettingsRepository.reload 的纯逻辑核）。
 *
 * 场景：备份恢复把 4 个设置键直写 SharedPreferences；仓库的全部持久化都
 * 源自内存快照整体写回 —— reload 必须把导入后的 SP 值回灌内存，否则
 * 恢复后任意一次设置编辑会把恢复前的陈旧快照写回（导入被静默回滚）。
 *
 * 钉死的语义：
 * - 键存在且合法 → 回灌解析值（含 agent 域 permission 迁移）；
 * - 键缺失（bundle 未携带）→ 维持当前内存值（原引用）；
 * - 键损坏（非法 JSON）→ 维持当前内存值 + 上报损坏键（调用方 onCorrupt）。
 */
class SettingsReloadTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun currentDomains(): SettingsDomains = SettingsDomains(
        providers = listOf(ProviderConfig(id = "cur", displayName = "Current", baseUrl = "https://cur")),
        profiles = listOf(ModelProfile(id = "curp", name = "Cur Profile", providerId = "cur", modelId = "m0")),
        roles = ModelRoleConfig(),
        agentSettings = AgentSettings()
    )

    @Test
    fun `valid keys are reloaded into memory snapshot`() {
        val importedProviders = listOf(
            ProviderConfig(id = "imp", displayName = "Imported", baseUrl = "https://imp")
        )
        val importedProfiles = listOf(
            ModelProfile(id = "impp", name = "Imported Profile", providerId = "imp", modelId = "m1", isDefault = true)
        )
        val importedRoles = ModelRoleConfig()
        val importedAgent = AgentSettings(keepAlive = false, permissionBypassMigrated = true)

        val reload = parseReloadDomains(
            rawProviders = json.encodeToString(ListSerializer(ProviderConfig.serializer()), importedProviders),
            rawProfiles = json.encodeToString(ListSerializer(ModelProfile.serializer()), importedProfiles),
            rawRoles = json.encodeToString(ModelRoleConfig.serializer(), importedRoles),
            rawAgent = json.encodeToString(AgentSettings.serializer(), importedAgent),
            current = currentDomains(),
            json = json
        )

        assertEquals(importedProviders, reload.domains.providers)
        assertEquals(importedProfiles, reload.domains.profiles)
        assertEquals(importedRoles, reload.domains.roles)
        assertEquals(false, reload.domains.agentSettings.keepAlive)
        assertTrue("agent 域照走 permission 迁移（幂等标记）", reload.domains.agentSettings.permissionBypassMigrated)
        assertTrue(reload.corruptKeys.isEmpty())
    }

    @Test
    fun `missing keys keep current in-memory values`() {
        val current = currentDomains()
        val reload = parseReloadDomains(
            rawProviders = null,
            rawProfiles = null,
            rawRoles = null,
            rawAgent = null,
            current = current,
            json = json
        )
        // 原引用透传 —— 内存值一个字节都不动（bundle 未携带的域不回退默认）。
        assertTrue(current.providers === reload.domains.providers)
        assertTrue(current.profiles === reload.domains.profiles)
        assertTrue(current.roles === reload.domains.roles)
        assertTrue(current.agentSettings === reload.domains.agentSettings)
        assertTrue(reload.corruptKeys.isEmpty())
    }

    @Test
    fun `corrupt keys keep current values and are reported`() {
        val current = currentDomains()
        val reload = parseReloadDomains(
            rawProviders = "{not valid json",
            rawProfiles = json.encodeToString(
                ListSerializer(ModelProfile.serializer()),
                listOf(ModelProfile(id = "ok", name = "OK", providerId = "cur", modelId = "m"))
            ),
            rawRoles = "]]]",
            rawAgent = "not json at all",
            current = current,
            json = json
        )
        // 损坏域维持内存值（不把用户带回默认态），并上报键名供 onCorrupt 留痕。
        assertTrue(current.providers === reload.domains.providers)
        assertTrue(current.roles === reload.domains.roles)
        assertTrue(current.agentSettings === reload.domains.agentSettings)
        assertEquals(3, reload.corruptKeys.size)
        assertTrue(reload.corruptKeys.contains("model_providers_v2"))
        assertTrue(reload.corruptKeys.contains("model_roles_v2"))
        assertTrue(reload.corruptKeys.contains("agent_settings_v2"))
        // 合法域照常回灌（部分损坏不拖垮整次恢复）。
        assertEquals("ok", reload.domains.profiles.single().id)
    }
}
