package com.apex.agent.platform.memory.ports

import com.apex.agent.platform.privilege.PrivilegeManager
import com.apex.agent.platform.privilege.UiAction
import com.apex.memkit.core.ActionPerformer
import com.apex.memkit.core.model.MacroAction
import javax.inject.Inject
import javax.inject.Singleton

/**
 * UI 动作执行端口 —— 库 [MacroAction] → [PrivilegeManager.executeUiAction]
 * 的类型化映射（宏回放的唯一执行通道）。
 *
 * 旧系统的动作参数是裸字符串、回放侧正则再解析（所谓双保险）；库化后
 * 动作在入库前即已是类型化实例，本适配器只做 sealed → 平台动作的一次
 * 直译，不存在解析失败分支。
 */
@Singleton
class PrivilegeActionPerformer @Inject constructor(
    private val privilegeManager: PrivilegeManager,
) : ActionPerformer {

    override suspend fun perform(action: MacroAction): Boolean = runCatching {
        val result = privilegeManager.executeUiAction(action.toUiAction())
        result.success
    }.getOrDefault(false)

    private fun MacroAction.toUiAction(): UiAction = when (this) {
        is MacroAction.Tap -> UiAction.Click(x, y)
        is MacroAction.Swipe -> UiAction.Swipe(x1, y1, x2, y2, durationMs)
        is MacroAction.Input -> UiAction.InputText(text)
        is MacroAction.Back -> UiAction.Back
        is MacroAction.Home -> UiAction.Home
    }
}
