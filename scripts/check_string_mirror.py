#!/usr/bin/env python3
# ═══════════════════════════════════════════════════════════════════════════
# i18n 字符串镜像一致性检查（guard-rails · CI: "i18n String Mirror"）
#
# 纪律依据：本仓库 res/values/ 与 res/values-zh/ 的 strings_*.xml 是 1:1
# 镜像（同一键在两种语言里都必须有定义，且格式化占位符一致）。历史上靠
# 人工比对（#291 手工验过 528 键），漏键只能等用户切语言才发现 —— 本脚本
# 把这条纪律变成门禁。
#
# 检查项：
#   1. values/strings_X.xml ↔ values-zh/strings_X.xml 键集双向差集为空
#      （values-zh 有而 values 没有同样算破镜 —— 默认语言是键的真源）
#   2. 同键两侧格式化占位符（%1$s / %d / %% 等）多重集一致
#      （防「英文两个参数、中文只写一个」这类切语言才炸的崩溃）
#   3. XML 可解析（坏文件直接报）
#
# 只用标准库；退出码 0 = 通过，1 = 有破镜项。
# ═════════════════════════════════════════════════════════════════════════
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

RES = Path(__file__).resolve().parent.parent / "app/src/main/res"
# 豁免键：应用名等不翻译的元数据键（跨语言同形，无镜像义务）。
EXEMPT_KEYS = {"app_name"}
PLACEHOLDER = re.compile(r"%\d+\$[sd]|%[sd]|%%")

def parse_keys(path: Path) -> dict[str, str]:
    """strings XML → {name: value}；解析失败抛异常由调用方按文件报错。"""
    root = ET.parse(path).getroot()
    out = {}
    for node in root.iter("string"):
        name = node.get("name")
        if name:
            out[name] = "".join(node.itertext())
    return out

def main() -> int:
    values_dir = RES / "values"
    zh_dir = RES / "values-zh"
    if not values_dir.is_dir() or not zh_dir.is_dir():
        print(f"✗ 资源目录缺失：{values_dir} / {zh_dir}")
        return 1

    problems: list[str] = []
    files = sorted(values_dir.glob("strings*.xml"))
    checked = 0
    total_keys = 0

    for en_path in files:
        rel = en_path.relative_to(RES)
        zh_path = zh_dir / en_path.name
        if not zh_path.exists():
            problems.append(f"{rel}：values-zh/ 缺同名镜像文件")
            continue
        try:
            en = parse_keys(en_path)
            zh = parse_keys(zh_path)
        except ET.ParseError as e:
            problems.append(f"{rel} 或其 zh 镜像 XML 解析失败：{e}")
            continue

        en = {k: v for k, v in en.items() if k not in EXEMPT_KEYS}
        zh = {k: v for k, v in zh.items() if k not in EXEMPT_KEYS}
        checked += 1
        total_keys += len(en)

        # 1. 键集双向差集
        only_en = sorted(set(en) - set(zh))
        only_zh = sorted(set(zh) - set(en))
        for k in only_en:
            problems.append(f"{en_path.name}：键 `{k}` 缺中文定义（values-zh/{en_path.name}）")
        for k in only_zh:
            problems.append(f"{zh_path.name}：键 `{k}` 缺英文定义（values/{en_path.name}）")

        # 2. 占位符一致性（同键双侧）
        for k in sorted(set(en) & set(zh)):
            p_en = sorted(PLACEHOLDER.findall(en[k]))
            p_zh = sorted(PLACEHOLDER.findall(zh[k]))
            if p_en != p_zh:
                problems.append(
                    f"{en_path.name}：键 `{k}` 占位符不一致 "
                    f"en={p_en} zh={p_zh}"
                )

    # 反向：values-zh 有而 values 没有的整文件
    for zh_path in sorted(zh_dir.glob("strings*.xml")):
        if not (values_dir / zh_path.name).exists():
            problems.append(f"values-zh/{zh_path.name}：values/ 缺同名镜像文件")

    if problems:
        print(f"✗ i18n 镜像检查失败（{len(problems)} 项）：\n")
        for p in problems:
            print(f"  - {p}")
        return 1

    print(f"✓ i18n 镜像检查通过：{checked} 个 strings 文件 / {total_keys} 键，中英 1:1，占位符全一致")
    return 0

if __name__ == "__main__":
    sys.exit(main())
