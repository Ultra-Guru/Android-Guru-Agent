package com.apex.agent.core.tools.extension.dsh

import com.apex.agent.core.tools.extension.ExtensionDirLayout
import kotlinx.serialization.json.Json
import java.io.File

/**
 * # DshCapabilitiesStore —— 能力快照持久化（`profile/dsh/capabilities.json`）
 *
 * 安装/升级后由 DshPluginIntrospector 写入；DshToolRegistrar（注册）、市场
 * （展示）、Doctor（健康）三方共用同一份快照 —— **能力真相只有一个落点**，
 * 不建第二份内存/磁盘镜像。
 *
 * 指纹校验：读回时 [DshCapabilities.inputFingerprint] 与当前 pkg 指纹不一致
 * = 插件已变而能力快照过期 —— 读方拿到 null（诚实过期，不拿旧账注册）。
 */
class DshCapabilitiesStore(private val layout: ExtensionDirLayout) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        prettyPrint = true
    }

    /** 写入能力快照（原子写：tmp + rename）。 */
    fun write(extId: String, capabilities: DshCapabilities): Result<File> = runCatching {
        val file = layout.profileFile(extId, PROFILE_RELATIVE).getOrThrow()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(DshCapabilities.serializer(), capabilities))
        if (file.exists() && !file.delete()) {
            tmp.delete()
            throw IllegalStateException("旧 capabilities.json 删除失败：${file.path}")
        }
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
        file
    }

    /**
     * 读取能力快照。
     *
     * @param currentFingerprint 当前 pkg 内容指纹；null = 不做新鲜度校验
     *   （仅展示场景）。注册场景必须传 —— 过期快照返回 null，绝不拿旧账注册。
     */
    fun read(extId: String, currentFingerprint: String? = null): DshCapabilities? {
        val file = layout.profileFile(extId, PROFILE_RELATIVE).getOrNull() ?: return null
        if (!file.isFile) return null
        val parsed = runCatching {
            json.decodeFromString(DshCapabilities.serializer(), file.readText())
        }.getOrNull() ?: return null
        if (currentFingerprint != null &&
            parsed.inputFingerprint.isNotBlank() &&
            parsed.inputFingerprint != currentFingerprint
        ) {
            return null // 快照过期（插件已升级/篡改）—— 诚实失效
        }
        return parsed
    }

    companion object {
        const val PROFILE_RELATIVE = "dsh/capabilities.json"
    }
}
