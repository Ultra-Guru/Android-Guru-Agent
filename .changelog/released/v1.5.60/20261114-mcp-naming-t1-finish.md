<!-- pr: mcp-naming-t1-finish -->

- 修复 MCP 命名契约残余失配：权限常规能力前缀表补 `Mcp`（v5 工具 id `Mcp{Server}{Tool}` 在 ACCEPT_EDITS 下曾被误判非常规能力），权限规则示例口径对齐 `McpGithub*` 通配
- MCP 服务器使用指引（initialize.instructions）注入链经兄弟库（apex-kit-standard）过滤语义实证确认接通
- 工具计数口径对齐实测：静态注册 238（工具全景表分项相加实数）/ CORE 常驻 77；README 版本口径更新至 v1.5.59
- 技能系统口径修正：四型（Composite / Prompt / Script / Connector）、随包内置 3 个技能
- 扩展生态矩阵如实升级：DSH 工具面 SUPPORTED（快照转一等工具已接线，真机 P0 待验证如实标注）；Claude hooks / .mcp.json 装后自动挂接 SUPPORTED；MCP resources 注释改如实（客户端未实现该协议方法）
