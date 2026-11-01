package com.apex.agent.marketplace

import com.apex.agent.core.tools.marketplace.ModelScopeSource
import com.apex.agent.core.tools.skill.SkillRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 魔搭安装编排（#354 事务化 GREEN）；宿主传入现有带确认门的 manifest 安装入口，
 * JVM 测试直接执行此生产代码。
 *
 * 事务语义（对照 ModelScopeSkillInstallerTest 六条回归）：
 * 1. **重复安装前置拒绝**：registry 已登记 → 直接失败，不进确认门、不下载、
 *    不写盘（旧实现会带着确认门一路走到覆盖安装，用户原 manifest 与资源被静默替换）；
 * 2. **暂存目录**：全部资源先落 `ms-<id>.staging`，绝不直接写 `ms-<id>/` ——
 *    确认门拒绝 / 取消 / 下载失败的任何路径都零接触既有用户数据；
 * 3. **拒绝半成品**：任一资源下载失败 → 整体中止，不安装残缺技能
 *    （旧实现 resourceFailed 只追加一句提示照常安装）；
 * 4. **确认通过才发布**：登记成功后把暂存资源合并进 skillHome
 *    （只覆盖同名文件，其余用户文件原样保留）；
 * 5. **取消传播**：CancellationException 原实例重抛（结构化并发纪律），
 *    暂存目录总被回收；
 * 6. **零残留**：全新安装被拒 / 失败 → 不留任何目录（旧实现拒绝后连
 *    既有资源目录一起 deleteRecursively，正是 #354 的数据损失根因）。
 */
internal class ModelScopeSkillInstaller(
    private val skillsDir: File,
    private val skillRegistry: SkillRegistry,
    private val modelScopeSource: ModelScopeSource,
    private val installManifest: suspend (String, String?) -> Result<String>
) {
    suspend fun install(
        skill: ModelScopeSource.ModelScopeSkill,
        defaultScope: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        val skillId = "ms-${skill.id}"
        val skillHome = File(skillsDir, skillId)

        // ① 重复安装前置拒绝：已登记技能不进确认门、不做任何下载 / 写盘
        if (skillRegistry.get(skillId) != null) {
            return@withContext Result.failure(
                Exception("技能 $skillId 已安装，重复安装已拒绝；如需重装请先卸载")
            )
        }

        val markdown = modelScopeSource.fetchSkillMarkdown(skill).getOrElse {
            return@withContext Result.failure(Exception("SKILL.md 下载失败：${it.message}"))
        }

        // ② 资源全部先落暂存目录：任何失败路径都不触碰既有 ms-<id>/ 用户数据
        val staging = File(skillsDir, "$skillId.staging")
        runCatching { staging.deleteRecursively() } // 上次中断的残骸清理（幂等）
        try {
            if (!staging.mkdirs()) {
                return@withContext Result.failure(Exception("无法创建暂存目录：$staging"))
            }

            // ③ 逐个下载到暂存；任一失败 → 整体中止（拒绝安装半成品技能）
            val resourceFiles = skill.files.filter { it != skill.path && !it.endsWith("/") }
            for (filePath in resourceFiles) {
                val content = modelScopeSource.fetchSkillResource(skill, filePath).getOrElse {
                    return@withContext Result.failure(
                        Exception("资源文件下载失败（$filePath）：${it.message} —— 已中止安装"
                        )
                    )
                }
                val rel = filePath.removePrefix("skills/${skill.id}/")
                if (rel.isBlank() || rel.contains("..")) continue
                val target = File(staging, rel)
                // 路径穿越防御：目标必须仍在暂存目录内
                if (!target.canonicalPath.startsWith(staging.canonicalPath + File.separator)) continue
                target.parentFile?.mkdirs()
                target.writeText(content)
            }

            // 构建 prompt 型 manifest：promptInjection = SKILL.md 全文
            val manifest = buildString {
                append("{\n")
                append("\"schema\":\"apex-skill-v1\",\n")
                append("\"id\":\"${escapeJson(skillId)}\",\n")
                append("\"name\":\"${escapeJson(skill.name)}\",\n")
                append("\"version\":\"1.0.0\",\n")
                append("\"description\":\"${escapeJson(skill.description)}\",\n")
                append("\"author\":\"modelscope\",\n")
                append("\"promptInjection\":\"${escapeJson(markdown)}\",\n")
                append("\"tools\":[],\n")
                append("\"configuration\":{\"autoSetup\":[]}\n")
                append("}")
            }

            // ④ 确认门：拒绝 / 失败 → 只回收暂存（finally），既有 skillHome 零接触
            val registered = installManifest(manifest, defaultScope)
            if (registered.isFailure) {
                return@withContext registered
            }

            // ⑤ 确认通过 → 发布暂存资源进 skillHome（合并：仅覆盖同名文件）
            val allPublished = publishStaged(staging, skillHome)
            if (allPublished) registered else registered.map { msg -> "$msg（部分资源文件发布失败）" }
        } catch (e: CancellationException) {
            // 取消不吞（原实例重抛）；暂存回收交给 finally
            throw e
        } catch (e: Exception) {
            // 磁盘 IO 等意外异常统一转友好失败；暂存回收交给 finally
            Result.failure(Exception("魔搭技能安装失败：${e.message}"))
        } finally {
            // 幂等兜底：成功路径 publishStaged 后暂存已清；失败 / 取消 / 早退路径必清
            runCatching { staging.deleteRecursively() }
        }
    }

    /**
     * 把暂存资源复制进 skillHome（合并语义：只覆盖同名文件，skillHome 里
     * 其余用户文件原样保留）。单文件失败不炸整体——技能已登记，回报
     * 「部分资源发布失败」由调用方提示；返回是否全部成功。
     */
    private fun publishStaged(staging: File, skillHome: File): Boolean {
        if (!staging.isDirectory) return true
        var allOk = true
        staging.walkTopDown().filter { it.isFile }.forEach { src ->
            val rel = src.toRelativeString(staging)
            val dst = File(skillHome, rel)
            // 路径穿越防御（staging 内容入场时已校验，此处对合并目标再兜底）
            if (!dst.canonicalPath.startsWith(skillHome.canonicalPath + File.separator)) {
                return@forEach
            }
            try {
                dst.parentFile?.mkdirs()
                src.copyTo(dst, overwrite = true)
            } catch (e: Exception) {
                allOk = false
            }
        }
        return allOk
    }

    private fun escapeJson(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
}
