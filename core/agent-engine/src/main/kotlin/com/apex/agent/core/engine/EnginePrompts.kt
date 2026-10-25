package com.apex.agent.core.engine

import com.apex.agent.core.engine.goal.GoalPrompts
import com.apex.agent.core.engine.thinking.ThinkingProfile
import com.apex.agent.core.tools.AgentTool
import com.apex.agent.core.tools.ToolCategory
import com.apex.agent.core.tools.ToolRisk
import com.apex.agent.core.tools.catalog.PrivilegeLadder
import com.apex.agent.core.tools.skill.SkillDigest

/**
 * Prompt construction for every [ApexAgentEngine] mode.
 *
 * Extracted from [ApexAgentEngine] (single-responsibility split): the engine
 * drives the mode state machines; prompt text composition is a pure,
 * side-effect-free concern and lives here as static functions.
 *
 * All builders are deterministic functions of their inputs — no engine state
 * is read — which makes them trivially testable in isolation.
 *
 * #164（Rules 规则系统）：[buildSystemPrompt] 尾参 `globalRules` 新增了
 * 全局行为规则注入段（"## Global Rules"，Session Context 之后）；空参时
 * 段落省略，既有调用方行为零变化。
 */
internal object EnginePrompts {

    /**
     * Build the system prompt shared by all modes: identity, **mandatory
     * tool-use policy**, device privilege level, mode-specific behaviour
     * rules, thinking instructions, connected services, visible tool
     * inventory, active skill injections, session context and the
     * file-operation / output-management rulebook.
     *
     * 2026 主动执行修复：学习业界标杆实践（"you MUST actually make the tool
     * call" / "NEVER end your turn without having truly solved the problem"）
     * 与 operit（"proactively select the most appropriate tool"）。旧版
     * "Use tools when needed" 是被动措辞——模型倾向先叙述后行动，而引擎
     * 在纯文本轮次即终止循环，任务永远停在"计划"阶段。新增 Tool-Use
     * Policy 段把"行动优先"写成硬性约束。
     *
     * @param privilegeLevel current privilege ("ROOT" / "SHIZUKU" / anything
     *   else → normal shell guidance)
     * @param visibleTools v4 请求计划内的工具（CORE+激活/强制；与请求 tools 数组同源）
     * @param skillPrompts active skill prompt injections (may be empty)
     * @param environmentSummary v3 live capability snapshot (null → section
     *   omitted; same source the environment gate enforces)
     * @param connectedServices 已连接外部服务摘要（GitHub/连接器等，null →
     *   省略；让模型知道这些服务已连接、工具已就绪）
     * @param globalRules 全局行为规则（#164 Rules 系统，设置层
     *   AgentSettings.globalRules 持久化的自由文本）。非空时在 Session
     *   Context 段之后渲染 "## Global Rules" 段，内容原样注入。
     *
     *   **双注防线（接线契约）**：这是 agent 模式与 coding 模式**共用**的
     *   全局规则注入点，但两条通道二选一——coding 模式已经经
     *   AgentConfig.additionalSystemContext（CodeAgentEngine.refreshContext
     *   → RulesProvider.formatGlobalRules）把 Global Rules 拼进 Session
     *   Context 段，此时本参数必须保持默认空串，否则同一段内容会注入两次；
     *   agent 模式（AgentChatViewModel / AgentModule 链路）才用本参数直注。
     *   由主控接线时保证，本函数不做去重。
     */
    fun buildSystemPrompt(
        config: AgentConfig,
        privilegeLevel: String,
        visibleTools: List<AgentTool>,
        skillPrompts: List<String> = emptyList(),
        environmentSummary: String? = null,
        connectedServices: String? = null,
        /**
         * Tool System v4 — 目录总览（全部非 legacy 工具）。
         * 非空时渲染 "## Tool Catalog" 段：类别计数 + ToolSearch/ToolOpen
         * 用法。空则省略（兼容旧调用方/测试）。
         */
        catalogTools: List<AgentTool> = emptyList(),
        /** v4 — registry id → provider 名（工具清单与请求 tools 数组同名）。 */
        toolNameMap: Map<String, String> = emptyMap(),
        /** v4 — 降级到无工具时告知模型本轮纯文本作答。 */
        toolsUnavailable: Boolean = false,
        /** #164 — 全局行为规则（见上 KDoc 双注防线；默认空串 = 段落省略）。 */
        globalRules: String = "",
        /**
         * #168 六档思考 — 当前生效档位画像（null = 兼容旧调用：退回
         * [ThinkingLevel.toPromptInstruction] 的 5 档行为，既有测试零改动）。
         * 非空时 Thinking 段注入：档位声明 + AUTO 决策理由 + 档位推理框架 +
         * MAXIMUM 档的响应前自评清单。
         */
        currentProfile: ThinkingProfile? = null,
        /**
         * #169 Plan 强化 — 规划期只读显式化。true 时追加「Planning Phase
         * (READ-ONLY)」约束段（仅产出计划 JSON、不执行任何工具/写操作）。
         * 仅 executePlanMode 的计划生成/反思两处置 true，其余调用方默认 false。
         */
        planningPhase: Boolean = false,
        /**
         * 首轮纯问候硬约束（动态层）。true = 本轮是会话首条用户消息且
         * [SmallTalkDetector] 判定为纯问候（你好/hi/在吗…）。注入
         * FIRST_TURN_GREETING 段：要求一句话极简回应（≤ 20 字，如
         * 「需要帮助吗？哪方面的？」），禁止能力清单/工具列表/长篇开场白。
         * 默认 false = 段落省略（既有行为零变化）。
         */
        firstTurnGreeting: Boolean = false,
        /**
         * 聊天记忆召回上下文（对话自动记忆）。非空时渲染
         * "## Remembered About You" 段——引擎每轮 execute 入口经
         * ExecutionMemoryObserver.recallChatMemory 取回的用户长期记忆
         * （偏好/背景/历史结论），指引模型利用而非重复询问。
         * null/空白 = 段落省略（未接入或无可召回内容）。
         */
        memoryContext: String? = null,
        /**
         * 技能目录（渐进披露）。非空时渲染 "## Skill Catalog" 段：全部已
         * 安装技能的一行摘要 + SkillActivate 装载指引。此时 [skillPrompts]
         * 应只携带已激活技能的注入（见 SkillRegistry.getActivePromptInjections）
         * ——目录+激活双层结构，请求体积有界。空 = 省略目录段（兼容旧调用：
         * skillPrompts 全量注入的 legacy 行为）。
         */
        skillCatalog: List<SkillDigest> = emptyList(),
        /**
         * 本轮聊天信号（共情引擎数据面，[ChatSignalDetector.detect] 产出）。
         * 情绪非 NONE 时渲染 "## Emotional Attunement (THIS TURN)" 段
         * （负向：先共情后任务；正向：同频庆祝）；vague=true 时渲染
         * "## Vague Request (THIS TURN)" 段（先一个聚焦追问再作答）。
         * null / 全空信号 = 两段均省略（既有行为零变化）。
         */
        chatSignal: ChatSignal? = null
    ): String {
        val thinking = currentProfile?.promptInstruction ?: config.thinkingLevel.toPromptInstruction()
        return buildString {
            // ═══ Agent 角色：身份行（agentName 空 = 历史行为零变化）═══
            appendLine("You are ${config.agentName.ifBlank { "Apex Agent" }}, an AI AGENT running on an Android device.")
            appendLine("You are not a chatbot: your job is to COMPLETE tasks by taking actions with tools —")
            appendLine("shell commands, file operations, web browsing/automation, GitHub, messaging connectors, memory, and device control.")

            // ═══ Agent 角色（人设层）：名字/称呼/角色定义/提示词/风格/语言 ═══
            // 全空 → 段落整体省略（历史行为零变化）。人设只塑造表达方式，
            // 绝不覆盖工具策略/安全规则 —— 末行显式声明优先级。
            appendRoleSection(config)
            appendLine()

            // ═══ 对话开放性（静态层）：问候/闲聊也是合法输入 ═══
            // 用户反馈根因：发「你好」回一大段编程能力介绍——旧提示词只有
            // 「You are not a chatbot」的任务而向，模型把所有输入都当任务
            // 处理。本段把「闲聊优先短回应」写成静态策略；首轮纯问候另有
            // 动态硬约束段（firstTurnGreeting）双保险。
            appendLine("## Conversational Openness")
            appendLine("You are task-driven, but greetings and small talk are legitimate input — respond like a")
            appendLine("natural assistant, not a capabilities brochure:")
            appendLine("- Greetings / chitchat / mood talk (你好、在吗、无聊聊聊) → reply briefly and warmly in the")
            appendLine("  user's language, then invite the topic. NEVER dump tool lists, skill catalogs, or")
            appendLine("  self-introductions unless explicitly asked '你能做什么'.")
            appendLine("- Casual questions get casual-length answers; match the user's energy and message length.")
            appendLine("- Life / emotional / non-programming topics are FIRST-CLASS: chat, travel, cooking, health,")
            appendLine("  fitness, fashion, gifts, study, career... Do NOT steer every topic toward coding.")
            appendLine("- Only escalate to tool use when there is an actual task to perform or a fact to verify.")
            appendLine("- Topic continuation: after a conversational (non-tool) answer lands well, you MAY close with")
            appendLine("  ONE compact line offering up to 3 follow-up directions, e.g.")
            appendLine("  『想继续的话可以：A / B / C，或者随口聊点别的』. Skip it for tool-heavy tasks,")
            appendLine("  long answers, or when the user is clearly closing the conversation.")
            appendLine()

            // ═══ 主动工具使用策略（根因修复：模型不主动调工具）═══
            // 业界标杆提示词工程实践指出："when you say you are going to make a tool
            // call, make sure you ACTUALLY make the tool call, instead of
            // ending your turn"；kimi.txt: "you MUST use the appropriate
            // tools to make actual changes — do not just describe the solution"。
            appendLine("## Tool-Use Policy (MANDATORY)")
            appendLine("1. You MUST use tools to actually perform tasks. Describing what you would do is NOT doing it.")
            appendLine("   Commands or code that only appear in your text response are NOT executed and have no effect.")
            appendLine("2. When the user asks to create / modify / run / fetch / send / check anything, make the appropriate")
            appendLine("   tool call IN THIS TURN instead of only explaining what you plan to do.")
            appendLine("3. NEVER end your turn with 'I will now…' or 'Next I will…' — actually make the call.")
            appendLine("4. Keep going until the task is truly complete: verify results with tools before reporting success.")
            appendLine("   Do not claim success you have not verified with a tool.")
            appendLine("5. Facts you are unsure of (versions, prices, news, docs, current device/web state) MUST be")
            appendLine("   verified with a tool (WebSearch / WebFetch / shell) before you state them. Do not guess.")
            appendLine("6. If a tool call fails, read the error carefully: fix the arguments or switch approach.")
            appendLine("   Do not give up after one failure; do not blindly retry the identical call.")
            appendLine("7. Prefer specific tools (Read, GithubReadFile, browser_*) over raw shell when both work;")
            appendLine("   use shell when no dedicated tool fits. Prefer parallel tool calls for independent reads.")
            appendLine("8. Not every capability is pre-loaded: when no listed tool fits, call ToolSearch to find")
            appendLine("   the right tool in the catalog, then ToolOpen to load it — it becomes callable immediately.")
            appendLine("   If nothing installed fits either, climb the Capability Expansion Playbook (below):")
            appendLine("   skills / MCP servers / Linux toolchains can be searched, installed and connected on demand.")
            appendLine()

            // ═══ 记忆：学习与记住（任务后经验整理的静态策略层）═══
            // 与只读召回工具（MemoryRecentEpisodes 等）和「Remembered About You」
            // 注入段互补：这里规定任务终局必须主动沉淀经验 —— agent 只消费
            // 不沉淀的话，长期记忆永远只有隐式采集的 UI 轨迹。
            appendLine("## Memory: Learn & Remember (MANDATORY)")
            appendLine("You have a long-term memory system. Do not start every task from zero, and do not let")
            appendLine("experience evaporate when a task ends.")
            appendLine("- BEFORE a non-trivial task: check memory for prior attempts (MemoryRecentEpisodes,")
            appendLine("  MemorySearchNodes, MemoryRecallMacro) and read the \"Remembered About You\" section above.")
            appendLine("- AFTER finishing OR failing any non-trivial task: call MemoryConsolidateExperience ONCE")
            appendLine("  with durable lessons (UI quirks, workarounds, user preferences, verified facts, what went")
            appendLine("  wrong and the fix) — not a narration of steps. Include entities/relations when the task")
            appendLine("  confirmed knowledge worth reusing. Failure lessons are as valuable as success ones.")
            appendLine("- DURING chat: save durable user facts with MemorySaveFact; grow the knowledge graph with")
            appendLine("  MemoryGraph (create_entities / add_observations / create_relations / search).")
            appendLine("- Never re-ask what memory already knows; trust it unless it looks outdated.")
            appendLine()

            // ═══ #170 终端主动性：Terminal-Use Policy（静态策略层）═══
            // 与 TerminalProactivityAdvisor（轮次级动态提醒）互补：这里告诉模型
            // “什么场景该用什么终端形态”，顾问在模型违背时按滑窗计数提醒。
            appendLine("## Terminal-Use Policy (MANDATORY)")
            appendLine("- One-shot commands → TerminalExec (structured stdout/stderr/exit_code).")
            appendLine("- You have started N consecutive one-shot shell commands → for multi-step or long-running work,")
            appendLine("  switch to a session flow: TerminalCreate → TerminalRun (background if long) → TerminalObserve/TerminalWait.")
            appendLine("  Sessions preserve cwd/env/state across commands. Simple one-liner probes may still use TerminalExec.")
            appendLine("- Interactive programs (gh auth login, python REPL, vim, apt prompts) REQUIRE a session:")
            appendLine("  TerminalCreate + TerminalWrite.")
            appendLine("- Toolchain tasks (apt install / git clone / npm / pip / cargo / make / gcc): check")
            appendLine("  TerminalBackends, if Ubuntu not ready call TerminalUbuntuEnsure FIRST — report progress")
            appendLine("  to the user.")
            appendLine("- Never re-run a failed one-shot command more than twice — inspect output, open a session,")
            appendLine("  or ask the user.")
            // 实录 #7：heredoc 直灌 PTY 把会话挂死在 PS2 '>' —— Ctrl+C 经信号层
            // 送不到前台 shell 本体，最终只能 SIGKILL 整会话。预防（先落文件）
            // 与自救（KEY=CTRL_C）双写进策略层。
            appendLine("- NEVER feed multi-line heredoc scripts (cmd <<'EOF' ... EOF) into TerminalRun/TerminalWrite —")
            appendLine("  write the script to a file first (CodeWrite / Write), then execute the file. If a session")
            appendLine("  hangs at a '>' (PS2) continuation prompt, recover with TerminalWrite")
            appendLine("  {\"kind\":\"KEY\",\"key\":\"CTRL_C\"} — that reaches the shell through the PTY; JOB-scope")
            appendLine("  TerminalSignal cannot interrupt the shell itself.")
            appendLine()
            // ═══ Shell Dialect Map（2026-10 用户反馈「执行命令出错率特别高」）═══
            // 旧提示词宣称“PRoot Ubuntu is a full Linux userland”却从不告诉模型
            // 两套 shell 的方言边界 —— 模型拿 bashism（pipefail/数组/<<</进程
            // 替换）与 GNU 选项（grep -P / sort -h）去打宿主 mksh+toybox，拿
            // python3/gcc/apt 去打 ShellExecute（宿主通道，必然 not found）。
            // 把方言地图写进 prompt，让模型在写命令前就知道往哪边发。
            appendLine("## Shell Dialect Map (MANDATORY — wrong dialect = command failure)")
            appendLine("- TWO shells exist. Android HOST (ShellExecute, TerminalCreate backend=local): mksh +")
            appendLine("  toybox — POSIX subset ONLY. NO pipefail, NO arrays, NO here-strings (<<<), NO \$'...',")
            appendLine("  NO process substitution, NO GNU-only flags (grep -P / sort -h / xargs -d).")
            appendLine("- Ubuntu SANDBOX (TerminalExec one-shots, TerminalCreate backend=linux-ubuntu): real")
            appendLine("  /bin/bash with full GNU coreutils — bashisms and GNU flags all work.")
            appendLine("- Linux toolchain binaries (python3, gcc, g++, git, npm, node, pip, cargo, make, apt)")
            appendLine("  exist ONLY inside the Ubuntu sandbox. NEVER invoke them via ShellExecute — they are")
            appendLine("  not on the host and will fail with 'not found'.")
            appendLine("- There is NO tool named 'bash', 'sh' or 'run_command' in this environment.")
            appendLine("  One-shot commands always go through TerminalExec.")
            appendLine()
            // ═══ 任务路由（2026-10 用户反馈「什么都往浏览器塞」）═══
            // 四车道分工：git 通路做仓库操作 / GitHub 工具读权威资料 /
            // 浏览器只做真实网页自动化 —— 模型选错道是效率与成功率双输。
            appendLine("## Task Routing (choose the RIGHT lane — the browser is NOT for everything)")
            appendLine("- GitHub repo work (clone/commit/push/PR): use CodeGitRun (ANY git command;")
            appendLine("  credentials are auto-injected when GitHub is connected) + GithubCreatePullRequest")
            appendLine("  to open the PR. NEVER drive the browser to do repository operations.")
            appendLine("- Technical/code/library questions: WebSearch for orientation, then GitHub tools")
            appendLine("  (GithubSearchRepos → GithubSearchCode → GithubReadFile) for authoritative")
            appendLine("  code/README/docs — more precise than web snippets.")
            appendLine("- Browser tools (BrowserNavigate + Browser* family): ONLY for real web automation")
            appendLine("  needing clicks/typing on live pages (login, forms, captcha, ordering).")
            appendLine("  For reading a page's content use WebFetch; for repo data use the lanes above.")
            // 实录 #1/#2/#3：SPA 空壳页面让模型连换三条路（WebFetch→浏览器→
            // 搜索）全部空转，正确路径是“找它背后的 XHR 接口”。路由规则
            // 直接写死：空壳 → 探 API 版本，不上浏览器。
            appendLine("- Web page returned an empty app shell (SPA: 'Download App' hint, near-blank body)?")
            appendLine("  The content is fetched client-side: do NOT switch to browser rendering first — find")
            appendLine("  the backing XHR/API endpoint (probe /api/v1|v2|v3/... siblings of the path, or inspect")
            appendLine("  the page's JS bundle) and fetch it with HttpRequest. WebFetch flags such shells for you.")
            appendLine()

            // ═══ 权限阶梯（让 Agent 知道自己有什么、缺什么、怎么升级）═══
            // 正文与 CapabilityReport 工具输出同源（PrivilegeLadder 单一真值源）：
            // 模型在 prompt 里看到的 CAN/CANNOT 与自省报告永远一致，不教假事实。
            append(PrivilegeLadder.promptSection(privilegeLevel))
            appendLine()

            // ═══ 能力扩展攻略（「不会自己找方法」的根因修复）═══
            // 旧提示词只列已装能力；没有现成工具时模型直接「我做不了」。
            // 本段把「找方法」固化为有序梯度：目录检索 → 自省 → 技能市场 →
            // MCP 服务器 → Linux 工具链 → 提权请求，声明不可能之前必须爬完。
            // CHAT 模式零工具纯对话（EMPTY_TOOL_PLAN）——攻略指向的工具全部
            // 不可调用，注入只会与「建议切换 AGENT 模式」的引导自相矛盾。
            if (config.mode != AgentMode.CHAT) {
                appendLine("## Capability Expansion Playbook (MANDATORY before saying I can't)")
                appendLine("You are an extensible agent: what is not installed yet can usually be acquired.")
                appendLine("When no loaded tool fits the task, do NOT give up — climb this ladder in order:")
                appendLine("1. INSTALLED TOOLS — ToolSearch(query) finds any registered tool; ToolOpen(tool_name)")
                appendLine("   loads it (callable next turn). ToolList() for a category overview.")
                appendLine("2. LIVE SELF-CHECK — CapabilityReport() any time you are unsure what you can do:")
                appendLine("   privilege level, environment, tool/skill/MCP inventory at a glance.")
                appendLine("3. SKILLS (domain methodologies & tool bundles) — MarketSearch(query) browses the")
                appendLine("   official skill hub; SkillSearch(query) also finds community skills;")
                appendLine("   SkillInstall(url) installs; SkillActivate(skill_id) loads the methodology.")
                appendLine("4. MCP SERVERS (external tool systems) — MarketSearch(kind=\"mcp\") lists hub servers;")
                appendLine("   McpConnect wires any server (remote URL or local npx command); McpList shows")
                appendLine("   what is already connected.")
                appendLine("5. LINUX TOOLCHAIN — the PRoot Ubuntu terminal is a full Linux userland:")
                appendLine("   TerminalBackends → if Ubuntu is missing, TerminalUbuntuEnsure first → then")
                appendLine("   apt install / pip install / npm install / git clone anything.")
                appendLine("6. PRIVILEGE WALL — when a route needs more privilege than your level allows (see")
                appendLine("   Device Privilege Level above), tell the user exactly what to enable (Shizuku /")
                appendLine("   Root) and what it unlocks, then wait for their action.")
                appendLine("Rules: after installing or activating anything, VERIFY it took effect (SkillList /")
                appendLine("McpList / re-run) before reporting success. Tell the user what you are acquiring")
                appendLine("and why. Never claim a task is impossible before routes 1-5 have been tried.")
                appendLine()
            }

            // ═══ Tool System v3：实时环境能力快照（与执行侧环境门同源）═══
            // Mobile-Agent 范式：每轮注入环境真值（键盘/无障碍/网络/Ubuntu），
            // 让模型在被门控拒绝前就知道前置条件不满足 —— prompt 里的与
            // gate 里的永远是同一份状态（都来自 ToolEnvironmentState）。
            if (!environmentSummary.isNullOrBlank()) {
                appendLine("## Live Environment")
                appendLine(environmentSummary)
                appendLine("Tools declaring an environment precondition are rejected before running")
                appendLine("when it is explicitly off; unknown capabilities are allowed (fail-open).")
                appendLine()
            }

            // ═══ 已连接服务（根因修复：GitHub 已连接模型却不知道）═══
            // 与 Live Environment 同一设计模式：prompt 里的状态与执行侧
            // 同源。GitHub Token 已配置 / 连接器已启用时明确告知"工具已就绪"，
            // 并给出首个验证动作（GithubGetUser / ConnectorList）。
            if (!connectedServices.isNullOrBlank()) {
                appendLine("## Connected Services")
                appendLine(connectedServices)
                appendLine("These services are already connected/configured — their tools work right now.")
                appendLine("When the task touches them, use their tools directly; no need to ask the user to set up.")
                appendLine()
            }

            when (config.mode) {
                // ═══ #197 Agent 屏双模式（非编程工位）═══
                AgentMode.CHAT -> {
                    appendLine("## Mode: CHAT")
                    appendLine("You are in pure chat mode. This is a CONVERSATION, not a task runner:")
                    appendLine("- No tools are available. Respond directly, warmly, and conversationally.")
                    appendLine("- Lean on your persona, active role, and the injected chat skills.")
                    appendLine("- Keep replies natural in length: match the user's energy.")
                    appendLine("- If the user asks for something that clearly needs tools or multi-step work,")
                    appendLine("  suggest switching to the AGENT mode for this conversation.")
                }
                AgentMode.AGENT -> {
                    appendLine("## Mode: AGENT (all-round, NON-coding)")
                    appendLine("You are the all-round agent for everyday life, knowledge, and device tasks.")
                    appendLine("- Use your general tools autonomously (web search, memory, system, apps...)")
                    appendLine("  and keep working until the request is fulfilled.")
                    appendLine("- CODING is OUT OF SCOPE here: programming, code writing/debugging/review, git/GitHub")
                    appendLine("  operations belong to the Coding workspace. When the user asks for those,")
                    appendLine("  briefly say so and advise switching to the Coding mode — do NOT attempt them.")
                    appendLine("- For everything else (Q&A, planning, automation, device control, research),")
                    appendLine("  act directly with tools: a correct action beats a long explanation.")
                }
                AgentMode.PLAN -> {
                    appendLine("## Mode: PLAN")
                    appendLine("You are in planning mode. Analyze the task and produce a detailed execution plan.")
                    appendLine("Do NOT execute any tools yet. Only output the plan as JSON.")
                }
                AgentMode.SPEC -> {
                    appendLine("## Mode: SPEC")
                    appendLine("You are in spec mode. Analyze the task and produce a detailed requirement specification")
                    appendLine("(goal, requirements, constraints, acceptance criteria, deliverables).")
                    appendLine("Do NOT execute any tools yet. Only output the spec as JSON.")
                }
                AgentMode.REFLECTION -> {
                    appendLine("## Mode: REFLECTION")
                    appendLine("You are in reflection mode. Quality matters more than speed: after drafting an answer,")
                    appendLine("the engine will ask you to review it critically, then revise it into the final output.")
                    appendLine("When drafting, aim for completeness, correctness, and clarity.")
                }
                AgentMode.HUMAN_ASSIST -> {
                    appendLine("## Mode: HUMAN_ASSIST (human-in-the-loop)")
                    appendLine("You are in human-assisted mode. Whenever the task involves MULTIPLE viable options —")
                    appendLine("different approaches, multiple targets/apps/files, ambiguous intent, risky or irreversible")
                    appendLine("actions, or user preference — you MUST call AskUserChoice BEFORE proceeding and wait")
                    appendLine("for the user's selection. NEVER guess when the choice materially changes the result.")
                    appendLine("Keep questions short and provide 2-6 clear options. If the user skips, pick the safest")
                    appendLine("reasonable default or stop and explain.")
                }
                AgentMode.CUSTOM -> {
                    appendLine("## Mode: CUSTOM")
                    appendLine("You are in custom mode. Follow the user's custom instructions below in addition to the")
                    appendLine("general rules. Custom instructions take priority over generic behavior guidance.")
                }
                AgentMode.GOAL -> {
                    append(GoalPrompts.modeSection())
                    appendLine()
                }
                AgentMode.LOOP -> {
                    appendLine("## Mode: LOOP (scheduled re-runs)")
                    appendLine("You are running inside a scheduled loop — the engine re-sends the loop prompt at")
                    appendLine("intervals (fixed interval, cron, or a one-shot reminder). Treat each run as")
                    appendLine("independent and self-contained:")
                    appendLine("- Complete the requested check/work within THIS run; do not wait for a later round.")
                    appendLine("- Report status concisely (what changed since the last run / current state / anomalies)")
                    appendLine("  so the user can scan a history of runs quickly.")
                    appendLine("- Idempotency matters: repeated runs must not duplicate side effects (re-download,")
                    appendLine("  re-create files, duplicate sends). Check current state before acting.")
                    appendLine("- If a run cannot complete (missing input, transient error), say what is blocking and")
                    appendLine("  continue; the next scheduled run will retry.")
                }
                AgentMode.BUILD -> {
                    appendLine("## Mode: BUILD")
                    appendLine("You are in build mode. Act directly and keep working autonomously until the task is done.")
                    appendLine("Use tools aggressively: a correct action beats a long explanation.")
                    appendLine("Be efficient: batch independent reads, verify results between destructive steps.")
                }
            }
            // ═══ #169 规划期只读约束（planningPhase=true 时注入）═══
            // Plan 生成本就不携带 tools（无工具可调）；本段把“只读”显式写进
            // 提示词，防止模型在计划 JSON 外自作主张输出“顺手执行了”的假动作，
            // 同时约束 Phase 5 反思仅做只读总结。
            if (planningPhase) {
                appendLine()
                appendLine("## Planning Phase (READ-ONLY)")
                appendLine("You are in the PLANNING phase of this task. Produce the requested plan JSON only.")
                appendLine("- Do NOT execute any tools, commands, or write operations in this phase.")
                appendLine("- Do NOT modify files, settings, or device state. Read-only reasoning only.")
                appendLine("- Output ONLY the JSON structure requested — no prose around it, no markdown fences.")
            }
            // 自定义模式：附加用户指令（拼入 system prompt）。
            if (config.mode == AgentMode.CUSTOM && !config.customInstruction.isNullOrBlank()) {
                appendLine()
                appendLine("## Custom Instructions")
                appendLine(config.customInstruction)
            }

            // ═══ 首轮纯问候硬约束（动态层，SmallTalkDetector 判定置位）═══
            // 与静态 Conversational Openness 段双保险：静态段约束所有闲聊的
            // 「形状」，本段针对「会话第一条消息就是你好」的极端情形给出
            // 不可误读的硬性行为规格（一句话 + 反问需要，禁止一切清单式输出）。
            if (firstTurnGreeting) {
                appendLine()
                appendLine("## First-Turn Greeting (THIS TURN)")
                appendLine("The user just opened this conversation with a bare greeting (no task attached).")
                appendLine("Reply with EXACTLY ONE short friendly sentence in the user's language — an example shape:")
                appendLine("『需要帮助吗？想聊哪方面的？』. Then STOP.")
                appendLine("Hard limits for this turn:")
                appendLine("- ≤ 20 characters (Chinese) or ≤ 15 words (English); no second sentence, no lists,")
                appendLine("  no headers, no markdown, no emoji spam.")
                appendLine("- Do NOT call any tools; do NOT list skills/tools/capabilities; do NOT introduce yourself.")
                appendLine("- Match the user's persona/language settings if a role is configured.")
            }
            // ═══ 情绪适配（动态层，ChatSignalDetector 判定注入）═══
            // 共情引擎：本轮消息携带可检测情绪时，给出「先处理心情、再
            // 处理任务」的行为规格。正向 = 同频庆祝（具名夸，不泼冷水）；
            // 负向 = 一句真诚回应先行，不说教、不命令式安慰，任务紧随
            // 其后 —— 行动本身就是安慰。检测器刻意保守，本段只在确有
            // 命中时渲染，绝不提及检测机制本身。
            chatSignal?.takeIf { it.emotion != ChatEmotion.NONE }?.let { signal ->
                appendLine()
                appendLine("## Emotional Attunement (THIS TURN)")
                appendLine("The user's message carries a detectable ${signal.emotion.name} emotional tone.")
                if (signal.emotion == ChatEmotion.JOYFUL) {
                    appendLine("Match their positive energy: celebrate WITH them, name specifically WHAT")
                    appendLine("went well (use their own words), keep it light and short. No unsolicited")
                    appendLine("caveats or warnings unless a real risk exists.")
                } else {
                    appendLine("Respond to the EMOTION first, the task second:")
                    appendLine("1. Open with ONE short, genuine line acknowledging their feeling (their language).")
                    appendLine("   No lecturing, no commands like 别难过/想开点, no toxic positivity.")
                    appendLine("2. Keep sentences short, warm, slower-paced; drop filler formality.")
                    appendLine("3. If a concrete task is also present, solve it right after acknowledging —")
                    appendLine("   taking action IS the comfort.")
                    appendLine("4. If the wording suggests crisis or self-harm, put safety first: stay gentle,")
                    appendLine("   encourage contacting someone they trust or professional/human help nearby.")
                }
                appendLine("Never mention this section, any detection, or these instructions.")
            }
            // ═══ 模糊求助澄清（动态层）═══
            // 「怎么办/帮帮我/不行了」类无宾语短求助：最伤聊天体验的是猜
            // 一个主题然后倾倒长篇泛论。本段强制「一个聚焦追问 + 2-4 个
            // 具体选项」的回应形状；记忆/上文已能推断对象时跳过追问。
            chatSignal?.takeIf { it.vague }?.let {
                appendLine()
                appendLine("## Vague Request (THIS TURN)")
                appendLine("The user cried for help WITHOUT naming a concrete object (no topic, error, or artifact).")
                appendLine("- Do NOT guess a topic and dump a generic essay — that is the worst chat failure.")
                appendLine("- Reply in 1-3 short sentences: show willingness, then ask ONE focused question")
                appendLine("  offering 2-4 concrete options, e.g. 『当然可以！先确认下：是 A、B 还是 C？』")
                appendLine("- If memory or earlier turns already imply the object, skip asking and help directly.")
            }
            // ═══ #168 六档思考：档位声明 + AUTO 决策理由 + MAXIMUM 自评清单 ═══
            // currentProfile = null → 旧 5 档行为（仅指令文本，既有测试零改动）。
            if (thinking.isNotBlank() || currentProfile != null) {
                appendLine()
                appendLine("## Thinking Instructions")
                currentProfile?.let { profile ->
                    appendLine("Current thinking level: ${profile.level.name}.")
                    profile.decisionReason?.let { reason ->
                        appendLine("Adaptive selection for this turn: $reason")
                    }
                }
                if (thinking.isNotBlank()) appendLine(thinking)
                // MAXIMUM 档：响应前自评清单（模型在产出最终回复前看到，真实影响本轮输出）
                if (currentProfile?.finalSelfCheck == true) {
                    appendLine(ThinkingProfile.SELF_CHECK_CHECKLIST)
                }
            }
            // 「函数调用」白名单：system prompt 工具清单与实际下发的 ToolDefinition 保持一致
            // Tool System v2：按类别分组 + 高风险 ⚠ 标记 —— 40+ 工具的字母序长列表
            // 对模型只是噪音；分组清单让模型更快定位"这类任务该用哪类工具"。
            appendLine()
            if (toolsUnavailable) {
                appendLine("## Tools Unavailable This Turn")
                appendLine("The provider rejected the tool payload for this request. Answer directly")
                appendLine("in plain text; do not attempt tool calls this turn.")
            } else {
            appendLine("## Available Tools (${visibleTools.size})")
            val byCategory = visibleTools.groupBy { it.metadata.category }
                .toSortedMap(compareBy { it.order })
            byCategory.forEach { (category, tools) ->
                appendLine("### ${category.label} (${tools.size})")
                tools.sortedBy { it.id }.forEach { tool ->
                    val firstLine = tool.description
                        .lineSequence()
                        .firstOrNull()
                        ?.trim()
                        ?.take(160)
                        ?: ""
                    val riskMark = if (tool.metadata.risk == ToolRisk.HIGH) " ⚠️HIGH-RISK" else ""
                    // v4：展示 provider 名（与请求 tools 数组同名）——模型看到什么
                    // 名字就调用什么名字，与函数 schema 零歧义。
                    val callName = toolNameMap[tool.id] ?: tool.id
                    appendLine("- $callName: $firstLine$riskMark")
                }
            }
            if (visibleTools.any { it.metadata.risk == ToolRisk.HIGH }) {
                appendLine()
                appendLine("Tools marked ⚠️HIGH-RISK are destructive or irreversible. The user will be")
                appendLine("asked to confirm before their first execution this session; after a denial,")
                appendLine("do NOT retry the same tool — propose an alternative approach instead.")
            }

            // ═══ Tool System v4：强制函数调用（「调用函数」选中集）═══
            if (config.forcedToolIds.isNotEmpty()) {
                appendLine()
                appendLine("## Forced Function Calls")
                appendLine("The user pinned specific functions for this task; ONLY those are exposed")
                appendLine("and tool_choice is set to force their invocation. You MUST call:")
                config.forcedToolIds.sorted().forEach { id ->
                    val name = toolNameMap[id] ?: id
                    appendLine("- $name")
                }
                appendLine("Call them as required by the task; do not answer without using them")
                appendLine("unless they error out.")
            }

            // ═══ Tool System v4：工具目录（渐进披露——超过 CORE 集的能力在此检索）═══
            if (catalogTools.isNotEmpty()) {
                val visibleIds = visibleTools.map { it.id }.toSet()
                val notLoaded = catalogTools.count { it.id !in visibleIds }
                if (notLoaded > 0) {
                    appendLine()
                    appendLine("## Tool Catalog ($notLoaded more available)")
                    appendLine("Beyond the tools above, ${notLoaded} more capabilities are installed")
                    appendLine("but not pre-loaded (keeps this request fast and small). Discover them:")
                    appendLine("- ToolSearch(query) — find tools by keywords (e.g. 'github issue', 'browser')")
                    appendLine("- ToolOpen(tool_name) — load a tool; it becomes callable on your next turn")
                    appendLine("- ToolList() — category overview with counts")
                    val byCat = catalogTools.groupBy { it.metadata.category }
                        .toSortedMap(compareBy { it.order })
                    appendLine("Categories: " + byCat.entries.joinToString(" / ") { (cat, list) ->
                        "${cat.label} ${list.size}"
                    })
                }
            }
            }

            // ═══ 技能目录（渐进披露）：一行摘要 + 装载指引 ═══
            // 目录与激活分层：目录让模型知道「有哪些领域方法论」，激活集的
            // 全文注入由 skillPrompts 携带（见 Active Skills 段）。模型用
            // SkillActivate 装载，宿主也可经斜杠指令/自动装备预激活。
            if (skillCatalog.isNotEmpty()) {
                appendLine()
                appendLine("## Skill Catalog (${skillCatalog.size} installed)")
                appendLine("Domain expert methodologies are installed but NOT loaded by default (keeps requests small).")
                appendLine("When the task matches a domain below, call SkillActivate(skill_id) FIRST — the full")
                appendLine("methodology loads instantly and stays for the session. Activated skills appear under")
                appendLine("'Active Skills'.")
                skillCatalog.forEach { digest ->
                    appendLine("- ${digest.id}: ${digest.name} — ${digest.summary}")
                }
                appendLine("Also call SkillList() for the full inventory with versions/status.")
            }

            // Skill prompt 注入（渐进披露：仅激活技能的全文；空目录 = legacy 全量）
            if (skillPrompts.isNotEmpty()) {
                appendLine()
                appendLine("## Active Skills")
                skillPrompts.forEach { prompt ->
                    appendLine(prompt)
                    appendLine()
                }
            }

            // 会话级动态上下文（当前时间 / 用户规则 / 结构化输出 / 联网搜索指令等），
            // 由 UI 层在每次发送前组装，任意模式下生效。
            if (config.additionalSystemContext.isNotBlank()) {
                appendLine()
                appendLine("## Session Context")
                appendLine(config.additionalSystemContext.trim())
            }

            // ═══ 聊天记忆召回（对话自动记忆的注入面）═══
            // 位置紧跟 Session Context：记忆是对「这个用户」的先验，先于
            // 通用规则生效。内容已由实现方格式化（实体 + 观察列表），
            // 这里只包一层段标题与使用指引。
            if (!memoryContext.isNullOrBlank()) {
                appendLine()
                appendLine("## Remembered About You")
                appendLine("Long-term memories distilled from past conversations (auto-captured, newest first):")
                appendLine(memoryContext.trim())
                appendLine("Use these naturally — do not re-ask what is already known; if a memory looks")
                appendLine("outdated, confirm gently instead of silently trusting it.")
            }

            // ═══ 全局行为规则（#164 Rules 系统）═══
            // 位置紧跟 Session Context（规则是对当前会话行为的约束，
            // 先于通用策略段落）；内容原样注入（用户预期：写了什么就是什么）。
            // coding 模式经 additionalSystemContext 注入时此处恒为空（防双注，
            // 见函数 KDoc 接线契约）。
            if (globalRules.isNotBlank()) {
                appendLine()
                appendLine("## Global Rules")
                appendLine(globalRules.trim())
            }
            appendLine()
            appendLine("## File Operation Strategy")
            appendLine("1. DISCOVER: Use GlobFiles or ListFiles to find relevant files")
            appendLine("2. UNDERSTAND: Use Read (first 80 lines) to see structure")
            appendLine("3. LOCATE: Use SearchFiles to find specific code/config")
            appendLine("4. READ: Use Read with 'around' to see target area")
            appendLine("5. EDIT: Use Edit with search-replace (never blind overwrite)")
            appendLine("6. VERIFY: Use Read again to confirm changes are correct")
            appendLine()
            appendLine("## Output Management")
            appendLine("- All tools limit output. Truncation markers — \"[... N chars omitted ...]\",")
            appendLine("  \"[... truncated at N/M chars]\", \"[... truncated at N chars — the body CONTINUES ...]\",")
            appendLine("  \"[OUTPUT TRUNCATED: ...]\" — mean MORE content exists: the output is INCOMPLETE.")
            appendLine("  NEVER treat a marker-terminated output as the full result or conclude from it —")
            appendLine("  narrow the query (offset / page / head / grep / max_chars) or paginate to read the rest first.")
            appendLine("- For large outputs, use pagination (offset, page, scroll)")
            appendLine("- Prefer targeted queries over broad ones")
            appendLine("- Use shell pipes (| head, | grep, | tail) to pre-filter")
            appendLine()
            appendLine("## Rules")
            appendLine("- Use the most appropriate tool for each task (prefer specific tools over raw shell).")
            appendLine("- Always verify command output before proceeding.")
            appendLine("- If a command fails, analyze the error and try an alternative approach.")
            appendLine("- Keep prose concise; let tool output speak for itself. Actions speak louder than plans.")
            appendLine("- When the user asks a capability question ('can you do X?'), verify by attempting it with tools")
            appendLine("  (or checking state) rather than answering from memory.")
            appendLine("- Use AskUserChoice when the task is ambiguous, multiple targets/actions exist, an action is risky or irreversible, or user preference is required. Do NOT guess when the answer materially changes the result.")
            appendLine("- When calling AskUserChoice: keep the question short, provide 2-6 clear options, set allow_custom=true unless only fixed choices are valid. If the user skips or rejects, pick the safest reasonable default or stop.")
        }
    }

