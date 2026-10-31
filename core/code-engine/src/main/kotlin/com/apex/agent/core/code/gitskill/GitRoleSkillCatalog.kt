package com.apex.agent.core.code.gitskill

/**
 * # Git 角色专属技能目录（9 套，阶段化 runbook 全量定义）
 *
 * 用户给出的社区技能源（上游归属逐一标注在 [GitRoleSkill.sourceRepo]），
 * 本目录把它们**代码化**为阶段化 runbook：每套技能 = 触发词表 + 阶段
 * （命令模板 + 验证方式）+ 安全护栏 + 适用判据 —— 模型按任务关键词
 * 就近选用，按阶段执行并逐阶段自验。
 *
 * ## 目录总览（9 套）
 *
 * | # | id | 能力域 | 上游 |
 * |---|----|--------|------|
 * | 1 | git-advanced-workflows | 分支策略/变基/救援 | sickn33/antigravity-awesome-skills |
 * | 2 | git-advanced | 交互式变基/冲突解决 | AutumnsGrove/ClaudeSkills (git-advanced) |
 * | 3 | git-workflows | Conventional Commits/agent-safe 流程 | git-master 语义 |
 * | 4 | github-actions-advanced | 生产级 CI/CD 加固 | 社区同名技能（多维护版本语义） |
 * | 5 | sync-repos | 跨仓库同步（保作者） | MilkClouds/my-dev-playbook |
 * | 6 | relay-merge | 推送受阻 Gist 中继 | dEitY719/gh-flow-skills |
 * | 7 | gh-cherry-pick | GitHub API 跨仓挑拣 | gh-cherry-pick（PyPI 工具语义） |
 * | 8 | github-multi-repo | 组织级多仓同步 | DreamLab-AI/agentbox |
 * | 9 | github-release-management | 紧急补丁/热修发布编排 | 社区同名技能语义 |
 *
 * 维度纪律：单文件 ≤ 1200 行门禁 —— 本文件承载 1-4（本地工作流域），
 * 5-9（跨仓库/发布域）在 [GitRoleSkillCatalogCrossRepo]。
 */
object GitRoleSkillCatalog {

    /** Git 内置角色 id（技能归属判据之一）。 */
    const val GIT_ROLE_ID = "builtin_coding_git"

    /**
     * Git 角色定义段的特征前缀（复制「另存为」的自定义角色会原样携带
     * roleDefinition —— 前缀匹配让技能跟随副本，硬编码 id 之外的第二判据）。
     */
    const val GIT_ROLE_DEFINITION_PREFIX = "Git power user"

    // ═══ 1. git-advanced-workflows ═══

