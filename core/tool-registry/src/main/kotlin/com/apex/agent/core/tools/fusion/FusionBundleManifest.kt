package com.apex.agent.core.tools.fusion

import com.apex.agent.core.tools.mcp.McpServerConfig
import com.apex.agent.core.tools.mcp.McpTransport
import com.apex.agent.core.tools.skill.ChangelogEntry
import com.apex.agent.core.tools.skill.SkillManifest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * # FusionBundle — 复合技能包 manifest（schema `fusion-bundle-v1`）
 *
 * **聚而不同（aggregate, not fuse）**：一个 FusionBundle 把若干**既有体系**
 * 的实体聚到一起 —— 技能（apex-skill-v1 manifest，装进 SkillRegistry）、
 * MCP 服务器（McpServerConfig，连进 McpManager）、工具激活提示
 * （引擎渐进披露）、agent 工作流人设（system prompt 段）。三大体系各自
 * 的注册表、热更新管道、作用域隔离全部**原样复用** —— FusionBundle 层
 * 只做编排（安装、连接、注入、恢复），不新建任何平行注册体系。
 *
 * 与单技能（apex-skill-v1）的本质差异：技能是「一份说明书」，包是
 * 「一支可整体调度的队伍」—— 顶格对齐业界顶级复合开源项目
 * （REA / Reverse Engineer Anything 等「MCP 服务器 + 工作流技能 + 工具」
 * 三位一体形态）的装载单位。
 *
 * 前向兼容：全部字段带默认值 + 解析 ignoreUnknownKeys —— 上游演进
 * 新增字段时旧 App 不炸（与 SkillManifest / McpServerConfig 同口径）。
 */
