package com.apex.agent.core.tools.fusion

/**
 * # FusionBundle 内置预置 —— 「SuperClaude · 结构化开发平台」
 *
 * 上游（SuperClaude-Org/SuperClaude_Framework v4.3.0，MIT）是把编码
 * agent 改造成「结构化开发平台」的元编程配置框架：30 个 /sc:* 命令 +
 * 20 个专业代理 + 7 个行为模式 + 8 台增强 MCP。
 *
 * 经 FusionBundle 装载进 AGA（能力面不弱于上游核心）：
 * - 5 个行为模式技能（apex-skill-v1 内联）：Brainstorming / Deep
 *   Research / Token-Efficiency / Orchestration / Business Panel ——
 *   上游 7 模式中与设备端相关的主干（另两模式为 harness 适配）；
 * - agent 人设：结构化元编程风格（命令域 /sc:* 的路由浓缩为提示路由）；
 * - MCP 聚合（上游 8 台增强 MCP 的设备端等价映射，全部 AGA 内置
 *   进程内服务器，零子进程零网络握手）：Tavily→search（WebSearch/
 *   WebFetch）、Sequential-Thinking→thinking、Serena→memory（知识图谱
 *   会话持久化）、代码智能→github；Playwright/Chrome DevTools→AGA
 *   一等 browser_* 工具族（无需 MCP）；Context7→WebFetch 文档抓取。
 */
object FusionSuperClaudePreset {

