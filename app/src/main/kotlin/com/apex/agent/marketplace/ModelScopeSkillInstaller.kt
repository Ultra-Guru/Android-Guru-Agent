package com.apex.agent.marketplace

import com.apex.agent.core.tools.marketplace.ModelScopeSource
import com.apex.agent.core.tools.skill.SkillRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 魔搭安装编排；宿主传入现有带确认门的 manifest 安装入口，JVM 测试直接执行此生产代码。 */
internal class ModelScopeSkillInstaller(
    private val skillsDir: File,
    private val skillRegistry: SkillRegistry,
    private val modelScopeSource: ModelScopeSource,
    private val installManifest: suspend (String, String?) -> Result<String>
) {
    suspend fun install(
        skill: ModelScopeSource.ModelScopeSkill,
        defaultScope: String? = null
    ): Result<String> =
        withContext(Dispatchers.IO) {
            val markdown = modelScopeSource.fetchSkillMarkdown(skill).getOrElse {
                return@withContext Result.failure(Exception("SKILL.md 下载失败：${it.message}"))
            }

            // 资源文件（references/scripts 等）下载到 skillsDir/<id>/ 资源目录
            val resourceFiles = skill.files.filter { it != skill.path && !it.endsWith("/") }
            val skillHome = File(skillsDir, "ms-${skill.id}").apply { mkdirs() }
            var resourceFailed = false
            try {
                for (filePath in resourceFiles) {
                    val content = modelScopeSource.fetchSkillResource(skill, filePath).getOrNull()
                    if (content != null) {
                        val rel = filePath.removePrefix("skills/${skill.id}/")
                        if (rel.isBlank() || rel.contains("..")) continue
                        val target = File(skillHome, rel)
                        // 路径穿越防御：目标必须仍在资源目录内
                        if (!target.canonicalPath.startsWith(skillHome.canonicalPath + File.separator)) continue
                        target.parentFile?.mkdirs()
                        target.writeText(content)
                    } else {
                        resourceFailed = true
                    }
                }

                // 构建 prompt 型 manifest：promptInjection = SKILL.md 全文
                val manifest = buildString {
                    append("{\n")
                    append("\"schema\":\"apex-skill-v1\",\n")
                    append("\"id\":\"ms-${escapeJson(skill.id)}\",\n")
                    append("\"name\":\"${escapeJson(skill.name)}\",\n")
                    append("\"version\":\"1.0.0\",\n")
                    append("\"description\":\"${escapeJson(skill.description)}\",\n")
                    append("\"author\":\"modelscope\",\n")
                    append("\"promptInjection\":\"${escapeJson(markdown)}\",\n")
                    append("\"tools\":[],\n")
                    append("\"configuration\":{\"autoSetup\":[]}\n")
                    append("}")
                }

                // P2（对齐 ClawHub 模式）：登记失败/中途取消时回收资源目录 ——
                // 旧实现无回滚，ms-<id>/ 成永久孤儿（未登记 → uninstall 也清不到它）。
                installManifest(manifest, defaultScope).also { result ->
                    if (result.isFailure) runCatching { skillHome.deleteRecursively() }
                }.map { msg ->
                    if (resourceFailed) "$msg（部分资源文件下载失败）" else msg
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 取消不吞（结构化并发语义）；已落盘的半成品资源一并回收
                runCatching { skillHome.deleteRecursively() }
                throw e
            } catch (e: Exception) {
                // 磁盘 IO 等意外异常统一转友好失败；回收半成品资源目录
                runCatching { skillHome.deleteRecursively() }
                Result.failure(Exception("魔搭技能安装失败：${e.message}"))
            }
        }

    private fun escapeJson(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")

}
