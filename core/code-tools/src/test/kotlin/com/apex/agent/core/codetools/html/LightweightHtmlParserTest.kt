package com.apex.agent.core.codetools.html

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 轻量 HTML 解析器行为契约：
 * 标签/属性/实体/void/隐式闭合/原文捕获/防御上限/容错。
 */
class LightweightHtmlParserTest {

    private fun parse(html: String): HtmlDocument = LightweightHtmlParser.parse(html)

    @Test
    fun `basic nesting builds tree`() {
        val doc = parse("<html><body><div id=\"a\"><p>hello</p></div></body></html>")
        val body = doc.firstByTag("body")
        assertNotNull(body)
        val div = body!!.children.first { it.tag == "div" }
        assertEquals("a", div.attrs["id"])
        val p = div.children.first { it.tag == "p" }
        assertEquals("hello", p.directText)
        assertEquals(1, doc.firstByTag("html")?.children?.size)
    }

    @Test
    fun `attributes quote variants`() {
        val doc = parse("""<input type="text" value='v1' disabled name=bare>""")
        val input = doc.firstByTag("input")
        assertNotNull(input)
        assertEquals("text", input!!.attrs["type"])
        assertEquals("v1", input.attrs["value"])
        assertEquals("", input.attrs["disabled"])
        assertEquals("bare", input.attrs["name"])
    }

    @Test
    fun `void elements never await closing`() {
        val doc = parse("<div><br><img src=\"x.png\"><span>s</span></div>")
        val div = doc.firstByTag("div")!!
        assertEquals(3, div.children.size)
        assertEquals("br", div.children[0].tag)
        assertEquals("img", div.children[1].tag)
        assertEquals("span", div.children[2].tag)
    }

    @Test
    fun `implicit close li`() {
        val doc = parse("<ul><li>one<li>two<li>three</ul>")
        val ul = doc.firstByTag("ul")!!
        val items = ul.allByTag("li")
        assertEquals(3, items.size)
        assertEquals(listOf("one", "two", "three"), items.map { it.directText })
    }

    @Test
    fun `implicit close p`() {
        val doc = parse("<p>first<p>second")
        val ps = doc.allByTag("p")
        assertEquals(2, ps.size)
        assertEquals("first", ps[0].directText)
    }

    @Test
    fun `script raw text not parsed as tags`() {
        val doc = parse("<head><script>if (a < b) { const s = \"<div>not a tag\"; }</script></head>")
        val script = doc.firstByTag("script")!!
        assertTrue(script.text.contains("a < b"))
        assertTrue(script.text.contains("<div>not a tag"))
        assertTrue(doc.allByTag("div").isEmpty())
    }

    @Test
    fun `style raw text kept`() {
        val doc = parse("<style>.a { color: red } .b::before { content: \"<b>\" }</style>")
        val style = doc.firstByTag("style")!!
        assertTrue(style.text.contains("content"))
        assertTrue(doc.allByTag("b").isEmpty())
    }

    @Test
    fun `entity decoding named and numeric`() {
        assertEquals("a & b < c", LightweightHtmlParser.decodeEntities("a &amp; b &lt; c"))
        assertEquals("x © yes", LightweightHtmlParser.decodeEntities("x &copy; yes"))
        assertEquals("char", LightweightHtmlParser.decodeEntities("&#99;&#104;&#97;&#114;"))
        assertEquals("keep &unknown; as is", LightweightHtmlParser.decodeEntities("keep &unknown; as is"))
    }

    @Test
    fun `comment and doctype preserved as nodes`() {
        val doc = parse("<!DOCTYPE html><!-- hi --><p>x</p>")
        assertEquals("#directive", doc.root.children[0].tag)
        assertTrue(doc.root.children[0].text.contains("DOCTYPE"))
        assertEquals("#comment", doc.root.children[1].tag)
        assertEquals("hi", doc.root.children[1].text.trim())
    }

