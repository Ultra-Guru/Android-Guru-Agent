# TERMINAL_CHROOT_MATURITY_T99 —— chroot 后端生产化纵深方案

> 状态：**Spec（待评审）**。T98（`docs/terminal/TERMINAL_CHROOT_BACKEND_T98.md`，
> PR 落地 `platform/terminal/.../chroot/`）交付了「真实 Root 的 chroot 执行
> 后端」骨架：SuBinaryLocator stat 级探测 + `mountpoint -q` 幂等挂载 +
> LIFO 最佳努力 umount。本篇是其后继纵深方案：把 T98 文档 §5 与代码
> KDoc 里自认的六个「后续工作」逐条清偿，并给出 Agent 可执行的实施
> 分解（任务包结构对齐 `docs/agent-task-packages.md`）。
>
> 关联：T98（前序）· T92（权限链审计）· T88（env trampoline）·
> T82/T83（Ubuntu 生命周期与诚实降级）· T72（rootfs 供给）·
> `docs/permission-degradation-ladder.md`（权限降级阶梯，共享 §A/§B 语义）。

---

## 0. 一句话定位

T98 回答了「能不能 chroot」（能，双后端显式可选）；T99 回答
「chroot 能不能像 PRoot 一样**无人值守地可靠**」——授权状态可缓存、
挂载可自愈、后端选择有依据、https 能通、权限链能联动。

## 1. 现状盘点（基于 main 分支代码逐一核对）

| # | 现状 | 证据（文件 / 行号） | 缺口 |
|---|------|--------------------|------|
| 1 | su 发现有**两套不一致的候选表** | `chroot/SuBinaryLocator.kt` L53-60（6 条：system/bin、system/xbin、system/sbin、sbin、debug_ramdisk、su/bin）vs `platform/privilege/PrivilegeDetector.kt` L44-75（7 条：多 `/data/adb/ksu/bin/su` KernelSU 与 `/data/adb/ap/bin/su` APatch，少 system/sbin、debug_ramdisk） | 同一台 KernelSU 设备：PrivilegeDetector 报 ROOT、chroot 后端报 `SU_NOT_FOUND` |
| 2 | availability **只探存在性，不探授权** | `SuBinaryLocator.locate()` 仅 `isFile && canExecute`；KDoc L8-13 自述「找到 su 不承诺授权」 | 未授权设备每次 `TerminalCreate(backend="linux-ubuntu-chroot")` 都 spawn 失败，PTY 如实呈现 su 拒绝信息——模型只能反复撞墙，没有「先引导授权」的路径 |
| 3 | su 授权结果**无缓存** | T98 文档 §5.1：「后续可接 platform/privilege 的权限链（PrivilegeManager）做授权状态缓存」 | 用户在 Magisk 弹窗点了「永久允许」，App 无从得知，下次会话仍按未知处理 |
| 4 | `mountpoint` 缺失的 ROM 守卫失效 | T98 文档 §5.2：守卫失效退化为堆叠挂载（`mount` 由 toybox 提供，Android 6+ 通常有；但部分 OEM 精简 ROM 与用户自刷 GSI 缺失） | 挂载表逐次翻倍，`/proc/mounts` 膨胀；退出时每次只清一层 |
| 5 | **无 PRoot 健康信号**，后端选择纯靠模型记忆 | `PRootArgvCapabilities`（`proot/PRootBackend.kt` L49-62）只探**二进制选项**（kill-on-exit / `--` 分隔符），不探**运行时 ptrace 可用性**；T98 文档 §5.4 自认「ptrace 受限探测是后续工作」 | HyperOS/ColorOS 收紧 `yama.ptrace_scope` 后 proot 假死（僵尸进程、终端黑屏）——模型在黑屏后才意识到该换后端，浪费整轮会话 |
| 6 | rootfs 内**无 CA bundle** | `docs/ubuntu-rootfs-t72.md` L136-140：「CA bundle 在 Android 生产缺失（http apt 源不受影响）」 | chroot/PRoot 双后端同样受害：`apt` 走 https 源、`pip install`、`git clone https://` 全部 TLS 失败，bootstrap 降级为「READY + bootstrapNote」的频率被人为抬高 |

另有两处**横向不一致**（与 `docs/permission-degradation-ladder.md` 共治）：

