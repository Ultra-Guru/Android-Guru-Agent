# 双工位绝对分离 + 能力声明同源化 + 知识库多卷制

> 版本：v1（2026-10-25）· 关联模块：`core/agent-engine`（工位分轨）+ `app/knowledge`（多卷制）
> 背景：用户规格 ——「全能 Agent 不靠提示词注入、Agent 模式与 Coding 模式绝对分离、每个角色有知识库、全能 Agent 的知识库可无限扩充且有目录一键定位」

---

## 一、动机：三个「声明与事实脱节」的根因

### 1.1 两工位共享同一份系统提示词

#197 把模式拆成两屏（Agent 屏 CHAT/AGENT/LOOP；Coding 屏 BUILD/PLAN/GOAL），
工具面当时已机械隔离（`EngineToolPlanner` 前缀黑名单 + scope 隔离），但系统
提示词仍由共享的 `EnginePrompts.buildSystemPrompt` 一体构建：

- **coding 会话沿用聊天屏身份**：自称「AI AGENT」，携带 Conversational
  Openness / First-Turn Greeting / Emotional Attunement / Vague Request
  等聊天屏交互规格 —— 编码会话是任务向的，闲聊姿态是噪音；
- **agent 会话拿到 coding 专属推荐**：Task Routing 无条件推荐
  `CodeGitRun` / `GithubCreatePullRequest` —— 但这些工具恰好在 AGENT
  工位的工具计划里被剔除，模型照提示词去调用只会得到「工具不存在」。

### 1.2 「全能」靠提示词虚标

内置全能角色的 `roleDefinition` 声称「coding, shell, web, files...」，而
AGENT 工位的工具面已机械扣住 Code 族与 Github 族 —— **人设文本与工具
计划互相矛盾**。模型要么信提示词（调不存在的工具），要么信工具面
（觉得人设撒谎），两头都是输。

### 1.3 知识库单卷上限

v1 体系下 `byRoleCache` 是 first-wins 的单值映射：一个角色只能绑一套
知识库，角色知识面被单文件锁死（单文件 20,000 行解析上限）——「全能
Agent 的知识库可无限扩充」没有机制支撑。

## 二、方案总览

| 缺口 | 机制 | 落点 |
| :--- | :--- | :--- |
| 工位不分轨 | `EngineWorkstation` 枚举（模式的一等属性） | `core/agent-engine/EngineWorkstation.kt` |
| 身份/聊天 UX 混装 | 提示词按工位分轨渲染 | `EnginePrompts.buildSystemPrompt` |
| 能力声明虚标 | 任务路由从可见工具**同源派生** | `EnginePrompts.appendTaskRouting` |
| 全能人设虚标 | roleDefinition 与工位工具面对齐 | `AgentRole.ALL_ROUNDER` |
| 知识库单卷上限 | 多卷制：加卷=加文件 | `KnowledgeBaseService` + `KnowledgeBaseTool` |

## 三、双工位绝对分离

### 3.1 工位推导（单一映射）

```
AgentMode.workstation:
  CHAT / AGENT / LOOP ──────────────┐
  SPEC / REFLECTION / HUMAN_ASSIST ─┤→ AGENT 工位（聊天屏；存量枚举保守归聊天屏）
  CUSTOM ───────────────────────────┘
  BUILD / PLAN / GOAL ──────────────→ CODING 工位（编码屏）
```

存量枚举（SPEC/Reflect/Assist/Custom）只出现在老配置反序列化，按其
历史活跃屏幕保守映射到 AGENT 工位 —— 行为零意外。

### 3.2 提示词分轨矩阵

| 系统提示词段 | AGENT 工位 | CODING 工位 |
| :--- | :---: | :---: |
| 身份行（AI AGENT / CODING agent） | ✅ AGENT 版 | ✅ CODING 版 |
| Conversational Openness | ✅ | ❌ 省略 |
| First-Turn Greeting（动态） | ✅ | ❌ 省略 |
| Emotional Attunement / Vague Request（动态） | ✅ | ❌ 省略 |
| Task Routing | ✅（同源派生） | ✅（同源派生） |
| Tool-Use Policy / 权限阶梯 / 扩展攻略 | ✅ 共享 | ✅ 共享 |
| 模式段（`## Mode: X`） | ✅ 各自模式 | ✅ 各自模式 |

分轨只拆「工位形态」（身份、闲聊姿态、情感规格）；两工位都需要的
执行纪律（行动优先、权限阶梯、能力扩展梯度）保持共享 —— 分离的判据
是「这段话在另一个工位是不是噪音或谎言」，不是为分而分。

