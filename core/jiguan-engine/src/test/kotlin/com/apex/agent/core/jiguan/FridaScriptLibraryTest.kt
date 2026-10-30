package com.apex.agent.core.jiguan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Frida 脚本库 + 沙盒校验器回归 —— 模板语义（注入安全 / 结构正确）与
 * fail-closed 校验纪律。
 */
class FridaScriptLibraryTest {

    // ── 模板 ──────────────────────────────────────────────────────

    @Test
    fun `javaHook LOG template carries class and method`() {
        val script = FridaScriptLibrary.javaHook(
            HookSpec(kind = HookKind.JAVA_METHOD, pid = 1, className = "com.x.EncryptUtil", methodName = "aes"),
        )
        assertTrue(script.contains("Java.use(\"com.x.EncryptUtil\")"))
        assertTrue(script.contains("\"aes\""))
        assertTrue(script.contains("Java.perform"))
        // 注入安全：类名里的引号被转义。
        val hostile = FridaScriptLibrary.javaHook(
            HookSpec(kind = HookKind.JAVA_METHOD, pid = 1, className = "a\";evil();\"", methodName = "m"),
        )
        assertFalse(hostile.contains("\"a\";evil();\"\""))
        assertTrue(hostile.contains("\\\""))
    }

    @Test
    fun `javaHook REPLACE template injects return literal`() {
        val script = FridaScriptLibrary.javaHook(
            HookSpec(
                kind = HookKind.JAVA_METHOD, pid = 1, className = "C", methodName = "m",
                action = "REPLACE", actionPayload = "true",
            ),
        )
        assertTrue(script.contains("return true;"))
    }

    @Test
    fun `jsLiteral passes only safe literals`() {
        assertEquals("null", FridaScriptLibrary.jsLiteral("null"))
        assertEquals("42", FridaScriptLibrary.jsLiteral("42"))
        assertEquals("\"abc\"", FridaScriptLibrary.jsLiteral("abc"))
        // 危险字面量降级为字符串 —— 永不执行。
        assertEquals("\"process.kill()\"", FridaScriptLibrary.jsLiteral("process.kill()"))
    }

    @Test
    fun `javaEnumerateClasses embeds filter and cap`() {
        val script = FridaScriptLibrary.javaEnumerateClasses("Encrypt")
        assertTrue(script.contains("Encrypt"))
        assertTrue(script.contains("seen >= 4000"))
    }

    @Test
    fun `nativeHook builds concatenation logging`() {
        val script = FridaScriptLibrary.nativeHook("libnative.so", "Java_x_sign", onEnterLog = true, onLeaveLog = true)
        assertTrue(script.contains("Module.findExportByName(MOD, SYM)"))
        assertTrue(script.contains("Interceptor.attach"))
        assertTrue(script.contains("\" enter args=\""))
    }

    @Test
    fun `sslUnpinning covers four layers`() {
        val script = FridaScriptLibrary.sslUnpinning()
        assertTrue(script.contains("X509TrustManager"))
        assertTrue(script.contains("CertificatePinner"))
        assertTrue(script.contains("onReceivedSslError"))
        assertTrue(script.contains("SSLContext"))
    }

    @Test
    fun `hardwareBreakpoint embeds address`() {
        val script = FridaScriptLibrary.hardwareBreakpoint(0x7fff1000L)
        assertTrue(script.contains("ptr(\"0x7fff1000\")"))
        assertTrue(script.contains("setHardwareBreakpoint"))
    }

    // ── 沙盒校验 ──────────────────────────────────────────────────

    @Test
    fun `validator accepts generated templates`() {
        val templates = listOf(
            FridaScriptLibrary.javaHook(HookSpec(HookKind.JAVA_METHOD, 1, "com.a.B", "m")),
            FridaScriptLibrary.javaEnumerateClasses("X"),
            FridaScriptLibrary.nativeHook("libx.so", "sym", true, true),
            FridaScriptLibrary.sslUnpinning(),
            FridaScriptLibrary.hardwareBreakpoint(0x1000),
            FridaScriptLibrary.javaCallMethod("com.a.B", "m", "[1]", true),
        )
        templates.forEach { script ->
            val verdict = SandboxValidator.validate(script)
            assertTrue("template should pass: ${verdict.reason}\n$script", verdict.ok)
        }
    }

    @Test
    fun `validator rejects unbalanced script`() {
        val verdict = SandboxValidator.validate("function f() { if (true) { ")
        assertFalse(verdict.ok)
        assertTrue(verdict.reason.contains("brace"))
    }

    @Test
    fun `validator rejects banned apis`() {
        val bad = SandboxValidator.validate("(function(){ Process.kill(); })();")
        assertFalse(bad.ok)
        assertTrue(bad.reason.contains("Process.kill"))
    }

    @Test
    fun `validator rejects oversized script`() {
        val huge = "var x = 1;\n" + "//".repeat(SandboxValidator.MAX_SCRIPT_BYTES)
        val verdict = SandboxValidator.validate(huge)
        assertFalse(verdict.ok)
        assertTrue(verdict.reason.contains("budget"))
    }

    @Test
    fun `validator ignores braces in strings and comments`() {
        val script = """
            (function () {
              // brace in comment { } fine
              var s = "unbalanced {{ in string";
              Java.perform(function () { console.log("ok"); });
            })();
        """.trimIndent()
        assertTrue(SandboxValidator.validate(script).ok)
    }

    @Test
    fun `bracketBalance flags unterminated string`() {
        val balance = SandboxValidator.bracketBalance("var s = \"open")
        assertTrue(balance.unterminatedString)
    }

    @Test
    fun `bracketBalance counts correctly with js comments`() {
        val script = "function a() { /* ( ( */ var x = (1+2); } // )\n"
        val balance = SandboxValidator.bracketBalance(script)
        assertEquals(0, balance.brace)
        assertEquals(0, balance.paren)
        assertFalse(balance.unterminatedString)
    }
}