    /** SuperClaude 包 manifest（fusion-bundle-v1）。 */
    val SUPERCLAUDE_BUNDLE_JSON = """
{
  "schema": "fusion-bundle-v1",
  "id": "superclaude",
  "name": "SuperClaude · 结构化开发平台",
  "version": "4.3.0",
  "description": "元编程配置框架：30 命令 + 20 代理 + 7 行为模式的浓缩装载。五大行为模式技能 + 四台内置 MCP 聚合（搜索/顺序思考/记忆图谱/代码智能）+ 结构化人设。对齐上游 SuperClaude_Framework v4.3.0 核心面，MCP 增强层全部映射为设备端进程内等价物。",
  "author": "Ultra-Guru (FusionBundle port) / upstream: SuperClaude-Org (MIT)",
  "license": "MIT",
  "category": "PLATFORM",
  "tags": ["framework", "modes", "mcp", "research", "token-efficiency"],
  "homepage": "https://superclaude.netlify.app",
  "repository": "https://github.com/SuperClaude-Org/SuperClaude_Framework",
  "trustLevel": "verified",
  "bundled": true,
  "scope": "all",
  "agent": {
    "persona": "SuperClaude 结构化开发平台（模式自适应 + MCP 增强）",
    "activationPrompt": "你当前以 SuperClaude 结构化平台运行：按任务形态自动切换行为模式 —— 新想法/新功能 → Brainstorming 模式（问对的问题，多方案 + 评估矩阵）；需要外部事实 → Deep Research 模式（自主网络研究：搜索 → 跨源交叉验证 → 带引用综合）；上下文吃紧 → Token-Efficiency 模式（批量工具调用、渐进披露、禁冗余复述）；多工具协作 → Orchestration 模式（最小工具集、组合调用、子代理并行）；战略决策 → Business Panel 模式（多专家圆桌）。结构化纪律：回答先结论后过程；复杂任务先出方案再动手；重要产出带置信度与依据；主动声明能力边界。MCP 增强已聚合就绪：WebSearch/WebFetch（研究）、顺序思考（多步推理）、记忆图谱（跨会话实体）、GitHub（代码智能）。",
    "routingHints": "开放性新需求 → SuperClaudeBrainstormMode；要查资料/做调研 → SuperClaudeDeepResearch（McpSearchWebSearch + McpSearchWebFetch）；长链推理/复杂决策 → 顺序思考（McpThinkingSequentialthinking）；上下文将满 → SuperClaudeTokenEfficiency；并行多任务 → SuperClaudeOrchestration（Task 子代理）；商业/产品取舍 → SuperClaudeBusinessPanelMode；代码理解/仓库事实 → McpGithub* 工具族 + browser_* 工具族（页面级操作）"
  },
  "skills": [
    {
      "skillId": "SuperClaudeBrainstormMode",
      "role": "发散探索与方案评估",
      "manifest": {
        "schema": "apex-skill-v1",
        "id": "SuperClaudeBrainstormMode",
        "name": "SuperClaude 头脑风暴模式",
        "version": "4.3.0",
        "description": "问对的问题而不是急着给答案：多方案探索 + 评估矩阵 + 假设显式化。",
        "author": "Ultra-Guru (port) / upstream: SuperClaude-Org (MIT)",
        "license": "MIT",
        "category": "PLATFORM",
        "tags": ["mode", "brainstorming", "evaluation"],
        "trustLevel": "verified",
        "bundled": true,
        "scope": "all",
        "promptInjection": "头脑风暴模式（SuperClaude）：1) 先问 2-3 个澄清问题再给方案（目标用户、成功标准、硬约束）—— 但一次只问一组，别审讯。2) 至少给出 2-3 个实质不同的方案（不是同一方案的三种措辞）。3) 每方案带评估矩阵：复杂度/风险/可逆性/依赖面。4) 显式列出你的假设 —— 假设被推翻时方案怎么变，一并说明。5) 有倾向就明说并给理由，但决策权留给对方。6) 对话轮次预算 3-5 轮：对方开始重复观点即收敛出结论清单。"
      }
    },
    {
      "skillId": "SuperClaudeDeepResearch",
      "role": "自主网络研究闭环",
      "manifest": {
        "schema": "apex-skill-v1",
        "id": "SuperClaudeDeepResearch",
        "name": "SuperClaude 深度研究模式",
        "version": "4.3.0",
        "description": "自主研究循环：拆问题 → 多轮搜索 → 跨源交叉验证 → 带引用综合成报告。",
        "author": "Ultra-Guru (port) / upstream: SuperClaude-Org (MIT)",
        "license": "MIT",
        "category": "PLATFORM",
        "tags": ["mode", "research", "citations"],
        "trustLevel": "verified",
        "bundled": true,
        "scope": "all",
        "promptInjection": "深度研究模式（SuperClaude）：1) 拆解研究问题为 3-7 个可独立检索的子问题。2) 逐子问题用 WebSearch 检索（每轮按结果修正关键词 —— 零结果换同义词，结果太泛加限定词）；关键页面用 WebFetch 取全文。3) 交叉验证：同一事实至少两个独立来源才算确认；来源冲突如实呈现分歧而非擅自裁决。4) 每条结论带引用（来源 + 检索时间）；无法验证的标注『未证实』。5) 研究预算：默认 5-8 轮检索；信息增量明显衰减（连续两轮无新事实）即停。6) 产出结构：结论先行 → 分主题综合 → 分歧与局限 → 来源清单。7) 区分事实/推断/观点三层，不混排。"
      }
    },
    {
      "skillId": "SuperClaudeTokenEfficiency",
      "role": "上下文经济纪律",
      "manifest": {
        "schema": "apex-skill-v1",
        "id": "SuperClaudeTokenEfficiency",
        "name": "SuperClaude 令牌效率模式",
        "version": "4.3.0",
        "description": "30-50% 上下文节省纪律：批量工具调用、渐进披露、输出按需分层。",
        "author": "Ultra-Guru (port) / upstream: SuperClaude-Org (MIT)",
        "license": "MIT",
        "category": "PLATFORM",
        "tags": ["mode", "efficiency", "context"],
        "trustLevel": "verified",
        "bundled": true,
        "scope": "all",
        "promptInjection": "令牌效率模式（SuperClaude）：1) 工具调用能合就合 —— 一次消息发多个独立调用（并行批处理），不来回打乒乓。2) 渐进披露：先 ToolSearch/摘要级信息，确有需要再取全文；大文件先看结构再取片段。3) 输出分层：默认给结论 + 关键证据；完整数据/长表格折叠为『需要可展开』。4) 禁冗余：不复述任务原文、不总结对方刚说的话、不输出仪式性开场白。5) 上下文回收：阶段性归档已完成分支的细节，只保留结论与未决项。6) 长任务每完成一阶段输出一次紧凑快照（完成了什么/剩什么/下一步），代替流水账。"
      }
    },
    {
      "skillId": "SuperClaudeOrchestration",
      "role": "工具与子代理编排",
      "manifest": {
        "schema": "apex-skill-v1",
        "id": "SuperClaudeOrchestration",
        "name": "SuperClaude 编排模式",
        "version": "4.3.0",
        "description": "高效工具编排：最小工具集、组合调用、子代理并行、失败降级路径。",
        "author": "Ultra-Guru (port) / upstream: SuperClaude-Org (MIT)",
        "license": "MIT",
        "category": "PLATFORM",
        "tags": ["mode", "orchestration", "subagents"],
        "trustLevel": "verified",
        "bundled": true,
        "scope": "all",
        "promptInjection": "编排模式（SuperClaude）：1) 开工前先画执行计划：哪些步骤、每步用哪个工具、哪些可并行 —— 工具选择讲最小集（够用就好，不为炫技多调）。2) 独立无依赖的步骤批量并行发；有依赖的严格排序。3) 可隔离的子任务派 Task 子代理（每子代理单一目标 + 干净上下文 + 明确产出物）；主上下文只做调度与整合，不做搬运。4) 每步带超时与失败预案：工具连续失败两次换路径（如 WebFetch 失败换 WebSearch 换关键词），不无限重试。5) 中间产物落盘（文件/会话证据），跨步骤传递用引用不用复述。6) 全程可追溯：每个决定能回答『为什么这步用这个工具』。"
      }
    },
    {
      "skillId": "SuperClaudeBusinessPanel",
      "role": "多专家战略圆桌",
      "manifest": {
        "schema": "apex-skill-v1",
        "id": "SuperClaudeBusinessPanel",
        "name": "SuperClaude 商业专家团模式",
        "version": "4.3.0",
        "description": "产品/技术/市场/风险多视角圆桌：每个视角独立论证，主席综合裁决。",
        "author": "Ultra-Guru (port) / upstream: SuperClaude-Org (MIT)",
        "license": "MIT",
        "category": "PLATFORM",
        "tags": ["mode", "strategy", "multi-perspective"],
        "trustLevel": "verified",
        "bundled": true,
        "scope": "all",
        "promptInjection": "商业专家团模式（SuperClaude）：1) 组团：按议题选 3-5 个专家席位（典型：产品负责人、技术负责人、市场/增长、风险/合规、用户代表 —— 按需增删，不硬凑）。2) 每席位独立论证一轮：只站在本席位立场说话，给论据给取舍，不客套不附和。3) 主席（你）组织交锋：点出席位间的真分歧，逐条对撞（每条分歧双方各一段，不合并观点）。4) 综合裁决：按『对目标的影响 × 置信度』排优先级，输出建议 + 各席位保留意见。5) 立场标记：每段发言前注明席位名；主席结论与前席位观点明确区隔。6) 需要事实支撑的点（市场规模/竞品/技术现状）先用研究工具取证再入席，不空对空。"
      }
    }
  ],
  "mcpServers": [
    {
      "serverId": "search",
      "transport": "BUILTIN",
      "autoConnect": true,
      "scope": "all",
      "role": "研究增强（上游 Tavily 等价物）：WebSearch 多源检索 + WebFetch 全文抓取 —— Deep Research 模式的主力"
    },
    {
      "serverId": "thinking",
      "transport": "BUILTIN",
      "autoConnect": true,
      "scope": "all",
      "role": "顺序思考（上游 Sequential-Thinking 等价物）：多步推理的思维链外化 —— 复杂决策与长链规划"
    },
    {
      "serverId": "memory",
      "transport": "BUILTIN",
      "autoConnect": true,
      "scope": "all",
      "role": "会话持久化（上游 Serena 等价物）：知识图谱实体/关系/观察 —— 跨会话记忆"
    },
    {
      "serverId": "github",
      "transport": "BUILTIN",
      "autoConnect": true,
      "scope": "all",
      "role": "代码智能（上游 Context7/仓库上下文等价物）：仓库/议题/PR 事实查询"
    }
  ],
  "toolActivationHints": [
    "McpSearchWebSearch",
    "McpSearchWebFetch",
    "McpThinkingSequentialthinking",
    "Task",
    "BrowserNavigate",
    "BrowserExtractContent",
    "ToolSearch"
  ],
  "runtime": {
    "engines": [],
    "notes": "零外部引擎：四台增强 MCP 全部映射为 AGA 进程内内置服务器（零子进程、零网络握手、与宿主同生命周期）；上游 Playwright/Chrome DevTools 的页面操作由一等 browser_* 工具族覆盖。"
  },
  "changelog": [
    {"version": "4.3.0", "notes": "FusionBundle 装载：上游 7 行为模式主干五模式全移植；8 台增强 MCP 的设备端等价映射（4 台内置 MCP 聚合 + browser_* 工具族）；/sc:* 命令域浓缩为提示路由。"}
  ]
}
""".trim()

    /** 解析后的 manifest（编码期自检 —— 非法即 error）。 */
    fun manifest(): FusionBundleManifest =
        FusionBundleManifest.parse(SUPERCLAUDE_BUNDLE_JSON)
            ?: error("内置 SuperClaude FusionBundle manifest 非法（编码期错误，需修正）")
}
