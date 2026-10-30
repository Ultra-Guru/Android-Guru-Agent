package com.apex.agent.platform.jiguan

import com.apex.agent.core.jiguan.JiguanSystemPort
import com.apex.agent.core.jiguan.SystemInspectResult
import com.apex.agent.platform.privilege.PrivilegeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * # 系统域宿主适配器
 *
 * - **inspect**：getenforce（SELinux 模式）+ `id -Z` 或 `ls -Z /proc/self`
 *   （自身上下文）+ service list 样例（Binder 面可见性）—— 全部经
 *   [PrivilegeManager.executeShell]（Root > Shizuku 自动择优）；
 * - **injectTouch**：Root `input tap/swipe`（物理输入事件 —— 比无障碍
 *   通道更隐蔽；无障碍回落由既有 UiTap/UiSwipe 工具承担，本端口不重复）。
 */
class SystemDomainAdapter(
    private val privilege: PrivilegeManager,
) : JiguanSystemPort {

    override val channelName: String = "root-input"

    override suspend fun ping(): Boolean =
        privilege.rootAvailable.value || privilege.shizukuAvailable.value

    override suspend fun inspect(): Result<SystemInspectResult> = withContext(Dispatchers.IO) {
        val mode = privilege.executeShell("getenforce", 8_000)
            .takeIf { it.success }?.output?.trim()?.lineSequence()?.firstOrNull()
            ?: "unknown"
        val context = privilege.executeShell("id -Z", 8_000)
            .takeIf { it.success }?.output?.trim()?.lineSequence()?.firstOrNull()
            ?: privilege.executeShell("ls -Zd /proc/self", 8_000)
                .takeIf { it.success }?.output?.trim()?.split(Regex("\\s+"))?.firstOrNull()
            ?: "unknown"
        // service list 行形如 "12\tandroid.os.IAlarmManager: [...]" —— 跳过表头。
        val services = privilege.executeShell("service list", 10_000)
            .takeIf { it.success }?.output?.lineSequence()
            ?.filter { Regex("^\\d+\\s").containsMatchIn(it) }
            ?.map { it.substringAfter('\t', it).substringBefore(':').trim() }
            ?.filter { it.isNotBlank() }
            ?.take(30)
            ?.toList()
            ?: emptyList()
        Result.success(
            SystemInspectResult(
                selinuxMode = mode,
                selfContext = context,
                rootAvailable = privilege.rootAvailable.value,
                serviceSample = services,
            ),
        )
    }

    override suspend fun injectTouch(
        x: Int,
        y: Int,
        swipe: Boolean,
        x2: Int,
        y2: Int,
        durationMs: Long,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val command = if (swipe) {
            "input swipe $x $y $x2 $y2 $durationMs"
        } else {
            "input tap $x $y"
        }
        val result = privilege.executeShell(command, 8_000)
        if (result.success) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("root input failed (via ${result.executedVia}): ${result.output.take(160)}"))
        }
    }
}
