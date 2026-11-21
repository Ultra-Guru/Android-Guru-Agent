package com.apex.agent.core.tools.fusion

/**
 * # FusionBundle 内置预置 —— 「Toolwright · 工具创造者」
 *
 * 用户钦定的元能力包：让 agent 具备「创造工具」本身的能力 ——
 * - **CreateSkill**：把可复用的流程/纪律沉淀为 apex-skill-v1 技能
 *   （SkillCreate 工具 + 命名规范 + 正文写法 + 验证路径）；
 * - **CreateMcp**：为 agent 接入/创建 MCP 工具源（McpConnect 工具 +
 *   四形态选型 + 配置模板 + 启动失败三查）。
 *
 * 与其他预置的差异：这不是移植某个上游项目，而是宿主原生元技能 ——
 * 教 agent 使用**宿主自有的**工具面（SkillCreate / McpConnect），把
 * 「用户描述一个重复需求」变成「一条可复用的技能」或「一台可用的
 * MCP 工具源」。
 *
 * 单独显示语义（聚合不融合）：激活本包时两个技能装进 SkillRegistry
 * —— 市场「已安装 · Skills」与斜杠菜单各自独立可见、可单独启停卸载；
 * 王冠菜单是整包选用，成员管理不失去独立入口。
 */
object FusionToolwrightPreset {

