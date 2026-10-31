package com.apex.agent.core.tools.builtin

import com.apex.agent.core.tools.AgentTool
import com.apex.agent.core.tools.EnvironmentAwareTool
import com.apex.agent.core.tools.ToolEnvironmentState
import kotlinx.serialization.json.*

/**
 * UI 交互接口 —— UI 工具可选的语义交互通道。
 *
 * 当 AccessibilityService 就绪时，UI 工具可通过此接口
 * 执行语义化的 UI 操作（手势执行、UI 树捕获），获得更高可靠性和更细粒度的控制。
 * 未就绪时回退到 input 命令方式。
 */
interface UiInteractionProvider {
    /** 是否可用（AccessibilityService 已就绪） */
    val isAvailable: Boolean

    /** 执行手势操作（点击/长按/滑动） */
    suspend fun performGesture(action: GestureAction): String

    /** 获取当前屏幕的结构化 UI 树 */
    suspend fun dumpUiTree(maxDepth: Int = 20): String
}

/**
 * 手势操作描述。
 */
sealed class GestureAction {
    data class Tap(val x: Int, val y: Int) : GestureAction()
    data class LongPress(val x: Int, val y: Int, val durationMs: Int = 1000) : GestureAction()
    data class Swipe(
        val x1: Int, val y1: Int,
        val x2: Int, val y2: Int,
        val durationMs: Int = 300
    ) : GestureAction()
    data class DirectionalScroll(val direction: String, val durationMs: Int = 300) : GestureAction()
    data class Back(val x: Int = 0, val y: Int = 0) : GestureAction()
    data class Home(val x: Int = 0, val y: Int = 0) : GestureAction()
    /** #240：展开通知栏（a11y GLOBAL_ACTION_NOTIFICATIONS / root cmd statusbar）。 */
    data object OpenNotifications : GestureAction()
    /** #240：收起通知栏（a11y BACK 收合 / root cmd statusbar collapse）。 */
    data object CloseNotifications : GestureAction()
}

/**
 * UI点击工具 —— 优先使用 AccessibilityService 语义交互，回退到 input 命令。
 */
