package com.apex.agent.core.tools.extension.dsh

import com.apex.agent.core.tools.extension.ExtensionDirLayout
import com.apex.agent.core.tools.extension.NodeInstallPort
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * # DshPluginIntrospector —— Stage 2 动态验证 + 能力调和（Reconciliation）
 *
 * 沙箱内执行 `node dsh-introspect.js <pkgDir>`（shim 加载插件、apply 一次、
 * 捕获注册面）→ 与 [DshArtifactAnalyzer] 的静态面调和 → 产出 [DshCapabilities]
 * 经 [DshCapabilitiesStore] 落盘。
 *
 * 调和纪律（红线 5/6）：
 * - **只有 observed 捕获到的工具才可能 available** —— 静态线索不产生工具；
 * - 服务依赖不满足（inject/触碰）→ 对应工具 unavailable + 原因；
 * - 探测失败是**结果**（envelope ok:false → capabilities 记 error，不炸账本）。
 */
class DshPluginIntrospector(
    private val port: NodeInstallPort,
    private val layout: ExtensionDirLayout,
    private val extensionRoot: File,
    private val analyzer: DshArtifactAnalyzer,
    private val store: DshCapabilitiesStore,
    private val resolver: DshCompatibilityResolver = DshCompatibilityResolver()
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 全量分析 + 调和 + 落盘。
     *
     * @param pinnedSource 来源钉定描述（npm version@integrity / git SHA）
     * @return capabilities（即使探测失败也产出结构化结果 —— available 全 false + 原因）
     */
    suspend fun introspectAndStore(extId: String, pinnedSource: String): Result<DshCapabilities> = runCatching {
        val probe = port.probeRuntime()
        val static = analyzer.analyze(extId).getOrElse {
            return@runCatching capabilitiesForFailure(
                extId, pinnedSource, "静态分析失败：${it.message}"
            )
        }
        val verdict = resolver.resolve(static.declaredDshRange, static.declaredNodeRange, probe)

        val observed = probeRuntimeCapabilities(extId)

        // ── 调和：observed 为准，static/verdict 提供服务与原因 ──
        val supported = DshCompatibilityResolver.SERVICE_SUPPORT
            .filterValues { it == DshCompatibilityResolver.ServiceLevel.SUPPORTED }.keys
        val unsupported = (verdict.unsupportedServices + observed.unsupportedServices + static.serviceTouches.keys
            .filter { it !in supported })
            .distinct()
        val tools = observed.tools.map { t ->
            val blockingService = unsupported.firstOrNull { svc ->
                svc in (observed.toolServices[t.name] ?: emptyList()) || observed.applyAbortedOn == svc
            }
            when {
                blockingService != null -> t.copy(
                    available = false,
                    unavailableReason = "依赖未支持服务 $blockingService（宿主 shim 不提供）"
                )
                else -> t.copy(available = true)
            }
        }
        val unavailableServices = unsupported.map { svc ->
            DshCapabilities.UnavailableService(
                service = svc,
                reason = "宿主 shim 不提供该 Cordis 服务（${supportedLevelOf(svc)}）",
                affectedTools = tools.filter { it.unavailableReason?.contains(svc) == true }.map { it.name }
            )
        }

        val capabilities = DshCapabilities(
            plugin = DshCapabilities.PluginIdentity(
                id = extId,
                name = static.pkgName ?: observed.pluginName ?: extId,
                version = static.version ?: "",
                pinnedSource = pinnedSource,
                runtimeVersion = probe.nodeVersion ?: ""
            ),
            compatibility = DshCapabilities.Compatibility(
                level = verdict.level,
                requiredServices = (static.declaredInject + observed.requiredServices).distinct(),
                supportedServices = supported.toList(),
                unsupportedServices = unsupported
            ),
            tools = tools,
            skills = staticSkills(static),
            ui = emptyList(),
            unavailable = unavailableServices,
            analyzedAt = System.currentTimeMillis(),
            analyzerVersion = DshCapabilities.ANALYZER_VERSION,
            inputFingerprint = static.fingerprint
        )
        store.write(extId, capabilities).getOrThrow()
        capabilities
    }

    /** 技能面：静态 SKILL.md 线索（本期仅登记 + available=false —— 转换在 P3 DshSkillAdapter）。 */
    private fun staticSkills(static: DshArtifactAnalyzer.StaticReport): List<DshCapabilities.SkillCapability> {
        // PR1 范围：静态技能文件在 analyze 的指纹面里存在但未单独枚举 ——
        // 如实留空（不虚报技能），P3 扩展 analyzer 枚举 skills/ 目录
        return emptyList()
    }

    private fun supportedLevelOf(service: String): String =
        DshCompatibilityResolver.SERVICE_SUPPORT[service]?.name ?: "UNKNOWN"

    private data class Observed(
        val ok: Boolean,
        val pluginName: String?,
        val form: String?,
        val requiredServices: List<String>,
        val unsupportedServices: List<String>,
        val tools: List<DshCapabilities.ToolCapability>,
        val toolServices: Map<String, List<String>>,
        val applyAbortedOn: String?,
        val error: String?
    )

    private suspend fun probeRuntimeCapabilities(extId: String): Observed {
        val deployed = DshHostScripts.deploy(extensionRoot).getOrElse {
            return Observed(false, null, null, emptyList(), emptyList(), emptyList(), emptyMap(), null,
                "脚本部署失败：${it.message}")
        }
        val pkgDir = layout.pkgDir(extId)
        if (!pkgDir.isDirectory) {
            return Observed(false, null, null, emptyList(), emptyList(), emptyList(), emptyMap(), null,
                "扩展 pkg 目录不存在：$extId")
        }
        // npm 安装布局的插件根（DshPackageLayout 统一定位 —— 探测的是包本体）
        val pkg = try {
            DshPackageLayout.pluginRootOf(pkgDir)
        } catch (e: IllegalStateException) {
            return Observed(false, null, null, emptyList(), emptyList(), emptyList(), emptyMap(), null, e.message)
        }
        val outcome = port.exec(
            command = listOf("node", File(deployed, "dsh-introspect.js").absolutePath, pkg.absolutePath),
            cwd = pkg,
            timeoutMs = 60_000
        ).getOrElse {
            return Observed(false, null, null, emptyList(), emptyList(), emptyList(), emptyMap(), null,
                "探测执行失败：${it.message}")
        }
        if (!outcome.success) {
            return Observed(false, null, null, emptyList(), emptyList(), emptyList(), emptyMap(), null,
                "探测退出码 ${outcome.exitCode}：${outcome.stderr.take(300)}")
        }
        val env = runCatching { json.parseToJsonElement(outcome.stdout.trim()).jsonObject }.getOrElse {
            return Observed(false, null, null, emptyList(), emptyList(), emptyList(), emptyMap(), null,
                "探测输出非 JSON：${outcome.stdout.take(200)}")
        }
        val ok = env["ok"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() } == "true"
        val toolsJson = env["tools"]?.jsonArray ?: kotlinx.serialization.json.JsonArray(emptyList())
        val tools = toolsJson.map { t ->
            val o = t.jsonObject
            DshCapabilities.ToolCapability(
                name = o["name"]?.jsonPrimitive?.content ?: "",
                description = o["description"]?.jsonPrimitive?.content ?: "",
                parametersSchema = o["parametersSchema"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() },
                outputSchema = o["outputSchema"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() },
                executionMode = DshCapabilities.ToolCapability.MODE_COLD_START,
                available = false // 调和阶段定
            )
        }
        val touched = env["servicesTouched"]?.jsonObject?.keys?.toList() ?: emptyList()
        val unsupported = env["unsupportedServices"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        val required = env["requiredServices"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        val abortedOn = env["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
            ?.takeIf { it.startsWith("service-unavailable:") }
            ?.substringAfter("service-unavailable:")?.trim()?.substringBefore(' ')
        return Observed(
            ok = ok,
            pluginName = env["name"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() },
            form = env["form"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() },
            requiredServices = required,
            unsupportedServices = unsupported,
            tools = tools,
            toolServices = tools.associate { it.name to required }, // 粗粒度：插件级依赖映射到全部工具（保守）
            applyAbortedOn = abortedOn,
            error = env["error"]?.jsonObject?.get("message")?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
        )
    }

    private fun capabilitiesForFailure(extId: String, pinnedSource: String, message: String): DshCapabilities =
        DshCapabilities(
            plugin = DshCapabilities.PluginIdentity(id = extId, pinnedSource = pinnedSource),
            compatibility = DshCapabilities.Compatibility(
                level = DshCapabilities.Compatibility.Level.INCOMPATIBLE,
                unsupportedServices = emptyList()
            ),
            unavailable = listOf(
                DshCapabilities.UnavailableService(service = "(analysis)", reason = message, affectedTools = emptyList())
            ),
            analyzedAt = System.currentTimeMillis(),
            analyzerVersion = DshCapabilities.ANALYZER_VERSION
        )
}
