package com.apex.agent.plugin.host.functional

import com.apex.agent.plugin.api.PluginContract
import java.io.File
import kotlinx.serialization.json.Json

/**
 * # 功能性插件安装目录管理（Plugin System v2）
 *
 * 功能性插件（[com.apex.agent.plugin.api.PluginKind.FUNCTIONAL]）的持久化
 * 载体是文件系统上的一个目录树：
 *
 * `filesDir/plugins/functional/<id>/plugin.json`（manifest）
 * `filesDir/plugins/functional/<id>/.disabled`（禁用标记，存在即禁用）
 *
 * 职责（对齐 [com.apex.agent.core.tools.skill.SkillRegistry] 的纪律）：
 * - **安装**：manifest 强校验（id 命名规范 / 宿主命名空间保留 / 版本门 /
 *   贡献点形状）→ 原子写（tmp + renameTo，rename 失败直写目标兜底——
 *   AGENTS.md 原子写纪律）。同 id 版本比较：仅新 version 更高才覆盖
 *   （升级语义，对齐技能 installBundled）；用户启停态（.disabled 标记）
 *   跨升级保留；
 * - **枚举**：目录扫描逐个解析——单条损坏折成 [StoredPlugin.brokenReason]
 *   （BROKEN 隔离，市场卡片如实展示），绝不向上抛；
 * - **启停**：`.disabled` 标记的创建 / 删除（幂等）；
 * - **卸载**：递归删除目录（canonical path 防穿越双重校验）。
 *
 * 本类纯 JVM（构造参数全是 File / String），无 Android 依赖——同
 * WorkflowStore 一样可直接单测；日志经注入式出口（app 层桥接 AppLogger）。
 *
 * @param rootDir 功能性插件安装根目录（filesDir 之下的 plugins/functional）
 * @param logger 结构化日志出口（INFO / WARN 两档），null = 静默
 */
