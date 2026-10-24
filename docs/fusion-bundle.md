# FusionBundle：复合技能包体系（聚变包）

> 状态：已落地（v3 自定义聚合 + Claude 导入，2026-10）
> 主题：把「技能 + MCP 服务器 + 功能插件 + agent 工作流」聚合为一个可一键
> 激活的装载单位 —— 复合体开源项目（REA 等「MCP 服务器 + 工作流技能 +
> 工具」三位一体形态）的标准化引入通道；v3 起用户可**自由组合**已安装
> 实体成为自己的聚合包，并可**一键导入 Claude 生态插件包**。
> 前置：[hub-ecosystem.md](hub-ecosystem.md)（技能 / MCP 仓库生态）
> · [plugin-system-v2.md](plugin-system-v2.md)（功能插件体系）
> · `core/tool-registry/.../mcp/`（内置 MCP 服务器七件套 —— REA 第六台 / Shrimp 第七台）

---

## 0. 一句话

**聚而不同（aggregate, not fuse）**：一个 FusionBundle 把若干**既有体系**
的实体聚到一起 —— 技能装进 SkillRegistry、MCP 服务器连进 McpManager、
agent 工作流注入引擎 system prompt —— 三大体系的注册表、热更新管道、
作用域隔离全部**原样复用**。FusionBundle 层只做编排（安装、连接、注入、
恢复），**不新建任何平行注册体系**。用户在聊天输入栏点**王冠图标**（FUSION
层级菜单）选用后，选中包以 chip 形式出现在输入框中，agent 将**强制使用
该体系**完成任务（详见 §2b 王冠入口）。

## 1. 背景：为什么单技能装不下顶级开源项目

既有技能（apex-skill-v1）是「一份说明书」：promptInjection + 可选的组合
工具声明。但 GitHub 趋势榜上的顶级「skills」项目——以 REA（Reverse
Engine Anything，morluto/rea，MIT，npm rea-agents，Trendshift 榜）为
典型代表——是**复合体**：

| 组成 | REA 里的形态 | 单技能体系的问题 |
| --- | --- | --- |
| MCP 服务器 | 一个 207 工具的 MCP server | 技能不携带服务器连接语义 |
| 工作流技能 | setup 时写入的 SKILL.md 指令集 | 只能手动逐条安装 |
| 工具面 | 207 个 MCP 工具（一等公民化后可用） | 无「随包整体可用」的装载单位 |
| agent 人设 | 工作流内的角色与路由纪律 | 无激活即注入的通道 |

「多个技能 + 多个 MCP + 工具需要**共同运行**」正是复合体的本质——逐个
安装、逐个连接的体验是碎片化的。FusionBundle 把它们定义在一个 manifest
里，一键聚合激活。

## 2. 架构总览

```
      输入栏 👑 王冠按钮 ──► FUSION 层级菜单（第一项 FUSION → 第二级各包）
      FusionList / FusionInfo / FusionActivate（模型侧自主闭环，v5 PascalCase）
                        │ （两者共用同一控制面）
              ┌─────────▼──────────┐
              │ FusionBundleController（app 层 @Singleton）
              │  bundles/activeBundle StateFlow · 持久化重激活
              └─────────┬──────────┘
                        │ 编排（三端口 —— 聚合不融合的执行面）
     ┌──────────────────┼──────────────────────┐
     ▼                  ▼                      ▼
 SkillInstallerPort   McpConnectorPort     EnginePromptPort
 （→ SkillRegistry）  （→ McpManager）     （→ ApexAgentEngine.patchConfig
  install 原样复用     ensureBuiltinServer   additionalSystemContext 读改写）
  依赖校验/autoSetup/  / addServer+connect
  HotReloader 热注册   / Registrar 一等工具化
     │                  │                      │
     └──────── FusionBundleActivator（core，纯 JVM 可测）
                FusionBundleRegistry（filesDir/fusion-bundles/<id>.json）
                FusionBundleManifest（schema fusion-bundle-v1）
```

