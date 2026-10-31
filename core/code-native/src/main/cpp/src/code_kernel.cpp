// ═══════════════════════════════════════════════════════════════════════════
// apex::codenative —— 算法实现（纯 C++17，零平台依赖）。
//
// 与 Kotlin 侧 StandardTextKernel.PureKotlin 的语义对齐说明：
//  - EstimateTokens / DiffStatOf / FuzzyLocate 的公式与边界条件逐一镜像；
//  - 交叉验证测试（host 侧）对同一输入断言两 implementation 输出一致。
// ═══════════════════════════════════════════════════════════════════════════
#include "apex/codenative/code_kernel.h"

#include <cmath>
#include <cstdint>
#include <limits>
#include <string>
#include <unordered_map>
#include <vector>

namespace apex::codenative {
namespace {

// CJK / 全角区间判定（与 Kotlin 侧三个区间一致）。
inline bool IsCjk(char16_t ch) {
    const uint32_t code = static_cast<uint32_t>(ch);
    return (code >= 0x2E80 && code <= 0x9FFF) ||
           (code >= 0xFF00 && code <= 0xFFEF) ||
           (code >= 0x3000 && code <= 0x303F);
}

// FNV-1a 64（行哈希；行内容视作 UTF-16 码元序列）。
inline uint64_t HashLine(const char16_t* data, size_t length) {
    uint64_t hash = 1469598103934665603ULL;  // FNV offset basis
    for (size_t i = 0; i < length; ++i) {
        hash ^= static_cast<uint64_t>(static_cast<uint16_t>(data[i]));
        hash *= 1099511628211ULL;  // FNV prime
    }
    return hash;
}

// 行切分（\n；与 Kotlin lineSequence 的语义对齐：末尾无换行不产生空尾行）。
struct LinesView {
    // 指回输入缓冲的 (begin, length) 列表；输入自身保持存活。
    std::vector<const char16_t*> begins;
    std::vector<size_t> lengths;
};

LinesView SplitLines(const char16_t* data, size_t length) {
    LinesView out;
    size_t start = 0;
    for (size_t i = 0; i <= length; ++i) {
        if (i == length || data[i] == u'\n') {
            // 去行尾 \r（CRLF 容错，与 Kotlin lineSequence 一致）
            size_t end = i;
            if (end > start && data[end - 1] == u'\r') --end;
            out.begins.push_back(data + start);
            out.lengths.push_back(end - start);
            start = i + 1;
        }
    }
    return out;
}

}  // namespace

int32_t EstimateTokens(const Utf16View& text) {
    if (text.length == 0) return 0;
    size_t cjk = 0;
    size_t other = 0;
    for (size_t i = 0; i < text.length; ++i) {
        if (IsCjk(text.data[i])) {
            ++cjk;
        } else {
            ++other;
        }
    }
    // Kotlin: (other / 4) + ((cjk * 3) / 2)   ——整型口径镜像
    return static_cast<int32_t>(other / 4 + (cjk * 3) / 2);
}

DiffStat DiffStatOf(const Utf16View& old_text, const Utf16View& new_text) {
    LinesView old_lines = SplitLines(old_text.data, old_text.length);
    LinesView new_lines = SplitLines(new_text.data, new_text.length);

    std::unordered_map<uint64_t, int64_t> counts;
    counts.reserve(old_lines.begins.size() + new_lines.begins.size());

    for (size_t i = 0; i < old_lines.begins.size(); ++i) {
        const uint64_t h = HashLine(old_lines.begins[i], old_lines.lengths[i]);
        --counts[h];
    }
    for (size_t i = 0; i < new_lines.begins.size(); ++i) {
        const uint64_t h = HashLine(new_lines.begins[i], new_lines.lengths[i]);
        ++counts[h];
    }

    int32_t removed = 0;
    int32_t added = 0;
    for (const auto& kv : counts) {
        if (kv.second > 0) {
            added += static_cast<int32_t>(kv.second);
        } else if (kv.second < 0) {
            removed += static_cast<int32_t>(-kv.second);
        }
    }
    return DiffStat{added, removed};
}

FuzzyMatch FuzzyLocate(const Utf16View& haystack, const Utf16View& needle) {
    if (needle.length == 0 || haystack.length == 0) {
        return FuzzyMatch{false, -1, 0.0f};
    }

    // 精确子串 → 满分。
    for (size_t start = 0; start + needle.length <= haystack.length; ++start) {
        bool equal = true;
        for (size_t j = 0; j < needle.length; ++j) {
            if (haystack.data[start + j] != needle.data[j]) {
                equal = false;
                break;
            }
        }
        if (equal) {
            return FuzzyMatch{true, static_cast<int32_t>(start), 1.0f};
        }
    }

    // 滑窗 + 贪心子序列评分（与 Kotlin PureKotlin.fuzzyLocate 同式）。
    const size_t window_raw = (needle.length * 3) / 2;
    const size_t window = window_raw < 8 ? 8 : window_raw;
    const size_t step_raw = window / 2;
    const size_t step = step_raw < 1 ? 1 : step_raw;

    int32_t best_index = -1;
    float best_score = 0.0f;

    for (size_t i = 0; i < haystack.length; i += step) {
        const size_t end = (i + window) < haystack.length ? (i + window) : haystack.length;
        float score = 0.0f;
        size_t ni = 0;
        for (size_t j = i; j < end; ++j) {
            if (ni < needle.length && haystack.data[j] == needle.data[ni]) {
                score += 1.0f / static_cast<float>(needle.length);
                ++ni;
            }
        }
        if (ni == needle.length) {
            const size_t consumed = (end - i) < 1 ? 1 : (end - i);
            score *= static_cast<float>(needle.length) / static_cast<float>(consumed);
        }
        if (score > best_score) {
            best_score = score;
            best_index = static_cast<int32_t>(i);
        }
        if (best_score >= 0.999f) break;
    }

    if (best_index < 0 || best_score < 0.6f) {
        return FuzzyMatch{false, -1, 0.0f};
    }
    const float capped = best_score > 1.0f ? 1.0f : best_score;
    return FuzzyMatch{true, best_index, capped};
}

}  // namespace apex::codenative
