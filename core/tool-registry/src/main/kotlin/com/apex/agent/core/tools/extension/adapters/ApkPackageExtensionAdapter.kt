package com.apex.agent.core.tools.extension.adapters

import com.apex.agent.core.tools.extension.ExtensionAdapter
import com.apex.agent.core.tools.extension.ExtensionManifest
import com.apex.agent.core.tools.extension.ExtensionSpecParser
import com.apex.agent.core.tools.extension.ExtensionTypes
import com.apex.agent.core.tools.extension.InstallOutcome
import com.apex.agent.core.tools.extension.InspectReport
import com.apex.agent.core.tools.extension.RuntimeDomains
import com.apex.agent.core.tools.extension.SourceKinds
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * # ApkPackageExtensionAdapter —— APK 扩展包适配器（type = apk_package）
 *
 * APK 扩展包与 npm 系安装**完全隔离**（brief 明确边界）：它的安装语义是
 * 「发起系统安装器（用户必须在系统 UI 上点安装）」——装完后插件宿主经
 * PLUGIN intent-service 自动发现（签名门校验），**不复制文件、不注册
 * AIDL 绑定表**（宿主的发现即注册）。
 *
 * 安全姿态（三门叠加）：
 * 1. resolve/inspect 如实标注「未知的第三方 APK」风险与来源路径；
 * 2. `hasPostinstall = true`（触发系统安装器等同生命周期副作用）→
 *    install 必须携带 confirm 令牌（模型须先向用户确认）；
 * 3. 系统安装器本身是用户确认 UI —— 双重确认。
 */
class ApkPackageExtensionAdapter(
    private val port: ApkInstallPort
) : ExtensionAdapter {

    /** APK 安装执行端口（app 层接 PackageInstaller 会话安装器；单测 fake）。 */
    interface ApkInstallPort {
        /** 发起系统安装（commit 后等用户在系统 UI 确认；返回仅代表已发起）。 */
        suspend fun installApk(apkPath: String): Result<String>
    }

    override val supportedTypes: Set<String> = setOf(ExtensionTypes.APK_PACKAGE)

    override suspend fun resolve(draft: ExtensionSpecParser.SpecDraft): Result<ExtensionManifest> {
        val path = draft.payload
        val file = File(path)
        if (!file.isFile) {
            return Result.failure(IllegalArgumentException("APK 文件不存在或不是文件：$path"))
        }
        if (!file.extension.equals("apk", ignoreCase = true)) {
            return Result.failure(IllegalArgumentException("只接受 .apk 文件（收到：${file.name}）"))
        }
        val name = file.nameWithoutExtension
        val manifest = ExtensionManifest(
            id = "apk.${OpenCodeExtensionAdapter.slugOf(name)}-${(file.length() and 0xFFFFF).toString(16)}",
            type = ExtensionTypes.APK_PACKAGE,
            spec = draft.raw,
            name = name,
            version = "0.0.0", // 包内 versionName 需 PackageManager（Android 侧），resolve 阶段不取
            sourceKind = SourceKinds.LOCAL,
            runtime = RuntimeDomains.ANDROID,
            permissions = listOf("install-app", "aidl-bind"),
            hasPostinstall = true, // 触发系统安装器 = 生命周期副作用，需显式确认
            resolved = buildJsonObject {
                put("kind", "apk"); put("path", path); put("bytes", file.length())
            },
            summary = "APK 扩展包「$name」（${file.length() / 1024}KB）→ 发起系统安装（用户确认后插件宿主自动发现）"
        )
        return Result.success(manifest.copy(manifestHash = manifest.computeHash()))
    }

    override suspend fun inspect(manifest: ExtensionManifest): Result<InspectReport> {
        val path = manifest.resolved?.get("path")?.toString()?.trim('"')
        return Result.success(
            InspectReport(
                manifest = manifest,
                targetRegistry = "Android 系统安装器 → 插件宿主（PLUGIN intent-service 自动发现 + 签名门）",
                targetLocation = "系统应用（包名由 APK 决定）",
                warnings = buildList {
                    add("将发起系统安装 —— 需用户在系统安装 UI 上手动确认（双重确认的第一道）")
                    add("未知来源 APK：插件宿主加载时执行签名门校验，但包内容自省有限 —— 只装可信来源")
                    add("安装完成后在「市场 · Plugins」页可见并可加载（agent tool bridge 按 manifest 声明接入）")
                    path?.let { add("来源文件：$it") }
                }
            )
        )
    }

    override suspend fun install(manifest: ExtensionManifest): Result<InstallOutcome> {
        val path = manifest.resolved?.get("path")?.toString()?.trim('"')
            ?: return Result.failure(IllegalArgumentException("resolved manifest 缺少 path"))
        val message = port.installApk(path).getOrElse { return Result.failure(it) }
        return Result.success(
            InstallOutcome(
                id = manifest.id,
                type = manifest.type,
                version = manifest.version,
                where = "系统安装器（已发起，等待用户确认）",
                notes = listOf(
                    message,
                    "装完后插件宿主经 PLUGIN intent-service 自动发现 —— 到「市场 · Plugins」页加载"
                )
            )
        )
    }

    override suspend fun uninstall(manifest: ExtensionManifest): Result<Unit> =
        Result.failure(
            UnsupportedOperationException(
                "APK 扩展包的卸载走系统「设置 · 应用管理」；本适配器不代替用户执行系统级卸载"
            )
        )
}
