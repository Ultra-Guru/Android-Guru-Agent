<!-- pr: plugin-host-2-worker-runtime -->

- Native Plugin Host 2.0 Phase 2 落地：插件 Worker 运行时 —— 持久化 node/bun Worker 进程 + 换行 JSON IPC 协议（STDOUT 纯协议、日志走 STDERR），工具热调用复用 Worker，不再每次调用冷启动 node
- Worker 崩溃自动恢复：Supervisor 窗口计数重启策略（超上限 GaveUp 如实报告），重启后自动重放握手与模块加载；用户主动停止不误触发重启
- 错误隔离三重保障：插件工具抛异常进程不死；进程死亡时在途请求失败返回而非悬挂；Worker 致命错误带 fatal 原因可诊断（stderr 环形缓冲）
- Node 与 Bun 运行时分离探测、分离启动命令 —— 探测失败如实报缺失，禁止互相冒充（TypeScript 入口在 node 下明确拒绝执行）
- 真实 node 端到端测试 19 项（协议编解码 + 全链路 IPC + 崩溃恢复），node 缺失环境如实标记 skipped 而非假通过
