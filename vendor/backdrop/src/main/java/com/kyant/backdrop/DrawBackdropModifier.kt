package com.kyant.backdrop

import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ObserverModifierNode
import androidx.compose.ui.node.observeReads
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.toIntSize
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightElement
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.InnerShadowElement
import com.kyant.backdrop.shadow.Shadow
import com.kyant.backdrop.shadow.ShadowElement

private val DefaultHighlight = { Highlight.Default }
private val DefaultShadow = { Shadow.Default }
private val DefaultOnDrawBackdrop: DrawScope.(DrawScope.() -> Unit) -> Unit = { it() }

fun Modifier.drawPlainBackdrop(
    backdrop: Backdrop,
    shape: () -> Shape,
    effects: BackdropEffectScope.() -> Unit,
    layerBlock: (GraphicsLayerScope.() -> Unit)? = null,
    exportedBackdrop: LayerBackdrop? = null,
    onDrawBehind: (DrawScope.() -> Unit)? = null,
    onDrawBackdrop: DrawScope.(drawBackdrop: DrawScope.() -> Unit) -> Unit = DefaultOnDrawBackdrop,
    onDrawSurface: (DrawScope.() -> Unit)? = null,
    onDrawFront: (DrawScope.() -> Unit)? = null
): Modifier {
    val shapeProvider = ShapeProvider(shape)
    return this
        .then(
            if (layerBlock != null) {
                Modifier.graphicsLayer(layerBlock)
            } else {
                Modifier
            }
        )
        .then(
            DrawBackdropElement(
                backdrop = backdrop,
                shapeProvider = shapeProvider,
                effects = effects,
                layerBlock = layerBlock,
                exportedBackdrop = exportedBackdrop,
                onDrawBehind = onDrawBehind,
                onDrawBackdrop = onDrawBackdrop,
                onDrawSurface = onDrawSurface,
                onDrawFront = onDrawFront
            )
        )
}

fun Modifier.drawBackdrop(
    backdrop: Backdrop,
    shape: () -> Shape,
    effects: BackdropEffectScope.() -> Unit,
    highlight: (() -> Highlight?)? = DefaultHighlight,
    shadow: (() -> Shadow?)? = DefaultShadow,
    innerShadow: (() -> InnerShadow?)? = null,
    layerBlock: (GraphicsLayerScope.() -> Unit)? = null,
    exportedBackdrop: LayerBackdrop? = null,
    onDrawBehind: (DrawScope.() -> Unit)? = null,
    onDrawBackdrop: DrawScope.(drawBackdrop: DrawScope.() -> Unit) -> Unit = DefaultOnDrawBackdrop,
    onDrawSurface: (DrawScope.() -> Unit)? = null,
    onDrawFront: (DrawScope.() -> Unit)? = null
): Modifier {
    val shapeProvider = ShapeProvider(shape)
    return this
        .then(
            if (layerBlock != null) {
                Modifier.graphicsLayer(layerBlock)
            } else {
                Modifier
            }
        )
        .then(
            if (innerShadow != null) {
                InnerShadowElement(
                    shapeProvider = shapeProvider,
                    shadow = innerShadow
                )
            } else {
                Modifier
            }
        )
        .then(
            if (shadow != null) {
                ShadowElement(
                    shapeProvider = shapeProvider,
                    shadow = shadow
                )
            } else {
                Modifier
            }
        )
        .then(
            if (highlight != null) {
                HighlightElement(
                    shapeProvider = shapeProvider,
                    highlight = highlight
                )
            } else {
                Modifier
            }
        )
        .then(
            DrawBackdropElement(
                backdrop = backdrop,
                shapeProvider = shapeProvider,
                effects = effects,
                layerBlock = layerBlock,
                exportedBackdrop = exportedBackdrop,
                onDrawBehind = onDrawBehind,
                onDrawBackdrop = onDrawBackdrop,
                onDrawSurface = onDrawSurface,
                onDrawFront = onDrawFront
            )
        )
}

