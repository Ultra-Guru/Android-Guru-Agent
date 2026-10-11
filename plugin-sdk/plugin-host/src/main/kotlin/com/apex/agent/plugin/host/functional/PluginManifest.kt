package com.apex.agent.plugin.host.functional

import com.apex.agent.plugin.api.PluginContract
import com.apex.agent.plugin.api.PluginKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * # 功能性插件 manifest 模型（plugin.json）
 *
 * Plugin System v2 的统一插件描述（[docs/plugin-system-v2.md]）。功能性插件
 * （[PluginKind.FUNCTIONAL]）是**纯数据**：一个 plugin.json 描述插件身份 +
 * 「贡献点」（contribution points，类比 coding 平台扩展的 contributes 段）。
 * 宿主把贡献物适配成进程内能力——工具宏 / 斜杠指令；manifest 里永不出现
 * 代码或可执行引用。
 *
 * 解析纪律（对齐仓库「防御式 IO」）：
 * - **宽松 schema**：`ignoreUnknownKeys = true` + 全字段缺省——未来贡献点
 *   （prompts / hooks / ui）新增字段不破坏旧宿主，旧插件在新宿主下照常加载；
 * - **解析失败 = null**：非法 JSON 由调用方（[FunctionalPluginStore]）折成
 *   「单插件 BROKEN 隔离」，绝不让一个坏 manifest 炸掉整个目录扫描；
 * - **kind 自描述**：字段缺省按 [PluginKind.FUNCTIONAL]（安装目录已区分
 *   形态；未知值也永不按 APK 代码加载——fail-safe）。
 *
 * 字段语义与 APK 插件 metadata JSON（getMetadataJson）同源同词表：
 * id / name / version / versionName / minHostVersion / description ——
 * 两种形态的版本门（[PluginContract.HOST_API_VERSION] 比对）走同一实现。
 */
@Serializable
data class PluginManifest(
    /** 插件 id（功能性插件命名规范见 [PluginContract.FUNCTIONAL_ID_PATTERN]，安装期强校验）。 */
    val id: String = "",
    /** 插件形态自描述（FUNCTIONAL / APK）；缺省与未知值按 FUNCTIONAL。 */
    val kind: String? = null,
    /** 展示名（缺省回退 id）。 */
    val name: String = "",
    /** 递增版本号（升级比较用；同 id 新 version 覆盖旧安装）。 */
    val version: Int = 1,
    /** 语义化版本名（纯展示）。 */
    val versionName: String? = null,
    /** 所需最低宿主插件 API 版本（版本门，安装期拒绝过新插件）。 */
    val minHostVersion: Int = 1,
    /** 描述文案（市场卡片 / 详情展示）。 */
    val description: String = "",
    /** 作者（纯展示）。 */
    val author: String? = null,
    /** 搜索标签（纯展示）。 */
    val tags: List<String> = emptyList(),
    /** 工位作用域（"agent" / "coding" / "all"，缺省 all——注册侧经 ToolMetadata.normalizeScope 归一）。 */
    val scope: String? = null,
    /** 贡献点集合（工具宏 / 斜杠指令 / 预留提示词注入）。 */
    val contributions: PluginContributions = PluginContributions()
) {
    /** 解析后的形态（kind 字段宽松归一）。 */
    val resolvedKind: PluginKind get() = PluginKind.fromRaw(kind)

    /** 展示名（name 空白回退 id，UI 永不出现空标题）。 */
    val displayName: String get() = name.trim().ifEmpty { id }

    /** 展示版本（versionName 优先，回退 version 数字）。 */
    val displayVersion: String get() = versionName?.trim().takeUnless { it.isNullOrEmpty() } ?: version.toString()

    companion object {
        /** 宽松解析配置（未来字段向前兼容 + 形态防御）。 */
        private val lenientJson: Json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        /**
         * 宽松解析：非法 JSON / 非对象 → null（调用方折成 BROKEN 隔离），
         * 绝不向上抛——插件目录里任何一个坏文件都不能炸掉宿主扫描。
         */
        fun parseOrNull(raw: String): PluginManifest? = runCatching {
            lenientJson.decodeFromString<PluginManifest>(raw)
        }.getOrNull()
    }
}

