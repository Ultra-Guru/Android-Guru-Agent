package com.apex.agent.ui

import androidx.lifecycle.ViewModel
import com.apex.agent.github.GithubTokenManager
import com.apex.agent.net.NetworkMonitor
import com.apex.agent.ui.screen.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * 全局状态数据源（ApexRoot / ApexDrawerContent 消费）。
 *
 * Agent 聊天屏移除后，原先由 AgentChatViewModel 兼职承载的全局状态
 * （网络监测 / 设置快照 / GitHub token 算）由本轻量 VM 承载：
 * - 离线横幅（ApexRoot）← [networkMonitor]；
 * - 启动更新检查与 Changelog 门控（ApexRoot）← [settingsRepository]；
 * - 抽屉 GitHub 连接状态徽标（ApexDrawerContent）← [githubTokenManager]。
 *
 * 纯透传（三个 @Inject 单例），无 init 协程、无自有状态。
 */
@HiltViewModel
class AppStatusViewModel @Inject constructor(
    /** 网络状态源（离线横幅）。 */
    val networkMonitor: NetworkMonitor,
    /** GitHub token 管理器（抽屉连接徽标 / 默认仓库）。 */
    val githubTokenManager: GithubTokenManager,
    /** 设置仓库（agentSettings 快照：autoUpdateCheck 等全局开关）。 */
    val settingsRepository: SettingsRepository,
) : ViewModel()
