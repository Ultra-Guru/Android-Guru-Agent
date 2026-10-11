# vendored: backdrop (kyant0/AndroidLiquidGlass)

上游：https://github.com/kyant0/AndroidLiquidGlass —— "A customizable Liquid Glass
effect library for Compose Multiplatform"，作者 Kyant，Apache License 2.0
（完整许可证见本目录 [LICENSE](./LICENSE)）。

## 为什么 vendored 而不是 Maven 依赖

**历史原因（已解除）**：Maven Central 坐标 `io.github.kyant0:backdrop` 的全部已发布
版本（1.0.0 起）均以 Kotlin 2.2.21+ / Compose 1.9.4+ 编译发布；彼时本仓工具链为
Kotlin 2.0.21 / Compose 1.7.6（BOM 2024.12.01），无法消费。**PR #362 已把工具链
升到 Kotlin 2.3.21 + BOM 2026.02.00（Compose 1.10.3）**，与上游 1.0.6 的构建矩阵
（kotlin-stdlib 2.3.10 + Compose 1.10.3）精确配套，并一度完成去 vendored 化
（PR #362 的 f86fc28e / 39a93c3e，直接消费 Maven 1.0.6）。

**现行原因（去 vendored 化显式回退）**：main 侧 b4648f22（diag-20261011）给本
vendored 副本打了两项 **internal 级热修**，上游 1.0.6 的二进制**不含**它们
（已对 Maven sources jar 逐一核实）。二进制依赖无法打 internal 补丁，直接换
Maven 会回退这两个修复：

| # | 文件 | 热修内容 | 修复的缺陷 |
|---|------|---------|-----------|
| 1 | `DrawBackdropModifier.kt` | `onObservedReadsChanged()` / `invalidateDrawCache()` 增加 `if (!isAttached) return` 守卫 | detached-remeasure 崩溃：脱离后到达的观察回调重订阅 observeReads / 失效 draw 缓存，访问已脱离的 LayoutNode |
| 2 | `ShapeProvider.kt` | 值语义 `equals`/`hashCode`（按 `innerShape` 比较） | 启动期 draw 失效风暴：旧实现默认身份相等 → `Modifier.drawBackdrop(shape = { shape })` 每次重组新建实例 → ModifierNodeElement.equals 恒 false → 每次重组都 update() → invalidateDrawCache → observeReads 重订阅，抽屉 16 项 + 聊天气泡启动期重组形成 draw/layout churn |

## 版本

**tag `1.0.0` 血统**（首个正式发布版，纯 Android library 形态：`src/main/java`，
无 KMP source set、无 io.github.kyant0:shapes 依赖 —— 两项都是上游后续版本
才引入的），叠加以下本仓增量：

- **PR #364**：从上游 android 分支同步**彩色 DefaultHighlight**（上游 1.0.3 起
  才进入 Maven 线，vendored 副本提前获得）；
- **b4648f22（diag-20261011）**：上表两项热修。

## 本仓适配清单（全部不改变行为）

| # | 文件 | 上游写法 | 适配写法 | 原因 |
|---|------|---------|---------|------|
| 1 | `LayerRecorder.kt` + 两个调用点 | `context(node: DelegatableNode)` 扩展 | 显式 `node: DelegatableNode` 参数 | Kotlin 2.0.21 K2 不支持 context receivers |
| 2 | `Shaders.kt` / `RuntimeShaderCache.kt` | `@Language("AGSL")`（org.jetbrains:annotations） | 移除注解 | 纯 IDE 提示注解，免额外依赖 |

上游 API 与 Compose 的兼容性：历史上以 1.7.6 逐项字节码核实
（`requireGraphicsContext` / `observeReads` / `invalidateDraw` / `requireDensity` /
`BlurEffect(RenderEffect,FFI)` / `asComposeRenderEffect` / `asAndroidRenderEffect` /
`asAndroidColorFilter` / `toAndroidTileMode` / `GraphicsLayer.record` /
`rememberGraphicsLayer` / `drawLayer`）；PR #362 合并后改由 **GitHub Actions
全量编译验证**（BOM 2026.02.00 = Compose 1.10.3，向后二进制兼容承诺覆盖）。

## 运行时能力分层（诚实声明）

- API 33+（TIRAMISU）：AGSL `RuntimeShader` —— **lens 折射/色散**（真液态玻璃）+ 高光着色器
- API 31+（S）：`RenderEffect` —— GPU blur 链 / ColorFilter（vibrancy 等）
- API < 31：无 RenderEffect —— `blur()`/`lens()` 静默跳过（库内建门禁）；
  宿主 `GlassSurface` 在此档位直接走 Frosted 降级，不绘制未模糊的 backdrop

## 升级路径

本模块**禁止业务代码直接 import**：唯一消费方是
`app/src/main/kotlin/com/apex/agent/ui/glass/`（GlassSurface / TerminalGlass
单点集成，换库只改该包）。工具链前置条件（Kotlin ≥ 2.1 + Compose ≥ 1.9）已由
PR #362 满足；剩余唯一阻塞是**上游吸收两项 diag-20261011 热修**（向上游提 PR
后跟踪其 release）。上游吸收后：整体删除本模块、`app` 依赖改回
`io.github.kyant0:backdrop`（版本取 ≥ 吸收热修的最低版），glass 包 API 形状
保持不变（包名同为 `com.kyant.backdrop`，公共 API 源兼容已核实）。
