package com.apex.agent.platform.terminal.searxng

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.io.File

/**
 * ═══ M1：SearXNG 安装器（SearXNG Local Search Spec v1.0 §6）═══
 *
 * 职责（Spec §4 组件表）：依赖安装、幂等判定、阶段进度打点。**不负责**启动服务。
 *
 * 阶段管线（每阶段幂等，Spec F11 断点重试）：
 * ```
 * APT(python3/venv/pip/git) → CLONE(--depth1) → VENV → PIP(requirements) → VERIFY(import searx)
 * ```
 *
 * 失败归类（Spec §12 失败矩阵）：
 *  - `install network`：apt/pip/clone 的网络性失败（DNS/TLS/超时）—— retryable；
 *  - `build failed`：pip 编译失败（缺失头文件等）—— retryable + 日志入口；
 *  - 产物保留：任一阶段失败不清理已完成的阶段产物（重试跳过，Spec §5 转移表
 *    「Installing 安装失败 → 保留部分产物供续装」）。
 *
 * 取消语义：协程取消（用户关闭开关）立即中止当前阶段 —— 已完成阶段的产物保留，
 * 状态交由 coordinator 决定（Stopping → Off）；CancellationException 原样透传
 * （不吞协程取消纪律）。
 *
 * 进度打点：每阶段进入时回调一次（progress = 阶段累计权重），阶段内长命令的
 * 细粒度进度不做（apt/pip 输出无稳定解析契约，伪造进度违反诚实性纪律）—— UI
 * 显示阶段文案 + 确定进度条（N1 的 ≤1s 更新间隔由阶段切换满足）。
 */
