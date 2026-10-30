package com.apex.agent.backup

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Base64
import com.apex.agent.BuildConfig
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import com.apex.agent.vault.VaultEntryListSerializer
import com.apex.agent.vault.VaultJson
import com.apex.agent.vault.VaultRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

// ─────────────────────────────────────────────────────────────────────────────
// 备份 / 恢复体系（BackupManager）
//
// 换机不丢数据：把「设置中心 + 聊天历史 +（可选）Vault 金库」打包成一个
// JSON 备份文件，经 SAF（Storage Access Framework）导出到用户选择的位置，
// 新机上选择同一文件恢复。
//
// 备份策略（有意为之的取舍）：
//  - 设置项**原样搬运 JSON 字符串**（model_profiles_v2 等 4 键），不依赖
//    data class 结构 —— 字段增删改都不破坏往返保真，向前兼容性最好；
//  - 敏感数据**永不明文出库**（铁律，见 BackupManager 类 KDoc）：
//    Provider 的 apiKeys 导出前强制脱敏为空数组；Vault 只以 AES-GCM
//    口令信封形式导出（AGM1 格式，见 [BackupCrypto]）。
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 备份加密原语 —— 全部使用 JDK 内置算法，零第三方依赖。
 *
 * 信封格式 `AGM1:<b64salt>:<b64iv>:<b64ct>`：
 *  - `AGM1` 为版本标识（AGM = Apex Guru Manager，1 = 首版），为将来算法
 *    升级（如 Argon2 / 更高迭代数）留位 —— 解密端按前缀路由，旧文件永远
 *    可解；
 *  - salt 16 字节、IV 12 字节，每次加密均由 [SecureRandom] 现场随机生成
 *    （同口令同明文两次导出产出不同密文，杜绝重放比对）；
 *  - 密钥经 PBKDF2WithHmacSHA256（210_000 迭代，256 位）从用户口令派生 ——
 *    离线小字典 / 暴力破解的防护**完全依赖口令强度**（见 [encrypt] KDoc）；
 *  - GCM 认证标签 128 位：篡改信封任何一字节（含 salt/IV 段）解密即失败。
 */
internal object BackupCrypto {

    private const val PBKDF2_ITERATIONS = 210_000
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val GCM_TAG_BITS = 128
    private const val TRANSFORM = "AES/GCM/NoPadding"
    internal const val ENVELOPE_PREFIX = "AGM1"

    /** 共享随机源（[SecureRandom] 线程安全；每次调用自取新熵）。 */
    private val random = SecureRandom()

    /**
     * 从口令派生 256 位 AES 密钥（PBKDF2WithHmacSHA256，210_000 迭代）。
     *
     * [PBEKeySpec] 用毕即 [PBEKeySpec.clearPassword] 清零内部口令副本，
     * 缩短明文口令在堆上的驻留窗口（调用方持有的 CharArray 由调用方负责）。
     */
    fun deriveKey(passphrase: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, PBKDF2_ITERATIONS, KEY_BITS)
        try {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    /**
     * 加密为 AGM1 信封（随机 salt + IV，AES/GCM）。
     *
     * 仅当设备 JCE 提供者异常时才会失败（AES/GCM 与 PBKDF2WithHmacSHA256
     * 在 minSdk 26+ 全系可用）—— 此时包装为 [IllegalStateException] 抛出，
     * 由调用方（BackupManager.export → ViewModel）转为用户可见错误。
     *
     * 安全边界（诚实声明）：本信封**没有**防离线暴力破解的额外机制 ——
     * PBKDF2 210_000 迭代只是抬高了单次猜测成本，若备份文件外泄，
     * 攻击者可以不限速地离线试口令。口令越弱（常见词 / 短口令）信封越脆，
     * 请使用高强度口令（长随机串优先）。
     */
    fun encrypt(plaintext: String, passphrase: CharArray): String {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val key = deriveKey(passphrase, salt)
        val ciphertext = runCatching {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        }.getOrElse { e ->
            throw IllegalStateException("AES/GCM encryption failed", e)
        }
        return buildString {
            append(ENVELOPE_PREFIX).append(':')
            append(Base64.encodeToString(salt, Base64.NO_WRAP)).append(':')
            append(Base64.encodeToString(iv, Base64.NO_WRAP)).append(':')
            append(Base64.encodeToString(ciphertext, Base64.NO_WRAP))
        }
    }

    /**
     * 解析并解密 AGM1 信封。**任何失败一律返回 null，绝不抛异常**：
     * 口令错误（AEADBadTagException）、信封损坏 / 段数不对、Base64 非法、
     * 未知版本前缀统一归为 null —— 调用方无需区分「口令错」与「文件坏」，
     * 统一提示「口令错误或数据损坏」即可（区分二者反而泄露可用信息）。
     */
    fun decrypt(envelope: String, passphrase: CharArray): String? = runCatching {
        val parts = envelope.split(':')
        require(parts.size == 4) { "malformed envelope: ${parts.size} segments" }
        require(parts[0] == ENVELOPE_PREFIX) { "unknown envelope version: ${parts[0]}" }
        val salt = Base64.decode(parts[1], Base64.NO_WRAP)
        val iv = Base64.decode(parts[2], Base64.NO_WRAP)
        val ciphertext = Base64.decode(parts[3], Base64.NO_WRAP)
        require(salt.isNotEmpty() && iv.size == IV_BYTES) { "bad salt/iv length" }
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(GCM_TAG_BITS, iv))
        String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }.getOrNull()
}

