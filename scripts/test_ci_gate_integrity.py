#!/usr/bin/env python3
# ═══════════════════════════════════════════════════════════════════════════════
# test_ci_gate_integrity.py —— CI 质量门的「不许回退」契约
#
# 背景（#353 剩余子项）：CI 里几条门禁曾把失败判成绿灯、或把校验换成覆盖，
# 让问题静默通过。这些不是「代码 bug」，是**门禁自身的可靠性**问题，一旦
# 回退就没有任何机制能发现。本套件把现状钉住：
#
#   ② KSP/Hilt 失败不得 exit 0（Hilt codegen 失败时 APK 缺 Dagger 组件，
#      装上去崩在启动期）；`daemon has terminated`（基础设施故障）必须单列
#      并硬失败，不能被并进「pre-existing Hilt 问题」放行。
#   ⑥ 每个 Gradle job 不得再用 `gradle wrapper --gradle-version X` 覆盖仓库里
#      已提交的 wrapper（会让本地/CI/发布产物版本脱钩且无告警）；必须是校验。
#   ⑧ check_code_quality.sh 的 xargs grep 必须带 -H（末批只剩一个文件时
#      命中行没有 `file:` 前缀，后续注释过滤与定位全部失配）。
#   ⑩ fetch_rootfs.sh 坏档必须自愈（删除重下），且计数变量非数字时归一为 0
#      （`[ "" -eq N ]` 会报 integer expression expected）。
#
# 用法：python3 scripts/test_ci_gate_integrity.py
# 退出码：0 通过 / 1 违反契约
# ═══════════════════════════════════════════════════════════════════════════════
import pathlib
import re
import sys

REPO_ROOT = pathlib.Path(__file__).resolve().parent.parent
CI = REPO_ROOT / ".github" / "workflows" / "ci.yml"
QUALITY = REPO_ROOT / "scripts" / "check_code_quality.sh"
ROOTFS = REPO_ROOT / "scripts" / "fetch_rootfs.sh"
VERIFY_WRAPPER = REPO_ROOT / ".github" / "actions" / "verify-wrapper" / "action.yml"

failures = []


def check(name: str, ok: bool, detail: str = "") -> None:
    if ok:
        print(f"  ✓ {name}")
    else:
        print(f"  ✗ {name}" + (f"\n      {detail}" if detail else ""))
        failures.append(name)


def read(p: pathlib.Path) -> str:
    return p.read_text(encoding="utf-8", errors="replace") if p.is_file() else ""


def strip_comments(text: str) -> str:
    """去掉整行注释（`#` 开头，允许前导空白）。

    必须做：这些契约本身就在注释里引述旧写法（「旧实现此处 exit 0」「旧实现
    每 job 跑 gradle wrapper ...」），不剥注释会把说明文字误判成违规。
    """
    return "\n".join(
        line for line in text.splitlines()
        if not line.lstrip().startswith("#")
    )


