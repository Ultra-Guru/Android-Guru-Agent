package com.apex.agent.searxng

import com.apex.agent.core.tools.builtin.LocalSearchResult
import com.apex.agent.core.tools.builtin.LocalSearchSource
import com.apex.agent.platform.terminal.searxng.SearxngLifecycleCoordinator

/**
 * [LocalSearchSource] 的生产实现 —— WebSearchTool 回退链的链首（Spec §10）。
 *
 * 触发条件（Spec §10 逐条）：
 *  - 仅当 [SearxngLifecycleCoordinator.isReady] 为 true 才发起请求（Off/失败/
 *    启动中一律 null —— 零网络开销）；
 *  - 请求超时 5s；非 200、解析失败、结果空 → null（null 不打断链）；
 *  - engine → 来源标记（"searxng" 语义由 WebSearchTool 的 [searxng:engine] 前缀
 *    呈现，埋点可区分本地命中）。
 */
class SearxngProvider(
    private val coordinator: SearxngLifecycleCoordinator,
    private val httpClient: SearxngHttpClient
) : LocalSearchSource {

    override suspend fun search(query: String, limit: Int): List<LocalSearchResult>? {
        if (!coordinator.isReady()) return null
        return httpClient.search(query, limit)?.map { r ->
            LocalSearchResult(
                title = r.title,
                url = r.url,
                snippet = r.snippet,
                engine = r.engine
            )
        }
    }
}
