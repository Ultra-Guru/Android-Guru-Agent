import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    // #266：Compose 编译器插件收编 catalog（原硬编码 version "2.0.21"，
    // 升 Kotlin 时易与 catalog 的 kotlin 版本脱钩）
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

// ═══ 发布签名凭据解析（S-01：脚本级单一解析点，android 块与门禁任务共用）═══
// 优先级：Gradle 属性 > 环境变量 > 无默认值（fail-closed，见 checkReleaseSigningCredentials）。
val releaseKeystorePath: String = (project.findProperty("apexKeystoreFile") as String?)
    ?: System.getenv("APEX_KEYSTORE_FILE")
    ?: rootProject.file("keystore/apex-release.jks").absolutePath
val releaseStorePassword: String? = (project.findProperty("apexKeystorePassword") as String?)
    ?: System.getenv("APEX_KEYSTORE_PASSWORD")
val releaseKeyAlias: String? = (project.findProperty("apexKeyAlias") as String?)
    ?: System.getenv("APEX_KEY_ALIAS")
val releaseKeyPassword: String? = (project.findProperty("apexKeyPassword") as String?)
    ?: System.getenv("APEX_KEY_PASSWORD")
val releaseSigningReady: Boolean =
    file(releaseKeystorePath).isFile &&
        !releaseStorePassword.isNullOrBlank() &&
        !releaseKeyAlias.isNullOrBlank() &&
        !releaseKeyPassword.isNullOrBlank()

