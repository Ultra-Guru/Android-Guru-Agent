package com.apex.agent.ui.screen.playground

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * ═══════════════════════════════════════════════════════════════
 *  游乐场插件契约 —— HTML 游戏插件的数据模型
 * ═══════════════════════════════════════════════════════════════
 *
 * 游乐场是插件化的：每个游戏 = 一个自包含 HTML 文件（内联 vendored
 * pretext，零网络请求）+ 同目录 `game.json` manifest。两种来源：
 *  - **BUILTIN**：随 APK 打包在 `assets/playground/games/<id>/`；
 *  - **INSTALLED**：运行时导入的单文件 HTML，落位
 *    `filesDir/playground/games/<id>/`（可卸载）。
 *
 * 同一 HTML 可以被拷出分发、或包进 WebView 壳 APK 插件
 * （转化路径见 playground/README.md 与 docs/playground-plugin-arcade.md）。
 *
 * 宽松解析（ignoreUnknownKeys + 全字段缺省）：未来 manifest 加字段，
 * 旧宿主不炸 —— 与功能性插件 PluginManifest 同一兼容哲学。
 */
@Serializable
data class GameManifest(
    /** 插件 id：小写字母数字与连字符（目录名即 id）。 */
    val id: String = "",
    /** 展示名（中文）。 */
    val name: String = "",
    /** 英文副题（可缺省）。 */
    @SerialName("nameEn") val nameEn: String? = null,
    /** 一句话玩法介绍。 */
    val tagline: String = "",
    /** manifest 版本（向上兼容判断用）。 */
    val version: Int = 1,
    /** 测量引擎标识（诚实展示位：如 pretext@0.0.9）。 */
    @SerialName("engine") val engine: String = "",
    /** 游戏真实调用的 pretext API 清单（目录卡片的「测量即玩法」徽章）。 */
    val apis: List<String> = emptyList(),
    /** 操作方式说明。 */
    val controls: String = "",
    /** 本地战绩 localStorage 键（文档性字段，宿主不消费）。 */
    @SerialName("scoreKey") val scoreKey: String? = null
)

/** 游戏来源：内置资产 or 运行时安装。 */
enum class GameSource { BUILTIN, INSTALLED }

/**
 * 目录条目：manifest + 加载入口 + 来源。
 *
 * @param entryUrl WebView 加载地址（file:// 协议；assets 与 filesDir 同构）。
 * @param installDir 安装目录（仅 INSTALLED；卸载 = 递归删除）。
 */
data class PlaygroundGame(
    val manifest: GameManifest,
    val source: GameSource,
    val entryUrl: String,
    val installDir: java.io.File? = null
) {
    /** 目录卡展示名：中文名 + 可选英文副题。 */
    val displayName: String
        get() = if (manifest.nameEn.isNullOrBlank()) {
            manifest.name.ifBlank { manifest.id }
        } else {
            "${manifest.name.ifBlank { manifest.id }} · ${manifest.nameEn}"
        }

    /** 引擎徽章：无 engine 字段的导入游戏显示「HTML 插件」。 */
    val engineBadge: String
        get() = manifest.engine.ifBlank { "HTML 插件" }
}

/** manifest 解析器：游戏目录专用宽松 JSON（未知字段忽略，坏 JSON 折叠为 null）。 */
object GameManifestParser {

    /** 游戏目录内的 manifest 文件名（与功能性插件的 plugin.json 命名先例对齐）。 */
    const val MANIFEST_FILE: String = "game.json"

    /** 解析 game.json 文本；坏形状返回 null（调用方如实标记跳过，不向上抛）。 */
    fun parse(text: String): GameManifest? = runCatching { json.decodeFromString<GameManifest>(text) }.getOrNull()

    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
}
