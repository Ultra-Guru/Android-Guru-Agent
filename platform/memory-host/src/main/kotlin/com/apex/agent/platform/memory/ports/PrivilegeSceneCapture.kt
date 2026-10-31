package com.apex.agent.platform.memory.ports

import android.content.Context
import android.content.pm.PackageManager
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import com.apex.agent.platform.privilege.PrivilegeManager
import com.apex.agent.platform.privilege.accessibility.ApexAccessibilityService
import com.apex.memkit.core.RawCapture
import com.apex.memkit.core.RawNode
import com.apex.memkit.core.SceneCapture
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * UI 场景捕获端口 —— [PrivilegeManager] → 库 [SceneCapture] 的适配器。
 *
 * **单源纪律（P0-1/P0-2 根治的宿主侧半边）**：
 * - 指纹的唯一来源是库内「捕获 → 降维」路径，本适配器是宿主侧唯一捕获点
 *   —— 采集（SessionDirector）与匹配（tryBypass）共用同一 [capture] 产物，
 *   不存在第二条自算指纹的路径（旧系统采集用 roots 嵌套树、匹配用 nodes
 *   扁平剥离视图的断链形态已从结构上消灭）；
 * - [RawCapture.appPackage] 取前台包名（无障碍服务解析），未知为 null
 *   —— Episode / 宏 / 匹配全程用这同一个值贯穿；
 * - [RawCapture.appVersion] 取**目标 App** 版本（PackageManager 缓存解析）
 *   —— 旧系统把宿主自身版本错记在节点上，外部 App 升级的指纹漂移永远
 *   无触发源；现在拓扑别名迁移有了正确的版本信号。
 *
 * 嵌套树契约：喂给库的是 [com.apex.agent.platform.privilege.UiTreeResult.roots]
 * （真实嵌套树，children 已填充）；[UiTreeResult.nodes] 是剥离子引用的兼容
 * 视图，**禁止**作为捕获产物（父上下文指纹会系统性丢失）。
 */
@Singleton
class PrivilegeSceneCapture @Inject constructor(
    private val privilegeManager: PrivilegeManager,
    @ApplicationContext private val context: Context,
) : SceneCapture {

    /** 目标 App 版本名缓存（PackageManager 解析一次，升级窗口内复用）。 */
    private val versionCache = java.util.concurrent.ConcurrentHashMap<String, String?>()

    override suspend fun capture(): RawCapture? {
        val tree = runCatching { privilegeManager.getUiTree() }.getOrNull()
        if (tree == null || !tree.success) return null
        val roots = tree.roots.ifEmpty { tree.nodes }
        if (roots.isEmpty()) return null
        val pkg = runCatching {
            ApexAccessibilityService.instance?.getForegroundPackage()
        }.getOrNull()
        return RawCapture(
            roots = roots.map { it.toRaw() },
            appPackage = pkg,
            appVersion = pkg?.let { resolveVersion(it) },
            activityLabel = null,
        )
    }

    private fun resolveVersion(pkg: String): String? {
        if (pkg.isBlank()) return null
        versionCache[pkg]?.let { return it }
        val version = runCatching {
            context.packageManager.getPackageInfo(pkg, 0).versionName
        }.getOrNull().also {
            AppLogger.instance.debug(LogCategory.CS_MEM, TAG, "目标 App 版本解析：$pkg → $it")
        }
        versionCache[pkg] = version
        return version
    }

    /** 宿主 UiNode → 库 RawNode（字段一一对应，零语义转换）。 */
    private fun com.apex.agent.platform.privilege.UiNode.toRaw(): RawNode = RawNode(
        className = className,
        text = text,
        contentDescription = contentDescription,
        resourceId = resourceId,
        bounds = bounds,
        clickable = clickable,
        scrollable = scrollable,
        children = children.map { it.toRaw() },
    )

    private companion object {
        private const val TAG = "SceneCapture"
    }
}
