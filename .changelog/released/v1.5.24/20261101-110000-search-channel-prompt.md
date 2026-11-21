<!-- pr: search-channel-prompt -->

- 网络搜索通道激活后模型侧真正「会调用」：按所选通道注入指名道姓的 system prompt 调用指引（内置=WebSearch/WebFetch、SearXNG=链首源优先、MCP=注册名 mcp__<server>__web_search 前缀可直检），禁止仅凭训练知识猜时效性事实
- 通道切换时序修正：先开新→智能停旧（BUILTIN↔SearXNG 同基建互切不再误关）→提示殿后；MCP 安装失败时旧通道原样保留不出现「双无」
