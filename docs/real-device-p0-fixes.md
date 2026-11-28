# 真机「极其难用」P0 根因与修复（real-device-p0-batch）

> 背景：仓库主人在真机测试中反馈 agent「极其难用」，而开发自测（root 设备 / 模拟器 /
> CI）几乎不复现。本批修复来自对全仓（agent-engine / tool-registry / privilege /
> llm-adapter / terminal / app UI + 111 篇 docs + 60 版 changelog）的静态走读，
> 结论：**不是打磨问题，而是 5 个确定性 P0 缺陷叠加，且每一条都恰好被开发环境绕开。**
> 每条修复均标注证据文件与复现方法；CI 只能证明编译与单测，**真机回归仍是最终验收**。

## 0. 一页总览

| # | 缺陷 | 真机体感 | 修复 |
|---|------|----------|------|
| P0-1 | CORE 工具 75 个 > 请求预算 64 条，名字序尾部 11 个（`Write / WebSearch / WebFetch / ToolSearch / ToolOpen / ToolList / TerminalRun/Observe/Wait/Snapshot / Time`）每轮**静默丢弃**，`droppedByBudget` 全仓无消费者 | 「不会写文件、不会联网、找不到工具」；提示词却仍在教模型用 `ToolSearch` | 两级预算：CORE/forced 无条件准入，扩展条目共享 48 条 / 48KB；丢弃落日志 |
| P0-2 | 计划层角色过滤读 `AgentConfig.grantedRoles`，该字段**从未被 DI 赋值**（恒空集）；目录层/调用层读 `BrowserRoleAuth` 热值 | 41 个 `browser_*` 工具从未进入请求；关限制 / 激活专家模板都无效 | `EngineRoleGrants` 动态源接入计划层（子代理不继承） |
| P0-3 | 开无障碍后：Swipe/滚动/长按落 `Unsupported action` 且不回落 shell；UiDump 只看 DFS 前 20 个扁平节点、不递归；InputText 只有 `input text`（不支持中文）；`dispatchGesture` 返回值被丢弃 | **「权限越给越坏」倒挂**：推荐配置下眼手全残，关无障碍 + root 反而正常 | 补 Swipe/PressKey 分支、手势校验 + 300ms 静置、UiDump 递归剪枝、InputText 接 ACTION_SET_TEXT、a11y 失败回落 shell |
| P0-4 | 命令门子串误判（`git add`→"dd "、`platform `→"rm "、`--format`→"format"）；询问桥单槽覆盖 | 高频 git 工作流每步弹「危险命令」，后台时 5 分钟后按拒绝；并行工具两弹窗互相覆盖 | 词边界匹配；并发提问串行化 |
| P0-5 | VISION 路由要求 `imageInput`，17 个内置 Provider 只声明 `vision` 从未声明 `imageInput` → 交集恒 false | 发任何图片立即「模型不具备该能力」 | vision 蕴含 imageInput（两级）；手输模型 id 同款能力预填；错误文案给设置指引 |
| P1 | 确定性失败被子串判成瞬时失败盲重试 7 次；列表输出固定砍成 15+10 行；`make/node/pip` 子串触发「先装 Ubuntu」；rootfs 逐文件 fsync；`proot --version` 无记忆化；每条命令 fork `su -c id`；Markdown 流式防抖饿死 | 「假死」「变笨」「先装系统」「首装半小时」「正文不出字」 | 见各节 |

## 1. 工具预算（P0-1）

**证据**：`ToolTierPolicy.CORE_TOOL_IDS` 实际 75 个；`ToolRequestBudget.MAX_TOOLS=64` 自 v4
初版未动（10-18 `3ac831b5` 起 CORE 越界）；`AndroidConnectedServicesProvider` 注释仍写
「CORE ~45」；`ToolSystemV4CatalogTest` 只用 ≤11 个工具的小注册表，从未钉住「真实 CORE 放得下」。

**修复**：`ToolRequestBudget` 两级准入——优先级条目（CORE / forced）无条件进入；扩展条目
（会话激活 + 已连接服务 + exposeAll 长尾）共享 `MAX_EXTENSION_TOOLS=48` / `MAX_TOTAL_BYTES=48KB`；
输出仍按 provider 名字序（prompt-cache 口径不变）。引擎侧 `EnginePlanDiagnostics`
把 `droppedByBudget` 落为 warn（30s 节流）。

**验证**：真机抓一次请求体 `tools` 数组（PromptDump），断言含 `Write / WebSearch /
ToolSearch / TerminalRun`；日志中 `tools dropped by request budget` 仅在扩展层超预算时出现。

## 2. 浏览器角色授予（P0-2）

**证据**：`AgentConfig.grantedRoles` 默认 `emptySet()`，全仓无赋值点；`EngineToolPlanner
.applyRoleIsolation` 据此剔除全部带 `requiredRoles` 的工具；`RoleGate` 与 `ToolSearch`
却读 `BrowserRoleAuth.currentGrants()`。`docs/browser-runtime.md` 承诺的「设置关闭即全会话可用」
在计划层从未兑现。

**修复**：新增 `EngineRoleGrants`（进程级动态源），`CodeModule` 装配引擎时
`install { BrowserRoleAuth.currentGrants() }`；`buildToolPlan` 每轮 `resolve(config)`。
`AgentConfig.inheritDynamicRoleGrants=false`（子代理工厂）时回退快照，保持
「子代理默认不继承 browser 角色」的 v2.1.0 口径。

