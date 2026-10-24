package com.apex.agent.core.tools.claudeimport

import com.apex.agent.core.tools.fusion.FusionBundleAgent
import com.apex.agent.core.tools.fusion.FusionBundleManifest
import com.apex.agent.core.tools.fusion.FusionPluginRef
import com.apex.agent.core.tools.fusion.FusionSkillRef
import com.apex.agent.core.tools.skill.SkillManifest
import com.apex.agent.core.tools.skill.SkillNaming
import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * # Claude 插件包导入器（.claude-plugin → fusion-bundle-v1）
 *
 * Claude 生态插件包的完整解析与形态映射：
 *
 * ```
 * <包根>/
 *   .claude-plugin/plugin.json   → 包元数据（name/version/description）
 *   skills 目录（任意嵌套层下的
 *     SKILL.md 文件）            → apex-skill-v1 技能（frontmatter name 归一
 *                                  PascalCase，正文 → promptInjection）
 *   commands 目录下的 .md 文件   → 功能性插件斜杠指令贡献（经聚合 plugins[]
 *                                  段搭载，激活即装 —— /plugin:<id> 可用）
 *   agents 目录下的 .md 文件     → 聚合 agent 人设（首个为主人设，其余并入
 *                                  激活提示）
 * ```
 *
 * 产物是一个自洽的 fusion-bundle-v1（含 plugins[] 段）：走
 * [com.apex.agent.core.tools.fusion.FusionBundleRegistry.install] 一键落盘，
 * 王冠菜单选用即聚合激活 —— 与内置七大包完全同权，零新概念。
 *
 * 命名映射（宿主命名规范，全部安装期强校验）：
 * - 技能 id：kebab-case → `SkillNaming.canonicalId` PascalCase（如
 *   `pdf-generation` → `PdfGeneration`）；
 * - 斜杠指令 id：Claude 文件名本就是 kebab-case，与宿主小写中划线规范同形
 *   （`/plugin:<id>` 路由直接可用）；
 * - commands 载体插件 id：`claude.<包名slug>.commands`（小写点分，宿主
 *   FUNCTIONAL_ID_PATTERN 合规，且不占 com.apex.agent 保留前缀）；
 * - 聚合包 id：`claude-<包名slug>`（小写中划线，FusionBundle ID_PATTERN 合规）。
 *
 * 安全边界：仅读取包根 canonical path 之内的文件（解压侧另由 SafeZipExtractor
 * 防穿越 / zip bomb，本类不重复设防，只保证自身不逃逸）；frontmatter 解析
 * 只认简单 `key: value` 标量行，复杂 YAML（列表/折叠块）降级为忽略该键。
 */
object ClaudePluginImporter {

    /** 导入产物：聚合 manifest + 其安装用 JSON（commands 已内联进 plugins[]）。 */
    data class Imported(
        val manifest: FusionBundleManifest,
        val manifestJson: String
    )

    /** 解析出的 Claude 文档（SKILL.md / command / agent 共用形态）。 */
    data class ClaudeDoc(
        val frontmatter: Map<String, String>,
        val body: String
    )

    /** 导入失败语义（fail closed：结构不合法整体拒绝，不装半截包）。 */
    class ImportException(message: String) : Exception(message)

