<!-- kb: id=git | role=builtin_coding_git | title=Git 专家知识库 | version=1 -->
# Git 专家知识库

## 必看内容

- **三区心智**：工作区（Working Tree）→ 暂存区（Index）→ 提交（HEAD 链）。
  一切命令都是在这三态之间搬运； confused 时画三列图（改了什么没 add？
  add 了没 commit？），90% 的"git 怪象"瞬间消解。
- **非破坏性检查先行**：动手改历史（rebase/reset）前，先
  `status` → `diff` → `log --oneline -10` 三连 —— 仓库本位的只读探查
  永远优先于写操作。
- **共享历史不可改写**：已推送的分支禁 reset/rebase 改写
  （强推 = 团队事故）；本地未推的历史随便整理。改写前先
  `branch backup/xxx` 打快照分支（成本 0，保命）。
- **提交原子性**：一次提交一个主题（功能/修复/格式化分开）；
  提交说明 `type(scope): 摘要`（本仓库规范）—— 未来的你靠 log 找回
  "当时为什么这么改"。
- **冲突解构**：冲突不是 Git 坏了，是两个意图在同一位置各说各话。
  解冲突 = 选一个意图或合并两个意图，然后用测试裁决 —— 不是"删掉
  <<< 那几行"。
- **挂了先 reflog**：任何"历史丢了/误删分支/reset 过头"，
  `git reflog` 里几乎都找得回（90 天窗口）—— 先备份再操作。

## 快速索引

| 想找什么 | 去哪一节 |
| --- | --- |
| 三区图解 | 1. 三区模型 |
| 状态/差异查看 | 2. 检视命令 |
| 暂存与提交 | 3. 提交命令 |
| 撤销各阶段 | 4. 撤销矩阵 |
| 分支操作 | 5. 分支命令 |
| 合并与冲突 | 6. 合并 |
| 变基（rebase） | 7. 变基 |
| 历史查找 | 8. 历史检索 |
| stash 临时区 | 9. stash |
| 标签 | 10. 标签 |
| 远程协作 | 11. 远程 |
| 拉取策略 | 12. fetch/pull |
| 子模块 | 13. submodule |
| worktree | 14. worktree |
| cherry-pick | 15. cherry-pick |
| bisect 二分 | 16. bisect |
| reflog 救援 | 17. reflog 救援 |
| 大文件/LFS | 18. 大文件 |
| 忽略规则 | 19. gitignore |
| 配置体系 | 20. 配置 |

## 1. 三区模型

```text
工作区          暂存区(Index)        本地仓库(.git)      远程
file.txt  --add-->  待提交快照  --commit-->  HEAD 链  --push-->  origin/branch
    <---- checkout/restore ----        <-- pull/fetch ----
```

- diff 的三种口径：
  `git diff`（工作区 vs 暂存）/ `git diff --staged`（暂存 vs HEAD）/
  `git diff HEAD`（工作区 vs HEAD）。
- add 是"快照点单"，不是"备份"—— 想部分提交用 `git add -p` 逐块选。

## 2. 检视命令

| 意图 | 命令 |
| --- | --- |
| 当前状态 | git status（-s 紧凑） |
| 改了什么 | git diff（见 1 节三种口径） |
| 谁在哪些文件改了多少 | git diff --stat |
| 提交历史 | git log --oneline --graph -20 |
| 某文件历史 | git log --follow -p path |
| 某行谁写的 | git blame -L 42,48 path |
| 两次提交间 | git diff a..b（^b 为父） |
| 查某字符串何时引入 | git log -S "text" --oneline |
| 目录树快照 | git ls-tree HEAD dir |
| 提交里某文件内容 | git show HEAD:path/to/f |

## 3. 提交命令

| 意图 | 命令 |
| --- | --- |
| 暂存全部 | git add -A |
| 部分文件 | git add path1 path2 |
| 交互选块 | git add -p |
| 提交 | git commit -m "type(scope): 摘要" |
| 追加到上一次（未推送） | git commit --amend（改历史！共享分支禁） |
| 上次提交消息错了 | git commit --amend -m "新消息"（同上纪律） |
| 空提交（触发 CI） | git commit --allow-empty -m "ci: retrigger" |

## 4. 撤销矩阵

| 状态 | 撤销命令 | 影响 |
| --- | --- | --- |
| 工作区改动未 add | git restore path（旧：checkout --） | 只丢工作区 |
| 已 add 未 commit | git restore --staged path（回工作区保留改动） | 只退暂存 |
| 已 commit 未 push | git revert HEAD（安全新提交） | 历史追加 |
| 已 commit 未 push（想消失） | git reset --soft HEAD~1（留改动）/ --mixed（默认）/ --hard（全丢，慎） | 改本地历史 |
| 已 push | git revert + push（唯一正路） | 不改写共享史 |

- --hard 之前必须 stash 或备份分支；误 --hard 的后悔药是 17 节 reflog。

## 5. 分支命令

| 意图 | 命令 |
| --- | --- |
| 列分支 | git branch -a（-v 带尾提交） |
| 建分支 | git branch name / git switch -c name（建+切） |
| 切换 | git switch name（旧：checkout） |
| 删除 | git branch -d name（-D 强删未合并） |
| 重命名 | git branch -m old new |
| 合并到当前 | git merge name（--no-ff 保留合并节点） |
| 跟踪上游 | git branch -u origin/name |
| 比分支差异 | git log main..feat --oneline；git diff main...feat |

## 6. 合并

- 快进（fast-forward）：无分叉时直接移指针 —— `--no-ff` 强制留节点
  （团队史更可读）。
- 三方合并：分叉后 merge 生成合并提交；冲突时：
  1. `git status` 看冲突文件清单。
  2. 编辑文件解决（或 `git checkout --ours/--theirs path` 取一方）。
  3. `git add` 标记已解；`git commit` 收尾。
- 中止/续做：`git merge --abort` / `--continue`。
- 策略：长期分支频繁同步主干（小冲突常解优于大冲突难解）。

## 7. 变基

```bash
git switch feat && git rebase main     # 把 feat 的提交"重放"到 main 顶端
# 冲突时：解 → git add → git rebase --continue；--abort 全撤
git rebase -i HEAD~5                    # 交互整理（见下）
```

交互 rebase 动词（`-i` 列表里改）：

| 动词 | 作用 |
| --- | --- |
| pick | 原样保留 |
| squash | 并入上一条（消息合并） |
| fixup | 并入上一条（丢消息） |
| reword | 改消息 |
| drop | 删除该提交 |
| edit | 停下来改内容 |

- 纪律：rebase 只整理**未推送**的本地史；
  `git push --force-with-lease`（优于裸 --force：带远程校验防覆盖他人）。

## 8. 历史检索

| 意图 | 命令 |
| --- | --- |
| 找引入 bug 的提交 | git bisect（16 节全流程） |
| 找字符串变更 | git log -S "sym"（增删处）；-G "regex" |
| 按作者 | git log --author="name" |
| 按时间窗 | git log --since="2 weeks ago" --until=... |
| 只看合并记录 | git log --merges |
| 文件何时删除 | git log --diff-filter=D -- path |
| 提交统计 | git shortlog -sn（每人提交数） |

## 9. stash

| 意图 | 命令 |
| --- | --- |
| 存当前（含未跟踪） | git stash push -u -m "说明" |
| 列出 | git stash list |
| 恢复并删 | git stash pop（冲突时自动保留） |
| 恢复留副本 | git stash apply |
| 丢 | git stash drop stash@{1} |
| 清空 | git stash clear |
| 局部存 | git stash push -p（选块） |

## 10. 标签

```bash
git tag -a v1.4.9 -m "release note"     # 附注标签（推荐）
git tag v1.4.9-light                     # 轻量标签（本地临时）
git push origin v1.4.9                   # 推单个
git tag -d v1.4.9 && git push origin :refs/tags/v1.4.9   # 删远程
```

- 发布类一律附注标签（-a）；CI 按标签触发发布流水线（本仓库 release.yml）。

## 快速索引 · 续（11-22 节）

| 想找什么 | 去哪一节 |
| --- | --- |
| 远程操作 | 11. 远程 |
| fetch vs pull | 12. 拉取策略 |
| 子模块 | 13. submodule |
| 多工作树 | 14. worktree |
| 摘提交 | 15. cherry-pick |
| 二分找 bug | 16. bisect |
| 误操作救援 | 17. reflog 救援 |
| 大文件 | 18. LFS |
| 忽略规则 | 19. gitignore |
| 配置 | 20. 配置 |
| 钩子 | 21. hooks |
| 内部模型 | 22. 对象模型 |

## 11. 远程

| 意图 | 命令 |
| --- | --- |
| 列远程 | git remote -v |
| 加远程 | git remote add name url |
| 改地址 | git remote set-url name url |
| 删远程分支 | git push origin --delete name |
| 跟踪关系 | git branch -vv |
| 拉取标签 | git fetch --tags |
| 推全部分支 | git push origin --all |

- 一次 push 多目标：`git remote set-url --add --push origin url2`。

## 12. 拉取策略

```bash
git fetch origin            # 只更新远程跟踪引用，不动工作区（安全首选）
git pull --ff-only          # 只允许快进（有分叉即停，逼你显式决定）
git pull --rebase           # 拉取并把本地提交重放其上（线性史）
git pull（默认）            # = fetch + merge（产生合并节点）
```

- 团队约定优先：本仓库 PR 流程 = 本地整理 + PR 合并，主干不做日常 pull 冲突。
- 永远先 fetch 看 `git log HEAD..origin/main --oneline` 再决定怎么合。

## 13. submodule

```bash
git submodule add https://…/lib.git libs/lib
git submodule update --init --recursive   # 克隆后初始化
git submodule update --remote             # 更新到各子模块远端最新
```

- 陷阱：submodule 钉的是**提交号**不是分支 —— 更新需显式进目录
  checkout 新提交后在外层 add。
- 克隆带子模块：`git clone --recurse-submodules url`。
- 替代：vendored 目录（本仓库 vendor/ 模式）适合需要改动的场景。

## 14. worktree

```bash
git worktree add ../hotfix hotfix-branch   # 同仓库多检出目录
git worktree list
git worktree remove ../hotfix
```

- 场景：热修复不想打断当前工作区；长期对照两个版本。
- 注意：同分支不能同时检出两处。

## 15. cherry-pick

