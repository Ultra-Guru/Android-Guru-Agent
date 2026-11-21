# 本地 MCP 起不来 —— 启动链路修复（v5 批次）

> 用户报告：「mcp 本地运行和启动都有一些问题」。本批次按真实代码路径逐条定位并修复，
> 每条都标注现象 / 根因（file 定位）/ 修复点。

## P0 —— 市场「启动」按钮对刚安装的服务器**必然失败**

- **现象**：官方 MCP 仓库（apex-mcp-hub）安装后点「启动」，进度弹窗真实闪过
  env 检查 → 子进程 pid → initialize，随后报
  `Server 'X' was disconnected or disabled during connect`，服务器永远不在运行态。
- **根因**：安装链路一律写 `enabled = false`（安装 ≠ 启用），而
  `McpManager.connect` 只在**握手成功之后**校验 `enabled` —— 于是最贵的一段
  （拉起进程 + 握手）白做，最后把刚 fork 的 client 关掉；市场行卡的「启动」按钮
  又不判 enabled，两条路径语义不一致。
- **修复**：
  1. `McpManager.connect` 在 ENV_CHECK **之前**快速失败，文案给出可行动指引
     （"已安装但未启用：请先打开启用开关再启动"）；
  2. `MarketViewModel.connectMcp` 语义改为**启用 + 连接**原子完成 —— 用户点一次
     「启动」就真的起来（与「启用开关」同一入口，不再需要点两下）。

## P1 —— 空响应被折叠成"假成功"

- **现象**：HTTP 网关（或 SSE）对 initialize / tools/list / tools/call 回
  200 + 空体时，App 报"已连接、0 工具"，或把一次失败的调用当成功（引擎按字符串
  协议判成败，模型据此继续）。
- **根因**：`McpHttpTransport` 把空 body 折成 null，`McpClient` 三个入口照旧
  `Result.success`。
- **修复**：带 id 的请求拿到空响应即失败，三处分别给出协议级错误文案
  （`服务器返回空响应（HTTP 200 无 body / SSE 无 data 帧）：<method>`）。

## P1 —— 一次 `tools/list` 失败 = 该服务器的一等工具整体消失

- **现象**：重连抖动后 `Mcp*` 工具从工具表整体消失，且不会自动回来。
- **根因**：`McpManager.listServerTools` 把失败折叠成空清单 →
  `McpToolRegistrar` 把空清单当"服务器不再提供工具"，REPLACE 语义把旧 id 全部注销。
- **修复**：`listServerTools` 返回 `Result`；注册器只在**发现成功**时做 REPLACE
  清算，失败时保留既有注册（断开路径本来就会精确注销）。市场启动弹窗同样如实
  上报"工具发现失败"而不是"发现 0 个工具"。

## P1 —— STDIO 写路径无超时，可永久钉死 IO 线程

- **现象**：stdio 服务器卡死（npx 卡下载、被 SIGSTOP、只写 stderr）后工具调用
  不再返回，上层取消也无效；重试几次耗尽 IO 线程池 → 全 App IO 变慢。
- **根因**：`McpStdioTransport.send` 在协程里同步 `write + flush`，阻塞写既不响应
  取消又长期持有 `writeLock`，后续请求（含 tools/list）全部排队。
- **修复**：写路径改**专用写线程 + 有界 `Future.get(requestTimeoutMs)`**，超时即
  标记传输不健康（`isHealthy()` 为 false → Supervisor 收尸重启），并抛可读错误。

## P1 —— SSE 分帧不兼容规范实现

- **现象**：`transport=sse` 连官方 SSE 端点必报"HTTP 200 返回非 JSON 响应
  （疑似网关/鉴权拦截页）"。
- **根因**：解析器只认"整个 body 以 `data:` 开头"；规范服务器首帧常是
  `event: endpoint` / 心跳 `: ping`，且响应后可能跟一条通知 —— 取"最后一条 data"
  会取错帧。
- **修复**：按行分帧（有 `data:` 行或首行是 `event:`/`:` 即按 SSE 处理），
  逐条解析并要求 **JSON-RPC id 匹配**本次请求，取不到再回退最后一条可解析对象。

## P1 —— 宿主 stdio 的不可行动报错 + 默认不开沙箱

- **现象**：加 `npx -y …` 本地服务器（未勾沙箱）→
  `Cannot run program "npx": error=ENOENT`，对用户与模型都无从下手。
- **修复**：
  1. `JvmProcessLauncher` 把启动异常折叠成带指引的 `McpException`
     （"Android 宿主通常没有 node/npx —— 请开启沙箱运行 runInSandbox=true"）；
  2. `McpConnect` 的 `run_in_sandbox` **默认改为 true**（显式 `false` 才走宿主），
     Android 上"配置即能起来"。

## 验证

- 全部改动在 `core:tool-registry`（纯 JVM，可单测闭环）+ app 市场两处调用点；
- 既有 MCP 单测（SandboxMcpPresetTest / SandboxMcpConfigTest / McpManagerStartupEventsTest /
  McpConfigValidatorTest …）语义保持：`runInSandbox` 透传、沙箱路由、启动事件序列不变；
- CI（GitHub Actions）：静态分析 + app 编译 + 9 步测试 + debug APK + 结构质量门禁。
