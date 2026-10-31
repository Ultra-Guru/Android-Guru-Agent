#!/usr/bin/env python3
"""Android-Guru-Agent 更新日志工具链（changelog 通道 v3）—— validate / build。

用法（CI 与本地共用，零第三方依赖）：

  validate
      校验 `.changelog/unreleased/` 片段：文件名合法（防路径穿越/空格）、
      非空（元数据标记行不计内容）、单片段 ≤ 32KB。changelog-gate.yml 的
      PR 门禁调用；违规退出码 1。

  build --version 1.4.9.27 --code 103 [--tag v1.4.9.27] [--date 2026-10-17]
        [--out dist/changelog.json] [--history-file <json>]
        [--consumed-out dist/consumed-fragments.txt]
        [--archived dist/archived-fragments.txt]
      聚合 `.changelog/unreleased/*.md` → changelog.json（apex-changelog-v3）：

      片段元数据（文件头 `<!-- key: value -->` 连续标记行，聚合时剥离）：
        - `source: publisher` —— 由管理端发布器 App（apex-Administrator）发送
          的片段 → 一级日志（弹窗置顶展示）；
        - `pr: <slug>` —— PR / 分支功能片段 → 二级日志，slug 直接作组标题
          （无此标记时组标题回退 Markdown 标题 / 首行 / 文件名 slug）；
        - `target: 1.4.9.27` —— 本片段面向的目标版本；构建版本 < target 时
          本片段缓发布（不收录、不消费，留在 unreleased 等待未来版本）；
          缺省 = 下一版即收录。

      一级判定双通道（v3.1 —— 「发布了却不显示更新内容」根治）：
        1. 显式标记：`source: publisher`；
        2. git 溯源兑底：片段由 commit message 含「via 发布器」的提交新增
           （老版发布器 App 不写标记也不丢一级置顶）；浅克隆 / 非 git
           环境宽松降级为空集，标记通道不受影响。
        两通道均未命中且无 `pr:` 标记的片段 = PR 片段 → 二级日志（弹窗
        一级要点下方滑动区，按组展示）。

      changelog.json 结构（v3，对 v1/v2 客户端前向兼容 —— 未知字段忽略）：
        - notes：一级日志要点（仅 publisher 片段的列表项剥壳，行内图片
          引用剥除）—— 弹窗置顶；
        - images：配图 URL 列表（publisher 片段的 ![alt](url) 顺序提取，
          封顶 9 张）—— 弹窗要点下方图廊，发布器（apex-Administrator
          v2.3+）发送的附图经此通道展示；
        - pr：二级日志分组 [{title, notes}]（PR 片段，每组 = 一条 PR 摘要）；
        - markdown：全量底稿（publisher 在前，`---` 分隔，PR 在后）；
        - history：拉取发布仓库现有 changelog.json 合并（同 versionCode 去重），
          最新在前封顶 50 条；`--history-file` 供本地/离线复现。

      消费闭环（配合 release.yml）：
        `--consumed-out` 写出本次已收录片段文件名清单（每行一个）——
        release.yml 发布成功后据此归档到 `.changelog/released/<tag>/` 并
        从 unreleased 移除（带 [skip ci] 回推 main）；target 面向未来的
        片段不进清单（跨版本滞留，绝不误消费）。

      跨 run 去重（配合 release.yml，v2.2）：
        `--archived` 指向「最新 main 的 .changelog/released/ 已归档片段
        文件名清单」（release.yml 构建期 fetch 最新 main 生成）。本 run 的
        触发 commit 快照可能早于前序 run 的消费回推 —— 快照里的片段实际
        已被前序版本发布过；命中归档索引即跳过，防止同片段进两个版本
        （历史缺陷：v35/v36、v39/v40 双双重复收录同一 PR 片段，导致
        「手动日志发行时 PR 日志丢失」的错位体验）。

      片段全空不硬失败 —— 生成兜底文案（发布列车绝不断），但 PR 门禁
      （changelog-gate.yml）会在合并前拦住「改码不写日志」的 PR。
"""

import argparse
import datetime
import json
import pathlib
import re
import subprocess
import sys
import urllib.request

REPO_ROOT = pathlib.Path(__file__).resolve().parent.parent
UNRELEASED_DIR = REPO_ROOT / ".changelog" / "unreleased"
RELEASE_REPO = "Ultra-Guru/Android-Guru-Agent-Release"
CHANGELOG_RAW_URL = f"https://raw.githubusercontent.com/{RELEASE_REPO}/main/changelog.json"

