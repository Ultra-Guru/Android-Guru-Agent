# 仓库依赖地图（CodeRepoMap / Aider repo-map 的 Kotlin 移植）

> Coding 模式第 229 个内置工具：用「个性化 PageRank + token 预算二分」把
> 整个工作区浓缩成一张约 1k token 的骨架地图 —— 哪个文件定义了哪些符号、
> 谁引用谁、当前任务相关的文件最相关。模型花极小代价获得仓库全局视野，
> 再决定深读哪些文件。
>
> 源头：[Aider](https://github.com/Aider-AI/aider) 的 `repomap.py`（约
> 50k star 的 AI 结对编程工具的核心组件），本仓库以纯 JVM Kotlin 重写，
> 无 tree-sitter / networkx / tiktoken 依赖。

## 一、解决什么问题

工具箱里已有的导航手段都是「点状」的：

| 工具 | 回答的问题 | 盲区 |
|---|---|---|
| CodeGlob | 文件名匹配什么 | 不知道文件里有什么 |
| CodeGrep | 哪里出现了某字符串 | 不知道该看哪条命中 |
| CodeRead | 这个文件长什么样 | 不知道还有哪些文件该读 |
| **CodeRepoMap** | **仓库长什么样、入口在哪、谁依赖谁** | —— |

对陌生仓库的第一跳，模型原本只能 Glob + Grep 盲人摸象（多轮、上下文
膨胀）；repo-map 一次调用给出全仓库的「目录 + 定义签名」骨架，跨文件
编辑的一次成功率直接受益 —— 手机场景上下文更贵，收益放大。

## 二、算法流水线（与 Aider 逐步对齐）

```
工作区文件 ──► TagExtractor ──► RepoMapBuilder ──► SkeletonRenderer
（噪音目录剔除）  def/ref 标签      边权 × PageRank      骨架 + 预算二分
```

### 1. 标签提取（TagExtractor）

Aider 用 tree-sitter `.scm` 查询；Android JVM 无可用原生绑定，改为
「语言轮廓 = 定义正则 + 关键字表」（Kotlin / Java / Python / JS-TS /
Go / Rust / C 族）：

- **def**：定义形态正则逐行捕获（`fun` / `class` / `def` / `func` /
  `fn`…），带 1 基行号 —— 骨架渲染的「关注行」；
- **ref**：全文件标识符 token 计数（剔除关键字、行注释、块注释续行；
  字符串字面量内的 `//` / `#` 防误伤，行首 `*` 不当注释）。
  与现代 tree-sitter 查询「def 名同时计一次 ref」的行为一致。

### 2. 图构建与权重乘数（RepoMapBuilder，常量取自 Aider 源码）

边 `引用文件 → 定义文件`，权重 = `mul × √引用次数`，其中 mul：

| 条件 | 乘数 |
|---|---|
| 标识符被会话提及 | × 10 |
| 蛇形/串形/驼峰且长度 ≥ 8 | × 10 |
| 下划线私有前缀 | × 0.1 |
| 定义文件 > 5 个（过度通用的名字） | × 0.1 |
| 引用文件已在聊天中 | × 50 |
| 引用次数 | 开平方（高频引用不吞掉排名） |

有定义无引用的标识符加 self-edge（weight 0.1）；全仓库无引用时引用
回退为定义集（Aider 对 C 族语言的兜底）。

### 3. 个性化 PageRank（PersonalizedPageRank）

networkx `pagerank(G, weight, personalization, dangling=personalization)`
的幂迭代移植（α=0.85，L1 容差 N×1e-6，≤100 轮）。个性化向量：base =
100/N，聊天/focus 文件 +base、路径成分命中提及标识符 +base。悬挂节点
的质量按个性化向量再分配。稀疏覆盖宽容处理（networkx 会抛 ValueError，
这里按防御式 IO 原则折叠）。

### 4. rank 分摊与排序

每条边分得 `srcRank × w / W_out(src)`，累进 `(目标文件, 标识符)` ——
「文件的重要性」被摊给它定义的具体符号。输出顺序：定义条目 rank 降序
（聊天文件跳过）→ 按 PageRank 补齐未覆盖文件 → 无标签文件裸名（字母序）。

### 5. 骨架渲染与预算二分（SkeletonRenderer）

- 关注行整行保留（超宽截断 120 字符），连续非关注行折叠为 `... (N lines)`；
- token 估算 = chars/4（与项目 TokenEstimator 英文口径一致；Aider 用
  tiktoken，JVM 侧不引依赖）；
- 二分搜索「取前 N 条条目」直到渲染结果落入预算 ±15%（初值
  `maxTokens/25`，Aider 同款经验值）。

### 6. 重要文件前置（Aider special.py 移植）

README / 构建脚本 / CI 配置等根级重要文件 + GitHub workflow 前置为
裸名条目 —— 地图的「门面」永远是仓库的入口文档。

## 三、工具契约

| 参数 | 类型 | 语义 |
|---|---|---|
| `max_tokens` | int 256..4096，默认 1024 | 预算（超界钳制） |
| `focus` | string | 逗号分隔的工作区相对路径：正在讨论的文件 —— 个性化加分 + 从地图中省略（用户已在视野内） |

输出 = 地图文本 + 统计尾注（文件数 / 定义数 / 估算 token / 预算）。
特性：只读、低风险；会话级 mtime 缓存（重复调用只重读变更文件）。

## 四、模块与接线

```
core/code-tools/src/main/kotlin/.../repomap/
  RepoTag.kt              标签模型（def 带行号 / ref 只计数）
  TagExtractor.kt         语言轮廓 + 正则提取（7 语言族）
  PersonalizedPageRank.kt networkx 幂迭代移植
  RepoMapBuilder.kt       图构建 + 乘数 + rank 分摊 + 排序
  SkeletonRenderer.kt     骨架渲染 + 预算二分 + token 估算
  CodeRepoMapTool.kt      工具壳（行走 / 缓存 / 重要文件前置）
```

- 工厂：`CodeTools.all()` 追加（app 层 ToolModule 随 SafeAgentTool 注册）；
- 别名：`code_repo_map` → `CodeRepoMap`（ToolIdAliases）；
- CORE 集：入 ToolTierPolicy.CORE_TOOL_IDS（schema 两个可选参数，
  常驻成本可忽略，与 CodeRead/CodeGrep 同层）；
- 子代理：EXPLORE（代码探索）与 REVIEWER（代码评审）白名单收录 ——
  探索员先拿地图再下钻，评审员先看全局再看变更。

## 五、测试

`core/code-tools/src/test/kotlin/.../repomap/` 四个契约测试文件
（纯 JVM，手算断言，无 mock 框架）：

- `TagExtractorTest` —— 三族语法定义抽取、引用计数、注释防误伤
  （乘法/字符串 URL 不当注释）；
- `PersonalizedPageRankTest` —— 手算图：双节点环 0.5/0.5、无边图
  均匀/个性化、悬挂再分配、平行边、稀疏覆盖；
- `RepoMapBuilderTest` —— 乘数常量、被引用定义排名、聊天文件排除、
  无引用回退、√ 抑制、裸名兜底；
- `CodeRepoMapToolTest` —— TemporaryFolder 端到端：预算内骨架、
  focus 语义、噪音目录、重要文件、空工作区引导、预算钳制、缓存复用。

## 六、与上游的差异（诚实清单）

| Aider | 本移植 | 原因 |
|---|---|---|
| tree-sitter 抽取（作用域感知） | 正则轮廓（token 级近似） | Android JVM 无原生绑定；接口已隔离可替换 |
| networkx PageRank | 自实现幂迭代 | 零依赖；语义对齐（含 dangling） |
| tiktoken 精确计数 | chars/4 启发 | 与项目 TokenEstimator 口径一致 |
| TreeContext（父作用域展开） | 行折叠骨架 | 简化；「最少 token 交代定义」目标一致 |
| SQLite 标签缓存 | 会话内 mtime 缓存 | 工具生命周期即会话；跨进程缓存收益低 |

后续演进位：TagExtractor 接口化后可替换 android-tree-sitter 绑定；
mentionedIdentifiers 已在 Input 预留（上游由聊天提及检测喂数）。
