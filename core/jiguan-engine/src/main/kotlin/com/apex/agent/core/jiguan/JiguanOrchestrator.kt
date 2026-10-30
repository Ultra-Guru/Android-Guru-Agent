package com.apex.agent.core.jiguan

/**
 * # 机关编排器（Unified Orchestrator）
 *
 * 云端 LLM 不需要记住所有基础 API —— 它掌握「能力域」，本编排器提供：
 * - **能力报告**：五域可用性 + 通道 + 降级指引（Agent 决策的一手依据）；
 * - **配方（Recipe）**：高频场景的 Thought 骨架（如「分析登录加密逻辑」），
 *   Agent 按配方串联工具调用 —— SPEC V3.0 工作流示例的代码化；
 * - **系统提示注入**：Tooling-as-Code 的机关能力域提示段。
 */
class JiguanOrchestrator(
    private val memoryPort: JiguanMemoryPort,
    private val javaPort: JiguanJavaPort,
    private val nativePort: JiguanNativePort,
    private val networkPort: JiguanNetworkPort,
    private val systemPort: JiguanSystemPort,
) {

    /** 五域能力报告（探测各端口通道；缺席域给指引）。 */
    suspend fun capabilityReport(): List<CapabilityReport> {
        data class Probe(val domain: String, val channel: String, val check: suspend () -> Boolean)
        val probes = listOf(
            Probe("process-memory", memoryPort.channelName) {
                runCatching { memoryPort.listProcesses().getOrThrow().isNotEmpty() }.isSuccess
            },
            Probe("java-art", javaPort.channelName) { runCatching { javaPort.ping() }.getOrDefault(false) },
            Probe("native", nativePort.channelName) { runCatching { nativePort.ping() }.getOrDefault(false) },
            Probe("network", networkPort.channelName) { runCatching { networkPort.ping() }.getOrDefault(false) },
            Probe("system", systemPort.channelName) { runCatching { systemPort.ping() }.getOrDefault(false) },
        )
        return probes.map { probe ->
            if (probe.channel != "none" && probe.check()) {
                CapabilityReport(probe.domain, available = true, guidance = "", channel = probe.channel)
            } else {
                CapabilityReport(
                    domain = probe.domain,
                    available = false,
                    guidance = guidanceFor(probe.domain),
                    channel = "none",
                )
            }
        }
    }

    /** 高频配方目录（Agent 的「打法库」）。 */
    fun recipes(): List<Recipe> = listOf(
        Recipe(
            id = "analyze-login-encryption",
            title = "分析 App 登录加密逻辑",
            steps = listOf(
                "1. JiguanPacketCapture 启动抓包（可选 JiguanSslBypass 先绕 SSL Pinning）",
                "2. 让用户/Agent 在目标 App 触发一次登录，JiguanPacketCapture 停止并提取 HTTP 报文",
                "3. 从报文里定位加密字段（password / sign / token 的密文形态）",
                "4. JiguanModules 查看目标进程加载的加密相关 .so（libcrypto / 自研 so）",
                "5. JiguanJavaClasses 过滤 Encrypt/Crypto/Sign 相关类",
                "6. JiguanHookJavaMethod 对可疑方法下 LOG Hook，捕获密钥与算法入参",
                "7. 拿到算法后用 TerminalRun 写 Python 复现加密，对比密文验证",
                "8. JiguanKnowledgeRemember 蒸馏本次结论（类名 + 算法 + 协议字段）",
            ),
        ),
        Recipe(
            id = "kill-splash-ads",
            title = "去除 App 开屏广告",
            steps = listOf(
                "1. JiguanProcesses 找到目标 App 进程（或从包名反查）",
                "2. JiguanJavaClasses 过滤 Splash/Ad/Launch 相关 Activity/类",
                "3. JiguanHookJavaMethod 对广告倒计时/展示方法下 REPLACE Hook（返回 0 / true）",
                "4. 观察 UI（UiDump / 截图）验证跳过生效",
                "5. JiguanKnowledgeRemember 记录 Hook 签名，下次同类 App 复用",
            ),
        ),
        Recipe(
            id = "memory-cheat-walkthrough",
            title = "数值修改标准流程（GameGuardian 对等）",
            steps = listOf(
                "1. JiguanProcesses 定位目标进程",
                "2. JiguanMemoryScan 精确扫描当前数值（INT32/FLOAT32）",
                "3. 在 App 内改变数值，再对上次结果做增量扫描（同值传 value，范围收窄）",
                "4. 命中收敛到个位数后 JiguanMemoryHexDump 查看数据结构上下文",
                "5. JiguanMemoryWrite 写入目标值（工具层有确认门 + 写前快照）",
            ),
        ),
    )

    /** 机关能力域系统提示段（Tooling-as-Code 注入面）。 */
    fun systemPromptFragment(): String = buildString {
        appendLine("### 机关（Jiguan）全能操控域")
        appendLine("你拥有系统级操控工具族（Jiguan 前缀），覆盖四大维度：")
        appendLine("- 进程与内存：进程枚举 / 全类型内存扫描（精确·模糊·AOB）/ Hex 视图 / 原子写入")
        appendLine("- Java/ART：类枚举 / 方法 Hook（LOG·REPLACE）/ 方法主动调用")
        appendLine("- Native：模块枚举 / 导出函数 Hook / 反汇编 / 硬件断点")
        appendLine("- 网络与系统：抓包与 HTTP 提取 / SSL Pinning 绕过 / Root 输入注入 / SELinux 巡检")
        appendLine("纪律：")
        appendLine("1. 先 JiguanCapabilityReport 看五域可用性，通道缺席时按 guidance 引导用户配置；")
        appendLine("2. 高频场景（登录加密分析/去广告/数值修改）优先按配方（JiguanRecipes）串联；")
        appendLine("3. 分析结论务必 JiguanKnowledgeRemember 蒸馏 —— 同类 App 下次直接复用；")
        appendLine("4. 内存写入与 Hook 安装前先取上下文（HexDump / 类枚举），绝不盲写。")
    }

    // ── 内部 ─────────────────────────────────────────────────────

    private fun guidanceFor(domain: String): String = when (domain) {
        "process-memory" ->
            "需要 root（或 Shizuku）：内存读写经 /proc/pid/mem + dd；" +
                "无 root 时仅可读自身进程。"
        "java-art" ->
            "需要注入通道：frida-server（/data/local/tmp 拉起 + PRoot pip install " +
                "frida-tools）或 Zygisk/LSPosed。"
        "native" ->
            "反汇编需要 PRoot Ubuntu（apt install binutils）；Hook 需要 frida-server。"
        "network" ->
            "抓包需要 tcpdump（PRoot: apt install tcpdump；root: 内置 toybox tcpdump）。"
        "system" ->
            "SELinux 巡检与 Root 输入注入需要 root 通道（PrivilegeManager）。"
        else -> "未知能力域"
    }

    /** 一条配方。 */
    data class Recipe(val id: String, val title: String, val steps: List<String>)
}
