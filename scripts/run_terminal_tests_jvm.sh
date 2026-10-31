#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════════════
# run_terminal_tests_jvm.sh — JVM 上运行 terminal-emulator 全量 JUnit 测试
#
# T92：本地验证（无 Gradle/设备）。编译 main+test 后跑 JUnitCore，
# 输出失败用例与统计。
# ═══════════════════════════════════════════════════════════════════════════
set -euo pipefail
cd "$(dirname "$0")/.."

KOTLINC="${KOTLINC:-$HOME/.local/kotlinc/bin/kotlinc}"
KOTLIN_HOME="${KOTLIN_HOME:-$HOME/.local/kotlinc}"
LIBS="${LIBS:-$HOME/kotlin-libs}"
PLUGIN="$HOME/.local/kotlinc/lib/kotlinx-serialization-compiler-plugin.jar"
CP="$LIBS/kotlinx-coroutines-core-jvm-1.9.0.jar:$LIBS/kotlinx-serialization-json-jvm-1.7.3.jar:$LIBS/kotlinx-serialization-core-jvm-1.7.3.jar:$LIBS/annotations-24.1.0.jar:$LIBS/junit-4.13.2.jar:$LIBS/hamcrest-core-1.3.jar:$LIBS/kotlinx-coroutines-test-jvm-1.9.0.jar"
OUT=$(mktemp -d)
STUBS=$(mktemp -d)

# terminal-emulator 纯模型 —— 无 android.* 依赖，无需 stub。
mkdir -p "$STUBS"

ANDROID_JAR="${ANDROID_JAR:-$HOME/android-sdk/platforms/android-35/android.jar}"

echo "── compiling terminal-emulator + terminal-view (main + test) ──"
JAVA_OPTS="-Xmx4g" "$KOTLINC" -J-Xmx4g -jvm-target 17 -nowarn -Xplugin="$PLUGIN" \
  terminal-emulator/src/main/kotlin \
  terminal-emulator/src/test/kotlin \
  terminal-view/src/main/kotlin \
  terminal-view/src/test/kotlin \
  -cp "$CP:$ANDROID_JAR" -d "$OUT/classes" 2>&1 | { grep -E "error:" || true; } | head -40

echo "── collecting test classes ──"
RUN_CP="$OUT/classes:$CP:$ANDROID_JAR"
for j in "$KOTLIN_HOME"/lib/kotlin-stdlib.jar "$KOTLIN_HOME"/lib/kotlin-reflect.jar "$KOTLIN_HOME"/lib/kotlinx-serialization-*.jar; do
  [ -f "$j" ] && RUN_CP="$RUN_CP:$j"
done
TESTS=$(find "$OUT/classes" -name '*Test.class' ! -name '*$*' | sed "s|$OUT/classes/||; s|\.class$||; s|/|.|g")

echo "── running JUnit ──"
java -cp "$RUN_CP" org.junit.runner.JUnitCore $TESTS 2>&1 | tail -30

STATUS=${PIPESTATUS[0]}
rm -rf "$OUT" "$STUBS"
exit $STATUS