### 2b. 王冠入口 · 层级菜单 · 强制使用（v2）

用户钦定的黄金路径：**王冠图标 → 层级菜单 → 选包 → 输入框 chip →
agent 强制使用**。

```
输入框工具栏：  [/] [👑] [+] [🧠] ...
                     │ 点击
                     ▼
        ┌────────────────────────────┐
        │ 👑 FUSION          7  ▾    │ ← 第一级第一项：体系缩写名
        │   工具聚合系统 · 技能+MCP+工具  │
        │    ├─ REA · Reverse Engineer Anything v6.5.0 [内置] ✓使用中 [停用]
        │    ├─ Superpowers  v7.0.0 [内置]
        │    ├─ SuperClaude / BMAD / Anthropic Skills …
        │    └─ （描述 + 技能·MCP·工具·引擎计数）
        │    点选体系包会固定进输入框，agent 将强制使用该体系完成任务。
        └────────────────────────────┘
                     │ 点选 REA
                     ▼
输入框：  [👑 REA · Reverse Engineer Anything] 分析这个 app.asar
                     │ 发送（chip 拼回 /fusion:rea + 附加文本）
                     ▼
        激活侧：系统提示注入「### 强制使用协议」（常驻）
        发送侧：agentPrompt 携带【FUSION 体系强制使用】三条款（逐次）
        ===== 双保险：agent 必须优先且强制使用该体系资源完成任务 =====
```

- **王冠图标**：形制临摹开源图标库 Lucide 的 `crown`（ISC License，
  https://lucide.dev/icons/crown），逐锚点移植为 Compose 矢量
  （`ui/icons/FusionCrownIcon.kt`）；输入栏按钮用 Canvas 精修版
  （王金渐变描边 + 三颗冠尖宝石 + 激活态微光呼吸），徽章/横幅用
  ImageVector 版。
- **体系缩写名**：菜单第一级第一项 = **FUSION**（大写缩写名，
  FusionBundle 工具聚合系统的门面）；点开透出第二级具体体系包。
- **强制使用双保险**：①激活时系统提示注入「强制使用协议」段
  （`FusionBundleActivator.buildActivationPrompt`）；②发送时
  `/fusion:<id>` 路由产出【FUSION 体系强制使用】提示词
  （`SlashCommandRouter.routeFusion`）——「必须优先且强制使用 /
  不得静默绕过 / 不可用须给降级证据」三条款。
- **输入框 chip**：选中包以 `PendingPipelineCommand(type="fusion")`
  挂进输入框（👑 前缀标签）；发送时拼回 `/fusion:<id>` 走既有斜杠
  管线（单 chip 重构 / 多 chip 管线均兼容）；手键 `/fusion:rea` 同效。
- **流水线视觉**：`ToolKind.FUSION` 王冠徽章 + 王金色
  （`AgentChatToolCards` / `PipelineBannerCard`）。
- **交互细节**：锚定 Popup + 上方不足 320dp 自动向下翻转 +
  focusable=false 不抢键盘焦点 + BackHandler 兜底（与斜杠菜单同款）。

### 2c. 兼容性强化（v2）

- **MCP 连接重试**：首连失败冷却 400ms 重试一次（服务器启动 /
  网络抖动容错），仍败才进 skipped；
- **人设注入容错**：patchConfig 异常折叠进 `skipped`（prompt:… 前缀）
  不弃激活 —— 技能/MCP 已就绪部分照常可用；
- **手键兼容**：`/fusion:<id>` 全语法（args / 附加文本）经解析器
  一等支持（`SlashCommand.Fusion`）；
- **多 chip 兼容**：fusion chip 与 skill/mcp/connector/plugin chip
  混排多选，逐 chip 路由合并单轮执行。

模块落点（全部纯 JVM，CI 镜像编译链与 JVM 单测全覆盖）：

