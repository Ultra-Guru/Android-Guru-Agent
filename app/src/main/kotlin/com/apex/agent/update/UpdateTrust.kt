package com.apex.agent.update

import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * ═══════════════════════════════════════════════════════════════════════════
 *  UpdateTrust —— 更新链路离线信任锚（S-02）
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * 问题背景：v1.5 之前远端清单（version.json / patches.json）的真伪完全取决于
 * 传输通道 —— 清单里的 sha256 / signingCertSha256 都是**自证字段**：篡改者
 * 改完字段后清单照样自洽；UpdateCenter.signatureMismatch 的期望值正取自同一
 * 份待验清单；UpdateDownloader / PatchIndex / HotUpdateEngine 三处「无指纹即
 * 放行」分支更是把「缺指纹」当成了可信信号（镜像劫持 + 清单篡改 = 任意 APK
 * / 任意技能注入直达用户）。
 *
 * 本文件落地信任锚三件套：
 * 1. **内置发布者公钥**（[PUBLISHER_PUBLIC_KEY_B64]）：EC P-256 SPKI，
 *    编译期随 APK 分发、绝不随清单下发 —— 无发布私钥的攻击者无法构造任何
 *    能通过验证的清单；
 * 2. **detached 签名验证**（[verifyDetachedSignature]）：签名覆盖清单的
 *    原始字节（CI 对落盘文件签名后同源分发 sidecar 文件），任何字段（下载
 *    URL / sha256 / signingCertSha256 / 版本号）改动一个字节即验签失败 ——
 *    清单内指纹从此只承担完整性职责，**身份认证归离线公钥**；
 * 3. **过渡期限**（[UNSIGNED_GRACE_UNTIL_EPOCH_MS]）：发布仓库尚未开始
 *    签名的存量清单，在编译期写死的截止点前按旧 schema 放行（WARN 留痕，
 *    见 [legacySchemaAllowed]）；截止点后无签名一律 fail-closed。该常量
 *    不可被远端翻转 —— 它随 APK 发布，翻越它的唯一途径是给用户装新 APK。
 *
 * 签名格式（与 release.yml 的 openssl 签名步骤一一对应）：
 * - 算法：SHA-256 with ECDSA（P-256 / prime256v1）；
 * - 公钥：X.509 SubjectPublicKeyInfo DER，base64 单行；
 * - 签名：DER 编码 ECDSA 签名（openssl dgst -sha256 -sign 原生产物），
 *   base64 单行，sidecar 文件与清单同源（清单 URL + `.sig` 后缀）。
 *
 * 纯 JCA 实现、零 Android API —— app/src/test 直接单测。
 */
object UpdateTrust {

    /**
     * 内置发布者公钥（EC P-256 SPKI DER → base64）。
     *
     * 私钥只存在于发布流水线的 CI secret（UPDATE_MANIFEST_SIGNING_KEY），
     * 绝不入库、绝不经清单下发。轮换流程：新公钥随 App 版本发布 → 旧公钥
     * 保留在 [PUBLISHER_LEGACY_PUBLIC_KEYS_B64] 过渡一代 → 下代移除。
     */
    const val PUBLISHER_PUBLIC_KEY_B64 =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEd5kst2haDiygmfMiekGQ0/cuqwXfzywBxHXJYvhFPOEx7LLs0h+kixk+uC2axFrA3j55Txnl6dHrULGm7MAzqg=="

    /**
     * 历史公钥过渡位（当前为空）—— 公钥轮换时把旧公钥挪入此处保留一代，
     * 让已签名未消费的清单在升级客户端上仍可验证，再下一代移除。
     */
    val PUBLISHER_LEGACY_PUBLIC_KEYS_B64: List<String> = emptyList()

    /**
     * 无签名清单 / 无指纹资产的过渡截止点：2027-03-01T00:00:00Z（epoch ms）。
     *
     * 取值依据：本 PR 合入后发布流水线即开始签名（release.yml），一个季度
     * 足以覆盖正常发版节奏；到期后老客户端收不收得到签名清单与它无关 ——
     * 新客户端自此 fail-closed。**编译期常量，不可被远端配置翻转。**
     */
    const val UNSIGNED_GRACE_UNTIL_EPOCH_MS = 1_803_859_200_000L

