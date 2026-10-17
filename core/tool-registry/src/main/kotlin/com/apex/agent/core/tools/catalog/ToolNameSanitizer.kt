package com.apex.agent.core.tools.catalog

/**
 * # Tool System v5 — Provider-safe tool name mapping（无分隔符 PascalCase）
 *
 * 背景：v4 之前注册表里混着 dotted id（`terminal.exec`）与 snake_case id
 * （`read_file`）。OpenAI 的函数名文法是 `^[a-zA-Z0-9_-]{1,64}$`，点号会让
 * **整个请求** 400（用户原话："直接发送对话会直接报错，还需要选择调用函数"）
 * —— 所以 v4 引入本对象把注册表 id 洗成 provider 合法名。
 *
 * v5 起注册表 id 自身就是无分隔符 PascalCase（`TerminalExec` / `Read` /
 * `McpGithubCreateIssue`），本对象退化为**第三方 id 的兜底层**：插件宏、
 * MCP 远端工具、技能自定义工具仍可能带 `-` / `_` / `.` / 空格 / 中文。
 *
 * - 非法字符按段切开后逐段首字母大写连写（`my-tool` → `MyTool`）——
 *   结果里既没有 `-` 也没有 `_`，同时保留可读的单词边界；
 * - 长度钳到 64（provider 上限）；
 * - 冲突确定性消解（非 legacy 优先、长 id 优先、字典序、纯数字后缀，
 *   后缀同样不含分隔符）；
 * - 反查把 provider 名映射回注册表 id；未命中时回落 [ToolIdAliases]，
 *   这样模型回显改名前的旧工具名（read_file / terminal.exec）依然可执行。
 */
object ToolNameSanitizer {

    private val NON_ALNUM = Regex("[^A-Za-z0-9]")
    private const val MAX_NAME_LENGTH = 64
    private const val FALLBACK_NAME = "Tool"

    /**
     * Build the full bidirectional mapping for a set of registry ids.
     *
     * @param registryIds every id that could enter a request this session.
     * @return mapping of registry id → provider-safe name. Keys that map to
     *         the same sanitized name are disambiguated deterministically;
     *         [ToolTierPolicy.LEGACY_ALIAS_IDS] ids lose their slot to the
     *         modern tool.
     */
    fun buildMapping(registryIds: Collection<String>): Map<String, String> {
        val ordered = registryIds.sortedWith(
            compareByDescending<String> { ToolTierPolicy.isLegacyAlias(it).not() }
                .thenByDescending { it.length }
                .thenBy { it }
        )
        val result = linkedMapOf<String, String>()
        val taken = mutableSetOf<String>()
        for (id in ordered) {
            var name = sanitize(id)
            if (name in taken) {
                // Deterministic disambiguation: append 2, 3, … while keeping
                // within the 64-char ceiling and free of separators.
                var suffix = 2
                var candidate: String
                do {
                    candidate = name.take(MAX_NAME_LENGTH - suffix.toString().length) + suffix
                    suffix++
                } while (candidate in taken)
                name = candidate
            }
            taken += name
            result[id] = name
        }
        return result
    }

    /** Sanitize a single id into a separator-free PascalCase name (no
     *  de-duplication). Clamped to the 64-char provider ceiling. */
    fun sanitize(id: String): String {
        val parts = id.split(NON_ALNUM).filter { it.isNotEmpty() }
        if (parts.isEmpty()) return FALLBACK_NAME
        val builder = StringBuilder(id.length)
        parts.forEach { part -> builder.append(part.replaceFirstChar { it.uppercaseChar() }) }
        return builder.toString().take(MAX_NAME_LENGTH).ifEmpty { FALLBACK_NAME }
    }

    /**
     * Reverse lookup helper: find the registry id whose provider name is
     * [providerName]. Falls back to [ToolIdAliases] (a model may echo a
     * pre-rename id), then to the raw value.
     */
    fun resolveRegistryId(providerName: String, mapping: Map<String, String>): String {
        mapping.entries.firstOrNull { it.value == providerName }?.let { return it.key }
        mapping.entries.firstOrNull { it.value.equals(providerName, ignoreCase = true) }
            ?.let { return it.key }
        return ToolIdAliases.canonical(providerName)
    }
}
