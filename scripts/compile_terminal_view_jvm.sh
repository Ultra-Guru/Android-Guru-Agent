#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════════════
# compile_terminal_view_jvm.sh — kotlinc type-check for :terminal-view
#
# T88: terminal-view 是 android.* View 层（Canvas/View/InputConnection），
# JVM 上用 android.jar（SDK platforms/android-35）做完整类型检查，无需 Gradle。
# 依赖 terminal-emulator 的纯模型 —— 一并编译。
# ═══════════════════════════════════════════════════════════════════════════
set -euo pipefail
cd "$(dirname "$0")/.."

KOTLINC="${KOTLINC:-$HOME/.local/kotlinc/bin/kotlinc}"
LIBS="${LIBS:-$HOME/kotlin-libs}"
ANDROID_JAR="${ANDROID_JAR:-$HOME/android-sdk/platforms/android-35/android.jar}"
PLUGIN="$HOME/.local/kotlinc/lib/kotlinx-serialization-compiler-plugin.jar"
CP="$LIBS/kotlinx-coroutines-core-jvm-1.9.0.jar:$LIBS/kotlinx-serialization-json-jvm-1.7.3.jar:$LIBS/kotlinx-serialization-core-jvm-1.7.3.jar:$LIBS/annotations-24.1.0.jar:$LIBS/junit-4.13.2.jar:$LIBS/hamcrest-core-1.3.jar:$LIBS/kotlinx-coroutines-test-jvm-1.9.0.jar:$ANDROID_JAR"
OUT=$(mktemp -d)

echo "── compiling terminal-emulator (models) + terminal-view (main+test) ──"
JAVA_OPTS="-Xmx4g" "$KOTLINC" -J-Xmx4g -jvm-target 17 -nowarn -Xplugin="$PLUGIN" \
  terminal-emulator/src/main/kotlin \
  terminal-view/src/main/kotlin \
  terminal-view/src/test/kotlin \
  -cp "$CP" -d "$OUT/classes" 2>&1 | { grep -E "error:" || true; } | head -60

echo "── OK: $(find $OUT/classes -name '*.class' | wc -l) classes ──"
rm -rf "$OUT"