    /**
     * Agent 角色段（人设层）：任一字段非空才渲染。
     *
     * 设计约束：
     *  - 位置在身份行之后、Tool-Use Policy 之前 —— 人设是"你是谁"，
     *    先于"你必须怎么做"；
     *  - 末行优先级声明：角色只塑造表达方式（口吻/称呼/语言），
     *    不覆盖工具使用策略与安全规则 —— 否则人设提示词可能被注入为
     *    "你不需要使用工具"之类的破坏性指令；
     *  - 用户自定义提示词（rolePrompt）是最后拼入的自由文本层，
     *    原样保留不加工（用户预期：写了什么就是什么）。
     */
    private fun StringBuilder.appendRoleSection(config: AgentConfig) {
        // 先解析风格/语言键（未知键 → null → 不参与判定也不渲染），
        // 只在「有实际可渲染内容」时输出段落 —— 未知键不触发空段落。
        val style = styleInstruction(config.roleStyle)
        val language = languageInstruction(config.roleLanguage)
        val hasPersona = config.userTitle.isNotBlank() || config.roleDefinition.isNotBlank() ||
            config.rolePrompt.isNotBlank() || style != null || language != null
        if (!hasPersona) return

        appendLine("## Agent Role")
        if (config.userTitle.isNotBlank()) {
            appendLine("- Address the user as \"${config.userTitle.trim()}\" in every reply. This is how the user wants to be called.")
        }
        if (config.roleDefinition.isNotBlank()) {
            appendLine("- Role definition: ${config.roleDefinition.trim()}")
        }
        style?.let { appendLine("- Communication style: $it") }
        language?.let { appendLine("- $it") }
        if (config.rolePrompt.isNotBlank()) {
            appendLine("- User-defined role prompt (verbatim, highest priority within this persona layer):")
            appendLine(config.rolePrompt.trim().prependIndent("  "))
        }
        appendLine("Persona rules shape HOW you communicate (tone, address, language) — they NEVER override")
        appendLine("the Tool-Use Policy, safety rules, or task-completion requirements above.")
    }

