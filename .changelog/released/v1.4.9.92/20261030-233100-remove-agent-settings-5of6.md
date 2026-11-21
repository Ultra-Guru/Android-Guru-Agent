<!-- pr: remove-agent-settings-5of6 -->

- 设置中心「Agent 配置」大分类移除：Agent 执行分区（默认模式/思考档位/迭代上限/重试与循环防护/高级行为）、角色管理分区（内置全能角色 + activeRoleId 激活链路）、模式预设编辑器（ModePresetEditorSection）、LOOP 循环默认值分区整删；分类收敛为「工作台」（Coding/子代理/权限/规则/钩子/压缩/视觉/目标/GitHub/记忆十叶）
- 迁移保功能：Keep Alive 开关迁入「终端 → 后台服务」（服务本体是终端运行时宿主）；聊天记忆摄取开关迁入「工作台 → 记忆」；单轮并行执行上限迁入「工作台 → Coding」；Coding 专家角色编辑器（AgentRoleEditorDialog + 另存为副本）独立成文件供模板工坊 Coding 页签复用
- AgentSettings 字段收敛：defaultMode/thinkLevel/thinkingLevelOverride/forceDeepThinking/maxIterations/重试与循环防护/反思规划/CUSTOM 预设/角色激活/任务总结卡/LOOP 与 goalAutoResume 等 Agent 聊天专属字段删除（存量 JSON 由 ignoreUnknownKeys 静默吞掉，升级零迁移）；AgentConfig 引擎侧遗留字段（reflectionRounds/customInstruction）随最后消费方删除；AgentSettingSet 工具白名单同步收敛（GOAL 执行模式合法值补全）
- 字符串双语清理：settings_nav Agent 13 键、模式/思考/重试循环/高级行为 ~40 键、mode_presets 25 键、LOOP 默认值 10 键、chat 模式描述 7 键、任务总结卡 2 键（values/ 与 values-zh/ 镜像同步）；限速一键组合与记忆隐私文案同步修正
- 红线确认保留：Coding 全部设置（思考逻辑/档位/执行模式/工具面/读守卫/规则）、通用设置（模型/主题/权限/备份/更新/通知/语言）、Terminal 设置（Ubuntu 运行时宿主）、记忆/市场/技能/MCP/GitHub/视觉/子代理设置全保留
