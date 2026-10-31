package com.apex.agent.platform.terminal.profile

/**
 * T87：Android 本地 Shell（mksh）rc 生成器 —— Shell 模式体验补全。
 *
 * 用户反馈「Shell 模式只有部分命令可以用」的四类根因与对策：
 *  1. **提示符裸奔**（`$ ` 无上下文）→ rc 提供 user@host:cwd 提示符 +
 *     历史记录（HISTFILE 需可写 HOME —— 由 app 层注入 $filesDir 路径）。
 *  2. **命令发现性差**（toybox 工具存在但用户不知道）→ `cmds` 函数列出
 *     PATH 上全部可用命令（按目录分组），`help` 打印速查卡。
 *  3. **无常用别名** → ll/la/l 等零成本别名（仅用 toybox 确定支持的形态）。
 *  4. **宿主工具误用**（T95 真机日志：adb/systemd-analyze/lsb_release 在
 *     设备 shell 里裸报 not found）→ 同款引导函数：说明工具属性 + 给出
 *     设备侧正确替代（getprop/pm/am/dumpsys），把 404 变成路标。
 *
 * 兼容性纪律（mksh ≠ bash）：
 *  - PS1 不用 ANSI 色转义：mksh 的提示符宽度计算不认识 \[ \]（bash readline
 *    专属），带色提示符会让长命令行编辑光标错位 —— 提示符保持纯文本
 *    （Termux 默认同样纯文本）。终端的彩色来自 VT 渲染层配色方案。
 *  - 只用 mksh 确定支持的语法：POSIX 函数 `name() { …; }`、${VAR:-default}、
 *    $(cmd)。不依赖数组/关联数组/局部变量（mksh 支持 local 但守 POSIX 更稳）。
 *  - 任何一条失败不影响 shell 启动：rc 内所有命令容错（|| true / 2>/dev/null）。
 *
 * 纯 Kotlin（字符串生成）—— platform:terminal JVM 单测直测内容不变式。
 */
object GuestShellProfile {

    /** rc 文件名（app 层写入 $filesDir/linux/shell/）。 */
    const val RC_FILENAME = ".gurc"

