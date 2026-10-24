package com.apex.agent.core.code.stream

import com.apex.agent.core.engine.AgentEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CodeStreamSession] 事件状态机测试。
 *
 * 覆盖：工具胶囊生命周期（含幂等重放）/ 思考与正文流式 / 终端脉冲与
 * 尾窗 / 验证轮次聚合 / 错误·停止·完成收尾 / tick 攒批语义 /
 * 受影响文件追踪。
 */
class CodeStreamSessionTest {

    private fun editStart(callId: String, path: String) = AgentEvent.ToolCallStart(
        callId, "CodeEdit", """{"path":"$path","diff":"x"}"""
    )

    private fun bashStart(callId: String, command: String) = AgentEvent.ToolCallStart(
        callId, "TerminalExec", """{"command":"$command"}"""
    )

    @Test
    fun `tool capsule goes through full lifecycle`() {
        val s = CodeStreamSession()
        s.beginRun("修一下 bug")
        s.onEvent(editStart("c1", "src/App.kt"))
        s.onEvent(
            AgentEvent.ToolCallComplete(
                callId = "c1", toolName = "CodeEdit", arguments = """{"path":"src/App.kt"}""",
                output = "(no textual change)", fullOutput = "--- a/src/App.kt\n+++ b/src/App.kt\n@@ -1,2 +1,2 @@\n-a\n+b\n(1 added, 1 removed)",
                success = true, durationMs = 120
            )
        )
        val snap = s.snapshot()
        val capsule = snap.entries.filterIsInstance<StreamEntry.ToolCapsuleEntry>().single()
        assertEquals(ToolKind.EDIT_FILE, capsule.call.kind)
        assertEquals("src/App.kt", capsule.call.target)
        assertEquals("编辑类成功 → APPLIED", ToolCallStatus.APPLIED, capsule.call.status)
        assertEquals(1, capsule.call.hunksTotal)
        assertEquals(1, capsule.call.hunksApplied)
        assertEquals("+1 −1 · 1 hunk", capsule.call.summary)
        assertEquals(listOf("src/App.kt"), snap.affectedFiles)
    }

    @Test
    fun `bash capsule tracks terminal pulses and exit code`() {
        val s = CodeStreamSession()
        s.beginRun("跑构建")
        s.onEvent(bashStart("b1", "./gradlew assemble"))
        repeat(8) { s.onEvent(AgentEvent.ToolOutputChunk("b1", "compile ok\n")) }
        // 行数条件（8 行）命中脉冲
        val snap = s.tick(0)
        assertNotNull("脉冲后应产出快照", snap)
        assertEquals("b1", snap!!.activeTerminalCallId)
        assertTrue(snap.terminalContent.contains("compile ok"))
        s.onEvent(
            AgentEvent.ToolCallComplete(
                callId = "b1", toolName = "TerminalExec", arguments = "",
                output = "build finished\nexit code 0", fullOutput = "",
                success = true, durationMs = 9_000
            )
        )
        val final = s.snapshot()
        val capsule = final.entries.filterIsInstance<StreamEntry.ToolCapsuleEntry>().single()
        assertEquals(ToolCallStatus.SUCCESS, capsule.call.status)
        assertEquals("exit 0", capsule.call.summary)
        assertEquals(0, capsule.call.exitCode)
    }

    @Test
    fun `idempotent replay of same call events does not duplicate`() {
        val s = CodeStreamSession()
        s.beginRun("task")
        val start = editStart("c1", "A.kt")
        s.onEvent(start)
        s.onEvent(start) // 重放
        val complete = AgentEvent.ToolCallComplete(
            "c1", "CodeEdit", """{"path":"A.kt"}""", "done", "--- a/A.kt\n+++ b/A.kt\n@@ -1,1 +1,2 @@\n x\n+y\n(1 added, 0 removed)",
            true, 50
        )
        s.onEvent(complete)
        s.onEvent(complete) // 重放
        val snap = s.snapshot()
        assertEquals("幂等：重放不产生重复胶囊", 1, snap.toolCallCount)
        assertEquals(1, snap.entries.count { it is StreamEntry.ToolCapsuleEntry })
    }

    @Test
    fun `thinking and assistant stream incrementally then finalize`() {
        val s = CodeStreamSession()
        s.beginRun("问个问题")
        s.onEvent(AgentEvent.ThinkingChunk("想一"))
        s.onEvent(AgentEvent.ThinkingChunk("想二"))
        s.onEvent(AgentEvent.ThinkingComplete("想一想想二"))
        s.onEvent(AgentEvent.ResponseChunk("结论"))
        s.onEvent(AgentEvent.ResponseChunk("是……"))
        s.onEvent(AgentEvent.ResponseComplete("结论是……"))
        val snap = s.snapshot()
        val thinking = snap.entries.filterIsInstance<StreamEntry.ThinkingEntry>().single()
        assertEquals("想一想想二", thinking.text)
        assertTrue(!thinking.isStreaming)
        val assistant = snap.entries.filterIsInstance<StreamEntry.AssistantEntry>().single()
        assertEquals("结论是……", assistant.text)
        assertTrue(!assistant.isStreaming)
    }

    // ═══ 空思考卡防护（2026-10 用户反馈：不思考的模型也被网关喂空串）═══

