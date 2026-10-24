package com.apex.agent.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.apex.agent.R
import com.apex.agent.core.tools.fusion.FusionBundleComposer
import com.apex.agent.core.tools.fusion.FusionBundleManifest
import com.apex.agent.core.tools.skill.SkillRegistry
import com.apex.agent.core.tools.mcp.McpManager
import com.apex.agent.fusion.FusionBundleController
import com.apex.agent.plugin.api.PluginKind
import com.apex.agent.plugin.host.functional.UnifiedPluginRegistry
import com.apex.agent.ui.icons.CrownGold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * # 自定义聚合包组合器（FusionBundle Composer）
 *
 * 用户自由聚合的编辑面：从**已安装**的技能 / MCP 服务器 / 功能插件中
 * 勾选任意成员（1 个是聚合，多个混搭也是聚合），命名 + 可选 agent 人设
 * 后保存 —— 经 [FusionBundleComposer.build] 拼成 fusion-bundle-v1 manifest
 * 落进 [FusionBundleController.registryForTools]，与内置包同权出现在
 * 王冠菜单（选用 → chip → 强制使用协议全链路复用）。
 *
 * 编辑态（[editing] 非 null）：回填原包成员，保存 = 版本递增覆盖升级；
 * 自建包（bundled=false）额外开放删除 / 导出 JSON（分享给他人直接安装）。
 * 内置包不进编辑态（菜单侧只对自建包提供编辑入口）。
 *
 * 成员来源经 [FusionComposerEntryPoint] 从 Application 取单例（与
 * [rememberFusionBundleController] 同款模式），组合器自身无 DI 依赖。
 */
