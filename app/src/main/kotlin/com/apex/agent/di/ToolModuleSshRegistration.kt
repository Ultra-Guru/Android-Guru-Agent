package com.apex.agent.di

import com.apex.agent.core.tools.DefaultToolRegistry
import com.apex.agent.core.tools.SafeAgentTool
import com.apex.agent.platform.TerminalFs.RemoteSshFilesystem

/**
 * SSH/SFTP 远程文件系统工具注册（Operit 对标 §4.7 明确列缺项：SSH/SFTP 远程 fs）。
 *
 * 独立 helper 的动机：ToolModule.kt 已逼近 SRP 预算（1200 行）上限，故把 SSH
 * 接线抽到这里，保持主模块边界干净（与 [ToolModuleTmuxRegistration] 同款解耦）。
 *
 * 注册面：单个 [com.apex.agent.platform.terminal.tools.v2.TerminalSshFsTool]
 * 覆盖 ping/read/write/append/list/stat/exists/mkdir/remove/move/copy/disconnect/
 * fingerprint 13 个 action —— 与 TerminalFsTool 的 subaction 设计对齐（一个
 * 工具描述远端 fs 全能力，避免工具爆炸污染模型可见契约）。
 */
internal object ToolModuleSshRegistration {

    fun register(
        registry: DefaultToolRegistry,
        fs: RemoteSshFilesystem
    ) {
        registry.register(SafeAgentTool(TerminalToolAdapter(
            com.apex.agent.platform.terminal.tools.v2.TerminalSshFsTool(fs)
        )))
    }
}
