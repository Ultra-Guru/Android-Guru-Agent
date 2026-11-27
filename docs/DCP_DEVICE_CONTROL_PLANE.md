# DCP 设备控制面（Device Control Plane）

> 目标：**用一句命令 / 一句自然语言，让 agent 借 Shizuku 权限管理本地文件、卸载/停用软件、执行高级 Android 命令**，且全过程可预演（dry-run）、可审批（gate + confirm token）、可审计（jsonl 三链）、可回滚（软删 undo / rollbackHint）。
>
> 对应 spec：S01 能力自省 / S02 意图清单 / S03 统一风险门 / S04 Preflight·DryRun·令牌 / S05 包操作 / S06 文件操作 / S07 统一入口与路由 / S08 Shizuku 通道加固 / S09 guest 桥 / S10 审计与测试矩阵。

---

## 1. 一句话架构

```
自然语言 / Ubuntu guest（apexctl device）
        │
        ▼
  DeviceIntent（JSON：intent / params / dry_run / confirm_token）
        │
        ├─▶ DeviceCapability（S01）   当前档位 ROOT/SHIZUKU/NORMAL_SHELL + supports/denies
        ├─▶ intents.json（S02）       单一事实源：risk / argv / preflight / verify / rollback
        ├─▶ DeviceIntentGate（S03）   语义分级：CRITICAL 恒 Ask（BYPASS 不豁免）
        ├─▶ DevicePreflight（S04）    只读探针 → DeviceDiff → confirm_token 绑指纹
        ▼
  DeviceOpsExecutor（S05/S06/S07 唯一写执行点）
        │  argv 数组直调（S08：不经 sh -c）
        ▼
  ShizukuProcessChannel（IShizukuService.newProcess，uid=2000）
        │
        ├─▶ verify 复探（before/after 对比）
        └─▶ ToolAuditLogger（S10：preflight / approved / executed 三条同 auditId）
```

## 2. 模块与文件地图

| 层 | 文件 | 职责 |
|----|------|------|
| 清单 | `app/src/main/assets/device/intents.json` | **单一事实源**：31 条意图（package×12 / file×9 / system×6 / probe×4）的 risk / argv / preflight / verify / rollback / guards / params 白名单 |
| core:tool-registry `tools/device/` | `IntentModel.kt` | DeviceIntent / DeviceDiff / DeviceOpResult / CapabilityProbe / DeviceOpsPort（纯 JVM，零 android.* import） |
| | `IntentCatalog.kt` | 防御式解析（单条坏意图跳过不炸表）+ validate（与 CI 同口径）+ supports/denies 派生 |
| | `ArgvRenderer.kt` | 参数白名单校验 + 占位符渲染 + **shell 元字符拒绝**（argv 语义下 `;` `\|` `$` 反引号换行全非法） |
| core:tool-registry `builtin/` | `DeviceOpsTool.kt` | 统一入口（描述由 manifest 派生 intent 枚举） |
| | `DeviceCapabilityTool.kt` | 能力自省（LOW / readOnlyHint） |
| | `DeviceAuditQueryTool.kt` | 审计查询（按 intent / decision / auditId 回放） |
| | `AppToolsExtra.kt` | AppUninstall / AppForceStop 的 S05 委托（DCP 关 → 原链逐字节不变） |
| core:agent-engine | `DeviceIntentGate.kt` | 语义分级 + 会话记忆 + 用户规则（尾缀星号）最高优先 |
| platform:privilege `shizuku/` | `DeviceChannel.kt` | argv 直调契约（纯 Kotlin 数据 + 接口） |
| app `tools/` | `ShizukuDeviceChannel.kt` | 落地：newProcess argv 直调 / 有界采集 / 分片轮询 / 取消 / invalidateCache |
| | `DeviceCapabilityProbe.kt` | PrivilegeDetector + ShizukuCommandExecutor 薄适配 |
| | `DevicePreflight.kt` + `DiffParsers.kt` | 只读探针 → diff（跨版本解析允许缺失，parseIncomplete 如实标注） |
| | `DeviceOpsExecutor.kt` | **唯一写执行点**（12 步编排） |
| | `PackageOpsRunner.kt` / `FileOpsRunner.kt` | 包族（IS_SELF 双保险）/ 文件族（软删 / 备份 / undo） |
| | `DevicePathPolicy.kt` + `DeviceTrash.kt` | 路径白名单 / 回收区（manifest.json + undo_token） |
| | `DeviceTokenSigner.kt` | HMAC-SHA256（密钥持久化 app 私有目录） |
| | `ToolAuditLogger.kt`（扩展） | Event/AuditEntry 尾部追加 11 个可选字段（旧 jsonl 照常解析） |
| app `di/` | `DeviceControlModule.kt` + `ToolModuleDeviceControlRegistration.kt` | Hilt 装配（零构造期快照：flag / 模式 / 规则每次调用实时读取） |
| guest 桥 | `AndroidBridgeHandlers.kt`（device handler）+ `GuestBridge.kt`（.bashrc 托管块） | `apexctl device '<JSON>'` 与 Agent 侧同一 port |
| CI | `scripts/check_intents.py` + quality-gate 步骤 | manifest 结构门禁（与 runtime validate 同口径） |

