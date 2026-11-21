<!-- pr: plugin-host-2-claude-compat -->

- Native Plugin Host 2.0 Phase 5 落地：Claude 插件 Hooks 兼容 —— 官方 hooks.json 协议调度（stdin JSON 输入 / stdout hookSpecificOutput 解析 / exit 0 成功与 exit 2 阻断语义 / matcher 工具名 regex / 命令超时强杀），真实 shell 端到端锁定
- 宿主事件映射表：SessionStart/End、UserPromptSubmit、PreToolUse、PostToolUse(Failure)、Notification 按官方事件名分发；无对应的宿主内部事件如实跳过不伪造
- 插件 .mcp.json 接入既有 MCP 体系：stdio 命令强制沙箱执行、http/sse 直连；来源归属账本保证卸载只删插件带来的服务器，用户自建配置永不被误删
- .lsp.json 诚实检测：识别语言服务器声明并明确报告「宿主 LSP 运行时尚未就绪」—— 不启动、不伪装支持
- prompt/agent/http 类型钩子如实标注未支持（跳过并留痕），不冒充执行
