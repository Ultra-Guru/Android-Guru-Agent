package com.apex.agent.update

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * [CheckMirror.fetchVerified] 单元测试 —— S-02 受信清单拉取协议。
 *
 * 覆盖 DoD 四类场景（传输层注入内存 fake，验签器注入测试密钥对 ——
 * 协议逻辑与真实网络 / 真实密钥解耦）：
 * - **无签名**：过渡期内按旧 schema 放行（LegacyUnsigned）；过渡期后
 *   fail-closed 抛 [ManifestTrustException]；
 * - **签名无效**：全链 base64 形态合法但验签不过 → 硬拒绝（绝不放行）；
 * - **镜像 URL 被劫持**：镜像候选返回被篡改的清单（携攻击者自签名）——
 *   绝不返回 Verified：直连候选干净则取直连内容；直连同样被劫持则硬拒绝；
 * - **字段被篡改**：篡改后的清单哪怕带形态合法的签名也进不了 Verified
 *   （验签覆盖原始字节，见 [UpdateTrustTest]）；
 * - 坏镜像伪 200（HTML 挑战页冒充 sidecar）按「无签名」处理，不误判篡改；
 * - 全链网络失败保持既有语义（抛最后一次网络异常）。
 */
class CheckMirrorTrustTest {

    private val rawUrl =
        "https://raw.githubusercontent.com/Ultra-Guru/Android-Guru-Agent-Release/main/version.json"

    /** 发布者测试密钥对（正路签名）。 */
    private val publisherKey = KeyPairGenerator.getInstance("EC")
        .apply { initialize(ECGenParameterSpec("secp256r1")) }
        .genKeyPair()

    /** 攻击者密钥对（伪造签名 —— 篡改场景专用）。 */
    private val attackerKey = KeyPairGenerator.getInstance("EC")
        .apply { initialize(ECGenParameterSpec("secp256r1")) }
        .genKeyPair()

    private val goodManifest = """{"versionName":"1.5.45","versionCode":545}"""

    /** 过渡期内时钟（真实 now 早于编译期截止点，这里显式钉死避免时间旅行脆弱）。 */
    private val withinGrace = UpdateTrust.UNSIGNED_GRACE_UNTIL_EPOCH_MS - 1

    /** 过渡期后时钟。 */
    private val pastGrace = UpdateTrust.UNSIGNED_GRACE_UNTIL_EPOCH_MS

