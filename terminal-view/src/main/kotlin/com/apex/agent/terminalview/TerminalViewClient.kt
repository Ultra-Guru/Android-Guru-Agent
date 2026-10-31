package com.apex.agent.terminalview

import com.apex.agent.terminalemulator.MouseTrackingMode
import com.apex.agent.terminalemulator.TerminalKey
import com.apex.agent.terminalemulator.TerminalMouseEventType

/**
 * T88（2-a）：TerminalView 宿主桥接口 —— :terminal-view 与 app 之间的唯一 IO 契约。
 *
 * 设计动机（为什么是回调而不是直接持有 ViewModel/PTY）：
 *  - `:terminal-view` 是**纯 View 层模块**（零 Compose / 零 Hilt / 零第三方依赖），
 *    不能 import app 的任何类型；回调反转依赖，宿主（Task 3 集成方）自行适配
 *    到 `TerminalViewModel.sendInput/sendKey/sendMouseEvent/...` 等既有方法。
 *  - 「输入策略门禁」（paste-bracketing、危险序列过滤、历史记录）全部留在宿主 ——
 *    View 只报告「用户想输入什么」，不决定「怎么发」。
 *
 * 线程契约：所有回调都在 **UI 线程**（View 事件/绘制线程）触发；宿主如需跨线程
 * 转发自行负责。
 */
interface TerminalViewClient {

    /**
     * 用户键入/粘贴的**纯文本**（IME 提交、组合增量、粘贴、软键盘 Enter 已归一为 `\r`）。
     *
     * ⚠️ 文本**不做任何编码**（不加 200~/201~ 括号、不剥控制字符）—— 策略归宿主
     * （对照 `TerminalViewModel.sendInput` / `pasteText`：bracketedPaste 括号与
     * 序列注入过滤都在 VM 内完成）。唯一的归一化是 `\n` → `\r`（终端 Enter 语义）。
     */
    fun onTerminalWrite(text: String)

    /**
     * 特殊键（方向/F1-F12/Home/End/PgUp/PgDn/Enter/Tab/Backspace/Esc/Delete/小键盘）。
     *
     * [mods] 是 [com.apex.agent.terminalemulator.KeyModifiers] 位掩码（Int ——
     * emulator 侧的 `KeyModifiers` 是 object 常量集而非类型，与
     * `TerminalEngine.encodeKey(key, mods: Int)` 的签名对齐）。DECCKM/DECKPAM
     * 应用模式由宿主编码时自行处理（`sendKey` → `encodeKey`）。
     */
    fun onTerminalKey(key: TerminalKey, mods: Int)

    /**
     * 控制字符（Ctrl+字母 → 0x01..0x1A、Ctrl+Space → 0x00 等）。宿主直接写字节。
     */
    fun onTerminalControlChar(code: Int)

    /**
     * 鼠标事件（触摸点击/拖动/滚轮，且快照 `mouseMode.enabled` 时才会派发）。
     *
     * [col]/[row] 是 **1-based 屏幕坐标**（不含 scrollback 偏移；与旧渲染器
     * `onMouse(0, col+1, visibleRow)` 的语义一致）。[released] 只对 SGR 编码
     * 有意义（press/release 区分）；[button]：0=左键、1=中键、2=右键（默认 0，
     * 双指轻点模拟右键时传 2）。[mouseMode] 回传快照当前模式，宿主无需再查。
     */
    fun onTerminalMouse(
        col: Int,
        row: Int,
        type: TerminalMouseEventType,
        mods: Int,
        mouseMode: MouseTrackingMode,
        released: Boolean,
        button: Int = 0
    )

    /** View 窗口焦点变化（DECSET 1004 焦点报告的触发源；宿主发 ESC[I / ESC[O）。 */
    fun onTerminalFocus(gained: Boolean)

    /**
     * 视口尺寸 → 网格尺寸（resize 握手）：View 完成字体探测与 rows/cols 计算
     * （150ms 防抖）后调用；宿主应把 rows/cols 下发 PTY（SIGWINCH）。
     * pixelWidth/pixelHeight 是内容区像素尺寸（宿主可传给 TIOCSWINSZ 的像素域）。
     */
    fun onTerminalViewSizeChanged(rows: Int, cols: Int, pixelWidth: Int, pixelHeight: Int)

