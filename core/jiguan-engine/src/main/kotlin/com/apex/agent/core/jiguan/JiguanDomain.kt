package com.apex.agent.core.jiguan

/**
 * # 机关（Jiguan）域模型 —— 全能系统级 Agent 终端 V3.0
 *
 * 机关把「云大脑 + 本地手」的四大操控维度（Native / Java-ART / System-Binder /
 * Network-UI）统一成一套可降级的端口模型（见 [JiguanPorts]）。本文件只放
 * 纯数据：进程、内存、模块、Hook、反汇编、断点、抓包、SELinux、知识条目
 * —— 全部 immutable，序列化面收敛在知识条目（RAG 持久化）。
 *
 * 设计纪律（对齐仓库 AGENTS.md）：
 * - 纯 Kotlin JVM、零 Android 依赖 —— 可被 CI 镜像编译与单测全覆盖；
 * - 防御式解析：/proc 文本任何形状都吞得下，坏行折叠为 null/跳过；
 * - 「能力可用性」是一等公民：Android 碎片化（8→15 / MIUI→鸿蒙）意味着
 *   任何后端都可能缺席，模型必须能拿到**结构化的降级指引**而不是异常。
 */

// ═══════════════════════════════════════════════════════════════════
// 一、进程与内存域（Process & Memory）
// ═══════════════════════════════════════════════════════════════════

/** 一个运行中进程的快照（/proc/<pid> 投影）。 */
data class ProcessEntry(
    val pid: Int,
    /** 进程名（comm，内核限 15 字符；cmdline 可读时优先完整名）。 */
    val name: String,
    /** 完整命令行（空格拼接，空 = 匿名/内核线程或不可读）。 */
    val cmdline: String = "",
    /** 属主 UID（-1 = 未知）。 */
    val uid: Int = -1,
    /** 父进程 PID（-1 = 未知）。 */
    val ppid: Int = -1,
    /** 主线程状态字母（R/S/Z/…，空 = 未知）。 */
    val state: String = "",
    /** RSS 物理内存占用（KiB，-1 = 未知）。 */
    val rssKb: Long = -1L,
    /** APK 包名（从 cmdline 提取的 pkg 形态段，可空）。 */
    val appPackage: String? = null,
)

/** 一段内存映射（/proc/<pid>/maps 的一行）。 */
data class MemoryRegion(
    val start: Long,
    val end: Long,
    /** 权限三字符 + p/s（如 "rw-p"）。 */
    val perms: String,
    val offset: Long,
    /** 设备号 "major:minor"。 */
    val dev: String,
    val inode: Long,
    /** 映射名（文件路径 / [heap] / [stack] / 空 = 匿名）。 */
    val path: String,
) {
    val sizeBytes: Long get() = end - start
    val readable: Boolean get() = perms.length >= 1 && perms[0] == 'r'
    val writable: Boolean get() = perms.length >= 2 && perms[1] == 'w'
    val executable: Boolean get() = perms.length >= 3 && perms[2] == 'x'
    /** 人类可读的地址区间。 */
    fun rangeText(): String = "0x${start.toString(16)}-0x${end.toString(16)}"
}

/** 内存扫描的值类型（全类型支持 = GameGuardian 对等面）。 */
enum class ScanValueType(val byteCount: Int) {
    INT8(1), INT16(2), INT32(4), INT64(8),
    FLOAT32(4), FLOAT64(8),
    UTF8(0), UTF16(0),
    BYTE_PATTERN(0);
}

/** 扫描请求（精确 / 模糊 ± / AOB 三模式收敛为一个请求对象）。 */
data class ScanRequest(
    val pid: Int,
    /** 精确值 / 模糊值的字面量（INT/FLOAT 家族）或字符串（UTF8/UTF16）。 */
    val value: String = "",
    val type: ScanValueType = ScanValueType.INT32,
    /** 模糊区间（仅数值家族有效，0 = 精确匹配）。 */
    val fuzzyRange: Double = 0.0,
    /** AOB 模式："DE AD ?? EF"（BYTE_PATTERN 时生效）。 */
    val bytePattern: String = "",
    /** 只扫这些区域路径包含的映射（空 = 全部 rw 区域）。 */
    val regionFilter: String = "",
    /** 最多返回命中数（Context 经济 —— 大命中集必须封顶）。 */
    val maxResults: Int = 200,
    /** 每区域最多读取字节数（防爆内存预算）。 */
    val regionReadCap: Long = 8L * 1024 * 1024,
)

