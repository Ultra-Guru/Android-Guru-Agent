package com.apex.agent.core.tools.skill

import com.apex.agent.core.tools.ToolMetadata
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File

/**
 * Skill 定义（apex-skill-v1 manifest）
 *
 * 四种 Skill 类型：
 * 1. Composite — 由多个现有 Tool 组合而成（步骤序列）
 * 2. Prompt    — 不添加新 Tool，注入专用 System Prompt
 * 3. Script    — 包含可执行脚本（Python/Shell），通过 shell_execute 运行
 * 4. Connector — 连接外部服务（API/SSH/数据库）
 */
@Serializable
data class SkillManifest(
    val schema: String = "apex-skill-v1",
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val author: String = "",
    val license: String = "MIT",
    val dependencies: List<String> = emptyList(),  // 依赖的其他 Skill id（按安装顺序先于本 Skill 加载）
    val requirements: SkillRequirements = SkillRequirements(),
    val tools: List<SkillToolDef> = emptyList(),
    val configuration: SkillConfiguration = SkillConfiguration(),
    val promptInjection: String? = null,  // Prompt Skill专用
    // ── 市场元数据（v2 增强，全部向后兼容，默认值保证旧 manifest 正常加载）──
    /** 市场分类（镜像 ToolCategory 枚举名：SHELL/FILE/WEB/...）。null = 未分类。 */
    val category: String? = null,
    /** 自由标签，用于市场搜索/过滤。 */
    val tags: List<String> = emptyList(),
    /** 主页/仓库链接，市场详情页展示。 */
    val homepage: String? = null,
    val repository: String? = null,
    /** 信任级别：verified（官方/已签名）/ community（社区）/ untrusted（被标记）。默认 community。 */
    val trustLevel: String = "community",
    /**
     * Issue #166：内置预装标记——由 APK assets（assets/skills 目录下的 JSON 清单）
     * 首启幂等释放安装。
     * true 的技能：市场 UI 显示「内置」徽标、卸载入口降级为「可禁用不可卸载」提示，
     * [SkillRegistry.uninstall] 对其直接返回 false（详见该方法 KDoc）。
     * 默认 false 保证旧 manifest（无此字段）反序列化后仍可正常卸载，向后兼容。
     */
    val bundled: Boolean = false,
    /** Ed25519 签名 hex（对 manifest 规范化字节的签名）。null = 未签名。 */
    val signature: String? = null,
    /** 版本变更日志。 */
    val changelog: List<ChangelogEntry> = emptyList(),
    /**
     * #197 工位作用域（"agent" | "coding" | "all"）：
     * - agent：聊天/全能工位专属（聊天技能、人设增强）；
     * - coding：编码工位专属（提交信息、代码评审等开发技能）；
     * - all（默认）：两个工位都注入/可见 —— 旧 manifest 无此字段时保持
     *   既有行为，向后兼容。
     * 市场分级（Agent 市场 / Coding 市场）与斜杠菜单、Prompt 注入过滤同源。
     */
    val scope: String = "all"
)

/** 版本变更日志条目。 */
@Serializable
data class ChangelogEntry(
    val version: String,
    val date: String? = null,   // ISO-8601 字符串
    val notes: String = ""
)

@Serializable
data class SkillRequirements(
    val minAppVersion: Int = 1,
    val permissions: List<String> = emptyList(),
    val toolsRequired: List<String> = emptyList(),
    val privilegeLevel: String = "none"  // none, shizuku, root
)

@Serializable
data class SkillToolDef(
    val id: String,
    val name: String,
    val description: String,
    val parameters: String,  // JSON Schema string
    val implementation: SkillImplementation
)

@Serializable
data class SkillImplementation(
    val type: String,  // "composite", "script", "prompt", "connector"
    val steps: List<SkillStep> = emptyList(),
    val script: String? = null,       // Script Skill
    val scriptLang: String? = null,   // "python", "shell"
    val connectorUrl: String? = null  // Connector Skill
)

@Serializable
data class SkillStep(
    val tool: String,
    val args: Map<String, String> = emptyMap(),
    val condition: String? = null  // 条件执行
)

@Serializable
data class SkillConfiguration(
    val autoSetup: List<SetupAction> = emptyList(),
    val userConfig: List<UserConfigField> = emptyList()
)

@Serializable
data class SetupAction(
    val action: String,  // "create_directory", "register_tool", "memorize", "install_package"
    val path: String? = null,
    val toolId: String? = null,
    val key: String? = null,
    val content: String? = null,
    val command: String? = null
)

@Serializable
data class UserConfigField(
    val key: String,
    val type: String,  // "string", "integer", "boolean", "enum"
    val default: String? = null,
    val options: List<String> = emptyList(),
    val description: String = "",
    /**
     * 必填开关（默认 true）：必填且无默认值、用户未填值的技能视为「未配置好」——
     * [SkillConfigRules.isConfigured] 判定，未配置好的技能不进斜杠菜单（用户需求：
     * 「没有配置好的不要在输入框 / 按钮里显示」）。显式声明 required=false 的
     * 字段不阻爆配置就绪。
     */
    val required: Boolean = true
)

/** 技能目录摘要（渐进披露）：提示词里的一行式清单条目。 */
data class SkillDigest(
    val id: String,
    val name: String,
    /** 一行摘要（description 首句，超长截断）。 */
    val summary: String,
    val tags: List<String> = emptyList(),
    /** #206 分类域（[SkillCategory.key]；未分类 = null）—— 目录抽样与展示分组用。 */
    val category: String? = null
)

