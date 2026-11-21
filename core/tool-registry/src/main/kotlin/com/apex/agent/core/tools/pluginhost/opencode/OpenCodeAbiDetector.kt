package com.apex.agent.core.tools.pluginhost.opencode

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * # OpenCodeAbiDetector —— V1/V2 ABI 探测与入口解析（spec §4.1/§4.3）
 *
 * 官方协议事实（2026-11 审计，docs/build/plugins + migrate-v1）：
 *
 * | | V1（OpenCode 1.x） | V2（现行） |
 * |--|--|--|
 * | SDK 包 | `@opencode-ai/plugin`（**仅类型**，运行时零依赖） | `@opencode/plugin`（Plugin.define 工厂） |
 * | 导出形状 | 具名异步函数，接收 `{directory, project, client}`，返回 hook map | default export `Plugin.define({id, setup(ctx)})` |
 * | 工具注册 | hook map 里的工具条目 | `ctx.tool.transform(editor => editor.add({name, description, input, execute}))` |
 * | 钩子 | `"tool.execute.before": async (input, output) => {}` | `ctx.tool.hook("execute.before", cb)` / `ctx.session.hook(...)` |
 *
 * 探测规则（不允许按包名瞎猜 —— spec 禁止事项）：
 * 1. package.json `dependencies`/`peerDependencies`/`devDependencies` 含
 *    `@opencode/plugin` → V2；含 `@opencode-ai/plugin` → V1；
 * 2. 二者皆无 → UNKNOWN（加载器按导出形状二次探测后仍不明则拒绝）；
 * 3. `type: "module"` / exports map 决定模块格式与入口。
 */
object OpenCodeAbiDetector {

    /** 探测结果。 */
    data class Abi(
        val version: OpenCodeVersion,
        /** 探测依据（人读，进兼容性报告）。 */
        val evidence: String,
        /** package.json type 字段（module → ESM）。 */
        val moduleFormat: String
    )

    enum class OpenCodeVersion { V1, V2, UNKNOWN }

    /** SDK 包名常量（探测与 shim 落盘共用）。 */
    object Sdks {
        const val V2_PACKAGE = "@opencode/plugin"
        const val V1_PACKAGE = "@opencode-ai/plugin"
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 从 package.json 文本探测 ABI（文本缺失/损坏 → UNKNOWN + 证据说明）。 */
    fun detect(packageJsonText: String?): Abi {
        if (packageJsonText.isNullOrBlank()) {
            return Abi(OpenCodeVersion.UNKNOWN, "package.json 缺失", "unknown")
        }
        val pkg = runCatching { json.parseToJsonElement(packageJsonText).jsonObject }
            .getOrElse { return Abi(OpenCodeVersion.UNKNOWN, "package.json 解析失败", "unknown") }
        val type = pkg.str("type").ifBlank { "commonjs" }
        val deps = buildSet {
            for (key in listOf("dependencies", "peerDependencies", "devDependencies")) {
                (pkg[key] as? JsonObject)?.keys?.let(::addAll)
            }
        }
        return when {
            Sdks.V2_PACKAGE in deps -> Abi(
                OpenCodeVersion.V2,
                "dependencies 声明 ${Sdks.V2_PACKAGE}（V2 SDK）",
                if (type == "module") "esm" else "cjs"
            )
            Sdks.V1_PACKAGE in deps -> Abi(
                OpenCodeVersion.V1,
                "dependencies 声明 ${Sdks.V1_PACKAGE}（V1 仅类型包）",
                if (type == "module") "esm" else "cjs"
            )
            else -> Abi(
                OpenCodeVersion.UNKNOWN,
                "未声明任何 OpenCode SDK 依赖（须靠导出形状二次探测）",
                if (type == "module") "esm" else "cjs"
            )
        }
    }

    /**
     * 解析插件真实入口（spec §4.3：不能只读 main 就当找到入口）。
     *
     * 优先级（对齐官方 exports 语义）：
     * 1. `exports["."]`（字符串或 `{import/default}` 条件对象）；
     * 2. `main`；3. `module`；4. 兜底 index.js / index.mjs / index.cjs。
     * 目录形态的 exports 值（如 `"./src"`）拼回相对路径再探文件。
     */
    fun resolveEntry(pkgDir: File, packageJsonText: String?): File? {
        val pkg = packageJsonText
            ?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
        val candidates = mutableListOf<String>()
        (pkg?.get("exports") as? JsonObject)?.get(".")?.let { dot ->
            when (dot) {
                is kotlinx.serialization.json.JsonPrimitive -> candidates += dot.content
                is JsonObject -> {
                    (dot["import"] as? kotlinx.serialization.json.JsonPrimitive)?.let { candidates += it.content }
                    (dot["default"] as? kotlinx.serialization.json.JsonPrimitive)?.let { candidates += it.content }
                }
                else -> Unit
            }
        }
        pkg?.str("main")?.takeIf { it.isNotBlank() }?.let { candidates += it }
        pkg?.str("module")?.takeIf { it.isNotBlank() }?.let { candidates += it }
        candidates += listOf("index.js", "index.mjs", "index.cjs", "index.ts")
        for (rel in candidates) {
            val direct = File(pkgDir, rel)
            if (direct.isFile) return direct
            // 目录形态入口：补默认 index
            for (index in listOf("index.js", "index.mjs", "index.cjs", "index.ts")) {
                val nested = File(direct, index)
                if (nested.isFile) return nested
            }
        }
        return null
    }

    private fun JsonObject.str(key: String): String =
        runCatching { this[key]?.jsonPrimitive?.content }.getOrNull() ?: ""
}
