package com.apex.agent.core.tools.pluginhost.opencode

import com.apex.agent.core.tools.DefaultToolRegistry
import com.apex.agent.core.tools.pluginhost.bridge.PluginEventBus
import com.apex.agent.core.tools.pluginhost.bridge.PluginToolBridge
import com.apex.agent.core.tools.pluginhost.worker.LocalProcessWorkerPort
import com.apex.agent.core.tools.pluginhost.worker.NodeRuntimeProvider
import com.apex.agent.core.tools.pluginhost.worker.PluginWorkerSupervisor
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * # OpenCodePluginLoader 端到端测试（真实 node，Phase 4 验收锚点）
 *
 * 用**官方形状**的 fixture 验证（对齐 docs/build/plugins/server 2026-11）：
 * - V2：`import { Plugin } from "@opencode/plugin"` + default export
 *   `Plugin.define({id, setup(ctx)})` + `ctx.tool.transform(editor => editor.add(...))`
 *   + `ctx.tool.hook` + `ctx.storage` + `ctx.options`；
 * - V1：具名异步导出返回 hook map（`"tool.execute.before": async (...) => {}`）。
 *
 * 全链路：ABI 探测 → 入口解析 → SDK 垫片 → Worker 生态化加载 → 工具一等
 * 注册 → 引擎调用 → 宿主事件 → 钩子触发 → 停止清理。
 */
class OpenCodePluginLoaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun nodeAvailable(): Boolean = runCatching {
        ProcessBuilder("node", "--version").start().waitFor() == 0
    }.getOrDefault(false)

    private fun realIo(timeoutMs: Long = 90_000, block: suspend CoroutineScope.() -> Unit) {
        val latch = CountDownLatch(1)
        var failure: Throwable? = null
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope.launch {
            try {
                block()
            } catch (t: Throwable) {
                failure = t
            } finally {
                latch.countDown()
            }
        }
        assertTrue("测试超时（${timeoutMs}ms）", latch.await(timeoutMs, TimeUnit.MILLISECONDS))
        failure?.let { throw it }
    }

    private suspend fun awaitCondition(timeoutMs: Long = 20_000, condition: suspend () -> Boolean) {
        withTimeout(timeoutMs) {
            while (!condition()) delay(25)
        }
    }

    /** 官方 V2 形状 fixture（package.json + src/index.mjs）。 */
    private fun writeV2Fixture(dir: File, useRealSdk: Boolean): File {
        val src = File(dir, "src").apply { mkdirs() }
        File(dir, "package.json").writeText(
            """
            {
              "name": "opencode-acme-plugin",
              "version": "1.0.0",
              "type": "module",
              "exports": { ".": "./src/index.mjs" },
              "dependencies": { "@opencode/plugin": "latest" }
            }
            """.trimIndent()
        )
        src.resolve("index.mjs").writeText(
            """
            import { Plugin } from "@opencode/plugin"

            export default Plugin.define({
              id: "acme.greeting",
              async setup(ctx) {
                await ctx.storage.set("loaded", true)
                await ctx.tool.transform((editor) => {
                  editor.add({
                    name: "greeting",
                    description: "Create a greeting",
                    input: {
                      type: "object",
                      properties: { name: { type: "string" } },
                      required: ["name"],
                      additionalProperties: false
                    },
                    execute: async (input) => {
                      const loaded = await ctx.storage.get("loaded");
                      return { content: "Hello " + input.name + "! (loaded=" + loaded + ", strict=" + ctx.options.strict + ")" }
                    }
                  })
                })
                await ctx.tool.hook("execute.before", (event) => {
                  console.error("[acme-hook] tool=" + event.tool)
                })
              }
            })
            """.trimIndent()
        )
        if (!useRealSdk) {
            // 未跑 npm install 的形态：垫片兜底（真实包优先由 useRealSdk=true 分支验证探测逻辑）
            File(dir, "package.json").writeText(
                File(dir, "package.json").readText()
                    .replace("\"dependencies\": { \"@opencode/plugin\": \"latest\" }", "\"dependencies\": { \"@opencode/plugin\": \"latest\" }, \"_noNodeModules\": true")
            )
        }
        return dir
    }

    /** 官方 V1 形状 fixture（具名异步导出 hook map；@opencode-ai/plugin 纯类型零运行时依赖）。 */
    private fun writeV1Fixture(dir: File): File {
        dir.resolve("package.json").writeText(
            """
            {
              "name": "opencode-legacy-plugin",
              "version": "0.9.0",
              "type": "module",
              "main": "./index.mjs",
              "dependencies": { "@opencode-ai/plugin": "^1.0.0" }
            }
            """.trimIndent()
        )
        dir.resolve("index.mjs").writeText(
            """
            export const LegacyPlugin = async ({ directory, project, client }) => {
              await client.storage.set("v1-loaded", "yes")
              return {
                "tool.execute.before": async (input) => {
                  console.error("[v1-hook] tool=" + (input && input.tool))
                }
              }
            }
            """.trimIndent()
        )
        return dir
    }

    @Test
    fun `v2 plugin full chain with official shapes`() {
        assumeTrue("node not available — test honestly skipped", nodeAvailable())
        val dir = tmp.newFolder()
        writeV2Fixture(dir, useRealSdk = false)

        realIo {
            val registry = DefaultToolRegistry()
            val eventBus = PluginEventBus(this)
            val toolBridge = PluginToolBridge(registry)
            val supervisor = PluginWorkerSupervisor(
                spawnPort = LocalProcessWorkerPort(),
                provider = NodeRuntimeProvider(NoProbePort()),
                bootstrapDir = dir,
                parentScope = this
            )
            val loader = OpenCodePluginLoader(supervisor, toolBridge, eventBus)

            // 加载：ABI 探测 V2 + SDK 垫片落盘 + 工具注册
            val outcome = loader.load(
                "opencode.acme", dir, """{"strict":true}"""
            ).getOrThrow()

            assertEquals(
                OpenCodeAbiDetector.OpenCodeVersion.V2,
                outcome.abi.version
            )
            assertTrue(outcome.sdkShimVendored)
            assertEquals(1, outcome.loaded.tools.size)
            assertEquals("greeting", outcome.loaded.tools.single().name)
            assertTrue(outcome.loaded.tools.single().parametersSchema?.contains("object") == true)
            assertEquals(1, outcome.registeredTools.size)

            // 一等注册：ToolRegistry 可寻址，ctx.options/ctx.storage 真实生效
            val toolId = outcome.registeredTools.single()
            val tool = registry.getTool(toolId)
            assertNotNull(tool)
            assertEquals(
                "Hello apex! (loaded=true, strict=true)",
                tool!!.execute("""{"name":"apex"}""")
            )
            // 存储持久化在插件目录
            assertTrue(File(dir, ".apex-plugin-storage.json").isFile)

            // 宿主事件 → OpenCode 钩子转发（tool.execute.before）
            eventBus.dispatch(
                PluginEventBus.PluginHostEvent.BEFORE_TOOL_CALL,
                """{"tool":"ShellExecute"}"""
            )
            awaitCondition {
                supervisor.worker("opencode.acme")!!
                    .stderrSnapshot().any { it.contains("[acme-hook] tool=ShellExecute") }
            }

            // 停止清理（加载器逆序收尾）：工具注销 + 订阅取消 + Worker 停
            loader.stop("opencode.acme")
            assertNull(registry.getTool(toolId))
            supervisor.stopAll()
        }
    }

    @Test
    fun `v1 legacy hook map plugin loads and receives events`() {
        assumeTrue("node not available — test honestly skipped", nodeAvailable())
        val dir = tmp.newFolder()
        writeV1Fixture(dir)

        realIo {
            val registry = DefaultToolRegistry()
            val eventBus = PluginEventBus(this)
            val toolBridge = PluginToolBridge(registry)
            val supervisor = PluginWorkerSupervisor(
                spawnPort = LocalProcessWorkerPort(),
                provider = NodeRuntimeProvider(NoProbePort()),
                bootstrapDir = dir,
                parentScope = this
            )
            val loader = OpenCodePluginLoader(supervisor, toolBridge, eventBus)

            val outcome = loader.load("opencode.legacy", dir).getOrThrow()
            assertEquals(OpenCodeAbiDetector.OpenCodeVersion.V1, outcome.abi.version)
            // V1 hook map：钩子被捕获上报，工具面为空（官方形状如此）
            assertTrue(outcome.loaded.hooks.contains("tool.execute.before"))
            assertEquals(0, outcome.registeredTools.size)

            // V1 的 client.storage 也走同一套文件持久化
            assertTrue(File(dir, ".apex-plugin-storage.json").isFile)

            // 宿主事件 → V1 hook map 条目分发
            eventBus.dispatch(
                PluginEventBus.PluginHostEvent.BEFORE_TOOL_CALL,
                """{"tool":"Read"}"""
            )
            awaitCondition {
                supervisor.worker("opencode.legacy")!!
                    .stderrSnapshot().any { it.contains("[v1-hook] tool=Read") }
            }
            supervisor.stopAll()
        }
    }

    @Test
    fun `abi detector distinguishes sdk declarations`() {
        val v2 = OpenCodeAbiDetector.detect(
            """{"name":"p","type":"module","dependencies":{"@opencode/plugin":"latest"}}"""
        )
        assertEquals(OpenCodeAbiDetector.OpenCodeVersion.V2, v2.version)
        assertEquals("esm", v2.moduleFormat)

        val v1 = OpenCodeAbiDetector.detect(
            """{"name":"p","dependencies":{"@opencode-ai/plugin":"^1"}}"""
        )
        assertEquals(OpenCodeAbiDetector.OpenCodeVersion.V1, v1.version)
        assertEquals("cjs", v1.moduleFormat)

        val unknown = OpenCodeAbiDetector.detect("""{"name":"p"}""")
        assertEquals(OpenCodeAbiDetector.OpenCodeVersion.UNKNOWN, unknown.version)
        assertEquals(OpenCodeAbiDetector.OpenCodeVersion.UNKNOWN, OpenCodeAbiDetector.detect(null).version)
    }

    @Test
    fun `entry resolution honors exports map then main`() {
        val dir = tmp.newFolder()
        // exports["."] 优先
        dir.resolve("package.json").writeText(
            """{"name":"p","type":"module","exports":{".":"./src/entry.mjs"},"main":"./fallback.js"}"""
        )
        dir.resolve("src").mkdirs()
        dir.resolve("src/entry.mjs").writeText("export const x = 1")
        dir.resolve("fallback.js").writeText("module.exports = {}")
        val entry = OpenCodeAbiDetector.resolveEntry(dir, dir.resolve("package.json").readText())
        assertTrue(entry?.path?.endsWith("entry.mjs") == true)

        // 无 exports：main 生效；都无：index 兜底
        dir.resolve("package.json").writeText("""{"name":"p","main":"./lib/impl.js"}""")
        dir.resolve("lib").mkdirs()
        dir.resolve("lib/impl.js").writeText("module.exports = {}")
        val byMain = OpenCodeAbiDetector.resolveEntry(dir, dir.resolve("package.json").readText())
        assertTrue(byMain?.path?.endsWith("impl.js") == true)

        dir.resolve("package.json").writeText("""{"name":"p"}""")
        dir.resolve("index.js").writeText("module.exports = {}")
        val byIndex = OpenCodeAbiDetector.resolveEntry(dir, dir.resolve("package.json").readText())
        assertTrue(byIndex?.path?.endsWith("index.js") == true)
    }

    @Test
    fun `event mapping is honest about unsupported host events`() {
        // 有映射的
        assertEquals(
            "tool.execute.before",
            OpenCodeEventMapping.toOpenCodeHook(PluginEventBus.PluginHostEvent.BEFORE_TOOL_CALL)
        )
        assertEquals(
            "session.prompt",
            OpenCodeEventMapping.toOpenCodeHook(PluginEventBus.PluginHostEvent.USER_PROMPT_SUBMIT)
        )
        // 无映射的如实返回 null（不伪造）
        assertNull(OpenCodeEventMapping.toOpenCodeHook(PluginEventBus.PluginHostEvent.SESSION_START))
        assertNull(OpenCodeEventMapping.toOpenCodeHook(PluginEventBus.PluginHostEvent.NOTIFICATION))
    }

    /** 探测通道假实现（Worker 路径不依赖探测）。 */
    private class NoProbePort : com.apex.agent.core.tools.extension.NodeInstallPort {
        override suspend fun exec(
            command: List<String>,
            cwd: File,
            timeoutMs: Long,
            stdin: String?
        ): Result<com.apex.agent.core.tools.extension.NodeInstallPort.ExecOutcome> =
            Result.failure(UnsupportedOperationException("probe not wired"))

        override suspend fun probeRuntime() =
            com.apex.agent.core.tools.extension.NodeInstallPort.RuntimeProbe(null, null)
    }
}
