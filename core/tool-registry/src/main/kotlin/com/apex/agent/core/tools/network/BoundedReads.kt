package com.apex.agent.core.tools.network

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * ═══ 有界流读取（OOM 防御，Issue #345）═══
 *
 * 安装管道与 MCP HTTP 传输长期持有远端响应：被劫持的 DNS/代理或异常
 * 网关返回超大响应时，无上限的整体读入（`body.string()` / `readBytes()`）
 * 会直接 OOM。本对象把「最多读 N 字节」收敛成单点实现，供 core 的
 * mcp 传输、marketplace 源与 app 安装管道共用（app 层各自的私有
 * `readBytesLimited` 语义相同，本次不做强制迁移）。
 *
 * 两种口径：
 * - [readAtMost]：超限返回 null（与各市场源既有实现一致，调用方转明确
 *   失败文案）；
 * - [readHead]：读到上限即止，返回**头部字节**（截断语义，供 frontmatter
 *   探测、错误体摘要这类「只需要开头」的场景）。
 *
 * 纯 JVM 无 Android 依赖；函数不关闭流（关闭职责归 `use` 调用方）。
 */
object BoundedReads {

    /**
     * 读取至多 [maxBytes] 字节。
     *
     * @return 流在达到上限前结束 → 全部字节；恰好还有剩余字节（超限）→
     *   null，由调用方折叠为明确失败（中文文案点名上限）。
     */
    fun readAtMost(stream: InputStream, maxBytes: Int): ByteArray? {
        require(maxBytes > 0) { "maxBytes 必须为正数：$maxBytes" }
        val out = ByteArrayOutputStream(initialSizeOf(maxBytes))
        val buf = ByteArray(BUFFER_SIZE)
        var total = 0
        while (total < maxBytes) {
            val n = stream.read(buf, 0, minOf(buf.size, maxBytes - total))
            if (n < 0) return out.toByteArray()
            out.write(buf, 0, n)
            total += n
        }
        // 读满上限：多探 1 字节判断是「恰好等于上限」还是「仍有剩余（超限）」
        return if (stream.read() >= 0) null else out.toByteArray()
    }

    /**
     * 读取**头部**至多 [maxBytes] 字节即止（截断语义，不视为失败）：
     * frontmatter 永远在文件头部、错误体摘要只取前几百字符 —— 超限的
     * 尾部内容本来就不需要。
     */
    fun readHead(stream: InputStream, maxBytes: Int): ByteArray {
        require(maxBytes > 0) { "maxBytes 必须为正数：$maxBytes" }
        val out = ByteArrayOutputStream(initialSizeOf(maxBytes))
        val buf = ByteArray(BUFFER_SIZE)
        var total = 0
        while (total < maxBytes) {
            val n = stream.read(buf, 0, minOf(buf.size, maxBytes - total))
            if (n < 0) break
            out.write(buf, 0, n)
            total += n
        }
        return out.toByteArray()
    }

    /** ByteArrayOutputStream 初始容量的粗略上限（避免大上限时直接预分配）。 */
    private fun initialSizeOf(maxBytes: Int): Int = minOf(maxBytes, 64 * 1024)

    private const val BUFFER_SIZE = 16 * 1024
}
