// ═══════════════════════════════════════════════════════════════════════════
// apex::codenative — 标准任务循环的文本加速核（C++17 纯算法层）
//
// 三个高频纯文本操作（与 Kotlin 参考实现 StandardTextKernel.PureKotlin
// 语义逐一对齐——单测交叉验证两侧行为一致）：
//   * estimateTokens —— token 启发式估算（ASCII≈4 chars/token，CJK×1.5）
//   * diffStat       —— 行级增删统计（行哈希多重集差）
//   * fuzzyLocate    —— 滑窗贪心子序列评分的模糊锚点定位
//
// 本层零 JNI、零平台依赖；JNI 桥在 code_native_jni.cpp。
// ═══════════════════════════════════════════════════════════════════════════
#ifndef APEX_CODENATIVE_CODE_KERNEL_H
#define APEX_CODENATIVE_CODE_KERNEL_H

#include <cstddef>
#include <cstdint>

namespace apex::codenative {

// ── 输入输出结构 ────────────────────────────────────────────────────────

/** UTF-16 码元视图（JNI GetStringChars 的零拷贝投影；核心算法不依赖 JNI）。 */
struct Utf16View {
    const char16_t* data;
    size_t length;
};

/** 行级 diff 统计。 */
struct DiffStat {
    int32_t added_lines;
    int32_t removed_lines;
};

/** 模糊匹配结果（score 0..1；match=false = 无合理匹配）。 */
struct FuzzyMatch {
    bool   matched;
    int32_t index;
    float  score;
};

// ── 算法 ────────────────────────────────────────────────────────────────

/**
 * token 估算：ASCII/拉丁 ≈ 4 chars/token；CJK（含全角标点）×1.5 密度。
 * 与 StandardSession.defaultTokenEstimator 完全同式（交叉验证锚点）。
 */
int32_t EstimateTokens(const Utf16View& text);

/**
 * 行级 diff 统计：行 → 哈希 → 多重集差（"整行增删"精确；
 * 行内微改计为一增一删——与 Kotlin PureKotlin.diffStat 同款口径）。
 */
DiffStat DiffStatOf(const Utf16View& old_text, const Utf16View& new_text);

/**
 * 模糊定位：needle 精确子串命中 → (index, 1.0)；否则滑窗（窗口 =
 * 1.5×needle 长度，步长 = 窗口/2）贪心子序列评分，取最高分窗口。
 * 评分 < 0.6 视为无匹配（matched=false）。
 */
FuzzyMatch FuzzyLocate(const Utf16View& haystack, const Utf16View& needle);

}  // namespace apex::codenative

#endif  // APEX_CODENATIVE_CODE_KERNEL_H
