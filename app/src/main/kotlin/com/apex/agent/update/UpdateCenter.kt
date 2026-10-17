package com.apex.agent.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.apex.agent.BuildConfig
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import com.apex.agent.core.tools.skill.SkillRegistry
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ═══════════════════════════════════════════════════════════════════════════
 *  UpdateCenter —— 应用级更新中枢（v1.4.5）
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * 为什么需要它：v1.4.4 及之前 [PatchUpdateEngine] 挂在「关于页」的组合域协程上
 * —— 用户一离开页面，下载轮询与链式合成随即取消，只留系统 DownloadManager
 * 里的半截补丁；下次进来还得从头重下重合成。本单例把更新流水线提升到
 * **应用生命周期**：
 *
 * - **后台持续**：自有 [scope]（SupervisorJob + IO），页面导航/重建不断流；
 *   「关于页」只是它的一块仪表盘（订阅 [patchState] 渲染）。
 * - **断点续传**：已完成的补丁段（SHA-256 对账通过）落盘保留，重启流水线
 *   时逐段跳过 —— 中断后从断点继续，不重拉已下载的分段（见
 *   [PatchUpdateEngine.start]）。
 * - **全局检测**：App 启动即静默检查一次（[checkForUpdate]，6 小时节流），
 *   结果经 [checkState] 供关于页渲染；新版本提醒统一走更新日志弹窗
 *   （com.apex.agent.changelog）+ 关于页更新面板（顶部常驻浮窗横幅已移除）。
 * - **状态共享**：[checkState] / [patchIndex] / [patchState] 三个 StateFlow
 *   即全部真相源；关于页与未来的通知栏入口都读同一份。
 *
 * v1.4.7 热更新体系接入（docs/hot-update-pipeline.md）：
 * - **有效版本口径**：[checkForUpdate] 内部以 max(包 versionCode, 已应用热更
 *   目标) 比对清单 —— 数据层已热更到 N 的设备不再被重复推 N 版；
 * - **热更流水线**：[hotState] / [startHotFlow] 与增量流水线同生命周期语义
 *   （应用级 + 断点复用），生效后自动重检刷新 UI；
 * - **签名预检**：[signatureMismatch] 比对本地签名证书指纹与清单
 *   `signingCertSha256` —— 不一致时增量/全量覆盖安装必报签名冲突，UI 提前
 *   劝导热更/卸载重装，不再让用户下载几百 MB 后才撞墙。
 *
 * 初始化：`ApexApp.onCreate → UpdateCenter.ensure(this)`（幂等）。全部
 * 耗时操作在 IO 线程；UI 只做 `collectAsStateWithLifecycle`。
 */
object UpdateCenter {

    /** 检查节流窗：同一会话/短时间内不重复拉清单（毫秒）。 */
    private const val CHECK_THROTTLE_MS = 6L * 60 * 60 * 1000

    /** 更新偏好（与 [MirrorPrefs] 同一 SharedPreferences 文件）。 */
    private const val PREFS = "apex_update_prefs"
    private const val KEY_LAST_CHECK = "last_check_epoch_ms"

    /** 进程级单次初始化守卫。 */
    private val initialized = AtomicBoolean(false)

    /** 应用上下文（[ensure] 注入；全部用 applicationContext，绝无 Activity 泄漏）。 */
    private lateinit var appContext: Context

    /** 更新链路共享作用域 —— 应用生命周期存活（页面导航不取消流水线）。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val checker = UpdateChecker()

    val downloader: UpdateDownloader by lazy { UpdateDownloader(appContext) }

    val patchEngine: PatchUpdateEngine by lazy { PatchUpdateEngine(appContext, downloader) }

    /** 热更内容仓（overlay 落位/读取/退役 —— 见 [HotContentStore]）。 */
    val hotStore: HotContentStore by lazy {
        HotContentStore(
            appContext.filesDir,
            HotContentStore.SharedPrefs(appContext),
            BuildConfig.VERSION_CODE
        )
    }

    /** 热更流水线（下载→校验→落位→技能即时注入；技能重放经 Hilt 接线）。 */
    val hotEngine: HotUpdateEngine by lazy {
        HotUpdateEngine(downloader, hotStore, hiltSkillReapplier)
    }

    /**
     * 技能重放出口：从 Hilt 单例图取 [SkillRegistry]（本 object 非 DI 托管，
     * EntryPoint 是标准姿势）。失败折叠 -1 —— 引擎只留痕（下次启动
     * SkillModule 会重放，热更内容不丢）。
     */
    private val hiltSkillReapplier = HotUpdateEngine.SkillReapplier { manifests ->
        runCatching {
            EntryPointAccessors.fromApplication(appContext, UpdateSkillsEntryPoint::class.java)
                .skillRegistry()
                .installBundled(manifests)
                .getOrDefault(0)
        }.getOrDefault(-1)
    }

