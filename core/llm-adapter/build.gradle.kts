plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core:logging"))
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)
    implementation(libs.okhttp)

    // T72 — first test dependencies in this module (registry/router/validator/error tests).
    // Pure JVM, JUnit4 + kotlinx-coroutines-test, mirroring :core:agent-engine.
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    // diag-202611（422 unknown variant 复盘）：server-side 搜索 tool 降级重发
    // 回归需要真实 HTTP 层（StreamingOpenAiClient 对 OkHttpClient 的请求头
    // 阶段失败/重发），MockWebServer 与 :core:tool-registry 同款（仅 testImplementation）。
    testImplementation(libs.mockwebserver)
}
