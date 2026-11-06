package com.apex.agent.core.tools.extension.adapters

import com.apex.agent.core.tools.extension.ExtensionAdapter
import com.apex.agent.core.tools.extension.ExtensionManifest
import com.apex.agent.core.tools.extension.ExtensionSpecParser
import com.apex.agent.core.tools.extension.ExtensionTypes
import com.apex.agent.core.tools.extension.InstallOutcome
import com.apex.agent.core.tools.extension.InspectReport
import com.apex.agent.core.tools.extension.RuntimeDomains
import com.apex.agent.core.tools.extension.SourceKinds
import com.apex.agent.core.tools.skill.SkillActivationStore
import com.apex.agent.core.tools.skill.SkillRegistry
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * # SkillExtensionAdapter —— skill 生态适配器
 *
 * 把统一 spec（`skill:<url|json:…|本地路径>`）翻译成 SkillRegistry 原生安装：
 * resolve 阶段抓取/读取 apex-skill-v1 manifest 并产出预览摘要，install 复用
 * [SkillRegistry.install] 的全部既有管道（依赖校验、热注册、市场可见性），
 * 安装成功后进激活集（市场热加载同款闭环）。
 */
class SkillExtensionAdapter(
    private val skillRegistry: SkillRegistry,
    /** 安装即装备（市场热加载闭环）：非空时安装成功直接进激活集。 */
    private val activation: SkillActivationStore? = null,
    /** URL 文本拉取端口（core 无 OkHttp 依赖时由 app 注入；null = URL 来源诚实失败）。 */
    private val fetchText: (suspend (String) -> Result<String>)? = null
) : ExtensionAdapter {

    override val supportedTypes: Set<String> = setOf(ExtensionTypes.SKILL)

    override suspend fun resolve(draft: ExtensionSpecParser.SpecDraft): Result<ExtensionManifest> {
        val jsonText = when (draft.sourceKind) {
            SourceKinds.JSON -> Result.success(draft.payload)
            SourceKinds.URL -> fetchText?.invoke(draft.payload)
                ?: return Result.failure(
                    UnsupportedOperationException("URL 拉取通道未接线（app 层注入 fetchText）——可改用 skill:json:<字面量>")
                )
            SourceKinds.LOCAL -> readLocal(draft.payload)
            else -> Result.failure(IllegalArgumentException("skill spec 载荷无法识别：${draft.raw}"))
        }.getOrElse { return Result.failure(it) }

        return manifestFromJson(jsonText, spec = draft.raw, sourceKind = draft.sourceKind)
    }

    override suspend fun inspect(manifest: ExtensionManifest): Result<InspectReport> {
        val warnings = buildList {
            if (manifest.trustLevel == "untrusted") add("来源被标记 untrusted —— 安装后正文将注入对话，请确认可信")
            val body = manifest.resolved?.get("promptInjection")
            if (body != null && body.toString().length > 4000) add("prompt 正文超过 4KB —— 占用激活集预算较大")
        }
        return Result.success(
            InspectReport(
                manifest = manifest,
                targetRegistry = "SkillRegistry（宿主 skills/ 目录，装后进激活集下轮对话生效）",
                targetLocation = "skills/${manifest.id.removePrefix("skill.")}",
                warnings = warnings
            )
        )
    }

    override suspend fun install(manifest: ExtensionManifest): Result<InstallOutcome> {
        val sid = manifest.id.removePrefix("skill.")
        val jsonText = com.apex.agent.core.tools.extension.ExtensionJson.json.encodeToString(
            JsonObject.serializer(), requireNotNull(manifest.resolved) { "resolved manifest 缺失" }
        )
        val installed = skillRegistry.install(jsonText).getOrElse { return Result.failure(it) }
        val activated = activation?.activate(installed.id) == true
        return Result.success(
            InstallOutcome(
                id = manifest.id,
                type = manifest.type,
                version = installed.version,
                where = "SkillRegistry: ${installed.id}",
                notes = buildList {
                    if (activated) add("已进激活集（下轮对话生效）")
                }
            )
        )
    }

    override suspend fun uninstall(manifest: ExtensionManifest): Result<Unit> {
        val sid = manifest.id.removePrefix("skill.")
        return if (skillRegistry.uninstall(sid)) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("SkillRegistry 拒绝卸载 '$sid'（不存在，或为内置 bundled 技能——内置技能只可禁用）"))
        }
    }

    private fun readLocal(path: String): Result<String> = runCatching {
        val f = File(path)
        require(f.isFile) { "本地文件不存在或不是文件：$path" }
        require(f.length() <= MAX_MANIFEST_BYTES) { "manifest 超过 2MB 上限" }
        f.readText()
    }

    private fun JsonObject.str(key: String): String? =
        this[key]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }

    internal companion object {
        const val MAX_MANIFEST_BYTES = 2L * 1024 * 1024

        /** 技能 id 白名单（账本键安全：禁止路径分隔符/空白/点分注入）。 */
        val SID_PATTERN = Regex("^[A-Za-z0-9_-]{1,64}$")

        /**
         * 技能 manifest JSON → ExtensionManifest（resolve 与旧入口
         * SkillInstallTool 内部转接共用的单源构造；含指纹计算）。
         */
        fun manifestFromJson(
            jsonText: String,
            spec: String,
            sourceKind: String? = SourceKinds.JSON
        ): Result<ExtensionManifest> {
            val obj = runCatching {
                com.apex.agent.core.tools.extension.ExtensionJson.json.parseToJsonElement(jsonText).jsonObject
            }.getOrElse { return Result.failure(IllegalArgumentException("技能 manifest 不是合法 JSON：${it.message}")) }

            fun JsonObject.str(key: String): String? =
                this[key]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }

            val sid = obj.str("id")
                ?: return Result.failure(IllegalArgumentException("manifest 缺少 id 字段（apex-skill-v1 必填）"))
            if (!SID_PATTERN.matches(sid)) {
                return Result.failure(IllegalArgumentException("技能 id 含非法字符（只允许字母数字与 -_）：'$sid'"))
            }
            val name = obj.str("name") ?: sid
            val version = obj.str("version") ?: "1.0.0"
            val description = obj.str("description").orEmpty()
            val hasBody = obj["promptInjection"] != null

            val manifest = ExtensionManifest(
                id = "skill.$sid",
                type = ExtensionTypes.SKILL,
                spec = spec,
                name = name,
                version = version,
                sourceKind = sourceKind ?: SourceKinds.JSON,
                runtime = RuntimeDomains.ANDROID,
                trustLevel = obj.str("trustLevel") ?: "community",
                resolved = obj,
                summary = "技能「$name」v$version" +
                    (if (description.isNotBlank()) "：${description.take(80)}" else "") +
                    (if (hasBody) "（prompt 型，正文将注入对话）" else "")
            )
            return Result.success(manifest.copy(manifestHash = manifest.computeHash()))
        }
    }
}