class FunctionalPluginStore(
    private val rootDir: File,
    private val logger: ((level: Level, message: String) -> Unit)? = null
) {

    /** 注入式日志档位（对齐 SkillHotReloadLogLevel 的两档口径）。 */
    enum class Level { INFO, WARN }

    /**
     * 目录里的一个已安装功能性插件（manifest 解析成功与否都如实呈现）：
     * - [manifest] 非 null：可用（enabled 决定是否激活）；
     * - null + [brokenReason]：BROKEN——manifest 损坏 / 形态非法，市场卡片
     *   展示原因并引导卸载重装，宿主不做任何猜测性加载。
     */
    data class StoredPlugin(
        val manifest: PluginManifest?,
        val dirName: String,
        val enabled: Boolean,
        val brokenReason: String? = null,
        val installedAtMillis: Long
    ) {
        /** 展示名（BROKEN 回退目录名）。 */
        val displayName: String get() = manifest?.displayName ?: dirName

        /** 可参与激活的完整 manifest（BROKEN / 禁用态为 null）。 */
        val activatableManifest: PluginManifest?
            get() = if (manifest != null && enabled && brokenReason == null) manifest else null
    }

    /** 安装结果（fail closed：一切拒绝都有人话理由）。 */
    sealed class InstallResult {
        /** 全新安装成功。 */
        data class Installed(val manifest: PluginManifest) : InstallResult()

        /** 升级成功（旧 version → 新 version，启停态保留）。 */
        data class Upgraded(val manifest: PluginManifest, val fromVersion: Int) : InstallResult()

        /** 已有同版本或更高版本——不覆盖（幂等重装 / 降级拒绝）。 */
        data class Unchanged(val manifest: PluginManifest) : InstallResult()

        /** 拒绝安装（manifest 不可解析 / 命名规范 / 版本门 / 贡献点形状）。 */
        data class Rejected(val reason: String) : InstallResult()
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ── 枚举 ─────────────────────────────────────────────────────────

    /**
     * 扫描安装目录枚举全部插件（含 BROKEN）。IO 异常折叠为空列表 +
     * WARN（目录不存在的首启空态是合法状态，不制造噪声）。
     */
    fun list(): List<StoredPlugin> = runCatching {
        val dirs = rootDir.listFiles { file -> file.isDirectory } ?: return emptyList()
        dirs.mapNotNull(::readStored).sortedBy { it.displayName.lowercase() }
    }.getOrElse {
        warn("list plugins failed: ${it.message}")
        emptyList()
    }

    /** 单插件 manifest 读取（激活 / 详情页的廉价路径）。 */
    fun manifestOf(id: String): PluginManifest? {
        val manifestFile = manifestFileOf(id)
        if (!manifestFile.isFile) return null
        return PluginManifest.parseOrNull(manifestFile.readText())
            ?: run {
                warn("manifest of '$id' not parseable")
                null
            }
    }

    /**
     * 单插件 manifest **原文**读取（FusionBundle 聚合内联 / 分享导出用）：
     * 直接返回磁盘文件内容——不经解析-重编码往返，零字段漂移；未安装返回 null。
     */
    fun rawManifestOf(id: String): String? {
        val manifestFile = manifestFileOf(id)
        if (!manifestFile.isFile) return null
        return runCatching { manifestFile.readText() }.getOrNull()
    }

    /** 插件安装目录（只读定位用 —— UI 入口的 canonical 复核在此目录内）。 */
    fun pluginDirOf(id: String): File = dirOf(id.trim())

    // ── 安装 / 升级 ───────────────────────────────────────────────────

    /**
     * 安装（或升级）一个功能性插件。
     *
     * 校验链（顺序即拒绝优先级，全部 fail closed）：
     * 1. JSON 可解析为 [PluginManifest]；
     * 2. id 命名规范（[PluginContract.FUNCTIONAL_ID_PATTERN]——防目录穿越
     *    与内置工具族伪装）；
     * 3. 宿主命名空间保留（`com.apex.agent.` 前缀——内置 / 官方分发预留，
     *    三方数据不允许占用）；
     * 4. 版本门（minHostVersion ≤ [PluginContract.HOST_API_VERSION]，与
     *    APK 插件同一道门）；
     * 5. 贡献点形状（工具宏步数 / 步内工具 id / 指令 id 命名与插件内唯一性）；
     * 6. 已存在同 id：version 更高才覆盖（[InstallResult.Upgraded]），否则
     *    [InstallResult.Unchanged]（.disabled 标记永不被安装动作触碰）。
     */
    fun install(manifestJson: String): InstallResult {
        val manifest = PluginManifest.parseOrNull(manifestJson)
            ?: return InstallResult.Rejected("manifest is not parseable JSON")

        validateIdentity(manifest)?.let { return InstallResult.Rejected(it) }
        validateContributions(manifest)?.let { return InstallResult.Rejected(it) }

        val existing = manifestOf(manifest.id)
        if (existing != null && existing.version >= manifest.version) {
            return InstallResult.Unchanged(existing)
        }

        val dir = dirOf(manifest.id)
        if (!dir.isDirectory && !dir.mkdirs()) {
            return InstallResult.Rejected("cannot create plugin directory: ${dir.path}")
        }

        val target = manifestFileOf(manifest.id)
        val tmp = File(dir, PluginContract.FUNCTIONAL_MANIFEST_FILE + ".tmp")
        val payload = json.encodeToString(PluginManifest.serializer(), manifest)
        val writeOk = runCatching {
            tmp.writeText(payload)
            // 原子写：tmp + renameTo；rename 失败直写目标兜底（AGENTS.md 纪律）。
            if (!tmp.renameTo(target)) {
                warn("atomic rename failed for ${manifest.id} — falling back to direct write")
                tmp.writeText(payload)
                target.writeText(payload)
                tmp.delete()
            }
        }.isSuccess
        if (!writeOk) {
            tmp.delete()
            return InstallResult.Rejected("manifest write failed for ${manifest.id}")
        }

        info(
            "functional plugin '${manifest.id}' v${manifest.displayVersion} installed " +
                "(tools=${manifest.contributions.tools.size}, slash=${manifest.contributions.slashCommands.size})"
        )
        return if (existing != null) {
            InstallResult.Upgraded(manifest, existing.version)
        } else {
            InstallResult.Installed(manifest)
        }
    }

    // ── 启停 / 卸载 ───────────────────────────────────────────────────

    /**
     * 启停切换：`.disabled` 标记的创建（false）/ 删除（true）。幂等——
     * 重复开关无副作用（标记已存在时禁用 = 成功，标记不存在时启用 = 成功）；
     * 未知 id 返回 false（调用方如实反馈）。
     */
    fun setEnabled(id: String, enabled: Boolean): Boolean {
        val dir = dirOf(id)
        if (!dir.isDirectory) return false
        val marker = File(dir, PluginContract.FUNCTIONAL_DISABLED_MARKER)
        return runCatching {
            if (enabled) {
                marker.delete() || !marker.exists()
            } else {
                marker.exists() || marker.createNewFile()
            }
        }.getOrDefault(false)
    }

    /**
     * 卸载：递归删除插件目录。双重防护：id 参数先做形状门（拒绝路径分隔
     * 符与点段——`..` 穿越在上游就拦），再校验 canonical path 为 root 的
     * **直接子目录**（解析后等于 root 本身或逃逸 root 的拒绝）；删除失败
     * 如实返回 false。
     */
    fun uninstall(id: String): Boolean {
        if (id.isEmpty() || id == "." || id == ".." || id.contains('/') || id.contains('\\')) {
            warn("uninstall rejected — invalid plugin directory name: $id")
            return false
        }
        val dir = dirOf(id)
        if (!dir.isDirectory) return false
        val rootCanonical = rootDir.canonicalPath + File.separator
        val dirCanonical = dir.canonicalPath + File.separator
        if (dirCanonical == rootCanonical || !dirCanonical.startsWith(rootCanonical)) {
            warn("uninstall rejected — path escapes plugin root: $id")
            return false
        }
        return runCatching { dir.deleteRecursively() }
            .also { if (it.getOrDefault(false)) info("functional plugin '$id' uninstalled") }
            .getOrDefault(false)
    }

    // ── 私有实现 ──────────────────────────────────────────────────────

    private fun readStored(dir: File): StoredPlugin? {
        val manifestFile = File(dir, PluginContract.FUNCTIONAL_MANIFEST_FILE)
        val installedAt = runCatching { manifestFile.lastModified() }.getOrDefault(0L)
        val disabled = File(dir, PluginContract.FUNCTIONAL_DISABLED_MARKER).exists()
        if (!manifestFile.isFile) {
            // 半截目录（安装中断 / 手动删文件）：如实 BROKEN，不猜测加载。
            return StoredPlugin(
                manifest = null,
                dirName = dir.name,
                enabled = !disabled,
                brokenReason = "manifest 文件缺失（目录半截或已损坏）",
                installedAtMillis = installedAt
            )
        }
        val manifest = runCatching { PluginManifest.parseOrNull(manifestFile.readText()) }
            .getOrNull()
        return if (manifest == null) {
            StoredPlugin(
                manifest = null,
                dirName = dir.name,
                enabled = !disabled,
                brokenReason = "manifest 无法解析（JSON 损坏）",
                installedAtMillis = installedAt
            )
        } else {
            // 安装期已过校验门；运行期重读（热更 / 手改文件）仍做身份复核，
            // 不合规即 BROKEN 隔离——磁盘上的数据可能已经不在宿主的信任假设内。
            val violation = validateIdentity(manifest) ?: validateContributions(manifest)
            StoredPlugin(
                manifest = manifest,
                dirName = dir.name,
                enabled = !disabled,
                brokenReason = violation,
                installedAtMillis = installedAt
            )
        }
    }

    /** 身份校验（id 规范 / 宿主命名空间 / 版本门）。null = 通过。 */
    private fun validateIdentity(manifest: PluginManifest): String? {
        val id = manifest.id.trim()
        if (id.isEmpty()) return "id is empty"
        if (!PluginContract.FUNCTIONAL_ID_PATTERN.matches(id)) {
            return "id '$id' violates naming rule（小写字母数字 + 点分 + 中划线，至少两段）"
        }
        if (id.startsWith(HOST_NAMESPACE_PREFIX)) {
            return "id '$id' occupies the reserved host namespace（com.apex.agent 前缀为内置保留）"
        }
        if (manifest.minHostVersion > PluginContract.HOST_API_VERSION) {
            return "minHostVersion ${manifest.minHostVersion} > host ${PluginContract.HOST_API_VERSION}（请升级宿主）"
        }
        return null
    }

    /** 贡献点形状校验。null = 通过。 */
    private fun validateContributions(manifest: PluginManifest): String? {
        val contributions = manifest.contributions
        // 工具宏：id 形状 + 插件内唯一 + 步数界 + 步内工具 id 非空。
        val toolIds = contributions.tools.map { it.id.trim() }
        if (toolIds.any { it.isEmpty() }) return "tool contribution has empty id"
        if (toolIds.distinct().size != toolIds.size) return "duplicate tool ids inside plugin"
        if (toolIds.any { id -> !TOOL_ID_PATTERN.matches(id) }) {
            return "tool id 违反命名规范（小写字母数字 + 点/斜杠/下划线/中划线）"
        }
        contributions.tools.forEach { tool ->
            if (tool.steps.isEmpty()) return "tool '${tool.id}' has no steps"
            if (tool.steps.size > PluginContract.MACRO_MAX_STEPS) {
                return "tool '${tool.id}' exceeds ${PluginContract.MACRO_MAX_STEPS} steps"
            }
            if (tool.steps.any { step -> step.tool.trim().isEmpty() }) {
                return "tool '${tool.id}' has a step with empty tool id"
            }
        }
        // 斜杠指令：id 形状 + 插件内唯一（/plugin:<id> 路由段）。
        val commandIds = contributions.slashCommands.map { it.id.trim() }
        if (commandIds.any { it.isEmpty() }) return "slash command contribution has empty id"
        if (commandIds.distinct().size != commandIds.size) return "duplicate slash command ids inside plugin"
        if (commandIds.any { id -> !COMMAND_ID_PATTERN.matches(id) }) {
            return "slash command id 违反命名规范（小写字母数字 + 中划线）"
        }
        // UI 页面贡献：entry 非空 + 插件内唯一 + 路径安全（禁绝对路径与
        // `..` 逃逸 —— WebView file:// 入口不得越出插件目录）。
        val uiEntries = contributions.ui.map { it.entry.trim() }
        if (uiEntries.any { it.isEmpty() }) return "ui contribution has empty entry"
        if (uiEntries.distinct().size != uiEntries.size) return "duplicate ui entries inside plugin"
        if (uiEntries.any { entry ->
                entry.startsWith("/") || entry.startsWith("\\") ||
                    entry.split('/', '\\').any { it == ".." }
            }
        ) {
            return "ui entry 违反路径规范（必须是插件目录内的相对路径，禁 `..` 与绝对路径）"
        }
        return null
    }

    private fun dirOf(id: String): File = File(rootDir, id.trim())

    private fun manifestFileOf(id: String): File =
        File(dirOf(id), PluginContract.FUNCTIONAL_MANIFEST_FILE)

    private fun info(message: String) = logger?.invoke(Level.INFO, message)

    private fun warn(message: String) = logger?.invoke(Level.WARN, message)

    private companion object {
        /** 宿主 / 官方分发保留的 id 前缀（三方功能性插件不得占用）。 */
        const val HOST_NAMESPACE_PREFIX = "com.apex.agent."

        /** 工具贡献 id：小写字母数字 + 点 / 斜杠 / 下划线 / 中划线（覆盖 host 现实工具族形态）。 */
        val TOOL_ID_PATTERN: Regex = Regex("^[a-z0-9][a-z0-9._/-]*$")

        /** 斜杠指令 id：小写字母数字与中划线（/plugin:briefing 形态）。 */
        val COMMAND_ID_PATTERN: Regex = Regex("^[a-z0-9][a-z0-9-]*$")
    }
}
