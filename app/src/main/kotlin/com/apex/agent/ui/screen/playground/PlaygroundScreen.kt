package com.apex.agent.ui.screen.playground

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.VideogameAsset
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.apex.agent.ui.component.FeedbackSeverity
import com.apex.agent.ui.component.LocalFeedbackController
import com.apex.agent.ui.glass.GlassCard

/**
 * ═══════════════════════════════════════════════════════════════
 *  游乐场 —— 插件化街机目录（一级导航页，玻璃实验室正上方）
 * ═══════════════════════════════════════════════════════════════
 *
 * 游乐场是插件化的：**每个游戏 = 一个自包含 HTML 文件**（内联 vendored
 * pretext @ 0.0.9，零网络请求）+ 同目录 game.json manifest。
 *
 * 目录三个来源并列：
 *  - **内置插件**（assets/playground/games/）：十款 pretext 游戏 ——
 *    每款锚定一个不同的 pretext API（宽度 / 行数 / 收缩包裹 / 逐行变宽
 *    路由 / 分段测量），「测量即玩法」；
 *  - **安装插件**（filesDir/playground/games/）：点「导入游戏」选任意
 *    .html 文件即装即玩（单文件即插件），可卸载；
 *  - **原生街机**《字符风暴 GLYPH STORM》：Compose TextMeasurer 与
 *    WebView 内 pretext 完成同一测量契约（点开进 [GlyphStormSection]）。
 *
 * 同一份 HTML 插件可以：拷出分发 → 任何浏览器可玩；或包进 WebView 壳
 * APK 插件（转化路径见 playground/README.md）—— HTML 是游戏的统一载体。
 */
