package com.apex.agent.core.tools.fusion.rea

import kotlinx.serialization.json.JsonObject
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipFile

/**
 * # REA Android APK 检查器（AXML manifest / dex 类清单 / Flutter 识别）
 *
 * 纯 Kotlin 解析 APK 三大静态面（对齐上游 inspect_android_package /
 * search_android_classes / identify_flutter_build 的核心输出）：
 *
 * 1. **AXML**（编译期二进制 AndroidManifest.xml）：字符串池 + START_ELEMENT
 *    chunk 解析 → package / versionName / versionCode / 权限 / 四大组件 /
 *    minSdk / targetSdk / debuggable；
 * 2. **dex 类清单**：string_ids 全量枚举 + `L…;` 形态过滤 → 类名清单
 *    （search_android_classes 的 substring 检索底座）；
 * 3. **Flutter 识别**：lib/<abi>/libflutter.so + libapp.so 存在性 +
 *    ELF 符号中的 Dart 快照四件套（_kDartVmSnapshotData 等）。
 */
object ReaApkInspector {

    // ── AXML 解析 ───────────────────────────────────────────────────────

    /** AXML 解析结果（manifest 关键字段 + 组件清单）。 */
    data class AxmlManifest(
        val packageName: String,
        val versionName: String,
        val versionCode: Long,
        val minSdk: Int?,
        val targetSdk: Int?,
        val debuggable: Boolean,
        val permissions: List<String>,
        val activities: List<String>,
        val services: List<String>,
        val receivers: List<String>,
        val providers: List<String>,
        val exportedComponents: List<String>,
        val applicationLabel: String
    )

    private const val CHUNK_AXML_HEADER = 0x00080003
    private const val CHUNK_STRING_POOL = 0x001C0001
    private const val CHUNK_START_ELEMENT = 0x00100102

