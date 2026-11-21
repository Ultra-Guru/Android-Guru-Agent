package com.apex.agent.tools

import com.apex.agent.core.engine.templates.playbook.BuiltinPlaybooks
import com.apex.agent.core.engine.templates.playbook.PlaybookRegistry
import com.apex.agent.core.tools.AgentTool
import com.apex.agent.ui.screen.code.PlaybookSessionStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * # PlaybookTool — 任务剧本 agent 工具通道（task-playbooks PR-C）
 *
 * 让 agent **自己**感知与推进剧本（引擎指令注入是被动面，本工具是主动面）：
 *
 * - `list`：剧本清单（id/名称/幕数/当前挂载态）——agent 知道有哪些手册；
 * - `stage`：取指定幕（缺省当前幕）的子提示词 + 验收条款——按需拉取，
 *   token 渐进披露（侦察附录随挂载指令注入，不在工具输出重复）；
 * - `advance`：自报当前幕完成 → 命令经 [PlaybookSessionStore] 流向
 *   控制器（Ops 层收集）→ 状态迁移 + extras 热重织 + 系统消息。
 *
 * 输出全中文（agent 侧消费口径与剧本正文一致）。推进是**显式工具调用**，
 * 语义等同用户指令——信任直推，不走回包判定器（判定器管被动推进）。
 */
class PlaybookTool(
    private val registry: PlaybookRegistry,
    private val sessionStore: PlaybookSessionStore
) : AgentTool {

    override val id: String = "PlaybookQuery"

    override val name: String = "Playbook Query"

    override val description: String = """
        查询与推进「任务剧本」（分幕战术手册）。
        - action=list：列出可用剧本与当前挂载态（开始大任务前建议先看一眼）；
        - action=stage：取某幕子提示词与验收条款（playbook_id 必填，stage_index 缺省=当前幕）；
        - action=advance：自报当前幕已完成（挂载中才有效），推进至下一幕。
    """.trimIndent()

    override val parametersSchema: String = """
        {
            "type": "object",
            "properties": {
                "action": {"type": "string", "enum": ["list", "stage", "advance"], "description": "list=剧本清单; stage=取幕子提示词; advance=自报当前幕完成"},
                "playbook_id": {"type": "string", "description": "剧本 id（stage/advance 必填；list 可选=只看这一个）"},
                "stage_index": {"type": "integer", "description": "幕序号（1 基，stage 缺省=当前幕；advance 忽略）"}
            },
            "required": ["action"]
        }
    """.trimIndent()

    override suspend fun execute(arguments: String): String {
        val args = runCatching { Json.parseToJsonElement(arguments).jsonObject }
            .getOrElse { return "Error: 参数不是合法 JSON 对象：${it.message}" }
        val action = args["action"]?.jsonPrimitive?.contentOrNull
            ?: return "Error: 缺少 action 参数"
        val playbookId = args["playbook_id"]?.jsonPrimitive?.contentOrNull
        val stageIndexRaw = args["stage_index"]?.jsonPrimitive?.intOrNull
        return when (action) {
            ACTION_LIST -> renderList(playbookId)
            ACTION_STAGE -> renderStage(playbookId, stageIndexRaw)
            ACTION_ADVANCE -> advance(playbookId)
            else -> "Error: 未知 action '$action'（可用：list / stage / advance）"
        }
    }

    // ═══ actions ═══

    private fun renderList(filterId: String?): String {
        val candidates = (registry.list().ifEmpty { BuiltinPlaybooks.ALL })
            .filter { filterId == null || it.id == filterId }
        if (candidates.isEmpty()) return "无匹配剧本。"
        val snapshot = sessionStore.snapshot.value
        val lines = candidates.map { playbook ->
            val mountedMark = when {
                snapshot?.playbookId == playbook.id -> "（当前挂载：幕${snapshot.stageIndex + 1}/${snapshot.stageCount}）"
                else -> ""
            }
            "• ${playbook.id}「${playbook.name}」${playbook.stageCount} 幕 [${playbook.mode}]" +
                " — ${playbook.description}$mountedMark"
        }
        return "可用剧本 ${candidates.size} 套：\n" + lines.joinToString("\n")
    }

    private suspend fun renderStage(playbookId: String?, stageIndexRaw: Int?): String {
        val (playbook, stageIdx) = resolveStageTarget(playbookId, stageIndexRaw)
            ?: return "Error: 未挂载剧本且未提供 playbook_id——先 list 查 id，或让用户挂载。"
        val stage = playbook.stageAt(stageIdx)
            ?: return "Error: 幕序号越界（1..${playbook.stageCount}）。"
        return buildString {
            appendLine("剧本「${playbook.name}」幕${stageIdx + 1}·${stage.title}（${playbook.mode}，共 ${playbook.stageCount} 幕）")
            appendLine()
            appendLine(stage.subPrompt)
            if (stage.acceptance.isNotEmpty()) {
                appendLine()
                append("本幕验收：")
                append(stage.acceptance.joinToString("；"))
                append("。")
            }
        }
    }

    private suspend fun advance(playbookId: String?): String {
        val snapshot = sessionStore.snapshot.value
            ?: return "Error: 当前无挂载剧本——advance 只在挂载态可用（挂载由用户/建议条完成）。"
        if (playbookId != null && playbookId != snapshot.playbookId) {
            return "Error: playbook_id 与挂载剧本不符（挂载的是 ${snapshot.playbookId}）。"
        }
        if (snapshot.stageIndex >= snapshot.stageCount - 1) {
            return "提示：当前已是末幕（幕${snapshot.stageCount}）——完成产出后直接输出「${com.apex.agent.core.engine.templates.playbook.PlaybookDirectiveBuilder.COMPLETION_TOKEN}」收尾即可，无需 advance。"
        }
        sessionStore.sendAdvance(
            PlaybookSessionStore.AdvanceCommand(
                playbookId = snapshot.playbookId,
                fromStageIndex = snapshot.stageIndex
            )
        )
        val next = snapshot.stageIndex + 1
        return "已受理：幕${snapshot.stageIndex + 1}（${snapshot.stageTitle}）自报完成，推进至幕${next + 1}。下一幕指令将在本轮后续迭代注入。"
    }

    /** 幕目标解析：playbook_id 显式给 → 校验存在；缺省 → 快照当前幕。 */
    private fun resolveStageTarget(
        playbookId: String?,
        stageIndexRaw: Int?
    ): Pair<com.apex.agent.core.engine.templates.playbook.PlaybookDefinition, Int>? {
        val playbook = when {
            playbookId != null -> registry.get(playbookId)
                ?: BuiltinPlaybooks.ALL.firstOrNull { it.id == playbookId }
                ?: return null
            else -> {
                val snapshot = sessionStore.snapshot.value ?: return null
                registry.get(snapshot.playbookId)
                    ?: BuiltinPlaybooks.ALL.firstOrNull { it.id == snapshot.playbookId }
                    ?: return null
            }
        }
        val stageIdx = when {
            stageIndexRaw != null -> stageIndexRaw - 1
            else -> sessionStore.snapshot.value
                ?.takeIf { it.playbookId == playbook.id }?.stageIndex
                ?: 0
        }
        return playbook to stageIdx
    }

    private companion object {
        const val ACTION_LIST = "list"
        const val ACTION_STAGE = "stage"
        const val ACTION_ADVANCE = "advance"
    }
}
