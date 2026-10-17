package com.apex.agent.plugin.game

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.apex.agent.plugin.api.IApexPlugin
import com.apex.agent.plugin.api.PluginContract
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 游戏插件模板服务（WebView 壳 APK 插件）。
 *
 * 职责：向宿主声明元数据与一个 `game_play_<id>` 工具 —— 调用即启动
 * [GameActivity]（全屏 WebView 运行 assets/game.html）。游戏本体仍是
 * 自包含 HTML（沙箱纪律与游乐场 HtmlGameScreen 一致：零网络请求）。
 *
 * 拷贝本模板后必改：[GAME_ID] / [GAME_NAME] / [GAME_DESC] 三个常量
 * （或按 plugin-workflow 先例改为从 meta-data 读取）。
 *
 * AIDL 协议形态与 plugins/plugin-workflow 参考实现一致；版本门锚定
 * [PluginContract.HOST_API_VERSION]。
 */
class GamePluginService : Service() {

    private val binder = GamePluginBinder()

    override fun onBind(intent: Intent): IBinder = binder

    inner class GamePluginBinder : IApexPlugin.Stub() {

        override fun getMetadataJson(): String = buildJsonObject {
            put("id", GAME_ID)
            put("name", GAME_NAME)
            put("version", 1)
            put("versionName", "1.0.0")
            put("minHostVersion", PluginContract.HOST_API_VERSION)
            put("description", GAME_DESC)
        }.toString()

        override fun getToolsJson(): String = buildJsonArray {
            addJsonObject {
                put("id", TOOL_PLAY)
                put("name", "启动游戏")
                put("description", "全屏启动 $GAME_NAME（WebView 壳运行自包含 HTML 游戏插件）")
                put("parametersSchema", "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}")
            }
        }.toString()

        override fun executeTool(toolId: String, argumentsJson: String): String {
            if (toolId != TOOL_PLAY) {
                return "Error: unknown tool $toolId (expected $TOOL_PLAY)"
            }
            // 游戏是 UI 实体：工具回执引导启动 Activity，游戏内战绩经
            // localStorage 落在本插件自己的 WebView DOM storage 里。
            val started = runCatching {
                startActivity(
                    Intent(this@GamePluginService, GameActivity::class.java).addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK
                    )
                )
                true
            }.getOrElse { false }
            return if (started) {
                "OK: $GAME_NAME 已启动（全屏 WebView）。游戏结束关闭页面即回到宿主。"
            } else {
                "Error: 启动 $GAME_NAME 失败（${GAME_ID} 插件进程无 Activity 启动能力）"
            }
        }

        override fun onActivate() = Unit

        override fun onDeactivate() = Unit

        override fun attachHost(hostBinder: IBinder?) = Unit
    }

    private companion object {
        // ═══ 拷贝后必改的三处身份 ═══
        const val GAME_ID = "com.apex.agent.plugin.game.mygame"
        const val GAME_NAME = "我的 HTML 游戏"
        const val GAME_DESC = "游乐场 HTML 游戏的 WebView 壳 APK 插件（转化模板产物）"

        // 工具 id 带命名空间，天然不与内置工具撞车
        const val TOOL_PLAY = "game_play_mygame"
    }
}
