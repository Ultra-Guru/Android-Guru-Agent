package com.apex.agent.ui.screen.agent

import android.widget.Toast
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.apex.agent.ui.component.MarkdownText
import com.apex.agent.ui.component.MessageAttachmentList
import com.apex.agent.ui.theme.LocalShowTimestamps
import kotlinx.coroutines.delay
import java.time.format.DateTimeFormatter
import java.util.Locale
import com.apex.agent.R

// ═══ 消息组件 ═══
// 2026-10 视觉口径:气泡容器全部 M3 Surface 实色(液态玻璃材质退役于消息流);
// 气泡头部压成单行角色标;操作以图标行常驻卡片外下方(复制/编辑),不再有
// 头部 overflow 菜单。

/**
 * 单条消息渲染入口（按 AgentUiMessage 类型分发到对应气泡 / 卡片）。
 */
@Composable
internal fun AgentMessageItem(
    message: AgentUiMessage,
    vm: AgentChatViewModel,
    // 操作门禁（流式生成中禁用编辑类操作，复制仍可用）。
    actionsEnabled: Boolean = true,
    // #169：Plan 模式当前执行步骤（锁定计划卡高亮用；-1 = 非步骤执行中）。
    currentStepIndex: Int = -1,
    onImageClick: (MessageAttachment) -> Unit = {},
    onFileClick: (MessageAttachment) -> Unit = {},
    // 多模态输出：Agent 回复 markdown 里的生成图片点击 → Lightbox（URL/data URI）。
    onMarkdownImageClick: (String) -> Unit = {},
    // 任务总结卡显隐（设置 showRunSummary；false 时 RunSummary 完全不渲染、不占位）。
    showRunSummary: Boolean = true,
    // HTML 产物预览：工具卡预览钮回调（宿主绝对路径）→ 应用内 WebView。
    onPreviewHtml: (String) -> Unit = {}
) {
    when (message) {
        is AgentUiMessage.User -> UserBubble(
            message = message,
            actionsEnabled = actionsEnabled,
            onEdit = { newText -> vm.editAndResend(message.id, newText) },
            onImageClick = onImageClick,
            onFileClick = onFileClick
        )
        is AgentUiMessage.Agent -> AgentBubble(
            message = message,
            onImageClick = onMarkdownImageClick
        )
        is AgentUiMessage.ToolCall -> {
            // HTML 产物检测：成功写入 .html 的调用给卡头挂「预览」钮（一次解析，按卡缓存）。
            val htmlPath = remember(message.id, message.success, message.output) {
                // #188：成功调用的输出也纳入扫描（写确认句式优先）——
                // 脚本内部写出的 .html 也能挂预览钮
                HtmlArtifactDetector.extractHtmlPath(
                    message.toolName, message.args,
                    // success 为 Boolean?（进行中调用为 null）——takeIf 谓词需非空 Boolean
                    message.output.takeIf { message.success == true }
                )?.let { vm.resolveHtmlPreviewPath(it) }
            }
            ToolCallCard(
                toolCall = message,
                onRetry = retryLastUser(vm),
                // UX-1 同款门禁：流式生成中重试会取消在途轮次（旧工具卡悬挂 + 部分回复丢失）
                retryEnabled = actionsEnabled,
                htmlPreviewPath = htmlPath,
                onPreviewHtml = onPreviewHtml
            )
        }
        is AgentUiMessage.System -> SystemMessage(message.text)
        is AgentUiMessage.PipelineBanner -> PipelineBannerCard(message)
        is AgentUiMessage.StepMarker -> StepMarkerCard(message)
        // 任务总结卡：默认隐藏（showRunSummary=false 时完全不渲染，不占空间）
        // 收尾新增复制入口：一键复制本轮最后一条 Agent 回复（无回复时回退总结文本）。
        is AgentUiMessage.RunSummary -> if (showRunSummary) {
            val clipboard = LocalClipboardManager.current
            val context = LocalContext.current
            val copiedToast = stringResource(R.string.chat_copied)
            RunSummaryCard(
                summary = message,
                onCopy = {
                    val lastAgentText = vm.uiState.value.messages
                        .asSequence()
                        .takeWhile { it.id != message.id }
                        .filterIsInstance<AgentUiMessage.Agent>()
                        .lastOrNull { !it.isPartial }
                        ?.text
                        ?: message.summary
                    if (lastAgentText.isNotBlank()) {
                        clipboard.setText(AnnotatedString(lastAgentText))
                        Toast.makeText(context, copiedToast, Toast.LENGTH_SHORT).show()
                    }
                }
            )
        }
        is AgentUiMessage.Error -> ErrorBlock(
            message = message.message,
            canRetry = message.canRetry,
            onRetry = retryLastUser(vm),
            // UX-1 同款门禁：菜单项有门禁而重试 Chip 没有 —— 流式中可点，
            // 取消在途轮次后旧错误卡仍残留、引擎上下文重复收到同一用户文本
            retryEnabled = actionsEnabled
        )
        is AgentUiMessage.ThinkingMessage -> ThinkingBubble(
            text = message.thought,
            finished = true,
            durationMs = message.durationMs
        )
        is AgentUiMessage.PlanMessage -> PlanCard(message.plan, currentStepIndex = currentStepIndex)
        is AgentUiMessage.SpecMessage -> SpecCard(message.spec)
        is AgentUiMessage.ReflectionReviewMessage -> ReflectionReviewBlock(message.text)
    }
}

