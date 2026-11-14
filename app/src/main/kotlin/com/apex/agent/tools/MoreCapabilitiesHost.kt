package com.apex.agent.tools

import android.Manifest
import android.provider.AlarmClock
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import com.apex.agent.core.tools.builtin.AlarmSetTool
import com.apex.agent.core.tools.builtin.CalendarEventListTool
import com.apex.agent.core.tools.builtin.CalendarEventRecord
import com.apex.agent.core.tools.builtin.ContactRecord
import com.apex.agent.core.tools.builtin.ContactSearchTool
import com.apex.agent.core.tools.builtin.FilePickTool
import com.apex.agent.core.tools.builtin.ImageCaptureTool
import com.apex.agent.core.tools.builtin.NotificationPostTool
import com.apex.agent.core.tools.builtin.SmsSendTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 高级能力工具的 Android 实现（#172 之后新增的一批设备工具）。
 *
 * - [searchContacts]：经 [ContactsContract] 检索通讯录
 * - [sendSms]：经 [SmsManager] 发送短信
 * - [listCalendarEvents]：经 [CalendarContract] 读取近 N 天事件
 * - [setAlarm]：经 [AlarmClock.ACTION_SET_ALARM] Intent 启动系统闹钟
 * - [pickFile]：经 SAF (ACTION_OPEN_DOCUMENT) 让用户选文件
 * - [captureImage]：经 [android.provider.MediaStore.ACTION_IMAGE_CAPTURE] 启动相机
 * - [postNotification]：经 [NotificationManager] 发系统通知
 *
 * 所有方法都是 suspend —— 在 [Dispatchers.IO] 上运行。
 */

/**
 * 通讯录检索——按姓名/电话/邮箱模糊匹配。
 *
 * 实现：先在 Contacts 表用 display_name LIKE %query% 取 ID 列表（LIMIT N），
 * 再对每个 ID 去 Data 表补 phone / email / organization。
 */
class ContactsReader(private val context: Context) {
    suspend fun search(query: String, limit: Int): List<ContactRecord> =
        withContext(Dispatchers.IO) {
            if (!hasPermission(Manifest.permission.READ_CONTACTS)) {
                return@withContext emptyList()
            }
            try {
                val resolver = context.contentResolver
                val pattern = "%${query.replace("%", "\\%").replace("_", "\\_")}%"
                // 第一步：在 Contacts 表用名字模糊匹配取 ID
                val contactIds = mutableListOf<Long>()
                resolver.query(
                    ContactsContract.Contacts.CONTENT_URI,
                    arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME),
                    "${ContactsContract.Contacts.DISPLAY_NAME} LIKE ? ESCAPE '\\'",
                    arrayOf(pattern),
                    "${ContactsContract.Contacts.DISPLAY_NAME} COLLATE LOCALIZED ASC LIMIT $limit"
                )?.use { c ->
                    val idIdx = c.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
                    while (c.moveToNext()) {
                        contactIds.add(c.getLong(idIdx))
                    }
                }
                // 第二步：电话号码也匹配（CALLER_ID 等不暴露，用 Phone 表 LIKE）
                if (contactIds.size < limit) {
                    resolver.query(
                        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        arrayOf(ContactsContract.CommonDataKinds.Phone.CONTACT_ID),
                        "${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ? ESCAPE '\\'",
                        arrayOf("%$query%"),
                        null
                    )?.use { c ->
                        val idIdx = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                        while (c.moveToNext() && contactIds.size < limit) {
                            val id = c.getLong(idIdx)
                            if (id !in contactIds) contactIds.add(id)
                        }
                    }
                }
                // 第三步：对每个 ID 查详细数据
                contactIds.mapNotNull { id -> loadContact(resolver, id) }
            } catch (e: Exception) {
                AppLogger.instance.warn(LogCategory.PLUGIN, "ContactsReader", "search failed: ${e.message}")
                emptyList()
            }
        }

    /** 加载单个联系人的详细数据（姓名 / 电话 / 邮箱 / 组织）。 */
    private fun loadContact(resolver: ContentResolver, contactId: Long): ContactRecord? {
        val displayName = mutableListOf<String>()
        val phones = mutableListOf<String>()
        val emails = mutableListOf<String>()
        val orgs = mutableListOf<String>()

        // 1. Contacts 表取 display_name
        resolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts.DISPLAY_NAME),
            "${ContactsContract.Contacts._ID} = ?",
            arrayOf(contactId.toString()),
            null
        )?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME)
                displayName.add(c.getString(idx) ?: "")
            }
        }

        // 2. Data 表一次性查所有 mime type
        val selection = "${ContactsContract.Data.CONTACT_ID} = ?"
        val args = arrayOf(contactId.toString())
        resolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(
                ContactsContract.Data.MIMETYPE,
                ContactsContract.Data.DATA1,
                ContactsContract.Data.DATA2
            ),
            selection, args, null
        )?.use { c ->
            val mimeIdx = c.getColumnIndexOrThrow(ContactsContract.Data.MIMETYPE)
            val data1Idx = c.getColumnIndexOrThrow(ContactsContract.Data.DATA1)
            while (c.moveToNext()) {
                val mime = c.getString(mimeIdx)
                val data1 = c.getString(data1Idx) ?: ""
                when (mime) {
                    ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE -> phones.add(data1)
                    ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE -> emails.add(data1)
                    ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE -> orgs.add(data1)
                }
            }
        }

        if (displayName.isEmpty() && phones.isEmpty() && emails.isEmpty()) return null
        return ContactRecord(
            id = contactId.toString(),
            displayName = displayName.firstOrNull() ?: "",
            phoneNumbers = phones.distinct(),
            emails = emails.distinct(),
            organization = orgs.firstOrNull() ?: ""
        )
    }

    private fun hasPermission(perm: String): Boolean =
        ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
}

