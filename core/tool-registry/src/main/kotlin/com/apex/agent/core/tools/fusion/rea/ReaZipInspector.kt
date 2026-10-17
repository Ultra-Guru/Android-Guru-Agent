package com.apex.agent.core.tools.fusion.rea

import com.apex.agent.core.tools.skill.SafeZipExtractor
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * # REA 归档 / 应用包清点器（inspect_artifact / extract_artifact / compare）
 *
 * ZIP 家族（ZIP / APK / IPA / JAR / MSIX / HAP）中央目录直读：条目清单、
 * 尺寸、方法、原生库 ABI、签名块、清单文件定位 —— 每条结论带条目名证据。
 * 解压复用仓库既有 [SafeZipExtractor]（路径穿越防御 + zip bomb 三重上限，
 * 与 SkillRegistry.installFromZip 同一防线）。
 */
object ReaZipInspector {

    /** 归档条目摘要。 */
    data class EntryInfo(
        val name: String,
        val compressedSize: Long,
        val size: Long,
        val method: Int,
        val isDirectory: Boolean
    )

    /** 归档全景：条目 + 家族分类 + 关键构件。 */
    data class ArtifactInventory(
        val entries: List<EntryInfo>,
        val family: String,          // apk / ipa / jar / zip / msix / hap / zip
        val nativeAbis: List<String>,
        val hasDex: Boolean,
        val dexFiles: List<String>,
        val manifestFile: String?,
        val signatureFiles: List<String>,
        val hasResourcesArsc: Boolean
    )

    /** EOCD 定位 + ZipFile 直读（ZipFile 走中央目录，天然抗坏数据区）。 */
    fun inspect(file: File): ArtifactInventory? {
        if (!file.isFile) return null
        val entries = mutableListOf<EntryInfo>()
        try {
            ZipFile(file).use { zip ->
                val enumeration = zip.entries()
                while (enumeration.hasMoreElements()) {
                    val e: ZipEntry = enumeration.nextElement()
                    entries.add(
                        EntryInfo(
                            name = e.name,
                            compressedSize = e.compressedSize,
                            size = e.size,
                            method = e.method,
                            isDirectory = e.isDirectory
                        )
                    )
                    if (entries.size > 50_000) break
                }
            }
        } catch (e: Exception) {
            // 中央目录损坏 → 退化 EOCD 扫描（读条目数与偏移至少报数）
            return null
        }
        val names = entries.filter { !it.isDirectory }.map { it.name }
        val isApk = file.extension.equals("apk", true) ||
            (names.any { it == "AndroidManifest.xml" } && names.any { it.startsWith("classes") && it.endsWith(".dex") })
        val isIpa = file.extension.equals("ipa", true) || names.any { it.startsWith("Payload/") }
        val isJar = file.extension.equals("jar", true) && names.any { it == "META-INF/MANIFEST.MF" }
        val isMsix = file.extension.equals("msix", true) || file.extension.equals("appx", true)
        val isHarmony = file.extension.equals("hap", true) || file.extension.equals("hsp", true)
        val family = when {
            isApk -> "apk"
            isIpa -> "ipa"
            isMsix -> "msix/appx"
            isHarmony -> "harmony"
            isJar -> "jar"
            else -> "zip"
        }
        val abis = names.filter { it.startsWith("lib/") && it.endsWith(".so") }
            .map { it.split("/")[1] }
            .distinct()
        val dexes = names.filter { it.endsWith(".dex") }
        val sigs = names.filter {
            it.startsWith("META-INF/") && (it.endsWith(".SF") || it.endsWith(".RSA") || it.endsWith(".DSA") || it.endsWith(".EC"))
        }
        val manifest = names.firstOrNull { it == "AndroidManifest.xml" }
            ?: names.firstOrNull { it.endsWith("Info.plist") }
            ?: names.firstOrNull { it == "META-INF/MANIFEST.MF" }
            ?: names.firstOrNull { it.endsWith("config.json") }
        return ArtifactInventory(
            entries = entries,
            family = family,
            nativeAbis = abis,
            hasDex = dexes.isNotEmpty(),
            dexFiles = dexes,
            manifestFile = manifest,
            signatureFiles = sigs,
            hasResourcesArsc = names.any { it == "resources.arsc" }
        )
    }

    /** EOCD 快速探测（坏归档时的最低限度事实：条目数与中央目录偏移）。 */
    fun probeEocd(file: File): Pair<Int, Long>? {
        RandomAccessFile(file, "r").use { f ->
            val len = f.length()
            if (len < 22) return null
            val windowSize = minOf(len, 66_000L)
            f.seek(len - windowSize)
            val buf = ByteArray(windowSize.toInt())
            f.readFully(buf)
            for (i in buf.size - 22 downTo 0) {
                if (buf[i] == 0x50.toByte() && buf[i + 1] == 0x4B.toByte() &&
                    buf[i + 2] == 0x05.toByte() && buf[i + 3] == 0x06.toByte()
                ) {
                    val entries = (buf[i + 10].toInt() and 0xFF) or ((buf[i + 11].toInt() and 0xFF) shl 8)
                    val cdOff = (buf[i + 16].toInt() and 0xFF).toLong() or
                        ((buf[i + 17].toInt() and 0xFF).toLong() shl 8) or
                        ((buf[i + 18].toInt() and 0xFF).toLong() shl 16) or
                        ((buf[i + 19].toInt() and 0xFF).toLong() shl 24)
                    return entries to cdOff
                }
            }
            return null
        }
    }

    // ── 渲染 ────────────────────────────────────────────────────────────

