package com.apex.agent.core.code.loom.permission

/**
 * # Loom Tool Risk Book — 工具读写画像册
 *
 * 回答两个问题：**这个工具是读还是写？**（[LoomToolEffect]）与**它敏感吗？**
 * （不可逆 / 出网 / 花钱 / 动系统——模态兜底里要问的那类）。
 *
 * ## 画像来源（双层）
 *
 * 1. **内置册**（[defaultBook]）：宿主工具目录里已知家族的显式画像
 *    （code_* / git / 文件 / 终端 / web / skill / mcp__ / loom_*）；
 * 2. **名字启发**（[effectByHeuristic]）：内置册未收录的工具按命名惯例
 *    兜底（write/edit/commit/push/delete/install → WRITE；其余 READ）——
 *    未知 MCP 工具按 WRITE 保守处理（宁可多问一次）。
 *
 * 线程契约：注册发生在装配期（DI 单遍）；查询无锁只读。
 */
class LoomToolRiskBook(
    private val effects: Map<String, LoomToolEffect> = emptyMap(),
    private val sensitiveIds: Set<String> = emptySet()
) {

    /** 工具效应画像（内置册优先，缺席走启发）。 */
    fun effectOf(toolId: String): LoomToolEffect =
        effects[toolId] ?: effectByHeuristic(toolId)

    /** 是否敏感工具（内置册独裁——启发不猜敏感，防误报打扰）。 */
    fun isSensitive(toolId: String): Boolean = sensitiveIds.contains(toolId)

    companion object {

        /** 命名启发：写动词清单（命中即 WRITE）。 */
        private val WRITE_HINTS = listOf(
            "write", "edit", "commit", "push", "delete", "remove", "rm",
            "install", "uninstall", "mkdir", "touch", "move", "rename",
            "create", "update", "patch", "apply", "execute", "run", "shell",
            "spawn", "send", "use", "activate", "drop", "reset", "clear"
        )

        /** MCP 工具前缀（未知 MCP 一律 WRITE 保守）。 */
        private const val MCP_PREFIX = "mcp__"

        /** 名字启发画像。 */
        fun effectByHeuristic(toolId: String): LoomToolEffect {
            val lower = toolId.lowercase()
            if (lower.startsWith(MCP_PREFIX)) return LoomToolEffect.WRITE
            return if (WRITE_HINTS.any { lower.contains(it) }) {
                LoomToolEffect.WRITE
            } else {
                LoomToolEffect.READ
            }
        }

        /**
         * 内置册：宿主共享工具目录的已知画像。
         *
         * 画像口径：
         * - code_* 全家：读四件（Read/Glob/Grep/Check）+ git 观测三件
         *   （Status/Diff/Log）为 READ；写四件（Write/Edit）+ git 落账
         *   （Commit/Branch）为 WRITE；
         * - 终端：ShellExecute = WRITE（敏感=false——命令级管控交给命令
         *   规则与模态，工具级不重复拦）；
         * - 文件四件与 web：读写按名；WebFetch/WebSearch 读；
         * - 技能面：SkillActivate/SkillList READ（状态变更低风险）；
         * - 敏感册：git push（出仓）、apt 安装类与 rm 等破坏命令不在
         *   工具级登记——ShellExecute 的命令串匹配在治理器命令规则层
         *   做，工具级敏感册只登记结构性敏感（出仓推送 / 发消息）。
         */
        fun defaultBook(): LoomToolRiskBook {
            val effects = mutableMapOf<String, LoomToolEffect>()
            val sensitive = mutableSetOf<String>()

            // code_* 读件
            for (id in listOf(
                "CodeRead", "CodeGlob", "CodeGrep", "CodeCheck",
                "CodeGitStatus", "CodeGitDiff", "CodeGitLog"
            )) {
                effects[id] = LoomToolEffect.READ
            }
            // code_* 写件
            for (id in listOf(
                "CodeWrite", "CodeEdit", "CodeTodo",
                "CodeGitCommit", "CodeGitBranch"
            )) {
                effects[id] = LoomToolEffect.WRITE
            }
            // 深潜线共享工具（文件 / 终端 / web）
            for (id in listOf("FileRead", "FileGlob", "Grep", "Glob")) {
                effects[id] = LoomToolEffect.READ
            }
            for (id in listOf("FileWrite", "FileEdit", "ShellExecute")) {
                effects[id] = LoomToolEffect.WRITE
            }
            effects["WebSearch"] = LoomToolEffect.READ
            effects["WebFetch"] = LoomToolEffect.READ
            // 技能面
            effects["SkillActivate"] = LoomToolEffect.READ
            effects["SkillList"] = LoomToolEffect.READ
            // git 推送：结构性敏感（出仓）
            sensitive.add("CodeGitPush")
            effects["CodeGitPush"] = LoomToolEffect.WRITE
            // 子代理（深潜线共享）——委派本身是读画像（子任务自带权限面）
            effects["CodeTask"] = LoomToolEffect.READ
            return LoomToolRiskBook(effects.toMap(), sensitive)
        }
    }
}
