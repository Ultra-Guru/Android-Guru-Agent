package com.apex.agent.platform.jiguan

import com.apex.agent.platform.TerminalExec.ExecEngine
import com.apex.agent.platform.privilege.PrivilegeManager
import java.io.File

/**
 * 机关宿主装配依赖包 —— app 层 JiguanModule 构造（ExecEngine 需要 app 包内
 * 的 ProotCommandSpawner，平台模块不可反向依赖 app —— 镜像 REA 的
 * ReaCommandRunnerImpl 桥接哲学）。
 */
data class JiguanHostDependencies(
    val execEngine: ExecEngine,
    val privilege: PrivilegeManager,
    /** app filesDir（机关工作区 / 知识仓）。 */
    val filesDir: File,
    /** PRoot 默认工作区宿主目录（guest /workspace —— 脚本/转储/pcap 落点）。 */
    val workspaceDir: File,
)
