package com.apex.agent.browser

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * 状态光环（代码绘制，替代 Lottie 二进制资源；去霓虹柔化重调）。
 *
 * 多层旋转渐变弧 + 呼吸透明度，营造克制的"环流"活动观感；强度已从
 * 旧等离子霓虹（高alpha/快转速）柔化为低调慢速旋转。颜色随
 * [NeonRingView.setStateColor] 切换（RUNNING 静谧蓝 / NEED_HUMAN 柔和琥珀 /
 * ERROR 暖红 / SUCCESS 柔和绿）。
 * 不用 Lottie 是为了避免引入不可控的二进制 JSON 资源，同时零额外包体。
 */
class NeonRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val rings = listOf(
        // 去霓虹柔化：alpha 0.9/0.7/0.5 → 0.55/0.38/0.26，宽度同步收细
        RingSpec(width = 3f, radiusFactor = 0.92f, speed = 0.7f, offset = 0f, alpha = 0.55f),
        RingSpec(width = 2f, radiusFactor = 0.80f, speed = -0.5f, offset = 120f, alpha = 0.38f),
        RingSpec(width = 1.5f, radiusFactor = 0.68f, speed = 0.35f, offset = 240f, alpha = 0.26f),
    )

    private var baseColor = 0xFF4E96C7.toInt()
    private var sweep = 90f
    private var breath = 0f
    private var angle = 0f
    private val mainHandler = Handler(Looper.getMainLooper())
    private var running = false

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    data class RingSpec(
        val width: Float,
        val radiusFactor: Float,
        val speed: Float,
        val offset: Float,
        val alpha: Float,
    )

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        start()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    fun start() {
        if (running) return
        running = true
        loop()
    }

    fun stop() {
        running = false
        mainHandler.removeCallbacks(animRunnable)
    }

    fun setStateColor(color: Int) {
        baseColor = color
    }

    private val animRunnable = Runnable { loop() }

    private fun loop() {
        if (!running) return
        // 去霓虹柔化：转速 3.2°/帧 → 1.6°/帧（慢速克制），呼吸幅度同步收窄
        angle = (angle + 1.6f) % 360f
        breath = (Math.sin(System.currentTimeMillis() / 560.0) * 0.10f + 0.90f).toFloat()
        invalidate()
        mainHandler.postDelayed(animRunnable, 16)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val baseR = min(w, h) / 2f * 0.96f

        for (ring in rings) {
            val r = baseR * ring.radiusFactor
            val rect = RectF(cx - r, cy - r, cx + r, cy + r)
            val start = angle * ring.speed + ring.offset
            val grad = SweepGradient(
                cx, cy,
                intArrayOf(
                    adjustAlpha(baseColor, 0f),
                    adjustAlpha(baseColor, ring.alpha),
                    adjustAlpha(baseColor, 0f),
                    adjustAlpha(baseColor, ring.alpha * 0.6f),
                ),
                floatArrayOf(0f, 0.30f, 0.55f, 1f),
            )
            paint.shader = grad
            paint.strokeWidth = ring.width * breath
            paint.alpha = (255 * breath).toInt().coerceIn(40, 255)
            canvas.drawArc(rect, start, sweep, false, paint)
        }
        paint.shader = null
    }

    private fun adjustAlpha(color: Int, factor: Float): Int {
        val a = (android.graphics.Color.alpha(color) * factor).toInt().coerceIn(0, 255)
        return android.graphics.Color.argb(
            a,
            android.graphics.Color.red(color),
            android.graphics.Color.green(color),
            android.graphics.Color.blue(color),
        )
    }
}
