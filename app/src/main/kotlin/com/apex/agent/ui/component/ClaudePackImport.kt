package com.apex.agent.ui.component

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.apex.agent.R
import com.apex.agent.core.tools.claudeimport.ClaudePluginImporter
import com.apex.agent.core.tools.skill.SafeZipExtractor
import com.apex.agent.fusion.FusionBundleController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * # Claude 插件包导入 launcher（共享实现）
 *
 * SAF 选 .zip → `SafeZipExtractor`（防穿越 / zip bomb）→
 * [ClaudePluginImporter]（.claude-plugin 三段映射）→ 聚合注册表落盘。
 * 王冠输入栏入口（FusionBundleButton）与工具聚合中心（AggregationCenterScreen）
 * 共用同一实现 —— 一处修复两处生效。
 *
 * @return SAF launcher（调用方以 `launch(arrayOf("application/zip", ...))` 触发）
 */
@Composable
fun rememberClaudeImportLauncher(controller: FusionBundleController): androidx.activity.result.ActivityResultLauncher<Array<String>> {
    val context = LocalContext.current
    val feedback = LocalFeedbackController.current
    val scope = rememberCoroutineScope()
    val donePattern = stringResource(R.string.fusion_import_claude_done)
    val failedPattern = stringResource(R.string.fusion_import_claude_failed)

    return rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val result = runCatching {
                val cacheDir = File(context.cacheDir, "claude-import")
                    .apply { deleteRecursively(); mkdirs() }
                val zipFile = File(cacheDir, "pack.zip")
                context.contentResolver.openInputStream(uri)!!.use { input ->
                    zipFile.outputStream().use { output -> input.copyTo(output) }
                }
                val extractDir = File(cacheDir, "unzipped")
                SafeZipExtractor.extract(zipFile, extractDir)
                ClaudePluginImporter.importFromDir(extractDir)
            }
            result.fold(
                onSuccess = { imported ->
                    // ── 带资源技能先行安装（installFromZip：manifest + scripts/
                    // references 落盘 skillsDir/<id>/）——聚合激活时 isInstalled
                    // 幂等跳过，资源已就位；失败不弃导入（技能 manifest 仍随
                    // 聚合包内联，激活时走纯 MD 安装，资源缺失如实降级）。
                    var resourceFailures = 0
                    val skillRegistry = entryPoint(context).skillRegistry()
                    val importCacheDir = File(context.cacheDir, "claude-import")
                    imported.skillZips.forEach { (skillId, bytes) ->
                        val tmp = File(importCacheDir, "skill-$skillId.zip")
                        runCatching {
                            tmp.writeBytes(bytes)
                            skillRegistry.installFromZip(tmp)
                                .onFailure { resourceFailures++ }
                        }.onFailure { resourceFailures++ }
                        tmp.delete()
                    }
                    controller.registryForTools.install(imported.manifestJson).fold(
                        onSuccess = {
                            val resourceNote =
                                if (resourceFailures == 0 && imported.skillZips.isNotEmpty())
                                    "（含 ${imported.skillZips.size} 个脚本资源技能）" else ""
                            feedback.show(
                                String.format(
                                    // #454：显式 Locale —— 防区域化数字漂移。
                                    java.util.Locale.US, donePattern, it.name,
                                    it.skills.size,
                                    it.plugins.firstOrNull()
                                        ?.let { p -> countSlashCommands(p) } ?: 0,
                                    if (it.agent.persona.isBlank()) 0 else 1
                                ) + resourceNote,
                                FeedbackSeverity.INFO
                            )
                        },
                        onFailure = {
                            feedback.show(
                                String.format(failedPattern, it.message ?: ""),
                                FeedbackSeverity.WARNING
                            )
                        }
                    )
                },
                onFailure = {
                    feedback.show(
                        String.format(failedPattern, it.message ?: ""),
                        FeedbackSeverity.WARNING
                    )
                }
            )
            // 导入缓存清理（注册表落盘的 bundle json 已独立保存，临时物即用即弃）
            File(context.cacheDir, "claude-import").deleteRecursively()
        }
    }
}

/** EntryPoint：Claude 导入的资源安装通道（SkillRegistry.installFromZip）。 */
@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface ClaudeImportEntryPoint {
    fun skillRegistry(): com.apex.agent.core.tools.skill.SkillRegistry
}

/** 组合期取 SkillRegistry 单例（随 Application 上下文记忆化）。 */
private fun entryPoint(context: android.content.Context): ClaudeImportEntryPoint =
    dagger.hilt.android.EntryPointAccessors.fromApplication(
        context.applicationContext,
        ClaudeImportEntryPoint::class.java
    )

/** 统计聚合内联插件（commands 载体）贡献的斜杠指令数（导入反馈文案用）。 */
private fun countSlashCommands(
    ref: com.apex.agent.core.tools.fusion.FusionPluginRef
): Int = runCatching {
    (ref.manifest["contributions"] as? kotlinx.serialization.json.JsonObject)
        ?.get("slashCommands") as? kotlinx.serialization.json.JsonArray
}.getOrNull()?.size ?: 0
