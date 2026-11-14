<!-- pr: websearch-server-tool-gating -->

- 修复：开启「模型内置搜索」后接通用 OpenAI 兼容网关时，非标准 tools[].type（"WebSearch" 等 server-side 搜索 tool 形态）被严格端点整请求 422 拒绝（unknown variant `WebSearch`, expected `function`），Agent 任务直接终止
- 协议层：显式选择的搜索模式（智谱 / DeepSeek / Anthropic server tool 形态）现按端点能力门控（与 AUTO 推断同口径），端点不匹配时不再下发非标准条目 —— 搜索意图降级为不发，而不是把任务变成协议错误
- 引擎层兜底：端点仍以「未知 tools[].type」类 4xx 拒绝时，自动剔除搜索条目降级重发一次（每客户端实例一次，非重放），任务继续推进；重发仍失败则原样上抛
- 非该形态的 4xx（上下文超长 / 鉴权 / 参数错）维持不重试、诚实暴露，不烧预算
