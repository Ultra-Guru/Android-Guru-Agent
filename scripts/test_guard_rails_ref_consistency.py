#!/usr/bin/env python3
"""Exercise the real Guard Rails command against an outdated PR branch (#349).

The fixture has a legacy positional-argument script at the branch point, a
newer event-aware script on base, and an unrelated PR change on the old branch.
Execute the workflow's actual shell command after its configured checkout.
"""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

import yaml

ROOT = Path(__file__).resolve().parent.parent
WORKFLOW = ROOT / ".github/workflows/guard-rails.yml"
SCRIPT = ROOT / "scripts/check_commit_messages.py"


class GuardRailsCheckoutTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.repo = Path(self.temp.name)
        self.env = dict(os.environ, GIT_CONFIG_NOSYSTEM="1", GIT_CONFIG_GLOBAL=os.devnull)
        self.git("init", "-b", "base")
        self.git("config", "user.name", "Fixture Author")
        self.git("config", "user.email", "fixture@example.invalid")
        self.git("config", "commit.gpgsign", "false")
        scripts = self.repo / "scripts"
        scripts.mkdir()
        # This intentionally models the historical positional-only interface.
        (scripts / SCRIPT.name).write_text(
            'import argparse\np = argparse.ArgumentParser()\n'
            'p.add_argument("base")\np.add_argument("head")\np.parse_args()\n',
            encoding="utf-8",
        )
        self.git("add", ".")
        self.git("commit", "-m", "old base predates the convention")
        self.fork = self.git("rev-parse", "HEAD")
        self.git("checkout", "-b", "topic")
        (self.repo / "feature.txt").write_text("PR payload\n", encoding="utf-8")
        self.git("add", ".")

    def git(self, *args):
        result = subprocess.run(
            ["git", "-C", str(self.repo), *args], env=self.env,
            capture_output=True, text=True, encoding="utf-8", timeout=20,
        )
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        return result.stdout.strip()

    def run_workflow(self, subject):
        self.git("commit", "-m", subject)
        head = self.git("rev-parse", "HEAD")
        self.git("checkout", "base")
        (self.repo / "scripts" / SCRIPT.name).write_text(
            SCRIPT.read_text(encoding="utf-8"), encoding="utf-8",
        )
        self.git("add", ".")
        # Deliberately invalid base commit: it must NOT be checked as PR work.
        self.git("commit", "-m", "base updates script interface")
        base = self.git("rev-parse", "HEAD")
        self.git("merge", "--no-ff", "topic", "-m", "Merge fixture PR")
        merged = self.git("rev-parse", "HEAD")
        workflow = yaml.safe_load(WORKFLOW.read_text(encoding="utf-8"))
        steps = workflow["jobs"]["commit-convention"]["steps"]
        checkout = next(step for step in steps if str(step.get("uses", "")).startswith("actions/checkout@"))
        selected = checkout.get("with", {}).get("ref", "")
        if not selected:
            ref = merged
        elif "github.event.pull_request.head.sha" in selected:
            ref = head
        else:
            self.fail("Fixture must explicitly model the workflow's checkout ref: " + str(selected))
        self.git("checkout", "--detach", ref)
        command = next(step["run"] for step in steps if "check_commit_messages.py" in step.get("run", ""))
        env = dict(self.env, IS_PR="true", PR_BASE=base, PUSH_BEFORE=self.fork, PUSH_REF="main")
        return subprocess.run(
            ["bash", "--noprofile", "--norc", "-e", "-o", "pipefail", "-c", command],
            cwd=self.repo, env=env, capture_output=True, text=True, encoding="utf-8", timeout=30,
        )

    def test_old_pr_uses_event_aware_script_from_merged_tree(self):
        result = self.run_workflow("fix(fixture): valid PR change")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("1 个提交全部合规", result.stdout)
        self.assertNotIn("base updates script interface", result.stdout)

    def test_invalid_pr_subject_is_not_hidden_by_merge_ref(self):
        result = self.run_workflow("invalid PR subject")
        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assertIn("invalid PR subject", result.stdout)
        self.assertNotIn("base updates script interface", result.stdout)


if __name__ == "__main__":
    unittest.main(verbosity=2)
