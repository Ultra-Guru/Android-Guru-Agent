#!/usr/bin/env python3
"""check_intents.py — DCP 意图清单 CI 门禁（S02）。

与 core:tool-registry 的 IntentCatalog.validate() 同口径校验
app/src/main/assets/device 目录下的 intents.json：

  1. JSON 可解析、schema 段存在；
  2. id 唯一且形如 package.uninstall（小写点分）；
  3. risk / requiredPrivilege 枚举合法；
  4. argv[0]（与 purgeArgv[0]）在白名单 —— 与
     IntentCatalog.EXECUTABLE_WHITELIST 同步维护；
  5. 占位符 {xxx} 全部在 params ∪ internal 内声明（闭合）；
  6. reversible=false 的意图必须有非空 preflight、verify 与 rollbackHint；
  7. params.kind 封闭集合；enum kind 必须带非空 values；
  8. pathScope 只含绝对路径前缀；
  9. 覆盖下限：package ≥ 10 / file ≥ 8 / system ≥ 6 / probe ≥ 4。

用法：python3 scripts/check_intents.py [路径]
退出码：0 = 通过；1 = 存在校验问题（CI 拦截）。
"""
import json
import re
import sys
from pathlib import Path

ID_REGEX = re.compile(r"^[a-z0-9_]+(\.[a-z0-9_]+)+$")
RISKS = {"READ", "LOW", "MEDIUM", "HIGH", "CRITICAL"}
PRIVILEGES = {"NORMAL_SHELL", "SHIZUKU", "ROOT"}
KINDS = {"package", "enum", "user", "setting-key", "path", "text", "payload"}
# 与 IntentCatalog.EXECUTABLE_WHITELIST / PURGE_EXECUTABLE_WHITELIST 同口径。
EXECUTABLES = {
    "am", "pm", "dumpsys", "cmd", "settings", "getprop", "setprop",
    "id", "df", "getenforce", "stat", "ls", "cat", "tee", "mv",
    "mkdir", "md5sum", "pidof",
}
PURGE_EXECUTABLES = {"rm"}
MIN_COUNTS = {"package": 10, "file": 8, "system": 6, "probe": 4}


def placeholders_of(argv):
    out = set()
    for element in argv:
        out.update(re.findall(r"\{([a-zA-Z0-9_]+)\}", element))
    return out


def validate(path: Path):
    issues = []
    try:
        doc = json.loads(path.read_text(encoding="utf-8"))
    except Exception as e:  # noqa: BLE001 — CI 门禁要给出可读报错
        print(f"FAIL: intents.json 解析失败：{e}")
        return None

    schema = doc.get("schema")
    if schema != "apex-device-intents/v1":
        issues.append(f"catalog.schema: 期望 apex-device-intents/v1，实际 {schema!r}")

    intents = doc.get("intents")
    if not isinstance(intents, list) or not intents:
        issues.append("catalog.intents: 缺少非空 intents 数组")
        return issues

    seen = set()
    counts = {"package": 0, "file": 0, "system": 0, "probe": 0}
    for i, intent in enumerate(intents):
        iid = intent.get("id", f"<#{i}>")
        if not isinstance(iid, str) or not ID_REGEX.match(iid):
            issues.append(f"intent[{iid}].id: id 必须是形如 package.uninstall 的小写点分名")
        if iid in seen:
            issues.append(f"intent[{iid}].id: 重复 id")
        seen.add(iid)

        family = iid.split(".", 1)[0] if isinstance(iid, str) else ""
        if family in counts:
            counts[family] += 1

        argv = intent.get("argv") or []
        if not argv:
            issues.append(f"intent[{iid}].argv: argv 不能为空")
        elif argv[0] not in EXECUTABLES:
            issues.append(f"intent[{iid}].argv[0]: 可执行名不在白名单：{argv[0]}")

        purge = intent.get("purgeArgv") or []
        if purge and purge[0] not in PURGE_EXECUTABLES:
            issues.append(f"intent[{iid}].purgeArgv[0]: purge 可执行名不在白名单：{purge[0]}")

        risk = intent.get("risk")
        if risk not in RISKS:
            issues.append(f"intent[{iid}].risk: 非法枚举 {risk!r}")
        priv = intent.get("requiredPrivilege", "SHIZUKU")
        if priv not in PRIVILEGES:
            issues.append(f"intent[{iid}].requiredPrivilege: 非法枚举 {priv!r}")

        reversible = intent.get("reversible", True)
        if not reversible:
            if not intent.get("preflight"):
                issues.append(f"intent[{iid}].preflight: reversible=false 必须有非空 preflight")
            if not intent.get("verify"):
                issues.append(f"intent[{iid}].verify: reversible=false 必须有非空 verify")
            if not (intent.get("rollbackHint") or "").strip():
                issues.append(f"intent[{iid}].rollbackHint: reversible=false 必须有非空 rollbackHint")

        params = intent.get("params") or []
        param_names = set()
        for p in params:
            name = p.get("name")
            if not name:
                issues.append(f"intent[{iid}].params: 缺少 name")
                continue
            param_names.add(name)
            kind = p.get("kind", "text")
            if kind not in KINDS:
                issues.append(f"intent[{iid}].params.{name}: 未知 kind {kind!r}")
            if kind == "enum" and not p.get("values"):
                issues.append(f"intent[{iid}].params.{name}: enum kind 必须提供非空 values")

        internal = set(intent.get("internal") or [])
        used = placeholders_of(argv) | placeholders_of(purge)
        for probe_argv in (intent.get("preflight") or []) + (intent.get("verify") or []):
            if not isinstance(probe_argv, list) or not probe_argv:
                issues.append(f"intent[{iid}].preflight/verify: 每条探针必须是非空 argv 数组")
                continue
            if probe_argv[0] not in EXECUTABLES:
                issues.append(
                    f"intent[{iid}].preflight/verify: 探针可执行名不在白名单：{probe_argv[0]}"
                )
            used |= placeholders_of(probe_argv)
        for ph in sorted(used - param_names - internal):
            issues.append(f"intent[{iid}].placeholders: 占位符 {{{ph}}} 未在 params 或 internal 中声明")

        scope = intent.get("pathScope")
        if scope is not None:
            for prefix in scope:
                if not isinstance(prefix, str) or not prefix.startswith("/"):
                    issues.append(f"intent[{iid}].pathScope: 必须是绝对路径前缀：{prefix!r}")

    for family, minimum in MIN_COUNTS.items():
        if counts[family] < minimum:
            issues.append(
                f"catalog.coverage: {family}.* 意图至少 {minimum} 条，实际 {counts[family]} 条"
            )
    return issues


def main():
    target = Path(sys.argv[1] if len(sys.argv) > 1 else "app/src/main/assets/device/intents.json")
    if not target.is_file():
        print(f"FAIL: 找不到意图清单：{target}")
        return 1
    issues = validate(target)
    if issues is None:
        return 1
    if issues:
        for line in issues:
            print(f"FAIL: {line}")
        print(f"共 {len(issues)} 个问题 —— 修复后再提交（详见 specs/S02 与 scripts/check_intents.py）")
        return 1
    print(f"OK: {target} 校验通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