    private val ADVANCED_WORKFLOWS = GitRoleSkill(
        id = "git-advanced-workflows",
        name = "Git 高级工作流",
        emoji = "🌀",
        summary = "分支策略 · 交互式变基 · reflog 救援 · bisect 二分定位",
        description = "面向单仓库的深水区 Git 操作：分支模型选择（短命特性分支/" +
            "长命集成分支）、交互式变基重写本地历史、reflog 找回\"丢失\"提交、" +
            "bisect 二分定位回归提交。管到本地与单人远程；跨仓库同步交给 " +
            "sync-repos / gh-cherry-pick。",
        sourceRepo = "https://github.com/sickn33/antigravity-awesome-skills",
        sourceNote = "安装形态：npx skills add sickn33/antigravity-awesome-skills --skill git-advanced-workflows",
        triggers = listOf(
            "rebase", "变基", "interactive rebase", "reflog", "bisect", "二分",
            "分支策略", "branching", "squash", "压缩提交", "丢失的提交", "找回提交",
            "重写历史", "rewrite history", "cherry-pick 本地", "merge conflict"
        ),
        phases = listOf(
            GitSkillPhase(
                name = "侦察",
                goal = "先看清楚再动手：当前分支/状态/提交图形状",
                commands = listOf(
                    "git status",
                    "git log --oneline --graph --decorate -20",
                    "git branch -vv"
                ),
                verify = "能口述当前分支基点与脏区清单；有未提交改动先 stash 或提交"
            ),
            GitSkillPhase(
                name = "分支决策",
                goal = "按改动性质选分支模型，短命特性分支优先",
                commands = listOf(
                    "git switch -c feat/<topic>",
                    "git merge-base main HEAD"
                ),
                verify = "git log --oneline main..HEAD 条数 ≤ 逻辑单元数；一条特性分支只装一件事"
            ),
            GitSkillPhase(
                name = "变基整形",
                goal = "交互式变基：squash 碎提交、reorder 逻辑、改写消息",
                commands = listOf(
                    "git rebase -i <upstream>",
                    "git rebase --onto <new-base> <old-base>..<branch>"
                ),
                verify = "git log --oneline 重放后线性且无重复；冲突解决后 git rebase --continue 走完"
            ),
            GitSkillPhase(
                name = "救援",
                goal = "reflog 找回任何\"丢失\"状态；bisect 定位回归",
                commands = listOf(
                    "git reflog --date=iso",
                    "git branch rescue <reflog-sha>",
                    "git bisect start && git bisect bad HEAD && git bisect good <known-good>"
                ),
                verify = "rescue 分支上能看到目标提交；bisect 收敛到首个 bad 提交后 git bisect reset"
            )
        ),
        guards = listOf(
            "绝不改写已推送到共享分支的历史（rebase 只作用于本地未推送提交）",
            "变基前先 git branch backup-<name> 打备份分支（救援成本为零）",
            "bisect 涉及构建脚本的自动化判定时，先确认脚本能区分 good/bad"
        ),
        whenToUse = "任务含本地历史整形/提交找回/回归定位时首选；纯提交纪律问题转 git-workflows；跨仓库转 sync-repos。"
    )

    // ═══ 2. git-advanced（交互式变基/冲突解决专项）═══

    private val GIT_ADVANCED = GitRoleSkill(
        id = "git-advanced",
        name = "变基与冲突解决",
        emoji = "🪢",
        summary = "交互式变基全流程 · 三方合并心智模型 · 冲突分型与收敛",
        description = "git-advanced（ClaudeSkills）的冲突解决专项深化：把冲突拆成" +
            "内容冲突/结构冲突/语义冲突三型，分别给收敛策略；变基过程中的" +
            "冲突逐提交解决而非一把梭。是 git-advanced-workflows 的冲突深钻件。",
        sourceRepo = "https://github.com/AutumnsGrove/ClaudeSkills",
        sourceNote = "技能路径：tree/main/git-advanced（ClaudeSkills 技能包）",
        triggers = listOf(
            "冲突", "conflict", "merge conflict", "解决冲突", "rebase 冲突",
            "both modified", "variant", "三方合并", "three-way", "ours theirs"
        ),
        phases = listOf(
            GitSkillPhase(
                name = "冲突盘点",
                goal = "列出全部冲突文件并分型（内容/结构/语义）",
                commands = listOf(
                    "git status --short | grep -E '^(UU|AA|DD)'",
                    "git diff --name-only --diff-filter=U"
                ),
                verify = "冲突文件清单非空且每文件已标型；无冲突则直接 --continue"
            ),
            GitSkillPhase(
                name = "内容冲突",
                goal = "逐 hunk 读三方（base/ours/theirs）后手改，不盲选一边",
                commands = listOf(
                    "git checkout --conflict=diff3 <file>",
                    "git diff <file>"
                ),
                verify = "文件内无 <<<<<<< 标记；改动同时保留两边语义而非覆盖一边"
            ),
            GitSkillPhase(
                name = "结构冲突",
                goal = "一方删一方改/重命名冲突：先判定哪边是意图",
                commands = listOf(
                    "git log --oneline --follow <file>",
                    "git rm <file>  # 确认删除是意图时",
                    "git checkout --theirs <renamed>"
                ),
                verify = "结构决策有提交证据支撑（log 溯源），不是猜的"
            ),
            GitSkillPhase(
                name = "收敛收尾",
                goal = "标记解决并完成合并/变基，验证结果完整性",
                commands = listOf(
                    "git add <resolved-files>",
                    "git rebase --continue  # 或 git commit 结束 merge",
                    "git log --oneline --graph -5"
                ),
                verify = "rebase/merge 完成无残留状态；工作树干净；关键文件编译/测试通过"
            )
        ),
        guards = listOf(
            "语义冲突（两边各自改了同域逻辑）必须人工审读合并结果，禁止 git checkout --ours/--theirs 一键了事",
            "一次变基里多个冲突提交要逐提交解决，不允许反复 abort 重来超过 3 次而不换策略"
        ),
        whenToUse = "任何出现 conflict/冲突字样的任务；变基过程中的中断恢复；与 git-advanced-workflows 配套使用。"
    )

