package com.apex.agent.ui

import androidx.lifecycle.ViewModel
import com.apex.agent.tools.AppNavigationBridge
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * [AppNavigationBridge] 的 Compose 侧取用壳。
 *
 * ApexRoot 是普通 @Composable，不能直接 @Inject 单例；经本 HiltViewModel
 * 暴露进程级单例桥（与 agentVm 同款 hiltViewModel() 取用模式）。桥本体的
 * 语义见 [AppNavigationBridge] KDoc（工具 → UI 的真实导航投递箱）。
 */
@HiltViewModel
class AppNavBridgeViewModel @Inject constructor(
    val bridge: AppNavigationBridge
) : ViewModel()
