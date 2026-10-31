package com.apex.agent.plugin.api

/**
 * # 插件类型（Plugin System v2）
 *
 * 仓库的插件分两种形态，共用同一套 id / 版本 / 信任门词汇，但**载体与
 * 信任边界完全不同**（详见 docs/plugin-system-v2.md）：
 *
 * - [FUNCTIONAL] **功能性插件**：进程内的**声明式数据**（一个 plugin.json
 *   manifest），不安装 APK、不含任何代码。类比 coding 平台（VS Code 类）
 *   的扩展：manifest 声明「贡献点」（工具宏 / 斜杠指令 / 提示词注入），
 *   宿主把贡献物适配进 ToolRegistry / 斜杠菜单。因为它只是数据，所有
 *   执行都落在宿主已有的工具上——**永不新增代码执行面**；
 * - [APK] **APK 插件**：独立安装的 APK（现状唯一形态，如
 *   :plugins:plugin-workflow）。进程外代码经 AIDL（[IApexPlugin]）提供
 *   工具清单与执行。代码在插件进程里跑，信任靠**同 keystore 签名门**。
 *
 * 设计约束（对照表，防两种形态的职责漂移）：
 *
 * | 维度 | FUNCTIONAL | APK |
 * | --- | --- | --- |
 * | 载体 | plugin.json 数据 | 独立 APK（代码） |
 * | 执行位置 | 宿主进程（宿主工具的组合） | 插件自己的进程 |
 * | 签名门 | 不适用（无代码可签名） | 必须（宿主同签名才注册工具） |
 * | 工具 id 冲突 | REJECT（数据不允许覆盖二进制能力） | REPLACE（同签名信任锚点） |
 * | 分发 | 市场下载 / 文件导入 / 热更通道 | 系统 PackageInstaller |
 * | 崩溃半径 | 解析失败 = 单插件隔离跳过 | 插件进程死亡 → 降级宿主直调 |
 */
enum class PluginKind {
    /** 功能性插件：进程内声明式贡献（无代码、无 APK）。 */
    FUNCTIONAL,

    /** APK 插件：独立 APK + AIDL 跨进程工具执行。 */
    APK;

    companion object {
        /**
         * 宽松解析：manifest 里 kind 缺省 / 拼写 / 大小写不敏感——功能插件
         * manifest 的 kind 字段本就是自描述性元数据（安装目录已经区分形态），
         * 未知值按 [FUNCTIONAL] 处理（fail-safe：数据永不按代码加载）。
         */
        fun fromRaw(raw: String?): PluginKind =
            if (raw?.trim()?.equals(APK.name, ignoreCase = true) == true) APK else FUNCTIONAL
    }
}
