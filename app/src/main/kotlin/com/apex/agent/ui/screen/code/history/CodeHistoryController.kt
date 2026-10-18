package com.apex.agent.ui.screen.code.history

import com.apex.agent.R
import com.apex.agent.ui.screen.code.CodeViewModel
import com.apex.agent.ui.screen.code.CodeStreamSnapshot
import com.apex.agent.ui.screen.code.session.CodeSessionSnapshot
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// ─────────────────────────────────────────────────────────────────────────────
// Coding 历史对话操作流（CodeViewModel 同包 internal 扩展 —— God-file 预算
// 纪律：VM 行数只减不增，新逻辑全部住在本文件，调用点零改动语义）。
//
// 三条用户操作：
//   1. 新会话 = 先归档再清空（clearConversation 委托到
//      [clearConversationWithArchive]，旧实现「物理删除」的根因修复）；
//   2. 恢复历史 = 当前对话同样先归档（恢复是替换语义，不能丢现场）→
//      引擎记忆回写 → 会话快照写回 → 重绑工作区（引擎重放 + UI 恢复）；
//   3. 删除历史 = 归档目录删除。
// ─────────────────────────────────────────────────────────────────────────────

/** 快照里存在「值得归档」的内容（用户/助手消息——纯系统欢迎语不算）。 */
internal fun hasArchivableContent(snapshot: CodeSessionSnapshot): Boolean =
    snapshot.messages.any { it.role.equals("USER", ignoreCase = true) || it.role.equals("ASSISTANT", ignoreCase = true) }

/**
 * 新会话（归档版）：当前对话有实质内容 → 先完整归档（UI 快照 + 双线引擎
 * 记忆），再执行旧清空链路（引擎 clearConversation / 快照删除 / UI 复位）。
 */
internal fun CodeViewModel.clearConversationWithArchive() {
    if (_uiState.value.isRunning) return
    val wsId = boundWorkspaceId
    if (wsId != null) {
        val snapshot = buildSessionSnapshot(wsId)
        if (hasArchivableContent(snapshot)) {
            viewModelScope.launch {
                val archived = withContext(Dispatchers.IO) {
                    runCatching { codeHistoryStore.archive(wsId, snapshot) }.getOrNull()
                }
                if (archived != null) {
                    appendSystemMessage(
                        languageManager.getString(
                            R.string.code_history_archived_fmt, snapshot.messages.size
                        )
                    )
                } else {
                    AppLogger.instance.warn(
                        LogCategory.UI, "CodeHistory",
                        "新会话归档失败（对话仍照常清空，历史里可能缺本次记录）"
                    )
                }
            }
        }
    }
    // ═══ 旧清空链路（原 clearConversation 逐行为迁移，语义不变）═══
    codeEngineImpl?.clearConversation()
    codeTodoTool.clear()
    if (wsId != null) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { codeSessionStore.clear(wsId) } }
        }
    }
    streamSession.clear()
    _uiState.update {
        it.copy(
            messages = emptyList(), todos = emptyList(), contextUsedTokens = 0,
            inputDraft = "", editorFilePath = null, editorFile = null, editorError = null,
            stream = CodeStreamSnapshot()
        )
    }
}

/** 载入当前工作区的历史列表（IO 线程；空工作区返回空表）。 */
internal suspend fun CodeViewModel.loadCodeHistoryEntries(): List<CodeHistoryEntry> {
    val wsId = boundWorkspaceId ?: return emptyList()
    return withContext(Dispatchers.IO) {
        runCatching { codeHistoryStore.entries(wsId) }.getOrDefault(emptyList())
    }
}

/**
 * 恢复历史会话（替换语义）：
 * 1. 当前对话先归档（防丢）；
 * 2. 引擎记忆（双线）+ UI 快照从归档回写；
 * 3. 重绑工作区 —— 引擎重放归档记忆、UI 走 restoreSessionSnapshot 恢复现场
 *    （bindWorkspace 以 launch 异步恢复，本协程持锁期间它排队，无死锁）。
 */
internal fun CodeViewModel.restoreCodeHistoryEntry(entry: CodeHistoryEntry) {
    if (_uiState.value.isRunning) return
    val wsId = boundWorkspaceId ?: return
    val ws = _uiState.value.activeWorkspace ?: return
    viewModelScope.launch {
        sessionMutex.withLock {
            val current = buildSessionSnapshot(wsId)
            if (hasArchivableContent(current)) {
                withContext(Dispatchers.IO) {
                    runCatching { codeHistoryStore.archive(wsId, current) }
                }
            }
            val restored = withContext(Dispatchers.IO) {
                runCatching { codeHistoryStore.restoreInto(wsId, entry.id) }.getOrNull()
            }
            if (restored == null) {
                appendSystemMessage(languageManager.getString(R.string.code_history_restore_failed))
                return@launch
            }
            withContext(Dispatchers.IO) {
                runCatching { codeSessionStore.save(restored) }
                    .onFailure {
                        AppLogger.instance.warn(
                            LogCategory.UI, "CodeHistory", "恢复写回会话快照失败：${it.message}"
                        )
                    }
            }
            // 重绑（同工作区重复 bind 不冲刷旧快照，见 bindWorkspace 守卫）
            bindWorkspace(ws)
        }
    }
}

/** 删除单条历史会话；返回是否成功。 */
internal suspend fun CodeViewModel.deleteCodeHistoryEntry(entry: CodeHistoryEntry): Boolean =
    withContext(Dispatchers.IO) {
        runCatching { codeHistoryStore.delete(entry.workspaceId, entry.id) }.isSuccess
    }
