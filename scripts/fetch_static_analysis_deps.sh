#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# fetch_static_analysis_deps.sh —— static-analysis job 的 Maven 依赖拉取
#
# 单一事实源是 scripts/static-analysis-deps.txt（每行一个 Maven 坐标路径）。
# 本脚本只做三件事：
#   1) 逐行读清单，已在 /tmp/apex-libs 的 jar 跳过（缓存命中路径零网络）；
#   2) 缺的用 curl 拉，失败即 exit 1（绝不带病继续编译）；
#   3) 全部就绪后打印一行统计。
#
# 为什么要独立成脚本（#353-⑤）：
#   - 旧实现把 jar 清单写死在 ci.yml 的两处（缓存命中检查 + 下载循环），
#     加一个 jar 要同步改两处，且 actions/cache 的 key 是静态字符串
#     `kotlinc-2.3.21-libs-v1` —— 忘了 bump 就永远命中旧缓存、每次都重下；
#   - 现在 key 用 hashFiles('scripts/static-analysis-deps.txt')，清单变则
#     key 变；清单与循环同源，不存在「两处不同步」。
#
# 用法：scripts/fetch_static_analysis_deps.sh [目标目录]
#       目标目录缺省 /tmp/apex-libs
# 退出码：0 全部就绪；1 清单缺失/为空/下载失败
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

MANIFEST="$(cd "$(dirname "$0")" && pwd)/static-analysis-deps.txt"
DEST="${1:-/tmp/apex-libs}"
REPO_BASE="${MAVEN_BASE:-https://repo1.maven.org/maven2}"

fail() { echo "[static-analysis-deps] ❌ $*" >&2; exit 1; }

[ -f "$MANIFEST" ] || fail "manifest not found: $MANIFEST"

# 读清单：去注释 / 去空行 / 去行尾空白（CRLF 兼容）
mapfile -t DEPS < <(sed -e 's/\r$//' -e 's/[[:space:]]*$//' "$MANIFEST" \
  | grep -v -e '^[[:space:]]*#' -e '^[[:space:]]*$')
[ "${#DEPS[@]}" -gt 0 ] || fail "manifest has no entries: $MANIFEST"

mkdir -p "$DEST"

missing=0
for dep in "${DEPS[@]}"; do
  fname="$(basename "$dep")"
  if [ -f "$DEST/$fname" ]; then
    continue
  fi
  missing=$((missing + 1))
  echo "[static-analysis-deps] downloading $fname"
  curl -fsSL --retry 3 --retry-delay 2 --connect-timeout 30 \
    "$REPO_BASE/$dep" -o "$DEST/$fname" \
    || fail "download failed: $REPO_BASE/$dep"
  # 空文件是典型的「HTTP 200 但内容是错误页」——必须拦住
  [ -s "$DEST/$fname" ] || fail "downloaded file is empty: $fname"
done

if [ "$missing" -eq 0 ]; then
  echo "[static-analysis-deps] ✅ all ${#DEPS[@]} jars restored from cache"
else
  echo "[static-analysis-deps] ✅ ${#DEPS[@]} jars ready ($missing downloaded)"
fi
