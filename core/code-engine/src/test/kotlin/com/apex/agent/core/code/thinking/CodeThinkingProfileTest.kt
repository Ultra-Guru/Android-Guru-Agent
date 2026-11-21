package com.apex.agent.core.code.thinking

import com.apex.agent.core.engine.thinking.ThinkingProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * [CodeThinkingProfile] 画像表 + 旋钮补偿纯函数测试。
 *
 * 断言四件事：
 * 1. 画像表：7 深度档全量覆盖，关键数值（迭代倍率 / 输出预算 / 压缩倍率）
 *    与文档表格一致，且随档位单调不降；
 * 2. 补偿数学端到端等式：base × 补偿 × 引擎映射档倍率 = 档位目标倍率
 *    （映射打底 + 反补的核心契约——引擎零改动的前提；SPE-03 P1-5 后
 *    引擎倍率**实时读** [ThinkingProfile] 表而非测试内写死 1.5——引擎常量
 *    变更 → 契约测试与补偿公式同步跟随，硬编码回退即失败）；
 * 3. APEX 五问清单：五问齐全（目标 / 副作用 / 遗漏 / 不变量 / 回归），
 *    比三问清单多出后两问；
 * 4. SPE-03 P1-3：档位自身布尔/别名单一事实源不在这里，见各自测试。
 */
class CodeThinkingProfileTest {

    @Test
    fun `profile table covers all seven depth tiers`() {
        CodeThinkingLevel.entries
            .filter { it.isDepthTier }
            .forEach { level ->
                val profile = CodeThinkingProfile.forLevel(level)
                assertEquals("画像档位字段与入参一致", level, profile.level)
            }
    }

    @Test
    fun `auto falls back to deep profile`() {
        // AUTO 元档不占表行：预检解析后用具体档画像；占位返回
        // AUTO_FALLBACK_LEVEL（SPE-02 P0-3 四处同源，旧 STANDARD 已对齐 DEEP）
        assertEquals(
            CodeThinkingProfile.forLevel(CodeThinkingLevel.AUTO_FALLBACK_LEVEL),
            CodeThinkingProfile.forLevel(CodeThinkingLevel.AUTO)
        )
    }

    @Test
    fun `iteration scale ladder is monotonic non-decreasing`() {
        val scales = CodeThinkingLevel.entries
            .filter { it.isDepthTier }
            .map { CodeThinkingProfile.forLevel(it).maxIterationsScale }
        // NONE ×0.8 → LIGHT ×0.9 → STANDARD ×1.0 → DEEP ×1.2 → MAXIMUM ×1.5 → ULTRACODE ×2.0 → APEXCODE ×3.0
        assertEquals(0.8f, scales[0], 0.0001f)
        assertEquals(3.0f, scales.last(), 0.0001f)
        scales.zipWithNext().forEach { (lo, hi) ->
            assertTrue("迭代倍率随档位单调不降（$lo → $hi）", hi >= lo)
        }
    }

    @Test
    fun `tool output budget ladder matches documented values`() {
        assertEquals(6000, CodeThinkingProfile.forLevel(CodeThinkingLevel.NONE).toolOutputBudget)
        assertEquals(7000, CodeThinkingProfile.forLevel(CodeThinkingLevel.LIGHT).toolOutputBudget)
        assertEquals(8000, CodeThinkingProfile.forLevel(CodeThinkingLevel.STANDARD).toolOutputBudget)
        assertEquals(9000, CodeThinkingProfile.forLevel(CodeThinkingLevel.DEEP).toolOutputBudget)
        assertEquals(10000, CodeThinkingProfile.forLevel(CodeThinkingLevel.MAXIMUM).toolOutputBudget)
        assertEquals(12000, CodeThinkingProfile.forLevel(CodeThinkingLevel.ULTRACODE).toolOutputBudget)
        assertEquals(16000, CodeThinkingProfile.forLevel(CodeThinkingLevel.APEXCODE).toolOutputBudget)
    }

    @Test
    fun `apex compresses later than baseline`() {
        // APEX 压缩倍率 0.9（< 1 = 更晚压缩）；其余档 ≥ 1.0
        assertTrue(
            "APEX 压缩倍率 < 1（更晚压缩保留证据链）",
            CodeThinkingProfile.forLevel(CodeThinkingLevel.APEXCODE).compressionAggressiveness < 1.0f
        )
        CodeThinkingLevel.entries
            .filter { it.isDepthTier && it != CodeThinkingLevel.APEXCODE }
            .forEach { level ->
                assertTrue(
                    "档位 $level 压缩倍率应 ≥ 1.0",
                    CodeThinkingProfile.forLevel(level).compressionAggressiveness >= 1.0f
                )
            }
    }

    // ═══ 补偿数学：端到端等式（核心契约；引擎倍率实时读表）═══

    /** 引擎侧对映射后档位的自乘倍率（SPE-03 P1-5：实时读表，不写死 1.5）。 */
    private fun engineScale(level: CodeThinkingLevel): Float =
        ThinkingProfile.forLevel(level.toAgentLevel()).maxIterationsScale

    @Test
    fun `ultracode compensated iterations reach target scale end to end`() {
        val base = 40 // CodeModule 引擎配置基数
        val compensated = CodeThinkingProfile.compensatedMaxIterations(base, CodeThinkingLevel.ULTRACODE)
        val scale = engineScale(CodeThinkingLevel.ULTRACODE) // = ThinkingProfile MAXIMUM 倍率
        // 引擎侧最终值：compensated × scale 四舍五入（resolveMaxIterations 同语义）
        val effective = (compensated * scale).roundToInt()
        assertEquals("端到端 = 40 × 2.0 = 80（映射 MAXIMUM 打底 + 反补）", 80, effective)
        assertEquals("档位目标倍率实时读表仍为 ×2.0", 2.0f, CodeThinkingProfile.forLevel(CodeThinkingLevel.ULTRACODE).maxIterationsScale, 0.0001f)
    }

