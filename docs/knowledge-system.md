# Agent 角色知识库体系（v2 · 多卷制 · 19 角色全覆盖）

> 版本：v2（2026-10-25 · v1 基础上升级多卷制）· 覆盖 19 个内置角色模板、20 卷，共 48,958 行进阶知识
> 关联模块：`app/src/main/kotlin/com/apex/agent/knowledge/`（解析器/服务）+ `app/src/main/kotlin/com/apex/agent/tools/KnowledgeBaseTool.kt`（检索工具）+ `app/src/main/kotlin/com/apex/agent/di/KnowledgeModule.kt`（DI 接线）
> 资产根：`app/src/main/assets/knowledge/`（每卷一个 `.md`）

---

## 一、设计动机与内容哲学

### 1.1 为什么需要「专属知识库」

Agent 角色模板（全能 / Coding 18 专家）的 `roleDefinition` 只有一句人设；
而模型权重里「已经掌握的」是教科书层知识。**真正拉开差距的是：
生产级最佳实践、内部机制、版本最新变化（2024-2026）、踩过才知道的陷阱**——
这些内容散落在演进极快的生态里，模型的训练快照天然滞后。

知识库体系就是把这部分「模型不知道 / 不确定 / 会过时」的专家经验
固化为可检索的 Markdown 资产，随 App 分发、随版本更新。

### 1.2 内容红线（写库纪律 · 用户规格）

| 红线 | 说明 |
| :--- | :--- |
| **禁止教科书层** | 基础语法表、「什么是 X」入门解释、常用 API 罗列、新手教程——这些是模型已掌握的，写进来只是噪音，还稀释检索精度 |
| **必须进阶层** | 生产最佳实践、顶尖惯用法、性能成本模型、并发陷阱与纪律、内部机制（编译器/运行时/GC/内存模型）、版本特性与迁移坑（2024-2026）、反模式与事故复盘 |
| **写作基线** | 每一节都要让资深工程师说出「这是踩过才知道的」；解释「为什么」和「什么时候会咬你」，而不是「怎么用」 |
| **时效纪律** | 版本敏感内容（新特性/弃用/行为变化）必须标注具体版本号；通过 web 检索校准到写作时的最新版本 |

### 1.3 结构契约（每套 ≥2000 行）

```
<!-- kb: id=<库id> | role=<角色模板id> | title=XX 专家知识库 | version=N -->
# XX 专家知识库

## 必看内容          ← 10-12 条专家级纪律（开工前必读的浓缩心智模型）
## 快速索引          ← 「想找什么 → 去哪一节」映射表（可分多个 · 续 块）
## N. 章节标题       ← 编号连续的正文节（15-22 行/节：代码 + 洞察 + 陷阱）
```

- 解析器（`KnowledgeBaseParser`）按此契约解析：kb 头声明 id/角色绑定/标题/版本；
  `必看内容`/`快速索引` 是保留节；`快速索引 · 续（X-Y 节）` 块自动合并为单索引。
- 每节编号用于 `read N` 直达；索引条目与节标题**精确一致**（校验 0 缺失）。
- 单文件上限 20,000 行（解析器硬限），实际每套 2000-3100 行。

## 二、检索面：Agent 怎么用

角色绑定知识库后，引擎提示词的 Agent Role 段自动注入检索指引
（`KnowledgeBaseService.roleDefinitionHint`）：**先 must_read、拿不准先 index/search、
锁定后 read 精读，禁止凭空猜测**。Agent 通过 `KnowledgeBase` 工具执行五个动作：

| 动作 | 说明 |
| :--- | :--- |
| `list_all` | 列出全部知识库摘要（id/角色/章节数/索引数/字数） |
| `must_read` | 读必看内容原文（角色开工仪式） |
| `index` | 取快速索引（「一键找到想看的内容」） |
| `search` | 关键词检索：索引主题命中 ×30 直达章节；标题 ×3 / 必看 ×2 / 正文 ×1 |
| `read` | 精读一节（ref 支持「N. 标题」/ 纯编号 / 标题前缀） |

