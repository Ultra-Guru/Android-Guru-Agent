package com.apex.agent.platform.browser

import com.apex.agent.core.tools.AgentTool
import com.apex.browser.core.BookmarkCore.AddResult
import com.apex.browser.core.UrlCleaner
import com.apex.browser.engine.BookmarkStore
import com.apex.browser.engine.BrowserEngine
import com.apex.browser.engine.BrowserTracer
import com.apex.browser.engine.DownloadCenter
import com.apex.browser.engine.EngineDisplayController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.inject.Inject

/**
 * 浏览器会话工具集（v1.7.0 —— 对标 Playwright MCP / 桌面浏览器的会话级基线）。
 *
 * 与 [BrowserAgentTools]（25 个页面内动作工具）的分工：本工具集覆盖
 * **会话级**能力——历史导航（back/forward/reload）、浏览历史查询、页内
 * 查找、下载中心（全量台账 + 生命周期控制）、书签、显示控制（文字缩放 /
 * 清除浏览数据）。此前这些能力引擎侧已具备（goBack / recentHistory /
 * findInPage / lastDownload），但**从未暴露给 Agent**——「能力存在但工具
 * 面缺位」正是 v1.7.0 要补全的细节功能层。
 *
 * 守卫纪律与 [BrowserAgentTools] 一致：每个工具执行前 [guard]
 * （WAITING_HUMAN 返回结构化 SYSTEM_LOCKED JSON）；全部经 [TracedTool]
 * 包装进 [BrowserTracer]（BrowserDebugDump / 仪表盘可见）。
 */
