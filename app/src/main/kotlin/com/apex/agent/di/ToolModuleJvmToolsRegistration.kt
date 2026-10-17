package com.apex.agent.di
import com.apex.agent.core.tools.*
import com.apex.agent.core.engine.ConversationMemory
import com.apex.agent.core.tools.builtin.*
import com.apex.agent.github.tools.*
import com.apex.agent.platform.terminal.tools.*

import com.apex.agent.core.tools.builtin.JsonTransformTool
import com.apex.agent.core.tools.builtin.VersionCompareTool
import com.apex.agent.core.tools.builtin.WaitTool
import com.apex.agent.core.tools.builtin.context.ContextRecapTool
import com.apex.agent.core.tools.builtin.context.ContextSearchTool
import com.apex.agent.core.tools.builtin.context.ConversationMemoryContextProvider
import com.apex.agent.core.tools.builtin.context.SessionContextProvider
import com.apex.agent.core.tools.builtin.context.SessionStatsTool
import com.apex.agent.core.tools.builtin.merged.JsonTool
import com.apex.agent.core.tools.builtin.merged.RandomTool
import com.apex.agent.core.tools.builtin.merged.RegexTool
import com.apex.agent.core.tools.builtin.merged.TimeTool
import java.io.File

/**
 * §9 实用工具 + §14 Tool System v3/v5 新工具的注册体 —— 自 ToolModule.kt
 * 沿 SRP seam 拆出（#290 合并后主文件 1221 行超 1200 预算；本族为
 * 零 Android 依赖的纯 JVM 确定性工具，边界天然清晰，注册内容逐字节原样迁移）。
 */

/** §9：实用工具（v1 两件 + v2 结构化数据/文本/时间 15 件，纯 JVM、离线、确定性）。 */
internal fun registerUtilityTools(
    registry: DefaultToolRegistry,
    workspaceDir: File
) {
    // ═══ 9. 实用工具 (2 v1 + 15 v2) ═══
    registry.register(SafeAgentTool(CalculateTool()))
    registry.register(SafeAgentTool(TextTransformTool()))
    // ── Tool System v2：结构化数据/文本/时间工具（纯 JVM、离线、确定性）──
    // JsonPathLegacy（JSONPath 查询）、RegexExtractLegacy / RegexReplaceLegacy（正则抽取/替换）、
    // TextDiff（Myers diff）、datetime（6 操作）、UuidGenerateLegacy（v4/v7）、
    // FileHash（流式 md5/sha1/sha256/sha512 + 沙箱）
    registry.register(SafeAgentTool(JsonPathTool()))
    registry.register(SafeAgentTool(RegexExtractTool()))
    registry.register(SafeAgentTool(RegexReplaceTool()))
    registry.register(SafeAgentTool(TextDiffTool()))
    registry.register(SafeAgentTool(DateTimeTool()))
    registry.register(SafeAgentTool(UuidGenerateTool()))
    registry.register(SafeAgentTool(FileHashTool(workspaceDir)))
    // CsvQuery（RFC4180 查询/过滤/排序）、BaseConvert（2-36 任意进制 + 前缀探测）、
    // StringDistance（levenshtein/damerau/jaro-winkler）、RandomGenerateLegacy（SecureRandom）
    registry.register(SafeAgentTool(CsvQueryTool()))
    registry.register(SafeAgentTool(BaseConvertTool()))
    registry.register(SafeAgentTool(StringDistanceTool()))
    registry.register(SafeAgentTool(RandomGenerateTool()))
    // CronNextLegacy（Vixie cron 解析/下 N 次/人话解释）、DurationConvertLegacy（人类时长↔秒）、
    // UnitConvert（长度/质量/数据/温度/速度）、XmlExtract（XML 路径抽取 + XXE 防护）
    registry.register(SafeAgentTool(CronTool()))
    registry.register(SafeAgentTool(DurationConvertTool()))
    registry.register(SafeAgentTool(UnitConvertTool()))
    registry.register(SafeAgentTool(XmlExtractTool()))
}

/** §14/14b/14c：Tool System v3 新工具 + v5 合并四族 + 上下文回顾三件套。 */
internal fun registerToolSystemV3V5Tools(
    registry: DefaultToolRegistry,
    conversationMemory: ConversationMemory
) {
    // ═══ 14. Tool System v3 新工具（纯 JVM，零新依赖）═══
    // wait：Anthropic computer-use 语义的有界可取消等待（UI 稳定窗口）；
    // JsonTransformLegacy：jq 风格七操作数据变换（工具间数据形状对齐）；
    // VersionCompare：SemVer 排序（1.10.0 > 1.9.0，预发布阶梯）。
    registry.register(SafeAgentTool(WaitTool()))
    registry.register(SafeAgentTool(JsonTransformTool()))
    registry.register(SafeAgentTool(VersionCompareTool()))

    // ═══ 14b. Tool System v5 —— #171 四族合并（merged 包，旧工具上方保留）═══
    // time（now/format/parse/add/diff/convert_tz/duration/CronNextLegacy，合并
    // GetTimeLegacy+datetime+CronNextLegacy+DurationConvertLegacy）、random（uuid_v4/v7+
    // int/float/string/pick，合并 UuidGenerateLegacy+RandomGenerateLegacy）、
    // regex（test/extract/replace/match_all/split，合并 RegexExtractLegacy+
    // RegexReplaceLegacy）、json（query/transform/validate/format，合并
    // JsonPathLegacy+JsonTransformLegacy）。旧 id 已进 LEGACY_ALIAS_IDS，不再随请求
    // 下发；此处注册的是新会话模型看到的唯一入口。
    registry.register(SafeAgentTool(TimeTool()))
    registry.register(SafeAgentTool(RandomTool()))
    registry.register(SafeAgentTool(RegexTool()))
    registry.register(SafeAgentTool(JsonTool()))

    // ═══ 14c. Tool System v5 —— #172 上下文回顾三件套（context 包）═══
    // 会话内自救：ContextRecap（结构化全景，CORE）/ ContextSearch
    // （子串定位）/ SessionStats（画像）。数据源接线到 SharedPrefs
    // ConversationMemory 单例（LlmMessage→ContextRecord，System 保留、
    // content 截断 2000、assistant 工具调用补 [tool_call] 标记行）。
    val sessionContext: SessionContextProvider =
        ConversationMemoryContextProvider { conversationMemory.load() }
    registry.register(SafeAgentTool(ContextRecapTool(sessionContext)))
    registry.register(SafeAgentTool(ContextSearchTool(sessionContext)))
    registry.register(SafeAgentTool(SessionStatsTool(sessionContext)))
}
