package com.apex.agent.core.tools.fusion

/**
 * # FusionBundle 内置预置 —— 「Shrimp Task Manager · 智能任务规划」
 *
 * 上游（cjo4m06/mcp-shrimp-task-manager v1.0.21，MIT，2.1k star）是
 * MCP 生态最流行的任务管理服务器之一：为 AI 编程助手提供结构化任务
 * 管理 —— 智能任务规划（自然语言 → 依赖 DAG + 复杂度 + 实现指南）、
 * 自动分解、执行追踪、完整性验证、反思洞察与风格一致性（三词摘要
 * 标签），强调思维链与反思。
 *
 * 经 FusionBundle 装载进 AGA（能力面不弱于上游）：
 * - **16 工具全目录进程内实现**（上游 docs/tools.md 逐名对齐）：
 *   规划族（plan/analyze/split）+ 执行族（execute/update/complete/
 *   delete）+ 验证族（verify/reflect/query/list）+ 记忆族
 *   （get_task_detail/clear_all_tasks 自动备份）+ 研究族
 *   （research_mode/init_project_rules）+ 思维族（process_thought）；
 * - **持久化升级**（超出上游）：任务数据 JSON 原子写（tmp+rename）；
 *   上游 JSON 存储同语义，另加 clear 前自动备份链；
 * - **零依赖运行**（超出上游）：纯 Kotlin 进程内 transport —— 无需
 *   Node/npx/网络，与宿主 App 同生命周期（第七台内置 MCP 服务器）；
 * - 2 个技能：任务规划工作流（规划→执行→反思闭环）+ 研究与规则纪律。
 */
object FusionShrimpPreset {

