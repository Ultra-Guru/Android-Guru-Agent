package com.apex.agent.tools

import com.apex.agent.platform.privilege.shizuku.DeviceChannel
import com.apex.agent.platform.privilege.shizuku.DeviceExecRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * ═══ DCP 回收区（S06 I4）═══
 *
 * file.delete 的软删语义落地：`mv` 到 `<TRASH_ROOT>/<auditId>/` 并写一份
 * manifest（原路径 + 审计 id），返回 `undo_token = auditId`；file.undo 用
 * token 读 manifest 还原到原路径。
 *
 * manifest 写入纪律：所有 argv / 路径以 **argv 元素**传递（mv / tee），不经
 * `sh -c`；跨挂载点 mv 失败时**如实报 MOVE_FAILED 并保持原文件不动**，绝不
 * 退化成 cp+rm 冒充软删。
 */
class DeviceTrash(
    private val channel: DeviceChannel
) {

    /** 一条软删记录（manifest.json 内容）。 */
    data class TrashEntry(
        val auditId: String,
        val originalPath: String,
        val basename: String,
        val trashedAtMillis: Long
    )

    /**
     * 为软删生成回收区内部 argv 参数（{auditId} / {basename} 的真值）。
     * 注意：本方法**不创建目录**（无副作用）—— 目录创建发生在 executeMv
     * 前，由 [ensureTrashDir] 用 mkdir argv 完成。
     */
    fun entryFor(auditId: String, originalPath: String, basename: String, now: Long): TrashEntry =
        TrashEntry(auditId, originalPath, basename, now)

    /** 回收区子目录（内嵌于 argv 的路径前缀）。 */
    fun dirFor(auditId: String): String = "$TRASH_ROOT_DIR/$auditId"

    /** mkdir 回收区子目录（-p 递归；幂等）。 */
    suspend fun ensureTrashDir(auditId: String): Boolean {
        val result = channel.execute(
            DeviceExecRequest(
                argv = arrayOf("mkdir", "-p", dirFor(auditId)),
                timeoutMs = 10_000L
            )
        )
        return result.ok
    }

    /**
     * 把软删 manifest 写进回收区子目录（tee + stdinUtf8 —— 内容不进 argv）。
     * manifest 位置：`<dir>/manifest.json`（与被软删文件同目录，undo 直读）。
     */
    suspend fun writeManifest(entry: TrashEntry): Boolean {
        val json = buildJsonObject {
            put("auditId", entry.auditId)
            put("originalPath", entry.originalPath)
            put("basename", entry.basename)
            put("trashedAtMillis", entry.trashedAtMillis)
        }.toString()
        val result = channel.execute(
            DeviceExecRequest(
                argv = arrayOf("tee", "${dirFor(entry.auditId)}/manifest.json"),
                stdinUtf8 = json,
                timeoutMs = 10_000L
            )
        )
        return result.ok
    }

    /**
     * 按 undo_token 解析软删记录：`cat <dir>/manifest.json`。
     * 解析失败（token 伪造 / 回收区被清理）返回 null —— 诚实报错不编造。
     */
    suspend fun resolveToken(token: String): TrashEntry? {
        val trimmed = token.trim()
        if (trimmed.isBlank() || trimmed.contains('/') || trimmed.contains("..")) return null
        val result = channel.execute(
            DeviceExecRequest(
                argv = arrayOf("cat", "${dirFor(trimmed)}/manifest.json"),
                timeoutMs = 10_000L
            )
        )
        if (!result.ok) return null
        return runCatching {
            val o = Json.parseToJsonElement(result.stdout).let { it as? kotlinx.serialization.json.JsonObject }
                ?: return null
            fun str(key: String) = (o[key] as? kotlinx.serialization.json.JsonPrimitive)?.content
            TrashEntry(
                auditId = str("auditId") ?: return null,
                originalPath = str("originalPath") ?: return null,
                basename = str("basename") ?: return null,
                trashedAtMillis = (o["trashedAtMillis"] as? kotlinx.serialization.json.JsonPrimitive)
                    ?.content?.toLongOrNull() ?: 0L
            )
        }.getOrNull()
    }

    internal companion object {
        /** 与 DevicePathPolicy.TRASH_ROOT 同源（单一常量，防两处漂移）。 */
        internal const val TRASH_ROOT_DIR = DevicePathPolicy.TRASH_ROOT
    }
}