    /**
     * 生成本地 shell 的 mksh rc 内容。
     *
     * @param hostnameHint 宿主机名（默认 android —— 真实值由 app 层从
     *   Build.MODEL 或 /proc/sys/kernel/hostname 读取后注入；rc 内再有兜底）
     */
    fun generate(hostnameHint: String = "android"): String = buildString {
        appendLine("# ─── Android-Guru-Agent local shell profile (mksh) ───")
        appendLine("# 由 host 侧生成；mksh 经 \$ENV 在交互启动时 source。")
        appendLine("# 纪律：零 bash 专属语法 / 零 PS1 色转义（宽度安全）/ 全命令容错。")
        appendLine()
        appendLine("# ── 身份与提示符 ──")
        appendLine("HOSTNAME=\${HOSTNAME:-\$(cat /proc/sys/kernel/hostname 2>/dev/null || echo '$hostnameHint')}")
        appendLine("export HOSTNAME")
        appendLine("USER=\${USER:-\$(id -un 2>/dev/null || echo u0)}")
        appendLine("export USER")
        appendLine("# 简写 cwd（家目录折叠为 ~；子 shell 展开每次求值）")
        appendLine("gurc_cwd() { case \"\$PWD\" in \"\$HOME\") echo '~' ;; \"\$HOME\"/*) echo \"~\${PWD#\$HOME}\" ;; *) echo \"\$PWD\" ;; esac; }")
        appendLine("PS1='\${USER}@\${HOSTNAME}:\$(gurc_cwd) \$ '")
        appendLine()
        appendLine("# ── 历史记录（HOME 由 host 注入可写路径时才真正生效）──")
        appendLine("if [ -n \"\$HOME\" ] && [ -d \"\$HOME\" ] && touch \"\$HOME/.h\" 2>/dev/null; then")
        appendLine("    rm -f \"\$HOME/.h\" 2>/dev/null")
        appendLine("    HISTFILE=\"\$HOME/.mksh_history\"")
        appendLine("    HISTSIZE=2000")
        appendLine("    export HISTFILE HISTSIZE")
        appendLine("fi")
        appendLine()
        appendLine("# ── 别名（只用 toybox 确定支持的形态）──")
        appendLine("alias ll='ls -alF'")
        appendLine("alias la='ls -A'")
        appendLine("alias l='ls -CF'")
        appendLine("alias ..='cd ..'")
        appendLine("alias ...='cd ../..'")
        appendLine()
        appendLine("# ── 命令发现：PATH 上到底有什么 ──")
        appendLine("# cmds —— 全部可用命令（去重排序；toybox 工具一网打尽）")
        appendLine("cmds() {")
        appendLine("    for d in \$(echo \"\$PATH\" | tr ':' ' '); do")
        appendLine("        [ -d \"\$d\" ] || continue")
        appendLine("        ls \"\$d\" 2>/dev/null")
        appendLine("    done | sort -u")
        appendLine("}")
        appendLine("# cmdf <name> —— 某命令在哪、是什么（type+file 双保险）")
        appendLine("cmdf() {")
        appendLine("    [ -n \"\$1\" ] || { echo 'usage: cmdf <command>'; return 2; }")
        appendLine("    type \"\$1\" 2>/dev/null")
        appendLine("    p=\$(command -v \"\$1\" 2>/dev/null) && file \"\$p\" 2>/dev/null")
        appendLine("}")
        appendLine()
        appendLine("# ── Ubuntu 引导（本 shell 无 apt —— 明说，不再让用户猜）──")
        appendLine("apt() {")
        appendLine("    echo \"apt 只在 Ubuntu 会话可用（顶栏 + 新建会话 → Ubuntu）。\"")
        appendLine("    echo \"当前是 Android 本地 shell（toybox 工具集，cmds 查看全部命令）。\"")
        appendLine("    return 127")
        appendLine("}")
        appendLine("apt-get() { apt \"\$@\"; }")
        appendLine()
        appendLine("# ── 宿主工具引导（T95：真机日志实锤 —— 用户在设备 shell 里跑")
        appendLine("# adb shell getprop / systemd-analyze / lsb_release，全是 PC 侧工具，")
        appendLine("# mksh 无 command_not_found 钩子，只能得到一句裸的 not found。")
        appendLine("# 这里按 apt() 同款范式给出口径 + 正确替代，把 404 变成路标）──")
        appendLine("adb() {")
        appendLine("    echo \"adb 是 PC 侧工具，设备上不存在 —— 就在本机直接跑：\"")
        appendLine("    echo \"  getprop <name>      # 例：getprop ro.build.version.release\"")
        appendLine("    echo \"  pm / am / dumpsys    # 包管理 / 组件 / 诊断（cmds 看全集）\"")
        appendLine("    return 127")
        appendLine("}")
        appendLine("systemd-analyze() {")
        appendLine("    echo \"Android 无 systemd —— 启动耗时看：dmesg | grep -i init\"")
        appendLine("    echo \"或 logcat -b events | grep boot。\"")
        appendLine("    return 127")
        appendLine("}")
        appendLine("systemctl() {")
        appendLine("    echo \"Android 无 systemd —— 服务由 init 解析 .rc 启动：\"")
        appendLine("    echo \"  getprop | grep init.  /  ps -A | grep <name>\"")
        appendLine("    return 127")
        appendLine("}")
        appendLine("lsb_release() {")
        appendLine("    echo \"本机是 Android，不是 Linux 发行版 —— 版本信息：\"")
        appendLine("    echo \"  getprop ro.build.version.release   # Android 大版本\"")
        appendLine("    echo \"  getprop ro.product.model / ro.product.brand\"")
        appendLine("    echo \"  uname -a                           # 内核\"")
        appendLine("    return 127")
        appendLine("}")
        appendLine()
        appendLine("# ── help 速查卡 ──")
        appendLine("guru_help() {")
        appendLine("    echo 'Android-Guru-Agent · Android Shell 速查'")
        appendLine("    echo '  cmds            列出全部可用命令'")
        appendLine("    echo '  cmdf <cmd>      查命令位置与类型'")
        appendLine("    echo '  ll / la / l     ls 别名'")
        appendLine("    echo '  Ubuntu 会话      顶栏 + → Ubuntu（apt/python3/gcc/git 可用）'")
        appendLine("    echo '  本机即设备      adb/systemd 系命令不存在：getprop/pm/am/dumpsys 直用'")
        appendLine("}")
        appendLine("alias help=guru_help")
        appendLine()
        appendLine("true")
    }.trimIndent().let { it + "\n" }

    /**
     * 本地会话注入的 env（app 层经 terminalRuntime.create(env=…) 传入；
     * 在朴素 shell 默认 env 之上覆盖 —— T73 语义：调用方意图优先）。
     *
     * @param homeHostDir 可写 HOME（app filesDir 下的 shell home；mksh 历史/rc 目录）
     * @param rcHostPath .gurc 绝对路径（mksh 交互启动 source \$ENV）
     */
    fun shellEnv(homeHostDir: String, rcHostPath: String): Map<String, String> = mapOf(
        "HOME" to homeHostDir,
        "ENV" to rcHostPath,
        "TERM" to "xterm-256color",
        "COLORTERM" to "truecolor"
    )

    /** 交互式会话 shell home 下应确保存在的子目录（预创建，免首条命令报错）。 */
    fun ensureShellHome(homeDir: java.io.File): java.io.File {
        if (!homeDir.isDirectory) homeDir.mkdirs()
        return homeDir
    }
}
