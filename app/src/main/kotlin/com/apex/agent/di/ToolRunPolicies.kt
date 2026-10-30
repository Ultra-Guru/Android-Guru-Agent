package com.apex.agent.di

import com.apex.agent.core.tools.ToolRunPolicy

/**
 * ═══ 终端/环境类工具的执行器超时策略表（v3 ToolRunPolicy 显式覆盖）═══
 *
 * 背景（Ubuntu 环境探索验证，docs/ubuntu-environment-exploration.md §6）：
 * `DefaultToolRunPolicyResolver` 对未覆盖的 mutating 工具推断为 60s 执行
 * 预算；而 Ubuntu 环境工具自身管理分钟级~十分钟级预算（rootfs 解包 2~5min、
 * apt install 600s、ensure 编排 15~30min）。执行器 withTimeout 会先于工具
 * 自身的超时逻辑杀掉调用 —— Agent 请求 `TerminalUbuntuEnsure` 等
 * 环境入口 100% 在 60s 收到 "timeout: tool call exceeded 60000ms budget"，
 * 即使工作本身可恢复/可续跑。
 *
 * 原则：执行器预算 = 工具自身内部预算 + 余量，不盲重试（幂等由工具
 * 状态机保证，IN_PROGRESS 可续跑）。提出为公开常量供单测对齐校验
 * （TerminalToolPolicyTest），防再次漂移。
 */
val TERMINAL_TOOL_RUN_POLICIES: Map<String, ToolRunPolicy> = mapOf(
    // shell 命令有自己的命令级确认与用户交互窗口：长超时不重试。
    "ShellExecute" to ToolRunPolicy(timeoutMs = 120_000L, maxRetries = 0),
    // 抓屏/敲链可能被系统限速：给一次重试余量。
    "Screenshot" to ToolRunPolicy(timeoutMs = 30_000L, maxRetries = 1, baseRetryDelayMs = 500L),
    // ═══ P0 修复（超时错配）：TerminalExec 自身管理 timeout_ms
    // （schema 声明 1000..600000ms，默认 30s），但默认推断策略是
    // mutating() 60s —— 执行器 withTimeout 会先于工具自身的超时
    // 逻辑杀掉长命令，模型明明要了 300s 却在 60s 收到
    // "Error: timeout: tool call exceeded 60000ms budget"。
    // 对齐工具自身上限（600s + 10s 余量），不盲重试。
    "TerminalExec" to ToolRunPolicy(timeoutMs = 610_000L, maxRetries = 0),
    // legacy TerminalExecLegacy 默认 timeoutMs 120s（可更高），同样被
    // 60s mutating 策略截杀。
    "TerminalExecLegacy" to ToolRunPolicy(timeoutMs = 610_000L, maxRetries = 0),
    // Ubuntu rootfs 安装 / Linux bootstrap 是长时下载+解压操作
    // （完整 rootfs 300MB+，真机上可达十余分钟），60s 必杀。
    "TerminalUbuntuInstall" to ToolRunPolicy(timeoutMs = 1_200_000L, maxRetries = 0),
    "TerminalLinuxBootstrap" to ToolRunPolicy(timeoutMs = 1_200_000L, maxRetries = 0),
    // 包安装（apt install）也可能超过 60s。
    "TerminalLinuxPackages" to ToolRunPolicy(timeoutMs = 600_000L, maxRetries = 0),

    // ═══ P0 修复（Ubuntu 探索验证 · 超时错配批次 2）：环境入口工具 ═══
    // 以下工具全部自身管理长预算，但推断策略为 mutating() 60s ——
    // Agent 调用即 60s 必杀（此前从未被覆盖，环境链路事实不可用）。
    // TerminalUbuntuEnsure：一发式 install→bootstrap→capability 快照，
    //   内部预算 DEFAULT_ENSURE_TIMEOUT_MS = 30min（1800s）。
    "TerminalUbuntuEnsure" to ToolRunPolicy(timeoutMs = 1_830_000L, maxRetries = 0),
    // TerminalWorkspaceEnvironment ensure：lifecycle ensureReady（≤900s
    //   内部预算）+ 批量 apt install 工具链 + 复测。
    "TerminalWorkspaceEnvironment" to ToolRunPolicy(timeoutMs = 960_000L, maxRetries = 0),
    // TerminalLinuxCapabilities ensure：refresh probe → apt install
    //   （600s apt 超时）→ invalidate + re-probe。
    "TerminalLinuxCapabilities" to ToolRunPolicy(timeoutMs = 660_000L, maxRetries = 0),
    // TerminalLinuxRepair：单轮修复可含 rootfs 重解包（分钟级 proot 操作）。
    "TerminalLinuxRepair" to ToolRunPolicy(timeoutMs = 1_260_000L, maxRetries = 0),
    // TerminalLinuxNetwork diagnose：端到端探针是一次真实 apt-get update
    //   （内部 apt 超时 600s）—— 慢网络下远超 60s。
    "TerminalLinuxNetwork" to ToolRunPolicy(timeoutMs = 660_000L, maxRetries = 0),
    // TerminalLinuxStatus 全量 6 维检查：每维一次 proot exec（10~30s 级），
    //   全量可达数分钟（quick=true 轻量）。
    "TerminalLinuxStatus" to ToolRunPolicy(timeoutMs = 300_000L, maxRetries = 0),
    // TerminalDiagnostics（T87）：会话/后端面秒回；smokeTest=true 含一次
    //   真实 exec 探针（最坏 600s —— 与 TerminalExec 同预算）。
    "TerminalDiagnostics" to ToolRunPolicy(timeoutMs = 660_000L, maxRetries = 0),
    // TerminalWait：schema 无 timeoutMs 上限，模型可请求 > 60s 等待
    //   （默认 60s 恰好撞 mutating 预算线）。给足等待自身上限 + 余量。
    "TerminalWait" to ToolRunPolicy(timeoutMs = 660_000L, maxRetries = 0)
)

