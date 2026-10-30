<!-- source: refactor/remove-agent-mode -->
<!-- target: patch -->

## 前置重构：共享聊天组件抽离至 ui/chatkit（Agent 模式移除准备 1/6）

- **中立包 ui/chatkit 建立**：把 Coding 屏与设置页/组件层共同消费的聊天 UI 组件从 ui/screen/agent 迁至中立包，为后续整目录删除 Agent 屏做零风险铺垫（纯移动 + 导入重写，零行为变化）
- **迁出组件清单**：ModeSelector（含 AGENT_SCREEN_MODES / CODING_SCREEN_MODES）、RoleSelector、BrainMenuButton / ToolkitRingButton（ToolRef + ToolkitChipsRow）、ChatToolkitStore（OutputFormat / ChatRule）、PendingPipelineCommand、QuestionCard、PlanConfirmationCard、llmConfiguredFlow
- **消费方导入全部改指 chatkit**：screen/code · screen/agent · settings · di · ui/component 五处消费域同步重写导入；同包免导入的 agent 屏文件补显式导入
- **编译修复**：QuestionCard 迁移时 Backdrop 导入误指 ui.glass（应为 kyant backdrop 库）、clickable / OutlinedButton 两项 Compose 导入遗漏、AGENT_SCREEN_MODES 两处使用点缺显式导入、agentModeIcon / agentModeDescription 跨包可见性收正（internal → public）—— 以上全部随本 PR 一并修正
