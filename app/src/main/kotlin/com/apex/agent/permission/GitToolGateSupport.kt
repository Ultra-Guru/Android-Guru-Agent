package com.apex.agent.permission

import com.apex.agent.core.codetools.git.policy.GitActionClass
import com.apex.agent.core.codetools.git.policy.GitCommandPolicy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * # GitToolGateSupport — 权限门/风险门共用的 git 命令分类桥（Git 审计 P0-5）
 *
 * [PermissionModeGate] 与 RiskAwareToolGate 都需要对 `CodeGitRun` 的
 * **单条命令**（而非工具 id）做门禁决策。本对象把工具参数 JSON 里的
 * `command` 字段解析为 [GitCommandPolicy] 分类，两门共用同一事实源。
 *
 * 防御式语义：非 git 工具 / 参数不可解析 → 返回 null（调用方走原有
 * 通用流程；参数解析失败由工具层给出可自修复的错误）。
 */
internal object GitToolGateSupport {

    /** CodeGitRun 调用的命令分类；null = 非 git run 工具或参数不可解析。 */
    fun classifyRunCommand(toolId: String, arguments: String): GitActionClass? {
        if (toolId != GitCommandPolicy.RUN_TOOL_ID) return null
        val raw = runCatching {
            (Json.parseToJsonElement(arguments).jsonObject["command"] as? JsonPrimitive)
                ?.contentOrNull
        }.getOrNull()?.trim() ?: return null
        val args = when (val parsed = GitCommandPolicy.parse(raw)) {
            is GitCommandPolicy.ParsedArgs.Ok -> parsed.args
            is GitCommandPolicy.ParsedArgs.Bad -> return null
        }
        return GitCommandPolicy.classify(args)
    }

    /** CONFIG_ESCAPE 硬拒绝文案（面向模型，含可执行指引）。 */
    fun configEscapeDenial(toolId: String): String =
        "git 配置逃逸命令已被策略硬拒绝（$toolId）：-c 注入式配置 / config 子命令可通过 " +
            "credential.helper、core.hooksPath 等执行任意程序，任何权限档（含 BYPASS）都不放行。" +
            "提交身份与仓库配置请在 App 的 GitHub 设置里维护；如确需特殊配置，请用户在 " +
            "Agent 之外的终端手工执行"
}
