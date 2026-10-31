package com.apex.agent.platform.terminal.chroot

import java.io.File

/**
 * T98：su 二进制定位器 —— chroot 后端的 Root 门禁探测。
 *
 * ## 设计约束（对齐 ExecutionBackend.availability 契约：廉价、无副作用）
 *
 * **只做文件系统探测（stat 级），绝不 exec** —— Android 的 su 体系
 *（Magisk / KernelSU / APatch）在 app 首次 exec su 时会拉起**授权弹窗**
 *（或直接拒绝）。availability() 每次会话创建都会被调用，把授权弹窗埋进
 * 探测路径等于每次 create 都骚扰用户。因此：
 *
 *  - 找到 su 文件（存在 + 可执行位）⇒ 后端报 READY（真实授权状态留在
 *    spawn 时刻由 su 自行裁决 —— 未授权时 su 打印拒绝信息并以非零退出，
 *    PTY 会话如实呈现，不伪造）；
 *  - 找不到 ⇒ [LinuxChrootBackend.availability] 报 FAILED（SU_NOT_FOUND），
 *    Agent 回退 PRoot 后端。
 *
 * ## 候选路径（按主流 Root 方案实测落点排序）
 *
 *  - `/system/bin/su`：Magisk（magic mount）/ KernelSU / APatch 的标准挂载点，
 *    现代方案绝大多数走这里；
 *  - `/system/xbin/su`：老式 SuperSU / 部分第三方 recovery 环境；
 *  - `/system/sbin/su`、`/sbin/su`：少数 ROM 的符号链接落点；
 *  - `/debug_ramdisk/su`：APatch 的备用路径；
 *  - `/su/bin/su`：phh's SuperUser 方案。
 *
 * 注：su 对 app 可见性本身由 Root 方案的挂载策略决定（Magisk 默认对所有
 * app 可见、执行时才鉴权）—— canExecute() 只反映存在性与执行位，不承诺
 * 授权通过。
 */
class SuBinaryLocator(
    /** 候选路径列表（JVM 测试注入 tmp 目录假 su；生产用 [DEFAULT_CANDIDATES]）。 */
    private val candidatesProvider: () -> List<File> = { DEFAULT_CANDIDATES.map(::File) },
    /**
     * 额外候选（app 层可注入用户指定路径，如非标准 Root 方案）；在标准
     * 候选之后探测。默认空。
     */
    private val extraCandidates: () -> List<File> = { emptyList() },
) {

    /**
     * 定位 su：首个「存在且可执行」的候选；全miss 返回 null（诚实 ——
     * 不猜 `which`、不 exec 探测）。
     */
    fun locate(): File? =
        (candidatesProvider() + extraCandidates()).firstOrNull { it.isFile && it.canExecute() }

    companion object {
        /** Android 主流 Root 方案的 su 落点（见类 KDoc 排序依据）。 */
        val DEFAULT_CANDIDATES: List<String> = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/system/sbin/su",
            "/sbin/su",
            "/debug_ramdisk/su",
            "/su/bin/su",
        )
    }
}
