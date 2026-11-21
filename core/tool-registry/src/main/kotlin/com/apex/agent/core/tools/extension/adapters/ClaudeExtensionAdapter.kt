package com.apex.agent.core.tools.extension.adapters

import com.apex.agent.core.tools.claudeimport.ClaudePluginImporter
import com.apex.agent.core.tools.extension.ExtensionAdapter
import com.apex.agent.core.tools.extension.ExtensionDirLayout
import com.apex.agent.core.tools.extension.ExtensionJson
import com.apex.agent.core.tools.extension.ExtensionManifest
import com.apex.agent.core.tools.extension.ExtensionSpecParser
import com.apex.agent.core.tools.extension.ExtensionTypes
import com.apex.agent.core.tools.extension.InstallOutcome
import com.apex.agent.core.tools.extension.InspectReport
import com.apex.agent.core.tools.extension.RuntimeDomains
import com.apex.agent.core.tools.extension.SourceKinds
import com.apex.agent.core.tools.fusion.FusionBundleRegistry
import com.apex.agent.core.tools.pluginhost.claude.ClaudeCompatWiring
import com.apex.agent.core.tools.skill.SafeZipExtractor
import com.apex.agent.core.tools.skill.SkillRegistry
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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
 *
 * Phase 6 收尾（装完 ≠ 生效 → 安装链挂接）：[compatWiring] 非空时，包内
 * `hooks/hooks.json`（需 [layout] 持久化包根，钩子命令经
 * `${CLAUDE_PLUGIN_ROOT}` 引用包内脚本）、`.mcp.json`（注册进既有
 * McpManager，enabled=false）、`.lsp.json`（只识别）三件在 install 时自动
 * 挂接，卸载时自动回收（详见 [ClaudeCompatWiring]）。
 */
