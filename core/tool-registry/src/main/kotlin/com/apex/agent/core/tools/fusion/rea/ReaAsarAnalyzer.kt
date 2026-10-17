package com.apex.agent.core.tools.fusion.rea

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import java.io.File
import java.io.RandomAccessFile

/**
 * # REA JavaScript / Electron 应用分析器（旗舰，纯设备实现）
 *
 * 对齐上游 `analyze_javascript_application` 的核心契约：**把本地 ASAR 或
 * 解包后的 JS/Electron 应用静态重构为应用图** —— 模块清单、import 图、
 * Electron 进程边界（main / renderer / preload）、package.json 依赖，
 * 全部带证据（来源文件 + 命中行）与限制（正则提取、非完整 AST）。
 *
 * ASAR 格式（Electron 官方 archive）：
 * `| u32=4 | u32=headerSize | header JSON（headerSize 字节，含空格填充）| 文件数据 |`
 * 头 JSON 的 files 树里，文件节点带字符串形式的 offset/size（相对数据区起点）。
 *
 * import 提取：require('x') / import from 'x' / export from 'x' 三族正则
 * （命中即证据；无完整 AST —— limitation 如实声明，与上游静态语义对齐）。
 */
object ReaAsarAnalyzer {

    /** ASAR 头解析结果：文件路径 → (数据区偏移, 大小)。 */
    data class AsarIndex(
        val headerSize: Int,
        val entries: Map<String, LongRange>, // path → [dataStart, dataEnd)
        val headerJson: String
    ) {
        val dataSize: Long get() = entries.values.maxOfOrNull { it.last + 1 } ?: 0L
    }

    /** 解析 ASAR 头（失败返回 null —— 非法头/非 ASAR）。 */
    fun parseAsarIndex(file: File): AsarIndex? {
        RandomAccessFile(file, "r").use { f ->
            if (f.length() < 16) return null
            // ASAR 头两字段为小端 u32（RandomAccessFile.readInt 是大端，手工组装）
            fun le32(): Int {
                val b0 = f.readUnsignedByte()
                val b1 = f.readUnsignedByte()
                val b2 = f.readUnsignedByte()
                val b3 = f.readUnsignedByte()
                return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
            }
            if (le32() != 4) return null // pickle 头
            val headerSize = le32()
            if (headerSize <= 0 || headerSize > 64 * 1024 * 1024) return null
            val headerBytes = ByteArray(headerSize)
            f.readFully(headerBytes)
            val headerJson = String(headerBytes, Charsets.UTF_8).trim()
            val header = runCatching {
                Json.parseToJsonElement(headerJson).jsonObject
            }.getOrNull() ?: return null
            val entries = mutableMapOf<String, LongRange>()
            val dataBase = 8L + headerSize
            fun walk(node: JsonObject, prefix: String) {
                val files = node["files"]?.jsonObject ?: return
                for ((name, child) in files) {
                    val childObj = child as? JsonObject ?: continue
                    val path = if (prefix.isEmpty()) name else "$prefix/$name"
                    val offsetStr = childObj["offset"]?.jsonPrimitive?.contentOrNull
                    val sizeStr = childObj["size"]?.jsonPrimitive?.contentOrNull
                    if (offsetStr != null && sizeStr != null) {
                        val off = offsetStr.toLongOrNull() ?: continue
                        val size = sizeStr.toLongOrNull() ?: continue
                        entries[path] = (dataBase + off)..(dataBase + off + size - 1)
                    } else {
                        walk(childObj, path) // 目录节点
                    }
                }
            }
            walk(header, "")
            return AsarIndex(headerSize = headerSize, entries = entries, headerJson = headerJson)
        }
    }

    /** 从 ASAR 读取单文件内容（bounded：≤ 4MB / 单文件）。 */
    fun readAsarEntry(file: File, index: AsarIndex, path: String): ByteArray? {
        val range = index.entries[path] ?: return null
        val size = (range.last - range.first + 1).coerceAtMost(4L * 1024 * 1024)
        val buf = ByteArray(size.toInt())
        RandomAccessFile(file, "r").use { f ->
            f.seek(range.first)
            val n = f.read(buf)
            if (n <= 0) return null
            return buf.copyOf(n)
        }
    }

