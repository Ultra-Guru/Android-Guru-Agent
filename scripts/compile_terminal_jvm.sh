#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════════════
# compile_terminal_jvm.sh — local kotlinc type-check for the terminal stack
#
# The sandbox has no Android SDK/Gradle; kotlinc (JVM target 17) gives full
# type-level verification for the pure-JVM modules this task touches:
#   :terminal-emulator   (pure Kotlin, zero Android imports)
#   :terminal-native     (one android.util.Log import — stubbed locally)
#   :platform:terminal   (screen/ pulls vtnative → android.util.Log, stubbed)
#
# android.util.Log is stubbed from a temp dir (never committed); everything
# else compiles against real sources. Main + test in ONE invocation gives
# tests `internal` visibility exactly like Gradle's test source set.
# ═══════════════════════════════════════════════════════════════════════════
set -euo pipefail
cd "$(dirname "$0")/.."

KOTLINC="${KOTLINC:-$HOME/.local/kotlinc/bin/kotlinc}"
LIBS="${LIBS:-$HOME/kotlin-libs}"
PLUGIN="$HOME/.local/kotlinc/lib/kotlinx-serialization-compiler-plugin.jar"
ANDROID_JAR="${ANDROID_JAR:-$HOME/android-sdk/platforms/android-35/android.jar}"
CP="$LIBS/kotlinx-coroutines-core-jvm-1.9.0.jar:$LIBS/kotlinx-serialization-json-jvm-1.7.3.jar:$LIBS/kotlinx-serialization-core-jvm-1.7.3.jar:$LIBS/annotations-24.1.0.jar:$LIBS/junit-4.13.2.jar:$LIBS/hamcrest-core-1.3.jar:$LIBS/kotlinx-coroutines-test-jvm-1.9.0.jar"
OUT=$(mktemp -d)
STUBS=$(mktemp -d)
# T92（merge main #254/#282 后）：platform/terminal 新增 service/ 包（AIDL +
# 前台通知）。本地 JVM 编译用 android.jar（框架真类）+ AIDL/R 的本地 stub
#（从不提交 —— CI 走 Gradle AIDL 代码生成）。
AIDL_DIR="$STUBS/com/apex/agent/platform/terminal/service"
mkdir -p "$AIDL_DIR" "$STUBS/com/apex/agent/platform/terminal"
cat > "$AIDL_DIR/ITerminalService.kt" <<'STUB'
// Local JVM-compile stub of the AIDL-generated ITerminalService (never committed).
package com.apex.agent.platform.terminal.service
import android.os.Binder
import android.os.IBinder
interface ITerminalService {
    fun createSession(backendId: String?, rows: Int, cols: Int, cwd: String?, envAssignments: MutableList<String>?): String
    fun write(sessionId: Long, data: ByteArray?)
    fun writeText(sessionId: Long, text: String?)
    fun resize(sessionId: Long, rows: Int, cols: Int)
    fun closeSession(sessionId: Long, force: Boolean)
    fun listSessions(): String
    fun ping(): String
    fun registerCallback(callback: ITerminalCallback?)
    fun unregisterCallback(callback: ITerminalCallback?)
    abstract class Stub : Binder(), ITerminalService {
        companion object {
            fun asInterface(binder: IBinder): ITerminalService? = null
        }
    }
}
STUB
cat > "$AIDL_DIR/ITerminalCallback.kt" <<'STUB'
// Local JVM-compile stub of the AIDL-generated ITerminalCallback (never committed).
package com.apex.agent.platform.terminal.service
import android.os.Binder
interface ITerminalCallback {
    fun onOutput(sessionId: Long, data: ByteArray?)
    fun onExit(sessionId: Long, exitCode: Int, cause: String?)
    fun onSessionStateChanged(sessionId: Long, state: String?)
    abstract class Stub : Binder(), ITerminalCallback
}
STUB
cat > "$STUBS/com/apex/agent/platform/terminal/R.kt" <<'STUB'
// Local JVM-compile stub of the generated R class (never committed).
package com.apex.agent.platform.terminal
object R {
    object string {
        const val terminal_service_channel_name = 1
        const val terminal_service_channel_desc = 2
        const val terminal_service_notif_title = 3
        const val terminal_service_notif_text = 4
    }
    object drawable {
        const val terminal_service_icon = 1
    }
}
STUB

echo "── compiling terminal-emulator + terminal-native + platform/terminal ──"
# 全栈一次编译内存压力大（UnicodeWidthTables 大区间表 + 800+ 源文件）：-J-Xmx4g
JAVA_OPTS="-Xmx4g" "$KOTLINC" -J-Xmx4g -jvm-target 17 -nowarn -Xplugin="$PLUGIN" \
  "$STUBS" \
  core/logging/src/main/kotlin \
  terminal-emulator/src/main/kotlin \
  terminal-emulator/src/test/kotlin \
  terminal-native/src/main/kotlin \
  terminal-native/src/test/kotlin \
  platform/terminal/src/main/kotlin \
  platform/terminal/src/test/kotlin \
  -cp "$CP:$ANDROID_JAR" -d "$OUT/classes" 2>&1 | { grep -E "error:" || true; } | head -60

echo "── OK: $(find $OUT/classes -name '*.class' | wc -l) classes ──"
rm -rf "$OUT" "$STUBS"