## 3. 四条不变式（实现落点）

1. **单一事实源**（S02/S07）：risk / argv / preflight / verify / guards 只存在于
   intents.json；`ProotCommandSpawner` 的 Android 专有命令集合 = 历史底线 ∪
   manifest 派生（`AndroidOnlyCommandsConsistencyTest` 锁定只增不减）；
2. **argv 而非字符串**（S08）：高危意图一律 `arrayOf("pm","uninstall",...)`
   逐元素经 `IShizukuService.newProcess` 直调；`ArgvRenderer` 拒绝一切 shell
   元字符 —— DeviceOps 路径 grep 不到 `sh -c` 拼接；
3. **不可逆必预演**（S04）：`reversible=false`（及 purge 变体）必须先
   `dry_run=true` 拿 `confirm_token`（绑定 diff 指纹 + 5 分钟窗口），
   执行前重跑 preflight 比对（TOCTOU：不一致返回 `STALE_CONFIRM_TOKEN` + 刷新 diff）；
4. **纯 additive**：旧 19 个设备工具一个不删（`AppUninstall`/`AppForceStop` 委托后
   返回文本向后兼容），旧通道 / 旧门 / 旧策略全部保留。

## 4. 安全不变式（I1–I6）

- **I1** 不可逆意图必有非空 preflight + verify + rollbackHint —— CI 与 JVM 测试双保险；
- **I2** confirm_token = `v1.<ts>.<HMAC(intentId+before+flags+argv|ts)>`，窗口
  `defaults.confirmWindowMs`（5 分钟，假钟测试覆盖过期分支）；审计只落指纹前 8 位；
- **I3** `IS_SELF` / `DEVICE_ADMIN` / `IS_LAUNCHER` 命中即硬拒
  （`GUARD_REJECTED`），不可被 BYPASS 覆盖；`PackageOpsRunner` 再做一次
  防御性 IS_SELF 检查（双保险，不依赖 dumpsys 解析结论）；
- **I4** `file.delete` 默认软删（`mv` 到 `/data/local/tmp/.dcp-trash/<auditId>/`
  + manifest.json），返回 `undo_token`；`purge=purge` 才硬删（走 manifest 的
  `purgeArgv`，按不可逆档走 token + Ask 双确认）；
- **I5** 所有输出有界（首 N + 尾 M，`truncated=true` 如实标注）；
- **I6** 拿不到权限**只诚实报出**：`DEVICE_PERMISSION_REQUIRED` + 当前档位 +
  开通步骤，绝不降级本地 shell 冒充成功（沿用 `ShizukuCommandExecutor` 纪律）。

## 5. 风险分级表（S03，`DeviceIntentGate`）

| 档位 | 行为 |
|------|------|
| 用户 DENY 规则（尾缀星号通配 intentId） | 恒拒（BYPASS 不豁免） |
| 用户 ASK 规则 | 恒问 |
| PLAN 模式 | 写意图拒（零副作用承诺）；dry_run / READ 放行 |
| CRITICAL 或 `reversible=false`（含 purge） | **恒 Ask，BYPASS 也不例外**；仅 `allow_once`（无 allow_session 选项） |
| HIGH（可逆） | Ask（allow_session 生效，会话记忆） |
| MEDIUM / LOW / READ | BYPASS / DEFAULT / ACCEPT_EDITS 放行 |

自由文本命令**不进新门**：`ShellExecute` / `TerminalExec` 照旧走
`CommandPermissionGate`（`rm -rf /sdcard/x` 照样弹确认 —— 零回归）。

## 6. 一次「卸载预装应用」的完整数据流

1. LLM 调 `DeviceCapability {}` → `SHIZUKU / uid=2000 / supports / denies`；
2. LLM 调 `DeviceOps {"intent":"package.uninstall_for_user","params":{"package":"com.x","user":"0"},"dry_run":true}`；
3. 执行器：查表 → 档位 → 参数渲染（包名白名单）→ **只读探针**
   （`pm list packages` / `dumpsys package` / `cmd package dump` /
   `dumpsys device_policy` / `cmd shortcut get-default-launcher`）→
   DeviceDiff（before / flags / argvPreview）+ `confirm_token`；
   `IS_SELF` / `DEVICE_ADMIN` / `IS_LAUNCHER` 任一命中 → `GUARD_REJECTED` 硬拒；
   `SYSTEM_APP` → 返回里附 `package.disable_user` 建议但不擅自改意图；