class UiTapTool(
    private val shellExecutor: suspend (String) -> String,
    private val uiProvider: UiInteractionProvider? = null
) : AgentTool {

    override val id = "UiTap"
    override val name = "Tap Screen"
    override val description = """
        Tap at specific screen coordinates.
        Use UiDump first to find element positions, or screenshot to see the screen.
        Coordinates are in pixels from top-left corner.
        When accessibility service is active, uses semantic gesture dispatch
        for more reliable interaction.

        Examples:
        - {"x": 540, "y": 1200} - tap center-ish of a 1080x2400 screen
        - {"x": 100, "y": 100} - tap top-left area
    """.trimIndent()

    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "x": {"type": "integer", "description": "X coordinate (pixels)"},
                "y": {"type": "integer", "description": "Y coordinate (pixels)"},
                "long_press": {"type": "boolean", "description": "Long press instead of tap (default: false)"}
            },
            "required": ["x", "y"]
        }
    """.trimIndent()

    override suspend fun execute(arguments: String): String {
        val json = Json.parseToJsonElement(arguments).jsonObject
        val x = json["x"]?.jsonPrimitive?.intOrNull ?: return "Error: 'x' required"
        val y = json["y"]?.jsonPrimitive?.intOrNull ?: return "Error: 'y' required"
        val longPress = json["long_press"]?.jsonPrimitive?.booleanOrNull ?: false

        // 优先使用 AccessibilityService 语义手势
        if (uiProvider?.isAvailable == true) {
            val action = if (longPress) {
                GestureAction.LongPress(x, y)
            } else {
                GestureAction.Tap(x, y)
            }
            return uiProvider.performGesture(action)
        }

        // 回退到 input 命令
        return if (longPress) {
            shellExecutor("input swipe $x $y $x $y 1000")
        } else {
            shellExecutor("input tap $x $y")
        }
    }
}

/**
 * UI滑动工具 —— 优先使用 AccessibilityService 语义交互，回退到 input 命令。
 *
 * @param screenMetrics 可选的真实屏幕分辨率提供者（宽,高 像素）——shell 回落
 * 分支的方向坐标按真实分辨率计算（与 PrivilegeUiProvider.directionToCoords 同款
 * 比例），替代 1080x2400 硬编码；a11y 分支本身经 UiInteractionProvider 走宿主
 * 侧真实分辨率，不依赖此参数。未注入时保持 1080x2400 假设（旧行为）。
 */
class UiSwipeTool(
    private val shellExecutor: suspend (String) -> String,
    private val uiProvider: UiInteractionProvider? = null,
    private val screenMetrics: (() -> Pair<Int, Int>)? = null
) : AgentTool {

    override val id = "UiSwipe"
    override val name = "Swipe Screen"
    override val description = """
        Swipe from one point to another on the screen.
        Use for scrolling, navigating, or gesture-based actions.
        When accessibility service is active, uses semantic gesture dispatch.

        Examples:
        - {"x1": 540, "y1": 1800, "x2": 540, "y2": 600} - scroll up
        - {"x1": 540, "y1": 600, "x2": 540, "y2": 1800} - scroll down
        - {"x1": 900, "y1": 1200, "x2": 100, "y2": 1200} - swipe left
        - {"direction": "up"} - simplified scroll
    """.trimIndent()

    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "x1": {"type": "integer", "description": "Start X"},
                "y1": {"type": "integer", "description": "Start Y"},
                "x2": {"type": "integer", "description": "End X"},
                "y2": {"type": "integer", "description": "End Y"},
                "direction": {"type": "string", "enum": ["up", "down", "left", "right"], "description": "Simplified direction (overrides coordinates)"},
                "duration_ms": {"type": "integer", "description": "Swipe duration in ms (default: 300)"}
            },
            "required": []
        }
    """.trimIndent()

    override suspend fun execute(arguments: String): String {
        val json = Json.parseToJsonElement(arguments).jsonObject
        val direction = json["direction"]?.jsonPrimitive?.content
        val duration = json["duration_ms"]?.jsonPrimitive?.intOrNull ?: 300

        // 优先使用 AccessibilityService 语义手势
        if (uiProvider?.isAvailable == true && direction != null) {
            return uiProvider.performGesture(
                GestureAction.DirectionalScroll(direction, duration)
            )
        }

        if (direction != null) {
            val coords = directionCoords(direction)
                ?: return "Error: Unknown direction"
            val cmd = "input swipe ${coords[0]} ${coords[1]} ${coords[2]} ${coords[3]} $duration"

            if (uiProvider?.isAvailable == true) {
                return uiProvider.performGesture(
                    GestureAction.Swipe(coords[0], coords[1], coords[2], coords[3], duration)
                )
            }
            return shellExecutor(cmd)
        }

        val x1 = json["x1"]?.jsonPrimitive?.intOrNull ?: return "Error: coordinates or direction required"
        val y1 = json["y1"]?.jsonPrimitive?.intOrNull ?: return "Error: 'y1' required"
        val x2 = json["x2"]?.jsonPrimitive?.intOrNull ?: return "Error: 'x2' required"
        val y2 = json["y2"]?.jsonPrimitive?.intOrNull ?: return "Error: 'y2' required"

        if (uiProvider?.isAvailable == true) {
            return uiProvider.performGesture(GestureAction.Swipe(x1, y1, x2, y2, duration))
        }
        return shellExecutor("input swipe $x1 $y1 $x2 $y2 $duration")
    }

    /**
     * 方向 → 滑动坐标：垂直向取 0.75h→0.25h，水平向取 0.8w→0.2w
     * （与 PrivilegeUiProvider.directionToCoords 同款比例，整数运算免浮点）。
     * 未注入 [screenMetrics] 时退回 1080x2400 假设——退化值与旧硬编码一致
     * （up = 540,1800,540,600）。
     */
    private fun directionCoords(direction: String): List<Int>? {
        val (w, h) = screenMetrics?.invoke() ?: (1080 to 2400)
        val cx = w / 2
        val cy = h / 2
        return when (direction) {
            "up" -> listOf(cx, h * 3 / 4, cx, h / 4)
            "down" -> listOf(cx, h / 4, cx, h * 3 / 4)
            "left" -> listOf(w * 4 / 5, cy, w / 5, cy)
            "right" -> listOf(w / 5, cy, w * 4 / 5, cy)
            else -> null
        }
    }
}

