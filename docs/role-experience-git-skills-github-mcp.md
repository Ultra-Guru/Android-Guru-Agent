# 角色经验卡 · Git 专属技能 · GitHub MCP 配置（v1.9）

> 用户规格三件套的架构落地：①「每次任务结束后自动提炼经验，下次同类任务
> 开始时检索注入」；②「Git 角色专属 skills，模板工坊可逐个开关，默认全开」；
> ③「GitHub 官方 MCP 放输入框的 GitHub 图标里，点击可配置，非 Git 角色
> 专属」。

## 总览图

```
AgentEvent 流（引擎执行）
   │
   ├─→ LongTaskTracker ──────────→ 长任务记录（规模画像，阈值入库）
   │
   └─→ RoleExperienceTracker ─────→ 角色经验卡（每轮必提炼，零门槛）
                                      │
        ┌─────────────────────────────┼──────────────────────────┐
        ▼                             ▼                          ▼
   确定性提炼                   每角色一卷 JSON               召回注入
   (Extractor 规则引擎)        (Store: role_experience/)     (Recall 打分)
   场景标签/工具序列/            原子写/有界裁剪/             top-3 相关经验
   下次注意(R1-R7)              hitCount 强化                 → session extras
```

```
模板工坊（TemplateStudioScreen）
   └─ 点击角色行 → RoleDetailSheet（v1.9 交互升级）
        ├─ 激活入口（withCodingRoleActivated → 人设热切换）
        ├─ 经验卡列表（单删/清空；空态文案讲沉淀机制）
        └─ Git 专属技能开关（9 套，默认全开；AgentSettings.roleSkillToggles）
                │
                ▼ 稀疏存储：缺席 = 开；关 = false；重开 = 移除键
        CodeRoleController.distinctUntilChangedBy(roleId, toggles)
                │
                ▼
        GitRoleSkillEngine.definitionHintFor(启用集)
                │  拼进 Role definition（人设通道，与专属知识库同管线）
                ▼
        引擎系统提示词：紧凑目录（触发词/摘要/安全红线）
```

```
输入框 GitHub 图标（GithubIconButton）
   └─ 下拉「GitHub MCP 配置」→ GithubMcpConfigDialog（非角色专属）
        ├─ 内置 github（进程内 7 工具）：setEnabled / connect
        └─ 官方 github-official（api.githubcopilot.com 全量工具面）
             ├─ ${GITHUB_TOKEN} 令牌桥（PAT 零落盘）
             └─ 工具集订阅：?toolsets= 参数（全选=服务器默认；子集=显式订阅）
```

## 1. 角色经验卡（core/code-engine/experience）

### 设计立场：代码化，不是提示词化

「下次注意什么」这个维度最容易退化成让模型自由发挥的提示词 —— 本包
反其道而行：**全部结论来自可复现的规则表**（[RoleExperienceExtractor]），
同一份输入在任何设备、任何模型上提炼出同一条经验：

| 规则 | 触发条件 | 产出 |
|------|----------|------|
| R1 失败根因 | FAILED + 错误文本 | 错误首行 + 类别化行动建议（编译/权限/网络/超时/未找到/JSON 六族） |
| R2 工具链健康 | 失败率 ≥30% 且样本 ≥3 | 「先核对参数/路径再批量执行」 |
| R3 已验证路径 | COMPLETED + 零失败 | 「工具序列可直接复用」 |
| R4 高频工具 | top2 合计 ≥60% | 点名主力工具 |
| R5 长任务拆步 | 迭代 ≥8 | 「先列 TODO 拆步」 |
| R6 改动面广 | 文件 ≥5 | 「先确认影响范围」 |
| R7 短路完成 | ≤2 轮 ≤3 调用 | 「同类直接做」 |

场景分类同样代码化：`ExperienceScenario` 枚举 + 中英关键词表（子串匹配，
≤2 标签，枚举声明序保证同输入同输出）。

### 四要素结构（用户口径 → 字段）

| 用户口径 | 字段 |
|----------|------|
| 什么场景 | `scenario`（goal 首句）+ `scenarioTags`（代码分类） |
| 用了什么工具序列 | `toolSequence`（**真时间序**，相邻折叠 `Tool×n`）+ `toolProfile` |
| 结果如何 | `outcome` + 计数族（iterations/toolCalls/toolFailures/filesTouched） |
| 下次注意什么 | `notes`（规则引擎） |

### 召回打分表（RoleExperienceRecall，纯函数）

标签交集 +3/个 → 词元交集 +2/个（拉丁词 + CJK 单字双通道）→ CJK 子串
+2 → hitCount 强化 +0.5×(hit-1)（封顶 5）→ 7 日新鲜度 +1 → FAILED ×1.25。
门槛 1.5 分：无关经验不注入（稀释比缺失更糟）。注入段 ≤1600 字符，
top-3。

### 强化闭环

被注入的经验 `hitCount+1`（fire-and-forget 回写）——「被反复验证的经验
更可信」的排序信号；裁剪时弱卡（hitCount 低 + 最旧）先淘汰（单卷
≤120 张）。

### 与长任务中心的分工

| 维度 | LongTaskTracker | RoleExperienceTracker |
|------|-----------------|------------------------|
| 入库门槛 | 长任务阈值 | 零门槛（≥1 工具或 ≥1 迭代） |
| 工具形态 | 频次表 | 真序列 |
| 产出 | 审计画像 | 复用经验 + 强化信号 |

