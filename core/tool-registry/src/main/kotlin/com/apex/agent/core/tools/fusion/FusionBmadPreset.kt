package com.apex.agent.core.tools.fusion

/**
 * # FusionBundle 内置预置 —— 「BMAD · 敏捷 AI 驱动开发方法」
 *
 * 上游（bmad-code-org/BMAD-METHOD v6.12 稳定线，MIT）是「Breakthrough
 * Method for Agile AI-Driven Development」：把想法/变更请求变成可运行
 * 软件的完整敏捷交付环 —— Clarify（澄清）→ Plan（规划）→ Build（构建
 * 与验证）→ Learn（学习调整），决策显式化、上下文向前携带、过程随
 * 工作量自适应伸缩（小改动直进构建，复杂工作拿到应有的深度）。
 *
 * 经 FusionBundle 装载进 AGA（能力面不弱于上游核心）：
 * - 5 个交付环阶段技能（apex-skill-v1 内联）：Clarify / Plan / Shard /
 *   Build / Verify —— 上游 8 代理（分析师/PM/架构师/PO/SM/Dev/QA/UA）
 *   的职责按环阶段重组；
 * - agent 人设：BMAD 编排者（环阶段路由 + 规模自适应）；
 * - MCP 聚合（超出上游的设备端增强）：thinking（顺序思考 —— 规划
 *   阶段推理外化）+ search（网络取证 —— 分析师阶段市场/技术调研）；
 * - 上游 v6 为 npm 安装的代理/模板体系（无 MCP 服务器）—— 本包以
 *   两台内置 MCP 作为增益层，方法论主体如实保持技能形态。
 */
object FusionBmadPreset {

