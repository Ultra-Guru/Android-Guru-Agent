package com.apex.agent.core.tools.pluginhost

/**
 * 兼容性等级（spec §10.2 原样落地）。
 *
 * 判定只依据**真实实现状态**（[PluginEcosystemMatrix]）与**真实运行时
 * 探测**（[PluginEnvironmentSnapshot]），「能安装」不参与评级 —— 安装
 * 成功但能力全 PLANNED 的插件评 D，如实告诉用户「装了也用不上」。
 */
enum class PluginCompatibilityLevel(val label: String) {
    A("官方 API 主要能力完整兼容"),
    B("核心能力兼容，部分非核心功能不支持"),
    C("经过转换后部分能力可用"),
    D("只能安装或解析，无法真正运行"),
    F("当前平台不可运行")
}

/** 运行时需求判定。 */
data class RuntimeVerdict(
    val runtime: PluginRuntimeKind,
    val available: Boolean,
    /** 缺失/降级原因（报告人读行）。 */
    val note: String
)

/**
 * 执行环境快照 —— 兼容性分析的运行时输入（测试注入 fake，不依赖真机）。
 *
 * 字段与探测通道一一对应：node/npm 来自 [com.apex.agent.core.tools.extension.NodeInstallPort]
 * 的既有探针；bun/python/proot 的探测在 Phase 2 RuntimeProvider 落地后
 * 填充 —— 未探测的字段保持 null/false，分析器按「缺失」处理并说明原因，
 * 绝不默认乐观。
 */
data class PluginEnvironmentSnapshot(
    val nodeVersion: String? = null,
    val npmVersion: String? = null,
    val bunVersion: String? = null,
    val pythonVersion: String? = null,
    /** PRoot Ubuntu rootfs 是否就绪（沙箱执行域的前提）。 */
    val prootReady: Boolean = false,
    /** 设备 ABI（arm64 / x86_64 …；CI JVM 测试注入假值）。 */
    val abiArch: String = "arm64"
) {
    companion object {
        /** 从既有 NodeInstallPort 探针构造（node/npm 真值，其余待 Phase 2 补全）。 */
        suspend fun probe(port: com.apex.agent.core.tools.extension.NodeInstallPort): PluginEnvironmentSnapshot {
            val probe = port.probeRuntime()
            return PluginEnvironmentSnapshot(
                nodeVersion = probe.nodeVersion,
                npmVersion = probe.npmVersion
            )
        }
    }
}

/**
 * 兼容性分析报告 —— 安装前「这个插件在 Android 上能干什么」的权威答案。
 *
 * 三个维度逐项给结论（spec §10.1）：
 * 1. 生态 × 能力矩阵（[capabilityStatuses]）；
 * 2. 运行时需求（[runtimeVerdicts]）；
 * 3. 总评级（[level]）+ 不兼容原因（[reasons]）。
 */
data class PluginCompatibilityReport(
    val pluginId: String,
    val ecosystem: PluginEcosystem,
    val level: PluginCompatibilityLevel,
    val capabilityStatuses: List<EcosystemCapabilityStatus>,
    val runtimeVerdicts: List<RuntimeVerdict>,
    val reasons: List<String>
) {
    /** 人读摘要（inspect/市场 UI 共用，一段话讲清楚能用到什么程度）。 */
    fun summary(): String = buildString {
        append("[").append(level.name).append("] ").append(level.label).append(" — ")
        append(ecosystem.name).append(" 插件 '").append(pluginId).append("': ")
        val ok = capabilityStatuses.count { it.support == CapabilitySupport.SUPPORTED }
        val partial = capabilityStatuses.count { it.support == CapabilitySupport.PARTIAL }
        val wait = capabilityStatuses.count {
            it.support == CapabilitySupport.PLANNED || it.support == CapabilitySupport.UNKNOWN
        }
        append("$ok 项能力可用")
        if (partial > 0) append("、$partial 项部分可用")
        if (wait > 0) append("、$wait 项等待宿主实现")
        val unsupported = capabilityStatuses.count { it.support == CapabilitySupport.UNSUPPORTED }
        if (unsupported > 0) append("、$unsupported 项平台不支持")
        runtimeVerdicts.filter { !it.available }.forEach {
            append("；运行时缺失: ").append(it.runtime.wireName)
        }
    }
}

/**
 * # PluginCompatibilityAnalyzer —— 安装前兼容性分析引擎（Phase 1 核心）
 *
 * spec §10 的落地：**安装之前**自动分析插件能否在 Android 上运行、哪些
 * 能力可用、哪些依赖缺失 —— 用户不应该装完才发现插件根本不能用。
 *
 * 判定输入全部可注入（描述符 + 环境快照），纯函数无 IO —— 测试与
 * ExtensionInspect 工具（Phase 6 接线）共用同一份逻辑。
 */
object PluginCompatibilityAnalyzer {

    /** 核心能力槽位（决定评级主体）：工具 / 技能 / 指令。 */
    private val CORE_CAPABILITIES = setOf(
        PluginCapability.TOOLS, PluginCapability.SKILLS, PluginCapability.COMMANDS
    )