/** 一条扫描命中。 */
data class ScanHit(
    val address: Long,
    /** 命中处的当前值（按请求类型的可读形态）。 */
    val valueText: String,
    /** 所在区域路径（匿名 = "[anon]"）。 */
    val regionPath: String,
)

/** 一次 Hex 视图（memory_hex_dump 的结构化产物）。 */
data class HexDumpView(
    val pid: Int,
    val address: Long,
    val size: Int,
    /** 十六进制主体（含 ASCII 栏），可直接进 LLM 上下文。 */
    val text: String,
    /** 按小端解释的前 8 字节常用数值形态（LLM 分析数据结构用）。 */
    val interpretations: List<String>,
)

// ═══════════════════════════════════════════════════════════════════
// 二、Java / ART 域（Java Runtime）
// ═══════════════════════════════════════════════════════════════════

/** 已加载 Java 类的枚举条目。 */
data class JavaClassEntry(
    val name: String,
    /** 来源 dex / apk（可空 = 枚举通道未提供）。 */
    val source: String? = null,
)

// ═══════════════════════════════════════════════════════════════════
// 三、Native 域（NDK & Assembler）
// ═══════════════════════════════════════════════════════════════════

/** 目标进程加载的一个 .so / 可执行映像。 */
data class ModuleEntry(
    val name: String,
    val path: String,
    val baseAddress: Long,
    val sizeBytes: Long,
    val readable: Boolean,
    val writable: Boolean,
    val executable: Boolean,
)

/** 一行反汇编（addr / bytes / mnemonic 三栏，LLM 可直接读）。 */
data class DisasmLine(
    val address: Long,
    val bytes: String,
    val text: String,
)

/** 硬件断点安装结果。 */
data class BreakpointHandle(
    val id: Int,
    val address: Long,
    val channel: String,
)

// ═══════════════════════════════════════════════════════════════════
// 四、Omni-Hook 统一模型（跨 Java / Native / ART）
// ═══════════════════════════════════════════════════════════════════

/** Hook 目标维度。 */
enum class HookKind { JAVA_METHOD, NATIVE_FUNC, ART_METHOD }

/**
 * Hook 通道 —— Omni-Hook 引擎按环境自动择优（用户有 Zygisk 用 Zygisk，
 * 有 frida-server 用 FRIDA_SERVER，都无则虚拟通道/指引降级）。
 */
enum class HookChannel {
    /** frida-server 常驻 + CLI 注入（PRoot frida-tools 执行面）。 */
    FRIDA_SERVER,
    /** Frida Gadget 重打包注入（无 root 场景）。 */
    FRIDA_GADGET,
    /** Zygisk / LSPosed 环境（用户已装模块框架）。 */
    ZYGISK,
    /** 无可用注入通道 —— 结构化降级（给出安装指引）。 */
    UNAVAILABLE,
}

/** 一条 Hook 规格（Agent 的声明式意图，脚本由 [FridaScriptLibrary] 生成）。 */
data class HookSpec(
    val kind: HookKind,
    val pid: Int,
    /** Java: com.xxx.EncryptUtil；Native: libxxx.so。 */
    val className: String = "",
    val methodName: String = "",
    /** Native 符号名 / 导出函数。 */
    val symbolName: String = "",
    /** 行为：LOG 只记录出入参，REPLACE 整体替换返回值，CALL 主动调用。 */
    val action: String = "LOG",
    /** REPLACE 时的返回值字面量 / CALL 时的参数 JSON 数组字面量。 */
    val actionPayload: String = "",
    /** 用户/AI 手写的自定义 Frida JS 主体（非空时优先于模板）。 */
    val customScript: String = "",
)

