package com.apex.agent.core.tools.builtin

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Locale

/**
 * #479 回归：`GlobFiles` 的时间列必须与设备区域无关。
 *
 * 工具输出直接进模型上下文。旧实现用 `Locale.getDefault()`：阿拉伯语设备上
 * `MM-dd HH:mm` 会输出阿拉伯-印度数字（٠٩:٣٠），模型无法据此判断文件新旧；
 * 泰语设备上 `yyyy` 会走佛历（2026 → 2569）。
 */
class FileGlobToolLocaleTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `时间列在阿拉伯语与泰语区域下仍输出 ASCII 公历`() = runTest {
        val dir = tmp.newFolder("ws")
        File(dir, "a.txt").writeText("x")

        val original = Locale.getDefault()
        try {
            // 顺序要紧：formatter 在 companion 里惰性缓存，首个区域决定实例。
            // ar-EG 排第一 —— 旧实现（Locale.getDefault）会在这里产出阿拉伯-印度
            // 数字，后续两个区域直接复用同一实例，所以断言能区分修复前后。
            val outputs = listOf("ar-EG", "th-TH", "en-US").map { tag ->
                Locale.setDefault(Locale.forLanguageTag(tag))
                FileGlobTool(dir).execute("""{"pattern":"*.txt"}""")
            }

            // 三种区域下时间列必须一致（都是 ASCII 公历）。
            val stamps = outputs.map { out ->
                out.lineSequence().first { it.contains("a.txt") }
                    .substringAfter("a.txt").trim()
            }
            assertEquals("不同区域时间列必须一致: $stamps", 1, stamps.toSet().size)
            assertTrue(
                "时间列必须是 ASCII: ${stamps[0]}",
                stamps[0].all { it.code < 128 }
            )
        } finally {
            Locale.setDefault(original)
        }
    }

    /**
     * 泰语区域单测：佛历只在含 `yyyy` 的格式里出现，而本工具的列是
     * `MM-dd HH:mm`（无年份）—— 因此这里锁的是「不因区域引入非 ASCII
     * 数字」这条更普遍的性质，顺带覆盖 th-TH 的 Buddhist calendar 切换。
     */
    @Test
    fun `时间列在泰语区域下不含非 ASCII 字符`() = runTest {
        val dir = tmp.newFolder("ws-th")
        File(dir, "a.txt").writeText("x")

        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("th-TH"))
            val out = FileGlobTool(dir).execute("""{"pattern":"*.txt"}""")
            val stamp = out.lineSequence().first { it.contains("a.txt") }
                .substringAfter("a.txt").trim()
            assertTrue("泰语区域时间列必须是 ASCII: $stamp", stamp.all { it.code < 128 })
        } finally {
            Locale.setDefault(original)
        }
    }
}
