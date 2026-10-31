package com.apex.agent.platform.terminal.searxng

/**
 * ═══ M1：guest 命令常量与构造器（SearXNG Local Search Spec v1.0 §7）═══
 *
 * 全部为**纯字符串函数**（零执行、零状态）—— 命令经 [SearxngCommandPort] 在
 * Ubuntu 沙箱内执行（生产：ProotExecutor；与 UbuntuAptPackageManager 的 apt
 * 命令同一条非交互执行路径）。集中于此便于：
 *  - 单测逐条断言（引号/路径/幂等守卫不再散落）；
 *  - 审计（危险命令 review 只看一个文件）。
 *
 * 幂等性契约（Spec F11 —— 安装失败可断点重试，各阶段重复执行无副作用）：
 *  - apt install：已装包自动跳过（dpkg 幂等）；
 *  - git clone：目标存在即跳过（installer 层判定）；
 *  - venv 创建：`python3 -m venv` 对已存在目录幂等（--without-pip 不重复装 pip）；
 *  - pip install：已满足依赖自动跳过（pip 语义）。
 */
object SearxngScripts {

    /** SearXNG 上游源（release 分支 —— 与官方 docker 镜像同源）。 */
    const val GIT_REPO_URL = "https://github.com/searxng/searxng.git"

    /** apt 依赖（python3-venv 是 venv 模块的硬依赖；git 为 clone 阶段必需）。 */
    const val APT_PACKAGES =
        "apt-get update -qq && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq python3 python3-venv python3-pip git"

    /** 安装目录创建（幂等）。 */
    const val MKDIR_INSTALL_DIR = "mkdir -p ${SearxngContract.GUEST_INSTALL_DIR}"

    /**
     * git clone（幂等由调用方保证 —— 目标目录存在时 installer 不执行本命令）。
     * --depth 1 省 90% 流量与时间（PRoot 内 IO 慢，浅克隆 8–15s vs 完整 60s+）。
     */
    const val GIT_CLONE =
        "git clone --depth 1 ${GIT_REPO_URL} ${SearxngContract.GUEST_SRC_DIR}"

    /** venv 创建（幂等：已存在目录不破坏既有内容）。 */
    const val VENV_CREATE =
        "python3 -m venv ${SearxngContract.GUEST_VENV_DIR}"

    /**
     * pip 依赖安装（幂等：已装依赖自动跳过）。
     * --no-cache-dir：PRoot 内 ~/.cache 体积失控的主因，直接禁用缓存。
     */
    const val PIP_INSTALL =
        "${SearxngContract.GUEST_VENV_DIR}/bin/pip install --no-cache-dir -r ${SearxngContract.GUEST_SRC_DIR}/requirements.txt"

    /**
     * 安装完整性冒烟（M1 验收：PRoot 会话内可 import）。
     * exit 0 = venv 内 SearXNG 可导入（依赖齐全）。
     */
    const val SMOKE_IMPORT =
        "cd ${SearxngContract.GUEST_SRC_DIR} && ${SearxngContract.GUEST_VENV_DIR}/bin/python -c \"import searx\""

    /**
     * 守护进程启动命令（Spec §7：python -m searx.webapp 语义 —— 不编译 uwsgi）。
     *
     * 实现注记：`python -m searx.webapp` 的 __main__ 路径在不同 SearXNG 版本间
     * 有漂移（有的版本无 main 块），这里用**等价的显式形式**：导入 app 对象 +
     * werkzeug run_simple（requirements.txt 自带 werkzeug，无新增依赖）。
     * threaded=True 让 JSON 搜索与 healthz 并发可用；use_reloader=False 禁掉
     * 文件监视线程（PRoot 内 inotify 语义不稳且费电）。
     *
     * 进程形态：python 作为 bash 的**前台子进程**运行（不用 exec 替换）——
     * T82 Shell Marker Protocol 依赖「命令结束 → bash 回 prompt 打尾标记」来
     * 上报真实退出码；exec 替换会让 bash 消失、尾标记永不出现，ProcessExited
     * 只能靠会话关闭兜底。保留 bash 父进程（~2MB）换取退出码保真（AC6 自愈
     * 的判定依据）。close() 的 kill(-PGID) 同时送达 bash+python，proot 的
     * --kill-on-exit 兜底清光 guest 孙进程。
     */
    fun daemonCommand(port: Int): String =
        "cd ${SearxngContract.GUEST_SRC_DIR} && " +
            "env SEARXNG_SETTINGS_PATH=${SearxngContract.GUEST_SETTINGS_FILE} " +
            "${SearxngContract.GUEST_VENV_DIR}/bin/python -c " +
            "\"from searx.webapp import app; from werkzeug.serving import run_simple; " +
            "run_simple('${SearxngContract.BIND_ADDRESS}', $port, app, threaded=True, use_reloader=False)\""

    /**
     * 卸载（Spec §8：rootfs 内 /opt/searxng —— 「清除搜索组件」入口执行）。
     * 注意只在守护进程 Off/Failed 状态调用（coordinator 门禁）。
     */
    const val UNINSTALL = "rm -rf ${SearxngContract.GUEST_INSTALL_DIR}"

    /** host 侧安装目录（guest 路径 → rootfs 物理路径换算）。 */
    fun hostInstallDir(rootfsDir: java.io.File): java.io.File =
        java.io.File(rootfsDir, SearxngContract.GUEST_INSTALL_DIR.removePrefix("/"))

    /** host 侧 venv python（isInstalled 判定基准，Spec §6）。 */
    fun hostVenvPython(rootfsDir: java.io.File): java.io.File =
        java.io.File(
            hostInstallDir(rootfsDir),
            ".venv/bin/python"
        )

    /** host 侧源码目录（git clone 完整性判定）。 */
    fun hostSrcDir(rootfsDir: java.io.File): java.io.File =
        java.io.File(hostInstallDir(rootfsDir), "src")

    /** host 侧 settings.yml。 */
    fun hostSettingsFile(rootfsDir: java.io.File): java.io.File =
        java.io.File(hostInstallDir(rootfsDir), "searxng.yml")
}
