package com.apex.agent.core.code.gitskill

/**
 * # Git 角色专属技能目录 · 跨仓库与发布域（5-9）
 *
 * 承接 [GitRoleSkillCatalog]（1-4 本地工作流域）的维度拆分 —— 文件预算
 * 纪律（≤1200 行/文件）。本文件覆盖用户指定的四个社区跨仓技能 +
 * 发布编排：
 *
 * | # | id | 能力域 | 上游 |
 * |---|----|--------|------|
 * | 5 | sync-repos | 双仓库同步（保作者 + 审批门禁） | MilkClouds/my-dev-playbook |
 * | 6 | relay-merge | 推送受阻 Gist 中继补丁 | dEitY719/gh-flow-skills |
 * | 7 | gh-cherry-pick | GitHub REST API 跨仓挑拣 | gh-cherry-pick 工具语义 |
 * | 8 | github-multi-repo | 组织级多仓同步 | DreamLab-AI/agentbox |
 * | 9 | github-release-management | 紧急补丁/热修发布编排 | 社区同名技能语义 |
 *
 * 这五套共同回答用户「双仓库补丁发布」的场景矩阵：
 * - 有共同历史 → 传统 cherry-pick（git-advanced-workflows）；
 * - **无共同历史**（独立 init 的双仓）→ sync-repos（补丁形态保作者）；
 * - 远端禁推 → relay-merge（Gist 中继 git am）；
 * - 要 commit 对象级精确（不落补丁文件）→ gh-cherry-pick（REST API）；
 * - N 仓齐步走 → github-multi-repo；要发版护栏 → github-release-management。
 */
object GitRoleSkillCatalogCrossRepo {

    // ═══ 5. sync-repos ═══

    private val SYNC_REPOS = GitRoleSkill(
        id = "sync-repos",
        name = "双仓库同步",
        emoji = "🔄",
        summary = "无共同历史跨仓同步 · 保留原作者 · 内置审批门禁",
        description = "sync-repos（MilkClouds/my-dev-playbook）语义：两个独立" +
            "初始化（无共同祖先）的仓库之间同步 commit —— upstream → fork、" +
            "开发仓 → 发布仓。用补丁形态（format-patch + am）传递，作者信息" +
            "原样保留；任何 cherry-pick/推送/建 PR 前必须过审批门禁，防敏感" +
            "内容意外跨仓泄漏。",
        sourceRepo = "https://github.com/MilkClouds/my-dev-playbook",
        sourceNote = "技能路径：skills/sync-repos（审批门禁为上游安全设计）",
        triggers = listOf(
            "双仓库", "两个仓库", "同步仓库", "sync repos", "upstream",
            "fork 同步", "无共同历史", "no common history", "开发仓",
            "发布仓", "镜像仓库", "保作者", "保留作者", "跨仓同步"
        ),
        phases = listOf(
            GitSkillPhase(
                name = "双仓基线",
                goal = "确认两仓无共同历史且各自身份清楚（谁源谁目标）",
                commands = listOf(
                    "git -C <src> log --oneline -5",
                    "git -C <dst> log --oneline -5",
                    "git merge-base <src-branch> <dst-branch> || echo '无共同祖先（sync-repos 场景成立）'"
                ),
                verify = "merge-base 失败输出确认了无共同历史；源/目标角色经用户确认"
            ),
            GitSkillPhase(
                name = "选段产补丁",
                goal = "源仓选定 commit 区间 → format-patch 产 mbox（作者内嵌）",
                commands = listOf(
                    "git -C <src> format-patch <base>..<head> -o <patch-dir>",
                    "ls <patch-dir>/*.patch"
                ),
                verify = "每个 .patch 头部 From: 是原作者；补丁数 = 区间提交数"
            ),
            GitSkillPhase(
                name = "审批门禁",
                goal = "补丁内容人工过目（敏感内容/密钥扫描）后才允许进入目标仓",
                commands = listOf(
                    "grep -riE 'token|secret|password|key' <patch-dir>",
                    "cat <patch-dir>/0001-*.patch"
                ),
                verify = "敏感扫描零命中；补丁内容已列给用户并获批（上游技能的安全红线）"
            ),
            GitSkillPhase(
                name = "目标仓落地",
                goal = "git am 保作者应用 + 三方收尾（签名链/PR/推送）",
                commands = listOf(
                    "git -C <dst> am <patch-dir>/*.patch",
                    "git -C <dst> log --format='%an %ae' -3"
                ),
                verify = "目标仓 log 的作者 = 源仓作者（未变成机器人身份）；am 冲突时转 git-advanced 冲突流程"
            )
        ),
        guards = listOf(
            "审批门禁是硬关卡：任何 am/push/建 PR 前必须敏感扫描 + 用户显式批准",
            "补丁只带内容 diff，绝不带源仓的 hooks/本地配置；发布仓身份（机器人账号）由用户指定而非默认"
        ),
        whenToUse = "两仓无共同历史的同步（否则普通 cherry-pick 更简单）；保作者是硬需求时必选。"
    )

