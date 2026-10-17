# 技能市场双工位完全隔离 + GitHub 精选 + 就绪门禁

> 关联：docs/scope-isolation.md（#197 工位作用域原始设计）、docs/hub-ecosystem.md（市场生态）。
> 本文覆盖其后的三步演进：per-scope 启停（v2）、GitHub 精选专栏、配置就绪门禁。

## 1. 问题（用户反馈三连）

1. **市场的 coding 与 agent 分级没有完全隔离** —— [SkillRegistry.setEnabled] 只有
   一个全局布尔（`.disabled` 平面 sidecar），市场分级（Agent 市场 / Coding 市场）
   只过滤列表可见性；在任一侧开关，另一侧、两屏 `/` 菜单、引擎工具注册同时变。
2. **两屏 `/` 菜单同步** —— [SlashMenuProvider] 是全局单例菜单，条目只带 manifest
   静态 scope；导入型技能（GitHub/URL/ClawHub/魔搭/粘贴 JSON）manifest 一律
   缺省 `scope=all`，于是在 Agent 屏和 Coding 屏同步出现。
3. **未配置 / 未启动的条目出现在 `/` 输入菜单** —— APK 插件把「发现但未加载」
   的条目列为可选指令（NOT_INSTALLED 状态可点）；技能侧则完全没有配置概念
   （`UserConfigField` 是死 schema）。

## 2. per-scope 启停（核心：生效作用域 = manifest 声明 ∩ 双工位启用）

### 2.1 存储模型（三 sidecar，旧安装零迁移）

```
skills/
  .disabled          # 双工位同禁（旧格式原样保留 —— 升级用户语义不变）
  .disabled-agent    # 仅 Agent 工位禁用（市场 Agent 分级开关写）
  .disabled-coding   # 仅 Coding 工位禁用（市场 Coding 分级开关写）
  .config.json       # 用户配置值 {skillId: {key: value}}（见 §4）
```

`InstalledSkill` 从单一 `enabled: Boolean` 升级为 `enabledAgent` / `enabledCoding`，
`enabled` 降级为派生属性（双工位至少一开，兼容旧消费方口径）。

**写入口径**：

| API | 语义 | 调用方 |
|---|---|---|
| `setEnabled(id, enabled)` | 双工位同开闭（旧行为） | 安装工具 / 内置释放升级 / 旧测试 |
| `setEnabledForScope(id, scope, enabled)` | 只写指定工位 | 市场分级开关（[MarketViewModel.toggleSkill]） |

从「双禁」里单开一个工位时，另一工位的禁用态自动落进对应单工位 sidecar
（`.disabled` 不承载混合态，保持旧格式语义纯粹）。

### 2.2 读口径（effectiveScopes 贯穿全部读者）

```kotlin
effectiveScopes(skill) = normalize(manifest.scope) ∩ {agent?, coding?}
```

- `getSkillDigests(scope)` / `getPromptInjections(scope)` /
  `getActivePromptInjections(activeIds, scope)`：`scope in effectiveScopes` 才返回；
- `getActiveToolsWithScope()`：manifest 声明 `all` 但只在单工位启用的技能，
  其工具以该工位 scope 注册（另一侧引擎的 planner 不可见）；生效集合为空 → 不注册；
- 公开只读视图 `effectiveScopesOf(skillId)` 供菜单 / 市场行消费。

### 2.3 安装默认工位（defaultScope 盖章）

`MarketInstallManager.installSkillFromJson(content, trusted, defaultScope)`：

- **新装**：`defaultScope = "agent"/"coding"` 时默认只在来源分级工位启用
  （另一侧去对应市场自行开启）—— 修「装完就两边亮」；
- **升级重装**：保留用户既有 per-scope 启停态（install 会重置双开，装后恢复）；
- 全部市场源接线：URL / 粘贴 JSON / ClawHub / 魔搭传当前市场分级；
  Hub 技能传条目声明的 scope；GitHub 精选传 "coding"（见 §3）；
  模型侧 `skill_install` 工具与内置模板维持旧行为（双开）。

## 3. GitHub 精选（真实顶级开源仓库 coding 技能专栏）

