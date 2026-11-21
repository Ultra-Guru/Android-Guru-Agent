package com.apex.agent.core.tools.extension.dsh

import java.io.File

/**
 * # DshPackageLayout —— npm 布局的插件根定位
 *
 * NodeFamilyInstaller 的 npm 安装把包落在 `<pkg>/node_modules/<name>/`（前缀
 * 目录即发布体，锁文件/完整性面保留在发布根）—— 而 analyzer / introspector /
 * entryHint 消费的「插件根」是包本体目录。本定位器统一规则，三处共用，
 * **禁止各调用点自写探测**（单一事实来源，同 ExtensionDirLayout 的公共助手纪律）：
 *
 * - pkg 根有 package.json → 根即插件根（本地目录安装 / github clone 布局）；
 * - 否则在 node_modules 下找**唯一**带 package.json 的包目录（npm 单包安装
 *   布局，含 scoped `@scope/name` 两级）；
 * - 多包/无包 → 诚实抛错（绝不猜）。
 */
object DshPackageLayout {

    fun pluginRootOf(pkg: File): File {
        if (File(pkg, "package.json").isFile) return pkg
        val modules = File(pkg, "node_modules")
        if (!modules.isDirectory) {
            throw IllegalStateException("插件根定位失败：$pkg 无 package.json 且无 node_modules（非 npm 安装产物？）")
        }
        val direct = modules.listFiles()
            ?.filter { it.isDirectory && File(it, "package.json").isFile }
            .orEmpty()
        val scoped = modules.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("@") }
            ?.flatMap { scope -> scope.listFiles()?.filter { it.isDirectory && File(it, "package.json").isFile }.orEmpty() }
            .orEmpty()
        return (direct + scoped).distinct().singleOrNull()
            ?: throw IllegalStateException(
                "插件根定位失败：node_modules 下非唯一插件包（direct=${direct.size}, scoped=${scoped.size}）—— npm 单包契约被破坏"
            )
    }
}
