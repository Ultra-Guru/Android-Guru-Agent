# 模板行为运行时 — 十行为代码化 + 深化模块（PR 系列 1-20）

> 状态：已实施 v2（模板运行时系列 20 个 PR 收口）
> 前置：[prompt-template-library.md](prompt-template-library.md)（模板库与渲染流水线）、[prompt-variables.md](prompt-variables.md)（变量引擎）
> 范围：`core/agent-engine` 纯 JVM 包 `com.apex.agent.core.engine.templates`（无 Android 依赖，全部可离线单测）

## 一、动机：模板 ≠ 提示词

模板库（P92）解决了「模板怎么存、怎么渲染」；但用户切换一个模板时，如果
只是把一段提示词塞进对话，那么「模板」就只是一个更贵的提示词片段。本系列
把模板升级为**可执行的 Kotlin 行为**：切换模板 = 装配一条确定性管线 ——
先于大模型做结构化分析（带着证据提问），回包后再做契约校验、机械修复与
结构化渲染（带着判据收货）。

```
模板激活（activate）
    ↓ 绑定行为（内置 1:1 目录绑定 / 自定义回落兜底）
六阶段管线
    PREPARE   输入规范化 + 形态探测（行数/结构/diff/CJK 比例）
    ANALYZE   确定性分析（规则引擎/度量/推导 → findings + hints）
    COMPOSE   变量替换 + 「确定性分析附录」注入（LLM 拿到带证据的任务书）
    ──────── 引擎发送 / 大模型回包 ────────
    VALIDATE  输出契约校验（章节/列表区间/纯载荷/行为专属门禁）
    REPAIR    坏格式确定性修复（剥寒暄/补章节壳/归一标记/领域专项修复）
    RENDER    结构化渲染（章节排序 + 机器可读摘要 + 质量分）
    ↓
TemplateRenderedReport（finalText + sections + summary 机器摘要）
```

## 二、二十个 PR 的分层落点

| 层 | PR | 内容 |
|----|----|------|
| 契约与骨架 | 1 | 六阶段契约（`AgentTemplateBehavior`）/ 上下文 / 分析模型 / 轮次模型 |
| 不可更改 | 2 | 内置模板 save 拒绝 / delete 拒绝 / 落盘篡改自愈 / 导入隔离 |
| 共享工具箱 | 3 | 文本度量 / 语言探测 / Markdown 扫描 / 章节解析 |
| 编排器 | 4 | `TemplateRuntime`（激活/轮次/回包闭环）+ 基座行为 + 兜底行为 |
| 十行为 | 5-14 | 每个内置模板一个行为 + 领域分析器（见 §四） |
| 行为目录 | 15 | 十行为 1:1 装订 + 完整性锁死 + 确定性冒烟 |
| app 接线 | 16 | 运行时 DI / 工坊「使用模板」流程 / 内置锁定 UI |
| 深化 I（语言与写作域）| 17 | 翻译质检 / 总结结构 / 提交规范 |
| 深化 II（工程流程域）| 18 | 重构风险 / BUG 分诊 / API 约定 |
| 深化 III（质量域）| 19 | 测试矩阵 / 文档风格 / 正则工具 / 审查启发 |
| 收口 | 20 | 端到端集成锁（版本 ≥2 + 附录信号 + 不可更改）+ 本文档 |

## 三、内置不可更改（单一事实源）

出厂定义只存在于 `BuiltinPromptTemplates.ALL`（代码里的出厂表）：

1. `PromptTemplateRegistry.save` 对已知内置 id 一律拒绝
   （`IllegalArgumentException`，与非法 id/空白正文同属 fail-fast 族）；
2. `delete` 对内置条目返回 false；
3. **落盘自愈**：磁盘上被外部篡改的内置条目（手改 JSON / 老版本遗留）
   在加载时强制回归出厂定义，仅保留 `createdAt/updatedAt/usageCount`
   三项运行元数据 —— 篡改没有存活通道；
4. 导入（MERGE/REPLACE 两种模式）永远不触碰内置 id；
5. 运行时激活内置模板时，定义**强制取出厂表**（注册表磁盘副本只贡献
   运行元数据）——「内置不可更改」在运行时侧的第二个落点；
6. 工坊 UI 对内置模板隐藏编辑/删除入口，定制走「另存为副本」。

## 四、十行为 × 深化模块全景

每个模板 = 行为类（六阶段接线）+ 第一层分析器 + 第二层深化模块：