    @Test
    fun `empty reasoning chunks never create thinking card`() {
        val s = CodeStreamSession()
        s.beginRun("你好")
        // 网关对不思考的模型每帧发空串 reasoning_content
        repeat(5) { s.onEvent(AgentEvent.ThinkingChunk("")) }
        // 引擎侧 reasoningBuilder 恒空 → 不发 ThinkingComplete（模拟真实时序）
        s.onEvent(AgentEvent.ResponseChunk("你好！需要帮助吗？"))
        s.onEvent(AgentEvent.ResponseComplete("你好！需要帮助吗？"))
        val snap = s.snapshot()
        assertEquals(
            "空串帧不建思考卡", 0,
            snap.entries.count { it is StreamEntry.ThinkingEntry }
        )
        assertEquals(1, snap.entries.count { it is StreamEntry.AssistantEntry })
    }

    @Test
    fun `whitespace-only first chunk does not create card but real chunk does`() {
        val s = CodeStreamSession()
        s.beginRun("你好")
        s.onEvent(AgentEvent.ThinkingChunk("  "))
        assertEquals(0, s.snapshot().entries.count { it is StreamEntry.ThinkingEntry })
        s.onEvent(AgentEvent.ThinkingChunk("认真想"))
        val snap = s.snapshot()
        val thinking = snap.entries.filterIsInstance<StreamEntry.ThinkingEntry>().single()
        assertEquals("空白前导被丢弃，正文完整", "认真想", thinking.text)
    }

    @Test
    fun `thinking complete with blank text removes placeholder card`() {
        val s = CodeStreamSession()
        s.beginRun("你好")
        s.onEvent(AgentEvent.ThinkingChunk(""))
        // 某些 provider 先发空串帧、收尾时 fullThought 也是空白
        s.onEvent(AgentEvent.ThinkingComplete("  "))
        assertEquals(
            "空白收尾不残留思考卡", 0,
            s.snapshot().entries.count { it is StreamEntry.ThinkingEntry }
        )
    }

    @Test
    fun `beginRun after empty thinking leaves no stale placeholder`() {
        val s = CodeStreamSession()
        s.beginRun("第一轮")
        s.onEvent(AgentEvent.ThinkingChunk(""))
        s.beginRun("第二轮") // 中止路径无 Complete，beginRun 收口
        assertEquals(
            "跨轮收口移除空白占位", 0,
            s.snapshot().entries.count { it is StreamEntry.ThinkingEntry }
        )
    }

    @Test
    fun `blank response chunks never create empty assistant bubble`() {
        val s = CodeStreamSession()
        s.beginRun("你好")
        // provider 发 content:"" 的 tool_calls 前导帧
        s.onEvent(AgentEvent.ResponseChunk(""))
        assertEquals(0, s.snapshot().entries.count { it is StreamEntry.AssistantEntry })
        s.onEvent(AgentEvent.ResponseComplete(""))
        assertEquals(
            "空白收尾不残留空气泡", 0,
            s.snapshot().entries.count { it is StreamEntry.AssistantEntry }
        )
    }

    // ═══ 思考卡防御性收口（2026-10 用户反馈：思考完成后仍显示「思考中」）═══
    // 引擎 notice 类思考分片（工具降级/重试/GOAL 提示）与标准线库事件集都
    // 只发 ThinkingChunk 不发 ThinkingComplete —— 旧实现思考卡一旦建立就
    // 永远挂着「思考中…」，直到下一轮 beginRun。

    @Test
    fun `thinking card finalizes when tool starts without complete`() {
        val s = CodeStreamSession()
        s.beginRun("装 adb 工具")
        s.onEvent(AgentEvent.ThinkingChunk("[engine] 降级提示"))
        s.onEvent(bashStart("b1", "apt install adb"))
        val thinking = s.snapshot().entries.filterIsInstance<StreamEntry.ThinkingEntry>().single()
        assertEquals("已累积文本保留", "[engine] 降级提示", thinking.text)
        assertTrue("工具开跑即收口，不再显示思考中", !thinking.isStreaming)
    }

    @Test
    fun `thinking card finalizes on response complete without complete event`() {
        // GOAL 停止路径：ThinkingChunk(userNotice) 紧跟 ResponseComplete
        val s = CodeStreamSession()
        s.beginRun("目标收尾")
        s.onEvent(AgentEvent.ThinkingChunk("目标已达成"))
        s.onEvent(AgentEvent.ResponseComplete("最终结论"))
        val thinking = s.snapshot().entries.filterIsInstance<StreamEntry.ThinkingEntry>().single()
        assertTrue("回复收尾即收口", !thinking.isStreaming)
    }

    @Test
    fun `residual streaming entries finalize on complete event`() {
        // 标准线库事件集没有思考完成事件：整轮只有 Chunk，收尾靠 Complete
        val s = CodeStreamSession()
        s.beginRun("标准线任务")
        s.onEvent(AgentEvent.ThinkingChunk("推理中"))
        s.onEvent(AgentEvent.ResponseChunk("结论前半"))
        s.onEvent(AgentEvent.Complete("done", 1, 0, 100))
        val snap = s.snapshot()
        assertTrue(
            "Complete 收口思考卡",
            snap.entries.filterIsInstance<StreamEntry.ThinkingEntry>().single().let { !it.isStreaming }
        )
        assertTrue(
            "Complete 收口结论气泡",
            snap.entries.filterIsInstance<StreamEntry.AssistantEntry>().single().let { !it.isStreaming }
        )
    }