SCHEMA = "apex-changelog-v3"
MAX_FRAGMENT_BYTES = 32 * 1024
MAX_NOTES = 20          # 一级日志（publisher）要点上限
MAX_PR_GROUPS = 12      # 二级日志（PR）分组上限
MAX_GROUP_NOTES = 10    # 单组要点上限
MAX_IMAGES = 9          # 一级日志配图上限（发布器单条 ≤9 张，与弹窗图廊对齐）
MAX_HISTORY = 50
FALLBACK_NOTE = "例行版本更新与稳定性改进。"
VERSION_RE = re.compile(r"^\d+(\.\d+)+$")
NAME_RE = re.compile(r"^[0-9A-Za-z][0-9A-Za-z._-]*\.md$")
BULLET_RE = re.compile(r"^\s*(?:[-*•·]|\d+[.、)])\s+(.+?)\s*$")
HEADING_RE = re.compile(r"^#{1,6}\s+(.+?)\s*$")
# Markdown 图片引用（![alt](url)）—— 发布器附图；alt 不捕获（展示走 images 字段）
IMG_RE = re.compile(r"!\[[^\]]*\]\((\S+?)\)")
# 文件头元数据标记：source / target / pr 三键（pr 的值直接作二级组标题）
MARKER_RE = re.compile(
    r"^<!--\s*(source|target|pr)\s*:\s*([^>]*?)\s*-->\s*$", re.IGNORECASE
)
TS_PREFIX_RE = re.compile(r"^\d{8}-\d{6}-")
# 发布器 App 提交的 commit message 特征（docs(changelog): …（via 发布器））
PUBLISHER_COMMIT_RE = re.compile(r"via[\s\u00b7]*发布器", re.IGNORECASE)
# git log --format=%H\ %s 的提交行（40 位 SHA 前缀，与文件路径行区分）
GIT_COMMIT_LINE_RE = re.compile(r"^[0-9a-f]{40} ")


def load_fragments():
    """未发布片段列表（按文件名排序 = 发布器时间戳前缀保证的时序）。"""
    if not UNRELEASED_DIR.is_dir():
        return []
    return sorted(
        p for p in UNRELEASED_DIR.iterdir()
        if p.is_file() and p.suffix == ".md" and not p.name.startswith(".")
    )


def strip_head_markers(text):
    """剥离文件头连续标记行 → (meta, body)。标记仅认头部（正文中的
    HTML 注释不受影响）；同键重复以首见为准。pr 值作二级组标题。"""
    lines = text.splitlines()
    meta = {"source": None, "target": None, "pr": None}
    i = 0
    while i < len(lines):
        m = MARKER_RE.match(lines[i])
        if not m:
            break
        key, value = m.group(1).lower(), m.group(2).strip()
        if key in meta and meta[key] is None and value:
            meta[key] = value
        i += 1
    return meta, "\n".join(lines[i:])


def version_tuple(v):
    v = str(v).strip().lstrip("vV")
    parts = []
    for seg in v.split("."):
        try:
            parts.append(int(seg))
        except ValueError:
            return None
    return tuple(parts) if parts else None


def version_le(a, b):
    """a <= b（数值逐段）；任一无法解析 → 宽松收录（宁可重复展示，绝不丢日志）。"""
    ta, tb = version_tuple(a), version_tuple(b)
    if ta is None or tb is None:
        return True
    n = max(len(ta), len(tb))
    ta += (0,) * (n - len(ta))
    tb += (0,) * (n - len(tb))
    return ta <= tb


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
        _meta, body = strip_head_markers(
            data.decode("utf-8", errors="replace")
        )
        if not body.strip():
            problems.append(f"{p.name}: 内容为空（元数据标记行不计内容）")
    if problems:
        for line in problems:
            print(f"::error::changelog validate — {line}", file=sys.stderr)
        return 1
    print(f"✅ changelog validate — {len(fragments)} 个未发布片段全部合法")
    return 0


def strip_images(text):
    """剥除行内 markdown 图片引用（![alt](url)）→ 剩余纯文本（两侧空白清理）。"""
    return IMG_RE.sub("", text).strip()


def extract_images(body):
    """单片段配图：markdown 图片引用顺序提取（去重保序）。"""
    seen, urls = set(), []
    for m in IMG_RE.finditer(body):
        u = m.group(1)
        if u not in seen:
            seen.add(u)
            urls.append(u)
    return urls


