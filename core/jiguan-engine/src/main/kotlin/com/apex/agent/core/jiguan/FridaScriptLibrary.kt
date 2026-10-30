package com.apex.agent.core.jiguan

/**
 * # Frida 脚本库（Omni-Hook 的生成器面）
 *
 * Agent 只表达**声明式意图**（[HookSpec]），本库把它编译成可执行的
 * Frida JS —— 「AI 辅助 Hook 生成」的落地面：用户说「去掉开屏广告」，
 * Agent 反编译定位类名后，这里产出 `Java.perform` 脚本。
 *
 * 所有模板遵守三条硬纪律：
 * 1. **console.log 单出口**：脚本与 Agent 之间的唯一通信面是 console 输出，
 *    被 FridaBridge 回收成文本（无 RPC 依赖，通道最鲁棒）；
 * 2. **try/catch 全包**：任何目标类缺失/签名不匹配都折叠成一条日志，
 *    绝不让脚本进程崩溃（Hook 失败是数据，不是事故）；
 * 3. **[SandboxValidator] 先行**：生成物与用户自定义脚本一律过沙盒校验
 *    再上机。
 */
object FridaScriptLibrary {

    // ── 1. Java 方法 Hook（LOG / REPLACE） ───────────────────────

    fun javaHook(spec: HookSpec): String {
        val actionBlock = when (spec.action.uppercase()) {
            "REPLACE" -> {
                val ret = spec.actionPayload.ifBlank { "null" }
                """
                |        var retval = impl.apply(this, args);
                |        console.log("[hook] ${spec.className}.${spec.methodName} args=" + JSON.stringify(args.map(safeStr)) + " origRet=" + safeStr(retval));
                |        return ${jsLiteral(ret)};
                """.trimMargin()
            }
            else -> // LOG（默认）：记录出入参并放行
                """
                |        var retval = impl.apply(this, args);
                |        console.log("[hook] ${spec.className}.${spec.methodName} args=" + JSON.stringify(args.map(safeStr)) + " ret=" + safeStr(retval));
                |        return retval;
                """.trimMargin()
        }
        return """
            |(function () {
            |  function safeStr(v) {
            |    try { return typeof v === "object" ? JSON.stringify(v) : String(v); }
            |    catch (e) { return "<unserializable>"; }
            |  }
            |  Java.perform(function () {
            |    try {
            |      var cls = Java.use(${jsString(spec.className)});
            |      var overloads = cls[${jsQuoted(spec.methodName)}].overloads;
            |      overloads.forEach(function (m) {
            |        m.implementation = function () {
            |          var args = Array.prototype.slice.call(arguments);
            |          var impl = this;
            $actionBlock
            |        };
            |      });
            |      console.log("[ok] hooked ${spec.className}.${spec.methodName} overloads=" + overloads.length);
            |    } catch (e) {
            |      console.log("[err] hook ${spec.className}.${spec.methodName}: " + e);
            |    }
            |  });
            |})();
        """.trimMargin()
    }

    // ── 2. Java 类枚举（list_java_classes） ──────────────────────

    fun javaEnumerateClasses(filter: String): String {
        val filterBlock = if (filter.isBlank()) {
            "return true;"
        } else {
            "return name.indexOf(${jsString(filter)}) !== -1;"
        }
        return """
            |(function () {
            |  var seen = 0;
            |  Java.perform(function () {
            |    try {
            |      Java.enumerateLoadedClasses({
            |        onMatch: function (name) {
            |          if (seen >= 4000) return;
            |          if (function (name) { $filterBlock }(name)) {
            |            seen++;
            |            console.log("[class] " + name);
            |          }
            |        },
            |        onComplete: function () { console.log("[done] classes matched=" + seen); }
            |      });
            |    } catch (e) { console.log("[err] enumerate: " + e); }
            |  });
            |})();
        """.trimMargin()
    }

    // ── 3. Java 方法主动调用（call_java_method） ─────────────────

