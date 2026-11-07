package com.apex.agent.marketplace

import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import com.apex.agent.core.tools.extension.ExtensionInstaller
import com.apex.agent.core.tools.extension.InspectReport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * # ExtensionWizardController —— 市场统一安装向导的状态域（市场完全重构 PR-C）
 *
 * 「粘贴任意 spec → resolve → inspect 预览 → 确认 → install」的用户侧入口：
 * 与 agent 侧 ExtensionResolve/Inspect/Install 三工具走**同一个闸门实例**
 * （CLI 与 Agent 同一条校验路径，brief 明确要求）。三步闸门的 hash 门 /
 * 确认门在控制器内如实传递 —— 预览即 inspect 报告，安装即带指纹的确认。
 *
 * 独立状态域（同 MarketHubController / MarketMcpSoController 惯例）：
 * 自持 StateFlow + 自持 scope，VM 销毁由 [onCleared] 收口。
 */
class ExtensionWizardController @javax.inject.Inject constructor(
    private val installer: ExtensionInstaller
) {
    /** 向导 UI 状态（对话框收集渲染）。 */
    data class WizardUiState(
        val visible: Boolean = false,
        val spec: String = "",
        val busy: Boolean = false,
        /** inspect 预览文本（null = 未 resolve/inspect）。 */
        val preview: String? = null,
        val manifestHash: String? = null,
        /** 需要用户显式确认（inspect 报了权限/脚本）。 */
        val requiresConfirm: Boolean = false,
        /** 安装结果（成功/失败文案；null = 尚未安装）。 */
        val result: String? = null
    )

    private val _state = MutableStateFlow(WizardUiState())
    val state: StateFlow<WizardUiState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun show() = _state.update { it.copy(visible = true, result = null) }
    fun hide() = _state.update { it.copy(visible = false) }
    fun updateSpec(spec: String) {
        // spec 变更后旧预览作废（hash 门语义：看过什么才能装什么）
        _state.update { it.copy(spec = spec, preview = null, manifestHash = null, requiresConfirm = false, result = null) }
    }

    /** 第一步：resolve → inspect（预览 + 指纹）。 */
    fun resolveAndInspect() {
        val spec = _state.value.spec.trim()
        if (spec.isEmpty() || _state.value.busy) return
        _state.update { it.copy(busy = true, preview = null, manifestHash = null, requiresConfirm = false, result = null) }
        scope.launch {
            val outcome = runCatching {
                val manifest = installer.resolve(spec).getOrElse { error(it.message ?: "resolve 失败") }
                val report: InspectReport = installer.inspect(manifest).getOrElse { error(it.message ?: "inspect 失败") }
                Triple(renderPreview(report), manifest.manifestHash, manifest.requiresConfirm())
            }
            _state.update { st ->
                outcome.fold(
                    onSuccess = { (preview, hash, confirm) ->
                        st.copy(busy = false, preview = preview, manifestHash = hash, requiresConfirm = confirm)
                    },
                    onFailure = { e ->
                        st.copy(busy = false, result = "resolve/inspect 失败：${e.message}")
                    }
                )
            }
        }
    }

    /** 第二步：install（携带 inspect 指纹；敏感扩展带 confirm 令牌）。 */
    fun install(confirmed: Boolean) {
        val st = _state.value
        val hash = st.manifestHash ?: return
        if (st.busy) return
        _state.update { it.copy(busy = true) }
        scope.launch {
            val outcome = runCatching {
                installer.install(hash, confirmToken = if (confirmed) "confirm" else null)
            }
            _state.update { cur ->
                outcome.fold(
                    onSuccess = { o ->
                        cur.copy(busy = false, result = "✅ 已安装：${o.where}" + o.notes.joinToString("；") { n -> n }.ifBlank { "" })
                    },
                    onFailure = { e ->
                        cur.copy(busy = false, result = "❌ 安装失败：${e.message}")
                    }
                )
            }
            AppLogger.instance.info(LogCategory.PLUGIN, "ExtensionWizard", "install finished: ${outcome.isSuccess}")
        }
    }

    fun onCleared() = scope.cancel()

    /** inspect 报告 → 用户可读预览（与 agent 侧 ExtensionInspect 输出同信息面）。 */
    private fun renderPreview(r: InspectReport): String = buildString {
        appendLine("${r.manifest.type} · ${r.manifest.name} v${r.manifest.version}")
        appendLine("runtime: ${r.manifest.runtime} · trust: ${r.manifest.trustLevel} · action: ${r.manifest.action}")
        appendLine("目标: ${r.targetRegistry}")
        appendLine("位置: ${r.targetLocation}")
        r.domainDependencies.forEach { appendLine("依赖: $it") }
        if (r.manifest.permissions.isNotEmpty()) appendLine("权限: ${r.manifest.permissions.joinToString(", ")}")
        appendLine("生命周期脚本: ${if (r.manifest.hasPostinstall) "有（默认拒绝自动安装）" else "未检出"}")
        r.warnings.forEach { appendLine("⚠️ $it") }
        append("简介: ${r.manifest.summary}")
    }

    /** runCatching 里的快捷失败（抛 IllegalStateException 被外层接住）。 */
    private fun error(message: String): Nothing = throw IllegalStateException(message)
}
