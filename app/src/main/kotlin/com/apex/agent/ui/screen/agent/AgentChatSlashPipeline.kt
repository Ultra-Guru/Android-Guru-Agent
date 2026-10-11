package com.apex.agent.ui.screen.agent

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 斜杠指令 + 多 chip 流水线执行体（God-file 预算拆分，模式同 AgentChatEventApplier.kt）。
 *
 * 原 [AgentChatViewModel.handleSlashCommand] / [AgentChatViewModel] 多 chip 管线
 * 两个成员整体迁出为同包扩展：两者只依赖 VM 已开放为 internal 的路由上下文 /
 * 流式缓冲 / GitHub 连接信号 / 任务控制器，与 VM 的输入/生命周期职责解耦。
 *
 * 单 chip（`/skill:a ...` 单命令）走 [handleSlashCommand]；输入框内 ≥2 枚技能
 * chip（v5 多选）走 [AgentChatViewModel.handleMultiChipPipeline] —— 斜杠解析器
 * 不认识复合命令，多选后逐 chip 路由、合并 agentPrompt 单轮执行。
 */
internal fun AgentChatViewModel.handleSlashCommand(command: String) {
    // mcpConnected 快照：路由器据此对已连接的 /mcp:<id> 注入「用 mcp_call 调
    // server=<id>」引导提示词（旧实现恒空集，模型面对 MCP 指令只能瞎猜）。
    val result = SlashCommands.handle(
        command,
        githubTokenManager,
        mcpConnected = runCatching { mcpManager.getConnectedServers().toSet() }.getOrDefault(emptySet()),
        // /skill:<id> 路由时同步装备（写入激活存储，本轮提示词即携带方法论）
        skillActivation = skillActivation,
        // Plugin System v2：/plugin:<id> 命中功能性指令时注入定制 prompt
        functionalCommands = unifiedPluginRegistry.functionalSlashCommands()
            .associateBy { it.commandId }
    )

    // 指令会取消上一个流式任务：先清空流式缓冲，防残留文本串入新一轮。
    streamBuffers.reset()

    // 始终追加反馈消息，让用户看到指令被识别 + 当前状态：
    // Skill/连接器/插件指令使用专用横幅（PipelineBanner），其余指令用 System 行。
    _uiState.update { s ->
        s.copy(
            messages = s.messages + result.banner,
            isLoading = result.isLoading,
            currentThinking = "",
            currentResponse = ""
        )
    }

    if (result is SlashCommands.Result.RequestGithubConnect) {
        // 请求 UI 打开 GitHub 连接流程；不进入 Agent 主循环。
        // tryEmit 因为 extraBufferCapacity=1，订阅者存在时一定成功；
        // 即便 UI 尚未订阅（冷启动竞态），缓冲区也会保留一次事件。
        _requestGithubConnect.tryEmit(Unit)
        return
    }

    // Hub 生态门控：路由器对「未运行的 MCP」返回空 agentPrompt（引导去
    // 市场启动）——不进入 Agent 主循环，不向模型发空转提示词。
    val execute = result as SlashCommands.Result.Execute
    if (execute.agentPrompt.isBlank()) {
        _uiState.update { s -> s.copy(isLoading = false) }
        return
    }

    // 记录流水线路由上下文，循环内产生的工具调用会携带对应来源徽章。
    routeContextKind = execute.contextKind
    routeContextName = execute.contextName
    activeBannerId = execute.banner.id

    currentJob = viewModelScope.launch {
        // P0 修复（闪退）：taskController.execute 的互斥拒绝
        // （IllegalStateException：上一轮执行仍在途）发生在作为参数求值时 ——
        // 位于 collectEngineFlowSafely 的 try/catch **之前**，异常冒泡到
        // viewModelScope（无 handler）直接闪退。这里先安全求值，拒绝时转
        // 可读错误气泡（与 executeNormalMessage 的兜底一致）。
        val flow = try {
            taskController.cancel() // 先串行化释放（同 sendMessage）
            taskController.execute(com.apex.agent.core.engine.UserInput.text(execute.agentPrompt))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _uiState.update { s ->
                s.copy(
                    messages = s.messages + AgentUiMessage.Error(
                        message = "执行失败：${e.message ?: e::class.simpleName}",
                        canRetry = true
                    ),
                    isLoading = false
                )
            }
            return@launch
        }
        collectEngineFlowSafely(flow)
    }.apply {
        invokeOnCompletion {
            routeContextKind = null
            routeContextName = null
        }
    }
}