    @Test
    fun `residual streaming entries finalize on error event`() {
        val s = CodeStreamSession()
        s.beginRun("会出错的任务")
        s.onEvent(AgentEvent.ThinkingChunk("想想"))
        s.onEvent(AgentEvent.ResponseChunk("半截"))
        s.onEvent(AgentEvent.Error("boom", recoverable = true))
        val snap = s.snapshot()
        assertTrue(
            "Error 收口思考卡",
            snap.entries.filterIsInstance<StreamEntry.ThinkingEntry>().single().let { !it.isStreaming }
        )
        assertTrue(
            "Error 收口结论气泡",
            snap.entries.filterIsInstance<StreamEntry.AssistantEntry>().single().let { !it.isStreaming }
        )
    }

    @Test
    fun `thinking chunk after finalized entry starts a new card`() {
        // 思考 → 工具 → 再思考的合法多段时序：收口后再来 Chunk 另起新卡
        val s = CodeStreamSession()
        s.beginRun("多段思考")
        s.onEvent(AgentEvent.ThinkingChunk("第一段"))
        s.onEvent(editStart("c1", "A.kt"))
        s.onEvent(AgentEvent.ToolCallComplete("c1", "CodeEdit", "{}", "ok", "", true, 10))
        s.onEvent(AgentEvent.ThinkingChunk("第二段"))
        val cards = s.snapshot().entries.filterIsInstance<StreamEntry.ThinkingEntry>()
        assertEquals("两段思考两张卡", 2, cards.size)
        assertTrue("旧卡已收口", !cards[0].isStreaming)
        assertTrue("新卡保持流式（等后续事件收口）", cards[1].isStreaming)
    }

    @Test
    fun `verify cycle collapses edit then lint into round card`() {
        val s = CodeStreamSession()
        s.beginRun("改完自检")
        s.onEvent(editStart("e1", "A.kt"))
        s.onEvent(AgentEvent.ToolCallComplete("e1", "CodeEdit", "{}", "ok", "", true, 30))
        s.onEvent(AgentEvent.ToolCallStart("v1", "lint", """{"path":"A.kt"}"""))
        s.onEvent(AgentEvent.ToolCallComplete("v1", "lint", "{}", "no issues", "", true, 40))
        // 收尾触发轮次收口
        s.onEvent(AgentEvent.Complete("done", 2, 2, 500))
        val snap = s.snapshot()
        val cycle = snap.entries.filterIsInstance<StreamEntry.VerifyCycleEntry>().single()
        assertEquals(1, cycle.cycle.round)
        assertEquals(listOf("e1"), cycle.cycle.editCallIds)
        assertEquals(listOf("v1"), cycle.cycle.verifyCallIds)
        assertEquals("验证通过 → passed=true", true, cycle.cycle.passed)
        // 轮内胶囊已折叠（时间轴不再有散装胶囊）
        assertEquals(0, snap.entries.count { it is StreamEntry.ToolCapsuleEntry })
    }

    @Test
    fun `failed verify marks cycle red`() {
        val s = CodeStreamSession()
        s.beginRun("改完自检")
        s.onEvent(editStart("e1", "A.kt"))
        s.onEvent(AgentEvent.ToolCallComplete("e1", "CodeEdit", "{}", "ok", "", true, 30))
        s.onEvent(AgentEvent.ToolCallStart("t1", "test", """{"path":"A.kt"}"""))
        s.onEvent(
            AgentEvent.ToolCallComplete(
                "t1", "test", "{}", "FAILED: ATest.b\nexit code 1", "", false, 60
            )
        )
        s.onEvent(editStart("e2", "A.kt")) // 第二轮 edit 开新轮次 → 上一轮收口
        val snap = s.snapshot()
        val cycles = snap.entries.filterIsInstance<StreamEntry.VerifyCycleEntry>()
        assertEquals(1, cycles.size)
        assertEquals(false, cycles[0].cycle.passed)
    }

    @Test
    fun `error event produces structured entry and lastError`() {
        val s = CodeStreamSession()
        s.beginRun("会出错的任务")
        s.onEvent(bashStart("b1", "make"))
        s.onEvent(AgentEvent.ToolOutputChunk("b1", "cc: error\n"))
        s.onEvent(AgentEvent.Error("工具执行失败：命令返回非零", recoverable = true))
        val snap = s.snapshot()
        val err = snap.entries.filterIsInstance<StreamEntry.ErrorEntry>().single()
        assertTrue(err.recoverable)
        assertNotNull(err.hint)
        assertEquals("b1", err.relatedCallId)
        assertEquals("工具执行失败：命令返回非零", snap.lastError?.message)
    }

    @Test
    fun `abort produces stop entry`() {
        val s = CodeStreamSession()
        s.beginRun("被中止")
        s.onEvent(AgentEvent.Aborted)
        val snap = s.snapshot()
        assertEquals(1, snap.entries.count { it is StreamEntry.StopEntry })
    }

