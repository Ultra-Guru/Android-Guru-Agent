plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.apex.agent.platform.browser"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // AgentTool / StreamingAgentTool 契约（browser_* 工具的宿主侧协议）
    api(project(":core:tool-registry"))

    // apex-browser-kit 三层以 api 传递给 app：app 仍直接消费
    // BrowserEngine / BrowserTracer / BrowserOverlay 等类型（ApexCoreService /
    // CyberNeonBallManager / BrowserScreen），不重复声明坐标。
    api(libs.browser.core)
    api(libs.browser.engine)
    api(libs.browser.chrome)

    implementation(libs.serialization.json)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
}
