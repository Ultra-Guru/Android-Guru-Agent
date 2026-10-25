package com.apex.agent.ui.screen.code.history

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.viewModelScope
import com.apex.agent.R
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import com.apex.agent.ui.screen.code.CodeViewModel
import com.apex.agent.ui.screen.code.session.CodeSessionSnapshot
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ─────────────────────────────────────────────────────────────────────────────
// Coding 历史会话导出 Markdown（v1.4.10 历史对话面板的补全能力）——
// CodeViewModel 同包 internal 扩展（God-file 预算纪律：VM 行数只减不增，
// 导出链路全部住在本文件，CodeHistorySheet 只出一个按钮回调）。
//
// 链路与 Agent 屏 ChatSessionOps.exportChatSessionMarkdown 同款范式：
// cacheDir/exports/ 落盘 + FileProvider + ACTION_SEND 系统分享；差异点：
//  - 数据源是 CodeHistoryStore 归档快照（readSnapshot 纯读取，不动活位），
//    而不是引擎侧活会话；
//  - 渲染 USER/ASSISTANT/TOOL/SYSTEM 四角色的 Coding 语义（工具条目带
//    名称/耗时/成败徽标，与时间轴胶囊的信息密度对齐）。
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 导出单条历史会话为 Markdown 并发起系统分享（人类可读归档通道）。
 *
 * 失败路径经 appendSystemMessage 进时间轴（与「已归档」同款行内留痕），
 * 成功路径的反馈即分享面板本身（不再额外发消息）。
 */
internal fun CodeViewModel.exportCodeHistoryEntryMarkdown(context: Context, entry: CodeHistoryEntry) {
    val appContext = context.applicationContext
    viewModelScope.launch(Dispatchers.IO) {
        runCatching {
            val snapshot = codeHistoryStore.readSnapshot(entry.workspaceId, entry.id)
            if (snapshot == null || snapshot.messages.isEmpty()) {
                appendSystemMessage(languageManager.getString(R.string.code_history_export_not_found))
                return@launch
            }
            val markdown = buildCodeHistoryMarkdown(entry, snapshot)
            val dir = File(appContext.cacheDir, "exports").apply { mkdirs() }
            val file = File(dir, "code-history-${entry.id.take(8)}-${System.currentTimeMillis()}.md")
            file.writeText(markdown, Charsets.UTF_8)
            val uri = FileProvider.getUriForFile(
                appContext,
                "${appContext.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/markdown"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            withContext(Dispatchers.Main) {
                appContext.startActivity(
                    Intent.createChooser(intent, languageManager.getString(R.string.code_history_export_chooser))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }.onFailure {
            AppLogger.instance.warn(
                category = LogCategory.UI,
                source = "CodeHistoryExport",
                message = "历史会话导出失败（${entry.id}）：${it.message}"
            )
            appendSystemMessage(languageManager.getString(R.string.code_history_export_failed))
        }
    }
}

/**
 * 归档快照 → Markdown 正文（纯函数，单测友好）。
 *
 * 结构对齐 Agent 屏 buildSessionMarkdown 的既定口径：标题 + 元信息行 +
 * 逐角色正文 + 落款。工具条目渲染为「名称 + 成败 + 耗时」头行 + 代码围栏，
 * 与 Coding 时间轴胶囊的摘要密度一致（导出读者不看 UI 也能还原执行过程）。
 */
internal fun buildCodeHistoryMarkdown(
    entry: CodeHistoryEntry,
    snapshot: CodeSessionSnapshot
): String = buildString {
    val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    appendLine("# ${entry.title.ifBlank { entry.id }}")
    appendLine()
    appendLine("> ${dateFmt.format(Date(entry.updatedAt))} · ${snapshot.messages.size} 条消息" +
        (snapshot.lastActiveFile?.takeIf { it.isNotBlank() }?.let { " · 最近编辑 $it" } ?: ""))
    appendLine()
    snapshot.messages.forEach { m ->
        when (m.role.uppercase()) {
            "USER" -> {
                appendLine("## 🧑 User")
                appendLine(m.text)
                appendLine()
            }
            "ASSISTANT" -> {
                appendLine("## 🤖 Assistant")
                appendLine(m.text)
                appendLine()
            }
            "TOOL" -> {
                val verdict = if (m.toolSuccess) "OK" else "FAILED"
                val duration = if (m.durationMs > 0) " · ${m.durationMs}ms" else ""
                appendLine("**🔧 ${m.toolName ?: "Tool"} · $verdict$duration**")
                appendLine("```")
                appendLine(m.text)
                appendLine("```")
                appendLine()
            }
            else -> {
                // SYSTEM 及未知角色的降级口径：引用块（内容保住、视觉弱化）
                appendLine("> ${m.text}")
                appendLine()
            }
        }
    }
    appendLine("---")
    appendLine("_Exported from Apex Agent Coding History · ${dateFmt.format(Date())}_")
}
