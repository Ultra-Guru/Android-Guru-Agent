package com.apex.agent.core.tools.extension.adapters

import com.apex.agent.core.tools.claudeimport.ClaudePluginImporter
import com.apex.agent.core.tools.extension.ExtensionAdapter
import com.apex.agent.core.tools.extension.ExtensionJson
import com.apex.agent.core.tools.extension.ExtensionManifest
import com.apex.agent.core.tools.extension.ExtensionSpecParser
import com.apex.agent.core.tools.extension.ExtensionTypes
import com.apex.agent.core.tools.extension.InstallOutcome
import com.apex.agent.core.tools.extension.InspectReport
import com.apex.agent.core.tools.extension.RuntimeDomains
import com.apex.agent.core.tools.extension.SourceKinds
import com.apex.agent.core.tools.fusion.FusionBundleRegistry
import com.apex.agent.core.tools.skill.SafeZipExtractor
import com.apex.agent.core.tools.skill.SkillRegistry
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * # ClaudeExtensionAdapter —— Claude 生态插件适配器（runtime = android）
 *
 * `claude:<name>@<marketplace>` / `claude:owner/repo` / `claude:<本地包目录>`
 * → **纯 Android 域文件操作**（不依赖 rootfs）：zip 下载（[SafeZipExtractor]
 * 防穿越解压）→ 复用既有 [ClaudePluginImporter]（.claude-plugin →
 * fusion-bundle-v1 全套映射：skills/commands/agents）→
 * [FusionBundleRegistry.install] + 资源技能 `SkillRegistry.installFromZip`
 * —— 与市场 Claude zip 导入完全同一管道，零新概念。
 *
 * marketplace 解析（真读 marketplace.json，插件实体常在别的仓库）：
 * - 内置官方表：`claude-code` / `official` → anthropics/claude-code；
 * - `name@owner/repo` 自定义形态 → 读该仓库 `.claude-plugin/marketplace.json`
 *   的 `plugins[]`，命中条目的 `source` 支持 `{source:"github",repo}` 与
 *   `"./相对路径"`（仓库内子目录）两种形态。
 */
