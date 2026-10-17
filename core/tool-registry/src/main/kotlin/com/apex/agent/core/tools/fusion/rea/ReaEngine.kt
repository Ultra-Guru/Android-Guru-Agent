package com.apex.agent.core.tools.fusion.rea

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

/**
 * # REA 引擎 —— tools/call 的统一调度面
 *
 * 路由模型（与目录分层一一对应）：
 * - ON_DEVICE → 纯 Kotlin 分析器（设备直跑，旗舰深实现）；
 * - DEVICE_SHELL → **设备本地执行**（上游 adb 族在 PC 侧；我们跑在设备上，
 *   映射为本地 am/pm/dumpsys/logcat/settings/screencap —— 无需 PC 的原生增强）；
 * - SANDBOX_BRIDGE → [ReaCommandRunner] 沙箱优先执行（objdump/rizin/jadx/
 *   apktool/binwalk 等 BYO 引擎；缺失 = 结构化降级 + 安装指引）；
 * - WEB_FETCH → [ReaPageFetcher] 静态抓取 + 脚本 import 分析；
 * - PROVIDER_GATED → 桌面分析引擎/运行时 attach 门控（缺失降级给证据）。
 *
 * 每次调用产出 Evidence 信封并自动入会话账本（get_evidence_bundle 可回溯）。
 */
