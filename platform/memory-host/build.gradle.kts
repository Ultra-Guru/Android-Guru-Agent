import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.apex.agent.platform.memory"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // 宿主单测为纯 JVM：被测路径（记法解析 / 迁移映射）不触碰平台 API，
        // 打开 returnDefaultValues 使类加载安全通过，无需 Robolectric。
        unitTests.isReturnDefaultValues = true
    }
}

// KGP 2.2：kotlinOptions DSL 已 deprecation —— 项目级 compilerOptions DSL
// （与 :platform:cs-mem 前身同款接线）。
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":platform:privilege"))
    implementation(project(":core:tool-registry"))
    implementation(project(":core:logging"))
    implementation(project(":core:llm-adapter"))

    // 记忆库（apex-memory-kit）：composite build（settings.gradle.kts 条件
    // includeBuild 兄弟/嵌套目录）按坐标自动替换为本地构建；库仓缺席时走
    // 远端 Maven 坐标（E3 稳定期发布后启用）。
    api("com.apex.memkit:memory-core:1.0.0")

    // Coroutines
    implementation(libs.coroutines.android)

    // Serialization（迁移器读旧 JSON / 工具参数解析）
    implementation(libs.serialization.json)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // WorkManager（记忆维护周期调度，原 DreamRenderer 语义）
    implementation(libs.work.runtime)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    // Unit tests（迁移映射 / 记法解析 —— 纯 JVM）
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
