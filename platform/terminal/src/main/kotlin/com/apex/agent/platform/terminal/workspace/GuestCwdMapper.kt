package com.apex.agent.platform.terminal.workspace

/**
 * T98：guest cwd 统一映射 —— PRoot / Chroot 双后端的单一事实源。
 *
 * 原实现内联在 [com.apex.agent.platform.terminal.proot.LinuxPRootBackend.mapGuestCwd]
 *（T73 引入）。T98 新增 chroot 后端时按「复用层，不复制」原则抽出（与本仓库
 * T81 D-6 消除 env 基线两处内联漂移同构 —— 两后端对同一语义的映射必须逐字节
 * 一致，否则同一 Agent 脚本换个后端跑就落在不同目录）：
 *
 *  - 空 / "/" → /workspace（会话默认落点）
 *  - "/sdcard" → /workspace（旧 LocalShellBackend 的 Android host 路径在
 *    guest 内无意义；与 "" 同语义。AndroidShell 移除后由 PRoot 后端内联，
 *    现统一在此）
 *  - 其他绝对路径 → 原样直通（如 "/root"）
 *  - 相对路径 → /workspace/<cwd>（workspace 命名空间内解析）
 */
object GuestCwdMapper {

    /** guest 侧 workspace 挂载点（PRoot -b 与 chroot mount 的共同目标）。 */
    const val GUEST_WORKSPACE_ROOT: String = "/workspace"

    /** request.cwd（guest 语义路径）→ chroot/proot 内真实落点。 */
    fun map(cwd: String): String {
        val trimmed = cwd.trim()
        if (trimmed.isEmpty() || trimmed == "/") return GUEST_WORKSPACE_ROOT
        if (trimmed == "/sdcard") return GUEST_WORKSPACE_ROOT
        return if (trimmed.startsWith("/")) trimmed else "$GUEST_WORKSPACE_ROOT/$trimmed"
    }
}
