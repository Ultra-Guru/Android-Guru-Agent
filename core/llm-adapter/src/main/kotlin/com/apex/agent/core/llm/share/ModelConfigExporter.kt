package com.apex.agent.core.llm.share

import com.apex.agent.core.llm.ModelProfile
import com.apex.agent.core.llm.ProviderConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * # 模型配置导出器（Provider + Profiles → JSON 文本）
 *
 * 导入侧（[ModelConfigImporter]，Python / JSON / YAML / TOML 粘贴即导）的
 * 对称闭环：把一个 [ProviderConfig] 与其挂载的 [ModelProfile] 列表序列化
 * 为**可读 JSON 文本**——跨设备迁移 / 存档 / 交给导入弹窗即恢复。
 *
 * ## 回读保证（export → import 零信息丢失的字段面）
 *
 * 导出键名与 [ModelConfigImporter] 的候选键表严格对齐，自家导入器可完整
 * 回读：provider / base_url / headers / models（条目内 id · name ·
 * context_window · vision · tool_calling · reasoning）。
 *
 * ## API Key 永不导出
 *
 * 与整包备份（BackupManager）同一纪律：密钥**强制脱敏（直接省略
 * api_key 键）**，导出文本可以安全地贴到任何地方；导入后由用户重新填 Key
 * （导入器对缺失 Key 的载荷天然兼容——base_url + models 即通过识别）。
 *
 * 纯 JVM、无 IO；同一输入恒等输出（幂等，可单测回环）。
 */
object ModelConfigExporter {

    /** 人类可读美化输出（默认缩进；贴聊天窗 / 存文件都直接可读）。 */
    private val prettyJson = Json { prettyPrint = true }

    /**
     * 导出一个 Provider 及其全部模型档案。
     *
     * @param provider 服务商配置（displayName / baseUrl / defaultHeaders 导出）
     * @param profiles 全量档案清单（函数内部按 providerId 过滤归属，
     *   modelId 为空的档案跳过——导入侧同样会忽略，导出它只会制造噪声）
     * @return JSON 文本；provider 无 baseUrl 且无有效模型时返回空串
     *   （调用方按空串提示「无可导出内容」）
     */
    fun export(provider: ProviderConfig, profiles: List<ModelProfile>): String {
        val owned = profiles.filter { it.providerId == provider.id && it.modelId.isNotBlank() }
        if (provider.baseUrl.isBlank() && owned.isEmpty()) return ""

        val root = buildJsonObject {
            put("provider", provider.displayName.ifBlank { provider.id })
            if (provider.baseUrl.isNotBlank()) put("base_url", provider.baseUrl)
            val headers = provider.defaultHeaders.filterKeys { it.isNotBlank() }
            if (headers.isNotEmpty()) {
                put("headers", buildJsonObject {
                    headers.forEach { (k, v) -> put(k, v) }
                })
            }
            put("models", buildJsonArray {
                owned.forEach { p ->
                    add(modelEntry(p))
                }
            })
        }
        return prettyJson.encodeToString(JsonObject.serializer(), root)
    }

    /** 单档案 → 模型条目对象（键名对齐 [ModelConfigImporter] 候选键表）。 */
    private fun modelEntry(p: ModelProfile): JsonObject = buildJsonObject {
        put("id", p.modelId)
        put("name", p.name.ifBlank { p.modelId })
        if (p.contextWindow > 0) put("context_window", p.contextWindow)
        put("vision", p.capabilities.vision)
        put("tool_calling", p.capabilities.toolCalling)
        put("reasoning", p.capabilities.reasoning)
    }
}