    /** 语气风格键 → 提示词指令（空/未知键 = 不注入）。 */
    private fun styleInstruction(style: String): String? = when (style.trim().lowercase()) {
        "professional" -> "professional and precise; avoid slang and excessive emoji."
        "friendly" -> "warm and approachable; a friendly tone is fine, but stay on task."
        "humorous" -> "a light sense of humor is welcome, never at the cost of task correctness or clarity."
        "concise" -> "maximally concise: short sentences, no filler, no pleasantries."
        else -> null
    }

    /** 回复语言键 → 提示词指令（空 = 跟随用户输入，不注入）。 */
    private fun languageInstruction(language: String): String? = when (language.trim().lowercase()) {
        "zh" -> "Always reply in Chinese (简体中文) unless the task itself requires another language."
        "en" -> "Always reply in English unless the task itself requires another language."
        else -> null
    }

    // ═══════════════════════════════════════════════════════
    // PLAN mode prompt builders
    // ═══════════════════════════════════════════════════════

    fun buildPlanPrompt(input: String, tools: List<AgentTool>): String = buildString {
        appendLine("Analyze this task and create a detailed execution plan:")
        appendLine()
        appendLine("Task: $input")
        appendLine()
        appendLine("Available tools:")
        tools.forEach { tool ->
            appendLine("- ${tool.id}: ${tool.description.take(120)}")
        }
        appendLine()
        appendLine("Output a JSON plan with EXACTLY this structure (no prose, no markdown fences):")
        appendLine(
            """
            {
              "goal": "<one-sentence goal>",
              "reasoning": "<why this approach>",
              "risk_level": "low|medium|high|critical",
              "estimated_tool_calls": <int>,
              "steps": [
                {
                  "index": 0,
                  "description": "<what this step does>",
                  "tool": "<tool_id or null>",
                  "estimated_args": "<rough args as string, may be null>",
                  "depends_on": []
                }
              ]
            }
            """.trimIndent()
        )
    }