class ClaudeExtensionAdapter(
    private val fusionRegistry: FusionBundleRegistry,
    private val skillRegistry: SkillRegistry,
    /** 字节拉取端口（zipball / raw marketplace.json；app 层 OkHttp + 镜像回退，单测 fake）。 */
    private val fetchBytes: suspend (String) -> Result<ByteArray>,
    /** Claude 兼容层挂接器（hooks/.mcp.json/.lsp.json）；null = 宿主未接线（转译内容照常安装，兼容层如实降级）。 */
    private val compatWiring: ClaudeCompatWiring? = null,
    /** 持久化布局（hooks 包根落 `<extensions>/claude.<id>/claude-pkg/`）；null = 不持久化 → 有钩子时如实标注未挂接。 */
    private val layout: ExtensionDirLayout? = null
) : ExtensionAdapter {

    override val supportedTypes: Set<String> = setOf(ExtensionTypes.CLAUDE_PLUGIN)

    override suspend fun resolve(draft: ExtensionSpecParser.SpecDraft): Result<ExtensionManifest> {
        val payload = draft.payload
        val (name, descriptor) = when {
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
            else -> File(payload).takeIf { it.isDirectory }?.let { dir ->
                // 本地包：以 plugin.json 的 name 为准（与 importer 的聚合包 id
                // 对齐 —— 账本 id replace('.','-') 必须能命中 claude-<slug>）；
                // 读不到时兜底目录名。
                val metaName = runCatching {
                    val pkgRoot = sequenceOf(dir, dir.listFiles()?.firstOrNull { it.isDirectory })
                        .filterNotNull().firstOrNull { File(it, ".claude-plugin/plugin.json").isFile }
                    pkgRoot?.let { root ->
                        val obj = ExtensionJson.json.parseToJsonElement(
                            File(root, ".claude-plugin/plugin.json").readText()
                        ).jsonObject
                        obj["name"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                    }
                }.getOrNull()
                (metaName ?: dir.name) to buildJsonObject {
                    put("kind", "local"); put("path", payload)
                }
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
        val bundle = resolveBundle(manifest).imported
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
                // 兼容层三件如实预告（Phase 6：装完自动挂接）
                if (bundle.mcpJson != null) {
                    add("检测到 .mcp.json —— 安装后注册其 MCP 服务器（默认停用，McpConnect/设置开关启用后工具生效）")
                }
                if (bundle.hooksJson != null) {
                    add("检测到 hooks/hooks.json —— 安装后自动挂接钩子事件（社区 shell 命令将在沙箱内执行，请确认可信）")
                }
                if (bundle.lspServerNames.isNotEmpty()) {
                    add("检测到 .lsp.json（${bundle.lspServerNames.size} 台语言服务器声明）—— 宿主 LSP 运行时尚未就绪，只识别不启动")
                }
            }
        )
    }

    override suspend fun install(manifest: ExtensionManifest): Result<InstallOutcome> = runCatching {
        // 钩子需要常驻包根（${CLAUDE_PLUGIN_ROOT} 引用包内脚本，temp 解包
        // 目录会被清理）——兼容层接线齐全时才持久化，避免无谓落盘。
        val persistRoot = if (compatWiring != null && layout != null) {
            layout.extensionDir(manifest.id).resolve(PKG_DIR_NAME)
        } else null
        val resolved = resolveBundle(manifest, persistRoot = persistRoot)
        val bundle = resolved.imported
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
        // Phase 6 收尾：hooks/.mcp.json/.lsp.json 装后自动挂接（失败不炸安装，notes 如实）
        val wiringNotes = compatWiring?.attach(
            pluginId = manifest.id,
            pluginRoot = resolved.persistedRoot,
            hooksJson = bundle.hooksJson,
            mcpJson = bundle.mcpJson,
            lspServerNames = bundle.lspServerNames
        )?.notes ?: emptyList()
        InstallOutcome(
            id = manifest.id,
            type = manifest.type,
            version = fusion.version,
            where = "FusionBundleRegistry: ${fusion.id}",
            notes = buildList {
                if (skillNotes.isNotEmpty()) add("技能已装：${skillNotes.joinToString(", ")}")
                add("聚合中心/王冠菜单可整体激活（与内置七包同权）")
                wiringNotes.forEach(::add)
            }
        )
    }

    override suspend fun uninstall(manifest: ExtensionManifest): Result<Unit> {
        // Phase 6 收尾：先摘兼容层（MCP 归属回收 + 钩子退订 + 持久化包根清除），
        // 再卸主产物——顺序保证钩子命令不再指向已被清除的包根。
        compatWiring?.detach(manifest.id)
        layout?.wipe(manifest.id)
        val bundleId = bundleIdOf(manifest)
        // 先清包内技能（聚合 manifest 反查），再卸聚合包
        val skillIds = fusionRegistry.get(bundleId)?.skills?.map { it.skillId }.orEmpty()
        skillIds.forEach { skillRegistry.uninstall(it) }
        if (!fusionRegistry.uninstall(bundleId)) {
            return Result.failure(IllegalStateException("聚合包 $bundleId 不存在或不可卸载"))
        }
        return Result.success(Unit)
    }

    // ── 来源 → 包根（resolve 阶段只取元数据；install 阶段取实体并导入）──

    /** resolveBundle 产物：转译产物 + 持久化后的包根（仅当 persistRoot 请求且复制成功）。 */
    private data class ResolvedBundle(
        val imported: ClaudePluginImporter.Imported,
        val persistedRoot: File?
    )

    private suspend fun resolveBundle(
        manifest: ExtensionManifest,
        persistRoot: File? = null
    ): ResolvedBundle {
        val kind = manifest.resolved?.get("kind")?.toString()?.trim('"')
            ?: throw IllegalArgumentException("resolved manifest 缺失")
        return when (kind) {
            "local" -> {
                val srcRoot = File(str(manifest, "path"))
                val pkgRoot = ClaudePluginImporter.locatePackageRoot(srcRoot) ?: srcRoot
                ResolvedBundle(
                    imported = ClaudePluginImporter.importFromDir(srcRoot),
                    persistedRoot = persistRoot?.let { copyRoot(pkgRoot, it) }
                )
            }
            "repo" -> importFromZipball(str(manifest, "repo"), subPath = "", persistRoot)
            "marketplace" -> importFromZipball(
                str(manifest, "repo"),
                subPath = strOrNull(manifest, "subPath") ?: "",
                persistRoot = persistRoot
            )
            else -> throw IllegalArgumentException("未知来源 kind：$kind")
        }
    }

    /** 包根 → 持久化目录（复制，不移动源）；失败返回 null（hooks 挂接如实降级，不炸安装）。 */
    private fun copyRoot(pkgRoot: File, target: File): File? = runCatching {
        if (target.exists()) target.deleteRecursively()
        target.mkdirs()
        pkgRoot.copyRecursively(target, overwrite = true)
        target
    }.getOrNull()

    /** zipball 下载 → 防穿越解压 → 容忍 {repo}-{sha}/ 包装层 + 可选仓库内子目录。 */
    private suspend fun importFromZipball(
        ownerRepo: String,
        subPath: String,
        persistRoot: File? = null
    ): ResolvedBundle {
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
            val persisted = persistRoot?.let { target ->
                val pkgRoot = ClaudePluginImporter.locatePackageRoot(root) ?: root
                copyRoot(pkgRoot, target)
            }
            return ResolvedBundle(ClaudePluginImporter.importFromDir(root), persisted)
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

        /** 持久化包根目录名（`<extensions>/claude.<id>/claude-pkg/`）。 */
        internal const val PKG_DIR_NAME = "claude-pkg"
    }
}
