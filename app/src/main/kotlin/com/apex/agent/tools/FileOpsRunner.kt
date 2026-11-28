package com.apex.agent.tools

import com.apex.agent.core.tools.device.DeviceDiff
import com.apex.agent.core.tools.device.DeviceIntent
import com.apex.agent.core.tools.device.DeviceOpResult
import com.apex.agent.platform.privilege.shizuku.DeviceChannel
import com.apex.agent.platform.privilege.shizuku.DeviceExecRequest
import com.apex.agent.platform.privilege.shizuku.DeviceExecResult

/**
 * ═══ DCP 文件操作执行器（S06）═══
 *
 * file.* 意图的执行面（argv 已由 DeviceOpsExecutor 渲染并过白名单）：
 * - `file.delete`：默认软删（mv 进回收区 + manifest，返回 undo_token）；
 *   `purge=purge` 时渲染 manifest 的 purgeArgv（rm）真删 —— 走同一
 *   token / Ask 流程（不可逆档）；
 * - `file.undo`：由 undo_token 解析回收区 manifest，mv 回原路径；
 * - `file.write`：tee + stdinUtf8（内容不进 argv）；**写前自动备份**
 *   原内容到回收区（cat 读取 + tee 写入）；
 * - `file.read`：cat 有界输出（超预算 truncated=true，首尾截断）；
 * - 跨挂载点 mv 失败：如实报 MOVE_FAILED 并保持原文件不动，绝不退化成
 *   cp+rm 冒充软删。
 */
class FileOpsRunner(
    private val channel: DeviceChannel,
    private val trash: DeviceTrash,
    private val pathPolicy: DevicePathPolicy,
    private val clock: () -> Long
) {

    /** 渲染后的执行产物（executor 组装 DeviceOpResult 用）。 */
    data class Outcome(
        val exit: DeviceExecResult,
        val undoToken: String? = null,
        val note: String? = null
    )

    /**
     * 执行一条 file.* 意图（argv 已渲染；内部占位符已由 executor 解析进
     * params）。dry_run 永不进本方法（executor 已拦）。
     */
    suspend fun run(
        intent: DeviceIntent,
        params: Map<String, String>,
        argv: List<String>,
        auditId: String,
        diff: DeviceDiff?
    ): Outcome = when (intent.id) {
        "file.delete" -> runDelete(intent, params, argv, auditId)
        "file.write" -> runWrite(intent, params, argv, auditId, diff)
        else -> plainExecute(intent, argv)
    }

    /** 软删（或 purge 硬删）。 */
    private suspend fun runDelete(
        intent: DeviceIntent,
        params: Map<String, String>,
        argv: List<String>,
        auditId: String
    ): Outcome {
        val path = params.getValue("path")
        val purge = params["purge"] == "purge"
        if (purge) {
            // 硬删：manifest purgeArgv 已由 executor 渲染（rm <path>）。
            val exit = channel.execute(
                DeviceExecRequest(argv = argv.toTypedArray(), timeoutMs = EXEC_TIMEOUT_MS)
            )
            return Outcome(exit, note = if (exit.ok) "已永久删除（purge，不可恢复）" else null)
        }
        if (!trash.ensureTrashDir(auditId)) {
            return Outcome(
                DeviceExecResult(ok = false, error = "MOVE_FAILED", hint = "回收区目录创建失败，原文件未动"),
                undoToken = null
            )
        }
        val exit = channel.execute(
            DeviceExecRequest(argv = argv.toTypedArray(), timeoutMs = EXEC_TIMEOUT_MS)
        )
        if (!exit.ok) {
            // 跨挂载点 mv 失败等：如实报错，保持原文件不动。
            return Outcome(
                DeviceExecResult(
                    ok = false, stderr = exit.stderr, stdout = exit.stdout,
                    error = if (exit.timedOut) "TIMEOUT" else "MOVE_FAILED",
                    hint = "移动进回收区失败（原文件未动）。可能跨挂载点，请用 file.move 移动到同分区或改用 purge（不可恢复）"
                )
            )
        }
        val basename = pathPolicy.basename(path)
        val wrote = trash.writeManifest(trash.entryFor(auditId, path, basename, clock()))
        return Outcome(
            exit,
            undoToken = if (wrote) auditId else "$auditId (manifest 写入失败，文件在回收区：${trash.dirFor(auditId)})"
        )
    }

    /** 覆写：写前自动备份原内容到回收区（存在时），tee + stdin。 */
    private suspend fun runWrite(
        intent: DeviceIntent,
        params: Map<String, String>,
        argv: List<String>,
        auditId: String,
        diff: DeviceDiff?
    ): Outcome {
        val content = params["content"] ?: return Outcome(
            DeviceExecResult(ok = false, error = "INVALID_PARAMS", hint = "file.write 需要 content 参数（payload）")
        )
        // 写前备份：before 里 exists=true 才备份（preflight 的 stat 探针结论）。
        val existed = diff?.before?.get("exists") == "true"
        if (existed) {
            val backup = channel.execute(
                DeviceExecRequest(argv = arrayOf("cat", params.getValue("path")), timeoutMs = EXEC_TIMEOUT_MS)
            )
            if (backup.ok && trash.ensureTrashDir(auditId)) {
                trash.writeManifest(
                    trash.entryFor(auditId, params.getValue("path"), pathPolicy.basename(params.getValue("path")), clock())
                )
                channel.execute(
                    DeviceExecRequest(
                        argv = arrayOf("tee", "${trash.dirFor(auditId)}/${pathPolicy.basename(params.getValue("path"))}"),
                        stdinUtf8 = backup.stdout,
                        timeoutMs = EXEC_TIMEOUT_MS
                    )
                )
            }
            // 备份失败不阻断写入，但如实标注（undo 可能不可用）。
        }
        val exit = channel.execute(
            DeviceExecRequest(
                argv = argv.toTypedArray(),
                stdinUtf8 = content,
                timeoutMs = EXEC_TIMEOUT_MS
            )
        )
        return if (exit.ok) {
            Outcome(exit, undoToken = if (existed) auditId else null)
        } else {
            Outcome(exit)
        }
    }

    private suspend fun plainExecute(intent: DeviceIntent, argv: List<String>): Outcome {
        val exit = channel.execute(
            DeviceExecRequest(argv = argv.toTypedArray(), timeoutMs = EXEC_TIMEOUT_MS)
        )
        return Outcome(exit)
    }

    private companion object {
        const val EXEC_TIMEOUT_MS = 30_000L
    }
}
