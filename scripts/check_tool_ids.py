#!/usr/bin/env python3
"""Tool ID uniqueness gate — semantic, scope-limited static check.

Replaces the previous `grep "override val id = "` step, which silently missed:
  * typed declarations (`override val id: String = "X"`),
  * `BaseTool(id = "X", …)` constructor declarations,
  * modules outside core:tool-registry / platform:terminal / app
    (e.g. core:code-tools),
  * constant-vs-literal collisions — the old labels `CONST_REF:name=value` and
    `LITERAL:value` could never compare equal.

Scope: only classes that really are agent tools (AgentTool /
StructuredAgentTool / StreamingAgentTool / TerminalTool / BaseTool).
Diagnostic-rule ids, hook ids, backend ids and similar `override val id`
declarations stay out of scope — flagging them would be wrong, not stricter.

Dynamically computed ids (`get() =`, calls, templates) cannot be resolved
statically; they are listed as UNRESOLVED so nobody reads "no duplicates" as
"everything was verified".

Exit codes: 0 = pass, 1 = duplicates found, 2 = scan could not be performed.
"""
from __future__ import annotations

import re
import sys
from dataclasses import dataclass
from pathlib import Path

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")  # type: ignore[attr-defined]

ROOT = Path(__file__).resolve().parent.parent

# Every module that may declare agent tools.
SOURCE_ROOTS = (
    "core/tool-registry/src/main/kotlin",
    "core/code-tools/src/main/kotlin",
    "core/code-engine/src/main/kotlin",
    "platform/terminal/src/main/kotlin",
    "platform/browser-agent/src/main/kotlin",
    "app/src/main/kotlin",
)

TOOL_BASES = (
    "AgentTool", "StructuredAgentTool", "StreamingAgentTool", "TerminalTool", "BaseTool",
)

DECL_HEAD = re.compile(
    r"(?m)^[ \t]*(?:(?:public|internal|private|protected|abstract|open|sealed|final|"
    r"data|value|inline|inner|enum|annotation|expect|actual|companion)\s+)*"
    r"(?:class|object)\s+(\w+)"
)
ID_DECL = re.compile(r"override\s+val\s+id\b\s*(?::[^=\n]*)?=\s*([^\n;]+)")
BASE_TOOL_CALL = re.compile(r"\bBaseTool\s*\(([^)]*)\)", re.S)
ID_ARG = re.compile(r"\bid\s*=\s*\"([^\"]*)\"")
CONST_DECL = re.compile(r"\bconst\s+val\s+(\w+)\s*(?::\s*String\s*)?=\s*\"([^\"]*)\"")
STRING_LITERAL = re.compile(r'^"([^"]*)"$')
IDENTIFIER = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")


@dataclass
class Hit:
    path: Path
    line: int
    cls: str
    value: str | None
    raw: str


def skip_string(text: str, i: int) -> int:
    """Return the index just past the string literal starting at `i`."""
    n = len(text)
    j = i + 1
    while j < n:
        if text[j] == "\\":
            j += 2
            continue
        if text[j] == '"':
            return j + 1
        j += 1
    return n


def strip_comments(text: str) -> str:
    """Blank comments out, preserving offsets, line numbers and string literals."""
    out: list[str] = []
    i, n = 0, len(text)
    while i < n:
        if text.startswith("//", i):
            end = text.find("\n", i)
            end = n if end == -1 else end
            out.append(" " * (end - i))
            i = end
        elif text.startswith("/*", i):
            end = text.find("*/", i + 2)
            end = n if end == -1 else end + 2
            out.append("".join(ch if ch == "\n" else " " for ch in text[i:end]))
            i = end
        elif text[i] == '"':
            end = skip_string(text, i)
            out.append(text[i:end])
            i = end
        else:
            out.append(text[i])
            i += 1
    return "".join(out)