    @Test
    fun `complete appends summary and file chips`() {
        val s = CodeStreamSession()
        s.beginRun("改两个文件")
        s.onEvent(editStart("e1", "A.kt"))
        s.onEvent(AgentEvent.ToolCallComplete("e1", "CodeEdit", "{}", "ok", "", true, 10))
        s.onEvent(editStart("e2", "B.kt"))
        s.onEvent(AgentEvent.ToolCallComplete("e2", "CodeEdit", "{}", "ok", "", true, 10))
        s.onEvent(AgentEvent.Complete("done", 3, 2, 1_000))
        val snap = s.snapshot()
        val chips = snap.entries.filterIsInstance<StreamEntry.FileChipsEntry>().single()
        assertEquals(listOf("A.kt", "B.kt"), chips.files)
        assertTrue(snap.entries.any { it is StreamEntry.StatusEntry })
    }

    @Test
    fun `tick returns null when nothing changed`() {
        val s = CodeStreamSession()
        s.beginRun("安静的任务")
        s.tick(1_000)
        assertNull("无变更无脉冲 → null（VM 跳过更新）", s.tick(1_001))
    }

    @Test
    fun `tick batches storm of chunks into coalesced snapshots`() {
        val s = CodeStreamSession()
        s.beginRun("流式风暴")
        s.onEvent(bashStart("b1", "cat big.log"))
        // 100 个小 chunk 事件
        repeat(100) { s.onEvent(AgentEvent.ToolOutputChunk("b1", "data\n")) }
        // 中间不 tick——全部攒住
        val snap = s.tick(50_000)
        assertNotNull(snap)
        assertEquals("终态只有一条胶囊", 1, snap!!.toolCallCount)
        assertTrue(snap.terminalContent.contains("data"))
    }

    @Test
    fun `inject system entry is visible in timeline`() {
        val s = CodeStreamSession()
        s.injectSystem("🧠 自适应预检：长文本+2 → 评分 2 → LIGHT")
        val snap = s.snapshot()
        val sys = snap.entries.filterIsInstance<StreamEntry.SystemEntry>().single()
        assertTrue(sys.text.contains("自适应预检"))
    }

    @Test
    fun `non bash log tail is windowed`() {
        val s = CodeStreamSession()
        s.beginRun("长输出")
        s.onEvent(AgentEvent.ToolCallStart("g1", "CodeGrep", """{"pattern":"foo"}"""))
        repeat(50) { s.onEvent(AgentEvent.ToolOutputChunk("g1", "match-$it\n")) }
        s.onEvent(AgentEvent.ToolCallComplete("g1", "CodeGrep", "{}", "done", "", true, 10))
        val snap = s.snapshot()
        val capsule = snap.entries.filterIsInstance<StreamEntry.ToolCapsuleEntry>().single()
        assertEquals(ToolKind.GREP_SEARCH, capsule.call.kind)
        assertTrue(
            "logTail 只保留尾窗（≤2000 字符）",
            capsule.call.logTail.length <= 2_000 && capsule.call.logTail.contains("match-49")
        )
    }

    @Test
    fun `failed capsule increments failure count`() {
        val s = CodeStreamSession()
        s.beginRun("失败案例")
        s.onEvent(bashStart("b1", "false"))
        s.onEvent(
            AgentEvent.ToolCallComplete("b1", "TerminalExec", "{}", "exit code 1", "", false, 5)
        )
        val snap = s.snapshot()
        assertEquals(1, snap.failedToolCallCount)
        val capsule = snap.entries.filterIsInstance<StreamEntry.ToolCapsuleEntry>().single()
        assertEquals(ToolCallStatus.FAILED, capsule.call.status)
        assertEquals(1, capsule.call.exitCode)
    }

    // ═══ 第三轮质量审计回归（P0/P1）═══

    @Test
    fun `restore then new run produces unique entry ids`() {
        // P0：replaceAll 不回拨序号 → nextId() 从 e-1 重发 → LazyColumn 重复 key 崩溃
        val first = CodeStreamSession()
        first.beginRun("老任务")
        first.onEvent(AgentEvent.ResponseChunk("老结论"))
        first.onEvent(AgentEvent.ResponseComplete("老结论"))
        first.onEvent(AgentEvent.Complete("done", 1, 0, 100))
        val restored = first.entriesSnapshot()

        val second = CodeStreamSession()
        second.replaceAll(restored)
        second.beginRun("新任务")
        second.onEvent(AgentEvent.ResponseChunk("新结论"))
        val ids = second.entriesSnapshot().map { it.id }
        assertEquals("恢复后新事件 id 不得与恢复轴撞号", ids.size, ids.toSet().size)
    }

    @Test
    fun `abort mid stream then new run does not merge bubbles`() {
        // P1：中止后 streamingAssistant 悬挂 → 下一轮文本拼进旧气泡
        val s = CodeStreamSession()
        s.beginRun("第一轮")
        s.onEvent(AgentEvent.ResponseChunk("第一轮的流式文本"))
        // 中止（无 ResponseComplete）
        s.onEvent(AgentEvent.Aborted)
        val afterAbort = s.snapshot()
        val bubble1 = afterAbort.entries.filterIsInstance<StreamEntry.AssistantEntry>().single()
        assertTrue("中止后旧气泡应收口（不再生成中）", !bubble1.isStreaming)
        // 新一轮
        s.beginRun("第二轮")
        s.onEvent(AgentEvent.ResponseChunk("第二轮的新文本"))
        val snap = s.snapshot()
        val bubbles = snap.entries.filterIsInstance<StreamEntry.AssistantEntry>()
        assertEquals("两轮各一条气泡", 2, bubbles.size)
        assertEquals("第一轮的流式文本", bubbles[0].text)
        assertEquals("第二轮的新文本", bubbles[1].text)
    }

