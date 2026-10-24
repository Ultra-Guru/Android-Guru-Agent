package com.apex.agent.ui.component

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apex.agent.R
import com.apex.agent.core.tools.fusion.FusionBundleDigest
import com.apex.agent.core.tools.fusion.FusionBundleManifest
import com.apex.agent.fusion.FusionBundleController
import com.apex.agent.ui.icons.CrownGold
import com.apex.agent.ui.icons.FusionCrown
import com.apex.agent.ui.icons.FusionCrownCanvas
import com.apex.agent.ui.screen.agent.PendingPipelineCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * # FusionBundle 王冠入口（输入栏工具栏）
 *
 * FUSION 工具聚合体系的唯一入口：**精致王冠图标**（形制临摹开源
 * 图标库 Lucide 的 crown，见 [FusionCrownCanvas]）置于输入框工具栏，
 * 点击弹出**层级菜单**：
 *
 * - 第一级第一项 = 体系缩写名 **FUSION**（FusionBundle 工具聚合系统）
 *   —— 王冠 + 名称 + 组件数角标 + 展开箭头；
 * - 点开 FUSION 后，第二级列出已装的具体体系包（REA / Superpowers /
 *   SuperClaude / BMAD / Anthropic Skills / Claude Flow / Shrimp …），
 *   含版本、描述、技能·MCP·工具·引擎计数；
 * - **点选任一包**：即激活（装技能 + 连 MCP + 注入体系 agent 工作流），
 *   同时该包以 chip 形式**出现在输入框中**（[onBundleChip] 回灌），
 *   发送后 agent 将**强制使用该体系**完成任务（系统侧「强制使用协议」
 *   + 发送侧 `/fusion:<id>` 强制指令双保险，见 SlashCommandRouter）。
 *
 * 交互模式与斜杠菜单（SlashCommandButton）同源：锚定 Popup、上方
 * 空间不足自动向下翻转、`focusable = false` 不抢键盘焦点、返回键
 * BackHandler 兜底、入场缩放淡入动画。
 *
 * @param controller 聚合控制面（bundles / activeBundle / activate）
 * @param isActive 当前是否有激活的包（王冠点亮呼吸）
 * @param onBundleChip 选中包后回灌输入框的 chip（调用方接 VM）
 * @param scope 工位（"agent" | "coding"；菜单按包 scope 过滤）
 */
