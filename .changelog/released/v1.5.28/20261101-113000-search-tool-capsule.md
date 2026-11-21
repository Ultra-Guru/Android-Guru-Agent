<!-- pr: search-tool-capsule -->

- 网络搜索专属流水式输出：WebSearch/WebFetch 与三台搜索 MCP 的 web_search/web_read 归入独立「网络搜索」胶囊族（天蓝地球图标，与本地代码搜索的 secondary 拉开色相）
- 搜索词流水预览：模型生成参数时 query 逐字流入胶囊目标行（半截 JSON 增量解码复用），等待期即有过程感
- 收束摘要结构化：「网页搜索 · N 条来源」（按输出中来源链接去重计数，内置 JSON 信封与 MCP 的 Markdown 结果通吃）
- 详情页搜索结果 Markdown 主题化渲染（标题/列表/链接直接可读），替代等宽代码块