服务层纪律：懒加载 + Mutex 并发安全；坏文件跳过留日志（一套损坏绝不拖垮体系）；
读侧纯内存快照。IO 边界钉在 DI 层（assets 两个纯函数注入），服务本体纯 JVM 可测。

## 三、20 卷知识库总表（19 角色）

| 知识库 | 角色模板 | 行数 | 章节 | 索引条目 | 主题主线 |
| :--- | :--- | ---: | ---: | ---: | :--- |
| all_rounder | builtin_all_rounder | 2,190 | 176 | 160 | 本 App 工具面决策树 + 任务流模式 + 上下文预算 + 排查总树（环境特有知识，模型不知道的部分） |
| all_rounder_tasking | builtin_all_rounder | 2,096 | 40 | 40 | 卷二：任务工程与安全治理 —— 需求工程 / 执行治理 / 验证工程 / 上下文与记忆工程 / 安全治理 / 协作交付（v2 多卷制首个扩充卷） |
| full_stack | builtin_coding_full_stack | 2,182 | 200 | 194 | 竖切交付 / API 与错误体系 / 幂等与认证深水 / 缓存与迁移 / SLO 与发布纪律 |
| git | builtin_coding_git | 2,064 | 138 | 134 | fetch 深水（blobless/treeless） / 历史取证（blame -C/log -L） / rerere/bisect / 撤销深水 / 维护加速内幕 |
| frontend | builtin_coding_frontend | 2,273 | 174 | 158 | 解析与失效内幕 / :has() 性能画像 / 选择器引擎右到左 / 表单深水（ElementInternals） / INP/LoAF 纪律 |
| android | builtin_coding_android | 2,357 | 109 | 108 | Compose 心智与性能 / 协程结构化并发 / 后台限制与真机纪律 / API 级别差异 / 崩溃对照 |
| kotlin | builtin_coding_kotlin | 2,253 | 131 | 135 | K2 推断收紧 / 协程纪律与热流口径 / 集合性能成本 / DSL 三件套 / value class 单位安全 |
| java | builtin_coding_java | 2,144 | 114 | 114 | Java 21-25 新特性 / 虚拟线程生产陷阱 / j.u.c 深水 / JIT/GC 成本模型 / FFM 与诊断 |
| python | builtin_coding_python | 2,242 | 115 | 115 | free-threading 3.13→3.14 决策树 / asyncio 深水（TaskGroup/取消） / CPython 对象内幕 / uv 工程化 |
| javascript | builtin_coding_javascript | 2,689 | 112 | 112 | 事件循环微宏任务序 / V8 隐藏类与 deopt / AbortSignal 取消传播 / ESM/CJS 互操作 / 泄漏模式 |
| typescript | builtin_coding_typescript | 2,736 | 115 | 115 | 类型级编程深水 / satisfies 与收窄控制流 / tsconfig 严格阶梯 / 5.x 迁移 / isolatedDeclarations |
| go | builtin_coding_go | 2,755 | 112 | 112 | 调度器内幕（GMP/异步抢占） / GC pacer 与 GOMEMLIMIT / Swiss Tables / synctest / b.Loop |
| rust | builtin_coding_rust | 3,016 | 112 | 112 | Pin/取消安全 / trait 设计（GATs/dyn） / unsafe 与 Miri / loom/proptest / Edition 2024 迁移 |
| cpp | builtin_coding_cpp | 2,316 | 112 | 112 | 协程 HALO / move 语义深水（URVO） / sanitizers 生产矩阵 / ABI 断裂清单 / 微基准失真源 |
| csharp | builtin_coding_csharp | 2,252 | 112 | 112 | async 状态机内幕 / Span/ref struct 纪律 / Tiered PGO / DATAS / AOT 修剪陷阱 |
| swift | builtin_coding_swift | 3,060 | 112 | 112 | 严格并发（actor 重入/Sendable 角落） / @Observable 内幕 / 特化丢失 / SwiftData 生产七错误 |
| php | builtin_coding_php | 2,258 | 112 | 112 | OPcache/JIT 调参 / 属性钩子与 lazy objects / Fibers 生态取舍 / 安全加固矩阵 / FPM 与发布 |
| ruby | builtin_coding_ruby | 2,311 | 112 | 112 | YJIT 调优 / Fiber 调度协议 / Rails 8 Solid 三件套 / Zeitwerk / 内存膨胀诊断手册 |
| sql | builtin_coding_sql | 2,876 | 112 | 112 | 计划读取解剖 / B-tree 与 GIN/BRIN 内幕 / MVCC 与锁矩阵 / 连接池模式坑 / keyset 分页 |
| shell | builtin_coding_shell | 2,888 | 112 | 112 | set -e 语义陷阱 / 信号与 trap 生命周期 / xargs -P 与 flock / awk 单遍 vs 管道 / cron/CI 环境 |

