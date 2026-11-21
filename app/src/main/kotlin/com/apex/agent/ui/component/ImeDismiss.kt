package com.apex.agent.ui.component

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView

/**
 * ═══ 全局 IME 收起控制器（2026-10 用户反馈三连修）═══
 *
 * 用户实测的三处「键盘不收回」：
 * 1. 点输入框弹出输入法后，点消息区空白 —— 不收回；
 * 2. 点左上角汉堡打开抽屉 —— 不收回；
 * 3. 抽屉里切换页面 —— 也不收回。
 *
 * 成因：全 app 只有 SkillChipInputField 的 showSoftInput（显式弹出），没有任何
 * 主动收回路径 —— 只能靠系统返回键或 EditText 失焦的隐式行为；而
 * edge-to-edge + AndroidView EditText 的组合下焦点 View 移出组合并不必然
 * 收起 IME（用户实测就是不收）。
 *
 * 收回实现走「窗口 token + InputMethodManager.hideSoftInputFromWindow」：
 * 对窗口内任意聚焦 View（Compose TextField 或 interop EditText）一律生效，
 * 不依赖具体输入组件的引用。随后清掉窗口当前焦点 —— 光标/选择指示器随焦
 * 点消失，重新点输入框时 SkillChipInputField 的焦点监听会重新弹起 IME
 * （行为闭环，不会出现「键盘没了但光标还在闪」的中间态）。
 */
class ImeDismisser internal constructor(private val rootView: View) {

    /** 收起输入法并清除窗口焦点。幂等：键盘未弹/无焦点时为 no-op，可放心高频调用。 */
    fun dismiss() {
        runCatching {
            val token = rootView.windowToken
            if (token != null) {
                val imm = rootView.context
                    .getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.hideSoftInputFromWindow(token, 0)
            }
        }
        // 全树清焦点：Activity 窗口的 currentFocus 指向真实聚焦 View（interop
        // EditText 或 AndroidComposeView），clearFocus 覆盖两套焦点体系。
        findActivity(rootView.context)?.window?.currentFocus?.clearFocus()
    }
}

/** 从 View context 逐层解包出宿主 Activity（Compose 里可能是 ContextThemeWrapper）。 */
private fun findActivity(context: Context): Activity? {
    var ctx = context
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

/** 组合内取得 [ImeDismisser]（绑定当前窗口，View 不变则实例复用）。 */
@Composable
fun rememberImeDismisser(): ImeDismisser {
    val view = LocalView.current
    return remember(view) { ImeDismisser(view) }
}

/**
 * 点击空白收输入法：挂在消息列表/时间轴等内容容器上。
 *
 * 手势语义：[detectTapGestures] 在 Main 通道等子组件先消费 —— 按钮/链接/
 * 卡片等可点击子项消费掉的点击不会冒泡成本手势（见到已消费的 up 即取消），
 * 只有真正的空白与非交互区域触发收起；滚动手势（超出 touch slop 被列表
 * 消费）同样不会误触。
 */
fun Modifier.imeDismissOnTap(dismisser: ImeDismisser): Modifier =
    pointerInput(dismisser) {
        detectTapGestures(onTap = { dismisser.dismiss() })
    }
