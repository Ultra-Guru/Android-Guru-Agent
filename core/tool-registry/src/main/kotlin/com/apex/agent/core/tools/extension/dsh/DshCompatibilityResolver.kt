package com.apex.agent.core.tools.extension.dsh

import com.apex.agent.core.tools.extension.NodeInstallPort

/**
 * # DshCompatibilityResolver —— DSH/Cordis/Node 版本兼容性判定（纯 JVM）
 *
 * dphmoblie 纪律（mobile-plugin-compat）：运行时依赖是一个整体 —— Node 与
 * DSH 依赖族版本必须协调，`@deepseek-ai/` 依赖族出现多物理副本会分裂 Cordis
 * 的 Symbol 身份。本判定器只做**版本面**的诚实分级；服务面（哪些 Cordis
 * service 宿主能提供）见 [SERVICE_SUPPORT] —— 数据源与 docs/dsh-compat.md
 * 钉定的验证结论同源，绝不在此虚报未验证的支持。
 *
 * 诚实约束：解析不了的版本串 = unknown（如实报告），**不 fail-open 成兼容**
 * —— 工具可用性最终由动态验证（observed）决定，本判定只影响级别展示与警告。
 */
class DshCompatibilityResolver {

    /** 宿主 shim 可提供的 Cordis 服务面（P3 逐项落地；slots 永不支持）。 */
    val serviceSupport: Map<String, ServiceLevel> = SERVICE_SUPPORT

    /**
     * 判定插件兼容性。
     *
     * @param declaredDshRange package.json 的 engines.dsh / peerDependencies 里
     *   @deepseek-ai/dsh 的版本范围（null = 未声明 —— 纯 Cordis 代码插件）
     * @param declaredNodeRange engines.node（null = 未声明）
     * @param probe 沙箱 node/npm 探针
     */
    fun resolve(
        declaredDshRange: String?,
        declaredNodeRange: String?,
        probe: NodeInstallPort.RuntimeProbe
    ): Verdict {
        val notes = mutableListOf<String>()

        // ── Node 版本门（ESM dynamic import / structuredClone 需要 ≥18）──
        val nodeMajor = probe.nodeVersion?.let { nodeMajorOf(it) }
        if (nodeMajor == null) {
            notes.add("沙箱 node 不可解析（TerminalLinuxBootstrap 先装齐 rootfs）")
            return Verdict(DshCapabilities.Compatibility.Level.INCOMPATIBLE, notes.toList(), emptyList())
        }
        if (nodeMajor < MIN_NODE_MAJOR) {
            notes.add("node ${probe.nodeVersion} 低于宿主要求 ≥$MIN_NODE_MAJOR（ESM/structuredClone 依赖）")
            return Verdict(DshCapabilities.Compatibility.Level.INCOMPATIBLE, notes.toList(), emptyList())
        }
        if (declaredNodeRange != null) {
            val declaredMajor = declaredNodeRange.majorOrCaretFloor()
            if (declaredMajor != null && nodeMajor < declaredMajor) {
                notes.add("插件要求 node $declaredNodeRange，沙箱为 ${probe.nodeVersion}")
                return Verdict(DshCapabilities.Compatibility.Level.INCOMPATIBLE, notes.toList(), emptyList())
            }
        }

        // ── DSH 依赖族版本面 ──
        val unsupported = SERVICE_SUPPORT.filterValues { it == ServiceLevel.UNSUPPORTED }.keys.toList()
        val level = when {
            declaredDshRange == null -> {
                notes.add("未声明 DSH 依赖（engines.dsh / peerDependencies 均无）—— 按 Cordis 代码插件处理")
                DshCapabilities.Compatibility.Level.PARTIAL
            }
            rangeMatchesPinned(declaredDshRange) -> {
                notes.add("DSH 依赖 $declaredDshRange 在已验证范围（pinned ${PINNED_DSH_RANGE}）")
                DshCapabilities.Compatibility.Level.PARTIAL
            }
            else -> {
                notes.add("DSH 依赖 $declaredDshRange 超出已验证范围 ${PINNED_DSH_RANGE} —— 能力以动态验证为准，谨慎使用")
                DshCapabilities.Compatibility.Level.MINIMAL
            }
        }
        // full 永不出现：DSH preview 阶段宿主不可能提供全部服务（agents/sessions/slots 缺位）
        notes.add("预览阶段兼容上限为 partial（agents/sessions/slots 宿主不提供）")
        return Verdict(level, notes.toList(), unsupported)
    }

    /** 版本范围匹配已验证区间（caret/tilde/精确/x-range 的保守实现）。 */
    internal fun rangeMatchesPinned(range: String): Boolean {
        val normalized = range.trim().removePrefix("^").removePrefix("~")
        if (normalized.startsWith("*") || normalized == "latest" || normalized == "next") return false
        val floor = normalized.majorOrCaretFloor() ?: return false
        val floorMinor = normalized.minorOrZero()
        val pinnedFloor = PINNED_DSH_RANGE.majorOrCaretFloor() ?: return false
        val pinnedMinor = PINNED_DSH_RANGE.minorOrZero()
        // 同主版本且插件 floor 不高于已验证 floor 的次版本 → 视为已验证范围
        return floor == pinnedFloor && floorMinor <= pinnedMinor
    }

    private fun nodeMajorOf(version: String): Int? =
        version.trim().removePrefix("v").substringBefore('.').filter { it.isDigit() }.toIntOrNull()

    private fun String.majorOrCaretFloor(): Int? =
        substringBefore('.').filter { it.isDigit() }.toIntOrNull()

    private fun String.minorOrZero(): Int =
        substringAfter('.', "").substringBefore('.').filter { it.isDigit() }.toIntOrNull()?.takeIf { it >= 0 } ?: 0

    /** 服务支持等级（市场/能力图展示用，与 unavailable 原因一一对应）。 */
    enum class ServiceLevel { SUPPORTED, STATIC_ONLY, PLANNED, UNSUPPORTED }

    data class Verdict(
        val level: String,
        val notes: List<String>,
        val unsupportedServices: List<String>
    )

    companion object {
        /**
         * P0 真机验证钉定值（device 验证后由 docs/dsh-compat.md 同步更新）。
         * 当前为文献级预填 —— **首次真机 P0 通过前，MINIMAL 级别是诚实默认**。
         */
        const val PINNED_DSH_RANGE = "0.x"
        const val MIN_NODE_MAJOR = 18

        /**
         * 宿主服务支持层级（brief 修正七的白名单落点）：
         * - tools：完整支持（注册面捕获 + 调用桥）；
         * - logger/config：Worker 侧基础实现；
         * - storage：命名空间隔离的宿主网关（P3）；
         * - skills：仅静态文件转换，动态 Provider 不支持；
         * - llm：评估接入宿主模型路由（P3+）；
         * - agents/sessions：后续实现（现如实不可用）；
         * - slots：DSH Client UI 专面，永不支持（红线 1）。
         */
        val SERVICE_SUPPORT: Map<String, ServiceLevel> = mapOf(
            "tools" to ServiceLevel.SUPPORTED,
            "logger" to ServiceLevel.SUPPORTED,
            "config" to ServiceLevel.SUPPORTED,
            "storage" to ServiceLevel.PLANNED,
            "skills" to ServiceLevel.STATIC_ONLY,
            "llm" to ServiceLevel.PLANNED,
            "agents" to ServiceLevel.UNSUPPORTED,
            "sessions" to ServiceLevel.UNSUPPORTED,
            "slots" to ServiceLevel.UNSUPPORTED
        )
    }
}
