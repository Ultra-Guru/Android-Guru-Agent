# T99 — PRoot 冒烟探针与 chroot 可用性遥测

本 PR 仅实现 T99 §D/§F；su 发现/授权缓存、挂载自愈、CA bundle 不属于此 PR。

## Agent 可见结果

- `TerminalBackends` 列出原有注册顺序与可用性（**纯只读发现**：任何 READY 查询都不再 exec guest 进程）。PRoot 的运行时健康探测拆到独立显式工具 `TerminalPRootSmoke`（非只读注解，PLAN 拒绝，DEFAULT/RiskAwareToolGate 走既有副作用门）：首次调用运行 `<proot> -r <rootfs> /bin/true`；`smoke` 为 `ok`、`timed_out`、`ptrace_signal`、`binary_error` 或 `not_ready`。无 rootfs 则不启动进程。缓存（10 分钟 TTL）由本工具独占；`TerminalBackends` 完全不读探针，也不显示其建议——只读发现与 guest 执行职责彻底分离。默认后端与 `TerminalCreate` 路由不变（PRoot 仍默认，无隐式切换）。
- 超时或 SIGTRAP/SIGSYS 才输出 `suggestion`：ptrace 限制**可能**存在；用户已经授予超级用户权限时可显式选择 `linux-ubuntu-chroot`。普通退出非零属于二进制/guest 故障，不推断 ptrace。默认后端与 `TerminalCreate` 路由不变。
- 结果 10 分钟 TTL；rootfs 离开 READY 时缓存失效，也提供 `invalidate` 钩子（当前非 READY 时统一重置缓存，不区分具体原因）。探针只由工具显式调用，不在 `availability()` 或 App 启动时执行。
- 环境摘要新增 `chroot_ready=on/off`，只代表后端的**可用性快照**，不是 root 授权已实测的保证。`Ready → on`（包括尚未实测授权但二进制与 rootfs 可用）；`SU_NOT_FOUND → off`；rootfs 未就绪、明确授权拒绝或其他错误 → 省略（unknown/fail-open）。它不是任何工具的 requiredEnv，绝不阻断已有 PRoot 路径。生命周期/root 状态变化触发异步刷新（IO 线程），不 exec su。

## 验证边界

CI 覆盖进程模拟的 exit/timeout/SIGTRAP/SIGSYS/取消与 TTL、工具 JSON 建议、真实 shell 进程持有 stdout 时的探针硬截止（`/bin/sleep` + PID 文件 + `kill -0` 回收断言）、只读发现零执行、显式执行工具契约与三态遥测/prompt 摘要。能力探针（`NativeLibraryPRootBinaryProvider`）同样先丢弃输出再等 10s 硬截止并强杀回收，避免被 ptrace 卡死的目标把 `availability`/`create` 冻住。

**NOT VERIFIED：**
- 真机（Magisk/KernelSU/APatch、受限 ptrace 的 HyperOS/ColorOS 设备、可用 PRoot 的真实进程、rootfs 重装后现场响应）。模拟进程无法证明 Android/OEM 的 ptrace 根因或真实 su 授权；建议保持“likely”措辞。
- 真机上 `TerminalPRootSmoke` 的运行确认：生产门禁是 `RiskAwareToolGate`（MEDIUM 且非只读 → DEFAULT 模式确认面）；JVM 单测只能锁注解为非只读，设备上确认 UI 与用户会话行为未验证。