android {
    namespace = "com.apex.agent"
    compileSdk = 36

    // ═══ 发布签名（S-01：凭据 fail-closed，keystore 不入库）══════════════════
    //
    // 历史：v1.4.7 及之前 release 用 signingConfigs.getByName("debug") ——
    // debug.keystore 是机器本地的，CI runner 与开发者本机必然不同 →
    // 增量更新（VCDIFF 合成）的 APK 字节级保留源 APK 签名块，但远端新版
    // 是 CI debug key 签的 → 覆盖安装必报 INSTALL_FAILED_UPDATE_INCOMPATIBLE。
    // v1.4.8 改为「入库 keystore + 默认口令」—— 指纹恒定了，但私钥与口令
    // 随公开仓库分发，等同于没有签名防线（S-01 修复对象）。
    //
    // 现状（S-01）：keystore 移出版本控制（keystore/.gitignore 拦再入库；
    // scripts/check_secrets.py 按「文件名后缀 + JKS magic 头」双通道拦截）；
    // 凭据一律经 env / Gradle 属性注入，无任何默认值 —— 缺失即构建失败
    // （checkReleaseSigningCredentials 任务，挂在 preReleaseBuild）。
    //
    // 注入通道（优先级：Gradle 属性 > env > 失败）：
    //   -PapexKeystoreFile / APEX_KEYSTORE_FILE（keystore 文件路径）
    //   -PapexKeystorePassword / APEX_KEYSTORE_PASSWORD（store 口令）
    //   -PapexKeyAlias / APEX_KEY_ALIAS
    //   -PapexKeyPassword / APEX_KEY_PASSWORD
    // CI 注入点：release.yml / apk.yml 的「Restore release keystore from
    // secrets」步骤（APEX_RELEASE_KEYSTORE_BASE64 等四个 repo secrets）。
    // 本地生成新 keystore：scripts/generate_release_keystore.sh（口令经 env 注入）。
    signingConfigs {
        create("release") {
            storeFile = file(releaseKeystorePath)
            storePassword = releaseStorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
            // 启用 v1/v2/v3 签名方案（v2 是 Android 7+ 覆盖安装首选，v1 兼容旧机）
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    defaultConfig {
        applicationId = "com.apex.agent"
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // ── 真机红线修复：targetSdk 必须 ≤ 28 ─────────────────────────────
        // targetSdk ≥ 29 的 app 运行在 SELinux untrusted_app 域，Android 10 起
        // 该域禁止 execute app_data_file（W^X 强制）：
        //   - Ubuntu rootfs 解压在 filesDir（TerminalModule: filesDir/rootfs/ubuntu）
        //   - PRoot guest 的 /bin/bash、/usr/bin/apt-get 等 execve → EACCES
        //   - guest 动态库 mmap(PROT_EXEC) 同样被拒
        // → Ubuntu 安装必然失败：下载/解压全成功，死在 RootfsHealthCheck 的
        //   canExecute()（ROOTFS_INVALID "not executable"）或 bootstrap 的
        //   apt-get（exec EACCES）。这是 T72-T82 真机链路一直 NOT VERIFIED
        //   背后的架构级问题，也是"ubuntu 安装不了"的直接根因。
        // Termux 至今钉 targetSdk 28 正是同因。本项目经 GitHub Releases
        // 侧载分发，不受 Play targetSdk 政策约束；若未来上架 Play Store，
        // 需改用 Shizuku/ADB shell 域执行 proot 或 rootfs-as-jniLibs 方案。
        // 参考：developer.android.com/about/versions/10/privacy/changes
        //       （"Execute permission for app home directory" 一节）
        targetSdk = 28
        // v1.4.2：双仓库发布架构升级 —— PR 合并即自动发版（release.yml push main 触发）。
        // CI 通过 -PapexVersionName / -PapexVersionCode 注入最终版本（版本号冲突时
        // 自动追加构建序号，如 1.4.2.1，并自动递增 versionCode）；本地构建走源码值。
        // v1.4.8：签名漂移根因修复 —— 发布签名固定为单一 keystore，CI 与本地
        // 构建签名指纹恒定 → 增量合成 APK 与已装 APK 签名一致，覆盖安装无
        // 冲突；本地 APK 常驻关于页（不再随 UpToDate/版本切换自动清理）；
        // installApk 复制内部 cache 再授权，修复 MIUI/EMUI「未找到 SD 储存卡」。
        // （v1.4.8 当时以「入库 keystore + 默认口令」实现；S-01 起凭据移出
        // 版本控制，语义不变：指纹恒定，来源改为 env/CI secrets 注入。）
        // v1.4.7：热更新体系（data-only 版本零安装热载 / 签名预检 / CI 热更包 /
        // 发布仓库应急热修通道，见 docs/hot-update-pipeline.md）。
        // v1.4.6：聊天输入 v5 —— 技能 chip 内联输入框（多选/去重/可删）/ 流式玻璃
        // 气泡（Agent+Coding 双模式）/ 顶栏与工作区栏按钮防挤压 / 函数调用文案纠偏
        // + #298 流式落盘原子性与滚动定位修复。
        // v1.4.5：更新体系 v2（多基底补丁单跳直达 / 应用级断点续传 / 非强制浮窗）
        // + 玻璃白天模式根因修复（RenderNode 级圆角裁剪）。
        // v1.4.8 起显式维护版本号（v1.4.7.25 断链教训：versionName 未 bump，
        // 流水线自动追加序号，版本号与提交内容标注脱节）。versionCode 取 73：
        // 71 是 v1.4.7.24，72 已被孤儿版 v1.4.7.25 烧掉（其 version.json 提交被
        // 镜像故障拖跳过，客户端从未见过该版），73 起单调无碰撞。
        // v1.4.9：网页自动化插件分发链路 + browser-kit 自动同步 —— 插件 APK 与
        // 宿主同 keystore 签名随 Release 分发（修复「市场 → 插件」页永远空的问题）；
        // gradle/browser-kit.lock 锁定库版本，sync-browser-kit 工作流随库仓库
        // 新绿提交自动 bump 并触发发布（主仓随库仓更新而更新，全自动）。
        // v1.5（20261031）：时间轴时序与滚动体验双修 —— ①收尾回答落位修复：
        // 工具信号收口流式正文段，最终回答另起新条目追加到时间轴末尾（旧：
        // 原位并进工具前旧气泡，收尾回答恒显示在工具记录上方）；②流式滚动
        // 锚重构：手势锁存 + canScrollForward 精确贴底 + 拖拽期停跟 ——
        // 流式输出可自由上滑回看，点「回到底部」FAB 或滑回底部恢复跟随
        // （旧：强制吸底滑不开）。v1.4.9 档期 CI 自动追加到 .102，1.5 起新版号
        // 档期归零；versionCode 取 179：上一版 v1.4.9.102 为 178，单调无碰撞。
        versionCode = (project.findProperty("apexVersionCode") as String?)?.toInt() ?: 179
        versionName = (project.findProperty("apexVersionName") as String?) ?: "1.5"

        ndk {
            // T83: 发布 arm64 纯净包（-PapexAbi=arm64-v8a）—— 内置 rootfs 伪 .so
            // 按 ABI 自动过滤，APK 从 ~117MB 降到 ~50MB。缺省（无属性）= 全 ABI
            // universal（含 armeabi-v7a / x86_64，兼容旧设备与模拟器）。
            // 注意：android.injected.abi 会被各模块显式 abiFilters 压过，不可用于此。
            if (project.hasProperty("apexAbi")) {
                abiFilters += project.property("apexAbi").toString().split(",").map { it.trim() }
            }
        }
    }

    buildFeatures {
        compose = true
        // 关于页：版本号（versionName/Code）经 BuildConfig 暴露给设置页
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // ExpiredTargetSdkVersion 的豁免理由（targetSdk=28 是有意为之，见上方红线注释）：
        // targetSdk ≥ 29 的 SELinux untrusted_app 域禁止 execute app_data_file（W^X），
        // PRoot guest（Ubuntu rootfs）将完全不可执行 —— Termux 钉 28 同因。本项目经
        // GitHub Releases 侧载分发，不受 Play targetSdk 政策约束；lintVitalRelease
        // 对 release 构建是 fatal，此检查必须显式关闭（否则 tag 构建永远失败）。
        disable += "ExpiredTargetSdkVersion"
    }

    buildTypes {
        // 发布 APK 以**注入式 release keystore**签名（S-01：keystore 不入库，
        // 凭据经 env / Gradle 属性 / CI secrets 注入，见 signingConfigs 上方
        // 说明）：签名指纹恒定 → 增量合成 APK 与已装 APK 签名一致，覆盖安装
        // 无冲突。凭据缺失时 preReleaseBuild 前置的 checkReleaseSigningCredentials
        // 任务以明确指引失败（fail-closed，不回退默认值）。
        //
        // 【不使用代码混淆】本 APK 明确不做 R8/ProGuard 混淆与资源缩减：
        //  - 持久化大量依赖 kotlinx.serialization 的字段名（ModelProfile / ProviderConfig /
        //    McpServerConfig / Skill manifest），混淆会静默写坏历史数据；
        //  - AIDL 插件跨进程桥、JNI/native 层依赖符号可见性，混淆后难定位问题；
        //  - isShrinkResources=false 保证 assets（如 MCP bridge 脚本）不被误剔除。
        // 若未来启用，必须先补 keep 规则并完成数据迁移验证。
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("release")
        }
        // debug 构建优先也用 release 签名 —— 让本地构建的 APK 可覆盖安装 CI
        // 发布版（签名一致 → 不再 INSTALL_FAILED_UPDATE_INCOMPATIBLE）。
        // S-01：keystore 移出版本控制后，本地/CI 无凭据时**回退 debug 签名**
        // （响亮警告，不阻断 —— PR CI 的 debug APK 工件、新克隆环境、
        // instrument 测试都走此路径）；凭据齐备时维持旧行为（release 签名）。
        // 如需强制纯调试签名可加 -PapexDebugSign=true（开发调试隔离场景）。
        debug {
            // 仅当显式传 -PapexDebugSign=true 时回退到 debug 签名（开发调试隔离场景）
            if ((project.findProperty("apexDebugSign") as String?) != "true") {
                if (releaseSigningReady) {
                    signingConfig = signingConfigs.getByName("release")
                } else {
                    logger.warn(
                        "[signing] ⚠️ release 凭据/keystore 不可用（S-01：已移出版本控制）" +
                            " —— 本次 debug 构建使用 debug 签名。" +
                            "需要与发布版同签名：export APEX_KEYSTORE_FILE/PASSWORD " +
                            "APEX_KEY_ALIAS/APEX_KEY_PASSWORD 后重试，" +
                            "或 -PapexKeystoreFile 等属性注入。"
                    )
                }
            }
        }
    }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
        // P71: PRoot 以可执行二进制（libproot.so 等）随 APK 分发。AGP 默认
        //不解压 .so 到磁盘（extractNativeLibs=false 语义），nativeLibraryDir 下将
        //不存在 proot 文件 —— 必须 legacy 打包（解压到 nativeLibraryDir）才能 exec。
        // Termux/UserLAnd 的标准做法。
        jniLibs {
            useLegacyPackaging = true
            // T83: 内置 Ubuntu rootfs 以伪 .so（tarball）随 jniLibs 分发 —— 非 ELF
            // 对象，release 的 stripReleaseNativeLibs（llvm-strip）会拒绝并使构建失败；
            // keepDebugSymbols 即"跳过 strip"清单，档案逐字节原样进 APK
            //（完整性由三层 SHA-256 链保证，见 BundledRootfsSource.kt）。
            keepDebugSymbols += "**/libubuntu-rootfs.so"
        }
    }
}

