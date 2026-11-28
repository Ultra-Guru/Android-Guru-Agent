package com.apex.agent.tools

/**
 * ═══ DCP 路径白名单策略（S06）═══
 *
 * file.* 意图的路径门（I6 诚实纪律的正面清单）：
 * - **允许**（默认白名单）：`/sdcard`、`/storage/emulated/0`、`/data/local/tmp`
 *   以及 app 私有目录（构造注入 [extraAllowedRoots]，宿主 filesDir 等）；
 * - **拒绝**：`/system`、`/vendor`、`/proc`、`/sys` 写入与其它包的
 *   `/data/data`（uid=2000 也拿不到，拿不到就诚实报出）；
 * - 规范化后校验：折叠 `.`、拒绝 `..`（防穿越），拒绝符号链接段里的
 *   元字符（路径以 argv 元素传递，本身无注入面，这里只做语义域校验）；
 * - 回收区 `/data/local/tmp/.dcp-trash` 是 DCP 自己的内部区：用户参数
 *   不允许直接指向它（防 undo_token 伪造），内部 argv 由 [DeviceTrash] 管理。
 */
class DevicePathPolicy(
    private val extraAllowedRoots: List<String> = emptyList()
) {

    private val defaultAllowedRoots = listOf(
        "/sdcard",
        "/storage/emulated/0",
        "/data/local/tmp"
    )

    private val allowedRoots: List<String> =
        (defaultAllowedRoots + extraAllowedRoots).map { normalize(it) }.distinct()

    /** 路径判定结果。 */
    sealed class Decision {
        object Allow : Decision()
        data class Deny(val reason: String) : Decision()
    }

    /**
     * 校验一条宿主路径。
     *
     * @param path 已过 ArgvRenderer PATH 校验的绝对路径（此处再做作用域判定）
     * @param write true = 写操作（读也受白名单约束 —— 语义一致，无读写双标）
     */
    fun check(path: String, write: Boolean): Decision {
        val normalized = normalize(path)
        if (normalized != path) {
            // 含会被规范化改写的段（./、重复分隔或尾分隔符）—— 统一拒绝，
            // 防混淆与绕过式变体；模型收到文案后用规范化路径重试即可。
            return Decision.Deny("路径含冗余段（./、重复分隔或尾分隔符），请提供规范化绝对路径：$path")
        }
        if (normalized.contains("..")) {
            return Decision.Deny("路径含 .. 路径穿越，已拒绝")
        }
        if (normalized == TRASH_ROOT || normalized.startsWith("$TRASH_ROOT/")) {
            return Decision.Deny("回收区是 DCP 内部区域（$TRASH_ROOT），不能作为操作目标；撤销请用 file.undo + undo_token")
        }
        if (FORBIDDEN_ROOTS.any { normalized == it || normalized.startsWith("$it/") }) {
            return Decision.Deny(
                if (write) "目标路径在系统保护区内（$normalized），拒绝写入" +
                    "（需要 ROOT 的操作当前档位做不到，不冒充成功）"
                else "目标路径在保护区（$normalized），当前档位读不到（uid=2000 也受 SELinux 约束）"
            )
        }
        val allowed = allowedRoots.any { normalized == it || normalized.startsWith("$it/") }
        if (!allowed) {
            return Decision.Deny(
                "目标路径不在白名单（允许：${allowedRoots.joinToString("、")}）；" +
                    "其它包的 /data/data 与系统分区 uid=2000 也拿不到"
            )
        }
        return Decision.Allow
    }

    /** 规范化：折叠 `.` 段与冗余分隔（不处理 ..，含 .. 直接由调用方拒绝）。 */
    private fun normalize(path: String): String {
        val p = path.trim()
        if (!p.startsWith("/")) return p
        val out = mutableListOf<String>()
        for (seg in p.split('/')) {
            when {
                seg.isEmpty() || seg == "." -> Unit
                else -> out += seg
            }
        }
        return "/" + out.joinToString("/")
    }

    /** basename（file.delete 的回收区文件名；路径已过白名单）。尾部空段
     * （尾分隔符）如实回退 "file" —— 不猜测目录名，防回收区文件名歧义。 */
    fun basename(path: String): String =
        path.substringAfterLast('/').ifBlank { "file" }

    internal companion object {
        /** 回收区根（S06 I4：软删一律移到这里，返回 undo_token）。 */
        const val TRASH_ROOT = "/data/local/tmp/.dcp-trash"

        /** 明确禁区（读写在白名单外也一并给出针对性文案）。 */
        private val FORBIDDEN_ROOTS = listOf(
            "/system", "/vendor", "/product", "/proc", "/sys", "/dev",
            "/data/data"
        )
    }
}
