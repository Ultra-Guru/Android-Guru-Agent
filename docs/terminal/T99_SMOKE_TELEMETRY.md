# T99 — PRoot 冒烟探针与 chroot 可用性遥测

本 PR 仅实现 T99 §D/§F；su 发现/授权缓存、挂载自愈、CA bundle 不属于此 PR。

## Agent 可见结果

- `TerminalBackends` 列出原有注册顺序与可用性。PRoot READY 时，首次调用额外运行 `<proot> -r <rootfs> /bin/true`；`smoke` 为 `ok`、`timed_out`、`ptrace_signal` 或 `binary_error`。无 rootfs 则不启动进程。
- 超时或 SIGTRAP/SIGSYS 才输出 `suggestion`：ptrace 限制**可能**存在；用户已经授予超级用户权限时可显式选择 `linux-ubuntu-chroot`。普通退出非零属于二进制/guest 故障，不推断 ptrace。默认后端与 `TerminalCreate` 路由不变。
- 进程等待最长 5 秒；取消或超时强制销毁子进程；输出重定向到 `/dev/null`，避免管道反压。结果 10 分钟 TTL；rootfs 离开 READY 时缓存失效，也提供 `invalidate(reason)` 钩子。探针只由工具显式调用，不在 `availability()` 或 App 启动时执行。
- 环境摘要新增 `chroot_ready=on/off`，只代表后端的**可用性快照**，不是 root 授权已实测的保证。`Ready → on`；`SU_NOT_FOUND → off`；rootfs 未就绪、其他错误或尚无确定授权结果 → 省略（unknown/fail-open）。它不是任何工具的 requiredEnv，绝不阻断已有 PRoot 路径。生命周期/root 状态变化触发异步刷新（IO 线程），不 exec su。

## 验证边界

CI 覆盖进程模拟的 exit/timeout/SIGTRAP/SIGSYS/取消与 TTL、工具 JSON 建议、三态遥测与 prompt 摘要。**NOT VERIFIED：真机**（Magisk/KernelSU/APatch、受限 ptrace 的 HyperOS/ColorOS 设备、可用 PRoot 的真实进程、rootfs 重装后现场响应）。模拟进程无法证明 Android/OEM 的 ptrace 根因或真实 su 授权；建议保持“likely”措辞。
