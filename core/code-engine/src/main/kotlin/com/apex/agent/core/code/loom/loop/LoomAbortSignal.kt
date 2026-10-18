package com.apex.agent.core.code.loom.loop

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException

/**
 * # Loom Abort Signal — 织造任务中止信号
 *
 * 协作式中止：引擎 abort() 置位，回合机在**回合边界与流帧边界**检查
 * （[ensureNotRequested] 抛 [CancellationException] → 回合机 catch 后
 * 织出 Aborted 事件正常收场——收集方仍然活跃，emit 可用）。
 *
 * 与「作用域取消」的分工：外部作用域取消（VM 销毁）由协程体系自然
 * 传播；本信号服务**引擎主动 abort**——用户点停止、切线、权限弹窗
 * 收口等场景。
 */
class LoomAbortSignal {

    private val aborted = AtomicBoolean(false)

    /** 引擎请求中止（幂等）。 */
    fun request() {
        aborted.set(true)
    }

    /** 是否已请求中止。 */
    fun isRequested(): Boolean = aborted.get()

    /**
     * 检查点：已请求 → 抛 [CancellationException]（回合机捕获后织出
     * Aborted 收场）。
     */
    fun ensureNotRequested() {
        if (aborted.get()) {
            throw CancellationException("loom-abort")
        }
    }

    /** 复位（新任务）。 */
    fun reset() {
        aborted.set(false)
    }
}
