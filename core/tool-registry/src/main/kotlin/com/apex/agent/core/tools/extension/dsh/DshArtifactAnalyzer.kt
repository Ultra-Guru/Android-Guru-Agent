package com.apex.agent.core.tools.extension.dsh

import com.apex.agent.core.tools.extension.ExtensionDirLayout
import com.apex.agent.core.tools.extension.NodeInstallPort
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * # DshArtifactAnalyzer —— Stage 1 静态能力分析（不执行插件代码）
 *
 * DSHA plugin-capabilities 哲学落地：包结构/入口/依赖/声明的**事实**在宿主侧
 * 直接读文件得出；JS 源码的 AST 线索（ctx.* 触碰、inject 声明、动态面标记）
 * 由沙箱内 `dsh-analyze.js`（Acorn 钉版，只 parse 不 require）完成 ——
 * **被检代码绝不执行**（验收 D05）。
 *
 * 预算（防恶意巨包拖垮宿主）：文件数 ≤ [MAX_FILES]、总字节 ≤ [MAX_TOTAL_BYTES]、
 * 超出部分计入 skipped 如实上报；node_modules/.git 不入清单（依赖面由
 * DshCompatibilityResolver/版本钉定负责，不属于本插件本体）。
 *
 * 诚实降级：acorn 未 bootstrap 时 AST 面返回 analyzer=null（DshHostScripts
 * 脚本如实声明），reconciliation 只信 observed —— 静态面永不冒充分析结论。
 */
