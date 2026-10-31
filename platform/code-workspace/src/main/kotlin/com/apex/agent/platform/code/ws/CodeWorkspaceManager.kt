package com.apex.agent.platform.code.ws

import com.apex.agent.core.codetools.CodeWorkspaceRoots
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/** 工作区索引（清单 + 激活 id）—— 状态流对外暴露的公开类型。 */
@Serializable
data class CodeWorkspaceIndex(
    val workspaces: List<CodeWorkspace> = emptyList(),
    val activeId: String? = null
)

/**
 * # Code Workspace Manager — 编码工作区生命周期管理
 *
 * 职责：
 * 1. **创建/列出/删除**工作区（目录 + JSON 索引 `code_workspaces/index.json`）；
 * 2. **环境探测**（build.gradle.kts → Gradle/Kotlin、package.json → Node、
 *    pyproject.toml → Python…）—— 目录文件标记嗅探，零进程开销；
 * 3. **激活态**（[activeRoot] 实现 [CodeWorkspaceRoots] 契约）—— @Volatile 原子
 *    切换，code_* 工具即时跟随；
 * 4. **激活持久化**（`index.json` 里的 activeId，重启后恢复上次工作区）。
 *
 * 与 [com.apex.agent.platform.terminal.workspace.LinuxWorkspaceManager] 的关系：
 * 共享同一父目录（`<filesDir>/linux/workspaces/`）但索引独立 —— 终端侧的
 * workspace 生命周期工具（TerminalWorkspaces）与本管理器互不干扰，同一物理
 * 目录可以被两边引用（这就是文件层互用）。
 */
