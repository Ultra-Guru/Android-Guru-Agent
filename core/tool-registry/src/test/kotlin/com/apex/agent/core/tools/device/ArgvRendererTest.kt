package com.apex.agent.core.tools.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ═══ S02 ArgvRenderer —— 渲染与注入拒绝测试 ═══
 *
 * 校验三层纪律：
 * 1. 参数白名单（kind 校验 / 未知参数拒绝 / 缺参拒绝）；
 * 2. 元字符拒绝（`;` `|` `$` 反引号换行 —— argv 语义下全部非法）；
 * 3. 空 flag 语法（渲染为空串的元素丢弃，如 keep_data="" → 无 -k）。
 */
class ArgvRendererTest {

    private val intent = DeviceIntent(
        id = "package.uninstall",
        argv = listOf("pm", "uninstall", "{keep_data}", "{package}"),
        risk = DeviceRisk.CRITICAL,
        requiredPrivilege = DevicePrivilege.SHIZUKU,
        reversible = false,
        preflight = listOf(listOf("pm", "list", "packages", "{package}")),
        verify = listOf(listOf("pm", "list", "packages", "{package}")),
        rollbackHint = "重装",
        blastRadius = "卸载应用",
        params = listOf(
            IntentParam(name = "keep_data", kind = ParamKind.ENUM, values = listOf("-k", ""), required = false, default = ""),
            IntentParam(name = "package", kind = ParamKind.PACKAGE)
        )
    )

    @Test
    fun `枚举参数渲染且空 flag 元素被丢弃`() {
        val withKeep = ArgvRenderer.render(intent.argv, mapOf("keep_data" to "-k", "package" to "com.a.b"))
        assertEquals(listOf("pm", "uninstall", "-k", "com.a.b"), withKeep)
        val withoutKeep = ArgvRenderer.render(intent.argv, mapOf("keep_data" to "", "package" to "com.a.b"))
        assertEquals(listOf("pm", "uninstall", "com.a.b"), withoutKeep)
    }

    @Test
    fun `withDefaults 填充 manifest 声明的默认值`() {
        val merged = ArgvRenderer.withDefaults(intent, mapOf("package" to "com.a.b"))
        assertEquals("-k可选枚举默认空串应被填充", "", merged["keep_data"])
        val rendered = ArgvRenderer.render(intent.argv, merged)
        assertEquals(listOf("pm", "uninstall", "com.a.b"), rendered)
    }

    @Test
    fun `未知参数被拒绝`() {
        val errors = ArgvRenderer.validateParams(intent, mapOf("package" to "com.a.b", "evil" to "x"))
        assertEquals(1, errors.size)
        assertTrue(errors[0].contains("未知参数 'evil'"))
    }

    @Test
    fun `缺必填参数被拒绝`() {
        val errors = ArgvRenderer.validateParams(intent, emptyMap())
        assertTrue(errors.isNotEmpty())
        assertTrue(errors.any { it.contains("缺少必填参数 'package'") })
    }

    @Test
    fun `包名白名单拒绝注入形态`() {
        val errors = ArgvRenderer.validateParams(
            intent,
            mapOf("package" to "com.a.b; rm -rf /")
        )
        assertTrue(errors.isNotEmpty())
        val badMeta = ArgvRenderer.validateParams(intent, mapOf("package" to "com.a.`id`"))
        assertTrue(badMeta.isNotEmpty())
    }

    @Test
    fun `枚举值域外的值被拒绝`() {
        val errors = ArgvRenderer.validateParams(
            intent,
            mapOf("keep_data" to "--evil-flag", "package" to "com.a.b")
        )
        assertEquals(1, errors.size)
        assertTrue(errors[0].contains("-k"))
    }

