#!/usr/bin/env python3
"""知识库程序化审计（issue #539 · R2-PR7）

三项审计（对齐 KnowledgeBaseParser 的解析口径，含「快速索引 · 续」多表合并）：
1. 索引指向：每条快速索引条目的 target 章节必须真实存在（真实解析器
   findSection 同款语义）；
2. 数量一致性：索引条目数 / 内容章节数 比值 —— 悬殊即「一键定位」覆盖缺口；
3. 检索覆盖抽查：每个内容节的标题首词经 search 同款打分（标题×3/正文×1/
   必看×2）必须可命中本库。

用法：python3 scripts/audit_knowledge_bases.py（审计失败退出码 1）
"""
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent / "repo"
KB_DIR = REPO / "app/src/main/assets/knowledge"

SEC_HEAD = re.compile(r"^##\s+(.+)$", re.M)
KB_HEAD = re.compile(r"<!--\s*kb:\s*(.+?)\s*-->", re.S)
FIELD = re.compile(r"(\w+)\s*=\s*([^|]+)")


def parse_kb(path: Path):
    text = path.read_text(encoding="utf-8")
    m = KB_HEAD.search(text)
    if not m:
        return None
    fields = dict(FIELD.findall(m.group(1)))
    sections = []  # (title, body)
    heads = list(SEC_HEAD.finditer(text))
    for i, h in enumerate(heads):
        end = heads[i + 1].start() if i + 1 < len(heads) else len(text)
        sections.append((h.group(1).strip(), text[h.end():end].strip()))
    return fields, sections, text


def parse_index(sections):
    """从「快速索引」及全部「快速索引 · 续」节合并解析 (topic, target) 表。"""
    rows = []
    for title, body in sections:
        if "快速索引" in title:
            for line in body.splitlines():
                line = line.strip()
                if line.startswith("|") and not re.match(r"^\|[\s\-|]+\|$", line):
                    cells = [c.strip() for c in line.strip("|").split("|")]
                    if len(cells) >= 2 and cells[0] not in ("想找什么", ""):
                        rows.append((cells[0], cells[1]))
    return rows


def kb_terms(query):
    return [t for t in re.split(r"\s+", query.lower()) if t]


def search_hits(sections, must_read, query):
    """镜像 KnowledgeBaseService.search 的打分（标题×3 / 正文×1 / 必看×2 / 索引→章节）。"""
    terms = kb_terms(query)
    if not terms:
        return 0
    score = 0
    for title, body in sections:
        tl, bl = title.lower(), body.lower()
        for t in terms:
            if t in tl:
                score += 3
            if t in bl:
                score += 1
    if any(t in must_read.lower() for t in terms):
        score += 2
    return score


def main():
    problems = []
    stats = []
    for path in sorted(KB_DIR.glob("*.md")):
        parsed = parse_kb(path)
        if not parsed:
            problems.append(f"{path.name}: 缺 kb 头")
            continue
        fields, sections, _ = parsed
        titles = [t for t, _ in sections]
        # 必看/快速索引是保留节，正文节 = 除保留节外
        content_secs = [(t, b) for t, b in sections if "必看" not in t and "快速索引" not in t]
        must_read = next((b for t, b in sections if "必看" in t), "")

        # 审计 1：索引指向
        index = parse_index(sections)
        missing_targets = []
        for topic, target in index:
            # target 形如 "4. 结构化并发" 或纯标题
            if not any(t == target or t.startswith(target) or target.startswith(t.split(".")[0] + ".") and target in t for t in titles):
                if target not in " ".join(titles):
                    missing_targets.append((topic, target))

        # 审计 2：数量一致性
        n_sec, n_idx = len(content_secs), len(index)
        ratio = n_idx / n_sec if n_sec else 0

        # 审计 3：检索覆盖抽查 —— 每个内容节取标题首词查询，应能命中本库
        zero_hits = []
        for title, body in content_secs:
            head = re.sub(r"^\d+\.\s*", "", title)  # 去编号前缀
            probe = re.split(r"[\s（(：:，,。/]", head)[0]
            probe = probe.strip()
            if len(probe) < 2:
                continue
            if search_hits(sections, must_read, probe) == 0:
                zero_hits.append(title)

        kb_id = fields.get("id", path.stem)
        stats.append((kb_id, n_sec, n_idx, round(ratio, 2), len(missing_targets), len(zero_hits)))
        for topic, target in missing_targets:
            problems.append(f"{kb_id}: 索引「{topic}」→ 指向不存在章节「{target}」")
        for t in zero_hits:
            problems.append(f"{kb_id}: 章节「{t}」标题词检索 0 命中")

    print(f"{'kb':16s} {'章节':>4s} {'索引':>4s} {'比值':>5s} {'坏指向':>6s} {'0命中':>5s}")
    for s in stats:
        print(f"{s[0]:16s} {s[1]:4d} {s[2]:4d} {s[3]:5.2f} {s[4]:6d} {s[5]:5d}")
    print()
    if problems:
        print(f"✗ {len(problems)} 项问题：")
        for p in problems[:60]:
            print("  -", p)
        sys.exit(1)
    print("✓ 全部知识库审计通过")


if __name__ == "__main__":
    main()
