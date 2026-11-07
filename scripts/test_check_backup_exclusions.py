#!/usr/bin/env python3
# ═════════════════════════════════════════════════════════════════════════════
# test_check_backup_exclusions.py —— S-05 回归：凭据类 prefs 必须被备份排除
#
# 背景：mcp_host_prefs（MCP Host Bearer token，明文）与 pulse_mcp_secure_prefs
# （PulseMCP 凭据，EncryptedSharedPreferences）曾长期漏出 data_extraction_rules /
# legacy_backup_rules 的排除表 —— 凭据随云备份与设备迁移导出。S-05 补齐并
# 新增 scripts/check_backup_exclusions.py 静态校验；本套件钉住其双向语义：
#   ① fixture 漏配任一节点 → check() 必须报出该节点；
#   ② 全部配置齐备 → check() 必须干净；
#   ③ 敏感名单外的新 prefs（普通设置）不受约束（防误报卡死日常开发）。
#
# 用法：python3 scripts/test_check_backup_exclusions.py
# 退出码：0 通过 / 1 违反契约
# ═════════════════════════════════════════════════════════════════════════════
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parent / "check_backup_exclusions.py"
sys.path.insert(0, str(SCRIPT.parent))
import check_backup_exclusions as cbe  # noqa: E402


def fixture_repo(tmp: Path, *, omit_legacy: bool = False, omit_transfer: bool = False):
    """构造最小 fixture：两个敏感 prefs + 一个普通 prefs + 三份排除规则。"""
    src = tmp / "app" / "src" / "main" / "kotlin" / "Fixture.kt"
    src.parent.mkdir(parents=True)
    src.write_text(
        'class Fixture {\n'
        '    fun prefs(ctx: android.content.Context) {\n'
        '        ctx.getSharedPreferences("mcp_host_prefs", 0)\n'
        '    }\n'
        '    companion object {\n'
        '        const val PREFS_NAME = "pulse_mcp_secure_prefs"\n'
        '    }\n'
        '    fun encrypted(ctx: android.content.Context, key: Any) {\n'
        '        androidx.security.crypto.EncryptedSharedPreferences.create(\n'
        '            ctx, PREFS_NAME, key,\n'
        '            androidx.security.crypto.EncryptedSharedPreferences'
        '.PrefKeyEncryptionScheme.AES256_SIV,\n'
        '            androidx.security.crypto.EncryptedSharedPreferences'
        '.PrefValueEncryptionScheme.AES256_GCM)\n'
        '    }\n'
        '    fun fallback(ctx: android.content.Context) {\n'
        '        ctx.getSharedPreferences("$PREFS_NAME-fallback", 0)\n'
        '    }\n'
        '    fun plain(ctx: android.content.Context) {\n'
        '        ctx.getSharedPreferences("apex_settings", 0)\n'
        '    }\n'
        '}\n',
        encoding="utf-8",
    )
    xml_dir = tmp / "app" / "src" / "main" / "res" / "xml"
    xml_dir.mkdir(parents=True)

    sensitive = ["mcp_host_prefs.xml", "pulse_mcp_secure_prefs.xml",
                 "pulse_mcp_secure_prefs-fallback.xml"]

    def excludes(names):
        return "".join(
            f'<exclude domain="sharedpref" path="{n}" />' for n in names
        )

    transfer_block = "" if omit_transfer else f"<device-transfer>{excludes(sensitive)}</device-transfer>"
    legacy_block = "" if omit_legacy else f"<full-backup-content>{excludes(sensitive)}</full-backup-content>"

    (xml_dir / "data_extraction_rules.xml").write_text(
        "<data-extraction-rules>"
        f"<cloud-backup>{excludes(sensitive)}</cloud-backup>"
        f"{transfer_block}"
        "</data-extraction-rules>",
        encoding="utf-8",
    )
    (xml_dir / "legacy_backup_rules.xml").write_text(
        f"<full-backup-content>{legacy_block}</full-backup-content>".replace(
            "<full-backup-content></full-backup-content>",
            "<full-backup-content />") if not legacy_block else legacy_block,
        encoding="utf-8",
    )
    return tmp


class BackupExclusionGateTest(unittest.TestCase):
    """S-05：凭据类 prefs 备份排除静态校验的双向契约。"""

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def test_complete_configuration_passes(self):
        fixture_repo(self.root)
        self.assertEqual(cbe.check(self.root), [], "三节点齐备时必须通过")

    def test_missing_legacy_node_fails(self):
        fixture_repo(self.root, omit_legacy=True)
        failures = cbe.check(self.root)
        self.assertTrue(any("legacy/full-backup-content" in f for f in failures),
                        f"legacy 漏配必须报出，实际：{failures}")

    def test_missing_device_transfer_node_fails(self):
        fixture_repo(self.root, omit_transfer=True)
        failures = cbe.check(self.root)
        self.assertTrue(any("data-extraction/device-transfer" in f for f in failures),
                        f"device-transfer 漏配必须报出，实际：{failures}")

    def test_sensitive_names_extracted_from_literals_and_templates(self):
        fixture_repo(self.root)
        names = cbe.collect_prefs_names()
        # 直接给 collect_prefs_names 喂 fixture 根（check() 已设全局，这里显式再设一次）
        cbe.MAIN_SOURCE_ROOTS = [self.root / "app" / "src" / "main", self.root / "platform"]
        names = cbe.collect_prefs_names()
        self.assertIn("mcp_host_prefs", names, "字面量 prefs 名必须被提取")
        self.assertIn("pulse_mcp_secure_prefs", names, "const prefs 名必须被提取")
        self.assertIn("pulse_mcp_secure_prefs-fallback", names, "模板串 fallback 变体必须被提取")
        self.assertIn("apex_settings", names, "普通 prefs 名同样被提取（供名单过滤）")

    def test_plain_prefs_not_flagged(self):
        fixture_repo(self.root)
        failures = cbe.check(self.root)
        self.assertFalse(any("apex_settings" in f for f in failures),
                         "非敏感 prefs 不在排除表属正常，不得误报")


if __name__ == "__main__":
    unittest.main(verbosity=2)
