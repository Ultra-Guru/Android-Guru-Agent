package com.apex.agent.platform.terminal.proot

import com.apex.agent.platform.terminal.api.TerminalMode
import com.apex.agent.platform.terminal.linux.CpuArchitecture
import com.apex.agent.platform.terminal.linux.RootfsDescriptor
import com.apex.agent.platform.terminal.workspace.AbsolutePath
import com.apex.agent.platform.terminal.workspace.WorkspacePath

/**
 * PR #63: PRoot Userspace Backend —— 共享组件层。
 *
 * T73 架构收编：本文件曾同时承载 (a) P71 LinuxPRootBackend 仍在使用的共享组件
 * （BinaryProvider / CommandBuilder / LaunchRequest / RootfsValidator）与 (b) P68 旧
 * 运行时栈（PRootRuntime + MountPlanner/EnvironmentBuilder + 伪 PID 的
 * PRootProcessProvider/PRootPtyProvider 管线）。后者已被 P71 方案 C（forkpty →
 * execv(proot …) 与本地会话共用同一条 PTY 基础设施）整体取代，双栈并存会误导
 * 后续开发 —— 现已删除（详见 T73 PR 描述）。
 *
 * 保留在本文件的类型均为 P71/T72 生产路径的活代码：
 *   - [PRootBinaryProvider]/[PRootBinaryInfo]/[PRootVersion]   二进制定位与校验
 *   - [PRootLaunchRequest]/[PRootBind]/[PRootCommand]          spawn 请求与产物
 *   - [PRootCommandBuilder]                                    argv 构造（-r/-0/--kill-on-exit/-b/-w/--/env-trampoline）
 *   - [RootfsValidator] 及校验类型                              rootfs 布局校验
 *
 * Spec: PR #63 sections 1-74（共享部分）/ PR #75 计划 §3（P71 架构）。
 */

// ─── Section 6/7: PRoot Binary Provider + Info ───

/**
 * T91（D5）：PRoot argv 能力集 —— 两个 Termux 补丁选项的**独立**实测结果。
 *
 * 背景（「版本静默差异」根因档案 + CI 实测修正）：仓库面对的 proot 对
 * Termux 补丁选项的支持是**正交的两项**，不能耦合在单一方言枚举里：
 *
 * | 二进制 | `--kill-on-exit` | `--` 终结符 |
 * |---|---|---|
 * | 捆绑 Termux 补丁版 5.1.107.92（APK jniLibs，设备生产） | ✅ | ✅ |
 * | 上游 5.1.0（Ubuntu 24.04 存档） | ❌ | ❌ |
 * | Debian 5.4.0（CI E2E 的 host proot，实测） | ✅（上游已采纳） | ❌（unknown option） |
 *
 * T88 时代测试里靠 `adaptForUpstreamProot` 手工过滤 —— 与 -E 事故同构的
 * 「适配层遮蔽真实 argv」风险。T91 起由 [NativeLibraryPRootBinaryProvider]
 * 的双能力探针在 verify 时实测判定，argv 构造端（[PRootCommandBuilder]）
 * 按能力自适应 —— **同一个 builder 对任何 proot 产出可执行 argv**。
 * 探针不可判定（exec 异常/超时）时保守省略（两项的省略在任何 proot 上
 * 均合法：guest 命令恒以非选项 token `/usr/bin/env` 开头，天然分界）。
 */
data class PRootArgvCapabilities(
    /** 支持 `--kill-on-exit`（proot 退出时杀光 guest 进程树）。 */
    val supportsKillOnExit: Boolean,
    /** 支持 `--` 选项终结符（guest 命令与 proot 选项的显式分界）。 */
    val supportsOptionSeparator: Boolean
) {
    companion object {
        /** 捆绑 Termux 5.1.107 的已知能力（测试夹具/诊断基线；设备 argv 逐字节不变）。 */
        val TERMUX_BUNDLED = PRootArgvCapabilities(supportsKillOnExit = true, supportsOptionSeparator = true)

        /** 保守形状：两项皆省 —— 任何 proot（含纯上游）均可执行。 */
        val UPSTREAM_SAFE = PRootArgvCapabilities(supportsKillOnExit = false, supportsOptionSeparator = false)
    }
}

