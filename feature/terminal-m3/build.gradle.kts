import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.apex.agent.feature.terminalm3"
    // 36 对齐宿主 app（Compose 1.10.3 线 aar-metadata 门槛，PR #362 矩阵）。
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// KGP 2.2+：kotlinOptions DSL 已是 error（KGP 3.0 移除）—— 对齐仓库其余模块，
// 用项目级 compilerOptions DSL（语义等价：对全部 Kotlin 编译任务生效）。
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // ── Material 3 Terminal UI（feature/terminal-m3）──────────────────────
    // 自包含 Compose 库模块：零 Hilt / 零 DataStore / 零跨模块依赖 ——
    // 只有 ViewModel（mutableStateOf + mutableStateListOf 单源状态）+
    // Screen（TopAppBar / OutlinedTextField / 终端 Card）+ Theme（恒暗
    // GitHub Dark 配色），app 经 DrawerDestination.TerminalM3 一个 composable
    // 接入，不反向依赖宿主任何类型。
    //
    // 对齐宿主 app 的 Compose BOM（2026.02.00 = Compose 1.10.3）。
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    // OutlinedTextField(prefix/trailingIcon) / Card / TopAppBar /
    // FilledIconButton / SelectionContainer 等 M3 组件。
    implementation(libs.compose.material3)
    // Icons.AutoMirrored.Filled.Send（发送钮）与 Icons.AutoMirrored.Filled.ArrowBack
    //（返回钮）—— 宿主 app 已依赖 extended 集，此处声明仅为模块独立可编译。
    implementation(libs.compose.material.icons)
}
