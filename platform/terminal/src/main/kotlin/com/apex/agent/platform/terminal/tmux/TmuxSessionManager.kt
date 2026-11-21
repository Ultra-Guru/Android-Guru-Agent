package com.apex.agent.platform.terminal.tmux

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Tmux 多会话管理器（Operit 对标：docs/terminal/operit-rikkahub-comparison.md §4.7
 * 列出的「SSH/tmux 多会话」能力补齐）。
 *
 * 路线（本仓库约束下的诚实选型）：tmux 跑在 **Ubuntu rootfs 内**，本管理器通过
 * 现有 shell-exec 通道（TerminalExecTool 同一条 ProotCommandSpawner 链路）发 tmux
 * 命令 —— 不引入第二套进程模型，不与既有 apex VT100 / C++17 终端热路径抢 PTY。
 *
 * 为什么走 shell-exec 而非 JNI：TERMUX_CAPABILITY_MATRIX §10.4 已记录 PRoot 下
 * tmux 的真实约束（server 与 rootfs 进程树同生命周期）；把会话语义放进 tmux
 * 自身（detached session）而非另起常驻守护，是与该约束一致的最小方案。
 *
 * 诚实性契约（对齐 ExecEngine / ProotExecutor）：
 *  - 会话存活**只以 `tmux ls` 的真实输出**为权威；[TmuxSessionStore] 的登记是
 *    软提示（上次叫什么），绝不假报存活；
 *  - tmux 不存在 = [TmuxException.TmuxUnavailable]（recoverable，先 ensure rootfs）；
 *  - tmux 命令非零退出 = [TmuxException.TmuxCommandFailed]，携带真实 stderr；
 *  - 参数校验失败 = [TmuxException.InvalidInput]，绝不动手执行半成品命令。
 *
 * 并发：[mutex] 串行化写操作（new/switch/kill），避免两个并发 create 抢同名会话；
 * 读操作（list）不加锁 —— tmux ls 本身是只读快照。
 */