    /**
     * 过渡期内？（true = 旧 schema「无签名 / 无指纹」内容按旧行为放行并
     * WARN 留痕；false = 一律 fail-closed）。过期不可逆。
     */
    fun legacySchemaAllowed(nowEpochMs: Long = System.currentTimeMillis()): Boolean =
        nowEpochMs < UNSIGNED_GRACE_UNTIL_EPOCH_MS

    /**
     * detached 签名验证（失败折叠 false + WARN 留痕，绝不抛）。
     *
     * @param payload 清单原始字节（验的是传输层收到的逐字节内容 —— 镜像
     *   改写、CDN 注入、中间人替换都会改变字节从而验签失败）
     * @param signatureB64 base64 单行 DER 签名（sidecar 文件原文）
     * @param publicKeyB64 验签公钥（默认内置发布者公钥；测试可注入自有密钥对）
     */
    fun verifyDetachedSignature(
        payload: ByteArray,
        signatureB64: String,
        publicKeyB64: String = PUBLISHER_PUBLIC_KEY_B64
    ): Boolean {
        val signature = decodeBase64OrNull(signatureB64)
            ?: return malformed("签名 base64 解码失败")
        val key = decodePublicKeyOrNull(publicKeyB64)
            ?: return malformed("验签公钥解析失败")
        return try {
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(key)
            verifier.update(payload)
            verifier.verify(signature)
        } catch (e: Exception) {
            AppLogger.instance.warn(
                LogCategory.SYSTEM, "UpdateTrust", "签名验证异常：${e.message}"
            )
            false
        }
    }

    /**
     * 对内置公钥（当前 + 历史）逐一验签 —— 公钥轮换过渡期的兼容入口。
     * 任一通过即通过。
     */
    fun verifyWithPublisherKeys(payload: ByteArray, signatureB64: String): Boolean =
        verifyDetachedSignature(payload, signatureB64, PUBLISHER_PUBLIC_KEY_B64) ||
            PUBLISHER_LEGACY_PUBLIC_KEYS_B64.any {
                verifyDetachedSignature(payload, signatureB64, it)
            }

    /**
     * base64 签名形态预检：挑战页 / HTML 错误页冒充 sidecar 时返回 false，
     * 让调用方按「无签名」而非「坏签名」处理（不误判篡改）。
     *
     * P-256 ECDSA DER 签名 ≈ 70~72 字节 → base64 ≈ 96 字符；下限放宽到
     * 48 字符兼容更短签名形态，上限 1024 防御超长垃圾体。
     */
    fun looksLikeBase64Signature(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.length !in 48..1024) return false
        return trimmed.all { char ->
            char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' ||
                char == '+' || char == '/' || char == '='
        }
    }

    /** 解析 SPKI 公钥（形态问题折叠 null，不抛）。 */
    fun decodePublicKeyOrNull(publicKeyB64: String): PublicKey? = try {
        val der = Base64.getDecoder().decode(publicKeyB64.trim())
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der))
    } catch (e: Exception) {
        AppLogger.instance.warn(
            LogCategory.SYSTEM, "UpdateTrust", "公钥解析失败：${e.message}"
        )
        null
    }

    private fun decodeBase64OrNull(text: String): ByteArray? = try {
        Base64.getDecoder().decode(text.trim())
    } catch (e: Exception) {
        null
    }

    private fun malformed(detail: String): Boolean {
        AppLogger.instance.warn(
            LogCategory.SYSTEM, "UpdateTrust", "签名形态非法（按验证失败处理）：$detail"
        )
        return false
    }
}

/** 清单信任硬失败 —— fail-closed 的唯一出口，[reason] 面向用户展示。 */
class ManifestTrustException(val reason: String) : Exception(reason)

/**
 * 受信清单原文 —— [CheckMirror.fetchVerified] 的返回契约。
 *
 * - [Verified]：detached 签名验证通过，内容逐字节可信；
 * - [LegacyUnsigned]：过渡期内（编译期截止点前）无签名清单，按旧 schema
 *   放行 —— 调用方按既有流程消费，但资产指纹缺失时的兜底策略仍走
 *   [UpdateTrust.legacySchemaAllowed] 判定（到期自动收紧）。
 */
sealed interface TrustedManifest {

    /** 清单原文（两种形态同构携带 —— 调用方无需分流取正文）。 */
    val text: String

    /** 签名验证通过的清单原文。 */
    data class Verified(override val text: String) : TrustedManifest

    /** 过渡期内放行的无签名清单原文（WARN 留痕；到期后此形态不再出现）。 */
    data class LegacyUnsigned(override val text: String) : TrustedManifest
}
