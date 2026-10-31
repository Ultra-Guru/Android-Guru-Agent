package com.apex.agent.environment

/**
 * A developer-environment dependency (JDK / Gradle / SDK / NDK / Node / Go / Rust…).
 *
 * T97（Ubuntu 大重构 · 版本自选）：终端控制台 UI 已随「Ubuntu 归 Agent 全权
 * 掌控」决策移除 —— 本目录只剩**开发环境安装**这一张 UI。为此 [DepItem]
 * 升级为版本可选模型：
 *  - [versions] 非空 → UI 渲染版本下拉（自选版本）；
 *  - 安装命令模板支持 `{V}` 占位符（所选版本插值）；
 *  - [checkCommand] 用于「已装探测」（状态徽章）。
 *
 * 命令执行环境：Ubuntu（proot）rootfs 内真实 bash —— 经
 * [EnvironmentProvisioner] 路由到 linux-ubuntu 会话。
 *
 * 命令风格契约（T97）：
 *  - **纯 `&&` 链，禁用 `set -e`** —— 安装命令跑在交互式 PTY 会话里，
 *    `set -e` 会把失败升级为「整个 bash 会话退出」（杀死所有后续安装）；
 *    `&&` 链的天然短路已给出诚实的非零退出码；
 *  - **失败即断链** —— 最后一步是版本验证命令（`xxx --version`），安装
 *    失败则验证不执行 → 退出码非零 → UI 日志如实展示 `[exit=N]`。
 *
 * @param id             stable identifier (e.g. "jdk")
 * @param name           human-readable name
 * @param group          BASIC / JAVA / ANDROID / RUNTIME（UI 分组渲染）
 * @param description    一句话说明（UI 副标题）
 * @param versions       可选版本列表（空 = 无版本可选，命令模板不含 {V}）
 * @param defaultVersion 默认选中版本（null → versions.first()）
 * @param installOfficial shell 命令模板（官方源；`{V}` = 版本插值）
 * @param installMirror   shell 命令模板（国内镜像；`{V}` = 版本插值）
 * @param checkCommand    shell 命令验证安装（exit 0 = 已装）
 */
data class DepItem(
    val id: String,
    val name: String,
    val group: DepGroup,
    val description: String,
    val versions: List<String> = emptyList(),
    val defaultVersion: String? = null,
    val installOfficial: String,
    val installMirror: String,
    val checkCommand: String
) {
    /** 有版本可选（UI 渲染下拉的判定位）。 */
    val versioned: Boolean get() = versions.isNotEmpty()

    /** 默认版本（null 时取 versions 首项；无版本项恒 null）。 */
    val preferredVersion: String? get() = defaultVersion ?: versions.firstOrNull()

    /**
     * 生成安装命令（版本插值）。
     *
     * @param useMirror true = 镜像模板（国内源）
     * @param version   所选版本（versioned 且非空时插值到 `{V}`）
     */
    fun installCommand(useMirror: Boolean, version: String? = null): String {
        val template = if (useMirror) installMirror else installOfficial
        if (!template.contains("{V}")) return template
        val v = if (versioned) (version ?: preferredVersion) else null
        return if (v != null) template.replace("{V}", v) else template
    }
}

enum class DepGroup { BASIC, JAVA, ANDROID, RUNTIME }

