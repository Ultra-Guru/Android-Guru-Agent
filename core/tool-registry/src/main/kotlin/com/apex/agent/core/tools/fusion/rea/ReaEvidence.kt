package com.apex.agent.core.tools.fusion.rea

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * # REA 证据信封（Evidence envelope）
 *
 * 上游 REA 的结果契约核心：**每个结论都要带证据与限制**（findings /
 * evidence / limitations / unknowns 四元组）。本实现逐字段对齐该哲学 ——
 * 所有工具结果都渲染为统一 JSON 信封：
 *
 * ```json
 * {
 *   "tool": "inspect_binary_layout",
 *   "target": "D:/apps/example.exe",
 *   "ok": true,
 *   "tier": "on_device",
 *   "findings":  [{"topic": "...", "detail": "..."}],
 *   "evidence":  [{"kind": "bytes", "at": "0x3c", "detail": "PE magic"}],
 *   "limitations": ["..."],
 *   "unknowns":  ["..."],
 *   "digest": "sha256:..."
 * }
 * ```
 *
 * 降级结果（tier 未就绪 / 引擎缺失）不是报错，而是 `ok=false` +
 * `degraded` 段（原因 + 恢复指引）—— 与上游「BYO 引擎，缺失时继续
 * 可做的部分工作」的恢复哲学一致。
 */
object ReaEvidence {

    /** 证据条目（可机读的结构化证据行）。 */
    data class Item(
        val kind: String,      // bytes / manifest / entry / symbol / string / command ...
        val at: String = "",   // 偏移/路径/地址等定位
        val detail: String
    )

    /** 单个发现（面向读者的结论行）。 */
    data class Finding(
        val topic: String,
        val detail: String
    )

    /** 渲染成功信封。 */
    fun success(
        tool: String,
        target: String,
        tier: String,
        findings: List<Finding> = emptyList(),
        evidence: List<Item> = emptyList(),
        limitations: List<String> = emptyList(),
        unknowns: List<String> = emptyList(),
        digest: String = "",
        extra: Map<String, JsonObject> = emptyMap()
    ): JsonObject = buildJsonObject {
        put("tool", tool)
        put("target", target)
        put("ok", true)
        put("tier", tier)
        putJsonArrayFields("findings", findings.map {
            buildJsonObject {
                put("topic", it.topic)
                put("detail", it.detail)
            }
        })
        putJsonArrayFields("evidence", evidence.map {
            buildJsonObject {
                put("kind", it.kind)
                if (it.at.isNotBlank()) put("at", it.at)
                put("detail", it.detail)
            }
        })
        putStrings("limitations", limitations)
        putStrings("unknowns", unknowns)
        if (digest.isNotBlank()) put("digest", digest)
        for ((k, v) in extra) put(k, v)
    }

    /** 渲染降级信封（引擎缺失 / 供应商不可用 —— 非错误，带恢复指引）。 */
    fun degraded(
        tool: String,
        target: String,
        tier: String,
        reason: String,
        recovery: String
    ): JsonObject = buildJsonObject {
        put("tool", tool)
        put("target", target)
        put("ok", false)
        put("tier", tier)
        put("degraded", buildJsonObject {
            put("reason", reason)
            put("recovery", recovery)
        })
        putStrings(
            "limitations",
            listOf("工具契约在目录中保留（与上游 207 工具面对齐），执行待引擎就绪")
        )
    }

    /** 渲染错误信封（真错误：路径不存在 / 参数非法等）。 */
    fun error(tool: String, target: String, message: String): JsonObject = buildJsonObject {
        put("tool", tool)
        put("target", target)
        put("ok", false)
        put("error", message)
    }

    // ── 渲染助手 ──────────────────────────────────────────────────────

    private fun kotlinx.serialization.json.JsonObjectBuilder.putStrings(
        key: String,
        values: List<String>
    ) {
        put(key, buildJsonArray { values.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putJsonArrayFields(
        key: String,
        values: List<JsonObject>
    ) {
        put(key, buildJsonArray { values.forEach { add(it) } })
    }

    /** sha256 摘要（前 16 位 hex，前缀标识）。 */
    fun digestOf(bytes: ByteArray): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val d = md.digest(bytes)
        val hex = d.joinToString("") { "%02x".format(it) }
        return "sha256:${hex.take(16)}"
    }

    /** 文件摘要（流式，大文件不整读）。 */
    fun digestOfFile(file: java.io.File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        val hex = md.digest().joinToString("") { "%02x".format(it) }
        return "sha256:${hex.take(16)}"
    }
}
