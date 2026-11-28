package com.apex.agent.ui.component

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 媒体卡本地路径白名单决策表（#858）。
 *
 * 决策纯字符串、无 Android 依赖 —— 这里钉住三条红线：
 *  1. 应用私有目录（/data/ 下任何形态）一律拒绝 —— 模型可控的 URL 绝不能
 *     把金库/记忆库/审计日志授权给任意外部应用；
 *  2. 仅公共存储媒体/下载一级目录放行（与 file_paths.xml 的
 *     external-path 根一一对应）；
 *  3. 非常规 scheme / 相对路径 / 路径穿越段一律拒绝（fail-closed）。
 */
class MediaOpenPolicyTest {

    // ── 红线 1：应用私有目录（含 file:// 形态）一律拒绝 ──────────────

    @Test
    fun `应用私有 files 目录路径被拒绝`() {
        assertFalse(
            MediaOpenPolicy.isAllowed("/data/user/0/com.apex.agent/files/linux/workspaces/default/out.mp4")
        )
    }

    @Test
    fun `应用私有 cache 目录路径被拒绝`() {
        assertFalse(MediaOpenPolicy.isAllowed("/data/data/com.apex.agent/cache/exports/x.txt"))
    }

    @Test
    fun `file 形态的应用私有路径同样被拒绝`() {
        assertFalse(
            MediaOpenPolicy.isAllowed("file:///data/user/0/com.apex.agent/files/vault/secret.json")
        )
    }

    @Test
    fun `其他应用的私有目录也被拒绝`() {
        assertFalse(MediaOpenPolicy.isAllowed("/data/user/0/com.other.app/files/pic.jpg"))
    }

    // ── 红线 2：公共存储媒体/下载目录放行 ──────────────────────────

    @Test
    fun `公共媒体目录路径放行`() {
        assertTrue(MediaOpenPolicy.isAllowed("/storage/emulated/0/DCIM/IMG_001.jpg"))
        assertTrue(MediaOpenPolicy.isAllowed("/storage/emulated/0/Pictures/screenshot.png"))
        assertTrue(MediaOpenPolicy.isAllowed("/storage/emulated/0/Movies/demo.mp4"))
        assertTrue(MediaOpenPolicy.isAllowed("/storage/emulated/0/Music/a.mp3"))
        assertTrue(MediaOpenPolicy.isAllowed("/storage/emulated/0/Download/ApexAgent/update.apk"))
        assertTrue(MediaOpenPolicy.isAllowed("/storage/emulated/0/Documents/notes.md"))
    }

    @Test
    fun `file 形态的公共媒体目录路径放行`() {
        assertTrue(MediaOpenPolicy.isAllowed("file:///storage/emulated/0/DCIM/IMG_001.jpg"))
    }

    @Test
    fun `sdcard 别名形态的公共媒体目录路径放行`() {
        assertTrue(MediaOpenPolicy.isAllowed("/sdcard/DCIM/IMG_001.jpg"))
    }

    @Test
    fun `多用户卷形态的公共媒体目录路径放行`() {
        assertTrue(MediaOpenPolicy.isAllowed("/storage/emulated/10/Pictures/a.png"))
    }

    // ── 红线 3：非常规形态一律拒绝（fail-closed）───────────────────

    @Test
    fun `空白与相对路径被拒绝`() {
        assertFalse(MediaOpenPolicy.isAllowed(""))
        assertFalse(MediaOpenPolicy.isAllowed("   "))
        assertFalse(MediaOpenPolicy.isAllowed("DCIM/IMG_001.jpg"))
    }

    @Test
    fun `http 与 https 被拒绝`() {
        // 远程 URL 走 FileOpener.openUrl 通道（另有 scheme 白名单）
        assertFalse(MediaOpenPolicy.isAllowed("https://example.com/v.mp4"))
        assertFalse(MediaOpenPolicy.isAllowed("http://example.com/v.mp4"))
    }

    @Test
    fun `content 与其它 scheme 被拒绝`() {
        assertFalse(MediaOpenPolicy.isAllowed("content://media/external/video/1"))
        assertFalse(MediaOpenPolicy.isAllowed("javascript:alert(1)"))
    }

    @Test
    fun `file 形态的相对路径被拒绝`() {
        assertFalse(MediaOpenPolicy.isAllowed("file://DCIM/IMG_001.jpg"))
    }

    @Test
    fun `路径穿越段被拒绝`() {
        assertFalse(
            MediaOpenPolicy.isAllowed("/storage/emulated/0/DCIM/../../../data/user/0/com.apex.agent/files/vault/secret.json")
        )
    }

    @Test
    fun `公共存储的非媒体一级目录被拒绝`() {
        assertFalse(MediaOpenPolicy.isAllowed("/storage/emulated/0/agent-out/video.mp4"))
        assertFalse(MediaOpenPolicy.isAllowed("/storage/emulated/0/Android/data/com.apex.agent/files/a.jpg"))
    }

    @Test
    fun `次级卷路径被拒绝`() {
        // 与 file_paths.xml 保持一致：仅主外部存储根，次级卷未声明对应根
        assertFalse(MediaOpenPolicy.isAllowed("/storage/0000-0000/DCIM/IMG_001.jpg"))
    }

    @Test
    fun `小写形态的一级目录名被拒绝`() {
        // Android 目录名大小写敏感：白名单按规范形态精确匹配
        assertFalse(MediaOpenPolicy.isAllowed("/storage/emulated/0/dcim/IMG_001.jpg"))
    }
}