data class PRootBinaryInfo(
    val path: AbsolutePath,
    val version: PRootVersion?,
    val architecture: CpuArchitecture,
    val executable: Boolean,
    /**
     * T91（D5）：argv 能力集（双探针实测；探针不可用时保守省略两项 ——
     * 见 [PRootArgvCapabilities] KDoc）。默认捆绑 Termux 基线（既有
     * 测试夹具的构造点不变）。
     */
    val capabilities: PRootArgvCapabilities = PRootArgvCapabilities.TERMUX_BUNDLED
)

data class PRootVersion(val major: Int?, val minor: Int?, val patch: Int?)

interface PRootBinaryProvider {
    suspend fun locate(): Result<AbsolutePath>
    suspend fun verify(binary: AbsolutePath): Result<PRootBinaryInfo>
}

// ─── Section 9/10/11: PRoot Command Builder ───
data class PRootLaunchRequest(
    val rootfs: RootfsDescriptor,
    val executable: String,
    val arguments: List<String> = emptyList(),
    val workingDirectory: WorkspacePath? = null,
    val environment: Map<String, String> = emptyMap(),
    val binds: List<PRootBind> = emptyList(),
    val terminalMode: TerminalMode = TerminalMode.AUTO,
    val fakeRoot: Boolean = false,
    val killOnExit: Boolean = true
)

data class PRootBind(
    val hostPath: AbsolutePath,
    val guestPath: String,
    val readOnly: Boolean = false
)

data class PRootCommand(
    val executable: AbsolutePath,
    val arguments: List<String>
)

interface PRootCommandBuilder {
    fun build(
        request: PRootLaunchRequest,
        prootBinary: AbsolutePath,
        rootfsPath: AbsolutePath,
        workspacePath: AbsolutePath,
        /** T91（D5）：目标 proot 能力集（默认捆绑 Termux 基线 —— 既有调用点行为不变）。 */
        capabilities: PRootArgvCapabilities = PRootArgvCapabilities.TERMUX_BUNDLED
    ): PRootCommand
}

