package com.apex.agent.core.tools.extension

/**
 * # ExtensionAdapter —— 各生态安装语义的统一翻译接口
 *
 * 每个生态只实现一个适配器，把统一 spec 翻译成该生态的原生动作：
 * resolve（认路，抓 manifest）→ inspect（看清，产出 [InspectReport]）→
 * install（装，落原生注册表）→ uninstall（卸）。
 *
 * 安全契约（brief 明确要求，实现方必须遵守）：
 * - 适配器内部只允许白名单动作（npm install 指定包、git clone 解析过的
 *   URL、写配置文件、复制文件到私有目录），**禁止把用户输入拼进 shell**；
 * - inspect 必须如实报告 postinstall / 生命周期脚本与权限声明；
 * - install 由 [ExtensionInstaller] 先过 hash 门与确认门，适配器不再重复设防。
 */
interface ExtensionAdapter {

    /** 本适配器承接的扩展类型（[ExtensionTypes] 常量集）。 */
    val supportedTypes: Set<String>

    /** 认路：spec 草稿 → 完整 manifest（含 resolved 原生 manifest 与指纹）。 */
    suspend fun resolve(draft: ExtensionSpecParser.SpecDraft): Result<ExtensionManifest>

    /** 看清：将安装什么、装到哪、要什么权限、有无脚本。 */
    suspend fun inspect(manifest: ExtensionManifest): Result<InspectReport>

    /** 装：落原生注册表。调用方保证 manifest 与 hash 均已核验。 */
    suspend fun install(manifest: ExtensionManifest): Result<InstallOutcome>

    /** 卸：从原生注册表移除（账本由 [ExtensionInstaller] 统一清理）。 */
    suspend fun uninstall(manifest: ExtensionManifest): Result<Unit>
}

/**
 * 适配器注册表：type → adapter 路由。未注册类型的 resolve 在编排层
 * 诚实报「适配器未注册」（而不是静默走错适配器）。
 */
class ExtensionAdapterRegistry(adapters: List<ExtensionAdapter>) {

    private val byType: Map<String, ExtensionAdapter> =
        adapters.flatMap { a -> a.supportedTypes.map { it to a } }.toMap()

    fun forType(type: String): ExtensionAdapter? = byType[type]

    /** 当前可用的类型面（doctor / resolve 引导文案用）。 */
    fun availableTypes(): Set<String> = byType.keys
}