/**
 * 贡献点集合。v2 落地 tools（宏工具）与 slashCommands（斜杠指令）两个
 * 贡献点；prompts 是**架构预留**（模型已定义、宿主暂不消费——引擎注入
 * 通道复用技能 promptInjection 的接线方式，见 docs/plugin-system-v2.md
 * 的扩展路线图）。未知贡献点字段被宽松忽略，不向前不兼容。
 */
@Serializable
data class PluginContributions(
    /** 工具宏贡献：每个条目适配成一个 [com.apex.agent.core.tools.AgentTool] 注册进 ToolRegistry。 */
    val tools: List<ToolContribution> = emptyList(),
    /** 斜杠指令贡献：进入斜杠菜单插件类目（/plugin:<id> 路由）。 */
    val slashCommands: List<SlashCommandContribution> = emptyList(),
    /** 提示词注入贡献（架构预留，v2 未消费）。 */
    val prompts: List<PromptContribution> = emptyList()
)

/**
 * 工具宏贡献。**宏 = 对既有宿主工具的声明式组合**：
 *
 * - `id`：注册进 ToolRegistry 的工具 id（与内置工具冲突时 REJECT——功能性
 *   插件是数据，永不覆盖二进制能力，对照 APK 插件的 REPLACE 语义）；
 * - `steps`：顺序执行的工具调用序列，步间经变量传递数据（[MacroStep]）；
 * - `parametersSchema`：声明给模型的入参 JSON Schema（缺省空对象——宽松
 *   schema 导入下等价「不校验」，对齐 APK 插件工具占位约定）。
 */
@Serializable
data class ToolContribution(
    val id: String = "",
    val name: String? = null,
    val description: String = "",
    /** 入参 JSON Schema 文本（值为 JSON 本身，与 APK 插件 parametersSchema 同构）。 */
    val parametersSchema: String? = null,
    /** 宏步骤序列（1..[PluginContract.MACRO_MAX_STEPS]，安装期强校验）。 */
    val steps: List<MacroStep> = emptyList()
) {
    /** 展示名（name 空白回退 id）。 */
    val displayName: String get() = name?.trim().takeUnless { it.isNullOrEmpty() } ?: id
}

/**
 * 宏步骤：一次对宿主工具的调用。
 *
 * - `tool`：目标工具 id（执行期经 ToolExecutor 管线解析——未注册则该步
 *   诚实失败，不静默跳过）；
 * - `args`：字面量参数对象（字符串值支持 `{{变量}}` 插值，见
 *   [MacroToolAdapter] 的变量规则）；
 * - `output`：可选变量名——本步完整输出捕获进变量域，供后续步骤插值引用。
 */
@Serializable
data class MacroStep(
    val tool: String = "",
    /** 字面量参数（字符串值可含插值占位符）。 */
    val args: JsonObject? = null,
    /** 输出捕获变量名（null = 不捕获）。 */
    val output: String? = null
)

/**
 * 斜杠指令贡献：进入斜杠菜单的插件类目，路由为 `/plugin:<id>`。
 * `prompt` 是注入给 agent 的指令模板（`{{input.field}}` 变量插值已列入
 * 路线图；v1 为字面量文本 + 指令参数追加，与 SlashCommandRouter 的既有
 * buildAgentPrompt 骨架对齐）。
 */
@Serializable
data class SlashCommandContribution(
    /** 指令 id（/plugin:<id> 的 id 段；小写字母数字与中划线，安装期校验）。 */
    val id: String = "",
    /** 菜单展示标题（缺省回退 id）。 */
    val title: String? = null,
    /** 菜单展示描述。 */
    val description: String? = null,
    /** 路由后的 agent 指令文本（空则退化为通用插件路由文案）。 */
    val prompt: String = ""
) {
    /** 展示标题（title 空白回退 id）。 */
    val displayTitle: String get() = title?.trim().takeUnless { it.isNullOrEmpty() } ?: id
}

/** 提示词注入贡献（架构预留：engine system prompt 追加段，v2 未消费）。 */
@Serializable
data class PromptContribution(
    val id: String = "",
    val description: String? = null,
    /** 注入文本（启用态进系统提示词，形态对齐技能 promptInjection）。 */
    val text: String = ""
)