    // ── import 提取（三族正则，命中即证据）─────────────────────────────

    private val RE_REQUIRE = Regex("""require\(\s*['"]([^'"]+)['"]\s*\)""")
    private val RE_IMPORT_FROM = Regex("""(?:^|\n)\s*import\s+[^;\n]*?from\s+['"]([^'"]+)['"]""")
    private val RE_EXPORT_FROM = Regex("""export\s+[^;\n]*?from\s+['"]([^'"]+)['"]""")
    private val RE_DYNAMIC_IMPORT = Regex("""import\(\s*['"]([^'"]+)['"]\s*\)""")

    /** 单文件 import 提取（spec + 行号）。 */
    data class ImportRef(val spec: String, val line: Int, val kind: String)

    fun extractImports(content: String): List<ImportRef> {
        val refs = mutableListOf<ImportRef>()
        fun lineOf(index: Int): Int = content.take(index).count { it == '\n' } + 1
        for (m in RE_REQUIRE.findAll(content)) refs.add(ImportRef(m.groupValues[1], lineOf(m.range.first), "require"))
        for (m in RE_IMPORT_FROM.findAll(content)) refs.add(ImportRef(m.groupValues[1], lineOf(m.range.first), "import"))
        for (m in RE_EXPORT_FROM.findAll(content)) refs.add(ImportRef(m.groupValues[1], lineOf(m.range.first), "export-from"))
        for (m in RE_DYNAMIC_IMPORT.findAll(content)) refs.add(ImportRef(m.groupValues[1], lineOf(m.range.first), "dynamic-import"))
        return refs
    }

    /** Electron 边界判定证据标记。 */
    private val MAIN_MARKERS = listOf("BrowserWindow", "ipcMain", "app.quit", "app.whenReady", "electron", "powerMonitor")
    private val RENDERER_MARKERS = listOf("ipcRenderer", "contextBridge", "process.type", "window.require", "document.", "localStorage")

    /**
     * 应用图节点（模块级）：路径、字节大小、import 出边、Electron 角色。
     */
    data class JsModule(
        val path: String,
        val sizeBytes: Long,
        val imports: List<ImportRef>,
        val electronRole: String // main / renderer / preload / shared / unknown
    )

    /**
     * 静态重构一个本地应用（ASAR 文件或已解包目录）。
     *
     * @param maxModules 模块数上限（防巨型应用爆内存；默认 8000）
     */
    fun analyzeApplication(root: File, maxModules: Int = 8000): Pair<List<JsModule>, JsonObject?> {
        val modules = mutableListOf<JsModule>()
        var packageJson: JsonObject? = null

        fun addModule(path: String, size: Long, content: String) {
            if (modules.size >= maxModules) return
            val imports = extractImports(content)
            val role = detectRole(content)
            modules.add(JsModule(path, size, imports, role))
        }

        if (root.isFile && root.name.endsWith(".asar", true)) {
            val index = parseAsarIndex(root)
                ?: return emptyList<JsModule>() to null
            for ((path, range) in index.entries.entries.sortedBy { it.key }) {
                val size = range.last - range.first + 1
                if (path.endsWith(".js", true) || path.endsWith(".mjs", true) || path.endsWith(".cjs", true)) {
                    val content = readAsarEntry(root, index, path)?.toString(Charsets.UTF_8) ?: continue
                    addModule(path, size, content)
                } else if (path == "package.json") {
                    packageJson = readAsarEntry(root, index, path)
                        ?.toString(Charsets.UTF_8)
                        ?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
                }
            }
        } else if (root.isDirectory) {
            val files = root.walkTopDown()
                .filter { it.isFile }
                .filter { it.extension.lowercase() in setOf("js", "mjs", "cjs", "json") }
                .take(maxModules * 2)
            for (f in files) {
                val rel = f.relativeTo(root).invariantSeparatorsPath
                if (rel.endsWith(".json", true)) {
                    if (rel == "package.json") {
                        packageJson = runCatching {
                            Json.parseToJsonElement(f.readText()).jsonObject
                        }.getOrNull()
                    }
                    continue
                }
                if (f.length() > 4L * 1024 * 1024) continue // 超 4MB 的巨型 bundle 单独跳过（限制里声明）
                addModule(rel, f.length(), f.readText())
            }
        }

        return modules to packageJson
    }

