package com.apex.agent.changelog

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.apex.agent.R
import com.apex.agent.ui.component.ImageLightbox
import com.apex.agent.update.UpdateCenter
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ═══════════════════════════════════════════════════════════════════════════
 *  更新日志弹窗层（changelog 通道 v1）—— 启动宿主 + 内容弹窗 + 历史回看
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * 三件套：
 * - [ChangelogStartupHost] —— ApexRoot 接线点。启动后台静默判定
 *   （远端清单 + 内置副本 + effectiveVersionCode 三源 → [ChangelogGate]），
 *   命中则弹窗；离线/异常/未命中一律静默 —— 绝不阻塞启动、不弹错误；
 * - [ChangelogEntryDialog] —— 内容弹窗（三模式 × 两级「下一步」：
 *   手动日志 →「下一步 · 功能变更」→ PR 合并内容，两级独立不冲突）。
 *   强制语义：非浏览模式下点外部/返回键不关闭（对齐「更新后强制弹出」
 *   的产品要求），但「知道了」恒可达 —— 绝不锁死用户；
 * - [ChangelogHistoryDialog] —— 关于页「更新日志」入口的两级回看
 *   （历史列表 → 单版详情），可自由关闭。
 *
 * 渲染策略：notes 结构化要点优先（零 Markdown 依赖）+ images 配图图廊
 * （Coil 缩略图 → 复用 [ImageLightbox] 全屏：双指缩放 / 拖拽 / 双击缩放）；
 * notes 缺失时降级 markdown 原文纯文本展示 —— 不为此引入渲染器重依赖。
 */

/** 「前往下载」的兜底出口：本版 Release 页缺失时去发布页列表。 */
private const val RELEASES_FALLBACK_URL =
    "https://github.com/Ultra-Guru/Android-Guru-Agent-Release/releases"

/** 弹窗模式 —— 按钮组与关闭语义由此驱动。 */
private enum class ChangelogDialogMode {
    /** 升级/热更后首启的「本次更新内容」（强制，单按钮）。 */
    WHATS_NEW,

    /** 远端领先时的「发现新版本」（强制，前往下载 + 知道了）。 */
    NEW_VERSION,

    /** 关于页历史回看的详情（自由关闭，可打开发布页）。 */
    BROWSE
}

/**
 * 启动弹窗宿主 —— ApexRoot 组合一次，内部完成「拉取 → 判定 → 弹窗 → 落已读」。
 *
 * @param autoUpdateCheck 设置中心「自动检查更新」开关 —— 关闭时零网络请求
 *   （对齐设置页「关闭后启动零检查零浮窗」的承诺），仅本地内置副本参与的
 *   「本版日志」判定照常（升级后仍能看到本版更新内容）。
 */
@Composable
fun ChangelogStartupHost(autoUpdateCheck: Boolean) {
    val context = LocalContext.current
    val store = remember { SeenChangelogStore(context) }
    // 仓储用 applicationContext —— remember 跨配置变更存活，绝不持 Activity
    val repo = remember { ChangelogRepository(context.applicationContext) }
    var active by remember {
        mutableStateOf<ChangelogGate.Decision>(ChangelogGate.Decision.None)
    }
    var settled by remember { mutableStateOf(false) }

    LaunchedEffect(autoUpdateCheck) {
        // 整段 runCatching：更新日志是增益路径，任何环境异常（含预览态下
        // UpdateCenter 未初始化）折叠为 None，绝不向启动链路抛异常。
        active = runCatching {
            withContext(Dispatchers.IO) {
                val effective = UpdateCenter.hotStore.effectiveVersionCode()
                val baselineInit = store.ensureBaseline(effective)
                val remote = if (autoUpdateCheck) repo.fetchRemote() else null
                val embedded = repo.loadEmbedded()
                ChangelogGate.decide(
                    remote = remote,
                    embedded = embedded,
                    effectiveVersionCode = effective,
                    seenVersionCode = store.seenVersionCode(),
                    seenAvailableVersionCode = store.seenAvailableVersionCode(),
                    baselineInitialized = baselineInit
                )
            }
        }.getOrDefault(ChangelogGate.Decision.None)
        settled = true
    }
    if (!settled) return

    when (val decision = active) {
        is ChangelogGate.Decision.NewVersionAvailable ->
            ChangelogEntryDialog(
                entry = decision.entry,
                mode = ChangelogDialogMode.NEW_VERSION,
                onDismiss = {
                    store.markAvailableSeen(decision.entry.versionCode)
                    active = ChangelogGate.Decision.None
                }
            )

        is ChangelogGate.Decision.WhatsNew ->
            ChangelogEntryDialog(
                entry = decision.entry,
                mode = ChangelogDialogMode.WHATS_NEW,
                onDismiss = {
                    store.markSeen(decision.entry.versionCode)
                    active = ChangelogGate.Decision.None
                }
            )

        ChangelogGate.Decision.None -> Unit
    }
}

