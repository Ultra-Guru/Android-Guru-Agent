package com.apex.agent.ui.screen.about

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.apex.agent.R
import com.apex.agent.update.PatchUpdateEngine
import com.apex.agent.update.UpdateDownloader
import com.apex.agent.update.UpdateManifest
import java.io.File

/**
 * ═══════════════════════════════════════════════════════════════
 *  关于页「软件更新」对话框层（v1.4.5 从 AboutUpdatePanel 拆出）
 * ═══════════════════════════════════════════════════════════════
 *
 * SRP 拆分（1200 行预算合规）：本文件只放「一次性事件对话框」—— 全量包
 * 下载完成三态（就绪安装 / 安装器失败 / 校验失败）+ 增量流水线三态
 *（就绪安装 / 已拉起 / 失败双路）。状态与编排在 [AboutUpdatePanel]，
 * 小控件在 [AboutUpdateWidgets]。
 */

/** 下载完成后的一次性事件（全量包路径；增量路径走 PatchUpdateEngine.State）。 */
internal sealed interface DownloadFinished {

    /** 全量 APK 下载完成且校验通过 —— 弹「立即安装」确认框。 */
    data class ApkReady(val file: File) : DownloadFinished

    /** 安装器未能拉起（罕见环境）—— 展示路径让用户手动装。 */
    data class InstallFailed(val file: File) : DownloadFinished

    /** SHA-256 校验失败 —— 文件不可用。 */
    data class VerifyFailed(val fileName: String) : DownloadFinished
}

/**
 * 全量包下载事件对话框 —— 事件驱动，一次性消费（onDismiss/onLater/onInstall
 * 回调把结果交还面板状态机）。
 *
 * @param event 当前事件（null 时什么都不渲染）
 * @param manifest 当前命中的更新清单（版本号文案；过期时退化为通用语）
 * @param downloader 安装器封装（ApkReady 确认键拉起）
 * @param onLater 「稍后安装」/点外部关闭 —— 登记重入口（file 交还面板）
 * @param onDismiss 纯关闭（VerifyFailed）
 * @param onInstallLaunched 安装器拉起成功（面板清事件 + Toast 在本层处理）
 * @param onInstallFailed 安装器拉起失败（面板转 InstallFailed 事件）
 */
@Composable
internal fun FinishedDownloadDialogs(
    event: DownloadFinished?,
    manifest: UpdateManifest?,
    downloader: UpdateDownloader,
    onLater: (File) -> Unit,
    onDismiss: () -> Unit,
    onInstallLaunched: (File) -> Unit,
    onInstallFailed: (File) -> Unit
) {
    when (event) {
        is DownloadFinished.ApkReady -> {
            // 版本号来自当前清单（下载中重查过/清单过期时退化为通用文案）
            val launchedHint = stringResource(R.string.about_update_installer_launched_toast)
            val context = LocalContext.current
            AlertDialog(
                onDismissRequest = {
                    // 点外部关闭与「稍后」同义：登记重入口（动作区一键回弹）
                    onLater(event.file)
                },
                title = { Text(stringResource(R.string.settings_about_update_apk_done_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(
                                R.string.settings_about_update_apk_done_body,
                                manifest?.versionName ?: "",
                                formatMb(event.file.length())
                            ),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            stringResource(
                                R.string.settings_about_update_patch_done_saved,
                                event.file.absolutePath
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (downloader.installApk(event.file)) {
                            Toast.makeText(context, launchedHint, Toast.LENGTH_SHORT).show()
                            onInstallLaunched(event.file)
                        } else {
                            onInstallFailed(event.file)
                        }
                    }) {
                        Text(stringResource(R.string.about_update_install_now))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { onLater(event.file) }) {
                        Text(stringResource(R.string.about_update_install_later))
                    }
                }
            )
        }

        is DownloadFinished.InstallFailed -> AlertDialog(
            onDismissRequest = { onLater(event.file) },
            title = { Text(stringResource(R.string.settings_about_update_install_failed_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.settings_about_update_install_failed,
                        event.file.absolutePath
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            },
            confirmButton = {
                TextButton(onClick = { onLater(event.file) }) {
                    Text(stringResource(R.string.settings_about_update_close))
                }
            }
        )

        is DownloadFinished.VerifyFailed -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.settings_about_update_verify_failed_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.settings_about_update_verify_failed_body, event.fileName
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            },
            confirmButton = {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.settings_about_update_close))
                }
            }
        )

        null -> Unit
    }
}

