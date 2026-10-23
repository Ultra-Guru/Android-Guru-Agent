package com.apex.agent.di

import android.content.Context
import com.apex.agent.core.tools.SafeAgentTool
import com.apex.agent.core.tools.ToolRegistry
import com.apex.agent.core.tools.builtin.AppForceStopTool
import com.apex.agent.core.tools.builtin.AppInfoTool
import com.apex.agent.core.tools.builtin.AppInstallTool
import com.apex.agent.core.tools.builtin.AppLaunchTool
import com.apex.agent.core.tools.builtin.AppListTool
import com.apex.agent.core.tools.builtin.AppUninstallTool
import com.apex.agent.core.tools.builtin.ClipboardTool
import com.apex.agent.core.tools.builtin.DeviceInfoTool
import com.apex.agent.core.tools.builtin.GetLocationTool
import com.apex.agent.core.tools.builtin.GetTimeTool
import com.apex.agent.core.tools.builtin.InputTextTool
import com.apex.agent.core.tools.builtin.LogcatTool
import com.apex.agent.core.tools.builtin.MediaControlTool
import com.apex.agent.core.tools.builtin.NotificationReadTool
import com.apex.agent.core.tools.builtin.ScreenshotTool
import com.apex.agent.core.tools.builtin.SettingsTool
import com.apex.agent.core.tools.builtin.UiDumpTool
import com.apex.agent.core.tools.builtin.UiNotificationsTool
import com.apex.agent.core.tools.builtin.UiSwipeTool
import com.apex.agent.core.tools.builtin.UiTapTool
import com.apex.agent.platform.PrivilegeUiProvider
import com.apex.agent.platform.privilege.PrivilegeManager

/**
 * # Device Tools — Tool Registration（原 ToolModule §5–§8）
 *
 * 自 [ToolModule.provideToolRegistry] 提取的设备类工具注册段（应用管理 /
 * 系统控制 / UI 操作 / 传感器，19 个工具），与既有
 * [ToolModuleJvmToolsRegistration] / [ToolModuleRegistrySections] /
 * [ToolModuleV5Registration] / [ToolModuleAppControlRegistration] 同款
 * SRP 拆分模式 —— 主注册文件贴近 1200 行预算（[scripts/check_file_size.sh]
 * 强制），设备工具族边界天然清晰（共享 shellExec 通道 + 特权链）。
 *
 * 通道说明：
 * - `shellExec` —— 设备类短命令通道（输出不带 via 标记，默认 30s 超时）；
 * - UI 族优先 AccessibilityService 语义交互，shell 为回落；
 * - Screenshot 走特权链（a11y API 30+ → root screencap 回退）。
 */
internal fun registerDeviceTools(
    registry: ToolRegistry,
    shellExec: suspend (String) -> String,
    privilegeUiProvider: PrivilegeUiProvider,
    privilegeManager: PrivilegeManager,
    context: Context
) {
    // ═══ 5. 应用管理 (6) ═══
    registry.register(SafeAgentTool(AppListTool(shellExec)))
    registry.register(SafeAgentTool(AppLaunchTool(shellExec)))
    registry.register(SafeAgentTool(AppInstallTool(shellExec)))
    registry.register(SafeAgentTool(AppUninstallTool(shellExec)))
    registry.register(SafeAgentTool(AppForceStopTool(shellExec)))
    registry.register(SafeAgentTool(AppInfoTool(shellExec)))

    // ═══ 6. 系统控制 (6) ═══
    registry.register(SafeAgentTool(DeviceInfoTool(shellExec)))
    registry.register(SafeAgentTool(SettingsTool(shellExec)))
    registry.register(SafeAgentTool(MediaControlTool(shellExec)))
    registry.register(SafeAgentTool(ClipboardTool(shellExec)))
    registry.register(SafeAgentTool(GetTimeTool()))
    registry.register(SafeAgentTool(LogcatTool(shellExec)))

    // ═══ 7. UI 操作 (5, 优先 AccessibilityService 语义交互) ═══
    registry.register(SafeAgentTool(UiTapTool(shellExec, privilegeUiProvider)))
    // 3-A 协调项：shell 回落分支的 swipe 方向坐标注入真实分辨率
    //（与 PrivilegeUiProvider.getScreenMetrics 同源 —— ApplicationContext
    // displayMetrics；未注入时工具内部保持 1080x2400 假设）。
    registry.register(SafeAgentTool(UiSwipeTool(
        shellExec, privilegeUiProvider,
        screenMetrics = {
            val dm = context.resources.displayMetrics
            dm.widthPixels to dm.heightPixels
        }
    )))
    registry.register(SafeAgentTool(UiDumpTool(shellExec, privilegeUiProvider)))
    // #240 收尾：通知栏 open/close 的 LLM 面（a11y GLOBAL_ACTION / cmd statusbar）
    registry.register(SafeAgentTool(UiNotificationsTool(shellExec, privilegeUiProvider)))
    // Issue #239：screenshot 工具接特权链（无障碍 API 30+ → root
    // screencap+base64 回退）—— 旧行为恒走裸 shell，无障碍开启但无 root
    // 的设备截图永远失败。适配 ScreenshotResult → 工具中立形状。
    registry.register(SafeAgentTool(ScreenshotTool(
        shellExec,
        privilegedScreenshot = {
            val r = privilegeManager.takeScreenshot()
            if (r.success && r.imageBytes != null) {
                ScreenshotTool.PrivilegedScreenshot(r.imageBytes, null)
            } else {
                ScreenshotTool.PrivilegedScreenshot(null, r.error ?: "privileged screenshot failed")
            }
        }
    )))
    registry.register(SafeAgentTool(InputTextTool(shellExec)))

    // ═══ 8. 传感器 (2) ═══
    registry.register(SafeAgentTool(GetLocationTool(shellExec)))
    registry.register(SafeAgentTool(NotificationReadTool(shellExec)))
}
