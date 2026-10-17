package com.apex.agent.core.tools.fusion.rea

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.RandomAccessFile

/**
 * # REA 目标会话（open_binary / binary_session / 证据包）
 *
 * 上游会话模型的设备端等价物：`open_binary` 打开本地目标 → 识别类型 +
 * 摘要 + 尺寸；会话持有目标与**保留证据**（每条工具结果都是一条 Evidence
 * 记录）→ `get_evidence_bundle` / `export_evidence_bundle` 取全量 /
 * 原子导出，`import_evidence_bundle` 校验导入。`close_binary` 关目标
 * （可选落快照）。
 *
 * 证据记录：`(id, tool, target, ok, tier, createdAtMs)` + 原始 JSON ——
 * 追加式、会话内保留（上限 512 条滚动）。
 */
class ReaSession {

    /** 当前打开的目标（null = 无目标）。 */
    data class Target(
        val file: File,
        val kind: ReaBinaryFormat.Kind,
        val digest: String,
        val sizeBytes: Long,
        val openedAtMs: Long
    )

    @Volatile
    var target: Target? = null
        private set

    /** 保留证据（滚动 512 条）。 */
    private val records = ArrayDeque<JsonObject>(512)

    /** 残留 unknown 台账（list_unknowns / record_unknown 族底座）。 */
    data class Unknown(
        val id: String,
        val text: String,
        val status: String,
        val recordedAtMs: Long
    )

    private val unknowns = mutableListOf<Unknown>()
    private var unknownSeq = 0

    /** open_binary：打开并识别目标（目录目标也放行 —— JS 应用分析用）。 */
    fun open(path: String): Result<Target> {
        val file = File(path)
        if (!file.exists()) {
            return Result.failure(Exception("目标不存在: $path"))
        }
        val kind = ReaBinaryFormat.detectKind(file)
        val digest = if (file.isFile) ReaEvidence.digestOfFile(file) else ""
        val t = Target(
            file = file,
            kind = kind,
            digest = digest,
            sizeBytes = if (file.isFile) file.length() else -1L,
            openedAtMs = System.currentTimeMillis()
        )
        target = t
        return Result.success(t)
    }

    /** close_binary：关目标（证据保留在会话，快照参数当前为兼容位）。 */
    fun close(): JsonObject = buildJsonObject {
        put("closed", target?.file?.absolutePath ?: "")
        target = null
    }

    fun requireTarget(): Result<Target> {
        val t = target ?: return Result.failure(
            Exception("无打开目标 —— 先 open_binary（或直接传 path 参数）")
        )
        return Result.success(t)
    }

    /** 记录一条 Evidence（每次工具调用的结果自动入账）。 */
    fun record(evidence: JsonObject) {
        if (records.size >= 512) records.removeFirst()
        records.addLast(evidence)
    }

    /** 全量证据包。 */
    fun evidenceBundle(): List<JsonObject> = records.toList()

    /** 导出证据包（原子写）。 */
    fun exportBundle(output: File): Result<Int> {
        return try {
            val bundle = buildJsonObject {
                put("exportedAtMs", System.currentTimeMillis())
                put("count", records.size)
                put("records", kotlinx.serialization.json.buildJsonArray {
                    records.forEach { add(it) }
                })
            }
            com.apex.agent.core.tools.builtin.AtomicFileWrite.writeText(
                output, bundle.toString()
            )
            Result.success(records.size)
        } catch (e: Exception) {
            Result.failure(Exception("导出失败: ${e.message}"))
        }
    }

    /** 导入证据包（校验 JSON 结构；返回导入条数）。 */
    fun importBundle(input: File): Result<Int> {
        return try {
            val root = Json.parseToJsonElement(input.readText())
            val recs = root as? JsonObject
                ?: return Result.failure(Exception("顶层必须是 JSON 对象"))
            val arr = recs["records"] as? kotlinx.serialization.json.JsonArray
                ?: return Result.failure(Exception("缺少 records 数组"))
            var imported = 0
            for (item in arr) {
                if (item is JsonObject) {
                    if (records.size >= 512) records.removeFirst()
                    records.addLast(item)
                    imported++
                }
            }
            Result.success(imported)
        } catch (e: Exception) {
            Result.failure(Exception("导入失败（非法 JSON）: ${e.message}"))
        }
    }