    @Test
    fun `render 阶段元字符值直接抛异常（防御纵深）`() {
        val e1 = runCatching {
            ArgvRenderer.render(listOf("cat", "{path}"), mapOf("path" to "/sdcard/x;rm"))
        }.exceptionOrNull()
        assertTrue("元字符值应抛异常", e1 is IllegalArgumentException)
        val e2 = runCatching {
            ArgvRenderer.render(listOf("cat", "{path}"), mapOf("path" to "/sdcard/x\$y"))
        }.exceptionOrNull()
        assertTrue("美元符应抛异常", e2 is IllegalArgumentException)
        val e3 = runCatching {
            ArgvRenderer.render(listOf("cat", "{path}"), mapOf("path" to "/sdcard/a|b"))
        }.exceptionOrNull()
        assertTrue("管道符应抛异常", e3 is IllegalArgumentException)
    }

    @Test
    fun `未知占位符 render 抛异常`() {
        val e = runCatching {
            ArgvRenderer.render(listOf("pm", "list", "{nope}"), mapOf("x" to "y"))
        }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException)
    }

    @Test
    fun `renderAll 逐组渲染 preflight 模板`() {
        val rendered = ArgvRenderer.renderAll(
            intent.preflight,
            mapOf("keep_data" to "", "package" to "com.a.b")
        )
        assertEquals(listOf(listOf("pm", "list", "packages", "com.a.b")), rendered)
    }

    @Test
    fun `user 与 setting-key 与 path 各自的域校验`() {
        val userIntent = DeviceIntent(
            id = "package.uninstall_for_user", argv = listOf("pm", "x", "--user", "{user}"),
            risk = DeviceRisk.CRITICAL, requiredPrivilege = DevicePrivilege.SHIZUKU, reversible = false,
            blastRadius = "x", rollbackHint = "y",
            params = listOf(
                IntentParam(name = "user", kind = ParamKind.USER, required = false, default = "0"),
                IntentParam(name = "key", kind = ParamKind.SETTING_KEY, required = false, default = "a.b"),
                IntentParam(name = "path", kind = ParamKind.PATH, required = false, default = "/sdcard/a")
            )
        )
        assertTrue(
            ArgvRenderer.validateParams(userIntent, mapOf("user" to "0")).isEmpty()
        )
        assertTrue(
            "非数字 user 应拒绝",
            ArgvRenderer.validateParams(userIntent, mapOf("user" to "abc")).isNotEmpty()
        )
        assertTrue(
            "相对路径应拒绝",
            ArgvRenderer.validateParams(userIntent, mapOf("path" to "sdcard/x")).isNotEmpty()
        )
        assertTrue(
            ".. 穿越应拒绝",
            ArgvRenderer.validateParams(userIntent, mapOf("path" to "/sdcard/../data/x")).isNotEmpty()
        )
        assertTrue(
            "非法 setting key 应拒绝",
            ArgvRenderer.validateParams(userIntent, mapOf("key" to "a b")).isNotEmpty()
        )
    }

    @Test
    fun `payload kind 只限长度不限字符（stdin 无注入面）`() {
        val payloadIntent = DeviceIntent(
            id = "file.write", argv = listOf("tee", "{path}"),
            risk = DeviceRisk.HIGH, requiredPrivilege = DevicePrivilege.SHIZUKU, reversible = true,
            blastRadius = "x",
            params = listOf(
                IntentParam(name = "path", kind = ParamKind.PATH),
                IntentParam(name = "content", kind = ParamKind.PAYLOAD)
            )
        )
        assertTrue(
            "多行内容是合法 payload（stdin 不进 argv）",
            ArgvRenderer.validateParams(
                payloadIntent,
                mapOf("path" to "/sdcard/a.txt", "content" to "line1\nline2; rm | x")
            ).isEmpty()
        )
        assertTrue(
            "超长 payload 拒绝",
            ArgvRenderer.validateParams(
                payloadIntent,
                mapOf("path" to "/sdcard/a.txt", "content" to "x".repeat(1_048_577))
            ).isNotEmpty()
        )
    }
}
