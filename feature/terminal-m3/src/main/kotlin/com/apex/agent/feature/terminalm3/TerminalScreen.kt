package com.apex.agent.feature.terminalm3

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/**
 * ═══════════════════════════════════════════════════════════════
 *  Material 3 Terminal —— 终端页（现代 M³ REPL 皮肤）
 * ═══════════════════════════════════════════════════════════════
 *
 * 页面结构（自上而下）：
 *  1. [TopAppBar]：标题 "Terminal"（等宽字体），返回钮走统一返回链；
 *  2. [CommandInputField]：28dp 全圆角命令输入框 —— prefix "$ " 绿色
 *     提示符、trailingIcon 为 FilledIconButton(Send)、IME action = Send
 *     （软键盘回车即提交）、placeholder "输入命令…"；
 *  3. [TerminalConsoleCard]：24dp 圆角终端卡片（surfaceContainerHigh 面
 *     + 1dp outline 描边），内嵌 SelectionContainer + LazyColumn —— 历史
 *     行按类型代码高亮（命令 token 着色），末行实时渲染 **viewModel.input**
 *     （与输入框同一状态源，见 [TerminalViewModel] 类注释）+ 闪烁光标 "▌"。
 *
 * 交互口径：
 *  - 提交守卫在 VM（空输入 / 执行中忽略），按钮侧再以 enabled 同步禁用；
 *  - 历史行新增（lines.size 变化）自动 scrollToItem 到底（末行 = 当前
 *    输入行，索引 lines.size）；
 *  - 长按终端任意文本可复制（SelectionContainer 整卡包裹）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun M3TerminalScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TerminalViewModel = remember { TerminalViewModel() },
) {
    val listState = rememberLazyListState()

    // 离开组合取消 VM 内部协程（fire-and-forget 纪律，见 VM 类注释）。
    DisposableEffect(viewModel) {
        onDispose { viewModel.dispose() }
    }

    // 自动滚动：新行落地即滚到末行（当前输入行，index = lines.size）。
    LaunchedEffect(viewModel.lines.size) {
        if (viewModel.lines.isNotEmpty()) {
            listState.scrollToItem(viewModel.lines.size)
        }
    }

    // 页内自带恒暗 M³ 主题（GitHub Dark）：不随全局亮暗切换洗白终端配色，
    // 终端语义天然暗色（见 Theme.kt 设计口径）。
    M3TerminalTheme {
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            TopAppBar(
                title = {
                    Text(
                        text = "Terminal",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CommandInputField(
                    value = viewModel.input,
                    onValueChange = viewModel::onInputChange,
                    onSubmit = viewModel::submit,
                    enabled = !viewModel.isExecuting,
                )

                TerminalConsoleCard(
                    viewModel = viewModel,
                    listState = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            }
        }
    }
}

/**
 * 命令输入框（28dp 全圆角 OutlinedTextField）。
 *
 * 单源同步关键位：value/onValueChange 直连 [TerminalViewModel.input] /
 * [TerminalViewModel.onInputChange] —— 输入变更即刻落回 VM 状态，终端卡片
 * 的当前输入行随同帧刷新，无第二份输入副本。
 */
@Composable
private fun CommandInputField(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        singleLine = true,
        prefix = { Text(text = "$ ") },
        placeholder = { Text(text = "输入命令…") },
        trailingIcon = {
            FilledIconButton(
                onClick = onSubmit,
                enabled = enabled && value.isNotBlank(),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "执行命令",
                )
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { onSubmit() }),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
            cursorColor = MaterialTheme.colorScheme.primary,
            focusedPrefixColor = MaterialTheme.colorScheme.primary,
            unfocusedPrefixColor = MaterialTheme.colorScheme.primary,
        ),
    )
}

/** 当前输入行在 LazyColumn 里的稳定键（历史行用自增 id，此行恒在末尾）。 */
private const val CURRENT_INPUT_ITEM_KEY = "current-input"

/**
 * 终端卡片（24dp 圆角 + surfaceContainerHigh 面 + 1dp 描边）。
 * SelectionContainer 整卡包裹 —— 长按任意行可复制。
 */
