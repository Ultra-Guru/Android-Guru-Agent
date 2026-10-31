package com.apex.agent.ui.screen.code

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.apex.agent.R

/**
 * 工具台面板 —— 展开菜单收编的五件套（2026-10 用户规格：工作区/模型/
 * 网络搜索/展开 四件套定版，其余收进向上层级菜单）。
 *
 * 点击展开菜单项 → 菜单收起 → [CodeScreen] 在展开按钮锚点处渲染对应
 * 组件的原弹层（五组件均已支持 `menuOpen`/`onMenuDismiss`/`triggerHidden`
 * 外提参数，Agent 屏默认参数零改动）。
 */
enum class CodePanel {
    /** 斜杠指令菜单（技能/MCP/插件/连接器，coding 作用域）。 */
    SLASH,

    /** FUSION 工具聚合（王冠层级菜单：体系包选配）。 */
    FUSION,

    /** GitHub 连接（连接态/Token/MCP 配置下拉）。 */
    GITHUB,

    /** 小圆环 Toolkit（函数调用/时间/结构化输出/规则）。 */
    TOOLKIT,

    /** 功能聚合（霓虹球 16 项能力面板）。 */
    NEON
}

/**
 * ▲ 展开按钮（工具行第 3 位）：点开向上层级菜单（M3 DropdownMenu 在
 * 屏底自动向上翻）；再点按钮或点空白关闭；展开/收起图标随状态旋转。
 * 菜单项点击不直接干活，而是经 [onPanelChange] 请求 [CodeScreen] 打开
 * 对应原面板（弹层锚点统一挂在展开按钮处）。
 */
@Composable
fun ToolbenchExpandButton(
    activePanel: CodePanel?,
    onPanelChange: (CodePanel?) -> Unit,
    modifier: Modifier = Modifier
) {
    var menuOpen by remember { mutableStateOf(false) }
    val cd = stringResource(R.string.code_toolbench_cd)
    val rotation by animateFloatAsState(
        targetValue = if (menuOpen) 180f else 0f,
        animationSpec = tween(180),
        label = "toolbench_expand_rotation"
    )

    Box(
        modifier = modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .background(
                color = if (menuOpen || activePanel != null) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
                shape = CircleShape
            )
            .semantics { contentDescription = cd }
            .clickable {
                // 点开面板期间再点按钮 = 收面板回菜单（按钮态切换语义）
                if (activePanel != null) onPanelChange(null) else menuOpen = !menuOpen
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.ExpandLess,
            contentDescription = null,
            tint = if (menuOpen || activePanel != null) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(20.dp)
                .rotate(rotation)
        )

        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            modifier = Modifier.sizeIn(minWidth = 220.dp)
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.code_toolbench_slash)) },
                leadingIcon = {
                    Icon(Icons.Default.Terminal, null, modifier = Modifier.size(18.dp))
                },
                onClick = {
                    menuOpen = false
                    onPanelChange(CodePanel.SLASH)
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.code_toolbench_fusion)) },
                leadingIcon = {
                    Icon(Icons.Default.Build, null, modifier = Modifier.size(18.dp))
                },
                onClick = {
                    menuOpen = false
                    onPanelChange(CodePanel.FUSION)
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.code_toolbench_github)) },
                leadingIcon = {
                    Icon(
                        painterResource(R.drawable.ic_github_mark),
                        null,
                        modifier = Modifier.size(18.dp)
                    )
                },
                onClick = {
                    menuOpen = false
                    onPanelChange(CodePanel.GITHUB)
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.code_toolbench_toolkit)) },
                leadingIcon = {
                    Icon(Icons.Default.Tune, null, modifier = Modifier.size(18.dp))
                },
                onClick = {
                    menuOpen = false
                    onPanelChange(CodePanel.TOOLKIT)
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.code_toolbench_neon)) },
                leadingIcon = {
                    Icon(Icons.Default.Extension, null, modifier = Modifier.size(18.dp))
                },
                onClick = {
                    menuOpen = false
                    onPanelChange(CodePanel.NEON)
                }
            )
        }
    }
}