    // ═══ 6. relay-merge ═══

    private val RELAY_MERGE = GitRoleSkill(
        id = "relay-merge",
        name = "中继补丁投递",
        emoji = "📡",
        summary = "推送受阻时的 Gist 中继 · git am 逐提交传递 · 先探测后启用",
        description = "relay-merge（dEitY719/gh-flow-skills）语义：远程仓库无法" +
            "直接 git push（企业限制/权限收口）时，把每个 commit 的补丁经 " +
            "Gist 中继传递到目标端 git am。先探测 push 是否真的受阻，只有" +
            "确实受阻才启用中继流程 —— 不是默认绕路。",
        sourceRepo = "https://github.com/dEitY719/gh-flow-skills",
        sourceNote = "gh-flow-skills 技能套件（GH_HOST 多主机认证是其配套安全设计）",
        triggers = listOf(
            "推送受阻", "push 被拒", "push blocked", "不能推送", "无法 push",
            "中继", "relay", "gist 中继", "企业仓库", "受限仓库",
            "git am 中继", "补丁投递"
        ),
        phases = listOf(
            GitSkillPhase(
                name = "受阻探测",
                goal = "先证明 push 真的不通（不是凭证过期这类可修问题）",
                commands = listOf(
                    "git push --dry-run <remote> <branch> 2>&1",
                    "git remote -v"
                ),
                verify = "干跑报错类型是权限/策略拒绝而非 401/网络（后者先修再推）；确实受阻才走中继"
            ),
            GitSkillPhase(
                name = "补丁序列化",
                goal = "待投递提交逐个 format-patch（一个 commit 一个文件）",
                commands = listOf(
                    "git format-patch <base>..<head> -o <relay-dir>",
                    "ls -1 <relay-dir> | wc -l"
                ),
                verify = "补丁数与待投递提交数一致；文件名序号连续（0001-000N）"
            ),
            GitSkillPhase(
                name = "Gist 中继",
                goal = "补丁上传 Gist（secret）取 raw 链接，投递给目标端执行者",
                commands = listOf(
                    "gh gist create <relay-dir>/0001-*.patch --secret",
                    "# 取 raw URL（含 <gist-id>/raw）递给目标端"
                ),
                verify = "raw URL 可匿名 GET 到补丁原文；敏感扫描已在 sync-repos 同款口径下通过"
            ),
            GitSkillPhase(
                name = "目标端落地",
                goal = "目标端 curl 补丁 → 逐个 git am（保序保作者）",
                commands = listOf(
                    "curl -sL <raw-url> -o relay-0001.patch",
                    "git am relay-0001.patch",
                    "git log --oneline -3"
                ),
                verify = "am 全链无冲突（有则停下走冲突流程）；目标端 log 与源端提交一一对应"
            )
        ),
        guards = listOf(
            "先探测后中继：push 未证实受阻不得启用（上游技能的核心设计，避免无谓绕路）",
            "Gist 必须 --secret 且内容过敏感扫描；中继只传补丁不传凭证"
        ),
        whenToUse = "push 被策略/权限挡死的场景；企业仓库/特殊受限仓。push 能通就用正常流程。"
    )

    // ═══ 7. gh-cherry-pick ═══

