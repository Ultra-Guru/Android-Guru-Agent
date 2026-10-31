<!-- pr: role-experience-git-skills-github-mcp -->

- 角色经验卡：每次任务结束自动提炼一条结构化经验（场景 / 工具序列 / 结果 / 下次注意），确定性规则引擎生成而非提示词发挥；同类任务开工时检索注入 top-3 相关经验（含 hitCount 强化信号，被复用的经验更易再次命中），跨会话越用越准
- 模板工坊点击任意角色模板打开详情面板：查看该角色的经验卡列表（可单删 / 清空）+ 激活入口；行点击从「直接激活」升级为「先看再激活」
- Git 角色专属技能（9 套，默认全开）：git-advanced-workflows / git-advanced / git-workflows / github-actions-advanced / sync-repos / relay-merge / gh-cherry-pick / github-multi-repo / github-release-management —— 每套为阶段化 runbook（命令模板 + 逐阶段验证 + 安全护栏），上游社区仓库逐一标注；模板工坊 → Git 角色详情可逐个开关，下轮请求即生效
- 输入框 GitHub 图标新增「GitHub MCP 配置」：内置 GitHub MCP（进程内 7 工具）与官方远程 GitHub MCP（api.githubcopilot.com 全量工具面）双服务器启停 + 连接态同屏管理；官方服务器支持工具集订阅（repos / issues / pull_requests / actions 等 10 集，全选 = 服务器默认），勾选即重连生效；PAT 经令牌桥零落盘