    @Test
    fun `verify cycle folding preserves in round thinking and status entries`() {
        // P1：闭环折叠整段 subList 清空 → 轮内思考/迭代状态条目被吞
        val s = CodeStreamSession()
        s.beginRun("改完自检")
        s.onEvent(editStart("e1", "A.kt"))
        s.onEvent(AgentEvent.ToolCallComplete("e1", "CodeEdit", "{}", "ok", "", true, 30))
        // 真实事件序：edit 完成 → 下一轮迭代声明 → 思考 → verify
        s.onEvent(AgentEvent.IterationStart(2))
        s.onEvent(AgentEvent.ThinkingChunk("思考怎么修"))
        s.onEvent(AgentEvent.ToolCallStart("v1", "lint", """{"path":"A.kt"}"""))
        s.onEvent(AgentEvent.ToolCallComplete("v1", "lint", "{}", "no issues", "", true, 40))
        s.onEvent(AgentEvent.Complete("done", 2, 2, 500))
        val snap = s.snapshot()
        assertEquals("轮次卡存在", 1, snap.entries.count { it is StreamEntry.VerifyCycleEntry })
        assertTrue(
            "轮内思考条目必须原位保留",
            snap.entries.any { it is StreamEntry.ThinkingEntry && (it as StreamEntry.ThinkingEntry).text == "思考怎么修" }
        )
        // 迭代声明不再产生状态行（用户反馈：迭代字样无意义、纯噪音）——
        // 轮次信息只在 Complete 收尾行汇总。
        assertTrue(
            "迭代声明不得再产生「第 N 轮迭代」状态行",
            snap.entries.none {
                it is StreamEntry.StatusEntry && (it as StreamEntry.StatusEntry).text.contains("轮迭代")
            }
        )
        assertTrue(
            "Complete 收尾状态行仍保留",
            snap.entries.any { it is StreamEntry.StatusEntry && (it as StreamEntry.StatusEntry).text.contains("完成") }
        )
    }

    @Test
    fun `failed complete replay does not double count failures`() {
        // P2：重放已终态调用的 Complete → failedToolCallCount 重复自增
        val s = CodeStreamSession()
        s.beginRun("重放场景")
        s.onEvent(bashStart("b1", "false"))
        val failed = AgentEvent.ToolCallComplete("b1", "TerminalExec", "{}", "exit code 1", "", false, 5)
        s.onEvent(failed)
        s.onEvent(failed) // 重放
        val snap = s.snapshot()
        assertEquals(1, snap.failedToolCallCount)
    }

    @Test
    fun `complete without prior chunk creates terminal entry`() {
        // P2：非流式路径 ThinkingComplete/ResponseComplete 全文不再静默丢弃
        val s = CodeStreamSession()
        s.beginRun("非流式")
        s.onEvent(AgentEvent.ThinkingComplete("一次性完整思考"))
        s.onEvent(AgentEvent.ResponseComplete("一次性完整结论"))
        val snap = s.snapshot()
        assertEquals(
            "一次性完整思考",
            snap.entries.filterIsInstance<StreamEntry.ThinkingEntry>().single().text
        )
        assertEquals(
            "一次性完整结论",
            snap.entries.filterIsInstance<StreamEntry.AssistantEntry>().single().text
        )
    }

    @Test
    fun `replaceAll restores affected files from last file chips entry`() {
        // P2：重启后 committedFiles 不再清零
        val first = CodeStreamSession()
        first.beginRun("改文件")
        first.onEvent(editStart("e1", "A.kt"))
        first.onEvent(AgentEvent.ToolCallComplete("e1", "CodeEdit", "{}", "ok", "", true, 10))
        first.onEvent(AgentEvent.Complete("done", 1, 1, 100))
        val restored = first.entriesSnapshot()

        val second = CodeStreamSession()
        second.replaceAll(restored)
        assertEquals(listOf("A.kt"), second.snapshot().affectedFiles)
    }

    @Test
    fun `affected files reset per run`() {
        // 文档语义：「本轮运行」触碰的文件
        val s = CodeStreamSession()
        s.beginRun("第一轮")
        s.onEvent(editStart("e1", "A.kt"))
        s.onEvent(AgentEvent.ToolCallComplete("e1", "CodeEdit", "{}", "ok", "", true, 10))
        s.onEvent(AgentEvent.Complete("done", 1, 1, 100))
        assertEquals(listOf("A.kt"), s.snapshot().affectedFiles)
        s.beginRun("第二轮（未改文件）")
        s.onEvent(AgentEvent.Complete("done", 1, 0, 50))
        assertTrue("新一轮开始后归零", s.snapshot().affectedFiles.isEmpty())
    }

