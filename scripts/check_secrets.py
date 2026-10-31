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
# 防误报：示例占位（YOUR_KEY / xxx / <token> 等短串天然不满足长度要求）；
# 白名单文件路径与白名单串（测试夹具里故意放的字面量）放行。
# 只用标准库；退出码 0 = 干净，1 = 疑似泄漏。
# ═════════════════════════════════════════════════════════════════════════
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

# 白名单：测试/文档里被授权出现的字面量（按需维护；宁缺毋滥）。
ALLOWED_STRINGS: set[str] = set()

# 只扫文本类与代码类后缀（跳过二进制/资源）。
TEXT_SUFFIXES = {
    ".kt", ".kts", ".java", ".xml", ".json", ".md", ".txt", ".yml", ".yaml",
    ".properties", ".gradle", ".sh", ".py", ".toml", ".cfg", ".conf", ".ini",
    ".js", ".ts", ".html", ".css", ".gitignore", ".pro", ".aidl", "",
}
MAX_FILE_BYTES = 2 * 1024 * 1024  # 超大文件跳过（凭证不藏在 2MB 文本里）

def tracked_files() -> list[Path]:
    out = subprocess.run(
        ["git", "ls-files"], capture_output=True, text=True, check=True
    ).stdout.splitlines()
    return [Path(p) for p in out if p.strip()]

def main() -> int:
    repo = Path(__file__).resolve().parent.parent
    findings: list[str] = []

    for path in tracked_files():
        if not path.is_file():
            continue
        if path.suffix.lower() not in TEXT_SUFFIXES:
            continue
        try:
            if path.stat().st_size > MAX_FILE_BYTES:
                continue
            text = path.read_text(encoding="utf-8", errors="ignore")
        except OSError:
            continue
        for label, pat in PATTERNS:
            for m in pat.finditer(text):
                hit = m.group(0)
                if hit in ALLOWED_STRINGS:
                    continue
                line = text.count("\n", 0, m.start()) + 1
                findings.append(f"{path}:{line}  [{label}]  {hit[:12]}…")

    if findings:
        print(f"✗ 疑似秘密泄漏（{len(findings)} 处）：\n")
        for f in findings:
            print(f"  - {f}")
        print("\n处理方式：撤销该凭证 → 从历史清除（git filter-repo）→ "
              "如属测试夹具请加入脚本 ALLOWED_STRINGS 白名单并注明理由。")
        return 1

    print("✓ 秘密扫描通过：无已知凭证指纹")
    return 0

if __name__ == "__main__":
    sys.exit(main())
