package com.apex.agent.backup

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.apex.agent.core.llm.ModelProfile
import com.apex.agent.core.llm.ModelRoleConfig
import com.apex.agent.core.llm.ProviderConfig
import com.apex.agent.ui.screen.settings.AgentSettings
import com.apex.agent.ui.screen.settings.SettingsRepository
import com.apex.agent.vault.InMemoryVaultStore
import com.apex.agent.vault.VaultRepository
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #853 回归套件：备份恢复必须热回灌 SettingsRepository 内存态。
 *
 * 缺陷链（修复前）：BackupManager.import 只写 SharedPreferences，仓库内存
 * StateFlow 仍是首启快照 —— 恢复后用户编辑任意一项设置（upsertProfile /
 * upsertProvider / updateAgentSettings 等）都会把**陈旧内存快照**经
 * persist* 整体写回，静默抹掉刚导入的 Provider / Profile / 角色映射 /
 * Agent 参数。UI 只提示「重启后生效」，用户在重启前编辑即丢数据。
 *
 * 修复：import 写回 SP 后立即 [SettingsRepository.reloadFromDisk]（与 init
 * 共用同一加载路径）。本套件钉死四个断言面：
 *  - reload 语义：直接写 SP → reload → 内存流更新，且 reload 只读不写
 *    （写盘纪律：无谓回写被禁止）；
 *  - 端到端：import 完成即内存态生效（无需重启），providers 键写回 SP 时
 *    apiKeys 仍强制脱敏（脱敏铁律不因新路径回退）；
 *  - 数据不再丢：import → 立即编辑（增改 Profile / Provider）→ 导入数据
 *    在内存与磁盘双双存活（修复前此步即抹除）；
 *  - 导入修复损坏域后，首页损坏横幅（B0-BUG-01 登记表）即时消退。
 *
 * 测试基建（无 mock 框架纪律）：手写 [FakeSharedPreferences] + 基于
 * ContextWrapper 的 [FakeSettingsContext]。JVM 单测上 MasterKey.Builder
 * 因 SDK_INT=0 抛 IllegalArgumentException，仓库 securePrefs 走既有
 * 「Keystore 不可用 → 退化普通 SP」分支 —— 与真机 Keystore 故障路径同源，
 * 无需 Robolectric。
 */
class BackupRestoreHotReloadTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private val importedProvider = ProviderConfig(
        id = "imported_provider",
        displayName = "Imported Provider",
        baseUrl = "https://imported.example.com/v1",
        // 备份文件里的明文 Key：import 写回 SP 前必须被强制脱敏（铁律）
        apiKeys = listOf("sk-plaintext-must-be-stripped"),
    )

    private val importedProfile = ModelProfile(
        id = "imported_profile",
        name = "Imported Profile",
        providerId = "imported_provider",
        modelId = "imported-model",
        isDefault = true,
    )

    // ── 手写 fake ───────────────────────────────────────────────

    /**
     * 进程内 SharedPreferences：单线程测试专用（不加锁）。apply/commit 语义
     * 对齐真机 —— putString(key, null) 即删除该键；[commitCount] 记录提交
     * 次数供「reload 只读不写」断言用。变更监听仅登记不广播（被测路径不
     * 消费监听，广播是死代码）。
     */
    private class FakeSharedPreferences : SharedPreferences {
        val map = mutableMapOf<String, Any>()
        var commitCount = 0
        private val listeners =
            mutableListOf<SharedPreferences.OnSharedPreferenceChangeListener>()

        override fun getAll(): Map<String, Any> = map.toMap()
        override fun getString(key: String?, defValue: String?): String? =
            map[key] as? String ?: defValue

        override fun getStringSet(
            key: String?,
            defValues: MutableSet<String>?,
        ): MutableSet<String>? =
            (map[key] as? Set<*>)?.mapTo(mutableSetOf()) { it.toString() } ?: defValues

        override fun getInt(key: String?, defValue: Int): Int = map[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = map[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = map[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean =
            map[key] as? Boolean ?: defValue

        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = FakeEditor(this)

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener,
        ) {
            listeners += listener
        }

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener,
        ) {
            listeners -= listener
        }

        private class FakeEditor(private val prefs: FakeSharedPreferences) :
            SharedPreferences.Editor {
            private val puts = mutableListOf<Pair<String, Any>>()
            private val removals = mutableSetOf<String>()
            private var clearAll = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                if (value == null) removals += key.orEmpty()
                else puts += key.orEmpty() to value
                return this
            }

            override fun putStringSet(
                key: String?,
                values: MutableSet<String>?,
            ): SharedPreferences.Editor {
                if (values == null) removals += key.orEmpty()
                else puts += key.orEmpty() to values
                return this
            }

            override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
                puts += key.orEmpty() to value
                return this
            }

            override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
                puts += key.orEmpty() to value
                return this
            }

            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
                puts += key.orEmpty() to value
                return this
            }

            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
                puts += key.orEmpty() to value
                return this
            }

            override fun remove(key: String?): SharedPreferences.Editor {
                removals += key.orEmpty()
                return this
            }

            override fun clear(): SharedPreferences.Editor {
                clearAll = true
                return this
            }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                if (clearAll) prefs.map.clear()
                removals.forEach(prefs.map::remove)
                puts.forEach { (k, v) -> prefs.map[k] = v }
                prefs.commitCount++
            }
        }
    }

    /** ContextWrapper 壳：只接 getSharedPreferences（按名共享同一实例，同进程语义）。 */
    private class FakeSettingsContext : ContextWrapper(null) {
        private val stores = mutableMapOf<String, FakeSharedPreferences>()

        override fun getApplicationContext(): Context = this

        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
            stores.getOrPut(name.orEmpty()) { FakeSharedPreferences() }

        fun prefs(name: String): FakeSharedPreferences =
            getSharedPreferences(name, Context.MODE_PRIVATE) as FakeSharedPreferences
    }

    // ── 测试 ─────────────────────────────────────────────────────

    @Test
    fun `reloadFromDisk re-reads sp keys into in-memory flows without writing`() = runTest {
        val context = FakeSettingsContext()
        val repo = SettingsRepository(context)
        val prefs = context.prefs(BackupManager.SETTINGS_PREFS_NAME)
        val commitsBefore = prefs.commitCount

        // 模拟导入写盘面（BackupManager.import 对 SP 做的事）
        prefs.edit()
            .putString(BackupManager.KEY_PROVIDERS, json.encodeToString(listOf(importedProvider)))
            .putString(BackupManager.KEY_PROFILES, json.encodeToString(listOf(importedProfile)))
            .putString(
                BackupManager.KEY_ROLES,
                json.encodeToString(ModelRoleConfig(primaryProfileId = "imported_profile")),
            )
            .putString(
                BackupManager.KEY_AGENT,
                json.encodeToString(AgentSettings(keepAlive = false)),
            )
            .apply()

        repo.reloadFromDisk()

        assertTrue(
            "providers 流必须反映刚写入 SP 的导入数据",
            repo.providers.value.any { it.id == importedProvider.id },
        )
        assertTrue(
            "profiles 流必须反映刚写入 SP 的导入数据",
            repo.profiles.value.any { it.id == importedProfile.id },
        )
        assertEquals("imported_profile", repo.roles.value.primaryProfileId)
        assertFalse("agentSettings 流必须反映导入值", repo.agentSettings.value.keepAlive)
        // 写盘纪律：reload 只读不写 —— commit 计数仅含上面的模拟导入那一次
        assertEquals(
            "reloadFromDisk 不得产生任何 SP 回写",
            commitsBefore + 1,
            prefs.commitCount,
        )
    }

    @Test
    fun `import hot-reloads repository state without restart and keeps api keys redacted`() =
        runTest {
            val context = FakeSettingsContext()
            val repo = SettingsRepository(context)
            val manager = BackupManager(context, VaultRepository(InMemoryVaultStore()), repo)
            val prefs = context.prefs(BackupManager.SETTINGS_PREFS_NAME)

            val result = manager.import(
                json = bundleJson(),
                restoreSettings = true,
                restoreVault = false,
                vaultPassphrase = null,
            )

            assertTrue(result.settingsImported)
            assertTrue("导入路径不应有错误：${result.errors}", result.errors.isEmpty())
            // 修复核心：不重启，内存态立即生效
            assertTrue(repo.providers.value.any { it.id == importedProvider.id })
            assertTrue(repo.profiles.value.any { it.id == importedProfile.id })
            assertEquals("imported_profile", repo.roles.value.primaryProfileId)
            assertFalse(repo.agentSettings.value.keepAlive)
            // 脱敏铁律不回退：写回 SP 的 providers 键 apiKeys 恒空
            val onDisk = json.decodeFromString<List<ProviderConfig>>(
                requireNotNull(prefs.getString(BackupManager.KEY_PROVIDERS, null))
            )
            assertTrue(onDisk.all { it.apiKeys.isEmpty() })
        }

    @Test
    fun `editing settings after import no longer wipes imported data`() = runTest {
        val context = FakeSettingsContext()
        val repo = SettingsRepository(context)
        val manager = BackupManager(context, VaultRepository(InMemoryVaultStore()), repo)
        val prefs = context.prefs(BackupManager.SETTINGS_PREFS_NAME)
        manager.import(
            json = bundleJson(),
            restoreSettings = true,
            restoreVault = false,
            vaultPassphrase = null,
        )

        // 修复前：此刻内存仍是首启种子快照，下面两次编辑会把陈旧快照整体
        // 写回 —— 导入的 provider / profile 被静默抹掉（#853 数据丢失路径）
        repo.upsertProfile(
            ModelProfile(
                id = "profile_added_after_import",
                name = "Edited After Import",
                providerId = importedProvider.id,
                modelId = "edited-model",
            )
        )
        repo.upsertProvider(
            ProviderConfig(
                id = "local_added_after_import",
                displayName = "Local Added",
                baseUrl = "https://local.example.com/v1",
            )
        )

        // 内存存活
        assertTrue(repo.providers.value.any { it.id == importedProvider.id })
        assertTrue(repo.profiles.value.any { it.id == importedProfile.id })
        // 磁盘也存活（persist 写的是回灌后的当前流，不再是陈旧快照）
        val providersOnDisk = json.decodeFromString<List<ProviderConfig>>(
            requireNotNull(prefs.getString(BackupManager.KEY_PROVIDERS, null))
        )
        assertTrue(
            "导入的 Provider 必须在 SP 中存活",
            providersOnDisk.any { it.id == importedProvider.id },
        )
        assertTrue(
            "编辑新增的 Provider 必须落盘",
            providersOnDisk.any { it.id == "local_added_after_import" },
        )
        val profilesOnDisk = json.decodeFromString<List<ModelProfile>>(
            requireNotNull(prefs.getString(BackupManager.KEY_PROFILES, null))
        )
        assertTrue(
            "导入的 Profile 必须在 SP 中存活",
            profilesOnDisk.any { it.id == importedProfile.id },
        )
        assertTrue(
            "编辑新增的 Profile 必须落盘",
            profilesOnDisk.any { it.id == "profile_added_after_import" },
        )
    }

    @Test
    fun `reload after import clears stale corruption banner`() = runTest {
        val context = FakeSettingsContext()
        val prefs = context.prefs(BackupManager.SETTINGS_PREFS_NAME)
        // 先落一块坏 JSON（模拟手改 / 半写文件），首启登记损坏域并挂横幅
        prefs.edit().putString(BackupManager.KEY_PROVIDERS, "not-json").apply()
        val repo = SettingsRepository(context)
        assertTrue(
            "坏 JSON 必须先登记损坏域",
            repo.dataCorruption.value.contains(BackupManager.KEY_PROVIDERS),
        )

        // 修复：导入有效 providers JSON → 热回灌 → 横幅消退
        prefs.edit()
            .putString(
                BackupManager.KEY_PROVIDERS,
                json.encodeToString(listOf(importedProvider)),
            )
            .apply()
        repo.reloadFromDisk()

        assertTrue(
            "导入修复后损坏横幅必须消退",
            repo.dataCorruption.value.isEmpty(),
        )
        assertTrue(repo.providers.value.any { it.id == importedProvider.id })
    }

    // ── 辅助 ─────────────────────────────────────────────────────

    /** 与导出端同构的最小备份文件（settingsJson 含明文 Key —— import 必须脱敏）。 */
    private fun bundleJson(): String = json.encodeToString(
        BackupBundle.serializer(),
        BackupBundle(
            formatVersion = BackupManager.CURRENT_FORMAT_VERSION,
            createdAt = 1_700_000_000_000L,
            appVersionName = "test",
            appVersionCode = 1,
            settingsJson = mapOf(
                BackupManager.KEY_PROVIDERS to json.encodeToString(listOf(importedProvider)),
                BackupManager.KEY_PROFILES to json.encodeToString(listOf(importedProfile)),
                BackupManager.KEY_ROLES to json.encodeToString(
                    ModelRoleConfig(primaryProfileId = "imported_profile")
                ),
                BackupManager.KEY_AGENT to json.encodeToString(AgentSettings(keepAlive = false)),
            ),
        ),
    )
}
