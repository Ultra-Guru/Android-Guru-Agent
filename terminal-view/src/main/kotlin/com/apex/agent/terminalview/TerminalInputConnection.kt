package com.apex.agent.terminalview

import android.text.Editable
import android.text.InputType
import android.text.Selection
import android.text.SpannableStringBuilder
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest

/**
 * T88（2-a）：终端 IME 桥（Termux `TerminalInputConnection` 的等价物）。
 *
 * ## 关键决策
 *
 *  - **组合文本差分直通**：`setComposingText` 是「替换整个组合区」语义 ——
 *    Gboard/百度/讯飞每击键上报**全量**组合串（"a"→"ab"→"abc"），直通会
 *    重复上屏（"aababc"）。以 [sentComposing] 镜像已同步到 PTY 的组合串，
 *    每次只下发差异（公共前缀 + 退格 + 增量），组合期字符实时上屏（旧渲染
 *    器实测「组合态不下发 = 字打不进去」），提交（commit）时同理差分替换
 *    —— 与真实终端输入一致；
 *  - **可编辑缓冲只留 1 行**：不镜像终端滚回 —— IME 只需要光标/删除的语义，
 *    大缓冲会撑爆内存并让 IME 误触发滚动；
 *  - **`\n` 归一为 `\r`**（终端 Enter 语义）；
 *  - `sendKeyEvent` 走 View 的硬件键路径（onKeyDown 的完整映射，保证 IME 送的
 *    Enter/Delete 与硬件键盘行为一致）；
 *  - inputType 用 `TYPE_CLASS_TEXT + NO_SUGGESTIONS`（而非 Termux 早期的
 *    TYPE_NULL —— 部分中文 IME 在 TYPE_NULL 下拒绝上屏；无建议避免输入法
 *    自作主张改写终端命令），imeOptions 去全屏/抽取，Enter 走原生键。
 */
