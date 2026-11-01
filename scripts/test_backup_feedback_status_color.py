#!/usr/bin/env python3
"""#268: backup result feedback must use theme-aware status colors.

This narrow source-wiring regression complements Compose compilation: it does
not claim to measure rendered contrast on a device or glass/image backgrounds.
"""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / "app/src/main/kotlin/com/apex/agent/ui/screen/settings/BackupSection.kt"
THEME = ROOT / "app/src/main/kotlin/com/apex/agent/ui/theme/StatusColors.kt"


def test_backup_result_color_uses_status_tokens():
    source = SOURCE.read_text(encoding="utf-8")
    theme = THEME.read_text(encoding="utf-8")
    # Restrict to the message presentation branch, not other settings controls.
    feedback = source.split("state.message?.let { msg ->", 1)[1].split("// ═══ 导入确认", 1)[0]
    assert re.search(r"color\s*=\s*if\s*\(state\.isError\)\s*statusError\(\)\s*else\s*statusSuccess\(\)", feedback)
    assert "BackupSuccessGreen" not in source
    assert "fun statusSuccess(): Color = LocalExtendedColors.current.success" in theme
    assert "fun statusError(): Color = MaterialTheme.colorScheme.error" in theme


if __name__ == "__main__":
    test_backup_result_color_uses_status_tokens()
    print("Backup result feedback uses status tokens")
