package com.apex.agent.core.tools.extension.dsh

import java.io.File
import java.security.MessageDigest

/**
 * # DshPackageFingerprint —— 包内容指纹（与能力快照的新鲜度校验基准）
 *
 * [DshCapabilitiesStore.read] 的注册场景要求传当前指纹（快照过期 = 插件已
 * 变 = 绝不拿旧账注册）。指纹算法此前内嵌在 [DshArtifactAnalyzer.analyze]
 * 的文件清单循环里——registrar 需要**只算指纹不跑 AST**（启动 sweep 不能
 * 每次 60s 沙箱分析），故抽出为独立对象；analyzer 改为委托本对象，保证
 * 两处指纹**同源**（算法漂移 = 全部快照集体过期，禁止各写一份）。
 *
 * 算法（与原实现逐字节一致）：跳过 node_modules/ 与 .git/，按相对路径
 * 排序，SHA-256(relPath + bytes)；预算内文件 [DshArtifactAnalyzer.MAX_FILES]
 * 个 / [DshArtifactAnalyzer.MAX_TOTAL_BYTES] 字节，超出如实计入 skipped。
 */
object DshPackageFingerprint {

    data class Result(
        val fingerprint: String,
        val analyzedFiles: Int,
        val skippedFiles: Int
    )

    /** 计算包内容指纹（读失败/无文件均如实计数，不抛）。 */
    fun compute(pkg: File): Result {
        val digest = MessageDigest.getInstance("SHA-256")
        var analyzedFiles = 0
        var skippedFiles = 0
        var totalBytes = 0L
        pkg.walkTopDown()
            .filter { it.isFile }
            .filter { f -> !relPath(pkg, f).startsWith("node_modules/") && !relPath(pkg, f).startsWith(".git/") }
            .sortedBy { relPath(pkg, it) }
            .forEach { f ->
                if (analyzedFiles >= DshArtifactAnalyzer.MAX_FILES || totalBytes >= DshArtifactAnalyzer.MAX_TOTAL_BYTES) {
                    skippedFiles += 1
                    return@forEach
                }
                val bytes = runCatching { f.readBytes() }.getOrNull()
                if (bytes == null) {
                    skippedFiles += 1
                    return@forEach
                }
                analyzedFiles += 1
                totalBytes += bytes.size
                digest.update(relPath(pkg, f).toByteArray(Charsets.UTF_8))
                digest.update(bytes)
            }
        return Result(
            fingerprint = digest.digest().joinToString("") { "%02x".format(it) },
            analyzedFiles = analyzedFiles,
            skippedFiles = skippedFiles
        )
    }

    private fun relPath(pkg: File, f: File): String =
        f.relativeTo(pkg).invariantSeparatorsPath
}
