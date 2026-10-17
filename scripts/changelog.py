#!/usr/bin/env python3
"""Android-Guru-Agent 更新日志工具链（changelog 通道 v1）—— validate / build。

用法（CI 与本地共用，零第三方依赖）：

  validate
      校验 `.changelog/unreleased/` 片段：文件名合法（防路径穿越/空格）、
      非空、单片段 ≤ 32KB。changelog-gate.yml 的 PR 门禁调用；违规退出码 1。

  build --version 1.4.9.20 --code 96 [--tag v1.4.9.20] [--date 2026-10-17]
        [--out dist/changelog.json] [--history-file <json>]
      聚合 `.changelog/unreleased/*.md` → changelog.json（apex-changelog-v1）：
        - notes：片段中的 Markdown 列表项（- / * / • / 1.）剥壳提取，无列表
          项时回退首行摘要 —— App 弹窗以 notes 渲染要点；
        - markdown：片段原文拼接（发布页正文 / 高级查看的完整底稿）；
        - history：拉取发布仓库现有 changelog.json 合并（同 versionCode 去重），
          最新在前封顶 50 条；`--history-file` 供本地/离线复现。
      片段为空不硬失败 —— 生成兜底文案（发布列车绝不断），但 PR 门禁
      （changelog-gate.yml）会在合并前拦住「改码不写日志」的 PR。

数据流向（release.yml）：
  本脚本 build → dist/changelog.json
    ├─ Release 资产上传（files: dist/* 自动带走）
    ├─ 提交发布仓库 main（raw CDN = 客户端固定读取点，与 version.json 同宿主）
    └─ 拷贝 app/src/main/assets/changelog.json（APK 内置离线兜底，.gitignore 排除）
"""

import argparse
import datetime
import json
import pathlib
import re
import sys
import urllib.request

REPO_ROOT = pathlib.Path(__file__).resolve().parent.parent
UNRELEASED_DIR = REPO_ROOT / ".changelog" / "unreleased"
RELEASE_REPO = "Ultra-Guru/Android-Guru-Agent-Release"
CHANGELOG_RAW_URL = f"https://raw.githubusercontent.com/{RELEASE_REPO}/main/changelog.json"

SCHEMA = "apex-changelog-v1"
MAX_FRAGMENT_BYTES = 32 * 1024
MAX_NOTES = 20
MAX_HISTORY = 50
FALLBACK_NOTE = "例行版本更新与稳定性改进。"
VERSION_RE = re.compile(r"^\d+(\.\d+)+$")
NAME_RE = re.compile(r"^[0-9A-Za-z][0-9A-Za-z._-]*\.md$")
BULLET_RE = re.compile(r"^\s*(?:[-*•·]|\d+[.、)])\s+(.+?)\s*$")


def load_fragments():
    """未发布片段列表（按文件名排序 = 发布器时间戳前缀保证的时序）。"""
    if not UNRELEASED_DIR.is_dir():
        return []
    return sorted(
        p for p in UNRELEASED_DIR.iterdir()
        if p.is_file() and p.suffix == ".md" and not p.name.startswith(".")
    )


def cmd_validate(_args):
    problems = []
    fragments = load_fragments()
    for p in fragments:
        if not NAME_RE.match(p.name):
            problems.append(
                f"{p.name}: 文件名不合法（仅允许 数字/字母/._- 组成的 .md，"
                "禁止空格与路径分隔符；发布器以 yyyyMMdd-HHmmss-前缀 命名）"
            )
            continue
        data = p.read_bytes()
        if len(data) > MAX_FRAGMENT_BYTES:
            problems.append(f"{p.name}: 超过 {MAX_FRAGMENT_BYTES // 1024}KB 上限")
            continue
        if not data.decode("utf-8", errors="replace").strip():
            problems.append(f"{p.name}: 内容为空")
    if problems:
        for line in problems:
            print(f"::error::changelog validate — {line}", file=sys.stderr)
        return 1
    print(f"✅ changelog validate — {len(fragments)} 个未发布片段全部合法")
    return 0


