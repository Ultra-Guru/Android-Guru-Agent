pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // NIA（android/nowinandroid）同款纪律：google() 只解析 Google 系 group，
        // 防止其它坐标误走 google() 拉到非预期构件，同时减少无谓元数据请求。
        // 本仓全部依赖核实过归属：androidx/com.android/com.google 走 google()，
        // 其余全在 mavenCentral，EasyFloat 独走 JitPack 白名单。
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        // 仅允许 EasyFloat 所在 group 走 JitPack，避免其它依赖误查 JitPack（按需构建不稳定源）
        exclusiveContent {
            forRepository {
                maven("https://jitpack.io")
            }
            filter {
                includeGroup("com.github.princekin-f")
            }
        }
    }
}

// NIA 同款前置检查：JDK 不满足直接给人话报错，而不是等 AGP 在配置中期
// 抛晦涩异常（本仓 JDK 17 起步，与 CI setup-java 对齐）。
check(JavaVersion.current().isCompatibleWith(JavaVersion.VERSION_17)) {
    "Android Guru Agent requires JDK 17+ (current: ${JavaVersion.current()}). " +
        "Set JAVA_HOME to a JDK 17+ installation. CI reference: .github/workflows (setup-java temurin 17)."
}

rootProject.name = "apex-agent"

// ── 浏览器库 composite build（拆库 E1 迁移期）─────────────────────────────
// apex-browser-kit 从本仓库抽出为独立库（com.apex.browser:{core,engine,chrome}）。
// 兄弟目录存在时自动接入 composite build：本地开发「改一处两边生效」；
// CI 由 workflow 把库检出为兄弟目录（ci.yml / apk.yml / release.yml 的
// sibling checkout 步骤）。目录不存在时跳过 —— 走远端 Maven 坐标（E3 稳定期
// GitHub Packages 发布后切换）。子目录兜底位供 consumer-check 等双仓布局复用。
val browserKitSibling = file("../apex-browser-kit")
val browserKitNested = file("apex-browser-kit")
when {
    browserKitSibling.isDirectory -> includeBuild(browserKitSibling)
    browserKitNested.isDirectory -> includeBuild(browserKitNested)
}

// ── 标准任务循环库 composite build（同款拆库模式）─────────────────────────
// apex-standard-kit 从本仓库抽出为独立库（com.apex.standardkit:standard-loop ——
// Coding「标准思考逻辑线」的循环内核：画像/权限门/压缩/子代理/并行工具/
// 续跑语义/MCP 指引）。兄弟目录存在时自动接入（本地开发改一处两边生效）；
// CI 由 workflow 按 gradle/standard-kit.lock 锁定版本检出（ci.yml / apk.yml /
// release.yml 的 pinned checkout 步骤）；目录不存在时跳过 —— 走远端
// Maven 坐标。子目录兜底位供库仓 consumer-check 双仓布局复用。
val standardKitSibling = file("../apex-standard-kit")
val standardKitNested = file("apex-standard-kit")
when {
    standardKitSibling.isDirectory -> includeBuild(standardKitSibling)
    standardKitNested.isDirectory -> includeBuild(standardKitNested)
}

// ── 织造循环库 composite build（同款拆库模式）───────────────────────────
// apex-loom-kit 从本仓库抽出为独立库（com.apex.loomkit:loom-weave ——
// Coding「织造思考逻辑线」的循环内核：in-band 标记协议/梭回合机/权限
// 三态门/子线程群/织造包/digest 压缩）。接线方式与 apex-standard-kit
// 完全同构：兄弟目录本地开发、嵌套目录 CI consumer-check 兜底、目录
// 不存在时走远端 Maven 坐标。库仓库：Ultra-Guru/apex-loom-kit（含独立
// CI + 宿主 consumer-check）。
val loomKitSibling = file("../apex-loom-kit")
val loomKitNested = file("apex-loom-kit")
when {
    loomKitSibling.isDirectory -> includeBuild(loomKitSibling)
    loomKitNested.isDirectory -> includeBuild(loomKitNested)
}

// ── 工作区浏览器库 composite build（拆库 E1 迁移期）───────────────────────
// apex-workspace-kit（com.apex.workspace:{core,explorer}）：Coding 模式
// 全屏工作区浏览器（文件树 / 多格式预览 / Git 面板 / 变更和产物 / 实时跟随）。
// 接线方式与 apex-browser-kit 完全同构：兄弟目录本地开发、嵌套目录 CI
// consumer-check 兜底、目录不存在时走远端 Maven 坐标。
// 库仓库：Ultra-Guru/apex-workspace-kit（含独立 CI + 宿主 consumer-check）。
val workspaceKitSibling = file("../apex-workspace-kit")
val workspaceKitNested = file("apex-workspace-kit")
when {
    workspaceKitSibling.isDirectory -> includeBuild(workspaceKitSibling)
    workspaceKitNested.isDirectory -> includeBuild(workspaceKitNested)
}

// 主APK
include(":app")

// Liquid Glass 底层引擎 —— vendored kyant0/AndroidLiquidGlass（上游 tag 1.0.0
// 血统 + 本仓 diag-20261011 热修 + PR #364 彩色 DefaultHighlight，Apache-2.0）。
// 工具链（Kotlin 2.3.21 / BOM 2026.02.00）已具备消费 Maven 线
// io.github.kyant0:backdrop 的能力，但上游 1.0.6 二进制不含 diag-20261011
// 两项 internal 级热修 —— 去 vendored 化在 PR #362 合并时显式回退，
// 详见 vendor/backdrop/README.md。
include(":vendor:backdrop")

// 核心引擎（纯Kotlin JVM，零Android依赖）
include(":core:agent-engine")
include(":core:llm-adapter")
include(":core:tool-registry")
include(":core:logging")

// Coding 模式（与 Agent 模式同级别）：编码工具集 + 编码引擎
include(":core:code-tools")
include(":core:code-engine")

// 标准任务循环文本加速核（C++17 JNI：token 估算 / 行级 diff / 模糊定位；
// 算法层 host 可测，JNI 桥 Android 平台——与 :terminal-native 同款接线）
include(":core:code-native")

// Android平台层
include(":platform:privilege")
include(":platform:persistence")
include(":platform:terminal")
include(":platform:cs-mem")
include(":platform:code-workspace")
// 浏览器自动化大功能：browser_* 工具协议 + apex-browser-kit 库 DI 接线
include(":platform:browser-agent")

// 逆向 MCP Host（#173）：手机作为 MCP Server，PC 端 AI 经 streamable HTTP 控制手机
include(":platform:mcp-host")

// Terminal Runtime 2.0 — vendored VT100/ANSI emulator (ATR Phase 2)
include(":terminal-emulator")

// Terminal native hot path — vendored apex-vt-native C++17 VT engine (JNI)
// 上游：Ultra-Guru/apex-vt-native（129 项奇偶校验测试 + NDK CI）
include(":terminal-native")

// Terminal view — T88 Termux 级自定义 View 渲染层（Canvas 网格 / 滚动 / 选择 / IME）
// 依赖 :terminal-emulator 的纯数据模型（RenderCell/TerminalRenderSnapshot），不依赖 Compose
include(":terminal-view")

// 插件SDK
include(":plugin-sdk:plugin-api")
include(":plugin-sdk:plugin-host")

// 插件APK
include(":plugins:plugin-workflow")
