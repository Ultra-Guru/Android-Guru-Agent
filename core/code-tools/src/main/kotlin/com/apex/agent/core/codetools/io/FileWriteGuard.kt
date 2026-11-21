package com.apex.agent.core.codetools.io

import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/**
 * # FileWriteGuard — code-tools 写路径统一守卫（原子写 + 跨工具按路径互斥）
 *
 * AGENTS.md「原子写：持久化一律 tmp + renameTo，rename 失败直写目标兜底」
 * 铁律在 code-tools 的落地。此前 CodeEdit / CodeWrite 各自裸 `writeText()`
 * （同仓 LongTaskStore / CodeConversationMemory 是正例，coding 工具是落后者）：
 * 进程在写中途死亡即留下半写文件，模型下一轮读到的是撕裂内容。
 *
 * 两个职责：
 * 1. **[writeFileAtomically]**：tmp 写入 → flush → fsync → renameTo（同目录
 *    同分区，rename 原子）。fsync 保证 rename 前数据已落盘——进程死亡时
 *    rename 要么已发生要么没发生，不会出现「rename 成功但内容丢失」。
 *    rename 失败（目标被锁 / 文件系统拒绝）→ 直写目标兜底（tmp 已 fsync
 *    成功，此时直写几乎必成）+ 留痕；tmp 阶段失败则**上抛**——目标文件
 *    保持原样，绝不在失败路径上截断用户源码（BaseTool 会折叠为
 *    EXECUTION_FAILED 回给模型，诚实可重试）。
 * 2. **[withPathLock]**：per-path [ReentrantLock] 池（canonical path 为键）。
 *    CodeEdit 的「读原文 → 模糊替换 → 写回」与 CodeWrite 的「读 before →
 *    覆盖」是完整临界区，共享同一把锁后互斥——此前两工具各有各的锁池
 *    （CodeWrite 干脆没有），并发写同一路径必然撕裂。
 */
object FileWriteGuard {

    private const val TAG = "FileWriteGuard"

    /** 按路径的互斥锁池：同文件并发写防撕裂（键 = canonical path）。 */
    private val pathLocks = ConcurrentHashMap<String, ReentrantLock>()

