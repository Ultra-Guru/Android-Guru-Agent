#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════════════
# run_core_tests_jvm.sh — JVM 上运行 core 模块 JUnit 全量测试
#
# P1/P2 批次新增：core:tool-registry（含 #230 流式门控 / #232 字节预算 /
# #239/#240 特权通道工具回归）+ core:agent-engine 存量。
# ═══════════════════════════════════════════════════════════════════════════
set -euo pipefail
cd "$(dirname "$0")/.."

KOTLINC="${KOTLINC:-$HOME/.local/kotlinc/bin/kotlinc}"
KOTLIN_HOME="${KOTLIN_HOME:-$HOME/.local/kotlinc}"
LIBS="${LIBS:-$HOME/kotlin-libs}"
PLUGIN="$HOME/.local/kotlinc/lib/kotlinx-serialization-compiler-plugin.jar"
CP="$LIBS/kotlinx-coroutines-core-jvm-1.9.0.jar:$LIBS/kotlinx-serialization-json-jvm-1.7.3.jar:$LIBS/kotlinx-serialization-core-jvm-1.7.3.jar:$LIBS/annotations-24.1.0.jar:$LIBS/junit-4.13.2.jar:$LIBS/hamcrest-core-1.3.jar:$LIBS/kotlinx-coroutines-test-jvm-1.9.0.jar:$LIBS/okhttp-4.12.0.jar:$LIBS/mockwebserver-4.12.0.jar:$LIBS/okhttp-sse-4.12.0.jar:$LIBS/okio-jvm-3.6.0.jar"
OUT=$(mktemp -d)

echo "── compiling core (logging → llm → tools → engine) main + test ──"
JAVA_OPTS="-Xmx4g" "$KOTLINC" -J-Xmx4g -jvm-target 17 -nowarn -Xplugin="$PLUGIN" \
  core/logging/src/main/kotlin \
  core/llm-adapter/src/main/kotlin \
  core/llm-adapter/src/test/kotlin \
  core/tool-registry/src/main/kotlin \
  core/tool-registry/src/test/kotlin \
  core/agent-engine/src/main/kotlin \
  core/agent-engine/src/test/kotlin \
  -cp "$CP" -d "$OUT/classes" 2>&1 | { grep -E "error:" || true; } | head -50

echo "── collecting test classes ──"
RUN_CP="$OUT/classes:$CP"
for j in "$KOTLIN_HOME"/lib/kotlin-stdlib.jar "$KOTLIN_HOME"/lib/kotlin-reflect.jar "$KOTLIN_HOME"/lib/kotlinx-serialization-*.jar; do
  [ -f "$j" ] && RUN_CP="$RUN_CP:$j"
done
TESTS=$(find "$OUT/classes" -name '*Test.class' ! -name '*$*' | sed "s|$OUT/classes/||; s|\.class$||; s|/|.|g")

echo "── running JUnit ($TESTS 候选类) ──"
java -Xmx2g -cp "$RUN_CP" org.junit.runner.JUnitCore $TESTS 2>&1 | tail -30

STATUS=${PIPESTATUS[0]}
rm -rf "$OUT"
exit $STATUS