    // ═══ 3. git-workflows（提交纪律 / agent-safe 流程）═══

    private val GIT_WORKFLOWS = GitRoleSkill(
        id = "git-workflows",
        name = "提交纪律与安全流程",
        emoji = "🧭",
        summary = "Conventional Commits · 原子提交 · agent-safe 只读先行",
        description = "git-master / git-workflows 语义：Conventional Commits 规范、" +
            "原子提交边界（一提交一逻辑变更）、agent-safe 操作纪律（非破坏性" +
            "侦察先行，破坏性操作必须显式批准）。是其余 8 套技能的地基 —— " +
            "任何技能动历史前都先过这里的纪律关卡。",
        sourceRepo = null,
        sourceNote = "git-master / git-workflows 社区语义（Conventional Commits + agent-safe 流程），本仓原生实现",
        triggers = listOf(
            "commit", "提交信息", "提交规范", "conventional", "原子提交",
            "atomic commit", "staging", "暂存", "写提交", "整理提交",
            "git 安全", "agent-safe", "pre-commit", "hook"
        ),
        phases = listOf(
            GitSkillPhase(
                name = "只读侦察",
                goal = "agent-safe 第一律：状态/差异/日志全看清，不做任何变更",
                commands = listOf(
                    "git status",
                    "git diff --stat",
                    "git diff --cached --stat"
                ),
                verify = "能口述：改了哪些文件、暂存区与工作树各是什么、相对基线几个提交"
            ),
            GitSkillPhase(
                name = "原子分组",
                goal = "按逻辑变更切分暂存区，一个提交只装一件事",
                commands = listOf(
                    "git add -p <file>",
                    "git stash push -- <unrelated-files>"
                ),
                verify = "每个待提交组能写出一行型摘要（写不出 = 组太大，继续拆）"
            ),
            GitSkillPhase(
                name = "规范提交",
                goal = "Conventional Commits：类型(范围): 摘要 —— 关键词",
                commands = listOf(
                    "git commit -m \"feat(<scope>): <summary> —— <keyword>\"",
                    "git log --oneline -3"
                ),
                verify = "类型 ∈ feat/fix/refactor/docs/test/chore/ci；摘要 ≤ 72 字符单行；消息能独立回答\"为什么改\""
            ),
            GitSkillPhase(
                name = "破坏性关卡",
                goal = "push -f / reset --hard / branch -D 前必须显式确认",
                commands = listOf(
                    "git push --force-with-lease  # 替代裸 -f（有保护）",
                    "git reflog -5  # 破坏性操作前的逃生锚点"
                ),
                verify = "破坏性命令执行前已向用户列出影响面并获批；--force-with-lease 而非 --force"
            )
        ),
        guards = listOf(
            "非破坏性侦察（status/diff/log）永远先于任何变更命令",
            "绝不自动执行 push --force / reset --hard / clean -fd —— 必须用户显式批准",
            "提交里不带密钥/令牌（先扫再提交：git diff --cached | grep -iE 'token|secret|key'）"
        ),
        whenToUse = "任何要产生提交的任务先过这套纪律；变基/挑拣前的分组整理也在这里做。"
    )

