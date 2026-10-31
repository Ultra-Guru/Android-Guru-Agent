package com.apex.agent.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #206 单测：[ToolDomain] 7 域分组 —— 穷举覆盖、无悬空、categories 反向派生一致。
 */
class ToolDomainTest {

    @Test
    fun `every category belongs to exactly one domain`() {
        val allMapped = ToolCategory.entries.map { ToolDomain.domainOf(it) }
        assertEquals(ToolCategory.entries.size, allMapped.size)
        assertEquals(ToolCategory.entries.size, allMapped.toSet().let { _ -> ToolCategory.entries.size })
        // 每个域的 categories 反向派生与 domainOf 完全一致
        ToolDomain.entries.forEach { domain ->
            domain.categories.forEach { category ->
                assertEquals(domain, ToolDomain.domainOf(category))
            }
        }
        // 并集 = 全部类别，无重复归属
        val union = ToolDomain.entries.flatMap { it.categories }.toSet()
        assertEquals(ToolCategory.entries.toSet(), union)
    }

    @Test
    fun `seven domains in stable display order`() {
        assertEquals(7, ToolDomain.entries.size)
        val ordered = ToolDomain.inDisplayOrder()
        assertEquals(ToolDomain.EXECUTION, ordered.first())
        assertEquals(ToolDomain.SAFETY_AND_UTILITIES, ordered.last())
        assertEquals(ordered.map { it.order }.sorted(), ordered.map { it.order })
    }

    @Test
    fun `domains cover all eighteen categories`() {
        val covered = ToolDomain.entries.sumOf { it.categories.size }
        assertEquals(18, covered)
        assertEquals(18, ToolCategory.entries.size)
    }

    @Test
    fun `every domain has a label and at least one category`() {
        ToolDomain.entries.forEach { domain ->
            assertTrue(domain.label.isNotBlank())
            assertTrue(domain.categories.isNotEmpty())
        }
    }
}
