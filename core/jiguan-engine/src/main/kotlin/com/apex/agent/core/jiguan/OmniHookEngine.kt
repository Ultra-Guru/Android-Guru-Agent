package com.apex.agent.core.jiguan

/**
 * # Omni-Hook 引擎（跨语言统一 Hook 编排）
 *
 * Rust 核心只做底层桥接，**对外统一 Hook 接口**在本引擎收敛：
 * - 声明式 [HookSpec]（Agent 的意图）→ [FridaScriptLibrary] 生成脚本；
 * - [SandboxValidator] 沙盒校验（fail-closed）；
 * - 通道解析：FRIDA_SERVER（frida-server 常驻 + CLI 注入）＞
 *   FRIDA_GADGET（重打包注入）＞ ZYGISK（用户已装框架）＞
 *   UNAVAILABLE（结构化降级 + 安装指引）；
 * - 会话登记：已装 Hook 可枚举、可读取捕获事件（console 输出即事件流）。
 *
 * 线程模型：单例 + 内部互斥 —— Hook 生命周期操作串行化，事件列表
 * copy-on-read（读侧无锁）。
 */
class OmniHookEngine(
    private val scriptRunner: suspend (pid: Int, script: String, timeoutMs: Long) -> Result<String>,
    private val fridaServerAvailable: suspend () -> Boolean,
    private val gadgetModeAvailable: suspend () -> Boolean = { false },
    private val zygiskAvailable: suspend () -> Boolean = { false },
) {

    private val lock = Any()
    private val hooks = LinkedHashMap<Int, HookHandle>()
    private var nextId = 1

    /**
     * 当前通道解析（挂起探测 —— frida-server 存活检查走 root shell；
     * 每次安装时重估，frida-server 可能被临时拉起）。
     */
    suspend fun resolveChannel(): HookChannel = when {
        fridaServerAvailable() -> HookChannel.FRIDA_SERVER
        zygiskAvailable() -> HookChannel.ZYGISK
        gadgetModeAvailable() -> HookChannel.FRIDA_GADGET
        else -> HookChannel.UNAVAILABLE
    }

    /**
     * 安装一条 Hook：spec → 脚本生成 → 沙盒校验 → 通道执行 → 登记。
     *
     * @param timeoutMs 脚本驻留观察窗（FRIDA_SERVER 通道下 = CLI 会话时长）。
     * @return 登记句柄；通道缺席时返回结构化降级（[HookHandle.channel] =
     *   UNAVAILABLE，事件里带安装指引）。
     */
    suspend fun install(spec: HookSpec, timeoutMs: Long = 15_000): HookHandle {
        val script = if (spec.customScript.isNotBlank()) spec.customScript else generate(spec)
        val verdict = SandboxValidator.validate(script)
        val id: Int
        synchronized(lock) {
            id = nextId++
        }
        val channel = resolveChannel()
        if (!verdict.ok) {
            val handle = HookHandle(id, spec, HookChannel.UNAVAILABLE, listOf(verdict.failureText()))
            synchronized(lock) { hooks[id] = handle }
            return handle
        }
        if (channel == HookChannel.UNAVAILABLE) {
            val handle = HookHandle(id, spec, channel, listOf(unavailableGuidance()))
            synchronized(lock) { hooks[id] = handle }
            return handle
        }
        val events = when (spec.kind) {
            HookKind.JAVA_METHOD -> executeScript(spec.pid, script, timeoutMs)
            HookKind.NATIVE_FUNC -> executeScript(spec.pid, script, timeoutMs)
            HookKind.ART_METHOD -> executeScript(spec.pid, script, timeoutMs)
        }
        val handle = HookHandle(id, spec, channel, events)
        synchronized(lock) { hooks[id] = handle }
        return handle
    }

    /** 已装 Hook 快照（读侧 copy-on-read）。 */
    fun installed(): List<HookHandle> = synchronized(lock) { hooks.values.toList() }

    /** 卸载（FRIDA_SERVER 通道下 = 会话已自然结束，这里只做登记清理）。 */
    fun uninstall(id: Int): Boolean = synchronized(lock) { hooks.remove(id) != null }

    // ── 内部 ─────────────────────────────────────────────────────

    private suspend fun executeScript(pid: Int, script: String, timeoutMs: Long): List<String> {
        val result = scriptRunner(pid, script, timeoutMs)
        return result.fold(
            onSuccess = { output ->
                output.lineSequence()
                    .filter { it.isNotBlank() }
                    .take(400) // Context 预算：事件流封顶。
                    .toList()
            },
            onFailure = { listOf("[err] script execution failed: ${it.message ?: it.javaClass.simpleName}") },
        )
    }

    private fun generate(spec: HookSpec): String = when (spec.kind) {
        HookKind.JAVA_METHOD -> FridaScriptLibrary.javaHook(spec)
        HookKind.NATIVE_FUNC -> FridaScriptLibrary.nativeHook(
            moduleName = spec.className,
            symbolName = spec.symbolName,
            onEnterLog = true,
            onLeaveLog = true,
        )
        HookKind.ART_METHOD -> FridaScriptLibrary.javaHook(spec)
    }

    /** 结构化降级指引（用户可照做的安装步骤）。 */
    fun unavailableGuidance(): String = buildString {
        appendLine("[jiguan] 无可用注入通道 —— 按以下优先级配置其一：")
        appendLine("1. frida-server（推荐）：下载对应架构的 frida-server 至 /data/local/tmp/，")
        appendLine("   chmod +x 后以 root 拉起（端口 27042）；PRoot Ubuntu 内 `pip install frida-tools`。")
        appendLine("2. Zygisk/LSPosed：安装模块框架后经其注入机关脚本。")
        appendLine("3. Frida Gadget：无 root 场景对目标 APK 重打包注入 gadget。")
        appendLine("配置完成后重试本工具即可自动走 FRIDA_SERVER 通道。")
    }
}