/**
 * SMS 发送器。
 *
 * #342 限流：实际发送前过 [SmsRateLimiter] 双闸门——每分钟最多 5 条 +
 * 相同内容（同收件人+同正文）10 分钟内不重复；被拒时返回失败结果，
 * 错误文案含限额事实，模型能据此调整任务而不是盲目重试。
 *
 * 注意：dual-SIM slot 路由当前**不实现**——
 * - 通过反射调用 `TelephonyManager.getSubscriptionId(slot)` 违反项目代码规范
 *   （GATE 1 禁止反射方法分发，应改用类型化接口）；
 * - 官方 API `SubscriptionManager.getActiveSubscriptionInfoForSimSlotIndex()`
 *   需要 `READ_PHONE_STATE` 危险权限，比 dual-SIM 选择功能本身更敏感；
 * - 默认 SIM 由用户在系统设置中指定即可。
 *
 * 如用户传入 `slot_id`，本实现记录警告但**不使用**——通过默认 SIM 发送。
 */
class SmsSender(
    private val context: Context,
    // #342：默认 5 条/分钟 + 相同内容 10 分钟去重；测试可注入假钟实例。
    private val rateLimiter: SmsRateLimiter = SmsRateLimiter()
) {
    suspend fun send(phone: String, message: String, slotId: Int?): SmsSendTool.SendResult =
        withContext(Dispatchers.IO) {
            if (!hasPermission(Manifest.permission.SEND_SMS)) {
                return@withContext SmsSendTool.SendResult(
                    success = false,
                    message = "SEND_SMS permission not granted"
                )
            }
            if (slotId != null) {
                AppLogger.instance.info(
                    LogCategory.PLUGIN, "SmsSender",
                    "slot_id=$slotId ignored — dual-SIM routing requires READ_PHONE_STATE; " +
                        "using default subscription set in system settings"
                )
            }
            // #342：实际发送前过限流器（滑动窗口 + 重复内容去重）——
            // 会话级放行 / 失控循环下也无法无限连发付费短信。
            val verdict = rateLimiter.tryAcquire(phone, message)
            if (verdict is SmsRateLimiter.Result.Denied) {
                AppLogger.instance.warn(
                    LogCategory.PLUGIN, "SmsSender",
                    "sms blocked by rate limiter: phone=$phone, reason=${verdict.reason}"
                )
                return@withContext SmsSendTool.SendResult(
                    success = false,
                    message = "短信发送被限流：${verdict.reason}。请稍后再试或调整任务。"
                )
            }
            try {
                val sm: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    context.getSystemService(SmsManager::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    SmsManager.getDefault()
                }
                // 长短信自动分段
                val parts = sm.divideMessage(message)
                if (parts.size == 1) {
                    sm.sendTextMessage(phone, null, message, null, null)
                } else {
                    sm.sendMultipartTextMessage(phone, null, parts, null, null)
                }
                SmsSendTool.SendResult(
                    success = true,
                    message = "sent to $phone (${parts.size} segment${if (parts.size > 1) "s" else ""})"
                )
            } catch (e: Exception) {
                SmsSendTool.SendResult(
                    success = false,
                    message = "${e::class.simpleName ?: "exception"}: ${e.message ?: "no message"}"
                )
            }
        }

    private fun hasPermission(perm: String): Boolean =
        ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
}

