package com.apex.agent.tools

import android.content.Context
import com.apex.agent.notify.ApexNotifications
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `NotifySend` —— 系统通知栏推送（方向③ Agent 全局生产力工具）
 *
 * 长任务收尾 / 后台完成 / 用户离开屏幕时的事件提醒。经
 * [ApexNotifications.notifyGeneral]（通用渠道）发射 —— 通知基建、渠道与
 * 权限边界同宿主既有通知。
 *
 * **Android 13+ 权限现实（诚实边界）**：POST_NOTIFICATIONS 是运行时权限，
 * 用户未授权时系统会**静默丢弃**通知 —— 本工具执行前以
 * [ApexNotifications.canPost] 探测，未授权时如实返回
 * `notifications_disabled`（而非假装成功），模型可引导用户去系统设置开启。
 */
class NotifySendTool(
    private val apexNotifications: com.apex.agent.notify.ApexNotifications,
    private val context: Context
) : com.apex.agent.core.tools.AgentTool {

    override val id = "NotifySend"
    override val name = "Send Notification"
    override val description = """
        Post a system notification (title + text) — useful when a long task
        finishes while the user is away, or to surface a completed build/test
        result on the notification shade.

        Honesty note: on Android 13+ notifications are a runtime permission.
        If the user has not granted POST_NOTIFICATIONS, the system silently
        drops notifications — this tool checks that first and returns
        {"ok": false, "error": "notifications_disabled"} so you can tell the
        user to enable notifications in system settings instead of assuming
        delivery.

        Examples:
        - {"title": "构建完成", "text": "app-debug.apk 已生成，用时 3 分 12 秒"}
    """.trimIndent()

    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "title": {"type": "string", "description": "Notification title"},
                "text": {"type": "string", "description": "Notification body text"}
            },
            "required": ["title", "text"]
        }
    """.trimIndent()

    override suspend fun execute(arguments: String): String {
        val json = runCatching { Json.parseToJsonElement(arguments).jsonObject }.getOrElse {
            return """{"ok": false, "error": "invalid_arguments"}"""
        }
        val title = json["title"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
            ?: return """{"ok": false, "error": "title required"}"""
        val text = json["text"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() } ?: ""

        // Android 13+ 运行时权限：未授权 = 系统静默丢弃 —— 如实上报而非假装成功
        if (!apexNotifications.canPost(context)) {
            return """{"ok": false, "error": "notifications_disabled", "hint": "引导用户到系统设置开启本应用的通知权限"}"""
        }
        return runCatching {
            apexNotifications.notifyGeneral(context, title, text)
            """{"ok": true}"""
        }.getOrElse {
            """{"ok": false, "error": "${it.message?.replace("\"", "'") ?: "notify failed"}"}"""
        }
    }
}
