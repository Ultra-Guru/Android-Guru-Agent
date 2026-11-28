# 测试指南（TESTING.md）

> 本文是 Android-Guru-Agent（apex-agent）的**单一权威测试文档**：测试理念、
> 模块矩阵、运行方式、CI 集成、替身（Fake）规范、新增测试的编写指南与
> 本仓库全部 93 个测试文件的分类清单（含 DCP 设备控制面 11 个，见 §9）。
>
> 原则：**文档必须跟代码走**。新增测试请同步更新 §7 的清单；改动 CI 步骤
> 请同步更新 §5。本文所有命令都可在仓库根目录直接复制执行。

---

## 1. 测试理念

### 1.1 测试金字塔（Android 分层实现）

```
        ╱ 仪器测试 ╱  androidTest  ——— 5 个：真机 forkpty/JNI、Ubuntu rootfs 供给
       ╱  （设备） ╱                 （CI 无模拟器：只编译不运行，真机跑通）
      ╱───────────╱
     ╱  JVM 单测  ╱ ———————— 69 个：纯 Kotlin + JUnit4 + runTest，
    ╱  （主力）  ╱                    无网络、无真 LLM、无 Android 运行时
   ╱─────────────╱
  ╱ 静态检查 CI  ╱ ————— 括号平衡 / 工具 ID 唯一性 / 类名重复 / 文件大小预算 /
 ╱  （门禁）    ╱       反模式（反射分发、printStackTrace）/ PRoot 二进制 sha256
╱───────────────╱
```

- **JVM 单测为主力**：`core/*` 三个模块是纯 JVM（零 Android 依赖），天然可测；
  Android library 模块（`platform/terminal`、`platform/memory-host`）通过
  **接口替身 + `returnDefaultValues`** 把被测路径保持在 JVM。
- **仪器测试留给只有真机才能验证的东西**：原生 PTY（forkpty/JNI 桥）、
  rootfs 供给（网络下载 + 解包）、PRoot 实进程。
- **静态检查兜底结构性退化**：God 文件、括号失衡、工具 ID 重复等在编译前拦截。

### 1.2 确定性原则

所有 JVM 单测满足三个"无"：

1. **无真实网络**——LLM 用 `FakeLlmClient`（脚本化响应序列），工具用
   `FakeToolExecutor`（注册式返回），GitHub 用离线 fixture；
2. **无真实时间依赖**——协程测试统一 `runTest`（虚拟时间，`delay` 零成本），
   需要真实挂起点的地方显式 `delay(10)` 让 collector 先跑（见
   `lifecycle events are emitted for state transitions` 用例的注释）；
3. **无随机**——指纹/哈希类断言只验证**性质**（确定性、稳定性、区分度），
   不硬编码具体摘要值。

### 1.3 回归锁定原则

每个历史缺陷修复**必须**伴随一个会失败的回归测试。本项目有大量
"v3 修复回归"标记的用例，例如：

- 旁路引擎跨版本宏回退（`BypassExecutionEngineTest`）；
- 编排器正文/思维链混流（`OrchestratorTestSuite` 流式语义组）；
- 并行工具调用分片撕裂（`tool call fragments with only index merge`）。

---

## 2. 模块测试矩阵

