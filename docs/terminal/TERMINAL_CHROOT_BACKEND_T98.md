# TERMINAL_CHROOT_BACKEND_T98 —— 真实 Root 的 chroot 执行后端

> T98 · `platform/terminal/src/main/kotlin/com/apex/agent/platform/terminal/chroot/`
> 关联：PR #75（P71 PRoot 后端方案 C）· T72/T83（rootfs 供给）· T75（workspace/home 持久化）· T82（系统 bind 语义）· T88（env trampoline）

## 1. 为什么（动机）

PRoot 是 ptrace 用户态翻译层。两个结构性短板：

1. **ptrace 依赖**：现代 Android（HyperOS / ColorOS / 鸿蒙等）收紧
   `kernel.yama.ptrace_scope` 与 SELinux 策略后，tracer 无法 attach →
   proot 假死（僵尸进程、终端黑屏）。这是「Ubuntu 用不了」的高频根因
   之一（rootfs E2E 集成测试的「ptrace 受限环境下僵尸 proot 挂住」注记
   即为此证）。
2. **性能损耗**：每次 syscall 走 tracer 拦截 + 路径重写 + 伪 root 伪装，
   文件密集型任务（apt / 构建 / git）有 3~10 倍损耗。

chroot 是内核原语：无 ptrace 依赖、syscall 原生直跑，代价是**必须真实
Root**。T98 落地双后端中的 chroot 侧，回答「本仓库是否支持 proot/chroot
双模式」：**是 —— PRoot 默认 + chroot 显式可选**。

## 2. 双模式定位

| 维度 | linux-ubuntu（PRoot，默认） | linux-ubuntu-chroot（T98） |
|---|---|---|
| 门槛 | 无 Root | 真实 Root（su 授权） |
| ptrace 依赖 | 强（受限环境假死） | 无 |
| 性能 | syscall 翻译损耗 | 原生 |
| guest uid | fake root（-0 伪装） | 真实 uid 0 |
| rootfs / workspace / home | 共享（同一 Provisioner 产物 / 同一目录池） | 共享（同左） |
| guest env | env trampoline（同源） | env trampoline（同源） |
| Agent 入口 | TerminalCreate 默认 | TerminalCreate(backend="linux-ubuntu-chroot") |

**不做隐式自动切换**（诚实性取舍）：可用性探测无法廉价区分「ptrace 受限」
与「proot 版本差异」，隐式切换会让排查变玄学。Agent 经 TerminalBackends
看到双后端真实可用性后**显式**选择；PRoot 保持注册表首位（默认不变）。

## 3. 架构

```
TerminalRuntime.create(backend="linux-ubuntu-chroot")
  → ExecutionBackendRegistry 路由 → LinuxChrootBackend
      availability()  ← SuBinaryLocator（stat 级探测，绝不 exec —— 授权弹窗副作用）
      prepare()       → SpawnSpec(argv=[su, "-c", script])
  → sessionManager.createFromSpec → forkpty → execv(su …)
```

### 3.1 spawn 脚本契约

```sh
R='<rootfs>'
mountpoint -q "$R/proc"      || mount -t proc  proc       "$R/proc"      2>/dev/null
mountpoint -q "$R/sys"       || mount -t sysfs sys        "$R/sys"       2>/dev/null
mountpoint -q "$R/dev"       || mount --bind /dev         "$R/dev"       2>/dev/null
mountpoint -q "$R/root"      || mount --bind <homeDir>    "$R/root"      2>/dev/null
mkdir -p "$R/workspace" 2>/dev/null
mountpoint -q "$R/workspace" || mount --bind <wsDir>      "$R/workspace" 2>/dev/null
#（共享存储可用时）mkdir -p "$R/sdcard"; mount --bind /storage/emulated/0 "$R/sdcard"
chroot "$R" /usr/bin/env -i 'K=V' … /bin/sh -c 'cd <guestCwd> && exec /bin/bash -i'
umount "$R/workspace" 2>/dev/null ; umount "$R/root" 2>/dev/null ; … （LIFO）
```

