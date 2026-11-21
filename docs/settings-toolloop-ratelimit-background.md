# 设置中心三件套：工具循环 / 限速友好 / 自定义背景

> 版本：v1.6.0 · 分支 `feat/settings-toolloop-ratelimit-background`
> 关联：AGENTS.md「提交与 PR 约定」；设置中心两级抽屉（SettingsNavTree）

## 0. 一句话

把三项此前「数据预埋 / dead 字段 / 缺失」的能力接成真实现：**工具循环**
（单轮并行执行上限 + 任务轮次无上限）、**模型限速友好**（请求节流 +
每请求信息密度，只针对有配额限制的厂商用户）、**自定义背景**（导入自己
的图片 + 专属优化渲染管线）。

## 1. 工具循环（Tool Loop）

### 1.1 用户需求 → 行为映射

| 需求 | 实现 |
| --- | --- |
| 「一次最多同时调用几个工具」 | `AgentSettings.toolCallParallelism`（1..8，默认 1=顺序）→ 引擎同轮并行**执行** |
| 「一个任务调用工具次数要无上限」 | `maxIterations = 0` 哨兵（默认开，存量用户一次性迁移）→ 循环不再被轮次硬顶打断 |
| （顺带修复）设置页「Max Tool Calls / Turn」 | `ModelProfile.maxToolCalls` 此前是 dead 字段 → 引擎真接线（超出延迟到下一轮 + System 说明） |

### 1.2 引擎实现（core/agent-engine/EngineToolExecution.kt）

`executeToolCallTurn`（主循环唯一入口）职责链：

```
capToolCallsPerTurn（单轮上限截断 + 延迟说明）
  → addMessage(Assistant(tool_calls))   ← 配对锚点：只携带实际执行的调用
  → AskUser 保序穿插（挂起等用户，不可并行）
  → 其余工具按 parallelism 分块并行执行
  → flushPendingSystemNotes（位置安全点：完整 tool 块之后）
```

**并行批的两条不变量**（这是本特性最核心的工程决策）：

1. **事件时间线不撕裂**：UI 的 currentToolCall 单卡设计一次只跟踪一个
   callId（迟到 chunk 直接丢弃）。并行工作协程只把事件塞进各自的
   `Channel`，由流属主协程按「发言权」协议顺序放行——每个工具的
   Start → Output → Complete 生命周期在时间线上连续。执行并行、呈现顺序。
2. **历史与检测器串行落盘**：`executeToolCallStreamingCore`（并行，事件流）
   与 `finalizeToolCallOutcome`（串行，历史 + 循环检测 + 韧性统计 +
   终端建议器 + 记忆采集）拆开——所有有状态组件只由流属主协程按原始
   tool_calls 顺序访问，无锁、无竞态、**ToolResult 顺序 = tool_calls 顺序**
   （OpenAI 兼容端点配对不变量，400 根因防线）。

配套：`EngineResilienceGuard.onToolFailure` 加 `@Synchronized`（并行批下
任务级重试计数器的原子性）；`ThinkingModeController.effectiveMaxIterations`
消费哨兵（`base ≤ 0 → Int.MAX_VALUE`，画像倍率不放大无限）。

### 1.3 与请求参数的正交性

ModelProfile 的 `parallelToolCalls`（OpenAI `parallel_tool_calls` 请求参数）
控制模型**能不能**一轮发多个调用；`toolCallParallelism` 控制引擎**怎么执行**
它们。两者独立组合（如：允许模型并行发 3 个、引擎仍顺序执行）。

### 1.4 设置入口

设置 → Agent 配置 → 执行 → 「工具循环」分组：任务轮次无上限开关（关闭
时显示 1..500 数字输入）+ 单轮并行执行上限滑杆（1..8）。

## 2. 模型限速友好（Rate-Limit Friendly）

### 2.1 用户需求 → 行为映射

「使用某些有速率限制和 API 请求次数的模型厂商……可以把 API 请求次数
减少，每次请求包含的信息多一些……这个设置只针对某些用户」——**默认全关
= 零行为变化**，三根支柱全部真接线：

| 支柱 | 实现 | 生效时机 |
| --- | --- | --- |
| 请求节流 | `RateLimitGovernor`（llm-adapter）：所有经过 `DefaultModelRuntime` 的请求（含压缩摘要/子代理/验收/降级重试的每一次尝试）发出前排队，相邻起点间隔 ≥ 设定值 | 即时（策略热读） |
| 每请求信息密度 | 引擎旋钮拍平：工具输出预算 ×2 / 保留近轮 +3 / 压缩阈值 +0.05（更晚压缩）——同样的任务用更少的请求 | 重启（DI 快照） |
| 一键推荐组合 | 限速模式开（≥4s）+ 信息密度 + 并行 4 路 + 任务轮次无上限 | 一键 |

