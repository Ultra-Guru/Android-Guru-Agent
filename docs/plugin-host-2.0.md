# Native Plugin Host 2.0 — 跨生态原生插件运行时

> 状态：Phase 0+1 已落地（本档随各阶段 PR 增量更新）。
> 源起：市场插件生态（Claude / OpenCode / DSH / MCP）「能装不能用」的
> 架构级补课 —— 安装、解析、运行、API 兼容是四个不同层级。

## 1. 问题定义

2026-10 市场重构后，统一扩展模型（`core/tools/extension/`）已把各生态的
**安装**收口到 resolve→inspect→install 单一闸门。但审计（基线
`aa104f64`）确认了三个断层：

| 断层 | 现状 | 后果 |
|------|------|------|
| OpenCode 无插件运行时 | `ExtensionRun` 只做 `node <entry>` 一次性执行 | 官方 SDK 插件（`Plugin.define` + ctx + hooks）装了也不能加载 |
| Claude 仅内容转换 | skills/commands/agents 转宿主结构；hooks / `.mcp.json` / `.lsp.json` 跳过 | 事件驱动与 MCP 声明能力丢失 |
| 无安装前判定 | inspect 不回答「装完能不能用」 | 用户装完才发现插件不可运行 |

## 2. 目标架构

```
Agent Core（ToolRegistry / SkillRegistry / FusionBundle / McpManager）
        ▲
        │ Unified Capability Bridge（Phase 3）
        │   工具注册 · 事件总线 · 存储桥 · 权限门
        │
  Ecosystem Loaders（Phase 4/5）
        │   OpenCode V1/V2 Loader · Claude Hook/MCP/LSP Adapter · DSH
        │
  Plugin Worker Runtime（Phase 2）
        │   持久化 Worker · JSON-RPC IPC · Supervisor 崩溃恢复
        │
  Android 执行层（既有）
        PRoot Ubuntu · Node/Bun · MCP STDIO 进程
```

原则（继承自本仓既有纪律）：

1. **账本不重建** —— `ExtensionRegistry` 仍是安装账本；Plugin Host 只做
   运行时视图（`PluginDescriptor.fromExtensionManifest` 单向映射）。
2. **工具不另起炉灶** —— 插件工具经 Tool System v4/v5（名称清洗、
   Schema 清洗、渐进披露、请求预算）进入 `ToolRegistry`。
3. **MCP 一等复用** —— 插件声明的 MCP 服务器走既有 `McpManager`，
   带 `sourcePluginId` 来源归属。
4. **诚实边界** —— 兼容性矩阵（`PluginEcosystemMatrix`）只记录真实
   实现状态；PLANNED 能力在报告中如实展示「装了也用不上」。

## 3. 核心模型（Phase 1 已落地）

`core/tool-registry/src/main/kotlin/com/apex/agent/core/tools/pluginhost/`：

| 类型 | 职责 |
|------|------|
| `PluginDescriptor` | 统一运行时描述符：生态 / ABI / 来源 / 入口 / 依赖 / 能力面 / 运行时需求 |
| `PluginEcosystem` | CLAUDE / OPENCODE_V1 / OPENCODE_V2 / DSH / MCP / FUNCTIONAL / APK（V1/V2 是不同 ABI，不允许合并解释） |
| `PluginCapability` | 能力槽位（TOOLS/HOOKS/EVENTS/STORAGE/CONTEXT/SKILLS/COMMANDS/AGENTS/MCP_SERVERS/LSP/TUI/GUI）—— 统一的是槽位，不是协议 |
| `PluginRuntimeKind` | NATIVE_KOTLIN / NODE / BUN / PYTHON / SHELL / MCP_PROCESS（Bun 与 Node 不可混同） |
| `PluginLifecycleState` | 14 态状态机（DISCOVERED→…→ACTIVE→…→STOPPED + 异常态），带 `canTransitionTo` 防乱序 |
| `PluginEcosystemMatrix` | 生态 × 能力支持矩阵 —— 兼容性分析的单一事实源 |
| `PluginCompatibilityAnalyzer` | 安装前评级引擎（A/B/C/D/F），评级只依据真实实现与真实探测 |

## 3.1 Worker 运行时（Phase 2 已落地）

`.../pluginhost/worker/`：