    /**
     * 从已解压的包根目录导入。
     *
     * @param root 包根（含 .claude-plugin/ 的目录；也容忍包根的直接子目录
     *   是实际包根 —— GitHub zip 常带一层仓库目录）
     */
    fun importFromDir(root: File): Imported {
        val pkgRoot = locatePackageRoot(root)
            ?: throw ImportException("未找到 .claude-plugin/plugin.json（不是 Claude 插件包结构）")
        val meta = parsePluginMeta(pkgRoot.resolve(".claude-plugin/plugin.json"))
        val slug = slugify(meta.name)

        val skills = pkgRoot.resolve("skills").takeIf { it.isDirectory }
            ?.walkSKillDocs()?.mapNotNull { (dirName, doc) -> doc.toSkillManifest(dirName) }
            ?.sortedBy { it.id }?.toList().orEmpty()
        val commands = pkgRoot.resolve("commands").takeIf { it.isDirectory }
            ?.listFiles { f -> f.isFile && f.extension.equals("md", true) }
            ?.sortedBy { it.name }
            ?.mapNotNull { it.readText().toClaudeDoc()?.let { d -> it.nameWithoutExtension to d } }
            .orEmpty()
        val agents = pkgRoot.resolve("agents").takeIf { it.isDirectory }
            ?.listFiles { f -> f.isFile && f.extension.equals("md", true) }
            ?.sortedBy { it.name }
            ?.mapNotNull { it.readText().toClaudeDoc() }
            .orEmpty()

        if (skills.isEmpty() && commands.isEmpty() && agents.isEmpty()) {
            throw ImportException("包内无 skills/commands/agents 任何可导入内容")
        }

        val manifest = FusionBundleManifest(
            id = "claude-$slug",
            name = meta.name,
            version = meta.version,
            description = meta.description.ifBlank {
                "Claude 插件包导入 · ${skills.size} 技能 · ${commands.size} 指令 · ${agents.size} agent"
            },
            license = meta.license,
            bundled = false,
            scope = "all",
            agent = buildAgent(agents),
            skills = skills.map { FusionSkillRef(skillId = it.id, manifest = it) },
            plugins = if (commands.isEmpty()) emptyList() else listOf(
                FusionPluginRef(
                    pluginId = "claude.$slug.commands",
                    manifest = buildCommandsPlugin(slug, meta, commands)
                )
            )
        )
        val json = FusionBundleManifest.json
            .encodeToString(FusionBundleManifest.serializer(), manifest)
        return Imported(manifest, json)
    }

    // ── 文档解析 ─────────────────────────────────────────────────────

    /**
     * Markdown 文档 → frontmatter + 正文。
     * 认可 `---` 围栏首段为 YAML frontmatter（Claude 生态标准形态）；
     * 无围栏时整文为正文。frontmatter 只解析简单标量行。
     */
    fun String.toClaudeDoc(): ClaudeDoc? {
        val text = this
        if (!text.startsWith("---")) return ClaudeDoc(emptyMap(), text.trim())
        val end = text.indexOf("\n---", startIndex = 3)
        if (end < 0) return null
        val fmRaw = text.substring(3, end).trim('\r', '\n', ' ')
        val body = text.substring(end + 4).trim()
        val fm = fmRaw.lines().mapNotNull { line ->
            val idx = line.indexOf(':')
            if (idx <= 0) return@mapNotNull null
            val key = line.substring(0, idx).trim()
            var value = line.substring(idx + 1).trim()
            // 剥引号（简单标量）；列表/折叠块（"- x" / ">-"）不认，忽略该键
            if (value.length >= 2 && ((value.first() == '"' && value.last() == '"') ||
                    (value.first() == '\'' && value.last() == '\''))
            ) {
                value = value.substring(1, value.length - 1)
            }
            if (key.isNotEmpty() && value.isNotEmpty() && !value.startsWith("-")) {
                key to value
            } else null
        }.toMap()
        return ClaudeDoc(fm, body)
    }

