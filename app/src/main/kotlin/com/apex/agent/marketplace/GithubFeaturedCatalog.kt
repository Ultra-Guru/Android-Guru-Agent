package com.apex.agent.marketplace

/**
 * # GitHub Featured Catalog — GitHub 精选 coding 技能专栏
 *
 * 市场 · Skills 页签的第四个仓库源（[SkillRepoSource.GITHUB]）：
 * 从真实 GitHub 顶级开源仓库里精选的 coding 相关技能，一键安装。
 *
 * ## 入选纪律
 *
 * - **只收录真实存在、可验证的开源仓库**（owner/repo/subdir 均逐一核实过
 *   SKILL.md 与 YAML frontmatter）；
 * - 顶级仓库优先：anthropics/skills（Anthropic 官方 Agent Skills 仓库）、
 *   obra/superpowers（软件开发方法论技能框架）；
 * - 全部为 coding 工位技能（scope 固定 "coding"）—— 只出现在 Coding 市场
 *   与 Coding 屏 / 菜单，与 Agent 分级完全隔离。
 *
 * ## 安装管线（零新安装代码）
 *
 * 条目 → [MarketInstallManager.GithubRepoCandidate]（raw SKILL.md 直链）→
 * 既有 [MarketInstallManager.installGithubCandidate]：下载 SKILL.md →
 * frontmatter 严格校验（name 必填）→ prompt 型 apex-skill-v1 manifest
 * （scope 盖章 "coding"）→ 安装确认门禁 → 安装即装备。安装 id 与
 * 「智能安装整个仓库再逐个挑选」的口径一致（gh-owner-repo-subdir 归一化），
 * 两条路互不去重冲突。
 *
 * star 数为收录时快照（展示用，不参与安装逻辑）。
 */
object GithubFeaturedCatalog {

    /** 一条精选技能条目（纯数据；安装换算见 [toCandidate]）。 */
    data class FeaturedSkill(
        /** 仓库归属（如 anthropics / obra）。 */
        val owner: String,
        /** 仓库名（如 skills / superpowers）。 */
        val repo: String,
        /** 仓库默认分支（raw 直链用）。 */
        val branch: String,
        /** SKILL.md 所在子目录（仓库相对路径，如 skills/mcp-builder）。 */
        val subdir: String,
        /** 展示名（与 SKILL.md frontmatter name 一致）。 */
        val name: String,
        /** 一行描述（中文，市场卡片副标题口径）。 */
        val description: String,
        /** 仓库级 star 数快照标签（如 "181k"）。 */
        val starsLabel: String
    ) {
        /** raw SKILL.md 绝对直链。 */
        val skillMarkdownUrl: String =
            "https://raw.githubusercontent.com/$owner/$repo/$branch/$subdir/SKILL.md"

        /** 仓库主页（卡片外链）。 */
        val homepage: String = "https://github.com/$owner/$repo"

        /** 安装 id（与 installSmartFromGithub 的 gh-owner-repo-subdir 归一化同口径）。 */
        val installId: String =
            "gh-${owner.lowercase()}-${repo.lowercase()}-${subdir.lowercase().replace('/', '-')}"

        /** 行 key（LazyColumn 稳定 key / 去重）。 */
        val key: String = "featured/$installId"
    }