    fun analyze(
        plugin: PluginDescriptor,
        environment: PluginEnvironmentSnapshot
    ): PluginCompatibilityReport {
        // ── 1) 能力矩阵判定（声明为空 → 生态典型能力面兜底）──────────────
        val declared = plugin.declaredCapabilities.ifEmpty {
            PluginEcosystemMatrix.typicalCapabilities(plugin.ecosystem)
        }
        val statuses = declared.sorted().map { capability ->
            PluginEcosystemMatrix.statusOf(plugin.ecosystem, capability)
                ?: EcosystemCapabilityStatus(
                    capability, CapabilitySupport.UNKNOWN,
                    "${plugin.ecosystem.name} 生态典型格式不含 '${capability.name}' 能力槽位，按未审计处理"
                )
        }

        // ── 2) 运行时需求判定（描述符未声明 → 生态典型候选 → 宿主原生）────
        val required = plugin.runtimeRequirements.ifEmpty {
            PluginEcosystemMatrix.typicalRuntimes(plugin.ecosystem).ifEmpty {
                setOf(PluginRuntimeKind.NATIVE_KOTLIN)
            }
        }
        val verdicts = required.sorted().map { runtime -> verdictFor(runtime, environment) }
        val nonNative = verdicts.filter { it.runtime != PluginRuntimeKind.NATIVE_KOTLIN }
        val allNonNativeMissing = nonNative.isNotEmpty() && nonNative.all { !it.available }

        // ── 3) 评级（spec §10.2；「能安装」不参与评级）────────────────────
        val supported = statuses.count { it.support == CapabilitySupport.SUPPORTED }
        val partial = statuses.count { it.support == CapabilitySupport.PARTIAL }
        val waiting = statuses.count {
            it.support == CapabilitySupport.PLANNED || it.support == CapabilitySupport.UNKNOWN
        }
        val unsupported = statuses.count { it.support == CapabilitySupport.UNSUPPORTED }

        val reasons = mutableListOf<String>()
        statuses.filter { it.support != CapabilitySupport.SUPPORTED }.forEach {
            reasons += "${it.capability.name}: ${it.reason}" + (it.plannedPhase?.let { p -> "（Phase $p 落地）" } ?: "")
        }

        // 缺失但非全部缺失的运行时只记入原因（信息性降级说明），不封顶评级：
        // 生态层的多运行时候选是「任一可用即具备执行前提」，真正封顶的是
        // 全部缺失（allNonNativeMissing）与能力面判定。
        verdicts.filter { !it.available }.forEach {
            reasons += "运行时 ${it.runtime.wireName} 缺失: ${it.note}"
        }

        val level = when {
            unsupported > 0 && (supported + partial) == 0 -> {
                reasons += "全部声明能力均平台不支持 —— 当前 Android 无法运行"
                PluginCompatibilityLevel.F
            }
            allNonNativeMissing -> {
                reasons += "所需运行时全部缺失: " +
                    nonNative.filter { !it.available }.joinToString(", ") { it.runtime.wireName }
                PluginCompatibilityLevel.D
            }
            supported > 0 && partial == 0 && waiting == 0 -> PluginCompatibilityLevel.A
            supported > 0 -> PluginCompatibilityLevel.B
            partial > 0 -> PluginCompatibilityLevel.C
            else -> {
                reasons += "声明能力均未在宿主实现（PLANNED/UNKNOWN）—— 当前只能安装解析，无法真正运行"
                PluginCompatibilityLevel.D
            }
        }

        return PluginCompatibilityReport(
            pluginId = plugin.id,
            ecosystem = plugin.ecosystem,
            level = level,
            capabilityStatuses = statuses,
            runtimeVerdicts = verdicts,
            reasons = reasons
        )
    }

    private fun verdictFor(
        runtime: PluginRuntimeKind,
        env: PluginEnvironmentSnapshot
    ): RuntimeVerdict = when (runtime) {
        PluginRuntimeKind.NATIVE_KOTLIN ->
            RuntimeVerdict(runtime, true, "宿主原生 Kotlin，同进程执行")
        PluginRuntimeKind.NODE -> {
            val v = env.nodeVersion
            if (v != null) {
                RuntimeVerdict(runtime, true, "沙箱 node $v 可用")
            } else {
                RuntimeVerdict(runtime, false, "沙箱内未探测到 node（Ubuntu rootfs 未就绪或未安装）")
            }
        }
        PluginRuntimeKind.BUN -> {
            val v = env.bunVersion
            if (v != null) {
                RuntimeVerdict(runtime, true, "沙箱 bun $v 可用（ARM64 系统调用兼容性须实机复验）")
            } else {
                RuntimeVerdict(
                    runtime, false,
                    "未探测到 bun —— OpenCode 官方运行时；不可默认以 node 等价替代（spec 禁止事项 #3）"
                )
            }
        }
        PluginRuntimeKind.PYTHON -> {
            val v = env.pythonVersion
            if (v != null) {
                RuntimeVerdict(runtime, true, "沙箱 python $v 可用")
            } else {
                RuntimeVerdict(runtime, false, "未探测到 python3")
            }
        }
        PluginRuntimeKind.SHELL ->
            if (env.prootReady) {
                RuntimeVerdict(runtime, true, "PRoot 沙箱 shell 可用")
            } else {
                RuntimeVerdict(runtime, false, "PRoot Ubuntu rootfs 未就绪")
            }
        PluginRuntimeKind.MCP_PROCESS ->
            if (env.prootReady) {
                RuntimeVerdict(runtime, true, "沙箱 MCP STDIO 进程可用")
            } else {
                RuntimeVerdict(runtime, false, "PRoot Ubuntu rootfs 未就绪（STDIO MCP 依赖沙箱进程）")
            }
    }
}