| 组件 | 职责 |
|------|------|
| `WorkerProtocol` | 换行 JSON IPC 信封（protocol/plugin-host v1）：hello/ready、load/loaded、tool.execute/tool.result、event.emit/plugin.event、ping/pong、cancel、shutdown、fatal —— STDOUT 纯协议、日志全走 STDERR |
| `PluginWorkerBootstrap` | Worker 侧 JS 引导脚本（通用插件模块契约：ESM/CJS 的 tools 导出 + 可选 setup）；console.log 重定向 STDERR，TS 入口在 node 下如实拒绝 |
| `PluginWorker` | 持久化 Worker：读循环 requestId 关联分发、写互斥、STDERR 环形缓冲、退出看护（NonCancellable 终结在途请求）、优雅停机/强杀 |
| `PluginWorkerSupervisor` | 崩溃看护：窗口计数重启策略 + 上限 GaveUp + 重启后 handshake/load 重放 + 用户 stop 不触发重启 |
| `WorkerProcessPort` / `LocalProcessWorkerPort` | 长驻进程生成端口（JVM 直启实现供 CI/桌面；Android 走 PRoot 通道，Phase 6 接线） |
| `RuntimeProvider`（Node/Bun） | 运行时探测与启动命令构造 —— Node 与 Bun 探测/命令分离，缺失如实报告，禁止互相冒充 |

验收锚点（真实 node E2E）：完整 IPC 链路、工具热调用复用 Worker、错误隔离（工具抛异常进程不死）、
在途请求随进程死亡失败而非悬挂、崩溃→自动重启→调用恢复、重启预算耗尽→GaveUp、用户 stop 不重启。

## 4. 兼容性等级

| 等级 | 含义 | 判定依据 |
|------|------|----------|
| A | 官方 API 主要能力完整兼容 | 声明能力全 SUPPORTED |
| B | 核心能力兼容，部分非核心不支持 | 有 SUPPORTED 且有 PARTIAL/PLANNED |
| C | 转换后部分能力可用 | 有 PARTIAL 无 SUPPORTED |
| D | 只能安装或解析，无法真正运行 | 能力全 PLANNED/UNKNOWN，或运行时全缺 |
| F | 当前平台不可运行 | 全部声明能力 UNSUPPORTED（如 TUI/GUI-only） |

「能安装」不参与评级 —— 这是对「安装成功但实际不能用」问题的直接回应。

## 5. Phase 1 基线矩阵（审计结论）

### Claude Code 插件（Phase 5 后）

官方协议事实（2026-11 审计 code.claude.com/docs/en/hooks + plugins）：hooks.json 事件 →
匹配组（matcher 为工具名 regex）→ hook 条目（type=command，stdin JSON 入 / stdout JSON
出 / exit 0 成功 / exit 2 阻断）；`.mcp.json` 与桌面导出同构（`{mcpServers:…}`）。

| 能力 | 状态 | 说明 |
|------|------|------|
| SKILLS | SUPPORTED | ClaudePluginImporter → SkillRegistry（含资源文件） |
| COMMANDS | SUPPORTED | commands 目录 → FusionBundle 斜杠指令 |
| AGENTS | PARTIAL | 合并为人设/提示词；完整 Subagent 生命周期未实现 |
| HOOKS | PARTIAL | `ClaudeHookAdapter` 官方协议调度已实现（stdin JSON / exit code 语义 / hookSpecificOutput 解析 / matcher regex / 超时强杀，真实 sh E2E 锁）；安装链路接线待 Phase 6 |
| MCP_SERVERS | PARTIAL | `ClaudeMcpBridge` → 既有 McpConfigPort（stdio 沙箱 + http + 来源归属账本：卸载只删插件带来的，用户自建不动）；安装链路接线待 Phase 6 |
| LSP | PLANNED | `.lsp.json` 检测与诚实报告（识别但不启动不伪装）；统一 LSP Host 属后续独立子系统 |
| TUI / GUI | UNSUPPORTED | Android 无终端/桌面宿主 |

### OpenCode 插件（V1/V2 分立 ABI，Phase 4 后）

官方协议事实（2026-11 审计 docs/build/plugins + migrate-v1）：V2 = `@opencode/plugin` +
default export `Plugin.define({id, setup(ctx)})`；V1 = `@opencode-ai/plugin`（纯类型）+
具名异步导出返回 hook map。

