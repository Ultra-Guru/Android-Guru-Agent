# T92 · T91 审计批次 —— D5 能力贯通收口 / 探针前置 prepare / IPC 输出链防线

> 对 PR #283（T91 Operit 对齐三件套）的**代码级审计后续**：五处确认缺陷的
> 根治记录。方法学：逐文件核对 T91 合并实现与实证事实（本机 Debian
> proot 5.4.0 实测 + Termux/upstream 源码核对 + 全部调用点枚举），
> 每项缺陷均有确定性证据与回归测试锁定。

## 缺陷 → 根因 → 修复对照表

| # | 缺陷 | 根因（T91 合并态） | 修复 |
|---|------|---------------------|------|
| 1 | **能力接线只覆盖 1/7 调用点**（D5 完成度缺口） | `LinuxPRootBackend` 正确消费 verify 能力，但 apt / capability-probe / fs / spawner / git / MCP 六条 argv 构造路径：五个默认 `TERMUX_BUNDLED`（builder 第 5 参默认），**MCP launcher 干脆手工硬编码 `--kill-on-exit` 与 `--`**（不走 builder）—— 二进制升级/替换后终端会话正常而其余六路径全部拒启（-E 事故同构残留面） | ① platform 侧：`LinuxExecutionContextFactory` 不再丢弃 verify 产物，能力随 `LinuxExecutionContext` 流转（apt/probe/fs 三消费方统一接入）；apt 自己的 verify 产物直接参与 build；② app 侧：新增 `PRootCapabilitySource`（构造即后台预取 + 非挂起读取 + 预取窗口保守省略），spawner/git/MCP 三类注入，MCP 手工 argv 改双旗标能力门 |
| 2 | **探针先于 `hostEnv.prepare()` 执行 → 首启静默降级** | 三探针直接 `hostEnv.hostEnv()`（其 KDoc 契约要求先 prepare）；首装/清缓存后 `libtalloc.so.2` symlink 与 `PROOT_TMP_DIR` 不存在 → exec 因动态链接失败**确定性非零退出（false 而非 null）** → 记忆化 `UPSTREAM_SAFE` → **整个进程生命周期**设备 argv 静默丢两旗标（与 T91「设备 argv 逐字节不变」承诺冲突；bootstrap APT_UPDATE 是首个触发点） | `probeEnv()`：幂等 prepare 成功才取 env；**prepare 失败 → 探针返回 null（不可判定）绝不 exec** —— 环境未就绪 ≠ 不支持；脚本级 JVM 测试锁定（含「可执行陷阱脚本」回归检测） |
| 3 | **oneway 回调失败被无限吞 → 输出静默丢失不可检测** | 「binder 异步事务缓冲天然背压」是错误认知 —— oneway 缓冲（~512KB-1MB/进程）耗尽时事务**直接失败**而非阻塞；`runCatching` 全吞 + `forwardOutput` 失败后继续派发后续块 → `cat` 大文件 + 256KB 大分片 + N 客户端放大 → 输出块静默丢弃、流撕裂 | 连续失败计数与剔除（16 次连续失败 ≈ 1MB 未送达 = 事实死客户端；成功即清零防误杀）；`maxOutputChunkBytes` 256KB → 64KB（多客户端缓冲占用降为 1/4）；`unregisterCallback` 同步清计数 |
| 4 | **registerCallback 回放语义声明与实现不符** | KDoc 承诺「已知会话从诞生起重放」，但 `putIfAbsent` 单收集器多播架构下，注册时已有收集器的会话只是**续流**（重放会把历史重复推给在场客户端） | 按实际语义重写声明（「已有收集器 → 续流；无收集器 → 从诞生重放」）；per-callback 锚点重构列为后续路线（零消费者阶段不做过度设计） |
| 5 | **本地验证链断裂**（`compile_terminal_jvm.sh` 在 T91 后必红） | T91 新增的 `TerminalService.kt`/`TerminalServiceClient.kt`（Service/Notification/IBinder 等框架 API）超出脚本的 Log stub 覆盖范围 —— T91 作者本地未跑该脚本（验证表自认「待远端 CI」） | 脚本以文件清单参编（显式排除两个 Android 壳，注释说明理由与登记纪律）；纯 JVM 的 `TerminalIpcController`/`Registry` 照常覆盖 |

顺手修复：`--version` 探针无界 `waitFor()` → 10s 有界（挂死的版本探针会钉死每次会话创建）；`TerminalSettingsSheet` 命令列表 `LazyColumn` 补 key（#245 残留最后一处）；T91 文档残留的 `PRootDialect`/`PRootDialectAdaptationTest` 引用更正（0c9b41ea 更名后未同步）。

