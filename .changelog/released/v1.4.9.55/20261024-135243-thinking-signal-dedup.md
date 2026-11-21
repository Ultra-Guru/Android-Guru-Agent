<!-- pr: feat-thinking-engine-quality -->
- 思考引擎 AUTO 选档的文本信号词表与计分核心收敛为单一事实源（ThinkingSignalAnalyzer）：Agent 与 Coding 两个选档器共用一份词表/权重/理由拼装，消除跨模块双份维护的漂移风险；选档行为与决策理由文案保持不变，既有 30+ 选档测试全数锁定
- ThinkingLevel 提示词回退改为直接委托 ThinkingProfile 画像表（主路径与回退字面同源）；清除全仓零调用的 toThinkingBudget / toReasoningEffortName 死方法，AgentConfig 净减约 70 行重复文本
- 新增 ThinkingSignalAnalyzer 专项单测：四维度检测与标签、引擎专有维度插入位、短平快标注、理由拼装格式、纯函数确定性全覆盖