/**
 * 日历事件读取器——读当前账号下从 now 到 now+daysAhead 天的事件。
 */
class CalendarReader(private val context: Context) {
    suspend fun list(daysAhead: Int, limit: Int): List<CalendarEventRecord> =
        withContext(Dispatchers.IO) {
            if (!hasPermission(Manifest.permission.READ_CALENDAR)) {
                return@withContext emptyList()
            }
            try {
                val nowMs = System.currentTimeMillis()
                val endMs = nowMs + daysAhead.toLong() * 24 * 60 * 60 * 1000
                val projection = arrayOf(
                    CalendarContract.Events._ID,
                    CalendarContract.Events.TITLE,
                    CalendarContract.Events.DESCRIPTION,
                    CalendarContract.Events.EVENT_LOCATION,
                    CalendarContract.Events.DTSTART,
                    CalendarContract.Events.DTEND,
                    CalendarContract.Events.ALL_DAY,
                    CalendarContract.Events.CALENDAR_ID
                )
                val selection = "${CalendarContract.Events.DTSTART} >= ? AND " +
                    "${CalendarContract.Events.DTSTART} <= ? AND " +
                    "${CalendarContract.Events.DELETED} = 0"
                val args = arrayOf(nowMs.toString(), endMs.toString())
                val sort = "${CalendarContract.Events.DTSTART} ASC LIMIT $limit"
                val calNames = mutableMapOf<Long, String>()
                loadCalendarNames(context.contentResolver, calNames)

                val out = mutableListOf<CalendarEventRecord>()
                context.contentResolver.query(
                    CalendarContract.Events.CONTENT_URI,
                    projection, selection, args, sort
                )?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        val title = c.getString(1) ?: "(no title)"
                        val desc = c.getString(2) ?: ""
                        val loc = c.getString(3) ?: ""
                        val start = c.getLong(4)
                        val end = c.getLong(5)
                        val allDay = c.getInt(6) == 1
                        val calId = c.getLong(7)
                        out.add(CalendarEventRecord(
                            id = id.toString(),
                            title = title,
                            description = desc,
                            location = loc,
                            startMs = start,
                            endMs = end,
                            allDay = allDay,
                            calendarName = calNames[calId] ?: ""
                        ))
                    }
                }
                out
            } catch (e: Exception) {
                AppLogger.instance.warn(LogCategory.PLUGIN, "CalendarReader", "list failed: ${e.message}")
                emptyList()
            }
        }

    private fun loadCalendarNames(resolver: ContentResolver, into: MutableMap<Long, String>) {
        resolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.NAME),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                into[c.getLong(0)] = c.getString(1) ?: ""
            }
        }
    }

    private fun hasPermission(perm: String): Boolean =
        ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
}

/**
 * 闹钟设置——经 [AlarmClock.ACTION_SET_ALARM] Intent。
 */
class AlarmSetter(private val context: Context) {
    suspend fun set(params: AlarmSetTool.AlarmParams): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                    putExtra(AlarmClock.EXTRA_HOUR, params.hour)
                    putExtra(AlarmClock.EXTRA_MINUTES, params.minutes)
                    if (params.message.isNotBlank()) {
                        putExtra(AlarmClock.EXTRA_MESSAGE, params.message)
                    }
                    if (params.daysOfWeek.isNotEmpty()) {
                        // AlarmClock.EXTRA_DAYS: List<Integer>，1=Sunday..7=Saturday
                        putExtra(AlarmClock.EXTRA_DAYS, ArrayList(params.daysOfWeek))
                    }
                    putExtra(AlarmClock.EXTRA_VIBRATE, params.vibrate)
                    if (params.skipUi) {
                        putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    }
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                true
            } catch (e: Exception) {
                AppLogger.instance.warn(LogCategory.PLUGIN, "AlarmSetter", "set failed: ${e.message}")
                false
            }
        }
}

