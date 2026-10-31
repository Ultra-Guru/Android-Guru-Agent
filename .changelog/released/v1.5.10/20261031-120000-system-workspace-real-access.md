<!-- pr: system-workspace-real-access -->

- 「整个系统」工作区兑现真实系统级操作（此前选择整个系统后模型仍只在工作区/沙箱里打转）：
- 提示词按工作区分档：root=/ 时明确 code_* 工具在真实 Android 文件系统读写（非沙箱副本）、/data 需 root 与 /system 只读的权限边界、终端侧真实系统根挂载 /host；普通工作区措辞零漂移（新增 CodePrompts 契约测试双锁）
- Ubuntu 通道挂载真实系统根：TerminalExec 一次性命令与 PTY 会话（STANDARD 档）新增 host / → guest /host bind（/host/system/build.prop 即宿主 /system/build.prop；权限门禁仍是真实 Android 权限/SELinux）；TerminalExec 工具描述同步
- cwd=/ 诚实映射 guest /host —— 旧实现走「真实目录同路径 bind」会得到 bind /:/ 覆盖 rootfs（/bin/bash 不复存在命令必炸）；/host 子路径按 guest 风格 cwd 直通（存在性按宿主根验证）
- 隔离纪律：市场沙箱（ProotSandboxCommandRunner）、MCP 启动器与 NONE 档（旧裸 -r）不挂 /host —— 隔离收窄的调用方不被动升级