def extract_bullets(body):
    """单片段要点：列表项剥壳（行内图片引用剥除，剥后为空的 bullet 丢弃 ——
    图片走 images 通道不占要点位）；整段无列表项时回退首行摘要（截 60 字）。"""
    notes = []
    for m in map(BULLET_RE.match, body.splitlines()):
        if m:
            cleaned = strip_images(m.group(1))
            if cleaned:
                notes.append(cleaned)
    if not notes:
        # 首行摘要跳过纯图行（图片走 images 通道），并剥除行内残余引用
        first = next(
            (ln.strip() for ln in body.splitlines() if strip_images(ln)),
            "",
        )
        first = re.sub(r"^#+\s*", "", first)
        if first:
            notes = [first[:60] + ("…" if len(first) > 60 else "")]
    return notes


def dedup_keep_order(items):
    seen, uniq = set(), []
    for n in items:
        if n not in seen:
            seen.add(n)
            uniq.append(n)
    return uniq


def publisher_committed_names():
    """git 溯源：由发布器 App 提交（commit message 含「via 发布器」）新增
    的片段文件名集合 —— 老版发布器不写 `source: publisher` 标记也能进一级。

    浅克隆只看得到触发 commit（发布器直推时恰是它自身）→ 足够；非 git
    环境 / 超时 / 任一异常 → 空集宽松降级（标记通道不受影响，发布列车
    绝不因此断）。"""
    try:
        proc = subprocess.run(
            [
                "git", "log", "--diff-filter=A", "--name-only",
                "--format=%H %s", "--", ".changelog/unreleased",
            ],
            cwd=REPO_ROOT, capture_output=True, text=True, timeout=15,
        )
        if proc.returncode != 0:
            return set()
    except Exception:
        return set()
    names = set()
    from_publisher = False
    for line in proc.stdout.splitlines():
        if GIT_COMMIT_LINE_RE.match(line):
            from_publisher = bool(PUBLISHER_COMMIT_RE.search(line))
        elif line.startswith(".changelog/unreleased/") and from_publisher:
            names.add(line.rsplit("/", 1)[-1])
    return names


def prettify_slug(slug):
    """pr 标记 slug → 可读组标题（`normal-browser-and-git` →
    `normal browser and git`）；去首尾空白与分隔符。"""
    return re.sub(r"[-_]+", " ", slug.strip()).strip()


def group_title(body, path, pr_slug=None):
    """PR 组标题：首个 Markdown 标题 > pr 标记 slug > 首个非列表正文行
    > 文件名 slug。pr 标记（`<!-- pr: xxx -->`）由发布器 / Agent 流程写入，
    值即 PR 分支语义名 —— 优先于首行捕抓，免受正文格式影响。"""
    for ln in body.splitlines():
        m = HEADING_RE.match(ln.strip())
        if m:
            return m.group(1)
    pr_slug = (pr_slug or "").strip()
    if pr_slug:
        pretty = prettify_slug(pr_slug)
        if pretty:
            return pretty[:60] + ("…" if len(pretty) > 60 else "")
    first = next(
        (ln.strip() for ln in body.splitlines()
         if ln.strip() and not BULLET_RE.match(ln) and not MARKER_RE.match(ln)
         and strip_images(ln)),
        "",
    )
    if first:
        first = strip_images(first)
    if first:
        return first[:60] + ("…" if len(first) > 60 else "")
    slug = TS_PREFIX_RE.sub("", path.name).removesuffix(".md")
    return slug.replace("-", " ").replace("_", " ").strip() or path.stem


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