/**
 * ═══ 机关（Jiguan V3.0）工具的执行器策略 ═══
 *
 * 机关工具自带内部预算（Hook 观察窗 = 自管 wait_seconds × 1s + CLI 收尾
 * 余量；抓包 start/stop 各一次 root 调用）。执行器预算 = 工具自身上限
 * + 余量（与终端族同原则），不盲重试 —— 内存写入/Hook 均非幂等。
 */
val JIGUAN_TOOL_RUN_POLICIES: Map<String, ToolRunPolicy> = mapOf(
    // 扫描：region 迭代 + 逐块 dd 读取（rw 区总量可达数十 MB）—— 分钟级。
    "JiguanMemoryScan" to ToolRunPolicy(timeoutMs = 300_000L, maxRetries = 0),
    // Hex 读取：单次 dd 对齐窗口（≤4KB）+ root 通道往返 —— 60s 富余。
    "JiguanMemoryHexDump" to ToolRunPolicy(timeoutMs = 90_000L, maxRetries = 1, baseRetryDelayMs = 500L),
    // 内存写入：非幂等（写前快照在结果里返回，不自动重放）。
    "JiguanMemoryWrite" to ToolRunPolicy(timeoutMs = 60_000L, maxRetries = 0),
    // 类枚举 / Hook / 调用 / 硬件断点 / SSL 绕过：frida CLI 会话
    //（自身管理 wait_seconds 上限 60s + 启动/收尾）。
    "JiguanJavaClasses" to ToolRunPolicy(timeoutMs = 120_000L, maxRetries = 0),
    "JiguanHookJavaMethod" to ToolRunPolicy(timeoutMs = 150_000L, maxRetries = 0),
    "JiguanCallJavaMethod" to ToolRunPolicy(timeoutMs = 90_000L, maxRetries = 0),
    "JiguanHookNativeFunc" to ToolRunPolicy(timeoutMs = 150_000L, maxRetries = 0),
    "JiguanHardwareBreakpoint" to ToolRunPolicy(timeoutMs = 240_000L, maxRetries = 0),
    "JiguanSslBypass" to ToolRunPolicy(timeoutMs = 90_000L, maxRetries = 0),
    // 反汇编：内存 dump（≤2KB）+ objdump（PRoot 内一次进程冷启动）。
    "JiguanDisassemble" to ToolRunPolicy(timeoutMs = 120_000L, maxRetries = 0),
    // 抓包：start/stop/status 三个轻面（tcpdump 拉起 + pkill + 稳定窗轮询）。
    "JiguanPacketCapture" to ToolRunPolicy(timeoutMs = 120_000L, maxRetries = 0),
    // 进程 / 模块 / 系统巡检 / 能力报告 / 配方 / 知识：只读快面。
    "JiguanProcesses" to ToolRunPolicy(timeoutMs = 60_000L, maxRetries = 1, baseRetryDelayMs = 300L),
    "JiguanModules" to ToolRunPolicy(timeoutMs = 60_000L, maxRetries = 1, baseRetryDelayMs = 300L),
    "JiguanSystemInspect" to ToolRunPolicy(timeoutMs = 60_000L, maxRetries = 0),
    "JiguanCapabilityReport" to ToolRunPolicy(timeoutMs = 90_000L, maxRetries = 0),
    "JiguanRecipes" to ToolRunPolicy(timeoutMs = 10_000L, maxRetries = 0),
    "JiguanKnowledgeQuery" to ToolRunPolicy(timeoutMs = 30_000L, maxRetries = 0),
    "JiguanKnowledgeRemember" to ToolRunPolicy(timeoutMs = 30_000L, maxRetries = 0),
    // Root 输入注入：input tap/swipe 秒级。
    "JiguanUiTouch" to ToolRunPolicy(timeoutMs = 30_000L, maxRetries = 0),
)

