package com.apex.agent.core.tools.skill

/**
 * 为斜杠菜单提供动态数据
 * 从 SkillRegistry 实时读取已安装的 Skill/MCP/连接器/插件
 *
 * v2 修复：旧实现用 label 后缀 `"(未安装)"` 字符串编码状态，消费方
 * （SlashMenuProvider）用 `label.contains("未安装")` 反推——改文案即全线错乱，
 * 用户自建技能名字含"未安装"还会被误标。现在改为结构化字段 [SkillMenuItem.installed]。
 */
class SkillMenuProvider(
    private val skillRegistry: SkillRegistry
) {
    /**
     * 获取当前可用的 Skills 列表（仅启用的）。
     *
     * per-scope 启停 + 就绪门禁（用户需求：市场按工位独立开关；未配置好的
     * 不进菜单）：
     * - [scope] 传 "agent" / "coding" 时只返回该工位可用的技能；
     * - 必填配置未填齐的技能整体剔除（配置齐了 SkillRegistry 发 changes，
     *   菜单自动重建）；
     * - 逐项携带 [SkillMenuItem.enabledScopes]（生效工位集合 = manifest 声明 ∩
     *   per-scope 启用），消费方（SlashMenuData.forScope）据此让 Agent 屏与
     *   Coding 屏的 / 菜单各归各 —— 修「两屏同步显示」问题。
     */
    fun getActiveSkills(scope: String? = null): List<SkillMenuItem> {
        return skillRegistry.getInstalled()
            .map { skill ->
                SkillMenuItem(
                    id = skill.manifest.id,
                    label = skill.manifest.name,
                    command = "/skill:${skill.manifest.id} ",
                    description = skill.manifest.description,
                    installed = true,
                    // 生效工位 = manifest 声明 ∩ per-scope 启用（空集 = 两屏都不可用）
                    enabledScopes = skillRegistry.effectiveScopesOf(skill.manifest.id)
                )
            }
            .filter { item ->
                // 就绪门禁：必填配置未填齐的技能不进菜单（配置齐了
                // SkillRegistry 发 changes，菜单自动重建条目即刻出现）
                skillRegistry.isConfigured(item.id) &&
                    // 工位过滤：指定 scope 时只留该工位生效的条目
                    (scope.isNullOrBlank() || scope in item.enabledScopes)
            }
    }

    /**
     * 获取内置模板（未安装但可安装的）。
     *
     * Issue #166 历史兼容保留：v1.1 起内置优质技能改由 APK assets
     * （assets/skills 目录，见 SkillRegistry.installBundled 与 SkillModule 首启释放）
     * 提供并自动预装；本硬编码模板清单仅为兼容旧版「模板安装」入口与
     * MarketInstallManager.installSkillTemplate 通道而原样保留，不再扩充。
     * 注意：两者 id 命名空间已隔离（本清单为 web_scraper 等 5 个旧 id，
     * assets 侧为 commit-message 等新 id，无冲突）。
     */
    fun getBuiltinTemplates(): List<SkillMenuItem> {
        val installedIds = skillRegistry.getInstalled().map { it.manifest.id }.toSet()
        return BUILTIN_TEMPLATES
            .filter { it.id !in installedIds }
            .map { t ->
                SkillMenuItem(
                    id = t.id,
                    label = t.name,
                    command = "/skill:${t.id} ",
                    description = t.description,
                    installed = false
                )
            }
    }

    companion object {
        // 公开给市场页（MarketScreen）复用同一份清单，避免与斜杠菜单两处漂移
        val BUILTIN_TEMPLATES = listOf(
            BuiltinTemplate("web_scraper", "网页数据爬取", "从网页提取结构化数据", "WEB", listOf("web", "scrape", "extract")),
            BuiltinTemplate("file_organizer", "文件自动整理", "按类型/日期自动分类整理文件", "FILE", listOf("file", "organize", "automation")),
            BuiltinTemplate("code_runner", "代码运行器", "编写并运行代码，自动修复错误", "SHELL", listOf("code", "execute", "python")),
            BuiltinTemplate("data_analyzer", "数据分析", "分析 CSV/JSON 数据，生成统计报告", "UTILITY", listOf("data", "analysis", "csv")),
            BuiltinTemplate("coding_principles", "编码原则 (Karpathy)", "AI 编程协作九原则，约束 Agent 编码行为", "AGENT", listOf("prompt", "coding", "principles"))
        )
    }

    data class BuiltinTemplate(
        val id: String,
        val name: String,
        val description: String,
        /** 市场分类（镜像 ToolCategory 枚举名）。 */
        val category: String? = null,
        val tags: List<String> = emptyList()
    )
}

data class SkillMenuItem(
    val id: String,
    val label: String,
    val command: String,
    val description: String = "",
    /** 是否已安装（消费方渲染角标/状态用，替代旧 label 字符串协议）。 */
    val installed: Boolean = true,
    /**
     * 生效工位集合（"agent"/"coding" 子集；manifest 声明 ∩ per-scope 启用）。
     * SlashMenuData.forScope 据此与 manifest scope 双重过滤 —— Agent 屏与
     * Coding 屏的 / 菜单各归各（市场里只在 Coding 分级开启的技能不再出现在
     * Agent 屏菜单）。默认双工位（MCP/插件/连接器等无 per-scope 启停概念的条目）。
     */
    val enabledScopes: Set<String> = setOf(SkillRegistry.SCOPE_AGENT, SkillRegistry.SCOPE_CODING)
)