/**
 * 重试上一条用户消息（ErrorBlock 与失败 ToolCallCard 共用）。
 * 找到最近一条 User 气泡后重新发起其文本指令；没有用户消息时为空操作。
 */
internal fun retryLastUser(vm: AgentChatViewModel): () -> Unit = {
    val lastUser = vm.uiState.value.messages
        .lastOrNull { it is AgentUiMessage.User } as? AgentUiMessage.User
    lastUser?.let { vm.retry(it.text, it.attachments) }
}

@Composable
internal fun UserBubble(
    message: AgentUiMessage.User,
    // 操作门禁：流式生成中禁用编辑（复制始终可用）。
    actionsEnabled: Boolean = true,
    // 编辑重发：编辑对话框确认后回传新文本（VM.editAndResend —— 以新内容重新回答）。
    onEdit: (String) -> Unit = {},
    onImageClick: (MessageAttachment) -> Unit = {},
    onFileClick: (MessageAttachment) -> Unit = {}
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    // i18n：Toast / contentDescription 文案在组合内预取（onClick 非组合上下文）
    val copiedToast = stringResource(R.string.chat_copied)
    val editCd = stringResource(R.string.chat_cd_edit)
    val timeStr = remember(message.timestamp) {
        DateTimeFormatter.ofPattern("HH:mm").format(
            java.time.Instant.ofEpochMilli(message.timestamp)
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalDateTime()
        )
    }
    var showEditDialog by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        Surface(
            // 用户/回复气泡区分：实色 primary + onPrimary（饱和 vs 淡染，
            // 与 Code 屏 UserBubble 同口径，双屏一致）。
            color = MaterialTheme.colorScheme.primary,
            shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                // 紧凑头部：角色标 + 可选时间戳。原 48dp overflow 菜单行随菜单
                // 退役（操作图标外置气泡下方），头部只留最小可读信息。
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "YOU",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    // 时间戳可由设置中心关闭（关闭时不渲染）
                    if (LocalShowTimestamps.current) {
                        Text(
                            text = timeStr,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.6f)
                        )
                    }
                }

                // 附件展示（如果有）
                if (message.attachments.isNotEmpty()) {
                    MessageAttachmentList(
                        attachments = message.attachments,
                        onFileClick = onFileClick,
                        onImageClick = onImageClick,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }

                // 文本内容
                if (message.text.isNotBlank()) {
                    SelectionContainer {
                        Text(
                            text = message.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }
        }

        // 卡片外操作行（开源 Material 图标）：复制 + 编辑（仅用户气泡有编辑）
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = {
                    clipboard.setText(AnnotatedString(message.text))
                    Toast.makeText(context, copiedToast, Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = stringResource(R.string.chat_cd_copy),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
            IconButton(
                onClick = { showEditDialog = true },
                enabled = actionsEnabled,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = editCd,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
    if (showEditDialog) {
        EditMessageDialog(
            initialText = message.text,
            onDismiss = { showEditDialog = false },
            onResend = { newText ->
                showEditDialog = false
                onEdit(newText)
            }
        )
    }
}

@Composable
internal fun AgentBubble(
    message: AgentUiMessage.Agent,
    // 多模态输出：markdown 生成图片点击 → Lightbox。
    onImageClick: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    // i18n：Toast 文案在组合内预取（onClick 非组合上下文，不能直接 stringResource）
    val copiedToast = stringResource(R.string.chat_copied)
    val timeStr = remember(message.timestamp) {
        DateTimeFormatter.ofPattern("HH:mm").format(
            java.time.Instant.ofEpochMilli(message.timestamp)
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalDateTime()
        )
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // v1.4.4 #8 无障碍：完成回复的 liveRegion —— TalkBack 在新气泡出现时
            // 主动播报（流式增量不设 liveRegion，避免每 token 一次的爆音轰炸；
            // 完成态整条播报一次即足够的上下文）。
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.Start
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp),
            modifier = Modifier.widthIn(max = 340.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                // 紧凑头部：角色标 + 可选时间戳（原 ✦ 头像井与 48dp overflow 行退役）
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "AGENT",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (LocalShowTimestamps.current) {
                        Text(
                            text = timeStr,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // 正文（Markdown 渲染：支持代码块 / 行内代码 / 粗体 / 列表 / 图片 / 视频 / 链接）
                SelectionContainer {
                    MarkdownText(markdown = message.text, onImageClick = onImageClick)
                }
            }
        }

        // 卡片外操作行：仅复制。「整理到记忆」人头图标随记忆系统重构退役；
        // 中止/出错残留的部分回复（isPartial）不出操作行。
        if (!message.isPartial) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(message.text))
                        Toast.makeText(context, copiedToast, Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = stringResource(R.string.chat_cd_copy),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

/**
 * 流式渲染辅助（[StreamingResponseBubble] 专用）：尾部未闭合媒体语法检测 +
 * 行内 code/bold 轻量样式。均为单遍 O(n) 纯函数，随 33ms 节流 flush 重算
 * （避免 MarkdownText 的全量重解析 —— 见 StreamingResponseBubble 性能注释）。
 */
private object StreamingRenderSupport {

    /**
     * 尾部未闭合媒体语法的字符长度（0 = 无）。
     * 与 MediaMarkdown 的产物对齐：
     * - 图片 `![image](url)`：最后一个 "![" 起的尾部没有 "…(…" 闭合形态
     *   （无 "](" 或其后无 ")"）→ 从该处起全部视为在途媒体语法；
     * - 视频 `<video src="url"></video>`：最后一个 "<video" 起的尾部
     *   无开标签 ">" 或无 "</video>"。
     */
    fun incompleteMediaTailLength(text: String): Int {
        val img = text.lastIndexOf("![")
        if (img >= 0) {
            val tail = text.substring(img)
            val mid = tail.indexOf("](")
            if (mid < 0 || tail.indexOf(')', mid + 2) < 0) return tail.length
        }
        val vid = text.lastIndexOf("<video")
        if (vid >= 0) {
            val tail = text.substring(vid)
            val tagEnd = tail.indexOf('>')
            if (tagEnd < 0 || tail.indexOf("</video>", tagEnd + 1) < 0) return tail.length
        }
        return 0
    }

    /**
     * 行内样式轻量解析：闭合的 `code` / **bold** 对在流式期间就按最终形态渲染
     * （等宽/加粗 + 标记剥离），减轻完成瞬间切 MarkdownText 的样式跳变幅度。
     * 刻意单遍、无嵌套、不碰段落级语法（列表/标题/代码块仍以原文展示）——
     * 越接近全量 Markdown 解析就越接近被避开的性能陷阱。未闭合的尾部标记
     * 原样保留（闭合后才样式化，天然免跳变）。
     */
    fun styleInline(text: String, codeStyle: SpanStyle): AnnotatedString = buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '`') {
                val close = text.indexOf('`', i + 1)
                if (close > i) {
                    if (close > i + 1) withStyle(codeStyle) { append(text, i + 1, close) }
                    i = close + 1
                    continue
                }
            } else if (c == '*' && i + 1 < text.length && text[i + 1] == '*') {
                val close = text.indexOf("**", i + 2)
                if (close > i + 1) {
                    if (close > i + 2) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(text, i + 2, close)
                    }
                    i = close + 2
                    continue
                }
            }
            append(c)
            i++
        }
    }
}

@Composable
internal fun StreamingResponseBubble(
    text: String,
    // 多模态输出：保留给调用方（AgentChatScreen 的 Lightbox 接线）——流式期间
    // 已改纯 Text 渲染（见下方性能注释），媒体仅在完成态经 AgentMessageBubble
    // 的 MarkdownText 渲染并接 Lightbox；此参数当前不被本气泡消费。
    onImageClick: (String) -> Unit = {}
) {
    // #262 延迟读取：cursor 透明度动画保留 State，在 graphicsLayer 块内读取 ——
    // 旧写法 `primary.copy(alpha = pulse)` 在组合期取值，700ms 循环期间整个
    // StreamingResponseBubble（含容器层）每帧重组，drawWithCache
    // 缓存被逐帧击穿；改为图层 alpha 后动画只失效这一个 Text 的图层。
    val pulseState = rememberInfiniteTransition(label = "stream-cursor").animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "cursor-alpha"
    )
    Column(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        // M3 实色容器（2026-10 流式去玻璃）：与完成态 AgentBubble 同色同形 ——
        // 流式与完成瞬间切换零跳变，且不再有玻璃层的 GPU 材质成本。
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp),
            modifier = Modifier.widthIn(max = 340.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                // 紧凑头部：角色标（与完成态一致；流式未落定无时间戳）
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "AGENT",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                // 性能（#2-c P1-1）：流式期间用纯 Text 渲染增量文本 ——
                // MarkdownText 的 remember(markdown){parseMarkdown} 会让每 33ms
                // 节流 flush 都触发全量重解析+重排版，回复越长单次成本线性涨
                //（整流 O(n²)）。完成/出错/中止时 VM 先 flush 落完整消息再清
                // currentResponse（AgentChatEventApplier.kt ResponseComplete），
                // 完成态由 AgentMessageBubble 的 MarkdownText 接管最终渲染 ——
                // 本气泡只在流式期间存在，无需自带完成分支。
                // 样式对齐 MarkdownText 段落排版（bodyMedium + onSurface），
                // 完成瞬间气泡替换不产生字号/行高跳变。
                // ═══ 两项减轻完成瞬间跳变的流式期补偿 ═══
                // ① 媒体语法尾部：URL 是逐字符流出的，未闭合的 `![image](http…`
                // 原样展示既是视觉噪音（长串裸 URL）又在完成切 MarkdownImage
                //（heightIn(min=96.dp)）时产生布局跳变 —— 尾部未闭合段截掉，
                // 改渲染迷你占位 chip（媒体接收完成前维持稳定高度）。
                // ② 行内 code/bold：闭合对按最终形态样式化（标记剥离 +
                // 等宽/加粗），与完成态 MarkdownText 的行内样式对齐。═══
                val codeStyle = SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    background = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                )
                val pendingMediaTail = remember(text) {
                    StreamingRenderSupport.incompleteMediaTailLength(text)
                }
                val shown = if (pendingMediaTail > 0) text.dropLast(pendingMediaTail) else text
                // ═══ 分段增量渲染（2026-10 流水式输出优化）═══
                // 单 Text 全量重排是长回复后期的主要掉帧源（整流 O(n²)）：
                // 改用 StreamingParagraphSlicer 切「定稿块 + 活动尾段」——
                // 定稿块（配平行组，含代码围栏块整体）只排一次，后续 flush
                // 走 Text 跳过路径；每 flush 仅活动尾段重排。视觉与单 Text
                // 逐行等价（详见 StreamingParagraphText.kt 文件头推导）。
                val slicer = remember { StreamingParagraphSlicer() }
                val slices = remember(shown) { slicer.update(shown) }
                // 定稿块样式缓存：块原文为 key（同块跨 flush 实例不变 →
                // Text 参数 equals → 跳过；重复块共享）。主题切换（codeStyle
                // 变化）→ 缓存重建全量重算，无陈旧样式残留。
                val styledBlocks = remember(codeStyle) { HashMap<String, AnnotatedString>() }
                SelectionContainer {
                    Column {
                        slices.committed.forEach { block ->
                            Text(
                                text = styledBlocks.getOrPut(block) {
                                    StreamingRenderSupport.styleInline(block, codeStyle)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        // 活动尾段：每 flush 重新样式化（唯一真正变化的部分）
                        Text(
                            text = remember(slices.liveTail, codeStyle) {
                                StreamingRenderSupport.styleInline(slices.liveTail, codeStyle)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                if (pendingMediaTail > 0) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Image,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(12.dp)
                            )
                            Text(
                                text = "…",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Text(
                    text = "▍",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .padding(top = 2.dp, start = 1.dp)
                        // 延迟读取点：图层 alpha 与原 color.copy(alpha=pulse) 视觉等效
                        //（纯色字形上两者逐像素一致）
                        .graphicsLayer { alpha = pulseState.value }
                )
            }
        }
    }
}

/** 秒数展示格式：①12.3s ②1m 02s（超过 60s）；0ms 兼容显示 0.0s。 */
internal fun formatThinkingSeconds(durationMs: Long): String {
    val totalSeconds = durationMs / 1000.0
    return if (durationMs >= 60_000) {
        val m = durationMs / 60_000
        val s = (durationMs % 60_000) / 1000.0
        String.format(Locale.US, "%dm %04.1fs", m, s)
    } else {
        String.format(Locale.US, "%.1fs", totalSeconds)
    }
}

@Composable
internal fun ThinkingBubble(
    text: String,
    finished: Boolean = false,
    durationMs: Long = 0,
    liveStartElapsed: Long = 0
) {
    // 胶囊化展开态（用户需求：「思考时展示全部思考内容，思考完自动折叠
    // 成胶囊」）：流式中默认展开（全文可见）；完成态是新的列表项（新 key
    // 新组合实例），默认折叠为单行胶囊 —— 两个状态互不共享 remember。
    var expanded by remember { mutableStateOf(!finished) }

    // 流式思考中：实时秒数计时器（每 200ms 刷新，低于重组节流频率，几乎无开销）。
    val liveSeconds = if (!finished && liveStartElapsed > 0) {
        produceState(initialValue = 0L, key1 = liveStartElapsed) {
            while (true) {
                value = android.os.SystemClock.elapsedRealtime() - liveStartElapsed
                delay(200)
            }
        }.value
    } else 0L

    // M3 实色容器（2026-10 流式去玻璃）：tertiaryContainer 淡染与 REVIEW 评审卡
    // 同族色；Surface onClick 重载自带圆角裁剪涟漪。
    Surface(
        onClick = { expanded = !expanded },
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.35f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.18f),
                    modifier = Modifier.padding(0.dp)
                ) {
                    Text(
                        text = "THINK",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
                Text(
                    text = when {
                        expanded -> stringResource(R.string.chat_thinking_process)
                        finished -> stringResource(R.string.chat_thinking_done_tap)
                        else -> stringResource(R.string.chat_reasoning_in_progress)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.weight(1f)
                )
                // 每一轮模型思考的秒数：流式中实时跳动，完成后定格实测值。
                val secondsText = if (finished) {
                    formatThinkingSeconds(durationMs)
                } else if (liveStartElapsed > 0) {
                    formatThinkingSeconds(liveSeconds)
                } else null
                if (secondsText != null) {
                    Text(
                        text = secondsText,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
                Icon(
                    imageVector = if (expanded) {
                        Icons.Default.KeyboardArrowUp
                    } else {
                        Icons.Default.KeyboardArrowDown
                    },
                    contentDescription = if (expanded) stringResource(R.string.chat_cd_collapse_thinking)
                    else stringResource(R.string.chat_cd_expand_thinking),
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }

            // 展开态才渲染正文；折叠态 = 单行胶囊（THINK 徽标 + 状态标签 +
            // 秒数 + 箭头），不再占五行预览 —— 与工具胶囊同口径的降噪。
            if (expanded) {
                Spacer(modifier = Modifier.height(8.dp))
                // 分段增量渲染（2026-10 流水式输出优化）：与回复流同款治理 ——
                // 定稿行只排一次，每 33ms flush 仅活动尾段重排（深度思考模型
                // 的长思考流同样受益）；视觉与单 Text 逐行等价。思考文本无
                // 行内样式，纯字符串直出。
                val slicer = remember { StreamingParagraphSlicer() }
                val slices = remember(text) { slicer.update(text) }
                Column {
                    slices.committed.forEach { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.85f)
                        )
                    }
                    Text(
                        text = slices.liveTail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.85f)
                    )
                }
            }
        }
    }
}

/**
 * 错误提示块：区别于灰色 System 行，使用红色高亮卡片 + 图标 + 可选重试。
 *
 * @param retryEnabled 重试门禁（流式生成中置 false —— 点重试会取消在途轮次；
 *   与消息菜单同款门禁口径，旧实现菜单有门禁而 Chip 没有）。
 */
@Composable
internal fun ErrorBlock(
    message: String,
    canRetry: Boolean = false,
    onRetry: () -> Unit = {},
    retryEnabled: Boolean = true
) {
    val errorColor = MaterialTheme.colorScheme.error
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    // i18n：Toast 文案在组合内预取（onClick 非组合上下文）
    val copiedErrorToast = stringResource(R.string.chat_copied_error)
    Surface(
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.9f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                drawRoundRect(
                    color = errorColor,
                    style = Stroke(width = 1.5.dp.toPx()),
                    cornerRadius = CornerRadius(12.dp.toPx())
                )
            }
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = stringResource(R.string.chat_execution_error),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.weight(1f))
                // 复制错误信息（便于粘贴给模型 / 提 Issue）
                IconButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(message))
                        Toast.makeText(context, copiedErrorToast, Toast.LENGTH_SHORT).show()
                    },
                    // UI-012：48dp 触区红线（原 28dp）
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = stringResource(R.string.chat_cd_copy_error),
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(15.dp)
                    )
                }
                if (canRetry) {
                    RetryChip(onRetry = onRetry, enabled = retryEnabled)
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

@Composable
internal fun SystemMessage(text: String) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.07f),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
    }
}

// ═══ 反思模式组件 ═══

/**
 * 反思模式评审卡片："生成 → 评审 → 修正"循环中的评审意见。
 * 视觉与思考卡片同族（tertiary），REVIEW 徽章区分"这是对草稿的评审"。
 */
@Composable
internal fun ReflectionReviewBlock(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.35f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.18f)
                ) {
                    Text(
                        text = "REVIEW",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
                Text(
                    text = stringResource(R.string.chat_review_opinion),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            SelectionContainer {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.85f)
                )
            }
        }
    }
}
