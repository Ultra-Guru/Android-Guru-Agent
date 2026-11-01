package com.apex.agent.core.code

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「整个系统」工作区（root = /）的提示词契约（2026-10 用户需求）：
 *
 * 用户选择整个系统后，模型必须知道 code_* 工具在**真实文件系统**上操作
 * （非沙箱副本）、/data 需 root 的权限边界、以及终端侧真实系统根挂载在
 * /host —— 任何一处缺失，模型都会按「沙箱内操作」的默认措辞自我设限，
 * 用户视角就是「选了整个系统还是只在工作区和沙箱里打转」。
 */
class CodePromptsWorkspaceContextTest {

    @Test
    fun `整个系统工作区注入真实系统语义与权限边界`() {
        val ctx = CodePrompts.workspaceContext(
            workspaceName = "Whole System",
            rootLabel = "/",
            guestPath = "/workspace",
            environmentSummary = null,
            projectStats = null,
            activeFile = null
        )

        assertTrue("host 根标注真实系统", ctx.contains("- host root: /"))
        assertTrue("真实系统读写语义", ctx.contains("真实 Android 文件系统"))
        assertTrue("非沙箱声明", ctx.contains("不是沙箱副本"))
        assertTrue("权限边界：/data 需 root", ctx.contains("/data"))
        assertTrue("只读分区预期", ctx.contains("只读挂载"))
        assertTrue("终端侧真实系统根挂载 /host", ctx.contains("/host"))
        assertTrue("结尾改为全系统口径", ctx.contains("在整个系统范围内真实读写"))
        // 沙箱措辞不得出现在整个系统模式 —— 那是模型自我设限的源头
        assertFalse("不得保留沙箱内操作措辞", ctx.contains("在此沙箱内操作"))
    }

    @Test
    fun `普通工作区保持既有沙箱措辞零漂移`() {
        val ctx = CodePrompts.workspaceContext(
            workspaceName = "my-project",
            rootLabel = "/data/user/0/app/files/linux/workspaces/ws-1",
            guestPath = "/workspace",
            environmentSummary = "Kotlin · gradle",
            projectStats = "12 个代码文件",
            activeFile = "app/src/Main.kt"
        )

        assertTrue("guest 挂载点保留", ctx.contains("- guest path（terminal/Ubuntu 视角）: /workspace"))
        assertTrue("沙箱措辞保留", ctx.contains("所有相对路径以工作区根解析；code_* 工具在此沙箱内操作。"))
        assertTrue("环境摘要保留", ctx.contains("- environment: Kotlin · gradle"))
        assertFalse("不得混入整个系统语义", ctx.contains("真实 Android 文件系统"))
    }
}