    private val GH_CHERRY_PICK = GitRoleSkill(
        id = "gh-cherry-pick",
        name = "GitHub API 跨仓挑拣",
        emoji = "🍒",
        summary = "REST API commit 对象级挑拣 · 冲突更智能 · 权限矩阵清晰",
        description = "gh-cherry-pick 工具语义：完全走 GitHub REST API 执行跨仓" +
            "cherry-pick，不落本地补丁文件 —— 基于 commit 对象操作，冲突处理" +
            "比 patch2pr 类工具更智能。Token 权限矩阵：classic 需 repo+workflow，" +
            "fine-grained 需 contents+workflows，私有仓另加 pull requests。",
        sourceRepo = null,
        sourceNote = "gh-cherry-pick（PyPI：uv tool install gh-cherry-pick）工具语义，本仓按 API 契约代码化",
        triggers = listOf(
            "api cherry-pick", "跨仓库挑拣", "跨仓 cherry-pick",
            "commit 对象", "patch2pr", "rest api 挑拣",
            "github api 同步提交", "免本地补丁"
        ),
        phases = listOf(
            GitSkillPhase(
                name = "权限对账",
                goal = "Token 权限与仓库可见性先对上（classic/fine-grained 矩阵）",
                commands = listOf(
                    "gh auth status",
                    "# classic: repo + workflow；fine-grained: contents + workflows（私有仓 + pull requests）"
                ),
                verify = "权限覆盖目标仓写路径；权限不足先升权再继续（401/403 是浪费一次调用）"
            ),
            GitSkillPhase(
                name = "源提交定位",
                goal = "拿源仓 commit SHA 与树对象（经 API，不 clone 源仓）",
                commands = listOf(
                    "gh api repos/<src-owner>/<src-repo>/commits/<sha>",
                    "gh api repos/<src-owner>/<src-repo>/commits/<sha>/pull"
                ),
                verify = "SHA 存在且属于目标分支；commit message/作者信息已取出（用于回传）"
            ),
            GitSkillPhase(
                name = "目标仓应用",
                goal = "目标仓基准分支上经 API 重建该提交（tree → commit → 更新 ref）",
                commands = listOf(
                    "gh api repos/<dst>/git/refs/heads/<base>  # 基准 ref",
                    "gh api repos/<dst>/git/trees -X POST  # 树重建",
                    "gh api repos/<dst>/git/commits -X POST  # 新 commit 对象（保作者）"
                ),
                verify = "新 commit 的 tree diff 与源提交语义一致；API 全程 2xx"
            ),
            GitSkillPhase(
                name = "回执与 PR",
                goal = "挑拣结果回执（SHA 映射表）+ 走 PR 落地而非直接改默认分支",
                commands = listOf(
                    "gh pr create --head <picked-branch> --title \"cherry-pick: <src-sha>\""
                ),
                verify = "SHA 映射（源 → 目标）已记录在 PR 描述；PR CI 绿后才算完成"
            )
        ),
        guards = listOf(
            "API 挑拣落 PR 而非直接推默认分支（审计链完整）；私有仓操作前核对 pull requests 权限",
            "冲突时优先回退建议人工介入（API 自动解冲突的语义风险高），并把冲突面写进 PR 描述"
        ),
        whenToUse = "要求 commit 对象级精确/免本地补丁文件的跨仓挑拣；与 sync-repos 的取舍：这里强在冲突智能与审计，那边强在作者保全与离线。"
    )

    // ═══ 8. github-multi-repo ═══

    private val MULTI_REPO = GitRoleSkill(
        id = "github-multi-repo",
        name = "组织级多仓同步",
        emoji = "🏛️",
        summary = "N 仓齐步走 · 安全补丁/版本对齐 · 不可逆操作护栏",
        description = "github-multi-repo（DreamLab-AI/agentbox）语义：组织级" +
            "同步编排 —— 安全补丁同时落 N 仓、依赖版本对齐、脚手架一致性。" +
            "核心是「必须同时落地的变更」的编排面 + 不可逆操作的硬护栏（先" +
            "试运行、逐仓确认、失败回滚路径预置）。",
        sourceRepo = "https://github.com/DreamLab-AI/agentbox",
        sourceNote = "org 级同步场景（安全补丁/版本对齐）+ 内置安全护栏",
        triggers = listOf(
            "多仓库", "多个仓库", "组织仓库", "org 仓库", "multi-repo",
            "多仓同步", "全部仓库", "所有仓库", "批量仓库", "仓库群",
            "安全补丁同步", "版本对齐", "组织级"
        ),
        phases = listOf(
            GitSkillPhase(
                name = "仓库清单",
                goal = "枚举受影响仓库（org API / 手工清单）并按影响分型",
                commands = listOf(
                    "gh repo list <org> --limit 100 --json name,defaultBranchRef",
                    "# 影响分型：直接命中（用了该依赖）/ 待验证 / 不受影响"
                ),
                verify = "清单有据（API 或用户确认）；分型三档各有归属，没有未分类仓库"
            ),
            GitSkillPhase(
                name = "试运行",
                goal = "单仓先行：一个仓库走完整流程验证补丁可行性",
                commands = listOf(
                    "# 选影响最大/结构最典型的仓先打补丁",
                    "gh pr create --repo <org>/<pilot> --title \"[multi-repo] <change>\""
                ),
                verify = "试点仓 PR CI 绿 + 语义验证通过；试点失败先修方案再扩散（不许带病铺开）"
            ),
            GitSkillPhase(
                name = "批量铺开",
                goal = "其余仓逐仓复用试点产物（补丁/脚本），每仓独立 PR",
                commands = listOf(
                    "gh pr create --repo <org>/<repo-N> --title \"[multi-repo] <change>\"",
                    "# 逐仓执行，不并发轰炸（API 限流 + 观察窗口）"
                ),
                verify = "每仓 PR 独立可回滚；铺开节奏串行或小批；失败仓不阻塞其余（记录差异原因）"
            ),
            GitSkillPhase(
                name = "收尾对账",
                goal = "PR 状态矩阵对账：全绿合并 / 有红处置 / 遗留清单",
                commands = listOf(
                    "gh pr list --repo <org>/<repo-N> --state open",
                    "# 输出 N 仓 × PR 状态矩阵给用户"
                ),
                verify = "状态矩阵无一仓失联；未合并仓有明确处置（修复/豁免/升级跟随）"
            )
        ),
        guards = listOf(
            "不可逆操作（合并/删除分支）逐仓显式确认，禁一刀切脚本连发",
            "失败回滚路径在铺开前预置（每仓 PR 可独立 revert）；试点未绿不铺开"
        ),
        whenToUse = "同一变更要落 ≥3 仓时；两仓转 sync-repos/gh-cherry-pick 更轻。"
    )

