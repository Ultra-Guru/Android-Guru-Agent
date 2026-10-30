<!-- source: refactor/remove-agent-engine -->
<!-- target: minor -->

## Agent 聊天交互规格移除：引擎域第一波（Agent 模式移除 3/6）

- **提示词四段删除**：Conversational Openness / First-Turn Greeting 硬约束 / Emotional Attunement 情绪调频 / Vague Request 模糊求助澄清 —— Agent 屏交互规格，Coding 工位从不注入
- **双检测器移除**：SmallTalkDetector（纯问候判定）与 ChatSignalDetector（共情/澄清信号）整文件删除；引擎轮次态（firstGreetingTurn / chatSignal）与填充链同步移除
- **死模式表清理**：chatkit 的 AGENT_SCREEN_MODES（Agent 屏已移除）
- **记忆通道保留**：chatMemoryNote（Remembered About You）不动 —— 记忆系统属全局能力
- **行为零变化**：Coding 引擎实例恒 CODING 工位，被删分支从未执行