class ClaudeExtensionAdapter(
    private val fusionRegistry: FusionBundleRegistry,
    private val skillRegistry: SkillRegistry,
    /** 字节拉取端口（zipball / raw marketplace.json；app 层 OkHttp + 镜像回退，单测 fake）。 */
    private val fetchBytes: suspend (String) -> Result<ByteArray>
) : ExtensionAdapter {

    override val supportedTypes: Set<String> = setOf(ExtensionTypes.CLAUDE_PLUGIN)

    override suspend fun resolve(draft: ExtensionSpecParser.SpecDraft): Result<ExtensionManifest> {
        val payload = draft.payload
        val (name, sourceKind, descriptor) = when {
            // name@marketplace（PR-A 解析器已保证此形态无路径分隔符与协议）
            '@' in payload && !payload.contains(':') -> {
                val pluginName = payload.substringBefore('@')
                val mp = payload.substringAfter('@')
                val mpRepo = marketplaceRepo(mp)
                    ?: return Result.failure(
                        IllegalArgumentException(
                            "未知 marketplace '$mp'（内置官方：claude-code/official；自定义用 claude:name@owner/repo 形态）"
                        )
                    )
                // 真读 marketplace.json 定位插件条目（实体仓库/子目录）
                val entry = locateMarketplaceEntry(mpRepo, pluginName).getOrElse { return Result.failure(it) }
                pluginName to entry
            }
            ExtensionSpecParser.githubRepoOf(payload) != null ->
                payload.removePrefix("github:") to buildJsonObject {
                    put("kind", "repo"); put("repo", ExtensionSpecParser.githubRepoOf(payload)!!)
                }
            else -> File(payload).takeIf { it.isDirectory }?.let {
                it.name to buildJsonObject { put("kind", "local"); put("path", payload) }
            } ?: return Result.failure(
                IllegalArgumentException("claude spec 无法识别：'$payload'（支持 <name>@<marketplace> / owner/repo / 本地包目录）")
            )
        }
        val manifest = ExtensionManifest(
            id = "claude." + OpenCodeExtensionAdapter.slugOf(name),
            type = ExtensionTypes.CLAUDE_PLUGIN,
            spec = draft.raw,
            name = name,
            version = "0.0.0", // 包内 plugin.json 版本，安装时回填账本
            sourceKind = draft.sourceKind ?: SourceKinds.GITHUB,
            runtime = RuntimeDomains.ANDROID,
            resolved = descriptor,
            summary = "Claude 插件包「$name」→ fusion-bundle（skills/commands/agents 全套映射，市场 zip 导入同管道）"
        )
        return Result.success(manifest.copy(manifestHash = manifest.computeHash()))
    }

    override suspend fun inspect(manifest: ExtensionManifest): Result<InspectReport> = runCatching {
        val bundle = resolveBundle(manifest)
        InspectReport(
            manifest = manifest,
            targetRegistry = "FusionBundleRegistry（聚合包 ${bundleIdOf(manifest)}）+ SkillRegistry（包内技能）",
            targetLocation = "fusion-bundles/${bundleIdOf(manifest)}.json + skills/",
            warnings = buildList {
                bundle.manifest.skills.takeIf { it.isNotEmpty() }?.let {
                    add("将安装 ${it.size} 个技能：${it.joinToString(", ") { s -> s.skillId }}")
                }
                if (bundle.skillZips.isNotEmpty()) add("${bundle.skillZips.size} 个技能带脚本/资源目录（模型经终端执行）")
                if (manifest.trustLevel != "verified") add("社区来源 —— 聚合包正文将注入对话，请确认可信")
            }
        )
    }

    override suspend fun install(manifest: ExtensionManifest): Result<InstallOutcome> = runCatching {
        val bundle = resolveBundle(manifest)
        val fusion = fusionRegistry.install(bundle.manifestJson).getOrElse {
            throw IllegalStateException("聚合包安装失败：${it.message}")
        }
        val skillNotes = bundle.skillZips.mapNotNull { (skillId, bytes) ->
            val zip = File.createTempFile("ext-skill", ".zip").apply {
                writeBytes(bytes); deleteOnExit()
            }
            skillRegistry.installFromZip(zip).fold(
                onSuccess = { "skill:${it.id}" },
                onFailure = { null }
            )
        }
        InstallOutcome(
            id = manifest.id,
            type = manifest.type,
            version = fusion.version,
            where = "FusionBundleRegistry: ${fusion.id}",
            notes = buildList {
                if (skillNotes.isNotEmpty()) add("技能已装：${skillNotes.joinToString(", ")}")
                add("聚合中心/王冠菜单可整体激活（与内置七包同权）")
            }
        )
    }

    override suspend fun uninstall(manifest: ExtensionManifest): Result<Unit> {
        val bundleId = bundleIdOf(manifest)
        // 先清包内技能（聚合 manifest 反查），再卸聚合包
        val skillIds = fusionRegistry.get(bundleId)?.manifest?.skills?.map { it.skillId }.orEmpty()
        skillIds.forEach { skillRegistry.uninstall(it) }
        if (!fusionRegistry.uninstall(bundleId)) {
            return Result.failure(IllegalStateException("聚合包 $bundleId 不存在或不可卸载"))
        }
        return Result.success(Unit)
    }

    // ── 来源 → 包根（resolve 阶段只取元数据；install 阶段取实体并导入）──

    private suspend fun resolveBundle(manifest: ExtensionManifest): ClaudePluginImporter.Imported {
        val kind = manifest.resolved?.get("kind")?.toString()?.trim('"')
            ?: throw IllegalArgumentException("resolved manifest 缺失")
        return when (kind) {
            "local" -> ClaudePluginImporter.importFromDir(File(str(manifest, "path")))
            "repo" -> importFromZipball(str(manifest, "repo"), subPath = "")
            "marketplace" -> importFromZipball(
                str(manifest, "repo"),
                subPath = strOrNull(manifest, "subPath") ?: ""
            )
            else -> throw IllegalArgumentException("未知来源 kind：$kind")
        }
    }

    /** zipball 下载 → 防穿越解压 → 容忍 {repo}-{sha}/ 包装层 + 可选仓库内子目录。 */
    private suspend fun importFromZipball(ownerRepo: String, subPath: String): ClaudePluginImporter.Imported {
        val repo = ExtensionSpecParser.githubRepoOf(ownerRepo)
            ?: throw IllegalArgumentException("GitHub 仓库不合法（白名单外）：'$ownerRepo'")
        val bytes = fetchBytes("https://api.github.com/repos/$repo/zipball").getOrElse {
            throw IllegalStateException("zip 下载失败：${it.message}")
        }
        val zip = File.createTempFile("ext-claude", ".zip").apply {
            writeBytes(bytes); deleteOnExit()
        }
        val dest = kotlin.io.path.createTempDirectory("ext-claude-src").toFile()
        try {
            SafeZipExtractor.extract(zip, dest)
            val firstDir = dest.listFiles()?.firstOrNull { it.isDirectory }
            var root: File = when {
                // 标准包根在第一层（{repo}-{sha}/）
                firstDir != null && File(firstDir, ".claude-plugin").isDirectory -> firstDir
                firstDir != null -> firstDir // importFromDir 自带一层容忍，交给它判定
                else -> dest
            }
            if (subPath.isNotBlank()) {
                val sub = File(root, subPath.removePrefix("./").trim('/'))
                if (!sub.isDirectory) throw IllegalArgumentException("仓库内子目录不存在：$subPath")
                root = sub
            }
            return ClaudePluginImporter.importFromDir(root)
        } finally {
            zip.delete()
            dest.deleteRecursively()
        }
    }

    /**
     * 读 marketplace.json 命中插件条目 → resolved descriptor
     * （{kind:"marketplace", repo:实体仓库, subPath?, pluginName}）。
     */
    private suspend fun locateMarketplaceEntry(
        mpRepo: String,
        pluginName: String
    ): Result<JsonObject> = runCatching {
        val repo = ExtensionSpecParser.githubRepoOf(mpRepo)
            ?: throw IllegalArgumentException("marketplace 仓库不合法：'$mpRepo'")
        val bytes = fetchBytes("https://raw.githubusercontent.com/$repo/main/.claude-plugin/marketplace.json")
            .getOrElse { throw IllegalStateException("marketplace.json 拉取失败：${it.message}") }
        val root = ExtensionJson.json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        val plugins = root["plugins"]?.jsonArray
            ?: throw IllegalArgumentException("marketplace.json 缺少 plugins 数组")
        val entry = plugins.firstOrNull { p ->
            runCatching { p.jsonObject["name"]?.jsonPrimitive?.content == pluginName }.getOrDefault(false)
        }?.jsonObject
            ?: throw IllegalArgumentException("marketplace '$repo' 未收录插件 '$pluginName'（已收录：${plugins.size} 个）")
        val source = entry["source"]
        val sourceObj = runCatching { source?.jsonObject }.getOrNull()
        when {
            // {source:"github", repo:"owner/repo"} —— 实体在别的仓库
            sourceObj?.get("repo") != null -> buildJsonObject {
                put("kind", "marketplace")
                put("repo", sourceObj["repo"]!!.jsonPrimitive.content)
                put("pluginName", pluginName)
            }
            // "./相对路径" —— 仓库内子目录
            source != null && source.toString().contains("./") -> buildJsonObject {
                put("kind", "marketplace")
                put("repo", repo)
                put("subPath", source.jsonPrimitive.content)
                put("pluginName", pluginName)
            }
            // 无 source（marketplace 仓库自身即插件包根）
            else -> buildJsonObject {
                put("kind", "marketplace")
                put("repo", repo)
                put("pluginName", pluginName)
            }
        }
    }

    /** marketplace 名 → 仓库（内置官方表；未知名字必须 name@owner/repo 直给）。 */
    private fun marketplaceRepo(mp: String): String? = when (mp.lowercase()) {
        "claude-code", "official" -> MARKETPLACE_OFFICIAL_REPO
        else -> ExtensionSpecParser.githubRepoOf(mp)
    }

    /** 账本 id `claude.<slug>` → importer 聚合包 id `claude-<slug>`。 */
    private fun bundleIdOf(manifest: ExtensionManifest): String = manifest.id.replace('.', '-')

    private fun str(manifest: ExtensionManifest, key: String): String =
        strOrNull(manifest, key) ?: throw IllegalArgumentException("resolved manifest 缺少 $key")

    private fun strOrNull(manifest: ExtensionManifest, key: String): String? =
        manifest.resolved?.get(key)?.let { v ->
            runCatching { v.jsonPrimitive.content }.getOrNull()
        }

    internal companion object {
        const val MARKETPLACE_OFFICIAL_REPO = "anthropics/claude-code"
    }
}
