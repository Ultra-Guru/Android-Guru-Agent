package com.apex.agent.changelog

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 发布仓库 main 分支上更新日志清单的固定地址 —— release.yml 每次发布提交，
 * 与 version.json 同宿主同链路（raw CDN 零鉴权，公开仓库无需 Token）。
 */
const val CHANGELOG_URL =
    "https://raw.githubusercontent.com/Ultra-Guru/Android-Guru-Agent-Release/main/changelog.json"

/**
 * changelog.json 清单模型 —— 与开发仓库 release.yml（scripts/changelog.py
 * build）生成的 schema（apex-changelog-v1）一一对应。
 *
 * 设计约束（对齐 update 包的 UpdateManifest 纪律）：
 * - **全字段可缺省 + ignoreUnknownKeys**：清单演进不崩老客户端，
 *   老清单（字段缺失）不崩新客户端；
 * - **notes 优先渲染**：构建期已从片段 Markdown 列表项剥壳提取，
 *   App 端零 Markdown 依赖（无渲染器时 notes 即结构化要点；markdown
 *   仅作 notes 缺失时的纯文本降级源）。
 */
@Serializable
data class ChangelogManifest(
    @SerialName("schema") val schema: String? = null,
    @SerialName("generatedAt") val generatedAt: String? = null,
    /** 当前最新发布版本的日志（App 弹窗的首选内容源）。 */
    @SerialName("latest") val latest: ChangelogEntry? = null,
    /** 历史版本日志（最新在前，封顶 50 条 —— 关于页「更新日志」入口的数据源）。 */
    @SerialName("history") val history: List<ChangelogEntry> = emptyList(),
)

/** 单个版本的更新日志条目（latest 与 history 元素同构）。 */
@Serializable
data class ChangelogEntry(
    @SerialName("versionName") val versionName: String,
    @SerialName("versionCode") val versionCode: Int,
    @SerialName("tag") val tag: String? = null,
    /** 发布日期（YYYY-MM-DD，展示用）。 */
    @SerialName("date") val date: String? = null,
    /** 弹窗标题（构建期生成「vX.Y.Z 更新内容」）。 */
    @SerialName("title") val title: String? = null,
    /** 要点列表（片段列表项剥壳）—— UI 首选渲染源。 */
    @SerialName("notes") val notes: List<String> = emptyList(),
    /** 片段原文（Markdown）—— notes 为空时的纯文本降级源，不引渲染器。 */
    @SerialName("markdown") val markdown: String? = null,
    /** 本版 Release 页（「发现新版本」弹窗的前往下载出口）。 */
    @SerialName("releaseUrl") val releaseUrl: String? = null,
)