def extract_notes(fragments):
    """弹窗要点：列表项剥壳提取；整段无列表项时回退首行摘要（截 60 字）。"""
    notes = []
    for p in fragments:
        text = p.read_text(encoding="utf-8")
        frag_notes = [
            m.group(1) for m in map(BULLET_RE.match, text.splitlines()) if m
        ]
        if not frag_notes:
            first = next(
                (ln.strip() for ln in text.splitlines() if ln.strip()), ""
            )
            first = re.sub(r"^#+\s*", "", first)
            if first:
                frag_notes = [first[:60] + ("…" if len(first) > 60 else "")]
        notes.extend(frag_notes)
    seen, uniq = set(), []
    for n in notes:  # 去重保序
        if n not in seen:
            seen.add(n)
            uniq.append(n)
    return uniq[:MAX_NOTES]


def fetch_remote_history():
    """发布仓库现有 changelog.json 的 history（首版 / 网络失败从空开始）。"""
    try:
        req = urllib.request.Request(
            CHANGELOG_RAW_URL, headers={"Cache-Control": "no-cache"}
        )
        with urllib.request.urlopen(req, timeout=10) as resp:
            data = json.loads(resp.read().decode("utf-8"))
        hist = data.get("history")
        if isinstance(hist, list):
            return [e for e in hist if isinstance(e, dict)]
    except Exception:
        pass
    return []


def cmd_build(args):
    if not VERSION_RE.match(args.version):
        print(
            f"::error::版本号非法：{args.version}（期望 X.Y[.Z[.N]]，"
            "与 app/build.gradle.kts 的 versionName 同源）",
            file=sys.stderr,
        )
        return 1
    version, code = args.version, args.code
    tag = args.tag or f"v{version}"
    date = args.date or datetime.datetime.now(
        datetime.timezone.utc
    ).strftime("%Y-%m-%d")

    fragments = load_fragments()
    notes = extract_notes(fragments)
    if not notes:
        notes = [FALLBACK_NOTE]
    markdown = (
        "\n\n---\n\n".join(
            p.read_text(encoding="utf-8").strip() for p in fragments
        )
        if fragments
        else FALLBACK_NOTE
    )

    latest = {
        "versionName": version,
        "versionCode": code,
        "tag": tag,
        "date": date,
        "title": f"v{version} 更新内容",
        "notes": notes,
        "markdown": markdown,
        "releaseUrl": f"https://github.com/{RELEASE_REPO}/releases/tag/{tag}",
    }

    if args.history_file:
        with open(args.history_file, encoding="utf-8") as f:
            old_history = json.load(f).get("history", [])
    else:
        old_history = fetch_remote_history()
    history = [latest] + [
        e for e in old_history if e.get("versionCode") != code
    ][: MAX_HISTORY - 1]

    manifest = {
        "schema": SCHEMA,
        "generatedAt": datetime.datetime.now(datetime.timezone.utc).strftime(
            "%Y-%m-%dT%H:%M:%SZ"
        ),
        "latest": latest,
        "history": history,
    }
    out = pathlib.Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    with open(out, "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=2, ensure_ascii=False)
        f.write("\n")

    print(
        f"📦 changelog.json — v{version}({code})：{len(fragments)} 片段 / "
        f"{len(notes)} notes / history {len(history)} 条（remote 存量 "
        f"{len(old_history)}）"
    )
    for n in notes[:10]:
        print(f"    - {n}")
    return 0


def main():
    ap = argparse.ArgumentParser(
        description="Android-Guru-Agent changelog 工具链（validate / build）"
    )
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("validate", help="校验 .changelog/unreleased 片段")
    b = sub.add_parser("build", help="聚合片段生成 changelog.json")
    b.add_argument("--version", required=True, help="versionName（与 gradle 同源）")
    b.add_argument("--code", type=int, required=True, help="versionCode")
    b.add_argument("--tag", default=None, help="发布 tag（缺省 v{version}）")
    b.add_argument("--date", default=None, help="发布日期 YYYY-MM-DD（缺省今天 UTC）")
    b.add_argument("--out", default="dist/changelog.json", help="输出路径")
    b.add_argument(
        "--history-file",
        default=None,
        help="本地 history 源（测试/离线复现；缺省拉发布仓库 raw）",
    )
    args = ap.parse_args()
    sys.exit(cmd_validate(args) if args.cmd == "validate" else cmd_build(args))


if __name__ == "__main__":
    main()