def main() -> int:
    print("CI 质量门契约：")

    for p in (CI, QUALITY, ROOTFS):
        check(f"{p.name} 存在", p.is_file(), str(p))
    if not CI.is_file():
        return 1

    ci = strip_comments(read(CI))

    # ── ② KSP / daemon 不得绿灯 ────────────────────────────────────────────
    # 只看**独立成行**的 `exit 0`：echo 文案里出现 "exit 0" 字样（解释旧行为）
    # 不算违规，所以必须按行匹配而不是全文子串。
    ksp_block = re.search(
        r'if \[ "\$KSP_ERRORS" -gt 0 \]; then([\s\S]{0,1200}?)^\s*fi\s*$',
        ci, re.MULTILINE,
    )
    check("② 找到 KSP 失败分支", ksp_block is not None, "ci.yml 里没有 KSP_ERRORS 判定块")
    if ksp_block:
        branch = ksp_block.group(1)
        standalone_exit0 = [
            line for line in branch.splitlines() if line.strip() == "exit 0"
        ]
        check(
            "② KSP 失败分支不再 exit 0",
            not standalone_exit0,
            "KSP 失败后仍有独立 `exit 0` —— 绿灯误导（#353-②）",
        )
    check(
        "② KSP 失败分支显式 exit 1",
        re.search(r"KSP_ERRORS\"\s*-gt\s*0[\s\S]{0,900}?exit 1", ci) is not None,
        "找不到 KSP 失败 → exit 1 的分支",
    )
    check(
        "② daemon 崩溃单列计数",
        "DAEMON_CRASHES=" in ci,
        "没有 DAEMON_CRASHES —— daemon 崩溃会被并入 KSP 计数后放行",
    )
    check(
        "② daemon 崩溃硬失败",
        re.search(r"DAEMON_CRASHES\"\s*-gt\s*0[\s\S]{0,600}?exit 1", ci) is not None,
        "daemon 崩溃未 exit 1",
    )
    check(
        "② KSP 计数只匹配错误行（不把 w: 当 error）",
        "grep -cE \"^e: .*\\[ksp\\]|Error occurred in KSP\"" in ci,
        "KSP 计数仍是宽匹配（w: 也会被算成 error）",
    )

    # ── ⑥ wrapper 不得被 CI 覆盖 ───────────────────────────────────────────
    # #782：校验逻辑收编为 .github/actions/verify-wrapper 复合动作（ci/apk/
    # release/media-results 四条流水线共用，单一事实源）。本契约随之改为
    # 校验「ci.yml 引用收编动作 + 动作内确有校验步骤」，防回退语义不变。
    verify_action = read(VERIFY_WRAPPER)
    regen = re.findall(r"gradle wrapper --gradle-version", ci)
    regen_action = re.findall(r"gradle wrapper --gradle-version", strip_comments(verify_action))
    check("⑥ 无 `gradle wrapper --gradle-version` 覆盖调用", not regen and not regen_action,
          f"ci.yml {len(regen)} 处 / 复合动作 {len(regen_action)} 处 wrapper 重生成")
    check(
        "⑥ wrapper 版本校验走 verify-wrapper 复合动作",
        "uses: ./.github/actions/verify-wrapper" in ci
        and "Verify Gradle wrapper is in sync" in verify_action
        and "distributionUrl" in verify_action,
        "ci.yml 未引用收编复合动作，或动作缺版本校验步骤",
    )

    # ── ⑧ check_code_quality.sh 的 grep 必须带 -H ──────────────────────────
    q = strip_comments(read(QUALITY))
    # 只有「后续还要按 `file:line:` 前缀过滤」的 grep 才必须带 -H（xargs 末批
    # 只剩一个文件时 grep 会省掉文件名前缀，过滤器随即失配）。纯 `wc -l` 的
    # 审计行不需要 -H，不能一刀切要求。
    q_lines = q.splitlines()
    missing_h = []
    for idx, line in enumerate(q_lines):
        if "xargs grep -n " not in line:
            continue
        following = "\n".join(q_lines[idx:idx + 4])
        if "grep -v" in following or "grep -vE" in following:
            missing_h.append(idx + 1)
    check("⑧ 依赖前缀过滤的 xargs grep 都带 -H", not missing_h,
          f"缺 -H 的行号：{missing_h}")
    check(
        "⑧ Gate 3 仍带 -H（未回退）",
        "xargs grep -HnE" in q,
        "Gate 3 的 -H 丢了",
    )

    # ── ⑩ fetch_rootfs 坏档自愈 + 计数归一 ─────────────────────────────────
    r = strip_comments(read(ROOTFS))
    check(
        "⑩ 坏档删除而非 fail 退出",
        "is corrupt (digest/size mismatch) — deleting for re-download" in r,
        "verify_staged 仍直接 fail（坏档无法自愈）",
    )
    check(
        "⑩ 计数变量归一为数字",
        r.count("''|*[!0-9]*)") >= 2,
        "计数归一化不足 2 处（fetch 主流程 + --check 分支都要）",
    )

    print()
    if failures:
        print(f"❌ {len(failures)} 项不通过")
        return 1
    print("✅ 全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