class SearxngInstaller(
    private val commands: SearxngCommandPort,
    private val aptTimeoutMs: Long = 600_000L,
    private val cloneTimeoutMs: Long = 600_000L,
    private val pipTimeoutMs: Long = 1_800_000L
) {

    /**
     * 安装完整性判定（Spec §6 isInstalled：/opt/searxng/.venv/bin/python 与源码
     * 完整性）。host 侧纯文件检查（rootfs 即 host 目录）—— 零 guest 进程开销。
     */
    suspend fun isInstalled(): Boolean {
        val rootfs = commands.rootfsDir() ?: return false
        return SearxngScripts.hostVenvPython(rootfs).isFile &&
            File(SearxngScripts.hostSrcDir(rootfs), "requirements.txt").isFile
    }

    /**
     * 幂等安装：已完成阶段自动跳过（断点续装）。成功 Unit；失败携带归因
     * reason（结构化前缀 + 尾部 stderr 摘录，供 Failed 状态与日志入口）。
     *
     * @param onStage 阶段进度打点（coordinator 适配为 Installing 状态更新）。
     */
    suspend fun install(
        onStage: (suspend (stage: SearxngInstallStage, progress: Float) -> Unit)? = null
    ): Result<Unit> = try {
        if (isInstalled()) {
            // 二次开启快路径（AC2：跳过 Installing，≤10s 进 Ready）
            onStage?.invoke(SearxngInstallStage.VERIFY, SearxngInstallStage.VERIFY.progressWeight)
            Result.success(Unit)
        } else {
            installFresh(onStage)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(RuntimeException(e.message ?: e::class.simpleName))
    }

    private suspend fun installFresh(
        onStage: (suspend (stage: SearxngInstallStage, progress: Float) -> Unit)?
    ): Result<Unit> {
        // ── APT：依赖包 ──────────────────────────────────────────────
        if (!aptSatisfied()) {
            onStage?.invoke(SearxngInstallStage.APT, SearxngInstallStage.APT.progressWeight * 0.2f)
            step("apt", SearxngScripts.APT_PACKAGES, aptTimeoutMs)?.let { return it }
        }

        // ── CLONE：源码（幂等：已存在跳过）──────────────────────────
        val rootfs = commands.rootfsDir()
            ?: return Result.failure(RuntimeException("install network — rootfs not ready"))
        val srcDir = SearxngScripts.hostSrcDir(rootfs)
        if (!File(srcDir, "requirements.txt").isFile) {
            onStage?.invoke(SearxngInstallStage.CLONE, SearxngInstallStage.CLONE.progressWeight * 0.5f)
            // 残缺克隆（上次失败中断）诚实重来 —— git clone 不允许非空目标
            if (srcDir.isDirectory) {
                commands.exec("rm -rf ${SearxngContract.GUEST_SRC_DIR}", 60_000L)
            }
            step("clone", SearxngScripts.GIT_CLONE, cloneTimeoutMs)?.let { return it }
        }

        // ── VENV ─────────────────────────────────────────────────────
        if (!SearxngScripts.hostVenvPython(rootfs).isFile) {
            onStage?.invoke(SearxngInstallStage.VENV, SearxngInstallStage.VENV.progressWeight * 0.5f)
            commands.exec(SearxngScripts.MKDIR_INSTALL_DIR, 30_000L)
            step("venv", SearxngScripts.VENV_CREATE, 300_000L)?.let { return it }
        }

        // ── PIP：依赖（幂等）────────────────────────────────────────
        onStage?.invoke(SearxngInstallStage.PIP, SearxngInstallStage.PIP.progressWeight * 0.1f)
        step("pip", SearxngScripts.PIP_INSTALL, pipTimeoutMs)?.let { return it }

        // ── VERIFY：import 冒烟 ──────────────────────────────────────
        onStage?.invoke(SearxngInstallStage.VERIFY, SearxngInstallStage.VERIFY.progressWeight * 0.5f)
        step("verify", SearxngScripts.SMOKE_IMPORT, 120_000L)?.let { return it }

        return Result.success(Unit)
    }

    /** apt 依赖已满足判定（避免每次重试都白跑 25% 权重的 apt update）。 */
    private suspend fun aptSatisfied(): Boolean {
        val r = commands.exec(
            "command -v git >/dev/null && python3 -m venv --help >/dev/null 2>&1",
            30_000L
        )
        return r.getOrNull()?.ok == true
    }

    /**
     * 单阶段执行：null = 成功继续；非 null = 结构化失败（终止管线）。
     * 失败归因（Spec §12）：网络性关键词 → `install network`，其余 → `build failed`。
     */
    private suspend fun step(
        name: String,
        command: String,
        timeoutMs: Long
    ): Result<Unit>? {
        val result = commands.exec(command, timeoutMs)
            .getOrElse { e ->
                if (e is CancellationException) throw e
                return Result.failure(
                    RuntimeException("install network — $name: ${e.message ?: e::class.simpleName}")
                )
            }
        if (result.ok) return null
        val tail = result.stderr.trim().takeLast(TAIL_EXCERPT_CHARS)
        val reason = when {
            result.timedOut -> "install network — $name timed out after ${timeoutMs / 1000}s"
            isNetworkFailure(result.stderr) -> "install network — $name: $tail"
            else -> "build failed — $name: $tail"
        }
        return Result.failure(RuntimeException(reason))
    }

    /**
     * 卸载（「清除搜索组件」入口，Spec §8/§11）。
     * host 侧直删（rootfs 即 host 目录，比经 guest rm -rf 快且不依赖守护状态）。
     * 幂等：目录不存在 = 成功。
     */
    suspend fun uninstall(): Result<Unit> = try {
        val rootfs = commands.rootfsDir()
        if (rootfs != null) {
            val dir = SearxngScripts.hostInstallDir(rootfs)
            if (dir.exists()) {
                withContext(Dispatchers.IO) { dir.deleteRecursively() }
            }
        }
        Result.success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(RuntimeException("uninstall failed: ${e.message ?: e::class.simpleName}"))
    }

    private fun isNetworkFailure(stderr: String): Boolean = NETWORK_FAILURE_HINTS.any { it in stderr }

    companion object {
        /** 网络性失败关键词（与 UbuntuAptPackageManager.mapExecToError 的判定集对齐）。 */
        private val NETWORK_FAILURE_HINTS = listOf(
            "Temporary failure", "Could not resolve", "Connection failed",
            "Network is unreachable", "timed out", "TLS", "Certificate verification",
            "Failed to connect", "gnutls_handshake", "unable to access"
        )

        /** 失败日志尾部摘录上限（Failed.reason 尺寸防线）。 */
        private const val TAIL_EXCERPT_CHARS = 600
    }
}
