package com.apex.agent.platform.terminal.di

import com.apex.agent.platform.terminal.runtime.TerminalRuntime
import com.apex.agent.platform.terminal.tools.v2.TerminalBackendsTool
import com.apex.agent.platform.terminal.tools.v2.TerminalCloseTool
import com.apex.agent.platform.terminal.tools.v2.TerminalCreateTool
import com.apex.agent.platform.terminal.tools.v2.TerminalObserveTool
import com.apex.agent.platform.terminal.tools.v2.TerminalResizeTool
import com.apex.agent.platform.terminal.tools.v2.TerminalRunTool
import com.apex.agent.platform.terminal.tools.v2.TerminalSignalTool
import com.apex.agent.platform.terminal.tools.v2.TerminalSnapshotTool
import com.apex.agent.platform.terminal.tools.v2.TerminalUbuntuInstallTool
import com.apex.agent.platform.terminal.tools.v2.TerminalWaitTool
import com.apex.agent.platform.terminal.tools.v2.TerminalWorkspacesTool
import com.apex.agent.platform.terminal.tools.v2.TerminalWriteTool
import com.apex.agent.platform.terminal.tools.legacy.LegacyCloseTool
import com.apex.agent.platform.terminal.tools.legacy.LegacyExecTool
import com.apex.agent.platform.terminal.tools.legacy.LegacyListTool
import com.apex.agent.platform.terminal.tools.legacy.LegacyReadTool
import com.apex.agent.platform.terminal.tools.legacy.LegacySendTool
import com.apex.agent.platform.terminal.tools.legacy.LegacySignalTool

/**
 * Tool registration guide for `app/.../di/ToolModule.kt`.
 *
 * Spec ref: ATR 2.0 Final Spec §44.4 (ToolModule REWRITE) / §46 (TerminalExecLegacy id collision fix)
 *
 * This is a GUIDE — the real repo's ToolModule.kt is an Hilt module that registers AgentTools
 * into the ToolRegistry. Below is the target registration block (pseudocode using the repo's
 * `registry.register(...)` pattern).
 *
 * ════════════════════════════════════════════════════════════════════════
 * PHASE 3 TARGET REGISTRATION (replace the old block at ToolModule.kt L139 + L204-209)
 * ════════════════════════════════════════════════════════════════════════
 *
 *   // ── 9 new Agent Terminal tools (preferred) ──
 *   registry.register(TerminalCreateTool(runtime))
 *   registry.register(TerminalRunTool(runtime))
 *   registry.register(TerminalObserveTool(runtime))
 *   registry.register(TerminalWaitTool(runtime))
 *   registry.register(TerminalWriteTool(runtime))
 *   registry.register(TerminalSignalTool(runtime))
 *   registry.register(TerminalResizeTool(runtime))
 *   registry.register(TerminalSnapshotTool(runtime))
 *   registry.register(TerminalCloseTool(runtime))
 *
 *   // ── 6 legacy compat aliases (deprecated, kept for 1 version) ──
 *   registry.register(LegacyExecTool(runtime))      // id="TerminalExecLegacy"     → run+wait+observe
 *   registry.register(LegacyReadTool(runtime))      // id="TerminalReadLegacy"      → observe(RAW)
 *   registry.register(LegacySendTool(runtime))      // id="TerminalSendLegacy"      → write
 *   registry.register(LegacySignalTool(runtime))    // id="TerminalSignalLegacy"    → signal
 *   registry.register(LegacyListTool(runtime))      // id="TerminalListLegacy"      → snapshot(SESSIONS)
 *   registry.register(LegacyCloseTool(runtime))     // id="TerminalCloseLegacy"     → close
 *
 *   // ── DELETED: StreamingTerminalExecTool (id collision with TerminalExecLegacy) ──
 *   // The old line 139 `registry.register(SafeAgentTool(StreamingTerminalExecTool(terminalManager)))`
 *   // is REMOVED. Streaming is now: TerminalRun (returns jobId) + TerminalObserve(afterCursor=...)
 *   // called repeatedly by the Agent. No separate streaming tool needed.
 *
 * ════════════════════════════════════════════════════════════════════════
 * TerminalExecLegacy ID COLLISION FIX (Spec §46)
 * ════════════════════════════════════════════════════════════════════════
 *
 * BEFORE (bug): two classes declared `id = "TerminalExecLegacy"`:
 *   - TerminalExecTool        (platform/terminal/.../tools/)         registered at L204
 *   - StreamingTerminalExecTool (app/.../tools/)                     registered at L139
 *   Whichever registered last won; behavior was non-deterministic across refactors.
 *
 * AFTER (fixed): exactly ONE class owns `id = "TerminalExecLegacy"`:
 *   - LegacyExecTool          (platform/terminal/.../tools/legacy/)  the compat alias
 *   - StreamingTerminalExecTool.kt is DELETED in Phase 4.
 *   - TerminalExecTool.kt (old non-streaming) is REPLACED by LegacyExecTool.
 *
 * The streaming use-case is served by the NEW non-blocking API:
 *   val job = TerminalRun(sessionId, command)          // returns immediately
 *   TerminalObserve(sessionId, RAW, afterCursor=job.startCursor)  // call repeatedly
 *
 * ════════════════════════════════════════════════════════════════════════
 * MIGRATION TIMELINE (Spec §45)
 * ════════════════════════════════════════════════════════════════════════
 *   Phase 3 (this): register 9 new + 6 legacy; Agent Engine can use either.
 *   Phase 4: mark 6 legacy as @Deprecated; delete StreamingTerminalExecTool.kt.
 *   Phase 5: remove 6 legacy entirely (or keep longer if external plugins depend on them).
 */
