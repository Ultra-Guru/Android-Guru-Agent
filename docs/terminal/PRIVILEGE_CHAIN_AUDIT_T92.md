# T92 — 权限链审计（Issue #255：权限真的被 Agent 利用了吗）

> 特性文档（docs/terminal 惯例）。落地「比 Operit 强的一周冲刺」排期中的
> D6–D7：把三级权限链（Root → Shizuku → PRoot/沙箱）从「文档级能力」变成
> **可测试、可观测的运行时事实**。关联：T88（env trampoline）/ T91（AIDL
> 终端服务 + PRoot 方言）—— 本特性不重复其结论，只引用。

## 0. 审计结论（先回答 #255 的问题）

**权限链是真实、被 Agent 大量使用的一等公民，不是摆设**：

| 路径 | 链路 | 消费方 |
| --- | --- | --- |
| `ShellExecute` | Root(`su -c`) > Shizuku(IShizukuService.newProcess AIDL, uid=2000) > app-shell(`sh -c`) | `PrivilegeDetector.executeShell`（ToolModule 接线）+ **18 个设备工具**（AppList / AppLaunch / device_info / UiTap / screenshot / logcat…） |
| `TerminalExec` | PRoot Ubuntu（rootfs 就绪且非 Android 专有命令）> Root > Shizuku > local-sh | `ProotCommandSpawner` → fallback `PrivilegedCommandSpawner` → `ExecEngine` |
| PTY 会话 | `TerminalCreate backend=linux-ubuntu` → proot argv（T88 env trampoline） | NativePty forkpty + execv |

端到端自证入口：`TerminalDiagnostics {smokeTest:true}`（真实执行
`printf 'TERMINAL-EXEC-OK'`，回 `smoke.chainOk`）。

审计同时发现 **5 个可观测性/完整性缺口**，本 PR 逐条修复（§1–§5）。

## 1. 缺口① —— 无逐命令通道日志 → privilege-chain 审计日志三件套

**问题**：一条命令到底走了 root、shizuku 还是 shell，日志中枢里查不到 ——
`PrivilegeDetector` / `PrivilegedCommandSpawner` / `ProotCommandSpawner` 全都
不打日志。用户质疑 #255 时无法拿出运行时证据。

**修复**（日志格式：`privilege-chain …`，tag 统一 `privilege-chain` + 通道名，
日志查看器按 tag 一键筛出整条链）：

| 文件 | 日志 |
| --- | --- |
| `PrivilegeDetector.kt` | `privilege-chain via=root exit=0 42ms cmd=<截断命令>` —— 每次执行恰好一条；`executeShell` 拆为公共入口（计时+审计）+ `executeShellInternal`（选路） |
| `ProotCommandSpawner.kt` | `privilege-chain route=proot-ubuntu cmd=…` / `route=host-fallback reason=rootfs-not-ready\|android-only-command\|rootfs-unresolved cmd=…` |
| `PrivilegedCommandSpawner.kt` | `privilege-chain channel=root-su\|shizuku\|local-sh cmd=…`（fallback 时紧跟 route 日志，给出宿主链最终选路） |

纪律：日志 `runCatching` 包裹永不阻断执行；命令文本折叠空白 + 截断 120 字符
（防超长命令打爆 8MB 日志缓冲）。

## 2. 缺口② —— ROOT/SHIZUKU 环境旗标死遥测 → EnvironmentStateUpdater 接活

**问题**：`ToolEnvironmentState.Flags.ROOT_AVAILABLE / SHIZUKU_AVAILABLE`
定义后全仓无 setter —— Live Environment 摘要（模型每轮可见）永远不含
root/shizuku 状态，模型只能靠试探性执行命令来探权限（浪费轮次）。

**修复**：`EnvironmentStateUpdater` 新增两路收集（与 accessibility/ubuntu 同款
事件驱动模式），事件源是 `DefaultPrivilegeManager` 的 #212 binder 生命周期回灌：

```
privilegeManager.rootAvailable     → Flags.ROOT_AVAILABLE
privilegeManager.shizukuAvailable  → Flags.SHIZUKU_AVAILABLE
```

模型每轮看到 `environment: … root=on shizuku=off ubuntu=on …`。
**零门控风险**：当前无任何工具把这两个旗标声明为 `requiredEnv`（工具全部
自带降级链），接线后仅提升 prompt 可见性，不产生 ToolEnvironmentGate 拒绝
—— 与 fail-open 哲学一致。

## 3. 缺口③ —— ShellExecute 成功时通道对模型不可见

**问题**：`TerminalExec` 的结构化输出恒含 `channel` 字段，但 `ShellExecute`
只在失败时才带 `via=…` —— 成功执行走了哪条通道，模型与用户都看不到。

