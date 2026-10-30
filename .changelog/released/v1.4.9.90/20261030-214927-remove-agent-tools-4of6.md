<!-- pr: remove-agent-tools-4of6 -->

- 附件管线整域移除：`attachment/` 三件（清理 Worker / 图片转换 / 预拷贝）、`AttachmentModule` DI、`AttachButton` 组件、分享接收 `SharedIntake` + Manifest SEND intent-filter（消费方 Agent 输入区已删，载荷无人订阅）
- Agent CUSTOM 模式预设工具删除：AgentModePresetList / AgentModePresetSelect + 别名 + 风险/注解/环检白名单口径同步收口（预设编辑器与 AgentSettings 字段由设置域 PR 收口）
- 存储页附件沙箱区块移除（用量/清空入口），保留 Ubuntu / 会话历史 / 运行日志三区
- 字符串双语清理：storage 附件 7 键 + chat 附件 4 键 + ViroPet 孤儿注释头；LogCategory 删 ATTACHMENT
- 红线确认保留：AskUser / AskUserChoice（Coding 深潜线挂起提问 + 结构化提问卡在用）、AgentSettingGet/Set 等 v5 自主设置工具（模型自调配置）、AgentRole 模型路由绑定工具与角色数据模型（Coding 专家模板基座）、chatkit 全部组件（CodeScreen 消费）
