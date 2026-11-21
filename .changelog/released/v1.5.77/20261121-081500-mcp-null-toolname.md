<!-- pr: mcp-null-toolname -->

- 修复「MCP null」全链失效：部分网关把流式 tool_calls 的 function.name 发成 JSON null（或参数续块重复携带 null 名），kotlinx 的 JsonNull.content 会返回字面串 "null"，旧解析把它当真名透传 —— 模型明明调用的是有效 MCP 工具（如 McpGithubCreateIssue），引擎却收到 "Tool 'null' not found (255 tools registered)"，连环重试 20+ 次直至任务报废
- 三层同修：① 解析层（StreamingOpenAiClient 流式 + 非流式）tool_calls 的 id/name/arguments 改用 contentOrNull，JSON null 一律视为字段缺席（与 finish_reason 等既有防御同款）；② 累积器（StreamingToolCallAccumulator）拒收字面 "null" 名分片，参数续块不再反向覆盖首片的正确工具名；③ MCP 工具清单（McpClient.listTools）同源 JsonNull 陷阱修复，远端服务器把 name/description 序列化成 null 时不再注册出 "MCP server/null" 幽灵条目
- 空名 / "null" 名到达执行器时改发专项指引文案（明说「名称在传输中丢失，请原样重发一次完整调用」），替代旧的通用 "Tool 'null' not found + 类别清单"（该文案只会诱导模型继续换名硬猜）；普通未知工具 id 的相近建议文案保持不变
- 搜索通道提示语（ChatToolkitStore）displayName 空缺时不再插值出「MCP「null」」诱导模型幻觉调用
- 附回归测试：ToolCallJsonNullNameTest（SSE 伪造帧）/ StreamingToolCallAccumulatorNullGuardTest / DegenerateToolNameGuidanceTest
