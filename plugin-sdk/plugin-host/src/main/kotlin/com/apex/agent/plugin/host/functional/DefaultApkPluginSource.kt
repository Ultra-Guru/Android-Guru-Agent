package com.apex.agent.plugin.host.functional

import com.apex.agent.plugin.host.PluginManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * [ApkPluginSource] 的生产实现：把 [PluginManager.loadedPlugins]
 * （Map<包名, LoadedPlugin>）投影成纯数据快照流（Map<包名, 展示名>）。
 *
 * 投影意义：[UnifiedPluginRegistry] 保持纯 JVM（LoadedPlugin 携带
 * ServiceConnection / IBinder 等 Android binder 类型，进入接口会把
 * Android 依赖与 AIDL 生命周期传染给统一注册表与其单测 fake）。
 *
 * 线程模型：stateIn 用 Eagerly——冷启动时 PluginManager 尚未加载任何
 * 插件，初始空 map 即终值；绑定回调后（往往在服务启动数秒内）流式对账。
 * 专用 SupervisorJob scope 隔离（收集协程失败不殊及宿主）。
 *
 * 装配：app 层 PluginModule 以 @Provides 构造（接口绑定单一入口，
 * 不用 @Binds——本模块已有 object 装配模块，避免再开 abstract 模块）。
 */
class DefaultApkPluginSource(
    pluginManager: PluginManager
) : ApkPluginSource {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val loadedApkPlugins: StateFlow<Map<String, String>> =
        pluginManager.loadedPlugins
            .map { plugins -> plugins.mapValues { (_, loaded) -> loaded.name } }
            .stateIn(scope, SharingStarted.Eagerly, emptyMap())
}
