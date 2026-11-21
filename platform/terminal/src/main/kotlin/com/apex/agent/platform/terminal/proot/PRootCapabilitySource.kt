package com.apex.agent.platform.terminal.proot

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * T92（D5 完成度）：argv 能力源 —— 把 suspend 的 [PRootBinaryProvider.verify]
 * （含双能力探针 + 记忆化）桥接成**非挂起读取** `() -> PRootArgvCapabilities`。
 *
 * ## 为什么需要它（app 模块三类 argv 构造点的困境）
 *
 * `ProotCommandSpawner.spawn` / `ProotMcpProcessLauncher.launch` /
 * `ProotGitCommandRunner.run` 的 argv 组装函数是**非挂起**的（ExecEngine /
 * MCP launcher 的既有契约），无法在组装时点 await 探针；而它们的 argv
 * 又必须按探针能力自适应（T91 只贯通了 LinuxPRootBackend 交互会话路径，
 * 其余六条路径默认 Termux 基线 —— 二进制升级/替换后 apt/git/MCP/fs/probe
 * 全部拒启而终端会话正常，静默分叉）。
 *
 * ## 语义（诚实降级，两层）
 *
 *  - **构造即后台预取**（fire-and-forget，SupervisorJob 隔离 —— AGENTS.md
 *    纪律）：app DI 单例构造时在 [scope] 上启动一次 verify；
 *  - **读取恒即时返回**当前已知能力：预取完成前 = [PRootArgvCapabilities.UPSTREAM_SAFE]
 *    （保守省略两项 —— 省略形状在任何 proot 上均合法，最坏丢清理性旗标与
 *    显式分界，绝不拒启）；完成后 = 探针实测结果（provider 内部按二进制
 *    路径记忆化，本类 refresh 不产生重复 exec —— 仅版本探针重跑一次）。
 *
 * 预取窗口（进程冷启动后首次探测的 ~百毫秒级）内发出的命令走保守形状 ——
 * 与 T91「探针不可判定 → 保守省略」哲学一致；预取完成后 argv 与交互会话
 * 路径完全一致。
 *
 * ## 失败语义
 *
 * locate/verify 失败（无二进制/ELF 损坏）→ 保持 UPSTREAM_SAFE（argv 照常
 * 构造，后续真实 exec 会以既有错误路径如实报错 —— 能力源不新增失败面）。
 */
class PRootCapabilitySource(
    private val provider: PRootBinaryProvider,
    /** 预取域（测试注入 TestScope；生产默认 SupervisorJob + IO）。 */
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : () -> PRootArgvCapabilities {

    @Volatile
    private var current: PRootArgvCapabilities = PRootArgvCapabilities.UPSTREAM_SAFE

    init {
        scope.launch { refresh() }
    }

    /** 立即返回当前已知能力（预取未完成 = 保守省略基线 —— 见类 KDoc）。 */
    override fun invoke(): PRootArgvCapabilities = current

    /** 重新探测并更新（幂等廉价：provider 内部记忆化能力探针结果）。 */
    suspend fun refresh() {
        val binary = provider.locate().getOrNull() ?: return
        val info = provider.verify(binary).getOrNull() ?: return
        current = info.capabilities
    }
}
