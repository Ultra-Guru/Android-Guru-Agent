package com.apex.agent.core.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 回归：累积器拒收字面 "null" 工具名（2026-11「MCP null」全链失效事故）。
 *
 * 事故链：网关把 `"function":{"name":null}` 序列化后，解析层曾把 JSON null
 * 误转字面串 "null"（已修，见 ToolCallJsonNullNameTest）；即便解析层已产出
 * 空串，字符串化网关仍可能直接发 `"name":"null"` 字符串。旧累积器
 * 「后到的非空白名覆盖先到的名」—— 参数续块上的 "null" 会反向覆盖首片的
 * 正确工具名，注册表收到 Tool 'null' not found。修复后：首片与续片同口径
 * 拒收字面 "null"，其余「最新非空白名生效」语义不变。
 *
 * 纯 JVM 单测（无协程/IO），断言口径对照 StreamingToolCallAccumulator 源码。
 */
class StreamingToolCallAccumulatorNullGuardTest {

    @Test
    fun `字面 null 首片名被归一为空串`() {
        val acc = StreamingToolCallAccumulator("call_1", initialName = "null")
        assertEquals("", acc.name)
    }

    @Test
    fun `字面 null 续片名不覆盖已累积的正确名`() {
        val acc = StreamingToolCallAccumulator("call_1", initialName = "McpGithubCreateIssue")
        acc.append("null", "}")
        assertEquals("McpGithubCreateIssue", acc.name)
    }

    @Test
    fun `全部分片均为 null 名时名保持空 参数正常累积`() {
        val acc = StreamingToolCallAccumulator("call_1")
        acc.append("null", """{"q":""")
        acc.append("null", """"mcp"}""")
        val built = acc.build()
        assertEquals("", built.name)
        assertEquals("""{"q":"mcp"}""", built.arguments)
    }

    @Test
    fun `真名续片仍然最新生效 既有语义不变`() {
        val acc = StreamingToolCallAccumulator("call_1")
        acc.append("FirstTool", """{"a":""")
        acc.append("", "1}")
        // provider 重复发名（同值幂等）与更正名（最新生效）都不受影响
        acc.append("FirstTool", "")
        acc.append("SecondTool", "")
        val built = acc.build()
        assertEquals("SecondTool", built.name)
        assertEquals("""{"a":1}""", built.arguments)
        assertEquals("call_1", built.id)
    }

    @Test
    fun `appendArguments 只进参数不碰名`() {
        val acc = StreamingToolCallAccumulator("call_1", initialName = "ToolList")
        acc.appendArguments("{}")
        val built = acc.build()
        assertEquals("ToolList", built.name)
        assertEquals("{}", built.arguments)
    }
}