    // ═══ 中止冻结与结论截断修复回归（用户实测 app-20261017 截图/日志）═══

    @Test
    fun `response complete keeps accumulated text when engine fullText is last iteration only`() {
        // 回归：引擎 contentBuilder 每轮迭代局部重建，ResponseComplete 只带
        // 末轮文本 —— 旧实现用 fullText 整体覆写，跨轮累积的结论被截成
        // 末轮残句（用户实测「思考完成后回答只剩两个字」）。
        val s = CodeStreamSession()
        s.beginRun("多轮工具任务")
        s.onEvent(AgentEvent.ResponseChunk("我先分析一下，"))
        s.onEvent(AgentEvent.ToolCallStart("t1", "CodeGrep", """{"pattern":"x"}"""))
        s.onEvent(AgentEvent.ToolCallComplete("t1", "CodeGrep", "{}", "done", "", true, 10))
        s.onEvent(AgentEvent.ResponseChunk("结论如下："))
        s.onEvent(AgentEvent.ResponseComplete("好的"))
        val snap = s.snapshot()
        val assistant = snap.entries.filterIsInstance<StreamEntry.AssistantEntry>().single()
        assertEquals("UI 累积优先，fullText 仅空时兜底", "我先分析一下，结论如下：", assistant.text)
        assertTrue(!assistant.isStreaming)
    }

    @Test
    fun `response complete falls back to engine fullText when ui text blank`() {
        // 兜底方向：反思修正轮等文本可能从未流经 UI（无任何 Chunk），
        // 完成事件的全量文本不得丢弃（非流式路径同款语义）。
        val s = CodeStreamSession()
        s.beginRun("非流式收尾")
        s.onEvent(AgentEvent.ResponseComplete("完整结论"))
        val snap = s.snapshot()
        assertEquals("完整结论", snap.entries.filterIsInstance<StreamEntry.AssistantEntry>().single().text)
    }

    @Test
    fun `abort event finalizes running capsules as interrupted`() {
        // 回归：中止只收口流式气泡不收胶囊 → RUNNING 胶囊无限计时转圈
        //（用户实测截图：中止后 skill_search 卡转 37s+ 仍显示进行中）。
        val s = CodeStreamSession()
        s.beginRun("跑到一半停")
        s.onEvent(AgentEvent.ResponseChunk("部分结论"))
        s.onEvent(bashStart("b1", "sleep 300"))
        s.onEvent(AgentEvent.ToolOutputChunk("b1", "zzz\n"))
        s.onEvent(AgentEvent.Aborted)
        val snap = s.snapshot()
        val capsule = snap.entries.filterIsInstance<StreamEntry.ToolCapsuleEntry>().single()
        assertEquals("在飞胶囊按中断折叠（与检查点恢复同语义）", ToolCallStatus.PARTIAL, capsule.call.status)
        assertTrue("计时收口（endAt 落定，displayDuration 不再无限累加）", capsule.call.endAt > 0)
        assertEquals("已中止", capsule.call.summary)
        val bubble = snap.entries.filterIsInstance<StreamEntry.AssistantEntry>().single()
        assertTrue("已累积文本保留", !bubble.isStreaming)
        assertEquals("部分结论", bubble.text)
        assertTrue(snap.entries.any { it is StreamEntry.StopEntry })
    }

    @Test
    fun `abortLocally finalizes stream without engine events`() {
        // VM abort：收集协程先死、引擎 Aborted 事件在已取消协程上 emit
        // 直接抛取消异常、永远到不了 —— 本地收尾是唯一兜底路径。
        val s = CodeStreamSession()
        s.beginRun("本地收尾")
        s.onEvent(AgentEvent.ThinkingChunk("想了一半"))
        s.onEvent(AgentEvent.ResponseChunk("写了一半"))
        s.onEvent(editStart("e1", "A.kt"))
        s.abortLocally()
        val snap = s.snapshot()
        val capsule = snap.entries.filterIsInstance<StreamEntry.ToolCapsuleEntry>().single()
        assertEquals(ToolCallStatus.PARTIAL, capsule.call.status)
        val thinking = snap.entries.filterIsInstance<StreamEntry.ThinkingEntry>().single()
        assertTrue(!thinking.isStreaming)
        val bubble = snap.entries.filterIsInstance<StreamEntry.AssistantEntry>().single()
        assertTrue(!bubble.isStreaming)
        assertEquals("写了一半", bubble.text)
        assertTrue(snap.entries.any { it is StreamEntry.StopEntry })
    }

    @Test
    fun `abortLocally is a no-op after clean completion`() {
        // 正常完成（Complete 已收尾）后误调 abort：不落多余的「已中止」卡
        val s = CodeStreamSession()
        s.beginRun("干净收尾")
        s.onEvent(AgentEvent.ResponseChunk("结论"))
        s.onEvent(AgentEvent.ResponseComplete("结论"))
        s.onEvent(AgentEvent.Complete("done", 1, 0, 100))
        s.abortLocally()
        val snap = s.snapshot()
        assertTrue(snap.entries.none { it is StreamEntry.StopEntry })
    }