    // ── unknowns 台账 ──────────────────────────────────────────────────

    fun listUnknowns(): List<Unknown> = unknowns.toList()

    fun recordUnknown(text: String): Unknown {
        val u = Unknown(
            id = "U${"%03d".format(++unknownSeq)}",
            text = text,
            status = "open",
            recordedAtMs = System.currentTimeMillis()
        )
        unknowns.add(u)
        return u
    }

    fun updateUnknown(id: String, status: String): Unknown? {
        val idx = unknowns.indexOfFirst { it.id == id }
        if (idx < 0) return null
        val updated = unknowns[idx].copy(status = status)
        unknowns[idx] = updated
        return updated
    }

    // ── 渲染 ────────────────────────────────────────────────────────────

    fun renderSession(): JsonObject {
        val t = target
        return buildJsonObject {
            put("hasTarget", t != null)
            if (t != null) {
                put("target", t.file.absolutePath)
                put("kind", t.kind.label)
                put("sizeBytes", t.sizeBytes)
                put("digest", t.digest)
                put("openedAtMs", t.openedAtMs)
            }
            put("evidenceCount", records.size)
            put("unknowns", unknowns.size)
            put("catalogTools", ReaToolCatalog.size())
            put(
                "tierCounts",
                buildJsonObject {
                    ReaToolCatalog.tierCounts().forEach { (tier, count) -> put(tier.name, count) }
                }
            )
        }
    }
}

/**
 * # REA 杂项小解析器（plist / sqlite / swift demangle / go / rust / evm）
 *
 * 单文件小件集合：每个都对齐一个上游纯解析工具的核心输出面，深析受
 * 格式复杂度约束时以 limitation 如实声明（证据哲学不变）。
 */
object ReaMiscParsers {

    /** inspect_plist：XML plist（键值对与顶层类型）+ 二进制 plist 识别。 */
    fun renderPlist(tool: String, file: File): JsonObject {
        if (!file.isFile) return ReaEvidence.error(tool, file.absolutePath, "文件不存在")
        val head = file.inputStream().use { it.readNBytes(8) }
        val findings = mutableListOf<ReaEvidence.Finding>()
        val evidence = mutableListOf<ReaEvidence.Item>()
        val limitations = mutableListOf<String>()
        if (head.size >= 6 && head[0] == 'b'.code.toByte()) {
            findings.add(ReaEvidence.Finding("format", "binary plist（bplist00）"))
            limitations.add("二进制 plist 键值提取待后续（当前识别 + 尺寸事实）")
            findings.add(ReaEvidence.Finding("size", "${file.length()} 字节"))
            evidence.add(ReaEvidence.Item("bytes", "0x0", "bplist00 魔数"))
        } else {
            val text = file.readText()
            if (!text.contains("<plist") && !text.contains("<?xml")) {
                return ReaEvidence.error(tool, file.absolutePath, "非 plist 文件")
            }
            findings.add(ReaEvidence.Finding("format", "XML plist"))
            // 顶层键提取（浅层 regex —— 深层结构走 XML 解析器）
            val keys = Regex("""<key>([^<]+)</key>""").findAll(text).map { it.groupValues[1] }.toList()
            findings.add(ReaEvidence.Finding("top keys", "${keys.size} 个（前 30：${keys.take(30).joinToString(", ")}）"))
            val version = Regex("""<string>([\d.]+)</string>""").find(text)?.groupValues?.get(1)
            if (version != null) findings.add(ReaEvidence.Finding("first version-like value", version))
            evidence.add(ReaEvidence.Item("text", file.name, "${text.length} 字符"))
            limitations.add("键值提取为浅层正则（嵌套数组/字典结构未展开）")
        }
        return ReaEvidence.success(
            tool, file.absolutePath, "on_device",
            findings, evidence, limitations, digest = ReaEvidence.digestOfFile(file)
        )
    }

