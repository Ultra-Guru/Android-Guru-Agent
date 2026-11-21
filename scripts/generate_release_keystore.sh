#!/usr/bin/env bash
# ═════════════════════════════════════════════════════════════════════════════
# 生成发布签名 keystore（固定指纹，消除签名漂移）—— S-01：凭据经 env 注入
# ═════════════════════════════════════════════════════════════════════════════
#
# 背景：v1.4.7 之前 release 构建用 signingConfigs.getByName("debug") ——
# debug.keystore 是机器本地的，CI runner 与开发者本机必然不同 →
# 增量更新（VCDIFF 合成）的 APK 字节级保留源 APK 签名块，但远端新版是 CI
# debug key 签的 → 覆盖安装必报 INSTALL_FAILED_UPDATE_INCOMPATIBLE。
#
# v1.4.8 曾以「入库 keystore + 硬编码口令」修复指纹漂移 —— S-01 起废除该
# 做法（私钥与口令随公开仓库分发 = 签名防线失效）。本脚本的职责调整为：
#   1. 口令/alias 一律从环境变量读取（无默认值，缺失即失败）；
#   2. 产物 keystore **不入库**（keystore/.gitignore 拦截；check_secrets.py
#      按「后缀 + JKS magic 头」双通道兜底拦截）；
#   3. 打印证书 SHA-256 指纹，用于 version.json.signingCertSha256 比对基准；
#   4. 打印 base64（供 CI repo secret APEX_RELEASE_KEYSTORE_BASE64 注入）。
#
# 用法：
#   APEX_KEYSTORE_PASSWORD=*** APEX_KEY_ALIAS=*** APEX_KEY_PASSWORD=*** \
#     scripts/generate_release_keystore.sh
# 可选环境变量：
#   APEX_KEYSTORE_FILE   输出路径（默认 keystore/apex-release.jks）
#   APEX_KEYSTORE_CN     证书 CN 字段（默认 "ApexAgent Release"，非机密）
# ═════════════════════════════════════════════════════════════════════════════
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
KEYSTORE_DIR="$REPO_ROOT/keystore"
KEYSTORE_FILE="${APEX_KEYSTORE_FILE:-$KEYSTORE_DIR/apex-release.jks}"

# ── 凭据（S-01：env 注入，无默认值）──
KEYSTORE_PASSWORD="${APEX_KEYSTORE_PASSWORD:-}"
KEY_ALIAS="${APEX_KEY_ALIAS:-}"
KEY_PASSWORD="${APEX_KEY_PASSWORD:-}"
CN_NAME="${APEX_KEYSTORE_CN:-ApexAgent Release}"
# 50 年有效期（18250 天）—— 避免发布证书过期导致的更新断裂
VALIDITY_DAYS=18250

if [[ -z "$KEYSTORE_PASSWORD" || -z "$KEY_ALIAS" || -z "$KEY_PASSWORD" ]]; then
    echo "❌ S-01：生成 keystore 需要以下环境变量（无默认值，本脚本不落任何明文口令）：" >&2
    echo "   APEX_KEYSTORE_PASSWORD（store 口令）" >&2
    echo "   APEX_KEY_ALIAS（key alias）" >&2
    echo "   APEX_KEY_PASSWORD（key 口令）" >&2
    echo "   可选：APEX_KEYSTORE_FILE（输出路径）/ APEX_KEYSTORE_CN（证书 CN）" >&2
    exit 1
fi

mkdir -p "$(dirname "$KEYSTORE_FILE")"

# 已存在则校验密码 + 指纹，匹配则跳过（幂等）
if [[ -f "$KEYSTORE_FILE" ]]; then
    echo "[keystore] 已存在：$KEYSTORE_FILE"
    if keytool -list -keystore "$KEYSTORE_FILE" -storepass "$KEYSTORE_PASSWORD" -alias "$KEY_ALIAS" >/dev/null 2>&1; then
        echo "[keystore] ✅ 凭据校验通过（alias=$KEY_ALIAS 已存在且密码匹配）"
        keytool -list -v -keystore "$KEYSTORE_FILE" -storepass "$KEYSTORE_PASSWORD" -alias "$KEY_ALIAS" \
            | grep -E 'SHA256|Owner|Valid' | head -5
        exit 0
    else
        echo "[keystore] ⚠️ 已存在但凭据不匹配 —— 备份旧文件后重新生成"
        mv "$KEYSTORE_FILE" "$KEYSTORE_FILE.bak.$(date +%s)"
    fi
fi

echo "[keystore] 生成发布签名 keystore："
echo "  路径: $KEYSTORE_FILE"
echo "  alias: $KEY_ALIAS"
echo "  CN  : $CN_NAME"
echo "  有效期: $VALIDITY_DAYS 天（≈ 50 年）"

keytool -genkeypair \
    -keystore "$KEYSTORE_FILE" \
    -storetype PKCS12 \
    -storepass "$KEYSTORE_PASSWORD" \
    -keyalg RSA \
    -keysize 2048 \
    -alias "$KEY_ALIAS" \
    -keypass "$KEY_PASSWORD" \
    -validity "$VALIDITY_DAYS" \
    -dname "CN=$CN_NAME, OU=ApexAgent, O=ApexAgent, L=Unknown, ST=Unknown, C=CN"

echo "[keystore] ✅ 生成完成"
echo ""
echo "[keystore] 证书指纹（用于 version.json.signingCertSha256 比对基准）："
keytool -list -v -keystore "$KEYSTORE_FILE" -storepass "$KEYSTORE_PASSWORD" -alias "$KEY_ALIAS" \
    | grep -E 'SHA256:' | head -1
echo ""
echo "[keystore] ⚠️ S-01：本 keystore 严禁入库（keystore/.gitignore 已拦截；"
echo "           scripts/check_secrets.py 按后缀 + JKS magic 头兜底拦截）。"
echo "[keystore] CI 注入：base64 -w0 \"$KEYSTORE_FILE\" → repo secret APEX_RELEASE_KEYSTORE_BASE64，"
echo "           口令三项 → APEX_KEYSTORE_PASSWORD / APEX_KEY_ALIAS / APEX_KEY_PASSWORD。"
echo "[keystore] 本地构建：export APEX_KEYSTORE_FILE=\"$KEYSTORE_FILE\" 及上述口令三项。"
