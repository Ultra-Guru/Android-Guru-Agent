package com.apex.agent.plugin.host.functional

import com.apex.agent.plugin.api.PluginKind
import javax.inject.Qualifier
import kotlinx.coroutines.flow.StateFlow

/**
 * # Plugin System v2 统一类型（跨形态共享的词汇）
 *
 * 双类型插件体系（[PluginKind]）里，功能性插件与 APK 插件的宿主侧设施
 * 完全不同（文件目录 vs AIDL 绑定），但市场 UI / 斜杠菜单 / 启动接线需要
 * 一套**统一词汇**：状态机、目录条目、命令贡献。本文件集中定义，避免
 * 这些小类型散落进各自实现类。
 */

/**
 * 统一插件状态（两种形态共用）：
 * - [INSTALLED]：已安装未激活（功能插件禁用态 / APK 已发现未绑定）；
 * - [ACTIVE]：激活中（功能插件工具宏已注册进 ToolRegistry / APK 已绑定注册）；
 * - [BROKEN]：安装数据损坏（manifest 不可解析 / 形状非法）——如实展示，
 *   宿主不做猜测性加载（fail closed）。
 */
enum class UnifiedPluginState { INSTALLED, ACTIVE, BROKEN }

/**
 * 统一目录条目（市场「已安装 · 插件」页的一行）。
 *
 * [id]：功能性插件 = manifest id；APK 插件 = 包名（两域不重叠——功能性
 * id 受命名规范与宿主命名空间保留约束，包名恒以反域名形式存在）。
 */
data class UnifiedPluginEntry(
    val id: String,
    val kind: PluginKind,
    val name: String,
    val description: String,
    /** 展示版本（versionName 优先）。 */
    val version: String,
    val state: UnifiedPluginState,
    /** 启停态（仅功能性插件有意义；APK 恒 true——其启停 = 绑定/解绑）。 */
    val enabled: Boolean = true,
    /** 状态补充说明（BROKEN 原因 / 激活拒绝原因）。 */
    val detail: String? = null
)

/**
 * APK 插件侧门面：把 [com.apex.agent.plugin.host.PluginManager]（Android
 * 依赖：Context / PackageManager IPC / AIDL）挡在纯 JVM 的
 * [UnifiedPluginRegistry] 之外——统一注册表只消费「已加载 APK 插件」
 * 快照流，测试用简单 fake 即可（仓库纪律：无 mock 框架，手写 fake）。
 *
 * 生产绑定见 app 层 PluginModule：包装 PluginManager.loadedPlugins。
 */
interface ApkPluginSource {
    /** 已加载（绑定 + 工具注册中）的 APK 插件：包名 → 展示名。 */
    val loadedApkPlugins: StateFlow<Map<String, String>>
}

/**
 * 宏步骤执行器的 Hilt 限定符：功能性插件的宏步骤经 ToolExecutor 管线
 * 执行（查找建议 / schema 校验 / 错误折叠），**不带 UI 门控**——外层宏
 * 工具调用的风险门已是安全边界（宏风险按步骤最大值钳制）。app 层用
 * ToolExecutorBuilder(registry).build() 绑定，避免与引擎执行器的既有
 * 绑定冲突（那些带完整权限 / 风险 / 钩子门）。
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MacroStepExecutor

/**
 * 功能性插件的斜杠指令贡献（已激活插件的快照，斜杠菜单插件类目数据源）：
 * [commandId] 进入 `/plugin:<commandId>` 路由；[pluginId] 用于来源徽章
 * 与（禁用插件后的）失效判定。
 */
data class FunctionalSlashCommand(
    val commandId: String,
    val pluginId: String,
    val pluginName: String,
    val title: String,
    val description: String,
    /** 路由注入的 agent 指令文本（空 = 通用插件路由文案）。 */
    val prompt: String
)
