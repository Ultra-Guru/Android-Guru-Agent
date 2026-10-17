package com.apex.agent.core.tools.fusion.rea

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * REA 纯设备分析器的行为锁定（手写二进制 fixture，零外部样本依赖）：
 *
 * 1. ASAR：pickle 头 + 头 JSON + 数据区三段构造 → 目录/单文件读取/
 *    import 提取/Electron 角色/应用图渲染；
 * 2. ELF64：最小头 + 一个 PT_LOAD → machine/type/entry/segments/
 *    vaddr→文件偏移映射；
 * 3. ZIP/APK：ZipOutputStream 造归档 → 条目清点/家族分类/Flutter 识别；
 * 4. AXML：字符串池 + START_ELEMENT chunk → package/权限/组件提取；
 * 5. dex：string_ids 单字符串 → 类清单。
 */
class ReaAnalyzersTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ═══ fixture 构造器 ═════════════════════════════════════════════════

    /** 构造最小 ASAR（Electron 归档格式）。 */
    private fun buildAsar(files: List<Pair<String, String>>): ByteArray {
        // 头 JSON：files 树（目录递归内联为单层 —— 测试面足够）
        val header = StringBuilder("{\"files\":{")
        var offset = 0L
        val datas = mutableListOf<ByteArray>()
        files.forEachIndexed { i, (name, content) ->
            val bytes = content.toByteArray(Charsets.UTF_8)
            if (i > 0) header.append(",")
            header.append("\"$name\":{\"size\":${bytes.size},\"offset\":$offset}")
            offset += bytes.size
            datas.add(bytes)
        }
        header.append("}}")
        var headerBytes = header.toString().toByteArray(Charsets.UTF_8)
        // 4 字节对齐填充（pickle 语义：空格填充）
        val pad = (4 - (headerBytes.size % 4)) % 4
        if (pad > 0) headerBytes = headerBytes + ByteArray(pad) { ' '.code.toByte() }

        val out = ByteArrayOutputStream()
        out.write(le32(4))
        out.write(le32(headerBytes.size))
        out.write(headerBytes)
        datas.forEach { out.write(it) }
        return out.toByteArray()
    }

    private fun le32(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte()
    )

    private fun le64(v: Long): ByteArray =
        le32((v and 0xFFFFFFFFL).toInt()) + le32(((v ushr 32) and 0xFFFFFFFFL).toInt())

    private fun u16b(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte()
    )

    /** 构造最小 ELF64（一个 PT_LOAD 段，无 section 表）。 */
    private fun buildElf64(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()))
        out.write(byteArrayOf(2, 1, 1, 0)) // 64-bit / LE / version / ABI
        out.write(ByteArray(8)) // ABI padding
        out.write(u16b(2))          // e_type = EXEC @16
        out.write(u16b(0xB7))       // e_machine = aarch64 @18
        out.write(le32(1))          // e_version @20
        out.write(le64(0x1000))     // e_entry @24
        out.write(le64(64))         // e_phoff @32
        out.write(le64(0))          // e_shoff @40
        out.write(le32(0))          // e_flags @48
        out.write(u16b(64))         // e_ehsize @52
        out.write(u16b(56))         // e_phentsize @54
        out.write(u16b(1))          // e_phnum @56
        out.write(u16b(64))         // e_shentsize @58
        out.write(u16b(0))          // e_shnum @60
        out.write(u16b(0))          // e_shstrndx @62
        // program header：PT_LOAD flags=RWX vaddr=0x1000 fileoff=0 filesz=128
        out.write(le32(1))          // p_type
        out.write(le32(5))          // p_flags
        out.write(le64(0))          // p_offset
        out.write(le64(0x1000))     // p_vaddr
        out.write(le64(0x1000))     // p_paddr
        out.write(le64(128))        // p_filesz
        out.write(le64(128))        // p_memsz
        out.write(le64(8))          // p_align
        // 数据区（128 字节可打印串）
        out.write("hello-elf-section".toByteArray())
        out.write(ByteArray(128 - "hello-elf-section".length))
        return out.toByteArray()
    }

    /** 构造最小 AXML（字符串池 + manifest 元素带 package 属性）。 */
    private fun buildAxml(): ByteArray {
        val strings = listOf("manifest", "package", "com.example.app", "uses-permission", "name", "android.permission.INTERNET")
        fun utf8String(s: String): ByteArray {
            val bytes = s.toByteArray(Charsets.UTF_8)
            return byteArrayOf(bytes.size.toByte(), bytes.size.toByte()) + bytes + byteArrayOf(0)
        }
        val stringData = strings.flatMap { utf8String(it).toList() }.toByteArray()
        val offsets = mutableListOf<Int>()
        var cursor = 0
        for (s in strings) {
            offsets.add(cursor)
            cursor += utf8String(s).size
        }
        val stringsStart = 28 + strings.size * 4
        // 字符串池 chunk
        val poolSize = 28 + strings.size * 4 + stringData.size
        val pool = ByteArrayOutputStream()
        pool.write(u16b(0x0001))            // chunk type STRING_POOL
        pool.write(u16b(0x001C))            // headerSize
        pool.write(le32(poolSize))
        pool.write(le32(strings.size))      // stringCount @8
        pool.write(le32(0))                 // styleCount @12
        pool.write(le32(0x100))             // flags @16 = UTF-8
        pool.write(le32(stringsStart))      // stringsStart @20（相对 chunk 起点）
        pool.write(le32(0))                 // stylesStart @24
        offsets.forEach { pool.write(le32(it)) }
        pool.write(stringData)

        // START_ELEMENT：manifest（attr：package=STRING(com.example.app)）
        fun attrRefName(nameIdx: Int, valueIdx: Int): ByteArray {
            // name 属性形态：rawValue=0xFFFFFFFF、typed STRING 引用 data
            val b = ByteArrayOutputStream()
            b.write(le32(-1))
            b.write(le32(nameIdx))
            b.write(le32(-1))
            b.write(u16b(8))
            b.write(byteArrayOf(0))
            b.write(byteArrayOf(0x03))
            b.write(le32(valueIdx))
            return b.toByteArray()
        }
        val packageAttr = attrRefName(1, 2) // name="package" value 串 idx 2
        val elementSize = 36 + packageAttr.size
        val elem = ByteArrayOutputStream()
        elem.write(u16b(0x0102))          // START_ELEMENT
        elem.write(u16b(0x0010))          // headerSize
        elem.write(le32(elementSize))
        elem.write(le32(1))               // line
        elem.write(le32(-1))              // comment
        elem.write(le32(-1))              // ns @16
        elem.write(le32(0))               // name idx 0 = "manifest" @20
        elem.write(u16b(20))              // attributeStart @24
        elem.write(u16b(20))              // attributeSize @26
        elem.write(u16b(1))               // attributeCount @28
        elem.write(u16b(0))               // idIndex @30
        elem.write(u16b(0))               // classIndex @32
        elem.write(u16b(0))               // styleIndex @34
        elem.write(packageAttr)

        val totalSize = 8 + pool.size() + elem.size()
        val top = ByteArrayOutputStream()
        top.write(u16b(0x0003))
        top.write(u16b(0x0008))
        top.write(le32(totalSize))
        top.write(pool.toByteArray())
        top.write(elem.toByteArray())
        return top.toByteArray()
    }

    /** 构造最小 dex（单个字符串 "Lcom/example/Foo;"）。 */
    private fun buildDex(): ByteArray {
        val dex = ByteArray(160)
        val magic = "dex\n035\u0000".toByteArray(Charsets.US_ASCII)
        System.arraycopy(magic, 0, dex, 0, 8)
        // string_ids_size @56 = 1，string_ids_off @60 = 70（string_ids[0] = 80 → string_data）
        System.arraycopy(le32(1), 0, dex, 56, 4)
        System.arraycopy(le32(70), 0, dex, 60, 4)
        System.arraycopy(le32(80), 0, dex, 70, 4)
        // string_data @80：uleb128(18) + "Lcom/example/Foo;" + 0x00
        val s = "Lcom/example/Foo;".toByteArray(Charsets.US_ASCII)
        dex[80] = s.size.toByte()
        System.arraycopy(s, 0, dex, 81, s.size)
        dex[81 + s.size] = 0
        return dex
    }

    private fun write(name: String, bytes: ByteArray): File {
        val f = tmp.newFile(name)
        f.writeBytes(bytes)
        return f
    }

    // ═══ ASAR ═══════════════════════════════════════════════════════════

    @Test
    fun `ASAR 目录解析与单文件读取`() {
        val mainJs = """
            const { app, BrowserWindow } = require('electron');
            const path = require('path');
            app.whenReady().then(() => { new BrowserWindow({ width: 800 }); });
        """.trimIndent()
        val rendererJs = """
            const { ipcRenderer } = require('electron');
            import { helper } from './helper.js';
            window.addEventListener('load', () => ipcRenderer.send('ready'));
        """.trimIndent()
        val pkg = """{"name":"fixture-app","version":"1.2.3","main":"main.js","dependencies":{"react":"18.2.0"}}"""
        val asar = write(
            "app.asar",
            buildAsar(listOf("main.js" to mainJs, "renderer.js" to rendererJs, "package.json" to pkg))
        )

        val index = ReaAsarAnalyzer.parseAsarIndex(asar)
        assertNotNull("ASAR 头必须可解析", index)
        index!!
        assertEquals(3, index.entries.size)
        assertTrue(index.entries.containsKey("main.js"))

        val content = ReaAsarAnalyzer.readAsarEntry(asar, index, "package.json")
        assertNotNull(content)
        assertEquals(pkg, content!!.toString(Charsets.UTF_8))
    }

    @Test
    fun `import 提取覆盖三族语法`() {
        val js = """
            const a = require('electron');
            const b = require("path");
            import { x } from './mod.js';
            export { y } from 'pkg2';
            const c = import('./dyn.js');
        """.trimIndent()
        val refs = ReaAsarAnalyzer.extractImports(js)
        assertEquals(5, refs.size)
        assertTrue(refs.any { it.spec == "electron" && it.kind == "require" })
        assertTrue(refs.any { it.spec == "path" && it.kind == "require" })
        assertTrue(refs.any { it.spec == "./mod.js" && it.kind == "import" })
        assertTrue(refs.any { it.spec == "pkg2" && it.kind == "export-from" })
        assertTrue(refs.any { it.spec == "./dyn.js" && it.kind == "dynamic-import" })
        assertTrue(refs.all { it.line in 1..5 })
    }

    @Test
    fun `应用图渲染含模块数 边界 依赖与限制`() {
        val mainJs = "const { app, BrowserWindow, ipcMain } = require('electron');\napp.whenReady();"
        val rendererJs = "const { ipcRenderer } = require('electron');\ndocument.title = 'x';"
        val pkg = """{"name":"e-app","version":"0.1.0","main":"main.js","dependencies":{"vue":"3.4.0"}}"""
        val asar = write(
            "e-app.asar",
            buildAsar(listOf("main.js" to mainJs, "renderer.js" to rendererJs, "package.json" to pkg))
        )
        val json = ReaAsarAnalyzer.renderAnalysis("analyze_javascript_application", asar)
        assertEquals("true", json["ok"]?.jsonPrimitive?.contentOrNull)
        val text = json.toString()
        assertTrue(text.contains("2 个 JS 模块"))
        assertTrue(text.contains("main:"))
        assertTrue(text.contains("renderer:"))
        assertTrue(text.contains("e-app@0.1.0"))
        assertTrue(text.contains("vue"))
        assertTrue("证据纪律：限制必须如实声明", text.contains("limitations"))
        assertTrue(text.contains("正则匹配"))
    }

    @Test
    fun `目录形态的应用同样可分析`() {
        val dir = tmp.newFolder("jsapp")
        File(dir, "package.json").writeText("""{"name":"dir-app","version":"2.0.0"}""")
        File(dir, "index.js").writeText("import './lib.js';")
        File(dir, "lib.js").writeText("export const v = 1;")

        val json = ReaAsarAnalyzer.renderAnalysis("analyze_javascript_application", dir)
        assertEquals("true", json["ok"]?.jsonPrimitive?.contentOrNull)
        assertTrue(json.toString().contains("dir-app@2.0.0"))
    }

    // ═══ ELF ════════════════════════════════════════════════════════════

    @Test
    fun `ELF64 布局解析与 vaddr 映射`() {
        val elf = write("libtest.so", buildElf64())
        val parsed = ReaBinaryFormat.parseElf(elf)
        assertNotNull(parsed)
        parsed!!
        assertEquals("aarch64", parsed.machine)
        assertEquals("EXEC", parsed.type)
        assertEquals(0x1000L, parsed.entry)
        assertEquals(1, parsed.segments.size)
        assertEquals("LOAD", parsed.segments[0].type)
        assertEquals(0x1000L, parsed.segments[0].vaddr)

        // vaddr → 文件偏移
        val off = ReaBinaryFormat.elfVaddrToFileOffset(parsed, 0x1010L)
        assertEquals(0x10L, off)
        assertEquals(null, ReaBinaryFormat.elfVaddrToFileOffset(parsed, 0x9000L))
    }

    @Test
    fun `ELF 布局渲染与字符串检索`() {
        val elf = write("libtest.so", buildElf64())
        val layoutJson = ReaBinaryFormat.renderLayout("inspect_binary_layout", elf)
        assertEquals("true", layoutJson["ok"]?.jsonPrimitive?.contentOrNull)
        val text = layoutJson.toString()
        assertTrue(text.contains("ELF"))
        assertTrue(text.contains("aarch64"))
        assertTrue(text.contains("LOAD"))

        // 字符串扫描（fixture 内嵌可打印串）
        val scan = ReaBinaryFormat.renderSymbolScan("search", elf, "hello-elf", regex = false)
        assertTrue(scan.toString().contains("hello-elf-section"))
    }

    @Test
    fun `search_bytes 十六进制模式命中`() {
        val elf = write("libtest.so", buildElf64())
        // "ELF" 魔数 45 4C 46
        val json = ReaBinaryFormat.renderSearchBytes("search_bytes", elf, "454c46")
        assertEquals("true", json["ok"]?.jsonPrimitive?.contentOrNull)
        assertTrue(json.toString().contains("1 处命中"))
    }

    @Test
    fun `read_bytes 与 address_to_file_offset`() {
        val elf = write("libtest.so", buildElf64())
        val rb = ReaBinaryFormat.renderReadBytes("read_bytes", elf, 1, 3)
        // 偏移 1..3 = "ELF"
        assertTrue(rb.toString().contains("45 4c 46"))
        val a2f = ReaBinaryFormat.renderAddressToFileOffset("address_to_file_offset", elf, "0x1000")
        assertTrue(a2f.toString().contains("0x0"))
    }

    // ═══ ZIP / APK ══════════════════════════════════════════════════════

    private fun buildZip(entries: List<Pair<String, ByteArray>>): File {
        val f = tmp.newFile("fixture.zip")
        ZipOutputStream(f.outputStream().buffered()).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return f
    }

    @Test
    fun `ZIP 清点与家族分类`() {
        val zip = buildZip(
            listOf(
                "AndroidManifest.xml" to buildAxml(),
                "classes.dex" to buildDex(),
                "lib/arm64-v8a/libflutter.so" to byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()),
                "META-INF/CERT.SF" to "Signature-File".toByteArray()
            )
        )
        val json = ReaZipInspector.renderInventory("inspect_artifact", zip)
        assertEquals("true", json["ok"]?.jsonPrimitive?.contentOrNull)
        val text = json.toString()
        assertTrue(text.contains("apk"))
        assertTrue(text.contains("arm64-v8a"))
        assertTrue(text.contains("CERT.SF"))
    }

    @Test
    fun `Flutter 识别`() {
        val apk = buildZip(
            listOf(
                "AndroidManifest.xml" to buildAxml(),
                "lib/arm64-v8a/libflutter.so" to buildElf64(),
                "lib/arm64-v8a/libapp.so" to buildElf64()
            )
        )
        val facts = ReaApkInspector.identifyFlutter(apk)
        assertTrue(facts.isFlutter)
        assertEquals(listOf("arm64-v8a"), facts.abis)
        assertTrue(facts.libappSize > 0)
    }

    @Test
    fun `APK 全景渲染（AXML + dex 类清单 + Flutter）`() {
        val apk = buildZip(
            listOf(
                "AndroidManifest.xml" to buildAxml(),
                "classes.dex" to buildDex(),
                "lib/arm64-v8a/libflutter.so" to buildElf64(),
                "lib/arm64-v8a/libapp.so" to buildElf64()
            )
        )
        val json = ReaApkInspector.renderPackage("inspect_android_package", apk)
        assertEquals("true", json["ok"]?.jsonPrimitive?.contentOrNull)
        val text = json.toString()
        assertTrue("AXML 提取 package", text.contains("com.example.app"))
        assertTrue("dex 类计数", text.contains("classes"))
        assertTrue("Flutter 识别", text.contains("Flutter 构建"))
    }

    @Test
    fun `search_android_classes 子串检索`() {
        val apk = buildZip(listOf("AndroidManifest.xml" to buildAxml(), "classes.dex" to buildDex()))
        val json = ReaApkInspector.renderSearchClasses("search_android_classes", apk, "example")
        assertEquals("true", json["ok"]?.jsonPrimitive?.contentOrNull)
        assertTrue(json.toString().contains("com.example.Foo"))
        // 空查询 = 全量前 500
        val all = ReaApkInspector.renderSearchClasses("search_android_classes", apk, "")
        assertTrue(all.toString().contains("com.example.Foo"))
    }

    // ═══ AXML / dex 直测 ════════════════════════════════════════════════

    @Test
    fun `AXML 解析出 package 属性`() {
        val manifest = ReaApkInspector.parseAxml(buildAxml())
        assertNotNull(manifest)
        manifest!!
        assertEquals("com.example.app", manifest.packageName)
    }

    @Test
    fun `dex 字符串枚举与类名归一`() {
        val strings = ReaApkInspector.enumerateDexStrings(buildDex())
        assertEquals(listOf("Lcom/example/Foo;"), strings)
        assertEquals(listOf("com.example.Foo"), ReaApkInspector.dexClassNames(strings))
    }

    // ═══ 杂项解析器 ═════════════════════════════════════════════════════

    @Test
    fun `plist 与 sqlite 头页事实`() {
        val plist = write(
            "Info.plist",
            """
            <?xml version="1.0"?>
            <plist version="1.0"><dict>
            <key>CFBundleIdentifier</key><string>com.example.iosapp</string>
            <key>CFBundleVersion</key><string>42</string>
            </dict></plist>
            """.trimIndent().toByteArray()
        )
        val pj = ReaMiscParsers.renderPlist("inspect_plist", plist)
        assertEquals("true", pj["ok"]?.jsonPrimitive?.contentOrNull)
        assertTrue(pj.toString().contains("CFBundleIdentifier"))

        val sqlite = write("data.db", "SQLite format 3".toByteArray() + ByteArray(2000))
        val sj = ReaMiscParsers.renderSqlite("inspect_sqlite_database", sqlite)
        assertEquals("true", sj["ok"]?.jsonPrimitive?.contentOrNull)
        assertTrue(sj.toString().contains("SQLite 3"))
    }

    @Test
    fun `Swift demangle 常见形态子集`() {
        assertEquals("Swift.String", ReaMiscParsers.demangleSwift("_TtC5Swift6String"))
        assertEquals("_NotSwift", ReaMiscParsers.demangleSwift("_NotSwift"))
    }

    @Test
    fun `EVM 选择子启发式`() {
        // PUSH4 0x63 + selector bytes
        val evm = write("runtime.bin", byteArrayOf(0x63, 0x1a, 0x2b, 0x3c, 0x4d, 0x00))
        val json = ReaMiscParsers.renderEvm("inspect_evm_interface", evm)
        assertEquals("true", json["ok"]?.jsonPrimitive?.contentOrNull)
        assertTrue(json.toString().contains("1a2b3c4d"))
    }
}
