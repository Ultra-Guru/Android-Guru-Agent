package com.apex.agent.marketplace

import com.apex.agent.core.tools.mcp.McpManager
import com.apex.agent.core.tools.mcp.McpServerConfig
import com.apex.agent.core.tools.mcp.McpTransport
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/**
 * # 搜索 MCP 预设 + 本地安装器 —— 网络搜索「搜索 MCP」子通道的数据源
 *
 * 三台**零 API key** 的本地搜索 MCP 服务器（Coding 工位网络搜索菜单三选一），
 * 经 [ProotSandboxCommandRunner] 在内嵌 Ubuntu rootfs 里真实安装后以
 * stdio 配置接入 [McpManager]（`runInSandbox = true`，与市场 Registry 安装
 * 同一条沙箱执行通道）：
 *
 * - **free-search-mcp**（PyPI）：local-first 多引擎搜索（DuckDuckGo/Mojeek）+
 *   页面抓取 + 文档读取，LLM 优化输出；`pip install` 后 console script
 *   `free-search-mcp` 即服务器入口（uvx free-search-mcp 同名入口的本地
 *   装法，rootfs 无 uv/uvx，走 pip）；
 * - **Searchlight**（PyPI `searchlight-mcp`）：面向 AI 编码工具的多引擎
 *   搜索（Bing/百度/Yandex/Brave/DuckDuckGo 等 7 引擎原生 HTTP 抓取），
 *   `web_search` / `web_read` 工具 + 智能缓存与自动故障转移；入口
 *   `python3 -m searchlight`；
 * - **Agent Search MCP**（npm `agent-search-mcp`）：免费优先搜索路由器，
 *   中英文源（搜狗/百度等 8 个零密钥源）+ 紧凑输出省 Token；入口
 *   `npx -y agent-search-mcp`。
 *
 * 安装纪律对齐 [RegistryMcpInstaller]：真实下载先行（绕开 npx/pip 冷启动
 * 超时）→ 配置写入 → 失败回滚不留半残；rootfs 未装由 runner 的门禁给出
 * TerminalUbuntuInstall 引导文案。
 */
enum class SearchMcpPreset(
    /** McpManager 配置名（与 GitHub/内置等既有服务器命名空间并列）。 */
    val serverName: String,
    val kind: Kind,
    /** 沙箱内安装的包名（pip 包 / npm 包）。 */
    val packageName: String,
    /** stdio 启动命令（沙箱内解析）。 */
    val command: String,
    val args: List<String>
) {
    FREE_SEARCH(
        "free-search-mcp", Kind.PYPI, "free-search-mcp",
        command = "free-search-mcp", args = emptyList()
    ),
    SEARCHLIGHT(
        "searchlight", Kind.PYPI, "searchlight-mcp",
        command = "python3", args = listOf("-m", "searchlight")
    ),
    AGENT_SEARCH(
        "agent-search", Kind.NPM, "agent-search-mcp",
        command = "npx", args = listOf("-y", "agent-search-mcp")
    );

    enum class Kind { PYPI, NPM }

    /** stdio 沙箱配置（enabled 由调用方按通道选择语义给出）。 */
    fun toConfig(enabled: Boolean): McpServerConfig = McpServerConfig(
        name = serverName,
        transport = McpTransport.STDIO,
        enabled = enabled,
        command = command,
        args = args,
        env = emptyMap(),
        runInSandbox = true,
        scope = "all"
    )

    /** 沙箱安装命令（PEP 668 的 rootfs 系统 Python 需 --break-system-packages）。 */
    internal fun installCommand(): List<String> = when (kind) {
        Kind.PYPI -> listOf("pip", "install", "--break-system-packages", packageName)
        Kind.NPM -> listOf("npm", "install", "-g", packageName)
    }

    /** 安装失败/配置写入失败时的回滚命令。 */
    internal fun uninstallCommand(): List<String> = when (kind) {
        Kind.PYPI -> listOf("pip", "uninstall", "--break-system-packages", "-y", packageName)
        Kind.NPM -> listOf("npm", "uninstall", "-g", packageName)
    }

    /** 超时口径：与 RegistryMcpInstaller 的 npm 安装窗口一致（下载耗时真实存在）。 */
    internal companion object {
        const val INSTALL_TIMEOUT_MS = 5L * 60 * 1000
    }
}

/**
 * 搜索 MCP 本地安装器：通道菜单选中某台 → [ensureInstalled]（幂等）→
 * [setEnabled] 启用。安装失败如实抛 Result.failure（stderr 尾部透传）。
 */
@Singleton
class SearchMcpInstaller @Inject constructor(
    private val mcpManager: McpManager,
    private val sandboxRunner: ProotSandboxCommandRunner
) {

    /** 配置是否已存在（安装过 = 配置在案；沙箱包体随 rootfs 持久）。 */
    fun isInstalled(preset: SearchMcpPreset): Boolean =
        mcpManager.getConfigs().any { it.name == preset.serverName }

    /**
     * 确保预设已安装（幂等）：已配置直接成功；未配置先沙箱真实安装，
     * 成功写配置（enabled=false —— 启用是 [setEnabled] 的职责），配置写入
     * 失败回滚卸载（回滚结果如实并入文案）。
     */
    suspend fun ensureInstalled(preset: SearchMcpPreset): Result<Unit> {
        if (isInstalled(preset)) return Result.success(Unit)
        val install = sandboxRunner.run(preset.installCommand(), SearchMcpPreset.INSTALL_TIMEOUT_MS)
        if (!install.success) {
            return Result.failure(
                Exception(
                    "沙箱内 ${preset.kind} 安装失败（退出码 ${install.exitCode}）：" +
                        install.stderr.takeLast(400).trim().ifEmpty { "无 stderr 输出" }
                )
            )
        }
        val added = mcpManager.addServer(preset.toConfig(enabled = false))
        if (added.isFailure) {
            var rollbackConfirmed = false
            try {
                rollbackConfirmed = sandboxRunner.run(
                    preset.uninstallCommand(),
                    SearchMcpPreset.INSTALL_TIMEOUT_MS
                ).success
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 回滚尽力而为：失败不掩盖主错误，仅在文案里如实说明
            }
            return Result.failure(
                Exception(
                    "配置写入失败" +
                        (if (rollbackConfirmed) "（沙箱内包已卸载）" else "（沙箱回滚未确认，请到终端页检查）") +
                        "——${added.exceptionOrNull()?.message ?: ""}"
                )
            )
        }
        return Result.success(Unit)
    }

    /** 启用/停用一台（单选互斥的启停由通道菜单编排）。 */
    suspend fun setEnabled(preset: SearchMcpPreset, enabled: Boolean): Result<Unit> =
        mcpManager.setEnabled(preset.serverName, enabled)
}