    @Test
    fun `beginRun finalizes stale running capsules from previous run`() {
        // 防御：收尾事件全部丢失（中止事件未达且本地收尾遗漏）的路径，
        // 下一轮 beginRun 不得让旧 RUNNING 胶囊带进新 run 无限计时。
        val s = CodeStreamSession()
        s.beginRun("上一轮")
        s.onEvent(bashStart("b1", "hang"))
        s.beginRun("下一轮")
        val snap = s.snapshot()
        val capsule = snap.entries.filterIsInstance<StreamEntry.ToolCapsuleEntry>().single()
        assertEquals(ToolCallStatus.PARTIAL, capsule.call.status)
        assertTrue("静默收口：不加停止卡", snap.entries.none { it is StreamEntry.StopEntry })
    }

    @Test
    fun `streaming chunks merge incrementally beyond two chunks`() {
        // 回归：streamingAssistant 引用不回写 → 第三个 Chunk 起条目被
        // 「首块+最新块」覆盖、中间块丢失（旧代码靠 Complete 的 fullText
        // 整体覆写掩盖，Completion 改累积优先后该缺陷失去遮盖）。
        val s = CodeStreamSession()
        s.beginRun("三段流式")
        s.onEvent(AgentEvent.ResponseChunk("第一段，"))
        s.onEvent(AgentEvent.ResponseChunk("第二段，"))
        s.onEvent(AgentEvent.ResponseChunk("第三段。"))
        val snap = s.snapshot()
        val bubble = snap.entries.filterIsInstance<StreamEntry.AssistantEntry>().single()
        assertEquals("流式中的条目即完整累积（不等 Complete 兜底）", "第一段，第二段，第三段。", bubble.text)
        assertTrue(bubble.isStreaming)
        // 思考链同款：三段思考流式不丢中间块
        val s2 = CodeStreamSession()
        s2.beginRun("三段思考")
        s2.onEvent(AgentEvent.ThinkingChunk("想一，"))
        s2.onEvent(AgentEvent.ThinkingChunk("想二，"))
        s2.onEvent(AgentEvent.ThinkingChunk("想三。"))
        val thinking = s2.snapshot().entries.filterIsInstance<StreamEntry.ThinkingEntry>().single()
        assertEquals("想一，想二，想三。", thinking.text)
    }

    // ═══ 终端面板流水式折叠信号（terminalBusy / 命令摘要 / 退出码）═══
    // 用户需求：「终端命令等流水似输出：运行时保持展开跟随输出，
    // 完成后自动折叠成胶囊」—— UI 侧由 CodeScreen 按 terminalBusy 驱动
    // 展开/折叠（userToggled 守卫），这里锁状态机信号本身的生命周期。

    @Test
    fun `terminal busy signal follows bash command lifecycle`() {
        val s = CodeStreamSession()
        s.beginRun("跑构建")
        // 开跑前：无命令、不忙
        val idle = s.snapshot()
        assertFalse(idle.terminalBusy)
        assertEquals("", idle.terminalCommandSummary)

        s.onEvent(bashStart("b1", "./gradlew assembleDebug --stacktrace"))
        val running = s.snapshot()
        assertTrue("命令运行中 → busy=true", running.terminalBusy)
        assertEquals(
            "头行展示首行命令摘要",
            "./gradlew assembleDebug --stacktrace",
            running.terminalCommandSummary
        )
        assertNull("运行中无退出码", running.terminalExitCode)

        s.onEvent(
            AgentEvent.ToolCallComplete(
                callId = "b1", toolName = "terminal.exec", arguments = "",
                output = "build ok\nexit code 0", fullOutput = "",
                success = true, durationMs = 1_200
            )
        )
        val done = s.snapshot()
        assertFalse("命令收束 → busy=false（UI 自动折叠）", done.terminalBusy)
        assertEquals("退出码就地保留供回看", 0, done.terminalExitCode)
        assertEquals("命令摘要跨完成保留", "./gradlew assembleDebug --stacktrace", done.terminalCommandSummary)
    }

    @Test
    fun `second bash command takes over terminal signals`() {
        val s = CodeStreamSession()
        s.beginRun("连跑两条")
        s.onEvent(bashStart("b1", "echo first"))
        s.onEvent(AgentEvent.ToolCallComplete("b1", "terminal.exec", "", "exit code 0", "", true, 10))
        s.onEvent(bashStart("b2", "echo second"))
        val running = s.snapshot()
        assertTrue(running.terminalBusy)
        assertEquals("echo second", running.terminalCommandSummary)
        assertNull("新命令清掉上一条退出码", running.terminalExitCode)
    }

    @Test
    fun `abort mid bash clears busy signal`() {
        val s = CodeStreamSession()
        s.beginRun("会中止的任务")
        s.onEvent(bashStart("b1", "sleep 100"))
        assertTrue(s.snapshot().terminalBusy)
        s.onEvent(AgentEvent.Aborted)
        assertFalse("中止收束 busy（面板不会永远脉冲）", s.snapshot().terminalBusy)
    }

    @Test
    fun `engine error clears busy signal`() {
        val s = CodeStreamSession()
        s.beginRun("会报错的任务")
        s.onEvent(bashStart("b1", "boom"))
        assertTrue(s.snapshot().terminalBusy)
        s.onEvent(AgentEvent.Error(message = "工具执行失败", recoverable = true))
        assertFalse("引擎错误收束 busy", s.snapshot().terminalBusy)
    }

