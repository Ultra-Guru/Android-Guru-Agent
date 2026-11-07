package com.apex.agent.core.tools.extension.adapters

import com.apex.agent.core.tools.extension.ExtensionAdapter
import com.apex.agent.core.tools.extension.ExtensionDirLayout
import com.apex.agent.core.tools.extension.ExtensionJson
import com.apex.agent.core.tools.extension.ExtensionManifest
import com.apex.agent.core.tools.extension.ExtensionSpecParser
import com.apex.agent.core.tools.extension.ExtensionTypes
import com.apex.agent.core.tools.extension.InstallOutcome
import com.apex.agent.core.tools.extension.InspectReport
import com.apex.agent.core.tools.extension.NodeInstallPort
import com.apex.agent.core.tools.extension.RuntimeDomains
import com.apex.agent.core.tools.extension.SourceKinds
import com.apex.agent.core.tools.extension.dsh.DshPluginIntrospector
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

/**
 * # DshExtensionAdapter —— DSH（DeepSeek Harness）插件适配器（runtime = ubuntu）
 *
 * brief 语义：`dsh:<npm 包名>` / `dsh:github:owner/repo` —— `dsh plugin add`
 * 的 profile 安装路径：实体装进沙箱扩展目录，`profile/dsh/profile.json` 写
 * 注册清单（name/version/source/entry/runtime —— ExtensionRun 与 DSH Host 按
 * 此发现插件）。npm 源强制 `dsh-*` 前缀（DSH 插件包名约定，防借 dsh: 装任意包）。
 *
 * **2026-11 Native Plugin Host 升级**：install 后接 [DshPluginIntrospector]
 * （沙箱 shim 动态验证）→ `profile/dsh/capabilities.json` 能力快照 +
 * 真实版本回填账本（InstallOutcome.version + PinnedSource）。introspector
 * 为 null 时保持安装-only 旧语义（如实标注，能力面为空）。
 */