class TerminalInputConnection(private val terminalView: TerminalView) :
    BaseInputConnection(terminalView, false) {

    /** 最小可编辑缓冲（IME 删除/光标语义的锚点；只存当前未提交行）。 */
    private val tinyBuffer: SpannableStringBuilder = SpannableStringBuilder().apply {
        Selection.setSelection(this, 0)
    }

    /**
     * ★ 组合区镜像（IME 替换语义的基准）：setComposingText 的契约是「替换
     * 整个组合区」而非「追加」—— Gboard/中文 IME 每击键上报**全量**组合串
     *（"a"→"ab"→"abc"）。旧实现把每份全量都直通 PTY → 终端收到
     * "aababc"（输入乱码/「打字不进字」的根因）。此处保存上一次已同步到
     * PTY 的组合串，每次只下发与新版差异（公共前缀之后的增删）。
     */
    private var sentComposing: String = ""

    override fun getEditable(): Editable {
        // 只返回当前行的小缓冲 —— 绝不镜像终端滚回（内存与 IME 行为双保险）
        return tinyBuffer
    }

    override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
        val raw = text?.toString() ?: return super.commitText(text, newCursorPosition)
        if (raw.isEmpty()) return true
        // ★ 提交替换组合区（IME 契约）：终端里已存在 sentComposing 个字符，
        // commitText 会把它们替换成 raw —— 只下发差异（退掉被删的尾部 + 补上
        // 新增的后缀），避免「组合期直通 + 提交期全量重发」的双份上屏。
        // Enter 归一（\n→\r，终端回车语义）沿 handleImeText 旧契约。
        val normalized = raw.replace('\n', '\r')
        replaceTerminalRegion(sentComposing, normalized)
        sentComposing = ""
        // 行缓冲同步（IME 期望 commit 后文本进入编辑框）
        tinyBuffer.clear()
        tinyBuffer.append(raw.replace('\n', ' ').replace('\r', ' '))
        Selection.setSelection(tinyBuffer, tinyBuffer.length)
        return true
    }

    override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
        val raw = text?.toString() ?: return super.setComposingText(text, newCursorPosition)
        // ★ 差分直通：组合区是「替换」语义 —— 与上一次已同步到 PTY 的内容求
        // 公共前缀，退掉多删的尾部、只补新增的后缀。IME 发空串（放弃组合）
        // 时自然退掉全部已上屏组合字符。
        replaceTerminalRegion(sentComposing, raw)
        sentComposing = raw
        return true
    }

    /**
     * 把终端里 old 文本呈现区替换成 new：公共前缀不变，退掉 (old.len - p)
     * 个退格，再下发 new 的 (p..end) 增量。两个调用方（组合/提交）共用。
     */
    private fun replaceTerminalRegion(old: String, new: String) {
        if (old == new) return
        val prefix = commonPrefixLength(old, new)
        repeat(old.length - prefix) { terminalView.handleImeBackspace() }
        val added = new.substring(prefix)
        if (added.isNotEmpty()) {
            terminalView.handleImeCompose(added)
        }
    }

    private fun commonPrefixLength(a: String, b: String): Int {
        val n = minOf(a.length, b.length)
        var i = 0
        while (i < n && a[i] == b[i]) i++
        return i
    }

    override fun setComposingRegion(start: Int, end: Int): Boolean {
        // 终端不支持区域内重组合：吞掉（返回 true 防 IME 反复重试）
        return true
    }

    override fun finishComposingText(): Boolean {
        // IME 结束组合（不一定紧随 commit）：保留已上屏字符（多数 IME 随后
        // commitText 会经差分自然对齐；清零镜像会导致提交时重复下发）。
        return super.finishComposingText()
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        var before = beforeLength.coerceAtLeast(0)
        var after = afterLength.coerceAtLeast(0)
        // ★ 组合区已被 IME 用 setComposingText("") 退净；此处只处理真实已
        // 提交文本的退格（beforeLength 以 IME 眼中的编辑框为准，映射到
        // tinyBuffer 行缓冲）。行缓冲收缩（IME 的本地视图一致）。
        while (before-- > 0) terminalView.handleImeBackspace()
        while (after-- > 0) terminalView.handleImeDeleteForward()
        val len = tinyBuffer.length
        if (len > 0) {
            tinyBuffer.clear()
            Selection.setSelection(tinyBuffer, 0)
        }
        return true
    }

    override fun sendKeyEvent(event: KeyEvent?): Boolean {
        if (event == null) return super.sendKeyEvent(event)
        // IME 生成的键（Gboard 的 Enter/Delete/方向）→ View 硬件键路径统一处理
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> terminalView.dispatchHardwareKeyEvent(event) ||
                super.sendKeyEvent(event)
            else -> super.sendKeyEvent(event)
        }
    }

    override fun performPrivateCommand(action: String?, data: android.os.Bundle?): Boolean {
        // 厂商 IME 私有指令（语音面板等）—— no-op，返回成功防重试风暴
        return true
    }

    override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? {
        // 全屏抽取模式已被 imeOptions 禁用；这里返回空防个别 IME 强行抽取
        val et = ExtractedText()
        et.text = tinyBuffer
        et.startOffset = 0
        et.selectionStart = tinyBuffer.length
        et.selectionEnd = tinyBuffer.length
        return et
    }

    companion object {
        /**
         * View 的 `onCreateInputConnection` 调用：填 EditorInfo（inputType/ime 选项
         * 的集中决策 —— 见类 KDoc）。
         *
         * T92：**删除 IME_FLAG_FORCE_ASCII** —— 该 flag 语义是「请求 IME 切到
         * ASCII/英文直通」，Gboard 中文/搜狗/百度等主流中文输入法会响应它直接
         * **关掉组合通道**（打不出任何中文），与整套 sentComposing 差分管线
         *（专为中文 IME 设计）自相矛盾。Termux 从不使用该 flag。
         * 同时 IME_ACTION_NONE → IME_ACTION_GO：NONE 会让 Android TV/部分第三方
         * 键盘的回车键失效（termux-app#221）；GO 走 performEditorAction →
         * BaseInputConnection 默认发 KEYCODE_ENTER → sendKeyEvent 归一路径。
         */
        fun populateEditorInfo(outAttrs: EditorInfo) {
            outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or
                EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                EditorInfo.IME_ACTION_GO
        }
    }
}