/**
 * 技能工具 + 所属技能的工位作用域（[SkillRegistry.getActiveToolsWithScope] 载体）。
 * v3：[SkillHotReloader] 注册复合工具时把 scope 打进 ToolMetadata——
 * 与市场分级、prompt 注入过滤（同 manifest 字段）同源。
 */
data class SkillToolWithScope(
    val def: SkillToolDef,
    /** 已规范化的 manifest scope（"agent" | "coding" | "all"）。 */
    val scope: String
)

/**
 * 目录协调报告（[SkillRegistry.reconcileDirectory] 的返回值）：
 * 新装 / 升级 / 卸载 / 跳过的技能 id 清单（诊断与 [SkillDirectoryWatcher] 日志用）。
 */
data class SkillDirSyncReport(
    val added: List<String>,
    val updated: List<String>,
    val removed: List<String>,
    /** 解析失败 / id 非法 / 依赖缺失 / 重复等被单条隔离跳过的条目（文件名或 id）。 */
    val skipped: List<String>
)

/**
 * Skill 注册表
 * 管理所有已安装的 Skill（持久化到文件系统）
 *
 * ## v2 修复
 * - **线程安全**：旧实现的 `installedSkills`/`disabledIds` 是裸可变集合并被 Main/IO/Default
 *   三线程读写（市场页、技能页、Agent 工具执行、斜杠菜单聚合），存在 HashMap 结构损坏与
 *   ConcurrentModificationException 闪退向量。现所有公开方法统一由 [lock] 监视器锁保护。
 * - **路径穿越防御**：`manifest.id` 与 autoSetup `path` 现在做清洗——安装来源包含不可信 URL
 *   下载，恶意 id（如 `../../x` 或含 `/`）旧实现可把文件写到 skillsDir 之外。
 * - **卸载语义**：旧实现返回 `File.delete()`，文件已不存在时返回 false 导致 UI 报"未找到"
 *   而内存中其实已移除；且不清理 `installFromZip` 建立的 `<id>/` 资源目录（孤儿文件泄漏）。
 *   现返回内存态是否移除，并连资源目录一起清理。
 * - **变更通知**：安装/卸载/开关都会发 [changes]，斜杠菜单与市场页订阅后自动刷新，
 *   修复旧实现"市场操作后菜单仍是旧快照"的问题。
 */
