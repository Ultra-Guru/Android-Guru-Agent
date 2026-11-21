package com.apex.agent.codenative

import com.apex.agent.core.code.StandardTextKernel

/**
 * # Native Text Kernel — 标准引擎文本加速核的 JNI 实现
 *
 * `libcode_native.so`（C++17，CMake 见 `src/main/cpp/`）承载三个高频
 * 纯文本操作；本类是它的 Kotlin 桥 + **纯 Kotlin 回退**：
 *
 * - .so 加载失败（模拟器裁剪 / 未来架构扩展）→ [available] = false，
 *   所有操作走 [StandardTextKernel.PureKotlin]（行为一致、慢一档）；
 * - JNI 调用全程 `runCatching` 包底——加速核**永不**把异常抛进引擎
 *   （契约见 code_kernel.h）；
 * - 语义与 PureKotlin 逐一对齐（估 token 公式 / 行哈希多重集 diff /
 *   滑窗子序列评分）——`code-native` 的单测用同一输入交叉验证两侧。
 *
 * ## 使用方
 *
 * App DI（CodeModule）把它作为 [StandardTextKernel] 注入
 * `StandardKitAdapter`（标准线循环库接线）——引擎侧只看 SPI，不感知 native。
 */
class NativeTextKernel : StandardTextKernel {

    override fun estimateTokens(text: String): Int {
        if (!available || text.isEmpty()) {
            return StandardTextKernel.PureKotlin.estimateTokens(text)
        }
        val native = runCatching { nativeEstimateTokens(text) }.getOrNull()
        return native ?: StandardTextKernel.PureKotlin.estimateTokens(text)
    }

    override fun diffStat(oldText: String, newText: String): StandardTextKernel.LineDiffStat? {
        if (!available) {
            return StandardTextKernel.PureKotlin.diffStat(oldText, newText)
        }
        val native = runCatching { nativeDiffStat(oldText, newText) }.getOrNull()
            ?: return StandardTextKernel.PureKotlin.diffStat(oldText, newText)
        if (native.size != 2) {
            return StandardTextKernel.PureKotlin.diffStat(oldText, newText)
        }
        return StandardTextKernel.LineDiffStat(
            addedLines = native[0],
            removedLines = native[1]
        )
    }

    override fun fuzzyLocate(haystack: String, needle: String): StandardTextKernel.FuzzyMatch? {
        if (!available || haystack.isEmpty() || needle.isEmpty()) {
            return StandardTextKernel.PureKotlin.fuzzyLocate(haystack, needle)
        }
        val native = runCatching { nativeFuzzyLocate(haystack, needle) }.getOrNull()
            ?: return StandardTextKernel.PureKotlin.fuzzyLocate(haystack, needle)
        if (native.size != 2) {
            return StandardTextKernel.PureKotlin.fuzzyLocate(haystack, needle)
        }
        return StandardTextKernel.FuzzyMatch(
            index = native[0],
            score = (native[1] / 1000f).coerceIn(0f, 1f)
        )
    }

    companion object {
        /**
         * .so 可用性（进程级一次判定）。
         *
         * 加载失败静默降级——App 在无 NDK ABI 的设备上行为与纯 Kotlin
         * 完全一致（用户无感知，只是仪表估算慢一档——均为微秒级操作）。
         */
        @Volatile
        var available: Boolean = false
            private set

        init {
            runCatching { System.loadLibrary("code_native") }
                .onSuccess { available = true }
        }
    }

    // ── JNI 声明（C++ 侧契约：零异常抛出，失败返回回退值/ null）──

    private external fun nativeEstimateTokens(text: String): Int

    private external fun nativeDiffStat(oldText: String, newText: String): IntArray?

    private external fun nativeFuzzyLocate(haystack: String, needle: String): IntArray?
}