| 文件 | 职责 |
| --- | --- |
| `core/tool-registry/.../fusion/FusionBundleManifest.kt` | manifest 模型 + 校验（id 路径安全 / skillId 一致 / schema 门） |
| `core/tool-registry/.../fusion/FusionBundleRegistry.kt` | 存储/装卸/changes 流/bundled 保护 |
| `core/tool-registry/.../fusion/FusionBundleActivator.kt` | 三端口编排 + 激活提示组装 + 聚合容错 |
| `core/tool-registry/.../fusion/FusionBundleTools.kt` | FusionList / FusionInfo / FusionActivate 工具族 |
| `core/tool-registry/.../fusion/FusionBundlePresets.kt` | 内置预置目录（七大体系，见 §4/§7） |
| `core/tool-registry/.../fusion/rea/` | REA 引擎全套（见 §4） |
| `core/tool-registry/.../fusion/FusionAnthropicSkillsPreset.kt` / `FusionClaudeFlowPreset.kt` / `FusionShrimpPreset.kt` | 第三波三体系预置（文档生产力 / SPARC 群智 / 任务规划） |
| `core/tool-registry/.../fusion/shrimp/` | Shrimp 引擎全套（16 工具 + JSON 持久化 + 第七台内置 MCP transport） |
| `app/.../fusion/FusionBundleController.kt` | 控制面 + 三端口适配 + 持久化重激活 |
| `app/.../fusion/ReaRuntimePorts.kt` | 命令执行/页面抓取端口实现（复用终端链/OkHttp） |
| `app/di/FusionModule.kt` / `McpModule.kt` / `ToolModuleFusionRegistration.kt` | DI 接线（第六/七台内置 MCP + fusion 工具注册 + Shrimp 任务存储单例） |
| `app/ui/icons/FusionCrownIcon.kt` | 王冠矢量（Lucide crown 移植）+ Canvas 精修版（渐变/宝石/呼吸） |
| `app/ui/component/FusionBundleButton.kt` | 王冠入口按钮 + FUSION 层级菜单 + 激活态横幅 |
| `app/slash/SlashCommand.kt` / `SlashCommandRouter.kt` | `/fusion:<id>` 解析与强制使用路由（发送侧保险） |

## 3. Manifest 规范（fusion-bundle-v1）

```json
{
  "schema": "fusion-bundle-v1",
  "id": "rea", "name": "REA · Reverse Engineer Anything", "version": "6.5.0",
  "description": "…", "trustLevel": "verified", "bundled": true, "scope": "agent",
  "agent": {
    "persona": "逆向工程智能体",
    "activationPrompt": "…（激活注入的工作流：路由/证据纪律/降级诚实）",
    "routingHints": "ASAR→analyze_javascript_application；APK→inspect_android_package…"
  },
  "skills": [
    { "skillId": "ReaWorkflow", "manifest": { "…完整 apex-skill-v1 manifest…" } }
  ],
  "mcpServers": [
    { "serverId": "rea", "transport": "BUILTIN", "autoConnect": true, "scope": "agent" }
  ],
  "toolActivationHints": ["McpReaOpenBinary", "…"],
  "runtime": {
    "engines": [
      { "name": "objdump", "purpose": "…", "optional": true,
        "installHint": "PRoot Ubuntu 终端: apt install binutils" }
    ]
  }
}
```

- **skills[]**：内联完整 apex-skill-v1 manifest —— 激活时装进 SkillRegistry
  （依赖校验、autoSetup、SkillHotReloader 热注册自然生效）；`skillId` 必须
  与内联 manifest.id 一致（防装配错位，parse 期校验）。
- **mcpServers[]**：扁平字段（serverId/transport/BUILTIN|STDIO|HTTP/
  runInSandbox/scope…）—— 激活时 `toMcpServerConfig()` 转既有
  McpServerConfig 连进 McpManager（BUILTIN 走 ensureBuiltinServer 幂等
  预置；STDIO/HTTP 走 addServer+connect）。
