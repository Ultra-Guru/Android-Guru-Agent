package com.apex.agent.tools

import java.io.File
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * ═══ DCP 确认令牌签名器（S04）═══
 *
 * `fingerprint / confirm_token` 的 HMAC-SHA256 签名源。密钥随机生成一次、
 * 持久化在 **app 私有目录**（filesDir/dcp/keys/token.key，应用沙箱私有，
 * 其他进程不可读），进程重启后令牌仍可验证。
 *
 * 密钥不进 git / 不进 assets；文件损坏或首次启动时重新生成（旧令牌全部
 * 失效 —— 按过期处理，诚实拒绝而非误判）。
 */
class DeviceTokenSigner(private val keyFile: File) {

    private val key: ByteArray by lazy { loadOrCreate() }

    /** HMAC-SHA256(payload, key) 的十六进制摘要。 */
    fun sign(payload: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(payload.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun loadOrCreate(): ByteArray {
        if (keyFile.isFile) {
            val existing = runCatching { keyFile.readBytes() }.getOrNull()
            if (existing != null && existing.size >= 32) return existing
        }
        val fresh = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        runCatching {
            keyFile.parentFile?.mkdirs()
            // 原子写纪律：tmp + renameTo，rename 失败直写目标兜底。
            val tmp = File(keyFile.parentFile, keyFile.name + ".tmp")
            tmp.writeBytes(fresh)
            if (!tmp.renameTo(keyFile)) keyFile.writeBytes(fresh)
            tmp.delete()
        }
        return fresh
    }
}
