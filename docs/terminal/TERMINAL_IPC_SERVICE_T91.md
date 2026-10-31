# T91 — Terminal IPC Service / PRoot 方言自适应 / 捏合默认关闭

> 特性文档（docs/feature-doc 惯例）。落地对话结论「比 Operit 强的一周冲刺」的
> D1（禁缩放）、D2–D4（AIDL 终端服务）、D5（版本探针参与 argv 决策）三项。
> 关联：T88（env trampoline，`-E` 事故）/ T89（TerminalScreen 重构）/
> T90（渲染层精修）—— 本特性不重复其结论，只引用。

## 1. D1 —— 终端捏合缩放默认关闭（terminal-view）

**动机**：用户反馈「缩放一坨」。T90 修复了 pinchScaleAccum 手势结束不复位的
症状（残留 1.05..1.24 累积凭空 ±1sp），但捏合与双指拖动/鼠标模式的手势上下文
冲突是结构性的。定性收敛：**默认关闭**，字号调节走宿主设置页 Slider
（`TerminalSettingsSheet` → `ViewModel.setFontSize`，8..24sp 链路，已存在）。

**变更**：

| 文件 | 变更 |
| --- | --- |
| `TerminalViewSettings.kt` | `pinchZoomEnabled` 默认 `true` → `false`（KDoc 记录决策与恢复入口） |
| `TerminalView.kt` | `handleGesture` 的 `Pinch` 分支按开关短路（`handlePinch` 内部守卫双保险） |
| `TerminalViewSettingsTest.kt`（新增） | 7 项契约锁：默认关闭 / 宿主构造继承 / 显式开启能力保留 / clamp·step·派生链路完整 / 坏配置拒绝 |

**效果**：关闭后捏合路径成为死代码（稳定性上限），T90 的累子修复成为永不触达
的兜底。宿主确要恢复：`TerminalViewSettings().copy(pinchZoomEnabled = true)`。

## 2. D5 —— PRoot 方言自适应：版本探针参与 argv 决策（platform/terminal/proot）

### 2.1 根因档案

仓库同时面对两类 proot：

| 二进制 | `--kill-on-exit` | `--` 分隔符 | 出现场景 |
| --- | --- | --- | --- |
| 捆绑 Termux 补丁版 5.1.107.92 | 支持 | 支持 | 设备生产（APK jniLibs） |
| 上游 5.1.0（Ubuntu 24.04 存档） | **不认**（unknown option 拒启） | **不认** | CI E2E、本地开发 host proot |
| Debian 5.4.0（CI 实测） | **支持**（上游已采纳） | **不认**（unknown option） | CI E2E 的 host proot |

> **两项能力正交**（Debian 5.4 即混合形态）—— 首版实现曾把它们耦合在单一
> 方言枚举（killOnExit ⇒ separator），CI 上 Debian 5.4 的探针如实返回
> 「支持 kill-on-exit」却被连带发出 `--` 而拒启。终版改为
> `PRootArgvCapabilities` 能力对：**双探针独立实测、独立决策**，并以
> `PRootArgvCapabilitiesTest`（含 Debian 5.4 混合形态 golden）锁定。

T88 时代靠测试内 `adaptForUpstreamProot` 手工过滤——与 `-E` 事故同构的
「适配层遮蔽真实 argv」风险：**CI 绿不等于设备绿**。

### 2.2 方案：能力探针 + 方言枚举

```
NativeLibraryPRootBinaryProvider.verify()
  ├─ 既有：ELF 机器字节判定 / ABI 匹配 / --version 解析
  └─ 新增：双能力探针（各自独立，10s 有界，按二进制路径记忆化 ——
     provider 为 DI 单例，每会话 verify 不重复 exec）
       ├─ kill-on-exit 探针：exec `<binary> --kill-on-exit --version`，
       │  exit 0 = 支持（不 ptrace，环境无关）
       └─ separator 探针：exec `<binary> -- <sh> -c true`（shell 路径回退
          /bin/sh → /system/bin/sh），exit 0 = 支持。该探针经 tracer 真实
          exec —— ptrace 受限环境给出假阴性，**假阴性方向安全**（省略 `--`
          在任何 proot 上均合法：guest 命令恒以非选项 token /usr/bin/env
          开头，天然分界）
     探针不可判定（exec 异常/超时）→ 保守省略该项

LinuxPRootBackend.prepare()
  └─ verify 结果的能力集（此前只作门禁被丢弃——静默差异的直接根源）→
     PRootCommandBuilder.build(capabilities)
       ├─ 捆绑 Termux 5.1.107：发 --kill-on-exit + --（设备 argv 逐字节不变）
       ├─ 上游 5.1.0：两者省略；env trampoline 原样保留
       └─ Debian 5.4：只发 --kill-on-exit（混合形态，CI 实测）
```

