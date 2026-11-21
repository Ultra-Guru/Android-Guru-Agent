package com.apex.agent.core.code.standard

import com.apex.agent.core.code.thinking.CodeThinkingLevel
import com.apex.standardkit.loop.KitThinkingLevel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 标准线思考档位映射回归锁（[toKit] / [toHost]）。
 *
 * 断言三件事：
 * 1. 七深度档 + AUTO 的枚举名与 KitThinkingLevel 等价直映（八枚举对齐
 *    ——宿主与库两侧行为不漂移的前提）；
 * 2. **AUTO 元档防御性折叠 DEEP**：VM 四线统一预检后引擎不应收到 AUTO，
 *    但任何泄漏路径（历史缓存 / 外部直调）都收敛 DEEP——与
 *    [CodeThinkingLevel.toAgentLevel] 的 AUTO 兜底、织造/信使两适配器
 *    （toWeave / toDispatch）的折叠口径一致（三线防御同款）；
 * 3. toHost 反向直映：库名 → 宿主枚举同名还原（AUTO 不在反向折叠——
 *    库侧 AUTO 只可能是宿主写入的缓存值，原样回显供 UI 判断）。
 */
class StandardKitAdapterMappingTest {

    @Test
    fun `all eight host tiers map to same-name kit tiers`() {
        assertEquals(KitThinkingLevel.NONE, CodeThinkingLevel.NONE.toKit())
        assertEquals(KitThinkingLevel.LIGHT, CodeThinkingLevel.LIGHT.toKit())
        assertEquals(KitThinkingLevel.STANDARD, CodeThinkingLevel.STANDARD.toKit())
        assertEquals(KitThinkingLevel.DEEP, CodeThinkingLevel.DEEP.toKit())
        assertEquals(KitThinkingLevel.MAXIMUM, CodeThinkingLevel.MAXIMUM.toKit())
        assertEquals(
            "深水档在库侧有等价枚举（不再是静默降级）",
            KitThinkingLevel.ULTRACODE, CodeThinkingLevel.ULTRACODE.toKit()
        )
        assertEquals(
            "巅峰档在库侧有等价枚举（不再是静默降级）",
            KitThinkingLevel.APEXCODE, CodeThinkingLevel.APEXCODE.toKit()
        )
    }

    @Test
    fun `auto meta tier folds to deep defensively`() {
        // 预检正常路径不泄漏 AUTO；此处锁的是防御性兜底（与 toAgentLevel 同档）
        assertEquals(KitThinkingLevel.DEEP, CodeThinkingLevel.AUTO.toKit())
    }

    @Test
    fun `kit tiers map back to same-name host tiers`() {
        KitThinkingLevel.entries.forEach { kit ->
            assertEquals(kit.name, CodeThinkingLevel.valueOf(kit.name), kit.toHost())
        }
    }

    @Test
    fun `kit and host enums stay name aligned`() {
        // 两枚举集合同名同尺寸——一旦库侧增删档位，此断言先红，
        // 提醒适配层补映射语义（而不是 valueOf 静默走 getOrDefault 降级）。
        assertEquals(
            "宿主与库侧档位枚举应同名同尺寸",
            CodeThinkingLevel.entries.map { it.name }.toSet(),
            KitThinkingLevel.entries.map { it.name }.toSet()
        )
    }
}