    private fun detectRole(content: String): String {
        val head = content.take(20_000)
        val mainHits = MAIN_MARKERS.count { head.contains(it) }
        val rendererHits = RENDERER_MARKERS.count { head.contains(it) }
        return when {
            mainHits > rendererHits && mainHits > 0 -> "main"
            rendererHits > 0 && head.contains("contextBridge") -> "preload"
            rendererHits > 0 -> "renderer"
            mainHits > 0 -> "main"
            else -> "unknown"
        }
    }

    // ── 渲染 ────────────────────────────────────────────────────────────

    /** analyze_javascript_application 的 Evidence 渲染。 */
    fun renderAnalysis(tool: String, root: File): JsonObject {
        val (modules, packageJson) = analyzeApplication(root)
        if (modules.isEmpty() && packageJson == null) {
            return ReaEvidence.error(
                tool, root.absolutePath,
                "未发现 JS 模块与 package.json（目标既非 ASAR 也非 JS 应用目录）"
            )
        }
        val findings = mutableListOf<ReaEvidence.Finding>()
        val evidence = mutableListOf<ReaEvidence.Item>()
        val limitations = mutableListOf<String>()

        findings.add(ReaEvidence.Finding("modules", "${modules.size} 个 JS 模块（上限 8000）"))
        val edges = modules.sumOf { it.imports.size }
        findings.add(ReaEvidence.Finding("import edges", "$edges 条 import/require 引用"))

        // Electron 边界
        val byRole = modules.groupBy { it.electronRole }
        val boundary = buildString {
            appendLine("Electron 进程边界：")
            appendLine("  main:     ${byRole["main"]?.size ?: 0}")
            appendLine("  renderer: ${byRole["renderer"]?.size ?: 0}")
            appendLine("  preload:  ${byRole["preload"]?.size ?: 0}")
            appendLine("  unknown:  ${byRole["unknown"]?.size ?: 0}")
        }
        findings.add(ReaEvidence.Finding("electron boundaries", boundary.trim()))

        // 高被引依赖（简化 centrality：import spec 出现频次 top10）
        val specCounts = modules.flatMap { m -> m.imports.map { it.spec } }
            .groupingBy { it }.eachCount()
        val topDeps = specCounts.entries.sortedByDescending { it.value }.take(10)
        if (topDeps.isNotEmpty()) {
            findings.add(
                ReaEvidence.Finding(
                    "top dependencies",
                    topDeps.joinToString(", ") { "${it.key}×${it.value}" }
                )
            )
        }

        // package.json 摘要
        packageJson?.let { pkg ->
            val name = pkg["name"]?.jsonPrimitive?.contentOrNull
            val version = pkg["version"]?.jsonPrimitive?.contentOrNull
            val main = pkg["main"]?.jsonPrimitive?.contentOrNull
            if (name != null) findings.add(ReaEvidence.Finding("app identity", "$name@$version entry=$main"))
            val deps = (pkg["dependencies"] as? JsonObject)?.keys?.take(20)
            if (!deps.isNullOrEmpty()) {
                findings.add(ReaEvidence.Finding("declared dependencies", deps.joinToString(", ")))
                evidence.add(ReaEvidence.Item("manifest", "package.json", "dependencies ×${deps.size}"))
            }
        }

        // 模块级证据（前 40 个模块的 import 摘要）
        for (m in modules.take(40)) {
            if (m.imports.isNotEmpty()) {
                evidence.add(
                    ReaEvidence.Item(
                        "module", m.path,
                        m.imports.take(6).joinToString(", ") { "${it.kind}:${it.spec}@L${it.line}" }
                    )
                )
            }
        }

        limitations.add("import 提取为正则匹配（非完整 AST）：多行 import 的从句可能少计；动态拼接的 require 不可见")
        limitations.add("Electron 角色为标记启发式（main/renderer 标记计数），非执行验证")
        if (modules.size >= 8000) limitations.add("模块数达上限，超出部分未计入")

        return ReaEvidence.success(
            tool = tool, target = root.absolutePath, tier = "on_device",
            findings = findings, evidence = evidence, limitations = limitations,
            digest = if (root.isFile) ReaEvidence.digestOfFile(root) else ""
        )
    }

