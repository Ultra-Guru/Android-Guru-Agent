package com.apex.agent.core.tools.skill

/**
 * # Skill 命名（v5：无分隔符 PascalCase）
 *
 * 与工具 / MCP 同源的用户规格：**技能名里不出现 `-` 与 `_`**。官方技能仓库
 * （apex-skill-hub）132 个技能已全量迁移（`ab-test-verdict` → `AbTestVerdict`）；
 * 第三方源（ClawHub / ModelScope / 手写 manifest / ZIP 包）仍可能带 kebab 或
 * snake 名，因此安装链路统一走 [canonicalId] 归一后再落盘：
 *
 * - 归一：按非字母数字切段 → 每段首字母大写连写（`my-skill` → `MySkill`）；
 * - 已安装的旧 id（老设备上的 kebab 目录 / .disabled sidecar）在读取时同样归一，
 *   于是"旧装技能"与"新装技能"在内存里是同一把键，不会出现两份并存；
 * - [isSafeId] 只管路径安全（字母数字、1..128、非空），保留对历史 id 的宽容。
 */
object SkillNaming {

    private val NON_ALNUM = Regex("[^A-Za-z0-9]")
    private const val MAX_ID_LENGTH = 128

    /** 归一为无分隔符 PascalCase id；无法归一时返回空串。 */
    fun canonicalId(raw: String): String =
        raw.trim().split(NON_ALNUM)
            .filter { it.isNotEmpty() }
            .joinToString("") { it.replaceFirstChar { ch -> ch.uppercaseChar() } }

    /** 是否是"已经是规范 id"（PascalCase、无分隔符）。 */
    fun isCanonical(raw: String): Boolean = raw.isNotEmpty() && raw == canonicalId(raw)

    /** 路径安全：非空、≤128、仅字母数字（防路径穿越）。 */
    fun isSafeId(id: String): Boolean =
        id.isNotBlank() && id.length <= MAX_ID_LENGTH && id.all { it.isLetterOrDigit() }
}