合计 **48,958 行 / 20 卷 / 19 角色**，覆盖全部内置角色模板（全能 2 卷 +
Coding 专家 18 卷）。每套均通过结构校验：行数 ≥2000、章节编号连续无缺、
索引目标 0 缺失、kb 头角色绑定与 `AgentRole` 内置 id 一一对应。

### 3.1 v2 多卷制（同角色可无限扩卷）

v2 起 `KnowledgeBaseService` 的角色绑定从单值 first-wins 升级为**多卷清单**：

- `volumesForRole(roleId)` 返回该角色全部卷（资产顺序）；`forRole`
  保留为主卷兼容口径（首卷）；同 id 重复仍首个生效（防检索歧义）；
- **加卷 = 在 assets/knowledge/ 加一个 .md**（kb 头 `role=` 同角色、
  `id=` 每卷独立），零代码改动 —— 角色知识面不再被单文件锁死；
- `KnowledgeBaseTool` 的 `list` 动作按角色分组展示多卷；检索动作
  （must_read / index / search / read）全部按卷 id 定位 —— 每卷自带
  「快速索引」目录表，「一键找到想看的内容」的口径不变；
- 首个扩充卷：`all_rounder_tasking`（全能 Agent 卷二，2,096 行 40 节）。

多卷动机与双工位分轨同 PR，详见 `docs/workstation-split-kb-volumes.md`。

## 四、角色绑定与注入链路

```
app/src/main/assets/knowledge/<id>.md          资产（id/role/title/version 头）
  └─ KnowledgeModule（DI）                       assets → 两个纯函数（列名/读文）
       └─ KnowledgeBaseService                   懒加载 + 角色绑定 + 五动作检索面
            ├─ KnowledgeBaseTool                 Agent 工具（SafeAgentTool 包装注册）
            └─ roleDefinitionHintFor()           角色提示词注入（v2 多卷口径）：
                 ├─ CodeRoleController           Coding 工位：roleDefinition + 全卷指引
                 └─ AgentChatViewModel           Agent 工位：同通道
```

- 角色绑定以 kb 头 `role=` 为准；v2 多卷制：同角色多卷按资产顺序全量
  保留（`forRole` 取主卷，`volumesForRole` 取全量）。
- 自定义角色无内置绑定 → 不注入指引（知识库工具仍可按 id 检索任意卷）。

## 五、维护纪律

1. **改内容不改结构**：编辑某节正文不需要动索引；改节标题必须同步索引条目
   （跑仓库校验脚本：索引目标 0 缺失）。
2. **加节**：编号追加在文件尾部（或主题对应部分），索引表补一行；
   `version` 头 +1。
3. **新知识库**：复制结构契约，头注释 `role=` 绑定到对应角色 id，
   落盘 assets 后服务自动发现（无需注册代码）。
4. **校验口径**（与仓库测试一致）：`sections ≥100`、`索引目标 0 缺失`、
   `必看内容存在`、`单文件 ≤20000 行`；进阶层内容红线见 §1.2。