    /** 热更流水线访问 SkillRegistry 的 EntryPoint（object 单例接 DI 的标准姿势）。 */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface UpdateSkillsEntryPoint {
        fun skillRegistry(): SkillRegistry
    }

    // ═══ 状态流（唯一真相源）════════════════════════════════════════════

    /** 更新检查状态：Idle → Checking → Done(result)。 */
    sealed interface CheckState {
        data object Idle : CheckState
        data object Checking : CheckState
        data class Done(val result: UpdateCheckResult) : CheckState
    }

    private val _checkState = MutableStateFlow<CheckState>(CheckState.Idle)
    val checkState: StateFlow<CheckState> = _checkState.asStateFlow()

    /** 补丁全量索引（跨版本/多基底单跳数据源；有新版时才拉取）。 */
    private val _patchIndex = MutableStateFlow<PatchIndex.Model?>(null)
    val patchIndex: StateFlow<PatchIndex.Model?> = _patchIndex.asStateFlow()

    /** 增量流水线状态（null = 未在流水线中；Ready/Installed/Failed 终态保留）。 */
    private val _patchState = MutableStateFlow<PatchUpdateEngine.State?>(null)
    val patchState: StateFlow<PatchUpdateEngine.State?> = _patchState.asStateFlow()

    /** 热更流水线状态（null = 未在流水线中；Applied/Failed 终态保留）。 */
    private val _hotState = MutableStateFlow<HotUpdateEngine.State?>(null)
    val hotState: StateFlow<HotUpdateEngine.State?> = _hotState.asStateFlow()

    // ═══ 生命周期 ═══════════════════════════════════════════════════════

    /** 幂等初始化（ApexApp.onCreate 调用）。重复调用无副作用。 */
    fun ensure(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        appContext = context.applicationContext
        AppLogger.instance.debug(
            LogCategory.SYSTEM, "UpdateCenter", "更新中枢就绪（应用级流水线 + 断点续传 + 启动检测）"
        )
    }

    /** 测试/进程重启防御：未初始化时惰性补齐（持有的是 applicationContext）。 */
    private fun requireContext(context: Context? = null): Context {
        if (initialized.get()) return appContext
        val fallback = context ?: error("UpdateCenter 未初始化（应在 ApexApp.onCreate ensure）")
        ensure(fallback)
        return appContext
    }

    // ═══ 更新检查 ═══════════════════════════════════════════════════════

    /**
     * 静默检查更新（App 启动 / 关于页手动刷新共用入口）。
     *
     * v1.4.7：比对基准改为**有效版本**（max(包 versionCode, 已应用热更目标)）——
     * 数据层已热更到清单版本的设备直接判 UpToDate，不再重复推同版。
     *
     * @param force true = 跳过节流（用户手动点「检查更新」/关于页进入）
     * @param fromTrigger 调用来源（日志定位用）
     */
    fun checkForUpdate(
        currentVersionCode: Int,
        force: Boolean = false,
        fromTrigger: String = "manual"
    ) {
        val context = requireContext()
        if (!force && isThrottled(context)) {
            AppLogger.instance.debug(
                LogCategory.SYSTEM, "UpdateCenter", "检查节流命中（$fromTrigger）—— 6h 内已查过"
            )
            return
        }
        if (_checkState.value is CheckState.Checking) return
        _checkState.value = CheckState.Checking
        // v1.4.9 闪退防御：检查链（热更版本口径 / 清单拉取 / 补丁索引）整体
        // runCatching —— 本中枢自有 scope 无 CoroutineExceptionHandler，任何
        // 逃逸异常会杀死进程；更新检查是增益路径，失败只留痕并复位状态。
        scope.launch {
            runCatching {
                // 有效版本口径：数据层（已应用热更）与二进制层取大（见 HotUpdatePolicy）
                val effective = hotStore.effectiveVersionCode()
                if (effective != currentVersionCode) {
                    AppLogger.instance.info(
                        LogCategory.SYSTEM, "UpdateCenter",
                        "有效版本口径：包 $currentVersionCode + 热更 → $effective"
                    )
                }
                val result = checker.check(effective)
                if (result is UpdateCheckResult.Available) {
                    // 有新版才拉补丁全量索引（多基底单跳/跨版链数据源）
                    _patchIndex.value = checker.fetchPatchIndex()
                }
                _checkState.value = CheckState.Done(result)
                markChecked(context)
                AppLogger.instance.info(
                    LogCategory.SYSTEM, "UpdateCenter",
                    "更新检查完成（$fromTrigger）：${result.javaClass.simpleName}"
                )
            }.onFailure { e ->
                _checkState.value = CheckState.Idle
                AppLogger.instance.error(
                    LogCategory.SYSTEM, "UpdateCenter",
                    "更新检查失败（$fromTrigger，已降级）：${e.message}"
                )
            }
        }
    }