// KGP 2.2：kotlinOptions DSL 已 deprecation（KGP 3.0 移除）—— 迁移到
// 项目级 compilerOptions DSL（语义等价：等价于对全部 Kotlin 编译任务生效）。
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // 内部模块
    implementation(project(":core:agent-engine"))
    implementation(project(":core:llm-adapter"))
    implementation(project(":core:tool-registry"))
    implementation(project(":core:logging"))
    implementation(project(":platform:privilege"))
    implementation(project(":platform:persistence"))
    implementation(project(":platform:terminal"))
    // P83: Terminal UI 直接消费 TerminalRenderSnapshot/RenderCell（styled grid 渲染）。
    // platform:terminal 对 :terminal-emulator 是 implementation（不传递），app 需显式声明。
    implementation(project(":terminal-emulator"))
    // T88 (2-a): Termux 级自定义 View 渲染层（Canvas 直绘 / 滚动 / 选区 / IME）。
    // terminal-view 对 :terminal-emulator 同为 implementation（不传递），app 的
    // 显式 terminal-emulator 依赖在上方已具备 —— 两边模型单一事实源。
    implementation(project(":terminal-view"))
    // #F-⑰：native VT 引擎黑匣子（VtFeedTrail）—— TerminalModule 在 runtime
    // 构造点 install。platform:terminal 对 :terminal-native 是 implementation
    // （不传递），app 需显式声明（与上方 terminal-emulator 同款先例）。
    implementation(project(":terminal-native"))
    // 认知记忆（apex-kit-memory 宿主层）：观察者 / MCP 图谱 transport / 旧存储
    // 迁移 / 维护调度 / 记忆页 UI 的平台端口接线
    implementation(project(":platform:memory-host"))
    // Coding 模式（与 Agent 模式同级别）：编码工具集 / 编码引擎 / 工作区管理
    implementation(project(":core:code-tools"))
    implementation(project(":core:code-engine"))
    // 标准任务循环文本加速核（JNI；DI 把 NativeTextKernel 注入 StandardKitAdapter → standard-loop 库）
    implementation(project(":core:code-native"))
    implementation(project(":platform:code-workspace"))
    // 逆向 MCP Host（#173）：手机作为 MCP Server（streamable HTTP，纯 JVM）
    implementation(project(":platform:mcp-host"))
    implementation(project(":plugin-sdk:plugin-host"))

    // ── 浏览器自动化大功能（v1.5.0 内置化）───────────────────────────────
    // :platform:browser-agent 以 api 传递 apex-kit-browser 三层坐标
    // （core/engine/chrome），app 仍直接消费 BrowserEngine / BrowserTracer /
    // BrowserOverlay / ApexChromeWiring 类型（ApexCoreService / CyberNeonBallManager /
    // BrowserScreen）；BrowserAgentTools 与库 DI 接线（BrowserKitModule）在该模块内。
    implementation(project(":platform:browser-agent"))
    // apex-kit-workspace（拆库 E1 迁移期）：Coding 模式工作区浏览器
    //（文件树/多格式预览/Git/变更和产物/实时跟随）。composite build 时
    // 兄弟目录 includeBuild 自动替换为本地构建；适配层（GitCommandRunner /
    // WorkspaceRootProvider / AppLogger 薄包装）住 ui/screen/code/WorkspaceKitHolder.kt。
    implementation(libs.workspace.core)
    implementation(libs.workspace.explorer)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.compose.material.icons)
    // Lucide 图标集（composablehorizons/compose-icons, MIT）：斜杠菜单分类图标预留。
    // 2.2.1（Kotlin 2.2.21 + foundation 1.9.3 构建）—— 与本仓工具链配套；
    // 当前代码零处使用 IconsLucide（预留位，见 libs.versions.toml 注释）。
    implementation(libs.lucide.icons)
    debugImplementation(libs.compose.ui.tooling)

    // AndroidX
    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.service)
    implementation(libs.navigation.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    // 其他
    implementation(libs.serialization.json)
    implementation(libs.coroutines.android)
    implementation(libs.okhttp)
    // 增量更新：VCDIFF 补丁的 LZMA(.xz) 二级压缩段解码（VcdiffDecoder）
    implementation(libs.xz)
    implementation(libs.work.runtime)

    // Coil (image loading)
    implementation(libs.coil.compose)
    // 模型品牌图标：SimpleIcons CDN 的 SVG 官方标解码（#174）
    implementation(libs.coil.svg)

    // Security (encrypted SharedPreferences for GitHub token)
    implementation(libs.security.crypto)

    // Shizuku (system privilege access)
    // 混沌审查修复（CR #C3）：硬编码 13.1.0 与版本目录（platform:privilege 引用的
    // libs.shizuku.* = 13.1.5）漂移 —— Gradle 冲突解析静默取高版本，硬编码 pin 实际无效；
    // 一旦移除 privilege 模块依赖会无声降级到 13.1.0（binder 协议不匹配的运行时风险）。
    // 统一走 catalog，保证全仓库单一版本源。
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    // 赛博霓虹悬浮球：全局低侵入 WindowManager 管理（JitPack，已做仓库过滤+版本锁定）
    implementation(libs.easyfloat)
    // Liquid Glass UI System 底层引擎 —— vendored kyant0/AndroidLiquidGlass（上游
    // tag 1.0.0 血统 + diag-20261011 热修 + PR #364 彩色 DefaultHighlight，Apache-2.0）。
    // 工具链已可消费 Maven 线（1.0.6 与本仓构建矩阵精确配套），但上游 1.0.6 二进制
    // 不含 isAttached 守卫与 ShapeProvider 值语义两项热修 —— 保留 vendored，
    // 消费层：ui/glass 包（业务层经 Glass 组件 API 访问，vendor/backdrop/README.md）。
    implementation(project(":vendor:backdrop"))
    // Vico 开源图表库（稳定线 1.13.1）—— 任务历史页「近 7 日任务量」柱状图 +
    // 记忆页类型分布；只引 compose 核心（主题色手动映射，不引 m2/m3 主题模块）
    implementation(libs.vico.compose)
    // 物理弹力手势（SpringAnimation 按压挤压形变 / 吸附）
    implementation(libs.dynamicanimation)

    // Unit testing (pure-JVM src/test)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

// 依赖锁定：固定已解析版本，避免 JitPack/EasyFloat 在不同时刻解析到不同工件，提升 CI 复现性
dependencyLocking {
    lockAllConfigurations()
}

// ═══ P0 修复（构建地雷）：内置 Ubuntu rootfs 档案守卫 ═══
//
// 背景（用户反馈"Ubuntu 根本用不了"的构建期根因）：libubuntu-rootfs.so 是
// ~300MB/ABI 的大档案，.gitignore 排除不入库，只能靠 scripts/fetch_rootfs.sh
// 构建期暂存。此前**没有任何 Gradle task 校验其存在** —— 若暂存缺失（新克隆、
// CI 忘跑 fetch 步骤、本地缓存被清），assembleRelease 照样成功，产出的 APK
// 安装后 BundledRootfsSource.resolve() 必然报
// "ARCHIVE_INVALID: Bundled rootfs archive missing"（recoverable=false）→
// Ubuntu 安装 100% 失败，且问题只暴露在真机运行期，构建期零提示。
//
// 守卫语义（与 scripts/fetch_rootfs.sh 的"宁可构建失败，不可带病打包"一致）：
//  - release 构建：所需 ABI 的档案缺失或 sha256 与 rootfs-bundle.sha256 不符
//    → 构建失败，错误信息给出修复命令；
//  - debug 构建：仅响亮警告（开发迭代速度优先；CI PR 路径只暂存 arm64）；
//  - 所需 ABI = -PapexAbi（逗号分隔）收窄，缺省 = 清单全集（universal）；
//  - 逃生门：-PapexSkipRootfsCheck=true（诊断/实验构建）。
val rootfsManifest = rootProject.layout.projectDirectory.file(
    "platform/terminal/rootfs-bundle.sha256"
).asFile
val rootfsJniLibsBase = rootProject.layout.projectDirectory.dir(
    "platform/terminal/src/main/jniLibs"
)

/** 解析清单：abi → sha256（跳过注释与空行）。 */
fun parseRootfsManifest(): Map<String, String> {
    if (!rootfsManifest.isFile) return emptyMap()
    return rootfsManifest.readLines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapNotNull { line ->
            val parts = line.split(Regex("\\s+"), limit = 2)
            val sha = parts.getOrNull(0) ?: return@mapNotNull null
            val path = parts.getOrNull(1) ?: return@mapNotNull null
            val abi = path.split("/").firstOrNull { it.startsWith("arm") || it.startsWith("x86") }
                ?: return@mapNotNull null
            abi to sha
        }
        .toMap()
}

/** 本次构建需要的 ABI 集（apexAbi 收窄，缺省全量）。 */
fun requiredRootfsAbis(): Set<String> {
    val all = parseRootfsManifest().keys
    val narrowed = (project.findProperty("apexAbi") as String?)
        ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
        ?: return all
    return all.intersect(narrowed.toSet()).ifEmpty { all }
}

tasks.register("checkBundledRootfs") {
    group = "verification"
    description = "P0 guard: bundled Ubuntu rootfs archives must exist and match rootfs-bundle.sha256 (release-critical)."
    doLast {
        if ((project.findProperty("apexSkipRootfsCheck") as String?) == "true") {
            logger.lifecycle("[rootfs-guard] skipped via -PapexSkipRootfsCheck=true")
            return@doLast
        }
        val manifest = parseRootfsManifest()
        if (manifest.isEmpty()) {
            throw GradleException(
                "[rootfs-guard] rootfs-bundle.sha256 缺失或不可解析：${rootfsManifest.absolutePath} —— " +
                    "清单是内置 rootfs 的指纹真值表，缺失意味着仓库损坏，请检查 git 状态。"
            )
        }
        val required = requiredRootfsAbis()
        val problems = mutableListOf<String>()
        for (abi in required.sorted()) {
            val expected = manifest.getValue(abi)
            val f = rootfsJniLibsBase.file("$abi/libubuntu-rootfs.so").asFile
            if (!f.isFile) {
                problems += "$abi: 档案缺失（${f.absolutePath}）"
                continue
            }
            val actual = MessageDigest.getInstance("SHA-256").let { md ->
                f.inputStream().use { input ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (n > 0) md.update(buf, 0, n)
                    }
                }
                md.digest().joinToString("") { "%02x".format(it) }
            }
            if (!actual.equals(expected, ignoreCase = true)) {
                problems += "$abi: 指纹不符（expected=$expected actual=$actual）—— 档案损坏或版本漂移"
            }
        }
        if (problems.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("[rootfs-guard] 内置 Ubuntu rootfs 守卫失败 —— 缺失/指纹不符的 ABI：")
                    problems.forEach { appendLine("  - $it") }
                    appendLine("修复：scripts/fetch_rootfs.sh            # 全部 ABI（universal 发布）")
                    appendLine("      scripts/fetch_rootfs.sh --arch arm64-v8a   # 只拉 arm64（本地快速验证）")
                    appendLine("绝不能跳过守卫直接打包：产物安装后 Ubuntu 必然不可用（运行期才暴露）。")
                }
            )
        }
        logger.lifecycle("[rootfs-guard] OK — ${required.sorted()} 的 libubuntu-rootfs.so 就位且指纹一致")
    }
}