Skills 页签第四个仓库源 `GITHUB`（本地 ⇄ 官方仓库 ⇄ ClawHub ⇄ **GitHub 精选**）。

- 目录：`app/marketplace/GithubFeaturedCatalog.kt`（纯数据）—— 收录纪律
  **只收真实可验证仓库**，收录时逐一核实过 SKILL.md 与 frontmatter：
  - **anthropics/skills**（Anthropic 官方 Agent Skills 仓库，~181k★）11 条：
    mcp-builder / web-artifacts-builder / webapp-testing / frontend-design /
    algorithmic-art / canvas-design / skill-creator / docx / pptx / xlsx / pdf；
  - **obra/superpowers**（软件开发方法论技能框架，~300k★，MIT）8 条：
    test-driven-development / systematic-debugging / writing-plans /
    executing-plans / verification-before-completion / requesting-code-review /
    dispatching-parallel-agents / using-git-worktrees。
- 安装：条目 → `GithubRepoCandidate(skillMarkdownUrl, scope="coding")` → 既有
  `installGithubCandidate` 管线（raw SKILL.md 下载 → frontmatter 严格校验 →
  prompt 型 manifest（scope 盖章 coding）→ 安装确认门禁 → 安装即装备）。
  **零新增安装代码**；安装 id 与「智能安装整仓再挑选」同口径
  （gh-owner-repo-subdir 归一化），两条路互不冲突。
- 隔离：manifest scope=coding → 只出现在 Coding 市场分级与 Coding 屏 `/` 菜单。

## 4. 就绪门禁（没有配置好的 / 没有启动的，不进输入框）

### 4.1 技能配置（把死 schema 激活）

- `UserConfigField` 新增 `required`（默认 true）；
- 判定规则（`SkillConfigRules`，纯函数）：
  `required=false` → 就绪；已存值非空白 → 就绪；manifest 默认值非空白 → 就绪；
  其余 → 缺失。全部字段就绪 = 技能「配置好」；
- 配置值持久化 `.config.json`（原子写：tmp + renameTo，rename 失败直写兜底），
  **跨 manifest 升级存活**（manifest 文件会被升级覆写，值必须住 sidecar）；
- 市场闭环：卡片「需配置」徽标 → 详情对话框配置表单段（boolean → 开关，
  其余 → 文本框，enum 选项作 supportingText）→ 保存 → registry 发 changes →
  斜杠菜单自动重建（条目即刻出现）。

### 4.2 消费方门禁

- **斜杠菜单**：`SkillMenuProvider.getActiveSkills` 剔除未配置技能；
  `SlashMenuItem` 新增 `enabledScopes`，`SlashMenuData.forScope` 双重过滤
  （manifest scope 匹配 **且** 该工位已启用）—— Agent 屏与 Coding 屏各归各；
- **自动装备**：`SkillAutoActivator.autoEquip(text, scope)` 按发送方工位过滤 +
  未配置不装备（AgentChatViewModel 传 "agent"）；
- **APK 插件**：`/plugin:` 类目只列**已加载（运行中）**的插件；
  发现未加载的 NOT_INSTALLED 条目不再出现（MCP 类目此前已 gated 到 connected）。

## 5. 已知边界（不变）

- [SkillActivationStore] 仍跨工位共享（scope-isolation.md 已知边界 #2）：
  激活集是「会话装备」语义，prompt 注入读取侧已按 effectiveScopes 过滤，
  激活集本身的 FIFO 记账跨屏共享暂不改（影响面小，改动牵动 SkillActivateTool 契约）。

## 6. 测试

- `SkillRegistryScopeTest`：分级独立启停 / 三 sidecar 持久化与旧格式兼容 /
  effectiveScopes 贯穿（digests/injections/tools）/ 配置值跨升级存活 /
  菜单门禁（未配置隐藏 → 配置后出现）/ 卸载清理；
- `SkillConfigRulesTest`：就绪规则全分支；
- `SlashMenuScopeTest`（app）：forScope 双重过滤四象限；
- 既有 BundledSkillsTest / SkillHotReloaderTest / PruneStaleBundledTest /
  SkillCategoryTest 语义不变（setEnabled 旧行为保留）。