**验证**：设置页关闭「浏览器角色限制」→ 下一条消息的 PromptDump 工具清单含
`BrowserNavigate`；激活专家模板同款。

## 3. 无障碍执行层（P0-3）

**证据**：`DefaultPrivilegeManager.executeViaAccessibility` 无 `Swipe / PressKey` 分支；
`PrivilegeUiProvider.dumpUiTree` 遍历的是剥离了 children 的 `nodes` 却 `take(20)` 且
`dumpNode` 不递归；`InputTextTool` 只接 `shellExec`；`dispatchGesture` 返回值被丢弃。

**修复**：
- `executeViaAccessibility`：补 Swipe（Path + duration 钳制）、PressKey（BACK/HOME/RECENTS
  走全局动作，其余回落 Root/Shizuku `input keyevent`）；全局动作与手势都校验系统返回值；
  手势成功后静置 300ms（紧随的 UiDump 不再抓到过渡页面）。
- `PrivilegeUiProvider.dumpUiTree`：遍历 `roots` 真实嵌套树，后序剪掉「无语义且无有语义
  后代」的装饰节点，上限 200 节点 / 7600 字符并在尾部标注截断。
- `UiInteractionProvider.inputText`（默认 null = 不支持）：`PrivilegeUiProvider` 经
  `UiAction.InputText` 走 ACTION_SET_TEXT；`InputTextTool` 优先无障碍，不可用回落
  `input text` 并对非 ASCII 文本追加「会静默丢字」告示。
- `gestureWithShellFallback`：UiTap / UiSwipe 的 a11y 手势失败回落 shell `input`。

**验证**：开无障碍（无 root）设备：UiSwipe direction=up 滚动生效；UiDump 输出含可点击
`[C]` 节点与文本；InputText 输入中文成功；关无障碍 + root 设备行为不变。

## 4. 命令确认门与询问桥（P0-4）

**证据**：`CommandPermissionGate.highRiskPatterns` 对整条命令 `contains`；仓库测试注释曾承认
「`git add` 本就会确认」；`UserQuestionBridge` 单 `answerDeferred`，并行 `ask` 覆盖。

**修复**：命令词/短语改词边界正则（`(?<![A-Za-z0-9_-])` / `(?![A-Za-z0-9_])`），
`/bin/rm`、`busybox rm`、`VAR=1 rm`、`"r"m` 仍命中；`format` 移除（无此破坏性二进制），
`fusermount` 补入。`UserQuestionBridge.ask` 以 Mutex 串行化，排队等待可取消。

**验证**：`git add .` / `git commit -m "Refactor platform module"` / `git log --format=%H` /
`npm run format` 直通；`rm -rf /`、`pm uninstall`、`git push --force`、`echo x > /dev/sda`
仍弹窗。并行度 ≥2 时两个需确认的工具依次弹窗、各自可答。

## 5. 图片输入（P0-5）

**证据**：`ModelProfile.builtInProviders` 全部只写 `vision = true`；`CapabilityResolver.effective`
对 `imageInput` 逐位 AND；`ModelSetupCard` 仅在 /models 选择时 `enrichCapabilities`。

**修复**：`effective` 中 `imageInput = (provider.imageInput || provider.vision) &&
(profile.imageInput || profile.vision)`；手输模型 id 时同样预填能力位；
`LlmErrorText.CAPABILITY_MISMATCH` 指向「设置 → 模型」能力开关。

**验证**：默认 OpenAI 档案 + `gpt-4o`：粘贴截图 → 走 VISION 角色不再报能力缺失。

## 6. P1 项

| 项 | 文件 | 要点 |
|----|------|------|
| 失败分类 | `FailureClassifier` | `DETERMINISTIC_PATTERNS` 先于瞬时词表（刻意不含裸 "not found"，DNS 仍可重试） |
| 列表截断 | `ToolOutputTruncator.truncateList` | 首尾按 head/tail 字符预算整行填充 |
| 工具链信号 | `TerminalProactivityAdvisor` | 词边界匹配 `make / node / pip …` |
| rootfs 解压 | `RootfsExtractor` | 移除逐文件 `fd.sync()`（staging + 原子 rename 保证一致性） |
| proot 探针 | `NativeLibraryPRootBinaryProvider` | `--version` 结果记忆化 |
| 选路缓存 | `PrivilegedCommandSpawner.resolve` | 走 `PrivilegeDetector.getPrivilegeLevel()` 30s TTL；`refreshStatus` 失效缓存 |
| 流式正文 | `MarkdownText` | 常驻 worker + `Channel.CONFLATED`，解析不随内容取消 |

## 7. 未在本批处理（建议后续）

- 压缩门不计系统提示词 + 工具 schema（≈20k token 固定开销）、切模型不刷新分母。
- LLM 10 级 / 工具 7 级重试阶梯在弱网下累计 440s / 317s；`CodeTask` 90s 必杀 300s 子代理、
  `CodeGitRun` 60s 必杀 600s 请求的超时错配。
- Coding 屏时间轴被编辑器 / Todo / 悬浮栈挤没；返回键取消任务且恢复后无续跑说明；
  等待提问无系统通知。
- `ToolOutputTruncator` 对 `Read/CodeRead` 的专用分支不可达（已被列表分支覆盖）。
- T76 长任务体系（暂停 / 恢复 / 崩溃续跑）生产零接线。
