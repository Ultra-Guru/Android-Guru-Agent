package com.apex.agent.core.tools.fusion

/**
 * # FusionBundle 内置预置 —— 首发「REA · Reverse Engineer Anything」
 *
 * 上游（morluto/rea，MIT，npm rea-agents v6.5.0）的「MCP 服务器 + 工作流
 * 技能 + 工具」三位一体，经 FusionBundle 装载进 AGA：
 * - MCP 服务器 `rea`：207 个工具全目录（[com.apex.agent.core.tools.fusion.rea.ReaToolCatalog]）；
 * - 工作流技能 `ReaWorkflow`（apex-skill-v1，v5 PascalCase）：目标路由 + 证据纪律
 *   （由上游 SKILL.md 的 routing/evidence 章节重构筑）；
 * - agent 人设：激活即注入的逆向工程智能体工作流。
 *
 * 安装：app 层 FusionModule 首启 installBundled（幂等 + 版本守卫）。
 */
object FusionBundlePresets {

    /** REA 包 manifest（fusion-bundle-v1，JSON —— 注册表直接消费）。 */
    val REA_BUNDLE_JSON = """
{
  "schema": "fusion-bundle-v1",
  "id": "rea",
  "name": "REA · Reverse Engineer Anything",
  "version": "6.5.0",
  "description": "逆向工程复合能力包：207 个 MCP 工具（ASAR/JS 应用、原生二进制、APK、.NET、网站、固件）+ 目标路由工作流技能 + 证据纪律 agent。对齐上游 morluto/rea v6.5.0 的完整工具面，静态分析核心纯设备直跑。",
  "author": "Ultra-Guru (FusionBundle port) / upstream: morluto/rea (MIT)",
  "license": "MIT",
  "category": "REVERSE",
  "tags": ["reverse-engineering", "mcp", "rea", "binary", "apk", "asar"],
  "homepage": "https://rea.tools",
  "repository": "https://github.com/morluto/rea",
  "trustLevel": "verified",
  "bundled": true,
  "scope": "agent",
  "agent": {
    "persona": "逆向工程智能体（Reverse Engineer Anything）",
    "activationPrompt": "你当前以 REA 逆向工程体系运行：看到任何「它是怎么实现的」类问题，先路由目标类型再选工具 —— ASAR/JS/Electron 应用走 analyze_javascript_application；ZIP/APK/IPA/归档走 open_binary 后 inspect_artifact；Android APK 声明与类清单走 inspect_android_package 与 search_android_classes；ELF/PE/Mach-O 原生布局走 inspect_binary_layout；网站静态面走 inspect_web_page（URL 入参）。每个结论必须引用工具返回的 findings/evidence 字段，limitations 与 unknowns 如实转述，绝不编造分析结果。引擎缺失（objdump/rizin/jadx 等 BYO 工具）时工具会返回降级证据与安装指引 —— 如实告知用户并继续做设备端可完成的部分。会话内用 binary_session 回看保留证据，list_unknowns 登记未决问题。",
    "routingHints": "ASAR 或解包 JS/Electron 目录 → analyze_javascript_application；ZIP/APK/IPA/MSIX 归档 → open_binary + inspect_artifact；APK 声明/权限/组件 → inspect_android_package；APK 类名检索 → search_android_classes；Flutter 构建 → identify_flutter_build；ELF/PE/Mach-O → inspect_binary_layout / inspect_macho / list_architectures；SQLite → inspect_sqlite_database；plist → inspect_plist；网站 → inspect_web_page（URL）；Swift 符号 → demangle_swift；设备本地检查（包/进程/窗口/logcat）→ *_adb_ 族（设备直跑，无需 PC）"
  },
  "skills": [
    {
      "skillId": "ReaWorkflow",
      "role": "目标路由与证据纪律工作流",
      "manifest": {
        "schema": "apex-skill-v1",
        "id": "ReaWorkflow",
        "name": "REA 逆向工程工作流",
        "version": "6.5.0",
        "description": "逆向调查的目标路由决策与证据纪律：先判目标类型（ASAR/JS、归档、APK、原生二进制、网站、固件），再选 REA 工具；结论必须携带 findings/evidence 并如实转述 limitations。",
        "author": "Ultra-Guru (port) / upstream: morluto/rea (MIT)",
        "license": "MIT",
        "category": "REVERSE",
        "tags": ["reverse-engineering", "rea", "evidence", "routing"],
        "homepage": "https://rea.tools",
        "trustLevel": "verified",
        "bundled": true,
        "scope": "agent",
        "promptInjection": "逆向工程纪律（REA 工作流）：1) 路由优先 —— 依据目标形态选首工具：ASAR/JS/Electron 目录 → analyze_javascript_application；归档（ZIP/APK/IPA/MSIX/HAP）→ open_binary 后 inspect_artifact；APK 深查 → inspect_android_package + search_android_classes；原生二进制 → inspect_binary_layout；网站 → inspect_web_page。2) 证据纪律 —— 每个结论引用工具 findings/evidence；limitations 与 unknowns 如实转述；不确定就说不确定。3) 降级诚实 —— BYO 引擎（objdump/rizin/jadx/apktool/binwalk）缺失时，降级证据包含安装指引；先完成设备端可做的静态分析。4) 复用证据 —— binary_session 查会话保留证据；对比类工具吃先前 Evidence 记录。5) 不越权 —— 只分析用户给的目标；不执行目标代码。"
      }
    }
  ],
  "mcpServers": [
    {
      "serverId": "rea",
      "autoConnect": true,
      "role": "207 工具全目录（ON_DEVICE 72 / DEVICE_SHELL 25 / SANDBOX_BRIDGE 34 / WEB_FETCH 15 / PROVIDER_GATED 61）"
    }
  ],
  "toolActivationHints": [
    "McpReaOpenBinary",
    "McpReaBinarySession",
    "McpReaInspectArtifact",
    "McpReaInspectBinaryLayout",
    "McpReaInspectAndroidPackage",
    "McpReaAnalyzeJavascriptApplication",
    "McpReaSearchAndroidClasses",
    "McpReaIdentifyFlutterBuild"
  ],
  "runtime": {
    "engines": [
      {"name": "objdump", "purpose": "GNU objdump 反汇编桥（inspect_with_objdump）", "optional": true, "installHint": "PRoot Ubuntu 终端: apt install binutils"},
      {"name": "rizin", "purpose": "Rizin/Cutter 分析与调试桥（execute_rizin_command 等 5 工具）", "optional": true, "installHint": "PRoot Ubuntu 终端安装 rizin"},
      {"name": "gdb", "purpose": "GDB MI 调试桥（debug_with_gdb 族）", "optional": true, "installHint": "PRoot Ubuntu 终端: apt install gdb"},
      {"name": "frida", "purpose": "Frida 动态插桩桥（instrument_with_frida 族）", "optional": true, "installHint": "PRoot Ubuntu 终端: pip install frida-tools"},
      {"name": "jadx", "purpose": "APK 反编译（inspect_android_method / trace_android_references）", "optional": true, "installHint": "PRoot Ubuntu 终端安装 jadx + JRE 17"},
      {"name": "apktool", "purpose": "APK 资源解码（decode_android_resources）", "optional": true, "installHint": "PRoot Ubuntu 终端安装 apktool"},
      {"name": "tshark", "purpose": "抓包检查（inspect_packet_capture 族）", "optional": true, "installHint": "PRoot Ubuntu 终端: apt install tshark"},
      {"name": "binwalk", "purpose": "固件签名扫描（inspect_firmware_regions / extract_firmware）", "optional": true, "installHint": "PRoot Ubuntu 终端: pip install binwalk"},
      {"name": "wabt", "purpose": "WASM 验证（inspect_wasm_artifact）", "optional": true, "installHint": "PRoot Ubuntu 终端安装 wabt"}
    ],
    "notes": "静态核心（ASAR/ELF/PE/Mach-O/APK/AXML/dex/SQLite/plist/证据系统/设备直连 adb 族）零引擎可用；上表引擎解锁对应深分析工具族（BYO 模型，与上游一致）。"
  },
  "changelog": [
    {"version": "6.5.0", "notes": "FusionBundle 首发装载：207 工具目录逐名对齐上游 rea-agents v6.5.0；静态核心纯 Kotlin 设备实现；adb 族原生化为设备本地执行。"}
  ]
}
""".trim()

    /** 全部内置预置（当前首发一台：REA）。 */
    fun all(): List<Pair<FusionBundleManifest, String>> {
        val parsed = FusionBundleManifest.parse(REA_BUNDLE_JSON)
            ?: error("内置 REA FusionBundle manifest 非法（编码期错误，需修正）")
        return listOf(parsed to REA_BUNDLE_JSON)
    }
}