@Composable
fun FusionBundleButton(
    controller: FusionBundleController,
    isActive: Boolean,
    onBundleChip: (PendingPipelineCommand) -> Unit,
    modifier: Modifier = Modifier,
    scope: String = "agent"
) {
    var showMenu by remember { mutableStateOf(false) }
    // 自定义聚合组合器（新建入口 + 自建包编辑入口共用一个挂载位）
    var composerEditing by remember { mutableStateOf<FusionBundleManifest?>(null) }
    var showComposer by remember { mutableStateOf(false) }
    val openCd = stringResource(
        if (isActive) R.string.fusion_cd_bundle_active else R.string.fusion_cd_bundle
    )

    // ── Claude 插件包导入（SAF 选 zip → 安全解压 → 导入器 → 聚合注册表）──
    val context = androidx.compose.ui.platform.LocalContext.current
    val feedback = LocalFeedbackController.current
    val importScope = rememberCoroutineScope()
    val importDonePattern = stringResource(R.string.fusion_import_claude_done)
    val importFailedPattern = stringResource(R.string.fusion_import_claude_failed)
    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val cacheDir = java.io.File(context.cacheDir, "claude-import")
                    .apply { deleteRecursively(); mkdirs() }
                val zipFile = java.io.File(cacheDir, "pack.zip")
                context.contentResolver.openInputStream(uri)!!.use { input ->
                    zipFile.outputStream().use { output -> input.copyTo(output) }
                }
                val extractDir = java.io.File(cacheDir, "unzipped")
                com.apex.agent.core.tools.skill.SafeZipExtractor.extract(zipFile, extractDir)
                com.apex.agent.core.tools.claudeimport.ClaudePluginImporter.importFromDir(extractDir)
            }
            result.fold(
                onSuccess = { imported ->
                    val installed = controller.registryForTools.install(imported.manifestJson)
                    installed.fold(
                        onSuccess = {
                            feedback.show(
                                String.format(
                                    importDonePattern, it.name,
                                    it.skills.size,
                                    it.plugins.firstOrNull()
                                        ?.let { p -> countSlashCommands(p) } ?: 0,
                                    if (it.agent.persona.isBlank()) 0 else 1
                                ),
                                FeedbackSeverity.INFO
                            )
                        },
                        onFailure = {
                            feedback.show(
                                String.format(importFailedPattern, it.message ?: ""),
                                FeedbackSeverity.WARNING
                            )
                        }
                    )
                },
                onFailure = {
                    feedback.show(
                        String.format(importFailedPattern, it.message ?: ""),
                        FeedbackSeverity.WARNING
                    )
                }
            )
            // 导入缓存清理（保留注册表落盘的 bundle json，临时物即用即弃）
            java.io.File(context.cacheDir, "claude-import").deleteRecursively()
        }
    }

    // 锚点纵坐标（窗口系；翻转判定用）
    var anchorTopInWindow by remember { mutableStateOf(Float.MAX_VALUE) }

    Box(
        modifier = modifier
            // UI-012：48dp 触区红线（与输入行全列按钮统一 48 系）
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .onGloballyPositioned { coords ->
                anchorTopInWindow = coords.positionInWindow().y
            }
            .semantics { contentDescription = openCd }
            // 激活态换金冠底色光环；常态与同行按钮统一 primary 12% 圆底
            .background(
                color = if (isActive) CrownGold.copy(alpha = 0.15f)
                else MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                shape = CircleShape
            )
            // 修复：原实现漏挂 clickable —— 点击王冠无任何反应（showMenu
            // 永远 false，层级菜单弹不出）；与 SlashCommandButton 同款收尾。
            .clickable { showMenu = true },
        contentAlignment = Alignment.Center
    ) {
        // 精致王冠（激活态宝石微光呼吸）
        FusionCrownCanvas(active = isActive, modifier = Modifier.size(24.dp))

        if (showMenu) {
            FusionCrownMenuPopup(
                controller = controller,
                scope = scope,
                anchorTopInWindow = anchorTopInWindow,
                onBundleChip = onBundleChip,
                onOpenComposer = { editing ->
                    composerEditing = editing
                    showComposer = true
                    showMenu = false
                },
                onImportClaude = {
                    showMenu = false
                    importLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
                },
                onDismiss = { showMenu = false }
            )
        }
    }

    if (showComposer) {
        FusionBundleComposerDialog(
            controller = controller,
            editing = composerEditing,
            onDismiss = { showComposer = false }
        )
    }
}

/**
 * # FUSION 层级菜单弹窗（王冠按钮锚定）
 *
 * 层级结构（用户钦定形态）：
 *
 *     👑 FUSION                      ← 第一项：体系缩写名（展开/收起）
 *        ├─ REA · Reverse Engineer Anything   v6.5.0  [内置] ✓使用中
 *        ├─ Superpowers                       v…
 *        └─ …（技能·MCP·工具·引擎计数 + 描述）
 *     ─────────────────────────────
 *     点选包 → 激活 + chip 进输入框 + 菜单收起；使用中 → 「停用」可卸甲。
 *
 * 空间翻转 / focusable=false / BackHandler：与 SlashMenuPopup 同款
 * （v5 空间翻转防盖输入行；菜单打开才组合 Popup，v3 点击卡死防线）。
 */