| # | 不一致 | 证据 |
|---|--------|------|
| 7 | `DefaultPrivilegeManager.executeShell` 与 `PrivilegeDetector.executeShell` 是两套并行选路：前者无本地 shell 兜底（面向必须有权限的调用方，L112-128），后者有（`sh -c` 兜底，L170-207） | `platform/privilege/DefaultPrivilegeManager.kt` vs `PrivilegeDetector.kt`；行为差异仅存在于注释 |
| 8 | chroot 可用性对 `EnvironmentState` **零贡献** | `EnvironmentStateUpdater`（app/platform/）只接 accessibility/keyboard/ubuntu_ready/root/shizuku 五路；`linux-ubuntu-chroot` 后端 READY 不产生任何遥测 | 模型看不到「chroot 可用」这一事实，每轮 prompt 的 environment 摘要缺失一行可 exploit 的能力 |

## 2. 目标与非目标

**目标**（全部可被 Agent 按 `docs/agent-task-packages.md` 的任务包结构执行）：

- G1 **统一 su 发现**：单一事实源，双消费方（PrivilegeDetector / SuBinaryLocator）共享同一候选表与探测语义；
- G2 **授权状态缓存**：su 授权结果进入 PrivilegeManager 状态流，chroot availability 三态化（含「su 存在但未授权」的引导语义）；
- G3 **挂载自愈**：mountpoint 缺失 ROM 上不堆叠挂载，异常退出的残留挂载可被下次会话清理；
- G4 **PRoot 冒烟探针**：廉价、有 TTL、可失效的 ptrace 健康信号，作为后端选择的**显式建议**（不做隐式自动切换——T98 的诚实性取舍不变）；
- G5 **CA bundle 供给**：rootfs 配置期落盘系统 CA，https 工具链（apt/pip/git）开箱可用；
- G6 **chroot 遥测接活**：`chroot_ready` 旗标进 EnvironmentState 摘要，模型每轮可见。

**非目标**（明确不做，防止范围蔓延）：

- 不做「proot 假死时自动切 chroot」的隐式切换——可用性探测无法廉价区分「ptrace 受限」与「proot 版本差异」（T98 §2 的取舍继续有效），只做**建议**；
- 不做 Shizuku 通道的 chroot 变体（Shizuku uid=2000 无 mount 权限，语义不成立）；
- 不重写 `ExecutionBackend` 抽象（`runtime/ExecutionBackend.kt` 冻结契约不动）；
- 不引入新的 native 依赖（全部走既有 toybox / rootfs 内工具）。

## 3. 方案设计

### §A 统一 su 发现源 —— `SuCandidateRegistry`（清偿缺口 1）

**落点**：`platform/privilege` 新增 `su/SuCandidateRegistry.kt`（object，纯 JVM 可测）。

```kotlin
object SuCandidateRegistry {
    /** 单一事实源：并集 + 语义注释（探测顺序 = 列表顺序） */
    val CANDIDATES: List<SuCandidate> = listOf(
        // —— 系统位（多数 Magisk 方案 symlink 到这里）——
        SuCandidate(path = "/system/bin/su",        manager = MANAGER_GENERIC),
        SuCandidate(path = "/system/xbin/su",       manager = MANAGER_GENERIC),
        SuCandidate(path = "/system/sbin/su",       manager = MANAGER_GENERIC),
        SuCandidate(path = "/sbin/su",              manager = MANAGER_GENERIC),
        // —— Magisk 官方位（29147f8+）/ APatch debug_ramdisk 位 ——
        SuCandidate(path = "/debug_ramdisk/su",     manager = MANAGER_APATCH),
        // —— phh superuser ——
        SuCandidate(path = "/su/bin/su",            manager = MANAGER_PHH),
        // —— KernelSU / APatch 用户位（PrivilegeDetector 独有，T99 并入）——
        SuCandidate(path = "/data/adb/ksu/bin/su",  manager = MANAGER_KERNELSU),
        SuCandidate(path = "/data/adb/ap/bin/su",   manager = MANAGER_APATCH),
    )

    /** stat 级探测（绝不 exec —— 授权弹窗副作用纪律，见 SuBinaryLocator KDoc） */
    fun locate(): SuCandidate? = CANDIDATES.firstOrNull { candidate ->
        runCatching { File(candidate.path).let { it.isFile && it.canExecute() } }.getOrDefault(false)
    )
}
```

**消费方改造**（行为对齐，非破坏性）：

| 消费方 | 现状 | 改后 |
|--------|------|------|
| `chroot/SuBinaryLocator.locate()` | 自持 6 条 | 委托 `SuCandidateRegistry.locate()`；`extraCandidates`（app 层用户指定路径）仍前插优先 |
| `PrivilegeDetector.detectRoot()` | 自持 7 条 + `su -c id` 实测 | 候选表换 `SuCandidateRegistry`；**实测语义保留**（桌面 CI 误报 root 的教训，L57-63 注释） |

