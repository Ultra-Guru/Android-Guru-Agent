package com.apex.agent.core.jiguan

/**
 * # 命令蓝图（Command Blueprints）
 *
 * Root shell / PRoot 双通道的所有命令在引擎内**以 argv 形态集中构造** ——
 * 平台适配器只负责执行。好处：
 * 1. 命令注入面收敛（参数一律经 [shellQuote] 或独立 argv 传递）；
 * 2. 命令语义 JVM 单测全覆盖（dd 偏移换算 / tcpdump 过滤式 / objdump 架构位宽）；
 * 3. 双通道差异化（root 直执行 vs PRoot 沙箱内路径）在构造点声明。
 */
object CommandBlueprints {

    /** POSIX 单引号转义（值进单引号串，内部单引号闭合转义）。 */
    fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    // ── 内存读取（dd 经 /proc/pid/mem → 临时文件 → 宿主回读） ───

    /**
     * root 通道读内存：dd 落盘（**4096 对齐窗口** —— 兼容所有 dd 变体：
     * GNU / busybox / toybox 都支持 bs + skip 块单位；iflag=skip_bytes 仅
     * 新工具链有）。调用方保证 [alignedStart] / [alignedSize] 为 4096 倍数，
     * 读回后在引擎侧裁剪目标区间。
     */
    fun ddReadMemAligned(pid: Int, alignedStart: Long, alignedSize: Int, outFile: String): List<String> {
        require(alignedStart % 4096L == 0L && alignedSize % 4096 == 0 && alignedSize > 0) {
            "dd window must be 4096-aligned (start=$alignedStart size=$alignedSize)"
        }
        return listOf(
            "dd",
            "if=/proc/$pid/mem",
            "of=$outFile",
            "bs=4096",
            "skip=${alignedStart / 4096}",
            "count=${alignedSize / 4096}",
        )
    }

    /** root 通道写内存：临时文件 → dd conv=notrunc 精确落位。 */
    fun ddWriteMem(pid: Int, address: Long, dataFile: String): List<String> = listOf(
        "dd",
        "if=$dataFile",
        "of=/proc/$pid/mem",
        "bs=1",
        "seek=$address",
        "conv=notrunc",
    )

    // ── 抓包（tcpdump，PRoot 优先 / root 回落） ─────────────────

    /**
     * tcpdump 启动（-i any 全接口；filter 例 "host 1.2.3.4" / "port 443"）。
     * 注：App 级（pid）过滤在 tcpdump 过滤式里无法表达 —— 抓全量，
     * App 归属在引擎侧用 /proc/<pid>/net 软关联（抓包工具的通用做法）。
     */
    fun tcpdumpStart(pcapFile: String, filter: String): List<String> {
        val cmd = mutableListOf(
            "tcpdump",
            "-i", "any",
            "-s", "0",
            "-w", pcapFile,
        )
        if (filter.isNotBlank()) cmd.add(filter)
        return cmd
    }

    /** 停止抓包（tcpdump 无优雅停 CLI —— pkill 按落盘文件精确匹配）。 */
    fun tcpdumpStop(pcapFile: String): List<String> = listOf(
        "pkill", "-f", "tcpdump.*${pcapFile.substringAfterLast('/')}",
    )

    // ── 反汇编（PRoot binutils：内存段落盘 → objdump -b binary） ──

    /**
     * objdump 裸二进制反汇编（aarch64 小端）。[adjustVma] 非零时恢复
     * 真实进程地址（dump 落盘后 VMA 从 0 起 —— 反汇编行号还原用）。
     */
    fun objdumpBinary(dumpFile: String, arch: String = "aarch64", adjustVma: Long = 0L): List<String> =
        buildList {
            add("objdump")
            add("-D")
            add("-b"); add("binary")
            add("-m"); add(arch)
            add("-EL")
            if (adjustVma != 0L) add("--adjust-vma=0x${adjustVma.toString(16)}")
            add(dumpFile)
        }

    // ── Frida（PRoot frida-tools CLI → frida-server 127.0.0.1:27042） ──

    /** frida CLI 静默执行脚本（-l 脚本文件；-q quiet；超时由外层管理）。 */
    fun fridaRunScript(pid: Int, scriptFile: String, timeoutSec: Int): List<String> = listOf(
        "timeout", timeoutSec.toString(),
        "frida",
        "-H", "127.0.0.1:27042",
        "-p", pid.toString(),
        "-l", scriptFile,
        "-q",
        "--no-pause",
    )

    /** frida-server 探测（进程表匹配）。 */
    fun fridaServerProbe(): List<String> = listOf("pgrep", "-f", "frida-server")

    // ── 系统域（SELinux / 服务面） ──────────────────────────────

    fun selinuxMode(): List<String> = listOf("getenforce")

    fun selinuxContext(path: String): List<String> = listOf("ls", "-Z", path)

    fun serviceList(): List<String> = listOf("service", "list")

    fun dumpsysService(service: String): List<String> = listOf("dumpsys", service)

    // ── Root 输入注入（input tap/swipe —— 比无障碍更隐蔽的通道） ──

    fun inputTap(x: Int, y: Int): List<String> = listOf("input", "tap", x.toString(), y.toString())

    fun inputSwipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): List<String> = listOf(
        "input", "swipe",
        x1.toString(), y1.toString(), x2.toString(), y2.toString(), durationMs.toString(),
    )
}
