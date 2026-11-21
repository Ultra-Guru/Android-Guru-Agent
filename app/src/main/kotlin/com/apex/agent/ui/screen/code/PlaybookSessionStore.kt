package com.apex.agent.ui.screen.code

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * # Playbook Session Store — 工具通道 ↔ 控制器会话桥（task-playbooks PR-C）
 *
 * agent 工具（PlaybookTool，引擎执行线程）与 CodePlaybookController
 * （VM 作用域）的解耦桥：
 * - **快照下行**：控制器每次状态变更后经 [publish] 推快照，工具的
 *   list/advance 动作读当前会话（无快照 = 无挂载，advance 报错引导）；
 * - **命令上行**：工具的 advance 动作发 [commands]，Ops 层（VM scope）
 *   收集后驱动控制器手动推进 + 系统消息 + extras 热重织——工具线程
 *   永不直接改控制器状态。
 *
 * 纯内存进程内单通道（@Singleton）：剧本会话本就是 Coding 屏会话级
 * 状态，不持久化。
 */
@Singleton
class PlaybookSessionStore @Inject constructor() {

    /** 控制器推给工具侧的会话快照（null = 无挂载）。 */
    data class SessionSnapshot(
        val playbookId: String,
        val playbookName: String,
        val stageIndex: Int,
        val stageCount: Int,
        val stageTitle: String,
        val completedStageIndexes: List<Int>
    )

    /** 工具 → 控制器的推进命令。 */
    data class AdvanceCommand(
        val playbookId: String,
        /** 期望推进前幕（0 基）；null = 不校验（信任快照）。 */
        val fromStageIndex: Int?
    )

    private val _snapshot = MutableStateFlow<SessionSnapshot?>(null)
    val snapshot: StateFlow<SessionSnapshot?> = _snapshot.asStateFlow()

    /** 推进命令流（replay=1：Ops 尚未收集时的首条命令不丢）。 */
    val commands = MutableSharedFlow<AdvanceCommand>(replay = 1, extraBufferCapacity = 8)

    /** 控制器侧发布快照（状态变更后调用）。 */
    fun publish(snap: SessionSnapshot?) {
        _snapshot.value = snap
    }

    /** 工具侧发推进命令。 */
    suspend fun sendAdvance(command: AdvanceCommand) {
        commands.emit(command)
    }

    /** 清快照（VM onCleared / 会话结束）。 */
    fun clear() {
        _snapshot.value = null
    }
}
