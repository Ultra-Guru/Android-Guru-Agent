package com.apex.agent.platform.terminal.proot

import java.io.File
import java.nio.file.Files

/**
 * libtalloc.so.2 SONAME staging —— proot 的 DT_NEEDED 是 `libtalloc.so.2`，
 * 而 Android 打包要求 jniLibs 以 `lib*.so` 命名（真实文件只能叫 `libtalloc.so`）。
 * 运行时必须在 LD_LIBRARY_PATH 覆盖的目录里提供同名字的 SONAME 入口
 * （Termux/UserLAnd 先例：symlink 指向真实文件，SONAME 查找按文件名进行）。
 *
 * ## 为什么需要跨目录重建（真机事故复盘，2026-10-24）
 *
 * 此前四份拷贝的兜底循环只做「同目录软链」：遍历 LD_LIBRARY_PATH 目录，
 * 仅在**含 libtalloc.so** 的目录（nativeLibraryDir）建 `libtalloc.so.2 →
 * libtalloc.so`。staging 目录（filesDir/linux/bin，LD_LIBRARY_PATH 首项、
 * 可写性有保证）里没有 libtalloc.so，永远拿不到链接；而 nativeLibraryDir
 * 建链在部分真机（OPPO PLE110 / Android 16 实证）静默失败——`runCatching`
 * 吞掉异常，git/MCP/marketplace 三条 proot 路径全线 `CANNOT LINK EXECUTABLE
 * libproot.so: library libtalloc.so.2 not found`。用户在 filesDir 手工建链
 * 后 proot 立即恢复——实证根因。
 *
 * 本助手改为：**每个 LD_LIBRARY_PATH 目录**都幂等重建 SONAME 入口，统一
 * 指向真实 libtalloc.so（nativeLibraryDir 内），因此 staging 目录也有了
 * 可用链接（linker 按序搜索，staging 在首位先命中）。同时修复两处历史坑：
 *
 * - **悬空链接**：app 升级后 nativeLibraryDir 路径变化（新 pkg hash 目录），
 *   staging 里的旧链接指向已删除目录成为悬空链接——`File.exists()` 对悬空
 *   链接返回 false，旧代码既删不掉（exists 判 false 跳过 delete）也建不了
 *   （createSymbolicLink 抛 FileAlreadyExistsException）。本助手显式判
 *   `isSymbolicLink && !exists` → 删除重建。
 * - **失败可观测**：不再静默吞异常，逐目录返回 FAILED + 异常详情，调用方
 *   记日志（下次诊断日志直接可见真实异常，不必再靠手工实验反推）。
 *
 * 纯 JVM（java.io.File / java.nio.file），无 Android Context 依赖：
 * app 层四条 proot 路径（git / MCP / tools spawner / marketplace）与
 * PRootHostEnvironment.prepare 共用本助手，消除四处拷贝。
 */
object PRootLibtallocStaging {

    /** 单个 LD_LIBRARY_PATH 目录的落地结果。 */
    data class DirResult(
        /** 目录绝对路径。 */
        val dir: String,
        /** 落地结论。 */
        val outcome: Outcome,
        /** FAILED 时的异常详情（`类名: 消息`）。 */
        val error: String? = null,
    )

    /** 目录落地结论。 */
    enum class Outcome {
        /** 本次新建了链接。 */
        CREATED,

        /** 链接已存在且有效（幂等跳过）。 */
        ALREADY_OK,

        /** 删除悬空链接后重建。 */
        RECREATED,

        /** 建链失败（异常详情见 [DirResult.error]）。 */
        FAILED,
    }

    /**
     * 幂等地确保 [hostEnv] 的 LD_LIBRARY_PATH 覆盖的每个目录都存在可用的
     * `libtalloc.so.2`，统一指向 `<libprootPath 所在目录>/libtalloc.so`。
     *
     * @param hostEnv proot 宿主 env（含 LD_LIBRARY_PATH；缺失时返回空列表）。
     * @param libprootPath libproot.so 绝对路径（仅用其父目录定位真实
     *   libtalloc.so，不校验该文件本身——调用方各自有二进制存在性检查）。
     * @return 逐目录结果（含 FAILED 详情）；不抛异常——失败是常态数据，
     *   由调用方决定记录/上抛语义。
     */
    fun ensure(hostEnv: Map<String, String>, libprootPath: String): List<DirResult> {
        val ldPath = hostEnv["LD_LIBRARY_PATH"] ?: return emptyList()
        val dirs = ldPath.split(':').filter { it.isNotBlank() }
        // 真实 libtalloc.so 所在目录（nativeLibraryDir，从 libprootPath 推导）。
        val nativeLibDir = File(libprootPath).parentFile
        val tallocSource = File(nativeLibDir, "libtalloc.so").takeIf { it.isFile }
            ?: return dirs.map {
                DirResult(it, Outcome.FAILED, "nativeLibraryDir 缺 libtalloc.so（APK 打包异常）")
            }

        return dirs.map { dir -> ensureDir(File(dir), tallocSource) }
    }

    /** 单目录落地：建目录 → 处理悬空链接 → 建链。异常折叠为 FAILED 结果。 */
    private fun ensureDir(dir: File, tallocSource: File): DirResult {
        val soname = File(dir, "libtalloc.so.2")
        try {
            if (!dir.isDirectory) dir.mkdirs()
            val p = soname.toPath()
            var recreated = false
            if (Files.isSymbolicLink(p) && !Files.exists(p)) {
                // 悬空链接：app 升级后指向旧 nativeLibraryDir —— 删除重建。
                soname.delete()
                recreated = true
            }
            if (Files.exists(p)) {
                // 有效链接（symlink 或常规文件均可被 linker 加载）。
                return DirResult(dir.absolutePath, Outcome.ALREADY_OK)
            }
            Files.createSymbolicLink(p, tallocSource.toPath())
            return DirResult(
                dir.absolutePath,
                if (recreated) Outcome.RECREATED else Outcome.CREATED,
            )
        } catch (e: Exception) {
            return DirResult(
                dir.absolutePath, Outcome.FAILED,
                "${e::class.simpleName}: ${e.message}",
            )
        }
    }
}