    /** 响铃（BEL）：bellSeq 序号变化即触发一次；振动/提示音与否是宿主的产品决策。 */
    fun onTerminalBell()

    /** 终端标题变化（OSC 0/2；去重后仅变化时触发）。 */
    fun onTerminalTitle(title: String)

    /** 选区复制请求（复制浮标/上下文菜单 Copy）：宿主写系统剪贴板。 */
    fun onTerminalClipboardCopy(text: String)

    /** 粘贴请求（上下文菜单/键盘快捷键）：宿主读剪贴板后走自己的 paste 策略。 */
    fun onTerminalPasteRequest()

    /** OSC 8 链接或 URL 自动识别的打开请求 —— 宿主起 ACTION_VIEW Intent。 */
    fun onTerminalLinkOpen(uri: String)

    /**
     * 滚动位置变化（topRow / 是否贴底）—— 宿主用于「跳到最新」浮标等 affordance。
     * View 内部已自带指示箭头，宿主可忽略；仅状态**变化**时触发（防抖）。
     */
    fun onTerminalScrollChanged(topRow: Int, atBottom: Boolean)

    /** 选区变化（null = 清空）；拖选/双击选词/清空都会触发。 */
    fun onTerminalSelectionChanged(selectedText: String?)

    /** 字号变化（双指捏合/宿主 API 调用后；已按 settings 边界 clamp）。 */
    fun onTerminalFontSizeChanged(newSp: Float)

    /**
     * 请求宿主在 (x, y)（屏幕像素坐标，适合 PopupWindow 定位）展示上下文菜单。
     * 菜单项由 View 生成（复制/粘贴/全选/打开链接/复制链接），宿主负责 UI 呈现
     * 与点击回调（点击后调用 View 的 `copySelection()/pasteFromClipboard()/
     * selectAll()` 等公开 API）。
     */
    fun onTerminalContextMenu(items: List<TerminalContextMenuItem>, x: Float, y: Float)
}

/**
 * 一条上下文菜单项：宿主展示用。id 稳定（宿主据此路由点击），label 已本地化职责
 * 在宿主（View 只出稳定 id + 默认英文 label，宿主可按语言表覆写）。
 */
data class TerminalContextMenuItem(val id: String, val label: String) {
    companion object {
        /** 选区文本 → 剪贴板（仅当 [TerminalView.hasSelection]）。 */
        const val ID_COPY = "terminal.copy"

        /** 读剪贴板 → 粘贴请求。 */
        const val ID_PASTE = "terminal.paste"

        /** 全选当前可见网格。 */
        const val ID_SELECT_ALL = "terminal.select_all"

        /** 清除选区。 */
        const val ID_CLEAR_SELECTION = "terminal.clear_selection"

        /** 打开命中链接（URL 自动识别菜单）。 */
        const val ID_OPEN_LINK = "terminal.open_link"

        /** 复制命中链接（URL 自动识别菜单）。 */
        const val ID_COPY_LINK = "terminal.copy_link"
    }
}

/** 默认选区菜单（选区存在时含 Copy；Paste/SelectAll/Clear 恒在）。 */
fun defaultSelectionMenuItems(hasSelection: Boolean): List<TerminalContextMenuItem> {
    val items = ArrayList<TerminalContextMenuItem>(4)
    if (hasSelection) items.add(TerminalContextMenuItem(TerminalContextMenuItem.ID_COPY, "Copy"))
    items.add(TerminalContextMenuItem(TerminalContextMenuItem.ID_PASTE, "Paste"))
    items.add(TerminalContextMenuItem(TerminalContextMenuItem.ID_SELECT_ALL, "Select all"))
    items.add(TerminalContextMenuItem(TerminalContextMenuItem.ID_CLEAR_SELECTION, "Clear"))
    return items
}

/** URL 命中菜单（Open link / Copy link）。 */
fun urlMenuItems(): List<TerminalContextMenuItem> = listOf(
    TerminalContextMenuItem(TerminalContextMenuItem.ID_OPEN_LINK, "Open link"),
    TerminalContextMenuItem(TerminalContextMenuItem.ID_COPY_LINK, "Copy link")
)
