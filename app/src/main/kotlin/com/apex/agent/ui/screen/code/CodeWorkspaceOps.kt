package com.apex.agent.ui.screen.code

import com.apex.agent.R
import com.apex.agent.platform.code.ws.CodeWorkspaceManager

// ─────────────────────────────────────────────────────────────────────────────
// 工作区导入操作（CodeViewModel 的 internal 扩展，God-file 预算拆分）
//
// 组织方式同 CodeGoalController.kt / CodeLongTaskCenterOps.kt：CodeViewModel
// 已贴 1200 行预算红线，工作区管理的「新增面」（SAF 文件夹导入）独立成
// 文件；既有 create/switch/delete/refresh 留在 VM（调用点多、搬移收益小）。
//
// 依赖成员开放 internal：workspaceManager / bindWorkspace（见 VM 字段声明）。
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 导入真实文件夹为工作区（2026-10 用户需求：「选择工作区需要实际去选择
 * 工作文件夹」）。SAF 选择器解码出的宿主路径直入 [CodeWorkspaceManager.importFrom]
 * —— 不复制文件、不落新目录、同目录幂等复用；失败（路径不可达/非法，含
 * 解码失败的空路径信号）弹错误条。
 */
internal fun CodeViewModel.importWorkspaceFromFolder(hostPath: String) {
    val imported = workspaceManager.importFrom(hostPath)
    if (imported == null) {
        showError(languageManager.getString(R.string.code_ws_import_failed))
        return
    }
    bindWorkspace(imported)
}
