package com.apex.agent.ui.component

/**
 * ═══ 媒体卡本地路径放行策略（#858）═══
 *
 * Markdown 媒体卡（[MarkdownText] 的 VideoCard / 图片链接）的 URL 全部
 * 来自**模型输出**（多模态消息里的 video_url / 模型自己写的 markdown）——
 * 模型可控字符串绝不能原样交给 FileProvider：旧 file_paths.xml 五根全
 * `path="."` 时，一条 `<video src="/data/user/0/<pkg>/files/vault/...">`
 * 就能把应用私有数据（金库 / 记忆库 / 审计日志 / 终端工作区）以
 * FLAG_GRANT_READ_URI_PERMISSION 授权给任意外部应用 —— 等于模型一句话
 * 触发越权外发。
 *
 * 放行边界（默认拒绝，白名单显式放行）：
 *  - 仅**公共共享存储的媒体/下载目录**（/storage/emulated/&lt;n&gt;/ 或
 *    /sdcard/ 下的 DCIM / Pictures / Movies / Music / Download /
 *    Documents 一级目录）—— 这些目录本就对全部持存储权限的应用可见，
 *    交给外部播放器不扩大暴露面；对应 FileProvider 的五个 external-path
 *    根（res/xml/file_paths.xml，两处保持同步）。
 *  - 应用私有目录（/data/data/、/data/user/ 下任意应用的 filesDir /
 *    cacheDir / dataDir）一律拒绝；/data/ 前缀整体短路（含 /data/media
 *    共享存储别名 —— 需要共享存储路径请用 /storage/emulated/ 规范形态）。
 *  - 相对路径、file:// 之外的 scheme（http/content/javascript 等）、含
 *    `..` 段的路径一律拒绝 —— 防 intent 重定向与路径穿越。
 *
 * 纯字符串决策、无 Android 依赖 —— 单测见 MediaOpenPolicyTest。
 * 消费方：[FileOpener.openFile]（媒体卡点击）与 CameraCapturer 的直写
 * 目标判定（tools/MoreCapabilitiesHost.kt —— 拍照 savePath 同为模型可控
 * 参数，白名单外的目标走「cache 临时件 + 拷贝」路径，行为不变）。
 */
object MediaOpenPolicy {

    /** 公共存储一级目录白名单（与 file_paths.xml 的 external-path 根一一对应）。 */
    val PUBLIC_MEDIA_TOP_DIRS: List<String> =
        listOf("DCIM", "Pictures", "Movies", "Music", "Download", "Documents")

    /** 主外部存储根前缀：规范形态 /storage/emulated/&lt;n&gt;/ 与 /sdcard/ 别名。 */
    private val EXTERNAL_ROOT = Regex("^/storage/emulated/\\d+/")

    /** /sdcard/ 直接别名（legacy 路径形态，规范形态之外唯一认可入口）。 */
    private const val SDCARD_ALIAS = "/sdcard/"

    /** 应用私有数据区前缀：/data/ 下的一切（data/user、data/data 等）。 */
    private const val APP_PRIVATE_PREFIX = "/data/"

    /**
     * 模型可控的本地路径是否允许交给 FileProvider / 外部应用。
     *
     * @param url 媒体卡 URL 或工具 savePath（裸绝对路径或 file:// 形态；
     *   http(s) 属 [FileOpener.openUrl] 通道，此处一律不放行）
     */
    fun isAllowed(url: String): Boolean {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return false

        // file:// 形态剥壳取路径；其余任何 scheme（http/content/intent 等）拒绝 ——
        // 交给 openUrl 之外的外部解析属 intent 重定向面。
        val path = if (trimmed.startsWith("file://")) {
            trimmed.removePrefix("file://").takeIf { it.startsWith("/") } ?: return false
        } else {
            trimmed
        }
        if (path.contains("://")) return false

        // 必须是绝对路径（相对路径解析到 CWD，语义不可控）
        if (!path.startsWith("/")) return false

        // 应用私有数据区（含全部应用形态）整体拒绝 —— 这是本策略的核心红线
        if (path.startsWith(APP_PRIVATE_PREFIX)) return false

        // 路径穿越段拒绝（公共目录内的 .. 组合在 FileProvider 侧另有
        // canonical path 复核，这里提前短路保持决策纯字符串可推理）
        if (path.split('/').any { it == ".." }) return false

        // 主外部存储 + 一级目录白名单（次级卷 /storage/<id>/ 不放行 ——
        // file_paths.xml 也未覆盖，保持两处一致）
        val rest = when {
            EXTERNAL_ROOT.containsMatchIn(path) ->
                path.replaceFirst(EXTERNAL_ROOT, "")
            path.startsWith(SDCARD_ALIAS) -> path.removePrefix(SDCARD_ALIAS)
            else -> return false
        }
        val topDir = rest.substringBefore('/').takeIf { it.isNotEmpty() } ?: return false
        return topDir in PUBLIC_MEDIA_TOP_DIRS
    }
}
