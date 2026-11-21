# 长任务韧性 + mcp.so 社区目录 + 上下文检索增强

> 特性分支：`feat/longtask-mcpso-context`
> 关联用户反馈（三条根因，一次闭环）：
> 1. Agent 任务过程中**一遇错误就立刻停止**，不会尝试其他方式，不符合长任务需求；
> 2. 市场**不会显示 mcp.so 的 MCP**（唯一目录源是自建 8 条精选 apex-mcp-hub）；
> 3. Agent **上下文检索薄弱**（全量拼接 + 滑窗压缩，无相关性概念）。

## 一、长任务韧性（遇错不再即停）

### 根因

旧链路里任务内唯一的自动重试是「工具 payload 降级」
（`EngineToolPlanner.isToolsRelatedRejection` → 逐级降级重试）。
其余任何失败路径都是**直接终结任务**：

- LLM 异常 → `executeBuildLoop` catch → `throw e` → `execute()` catch →
  `AgentEvent.Error` → `finally` 发 `Complete`，任务结束（唯一重试入口是
  用户手动点「重试」）；
- 空响应 → 直接 `Error("Empty response from LLM")` 返回；
- 工具失败 → 虽不终结任务（错误回灌 LLM），但单次执行零重试、零换路引导。

### 方案：引擎级韧性守卫（EngineResilienceGuard）

新增 `core/agent-engine/.../EngineResilience.kt`，经引擎构造参数注入
（默认启用；测试可注入 `EngineResiliencePolicy.DISABLED` 回退旧行为）：

| 层 | 机制 | 预算（默认） |
|---|---|---|
| LLM 瞬时错误 | 指数退避（1.2s→2.4s→…上限 10s，±25% 抖动）后**重试同一轮**（不消耗迭代配额） | 3 次/任务 |
| 空响应 | 退避后重试同一轮 | 2 次/任务 |
| 工具瞬时失败 | FailureClassifier 判 TRANSIENT/TIMEOUT → 退避重试同一调用 | 2 次/调用 + 6 次/任务 |
| 连续工具失败 | 连续 3 次失败 → 注入系统级换路提示（换工具/换参数/换分解） | 3 次/任务 |

瞬时判定口径：

- `ModelRuntimeException`：`ModelUnavailable / ModelRateLimited / ModelTimeout`
  重试；鉴权失败 / 请求被拒 / 配置错误 / 降级链耗尽**不**重试（重发同一请求
  无意义，按旧语义上抛）；
- 裸异常（SingleClientModelRuntime / 测试路径）：复用 orchestrator
  `FailureClassifier` 的 TRANSIENT/TIMEOUT 启发式（网络断连/超时/429/5xx…）。

重试可见性：退避期间发 `AgentEvent.ThinkingChunk("[engine] …")` /
`ToolOutputChunk("[engine] …")` 提示（**不**发 `Error` 事件 —— 避免误置
UI 的 isLoading=false 与 TaskRuntime 的错误终态化）。

文件预算：`executeToolCallStreaming` 迁出为同包扩展
`EngineToolExecution.kt`（与 EngineAskUserFlow / EngineCompressionGate 同
模式），`ApexAgentEngine.kt` 1195 → 1111 行（预算 1200 内）。

### 复用而非新写

工具重试直接复用 A68.2 休眠韧性件（此前仅测试可达）：
`FailureClassifier` + `RetryPolicy`（指数退避 + 抖动 + 任务级 RetryBudget），
语义与 orchestrator 完全一致：只重试 TRANSIENT/TIMEOUT，权限/致命失败立刻
回灌 LLM 自行换路。

## 二、MCP 市场：mcp.so 社区目录

### 根因

全仓库 0 处 mcp.so 引用 —— 市场唯一 MCP 目录源是自建
`apex-mcp-hub`（8 条精选），社区长尾（1.5 万+）完全不可见。

### 方案：McpSoSource（core/tool-registry，纯 JVM）

mcp.so 无公开 JSON API（`/api/*` 被 Cloudflare 拦截，HTML 路由对任意 UA
正常返回，已实测 okhttp UA 200），采用 SSR HTML 解析：

- **目录**：`GET mcp.so/servers?page=N`（60 条/页）→ 正则解析卡片
  （slug/名称/作者/描述/分类/热度/verified/featured 徽标）；
- **安装配置**：`GET mcp.so/servers/{slug}` 详情页 `<code class="font-mono">`
  块 —— 内容即社区通用 `{"mcpServers": {...}}` JSON，交给既有
  `McpConfigImport` 统一解析（headers/env/command 全兼容）；