    fun renderInventory(tool: String, file: File): JsonObject {
        val inventory = inspect(file)
        if (inventory == null) {
            val probe = probeEocd(file)
            return if (probe != null) {
                ReaEvidence.success(
                    tool = tool, target = file.absolutePath, tier = "on_device",
                    findings = listOf(
                        ReaEvidence.Finding("format", "ZIP（中央目录异常，EOCD 探测兜底）"),
                        ReaEvidence.Finding("declared entries", "${probe.first} 个（EOCD 声明）")
                    ),
                    evidence = listOf(ReaEvidence.Item("eocd", "0x${probe.second.toString(16)}", "中央目录偏移")),
                    limitations = listOf("条目级清点不可用（ZipFile 解析失败）")
                )
            } else {
                ReaEvidence.error(tool, file.absolutePath, "非 ZIP 家族归档或文件损坏")
            }
        }
        val findings = mutableListOf<ReaEvidence.Finding>()
        val evidence = mutableListOf<ReaEvidence.Item>()
        val totalUnc = inventory.entries.filter { !it.isDirectory }.sumOf { it.size }
        val totalComp = inventory.entries.filter { !it.isDirectory }.sumOf { it.compressedSize }
        findings.add(ReaEvidence.Finding("format", "ZIP 家族 → ${inventory.family}"))
        findings.add(
            ReaEvidence.Finding(
                "inventory",
                "${inventory.entries.count { !it.isDirectory }} 个文件 / ${inventory.entries.count { it.isDirectory }} 个目录，" +
                    "解压后 ${totalUnc / 1024} KB（压缩 ${totalComp / 1024} KB）"
            )
        )
        if (inventory.nativeAbis.isNotEmpty()) {
            findings.add(ReaEvidence.Finding("native ABIs", inventory.nativeAbis.joinToString(", ")))
        }
        if (inventory.hasDex) {
            findings.add(ReaEvidence.Finding("dex", inventory.dexFiles.joinToString(", ")))
        }
        if (inventory.hasResourcesArsc) findings.add(ReaEvidence.Finding("resources", "resources.arsc 存在"))
        if (inventory.signatureFiles.isNotEmpty()) {
            findings.add(ReaEvidence.Finding("signatures", inventory.signatureFiles.joinToString(", ")))
        }
        inventory.manifestFile?.let {
            evidence.add(ReaEvidence.Item("manifest", it, "清单文件定位"))
        }
        // 最大的 20 个文件（人工检查的黄金入口）
        val largest = inventory.entries.filter { !it.isDirectory }
            .sortedByDescending { it.size }.take(20)
        for (e in largest) {
            evidence.add(ReaEvidence.Item("entry", e.name, "${e.size / 1024} KB"))
        }
        return ReaEvidence.success(
            tool = tool, target = file.absolutePath, tier = "on_device",
            findings = findings, evidence = evidence,
            limitations = if (inventory.entries.size > 50_000) listOf("条目清点上限 50k") else emptyList(),
            digest = ReaEvidence.digestOfFile(file)
        )
    }

    fun renderExtract(tool: String, file: File, output: File): JsonObject {
        if (!file.isFile) return ReaEvidence.error(tool, file.absolutePath, "归档不存在")
        try {
            SafeZipExtractor.extract(file, output)
        } catch (e: Exception) {
            return ReaEvidence.error(tool, file.absolutePath, "解压失败: ${e.message}")
        }
        val extracted = output.walkTopDown().count { it.isFile }
        return ReaEvidence.success(
            tool = tool, target = file.absolutePath, tier = "on_device",
            findings = listOf(ReaEvidence.Finding("extracted", "$extracted 个文件 → ${output.absolutePath}")),
            evidence = listOf(
                ReaEvidence.Item("directory", output.absolutePath, "输出目录"),
                ReaEvidence.Item("guards", "", "路径穿越防御 + 单文件 50MB / 总量 256MB 上限")
            )
        )
    }

    fun renderCompare(tool: String, a: File, b: File): JsonObject {
        val invA = inspect(a)
        val invB = inspect(b)
        if (invA == null || invB == null) {
            return ReaEvidence.error(tool, "${a.name} vs ${b.name}", "至少一侧不是可读 ZIP 家族归档")
        }
        val namesA = invA.entries.filter { !it.isDirectory }.map { it.name }.toSet()
        val namesB = invB.entries.filter { !it.isDirectory }.map { it.name }.toSet()
        val added = namesB - namesA
        val removed = namesA - namesB
        // 尺寸变化（同路径）
        val sizeA = invA.entries.filter { !it.isDirectory }.associate { it.name to it.size }
        val sizeB = invB.entries.filter { !it.isDirectory }.associate { it.name to it.size }
        val changed = namesA.intersect(namesB).filter { sizeA[it] != sizeB[it] }
        return ReaEvidence.success(
            tool = tool, target = "${a.name} vs ${b.name}", tier = "on_device",
            findings = listOf(
                ReaEvidence.Finding("logical diff", "A=${namesA.size} B=${namesB.size} 新增=${added.size} 移除=${removed.size} 尺寸变化=${changed.size}")
            ),
            evidence = buildList {
                added.take(40).forEach { add(ReaEvidence.Item("added", it, "")) }
                removed.take(40).forEach { add(ReaEvidence.Item("removed", it, "")) }
                changed.take(40).forEach { add(ReaEvidence.Item("size-changed", it, "${sizeA[it] ?: 0} → ${sizeB[it] ?: 0}")) }
            },
            limitations = listOf("对比为路径 + 尺寸级（内容 hash 级对比待后续）")
        )
    }
}
