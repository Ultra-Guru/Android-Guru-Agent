package com.apex.agent.ui.glass

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * ═══ 玻璃模态幕帘（门控弹层的背景可读性保障）═══
 *
 * 问题（2026-10 用户实测反馈）：工具/权限门的结构化提问卡（QuestionCard）
 * 以液态玻璃形态悬浮于消息流之上时，玻璃只对**采样到的图层**做 GPU blur
 * —— 采样盲区（动画图层 / 非 LazyColumn 成员 / 采样时序差）背后的文字
 * 以锐利形态透过半透明霜面干扰弹层阅读（「平衡：会修改数据或状态」一行
 * 与底层气泡「你好」叠字的实拍截图）。
 *
 * 修复策略（双保险，不依赖采样完备性）：
 *  1. [glassModalBlur]：弹层挂起时，对被覆盖内容整体施加 RenderEffect
 *     blur（API 31+）。修饰顺序必须在 `layerBackdrop` **之前**（外层）——
 *     采样录制的内层内容保持原样，玻璃自身 blur 语义不变；屏幕上的真实
 *     内容则已模糊，透出霜面的只能是模糊像素。
 *  2. [GlassModalScrim]：主题 scrim 蒙层。API < 31 无 RenderEffect，blur
 *     是诚实 no-op —— 加重 scrim 兜底；API 31+ 轻 scrim 提供模态聚焦感。
 *     同时消费全部点击（模态语义：门控问题必须先作答，避免「边模糊边
 *     操作底层列表」的错乱状态）。
 *
 * 两个入口配对使用：内容侧挂 blur，同 Box 内、悬浮栈之下挂 scrim。
 */
object GlassModalVeil {

    /** 弹层激活时被覆盖内容的模糊半径（GPU RenderEffect；API < 31 no-op）。 */
    val BLUR_RADIUS: Dp = 18.dp
}

/**
 * 模态玻璃幕帘的内容侧模糊：[enabled] 时对被覆盖内容整体 blur。
 *
 * 顺序契约：必须放在 `Modifier.layerBackdrop(...)` **之前**（链上更靠外）——
 * layerBackdrop 录制的是其内层子树（未模糊原样），玻璃采样源不失真；
 * 屏幕呈现路径经过本 blur 节点（graphicsLayer RenderEffect），真实内容模糊。
 */
fun Modifier.glassModalBlur(enabled: Boolean, radius: Dp = GlassModalVeil.BLUR_RADIUS): Modifier =
    if (enabled) this.blur(radius) else this

/**
 * 模态玻璃幕帘的蒙层：主题 scrim + 点击拦截。
 *
 * - API 31+：blur 已由 [glassModalBlur] 承担，轻 scrim（0.20f）只做聚焦；
 * - API < 31：blur no-op，重 scrim（0.55f）保证被覆盖文字不再与弹层内容
 *   叠字干扰（诚实降级，不冒充模糊）。
 */
@Composable
fun GlassModalScrim(modifier: Modifier = Modifier) {
    val preS = Build.VERSION.SDK_INT < Build.VERSION_CODES.S
    Box(
        modifier = modifier
            .background(
                MaterialTheme.colorScheme.scrim.copy(alpha = if (preS) 0.55f else 0.20f)
            )
            // 模态语义：吞掉全部点击，门控问题未决前底层列表不可交互
            .pointerInput(Unit) { detectTapGestures { } }
    )
}
