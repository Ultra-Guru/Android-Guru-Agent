// ═══════════════════════════════════════════════════════════════════════════
// apex::codenative —— host 侧单元测试（算法层与 Kotlin PureKotlin 交叉验证）。
//
// 构建方式（host，不进 Android 产物）：
//   cmake -DAPEX_CODE_BUILD_TESTS=ON .. && cmake --build . && ctest
// 值语义与 Kotlin 侧 StandardTextKernelTest 一一对应（同名用例）。
// ═══════════════════════════════════════════════════════════════════════════
#include <cstdio>
#include <string>

#include "apex/codenative/code_kernel.h"

namespace {

using apex::codenative::DiffStatOf;
using apex::codenative::EstimateTokens;
using apex::codenative::FuzzyLocate;
using apex::codenative::Utf16View;

Utf16View View(const std::u16string& s) {
    return Utf16View{s.data(), s.size()};
}

int g_failures = 0;

#define CHECK(cond)                                              \
    do {                                                         \
        if (!(cond)) {                                           \
            std::printf("FAIL %s:%d  %s\n", __FILE__, __LINE__, #cond); \
            ++g_failures;                                        \
        }                                                        \
    } while (0)

void TestEstimateTokens() {
    CHECK(EstimateTokens(View(u"")) == 0);
    CHECK(EstimateTokens(View(u"abcd")) == 1);
    CHECK(EstimateTokens(View(u"abcdefgh")) == 2);
    // 4 个 CJK → 4*3/2 = 6（与 Kotlin defaultTokenEstimator 同式）
    CHECK(EstimateTokens(View(u"中文计数")) == 6);
}

void TestDiffStat() {
    const auto same = DiffStatOf(View(u"a\nb\nc"), View(u"a\nb\nc"));
    CHECK(same.added_lines == 0 && same.removed_lines == 0);

    const auto append = DiffStatOf(View(u"a\nb"), View(u"a\nb\nc\nd"));
    CHECK(append.added_lines == 2 && append.removed_lines == 0);

    const auto removal = DiffStatOf(View(u"a\nb\nc"), View(u"a"));
    CHECK(removal.added_lines == 0 && removal.removed_lines == 2);

    // 行内改写 = 一增一删
    const auto rewrite = DiffStatOf(View(u"old line\nkeep"), View(u"new line\nkeep"));
    CHECK(rewrite.added_lines == 1 && rewrite.removed_lines == 1);

    // 空串按一行空行计（Kotlin lines() 同口径）
    const auto empty = DiffStatOf(View(u""), View(u"a\nb"));
    CHECK(empty.added_lines == 2 && empty.removed_lines == 1);
}

void TestFuzzyLocate() {
    const auto exact = FuzzyLocate(View(u"fun main() { println(it) }"), View(u"println"));
    CHECK(exact.matched && exact.score == 1.0f);
    CHECK(exact.index == 13);

    const auto none = FuzzyLocate(View(u"abcdef"), View(u"zzzzzzzzzz"));
    CHECK(!none.matched);

    const auto drift = FuzzyLocate(View(u"fun  main ( ) { }"), View(u"fun main()"));
    CHECK(drift.matched && drift.score >= 0.6f);

    CHECK(!FuzzyLocate(View(u""), View(u"abc")).matched);
    CHECK(!FuzzyLocate(View(u"abc"), View(u"")).matched);
}

}  // namespace

int main() {
    TestEstimateTokens();
    TestDiffStat();
    TestFuzzyLocate();
    if (g_failures == 0) {
        std::printf("code_native host tests: ALL PASS\n");
        return 0;
    }
    std::printf("code_native host tests: %d failure(s)\n", g_failures);
    return 1;
}
