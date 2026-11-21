package com.apex.agent.ui.chatkit

import androidx.compose.runtime.Immutable

/**
 * 待发送的流水线指令（迷你胶囊）。
 *
 * 用户从斜杠菜单选中 Skill / MCP / 插件 / 连接器时，不再把 `/skill:xxx`
 * 裸文本塞进输入框，而是以结构化胶囊挂在输入栏上：
 * - 胶囊按类型渲染图标与「type: 名称」标签；
 * - 发送时与输入框文本拼回 `"/type:id 附加文本"` 走既有斜杠管线
 *   （解析/路由/横幅/工具来源标记全部复用，零新链路）；
 * - 再选一条直接替换当前胶囊（单条语义：一次发送触发一条流水线）。
 *
 * @param type 指令类型（"skill" / "mcp" / "connector" / "plugin" / "fusion"）。
 * @param id 指令 id（斜杠命令冒号后的部分）。
 * @param label 展示名（菜单项 label，优先于裸 id 呈现）。
 */
@Immutable
data class PendingPipelineCommand(
    val type: String,
    val id: String,
    val label: String
) {
    /** 拼回斜杠命令首词：`/skill:WebSearch`。 */
    fun toCommandToken(): String = "/$type:$id"

    companion object {
        /**
         * 从斜杠菜单命令串解析胶囊（宽容失败 → null 走旧文本插入路径）。
         * 输入形态：`/skill:WebSearch `（菜单命令自带尾随空格）或无空格裸命令。
         */
        fun fromCommand(command: String, label: String): PendingPipelineCommand? {
            val token = command.trim()
            if (!token.startsWith("/")) return null
            val colon = token.indexOf(':')
            if (colon <= 1) return null
            val type = token.substring(1, colon).lowercase()
            val id = token.substring(colon + 1).trim()
            if (id.isEmpty()) return null
            if (type !in setOf("skill", "mcp", "connector", "plugin", "fusion")) return null
            return PendingPipelineCommand(type = type, id = id, label = label.ifBlank { id })
        }
    }
}
