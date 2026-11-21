package com.apex.agent.di

import android.content.Context
import com.apex.agent.core.tools.DefaultToolRegistry
import com.apex.agent.core.tools.SafeAgentTool
import com.apex.agent.core.tools.builtin.TmuxCapturePaneTool
import com.apex.agent.core.tools.builtin.TmuxKillSessionTool
import com.apex.agent.core.tools.builtin.TmuxListSessionsTool
import com.apex.agent.core.tools.builtin.TmuxNewSessionTool
import com.apex.agent.core.tools.builtin.TmuxSendKeysTool
import com.apex.agent.core.tools.builtin.TmuxSwitchSessionTool
import com.apex.agent.core.tools.builtin.TmuxSessionGateway
import com.apex.agent.platform.terminal.tmux.TmuxSessionManager
import com.apex.agent.platform.terminal.tmux.TmuxSessionStore
import java.io.File

/**
 * Tmux 多会话工具注册（Operit 对标：docs/terminal/operit-rikkahub-comparison.md §4.7）。
 *
 * 独立 helper 的动机：ToolModule.kt 逼近 SRP 预算（1200 行）上限，故把 tmux 接线抽到这里，
 * 保持主模块边界干净。
 *
 * core:tool-registry 对 platform:terminal **零 Kotlin import**（TERMINAL_SDK_BOUNDARY.md
 * 毕业前提），故经 [TmuxSessionGateway] 纯 JVM 接缝把 platform 的 [TmuxSessionManager]
 * 适配进 core 的六个 tmux 工具（对齐 TerminalLinuxPackagesTool ← LinuxPackageManager 同款解耦）。
 *
 * shell-exec 接缝由调用方（ToolModule）注入 guest `ExecEngine` / `ProotCommandSpawner`：
 * rootfs 就绪即在 Ubuntu 内驱动 tmux，Android 专有/未就绪回落宿主（tmux 缺失 → 结构化
 * TmuxError 而非崩溃）。
 *
 * 注册面：三个核心（new/switch/list）必注册；三个增补（send/capture/kill）随核心一起注册
 * —— 只创建/切换而不 send/capture 的多会话没有实际价值（Operit §4.7 的价值就在并行交互）。
 */
internal object ToolModuleTmuxRegistration {

    /**
     * 构建 [TmuxSessionManager] 并注册六个 tmux 工具到 [registry]。
     *
     * @param exec  shell-exec 接缝：`(command, timeoutMs) -> TmuxCommandResult`，
     *              由调用方绑定到 guest exec 通道。
     */
    fun register(
        registry: DefaultToolRegistry,
        context: Context,
        exec: suspend (command: String, timeoutMs: Long) -> TmuxSessionManager.TmuxCommandResult
    ) {
        val tmuxManager = TmuxSessionManager(
            executor = exec,
            store = TmuxSessionStore(
                storeFile = File(context.filesDir, "terminal/tmux/sessions.json")
            )
        )

        // 经纯 JVM 接缝把 manager 适配进 core 工具（manager 与 gateway 方法签名一一对应）。
        val tmuxGateway = object : TmuxSessionGateway {
            override suspend fun createSession(name: String, workingDirectory: String?) =
                tmuxManager.createSession(name, workingDirectory)
            override suspend fun switchSession(name: String) = tmuxManager.switchSession(name)
            override suspend fun listSessions() = tmuxManager.listSessions()
            override suspend fun killSession(name: String) = tmuxManager.killSession(name)
            override suspend fun sendKeys(name: String, command: String) =
                tmuxManager.sendKeys(name, command)
            override suspend fun capturePane(name: String, lines: Int) =
                tmuxManager.capturePane(name, lines)
        }

        registry.register(SafeAgentTool(TmuxNewSessionTool(tmuxGateway)))
        registry.register(SafeAgentTool(TmuxSwitchSessionTool(tmuxGateway)))
        registry.register(SafeAgentTool(TmuxListSessionsTool(tmuxGateway)))
        registry.register(SafeAgentTool(TmuxSendKeysTool(tmuxGateway)))
        registry.register(SafeAgentTool(TmuxCapturePaneTool(tmuxGateway)))
        registry.register(SafeAgentTool(TmuxKillSessionTool(tmuxGateway)))
    }
}
