package com.apex.agent.ui.screen.market

import androidx.lifecycle.viewModelScope
import com.apex.agent.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// ─────────────────────────────────────────────────────────────────────────────
// Skills 安装/卸载/导入 —— MarketViewModel 的扩展（God-file 预算拆分，
// 模式同 MarketViewModelMcpOps.kt：函数体原样迁移，skillRegistry /
// installManager 已开放 internal，Compose 调用点与方法引用语义不变）。
//
// 职责：
//  - toggleSkill / uninstallSkill：启停与卸载（内置技能拦截，Issue #166）；
//  - installSkillTemplate / importSkillJson / importSkillFromUrl /
//    importSkillFromFile：模板与本地（zip/json）技能安装入口；
//  - importMcpConfigFromFile：MCP 配置文件导入的读文件壳（解析复用
//    importMcpConfig 管道）。
// ─────────────────────────────────────────────────────────────────────────────

fun MarketViewModel.toggleSkill(skillId: String, enabled: Boolean) {
    viewModelScope.launch(Dispatchers.IO) {
        skillRegistry.setEnabled(skillId, enabled)
        refresh()
    }
}

fun MarketViewModel.uninstallSkill(skillId: String) {
    viewModelScope.launch(Dispatchers.IO) {
        // Issue #166：内置技能不可卸载（SkillRegistry.uninstall 对 bundled 恒 false），
        // 提前拦下并给出正确提示——否则会落进「未找到技能」的误导文案。
        if (_uiState.value.skills.any { it.id == skillId && it.bundled }) {
            message(languageManager.getString(R.string.market_skill_bundled_uninstall_blocked_hint))
            return@launch
        }
        val ok = skillRegistry.uninstall(skillId)
        message(
            if (ok) {
                languageManager.getString(R.string.market_skill_uninstalled).format(skillId)
            } else {
                languageManager.getString(R.string.market_skill_not_found).format(skillId)
            }
        )
        refresh()
    }
}

fun MarketViewModel.installSkillTemplate(templateId: String) {
    viewModelScope.launch {
        installManager.installSkillTemplate(templateId).fold(
            onSuccess = { message(it) },
            onFailure = {
                message(languageManager.getString(R.string.market_install_failed).format(it.message ?: ""))
            }
        )
        refresh()
    }
}

/** 导入自定义 Skill JSON（粘贴内容）。 */
fun MarketViewModel.importSkillJson(content: String) {
    viewModelScope.launch {
        installManager.installSkillFromJson(content).fold(
            onSuccess = { message(it) },
            onFailure = {
                message(languageManager.getString(R.string.market_import_failed).format(it.message ?: ""))
            }
        )
        refresh()
    }
}

/** 从 URL 导入 Skill manifest。 */
fun MarketViewModel.importSkillFromUrl(url: String) {
    viewModelScope.launch {
        installManager.installSkillFromUrl(url).fold(
            onSuccess = { message(it) },
            onFailure = {
                message(languageManager.getString(R.string.market_import_failed).format(it.message ?: ""))
            }
        )
        refresh()
    }
}

/**
 * 从本地文件导入 Skill（.zip / .json 自动识别）。
 *
 * 用户反馈"Skills 不能自己导入"：本地 skill 包此前没有任何入口。
 * zip 走 SafeZipExtractor（路径穿越 / zip bomb 防御），json 直接装 manifest。
 */
fun MarketViewModel.importSkillFromFile(uri: android.net.Uri) {
    viewModelScope.launch {
        installManager.installSkillFromFile(uri).fold(
            onSuccess = { message(it) },
            onFailure = {
                message(it.message ?: languageManager.getString(R.string.market_local_import_failed))
            }
        )
        refresh()
    }
}

/**
 * 从本地文件导入 MCP 配置（`{"mcpServers": {...}}` 形态的 .json）。
 * 复用 [importMcpConfig] 的解析与逐条报错管道。
 */
fun MarketViewModel.importMcpConfigFromFile(uri: android.net.Uri) {
    viewModelScope.launch {
        installManager.readTextFile(uri).fold(
            onSuccess = { text -> importMcpConfig(text) },
            onFailure = {
                message(
                    languageManager.getString(R.string.market_read_file_failed)
                        .format(it.message ?: "")
                )
            }
        )
    }
}