object ToolRegistrationGuide {

    /** Construct all 9 new tools (call once during Hilt init). */
    fun newTools(rt: TerminalRuntime) = listOf(
        TerminalCreateTool(rt),
        TerminalRunTool(rt),
        TerminalObserveTool(rt),
        TerminalWaitTool(rt),
        TerminalWriteTool(rt),
        TerminalSignalTool(rt),
        TerminalResizeTool(rt),
        TerminalSnapshotTool(rt),
        TerminalCloseTool(rt)
    )

    /**
     * 一次性结构化执行工具（TerminalExec —— ExecRequest/ExecEngine/CommandResult）。
     * 构造需要 ExecEngine（CommandSpawner 通道由 app 层提供：root-su/shizuku/local-sh），
     * 因此不进 newTools(rt)，由 ToolModule 直接注册（同时注入门禁与 cd 记忆钩子）。
     */
    fun execTool(engine: com.apex.agent.platform.TerminalExec.ExecEngine) =
        com.apex.agent.platform.terminal.tools.v2.TerminalExecTool(engine)

    /**
     * T73: 后端能力发现 + Ubuntu rootfs 安装引导 —— Agent 自主进入 Ubuntu 的两个入口。
     * 构造需要 RootfsProvisioner/RootfsTarget（app TerminalModule 提供），
     * 因此不进 newTools(rt)，由 ToolModule 直接注册。
     */
    fun backendTools(rt: TerminalRuntime, provisioner: com.apex.agent.platform.terminal.ubuntu.RootfsProvisioner, target: com.apex.agent.platform.terminal.ubuntu.RootfsTarget) = listOf(
        TerminalBackendsTool(rt),
        TerminalUbuntuInstallTool(provisioner, target)
    )

    /**
     * T75: workspace 管理（list/create/inspect/delete）。构造需要 LinuxWorkspaceManager
     * （app TerminalModule 提供），由 ToolModule 直接注册。
     */
    fun workspaceTools(
        workspaces: com.apex.agent.platform.terminal.workspace.LinuxWorkspaceManager,
        projectEnvironment: com.apex.agent.platform.terminal.environment.ProjectEnvironmentCoordinator
    ) = listOf(
        TerminalWorkspacesTool(workspaces),
        com.apex.agent.platform.terminal.tools.v2.TerminalWorkspaceEnvironmentTool(projectEnvironment)
    )

    /** Construct all 6 legacy compat aliases (call once during Hilt init). */
    fun legacyTools(rt: TerminalRuntime) = listOf(
        LegacyExecTool(rt),
        LegacyReadTool(rt),
        LegacySendTool(rt),
        LegacySignalTool(rt),
        LegacyListTool(rt),
        LegacyCloseTool(rt)
    )

    /** Tool id → spec section, for documentation / introspection. */
    val TOOL_SPEC_MAP: Map<String, String> = mapOf(
        "TerminalCreate" to "§34.1 + T73 backend routing",
        "TerminalRun" to "§34.2",
        "TerminalObserve" to "§34.3",
        "TerminalWait" to "§34.4",
        "TerminalWrite" to "§34.5",
        "TerminalSignal" to "§34.6",
        "TerminalResize" to "§34.7",
        "TerminalSnapshot" to "§34.8",
        "TerminalClose" to "§34.9",
        "TerminalBackends" to "T73（后端能力发现：availability 三态）",
        "TerminalExec" to "exec 包（一次性结构化执行：stdout/stderr/exit_code/duration_ms/truncated + 限长 + ANSI）",
        "TerminalUbuntuInstall" to "T73（Ubuntu rootfs 安装引导）",
        "TerminalWorkspaces" to "T75（workspace 管理：隔离文件区生命周期）",
        "TerminalWorkspaceEnvironment" to "P83（项目感知开发环境：analyze/ensure 工具链闭环）",
        "TerminalExecLegacy" to "§35 (compat → run+wait+observe)",
        "TerminalReadLegacy" to "§35 (compat → observe RAW)",
        "TerminalSendLegacy" to "§35 (compat → write)",
        "TerminalSignalLegacy" to "§35 (compat → signal)",
        "TerminalListLegacy" to "§35 (compat → snapshot SESSIONS)",
        "TerminalCloseLegacy" to "§35 (compat → close)"
    )
}
