import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.apex.agent.platform.jiguan"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // 宿主层无单测：全部可测逻辑（解析/脚本/扫描/重组）住在
        // core:jiguan-engine 纯 JVM 层。保持 returnDefaultValues 以防
        // 未来新增宿主单测时类加载安全通过。
        unitTests.isReturnDefaultValues = true
    }
}

// KGP 2.2：kotlinOptions DSL 已 deprecation —— 项目级 compilerOptions DSL
// （与 :platform:memory-host 同款接线）。
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // 机关引擎（纯 Kotlin：域模型/端口/扫描/脚本库/知识）。
    implementation(project(":core:jiguan-engine"))
    implementation(project(":core:tool-registry"))
    implementation(project(":core:logging"))

    // 权限面（Root ⊃ Shizuku ∪ Accessibility —— input 注入 / shell 执行）。
    implementation(project(":platform:privilege"))

    // 终端执行链（ExecEngine —— PRoot 优先 / 宿主 shell 回落的一次性命令）。
    implementation(project(":platform:terminal"))

    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
}