    /** inspect_sqlite_database：头页事实 + sqlite_master 可读时的表清单。 */
    fun renderSqlite(tool: String, file: File): JsonObject {
        if (!file.isFile) return ReaEvidence.error(tool, file.absolutePath, "文件不存在")
        RandomAccessFile(file, "r").use { f ->
            val magic = ByteArray(16)
            f.readFully(magic)
            val magicStr = String(magic, 0, 15, Charsets.US_ASCII)
            if (!magicStr.startsWith("SQLite format 3")) {
                return ReaEvidence.error(tool, file.absolutePath, "非 SQLite 数据库（魔数不符）")
            }
            f.seek(16)
            // 头页 page size 为大端 u16（SQLite 规范），1 = 65536
            val pageSizeRaw = (f.readUnsignedByte() shl 8) or f.readUnsignedByte()
            val pageSize = when {
                pageSizeRaw == 1 -> 65_536
                pageSizeRaw in 512..65_536 -> pageSizeRaw
                else -> 0 // 损坏头/非标准值 —— 走限制声明而非除零
            }
            val findings = mutableListOf(
                ReaEvidence.Finding("format", "SQLite 3"),
                ReaEvidence.Finding(
                    "page size",
                    if (pageSize > 0) pageSize.toString() else "非标准（$pageSizeRaw）"
                )
            )
            if (pageSize > 0) {
                findings.add(ReaEvidence.Finding("file size", "${file.length()} 字节 ≈ ${file.length() / pageSize} 页"))
            }
            val limitations = mutableListOf<String>()
            if (pageSize <= 0) limitations.add("页大小字段非标准（$pageSizeRaw），页级计算跳过")
            limitations.add("表清单经 sqlite_master b-tree 遍历待后续（当前为头页级事实）")
            return ReaEvidence.success(
                tool, file.absolutePath, "on_device",
                findings,
                listOf(ReaEvidence.Item("bytes", "0x0", "SQLite format 3 魔数"), ReaEvidence.Item("bytes", "0x10", "页大小字段")),
                limitations,
                digest = ReaEvidence.digestOfFile(file)
            )
        }
    }

    /** demangle_swift：常见 _Tt 形态子集（长度段 + 名称段级联）。 */
    fun demangleSwift(symbol: String): String {
        if (!symbol.startsWith("_Tt")) return symbol
        var s = symbol.removePrefix("_Tt")
        val parts = mutableListOf<String>()
        // 数字前缀长度段（Swift 标准 mangling：长度 + 名称）
        while (s.isNotEmpty()) {
            val digits = s.takeWhile { it.isDigit() }
            if (digits.isEmpty()) {
                // 类型标记（C/S/O/P/F/v/f…）跳过一位继续
                s = s.drop(1)
                continue
            }
            val len = digits.toIntOrNull() ?: break
            s = s.drop(digits.length)
            if (s.length < len) break
            parts.add(s.take(len))
            s = s.drop(len)
        }
        val joined = parts.joinToString(".")
        return if (joined.isBlank()) symbol else joined
    }

    fun renderDemangle(tool: String, symbols: List<String>): JsonObject {
        val results = symbols.map { ReaEvidence.Item("symbol", it, demangleSwift(it)) }
        return ReaEvidence.success(
            tool, "inline", "on_device",
            findings = listOf(ReaEvidence.Finding("demangled", "${symbols.size} 个符号（常见 _Tt 子集）")),
            evidence = results,
            limitations = listOf("非完整 Swift mangler（泛型/闭包/嵌套类型复杂形态原样返回）")
        )
    }

    /** inspect_go_binary：buildinfo 字符串扫描子集。 */
    fun renderGoBinary(tool: String, file: File): JsonObject {
        val strings = ReaBinaryFormat.extractStrings(file, minLen = 8, maxCount = 300_000)
        val goVersion = strings.firstOrNull { it.startsWith("go1.") }
        val buildPath = strings.firstOrNull { it.contains("/usr/local/go/src") || it.contains("/go/src") }
        val moduleInfo = strings.firstOrNull { it.startsWith("mod\t") || it.startsWith("path\t") }
        val findings = mutableListOf<ReaEvidence.Finding>()
        val limitations = mutableListOf<String>()
        if (goVersion != null) findings.add(ReaEvidence.Finding("go version", goVersion))
        if (buildPath != null) findings.add(ReaEvidence.Finding("build path evidence", buildPath.take(120)))
        if (moduleInfo != null) findings.add(ReaEvidence.Finding("module info", moduleInfo.take(120)))
        if (findings.isEmpty()) {
            findings.add(ReaEvidence.Finding("result", "未发现 Go buildinfo 标记（可能非 Go 二进制或已剥离）"))
        }
        limitations.add("buildinfo 为字符串扫描（非 debug/buildinfo 段结构化解析）")
        return ReaEvidence.success(
            tool, file.absolutePath, "on_device", findings,
            listOfNotNull(goVersion?.let { ReaEvidence.Item("string", "", it) }),
            limitations, digest = ReaEvidence.digestOfFile(file)
        )
    }

