<!-- pr: plugin-host-2-opencode-loader -->

- Native Plugin Host 2.0 Phase 4 落地：OpenCode 插件真正可运行 —— 官方 V2 形状（Plugin.define + setup(ctx)）经上下文桥加载，ctx.tool.transform 注册的工具一等注册进 ToolRegistry，Agent 可直接调用
- V1/V2 ABI 分立探测：按 SDK 依赖声明（@opencode/plugin vs @opencode-ai/plugin）+ 导出形状判定，入口解析遵循官方 exports map → main → module 优先级，不按包名瞎猜
- 官方 ctx 上下文桥：ctx.storage（插件目录 JSON 持久化）、ctx.options、ctx.location、ctx.app 完整注入；ctx.$ / generate / model 等未支持能力调用即抛描述性错误，不静默假装
- 宿主事件 → 插件钩子转发：tool.execute.before/after、session.prompt 按官方映射表转发，无映射的事件如实跳过不伪造
- V1 遗留插件兼容：具名异步导出返回 hook map 的官方 V1 形状经兼容层分发
- SDK 垫片按需落盘：插件未安装 @opencode/plugin 时提供与官方客户端行为等价的最小实现，真实包已装时不介入
- 端到端测试用官方形状 fixture + 真实 node 锁定全链路（加载→工具注册→引擎调用→存储/选项生效→钩子触发→停止清理）