@Composable
private fun FusionCrownMenuPopup(
    controller: FusionBundleController,
    scope: String,
    anchorTopInWindow: Float,
    onBundleChip: (PendingPipelineCommand) -> Unit,
    onOpenComposer: (editing: FusionBundleManifest?) -> Unit,
    onImportClaude: () -> Unit,
    onDismiss: () -> Unit
) {
    val bundlesRaw by controller.bundles.collectAsStateWithLifecycle()
    val active by controller.activeBundle.collectAsStateWithLifecycle()
    val feedback = LocalFeedbackController.current
    val menuScope = rememberCoroutineScope()

    // 工位过滤（"all" 包放行；scope 不符的包不进菜单 —— 防错位激活）
    val bundles = remember(bundlesRaw, scope) {
        bundlesRaw.filter { it.scope == "all" || it.scope == scope }
    }

    // 异步回调里不可调 stringResource —— 模板在组合期预解析（仓库惯例）
    val activatedPattern = stringResource(R.string.fusion_result_activated)
    val skippedPattern = stringResource(R.string.fusion_result_skipped)
    val failedText = stringResource(R.string.fusion_result_failed)
    val deactivatedPattern = stringResource(R.string.fusion_result_deactivated)
    val installedPattern = stringResource(R.string.fusion_result_installed)
    val installFailedText = stringResource(R.string.fusion_install_failed)

    // ── 空间翻转判定：锚点上方能否容下 320dp（不足则向下生长）──
    val view = LocalView.current
    val density = LocalDensity.current
    val flipDown = remember(anchorTopInWindow) {
        val visible = android.graphics.Rect()
        view.getWindowVisibleDisplayFrame(visible)
        val spaceAbovePx = anchorTopInWindow - visible.top
        val needPx = with(density) { 320.dp.toPx() }
        spaceAbovePx < needPx
    }

    // 第一级展开态：默认展开（点王冠即见体系包 —— 一跳直达黄金路径）
    var expanded by remember { mutableStateOf(true) }

    Popup(
        onDismissRequest = onDismiss,
        alignment = if (flipDown) Alignment.TopStart else Alignment.BottomStart,
        offset = IntOffset(0, with(density) { (if (flipDown) 4 else -4).dp.roundToPx() }),
        // focusable = false：不抢焦点（键盘不收起）；返回键 BackHandler 兜底
        properties = PopupProperties(focusable = false)
    ) {
        BackHandler(onBack = onDismiss)

        // 入场动画（组合即播放）
        val entrance = remember { MutableTransitionState(false).apply { targetState = true } }
        AnimatedVisibility(
            visibleState = entrance,
            enter = fadeIn(tween(150)) +
                scaleIn(initialScale = 0.95f, animationSpec = tween(150)) +
                slideInVertically(
                    initialOffsetY = { if (flipDown) 8 else -8 },
                    animationSpec = tween(150)
                )
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                shadowElevation = 8.dp,
                tonalElevation = 3.dp,
                modifier = Modifier.width(304.dp)
            ) {
                Column(
                    modifier = Modifier
                        .padding(8.dp)
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    // ═══ 第一级第一项：FUSION（体系缩写名）═══
                    FusionSystemHeaderItem(
                        bundleCount = bundles.size,
                        expanded = expanded,
                        onToggle = { expanded = !expanded }
                    )

                    // ═══ 第二级：具体体系包 ═══
                    AnimatedVisibility(
                        visible = expanded,
                        enter = expandVertically(animationSpec = tween(150)),
                        exit = shrinkVertically(animationSpec = tween(150))
                    ) {
                        Column(modifier = Modifier.padding(start = 4.dp)) {
                            if (bundles.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.fusion_picker_empty),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                )
                            } else {
                                bundles.forEach { digest ->
                                    FusionBundleMenuRow(
                                        digest = digest,
                                        isActive = active?.id == digest.id,
                                        onSelect = {
                                            menuScope.launch {
                                                // 工位 scope 透传（all 包双工位可激活；
                                                // 域包错位激活在 activator 层拒绝）
                                                controller.activate(digest.id, scope = scope).fold(
                                                    onSuccess = { act ->
                                                        // ★ chip 进输入框（agent 将强制使用该体系）
                                                        onBundleChip(
                                                            PendingPipelineCommand(
                                                                type = "fusion",
                                                                id = digest.id,
                                                                label = "\uD83D\uDC51 ${digest.name}"
                                                            )
                                                        )
                                                        val skippedNote =
                                                            if (act.skipped.isEmpty()) ""
                                                            else String.format(skippedPattern, act.skipped.size)
                                                        feedback.show(
                                                            String.format(activatedPattern, act.bundleName) + skippedNote,
                                                            FeedbackSeverity.INFO
                                                        )
                                                        onDismiss()
                                                    },
                                                    onFailure = {
                                                        feedback.show(
                                                            it.message ?: failedText,
                                                            FeedbackSeverity.WARNING
                                                        )
                                                    }
                                                )
                                            }
                                        },
                                        onDeactivate = {
                                            controller.deactivate()
                                                .onFailure {
                                                    feedback.show(
                                                        it.message ?: failedText,
                                                        FeedbackSeverity.WARNING
                                                    )
                                                }
                                                .onSuccess {
                                                    feedback.show(
                                                        String.format(deactivatedPattern, digest.name),
                                                        FeedbackSeverity.INFO
                                                    )
                                                }
                                        },
                                        onEdit = {
                                            // 自建包专属：打开组合器编辑成员（内置包不透出）
                                            onOpenComposer(controller.registryForTools.get(digest.id))
                                        },
                                        onInstallMembers = {
                                            // 安装型聚合入口：把成员装到位（仓库引用按需
                                            // 下载），不激活不注入 —— 激活仍走点选整行。
                                            menuScope.launch {
                                                controller.installMembers(digest.id).fold(
                                                    onSuccess = { report ->
                                                        val note = if (report.skipped.isEmpty()) ""
                                                        else String.format(skippedPattern, report.skipped.size)
                                                        feedback.show(
                                                            String.format(
                                                                installedPattern, report.bundleName,
                                                                report.installedSkills.size,
                                                                report.configuredServers.size +
                                                                    report.connectedServers.size,
                                                                report.installedPlugins.size
                                                            ) + note,
                                                            FeedbackSeverity.INFO
                                                        )
                                                    },
                                                    onFailure = {
                                                        feedback.show(
                                                            it.message ?: installFailedText,
                                                            FeedbackSeverity.WARNING
                                                        )
                                                    }
                                                )
                                            }
                                        }
                                    )
                                }
                            }
                            // 自定义聚合入口（组合器新建路径）
                            Surface(
                                onClick = { onOpenComposer(null) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.06f)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = null,
                                        tint = CrownGold,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = stringResource(R.string.fusion_composer_entry),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        color = CrownGold
                                    )
                                }
                            }
                            // Claude 插件包导入入口（zip → 聚合包）
                            Surface(
                                onClick = onImportClaude,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                color = Color.Transparent
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Download,
                                        contentDescription = stringResource(R.string.fusion_import_claude_cd),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = stringResource(R.string.fusion_import_claude_entry),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            // 强制使用语义脚注（用户钦定行为说明）
                            Text(
                                text = stringResource(R.string.fusion_menu_forced_footnote),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 层级菜单第一项：**FUSION**（工具聚合系统缩写名）。
 *
 * 王冠图标 + 大写缩写名 + 体系标语 + 已装包数角标 + 展开箭头（旋转动画）。
 * 点按切换展开 —— 第二级透出具体体系包。
 */
@Composable
private fun FusionSystemHeaderItem(
    bundleCount: Int,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(durationMillis = 150),
        label = "fusion_header_arrow"
    )
    Surface(
        onClick = onToggle,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = if (expanded) {
            CrownGold.copy(alpha = 0.10f)
        } else Color.Transparent
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 王冠（体系身份符号，菜单头部静态版）
            FusionCrownCanvas(active = false, modifier = Modifier.size(22.dp))
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.fusion_menu_system_name),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(R.string.fusion_menu_system_tagline),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // 已装包数角标
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = CrownGold.copy(alpha = 0.15f)
            ) {
                Text(
                    text = bundleCount.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = CrownGold,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier
                    .size(20.dp)
                    .rotate(arrowRotation),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 第二级体系包行：REA / Superpowers / 自建聚合包 …
 *
 * 名称 + 版本 + 内置/自建徽章 + 使用中标记；描述与「技能·MCP·插件·工具·
 * 引擎」计数一行式；非激活态整行可点（激活 + chip + 收起菜单），激活态追加
 * 「停用」按钮（换装其他包时先卸甲）；自建包（非 bundled）追加「编辑」
 * 图标钮 → 组合器编辑成员。
 */
@Composable
private fun FusionBundleMenuRow(
    digest: FusionBundleDigest,
    isActive: Boolean,
    onSelect: () -> Unit,
    onDeactivate: () -> Unit,
    onEdit: () -> Unit,
    onInstallMembers: () -> Unit
) {
    Surface(
        onClick = onSelect,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = if (isActive) {
            CrownGold.copy(alpha = 0.12f)
        } else Color.Transparent
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = digest.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (digest.bundled) {
                    Text(
                        text = stringResource(R.string.fusion_badge_bundled),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else if (digest.id.startsWith("custom-")) {
                    Text(
                        text = stringResource(R.string.fusion_composer_custom_badge),
                        style = MaterialTheme.typography.labelSmall,
                        color = CrownGold
                    )
                }
                Text(
                    text = "v${digest.version}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (isActive) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = stringResource(R.string.fusion_menu_active_badge),
                        tint = CrownGold,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            if (digest.description.isNotBlank()) {
                Text(
                    text = digest.description,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 4.dp)
            ) {
                Text(
                    text = stringResource(
                        R.string.fusion_row_components,
                        digest.skillCount, digest.mcpServerCount, digest.pluginCount,
                        digest.toolHintCount, digest.engineCount
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                if (isActive) {
                    Text(
                        text = stringResource(R.string.fusion_menu_active_badge),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = CrownGold
                    )
                    OutlinedButton(
                        onClick = onDeactivate,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 10.dp, vertical = 2.dp
                        ),
                        modifier = Modifier.height(26.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.fusion_action_deactivate),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                } else {
                    Text(
                        text = digest.trustLevel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (!digest.bundled) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = stringResource(R.string.fusion_composer_edit_cd),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(20.dp)
                            .clickable(onClick = onEdit)
                    )
                }
                if (!isActive) {
                    Text(
                        text = stringResource(R.string.fusion_action_install_members),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable(onClick = onInstallMembers)
                    )
                }
            }
        }
    }
}

/**
 * # FusionBundle 激活态横幅（输入栏内一行式）
 *
 * 当前激活包的常驻指示：王冠 + 包名 + 体系 agent 运行中 + 停用钮。
 * 挂在附件预览条之下 —— 用户随时知道「这个体系的 agent 正在运行」。
 */
@Composable
fun FusionBundleActiveBanner(
    bundleName: String,
    onDeactivate: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = CrownGold.copy(alpha = 0.10f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Icon(
                imageVector = FusionCrown,
                contentDescription = stringResource(R.string.fusion_cd_bundle_active),
                tint = CrownGold,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = stringResource(R.string.fusion_banner_active, bundleName),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            OutlinedButton(
                onClick = onDeactivate,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                modifier = Modifier.height(28.dp)
            ) {
                Text(
                    text = stringResource(R.string.fusion_action_deactivate),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

/** EntryPoint：从 Application 取 FusionBundleController 单例。 */
@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface FusionBundleEntryPoint {
    fun fusionBundleController(): FusionBundleController
}

/** 统计聚合内联插件（commands 载体）贡献的斜杠指令数（导入反馈文案用）。 */
private fun countSlashCommands(
    ref: com.apex.agent.core.tools.fusion.FusionPluginRef
): Int = runCatching {
    (ref.manifest["contributions"] as? kotlinx.serialization.json.JsonObject)
        ?.get("slashCommands") as? kotlinx.serialization.json.JsonArray
}.getOrNull()?.size ?: 0

/** Compose 中获取 FusionBundle 控制器单例（随 Application 上下文记忆化）。 */
@Composable
fun rememberFusionBundleController(): FusionBundleController {
    val context = androidx.compose.ui.platform.LocalContext.current
    val controller = androidx.compose.runtime.remember(context) {
        dagger.hilt.android.EntryPointAccessors.fromApplication(
            context.applicationContext,
            FusionBundleEntryPoint::class.java
        ).fusionBundleController()
    }
    // 暖启（幂等）：恢复上次激活的包 —— 组件装配完成后才解析引擎（DI 破环配套）
    controller.ensureWarmedUp()
    return controller
}
