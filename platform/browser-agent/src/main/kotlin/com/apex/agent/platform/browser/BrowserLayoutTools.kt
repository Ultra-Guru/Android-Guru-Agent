package com.apex.agent.platform.browser

import com.apex.agent.core.tools.AgentTool
import com.apex.browser.engine.BrowserEngine
import com.apex.browser.engine.BrowserTracer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

/**
 * 浏览器页面布局分析工具（v1.8.0 · HTML 预览成熟化第一线）。
 *
 * [BrowserAnalyzeLayout]：在**当前页面**上执行一次采集脚本，返回页面的
 * 真实渲染布局报告 —— 与 HtmlAnalyzeLayout（core 层静态分析，文件级）
 * 互补，本工具拿到的是 getComputedStyle / getBoundingClientRect 的
 * **动态几何**：
 *
 * - 视口与横向溢出（scrollWidth vs innerWidth）；
 * - landmark 区块（header/nav/main/aside/footer）的可见矩形；
 * - flex / grid 容器的方向、对齐、gap 与子元素数；
 * - absolute / fixed 定位元素及其「有无已定位祖先」判定；
 * - 越出视口的元素（横向滚动根因定位）；
 * - z-index 配 static 的无效层叠、缺 alt 的图片、标题层级链。
 *
 * 采集脚本为纯表达式 IIFE（无网络、无 DOM 变更），元素扫描上限 4000 个、
 * 每类列表截断（8-20 条），报告对病态 SPA 页保持 KB 级。
 */
