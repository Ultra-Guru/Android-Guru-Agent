package com.apex.agent.tools

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * #343：宿主媒体结果 broker —— 让无 UI 上下文里的工具拿到**真实的
 * Activity Result**，而不是 fire-and-forget 的「启动即成功」。
 *
 * 旧实现 `context.startActivity(...)` 后直接返回成功 / 占位结果：
 * - FilePicker 返回空 URIs +「SAF picker launched」假显示名
 * - CameraCapturer 把「启动了相机」当「拍到了照片」（success=true）
 *
 * 现在经 [MediaResultBrokerActivity] 蹦床走真正的
 * startActivityForResult → onActivityResult 分发：
 * - 工具侧 [awaitResult] 挂起等待真实结果（带超时防御）；
 * - RESULT_OK / RESULT_CANCELED 语义如实上抛，由各工具判定成败；
 * - 蹦床 Activity 透明无历史、独立任务栈，用完即毁，用户无感。
 *
 * 结果通道按 token 隔离（ConcurrentHashMap），并发拍照 / 选文件互不串线。
 */
object MediaResultBroker {

    /** 宿主真实回传的结果（resultCode + data 原样）。 */
    data class HostResult(val resultCode: Int, val data: Intent?)

    private const val TAG = "MediaResultBroker"

    internal const val EXTRA_TOKEN = "broker.token"
    internal const val EXTRA_TARGET = "broker.target"

    /** 蹦床 Activity 的 startActivityForResult 请求码（仅一个用途，常量即可）。 */
    internal const val REQUEST_CODE = 0x6d72 // "mr"

    /** 默认等待上限：真实用户操作（SAF 选文件 / 相机拍照）可能耗时较长。 */
    internal const val DEFAULT_TIMEOUT_MS = 120_000L

    private val pending = ConcurrentHashMap<Long, CompletableDeferred<HostResult>>()
    private val tokens = AtomicLong(0)

    /**
     * 启动蹦床并等待真实宿主结果。
     *
     * @return 宿主回传的 [HostResult]；蹦床启动失败（无 Activity 上下文可用、
     *   系统限制等）或等待超时返回 null——调用方必须把它折叠为**失败**，
     *   不得当作「成功无数据」。
     */
    suspend fun awaitResult(
        context: Context,
        target: Intent,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): HostResult? {
        val token = tokens.incrementAndGet()
        val deferred = CompletableDeferred<HostResult>()
        pending[token] = deferred
        return try {
            val brokerIntent = Intent(context, MediaResultBrokerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(EXTRA_TOKEN, token)
                putExtra(EXTRA_TARGET, target)
            }
            val launched = runCatching { context.startActivity(brokerIntent) }
                .onFailure { e ->
                    AppLogger.instance.warn(
                        LogCategory.PLUGIN, TAG,
                        "broker activity 启动失败: ${e.message}"
                    )
                }
                .isSuccess
            if (!launched) return null
            withTimeoutOrNull(timeoutMs) { deferred.await() }
        } finally {
            pending.remove(token)
        }
    }

    /** 蹦床 Activity 回传结果的唯一入口（主线程调用）。 */
    internal fun deliver(token: Long, resultCode: Int, data: Intent?) {
        pending[token]?.complete(HostResult(resultCode, data))
    }
}

/**
 * 透明蹦床 Activity：承载真正的 startActivityForResult / onActivityResult 分发。
 *
 * - 无 UI（ translucent ）、noHistory、独立 taskAffinity——不进最近任务、
 *   不污染宿主导航栈，结果回传后立即 finish；
 * - 重建（配置变更 / 进程回收）不再重发外部 Intent——按取消收尾，
 *   避免重复拉起相机 / 选择器；
 * - onDestroy 兜底：Activity 被系统杀死等未回传路径按 RESULT_CANCELED
 *   收尾，等待方不会悬空到超时。
 */
class MediaResultBrokerActivity : Activity() {

    private var token: Long = -1L
    private var delivered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        token = intent?.getLongExtra(MediaResultBroker.EXTRA_TOKEN, -1L) ?: -1L
        val target: Intent? = intent?.getParcelableExtra(MediaResultBroker.EXTRA_TARGET)
        if (savedInstanceState != null || target == null || token == -1L) {
            // 重建实例 / 无效载荷：按取消收尾，绝不重发外部 Intent
            finish()
            return
        }
        // 走真正的宿主结果分发（Activity Result 契约的核心）
        @Suppress("DEPRECATION")
        startActivityForResult(target, MediaResultBroker.REQUEST_CODE)
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != MediaResultBroker.REQUEST_CODE) return
        deliverOnce(resultCode, data)
        finish()
    }

    override fun onDestroy() {
        deliverOnce(RESULT_CANCELED, null)
        super.onDestroy()
    }

    private fun deliverOnce(resultCode: Int, data: Intent?) {
        if (delivered) return
        delivered = true
        MediaResultBroker.deliver(token, resultCode, data)
    }
}