    private fun isThrottled(context: Context): Boolean {
        val last = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_CHECK, 0L)
        return last > 0 && System.currentTimeMillis() - last < CHECK_THROTTLE_MS
    }

    private fun markChecked(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
    }

    // ═══ 增量流水线（应用级 —— 后台持续 + 断点续传）════════════════════

    /**
     * 启动增量流水线（关于页按钮触发；此后可随意离开页面，下载与合成继续）。
     * 已在跑则忽略（防重入）；引擎回调直接落 StateFlow，任何页面回来都能续看。
     */
    fun startPatchFlow(chain: PatchIndex.Chain, manifest: UpdateManifest, mirror: DownloadMirror) {
        if (patchEngine.isRunning) return
        patchEngine.start(scope, chain, manifest, mirror) { state ->
            _patchState.value = state
        }
    }

    /** 就绪产物 → 系统安装器（不依赖流水线协程，可对 Ready 产物反复重发）。 */
    fun launchInstaller(apk: java.io.File) {
        patchEngine.launchInstaller(apk) { state -> _patchState.value = state }
    }

    // ═══ 热更流水线（v1.4.7 —— 零安装通道）════════════════════════════

    /**
     * 判定当前命中清单的热更适用性（UI 卡片开关；门控见 [HotUpdatePolicy]）。
     * 包指纹随行 —— 发布仓库应急重发同版本包时，客户端凭指纹重新应用。
     */
    fun resolveHotUpdate(manifest: UpdateManifest): HotAsset? = checker.resolveHotUpdate(
        manifest,
        hotStore.effectiveVersionCode(),
        hotStore.appliedTargetVersionCode(),
        hotStore.appliedPackageSha256()
    )

    /**
     * 启动热更流水线（关于页按钮触发；此后可离开页面，下载与落位继续）。
     * 已在跑则忽略；生效后自动重检（UI 翻转 UpToDate）。
     */
    fun startHotFlow(hot: HotAsset, manifest: UpdateManifest, mirror: DownloadMirror) {
        if (hotEngine.isRunning) return
        hotEngine.start(scope, hot, manifest, mirror) { state ->
            _hotState.value = state
            // 生效即重检：有效版本追平清单 → UpToDate
            if (state is HotUpdateEngine.State.Applied) {
                checkForUpdate(
                    BuildConfig.VERSION_CODE, force = true, fromTrigger = "hot-applied"
                )
            }
        }
    }

    /** 清热更终态（失败重试用 —— 不影响引擎执行）。 */
    fun resetHotState() {
        if (!hotEngine.isRunning) _hotState.value = null
    }

    // ═══ 签名预检（v1.4.7）════════════════════════════════════════════

    /**
     * 本地 APK 签名证书 SHA-256 指纹（hex 小写；取首个签名者；失败 null）。
     * API 28+ 走 SigningInfo，旧设备走废弃的 signatures 字段。
     */
    fun localSigningCertSha256(): String? = runCatching {
        val pm = appContext.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(appContext.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(appContext.packageName, PackageManager.GET_SIGNATURES)
        }
        val bytes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
        } else {
            @Suppress("DEPRECATION")
            info.signatures?.firstOrNull()?.toByteArray()
        } ?: return@runCatching null
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }.onFailure {
        AppLogger.instance.warn(
            LogCategory.SYSTEM, "UpdateCenter", "本地签名指纹读取失败：${it.message}"
        )
    }.getOrNull()

    /**
     * 签名预检：本地签名与清单 `signingCertSha256` 是否不一致。
     *
     * true = 增量/全量覆盖安装**必报签名冲突**（本地构建/分叉构建/不同签名源），
     * UI 应提前劝导热更或卸载重装；false = 一致或任一侧指纹不可得（按旧
     * 行为放行，安装器自身仍是一道防线）。
     */
    fun signatureMismatch(manifest: UpdateManifest): Boolean {
        val expected = manifest.signingCertSha256 ?: return false
        val local = localSigningCertSha256() ?: return false
        return !expected.equals(local, ignoreCase = true)
    }

    /** 清流水线终态（对话框关闭/重置 UI 用 —— 不影响引擎执行）。 */
    fun resetPatchState() {
        if (!patchEngine.isRunning) _patchState.value = null
    }

    /**
     * 就绪产物重入口：磁盘复核通过的合成包重新挂回状态流（关于页
     * 「稍后安装」→ 离开 → 回来 → 一键回弹安装确认框）。
     */
    fun restoreReadyState(apk: java.io.File, sizeBytes: Long) {
        if (patchEngine.isRunning) return
        _patchState.value = PatchUpdateEngine.State.Ready(apk, sizeBytes)
    }
}
