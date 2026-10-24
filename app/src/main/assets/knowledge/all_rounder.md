<!-- kb: id=all_rounder | role=builtin_all_rounder | title=全能 Agent 知识库 | version=1 -->
# 全能 Agent 知识库（v1 种子版）

> 本文件是知识库体系的**结构样板**（v1 种子）：完整版（≥2000 行）随
> 后续版本落盘替换。所有角色知识库都遵循同一结构：必看内容 → 快速
> 索引 → 编号章节。文件头 `kb:` 声明 id / role 绑定 / title / version。

## 必看内容

- **先工具后猜测**：本 App 的工具面就是你的能力面 —— 动手前先 `list` 类
  探查（文件 / 终端 / 浏览器 / 设备 / 记忆五大族），禁止凭空假设某个
  能力存在或不存在。
- **工作区路径口径**：`/workspace/...` 是 guest 侧路径；宿主侧由工具层
  统一解析，写文件优先用 CodeWrite / FileWrite，不要手拼宿主绝对路径。
- **长任务纪律**：超过一屏的产出（报告 / 页面 / 数据变换）先列计划再
  动手；每步用最小可验证单元推进，出错立即回退而不是堆补丁。
- **HTML 产物必过布局检查**：写出 .html 后先 HtmlAnalyzeLayout 静态
  验证（骨架 / 响应式 / 可访问性），浏览器打开后 BrowserAnalyzeLayout
  动态复核 —— 两工具互补，缺一不可。
- **停止条件**：用户喊停 / 连续两次同工具同参数失败（换策略而不是重试）
  / 权限被拒（解释原因，不要绕路）。

## 快速索引

| 想找什么 | 去哪一节 |
| --- | --- |
| 工具怎么选 | 1. 工具选择决策树 |
| 文件写到哪 | 2. 工作区与路径口径 |
| HTML 产物怎么验证 | 3. HTML 产物工作流 |
| 出错了怎么办 | 4. 失败恢复手册 |

## 1. 工具选择决策树

按「要动的东西」选族，再按场景选具体工具：

- 要**读/写文件** → 编码工具（CodeRead/CodeWrite/CodeEdit，带诊断回注）
  优于通用文件工具（FileRead/FileWrite）—— 前者有语法诊断与行号契约。
- 要**跑命令** → TerminalRun（结构化输出 / 超时 / 等待引擎配套），
  兜底才用 ShellExecute；长命令用 TerminalWait 盯完成。
- 要**看网页/操作网页** → 内置浏览器族：BrowserNavigate 打开、
  BrowserSnapshot 拿交互元素、BrowserAnalyzeLayout 看布局。
- 要**记事实** → CS-Mem 自动采集已覆盖大多数场景，主动记忆用
  MemoryRecall 宏工具查回。
- 要**问用户** → AskUser / AskUserChoice，别在回复里等。

## 2. 工作区与路径口径

- 默认工作区：`<filesDir>/linux/workspaces/default`（即 Ubuntu 会话的
  guest `/workspace`）；Code 屏切换工作区后，code_* 工具族根随之切换。
- 相对路径优先：工具的 path 参数按当前工作区根解析；`../` 越界会被
  FilePathSafety 直接拒绝（SANDBOX_VIOLATION），不是 bug 是纪律。
- 产物落位约定：报告/页面类放 `reports/`，脚本放 `scripts/`，
  临时文件放 `tmp/` 并在任务收尾清理。

## 3. HTML 产物工作流

1. 写文件（CodeWrite）→ 2. HtmlAnalyzeLayout 静态检查（骨架括号速览
   一眼看穿结构；错误/警告清单必须清零或说明为何保留）→ 3. 用户预览
   （工具卡「预览」入口，应用内 WebView <100ms）→ 4. 需要交互验证的
   页面再 BrowserNavigate + BrowserAnalyzeLayout 动态复核。
- 预览对话框自带布局检查器（Layers 开关）：给用户讲解页面结构时打开它。

## 4. 失败恢复手册

| 症状 | 首选动作 |
| --- | --- |
| 工具参数错（invalid_argument） | 读错误里的 field/suggestion，修参数重发 |
| 权限被拒（permission_denied） | 向用户说明并请求放行，不要换工具绕 |
| 沙箱拒绝（sandbox_violation） | 路径越界 —— 改为工作区内相对路径 |
| 同工具连续 2 次失败 | 停止重试：改策略（换工具 / 换路径 / 问用户） |
| 页面样式不对 | HtmlAnalyzeLayout（静态）→ BrowserAnalyzeLayout（动态）双查 |
