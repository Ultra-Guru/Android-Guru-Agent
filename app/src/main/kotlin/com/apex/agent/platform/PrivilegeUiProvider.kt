package com.apex.agent.platform

import android.content.Context
import com.apex.agent.core.tools.builtin.GestureAction
import com.apex.agent.core.tools.builtin.UiInteractionProvider
import com.apex.agent.platform.privilege.PrivilegeManager
import com.apex.agent.platform.privilege.UiAction
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 基于 PrivilegeManager 的 UiInteractionProvider 实现。
 *
 * 当 AccessibilityService 就绪时，通过 AccessibilityService 执行语义手势
 * 和结构化的 UI 树捕获，代替原始的 input 命令。
 */
@Singleton
class PrivilegeUiProvider @Inject constructor(
    private val privilegeManager: PrivilegeManager,
    @ApplicationContext private val context: Context
) : UiInteractionProvider {

    override val isAvailable: Boolean
        get() = privilegeManager.accessibilityAvailable.value

    override suspend fun performGesture(action: GestureAction): String {
        val uiAction = when (action) {
            is GestureAction.Tap -> {
                UiAction.Click(action.x, action.y)
            }
            is GestureAction.LongPress -> {
                // 长按通过 swipe with same coords + long duration 模拟
                UiAction.Swipe(action.x, action.y, action.x, action.y, action.durationMs.toLong())
            }
            is GestureAction.Swipe -> {
                UiAction.Swipe(action.x1, action.y1, action.x2, action.y2, action.durationMs.toLong())
            }
            is GestureAction.DirectionalScroll -> {
                val metrics = getScreenMetrics()
                val w = metrics.first
                val h = metrics.second
                val (x1, y1, x2, y2) = directionToCoords(action.direction, w, h)
                UiAction.Swipe(x1, y1, x2, y2, action.durationMs.toLong())
            }
            is GestureAction.Back -> UiAction.Back
            is GestureAction.Home -> UiAction.Home
            is GestureAction.OpenNotifications -> UiAction.OpenNotifications
            is GestureAction.CloseNotifications -> UiAction.CloseNotifications
        }

        val result = privilegeManager.executeUiAction(uiAction)
        return if (result.success) {
            "OK: ${describeAction(action)}"
        } else {
            "Error: ${result.message}"
        }
    }

    /**
     * 无障碍文本输入（真机 P0 修复）：经 [PrivilegeManager.executeUiAction] 的
     * InputText 分支 —— ACTION_SET_TEXT 写入焦点节点；节点不可写时该分支自行
     * 回落 Root/Shizuku `input text`，失败原因原样透出（不再静默）。
     */
    override suspend fun inputText(text: String): String? {
        if (!isAvailable) return null
        val result = privilegeManager.executeUiAction(UiAction.InputText(text))
        return if (result.success) {
            "OK: typed ${text.length} chars into focused field"
        } else {
            "Error: ${result.message}"
        }
    }

    /**
     * 结构化 UI 树 → 文本。真机 P0 修复：旧实现遍历的是 `nodes`（DFS **扁平**
     * 先序视图，children 已剥离）却只取前 20 个节点、`dumpNode` 永不递归 →
     * 开无障碍时模型只能看到 20 个容器/装饰布局，页面里可点击元素几乎全被
     * 截掉，而输出非空又永不回落 uiautomator —— agent 近乎失明。
     *
     * 现遍历 `roots`（真实嵌套树）：剪掉「自身无语义且无有语义后代」的纯装饰
     * 节点，按 [maxDepth] 递归输出，总量受 [MAX_DUMP_NODES] / [MAX_DUMP_CHARS]
     * 约束并在尾部诚实标注截断。
     */
    override suspend fun dumpUiTree(maxDepth: Int): String {
        val result = privilegeManager.getUiTree()
        if (!result.success) return ""
        val roots = result.roots.ifEmpty { result.nodes.filter { it.children.isNotEmpty() } }
            .ifEmpty { result.nodes }
        if (roots.isEmpty()) return ""

        fun isSemantic(node: com.apex.agent.platform.privilege.UiNode): Boolean =
            node.clickable || node.scrollable || node.text.isNotBlank() ||
                node.contentDescription.isNotBlank() ||
                node.className.contains("EditText") || node.className.contains("CheckBox") ||
                node.className.contains("Switch")

        // 后序剪枝：返回保留子树（无语义叶子剔除），保持层级缩进可读。
        fun prune(
            node: com.apex.agent.platform.privilege.UiNode,
            depth: Int
        ): com.apex.agent.platform.privilege.UiNode? {
            if (depth > maxDepth) return null
            val kids = node.children.mapNotNull { prune(it, depth + 1) }
            if (kids.isEmpty() && !isSemantic(node)) return null
            return if (kids.size == node.children.size) node else node.copy(children = kids)
        }

        val sb = StringBuilder(4096)
        sb.appendLine("=== UI Tree (${result.nodes.size} nodes; [C]lickable [S]crollable [T]ext) ===")
        var shown = 0
        var truncated = false
        fun walk(node: com.apex.agent.platform.privilege.UiNode, depth: Int) {
            if (truncated) return
            if (shown >= MAX_DUMP_NODES || sb.length >= MAX_DUMP_CHARS) {
                truncated = true
                return
            }
            dumpNode(sb, node, depth)
            shown++
            for (child in node.children) walk(child, depth + 1)
        }
        for (root in roots) {
            prune(root, 0)?.let { walk(it, 0) }
        }
        if (shown == 0) return ""
        if (truncated) {
            sb.appendLine(
                "... [truncated: $shown of ${result.nodes.size} nodes shown — " +
                    "narrow with filter_text or a deeper max_depth on a specific screen]"
            )
        }
        return sb.toString()
    }

    private fun dumpNode(
        sb: StringBuilder,
        node: com.apex.agent.platform.privilege.UiNode,
        depth: Int
    ) {
        val indent = "  ".repeat(depth)
        val roleIcon = buildString {
            if (node.clickable) append("[C]")
            if (node.scrollable) append("[S]")
            if (node.text.isNotBlank()) append("[T]")
            if (isEmpty()) append("   ")
        }
        val text = when {
            node.text.isNotBlank() -> "\"${node.text.take(80)}\""
            node.contentDescription.isNotBlank() -> "\"${node.contentDescription.take(80)}\""
            else -> ""
        }
        val resId = node.resourceId.substringAfterLast('/').ifBlank { "" }
        sb.appendLine("$indent$roleIcon ${node.className.substringAfterLast('.')} $resId $text ${node.bounds}")
    }

    private fun getScreenMetrics(): Pair<Int, Int> {
        val dm = context.resources.displayMetrics
        return Pair(dm.widthPixels, dm.heightPixels)
    }

    private fun directionToCoords(
        direction: String,
        screenWidth: Int,
        screenHeight: Int
    ): List<Int> {
        val cx = screenWidth / 2
        val cy = screenHeight / 2
        return when (direction) {
            "up"    -> listOf(cx, (screenHeight * 0.75).toInt(), cx, (screenHeight * 0.25).toInt())
            "down"  -> listOf(cx, (screenHeight * 0.25).toInt(), cx, (screenHeight * 0.75).toInt())
            "left"  -> listOf((screenWidth * 0.8).toInt(), cy, (screenWidth * 0.2).toInt(), cy)
            "right" -> listOf((screenWidth * 0.2).toInt(), cy, (screenWidth * 0.8).toInt(), cy)
            else    -> listOf(cx, (screenHeight * 0.75).toInt(), cx, (screenHeight * 0.25).toInt())
        }
    }

    private fun describeAction(action: GestureAction): String = when (action) {
        is GestureAction.Tap -> "tapped at (${action.x}, ${action.y})"
        is GestureAction.LongPress -> "long-pressed at (${action.x}, ${action.y})"
        is GestureAction.Swipe -> "swiped (${action.x1},${action.y1})→(${action.x2},${action.y2})"
        is GestureAction.DirectionalScroll -> "scrolled ${action.direction}"
        is GestureAction.Back -> "pressed back"
        is GestureAction.Home -> "pressed home"
        is GestureAction.OpenNotifications -> "opened notification shade"
        is GestureAction.CloseNotifications -> "closed notification shade"
    }

    private companion object {
        /** 递归输出的节点上限（典型页面 100-500 节点，剪枝后通常 < 150）。 */
        private const val MAX_DUMP_NODES = 200

        /** 字符上限：留出 UiDumpTool 外层 8000 字符截断的余量，保证尾部截断标注可见。 */
        private const val MAX_DUMP_CHARS = 7_600
    }
}