    /** recover_javascript_sources：把可读模块恢复到目标目录（仅新目录）。 */
    fun renderRecoverSources(tool: String, source: File, output: File): JsonObject {
        if (output.exists() && output.list()?.isNotEmpty() == true) {
            return ReaEvidence.error(tool, source.absolutePath, "输出目录已存在且非空（上游契约：必须是 absent 目录）")
        }
        output.mkdirs()
        var recovered = 0
        if (source.isFile && source.name.endsWith(".asar", true)) {
            val index = parseAsarIndex(source)
                ?: return ReaEvidence.error(tool, source.absolutePath, "非法 ASAR")
            for ((path, range) in index.entries) {
                if (!path.endsWith(".js", true) && !path.endsWith(".mjs", true)) continue
                val bytes = readAsarEntry(source, index, path) ?: continue
                val target = File(output, path)
                target.parentFile?.mkdirs()
                target.writeBytes(bytes)
                recovered++
            }
        } else if (source.isDirectory) {
            source.walkTopDown().filter { it.isFile }.take(20_000).forEach { f ->
                val rel = f.relativeTo(source).invariantSeparatorsPath
                val target = File(output, rel)
                target.parentFile?.mkdirs()
                f.copyTo(target, overwrite = false)
                recovered++
            }
        }
        return ReaEvidence.success(
            tool = tool, target = source.absolutePath, tier = "on_device",
            findings = listOf(ReaEvidence.Finding("recovered", "$recovered 个文件 → ${output.absolutePath}")),
            evidence = listOf(ReaEvidence.Item("directory", output.absolutePath, "输出目录")),
            limitations = listOf("恢复的是原始字节（含 minified bundle 原样），可读性视源而定")
        )
    }

    /** compare_application_versions：两个应用图的模块级 diff。 */
    fun renderCompareVersions(tool: String, a: File, b: File): JsonObject {
        val (modsA, _) = analyzeApplication(a)
        val (modsB, _) = analyzeApplication(b)
        val pathsA = modsA.map { it.path }.toSet()
        val pathsB = modsB.map { it.path }.toSet()
        val added = pathsB - pathsA
        val removed = pathsA - pathsB
        val changedImports = modsB.filter { mb ->
            val ma = modsA.firstOrNull { it.path == mb.path }
            ma != null && ma.imports.map { it.spec } != mb.imports.map { it.spec }
        }.map { it.path }
        return ReaEvidence.success(
            tool = tool, target = "${a.name} vs ${b.name}", tier = "on_device",
            findings = listOf(
                ReaEvidence.Finding("module diff", "A=${pathsA.size} B=${pathsB.size} 新增=${added.size} 移除=${removed.size} import 变化=${changedImports.size}")
            ),
            evidence = buildList {
                added.take(30).forEach { add(ReaEvidence.Item("added", it, "")) }
                removed.take(30).forEach { add(ReaEvidence.Item("removed", it, "")) }
                changedImports.take(30).forEach { add(ReaEvidence.Item("imports-changed", it, "")) }
            },
            limitations = listOf("模块内容 hash 级对比未做（仅路径 + import 边）")
        )
    }
}