class TmuxSessionManager(
    /** shell-exec 接缝：注入即纯 JVM 可测（无 gradle/无 ANDROID_HOME 的 CI）。 */
    private val executor: suspend (command: String, timeoutMs: Long) -> TmuxCommandResult,
    private val store: TmuxSessionStore,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    /** 一条 tmux 命令的结构化结果（对齐 CommandResult 的最小子集）。 */
    data class TmuxCommandResult(
        val exitCode: Int?,
        val stdout: String,
        val stderr: String
    )

    /** 一条存活会话（来自 `tmux ls` 真实输出，非登记软提示）。 */
    data class TmuxSession(
        val name: String,
        val windows: Int,
        val created: Long?,
        val attached: Boolean,
        /** 该会话是否为当前登记的活跃会话。 */
        val active: Boolean = false
    )

    /** ── 创建命名会话（幂等：同名且存活 → 直接返回成功，不报错）。 ── */
    suspend fun createSession(name: String, workingDirectory: String? = null): String {
        validateName(name)
        return mutex.withLock {
            ensureTmuxAvailable()
            val existing = rawList()
            if (existing.any { it.name == name }) {
                // 幂等创建（对齐 LinuxWorkspaceManager.create 的同款语义）。
                markActive(name)
                return@withLock buildJsonObject {
                    put("ok", true)
                    put("action", "new")
                    put("name", name)
                    put("alreadyExists", true)
                    put("activeSession", name)
                }.let { json.encodeToString(it) }
            }
            val dir = sanitizeDir(workingDirectory)
            val cmd = buildString {
                append(tmuxBin())
                append(" new-session -d -s ").append(quote(name))
                if (dir != null) append(" -c ").append(quote(dir))
            }
            val result = executor(cmd, DEFAULT_TIMEOUT_MS)
            requireExitZero(result, "new-session '$name'")
            markActive(name)
            persistRegistration(name, dir)
            buildJsonObject {
                put("ok", true)
                put("action", "new")
                put("name", name)
                put("alreadyExists", false)
                put("workingDirectory", dir)
                put("activeSession", name)
            }.let { json.encodeToString(it) }
        }
    }

    /** ── 列出全部存活会话（权威：`tmux ls`；store 仅提供 active 标注）。 ── */
    suspend fun listSessions(): String {
        ensureTmuxAvailable()
        val sessions = rawList()
        val active = store.load()?.lastActiveSession
        val arr = buildJsonArray {
            sessions.forEach { s ->
                add(buildJsonObject {
                    put("name", s.name)
                    put("windows", s.windows)
                    s.created?.let { put("created", it) }
                    put("attached", s.attached)
                    put("active", s.name == active)
                })
            }
        }
        return buildJsonObject {
            put("ok", true)
            put("action", "list")
            put("activeSession", active)
            put("sessions", arr)
            put("count", sessions.size)
        }.let { json.encodeToString(it) }
    }

    /** ── 切换活动会话（仅存活会话；store 落 lastActive 供下一次默认）。 ── */
    suspend fun switchSession(name: String): String {
        validateName(name)
        return mutex.withLock {
            ensureTmuxAvailable()
            val sessions = rawList()
            if (sessions.none { it.name == name }) {
                throw TmuxException.SessionNotFound(name)
            }
            markActive(name)
            touchAttachment(name)
            buildJsonObject {
                put("ok", true)
                put("action", "switch")
                put("name", name)
                put("activeSession", name)
            }.let { json.encodeToString(it) }
        }
    }

    /** ── 终止会话（kill-session；幂等：不存在 → SessionNotFound）。 ── */
    suspend fun killSession(name: String): String {
        validateName(name)
        return mutex.withLock {
            ensureTmuxAvailable()
            val sessions = rawList()
            if (sessions.none { it.name == name }) {
                throw TmuxException.SessionNotFound(name)
            }
            val cmd = "${tmuxBin()} kill-session -t ${quote(name)}"
            val result = executor(cmd, DEFAULT_TIMEOUT_MS)
            requireExitZero(result, "kill-session '$name'")
            forgetRegistration(name)
            buildJsonObject {
                put("ok", true)
                put("action", "kill")
                put("name", name)
            }.let { json.encodeToString(it) }
        }
    }

    /** ── 向目标会话发送一行命令（send-keys；多会话并行的实际价值点）。 ── */
    suspend fun sendKeys(name: String, command: String): String {
        validateName(name)
        if (command.isBlank()) {
            throw TmuxException.InvalidInput("command 不能为空（send-keys 需要有效载荷）")
        }
        return mutex.withLock {
            ensureTmuxAvailable()
            val sessions = rawList()
            if (sessions.none { it.name == name }) {
                throw TmuxException.SessionNotFound(name)
            }
            val payload = quote(command.replace("\n", " ").trim())
            val cmd = "${tmuxBin()} send-keys -t ${quote(name)} $payload Enter"
            val result = executor(cmd, DEFAULT_TIMEOUT_MS)
            requireExitZero(result, "send-keys '$name'")
            buildJsonObject {
                put("ok", true)
                put("action", "sendKeys")
                put("name", name)
            }.let { json.encodeToString(it) }
        }
    }

    /** ── 抓取目标会话当前屏面（capture-pane；Agent 无需 attach 即可读输出）。 ── */
    suspend fun capturePane(name: String, lines: Int = 200): String {
        validateName(name)
        val bounded = lines.coerceIn(MIN_CAPTURE_LINES, MAX_CAPTURE_LINES)
        return mutex.withLock {
            ensureTmuxAvailable()
            val sessions = rawList()
            if (sessions.none { it.name == name }) {
                throw TmuxException.SessionNotFound(name)
            }
            val cmd = "${tmuxBin()} capture-pane -t ${quote(name)} -p -S -$bounded"
            val result = executor(cmd, DEFAULT_TIMEOUT_MS)
            requireExitZero(result, "capture-pane '$name'")
            buildJsonObject {
                put("ok", true)
                put("action", "capture")
                put("name", name)
                put("lines", result.stdout.lines().size)
                put("output", result.stdout)
            }.let { json.encodeToString(it) }
        }
    }

    // ───────────────────────── internals ─────────────────────────

    /**
     * `tmux ls` 真实解析（格式串：name:windows:created:attached）。
     * 无任何会话时 tmux 以 exit 1 + "no server running" 退出 —— 这是**正常空态**，
     * 不是失败（诚实上报 count=0，绝不抛异常让 Agent 误判环境坏了）。
     */
    private suspend fun rawList(): List<TmuxSession> {
        val cmd = "${tmuxBin()} list-sessions -F " + quote("#S\t#{session_windows}\t#{session_created}\t#{session_attached}")
        val result = executor(cmd, DEFAULT_TIMEOUT_MS)
        val code = result.exitCode
        if (code != null && code != 0) {
            val combined = (result.stderr + result.stdout).lowercase()
            if (combined.contains("no server running") || combined.contains("no sessions")) {
                return emptyList()
            }
            throw TmuxException.TmuxCommandFailed(
                "list-sessions 失败（exit=$code）: ${result.stderr.ifBlank { result.stdout }}"
            )
        }
        return parseListOutput(result.stdout)
    }

    /** 解析 list-sessions 的制表符分隔输出；畸形行跳过（不臆造会话）。 */
    internal fun parseListOutput(stdout: String): List<TmuxSession> =
        stdout.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size < 4) return@mapNotNull null
                val name = parts[0].substringBefore(':').trim()
                if (name.isEmpty()) return@mapNotNull null
                val windows = parts[1].trim().toIntOrNull() ?: return@mapNotNull null
                val created = parts[2].trim().toLongOrNull()
                val attached = parts[3].trim() == "1"
                TmuxSession(name, windows, created, attached)
            }

    private suspend fun ensureTmuxAvailable() {
        val probe = executor("command -v ${tmuxBin()} || command -v tmux", PROBE_TIMEOUT_MS)
        if (probe.exitCode != 0 || probe.stdout.isBlank()) {
            throw TmuxException.TmuxUnavailable(
                "guest rootfs 内未找到 tmux —— 先 TerminalUbuntuEnsure / " +
                    "TerminalLinuxPackages(action=install, packages=[\"tmux\"]) 补装"
            )
        }
    }

    private suspend fun markActive(name: String) {
        val current = store.load() ?: TmuxSessionStore.StoreRecord()
        store.save(current.copy(lastActiveSession = name, updatedAt = clock()))
    }

    private suspend fun persistRegistration(name: String, workingDirectory: String?) {
        val current = store.load() ?: TmuxSessionStore.StoreRecord()
        val now = clock()
        val entry = TmuxSessionStore.SessionRecord(
            name = name, createdAt = now, workingDirectory = workingDirectory, lastAttachedAt = now
        )
        val next = current.sessions.filterNot { it.name == name } + entry
        store.save(current.copy(sessions = next, updatedAt = now))
    }

    private suspend fun touchAttachment(name: String) {
        val current = store.load() ?: return
        val now = clock()
        val next = current.sessions.map {
            if (it.name == name) it.copy(lastAttachedAt = now) else it
        }
        store.save(current.copy(sessions = next, updatedAt = now))
    }

    private suspend fun forgetRegistration(name: String) {
        val current = store.load() ?: return
        val next = current.sessions.filterNot { it.name == name }
        val lastActive = current.lastActiveSession?.takeIf { it != name }
        store.save(current.copy(sessions = next, lastActiveSession = lastActive, updatedAt = clock()))
    }

    private fun requireExitZero(result: TmuxCommandResult, op: String) {
        val code = result.exitCode
        if (code == null) {
            throw TmuxException.TmuxCommandFailed("$op spawn 失败: ${result.stderr.ifBlank { "no stderr" }}")
        }
        if (code != 0) {
            throw TmuxException.TmuxCommandFailed(
                "$op 失败（exit=$code）: ${result.stderr.ifBlank { result.stdout }}"
            )
        }
    }

    private fun tmuxBin(): String = "tmux"

    private fun validateName(name: String?) {
        if (name.isNullOrBlank()) {
            throw TmuxException.InvalidInput("name 不能为空")
        }
        if (name.length > MAX_NAME_LENGTH) {
            throw TmuxException.InvalidInput("name 过长（>${MAX_NAME_LENGTH} 字符）")
        }
        if (!NAME_PATTERN.matches(name)) {
            throw TmuxException.InvalidInput(
                "name '$name' 含非法字符（规则: $NAME_PATTERN_TEXT；tmux 目标名禁 `.` `:` 等定位符）"
            )
        }
    }

    private fun sanitizeDir(dir: String?): String? {
        if (dir.isNullOrBlank()) return null
        if (dir.contains('\n') || dir.contains('\u0000')) {
            throw TmuxException.InvalidInput("workingDirectory 含非法控制字符")
        }
        return dir.trim()
    }

    /** 单引号安全转义（对齐 builtin/ShellQuote 的 POSIX 语义，本模块不依赖 tool-registry）。 */
    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    companion object {
        const val DEFAULT_TIMEOUT_MS = 30_000L
        const val PROBE_TIMEOUT_MS = 10_000L
        const val MAX_NAME_LENGTH = 64
        const val MIN_CAPTURE_LINES = 1
        const val MAX_CAPTURE_LINES = 10_000
        const val NAME_PATTERN_TEXT = "^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$"
        private val NAME_PATTERN = Regex(NAME_PATTERN_TEXT)

        /** 供测试/工具层复用的 result 工厂。 */
        fun result(exitCode: Int?, stdout: String, stderr: String) =
            TmuxCommandResult(exitCode, stdout, stderr)
    }
}