/**
 * 增量流水线对话框：就绪弹安装按钮 / 已拉起安装器 / 失败双路（重试/改全量）。
 *
 * @param flow 流水线状态（Downloading/Applying 静默；三终态弹框）
 * @param manifest 当前命中的更新清单（版本号文案）
 * @param canRetry 失败框「重试增量」可用性（链可解析时 true）
 * @param onDismiss 「稍后安装」/关闭 —— 面板清中枢终态
 * @param onInstall 就绪产物 → 系统安装器
 * @param onRetry 重试增量（沿用同一条链重启流水线）
 * @param onUseFull 改用全量下载
 */
@Composable
internal fun PatchFlowDialogs(
    flow: PatchUpdateEngine.State?,
    manifest: UpdateManifest?,
    canRetry: Boolean,
    onDismiss: () -> Unit,
    onInstall: (File) -> Unit,
    onRetry: () -> Unit,
    onUseFull: () -> Unit
) {
    when (flow) {
        // 合成完成且指纹对账通过 —— 用户主导安装时机（下载完弹出的安装按钮）
        is PatchUpdateEngine.State.Ready -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.about_update_install_ready_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(
                            R.string.about_update_install_ready_body,
                            manifest?.versionName ?: "",
                            formatMb(flow.sizeBytes)
                        ),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        flow.apk.absolutePath,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { onInstall(flow.apk) }) {
                    Text(stringResource(R.string.about_update_install_now))
                }
            },
            dismissButton = {
                // 稍后安装：保留 readyPatch 重入口（动作区一键回弹本框）
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.about_update_install_later))
                }
            }
        )

        is PatchUpdateEngine.State.Installed -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.about_update_patch_done_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.about_update_patch_done_body),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        flow.apk.absolutePath,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.settings_about_update_close))
                }
            }
        )

        is PatchUpdateEngine.State.Failed -> {
            // 失败原因按类别本地化；detail 只进日志/高级线索
            val body = when (flow.kind) {
                PatchUpdateEngine.FailKind.SPACE -> stringResource(
                    R.string.about_update_patch_failed_space,
                    // 预检 detail 形如 "need=… free=…"；UI 侧拿不到体积参数时退化为通用语
                    flow.detail?.substringAfter("need=")?.substringBefore(" ")
                        ?.toLongOrNull()?.let { formatMb(it) } ?: ""
                )
                PatchUpdateEngine.FailKind.DOWNLOAD -> stringResource(
                    R.string.about_update_patch_failed_download
                )
                PatchUpdateEngine.FailKind.VERIFY -> stringResource(
                    R.string.about_update_patch_failed_verify, flow.detail.orEmpty()
                )
                PatchUpdateEngine.FailKind.DECODE -> stringResource(
                    R.string.about_update_patch_failed_decode, flow.detail.orEmpty()
                )
                PatchUpdateEngine.FailKind.INSTALLER -> stringResource(
                    R.string.about_update_patch_failed_installer, flow.detail.orEmpty()
                )
            }
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.about_update_patch_failed_title)) },
                text = {
                    Text(
                        body,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                },
                confirmButton = {
                    // 重试增量：沿用同一条链重启流水线（可用性由面板判定）
                    TextButton(onClick = onRetry, enabled = canRetry) {
                        Text(stringResource(R.string.about_update_patch_retry))
                    }
                },
                dismissButton = {
                    TextButton(onClick = onUseFull) {
                        Text(stringResource(R.string.about_update_patch_use_full))
                    }
                }
            )
        }

        else -> Unit
    }
}
