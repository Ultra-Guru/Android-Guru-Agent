# Agent.md — 完成任务前必看（本仓库专用规则）

> 本文件与 [AGENTS.md](AGENTS.md) 并存：AGENTS.md 管构建纪律 / CI 门禁 /
> Kotlin 陷阱 / 测试纪律；本文件管**任务边界与仓库地图**。两者冲突时以
> 安全约束最高、其余按本文件优先。任何 agent 在本仓库干活前必须先读完
> 本文件再动手。

## 一、绝对边界：不要看 Theocracy 仓库

- **[Theocracy](https://github.com/Ultra-Guru/Theocracy) 是独立的纯聊天 App 仓库**，
  与本仓库（AI 编码工作台）已彻底分家。完成本仓库的任何任务都
  **不需要、也不允许**去读它的代码 / 文档 / 发布页；
- 两个项目的下载入口不要混淆：本 App 的安装包永远从
  [Android-Guru-Agent-Release](https://github.com/Ultra-Guru/Android-Guru-Agent-Release/releases/latest)
  取，聊天版才是 Theocracy 的 Releases；
- 任务里提到「聊天模式 / Agent 聊天屏 / 六档思考」等历史概念时，它们
  已随拆库迁移出本仓库 —— 按本仓库现状（Coding 屏 + 四条思考逻辑线）
  理解，不要去 Theocracy 佐证。

## 二、流水式输出 UI 的修改红线

Coding 屏有两种流水式输出 UI（设置 → 工作区 → Coding 工位 →
「流水式输出UI」，枚举见 `CodeOutputStyle`）：

| 选项 | 持久化值 | 实现 | 修改红线 |
| --- | --- | --- | --- |
| **默认** | `timeline` | 经典胶囊时间轴（`CodeStreamTimeline` + `CodeCapsule` 族） | **可以改**——任务没特别声明时，默认流水 UI 的胶囊视觉 / 动效 / 状态呈现均可按需求调整 |
| **rikka思考方式UI** | `rikka` | Rikka 聊天式（`RikkaStreamTimeline` + `RikkaCards` / `RikkaToolBlock` / `RikkaThinkingPresentation`） | **任务没有明确提到「rikka 流水式输出方式」时一律忽略，千万不要修改** |

- 判断标准只有一条：任务原文是否点名 rikka（如「rikka 思考方式 UI」
  「改 rikka 流水输出」）。没点名 → rikka 全链路零改动；
- 两种风格共享的部分（`CodeStreamSession` 状态机 / `CodeStreamSnapshot`
  数据通道 / 终端胶囊 / `StreamingCodeWindow`）属于公共底座，可以按需
  修改——但改动必须同时验证两种皮肤渲染不破；
- 改默认（timeline）流水 UI 时注意 rikka 侧复用组件（`capsuleStyle` /
  `statusColor` / `statusIcon` / `capsuleDisplayTarget` / `FileLanguageIcon`）
  的行为兼容：只增不删、语义不反转。

## 三、附属仓库地图（改到哪层就去哪个仓库）

本仓库是多模块 Gradle 主仓，部分能力已拆成独立库仓（composite build：
本地克隆成兄弟目录即自动接入，CI 按 `gradle/*.lock` 锁定版本检出）。
**改这些库的行为，去对应库仓库提 PR，不在本仓库改**：

| 仓库 | 是什么 | 坐标 / 接入 |
| --- | --- | --- |
| [apex-kit-browser](https://github.com/Ultra-Guru/apex-kit-browser) | **浏览器引擎库**（BrowserEngine / chrome UI / DOM 解析；本仓 `:platform:browser-agent` 只留工具协议与 DI 接线） | `com.apex.browser:{core,engine,chrome}`；`gradle/browser-kit.lock` |
| [apex-kit-standard](https://github.com/Ultra-Guru/apex-kit-standard) | **标准任务循环库**（Coding「标准思考逻辑线」的循环内核：画像 / 权限门 / 压缩 / 子代理 / 并行工具 / 续跑 / MCP 指引） | `com.apex.standardkit:standard-loop`；`gradle/standard-kit.lock` |
| [apex-kit-loom](https://github.com/Ultra-Guru/apex-kit-loom) | **织造循环库**（Coding「织造思考逻辑线」的内核：in-band 标记协议 / 梭回合机 / 权限三态门 / 子线程群 / digest 压缩） | `com.apex.loomkit:loom-weave`；`gradle/loom-kit.lock` |
| [apex-kit-courier](https://github.com/Ultra-Guru/apex-kit-courier) | **信使调度库**（Coding「信使思考逻辑线」的内核：驿传式任务调度） | `com.apex.courierkit:courier-dispatch`；`gradle/courier-kit.lock` |
| [apex-kit-workspace](https://github.com/Ultra-Guru/apex-kit-workspace) | **工作区浏览器库**（Coding 全屏文件树 / 多格式预览 / Git 面板 / 变更产物 / 实时跟随） | `com.apex.workspace:{core,explorer}`；`gradle/workspace-kit.lock` |
| [apex-kit-memory](https://github.com/Ultra-Guru/apex-kit-memory) | **认知记忆库**（统一记忆域：会话键控情景 / 差分摄取 / 蒸馏纪律 / 能量衰减 / 拓扑别名） | `com.apex.memkit:memory-core`；`gradle/memory-kit.lock` |
| [apex-native-vt](https://github.com/Ultra-Guru/apex-native-vt) | **终端原生 VT 引擎库**（C++17 VT100/ANSI JNI 热路径；本仓 `:terminal-native` 为 vendored 消费层） | vendored；升级走上游仓库发版 |

## 四、发布与镜像仓库（产物在哪里）

| 仓库 | 是什么 |
| --- | --- |
| [Android-Guru-Agent-Release](https://github.com/Ultra-Guru/Android-Guru-Agent-Release) | **发布仓库**（正式版 APK / version.json / xdelta3 增量补丁 / patches.json；PR 合并进 main 即全自动构建发布，双落点之一） |
| [Android-Guru-Agent](https://github.com/Ultra-Guru/Android-Guru-Agent) | **开发仓库 = 本仓库**（同样镜像创建同 tag Release，软件在本仓库也可下载；Gitee 镜像自动同步） |

- 本仓库不发「纯文档 PR 也要动版本号」的规矩不存在，但**改用户可感知
  代码必须同时写 `.changelog/unreleased/` 片段**（changelog-gate CI 会拦）；
- 版本号在 `app/build.gradle.kts`，发布流水线自动处理冲突序号与
  versionCode 递增。

## 五、任务收尾清单

1. 本文件红线逐条自检（没碰 Theocracy / 没动 rikka（除非点名））；
2. AGENTS.md 的 CI 门禁自检（文件 ≤1200 行 / 无反射派发 / 括号平衡
   `python3 scripts/kotlin_balance.py <files>`）；
3. i18n 镜像：`values/` 与 `values-zh/` 的 string 键集与占位符 1:1；
4. `.changelog/unreleased/` 片段已写（文件名 = `日期-主题.md`，含
   `<!-- pr: 分支名 -->` 锚）；
5. 提交信息格式：`类型(范围): 中文摘要 —— 关键词 / 关键词`；
6. PR 描述附验证表（编译 / 测试 / 门禁结果），由仓库主人合并。