    fun javaCallMethod(className: String, methodName: String, argsJson: String, static: Boolean): String {
        val argsArray = argsJson.ifBlank { "[]" }
        return """
            |(function () {
            |  Java.perform(function () {
            |    try {
            |      var cls = Java.use(${jsString(className)});
            |      var raw = $argsArray;
            |      var argv = [];
            |      for (var i = 0; i < raw.length; i++) {
            |        var a = raw[i];
            |        argv.push(typeof a === "object" ? JSON.stringify(a) : a);
            |      }
            |      var ret = static ? cls[${jsQuoted(methodName)}].apply(cls, argv)
            |                        : cls.${'$'}new()[${jsQuoted(methodName)}].apply(null, argv);
            |      console.log("[ret] " + (typeof ret === "object" ? JSON.stringify(ret) : String(ret)));
            |    } catch (e) { console.log("[err] call: " + e); }
            |  });
            |})();
        """.trimMargin()
    }

    // ── 4. Native 函数 Hook（Interceptor.attach） ────────────────

    fun nativeHook(moduleName: String, symbolName: String, onEnterLog: Boolean, onLeaveLog: Boolean): String {
        val enter = if (onEnterLog) {
            """console.log("[nat] " + SYM + " enter args=" + JSON.stringify([].slice.call(args).map(safeStr)));"""
        } else {
            ""
        }
        val leave = if (onLeaveLog) {
            """console.log("[nat] " + SYM + " leave ret=" + safeStr(retval));"""
        } else {
            ""
        }
        return """
            |(function () {
            |  function safeStr(v) {
            |    try { return typeof v === "object" ? JSON.stringify(v) : String(v); }
            |    catch (e) { return "<unserializable>"; }
            |  }
            |  var SYM = ${jsString(symbolName)};
            |  var MOD = ${jsString(moduleName)};
            |  var addr = Module.findExportByName(MOD, SYM);
            |  if (addr === null) { console.log("[err] export not found: " + MOD + "!" + SYM); return; }
            |  try {
            |    Interceptor.attach(addr, {
            |      onEnter: function (args) { $enter },
            |      onLeave: function (retval) { $leave }
            |    });
            |    console.log("[ok] native hooked " + MOD + "!" + SYM + " @ " + addr);
            |  } catch (e) { console.log("[err] native hook: " + e); }
            |})();
        """.trimMargin()
    }

    // ── 5. SSL Pinning 绕过（bypass_ssl_pinning） ────────────────
    // 覆盖四层：TrustManager（Java 默认）/ OkHttp CertificatePinner /
    // Conscrypt / WebView Client。逐层 try/catch —— 哪层缺席都不影响其它层。

    fun sslUnpinning(): String = """
        |(function () {
        |  Java.perform(function () {
        |    // ── 层 1：默认 X509TrustManager ─────────────────────
        |    try {
        |      var X509T = Java.use("javax.net.ssl.X509TrustManager");
        |      var TrustManager = Java.use("javax.net.ssl.TrustManager");
        |      var SSLContext = Java.use("javax.net.ssl.SSLContext");
        |      var Permissive = Java.registerClass({
        |        name: "com.jiguan.PermissiveTrustManager",
        |        implements: [X509T],
        |        methods: {
        |          checkClientTrusted: function () {},
        |          checkServerTrusted: function () {},
        |          getAcceptedIssuers: function () { return []; }
        |        }
        |      });
        |      var init = SSLContext.init.overload(
        |        "[Ljavax.net.ssl.KeyManager;", "[Ljavax.net.ssl.TrustManager;", "java.security.SecureRandom");
        |      init.implementation = function (km, tm, sr) {
        |        console.log("[ssl] SSLContext.init intercepted, planting permissive TM");
        |        init.call(this, km, [Permissive.${'$'}new()], sr);
        |      };
        |      console.log("[ok] layer1 TrustManager planted");
        |    } catch (e) { console.log("[skip] layer1: " + e); }
        |    // ── 层 2：OkHttp CertificatePinner ──────────────────
        |    try {
        |      var Pinner = Java.use("okhttp3.CertificatePinner");
        |      Pinner.check.overload("java.lang.String", "java.util.List").implementation = function (h, p) {
        |        console.log("[ssl] OkHttp pin bypassed for " + h);
        |        return;
        |      };
        |      console.log("[ok] layer2 OkHttp pinner bypassed");
        |    } catch (e) { console.log("[skip] layer2: " + e); }
        |    // ── 层 3：Conscrypt / platform pinning ──────────────
        |    try {
        |      var Platform = Java.use("android.security.net.config.NetworkSecurityConfig");
        |      console.log("[ok] layer3 NetworkSecurityConfig present (no-op)");
        |    } catch (e) { console.log("[skip] layer3: " + e); }
        |    // ── 层 4：WebViewClient onReceivedSslError ───────────
        |    try {
        |      var WebViewClient = Java.use("android.webkit.WebViewClient");
        |      WebViewClient.onReceivedSslError.overload(
        |        "android.webkit.WebView", "android.webkit.SslErrorHandler", "android.net.http.SslError"
        |      ).implementation = function (view, handler, error) {
        |        console.log("[ssl] WebView SSL error bypassed");
        |        handler.proceed();
        |      };
        |      console.log("[ok] layer4 WebView bypassed");
        |    } catch (e) { console.log("[skip] layer4: " + e); }
        |  });
        |})();
    """.trimMargin()