    @Test
    fun `begin run resets terminal signals`() {
        val s = CodeStreamSession()
        s.beginRun("第一轮")
        s.onEvent(bashStart("b1", "echo hi"))
        s.onEvent(AgentEvent.ToolCallComplete("b1", "terminal.exec", "", "exit code 0", "", true, 5))
        assertFalse(s.snapshot().terminalBusy)

        s.beginRun("第二轮")
        val snap = s.snapshot()
        assertFalse(snap.terminalBusy)
        assertEquals("新 run 归零命令摘要", "", snap.terminalCommandSummary)
        assertNull("新 run 归零退出码", snap.terminalExitCode)
        assertNull("新 run 无活跃终端", snap.activeTerminalCallId)
    }

    // ═══ Git 操作可视化（git 工具成功 → GitOpEntry 结构化卡入轴）═══

    @Test
    fun `git status success appends git op visual entry`() {
        val s = CodeStreamSession()
        s.beginRun("看看仓库状态")
        s.onEvent(AgentEvent.ToolCallStart("g1", "CodeGitStatus", "{}"))
        s.onEvent(
            AgentEvent.ToolCallComplete(
                callId = "g1", toolName = "CodeGitStatus", arguments = "{}",
                output = "分支: main（跟踪 origin/main，领先 2）\n\n已暂存（将进入下次提交）（1）：\n- 修改  a.kt\n\n未跟踪（2）：\n- b.kt\n- c.kt",
                fullOutput = "", success = true, durationMs = 800
            )
        )
        val snap = s.snapshot()
        val capsule = snap.entries.filterIsInstance<StreamEntry.ToolCapsuleEntry>().single()
        assertEquals(ToolKind.GIT, capsule.call.kind)
        assertEquals("git 胶囊 target 用语义占位", "工作区", capsule.call.target)
        val visual = snap.entries.filterIsInstance<StreamEntry.GitOpEntry>().single()
        assertEquals(GitOpSummary.GitOpType.STATUS, visual.summary.op)
        assertEquals("main", visual.summary.branch)
        assertEquals(2, visual.summary.ahead)
        assertEquals(1, visual.summary.staged)
        assertEquals(2, visual.summary.untracked)
        assertEquals("可视化卡关联胶囊 callId", "g1", visual.callId)
    }

    @Test
    fun `git commit capsule target is message subject and visual carries hash`() {
        val s = CodeStreamSession()
        s.beginRun("提交一下")
        s.onEvent(
            AgentEvent.ToolCallStart(
                "g2", "CodeGitCommit", """{"message":"修复登录页崩溃\n\n细节"}"""
            )
        )
        s.onEvent(
            AgentEvent.ToolCallComplete(
                callId = "g2", toolName = "CodeGitCommit",
                arguments = """{"message":"修复登录页崩溃"}""",
                output = "已提交 a1b2c3d: 修复登录页崩溃\n剩余未提交变更：无",
                fullOutput = "", success = true, durationMs = 1_200
            )
        )
        val snap = s.snapshot()
        val capsule = snap.entries.filterIsInstance<StreamEntry.ToolCapsuleEntry>().single()
        assertEquals("commit 胶囊 target = message 首行", "修复登录页崩溃", capsule.call.target)
        val visual = snap.entries.filterIsInstance<StreamEntry.GitOpEntry>().single()
        assertEquals("a1b2c3d", visual.summary.commitHash)
        assertEquals(0, visual.summary.remainingChanges)
    }

    @Test
    fun `git tool failure appends no visual entry`() {
        val s = CodeStreamSession()
        s.beginRun("坏仓库")
        s.onEvent(AgentEvent.ToolCallStart("g3", "CodeGitStatus", "{}"))
        s.onEvent(
            AgentEvent.ToolCallComplete(
                callId = "g3", toolName = "CodeGitStatus", arguments = "{}",
                output = "git status 失败（退出码 -1）：Ubuntu 环境未安装",
                fullOutput = "", success = false, durationMs = 30
            )
        )
        assertEquals(
            "失败不产生可视化卡（错误由胶囊摘要承载）",
            0,
            s.snapshot().entries.count { it is StreamEntry.GitOpEntry }
        )
    }

    @Test
    fun `git op entry survives checkpoint roundtrip`() {
        val s = CodeStreamSession()
        s.beginRun("提交并查看")
        s.onEvent(AgentEvent.ToolCallStart("g4", "CodeGitBranch", """{"action":"list"}"""))
        s.onEvent(
            AgentEvent.ToolCallComplete(
                callId = "g4", toolName = "CodeGitBranch", arguments = """{"action":"list"}""",
                output = "分支列表（2）：\n* main\n  dev",
                fullOutput = "", success = true, durationMs = 90
            )
        )
        val checkpoint = CodeStreamCheckpoint.fromSession(s, "ws-1")
        val restored = CodeStreamCheckpoint.toEntries(checkpoint)
        val visual = restored.filterIsInstance<StreamEntry.GitOpEntry>().single()
        assertEquals(GitOpSummary.GitOpType.BRANCH, visual.summary.op)
        assertEquals("main", visual.summary.branch)
        assertEquals("g4", visual.callId)
    }
}