/**
 * Canonical dependency list (T97 重写)。
 *
 * 旧清单问题（v2 遗留）：
 *  1. Gradle 用 apt 装（Ubuntu 24.04 的 apt 源里是 4.4.1 古董版）却标榜「8.10」；
 *  2. sdkmanager 系列命令假设 sdkmanager 已在 PATH —— 但没有任何条目真正装
 *     cmdline-tools（先有鸡还是先有蛋）；
 *  3. 镜像变体 `sdkmanager --proxy=http --proxy_host=mirrors.tuna.tsinghua.edu.cn`
 *     是**伪镜像**（TUNA 不是 HTTP 代理服务器）—— 100% 失败；
 *  4. JDK/Build-Tools/NDK 均为单一固定版本，无自选能力。
 *
 * 新清单设计原则：
 *  - **零鸡生蛋**：cmdline-tools 用直链 zip 落地（官方 dl.google.com / 腾讯镜像），
 *    其余 sdkmanager 条目依赖它（描述里注明前置）；platform-tools 同样直链 zip；
 *  - **版本自选**：JDK 17/21/11/8、Build-Tools 35/34/36/33、NDK 27/26/25、
 *    Gradle 8.12/8.11/8.10、Node 22/20/18、Go 1.24/1.23/1.22、Rust stable/nightly；
 *  - **真镜像**：apt → TUNA（arm64 走 ubuntu-ports）；Gradle → 腾讯；
 *    Node → npmmirror；Go → USTC；Rust → rsproxy.cn；Android SDK zips → 腾讯；
 *    sdkmanager 仓库无法换源（谷歌硬编码 dl.google.com）→ 镜像开关对 sdkmanager
 *    条目不生效（两模板相同，诚实不伪装）；
 *  - **PATH 持久化**：所有二进制 symlink 进 /usr/local/bin（任何 shell 直接可用），
 *    结构化变量（ANDROID_HOME 等）写 /etc/profile.d/*.sh；
 *  - **幂等**：重复安装安全（unzip -o / ln -sf / apt -y）。
 */
object DepCatalog {

    // ═══════════ 命令模板（shell 文本经 Kotlin 转义后所见即所得）═══════════
    //
    // Kotlin 转义备忘：`\$` → shell 字面 `$`；`\\n` → shell 字面 `\n`（printf
    // 再解释为换行）。模板内 `{V}` 为版本占位符、`{URL}` 为镜像基址占位符。

    /**
     * TUNA apt 镜像前缀（arm64 自动走 ubuntu-ports）。
     *
     * 用 `-o Dir::Etc::sourcelist` 单次指向 TUNA 源做 update —— 不改全局
     * deb822 配置（bootstrap 的 HTTP-first 官方源契约不受影响），install
     * 消费的是刚拉的 TUNA 索引。tuna.list 落盘仅为 update 读取，与既有
     * ubuntu.sources 并存无害。
     */
    private const val APT_TUNA_PREFIX =
        "A=\$(dpkg --print-architecture); P=\$([ \"\$A\" = arm64 ] && echo ubuntu-ports || echo ubuntu); " +
            "printf 'deb https://mirrors.tuna.tsinghua.edu.cn/\$P/ noble main restricted universe multiverse\\n' > /etc/apt/sources.list.d/tuna.list && " +
            "apt-get update -o Dir::Etc::sourcelist=/etc/apt/sources.list.d/tuna.list -o Dir::Etc::sourceparts=/dev/null && "

    /** cmdline-tools：解包 + PATH 持久化（/usr/local/bin symlink + profile.d）。 */
    private const val CMDLINE_TOOLS_CMD =
        "mkdir -p /opt/android-sdk/cmdline-tools && " +
            "curl -fsSL {URL} -o /tmp/cmdtools.zip && " +
            "unzip -qo /tmp/cmdtools.zip -d /tmp/cmdtools && rm -rf /opt/android-sdk/cmdline-tools/latest && " +
            "mv /tmp/cmdtools/cmdline-tools /opt/android-sdk/cmdline-tools/latest && rm -rf /tmp/cmdtools /tmp/cmdtools.zip && " +
            "printf 'export ANDROID_HOME=/opt/android-sdk\\nexport PATH=\$PATH:\$ANDROID_HOME/cmdline-tools/latest/bin:\$ANDROID_HOME/platform-tools\\n' > /etc/profile.d/android-sdk.sh && " +
            "ln -sf /opt/android-sdk/cmdline-tools/latest/bin/sdkmanager /usr/local/bin/sdkmanager && " +
            "ln -sf /opt/android-sdk/cmdline-tools/latest/bin/avdmanager /usr/local/bin/avdmanager && " +
            "(yes | sdkmanager --licenses >/dev/null 2>&1 || true) && sdkmanager --version"

    /** platform-tools：直链 zip 解包到 /opt/android-sdk + adb/fastboot symlink。 */
    private const val PLATFORM_TOOLS_CMD =
        "mkdir -p /opt/android-sdk && " +
            "curl -fsSL {URL} -o /tmp/pt.zip && " +
            "unzip -qo /tmp/pt.zip -d /opt/android-sdk && rm -f /tmp/pt.zip && " +
            "ln -sf /opt/android-sdk/platform-tools/adb /usr/local/bin/adb && " +
            "ln -sf /opt/android-sdk/platform-tools/fastboot /usr/local/bin/fastboot && adb --version"