@Composable
private fun TerminalConsoleCard(
    viewModel: TerminalViewModel,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        SelectionContainer {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(viewModel.lines, key = { it.id }) { line ->
                    TerminalLineText(line = line)
                }
                item(key = CURRENT_INPUT_ITEM_KEY) {
                    CurrentInputLine(
                        text = viewModel.input,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/**
 * 单行终端文本 —— 按行类型代码高亮：
 *  - COMMAND：绿色 "$" 提示符 + 命令名亮白加粗 + 旗标/引号串橙色 + 普通参数弱灰；
 *  - SUCCESS："✓" 绿色加粗 + 正文亮白；
 *  - ERROR：全行 GitHub Dark 红；
 *  - PENDING：弱灰斜体（执行中的过渡态）；
 *  - HINT / OUTPUT：弱灰 / 亮白（终端常规输出密度）。
 */
@Composable
private fun TerminalLineText(line: TerminalLine) {
    Text(
        text = highlightLine(line),
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** 末行「当前输入」："$ " + 实时输入文本（与输入框同一状态源）+ 闪烁光标 "▌"。 */
@Composable
private fun CurrentInputLine(
    text: String,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "cursor-blink")
    val cursorAlpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 520),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "cursor-alpha",
    )
    Text(
        text = buildAnnotatedString {
            withStyle(
                SpanStyle(
                    color = TerminalSyntax.prompt,
                    fontWeight = FontWeight.Bold,
                ),
            ) { append("$ ") }
            withStyle(SpanStyle(color = TerminalSyntax.output)) { append(text) }
            withStyle(SpanStyle(color = TerminalSyntax.prompt.copy(alpha = cursorAlpha))) {
                append("▌")
            }
        },
        style = MaterialTheme.typography.bodyLarge,
        modifier = modifier,
    )
}

/**
 * 行文本 → 高亮 AnnotatedString（按 [TerminalLine.type] 分派）。
 * 纯函数（非 Composable）—— 便于未来抽 JVM 单测锁口径。
 */
private fun highlightLine(line: TerminalLine): AnnotatedString = when (line.type) {
    TerminalLineType.COMMAND -> highlightCommandTokens(line.text)
    TerminalLineType.SUCCESS -> buildAnnotatedString {
        withStyle(
            SpanStyle(
                color = TerminalSyntax.prompt,
                fontWeight = FontWeight.Bold,
            ),
        ) { append("✓ ") }
        withStyle(SpanStyle(color = TerminalSyntax.output)) {
            append(line.text.removePrefix("✓ "))
        }
    }
    TerminalLineType.ERROR -> buildAnnotatedString {
        withStyle(SpanStyle(color = TerminalSyntax.error)) { append(line.text) }
    }
    TerminalLineType.PENDING -> buildAnnotatedString {
        withStyle(
            SpanStyle(
                color = TerminalSyntax.muted,
                fontStyle = FontStyle.Italic,
            ),
        ) { append(line.text) }
    }
    TerminalLineType.HINT -> buildAnnotatedString {
        withStyle(SpanStyle(color = TerminalSyntax.muted)) { append(line.text) }
    }
    TerminalLineType.OUTPUT -> buildAnnotatedString {
        withStyle(SpanStyle(color = TerminalSyntax.output)) { append(line.text) }
    }
}

/**
 * 命令行 token 级代码高亮：
 * prompt("$")绿加粗 → 命令名(首 token)亮白加粗 → 旗标(-x/--long)橙 →
 * 引号串橙斜体 → 其余参数弱灰。
 */
private fun highlightCommandTokens(command: String): AnnotatedString = buildAnnotatedString {
    withStyle(
        SpanStyle(
            color = TerminalSyntax.prompt,
            fontWeight = FontWeight.Bold,
        ),
    ) { append("$ ") }
    command.split(" ").forEachIndexed { index, token ->
        if (index > 0) append(" ")
        when {
            index == 0 -> withStyle(
                SpanStyle(
                    color = TerminalSyntax.command,
                    fontWeight = FontWeight.Bold,
                ),
            ) { append(token) }
            token.startsWith("-") -> withStyle(
                SpanStyle(color = TerminalSyntax.flag),
            ) { append(token) }
            token.startsWith("\"") || token.startsWith("'") -> withStyle(
                SpanStyle(
                    color = TerminalSyntax.flag,
                    fontStyle = FontStyle.Italic,
                ),
            ) { append(token) }
            else -> withStyle(
                SpanStyle(color = TerminalSyntax.muted),
            ) { append(token) }
        }
    }
}
