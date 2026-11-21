package com.apex.agent.core.tools.mcp

/**
 * #197 MCP 真实启动事件 —— 对齐 MCP 官方本地运行生命周期。
 *
 * MCP 本地（stdio）服务器的真实启动过程（见官方 spec / 各宿主实现）：
 *
 * 1. **环境检查**：宿主解析配置（命令/参数/环境变量或 URL），沙箱形态还需
 *    确认 rootfs 就绪；
 * 2. **spawn 子进程**：宿主 fork 出 server 进程（如 `npx -y
 *    @modelcontextprotocol/server-memory`），stdin/stdout 双管道接驳，
 *    stderr 走日志；
 * 3. **initialize 请求**：宿主 → server 的 JSON-RPC 握手（protocolVersion +
 *    capabilities + clientInfo）；
 * 4. **initialize 响应**：server 回报自身 capabilities 与 serverInfo（名称/版本）；
 * 5. **notifications/initialized**：宿主发出的就绪通知；
 * 6. **tools/list 发现**：宿主拉取 server 的工具清单，注册进工具注册表。
 *
 * 每一个 [McpStartupEvent] 都由真实代码路径产生（进程 pid / 握手结果 /
 * 工具数全部来自实际返回值）—— **没有模拟延时，没有假阶段**：
 * 进程内（BUILTIN）服务器的"spawn"阶段如实标注「进程内」。
 */
enum class McpStartupStage {
    /** 配置/环境检查（transport + 命令行或 URL + 沙箱就绪态）。 */
    ENV_CHECK,

    /** 子进程已 fork（detail 携带真实 pid 与 argv）；BUILTIN = 进程内标注。 */
    SPAWN,

    /** initialize 请求已发出。 */
    INITIALIZE_SENT,

    /** initialize 响应已收到（detail 携带 server 真实 serverInfo 与 capabilities）。 */
    INITIALIZE_RESULT,

    /** notifications/initialized 已发出（会话就绪）。 */
    INITIALIZED,

    /** tools/list 完成（detail 携带真实发现的工具清单）。 */
    TOOLS_DISCOVERED,

    /**
     * #205 子进程 stderr 输出（detail = 截断后的真实 stderr 行）。
     *
     * npx 下载失败、Python 崩栈、Node 告警都走 stderr —— 握手超时前用户
     * 在时间线上就能看到真实原因。有速率上限（每连接前 [McpClient] 侧
     * 最多上报 N 行），不会淹没阶段事件。
     */
    STDERR,

    /** 连接失败（detail 携带真实异常信息）。 */
    FAILED
}

/** 单条真实启动事件（[McpStartupStage] + 真实数据 + 时间戳）。 */
data class McpStartupEvent(
    val serverName: String,
    val stage: McpStartupStage,
    val detail: String,
    val timestampMs: Long = System.currentTimeMillis()
)

/** 启动事件监听（McpManager.connect 可选注入；市场页进度弹窗消费）。 */
fun interface McpStartupListener {
    fun onStartupEvent(event: McpStartupEvent)
}