### 3.3 身份行同源

- **AGENT 工位**：保持「AI AGENT」自称；能力自述剔除 GitHub
  （Code/Github 族已被计划层机械剔除，声明与工具面同源）；
- **CODING 工位**：自称「CODING agent working in a real project
  workspace」；能力自述只列编码工具面（读改文件 / 搜索 / 构建 / git）。

## 四、能力声明同源化（去提示词注入）

任务路由段不再硬编码工具名，改由本轮可见工具集**同源派生**
（与 PrivilegeLadder ↔ CapabilityReport 的既有同源模式同构）：

| 车道 | 渲染条件 | 不可见时 |
| :--- | :--- | :--- |
| git/PR 通路（CodeGitRun + GithubCreatePullRequest） | 计划含 Code/Github/McpGithub 族 | 改写为「引导切换 Coding 工位」 |
| GitHub 权威资料（WebSearch → Github 族） | 两者都在场 | 只有 WebSearch 时退化为 WebFetch 指引 |
| 浏览器自动化（Browser 族） | 计划含 Browser 族 | 整条省略 |
| （无任何路由相关工具 / CHAT 零工具） | — | **整段省略**（推荐不可调用的工具只会自相矛盾） |

全能角色的 `roleDefinition` 同步对齐：只声明 Agent 工位机械可见的
能力面，编程/git 诉求显式引导交接 Coding 工位。**「全能」的落点从
提示词虚标迁移到**：工具面（111 个工具 + 目录检索渐进披露）+ 知识库
（多卷可无限扩充 + 索引一键定位）—— 能力声明永远跟得上机械事实。

## 五、知识库 v2 多卷制

### 5.1 机制

```
解析器/服务层：byRoleCache(单值, first-wins) → byRoleVolumes(多卷清单)
  volumesForRole(roleId)  → List<KnowledgeBase>（资产顺序，全量保留）
  forRole(roleId)         → 主卷（首卷；v1 调用方兼容口径）
  roleDefinitionHintFor()  → 多卷指引：声明卷数 + 逐卷 id + 检索工作流
加卷 = 在 assets/knowledge/ 加一个 .md 文件（role= 同角色）—— 零代码改动
```

同 id 重复仍首个生效（防检索歧义）；坏文件跳过不拖垮多卷加载。

### 5.2 检索面（目录一键定位）

- `KnowledgeBase` 工具 `list` 动作升级为**按角色分组**展示：
  「角色 X 绑定 N 卷：卷1 id=... / 卷2 id=...」；
- 每卷独立 id + 自带「快速索引」（想找什么 → 去哪一节）目录表；
- 五动作工作流不变：`must_read` → `index`/`search` → `read`，全部按卷 id。

### 5.3 首个扩充卷：全能 Agent 卷二

`assets/knowledge/all_rounder_tasking.md`（2,096 行 · 40 节 · id=
`all_rounder_tasking`）：任务工程与安全治理进阶 —— 需求工程 / 执行
治理 / 验证工程 / 上下文与记忆工程 / 安全治理 / 协作与交付六层，
与卷一（工具族与任务流）零重复、23 处「见卷一 N 节」交叉引用。
这是多卷制的实证：后续卷三、卷四……只需加文件。

## 六、测试

| 测试 | 锁定 |
| :--- | :--- |
| `WorkstationPromptSplitTest`（新） | 工位推导全矩阵 / 身份分轨 / 聊天 UX 段单侧 / 路由同源（不可见不推荐） / 共享执行脊不受伤 |
| `RolePromptTest`（适配） | 默认 BUILD 身份行走 CODING 轨道；人设层行为零变化 |
| `KnowledgeBaseServiceTest`（扩充） | 多卷资产序 / 逐卷检索 / 多卷指引带 id / 单卷退化 / 空卷空串 |

## 七、验证与影响面

- 本地：`kotlin_balance` / `check_comment_integrity` / `check_file_size`
  / `check_code_quality` 全绿；`core` 四模块 + agent-engine 测试
  kotlinc 2.3.21 镜像编译零 error（1,526 classes）；
- CI：静态分析（含上述门禁与镜像编译）→ app 编译 → 9 步测试 →
  debug APK → 结构质量四门禁（本 PR 全走既有流水线，无新 workflow）；
- 行为影响面：AGENT/LOOP 提示词变短（丢掉路由到不可见工具的推荐与
  虚标能力）；coding 提示词去掉闲聊段（任务向更纯）；其余模式与
  存量枚举零变化。