**注意**：`PrivilegeDetector.detectRoot` 的 `su -c id` 实测会拉起授权弹窗——这是**授权语义**（用户主动触发 root 检测场景），与 `SuBinaryLocator` 的**可用性语义**（每次会话创建都调，必须无副作用）不同。T99 把这一区分显式化：

- **可用性探测**（availability 链路）：stat 级，绝不 exec —— 不变；
- **授权确认**（§B 新增）：仅在用户/模型显式发起「使用 chroot 后端」动作时 exec 一次，结果缓存。

### §B su 授权状态缓存 —— 三态 availability（清偿缺口 2/3）

**现状**：`BackendAvailability` 三态是 `Ready / NeedsRootfs / Failed`。「su 存在但未授权」落在 `Ready`——模型创建会话才撞墙。

**改造**（不改 `BackendAvailability` 密封类——冻结契约，扩展 `Failed` 的 reason 命名空间）：

```kotlin
// LinuxChrootBackend.availability() 伪代码
val su = suLocator.locate() ?: return Failed("ChrootError:SU_NOT_FOUND — …")
// —— T99 新增：授权缓存查询（读 PrivilegeManager，不 exec）——
when (authorizationCache.state(su)) {
    GRANTED   -> /* 继续走 rootfs 检查，Ready */
    DENIED    -> return Failed("ChrootError:SU_NOT_AUTHORIZED — " +
        "su binary exists but authorization was denied. " +
        "Fix: open Magisk/KernelSU app and grant superuser access to this app, " +
        "or use backend 'linux-ubuntu' (PRoot) instead.")
    UNKNOWN   -> /* 维持 Ready —— stat 找到即报可用（诚实性：没试过就不谎报拒绝） */
}
```

**授权缓存的状态机**（落点 `platform/privilege` 新增 `su/SuAuthorizationCache.kt`）：

```
UNKNOWN ──(首次 spawn 前 exec 探测：su -c id, 3s 超时)──┬─→ GRANTED（uid=0）
                                                        ├─→ DENIED（非零退出/超时/输出非 uid=0）
                                                        └─→ UNKNOWN（探测本身异常，不升级）
GRANTED ──(spawn 失败且 stderr 含授权拒绝特征)──→ DENIED（负反馈修正，防缓存漂移）
DENIED  ──(用户从设置页重置 / PrivilegeManager.refreshStatus)──→ UNKNOWN
```

- **TTL 纪律**：GRANTED/DENIED 缓存 **30s TTL**（与 `PrivilegeDetector.CACHE_TTL_MS` 同值，L26）——用户随时可能在 Magisk 里改授权，长缓存会撒谎；
- **负反馈修正**：缓存认为 GRANTED 但 spawn 在 su 处失败（PTY 输出含 `denied` / `not granted` 特征）→ 立即降级 DENIED 并记录 `privilege-chain` 审计日志（对齐 T92 §1 的日志三件套格式）；
- **决不主动 exec**：cache miss 时 availability 仍报 Ready（UNKNOWN 语义）；真正的 exec 探测只发生在 `prepare()` 之前的显式动作里（TerminalCreate 工具调用即是），且结果立刻入缓存——下一次 availability 就能拿到三态。

**授权引导闭环**（模型侧）：`Failed(SU_NOT_AUTHORIZED)` 的 reason 文本含 Fix 指引（如上伪代码）——`ToolEnvironmentGate.fixHint` 同款惯例（`core/tool-registry/.../ToolEnvironmentState.kt` L213-242），模型读到即可向用户转述，或降级选 `linux-ubuntu`。

### §C 挂载自愈 —— `MountGuard`（清偿缺口 4）

**现状**：`LinuxChrootBackend.buildScript`（L214-255）的幂等守卫完全依赖 `mountpoint -q`。

**方案**：守卫函数降级三链 —— `mountpoint -q` → `/proc/mounts` grep（rootfs 路径 + 挂载点拼接比对）→ 无守卫直挂（最后手段）。

```sh
# buildScript 守卫段改造（伪代码，生成脚本时按探测结果选定一个分支）
if command -v mountpoint >/dev/null 2>&1; then
    mountpoint -q "$R/proc" || mount -t proc proc "$R/proc" 2>/dev/null
elif grep -qs " $R/proc " /proc/mounts; then
    :   # 已挂载（grep 守卫命中）
else
    mount -t proc proc "$R/proc" 2>/dev/null   # 无守卫直挂（退化为 T98 现状）
fi
```