tasks.register("warnBundledRootfs") {
    group = "verification"
    description = "Debug-friendly existence check: loudly warn when bundled rootfs archives are missing (no hash, no fail)."
    doLast {
        val manifest = parseRootfsManifest()
        if (manifest.isEmpty()) return@doLast
        val missing = requiredRootfsAbis().filter { abi ->
            !rootfsJniLibsBase.file("$abi/libubuntu-rootfs.so").asFile.isFile
        }
        if (missing.isNotEmpty()) {
            logger.warn(
                "[rootfs-guard] ⚠️ 内置 Ubuntu rootfs 档案缺失（$missing）—— 本 debug 构建的 APK " +
                    "安装后 Ubuntu 终端不可用（BundledRootfsSource 将报 ARCHIVE_INVALID）。" +
                    "如需可用 Ubuntu：scripts/fetch_rootfs.sh --arch arm64-v8a"
            )
        }
    }
}

// ═══ S-01 门禁：发布签名凭据 fail-closed ═══
//
// 背景：v1.4.8 起 release 签名曾以「入库 keystore + 默认口令」实现 —— 私钥
// 与口令随公开仓库分发，任何人可用其签出与本 App 同签名的 APK（插件信任、
// 覆盖安装防线全部失效）。S-01 把 keystore 移出版本控制后，构建脚本里不再
// 有任何默认口令；本任务在 release 构建前置位校验凭据完整性，缺失即以可
// 操作的指引失败（AGP 自身的报错更晚且含混）。
//
// 语义：keystore 文件存在 + store 口令 / alias / key 口令齐备（env 或 Gradle
// 属性注入，无默认值）。debug 构建不受影响（无凭据时回退 debug 签名，见
// buildTypes.debug）。
tasks.register("checkReleaseSigningCredentials") {
    group = "verification"
    description = "S-01 guard: release signing credentials must be injected via env/Gradle properties (no defaults since keystore left VCS)."
    doLast {
        val problems = mutableListOf<String>()
        if (!file(releaseKeystorePath).isFile) {
            problems += "keystore 文件不存在：$releaseKeystorePath（设 APEX_KEYSTORE_FILE 或 -PapexKeystoreFile）"
        }
        if (releaseStorePassword.isNullOrBlank()) {
            problems += "storePassword 缺失：设 APEX_KEYSTORE_PASSWORD（或 -PapexKeystorePassword）"
        }
        if (releaseKeyAlias.isNullOrBlank()) {
            problems += "keyAlias 缺失：设 APEX_KEY_ALIAS（或 -PapexKeyAlias）"
        }
        if (releaseKeyPassword.isNullOrBlank()) {
            problems += "keyPassword 缺失：设 APEX_KEY_PASSWORD（或 -PapexKeyPassword）"
        }
        if (problems.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("[signing-guard] S-01 fail-closed：发布签名凭据不完整 —— 无默认值可回退（keystore 已移出版本控制）。")
                    problems.forEach { appendLine("  - $it") }
                    appendLine("本地生成新 keystore：scripts/generate_release_keystore.sh（口令经 env 注入，不入库）。")
                    appendLine("CI 配置：repo secrets APEX_RELEASE_KEYSTORE_BASE64 / APEX_KEYSTORE_PASSWORD / APEX_KEY_ALIAS / APEX_KEY_PASSWORD。")
                    appendLine("开发迭代免发布签名：-PapexDebugSign=true（debug 签名）。")
                }
            )
        }
        logger.lifecycle("[signing-guard] OK — 发布签名凭据就绪（keystore=${file(releaseKeystorePath).name}）")
    }
}

// release：严格门禁（rootfs 缺失/指纹不符、签名凭据缺失 = 构建失败）；
// debug：存在性警告（不阻断迭代）。
// ⚠️ 时机：AGP 的 preBuild/preReleaseBuild 在 afterEvaluate 之前尚未注册 ——
// 直接 tasks.named(...) 在脚本执行期即抛 "Task not found"（CI 实测）。
// 挪入 afterEvaluate：任务注册完成后解析，严格性不降级（不存在照样报错）。
project.afterEvaluate {
    tasks.named("preReleaseBuild") { dependsOn("checkBundledRootfs", "checkReleaseSigningCredentials") }
    tasks.named("preBuild") { dependsOn("warnBundledRootfs") }
}