class BrowserLayoutTools @Inject constructor(
    private val engine: BrowserEngine,
    private val tracer: BrowserTracer,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun argStr(args: String, key: String): String? =
        runCatching { json.decodeFromString<JsonObject>(args)[key]?.jsonPrimitive?.content }.getOrNull()

    private fun guard(engine: BrowserEngine): String? = try {
        engine.assertAgentControl()
        null
    } catch (e: BrowserEngine.HandoffLockedException) {
        buildString {
            append("{\"status\":\"SYSTEM_LOCKED\"")
            append(",\"reason\":\"${e.message ?: "human handoff in progress"}\"")
            append(",\"hint\":\"用户正在浮窗中人工操作，自动化工具已锁定。请等待 BrowserShow 展开确认，或稍后重试。\"}")
        }
    }

    /** 页面布局采集：可见几何 / 弹性网格 / 定位 / 溢出 / 层叠 / 标题链。 */
    val analyzeLayout = object : AgentTool {
        override val id = "BrowserAnalyzeLayout"
        override val name = "BrowserAnalyzeLayout"
        override val description =
            "分析当前页面的真实渲染布局，返回结构化报告：视口尺寸与横向溢出、landmark 区块矩形、" +
                "flex/grid 容器属性（方向/对齐/gap/子元素数）、absolute/fixed 定位元素及定位祖先判定、" +
                "越出视口元素（横向滚动根因）、z-index 失配、缺 alt 图片、标题层级链。" +
                "在写/改页面样式后验证布局、定位「为什么横向滚动了/为什么重叠了」这类问题时优先使用。" +
                "与 HtmlAnalyzeLayout（本地文件静态分析）互补：本工具拿到的是真实渲染几何。"
        override val parametersSchema = """{
            "type":"object",
            "properties":{
                "max_elements":{"type":"integer","description":"最多扫描元素数，默认 1200，上限 4000"}
            }
        }"""
        override suspend fun execute(arguments: String): String {
            guard(engine)?.let { return it }
            val maxElements = argStr(arguments, "max_elements")?.toIntOrNull()?.coerceIn(50, 4_000) ?: 1_200
            val script = buildCollectScript(maxElements)
            val raw = engine.executeJavaScript(script, 10_000)
                ?: return "(采集失败——可能页面尚未加载完成（先 BrowserNavigate wait_for）或脚本超时)"
            val payload = raw.trim().ifBlank { return "(页面无内容可分析)" }
            return formatReport(payload)
        }
    }

    /** 采集脚本（IIFE，返回 JSON 字符串；只读 DOM，不发起网络、不修改页面）。 */
    internal fun buildCollectScript(maxElements: Int): String {
        val n = maxElements.coerceIn(50, 4_000)
        return """
        (function(){
          var LIMIT=$n;
          var out={viewport:{w:innerWidth,h:innerHeight,scrollW:document.documentElement.scrollWidth,overflowX:document.documentElement.scrollWidth>innerWidth+1},landmarks:[],flex:[],grid:[],absPos:[],fixed:[],overflowEls:[],zIssues:[],noAltImgs:[],headings:[],maxDepth:0,textChars:0};
          function rectOf(el){var r=el.getBoundingClientRect();return {x:Math.round(r.x),y:Math.round(r.y+scrollY),w:Math.round(r.width),h:Math.round(r.height)};}
          function selOf(el){var t=el.tagName.toLowerCase();var id=el.id?'#'+el.id:'';var c='';try{if(el.className&&typeof el.className==='string'&&el.className.trim()){c='.'+el.className.trim().split(/\s+/).slice(0,2).join('.');}}catch(e){}
            return t+id+c;}
          var body=document.body;
          out.textChars=body&&body.innerText?body.innerText.length:0;
          if(!body)return JSON.stringify(out);
          var all=body.querySelectorAll('*');
          for(var i=0;i<all.length&&i<LIMIT;i++){
            var el=all[i];var tag=el.tagName.toLowerCase();
            if(tag==='script'||tag==='style'||tag==='noscript'||tag==='svg')continue;
            var s=getComputedStyle(el);
            if(s.display==='none'||s.visibility==='hidden')continue;
            var r=rectOf(el);
            if(r.w<1&&r.h<1)continue;
            var d=0,p=el;while(p&&p!==body){d++;p=p.parentElement;}if(d>out.maxDepth)out.maxDepth=d;
            if((tag==='header'||tag==='nav'||tag==='main'||tag==='aside'||tag==='footer')&&out.landmarks.length<12){
              out.landmarks.push({tag:tag,rect:r,text:(el.textContent||'').trim().slice(0,30)});
            }
            if((s.display==='flex'||s.display==='inline-flex')&&out.flex.length<20){
              out.flex.push({sel:selOf(el),dir:s.flexDirection,wrap:s.flexWrap,justify:s.justifyContent,align:s.alignItems,gap:s.gap,kids:el.children.length,rect:r});
            }else if((s.display==='grid'||s.display==='inline-grid')&&out.grid.length<12){
              out.grid.push({sel:selOf(el),cols:s.gridTemplateColumns.slice(0,60),kids:el.children.length,rect:r});
            }
            if(s.position==='absolute'&&out.absPos.length<16){
              var anc=el.parentElement,ok=false;
              while(anc&&anc!==document.documentElement){var ps=getComputedStyle(anc);if(ps.position!=='static'){ok=true;break;}anc=anc.parentElement;}
              out.absPos.push({sel:selOf(el),rect:r,positionedAncestor:ok});
            }
            if(s.position==='fixed'&&out.fixed.length<12){out.fixed.push({sel:selOf(el),rect:r,z:s.zIndex});}
            if(s.zIndex!=='auto'&&s.position==='static'&&out.zIssues.length<10){out.zIssues.push({sel:selOf(el),z:s.zIndex});}
            if((r.x<-2||r.x+r.w>out.viewport.w+2)&&s.position!=='fixed'&&out.overflowEls.length<12){
              out.overflowEls.push({sel:selOf(el),rect:r});
            }
            if(tag==='img'&&!el.getAttribute('alt')&&out.noAltImgs.length<8){out.noAltImgs.push({src:(el.getAttribute('src')||'').slice(0,40)});}
            if(tag.length===2&&tag[0]==='h'&&tag[1]>='1'&&tag[1]<='6'&&out.headings.length<20){
              out.headings.push({level:parseInt(tag[1],10),text:(el.textContent||'').trim().slice(0,30)});
            }
          }
          return JSON.stringify(out);
        })()
        """.trimIndent()
    }

    /** 把采集 JSON 格式化为 Agent 可读报告。 */
    internal fun formatReport(payload: String): String {
        val obj = try {
            json.parseToJsonElement(payload).jsonObject
        } catch (e: Exception) {
            return "(采集结果解析失败：${e.message?.take(80) ?: e::class.simpleName}；原始片段: ${payload.take(120)})"
        }
        val sb = StringBuilder()
        val vp = obj["viewport"]?.jsonObject
        val vw = vp?.get("w")?.jsonPrimitive?.content ?: "?"
        val vh = vp?.get("h")?.jsonPrimitive?.content ?: "?"
        val scrollW = vp?.get("scrollW")?.jsonPrimitive?.content ?: "?"
        val overflowX = vp?.get("overflowX")?.jsonPrimitive?.content == "true"
        sb.appendLine("## 页面布局报告（真实渲染几何）")
        sb.appendLine()
        sb.appendLine("- 视口: ${vw}x${vh} · 文档宽: $scrollW" + if (overflowX) " · ⚠️ 存在横向溢出" else " · 无横向溢出")
        sb.appendLine("- 最大嵌套: ${str(obj["maxDepth"])} 层 · 正文 ${str(obj["textChars"])} 字符")
        sb.appendLine()

        fun rows(key: String, limitLabel: String, render: (JsonObject) -> String): List<String> {
            val arr = obj[key]?.jsonArray ?: return emptyList()
            val out = mutableListOf<String>()
            for (i in 0 until arr.jsonArray.size) {
                val row = arr.jsonArray[i] as? JsonObject ?: continue
                out.add(render(row))
            }
            return out.also { if (arr.jsonArray.size > it.size) it.add("…（$limitLabel 截断）") }
        }

        val landmarks = rows("landmarks", "12") {
            val r = it["rect"]?.jsonObject
            "- <${str(it["tag"])}> ${str(r?.get("w"))}x${str(r?.get("h"))} @(${str(r?.get("x"))},${str(r?.get("y"))}) “${str(it["text"])}”"
        }
        if (landmarks.isNotEmpty()) sb.appendLine("### 区块 (landmarks)\n${landmarks.joinToString("\n")}\n")

        val flex = rows("flex", "20") {
            "- ${str(it["sel"])} [${str(it["dir"])}] justify=${str(it["justify"])} align=${str(it["align"])} gap=${str(it["gap"])} ×${str(it["kids"])}子"
        }
        if (flex.isNotEmpty()) sb.appendLine("### flex 容器\n${flex.joinToString("\n")}\n")

        val grid = rows("grid", "12") {
            "- ${str(it["sel"])} cols=${str(it["cols"])} ×${str(it["kids"])}子"
        }
        if (grid.isNotEmpty()) sb.appendLine("### grid 容器\n${grid.joinToString("\n")}\n")

        val absPos = rows("absPos", "16") {
            val r = it["rect"]?.jsonObject
            val anc = if (str(it["positionedAncestor"]) == "true") "已定位祖先OK" else "⚠️无已定位祖先(以body为基准)"
            "- ${str(it["sel"])} ${str(r?.get("w"))}x${str(r?.get("h"))} — $anc"
        }
        if (absPos.isNotEmpty()) sb.appendLine("### absolute 定位\n${absPos.joinToString("\n")}\n")

        val fixed = rows("fixed", "12") {
            val r = it["rect"]?.jsonObject
            "- ${str(it["sel"])} @(${str(r?.get("x"))},${str(r?.get("y"))}) z=${str(it["z"])}"
        }
        if (fixed.isNotEmpty()) sb.appendLine("### fixed 定位\n${fixed.joinToString("\n")}\n")

        val overflow = rows("overflowEls", "12") {
            val r = it["rect"]?.jsonObject
            "- ${str(it["sel"])} x=${str(r?.get("x"))} w=${str(r?.get("w"))}（越出 ${vw}px 视口）"
        }
        if (overflow.isNotEmpty()) sb.appendLine("### 越出视口（横向滚动根因）\n${overflow.joinToString("\n")}\n")

        val zIssues = rows("zIssues", "10") { "- ${str(it["sel"])} z=${str(it["z"])} 配 static（不生效）" }
        if (zIssues.isNotEmpty()) sb.appendLine("### 层叠失效\n${zIssues.joinToString("\n")}\n")

        val noAlt = rows("noAltImgs", "8") { "- <img src=\"${str(it["src"])}\"> 缺 alt" }
        if (noAlt.isNotEmpty()) sb.appendLine("### 图片无 alt\n${noAlt.joinToString("\n")}\n")

        val headings = rows("headings", "20") { "h${str(it["level"])} “${str(it["text"])}”" }
        if (headings.isNotEmpty()) sb.appendLine("### 标题链: ${headings.joinToString(" → ")}\n")

        if (sb.isBlank() || sb.length < 40) {
            sb.appendLine("(页面可见元素极少——可能内容未渲染，先 BrowserNavigate 带 wait_for 重试)")
        }
        sb.appendLine("提示：静态文件分析用 HtmlAnalyzeLayout；交互元素定位用 BrowserSnapshot。")
        return sb.toString().trim()
    }

    private fun str(el: kotlinx.serialization.json.JsonElement?): String =
        try { el?.jsonPrimitive?.content ?: "-" } catch (e: Exception) { "-" }

    fun all(): List<AgentTool> = listOf(
        TracedTool(analyzeLayout, engine, tracer)
    )

    /** trace 包装（与 BrowserAgentTools 同款语义：guard 后执行前后记录）。 */
    private class TracedTool(
        private val delegate: AgentTool,
        private val engine: BrowserEngine,
        private val tracer: BrowserTracer,
    ) : AgentTool {
        override val id get() = delegate.id
        override val name get() = delegate.name
        override val description get() = delegate.description
        override val parametersSchema get() = delegate.parametersSchema
        override suspend fun execute(arguments: String): String {
            val start = System.currentTimeMillis()
            return try {
                val result = delegate.execute(arguments)
                tracer.record(delegate.id, arguments.take(160), result.take(400),
                    System.currentTimeMillis() - start, engine.activeTab()?.url, engine.currentState.name)
                result
            } catch (e: Throwable) {
                tracer.record(delegate.id, arguments.take(160), "ERROR: ${e.message?.take(160)}",
                    System.currentTimeMillis() - start, engine.activeTab()?.url, engine.currentState.name)
                throw e
            }
        }
    }
}
