#!/usr/bin/env python3
"""Black-box tests of the actual ci.yml tool-ID step (no Kotlin compilation)."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SOURCE_ROOTS = (
    'core/tool-registry/src/main/kotlin', 'platform/terminal/src/main/kotlin',
    'app/src/main/kotlin', 'core/code-tools/src/main/kotlin',
)


def workflow_command():
    text = (ROOT / '.github/workflows/ci.yml').read_text()
    block = text.split('      - name: Check tool ID uniqueness\n', 1)[1]
    block = block.split('\n      - name:', 1)[0]
    lines = block.splitlines()[1:]
    return '\n'.join(line[10:] for line in lines)


class ToolIdGateTest(unittest.TestCase):
    def gate(self, source, extra=None):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            for directory in SOURCE_ROOTS:
                (root / directory).mkdir(parents=True)
            (root / SOURCE_ROOTS[0] / 'Fixture.kt').write_text(source)
            for path, content in (extra or {}).items():
                target = root / path
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text(content)
            (root / 'scripts').mkdir()
            for script in ROOT.glob('scripts/*tool_ids*.py'):
                shutil.copy(script, root / 'scripts' / script.name)
            return subprocess.run(['bash', '-eo', 'pipefail', '-c', workflow_command()],
                                  cwd=root, text=True, capture_output=True)

    def duplicate(self, source, extra=None):
        result = self.gate(source, extra)
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)

    def test_typed_property_collides_with_literal(self):
        self.duplicate('''class First : AgentTool {
 override val id = "collision"
}
class Second : StructuredAgentTool {
 override val id: String = "collision"
}
''')

    def test_base_tool_constructor_collides_with_literal(self):
        self.duplicate('''class First : AgentTool {
 override val id = "collision"
}
class Second : BaseTool(id = "collision", description = "fixture")
''')

    def test_constant_and_literal_share_value_namespace(self):
        self.duplicate('''class First : AgentTool {
 override val id = "collision"
}
class Second : StreamingAgentTool {
 override val id = TOOL_ID
 companion object { const val TOOL_ID = "collision" }
}
''')


if __name__ == '__main__':
    unittest.main(verbosity=2)
