import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// 游乐场 APK 插件模板 —— WebView 壳模块（拷贝后按需改名）
//
// 依赖宿主的 :plugin-sdk:plugin-api（AIDL 契约），产物是独立 APK。
// 本模板不参与主仓库构建（未列入 settings.gradle.kts）—— 见同目录
// README.md 的三步转化。构建形态对齐 plugins/plugin-workflow 参考实现。
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.apex.agent.plugin.game"
    compileSdk = 36

    defaultConfig {
        // 拷贝后改包名（如 com.apex.agent.plugin.game.mygame）—— 插件身份的一部分
        applicationId = "com.apex.agent.plugin.game"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// KGP 2.2：kotlinOptions DSL 已 deprecation —— 用项目级 compilerOptions DSL
// （与 plugins/plugin-workflow 同款写法）。
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // AIDL 契约层（IApexPlugin / IApexPluginHost / PluginContract）
    implementation(project(":plugin-sdk:plugin-api"))
}