纯聊天轮不沉淀（无可复用的执行证据）—— 诚实门槛。

### 接线（app 层）

- `CodeViewModel.runEngine`：beginRun（当前激活角色领卡）→ collect 里
  `onEvent` → finally `endRun(taskStatus.name, finalError)`；
- 注入走 session extras 通道（与「小圆环」同路）：
  `roleExperience.enrichExtras(base, goal)` —— 每轮 run 刷新；
- DI：`RoleExperienceModule`（store + tracker + Supervisor IO scope）。

## 2. Git 角色专属技能（core/code-engine/gitskill）

### 为什么不装进 SkillRegistry

- SkillRegistry 技能是全局的（per-scope agent/coding），没有角色归属
  语义 —— 装进去会泄漏给所有角色，违背「Git 角色专属」；
- 开关语义不同：市场技能默认关（装≠开），角色专属技能**默认全开**，
  开关面在模板工坊的角色详情里；
- 注入通道不同：技能目录经角色 definition（人设通道，随角色热切换），
  不走斜杠菜单。

### 9 套技能（上游归属逐一标注）

本地工作流域：git-advanced-workflows（sickn33/antigravity-awesome-skills）、
git-advanced（AutumnsGrove/ClaudeSkills）、git-workflows（Conventional
Commits + agent-safe）、github-actions-advanced（生产加固语义）。

跨仓库/发布域：sync-repos（MilkClouds/my-dev-playbook）、relay-merge
（dEitY719/gh-flow-skills）、gh-cherry-pick（REST API 工具语义）、
github-multi-repo（DreamLab-AI/agentbox）、github-release-management。

### 代码化的形态

每套技能 = 触发词表（`triggers`，中英子串匹配）+ 阶段化 runbook
（`phases`：目标/命令模板/**验证方式** —— 没有验证的步骤链只是提示词，
有验证的才是管线）+ 安全护栏（`guards`，破坏性操作批准线）+ 适用判据
（`whenToUse`，多技能间选择的决策辅助）。

「他可看情况选择使用哪个」：`GitRoleSkillEngine.matchScores(taskText)`
给模型一份带理由（hits 透明化）的推荐清单；注入段是**紧凑目录**
（渐进披露），完整 runbook 由 `renderRunbook` 按需展开。

### 开关存储（默认全开的存储形态）

`AgentSettings.roleSkillToggles: Map<skillId, Boolean>` —— **缺席 = 开**。
关闭 → 写 false；重新开启 → **移除键**。稀疏存储让升级新增技能天然
全开，无需迁移。归属判定双判据：`builtin_coding_git` id 或
roleDefinition 前缀 `Git power user`（「另存为」副本原样携带定义段，
技能跟随副本）。

热切换：`CodeRoleController.distinctUntilChangedBy(codeActiveRoleId to
roleSkillToggles)` —— 模板工坊里拨开关，下一轮请求即生效。

## 3. GitHub MCP 配置（输入框 GitHub 图标）

用户规格：「github mcp 要放在输入框的那个 github 图标，点击后可以在
那里配置；这个 GitHub 官方 mcp 不是 git 角色专属」。

- `GithubIconButton` 下拉新增「GitHub MCP 配置」项（连接/未连接两态
  恒在）→ `GithubMcpConfigDialog`；
- 两台服务器同屏管理：内置 `github`（进程内 7 工具）与官方远程
  `github-official`（api.githubcopilot.com 全量工具面）；
- 官方服务器的**工具集订阅**：`?toolsets=` 查询参数（官方文档口径），
  10 个工具集复选（repos/issues/pull_requests/actions/code_security/
  users/orgs/notifications/discussions/gists）；全选 = 无参数 = 服务器
  默认集；勾选变更 → URL 重建（保留 Authorization 占位符头）→ 已连接
  则重连生效；
- PAT 前置：官方服务器要求 Bearer PAT，经 `${GITHUB_TOKEN}` 令牌桥从
  GithubTokenManager 解析（真 token 零落盘）；未连接 GitHub 时显示
  引导文案；
- 配置直接落 `mcp_servers.json`（McpManager 单一数据源 —— 市场页/
  斜杠菜单/本对话框三处同源），`changes` 流驱动对话框自动刷新。

## 测试

- `RoleExperienceStoreTest`（12 用例）：roundtrip / 分卷隔离 / 删清 /
  bumpHits / 有界裁剪 / 坏卷隔离 / 路径穿越防御 / 跨实例重载；
- `RoleExperienceExtractorTest`（18 用例）：场景分类 / 序列压缩 /
  R1-R7 逐条 / 确定性复现；
- `RoleExperienceRecallTest`（16 用例）：打分口径 / 召回排序门槛 /
  注入段 / Tracker 全链（含未收尾自动 ABORTED）；
- `GitRoleSkillCatalogTest`（16 用例）：9 套完整性与唯一性 / runbook
  结构 / 上游标注 / 归属判定 / 开关语义 / 匹配路由 / 注入段 / 渲染；
- `GithubMcpToolsetsTest`（11 用例）：全选=无参数 / 部分选择官方序 /
  roundtrip / 未知 key 防御 / 坏参数回退。