private class DrawBackdropElement(
    val backdrop: Backdrop,
    val shapeProvider: ShapeProvider,
    val effects: BackdropEffectScope.() -> Unit,
    val layerBlock: (GraphicsLayerScope.() -> Unit)?,
    val exportedBackdrop: LayerBackdrop?,
    val onDrawBehind: (DrawScope.() -> Unit)?,
    val onDrawBackdrop: DrawScope.(drawBackdrop: DrawScope.() -> Unit) -> Unit,
    val onDrawSurface: (DrawScope.() -> Unit)?,
    val onDrawFront: (DrawScope.() -> Unit)?
) : ModifierNodeElement<DrawBackdropNode>() {

    override fun create(): DrawBackdropNode {
        return DrawBackdropNode(
            backdrop = backdrop,
            shapeProvider = shapeProvider,
            effects = effects,
            layerBlock = layerBlock,
            exportedBackdrop = exportedBackdrop,
            onDrawBehind = onDrawBehind,
            onDrawBackdrop = onDrawBackdrop,
            onDrawSurface = onDrawSurface,
            onDrawFront = onDrawFront
        )
    }

    override fun update(node: DrawBackdropNode) {
        node.backdrop = backdrop
        node.shapeProvider = shapeProvider
        node.effects = effects
        node.layerBlock = layerBlock
        node.exportedBackdrop = exportedBackdrop
        node.onDrawBehind = onDrawBehind
        node.onDrawBackdrop = onDrawBackdrop
        node.onDrawSurface = onDrawSurface
        node.onDrawFront = onDrawFront
        node.invalidateDrawCache()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "drawBackdrop"
        properties["backdrop"] = backdrop
        properties["shapeProvider"] = shapeProvider
        properties["effects"] = effects
        properties["layerBlock"] = layerBlock
        properties["exportedBackdrop"] = exportedBackdrop
        properties["onDrawBehind"] = onDrawBehind
        properties["onDrawBackdrop"] = onDrawBackdrop
        properties["onDrawSurface"] = onDrawSurface
        properties["onDrawFront"] = onDrawFront
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DrawBackdropElement) return false

        if (backdrop != other.backdrop) return false
        if (shapeProvider != other.shapeProvider) return false
        if (effects != other.effects) return false
        if (layerBlock != other.layerBlock) return false
        if (exportedBackdrop != other.exportedBackdrop) return false
        if (onDrawBehind != other.onDrawBehind) return false
        if (onDrawBackdrop != other.onDrawBackdrop) return false
        if (onDrawSurface != other.onDrawSurface) return false
        if (onDrawFront != other.onDrawFront) return false

        return true
    }

    override fun hashCode(): Int {
        var result = backdrop.hashCode()
        result = 31 * result + shapeProvider.hashCode()
        result = 31 * result + effects.hashCode()
        result = 31 * result + (layerBlock?.hashCode() ?: 0)
        result = 31 * result + (exportedBackdrop?.hashCode() ?: 0)
        result = 31 * result + (onDrawBehind?.hashCode() ?: 0)
        result = 31 * result + onDrawBackdrop.hashCode()
        result = 31 * result + (onDrawSurface?.hashCode() ?: 0)
        result = 31 * result + (onDrawFront?.hashCode() ?: 0)
        return result
    }
}

