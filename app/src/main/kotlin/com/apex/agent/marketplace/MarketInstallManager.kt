package com.apex.agent.marketplace

import com.apex.agent.core.tools.builtin.SkillInstallTool
import com.apex.agent.core.tools.connector.ConnectorDef
import com.apex.agent.core.tools.connector.ConnectorRegistry
import com.apex.agent.core.tools.marketplace.ClawHubSource
import com.apex.agent.core.tools.marketplace.GithubMcpSource
import com.apex.agent.core.tools.marketplace.ModelScopeSource
import com.apex.agent.core.tools.marketplace.RawGitHubMirrors
import com.apex.agent.core.tools.marketplace.RegistryServer
import com.apex.agent.core.tools.mcp.McpManager
import com.apex.agent.core.tools.mcp.McpServerConfig
import com.apex.agent.core.tools.skill.SafeZipExtractor
import com.apex.agent.core.tools.skill.SkillActivationStore
import com.apex.agent.core.tools.skill.SkillRegistry
import com.apex.agent.github.GithubTokenManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 市场统一安装管道
 *
 * 把来自不同来源（内置模板 / URL / JSON 内容 / 魔搭 / GitHub / ClawHub）的安装请求
 * 分发到对应的注册表：
 * - Skill      → [SkillRegistry]（`<id>.json` manifest + `<id>/` 资源目录）
 * - MCP        → [McpManager]（mcp_servers.json）
 * - 连接器      → [ConnectorRegistry]（connectors.json）
 *
 * 所有方法返回 Result，错误信息可直接展示给用户。
 * v2：网络路径全部 withContext(Dispatchers.IO) + 响应体大小上限；
 * JSON 转义补齐换行/回车/制表符（PR45 初版只转义 \\ 与 \"，多行
 * description 必然写坏 manifest）。
 */
