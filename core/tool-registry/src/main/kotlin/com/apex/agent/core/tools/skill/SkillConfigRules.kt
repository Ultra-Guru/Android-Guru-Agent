package com.apex.agent.core.tools.skill

/**
 * # Skill Config Rules — 技能配置就绪规则（纯函数）
 *
 * 用户需求：「没有配置好的不要在输入框 / 按钮里显示」。manifest 的
 * `configuration.userConfig` 声明了用户需填写的配置字段（如 API Key、
 * 端点地址）；必填字段在用户填值（或 manifest 自带默认值）齐备之前，
 * 技能视为「未配置好」—— 斜杠菜单 / 自动装备 / 渐进披露都不接它，
 * 市场卡片则以「需配置」徽标引导用户去配置。
 *
 * 判定规则（单字段就绪，全部字段就绪 = 技能就绪）：
 * 1. 字段声明 `required=false` → 恒就绪（可选配置项）；
 * 2. 已存用户值非空白 → 就绪（[SkillRegistry.setConfigValues] 持久化于
 *    `.config.json` sidecar，跨 manifest 升级存活）；
 * 3. manifest 默认值非空白 → 就绪（开箱即用）；
 * 4. 其余（必填、无默认、未填值）→ 未就绪。
 *
 * 纯 JVM、无状态；与 [SkillRegistry.isConfigured]（带存储读取的包装）分层。
 */
object SkillConfigRules {

    /**
     * 技能是否「配置好」。
     *
     * @param manifest 技能 manifest（取 configuration.userConfig 声明）
     * @param configValues 用户已填配置值（[SkillRegistry.getConfigValues] 快照）
     */
    fun isConfigured(manifest: SkillManifest, configValues: Map<String, String>): Boolean =
        missingRequiredFields(manifest, configValues).isEmpty()

    /**
     * 仍缺值的必填字段清单（市场「需配置」徽标 / 表单高亮用）。
     * 空列表 = 已配置好（或 manifest 未声明任何 userConfig）。
     */
    fun missingRequiredFields(
        manifest: SkillManifest,
        configValues: Map<String, String>
    ): List<UserConfigField> =
        manifest.configuration.userConfig.filterNot { field ->
            fieldSatisfied(field, configValues[field.key])
        }

    /** 单字段就绪判定（规则见类 KDoc）。 */
    private fun fieldSatisfied(field: UserConfigField, storedValue: String?): Boolean {
        if (!field.required) return true
        if (!storedValue.isNullOrBlank()) return true
        return !field.default.isNullOrBlank()
    }
}