**残留清理（Mount Sweeper）**：会话启动脚本头部追加**孤儿挂载检测**——扫描 `/proc/mounts` 中 `rootfs 路径前缀` 的挂载项，若对应挂载点在 `/proc/mounts` 中出现 ≥2 次同一 target（堆叠证据）且无活跃会话引用（会话计数文件 `$R/../.chroot-sessions` 计数器），LIFO 追加一轮 `umount`。计数器语义：

- spawn 脚本开头 `echo $$ >> <sessions-file>`（+1）；
- 退出 trap（`trap '… umount LIFO …; decrement' EXIT`——现有 LIFO 段挪进 trap，异常退出也能清场）；
- 计数为 0 且仍有挂载残留 → 下一会话启动时清理。

**诚实边界**：sweeper 是**最佳努力**，失败静默（与现有 LIFO umount 语义一致）；审计日志 `privilege-chain mount-sweeper removed=N stacked` 记录清理量，方便排查 OEM ROM 行为。

### §D PRoot 冒烟探针 + 后端建议（清偿缺口 5）

**落点**：`platform/terminal/proot/` 新增 `PRootSmokeProbe.kt`。

```kotlin
class PRootSmokeProbe(
    private val binaryProvider: PRootBinaryProvider,
    private val rootfsProvider: RootfsProvider,
    private val clock: () -> Long,          // 假钟注入（测试纪律）
) {
    /** 冒烟探针：proot -r <rootfs> /bin/true，5s 超时
     *  返回 SmokeResult { OK, PTRACE_BLOCKED(exit=SIGTRAP/挂起超时), BINARY_ERROR(其它非零) } */
    suspend fun probe(): SmokeResult

    /** TTL 缓存（默认 10 分钟）：挂起类失败不重试直到 TTL 过期或 invalidate() */
    fun invalidate(reason: String)          // 网络切换 / rootfs 重装 / 设置变更时调用
}
```

- **判定矩阵**：exit 0 → OK；超时（5s 无输出且进程存活——ptrace attach 挂起的典型形态）→ PTRACE_BLOCKED；SIGTRAP/SIGSYS → PTRACE_BLOCKED；其它非零 → BINARY_ERROR（不归咎 ptrace）；
- **消费点**：`TerminalBackends` 工具（tools/v2/，双后端可用性清单）的输出**追加建议行**：

```
backend linux-ubuntu: READY (smoke: ok)
backend linux-ubuntu-chroot: READY
suggestion: proot smoke probe reports PTRACE_BLOCKED (yama/SELinux policy likely).
            Sessions may hang on this device. Consider backend 'linux-ubuntu-chroot'
            if superuser is granted, or report the issue.
```

- **纪律**：建议只出现在 `TerminalBackends` 的输出与审计日志，**不改变** `ExecutionBackendRegistry` 的注册顺序、不自动改写 TerminalCreate 的默认路由（T98 §2「不做隐式自动切换」不变）；
- **触发时机**：首次调用 `TerminalBackends` 工具时懒执行 + TTL 缓存；后续调用读缓存（可用性探测「廉价无副作用」纪律——probe 有 exec 副作用，所以它**不在 availability 链路里**，是工具层的独立信号）。

### §E CA bundle 供给（清偿缺口 6）

**落点**：`ubuntu/RootfsConfigurator.kt`（rootfs 配置阶段追加）。

**方案**（离线优先，三链降级）：

1. **bundled 优先**：构建期把 `ca-certificates` 的 `cacert.pem`（Debian 证书包产物）打进 rootfs tarball 的 `/etc/ssl/certs/ca-certificates.crt`（`BundledRootfsSource` 构建 pipeline 追加，`POINT_VERSION = "24.04.4"`（L205）不变，checksum 相应重算钉死——T72 发布真值表流程）；
2. **宿主回填**（已装 rootfs 的存量修复）：配置阶段检测 `/etc/ssl/certs/ca-certificates.crt` 缺失或为空 → 从宿主 Android CA（`/system/etc/security/cacerts/` PEM 集合，需 root/shizuku 通道读取；无权限则跳过）拼接落盘；
3. **apt 引导**（兜底，需网络）：`bootstrapNote` 里如实标注「CA 缺失，https 源不可用；已改写 sources.list 为 http 镜像」——现有 `AptMirrorRegistry` 机制延伸，**绝不伪造空 bundle**（T72 纪律）。