    /** build-tools：sdkmanager 安装 + 四件套 symlink。 */
    private const val BUILD_TOOLS_CMD =
        "sdkmanager --install \"build-tools;{V}\" && " +
            "ln -sf /opt/android-sdk/build-tools/{V}/aapt /usr/local/bin/aapt && " +
            "ln -sf /opt/android-sdk/build-tools/{V}/aapt2 /usr/local/bin/aapt2 && " +
            "ln -sf /opt/android-sdk/build-tools/{V}/zipalign /usr/local/bin/zipalign && " +
            "ln -sf /opt/android-sdk/build-tools/{V}/apksigner /usr/local/bin/apksigner && aapt version"

    /** NDK：sdkmanager 安装 + ANDROID_NDK_HOME 持久化 + ndk-build symlink。 */
    private const val NDK_CMD =
        "sdkmanager --install \"ndk;{V}\" && " +
            "printf 'export ANDROID_NDK_HOME=/opt/android-sdk/ndk/{V}\\n' >> /etc/profile.d/android-sdk.sh && " +
            "ln -sf /opt/android-sdk/ndk/{V}/ndk-build /usr/local/bin/ndk-build && " +
            "(ln -sf /opt/android-sdk/ndk/{V}/toolchains/llvm/prebuilt/*/bin/clang /usr/local/bin/ndk-clang 2>/dev/null || true) && " +
            "ndk-build --version"

    /**
     * Node.js：官方 tarball 解包 /opt + node/npm/npx symlink。
     * `$A` = dpkg 架构映射到 node 命名（amd64 → x64；arm64 原样）。
     */
    private const val NODE_CMD =
        "A=\$(dpkg --print-architecture | sed s/amd64/x64/); " +
            "curl -fsSL {URL}/v{V}/node-v{V}-linux-\$A.tar.xz -o /tmp/node.tar.xz && " +
            "tar -xJf /tmp/node.tar.xz -C /opt && " +
            "ln -sf /opt/node-v{V}-linux-\$A/bin/node /usr/local/bin/node && " +
            "ln -sf /opt/node-v{V}-linux-\$A/bin/npm /usr/local/bin/npm && " +
            "ln -sf /opt/node-v{V}-linux-\$A/bin/npx /usr/local/bin/npx && " +
            "rm -f /tmp/node.tar.xz && node --version"

    /**
     * Go：官方 tarball 解包 /usr/local/go + go/gofmt symlink。
     * `$A` = dpkg 架构映射到 go 命名（amd64 → x86_64；arm64 原样）。
     */
    private const val GO_CMD =
        "A=\$(dpkg --print-architecture | sed s/amd64/x86_64/); " +
            "curl -fsSL {URL}/go{V}.linux-\$A.tar.gz -o /tmp/go.tgz && " +
            "tar -xzf /tmp/go.tgz -C /usr/local && rm -f /tmp/go.tgz && " +
            "printf 'export PATH=\$PATH:/usr/local/go/bin\\n' > /etc/profile.d/golang.sh && " +
            "ln -sf /usr/local/go/bin/go /usr/local/bin/go && " +
            "ln -sf /usr/local/go/bin/gofmt /usr/local/bin/gofmt && go version"

    /** Rust：rustup 官方安装器 + cargo/rustc/rustup symlink。 */
    private const val RUST_OFFICIAL_CMD =
        "curl -fsSL https://sh.rustup.rs | sh -s -- -y --default-toolchain {V} --profile minimal && " +
            "printf 'export PATH=\$PATH:/root/.cargo/bin\\n' > /etc/profile.d/rust.sh && " +
            "ln -sf /root/.cargo/bin/cargo /usr/local/bin/cargo && " +
            "ln -sf /root/.cargo/bin/rustc /usr/local/bin/rustc && " +
            "ln -sf /root/.cargo/bin/rustup /usr/local/bin/rustup && rustc --version"