/**
 * ═══ 市场目录检索工具的执行器策略（P0 超时错配修复）═══
 *
 * 背景（用户实测 app-20261017 日志）：MarketSearch 拉取官方技能/目录双源
 * （GitHub raw / hub index），注入的 OkHttp 客户端 connect 15s + read 30s；
 * readOnly() 注解经 DefaultToolRunPolicyResolver 推断为 quickRead() ——
 * **30s 执行预算先于工具自身的网络超时窗口杀掉调用**，慢网下必然
 * "Error: timeout: tool call exceeded 30000ms budget"。SkillSearch（GitHub
 * API 检索）同理落在推断的 60s 线附近。
 *
 * 原则（与终端族同款）：执行器预算 = 工具自身内部预算 + 余量；不盲重试
 * （引擎侧瞬时失败阶梯已带退避重试，执行器层再重试只会叠加等待）。
 */
val MARKET_TOOL_RUN_POLICIES: Map<String, ToolRunPolicy> = mapOf(
    // kind=all 顺序拉双源（skills + mcp servers），每源最坏 15+30s → 90s，加余量。
    "MarketSearch" to ToolRunPolicy(timeoutMs = 120_000L, maxRetries = 0),
    // 单次 GitHub API 检索（最坏 45s）+ 本地模板匹配。
    "SkillSearch" to ToolRunPolicy(timeoutMs = 90_000L, maxRetries = 0)
)

/**
 * ═══ P2 修复（开放世界工具族超时错配）：前缀策略表 ═══
 *
 * 背景：browser_*（15 个）/ McpCall / mcp__server__tool（动态一等工具）/
 * ConnectorSendMessage 按注解推断为 mutating() —— 60s 硬超时 + 0 重试：
 * - 慢网页操作 / 慢 MCP 服务端 > 60s 即被执行器 withTimeout 强杀，而强杀
 *   可能留下半执行的副作用（点击已发生、消息已发出），模型看到超时
 *   重试 → 二次副作用；
 * - Mcp{Server}{Tool} 动态 id 无法逐个列进静态覆盖表 → DefaultToolRunPolicyResolver
 *   新增前缀族覆盖（最长前缀胜出）。
 *
 * 原则：对非幂等工具宁可放大超时也不要早杀（宁可慢也不重复执行副作用）。
 */
val OPEN_WORLD_PREFIX_TOOL_RUN_POLICIES: Map<String, ToolRunPolicy> = mapOf(
    // MCP 动态一等工具（Mcp{Server}{Tool}）：走远端/沙箱进程，慢服务器常见；
    // 不重试（非幂等风险由具体工具自查），只放宽超时。
    "Mcp" to ToolRunPolicy(timeoutMs = 200_000L, maxRetries = 0),
    // 浏览器自动化族：页面加载/滚动/下载都是秒级~十秒级网络操作。
    "Browser" to ToolRunPolicy(timeoutMs = 120_000L, maxRetries = 1, baseRetryDelayMs = 1_000L),
    // MCP 元工具 McpCall：远端 JSON-RPC / 沙箱 npx 冷启动（180s 握手放宽口径）。
    "McpCall" to ToolRunPolicy(timeoutMs = 200_000L, maxRetries = 0),
    // 连接器消息发送（Telegram/邮件等第三方网关）：网络慢常见；不盲重试
    // （消息重复发送比超时更糟）。
    "Connector" to ToolRunPolicy(timeoutMs = 120_000L, maxRetries = 0)
)
