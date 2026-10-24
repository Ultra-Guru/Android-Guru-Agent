package com.apex.agent.core.tools.fusion

import com.apex.agent.core.tools.mcp.McpServerConfig
import com.apex.agent.core.tools.skill.SkillManifest
import kotlinx.serialization.json.JsonObject

/**
 * # FusionBundle 组合器 —— 用户自定义聚合包的构建面
 *
 * 「1 个技能是聚合，5 个技能 + 2 台 MCP + 1 个插件混搭也是聚合」的落地：
 * 把用户在组合 UI 勾选的成员（已装技能 manifest / 已配 MCP 服务器 /
 * 已装功能插件原文）拼装成一份 fusion-bundle-v1 manifest，经
 * [FusionBundleRegistry.install] 落盘后即与内置包同权 —— 王冠菜单选用、
 * 一键激活、强制使用协议全链路复用，零新概念。
 *
 * 纯函数集合（无 IO / 无 Android 依赖，单测直测）：
 * - [build]：成员 → 完整 manifest（id 生成 / 版本递增 / 描述自动拼装）；
 * - [slugify]：用户可读名 → 合法 bundle id（ASCII 收敛，中文回退时间戳）；
 * - [nextVersion]：编辑重存时的版本递增（注册表按 version 字符串判升级）。
 */
object FusionBundleComposer {

    /** 自建包 id 前缀（与内置预置 id 空间自然区分，卸载不受 bundled 保护）。 */
    const val CUSTOM_ID_PREFIX = "custom-"

    /**
     * 组装一份自建聚合包 manifest。
     *
     * @param bundleId 包 id（新建用 [slugify] 生成 / 编辑沿用原 id）
     * @param name 展示名（用户输入）
     * @param persona agent 人设（可空 —— 空则激活提示退化为通用强制使用协议）
     * @param skills 勾选的技能完整 manifest（内联进 skills[]）
     * @param mcpServers 勾选的 MCP 服务器配置（转 FusionMcpServerRef 内联）
     * @param plugins 勾选的功能插件（id + plugin.json 原文解析出的 JsonObject）
     * @param skillPicks 官方仓库技能引用（安装型聚合：安装时按需下载）
     * @param mcpPicks 官方仓库 MCP 引用（安装型聚合：安装时按需写配置）
     * @param version 版本号（新建 "1"；编辑用 [nextVersion] 递增）
     */
    fun build(
        bundleId: String,
        name: String,
        persona: String,
        skills: List<SkillManifest>,
        mcpServers: List<McpServerConfig>,
        plugins: List<Pair<String, JsonObject>>,
        skillPicks: List<String> = emptyList(),
        mcpPicks: List<String> = emptyList(),
        version: String = "1"
    ): FusionBundleManifest = FusionBundleManifest(
        id = bundleId,
        name = name.trim().ifEmpty { bundleId },
        version = version,
        description = describe(skills.size + skillPicks.size, mcpServers.size + mcpPicks.size, plugins.size),
        trustLevel = "community",
        bundled = false,
        scope = "all",
        agent = FusionBundleAgent(
            persona = persona.trim(),
            activationPrompt = if (persona.isBlank()) "" else
                "以「${persona.trim()}」的角色执行任务，优先使用本聚合包的成员资源。"
        ),
        skills = skills.map { FusionSkillRef(skillId = it.id, manifest = it) },
        mcpServers = mcpServers.map { it.toFusionRef() },
        plugins = plugins.map { (id, raw) -> FusionPluginRef(pluginId = id, manifest = raw) },
        skillPicks = skillPicks,
        mcpPicks = mcpPicks
    )

    /** manifest → 安装用 JSON 字符串（注册表入参形态）。 */
    fun toJson(manifest: FusionBundleManifest): String =
        FusionBundleManifest.json.encodeToString(FusionBundleManifest.serializer(), manifest)

    /**
     * 用户可读名 → 合法 bundle id：ASCII 字母数字保留、空白折中划线、
     * 其余丢弃；结果为空或非法（如以中划线开头）时回退 `custom-<时间戳36>`。
     */
    fun slugify(name: String, nowMillis: Long = System.currentTimeMillis()): String {
        val slug = name.trim().lowercase()
            .map { c -> if (c.isLetterOrDigit() && c.code < 128) c else if (c.isWhitespace()) '-' else null }
            .filterNotNull()
            .joinToString("")
            .trim('-')
            .take(48)
        return if (slug.isNotEmpty() && slug.first().isLetterOrDigit()) {
            CUSTOM_ID_PREFIX + slug
        } else {
            CUSTOM_ID_PREFIX + java.lang.Long.toString(nowMillis, 36)
        }
    }

    /** 编辑重存的版本递增：数字尾 +1，非数字从 1 起算（version 字符串不等即升级覆盖）。 */
    fun nextVersion(current: String): String {
        val n = current.trim().toLongOrNull()
        return if (n != null) (n + 1).toString() else "1"
    }

    /** plugin.json 原文 → JsonObject（内联载体；解析失败返回 null 由调用方拒绝该成员）。 */
    fun parsePluginRaw(rawJson: String): JsonObject? = runCatching {
        val element = kotlinx.serialization.json.Json.parseToJsonElement(rawJson)
        element as? JsonObject
    }.getOrNull()

    /** MCP 配置 → 聚合内联引用（toMcpServerConfig 的逆向）。 */
    internal fun McpServerConfig.toFusionRef(): FusionMcpServerRef = FusionMcpServerRef(
        serverId = name,
        transport = transport.name.uppercase(),
        url = url,
        command = command,
        args = args,
        env = env,
        runInSandbox = runInSandbox,
        headers = headers,
        enabled = enabled,
        scope = scope,
        autoConnect = true
    )

    private fun describe(skills: Int, mcp: Int, plugins: Int): String {
        val parts = mutableListOf<String>()
        if (skills > 0) parts.add("$skills 技能")
        if (mcp > 0) parts.add("$mcp MCP")
        if (plugins > 0) parts.add("$plugins 插件")
        val body = if (parts.isEmpty()) "空聚合" else parts.joinToString(" · ")
        return "自建聚合包 · $body"
    }
}
