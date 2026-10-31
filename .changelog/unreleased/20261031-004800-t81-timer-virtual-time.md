<!-- pr: t81-timer-virtual-time -->

- T81 超时控制器隔离测试转虚拟时间驱动（runTest + StandardTestDispatcher 共享调度器）—— issue #276 测试纪律方向落地，runBlocking 存量 −2
- 「cancelled timer sends no signals」假阴根除：真实 delay(2)ms 对 5ms 定时器在 CI 高负载下偶发超线（已实际击中 PR #672 的编译门），虚拟时间轴上挂起-取消零竞态
- 「TERM→KILL 宽限链」同步虚拟化：消除每次 200ms 真实等待，runner 冻结场景不再假阴