    /** 精选清单（收录时逐一核实过仓库结构、SKILL.md 与 frontmatter）。 */
    val SKILLS: List<FeaturedSkill> = listOf(
        // ── anthropics/skills（Anthropic 官方 Agent Skills 仓库）──
        FeaturedSkill(
            owner = "anthropics", repo = "skills", branch = "main",
            subdir = "skills/mcp-builder", name = "mcp-builder",
            description = "构建高质量 MCP 服务器（Model Context Protocol）：工具设计规范、Python/Node 双栈实现指南",
            starsLabel = "181k"
        ),
        FeaturedSkill(
            owner = "anthropics", repo = "skills", branch = "main",
            subdir = "skills/web-artifacts-builder", name = "web-artifacts-builder",
            description = "用 React + Tailwind + shadcn/ui 构建多组件复杂 Web 工件（状态管理 / 路由 / 组件库）",
            starsLabel = "181k"
        ),
        FeaturedSkill(
            owner = "anthropics", repo = "skills", branch = "main",
            subdir = "skills/webapp-testing", name = "webapp-testing",
            description = "Playwright 驱动的 Web 应用测试：前端功能验证、UI 调试、截图与浏览器日志",
            starsLabel = "181k"
        ),
        FeaturedSkill(
            owner = "anthropics", repo = "skills", branch = "main",
            subdir = "skills/frontend-design", name = "frontend-design",
            description = "前端设计：现代 Web 界面美学与可访问性实践",
            starsLabel = "181k"
        ),
        FeaturedSkill(
            owner = "anthropics", repo = "skills", branch = "main",
            subdir = "skills/algorithmic-art", name = "algorithmic-art",
            description = "p5.js 生成艺术：种子随机、参数探索与生成系统（艺术哲学 → 代码表达）",
            starsLabel = "181k"
        ),
        FeaturedSkill(
            owner = "anthropics", repo = "skills", branch = "main",
            subdir = "skills/canvas-design", name = "canvas-design",
            description = "海报 / 信息图等静态视觉设计：设计哲学驱动的 PNG/PDF 出图",
            starsLabel = "181k"
        ),
        FeaturedSkill(
            owner = "anthropics", repo = "skills", branch = "main",
            subdir = "skills/skill-creator", name = "skill-creator",
            description = "创建 / 改进 / 评测技能本身：从零写技能、优化触发描述、跑基准测试",
            starsLabel = "181k"
        ),
        FeaturedSkill(
            owner = "anthropics", repo = "skills", branch = "main",
            subdir = "skills/docx", name = "docx",
            description = "Word 文档工程：创建 / 读取 / 编辑 .docx（目录、页眉页脚、批注、修订）",
            starsLabel = "181k"
        ),
        FeaturedSkill(
            owner = "anthropics", repo = "skills", branch = "main",
            subdir = "skills/pptx", name = "pptx",
            description = "PPT 演示文稿工程：创建 / 解析 / 编辑 .pptx（模板、版式、演讲者备注）",
            starsLabel = "181k"
        ),
        FeaturedSkill(
            owner = "anthropics", repo = "skills", branch = "main",
            subdir = "skills/xlsx", name = "xlsx",
            description = "电子表格工程：.xlsx/.csv 读写、公式、图表与数据清洗",
            starsLabel = "181k"
        ),
        FeaturedSkill(
            owner = "anthropics", repo = "skills", branch = "main",
            subdir = "skills/pdf", name = "pdf",
            description = "PDF 文档工程：生成、表单填写、拆分合并与内容提取",
            starsLabel = "181k"
        ),
        // ── obra/superpowers（软件开发方法论技能框架）──
        FeaturedSkill(
            owner = "obra", repo = "superpowers", branch = "main",
            subdir = "skills/test-driven-development", name = "test-driven-development",
            description = "测试驱动开发：实现任何功能 / 修复前先写测试的方法论",
            starsLabel = "300k"
        ),
        FeaturedSkill(
            owner = "obra", repo = "superpowers", branch = "main",
            subdir = "skills/systematic-debugging", name = "systematic-debugging",
            description = "系统化调试：遇到 bug / 测试失败 / 意外行为时，先诊断根因再动手修",
            starsLabel = "300k"
        ),
        FeaturedSkill(
            owner = "obra", repo = "superpowers", branch = "main",
            subdir = "skills/writing-plans", name = "writing-plans",
            description = "实施计划编写：多步任务的规格 → 计划拆解，动代码之前先想清楚",
            starsLabel = "300k"
        ),
        FeaturedSkill(
            owner = "obra", repo = "superpowers", branch = "main",
            subdir = "skills/executing-plans", name = "executing-plans",
            description = "计划执行：按既定实施计划逐步落地并保持验证纪律",
            starsLabel = "300k"
        ),
        FeaturedSkill(
            owner = "obra", repo = "superpowers", branch = "main",
            subdir = "skills/verification-before-completion", name = "verification-before-completion",
            description = "完成前验证：宣称「修好了 / 过了」之前必须跑命令看真实输出 —— 证据先于断言",
            starsLabel = "300k"
        ),
        FeaturedSkill(
            owner = "obra", repo = "superpowers", branch = "main",
            subdir = "skills/requesting-code-review", name = "requesting-code-review",
            description = "发起代码评审：完成任务 / 大特性 / 合并前验证工作满足要求",
            starsLabel = "300k"
        ),
        FeaturedSkill(
            owner = "obra", repo = "superpowers", branch = "main",
            subdir = "skills/dispatching-parallel-agents", name = "dispatching-parallel-agents",
            description = "并行子代理分发：2 个以上无共享状态的独立任务的隔离执行策略",
            starsLabel = "300k"
        ),
        FeaturedSkill(
            owner = "obra", repo = "superpowers", branch = "main",
            subdir = "skills/using-git-worktrees", name = "using-git-worktrees",
            description = "Git worktree 隔离：特性开发与当前工作区隔离的原生工作流",
            starsLabel = "300k"
        )
    )

    /** 已安装的精选条目（按 installId 匹配市场已装快照）。 */
    fun installedIds(): Set<String> = SKILLS.map { it.installId }.toSet()

    /** 精选条目 → 既有 GitHub 候选安装管线（scope 盖章 coding）。 */
    fun toCandidate(entry: FeaturedSkill): MarketInstallManager.GithubRepoCandidate =
        MarketInstallManager.GithubRepoCandidate(
            kind = MarketInstallManager.GithubRepoCandidate.Kind.SKILL,
            name = entry.name,
            description = entry.description,
            skillId = entry.installId,
            skillMarkdownUrl = entry.skillMarkdownUrl,
            skillOwner = entry.owner,
            scope = "coding"
        )
}