    // ═══ 9. github-release-management ═══

    private val RELEASE_MGMT = GitRoleSkill(
        id = "github-release-management",
        name = "发布编排",
        emoji = "🚀",
        summary = "紧急补丁/热修发布 · 版本化 · 角色协作 · 回滚预案",
        description = "github-release-management 语义：发布流程编排 —— 紧急补丁" +
            "（emergency patch）与热修（hotfix）的版本化、发布、部署与回滚" +
            "协调。覆盖发布经理/CI 工程师的角色协作面：热修分支模型、changelog" +
            "片段门禁、发版 tag 与 Release 资产、回滚触发判据。",
        sourceRepo = null,
        sourceNote = "github-release-management 社区技能语义（发布经理/CI 工程师角色协作），本仓原生实现",
        triggers = listOf(
            "发布", "release", "发版", "热修", "hotfix", "紧急补丁",
            "emergency patch", "版本发布", "上版", "回滚", "rollback",
            "changelog 门禁", "tag 发布", "release notes"
        ),
        phases = listOf(
            GitSkillPhase(
                name = "发布分型",
                goal = "定性：常规发版 vs 热修（流程与节奏完全不同）",
                commands = listOf(
                    "git log <last-tag>..HEAD --oneline",
                    "git tag -l 'v*' --sort=-v:refname | head -3"
                ),
                verify = "分型有据（变更集内容）；热修走短路径（hotfix 分支模型）而非完整发版仪式"
            ),
            GitSkillPhase(
                name = "版本与变更集",
                goal = "定版本号 + 聚合 changelog 片段（门禁口径）",
                commands = listOf(
                    "# 语义化版本决策：breaking → major / feature → minor / fix → patch",
                    "ls .changelog/unreleased/ 2>/dev/null || ls docs/changelog/ 2>/dev/null"
                ),
                verify = "版本号与变更集语义匹配；changelog 片段齐（缺片段的门禁先补齐再发）"
            ),
            GitSkillPhase(
                name = "发版执行",
                goal = "tag + Release 资产 + 发布通知（原子序：tag → build → release）",
                commands = listOf(
                    "git tag -a v<version> -m \"<summary>\"",
                    "git push origin v<version>",
                    "gh release create v<version> --notes-file <notes>"
                ),
                verify = "tag 指向的 commit 与 PR 合并态一致；Release 资产构建产物齐全（APK/包/校验和）"
            ),
            GitSkillPhase(
                name = "回滚预案",
                goal = "回滚判据与路径在发布时就位（不是出事后现想）",
                commands = listOf(
                    "git revert -m 1 <merge-sha>  # 合并提交的定向回滚",
                    "gh release delete v<version> --yes  # 必要时撤发行"
                ),
                verify = "回滚判据（崩溃率/关键指标阈值）书面化；回滚命令演练过干跑"
            )
        ),
        guards = listOf(
            "热修必须从受影响 release tag 拉 hotfix 分支，不基于 main（避免夹带未发布变更）",
            "回滚预案是发布的一部分：没有回滚路径的发布不上线"
        ),
        whenToUse = "任何版本发布/热修任务；发布事故（漏发/错版本/需回滚）处置。"
    )

    /** 跨仓库与发布域技能（5-9）。 */
    val CROSS_REPO_SKILLS: List<GitRoleSkill> = listOf(
        SYNC_REPOS, RELAY_MERGE, GH_CHERRY_PICK, MULTI_REPO, RELEASE_MGMT
    )
}