## 修复明细

### 1. 能力贯通（缺陷 1 ——「根除静默差异」的完成度）

**platform/terminal**：
- `LinuxExecutionContext`：新增 `capabilities` 字段（默认 `TERMUX_BUNDLED`
  保既有测试夹具；生产构造点唯一 —— factory.resolve）；
- `LinuxExecutionContextFactory.resolve()`：verify 产物捕获进上下文；
- `UbuntuAptPackageManager.buildProotCommand()`：verify 产物捕获 →
  `build(..., capabilities = binaryInfo.capabilities)`；
- `GuestFilesystem.exec()` / `LinuxCapabilityProbe.runGuest()`：
  `build(..., capabilities = ctx.capabilities)`；
- 新增 `PRootCapabilitySource`（纯 JVM）：`suspend verify` → 非挂起读取桥。
  构造即 fire-and-forget 预取（SupervisorJob + IO，AGENTS.md 纪律）；
  预取窗口内读取 = `UPSTREAM_SAFE`（保守省略 —— 省略形状在任何 proot
  上均合法，**绝不拒启**）；provider 内部记忆化保证 refresh 无重复 exec。

**app**：
- `ProotCommandSpawner` / `ProotGitCommandRunner`：构造注入
  `capabilities: () -> PRootArgvCapabilities`（默认 Termux 基线保兼容），
  `buildCommand` 消费；
- `ProotMcpProcessLauncher`：手工 argv 的双旗标改能力门（`--` 省略时
  trampoline 首 token `/usr/bin/env` 是非选项 token，天然分界）；
- DI：`TerminalModule.providePRootCapabilitySource`（单例）；CodeModule /
  McpModule / ToolModule×2 四处构造点注入 `capabilitySource::invoke`。

### 2. 探针前置 prepare（缺陷 2 —— 首启不静默降级）

`NativeLibraryPRootBinaryProvider`：
- `probeEnv(hostEnv)`：`prepare().getOrNull()?.let { hostEnv() }` —— 三探针
  统一前置；prepare 失败 → null → 不可判定（绝不 exec）；
- `defaultVersionProbe`：无界 `waitFor()` → `PROBE_TIMEOUT_SECONDS`（10s）
  有界 + `destroyForcibly()`。

### 3. IPC 输出链防线（缺陷 3/4）

`TerminalIpcController`：
- `callbackFailures: ConcurrentHashMap<Callback, AtomicInteger>` 连续失败
  计数；`CALLBACK_EVICTION_THRESHOLD = 16`；剔除后重新 register 可再接入；
- `maxOutputChunkBytes` 默认 256KB → 64KB；
- `unregisterCallback`/`shutdown` 同步清计数；
- 回放语义 KDoc 按实现重写（见对照表 #4）。

## 验证

| 项 | 结果 |
|----|------|
| `scripts/compile_terminal_jvm.sh`（T92 修复后） | ✅ 2423 classes，0 error |
| platform:terminal JVM 单测（JUnitCore 按包分批） | ✅ proot 72 / service 13 / environment 205 / pkg 60 / fs 10 / runtime 42 / ubuntu 185（除 E2E）等 —— 全绿 |
| 新增测试 | `PRootCapabilitySourceTest`（4 项：预取窗口/失败面/穿透）；`PRootArgvCapabilitiesTest` +2（脚本级 prepare 前置实测 + 陷阱脚本回归检测）；`T81ExecutionContextTest` +1（能力贯入上下文）；`TerminalIpcControllerTest` +2（死回调剔除 + 间歇失败宽恕）；`ProotMcpProcessLauncherCapabilityTest`（4 项：手工 argv 能力门与 builder 逐项一致） |
| 门禁 | `kotlin_balance.py` ✅ / `check_file_size.sh` ✅ / `check_code_quality.sh` ✅ |

## 后续路线（本批次刻意不做）

1. **AIDL 消费者接线**（T91 交付了完整的 Service/Controller/Client 链但
   `TerminalServiceClient` 零消费者 —— ViewModel 迁移到 bind 路径需要真机
   回归支撑，独立 PR）；
2. per-callback 回放锚点（缺陷 4 的彻底解，零消费者阶段过度设计）；
3. `observe(RAW)` 输出路径的 String↔UTF-8 往返（二进制输出经 IPC 会被
   替换字符污染 —— 共享既有机制，需 ObservationEngine 层字节直通改造）；
4. `createSession` 的 15s 预算内 ProcessBuilder 探针不可协作取消（超时后
   孤儿会话收口）；
5. D6-D7 权限链审计可观测化（Issue #255）。