| 模块 | 类型 | 测试文件数 | 运行命令 | CI 中的步骤 |
|------|------|----------|---------|------------|
| `:core:agent-engine` | JVM | 3 套件 | `./gradlew :core:agent-engine:test` | `Run core:agent-engine unit tests` |
| `:core:tool-registry` | JVM | 4 | `./gradlew :core:tool-registry:test` | `Run core:tool-registry unit tests` |
| `:core:llm-adapter` | JVM | 6 | `./gradlew :core:llm-adapter:test` | 随 `:app:compileDebugKotlin` 编译（脚本化静态编译覆盖） |
| `:platform:terminal` | JVM | 45 | `./gradlew :platform:terminal:testDebugUnitTest` | `Run platform:terminal unit tests` |
| `:platform:terminal` | androidTest | 5 | `./gradlew :platform:terminal:connectedDebugAndroidTest`（真机） | CI 只编译：`compileDebugAndroidTestKotlin` |
| `:platform:memory-host` | JVM | 6（**记忆拆库后宿主层**） | `./gradlew :platform:memory-host:testDebugUnitTest` | `Run platform:memory-host unit tests`（记忆域本体在 [apex-kit-memory](https://github.com/Ultra-Guru/apex-kit-memory)，63 项库内测试） |
| `:platform:privilege` | JVM | 1 | `./gradlew :platform:privilege:testDebugUnitTest` | 随 app 编译覆盖 |
| `:terminal-emulator` | JVM | 1 | `./gradlew :terminal-emulator:test` | 随静态编译覆盖 |
| `:app` | JVM | 4 | `./gradlew :app:testDebugUnitTest` | 随 app 编译覆盖（未单独设门禁） |

> 一键全量（本地，约 3–8 分钟；仓库未锁定 wrapper，首次先
> `gradle wrapper --gradle-version 8.10 && chmod +x gradlew`，同 CI）：
> ```bash
> ./gradlew :core:agent-engine:test :core:tool-registry:test \
>   :platform:terminal:testDebugUnitTest :platform:memory-host:testDebugUnitTest \
>   --no-daemon -Pkotlin.incremental=false
> ```

---

## 3. 各模块测试详情

### 3.1 `core:agent-engine` —— 编排器与引擎（最重的测试面）

**OrchestratorTestSuite.kt**（A68.1 规格，6 大类）：

| 类别 | 覆盖 |
|------|------|
| StateTransitionTests | TaskState 状态机转移（Idle→Planning→…→Completed/Aborted） |
| SuccessfulExecutionTests | BUILD 循环全链路（Fake LLM + Fake 工具） |
| ToolFailurePropagationTests | 工具错误回喂 LLM 而非崩溃 |
| CancellationTests | `abort()` 中途取消、协程取消传播 |
| TimeoutTests | 单工具超时 + 任务级超时 |
| ProgressAndEventTests | TaskProgress + AgentEvent + 生命周期事件 + **流式语义回归组（本 PR 新增）** |

**本 PR 新增的流式语义回归组（3 用例）**：

| 用例 | 锁定的缺陷 |
|------|-----------|
| `content chunks stream as ResponseChunk during LLM call` | 旧实现把正文整段缓存后误当 ThinkingChunk 发射，UI 无法逐字渲染 |
| `reasoningContent streams as ThinkingChunk and never mixes with content` | 原生 `reasoning_content`（R1/Qwen3-thinking）被丢弃；正文与思维链混流 |
| `tool call fragments with only index merge into one accumulator` | 并行工具调用分片：累加器键 id 优先导致首片/续片撕裂、参数 JSON 裁断 |

**OrchestratorResilienceTestSuite.kt** —— 恢复规划器、失败分类、重试策略、
循环检测、工具调用图、用户交互门的韧性场景。

**RoleRoutingGoldenTest.kt** —— T72 多模型角色路由的黄金用例（含图片走
VISION、能力不匹配降级）。

**共享替身**（`src/test` 内，生产代码永不导入）：`FakeLlmClient`
（`ScriptedResponse.Ok/Stream/Throw` 三形态脚本 + `callLog` 断言历史）、
`FakeToolExecutor`（`registerSuccess/registerFailure` 注册式）、
`FakeToolRegistry`、`FakeConversationMemory`。

### 3.2 `core:tool-registry` —— 工具层

- **SafeAgentToolStreamingTest**：`SafeAgentTool` 包装器必须**透传**被包装
  工具的流式能力（否则 wrapper 会把 executor 的 `is StreamingAgentTool`
  探测挡掉，全网工具静默失去流式）——历史上真实发生过。
- **DefaultToolExecutorStreamingTest**：普通 `AgentTool` 被透明包装为
  `Output + Complete` 流；`StreamingAgentTool` 原样转发。
- **DomParserTest / BrowserScriptTest**：浏览器 DOM 解析与脚本生成
  （含 FORM_FIELDS 选择器解析精度——修复过"textarea,"误匹配 "a," 的假阳性）。

### 3.3 `core:llm-adapter` —— 多模型运行时

`ModelProfileValidatorTest` / `ModelRuntimeRegistryTest` / `DefaultModelRuntimeTest`
/ `CapabilityResolverTest` / `ErrorClassifierTest` / `ModelRoleRouterTest` ——
模型档案校验、运行时注册表、能力解析、错误分类、角色路由。共享
`FakeModelRuntimeSupport`。

### 3.4 `platform:terminal` —— 终端运行时（45 个 JVM 测试 + 5 个仪器测试）

按域分组（与源码包结构一致）：

| 域 | 测试 | 说明 |
|----|------|------|
| 运行时 | TerminalRuntimeContractTest / TerminalRuntimeEndToEndTest / TerminalRuntimeBackendTest / ExecutionBackendGoldenTest / RuntimeWorkspaceTest / UbuntuTerminalRuntimeWiringTest | 运行时契约 + 端到端 + 后端接线 |
| Ubuntu 供给 | RootfsProvisioningTest / RootfsConfiguratorTest / RootfsExtractorTest / UbuntuBootstrapManagerTest / UbuntuSourcesListTest / BootstrapStateStoreTest / **UbuntuRootfsEndToEndIntegrationTest** | E2E 会下载**真实 Ubuntu 24.04 rootfs** 并跑真实 proot（CI 装 proot 5.4；不可用时自我跳过 L2/L3 并诚实报告） |
| PRoot | PRootBackendTest / LinuxPRootBackendTest / ProotExecutorTest / ProotExecutorProotSmokeTest / BoundedOutputCaptureTest | 后端契约 + P71 builder 契约冒烟 |
| 包管理 | PackageManagerTest / PackageOperationLockTest | UbuntuApt / Fake 包管理 + 并发锁 |
| 会话/IO/进程 | SessionLifecycleTest / NativeSessionIdIsolationTest / IOLayerTest / PumpEofSemanticsTest / InputLayerTest / ProcessControlTest / ProcessGroupSignalTest / ProcessPolicyTest | 会话生命周期、PTY 输出泵 EOF 语义、进程组信号 |
| 环境自适应 | EnvironmentTest / LinuxEnvironmentManagerTest / AdaptiveEnvironmentTest | 环境配置 + 自适应修复环（DiagnosticRules） |
| 观察/智能/控制 | Observation2Test / IntelligenceLayerTest / ControlPlaneTest | 语义状态降维、PromptDetector/JobStateMachine、控制平面 |
| API/策略/可靠性 | TerminalApiContractTest / ApiHardeningTest / LinuxErrorTest / ReliabilityTest | 契约 + 错误分类 + 恢复协调 |
| 工具 v2 | TerminalLinuxPackagesToolTest / TerminalLinuxStatusToolTest / TerminalBackendsToolTest / TerminalWorkspacesToolTest | `terminal.*` 工具参数与输出契约 |
| JNI 桥 | JniBridgeMappingTest | Kotlin↔C++ 符号映射（纯 JVM 侧校验） |

**仪器测试（5 个，真机）**：NativePtyJniInstrumentationTest（真实 forkpty）、
NativePtyArgvInstrumentationTest（argv 编组）、RootfsProvisioningInstrumentationTest、
UbuntuTerminalRuntimeInstrumentationTest、UbuntuLinuxEnvironmentInstrumentationTest。
CI 无模拟器 → 只编译保证不烂（`compileDebugAndroidTestKotlin`），真机运行：
```bash
./gradlew :platform:terminal:connectedDebugAndroidTest
```

### 3.5 `platform:memory-host` —— 认知记忆宿主层（记忆域已拆库为 apex-kit-memory）

库本体（apex-kit-memory，63 项纯 JVM 测试）覆盖：摄取 / 会话 / 蒸馏 / 匹配 /
维护 / 存储原子性。宿主层测试锁定**旧存储 → 新库的一次性迁移**语义：

| 测试类 | 覆盖 | 锁定的缺陷 |
|--------|------|-----------|
| **LegacyCsMemMigratorTest** | 旧 FSM 宏 `transitions_json` → 类型化 `MacroAction` 的一对一映射（click/swipe/inputtext/back / 锚点保留）；未知动作类型 / 坏 JSON / 缺参数 → 整宏丢弃（null），不留半迁移状态 | 旧宏 `actionParams` 是裸字符串 + 回放侧正则再解析 —— 迁移期半解析会把不可回放的宏带进新库 |

**技术要点**：memory-host 是 Android library，但被测路径全部纯 JVM；
`returnDefaultValues = true` 使类加载安全通过，无需 Robolectric。迁移器
`parseLegacyTransitions` 为 `internal` 纯函数，不触 Context/DB。

### 3.6 其余模块

- **`app`**（4）：SlashCommandParserTest / SlashCommandRouterTest（斜杠命令
  文法与路由，纯 JVM 包 `com.apex.agent.slash`）、BrowserTracerTest /
  RetryPolicyTest（浏览器追踪重试）。
- **`platform:privilege`**（1）：ProcessStreamFactoryTest——三级权限链
  （Root/Shizuku/普通）共享的进程流读取逻辑。
- **`terminal-emulator`**（1）：TerminalCoreTest——vendored VT100/ANSI
  模拟器的核心行为（转义序列、滚动区、UTF-8 解码）。

---

## 4. 替身（Fake）编写规范

1. **手写 Fake，不引 MockK/Turbine**——版本目录（`gradle/libs.versions.toml`）
   只声明 JUnit4 + coroutines-test；手写替身可读性更高且零依赖成本。
   `FakeLlmClient` 是范本：密封 `ScriptedResponse` 三形态 + `callLog`
   捕获 + `reset()` 复用。
2. **替身只住在 `src/test`**——生产代码永不 import 测试源集（包内注释
   强制声明）。跨测试类共享的替身（FakeLlmClient 等）放同模块 test 包。
3. **接口替身最小实现**——大接口（如 `MemoryGraphStore` 约 30 方法）的
   Fake 只实现被测路径触达的方法，其余显式 `= Unit / null / emptyList()`
   并集中放在文件末尾"以下为不触达的默认实现"分隔注释下。
4. **`runTest` + 虚拟时间**——挂起测试统一 `kotlinx.coroutines.test.runTest`；
   需要真实调度让位的场合（SharedFlow collector 先启动）显式 `delay(10)`，
   并写注释解释为什么（范本见 OrchestratorTestSuite）。
5. **断言信息必须可诊断**——`assertEquals("跨版本场景：别名桥反查应命中
   旧宏并成功回放，实际: $result", …)`：失败时一眼定位语义而非只看到 diff。

## 5. CI 集成（.github/workflows/）

| 工作流 | 作用 | 与测试相关的步骤 |
|--------|------|----------------|
| **ci.yml** | 主流水线 | ① 静态分析（kotlinc 编译 core 四模块 + 工具 ID 唯一性 + 括号平衡 + PRoot sha256 校验）② `app-compile` job：`:app:compileDebugKotlin` → `:platform:terminal:testDebugUnitTest` → 仪器测试编译 → `:core:agent-engine:test` → `:core:tool-registry:test` → **`:platform:memory-host:testDebugUnitTest`（记忆拆库后宿主层）** ③ `build-apk` job：assembleDebug + APK 体积报告（PR 评论） |
| **quality-gate.yml** | 结构门禁 | 文件大小预算（main ≤1200 行 / test ≤1600 行）、反模式（反射分发、printStackTrace）、空 catch + TODO 普查（Job Summary） |
| **pr-labeler.yml** | PR 自动标签 | 按改动路径打 area/risk 标签 |

**红线**（ci.yml 步骤内注释明文）：`core:tool-registry` 的测试步骤
**不得**通过删除或跳过让 CI 变绿——要么修代码，要么修测试。
CI 在 PR 上会安装 Debian proot 5.4 供 E2E 用（Ubuntu 24.04 自带 5.1 跑不动
glibc-2.39 guest）；安装失败时测试自我跳过高级别场景并诚实报告。

## 6. 编写新测试的检查单

- [ ] 放对位置：纯逻辑 → `src/test`（JVM）；必须真机（JNI/下载/实进程）→ `src/androidTest`；
- [ ] 命名：反引号自然语言句子，**不含 `.`**（Kotlin 反引号方法名非法字符，
  历史上踩过）；
- [ ] 断言信息中文说明语义 + 失败现场；
- [ ] 回归测试：先确认**修复前会失败**（红→绿），并在 KDoc 注明锁定的缺陷；
- [ ] 括号平衡：字符串字面量中的 ASCII 括号也要配对（CI 的朴素 grep 检查
  连字符串一起数——范本：`extractInputText malformed parens` 用例用
  括号倒置形态 `InputText)("unclosed` 同时满足语义与门禁）；
- [ ] 新模块测试：`build.gradle.kts` 加 `testImplementation(libs.junit)`
  （+ 需要时 `libs.coroutines.test`）；Android library 模块评估
  `returnDefaultValues`；
- [ ] 同步更新本文 §2 矩阵与 §7 清单。

## 7. 测试文件总清单（93 个）

<details>
<summary><b>core:agent-engine（5）</b></summary>

```
src/test/kotlin/com/apex/agent/core/engine/
├── RoleRoutingGoldenTest.kt
├── DeviceIntentGateTest.kt             （DCP S03：统一风险门语义分级矩阵）
├── compression/ToolOutputTruncatorNoticeTest.kt   （对话实录 #6：截断告示回归锁）
└── orchestrator/
    ├── OrchestratorTestSuite.kt            （含本 PR 新增流式语义回归组）
    ├── OrchestratorResilienceTestSuite.kt   （含幂等只读白名单回归组）
    ├── FakeLlmClient.kt / FakeToolExecutor.kt / FakeToolRegistry.kt / FakeConversationMemory.kt（替身）
```
</details>

<details>
<summary><b>core:tool-registry（9）</b></summary>

```
src/test/kotlin/com/apex/agent/core/tools/
├── DefaultToolExecutorStreamingTest.kt
├── SafeAgentToolStreamingTest.kt
├── ToolSchemaAliasTest.kt               （对话实录 #8：必填参数别名互认回归锁）
├── device/                              （DCP S02：单一事实源不变量）
│   ├── IntentCatalogTest.kt             （真实 intents.json 穷举，新增意图自动进测）
│   └── ArgvRendererTest.kt              （参数白名单 / 元字符拒绝 / 空 flag 语法）
└── builtin/
    ├── WebHintsTest.kt                  （对话实录 #1/#4/#5：SPA 壳探测与状态码向导）
    ├── DeviceControlToolsTest.kt        （DCP S01/S05/S07/S10：三工具 + 旧工具委托兼容）
    └── browser/
        ├── DomParserTest.kt
        └── BrowserScriptTest.kt
```
</details>

<details>
<summary><b>core:llm-adapter（6 + 1 替身）</b></summary>

```
src/test/kotlin/com/apex/agent/core/llm/runtime/
├── CapabilityResolverTest.kt
├── DefaultModelRuntimeTest.kt
├── ErrorClassifierTest.kt
├── ModelProfileValidatorTest.kt
├── ModelRoleRouterTest.kt
├── ModelRuntimeRegistryTest.kt
└── FakeModelRuntimeSupport.kt（替身）
```
</details>

<details>
<summary><b>platform:memory-host（6，记忆拆库后宿主层）</b></summary>

```
src/test/kotlin/com/apex/agent/platform/memory/
└── migrate/LegacyCsMemMigratorTest.kt
```
</details>

<details>
<summary><b>platform:terminal（46 JVM + 5 仪器）</b></summary>

```
src/test/kotlin/com/apex/agent/platform/terminal/   （46，按 §3.4 域分组；
  含 tools/v2/AgentGuidanceTest.kt —— 对话实录 #7/#8/#9：heredoc 警告/别名
  宽容/EVENT 提示纯函数回归锁；ubuntu/RootfsConfiguratorTest.kt 增补组条目
  回归组 —— 对话实录 #14）
src/androidTest/kotlin/com/apex/agent/platform/terminal/   （5，真机）
```
</details>

<details>
<summary><b>app / platform:privilege / terminal-emulator（17）</b></summary>

```
app/src/test/kotlin/com/apex/agent/
├── browser/BrowserTracerTest.kt
├── browser/RetryPolicyTest.kt
├── bridge/DeviceBridgeHandlerTest.kt   （DCP S09：apexctl device 同一 port 往返）
├── slash/SlashCommandParserTest.kt
├── slash/SlashCommandRouterTest.kt
├── tools/                              （DCP S04/S05/S06/S07/S10 执行面）
│   ├── DevicePreflightTest.kt          （diff 解析 / 守卫 flag / 令牌 Valid-Stale-Rejected）
│   ├── DeviceOpsExecutorTest.kt        （编排矩阵：DCP_DISABLED / dry_run / token / 守卫 / 审计三链）
│   ├── DevicePathPolicyTest.kt         （路径白名单 / 穿越 / 保护区 / 回收区）
│   ├── ToolAuditLoggerCompatTest.kt    （旧 jsonl 兼容 + 新字段解析）
│   └── AndroidOnlyCommandsConsistencyTest.kt（manifest 派生 vs 历史底线只增不减）
└── ui/screen/terminal/            （T96 终端大重构：真实执行管线）
    ├── RawStreamSanitizerTest.kt
    ├── RealCommandExecutorTest.kt （手写 FakeRuntime）
    ├── CommandPolicyStoreTest.kt
    ├── TerminalCommandHistoryTest.kt
    └── TerminalSyntaxTest.kt
platform/privilege/src/test/.../ProcessStreamFactoryTest.kt
platform/privilege/src/test/.../shizuku/ShizukuProcessAdapterContractTest.kt（DCP S08 契约）
terminal-emulator/src/test/.../TerminalCoreTest.kt
```
</details>

---

## 8. 常见问题（FAQ）

**Q：为什么 `:app:testDebugUnitTest` 没进 CI 门禁？**
A：app 模块测试依赖 Compose/Android 运行时较多，`app-compile` job 的
`:app:compileDebugKotlin` 已覆盖编译错误；UI 逻辑尽量下沉到
`com.apex.agent.slash` 这类纯 JVM 包再测。若 app 测试持续增加，可另立 job。

**Q：本地跑 terminal E2E 为什么自动跳过部分场景？**
A：`UbuntuRootfsEndToEndIntegrationTest` 探测宿主 proot 版本：≥5.4 才跑
L2/L3（真实 guest 进程），否则自我跳过并在输出中声明跳过级别——这是诚实
降级，不是静默通过。

**Q：memory-host 测试为什么不引 Robolectric？**
A：被测路径（迁移映射）不触碰 Android API；`returnDefaultValues`
已覆盖类加载需求。Robolectric 会拖慢启动且引大量传递依赖，仅当未来要测
Context 相关迁移分支时再评估。

**Q：怎么验证我的回归测试真的在锁缺陷？**
A：`git stash` 你的修复 → 跑测试应当红 → `git stash pop` → 绿。两步都过
才算回归测试就位。

---

## 9. DCP 设备控制面（Device Control Plane）测试矩阵

> S01–S10 的测试面总览：manifest 不变量、风险分级、令牌绑定/过期/重放、
> argv 渲染与注入拒绝、路径白名单、diff 解析（含 parseIncomplete）、
> 审计兼容、guest 桥往返、Shizuku 通道契约。

### 9.1 JVM 单测矩阵（新增 11 个文件，全绿门禁）

| Spec | 文件 | 关键断言 |
|------|------|----------|
| S02 | `core:tool-registry .../device/IntentCatalogTest.kt` | 真实 intents.json 穷举：id 唯一 / 覆盖下限（package≥10/file≥8/system≥6/probe≥4）/ 不可逆必有 preflight+verify+rollbackHint / 占位符闭合 / argv[0] 白名单 / 坏 intent 跳过不炸表 |
| S02 | `core:tool-registry .../device/ArgvRendererTest.kt` | 参数白名单（kind 校验 / 未知参数 / 缺参）、元字符拒绝（`;` `\|` `$` 反引号）、空 flag 语法（"" → 元素丢弃）、payload 只限长度 |
| S03 | `core:agent-engine .../DeviceIntentGateTest.kt` | CRITICAL+不可逆 BYPASS 下仍 Ask / DENY 规则最高优先 / allow_session 只对可逆 / PLAN 写拒 dry_run 放 / 伪造 allow_session 答案 fail-safe 拒绝 |
| S08 | `platform:privilege .../ShizukuProcessAdapterContractTest.kt` | exitValue 未退出抛 IllegalStateException / destroy 幂等 / waitFor 超时可重入 / isAlive 翻转一致 |
| S01/S05/S07/S10 | `core:tool-registry .../builtin/DeviceControlToolsTest.kt` | DeviceCapability 快照 JSON / DeviceOps 四字段透传 / 描述由 manifest 派生 / AppUninstall·AppForceStop 委托兼容（DCP_DISABLED 回退旧链）/ DeviceAuditQuery 按 auditId 回放三链 |
| S04 | `app .../DevicePreflightTest.kt` | dumpsys 解析（SYSTEM_APP）/ IS_SELF·DEVICE_ADMIN·IS_LAUNCHER flag / parseIncomplete 不编造 / 令牌 Valid·Stale·Rejected·过期（假钟）/ dry_run 零副作用（通道只见只读探针） |
| S05/S06/S07 | `app .../DeviceOpsExecutorTest.kt` | DCP_DISABLED 不静默降级 / 档位不足给开通步骤 / dry_run→token→执行全链（argv 逐元素断言）/ Stale 刷新不执行写 argv / 守卫硬拒 / 路径越权拒绝 / 审计三条同 auditId |
| S06 | `app .../DevicePathPolicyTest.kt` | 白名单根 / `..` 穿越 / 冗余段 / 系统保护区 / 其它包 data / 回收区本体拒作目标 / 读写下同表 |
| S10 | `app .../ToolAuditLoggerCompatTest.kt` | 旧格式 9 字段行照常解析 / 新扩展字段可选 / 损坏行返回 null / denied_by_user 判失败 / preflight 不判失败 |
| S07 | `app .../AndroidOnlyCommandsConsistencyTest.kt` | 生效集合 ⊇ 历史底线（只增不减）/ manifest 派生全覆盖 / 差异记录可审 / 未注入派生源零回归 |
| S09 | `app .../bridge/DeviceBridgeHandlerTest.kt` | apexctl device JSON 透传同一 port / status→probe.id / 未知 action BRIDGE_UNKNOWN_ACTION / 非法 JSON ok:false |

### 9.2 CI 门禁增量

- `quality-gate.yml` 新增 **DCP intents manifest gate**（`scripts/check_intents.py`）：
  与 `IntentCatalog.validate()` 同口径的结构校验（runtime 兜底 + CI 前移双保险）；
- 其余门禁（文件预算 / 反模式 / 括号平衡 / 工具 ID 唯一性）对新文件照常生效。

### 9.3 真机 NOT VERIFIED 清单（绝不写假 PASS）

沿用仓库 NOT VERIFIED 纪律 —— 以下项**必须真机验证后才能标注 VERIFIED**：

| # | 待验证项 | 验证方式 | 当前状态 |
|---|---------|---------|---------|
| 1 | `pm uninstall -k --user 0 <pkg>` 对预装应用的实际效果（部分 ROM 需 disable-user 而非 uninstall） | Shizuku 授权真机：dry_run → 确认 → 执行 → `pm list packages --user 0` 复探 | NOT VERIFIED |
| 2 | `pm install-existing --user 0` 恢复路径在真 ROM 上的行为 | 卸载预装后恢复 | NOT VERIFIED |
| 3 | Shizuku 各版本 `newProcess` argv 语义（13.x 真机） | DeviceOps 干跑 `probe.id` 看输出 | NOT VERIFIED |
| 4 | `/sdcard` 在 Android 11+ 分区存储下经 uid=2000 的读写（未授 `MANAGE_EXTERNAL_STORAGE`） | `file.list /sdcard/Download` + `file.write` 往返 | NOT VERIFIED |
| 5 | `/sdcard` 与 `/data/local/tmp` 跨挂载点 mv（软删）是否失败 | `file.delete` 真机跑一次看 MOVE_FAILED 分支 | NOT VERIFIED |
| 6 | `dumpsys device_policy` / `cmd shortcut get-default-launcher` 在各 Android 版本的输出格式 | 干跑 `package.uninstall` dry_run 看 diff.before 解析完整度 | NOT VERIFIED |
| 7 | PTY 会话内 `.bashrc` 托管块被 `bash -i` 实际加载（command_not_found_handle 生效） | guest 内敲 `pm list packages` 看引导文案 | NOT VERIFIED |

### 9.4 真机验收脚本（Shizuku 已授权设备）

```
# 1) 自省
DeviceCapability {}
# 期望：privilege=SHIZUKU, uid=2000, denies 含 file.data_data 类（ROOT-only 意图）

# 2) 只读预演（不执行）
DeviceOps {"intent":"package.uninstall_for_user","params":{"package":"com.example.demo","user":"0"},"dry_run":true}
# 期望：diff.before 有 versionName/systemApp，返回 confirm_token，channel="dry-run"

# 3) token 提交
DeviceOps {"intent":"package.uninstall_for_user","params":{"package":"com.example.demo","user":"0"},"dry_run":false,"confirm_token":"<上一步>"}
# 期望：verify 显示已不在 pm list packages，auditId 非空

# 4) 文件软删 + 撤销
DeviceOps {"intent":"file.delete","params":{"path":"/sdcard/Download/a.txt"},"dry_run":true}
DeviceOps {"intent":"file.undo","params":{"undo_token":"<上一步 undo_token>"}}
# 期望：文件回到原路径（stat size/mtime 一致）

# 5) 越权诚实拒绝
DeviceOps {"intent":"file.write","params":{"path":"/data/data/com.other/x","content":"y"}}
# 期望：ok=false, error=PATH_DENIED（或 DEVICE_PERMISSION_REQUIRED），绝不冒充成功

# 6) guest 侧
TerminalExec {"command":"apexctl device '{\"intent\":\"probe.id\"}'"}
# 期望：与 Agent 侧 DeviceCapability 同源档位

# 7) 审计回放
DeviceAuditQuery {"auditId":"<第3步 auditId>"}
# 期望：preflight → approved → executed 三条同链
```