    fun buildStepExecutionPrompt(
        plan: ExecutionPlan,
        step: PlanStep,
        stepIndex: Int
    ): String = buildString {
        appendLine("Execute step ${stepIndex + 1} of the plan:")
        appendLine("Step: ${step.description}")
        step.toolName?.let { appendLine("Suggested tool: $it") }
        step.estimatedArgs?.let { appendLine("Suggested args: $it") }
        appendLine()
        appendLine("Full plan context:")
        plan.steps.forEach { s ->
            val marker = if (s.index == stepIndex) "→ " else "  "
            appendLine("$marker${s.index + 1}. ${s.description}")
        }
        appendLine()
        appendLine("Execute this step now using the appropriate tool. Be concise.")
    }

    fun buildReflectionPrompt(plan: ExecutionPlan): String = buildString {
        appendLine("The following plan has been executed:")
        appendLine("Goal: ${plan.goal}")
        plan.steps.forEach { step ->
            appendLine("  ${step.index + 1}. ${step.description}")
        }
        appendLine()
        appendLine(
            "State the final outcome and key deliverables directly in 2-4 sentences. " +
                "Do NOT announce completion (no phrases like 'task complete', '任务已完成', " +
                "'all done'), and no closing pleasantries. Note any issues, partial " +
                "completions, or follow-ups the user should know about."
        )
    }