    private fun sign(payload: String, keyPair: java.security.KeyPair): String {
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(keyPair.private)
        signer.update(payload.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(signer.sign())
    }

    /** 以发布者密钥验签（fetchVerified 的注入验签器）。 */
    private val publisherVerifier: (ByteArray, String) -> Boolean =
        { payload, sig ->
            runCatching {
                val verifier = Signature.getInstance("SHA256withECDSA")
                verifier.initVerify(publisherKey.public)
                verifier.update(payload)
                verifier.verify(Base64.getDecoder().decode(sig.trim()))
            }.getOrDefault(false)
        }

    /** 内存传输层：URL → 正文；缺省（不在表中）→ 404 语义抛 IOException。 */
    private fun fakeTransport(responses: Map<String, String>):
        suspend (String, Boolean) -> String = { url, _ ->
            responses[url] ?: throw IOException("HTTP 404 $url")
        }

    private val directUrl = rawUrl

    // ── 验签正路 ────────────────────────────────────────────────────────

    @Test
    fun `signed manifest from direct candidate verifies`() = runTest {
        val transport = fakeTransport(
            mapOf(
                directUrl to goodManifest,
                "$directUrl.sig" to sign(goodManifest, publisherKey)
            )
        )
        val result = CheckMirror.fetchVerified(
            rawUrl, nowEpochMs = withinGrace, fetcher = transport,
            verifier = publisherVerifier
        )
        assertTrue(result is TrustedManifest.Verified)
        assertEquals(goodManifest, (result as TrustedManifest.Verified).text)
    }

    @Test
    fun `signed manifest from mirror candidate verifies identically`() = runTest {
        // 镜像候选改写前后同一签名都校验通过 —— 镜像只是加速通道
        val mirrorUrl = DownloadMirror.NODES.first().prefix + rawUrl
        val transport = fakeTransport(
            mapOf(
                mirrorUrl to goodManifest,
                "$mirrorUrl.sig" to sign(goodManifest, publisherKey)
            )
        )
        val result = CheckMirror.fetchVerified(
            rawUrl, nowEpochMs = withinGrace, fetcher = transport,
            verifier = publisherVerifier
        )
        assertTrue(result is TrustedManifest.Verified)
    }

    // ── 无签名（DoD：清单缺签名字段时拒绝）──────────────────────────────

    @Test
    fun `unsigned manifest within grace falls back to legacy`() = runTest {
        val transport = fakeTransport(mapOf(directUrl to goodManifest))
        val result = CheckMirror.fetchVerified(
            rawUrl, nowEpochMs = withinGrace, fetcher = transport,
            verifier = publisherVerifier
        )
        assertTrue(result is TrustedManifest.LegacyUnsigned)
        assertEquals(goodManifest, (result as TrustedManifest.LegacyUnsigned).text)
    }

    @Test
    fun `unsigned manifest past grace is rejected`() = runTest {
        val transport = fakeTransport(mapOf(directUrl to goodManifest))
        try {
            CheckMirror.fetchVerified(
                rawUrl, nowEpochMs = pastGrace, fetcher = transport,
                verifier = publisherVerifier
            )
            throw AssertionError("过渡期后无签名清单必须 fail-closed")
        } catch (expected: ManifestTrustException) {
            assertTrue(expected.reason.contains("缺少发布者签名"))
        }
    }

    // ── 签名无效（DoD：签名无效 / 字段被篡改）──────────────────────────

    @Test
    fun `invalid signature on all candidates hard-rejects`() = runTest {
        // 攻击者全链劫持：篡改字段（下载地址指向 evil）+ 自签名冒充发布者
        val tampered = """{"versionName":"1.5.45","versionCode":545,"download":{"url":"http://evil.example/apk"}}"""
        val responses = CheckMirror.candidates(rawUrl).associateWith { tampered } +
            CheckMirror.candidates(rawUrl).associate { "$it.sig" to sign(tampered, attackerKey) }
        try {
            CheckMirror.fetchVerified(
                rawUrl, nowEpochMs = withinGrace, fetcher = fakeTransport(responses),
                verifier = publisherVerifier
            )
            throw AssertionError("攻击者签名（验签不过）绝不能进入 Verified")
        } catch (expected: ManifestTrustException) {
            assertTrue(expected.reason.contains("疑似被篡改"))
        }
    }

    // ── 镜像 URL 被劫持（DoD 第四类）───────────────────────────────────

    @Test
    fun `hijacked mirror falls through to clean direct candidate`() = runTest {
        // 镜像候选被劫持：篡改清单 + 攻击者签名；直连候选干净带合法签名
        val mirrorUrl = DownloadMirror.NODES.first().prefix + rawUrl
        val hijacked = """{"versionName":"9.9.9","versionCode":999}"""
        val transport = fakeTransport(
            mapOf(
                mirrorUrl to hijacked,
                "$mirrorUrl.sig" to sign(hijacked, attackerKey),
                directUrl to goodManifest,
                "$directUrl.sig" to sign(goodManifest, publisherKey)
            )
        )
        val result = CheckMirror.fetchVerified(
            rawUrl, nowEpochMs = withinGrace, fetcher = transport,
            verifier = publisherVerifier
        )
        // 坏镜像降级不截断回退链；最终内容以直连候选为准（未被污染）
        assertTrue(result is TrustedManifest.Verified)
        assertEquals(goodManifest, (result as TrustedManifest.Verified).text)
    }

    @Test
    fun `hijacked mirror with unsigned direct hard-rejects even in grace`() = runTest {
        // 现实过渡期场景：镜像被劫持返回坏签名（篡改证据），直连真实但尚未
        // 发布签名 —— 篡改证据优先级高于过渡期放行：链上见过坏签名即硬拒绝，
        // 绝不退回「无签名按旧 schema 放行」（spec：任何「无签名放行」都
        // 不适用于已检出篡改的链）
        val mirrorUrl = DownloadMirror.NODES.first().prefix + rawUrl
        val hijacked = """{"versionName":"9.9.9","versionCode":999}"""
        val transport = fakeTransport(
            mapOf(
                mirrorUrl to hijacked,
                "$mirrorUrl.sig" to sign(hijacked, attackerKey),
                directUrl to goodManifest
            )
        )
        try {
            CheckMirror.fetchVerified(
                rawUrl, nowEpochMs = withinGrace, fetcher = transport,
                verifier = publisherVerifier
            )
            throw AssertionError("见过坏签名的链必须硬拒绝，即使过渡期内有无签名候选")
        } catch (expected: ManifestTrustException) {
            assertTrue(expected.reason.contains("疑似被篡改"))
        }
    }

    // ── 坏镜像的挑战页防御 ──────────────────────────────────────────────

    @Test
    fun `html challenge page as sidecar is treated as missing not tampering`() = runTest {
        val transport = fakeTransport(
            mapOf(
                directUrl to goodManifest,
                "$directUrl.sig" to "<html>challenge page</html>"
            )
        )
        val result = CheckMirror.fetchVerified(
            rawUrl, nowEpochMs = withinGrace, fetcher = transport,
            verifier = publisherVerifier
        )
        // 挑战页形态非法 → 按「无签名」处理（过渡期放行），不误判为篡改硬拒绝
        assertTrue(result is TrustedManifest.LegacyUnsigned)
    }

    // ── 全链网络失败（既有语义保持）────────────────────────────────────

    @Test
    fun `all endpoints down throws last network error`() = runTest {
        try {
            CheckMirror.fetchVerified(
                rawUrl, nowEpochMs = withinGrace,
                fetcher = fakeTransport(emptyMap()),
                verifier = publisherVerifier
            )
            throw AssertionError("全链失败必须抛网络异常")
        } catch (expected: IOException) {
            // 与 fetch 同语义：最后一次（直连）的网络异常上抛
        }
    }
}
