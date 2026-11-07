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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * # OpenCodeExtensionAdapter —— OpenCode 生态适配器（runtime = ubuntu）
 *
 * OpenCode 插件 = npm 包 / 本地 .ts/.js 文件（opencode 插件 API）。本适配器
 * 覆盖「装到沙箱扩展目录 + 注册元数据」闭环；**执行桥 experimental**：
 * opencode 运行时本身不在本沙箱内运行，插件宿主调度是后续工作 —— inspect
 * 与安装结果都如实标注，绝不虚报可用性。
 */
class OpenCodeExtensionAdapter(
    port: NodeInstallPort,
    layout: ExtensionDirLayout
) : ExtensionAdapter {

    private val installer = NodeFamilyInstaller(port, layout)
    private val layoutRef = layout
    private val portRef = port

    override val supportedTypes: Set<String> = setOf(ExtensionTypes.OPENCODE_PLUGIN)

    override suspend fun resolve(draft: ExtensionSpecParser.SpecDraft): Result<ExtensionManifest> {
        val payload = draft.payload
        val (name, sourceKind, descriptor) = when {
            draft.sourceKind == SourceKinds.NPM && ExtensionSpecParser.isNpmName(payload) ->
                Triple(payload, SourceKinds.NPM, buildJsonObject {
                    put("kind", "npm"); put("pkg", payload)
                })
            ExtensionSpecParser.githubRepoOf(payload) != null ->
                Triple(payload.removePrefix("github:"), SourceKinds.GITHUB, buildJsonObject {
                    put("kind", "github"); put("repo", ExtensionSpecParser.githubRepoOf(payload))
                })
            payload.contains('/') || payload.contains('\\') || payload.endsWith(".ts") || payload.endsWith(".js") ->
                Triple(File(payload).nameWithoutExtension.ifBlank { "local-plugin" }, SourceKinds.LOCAL, buildJsonObject {
                    put("kind", "local"); put("path", payload)
                })
            else -> return Result.failure(
                IllegalArgumentException("opencode spec 无法识别：'$payload'（支持 npm 包名 / 本地 .ts/.js 路径 / github:owner/repo）")
            )
        }
        val manifest = ExtensionManifest(
            id = "opencode.${slugOf(name)}",
            type = ExtensionTypes.OPENCODE_PLUGIN,
            spec = draft.raw,
            name = name,
            version = "0.0.0", // npm 元数据安装后回填（不额外发网络请求探版本）
            sourceKind = sourceKind,
            runtime = RuntimeDomains.UBUNTU,
            resolved = descriptor,
            summary = "OpenCode 插件「$name」（npm/GitHub 源 → PRoot Ubuntu 沙箱扩展目录；执行桥 experimental）"
        )
        return Result.success(manifest.copy(manifestHash = manifest.computeHash()))
    }

    override suspend fun inspect(manifest: ExtensionManifest): Result<InspectReport> {
        val probe = portRef.probeRuntime()
        val deps = buildList {
            add("PRoot Ubuntu rootfs 就绪")
            add(if (probe.ready) "node ${probe.nodeVersion} / npm ${probe.npmVersion}" else "⚠️ node/npm 在沙箱内不可解析（先 TerminalLinuxBootstrap/TerminalLinuxPackages 装齐）")
        }
        return Result.success(
            InspectReport(
                manifest = manifest,
                targetRegistry = "沙箱扩展目录（linux/extensions/${manifest.id}/pkg，宿主与 guest 经同路径 bind 共见）",
                targetLocation = "${manifest.id}/pkg",
                domainDependencies = deps,
                estimatedSize = layoutRef.sizeSummary(manifest.id),
                warnings = listOf(
                    "执行桥 experimental：本 PR 只保证安装/注册/健康检查闭环，opencode 运行时调度插件是后续工作"
                )
            )
        )
    }

    override suspend fun install(manifest: ExtensionManifest): Result<InstallOutcome> {
        val kind = manifest.resolved?.get("kind")?.toString()?.trim('"')
        val published = when (kind) {
            "npm" -> installer.npmInstall(manifest.id, str(manifest, "pkg"))
            "github" -> installer.gitClone(manifest.id, str(manifest, "repo"))
            "local" -> installer.installLocalPath(manifest.id, str(manifest, "path"), setOf("ts", "js", "mjs", "cjs"))
            else -> return Result.failure(IllegalArgumentException("未知来源 kind：$kind"))
        }.getOrElse { return Result.failure(it) }

        // 注册元数据（ExtensionRun 的运行入口清单）：runtime/main 供
        // NodeInstallPort 白名单执行；profile 文件经 layout 规范化归属校验
        val profileFile = layoutRef.profileFile(manifest.id, "opencode.json").getOrElse { return Result.failure(it) }
        // 入口提示按 kind 取 —— npm 看 node_modules/<pkg>，clone 看 src/，
        // 其余（local 单文件等）退 published 根；kind 键缺失的源不强取 pkg
        val entryHint = when (kind) {
            "npm" -> File(published, "node_modules/${str(manifest, "pkg")}").takeIf { it.isDirectory }?.absolutePath
            "github" -> File(published, "src").takeIf { it.isDirectory }?.absolutePath
            else -> null
        } ?: published.absolutePath
        ExtensionJson.json.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(),
            buildJsonObject {
                put("id", manifest.id)
                put("name", manifest.name)
                put("spec", manifest.spec)
                put("kind", kind ?: "unknown")
                put("runtime", RUNTIME_NODE)
                put("publishedAt", System.currentTimeMillis())
                put("pkgPath", published.absolutePath)
                put("entryHint", entryHint)
                put("experimental", true)
            }
        ).let { profileFile.writeText(it) }

        return Result.success(
            InstallOutcome(
                id = manifest.id,
                type = manifest.type,
                version = manifest.version,
                where = "沙箱扩展目录: ${published.path}",
                notes = listOf("注册元数据已写 profile/opencode.json", "执行桥 experimental —— 插件尚未被运行时调度")
            )
        )
    }

    override suspend fun uninstall(manifest: ExtensionManifest): Result<Unit> {
        layoutRef.wipe(manifest.id)
        return Result.success(Unit)
    }

    private fun str(manifest: ExtensionManifest, key: String): String =
        manifest.resolved?.get(key)?.toString()?.trim('"')
            ?: throw IllegalArgumentException("resolved manifest 缺少 $key")

    internal companion object {
        /** 运行时标记（ExtensionRun 白名单执行依据：node 入口）。 */
        const val RUNTIME_NODE = "node"

        /** npm scoped 名（@scope/name）与包名 → 账本 id 安全 slug。 */
        fun slugOf(raw: String): String = raw.lowercase()
            .removePrefix("@").replace('/', '-').replace('_', '-')
            .replace(Regex("[^a-z0-9.-]"), "-").trim('-', '.').take(48).ifEmpty { "plugin" }
    }
}