    /** BMAD 包 manifest（fusion-bundle-v1）。 */
    val BMAD_BUNDLE_JSON = """
{
  "schema": "fusion-bundle-v1",
  "id": "bmad",
  "name": "BMAD · 敏捷 AI 驱动开发",
  "version": "6.12.0",
  "description": "Breakthrough Method 敏捷交付环：Clarify（澄清意图）→ Plan（PRD+架构）→ Shard（分片）→ Build（TDD 构建）→ Verify（验收）→ Learn（复盘）。决策显式化、上下文跨阶段携带、规模自适应。对齐上游 bmad-code-org/BMAD-METHOD v6.12 稳定线八代理职责重组。",
  "author": "Ultra-Guru (FusionBundle port) / upstream: bmad-code-org (MIT)",
  "license": "MIT",
  "category": "METHODOLOGY",
  "tags": ["agile", "sdlc", "workflow", "planning", "tdd"],
  "homepage": "https://github.com/bmad-code-org/BMAD-METHOD",
  "repository": "https://github.com/bmad-code-org/BMAD-METHOD",
  "trustLevel": "verified",
  "bundled": true,
  "scope": "all",
  "agent": {
    "persona": "BMAD 敏捷交付环编排者（Analyst/PM/Architect/SM/Dev/QA 八职责一体）",
    "activationPrompt": "你当前以 BMAD 方法体系运行：任何想法或变更请求都进入交付环 —— 1) Clarify：模糊想法先澄清成清晰意图（目标用户、问题陈述、成功标准），需要事实就用检索取证；2) Plan：意图清晰后产出 PRD 与架构（技术选型必带理由与备选、风险与缓解、验收标准可测）；3) Shard：把计划切成可独立验证的分片（每片有明确完成定义）；4) Build：逐片实现，恪守 TDD（无失败测试不写产品代码），每片完成即验证；5) Verify：对照验收标准逐条核对 + 冒烟主路径；6) Learn：复盘沉淀（什么有效/什么没效/下次怎么改），结论写回计划文档。规模自适应铁律：小改动跳过重型阶段直进 Build（一句『改个颜色』不需要 PRD）；复杂工作才上全环深度 —— 过程为工作服务，不是工作为过程服务。上下文携带：每阶段产出结构化文档，下一阶段从文档出发不从记忆出发。",
    "routingHints": "想法模糊/说不清要什么 → BmadClarify（+ McpSearchWebSearch 取证）；意图清晰要做规划 → BmadPlan（+ McpThinkingSequentialthinking 推理外化）；计划太大 → BmadShard 分片；拿到分片 → BmadBuild（Task 派子代理 + TDD）；实现完成 → BmadVerify（验收核对 + 冒烟）；交付/迭代结束 → Learn 复盘；一行能改完的小事 → 直接改（跳过环）"
  },
  "skills": [
    {
      "skillId": "BmadClarify",
      "role": "澄清阶段（分析师职责）",
      "manifest": {
        "schema": "apex-skill-v1",
        "id": "BmadClarify",
        "name": "BMAD 澄清阶段",
        "version": "6.12.0",
        "description": "模糊想法 → 清晰意图：问题陈述、目标用户、成功标准、范围边界；需要事实先取证。",
        "author": "Ultra-Guru (port) / upstream: bmad-code-org (MIT)",
        "license": "MIT",
        "category": "METHODOLOGY",
        "tags": ["agile", "clarify", "requirements"],
        "trustLevel": "verified",
        "bundled": true,
        "scope": "all",
        "promptInjection": "澄清阶段纪律（BMAD · 分析师）：1) 产出三件套：问题陈述（要解决谁的什么痛点，一句话）、目标用户画像（谁在什么场景用）、成功标准（可观察可度量，2-4 条）。2) 范围边界显式化：明确『不做什么』与『留待下期』—— 范围蔓延是交付环第一杀手。3) 涉及市场/竞品/技术现状的断言先用 WebSearch 取证（带来源），不空对空。4) 已有系统的话：梳理现状与约束（既有架构/数据/团队能力），变更影响面初判。5) 澄清对话 3-5 轮收敛：对方开始重复即进入确认 —— 回放三件套请对方逐条确认。6) 小改动判定：一句话能说清且影响面单一 → 记录后直进 Build，不硬走全环。"
      }
    },
    {
      "skillId": "BmadPlan",
      "role": "规划阶段（PM + 架构师职责）",
      "manifest": {
        "schema": "apex-skill-v1",
        "id": "BmadPlan",
        "name": "BMAD 规划阶段",
        "version": "6.12.0",
        "description": "清晰意图 → PRD + 架构：用户故事带验收标准，技术选型带理由与备选，风险带缓解。",
        "author": "Ultra-Guru (port) / upstream: bmad-code-org (MIT)",
        "license": "MIT",
        "category": "METHODOLOGY",
        "tags": ["agile", "planning", "architecture"],
        "trustLevel": "verified",
        "bundled": true,
        "scope": "all",
        "promptInjection": "规划阶段纪律（BMAD · PM + 架构师）：PRD 侧 —— 1) 用户故事模板『作为<角色>，我要<能力>，以便<价值>』，每条带 2-5 条可测验收标准（Given/When/Then 形态优先）；2) 非功能需求单列（性能/安全/离线/隐私 —— 移动场景必查）。架构侧 —— 3) 技术选型每个都带：候选对比（≥2）、选型理由、被放弃候选的失效条件（何时该换）；4) 数据模型与接口契约先行（字段/类型/错误语义），实现前冻结；5) 风险登记：每条风险带概率×影响×缓解措施×触发信号。6) 复杂规划用顺序思考工具外化推理链（McpThinkingSequentialthinking），关键决策留痕可回溯。7) 规划产出落盘为文档（写入工作区文件），下一阶段从文档出发。"
      }
    },
    {
      "skillId": "BmadShard",
      "role": "分片阶段（SM 职责）",
      "manifest": {
        "schema": "apex-skill-v1",
        "id": "BmadShard",
        "name": "BMAD 分片阶段",
        "version": "6.12.0",
        "description": "计划 → 可独立验证的工作分片：每片有完成定义、依赖显式、可派子代理。",
        "author": "Ultra-Guru (port) / upstream: bmad-code-org (MIT)",
        "license": "MIT",
        "category": "METHODOLOGY",
        "tags": ["agile", "sharding", "task-breakdown"],
        "trustLevel": "verified",
        "bundled": true,
        "scope": "all",
        "promptInjection": "分片阶段纪律（BMAD · SM）：1) 分片单位 = 一个可独立验证的增量（一片做完能跑一条验证命令并观察结果），不是『一个文件』也不是『一个层』。2) 每片字段：目标、涉及文件（确切路径）、完成定义（跑什么、期望什么输出）、依赖片（显式 DAG，无依赖即并行候选）。3) 片间依赖是最小集：能解耦就解耦（接口契约先行）—— 依赖越少，Task 子代理并行度越高。4) 验收标准拆到片级：每片映射 PRD 的哪条标准，合起来必须全覆盖（缺口即漏测）。5) 风险前置：不确定的技术验证点（新依赖/新 API）独立成探针片，最先做。6) 分片清单落盘，执行阶段按清单逐片销账。"
      }
    },
    {
      "skillId": "BmadBuild",
      "role": "构建阶段（Dev 职责）",
      "manifest": {
        "schema": "apex-skill-v1",
        "id": "BmadBuild",
        "name": "BMAD 构建阶段",
        "version": "6.12.0",
        "description": "逐片 TDD 构建：先写失败测试再实现，片完成即验证，子代理并行推进。",
        "author": "Ultra-Guru (port) / upstream: bmad-code-org (MIT)",
        "license": "MIT",
        "category": "METHODOLOGY",
        "tags": ["agile", "build", "tdd"],
        "trustLevel": "verified",
        "bundled": true,
        "scope": "all",
        "promptInjection": "构建阶段纪律（BMAD · Dev）：1) 严格按分片 DAG 执行：无依赖的片用 Task 子代理并行派发（每子代理一片 + 该片的完成定义 + 干净上下文）；有依赖的串行。2) 片内 TDD：先写失败测试（映射该片完成定义）→ 看它失败 → 最小实现 → 看它通过 → 重构。3) 片完成立即跑完成定义里的验证命令 —— 不过不销账、不进下一片。4) 计划外发现（接口对不上/路径不存在/文档过时）：小且局部就修并记录；影响契约就停，回 Plan 修订再继续 —— 不默默绕行。5) 每片销账留痕：做了什么、验证输出摘要、偏离与原因。6) 主上下文只做调度/评审/整合，实现细节下放子代理。"
      }
    },
    {
      "skillId": "BmadVerify",
      "role": "验收与复盘阶段（QA + Learn）",
      "manifest": {
        "schema": "apex-skill-v1",
        "id": "BmadVerify",
        "name": "BMAD 验收与复盘",
        "version": "6.12.0",
        "description": "验收标准逐条核对 + 冒烟主路径 + 复盘沉淀（Learn 环）：什么有效、什么没效、写回计划。",
        "author": "Ultra-Guru (port) / upstream: bmad-code-org (MIT)",
        "license": "MIT",
        "category": "METHODOLOGY",
        "tags": ["agile", "qa", "retrospective"],
        "trustLevel": "verified",
        "bundled": true,
        "scope": "all",
        "promptInjection": "验收与复盘纪律（BMAD · QA + Learn）：验收 —— 1) 对照 PRD 验收标准逐条核对：每条给『通过/不通过/未测』三态 + 证据（命令与输出）；不通过的按严重度分级（阻断/重要/建议），阻断项不过不交付。2) 冒烟主路径：至少一条端到端用户路径完整走通（不是单测绿就算完）。3) 回归检查：改动波及面重跑相关测试。复盘（Learn）—— 4) 三问：什么有效（保持）、什么没效（停止/改法）、下次怎么做（具体动作）；5) 结论写回计划文档（追加 Learn 段），下一迭代从最新文档出发 —— 不从记忆出发；6) 未尽事项转待办（带优先级与估算），不丢不藏。交付报告：交付物清单 + 验收矩阵 + 已知限制 + 待办。"
      }
    }
  ],
  "mcpServers": [
    {
      "serverId": "thinking",
      "transport": "BUILTIN",
      "autoConnect": true,
      "scope": "all",
      "role": "增益层（超出上游）：规划阶段推理外化 —— McpThinkingSequentialthinking"
    },
    {
      "serverId": "search",
      "transport": "BUILTIN",
      "autoConnect": true,
      "scope": "all",
      "role": "增益层（超出上游）：澄清阶段取证 —— McpSearchWebSearch/WebFetch"
    }
  ],
  "toolActivationHints": [
    "McpThinkingSequentialthinking",
    "McpSearchWebSearch",
    "Task",
    "Read",
    "Write",
    "Edit",
    "TerminalExec"
  ],
  "runtime": {
    "engines": [
      {"name": "git", "purpose": "交付环的分支/提交纪律（可选增强）", "optional": true, "installHint": "PRoot Ubuntu 终端: apt install git（宿主 fallback 链亦有 git）"}
    ],
    "notes": "方法论主体零引擎依赖；两台内置 MCP 为增益层（顺序思考 + 网络取证），零子进程零网络握手。"
  },
  "changelog": [
    {"version": "6.12.0", "notes": "FusionBundle 装载：上游 v6.12 稳定线八代理职责按交付环五阶段重组移植；增益层聚合 thinking/search 两台内置 MCP（超出上游的能力增强）。"}
  ]
}
""".trim()

    /** 解析后的 manifest（编码期自检 —— 非法即 error）。 */
    fun manifest(): FusionBundleManifest =
        FusionBundleManifest.parse(BMAD_BUNDLE_JSON)
            ?: error("内置 BMAD FusionBundle manifest 非法（编码期错误，需修正）")
}
