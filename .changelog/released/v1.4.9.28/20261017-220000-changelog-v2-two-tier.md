# 更新日志通道 v2 —— 两级展示 + 片段消费闭环

- 更新日志弹窗分两级：一级置顶展示管理软件（apex-Administrator 发布器 App）发送的更新日志要点；下滑二级区为 PR 合并内容（新增功能 / 修复介绍），每组一条 PR 摘要 —— 两级互不冲突，同时可空可满
- 片段元数据化：文件头 `<!-- source: publisher -->` 标记来源（发布器 App 发送）；`<!-- target: X.Y.Z -->` 标记目标版本，构建版本未到时片段缓发布（不收录不消费，跨版本滞留到未来版本）
- changelog.json 升级 apex-changelog-v2：新增 pr 分组字段（旧客户端自动忽略，前向兼容；旧清单无 pr → 新客户端渲染自动跳过，后向兼容）
- 修复 v1 缺陷：发布后 `.changelog/unreleased/` 片段从不消费 → 下一版重复聚合旧片段；现在发布流水线自动归档到 `.changelog/released/<tag>/` 并清空对应 unreleased（[skip ci] 回推 main，三次重试防并行竞态）
- 发布器 App 支持目标版本选择：填完 Token 自动拉取线上最新版本（发布仓库 version.json）与下一版预告（gradle versionName），可写下一版或未来版本的日志
