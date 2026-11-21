<!-- pr: native-text-kernel-jni-symbols -->

- 修复 libcode_native.so JNI 符号名错绑：导出符号曾以 CodeNativeBridge 结尾，而 Kotlin 声明类是 NativeTextKernel —— 编译期无感，运行期每次调用 UnsatisfiedLinkError 刷 E 级 logcat 后静默回退纯 Kotlin（加速核从未生效，还倒贴 JNI 查找 + 异常构造成本），现已按 JNI 静态绑定规则对齐，标准线文本加速核真正启用
- 同步修正 JNI 第二形参 jclass → jobject（external fun 为实例方法，jclass 是静态方法绑定的形参类型）
- 新增源级 JNI 符号契约单测（core:code-native 首个进 CI 的测试）：Kotlin external 声明 ↔ .cpp 导出符号双向核对 + 实例方法 jobject 形参校验，符号漂移在 CI 就地翻红，不再等真机 logcat 暴露