    /** Rust 镜像：rsproxy.cn（RUSTUP_DIST_SERVER + rustup-init.sh 双替换）。 */
    private const val RUST_MIRROR_CMD =
        "export RUSTUP_DIST_SERVER=https://rsproxy.cn RUSTUP_UPDATE_ROOT=https://rsproxy.cn/rustup; " +
            "curl -fsSL https://rsproxy.cn/rustup-init.sh | sh -s -- -y --default-toolchain {V} --profile minimal && " +
            "printf 'export PATH=\$PATH:/root/.cargo/bin\\n' > /etc/profile.d/rust.sh && " +
            "ln -sf /root/.cargo/bin/cargo /usr/local/bin/cargo && " +
            "ln -sf /root/.cargo/bin/rustc /usr/local/bin/rustc && " +
            "ln -sf /root/.cargo/bin/rustup /usr/local/bin/rustup && rustc --version"

    val ALL: List<DepItem> = listOf(

        // ═══════════ 基础工具（BASIC）═══════════

        DepItem(
            id = "git", name = "Git", group = DepGroup.BASIC,
            description = "版本控制（bootstrap 已装，可修复重装）",
            installOfficial = "apt-get update && apt-get install -y git",
            installMirror = APT_TUNA_PREFIX + "apt-get install -y git",
            checkCommand = "git --version"
        ),
        DepItem(
            id = "build-essential", name = "C / C++ 工具链", group = DepGroup.BASIC,
            description = "gcc / g++ / make / libc 头文件",
            installOfficial = "apt-get update && apt-get install -y build-essential",
            installMirror = APT_TUNA_PREFIX + "apt-get install -y build-essential",
            checkCommand = "gcc --version"
        ),
        DepItem(
            id = "cmake", name = "CMake", group = DepGroup.BASIC,
            description = "跨平台构建系统（apt 源最新版）",
            installOfficial = "apt-get update && apt-get install -y cmake",
            installMirror = APT_TUNA_PREFIX + "apt-get install -y cmake",
            checkCommand = "cmake --version"
        ),
        DepItem(
            id = "pkg-config", name = "pkg-config", group = DepGroup.BASIC,
            description = "编译期依赖发现（C/C++ 项目常用）",
            installOfficial = "apt-get update && apt-get install -y pkg-config",
            installMirror = APT_TUNA_PREFIX + "apt-get install -y pkg-config",
            checkCommand = "pkg-config --version"
        ),

        // ═══════════ Java 与构建（JAVA）═══════════

        DepItem(
            id = "jdk", name = "JDK (OpenJDK)", group = DepGroup.JAVA,
            description = "Java 编译运行时（sdkmanager 的前置）",
            versions = listOf("17", "21", "11", "8"),
            defaultVersion = "17",
            installOfficial = "apt-get update && apt-get install -y openjdk-{V}-jdk-headless",
            installMirror = APT_TUNA_PREFIX + "apt-get install -y openjdk-{V}-jdk-headless",
            checkCommand = "java -version"
        ),
        DepItem(
            id = "gradle", name = "Gradle", group = DepGroup.JAVA,
            description = "官方发行版 zip（apt 源是 4.x 古董，不可用）",
            versions = listOf("8.12.1", "8.11.1", "8.10.2"),
            defaultVersion = "8.12.1",
            installOfficial =
                "curl -fsSL https://services.gradle.org/distributions/gradle-{V}-bin.zip -o /tmp/gradle.zip && " +
                    "unzip -qo /tmp/gradle.zip -d /opt && ln -sf /opt/gradle-{V}/bin/gradle /usr/local/bin/gradle && " +
                    "rm -f /tmp/gradle.zip && gradle --version",
            installMirror =
                "curl -fsSL https://mirrors.cloud.tencent.com/gradle/gradle-{V}-bin.zip -o /tmp/gradle.zip && " +
                    "unzip -qo /tmp/gradle.zip -d /opt && ln -sf /opt/gradle-{V}/bin/gradle /usr/local/bin/gradle && " +
                    "rm -f /tmp/gradle.zip && gradle --version",
            checkCommand = "gradle --version"
        ),

        // ═══════════ Android 开发环境（ANDROID）═══════════

        DepItem(
            id = "cmdline-tools", name = "Android cmdline-tools", group = DepGroup.ANDROID,
            description = "sdkmanager 本体（其余 Android 组件的前置；需 JDK）",
            installOfficial = CMDLINE_TOOLS_CMD.replace("{URL}", "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"),
            installMirror = CMDLINE_TOOLS_CMD.replace("{URL}", "https://mirrors.cloud.tencent.com/AndroidSDK/commandlinetools-linux-11076708_latest.zip"),
            checkCommand = "sdkmanager --version"
        ),
        DepItem(
            id = "platform-tools", name = "Platform Tools (adb)", group = DepGroup.ANDROID,
            description = "adb / fastboot（直链 zip，无需 sdkmanager）",
            installOfficial = PLATFORM_TOOLS_CMD.replace("{URL}", "https://dl.google.com/android/repository/platform-tools-latest-linux.zip"),
            installMirror = PLATFORM_TOOLS_CMD.replace("{URL}", "https://mirrors.cloud.tencent.com/AndroidSDK/platform-tools-latest-linux.zip"),
            checkCommand = "adb --version"
        ),
        DepItem(
            id = "build-tools", name = "Android Build-Tools", group = DepGroup.ANDROID,
            description = "aapt / aapt2 / zipalign / apksigner（需 cmdline-tools + JDK）",
            versions = listOf("35.0.0", "34.0.0", "36.0.0", "33.0.2"),
            defaultVersion = "35.0.0",
            installOfficial = BUILD_TOOLS_CMD,
            // sdkmanager 仓库源为谷歌硬编码（dl.google.com），无公开镜像可换 ——
            // 伪代理（--proxy_host=TUNA）必然失败，镜像开关对本条目诚实无效。
            installMirror = BUILD_TOOLS_CMD,
            checkCommand = "aapt version"
        ),
        DepItem(
            id = "ndk", name = "Android NDK", group = DepGroup.ANDROID,
            description = "native 开发工具链（需 cmdline-tools + JDK，体积大）",
            versions = listOf("27.0.12077973", "26.3.11579464", "25.2.9519653"),
            defaultVersion = "27.0.12077973",
            installOfficial = NDK_CMD,
            installMirror = NDK_CMD,
            checkCommand = "ndk-build --version"
        ),

        // ═══════════ 更多开发环境（RUNTIME）═══════════

        DepItem(
            id = "node", name = "Node.js", group = DepGroup.RUNTIME,
            description = "官方二进制发行版（npm / npx 一并就绪）",
            versions = listOf("22.14.0", "20.18.1", "18.20.4"),
            defaultVersion = "22.14.0",
            installOfficial = NODE_CMD.replace("{URL}", "https://nodejs.org/dist"),
            installMirror = NODE_CMD.replace("{URL}", "https://registry.npmmirror.com/-/binary/node"),
            checkCommand = "node --version"
        ),
        DepItem(
            id = "go", name = "Go", group = DepGroup.RUNTIME,
            description = "官方 tarball（含 gofmt）",
            versions = listOf("1.24.1", "1.23.6", "1.22.12"),
            defaultVersion = "1.24.1",
            installOfficial = GO_CMD.replace("{URL}", "https://go.dev/dl"),
            installMirror = GO_CMD.replace("{URL}", "https://mirrors.ustc.edu.cn/golang"),
            checkCommand = "go version"
        ),
        DepItem(
            id = "rust", name = "Rust", group = DepGroup.RUNTIME,
            description = "rustup 安装器（rustc / cargo）",
            versions = listOf("stable", "nightly"),
            defaultVersion = "stable",
            installOfficial = RUST_OFFICIAL_CMD,
            installMirror = RUST_MIRROR_CMD,
            checkCommand = "rustc --version"
        ),
        DepItem(
            id = "python3", name = "Python 3 + pip", group = DepGroup.RUNTIME,
            description = "python3 / pip / venv（bootstrap 已装，可修复重装）",
            installOfficial = "apt-get update && apt-get install -y python3 python3-pip python3-venv",
            installMirror = APT_TUNA_PREFIX + "apt-get install -y python3 python3-pip python3-venv",
            checkCommand = "python3 --version"
        )
    )
}