/**
 * 文件选择器——经 SAF (ACTION_OPEN_DOCUMENT)。
 *
 * #343：真实 Activity Result 契约——经 [MediaResultBroker] 蹦床等待宿主回传，
 * RESULT_OK 时把用户真实选中的 URIs + 显示名（OpenableColumns.DISPLAY_NAME）
 * 原样上抛；取消 / 超时返回空清单（诚实结果，不再是「SAF picker launched」
 * 占位假名）。
 */
class FilePicker(private val context: Context) {
    suspend fun pick(mimeType: String, allowMultiple: Boolean): FilePickTool.FilePickResult =
        withContext(Dispatchers.IO) {
            try {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = mimeType
                    if (allowMultiple) {
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    }
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val host = MediaResultBroker.awaitResult(context, intent)
                    ?: return@withContext FilePickTool.FilePickResult(
                        uris = emptyList(),
                        displayNames = emptyList()
                    )
                if (host.resultCode != android.app.Activity.RESULT_OK) {
                    // 用户取消 / 宿主失败：诚实空结果，不伪装成功
                    return@withContext FilePickTool.FilePickResult(uris = emptyList())
                }
                val uris = extractPickedUris(host.data)
                if (uris.isEmpty()) {
                    return@withContext FilePickTool.FilePickResult(uris = emptyList())
                }
                FilePickTool.FilePickResult(
                    uris = uris.map { it.toString() },
                    displayNames = uris.map { queryDisplayName(it) },
                    mimeTypes = uris.map { context.contentResolver.getType(it) ?: "" }
                )
            } catch (e: Exception) {
                AppLogger.instance.warn(LogCategory.PLUGIN, "FilePicker", "pick failed: ${e.message}")
                FilePickTool.FilePickResult(uris = emptyList())
            }
        }

    /** SAF 结果里可能单选（data）或多选（clipData），全量提取。 */
    private fun extractPickedUris(data: Intent?): List<Uri> {
        if (data == null) return emptyList()
        val out = mutableListOf<Uri>()
        data.data?.let { out += it }
        val clip = data.clipData ?: return out.toList()
        for (i in 0 until clip.itemCount) {
            clip.getItemAt(i).uri?.let { out += it }
        }
        return out.toList()
    }

    /** 显示名：OpenableColumns.DISPLAY_NAME 查询，查不到退化 lastPathSegment。 */
    private fun queryDisplayName(uri: Uri): String {
        return runCatching {
            context.contentResolver.query(
                uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null
            )?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: uri.lastPathSegment.orEmpty()
        }.getOrDefault(uri.lastPathSegment.orEmpty())
    }
}

/**
 * 拍照——经 [android.provider.MediaStore.ACTION_IMAGE_CAPTURE]。
 *
 * #343：真实 Activity Result 契约——经 [MediaResultBroker] 蹦床等待相机回传：
 * - 启动相机 ≠ 拍到照片：RESULT_CANCELED / RESULT_OK 但零字节都是失败；
 * - 输出经 FileProvider content:// URI（EXTRA_OUTPUT + clipData 双落点、
 *   FLAG_GRANT_WRITE），不再用 file://（targetSdk 26+ 相机几乎都不接）；
 * - 成功必验真实字节（文件存在且 > 0），结果常实际尺寸；
 * - 用户指定 savePath 不在 FileProvider 覆盖范围时：先拍入 cache 再拷贝
 *   到目标路径，拷贝失败如实报失败。
 */