/**
 * 通知栏工具（Issue #240 的 LLM 面）—— `UiAction.OpenNotifications` 的
 * root 误映射（`input keyevent 26` = 电源键熄屏）已在 DefaultPrivilegeManager
 * 修正为 `cmd statusbar expand-notifications`，但该动作此前**没有任何
 * LLM 工具会构造**——修正后仍是死代码。本工具补齐入口：open/close 语义，
 * a11y 就绪走 GLOBAL_ACTION（无障碍通道从未受 #240 影响），否则 shell
 * `cmd statusbar`（API 24+，root/sh 权限取决于 shellExecutor 通道）。
 */
class UiNotificationsTool(
    private val shellExecutor: suspend (String) -> String,
    private val uiProvider: UiInteractionProvider? = null
) : AgentTool {

    override val id = "UiNotifications"
    override val name = "Open/Close Notification Shade"
    override val description = """
        Expand or collapse the notification shade (system notification panel).
        Use "open" to pull down the shade and read/interact with notifications,
        "close" to dismiss it back up.

        Examples:
        - {} - open the notification shade (default)
        - {"action": "close"} - collapse the shade
    """.trimIndent()

    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "action": {"type": "string", "enum": ["open", "close"], "description": "open = expand shade (default), close = collapse shade"}
            },
            "required": []
        }
    """.trimIndent()

    override suspend fun execute(arguments: String): String {
        val json = Json.parseToJsonElement(arguments).jsonObject
        val action = json["action"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "open"
        if (action != "open" && action != "close") {
            return "Error: 'action' must be \"open\" or \"close\" (got: $action)"
        }

        // a11y 语义通道优先（GLOBAL_ACTION_NOTIFICATIONS / BACK 收合）
        if (uiProvider?.isAvailable == true) {
            val gesture = if (action == "open") GestureAction.OpenNotifications else GestureAction.CloseNotifications
            return uiProvider.performGesture(gesture)
        }

        // shell 回退：cmd statusbar（API 24+）—— 绝不用 keyevent 26（电源键，
        // #240 的原始事故：要求开通知栏结果熄了屏）。
        val command = if (action == "open") {
            "cmd statusbar expand-notifications"
        } else {
            "cmd statusbar collapse"
        }
        return shellExecutor(command)
    }
}

/**
 * UI树读取工具 —— 优先使用 AccessibilityService 结构化树，回退到 uiautomator XML。
 */
class UiDumpTool(
    private val shellExecutor: suspend (String) -> String,
    private val uiProvider: UiInteractionProvider? = null
) : AgentTool {

    override val id = "UiDump"
    override val name = "Dump UI Hierarchy"
    override val description = """
        Dump the current screen's UI hierarchy (view tree).
        Returns structured info with all visible elements, their types, text, and bounds.
        Use this to find elements before tapping or interacting.

        When accessibility service is active, returns parsed semantic tree.
        Falls back to shell 'uiautomator dump' when unavailable.
    """.trimIndent()

    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "max_depth": {"type": "integer", "description": "Max tree depth (default: 10)"},
                "filter_text": {"type": "string", "description": "Only show elements containing this text"}
            },
            "required": []
        }
    """.trimIndent()

    override suspend fun execute(arguments: String): String {
        val json = Json.parseToJsonElement(arguments).jsonObject
        val maxDepth = json["max_depth"]?.jsonPrimitive?.intOrNull ?: 10
        val filterText = json["filter_text"]?.jsonPrimitive?.content

        // 优先使用 AccessibilityService 结构化树
        if (uiProvider?.isAvailable == true) {
            val result = uiProvider.dumpUiTree(maxDepth)
            if (result.isNotBlank()) {
                return if (filterText != null) {
                    val lines = result.lines().filter { it.contains(filterText, ignoreCase = true) }
                    "Found ${lines.size} elements matching '$filterText':\n${lines.joinToString("\n").take(5000)}"
                } else {
                    result.take(8000).let {
                        if (it.length < result.length) "$it\n[... truncated]" else it
                    }
                }
            }
        }

        // 回退到 uiautomator dump
        var result = shellExecutor("uiautomator dump /data/local/tmp/UiDump.xml 2>/dev/null && cat /data/local/tmp/UiDump.xml")

        if (result.contains("Error") || result.isBlank()) {
            return "UI dump failed. This may require accessibility service or specific permissions."
        }

        if (filterText != null) {
            val lines = result.split("><").filter { it.contains(filterText, ignoreCase = true) }
            return "Found ${lines.size} elements matching '$filterText':\n${lines.joinToString("\n") { "<$it>" }.take(3000)}"
        }

        if (result.length > 5000) {
            result = result.take(5000) + "\n[... truncated at 5000 chars]"
        }

        return result
    }
}

