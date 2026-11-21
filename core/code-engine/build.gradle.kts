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
    // 引擎契约（ApexAgentEngine / AgentConfig / ConversationMemory）+ 工具注册表
    implementation(project(":core:agent-engine"))
    implementation(project(":core:tool-registry"))
    implementation(project(":core:llm-adapter"))
    implementation(project(":core:logging"))
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)

    // 标准任务循环库模块（com.apex.standardkit:standard-loop —— Ultra-Guru/apex-kit-standard）：
    // composite build（settings.gradle.kts 条件 includeBuild 兄弟/嵌套目录）按坐标
    // 自动替换为本地构建；库仓缺席时走远端 Maven 坐标（后续发布启用）。
    // 端口-适配器接线见 standard/StandardKitAdapter.kt。
    api("com.apex.standardkit:standard-loop:1.1.0")

    // 织造循环库模块（com.apex.loomkit:loom-weave —— Ultra-Guru/apex-kit-loom）：
    // 同款拆库纪律（in-band 标记协议 / 梭回合机 / 权限三态门 / 子线程群 /
    // 织造包 / digest 压缩——全部循环语义在库仓演进）。composite build 按
    // 坐标自动替换（gradle/loom-kit.lock 锁定版本）；端口-适配器接线见
    // loom/LoomKitAdapter.kt。
    api("com.apex.loomkit:loom-weave:1.0.0")
    api("com.apex.courierkit:courier-dispatch:1.0.0")

    // Unit testing (pure-JVM src/test)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