| 模板 | 行为 | 第一层 | 第二层（深化） |
|------|------|--------|----------------|
| 代码审查 | CodeReviewTemplateBehavior | 13 语言规则表 + 结构度量 | **CodeReviewHeuristics**：命名/注释/跨函数/层次四域启发 |
| 提交信息 | CommitMessageTemplateBehavior | diff 状态机 + 类型打分 | **CommitMessageConventions**：四风格规范 + 十二类型库 + 互转 |
| 专业翻译 | TranslateTemplateBehavior | 领域术语表 + 保真比对 | **TranslateQualityLinter**：七族质检 + 百分制 + 标点修复 |
| 内容总结 | SummarizeTemplateBehavior | 预算解析 + 事实抽取 | **SummarizeStructureBuilder**：切句/显著度/聚类/大纲 |
| 重构方案 | RefactorPlanTemplateBehavior | 诊断 + 步骤骨架 | **RefactorRiskModel**：安全序 DAG + 四维风险 + 违序门禁 |
| BUG 报告 | BugReportTemplateBehavior | 严重度 + 复现质量 | **BugReportTriageEngine**：P0-P4 分诊 + 指纹 + 排查树 |
| API 设计 | ApiDesignTemplateBehavior | 资源/操作/字段推断 | **ApiDesignConventionLibrary**：命名 lint + 状态码税则 + OpenAPI 骨架 |
| 测试用例 | TestCasesTemplateBehavior | 等价类 + 边界推导 | **TestCaseFrameworkKit**：八框架规范 + TC 骨架 + 反模式门禁 |
| 技术文档 | TechDocTemplateBehavior | 受众归一 + 五段大纲 | **TechDocStyleGuide**：四型模板 + 示例三查 + 可读性评分 |
| 正则解释 | RegexExplainTemplateBehavior | 递归下降解析 + 风险 | **RegexToolkit**：方言探测 + 优化重写 + 三语言测试片段 |

### 深化模块的关键设计

- **回包门禁（VALIDATE 深化）**：不只是查格式 —— 重构方案查步骤顺序是否
  违反安全序、测试用例查反模式（无断言/魔法 sleep）、API 设计查端点命名、
  技术文档查句长与术语先用后定义。违规全部带「为什么」与确定性修复建议。
- **确定性产物直出（RENDER 深化）**：API 设计渲染区直接给出 OpenAPI 3.1
  骨架、提交信息给出规范化提交块、BUG 报告给出分诊徽标 —— LLM 补内容，
  结构由代码定。
- **启发器不产 BLOCKER**：第三层启发（CodeReviewHeuristics）只发
  MINOR/IMPORTANT，阻断级留给规则表的安全/正确性命中 —— 辅助信号
  不淹没主信号。

## 五、并发与防御纪律

- 编排器无共享可变状态（激活对象是不可变快照），行为实现无状态可并发；
- **任何阶段的异常都被折叠**为该阶段 ok=false + 轨迹留痕，绝不上抛 ——
  聊天主流程永不被模板功能打挂；
- 全部分析器是纯函数：空输入/截断 diff/无法解析的正则/方言不明的环境串
  全部有退化路径（空分析或单条降级注记）；
- 测试口径：`:core:agent-engine:test` 全离线（无网络/无设备），十行为
  各有专属套件 + 目录冒烟 + 端到端收官（`TemplateRuntimeEndToEndTest`）。

## 六、与工坊 UI 的接线（app 层）

- `TemplateModule` 提供 `TemplateRuntime` 单例（绑定十行为目录）；
- 工坊「使用模板」对话框：变量表单 → 激活 + 六阶段组装 → 生成结果
  （提示词 + 附录注入说明）→ 一键复制到聊天；
- 内置模板卡片收起编辑/删除入口（与注册表不可更改语义对齐）；
- 回包侧（聊天引擎消费 `processResponse` 产物）走 `TemplateRenderedReport`：
  `summary` 机器摘要（质检得分/分诊优先级/大纲覆盖率…）可直接驱动 UI 徽标。

## 七、验收矩阵

| 验收项 | 锚点测试 |
|--------|----------|
| 目录 1:1 完整性 | `BuiltinBehaviorCatalogTest`（键集 == 内置 id 集） |
| 十行为全链路确定性 | 同上（同输入两次组装一致） |
| 深化模块接线 | `TemplateRuntimeEndToEndTest`（behaviorVersion ≥ 2 + 附录专属信号） |
| 内置不可更改 | 注册表套件 + E2E（save/delete 拒绝 + 落盘自愈） |
| 各深化模块 | `TranslateQualityLinterTest` 等 10 个专属套件 |