@Serializable
data class FusionBundleManifest(
    val schema: String = SCHEMA,
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val author: String = "",
    val license: String = "MIT",
    /** 市场分类（自由文本，展示用）。 */
    val category: String = "",
    val tags: List<String> = emptyList(),
    val homepage: String? = null,
    val repository: String? = null,
    /** verified（官方/已签名）/ community / untrusted。 */
    val trustLevel: String = "community",
    /** 内置预装标记：true 的包不可卸载（与 SkillManifest.bundled 同语义）。 */
    val bundled: Boolean = false,
    /** 工位作用域（"agent" | "coding" | "all"，默认 agent —— 复合包首发于聊天工位）。 */
    val scope: String = "agent",
    /** agent 工作流人设：激活后注入 system prompt 的 Session Context 段。 */
    val agent: FusionBundleAgent = FusionBundleAgent(),
    /** 聚合的技能（完整 apex-skill-v1 manifest 内联 —— 装进 SkillRegistry 复用其全部管道）。 */
    val skills: List<FusionSkillRef> = emptyList(),
    /** 聚合的 MCP 服务器（完整 McpServerConfig 内联 —— 连进 McpManager 复用其全部管道）。 */
    val mcpServers: List<FusionMcpServerRef> = emptyList(),
    /**
     * 聚合的功能性插件（plugin.json 原文内联为 JsonObject —— 装进
     * UnifiedPluginRegistry 复用其全部管道）。core 模块不依赖 plugin-sdk
     * （模块边界），manifest 以不透明 JSON 载体内联，[FusionPluginRef.manifestJson]
     * 还原原文交由 app 层端口（FunctionalPluginStore.install）消费。
     */
    val plugins: List<FusionPluginRef> = emptyList(),
    /**
     * **安装型聚合**的技能引用（apex-skill-hub 仓库 id，不内联正文）：
     * 安装/激活时经 [FusionHubInstaller] 按需从官方仓库下载装齐 ——
     * 「用户要安装的时候调用」的轻量聚合形态：一个几十字节的引用清单
     * 就能表达「装这 5 个技能」，成员升级随仓库演进（内联形态则锁定版本）。
     */
    val skillPicks: List<String> = emptyList(),
    /**
     * **安装型聚合**的 MCP 服务器引用（apex-mcp-hub 目录名）：安装/激活时
     * 经 [FusionHubInstaller] 从仓库目录解析配置写入注册表 —— 与
     * mcpServers 内联形态的区别同样是「引用 vs 快照」。
     */
    val mcpPicks: List<String> = emptyList(),
    /**
     * 工具激活提示（McpReaOpenBinary 这类一等工具 id，v5 组合规则
     * Mcp{Server}{Tool}）：服务器连上后
     * 工具自动进入引擎计划池（渐进披露），此处声明帮助模型优先关注旗舰工具。
     */
    val toolActivationHints: List<String> = emptyList(),
    /** 运行时引擎声明（全部 optional —— BYO 引擎模型，缺失时对应工具降级并给证据）。 */
    val runtime: FusionBundleRuntime = FusionBundleRuntime(),
    val changelog: List<ChangelogEntry> = emptyList()
) {
    companion object {
        const val SCHEMA = "fusion-bundle-v1"

        /** 解析器：ignoreUnknownKeys 前向兼容 + 宽松（默认值兜底）。 */
        internal val json: Json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = false
        }

        /** id 合法形态：小写字母数字开头，允许连字符/下划线（路径安全，防穿越）。 */
        private val ID_PATTERN = Regex("^[a-z0-9][a-z0-9_-]{0,63}$")

        /**
         * 解析 + 校验一份包 manifest JSON。
         *
         * 校验口径（失败即返回 null，调用方转 Result 失败语义）：
         * 1. schema 字段必须是 fusion-bundle-v1（缺失按默认放行 —— 手写包省心）；
         * 2. id 形态合法（将来落盘 `bundlesDir` 下的 id.json，防路径穿越 —— 与
         *    SkillRegistry.install 同一防线）；
         * 3. 每个 FusionSkillRef.skillId 与内联 manifest.id 一致（防装配错位）；
         * 4. MCP 服务器 name 非空（它是 connect 的键）；
         * 5. 每个 FusionPluginRef.pluginId 与内联 plugin.json 的 id 字段一致
         *    （防装配错位 —— 与技能引用同一防线；manifest 载体是不透明 JSON，
         *    只能取 id 字符串比对）。
         */
        fun parse(manifestJson: String): FusionBundleManifest? {
            val manifest = try {
                json.decodeFromString(serializer(), manifestJson)
            } catch (e: Exception) {
                return null
            }
            if (!ID_PATTERN.matches(manifest.id)) return null
            if (manifest.schema != SCHEMA) return null
            if (manifest.skills.any { it.skillId != it.manifest.id }) return null
            if (manifest.mcpServers.any { it.serverId.isBlank() }) return null
            if (manifest.plugins.any { it.pluginId != it.inlinePluginId }) return null
            if (manifest.skillPicks.any { it.isBlank() }) return null
            if (manifest.mcpPicks.any { it.isBlank() }) return null
            return manifest
        }
    }
}

/** agent 工作流人设：激活后「直接使用这个体系 agent」的提示词来源。 */
@Serializable
data class FusionBundleAgent(
    /** 人设一句话（如「逆向工程智能体」）。 */
    val persona: String = "",
    /** 激活注入的完整工作流提示（目标路由、证据纪律、降级诚实原则）。 */
    val activationPrompt: String = "",
    /** 目标路由速查（附在激活提示后，帮模型选对首个工具）。 */
    val routingHints: String = ""
)

/** 聚合的技能引用：内联完整 apex-skill-v1 manifest。 */
@Serializable
data class FusionSkillRef(
    /** 必须与 manifest.id 一致（parse 校验）。 */
    val skillId: String,
    /** 完整技能 manifest —— SkillRegistry.install 直接消费。 */
    val manifest: SkillManifest,
    /** 该技能在本包中的角色说明（展示用）。 */
    val role: String = ""
)

/**
 * 聚合的 MCP 服务器引用（扁平字段 —— 手写 manifest 友好；
 * [toMcpServerConfig] 在激活时转换为既有 [McpServerConfig] 全形态）。
 */