/**
 * 截图工具
 *
 * Issue #239：新增 [privilegedScreenshot] 通道 —— app 层接线
 * `PrivilegeManager.takeScreenshot()`（无障碍 API 30+ → root screencap+base64
 * 回退链）。旧行为恒走 `shellExecutor`（PrivilegeDetector：root > 裸 sh）——
 * 设备开了无障碍但无 root 时，screencap 在普通应用沙箱里必然 EACCES，
 * 「开启无障碍 = 截图永远失败」。新顺序：特权链（a11y → root）成功直接落盘；
 * 失败仍回退 shell（模拟器/调试构建的非特权 screencap 依旧可用），两段
 * 失败并列上报（可诊断，不互相遮蔽）。
 */
class ScreenshotTool(
    private val shellExecutor: suspend (String) -> String,
    /** #239：特权截图供给；null = 无特权通道（纯 shell 旧行为）。 */
    private val privilegedScreenshot: (suspend () -> PrivilegedScreenshot)? = null
) : AgentTool {

    /**
     * Issue #239：特权截图通道结果（core 层中立形状 —— app 层从
     * platform.privilege.ScreenshotResult 适配；模块方向不允许直接依赖）。
     */
    data class PrivilegedScreenshot(
        /** PNG 字节（成功）；null = 失败（见 [error]）。 */
        val pngBytes: ByteArray?,
        val error: String?
    )

    override val id = "Screenshot"
    override val name = "Take Screenshot"
    override val description = """
        Capture the current screen as a PNG image.
        Returns the file path of the saved screenshot.

        Examples:
        - {} - save to default location
        - {"path": "/sdcard/Pictures/my_screenshot.png"}
    """.trimIndent()

    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "path": {"type": "string", "description": "Save path (default: /sdcard/Pictures/apex_screen.png)"}
            },
            "required": []
        }
    """.trimIndent()

    override suspend fun execute(arguments: String): String {
        val json = Json.parseToJsonElement(arguments).jsonObject
        val path = json["path"]?.jsonPrimitive?.content ?: "/sdcard/Pictures/apex_screen.png"

        // #239：特权链优先 —— a11y（Android 11+）→ root screencap。
        val privileged = privilegedScreenshot?.invoke()
        if (privileged?.pngBytes != null) {
            return try {
                val out = java.io.File(path)
                out.parentFile?.mkdirs()
                out.writeBytes(privileged.pngBytes)
                "OK: Screenshot saved to $path"
            } catch (e: Exception) {
                "Error: screenshot captured but failed to write $path: ${e.message ?: e::class.simpleName}"
            }
        }

        // 特权链不可用/失败 → shell 兜底（保留旧行为：模拟器/调试设备上
        // 非特权 screencap 仍可用；特权链失败时其错误一并上报，可诊断）。
        // Shell-escape the screenshot path — unescaped `;` / `$(...)` / `'` in the path
        // would inject into `screencap -p $path`.
        val result = shellExecutor("screencap -p ${ShellQuote.shellQuote(path)}")
        val shellOk = !(result.contains("Error") && !result.contains("written"))
        return if (shellOk) {
            "OK: Screenshot saved to $path"
        } else if (privileged != null) {
            // 两段失败并列：特权链根因 + shell 兜底输出（截断防爆屏）。
            "Error: screenshot failed. Privileged channel (accessibility/root): " +
                "${privileged.error ?: "unavailable"}. Shell fallback: ${result.take(200)}"
        } else {
            "Error: $result"
        }
        return "OK: Screenshot saved to $path"
    }
}

/**
 * 文本输入工具
 *
 * Type text into the currently focused input field.
 * Make sure the target input field is focused (tap it first with UiTap).
 *
 * v3：声明硬前置条件 [EnvironmentAwareTool.requiredEnv] —— 键盘未激活时
 * `input text` 必然失败，门控在执行前就挡下并给出修复指引（Mobile-Agent
 * 的键盘门控范式）。遥测未接入时（未知态）fail-open，行为与 v1 一致。
 */
class InputTextTool(
    private val shellExecutor: suspend (String) -> String
) : AgentTool, EnvironmentAwareTool {

    override val requiredEnv: List<String> =
        listOf(ToolEnvironmentState.Flags.KEYBOARD_ACTIVE)

    override val id = "InputText"
    override val name = "Input Text"
    override val description = """
        Type text into the currently focused input field.
        Make sure the target input field is focused (tap it first with UiTap).

        For special keys, use key codes:
        - Enter: keyevent 66
        - Backspace: keyevent 67
        - Tab: keyevent 61

        Examples:
        - {"text": "Hello World"}
        - {"text": "search query", "submit": true} - type and press Enter
        - {"keyevent": 66} - press Enter
    """.trimIndent()

    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "text": {"type": "string", "description": "Text to type"},
                "submit": {"type": "boolean", "description": "Press Enter after typing (default: false)"},
                "keyevent": {"type": "integer", "description": "Press a specific key code instead of typing text"}
            },
            "required": []
        }
    """.trimIndent()

    override suspend fun execute(arguments: String): String {
        val json = Json.parseToJsonElement(arguments).jsonObject
        val text = json["text"]?.jsonPrimitive?.content
        val submit = json["submit"]?.jsonPrimitive?.booleanOrNull ?: false
        val keyevent = json["keyevent"]?.jsonPrimitive?.intOrNull

        if (keyevent != null) {
            return shellExecutor("input keyevent $keyevent")
        }

        if (text == null) return "Error: 'text' or 'keyevent' required"

        // 转义特殊字符
        val escaped = text
            .replace("\\", "\\\\")
            .replace("'", "'\\''")
            .replace(" ", "%s")
            .replace("&", "\\&")
            .replace("<", "\\<")
            .replace(">", "\\>")
            .replace("(", "\\(")
            .replace(")", "\\)")
            .replace("|", "\\|")

        var result = shellExecutor("input text '$escaped'")

        if (submit) {
            result += "\n" + shellExecutor("input keyevent 66")
        }

        return result.ifBlank { "OK: Typed '${text.take(50)}'" }
    }
}
