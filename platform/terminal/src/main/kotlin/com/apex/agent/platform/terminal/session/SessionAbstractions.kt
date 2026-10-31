package com.apex.agent.platform.terminal.session

/**
 * PR #54 §4: Session creation config. Config ≠ Runtime State.
 */
data class TerminalSessionConfig(
    val workingDirectory: String? = null,
    val rows: Int = 24,
    val cols: Int = 80,
    val environment: Map<String, String> = emptyMap()
)

/** PR #54 §12: Consistent point-in-time snapshot. */
data class TerminalSessionSnapshot(
    val sessionId: Long, val state: SessionState, val exitReason: SessionExitReason?,
    val createdAt: Long, val startedAt: Long?, val finishedAt: Long?,
    val primaryProcessId: Long?, val shell: String?, val workingDirectory: String?
)

// v3（死代码清理）：本文件原有的 TerminalSessionEvent / PrimaryProcess /
// TerminalSessionListener / TerminalExecutionBackend 四个类型零引用（事件
// 走 events/TerminalEvent 目录、进程走 pty/NativePty + ExecutionBackend，
// 均为各自的单一事实源）—— PR #54 时代的并行设计稿，留着只会误导后来人
// 以为有两套可换的实现。已删除；TerminalSessionConfig / TerminalSessionSnapshot
// 在用，保留。