`PRootArgvContract` 双方言形状化：黑名单扫描边界（`--` 或 trampoline 首元素
止）与 trampoline 判定均接受两种形状。

### 2.3 E2E 适配层删除（测试真实性收益）

`ProotExecutorProotSmokeTest` / `UbuntuRootfsEndToEndIntegrationTest` /
`UbuntuTerminalRuntimeWiringTest` 的 `adaptForUpstreamProot` 全部删除，改为
`hostDialect()`（生产 provider 的真实探针）注入 fake binary provider ——
**测试由此真正执行生产 argv 构造路径**，与 `-E` 共犯模式彻底切割。

### 2.4 测试

- `PRootArgvCapabilitiesTest`（新增，10 项）：双探针三态映射（含 Debian 5.4
  混合形态回归锁 —— 本套件的诞生动机）/ 记忆化（每项探针只 exec 一次）/
  四能力组合 golden argv / killOnExit=false 恒不发 / 契约多形状与违规拒绝 /
  backend prepare 端到端能力路由。
- 本地真实复现：Debian proot 5.4.0（CI 同版本）上走生产路径（探针 → builder →
  ProotExecutor）全链路 —— 探针如实给出混合能力（killOnExit=true,
  separator=false），argv 正确自适应，`P71_SMOKE` env 注入 + `-w /root` cwd
  均生效，exitCode=0。
- 既有 golden（`LinuxPRootBackendTest` argv 契约、`PRootCommandBuilderTest`
  27 项）全部保持绿 —— 默认方言 = 捆绑 Termux 版，设备行为不变。

## 3. D2–D4 —— Terminal IPC Service（Operit terminal-core 对齐）

### 3.1 架构

```
ITerminalService.aidl / ITerminalCallback.aidl      ← 跨进程契约（8+3 方法）
        ↑ binder
TerminalService（specialUse 前台服务壳，零逻辑）
        ↓ 转发
TerminalIpcController（纯 JVM：编码/防注入/事件桥 —— 全部单测覆盖）
        ↓ 9 操作门面
TerminalRuntime（PR #60 冻结契约，additive：terminalEventFlow 新增）
        ↑ install（TerminalModule @Provides 副作用）
TerminalRuntimeRegistry（进程内注册表）
```

**薄壳纪律**：与 T88「View 只做事件清洗与动作转发」同构——Service 只做
binder 参数↔控制器调用的机械转发，全部语义在可单测的控制器。

**为什么是注册表而非 @AndroidEntryPoint**：platform:terminal 此前零 Hilt
注解使用；给库模块引入第一个 @AndroidEntryPoint 是未经验证的构建路径
（KSP 聚合 / super-component 接线）。注册表 = 纯 JVM、零代码生成风险、
与 AGENTS.md「能力为全模式共享单例」同构。宿主接线一行：
`TerminalRuntimeImpl(...).also { TerminalRuntimeRegistry.install(it) }`。

### 3.2 契约要点

- **createSession** 返回十进制 sessionId 或 `"ERR:<message>"`（单往返携带完整
  错误信息，不依赖 binder 异常传播）；env 以 `"K=V"` 列表传入，形态非法整条
  跳过（防御式 IO，不炸 create）；
- **write/writeText** owner 恒为 `USER`（IPC 桥是用户侧通道，AIDL 客户端
  不可能伪造 owner=AGENT —— 与 Spec §14 同构）；
- **回调流事件驱动**：`TerminalRuntime.terminalEventFlow`（EventBus 的
  crash-safe 增量订阅）→ `onOutput`（按 OutputProduced 游标段拉取，256KB
  分片防 binder 事务超限）/ `onExit`（ProcessExited+SessionClosed 双源合并
  单次派发）/ `onSessionStateChanged`。**非轮询**；
- **registerCallback 重放语义**：已知会话从诞生起全量重放 —— 重连客户端的
  transcript 重同步（与 Termux 客户端重附时的回读对齐），EventBus 既有
  crash-safe 语义的直接复用，非为此新建路径。

### 3.3 前台语义

- `specialUse` 类型 + `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`（与 app 的
  ApexCoreService 同款模式）；API 34+ 显式 `FOREGROUND_SERVICE_TYPE_SPECIAL_USE`
  三参 startForeground（OEM 两参解析不一致的秒闪退防御同款处理）；