/** 已装 Hook 的登记项。 */
data class HookHandle(
    val id: Int,
    val spec: HookSpec,
    val channel: HookChannel,
    /** 已捕获的事件（出入参日志行）。 */
    val events: List<String> = emptyList(),
)

// ═══════════════════════════════════════════════════════════════════
// 五、网络域（Network & Traffic）
// ═══════════════════════════════════════════════════════════════════

/** 一次抓包会话状态。 */
data class CaptureSession(
    val id: Int,
    val pid: Int?,
    /** pcap 落盘路径（宿主可见路径）。 */
    val pcapPath: String,
    val running: Boolean,
    /** 使用的通道（tcpdump-proot / tcpdump-root / unavailable）。 */
    val channel: String,
)

/** 从 pcap 里提取的一条 HTTP 报文（请求或响应）。 */
data class HttpMessage(
    val streamId: String,
    val direction: String, // "request" | "response"
    val method: String,
    val url: String,
    val host: String,
    val status: Int,
    val headers: String,
    val bodyPreview: String,
)

// ═══════════════════════════════════════════════════════════════════
// 六、系统 / 框架域（Binder / SELinux / 提权面）
// ═══════════════════════════════════════════════════════════════════

/** 系统域巡检结果（SELinux / 服务 / Binder 面）。 */
data class SystemInspectResult(
    /** SELinux 模式（Enforcing / Permissive / Disabled / unknown）。 */
    val selinuxMode: String,
    /** 当前进程 SELinux 上下文（u:r:…）。 */
    val selfContext: String,
    /** root 可用性（PrivilegeManager 投影）。 */
    val rootAvailable: Boolean,
    /** system 服务数量与样例。 */
    val serviceSample: List<String>,
)

// ═══════════════════════════════════════════════════════════════════
// 七、能力面（Capability）—— 结构化降级的根基
// ═══════════════════════════════════════════════════════════════════

/** 一个能力域的可用性报告（引擎启动时探测，Agent 决策依据）。 */
data class CapabilityReport(
    val domain: String,
    val available: Boolean,
    /** 缺席原因 + 用户可执行的补救指引（装 frida-server / 开 root…）。 */
    val guidance: String,
    /** 实际生效的通道（root-shell / proot-frida / native-so / none）。 */
    val channel: String = "none",
)

// ═══════════════════════════════════════════════════════════════════
// 八、全场景记忆（Universal Memory RAG）
// ═══════════════════════════════════════════════════════════════════

/**
 * 一个 App 的机关知识条目 —— 破解/分析经验的向量化前形态。
 *
 * 结构：应用包名 →（Hook 点特征 / 内存结构 / 网络协议特征 / 操作配方）。
 * 下一次同类 App，Agent 按「包名精确 + 特征文本相似」复用知识。
 */
data class AppKnowledge(
    val appPackage: String,
    val title: String,
    /** 知识类别（hook-signature / memory-landmark / protocol / recipe）。 */
    val category: String,
    /** 机器可复述的内容（Hook 代码 / 地址结构 / 协议字段说明 / 步骤清单）。 */
    val content: String,
    /** 关键词（相似匹配用，空格分词）。 */
    val keywords: String = "",
    /** 命中成功次数（复用即 +1，蒸馏纪律的证据）。 */
    val successCount: Int = 0,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    /** 相似度评分（0..1）：关键词命中数 / 关键词总数，包名全等直接 1。 */
    fun relevanceScore(query: String, queryPackage: String?): Double {
        if (queryPackage != null && queryPackage == appPackage) return 1.0
        val kws = keywords.split(' ', '\t').filter { it.isNotBlank() }
        if (kws.isEmpty()) return if (content.contains(query, ignoreCase = true)) 0.3 else 0.0
        val q = query.lowercase()
        val hit = kws.count { q.contains(it.lowercase()) }
        val base = hit.toDouble() / kws.size
        val contentBonus = if (content.lowercase().contains(q)) 0.2 else 0.0
        return (base + contentBonus).coerceAtMost(1.0)
    }
}