@Singleton
class MarketInstallManager @Inject constructor(
    @ApplicationContext private val context: android.content.Context,
    private val skillRegistry: SkillRegistry,
    private val skillActivation: SkillActivationStore,
    private val mcpManager: McpManager,
    private val connectorRegistry: ConnectorRegistry,
    private val httpClient: OkHttpClient,
    private val modelScopeSource: ModelScopeSource,
    private val clawHubSource: ClawHubSource,
    private val githubTokenManager: GithubTokenManager,
    // GitHub MCP 探测源 + Registry 安装器：「从 GitHub 安装」的 MCP 分支
    //（Anthropic 官方 modelcontextprotocol/servers 等仓库直装）。
    // 依赖无环：RegistryMcpInstaller 只依赖 McpManager + 沙箱执行器。
    private val githubMcpSource: GithubMcpSource,
    private val registryMcpInstaller: RegistryMcpInstaller
) {
    private val json = Json { ignoreUnknownKeys = true }

    data class GitHubRepoHit(
        val fullName: String,
        val description: String,
        val stars: Int,
        val htmlUrl: String
    )

    // ═══ Skill：JSON 内容安装（全部安装路径的收口点：URL/模板/文件/魔搭/GitHub/ClawHub 都汇到这里）═══
    // 市场热加载闭环：install → registry 落盘 + changes 广播（工具表/目录热更）
    // → 这里再进激活集 → 下一轮系统提示词即携带新技能方法论，零重启零手动。
    //
    // P1（市场审计）：安装确认门禁 —— 市场加载的 Skill JSON 是不可信第三方内容
    // （promptInjection 直接进下一轮系统提示词），落盘+自动激活前给用户一次目检。
    // 宿主（MarketViewModel）经 registerInstallConfirmGate 注册 UI 确认实现。
    //
    // #347 P2 修复（双 ViewModel 互踩）：旧实现是单个 @Volatile 变量——市场页
    // 返回栈复入（A 未销毁又建 B）时 B 的 init 无条件覆盖 gate、B 的
    // onCleared 无条件置 null，存活的 A 与所有单例控制器（自持
    // SupervisorJob scope）的后续安装全部绕过门禁。改为身份注册表
    // [InstallConfirmGateRegistry]（独立成类，JVM 可直测）：后注册者生效
    // （弹窗归最上层 VM），注销只移除自己（当前生效门禁注销后自动回退到
    // 仍存活的次新注册者）。
    //
    // #347 fail-closed（与 main 修复融合）：注册表为空（无任何存活注册者）时
    // [installConfirmGate] 为 null，安装路径**拒绝**而不是放行 —— 宁可明确
    // 报错引导重新发起，也不允许不可信内容绕过目检直接落盘（null 永不等于
    // 放行）。注册表注销回退解决互踩，fail-closed 兜底零注册者。
    private val confirmGateRegistry = InstallConfirmGateRegistry()

    /** 当前生效的确认门禁（最后注册者）；无注册者时 null → 安装路径 fail-closed 拒绝。 */
    val installConfirmGate: (suspend (ManifestPreview) -> Boolean)?
        get() = confirmGateRegistry.current

    /**
     * 注册确认门禁（MarketViewModel init 调用）。按身份幂等（同实例重复
     * 注册只入表一次）；注册即成为当前生效门禁。
     */
    fun registerInstallConfirmGate(gate: (suspend (ManifestPreview) -> Boolean)) {
        confirmGateRegistry.register(gate)
    }

    /**
     * 按身份注销确认门禁（MarketViewModel onCleared 调用，#347）—— 只拆
     * 自己那一份：被注销者是当前生效门禁时自动回退到仍存活的次新注册者，
     * 不影响其他存活 ViewModel 的门禁。
     */
    fun unregisterInstallConfirmGate(gate: (suspend (ManifestPreview) -> Boolean)?) {
        confirmGateRegistry.unregister(gate)
    }

    /** 用户在确认对话框点了取消（调用方可据此静默或给出友好文案，区别于真失败）。 */
    class InstallCancelledByUser : Exception("已取消安装，未写入任何内容")

    suspend fun installSkillFromJson(
        content: String,
        trusted: Boolean = false,
        /**
         * 安装默认启用工位（"agent" / "coding"；null = 双工位同开，旧行为）。
         * 市场分级隔离：从 Coding 市场装的技能默认只在 Coding 工位启用（另一侧
         * 去对应市场自行开启）；升级重装则保留用户既有 per-scope 启停态。
         */
        defaultScope: String? = null
    ): Result<String> =
        withContext(Dispatchers.IO) {
            val gate = installConfirmGate
            if (!trusted) {
                if (gate == null) {
                    // #347 fail-closed：确认通道缺失 = 拒绝，绝不静默跳过门禁
                    return@withContext Result.failure(
                        Exception("确认通道未初始化：请从市场页面重新发起安装")
                    )
                }
                val preview = dryRunInstall(content).getOrNull()
                    ?: return@withContext Result.failure(
                        Exception("manifest 解析失败，已拒绝安装（无法预览的内容不盲装）")
                    )
                if (!gate(preview)) {
                    return@withContext Result.failure(InstallCancelledByUser())
                }
            }
            // per-scope 默认启用工位判定：安装前取既有条目（install 会重置为双开）
            val parsed = runCatching {
                json.decodeFromString<com.apex.agent.core.tools.skill.SkillManifest>(content)
            }.getOrNull()
            val existing = parsed?.let { skillRegistry.get(it.id) }
            val scoped = defaultScope == SkillRegistry.SCOPE_AGENT || defaultScope == SkillRegistry.SCOPE_CODING
            skillRegistry.install(content).map { manifest ->
                // 安装即装备：用户从市场装技能的意图就是要用；不激活的话
                // 渐进披露目录里只是多一行摘要，方法论仍是「未装载」。
                skillActivation.activate(manifest.id)
                if (scoped) {
                    if (existing != null) {
                        // 升级重装：保留用户既有 per-scope 启停态
                        if (!existing.enabledAgent) {
                            skillRegistry.setEnabledForScope(manifest.id, SkillRegistry.SCOPE_AGENT, false)
                        }
                        if (!existing.enabledCoding) {
                            skillRegistry.setEnabledForScope(manifest.id, SkillRegistry.SCOPE_CODING, false)
                        }
                    } else {
                        // 新装：默认只在安装来源分级启用（双工位隔离）
                        val other = if (defaultScope == SkillRegistry.SCOPE_AGENT) {
                            SkillRegistry.SCOPE_CODING
                        } else {
                            SkillRegistry.SCOPE_AGENT
                        }
                        skillRegistry.setEnabledForScope(manifest.id, other, false)
                    }
                }
                "已安装并装备 Skill：${manifest.name}（${manifest.id}），下一轮对话即生效"
            }
        }

    /**
     * 干运行安装：解析 manifest 但不写入磁盘。供市场详情页「安装前预览」使用。
     *
     * 返回 [ManifestPreview]：解析后的 manifest 摘要 + 缺失依赖 + 工具数 + 权限要求。
     * 不修改任何状态，可安全反复调用。
     */
    suspend fun dryRunInstall(content: String): Result<ManifestPreview> =
        withContext(Dispatchers.IO) {
            runCatching {
                val manifest = json.decodeFromString<
                    com.apex.agent.core.tools.skill.SkillManifest
                >(content)
                val installedIds = skillRegistry.getInstalled().map { it.manifest.id }.toSet()
                val missing = com.apex.agent.core.tools.skill.SkillDependencyResolver
                    .validateDependencies(manifest, installedIds)
                ManifestPreview(
                    id = manifest.id,
                    name = manifest.name,
                    version = manifest.version,
                    description = manifest.description,
                    author = manifest.author,
                    license = manifest.license,
                    category = manifest.category,
                    tags = manifest.tags,
                    trustLevel = manifest.trustLevel,
                    toolCount = manifest.tools.size,
                    hasPromptInjection = manifest.promptInjection != null,
                    missingDependencies = missing,
                    requirements = manifest.requirements.permissions +
                        manifest.requirements.toolsRequired,
                    privilegeLevel = manifest.requirements.privilegeLevel,
                    isInstalled = manifest.id in installedIds
                )
            }
        }

    /** 干运行预览结果。 */
    data class ManifestPreview(
        val id: String,
        val name: String,
        val version: String,
        val description: String,
        val author: String,
        val license: String,
        val category: String?,
        val tags: List<String>,
        val trustLevel: String,
        val toolCount: Int,
        val hasPromptInjection: Boolean,
        val missingDependencies: List<String>,
        val requirements: List<String>,
        val privilegeLevel: String,
        val isInstalled: Boolean
    ) {
        /** 是否可安全安装（无缺失依赖）。 */
        val canInstall: Boolean get() = missingDependencies.isEmpty()
    }

    // ═══ Skill：URL 安装（IO 线程 + 2MB 上限）═══
    suspend fun installSkillFromUrl(url: String, defaultScope: String? = null): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "ApexAgent/1.0")
                    .build()
                httpClient.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) throw Exception("HTTP ${resp.code} 下载失败")
                    val bytes = resp.body?.byteStream()?.use { stream ->
                        stream.readBytesLimited(MAX_DOWNLOAD_BYTES)
                    } ?: throw Exception("空响应")
                    if (bytes == null) throw Exception("文件过大（>${MAX_DOWNLOAD_BYTES / 1024 / 1024}MB）")
                    String(bytes, Charsets.UTF_8)
                }
            }.fold(
                onSuccess = { content -> installSkillFromJson(content, defaultScope = defaultScope) },
                onFailure = { Result.failure(Exception("下载失败：${it.message}")) }
            )
        }

    // ═══ Skill：内置模板安装 ═══
    suspend fun installSkillTemplate(templateId: String): Result<String> {
        val manifestJson = when (templateId) {
            "coding_principles" -> SkillInstallTool.CODING_PRINCIPLES_TEMPLATE
            "web_scraper" -> SkillInstallTool.WEB_SCRAPER_TEMPLATE
            "file_organizer" -> SkillInstallTool.FILE_ORGANIZER_TEMPLATE
            "code_runner" -> SkillInstallTool.CODE_RUNNER_TEMPLATE
            "data_analyzer" -> SkillInstallTool.DATA_ANALYZER_TEMPLATE
            else -> return Result.failure(Exception("未知模板 $templateId"))
        }
        // 内置模板是应用自带可信内容，跳过安装确认门禁（弹窗即骚扰）。
        return installSkillFromJson(manifestJson, trusted = true)
    }

    // ═══ Skill：本地文件导入（SAF Uri → .zip / .json 自动识别）═══
    //
    // 用户反馈"Skills 不能自己导入"：此前市场页只有 GitHub / URL / 粘贴 JSON 三个
    // 联网或手工入口，本地 skill 包（zip 打包或单个 manifest.json）无处安放。
    // 识别策略不依赖 SAF 的 mime（不同文件管理器对 zip/json 上报极不可靠）：
    // 读入字节流后按 ZIP 魔数 PK\x03\x04 分流，其余一律按 JSON 文本处理。
    suspend fun installSkillFromFile(uri: android.net.Uri): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val bytes = context.contentResolver.openInputStream(uri)?.use { stream ->
                    stream.readBytesLimited(MAX_LOCAL_FILE_BYTES)
                } ?: throw Exception("无法读取所选文件（uri=$uri）")
                if (bytes == null) throw Exception("文件过大（>${MAX_LOCAL_FILE_BYTES / 1024 / 1024}MB）")

                val isZip = bytes.size >= 4 &&
                    bytes[0] == 'P'.code.toByte() &&
                    bytes[1] == 'K'.code.toByte() &&
                    bytes[2] == 3.toByte() &&
                    bytes[3] == 4.toByte()

                if (isZip) {
                    // 复制到 cacheDir 临时文件后走 SafeZipExtractor 管道（路径穿越/zip bomb 防御）
                    val tmp = File(context.cacheDir, "import-skill-${System.nanoTime()}.zip")
                    try {
                        tmp.writeBytes(bytes)
                        skillRegistry.installFromZip(tmp).fold(
                            onSuccess = { "已安装 Skill：${it.name}（${it.id}，来自本地 ZIP）" },
                            onFailure = { throw it }
                        )
                    } finally {
                        runCatching { tmp.delete() }
                    }
                } else {
                    val content = String(bytes, Charsets.UTF_8)
                    // P1：本地 JSON 同样过门禁（不可信内容统一确认链）；改走收口点
                    // installSkillFromJson —— 顺带补齐此前缺失的「安装即装备」激活
                    // （旧直连 skillRegistry.install 装完不激活，目录里只是多一行摘要）。
                    installSkillFromJson(content).fold(
                        onSuccess = { it },
                        onFailure = { throw it }
                    )
                }
            }.fold(
                onSuccess = { Result.success(it) },
                onFailure = { Result.failure(Exception("本地导入失败：${it.message}", it)) }
            )
        }

    /** 读取本地文本文件（MCP 配置导入用，2MB 上限）。 */
    suspend fun readTextFile(uri: android.net.Uri): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val bytes = context.contentResolver.openInputStream(uri)?.use { stream ->
                    stream.readBytesLimited(MAX_DOWNLOAD_BYTES)
                } ?: throw Exception("无法读取所选文件（uri=$uri）")
                if (bytes == null) throw Exception("文件过大（>${MAX_DOWNLOAD_BYTES / 1024 / 1024}MB）")
                String(bytes, Charsets.UTF_8)
            }
        }

    // ═══ Skill：魔搭 SKILL.md → apex-skill-v1（prompt 型）═══
    suspend fun installModelScopeSkill(
        skill: ModelScopeSource.ModelScopeSkill,
        defaultScope: String? = null
    ): Result<String> = ModelScopeSkillInstaller(
        skillsDir = skillHomeDir(),
        skillRegistry = skillRegistry,
        modelScopeSource = modelScopeSource,
        installManifest = { manifest, scope -> installSkillFromJson(manifest, defaultScope = scope) }
    ).install(skill, defaultScope)

    // ═══ Skill：ClawHub 仓库 ZIP → apex-skill-v1（prompt 型）═══

    /**
     * 从 ClawHub（clawhub.ai）安装技能。
     *
     * 流程（参照 [installModelScopeSkill] 的「源负责网络、本类负责编排」分层）：
     * 1. 下载技能 ZIP（[ClawHubSource.downloadSkillZip]，20MB 上限）；
     * 2. SafeZipExtractor 解压到临时目录（路径穿越 / zip bomb 防御）；
     * 3. 解析 SKILL.md frontmatter（name / description / license）；
     * 4. references/scripts 等资源复制到 skillsDir/ch-<id>/ 资源目录
     *    （SkillRegistry.uninstall 会连该目录一并清理）；
     * 5. 构建 prompt 型 manifest（promptInjection = SKILL.md 全文）并安装。
     */
    suspend fun installClawHubSkill(
        entry: ClawHubSource.ClawHubSkillEntry,
        defaultScope: String? = null
    ): Result<String> =
        withContext(Dispatchers.IO) {
            val installId = ClawHubSource.installIdFor(entry.slug)

            // 重复安装友好拦截（SkillRegistry.install 会静默覆盖，不能依赖它报错）
            val alreadyInstalled = skillRegistry.getInstalled().any { it.manifest.id == installId }
            if (alreadyInstalled) {
                return@withContext Result.failure(
                    Exception("该技能已安装（$installId）；如需重新安装，请先在「已安装管理」中卸载")
                )
            }

            // 1. 下载 ZIP
            val zipBytes = clawHubSource.downloadSkillZip(entry).getOrElse {
                return@withContext Result.failure(Exception("ClawHub 安装包下载失败：${it.message}"))
            }

            // 2. 解压到临时目录
            val tmpZip = File(context.cacheDir, "clawhub-${System.nanoTime()}.zip")
            val extractDir = File(context.cacheDir, "clawhub-extract-${System.nanoTime()}")
            try {
                tmpZip.writeBytes(zipBytes)
                try {
                    SafeZipExtractor.extract(tmpZip, extractDir)
                } catch (e: Exception) {
                    return@withContext Result.failure(Exception("安装包解压失败：${e.message}"))
                }

                // 3. 定位 SKILL.md（ZIP 内通常嵌套在 <slug>/ 顶层目录下，多候选时取最浅层）
                val skillMd = extractDir.walkTopDown()
                    .filter { it.isFile && it.name.equals("SKILL.md", ignoreCase = true) }
                    .minByOrNull { it.canonicalPath.count { c -> c == File.separatorChar } }
                    ?: return@withContext Result.failure(
                        Exception("安装包中未找到 SKILL.md（该技能可能不符合 Agent Skills 格式）")
                    )
                val markdown = try {
                    skillMd.readText(Charsets.UTF_8)
                } catch (e: Exception) {
                    return@withContext Result.failure(Exception("SKILL.md 读取失败：${e.message}"))
                }
                val frontmatter = parseSkillFrontmatter(markdown)

                // 4. 资源落盘：SKILL.md 所在目录视为技能根，其余文件保持相对路径复制
                val skillRoot = skillMd.parentFile ?: extractDir
                val rootPrefix = skillRoot.canonicalPath + File.separator
                val skillHome = File(skillHomeDir(), installId).apply { mkdirs() }
                val skillMdCanonical = skillMd.canonicalPath
                extractDir.walkTopDown()
                    .filter { it.isFile && it.canonicalPath != skillMdCanonical }
                    .forEach { file ->
                        val canonical = file.canonicalPath
                        if (!canonical.startsWith(rootPrefix)) return@forEach
                        val rel = canonical.removePrefix(rootPrefix)
                        if (rel.isBlank()) return@forEach
                        val target = File(skillHome, rel)
                        // 路径穿越防御：目标必须仍在资源目录内
                        if (!target.canonicalPath.startsWith(skillHome.canonicalPath + File.separator)) return@forEach
                        target.parentFile?.mkdirs()
                        runCatching { file.copyTo(target, overwrite = true) }
                    }

                // 5. prompt 型 manifest（SKILL.md 全文注入）
                val manifest = promptManifest(
                    id = installId,
                    name = entry.displayName.ifBlank { frontmatter.first ?: entry.slug },
                    description = entry.summary.ifBlank { frontmatter.second.orEmpty() }
                        .ifBlank { "ClawHub 技能 ${entry.slug}（来自 clawhub.ai）" },
                    author = "clawhub:${entry.owner}",
                    markdown = markdown,
                    homepage = entry.homepage,
                    license = frontmatter.third,
                    trustLevel = if (entry.official) "verified" else "community",
                    tags = entry.categories
                )

                // 6. 安装；失败时回收资源目录，避免孤儿文件
                installSkillFromJson(manifest, defaultScope = defaultScope).also { result ->
                    if (result.isFailure) runCatching { skillHome.deleteRecursively() }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 取消不吞（结构化并发语义）
                throw e
            } catch (e: Exception) {
                // 磁盘 IO 等意外异常统一转友好失败，不向调用协程抛出
                Result.failure(Exception("ClawHub 安装失败：${e.message}"))
            } finally {
                runCatching { tmpZip.delete() }
                runCatching { extractDir.deleteRecursively() }
            }
        }

    // ═══ GitHub 仓库安装（真实联网，技能形态严格校验 + MCP / 技能集合智能路由）═══

    /**
     * GitHub 仓库安装（纯技能分支，旧调用方兼容）。
     *
     * 旧实现只试 `main` 分支下的 5 个候选路径，绝大多数第三方仓库打不到 ——
     * 按钮点了永远是「仓库中未找到可安装的 manifest」，与市场期望不符（摆设）。
     * 现在的策略：
     *  1. 先查 GitHub API 拿到**真实默认分支**（有的仓库只有 master）；
     *  2. manifest 候选路径扩到常见约定（含 `.apex/` 子目录）；
     *  3. 找不到 `apex-skill-v1` manifest 时，读取 `SKILL.md` / `skill.md`
     *     —— 必须含 Agent Skills 规范的 YAML frontmatter（name 必填）才
     *     转成 prompt 型技能安装。
     *
     * diag-20261011 修复（用户反馈「从 GitHub 安装的不是技能，有什么装什么」）：
     * 旧实现把 `README.md` 也列为 markdown 兜底路径 —— GitHub 上几乎每个仓库
     * 都有 README，于是**任意仓库**都能被转成 prompt 型"技能"装入，README
     * 全文经 promptInjection 直接注入对话系统提示词（既是静默假安装，也是
     * 提示词注入安全洞）。现在：
     *  - README.md 不再参与安装；
     *  - markdown 路径要求 frontmatter（Agent Skills 规范的技能声明）；
     *  - manifest 判定收紧为必须 `schema == "apex-skill-v1"`（旧的
     *    id+name+tools 三键兜底会把大量普通 JSON 当 manifest）；
     *  - 不满足形态 → 明确报错（与 strings_market 文案承诺一致：
     *    「仓库里没有这些内容时会明确报错，不会静默假安装」）。
     */
    suspend fun installSkillFromGitHubRepo(owner: String, repo: String): Result<String> =
        withContext(Dispatchers.IO) {
            val branch = resolveDefaultBranch(owner, repo) ?: "main"
            // distinct：默认分支为 main 时避免重复探测同一分支（10 个候选路径
            // 全部重复 404，浪费请求与时间）
            val probe = probeRootSkill(owner, repo, listOf(branch, "main", "master").distinct())
            if (probe != null) {
                return@withContext installProbeResult(probe)
            }
            Result.failure(Exception(githubNoSkillMessage(owner, repo)))
        }

    /** 根技能探测命中结果（manifest JSON 或 SKILL.md 文本二选一，由[probeRootSkill]产出）。 */
    private sealed interface RootSkillProbe {
        val branch: String

        data class ManifestFound(override val branch: String, val json: String) : RootSkillProbe

        data class SkillDocFound(
            override val branch: String,
            val owner: String,
            val repo: String,
            val markdown: String,
            val fmName: String,
            val fmDescription: String?,
            val fmLicense: String?
        ) : RootSkillProbe
    }

    /**
     * 根路径技能探测（不改任何状态）：manifest 候选 → SKILL.md frontmatter。
     * 命中返回 [RootSkillProbe]；未命中返回 null（调用方继续 MCP / 技能集合分支）。
     */
    // suspend：内部经 #339 镜像回退拉取（RawGitHubMirrors.fetchWithFallback 为
    // 挂起实现）；两个调用方（installSkillFromGitHubRepo / installSmartFromGithub）
    // 均已在 withContext(Dispatchers.IO) 内。
    private suspend fun probeRootSkill(owner: String, repo: String, branches: List<String>): RootSkillProbe? {
        val manifestPaths = listOf(
            "manifest.json", "skill.json", "apex-skill.json", "apex_skill.json",
            "$repo.json", "skills/$repo.json", ".apex/skill.json", "skill/manifest.json"
        )
        val skillDocPaths = listOf("SKILL.md", "skill.md")

        for (branch in branches) {
            for (path in manifestPaths) {
                val content = tryDownload(
                    "https://raw.githubusercontent.com/$owner/$repo/$branch/$path"
                ) ?: continue
                if (looksLikeApexManifest(content)) {
                    return RootSkillProbe.ManifestFound(branch, content)
                }
            }
            for (path in skillDocPaths) {
                val markdown = tryDownload(
                    "https://raw.githubusercontent.com/$owner/$repo/$branch/$path"
                ) ?: continue
                if (markdown.isBlank()) continue
                // 技能形态判定：Agent Skills 规范要求 SKILL.md 带含
                // name/description 的 YAML frontmatter —— 没有即普通
                // markdown 仓库，不装（README 式「有什么装什么」终结）。
                val (fmName, fmDescription, fmLicense) = parseSkillFrontmatter(markdown)
                if (fmName.isNullOrBlank()) continue
                return RootSkillProbe.SkillDocFound(
                    branch, owner, repo, markdown, fmName, fmDescription, fmLicense
                )
            }
        }
        return null
    }

    /** 把根技能探测命中结果送入统一安装管道。 */
    private suspend fun installProbeResult(probe: RootSkillProbe): Result<String> = when (probe) {
        is RootSkillProbe.ManifestFound -> installSkillFromJson(probe.json)
        is RootSkillProbe.SkillDocFound -> {
            val (heading, headingDesc) = parseMarkdownHeading(probe.markdown, fallbackName = probe.fmName)
            installSkillFromJson(
                promptManifest(
                    id = "gh-${probe.owner.lowercase()}-${probe.repo.lowercase()}",
                    name = heading,
                    description = probe.fmDescription ?: headingDesc,
                    author = "github:${probe.owner}",
                    markdown = probe.markdown,
                    homepage = "https://github.com/${probe.owner}/${probe.repo}",
                    license = probe.fmLicense
                )
            )
        }
    }

    /**
     * 从任意形式的 GitHub 输入安装（纯技能分支，旧调用方兼容）：
     * `owner/repo` / 完整 https 链接 / git@ SSH 链接。
     */
    suspend fun installSkillFromRepoInput(input: String): Result<String> {
        val spec = parseGithubInput(input) ?: return Result.failure(parseGithubInputError(input))
        return installSkillFromGitHubRepo(spec.first, spec.second)
    }

    // ═══ GitHub 智能安装（技能 ⇄ MCP ⇄ 技能集合 内容类型路由）═══
    //
    // 背景（用户诉求「市场能安装 Anthropic 的 MCP」）：GitHub 上有两类
    // 官方形态此前装不上 ——
    //  1. MCP server 仓库（modelcontextprotocol/servers：根 .mcp.json 或
    //     src 一级子目录 package.json 带 mcpName/bin）；
    //  2. 技能集合仓库（anthropics/skills：skills 一级子目录各含 SKILL.md）。
    // 旧入口只认「根技能形态」，这两类仓库一律报「不是可安装的技能」。
    //
    // 智能路由（探测优先级 = 安装确定性从高到低）：
    //  1. 根技能形态（apex-skill-v1 manifest / 根 SKILL.md frontmatter）→
    //     原路径直装（零行为回归 —— 单技能仓库行为与 diag-20261011 修复
    //     后完全一致）；
    //  2. MCP 形态（根 server.json / 根 .mcp.json / src monorepo）→
    //     RegistryServer → [RegistryMcpInstaller] 决策树（远端直装 /
    //     npm 沙箱预装 + 回滚 / 诚实 UNSUPPORTED）；
    //  3. 技能集合形态（子目录 SKILL.md 带 frontmatter）→ 候选列表由
    //     用户选择安装（全部 / 单个）；
    //  4. 全部未命中 → 明确报错（列支持形态，绝不假安装）。
    //  命中多个候选（>1）时返回 PickNeeded —— 单候选直装不打扰。

    /** GitHub 仓库中单个可安装候选（技能或 MCP server）。 */
    data class GithubRepoCandidate(
        val kind: Kind,
        val name: String,
        val description: String,
        /** SKILL：manifest 安装 id（gh-owner-repo-subdir 形态）。 */
        val skillId: String? = null,
        /** SKILL：SKILL.md 的 raw 绝对 URL。 */
        val skillMarkdownUrl: String? = null,
        /** SKILL：仓库归属（author 字段与 id 去重用）。 */
        val skillOwner: String? = null,
        /**
         * SKILL：安装时 manifest scope 盖章（null = all 双工位）。
         * GitHub 精选 coding 技能传 "coding" —— 只进 Coding 市场 / Coding 屏。
         */
        val scope: String? = null,
        /** MCP：安装决策树输入。 */
        val server: RegistryServer? = null
    ) {
        enum class Kind { SKILL, MCP }

        /** 候选列表 key（去重与 LazyColumn key 用）。 */
        val key: String get() = "${kind.name}:${skillId ?: server?.key ?: name}"
    }

    /** 智能安装结果：直接装完，或需要用户从候选中选择。 */
    sealed interface GithubInstallOutcome {
        data class Done(val message: String) : GithubInstallOutcome

        data class PickNeeded(
            val repoTitle: String,
            val candidates: List<GithubRepoCandidate>
        ) : GithubInstallOutcome
    }

    /**
     * 智能安装入口：按内容类型自动路由。多候选时返回 [GithubInstallOutcome.PickNeeded]
     *（VM 置入 UI 状态弹候选列表）；单候选直接装完返回 Done。
     */
    suspend fun installSmartFromGithub(input: String): Result<GithubInstallOutcome> {
        val spec = parseGithubInput(input) ?: return Result.failure(parseGithubInputError(input))
        val (owner, repo) = spec
        return withContext(Dispatchers.IO) {
            // ① 根技能形态（单技能仓库 —— 行为与旧版严格一致）
            val branches = listOfNotNull(resolveDefaultBranch(owner, repo), "main", "master").distinct()
            val rootSkill = probeRootSkill(owner, repo, branches)
            if (rootSkill != null) {
                return@withContext installProbeResult(rootSkill)
                    .map { GithubInstallOutcome.Done(it) }
            }

            // ② MCP 形态：server.json / .mcp.json / src monorepo
            val branch = branches.firstOrNull() ?: "main"
            val mcpServers = githubMcpSource.detectServers(owner, repo, listOf(branch))
                .getOrNull().orEmpty()
            if (mcpServers.isNotEmpty()) {
                return@withContext routeMcpServers(owner, repo, mcpServers)
            }

            // ③ 技能集合形态（子目录 SKILL.md，anthropics/skills 血统）
            val skillCandidates = discoverSkillCollectionCandidates(owner, repo, branch)
            if (skillCandidates.isNotEmpty()) {
                if (skillCandidates.size == 1) {
                    return@withContext installGithubCandidate(skillCandidates.first())
                        .map { GithubInstallOutcome.Done(it) }
                }
                return@withContext Result.success(
                    GithubInstallOutcome.PickNeeded(
                        repoTitle = "$owner/$repo",
                        candidates = skillCandidates
                    )
                )
            }

            // ④ 全部未命中 —— 诚实报错（列支持形态，绝不假安装）
            Result.failure(
                Exception(
                    "仓库 $owner/$repo 未探测到可安装内容。支持三种形态：" +
                        "① 技能（根 apex-skill-v1 manifest 或带 frontmatter 的 SKILL.md）；" +
                        "② MCP server（根 server.json / .mcp.json，或 src 一级子目录含 mcpName 的 " +
                        "package.json，如 modelcontextprotocol/servers）；" +
                        "③ 技能集合（子目录各含 SKILL.md，如 anthropics/skills）。" +
                        "普通仓库（仅 README）不会假安装。"
                )
            )
        }
    }

    /** MCP 候选路由：单台直装，多台交用户选择。 */
    private suspend fun routeMcpServers(
        owner: String,
        repo: String,
        servers: List<RegistryServer>
    ): Result<GithubInstallOutcome> {
        if (servers.size == 1) {
            return registryMcpInstaller.install(servers.first())
                .map { GithubInstallOutcome.Done(it) }
        }
        return Result.success(
            GithubInstallOutcome.PickNeeded(
                repoTitle = "$owner/$repo",
                candidates = servers.map { server ->
                    GithubRepoCandidate(
                        kind = GithubRepoCandidate.Kind.MCP,
                        name = server.displayName,
                        description = server.description,
                        server = server
                    )
                }
            )
        )
    }

    /**
     * 技能集合候选发现：仓库树 → 子目录 SKILL.md（深度 1-2，非根）→
     * 逐个拉取验 frontmatter（name 必填 —— 与根 SKILL.md 同一严格门槛，
     * 普通 markdown 仓库不进候选）。上限 [MAX_SKILL_COLLECTION_CANDIDATES]。
     */
    private suspend fun discoverSkillCollectionCandidates(
        owner: String,
        repo: String,
        branch: String
    ): List<GithubRepoCandidate> {
        val paths = githubMcpSource.fetchRepoTreePaths(owner, repo, branch) ?: return emptyList()
        val skillDocPaths = paths.filter { path ->
            val depth = path.count { it == '/' }
            (depth == 1 || depth == 2) &&
                path.substringAfterLast('/') == "SKILL.md" &&
                path.substringBeforeLast('/').isNotBlank()
        }.take(MAX_SKILL_COLLECTION_CANDIDATES)
        val candidates = mutableListOf<GithubRepoCandidate>()
        for (path in skillDocPaths) {
            val markdown = tryDownload(
                "https://raw.githubusercontent.com/$owner/$repo/$branch/$path"
            ) ?: continue
            if (markdown.isBlank()) continue
            val (fmName, fmDescription, _) = parseSkillFrontmatter(markdown)
            if (fmName.isNullOrBlank()) continue
            val subdir = path.substringBeforeLast('/')
            val (_, headingDesc) = parseMarkdownHeading(markdown, fallbackName = fmName)
            candidates += GithubRepoCandidate(
                kind = GithubRepoCandidate.Kind.SKILL,
                name = fmName,
                description = (fmDescription ?: headingDesc).take(160),
                skillId = "gh-${owner.lowercase()}-${repo.lowercase()}-${subdir.lowercase().replace('/', '-')}",
                skillMarkdownUrl = "https://raw.githubusercontent.com/$owner/$repo/$branch/$path",
                skillOwner = owner,
                server = null
            )
            // license frontmatter 不暂存 —— 安装时从 markdown 全量重读
        }
        // P2：skillId 按 subdir 归一化（a-b 与 a/b 的子目录都会折叠成 a-b）——
        // 同仓同名候选按 id 去重，防 LazyColumn 撞 key 崩溃
        return candidates.distinctBy { it.skillId }
    }

    /** 安装单个 GitHub 候选（用户在候选列表点选，或单候选直装；GitHub 精选专栏同管线）。 */
    suspend fun installGithubCandidate(
        candidate: GithubRepoCandidate,
        defaultScope: String? = null
    ): Result<String> =
        withContext(Dispatchers.IO) {
            when (candidate.kind) {
                GithubRepoCandidate.Kind.MCP -> {
                    val server = candidate.server
                        ?: return@withContext Result.failure(Exception("候选缺少 MCP server 元数据"))
                    registryMcpInstaller.install(server)
                }
                GithubRepoCandidate.Kind.SKILL -> installGithubSkillCandidate(candidate, defaultScope)
            }
        }

    /** 技能候选安装：拉 SKILL.md → frontmatter 严格校验 → prompt 型安装。 */
    private suspend fun installGithubSkillCandidate(
        candidate: GithubRepoCandidate,
        defaultScope: String? = null
    ): Result<String> {
        val markdown = candidate.skillMarkdownUrl?.let { tryDownload(it) }
            ?: return Result.failure(Exception("技能文档下载失败（${candidate.name}）"))
        val (fmName, fmDescription, fmLicense) = parseSkillFrontmatter(markdown)
        if (fmName.isNullOrBlank()) {
            return Result.failure(
                Exception("SKILL.md 缺少 name frontmatter —— 不满足技能形态，拒绝安装")
            )
        }
        val (heading, headingDesc) = parseMarkdownHeading(markdown, fallbackName = fmName)
        val owner = candidate.skillOwner ?: "github"
        val id = candidate.skillId ?: "gh-${owner.lowercase()}-${heading.lowercase().replace(Regex("[^a-z0-9-]"), "-")}"
        // homepage 指向仓库页（与根技能路径口径一致）；raw SKILL.md 直链
        // 反推：raw.githubusercontent.com/owner/repo/branch/path → github.com/owner/repo
        val repoHomepage = candidate.skillMarkdownUrl
            ?.removePrefix("https://raw.githubusercontent.com/")
            ?.split('/')
            ?.take(2)
            ?.joinToString("/", prefix = "https://github.com/")
        return installSkillFromJson(
            promptManifest(
                id = id,
                name = heading,
                description = fmDescription ?: headingDesc,
                author = "github:$owner",
                markdown = markdown,
                homepage = repoHomepage ?: candidate.skillMarkdownUrl,
                license = fmLicense,
                scope = candidate.scope
            ),
            defaultScope = defaultScope
        )
    }

    /** 批量安装候选（用户点「全部安装」）：逐个顺序装，失败项点名不中断。 */
    suspend fun installAllGithubCandidates(
        candidates: List<GithubRepoCandidate>
    ): Result<String> = withContext(Dispatchers.IO) {
        val installed = mutableListOf<String>()
        val failed = mutableListOf<String>()
        for (candidate in candidates) {
            installGithubCandidate(candidate).fold(
                onSuccess = { installed += candidate.name },
                onFailure = { failed += "${candidate.name}（${it.message}）" }
            )
        }
        buildString {
            append("批量安装完成：成功 ${installed.size}、失败 ${failed.size}")
            if (installed.isNotEmpty()) append("。成功：${installed.joinToString("、")}")
            if (failed.isNotEmpty()) append("。失败：${failed.joinToString("、")}")
        }.let { Result.success(it) }
    }

    /** 解析 GitHub 输入为 owner/repo。 */
    private fun parseGithubInput(input: String): Pair<String, String>? {
        val raw = input.trim().trimEnd('/')
        if (raw.isBlank()) return null
        return parseRepoSpec(raw)
    }

    private fun parseGithubInputError(input: String): Exception =
        Exception(if (input.isBlank()) "请输入 owner/repo 或 GitHub 链接" else "无法解析仓库地址：$input")

    /** 根技能形态未命中的错误文案（纯技能分支口径）。 */
    private fun githubNoSkillMessage(owner: String, repo: String): String =
        "仓库 $owner/$repo 不是可安装的技能（根路径无 apex-skill-v1 manifest 或带" +
            " frontmatter 的 SKILL.md）。如需安装 MCP server 或技能集合，" +
            "请使用智能安装入口（也支持 modelcontextprotocol/servers 与 anthropics/skills）。"

    /** 解析仓库归属：支持 owner/repo、https://github.com/owner/repo[.git]、git@github.com:owner/repo.git。 */
    private fun parseRepoSpec(input: String): Pair<String, String>? {
        val cleaned = input
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("git@github.com:")
            .removePrefix("github.com/")
            .removeSuffix(".git")
        val parts = cleaned.split('/').filter { it.isNotBlank() }
        if (parts.size < 2) return null
        val owner = parts[0]
        val repo = parts[1]
        if (!owner.matches(Regex("[A-Za-z0-9._-]+")) || !repo.matches(Regex("[A-Za-z0-9._-]+"))) return null
        return owner to repo
    }

    /** 查询仓库真实默认分支；失败返回 null（由调用方回退到 main/master）。 */
    private fun resolveDefaultBranch(owner: String, repo: String): String? = runCatching {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "ApexAgent/1.0")
            .apply { githubTokenManager.getToken()?.let { header("Authorization", "Bearer $it") } }
            .build()
        httpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            // #345：流式限读（与同文件其他下载点同口径）—— 旧 `body?.string()`
            // 先整体读入内存，异常网关返回超大响应即 OOM。
            val bodyBytes = resp.body?.byteStream()?.use { it.readBytesLimited(MAX_DOWNLOAD_BYTES) }
                ?: return@use null
            json.parseToJsonElement(String(bodyBytes, Charsets.UTF_8))
                .jsonObject["default_branch"]?.jsonPrimitive?.contentOrNull
        }
    }.getOrNull()

    /**
     * 解析 SKILL.md 的 YAML frontmatter（name / description / license）。
     *
     * 只做简单键值行解析（与 ModelScopeSource.fetchFrontmatter 同风格），
     * 只匹配顶格键（列 0）——避免误抓 metadata 等嵌套块内的同名字段；
     * 值两侧的成对引号（"…" / '…'）会被剥掉。
     */
    private fun parseSkillFrontmatter(markdown: String): Triple<String?, String?, String?> {
        val lines = markdown.lines()
        if (lines.isEmpty() || lines.first().trim() != "---") {
            return Triple(null, null, null)
        }
        val end = lines.drop(1).indexOfFirst { it.trim() == "---" }
        val front = if (end >= 0) lines.subList(1, 1 + end) else lines.drop(1)
        var name: String? = null
        var description: String? = null
        var license: String? = null
        for (line in front) {
            when {
                name == null && line.startsWith("name:") ->
                    name = stripQuotes(line.removePrefix("name:"))
                description == null && line.startsWith("description:") ->
                    description = stripQuotes(line.removePrefix("description:"))
                license == null && line.startsWith("license:") ->
                    license = stripQuotes(line.removePrefix("license:"))
            }
        }
        return Triple(name, description, license)
    }

    /** 剥掉值两侧的成对引号。 */
    private fun stripQuotes(value: String): String {
        val t = value.trim()
        return when {
            t.length >= 2 && t.startsWith("\"") && t.endsWith("\"") -> t.substring(1, t.length - 1)
            t.length >= 2 && t.startsWith("'") && t.endsWith("'") -> t.substring(1, t.length - 1)
            else -> t
        }
    }

    /** 从 markdown 里取首个一级标题当名字，紧跟的第一段非空文字当描述。 */
    private fun parseMarkdownHeading(markdown: String, fallbackName: String): Pair<String, String> {
        val lines = markdown.lineSequence().map { it.trim() }.toList()
        val heading = lines.firstOrNull { it.startsWith("# ") }
            ?.removePrefix("# ")?.trim()?.takeIf { it.isNotBlank() }
            ?: fallbackName
        val headingIdx = lines.indexOfFirst { it.startsWith("# ") }
        val description = if (headingIdx >= 0) {
            lines.drop(headingIdx + 1)
                .firstOrNull { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("---") }
                ?.take(120)
                .orEmpty()
        } else {
            lines.firstOrNull { it.isNotBlank() && !it.startsWith("---") }?.take(120).orEmpty()
        }
        return heading to description.ifBlank { "$fallbackName（GitHub 仓库转换）" }
    }

    /** 构造 prompt 型 apex-skill-v1 manifest（SKILL.md 全量注入；可选市场元数据）。 */
    private fun promptManifest(
        id: String,
        name: String,
        description: String,
        author: String,
        markdown: String,
        homepage: String? = null,
        license: String? = null,
        trustLevel: String? = null,
        tags: List<String> = emptyList(),
        /** 工位作用域盖章（null = all 双工位；GitHub 精选 coding 技能传 "coding"）。 */
        scope: String? = null
    ): String = buildString {
        append("{\n")
        append("\"schema\":\"apex-skill-v1\",\n")
        append("\"id\":\"${escapeJson(id)}\",\n")
        append("\"name\":\"${escapeJson(name)}\",\n")
        append("\"version\":\"1.0.0\",\n")
        append("\"description\":\"${escapeJson(description)}\",\n")
        append("\"author\":\"${escapeJson(author)}\",\n")
        if (license != null) append("\"license\":\"${escapeJson(license)}\",\n")
        if (homepage != null) append("\"homepage\":\"${escapeJson(homepage)}\",\n")
        if (trustLevel != null) append("\"trustLevel\":\"${escapeJson(trustLevel)}\",\n")
        if (tags.isNotEmpty()) {
            append("\"tags\":[")
            append(tags.joinToString(",") { "\"${escapeJson(it)}\"" })
            append("],\n")
        }
        if (scope != null) append("\"scope\":\"${escapeJson(scope)}\",\n")
        append("\"promptInjection\":\"${escapeJson(markdown)}\",\n")
        append("\"tools\":[],\n")
        append("\"configuration\":{\"autoSetup\":[]}\n")
        append("}")
    }

    // ═══ GitHub 仓库搜索（市场"集成"页）═══
    suspend fun searchGitHubSkills(query: String): Result<List<GitHubRepoHit>> =
        withContext(Dispatchers.IO) {
            try {
                val encoded = URLEncoder.encode(query, "UTF-8")
                val request = Request.Builder()
                    .url("https://api.github.com/search/repositories?q=$encoded&sort=stars&per_page=20")
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "ApexAgent/1.0")
                    .apply { githubTokenManager.getToken()?.let { header("Authorization", "Bearer $it") } }
                    .build()
                httpClient.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        return@withContext Result.failure(Exception("GitHub 搜索失败: HTTP ${resp.code}"))
                    }
                    // #345：流式限读（2MB，与同文件其他下载点同口径）——
                    // 旧 `body?.string()` 无上限整体读入；空体与超限分别报错。
                    val bodyBytes = resp.body?.byteStream()
                        ?: return@withContext Result.failure(
                            Exception("GitHub 搜索失败: HTTP ${resp.code}（空响应体）")
                        )
                    val bounded = bodyBytes.use { it.readBytesLimited(MAX_DOWNLOAD_BYTES) }
                    if (bounded == null) {
                        return@withContext Result.failure(
                            Exception("GitHub 搜索失败: 响应体超过 ${MAX_DOWNLOAD_BYTES / 1024 / 1024}MB 上限")
                        )
                    }
                    val root = json.parseToJsonElement(String(bounded, Charsets.UTF_8)).jsonObject
                    val hits = root["items"]?.jsonArray?.mapNotNull { item ->
                        val obj = item.jsonObject
                        GitHubRepoHit(
                            fullName = obj["full_name"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                            description = obj["description"]?.jsonPrimitive?.contentOrNull ?: "",
                            stars = obj["stargazers_count"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                            htmlUrl = obj["html_url"]?.jsonPrimitive?.content ?: ""
                        )
                    } ?: emptyList()
                    Result.success(hits)
                }
            } catch (e: Exception) {
                Result.failure(Exception("GitHub 搜索异常: ${e.message}"))
            }
        }

    // ═══ MCP ═══
    suspend fun addMcpServer(config: McpServerConfig): Result<String> {
        return mcpManager.addServer(config).map { "已添加 MCP 服务器：${config.name}" }
    }

    // ═══ 连接器 ═══
    fun addConnector(def: ConnectorDef): Result<String> {
        return connectorRegistry.add(def).map { "已添加连接器：${def.name}" }
    }

    // ─── 内部 ───

    private fun skillHomeDir(): File {
        // SkillRegistry 持久化在 filesDir/skills；魔搭资源目录挂在其下
        val dir = File(context.filesDir, "skills")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    // #339：raw.githubusercontent.com 直连在国内大面积不可达 —— 统一走
    // 镜像 fallback（jsdelivr 三级 CDN → 直连；非 raw URL 原样直取）。
    // 失败（全部源不可达）返回 null，由调用方继续尝试下一候选路径。
    private suspend fun tryDownload(url: String): String? = try {
        val bytes = RawGitHubMirrors.fetchWithFallback(
            client = httpClient,
            rawUrl = url,
            maxBytes = MAX_DOWNLOAD_BYTES
        )
        String(bytes, Charsets.UTF_8)
    } catch (e: IOException) {
        null
    }

    /** 读取至多 [max] 字节；超限返回 null。 */
    private fun java.io.InputStream.readBytesLimited(max: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0
        while (total < max) {
            val n = read(buf, 0, minOf(buf.size, max - total))
            if (n < 0) break
            out.write(buf, 0, n)
            total += n
        }
        if (total >= max && read() >= 0) return null
        return out.toByteArray()
    }

    private fun looksLikeApexManifest(content: String): Boolean = runCatching {
        // diag-20261011：收紧为必须显式声明 apex-skill-v1 —— 旧的 id+name+tools
        // 三键兜底会把任意带这些键的普通 JSON（package 清单等）当技能装入。
        json.parseToJsonElement(content).jsonObject["schema"]?.jsonPrimitive?.content == "apex-skill-v1"
    }.getOrDefault(false)

    /** 完整 JSON 字符串转义（含换行/回车/制表符）。 */
    private fun escapeJson(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")

    companion object {
        private const val MAX_DOWNLOAD_BYTES = 2 * 1024 * 1024

        /** 本地 skill 包上限：ZIP 可能携带 scripts/references 资源，放宽到 20MB。 */
        private const val MAX_LOCAL_FILE_BYTES = 20 * 1024 * 1024

        /** 技能集合候选上限（防恶意仓库塞爆候选列表）。 */
        private const val MAX_SKILL_COLLECTION_CANDIDATES = 48
    }
}
