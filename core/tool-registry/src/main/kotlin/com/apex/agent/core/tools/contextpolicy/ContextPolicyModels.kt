package com.apex.agent.core.tools.contextpolicy

/**
 * # Context Policy Models — 上下文治理档位（P99）
 *
 * **模块方向**：模型定义在本层（tool-registry）——`ContextPolicyGet` /
 * `ContextPolicySet` 工具住本层，agent-engine → tool-registry 单向依赖
 * （放 engine 会成环，同 P97 PromptModels 的方向修正）；压缩门与引擎
 * 状态组装住 agent-engine，import 本文件类型。
 *
 * 只白名单不任意覆写：[ContextPreset] 九档（keepLastN / maxTokens 成对
 * 绑定）；SYSTEM 段与 PromptSection 骨架永不裁（keepLastN 只作用于非
 * SYSTEM 消息保留层数）；压缩前抢救保护无关闭开关。
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
 * 上下文策略状态快照（ContextPolicyGet 的结构化口径；引擎侧组装同源值）。
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