private class DrawBackdropNode(
    var backdrop: Backdrop,
    var shapeProvider: ShapeProvider,
    var effects: BackdropEffectScope.() -> Unit,
    var layerBlock: (GraphicsLayerScope.() -> Unit)?,
    var exportedBackdrop: LayerBackdrop?,
    var onDrawBehind: (DrawScope.() -> Unit)?,
    var onDrawBackdrop: DrawScope.(drawBackdrop: DrawScope.() -> Unit) -> Unit,
    var onDrawSurface: (DrawScope.() -> Unit)?,
    var onDrawFront: (DrawScope.() -> Unit)?
) : LayoutModifierNode, DrawModifierNode, GlobalPositionAwareModifierNode, ObserverModifierNode, Modifier.Node() {

    override val shouldAutoInvalidate: Boolean = false

    private val effectScope =
        object : BackdropEffectScopeImpl() {

            override val shape: Shape get() = shapeProvider.innerShape
        }

    private var graphicsLayer: GraphicsLayer? = null

    private val layoutLayerBlock: GraphicsLayerScope.() -> Unit = {
        clip = true
        shape = shapeProvider.shape
        compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen
    }

    private var layoutCoordinates: LayoutCoordinates? by mutableStateOf(null, neverEqualPolicy())

    private var padding by mutableFloatStateOf(0f)

    // ═══ perf-20261114：层重录制输入快照 ═══
    // recordLayer 的产物只由以下输入决定；输入未变时跳过重录制（draw()
    // 会被祖先/内容失效连带重入，旧实现每次都整层重录 —— 流式期间每帧
    // 全部玻璃卡的 display list 重录正是「Record View#draw」尖峰与
    // RenderThread「flush layers」排队的主要来源）。源层（LayerBackdrop）
    // 的内容更新不需重录：录制产物持有对源 GraphicsLayer 的活引用，重放
    // 时自动取源层当前内容；只有位置需要重录。
    //
    // 位置差分用 positionInWindow() **值比较**而非 LayoutCoordinates 实例
    // 比较 —— Compose 的 coordinator 实例在纯位移（滚动/抽屉滑动/IME 平移）
    // 时复用不变，实例恒等会漏检 → 采样错位。取值 walks coordinator 链，
    // 代价远低于整层重录。
    //
    // 已知取舍：layerBlock 内部读取的动画状态（按压缩放的反演变换）只在
    // 重录时取样 —— 按压动画期间采样反演滞后约一帧级别（视觉缩放本身经
    // graphicsLayer 层属性通道仍实时）；本仓列表卡 scaleOnPress=false，
    // 仅小按钮受影响，亚像素级可忽略。
    private var recordedPosition: Offset? = null
    private var recordedSourcePosition: Offset? = null
    private var recordedPadding: Float = -1f
    private var recordedBackdrop: Backdrop? = null
    private var recordedOnDrawBackdrop: (DrawScope.(DrawScope.() -> Unit) -> Unit)? = null
    private var recordedLayerBlock: (GraphicsLayerScope.() -> Unit)? = null
    private var recordedSize: IntSize = IntSize(-1, -1)
    private var recordedDirection: LayoutDirection? = null

    // ═══ exportedBackdrop 包装层的独立快照（主层快照的尺寸含 padding，
    // 混用会乒乓互触发重录 —— 拆开各自记账）═══
    private var exportedRecordedSize: IntSize = IntSize(-1, -1)
    private var exportedRecordedDirection: LayoutDirection? = null
    private var exportedRecordedOnDrawBehind: (DrawScope.() -> Unit)? = null
    private var exportedRecordedOnDrawSurface: (DrawScope.() -> Unit)? = null
    private var exportedRecordedOnDrawFront: (DrawScope.() -> Unit)? = null

    /**
     * 是否必须重录：未知形态的坐标依赖源（CombinedBackdrop 等组合形态）
     * 读不到其内部位置状态，保守恒重录（保持旧语义）；LayerBackdrop 与
     * 坐标无关源（Canvas/Empty）可安全差分。
     */
    private fun isAlwaysDirty(): Boolean =
        backdrop.isCoordinatesDependent && backdrop !is LayerBackdrop

    private fun sourceCoordinates(): LayoutCoordinates? =
        (backdrop as? LayerBackdrop)?.layerCoordinates

    // 窗口坐标取值（null = 无坐标 —— 坐标无关源恒 null，差分退化为恒等）
    private fun currentPosition(): Offset? = layoutCoordinates?.positionInWindow()

    private fun sourcePosition(): Offset? = sourceCoordinates()?.positionInWindow()

    private fun inputsChanged(
        layerSize: IntSize,
        direction: LayoutDirection
    ): Boolean {
        // 逐项引用/值比较 —— 任一输入变化才允许 recordLayer
        return isAlwaysDirty() ||
            layerSize != recordedSize ||
            direction != recordedDirection ||
            padding != recordedPadding ||
            currentPosition() != recordedPosition ||
            sourcePosition() != recordedSourcePosition ||
            backdrop !== recordedBackdrop ||
            onDrawBackdrop !== recordedOnDrawBackdrop ||
            layerBlock !== recordedLayerBlock
    }

    private fun snapshotInputs(
        layerSize: IntSize,
        direction: LayoutDirection
    ) {
        recordedSize = layerSize
        recordedDirection = direction
        recordedPadding = padding
        recordedPosition = currentPosition()
        recordedSourcePosition = sourcePosition()
        recordedBackdrop = backdrop
        recordedOnDrawBackdrop = onDrawBackdrop
        recordedLayerBlock = layerBlock
    }

    /** exportedBackdrop 包装层的输入差分（尺寸不含 padding，与主层分开记账）。 */
    private fun exportedInputsChanged(
        layerSize: IntSize,
        direction: LayoutDirection
    ): Boolean =
        layerSize != exportedRecordedSize ||
            direction != exportedRecordedDirection ||
            onDrawBehind !== exportedRecordedOnDrawBehind ||
            onDrawSurface !== exportedRecordedOnDrawSurface ||
            onDrawFront !== exportedRecordedOnDrawFront

    private fun snapshotExportedInputs(
        layerSize: IntSize,
        direction: LayoutDirection
    ) {
        exportedRecordedSize = layerSize
        exportedRecordedDirection = direction
        exportedRecordedOnDrawBehind = onDrawBehind
        exportedRecordedOnDrawSurface = onDrawSurface
        exportedRecordedOnDrawFront = onDrawFront
    }

    private val recordBackdropBlock: (DrawScope.() -> Unit) = {
        val canvas = drawContext.canvas
        val padding = padding

        if (padding != 0f) {
            canvas.translate(padding, padding)
        }
        onDrawBackdrop {
            with(backdrop) {
                drawBackdrop(
                    density = effectScope,
                    coordinates = layoutCoordinates,
                    layerBlock = layerBlock
                )
            }
        }
        if (padding != 0f) {
            canvas.translate(-padding, -padding)
        }
    }

    /**
     * perf-20261114：返回是否重录了层内容 —— exportedBackdrop 的外层包装
     * 录制以此为脏标记（内部输入未变时包装层同样免重录）。
     */
    private val drawBackdropLayer: DrawScope.() -> Boolean = {
        var recorded = false
        val layer = graphicsLayer
        if (layer != null) {
            val padding = padding
            val layerSize = IntSize(
                size.width.toInt() + padding.toInt() * 2,
                size.height.toInt() + padding.toInt() * 2
            )

            // perf-20261114：输入快照差分 —— 内容失效连带重入但采样输入
            // 未变时复用已录制层（源层内容更新经活引用自动可见）。
            if (inputsChanged(layerSize, layoutDirection)) {
                recordLayer(
                    this@DrawBackdropNode,
                    layer,
                    size = layerSize,
                    block = recordBackdropBlock
                )
                snapshotInputs(layerSize, layoutDirection)
                recorded = true
            }

            layer.topLeft =
                if (padding != 0f) IntOffset(-padding.toInt(), -padding.toInt())
                else IntOffset.Zero
            drawLayer(layer)
        }
        recorded
    }

    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints
    ): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) {
            placeable.placeWithLayer(IntOffset.Zero, layerBlock = layoutLayerBlock)
        }
    }

    override fun ContentDrawScope.draw() {
        if (effectScope.update(this)) {
            updateEffects()
        }

        onDrawBehind?.invoke(this)
        val backdropRecorded = drawBackdropLayer()
        onDrawSurface?.invoke(this)
        drawContent()
        onDrawFront?.invoke(this)

        exportedBackdrop?.graphicsLayer?.let { layer ->
            // perf-20261114：包装层独立快照 —— 主层引用是活的（内容更新
            // 自动可见），只在包装自身输入（尺寸/方向/绘制 lambda 实例）
            // 变化或主层重录时保守重录。
            val exportedSize = size.toIntSize()
            if (backdropRecorded || exportedInputsChanged(exportedSize, layoutDirection)) {
                recordLayer(this@DrawBackdropNode, layer) {
                    onDrawBehind?.invoke(this)
                    drawBackdropLayer()
                    onDrawSurface?.invoke(this)
                    onDrawFront?.invoke(this)
                }
                snapshotExportedInputs(exportedSize, layoutDirection)
            }
        }
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        if (coordinates.isAttached) {
            if (backdrop.isCoordinatesDependent) {
                layoutCoordinates = coordinates
            } else {
                if (layoutCoordinates != null) {
                    layoutCoordinates = null
                }
            }
            exportedBackdrop?.layerCoordinates = coordinates
        }
    }

    override fun onObservedReadsChanged() {
        // diag-20261011：脱离后到达的观察回调直接忽略 —— 节点已不持有
        // owner，重订阅 observeReads / 失效 draw 缓存都可能访问已脱离的
        // LayoutNode（detached-remeasure 崩溃的同类时序缝隙）。
        if (!isAttached) return
        invalidateDrawCache()
    }

    fun invalidateDrawCache() {
        if (!isAttached) return
        observeEffects()
    }

    private fun observeEffects() {
        observeReads { updateEffects() }
    }

    private fun updateEffects() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            effectScope.apply(effects)
            graphicsLayer?.renderEffect = effectScope.renderEffect?.asComposeRenderEffect()
            padding = effectScope.padding
        }
    }

    override fun onAttach() {
        val graphicsContext = requireGraphicsContext()
        graphicsLayer = graphicsContext.createGraphicsLayer()

        observeEffects()
    }

    override fun onDetach() {
        val graphicsContext = requireGraphicsContext()
        graphicsLayer?.let { layer ->
            graphicsContext.releaseGraphicsLayer(layer)
            graphicsLayer = null
        }

        effectScope.reset()
        layoutCoordinates = null
        exportedBackdrop?.layerCoordinates = null
        // perf-20261114：复位输入快照 —— 复挂后首轮 draw 强制重录
        recordedSize = IntSize(-1, -1)
        recordedDirection = null
        recordedPadding = -1f
        recordedPosition = null
        recordedSourcePosition = null
        recordedBackdrop = null
        recordedOnDrawBackdrop = null
        recordedLayerBlock = null
        exportedRecordedSize = IntSize(-1, -1)
        exportedRecordedDirection = null
        exportedRecordedOnDrawBehind = null
        exportedRecordedOnDrawSurface = null
        exportedRecordedOnDrawFront = null
    }
}
