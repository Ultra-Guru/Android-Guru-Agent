package com.apex.agent.platform.privilege.shizuku

/**
 * ═══ DCP 结构化执行通道（S08）═══
 *
 * 高危意图的 argv 直调契约：调用方（DeviceOpsExecutor / DevicePreflight）
 * 把渲染后的 `Array<String>` 原样交给 [DeviceChannel.execute] —— 通道实现
 * **必须**经 `IShizukuService.newProcess(argv, env, dir)` 直调，禁止包装
 * `sh -c` 拼接（结构化意图的注入面就此关闭）。
 *
 * 本文件是纯 Kotlin 数据模型 + 接口（零 android.* import —— 纯 JVM 单测
 * 可直接 fake）；Shizuku 落地实现在 app 层 [com.apex.agent.tools.ShizukuDeviceChannel]。
 */

/** 一次结构化执行请求。argv 逐元素直传，不含任何 shell 语法。 */
data class DeviceExecRequest(
    val argv: Array<String>,
    val env: Array<String>? = null,
    val dir: String? = null,
    /** stdin 载荷（UTF-8；file.write 的 tee 通道用）。null = 不写 stdin。 */
    val stdinUtf8: String? = null,
    val timeoutMs: Long = 30_000L,
    /** 输出预算（首 N + 尾 M 有界采集；超预算 truncated=true，绝不 OOM）。 */
    val maxOutputBytes: Long = 1_048_576L
)

/** 结构化执行结果。字段语义全部「诚实」：超时/截断/取消如实上报。 */
data class DeviceExecResult(
    val ok: Boolean,
    val pid: Long? = null,
    val exitCode: Int? = null,
    val stdout: String = "",
    val stderr: String = "",
    /** 输出超预算被首尾截断时为 true —— 调用方不得把截断输出当完整输出。 */
    val truncated: Boolean = false,
    val timedOut: Boolean = false,
    val cancelled: Boolean = false,
    /** 实际执行通道标识（shizuku / dry-run 等）。 */
    val channel: String = "shizuku",
    /** IRemoteProcess 无进程组信号语义 —— 恒 false，别让上层以为能发 SIGINT。 */
    val supportsSignal: Boolean = false,
    /** 结构化错误码（通道级）：null = 正常返回。 */
    val error: String? = null,
    /** 面向模型的开通指引（error 非空时）。 */
    val hint: String? = null
)

/**
 * 结构化 argv 执行通道。
 *
 * 实现纪律（S08 验收口径）：
 * 1. 未授权 / binder 死亡 → 结构化失败 + 开通步骤，**绝不**回退本地
 *    app-shell 冒充（对齐 ShizukuCommandExecutor 的诚实口径）；
 * 2. binder 死亡 / SecurityException → 主动 PrivilegeDetector.invalidateCache()，
 *    防止 30s 缓存误报可用；
 * 3. 超时/取消用分片轮询（100ms slice + 排空宽限），destroy 收尾；
 * 4. 输出首 N + 尾 M 有界采集；
 * 5. supportsSignal=false 恒如实。
 */
interface DeviceChannel {
    /**
     * @param isCancelled 协作取消探针（分片轮询间隙检查；取消时 destroy 并
     *   返回 cancelled=true，不抛 CancellationException —— 结构化结果优先）
     */
    suspend fun execute(req: DeviceExecRequest, isCancelled: () -> Boolean = { false }): DeviceExecResult
}
