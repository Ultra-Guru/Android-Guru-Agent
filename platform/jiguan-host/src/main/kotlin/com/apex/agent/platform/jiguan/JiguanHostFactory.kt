package com.apex.agent.platform.jiguan

import com.apex.agent.core.jiguan.JiguanKnowledge
import com.apex.agent.core.jiguan.JiguanMemoryPort
import com.apex.agent.core.jiguan.JiguanOrchestrator
import com.apex.agent.core.jiguan.JiguanRuntime
import com.apex.agent.core.jiguan.OmniHookEngine
import com.apex.agent.platform.TerminalExec.ExecEngine
import com.apex.agent.platform.privilege.PrivilegeManager
import java.io.File

/**
 * # 机关宿主装配厂
 *
 * 把五域端口 + Omni-Hook + 知识库装配成 [JiguanRuntime] —— 由 app 层
 * JiguanModule 调用（ExecEngine / PrivilegeManager / 目录布局均来自 app
 * 装配；本厂只做纯组装，不触碰 app 类型 —— 与 REA 的 app 层桥接哲学一致，
 * 但把可复用的 Android 侧逻辑沉淀在平台模块）。
 *
 * @param execEngine PRoot 优先的命令执行链（与 TerminalExec / REA 同一条）
 * @param privilege 权限面（Root ⊃ Shizuku ∪ Accessibility）
 * @param filesDir app filesDir（机关工作区 jiguan/ 与知识仓挂载点）
 * @param workspaceDir PRoot 默认工作区宿主目录（guest /workspace ——
 *   Frida 脚本 / 内存转储 / pcap 的双侧可见落点）
 */
object JiguanHostFactory {

    fun create(
        execEngine: ExecEngine,
        privilege: PrivilegeManager,
        filesDir: File,
        workspaceDir: File,
    ): JiguanRuntime {
        val commandBridge = JiguanCommandBridge(execEngine)
        val rootShellAvailable = { privilege.rootAvailable.value }
        val rootShell: suspend (String, Long) -> Result<String> = { cmd, timeout ->
            val result = privilege.executeShell(cmd, timeout)
            if (result.success) Result.success(result.output) else Result.failure(IllegalStateException(result.output.ifBlank { "exit ${result.exitCode} via ${result.executedVia}" }))
        }

        val memory: JiguanMemoryPort = ProcFsMemoryAdapter(
            commandRunner = commandBridge,
            rootShellAvailable = rootShellAvailable,
            rootShell = rootShell,
            scratchDir = File(filesDir, "jiguan/tmp"),
        )
        val frida = FridaBridgeAdapter(
            commandRunner = commandBridge,
            memoryRegions = { pid -> memory.regions(pid) },
            memoryRead = { pid, address, size -> memory.read(pid, address, size) },
            rootShellAvailable = rootShellAvailable,
            rootShell = rootShell,
            workspaceDir = workspaceDir,
        )
        val network = PacketCaptureAdapter(
            rootShellAvailable = rootShellAvailable,
            rootShell = rootShell,
            workspaceDir = workspaceDir,
        )
        val system = SystemDomainAdapter(privilege)

        // 通道探测 = frida-server 真实存活检查（adapter 内部含拉起兜底）。
        val hooks = OmniHookEngine(
            scriptRunner = { pid, script, timeoutMs -> frida.runScript(pid, script, timeoutMs) },
            fridaServerAvailable = { frida.ensureFridaServer() },
        )
        val knowledge = JiguanKnowledge(KnowledgeFileStore(File(filesDir, "jiguan/knowledge.json")))

        return JiguanRuntime(
            memory = memory,
            java = frida,
            native = frida,
            network = network,
            system = system,
            hooks = hooks,
            knowledge = knowledge,
        )
    }

    /** 编排器（capability 报告 / 配方 / 提示段）—— 与 runtime 同源端口。 */
    fun orchestrator(runtime: JiguanRuntime): JiguanOrchestrator =
        JiguanOrchestrator(
            memoryPort = runtime.memory,
            javaPort = runtime.java,
            nativePort = runtime.native,
            networkPort = runtime.network,
            systemPort = runtime.system,
        )
}
