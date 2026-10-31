<!-- source: refactor/remove-agent-engine-3 -->
<!-- target: major -->

## 引擎域 Agent 模式核心移除（Agent 模式移除 3/6）

- **模式档位收敛**：AgentMode 删 CHAT/AGENT/LOOP/SPEC/REFLECTION/HUMAN_ASSIST/CUSTOM 七个 Agent 屏/存量档位，只留编码工位三档 BUILD/PLAN/GOAL（Coding 功能零损失：深潜/标准/织造/信使四线与 Goal 驱动全部保留）
- **整文件删除**：EngineSpecFlow.kt（SPEC 规格）、EnginePromptDelegates.kt（SPEC/Reflection 提示词包装）、assist/ 的 DecisionPointDetector/HumanAssistFlow/ReflectionFlow 三流（含三份测试）、EngineWorkstation.kt（双工位枚举 —— 纯 Coding 软件不再有工位分轨）
- **事件协议收口**：AgentEvent 删 SpecGenerated/SpecAwaitingConfirmation/SpecConfirmed/ReflectionReview 与 ExecutionSpec 载荷；TaskRuntime 与 CodeStreamSession 的对应消费分支同步移除
- **确认通道收口**：spec 确认 deferred/submitSpecConfirmation/abort 收尾三处删除（ConfirmationSink 只剩 PLAN 门；plan/AskUser 通道不动）
- **提示词收口**：模式段（CHAT/AGENT/SPEC/REFLECTION/HUMAN_ASSIST/CUSTOM/LOOP）、身份行双工位分轨、CUSTOM 附加指令段、CHAT 攻略豁免门全部移除；恒为 CODING agent 身份行；任务路由的「引导切换 Coding 工位」改写分支删除（git 工具不可见时 GitHub 车道诚实省略）
- **工具计划收口**：AGENT/LOOP 的 Code/Github 族前缀黑名单隔离（AGENT_EXCLUDED_TOOL_PREFIXES/filterOutCodingTools）删除；市场分级作用域隔离（mcpScopeIsolation）保留
- **遗留字段过渡**：AgentConfig.reflectionRounds/customInstruction 字段保留（消费方在工具/设置域，PR 4/6、5/6 随消费方删除），引擎侧消费点已全部移除
- **刻意保留**（grep 引用为准）：goal/ 整包（CodeGoalController/CodeViewModel/DI 实际消费 —— Coding 目标驱动模式在用）、EngineAskUserFlow 与 AskUser 通道（Coding 计划确认/权限门在用）、ModePresets.kt（设置域消费，PR 5/6 删）
