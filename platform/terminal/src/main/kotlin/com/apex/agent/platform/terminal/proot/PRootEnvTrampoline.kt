package com.apex.agent.platform.terminal.proot

import com.apex.agent.platform.terminal.environment.LinuxEnvironmentManager

/**
 * T88: PRoot guest 环境变量 trampoline —— 用 `/usr/bin/env -i K=V … cmd` 传 guest env，
 * 彻底取代 proot `-E KEY=VALUE` 选项。
 *
 * ## 为什么必须 trampoline（根因档案）
 *
 * 本仓库捆绑的 proot 是 **5.1.107.92**（`platform/terminal/src/main/jniLibs/<abi>/libproot.so`，
 * Termux 时代的打包版）。`-E KEY=VALUE` 选项是 proot **5.3.0（2023）** 才引入的 ——
 * 5.1.107 的 `cli.c` 选项表里根本没有它，运行时直接报：
 *
 * ```
 * proot error: unknown option '-E'
 * bootstrap stage 'APT_UPDATE' failed: exit=1: …
 * ```
 *
 * 这正是用户实测的故障（T87 修复后依旧复现）：CI 的 E2E 环境安装的是 Debian proot
 * 5.4（支持 `-E`），所以 CI 全绿；设备上跑的是 APK 内捆绑的 5.1.107 ——
 * 经典的「CI 与生产二进制不一致」盲区。
 *
 * ## 为什么选 `/usr/bin/env -i`
 *
 * Termux proot-distro 的生产做法（proot_distro.sh 通行十几年的兼容路径）：
 *
 * ```
 * proot -r $ROOTFS -b … -w … -- /usr/bin/env -i HOME=/root PATH=… TERM=… /bin/bash --login
 * ```
 *
 * - `/usr/bin/env` 属于 coreutils（Debian/Ubuntu `required` 优先级），任何
 *   ubuntu-base rootfs 必然自带 —— 包括本仓库 `scripts/build_full_rootfs.sh` 的产物。
 * - `env -i` 保证 guest env **完全由本构造决定**（清空继承链），与 proot 版本无关：
 *   5.1.107 / 5.4 / 未来版本行为一致。
 * - 对 proot 5.4 依然合法（CI E2E 不需要分叉逻辑）。
 *
 * ## 防注入（继承 TM6 纪律）
 *
 * - key 必须匹配 `[A-Za-z_][A-Za-z0-9_]*` —— 拒绝 `-` 前缀（`env -i -K=V` 会被 env
 *   当作自己的选项解析）、拒绝含 `=`（`env` 只按第一个 `=` 切分，key 里的 `=` 会把
 *   边界搞乱）。
 * - value 禁止 `\n` / NUL（argv 边界完整性，原 `-E` 守卫的平移）。
 * - 环境变量顺序 = 调用方 Map 的迭代序（LinkedHashMap 稳定），golden 测试可精确断言。
 *
 * Spec: T88 PR 计划 §1（根因与策略）；对照 proot-me/proot cli.c 选项表
 * （5.1.107 vs 5.3.0+）与 Termux proot-distro 的 guest 启动 argv。
 */
object PRootEnvTrampoline {

    /** guest 侧 env 解释器（ubuntu-base 的 coreutils 必然存在）。 */
    const val ENV_EXECUTABLE: String = "/usr/bin/env"

    /** 清空 guest 继承 env，只保留显式传入的变量（proot-distro 同款）。 */
    const val CLEAN_ENV_FLAG: String = "-i"

    /** env KEY 合法形态：字母/下划线开头，只含字母数字下划线。 */
    private val ENV_KEY_PATTERN = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

    /**
     * 构造 guest 命令前缀：`[ENV_EXECUTABLE, CLEAN_ENV_FLAG, "K1=V1", …]`。
     *
     * 防御性保证：**永远注入 PATH**。`env` 用最终 PATH 解析相对可执行名
     * （如 `apt-get`），缺 PATH 的 guest shell 连内建命令查找都会失灵 ——
     * 调用方漏传时兜底 [LinuxEnvironmentManager.GUEST_PATH]（与权威基线同源）。
     *
     * @throws IllegalArgumentException key/value 违反防注入约束（TM6 平移）。
     */
    fun guestPrefix(environment: Map<String, String>): List<String> {
        for ((key, value) in environment) {
            require(ENV_KEY_PATTERN.matches(key)) {
                "PRootError:InvalidEnvKey — env trampoline key 必须匹配 " +
                    "[A-Za-z_][A-Za-z0-9_]*（got \"$key\"）；含 '-'/'=' 的 key 会被 " +
                    "/usr/bin/env 误解析为自身选项或破坏 KEY=VALUE 边界"
            }
            require(value.indexOf('\n') < 0 && value.indexOf('\u0000') < 0) {
                "PRootError:InvalidEnv — value for '$key' must not contain '\\n' or NUL " +
                    "(got ${value.length} chars); /usr/bin/env 的 KEY=VALUE 解析是 " +
                    "argument-injected（TM6）"
            }
        }
        val assignments = environment.entries.map { (k, v) -> "$k=$v" }.toMutableList()
        if (!environment.containsKey("PATH")) {
            assignments.add(0, "PATH=${LinuxEnvironmentManager.GUEST_PATH}")
        }
        return buildList(2 + assignments.size) {
            add(ENV_EXECUTABLE)
            add(CLEAN_ENV_FLAG)
            addAll(assignments)
        }
    }
}

