package com.apex.agent.github

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * # GithubWriteGuard — 私有仓写防线单一事实源（Git 审计 P1-13）
 *
 * 旧版写保护只覆盖 REST 一条通路（GithubWriteFile 有可见性探针 +
 * confirm_private_write），内置 MCP 的 `create_or_update_file` 没有——
 * 同一权限体系被第二条通路绕过（审计判定：私有仓防线漏一处已发生）。
 *
 * 本守卫由 **REST 工具（GithubWriteFile）与内置 MCP（create_or_update_file）
 * 共同调用**：写前探仓库可见性（GET /repos 单次往返），私有仓必须携带
 * `confirm_private_write=true`（且该标志只应在向用户转述风险并获得明确
 * 同意后设置）。探针失败一律 fail-closed（探针与写入走同一 API 通道，
 * 探针挂了写入大概率也挂——宁可诚实失败也不降级放行）。
 */
object GithubWriteGuard {

    /** 探针判定结果。 */
    sealed class Verdict {
        /** 公开仓（或私有仓已带确认标志）——放行。 */
        object Allowed : Verdict() {
            override fun toString(): String = "Allowed"
        }

        /** 私有仓未确认——模型必须停下来向用户转述并取得同意后重试。 */
        data class Blocked(val message: String) : Verdict()

        /** 探针失败（404 除外会向上翻）——fail-closed，禁止降级放行。 */
        data class Failed(val message: String) : Verdict()
    }

    /**
     * 写前校验：仓库可见性探针 + 私有仓确认标志核验。
     *
     * @param api 共享 API 服务（与写入同一认证通道）。
     * @param target 写入对象的展示形态（如 `path`），用于确认文案。
     */
    suspend fun ensureWritable(
        api: GithubApiService,
        owner: String,
        repo: String,
        target: String,
        confirmPrivateWrite: Boolean,
    ): Verdict = try {
        val repoInfo = api.getRepo(owner, repo)
        if (repoInfo.private && !confirmPrivateWrite) {
            Verdict.Blocked(
                "$owner/$repo is a PRIVATE repository. Writing to private repos requires " +
                    "explicit user approval — STOP and tell the user: " +
                    "即将向私有仓库 $owner/$repo 写入 $target，是否确认？ " +
                    "Only after the user explicitly confirms, retry with " +
                    "confirm_private_write=true. Do NOT set the flag without asking."
            )
        } else {
            Verdict.Allowed
        }
    } catch (e: GithubApiException) {
        // 404 = 仓库不存在/无权限——写入路径同样会 404，提前失败省一次往返；
        // 其他状态码（网络/5xx）fail-closed 诚实上报，不降级放行。
        Verdict.Failed("检查仓库可见性失败: ${e.message}（fail-closed，不降级放行）")
    } catch (e: Exception) {
        Verdict.Failed("检查仓库可见性失败: ${e.message}（fail-closed，不降级放行）")
    }

    /**
     * 从工具参数里读 `confirm_private_write`（宽容解析：非布尔/缺失 → false
     * —— fail-closed 方向）。REST 与 MCP 两条通路共用同一解析口径。
     */
    fun readConfirmFlag(args: JsonObject): Boolean =
        (args["confirm_private_write"] as? JsonPrimitive)
            ?.let { runCatching { it.content.toBooleanStrict() }.getOrNull() } ?: false
}
