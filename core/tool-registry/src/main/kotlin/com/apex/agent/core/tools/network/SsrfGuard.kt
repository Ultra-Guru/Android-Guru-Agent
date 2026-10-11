package com.apex.agent.core.tools.network

import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * ═══ SSRF 防护器（issue #341）═══
 *
 * 背景：`app_download_install` 此前仅做 http(s) 前缀检查，URL host 无任何
 * 校验——环回 / 内网 / 链路本地（含云元数据 169.254.169.254）地址均可
 * 下载；DI 注入的 OkHttpClient 又是 followRedirects(true)，公网 URL 可经
 * 302 跳内网。下载内容落盘后路径会告知 LLM，可再经文件读取工具回读，
 * 构成「内网响应体读取原语」（结合网页 prompt injection 可探测内网）。
 *
 * 本对象提供三件套：
 *
 * 1. [assertPublicHttpUrl]——对 [HttpUrl] 的 host 做 DNS 解析（纯 IP 与
 *    域名一视同仁；「127.1」等缩写与纯数字变体由 getAllByName 兜住），
 *    任一解析结果命中黑名单段即抛 [IOException]（中文消息说明命中类别）。
 *    黑名单：IPv4 环回 127.0.0.0/8、私有 10/8、172.16/12、192.168/16、
 *    链路本地 169.254/16、未指定 0.0.0.0/8、广播 255.255.255.255；
 *    IPv6 环回 ::1、ULA fc00::/7、链路本地 fe80::/10、未指定 ::，
 *    以及 ::ffff:0:0/96 的 IPv4 映射地址（取出内嵌 IPv4 按 v4 段判断）；
 *    特殊主机名 metadata.google.internal 直接拒绝。
 *    DNS 解析失败的 host **放行**（后续连接自然失败，不破坏对畸形但公网
 *    正常域名的行为），仅记 AppLogger warn。
 * 2. [noRedirectClient]——从基础 client 派生一个关闭自动重定向的包装
 *    （共享连接池与线程池，零额外成本）。
 * 3. [executeValidated]——手动受控跟随重定向：首跳与每一跳先过
 *    [assertPublicHttpUrl] 再发起；https 降级到 http 拒绝；最多跟
 *    [MAX_REDIRECTS] 跳；非 3xx 直接返回 Response（响应体不提前消费）。
 *
 * IP 段判断用 java.net.InetAddress + 手写前缀位匹配，不引入新依赖。
 */
object SsrfGuard {

    /** 云元数据服务主机名（GCP 元数据端点，直接拒绝，不走 DNS）。 */
    private const val METADATA_HOST = "metadata.google.internal"

    /** 受控重定向的最大跳数。 */
    const val MAX_REDIRECTS = 5

    /**
     * 断言 [url] 是公网 http(s) 地址；host 命中黑名单段或协议非 http(s)
     * 时抛 [IOException]（消息为中文，说明命中的地址类别）。
     */
    @Throws(IOException::class)
    fun assertPublicHttpUrl(url: HttpUrl) {
        val scheme = url.scheme.lowercase()
        if (scheme != "http" && scheme != "https") {
            throw IOException("目标 URL 协议 '$scheme' 不是 http 或 https（SSRF 防护）")
        }
        val host = url.host
        if (host.equals(METADATA_HOST, ignoreCase = true)) {
            throw IOException("目标主机 '$host' 是云元数据服务地址（SSRF 防护）")
        }
        // host 是纯 IP 字面量还是域名都交给 getAllByName 解析——「127.1」、
        // 「2130706433」等缩写与纯数字变体同样被兜住，无需自行解析。
        val addresses: Array<InetAddress> = try {
            InetAddress.getAllByName(host)
        } catch (e: UnknownHostException) {
            // 解析失败：放行——畸形但公网正常的域名不因防护被误杀，后续
            // 连接会自然失败；留 warn 痕迹便于事后归因。
            AppLogger.instance.warn(
                LogCategory.NETWORK, "SsrfGuard",
                "host '$host' DNS 解析失败，放行待连接自然失败: ${e.message}"
            )
            return
        }
        for (addr in addresses) {
            val reason = classifyBlocked(addr)
            if (reason != null) {
                throw IOException(
                    "目标地址 '$host' 解析为$reason（${addr.hostAddress}），" +
                        "下载被拒绝：目标地址位于内网/保留网段（SSRF 防护）"
                )
            }
        }
    }

    /**
     * 从基础 client 派生关闭自动重定向的包装 client（共享连接池 / 线程池）。
     * 重定向由 [executeValidated] 手动受控跟随，保证每一跳都过校验。
     */
    fun noRedirectClient(base: OkHttpClient): OkHttpClient =
        base.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

    /**
     * 受控执行：首跳先过 [assertPublicHttpUrl]，返回前手动跟随 3xx——
     * 每一跳对 Location 解析出的绝对 URL 先校验再发起；https 降级到
     * http 拒绝；超过 [MAX_REDIRECTS] 跳拒绝；非 3xx（或无 Location 的
     * 3xx）直接返回 Response，响应体不提前消费（调用方负责 close）。
     *
     * [client] 应为 [noRedirectClient] 的产物（自动重定向必须关闭，
     * 否则 OkHttp 会在本方法看不见的内部跳转）。
     */
    @Throws(IOException::class)
    fun executeValidated(client: OkHttpClient, request: Request): Response {
        var current = request
        assertPublicHttpUrl(current.url)
        var hops = 0
        while (true) {
            val response = client.newCall(current).execute()
            if (!response.isRedirect) return response
            val location = response.header("Location") ?: return response
            val from = current.url
            response.close()
            if (++hops > MAX_REDIRECTS) {
                throw IOException("重定向超过 $MAX_REDIRECTS 跳，下载被拒绝（SSRF 防护）")
            }
            val next = resolveNextUrl(from, location)
            current = current.newBuilder().url(next).build()
        }
    }