class DshArtifactAnalyzer(
    private val port: NodeInstallPort,
    private val layout: ExtensionDirLayout,
    private val extensionRoot: File
) {

    /** 包形态分类（brief 修正四：不凭 dsh- 前缀判兼容）。 */
    enum class PackageClass {
        /** Cordis 代码插件（函数/对象模块）。 */
        CORDIS_CODE,
        /** DSH Bundle（声明 dsh.bundle / patch 配置行）。 */
        DSH_BUNDLE,
        /** DSH Client UI 插件（依赖 web client 运行时 —— 宿主永不支持其 UI）。 */
        CLIENT_UI,
        /** 半成品（无可用入口）。 */
        PARTIAL_ARTIFACT
    }

    data class StaticReport(
        val packageClass: PackageClass,
        val pkgName: String?,
        val version: String?,
        val declaredDshRange: String?,
        val declaredNodeRange: String?,
        val mainEntry: String?,
        val analyzedFiles: Int,
        val skippedFiles: Int,
        val fingerprint: String,
        val analyzerUsed: String?,
        val serviceTouches: Map<String, List<String>>,
        val declaredInject: List<String>,
        val dynamicMarkers: List<String>,
        val notes: List<String>
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun analyze(extId: String): Result<StaticReport> = runCatching {
        val pkgDir = layout.pkgDir(extId)
        if (!pkgDir.isDirectory) throw IllegalStateException("扩展未安装：$extId")
        // npm 安装布局的插件根在 node_modules/<name>/（DshPackageLayout 统一定位）
        val pkg = DshPackageLayout.pluginRootOf(pkgDir)
        val pkgJson = File(pkg, "package.json")
        if (!pkgJson.isFile) throw IllegalStateException("package.json 缺失 —— 非 npm 形态插件")
        val meta = json.parseToJsonElement(pkgJson.readText()).jsonObject

        val name = meta["name"]?.jsonPrimitive?.content
        val version = meta["version"]?.jsonPrimitive?.content
        val mainEntry = (meta["main"] ?: meta["module"])?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
        val declaredDshRange = meta["engines"]?.jsonObject?.get("dsh")?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
            ?: meta["peerDependencies"]?.jsonObject?.get("@deepseek-ai/dsh")?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
        val declaredNodeRange = meta["engines"]?.jsonObject?.get("node")?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
        val deps = (meta["dependencies"]?.jsonObject?.keys ?: emptySet()) + (meta["peerDependencies"]?.jsonObject?.keys ?: emptySet())
        val hasBundle = meta.containsKey("dsh.bundle") || meta["dsh"]?.jsonObject?.containsKey("bundle") == true

        val packageClass = when {
            hasBundle -> PackageClass.DSH_BUNDLE
            deps.any { it.contains("dsh-web") || it.contains("dsh-client") } -> PackageClass.CLIENT_UI
            mainEntry != null || File(pkg, "index.js").isFile -> PackageClass.CORDIS_CODE
            else -> PackageClass.PARTIAL_ARTIFACT
        }

        // ── 文件清单 + 预算 + 内容指纹（DshPackageFingerprint 单一真相源）──
        val notes = mutableListOf<String>()
        val fingerprintResult = DshPackageFingerprint.compute(pkg)
        val analyzedFiles = fingerprintResult.analyzedFiles
        val skippedFiles = fingerprintResult.skippedFiles
        val fingerprint = fingerprintResult.fingerprint
        val scripts = pkg.walkTopDown()
            .filter { it.isFile }
            .filter { f -> !relPath(pkg, f).startsWith("node_modules/") && !relPath(pkg, f).startsWith(".git/") }
            .map { relPath(pkg, it) }
            .filter { it.endsWith(".js") || it.endsWith(".mjs") || it.endsWith(".cjs") }
            .toList()
        if (skippedFiles > 0) notes.add("文件预算截断：$skippedFiles 个文件未分析（上限 $MAX_FILES 个 / ${MAX_TOTAL_BYTES}B）")

        // ── AST 线索（沙箱内 acorn；未 bootstrap = analyzer null 诚实降级）──
        var analyzerUsed: String? = null
        var serviceTouches: Map<String, List<String>> = emptyMap()
        var declaredInject: List<String> = emptyList()
        var dynamicMarkers: List<String> = emptyList()
        if (scripts.isNotEmpty() && DshHostScripts.analyzerReady(extensionRoot)) {
            val deployed = DshHostScripts.deploy(extensionRoot).getOrNull()
            if (deployed != null) {
                val manifestFile = File(pkg.parentFile, "profile/.analyze-manifest.tmp.json")
                runCatching {
                    manifestFile.parentFile?.mkdirs()
                    manifestFile.writeText(json.encodeToString(
                        kotlinx.serialization.serializer<List<String>>(), scripts
                    ))
                    val outcome = port.exec(
                        command = listOf(
                            "node", File(deployed, "dsh-analyze.js").absolutePath,
                            pkg.absolutePath, manifestFile.absolutePath,
                            DshHostScripts.analyzerDir(extensionRoot).absolutePath
                        ),
                        cwd = pkg,
                        timeoutMs = 60_000
                    ).getOrThrow()
                    if (outcome.success) {
                        val env = json.parseToJsonElement(outcome.stdout.trim()).jsonObject
                        if (env["ok"]?.jsonPrimitive?.contentOrNull() == "true") {
                            analyzerUsed = env["analyzer"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
                            serviceTouches = env["serviceTouches"]?.jsonObject?.mapValues { (_, v) ->
                                v.jsonArray.map { it.jsonPrimitive.content }
                            } ?: emptyMap()
                            declaredInject = env["declaredInject"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
                            dynamicMarkers = env["dynamic"]?.jsonArray?.map { d ->
                                val o = d.jsonObject
                                "${o["kind"]?.jsonPrimitive?.content}:${o["file"]?.jsonPrimitive?.content}:${o["line"]?.jsonPrimitive?.content}"
                            } ?: emptyList()
                        }
                    }
                }.onFailure { notes.add("AST 面降级：${it.message}") }
                manifestFile.delete()
            }
        } else if (scripts.isNotEmpty()) {
            notes.add("AST 分析器未就绪（.dsh-host/analyzer 无 acorn）—— 静态面只有包结构事实")
        }

        StaticReport(
            packageClass = packageClass,
            pkgName = name,
            version = version,
            declaredDshRange = declaredDshRange,
            declaredNodeRange = declaredNodeRange,
            mainEntry = mainEntry,
            analyzedFiles = analyzedFiles,
            skippedFiles = skippedFiles,
            fingerprint = fingerprint,
            analyzerUsed = analyzerUsed,
            serviceTouches = serviceTouches,
            declaredInject = declaredInject,
            dynamicMarkers = dynamicMarkers,
            notes = notes
        )
    }

    private fun relPath(pkg: File, f: File): String =
        f.relativeTo(pkg).invariantSeparatorsPath

    private fun kotlinx.serialization.json.JsonElement.contentOrNull(): String? =
        runCatching { jsonPrimitive.content }.getOrNull()

    companion object {
        const val MAX_FILES = 300
        const val MAX_TOTAL_BYTES = 2L * 1024 * 1024
    }
}
