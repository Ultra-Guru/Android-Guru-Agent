@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.apex.agent.ui.screen.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import com.apex.agent.R
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.role

/**
 * 设置页通用控件（v2 抽屉化重构自 SettingsScreen.kt 原样迁出，零行为变更）。
 *
 * 迁移动机：设置页重构为「抽屉导航 + 页面路由」后 SettingsScreen 只保留
 * 顶层骨架，控件层独立成文件避免单文件超 1200 行预算红线；所有可见性
 * 保持 internal —— SettingsV3Sections / ModelSections 等分区文件继续复用。
 */
@Composable
internal fun SectionCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    subtitle: String? = null,
    initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    // BUG-27（设置 spec 02）：头部可点击行补齐语义 —— Role.Button +
    // stateDescription(已展开/已折叠) + onClickLabel;箭头为纯装饰,
    // 显式 clearAndSetSemantics 防止 TalkBack 播报空节点。
    val stateDesc = stringResource(
        if (expanded) R.string.settings_section_expanded
        else R.string.settings_section_collapsed
    )
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClickLabel = title) { expanded = !expanded }
                    .semantics {
                        role = Role.Button
                        stateDescription = stateDesc
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    if (subtitle != null) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    null,
                    Modifier.clearAndSetSemantics {}
                )
            }
            AnimatedVisibility(expanded) {
                Column(
                    Modifier.padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) { content() }
            }
        }
    }
}

/** 控件下方的一行小字说明；为 null 时不占位。（internal：分区文件复用） */
@Composable
internal fun DescriptionText(description: String?) {
    if (description != null) {
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
    }
}

@Composable
internal fun SwitchRow(
    label: String,
    checked: Boolean,
    description: String? = null,
    onCheckedChange: (Boolean) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f))
            Switch(checked, onCheckedChange)
        }
        DescriptionText(description)
    }
}

@Composable
internal fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    description: String? = null,
    fmt: (Float) -> String = { it.toString() },
    onValueChange: (Float) -> Unit
) {
    // 拖动期间只更新本地值，松手才回调（避免每个 tick 写 SharedPreferences）
    var local by remember(value) { mutableStateOf(value) }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, Modifier.weight(1f))
            Text(
                fmt(local.coerceIn(range)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = local.coerceIn(range),
            onValueChange = { local = it },
            valueRange = range,
            steps = steps,
            onValueChangeFinished = { onValueChange(local.coerceIn(range)) },
            modifier = Modifier.semantics { contentDescription = label } // 修复：TalkBack 播报滑块名称
        )
        DescriptionText(description)
    }
}

/** BUG-15（设置 spec 02）数字输入三类错误：空 / 非数字 / 超范围。 */
private enum class IntFieldError { EMPTY, NOT_NUMBER, OUT_OF_RANGE }

@Composable
internal fun IntFieldRow(
    label: String,
    value: Int,
    description: String? = null,
    min: Int = Int.MIN_VALUE,
    max: Int = Int.MAX_VALUE,
    onValueChange: (Int) -> Unit
) {
    // BUG-15（设置 spec 02）：不再边打字边钳制 —— 旧实现清空后输入「0」
    // 开头的合法中间态会被立即改写成 min、超大整数 toIntOrNull 静默吞掉、
    // 非法输入无提示，且 remember(value) 在外部值回写时重置光标文本。
    // 现改为：本地保留原始文本，IME Done / 失焦时校验提交；提交失败保留
    // 文本并按三类错误显示 isError + supportingText；范围提示随 description。
    var text by rememberSaveable { mutableStateOf(value.toString()) }
    var error by rememberSaveable { mutableStateOf<IntFieldError?>(null) }
    val currentOnValueChange by rememberUpdatedState(onValueChange)

    fun commit() {
        val t = text.trim()
        val parsed = when {
            t.isEmpty() -> {
                error = IntFieldError.EMPTY
                return
            }
            else -> t.toIntOrNull() ?: run {
                error = IntFieldError.NOT_NUMBER
                return
            }
        }
        if (parsed < min || parsed > max) {
            error = IntFieldError.OUT_OF_RANGE
            return
        }
        error = null
        if (parsed != value) currentOnValueChange(parsed)
    }

    // 外部值回写 → 仅当本地文本不再等于外部值时同步（不重置正在输入的中间态）
    LaunchedEffect(value) {
        if (text.trim().toIntOrNull() != value) text = value.toString()
    }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            error = null
        },
        label = { Text(label) },
        isError = error != null,
        supportingText = {
            when (error) {
                IntFieldError.EMPTY -> Text(stringResource(R.string.settings_int_error_empty))
                IntFieldError.NOT_NUMBER -> Text(stringResource(R.string.settings_int_error_not_number))
                IntFieldError.OUT_OF_RANGE ->
                    Text(stringResource(R.string.settings_int_error_out_of_range, min, max))
                null -> description?.let { Text(it) }
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { if (!it.isFocused) commit() },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { commit() })
    )
}

/**
 * BUG-16（设置 spec 02）：防抖文本输入 —— 逐字符直接回调会让大文本
 * （64KB 全局规则 / 系统提示词）每按键整份序列化落盘。300ms 防抖 +
 * 失焦 / 离页冲刷（最后一次编辑不丢）；外部值回写仅在「与本地不同」时
 * 同步，不重置光标。旋转后草稿可恢复（rememberSaveable，非敏感项）。
 */