```bash
git cherry-pick <sha>            # 摘一个提交到当前分支
git cherry-pick a^..b            # 摘区间（含 a）
git cherry-pick --continue       # 冲突解后继续
git cherry-pick --abort
```

- 用途：热修复回主干；不想要整个分支只想要一两个提交。
- 产生新 sha（历史分叉同内容）；后续 merge 仍会见到"重复"，
  无害（内容等价）。

## 16. bisect

```bash
git bisect start
git bisect bad                    # 当前（坏）
git bisect good v1.4.8            # 好的锚点
# …git 自动 checkout 中点，你测试后：
git bisect good   # 或 bad —— 每轮 halves 区间
# 定位后：
git bisect reset                  # 回到起点
git bisect log > /tmp/bisect.log  # 过程留档
```

- 自动化：`git bisect run scripts/check.sh`（脚本 exit 0=good）。

## 17. reflog 救援

```bash
git reflog                        # HEAD 的移动史（含"丢失"操作前）
git branch rescue HEAD@{3}        # 在那点打分支
# 或：
git reset --hard HEAD@{3}        # 直接回去（慎：当前未提交改动会丢）
```

事故恢复对照表：

| 事故 | 恢复 |
| --- | --- |
| reset --hard 过头 | reflog 找旧 HEAD → reset --hard 旧 |
| 误删分支 | git branch 名 <reflog里的尾提交> |
| rebase 中断丢提交 | reflog 找 rebase 前的 ORIG_HEAD |
| amend 覆盖原提交 | reflog 找 amend 前 HEAD |
| stash pop 冲突后 stash 没了 | git fsck --lost-found 找悬空 blob |

## 18. LFS

- 判据：单文件 >50MB 或仓库总量膨胀失控 —— 早期就上 LFS
  （历史重写成本随时间暴涨）。
- `.gitattributes`：`*.psd filter=lfs diff=lfs merge=lfs -text`。
- 协作方必须装 git-lfs（CI 同）；未装者检出的是指针文本。

## 19. gitignore

```gitignore
build/            # 目录
*.log             # 模式
!important.log    # 反例外（! 需在前规则之后）
/node_modules     # 只根级
**/temp           # 任意层级
```

- 已跟踪文件 ignore 无效 —— 先 `git rm --cached path` 再 ignore。
- 全局忽略：`core.excludesFile`。
- 查询为何被忽略：`git check-ignore -v path`。

## 20. 配置

| 层 | 文件 | 优先级 |
| --- | --- | --- |
| 系统 | /etc/gitconfig | 低 |
| 全局 | ~/.gitconfig | 中 |
| 仓库 | .git/config | 高 |

```bash
git config user.name "…"; git config user.email "…"      # 仓库级
git config --global core.editor "code --wait"
git config --global pull.rebase true
git config --global fetch.prune true                       # fetch 顺手清远程引用
git config alias.st "status -sb"                           # 别名
```

## 21. hooks

- 位置 `.git/hooks/`（不入库）；团队共享用 husky/.pre-commit-config 框架。
- 常用：pre-commit（lint/format）、commit-msg（消息规范校验）、
  pre-push（测试）。
- 跳过（紧急）：`git commit --no-verify`（纪律：偶尔为之）。

## 22. 对象模型

| 对象 | 内容 | 寻址 |
| --- | --- | --- |
| blob | 文件内容 | 内容 sha1 |
| tree | 目录（名→对象） | sha1 |
| commit | 树 + 父 + 作者/消息 | sha1 |
| tag | 附注标签指向 | sha1 |

- 分支 = 指向 commit 的可移动指针（.git/refs/heads/ 下的文本文件）；
  HEAD = 当前分支指针（或 detached 直接指 commit）。
- 理解对象模型后：`git cat-file -p HEAD` 可直接看内部；一切"神奇命令"
  只是指针操作。

## 快速索引 · 续（23-34 节）

| 想找什么 | 去哪一节 |
| --- | --- |
| 分支策略选型 | 23. 分支策略 |
| 提交消息规范 | 24. 提交规范 |
| PR 流程 | 25. PR 纪律 |
| code review 要点 | 26. review 清单 |
| 版本号策略 | 27. 版本号 |
| 发布流程 | 28. 发布 |
| 回滚发布 | 29. 回滚 |
| 热修复流 | 30. 热修复 |
| 多仓/monorepo | 31. 仓库形态 |
| 迁移仓库 | 32. 迁移 |
| 凭证管理 | 33. 凭证 |
| 性能与维护 | 34. 维护 |

## 23. 分支策略

| 策略 | 形态 | 适用 |
| --- | --- | --- |
| 主干开发 | 全部短分支直入 main | 强 CI + 强 review 团队 |
| GitHub flow | main + 短命 feature | 绝大多数（本仓库） |
| Git flow | main/develop/release/hotfix | 有版本发布节奏的 |
| trunk + tag | 只 main 打 tag | 小项目 |