**验收命令**（rootfs READY 后）：`curl -sI https://deb.debian.org` 返回 HTTP 200 头（chroot 与 PRoot 双后端各验一次）。

### §F chroot 遥测接活（清偿缺口 8）

**落点**：`app/platform/EnvironmentStateUpdater.kt` 追加第六路收集：

```
terminalBackendRegistry.chrootAvailability → Flags.CHROOT_READY
```

- `LinuxChrootBackend.availability() == Ready` → `chroot=on`；`Failed(SU_NOT_FOUND)` → `chroot=off`；**其余（NeedsRootfs/UNKNOWN 授权态）→ 不写入**（unknown 语义 = fail-open，`ToolEnvironmentState` L24-29 纪律）；
- prompt 摘要追加行：`environment: … root=on shizuku=off ubuntu=on chroot=on …`；
- **不声明任何工具的 requiredEnv 消费它**（能降级到 PRoot 的能力绝不门禁——`EnvironmentAwareTool` KDoc L162-169 纪律），仅可见性。

## 4. 架构总览（T99 后）

```
┌────────────────────────────────────────────────────────────────────┐
│ Agent / 模型                                                        │
│   每轮 prompt: environment: … ubuntu=on chroot=on …（§F）           │
│   TerminalBackends 工具输出：双后端可用性 + smoke 建议（§D）          │
├────────────────────────────────────────────────────────────────────┤
│ 工具层 tools/v2                                                     │
│   TerminalCreate(backend=…) ── 失败原因含 Fix 指引（§B 授权三态）     │
├────────────────────────────────────────────────────────────────────┤
│ runtime 层（契约冻结）                                               │
│   ExecutionBackendRegistry ── PRoot（首位默认）/ chroot（显式）      │
│        │ availability:  stat 级 su + rootfs + 授权缓存读（§A/§B）   │
│        │ prepare:       spawn 脚本 = 三链守卫 + trap 清场（§C）      │
├────────────────────────────────────────────────────────────────────┤
│ platform/privilege（T99 扩两个文件）                                │
│   SuCandidateRegistry（§A 单一事实源，双消费方）                     │
│   SuAuthorizationCache（§B 30s TTL + 负反馈修正）                    │
├────────────────────────────────────────────────────────────────────┤
│ ubuntu 供给层                                                        │
│   RootfsConfigurator += CA bundle 三链（§E）                        │
│   PRootSmokeProbe（§D，TTL 缓存，不在 availability 链路）            │
└────────────────────────────────────────────────────────────────────┘
```

## 5. 实施分解（Agent 任务包）

> 结构对齐 `docs/agent-task-packages.md`：每包独立可交付、可验证、可回滚。
> 依赖关系：P1 → P2 →（P3 ∥ P4 ∥ P5）→ P6。

### P1 `su-unify` —— 统一 su 发现源（§A）
- **改**：新增 `platform/privilege/su/SuCandidateRegistry.kt`；`SuBinaryLocator` / `PrivilegeDetector.detectRoot` 改为消费它
- **验收**：新增 `SuCandidateRegistryTest`（候选表并集完整性 8 条、探测顺序、stat 语义、extraCandidates 前插优先）；`PrivilegeDetectorAuditTest` 既有 3 项不红
- **风险**：无行为破坏（并集超集化，只会「更找得到」）

### P2 `su-auth-cache` —— 授权状态缓存（§B）
- **改**：新增 `SuAuthorizationCache.kt`；`LinuxChrootBackend.availability()` 接缓存；spawn 失败负反馈修正
- **验收**：假钟注入测 TTL 过期；`LinuxChrootBackendTest` 追加授权三态矩阵（GRANTED/DENIED/UNKNOWN × rootfs 就绪/缺失）；reason 文本含 Fix 指引断言
- **风险**：DENIED 误报（用户其实想授权）→ TTL 30s 自愈 + refreshStatus 重置

### P3 `mount-guard`（§C，可与 P4/P5 并行）
- **改**：`LinuxChrootBackend.buildScript` 守卫三链 + trap 清场 + 会话计数
- **验收**：`LinuxChrootBackendTest` 脚本契约断言更新（trap 存在、计数器读写、三链分支生成）；sweeper 逻辑 JVM 单测（/proc/mounts 文本 fixture）
- **风险**：trap 语法错误 = 全部会话起不来 → 脚本模板进 JVM 测试逐行断言（既有纪律）