class DshExtensionAdapter(
    port: NodeInstallPort,
    layout: ExtensionDirLayout,
    /** 动态验证编排器（DI 注入；null = 旧安装-only 语义）。 */
    private val introspector: DshPluginIntrospector? = null
) : ExtensionAdapter {

    private val installer = NodeFamilyInstaller(port, layout)
    private val layoutRef = layout
    private val portRef = port

    override val supportedTypes: Set<String> = setOf(ExtensionTypes.DSH_PLUGIN)

    internal companion object {
        /** 运行时标记（ExtensionRun 白名单执行依据：node 入口）。 */
        const val RUNTIME_NODE = "node"
    }

    override suspend fun resolve(draft: ExtensionSpecParser.SpecDraft): Result<ExtensionManifest> {
        val payload = draft.payload
        val (name, sourceKind, descriptor) = when {
            draft.sourceKind == SourceKinds.NPM && ExtensionSpecParser.isNpmName(payload) -> {
                if (!payload.startsWith("dsh-")) {
                    return Result.failure(
                        IllegalArgumentException("DSH 插件的 npm 包名必须以 dsh- 开头（收到 '$payload'）；其它 npm 包请走 opencode: 前缀")
                    )
                }
                Triple(payload, SourceKinds.NPM, buildJsonObject {
                    put("kind", "npm"); put("pkg", payload)
                })
            }
            ExtensionSpecParser.githubRepoOf(payload) != null ->
                Triple(payload.removePrefix("github:"), SourceKinds.GITHUB, buildJsonObject {
                    put("kind", "github"); put("repo", ExtensionSpecParser.githubRepoOf(payload))
                })
            else -> return Result.failure(
                IllegalArgumentException("dsh spec 无法识别：'$payload'（支持 dsh-* npm 包名 / github:owner/repo）")
            )
        }
        val manifest = ExtensionManifest(
            id = "dsh.${OpenCodeExtensionAdapter.slugOf(name)}",
            type = ExtensionTypes.DSH_PLUGIN,
            spec = draft.raw,
            name = name,
            version = "0.0.0",
            sourceKind = sourceKind,
            runtime = RuntimeDomains.UBUNTU,
            resolved = descriptor,
            summary = "DeepSeek Harness 插件「$name」（dsh plugin add 语义 → PRoot Ubuntu 沙箱；安装后动态验证能力面）"
        )
        return Result.success(manifest.copy(manifestHash = manifest.computeHash()))
    }

    override suspend fun inspect(manifest: ExtensionManifest): Result<InspectReport> {
        val probe = portRef.probeRuntime()
        return Result.success(
            InspectReport(
                manifest = manifest,
                targetRegistry = "沙箱扩展目录 + DSH profile（linux/extensions/${manifest.id}/{pkg,profile/dsh/profile.json}）",
                targetLocation = "${manifest.id}/pkg",
                domainDependencies = listOf(
                    "PRoot Ubuntu rootfs 就绪",
                    if (probe.ready) "node ${probe.nodeVersion} / npm ${probe.npmVersion}"
                    else "⚠️ node/npm 在沙箱内不可解析（先 TerminalLinuxBootstrap/TerminalLinuxPackages 装齐）"
                ),
                estimatedSize = layoutRef.sizeSummary(manifest.id),
                warnings = listOf(
                    "执行桥 experimental：工具经宿主桥在沙箱 node 冷执行（P0 模式）；",
                    "依赖完整 Cordis 服务（agents/sessions/slots）的能力不可用，以安装后能力面为准"
                )
            )
        )
    }

    override suspend fun install(manifest: ExtensionManifest): Result<InstallOutcome> {
        val kind = manifest.resolved?.get("kind")?.toString()?.trim('"')
        val (published, pinned) = when (kind) {
            "npm" -> installer.npmInstallPinned(manifest.id, str(manifest, "pkg"))
            "github" -> installer.gitClonePinned(manifest.id, str(manifest, "repo"))
            else -> return Result.failure(IllegalArgumentException("未知来源 kind：$kind"))
        }.getOrElse { return Result.failure(it) }

        val entry = entryHint(published)

        // dsh plugin add 语义：profile 注册清单（ExtensionRun / DSH Host 发现入口）
        val profileFile = layoutRef.profileFile(manifest.id, "dsh/profile.json").getOrElse { return Result.failure(it) }
        ExtensionJson.json.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(),
            buildJsonObject {
                put("schema", "dsh-profile-v1")
                put("id", manifest.id)
                put("name", manifest.name)
                put("spec", manifest.spec)
                put("kind", kind ?: "unknown")
                put("runtime", RUNTIME_NODE)
                put("version", pinned.version ?: manifest.version)
                put("pinnedSource", pinned.toDescriptor())
                put("pkgPath", published.absolutePath)
                put("entryHint", entry)
                put("registeredAt", System.currentTimeMillis())
                put("experimental", true)
            }
        ).let { profileFile.writeText(it) }

        // ── 动态验证（Native Plugin Host）：capabilities 快照 + 真实版本回填 ──
        //（注意 ?. 只保第一跳 —— getOrNull 也须安全调用，否则空 introspector 直接 NPE）
        val capabilities = introspector?.introspectAndStore(manifest.id, pinned.toDescriptor())?.getOrNull()
        val realVersion = capabilities?.plugin?.version?.takeIf { it.isNotBlank() }
            ?: pinned.version ?: manifest.version
        val notes = buildList {
            add("profile/dsh/profile.json 已注册（dsh plugin add 语义）")
            if (capabilities != null) {
                add("能力面：${capabilities.summaryLine()}")
                if (capabilities.availableTools.isNotEmpty()) {
                    add("可用工具：${capabilities.availableTools.joinToString(", ") { it.name }}（经 DSH 桥执行，experimental）")
                }
            } else {
                add("能力验证未接入 —— 安装-only 旧语义（experimental）")
            }
        }

        return Result.success(
            InstallOutcome(
                id = manifest.id,
                type = manifest.type,
                version = realVersion,
                where = "沙箱扩展目录: ${published.path}",
                notes = notes,
                pinned = pinned
            )
        )
    }

    override suspend fun uninstall(manifest: ExtensionManifest): Result<Unit> {
        layoutRef.wipe(manifest.id)
        return Result.success(Unit)
    }

    /**
     * 入口提示：npm 形态解析 package.json main/module 到**真实入口文件**
     * （2026-11 修复：此前返回 package.json 自身路径，ExtensionRun 会对它
     * 执行 `node package.json`）；clone 形态看 src 根。解析不出 = 目录路径
     * （ExtensionRun 的目录容忍链继续兜底）。
     */
    private fun entryHint(published: File): String {
        val pkgJson = File(published, "package.json")
        if (pkgJson.isFile) {
            val main = runCatching {
                ExtensionJson.json.parseToJsonElement(pkgJson.readText()).jsonObject
            }.getOrNull()
            val mainRel = main?.get("main")?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
                ?: main?.get("module")?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
            if (mainRel != null) {
                val entry = File(published, mainRel)
                for (candidate in listOf(entry, File("$entry.js"), File("$entry.cjs"), File("$entry.mjs"))) {
                    if (candidate.isFile) return candidate.absolutePath
                }
            }
        }
        return published.absolutePath
    }

    private fun str(manifest: ExtensionManifest, key: String): String =
        manifest.resolved?.get(key)?.toString()?.trim('"')
            ?: throw IllegalArgumentException("resolved manifest 缺少 $key")
}
