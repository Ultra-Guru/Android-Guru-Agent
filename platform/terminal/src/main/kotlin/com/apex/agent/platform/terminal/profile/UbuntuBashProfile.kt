package com.apex.agent.platform.terminal.profile

import java.io.File

/**
 * T87：Ubuntu guest bashrc 生成器 —— Ubuntu 会话开箱体验补全。
 *
 * 落点：持久化 home（host `<filesDir>/linux/home`，bind → guest /root）的
 * `.bashrc` —— [com.apex.agent.platform.terminal.workspace.GuestUserHome] 播种
 * 时使用（旧版 MINIMAL_BASHRC 提示符没有颜色/没有容错）。
 *
 * 用户反馈对应：
 *  - 「Ubuntu 还是会显示 apt 未引导」→ `command_not_found_handle` 给出
 *    **可行动**提示（`apt install <pkg>`），另附 `apt-fix` 引导修复函数
 *    （DNS 重试 + 换镜像 + dpkg --configure -a 一键三连）。
 *  - 「像 Termux 一样彩色」→ PS1 彩色（root 绿字 + 路径亮蓝 + git 分支），
 *    ls/grep/less/man 全部上色（bash \[ \] 宽度标记 —— readline 编辑安全）。
 *
 * 兼容性纪律：
 *  - bash \[ \] 非打印标记**必须**包住所有 ANSI 序列（否则长行编辑光标错位）。
 *  - 所有工具调用容错（`--color=auto 2>/dev/null ||` 回退），旧 coreutils 不炸。
 *  - 受管块用标记对包裹（同 GuestUserHome.TOOLCHAIN_ENV_BLOCK 哲学），
 *    幂等注入：已含标记 → 不重写（用户改动优先）。
 */
object UbuntuBashProfile {

    /** 受管标记对（幂等注入/升级检测）。 */
    const val MANAGED_BEGIN = "# >>> apex-bash-profile (managed by Android-Guru-Agent) >>>"
    const val MANAGED_END = "# <<< apex-bash-profile <<<"

    /** 完整 bashrc（新 home 播种用）。 */
    fun generate(): String = buildString {
        appendLine("# ~/.bashrc — Android-Guru-Agent Ubuntu guest home")
        appendLine("# 由 host 播种；用户可自由修改（受管块外的改动升级时保留）。")
        appendLine(MANAGED_BEGIN)
        appendLine(managedBlock())
        appendLine(MANAGED_END)
    }.trimIndent().let { it + "\n" }

