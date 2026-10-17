# 更新日志（changelog 通道 v1）

> 面向用户的版本更新日志 —— 每次发版自动聚合进 `changelog.json`，
> App 内升级后首启弹窗展示 + 关于页随时回看。

## 目录结构与生命周期

```
.changelog/
  README.md                          ← 本文档（规范）
  unreleased/                        ← 未发布片段（下一个版本的内容）
    20261017-153000-add-foo.md       ← 发布器 App / PR 流程写入
  v1.4.9.20.md                       ← （可选）发布后的归档快照，CI 不依赖
```

- **写日志的时机：合并 PR 之前**。功能/修复 PR 必须携带一个片段文件，
  `changelog-gate.yml` 门禁会拦（纯内部变更可打 `skip-changelog` 标签豁免）。
- **构建期只读取、只汇总**（`scripts/changelog.py build`），绝不在构建后人工补。
- 片段在 tag 发布时被聚合进 `dist/changelog.json`，随后三落点：
  Release 资产 / 发布仓库 main（客户端 raw 读取点）/ APK assets（离线兜底）。

## 片段怎么写

- 一个文件 = 一个 PR / 一次发布器提交的日志增量；
- 文件名：`数字/字母/._-` 组成的 `.md`（发布器自动用 `yyyyMMdd-HHmmss-标题slug` 前缀，保证多片段按时间排序）；
- 内容：面向用户的 Markdown，**用列表项写要点**（`- 新增 xxx`）——
  弹窗的 notes 就是这些列表项剥壳后的文本；没有列表项时取首行作摘要；
- 单片段 ≤ 32KB、禁止空文件（`scripts/changelog.py validate` 校验）。

示例：

```markdown
- 新增更新日志弹窗：升级后首次启动展示本版更新内容
- 修复部分机型终端字体渲染偏移
```

## 与更新体系的关系

| 通道 | 载体 | changelog 行为 |
| --- | --- | --- |
| 增量补丁 / 全量 APK | 覆盖安装（versionCode 提升） | 升级后首启弹「本次更新内容」（内置 assets 优先，远端同版兜底） |
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

发布器 App（手机端写日志一键提交）见 `Ultra-Guru/apex-changelog-publisher`。
