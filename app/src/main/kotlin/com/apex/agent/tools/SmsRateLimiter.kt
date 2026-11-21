package com.apex.agent.tools

/**
 * ═══ 短信发送限流器（issue #342）═══
 *
 * 背景：sms_send 是 HIGH 风险工具中唯一有直接金钱后果的（每条真实计费），
 * 会话级放行（allow_session）后模型可在会话内无限次发送——prompt injection
 * 或任务失控场景下可向 106 开头等付费号段连发。本限流器在宿主实际发送前
 * 做双闸门：
 *
 * - **滑动窗口**：任意 60 秒内最多 [maxPerMinute] 条（时间戳 ArrayDeque，
 *   每次调用先淘汰滑出窗口的旧记录）；
 * - **重复内容去重**：相同（收件号 + 归一化正文）在 [duplicateWindowMs]
 *   内重复发送直接拒绝。指纹 = 去格式化手机号 + 空白折叠（换行 / 连续
 *   空格视为同一）后的正文。
 *
 * 记账时机：tryAcquire 通过即计入额度（无论后续 SmsManager 是否发送成功）
 * ——失败重试同样消耗额度，防「故意失败 + 无限重试」绕过限流。
 *
 * 并发：synchronized 单锁保护两个结构（短信发送频率极低，锁竞争可忽略）；
 * 时钟经构造器注入（JVM 单测用假钟，无 flake）。
 */
class SmsRateLimiter(
    private val maxPerMinute: Int = 5,
    private val duplicateWindowMs: Long = 10 * 60_000L,
    private val clock: () -> Long = System::currentTimeMillis
) {

    /** 限流判定结果。 */
    sealed class Result {
        /** 放行（额度已计入）。 */
        object Allowed : Result()

        /** 拒绝：[reason] 为可直接拼进错误文案的原因短语（不含句号）。 */
        data class Denied(val reason: String) : Result()
    }

    private val lock = Any()

    /** 滑动窗口内的发送时间戳（升序）。 */
    private val sendTimestamps = ArrayDeque<Long>()

    /** 重复内容指纹 → 最近一次放行时间（滑出窗口的条目每次调用时清理）。 */
    private val recentFingerprints = LinkedHashMap<String, Long>()

    /**
     * 尝试取得发送额度：先查重复内容（更具体的原因优先报告），再查滑动
     * 窗口；通过即记账并返回 [Result.Allowed]。
     */
    fun tryAcquire(phone: String, message: String): Result {
        val now = clock()
        val fingerprint = fingerprint(phone, message)
        synchronized(lock) {
            pruneLocked(now)
            val lastSentAt = recentFingerprints[fingerprint]
            if (lastSentAt != null && now - lastSentAt < duplicateWindowMs) {
                return Result.Denied(
                    "相同内容 ${duplicateWindowMs / 60_000} 分钟内不会重复发送（同一收件人+相同正文）"
                )
            }
            if (sendTimestamps.size >= maxPerMinute) {
                return Result.Denied("每分钟最多 $maxPerMinute 条（本分钟额度已用完）")
            }
            sendTimestamps.addLast(now)
            recentFingerprints[fingerprint] = now
            return Result.Allowed
        }
    }

    /** 清理两个结构里已滑出窗口的旧记录（须持 [lock] 调用）。 */
    private fun pruneLocked(now: Long) {
        while (sendTimestamps.isNotEmpty() && now - sendTimestamps.first() >= WINDOW_MS) {
            sendTimestamps.removeFirst()
        }
        recentFingerprints.entries.removeAll { now - it.value >= duplicateWindowMs }
    }

    /** 重复内容指纹：去格式化手机号 + 空白折叠后的正文。 */
    private fun fingerprint(phone: String, message: String): String {
        val normalizedPhone = phone.filter { it.isDigit() || it == '+' }
        val normalizedMessage = message.trim().replace(WHITESPACE_RUN, " ")
        return "$normalizedPhone|$normalizedMessage"
    }

    private companion object {
        /** 滑动窗口长度（毫秒）。 */
        const val WINDOW_MS = 60_000L

        /** 连续空白（含换行 / 制表符）折叠为单空格。 */
        val WHITESPACE_RUN = Regex("\\s+")
    }
}
