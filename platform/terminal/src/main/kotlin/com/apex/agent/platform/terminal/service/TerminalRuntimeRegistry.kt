package com.apex.agent.platform.terminal.service

import com.apex.agent.platform.terminal.runtime.TerminalRuntime

/**
 * T91（D2-D4）：TerminalRuntime 进程内注册表 —— TerminalService 与宿主 DI 之间的
 * 安装点。
 *
 * ## 为什么是注册表而不是 @AndroidEntryPoint
 *
 * platform:terminal 是 Android Library 模块，此前**零 Hilt 注解使用**（构建脚本
 * 预留了插件但无一处消费）。给库模块引入第一个 @AndroidEntryPoint 属于未经验证
 * 的构建路径（KSP 聚合 / 库模块生成的 super-component 接线），而本 PR 的验证
 * 预算在「契约 + 控制器 + 测试」上。注册表模式：
 *  - 宿主（ApexApp）在 Application.onCreate 里 install 一次（Hilt 单例创建时）；
 *  - TerminalService.onCreate 从这里取 —— 同进程必然已初始化（任何 Service
 *    的 onCreate 都晚于 Application.onCreate）；
 *  - 纯 JVM、可单测、零构建期代码生成风险。
 *
 * 与 AGENTS.md「能力为全模式共享单例，新模式接入走注入而非复制」同构 ——
 * 运行时仍只有一个（TerminalModule 的 @Singleton），注册表只是把它暴露给
 * 无 DI 上下文的 Service 壳。
 *
 * ## 多进程路线（诚实记录）
 *
 * AIDL 契约已为 `android:process=":terminal"` 预留（所有载荷都是 parcelable
 * 原语）。但**本 PR 不启用多进程**：agent 工具 / TerminalViewModel /
 * EnvironmentProvisioner 仍进程内直连 runtime，多进程会导致两个 runtime
 * 实例（主进程 + :terminal 进程）状态分裂。等全部消费者迁移到 AIDL 之后再
 * 翻转 manifest（见 docs/terminal/TERMINAL_IPC_SERVICE_T91.md 路线图）。
 */
object TerminalRuntimeRegistry {

    @Volatile
    private var runtime: TerminalRuntime? = null

    /** 宿主 Application 启动时安装（幂等 —— 后装覆盖前装，供测试重建场景）。 */
    fun install(runtime: TerminalRuntime) {
        this.runtime = runtime
    }

    /** 当前 runtime（null = 宿主尚未安装 —— Service 据此返回 RUNTIME_NOT_INSTALLED）。 */
    fun get(): TerminalRuntime? = runtime

    /** 测试隔离：卸载（生产代码不调用）。 */
    fun reset() {
        runtime = null
    }
}
