package com.apex.agent.ui.screen.terminal.scheme

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apex.agent.R
import com.apex.agent.ui.screen.terminal.ConsoleTheme

/**
 * T87：终端配色方案选择器（底部弹层）。
 *
 * Termux properties 的可视化等价物：31 套内置方案网格陈列，每张卡片 =
 * 迷你终端预览（方案底色 + 8 色 ANSI 色条 + 提示符示例），点击即切换
 *（VM 的 schemeId StateFlow → 渲染树调色板即时换色，无需重启会话）。
 *
 * 纯展示组件 —— 选中态/回调由宿主传入；不直接触碰持久化。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalSchemePickerSheet(
    currentSchemeId: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(Icons.Default.Palette, contentDescription = null, tint = ConsoleTheme.accent)
                Text(
                    stringResource(R.string.term_scheme_picker_title),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.term_scheme_picker_desc),
                fontSize = 12.sp,
                color = Color(0xFF8A9AA0)
            )
            Spacer(Modifier.height(12.dp))

            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(560.dp)
            ) {
                items(TerminalColorSchemeRegistry.all(), key = { it.id }) { scheme ->
                    SchemeCard(
                        scheme = scheme,
                        selected = scheme.id == currentSchemeId,
                        onClick = { onPick(scheme.id) }
                    )
                }
            }
        }
    }
}

/** 单张方案卡片：底色块 + 提示符示例 + 8 色条 + 选中角标。 */
@Composable
private fun SchemeCard(
    scheme: TerminalColorScheme,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        border = if (selected) {
            androidx.compose.foundation.BorderStroke(2.dp, ConsoleTheme.accent)
        } else {
            androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2A3530))
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier
                .background(Color(0xFF101714))
                .padding(10.dp)
        ) {
            // ── 迷你终端预览（方案真实底/前景色）──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(scheme.backgroundC)
                    .padding(8.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "user@android:~$ ls",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = Color(scheme.green.toInt())
                    )
                    Text(
                        "build  docs  src",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = scheme.foregroundC
                    )
                    Text(
                        "user@android:~$ _",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = Color(scheme.yellow.toInt())
                    )
                }
                if (selected) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(20.dp)
                            .background(ConsoleTheme.accent, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = Color(0xFF06120D),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                scheme.name,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
            )
            Text(
                scheme.nameZh,
                fontSize = 11.sp,
                color = Color(0xFF8A9AA0)
            )
            Spacer(Modifier.height(6.dp))
            // ── 8 基础色色条（ANSI black→white）──
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (c in scheme.previewColors) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color(c.toInt()))
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (c in scheme.ansi.subList(8, 16)) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color(c.toInt()))
                    )
                }
            }
        }
    }
}