- **agent**：人设 + activationPrompt + 路由速查 —— 激活时经引擎
  patchConfig 拼进 additionalSystemContext 的 Session Context 段（去激活
  恢复原值）。
- **runtime.engines[]**：BYO 引擎声明（optional）—— 缺失只降级对应工具，
  不阻塞激活（与上游 REA「BYO Hopper/Ghidra/JADX」哲学一致）。
- 前向兼容：全字段默认值 + ignoreUnknownKeys —— 上游演进新增字段旧端不炸。

## 4. 首发内置包：REA（功能不弱于上游）

上游（rea-agents v6.5.0）实测 `tools/list` = **207 个工具**。本实现的
工具目录**逐名对齐**（同名同域，描述为上游英文契约首句 —— 模型可无差别
迁移既有逆向工作流）；差异只在执行层的**五级分层**：

| 分层 | 数量 | 语义 | 与上游对比 |
| --- | --- | --- | --- |
| ON_DEVICE | 72 | 纯 Kotlin 深实现：ASAR/JS 应用图、ELF/PE/Mach-O 布局、ZIP/APK/IPA 清点、AXML manifest、dex 类清单、Flutter/Dart AOT、SQLite/plist/EVM/Go/Rust、证据系统、会话与 unknowns 台账 | 上游同类工具无需引擎即可跑 —— 我们同样设备直跑，零依赖 |
| DEVICE_SHELL | 25 | adb 族映射为**设备本地执行**（am/pm/dumpsys/logcat/settings/screencap/ps） | 上游需 PC + adb；我们跑在设备上 —— **原生更强** |
| SANDBOX_BRIDGE | 34 | PRoot Ubuntu 引擎桥（objdump/rizin/gdb/frida/jadx/apktool/tshark/binwalk/wabt） | BYO 引擎哲学一致：装了就能用，缺失给结构化降级证据 + 安装指引 |
| WEB_FETCH | 15 | OkHttp 静态抓取 + 脚本 import 分析 + webmcp 标记发现 | 上游经 CDP；我们静态子集（限制如实声明） |
| PROVIDER_GATED | 61 | 桌面分析引擎（Hopper 桥 54）/ V8 Inspector attach / CDP 浏览器场景 | 上游同样需要外部供应商；缺失时降级证据 + 可用替代路径指引 |

**证据纪律**（上游结果契约的核心）：所有工具结果渲染统一 Evidence 信封
（findings / evidence / limitations / unknowns 四元组 + digest）；降级不是
报错而是 `ok=false + degraded{reason, recovery}` —— 诚实告知与恢复指引。
会话内保留证据账本（binary_session / get_evidence_bundle /
export_evidence_bundle / import_evidence_bundle）。

**载体**：第六台进程内内置 MCP 服务器 `rea`（`ReaBuiltinMcpTransport`，
架构同 search/fs/memory/thinking 五件套）—— McpManager 工厂注册，
FusionBundle 激活器负责 ensureBuiltinServer + connect（未激活不自动连）。
连接后 207 工具经 McpToolRegistrar 自动一等公民化（`McpRea*`，v5 组合规则 Mcp{Server}{Tool}），
引擎 64 工具预算 + 渐进披露（tool_search/tool_open）自然裁剪 —— 与
MCP 生态的注册表模式完全同轨。

## 5. 激活状态机

```
  ┌──────────┐  activate(id)   ┌──────────────┐
  │ 未激活    │ ──────────────► │ 激活中        │
  │（无提示） │                 │ 装→连→注入    │
  └──────────┘                 └──────┬───────┘
      ▲   restore()                   │ 全部端口完成（部分失败进 skipped）
      │◄──────────────── deactivate() ▼
  ┌──────────────────────────────────────┐
  │ 已激活：skills 已装 + MCP 已连 +      │
  │ 人设常驻 Session Context（持久化，   │
  │ 重启后台幂等重激活）                  │
  └──────────────────────────────────────┘
```

- **聚合容错**：单个组件失败（技能装失败 / 服务器连不上）进
  `FusionActivation.skipped`，整体激活仍成功 —— 已就绪的部分照常可用。
