<!-- pr: linux-chroot-backend-t98 -->

- 全新「linux-ubuntu-chroot」执行后端：真实 Root 设备上以 su + mount + chroot 直跑**同一个** Ubuntu rootfs —— 无 ptrace 依赖（PRoot 在 HyperOS/ColorOS 等收紧 ptrace 的 ROM 上假死的问题从架构上绕开），syscall 原生执行无翻译损耗（apt/构建/git 等文件密集任务提速明显）
- 双模式数据互通：与 PRoot 后端共享同一 rootfs 安装、同一 workspace 目录池、同一持久化 home（/root）与 guest 环境基线 —— 两种模式交替使用，文件与配置零迁移
- Agent 全链路可选：TerminalBackends 真实可用性双列展示（未 Root 设备如实报 SU_NOT_FOUND 并引导默认 PRoot 后端）；TerminalCreate 新增 backend="linux-ubuntu-chroot" 参数（enum 同步扩展）；默认后端保持 PRoot 不变，不隐式自动切换
- 工程质量：挂载幂等守卫（mountpoint -q，异常退出的残留挂载直接复用不叠加）+ LIFO 最佳努力 umount 收尾 + 多会话共享挂载 EBUSY 安全；env 防注入 trampoline / guest cwd 映射与 PRoot 后端单一事实源（新抽 GuestCwdMapper，双后端逐字节同映射）；17 项 JVM 单测锁定脚本契约（挂载顺序 / cd 语义 / shellQuote 防注入 / 授权窗口诚实失败）