@Composable
fun FusionBundleComposerDialog(
    controller: FusionBundleController,
    editing: FusionBundleManifest?,
    onDismiss: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val entryPoint = remember(context) {
        dagger.hilt.android.EntryPointAccessors.fromApplication(
            context.applicationContext,
            FusionComposerEntryPoint::class.java
        )
    }
    val feedback = LocalFeedbackController.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    // ── 成员快照（组合期一次拉取；registry 变化由王冠菜单侧的流刷新兜底）──
    val memberSources = remember(entryPoint, editing) {
        val skills = entryPoint.skillRegistry().getInstalled()
            .sortedBy { it.manifest.name.lowercase() }
        val mcps = entryPoint.mcpManager().getConfigs().sortedBy { it.name.lowercase() }
        val plugins = entryPoint.pluginRegistry().catalog.value
            .filter { it.kind == PluginKind.FUNCTIONAL }
            .sortedBy { it.name.lowercase() }
        MemberSources(skills = skills, mcps = mcps, plugins = plugins)
    }

    // ── 编辑态回填 / 新建空态 ──────────────────────────────────────────
    var name by remember(editing) { mutableStateOf(editing?.name.orEmpty()) }
    var persona by remember(editing) {
        mutableStateOf(editing?.agent?.persona.orEmpty())
    }
    var selected by remember(editing) {
        mutableStateOf(
            buildSet {
                editing?.skills?.forEach { add(it.skillId) }
                editing?.mcpServers?.forEach { add(it.serverId) }
                editing?.plugins?.forEach { add(it.pluginId) }
            }
        )
    }

    // 异步回调里不可调 stringResource —— 模板在组合期预解析（仓库惯例）
    val savedPattern = stringResource(R.string.fusion_composer_saved)
    val saveFailedPattern = stringResource(R.string.fusion_composer_save_failed)
    val emptySelectionText = stringResource(R.string.fusion_composer_empty_selection)
    val deletedPattern = stringResource(R.string.fusion_composer_deleted)
    val exportedText = stringResource(R.string.fusion_composer_exported)
    val idPattern = stringResource(R.string.fusion_composer_id_label)

    fun save() {
        if (selected.isEmpty()) {
            feedback.show(emptySelectionText, FeedbackSeverity.WARNING)
            return
        }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                val pickedSkills = memberSources.skills
                    .filter { it.manifest.id in selected }
                    .map { it.manifest }
                val pickedMcps = memberSources.mcps.filter { it.name in selected }
                val pickedPlugins = memberSources.plugins
                    .filter { it.id in selected }
                    .mapNotNull { entry ->
                        entryPoint.pluginRegistry()
                            .rawFunctionalManifestJson(entry.id)
                            ?.let { raw -> FusionBundleComposer.parsePluginRaw(raw) }
                            ?.let { json -> entry.id to json }
                    }
                // 新建：名称 slug 化；撞已有自建包 id 时追加时间戳后缀防覆盖。
                // 编辑：沿用原 id + 版本递增（注册表按 version 判升级）。
                val registry = controller.registryForTools
                val baseId = editing?.id ?: FusionBundleComposer.slugify(name)
                val bundleId = if (editing != null) baseId else {
                    if (registry.get(baseId) == null) baseId
                    else baseId + "-" + java.lang.Long.toString(System.currentTimeMillis(), 36)
                }
                val version = editing?.let { FusionBundleComposer.nextVersion(it.version) } ?: "1"
                val manifest = FusionBundleComposer.build(
                    bundleId = bundleId,
                    name = name,
                    persona = persona,
                    skills = pickedSkills,
                    mcpServers = pickedMcps,
                    plugins = pickedPlugins,
                    version = version
                )
                registry.install(FusionBundleComposer.toJson(manifest))
            }
            result.fold(
                onSuccess = {
                    feedback.show(String.format(savedPattern, it.name), FeedbackSeverity.INFO)
                    onDismiss()
                },
                onFailure = {
                    feedback.show(String.format(saveFailedPattern, it.message ?: ""), FeedbackSeverity.WARNING)
                }
            )
        }
    }

    fun delete() {
        val target = editing ?: return
        scope.launch {
            val ok = withContext(Dispatchers.IO) { controller.registryForTools.uninstall(target.id) }
            if (ok) {
                feedback.show(String.format(deletedPattern, target.name), FeedbackSeverity.INFO)
                onDismiss()
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                // ── 标题行 ──
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = com.apex.agent.ui.icons.FusionCrown,
                        contentDescription = null,
                        tint = CrownGold,
                        modifier = Modifier.size(22.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(
                                if (editing != null) R.string.fusion_composer_edit_title
                                else R.string.fusion_composer_title
                            ),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        if (editing != null) {
                            Text(
                                text = String.format(idPattern, editing.id),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.fusion_composer_close))
                    }
                }
                Text(
                    text = stringResource(R.string.fusion_composer_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp, bottom = 10.dp)
                )

                // ── 名称 / 人设 ──
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.fusion_composer_name_label)) },
                    placeholder = { Text(stringResource(R.string.fusion_composer_name_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = persona,
                    onValueChange = { persona = it },
                    label = { Text(stringResource(R.string.fusion_composer_persona_label)) },
                    placeholder = { Text(stringResource(R.string.fusion_composer_persona_placeholder)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                )

                // ── 成员三段 ──
                ComposerSection(
                    title = stringResource(R.string.fusion_composer_section_skills),
                    entries = memberSources.skills.map { it.manifest.id to it.manifest.name },
                    selected = selected,
                    onToggle = { id ->
                        selected = if (id in selected) selected - id else selected + id
                    }
                )
                ComposerSection(
                    title = stringResource(R.string.fusion_composer_section_mcp),
                    entries = memberSources.mcps.map { it.name to it.name },
                    selected = selected,
                    onToggle = { id ->
                        selected = if (id in selected) selected - id else selected + id
                    }
                )
                ComposerSection(
                    title = stringResource(R.string.fusion_composer_section_plugins),
                    entries = memberSources.plugins.map { it.id to it.name },
                    selected = selected,
                    onToggle = { id ->
                        selected = if (id in selected) selected - id else selected + id
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

                // ── 底部操作行 ──
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = String.format(
                            stringResource(R.string.fusion_composer_selected_count),
                            selected.size
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    if (editing != null && !editing.bundled) {
                        OutlinedButton(onClick = {
                            clipboard.setText(
                                AnnotatedString(
                                    FusionBundleComposer.toJson(editing)
                                )
                            )
                            feedback.show(exportedText, FeedbackSeverity.INFO)
                        }) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.fusion_composer_export))
                        }
                        OutlinedButton(onClick = ::delete) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    Button(onClick = ::save) {
                        Icon(
                            imageVector = if (editing != null) Icons.Default.Save else Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.fusion_composer_save))
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

/** 一段成员勾选区（标题 + 行列表；行 = 复选框 + 成员名）。 */
@Composable
private fun ComposerSection(
    title: String,
    entries: List<Pair<String, String>>,
    selected: Set<String>,
    onToggle: (String) -> Unit
) {
    Column(modifier = Modifier.padding(top = 14.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        if (entries.isEmpty()) {
            Text(
                text = stringResource(R.string.fusion_composer_section_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 6.dp)
            )
        } else {
            entries.forEach { (id, label) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 0.dp)
                ) {
                    Checkbox(
                        checked = id in selected,
                        onCheckedChange = { onToggle(id) }
                    )
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp)
                    )
                }
            }
        }
    }
}

/** 成员快照（组合期一次拉取的三源数据）。 */
private data class MemberSources(
    val skills: List<com.apex.agent.core.tools.skill.SkillRegistry.InstalledSkill>,
    val mcps: List<com.apex.agent.core.tools.mcp.McpServerConfig>,
    val plugins: List<com.apex.agent.plugin.host.functional.UnifiedPluginEntry>
)

/** EntryPoint：组合器的成员数据源（全部 @Singleton，无 DI 环风险）。 */
@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface FusionComposerEntryPoint {
    fun skillRegistry(): SkillRegistry

    fun mcpManager(): McpManager

    fun pluginRegistry(): UnifiedPluginRegistry
}