    /**
     * 解析重定向目标为绝对 [HttpUrl]：非法 Location（含非 http(s) 协议）
     * 与 https 到 http 的跨协议降级都抛 [IOException]。
     */
    @Throws(IOException::class)
    fun resolveNextUrl(from: HttpUrl, location: String): HttpUrl {
        val resolved = from.resolve(location)
        if (resolved == null) {
            throw IOException("重定向 Location 无法解析为合法 http(s) URL: '$location'（SSRF 防护）")
        }
        if (from.scheme == "https" && resolved.scheme == "http") {
            throw IOException("重定向从 https 降级到 http（${from.url} → ${resolved.url}），已拒绝（SSRF 防护）")
        }
        return resolved
    }

    // ── 地址分类 ──────────────────────────────────────────────────────────

    /** 命中黑名单返回类别描述（如「环回地址」），公网地址返回 null。 */
    internal fun classifyBlocked(addr: InetAddress): String? {
        val bytes = addr.address
        if (bytes.size == 4) return classifyIpv4(bytes)
        if (bytes.size == 16) {
            // ::ffff:0:0/96 的 IPv4 映射地址：取出内嵌 IPv4 按对应 v4 段判断
            //（Java 对字面量常直接返回 Inet4Address，两种形态都兜住）。
            if (isIpv4Mapped(bytes)) {
                return classifyIpv4(bytes.copyOfRange(12, 16))?.let { "IPv4 映射$it" }
            }
            return classifyIpv6(bytes)
        }
        return null
    }

    private fun classifyIpv4(b: ByteArray): String? = when {
        matchesPrefix(b, V4_LOOPBACK, 8) -> "环回地址"
        matchesPrefix(b, V4_PRIVATE10, 8) -> "私有网段"
        matchesPrefix(b, V4_PRIVATE172, 12) -> "私有网段"
        matchesPrefix(b, V4_PRIVATE192, 16) -> "私有网段"
        matchesPrefix(b, V4_LINK_LOCAL, 16) -> "链路本地地址"
        matchesPrefix(b, V4_UNSPECIFIED, 8) -> "未指定地址段"
        matchesPrefix(b, V4_BROADCAST, 32) -> "广播地址"
        else -> null
    }

    private fun classifyIpv6(b: ByteArray): String? = when {
        matchesPrefix(b, V6_LOOPBACK, 128) -> "IPv6 环回地址"
        matchesPrefix(b, V6_UNSPECIFIED, 128) -> "未指定地址"
        matchesPrefix(b, V6_ULA, 7) -> "IPv6 ULA 私有网段"
        matchesPrefix(b, V6_LINK_LOCAL, 10) -> "IPv6 链路本地地址"
        else -> null
    }

    /** 前 96 位为 0 且第 11/12 字节为 0xFF（::ffff:a.b.c.d 形态）。 */
    private fun isIpv4Mapped(b: ByteArray): Boolean {
        for (i in 0..9) {
            if (b[i] != 0.toByte()) return false
        }
        return b[10] == 0xFF.toByte() && b[11] == 0xFF.toByte()
    }

    /** 手写前缀位匹配：[prefix] 与地址同宽（4 或 16 字节），[prefixBits] 为前缀长度。 */
    private fun matchesPrefix(bytes: ByteArray, prefix: ByteArray, prefixBits: Int): Boolean {
        if (bytes.size != prefix.size) return false
        val fullBytes = prefixBits / 8
        val remBits = prefixBits % 8
        for (i in 0 until fullBytes) {
            if (bytes[i] != prefix[i]) return false
        }
        if (remBits == 0) return true
        val mask = (0xFF shl (8 - remBits)) and 0xFF
        return (bytes[fullBytes].toInt() and 0xFF and mask) ==
            (prefix[fullBytes].toInt() and 0xFF and mask)
    }

    private val V4_LOOPBACK = byteArrayOf(127.toByte(), 0, 0, 0)
    private val V4_PRIVATE10 = byteArrayOf(10, 0, 0, 0)
    private val V4_PRIVATE172 = byteArrayOf(172.toByte(), 16, 0, 0)
    private val V4_PRIVATE192 = byteArrayOf(192.toByte(), 168.toByte(), 0, 0)
    private val V4_LINK_LOCAL = byteArrayOf(169.toByte(), 254.toByte(), 0, 0)
    private val V4_UNSPECIFIED = byteArrayOf(0, 0, 0, 0)
    private val V4_BROADCAST = byteArrayOf(
        255.toByte(), 255.toByte(), 255.toByte(), 255.toByte()
    )

    private val V6_LOOPBACK = ByteArray(16).also { it[15] = 1 }
    private val V6_UNSPECIFIED = ByteArray(16)
    private val V6_ULA = ByteArray(16).also { it[0] = 0xFC.toByte() }
    private val V6_LINK_LOCAL = ByteArray(16).also {
        it[0] = 0xFE.toByte()
        it[1] = 0x80.toByte()
    }
}
