#!/usr/bin/env python3
# ═══════════════════════════════════════════════════════════════════════════════
# check_test_discipline.py — 测试纪律棘轮门禁（issue #276）
# ═══════════════════════════════════════════════════════════════════════════════
# 背景：测试纪律漂移——runBlocking 替代 runTest（虚拟时间不推进、超时语义
#   失真）、Thread.sleep 轮询替代假钟注入（墙钟依赖、CI 高负载下 flake）。
#   全仓存量（2026-10 基线）：runBlocking 696 处 / Thread.sleep 33 处，横跨
#   83 个测试文件 —— 一次性清零不现实也不安全（大量集成风格测试刻意使用
#   真实异步 + 轮询等待，见 AGENTS.md「backgroundScope 不受 advanceUntilIdle
#   推进」的既有约定）。
#
# 策略：**棘轮（ratchet）**——基线钉在当前存量，只允许减少不允许增加：
#   • 新增 runBlocking / Thread.sleep 超过基线 → 红（防止漂移继续恶化）；
#   • 低于基线 → 绿，并提示把基线降下来（存量清理可量化、可验收）；
#   • 清理存量后跑 `--update-baseline` 重钉基线（只降不升）。
#
# 判定口径：
#   • 只扫 */src/test/ 下的 .kt（main 源码的 runBlocking 另属反模式，
#     由 check_code_quality.sh 域外管辖，不在此重复）；
#   • 词边界匹配（不误伤 runBlockingTest 这类历史命名或注释中的引用词）；
#   • 注释行同样计数 —— 测试文件里注释掉的 runBlocking 复活时不应静默放行。
#
# 用法:
#   python3 scripts/check_test_discipline.py                  # 棘轮检查
#   python3 scripts/check_test_discipline.py --update-baseline # 存量清理后重钉
# 退出码：0 = 通过 / 1 = 超基线（或参数错误）
# ═══════════════════════════════════════════════════════════════════════════════
import argparse
import pathlib
import re
import sys

REPO_ROOT = pathlib.Path(__file__).resolve().parent.parent

# ── 棘轮基线（issue #276：只降不升；清理后 --update-baseline 重钉）──────────
BASELINE_RUN_BLOCKING = 696
BASELINE_THREAD_SLEEP = 33

RUN_BLOCKING_RE = re.compile(r"\brunBlocking\b")
THREAD_SLEEP_RE = re.compile(r"\bThread\.sleep\b")

# script 自身在 git 基线段中的位置（--update-baseline 用 sed 式重写）
SELF = pathlib.Path(__file__).resolve()


def scan() -> tuple[int, int, int]:
    """返回 (runBlocking 数, Thread.sleep 数, 命中文件数)。"""
    rb_total = sleep_total = files = 0
    for path in REPO_ROOT.rglob("src/test/**/*.kt"):
        if ".git" in path.parts or "build" in path.parts:
            continue
        text = path.read_text(encoding="utf-8", errors="replace")
        rb = len(RUN_BLOCKING_RE.findall(text))
        sl = len(THREAD_SLEEP_RE.findall(text))
        if rb or sl:
            files += 1
            rb_total += rb
            sleep_total += sl
    return rb_total, sleep_total, files


def rewrite_baseline(rb: int, sl: int) -> None:
    text = SELF.read_text(encoding="utf-8")
    text = re.sub(
        r"BASELINE_RUN_BLOCKING = \d+", f"BASELINE_RUN_BLOCKING = {rb}", text
    )
    text = re.sub(
        r"BASELINE_THREAD_SLEEP = \d+", f"BASELINE_THREAD_SLEEP = {sl}", text
    )
    SELF.write_text(text, encoding="utf-8")


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")

    parser = argparse.ArgumentParser(description="测试纪律棘轮门禁（issue #276）")
    parser.add_argument(
        "--update-baseline",
        action="store_true",
        help="把基线重钉到当前存量（只降不升：当前值高于旧基线时拒绝）",
    )
    args = parser.parse_args()

    rb, sl, files = scan()
    print(f"当前存量：runBlocking={rb}（基线 {BASELINE_RUN_BLOCKING}）"
          f" / Thread.sleep={sl}（基线 {BASELINE_THREAD_SLEEP}）"
          f" / 命中 {files} 个测试文件")

    if args.update_baseline:
        if rb > BASELINE_RUN_BLOCKING or sl > BASELINE_THREAD_SLEEP:
            print("✗ 拒绝重钉：当前存量高于既有基线（棘轮只降不升）")
            return 1
        rewrite_baseline(rb, sl)
        print(f"✓ 基线已重钉：runBlocking={rb} / Thread.sleep={sl}")
        return 0

    ok = True
    if rb > BASELINE_RUN_BLOCKING:
        print(f"✗ runBlocking 超基线（{rb} > {BASELINE_RUN_BLOCKING}）："
              f"新测试请用 runTest + 假钟注入（见 AGENTS.md 测试纪律；issue #276）")
        ok = False
    elif rb < BASELINE_RUN_BLOCKING:
        print(f"↓ runBlocking 低于基线（{rb} < {BASELINE_RUN_BLOCKING}）："
              f"清理有效！提交后跑 scripts/check_test_discipline.py --update-baseline 重钉")
    if sl > BASELINE_THREAD_SLEEP:
        print(f"✗ Thread.sleep 超基线（{sl} > {BASELINE_THREAD_SLEEP}）："
              f"轮询等待请改假钟（clock 注入）或 awaitCondition 工具（issue #276）")
        ok = False
    elif sl < BASELINE_THREAD_SLEEP:
        print(f"↓ Thread.sleep 低于基线（{sl} < {BASELINE_THREAD_SLEEP}）："
              f"清理有效！提交后跑 scripts/check_test_discipline.py --update-baseline 重钉")

    if ok:
        print("✓ 测试纪律棘轮通过（未超基线）")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
