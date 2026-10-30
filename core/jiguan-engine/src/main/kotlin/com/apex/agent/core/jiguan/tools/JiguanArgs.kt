package com.apex.agent.core.jiguan.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * 机关工具参数解析（防御式）—— 仓库「外部输入任何形状都吞得下」纪律：
 * 缺参/类型错位一律 null / 默认值，绝不抛异常给工具层。
 */
internal object JiguanArgs {

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(raw: String): JsonObject? = runCatching {
        json.parseToJsonElement(raw) as? JsonObject
    }.getOrNull()

    fun string(args: JsonObject?, key: String, default: String = ""): String =
        runCatching {
            args?.get(key)?.jsonPrimitive?.contentOrNull
        }.getOrNull() ?: default

    fun int(args: JsonObject?, key: String, default: Int): Int =
        runCatching {
            args?.get(key)?.jsonPrimitive?.intOrNull
        }.getOrNull() ?: default

    fun long(args: JsonObject?, key: String, default: Long): Long =
        runCatching {
            args?.get(key)?.jsonPrimitive?.longOrNull
        }.getOrNull() ?: default

    fun double(args: JsonObject?, key: String, default: Double): Double =
        runCatching {
            args?.get(key)?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
        }.getOrNull() ?: default

    fun bool(args: JsonObject?, key: String, default: Boolean): Boolean =
        runCatching {
            args?.get(key)?.jsonPrimitive?.booleanOrNull
        }.getOrNull() ?: default

    /** 地址解析：十进制 / 0x 十六进制双形态。 */
    fun address(args: JsonObject?, key: String, default: Long = -1L): Long {
        val raw = string(args, key)
        if (raw.isBlank()) return default
        return raw.trim().removePrefix("0x").removePrefix("0X").toLongOrNull(16)
            ?: raw.trim().toLongOrNull()
            ?: default
    }
}