class CodeWorkspaceManager(
    private val workspacesBaseDir: File,
    private val indexDir: File
) : CodeWorkspaceRoots {

    @Serializable
    private data class Index(
        val workspaces: List<CodeWorkspace> = emptyList(),
        val activeId: String? = null
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val indexFile: File get() = File(indexDir, "index.json")

    /** 全量状态（工作区清单 + 激活 id）。 */
    private val _state = MutableStateFlow(loadIndex().toPublic())

    /** 公开状态流（快照型，避免私有类型泄漏）。 */
    val state: StateFlow<CodeWorkspaceIndex> get() = _state

    private fun Index.toPublic() = CodeWorkspaceIndex(workspaces = workspaces, activeId = activeId)

    private fun CodeWorkspaceIndex.toPrivate() = Index(workspaces = workspaces, activeId = activeId)

    private val activeRootRef = AtomicReference<File?>(null)

    init {
        workspacesBaseDir.mkdirs()
        indexDir.mkdirs()
        ensureSystemWorkspace()
        // 恢复激活：索引里的 activeId 对应目录存在则生效
        val restored = _state.value
        val active = restored.workspaces.firstOrNull { it.workspaceId == restored.activeId }
        if (active != null && File(active.hostRootPath).isDirectory) {
            activeRootRef.set(File(active.hostRootPath))
        } else if (restored.workspaces.isEmpty()) {
            // 首启默认 = 整个系统（2026-10 用户需求：工作区不圈定目录，
            // 默认即全系统；需要真实文件夹时走「选择文件夹」导入）
            activate(SYSTEM_ID)
        } else {
            val first = restored.workspaces.firstOrNull()
            if (first != null) activate(first.workspaceId) else activate(SYSTEM_ID)
        }
    }

    // ── CodeWorkspaceRoots 契约 ──────────────────────────────────────

    override fun activeRoot(): File? = activeRootRef.get()

    /** 当前激活工作区（null = 无）。 */
    fun activeWorkspace(): CodeWorkspace? {
        val s = _state.value
        return s.workspaces.firstOrNull { it.workspaceId == s.activeId }
    }

    // ── 生命周期 API ─────────────────────────────────────────────────

    /**
     * 创建新工作区。
     *
     * @return 新建的工作区；重名时返回 null（幂等拒绝，不静默复用）。
     */
    fun create(name: String): CodeWorkspace? {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.length > 64) return null
        // #454：十六进制 id 是机器可读格式 —— Locale.ROOT 与工具层惯例一致。
        val id = slugify(trimmed) + "-" + String.format(Locale.ROOT, "%x", System.currentTimeMillis() and 0xFFFFFF)
        val dir = File(workspacesBaseDir, id)
        if (dir.exists()) return null
        if (!dir.mkdirs()) return null

        val ws = CodeWorkspace(
            workspaceId = id,
            name = trimmed,
            hostRootPath = dir.canonicalPath,
            createdAt = System.currentTimeMillis()
        )
        val detected = detectEnvironment(dir)
        val withEnv = ws.copy(
            detectedEnvironment = detected.summary,
            detectedLanguages = detected.languages,
            buildSystem = detected.buildSystem
        )
        updateIndex { it.copy(workspaces = it.workspaces + withEnv) }
        AppLogger.instance.info(LogCategory.SYSTEM, TAG, "Code workspace created: $id ($trimmed)")
        return withEnv
    }

    /**
     * 删除工作区（索引条目 + 自建目录）。激活中的工作区被删则回落（system
     * 优先兜底）。导入型工作区（用户真实文件夹）只解绑索引 —— 绝不删除
     * 用户目录；system 工作区（根 = /）恒不可删。
     */
    fun delete(workspaceId: String): Boolean {
        if (workspaceId == SYSTEM_ID) return false
        val current = _state.value
        val target = current.workspaces.firstOrNull { it.workspaceId == workspaceId } ?: return false

        // 只删本管理器自建的目录（workspacesBaseDir 之下）；导入的用户
        // 真实文件夹仅解绑 —— deleteRecursively 走到用户目录是不可接受的事故。
        val dir = File(target.hostRootPath)
        val baseCanonical = workspacesBaseDir.canonicalPath
        val dirCanonical = runCatching { dir.canonicalPath }.getOrDefault(dir.path)
        if (dir.exists() && dirCanonical.startsWith(baseCanonical + File.separator)) {
            dir.deleteRecursively()
        }

        val remaining = current.workspaces.filter { it.workspaceId != workspaceId }
        val wasActive = current.activeId == workspaceId
        updateIndex { it.copy(workspaces = remaining, activeId = if (wasActive) null else it.activeId) }
        if (wasActive) {
            activeRootRef.set(null)
            val fallback = remaining.firstOrNull()
            if (fallback != null) activate(fallback.workspaceId) else activate(SYSTEM_ID)
        }
        return true
    }

    /** 激活工作区（更新 lastUsedAt + 持久化 activeId；system 根 = / 恒存在）。 */
    fun activate(workspaceId: String): CodeWorkspace? {
        val current = _state.value
        val target = current.workspaces.firstOrNull { it.workspaceId == workspaceId } ?: return null
        val dir = File(target.hostRootPath)
        if (workspaceId != SYSTEM_ID && !dir.isDirectory) return null

        val touched = target.copy(lastUsedAt = System.currentTimeMillis())
        updateIndex { idx ->
            Index(
                workspaces = idx.workspaces.map { if (it.workspaceId == workspaceId) touched else it },
                activeId = workspaceId
            )
        }
        activeRootRef.set(dir)
        return touched
    }

    /**
     * 导入真实文件夹为工作区（2026-10 用户需求：「选择工作区需要实际去
     * 选择工作文件夹」）。目录必须已存在（SAF 选择器解码出的宿主路径）；
     * 同路径已导入 → 幂等复用（仅激活）；不复制文件、不落新目录、删除
     * 仅解绑索引（见 [delete]）。
     *
     * @param hostPath 宿主侧真实目录路径（如 /storage/emulated/0/MyProject）
     * @param displayName 展示名（null = 取目录名）
     * @return 导入（或复用激活）的工作区；路径不存在/名称非法 → null
     */
    fun importFrom(hostPath: String, displayName: String? = null): CodeWorkspace? {
        val dir = File(hostPath)
        if (!dir.isDirectory) return null
        val canonical = runCatching { dir.canonicalPath }.getOrDefault(hostPath)

        // 同一物理目录已注册 → 直接激活（幂等复用，不重复建条目）
        _state.value.workspaces.firstOrNull { it.hostRootPath == canonical }?.let {
            return activate(it.workspaceId)
        }

        val name = displayName?.trim()?.takeIf { it.isNotEmpty() } ?: dir.name
        if (name.isEmpty() || name.length > 64) return null
        val id = "imported-" + slugify(name) + "-" + String.format(Locale.ROOT, "%x", System.currentTimeMillis() and 0xFFFFFF)
        val detected = detectEnvironment(dir)
        val ws = CodeWorkspace(
            workspaceId = id,
            name = name,
            hostRootPath = canonical,
            createdAt = System.currentTimeMillis(),
            detectedEnvironment = detected.summary,
            detectedLanguages = detected.languages,
            buildSystem = detected.buildSystem
        )
        updateIndex { it.copy(workspaces = it.workspaces + ws) }
        activate(id)
        AppLogger.instance.info(LogCategory.SYSTEM, TAG, "Code workspace imported: $canonical")
        return ws
    }

    /** 全部工作区（列表 UI 用）。 */
    fun list(): List<CodeWorkspace> = _state.value.workspaces

    /** 刷新指定工作区的环境探测（项目结构变化后调用）。 */
    fun refreshEnvironment(workspaceId: String) {
        val current = _state.value
        val target = current.workspaces.firstOrNull { it.workspaceId == workspaceId } ?: return
        val detected = detectEnvironment(File(target.hostRootPath))
        updateIndex { idx ->
            Index(
                workspaces = idx.workspaces.map {
                    if (it.workspaceId == workspaceId) it.copy(
                        detectedEnvironment = detected.summary,
                        detectedLanguages = detected.languages,
                        buildSystem = detected.buildSystem
                    ) else it
                },
                activeId = idx.activeId
            )
        }
    }

    // ── 内部 ─────────────────────────────────────────────────────────

    /**
     * 常驻「整个系统」工作区（id = system，根 = /）—— 2026-10 用户需求：
     * 不选文件夹时默认即整个系统。仅注册索引条目；删除被 [delete] 硬拒
     * （根目录绝不可删），激活被 [activate] 特判（/ 恒存在）。
     */
    private fun ensureSystemWorkspace() {
        val exists = _state.value.workspaces.any { it.workspaceId == SYSTEM_ID }
        if (exists) return
        val ws = CodeWorkspace(
            workspaceId = SYSTEM_ID,
            name = SYSTEM_NAME,
            hostRootPath = "/",
            createdAt = System.currentTimeMillis(),
            detectedEnvironment = null
        )
        updateIndex { it.copy(workspaces = listOf(ws) + it.workspaces) }
    }

    private fun updateIndex(transform: (Index) -> Index) {
        val next = transform(_state.value.toPrivate())
        _state.value = next.toPublic()
        persist(next)
    }

    private fun loadIndex(): Index = try {
        if (indexFile.exists()) {
            Json { ignoreUnknownKeys = true }.decodeFromString(Index.serializer(), indexFile.readText())
        } else Index()
    } catch (e: Exception) {
        runCatching { indexFile.copyTo(File(indexDir, "index.json.corrupt"), overwrite = true) }
        Index()
    }

    private fun persist(index: Index) {
        try {
            val raw = json.encodeToString(Index.serializer(), index)
            val tmp = File(indexDir, "index.json.tmp")
            tmp.writeText(raw)
            if (!tmp.renameTo(indexFile)) {
                indexFile.writeText(raw)
                tmp.delete()
            }
        } catch (e: Exception) {
            AppLogger.instance.warn(LogCategory.SYSTEM, TAG, "persist index failed: ${e.message}")
        }
    }

    private fun slugify(raw: String): String =
        raw.lowercase().replace(Regex("[^a-z0-9\\u4e00-\\u9fa5]+"), "-").trim('-').take(24)
            .ifEmpty { "ws" }

    // ── 环境探测（文件标记嗅探，零进程开销）─────────────────────────

    data class EnvironmentProbe(
        val languages: List<String>,
        val buildSystem: String?,
        val summary: String?
    )

    fun detectEnvironment(root: File): EnvironmentProbe {
        if (!root.isDirectory) return EnvironmentProbe(emptyList(), null, null)
        val langs = mutableListOf<String>()
        var build: String? = null

        val has = { name: String -> File(root, name).exists() }

        when {
            has("build.gradle.kts") || has("build.gradle") || has("settings.gradle.kts") -> {
                build = "gradle"
                if (has("settings.gradle.kts") || File(root, "app").isDirectory) langs += "Kotlin"
                if (File(root, "gradle/wrapper").isDirectory) Unit // wrapper 不算语言
            }
            has("package.json") -> {
                build = "npm"
                if (has("tsconfig.json")) langs += "TypeScript"
                langs += if ("TypeScript" in langs) "JavaScript" else "JavaScript"
            }
            has("pyproject.toml") -> { build = "pip"; langs += "Python" }
            has("requirements.txt") -> { build = if (build == null) "pip" else build; langs += "Python" }
            has("Cargo.toml") -> { build = "cargo"; langs += "Rust" }
            has("go.mod") -> { build = "go"; langs += "Go" }
            has("pom.xml") -> { build = "maven"; langs += "Java" }
            has("CMakeLists.txt") -> { build = "cmake"; langs += "C/C++" }
            has("Makefile") -> { build = build ?: "make" }
        }
        if (has("pubspec.yaml")) { langs += "Dart"; build = build ?: "flutter" }
        if (File(root, ".git").isDirectory && "git" !in langs) {
            // git 不是语言，但 summary 里可以提示仓库存在
        }

        val summary = when {
            langs.isEmpty() && build == null -> if (root.listFiles()?.isEmpty() == true) "空工作区" else null
            langs.isEmpty() -> build
            build == null -> langs.joinToString(" · ")
            else -> "${langs.joinToString(" · ")} · $build"
        }
        return EnvironmentProbe(langs.distinct(), build, summary)
    }

    /** 项目统计摘要（行数/文件数，供系统提示词 JIT 上下文）。 */
    fun projectStats(root: File): String? {
        if (!root.isDirectory) return null
        var files = 0
        var lines = 0L
        fun walk(dir: File, depth: Int) {
            if (depth > 6 || files > 2000) return
            dir.listFiles()?.forEach { f ->
                if (f.isDirectory) {
                    if (f.name !in IGNORED) walk(f, depth + 1)
                } else if (f.extension in CODE_EXTS && f.length() < 1_000_000) {
                    files++
                    if (files <= 2000) lines += runCatching { f.readLines().size.toLong() }.getOrDefault(0L)
                }
            }
        }
        walk(root, 0)
        if (files == 0) return null
        return "$files 个代码文件 · 约 ${lines / 1000}k 行"
    }

    companion object {
        private const val TAG = "CodeWorkspaceManager"

        /** 整个系统工作区 id（根 = /；默认工作区，UI 与删除守卫消费）。 */
        const val SYSTEM_ID = "system"

        /** system 工作区存储名（UI 侧另有本地化显示名覆盖）。 */
        const val SYSTEM_NAME = "Whole System"
        val IGNORED = setOf(".git", ".gradle", "build", "node_modules", ".idea", "__pycache__")
        val CODE_EXTS = setOf("kt", "java", "py", "js", "ts", "tsx", "jsx", "rs", "go", "c", "cpp", "h", "xml", "md")
    }
}