要点：

- **挂载幂等**：`mountpoint -q` 守卫 —— 上次会话异常退出留下的挂载直接
  复用，不叠加堆叠挂载；`mountpoint` 由 toybox 提供（Android 6+）。
- **并发会话**：多会话共享挂载；先退出的 umount 因 EBUSY 静默失败，
  最后退出的清场（最佳努力，无累积泄漏）。
- **env trampoline 复用**：`/usr/bin/env -i K=V` 与 PRoot 后端同一构造
  （PRootEnvTrampoline —— 防注入校验 + PATH 兜底 + 与
  LinuxEnvironmentManager.interactiveGuestEnv 同源基线）。
- **cwd**：chroot(1) 落 guest "/"，经 `sh -c 'cd <cwd> && exec bash -i'`
  进 /workspace；映射走 GuestCwdMapper（与 PRoot 逐字节同源，T98 抽出）。
- **防注入**：路径与 K=V token 一律 shellQuote（单引号包裹 + `'\''` 转义）；
  guestCwd 额外过 `^[A-Za-z0-9._/-]+$` 白名单（内嵌无引号边界）。

### 3.2 PTY / 信号语义

forkpty 使 su 成为会话 + 进程组长（PGID==su PID）→ 与 LOCAL/PRoot 会话
共用同一套 `kill(-PGID)` / TIOCSWINSZ / tcgetpgrp 语义；bash 拿到的是真
PTY slave（su / chroot 只传递 fd，不翻译）。强杀路径：`kill(-PGID)` 打到
su 与其子进程树；su 被杀后 PTY 关闭 → bash 收内核 SIGHUP（与 PRoot 的
`--kill-on-exit` 语义等价可达）。

## 4. 测试

- **JVM 单测**（`LinuxChrootBackendTest`）：SuBinaryLocator 探测矩阵、
  availability 三态、argv 脚本逐行契约（挂载顺序 / trampoline / cd /
  LIFO umount）、cwd 映射、shellQuote 边界、InvalidCwd 拒绝、共享存储
  可用性降级、su 并发消失窗口、home 幂等播种。
- **真机验收**（Root 设备，手工矩阵）：
  1. Magisk 授权后 TerminalBackends 显示 linux-ubuntu-chroot=READY；
  2. TerminalCreate(backend="linux-ubuntu-chroot") → bash -i 落 /workspace；
  3. `ps aux` / `ls /dev` / `apt update`（系统挂载真实性）；
  4. exit 后 `/proc/mounts` 无残留 rootfs 挂载（umount 收尾）；
  5. 双会话并发 + 先后退出（EBUSY 共享挂载清场）；
  6. PRoot 会话与 chroot 会话交替（home/workspace 数据互通）。
- **CI**：JVM 侧全量（无 Root 环境的真实 su/mount/chroot 不可达，诚实标注）。

## 5. 已知限制与路线图

1. **未授权 su**：availability 只探 su 存在性（stat 级）；未授权设备的
   spawn 在 su 处失败（PTY 如实呈现拒绝信息）。后续可接 platform/privilege
   的权限链（PrivilegeManager）做授权状态缓存。
2. **mountpoint 缺失的 ROM**：守卫失效退化为堆叠挂载（可用性不受损，
   挂载表翻倍；下一次正常退出仍会清一层）。
3. **/proc 挂载来源**：`mount -t proc` 挂宿主 proc（与 bind /proc 等价
  可见性；SystemBindProfile.STANDARD 语义对齐）。
4. **自动降级/升级**：ptrace 受限探测（proot 冒烟探针 + chroot 可用性
   联动）是后续工作；当前显式选择优先。
5. **Android 10+ W^X**：rootfs 内可执行段经 chroot 执行不受 W^X 限制
   （rootfs 在 app 数据目录，exec 权限由 rootfs 落盘时的 mode 位决定 ——
   与 PRoot 路径同源，无额外约束）。