@Serializable
data class FusionMcpServerRef(
    /** 服务器名（connect 的键；BUILTIN 形态对应内置 transport 工厂 key）。 */
    val serverId: String,
    /** BUILTIN（进程内，默认）| STDIO | HTTP | SSE。 */
    val transport: String = "BUILTIN",
    val url: String = "",
    /** STDIO 形态：可执行文件与参数。 */
    val command: String? = null,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    /** true = STDIO 命令在 PRoot Ubuntu 沙箱内启动（同 McpServerConfig 语义）。 */
    val runInSandbox: Boolean = false,
    val headers: Map<String, String> = emptyMap(),
    val enabled: Boolean = true,
    /** 工位作用域（"agent" | "coding" | "all"）。 */
    val scope: String = "agent",
    /** 激活时是否自动连接（默认 true；false = 只装配置，用户市场里手动启动）。 */
    val autoConnect: Boolean = true,
    /** 该服务器在本包中的角色说明（展示用）。 */
    val role: String = ""
) {
    /** 转换为既有 McpServerConfig（激活器连接用 —— 聚合复用不重建）。 */
    fun toMcpServerConfig(): McpServerConfig = McpServerConfig(
        name = serverId,
        url = url,
        transport = runCatching { McpTransport.valueOf(transport.trim().uppercase()) }
            .getOrDefault(McpTransport.BUILTIN),
        enabled = enabled,
        command = command,
        args = args,
        env = env,
        runInSandbox = runInSandbox,
        headers = headers,
        scope = scope
    )
}

/**
 * 聚合的功能性插件引用：内联完整 plugin.json（不透明 JsonObject）。
 *
 * core/tool-registry 不依赖 plugin-sdk（模块边界：注册表只认技能/MCP/
 * 聚合自有模型），故 manifest 以 [JsonObject] 原文内联 —— 激活时经
 * [manifestJson] 还原字符串，由 app 层第四端口（FunctionalPluginStore.install）
 * 消费其全部校验与安装管道（id 命名规范 / 宿主命名空间保留 / 版本门 /
 * 贡献点形状在那一侧 fail closed）。
 */
@Serializable
data class FusionPluginRef(
    /** 必须与内联 plugin.json 的 id 字段一致（parse 校验）。 */
    val pluginId: String,
    /** 完整功能性插件 manifest 原文（不透明 JSON —— 端口侧解析）。 */
    val manifest: JsonObject,
    /** 该插件在本包中的角色说明（展示用）。 */
    val role: String = ""
) {
    /** 内联 plugin.json 的 id 字段（缺失/非字符串 = null，parse 期比对失败）。 */
    val inlinePluginId: String?
        get() = runCatching { manifest["id"]?.jsonPrimitive?.content }.getOrNull()

    /** 还原 manifest JSON 字符串（端口安装入参）。 */
    fun manifestJson(): String = manifest.toString()
}

/** 运行时引擎声明：BYO 引擎模型（同 REA「BYO Hopper/Ghidra/JADX」哲学）。 */
@Serializable
data class FusionBundleRuntime(
    /** 引擎清单：全部 optional —— 缺失只降级对应工具，不阻塞包激活。 */
    val engines: List<FusionRuntimeEngine> = emptyList(),
    /** 人类可读的运行时备注。 */
    val notes: String = ""
)

/** 单个外部引擎声明。 */
@Serializable
data class FusionRuntimeEngine(
    /** 引擎名（objdump / rizin / jadx / ghidra ...）。 */
    val name: String,
    /** 该引擎解锁的工具族说明。 */
    val purpose: String = "",
    /** true = 可选（默认）：缺失降级；false = 必需（激活时告警）。 */
    val optional: Boolean = true,
    /** 安装指引（如「PRoot Ubuntu 终端内安装」）。 */
    val installHint: String = ""
)

/** 包目录摘要（FusionList 工具与 UI 列表的行级数据）。 */
data class FusionBundleDigest(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val scope: String,
    val bundled: Boolean,
    val trustLevel: String,
    val category: String,
    /** 内联技能数 + 仓库引用（skillPicks）数 —— UI 展示口径的成员总数。 */
    val skillCount: Int,
    val mcpServerCount: Int,
    val pluginCount: Int,
    val toolHintCount: Int,
    val engineCount: Int
)
