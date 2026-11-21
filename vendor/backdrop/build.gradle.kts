import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.kyant.backdrop"
    // 36 对齐宿主 app（PR #362 矩阵：Compose 1.10.3 线的 aar-metadata 门槛）。
    // vendored 源码（上游 1.0.0 血统 + diag-20261011 热修 + PR #364 彩色
    // DefaultHighlight）已在该 BOM 下经 GitHub Actions 全链路编译验证。
    compileSdk = 36

    defaultConfig {
        // 上游 backdrop 1.0.0 支持到 API 21；宿主 app minSdk 26 —— 取交集上游值，
        // 库自身无更低要求，保留 21 与上游 AAR 声明一致（Gradle 解析时取 max(21,26)=26）。
        minSdk = 21
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// KGP 2.2：kotlinOptions DSL 已 deprecation（KGP 3.0 移除）—— 迁移到
// 项目级 compilerOptions DSL（语义等价：等价于对全部 Kotlin 编译任务生效）。
// vendored 模块随 PR #362 的 13 模块 DSL 迁移补齐为第 14 个。
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // 对齐宿主 app 的 Compose BOM（2026.02.00 = Compose 1.10.3）—— 上游 1.0.0
    // 血统源码的 API 需求（GraphicsLayer / RenderEffect / Modifier.Node /
    // RuntimeShader 桥接等）在 1.10.3 下向后兼容（Compose 二进制兼容承诺 +
    // PR #362 CI 全量编译实证）。
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
}
