# 更新日志（changelog 通道 v3）

> 面向用户的版本更新日志 —— 每次发版自动聚合进 `changelog.json`，
> App 内升级后首启弹窗展示 + 关于页随时回看。

## 目录结构与生命周期

```
.changelog/
  README.md                          ← 本文档（规范）
  unreleased/                        ← 未发布片段（下一个版本的内容）
    20261017-153000-add-foo.md       ← 发布器 App / PR 流程写入
  images/                            ← 发布器配图（raw CDN 读取点，永续保留）
    20261024-131500-1.jpg            ← 片段时间戳-序号 命名，与片段同一 commit
  released/<tag>/                    ← 已发布片段归档（跨 run 去重索引）
```

- **写日志的时机：合并 PR 之前**。功能/修复 PR 必须携带一个片段文件，
  `changelog-gate.yml` 门禁会拦（纯内部变更可打 `skip-changelog` 标签豁免）。
- **构建期只读取、只汇总**（`scripts/changelog.py build`），绝不在构建后人工补。
- 片段在 tag 发布时被聚合进 `dist/changelog.json`，随后三落点：
  Release 资产 / 发布仓库 main（客户端 raw 读取点）/ APK assets（离线兜底）。
- 发布成功后片段归档到 `released/<tag>/`（`[skip ci]` 回推），归档索引供
  跨 run 去重 —— 同一片段绝不进两个版本。

## 两级日志与片段标记（v3）

主 App 弹窗分两步展示：**第 1 步「更新日志」**（发布器手动日志，置顶）→
点「下一步」→ **第 2 步「功能变更」**（PR 合并内容分组）。片段走哪一级由
文件头标记决定：

| 标记 | 语义 | 去向 |
| --- | --- | --- |
| `<!-- source: publisher -->` | 发布器 App（apex-Administrator）发送的手动日志 | 一级置顶（notes + images 图廊） |
| `<!-- pr: <slug> -->` | PR / 分支功能片段 | 二级分组，slug 直接作组标题 |
| `<!-- target: 1.4.9.27 -->` | 面向未来版本 | 构建未到 → 缓发布，滞留到目标版本才收录 |
| （无标记） | PR 流程 / Agent 提交的功能片段 | 二级分组（标题回退 Markdown 标题 / 首行 / 文件名 slug） |

**一级判定双通道（v3.1）**：`source: publisher` 显式标记，或片段由 commit
message 含「via 发布器」的提交新增（git 溯源兜底 —— 老版发布器不写标记
也不丢一级置顶）。显式 `pr:` 标记恒二级（优先于溯源推断）。

**配图（v3）**：发布器 App 发送片段可附最多 9 张截图（压缩至最长边
≤1600px / JPEG 85，与片段同一原子 commit 进 `images/`）；正文中的
`![alt](url)` 引用由聚合端提取进 `images` 字段 —— 主 App 弹窗图廊展示，
点开全屏（双指缩放 / 平移）。

**跨版本聚合（App 端 v2.3）**：用户跨多版本升级（含增量补丁链）时，
App 聚合 (已读版本, 本地版本] 区间内**全部版本**的日志 —— 一级要点、
配图、二级分组跨版本并集去重，中间版本的更新一条不丢。

## 片段怎么写

- 一个文件 = 一个 PR / 一次发布器提交的日志增量；
- 文件名：`数字/字母/._-` 组成的 `.md`（发布器自动用 `yyyyMMdd-HHmmss-标题slug`
  前缀，保证多片段按时间排序）；
- 内容：面向用户的 Markdown，**用列表项写要点**（`- 新增 xxx`）——
  弹窗的 notes 就是这些列表项剥壳后的文本；没有列表项时取首行作摘要；
- 单片段 ≤ 32KB、禁止空文件（`scripts/changelog.py validate` 校验）；
- **面向用户公告**（由 Agent 代发时）务必带 `<!-- source: publisher -->`
  标记 —— 否则降为二级功能变更展示。

示例（发布器 / 公告片段）：

```markdown
<!-- source: publisher -->
- 新增更新日志弹窗：升级后首次启动展示本版更新内容
- 修复部分机型终端字体渲染偏移
```

示例（PR 功能片段）：

```markdown
<!-- pr: terminal-real-console -->
- 终端命令改为真实执行：PTY 会话实时上屏
- 控制台与终端入口统一
```

## 与更新体系的关系

| 通道 | 载体 | changelog 行为 |
| --- | --- | --- |
| 增量补丁 / 全量 APK | 覆盖安装（versionCode 提升） | 升级后首启弹「本次更新内容」（远端优先，内置 assets 兜底；跨版本自动聚合区间内全部日志） |
| 热更（data-only） | 数据层 overlay（effectiveVersionCode 提升） | 同样首启弹窗（远端同版内容） |
| 远端领先本地 | 仅提醒不安装 | 弹「发现新版本」：新版日志 + 前往下载页（受设置「自动检查更新」开关控制） |

已读口径：`max(包 versionCode, 已应用热更目标)` 对比 `seen_version_code`
（`apex_changelog_prefs`）—— 与 UpdateCenter 的 effectiveVersionCode 同源，
三条通道统一覆盖，同一版本只弹一次。

## 工具

```bash
# 校验未发布片段（PR 门禁同款）
python3 scripts/changelog.py validate

# 本地预览聚合产物（不联网：--history-file 指定本地底稿）
python3 scripts/changelog.py build --version 1.4.9.20 --code 96 \
  --history-file /tmp/empty.json --out /tmp/changelog.json
```

发布器 App（手机端写日志一键提交，支持配图）见
`Ultra-Guru/apex-Administrator`。