class PRootCommandBuilderImpl : PRootCommandBuilder {
    override fun build(
        request: PRootLaunchRequest,
        prootBinary: AbsolutePath,
        rootfsPath: AbsolutePath,
        workspacePath: AbsolutePath,
        capabilities: PRootArgvCapabilities
    ): PRootCommand {
        val args = mutableListOf<String>()
        // Root
        args.add("-r")
        args.add(rootfsPath.value)
        // Fake root
        if (request.fakeRoot) args.add("-0")
        // Kill on exit —— T91（D5）：仅当目标 proot 实测支持时才发。
        // 上游 5.1.0 的选项表没有它（Debian 5.4 已采纳）；盲发在 5.1.0 上
        // 直接 unknown option 拒启 —— 此前 CI 靠测试内适配层偷偷过滤
        //（-E 事故同构风险），现在由探针结果参与决策，根除静默差异。
        if (request.killOnExit && capabilities.supportsKillOnExit) args.add("--kill-on-exit")
        // Binds
        for (bind in request.binds) {
            // TM6: argument-injection guard — proot splits `-b host:guest[:options]`
            // on the FIRST `:`, so a `:` embedded in guestPath shifts the remainder
            // into the `:options` slot (e.g. `host:/etc:passwd:0` becomes
            // host=/host, guest=/etc, options=passwd:0). Reject any `:` in guestPath.
            // hostPath is an AbsolutePath (already validated to be absolute, no `:`).
            require(bind.guestPath.indexOf(':') < 0) {
                "PRootError:InvalidBind — guestPath must not contain ':' (got \"${bind.guestPath}\"), " +
                    "otherwise proot's -b host:guest[:options] parser is argument-injected"
            }
            args.add("-b")
            args.add("${bind.hostPath.value}:${bind.guestPath}" + if (bind.readOnly) ":0" else "")
        }
        // Workspace bind (always bind workspace to /workspace inside rootfs)
        args.add("-b")
        args.add("${workspacePath.value}:/workspace")
        // Working directory
        // P71 修正：-w 是 GUEST 路径。workspace 绑定在 guest /workspace ——
        // WorkspacePath("workspace:/foo") 必须映射为 "/workspace/foo"，
        // 而不是旧的 removePrefix 结果 "/foo"（那是 rootfs 相对路径，指向错位置）。
        // 无前缀的值按 guest 绝对路径原样使用（如 "/root"）。
        val guestCwd = request.workingDirectory?.let { toGuestPath(it.value) }
        if (guestCwd != null) {
            args.add("-w")
            args.add(guestCwd)
        }
        // Environment passthrough —— T88 根治：不再使用 proot `-E`（捆绑的 5.1.107
        // 不支持该选项，用户设备上 bootstrap 直接报 proot error: unknown option '-E'）。
        // 改用 Termux proot-distro 同款 env trampoline：guest 命令变成
        // `/usr/bin/env -i K=V … <executable> <args…>`，5.1~5.4+ 全兼容。
        // 防注入校验（key 形态 / value 无 \n、NUL）下沉到 [PRootEnvTrampoline]。
        //
        // TM6 历史注释存档：原 -E 守卫拒绝 value 中的 `\n`/NUL，语义由
        // PRootEnvTrampoline.guestPrefix 完整平移并加强（key 形态校验）。
        //
        // T91（D5）：`--` 终结符独立探针实测（Debian 5.4 实测不支持 —— 即使它
        // 已支持 --kill-on-exit）。不支持时省略：proot 选项解析遇首个非选项
        // token 即视为命令起点，`/usr/bin/env` 开头的 trampoline 天然分界
        //（上游 5.1.0/5.4 E2E 实测同形可用；省略在任何 proot 上均合法）。
        if (capabilities.supportsOptionSeparator) args.add("--")
        args.addAll(PRootEnvTrampoline.guestPrefix(request.environment))
        args.add(request.executable)
        args.addAll(request.arguments)
        return PRootCommand(executable = prootBinary, arguments = args)
    }

    /** workspace: 前缀路径 → guest /workspace 下路径；无前缀 → guest 绝对路径。 */
    internal fun toGuestPath(workspacePathValue: String): String {
        val prefix = "workspace:"
        return if (workspacePathValue.startsWith(prefix)) {
            val rest = workspacePathValue.removePrefix(prefix)
            if (rest.isEmpty() || rest == "/") "/workspace"
            else if (rest.startsWith("/")) "/workspace$rest"
            else "/workspace/$rest"
        } else {
            workspacePathValue
        }
    }
}

// ─── Section 13/14/15: Rootfs Validation ───
enum class RootfsValidationError {
    MISSING_ROOT, MISSING_BIN, MISSING_ETC, MISSING_USR, MISSING_HOME,
    NOT_DIRECTORY, NOT_READABLE, ARCHITECTURE_MISMATCH, CORRUPTED, UNKNOWN
}

data class RootfsValidation(
    val valid: Boolean,
    val architectureCompatible: Boolean,
    val hasRootDirectory: Boolean,
    val hasBin: Boolean,
    val hasEtc: Boolean,
    val hasUsr: Boolean,
    val hasHome: Boolean,
    val errors: List<RootfsValidationError>
)

interface RootfsValidator {
    suspend fun validate(rootfs: RootfsDescriptor): Result<RootfsValidation>
}
