package com.apex.agent.ui.component

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import com.apex.agent.R
import java.io.File

/**
 * 通过 FileProvider 唤起外部应用打开附件。
 *
 * 必须在 AndroidManifest 中注册 FileProvider，并配置 res/xml/file_paths.xml。
 * #858 起 file_paths.xml 已收窄为各调用点实际使用的子目录（不再覆盖全部
 * files/cache 根），且本入口的路径全部来自**模型可控**的 Markdown 媒体卡
 * （VideoCard / 图片链接）——因此路径先经 [MediaOpenPolicy] 白名单把关：
 * 仅公共存储媒体/下载目录放行，应用私有目录（/data/ 下）一律拒绝，
 * 防止模型一句话把私有数据授权给任意外部应用。
 *
 * 在 Android 11+（API 30+）上 `resolveActivity()` 受 `<queries>` 限制会返回 null，
 * 因此这里直接 startActivity 并捕获 [ActivityNotFoundException]，比 resolveActivity 更可靠。
 */
object FileOpener {

    private const val TAG = "FileOpener"

    /**
     * 唤起外部应用打开文件（模型可控本地路径入口，#858 起白名单放行）。
     *
     * @param context 任意 Context（内部会调用 startActivity，自动加 NEW_TASK flag）
     * @param filePath 文件绝对路径（必须通过 [MediaOpenPolicy] 白名单：公共存储
     * 媒体/下载目录；应用私有目录会被拒绝并提示）
     * @param mimeType 文件 MIME；为 null 时根据文件名兜底推断
     */
    fun openFile(context: Context, filePath: String, mimeType: String? = null) {
        // #858：本入口的全部调用方（Markdown 媒体卡）路径均为模型可控 ——
        // 白名单外的路径（应用私有数据 / 非常规 scheme / 路径穿越）直接拒绝，
        // 不再依赖 FileProvider 根收窄后的 IllegalArgumentException 兜底。
        if (!MediaOpenPolicy.isAllowed(filePath)) {
            Toast.makeText(context, context.getString(R.string.file_open_provider_reject), Toast.LENGTH_SHORT).show()
            Log.w(TAG, "Blocked non-whitelisted media path: ${filePath.take(160)}")
            return
        }
        try {
            val file = File(filePath)
            if (!file.exists() || !file.canRead()) {
                Toast.makeText(context, context.getString(R.string.file_open_not_readable), Toast.LENGTH_SHORT).show()
                return
            }

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val resolvedMimeType = mimeType?.takeIf { it.isNotBlank() } ?: guessMimeType(file.name)

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, resolvedMimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            // Android 11+ 不再依赖 resolveActivity —— 直接 startActivity + ActivityNotFoundException
            // i18n（#2-c P1-5）：chooser 标题走双语资源（英文用户不再看到中文）。
            val chooser = Intent.createChooser(
                intent,
                context.getString(R.string.file_open_chooser_title)
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, context.getString(R.string.file_open_no_app), Toast.LENGTH_SHORT).show()
        } catch (e: IllegalArgumentException) {
            // FileProvider 路径越界（#858 起根已收窄为白名单目录；白名单外的
            // 路径已被 MediaOpenPolicy 前置拦截，这里是双保险兜底）
            Toast.makeText(context, context.getString(R.string.file_open_provider_reject), Toast.LENGTH_SHORT).show()
            Log.w(TAG, "FileProvider rejected path: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to open file: ${e.message}")
        }
    }

    /**
     * 打开外部 URL（仅 http/https）：优先唤起能处理该 scheme 的应用（浏览器等）。
     *
     * Markdown 里的视频卡 / 链接点击时使用；与 [openFile] 的区别是无需
     * FileProvider（remote URL 不在沙箱目录内）。
     * #565：出口统一 scheme 白名单 —— 调用方（如 MarkdownText 的 onOpenLink
     * 回调）不保证全部前置检查，非 http(s) 直接拒绝防 intent 重定向。
     */
    fun openUrl(context: Context, url: String): Boolean {
        val scheme = Uri.parse(url).scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            Log.w(TAG, "Blocked non-http(s) url: $scheme")
            return false
        }
        return try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse(url)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, context.getString(R.string.file_open_no_link_app), Toast.LENGTH_SHORT).show()
            false
        } catch (e: Exception) {
            Log.w(TAG, "Failed to open url: $url (${e.message})")
            false
        }
    }

    /**
     * 根据文件名扩展名推断 MIME 类型。
     *
     * 优先使用系统 [MimeTypeMap]（覆盖绝大多数常见类型）；
     * 对系统不识别但项目常用的类型（apk/json/yaml/markdown 等）做补充兜底。
     */
    fun guessMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext.isBlank()) return "*/*"

        // 1. 优先使用系统 MimeTypeMap
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.let { return it }

        // 2. 兜底：项目常用但系统可能未覆盖的类型
        return when (ext) {
            "apk" -> "application/vnd.android.package-archive"
            "json" -> "application/json"
            "yaml", "yml" -> "application/x-yaml"
            "md" -> "text/markdown"
            "kt", "kts" -> "text/x-kotlin"
            "java" -> "text/x-java-source"
            "py" -> "text/x-python"
            "sh" -> "application/x-sh"
            "csv" -> "text/csv"
            "log" -> "text/plain"
            "ts" -> "application/typescript"
            "tsx" -> "application/typescript"
            "jsx" -> "application/javascript"
            "js" -> "application/javascript"
            "css" -> "text/css"
            "svg" -> "image/svg+xml"
            else -> "*/*"
        }
    }

    /**
     * 根据 mimeType 简单判断是否为可直接预览的图片/文本/视频/音频。
     * 否则需要外部应用支持。
     */
    fun isPreviewable(mimeType: String): Boolean = when {
        mimeType.startsWith("image/") -> true
        mimeType.startsWith("text/") -> true
        mimeType.startsWith("video/") -> true
        mimeType.startsWith("audio/") -> true
        mimeType.contains("pdf") -> true
        else -> false
    }

    /**
     * 给 [Uri] 提供一个便捷入口（ContentResolver 查询失败时降级到 [guessMimeType]）。
     */
    fun resolveMimeType(context: Context, uri: Uri, fallbackName: String? = null): String {
        context.contentResolver.getType(uri)?.let { return it }
        val name = fallbackName ?: uri.lastPathSegment.orEmpty()
        return guessMimeType(name)
    }
}