class CameraCapturer(private val context: Context) {
    suspend fun capture(params: ImageCaptureTool.CaptureParams): ImageCaptureTool.CaptureResult =
        withContext(Dispatchers.IO) {
            // #869：开工先扫历史残留（崩溃/被杀路径留下的 capture_*.jpg），
            // 超过 STALE_CAPTURE_MS 的临时件已无任何在途回调会再用它——回收。
            sweepStaleCaptures()
            // cache 临时件句柄：取消/失败/异常路径统一回收（#869：拷贝成功后
            // 也由 finalizeCapture 删除，cacheDir/camera 不再单调膨胀）。
            var tempCaptureFile: java.io.File? = null
            try {
                // 拍摄落地文件：savePath 可直用则直用，否则先落 cache（provider 覆盖）
                val directTarget = params.savePath?.let { java.io.File(it) }
                val captureFile = if (directTarget != null && isUnderProviderRoot(directTarget)) {
                    directTarget
                } else {
                    java.io.File(java.io.File(context.cacheDir, "camera").apply { mkdirs() },
                        "capture_${System.currentTimeMillis()}.jpg")
                }
                if (captureFile != directTarget) tempCaptureFile = captureFile
                captureFile.parentFile?.mkdirs()

                val authority = "${context.packageName}.fileprovider"
                val uri = androidx.core.content.FileProvider.getUriForFile(context, authority, captureFile)
                val intent = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).apply {
                    putExtra(android.provider.MediaStore.EXTRA_OUTPUT, uri)
                    // 授权随 intent flags + clipData 双通道：不同相机实现读的位置不同
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    clipData = android.content.ClipData.newUri(
                        context.contentResolver, "capture", uri
                    )
                }

                val host = MediaResultBroker.awaitResult(context, intent)
                    ?: return@withContext ImageCaptureTool.CaptureResult(
                        success = false,
                        errorMessage = "相机结果等待失败（broker 启动失败或超时）"
                    )
                when {
                    host.resultCode != android.app.Activity.RESULT_OK -> {
                        cleanupTempCapture(tempCaptureFile)
                        ImageCaptureTool.CaptureResult(
                            success = false,
                            errorMessage = "相机已取消或失败（resultCode=${host.resultCode}）——启动相机不等于拍到照片"
                        )
                    }
                    !captureFile.exists() || captureFile.length() <= 0L -> {
                        cleanupTempCapture(tempCaptureFile)
                        ImageCaptureTool.CaptureResult(
                            success = false,
                            errorMessage = "相机返回 OK 但未写入任何图像字节（${captureFile.absolutePath}）"
                        )
                    }
                    else -> finalizeCapture(params, captureFile, directTarget)
                }
            } catch (e: Exception) {
                cleanupTempCapture(tempCaptureFile)
                ImageCaptureTool.CaptureResult(
                    success = false,
                    errorMessage = "${e::class.simpleName}: ${e.message}"
                )
            }
        }

    /** 成功路径收尾：savePath 落地 / 缩略图 / 真实字节数。 */
    private suspend fun finalizeCapture(
        params: ImageCaptureTool.CaptureParams,
        captureFile: java.io.File,
        directTarget: java.io.File?
    ): ImageCaptureTool.CaptureResult {
        var finalFile = captureFile
        if (directTarget != null && directTarget != captureFile) {
            // savePath 不在 provider 覆盖范围：拷贝到用户指定路径，失败如实报
            runCatching { captureFile.copyTo(directTarget, overwrite = true) }
                .getOrElse { e ->
                    cleanupTempCapture(captureFile)
                    return ImageCaptureTool.CaptureResult(
                        success = false,
                        errorMessage = "图像已拍入缓存但拷贝到目标路径失败: ${e.message}"
                    )
                }
            // #869：拷贝成功后 cache 临时副本使命完成——用完即毁
            cleanupTempCapture(captureFile)
            finalFile = directTarget
        }
        return ImageCaptureTool.CaptureResult(
            success = true,
            savedPath = finalFile.absolutePath,
            sizeBytes = finalFile.length(),
            mimeType = "image/jpeg"
        )
    }

    /** 判断文件是否落在 FileProvider 覆盖的根下（files/cache/external 同款配置）。 */
    private fun isUnderProviderRoot(file: java.io.File): Boolean {
        val roots = listOfNotNull(
            context.filesDir,
            context.cacheDir,
            context.getExternalFilesDir(null),
            context.externalCacheDir
        )
        val path = file.canonicalPath
        return roots.any { path.startsWith(it.canonicalPath + java.io.File.separator) }
    }

    /** cache 临时副本回收（#869）：删除失败只留痕——cacheDir 本身有系统兜底。 */
    private fun cleanupTempCapture(file: java.io.File?) {
        if (file == null) return
        runCatching { file.delete() }
            .onFailure { e ->
                AppLogger.instance.warn(
                    LogCategory.TOOL, "CameraCapturer",
                    "临时照片回收失败（${file.name}）: ${e.message}"
                )
            }
    }

    /**
     * 历史残留回收（#869 兑底）：清 cacheDir/camera 下早于 [STALE_CAPTURE_MS]
     * 的 capture_*.jpg。在途回调的临时件必是刚创建（mtime 新），3 天阈值下
     * 绝无误删；崩溃/被杀遗留的半成品在此被收容。
     */
    private fun sweepStaleCaptures() {
        runCatching {
            val camDir = java.io.File(context.cacheDir, "camera")
            if (!camDir.isDirectory) return
            val cutoff = System.currentTimeMillis() - STALE_CAPTURE_MS
            camDir.listFiles { f ->
                f.isFile && f.name.startsWith("capture_") && f.name.endsWith(".jpg") &&
                    f.lastModified() < cutoff
            }?.forEach { stale -> runCatching { stale.delete() } }
        }
    }

    private companion object {
        /** cache 临时照片的保留窗口：超过即视为历史残留回收。 */
        const val STALE_CAPTURE_MS = 3L * 24 * 60 * 60 * 1000
    }
}

