<!-- source: refactor/remove-agent-chat -->
<!-- target: major -->

## Agent 对话屏与任务页整域移除（Agent 模式移除 2/6）

- **整目录删除**：`ui/screen/agent`（48 文件 —— 对话屏/历史/附件/流水渲染/模式指南/斜杠管线）、`ui/screen/tasks`（任务历史）、`loop/`（LOOP 循环调度）、附件双组件与 ViroPet 桌宠
- **导航收口**：抽屉 Agent/Tasks 目的地移除；默认落地页 → Coding；返回栈语义同步
- **全局状态迁移**：原由 AgentChatViewModel 兼职承载的网络监测/设置快照/GitHub 徽标 → 独立轻量 AppStatusViewModel
- **DI 收口**：AgentConfig 组装、TaskRuntime、Skill 激活链 Provider 移除（Coding 引擎自建配置不受影响）
- **服务收口**：LOOP 调度器随服务启停的接线移除
- **备份域收口**：聊天会话备份/恢复（Agent 会话专属）移除；设置 + Vault 备份通道保持
- **资产清理**：strings_loop 双语删除；338 个孤儿字符串键双语同步清理
