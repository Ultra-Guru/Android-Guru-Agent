<!-- pr: fix-stream-contract-response-complete -->
- 修复 Agent 多轮循环中回复气泡「永久转圈」且两轮文字粘连成一条消息的问题（引擎事件流契约：每轮流式输出必有完成收尾）
- 覆盖肌肉记忆旁路、BUILD 催促续跑、GOAL 目标续跑三条路径，并在 UI 层加兜底收口防线
