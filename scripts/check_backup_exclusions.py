#!/usr/bin/env python3
# ═══════════════════════════════════════════════════════════════════════════
# 凭据类 SharedPreferences 备份排除校验（guard-rails · CI: "Backup Exclusions"）
#
# 背景（Fix Spec S-05）：data_extraction_rules.xml / legacy_backup_rules.xml
# 曾漏排除 mcp_host_prefs（逆向 MCP Host 的 Bearer token，明文 prefs）与
# pulse_mcp_secure_prefs（PulseMCP 凭据，EncryptedSharedPreferences —— 密文
# 迁移到无对应 KeyStore MasterKey 的新机必然触发明文降级分支）。
# 凭据随云备份/设备迁移导出 = 跨设备泄漏；这类漏配编译期零提示、运行期
# 零报错，只能靠静态校验钉住。
#
# 规则（双向）：
#   ① 源码中使用的「敏感 prefs 名」（名字含 secure/token/credential/
#     secret/mcp_host，大小写不敏感）必须出现在全部三个排除节点：
#     cloud-backup / device-transfer / legacy full-backup-content；
#   ② 源码中实际存在的「敏感 prefs 的 fallback 变体」（…-fallback /
#     …_fallback）同样必须排除（明文降级文件更不该出设备）。
#   敏感名单外的新 prefs（如普通设置）不受约束 —— 只有凭据语义的名字进门。
#
# 提取方式：扫描 app/ platform/ 主源码（排除 src/test）中的
#   - getSharedPreferences("字面量" 与 "$CONST" 模板串
#   - EncryptedSharedPreferences.create(context, CONST/"字面量", …)
#   - const val XXX = "…"（文件内解析模板引用）
# 只用标准库；退出码 0 = 校验通过，1 = 漏配。
# ═══════════════════════════════════════════════════════════════════════════
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
MAIN_SOURCE_ROOTS = [REPO_ROOT / "app" / "src" / "main", REPO_ROOT / "platform"]
EXTRACTION_RULES = REPO_ROOT / "app" / "src" / "main" / "res" / "xml" / "data_extraction_rules.xml"
LEGACY_RULES = REPO_ROOT / "app" / "src" / "main" / "res" / "xml" / "legacy_backup_rules.xml"

# 敏感语义关键词：名字命中即视为凭据类 prefs，必须被备份排除。
SENSITIVE_KEYWORDS = re.compile(r"secure|token|credential|secret|mcp_host", re.IGNORECASE)

# 源码提取模式
RE_CONST_DECL = re.compile(r'const\s+val\s+([A-Z][A-Z0-9_]*)\s*=\s*"([^"]*)"')
RE_GETPREF_LITERAL = re.compile(r'getSharedPreferences\(\s*"([^"$]+)"')
RE_GETPREF_TEMPLATE = re.compile(r'getSharedPreferences\(\s*"\$\{?([A-Z][A-Z0-9_]*)\}?(-?[A-Za-z_]*)')
RE_ENC_CREATE = re.compile(
    r'EncryptedSharedPreferences\.create\(\s*[A-Za-z_][A-Za-z0-9_.]*\s*,\s*'
    r'(?:([A-Z][A-Z0-9_]*)|"([^"$]+)")',
    re.DOTALL,
)


def collect_prefs_names() -> set[str]:
    """扫描主源码，返回实际使用的 SharedPreferences 名（含 fallback 变体）。"""
    names: set[str] = set()
    for root in MAIN_SOURCE_ROOTS:
        if not root.is_dir():
            continue
        for kt in root.rglob("*.kt"):
            if "src/test" in kt.parts or "src/androidTest" in kt.parts:
                continue
            try:
                text = kt.read_text(encoding="utf-8", errors="ignore")
            except OSError:
                continue
            consts = dict(RE_CONST_DECL.findall(text))

            for literal in RE_GETPREF_LITERAL.findall(text):
                names.add(literal)
            for const, suffix in RE_GETPREF_TEMPLATE.findall(text):
                base = consts.get(const)
                if base:
                    names.add(base + suffix)
            for const, literal in RE_ENC_CREATE.findall(text):
                if literal:
                    names.add(literal)
                elif const and const in consts:
                    names.add(consts[const])
    return names


def extraction_excludes() -> dict[str, set[str]]:
    """返回 {节点名: {exclude path 集合}}（data_extraction + legacy 合并视图）。

    预期节点缺失（如整个 device-transfer 被误删）时以空集合占位 ——
    check() 才能把「节点不存在」判成「全部敏感 prefs 漏配」而非静默通过。
    """
    result: dict[str, set[str]] = {
        "data-extraction/cloud-backup": set(),
        "data-extraction/device-transfer": set(),
        "legacy/full-backup-content": set(),
    }

    ext_root = ET.parse(EXTRACTION_RULES).getroot()
    for node in ext_root:
        paths = {
            e.get("path", "")
            for e in node
            if e.tag == "exclude" and e.get("domain") == "sharedpref"
        }
        result[f"data-extraction/{node.tag}"] = paths

    legacy_root = ET.parse(LEGACY_RULES).getroot()
    legacy_paths = {
        e.get("path", "")
        for e in legacy_root
        if e.tag == "exclude" and e.get("domain") == "sharedpref"
    }
    result["legacy/full-backup-content"] = legacy_paths
    return result


def check(repo_root: Path) -> list[str]:
    """返回违规清单；空列表 = 通过。repo_root 供回归测试注入 fixture。"""
    global MAIN_SOURCE_ROOTS, EXTRACTION_RULES, LEGACY_RULES
    MAIN_SOURCE_ROOTS = [repo_root / "app" / "src" / "main", repo_root / "platform"]
    EXTRACTION_RULES = repo_root / "app" / "src" / "main" / "res" / "xml" / "data_extraction_rules.xml"
    LEGACY_RULES = repo_root / "app" / "src" / "main" / "res" / "xml" / "legacy_backup_rules.xml"

    failures: list[str] = []
    sensitive = sorted(n for n in collect_prefs_names() if SENSITIVE_KEYWORDS.search(n))
    nodes = extraction_excludes()

    for name in sensitive:
        required = f"{name}.xml"
        for node_name, paths in nodes.items():
            if required not in paths:
                failures.append(
                    f"[{node_name}] 凭据类 prefs「{name}」未被备份排除"
                    f"（缺 <exclude domain=\"sharedpref\" path=\"{required}\" />）"
                )

    return failures


def main() -> int:
    failures = check(REPO_ROOT)

    if failures:
        print(f"✗ 凭据类 prefs 备份排除校验失败（{len(failures)} 处）：\n")
        for f in failures:
            print(f"  - {f}")
        print("\n处理：在 app/src/main/res/xml/data_extraction_rules.xml 的 cloud-backup 与")
        print("device-transfer 两节点 + legacy_backup_rules.xml 各补对应 exclude。")
        print("依据：密文 prefs 跨设备迁移必触发 KeyStore 失配降级；明文凭据更不应出设备。")
        return 1

    print("✓ 备份排除校验通过：全部凭据类 prefs 均被三个备份通道排除")
    return 0


if __name__ == "__main__":
    sys.exit(main())
