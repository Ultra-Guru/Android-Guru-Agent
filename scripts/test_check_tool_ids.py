#!/usr/bin/env python3
"""Regression suite for the tool-ID uniqueness gate (scripts/check_tool_ids.py).

The suite copies the real scanner into a temporary module tree and runs it, so
the tests exercise the production script rather than a reimplementation. A
negative control proves the harness can report success; without it, "duplicate
detected" could be satisfied by a scanner that merely crashed.
"""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SCANNER = ROOT / 'scripts/check_tool_ids.py'
SOURCE_ROOTS = (
    'core/tool-registry/src/main/kotlin',
    'core/code-tools/src/main/kotlin',
    'core/code-engine/src/main/kotlin',
    'platform/terminal/src/main/kotlin',
    'platform/browser-agent/src/main/kotlin',
    'app/src/main/kotlin',
)


class ToolIdGateTest(unittest.TestCase):
    def gate(self, files):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            for directory in SOURCE_ROOTS:
                (root / directory).mkdir(parents=True)
            (root / 'scripts').mkdir()
            shutil.copy(SCANNER, root / 'scripts' / SCANNER.name)
            for relative, content in files.items():
                target = root / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text(content, encoding='utf-8')
            return subprocess.run(
                ['python3', 'scripts/check_tool_ids.py'],
                cwd=root, text=True, capture_output=True, timeout=60,
            )

    def assert_duplicate(self, files, value='collision'):
        result = self.gate(files)
        output = result.stdout + result.stderr
        self.assertEqual(1, result.returncode, output)
        self.assertIn(value, output)

    def assert_clean(self, files):
        result = self.gate(files)
        output = result.stdout + result.stderr
        self.assertEqual(0, result.returncode, output)
        return output

    def test_harness_accepts_unique_ids(self):
        # Negative control: a crashing or non-running scanner fails here.
        output = self.assert_clean({
            'core/tool-registry/src/main/kotlin/A.kt': '''class First : AgentTool {
 override val id = "alpha"
}
class Second : StructuredAgentTool {
 override val id: String = "beta"
}
''',
        })
        self.assertIn('2 个静态可解析 ID', output)

    def test_typed_declaration_collides_with_untyped(self):
        self.assert_duplicate({
            'core/tool-registry/src/main/kotlin/A.kt': '''class First : AgentTool {
 override val id = "collision"
}
class Second : StructuredAgentTool {
 override val id: String = "collision"
}
''',
        })

    def test_base_tool_constructor_collides_with_override(self):
        self.assert_duplicate({
            'core/tool-registry/src/main/kotlin/A.kt': '''class First : AgentTool {
 override val id = "collision"
}
class Second : BaseTool(
    id = "collision",
    name = "Second",
    description = "fixture"
)
''',
        })

    def test_constant_and_literal_share_one_namespace(self):
        self.assert_duplicate({
            'core/tool-registry/src/main/kotlin/A.kt': '''class First : AgentTool {
 override val id = "collision"
}
class Second : StreamingAgentTool {
 override val id = TOOL_ID
 companion object { const val TOOL_ID = "collision" }
}
''',
        })

    def test_cross_module_collision_is_detected(self):
        # The previous gate never scanned core:code-tools at all.
        self.assert_duplicate({
            'core/tool-registry/src/main/kotlin/A.kt': '''class First : AgentTool {
 override val id = "collision"
}
''',
            'core/code-tools/src/main/kotlin/B.kt': '''class Second : BaseTool(
    id = "collision", name = "Second", description = "fixture"
)
''',
        })

    def test_non_tool_ids_are_out_of_scope(self):
        output = self.assert_clean({
            'platform/terminal/src/main/kotlin/Rules.kt': '''class Rule : DiagnosticRule {
 override val id: String = "collision"
}
class First : AgentTool {
 override val id = "collision"
}
''',
        })
        self.assertIn('1 个静态可解析 ID', output)

    def test_nested_override_is_not_double_counted(self):
        output = self.assert_clean({
            'core/tool-registry/src/main/kotlin/A.kt': '''class Outer : AgentTool {
 override val id = "alpha"
 class Inner {
  override val id = "alpha"
 }
}
''',
        })
        self.assertIn('1 个静态可解析 ID', output)

    def test_dynamic_id_is_reported_not_silently_passed(self):
        output = self.assert_clean({
            'core/tool-registry/src/main/kotlin/A.kt': '''class First : AgentTool {
 override val id: String get() = computeId()
}
''',
        })
        self.assertIn('动态 ID', output)

    def test_comment_mention_is_ignored(self):
        output = self.assert_clean({
            'core/tool-registry/src/main/kotlin/A.kt': '''// class Ghost : AgentTool { override val id = "alpha" }
class First : AgentTool {
 override val id = "beta"
}
''',
        })
        self.assertIn('1 个静态可解析 ID', output)


if __name__ == '__main__':
    unittest.main(verbosity=2)