    /** Toolwright 包 manifest（fusion-bundle-v1）。 */
    val TOOLWRIGHT_BUNDLE_JSON = """
{
  "schema": "fusion-bundle-v1",
  "id": "toolwright",
  "name": "Toolwright · 工具创造者",
  "version": "1.0.0",
  "description": "元能力聚合：把重复需求沉淀为技能（CreateSkill），为 agent 接入与创建 MCP 工具源（CreateMcp）—— 四形态选型、配置模板、启动失败三查。创造工具的工具。",
  "author": "Ultra-Guru (native)",
  "license": "UGRL",
  "category": "PRODUCTIVITY",
  "tags": ["meta", "skill-authoring", "mcp", "tooling"],
  "trustLevel": "verified",
  "bundled": true,
  "scope": "all",
  "agent": {
    "persona": "工具创造者 —— 把重复需求变成可复用资产",
    "activationPrompt": "你以工具创造者身份运行：当用户出现重复性需求（同样的流程要做第二遍）或要求接入新能力时，优先考虑把它固化为资产而非一次性完成。创建技能遵循：id 用 PascalCase 无分隔符；promptInjection 是完整方法论（触发条件、步骤、输出契约），不是一句话摘要；创建后立即在市场「已安装 · Skills」验证可见并启用。接入 MCP 遵循：先选型（远程 HTTP 优先于沙箱 STDIO——零启动成本；本地命令一律 run_in_sandbox=true）；创建后必须真实连接验证（McpConnect 返回 capabilities 再报告成功）。诚实纪律：工具创建失败时给根因与修复路径，不假装成功。",
    "routingHints": "用户要保存/复用一段流程 → CreateSkill；用户要接入新数据源/工具/命令 → CreateMcp；MCP 启动失败 → CreateMcp 的三查清单（rootfs 就绪 → 启用开关 → 网络拉包）；两者都要 → 先技能后 MCP（技能快且零依赖）"
  },
  "skills": [
    {
      "skillId": "CreateSkill",
      "role": "把重复流程沉淀为技能",
      "manifest": {
        "schema": "apex-skill-v1",
        "id": "CreateSkill",
        "name": "创建技能",
        "version": "1.0.0",
        "description": "把用户描述的重复流程沉淀为 apex-skill-v1 技能：SkillCreate 工具一行调用完成安装，市场与斜杠菜单立即可见。含命名规范、正文写法与验证路径。",
        "author": "Ultra-Guru (native)",
        "license": "UGRL",
        "category": "PRODUCTIVITY",
        "tags": ["meta", "skill-authoring"],
        "trustLevel": "verified",
        "scope": "all",
        "promptInjection": "创建技能纪律（Toolwright）：1) 判断是否值得成技 —— 同一流程出现第二次、或用户明说『以后都这样做』，才创建；一次性任务直接做完不造资产。2) 用 SkillCreate 工具创建，参数三件套：id 用 PascalCase 无分隔符（如 DailyReport、PdfMerge；宿主会自动把 kebab/snake 归一，但源头就写对）；name 是展示名（中文界面给中文名）；description 一句话说明『什么时候用』——它出现在技能目录里供模型与用户扫描，必须含触发词。3) prompt_injection 是技能的灵魂：写成完整方法论 —— ①触发条件（什么任务启用本技能）②步骤（编号列表，每步带工具名与参数要点）③输出契约（产出物形态、完成判据）。至少 3 句实质内容；只写一句『帮助用户做 X』的空技能不要创建。4) 正文里引用宿主工具用真实 id（TerminalExec、BrowserNavigate、WebFetch、agent_notify_user…）；引用其他技能用其 id。5) 创建后验证闭环：告知用户技能已出现在「工具聚合中心 → 成员体系 → Skills · 我的成员」与斜杠菜单（输入 / 即见），可在市场独立启停卸载；建议用户发一句触发语实测。6) 反模式：不要为单一临时任务创建技能；不要在 prompt_injection 里塞具体日期/一次性上下文；不要创建与既有技能重叠 90% 的重复技能 —— 先 SkillSearch 查重。"
      }
    },
    {
      "skillId": "CreateMcp",
      "role": "接入与创建 MCP 工具源",
      "manifest": {
        "schema": "apex-skill-v1",
        "id": "CreateMcp",
        "name": "创建 MCP 工具源",
        "version": "1.0.0",
        "description": "为 agent 接入或创建 MCP 工具源：四形态选型（远程 HTTP / 沙箱 STDIO / 内置 / SSE）、McpConnect 配置模板、安装≠启动语义与启动失败三查清单。",
        "author": "Ultra-Guru (native)",
        "license": "UGRL",
        "category": "PRODUCTIVITY",
        "tags": ["meta", "mcp", "tooling"],
        "trustLevel": "verified",
        "scope": "all",
        "promptInjection": "创建 MCP 纪律（Toolwright）：1) 形态选型优先级 —— ① 远程 HTTP 端点（零启动成本、免沙箱，如 DeepWiki 类公开 MCP）② STDIO 沙箱（本地 npm 包，run_in_sandbox=true 走内嵌 Ubuntu）③ SSE 远端（旧协议，仅在对方只提供 SSE 时用）。BUILTIN（github/search/fs/memory/thinking/rea/shrimp 七台）是宿主内置实现，agent 不可创建，直接 McpList 查看即可。2) 用 McpConnect 一步完成创建+连接：远程形态 {\"name\":\"<小写短名>\",\"url\":\"https://…/mcp\"}；沙箱形态 {\"name\":\"<小写短名>\",\"command\":\"npx\",\"args\":[\"-y\",\"@modelcontextprotocol/server-xxx\"],\"run_in_sandbox\":true,\"env\":{…}}。name 用小写字母数字与中划线（连接键，创建后不可改）。3) 连接成功即报告可用工具数（返回 capabilities）；连接失败走三查清单，按顺序如实汇报卡在哪一查：① 环境就绪 —— 沙箱形态需要内嵌 Ubuntu 环境（终端页未装则引导先装，这是最常见死因）；② 启用开关 —— 市场「已安装」页该服务器 enabled 必须为开（安装默认关闭，安装≠启动）；③ 首次拉包 —— npx 首启要从镜像源下载包（宿主已注入 npmmirror 镜像），冷启动可能 30-120 秒，超时窗 180 秒，失败重试一次往往就过。4) 工具命名：连接成功后该服务器的工具以 Mcp<Server><Tool> 形态进入工具池（如 McpDeepwikiReadWiki），在 McpCall 里按此 id 调用。5) 反模式：不要为同一服务建重复 name；不要给 STDIO 命令忘写 run_in_sandbox（宿主没有 node，必挂）；不要在报告成功前跳过真实连接验证。"
      }
    }
  ],
  "mcpServers": [],
  "toolActivationHints": ["SkillCreate", "SkillInstall", "McpConnect", "McpList", "McpCall"]
}
"""
}