    @Test
    fun `unclosed tags forgiven at document end`() {
        val doc = parse("<div><span>text")
        val div = doc.firstByTag("div")
        assertNotNull(div)
        assertEquals("text", div!!.allText)
    }

    @Test
    fun `stray closing tag ignored`() {
        val doc = parse("<div>a</span>b</div>")
        val div = doc.firstByTag("div")!!
        assertEquals("a b", div.allText.replace(Regex("\\s+"), " ").trim().let { it })
    }

    @Test
    fun `literal lt not swallowed`() {
        val doc = parse("<p>5 < 6 and 7 > 3</p>")
        val p = doc.firstByTag("p")!!
        // 字面 '<'/'>' 不会被当成标签吞掉：文本里两者都在，且无伪元素产生
        assertTrue(p.directText.contains("<"))
        assertTrue(p.directText.contains(">"))
        assertTrue(p.directText.contains("6 and 7"))
        assertTrue(doc.allByTag("#text").isNotEmpty())
    }

    @Test
    fun `self closing slash works`() {
        val doc = parse("<div/><span>s</span>")
        val div = doc.firstByTag("div")!!
        assertTrue(div.children.isEmpty())
        assertEquals("s", doc.firstByTag("span")!!.directText)
    }

    @Test
    fun `classes parsed lowercase list`() {
        val doc = parse("<div class=\"Card  Large  shadow\"></div>")
        val div = doc.firstByTag("div")!!
        assertEquals(listOf("card", "large", "shadow"), div.classes)
    }

    @Test
    fun `style attr parsed to map`() {
        val doc = parse("<div style=\"display:flex; gap: 8px; color:red\"></div>")
        val div = doc.firstByTag("div")!!
        assertEquals("flex", div.style["display"])
        assertEquals("8px", div.style["gap"])
        assertEquals("red", div.style["color"])
    }

    @Test
    fun `guard max depth truncated not crash`() {
        val deep = buildString {
            repeat(400) { append("<div>") }
            repeat(400) { append("</div>") }
        }
        val doc = parse(deep)
        assertNotNull(doc.truncatedReason)
        assertTrue(doc.truncatedReason!!.contains("depth"))
        // 部分树仍然可用：至少有若干 div
        assertTrue(doc.allByTag("div").size > 50)
    }

    @Test
    fun `guard max nodes truncated`() {
        val many = buildString {
            append("<body>")
            repeat(70_000) { append("<span>x</span>") }
            append("</body>")
        }
        val doc = parse(many)
        assertNotNull(doc.truncatedReason)
        assertTrue(doc.truncatedReason!!.contains("nodes"))
    }

    @Test
    fun `empty input returns empty root`() {
        val doc = parse("")
        assertTrue(doc.root.children.isEmpty())
        assertNull(doc.truncatedReason)
    }

    @Test
    fun `lineOf approximates source line`() {
        val doc = parse("<html>\n<body>\n<div id=\"x\">\n<p>hi</p>\n</div>\n</body>\n</html>")
        val p = doc.firstByTag("p")!!
        assertEquals(4, doc.lineOf(p))
    }

    @Test
    fun `hidden detection via style attr and aria`() {
        val doc = parse("<div style=\"display:none\" class=\"menu\">a</div><span aria-hidden=\"true\">b</span>")
        val menu = doc.firstByTag("div")!!
        assertTrue(menu.isHidden)
        val span = doc.firstByTag("span")!!
        assertTrue(span.isHidden)
    }

    @Test
    fun `cdata and uppercase tags normalized`() {
        val doc = parse("<DIV CLASS=\"Big\"><![CDATA[raw <b>]]></DIV>")
        val div = doc.firstByTag("div")
        assertNotNull(div)
        assertEquals(listOf("big"), div!!.classes)
        assertEquals("#cdata", div.children.firstOrNull { it.tag == "#cdata" }?.tag)
    }
}
