package com.apex.agent.di

import com.apex.agent.core.tools.extension.ExtensionDirLayout
import com.apex.agent.core.tools.extension.InstallLifecycleHook
import com.apex.agent.core.tools.extension.ExtensionManifest
import com.apex.agent.core.tools.extension.ExtensionTypes
import com.apex.agent.core.tools.extension.ExtensionRegistry
import com.apex.agent.core.tools.extension.InstallOutcome
import com.apex.agent.core.tools.extension.dsh.DshToolRegistrar
import com.apex.agent.core.tools.pluginhost.opencode.OpenCodePluginLoader
import javax.inject.Inject
import javax.inject.Singleton

/**
 * # PluginRuntimeWiring —— 装后/卸前钩子的生产实现（plugin-host-2.0 Phase 6 收尾）
 *
 * 核查结论（docs/plugin-host-2.0.md「Phase 6 之后」清单第三条）：
 * `OpenCodePluginLoader.load()` 在 `app/src/main` 下 0 生产调用点——组件齐、
 * 测试绿，缺一个启动按钮。本类经 [ExtensionInstaller.InstallLifecycleHook]
 * 把按钮接上：
 *
 * - **opencode_plugin 装后**：`OpenCodePluginLoader.load()`（ABI 探测 → 入口
 *   解析 → SDK 垫片 → Worker 启动 → `PluginToolBridge.bind` 一等工具注册）；
 * - **dsh_plugin 装后**：`DshToolRegistrar.registerPlugin()`（能力快照的
 *   observed 工具 → 一等注册，冷启动执行）；
 * - **卸前**：opencode 停加载器（注销工具 + 退订事件 + 停 Worker）、dsh
 *   注销工具——先于目录清除（cwd/文件还在）。
 *
 * 失败语义（钩子契约）：启动失败**不炸安装**——安装是既成事实，notes
 * 如实呈现失败原因，模型可经 PluginHostDoctor 诊断后重装重试。
 */
@Singleton
class PluginRuntimeWiring @Inject constructor(
    private val layout: ExtensionDirLayout,
    private val opencodeLoader: OpenCodePluginLoader,
    private val dshRegistrar: DshToolRegistrar
) : InstallLifecycleHook {

    override suspend fun onInstalled(
        manifest: ExtensionManifest,
        outcome: InstallOutcome
    ): List<String> = when (manifest.type) {
        ExtensionTypes.OPENCODE_PLUGIN -> startOpencode(manifest)
        ExtensionTypes.DSH_PLUGIN -> dshRegistrar.registerPlugin(manifest.id)
        else -> emptyList() // skill/mcp/claude/apk 无 Plugin Host 运行时语义
    }

    override suspend fun onUninstalling(record: ExtensionRegistry.Record) {
        when (record.type) {
            ExtensionTypes.OPENCODE_PLUGIN -> opencodeLoader.stop(record.id)
            ExtensionTypes.DSH_PLUGIN -> dshRegistrar.unregisterPlugin(record.id)
        }
    }

    private suspend fun startOpencode(manifest: ExtensionManifest): List<String> {
        val pkgDir = layout.pkgDir(manifest.id)
        if (!pkgDir.isDirectory) {
            return listOf("自动启动跳过：pkg 目录缺失（${manifest.id}）")
        }
        return opencodeLoader.load(manifest.id, pkgDir).fold(
            onSuccess = { loaded ->
                buildList {
                    add(
                        "插件已自动启动（${loaded.ecosystem.name} / ABI ${loaded.abi.version}" +
                            "，入口 ${loaded.entry.name}）—— Worker ACTIVE"
                    )
                    if (loaded.registeredTools.isNotEmpty()) {
                        add("一等工具已注册：${loaded.registeredTools.joinToString(", ")}")
                    } else {
                        add("插件未声明工具（钩子/事件型插件）")
                    }
                    if (loaded.rejectedTools.isNotEmpty()) {
                        add(
                            "工具被拒（与内置撞名，第三方数据永不覆盖内置）：" +
                                loaded.rejectedTools.joinToString(", ") { it.toolName }
                        )
                    }
                }
            },
            onFailure = {
                listOf(
                    "自动启动失败（安装已成功，PluginHostDoctor 可诊断；重新 ExtensionInstall 幂等重试）：" +
                        "${it.message ?: it::class.simpleName}"
                )
            }
        )
    }
}
