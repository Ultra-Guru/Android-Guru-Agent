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
