import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.apex.agent.platform.browser"
    // 35 → 36：随 PR #362 矩阵对齐宿主 app（Compose 1.10.3 线 aar-metadata 门槛）。
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// KGP 2.2+：kotlinOptions DSL 已是 error（KGP 3.0 移除）—— 随 PR #362
// 工具链升级（Kotlin 2.3.21）补齐迁移：项目级 compilerOptions DSL
//（语义等价：对全部 Kotlin 编译任务生效）。
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
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