    /** 受管块内容（幂等追加到既有 .bashrc 尾部）。 */
    fun managedBlock(): String = buildString {
        // ── 交互守卫：非交互（scp/rsync/apt postinst）全部跳过 ──
        appendLine("case \$- in *i*) ;; *) return 2>/dev/null || exit 0;; esac")
        appendLine()
        // ── 彩色 PS1（root 绿 + 路径亮蓝 + git 分支持续黄）──
        appendLine("# PS1：root@host 绿 + 路径亮蓝 + git 分支 —— \\[ \\] 宽度标记必带（readline 安全）")
        appendLine("gurc_git_branch() {")
        appendLine("    b=\$(git symbolic-ref --short HEAD 2>/dev/null) || b=\$(git rev-parse --short HEAD 2>/dev/null) || return 0")
        appendLine("    printf ' (%s)' \"\$b\"")
        appendLine("}")
        appendLine("if [ \"\$TERM\" != dumb ]; then")
        appendLine("    PS1='\\[\\e[1;32m\\]\\u@\\h\\[\\e[0m\\]:\\[\\e[1;34m\\]\\w\\[\\e[33m\\]\$(gurc_git_branch)\\[\\e[0m\\]# '")
        appendLine("    PS2='\\[\\e[32m\\]> \\[\\e[0m\\]'")
        appendLine("else")
        appendLine("    PS1='\\u@\\h:\\w# '")
        appendLine("fi")
        appendLine()
        // ── ls/grep/less/man 彩色 ──
        appendLine("# ls 彩色（dir=f 目录=亮蓝、符号链接=亮青…；GNU ls 标准 KEY）")
        appendLine("export LS_COLORS='di=1;34:ln=1;36:so=35:pi=33:ex=1;32:bd=1;33:cd=1;33:su=37;41:sg=30;43:tw=30;42:ow=34;42'")
        appendLine("ls --color=auto -d / >/dev/null 2>&1 && alias ls='ls --color=auto'")
        appendLine("alias ll='ls -alF --color=auto'")
        appendLine("alias la='ls -A --color=auto'")
        appendLine("alias l='ls -CF --color=auto'")
        appendLine("grep --color=auto -q x /dev/null 2>/dev/null && alias grep='grep --color=auto'")
        appendLine("alias egrep='grep -E --color=auto' 2>/dev/null")
        appendLine("# less 彩色 + man 彩色（ANSI 直传；不可用时静默回退）")
        appendLine("export LESS='-R'")
        appendLine("export LESS_TERMCAP_md=\$'\\e[1;36m'   # man 标题亮青")
        appendLine("export LESS_TERMCAP_me=\$'\\e[0m'")
        appendLine("export LESS_TERMCAP_us=\$'\\e[1;32m'   # 下划线亮绿")
        appendLine("export LESS_TERMCAP_ue=\$'\\e[0m'")
        appendLine()
        // ── 历史与输入习惯 ──
        appendLine("export HISTCONTROL=ignoreboth:erasedups")
        appendLine("export HISTSIZE=5000")
        appendLine("export HISTFILESIZE=10000")
        appendLine("export HISTTIMEFORMAT='%F %T '")
        appendLine("shopt -s histappend checkwinsize 2>/dev/null")
        appendLine()
        // ── command_not_found_handle：从「找不到」到「怎么装」──
        appendLine("# 未知命令 → 可行动提示（command-not-found 包未装时也有引导）")
        appendLine("command_not_found_handle() {")
        appendLine("    echo \"bash: \$1: 未找到命令\" >&2")
        appendLine("    case \"\$1\" in")
        appendLine("        python|python3|pip|pip3) echo '  提示: apt install python3 python3-pip' >&2 ;;")
        appendLine("        gcc|g++|make|cc)            echo '  提示: apt install build-essential' >&2 ;;")
        appendLine("        git)                        echo '  提示: apt install git' >&2 ;;")
        appendLine("        curl|wget)                  echo '  提示: apt install curl wget' >&2 ;;")
        appendLine("        vim|nano|less)              echo '  提示: apt install vim nano less' >&2 ;;")
        appendLine("        htop|tmux|screen)           echo '  提示: apt install htop tmux screen' >&2 ;;")
        appendLine("        ssh|scp)                    echo '  提示: apt install openssh-client' >&2 ;;")
        appendLine("        zip|unzip|tar)              echo '  提示: apt install zip unzip tar' >&2 ;;")
        appendLine("        *)                          echo '  提示: apt install <包名>（或用 apt-cache search 检索）' >&2 ;;")
        appendLine("    esac")
        appendLine("    return 127")
        appendLine("}")
        appendLine()
        // ── apt 自修复引导（apt-fix：DNS→dpkg→镜像三连）──
        appendLine("apt-fix() {")
        appendLine("    echo '── apt 引导修复（DNS → dpkg → 官方源/镜像重试）──'")
        appendLine("    cat /etc/resolv.conf 2>/dev/null | head -3")
        appendLine("    echo '── dpkg 中断修复 ──'")
        appendLine("    dpkg --configure -a 2>&1 | tail -5")
        appendLine("    echo '── apt update（官方源；失败换镜像见 host 环境中心）──'")
        appendLine("    apt-get update 2>&1 | tail -10")
        appendLine("}")
        appendLine()
        appendLine("true")
    }.trimIndent().let { it + "\n" }

    /**
     * 幂等确保 home 目录的 .bashrc 含受管块。
     *
     * 规则：
     *  1. 无 .bashrc → 写 [generate] 全量；
     *  2. 有但缺标记对 → 尾部追加受管块（用户内容原样保留）；
     *  3. 已含标记对 → 不动（用户/后续版本管理）。
     *
     * @return 变更动作描述（写日志/测试断言用）
     */
    fun ensure(homeDir: File): String {
        val bashrc = File(homeDir, ".bashrc")
        if (!bashrc.isFile) {
            bashrc.writeText(generate())
            return "bashrc: seeded full profile (${bashrc.length()} bytes)"
        }
        val content = bashrc.readText()
        if (content.contains(MANAGED_BEGIN) && content.contains(MANAGED_END)) {
            return "bashrc: managed block already present — skipped"
        }
        bashrc.writeText(content.trimEnd() + "\n\n" + MANAGED_BEGIN + "\n" + managedBlock() + MANAGED_END + "\n")
        return "bashrc: appended managed block (+${bashrc.length()} bytes total)"
    }
}
