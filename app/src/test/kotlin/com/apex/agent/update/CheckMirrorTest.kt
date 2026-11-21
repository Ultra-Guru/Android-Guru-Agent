package com.apex.agent.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CheckMirror] 单元测试 —— 检查链路镜像回退的候选表构造。
 *
 * 覆盖：镜像在前直连殿后的顺序契约 / 前缀拼接保真 / 候选数量与
 * [DownloadMirror] 实体节点一一对应 / 直连候选原样返回。
 *
 * （[CheckMirror.fetch] 的网络回退与伪 200 防御依赖 OkHttp 真连接，
 * 不在 JVM 单测射程 —— 由真机关于页「检查更新」人工验收。）
 */
class CheckMirrorTest {

    private val rawManifestUrl =
        "https://raw.githubusercontent.com/Ultra-Guru/Android-Guru-Agent-Release/main/version.json"

    @Test
    fun `候选表镜像在前直连殿后`() {
        val candidates = CheckMirror.candidates(rawManifestUrl)

        assertEquals(
            "候选数 = 实体镜像节点数 + 1 个直连",
            DownloadMirror.NODES.size + 1,
            candidates.size
        )
        // 前 N 项逐位对应实体节点的前缀拼接，末项必须是原样直链
        candidates.dropLast(1).forEachIndexed { index, url ->
            assertEquals(
                DownloadMirror.NODES[index].prefix + rawManifestUrl,
                url
            )
        }
        assertEquals(rawManifestUrl, candidates.last())
    }

    @Test
    fun `所有镜像候选以前缀开头以直链收尾`() {
        for (url in CheckMirror.candidates(rawManifestUrl).dropLast(1)) {
            assertTrue(
                "镜像候选必须挂在某个实体节点前缀上：$url",
                DownloadMirror.NODES.any { url.startsWith(it.prefix) }
            )
            assertTrue(
                "镜像候选必须以完整 raw 直链收尾（gh 系反代约定）：$url",
                url.endsWith(rawManifestUrl)
            )
        }
    }

    @Test
    fun `直连候选原样返回不拼接`() {
        val last = CheckMirror.candidates(rawManifestUrl).last()

        assertEquals(
            "直连候选与入参逐字节一致（绝不能拼出坏 URL）",
            rawManifestUrl,
            last
        )
    }

    @Test
    fun `补丁索引与日志清单同样进入回退链`() {
        // 三份清单同链路：任一 raw 直链的候选表形态一致
        for (raw in listOf(
            rawManifestUrl,
            "https://raw.githubusercontent.com/Ultra-Guru/Android-Guru-Agent-Release/main/patches.json",
            "https://raw.githubusercontent.com/Ultra-Guru/Android-Guru-Agent-Release/main/changelog.json"
        )) {
            val candidates = CheckMirror.candidates(raw)
            assertEquals(DownloadMirror.NODES.size + 1, candidates.size)
            assertEquals(raw, candidates.last())
        }
    }
}