def load_archived_index(path):
    """`--archived` 清单（每行一个已归档片段文件名）→ set。
    文件缺失 / 为空 = 无历史归档（通道首用 / fetch 失败），空集宽松降级。"""
    if not path:
        return set()
    f = pathlib.Path(path)
    if not f.is_file():
        return set()
    return {
        ln.strip() for ln in f.read_text(encoding="utf-8").splitlines()
        if ln.strip()
    }


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

    # ── 跨 run 去重：最新 main 的已归档片段 ─────────────────────
    # 触发 commit 快照可能含已被前序 run 消费的片段（消费回推晚于本 run
    # 的 checkout）—— 命中归档索引即跳过，防同片段进两个版本。
    archived = load_archived_index(args.archived)

    # ── 收录判定：target 感知 + 归档去重 ─────────────────────
    # 面向未来版本的片段（target > 构建版本）缓发布：不收录、不消费；
    # 已被前序 run 发布的片段（归档索引命中）跳过，防跨 run 重复收录。

    # ── 一级判定双通道（v3.1）：标记 + git 溯源兑底 ─────────────
    publisher_sent = publisher_committed_names()

    included, deferred, deduped = [], [], []
    for p in load_fragments():
        if p.name in archived:
            deduped.append(p)
            print(
                f"⏭️  {p.name}: 已被前序版本发布（归档索引命中）—— 跳过，"
                "防跨 run 重复收录"
            )
            continue
        raw = p.read_text(encoding="utf-8")
        meta, body = strip_head_markers(raw)
        target = meta.get("target")
        if target and not version_le(target, version):
            deferred.append((p, target))
            print(
                f"⏭️  {p.name}: target v{target} > 本版 v{version} —— 缓发布"
                "（留在 unreleased 等待未来版本）"
            )
            continue
        included.append((p, meta, body))

    publisher = [
        x for x in included
        # 显式 pr 标记 → 恒二级（标记优先于 git 溯源推断，防误升一级）
        if not (x[1].get("pr") or "").strip()
        and (
            (x[1].get("source") or "").strip().lower() == "publisher"
            or x[0].name in publisher_sent
        )
    ]
    pr_frags = [x for x in included if x not in publisher]

    # ── 一级日志：publisher 片段要点（弹窗置顶）+ 配图（图廊）────────
    notes = dedup_keep_order(
        [n for _p, _m, b in publisher for n in extract_bullets(b)]
    )[:MAX_NOTES]
    images = dedup_keep_order(
        [u for _p, _m, b in publisher for u in extract_images(b)]
    )[:MAX_IMAGES]

    # ── 二级日志：PR 片段分组（下滑区「新增功能 / 修复」介绍）────────
    pr_groups = []
    for p, m, body in pr_frags[:MAX_PR_GROUPS]:
        g_notes = dedup_keep_order(extract_bullets(body))[:MAX_GROUP_NOTES]
        if g_notes:
            pr_groups.append({
                "title": group_title(body, p, m.get("pr")),
                "notes": g_notes,
            })
    if len(pr_frags) > MAX_PR_GROUPS:
        pr_groups.append({
            "title": f"另有 {len(pr_frags) - MAX_PR_GROUPS} 项合并内容",
            "notes": [f"详见发布页完整清单（共 {len(pr_frags)} 条 PR 片段）"],
        })

    # ── 兜底：两级全空才使用兜底文案（发布列车绝不断）──────────────
    if not notes and not pr_groups:
        notes = [FALLBACK_NOTE]

    tier1_md = [b.strip() for _p, _m, b in publisher if b.strip()]
    tier2_md = [b.strip() for _p, _m, b in pr_frags if b.strip()]
    markdown = (
        "\n\n---\n\n".join(tier1_md + tier2_md)
        if (tier1_md or tier2_md)
        else FALLBACK_NOTE
    )

    latest = {
        "versionName": version,
        "versionCode": code,
        "tag": tag,
        "date": date,
        "title": f"v{version} 更新内容",
        "notes": notes,
        "images": images,
        "pr": pr_groups,
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

    # ── 消费清单：本版已收录片段（publisher + pr 全部）───────────────
    # release.yml 发布成功后据此归档；target 缓发布片段不在其列。
    if args.consumed_out:
        consumed = pathlib.Path(args.consumed_out)
        consumed.parent.mkdir(parents=True, exist_ok=True)
        with open(consumed, "w", encoding="utf-8") as f:
            for p, _m, _b in included:
                f.write(p.name + "\n")
            f.flush()

    print(
        f"📦 changelog.json — v{version}({code})：{len(included)} 片段收录 / "
        f"{len(deferred)} 缓发布 / {len(deduped)} 归档去重 | 一级 {len(notes)} 条（publisher "
        f"{len(publisher)}，其中 git 溯源命中 "
        f"{sum(1 for x in publisher if (x[1].get('source') or '').strip().lower() != 'publisher')}）"
        f"+ 配图 {len(images)} 张 + 二级 {len(pr_groups)} 组（pr "
        f"{len(pr_frags)}）/ history {len(history)} 条（remote 存量 {len(old_history)}）"
    )
    for n in notes[:8]:
        print(f"    • {n}")
    for g in pr_groups[:4]:
        print(f"    ▸ [{g['title']}] {len(g['notes'])} 条")
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
    b.add_argument(
        "--consumed-out",
        default=None,
        help="本版已收录片段文件名清单输出（release.yml 归档依据）",
    )
    b.add_argument(
        "--archived",
        default=None,
        help="最新 main 已归档片段文件名清单（跨 run 去重：命中即跳过，"
        "防同片段进两个版本）",
    )
    args = ap.parse_args()
    sys.exit(cmd_validate(args) if args.cmd == "validate" else cmd_build(args))


if __name__ == "__main__":
    main()