- **去激活语义**：恢复引擎提示原值；技能与 MCP **留在各自体系**（聚合
  不融合 —— 用户可在市场单独关停，技能卸载走 skill_uninstall）。
- **作用域**：包 scope 与工位不符时拒绝激活（agent 包不可在 coding 工位
  激活）；scope="all" 的包两工位通用。
- **bundled 保护**：内置包不可卸载（与 SkillManifest.bundled 同防线），
  升级幂等替换（版本守卫）。

## 6. 安全边界

| 面 | 防线 |
| --- | --- |
| manifest 注入 | id 路径安全正则（防穿越落盘）；skillId↔manifest.id 一致性校验；schema 门 |
| 归档解压 | 复用 SafeZipExtractor（路径穿越防御 + zip bomb 三重上限） |
| MCP 劫持 | ensureBuiltinServer 幂等不劫持用户配置；同名服务器拒绝覆盖（McpManager 既有防线） |
| 引擎提示注入 | additionalSystemContext 读改写（保留既有内容）；去激活精确恢复原值 |
| 引擎命令执行 | 走 shell_execute 同一条 ExecEngine + ProotCommandSpawner 链（权限链 + 超时 + 采集上限）；引用参数单引号转义 |
| scope 隔离 | 包级 scope 透传 McpServerConfig.scope / SkillManifest.scope —— 引擎计划层自动过滤 |

## 7. 内置复合开源项目目录（七大体系）

FusionBundle 是**通用装载单位**，不绑定 REA。首发即内置七包（全部
`bundled=true` 首启幂等释放，`scope=all` 全工位可用）：

| 包 | 上游 | 形态 | 聚合内容 |
| --- | --- | --- | --- |
| `rea` | morluto/rea v6.5.0（MIT） | MCP 服务器 + 工作流技能 + 工具 | 207 工具内置 MCP + 目标路由技能 + 证据纪律人设（§4） |
| `superpowers` | obra/superpowers v7.0.0（MIT） | 纯方法论技能集 | 8 技能（brainstorming/writing-plans/executing-plans/TDD/systematic-debugging/verification/code-review/finishing）+ using-superpowers 引导人设；上游无 MCP，如实保持零服务器 |
| `superclaude` | SuperClaude-Org v4.3.0（MIT） | 命令 + 代理 + 模式 + 增强 MCP | 5 模式技能（Brainstorming/DeepResearch/TokenEfficiency/Orchestration/BusinessPanel）+ 人设；上游 8 台增强 MCP 的设备端等价映射 —— Tavily→`search`、Sequential-Thinking→`thinking`、Serena→`memory`、代码智能→`github`（四台内置 MCP 聚合；Playwright/DevTools→一等 browser_* 工具族） |
| `bmad` | bmad-code-org v6.12.0（MIT） | 敏捷交付环代理体系 | 5 环阶段技能（Clarify/Plan/Shard/Build/Verify）+ 编排者人设；增益层聚合 thinking + search 两台内置 MCP（超出上游） |
| `anthropicskills` | anthropics/skills（官方技能体系） | 文档生产力 + 技能工程 | 8 技能：文档四件（Word/Excel/PPT/PDF，经 PRoot Ubuntu Python 工具链真实产出）+ skill-creator/mcp-builder/webapp-testing/frontend-design；上游无 MCP 如实保持零服务器。**许可如实**：上游文档技能 source-available（© Anthropic 保留所有权利）—— 本移植为**原创等能力重述**（零上游文本/脚本复制）；工程类技能对齐上游 Apache-2.0 方法论 |
| `claudeflow` | ruvnet/ruflo（claude-flow）v3.56.10（MIT） | SPARC 方法论 + 群智编排 + 持久记忆 | 7 技能：五阶段（Specify/Architect/TDD/Review/Complete）+ SparcSwarm（Task 子代理并行派发）+ SparcMemory（知识图谱沉淀）；上游增强 MCP 的内置等价映射 —— sequential-thinking→`thinking`、自学习记忆→`memory` |
| `shrimp` | cjo4m06/mcp-shrimp-task-manager v1.0.21（MIT） | 结构化任务管理 MCP | **16 工具全目录进程内实现**（第七台内置 MCP：规划/执行/验证/反思/研究/思维链，上游 docs/tools.md 逐名对齐）；任务数据 JSON 原子写持久 + 清档自动备份；超出上游的零依赖形态（无需 Node/npx/网络） |

