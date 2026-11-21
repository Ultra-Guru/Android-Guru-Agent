<!-- pr: feat/aga-self-introspection -->

- 新增 Prompt 段级观测与全量输入包转储：PromptSectionList 列出本轮提示词段/token 估算/真值源，PromptDump 按 current/last 导出完整输入包（脱敏默认开启，可选原子落盘 + 审计留痕）
- 深潜线引擎每次组装系统提示词即产出结构化 PromptSnapshot 快照（段/回合/工具/token 估算四线同构形态）；默认无钩子注册时提示词逐字节不变
- 段级钩子 SPI（PromptStageHook：Replace/Append/DropTurns 预算内变异，异常防御式降级为 Pass）为后续上下文治理与激活清单注入提供通道