    // ═══ 4. github-actions-advanced ═══

    private val ACTIONS_ADVANCED = GitRoleSkill(
        id = "github-actions-advanced",
        name = "GitHub Actions 高级工程",
        emoji = "⚙️",
        summary = "生产级 CI/CD 设计 · 加固 · 触发去重 · 密钥与缓存纪律",
        description = "生产级工作流的设计与加固：触发去重（concurrency 取消中间" +
            "态）、权限最小化（permissions: read）、pinned action（免 tag 劫持）、" +
            "缓存与矩阵设计、密钥零裸奔。覆盖 CI 设计评审与 workflow 编写两侧。",
        sourceRepo = null,
        sourceNote = "github-actions-advanced 社区多维护版本的生产加固语义（sickn33 / dhar174 等版本聚焦点合并），本仓原生实现",
        triggers = listOf(
            "github actions", "actions", "workflow yml", "ci 配置", "ci 门禁",
            "ci/cd", "流水线", "pipeline", "workflow 加固", "触发去重",
            "concurrency", "runner", "缓存策略", "matrix", "矩阵构建"
        ),
        phases = listOf(
            GitSkillPhase(
                name = "现状审计",
                goal = "读现有 workflow：触发/权限/密钥/缓存/超时五面体",
                commands = listOf(
                    "ls .github/workflows/",
                    "cat .github/workflows/<name>.yml"
                ),
                verify = "五面体逐项有结论（缺项记为风险点）；触发重叠（push+PR 双触发）单列"
            ),
            GitSkillPhase(
                name = "触发与并发",
                goal = "去重触发 + concurrency 取消中间态",
                commands = listOf(
                    "on: pull_request 仅留 PR 触发（push 只留 main 终验）",
                    "concurrency: group: ci-\${{ github.ref }} / cancel-in-progress: true"
                ),
                verify = "PR 更新不再双倍运行；同组旧 run 被自动取消"
            ),
            GitSkillPhase(
                name = "权限与加固",
                goal = "permissions 最小化 + action pin 到全长 SHA + 超时封顶",
                commands = listOf(
                    "permissions: contents: read",
                    "uses: actions/checkout@<full-40-char-sha>  # 不用浮动 tag",
                    "timeout-minutes: 10"
                ),
                verify = "无 permissions: write 的默认；每个 uses 都是 SHA pin；每个 job 有 timeout"
            ),
            GitSkillPhase(
                name = "缓存与验证",
                goal = "缓存键正确失效 + 本地静态校验（actionlint/yaml parse）",
                commands = listOf(
                    "actions/cache@<sha> with: key: \${{ hashFiles('**/*.lock') }}",
                    "actionlint  # 或 python -c 'import yaml,sys; yaml.safe_load(open(sys.argv[1]))' <file>"
                ),
                verify = "缓存键含锁文件哈希（依赖变了缓存必失效）；actionlint 零告警"
            )
        ),
        guards = listOf(
            "secrets 只经 ${{ secrets.X }} 引用，绝不 echo/写日志（pull_request_target 的 checkout 污染向量单独评估）",
            "第三方 action 必须 pin 全长 SHA 并可溯源仓库；新加 workflow 前先本地过 YAML 解析器"
        ),
        whenToUse = "设计/评审/加固 .github/workflows 下任何文件时；CI 双倍运行、超预算、action 失效类事故排查。"
    )

    /** 本地域技能（1-4）：单仓库内的高级工作流。 */
    val LOCAL_WORKFLOW_SKILLS: List<GitRoleSkill> = listOf(
        ADVANCED_WORKFLOWS, GIT_ADVANCED, GIT_WORKFLOWS, ACTIONS_ADVANCED
    )
}