/**
 * 单版内容弹窗（包内私用 —— 模式枚举为 file-private，不外露）。
 *
 * v2.2 两级「下一步」交互（用户明确要求的产品语义）：
 * - 第 1 步 = 一级日志（管理软件发布器发送的手动日志要点）—— 确认看到
 *   自己发的更新日志后，点「下一步 · 功能变更」进入；
 * - 第 2 步 = 二级日志（PR 合并内容：新增功能 / 修复分组）；
 * - 两级独立展示、互不覆盖：无手动日志直进第 2 步，无 PR 内容单步收尾
 *   （不写手动日志时 PR 部分照常展示）；两级全空降级 markdown 原文。
 *
 * 强制语义不变：非浏览模式下点外部/返回键不关闭，但「知道了」恒可达
 * —— 绝不锁死用户。滚动区封顶 380dp；步骤切换带方向性滑动动画。
 */
@Composable
private fun ChangelogEntryDialog(
    entry: ChangelogEntry,
    mode: ChangelogDialogMode,
    onDismiss: () -> Unit,
    onOpenUrl: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    val noBrowserHint = stringResource(R.string.settings_about_no_browser)
    // 一级日志存在性：要点或配图任一即算（纯图片段 —— 发布器只发了截图没写
    // 要点 —— 同样进第 1 步，图廊在弹窗置顶展示）
    val hasManual = entry.notes.isNotEmpty() || entry.images.isNotEmpty()
    val hasPr = entry.pr.isNotEmpty()
    val twoStep = hasManual && hasPr
    // 步骤：0 = 手动日志（一级），1 = PR 功能变更（二级）；
    // 无手动日志直进第 2 步（不写手动日志时 PR 部分照常展示）
    var step by remember(entry) { mutableStateOf(if (hasManual) 0 else 1) }

    // 配图全屏查看（复用 ImageLightbox：缩放 / 平移 / 双击缩放 / 点空白关闭）
    var viewingImage by remember(entry) { mutableStateOf<String?>(null) }
    viewingImage?.let { url ->
        ImageLightbox(imageModel = url, onDismiss = { viewingImage = null })
    }

    // 浏览模式可自由关闭；两档启动模式强制（点外部/返回键不关，「知道了」恒可达）
    val dismissOnOutside = mode == ChangelogDialogMode.BROWSE

    AlertDialog(
        onDismissRequest = { if (dismissOnOutside) onDismiss() },
        title = {
            Text(
                when (mode) {
                    ChangelogDialogMode.WHATS_NEW ->
                        entry.title ?: stringResource(R.string.changelog_dialog_whats_new)
                    ChangelogDialogMode.NEW_VERSION ->
                        stringResource(R.string.changelog_dialog_new_version)
                    ChangelogDialogMode.BROWSE ->
                        entry.title ?: "v${entry.versionName}"
                },
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column(modifier = Modifier.heightIn(max = 380.dp)) {
                if (twoStep) {
                    // 两级俱在才显示步骤指示 —— 单级展示不增噪音
                    ChangelogStepIndicator(step = step)
                    Spacer(Modifier.size(12.dp))
                }
                AnimatedContent(
                    targetState = step,
                    transitionSpec = {
                        val dir = if (targetState > initialState) 1 else -1
                        (slideInHorizontally { it / 4 * dir } + fadeIn()) togetherWith
                            (slideOutHorizontally { -it / 4 * dir } + fadeOut())
                    },
                    label = "changelogStep"
                ) { currentStep ->
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState())
                    ) {
                        if (currentStep == 0 && hasManual) {
                            if (mode == ChangelogDialogMode.NEW_VERSION) {
                                Text(
                                    "v${entry.versionName}",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            entry.date?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                            if (mode == ChangelogDialogMode.NEW_VERSION || entry.date != null) {
                                Spacer(Modifier.size(8.dp))
                            }
                            // ── 第 1 步：一级日志（发布器手动日志）要点 ──
                            entry.notes.forEach { note ->
                                Row(modifier = Modifier.padding(vertical = 3.dp)) {
                                    Text(
                                        "•",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        note,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                            // ── 配图图廊（changelog v3：发布器附图）──
                            if (entry.images.isNotEmpty()) {
                                if (entry.notes.isNotEmpty()) {
                                    Spacer(Modifier.size(10.dp))
                                }
                                ChangelogImagesRow(
                                    images = entry.images,
                                    onOpen = { viewingImage = it }
                                )
                            }
                        } else {
                            // ── 第 2 步：二级日志（PR 合并内容分组）──
                            if (hasPr) {
                                Text(
                                    stringResource(R.string.changelog_pr_section),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.size(6.dp))
                                entry.pr.forEachIndexed { index, group ->
                                    if (group.title.isNotBlank()) {
                                        Text(
                                            group.title,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.padding(
                                                top = if (index == 0) 0.dp else 10.dp
                                            )
                                        )
                                    }
                                    group.notes.forEach { note ->
                                        Row(
                                            modifier = Modifier.padding(
                                                start = 8.dp,
                                                top = 3.dp,
                                                bottom = 3.dp
                                            )
                                        ) {
                                            Text(
                                                "·",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.outline
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                note,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            } else {
                                // 两级全空：markdown 原文降级纯文本（不引渲染器）
                                if (mode == ChangelogDialogMode.NEW_VERSION) {
                                    Text(
                                        "v${entry.versionName}",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(Modifier.size(8.dp))
                                }
                                Text(
                                    entry.markdown.orEmpty()
                                        .ifBlank { stringResource(R.string.changelog_history_empty) },
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (step == 0 && hasPr) {
                // 第 1 步且还有第 2 步：下一步进入 PR 功能变更
                Button(onClick = { step = 1 }) {
                    Text(stringResource(R.string.changelog_step_next))
                }
            } else {
                when (mode) {
                    ChangelogDialogMode.WHATS_NEW -> TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.changelog_dialog_got_it))
                    }

                    ChangelogDialogMode.NEW_VERSION -> Button(
                        onClick = {
                            openInBrowser(
                                context,
                                entry.releaseUrl ?: RELEASES_FALLBACK_URL,
                                noBrowserHint
                            )
                            onDismiss()
                        }
                    ) {
                        Text(stringResource(R.string.changelog_dialog_download))
                    }

                    ChangelogDialogMode.BROWSE -> TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.changelog_dialog_browse_close))
                    }
                }
            }
        },
        dismissButton = {
            when {
                // 第 1 步：恒可达的提前出口（不强制看完第 2 步）
                step == 0 -> TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.changelog_dialog_got_it))
                }

                // 第 2 步且有两级：返回第 1 步
                twoStep -> TextButton(onClick = { step = 0 }) {
                    Text(stringResource(R.string.changelog_step_prev))
                }

                // 单级直入第 2 步（无手动日志）：模式语义保持
                else -> when (mode) {
                    ChangelogDialogMode.NEW_VERSION -> TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.changelog_dialog_got_it))
                    }

                    ChangelogDialogMode.BROWSE ->
                        entry.releaseUrl?.let { url ->
                            TextButton(
                                onClick = {
                                    onOpenUrl?.invoke(url)
                                        ?: openInBrowser(context, url, noBrowserHint)
                                }
                            ) {
                                Text(stringResource(R.string.changelog_dialog_open_release))
                            }
                        }

                    ChangelogDialogMode.WHATS_NEW -> Unit
                }
            }
        }
    )
}

/**
 * 配图图廊（changelog v3）—— 一级日志要点下方的横向缩略图行。
 *
 * - Coil [AsyncImage] 直加载 raw CDN URL，占位以 surfaceVariant 底色呈现；
 * - 点击缩略图 → [ImageLightbox] 全屏（复用聊天多模态的成熟查看器：
 *   双指缩放 / 单指平移 / 双击 2.5x / 点空白关闭）；
 * - 封顶 9 张（聚合端 MAX_IMAGES 同尺）—— 横向滚动即可，不做分页。
 */
@Composable
private fun ChangelogImagesRow(
    images: List<String>,
    onOpen: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        images.forEach { url ->
            AsyncImage(
                model = url,
                contentDescription = stringResource(R.string.changelog_image_desc),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(width = 128.dp, height = 96.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onOpen(url) }
            )
        }
    }
}

/**
 * 两级步骤指示器：「① 更新日志 —— ② 功能变更」；当前步高亮主色，
 * 连接线随进度点亮。仅两级俱在时展示。
 */
@Composable
private fun ChangelogStepIndicator(step: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StepChip(
            active = step == 0,
            text = stringResource(R.string.changelog_step_manual)
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp)
                .height(2.dp)
                .background(
                    if (step >= 1) MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                    else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                    RoundedCornerShape(1.dp)
                )
        )
        StepChip(
            active = step == 1,
            text = stringResource(R.string.changelog_step_pr)
        )
    }
}