// ─────────────────────────────────────────────────────────────────────────────
// 备份数据模型
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 整份备份文件的最外层结构（JSON 落盘格式即本类序列化结果）。
 *
 * 版本兼容语义：`formatVersion` 1 = 当前格式。**读端宽容（ignoreUnknownKeys）、
 * 写端只认当前版** —— 旧版本 App 读到新格式时由 import 侧的
 * `formatVersion > 1` 守卫明确拒绝（而非半懂不懂地恢复一半）；
 * 新版本 App 读旧文件永远安全（缺字段走默认值）。向后只读不破坏。
 */
@Serializable
data class BackupBundle(
    val formatVersion: Int = 1,
    /** 备份生成时刻（epoch ms）。 */
    val createdAt: Long,
    val appVersionName: String,
    val appVersionCode: Int,
    /**
     * 设置中心 4 键的原样 JSON 快照（key = SP 键名，value = 该键的 JSON 字符串）。
     * `model_providers_v2` 的 value 在导出时已脱敏（apiKeys → 空数组）。
     */
    val settingsJson: Map<String, String> = emptyMap(),
    /** Vault 的 AGM1 加密信封；未包含 Vault 时为 null。 */
    val vaultEnvelope: String? = null,
    /** 导出时 Vault 内的条目数（预览展示用；解密前不可知具体内容）。 */
    val vaultEntryCount: Int = 0,
    /** 本次导出脱敏删除的 Provider API Key 个数（安全透明度指标）。 */
    val redactedApiKeyCount: Int = 0,
)

/**
 * 导出选项。
 *
 * 注意：`vaultPassphrase` 含敏感数据 —— equals/hashCode 沿用 data class
 * 默认实现（对 CharArray 按引用比较而非内容比较；**本类有意不重写**，
 * 避免把口令内容卷入 hashCode 缓存 / 相等性比较的散布面）；用毕清零
 * （`fill('\u0000')`）由调用方（ViewModel）负责。
 */
data class ExportOptions(
    /** 包含 Vault 时必须提供非空 [vaultPassphrase]，否则 [BackupManager.export] 抛 [IllegalArgumentException]。 */
    val includeVault: Boolean = false,
    val vaultPassphrase: CharArray? = null,
)

/** 导入前预览（只含元信息，不含任何可恢复内容的明文）。 */
data class ImportPreview(
    val formatVersion: Int,
    val createdAt: Long,
    val appVersionName: String,
    val vaultIncluded: Boolean,
    val settingsIncluded: Boolean,
)

/**
 * 导入结果。errors 为机器可读错误码（见 [BackupManager] companion 的
 * `ERR_*` 常量，部分附 `:<上下文>` 后缀），由 ViewModel 映射为本地化文案。
 */
data class ImportResult(
    val settingsImported: Boolean,
    val vaultImported: Boolean,
    val errors: List<String>,
)

