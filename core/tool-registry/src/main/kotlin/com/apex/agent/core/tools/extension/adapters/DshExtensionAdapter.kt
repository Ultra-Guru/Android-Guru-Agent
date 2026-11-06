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
 * # DshExtensionAdapter —— DSH（DeepSeek Harness）插件适配器（runtime = ubuntu）
 *
 * brief 语义：`dsh:<npm 包名>` / `dsh:github:owner/repo` —— 模拟
 * `dsh plugin add` 的 profile 安装路径：实体装进沙箱扩展目录，同时在
 * `profile/dsh/profile.json` 写注册清单（name/version/source/entry —— DSH
 * harness 未来接入沙箱时按此 profile 发现插件）。npm 源强制 `dsh-*` 前缀
 * （DSH 插件的包名约定，防止借 dsh: 前缀装任意 npm 包）。
 *
 * **执行桥 experimental**：harness 本体不在沙箱内运行；本适配器保证
 * 安装 / profile 注册 / 健康检查闭环。
 */
class DshExtensionAdapter(
    port: NodeInstallPort,
    layout: ExtensionDirLayout
) : ExtensionAdapter {

    private val installer = NodeFamilyInstaller(port, layout)
    private val layoutRef = layout
    private val portRef = port

    override val supportedTypes: Set<String> = setOf(ExtensionTypes.DSH_PLUGIN)

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
            summary = "DeepSeek Harness 插件「$name」（dsh plugin add 语义模拟 → PRoot Ubuntu 沙箱；执行桥 experimental）"
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
                warnings = listOf("执行桥 experimental：harness 调度是后续工作，本安装只落实体与 profile")
            )
        )
    }

    override suspend fun install(manifest: ExtensionManifest): Result<InstallOutcome> {
        val kind = manifest.resolved?.get("kind")?.toString()?.trim('"')
        val published = when (kind) {
            "npm" -> installer.npmInstall(manifest.id, str(manifest, "pkg"))
            "github" -> installer.gitClone(manifest.id, str(manifest, "repo"))
            else -> return Result.failure(IllegalArgumentException("未知来源 kind：$kind"))
        }.getOrElse { return Result.failure(it) }

        // dsh plugin add 语义模拟：profile 注册清单（DSH harness 接入沙箱时的发现入口）
        val profileDir = layoutRef.profileDir(manifest.id).resolve("dsh").apply { mkdirs() }
        ExtensionJson.json.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(),
            buildJsonObject {
                put("schema", "dsh-profile-v1")
                put("id", manifest.id)
                put("name", manifest.name)
                put("spec", manifest.spec)
                put("kind", kind ?: "unknown")
                put("version", manifest.version)
                put("pkgPath", published.absolutePath)
                put("entryHint", entryHint(published))
                put("registeredAt", System.currentTimeMillis())
                put("experimental", true)
            }
        ).let { File(profileDir, "profile.json").writeText(it) }

        return Result.success(
            InstallOutcome(
                id = manifest.id,
                type = manifest.type,
                version = manifest.version,
                where = "沙箱扩展目录: ${published.path}",
                notes = listOf("profile/dsh/profile.json 已注册（dsh plugin add 语义模拟）", "执行桥 experimental")
            )
        )
    }

    override suspend fun uninstall(manifest: ExtensionManifest): Result<Unit> {
        layoutRef.wipe(manifest.id)
        return Result.success(Unit)
    }

    /** 入口提示：npm 形态看 package.json main，clone 形态看 src 根。 */
    private fun entryHint(published: File): String {
        val pkgJson = File(published, "package.json")
        if (pkgJson.isFile) {
            val main = runCatching {
                ExtensionJson.json.parseToJsonElement(pkgJson.readText())
            }.getOrNull()
            main?.let { return pkgJson.absolutePath }
        }
        return published.absolutePath
    }

    private fun str(manifest: ExtensionManifest, key: String): String =
        manifest.resolved?.get(key)?.toString()?.trim('"')
            ?: throw IllegalArgumentException("resolved manifest 缺少 $key")
}
