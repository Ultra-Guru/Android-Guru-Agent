# Agent ↔ Terminal 调用链（T87 自证指南）

> 回答一个直接的问题：**「这个项目的终端真的能被 Agent 调用吗？」**
> 能 —— 而且现在它自己就能证明（`TerminalDiagnostics` 的 smokeTest）。

## 1. Agent 可用的终端工具全集（core-tier 常驻 + 可发现）

会话流（交互式 PTY）：
| 工具 | 用途 |
|------|------|
| `TerminalCreate` | 创建会话（backend=local / linux-ubuntu） |
| `TerminalRun` | 会话内跑命令（OSC 633 marker → 真实退出码） |
| `TerminalWrite` / `TerminalObserve` / `TerminalWait` / `TerminalSignal` / `TerminalResize` / `TerminalSnapshot` / `TerminalClose` | 输入 / 观察 / 等待 / 信号 / 尺寸 / 快照 / 关闭 |
| `TerminalExec` | 一次性执行（自动路由：rootfs 就绪 → proot Ubuntu；否则 su>Shizuku>local） |

环境流：
| 工具 | 用途 |
|------|------|
| `TerminalBackends` | 后端能力发现（READY/NEEDS_ROOTFS/FAILED） |
| `TerminalUbuntuInstall` / `TerminalUbuntuEnsure` / `TerminalUbuntuStatus` | rootfs 安装/就绪/状态 |
| `TerminalLinuxBootstrap` / `TerminalLinuxPackages` / `TerminalLinuxNetwork` / `TerminalLinuxCapabilities` / `TerminalLinuxRepair` / `TerminalLinuxStatus` | apt 引导 / 包管理 / 网络诊断 / 能力探测 / 修复 |
| `TerminalWorkspaces` / `TerminalWorkspaceEnvironment` / `TerminalFs` / `TerminalBridge` | 工作区 / 文件桥 |
| **`TerminalDiagnostics`（T87 新增）** | **一发起诊断 + smokeTest 自证 exec 链路** |

## 2. 调用链（TerminalExec → proot Ubuntu）

```
Agent LLM → tool call TerminalExec {command: "python3 -V"}
  → core:tool-registry SafeAgentTool → TerminalToolAdapter（模块边界适配）
  → platform:terminal TerminalExecTool → ExecEngine
  → ProotCommandSpawner（rootfs 就绪且非 Android 命令 → channel=proot-ubuntu）
  → ProotExecutor：ProcessBuilder(proot -r rootfs … python3)   ← 无 PTY 一次性
  → 输出采集（head+tail 预算）→ CommandResult → JSON → Agent
```

会话式（交互）：
```
TerminalCreate {backend: "linux-ubuntu"}
  → TerminalRuntimeImpl.create → LinuxPRootBackend.prepare（argv/env/binds）
  → SessionManagerImpl.createFromSpec
      → NativePty.nativeCreateSessionArgv → JNI forkpty → execv(proot argv)
      → [T87] nativeGetSpawnError 探测：exec 失败 → TerminalError:ExecFailed（当场揭穿）
      → 装配（VT + pump + 观察引擎 + exit watcher）
  → Agent 拿到 sessionId → run/write/observe/wait …
```

## 3. 怎么自证（30 秒）

让 Agent 调：

```json
{ "tool": "TerminalDiagnostics", "input": { "smokeTest": true } }
```

返回里的 `smoke.chainOk=true` + `smoke.output` 含 `TERMINAL-EXEC-OK` = **exec 链路端到端可用**
（探针与 TerminalExec 共用同一 ExecEngine/Spawner 构造）。会话/后端异常时 `nextActions`
直接给出可执行动作（如 `TerminalUbuntuInstall`）。

## 4. 历史遗留语义（维护者备忘）

- legacy 别名 `TerminalExecLegacy` / `TerminalSendLegacy` / `TerminalReadLegacy` / `TerminalListLegacy` 仍在册（@Deprecated，永不发给模型 —— tier 策略 LEGACY_ALIAS_IDS）；
- `ShellExecute`（core:tool-registry builtin）是 **Android 宿主**一次性执行（su>Shizuku>sh），与 `TerminalExec`（可路由 proot）职责不同；
- `code_git_*` 走 ProotGitCommandRunner（Ubuntu 内 git）；MCP stdio 服务器经 ProotMcpProcessLauncher 进沙箱 —— 都是同一 proot 基座的消费者。