// ─────────────────────────────────────────────────────────────────────────────
// 备份管理器
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 备份 / 恢复编排器。
 *
 * ## 敏感数据脱敏铁律（最高优先级契约）
 *
 * **备份文件默认不含任何明文密钥 —— 没有例外，没有开关。**
 *
 *  - Provider 的 `apiKeys`：导出时对 `model_providers_v2` 的 JSON 先
 *    [Json.parseToJsonElement] 解析，把每个 provider 元素的 `apiKeys` 数组
 *    **强制替换为空数组**并统计被删 Key 数（写入 [BackupBundle.redactedApiKeyCount]，
 *    供用户核对）；解析失败则该键整体跳过并记 error（AppLogger）。apiKeys
 *    归属 EncryptedSharedPreferences（SettingsRepository.securePrefs），由
 *    用户在新机重新录入 —— 备份不是密钥搬运工具。
 *  - Vault 密钥：只以 [BackupCrypto] 口令信封形式导出（信封内容 =
 *    [VaultJson] 编码的 VaultEntry 数组，与存储层格式逐字一致）；导入
 *    解密后逐条经 [VaultRepository.save] 合入 —— 内存快照 / 加密盘 /
 *    脱敏登记表三者同步更新，无需重启即生效。
 *  - 防御性双保险：**导入写回 SP 前对 providers 键再做一次同样的脱敏** ——
 *    不信任文件内容，哪怕它不是本 App 导出的。
 *
 * ## SharedPreferences 契约（有意共享）
 *
 * `context.getSharedPreferences("apex_settings", MODE_PRIVATE)` 与
 * [com.apex.agent.ui.screen.settings.SettingsRepository] 同名同键
 * （model_profiles_v2 / model_providers_v2 / model_roles_v2 /
 * agent_settings_v2）—— 这是本类有意依赖的存储契约而非私有实现细节；
 * 键名变更必须两侧同步。注意：SettingsRepository 的内存 StateFlow 在
 * 进程启动时加载，本类导入写回 SP **不会热更新其内存态** —— 设置项恢复
 * 需重启应用完全生效（由 UI 层明示用户）。
 *
 * ## 线程模型
 *
 * 所有公开方法均为**同步阻塞 IO**（SP 读写 / SAF 流 / 密钥派生），方法
 * 内部不挂起也不切线程 —— 调用方（ViewModel）负责 Dispatchers.IO。
 *
 * @param vaultRepository 金库仓库 —— 有意不直接注入 VaultStore 接口：仓库
 *   无 `@Binds` 绑定（ToolModule 手动构造 store 注入 repository），直接注入
 *   接口会在 Hilt 图校验时报错；且绕过 repository 的内存快照写 raw store
 *   会在下次 vault 写入时被陈旧快照覆盖。经 repository 走 save() 同步内存
 *   与磁盘，业务层边界也更正确（VaultStore KDoc 自述）。
 */
