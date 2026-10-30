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
    // AgentTool / ToolMetadata / SafeAgentTool 契约（工具系统 v2）—— 与
    // core:code-tools 同款接线：引擎只依赖工具契约与结构化日志，不反向
    // 依赖任何平台模块（端口由 platform:jiguan-host 注入）。
    implementation(project(":core:tool-registry"))
    implementation(project(":core:logging"))

    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)

    // 单测（纯 JVM）：JUnit4 + kotlinx-coroutines-test（runTest）—— 手写
    // fake 端口驱动域逻辑，无 mock 框架（版本目录不提供，仓库纪律）。
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
