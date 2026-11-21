#!/usr/bin/env python3
# ═══════════════════════════════════════════════════════════════════════════
# 秘密泄漏扫描（guard-rails · CI: "Secrets Scan"）
#
# 背景：本项目文档/代码里出现过真实第三方凭证的引用风险（GitHub token、
# 云 API key 等）。一旦带 token 的文档或配置被提交，公开仓库等于裸奔。
# 本脚本做轻量 gitleaks：全仓文本文件扫描已知凭证指纹。
#
# 指纹族（前缀 + 长度/字符集由各家官方格式决定）：
#   ghp_/gho_/ghu_/ghs_/ghr_（GitHub classic & fine-grained）
#   github_pat_（GitHub fine-grained 新格式）
#   AKIA/ASIA（AWS Access Key）｜ AIza（Google API）
#   xox[baprs]-（Slack）｜ sk-（OpenAI 风格）｜ sk_live_（Stripe）
#   -----BEGIN ... PRIVATE KEY-----（任意私钥头）
#
# S-01 新增：二进制密钥库入库拦截（双通道）——
#   ① 文件名后缀规则：.jks / .keystore / .p12 / .pfx / .pkcs12 一律拦下
#     （发布 keystore 已移出版本控制，任何此类文件入库都视同私钥泄漏）；
#   ② 文件头 magic 规则：非文本后缀的入库文件若以 Java KeyStore magic
#     （FE ED FE ED）开头同样拦下 —— 防改名绕过（如 apex-release.bin）。
#   回归用例见 scripts/test_check_secrets.py（quality-gate CI 执行）。
#
# 防误报：示例占位（YOUR_KEY / xxx / <token> 等短串天然不满足长度要求）；
# 白名单文件路径与白名单串（测试夹具里故意放的字面量）放行。
# 只用标准库；退出码 0 = 干净，1 = 疑似泄漏。
# ═══════════════════════════════════════════════════════════════════════════
import os
import re
import subprocess
import sys
from pathlib import Path

PATTERNS: list[tuple[str, re.Pattern]] = [
    ("GitHub token (ghp_/gho_/ghu_/ghs_/ghr_)",
     re.compile(r"\bgh[pousr]_[A-Za-z0-9]{30,}\b")),
    ("GitHub fine-grained PAT",
     re.compile(r"\bgithub_pat_[A-Za-z0-9_]{40,}\b")),
    ("AWS Access Key", re.compile(r"\b(AKIA|ASIA)[0-9A-Z]{16}\b")),
    ("Google API key", re.compile(r"\bAIza[0-9A-Za-z_-]{35}\b")),
    ("Slack token", re.compile(r"\bxox[baprs]-[0-9A-Za-z-]{10,}\b")),
    ("OpenAI-style key", re.compile(r"\bsk-[A-Za-z0-9_-]{40,}\b")),
    ("Stripe live key", re.compile(r"\bsk_live_[0-9A-Za-z]{20,}\b")),
    ("Private key block",
     re.compile(r"-----BEGIN (RSA |EC |DSA |OPENSSH |ENCRYPTED )?PRIVATE KEY-----")),
]

# ── S-01：二进制密钥库拦截 ────────────────────────────────────────────────
# 后缀规则命中即报（不必读内容 —— 密钥库文件本身就是私钥材料）。
KEYSTORE_SUFFIXES = {".jks", ".keystore", ".p12", ".pfx", ".pkcs12"}
# Java KeyStore 真二进制格式的文件头 magic（0xFEEDFEED）；PKCS12 无此头，
# 由后缀规则覆盖。改名绕过（.bin/.dat 等）走此通道兜底。
JKS_MAGIC = b"\xfe\xed\xfe\xed"

# 白名单：测试/文档里被授权出现的字面量（按需维护；宁缺毋滥）。
ALLOWED_STRINGS: set[str] = set()

# 只扫文本类与代码类后缀（跳过二进制/资源）。
TEXT_SUFFIXES = {
    ".kt", ".kts", ".java", ".xml", ".json", ".md", ".txt", ".yml", ".yaml",
    ".properties", ".gradle", ".sh", ".py", ".toml", ".cfg", ".conf", ".ini",
    ".js", ".ts", ".html", ".css", ".gitignore", ".pro", ".aidl", "",
}
MAX_FILE_BYTES = 2 * 1024 * 1024  # 超大文件跳过（凭证不藏在 2MB 文本里）
MAGIC_READ_BYTES = 4  # 只读文件头 4 字节做 magic 比对


def tracked_files(repo_root: Path) -> list[Path]:
    out = subprocess.run(
        ["git", "ls-files"], cwd=repo_root,
        capture_output=True, text=True, check=True,
    ).stdout.splitlines()
    return [Path(p) for p in out if p.strip()]


def scan_repo(repo_root: Path) -> list[str]:
    """返回该 git 仓库（工作树）的全部可疑发现；空列表 = 干净。"""
    findings: list[str] = []

    for path in tracked_files(repo_root):
        abs_path = repo_root / path
        if not abs_path.is_file():
            continue

        # 通道①：密钥库后缀 —— 文件名本身就是私钥材料，直接拦。
        if path.suffix.lower() in KEYSTORE_SUFFIXES:
            findings.append(f"{path}  [Binary keystore in VCS (suffix rule)]  "
                            f"keystore 文件严禁入库（S-01）")
            continue

        # 通道②：非文本后缀 —— 读文件头比对 JKS magic（防改名绕过）。
        if path.suffix.lower() not in TEXT_SUFFIXES:
            try:
                with abs_path.open("rb") as fh:
                    head = fh.read(MAGIC_READ_BYTES)
            except OSError:
                continue
            if head == JKS_MAGIC:
                findings.append(f"{path}  [Binary keystore in VCS (JKS magic)]  "
                                f"文件头 FEEDFEED = Java KeyStore（S-01）")
            continue

        # 文本通道：凭证指纹扫描（原逻辑）。
        try:
            if abs_path.stat().st_size > MAX_FILE_BYTES:
                continue
            text = abs_path.read_text(encoding="utf-8", errors="ignore")
        except OSError:
            continue
        for label, pat in PATTERNS:
            for m in pat.finditer(text):
                hit = m.group(0)
                if hit in ALLOWED_STRINGS:
                    continue
                line = text.count("\n", 0, m.start()) + 1
                findings.append(f"{path}:{line}  [{label}]  {hit[:12]}…")

    return findings


def main() -> int:
    # 默认扫本仓库；APEX_SECRETS_SCAN_ROOT 供回归测试指向临时 fixture 仓库。
    override = os.environ.get("APEX_SECRETS_SCAN_ROOT", "").strip()
    repo = Path(override) if override else Path(__file__).resolve().parent.parent
    findings = scan_repo(repo)

    if findings:
        print(f"✗ 疑似秘密泄漏（{len(findings)} 处）：\n")
        for f in findings:
            print(f"  - {f}")
        print("\n处理方式：撤销该凭证 → 从历史清除（git filter-repo）→ "
              "如属测试夹具请加入脚本 ALLOWED_STRINGS 白名单并注明理由。")
        print("keystore/私钥类：移出版本控制（keystore/.gitignore 已拦截），"
              "凭据经 env / CI secrets 注入（见 scripts/generate_release_keystore.sh）。")
        return 1

    print("✓ 秘密扫描通过：无已知凭证指纹，无二进制密钥库")
    return 0


if __name__ == "__main__":
    sys.exit(main())