class BrowserSessionTools @Inject constructor(
    private val engine: BrowserEngine,
    private val tracer: BrowserTracer,
    private val downloads: DownloadCenter,
    private val bookmarks: BookmarkStore,
    private val display: EngineDisplayController,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun argStr(args: String, key: String): String? =
        runCatching { json.decodeFromString<JsonObject>(args)[key]?.jsonPrimitive?.content }.getOrNull()

    private fun argInt(args: String, key: String, default: Int = 0): Int =
        argStr(args, key)?.toIntOrNull() ?: default

    /** 守卫（与 BrowserAgentTools.guard 同语义、同文案——模型侧无需区分来源）。 */
    private fun guard(): String? = try {
        engine.assertAgentControl()
        null
    } catch (e: BrowserEngine.HandoffLockedException) {
        kotlinx.serialization.json.buildJsonObject {
            put("status", "SYSTEM_LOCKED")
            put("reason", e.message ?: "human handoff in progress")
            put("hint", "用户正在浮窗中人工操作，自动化工具已锁定。请等待 BrowserShow 展开确认用户完成，或改做其他任务，稍后重试。")
        }.toString()
    }

    /* ═════════ 历史导航（引擎已有能力，首次暴露给 Agent） ═════════ */

    /** 后退一页（Playwright browser_go_back 对齐）。 */
    val back = tool(
        id = "BrowserBack",
        description = "在浏览历史中后退一页（对标 Playwright browser_go_back）。已在历史最底部时返回提示。" +
            "常用于：搜索结果页 → 详情页 → 后退回结果页继续浏览下一个结果。",
        schema = """{"type":"object","properties":{}}""",
    ) { _ ->
        val ok = engine.goBack()
        if (ok) {
            val tab = engine.activeTab()
            "已后退到：${tab?.url ?: "(加载中)"}\n建议重新 BrowserSnapshot 获取新页面结构。"
        } else {
            "已在浏览历史最底部，无法后退（可用 BrowserNavigate 打开新地址）。"
        }
    }

    /** 前进一页（Playwright browser_go_forward 对齐）。 */
    val forward = tool(
        id = "BrowserForward",
        description = "在浏览历史中前进一页（对标 Playwright browser_go_forward）。已在历史最顶端时返回提示。",
        schema = """{"type":"object","properties":{}}""",
    ) { _ ->
        val ok = engine.goForward()
        if (ok) {
            val tab = engine.activeTab()
            "已前进到：${tab?.url ?: "(加载中)"}\n建议重新 BrowserSnapshot 获取新页面结构。"
        } else {
            "已在浏览历史最顶端，无法前进。"
        }
    }

    /** 重新加载当前页（带加载等待语义，非裸 reload）。 */
    val reload = tool(
        id = "BrowserReload",
        description = "重新加载当前页面（带就绪等待：等 onPageFinished，超时兜底返回当前已渲染状态）。" +
            "适用于页面内容过期、登录后刷新、验证码重试等场景。",
        schema = """{
            "type":"object",
            "properties":{"timeout_ms":{"type":"integer","description":"加载等待超时毫秒，默认 15000"}}
        }""",
    ) { args ->
        val timeout = argInt(args, "timeout_ms", 15_000).toLong()
        val ok = engine.reload(timeout)
        if (ok) "页面已重新加载完成。建议重新 BrowserSnapshot 获取最新结构。"
        else "页面重新加载超时（${timeout}ms），以下为当前已渲染状态——可 BrowserSnapshot 查看。"
    }

    /** 浏览历史查询（引擎 recentHistory 首次暴露）。 */
    val history = tool(
        id = "BrowserHistory",
        description = "读取最近浏览历史（URL 列表，最新在前）。用于：回顾本会话去过哪些页面、" +
            "找回刚才看过的内容地址、规划回退路径（配合 BrowserBack/BrowserNavigate）。" +
            "上限 100 条（引擎环形缓冲）。",
        schema = """{
            "type":"object",
            "properties":{"limit":{"type":"integer","description":"返回最近 N 条，默认 20（1-100）"}}
        }""",
    ) { args ->
        val limit = argInt(args, "limit", 20).coerceIn(1, 100)
        val items = withContext(Dispatchers.Main) { engine.recentHistory(limit) }
        if (items.isEmpty()) {
            "(暂无浏览历史——本会话还没有导航记录)"
        } else {
            val active = engine.activeTab()?.url
            items.asReversed().joinToString("\n") { url ->
                val mark = if (url == active) " ← 当前" else ""
                "- $url$mark"
            } + "\n提示：BrowserBack/BrowserForward 按历史栈移动；BrowserNavigate 直达。"
        }
    }

    /* ═════════ 页内查找（引擎 findInPage 首次暴露） ═════════ */

    /** 页内查找计数（chrome 人用查找栏的 Agent 对位面）。 */
    val find = tool(
        id = "BrowserFind",
        description = "在当前页面内查找文本并返回匹配数（findAllAsync + 高亮首个匹配）。" +
            "用于快速确认页面是否包含某关键词（如「库存」「售罄」「已提交」）而无需全页 snapshot。" +
            "已知取舍：与 chrome 层查找共用 WebView 单槽 FindListener（Agent 计数优先）。",
        schema = """{
            "type":"object",
            "properties":{"query":{"type":"string","description":"要查找的文本"}},
            "required":["query"]
        }""",
    ) { args ->
        val query = argStr(args, "query")?.trim()
            ?: return@tool "Error: 缺少 query 参数"
        if (query.isEmpty()) return@tool "Error: query 参数不能为空"
        val count = engine.findInPage(query)
        if (count > 0) "✅ 找到 $count 处「$query」（首个匹配已高亮）"
        else "(页面中未找到「$query」)"
    }

    /* ═════════ 下载中心（DownloadCenter 台账，v1.7.0 新能力） ═════════ */

    /** 下载全量查询与控制（对标 Playwright browser_downloads + 桌面浏览器下载面板）。 */
    val downloadsTool = tool(
        id = "BrowserDownloads",
        description = "查询与控制内置浏览器的文件下载（全量台账，跨进程重启可查——v1.7.0 新能力）。" +
            "action：list（列出全部下载及状态/进度）/ cancel（取消并删除）/ retry（失败后原 URL 重入队，" +
            "转发会话 Cookie）/ forget（仅删台账记录）/ open（打开系统下载应用）。" +
            "与 BrowserDownloadList（只报最近一次）互补：本工具是下载的「管理面」。",
        schema = """{
            "type":"object",
            "properties":{
                "action":{"type":"string","enum":["list","cancel","retry","forget","open"],"description":"下载操作，默认 list"},
                "id":{"type":"integer","description":"action=cancel/retry/forget 时必填：下载 id（list 输出中获取）"},
                "limit":{"type":"integer","description":"action=list 时最多返回几条，默认 20"}
            }
        }""",
    ) { args ->
        val action = argStr(args, "action") ?: "list"
        when (action) {
            "cancel" -> {
                val id = argInt(args, "id", -1).toLong()
                if (id <= 0) return@tool "Error: 缺少 id 参数（先用 action=list 查看下载列表）"
                if (downloads.cancel(id)) "已取消并删除下载 id=$id（系统 DownloadManager 同步删文件）。"
                else "Error: 下载 id=$id 不存在或已结束（用 action=list 查看有效项）"
            }
            "retry" -> {
                val id = argInt(args, "id", -1).toLong()
                if (id <= 0) return@tool "Error: 缺少 id 参数"
                val newId = downloads.retry(id)
                    ?: return@tool "Error: 重试失败（id=$id 不存在，或原 URL 无法重新入队）"
                "已重新入队下载 id=$id → 新 id=$newId（转发会话 Cookie，登录态下载同权重试）。"
            }
            "forget" -> {
                val id = argInt(args, "id", -1).toLong()
                if (id <= 0) return@tool "Error: 缺少 id 参数"
                if (downloads.forget(id)) "已从台账移除下载 id=$id（不影响进行中的下载文件）。"
                else "Error: 台账中没有 id=$id 的记录"
            }
            "open" -> {
                downloads.openSystemDownloads()
                "已打开系统「下载」应用（完结文件的管理与分享走系统 UI）。"
            }
            else -> {
                downloads.refresh()
                val limit = argInt(args, "limit", 20).coerceIn(1, 100)
                val list = downloads.snapshot().take(limit)
                if (list.isEmpty()) {
                    "(暂无下载记录——本库发起的下载会自动入账；系统其他应用的下载不在管辖内)"
                } else {
                    list.joinToString("\n") { d ->
                        val pct = if (d.totalBytes > 0) " · ${d.percent}%" else ""
                        val bytes = formatBytes(d.bytesSoFar, d.totalBytes)
                        "- id=${d.dmId} [${d.state}]${pct} ${d.fileName}（$bytes）\n  来源: ${d.url}"
                    } + "\n提示：cancel/retry/forget 用 id 定位；PAUSED 状态是系统因网络自动暂停（公开 API 无手动暂停/恢复）。"
                }
            }
        }
    }

    private fun formatBytes(soFar: Long, total: Long): String {
        val so = humanBytes(soFar)
        return if (total > 0) "$so / ${humanBytes(total)}" else so
    }

    private fun humanBytes(v: Long): String = when {
        v >= 1 shl 30 -> String.format(java.util.Locale.US, "%.1fGB", v / 1073741824.0)
        v >= 1 shl 20 -> String.format(java.util.Locale.US, "%.1fMB", v / 1048576.0)
        v >= 1 shl 10 -> String.format(java.util.Locale.US, "%.1fKB", v / 1024.0)
        else -> "${v}B"
    }

    /* ═════════ 书签（BookmarkStore，v1.7.0 新能力） ═════════ */

    /** 书签管理（持久化，chrome 书签面板与 Agent 共用同一存储）。 */
    val bookmarksTool = tool(
        id = "BrowserBookmarks",
        description = "管理浏览器书签（跨会话持久化，与浏览器页签的书签面板共用同一存储）。" +
            "action：list（MRU 序，最新访问在前）/ add（收藏页面，重复页面自动更新）/ " +
            "remove（按 id 删除）/ search（按标题/站点/网址搜索）。" +
            "去重语义：跟踪参数变体（utm_* 等）与原始 URL 归一为同一条书签。",
        schema = """{
            "type":"object",
            "properties":{
                "action":{"type":"string","enum":["list","add","remove","search"],"description":"书签操作，默认 list"},
                "url":{"type":"string","description":"action=add 时必填：要收藏的页面地址"},
                "title":{"type":"string","description":"action=add 时可选：书签标题（缺省用站点名）"},
                "id":{"type":"string","description":"action=remove 时必填：书签 id（list/search 输出中获取）"},
                "query":{"type":"string","description":"action=search 时必填：搜索关键词"},
                "limit":{"type":"integer","description":"list/search 返回上限，默认 30"}
            }
        }""",
    ) { args ->
        val action = argStr(args, "action") ?: "list"
        when (action) {
            "add" -> {
                val url = argStr(args, "url")?.trim()
                    ?: return@tool "Error: 缺少 url 参数"
                if (!url.contains("://")) return@tool "Error: url 需带 scheme（如 https://example.com/a）"
                val title = argStr(args, "title")?.trim().orEmpty()
                when (bookmarks.add(url, title)) {
                    AddResult.ADDED -> "已加入书签：${title.ifBlank { UrlCleaner.hostOf(url) }}（$url）"
                    AddResult.UPDATED -> "书签已更新（该页面此前已收藏，标题与访问时间已刷新）：$url"
                    AddResult.REJECTED -> "Error: 书签写入被拒绝（url 非法）"
                }
            }
            "remove" -> {
                val id = argStr(args, "id")?.trim()
                    ?: return@tool "Error: 缺少 id 参数（先用 action=list 查看）"
                if (bookmarks.remove(id)) "已删除书签 id=$id"
                else "Error: 书签 id=$id 不存在"
            }
            "search" -> {
                val query = argStr(args, "query")?.trim()
                    ?: return@tool "Error: 缺少 query 参数"
                val limit = argInt(args, "limit", 30).coerceIn(1, 100)
                val found = bookmarks.search(query, limit)
                if (found.isEmpty()) "(没有匹配「$query」的书签——可换更短的关键词)"
                else renderBookmarkList(found) { "\n提示：remove 用 id 定位。" }
            }
            else -> {
                val limit = argInt(args, "limit", 30).coerceIn(1, 100)
                val list = bookmarks.recent(limit)
                if (list.isEmpty()) "(书签为空——action=add 收藏第一个页面；或让用户在浏览器页签的书签面板手动添加)"
                else renderBookmarkList(list) {
                    "\n提示：add 的 url 带 utm 参数会与原始 URL 归一为同一条（跟踪参数变体不重复收藏）。"
                }
            }
        }
    }

    private inline fun renderBookmarkList(list: List<com.apex.browser.core.Bookmark>, tail: () -> String): String =
        list.joinToString("\n") { bm ->
            val folder = bm.folder.takeIf { it.isNotBlank() }?.let { "[$it] " } ?: ""
            "- id=${bm.id} ${folder}${bm.title}\n  ${bm.url}"
        } + tail()

    /* ═════════ 显示与会话数据（v1.7.0 新能力） ═════════ */

    /** 文字缩放（无障碍 + 截图可读性）。 */
    val zoom = tool(
        id = "BrowserZoom",
        description = "设置页面文字缩放（50..200，100 = 原生大小）。跨页面与跨标签保持。" +
            "用途：截图前放大关键文字提升可读性（视觉模型识别率）；为弱视用户朗读页面做准备。" +
            "注意：影响整页渲染密度，改后应重新 BrowserSnapshot（元素布局坐标变化）。",
        schema = """{
            "type":"object",
            "properties":{"text_zoom":{"type":"integer","description":"缩放百分比 50-200，默认 100"}},
            "required":["text_zoom"]
        }""",
    ) { args ->
        val percent = argInt(args, "text_zoom", 100).coerceIn(50, 200)
        display.setTextZoom(percent)
        "文字缩放已设为 $percent%（跨页保持；重新 BrowserSnapshot 获取新布局）。"
    }

    /** 清除浏览数据（不可逆动作，工具描述即警告）。 */
    val clearData = tool(
        id = "BrowserClearData",
        description = "清除浏览数据（不可逆）。scope：cookies（全局 Cookie——所有站点将退出登录，" +
            "慎用且须先告知用户）/ caches（页面缓存）/ histories（页面导航历史）/ all（全部）。" +
            "用途：登录态重置排障、存储空间回收、自动化前的干净环境。执行前确认用户意图。",
        schema = """{
            "type":"object",
            "properties":{"scope":{"type":"string","enum":["cookies","caches","histories","all"],"description":"清除范围，默认 all"}},
            "required":["scope"]
        }""",
    ) { args ->
        val scope = argStr(args, "scope")?.lowercase() ?: "all"
        val (c, k, h) = when (scope) {
            "cookies" -> Triple(true, false, false)
            "caches" -> Triple(false, true, false)
            "histories" -> Triple(false, false, true)
            else -> Triple(true, true, true)
        }
        display.clearBrowsingData(c, k, h)
        val scopeText = when (scope) {
            "cookies" -> "Cookie（全部站点登录态已清除）"
            "caches" -> "页面缓存"
            "histories" -> "页面导航历史"
            else -> "Cookie + 缓存 + 历史"
        }
        "已清除浏览数据（$scopeText）。\n注意：地址栏联想历史不受影响；如需重新登录站点请用 BrowserShow 人工接管。"
    }

    /* ═════════ 装配 ═════════ */

    /** 全部会话级工具（TracedTool 包装：与 BrowserAgentTools 同款可观测性）。 */
    fun all(): List<AgentTool> = listOf(
        back, forward, reload, history, find,
        downloadsTool, bookmarksTool, zoom, clearData,
    ).map { TracedTool(it, engine, tracer) }

    /** 工具构造 DSL（统一 id/name/schema + guard 前置）。 */
    private fun tool(
        id: String,
        description: String,
        schema: String,
        execute: suspend (String) -> String,
    ): AgentTool = object : AgentTool {
        override val id = id
        override val name = id
        override val description = description
        override val parametersSchema = schema
        override suspend fun execute(arguments: String): String {
            guard()?.let { return it }
            return execute(arguments)
        }
    }

    /**
     * trace 包装（与 BrowserAgentTools.TracedTool 同语义；独立副本是因为
     * 那边是 private class——两处工具集分文件演进，各自的 trace 包装也
     * 分开演化避免共享私有面）。
     */
    private class TracedTool(
        private val delegate: AgentTool,
        private val engine: BrowserEngine,
        private val tracer: BrowserTracer,
    ) : AgentTool {
        override val id get() = delegate.id
        override val name get() = delegate.name
        override val description get() = delegate.description
        override val parametersSchema get() = delegate.parametersSchema

        override suspend fun execute(arguments: String): String {
            val start = System.currentTimeMillis()
            val loggedParams = sanitizeParams(delegate.id, arguments)
            return try {
                val result = delegate.execute(arguments)
                tracer.record(
                    delegate.id, loggedParams, result.take(200),
                    System.currentTimeMillis() - start,
                    engine.activeTab()?.url, engine.currentState.name,
                )
                result
            } catch (e: Throwable) {
                tracer.record(
                    delegate.id, loggedParams, "ERROR: ${e.message?.take(160)}",
                    System.currentTimeMillis() - start,
                    engine.activeTab()?.url, engine.currentState.name,
                )
                throw e
            }
        }

        private fun sanitizeParams(toolId: String, args: String): String = when (toolId) {
            "BrowserFind" -> args.replace(Regex("\"query\"\\s*:\\s*\"[^\"]*\""), "\"query\":\"***\"")
            else -> args
        }.take(200)
    }
}
