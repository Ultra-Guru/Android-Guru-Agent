#!/usr/bin/env python3
"""#270 selector outline token wiring; source contract, not a render test."""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parent.parent
CHATKIT = ROOT / "app/src/main/kotlin/com/apex/agent/ui/chatkit"
THEME = ROOT / "app/src/main/kotlin/com/apex/agent/ui/theme/AlphaTokens.kt"


def test_mode_and_role_selectors_share_semantic_outline():
    assert THEME.is_file(), "semantic AlphaTokens.kt is missing"
    token = THEME.read_text(encoding="utf-8")
    assert re.search(r"fun selectorOutlineColor\(\): Color\s*=\s*MaterialTheme\.colorScheme\.outlineVariant\.copy\(alpha\s*=\s*0\.6f\)", token)
    # v4 级联选择器重构：模式胶囊随 CodeModeCascadeSelector 从 chatkit/ModeSelector.kt
    # 迁至 screen/code（ModeSelector.kt 只剩共享助手）；契约跟随代码——模式
    # 与角色两个选择器胶囊仍必须共用同一语义描边 token。
    selectors = (
        ROOT / "app/src/main/kotlin/com/apex/agent/ui/screen/code/CodeModeCascadeSelector.kt",
        CHATKIT / "RoleSelector.kt",
    )
    for path in selectors:
        source = path.read_text(encoding="utf-8")
        assert "import com.apex.agent.ui.theme.selectorOutlineColor" in source, path.name
        assert re.search(r"border\s*=\s*BorderStroke\(1\.dp,\s*selectorOutlineColor\(\)\)", source), path.name
        assert "outlineVariant.copy(alpha = 0.6f)" not in source, path.name


if __name__ == "__main__":
    test_mode_and_role_selectors_share_semantic_outline()
    print("Mode and role selector outlines share one semantic token")