@Singleton
class BackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val vaultRepository: VaultRepository,
) {
    /** 与 SettingsRepository 同名同键的 SP 访问点（契约见类 KDoc）。 */
    private val prefs: SharedPreferences =
        context.getSharedPreferences(SETTINGS_PREFS_NAME, Context.MODE_PRIVATE)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    // ── 导出 ───────────────────────────────────────────────────

    /**
     * 组装完整备份。设置项始终包含（4 键原样搬运，providers 键脱敏后放行）；
     * Vault 按 [options] 选择。
     *
     * @throws IllegalArgumentException includeVault=true 且口令为空 ——
     *   调用方（ViewModel）应转译为用户可读提示；UI 层已在按钮禁用态兜底，
     *   此异常是防御性第二道闸。
     */
    fun export(options: ExportOptions): BackupBundle {
        if (options.includeVault &&
            (options.vaultPassphrase == null || options.vaultPassphrase.isEmpty())
        ) {
            throw IllegalArgumentException("vault passphrase required when includeVault=true")
        }
        val settings = mutableMapOf<String, String>()
        var redactedCount = 0
        for (key in SETTINGS_KEYS) {
            val raw = prefs.getString(key, null) ?: continue
            if (key != KEY_PROVIDERS) {
                settings[key] = raw
                continue
            }
            val redacted = redactProviderApiKeys(raw)
            if (redacted == null) {
                // 解析失败：该键整体跳过（宁缺勿泄），记 error 供用户在日志页追溯
                AppLogger.instance.warn(
                    category = LogCategory.SYSTEM,
                    source = "BackupManager",
                    message = "providers key skipped in export: unparseable JSON",
                )
            } else {
                settings[key] = redacted.first
                redactedCount += redacted.second
            }
        }
        var vaultEnvelope: String? = null
        var vaultEntryCount = 0
        if (options.includeVault) {
            // entriesFlow 快照 = 存储层最新内容（repository 每次写都同步落盘）。
            // 逐字复用存储层格式（VaultJson + VaultEntryListSerializer），
            // 导入端按同一格式解回 —— 不引入第二套 vault 序列化。
            val raw = VaultJson.encodeToString(VaultEntryListSerializer, vaultRepository.entriesFlow.value)
            vaultEnvelope = BackupCrypto.encrypt(raw, requireNotNull(options.vaultPassphrase))
            vaultEntryCount = vaultRepository.entryCount()
        }
        return BackupBundle(
            formatVersion = CURRENT_FORMAT_VERSION,
            createdAt = System.currentTimeMillis(),
            appVersionName = BuildConfig.VERSION_NAME,
            appVersionCode = BuildConfig.VERSION_CODE,
            settingsJson = settings,
            vaultEnvelope = vaultEnvelope,
            vaultEntryCount = vaultEntryCount,
            redactedApiKeyCount = redactedCount,
        )
    }

    /** 导出并序列化为 JSON 字符串（[export] 的直通封装）。 */
    fun exportToString(options: ExportOptions): String =
        json.encodeToString(BackupBundle.serializer(), export(options))

    /**
     * 导出并写入 SAF 目标（contentResolver.openOutputStream → 缓冲写）。
     * 任何失败（流关闭 / 加密异常 / 口令缺失）归一为 Result.failure，
     * 异常 message 由 ViewModel 转用户提示。
     */
    fun writeBundleToUri(uri: Uri, options: ExportOptions): Result<Unit> = runCatching {
        val text = exportToString(options)
        val stream = context.contentResolver.openOutputStream(uri, "wt")
            ?: throw IOException("openOutputStream returned null: $uri")
        stream.use { it.writer(Charsets.UTF_8).buffered().use { w -> w.write(text) } }
    }

    // ── 导入（预览 / 读取）─────────────────────────────────────

    /** 从 SAF 目标读出备份文件全文（含损坏 / 非备份文件，解析交给 [parsePreview]）。 */
    fun readBundleFromUri(uri: Uri): String? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            input.reader(Charsets.UTF_8).buffered().readText()
        }
    }.getOrNull()

    /**
     * 只解析元信息生成预览（formatVersion / createdAt / 来源版本 /
     * 是否含 Vault / 是否含设置）。任何解析失败返回 null —— 预览阶段绝不
     * 触碰恢复逻辑。
     */
    fun parsePreview(input: String): ImportPreview? = runCatching {
        val bundle = json.decodeFromString(BackupBundle.serializer(), input)
        ImportPreview(
            formatVersion = bundle.formatVersion,
            createdAt = bundle.createdAt,
            appVersionName = bundle.appVersionName,
            vaultIncluded = bundle.vaultEnvelope != null,
            settingsIncluded = bundle.settingsJson.isNotEmpty(),
        )
    }.getOrNull()

    // ── 导入（执行）────────────────────────────────────────────

    /**
     * 执行恢复。逐项隔离失败：**单条 runCatching 收集 errors，绝不中断整批**
     * （一项损坏不该拖掉设置恢复）。
     *
     *  - formatVersion > 1 直接拒绝（读到不认识的格式宁可不动本机数据）；
     *  - settings：仅写回 4 个已知键中的非空值，providers 键写前**再脱敏一次**
     *    （防御性处理，不信任文件内容）；本机已有的键值被覆盖 —— 恢复语义
     *    即「以备份为准」；
     *  - vault：信封解密成功才入库；**合并语义（upsert）** —— 解密后逐条
     *    [VaultRepository.save]（同 id / 同 label 合入，本机独有的条目保留），
     *    内存快照 + 加密盘 + 脱敏登记表同步更新，无需重启即生效；口令错误 /
     *    信封损坏统一记 [ERR_VAULT_DECRYPT_FAILED]（见 [BackupCrypto.decrypt]
     *    的安全考量）。合并而非整库替换是有意的保守选择：恢复操作绝不
     *    清空本机现有密钥。
     *
     * @param vaultPassphrase 仅在恢复 Vault 时使用；用毕清零由调用方负责。
     */
    fun import(
        json: String,
        restoreSettings: Boolean,
        restoreVault: Boolean,
        vaultPassphrase: CharArray?,
    ): ImportResult {
        val bundle = runCatching {
            this.json.decodeFromString(BackupBundle.serializer(), json)
        }.getOrElse {
            return ImportResult(false, false, listOf(ERR_BUNDLE_UNREADABLE))
        }
        if (bundle.formatVersion > CURRENT_FORMAT_VERSION) {
            return ImportResult(false, false, listOf(ERR_VERSION_TOO_NEW))
        }
        val errors = mutableListOf<String>()
        var settingsImported = false
        var vaultImported = false

        if (restoreSettings) {
            for (key in SETTINGS_KEYS) {
                val value = bundle.settingsJson[key]?.takeIf { it.isNotBlank() } ?: continue
                val safe = if (key == KEY_PROVIDERS) {
                    val redacted = redactProviderApiKeys(value)
                    if (redacted == null) {
                        errors += ERR_PROVIDERS_REDACT_FAILED
                        continue
                    }
                    redacted.first
                } else {
                    value
                }
                runCatching { prefs.edit().putString(key, safe).apply() }
                    .onSuccess { settingsImported = true }
                    .onFailure { errors += "$ERR_SETTINGS_WRITE_FAILED:$key" }
            }
        }

        if (restoreVault && bundle.vaultEnvelope != null) {
            if (vaultPassphrase == null || vaultPassphrase.isEmpty()) {
                errors += ERR_VAULT_PASSPHRASE_MISSING
            } else {
                val entries = runCatching {
                    val plain = BackupCrypto.decrypt(bundle.vaultEnvelope, vaultPassphrase)
                        ?: return@runCatching null
                    VaultJson.decodeFromString(VaultEntryListSerializer, plain)
                }.getOrNull()
                if (entries == null) {
                    errors += ERR_VAULT_DECRYPT_FAILED
                } else {
                    val failed = entries.count { entry ->
                        runCatching { vaultRepository.save(entry) }.isFailure
                    }
                    if (failed > 0) errors += ERR_VAULT_WRITE_FAILED
                    vaultImported = failed < entries.size
                }
            }
        }

        return ImportResult(settingsImported, vaultImported, errors)
    }

    // ── 内部工具 ───────────────────────────────────────────────

    /**
     * providers JSON 脱敏：每个 provider 元素的 `apiKeys` 数组替换为空数组，
     * 返回 (脱敏后 JSON, 被删 Key 数)；解析失败 / 结构非法返回 null（调用方
     * 按整体跳过处理）。其余字段逐字保留 —— 往返保真。
     */
    private fun redactProviderApiKeys(raw: String): Pair<String, Int>? = runCatching {
        val arr = json.parseToJsonElement(raw) as? JsonArray ?: return null
        var removed = 0
        val redacted = arr.map { element ->
            val obj = element as? JsonObject ?: return@map element
            val keys = obj[KEY_API_KEYS_FIELD] as? JsonArray
            if (keys.isNullOrEmpty()) {
                obj
            } else {
                removed += keys.size
                JsonObject(obj.toMutableMap().apply { put(KEY_API_KEYS_FIELD, JsonArray(emptyList())) })
            }
        }
        JsonArray(redacted).toString() to removed
    }.getOrNull()

    companion object {
        /** 当前备份格式版本（1 = 首版；递增必须保持旧文件可读）。 */
        const val CURRENT_FORMAT_VERSION = 1

        /** 与 SettingsRepository 共享的 SP 名（契约见类 KDoc）。 */
        const val SETTINGS_PREFS_NAME = "apex_settings"
        const val KEY_PROFILES = "model_profiles_v2"
        const val KEY_PROVIDERS = "model_providers_v2"
        const val KEY_ROLES = "model_roles_v2"
        const val KEY_AGENT = "agent_settings_v2"

        /** Provider JSON 内 apiKeys 字段名（脱敏目标；与 ProviderConfig.apiKeys 序列化名一致）。 */
        private const val KEY_API_KEYS_FIELD = "apiKeys"

        /** 恢复时只信任这 4 个已知键（settingsJson 中的外来键一律忽略）。 */
        val SETTINGS_KEYS = listOf(KEY_PROFILES, KEY_PROVIDERS, KEY_ROLES, KEY_AGENT)

        // ── 导入错误码（稳定契约，ViewModel 依此映射本地化文案）──
        const val ERR_VERSION_TOO_NEW = "version_too_new"
        const val ERR_BUNDLE_UNREADABLE = "bundle_unreadable"
        const val ERR_VAULT_PASSPHRASE_MISSING = "vault_passphrase_missing"
        const val ERR_VAULT_DECRYPT_FAILED = "vault_decrypt_failed"
        const val ERR_VAULT_WRITE_FAILED = "vault_write_failed"
        const val ERR_PROVIDERS_REDACT_FAILED = "providers_redact_failed"
        const val ERR_SETTINGS_WRITE_FAILED = "settings_write_failed"
    }
}