    /** Shrimp 包 manifest（fusion-bundle-v1）。 */
    val SHRIMP_BUNDLE_JSON = """
{
  "schema": "fusion-bundle-v1",
  "id": "shrimp",
  "name": "Shrimp Task Manager · 智能任务规划",
  "version": "1.0.21",
  "description": "MCP 生态流行的结构化任务管理体系：16 工具全目录（规划/执行/验证/反思/研究/思维链）进程内实现 —— 任务依赖 DAG、复杂度分析、实现指南、验证证据、反思洞察、项目规则常驻、三词摘要风格一致性。任务数据 JSON 原子写持久 + 清空前自动备份。对齐上游 cjo4m06/mcp-shrimp-task-manager v1.0.21。",
  "author": "Ultra-Guru (FusionBundle port) / upstream: cjo4m06/mcp-shrimp-task-manager (MIT)",
  "license": "MIT",
  "category": "TASKS",
  "tags": ["task-management", "planning", "mcp", "reflection"],
  "homepage": "https://github.com/cjo4m06/mcp-shrimp-task-manager",
  "repository": "https://github.com/cjo4m06/mcp-shrimp-task-manager",
  "trustLevel": "verified",
  "bundled": true,
  "scope": "all",
  "agent": {
    "persona": "Shrimp 结构化任务规划智能体（思维链 + 反思 + 风格一致性）",
    "activationPrompt": "你当前以 Shrimp Task Manager 体系运行：任何多步骤工作先建任务账本再动手 —— 1) plan_task 记录目标（已完成任务自动归档）；2) 分解成原子任务（一个任务一个可验证交付物），推理用 process_thought 外化，定型后 update_task 整表落库（每个任务六件套：description/prerequisites/complexity/analysis/implementation_guide/verification_steps，summary 用三词标签）；3) 严格按依赖执行：execute_task 取任务（前置未全会被拒 —— 这是对的，别绕），按 implementation_guide 干活；4) 完成 = complete_task 带证据备注（命令 + 输出摘要），验证走 verify_task 对照 verification_steps 逐条核 —— 没验证就说没验证；5) 每个任务完成后 reflect_task 沉淀洞察（什么有效/什么没效/下次怎么改）—— execute_task 会带回最近洞察，保持全任务风格一致；6) 项目标准先 init_project_rules（execute 上下文常驻）；技术选型类问题先 research_mode 系统化取证再进计划。任务太复杂就 split_tasks 拆链（原任务归档、依赖自动转接）。全部任务完成 ≠ 结束 —— reflect 复盘 + 洞察已在账上才算闭环。",
    "routingHints": "新目标/新一天开工 → McpShrimpPlanTask（清已完成的场）；任务清单成型 → McpShrimpUpdateTask（整表六件套落库）；取活干 → McpShrimpExecuteTask（前置门禁 + 规则 + 洞察随身）；干完 → McpShrimpCompleteTask（证据备注）；要核验 → McpShrimpVerifyTask；要复盘 → McpShrimpReflectTask；看全局 → McpShrimpListTasks / McpShrimpQueryTask；单任务全貌 → McpShrimpGetTaskDetail；任务太大 → McpShrimpSplitTasks；重开项目 → McpShrimpInitProjectRules（标准常驻）；选型/调研 → McpShrimpResearchMode；推理中 → McpShrimpProcessThought；推倒重来 → McpShrimpClearAllTasks（自动备份）"
  },
  "skills": [
    {
      "skillId": "ShrimpWorkflow",
      "role": "任务规划→执行→反思闭环",
      "manifest": {
        "schema": "apex-skill-v1",
        "id": "ShrimpWorkflow",
        "name": "Shrimp 任务规划工作流",
        "version": "1.0.21",
        "description": "结构化任务管理的核心闭环：规划（依赖 DAG + 六件套）→ 按序执行（前置门禁）→ 完成验证（证据入账）→ 反思沉淀（洞察回灌）。风格一致性由三词摘要与洞察随行保证。",
        "author": "Ultra-Guru (port) / upstream: cjo4m06/mcp-shrimp-task-manager (MIT)",
        "license": "MIT",
        "category": "TASKS",
        "tags": ["task-management", "planning", "reflection"],
        "trustLevel": "verified",
        "bundled": true,
        "scope": "all",
        "promptInjection": "任务规划纪律（Shrimp 核心闭环）：1) 规划面 —— 原子性（一个任务一个可验证交付物，不是一层目录）；依赖显式（prerequisites 是 DAG，能并行就别串行）；六件套齐（description 说清做什么、complexity 三档、analysis 说清为什么、implementation_guide 是操作手册、verification_steps 是验收口径、summary 三词标签 —— 上游风格一致性的载体）。2) 执行面 —— 前置门禁神圣：execute_task 被拒（前置未完）就先去做前置，绝不绕开依赖顺序；干完 complete_task 必须带证据备注（命令 + 输出摘要），『我觉得好了』不算证据。3) 验证面 —— verify_task 对照 verification_steps 逐条核；没验的项如实标注；验证不过回 execute_task 修复，不许假验证。4) 反思面 —— 每任务 reflect_task 至少一条洞察；洞察原子化（一条一件事）；execute_task 随身带最近洞察 —— 同样的坑不踩第二遍就是体系的价值。5) 账本面 —— list_tasks 常看（进度 percent 与状态分布）；query_task 按状态/关键词定位；清档 clear_all_tasks 自动备份，恢复有路。"
      }
    },
    {
      "skillId": "ShrimpResearch",
      "role": "研究模式与项目规则纪律",
      "manifest": {
        "schema": "apex-skill-v1",
        "id": "ShrimpResearch",
        "name": "Shrimp 研究与规则纪律",
        "version": "1.0.21",
        "description": "技术决策的系统化研究（界定→取证→对比→结论→回填任务）与项目标准常驻（init_project_rules —— 所有执行同风格）。",
        "author": "Ultra-Guru (port) / upstream: cjo4m06/mcp-shrimp-task-manager (MIT)",
        "license": "MIT",
        "category": "TASKS",
        "tags": ["research", "standards", "decision"],
        "trustLevel": "verified",
        "bundled": true,
        "scope": "all",
        "promptInjection": "研究与规则纪律（Shrimp 增益面）：1) 研究模式（research_mode）—— 先界定要决策什么与约束，再取证（WebSearch 官方文档/基准/已知陷阱，结论带来源），对比至少两维（能力/成本/风险/迁移性，过程用 process_thought 分步外化），结论给明确推荐 + 失效条件（何时该换）；研究必须回填：结论作为 analysis 写进相关任务或 plan_task 开新计划 —— 研究不落任务等于白研究。2) 项目规则（init_project_rules）—— 新工作区/新项目先把标准立起来：代码风格、测试要求、提交纪律、命名约定；规则常驻 execute_task 上下文 —— 所有子任务在同一标准下产出（上游 style consistency 的根基）。3) 规则演进 —— 执行中发现规则不合身（如测试框架换版）：先改规则再继续，别默默违背；规则变更当着用户面确认。4) 边界 —— 规则不约束用户明说的临时要求（用户说『这次不用写测试』就照办，但 reflect_task 里记一笔）。"
      }
    }
  ],
  "mcpServers": [
    {
      "serverId": "shrimp",
      "autoConnect": true,
      "scope": "all",
      "role": "16 工具全目录（规划/执行/验证/反思/研究/思维链）—— 进程内实现，任务数据 JSON 原子写持久（filesDir/fusion/shrimp）"
    }
  ],
  "toolActivationHints": [
    "McpShrimpPlanTask",
    "McpShrimpUpdateTask",
    "McpShrimpExecuteTask",
    "McpShrimpCompleteTask",
    "McpShrimpListTasks",
    "McpShrimpReflectTask"
  ],
  "runtime": {
    "engines": [],
    "notes": "零外部引擎：纯 Kotlin 进程内实现（无需 Node/npx/网络 —— 超出上游的设备端零依赖形态）；任务持久化 JSON 原子写 + clear 自动备份链；思维链会话内缓冲不落盘（上游同语义）。"
  },
  "changelog": [
    {"version": "1.0.21", "notes": "FusionBundle 第三波装载：16 工具全目录进程内实现（上游 docs/tools.md 逐名对齐）；任务账本 JSON 原子写 + 备份链；两技能固化规划-执行-反思闭环与研究/规则纪律。"}
  ]
}
""".trim()

    /** 解析后的 manifest（编码期自检 —— 非法即 error）。 */
    fun manifest(): FusionBundleManifest =
        FusionBundleManifest.parse(SHRIMP_BUNDLE_JSON)
            ?: error("内置 Shrimp FusionBundle manifest 非法（编码期错误，需修正）")
}
