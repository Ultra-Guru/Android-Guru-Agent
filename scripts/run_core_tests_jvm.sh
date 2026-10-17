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

# 手工拼装 classpath（$LIBS 为本地维护的 jar 目录，不走 Gradle 解析）。
# #337：补 mockwebserver —— core:tool-registry 的 AppDownloadInstallToolTest
# 用 MockWebServer 假扮 APK 下载源（Gradle 侧 testImplementation
# libs.mockwebserver，坐标对账 gradle/libs.versions.toml：mockwebserver 与
# okhttp 同用 okhttp=4.12.0 版本 ref）。其传递依赖 okhttp / junit /
# kotlin-stdlib-jdk8 均已在下方清单或 KOTLIN_HOME/lib；okio-jvm（okhttp 带入）
# 亦已在清单 —— 无需额外 jar。
CP_JARS=(
  "$LIBS/kotlinx-coroutines-core-jvm-1.9.0.jar"
  "$LIBS/kotlinx-serialization-json-jvm-1.7.3.jar"
  "$LIBS/kotlinx-serialization-core-jvm-1.7.3.jar"
  "$LIBS/annotations-24.1.0.jar"
  "$LIBS/junit-4.13.2.jar"
  "$LIBS/hamcrest-core-1.3.jar"
  "$LIBS/kotlinx-coroutines-test-jvm-1.9.0.jar"
  "$LIBS/okhttp-4.12.0.jar"
  "$LIBS/okhttp-sse-4.12.0.jar"
  "$LIBS/okio-jvm-3.6.0.jar"
  "$LIBS/mockwebserver-4.12.0.jar"
)
CP="$(IFS=:; printf '%s' "${CP_JARS[*]}")"

# 缺 jar 先行明确报错（#337 教训：classpath 手工拼、无人对账时，缺件会让
# kotlinc 抛一串 unresolved reference 连坐整个套件 —— 本地镜像红而 CI 绿）。
# 补齐方式：从 Gradle 缓存或 Maven Central 复制到 $LIBS，例如
#   ~/.gradle/caches/modules-2/files-2.1/com.squareup.okhttp3/mockwebserver/4.12.0/<hash>/mockwebserver-4.12.0.jar
missing=()
for j in "${CP_JARS[@]}"; do
  [ -f "$j" ] || missing+=("$j")
done
if [ "${#missing[@]}" -gt 0 ]; then
  echo "error: classpath 缺少以下 jar（LIBS=$LIBS）:" >&2
  printf '  %s\n' "${missing[@]}" >&2
  exit 1
fi

OUT=$(mktemp -d)
# #337：临时目录清理改 trap EXIT —— 原先 java|tail 管道在 set -euo pipefail
# 下失败即整脚本退出，rm -rf 永远不可达（编译/测试失败均泄漏临时目录）。
# 单引号让 $OUT 在 trap 触发时求值（此刻已赋值，正常/异常路径统一清理）。
trap 'rm -rf "$OUT"' EXIT

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
# #337：去掉 tail 管道（pipefail 下 JUnit 退出码被管道吞掉，PIPESTATUS
# 依赖脆弱且失败路径早退）—— 完整输出落日志文件再摘尾，状态捕获用 || 完成。
LOG="$OUT/junit.log"
STATUS=0
java -Xmx2g -cp "$RUN_CP" org.junit.runner.JUnitCore $TESTS >"$LOG" 2>&1 || STATUS=$?
tail -30 "$LOG"
exit $STATUS
