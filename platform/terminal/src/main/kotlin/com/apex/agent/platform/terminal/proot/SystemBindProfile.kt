package com.apex.agent.platform.terminal.proot

import com.apex.agent.platform.terminal.workspace.AbsolutePath
import java.io.File

/**
 * T82 — Linux 会话的系统级 bind 集（proot-distro / Termux 基线 §4.2）。
 *
 * 背景：此前 proot 以裸 `-r` 启动 —— guest 内 `/proc`、`/dev`、`/sys` 是 Ubuntu
 * Base 解压出的空目录。后果：`ps/top/pstree`（procps 是 essential 包！）在
 * guest 内全灭；`/dev/urandom`、`/dev/null` 缺失会破坏 python(ssl)/curl 等的
 * 正常熵源与重定向习惯。proot-distro 的 login 默认带这些 bind —— 本类把同一
 * 语义带进我们的 PRoot argv。
 *
 * 语义：
 *  - [STANDARD]：`/proc`、`/dev`、`/sys` 各自 bind 到同名 guest 路径
 *    （host 侧真实内核接口 → guest 内真实可用）。Android host 上这些目录
 *    恒存在；不存在的 host 路径会被 [filterExisting] 诚实丢弃（不伪造）。
 *  - [NONE]：旧行为（裸 -r，无系统 bind）—— 显式兼容开关。
 *
 * 安全注记：bind host /dev 给 guest = guest 可访问 host 设备节点。这与
 * proot-distro 的默认行为一致（Android 的 SELinux/设备权限仍是真实门禁 —
 * app 只能访问自己有权限的节点）。需要更紧隔离的调用方选 [NONE]。
 */
class SystemBindProfile private constructor(
    val entries: List<Entry>
) {
    data class Entry(
        val hostPath: String,
        val guestPath: String,
        val readOnly: Boolean = false
    )

    /** 转为 PRootBind（只含 host 侧真实存在的路径 —— 诚实过滤）。 */
    fun toBinds(): List<PRootBind> =
        entries.filter { File(it.hostPath).exists() }.map {
            PRootBind(AbsolutePath(it.hostPath), it.guestPath, readOnly = it.readOnly)
        }

    /** 显式版本（诊断/工具输出用）。 */
    fun describe(): String =
        if (entries.isEmpty()) "none" else entries.joinToString(",") { "${it.hostPath}:${it.guestPath}" }

    companion object {
        /** proot-distro 语义的标准系统 bind。 */
        val STANDARD = SystemBindProfile(
            listOf(
                Entry("/proc", "/proc"),
                Entry("/dev", "/dev"),
                Entry("/sys", "/sys")
            )
        )

        /** 旧行为：无系统 bind（裸 -r）。 */
        val NONE = SystemBindProfile(emptyList())
    }
}

/**
 * T82 — 共享存储桥（Termux `termux-setup-storage` 的诚实等价物，基线 §11.3）。
 *
 * Termux 的 setup-storage 通过 intent 引导用户授权后把
 * `/storage/emulated/0` 链到 `~/storage/shared`。我们的架构没有 UI 授权流 ——
 * app 层在持有权限时解析出 Android 共享存储目录（`/storage/emulated/0` 或
 * `context.getExternalFilesDir` 变体），以 **host 目录 provider** 注入本类；
 * 目录不可用（无权限/不存在）→ bind 为 null（诚实降级：guest 看不到共享
 * 存储，绝不伪造空目录）。
 *
 * bind 目标：guest `/sdcard`（Termux 兼容语义 —— guest 脚本的直觉路径）。
 */
class SharedStorageBridge(
    /** host 侧共享存储目录（app 层解析；null/不存在 = 未授权/不可用）。 */
    private val hostDirProvider: () -> File?
) {
    /** 可用性（诊断/工具输出用）。 */
    fun available(): Boolean = hostDirProvider()?.isDirectory == true

    /** host 目录（不存在 → null）。 */
    fun hostDir(): File? = hostDirProvider()?.takeIf { it.isDirectory }

    /** PRoot bind（host 共享存储 → guest /sdcard）；不可用 → null。 */
    fun toBind(): PRootBind? = hostDir()?.let {
        PRootBind(AbsolutePath(it.absolutePath), GUEST_PATH)
    }

    companion object {
        /** guest 侧共享存储路径。 */
        const val GUEST_PATH = "/sdcard"
    }
}

/**
 * 真实系统根桥 —— host `/` → guest `/host`（2026-10 用户需求：「整个系统」
 * 工作区必须真实可见）。
 *
 * 此前 Ubuntu 通道（PTY 会话 / TerminalExec）只挂 rootfs + /workspace +
 * /sdcard —— 模型在「整个系统」工作区里用 shell 探索时看到的 `/` 永远是
 * rootfs 沙箱，与 code_* 工具（host 根 = /）的视野割裂。本桥把真实 Android
 * 文件系统根挂进 guest `/host`：
 *
 *  - `/host/system/build.prop` 即宿主 `/system/build.prop`（纯路径翻译，无拷贝）；
 *  - 权限门禁仍是真实的 Android 权限/SELinux：`/host/data` 等返回
 *    Permission denied（需 root，属预期）；`/system` 只读挂载写入失败（属预期）；
 *  - 与 `/proc`、`/dev`、`/sys`（[SystemBindProfile.STANDARD]）、`/sdcard`
 *    （[SharedStorageBridge]）同一 proot-distro 式「诚实挂载」纪律 ——
 *    app uid 能访问什么，guest 就能看到什么；
 *  - 消费面：PRoot 通道的 Agent 执行壳（PTY 会话 STANDARD 档 + TerminalExec
 *    一次性命令）。STANDARD 之外的档位（NONE = 旧裸 -r 隔离语义）与市场
 *    沙箱（ProotSandboxCommandRunner）不挂 —— 隔离收窄的调用方不被动升级。
 */
object HostRootBridge {

    /** guest 侧真实系统根路径。 */
    const val GUEST_PATH = "/host"

    /** PRoot bind（host `/` → guest `/host`）。 */
    fun toBind(): PRootBind = PRootBind(AbsolutePath("/"), GUEST_PATH)
}
