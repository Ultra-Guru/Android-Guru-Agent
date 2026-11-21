package com.kyant.backdrop

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

/**
 * 形状提供器（本仓适配：v1 补值语义 equals/hashCode）。
 *
 * diag-20261011 修复（启动期 draw 失效风暴 / detached-remeasure 崩溃诱因）：
 * 旧实现用默认身份相等 —— `Modifier.drawBackdrop(shape = { shape })` 在每次
 * 重组都新建 ShapeProvider 实例，ModifierNodeElement.equals 恒 false →
 * 每次重组都走 update() → invalidateDrawCache() → observeReads 重订阅。
 * 抽屉 16 项 + 聊天气泡等启动期大量重组时形成 draw/layout 失效 churn，叠加
 * 换页 dispose 竞争放大 detached-remeasure 崩溃窗口。值语义：解析
 * shapeBlock 的当前 Shape 比较相等（RoundedCornerShape / CircleShape 等
 * 均有正确 equals），等价 provider 不再触发 update。
 */
@Immutable
internal class ShapeProvider(val shapeBlock: () -> Shape) {

    private var _shape: Shape? = null
    private var _outline: Outline? = null
    private var _size: Size = Size.Unspecified
    private var _layoutDirection: LayoutDirection? = null
    private var _density: Float? = null

    val innerShape
        get() = shapeBlock()

    val shape = object : Shape {

        override fun createOutline(
            size: Size,
            layoutDirection: LayoutDirection,
            density: Density
        ): Outline {
            val shape = shapeBlock()
            if (_shape != shape) {
                _shape = shape
                _outline = null
            }
            if (_outline == null || _size != size || _layoutDirection != layoutDirection || _density != density.density) {
                _size = size
                _layoutDirection = layoutDirection
                _density = density.density
                _outline = shape.createOutline(size, layoutDirection, density)
            }

            return _outline!!
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ShapeProvider) return false
        return innerShape == other.innerShape
    }

    override fun hashCode(): Int = innerShape.hashCode()
}