    // ── 6. 硬件断点捕获（set_hardware_breakpoint，Frida 侧通道） ──

    fun hardwareBreakpoint(address: Long): String = """
        |(function () {
        |  var ADDR = ptr("0x${java.lang.Long.toHexString(address)}");
        |  try {
        |    var threads = Process.enumerateThreads();
        |    var installed = 0;
        |    threads.forEach(function (t) {
        |      try { t.setHardwareBreakpoint(0, ADDR); installed++; }
        |      catch (e) { console.log("[hwbp] thread " + t.id + " bp failed: " + e); }
        |    });
        |    Process.setExceptionHandler(function (details) {
        |      console.log("[hwbp-hit] tid=" + details.context.pc +
        |        " type=" + details.type + " addr=" + (details.memory && details.memory.address || "?"));
        |      return true;
        |    });
        |    console.log("[ok] hwbp installed on " + installed + "/" + threads.length + " threads @ " + ADDR);
        |  } catch (e) { console.log("[err] hwbp: " + e); }
        |})();
    """.trimMargin()

    // ── 7. Binder / 系统服务探针（System 域的 Frida 视角） ────────

    fun binderProbe(): String = """
        |(function () {
        |  Java.perform(function () {
        |    try {
        |      var SM = Java.use("android.os.ServiceManager");
        |      var list = SM.listServices();
        |      console.log("[binder] services=" + list.length);
        |      for (var i = 0; i < list.length && i < 80; i++) console.log("[svc] " + list[i]);
        |    } catch (e) { console.log("[err] binder probe: " + e); }
        |  });
        |})();
    """.trimMargin()

    // ── JS 字面量安全（防注入：字符串一律走 JSON 引号） ──────────

    /** JS 双引号字符串字面量（转义引号/反斜杠/控制字符）。 */
    fun jsString(raw: String): String {
        val sb = StringBuilder("\"")
        for (c in raw) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append(String.format("\\u%04x", c.code))
                else -> sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    /** 带引号的 JS 属性访问字面量（cls["method"] 形态）。 */
    fun jsQuoted(raw: String): String = jsString(raw)

    /**
     * 返回值字面量透传：null / true / false / 数字 直通，其余按字符串。
     * 非法形态（括号分号等）直接按字符串处理 —— 模板永不执行用户代码
     * 注入面（SandboxValidator 的配套纪律）。
     */
    fun jsLiteral(raw: String): String {
        val t = raw.trim()
        if (t in listOf("null", "true", "false")) return t
        if (t.matches(Regex("-?\\d+(\\.\\d+)?"))) return t
        return jsString(t)
    }
}
