# SearXNG 本地搜索开关（SearXNG Local Search v1.0）

> 状态：已实施（JVM 全绿 + CI 全绿）。真机链路（PRoot 内 pip 编译、AC1-AC12 设备验收）见文末「真机验收清单」。
>
> 规格来源：SearXNG 本地搜索开关 · 规格说明书 v1.0（本文档即其实现对照稿）。

## 1. 一句话

设置页一个开关：开启 → 在内置 PRoot Ubuntu 沙箱里装好并拉起 SearXNG 元搜索实例
（127.0.0.1:8888），Agent 搜索优先走本地，不可用静默回退远程链；关闭 → 优雅停
止不留僵尸。意图持久化，重启 App / 开机自恢复。

## 2. 架构（Spec §4 落地形态）

```
Compose UI (SettingsSearchPages.kt — search/local)
   │ 先写意图（SearxngSettingsStore）再 startForegroundService(ACTION_START/STOP)
   ▼
ApexCoreService (specialUse, 既有宿主)
   │ scope 协程 → enable()/disable()/reconcile()
   ▼
SearxngLifecycleCoordinator (platform:terminal/searxng/ — @Singleton)
   │            │                    │
   │            │                    └─ SearxngInstaller (apt→clone→venv→pip→verify)
   │            └─ SearxngSettingsWriter (settings.yml, host 直写 rootfs)
   │                 └─ keep_only 引擎过滤（use_default_settings.engines.keep_only）
   ▼
TerminalRuntime headless 会话 (linux-ubuntu 后端, workspaceId="searxng")
   │ bash 父进程 + python 子进程（前台 job —— Shell Marker 真实退出码）
   ▼
Guest: python -c "from searx.webapp import app; run_simple('127.0.0.1', 8888, …)"
   ▲
   │ HTTP JSON（PRoot 与宿主共享网络栈 —— 127.0.0.1 直达）
SearxngHttpClient (healthz / search) ── SearxngProvider ──> WebSearchTool 链首
                                                              （其后 DDG→Lite→Bing 原样保留）
```

模块边界：状态机/安装器/配置写入在 `platform:terminal`（纯 JVM 可测，函数端口
注入 healthz/意图/ubuntu 门禁）；HTTP 客户端与 Provider 在 `app`（OkHttp）；
WebSearchTool 的链首插点是 `core:tool-registry` 的 `LocalSearchSource` 端口
（null 默认 —— 既有调用方行为零变化）。

## 3. 关键实现决策（对照 Spec 的偏差记录）

| Spec 条目 | 实现 | 理由 |
|---|---|---|
| §6 Vault 键 | `SearxngSettingsStore`（SharedPreferences `searxng_prefs`） | BootReceiver 无 DI 通道需同步快读；secret_key 是本机随机数非用户凭据，不占金库 UI |
| §6 tag=searxng 进程组 | headless 会话的 `workspaceId="searxng"` | ProcessController 的进程组注册挂在会话生命周期上（AC12 以会话存在性表达） |
| §7 python -m searx.webapp | `python -c "from searx.webapp import app; run_simple(...)"` | `-m searx.webapp` 的 `__main__` 路径跨版本漂移；显式形式等价且稳定（werkzeug 是 Flask 依赖，零新增包） |
| §7 引擎 disabled 名单 | `use_default_settings.engines.keep_only` | 官方语义「只保留名单引擎」—— 比枚举 261 个引擎的 disabled 翻转稳健（AC11：仅 wikipedia → 结果只来自 wikipedia） |
| §8 Service onDestroy 兜底 | `onHostShutdown()`（不写意图）+ 既有 TerminalRuntime.shutdown() 关会话 | disable() 会写意图=false，与 F6/F7「意图保持、下次恢复」矛盾；收尾语义 = 杀进程不杀意图 |
| §9 重启 | 单一 startJob 生命线 + cancelAndJoin | 消灭「取消中的序列回写 Ready」竞态；连续 3 次失败封顶 → Failed(retryable) |

## 4. 状态机（Spec §5 全量落地）

`Off → Installing(进度,阶段) → Starting → Ready ⇄ Restarting；任意 → Stopping → Off；
失败一律 Failed(reason, retryable)`。粘性守卫：Stopping 只出向 Off；Off 仅新
start 流可离开（防陈旧协程回写）。INV1（单飞）= opMutex 决策互斥 + 单一
startJob 工作线；INV2（watchdog 存活）与 Ready 同点启动；INV4（UI 只读
stateFlow）；INV5（意图对账）三路 reconcile（App 启动 / Service onCreate /
BootReceiver action）。

## 5. 失败矩阵（Spec §12）

| 场景 | 状态 | 归因 reason |
|---|---|---|
| Ubuntu 未就绪 | Failed(retryable) | `ubuntu not ready` |
| apt/pip/clone 网络失败 | Failed(retryable) | `install network — <stage>` |
| pip 编译失败 | Failed(retryable) | `build failed — <stage>: <stderr 尾部>` |
| 端口被占（两次探测） | Failed(retryable) | `port in use` |
| healthz 30s 超时 | Failed(retryable) | `startup timeout`（附会话尾部日志） |
| 守护进程崩溃 | Restarting → Ready / Failed | 退出码观察者；预算 3 次封顶 |
| 上游引擎全挂 | Ready 不变 | Provider 空结果 → 回退链（用户无感） |

## 6. 测试与 CI

- `platform:terminal` JVM 单测：Coordinator 状态机 12 用例（虚拟时钟 + 可编程
  FakeRuntime）、Installer 幂等/断点/归因 10 用例、SettingsWriter 规格逐行
  断言 8 用例 —— 全部 runTest 纪律（零 runBlocking/Thread.sleep 新增）。
- `core:tool-registry`：WebSearchLocalSourceTest 4 用例（本地命中标记 / 空结
  果降级 / 异常降级 / 旧构造兼容）。
- CI 门禁照常：static-analysis（tool-registry 镜像编译）/ app-compile（全模块
  编译 + 单测）/ build-apk / quality-gate / guard-rails（i18n 镜像）/ changelog-gate。

## 7. 真机验收清单（AC1-AC12 对应步骤）

1. 设置 → 搜索 → 本地搜索：开开关 → 观察 Installing 进度（首次 ~5-15min，
   PRoot 内 pip 编译）→ Ready 绿点；`curl 127.0.0.1:8888/healthz` = 200（AC1）
2. 关再开：跳过安装 ≤10s 进 Ready（AC2）
3. Agent 问一个需要搜索的问题 → 日志过滤 `searxng`：WebSearchTool 命中本地
   `[searxng:google]`（AC4）；关开关后再问 → 命中 DDG 链且结果正常（AC10）
4. 关开关 → 3s 内 `ps` 无 python 残留（AC5）
5. Ubuntu 会话里 `kill` 守护进程 → 15-45s 内回到 Ready 或 Failed 可重试（AC6）
6. 重启 App（意图开）→ 自动恢复 Ready（AC7）；重启手机 → 不开 App 直接问 →
   本地命中（AC8，需通知权限已授）
7. 另起进程占 8888 → 开开关 → `port in use`（AC9）
8. 引擎只勾 wikipedia → 搜索结果全部 `[searxng:wikipedia]`（AC11）
