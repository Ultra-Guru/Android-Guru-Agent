package com.apex.agent.ui.screen.code

import com.apex.agent.core.codetools.git.GitCommandRunner
import com.apex.agent.core.codetools.git.GitCommandResult
import com.apex.agent.core.engine.AgentEvent
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import com.apex.workspace.core.WorkspaceExplorerController
import com.apex.workspace.core.contract.GitCommandRunner as KitGitRunner
import com.apex.workspace.core.contract.KitLogLevel
import com.apex.workspace.core.contract.KitLogger
import com.apex.workspace.core.contract.WorkspaceRootProvider
import com.apex.workspace.core.model.FileChangeKind
import com.apex.workspace.core.model.WorkspaceFileEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * apex-kit-workspace 宿主适配层：把主仓库的 git 执行器 / 工作区根 / 日志
 * 三项能力以薄包装注入库控制器，并把引擎工具事件映射为库的文件事件
 * （实时跟随的数据源）。
 *
 * 拆库 E1 迁移期的接线约定（与 apex-kit-browser 的 BrowserKitModule 同构，
 * 但适配均为无状态 lambda —— 无需独立 Gradle 模块）：
 * - GitCommandRunner：宿主 core:code-tools 同名接口逐字段映射（PRoot 沙箱
 *   执行器不变，恒定把激活工作区 bind 为 guest /workspace）；
 * - WorkspaceRootProvider：CodeWorkspaceRoots.activeRoot() 直通；
 * - KitLogger：转发 AppLogger（LogCategory.TOOL，source = "WorkspaceKit"）。
 *
 * 事件映射（ToolCallComplete → WorkspaceFileEvent，工具名用 v5 PascalCase
 * 现役 id，旧 snake_case 名仅作历史会话回放兜底）：
 * - CodeWrite / Write → CREATED；CodeEdit / Edit → MODIFIED；
 * - DeleteFile → DELETED；CopyMoveFile → RENAMED；
 * - CodeGitCommit / CodeGitBranch → GIT_OP（Git 面板与变更标签失效刷新）；
 * - TerminalExec / ShellExecute → COMMAND（改了什么不可静态判定 → 全量刷新兜底）。
 */
internal class WorkspaceKitHolder(
    gitRunner: GitCommandRunner,
    workspaceRoots: com.apex.agent.core.codetools.CodeWorkspaceRoots,
    scope: CoroutineScope,
) {

    val controller: WorkspaceExplorerController = WorkspaceExplorerController(
        gitRunner = KitGitRunner { args, timeoutMs ->
            val r: GitCommandResult = gitRunner.run(args, timeoutMs)
            com.apex.workspace.core.contract.GitCommandResult(r.exitCode, r.stdout, r.stderr)
        },
        rootProvider = WorkspaceRootProvider { workspaceRoots.activeRoot() },
        scope = scope,
        logger = KitLogger { level, source, message, error ->
            // AppLogger 仅 error/fatal 收 Throwable；warn 的异常并入消息文本
            when (level) {
                KitLogLevel.DEBUG -> AppLogger.instance.debug(LogCategory.TOOL, source, message)
                KitLogLevel.INFO -> AppLogger.instance.info(LogCategory.TOOL, source, message)
                KitLogLevel.WARN -> AppLogger.instance.warn(
                    LogCategory.TOOL, source,
                    if (error != null) "$message: ${error.message}" else message,
                )
                KitLogLevel.ERROR -> AppLogger.instance.error(LogCategory.TOOL, source, message, error)
            }
        },
    )

    /** 引擎事件转发（runEngine collect 循环调用；callId 幂等去重在库内完成）。 */
    fun onAgentEvent(event: AgentEvent) {
        if (event !is AgentEvent.ToolCallComplete) return
        val kind = mapKind(event.toolName) ?: return
        controller.pushAgentEvent(
            WorkspaceFileEvent(
                callId = event.callId,
                kind = kind,
                toolId = event.toolName,
                path = if (kind == FileChangeKind.COMMAND || kind == FileChangeKind.GIT_OP) {
                    null
                } else {
                    extractToolPath(event.arguments)
                },
                success = event.success,
                timestampMs = System.currentTimeMillis(),
                durationMs = event.durationMs,
                detail = event.output.lineSequence().firstOrNull()?.take(60),
            ),
        )
    }

    private fun mapKind(toolName: String): FileChangeKind? = when (toolName) {
        "CodeWrite", "Write", "code_write", "write_file" -> FileChangeKind.CREATED
        "CodeEdit", "Edit", "code_edit", "edit_file" -> FileChangeKind.MODIFIED
        "DeleteFile", "delete_file" -> FileChangeKind.DELETED
        "CopyMoveFile", "copy_move_file" -> FileChangeKind.RENAMED
        "CodeGitCommit", "CodeGitBranch",
        "code_git_commit", "code_git_branch" -> FileChangeKind.GIT_OP
        "TerminalExec", "ShellExecute",
        "terminal.exec", "shell_execute" -> FileChangeKind.COMMAND
        else -> null
    }

    /** 从工具参数 JSON 提取 path 字段（与 CodeEventReducer 同规则的自持副本）。 */
    private fun extractToolPath(arguments: String): String? = runCatching {
        Json.parseToJsonElement(arguments).jsonObject["path"]?.jsonPrimitive?.content
    }.getOrNull()?.takeIf { it.isNotBlank() }
}