**修复**（ToolModule）：`shellExec` 重构为三层单源结构 ——
`shellExecResult`（门禁+执行，返回 `ShellExecResult`；via 另有 `gate-denied` /
`exception` 两个非执行器语义）→ `formatShellResult`（单源格式化）→
两个消费通道：

- `shellExecAudited`（仅 `ShellExecuteTool`）：成功输出尾部附
  `[executed via: root]`；
- `shellExec`（18 个设备工具）：输出与旧版**逐字节一致** —— AppList/DeviceInfo
  等对输出做行级过滤/计数/拼接，不受标记污染。

## 4. 缺口④ —— 死代码与假失败 stub

| 对象 | 问题 | 处置 |
| --- | --- | --- |
| `ShellStreamSource` / `ShizukuStreamAdapter` / `ProcessStreamFactory`（+ 其测试） | 流式 shell 死代码三件套：全仓零调用方（唯一引用是 ApexApp 一句注释），与真实链路（PrivilegeDetector / Spawner）并存误导读者 | 删除；ApexApp 注释同步改为指向 PrivilegedCommandSpawner |
| `DefaultPrivilegeManager.executeViaShizuku` | "Shizuku execution not yet implemented" 假失败 stub —— 任何误入的调用者拿到假结果，与「权限链真实可用」矛盾 | 委托真实执行器 `ShizukuCommandExecutor`（与主链路同一 AIDL 路径，行为一致） |
| `platform/privilege` → `core:tool-registry` 依赖 | 唯一消费方（ToolStreamEvent）随死代码删除 | 依赖移除 —— privilege 收紧为叶子模块（core:logging + Shizuku SDK + coroutines） |

## 5. 缺口⑤ —— 路由与门禁零单测 → 36 项回归锁

| 测试 | 覆盖 |
| --- | --- |
| `CommandPermissionGateTest`（core:agent-engine，15 项） | 良性放行零询问；rm/pm uninstall/`> /dev/` 原文命中；四类混淆防御（`VAR=1 rm` / `"r"m` / TAB / 换行 / 大写）；allow_once 不记忆 vs allow_session 记忆；skipped/超时折叠拒绝；归一化命令共享会话记忆 |
| `ProotCommandSpawnerTest`（app，18 项） | ANDROID_ONLY_COMMANDS 首 token 判定（引号/绝对路径/多行/sudo 保守语义）；host→guest cwd 映射表逐条（null→/workspace、home→/root、工作区 bind、同路径 bind、不存在→FileNotFoundException）；rootfs 路由门禁（未就绪/Android 专有/current 标记解析/标记损坏诚实回落/裸布局） |
| `PrivilegeDetectorAuditTest`（platform:privilege，3 项） | 端到端：executeShell 真实执行恰好产出一条审计日志（via/exit 与返回值一致）；消息形态：多行折叠单行、截断 120 + 省略号、tag 含通道名 |

CI：ci.yml 新增 `:platform:privilege:testDebugUnitTest` 步骤（该模块此前
不在 CI 测试矩阵中）。

## 6. 审计日志怎么用（面向 #255 的验收路径）

1. 让 Agent 执行任意 shell 命令（`ShellExecute` 或 `TerminalExec`）；
2. 打开日志查看器，按 tag `privilege-chain` 筛选：
   - ShellExecute：`via=root|shizuku|shell exit=… Nms cmd=…`
   - TerminalExec：`route=proot-ubuntu` 或 `route=host-fallback reason=…` +
     紧随的 `channel=root-su|shizuku|local-sh`；
3. 授予/撤销 Root、Shizuku 后重复 —— 通道选择即时变化（#12 binder 回灌
   已有），日志与 `environment:` 摘要同步反映；
4. E2E 自证：`TerminalDiagnostics {smokeTest:true}` 返回 `smoke.chainOk=true`。

## 7. 决策记录

- **不接 ToolTraceRecorder span**：审计日志落 AppLogger（8MB 环形缓冲 +
  查看器 + 按标签筛选）已满足「可查、可筛、可回放」；spawner 位于
  platform:privilege / app 装配层，拿不到 per-call 的 trace 上下文，
  强接需要穿透模块边界 —— 不做。
- **命令进日志的隐私权衡**：命令文本本就进入 CommandPermissionGate 的
  用户弹窗与 ToolTrace 记录，日志中枢是同信任级的设备本地存储；截断 120
  字符兼顾可读性与体积。
- **`[executed via: x]` 只给 ShellExecute**：设备工具的输出被行级解析
  （AppListTool 计数、DeviceInfo 拼接），尾部标记会污染 —— 拆两条消费
  通道而非全局追加。