扩充包的能力对齐原则与 REA 相同：**主干技能全量移植**（方法论
正文进技能 promptInjection，v5 PascalCase id）、**人设路由浓缩**上游
引导指令、**MCP 聚合映射**优先复用内置服务器（零子进程零网络握手）。
methodology 类项目无 BYO 引擎面，runtime.engines 仅 git/python 可选项；
anthropicskills 的文档工具链为 BYO Python 库（PRoot Ubuntu pip 安装，
缺失时技能内如实指引装库）。
Shrimp 是 REA 之后的第二个「进程内深实现」包：工具面逐名对齐上游，
执行层为纯 Kotlin（依赖 DAG 门禁 / 反思洞察账本 / 项目规则常驻 /
思维链环形缓冲），任务数据跨连接持久。

后续引入其他复合开源项目同口径：
1. 目标项目拆解为 skills[] + mcpServers[] + agent 人设（MCP server 可用
   STDIO + runInSandbox 直接引用 npm 包 —— 沙箱内 npx 启动，同
   apex-mcp-hub 的远端目录模式）；
2. manifest 落 `fusion-bundles/` 或经市场 JSON 通道分发（index.json 索引
   同构，见 hub-ecosystem.md）；
3. 激活语义/作用域/信任边界全部复用 —— 零新概念。

路线图：市场「聚变包」货架（复用五货架 UI）· 斜杠菜单 fusion 分类 ·
Coding 工位入口 · 包级依赖（bundle 间引用）。

## 8. v3：plugins[] 聚合段 · 自定义组合器 · Claude 插件包导入

**「1 个技能是聚合，5 个技能 + 2 台 MCP + 1 个插件混搭也是聚合」**
——v3 把聚合的构成权交给用户：

### 8.1 plugins[] 聚合段（第四端口）

manifest 新增 `plugins[]`：内联完整功能插件 plugin.json（`FusionPluginRef`，
manifest 以不透明 JsonObject 载体内联 —— core 模块不依赖 plugin-sdk，
`pluginId` 与内联 id 一致性 parse 期校验）。激活器扩第四端口
`FusionPluginInstaller`（app 层 `FusionModule.PluginInstallerPort` →
`UnifiedPluginRegistry.installFunctionalPlugin`，`dagger.Lazy` 破环：
控制器→激活器→UPR→ToolRegistry→控制器的环在 Lazy 处断开）——插件安装
的全部校验（id 规范/命名空间/版本门/贡献点形状）落在端口侧 fail closed，
失败进 `skipped` 不弃包（与技能/MCP 同款聚合容错）。

### 8.2 自定义组合器（用户自由聚合）

`FusionBundleComposerDialog`（王冠菜单 →「组合我的聚合包」/ 自建包行
「编辑」图标）：从已安装技能 / 已配 MCP / 已装功能插件三源勾选成员 +
命名 + 可选人设 → `FusionBundleComposer`（core 纯函数：slugify id 生成 /
版本递增 / 描述拼装）→ `registry.install`。自建包 `bundled=false`：
可编辑（版本递增覆盖）、可删除、可导出 JSON 分享。成员清单王冠菜单
`custom-` 前缀「自建」徽章区分。

### 8.3 Claude 插件包导入（.claude-plugin → fusion-bundle-v1）

王冠菜单 →「导入 Claude 插件包（.zip）」：SAF 选 zip →
`SafeZipExtractor`（防穿越/zip bomb）→ `ClaudePluginImporter`：

