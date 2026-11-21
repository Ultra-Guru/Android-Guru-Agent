#!/usr/bin/env python3
# ═══════════════════════════════════════════════════════════════════════════════
# test_fetch_static_analysis_deps.py —— static-analysis 依赖清单契约回归
#
# 背景（#353-⑤）：旧实现把 Maven jar 清单写死在 ci.yml 两处（缓存命中检查 +
#   下载循环），且 actions/cache 的 key 是静态字符串 —— 加一个 jar 却忘了 bump
#   版本号时，缓存永远命中旧内容、新 jar 每次 run 重新下载，且两处列表不同步
#   不会报错。现在清单收敛到 scripts/static-analysis-deps.txt（单一事实源），
#   ci.yml 用 hashFiles() 拼 key、用 fetch_static_analysis_deps.sh 下载。
#
# 本套件锁三条不变量：
#   1. 清单文件存在且每行都是合法 Maven 坐标（`g/a/v/a-v.jar`，无空格/CRLF）；
#   2. ci.yml 的 cache key 含 hashFiles('scripts/static-analysis-deps.txt')
#      —— 否则清单变更不会让缓存失效；
#   3. ci.yml 不再出现第二份手写 jar 清单（`for jar in kotlin-stdlib-...` 之类），
#      下载只经 fetch_static_analysis_deps.sh —— 单一事实源不可回退。
#
# 用法：python3 scripts/test_fetch_static_analysis_deps.py
# 退出码：0 通过 / 1 违反契约
# ═══════════════════════════════════════════════════════════════════════════════
import pathlib
import re
import sys

REPO_ROOT = pathlib.Path(__file__).resolve().parent.parent
MANIFEST = REPO_ROOT / "scripts" / "static-analysis-deps.txt"
FETCHER = REPO_ROOT / "scripts" / "fetch_static_analysis_deps.sh"
CI = REPO_ROOT / ".github" / "workflows" / "ci.yml"

# Maven 坐标：group/path/.../artifact/version/artifact-version.jar
COORD = re.compile(r"^[A-Za-z0-9_.\-]+(/[A-Za-z0-9_.\-]+)+/[A-Za-z0-9_.\-]+\.jar$")

failures = []


def check(name: str, ok: bool, detail: str = "") -> None:
    if ok:
        print(f"  ✓ {name}")
    else:
        print(f"  ✗ {name}" + (f"\n      {detail}" if detail else ""))
        failures.append(name)


def manifest_entries() -> list[str]:
    raw = MANIFEST.read_text(encoding="utf-8")
    return [
        line.strip()
        for line in raw.splitlines()
        if line.strip() and not line.lstrip().startswith("#")
    ]


def main() -> int:
    print("static-analysis 依赖清单契约：")

    check("清单文件存在", MANIFEST.is_file(), str(MANIFEST))
    if not MANIFEST.is_file():
        return 1

    entries = manifest_entries()
    check("清单非空", len(entries) > 0, "清单没有任何坐标行")

    bad = [e for e in entries if not COORD.match(e)]
    check("每行都是合法 Maven 坐标", not bad, f"非法行：{bad[:3]}")

    # 文件名与坐标尾部必须一致（`a/v/a-v.jar`），否则下载后 basename 对不上
    mismatched = []
    for e in entries:
        parts = e.split("/")
        if len(parts) < 3:
            mismatched.append(e)
            continue
        artifact, version, fname = parts[-3], parts[-2], parts[-1]
        if fname != f"{artifact}-{version}.jar":
            mismatched.append(e)
    check("文件名与坐标尾部一致（artifact-version.jar）", not mismatched,
          f"不一致：{mismatched[:3]}")

    check("无 CRLF（清单按 LF 提交）",
          "\r" not in MANIFEST.read_bytes().decode("utf-8", "replace"),
          "发现 \\r，Windows 编辑器可能改了行尾")

    check("下载脚本存在", FETCHER.is_file(), str(FETCHER))

    check("ci.yml 存在", CI.is_file(), str(CI))
    if not CI.is_file():
        return 1
    ci = CI.read_text(encoding="utf-8", errors="replace")

    check(
        "cache key 含清单哈希（清单变则缓存失效）",
        "hashFiles('scripts/static-analysis-deps.txt')" in ci,
        "ci.yml 里找不到 hashFiles('scripts/static-analysis-deps.txt')",
    )

    check(
        "下载经统一脚本（单一事实源）",
        "scripts/fetch_static_analysis_deps.sh" in ci,
        "ci.yml 未调用 scripts/fetch_static_analysis_deps.sh",
    )

    # 反例：第二份手写 jar 清单（旧实现的 `for jar in kotlin-stdlib-...`）
    check(
        "ci.yml 无第二份手写 jar 清单",
        not re.search(r"for jar in kotlin-stdlib", ci),
        "发现 `for jar in kotlin-stdlib...` —— 手写清单回退，必须删掉改用清单文件",
    )

    # 反例：静态 key（不含 hashFiles 的 kotlinc-...-libs-vN）
    static_key = re.search(r"key:\s*kotlinc-[0-9.]+-libs-v\d+", ci)
    check(
        "无静态版本化 cache key",
        static_key is None,
        f"发现静态 key：{static_key.group(0) if static_key else ''}",
    )

    print()
    if failures:
        print(f"❌ {len(failures)} 项不通过")
        return 1
    print(f"✅ 全部通过（{len(entries)} 个依赖坐标）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