/**
 * 系统通知发布器。
 *
 * 通知渠道：默认创建 [DEFAULT_CHANNEL_ID]（silent，no vibration），
 * 用户可经 channel_id 参数指定自定义渠道（需提前在 Settings UI 创建）。
 */
class NotificationPoster(private val context: Context) {
    suspend fun post(params: NotificationPostTool.NotificationParams): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val nm = context.getSystemService(NotificationManager::class.java)
                // API 31+ (Android 12+) 需要 POST_NOTIFICATIONS runtime 权限
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(context, "android.permission.POST_NOTIFICATIONS")
                        != PackageManager.PERMISSION_GRANTED
                ) {
                    AppLogger.instance.warn(
                        LogCategory.PLUGIN, "NotificationPoster",
                        "POST_NOTIFICATIONS permission not granted (Android 13+)"
                    )
                    // 仍尝试发——某些 ROM 允许，发不出时 catch
                }
                // 确保渠道存在
                ensureChannel(nm, params.channelId)
                val notif = androidx.core.app.NotificationCompat.Builder(context, params.channelId)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(params.title)
                    .setContentText(params.text)
                    .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(params.text))
                    .setAutoCancel(true)
                    .setSilent(true)  // 不响铃（避免骚扰）
                    .build()
                @Suppress("DEPRECATION")
                nm.notify(params.tag, params.id, notif)
                true
            } catch (e: Exception) {
                AppLogger.instance.warn(LogCategory.PLUGIN, "NotificationPoster", "post failed: ${e.message}")
                false
            }
        }

    private fun ensureChannel(nm: NotificationManager, channelId: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (nm.getNotificationChannel(channelId) != null) return
        val ch = NotificationChannel(
            channelId,
            "Agent Notifications ($channelId)",
            NotificationManager.IMPORTANCE_LOW  // silent / no sound
        ).apply {
            description = "Notifications posted by the Apex Agent"
            enableVibration(false)
            setShowBadge(false)
        }
        // 防御式 IO 留痕（#260）：O+ 且已查重，理论必成；个别 ROM 私有实现
        // 对并发/重复建渠道仍抛 —— warn 留痕，防「静默丢通知」无从排查
        try { nm.createNotificationChannel(ch) } catch (t: Throwable) {
            AppLogger.instance.warn(
                LogCategory.PLUGIN, "NotificationPoster",
                "createNotificationChannel '$channelId' failed: ${t.message}"
            )
        }
    }

    companion object {
        const val DEFAULT_CHANNEL_ID = "agent_default"
    }
}