| 能力 | V2 状态 | V1 状态 | 说明 |
|------|---------|---------|------|
| 安装（npm/github/local） | 已通 | 已通 | NodeFamilyInstaller → PRoot 沙箱 |
| ABI 探测 | ✅ | ✅ | SDK 依赖声明 + 导出形状，不按包名瞎猜 |
| 入口解析 | ✅ | ✅ | exports map → main → module → index 兜底 |
| TOOLS | SUPPORTED | UNKNOWN | V2：`ctx.tool.transform` 注册的工具一等注册进 ToolRegistry（E2E 锁）；V1 工具形状未审计 |
| HOOKS | PARTIAL | PARTIAL | `tool.execute.before/after`、`session.prompt` 经宿主事件转发；请求级 context/compaction 钩子未接 |
| EVENTS | PARTIAL | PARTIAL | `ctx.event.subscribe` 经宿主事件转发 |
| STORAGE | SUPPORTED | SUPPORTED | `ctx.storage` 插件目录 JSON 持久化（随卸载清理） |
| ctx.options / location / app | ✅ | ✅ | 官方形状注入 |
| ctx.$ / generate / model… | 如实拒绝 | 如实拒绝 | 调用即抛「not yet supported」描述错误，不静默假装 |
| TUI / GUI | UNSUPPORTED | UNSUPPORTED | 平台不可行 |

SDK 垫片：插件目录未安装 `@opencode/plugin` 时落盘最小实现
（`Plugin.define` 原样返回定义 —— 与官方客户端行为等价）；真实包已装时垫片不介入。

### MCP / FUNCTIONAL / APK

| 生态 | 结论 |
|------|------|
| MCP | TOOLS SUPPORTED（McpToolRegistrar 一等注册）；STDIO 依赖 PRoot 就绪 |
| FUNCTIONAL | TOOLS/COMMANDS SUPPORTED（plugin-system-v2，51 项契约锁） |
| APK | TOOLS SUPPORTED（AIDL + 同签名门禁） |

### Android 执行环境（Phase 0 结论）

- Node.js：Ubuntu rootfs 内可安装（`probeRuntime` 已通）—— ARM64 实机
  验证标记为「待设备复验」（CI 是 x86_64 JVM，不能冒充 ARM64 结论）。
- Bun：官方重要运行时，ARM64 Linux 存在但 PRoot 系统调用兼容性
  **未验证** —— 分析器对 Bun 缺失的降级说明明确写「不可默认以 node
  等价替代」。
- PRoot：非安全边界，是执行环境隔离手段 —— 插件权限门（Phase 3）
  在宿主桥接层做，不依赖沙箱兜底。

## 6. 阶段路线图

| Phase | 交付 | 状态 |
|-------|------|------|
| 0 | 现状审计 + 协议研究 + 兼容矩阵 | ✅ 本档 §5 |
| 1 | 统一核心模型 + 兼容性分析引擎 | ✅ |
| 2 | PluginWorker + 换行 JSON IPC + Supervisor 崩溃恢复 + Node/Bun RuntimeProvider | ✅（真实 node E2E 锁定） |
| 3 | Unified Capability Bridge + ToolRegistry 动态注册 + 生命周期管理器 | ✅（真实 node E2E 锁定） |
| 4 | OpenCode V1/V2 Loader + 官方 SDK 上下文桥 | ✅（官方形状 fixture 真实 node 锁定） |
| 5 | Claude Hooks + `.mcp.json` + `.lsp.json` 兼容 | ✅（真实 sh E2E 锁定；安装链路接线在 Phase 6） |
| 6 | DSH 审计 + App DI 接线 + Doctor/Inspect 集成 + 端到端链路 | 计划 |

每个 Phase 的矩阵状态迁移（PLANNED→SUPPORTED）必须同步更新
`PluginEcosystemMatrix` 与 `PluginHostModelTest` 契约锁 —— 评级永远
反映真实实现，禁止领先于实现。

## 7. 验收锚点（对齐 spec §20）

- 插件工具进入 ToolRegistry 且名称经 v5 清洗无冲突；
- Worker 崩溃不影响 Agent 主循环（Supervisor 隔离 + 重启上限）；
- 卸载后工具/事件订阅/进程/MCP 连接全清理（逆序十步）；
- 安装前兼容性报告如实（本 Phase 已落地）；
- 未实现能力明确标记，禁止把跳过的测试显示为通过。
