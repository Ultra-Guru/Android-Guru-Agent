# 浏览器角色门禁与异步导航协议（v2.1.0）

## 五层架构

Agent 智能层（模型决策/检查点判断）→ Browser Task Orchestrator（一次提交自主推进）→ Fast Action Engine + Event Engine + Snapshot Engine + Scheduler（TabWorker）→ apex-kit-browser 引擎（同进程）。Authorization（本文）与 Observability（BrowserTracer 只观测不判权）横切。

## 角色模型（默认严格）

- **browser-automation**：常规浏览器工具。授予来源：①设置「浏览器工具仅限专家角色」**关闭**时全会话默认授予；②模板工坊内置「浏览器自动化专家」模板激活（`toolRole` 仅 isBuiltIn 模板生效——自定义/导入模板不可自授特权）。
- **browser-sensitive**：execute_js/get_cookies/file_upload/clear_data 四件套，需设置「允许浏览器敏感操作」显式授权，且逐会话弹确认（三重门：角色×2 + sensitiveAction）。
- **三处强制**：EngineToolPlanner 计划层（forced/allowed/default 三路径，子代理默认不继承）→ RoleGate 调用层（授予集每次现取，设置热生效）→ 目录层（ToolSearch/ToolList/CapabilityReport roleFilter；CapabilityReport 的 Browser 行未授权整行省略）。
- **会话隔离口径**：单设备单活动会话——授予集对当前活动会话生效；多会话并行各自隔离属未来工作。

## 异步导航协议

- navigationId（任务）/ docId（文档 `d<tabId>g<generation>`）/ tabId 三标识分离；docId 在文档提交时赋值（SPA pushState 不递增；重启归零，旧快照必然 STALE）。
- 双状态机：navigation_status（STARTED/COMMITTED/FAILED/CANCELLED/SUPERSEDED/TAB_CLOSED/ENGINE_DISPOSED/EXPIRED）⊥ readiness_status（WAITING/DOM_READY/SELECTOR_MATCHED/NETWORK_IDLE/TIMEOUT/NOT_DETECTED）。**等待超时=停止等待不取消导航**；提交超时=导航失败。
- 同 tab 新导航 → 旧导航 SUPERSEDED（晚到协程写不进状态）；等待者广播唤醒；**人工接管（WAITING_HUMAN）优先**——在途任务逐步中止。
- 事件游标：按 docId 分桶（≤128/桶，留 3 桶），oldestSeq/nextSeq/truncated 丢失可检测，淘汰桶返回 EVICTED；终态 TTL 5 分钟惰性折叠 EXPIRED。
- network_idle：750ms 静默窗口；监控未注入诚实报 NOT_DETECTED。

## 工具面（41 个 browser_*）

BrowserTask（一次提交 ≤12 步：navigate/wait_for/fill/click/press_key/scroll/extract；预校验零副作用；歧义检查点返回候选 ref；不承诺回滚；total_timeout 兜底；mode=background+taskState）· BrowserNavigateAsync · BrowserDocState · BrowserDocEvents · BrowserDocControl(cancel|release) · BrowserSnapshot 增 mode=focused/diff + 返回 doc_id · 8 个 ref 工具可带 expected_doc_id（STALE_DOCUMENT 闭环）。

## 授权主体矩阵

| 调用主体 | 授权方式 |
|---|---|
| Agent 会话 | Session grantedRoles（AgentConfig）+ RoleGate |
| 功能性插件（宏） | manifest.permissions（三态 fail-closed）+ 步骤权限 |
| APK 插件 | 经 PluginHostBridge——**不含 async 协议 5 工具**（fail-closed），legacy 插件浏览器能力默认不授、首次调用走用户确认流（待续：per-connection binder 身份快照） |
| MCP Host | 分类白名单（默认不含 BROWSER）+ blocklist（四件套默认封）+ 宿主执行层敏感角色全入口防线 |

## 回退

- 角色门禁：设置开关关闭即回到"全会话可用"现状。
- 插件权限：legacy manifest 保持非浏览器能力照旧。
- 异步路径降级：kit 侧 `EngineRuntime.asyncEnabled`（内部开关，异常时降级阻塞语义）。

## 性能基准（docs/benchmark.md）

实测数字按基准协议回填（P50/P95，分冷/热启动与页面复杂度），不预设倍数口径。
