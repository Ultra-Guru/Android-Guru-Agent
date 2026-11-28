package com.apex.agent.tools

import com.apex.agent.core.tools.device.DeviceIntent
import com.apex.agent.platform.privilege.shizuku.DeviceChannel
import com.apex.agent.platform.privilege.shizuku.DeviceExecRequest
import com.apex.agent.platform.privilege.shizuku.DeviceExecResult

/**
 * ═══ DCP 包操作执行器（S05）═══
 *
 * package.* 意图的执行面。argv 已由 DeviceOpsExecutor 渲染（包名经
 * `^[A-Za-z0-9._]+$` 白名单 + argv 元素直传，不经 shell）。
 *
 * S05 双保险：S04 的 preflight 守卫（IS_SELF / DEVICE_ADMIN / IS_LAUNCHER
 * 硬拒）之后，本层在执行前**再做一次防御性 IS_SELF 检查** —— 守卫数据来自
 * dumpsys 解析（允许 parseIncomplete），最后一道防线不依赖解析结论。
 */
class PackageOpsRunner(
    private val channel: DeviceChannel,
    private val selfPackage: String
) {

    /**
     * 执行 package.* 意图（argv 已渲染并过守卫）。
     *
     * @return 通道执行结果；防御性检查不过时返回结构化 GUARD_REJECTED。
     */
    suspend fun run(
        intent: DeviceIntent,
        params: Map<String, String>,
        argv: List<String>
    ): DeviceExecResult {
        // 双保险（I3）：目标包 == 本应用包名 → 硬拒（不依赖 dumpsys 解析）。
        val target = params["package"]
        if (target != null && target == selfPackage) {
            return DeviceExecResult(
                ok = false,
                error = "GUARD_REJECTED",
                hint = "目标是本应用自身（$selfPackage），已防御性硬拒；卸载/停用自己会让 App 当场消失"
            )
        }
        return channel.execute(
            DeviceExecRequest(
                argv = argv.toTypedArray(),
                timeoutMs = EXEC_TIMEOUT_MS
            )
        )
    }

    private companion object {
        const val EXEC_TIMEOUT_MS = 30_000L
    }
}