class ReaEngine(
    private val commandRunner: ReaCommandRunner? = null,
    private val pageFetcher: ReaPageFetcher? = null,
    private val workspace: ReaWorkspace? = null
) {
    private val json = Json { ignoreUnknownKeys = true }
    val session = ReaSession()

    /** tools/call 入口：name + arguments JSON。返回 MCP content 结构。 */
    suspend fun callTool(name: String, arguments: JsonObject): JsonObject {
        val spec = ReaToolCatalog.find(name)
            ?: return ReaEvidence.error(name, "", "未知工具: $name（可用工具见 tools/list，共 ${ReaToolCatalog.size()} 个）")
        val result = dispatch(spec, arguments)
        session.record(result)
        return result
    }

    private suspend fun dispatch(spec: ReaToolSpec, args: JsonObject): JsonObject = when (spec.tier) {
        ReaTier.ON_DEVICE -> dispatchOnDevice(spec, args)
        ReaTier.DEVICE_SHELL -> dispatchDeviceShell(spec, args)
        ReaTier.SANDBOX_BRIDGE -> dispatchSandboxBridge(spec, args)
        ReaTier.WEB_FETCH -> dispatchWebFetch(spec, args)
        ReaTier.PROVIDER_GATED -> dispatchProviderGated(spec)
    }

    // ── 参数助手 ────────────────────────────────────────────────────────

    private fun JsonObject.str(key: String): String =
        this[key]?.jsonPrimitive?.contentOrNull ?: ""

    private fun JsonObject.intOr(key: String, default: Int): Int =
        this[key]?.jsonPrimitive?.intOrNull ?: default

    private fun JsonObject.boolOr(key: String, default: Boolean): Boolean =
        this[key]?.jsonPrimitive?.booleanOrNull ?: default

    private fun targetOrPath(args: JsonObject): String {
        val p = args.str("path")
        if (p.isNotBlank()) return p
        return session.target?.file?.absolutePath ?: ""
    }

    private fun resolveFile(path: String, tool: String): Result<File> {
        if (path.isBlank()) {
            return Result.failure(Exception("缺少 path 参数且无打开目标（先 open_binary 或传 path）"))
        }
        val f = File(path)
        if (!f.exists()) return Result.failure(Exception("目标不存在: $path"))
        return Result.success(f)
    }

    // ═══ ON_DEVICE：旗舰深实现 ═════════════════════════════════════════

    private fun dispatchOnDevice(spec: ReaToolSpec, args: JsonObject): JsonObject {
        val path = targetOrPath(args)
        return try {
            when (spec.name) {
                // ── 会话与证据 ──
                "open_binary" -> {
                    val r = session.open(path)
                    if (r.isFailure) ReaEvidence.error(spec.name, path, r.exceptionOrNull()?.message ?: "")
                    else ReaEvidence.success(
                        spec.name, path, "on_device",
                        findings = listOf(
                            ReaEvidence.Finding("kind", r.getOrThrow().kind.label),
                            ReaEvidence.Finding("size", "${r.getOrThrow().sizeBytes} 字节")
                        ),
                        evidence = listOf(ReaEvidence.Item("digest", "", r.getOrThrow().digest)),
                        digest = r.getOrThrow().digest
                    )
                }
                "close_binary" -> {
                    val closed = session.close()
                    ReaEvidence.success(
                        spec.name, closed["closed"]?.jsonPrimitive?.contentOrNull ?: "", "on_device",
                        findings = listOf(ReaEvidence.Finding("closed", "会话目标已关闭（证据账本保留）"))
                    )
                }
                "binary_session" -> session.renderSession()
                "get_evidence_bundle" -> {
                    val bundle = session.evidenceBundle()
                    ReaEvidence.success(
                        spec.name, session.target?.file?.absolutePath ?: "", "on_device",
                        findings = listOf(ReaEvidence.Finding("retained evidence", "${bundle.size} 条（会话滚动账本，上限 512）")),
                        evidence = bundle.take(30).map {
                            ReaEvidence.Item(
                                "record",
                                it["tool"]?.jsonPrimitive?.contentOrNull ?: "",
                                "ok=${it["ok"]?.jsonPrimitive?.contentOrNull}"
                            )
                        }
                    )
                }
                "export_evidence_bundle" -> {
                    val out = args.str("output").ifBlank {
                        File(workspace?.ensure() ?: File(System.getProperty("java.io.tmpdir")), "rea-evidence-${System.currentTimeMillis()}.json").absolutePath
                    }
                    val r = session.exportBundle(File(out))
                    if (r.isFailure) ReaEvidence.error(spec.name, out, r.exceptionOrNull()?.message ?: "")
                    else ReaEvidence.success(
                        spec.name, out, "on_device",
                        findings = listOf(ReaEvidence.Finding("exported", "${r.getOrThrow()} 条 → $out"))
                    )
                }
                "import_evidence_bundle" -> {
                    val r = resolveFile(path, spec.name).getOrNull()
                        ?.let { session.importBundle(it) }
                        ?: return ReaEvidence.error(spec.name, path, "导入文件不存在")
                    if (r.isFailure) ReaEvidence.error(spec.name, path, r.exceptionOrNull()?.message ?: "")
                    else ReaEvidence.success(
                        spec.name, path, "on_device",
                        findings = listOf(ReaEvidence.Finding("imported", "${r.getOrThrow()} 条证据入账"))
                    )
                }
                "list_unknowns" -> {
                    val list = session.listUnknowns()
                    ReaEvidence.success(
                        spec.name, "session", "on_device",
                        findings = listOf(ReaEvidence.Finding("unknowns", "${list.size} 条（状态分布：${list.groupBy { it.status }.map { "${it.key}×${it.value.size}" }.joinToString()}）")),
                        evidence = list.take(50).map { ReaEvidence.Item(it.id, it.status, it.text.take(120)) }
                    )
                }
                "record_unknown" -> {
                    val text = args.str("query").ifBlank { args.str("text") }
                    if (text.isBlank()) ReaEvidence.error(spec.name, "", "缺少 query/text 参数")
                    else {
                        val u = session.recordUnknown(text)
                        ReaEvidence.success(spec.name, "session", "on_device",
                            findings = listOf(ReaEvidence.Finding(u.id, u.text.take(160))))
                    }
                }
                "update_unknown" -> {
                    val id = args.str("query").ifBlank { args.str("id") }
                    val status = args.str("status").ifBlank { "resolved" }
                    val u = session.updateUnknown(id, status)
                    if (u == null) ReaEvidence.error(spec.name, id, "unknown id 不存在")
                    else ReaEvidence.success(spec.name, id, "on_device",
                        findings = listOf(ReaEvidence.Finding(u.id, "status → ${u.status}")))
                }

                // ── 归档与包 ──
                "inspect_artifact", "inspect_analysis_view" -> {
                    val f = resolveFile(path, spec.name).getOrNull()
                        ?: return ReaEvidence.error(spec.name, path, "目标不存在")
                    ReaZipInspector.renderInventory(spec.name, f)
                }
                "extract_artifact" -> {
                    val f = resolveFile(path, spec.name).getOrNull()
                        ?: return ReaEvidence.error(spec.name, path, "目标不存在")
                    val out = args.str("output").ifBlank {
                        File(workspace?.ensure() ?: File(System.getProperty("java.io.tmpdir")), "rea-extract-${System.currentTimeMillis()}").absolutePath
                    }
                    ReaZipInspector.renderExtract(spec.name, f, File(out))
                }
                "compare_artifacts" -> {
                    val a = resolveFile(args.str("a").ifBlank { args.str("path") }, spec.name).getOrNull()
                    val b = resolveFile(args.str("b"), spec.name).getOrNull()
                    if (a == null || b == null) ReaEvidence.error(spec.name, "", "需要 a/b 两个归档路径")
                    else ReaZipInspector.renderCompare(spec.name, a, b)
                }

                // ── 原生二进制 ──
                "inspect_binary_layout", "binary_overview" -> {
                    val f = resolveFile(path, spec.name).getOrNull()
                        ?: return ReaEvidence.error(spec.name, path, "目标不存在")
                    ReaBinaryFormat.renderLayout(spec.name, f)
                }
                "inspect_macho", "list_architectures", "trace_dylib_resolution", "inspect_signature" -> {
                    val f = resolveFile(path, spec.name).getOrNull()
                        ?: return ReaEvidence.error(spec.name, path, "目标不存在")
                    renderMachoFamily(spec.name, f)
                }
                "read_bytes" -> {
                    val f = resolveFile(path, spec.name).getOrNull()
                        ?: return ReaEvidence.error(spec.name, path, "目标不存在")
                    val address = args.str("address")
                    val offset = parseHexOrDec(address)
                        ?: return ReaEvidence.error(spec.name, path, "非法地址: $address")
                    ReaBinaryFormat.renderReadBytes(spec.name, f, offset, args.intOr("length", 64))
                }
                "search_bytes" -> {
                    val f = resolveFile(path, spec.name).getOrNull()
                        ?: return ReaEvidence.error(spec.name, path, "目标不存在")
                    val pattern = args.str("pattern").ifBlank { args.str("query") }
                    if (pattern.isBlank()) ReaEvidence.error(spec.name, path, "缺少 pattern")
                    else ReaBinaryFormat.renderSearchBytes(spec.name, f, pattern)
                }
                "address_to_file_offset" -> {
                    val f = resolveFile(path, spec.name).getOrNull()
                        ?: return ReaEvidence.error(spec.name, path, "目标不存在")
                    ReaBinaryFormat.renderAddressToFileOffset(spec.name, f, args.str("address"))
                }
                "search", "search_strings", "search_procedures", "list_strings", "list_names" -> {
                    val f = resolveFile(path, spec.name).getOrNull()
                        ?: return ReaEvidence.error(spec.name, path, "目标不存在")
                    ReaBinaryFormat.renderSymbolScan(spec.name, f, args.str("query").ifBlank { null }, args.boolOr("regex", false))
                }

                // ── Android ──
                "inspect_android_package" -> {
                    val f = resolveFile(path, spec.name).getOrNull()
                        ?: return ReaEvidence.error(spec.name, path, "目标不存在")
                    ReaApkInspector.renderPackage(spec.name, f)
                }
                "search_android_classes", "inspect_android_class" -> {
                    val f = resolveFile(path, spec.name).getOrNull()
                        ?: return ReaEvidence.error(spec.name, path, "目标不存在")
                    ReaApkInspector.renderSearchClasses(spec.name, f, args.str("query"))
                }
                "identify_flutter_build", "inspect_dart_aot" -> {
                    val f = resolveFile(path, spec.name).getOrNull()
                        ?: return ReaEvidence.error(spec.name, path, "目标不存在")
                    val fl = ReaApkInspector.identifyFlutter(f)
                    ReaEvidence.success(
                        spec.name, f.absolutePath, "on_device",
                        findings = listOf(
                            ReaEvidence.Finding("flutter", if (fl.isFlutter) "是（ABI: ${fl.abis.joinToString("/")}）" else "否（无 libflutter/libapp）"),
                            ReaEvidence.Finding("libapp.so", if (fl.libappSize > 0) "${fl.libappSize / 1024} KB" else "缺失"),
                            ReaEvidence.Finding(
                                "dart snapshot symbols",
                                fl.snapshotSymbols.joinToString(", ").ifBlank { "未检出（快照符号缺失或非 AOT）" }
                            )
                        ),
                        evidence = fl.snapshotSymbols.take(10).map { ReaEvidence.Item("symbol", "libapp.so", it) },
                        digest = ReaEvidence.digestOfFile(f)
                    )
                }

                // ── JS / Electron ──
                "analyze_javascript_application" -> {
                    val f = resolveFile(path, spec.name).getOrNull()
                        ?: return ReaEvidence.error(spec.name, path, "目标不存在")
                    ReaAsarAnalyzer.renderAnalysis(spec.name, f)
                }
                "recover_javascript_sources" -> {
                    val f = resolveFile(path, spec.name).getOrNull()
                        ?: return ReaEvidence.error(spec.name, path, "目标不存在")
                    val out = args.str("output").ifBlank {
                        File(workspace?.ensure() ?: File(System.getProperty("java.io.tmpdir")), "rea-recovered-${System.currentTimeMillis()}").absolutePath
                    }
                    ReaAsarAnalyzer.renderRecoverSources(spec.name, f, File(out))
                }
                "compare_application_versions" -> {
                    val a = resolveFile(args.str("a").ifBlank { args.str("path") }, spec.name).getOrNull()
                    val b = resolveFile(args.str("b"), spec.name).getOrNull()
                    if (a == null || b == null) ReaEvidence.error(spec.name, "", "需要 a/b 两个应用路径（ASAR 或目录）")
                    else ReaAsarAnalyzer.renderCompareVersions(spec.name, a, b)
                }

                // ── 杂项解析器 ──
                "inspect_plist" -> {
                    val f = resolveFile(path, spec.name).getOrNull()
                        ?: return ReaEvidence.error(spec.name, path, "目标不存在")
                    ReaMiscParsers.renderPlist(spec.name, f)
                }
                "inspect_sqlite_database" -> {
                    val f = resolveFile(path, spec.name).getOrNull()
                        ?: return ReaEvidence.error(spec.name, path, "目标不存在")
                    ReaMiscParsers.renderSqlite(spec.name, f)
                }
                "demangle_swift" -> {
                    val symbols = listOf(args.str("symbols"), args.str("query"), args.str("symbol"))
                        .filter { it.isNotBlank() } + (args.str("path").takeIf { it.isNotBlank() }?.let { listOf(it) } ?: emptyList())
                    if (symbols.isEmpty()) ReaEvidence.error(spec.name, "", "缺少 symbols 参数")
                    else ReaMiscParsers.renderDemangle(spec.name, symbols)
                }
                "inspect_go_binary" -> {
                    val f = resolveFile(path, spec.name).getOrNull()
                        ?: return ReaEvidence.error(spec.name, path, "目标不存在")
                    ReaMiscParsers.renderGoBinary(spec.name, f)
                }
                "inspect_rust_binary" -> {
                    val f = resolveFile(path, spec.name).getOrNull()
                        ?: return ReaEvidence.error(spec.name, path, "目标不存在")
                    ReaMiscParsers.renderRustBinary(spec.name, f)
                }
                "inspect_evm_interface" -> {
                    val f = resolveFile(path, spec.name).getOrNull()
                        ?: return ReaEvidence.error(spec.name, path, "目标不存在")
                    ReaMiscParsers.renderEvm(spec.name, f)
                }

                // ── 记录对比 / 投影族（Evidence 记录驱动的通用面）──
                else -> renderRecordDriven(spec, args)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ReaEvidence.error(spec.name, path, "${e::class.simpleName}: ${e.message}")
        }
    }

    /** Mach-O 家族渲染（inspect_macho / list_architectures / 签名 / dylib 解析）。 */
    private fun renderMachoFamily(tool: String, file: File): JsonObject {
        val findings = mutableListOf<ReaEvidence.Finding>()
        val evidence = mutableListOf<ReaEvidence.Item>()
        val limitations = mutableListOf<String>()
        val fat = ReaBinaryFormat.parseFat(file)
        if (fat != null) {
            findings.add(ReaEvidence.Finding("format", "Mach-O universal（${fat.size} slices）"))
            fat.forEachIndexed { i, s ->
                findings.add(ReaEvidence.Finding("slice[$i]", "cpu=${s.cpuType} offset=0x${s.offset.toString(16)} size=${s.size}"))
            }
        }
        val macho = ReaBinaryFormat.parseMacho(file)
        if (macho != null) {
            findings.add(ReaEvidence.Finding("filetype", "${macho.magic} ${macho.fileType}"))
            findings.add(ReaEvidence.Finding("segments", macho.segments.joinToString(", ")))
            findings.add(ReaEvidence.Finding("load commands", "${macho.commands.size} 个"))
            if (macho.dylibs.isNotEmpty()) {
                findings.add(ReaEvidence.Finding("linked dylibs", macho.dylibs.joinToString(", ")))
                evidence.add(ReaEvidence.Item("load-commands", "LC_LOAD_DYLIB", "×${macho.dylibs}"))
            }
            // LC_CODE_SIGNATURE (0x1D) 存在性
            val hasCodeSig = macho.commands.any { it.cmd == 0x1DL }
            findings.add(
                ReaEvidence.Finding(
                    "code signature",
                    if (hasCodeSig) "LC_CODE_SIGNATURE 存在（身份/证书链深析待后续）" else "无 LC_CODE_SIGNATURE"
                )
            )
            if (hasCodeSig) limitations.add("签名为 load command 存在性级（CMS 证书链解析待后续）")
        } else if (fat == null) {
            limitations.add("非 Mach-O（或头不完整）")
        }
        return ReaEvidence.success(
            tool, file.absolutePath, "on_device", findings, evidence, limitations,
            digest = ReaEvidence.digestOfFile(file)
        )
    }

    /**
     * 记录驱动族：compare_* / project_* / 对账 / 覆盖率 —— 输入是既有
     * Evidence 记录（会话内或路径），做结构化 diff / 投影 / 汇总。
     */
    private fun renderRecordDriven(spec: ReaToolSpec, args: JsonObject): JsonObject {
        val pathA = args.str("a").ifBlank { args.str("path") }
        val pathB = args.str("b")
        return when {
            spec.name.startsWith("compare_") && pathA.isNotBlank() && pathB.isNotBlank() -> {
                val a = loadRecord(pathA)
                val b = loadRecord(pathB)
                if (a == null || b == null) {
                    ReaEvidence.error(spec.name, "$pathA vs $pathB", "两侧记录需为合法 Evidence JSON")
                } else {
                    val (changed, added, removed) = diffRecords(a, b)
                    ReaEvidence.success(
                        spec.name, "$pathA vs $pathB", "on_device",
                        findings = listOf(
                            ReaEvidence.Finding("record diff", "changed=$changed added=${added.size} removed=${removed.size}")
                        ),
                        evidence = (added.take(20).map { ReaEvidence.Item("added", it, "") } +
                            removed.take(20).map { ReaEvidence.Item("removed", it, "") }),
                        limitations = listOf("对比为键级结构 diff（域语义加权对比待后续）")
                    )
                }
            }
            spec.name.startsWith("project_") && pathA.isNotBlank() -> {
                val record = loadRecord(pathA)
                if (record == null) {
                    ReaEvidence.error(spec.name, pathA, "输入需为 inspect_artifact 的 Evidence JSON")
                } else {
                    val nodes = (record["evidence"] as? kotlinx.serialization.json.JsonArray)
                        ?.filterIsInstance<JsonObject>()
                        ?.mapNotNull { it["at"]?.jsonPrimitive?.contentOrNull }
                        ?.filter { it.isNotBlank() }
                        ?.toList() ?: emptyList()
                    val grouped = nodes.groupBy { nodeGroup(it) }
                    ReaEvidence.success(
                        spec.name, pathA, "on_device",
                        findings = listOf(
                            ReaEvidence.Finding("graph nodes", "${nodes.size} 个构件节点"),
                            ReaEvidence.Finding(
                                "node families",
                                grouped.entries.joinToString(", ") { "${it.key}×${it.value.size}" }
                            )
                        ),
                        evidence = grouped.entries.take(12).map { ReaEvidence.Item("family", it.key, "×${it.value.size}") },
                        limitations = listOf("投影为条目级无执行图（依赖边深析待后续）")
                    )
                }
            }
            else -> {
                // 未特化的 ON_DEVICE 记录族（对账/覆盖率/账本）：基于会话证据汇总
                val bundle = session.evidenceBundle()
                ReaEvidence.success(
                    spec.name, session.target?.file?.absolutePath ?: "session", "on_device",
                    findings = listOf(
                        ReaEvidence.Finding("basis", "基于会话 ${bundle.size} 条保留证据的结构化汇总"),
                        ReaEvidence.Finding(
                            "coverage",
                            "ok=${bundle.count { it["ok"]?.jsonPrimitive?.contentOrNull == "true" }} " +
                                "degraded=${bundle.count { it["ok"]?.jsonPrimitive?.contentOrNull == "false" }}"
                        )
                    ),
                    limitations = listOf("本族 v1 为会话证据汇总级（确定性账本展开待后续）")
                )
            }
        }
    }

    private fun nodeGroup(at: String): String = when {
        at.endsWith(".dex") -> "dex"
        at.startsWith("lib/") -> "native-lib"
        at.endsWith(".so") || at.endsWith(".dylib") -> "native-lib"
        at.endsWith(".js") || at.endsWith(".mjs") -> "js-module"
        at.endsWith(".plist") -> "plist"
        at == "AndroidManifest.xml" -> "manifest"
        at.endsWith(".json") -> "json"
        else -> "resource"
    }

    private fun loadRecord(path: String): JsonObject? {
        val f = File(path)
        if (!f.isFile) return null
        return runCatching { json.parseToJsonElement(f.readText()).jsonObject }.getOrNull()
    }

    private fun diffRecords(a: JsonObject, b: JsonObject): Triple<Int, List<String>, List<String>> {
        val keysA = a.keys
        val keysB = b.keys
        val added = keysB - keysA
        val removed = keysA - keysB
        var changed = 0
        for (k in keysA.intersect(keysB)) {
            if (a[k].toString() != b[k].toString()) changed++
        }
        return Triple(changed, added.toList(), removed.toList())
    }

    // ═══ DEVICE_SHELL：设备本地执行（adb 族的原生增强）══════════════════

    private suspend fun dispatchDeviceShell(spec: ReaToolSpec, args: JsonObject): JsonObject {
        val runner = commandRunner
            ?: return ReaEvidence.degraded(
                spec.name, "local-device", "device_shell",
                "命令执行端口未接线（FusionBundle 宿主未注入）",
                "在 app 层 DI 注入 ReaCommandRunner（PRoot 终端执行链）"
            )
        val cmd = adbFamilyCommand(spec.name, args)
            ?: return ReaEvidence.degraded(
                spec.name, "local-device", "device_shell",
                "工具 ${spec.name} 的本地命令模板未映射",
                "该工具契约保留在目录中（207 面对齐）；命令模板待补充"
            )
        val r = runner.run(cmd.toList(), 30_000)
        return r.fold(
            onSuccess = { out ->
                ReaEvidence.success(
                    spec.name, "local-device", "device_shell",
                    findings = listOf(
                        ReaEvidence.Finding("command", cmd.joinToString(" ")),
                        ReaEvidence.Finding("exit", "${out.exitCode}（${out.channel}）"),
                        ReaEvidence.Finding("stdout", out.stdout.take(4000))
                    ),
                    evidence = listOf(
                        ReaEvidence.Item("command", "", cmd.joinToString(" ")),
                        ReaEvidence.Item("output", "stdout", "${out.stdout.length} 字符")
                    ),
                    limitations = if (out.stderr.isNotBlank()) listOf("stderr: ${out.stderr.take(500)}") else emptyList()
                )
            },
            onFailure = {
                ReaEvidence.degraded(
                    spec.name, "local-device", "device_shell",
                    "本地执行失败: ${it.message}",
                    "检查设备 shell 权限链（Root/Shizuku/普通 shell 三级）"
                )
            }
        )
    }

    /** adb 族 → 设备本地命令模板（无需 PC —— 这正是设备端原生化优势）。 */
    internal fun adbFamilyCommand(name: String, args: JsonObject): List<String>? {
        val shell = mutableListOf("sh", "-c")
        val inner: String = when (name) {
            "list_adb_devices" -> return listOf("sh", "-c", "echo 'local-device (on-device execution: no adb host needed)'")
            "inspect_adb_client" -> return listOf("sh", "-c", "echo 'on-device mode: commands run through local privilege chain'")
            "list_adb_packages" -> if (args.boolOr("third_party_only", false)) "pm list packages -3" else "pm list packages"
            "resolve_adb_packages" -> "pm list packages | grep -i '${args.str("query").replace("'", "")}'"
            "inspect_adb_package" -> "dumpsys package ${args.str("package")}"
            "inspect_adb_device" -> "getprop ro.build.version.release; getprop ro.product.model; getprop ro.build.version.sdk"
            "list_adb_processes" -> "ps -A"
            "list_adb_features" -> "pm list features"
            "list_adb_services" -> "service list"
            "inspect_adb_display" -> "wm size; wm density"
            "inspect_adb_window" -> "dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'"
            "read_adb_logcat" -> {
                val buffer = args.str("buffer").ifBlank { "main" }
                val lines = args.intOr("lines", 200)
                "logcat -d -t $lines -b $buffer"
            }
            "read_adb_setting" -> "settings get ${args.str("namespace")} ${args.str("key")}"
            "list_adb_directory" -> "ls -la ${args.str("device_path")}"
            "capture_adb_screen" -> "screencap -p /sdcard/.rea-screen.png && ls -la /sdcard/.rea-screen.png"
            "start_adb_app" -> "monkey -p ${args.str("package")} -c android.intent.category.LAUNCHER 1"
            "start_adb_activity" -> buildString {
                append("am start -W ")
                append(args.str("action"))
                val uri = args.str("uri")
                if (uri.isNotBlank()) append(" -d '$uri'")
                val comp = args.str("component")
                if (comp.isNotBlank()) append(" -n $comp")
            }
            "stop_adb_app" -> "am force-stop ${args.str("package")}"
            "uninstall_adb_package" -> "pm uninstall ${args.str("package")}"
            "install_adb_package" -> "pm install ${args.str("path")}"
            "pull_adb_file", "push_adb_file", "pull_adb_package", "collect_adb_bugreport" ->
                return listOf("sh", "-c", "echo 'on-device: ${'$'}name 直接用 cp/cat 完成 —— 路径参数: ${args.str("device_path").ifBlank { args.str("path") }}'")
            "capture_process_scenario" -> {
                val command = args.str("path").ifBlank { args.str("command") }
                if (command.isBlank()) return null
                return listOf("sh", "-c", command)
            }
            else -> return null
        }
        shell.add(inner)
        return shell
    }

    // ═══ SANDBOX_BRIDGE：PRoot Ubuntu 引擎桥（BYO 模型）═════════════════

    /** 引擎名 → 工具族的安装指引。 */
    private val engineInstallHints = mapOf(
        "objdump" to "PRoot Ubuntu 终端: apt install binutils",
        "rizin" to "PRoot Ubuntu 终端: 安装 rizin（rizin.re 官方脚本或发行包）",
        "gdb" to "PRoot Ubuntu 终端: apt install gdb",
        "frida" to "PRoot Ubuntu 终端: pip install frida-tools",
        "jadx" to "PRoot Ubuntu 终端: 下载 jadx 发行包 + apt install openjdk-17-jre-headless",
        "apktool" to "PRoot Ubuntu 终端: 下载 apktool 发行包",
        "tshark" to "PRoot Ubuntu 终端: apt install tshark",
        "binwalk" to "PRoot Ubuntu 终端: pip install binwalk",
        "unblob" to "PRoot Ubuntu 终端: pip install unblob",
        "sigrok" to "PRoot Ubuntu 终端: apt install sigrok",
        "jeb" to "JEB 为商业引擎（BYO 许可证）",
        "cutter" to "Cutter 为桌面 GUI 引擎（Rizin 桥的 GUI 形态）",
        "wabt" to "PRoot Ubuntu 终端: 安装 wabt（wasm2wat）"
    )

    private fun engineFor(tool: String): String = when {
        tool.startsWith("inspect_with_objdump") -> "objdump"
        tool.contains("rizin") || tool.contains("cutter") -> "rizin"
        tool.contains("gdb") -> "gdb"
        tool.contains("frida") -> "frida"
        tool.contains("jeb") -> "jeb"
        tool.contains("apktool") || tool == "decode_android_resources" -> "apktool"
        tool.contains("packet") || tool.contains("pcap") -> "tshark"
        tool.contains("firmware") -> "binwalk"
        tool.contains("signal") || tool.contains("sigrok") -> "sigrok"
        tool.contains("wasm") -> "wabt"
        tool.contains("android_method") || tool.contains("android_references") -> "jadx"
        else -> "engine"
    }

    private suspend fun dispatchSandboxBridge(spec: ReaToolSpec, args: JsonObject): JsonObject {
        val runner = commandRunner
            ?: return ReaEvidence.degraded(
                spec.name, targetOrPath(args), "sandbox_bridge",
                "命令执行端口未接线",
                "app 层 DI 注入 ReaCommandRunner（PRoot 终端执行链）"
            )
        val engine = engineFor(spec.name)
        // 探针：引擎在 PATH 才跑真命令；否则结构化降级（BYO 模型）
        val probe = runner.run(listOf("sh", "-c", "command -v $engine"), 10_000)
        val available = probe.getOrNull()?.isOk() == true
        if (!available) {
            return ReaEvidence.degraded(
                spec.name, targetOrPath(args), "sandbox_bridge",
                "引擎 $engine 未安装（BYO 模型：本族工具需要该引擎就位）",
                engineInstallHints[engine] ?: "PRoot Ubuntu 终端内安装 $engine 后重试"
            )
        }
        // 真执行：透传 path/query（引擎子命令形态保守直拼，带引号防护）
        val path = targetOrPath(args)
        val command = listOf("sh", "-c", buildString {
            append(engine)
            append(" ")
            append(when {
                path.isNotBlank() -> "'${path.replace("'", "")}'"
                else -> "--help"
            })
        })
        val r = runner.run(command, 60_000)
        return r.fold(
            onSuccess = { out ->
                ReaEvidence.success(
                    spec.name, path, "sandbox_bridge",
                    findings = listOf(
                        ReaEvidence.Finding("engine", "$engine（${out.channel}）"),
                        ReaEvidence.Finding("exit", "${out.exitCode}"),
                        ReaEvidence.Finding("output", (out.stdout + out.stderr).take(4000))
                    ),
                    evidence = listOf(ReaEvidence.Item("command", "", command.joinToString(" ")))
                )
            },
            onFailure = {
                ReaEvidence.degraded(
                    spec.name, path, "sandbox_bridge",
                    "引擎执行失败: ${it.message}",
                    "检查 $engine 安装与参数形态"
                )
            }
        )
    }

    // ═══ WEB_FETCH：静态抓取 + 脚本分析 ═════════════════════════════════

    private suspend fun dispatchWebFetch(spec: ReaToolSpec, args: JsonObject): JsonObject {
        val fetcher = pageFetcher
            ?: return ReaEvidence.degraded(
                spec.name, args.str("url"), "web_fetch",
                "页面抓取端口未接线",
                "app 层 DI 注入 ReaPageFetcher（OkHttp）"
            )
        val url = args.str("url").ifBlank { args.str("path") }
        if (url.isBlank() || !url.startsWith("http")) {
            return ReaEvidence.error(spec.name, url, "缺少合法 url（http/https）")
        }
        val r = fetcher.fetch(url, 20_000)
        return r.fold(
            onSuccess = { page ->
                val scripts = extractScriptSources(page.body)
                val findings = mutableListOf(
                    ReaEvidence.Finding("status", "${page.status}（${page.contentType}）"),
                    ReaEvidence.Finding("body size", "${page.body.length} 字符"),
                    ReaEvidence.Finding(
                        "scripts",
                        "${scripts.size} 个引用（前 20：${scripts.take(20).joinToString(", ")}）"
                    )
                )
                if (spec.name == "analyze_web_bundle") {
                    // 静态 bundle 分析：对内联 script 做 import 提取
                    val inline = extractInlineScripts(page.body)
                    val imports = inline.flatMap { ReaAsarAnalyzer.extractImports(it) }
                    findings.add(ReaEvidence.Finding("inline scripts", "${inline.size} 段"))
                    findings.add(ReaEvidence.Finding("import refs", "${imports.size} 条（前 30：${imports.take(30).joinToString(", ") { it.spec }}）"))
                }
                if (spec.name == "discover_webmcp_tools") {
                    val webmcp = Regex("""webmcp""", RegexOption.IGNORE_CASE).findAll(page.body).count()
                    findings.add(ReaEvidence.Finding("webmcp markers", "$webmcp 处"))
                }
                ReaEvidence.success(
                    spec.name, url, "web_fetch",
                    findings,
                    evidence = listOf(
                        ReaEvidence.Item("fetch", url, "sha256:${page.bodySha256.take(16)}")
                    ) + scripts.take(20).map { ReaEvidence.Item("script-src", it, "") },
                    limitations = listOf("静态抓取（无 CDP 执行态：DOM 渲染后内容与运行时网络不可见）")
                )
            },
            onFailure = {
                ReaEvidence.error(spec.name, url, "抓取失败: ${it.message}")
            }
        )
    }

    private fun extractScriptSources(html: String): List<String> =
        Regex("""<script[^>]+src=["']([^"']+)["']""").findAll(html).map { it.groupValues[1] }.toList()

    private fun extractInlineScripts(html: String): List<String> =
        Regex("""<script(?![^>]*src)[^>]*>([\s\S]*?)</script>""").findAll(html)
            .map { it.groupValues[1] }.filter { it.isNotBlank() }.toList()

    // ═══ PROVIDER_GATED：供应商门控（桌面分析引擎 / 运行时 attach）═════

    private fun dispatchProviderGated(spec: ReaToolSpec): JsonObject = ReaEvidence.degraded(
        spec.name, session.target?.file?.absolutePath ?: "", "provider_gated",
        when {
            spec.domain == ReaDomain.HOPPER -> "本族工具需要桌面原生分析引擎（Hopper/Ghidra/IDA）桥接"
            spec.name.contains("runtime") || spec.name.contains("electron") -> "本工具需要 V8 Inspector / CDP 运行时端点"
            spec.name.startsWith("capture_") || spec.name.startsWith("observe_web") -> "本工具需要 CDP 浏览器场景引擎"
            else -> "本工具需要外部供应商（BYO 引擎模型）"
        },
        "可用替代路径：inspect_binary_layout / search / read_bytes 等纯设备工具已覆盖静态面；" +
            "动态场景经内置浏览器自动化生态桥接（roadmap）"
    )

    private fun parseHexOrDec(s: String): Long? {
        if (s.isBlank()) return null
        return s.removePrefix("0x").removePrefix("0X").toLongOrNull(16) ?: s.toLongOrNull()
    }

    companion object {
        /** 引擎单例工厂（app 层 McpModule 每连接构造）。 */
        fun create(
            commandRunner: ReaCommandRunner? = null,
            pageFetcher: ReaPageFetcher? = null,
            workspace: ReaWorkspace? = null
        ): ReaEngine = ReaEngine(commandRunner, pageFetcher, workspace)
    }
}
