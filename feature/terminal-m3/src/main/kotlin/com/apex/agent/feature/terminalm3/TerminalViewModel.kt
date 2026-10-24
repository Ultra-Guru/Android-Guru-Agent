package com.apex.agent.feature.terminalm3

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 终端行语义类型 —— [TerminalScreen] 按类型做代码高亮（prompt 绿 /
 * 成功绿 / 错误红 / 提示灰），[TerminalViewModel] 按类型路由命令语义。
 */
enum class TerminalLineType { COMMAND, OUTPUT, SUCCESS, ERROR, HINT, PENDING }

/**
 * 终端输出行（不可变值对象；自增 [id] 作 LazyColumn 复用键，避免
 * 相同文本行被合并复用导致的高亮错位）。
 */
data class TerminalLine(
    val id: Long,
    val type: TerminalLineType,
    val text: String,
)

/**
 * ═══════════════════════════════════════════════════════════════
 *  M³ 终端 ViewModel —— 单源状态 + 模拟命令执行
 * ═══════════════════════════════════════════════════════════════
 *
 * 状态纪律（单源同步的核心约束）：
 *  - [input] 是输入框与终端卡片「当前输入行」共用的**唯一**输入状态 ——
 *    输入框 onValueChange → [onInputChange] 写回 [input]；终端卡片
 *    当前行直接读 [input] 实时渲染（$ + 文本 + 闪烁光标）。两处 UI
 *    永远一致，不存在第二份输入副本，也不需要额外回调同步；
 *  - [lines]（SnapshotStateList）承载历史命令 / 输出 / 错误 / 提示行，
 *    新行 append、模拟执行完成原位替换 PENDING 行（id 不变，滚动位置
 *    与 LazyColumn 复用键稳定）；
 *  - [isExecuting] 门闩：模拟执行期间（500ms）忽略再次提交 —— 与
 *    「空输入忽略提交」同为 [submit] 的两道守卫。
 *
 * 协程纪律（仓库 AGENTS.md「fire-and-forget」口径）：
 *  - 内部 scope = SupervisorJob + Main（UI 状态写在主线程）；
 *  - 模拟执行的 delay 折叠在协程内，不阻塞调用方；
 *  - [dispose] 随组合离开一次性取消 scope（屏幕 DisposableEffect 调用）。
 */
class TerminalViewModel {

    /** 输入框与终端当前输入行共用的单源输入状态（见类注释）。 */
    var input: String by mutableStateOf("")
        private set

    /** 终端历史行（命令 / 输出 / 成功 / 错误 / 提示 / 执行中）。 */
    val lines: MutableList<TerminalLine> = mutableStateListOf()

    /** 模拟命令执行中（true 时提交按钮禁用 + submit 直接返回）。 */
    var isExecuting: Boolean by mutableStateOf(false)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var nextId: Long = 0L

    init {
        lines += hint("APEX Agent · M³ Terminal")
        lines += hint("输入 help 查看可用命令；长按终端可复制文本")
    }

    /**
     * 输入框唯一写入口 —— OutlinedTextField.onValueChange 直连此函数，
     * 变更即刻落回 [input]（单源），终端当前输入行随之同帧刷新。
     */
    fun onInputChange(value: String) {
        input = value
    }

    /**
     * 提交当前输入。守卫：空输入 / 纯空白输入、或上一条模拟命令仍在
     * 执行中 → 直接忽略（不产生任何行、不重置输入）。
     */
    fun submit() {
        val command = input.trim()
        if (command.isEmpty() || isExecuting) return

        input = ""
        lines += TerminalLine(nextId++, TerminalLineType.COMMAND, command)

        when {
            command == "help" -> printHelp()
            command == "clear" || command == "cls" -> clearScreen()
            command == "echo" -> lines += hint("用法：echo <text>")
            command.startsWith("echo ") -> {
                lines += TerminalLine(
                    nextId++,
                    TerminalLineType.OUTPUT,
                    command.removePrefix("echo ").trim(),
                )
            }
            command == "date" -> printDate()
            else -> simulateExecution(command)
        }
    }

    /** 手动清屏入口（顶栏清理按钮等 UI 钩子）。 */
    fun clearScreen() {
        lines.clear()
        lines += hint("已清屏")
    }

    /** 离开组合时取消内部协程（屏幕 DisposableEffect 调用）。 */
    fun dispose() {
        scope.cancel()
    }

    // ── 命令实现 ──────────────────────────────────────────────────────────

    private fun printHelp() {
        lines += hint("可用命令：")
        lines += hint("  help          显示本帮助")
        lines += hint("  clear | cls   清空终端")
        lines += hint("  echo <text>   输出文本")
        lines += hint("  date          输出当前时间戳")
        lines += hint("  <其他命令>    模拟执行（约 500ms）")
    }

    private fun printDate() {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        lines += TerminalLine(nextId++, TerminalLineType.OUTPUT, stamp)
    }

    /**
     * 未知命令的模拟执行：先落一行 PENDING「正在执行 xxx …」，500ms 后
     * **原位替换**为 SUCCESS「✓ xxx 执行完成」（id 复用 → LazyColumn 键
     * 稳定、滚动位置不跳）。
     */
    private fun simulateExecution(command: String) {
        isExecuting = true
        val pendingId = nextId++
        lines += TerminalLine(pendingId, TerminalLineType.PENDING, "正在执行 $command …")
        scope.launch {
            delay(500)
            val index = lines.indexOfFirst { it.id == pendingId }
            if (index >= 0) {
                lines[index] = TerminalLine(pendingId, TerminalLineType.SUCCESS, "✓ $command 执行完成")
            } else {
                // 防御：清屏等路径已移除该行（如用户在执行期间手动清屏）——
                // 不复活旧行，仅补一行成功态，保持台账完整。
                lines += TerminalLine(pendingId, TerminalLineType.SUCCESS, "✓ $command 执行完成")
            }
            isExecuting = false
        }
    }

    private fun hint(text: String): TerminalLine =
        TerminalLine(nextId++, TerminalLineType.HINT, text)
}
