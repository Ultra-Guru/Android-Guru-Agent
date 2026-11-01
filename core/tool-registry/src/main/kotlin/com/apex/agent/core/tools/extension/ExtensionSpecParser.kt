package com.apex.agent.core.tools.extension

/**
 * # Extension Spec 解析器 —— 统一安装入口的「认路」语法（纯函数，零 IO）
 *
 * 用户/模型只需要学一种语法，底层按 spec 形态路由到对应适配器：
 *
 * | spec 形态 | 类型 | 说明 |
 * |---|---|---|
 * | `skill:<url>` / `skill:json:{…}` / `skill:<本地路径>` | skill | apex-skill-v1 manifest |
 * | `mcp:{…}`（JSON 字面量）/ `mcp:import:<文件>` / `mcp:<目录名>` | mcp_connector | register/import 语义分离 |
 * | `claude:<name>@<marketplace>` / `claude:<zip路径|仓库URL>` | claude_plugin | PR-B |
 * | `dsh:<pkg>` / `dsh:github:owner/repo` | dsh_plugin | PR-B |
 * | `npm:<pkg>`（`dsh-*` 前缀自动判 DSH） | opencode/dsh_plugin | PR-B |
 * | `github:owner/repo` | 按仓库内容判定（适配器探测） | PR-B |
 * | `apk:<路径>` | apk_package | PR-C |
 * | 裸 `https://…json` | 按扩展名猜测（skill） | 尽力而为 |
 *
 * 解析只产出 [SpecDraft]（类型判定 + 载荷提取），不做网络/文件访问 ——
 * 真正的 resolve（抓 manifest / 查目录）由各 [ExtensionAdapter] 负责。
 */
object ExtensionSpecParser {

    /** 解析产物：类型判定 + 交给适配器的载荷。 */
    data class SpecDraft(
        /** 用户输入的 spec 原文（trim 后）。 */
        val raw: String,
        /** 判定的扩展类型（[ExtensionTypes] 常量；未知来源为 null → 适配器探测）。 */
        val type: String?,
        /** 提取的载荷（去前缀后的有效部分）。 */
        val payload: String,
        /** 来源类别猜测（[SourceKinds]；未知 null）。 */
        val sourceKind: String? = null
    )

    /** npm 包名合法形态（scoped @scope/name 与普通 name；防注入 —— 只放行白名单字符）。 */
    private val NPM_NAME = Regex("^(@[a-z0-9][a-z0-9._-]*/)?[a-z0-9][a-z0-9._-]*$")

    fun parse(input: String): Result<SpecDraft> {
        val spec = input.trim()
        if (spec.isEmpty()) {
            return Result.failure(IllegalArgumentException("spec 为空 —— 给出来源串（如 mcp:memory / skill:<url> / npm:<包名>）"))
        }
        val scheme = spec.substringBefore(':', "").lowercase()
        val rest = spec.removePrefix("$scheme:").trim()
        return when (scheme) {
            "skill" -> parseSkill(rest, spec)
            "mcp" -> parseMcp(rest, spec)
            "claude" -> parseClaude(rest, spec)
            "dsh" -> Result.success(SpecDraft(spec, ExtensionTypes.DSH_PLUGIN, rest, SourceKinds.NPM.takeIf { isNpmName(rest) } ?: SourceKinds.GITHUB.takeIf { rest.startsWith("github:") }))
            "npm" -> Result.success(
                if (!isNpmName(rest)) {
                    SpecDraft(spec, null, rest) // 非法 npm 名 → 适配器侧诚实报错
                } else {
                    // brief：npm 包名自动判断 OpenCode / DSH（dsh-* 前缀 → DSH）
                    SpecDraft(
                        spec,
                        if (rest.startsWith("dsh-")) ExtensionTypes.DSH_PLUGIN else ExtensionTypes.OPENCODE_PLUGIN,
                        rest,
                        SourceKinds.NPM
                    )
                }
            )
            "github" -> Result.success(SpecDraft(spec, null, rest, SourceKinds.GITHUB))
            "apk" -> Result.success(SpecDraft(spec, ExtensionTypes.APK_PACKAGE, rest, SourceKinds.LOCAL))
            "http", "https" -> Result.success(
                SpecDraft(
                    spec, ExtensionTypes.SKILL.takeIf { spec.substringBefore('?').endsWith(".json") },
                    spec, SourceKinds.URL
                )
            )
            else -> Result.failure(
                IllegalArgumentException(
                    "无法识别的 spec 前缀 '$scheme' —— 支持：skill: / mcp: / claude: / dsh: / npm: / github: / apk: / https URL"
                )
            )
        }
    }

    private fun parseSkill(rest: String, raw: String): Result<SpecDraft> = when {
        rest.isEmpty() -> Result.failure(IllegalArgumentException("skill: 后需要 URL、json: 字面量或本地路径"))
        rest.startsWith("json:", ignoreCase = true) ->
            Result.success(SpecDraft(raw, ExtensionTypes.SKILL, rest.removePrefix("json:").trim(), SourceKinds.JSON))
        rest.startsWith("http://") || rest.startsWith("https://") ->
            Result.success(SpecDraft(raw, ExtensionTypes.SKILL, rest, SourceKinds.URL))
        else -> Result.success(SpecDraft(raw, ExtensionTypes.SKILL, rest, SourceKinds.LOCAL))
    }

    private fun parseMcp(rest: String, raw: String): Result<SpecDraft> = when {
        rest.isEmpty() -> Result.failure(IllegalArgumentException("mcp: 后需要 JSON 字面量、import:<文件> 或目录名"))
        rest.startsWith("{") ->
            Result.success(SpecDraft(raw, ExtensionTypes.MCP_CONNECTOR, rest, SourceKinds.JSON))
        rest.startsWith("import:", ignoreCase = true) ->
            Result.success(SpecDraft(raw, ExtensionTypes.MCP_CONNECTOR, rest.removePrefix("import:").trim(), SourceKinds.LOCAL))
        else -> Result.success(SpecDraft(raw, ExtensionTypes.MCP_CONNECTOR, rest, SourceKinds.CATALOG))
    }

    private fun parseClaude(rest: String, raw: String): Result<SpecDraft> {
        if (rest.isEmpty()) {
            return Result.failure(IllegalArgumentException("claude: 后需要 <name>@<marketplace> 或 zip/仓库路径"))
        }
        // name@marketplace 形态（不含路径分隔符与协议）
        if ('@' in rest && !rest.contains("://") && !rest.contains('\\') && !rest.contains('/')) {
            return Result.success(SpecDraft(raw, ExtensionTypes.CLAUDE_PLUGIN, rest, SourceKinds.CATALOG))
        }
        val kind = when {
            rest.startsWith("http://") || rest.startsWith("https://") -> SourceKinds.URL
            rest.startsWith("github:") -> SourceKinds.GITHUB
            else -> SourceKinds.LOCAL
        }
        return Result.success(SpecDraft(raw, ExtensionTypes.CLAUDE_PLUGIN, rest, kind))
    }

    /** npm 包名白名单判定（防注入：适配器后续拼命令只允许过本检查的名字）。 */
    fun isNpmName(raw: String): Boolean = raw.isNotEmpty() && NPM_NAME.matches(raw)
}