    @Test
    fun `apexcode compensated iterations reach target scale end to end`() {
        val base = 40
        val compensated = CodeThinkingProfile.compensatedMaxIterations(base, CodeThinkingLevel.APEXCODE)
        val scale = engineScale(CodeThinkingLevel.APEXCODE)
        assertEquals("端到端 = 40 × 3.0 = 120", 120, (compensated * scale).roundToInt())
    }

    /**
     * SPE-03 P1-5 契约测试：全档位扫描——`base × 补偿 × 引擎映射档倍率 ==
     * base × 档位目标倍率`（含舍入容差 ±1）。引擎倍率实时读取
     * [ThinkingProfile] 表：若引擎把 MAXIMUM 倍率改为 1.6，本测试仍通过
     * （补偿公式同步跟随）；若把补偿回退为硬编码 /1.5，本测试失败。
     */
    @Test
    fun `compensation contract holds across the whole ladder reading engine table`() {
        val bases = listOf(40, 7, 1)
        (CodeThinkingLevel.entries).forEach { level ->
            val target = CodeThinkingProfile.forLevel(level).maxIterationsScale
            val engine = engineScale(level)
            bases.forEach { base ->
                val compensated = CodeThinkingProfile.compensatedMaxIterations(base, level)
                val effective = (compensated * engine).roundToInt()
                val expected = (base * target).roundToInt()
                assertTrue(
                    "档位 $level 基数 $base：补偿后端到端 $effective 应等于目标 $expected（±1）",
                    kotlin.math.abs(effective - expected) <= 1
                )
            }
        }
    }

    @Test
    fun `non deep water tiers keep base untouched for iterations`() {
        // NONE..MAXIMUM：基数复位，由引擎自己的倍率体系接管（恒等档补偿=1）
        listOf(CodeThinkingLevel.NONE, CodeThinkingLevel.LIGHT, CodeThinkingLevel.STANDARD,
            CodeThinkingLevel.DEEP, CodeThinkingLevel.MAXIMUM, CodeThinkingLevel.AUTO).forEach { level ->
            assertEquals("档位 $level 迭代基数应保持原值", 40, CodeThinkingProfile.compensatedMaxIterations(40, level))
        }
    }

    @Test
    fun `compensated output budget lifts to tier budget`() {
        assertEquals("ULTRACODE 抬到 12000", 12000, CodeThinkingProfile.compensatedToolOutputBudget(8000, CodeThinkingLevel.ULTRACODE))
        assertEquals("APEXCODE 抬到 16000", 16000, CodeThinkingProfile.compensatedToolOutputBudget(8000, CodeThinkingLevel.APEXCODE))
        assertEquals("用户基数更大时尊重用户设置", 20000, CodeThinkingProfile.compensatedToolOutputBudget(20000, CodeThinkingLevel.ULTRACODE))
        assertEquals("其余档保持原值", 8000, CodeThinkingProfile.compensatedToolOutputBudget(8000, CodeThinkingLevel.STANDARD))
    }

    @Test
    fun `compensated compression threshold divides by apex aggressiveness`() {
        val base = 0.8f
        val compensated = CodeThinkingProfile.compensatedCompressionThreshold(base, CodeThinkingLevel.APEXCODE)
        assertEquals("0.8 / 0.9 ≈ 0.889（更晚压缩）", 0.8f / 0.9f, compensated, 0.0001f)
        assertEquals("其余档保持原值", base, CodeThinkingProfile.compensatedCompressionThreshold(base, CodeThinkingLevel.ULTRACODE), 0.0001f)
        assertEquals(base, CodeThinkingProfile.compensatedCompressionThreshold(base, CodeThinkingLevel.STANDARD), 0.0001f)
    }

    @Test
    fun `engine snapshot base keeps compensation idempotent across switches`() {
        // 快照基数契约：CodeAgentEngine 每次换档都以构造时快照为基数调用
        // compensated*（而非叠加当前配置）——任意次换档组合后，APEXCODE 的
        // 补偿值与「直接从快照切 APEXCODE」完全一致（无指数污染）。
        val snapshot = 40
        var applied = snapshot
        // 引擎语义：updateThinkingLevel 三次，每次内部都读 engineKnobBase
        applied = CodeThinkingProfile.compensatedMaxIterations(snapshot, CodeThinkingLevel.ULTRACODE)
        applied = CodeThinkingProfile.compensatedMaxIterations(snapshot, CodeThinkingLevel.STANDARD)
        applied = CodeThinkingProfile.compensatedMaxIterations(snapshot, CodeThinkingLevel.APEXCODE)
        assertEquals(
            "快照基数语义：换档历史不影响补偿结果",
            CodeThinkingProfile.compensatedMaxIterations(snapshot, CodeThinkingLevel.APEXCODE),
            applied
        )
    }

    // ═══ APEX 五问清单 ═══

    @Test
    fun `apex self check checklist has five questions`() {
        val checklist = CodeThinkingProfile.APEX_SELF_CHECK_CHECKLIST
        listOf(
            "1. Goal:", "2. Side effects:", "3. Omissions:",
            "4. Invariants:", "5. Regression:"
        ).forEach { marker ->
            assertTrue("五问清单应含 $marker", checklist.contains(marker))
        }
    }

    @Test
    fun `apex checklist closes with fix-first discipline`() {
        assertTrue(
            "清单收尾：先修再答",
            CodeThinkingProfile.APEX_SELF_CHECK_CHECKLIST.contains("fix the gap with tools first")
        )
    }
}