    /**
     * 串行化对同一路径的读-改-写临界区。[ReentrantLock] 可重入——临界区内
     * 再触发对同一路径的写（如 [writeFileAtomically] 的自持锁）安全。
     */
    fun <T> withPathLock(path: File, block: () -> T): T {
        val lock = pathLocks.computeIfAbsent(lockKeyOf(path)) { ReentrantLock() }
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    /**
     * 原子写：tmp → flush → fsync → renameTo；rename 失败直写目标兜底。
     *
     * 内部自持 [withPathLock]（可重入），未走临界区的裸调用也获得写级互斥；
     * 但「读原文 → 计算 → 写回」的原子性仍需调用方把整个区间包进
     * [withPathLock]。
     *
     * @throws IOException tmp 写入/清盘失败——目标文件保持原样，由调用方
     *   异常通道上抛（诚实失败优于截断源码后谎报成功）
     */
    fun writeFileAtomically(file: File, content: String) = withPathLock(file) {
        file.parentFile?.let { parent -> if (!parent.isDirectory) parent.mkdirs() }
        val tmp = File(file.parentFile, file.name + TMP_SUFFIX)
        try {
            FileOutputStream(tmp).use { out ->
                out.write(content.toByteArray(Charsets.UTF_8))
                out.flush()
                // fsync：rename 前数据已落盘——进程死亡时 rename 要么已发生
                // 要么没发生，不会出现「rename 成功但内容丢失」。
                out.fd.sync()
            }
            if (!tmp.renameTo(file)) {
                // rename 失败（目标被锁 / 文件系统拒绝原子替换）→ 直写目标
                // 兜底 + 留痕（AGENTS.md 原子写契约的兜底分支）。
                AppLogger.instance.warn(
                    LogCategory.TOOL, TAG,
                    "原子 rename 失败，回退直写: ${file.path}"
                )
                try {
                    file.writeText(content, Charsets.UTF_8)
                } finally {
                    tmp.delete()
                }
            }
        } catch (e: IOException) {
            // tmp 阶段失败（磁盘满/权限）：清理 tmp 并上抛——绝不在失败路径
            // 上截断既有的目标文件。
            tmp.delete()
            throw e
        }
    }

    /** 锁键：canonical path（符号链接归一，edit/write 解析出同一 File 即同键）；解析失败回退绝对路径。 */
    private fun lockKeyOf(path: File): String =
        runCatching { path.canonicalPath }.getOrDefault(path.absolutePath)

    private const val TMP_SUFFIX = ".tmp"
}

/**
 * 文本风格（BOM / CRLF）探测与还原 —— CodeEdit / CodeWrite 的共享契约：
 * 改写既有文件时保留原 BOM 与原行尾风格，不让全量写悄悄改变文件形态
 * （与 git diff 噪音 / Windows 工程兼容性直接相关）。
 *
 * - [normalize]：原文 → 无 BOM、LF 行尾的归一形态（diff 与 FuzzyReplacer
 *   匹配用——两侧同为归一形态，diff 才不因行尾差异误报全文件变更）；
 * - [apply]：新内容按本风格还原（原 BOM 重新前置；原文件是 CRLF 且新内容
 *   不含 \r 时 LF → CRLF）。
 */
data class TextFileStyle(
    val hasBom: Boolean,
    val hasCrlf: Boolean
) {

    /** 原文 → 归一形态：剥 BOM + CRLF → LF。 */
    fun normalize(raw: String): String {
        var body = if (hasBom && raw.startsWith(BOM)) raw.substring(BOM.length) else raw
        if (hasCrlf) body = body.replace("\r\n", "\n")
        return body
    }

    /** 归一/新内容 → 磁盘形态：按原风格还原 BOM 与行尾。 */
    fun apply(content: String): String {
        var body = if (hasBom && content.startsWith(BOM)) content.substring(BOM.length) else content
        // 仅当内容是 LF 形态（不含任何 \r）才还原 CRLF——模型显式给出 \r 时
        // 尊重其字面内容，不做二次改写。
        if (hasCrlf && !body.contains('\r')) body = body.replace("\n", "\r\n")
        return (if (hasBom) BOM else "") + body
    }

    companion object {
        /** U+FEFF —— 显式转义书写，避免源码中不可见字符被编辑器悄悄增删。 */
        const val BOM = "\uFEFF"

        /** 中性风格（新文件 / 探测失败）：内容原样落盘，不改写任何形态。 */
        val PLAIN = TextFileStyle(hasBom = false, hasCrlf = false)

        /** 从已读入的全文探测风格（文件在大小上限内被完整读入的场景）。 */
        fun of(raw: String): TextFileStyle = TextFileStyle(
            hasBom = raw.startsWith(BOM),
            hasCrlf = raw.contains("\r\n")
        )

        /**
         * 从文件头部有限读取探测风格——超大小上限的大文件不做全量读入
         * （OOM 防护），但风格契约仍然成立。读 [SNIFF_HEAD_BYTES] 字节；
         * 行尾风格出现在头部之外的单行巨文件探测为启发式（可接受：BOM 锚定
         * 首 3 字节，CRLF 文件通常前几行即可见）。
         */
        fun sniff(file: File): TextFileStyle = runCatching {
            file.inputStream().use { ins ->
                val head = ByteArray(SNIFF_HEAD_BYTES)
                var off = 0
                while (off < head.size) {
                    val n = ins.read(head, off, head.size - off)
                    if (n < 0) break
                    off += n
                }
                // 多字节序列在截断边界可能撕裂出替换字符——对 BOM/CRLF 探测
                // 无碍（两者都锚定 ASCII / 首字节）。
                of(head.decodeToString(endIndex = off))
            }
        }.getOrDefault(PLAIN)

        private const val SNIFF_HEAD_BYTES = 64 * 1024
    }
}
