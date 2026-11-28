package com.apex.agent.bridge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import com.apex.agent.core.tools.builtin.ClipboardBridgeGate
import com.apex.agent.platform.TerminalBridge.BridgeAccessRefused
import com.apex.agent.platform.TerminalBridge.GuestBridgeHandler

/**
 * T82 — apexctl 桥的 Android 侧 handler 集（Termux:API 等价物的宿主实现）。
 *
 * guest 内用法（安装 rootfs 后自动可用，`/root/.apex/bin/apexctl`）：
 * ```
 * apexctl clipboard get
 * apexctl clipboard set '"hello from ubuntu"'
 * apexctl device-info
 * apexctl battery
 * ```
 *
 * Agent 侧同一 handler 集经 `TerminalBridge call` 直连（无文件往返）。
 *
 * 诚实语义：ClipboardManager / BatteryManager 拿不到（极端 ROM 限制）→
 * 抛异常 → 响应 ok:false + error（绝不编造数据）。
 *
 * #854 安全默认：剪贴板桥**默认关闭**（`AgentSettings.clipboardBridgeEnabled`，
 * 默认 false）——LLM 工具（TerminalBridge call clipboard）与 guest 内任意
 * 进程（`apexctl clipboard`，含 npm/pip 钩子等）共用同一道门：关闭时 get/set
 * 一律结构化拒绝（[BridgeAccessRefused] → ok:false + error + hint），密码
 * 管理器 OTP/收码不再零门槛直入模型上下文，set 静默投毒通道同步关闭。
 */
object AndroidBridgeHandlers {

    /**
     * #854 剪贴板 handler（依赖全 lambda 注入：门禁 + getter/setter —— 纯 JVM 可单测，
     * 与 VaultAgentTools 同款分层；Android 侧 ClipboardManager 装配见 [all]）。
     *
     * 门禁先于一切（fail-closed）：开关关闭时**不触碰**剪贴板服务，直接
     * 结构化拒绝（稳定错误码 + 引导文案，单一真源见 [ClipboardBridgeGate]）。
     */
    internal fun clipboardHandler(
        enabled: () -> Boolean,
        textGetter: () -> String?,
        textSetter: (String) -> Unit
    ): GuestBridgeHandler = ClipboardBridge(enabled, textGetter, textSetter)

    private class ClipboardBridge(
        private val enabled: () -> Boolean,
        private val textGetter: () -> String?,
        private val textSetter: (String) -> Unit
    ) : GuestBridgeHandler {
        override val action = "clipboard"
        // 描述即输出（status 列出）—— 默认关与溯源都写给调用方：
        // get 返回的是**逐字剪贴板内容**（可能是密码管理器刚复制的 OTP）。
        override val description =
            "get/set Android clipboard (OFF by default; enable in Settings, or use VaultSave/VaultPaste). " +
                "args: 'get' | 'set \"<text>\"' — get returns verbatim clipboard content"

        override suspend fun handle(argsJson: String): String {
            if (!enabled()) {
                throw BridgeAccessRefused(
                    code = ClipboardBridgeGate.DISABLED_ERROR,
                    hint = ClipboardBridgeGate.DISABLED_HINT
                )
            }
            val arg = argsJson.trim().removeSurrounding("\"").trim()
            if (arg.equals("get", ignoreCase = true)) {
                return textGetter()
                    ?: ""   // 空剪贴板 = 空串（语义清晰，非错误）
            }
            if (arg.startsWith("set", ignoreCase = true)) {
                val text = argsJson.trim()
                    .removePrefix("set")
                    .trim()
                    .removeSurrounding("\"")
                textSetter(text)
                return "ok"
            }
            throw IllegalArgumentException("usage: apexctl clipboard get | apexctl clipboard set \"<text>\"")
        }
    }

    private class DeviceInfo(private val context: Context) : GuestBridgeHandler {
        override val action = "device-info"
        override val description = "Android device info (model/brand/SDK/abi) as one-line text"

        override suspend fun handle(argsJson: String): String =
            "model=${Build.MODEL} brand=${Build.BRAND} manufacturer=${Build.MANUFACTURER} " +
                "android=${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) " +
                "abi=${Build.SUPPORTED_ABIS.firstOrNull() ?: "?"} " +
                "pkg=${context.packageName}"
    }

    private class Battery(private val context: Context) : GuestBridgeHandler {
        override val action = "battery"
        override val description = "battery status (termux-battery-status equivalent, one-line)"

        override suspend fun handle(argsJson: String): String {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                ?: throw IllegalStateException("battery service unavailable")
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val statusInt = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
            val status = when (statusInt) {
                BatteryManager.BATTERY_STATUS_CHARGING -> "CHARGING"
                BatteryManager.BATTERY_STATUS_FULL -> "FULL"
                BatteryManager.BATTERY_STATUS_DISCHARGING -> "DISCHARGING"
                BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "NOT_CHARGING"
                else -> "UNKNOWN($statusInt)"
            }
            return "level=$level% status=$status"
        }
    }

    /**
     * 注册集（TerminalModule 注入 GuestBridgeService）。
     *
     * @param clipboardBridgeEnabled #854 门禁（实时读设置，非构造期快照 ——
     *   设置页开关切换后无需重启即生效）。
     */
    fun all(
        context: Context,
        clipboardBridgeEnabled: () -> Boolean = { false }
    ): List<GuestBridgeHandler> = listOf(
        clipboardHandler(
            enabled = clipboardBridgeEnabled,
            textGetter = {
                val cm = clipboardManager(context)
                    ?: throw IllegalStateException("clipboard service unavailable")
                cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
            },
            textSetter = { text ->
                val cm = clipboardManager(context)
                    ?: throw IllegalStateException("clipboard service unavailable")
                cm.setPrimaryClip(ClipData.newPlainText("apexctl", text))
            }
        ),
        DeviceInfo(context),
        Battery(context)
    )

    private fun clipboardManager(context: Context): ClipboardManager? =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
}