/**
 * T88: PRoot argv 形状契约 —— 生产断言 + 测试共享的单一事实源。
 *
 * 背景：`-E` 事故的教训是「argv 形状没有机器可校验的契约，靠人肉记忆」。
 * 本对象把「合法 argv」固化为可执行检查，供三处消费：
 *  1. golden 单测（[PRootCommandBuilderImpl] 全路径快照）
 *  2. proot 5.1 兼容回归（[LEGACY_INCOMPATIBLE_FLAGS] 永不为空命中）
 *  3. 诊断工具运行时自检（TerminalDiagnostics 的 argv 审计）
 *
 * T91（D5）：argv 的合法形状由能力集决定（双探针实测 —— 见 [PRootArgvCapabilities]）：
 *  - Termux 方言：`…options -- /usr/bin/env -i K=V… cmd args…`；
 *  - 上游方言：`…options /usr/bin/env -i K=V… cmd args…`（无 `--` 终结符，
 *    首个非选项 token 即命令起点）。两个检查均接受两种形状。
 */
object PRootArgvContract {

    /**
     * 捆绑 proot 5.1.107.92 **不支持**、但曾被本仓库使用过的选项。
     * 新代码禁止出现在 argv —— 单测逐元素扫描。
     * （`-E` 是 proot 5.3.0+ 选项；其余为 5.2.0+ 的别名组，同样不在 5.1.107 选项表。）
     */
    val LEGACY_INCOMPATIBLE_FLAGS: Set<String> = setOf(
        "-E",      // proot 5.3.0+ 才有：环境变量注入 → 已由 env trampoline 取代
        "-R",      // 5.2.0+ 别名：rootfs + 推荐 binds → 显式 -b 取代
        "-S",      // 5.2.0+ 别名（-R + -0）→ 显式 -b + -0 取代
        "-L",      // 5.2.0+ 别名（-R + 隐藏 /etc）→ 不使用
        "-T",      // 5.2.0+ 别名（测试用）→ 不使用
        "-M",      // 5.2.0+ 别名（-L + fake id）→ 不使用
        "-C",      // 5.2.0+：验证 rootfs → 不使用
        "-P",      // 5.2.0+：pid 离散化 → 不使用
        "-B"       // 5.2.0+ 别名：最小 binds → 显式 -b 取代
    )

    /**
     * argv 中 proot 选项段是否含 5.1.107 不支持的 flag。
     * 返回命中的 flag 列表（空 = 合规）。argv[0] 是 proot 本体，跳过。
     *
     * T91（D5）：选项段边界按方言自适应 —— 有 `--` 终结符时到 `--` 止；
     * 无 `--`（上游方言）时到 env trampoline 首个 token（`/usr/bin/env`）止，
     * 避免 guest 命令段被误扫描（如 bash 的 `-i` —— 虽不在黑名单，
     * 但边界语义必须是「仅扫描 proot 自己看到的选项」）。
     */
    fun legacyIncompatibleFlags(argv: List<String>): List<String> {
        val dd = argv.indexOf("--")
        val optionSegment = if (dd >= 0) {
            argv.subList(0, dd)
        } else {
            // 上游方言：env trampoline 开头 = 命令段起点（T88 后生产 argv 必然以
            // trampoline 开头 —— trampoline 缺失时退化为全 argv 扫描，保持旧语义）。
            val cmdStart = argv.indexOfFirst { it == PRootEnvTrampoline.ENV_EXECUTABLE }
            if (cmdStart > 0) argv.subList(0, cmdStart) else argv
        }
        return optionSegment.filter { it in LEGACY_INCOMPATIBLE_FLAGS }
    }

    /**
     * guest 命令段应为一个 env trampoline：`[env, -i, K=V…, executable, args…]`。
     * T91（D5）：接受两种方言形状 —— `--` 后紧跟 trampoline（Termux），或无
     * `--` 时选项段后直接是 trampoline（上游）。返回 false = argv 形状违规。
     */
    fun hasEnvTrampoline(argv: List<String>): Boolean {
        val dd = argv.indexOf("--")
        val guestCmd = if (dd >= 0) {
            argv.subList(dd + 1, argv.size)
        } else {
            // 上游方言：首个非选项 token 起 = 命令段。trampoline 必然以
            // /usr/bin/env 开头 —— 直接定位它（找不到 = 无 trampoline）。
            val envIdx = argv.indexOfFirst { it == PRootEnvTrampoline.ENV_EXECUTABLE }
            if (envIdx <= 0) return false
            argv.subList(envIdx, argv.size)
        }
        return guestCmd.getOrNull(0) == PRootEnvTrampoline.ENV_EXECUTABLE &&
            guestCmd.getOrNull(1) == PRootEnvTrampoline.CLEAN_ENV_FLAG
    }
}