4. 用户确认后，LLM 带 token 再调；执行器重跑 preflight 比对指纹 ——
   一致 → gate Ask（弹窗展示影响面）→ 允许 →
   `["pm","uninstall","-k","--user","0","com.x"]` argv 直调 → verify 复探 →
   审计三条（preflight / approved / executed）同 `auditId`；
5. Ubuntu guest 内：`apexctl device '{"intent":...}'` 走**同一个** port
   （同一 preflight / gate / 审计；PTY 里敲 `pm` 会得到 `.bashrc` 托管块的
   `apexctl device` 引导 —— GuidedBridge 决策，改 PTY 后端为非目标）。

## 7. 兼容、灰度与回滚

- **Feature flag**：`SettingsRepository.AgentSettings.dcpEnabled`（默认 **false**；
  设置 → 扩展与服务 → 设备控制面）。
  关闭 = 全仓行为与 main 一致（旧设备工具走原 shellExec 链；`DeviceOps` 返回
  结构化 `DCP_DISABLED` 提示，**不静默降级**旧链 —— 审计链不断）；
- **旧工具委托**：`AppUninstall` / `AppForceStop` 注入 port 后内部走
  `executeLegacy`（单调用：preflight → 守卫 → gate Ask（弹窗含完整 diff）→
  执行 → verify），返回文本保持 `Success` / `Error:` 前缀（旧提示词零改动）；
  这保持了旧链单弹窗 UX 的同时叠加了 preflight 守卫；
- **审计兼容**：`ToolAuditLogger` 新字段全部可选 + 默认值，历史 jsonl 照常
  解析（`ToolAuditLoggerCompatTest` 用旧格式 fixture 锁定）；轮转阈值语义未动；
- **回滚路径**：关 `dcpEnabled` 即回旧行为；`.bashrc` 托管块可删（幂等安装会
  重建）；manifest 单条坏意图运行时跳过（CI 前移拦截）。

## 8. 诚实边界（NOT VERIFIED 纪律）

- `pm uninstall --user 0` 对预装应用的实际效果、`pm install-existing` 恢复、
  Shizuku 13.x `newProcess` argv 语义、Android 11+ 分区存储下 uid=2000 的
  `/sdcard` 读写、跨挂载点 mv、`dumpsys device_policy` /
  `cmd shortcut get-default-launcher` 跨版本输出格式、PTY 内 `.bashrc` 加载 ——
  **全部标注 NOT VERIFIED**（真机验证清单见 `docs/TESTING.md` §9.3）；
- Shizuku 是 uid=2000 的 ADB 级，不是 root：`/data/data/<其它包>`、`/system`
  写入拿不到就是拿不到 —— DCP 只承诺**诚实报出拿不到**（I6），不承诺越权。

## 9. 设计决策记录（对 spec 的微调与理由）

| 决策 | 理由 |
|------|------|
| `DeviceCapabilityProbe` 实现放 app 层（spec 原文放 platform:privilege） | `platform:privilege` 是刻意维持的**叶子模块**（build 注释明言"无引擎/工具依赖"），而 probe 需要 IntentCatalog（core:tool-registry）派生 supports/denies；10-ARCHITECTURE.md 分层图本就把它画在 app 层 |
| `DeviceIntentGate` 用 core 侧 `DeviceGateMode/Rule` 而非直接引用 app 层 `PermissionMode/PermissionRule` | core:agent-engine 不能依赖 app 层类型；语义一一对应，DI 一行映射 |
| S05 的 `DevicePackageOpsTool` 并入 S07 的统一入口 `DeviceOps` | S07（后续 spec）明确"一个 DeviceOps 统一入口承载全部 intents.json"；分开落地会造成 3 个重叠工具 |
| PTY 断点选 **GuidedBridge**（提示引导）而非 AutoFallbackToHost | S07 推荐（"改动最小、不破坏 PTY 语义"）；落地为 `.bashrc` 托管块 + `apexctl device` handler + TerminalExec/ShellExecute 描述引导 |
| 旧工具委托走 `executeLegacy`（单弹窗）而非 token 两步 | 旧链的 CommandPermissionGate 本就是单弹窗 UX；S05 要求"返回文本仍能被旧提示词消费"；两步流程留给 DeviceOps 主入口 |
| `ANDROID_ONLY_COMMANDS` 保留历史底线并集 manifest 派生（而非纯派生） | 纯派生会**删掉** input/wm/screencap/logcat 等 18 个历史名 → TerminalExec 回落语义回归；checklist 允许"保留 + 一致性测试"路线 |
| 审计依赖经 `DeviceAuditSink` fun interface 注入 | ToolAuditLogger 构造需 Context；fun interface 让 JVM 测试手写 recording fake（无 mock 框架纪律） |
