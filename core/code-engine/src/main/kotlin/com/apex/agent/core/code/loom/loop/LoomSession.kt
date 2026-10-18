package com.apex.agent.core.code.loom.loop

import com.apex.agent.core.code.loom.LoomActivePackage
import com.apex.agent.core.code.loom.LoomAttachmentTag
import com.apex.agent.core.code.loom.LoomEngineConfig
import com.apex.agent.core.code.loom.LoomInvocation
import com.apex.agent.core.code.loom.LoomPrompts
import com.apex.agent.core.code.loom.markup.LoomMarkupGrammar
import com.apex.agent.core.code.thinking.CodeThinkingLevel
import com.apex.agent.core.engine.AgentMode
import com.apex.agent.core.engine.UserInput
import com.apex.agent.core.llm.LlmMessage
import com.apex.agent.core.llm.ToolDefinition
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * # Loom Session — 织造会话状态与提示词装配
 *
 * 会话 = **历史织物**（不含系统轮——系统轮每回合 JIT 重织）+ 宿主注入的
 * 易变上下文（规则 / 人设 / 附加段 / 工作区 / 织造包）。所有轮次追加都
 * 经过本类落账（内存 + [LoomMemory] 持久化），保证回放与断点续跑一致。
 *
 * ## 轮次样式（in-band 协议下的历史形状）
 *
 * | 轮 | 角色 | 内容 |
 * |---|---|---|
 * | 用户输入 | user | 文本 + 附件标签行（图片走 images 字段） |
 * | 助手回合 | assistant | deliberate 块 + 正文 + use 块（**无**原生 toolCalls 字段——全 in-band） |
 * | 回执轮 | user | outcome 块（空行分隔，块外零闲字） |
 * | 转向轮 | user | [转向] 前缀的用户补充 |
 * | 催促轮 | user | 纯思考退回反馈 |
 * | 裁决轮 | user | 布样确认/驳回 |
 * | 摘要轮 | user | loom-digest 块（压缩后的旧轮结晶） |
 *
 * ## 线程契约
 *
 * 历史列表的读写发生在引擎执行协程（单协程内顺序调用）；宿主侧的易变
 * 上下文（规则/人设/…）用 [AtomicReference] 快照——设置线程与执行协程
 * 并发安全。
 */
