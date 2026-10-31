// ═══════════════════════════════════════════════════════════════════════════
// apex::codenative —— JNI 桥（libcode_native.so）
//
// 契约（与 NativeTextKernel.kt 约定）：
//  * 所有入口零异常抛出——失败路径返回回退值（0 / nullptr），
//    Kotlin 侧据此走纯 Kotlin 回退实现；
//  * 字符串经 GetStringCritical 零拷贝读取（短临界区，无 JNI 调用）；
//  * diffStat 返回 jintArray{added, removed}；fuzzyLocate 返回
//    jintArray{index, scorePermille(0..1000)} 或 nullptr（无匹配）。
// ═══════════════════════════════════════════════════════════════════════════
#include <jni.h>

#include "apex/codenative/code_kernel.h"

namespace {

using apex::codenative::DiffStat;
using apex::codenative::DiffStatOf;
using apex::codenative::EstimateTokens;
using apex::codenative::FuzzyLocate;
using apex::codenative::Utf16View;

// RAII 临界字符串守卫（GetStringCritical / ReleaseStringCritical 配对）。
// jchar 与 char16_t 同宽（16-bit）——经 reinterpret_cast 桥接（标准布局，
// C++17 下 char16_t 是独立类型但表示一致）。
class CriticalString {
public:
    explicit CriticalString(JNIEnv* env, jstring str) : env_(env), str_(str) {
        if (env != nullptr && str != nullptr) {
            ptr_ = reinterpret_cast<const char16_t*>(
                env->GetStringCritical(str, &is_copy_));
        }
    }
    ~CriticalString() {
        if (env_ != nullptr && str_ != nullptr && ptr_ != nullptr) {
            env_->ReleaseStringCritical(
                str_, reinterpret_cast<const jchar*>(ptr_));
        }
    }

    bool valid() const { return ptr_ != nullptr; }
    Utf16View view() const {
        if (!valid()) return Utf16View{nullptr, 0};
        return Utf16View{ptr_, static_cast<size_t>(env_->GetStringLength(str_))};
    }

    CriticalString(const CriticalString&) = delete;
    CriticalString& operator=(const CriticalString&) = delete;

private:
    JNIEnv* env_;
    jstring str_;
    const char16_t* ptr_ = nullptr;
    jboolean is_copy_ = JNI_FALSE;
};

}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_apex_agent_codenative_CodeNativeBridge_nativeEstimateTokens(
        JNIEnv* env, jclass /*clazz*/, jstring text) {
    if (env == nullptr || text == nullptr) return 0;
    CriticalString critical(env, text);
    if (!critical.valid()) return 0;
    return EstimateTokens(critical.view());
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_apex_agent_codenative_CodeNativeBridge_nativeDiffStat(
        JNIEnv* env, jclass /*clazz*/, jstring old_text, jstring new_text) {
    if (env == nullptr || old_text == nullptr || new_text == nullptr) return nullptr;
    try {
        CriticalString old_critical(env, old_text);
        CriticalString new_critical(env, new_text);
        if (!old_critical.valid() || !new_critical.valid()) return nullptr;

        const DiffStat stat = DiffStatOf(old_critical.view(), new_critical.view());
        jintArray out = env->NewIntArray(2);
        if (out == nullptr) return nullptr;  // OOM → 抛给调用方回退（已由 Kotlin 捕获）
        jint values[2] = {stat.added_lines, stat.removed_lines};
        env->SetIntArrayRegion(out, 0, 2, values);
        return out;
    } catch (...) {
        return nullptr;  // 未知异常：回退 Kotlin 实现
    }
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_apex_agent_codenative_CodeNativeBridge_nativeFuzzyLocate(
        JNIEnv* env, jclass /*clazz*/, jstring haystack, jstring needle) {
    if (env == nullptr || haystack == nullptr || needle == nullptr) return nullptr;
    try {
        CriticalString hay_critical(env, haystack);
        CriticalString needle_critical(env, needle);
        if (!hay_critical.valid() || !needle_critical.valid()) return nullptr;

        const auto match = FuzzyLocate(hay_critical.view(), needle_critical.view());
        if (!match.matched) return nullptr;

        jintArray out = env->NewIntArray(2);
        if (out == nullptr) return nullptr;
        const jint score_permille = static_cast<jint>(
            match.score < 0.0f ? 0.0f : (match.score > 1.0f ? 1000.0f : match.score * 1000.0f));
        jint values[2] = {match.index, score_permille};
        env->SetIntArrayRegion(out, 0, 2, values);
        return out;
    } catch (...) {
        return nullptr;
    }
}
