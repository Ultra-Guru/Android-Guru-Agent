<!-- pr: plugin-host-2-capability-bridge -->

- Native Plugin Host 2.0 Phase 3 落地：插件工具正式成为一等 Agent 工具 —— Worker 发现的工具经 PluginToolBridge 注册进 ToolRegistry（Plugin 前缀 + 无分隔符 PascalCase 命名），可被 ToolSearch 搜到、被引擎直接调用
- 统一生命周期管理器：14 态状态机驱动启动（Worker → 握手 → 加载 → 工具注册 → ACTIVE）与逆序清理（注销工具 → 取消事件订阅 → 停 Worker），禁用/启用/卸载全链路，状态流转非法即拒绝并留原因
- 统一插件事件总线：异步分发 + 异常隔离 + 单处理器超时 + 并发信号量 —— 插件处理慢或拖死不阻塞 Agent 主循环；卸载时订阅全量清理
- 工具冲突防护：第三方插件工具与内置工具撞名时拒绝注册（数据永不覆盖代码）；同插件重启重放幂等 REPLACE
- 修复 suspend 边界 Result 双重装箱陷阱（执行路由改用 DTO 跨协程边界）