class LoomSession(
    private val config: LoomEngineConfig,
    private val memory: LoomMemory?,
    private val estimator: LoomTokenEstimator,
    private val environmentSource: LoomEnvironmentSource,
    private val workspaceContextSource: LoomWorkspaceContextSource?,
    private val modelInfoSource: LoomModelInfoSource?,
    private val logger: LoomLogger
) {

    /** 会话历史（不含系统轮）。 */
    private val turns = mutableListOf<LlmMessage>()

    /** 工作区状态快照。 */
    private data class WorkspaceState(
        val id: String = DEFAULT_WORKSPACE,
        val name: String = DEFAULT_WORKSPACE,
        val root: File? = null,
        val activeFile: String? = null
    )

    private val workspace = AtomicReference(WorkspaceState())

    /** 宿主易变段（设置线程写 / 执行协程读——全部快照式）。 */
    private val globalRulesRef = AtomicReference("")
    private val roleDefinitionRef = AtomicReference("")
    private val rolePromptRef = AtomicReference<String?>(null)
    private val extrasRef = AtomicReference<String?>(null)
    private val packagesRef = AtomicReference<List<LoomActivePackage>>(emptyList())
    private val modeRef = AtomicReference(AgentMode.BUILD)
    private val thinkingLevelRef = AtomicReference(CodeThinkingLevel.STANDARD)
    private val thinkingDirectiveRef = AtomicReference("")
    private val planSettledRef = AtomicReference(false)

    // ═══════════════════════════ 生命周期 ═══════════════════════════

    /** 绑定工作区：记忆通道切换 + 历史重放。 */
    fun bindWorkspace(workspaceId: String, name: String, root: File?, activeFile: String?) {
        workspace.set(
            WorkspaceState(
                id = workspaceId.ifBlank { DEFAULT_WORKSPACE },
                name = name.ifBlank { workspaceId },
                root = root,
                activeFile = activeFile
            )
        )
        memory?.bindWorkspace(workspaceId.ifBlank { DEFAULT_WORKSPACE })
        reload()
    }

    /** 从持久化通道重放历史。 */
    fun reload() {
        turns.clear()
        val loaded = memory?.load() ?: emptyList()
        // 防御：持久化层如意外存进 system 轮（历史版本脏数据），丢弃之
        turns.addAll(loaded.filterNot { it is LlmMessage.System })
        logger.info(TAG, "history replayed: ${turns.size} turns")
    }

    /** 清空会话（新会话——记忆通道同步清）。 */
    fun clear() {
        turns.clear()
        memory?.clear()
    }

    /** 历史轮数（UI 徽标用；不含系统轮）。 */
    fun historyCount(): Int = turns.size

    /** 历史快照（执行协程外只读消费）。 */
    fun historySnapshot(): List<LlmMessage> = turns.toList()

    // ═══════════════════════════ 宿主易变段 ═══════════════════════════

    fun updateGlobalRules(rules: String) {
        globalRulesRef.set(rules)
    }

    fun updateRolePersona(roleDefinition: String, rolePrompt: String?) {
        roleDefinitionRef.set(roleDefinition)
        rolePromptRef.set(rolePrompt)
    }

    fun updateSessionExtras(extras: String?) {
        extrasRef.set(extras)
    }

    fun updateMode(mode: AgentMode) {
        modeRef.set(mode)
    }

    /** 思考档位（UI 同步——回合机消费）。 */
    fun updateThinkingLevel(level: CodeThinkingLevel) {
        thinkingLevelRef.set(level)
    }

    /** 当前思考档位。 */
    fun thinkingLevel(): CodeThinkingLevel = thinkingLevelRef.get()

    /** 思考档位努力取向文案（回合机每回合刷新——含模型家族偏好）。 */
    fun updateThinkingDirective(directive: String) {
        thinkingDirectiveRef.set(directive)
    }

    /** 布样已确认（PLAN 模式提示词切换到「按步执行」口径）。 */
    fun updatePlanSettled(settled: Boolean) {
        planSettledRef.set(settled)
    }

    fun updateActivePackages(packages: List<LoomActivePackage>) {
        packagesRef.set(packages)
    }

    fun updateActiveFile(activeFile: String?) {
        val cur = workspace.get()
        workspace.set(cur.copy(activeFile = activeFile))
    }

    fun currentWorkspaceId(): String = workspace.get().id

    /** 工作区名（UI 快照用）。 */
    fun currentWorkspaceName(): String = workspace.get().name

    /** 工作区根（权限门边界校验用）。 */
    fun currentWorkspaceRoot(): File? = workspace.get().root

    // ═══════════════════════════ 轮次追加 ═══════════════════════════

    /** 用户输入轮（文本 + 附件标签；图片直通多模态字段）。 */
    fun appendUserInput(input: UserInput, steeringNotes: List<String>) {
        val body = StringBuilder()
        for (s in steeringNotes) {
            body.append(s.trim()).append("\n\n")
        }
        body.append(input.text.trim())
        for (f in input.files) {
            body.append("\n\n").append(
                LoomPrompts.attachmentTag(
                    LoomAttachmentTag(
                        name = f.name,
                        mimeType = f.mimeType,
                        sizeBytes = f.sizeBytes
                    )
                )
            )
        }
        append(LlmMessage.User(content = body.toString().trim(), images = input.images))
    }

    /** 助手回合轮（思考块 + 正文 + use 块——全 in-band，零原生 toolCalls）。 */
    fun appendAssistantRound(
        deliberation: String,
        text: String,
        invocations: List<LoomInvocation>
    ) {
        val body = StringBuilder()
        if (deliberation.isNotBlank()) {
            body.append(LoomMarkupGrammar.wrapDeliberation(deliberation.trim())).append("\n\n")
        }
        if (text.isNotBlank()) {
            body.append(text.trim())
        }
        for (inv in invocations) {
            body.append("\n\n").append(LoomMarkupGrammar.useBlock(inv))
        }
        if (body.isBlank()) return // 空回合不落账（防脏历史）
        append(LlmMessage.Assistant(content = body.toString().trim()))
    }

    /** 原文用户轮（回执 / 转向 / 催促 / 裁决 / 摘要轮的公共落点）。 */
    fun appendUserTurn(content: String) {
        if (content.isBlank()) return
        append(LlmMessage.User(content = content.trim()))
    }

    /** 摘要轮（压缩后的旧轮结晶——恒居历史头）。 */
    fun replaceHeadWithDigest(digestContent: String, tail: List<LlmMessage>) {
        turns.clear()
        turns.add(LlmMessage.User(content = digestContent))
        turns.addAll(tail)
        persistAll()
    }

    /** 全量落账（压缩 / 重放后）。 */
    fun persistAll() {
        memory?.save(turns.toList())
    }

    /** 追加 + 落账。 */
    private fun append(message: LlmMessage) {
        turns.add(message)
        memory?.append(message)
    }

    /** 历史裁剪后的重排（滑窗兜底路径）。 */
    fun replaceHistory(messages: List<LlmMessage>) {
        turns.clear()
        turns.addAll(messages)
        persistAll()
    }

    // ═══════════════════════════ 提示词装配 ═══════════════════════════

    /**
     * 请求消息序列：[系统轮] + 历史。
     *
     * 系统轮每回合 JIT 重织（规则 / 附加段 / 织造包 / 工作区全部实时）。
     */
    fun assembleMessages(toolDefs: List<ToolDefinition>): List<LlmMessage> =
        listOf(LlmMessage.System(assembleSystemPrompt(toolDefs))) + turns

    /** 系统提示词（段落次序见 LoomPrompts KDoc）。 */
    fun assembleSystemPrompt(toolDefs: List<ToolDefinition>): String {
        val env = environmentSource.current()
        val model = modelInfoSource?.current()
        val sections = mutableListOf<String>()
        sections.add(LoomPrompts.identitySection())
        sections.add(
            LoomPrompts.environmentSection(
                platform = env.platformText,
                workdir = env.workdirLabel ?: workspace.get().root?.path,
                isGitRepo = env.isGitRepo,
                modelId = model?.modelId
            )
        )
        workspaceContextSource?.let { src ->
            src.workspaceSection()?.let { sections.add(it) }
            src.globalRulesSection()?.let { sections.add(it) }
            src.projectRulesSection()?.let { sections.add(it) }
        }
        sections.add(
            LoomPrompts.personaSection(roleDefinitionRef.get(), rolePromptRef.get())
        )
        sections.add(LoomPrompts.protocolSection(config.nativeToolCalling))
        sections.add(
            listOf(
                LoomPrompts.deliberationSection(),
                thinkingDirectiveRef.get()
            ).filter { it.isNotBlank() }.joinToString("\n")
        )
        sections.add(LoomPrompts.toolCatalogSection(toolDefs))
        if (modeRef.get() == AgentMode.PLAN) {
            sections.add(
                if (planSettledRef.get()) LoomPrompts.planSettledSection()
                else LoomPrompts.planProtocolSection()
            )
        }
        sections.add(LoomPrompts.packagesSection(packagesRef.get()))
        sections.add(LoomPrompts.extrasSection(extrasRef.get()))
        return LoomPrompts.systemPrompt(sections)
    }

    // ═══════════════════════════ token 计量 ═══════════════════════════

    /** 历史估算 token（含系统轮近似——上下文仪表口径）。 */
    fun estimateContextTokens(toolDefs: List<ToolDefinition>): Int {
        var total = 0
        for (t in turns) total += estimateTurn(t)
        total += assembleSystemPrompt(toolDefs).length / CHAR_PER_TOKEN_HEURISTIC
        return total
    }

    private fun estimateTurn(message: LlmMessage): Int = when (message) {
        is LlmMessage.System -> estimator.estimateTokens(message.content)
        is LlmMessage.User -> estimator.estimateTokens(message.content) +
            IMAGES_TOKEN_COST * message.images.size
        is LlmMessage.Assistant -> estimator.estimateTokens(message.content)
        is LlmMessage.ToolResult -> estimator.estimateTokens(message.content)
    }

    companion object {
        private const val TAG = "LoomSession"
        private const val DEFAULT_WORKSPACE = "default"
        private const val CHAR_PER_TOKEN_HEURISTIC = 4
        private const val IMAGES_TOKEN_COST = 800
    }
}
