package com.apex.agent.platform.terminal.tmux

/**
 * Tmux 多会话模型的类型化异常（对齐 errors/TerminalError.kt 的 code/recoverable 契约）。
 *
 * 兼容性：message 格式保持仓库既有约定 `"TmuxError:<code> — <detail>"`，
 * 工具层按前缀解析的行为不变；但 code/recoverable 现在是**类型化字段**，
 * 调用方 catch 后可直接决策 retry / repair / abort，不再解析字符串。
 */
open class TmuxException(
    val code: String,
    override val message: String,
    val recoverable: Boolean,
    cause: Throwable? = null
) : RuntimeException("TmuxError:$code — $message", cause) {

    /** tmux 二进制不可用（guest rootfs 未安装 tmux / 未就绪）。 */
    class TmuxUnavailable(detail: String, cause: Throwable? = null) :
        TmuxException("TmuxUnavailable", detail, recoverable = true, cause = cause)

    /** 目标会话不存在（已退出或从未创建）。 */
    class SessionNotFound(session: String) :
        TmuxException("SessionNotFound", "session '$session' 不存在", recoverable = false)

    /** 目标会话已存在（幂等创建路径之外的重名请求）。 */
    class SessionAlreadyExists(session: String) :
        TmuxException("SessionAlreadyExists", "session '$session' 已存在", recoverable = false)

    /** tmux server 报错（new-session / kill-session 等命令非零退出）。 */
    class TmuxCommandFailed(detail: String, cause: Throwable? = null) :
        TmuxException("TmuxCommandFailed", detail, recoverable = true, cause = cause)

    /** 参数非法（空名 / 非法字符 / 未知 action）。 */
    class InvalidInput(detail: String) :
        TmuxException("InvalidInput", detail, recoverable = false)

    /** 持久化存储读写失败（不阻断 tmux 真实操作，仅影响元数据视图）。 */
    class StoreFailed(detail: String, cause: Throwable? = null) :
        TmuxException("StoreFailed", detail, recoverable = true, cause = cause)

    /** 错误码 → 是否可重试（Agent 侧决策用）。 */
    val retryable: Boolean get() = recoverable
}
