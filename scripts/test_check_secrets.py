#!/usr/bin/env python3
# ═════════════════════════════════════════════════════════════════════════════
# test_check_secrets.py —— S-01 回归：二进制密钥库入库必须被拦截
#
# 背景：发布签名 keystore 曾直接入库（v1.4.8「入库 keystore + 默认口令」
# 方案），私钥随公开仓库分发 = 签名防线失效。S-01 把 keystore 移出版本
# 控制，check_secrets.py 新增双通道拦截：
#   ① 文件名后缀：.jks / .keystore / .p12 / .pfx / .pkcs12
#   ② 文件头 magic：FE ED FE ED（Java KeyStore）—— 防改名绕过
# 本套件把两条通道 + 原文本指纹通道钉住：任何一条回退，CI 立即变红。
#
# 用法：python3 scripts/test_check_secrets.py
# 退出码：0 通过 / 1 违反契约
# ═════════════════════════════════════════════════════════════════════════════
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parent / "check_secrets.py"
sys.path.insert(0, str(SCRIPT.parent))
import check_secrets  # noqa: E402

JKS_MAGIC = b"\xfe\xed\xfe\xed"


class CheckSecretsKeystoreGateTest(unittest.TestCase):
    """S-01：keystore 二进制入库拦截（后缀 + magic 双通道）。"""

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.repo = Path(self.temp.name)
        self.git("init", "-b", "main")

    def git(self, *args):
        env = dict(os.environ, GIT_CONFIG_NOSYSTEM="1",
                   GIT_CONFIG_GLOBAL=os.devnull)
        return subprocess.run(
            ["git", "-C", str(self.repo), *args], env=env,
            capture_output=True, text=True, timeout=20,
        )

    def stage(self, name: str, data: bytes):
        """把二进制内容写入工作树并 git add（ls-files 读 index，无需 commit）。"""
        target = self.repo / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
        result = self.git("add", name)
        assert result.returncode == 0, result.stderr

    def findings(self) -> list[str]:
        return check_secrets.scan_repo(self.repo)

    # ── 通道①：后缀规则 ────────────────────────────────────────────────────

    def test_jks_suffix_blocked(self):
        self.stage("keystore/apex-release.jks", b"\x30\x82\x01\x50placeholder")
        hits = [f for f in self.findings() if "suffix rule" in f]
        self.assertTrue(hits, "入库 .jks 必须被后缀规则拦截")

    def test_p12_and_keystore_suffixes_blocked(self):
        for name in ("release.p12", "release.pfx", "release.pkcs12", "release.keystore"):
            self.stage(name, b"not-a-real-keystore-but-suffix-counts")
        hits = [f for f in self.findings() if "suffix rule" in f]
        self.assertEqual(len(hits), 4, "全部密钥库后缀都必须拦截")

    # ── 通道②：JKS magic 头（防改名绕过）──────────────────────────────────

    def test_renamed_jks_blocked_by_magic(self):
        self.stage("assets/blob.bin", JKS_MAGIC + b"\x00\x00rest-of-keystore")
        hits = [f for f in self.findings() if "JKS magic" in f]
        self.assertTrue(hits, "改名（非密钥库后缀）的 JKS 必须被 magic 头拦截")

    def test_jks_magic_not_at_offset_zero_not_flagged(self):
        # magic 必须位于文件头 —— 嵌在正文的同字节序列不误报（宽松但防噪）。
        self.stage("assets/data.bin", b"\x00\x00" + JKS_MAGIC + b"payload")
        hits = [f for f in self.findings() if "JKS magic" in f]
        self.assertFalse(hits, "文件头不是 FEEDFEED 的二进制不应误报")

    # ── 原文本指纹通道不回退 ────────────────────────────────────────────────

    def test_text_token_channel_still_works(self):
        self.stage("notes.txt", b"token: ghp_" + b"A" * 36 + b"\n")
        hits = [f for f in self.findings() if "GitHub token" in f]
        self.assertTrue(hits, "文本凭证指纹扫描（ghp_ 等）不得因 S-01 改动回退")

    # ── 干净仓库不误报 ──────────────────────────────────────────────────────

    def test_clean_repo_passes(self):
        self.stage("README.md", "# normal project docs\n".encode())
        self.stage("scripts/run.sh", b"#!/usr/bin/env bash\necho ok\n")
        self.stage("lib/libproot.so", b"\x7fELF\x02\x01\x01\x00" + b"\x00" * 32)
        self.assertEqual(self.findings(), [], "正常文本/ELF 二进制不应产生发现")

    # ── 端到端：脚本作为子进程，退出码语义（0 干净 / 1 拦截）────────────────

    def test_script_exit_code_on_keystore_fixture(self):
        self.stage("keystore/leaked.jks", b"\x30\x82\x01\x50placeholder")
        env = dict(os.environ, APEX_SECRETS_SCAN_ROOT=str(self.repo))
        result = subprocess.run(
            [sys.executable, str(SCRIPT)], env=env,
            capture_output=True, text=True, timeout=30,
        )
        self.assertEqual(result.returncode, 1, "发现入库 keystore 时脚本必须 exit 1")
        self.assertIn("Binary keystore", result.stdout)

    def test_script_exit_code_on_clean_fixture(self):
        self.stage("README.md", "# clean\n".encode())
        env = dict(os.environ, APEX_SECRETS_SCAN_ROOT=str(self.repo))
        result = subprocess.run(
            [sys.executable, str(SCRIPT)], env=env,
            capture_output=True, text=True, timeout=30,
        )
        self.assertEqual(result.returncode, 0, "干净 fixture 必须通过（exit 0）")


if __name__ == "__main__":
    unittest.main(verbosity=2)