    /** inspect_rust_binary：cargo-auditable 记录扫描子集。 */
    fun renderRustBinary(tool: String, file: File): JsonObject {
        val strings = ReaBinaryFormat.extractStrings(file, minLen = 8, maxCount = 300_000)
        // cargo auditable JSON 嵌在 .cargo-auditable 段或版本串中
        val jsonLike = strings.firstOrNull { it.contains("\"packages\"") && it.contains("\"name\"") }
        val rustc = strings.firstOrNull { it.startsWith("rustc/") || it.contains("rustc 1.") }
        val findings = mutableListOf<ReaEvidence.Finding>()
        if (jsonLike != null) findings.add(ReaEvidence.Finding("cargo-auditable", jsonLike.take(200)))
        if (rustc != null) findings.add(ReaEvidence.Finding("rustc", rustc.take(80)))
        if (findings.isEmpty()) {
            findings.add(ReaEvidence.Finding("result", "未发现 cargo-auditable 记录（未嵌入或非 Rust 构建）"))
        }
        return ReaEvidence.success(
            tool, file.absolutePath, "on_device", findings,
            listOfNotNull(rustc?.let { ReaEvidence.Item("string", "", it) }),
            listOf("依赖清单为字符串扫描子集（完整 crates 图需 cargo-auditable 段解析）"),
            digest = ReaEvidence.digestOfFile(file)
        )
    }

    /** inspect_evm_interface：4byte 选择器 + opcode 清单子集。 */
    fun renderEvm(tool: String, file: File): JsonObject {
        if (!file.isFile) return ReaEvidence.error(tool, file.absolutePath, "文件不存在")
        val bytes = file.readBytes()
        if (bytes.size > 8L * 1024 * 1024) {
            return ReaEvidence.error(tool, file.absolutePath, "字节码超 8MB 上限")
        }
        // PUSH4 操作码（0x63）+ 常见 ABI 选择器提取：0x63 <4bytes>
        val selectors = mutableSetOf<String>()
        for (i in 0..bytes.size - 5) {
            if (bytes[i].toInt() == 0x63) {
                selectors.add(bytes.copyOfRange(i + 1, i + 5).joinToString("") { "%02x".format(it) })
            }
        }
        val opcodeCounts = IntArray(256)
        var i = 0
        while (i < bytes.size) {
            val op = bytes[i].toInt() and 0xFF
            opcodeCounts[op]++
            // 跳过 PUSH1..PUSH32 的数据字节
            if (op in 0x60..0x7F) i += (op - 0x5F)
            i++
        }
        val interesting = listOf(0xF1 to "CALL", 0xF2 to "CALLCODE", 0xF4 to "DELEGATECALL", 0xFA to "STATICCALL", 0x73 to "CREATE2", 0x30 to "ADDRESS")
        val interfaceFindings = interesting.mapNotNull { (op, name) ->
            if (opcodeCounts[op] > 0) ReaEvidence.Finding("opcode $name", "×${opcodeCounts[op]}") else null
        }
        val findings = mutableListOf(
            ReaEvidence.Finding("size", "${bytes.size} 字节字节码"),
            ReaEvidence.Finding("push4 selectors (函数签名候选)", "${selectors.size} 个（前 20：${selectors.take(20).joinToString(" ")}）")
        )
        findings.addAll(interfaceFindings)
        return ReaEvidence.success(
            tool, file.absolutePath, "on_device", findings,
            selectors.take(50).map { ReaEvidence.Item("selector", "0x$it", "") },
            listOf("选择子为 PUSH4 启发式（非 ABI 签名注册表解码）"),
            digest = ReaEvidence.digestOfFile(file)
        )
    }
}