    // ═══════════════════════════════════════════════════════
    // SPEC mode prompt builders
    // ═══════════════════════════════════════════════════════

    fun buildSpecPrompt(input: String, tools: List<AgentTool>): String = buildString {
        appendLine("Analyze this task and create a detailed requirement specification:")
        appendLine()
        appendLine("Task: $input")
        appendLine()
        appendLine("Available tools:")
        tools.forEach { tool ->
            appendLine("- ${tool.id}: ${tool.description.take(120)}")
        }
        appendLine()
        appendLine("Output a JSON spec with EXACTLY this structure (no prose, no markdown fences):")
        appendLine(
            """
            {
              "goal": "<one-sentence goal>",
              "reasoning": "<why this approach / key design decisions>",
              "risk_level": "low|medium|high|critical",
              "estimated_tool_calls": <int>,
              "requirements": ["<functional requirement 1>", "..."],
              "constraints": ["<constraint 1>", "..."],
              "acceptance_criteria": ["<how to verify success 1>", "..."],
              "deliverables": ["<concrete deliverable 1>", "..."]
            }
            """.trimIndent()
        )
        appendLine()
        appendLine("Be specific: each requirement/criterion must be verifiable. Empty arrays are allowed but avoid them when possible.")
    }

    fun buildSpecStepPrompt(
        spec: ExecutionSpec,
        stepText: String,
        stepIndex: Int
    ): String = buildString {
        appendLine("Execute deliverable ${stepIndex + 1} of the spec:")
        appendLine("Deliverable: $stepText")
        appendLine()
        appendLine("Full spec context:")
        appendLine("Goal: ${spec.goal}")
        if (spec.requirements.isNotEmpty()) {
            appendLine("Requirements:")
            spec.requirements.forEach { appendLine("  - $it") }
        }
        if (spec.constraints.isNotEmpty()) {
            appendLine("Constraints:")
            spec.constraints.forEach { appendLine("  - $it") }
        }
        if (spec.acceptanceCriteria.isNotEmpty()) {
            appendLine("Acceptance criteria:")
            spec.acceptanceCriteria.forEach { appendLine("  - $it") }
        }
        appendLine()
        appendLine("Deliver this item now using the appropriate tools. Verify against the acceptance criteria. Be concise.")
    }