- **安装策略**：`enabled=false`（安装 ≠ 启动，与官方 Hub 口径一致）；
  STDIO 条目自动 `runInSandbox=true`（mcp.so 的 stdio 几乎全是
  npx/uvx/docker，Android 宿主无 node 运行时 → PRoot Ubuntu 沙箱内跑，
  与 Issue #163 一脉相承）；
- **错误契约**：任何失败 `Result.failure`（与 HubSource/ClawHubSource
  一致）；README 无标准配置块的服务器明确报错引导去详情页，绝不静默空安装。

App 层：`MarketMcpSoController`（与 MarketHubController 同构的独立状态域：
分页/加载更多/行级 busy 安装）+ `MarketMcpSoSections`（行卡 UI）+ MCP 页签
接入 + DI（`MarketplaceModule.provideMcpSoSource`）+ 双语字符串。

### 实测（真实页面）

- `/servers` 页解析 60/60 条（名称/作者/分类/热度/徽标全提取）；
- medplum / hostinger（remote `type:"http"`）/ google-search（带 headers）
  详情页配置提取 + 解析 + 安装参数全部正确；
- 分页 `?page=2` 正常（不足 60 条即判定末页）。

## 三、上下文检索增强（BM25-lite 相关性）

### 根因

- `buildMessages()` = 系统提示 + **全量**历史；超阈值后
  `SlidingWindowCompressor` 把中间段压成一条规则摘要（取**前 3 条**用户
  消息 + **前 3 条**工具结果 —— 与任务无关的早期闲聊占据摘要，关键细节
  反被丢弃）；
- `ContextSearch` 工具是纯子串匹配 —— 模型记错一个词（"apikey" vs
  "api key"）就零命中。

### 方案：TextRelevance（共享 BM25-lite 评分器，CJK 感知）

新增 `core/tool-registry/.../util/TextRelevance.kt`（纯函数、零依赖）：

- 分词：ASCII 词 + CJK 二元组（孤立单字保留）；
- BM25（k1=1.5/b=0.75）+ IDF —— 高频停用词自动降权；
- 模块归属 tool-registry（agent-engine 依赖它，双模块共用一份实现）。

三处接入：

1. **压缩保留（SlidingWindowCompressor）**：以「当前任务锚点」（保留窗内
   最后一条 User 消息）对被压缩段做相关性排序 —— 摘要选录改为相关性
   选录；额外注入 `[RELEVANT RECALL]` 块（top-3 相关消息逐字摘录，每条
   ≤300 字），压缩后模型仍能引用任务关键细节（报错原文/参数/约束）；
2. **会话检索（ContextSearch）**：新增 `mode` 参数（auto|exact|fuzzy，
   默认 auto）—— 精确零命中自动 BM25 回退（`~#index [role]` 标记），
   换词/转写也能找到；
3. `relatedness` 便捷口径供后续记忆管线复用。

## 验证

| 项 | 结果 |
|---|---|
| kotlinc 2.0.21 编译（logging→llm→tool-registry→agent-engine，CI 同配方） | ✅ 0 error |
| tool-registry 全量单测 | ✅ 604/604 |
| agent-engine 全量单测 | ✅ 559/559 |
| 文件预算（main ≤1200 / test ≤1600） | ✅ check_file_size.sh |
| 反模式门禁（无反射派发 / 无 printStackTrace） | ✅ check_code_quality.sh |
| 括号平衡 | ✅ kotlin_balance.py（7 文件） |
| mcp.so 真实页面解析 | ✅ 目录 60/60 + 三台真实服务器配置提取 |
| 新增测试 | TextRelevanceTest(12) / McpSoSourceTest(12) / EngineResilienceTest(19) / ContextToolsTest fuzzy(3) |

App 层改动（MarketMcpSoController / MarketMcpSoSections / MarketViewModel /
MarketBrowseTabs / MarketplaceModule / strings）沿用既有模式，由 CI 的
`:app:compileDebugKotlin` + 结构质量门禁把关。

## 兼容性说明

- `EngineResiliencePolicy.DISABLED` 完整保留旧「遇错即停」行为（测试断言
  基线）；
- `ContextSearch` 默认 auto（模糊回退）：两个纯精确语义的存量测试改传
  `mode=exact`（测试意图 —— 窗口裁剪 / system 跳过 —— 不变）；
- 压缩摘要文本格式扩展（新增 `[RELEVANT RECALL]` 块），`CompressionCompat`
  事件与 checkpoint 语义零改动（559 个引擎测试全绿）。
