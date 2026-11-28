package com.apex.agent.tools

import com.apex.agent.core.tools.device.CapabilityProbe
import com.apex.agent.core.tools.device.DeviceCapabilitySnapshot
import com.apex.agent.core.tools.device.DevicePrivilege
import com.apex.agent.core.tools.device.IntentCatalog
import com.apex.agent.platform.privilege.PrivilegeDetector
import com.apex.agent.platform.privilege.PrivilegeLevel
import com.apex.agent.platform.privilege.shizuku.DeviceChannel
import com.apex.agent.platform.privilege.shizuku.DeviceExecRequest
import com.apex.agent.platform.privilege.shizuku.ShizukuCommandExecutor
import kotlinx.coroutines.runBlocking

/**
 * ═══ DCP 能力自省探针（S01）═══
 *
 * 「先自省再执行」：LLM 在执行任何设备意图前，先经 DeviceCapability 工具
 * 拿到本快照 —— 当前档位（ROOT / SHIZUKU / NORMAL_SHELL）、uid、binder
 * 存活与授权态、supports（当前档位满足 requiredPrivilege 的意图）与
 * denies（档位不足 + **可执行的开通步骤**）。
 *
 * 档位判定真值口径沿用 [PrivilegeDetector.getPrivilegeLevel]（`su -c id`
 * 验 uid=0 的真探测 + 30s 缓存）；binder 死亡 / 授权被撤时主动
 * [PrivilegeDetector.invalidateCache]，避免 30s 缓存误报可用。
 *
 * uid 判定诚实优先：SHIZUKU 档尝试经通道跑 `id` 解析真实 uid=2000；
 * 探测失败如实回 null（不编造 2000）。ROOT 档报 0（su -c id 已验 uid=0）。
 */
class DeviceCapabilityProbe(
    private val catalog: IntentCatalog,
    private val channel: DeviceChannel,
    private val selfUid: () -> Int,
    private val shizukuVersion: () -> Int? = { null },
    private val now: () -> Long = System::currentTimeMillis
) : CapabilityProbe {

    override fun snapshot(): DeviceCapabilitySnapshot {
        val binderAlive = runCatching { ShizukuCommandExecutor.isAvailable() }.getOrDefault(false)
        val granted = binderAlive && runCatching { ShizukuCommandExecutor.hasPermission() }.getOrDefault(false)
        val level = runCatching { PrivilegeDetector.getPrivilegeLevel() }
            .getOrElse { PrivilegeLevel.NORMAL_SHELL }
        if (binderAlive && !granted) {
            // binder 活着但授权没了：缓存里的 SHIZUKU 可能已失真，主动失效。
            runCatching { PrivilegeDetector.invalidateCache() }
        }
        val privilege = when (level) {
            PrivilegeLevel.ROOT -> DevicePrivilege.ROOT
            PrivilegeLevel.SHIZUKU -> DevicePrivilege.SHIZUKU
            PrivilegeLevel.NORMAL_SHELL -> DevicePrivilege.NORMAL_SHELL
        }
        val uid = detectUid(privilege)
        return DeviceCapabilitySnapshot(
            privilege = privilege,
            uid = uid,
            shizukuBinderAlive = binderAlive,
            shizukuGranted = granted,
            shizukuApiLevel = runCatching { shizukuVersion() }.getOrNull(),
            supports = catalog.supportsFor(privilege).toSet(),
            denies = catalog.deniesFor(privilege),
            probedAtMillis = now()
        )
    }

    /** 诚实 uid：SHIZUKU 档经通道探 `id`（uid=2000）；探测失败报 null。 */
    private fun detectUid(privilege: DevicePrivilege): Int? = when (privilege) {
        DevicePrivilege.ROOT -> 0
        DevicePrivilege.SHIZUKU -> runBlocking {
            val r = runCatching {
                channel.execute(DeviceExecRequest(argv = arrayOf("id"), timeoutMs = 5_000L))
            }.getOrNull()
            r?.takeIf { it.ok }?.stdout?.let { parseUid(it) }
        }
        DevicePrivilege.NORMAL_SHELL -> runCatching { selfUid() }.getOrNull()
    }

    private fun parseUid(idOutput: String): Int? {
        val m = UID_LINE.find(idOutput) ?: return null
        return m.groupValues[1].toIntOrNull()
    }

    private companion object {
        /** `uid=2000(shell)` 形态 —— 只取第一行第一个 uid= 数字。 */
        private val UID_LINE = Regex("uid=(\\d+)")
    }
}
