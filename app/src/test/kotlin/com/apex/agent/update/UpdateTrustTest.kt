package com.apex.agent.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * [UpdateTrust] 单元测试 —— S-02 离线信任锚的核心契约。
 *
 * 覆盖（DoD 四类里的三类在本文件，镜像劫持类见 [CheckMirrorTrustTest]）：
 * - ECDSA detached 签名验证正路（测试现场生成 P-256 密钥对，与生产
 *   openssl 签名步骤同算法同格式）；
 * - **字段被篡改**：payload 改一个字节即验签失败（签名覆盖原始字节）；
 * - **签名无效**：错钥签的 / 非 base64 / 非 DER 形态一律 false（折叠不抛）；
 * - **无签名**：本层不涉及（fetch 层策略，见 [CheckMirrorTrustTest]）；
 * - **内置公钥常量交叉验证**：CI secret 中私钥对参考 payload 的真实签名
 *   （openssl 产出）必须能被内置公钥验证 —— 钥匙对一致性进 CI，防
 *   「secret 换了 / 常量没换」的漂移；
 * - 过渡期常量边界：截止点前放行、到期即 fail-closed（编译期不可翻转）。
 */
class UpdateTrustTest {

    /** 测试专用 P-256 密钥对（协议逻辑与密钥本身解耦单测）。 */
    private val testKeyPair = KeyPairGenerator.getInstance("EC")
        .apply { initialize(ECGenParameterSpec("secp256r1")) }
        .genKeyPair()

    private val testPublicKeyB64: String =
        Base64.getEncoder().encodeToString(testKeyPair.public.encoded)

    private fun sign(payload: ByteArray, keyPair: java.security.KeyPair = testKeyPair): String {
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(keyPair.private)
        signer.update(payload)
        return Base64.getEncoder().encodeToString(signer.sign())
    }

    private val payload = """{"versionName":"1.5.45","versionCode":545}"""
        .toByteArray(Charsets.UTF_8)

    // ── 验签正路与篡改 ──────────────────────────────────────────────────

    @Test
    fun `generated keypair signature verifies`() {
        val signature = sign(payload)
        assertTrue(
            UpdateTrust.verifyDetachedSignature(payload, signature, testPublicKeyB64)
        )
    }

    @Test
    fun `tampered payload field fails verification`() {
        val signature = sign(payload)
        // DoD「字段被篡改」：versionCode 545 → 546，单字节差异
        val tampered = """{"versionName":"1.5.45","versionCode":546}"""
            .toByteArray(Charsets.UTF_8)
        assertFalse(
            UpdateTrust.verifyDetachedSignature(tampered, signature, testPublicKeyB64)
        )
        // 下载 URL / sha256 / signingCertSha256 字段同理 —— 签名覆盖原始字节
        val tamperedUrl = """{"versionName":"1.5.45","versionCode":545,"download":{"url":"http://evil/"}}"""
            .toByteArray(Charsets.UTF_8)
        assertFalse(
            UpdateTrust.verifyDetachedSignature(tamperedUrl, signature, testPublicKeyB64)
        )
    }

    @Test
    fun `signature by wrong key fails`() {
        val otherKeyPair = KeyPairGenerator.getInstance("EC")
            .apply { initialize(ECGenParameterSpec("secp256r1")) }
            .genKeyPair()
        val forged = sign(payload, otherKeyPair)
        assertFalse(
            UpdateTrust.verifyDetachedSignature(payload, forged, testPublicKeyB64)
        )
    }

    @Test
    fun `malformed signature shapes fold to false without throwing`() {
        // 非 base64 文本（镜像挑战页）
        assertFalse(
            UpdateTrust.verifyDetachedSignature(
                payload, "<html>challenge page</html>", testPublicKeyB64
            )
        )
        // 合法 base64 但非 DER 签名
        val notDer = Base64.getEncoder().encodeToString(ByteArray(96) { 0x41 })
        assertFalse(
            UpdateTrust.verifyDetachedSignature(payload, notDer, testPublicKeyB64)
        )
        // 公钥侧形态损坏同样折叠 false
        val signature = sign(payload)
        assertFalse(
            UpdateTrust.verifyDetachedSignature(payload, signature, "not-base64!!")
        )
    }

    // ── 内置公钥常量交叉验证（CI secret ↔ 客户端常量一致性锁）─────────

    @Test
    fun `embedded publisher key parses as EC P-256`() {
        val key = UpdateTrust.decodePublicKeyOrNull(UpdateTrust.PUBLISHER_PUBLIC_KEY_B64)
        assertNotNull("内置公钥常量必须是可解析的 SPKI DER", key)
        assertEquals("EC", key!!.algorithm)
    }

    @Test
    fun `openssl reference signature verifies against embedded key`() {
        // 参考对（payload + openssl dgst -sha256 -sign 产出，与 CI secret 中
        // UPDATE_MANIFEST_SIGNING_KEY 同一私钥）—— 内置公钥若与 secret 漂移
        // （换钥没改常量 / 抄错字符）此测试即红
        val referencePayload =
            """{"versionName":"1.5.45","versionCode":545}""".toByteArray(Charsets.UTF_8)
        val referenceSignature =
            "MEYCIQDkhaROEkVzsSTDMa9vgwggLuu+UzxCq7U01koaSmByqgIhALhbgYbdbEW5HsfnheISzIzRX3XWkAedxiPLj3TUO3+U"
        assertTrue(
            "内置公钥必须能验证 CI 私钥的参考签名",
            UpdateTrust.verifyDetachedSignature(
                referencePayload, referenceSignature, UpdateTrust.PUBLISHER_PUBLIC_KEY_B64
            )
        )
    }

    // ── sidecar 形态预检 ────────────────────────────────────────────────

    @Test
    fun `looksLikeBase64Signature accepts real signatures only`() {
        val signature = sign(payload)
        assertTrue(UpdateTrust.looksLikeBase64Signature(signature))
        assertTrue(UpdateTrust.looksLikeBase64Signature("  $signature \n"))
        // 挑战页 / HTML / 空串 / 超长垃圾体都不是签名形态
        assertFalse(UpdateTrust.looksLikeBase64Signature("<html>blocked</html>"))
        assertFalse(UpdateTrust.looksLikeBase64Signature(""))
        assertFalse(UpdateTrust.looksLikeBase64Signature("a".repeat(2048)))
        assertFalse(UpdateTrust.looksLikeBase64Signature("too-short"))
    }

    // ── 过渡期常量（编译期写死，不可被远端翻转）────────────────────────

    @Test
    fun `legacy schema allowance flips at compile-time deadline`() {
        val deadline = UpdateTrust.UNSIGNED_GRACE_UNTIL_EPOCH_MS
        assertTrue("截止点前一毫秒仍属过渡期", UpdateTrust.legacySchemaAllowed(deadline - 1))
        assertFalse("截止点即 fail-closed", UpdateTrust.legacySchemaAllowed(deadline))
        assertFalse(UpdateTrust.legacySchemaAllowed(deadline + 1))
        // 截止点必须落在「现在」之后 —— 防止合入即全员锁死的回归
        assertTrue(
            "过渡截止点必须在合入时刻之后",
            deadline > 1_780_000_000_000L // 2026-05-29 之后（合入前合理性下界）
        )
    }
}
