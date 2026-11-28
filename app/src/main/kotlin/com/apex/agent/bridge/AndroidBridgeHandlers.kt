package com.apex.agent.bridge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import com.apex.agent.core.tools.device.DeviceOpsPort
import com.apex.agent.platform.TerminalBridge.GuestBridgeHandler
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * T82 — apexctl 桥的 Android 侧 handler 集（Termux:API 等价物的宿主实现）。
 *
 * guest 内用法（安装 rootfs 后自动可用，`/root/.apex/bin/apexctl`）：
 * ```
 * apexctl clipboard get
 * apexctl clipboard set '"hello from ubuntu"'
 * apexctl device-info
 * apexctl battery
 * apexctl device '{"intent":"package.list"}'
 * ```
 *
 * Agent 侧同一 handler 集经 `TerminalBridge call` 直连（无文件往返）。
 *
 * 诚实语义：ClipboardManager / BatteryManager 拿不到（极端 ROM 限制）→
 * 抛异常 → 响应 ok:false + error（绝不编造数据）。
 */
object AndroidBridgeHandlers {

    private class ClipboardGet(private val context: Context) : GuestBridgeHandler {
        override val action = "clipboard"
        override val description = "get/set Android clipboard. args: 'get' | 'set \"<text>\"'"

        override suspend fun handle(argsJson: String): String {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: throw IllegalStateException("clipboard service unavailable")
            val arg = argsJson.trim().removeSurrounding("\"").trim()
            if (arg.equals("get", ignoreCase = true)) {
                return cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
                    ?: ""   // 空剪贴板 = 空串（语义清晰，非错误）
            }
            if (arg.startsWith("set", ignoreCase = true)) {
                val text = argsJson.trim()
                    .removePrefix("set")
                    .trim()
                    .removeSurrounding("\"")
                cm.setPrimaryClip(ClipData.newPlainText("apexctl", text))
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
     * S09 —— DCP 设备操作的 guest 侧对称入口：`apexctl device <DeviceOps-JSON>`。
     *
     * argsJson **就是** DeviceOps 的入参 JSON（intent / params / dry_run /
     * confirm_token），不另造协议；guest 侧与 Agent 侧走**同一个**
     * [DeviceOpsPort]（同一 preflight / gate / 审计）—— 人工确认同样适用
     * （不可逆意图同样必须 dry_run → token → 执行，guest 里没有免确认通道）。
     */
    internal class Device(private val port: DeviceOpsPort) : GuestBridgeHandler {
        override val action = "device"
        override val description =
            "Structured device ops (package.* / file.* / system.* / probe.*). " +
                "args: DeviceOps JSON, e.g. '{\"intent\":\"package.list\"}' or '{\"intent\":\"package.uninstall_for_user\",\"params\":{\"package\":\"x\"},\"dry_run\":true}'"

        override suspend fun handle(argsJson: String): String {
            val trimmed = argsJson.trim().removeSurrounding("\"")
            if (trimmed.equals("status", ignoreCase = true) || trimmed.isEmpty()) {
                // 档位摘要：probe.id 是只读意图，零副作用（与 DeviceCapability 同源通道）。
                val r = port.execute("probe.id", emptyMap(), dryRun = false, token = null)
                return r.toJson()
            }
            val json = try {
                Json.parseToJsonElement(trimmed).jsonObject
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                throw IllegalArgumentException("args 必须是 DeviceOps JSON（intent/params/dry_run/confirm_token）：${e.message}")
            }
            val intentId = (json["intent"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw IllegalArgumentException("缺少 'intent' 字段")
            val params = ((json["params"] as? JsonObject) ?: JsonObject(emptyMap()))
                .mapValues { (_, v) ->
                    (v as? JsonPrimitive)?.content
                        ?: throw IllegalArgumentException("params 值必须是字符串")
                }
            val dryRun = (json["dry_run"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false
            val token = (json["confirm_token"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val result = port.execute(intentId, params, dryRun, token)
            return result.toJson()
        }
    }

    /**
     * 注册集（TerminalModule 注入 GuestBridgeService）。
     * S09 起第 4 个 handler：`device`（DCP 结构化设备操作）。
     */
    fun all(context: Context, port: DeviceOpsPort): List<GuestBridgeHandler> = listOf(
        ClipboardGet(context),
        DeviceInfo(context),
        Battery(context),
        deviceBridge(port)
    )

    /** device handler 的独立工厂（注册与 JVM 测试共用 —— 不需 Context）。 */
    fun deviceBridge(port: DeviceOpsPort): GuestBridgeHandler = Device(port)
}