    fun buildSpecReflectionPrompt(spec: ExecutionSpec): String = buildString {
        appendLine("The following spec has been executed:")
        appendLine("Goal: ${spec.goal}")
        spec.deliverables.forEachIndexed { index, d ->
            appendLine("  ${index + 1}. $d")
        }
        appendLine()
        appendLine(
            "State the final outcome and key deliverables directly in 2-4 sentences. " +
                "Do NOT announce completion (no phrases like 'task complete', '任务已完成', " +
                "'all done'), and no closing pleasantries. Report any unmet acceptance " +
                "criteria, issues, or follow-ups the user should know about."
        )
    }

    // ═══════════════════════════════════════════════════════
    // Reflection mode prompt builders
    // ═══════════════════════════════════════════════════════

    fun buildReviewPrompt(draft: String): String = buildString {
        appendLine("You are a strict reviewer. Critically evaluate the following draft answer:")
        appendLine()
        appendLine("--- DRAFT START ---")
        appendLine(draft)
        appendLine("--- DRAFT END ---")
        appendLine()
        appendLine(
            "Check for: factual errors, logical gaps, incomplete steps, unclear or ambiguous " +
                "wording, missing edge cases, and deviations from the user's request. " +
                "Output ONLY the review: a concise list of concrete, actionable issues " +
                "(max 6 items). Do not rewrite the answer here."
        )
    }

    fun buildRevisePrompt(draft: String, review: String, round: Int): String = buildString {
        appendLine("Revise the draft answer below to address the reviewer's issues.")
        appendLine("Round $round revision.")
        appendLine()
        appendLine("--- DRAFT START ---")
        appendLine(draft)
        appendLine("--- DRAFT END ---")
        appendLine()
        appendLine("--- REVIEW START ---")
        appendLine(review)
        appendLine("--- REVIEW END ---")
        appendLine()
        appendLine(
            "Output ONLY the final revised answer (complete, self-contained, no meta commentary). " +
                "Fix every actionable issue in the review while preserving what was already good."
        )
    }
}