@Composable
fun PlaygroundScreen() {
    val context = LocalContext.current
    val catalog = remember { PlaygroundCatalog(context) }
    val feedback = LocalFeedbackController.current

    var games by remember { mutableStateOf(catalog.loadGames()) }
    var activeGame by remember { mutableStateOf<PlaygroundGame?>(null) }
    var showNativeGame by remember { mutableStateOf(false) }
    var pendingUninstall by remember { mutableStateOf<PlaygroundGame?>(null) }

    // ── 导入启动器：选 .html → 读文本 → 安装 → 刷新目录 + 反馈 ──
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val html = runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
        }.getOrNull()
        if (html == null) {
            feedback.show("读取所选文件失败", severity = FeedbackSeverity.ERROR)
            return@rememberLauncherForActivityResult
        }
        val fileName = resolveDisplayName(context, uri) ?: "imported-game.html"
        when (val result = catalog.installHtmlGame(html, fileName)) {
            is InstallResult.Installed -> {
                games = catalog.loadGames()
                feedback.show("已安装游戏插件「${result.id}」—— 目录见")
            }
            is InstallResult.Conflict ->
                feedback.show("插件 id「${result.id}」与内置或已装游戏冲突 —— 拒绝覆盖", severity = FeedbackSeverity.WARNING)
            InstallResult.NotHtml ->
                feedback.show("所选文件不是 HTML —— 无法安装", severity = FeedbackSeverity.ERROR)
            InstallResult.IoError ->
                feedback.show("安装写盘失败（详见运行日志）", severity = FeedbackSeverity.ERROR)
        }
    }

    // ── 全幅游戏态：HTML 插件在 WebView 街机屏运行 ──
    activeGame?.let { game ->
        HtmlGameScreen(
            game = game,
            onExit = { activeGame = null }
        )
        return
    }

    // ── 原生街机详情态 ──
    if (showNativeGame) {
        GlyphStormSection(onBack = { showNativeGame = false })
        return
    }

    // ── 目录态 ──
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 16.dp, vertical = 12.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { ArcadeHeader(onImport = { importLauncher.launch("text/html") }) }
        item { NativeGameCard(onOpen = { showNativeGame = true }) }
        item {
            Text(
                "PRETEXT 插件街机 · ${games.count { it.source == GameSource.BUILTIN }} 内置",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        val rows = games.chunked(2)
        items(rows.size) { rowIdx ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                rows[rowIdx].forEach { game ->
                    GameCard(
                        game = game,
                        onOpen = { activeGame = game },
                        onUninstall = { pendingUninstall = game },
                        modifier = Modifier.weight(1f)
                    )
                }
                if (rows[rowIdx].size == 1) {
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
        item { PluginDocsCard() }
    }

    // ── 卸载确认门 ──
    pendingUninstall?.let { game ->
        AlertDialog(
            onDismissRequest = { pendingUninstall = null },
            title = { Text("卸载游戏插件") },
            text = { Text("确定卸载「${game.displayName}」？其本地目录与战绩将被删除。") },
            confirmButton = {
                TextButton(onClick = {
                    val ok = catalog.uninstall(game)
                    games = catalog.loadGames()
                    pendingUninstall = null
                    feedback.show(
                        if (ok) "已卸载「${game.manifest.id}」" else "卸载失败（详见运行日志）",
                        severity = if (ok) FeedbackSeverity.INFO
                        else FeedbackSeverity.ERROR
                    )
                }) { Text("卸载", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingUninstall = null }) { Text("取消") }
            }
        )
    }
}

// ═══════════════════════════════════════════════════════════════
//  页头：插件街机宣言 + 导入入口
// ═══════════════════════════════════════════════════════════════

@Composable
private fun ArcadeHeader(onImport: () -> Unit) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.SportsEsports,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp)
                )
                Column {
                    Text(
                        "游乐场 · 插件街机",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "PRETEXT PLUGIN ARCADE",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                "每个游戏都是一个自包含 HTML 插件（内联 chenglou/pretext，MIT，零网络请求）。" +
                    "十款内置各锚定一个不同的测量 API —— 宽度、行数、收缩包裹、逐行变宽路由。" +
                    "任意 .html 游戏文件也可以导入即玩；同一 HTML 还能包成 APK 插件（见仓库" +
                    "playground/README.md 的转化指南）。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                ) {
                    Row(
                        modifier = Modifier
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                            .clickable { onImport() },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AddCircleOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            "导入游戏 HTML",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  原生游戏卡
// ═══════════════════════════════════════════════════════════════

@Composable
private fun NativeGameCard(onOpen: () -> Unit) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpen() }
                .padding(16.dp)
        ) {
            Icon(
                imageVector = Icons.Default.VideogameAsset,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "字符风暴 · GLYPH STORM",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    SourceBadge("原生")
                }
                Text(
                    "Compose TextMeasurer 实测宽度驱动的物理拼词街机 —— 插件街机的原生孪生",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                "进入",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  游戏插件卡
// ═══════════════════════════════════════════════════════════════

@Composable
private fun GameCard(
    game: PlaygroundGame,
    onOpen: () -> Unit,
    onUninstall: () -> Unit,
    modifier: Modifier = Modifier
) {
    GlassCard(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpen() }
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    game.manifest.name.ifBlank { game.manifest.id },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                if (game.source == GameSource.INSTALLED) {
                    IconButton(onClick = onUninstall, modifier = Modifier.size(28.dp)) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = "卸载游戏",
                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
            game.manifest.nameEn?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            Text(
                game.manifest.tagline,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SourceBadge(if (game.source == GameSource.BUILTIN) "内置" else "已装")
                if (game.manifest.apis.isNotEmpty()) {
                    Text(
                        game.manifest.apis.take(2).joinToString(" + "),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun SourceBadge(label: String) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

// ═══════════════════════════════════════════════════════════════
//  插件化说明卡（转化路径指引）
// ═══════════════════════════════════════════════════════════════

@Composable
private fun PluginDocsCard() {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(16.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Extension,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(22.dp)
            )
            Column {
                Text(
                    "把游戏做成插件 / 把插件变成 APK",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "游戏插件格式（game.json + 单文件 index.html）、安装目录与" +
                        "「HTML → WebView 壳 APK 插件」转化模板见仓库 playground/ 与" +
                        "docs/playground-plugin-arcade.md。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  工具
// ═══════════════════════════════════════════════════════════════

/** SAF Uri → 展示文件名（查询 DISPLAY_NAME，失败退回 lastPathSegment）。非组合上下文可用。 */
private fun resolveDisplayName(context: android.content.Context, uri: Uri): String? =
    runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        } ?: uri.lastPathSegment
    }.getOrNull() ?: uri.lastPathSegment