    private fun u16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 3 downTo 0) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
        return v
    }

    /** 字符串池解码（UTF-8 与 UTF-16 两种形态）。 */
    private fun parseStringPool(b: ByteArray, chunkStart: Int): List<String> {
        val stringCount = u32(b, chunkStart + 8).toInt()
        val flags = u32(b, chunkStart + 16)
        val isUtf8 = (flags and 0x100L) != 0L
        val stringsStart = chunkStart + u32(b, chunkStart + 20).toInt()
        val out = mutableListOf<String>()
        for (i in 0 until stringCount.coerceAtMost(65_536)) {
            val off = stringsStart + u32(b, chunkStart + 28 + i * 4).toInt()
            if (off >= b.size) {
                out.add("")
                continue
            }
            out.add(
                if (isUtf8) decodeUtf8String(b, off) else decodeUtf16String(b, off)
            )
        }
        return out
    }

    /** UTF-8 字符串头的变长整数（1-2 字节形态：高位置位时两字节）。 */
    private fun readVarLength(b: ByteArray, cursor: Int): Pair<Int, Int> {
        val first = b[cursor].toInt() and 0xFF
        return if (first and 0x80 != 0 && cursor + 1 < b.size) {
            (((first and 0x7F) shl 8) or (b[cursor + 1].toInt() and 0xFF)) to 2
        } else {
            first to 1
        }
    }

    private fun decodeUtf8String(b: ByteArray, off: Int): String {
        var cursor = off
        val (_, n1) = readVarLength(b, cursor)
        cursor += n1
        val (byteLen, n2) = readVarLength(b, cursor)
        cursor += n2
        val end = (cursor + byteLen).coerceAtMost(b.size)
        if (end <= cursor) return ""
        return String(b, cursor, end - cursor, Charsets.UTF_8)
    }

    private fun decodeUtf16String(b: ByteArray, off: Int): String {
        val len = u16(b, off)
        val start = off + 2
        val end = (start + len * 2).coerceAtMost(b.size)
        val sb = StringBuilder()
        var i = start
        while (i + 1 < end) {
            val c = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
            if (c == 0) break
            sb.append(c.toChar())
            i += 2
        }
        return sb.toString()
    }

    /** 解析二进制 AndroidManifest.xml（bytes 来自 APK 条目）。 */
    fun parseAxml(bytes: ByteArray): AxmlManifest? {
        if (bytes.size < 8) return null
        if (u32(bytes, 0) != CHUNK_AXML_HEADER.toLong()) {
            // 某些构建管线丢失顶层头 —— 容错：从字符串池 chunk 直接开始
            if (u32(bytes, 0) != CHUNK_STRING_POOL.toLong()) return null
        }
        var cursor = 8
        var strings: List<String> = emptyList()
        // 元素树（扁平收集 START_ELEMENT 的名字与属性）
        data class Elem(val name: String, val attrs: Map<String, String>)
        val elements = mutableListOf<Elem>()
        while (cursor + 8 <= bytes.size) {
            val chunkType = u32(bytes, cursor).toInt()
            val chunkSize = u32(bytes, cursor + 4).toInt()
            if (chunkSize <= 0 || cursor + chunkSize > bytes.size) break
            when (chunkType) {
                CHUNK_STRING_POOL -> strings = parseStringPool(bytes, cursor)
                CHUNK_START_ELEMENT -> {
                    // 头 16 字节（chunk 头 + line/comment）+ ns/name 等
                    val nameIdx = u32(bytes, cursor + 20).toInt()
                    val attrStart = u16(bytes, cursor + 24)
                    val attrSize = u16(bytes, cursor + 26)
                    val attrCount = u16(bytes, cursor + 28)
                    val attrs = mutableMapOf<String, String>()
                    for (i in 0 until attrCount.coerceAtMost(256)) {
                        // attributeStart 相对 attrExt 结构起点（chunkStart+headerSize=16）：
                        // Android 源码 ResXMLTree 里 attrExt = node + headerSize，属性区 = attrExt + attributeStart
                        val attrBase = cursor + 16 + attrStart + i * attrSize
                        if (attrBase + 20 > bytes.size) break
                        // ResXMLTree_attribute：ns@0 name@4 rawValue@8
                        // typed_value：size@12 res0@14 dataType@15 data@16
                        val attrNameIdx = u32(bytes, attrBase + 4).toInt()
                        val rawValueIdxLong = u32(bytes, attrBase + 8)
                        val dataType = bytes[attrBase + 15].toInt() and 0xFF
                        val data = u32(bytes, attrBase + 16).toInt()
                        val attrName = strings.getOrNull(attrNameIdx) ?: continue
                        val value: String = when {
                            rawValueIdxLong != 0xFFFFFFFFL && rawValueIdxLong in 0 until strings.size ->
                                strings[rawValueIdxLong.toInt()]
                            dataType == 0x03 -> strings.getOrNull(data) ?: ""
                            dataType == 0x12 -> if (data != 0) "true" else "false"
                            dataType == 0x10 -> data.toString()
                            else -> "0x${data.toString(16)}"
                        }
                        attrs[attrName] = value
                    }
                    val name = strings.getOrNull(nameIdx) ?: "?"
                    elements.add(Elem(name, attrs))
                }
            }
            cursor += chunkSize
        }
        if (elements.isEmpty()) return null

        val manifest = elements.firstOrNull { it.name == "manifest" } ?: elements.first()
        val usesSdk = elements.firstOrNull { it.name == "uses-sdk" }
        val application = elements.firstOrNull { it.name == "application" }
        val permissions = elements.filter { it.name == "uses-permission" || it.name == "permission" }
            .mapNotNull { it.attrs["name"] }
        fun components(tag: String): List<String> =
            elements.filter { it.name == tag }.mapNotNull { it.attrs["name"] }
        val exported = elements.filter {
            it.name in setOf("activity", "service", "receiver", "provider") &&
                it.attrs["exported"] == "true"
        }.mapNotNull { it.attrs["name"] }

        return AxmlManifest(
            packageName = manifest.attrs["package"] ?: "",
            versionName = manifest.attrs["versionName"] ?: "",
            versionCode = manifest.attrs["versionCode"]?.toLongOrNull() ?: 0L,
            minSdk = usesSdk?.attrs?.get("minSdkVersion")?.toIntOrNull(),
            targetSdk = usesSdk?.attrs?.get("targetSdkVersion")?.toIntOrNull(),
            debuggable = application?.attrs?.get("debuggable") == "true",
            permissions = permissions,
            activities = components("activity"),
            services = components("service"),
            receivers = components("receiver"),
            providers = components("provider"),
            exportedComponents = exported,
            applicationLabel = application?.attrs?.get("label") ?: ""
        )
    }

    // ── dex 字符串枚举（类清单底座）────────────────────────────────────

    /** 从 dex bytes 枚举全部字符串（MUTF-8；上限 200k 条）。 */
    fun enumerateDexStrings(dex: ByteArray): List<String> {
        if (dex.size < 60) return emptyList()
        if (dex[0] != 'd'.code.toByte() || dex[1] != 'e'.code.toByte()) return emptyList()
        val stringIdsSize = u32(dex, 56).toInt()
        val stringIdsOff = u32(dex, 60).toInt()
        val out = mutableListOf<String>()
        for (i in 0 until stringIdsSize.coerceAtMost(200_000)) {
            val dataOff = u32(dex, stringIdsOff + i * 4).toInt()
            if (dataOff <= 0 || dataOff >= dex.size) continue
            // uleb128 长度（字符数，仅用于跳过）+ MUTF-8 字节
            var cursor = dataOff
            var shift = 0
            var charLen = 0
            while (true) {
                if (cursor >= dex.size) return out
                val b = dex[cursor].toInt() and 0xFF
                charLen = charLen or ((b and 0x7F) shl shift)
                cursor++
                if (b and 0x80 == 0) break
                shift += 7
            }
            val end = run {
                var e = cursor
                while (e < dex.size && dex[e] != 0.toByte()) e++
                if (e >= dex.size) dex.size else e
            }
            val stop = end
            if (stop > cursor) {
                out.add(String(dex, cursor, stop - cursor, Charsets.UTF_8))
            }
        }
        return out
    }

    /** `Lcom/foo/Bar;` 形态的类描述符过滤 → 类名清单。 */
    fun dexClassNames(strings: List<String>): List<String> =
        strings.filter { it.length > 2 && it.startsWith("L") && it.endsWith(";") && !it.contains(' ') }
            .map { it.removePrefix("L").removeSuffix(";").replace('/', '.') }
            .distinct()
            .sorted()

    // ── Flutter / Dart AOT ─────────────────────────────────────────────

    data class FlutterFacts(
        val isFlutter: Boolean,
        val abis: List<String>,
        val snapshotSymbols: List<String>,
        val libappSize: Long
    )

    fun identifyFlutter(apk: File): FlutterFacts {
        var isFlutter = false
        val abis = mutableListOf<String>()
        val symbols = mutableListOf<String>()
        var libappSize = 0L
        try {
            ZipFile(apk).use { zip ->
                val entries = zip.entries().asSequence().toList()
                val flutterLibs = entries.filter {
                    it.name.startsWith("lib/") && it.name.endsWith("/libflutter.so")
                }
                val appLibs = entries.filter {
                    it.name.startsWith("lib/") && it.name.endsWith("/libapp.so")
                }
                isFlutter = flutterLibs.isNotEmpty() || appLibs.isNotEmpty()
                flutterLibs.forEach {
                    abis.add(it.name.split("/")[1])
                }
                if (appLibs.isNotEmpty()) {
                    libappSize = appLibs.first().size
                    // libapp.so = ELF：枚举动态符号里的 Dart 快照四件套
                    val bytes = zip.getInputStream(appLibs.first()).use { it.readBytes() }
                    val tmp = File.createTempFile("rea-libapp", ".so")
                    try {
                        tmp.writeBytes(bytes)
                        val elf = ReaBinaryFormat.parseElf(tmp)
                        if (elf != null) {
                            val snap = elf.dynamicSymbols.filter {
                                it.contains("DartVmSnapshot") || it.contains("DartIsolateSnapshot")
                            }
                            symbols.addAll(snap)
                        }
                    } finally {
                        tmp.delete()
                    }
                }
            }
        } catch (e: Exception) {
            // 归档读取失败 → 维持 isFlutter=false 的事实态
        }
        return FlutterFacts(isFlutter, abis.distinct(), symbols, libappSize)
    }

    // ── 渲染 ────────────────────────────────────────────────────────────

    fun renderPackage(tool: String, apk: File): JsonObject {
        val inventory = ReaZipInspector.inspect(apk)
        if (inventory == null) {
            return ReaEvidence.error(tool, apk.absolutePath, "非可读归档（ZIP/APK）")
        }
        val findings = mutableListOf<ReaEvidence.Finding>()
        val evidence = mutableListOf<ReaEvidence.Item>()
        val limitations = mutableListOf<String>()

        var axml: AxmlManifest? = null
        var dexClassCount = 0
        try {
            ZipFile(apk).use { zip ->
                val manifestEntry = zip.getEntry("AndroidManifest.xml")
                if (manifestEntry != null) {
                    val bytes = zip.getInputStream(manifestEntry).use { it.readBytes() }
                    axml = parseAxml(bytes)
                    evidence.add(ReaEvidence.Item("manifest", "AndroidManifest.xml", "AXML ${bytes.size} 字节"))
                }
                var dexIdx = 0
                for (dexName in inventory.dexFiles) {
                    val entry = zip.getEntry(dexName) ?: continue
                    if (entry.size > 80L * 1024 * 1024) {
                        limitations.add("$dexName 超 80MB 跳过类统计")
                        continue
                    }
                    val bytes = zip.getInputStream(entry).use { it.readBytes() }
                    val classes = dexClassNames(enumerateDexStrings(bytes))
                    dexClassCount += classes.size
                    dexIdx++
                    evidence.add(ReaEvidence.Item("dex", dexName, "${classes.size} 类"))
                }
            }
        } catch (e: Exception) {
            limitations.add("dex/manifest 读取异常: ${e.message}")
        }

        val m = axml
        if (m != null) {
            if (m.packageName.isNotBlank()) findings.add(ReaEvidence.Finding("package", m.packageName))
            findings.add(ReaEvidence.Finding("version", "${m.versionName}(${m.versionCode})"))
            if (m.minSdk != null || m.targetSdk != null) {
                findings.add(ReaEvidence.Finding("sdk", "min=${m.minSdk ?: "?"} target=${m.targetSdk ?: "?"}"))
            }
            if (m.permissions.isNotEmpty()) {
                findings.add(ReaEvidence.Finding("permissions", "${m.permissions.size} 项：${m.permissions.take(20).joinToString(", ")}"))
            }
            findings.add(
                ReaEvidence.Finding(
                    "components",
                    "activity×${m.activities.size} service×${m.services.size} receiver×${m.receivers.size} provider×${m.providers.size} exported×${m.exportedComponents.size}"
                )
            )
            if (m.debuggable) findings.add(ReaEvidence.Finding("debuggable", "android:debuggable=true"))
        } else {
            limitations.add("AndroidManifest.xml 缺失或 AXML 解析失败（清单结论不可用）")
        }
        findings.add(ReaEvidence.Finding("classes", "$dexClassCount 个类（全部 dex 字符串枚举）"))
        if (inventory.nativeAbis.isNotEmpty()) {
            findings.add(ReaEvidence.Finding("native ABIs", inventory.nativeAbis.joinToString(", ")))
        }
        val flutter = identifyFlutter(apk)
        if (flutter.isFlutter) {
            findings.add(ReaEvidence.Finding("flutter", "Flutter 构建（${flutter.abis.joinToString("/")}，libapp.so ${flutter.libappSize / 1024} KB）"))
            if (flutter.snapshotSymbols.isNotEmpty()) {
                findings.add(ReaEvidence.Finding("dart snapshot symbols", flutter.snapshotSymbols.joinToString(", ")))
                evidence.add(ReaEvidence.Item("symbols", "lib/<abi>/libapp.so", "Dart AOT 快照符号"))
            }
        }
        findings.add(ReaEvidence.Finding("family", "APK（${inventory.dexFiles.joinToString(", ")}）"))

        return ReaEvidence.success(
            tool = tool, target = apk.absolutePath, tier = "on_device",
            findings = findings, evidence = evidence, limitations = limitations,
            digest = ReaEvidence.digestOfFile(apk)
        )
    }

    fun renderSearchClasses(tool: String, apk: File, query: String): JsonObject {
        try {
            ZipFile(apk).use { zip ->
                val dexNames = zip.entries().asSequence()
                    .filter { it.name.endsWith(".dex") && !it.isDirectory }
                    .map { it.name }.toList()
                val hits = mutableListOf<String>()
                for (dexName in dexNames) {
                    val entry = zip.getEntry(dexName) ?: continue
                    if (entry.size > 80L * 1024 * 1024) continue
                    val bytes = zip.getInputStream(entry).use { it.readBytes() }
                    val classes = dexClassNames(enumerateDexStrings(bytes))
                    if (query.isBlank()) {
                        hits.addAll(classes.take(500))
                    } else {
                        hits.addAll(classes.filter { it.contains(query, ignoreCase = true) })
                    }
                }
                return ReaEvidence.success(
                    tool = tool, target = apk.absolutePath, tier = "on_device",
                    findings = listOf(ReaEvidence.Finding("matches", "${hits.size} 个类名命中 '$query'（空查询 = 全量前 500）")),
                    evidence = hits.take(200).map { ReaEvidence.Item("class", it, "") },
                    limitations = listOf("类清单来自 dex 字符串枚举（无方法级签名 —— 方法级需 jadx 桥）")
                )
            }
        } catch (e: Exception) {
            return ReaEvidence.error(tool, apk.absolutePath, "APK 读取失败: ${e.message}")
        }
    }
}