- 通知走框架 `Notification.Builder`（minSdk 26 = 通道必在，库模块零
  androidx 依赖）；资源 `terminal_service_*` 前缀防合并冲突（values + values-zh）；
- 停止 = `context.stopService()`（系统语义。刻意不设 shutdown action ——
  startForegroundService 启动后 5s 内未 startForeground 即崩，自定义 action
  反而制造该崩溃路径）。

### 3.4 多进程路线（刻意未启用）

AIDL 契约已为 `android:process=":terminal"` 预留（所有载荷均为 parcelable
原语）。**本 PR 不启用**：agent 工具 / TerminalViewModel /
EnvironmentProvisioner 仍进程内直连 runtime；此时拆进程会产生两个 runtime
实例（主进程 + :terminal 进程）状态分裂。迁移顺序：

1. （本 PR）AIDL 骨架 + Registry 接线 + 事件流 API（terminalEventFlow）；
2. （后续）TerminalViewModel → TerminalServiceClient 消费 AIDL（renderState
   仍走进程内 styledScreenFlow，输入/输出走 IPC —— 两路并存）；
3. （后续）agent 工具 / provisioner 迁移；
4. （最终）全部消费者走 IPC 后，manifest 翻转 `android:process=":terminal"`
   —— UI 崩溃不再杀后台 shell（Operit terminal-core 的完整对齐）。

### 3.5 测试

`TerminalIpcControllerTest`（新增，11 项，真实 TerminalRuntimeImpl +
FakeNativePty 全链路）：create 编码双态 / 事件驱动首输出 / writeText·write
回显（UTF-8 字节直通）/ listSessions JSON / ping 版本 / close → onExit 单次
派发 / env 防御解析 / 注销停止派发 / runtime 未安装诚实 ERR。

## 4. 验证表

| 验证项 | 方式 | 结果 |
| --- | --- | --- |
| platform:terminal 主源集全量编译 | kotlinc 2.0.21（CI static-analysis 同链路）+ android-all-35 | ✅ |
| platform:terminal 测试源集全量编译（165 类） | 同上 + friend-paths | ✅ |
| platform:terminal 全部单测（~1600 项，分批 forkEvery=16 同构） | JUnitCore + io.parallelism=96 | ✅（BundledRootfsSourceTest 2 项为 Gradle 工作目录差异，模块目录下运行全绿） |
| terminal-view 全部单测（159 项，含新增 7 项） | JUnitCore | ✅ |
| TerminalService/Client 壳编译 | 手写 AIDL 生成类形状桩（AGP aidl 等效）| ✅（CI Gradle 复核） |
| 仓库门禁：文件预算 / 反射派发 / printStackTrace / 括号平衡 | scripts/check_file_size.sh · check_code_quality.sh · kotlin_balance.py | ✅ |
| app:compileDebugKotlin + :app:testDebugUnitTest + AIDL 生成 | CI（pr 触发） | 待远端（本 PR 无 Android SDK 本地环境） |
| 真机回归（T88/T89/T90 修复 + 本特性） | release APK 手工 | 待仓库主人 |

## 5. 变更清单

**terminal-view**：`TerminalViewSettings.kt`（D1 默认值）/ `TerminalView.kt`
（Pinch 短路）/ `TerminalViewSettingsTest.kt`（新增）。

**platform/terminal**：
- proot/：`PRootBackend.kt`（PRootArgvCapabilities 能力对 + builder 能力参数，
  0c9b41ea 前身 PRootDialect 已废弃）、`PRootEnvTrampoline.kt`（契约双能力
  形状）、`NativeLibraryPRootBinaryProvider.kt`（双能力探针 + 记忆化）、
  `LinuxPRootBackend.kt`（verify 能力参与 argv）；
- runtime/：`TerminalRuntime.kt`（additive：terminalEventFlow）、
  `TerminalRuntimeImpl.kt`（EventBus 直通实现）；
- service/（新增）：`ITerminalService.aidl` / `ITerminalCallback.aidl` /
  `TerminalRuntimeRegistry.kt` / `TerminalIpcController.kt` / `TerminalService.kt` /
  `TerminalServiceClient.kt`；
- 资源（新增）：`res/drawable/terminal_service_icon.xml`、
  `res/values{,-zh}/strings.xml`；
- 构建：`build.gradle.kts`（buildFeatures.aidl）、`AndroidManifest.xml`
  （service 声明 + 库自包含权限）；
- 测试：`PRootArgvCapabilitiesTest.kt` / `TerminalIpcControllerTest.kt`（新增）、
  三个 E2E 去适配层、两个工具测试 FakeRuntime 补新接口方法。

**app**：`TerminalModule.kt`（runtime 创建即 install Registry）。
