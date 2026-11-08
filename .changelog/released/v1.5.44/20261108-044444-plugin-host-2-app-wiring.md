<!-- pr: plugin-host-2-app-wiring -->

- Native Plugin Host 2.0 Phase 6 落地：App 层完整接线 —— PluginHostModule DI 模块（事件总线 / 工具桥 / 生命周期管理器 / OpenCode 加载器 / Claude 兼容组件全部进入 Hilt 图）
- 新增 PluginHostDoctor 诊断工具：Agent 可自检插件宿主（生命周期状态 / Worker 存活与 ping / 已绑定工具清单 / node-bun 真实探测 / 生态兼容性评级），插件工具失败后可先诊断再决定重试策略
- PRoot 沙箱长驻进程通道：ProotCommandSpawner 新增 spawnLongLived（复用全部路由/bind/env 约定，stdin 保持打开 = Worker IPC 通道）；Claude 钩子沙箱执行端口同款协议形状
- 诚实边界：沙箱长驻进程实机行为（rootfs 就绪度 / bind 映射 / ARM64 node）标注待设备验证 —— CI 验证编译与 JVM E2E，不冒充实机结论
