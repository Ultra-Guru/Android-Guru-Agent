package com.apex.agent.core.tools.extension

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.security.MessageDigest

/**
 * # Extension Model — 统一扩展模型的公开类型（schema `apex-extension-v1`）
 *
 * 2026-10 市场完全重构的第一块基石：把 OpenCode / Claude / DSH / Skill / MCP /
 * APK 各生态的「安装」收口到一个闸门 —— **resolve（认路）→ inspect（看清）→
 * install（装）** 三步各自由 [ExtensionAdapter] 翻译成目标生态的原生动作，
 * 实体最终落各原生注册表（SkillRegistry / McpManager / UnifiedPluginRegistry），
 * 本模型只做「来源账本 + 前置闸门」，不建第三平行注册体系（与 FusionBundle
 * 的「聚而不同」同一纪律）。
 *
 * 与 [com.apex.agent.core.tools.fusion.FusionBundleManifest] 的分工：
 * - FusionBundle = 复合聚合包（多元实体整体调度）；
 * - Extension = 单一来源单一实体的统一安装面（`npm 名 / claude:@mp / github: /
 *   URL / JSON 字面量` 都能走同一条 resolve→inspect→install 链）。
 *
 * 前向兼容：全部字段带默认值 + 解析 ignoreUnknownKeys（与 SkillManifest /
 * McpServerConfig / FusionBundleManifest 同口径）。
 */
@Serializable
data class ExtensionManifest(
    val schema: String = SCHEMA,
    /** 稳定扩展 id（适配器赋值，如 `skill.web-scraper` / `mcp.memory`），账本主键。 */
    val id: String,
    /** 扩展类型（[ExtensionTypes] 常量之一）。 */
    val type: String,
    /** 原始来源串（用户/模型输入的 spec 原文，账本回溯用）。 */
    val spec: String,
    val name: String,
    val version: String = "1.0.0",
    /** 来源类别（[SourceKinds] 常量之一）。 */
    val sourceKind: String,
    /** 运行时域（[RuntimeDomains]）：安装动作走宿主侧还是 PRoot Ubuntu。 */
    val runtime: String = RuntimeDomains.ANDROID,
    /** 信任级别：verified / community / untrusted（与 Fusion/Skill 口径一致）。 */
    val trustLevel: String = "community",
    /**
     * MCP 语义分离（brief 明确要求）：`register` = 新增服务器；
     * `import` = 导入既有配置（桌面 Claude/Cursor 导出的 mcp.json）。
     * 二者混用会导致配置重复或覆盖 —— resolve 阶段即判定并携带。
     */
    val action: String = Actions.REGISTER,
    /** 声明的权限（network / spawn-process / env-secrets / aidl-bind…）。 */
    val permissions: List<String> = emptyList(),
    /** 目标生态含安装期/生命周期脚本（postinstall 等）：默认拒绝自动安装。 */
    val hasPostinstall: Boolean = false,
    /** 适配器产出的目标生态原生 manifest（SkillManifest / McpServerConfig / … 内联 JSON）。 */
    val resolved: JsonObject? = null,
    /** 一句话「将安装什么」（inspect/预览/确认弹窗共用）。 */
    val summary: String = "",
    /**
     * manifest 指纹（sha-256，resolve 时计算）：**install 必须回传**——
     * 强制「先看后装」的闸门令牌，防止模型跳过 inspect 直接装（见
     * [ExtensionInstaller] 的 hash 门与篡改检测）。
     */
    val manifestHash: String = ""
) {
    /** install 确认是否必需（敏感权限或含脚本 —— 二者其一即需显式确认令牌）。 */
    fun requiresConfirm(): Boolean = hasPostinstall || permissions.isNotEmpty()

    companion object {
        const val SCHEMA = "apex-extension-v1"

        internal val json: Json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = false
        }

        /** 解析 + 最小校验（id/type 非空）。 */
        fun parse(text: String): ExtensionManifest? = runCatching {
            val m = json.decodeFromString(serializer(), text)
            if (m.id.isBlank() || m.type.isBlank()) null else m
        }.getOrNull()

        /** canonical JSON（encodeDefaults=false，键序由序列化器稳定输出）——hash 输入。 */
        fun canonicalJson(manifest: ExtensionManifest): String =
            json.encodeToString(serializer(), manifest)
    }

    /** 指纹计算：sha-256(canonical JSON)。 */
    fun computeHash(): String = hashlib(canonicalJson(this))

    private fun hashlib(input: String): String =
        MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

/** extension 包共享的宽松 JSON 解析入口（适配器解析原生 manifest 用）。 */
object ExtensionJson {
    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
}

/** 扩展类型常量（PR-A 实现 skill / mcp_connector；其余为 PR-B/C 预留占位）。 */
object ExtensionTypes {
    const val SKILL = "skill"
    const val MCP_CONNECTOR = "mcp_connector"
    const val OPENCODE_PLUGIN = "opencode_plugin"
    const val CLAUDE_PLUGIN = "claude_plugin"
    const val DSH_PLUGIN = "dsh_plugin"
    const val FUNCTIONAL_PLUGIN = "functional_plugin"
    const val APK_PACKAGE = "apk_package"

    /** 当前已注册适配器的类型（诚实能力面 —— resolve 前可查）。 */
    fun implemented(): Set<String> = setOf(SKILL, MCP_CONNECTOR)
}

/** 运行时域常量。 */
object RuntimeDomains {
    const val ANDROID = "android"
    const val UBUNTU = "ubuntu"
}

/** 来源类别常量。 */
object SourceKinds {
    const val NPM = "npm"
    const val GIT = "git"
    const val URL = "url"
    const val LOCAL = "local"
    const val JSON = "json"
    const val CATALOG = "catalog"
    const val GITHUB = "github"
}

/** MCP register/import 语义分离。 */
object Actions {
    const val REGISTER = "register"
    const val IMPORT = "import"
}

/**
 * inspect 产物 —— 「看清」步骤的结构化报告（工具输出与 UI 预览共用）。
 */
data class InspectReport(
    val manifest: ExtensionManifest,
    /** 落点注册表（SkillRegistry / McpManager / …，人读语义）。 */
    val targetRegistry: String,
    /** 安装落盘/注册的位置说明。 */
    val targetLocation: String,
    /** Ubuntu 域依赖说明（node 版本/npm 包数等；android 域为空）。 */
    val domainDependencies: List<String> = emptyList(),
    /** 预计磁盘占用（人读，如 "~2.1MB"；拿不到时 null）。 */
    val estimatedSize: String? = null,
    /** 检查发现的告警（密钥明文 / 含脚本 / 不可信来源…）。 */
    val warnings: List<String> = emptyList()
)

/** install 产物。 */
data class InstallOutcome(
    val id: String,
    val type: String,
    val version: String,
    /** 实际落点（如 "SkillRegistry: PdfGeneration" / "McpManager: memory"）。 */
    val where: String,
    /** 安装期说明（幂等命中 / 自动激活 / 已存在同端点…）。 */
    val notes: List<String> = emptyList()
)