@Composable
internal fun DebouncedTextField(
    label: String,
    value: String,
    description: String? = null,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    maxLines: Int = if (singleLine) 1 else 6,
) {
    var text by rememberSaveable { mutableStateOf(value) }
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentValue by rememberUpdatedState(value)
    LaunchedEffect(value) { if (value != text) text = value }
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }

    fun flushNow() {
        job?.cancel()
        job = null
        if (text != currentValue) currentOnValueChange(text)
    }
    // 失焦冲刷：切到下一个控件前把最后编辑落盘
    val focusModifier = modifier
        .fillMaxWidth()
        .onFocusChanged { if (!it.isFocused) flushNow() }
    // 离页冲刷：组合销毁（返回 / 切标签）时补交最后一次编辑
    DisposableEffect(Unit) { onDispose { flushNow() } }

    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            text = raw
            job?.cancel()
            job = scope.launch {
                delay(300)
                if (raw != currentValue) currentOnValueChange(raw)
            }
        },
        label = { Text(label) },
        supportingText = description?.let { { Text(it) } },
        modifier = focusModifier,
        singleLine = singleLine,
        maxLines = maxLines,
    )
}

@Composable
internal fun TextFieldRow(
    label: String,
    value: String,
    description: String? = null,
    onValueChange: (String) -> Unit
) {
    // BUG-16：统一走防抖（300ms + 失焦/离页冲刷），全部文本类设置项
    // 自动受益，调用方零改动。
    DebouncedTextField(
        label = label,
        value = value,
        description = description,
        onValueChange = onValueChange,
    )
}

/** 只读下拉（ExposedDropdownMenuBox 实现，替代旧的 AlertDialog + RadioButton）。 */
@Composable
internal fun <T> DropdownRow(
    label: String,
    options: List<Pair<T, String>>,
    selected: T,
    description: String? = null,
    onSelected: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    // BUG-18（设置 spec 02）：未知选中值不再裸显 toString() —— 给人话标签
    //（值本身保留，不做任何猜测性改写）
    val current = options.firstOrNull { it.first == selected }?.second
        ?: stringResource(R.string.settings_unknown_value)
    Column(Modifier.fillMaxWidth()) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it }
        ) {
            OutlinedTextField(
                value = current,
                onValueChange = {},
                readOnly = true,
                label = { Text(label) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) }
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                options.forEach { (v, text) ->
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = v == selected, onClick = null)
                                Spacer(Modifier.width(8.dp))
                                Text(text)
                            }
                        },
                        onClick = { onSelected(v); expanded = false }
                    )
                }
            }
        }
        DescriptionText(description)
    }
}

@Composable
internal fun ChipMultiSelect(
    label: String,
    options: List<Int>,
    selected: Set<Int>,
    onToggle: (Int) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(options, key = { it }) { code ->
                FilterChip(
                    selected = selected.contains(code),
                    onClick = { onToggle(code) },
                    label = { Text(code.toString()) }
                )
            }
        }
    }
}

@Composable
internal fun KeyValueEditor(
    label: String,
    map: Map<String, String>,
    onChange: (Map<String, String>) -> Unit
) {
    // BUG-19（设置 spec 02）：键 trim + 非空校验 + 大小写去重（HTTP 头不
    // 区分大小写）+ 空值拦截；硬编码 Header/Value 文案与删除/添加按钮的
    // 语义标签全部走资源（双语）。
    var key by rememberSaveable { mutableStateOf("") }
    var value by rememberSaveable { mutableStateOf("") }
    var invalid by rememberSaveable { mutableStateOf(false) }
    val addDescription = stringResource(R.string.settings_kv_add)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        map.forEach { (k, v) ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("$k : $v", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                // UI-012：48dp 触区红线（原 28dp）
                // BUG-19:stringResource 是 @Composable,不能进 semantics lambda —— 上提
                val deleteDesc = stringResource(R.string.settings_kv_delete, k)
                IconButton(
                    onClick = { onChange(map - k) },
                    modifier = Modifier
                        .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        .semantics { contentDescription = deleteDesc },
                ) {
                    Icon(Icons.Default.Delete, null, Modifier.size(16.dp))
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                key,
                { key = it; invalid = false },
                label = { Text(stringResource(R.string.settings_kv_key)) },
                isError = invalid,
                supportingText = if (invalid) {
                    { Text(stringResource(R.string.settings_kv_invalid), style = MaterialTheme.typography.labelSmall) }
                } else {
                    null
                },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            Spacer(Modifier.width(6.dp))
            OutlinedTextField(value, { value = it }, label = { Text(stringResource(R.string.settings_kv_value)) },
                modifier = Modifier.weight(1f), singleLine = true)
            IconButton(
                onClick = {
                    val k = key.trim()
                    // 校验：键非空、值非空、大小写不敏感去重
                    if (k.isBlank() || value.isBlank() || map.keys.any { it.equals(k, ignoreCase = true) }) {
                        invalid = true
                        return@IconButton
                    }
                    onChange(map + (k to value))
                    key = ""
                    value = ""
                    invalid = false
                },
                modifier = Modifier.semantics { contentDescription = addDescription },
            ) { Icon(Icons.Default.Add, null) }
        }
    }
}

/** 叶子页面通用容器：滚动列布局（16dp 边距 + 12dp 间距）。 */
@Composable
internal fun SettingsLeafColumn(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content
    )
}