### 2.2 设计要点

- **单一节流点无旁路**：接线在 `DefaultModelRuntime` 的 chat/chatStream
  尝试循环内——引擎、压缩器、子代理、验收器全部经此通道；
- **策略热读**：每次 permit 拉取 `repo.agentSettings.value` 快照，设置页
  改参数下一个请求立即生效（无需重启/重建 runtime）；
- **关停零开销**：disabled 时不进锁不挂起；
- **时钟可注入**：`clock: () -> Long`，测试用 TestScheduler 虚拟时间驱动
  （首请求不等待的 `hasDispatched` 显式标记，避免虚拟时钟 0 值歧义）；
- **与 RetryInterceptor 分工**：单请求内 429 退避归 OkHttp 拦截器，请求间
  主动限速归本节流器——两层正交，目标是让 429 根本不发生。

### 2.3 设置入口

设置 → 模型配置 → 限速友好（新叶子页 `model/rate-limit`）。

## 3. 自定义背景（Custom Background）

### 3.1 用户需求 → 行为映射

「主题增加背景自定义，可导入自己的图片，要专属优化」——四个专属优化
（ui/theme/BackgroundImage.kt）：

1. **降采样解码**：bound 解码求原始尺寸 → 幂次 inSampleSize → 目标长边
   1440px 内实际解码。48MP 照片直解 ≈ 190MB ARGB（稳 OOM），降采样后
   ≈ 8MB；
2. **亮度自适应纱罩**：降采样位图稀疏采样（≤17×17）求感知亮度均值 →
   暗色主题下图片越亮纱罩越深 / 亮色主题用白纱、图越暗越厚——文字对比度
   有下限（0.10）保证，上限 0.85（背景永远可见）；
3. **一图一解码**：`produceState(uri)` 以 URI 为 key，换图才重解码，
   重组零 IO；
4. **失败诚实降级**：URI 失效（授权回收/文件被删）→ null → 背景层整体
   不渲染（回退纯色）+ WARN 留痕，绝不崩。

### 3.2 工程细节

- SAF `OpenDocument` + `takePersistableUriPermission`（重启仍可读，
  chaos-crash-audit C-09 同款纪律）；
- 渲染位置：MainActivity Surface 内首个子层（垫在全部内容之下，含
  Onboarding）；
- 用户纱罩强度滑杆（0.1..0.8）叠加在自适应基数之上——用户只调「感觉」，
  对比度下限交给自适应。

### 3.3 设置入口

设置 → 界面外观 → 「自定义背景」分区（选择/更换/清除 + 纱罩强度）。

## 4. 字段与迁移

`AgentSettings` 新字段：`toolCallParallelism` / `toolLoopMigrated` /
`rateLimitFriendly` / `rateLimitMinIntervalMs` / `rateLimitRichPayload` /
`backgroundImageUri` / `backgroundDim`；`maxIterations` 默认 20 → 0。

`migrateToolLoopDefaults`（与 `migratePermissionDefaultBypass` 同款可测
口径）：存量用户一次性翻 `maxIterations=0` + 落 `toolLoopMigrated` 标记
（幂等；迁移后用户手动设的上限不再被翻）。

`AgentConfig` 新字段：`toolCallParallelism` / `maxToolCallsPerTurn`；
`maxIterations` KDoc 补哨兵语义。

## 5. 测试

| 测试 | 模块 | 覆盖 |
| --- | --- | --- |
| `EngineToolLoopTest`（4） | agent-engine | 并发峰值=3 / 发言权事件顺序 / 配对不变量 / 顺序路径回归 / 单轮上限截断+延迟说明位置 / 30 轮无上限 |
| `ThinkingModeControllerTest`（+1） | agent-engine | 无上限哨兵（0/负值 → MAX_VALUE，画像不放大） |
| `RateLimitGovernorTest`（5） | llm-adapter | 关停零开销 / 顺序间隔 / 并发排队 / 策略热切换 / 零间隔（全虚拟时钟） |
| `ToolLoopMigrationTest`（3） | app | 存量翻转 / 幂等 / 新装机零行为差 |
| `BackgroundScrimTest`（5） | app | 暗色随亮加深 / 亮色随暗加厚 / 钳制边界 / 基色断言 |

本地验证：kotlinc 2.3.21（与 CI 同版本）镜像编译 core 四模块 main+test
零 error；JUnitCore 实跑 107 用例全绿（4+5+7+19+47+25）。

## 6. 兼容性说明

- `parallelToolExecution`（旧数据预埋字段）保留 JSON 兼容、标记废弃，
  UI 行已由工具循环分组取代；
- 未开启任何新设置时，全部路径行为与历史逐字节一致（默认值即旧值：
  parallelism=1 顺序、限速关、无背景图、上限 0 是唯一显式要求的默认变化
  ——见迁移）。
