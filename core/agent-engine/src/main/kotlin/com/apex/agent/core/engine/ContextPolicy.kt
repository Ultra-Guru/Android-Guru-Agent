package com.apex.agent.core.engine

/**
 * # Context Policy — 上下文治理档位（P99）
 *
 * 补上「模型能查询并调节自己上下文」的白名单档位面（对齐 Operit
 * context_limiter_c 的语义，用本仓状态机与压缩门实现）：
 *
 * - **只白名单不任意覆写**：[ContextPreset] 九档（keepLastN / maxTokens
 *   成对绑定），非法档位返回可操作错误（列全允许值）；
 * - **SYSTEM 段与 PromptSection 骨架永不裁**：[ContextPreset.keepLastN]
 *   只影响非 SYSTEM 消息保留层数；「压缩前抢救」（SessionContextArchive）
 *   保护**没有关闭开关**；
 * - **下轮生效**：写入经 `SettingsRepository.updateAgentSettings`（原子
 *   tmp+renameTo），本轮提示词不变；
 * - [ContextPolicyState] 为 `ContextPolicyGet` 的返回口径（compressed /
 *   tokenEstimate / line 由引擎侧组装）。
 *
 * 纯 JVM、无 Android 依赖；真值源 = AgentConfig + 引擎历史。
 */
enum class ContextPreset(val keepLastN: Int, val maxTokens: Int) {
    P3(3, 4_000),
    P5(5, 6_000),
    P8(8, 8_000),
    P10(10, 10_000),
    P15(15, 14_000),
    P20(20, 18_000),
    P30(30, 24_000),
    P50(50, 32_000),
    P100(100, 48_000);

    companion object {
        /** 档位值 → 预设（keepLastN 为唯一键；未命中 → null）。 */
        fun from(raw: Int): ContextPreset? = entries.find { it.keepLastN == raw }

        /** 允许值清单（错误契约用：列全允许值，不裸异常）。 */
        val ALLOWED: List<Int> get() = entries.map { it.keepLastN }
    }
}

/**
 * 上下文策略状态快照（ContextPolicyGet 的结构化口径）。
 *
 * @param preset null = 宿主默认（未开启自定义）
 * @param keepLastN 当前生效的最近轮保护层数（预设或引擎默认）
 * @param maxTokens 当前生效的压缩阈值基数（预设或引擎窗口，取小防越窗）
 * @param compressed 本轮会话是否已发生过压缩（压缩门置位）
 * @param tokenEstimate 当前历史 token 估算（chars/4 口径）
 * @param line 深潜 / 标准 / 织造 / 信使
 * @param exportCapable P97 后恒 true（PromptDump 通道）
 */
data class ContextPolicyState(
    val preset: ContextPreset?,
    val keepLastN: Int,
    val maxTokens: Int,
    val compressed: Boolean,
    val tokenEstimate: Int,
    val line: String,
    val exportCapable: Boolean = true
)