    /** .claude-plugin/plugin.json → 包元数据。 */
    private fun parsePluginMeta(file: File): ClaudePluginMeta {
        if (!file.isFile) throw ImportException("plugin.json 文件缺失")
        val obj = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(file.readText()).jsonObject
        }.getOrNull() ?: throw ImportException("plugin.json 无法解析")
        fun str(key: String): String =
            runCatching { obj[key]?.jsonPrimitive?.content }.getOrDefault("")
        val name = str("name").ifBlank { file.parentFile?.parentFile?.name ?: "claude-plugin" }
        val version = str("version").ifBlank { "1.0.0" }
        // author 可能是 {name: "..."} 对象或字符串
        val author = runCatching {
            (obj["author"] as? JsonObject)?.get("name")?.jsonPrimitive?.content
                ?: str("author")
        }.getOrDefault("")
        val license = runCatching {
            (obj["license"] as? JsonObject)?.get("name")?.jsonPrimitive?.content
                ?: str("license")
        }.getOrDefault("").ifBlank { "SEE REPO" }
        // description 可能是字符串或字符串数组（两种形态都见过）——逐形态尝试
        val description = runCatching {
            obj["description"]?.jsonPrimitive?.content
        }.getOrNull()
            ?: runCatching {
                obj["description"]?.jsonArray?.joinToString(" ") { it.jsonPrimitive.content }
            }.getOrDefault("")
        return ClaudePluginMeta(
            name = name, version = version, description = description,
            author = author, license = license
        )
    }

    /** skills 目录下的 (目录名, SKILL.md 文档) 序列（缺 SKILL.md 的子目录跳过）。 */
    private fun File.walkSKillDocs(): Sequence<Pair<String, ClaudeDoc>> {
        val rootCanonical = canonicalPath + File.separator
        return walkTopDown()
            .filter { it.isFile && it.name.equals("SKILL.md", ignoreCase = true) }
            .onEach {
                // 不逃逸包根（防御：解压侧已防穿越，这里双保险）
                if (!it.canonicalPath.startsWith(rootCanonical)) {
                    throw ImportException("SKILL.md 路径逃逸包根: ${it.path}")
                }
            }
            .mapNotNull { f ->
                val dirName = f.parentFile?.name ?: return@mapNotNull null
                f.readText().toClaudeDoc()?.let { dirName to it }
            }
    }

    /** SKILL.md → apex-skill-v1（kebab id 归一 PascalCase；正文 → promptInjection）。 */
    private fun ClaudeDoc.toSkillManifest(dirName: String): SkillManifest? {
        val rawName = frontmatter["name"].orEmpty().ifBlank { dirName }
        val canonical = SkillNaming.canonicalId(rawName)
        if (!SkillNaming.isSafeId(canonical)) return null // 非法 id（纯符号等）跳过
        val desc = frontmatter["description"].orEmpty().ifBlank { "Claude skill: $rawName" }
        return SkillManifest(
            id = canonical,
            name = rawName.replaceFirstChar { it.uppercaseChar() },
            version = "1.0",
            description = desc,
            promptInjection = body.ifBlank { null },
            scope = "all",
            trustLevel = "community"
        )
    }

    /** agents 文档列表 → 聚合人设（首个为主人设；其余以分节并入激活提示）。 */
    private fun buildAgent(agents: List<ClaudeDoc>): FusionBundleAgent {
        val first = agents.firstOrNull() ?: return FusionBundleAgent()
        val persona = first.frontmatter["description"].orEmpty()
            .ifBlank { "Claude agent（导入）" }
        val rest = agents.drop(1)
        val activationPrompt = buildString {
            append(first.body)
            if (rest.isNotEmpty()) {
                append("\n\n### 附加 agent\n")
                rest.forEachIndexed { i, doc ->
                    append("${i + 1}. ${doc.frontmatter["description"].orEmpty()}\n")
                    append(doc.body.trim().prependIndent("   "))
                    append('\n')
                }
            }
        }.trim()
        return FusionBundleAgent(
            persona = persona,
            activationPrompt = activationPrompt
        )
    }

    /** commands → 功能性插件 manifest（JsonObject，聚合 plugins[] 段载体）。 */
    private fun buildCommandsPlugin(
        slug: String,
        meta: ClaudePluginMeta,
        commands: List<Pair<String, ClaudeDoc>>
    ): JsonObject = buildJsonObject {
        put("id", "claude.$slug.commands")
        put("name", "${meta.name} · 斜杠指令")
        put("version", 1)
        put("description", "Claude 插件包 ${meta.name} 的 commands 段导入（${commands.size} 条）")
        put("author", meta.author)
        put("scope", "all")
        put(
            "contributions",
            buildJsonObject {
                put(
                    "slashCommands",
                    kotlinx.serialization.json.buildJsonArray {
                        commands.forEach { (id, doc) ->
                            add(
                                buildJsonObject {
                                    put("id", id)
                                    put("title", doc.frontmatter["argument-hint"].takeUnless { it.isBlank() }
                                        ?: id)
                                    put("description", doc.frontmatter["description"].orEmpty())
                                    put("prompt", doc.body)
                                }
                            )
                        }
                    }
                )
            }
        )
    }

    /** 包名 → bundle id 尾段（小写字母数字中划线；空则时间戳回退）。 */
    private fun slugify(name: String): String {
        val slug = name.lowercase().map { c ->
            when {
                c.isLetterOrDigit() && c.code < 128 -> c
                c.isWhitespace() || c == '_' || c == '-' -> '-'
                else -> null
            }
        }.filterNotNull().joinToString("").trim('-').take(40)
        return slug.ifEmpty { java.lang.Long.toString(System.currentTimeMillis(), 36) }
    }

    /** 定位真实包根：root 本身含 .claude-plugin，或其某个直接子目录含。 */
    private fun locatePackageRoot(root: File): File? {
        if (root.resolve(".claude-plugin/plugin.json").isFile) return root
        return root.listFiles { f -> f.isDirectory }
            ?.firstOrNull { it.resolve(".claude-plugin/plugin.json").isFile }
    }

    private data class ClaudePluginMeta(
        val name: String,
        val version: String,
        val description: String,
        val author: String,
        val license: String
    )
}
