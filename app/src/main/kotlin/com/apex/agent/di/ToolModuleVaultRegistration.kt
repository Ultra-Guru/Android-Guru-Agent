package com.apex.agent.di

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.apex.agent.core.tools.SafeAgentTool
import com.apex.agent.platform.terminal.io.InputOwner
import com.apex.agent.platform.terminal.runtime.TerminalRuntime
import com.apex.agent.vault.VaultAgentTools
import com.apex.agent.vault.VaultRepository
import okhttp3.OkHttpClient

/**
 * # 加密剪切板金库工具注册段（#167，4 个工具）
 *
 * 自 [ToolModule.provideToolRegistry] 提取的 vault_* 工具族注册（同
 * [ToolModuleDeviceControlRegistration] SRP 拆分模式 —— 主注册文件贴近
 * 1200 行预算，scripts/check_file_size.sh 强制）。
 *
 * 安全契约：Agent 只见标签与备注 —— VaultList 输出脱敏快照；
 * VaultSave write-only（成功返回仅 label+id，写完自己也读不回）；
 * VaultPaste 三通道直投（clipboard/terminal/http），返回仅字节数/
 * 状态码/脱敏响应体，内容永不回显；VaultDelete HIGH 风险门控。
 * 人类可经抽屉「保险库」页储放 GitHub token / AI API 密钥。
 */
internal fun registerVaultTools(
    registry: com.apex.agent.core.tools.ToolRegistry,
    context: Context,
    httpClient: OkHttpClient,
    vaultRepository: VaultRepository,
    terminalRuntime: TerminalRuntime
) {
    VaultAgentTools.all(
        repository = vaultRepository,
        clipboardSetter = { text ->
            // Android 10+ 前台限制：后台写入剪贴板可能抛异常 ——
            // 由 VaultPasteTool 捕获并转成可自修复的 Error 文本。
            // #452：CLIPBOARD_SERVICE 极端环境可能为 null —— error() 同样
            // 被 VaultPasteTool 捕获，与原 as 崩溃路径语义一致。
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: error("clipboard service unavailable")
            cm.setPrimaryClip(ClipData.newPlainText("vault", text))
        },
        terminalWriter = { sessionId, text ->
            // kind=RAW：密钥按字节原样进入 PTY（换行已在工具层拼接）。
            terminalRuntime.write(
                sessionId = sessionId,
                owner = InputOwner.AGENT,
                kind = TerminalRuntime.WriteKind.RAW,
                text = text
            ).fold(onSuccess = { it.written }, onFailure = { false })
        },
        httpClient = httpClient
    ).forEach { registry.register(SafeAgentTool(it)) }
}
