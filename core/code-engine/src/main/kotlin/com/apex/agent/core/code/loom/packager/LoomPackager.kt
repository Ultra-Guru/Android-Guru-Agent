package com.apex.agent.core.code.loom.packager

import com.apex.agent.core.code.loom.LoomActivePackage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * # Loom Packager — 织造包（技能包运行时激活）
 *
 * 技能/方法论的**运行时激活通道**：主模型经 `loom_package_*` 三工具
 * 查目录 → 激活 → 停用。激活即生效：
 *
 * 1. **回执注入**：激活动作的回执载荷就是包内指引全文（模型立即可读
 *    可遵——不需要下一轮才发现）；
 * 2. **持续生效**：激活集合注入系统提示词的织造包段（每回合 JIT）；
 * 3. **会话级生命周期**：clearConversation 清空激活集合。
 *
 * ## 目录端口
 *
 * [LoomPackageCatalog] 由宿主接线（映射到共享技能仓库的 coding 域
 * 技能）；引擎模块自带空目录兜底（未接线时三工具仍可调用——返回
 * 「目录未接线」的诚实回执）。
 */
/** 包目录条目（list 工具的展示粒度）。 */
data class LoomPackageInfo(
    val id: String,
    val title: String,
    /** 一行摘要（目录渲染）。 */
    val summary: String
)

/** 包目录端口（宿主接线到技能仓库）。 */
interface LoomPackageCatalog {
    /** 全目录（coding 域）。 */
    fun list(): List<LoomPackageInfo>

    /** 取包（含指引全文）；不存在返回 null。 */
    fun load(id: String): LoomActivePackage?

    companion object {
        /** 空目录（未接线兜底）。 */
        fun empty(): LoomPackageCatalog = object : LoomPackageCatalog {
            override fun list(): List<LoomPackageInfo> = emptyList()
            override fun load(id: String): LoomActivePackage? = null
        }
    }
}

/** 包管理器（激活集合 + 三工具实现）。 */
class LoomPackager(private val catalog: LoomPackageCatalog) {

    /** 激活集合（保序——激活次序即提示词段渲染序）。 */
    private val active = LinkedHashMap<String, LoomActivePackage>()

    /** 当前激活集合快照（session.updateActivePackages 消费）。 */
    fun activePackages(): List<LoomActivePackage> = active.values.toList()

    /** 激活（重复激活幂等）。 */
    fun activate(id: String): String {
        val pkg = catalog.load(id)
            ?: return "织造包不存在：$id（用 loom_package_list 查看目录）"
        active[id] = pkg
        return "已激活织造包「${pkg.title}」。以下是该包的完整指引，请在本会话中遵循：\n\n${pkg.instructions}"
    }

    /** 停用。 */
    fun deactivate(id: String): String =
        if (active.remove(id) != null) "已停用织造包：$id" else "该包未激活：$id"

    /** 清空（新会话）。 */
    fun clear() = active.clear()

    /** 三工具实例。 */
    fun tools(): List<com.apex.agent.core.code.loom.swarm.LoomLocalTool> = listOf(
        ListTool(this),
        UseTool(this),
        DropTool(this)
    )

    private abstract class BaseTool(
        override val id: String,
        override val description: String,
        override val parametersSchema: String
    ) : com.apex.agent.core.code.loom.swarm.LoomLocalTool

    private class ListTool(private val packager: LoomPackager) : BaseTool(
        id = "loom_package_list",
        description = "列出可激活的织造包（技能/方法论目录）。",
        parametersSchema = "{\"type\":\"object\",\"properties\":{}}"
    ) {
        override suspend fun execute(argumentsJson: String): String {
            val all = packager.catalog.list()
            if (all.isEmpty()) return "（织造包目录为空或未接线）"
            return all.joinToString("\n") { p ->
                "- ${p.id}：${p.title}——${p.summary}"
            }
        }
    }

    private class UseTool(private val packager: LoomPackager) : BaseTool(
        id = "loom_package_use",
        description = "激活一个织造包：其指引立即织入回执并持续生效。",
        parametersSchema = "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"}},\"required\":[\"id\"]}"
    ) {
        override suspend fun execute(argumentsJson: String): String {
            val id = parsePackageId(argumentsJson) ?: return "参数缺失：id"
            return packager.activate(id)
        }
    }

    private class DropTool(private val packager: LoomPackager) : BaseTool(
        id = "loom_package_drop",
        description = "停用一个织造包（不再注入指引）。",
        parametersSchema = "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"}},\"required\":[\"id\"]}"
    ) {
        override suspend fun execute(argumentsJson: String): String {
            val id = parsePackageId(argumentsJson) ?: return "参数缺失：id"
            return packager.deactivate(id)
        }
    }

}

/** 参数解析（防御：坏 JSON 折叠 null）——顶层入口（嵌套工具类可直接调）。 */
private fun parsePackageId(json: String): String? = runCatching {
    (Json.parseToJsonElement(json) as? JsonObject)
        ?.get("id")
        ?.let { it.jsonPrimitive.content }
}.getOrNull()?.takeIf { it.isNotBlank() }
