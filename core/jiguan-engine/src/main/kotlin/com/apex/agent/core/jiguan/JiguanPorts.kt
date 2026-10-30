package com.apex.agent.core.jiguan

import kotlinx.coroutines.flow.StateFlow

/**
 * # 机关运行时端口（Ports）
 *
 * 引擎（纯 Kotlin，core:jiguan-engine）对宿主环境的**全部**依赖收敛为以下
 * 小接口 —— app 层 DI 适配（platform:jiguan-host 提供实现；core 不反向
 * 依赖平台模块，镜像 ReaCommandRunner / SkillHotReloadLogSink 的注入哲学）。
 *
 * 通道语义（重要）：所有重 IO 端口的实现按环境择优 ——
 * 1. **Native .so 加速**（jiguan-native Rust 核心在 classpath 时）；
 * 2. **Root shell**（PrivilegeManager：dd / /proc 直读）；
 * 3. **PRoot Ubuntu 沙箱**（frida-tools / tcpdump / objdump 等 apt 工具链）；
 * 4. **不可用** → 结构化降级（[CapabilityReport] 携带指引，绝不抛异常出引擎）。
 *
 * 未注入或通道缺席时，工具层输出降级指引文本 —— 引擎本身永不因端口缺失崩溃。
 */

// ─────────────────────────────────────────────────────────────────────
// 命令执行端口（Root / PRoot 双通道，参数化 argv 防注入）
// ─────────────────────────────────────────────────────────────────────

/** 命令执行结果。 */
data class JiguanCommandOutput(
    val command: List<String>,
    val stdout: String,
    val stderr: String = "",
    val exitCode: Int,
    val durationMs: Long = 0,
    /** 实际执行通道（"root" = Root shell；"proot" = PRoot Ubuntu；"unavailable"）。 */
    val channel: String = "unavailable",
) {
    fun isOk(): Boolean = exitCode == 0
}

/**
 * 参数化命令执行端口 —— [com.apex.agent.core.tools.fusion.rea.ReaCommandRunner]
 * 的机关同构版（argv 形态、PRoot 优先 / 宿主 shell 回落、超时由实现管理）。
 */
fun interface JiguanCommandRunner {
    suspend fun run(command: List<String>, timeoutMs: Long): Result<JiguanCommandOutput>
}

// ─────────────────────────────────────────────────────────────────────
// 一、进程与内存域
// ─────────────────────────────────────────────────────────────────────

/** 进程 / 内存操控端口（读取走最优通道；写入必须 root，见各实现降级说明）。 */
interface JiguanMemoryPort {

    /** 枚举进程（无 root 时只含自身 UID 可见的进程 —— 语义由实现声明）。 */
    suspend fun listProcesses(): Result<List<ProcessEntry>>

    /** 目标进程的内存映射（maps 投影；无权限时空表 + failure 语义）。 */
    suspend fun regions(pid: Int): Result<List<MemoryRegion>>

    /** 读一段内存（返回原始字节；失败 Result）。 */
    suspend fun read(pid: Int, address: Long, size: Int): Result<ByteArray>

    /** 写一段内存（原子替换；实现必须做快照日志 —— 安全纪律）。 */
    suspend fun write(pid: Int, address: Long, data: ByteArray): Result<Unit>

    /** 生效通道名（native-so / root-shell / none）—— 能力报告用。 */
    val channelName: String
}

// ─────────────────────────────────────────────────────────────────────
// 二、Java / ART 域
// ─────────────────────────────────────────────────────────────────────

/** Java 运行时端口（Frida 桥接为主通道）。 */
interface JiguanJavaPort {

    /** 枚举目标进程已加载类（Java.enumerateLoadedClasses 投影）。 */
    suspend fun listClasses(pid: Int, filter: String): Result<List<JavaClassEntry>>

    /** 执行一段 Frida JS（[FridaScriptLibrary] 生成 / 用户自定义）并回收 console 输出。 */
    suspend fun runScript(pid: Int, script: String, timeoutMs: Long): Result<String>

    /** 通道可用性探测（frida-server 存活 / 可拉起）。 */
    suspend fun ping(): Boolean = true

    /** 通道可用性（frida-server 在跑 + CLI 可用）。 */
    val channelName: String
}

// ─────────────────────────────────────────────────────────────────────
// 三、Native 域
// ─────────────────────────────────────────────────────────────────────

/** Native 域端口（模块枚举 = maps 投影；hook / 反汇编走 Frida / objdump）。 */
interface JiguanNativePort {

    /** 目标进程加载的模块清单（maps 聚合出 .so / 主映像）。 */
    suspend fun listModules(pid: Int): Result<List<ModuleEntry>>

    /** 反汇编一段内存（addr → DisasmLine 列表；通道：objdump / hex 回退）。 */
    suspend fun disassemble(pid: Int, address: Long, size: Int): Result<List<DisasmLine>>

    /** Frida JS 执行面（与 Java 域共用一个 FridaBridge，端口上各自声明）。 */
    suspend fun runScript(pid: Int, script: String, timeoutMs: Long): Result<String>

    /** 通道可用性探测（模块枚举走 maps —— 同 UID 直读；跨 UID 需 root）。 */
    suspend fun ping(): Boolean = true

    val channelName: String
}

// ─────────────────────────────────────────────────────────────────────
// 四、网络域
// ─────────────────────────────────────────────────────────────────────

/** 抓包端口（tcpdump 落盘 pcap；HTTP 提取在引擎内纯 Kotlin 解析）。 */
interface JiguanNetworkPort {

    /** 启动抓包（pid 为空 = 全设备；返回会话）。 */
    suspend fun startCapture(pid: Int?, filter: String): Result<CaptureSession>

    /** 停止抓包（返回累计 pcap 路径）。 */
    suspend fun stopCapture(sessionId: Int): Result<CaptureSession>

    /** 通道可用性探测（tcpdump 存在 + root/shell 通道）。 */
    suspend fun ping(): Boolean = true

    val channelName: String
}

// ─────────────────────────────────────────────────────────────────────
// 五、系统 / 框架域
// ─────────────────────────────────────────────────────────────────────

/** 系统域端口（SELinux / 服务面 / Root 输入注入）。 */
interface JiguanSystemPort {

    /** 巡检：SELinux 模式 + 自身上下文 + 服务样例。 */
    suspend fun inspect(): Result<SystemInspectResult>

    /** Root 级物理输入注入（input tap/swipe —— 比无障碍更隐蔽的通道）。 */
    suspend fun injectTouch(x: Int, y: Int, swipe: Boolean, x2: Int, y2: Int, durationMs: Long): Result<Unit>

    /** 通道可用性探测（root / Shizuku 任一在位）。 */
    suspend fun ping(): Boolean = true

    val channelName: String
}

// ─────────────────────────────────────────────────────────────────────
// 六、知识面（Universal Memory RAG）
// ─────────────────────────────────────────────────────────────────────

/** 知识存取端口（实现落 filesDir JSON；原子写由实现保证）。 */
interface JiguanKnowledgeStore {
    suspend fun all(): List<AppKnowledge>
    suspend fun save(entry: AppKnowledge)
    suspend fun delete(appPackage: String, title: String)
}

// ─────────────────────────────────────────────────────────────────────
// 七、能力状态（引擎对外广播的探测面）
// ─────────────────────────────────────────────────────────────────────

/** 运行时能力总线 —— 引擎启动探测一次，端口实现热更新时刷新。 */
interface JiguanCapabilityBus {
    /** 各域能力报告（domain → report）。 */
    val capabilities: StateFlow<List<CapabilityReport>>
}
