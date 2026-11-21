package com.apex.agent.ui.component

import android.content.res.TypedArray
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import com.apex.agent.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Agent 任务进行中的品牌动效指示器：「霓虹魔法环」27 帧循环
 * （PR#920 霓虹环形钻石品牌标的动效版，彗星绕环一圈即一轮任务脉动）。
 *
 * - 帧资源：drawable-nodpi 下的 agent_orb_f00 至 f26（nodpi 保证逐帧
 *   1:1 解码，不随密度二次缩放）；帧序由 values/agent_orb.xml 的
 *   array 聚合，R 常量引用，不走 getIdentifier 反射
 * - 解码：Dispatchers.Default 一次性异步解码 27 张（224px 位图约
 *   5MB，仅任务运行期驻留，指示器离场随组合状态释放）
 * - 接线：CodeInputBar 末行 start 侧，isRunning 为真入场，任务结束
 *   随组合消失（「回答时固定左下，直到任务结束」）
 */
@Composable
fun AgentOrbIndicator(
    modifier: Modifier = Modifier,
    size: Dp = 34.dp,
    frameMillis: Long = 47L
) {
    val context = LocalContext.current
    val frameIds = remember {
        val typed: TypedArray = context.resources.obtainTypedArray(R.array.agent_orb_frames)
        try {
            IntArray(typed.length()) { typed.getResourceId(it, 0) }
        } finally {
            typed.recycle()
        }
    }
    val frames by produceState<List<ImageBitmap>?>(initialValue = null) {
        value = withContext(Dispatchers.Default) {
            frameIds.mapNotNull { id ->
                BitmapFactory.decodeResource(context.resources, id)?.asImageBitmap()
            }
        }
    }
    var index by remember { mutableIntStateOf(0) }
    LaunchedEffect(frames) {
        while (frames != null) {
            delay(frameMillis)
            index = (index + 1) % frameIds.size
        }
    }
    val bitmap = frames?.getOrNull(index)
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = stringResource(R.string.agent_orb_running),
            filterQuality = FilterQuality.Medium,
            modifier = modifier.size(size)
        )
    }
}