class SkillRegistry(
    private val skillsDir: File,
    /**
     * Issue #166：内置技能释放日志出口。core:tool-registry 不依赖 core:logging，
     * 复用同包 [SkillHotReloadLogSink] 抽象（避免再造平行接口），由宿主（app 层 DI）
     * 映射到 AppLogger 注入；不传时默认静默，纯 JVM 单测无需任何日志设施
     * （与 [SkillHotReloader] 的 logger 同款约定）。
     */
    private val logger: SkillHotReloadLogSink = SkillHotReloadLogSink { _, _ -> }
) {
    private val installedSkills = LinkedHashMap<String, InstalledSkill>()
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    // 禁用状态持久化：sidecar 文件（每行一个 skill id），不污染 manifest schema。
    // per-scope 启停（双工位隔离）：.disabled = 双工位同禁（旧格式，升级用户零迁移）；
    // .disabled-agent / .disabled-coding = 单工位禁用 —— 两个市场分级里各自独立开关。
    private val disabledIds = mutableSetOf<String>()
    private val disabledAgentIds = mutableSetOf<String>()
    private val disabledCodingIds = mutableSetOf<String>()

    // 用户配置值持久化（.config.json：{skillId: {key: value}}）：
    // manifest 升级会覆写 <id>.json，配置值必须住 sidecar 才能跨升级存活。
    private val configValues = LinkedHashMap<String, MutableMap<String, String>>()

    private val lock = Any()

    /** 安装/卸载/开关变更通知（快照流消费者订阅后自动刷新）。 */
    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
    val changes: SharedFlow<Unit> = _changes.asSharedFlow()

    init {
        runCatching { skillsDir.mkdirs() }
        loadDisabledIds()
        loadConfigValues()
        loadInstalledSkills()
    }

    private val disabledFile: File get() = File(skillsDir, ".disabled")
    private val disabledAgentFile: File get() = File(skillsDir, ".disabled-agent")
    private val disabledCodingFile: File get() = File(skillsDir, ".disabled-coding")
    private val configFile: File get() = File(skillsDir, ".config.json")

    private fun loadDisabledIds() {
        if (disabledFile.exists()) {
            disabledFile.readLines().forEach { if (it.isNotBlank()) disabledIds.add(it.trim()) }
        }
        // per-scope sidecar（文件缺失 = 空集，首次升级自然兼容）
        if (disabledAgentFile.exists()) {
            disabledAgentFile.readLines().forEach { if (it.isNotBlank()) disabledAgentIds.add(it.trim()) }
        }
        if (disabledCodingFile.exists()) {
            disabledCodingFile.readLines().forEach { if (it.isNotBlank()) disabledCodingIds.add(it.trim()) }
        }
    }

    private fun persistDisabledIds() {
        try {
            disabledFile.writeText(disabledIds.joinToString("\n"))
            disabledAgentFile.writeText(disabledAgentIds.joinToString("\n"))
            disabledCodingFile.writeText(disabledCodingIds.joinToString("\n"))
        } catch (_: Exception) { /* 写失败不阻断内存态，下次 setEnabled 再试 */ }
    }

    /** 加载用户配置值（防御式：文件损坏只丢配置值，不阻爆注册表启动）。 */
    private fun loadConfigValues() {
        if (!configFile.exists()) return
        runCatching {
            val root = json.parseToJsonElement(configFile.readText()).jsonObject
            for ((skillId, value) in root) {
                val fields = value as? kotlinx.serialization.json.JsonObject ?: continue
                val map = mutableMapOf<String, String>()
                for ((k, v) in fields) {
                    (v as? kotlinx.serialization.json.JsonPrimitive)?.let { map[k] = it.content }
                }
                configValues[skillId] = map
            }
        }.onFailure {
            logger.log(SkillHotReloadLogLevel.WARN, "技能配置值加载失败（已忽略，按未配置处理）：${it.message}")
        }
    }

    /** 原子写配置值（tmp + renameTo；rename 失败直写目标兑底）。 */
    private fun persistConfigValues() {
        try {
            val root = kotlinx.serialization.json.buildJsonObject {
                for ((skillId, values) in configValues) {
                    put(skillId, kotlinx.serialization.json.buildJsonObject {
                        for ((k, v) in values) put(k, JsonPrimitive(v))
                    })
                }
            }
            val text = json.encodeToString(
                kotlinx.serialization.json.JsonObject.serializer(), root
            )
            val tmp = File(skillsDir, ".config.json.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(configFile)) {
                configFile.writeText(text)
            }
        } catch (_: Exception) { /* 写失败不阻断内存态，下次写配置再试 */ }
    }

    data class InstalledSkill(
        val manifest: SkillManifest,
        /** Agent 工位（Agent 屏 / Agent 市场）是否启用。 */
        val enabledAgent: Boolean = true,
        /** Coding 工位（Coding 屏 / Coding 市场）是否启用。 */
        val enabledCoding: Boolean = true,
        val installedAt: Long = System.currentTimeMillis(),
        /** 用户已填的配置值快照（真相源是 .config.json sidecar；只读视图）。 */
        val config: MutableMap<String, String> = mutableMapOf()
    ) {
        /** 双工位至少一开（旧调用方语义：全局启用与否）。 */
        val enabled: Boolean get() = enabledAgent || enabledCoding
    }

    /**
     * 校验 skill id 可用作安全文件名（防路径穿越）。
     * 只允许字母、数字、下划线、连字符、点，且不得含 `..` 段、不得以点开头。
     */
    private fun validateSkillId(id: String): Boolean {
        if (id.isBlank() || id.length > 128) return false
        if (!id.matches(Regex("[A-Za-z0-9_-]+(\\.[A-Za-z0-9_-]+)*"))) return false
        if (id.startsWith(".") || id.contains("..")) return false
        return true
    }

    /** autoSetup path 清洗：只允许相对 skillsDir 的路径，拒绝绝对路径与 `..` 逃逸。 */
    private fun safeSetupPath(path: String): File? {
        val normalized = path.replace('\\', '/')
        if (normalized.isBlank()) return null
        if (normalized.startsWith("/") || normalized.contains("..")) return null
        val target = File(skillsDir, normalized)
        if (!target.canonicalPath.startsWith(skillsDir.canonicalPath + File.separator)) return null
        return target
    }

    /**
     * 安装 Skill（从 JSON 字符串）。会先校验依赖是否齐备，缺失依赖则拒绝安装。
     */
    fun install(manifestJson: String): Result<SkillManifest> {
        val result = synchronized(lock) {
            try {
                val manifest = json.decodeFromString<SkillManifest>(manifestJson)

                if (manifest.id.isBlank()) return Result.failure(Exception("Skill ID is empty"))
                if (manifest.name.isBlank()) return Result.failure(Exception("Skill name is empty"))
                if (!validateSkillId(manifest.id)) {
                    return Result.failure(
                        Exception("Invalid skill id '${manifest.id}': only [A-Za-z0-9_.-] allowed, no path separators")
                    )
                }

                // 依赖校验：所有依赖必须已安装
                val missing = SkillDependencyResolver.validateDependencies(manifest, installedSkills.keys)
                if (missing.isNotEmpty()) {
                    return Result.failure(Exception("Missing dependencies: ${missing.joinToString(", ")}"))
                }

                val skillFile = File(skillsDir, "${manifest.id}.json")
                skillFile.writeText(manifestJson)

                installedSkills[manifest.id] = InstalledSkill(
                    manifest = manifest,
                    config = configValues[manifest.id]?.toMutableMap() ?: mutableMapOf()
                )

                executeAutoSetup(manifest)

                Result.success(manifest)
            } catch (e: Exception) {
                Result.failure(Exception("Skill install failed: ${e.message}"))
            }
        }
        if (result.isSuccess) notifyChanged()
        return result
    }

    /**
     * 从 ZIP 包安装 Skill。
     * 包内需含一个 `<skillId>.json`（apex-skill-v1 manifest），其余文件为 Skill 资源。
     * 解压走 [SafeZipExtractor]（路径穿越防御 + zip bomb 防护）。
     */
    fun installFromZip(zipFile: File): Result<SkillManifest> {
        val tmpDir = File(skillsDir, ".tmp-${zipFile.nameWithoutExtension}-${System.nanoTime()}")
        val result = synchronized(lock) {
            try {
                SafeZipExtractor.extract(zipFile, tmpDir)

                val manifestFile = tmpDir.listFiles()
                    ?.firstOrNull { it.extension == "json" && it.name != "installed.json" }
                    ?: return Result.failure(Exception("No skill manifest .json found in zip"))

                val manifest = json.decodeFromString<SkillManifest>(manifestFile.readText())

                if (!validateSkillId(manifest.id)) {
                    return Result.failure(
                        Exception("Invalid skill id '${manifest.id}': only [A-Za-z0-9_.-] allowed, no path separators")
                    )
                }

                // 依赖校验
                val missing = SkillDependencyResolver.validateDependencies(manifest, installedSkills.keys)
                if (missing.isNotEmpty()) {
                    return Result.failure(Exception("Missing dependencies: ${missing.joinToString(", ")}"))
                }

                // 资源目录：将 zip 内除 manifest 外的所有文件并入 skillsDir/<id>/，
                // manifest 只作为顶层索引 <id>.json 保存（避免重复存储）。
                val skillHome = File(skillsDir, manifest.id).apply { mkdirs() }
                tmpDir.listFiles()
                    ?.filter { it != manifestFile }
                    ?.forEach { it.copyRecursively(File(skillHome, it.name), overwrite = true) }

                val skillFile = File(skillsDir, "${manifest.id}.json")
                skillFile.writeText(manifestFile.readText())

                installedSkills[manifest.id] = InstalledSkill(
                    manifest = manifest,
                    config = configValues[manifest.id]?.toMutableMap() ?: mutableMapOf()
                )
                executeAutoSetup(manifest)

                Result.success(manifest)
            } catch (e: Exception) {
                Result.failure(Exception("Skill zip install failed: ${e.message}"))
            } finally {
                runCatching { tmpDir.deleteRecursively() }
            }
        }
        if (result.isSuccess) notifyChanged()
        return result
    }

    /**
     * Issue #166：内置技能资产化 —— 幂等释放 APK assets 中打包的优质技能集。
     *
     * 对列表中每个 JSON 独立执行，语义：
     * 1. 解析 manifest（失败 → 记 WARN 跳过该条，不中断整批——**单条失败隔离是
     *    刻意的**：一个损坏的 asset 不能拖垮其余内置技能的首启体验，其余条目照常释放）；
     * 2. 未安装 → 走 [install] 全新安装（无条件补齐 bundled=true 标记后再落盘，
     *    保证经本通道释放的技能全部可被市场识别与卸载守卫覆盖）；
     * 3. 已安装且 assets 版本更高（[compareVersions] 逐段比较）→ 重新 install 升级；
     *    install 会把 enabled 重置为 true，故升级后按 .disabled sidecar 语义把用户
     *    此前禁用过的技能重新置回禁用（保留用户偏好，对齐 McpManager.ensureBuiltinServer
     *    的 `existing?.let { config.copy(enabled = it.enabled) }` 先例）；
     * 4. 已安装且版本相同或更低 → 跳过（用户侧不低于内置版本时绝不降级、绝不覆盖）。
     *
     * 幂等：同一批 JSON 重复调用，第二次起恒返回 0、零副作用；每次 App 启动重复调用安全。
     *
     * @return 本次**新增安装**的技能数（升级不计入，跳过不计入；解析失败不影响其余计数）
     */
    fun installBundled(manifestJsons: List<String>): Result<Int> {
        var added = 0
        for (raw in manifestJsons) {
            try {
                val manifest = json.decodeFromString<SkillManifest>(raw)
                // 无条件补齐 bundled 标记：assets 通道是内置语义的唯一入口，
                // 防止资产作者漏写字段后该技能被当普通社区技能卸载。
                val payload = if (manifest.bundled) {
                    raw
                } else {
                    json.encodeToString(SkillManifest.serializer(), manifest.copy(bundled = true))
                }
                val existing = synchronized(lock) { installedSkills[manifest.id] }
                if (existing == null) {
                    install(payload).getOrThrow()
                    added++
                    logger.log(
                        SkillHotReloadLogLevel.INFO,
                        "内置技能释放：${manifest.id} v${manifest.version}"
                    )
                } else if (compareVersions(manifest.version, existing.manifest.version) > 0) {
                    // 升级保留用户 per-scope 启停态（install 会重置为双开）
                    val wasAgent = existing.enabledAgent
                    val wasCoding = existing.enabledCoding
                    install(payload).getOrThrow()
                    if (!wasAgent) setEnabledForScope(manifest.id, SCOPE_AGENT, false)
                    if (!wasCoding) setEnabledForScope(manifest.id, SCOPE_CODING, false)
                    logger.log(
                        SkillHotReloadLogLevel.INFO,
                        "内置技能升级：${manifest.id} ${existing.manifest.version} → ${manifest.version}" +
                            "（保持用户禁用态：agent=${!wasAgent}, coding=${!wasCoding}）"
                    )
                } else {
                    logger.log(
                        SkillHotReloadLogLevel.INFO,
                        "内置技能 ${manifest.id} 已是 v${existing.manifest.version}（不低于 assets 的 " +
                            "v${manifest.version}），跳过"
                    )
                }
            } catch (e: Exception) {
                // 刻意隔离（见 KDoc）：单条损坏只记日志，不中断其余条目
                logger.log(
                    SkillHotReloadLogLevel.WARN,
                    "内置技能释放失败，已跳过：${e.message}"
                )
            }
        }
        return Result.success(added)
    }

    /**
     * 内置瘦身迁移：清掉「曾经内置、现已从 assets 移除」的技能。
     *
     * 背景（Hub 生态重构）：APK 内置技能从 75 收敛到 13 个核心，其余迁往
     * 官方技能仓库（apex-skill-hub）。升级用户设备上残留的旧内置技能
     * bundled=true、不可卸载，会永久卡在已安装列表——本方法按 assets 白名单
     * 反向清理：
     * - 只动 `bundled == true` 的条目（社区/仓库安装的永不触碰）；
     * - id 不在 [bundledIds]（当前 assets 清单）里的内置技能被卸载
     *   （绕过 [uninstall] 的 bundled 防线——那道防线防的是用户手动卸载后
     *   被下次启动复活，本方法是同一个释放管线的另一半，语义互补）；
     * - 同时清理 `.disabled` sidecar 里的残留 id 与 `<id>/` 资源目录。
     *
     * @param bundledIds 当前 APK assets/skills/ 里仍然打包的技能 id 集合
     * @return 本次清理掉的技能数
     */
    fun pruneStaleBundled(bundledIds: Set<String>): Int {
        val stale = synchronized(lock) {
            installedSkills.values
                .filter { it.manifest.bundled && it.manifest.id !in bundledIds }
                .map { it.manifest.id }
        }
        for (id in stale) {
            synchronized(lock) {
                installedSkills.remove(id)
                disabledIds.remove(id)
                disabledAgentIds.remove(id)
                disabledCodingIds.remove(id)
                configValues.remove(id)
                persistDisabledIds()
                persistConfigValues()
                runCatching { File(skillsDir, "$id.json").delete() }
                runCatching { File(skillsDir, id).deleteRecursively() }
            }
            logger.log(
                SkillHotReloadLogLevel.INFO,
                "内置瘦身：技能 '$id' 已从 assets 移除（迁往官方仓库），本地清理完成"
            )
        }
        if (stale.isNotEmpty()) notifyChanged()
        return stale.size
    }

    /**
     * 朴素 semver 比较（major.minor.patch 逐段数值比较；缺段按 0、非数字段按 0）。
     * Issue #166 内置技能升级判定专用——资产版本由本仓库统一管理，无需完整 SemVer
     * 语义（预发布/构建元数据按 0 段处理，误判后果仅是跳过或延后一次升级，无害）。
     */
    private fun compareVersions(newVersion: String, oldVersion: String): Int {
        val a = newVersion.split('.').map { it.trim().toIntOrNull() ?: 0 }
        val b = oldVersion.split('.').map { it.trim().toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    /**
     * 卸载 Skill（连同 `<id>/` 资源目录一起清理）。
     *
     * Issue #166：内置技能（manifest.bundled == true）不可卸载——卸载后下次启动
     * 会被 assets 释放逻辑重新装回，「卸载成功又复活」比「不可卸载」更欺骗用户。
     * 此处直接返回 false，UI 层应把 bundled 技能的卸载入口降级为
     * 「内置技能可禁用不可卸载」提示（禁用走 [setEnabled]）。
     *
     * 返回内存中是否存在该技能——旧实现返回 `File.delete()`，
     * 文件已被外部清理时会误报失败（状态撕裂）。
     */
    fun uninstall(skillId: String): Boolean {
        val removed = synchronized(lock) {
            val target = installedSkills[skillId]
            if (target != null && target.manifest.bundled) return false
            val existed = installedSkills.remove(skillId) != null
            if (existed) {
                disabledIds.remove(skillId)
                disabledAgentIds.remove(skillId)
                disabledCodingIds.remove(skillId)
                configValues.remove(skillId)
                persistDisabledIds()
                persistConfigValues()
                runCatching { File(skillsDir, "$skillId.json").delete() }
                // 清理 ZIP 安装产生的资源目录，避免孤儿文件累积
                runCatching { File(skillsDir, skillId).deleteRecursively() }
            }
            existed
        }
        if (removed) notifyChanged()
        return removed
    }

    /**
     * 获取所有已安装 Skill（快照读，线程安全）。
     */
    fun getInstalled(): List<InstalledSkill> = synchronized(lock) { installedSkills.values.toList() }

    /**
     * 获取启用的 Skill 提供的工具
     */
    fun getActiveTools(): List<SkillToolDef> {
        return synchronized(lock) {
            installedSkills.values
                .filter { it.enabled }
                .flatMap { it.manifest.tools }
        }
    }

    /**
     * v3 获取启用技能提供的工具（携带所属技能的工位作用域）。
     *
     * [SkillHotReloader] 注册复合工具时用它把 scope 打进 ToolMetadata，
     * 与市场分级、prompt 注入过滤（[getActivePromptInjections]）同源。
     *
     * per-scope 启停：生效作用域 = manifest 声明 ∩ 双工位启用集合 ——
     * manifest 声明 all 但只在 coding 启用的技能，其工具以 scope="coding"
     * 注册（Agent 引擎不可见）；双工位都开才保留 "all"。
     */
    fun getActiveToolsWithScope(): List<SkillToolWithScope> {
        return synchronized(lock) {
            installedSkills.values
                .flatMap { skill ->
                    val scopes = effectiveScopes(skill)
                    val scope = when {
                        scopes.size == 2 -> "all"
                        scopes.isEmpty() -> return@flatMap emptyList()
                        else -> scopes.first()
                    }
                    skill.manifest.tools.map { SkillToolWithScope(it, scope) }
                }
        }
    }

    /**
     * 获取所有 Prompt 注入（全量——旧行为，兼容未接入渐进披露的调用方）。
     */
    fun getPromptInjections(): List<String> {
        return synchronized(lock) {
            installedSkills.values
                .filter { it.enabled }
                .mapNotNull { it.manifest.promptInjection }
        }
    }

    /**
     * #197 按工位作用域获取 Prompt 注入。
     *
     * @param scope "agent" | "coding" —— 只返回生效作用域含该工位的技能
     *        文本。null = 不过滤（全量，等价 [getPromptInjections]，兼容旧调用）。
     *        生效作用域 = manifest 声明 ∩ per-scope 启用集合。
     */
    fun getPromptInjections(scope: String?): List<String> {
        if (scope.isNullOrBlank()) return getPromptInjections()
        return synchronized(lock) {
            installedSkills.values
                .filter { scope in effectiveScopes(it) }
                .mapNotNull { it.manifest.promptInjection }
        }
    }

    /**
     * 渐进披露：仅返回「已激活且启用」技能的 Prompt 注入。
     *
     * 技能库扩到 40+ 后全量注入会撞请求体积上限；激活集外的技能只经
     * [getSkillDigests] 目录（一行摘要）暴露，需要时由模型调 skill_activate
     * 或 [SkillAutoActivator] 自动装备。激活 id 里已卸载/禁用的条目自然过滤。
     *
     * #197 双工位：[scope] 非空时在激活集上再按工位作用域过滤
     * （"agent" | "coding"；"all" 技能两工位可见）。null = 不过滤（main 行为）。
     */
    fun getActivePromptInjections(activeIds: Set<String>, scope: String? = null): List<String> {
        if (activeIds.isEmpty()) return emptyList()
        return synchronized(lock) {
            activeIds.asSequence()
                .mapNotNull { installedSkills[it] }
                .filter { scope.isNullOrBlank() || scope in effectiveScopes(it) }
                .mapNotNull { it.manifest.promptInjection }
                .toList()
        }
    }

    /**
     * 技能目录摘要（全部已安装且启用的技能，含未激活）：
     * 提示词「Skill Catalog」段的渲染源。summary 取 description 首句并截断，
     * 保证目录段体积与技能数量线性且每条 ≤ 一行。
     *
     * #197 双工位：[scope] 非空时目录同样按工位作用域过滤。
     * #206：[SkillDigest.category] 随附（目录抽样按域均衡的依据）。
     */
    fun getSkillDigests(scope: String? = null): List<SkillDigest> {
        return synchronized(lock) {
            installedSkills.values
                .filter { scope.isNullOrBlank() || scope in effectiveScopes(it) }
                .map { skill ->
                    SkillDigest(
                        id = skill.manifest.id,
                        name = skill.manifest.name,
                        summary = firstSentence(skill.manifest.description),
                        tags = skill.manifest.tags.take(4),
                        category = SkillCategory.sanitize(skill.manifest.category)
                    )
                }
                .sortedBy { it.id }
        }
    }

        /** description 首句（首个句号/分号前），超 72 字符截断加省略号。 */
    private fun firstSentence(description: String): String {
        val cut = description.indexOfFirst { it == '。' || it == '；' || it == ';' }
        val sentence = if (cut > 0) description.substring(0, cut) else description
        return if (sentence.length > 72) sentence.take(72) + "…" else sentence
    }

    /**
     * 启用/禁用 Skill（双工位同开/同关 —— 旧调用方语义）。
     *
     * 市场分级的独立开关请用 [setEnabledForScope]；本方法保留给无工位
     * 上下文的调用方（安装工具 / 内置释放升级 / 旧测试）。
     */
    fun setEnabled(skillId: String, enabled: Boolean) {
        val changed = synchronized(lock) {
            val before = installedSkills[skillId]?.let { it.enabledAgent to it.enabledCoding }
            installedSkills[skillId]?.let {
                installedSkills[skillId] = it.copy(enabledAgent = enabled, enabledCoding = enabled)
            }
            if (enabled) {
                disabledIds.remove(skillId)
                disabledAgentIds.remove(skillId)
                disabledCodingIds.remove(skillId)
            } else {
                disabledIds.add(skillId)
                disabledAgentIds.remove(skillId)
                disabledCodingIds.remove(skillId)
            }
            persistDisabledIds()
            before != null && (before.first != enabled || before.second != enabled)
        }
        if (changed) notifyChanged()
    }

    /**
     * 双工位独立启停（市场分级开关）。
     *
     * - [scope] 仅接受 [SCOPE_AGENT] / [SCOPE_CODING]（其他值折回双工位同开闭）；
     * - 关闭本工位不动另一工位；从「双禁」里单开一个工位时，另一工位
     *   的禁用态落进对应单工位 sidecar（.disabled 不再承载混合态）。
     */
    fun setEnabledForScope(skillId: String, scope: String, enabled: Boolean) {
        val normalized = normalizeScopeOrAll(scope)
        if (normalized != SCOPE_AGENT && normalized != SCOPE_CODING) {
            setEnabled(skillId, enabled)
            return
        }
        val changed = synchronized(lock) {
            val before = installedSkills[skillId]?.let {
                if (normalized == SCOPE_AGENT) it.enabledAgent else it.enabledCoding
            }
            installedSkills[skillId]?.let {
                installedSkills[skillId] = if (normalized == SCOPE_AGENT) {
                    it.copy(enabledAgent = enabled)
                } else {
                    it.copy(enabledCoding = enabled)
                }
            }
            if (enabled) {
                // 从双禁单开一个工位：另一工位保持禁用（落在单工位 sidecar）
                if (disabledIds.remove(skillId)) {
                    if (normalized == SCOPE_AGENT) disabledCodingIds.add(skillId)
                    else disabledAgentIds.add(skillId)
                }
                if (normalized == SCOPE_AGENT) disabledAgentIds.remove(skillId)
                else disabledCodingIds.remove(skillId)
            } else {
                disabledIds.remove(skillId)
                if (normalized == SCOPE_AGENT) disabledAgentIds.add(skillId)
                else disabledCodingIds.add(skillId)
            }
            persistDisabledIds()
            before != null && before != enabled
        }
        if (changed) notifyChanged()
    }

    /** 某工位是否启用该技能（未安装 = false；scope 非法折回任意工位开）。 */
    fun isEnabledForScope(skillId: String, scope: String): Boolean {
        val normalized = normalizeScopeOrAll(scope)
        return synchronized(lock) {
            val skill = installedSkills[skillId] ?: return@synchronized false
            when (normalized) {
                SCOPE_CODING -> skill.enabledCoding
                SCOPE_AGENT -> skill.enabledAgent
                else -> skill.enabled
            }
        }
    }

    /**
     * 技能生效工位集合的公开只读视图（斜杠菜单 / 市场行用）：
     * manifest 声明 ∩ 双工位启用集合；未安装 = 空集。
     */
    fun effectiveScopesOf(skillId: String): Set<String> =
        synchronized(lock) { installedSkills[skillId]?.let { effectiveScopes(it) } ?: emptySet() }

    /**
     * 技能生效工位集合：manifest 声明 ∩ 双工位启用集合。
     * （"all" manifest 只在 coding 开 → {coding}；coding manifest 在 agent 侧关着 → 空集）
     */
    private fun effectiveScopes(skill: InstalledSkill): Set<String> {
        val declared = when (ToolMetadata.normalizeScope(skill.manifest.scope)) {
            SCOPE_AGENT -> setOf(SCOPE_AGENT)
            SCOPE_CODING -> setOf(SCOPE_CODING)
            else -> setOf(SCOPE_AGENT, SCOPE_CODING)
        }
        val enabled = buildSet {
            if (skill.enabledAgent) add(SCOPE_AGENT)
            if (skill.enabledCoding) add(SCOPE_CODING)
        }
        return declared intersect enabled
    }

    /** scope 规范化（复用 [ToolMetadata.normalizeScope]，脏值折 all）。 */
    private fun normalizeScopeOrAll(scope: String): String =
        ToolMetadata.normalizeScope(scope)

    // ═══ 用户配置值（就绪门禁：没有配置好的不进斜杠菜单）═══

    /** 读取某技能的用户配置值（未配置 = 空表）。 */
    fun getConfigValues(skillId: String): Map<String, String> =
        synchronized(lock) { configValues[skillId]?.toMap() ?: emptyMap() }

    /**
     * 整表写入某技能的配置值（市场详情表单保存；覆盖写，空白值剔除）。
     * 写入后发 [changes] —— 斜杠菜单据此重建（配置齐了条目即刻出现）。
     */
    fun setConfigValues(skillId: String, values: Map<String, String>) {
        val changed = synchronized(lock) {
            if (installedSkills[skillId] == null) return@synchronized false
            val cleaned = values.filterValues { it.isNotBlank() }
            val before = configValues[skillId]
            if (cleaned.isEmpty()) configValues.remove(skillId) else configValues[skillId] = cleaned.toMutableMap()
            installedSkills[skillId]?.let {
                installedSkills[skillId] = it.copy(config = configValues[skillId]?.toMutableMap() ?: mutableMapOf())
            }
            persistConfigValues()
            before != configValues[skillId]
        }
        if (changed) notifyChanged()
    }

    /**
     * 技能是否「配置好」（[SkillConfigRules.isConfigured]：全部必填字段已有值或默认值）。
     * 未安装 / 无配置声明 = true（就绪）。斜杠菜单与自动装备据此门禁。
     */
    fun isConfigured(skillId: String): Boolean = synchronized(lock) {
        val skill = installedSkills[skillId] ?: return@synchronized true
        SkillConfigRules.isConfigured(skill.manifest, configValues[skillId] ?: emptyMap())
    }

    /**
     * 执行自动配置（创建目录等。register_tool/memorize 由 ToolRegistry 层处理）
     */
    private fun executeAutoSetup(manifest: SkillManifest) {
        for (action in manifest.configuration.autoSetup) {
            when (action.action) {
                "create_directory" -> {
                    // v2：path 经 safeSetupPath 清洗，拒绝绝对路径与 `..` 逃逸出 skillsDir
                    action.path?.let { safeSetupPath(it)?.mkdirs() }
                }
                // register_tool / memorize / install_package 由调用方在 ToolRegistry 中处理
            }
        }
    }

    private fun loadInstalledSkills() {
        skillsDir.listFiles()?.filter { it.extension == "json" }?.forEach { file ->
            try {
                val manifest = json.decodeFromString<SkillManifest>(file.readText())
                if (validateSkillId(manifest.id)) {
                    synchronized(lock) {
                        installedSkills[manifest.id] = InstalledSkill(
                            manifest = manifest,
                            // 双禁 sidecar 优先；单工位 sidecar 独立叠加（旧安装无后两者）
                            enabledAgent = manifest.id !in disabledIds && manifest.id !in disabledAgentIds,
                            enabledCoding = manifest.id !in disabledIds && manifest.id !in disabledCodingIds,
                            config = configValues[manifest.id]?.toMutableMap() ?: mutableMapOf()
                        )
                    }
                }
            } catch (_: Exception) { /* skip malformed */ }
        }
    }

    /**
     * 读取指定 Skill（安装管理页 / 市场详情用）。
     */
    fun get(skillId: String): InstalledSkill? = synchronized(lock) { installedSkills[skillId] }

    /**
     * # 目录协调（热加载）：把磁盘上 skillsDir 目录的 JSON 清单与内存安装表对齐。
     *
     * [SkillDirectoryWatcher] 每次指纹变化时调用（市场外的文件变化：热更
     * 引擎落盘、用户文件同步、adb push、ZIP 手动解压……），对齐后
     * [notifyChanged] → SkillHotReloader 增量同步工具 + 市场页/斜杠菜单
     * 自动刷新 + 引擎每轮重读 prompt 注入 —— **装、卸、升级全程免重启**。
     *
     * ## 对齐规则（registry 为真相源，绝不无脑重装）
     *
     * - 磁盘新文件（manifest 合法、id 未安装）→ [install] 全新安装；
     * - 磁盘文件与已装条目同 id 且**版本更高** → 升级安装（保持用户启停
     *   态，语义对齐 [installBundled]）；版本相同或更低 → 跳过 —— 这同时
     *   封死了「[install] 落盘改 mtime → watcher 再触发 → 再 install」的
     *   自激环：内容不变即无动作；
     * - 已装非内置条目的 manifest 文件消失 → 卸载（含 `<id>/` 资源目录）；
     * - 内置（bundled）条目文件消失 → 保留内存态（assets/热更通道下次
     *   启动会重放释放，运行期不制造「卸了又复活」的抖动）；
     * - 解析失败/依赖缺失 → 记入 [SkillDirSyncReport.skipped]（单条隔离，
     *   不拖垮整批——与 installBundled 同款纪律）。
     *
     * ## 并发安全
     *
     * 决策基于快照（getInstalled + 一次目录扫描），动作复用既有
     * [install]/[uninstall]（各自持锁）；与市场页并发操作天然幂等——
     * 同 id 双方都装一遍结果一致。
     *
     * @return 本次协调报告（新增/升级/卸载/跳过的 id 清单）
     */
    fun reconcileDirectory(): SkillDirSyncReport {
        val files = runCatching {
            skillsDir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".json") && !it.name.startsWith(".") }
                .orEmpty()
        }.getOrDefault(emptyList())

        // 磁盘侧：文件 → manifest（单条解析失败只进 skipped，不中断）
        data class DiskEntry(val manifest: SkillManifest, val text: String)
        val onDisk = LinkedHashMap<String, DiskEntry>()
        val skipped = mutableListOf<String>()
        for (file in files) {
            try {
                val text = file.readText()
                val manifest = json.decodeFromString<SkillManifest>(text)
                if (manifest.id.isBlank() || !validateSkillId(manifest.id)) {
                    skipped += file.name
                    logger.log(
                        SkillHotReloadLogLevel.WARN,
                        "目录协调：文件 ${file.name} 的 id 非法（'${manifest.id}'），跳过"
                    )
                    continue
                }
                onDisk[manifest.id] = DiskEntry(manifest, text)
            } catch (e: Exception) {
                skipped += file.name
                logger.log(
                    SkillHotReloadLogLevel.WARN,
                    "目录协调：文件 ${file.name} 解析失败（可能写入中），跳过：${e.message}"
                )
            }
        }

        val installed = getInstalled().associateBy { it.manifest.id }
        val added = mutableListOf<String>()
        val updated = mutableListOf<String>()
        val removed = mutableListOf<String>()

        // 1. 磁盘有 → 新装或升级
        for ((id, entry) in onDisk) {
            val existing = installed[id]
            when {
                existing == null -> {
                    install(entry.text).fold(
                        onSuccess = { added += id },
                        onFailure = { skipped += id }
                    )
                }
                // 升级判定：磁盘版本严格更高才动（同版本/降级跳过，见 KDoc 自激环说明）
                compareVersions(entry.manifest.version, existing.manifest.version) > 0 -> {
                    // 升级保留用户 per-scope 启停态（install 会重置为双开）
                    val wasAgent = existing.enabledAgent
                    val wasCoding = existing.enabledCoding
                    install(entry.text).fold(
                        onSuccess = {
                            if (!wasAgent) setEnabledForScope(id, SCOPE_AGENT, false)
                            if (!wasCoding) setEnabledForScope(id, SCOPE_CODING, false)
                            updated += id
                        },
                        onFailure = { skipped += id }
                    )
                }
                else -> Unit // 版本未变：注册表已是真相，零动作
            }
        }

        // 2. 磁盘无 → 卸载（内置条目除外，见 KDoc）
        for (skill in installed.values) {
            if (skill.manifest.id in onDisk) continue
            if (skill.manifest.bundled) continue
            if (uninstall(skill.manifest.id)) removed += skill.manifest.id
        }

        if (added.isNotEmpty() || updated.isNotEmpty() || removed.isNotEmpty()) {
            logger.log(
                SkillHotReloadLogLevel.INFO,
                "技能目录热同步：新增 ${added.size}（${added.joinToString(", ").take(120)}）" +
                    "· 升级 ${updated.size} · 卸载 ${removed.size} · 跳过 ${skipped.size}"
            )
        }
        return SkillDirSyncReport(
            added = added,
            updated = updated,
            removed = removed,
            skipped = skipped
        )
    }

    private fun notifyChanged() {
        _changes.tryEmit(Unit)
    }

    companion object {
        /** Agent 工位 scope 值（Agent 屏 / Agent 市场）。 */
        const val SCOPE_AGENT = "agent"

        /** Coding 工位 scope 值（Coding 屏 / Coding 市场）。 */
        const val SCOPE_CODING = "coding"
    }
}
