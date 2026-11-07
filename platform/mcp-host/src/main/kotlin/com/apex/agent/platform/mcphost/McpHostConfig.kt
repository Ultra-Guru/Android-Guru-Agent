package com.apex.agent.platform.mcphost

import kotlinx.serialization.Serializable
import java.security.SecureRandom

/**
 * # 逆向 MCP Host 配置
 *
 * 三重安全模型的第一层：**Token 鉴权 + 绑定面 + 工具暴露面收窄**。
 * 外部 AI（PC 端 Cline/Claude Desktop 等）能调用的工具面由本配置收窄：
 * - 绑定面（[allowLan]）：默认只绑 loopback（127.0.0.1）—— 局域网暴露
 *   （0.0.0.0）是显式开关且 UI 明示风险（S-04）；
 * - 分类白名单（[allowedCategories]）：只放行 `ToolCategory.name` 命中的分类；
 * - 工具黑名单（[blockedToolIds]）：分类放行后仍强制禁用的个别工具；
 * - 写/执行类工具默认不暴露（[exposeWriteTools]，S-04）：非只读工具
 *   （写文件 / git 提交 / 改状态）须显式放开；HIGH 风险工具在
 *   [McpHostBridge] 内**写死永禁**；
 * - `vault_*` 家族在 [McpHostBridge] 内**写死硬拦截**（安全红线，
 *   即使配置放开也拦截——金库密钥绝不经外部 AI 通道出入）。
 */
@Serializable
data class McpHostConfig(
    /** 总开关（false 时服务器不监听）。 */
    val enabled: Boolean = false,
    /** 监听端口。 */
    val port: Int = 8765,
    /**
     * S-04：是否监听全部网卡（0.0.0.0，局域网内 PC 可访问）。
     * 默认 false —— 只绑 loopback（127.0.0.1），局域网设备无法连接；
     * PC 端控制场景需显式开启（UI 带明文 HTTP 风险警示）。存量配置缺省
     * 该字段时按 false 解码（ignoreUnknownKeys 反向兼容：旧 JSON 无此键
     * → 回落默认值），已开启的存量用户升级后回到 loopback，需重新打开。
     */
    val allowLan: Boolean = false,
    /**
     * S-04：是否暴露写/执行类工具（非只读副作用工具 —— 写文件、git 提交、
     * 改状态等）。默认 false —— FILE 分类中的写/执行工具不再默认暴露，
     * tools/list 与 tools/call 同判定；只读工具不受影响。确需远程写操作
     * 的场景显式开启。
     */
    val exposeWriteTools: Boolean = false,
    /** Bearer Token：首启生成 32 位随机，可重置；空 = 拒绝所有外部请求。 */
    val token: String = "",
    /**
     * 分类白名单（`ToolCategory.name` 集合）。
     * 默认只读友好集；SHELL/TERMINAL/APP/UI/GITHUB/SECURITY 等高风险分类默认关。
     * `CONTEXT` 为上下文回顾类工具预留（该分类当前未落地，属前瞻性放行）。
     */
    val allowedCategories: List<String> = listOf(
        "UTILITY", "FILE", "WEB", "SYSTEM", "SENSOR", "MEMORY", "CONTEXT"
    ),
    /**
     * 工具黑名单：分类放行后仍强制禁用的工具 id（安全默认——
     * 剪贴板写入 / 内容分享等高敏副作用工具即使所在分类被放行也不暴露）。
     * `ShareContent` 为未来共享工具预留的防御性条目。
     */
    val blockedToolIds: List<String> = listOf(
        "VaultSave", "VaultPaste", "VaultDelete", "VaultList", "ShareContent",
        // v2.1.0：浏览器敏感四件套默认封禁（评审 B6）——即使把 BROWSER 加入分类
        // 白名单，这四个也不经外部 AI 通道出入；确需开放须从本清单显式移除。
        // 注意：这只是 MCP 入口防护之一，宿主执行层的 browser-sensitive 角色
        // （BrowserRolePolicy/RoleGate）才是全入口防线。
        "BrowserExecuteJs", "BrowserGetCookies", "BrowserFileUpload", "BrowserClearData",
    ),
    /** 每远程地址每分钟请求上限（令牌桶限速）。 */
    val rateLimitPerMinute: Int = 60
) {
    companion object {
        /** 端口合法范围（避开特权端口与 ephemeral 区间边缘）。 */
        const val MIN_PORT = 1024
        const val MAX_PORT = 65535

        private val random = SecureRandom()
        private const val TOKEN_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

        /** 生成 32 位随机 Token（SecureRandom，~190 bit 熵）。 */
        fun generateToken(): String {
            val chars = CharArray(32)
            for (i in chars.indices) {
                chars[i] = TOKEN_ALPHABET[random.nextInt(TOKEN_ALPHABET.length)]
            }
            return String(chars)
        }

        /** 端口校验 + 收敛（非法端口回落默认 8765）。 */
        fun sanitizePort(port: Int): Int =
            if (port in MIN_PORT..MAX_PORT) port else 8765

        /** 限速收敛（<=0 视为关闭限速的下限保护：至少 1/min，避免除零/永拒）。 */
        fun sanitizeRateLimit(rate: Int): Int = if (rate < 1) 1 else rate
    }

    /** 派生：规范化端口（配置篡改防御，读取路径统一过此校验）。 */
    val effectivePort: Int get() = sanitizePort(port)

    /** 派生：规范化限速。 */
    val effectiveRateLimitPerMinute: Int get() = sanitizeRateLimit(rateLimitPerMinute)

    /** 分类白名单快速判定（大小写不敏感）。 */
    fun isCategoryAllowed(categoryName: String): Boolean =
        allowedCategories.any { it.equals(categoryName, ignoreCase = true) }

    /** 工具黑名单判定。 */
    fun isToolBlocked(toolId: String): Boolean = toolId in blockedToolIds
}

/**
 * 配置持久化接口。app 层用 SharedPreferences + JSON 实现注入；
 * 纯 JVM 测试 / 平台模块用内存实现。
 */
interface McpHostConfigStore {
    fun load(): McpHostConfig
    fun save(config: McpHostConfig)
}

/** 内存实现（测试与无持久化环境用）。 */
class InMemoryMcpHostConfigStore(initial: McpHostConfig = McpHostConfig()) : McpHostConfigStore {
    @Volatile
    private var current: McpHostConfig = initial

    override fun load(): McpHostConfig = current

    override fun save(config: McpHostConfig) {
        current = config
    }
}
