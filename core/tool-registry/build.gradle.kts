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
    implementation(project(":core:llm-adapter"))
    // #341：SsrfGuard 的 DNS 解析失败放行需要 AppLogger warn 留痕
    //（core:logging 纯 JVM，无 Android 依赖）。
    implementation(project(":core:logging"))
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)
    implementation(libs.okhttp)  // WebFetchTool / WebSearchTool / HttpRequestTool

    // Unit testing (pure-JVM src/test)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    // v5 测试下载/安装工具链所需（AppDownloadInstallToolTest）
    testImplementation(libs.mockwebserver)
}
// CI 诊断：测试失败输出完整异常（message + stack）—— 本仓无 JDK 本地验证，
// CI 日志是唯一失败原因来源（默认单行 class+line 不足以定位）。
tasks.withType<Test>().configureEach {
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showExceptions = true
        showCauses = true
        showStackTraces = true
    }
}
