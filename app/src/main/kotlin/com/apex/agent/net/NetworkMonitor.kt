package com.apex.agent.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import androidx.annotation.StringRes
import com.apex.agent.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ═══════════════════════════════════════════════════════════════
 *  全局网络状态监测 —— isOnline / networkKind 的单一事实源
 * ═══════════════════════════════════════════════════════════════
 *
 * 背景：接入本类之前全 App 无任何网络状态监测 —— 断网时用户只能看到请求转圈失败。
 * 此后离线横幅（ui/component/OfflineBanner）与请求失败提示（ui/component/Feedback
 * 的 LocalFeedbackController）及各屏在线态 UI 统一订阅 [isOnline] / [networkKind]。
 *
 * ── 设计要点：回调只是触发器，仲裁查询才是事实 ──
 * NetworkCallback 的经典坑：[ConnectivityManager.NetworkCallback.onLost] 被
 * 调用 ≠ 设备断网 —— 设备可能同时持有多个 network（Wi-Fi + 蜂窝双通道、
 * VPN 叠加底层传输），丢失其中一条时另一条仍在线。因此 onAvailable /
 * onLost / onCapabilitiesChanged 三个回调统一只做一件事：触发 [arbitrate]
 * —— 重新同步查询 activeNetwork 的能力，**以仲裁查询结果为准**更新状态，
 * 回调入参本身不作数。仲裁结果与缓存一致则直接短路，天然抑制
 * onCapabilitiesChanged 的高频抖动（信号强度变化不产生虚假发射）。
 *
 * ── 生命周期 ──
 * @Singleton：回调注册一次，进程存活期间不注销（单例中途注销只会在状态监测上
 * 开洞）；持有 ApplicationContext，无泄漏。由 Hilt 首次注入时创建（主控在 ApexApp
 * 启动时触发实例化，构造内的注册随即生效）。
 *
 * ── 容错 ──
 * 极端 ROM 上 registerNetworkCallback 可能抛异常：注册整体 runCatching，
 * 失败时保持构造时的同步探测初值，绝不崩（宁可状态不更新，不可启动失败）。
 *
 * 权限：ACCESS_NETWORK_STATE 已在 Manifest 声明，无需新增。
 */

/** 网络类型（按用户可感知的方式分类）。VPN 优先于底层传输展示。 */
enum class NetworkKind { NONE, WIFI, CELLULAR, ETHERNET, VPN, OTHER }

@Singleton
class NetworkMonitor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    // getSystemService 极端环境可能返回 null：安全转换，null 一律按离线处理
    private val connectivityManager: ConnectivityManager? =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    /** 构造时同步探测一次 activeNetwork —— StateFlow 初值即真实状态（不做乐观假设）。 */
    private val initialKind: NetworkKind = probeActiveNetwork()

    /** 当前网络类型（仲裁后的单一事实源）。 */
    private val _networkKind = MutableStateFlow(initialKind)
    val networkKind: StateFlow<NetworkKind> = _networkKind.asStateFlow()

    /** 在线 ⇔ 当前仲裁出的网络具备 INTERNET 能力（与 [networkKind] 同源派生）。 */
    private val _isOnline = MutableStateFlow(initialKind != NetworkKind.NONE)
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    /** 仲裁缓存：binder 线程回调与构造线程共写，volatile 读 + 锁内读-判-写。 */
    @Volatile
    private var lastArbitrated: NetworkKind = initialKind

    /** 保护 [lastArbitrated] 读-判-写原子性的锁（StateFlow 赋值本身线程安全，无需入锁）。 */
    private val arbitrationLock = Any()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        // 三个回调一律不信任入参 network —— 统一重查 activeNetwork 仲裁（见类 KDoc）
        override fun onAvailable(network: Network) = arbitrate()

        // onLost 是最不可信的：可能还有别的 network 撑着（NetworkCallback 经典坑）
        override fun onLost(network: Network) = arbitrate()

        // 高频回调（信号强度变化也会来）—— 仲裁缓存短路防抖
        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities
        ) = arbitrate()
    }

    init {
        val cm = connectivityManager
        if (cm == null) {
            Log.w(TAG, "ConnectivityManager unavailable — keep initial probe state")
        } else {
            runCatching {
                cm.registerNetworkCallback(
                    NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build(),
                    callback
                )
            }.onFailure { e ->
                Log.w(
                    TAG,
                    "registerNetworkCallback failed (exotic ROM?) — " +
                        "keep initial probe state: ${e.message}"
                )
            }
        }
    }

    /**
     * 仲裁：所有 NetworkCallback 的唯一出口 —— 重查 activeNetwork 能力后更新状态。
     * 回调在 binder 线程；MutableStateFlow 赋值（等价 tryEmit，conflated 必成功）
     * 线程安全可直接做，[arbitrationLock] 只保证缓存 读-判-写 原子性防重复发射。
     */
    private fun arbitrate() {
        val kind = probeActiveNetwork()
        val changed = synchronized(arbitrationLock) {
            if (kind != lastArbitrated) {
                lastArbitrated = kind
                true
            } else {
                false
            }
        }
        if (changed) {
            _networkKind.value = kind
            _isOnline.value = kind != NetworkKind.NONE
        }
    }

    /**
     * 同步探测当前 activeNetwork 的能力。任何环节异常一律按 [NetworkKind.NONE]
     * 兜底 —— 宁可误报离线（触发降级提示），绝不误报在线（请求静默卡死）。
     */
    private fun probeActiveNetwork(): NetworkKind {
        val cm = connectivityManager ?: return NetworkKind.NONE
        return runCatching {
            val network = cm.activeNetwork ?: return@runCatching NetworkKind.NONE
            val caps = cm.getNetworkCapabilities(network) ?: return@runCatching NetworkKind.NONE
            caps.toNetworkKind()
        }.getOrDefault(NetworkKind.NONE)
    }

    /** 能力 → 类型：先验 INTERNET 能力（无则视为离线），VPN 优先于底层传输。 */
    private fun NetworkCapabilities.toNetworkKind(): NetworkKind = when {
        !hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> NetworkKind.NONE
        hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> NetworkKind.VPN
        hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkKind.WIFI
        hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkKind.CELLULAR
        hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkKind.ETHERNET
        else -> NetworkKind.OTHER
    }

    /**
     * 当前网络类型摘要的字符串资源（Wi-Fi/移动网络/以太网/VPN/其他/离线），
     * 供 UI `stringResource(networkMonitor.currentSummaryRes())` 显示
     * （如 OfflineBanner 右侧小字、状态页标签）。
     */
    @StringRes
    fun currentSummaryRes(): Int = when (networkKind.value) {
        NetworkKind.WIFI -> R.string.net_kind_wifi
        NetworkKind.CELLULAR -> R.string.net_kind_cellular
        NetworkKind.ETHERNET -> R.string.net_kind_ethernet
        NetworkKind.VPN -> R.string.net_kind_vpn
        NetworkKind.OTHER -> R.string.net_kind_other
        NetworkKind.NONE -> R.string.net_kind_offline
    }

    private companion object {
        private const val TAG = "NetworkMonitor"
    }
}