### P4 `smoke-probe`（§D，可并行）
- **改**：新增 `PRootSmokeProbe.kt`；`TerminalBackends` 工具输出追加建议行
- **验收**：`PRootSmokeProbeTest`（OK/超时/SIGTRAP/其它非零四态判定矩阵，假钟驱动）；建议行格式快照测试；**不改变注册顺序**的回归断言
- **风险**：probe 有副作用（exec）→ 严格限定在工具层触发 + TTL 缓存 + invalidate 钩子

### P5 `ca-bundle`（§E，可并行）
- **改**：rootfs 构建 pipeline（`BundledRootfsSource` 构建脚本）+ `RootfsConfigurator` 存量回填 + `AptMirrorRegistry` http 兜底标注
- **验收**：配置后 `/etc/ssl/certs/ca-certificates.crt` 非空断言（单测 fixture）；`curl -sI https://…` 真机验收（标注 NOT VERIFIED 诚实声明——对齐 ubuntu-lifecycle.md L73-81 惯例）
- **风险**：checksum 重算牵动发布真值表 → 与 rootfs workflow（`.github/workflows/rootfs.yml`）联动，版本号递增

### P6 `chroot-telemetry`（§F，收尾）
- **改**：`EnvironmentStateUpdater` 第六路 + `ToolEnvironmentState.Flags.CHROOT_READY`
- **验收**：`EnvironmentStateUpdaterTest` 三态映射（Ready→on / SU_NOT_FOUND→off / 其它→absent）；prompt 摘要快照
- **风险**：无（纯可见性，fail-open）

## 6. 测试策略总览

| 层 | 手段 | 覆盖 |
|----|------|------|
| JVM 单测 | 假钟 + 脚本模板逐行断言 + /proc/mounts fixture | §A/§B/§C/§D/§F 全量（延续 T98 `LinuxChrootBackendTest` 纪律） |
| 真机矩阵 | Magisk / KernelSU / APatch 各一台 + 一台 ptrace 受限机型（HyperOS） | §B 授权三态跨管理器、§C trap 清场、§D 建议行、§E curl 验收 |
| CI | 既有 `:platform:terminal:testDebugUnitTest` + `:platform:privilege:testDebugUnitTest`（T92 已入矩阵）步骤，无新增 job |
| 诚实声明 | 真机未跑的部分在 spec 转 PR 时于文末标注 NOT VERIFIED（对齐 ubuntu-lifecycle.md 惯例） | —— |

## 7. 已知限制与路线图

1. **DENIED 特征匹配是启发式**（stderr 含 denied/not granted）——多语言 ROM 输出可能漏判，漏判仅退化为 UNKNOWN（fail-open），不误伤；
2. **smoke probe 的 PTRACE_BLOCKED 判定**无法区分 yama scope 与 SELinux 策略两种根因（`getprop` 可辅助，但跨 ROM 差异大）——建议文本措辞用 "likely"，不硬下结论；
3. **宿主 CA 回填**（§E 链 2）依赖 root/shizuku 读 `/system/etc/security/cacerts/`——无权限设备跳过，走链 3；
4. **后续（T100 候选）**：chroot 后端的 `mount --bind` 换 `mount --rbind`（递归 bind，`/dev` 子挂载完整性）——需要真机矩阵验证 OEM 兼容性后立项；
5. **后续（T100 候选）**：`SuAuthorizationCache` 与 `DefaultPrivilegeManager` 的 root 状态流合并（缺口 7 的完整清偿在 `docs/permission-degradation-ladder.md` §B 统一处理）。

## 8. 里程碑

| 包 | 规模预估 | 依赖 |
|----|----------|------|
| P1 su-unify | ~120 行 main + ~150 行 test | 无 |
| P2 su-auth-cache | ~200 行 main + ~250 行 test | P1 |
| P3 mount-guard | ~150 行 main + ~200 行 test | P2（脚本模板共享） |
| P4 smoke-probe | ~180 行 main + ~220 行 test | 无（可与 P1 并行启动） |
| P5 ca-bundle | ~100 行 main + ~80 行 test + 构建脚本 | 无（可并行） |
| P6 chroot-telemetry | ~60 行 main + ~80 行 test | P1（旗标语义依赖 §A） |

全部任务包 JVM 侧可验证（真机矩阵独立于合并门禁，NOT VERIFIED 声明兜底）——
满足「Agent 可执行」的硬约束：每包改动 ≤3 文件、验收可脚本化、回滚单 commit revert。