| Claude 段 | 映射 |
| --- | --- |
| `.claude-plugin/plugin.json` | 包元数据（id `claude-<slug>` / 版本 / 描述） |
| `skills/**/SKILL.md` | apex-skill-v1：frontmatter name → `SkillNaming.canonicalId` PascalCase（kebab/snake 归一），正文 → promptInjection |
| `commands/*.md` | 功能性插件斜杠指令（`claude.<slug>.commands`，经 plugins[] 段搭载，激活即装 —— `/plugin:<id>` 可用） |
| `agents/*.md` | 聚合 agent 人设（首个为主人设，其余并入激活提示） |

产物即一个自洽聚合包，与内置七大包完全同权。命名映射全部安装期强校验
（fail closed：结构不合法整体拒绝，不装半截包）。

## 9. 测试

纯 JVM（JUnit4 + runTest + TemporaryFolder，手写 fake，无 mock 框架）：

| 文件 | 覆盖 |
| --- | --- |
| `fusion/FusionBundleCoreTest.kt` | manifest 校验五防线 / registry 装卸幂等与 bundled 保护 / 激活器三端口聚合语义 / 强制使用协议段 / 容错（含 MCP 重试 + 注入容错）/ 去激活恢复 / scope 拒绝 |
| `slash/SlashCommandParserTest.kt` / `SlashCommandRouterTest.kt` | `/fusion:<id>` 解析（args/附加文本）与强制使用路由（三条款 / routeKind / chip 拼回形态） |
| `fusion/FusionBundleExpansionTest.kt` | 前四波预置契约（id 唯一 / 技能 id v5 PascalCase / MCP 聚合清单 / 上游版本溯源）/ 三包激活聚合（技能·MCP·人设）/ 多包切换语义（聚合不融合：实体只增不减）/ 七包 bundled 保护 |
| `fusion/FusionBundleWaveThreeTest.kt` | 第三波三包契约（AnthropicSkills 8 技能零 MCP + BYO Python 引擎面 / ClaudeFlow 七技能 + thinking·memory 聚合 / Shrimp 2 技能 + 16 工具 MCP）/ 三包激活聚合 / 七包共存切换（实体只增不减）/ bundled 幂等 |
| `fusion/shrimp/ShrimpTaskEngineTest.kt` | 16 工具全目录：规划链（plan 归档 + update 五防线：空表/缺描述/悬空引用/自引用/环）/ 依赖纪律（前置门禁 + 洞察随身）/ 执行链（complete 证据与下一个建议 / verify 先完成门 / reflect 入账）/ 记忆链（list·query·detail / split 依赖转接 / delete 引用清理）/ 清档备份与规则保留 / 研究与思维链（环形上限 20 不落盘）/ 跨引擎实例持久 / JSON-RPC 传输三方法 + 16 工具 tools_list |
| `fusion/rea/ReaAnalyzersTest.kt` | ASAR 目录与单文件 / import 三族语法 / 应用图渲染 / ELF64 布局与 vaddr 映射 / search_bytes / AXML package 提取 / dex 类清单 / Flutter 识别 / plist/sqlite/demangle/EVM |
| `fusion/rea/ReaEngineTransportTest.kt` | 目录 207 条与分层统计恒定 / tools_list 渲染 / tools_call 真跑分析 / 降级信封 / 未知工具错误 / 会话证据账本 / unknowns 闭环 / JSON-RPC 协议（initialize/list/call/通知/未知方法） |

本地验证：`scripts/run_core_tests_jvm.sh` → 全绿（core 镜像 1379/1379；fusion 族 93：核心 16 + 扩充 12 + 第三波 10 + REA 34 + Shrimp 21）；
`scripts/check_file_size.sh` / `check_code_quality.sh` /
`check_comment_integrity.py` / `kotlin_balance.py` / `check_string_mirror.py`
全绿。CI（app-compile + 9 步模块测试）验证 Android 侧装配。