/**
 * v5 多 chip 流水线：输入框内 ≥2 枚技能 chip 时的发送执行体。
 *
 * 斜杠解析器不认识 `/skill:a /mcp:b` 复合命令（单胶囊时代的硬约束），
 * 多选后改走本路径：
 * 1. 逐 chip 经 [SlashCommands.handle] 路由 —— 复用全部既有语义
 *   （skill 装备副作用 / MCP 连接门控 / github 未连接信号 / 逐条横幅）；
 * 2. 被门控拦截的 chip（如未运行的 MCP，agentPrompt 为空）只留横幅提示，
 *   不阻断其余 chip；
 * 3. 有效 chip 的 agentPrompt 合并为一条提示词（附加用户文本），**单轮**
 *   引擎执行 —— 多 skill 装备后系统提示词即携带全部方法论，无需多轮；
 * 4. 路由上下文取首枚有效 chip（工具调用来源徽章）。
 */
internal fun AgentChatViewModel.handleMultiChipPipeline(
    chips: List<PendingPipelineCommand>,
    userText: String
) {
    val mcpConnected = runCatching { mcpManager.getConnectedServers().toSet() }
        .getOrDefault(emptySet())
    // Plugin System v2：多 chip 管线同样携带功能性插件指令贡献快照。
    val functionalCommands = unifiedPluginRegistry.functionalSlashCommands()
        .associateBy { it.commandId }

    val banners = mutableListOf<AgentUiMessage>()
    var githubConnectRequested = false
    val prompts = mutableListOf<String>()
    var contextKind: ToolKind? = null
    var contextName: String? = null

    chips.forEach { chip ->
        val result = SlashCommands.handle(
            chip.toCommandToken(),
            githubTokenManager,
            mcpConnected = mcpConnected,
            skillActivation = skillActivation,
            functionalCommands = functionalCommands
        )
        banners += result.banner
        when (result) {
            is SlashCommands.Result.RequestGithubConnect -> githubConnectRequested = true
            is SlashCommands.Result.Execute -> {
                // 门控拦截（未运行的 MCP 等）：横幅已给出引导，跳过该 chip
                if (result.agentPrompt.isBlank()) return@forEach
                if (contextKind == null) {
                    contextKind = result.contextKind
                    contextName = result.contextName
                }
                prompts += result.agentPrompt
            }
        }
    }

    // 指令会取消上一个流式任务：先清空流式缓冲，防残留文本串入新一轮。
    streamBuffers.reset()

    _uiState.update { s ->
        s.copy(
            messages = s.messages + banners,
            isLoading = prompts.isNotEmpty(),
            currentThinking = "",
            currentResponse = ""
        )
    }

    if (githubConnectRequested) {
        _requestGithubConnect.tryEmit(Unit)
    }
    if (prompts.isEmpty()) {
        // 全部被门控拦截：不进引擎（与单 chip 空 prompt 语义一致）
        _uiState.update { s -> s.copy(isLoading = false) }
        return
    }

    val combinedPrompt = buildString {
        prompts.forEachIndexed { index, prompt ->
            if (index > 0) append("\n\n")
            append(prompt)
        }
        if (userText.isNotBlank()) {
            append("\n\n用户附加要求: ").append(userText)
        }
    }

    routeContextKind = contextKind
    routeContextName = contextName
    activeBannerId = (banners.lastOrNull() as? AgentUiMessage.PipelineBanner)?.id

    currentJob = viewModelScope.launch {
        val flow = try {
            taskController.cancel() // 先串行化释放（同 sendMessage）
            taskController.execute(com.apex.agent.core.engine.UserInput.text(combinedPrompt))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _uiState.update { s ->
                s.copy(
                    messages = s.messages + AgentUiMessage.Error(
                        message = "执行失败：${e.message ?: e::class.simpleName}",
                        canRetry = true
                    ),
                    isLoading = false
                )
            }
            return@launch
        }
        collectEngineFlowSafely(flow)
    }.apply {
        invokeOnCompletion {
            routeContextKind = null
            routeContextName = null
        }
    }
}