@Composable
private fun StepChip(active: Boolean, text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = if (active) FontWeight.SemiBold else null,
        color = if (active) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.outline,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            )
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

/**
 * 历史回看（关于页入口）—— 远端清单优先，离线回退 APK 内置副本；两级结构：
 * 版本列表（v + 日期 + 箭头）→ 点开单版详情（[ChangelogEntryDialog] 浏览模式）。
 */
@Composable
fun ChangelogHistoryDialog(
    onDismiss: () -> Unit,
    onOpenUrl: (String) -> Unit
) {
    val context = LocalContext.current
    val repo = remember { ChangelogRepository(context.applicationContext) }
    var manifest by remember { mutableStateOf<ChangelogManifest?>(null) }
    var failed by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<ChangelogEntry?>(null) }

    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) {
            repo.fetchRemote() ?: repo.loadEmbedded()
        }
        if (loaded == null) failed = true else manifest = loaded
    }

    selected?.let { entry ->
        ChangelogEntryDialog(
            entry = entry,
            mode = ChangelogDialogMode.BROWSE,
            onDismiss = { selected = null },
            onOpenUrl = onOpenUrl
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.about_section_changelog),
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            when {
                manifest == null && !failed -> Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                }

                failed -> Text(
                    stringResource(R.string.changelog_history_failed),
                    style = MaterialTheme.typography.bodyMedium
                )

                else -> {
                    val entries = manifest?.history
                        ?.ifEmpty { listOfNotNull(manifest?.latest) }
                        .orEmpty()
                    if (entries.isEmpty()) {
                        Text(
                            stringResource(R.string.changelog_history_empty),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } else {
                        Column(
                            modifier = Modifier
                                .heightIn(max = 420.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            entries.forEach { e ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { selected = e }
                                        .padding(vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(
                                            "v${e.versionName}",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        e.date?.let {
                                            Text(
                                                it,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.outline
                                            )
                                        }
                                    }
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowForward,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.outline
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.changelog_dialog_browse_close))
            }
        }
    )
}

/** ACTION_VIEW 统一出口（runCatching 兜底无浏览器环境，与 about 页同款语义）。 */
private fun openInBrowser(context: Context, url: String, noBrowserHint: String) {
    // #565：scheme 白名单 —— 链接源是 changelog.json 的外部内容（发布器/市场），
    // 拒绝非 http(s) scheme 防止 intent 重定向打开非预期组件。
    val scheme = Uri.parse(url).scheme?.lowercase()
    if (scheme != "http" && scheme != "https") return
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }.onFailure {
        Toast.makeText(context, noBrowserHint, Toast.LENGTH_SHORT).show()
    }
}
