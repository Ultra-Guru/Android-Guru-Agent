<!-- pr: plugin-host-2-core-model -->

- Native Plugin Host 2.0 开工：新增统一插件核心模型（生态/ABI/能力槽位/运行时种类/14 态生命周期状态机），Claude、OpenCode V1/V2、DSH、MCP、功能性、APK 六大生态首次有了同一张运行时描述符
- 新增安装前兼容性分析引擎：A–F 五级评级 + 逐能力原因说明，「能安装」不再冒充「能运行」—— OpenCode 插件当前如实评为 D（能力桥 Phase 4 落地），TUI/GUI-only 插件评 F
- 新增生态能力支持矩阵（Phase 0 审计基线）：Claude hooks/.mcp.json/.lsp.json 标记待实现，skills/commands 维持已支持，评级随各阶段落地演进
- 架构文档 docs/plugin-host-2.0.md：审计结论 + 目标架构 + 六阶段路线图