def declaration_body(text: str, start: int) -> tuple[str, str] | None:
    """Split a class/object declaration into (header, body) starting at `start`.

    A declaration without a `{` body returns an empty body so callers can still
    inspect the header (e.g. `BaseTool(id = "X", …)` with no class body).
    """
    paren = 0
    i = start
    while i < len(text):
        ch = text[i]
        if ch == '"':
            i = skip_string(text, i)
            continue
        if ch == "(":
            paren += 1
        elif ch == ")":
            paren = max(0, paren - 1)
        elif ch == "{" and paren == 0:
            header = text[start:i]
            depth = 0
            j = i
            while j < len(text):
                if text[j] == '"':
                    j = skip_string(text, j)
                    continue
                if text[j] == "{":
                    depth += 1
                elif text[j] == "}":
                    depth -= 1
                    if depth == 0:
                        return header, text[i:j + 1]
                j += 1
            return header, text[i:]
        elif ch == "\n" and paren == 0:
            # Body-less declaration (`class X : BaseTool(id = "…", …)`) —
            # keep the header, it may carry a constructor id argument.
            return text[start:i], ""
        i += 1
    return None


def depth_at(body: str, offset: int) -> int:
    """Brace depth at `offset` inside a body that starts with '{' (== 1 at top)."""
    depth = 0
    i = 0
    while i < offset:
        ch = body[i]
        if ch == '"':
            i = skip_string(body, i)
            continue
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
        i += 1
    return depth


def scan(path: Path) -> tuple[list[Hit], list[Hit]]:
    text = strip_comments(path.read_text(encoding="utf-8", errors="replace"))
    constants = dict(CONST_DECL.findall(text))
    resolved: list[Hit] = []
    unresolved: list[Hit] = []

    for match in DECL_HEAD.finditer(text):
        split = declaration_body(text, match.start())
        if split is None:
            continue
        header, body = split
        if not any(re.search(rf"\b{base}\b", header) for base in TOOL_BASES):
            continue
        cls = match.group(1)
        base_line = text.count("\n", 0, match.start()) + 1

        for call in BASE_TOOL_CALL.finditer(header):
            for arg in ID_ARG.finditer(call.group(1)):
                resolved.append(Hit(path, base_line, cls, arg.group(1), "BaseTool(id=…)"))

        for m in ID_DECL.finditer(body):
            if depth_at(body, m.start()) != 1:
                continue  # nested class / local scope owns its own declaration
            raw = " ".join(m.group(1).split())
            line = base_line + body.count("\n", 0, m.start())
            literal = STRING_LITERAL.match(raw)
            if literal:
                resolved.append(Hit(path, line, cls, literal.group(1), raw))
            elif IDENTIFIER.match(raw) and raw in constants:
                resolved.append(Hit(path, line, cls, constants[raw], raw))
            else:
                unresolved.append(Hit(path, line, cls, None, raw))
    return resolved, unresolved


def main() -> int:
    missing = [root for root in SOURCE_ROOTS if not (ROOT / root).is_dir()]
    if missing:
        print("✗ 扫描根不存在（门禁未实际执行，绝不当作通过）：" + ", ".join(missing))
        return 2

    files = sorted(p for root in SOURCE_ROOTS for p in (ROOT / root).rglob("*.kt"))
    if not files:
        print("✗ 没有找到任何 Kotlin 源文件（门禁未实际执行）")
        return 2

    resolved: list[Hit] = []
    unresolved: list[Hit] = []
    for path in files:
        r, u = scan(path)
        resolved.extend(r)
        unresolved.extend(u)

    by_value: dict[str, list[Hit]] = {}
    for hit in resolved:
        by_value.setdefault(hit.value or "", []).append(hit)
    duplicates = {v: hits for v, hits in by_value.items() if len(hits) > 1}

    if duplicates:
        print(f"✗ 工具 ID 重复（{len(duplicates)} 个值）：")
        for value, hits in sorted(duplicates.items()):
            print(f'  - "{value}"')
            for hit in hits:
                print(f"      {hit.path.relative_to(ROOT).as_posix()}:{hit.line}  {hit.cls}")
        return 1

    print(
        f"✓ 工具 ID 唯一性检查通过：{len(resolved)} 个静态可解析 ID，"
        f"0 个重复，扫描 {len(files)} 个文件"
    )
    if unresolved:
        print(f"  ℹ {len(unresolved)} 个动态 ID 未纳入静态比较（需人工/运行时校验）：")
        for hit in unresolved:
            print(f"      {hit.path.relative_to(ROOT).as_posix()}:{hit.line}  {hit.cls}  {hit.raw}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
