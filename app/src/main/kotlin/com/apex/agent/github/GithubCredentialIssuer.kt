package com.apex.agent.github

import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import java.io.File
import java.util.UUID

/**
 * # GithubCredentialIssuer — 一次性 git 凭据脚本签发器（Git 审计 P0-1 根修）
 *
 * 把已连接的 GitHub PAT 写成 guest 沙箱内的一次性 `GIT_ASKPASS` 脚本，
 * 取代「PAT 注入进程 env」的旧通道：
 *
 * - **PAT 不进任何进程 env**：复合命令（`git pull && curl …`）与恶意
 *   仓库 hooks 继承到的只有脚本**路径**；
 * - **凭据只流向 github.com**：脚本校验 git 传入的提示串（"Username
 *   for 'https://github.com/'…"）必须指向 github.com 才应答，其他主机
 *   一律退出非零（git 得到空凭据 → 认证失败，诚实报错）；
 * - **单次生命周期**：[IssuedCredentials.dispose] 在命令结束后删除脚本
 *   （runner 通道显式调用）；终端通道（ProotCommandSpawner）无法感知
 *   进程结束，靠 [issue] 每次签发前的惰性清扫（[STALE_MS]）兜底；
 * - **fail-closed**：token 含非常规字符（无法安全嵌入单引号字面量）或
 *   写盘失败 → 返回 null，本次命令无凭据（git 认证失败可见），绝不降级
 *   回 env 注入。
 *
 * 脚本落点：`<rootfs>/tmp/guru-askpass-<ts>-<rand>.sh`（guest 侧 /tmp，
 * 无独立 bind——rootfs 即 guest 根）。权限按 Android 每应用沙箱模型即
 * 已足够（guest 进程全部同 uid，perms 仅纵深防御）。
 */
object GithubCredentialIssuer {

    private const val TAG = "GithubCredIssuer"

    /** 脚本文件名前缀（清扫按此前缀 + 文件名内嵌时间戳识别陈旧脚本）。 */
    private const val PREFIX = "guru-askpass-"

    /**
     * 终端通道脚本的兜底生命周期：签发时清扫更旧的残留。上限对齐
     * GitRunTool 最大超时（600s）×2 取整——超长命令执行中脚本不会被
     * 下一次签发误删。
     */
    private const val STALE_MS = 30 * 60 * 1000L

    /** PAT 允许的字符集（ghp_/gho_/ghu_/ghs_/ghr_/github_pat_ 全部落于
     *  字母数字下划线——单引号字面量安全嵌入的前提）。 */
    private val SAFE_TOKEN = Regex("^[A-Za-z0-9_]+$")

    /** 一次签发的句柄。 */
    class IssuedCredentials(
        /** guest 侧脚本路径（GIT_ASKPASS 值）。 */
        val guestAskPassPath: String,
        /** 宿主侧脚本文件（dispose 的删除目标）。 */
        private val hostFile: File,
    ) {
        /** 命令结束后吊销（删除脚本——token 载体即刻消失）。 */
        fun dispose() {
            runCatching { hostFile.delete() }
        }
    }

    /**
     * 为一条 git 命令签发 askpass 脚本。
     *
     * @param rootfsDir 激活 rootfs 根目录（guest /tmp 的宿主映射）。
     * @param token 已连接的 GitHub PAT（null/blank = 未连接 → null）。
     * @return 签发句柄；未连接 / token 不安全 / 写盘失败 → null（fail-closed）。
     */
    fun issue(rootfsDir: File, token: String?): IssuedCredentials? {
        if (token.isNullOrBlank()) return null
        if (!SAFE_TOKEN.matches(token)) {
            logWarn("token 含非常规字符，拒绝写入 askpass 脚本（fail-closed）")
            return null
        }
        sweepStale(rootfsDir)
        return runCatching {
            val tmpDir = File(rootfsDir, "tmp").apply { mkdirs() }
            val hostFile = File(tmpDir, "$PREFIX${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}.sh")
            // token 已过 SAFE_TOKEN 校验（无引号/无 $/无换行），单引号字面量安全
            hostFile.writeText(
                """#!/bin/sh
# Guru one-shot git credential bridge (audit P0-1) — auto-deleted after the command
case "${'$'}1" in
  Username*github.com*) echo guru ;;
  Password*github.com*) echo '$token' ;;
  *) exit 1 ;;
esac
"""
            )
            runCatching { hostFile.setExecutable(true, false) }
            runCatching { hostFile.setReadable(false, false); hostFile.setReadable(true, true) }
            IssuedCredentials("/tmp/${hostFile.name}", hostFile)
        }.onFailure {
            logWarn("askpass 脚本写盘失败：${it.message}（本次命令无凭据）")
        }.getOrNull()
    }

    /** 清扫超龄残留脚本（终端通道无法 dispose 的兜底；按文件名时间戳）。 */
    private fun sweepStale(rootfsDir: File) {
        runCatching {
            val now = System.currentTimeMillis()
            File(rootfsDir, "tmp").listFiles { f ->
                f.isFile && f.name.startsWith(PREFIX)
            }?.forEach { f ->
                val ts = f.name.removePrefix(PREFIX).substringBefore('-').toLongOrNull()
                if (ts != null && now - ts > STALE_MS) {
                    runCatching { f.delete() }
                }
            }
        }
    }

    private fun logWarn(message: String) {
        runCatching {
            AppLogger.instance.warn(LogCategory.SYSTEM, TAG, message)
        }
    }
}
