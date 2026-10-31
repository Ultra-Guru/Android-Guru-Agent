#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════════════
# compile_privilege_jvm.sh — local kotlinc type-check for :platform:privilege
#
# T92 后接续（P1/P2 批次）：该模块此前无本地编译脚本（CI Gradle 兜底）。
# 依赖：android.jar（框架）+ core 模块产物 + Shizuku/javax.inject/dagger 的
# 本地 stub（从不提交）。Stub 只保证签名形状 —— 运行语义由 CI/真机把关。
# ═══════════════════════════════════════════════════════════════════════════
set -euo pipefail
cd "$(dirname "$0")/.."

KOTLINC="${KOTLINC:-$HOME/.local/kotlinc/bin/kotlinc}"
LIBS="${LIBS:-$HOME/kotlin-libs}"
ANDROID_JAR="${ANDROID_JAR:-$HOME/android-sdk/platforms/android-35/android.jar}"
PLUGIN="$HOME/.local/kotlinc/lib/kotlinx-serialization-compiler-plugin.jar"
OUT=$(mktemp -d)
STUBS=$(mktemp -d)

# ── core 模块产物（logging + tool-registry 的可见性传递）──
"$KOTLINC" -jvm-target 17 -nowarn -Xplugin="$PLUGIN" \
  core/logging/src/main/kotlin \
  core/llm-adapter/src/main/kotlin \
  core/tool-registry/src/main/kotlin \
  -cp "$LIBS/kotlinx-coroutines-core-jvm-1.9.0.jar:$LIBS/kotlinx-serialization-json-jvm-1.7.3.jar:$LIBS/kotlinx-serialization-core-jvm-1.7.3.jar:$LIBS/annotations-24.1.0.jar:$LIBS/okhttp-4.12.0.jar:$LIBS/okhttp-sse-4.12.0.jar:$LIBS/okio-jvm-3.6.0.jar" \
  -d "$OUT/core-classes" 2>&1 | { grep -E "error:" || true; } | head -30

# ── Shizuku / inject stubs（签名形状对齐 Shizuku API 13）──
mkdir -p "$STUBS/rikka/shizuku" "$STUBS/moe/shizuku/server" "$STUBS/javax/inject" "$STUBS/dagger/hilt/android/qualifiers" "$STUBS/androidx/annotation"
cat > "$STUBS/rikka/shizuku/Shizuku.kt" <<'STUB'
package rikka.shizuku
import moe.shizuku.server.IRemoteProcess
object Shizuku {
    fun addBinderDeadListener(listener: Runnable) {}
    fun addBinderReceivedListenerSticky(listener: Runnable) {}
    fun interface RequestPermissionResultListener { fun onRequestPermissionResult(requestCode: Int, result: Int) }
    fun addRequestPermissionResultListener(listener: RequestPermissionResultListener) {}
    fun checkSelfPermission(): Int = 0
    fun getBinder(): android.os.IBinder? = null
    fun isPreV(): Boolean = false
    fun isPreV11(): Boolean = false
    fun newProcess(cmd: Array<String>, env: Array<String>?, dir: String?): IRemoteProcess? = null
    fun pingBinder(): Boolean = false
    fun requestPermission(requestCode: Int): Int = 0
}
STUB
cat > "$STUBS/moe/shizuku/server/IShizukuService.kt" <<'STUB'
package moe.shizuku.server
interface IShizukuService {
    fun newProcess(cmd: Array<String>, env: Array<String>?, dir: String?): IRemoteProcess
    fun pingBinder(): Boolean = true
    abstract class Stub : IShizukuService {
        companion object {
            fun asInterface(binder: android.os.IBinder): IShizukuService? = null
        }
    }
}
STUB
# 形状对齐 dev.rikka.shizuku:aidl 13.1.5 编译产物。必须是 JAVA stub：
# AIDL 生成的是 Java 接口（流通道为 getXxx() getter）——主代码的
# remote.outputStream 合成属性访问只在 Java 声明上成立，Kotlin 接口写
# getter 方法会炸主代码、写 val 又会炸真依赖下的测试（CI 曾红：
# overrides nothing）。
cat > "$STUBS/moe/shizuku/server/IRemoteProcess.java" <<'STUB'
package moe.shizuku.server;

import android.os.IInterface;
import android.os.ParcelFileDescriptor;

public interface IRemoteProcess extends IInterface {
    ParcelFileDescriptor getOutputStream();
    ParcelFileDescriptor getInputStream();
    ParcelFileDescriptor getErrorStream();
    int waitFor();
    int exitValue();
    void destroy();
    boolean alive();
    boolean waitForTimeout(long timeout, String unit);
}
STUB
cat > "$STUBS/androidx/annotation/RequiresApi.kt" <<'STUB'
package androidx.annotation
annotation class RequiresApi(val value: Int = 1)
STUB
cat > "$STUBS/javax/inject/Inject.kt" <<'STUB'

package javax.inject
annotation class Inject
STUB
cat > "$STUBS/javax/inject/Singleton.kt" <<'STUB'
package javax.inject
annotation class Singleton
STUB
cat > "$STUBS/dagger/hilt/android/qualifiers/ApplicationContext.kt" <<'STUB'
package dagger.hilt.android.qualifiers
annotation class ApplicationContext
STUB

echo "── compiling platform/privilege (main) ──"
"$KOTLINC" -jvm-target 17 -nowarn -Xplugin="$PLUGIN" \
  "$STUBS" \
  platform/privilege/src/main/kotlin \
  -cp "$OUT/core-classes:$ANDROID_JAR:$LIBS/kotlinx-coroutines-core-jvm-1.9.0.jar:$LIBS/kotlinx-serialization-json-jvm-1.7.3.jar:$LIBS/kotlinx-serialization-core-jvm-1.7.3.jar:$LIBS/annotations-24.1.0.jar" \
  -d "$OUT/priv-classes" 2>&1 | { grep -E "error:" || true; } | head -40

echo "── privilege OK: $(find "$OUT/priv-classes" -name '*.class' | wc -l) classes ──"
rm -rf "$OUT" "$STUBS"