- 本仓库口径：GitHub flow + PR（base main / feat/** 触发 CI 全门禁）。
- 分支寿命纪律：feature ≤3 天合入；长寿分支每天 rebase 主干。

## 24. 提交规范

本仓库格式（CI commit-message 门禁同款）：

```text
type(scope): 中文摘要 —— 关键词
```

| type | 用途 |
| --- | --- |
| feat | 新功能 |
| fix | 修复 |
| refactor | 重构（无行为变化） |
| test | 测试 |
| docs | 文档 |
| chore | 杂务（构建/依赖） |
| ci | CI 配置 |
| perf | 性能 |
| style | 格式（不影响逻辑） |
| revert | 回滚 |
| release | 发布 |
| security | 安全 |

- 正文三段：动机/做法/影响；PR 描述同构（见 docs/pr-body.md）。

## 25. PR 纪律

- 小而专注：单 PR ≤400 行 diff（review 质量悬崖在 ~500 行）。
- 标题 = 提交主题延续；描述带验证证据（"CI 绿 + 本地测试 N 项过"）。
- 依赖栈（stacked PR）：base 指向前一 PR 分支，合并后逐个改 base。
- 更新 PR：新提交推同分支（不要"再开一个 PR"）。
- 本仓库特有：changelog 片段随 PR（changelog-gate 门禁）；
  skip-changelog 标签仅限纯内部变更。

## 26. review 清单

- [ ] 改动与标题一致（无夹带）
- [ ] diff 无意外格式化噪音
- [ ] 错误路径有处理（不吞）
- [ ] 新增依赖必要性
- [ ] 测试覆盖新行为
- [ ] 命名可读；复杂处有注释（为什么，不是什么）
- [ ] 破坏性变更在描述声明（迁移/兼容说明）

## 27. 版本号

- 语义化版本：MAJOR.MINOR.PATCH（破坏.功能.修复）。
- 预发布：1.5.0-rc.1（排序低于正式）；构建元数据 +build.sha 不参与优先级。
- 本仓库形态：v1.4.9.45（四位，第 4 位为热修粒度）—— 版本口径
  跟随仓库既有约定优先于通用规范。

## 28. 发布

1. 冻结（只收修复）→ changelog 聚合（本仓库 changelog.py build）。
2. 打附注标签 → 推标签触发发布流水线（release.yml）。
3. 产物校验（APK/资产）→ 发布说明发布。
4. 版本记录归档（released/ 目录模式）。

## 29. 回滚

- 代码回滚：`git revert <sha>`（新提交反转，历史可读）优于 reset。
- 发布回滚：重发前一版本制品（比代码回滚更快触达用户）。
- 数据回滚：回滚代码 + 数据库迁移必须向后兼容（迁移只加不删）。

## 30. 热修复

```bash
git switch -c hotfix/issue-123 main   # 或 release 分支
# 修 + 测试
git commit -m "fix(core): …"
# 双向合并：先合 main（走 PR + CI），再 cherry-pick 到 release 分支
git switch release/1.5 && git cherry-pick <sha>
```

- hotfix 分支寿命最短（当天）；禁止在 hotfix 上顺手做其他事。

## 31. 仓库形态

| 形态 | 优 | 劣 |
| --- | --- | --- |
| monorepo | 原子跨模块变更/统一工具链 | 仓库膨胀/权限粗 |
| polyrepo | 边界清晰/CI 小 | 跨仓变更同步地狱 |

- monorepo 关键配套：CODEOWNERS 分区 review；CI 按路径触发。
- 依赖仓锁定：本仓库 gradle/*.lock 模式（kit 仓按 sha 锁定）。

## 32. 迁移

```bash
git clone --mirror old.git && cd old.git
git push --mirror new-repo.git
# 历史重写（去密钥等）：git filter-repo（优于 filter-branch）
```

- 迁移清单：hooks/CI secrets/保护分支/webhook/协作者 ——
  mirror 不带这些，需手动。

## 33. 凭证

- HTTPS：credential helper（osxkeychain/wincred/libsecret）；
  token 分类：classic PAT（repo/workflow scope）最小授权。
- SSH：`ssh-keygen -t ed25519`；公钥入平台；签名提交
  （user.signingkey）可选。
- 泄露处置：立即 revoke token → 清历史（filter-repo）→ 轮换所有同源密钥。

## 34. 维护

| 症状 | 诊断 | 处理 |
| --- | --- | --- |
| clone 越来越慢 | du -sh .git | 大文件进史（18 节 LFS 早期化） |
| gc 慢 | git count-objects -vH | git gc --aggressive 夜间 |
| 子模块不同步 | status 带 -dirty | 锁定版本表 + update --init 校验步骤入 CI |
| clone 卡 LFS | 未装 lfs | 安装 git-lfs；CI 加前置步骤 |

## 快速索引 · 续（35-46 节）

| 想找什么 | 去哪一节 |
| --- | --- |
| 冲突场景库 | 35. 冲突场景库 |
| 空树/初始提交技巧 | 36. 边界技巧 |
| 分支对比技巧 | 37. 对比技巧 |
| 提交切分 | 38. 提交切分 |
| 历史美化 | 39. 历史整理 |
| 二进制冲突 | 40. 二进制 |
| 团队约定模板 | 41. 团队约定 |
| 常见报错速查 | 42. 报错速查 |
| 免责与边界 | 43. 操作分级 |
| GUI 工具 | 44. GUI |
| GitHub 特化 | 45. GitHub 特化 |
| 本仓库纪律 | 46. 仓库纪律 |

## 35. 冲突场景库

| 场景 | 解法 |
| --- | --- |
| 双方同函数不同改 | 合并语义后跑测试裁决 |
| 一方删文件一方改 | 决定去留：`git rm` 或恢复文件再改 |
| 重命名 + 改动 | Git 通常自动跟随；未跟时手工把改动搬到新名 |
| 换行符战争 | .gitattributes `* text=auto`；core.autocrlf 统一口径 |
| 模式冲突 | 逐块看 `git diff`；别信"全取我方" |
| 生成物冲突 | 根本解：生成物不入库 |

## 36. 边界技巧

```bash
git commit --allow-empty -m "chore: branch anchor"   # 空锚点提交
git switch --orphan fresh                              # 无父新史（换根）
git checkout $(git rev-list --max-parents=0 HEAD)      # 首个提交
git rm -r --cached . && git add .                      # 重新按 ignore 摄入
```

## 37. 对比技巧

| 意图 | 命令 |
| --- | --- |
| 两分支谁多哪些提交 | git log main..feat --oneline（feat 独有） |
| 分叉点 | git merge-base a b |
| 只看本分支改了什么 | git diff main...feat（三点：自分叉起） |
| 两个提交同一文件 | git diff a b -- path |
| 统计行数变化 | git diff --stat；--shortstat |

## 38. 提交切分

- 反悔最后一次太杂：`git reset --soft HEAD~1` → 分批 add → 多次 commit。
- 改历史中间某提交：`git rebase -i` 标 edit → 改 → `git commit --amend` → continue。
- 按块拆（未提交）：`git add -p` 逐块走。

## 39. 历史整理

- 压缩碎提交：rebase -i 的 squash/fixup（推送前做）。
- 修正作者：`git rebase -i --exec "git commit --amend --reset-author"`。
- 去除敏感文件（未推送）：filter-repo 单文件移除。

## 40. 二进制

- 冲突无法合并：约定"谁的版本"规则（`merge=ours` 或锁定单一 owner 目录）。
- .gitattributes 标 `-diff`（不显示假文本 diff）。

## 41. 团队约定

最小集模板（贴 README）：

```text
1. 分支：feat/… bug/… hotfix/…（≤3 天合入）
2. 提交：type(scope): 摘要 —— 关键词
3. PR：≤400 行，验证证据入描述，changelog 片段随行
4. 主干常绿：CI 必过才合
5. 禁 force push 共享分支（--force-with-lease 也仅限自己分支）
```

## 42. 报错速查

| 报错 | 原因/解 |
| --- | --- |
| detached HEAD | 检出了提交而非分支；switch 回或建新分支再动 |
| non-fast-forward | 远程有你没有的提交；fetch 后 rebase/merge |
| permission denied (publickey) | SSH 密钥未配/未加平台 |
| Authentication failed | token 过期/权限 scope 不足 |
| fatal: not a git repository | 目录层级不对（cd 到仓库内） |
| Your local changes would be overwritten | 先 stash 或 commit 再拉 |
| untracked working tree files | merge 会覆盖未跟踪文件：移走或 add |
| bad object | 对象损坏/浅克隆缺对象：完整 fetch |

## 43. 操作分级

| 级 | 操作 | 前置 |
| --- | --- | --- |
| 只读 | status/diff/log | 无 |
| 常规写 | add/commit/switch | 无 |
| 历史整理（未推） | rebase/reset/amend | backup 分支 |
| 改共享历史 | - | 默认禁止；走新提交抵消 |
| 仓库级重写 | filter-repo | 全员协调停推 |

## 44. GUI

| 工具 | 场景 |
| --- | --- |
| IDE 内建（AS/VSCode） | 日常 diff/历史/blame |
| gitk / gitg | 轻量历史树 |
- GUI 永远是 CLI 的皮；出怪象回 CLI 诊断（GUI 的错误提示常被翻译丢信息）。

## 45. GitHub 特化

| 意图 | 姿势 |
| --- | --- |
| 常驻分支保护 | settings/branches：要求 CI + review 数 |
| CODEOWNERS | .github/CODEOWNERS 分区指定 review 人 |
| PR 自动关 issue | 描述写 Closes #123 |
- fork 工作流：fork → branch → PR 上游；同步上游用
  `git remote add upstream` + `git fetch upstream`。

## 46. 仓库纪律（本仓库）

- 提交消息：type(scope): 摘要（CI guard-rails 门禁）。
- PR：changelog 片段随行（changelog-gate）；代码 PR 必过 CI + 四门禁
  （结构质量/注释完整性/测试纪律/字符串镜像）。
- kit 兄弟仓锁定：gradle/*.lock 按 sha；改库行为去库仓提 PR
  （其 consumer-check 会回来跑本仓库编译）。
- AGENTS.md 是协作规则单源（本库 41 节团队约定的实例）。

## 快速索引 · 续（47-60 节）

| 想找什么 | 去哪一节 |
| --- | --- |
| 命令全表·检视 | 47. 全表·检视 |
| 命令全表·分支 | 48. 全表·分支 |
| 命令全表·远程 | 49. 全表·远程 |
| 命令全表·历史 | 50. 全表·历史 |
| 命令全表·撤销 | 51. 全表·撤销 |
| 剧本·合并主干 | 52. 剧本·合主干 |
| 剧本·解决大冲突 | 53. 剧本·大冲突 |
| 剧本·找回丢历史 | 54. 剧本·找历史 |
| 剧本·整理烂历史 | 55. 剧本·整理史 |
| 剧本·跨仓同步 | 56. 剧本·跨仓 |

## 47. 全表·检视

| 命令 | 说明 |
| --- | --- |
| git status -sb | 紧凑状态+跟踪分支 |
| git diff | 工作区 vs 暂存 |
| git diff --staged | 暂存 vs HEAD |
| git diff HEAD | 工作区 vs HEAD |
| git diff --cached --stat | 暂存统计 |
| git show | 最近提交详情 |
| git show HEAD~3:file | 历史版本文件内容 |
| git log --oneline --graph --all --decorate | 全景图 |
| git log -p -3 | 最近3个带补丁 |
| git log --author="me" --since="1 week" | 过滤组合 |
| git log --grep="fix" | 按消息搜 |
| git log -S "funcName" | 内容出现/消失处 |
| git log --follow -- path | 单文件史（含改名） |
| git blame -L 10,20 path | 段落归属 |
| git blame --reverse path | 这行最后谁改没了 |
| git shortlog -sn | 按人统计 |
| git describe --tags | 最近标签相对描述 |
| git merge-base a b | 分叉点 |
| git ls-files | 索引清单 |
| git cat-file -p sha | 看任意对象 |
| git reflog | HEAD 移动史 |
| git fsck --lost-found | 悬空对象 |

## 48. 全表·分支

| 命令 | 说明 |
| --- | --- |
| git branch | 本地列表 |
| git branch -v | 带尾提交 |
| git branch -a | 含远程 |
| git branch -vv | 带跟踪关系 |
| git branch -d name | 删（已合并才成） |
| git branch -D name | 强删 |
| git branch -m new | 改名 |
| git branch -u origin/new | 设上游 |
| git switch name | 切换 |
| git switch -c name | 建并切 |
| git switch - | 回上一个 |
| git checkout --detach sha | 游离到提交 |
| git branch --contains sha | 谁包含此提交 |
| git branch --merged main | 已并入清单 |
| git cherry main feat | feat 独有提交标记 |

## 49. 全表·远程

| 命令 | 说明 |
| --- | --- |
| git remote -v | 列出 |
| git remote add n url | 添加 |
| git remote set-url n url | 改址 |
| git remote rename a b | 改名 |
| git remote remove n | 删除 |
| git fetch [remote] | 取引用不动工作区 |
| git fetch --prune | 顺手清失效远程引用 |
| git fetch --tags | 带标签 |
| git push | 推当前（有上游时） |
| git push origin local:remote | 显式映射 |
| git push -u origin name | 首推+设上游 |
| git push --tags | 推全部标签 |
| git push origin --delete name | 删远程分支 |
| git push --force-with-lease | 带校验强推（仅己分支） |
| git pull = fetch+merge | 见 12 节策略 |
| git pull --rebase | 拉取重放 |

## 50. 全表·历史

| 命令 | 说明 |
| --- | --- |
| git log --pretty=format:"%h %an %s" | 自定义输出 |
| git log --name-status | 每次提交改了哪些文件 |
| git log --diff-filter=M | 只修改类 |
| git log --stat --summary | 带统计 |
| git rev-list --count HEAD | 提交总数 |
| git rev-list --max-parents=0 HEAD | 根提交 |
| git log main..feat ^main | feat 独有（同义） |
| git log --cherry-pick --right-only a...b | 忽略等价补丁 |

## 51. 全表·撤销

| 场景 | 命令 |
| --- | --- |
| 未 add 丢工作区改动 | git restore path |
| 未 add 全丢 | git restore . |
| 已 add 退暂存 | git restore --staged path |
| 已 add 且想丢改动 | git restore --staged path && git restore path |
| 未推撤提交（留改动） | git reset --soft HEAD~1 |
| 未推撤提交（回到未 add） | git reset --mixed HEAD~1 |
| 未推彻底消失 | git reset --hard HEAD~1（reflog 可救） |
| 已推反转 | git revert HEAD |
| 反转一段 | git revert OLD^..NEW |
| 反转合并提交 | git revert -m 1 <merge-sha> |
| amend 未推 | git commit --amend |
| amend 已推 | 禁；revert 抵消后重提 |

## 52. 剧本·合主干

把长命 feature 合回 main 的标准动作：

1. fetch + `git log HEAD..origin/main --oneline` 看增量。
2. `git rebase origin/main`（本地史未推可安全做）。
3. 冲突逐个解（35 节场景库）→ 测试过。
4. `git push`（或走 PR 由平台合并）。
5. 合后 `git branch -d feat` 清理。

## 53. 剧本·大冲突

积累数周的大冲突解构法：

1. `git merge --no-commit --no-ff main` 让冲突全暴露。
2. 按文件分桶：独立文件冲突 / 生成物冲突 / 语义冲突。
3. 生成物直接选一方重生成；独立文件逐个解。
4. 语义冲突（同函数）最后解 —— 结合双方 intent 重写，跑全测试。
5. 若冲突面 >30 文件：放弃合并，评估"分段合"或主干重实现。

## 54. 剧本·找历史

"上周还好好的"定位法：

1. `git log --since="7 days" --oneline` 圈时间窗。
2. 锚定已知好版本（tag/描述）→ bisect（16 节）。
3. 或 `git log -S "出问题的标识"` 直接锁定引入提交。

## 55. 剧本·整理史

推送前的洗史流程：

1. `git branch backup/pre-rebase` 快照。
2. `git rebase -i` squash 碎提交 / reword 消息 / drop 噪音。
3. `git log --oneline` 复核叙事可读性。
4. 全测过 → push。

## 56. 剧本·跨仓

上游库改了要联动本仓库：

1. 上游仓 PR + 其 CI（consumer-check 回测本仓库编译）。
2. 本仓库 bump 锁文件（gradle/*.lock）为上游新 sha ——
   本仓库 PR 的 CI 用新锁构建。
3. 两仓提交互相引用（PR 号互链）。

## 快速索引 · 续（57-70 节）

| 想找什么 | 去哪一节 |
| --- | --- |
| 深度问答·概念 | 57. 问答·概念 |
| 深度问答·操作 | 58. 问答·操作 |
| 反模式大全 | 59. 反模式 |
| 场景速配卡 | 60. 速配卡 |
| 剧本·发布日 | 61. 剧本·发布日 |
| 剧本·事故复盘 | 62. 剧本·复盘 |
| 剧本·交接仓库 | 63. 剧本·交接 |
| 剧本·两步验证 | 64. 剧本·签名 |
| 剧本·仓库瘦身 | 65. 剧本·瘦身 |
| 剧本·换主分支名 | 66. 剧本·改名 |

## 57. 问答·概念

| 问 | 答 |
| --- | --- |
| HEAD 是什么 | 当前位置指针（通常指分支名，detached 时指提交） |
| origin 是什么 | 默认远程的短名（可多个远程） |
| fetch 和 pull 本质 | 前者取引用；后者取完还合 |
| tracking branch | 本地分支与远程的对应（-u 建立） |
| shallow clone | 截断史（--depth 1）；省流量但不可 bisect/深史操作 |
| detached HEAD 危险吗 | 提交会脱离分支（易丢）；有产出就立刻建分支 |
| fast-forward | 无分叉时指针直移（--no-ff 可禁） |
| squash merge | 平台侧把 PR 压成单提交合入（史干净但丢细粒度） |
| ORIG_HEAD | 一次大操作（merge/rebase）前的位置备忘 |
| packed-refs | 引用的打包形态（性能，无语义差） |

## 58. 问答·操作

| 问 | 答 |
| --- | --- |
| 只想把 A 文件恢复到历史版 | git restore --source=HEAD~3 path |
| 看历史某版全仓 | git checkout HEAD~3（detached）或 switch --detach |
| 把别的分支一个文件拿过来 | git checkout feat -- path（旧式但好用） |
| 本地提交推不上去 | fetch 后看 diverge；rebase 或 merge 后再推 |
| 误在 main 上提交了 | 未推：switch -c feat 带走 + main reset 回退 |
| stash 带未跟踪 | push -u；apply 时注意 untracked 合并 |
| 怎么只拉某分支 | git fetch origin feat:local-feat |
| 别人删了远程分支我还在引用 | fetch --prune 清理 |

## 59. 反模式

| 反模式 | 后果 | 正解 |
| --- | --- | --- |
| force push 共享分支 | 团队史错乱 | revert；force 仅限本人短命分支 |
| 巨型提交（一周一提） | review 不可读/回滚粒度死 | 原子提交（24 节） |
| 提交生成物 | 冲突永动机 | .gitignore 收口 |
| "先合了再说"绕 CI | 主干红着过日子 | 常绿纪律（46 节） |
| reset 当撤销键 | 已推历史消失 | revert；reflog 救一次不代表每次 |
| 提交消息"修改bug" | 未来考古无门 | 规范消息+正文 |
| 在 master 直接开发 | 规避了 review | 分支+PR 流（23 节） |
| ignore 已跟踪文件 | 照样被改 | rm --cached 先脱管 |
| 半 rebase 弃疗 --abort 惯了 | 分叉越攒越大 | 冲突早解小解（6 节策略） |

## 60. 速配卡

| 症状 | 节 |
| --- | --- |
| 想撤销 | 4 / 51 |
| 状态看不懂 | 1 / 47 |
| 冲突 | 6 / 35 / 53 |
| 历史丢了 | 17 / 54 |
| 提交太乱 | 38 / 39 / 55 |
| 远程不一致 | 12 / 49 |
| 找引入点 | 16 / 54 |
| 分支策略 | 23 |
| PR 怎么写 | 25 |
| 报错看不懂 | 42 |
| 概念不懂 | 57 |
| 团队约定 | 41 / 46 |

## 61. 剧本·发布日

```bash
# 1) 冻结与聚合
git switch main && git pull --ff-only
python3 scripts/changelog.py build --version 1.5.0 --code 120
# 2) 打标签（附注）
git tag -a v1.5.0 -m "release 1.5.0"
git push origin v1.5.0            # 触发 release 流水线
# 3) 版本锚提交
git commit --allow-empty -m "release: v1.5.0 anchor" && git push
```

- 发布日纪律：只做发布动作，不顺手改代码；任何代码变更=重走流程。

## 62. 剧本·复盘

事故（主干红/误推/丢失）后 30 分钟复盘产出：

| 项 | 内容 |
| --- | --- |
| 时间线 | 何时引入/发现/修复 |
| 根因 | 技术根因 + 流程根因（如绕过 CI） |
| 触发器 | 怎么能更早发现（钩子/门禁） |
| 改进 | 落到 hook/CI/约定的具体一条 |

## 63. 剧本·交接

接手陌生仓库动作序列：

1. clone --recurse-submodules。
2. README/AGENTS.md/CI 配置三读。
3. `git log --oneline -30` 看近期脉搏；
   `git shortlog -sn` 看作者分布。
4. branch -a 看分支卫生；remote -v 看协作形态。
5. 跑一次 CI 全绿基线（本仓库：`./gradlew :app:compileDebugKotlin`）。

## 64. 剧本·签名

```bash
git config --global commit.gpgsign true
git config --global user.signingkey <key-id>
# SSH 签名（新平台支持）：
git config --global gpg.format ssh
git config --global user.signingkey ~/.ssh/id_ed25519.pub
```

- 验证：`git log --show-signature -1`。
- 平台显示 Verified 徽标需公钥已上传。

## 65. 剧本·瘦身

仓库膨胀治理：

1. `git count-objects -vH` 看体积。
2. 找大户：`git rev-list --objects --all | …` 按 blob 聚合排序。
3. filter-repo 移除历史中的大文件目录。
4. 全员重新 clone（旧 .git 作废）；配套 LFS 防复发。

## 66. 剧本·改名

master → main 全链路：

```bash
git branch -m master main
git push -u origin main
git symbolic-ref HEAD refs/heads/main    # 服务端默认分支（平台侧同步改）
git push origin --delete master          # 等下游引用迁完再删
```

- 同步改：CI 的 on.branches、保护规则、PR 模板、文档引用（grep 全库）。

## 快速索引 · 续（67-80 节）

| 想找什么 | 去哪一节 |
| --- | --- |
| 环境变量/配置速查 | 67. 环境速查 |
| 换行符 | 68. 换行符 |
| 编码与路径 | 69. 编码路径 |
| 性能命令 | 70. 性能 |
| 剧本·断点续修 | 71. 剧本·续修 |
| 剧本·多人同文件 | 72. 剧本·同文件 |
| 剧本·开源贡献 | 73. 剧本·开源 |
| 剧本·cherry-pick 流 | 74. 剧本·摘流 |
| 附·速记卡 | 75. 速记卡 |
| 附·总目录 | 76. 总目录 |

## 67. 环境速查

| 变量 | 用途 |
| --- | --- |
| GIT_EDITOR | 交互操作编辑器 |
| GIT_MERGE_AUTOEDIT | merge 消息自动化 |
| GIT_PAGER | 分页器 |
| core.autocrlf | 换行策略（见 68 节） |
| core.excludesFile | 全局 ignore |
| init.defaultBranch | 新仓默认分支名 |
| pull.rebase | pull 默认变基 |
| fetch.prune | fetch 清远程引用 |
| push.default | 推送范围（current/upstream） |
| rerere.enabled | 记住冲突解法复用 |

## 68. 换行符

```gitattributes
* text=auto           # 基线：文本自动规范（入库 LF）
*.bat text eol=crlf   # 平台特定强制
*.png binary
```

- 团队口径写进 .gitattributes（比各人 autocrlf 配置可靠）；
  冲突中"整文件都变了"多半是换行战争。

## 69. 编码路径

- 内容编码与 Git 无关（存字节）；乱码是文件本身的事。
- 路径大小写：Linux 敏感/macOS 默认不敏感 —— 跨平台改名用两步
  （先临时名再目标名）。

## 70. 性能

| 症状 | 命令 |
| --- | --- |
| 慢在扫描 | git status 慢：git update-index --untracked-cache |
| gc 慢 | 夜间 git gc；--aggressive 偶尔 |
| log 慢 | --oneline 少带 -p；用 --first-parent 看主干 |
| clone 慢 | --depth 1（浅）；--filter=blob:none（部分克隆） |
| fetch 慢 | --no-tags；--single-branch |

## 71. 剧本·续修

长任务中断后回到现场：

1. `git status` + `git stash list`（有没有忘掉的现场）。
2. `git log --oneline -5` 看已完成到哪。
3. tmp/ 工作产物盘点（与 git 状态对账）。
4. 从断点清单继续（不凭记忆重启）。

## 72. 剧本·同文件

两人常态改同一文件（排期错峰 or 分区）：

1. 约定文件所有权段（物理分区减少语义冲突）。
2. 每天合一次主干（小冲突化）。
3. 生成器产物锁单一出口（一处生成，别处改生成源）。

## 73. 剧本·开源

向上游贡献标准流：

1. fork → clone 自己的 fork（remote add upstream 原仓）。
2. feature 分支提交；PR 到 upstream/main。
3. CI 过 + review 意见逐轮 commit（同分支追加，别重开 PR）。
4. 合入后同步：`git fetch upstream && git rebase upstream/main`。

## 74. 剧本·摘流

把 release 分支需要的修复摘过去：

```bash
git switch release/1.5
git log main --oneline --grep="fix" -20     # 挑清单
git cherry-pick <sha1> <sha2>               # 多个一起
# 冲突解法同 rebase；tag 重打：
git tag -a v1.5.1 -m "hotfix" && git push origin v1.5.1
```

## 75. 速记卡

```text
看   : status / diff(三口径) / log --graph / blame
存   : add(-p 选块) / commit(type(scope): …) / stash(-u)
撤   : restore(工作区) / reset --soft(撤提交留改) / revert(已推唯一路)
分   : switch -c / merge --no-ff / rebase(只理未推)
远   : fetch 优先 / pull --rebase / push --force-with-lease(仅己)
救   : reflog → branch rescue HEAD@{N}
禁   : force 共享分支 / reset 已推 / 改历史不打备份
```

## 76. 总目录

| 段 | 节 |
| --- | --- |
| 模型与检视 | 1, 2, 22, 47, 50 |
| 提交/撤销 | 3, 4, 38, 39, 51 |
| 分支/合并 | 5, 6, 7, 35, 37, 48, 53 |
| 远程协作 | 11, 12, 25, 45, 49, 56 |
| 历史/救援 | 8, 16, 17, 54, 71 |
| 团队流程 | 23, 24, 26, 28, 29, 30, 41, 46, 61-66 |
| 环境维护 | 19, 20, 21, 32, 34, 65, 67-70 |
| 参考卡 | 42, 57-60, 75, 76 |

## 快速索引 · 续（77-88 节）

| 想找什么 | 去哪一节 |
| --- | --- |
| 实战案例集 | 77. 案例集 |
| 命令补充表 | 78. 命令补充 |
| 场景决策树 | 79. 决策树 |
| hooks 速查 | 80. hooks 表 |
| plumbing 表 | 81. plumbing |
| rebase 实战问答 | 82. 问答·rebase |
| merge 实战问答 | 83. 问答·merge |
| 远程实战问答 | 84. 问答·远程 |
| 团队角色 | 85. 团队角色 |
| 新人教学脚本 | 86. 教学脚本 |

## 77. 案例集

**案例 1：rescue 误 reset**

```text
$ git reset --hard HEAD~2     # 手滑，两个提交消失
$ git reflog
  a1b2c3d HEAD@{0}: reset: moving to HEAD~2   ← 移动前在这
  e4f5g6h HEAD@{1}: commit: feat: …
$ git reset --hard e4f5g6h    # 复活（未 gc 前有效）
```

**案例 2：rebase 中途想全撤**

```bash
git rebase --abort            # 回到 rebase 前一字不差
```

**案例 3：merge 冲突看不懂**

```bash
git diff                # 冲突块 <<<< ==== >>>> 三段对照
git checkout --theirs path   # 拿对方版（来自被合分支）
git checkout --ours path     # 拿我方版
# "ours/theirs" 在 rebase 里方向互换 —— 84 节
```

**案例 4：pull 把历史弄成分叉毛线**

治本：pull --rebase 常态化（12 节）；治标：
`git rebase origin/main` 后 push（自己的短命分支）。

**案例 5：submodule 提示 dirty**

`git status` 显示 `(modified content)`：进子目录
`git status` 看内部（多半是子目录未提交的改动）—— 处理内层或
`git submodule absorbgitdirs`。

**案例 6：删了分支还能找**

`git branch -D feat` 后 `git reflog | grep feat` 拿尾提交 →
`git branch feat <sha>`。

**案例 7：想看 PR 合并后真实文件**

平台 squash 合并：本地 `git pull` 后
`git show <squash-sha>` 直接看最终态（没有中间史）。

**案例 8：clone 体积失控**

浅克隆 + 单分支：`git clone --depth 1 --single-branch url`；
后续要深史：`git fetch --unshallow`。

## 78. 命令补充

| 命令 | 用途 |
| --- | --- |
| git merge --squash feat | 工作区带过来不自动提交（手工单提交） |
| git commit -v | 编辑器里带 diff（写消息有据可依） |
| git add -i | 交互模式（比 -p 多功能面） |
| git clean -fd | 删未跟踪（-n 先干跑） |
| git archive HEAD --format=tar | 导出快照（不带 .git） |
| git bundle create f.bundle main | 离线传输（无网环境） |
| git clone f.bundle repo | 从 bundle 克隆 |
| git diff --word-diff | 词级差异（长句文本友好） |
| git log -p --color-words | 词级历史 |
| git rerere | 记住冲突解法（rerere.enabled） |
| git range-diff a..b c..d | 比较两个提交序列差异 |
| git worktree add --detach dir sha | 临时检出某历史 |

## 79. 决策树

```text
要撤销？
├─ 改动没 add → restore
├─ add 没 commit → restore --staged
├─ commit 没 push → reset --soft/--hard（未推可改史）
└─ 已 push → revert（唯一）
要合并？
├─ 本地史要线性好读 → rebase（未推）
├─ 想保留并行拓扑真相 → merge --no-ff
└─ 平台侧 → PR（squash/merge 由团队约定）
要找回？
└─ reflog → 打分支 → 核对
```

## 80. hooks 表

| 钩子 | 时机 | 典型用途 |
| --- | --- | --- |
| pre-commit | commit 前 | lint/format/密钥扫描 |
| commit-msg | 消息写完 | 规范校验 |
| pre-push | push 前 | 跑测试 |
| post-merge | merge 后 | 依赖刷新提示 |
| pre-rebase | rebase 前 | 拦危险整理 |
| post-checkout | 切换后 | 环境同步提示 |

- 团队共享：husky（JS 生态）或 git hooks 模板目录 + 初始化脚本。
- 本仓库纪律：CI 门禁为主（commit-message/secret 扫描），本地钩子自愿。

## 81. plumbing

| 命令 | 用途 |
| --- | --- |
| git hash-object -w file | 算 blob sha 并入库 |
| git cat-file -p sha | 读任意对象 |
| git cat-file -t sha | 看类型 |
| git rev-parse HEAD | 解析引用为 sha |
| git update-index --add | 手动操纵索引（脚本用） |
| git write-tree | 从索引写 tree |
| git ls-files --stage | 索引原始态 |
| git diff-files | 工作区 vs 索引（底层） |

- 用途：写工具/核对内部一致性；日常操作用 porcelain 就够。

## 82. 问答·rebase

| 问 | 答 |
| --- | --- |
| rebase 时 ours/theirs 反了吗 | 是：rebase 的 ours=被重放基底（目标分支），theirs=你的提交 |
| rebase 后为什么 sha 全变了 | 重放=新提交（内容同，父链新） |
| 别人基于我的旧史开发怎么办 | 已推史别 rebase（41 节红线）；先合后整 |
| rebase 到一半想留档 | backup 分支早打好（7 节纪律） |
| interactive 列表太长 | HEAD~N 限定窗口；先 squash 大头再二轮 |
| rebase 冲突文件太多 | 考虑分段（每 N 个提交一轮）或放弃改 merge |

## 83. 问答·merge

| 问 | 答 |
| --- | --- |
| --no-ff 何时用 | 想在史里留"这是一次特性合并"的节点 |
| squash merge 后本地分支怎么办 | 平台合了远端，本地 feat 已无用：switch main 拉取后删 |
| 合并后想反悔 | 未推：reset --hard ORIG_HEAD |
| merge 冲突里生成文件 | 重新生成覆盖（先解源头文件再跑生成器） |
| 为什么我的 merge 是 fast-forward | 目标没新提交，指针直移即可 |

## 84. 问答·远程

| 问 | 答 |
| --- | --- |
| push 被拒 non-fast-forward | fetch 看增量；rebase 后再推 |
| token 权限够吗 | repo + workflow scope（改 CI 需后者） |
| 多平台同仓 | remote 加两个（origin/github 另一个）；push 双发 |
| 删远程分支本地还引用 | fetch --prune |
| fork 的 PR 怎么更新 | 同分支追加 commit，PR 自动更新 |

## 85. 团队角色

| 角色 | git 关注点 |
| --- | --- |
| 开发 | 原子提交/分支卫生/小 PR |
| review 者 | diff 可读/无夹带/测试 |
| 维护者 | 常绿主干/锁文件治理/标签纪律 |
| 发布员 | changelog 聚合/回滚预案 |

## 86. 教学脚本

给新人 30 分钟路线：

1. 三区图（1 节）手画一遍。
2. 玩坏一个玩具仓：add/commit/reset/reflog 全打一遍。
3. 两人模拟冲突：同文件各改各的 → merge → 手解。
4. 走一遍完整 PR 流（25 节）。
5. 发 reflog 救援卡（17 节）随身。

## 快速索引 · 续（87-100 节）

| 想找什么 | 去哪一节 |
| --- | --- |
| 综合演练题 | 87. 演练题 |
| 自测题 | 88. 自测 |
| 提交流模板 | 89. 提交流模板 |
| 日常 10 分钟流 | 90. 日常流 |
| 版本号决策表 | 91. 版本决策 |
| 发布核对表 | 92. 发布核对 |
| 术语表 | 93. 术语 |
| 反模式 FAQ | 94. 反模式 FAQ |
| 引擎问答（深层原理） | 95. 原理问答 |
| 知识库回执 | 96. 回执 |

## 87. 演练题

（做完对照 60/79 节）

1. 把一个 3 提交的特性分支 squash 成 1 个 → 答：rebase -i squash（39 节）
2. 已推送的提交发现含密钥 → 答：立即换 token；filter-repo 清史；force-with-lease（团队协调）
3. 主干红 30 分钟怎么处置 → 答：revert 引入提交（29 节）先绿后查
4. 两人同函数冲突 → 答：合并意图 + 测试裁决（6 节）
5. 想看上周三那天的仓库全貌 → 答：log --since/--until 圈提交或 checkout 当日尾提交

## 88. 自测

| 题 | 节 |
| --- | --- |
| diff 三口径？ | 1 |
| 撤已推提交唯一正路？ | 4 |
| rebase 的 ours 指谁？ | 82 |
| reflog 能救的前提？ | 17（未 gc） |
| squash merge 特点？ | 57 |
| 何时 --force-with-lease？ | 7（仅己分支） |
| submodule 钉的是什么？ | 13（提交号） |
| 忽略已跟踪文件？ | 19（rm --cached） |
| 冲突的本质？ | 必看（两意图打架） |
| 提交规范格式？ | 24 |

## 89. 提交流模板

一天的标准 git 节奏（可贴桌）：

```bash
# 开工
git switch main && git pull --ff-only
git switch -c feat/short-desc
# 编码中…小块提交
git add -p && git commit -m "feat(scope): …"
# 下班前合主干
git fetch origin && git rebase origin/main
git push -u origin feat/short-desc   # 开 PR
```

## 90. 日常流

10 分钟版本：

```text
1 min  : status + diff 检视
3 min  : add -p + commit（规范消息）
2 min  : fetch + rebase（如主干有新）
2 min  : push + PR 更新
2 min  : 回 review 意见（同分支追加提交）
```

## 91. 版本决策

| 变更 | 版本位 |
| --- | --- |
| 破坏兼容 | MAJOR |
| 新功能（向后兼容） | MINOR |
| 修复 | PATCH |
| 本仓库形态 | 第 4 位热修粒度（27 节） |
| 预发布 | -rc.N 后缀 |

## 92. 发布核对

- [ ] 标签附注（非轻量）
- [ ] changelog 聚合完成
- [ ] CI 全绿
- [ ] 回滚预案就绪（revert sha 清单）
- [ ] 产物校验（checksum）
- [ ] 发布说明用户语言书写

## 93. 术语

| 术语 | 含义 |
| --- | --- |
| porcelain | 人用命令（status/add…） |
| plumbing | 脚本用底层命令（81 节） |
| index/staging | 暂存区同义 |
| ORIG_HEAD | 大操作前位置备忘 |
| reachable | 从某引用可走到的对象集 |
| dangling | 无引用对象（fsck 报） |
| packfile | 压缩对象包（.git/objects/pack） |
| refspec | 引用映射（src:dst） |
| shallow | 截断史 |
| worktree | 同仓多检出 |

## 94. 反模式 FAQ

| 现象 | 根因 | 正解 |
| --- | --- | --- |
| "越合冲突越多" | 攒太久 | 每日小合（52 节） |
| "历史里全是 merge bubble" | pull 默认 merge | pull --rebase 常态 |
| "谁的提交找不到" | squash merge 丢细节 | 平台 PR 描述里留原史链接 |
| "CI 好好的到主干就红" | 本地与 CI 环境漂移 | 只信 CI（46 节门禁） |
| "仓库越clone越大" | 大文件进史 | 65 节瘦身 + LFS |

## 95. 原理问答

| 问 | 答 |
| --- | --- |
| Git 存的是快照还是差异 | 快照（blob 全量）；pack 里再物理压缩 |
| 为什么 add 后改动文件 diff 还有 | add 存的是当时的 blob；工作区又变了 |
| sha 怎么算 | 内容+类型+长度 的 SHA-1（同内容同 sha=去重） |
| branch 删除丢东西吗 | 只删指针；对象到 gc 前 reflog 可及 |
| merge 怎么找基准 | merge-base（最近公共祖先）三方对比 |
| 为什么 rebase 冲突可能反复出现 | 每个提交重放时都要过一遍冲突点 |

## 96. 回执

- 某节救了你 → 回复点名（vNext 扩写评估）。
- 错漏带节号 48h 修订。
- 推荐使用顺序：新任务 → 60 节速配卡 → read 起点节。

## 快速索引 · 续（97-110 节）

| 想找什么 | 去哪一节 |
| --- | --- |
| 高频 100 条·状态 | 97. 百条·状态 |
| 高频 100 条·改史 | 98. 百条·改史 |
| 高频 100 条·锁与安全 | 99. 百条·安全 |
| 高频 100 条·疑难 | 100. 百条·疑难 |
| 附·bisect 完整实录 | 101. bisect 实录 |
| 附·submodule 完整实录 | 102. submodule 实录 |
| 附·worktree 实录 | 103. worktree 实录 |
| 附·LFS 实录 | 104. LFS 实录 |
| 附·filter-repo 实录 | 105. filter-repo 实录 |
| 附·总速查卡 | 106. 总卡 |

## 97. 百条·状态

| # | 场景 | 命令 |
| --- | --- | --- |
| 1 | 看当前在哪个分支 | git branch --show-current |
| 2 | 一行状态 | git status -sb |
| 3 | 谁改了未提交 | git diff --stat |
| 4 | 新文件列表 | git ls-files --others --exclude-standard |
| 5 | 已暂存清单 | git diff --cached --name-only |
| 6 | 某文件在哪些提交改过 | git log --oneline -- path |
| 7 | 空目录为什么不被跟踪 | Git 只跟文件（放 .keep） |
| 8 | 忽略了但想看规则 | git check-ignore -v path |
| 9 | 想看 tag 列表 | git tag -l "v1.4*" |
| 10 | 描述当前位置 | git describe |

## 98. 百条·改史

| # | 场景 | 命令 |
| --- | --- | --- |
| 11 | 合并最近 3 提交 | rebase -i HEAD~3（squash） |
| 12 | 只改最旧那条消息 | rebase -i 标 reword |
| 13 | 拆开一个提交 | rebase -i 标 edit → reset --soft → 分提 |
| 14 | 把提交挪到别的分支 | cherry-pick 后原分支 drop |
| 15 | 交换提交顺序 | rebase -i 直接拖行序 |
| 16 | 删历史中的大文件 | filter-repo --path g --invert-paths |
| 17 | 全库改邮箱 | filter-repo --mailmap |
| 18 | 追加共同作者 | commit --amend 加 Co-authored-by 尾行 |
| 19 | 修复空消息提交 | rebase -i reword |
| 20 | 重放时跳过坏提交 | rebase --onto new-base bad..tip |

## 99. 百条·安全

| # | 场景 | 动作 |
| --- | --- | --- |
| 21 | token 疑似泄露 | 立即 revoke → 轮换 → 清史（filter-repo） |
| 22 | 提交里有 .env | 33 节三步 |
| 23 | 签名提交 | 64 节配置 |
| 24 | 校验他人签名 | git log --show-signature |
| 25 | 审计谁改过 CI 文件 | git log --oneline -- .github/workflows/ |
| 26 | 保护分支配置 | 平台 settings（45 节） |
| 27 | 最小 scope token | repo + workflow（仅改 CI 时加 workflow） |
| 28 | 密钥放哪 | 平台 secrets；本地 credential helper |
| 29 | 检查将要公开的敏感串 | 提交前 gitleaks 类扫描（21 节钩子） |
| 30 | fork 的安全 | PR 来自 fork 只跑只读权限 CI |

## 100. 百条·疑难

| # | 症状 | 一句话解 |
| --- | --- | --- |
| 31 | "You are in detached HEAD" | switch 回分支或建分支 |
| 32 | merge 说 already up to date | 目标已包含你（没东西可合） |
| 33 | rebase 说 current branch up to date | 同上 |
| 34 | fetch 后看不到新分支 | git branch -a 看远程引用名 |
| 35 | pull 报 divergent branches | 显式选 rebase 或 merge（新 Git 会逼问） |
| 36 | submodule 报 untracked content | 进子目录看内部改动 |
| 37 | tag 推不上去 | 需显式 git push origin v1.x |
| 38 | 换行符全文件 diff | 68 节统一 .gitattributes |
| 39 | 大小写改名不生效 | 两步改名（69 节） |
| 40 | clone 后没默认分支 | init.defaultBranch 与远端 HEAD 对齐 |

## 101. bisect 实录

```text
现象：构建偶发失败，近 300 提交内引入。
$ git bisect start
$ git bisect bad HEAD
$ git bisect good v1.4.9.30
Bisecting: 150 revisions left
$ ./gradlew :app:compileDebugKotlin   # 测试中点
$ git bisect good
Bisecting: 75 left …（共 8 轮）
<sha> is the first bad commit
$ git bisect reset
$ git show <sha> --stat               # 定罪证据
```

自动化版：`git bisect run ./scripts/check.sh`。

## 102. submodule 实录

```bash
git submodule add https://…/kit.git libs/kit
git commit -m "chore(vendor): 引入 kit 子模块"
# 同事克隆后：
git submodule update --init --recursive
# 升级子模块：
cd libs/kit && git switch main && git pull
cd ../.. && git add libs/kit          # 记录新 sha
git commit -m "chore(vendor): bump kit"
```

## 103. worktree 实录

```bash
# 热修不打断当前工作：
git worktree add ../hotfix-123 hotfix/123
cd ../hotfix-123    # 修+测+推
cd - && git worktree remove ../hotfix-123
# 对照两个版本调试：
git worktree add ../v1.4 v1.4.9
```

## 104. LFS 实录

```bash
git lfs install                       # 一次
git lfs track "*.psd"                 # 写入 .gitattributes
git add .gitattributes
git add design.psd && git commit …    # 正常流（内容走 LFS）
git lfs ls-files                      # 清单
# 迁移历史大文件：
git lfs migrate import --include="*.psd" --everything
```

## 105. filter-repo 实录

```bash
pip install git-filter-repo
git filter-repo --path secrets/ --invert-paths    # 删目录史
git filter-repo --replace-text expressions.txt    # 批量替换字符串
# 强制全员重新 clone（旧 .git 作废）；锁文件/保护规则重新配
```

## 106. 总卡

```text
模型   : 三区（1）/ 对象=快照（22/95）
检视   : status/diff 三口径/log --graph（2/47）
提交   : add -p + type(scope): …（3/24）
撤销   : restore / reset --soft / revert（4/51）
分支   : switch -c + 短命（5/23）
合并   : 冲突=两意图（6/35）；大冲突分桶（53）
变基   : 只理未推（7/82）
救援   : reflog（17）一切后悔药
安全   : token 最小 scope（33/99）
团队   : PR 小而绿（25/46）
```

## 快速索引 · 续（107-118 节）

| 想找什么 | 去哪一节 |
| --- | --- |
| 附·命令速查（状态类） | 107. 速查·状态 |
| 附·命令速查（协作类） | 108. 速查·协作 |
| 附·决策表合集 | 109. 决策表合集 |
| 附·常见事故对照 | 110. 事故对照 |
| 附·新人 20 问 | 111. 新人 20 问 |
| 附·进阶 10 问 | 112. 进阶 10 问 |
| 附·CI 与 git | 113. CI 联动 |
| 附·changelog 联动 | 114. changelog |
| 附·多仓锁治理 | 115. 锁治理 |
| 附·知识库结构说明 | 116. 结构说明 |

## 107. 速查·状态

| 场景 | 一行 |
| --- | --- |
| 现在有什么没提交 | git status -sb |
| 暂存了什么 | git diff --cached --stat |
| 工作区改了什么 | git diff --stat |
| 最近的提交 | git log -1 --stat |
| 这个文件被谁改过 | git blame path \| head -30 |
| 分支落后远程多少 | git rev-list --count HEAD..origin/main |
| 领先远程多少 | git rev-list --count origin/main..HEAD |
| 有没有 stash 忘了 | git stash list |

## 108. 速查·协作

| 场景 | 一行 |
| --- | --- |
| 同步远端不动工作区 | git fetch --prune |
| 拉平主干 | git pull --rebase origin main |
| 给同事看我的分支 | git push -u origin name |
| 帮同事调试他的分支 | git fetch origin && git switch coworker/feat |
| 分支同步给测试 | 打 tag 推送（10 节） |
| 撤销刚才的 push（未扩散） | revert + push（唯一路） |

## 109. 决策表合集

| 决策 | 表 |
| --- | --- |
| merge or rebase | 79 节树 |
| 保留拓扑 or 线性 | 23 节策略 |
| squash 与否 | 25 节 PR 约定 |
| revert or reset | 4 节矩阵 |
| force 与否 | 7 节纪律（默认否） |
| tag 类型 | 10 节（发布附注） |
| clone 形态 | 70 节（按需浅克隆） |

## 110. 事故对照

| 事故 | 第一动作 | 恢复节 |
| --- | --- | --- |
| reset --hard 过头 | git reflog | 17 |
| rebase 后悔 | --abort（进行中）/ reflog（已完成） | 77 |
| merge 后悔 | reset --hard ORIG_HEAD | 83 |
| 分支误删 | reflog 找尾提交 | 77 |
| push 错分支 | revert + push | 4 |
| 提交了密钥 | 换 token → filter-repo | 33/99 |
| stash 丢了 | fsck --lost-found | 17 |
| 标签打错 | tag -d + 重打 | 10 |

## 111. 新人 20 问

| 问 | 节 |
| --- | --- |
| 我该什么时候 commit | 3/24（小步） |
| add -A 和 add . 差别 | 都全量；. 仅当前目录起 |
| 怎么只提交一半改动 | add -p |
| 为什么 push 不上去 | 84 |
| PR 和 branch 关系 | 25 |
| review 意见怎么改 | 同分支追加提交 |
| main 可以直接推吗 | 团队约定（46 默认禁） |
| detached HEAD 怎么办 | 100 |
| stash 什么时候用 | 9（临时切走） |
| tag 什么时候打 | 28（发布） |
| .gitignore 何时生效 | 19 |
| fork 和 clone 差别 | 45 |
| 为什么有冲突 | 必看（两意图） |
| 冲突文件里三段什么意思 | 6（本方/分隔/对方） |
| revert 和 reset 差别 | 4 |
| 别人的分支怎么拿 | 108 |
| 怎么看历史某天 | 87 |
| 提交消息写多长 | 24（标题 50 内+正文） |
| CI 和 git 什么关系 | 113 |
| 什么能 force push | 只自己短命分支 |

## 112. 进阶 10 问

| 问 | 节 |
| --- | --- |
| 换行符治理 | 68 |
| 签名提交 | 64 |
| 部分克隆 | 70 |
| bundle 离线 | 78 |
| rerere 自动解冲突 | 67 |
| range-diff 比两轮 patch | 78 |
| filter-repo 批量改史 | 105 |
| worktree 双版本 | 103 |
| plumbing 自制工具 | 81 |
| 包与 gc | 93/34 |

## 113. CI 联动

- 本仓库：push main 终验 + pull_request（base main/feat/**）全门禁。
- 触发去重：PR 分支只由 pull_request 事件覆盖（语义=含与 main 的合并树）。
- 提交消息规范由 guard-rails 校验（46 节）；changelog 片段随 PR（114 节）。
- 改 CI 的提交要过 workflow-yaml 门禁（YAML 严格解析）。

## 114. changelog

- 本仓库通道：.changelog/unreleased/ 片段（文件名时间戳排序）。
- 片段规范：面向用户列表项（- 新增/修复 …）；≤32KB；空文件拒。
- 发布时聚合进 changelog.json（三落点：Release/发布仓/APK assets）。
- skip-changelog 标签仅纯内部变更。

## 115. 锁治理

- 兄弟 kit 仓按 sha 锁（gradle/*.lock）；上游 main 绿提交才 bump。
- bump PR 引用上游 PR 号；两仓 CI 互检（consumer-check）。
- 缺锁回退 main 只限 CI 验证；发布流水线 fail-closed。

## 116. 结构说明

本库 116 节构成：

| 段 | 节 |
| --- | --- |
| 模型/检视 | 1-2, 22 |
| 提交/撤销 | 3-4, 38-39, 51 |
| 分支/合并/rebase | 5-7, 35, 37, 48 |
| 远程/团队 | 11-12, 23-31, 41, 45-46, 56 |
| 高级区 | 13-22, 32-34, 61-66, 71-74 |
| 速查大全 | 42, 47-50, 57-60, 75, 97-112 |
| 实录 | 77, 101-105 |
| 本仓库特化 | 46, 113-115 |

版本：v1。

## 快速索引 · 续（117-124 节）

| 想找什么 | 去哪一节 |
| --- | --- |
| 状态命令 30 条 | 117. 三十·状态 |
| 历史命令 30 条 | 118. 三十·历史 |
| 恢复命令 20 条 | 119. 二十·恢复 |
| 协作命令 20 条 | 120. 二十·协作 |
| 速记口诀 | 121. 口诀 |
| 场景→命令总表 | 122. 总表 |
| 版本变更记录 | 123. 版本 |
| 结语·使用顺序 | 124. 结语 |

## 117. 三十·状态

| # | 需求 | 命令 |
| --- | --- | --- |
| 1 | 当前分支名 | git branch --show-current |
| 2 | 紧凑状态 | git status -sb |
| 3 | 改动统计 | git diff --stat |
| 4 | 暂存统计 | git diff --cached --stat |
| 5 | 未跟踪清单 | git ls-files -o --exclude-standard |
| 6 | 近 5 提交 | git log --oneline -5 |
| 7 | 图形历史 | git log --oneline --graph --all -20 |
| 8 | 单文件史 | git log --oneline -- path |
| 9 | 行归属 | git blame -L n,m path |
| 10 | 提交详情 | git show sha |
| 11 | 两提交差异 | git diff a b |
| 12 | 分支独有 | git log main..feat --oneline |
| 13 | 分叉点 | git merge-base a b |
| 14 | 描述位置 | git describe |
| 15 | 标签列表 | git tag -l |
| 16 | 某标签内容 | git show v1.4 |
| 17 | stash 清单 | git stash list |
| 18 | 引用移动史 | git reflog -10 |
| 19 | 提交总数 | git rev-list --count HEAD |
| 20 | 根提交 | git rev-list --max-parents=0 HEAD |
| 21 | 内容搜索史 | git log -S "text" |
| 22 | 正则搜索史 | git log -G "re" |
| 23 | 作者过滤 | git log --author=x |
| 24 | 时间过滤 | git log --since="3 days" |
| 25 | 只看合并 | git log --merges |
| 26 | 短日志统计 | git shortlog -sn |
| 27 | 索引清单 | git ls-files |
| 28 | 悬空对象 | git fsck --lost-found |
| 29 | 对象体积 | git count-objects -vH |
| 30 | 目录树 | git ls-tree HEAD | head -30 |

## 118. 三十·历史

| # | 需求 | 命令 |
| --- | --- | --- |
| 1 | 历史版本文件 | git show HEAD~3:path |
| 2 | 恢复到历史版 | git restore --source=HEAD~3 path |
| 3 | 游离看全仓 | git switch --detach HEAD~3 |
| 4 | 检出某标签 | git switch --detach v1.4 |
| 5 | 找删除文件的提交 | git log --diff-filter=D -- path |
| 6 | 找重命名链 | git log --follow -- path |
| 7 | 每提交文件动作 | git log --name-status -5 |
| 8 | 词级 diff | git diff --word-diff |
| 9 | 历史区间 | git log a..b |
| 10 | 排除某分支 | git log main --not origin/x |
| 11 | 第一个坏提交 | git bisect run script（101 实录） |
| 12 | cherry 标记 | git cherry main feat |
| 13 | 等价补丁剔除 | git log --cherry-pick --right-only a...b |
| 14 | 环比两序列 | git range-diff a..b c..d |
| 15 | 自定义输出 | git log --pretty=format:"%h|%an|%s" |
| 16 | 带分支装饰 | --decorate=short |
| 17 | 只看消息首行 | --oneline |
| 18 | 提交所在分支 | git branch --contains sha -a |
| 19 | 合并进 main 的清单 | git branch --merged main |
| 20 | 尚未合并清单 | git branch --no-merged |
| 21 | 追溯一行演变 | git blame --reverse path |
| 22 | 子模块某版本 | git ls-tree HEAD libs/kit |
| 23 | 引用日志找 sha | git reflog \| grep "commit:" |
| 24 | ORIG_HEAD | 大操作前位置 |
| 25 | 提交时间戳 | git log --format="%ci %s" |
| 26 | 按目录过滤 | git log -- dir/ |
| 27 | 修改类过滤 | --diff-filter=M |
| 28 | 统计增删行 | git log --numstat -3 |
| 29 | 显示签名 | --show-signature |
| 30 | 图形化工具 | gitk / IDE 历史面板 |

## 119. 二十·恢复

| # | 场景 | 动作 |
| --- | --- | --- |
| 1 | 改动没 add 想丢 | git restore path |
| 2 | 全部丢 | git restore . |
| 3 | add 了想退 | git restore --staged path |
| 4 | 想丢且已 add | --staged 再 restore |
| 5 | 提交错了想撤（未推） | reset --soft HEAD~1 |
| 6 | 撤且连改动丢 | reset --hard HEAD~1（reflog 可救） |
| 7 | 已推撤 | git revert HEAD |
| 8 | 撤多个 | git revert OLD^..NEW |
| 9 | 撤合并 | revert -m 1 sha |
| 10 | reset 过头 | reflog → reset --hard 旧 |
| 11 | 分支删了 | reflog → branch name sha |
| 12 | stash pop 冲突 | 冲突解后 stash 自动 drop；没 drop 的可复用 |
| 13 | stash 丢了 | fsck --lost-found |
| 14 | rebase 中断续 | rebase --continue |
| 15 | rebase 全撤 | rebase --abort |
| 16 | merge 全撤 | merge --abort |
| 17 | merge 完了后悔 | reset --hard ORIG_HEAD |
| 18 | amend 错了 | reflog 找 amend 前 |
| 19 | checkout 弄乱想回 | git switch -（上一位置） |
| 20 | 生成物污染想净 | git clean -fd（-n 先看） |

## 120. 二十·协作

| # | 场景 | 动作 |
| --- | --- | --- |
| 1 | 首推分支 | push -u origin name |
| 2 | 同步远程引用 | fetch --prune |
| 3 | 拉平主干到本地 | pull --rebase origin main |
| 4 | 看远程新提交 | log HEAD..origin/main --oneline |
| 5 | 删远程分支 | push origin --delete name |
| 6 | 改远程地址 | remote set-url |
| 7 | 加第二个远程 | remote add upstream url |
| 8 | fork 同步上游 | fetch upstream + rebase |
| 9 | 推标签 | push origin v1.x |
| 10 | 删远程标签 | push origin :refs/tags/v1.x |
| 11 | 带子模块克隆 | clone --recurse-submodules |
| 12 | 子模块初始化 | submodule update --init --recursive |
| 13 | 锁定提交给同事 | 打 tag 或推分支 |
| 14 | 只拉某分支 | fetch origin feat:local |
| 15 | 查看同事分支 | fetch 后 switch |
| 16 | PR 更新 | 同分支追加 commit |
| 17 | 离线传输 | bundle create/clone |
| 18 | 归档快照 | git archive HEAD |
| 19 | 换行统一 | .gitattributes（68 节） |
| 20 | 大文件治理 | LFS track（104 节） |

## 121. 口诀

```text
先看后动（2），小步提交（3），
撤销分层（4），已推必 revert，
分支短命（23），历史未推才可整（7），
冲突即意图（6），reflog 是后悔药（17），
tag 附注（10），锁仓按 sha（115）。
```

## 122. 总表

场景 → 节号（0-9 前缀表示必看/结构节）：

| 场景 | 节 |
| --- | --- |
| 任何 git 动作前 | 2（三连） |
| 提交 | 3, 24 |
| 撤销 | 4, 51 |
| 分支 | 5, 48 |
| 合并冲突 | 6, 35, 53 |
| rebase | 7, 82 |
| stash | 9 |
| tag | 10, 28 |
| 远程 | 11, 49 |
| pull 策略 | 12 |
| submodule/worktree | 13, 14 |
| cherry-pick | 15, 74 |
| bisect | 16, 101 |
| reflog 救援 | 17, 77 |
| LFS | 18, 104 |
| ignore | 19 |
| 配置 | 20, 67 |
| hooks | 21, 80 |
| 内部原理 | 22, 95 |
| 分支策略 | 23 |
| PR | 25, 45 |
| 版本号 | 27, 91 |
| 发布/回滚 | 28, 29, 30 |
| 迁移/瘦身 | 32, 65 |
| 凭证安全 | 33, 99 |
| 性能 | 34, 70 |
| 报错 | 42, 100 |
| 团队 | 41, 46, 85 |
| 新人 | 86, 111 |

## 123. 版本

- v1（当前）：116 节主体 + 117-124 速查附录；命令 200+；剧本 16；实录 6。

## 124. 结语

使用顺序：新任务 → 122 节总表定位 → read 起点节 → 干活 → 回执（96）。
改历史先打 backup；reflog 常备；--force 永远多想一秒。

## 125. 附录·每日一练（7 天）

| 天 | 练 | 验证 |
| --- | --- | --- |
| 1 | 玩具仓做 3 提交 + reset --hard + reflog 救回 | 救回成功 |
| 2 | add -p 拆一个混合提交为 3 个原子提交 | log --stat 复核 |
| 3 | 造冲突（两分支同文件）→ 手解 → merge --abort 重来再解 | 测试通过 |
| 4 | rebase -i squash 5 个碎提交为 1 | backup 分支对照 |
| 5 | bisect 人工注入坏提交后定位 | 首坏定位准确 |
| 6 | cherry-pick 到 release 分支 + 重打 tag | tag 指向正确 |
| 7 | filter-repo 从玩具仓删一个文件的全部历史 | log -- 查无此文件 |

## 126. 附录·本仓库 git 速查

| 场景 | 本仓库口径 |
| --- | --- |
| 提交消息 | type(scope): 摘要 —— 关键词（24 节） |
| 分支命名 | feat/… fix/… chore/… docs/… |
| PR base | main（或 stacked：前一 PR 分支） |
| CI 触发 | PR（base main/feat/**）+ push main 终验 |
| changelog | .changelog/unreleased/ 片段随 PR |
| 锁文件 | gradle/*.lock 按 sha（kit 仓） |
| 发布 | tag v 形态触发 release.yml |
| 规则单源 | AGENTS.md（本库随其演化） |

## 127. 附录·检查清单合集

**提交前**

- [ ] diff 自查（无夹带/无格式化噪音）
- [ ] 消息规范（type(scope): …）
- [ ] 生成物没进（.gitignore 生效）

**推送前**

- [ ] fetch 看增量
- [ ] rebase 整理（未推部分）
- [ ] 测试/构建本地过（但以 CI 为准）

**发布前**（92 节）

**历史整理前**

- [ ] backup 分支已打
- [ ] 确认这段史没推过/没人基于它

## 128. 结语·数据

- 节总数：128
- 命令条目：200+
- 剧本：16
- 实录：6
- 版本：v1

## 129. 附录·命令补遗（40 条）

| # | 场景 | 命令 |
| --- | --- | --- |
| 1 | 看暂存逐块 | git diff --cached -p |
| 2 | 检视某提交的父 | git show sha^ |
| 3 | 两个 tag 间 | git diff v1.4.8 v1.4.9 --stat |
| 4 | 标签含提交清单 | git log v1.4.8..v1.4.9 --oneline |
| 5 | 行数变更统计 | git diff --numstat |
| 6 | 忽略空白比较 | git diff -w |
| 7 | 只看新增文件 | git diff --diff-filter=A --name-only |
| 8 | 检索分支名 | git branch -a \| grep feat |
| 9 | 远程分支清单 | git branch -r |
| 10 | 上次 merge 是谁 | git log -1 --merges |
| 11 | 提交所在 tag | git describe --contains sha |
| 12 | 未推送提交 | git log origin/main..HEAD --oneline |
| 13 | 提交是否已推 | git branch -r --contains sha |
| 14 | 修改上次作者时间 | commit --amend --date=now --reset-author |
| 15 | 保留空目录 | touch dir/.keep && git add |
| 16 | 看对象类型 | git cat-file -t sha |
| 17 | 看打包统计 | git verify-pack -v .git/objects/pack/*.idx \| head |
| 18 | 手动 gc | git gc --prune=now |
| 19 | 重建索引 | git rebase --root（重放全史） |
| 20 | 环境变量一次 | GIT_EDITOR=vim git rebase -i … |

## 130. 附录·终页

```text
git 的三句话：
1. 一切皆指针（分支/tag/HEAD）。
2. 提交是快照树；diff 是事后算的。
3. 只要不改共享历史，怎么折腾都有后悔药（reflog）。
```

版本：v1 · 130 节 · 240+ 命令 · 使用回执文化见 96 节。

## 131. 附录·跨库公共约定

- 结构三段式：必看 → 快速索引 → 编号章节；索引覆盖全部章节。
- 坑三要素：症状 → 根因 → 正解。
- 仓库强相关纪律随 AGENTS.md 同步演化。
- 使用回执：救场节点名 / 错漏带节号 48h 修订。

## 132. 附录·术语补遗

| 术语 | 含义 |
| --- | --- |
| refspec | 引用映射（src:dst，push/fetch 用） |
| prereceive hook | 服务端推送校验 |
| alternates | 共享对象库（省空间） |
| partial clone | 按需取 blob（--filter） |
| promisor remote | partial clone 的承诺方 |
| commit-graph | 加速 commit 查询的索引 |
| multi-pack-index | 多包索引（大仓加速） |
| sparse-checkout | 只检出部分目录 |
| clone --bundle | 从 bundle 恢复 |
| branch.<name>.merge | 上游配置项 |

## 133. 附录·性能进阶

| 技术 | 启用 |
| --- | --- |
| commit-graph | git config core.commitGraph true |
| multi-pack | git multi-pack-index write |
| untracked cache | git config core.untrackedCache true |
| fsmonitor | git config core.fsmonitor true（大仓观感翻倍） |
| sparse-checkout | git sparse-checkout set dirs… |

## 134. 附录·数据

- 节总数：134
- 命令条目：240+
- 剧本：16 + 每日一练 7
- 实录：6
- 版本：v1

## 135. 附录·高频组合拳

| 目标 | 组合 |
| --- | --- |
| 保存现场切走再回 | stash -u → switch … → switch - → stash pop |
| 把 WIP 变正式提交 | add -p → commit（24 节消息） |
| 主干同步并线性化 | fetch → rebase → push |
| 撤销已推合并 | revert -m 1 → push → 复查 |
| 热修回两个分支 | cherry-pick ×2 → tag 重打 |
| 定位回归 | bisect start/good/bad → reset |
| 找回一切 | reflog → branch rescue |
| 整理未推史 | backup 分支 → rebase -i → 全测 → push |

## 136. 结语

- 使用顺序：134 节数据页无内容需跳过；新任务查 122 节总表。
- 一切改历史动作前问自己：backup 打了吗？这段推过吗？
- reflog 是后悔药，gc 是保质期 —— 别考验它。
- 版本 v1 · 136 节 · 240+ 命令 · 剧本 16 · 实录 6 · 口诀 1 · 总表 1 · 使用回执见 96 节

## 137. 附录·速用直达

- 状态三连：`git status -sb` → `git diff --stat` → `git log --oneline -5`
- 撤销三连：`git restore path` / `git restore --staged path` / `git revert HEAD`
- 救援三连：`git reflog` → `git branch rescue HEAD@{N}` → 核对
- 提交三连：`git add -p` → `git commit -m "type(scope): …"` → `git push`

## 138. 附录·致谢与关联库

- 跨域工作流（工具选择/长任务/汇报）→ all_rounder 库
- 前后端工程实践 → full_stack / frontend 库
- 各语言深水区 → 对应语言专家库
- 本库专注版本管理与团队协作（137 节直达为入口）
