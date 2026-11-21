# T90 · 终端渲染层精修 —— 对称 / 字体形变 / 缩放链 / 输出格式

> **[已退役 · T96 终端大重构 / PR #474]** 本文档描述的旧交互终端已删除，现存唯一终端为 M3 控制台（真实 PTY 执行）；文中涉及的配色方案 / 扩展键 / 键栏 / VT 网格渲染等子系统随之移除，本文仅作历史设计存档。

> 用户反馈四症状的根治记录：**页面不对称、字体扁、缩放一坨、命令输出格式乱**。
> 方法学：对照 Termux（termux-app）`TerminalRenderer` / `TerminalView` 的成熟
> 实现，逐症状定位本项目渲染链的确定性根因，全部为**代码级 bug**（非设备玄学），
> 每项修复均有 JVM 单测或可复现的数学断言锁定。

## 症状 → 根因 → 修复对照表

| # | 症状 | 根因（文件:旧行为） | 修复 |
|---|------|---------------------|------|
| 1 | 字体扁（粗体/中文被水平压扁） | `TerminalCanvasRenderer.drawRowText`：run 级 bold/italic **切换 typeface 变体**（BOLD 变体的 '0' advance 比探测用的 NORMAL 更宽 → scaleX<1 强制压扁）；CJK 依赖 `'0'` 单一探测零余量 | **Termux 同款 fake bold/skewX**（`setFakeBoldText`/`setTextSkewX(-0.35f)`，不换 typeface → 度量与绘制同源）；**双探测 cell 宽** = max(64×'0' 均值, CJK 探测串/2) —— CJK/全角字形 scaleX≥1 永不压扁 |
| 2 | 输出格式乱（列错位/越走越歪） | `textScaleX` 超出 0.5..2.2 时**静默回退 1f**（run 无限溢出，后续 run 起点错位）；UNDERLINE 光标 2 cell 宽侵入右邻列；**后台会话永远 24×80**（切换不重握手 → 80 列行在窄屏右裁 + 短网格浮在长视口） | 超界**钳制到边界**（最坏 2.2×/0.5× 有界，列不失控）；UNDERLINE 1 cell；**会话切换重握手**（Host `LaunchedEffect(activeSessionId)` → `View.currentGridSize()` → `resizeTerminal`，幂等） |
| 3 | 页面不对称（左贴边/右侧空一列/滚动条压字） | `TerminalTextGrid.compute`：cols=floor 截断 → 文本 x=0 起步、余量全堆右沿；滚动条 3dp 半透明叠在最右列文字上；KeyToolbar 13 键不滚动 → 窄屏右侧键裁出屏外 | **余量分摊两侧**（`originX/originY` 居中，像素换算双向自洽）；滚动条 2dp + 透明度 0.08/0.30（居中后与文字天然分离）；KeyToolbar 主行 `horizontalScroll` |
| 4 | 缩放一坨（捏合乱跳/意外步进） | `pinchScaleAccum` **手势结束不重置**（残留 1.05..1.24 累积带到下次小捏合凭空 ±1sp）；字号步进后 150ms 防抖窗内双重重排观感 | 手势结束（UP/CANCEL/POINTER_UP）**一律复位**累子；会话切换即时重握手（不再依赖防抖通道） |

## 修复明细

### 1. 渲染核心（`terminal-view` 模块）

**`TerminalCanvasRenderer`（重写绘制度量策略）**
- 删除 `boldTypeface/italicTypeface/boldItalicTypeface` 三变体 —— run 级
  `RenderCell.FLAG_BOLD/FLAG_ITALIC` 改为 `isFakeBoldText` + `textSkewX`
  （Termux `TerminalRenderer.drawTextRun` 同款）；探测与绘制共用同一
  paint 状态 → 任何 style 组合下**列宽逐像素稳定**；
- `onFontChanged` 双探测：`PROBE_CHARS`（64×'0'）+ `WIDE_PROBE_CHARS`
  （"中文日本語한글"，走真实 fallback 字体路径），cell 宽取
  `max(narrow, wide/2)`；
- `drawRowText`：`textScaleX.coerceIn(0.5f, 2.2f)`（旧版越界静默 1f）；
- UNDERLINE 光标宽 `cellWidthPx`（旧 `×2`）；
- 滚动条：默认 2dp / 轨道 α0.08 / 滑块 α0.30（旧 3dp / 0.18 / 0.55）；
- **移除画布内「新输出 ↓」药丸**（`drawNewOutputIndicator` 删；与宿主
  `JumpToLatestPill` 双层叠遮最后几行输出 —— `settings` 字段保留兼容但
  默认 false 且不再消费）。

**`TerminalTextGrid`（居中几何）**
- 新增 `originX/originY`：`compute()` 把 floor 余量分摊两侧；
- 像素换算双向约定：内容→视口（`columnX/rowTopY/cursorPixelX`）输出已加
  origin；视口→内容（`columnAt/rowAt/hitTestColumn`）输入自动减 origin
  —— 手势命中零改动（View 传视口坐标）；滚动条/渐隐等纯视口层不受影响；
- `init` 校验 origin 非负有限（坏度量绝不进几何，单测锁定）。

**`TerminalView`（缩放与尺寸真源）**
- `onTouchEvent`：UP/CANCEL/POINTER_UP 复位 `pinchScaleAccum`；
- 新增 `currentGridSize(): TerminalGridSize?`（未真实布局返回 null ——
  宿主据此避免把 2×4 占位网格误报给 PTY）。

### 2. 宿主装配（`app` 模块）

- **`TerminalViewHost`**：`LaunchedEffect(activeSessionId)` 把 View 当前
  网格推给新活跃会话（`viewModel.resizeTerminal`）—— 后台创建的会话
  （agent 附属/装依赖/第二会话）不再以 runtime 默认 24×80 悬空；首个会话
  也不再等 150ms 防抖通道；
- **`TerminalKeyToolbar`**：主行 `horizontalScroll`（13 键 ≈560-620dp，
  窄屏旧版右裁）；
- **`ExtraKeysBar`**：垂直 padding 对齐 KeyToolbar（旧版只有水平 5dp +
  尾部 2dp Spacer → 双栏堆叠垂直节奏不一致）；
- **`TerminalScreen`**：NoticeBanner 错误判色补英文关键词（failed/
  failure/denied）。

## 验证

- `compile_terminal_view_jvm.sh`：**160 classes，0 error**（含新 origin
  API / fake bold / wide 探测 / pinch 复位 / currentGridSize）；
- `compile_terminal_jvm.sh`：**2385 classes，0 error**（emulator + native +
  platform/terminal 全链）；
- `:terminal-view` 全部 7 个测试类 **152 tests OK**（含新增 4 个居中几何
  锁定用例：floor 余量对称分摊 / 双向换算往返恒等 / 非法 origin 拒绝 /
  clamp 网格超视口时 origin 钳 0）；
- app 模块编译由 CI `:app:compileDebugKotlin` 兜底（改动均为既有 API 调用，
  imports 已核对）。

## 真机回归清单（对照症状）

1. **字体形变**：`echo -e '\e[1mbold\e[0m 中中文文'` —— 粗体与中文同行列
   对齐无压扁；`cmatrix`/`htop` 列框线竖直贯通；
2. **对称**：任意字号下左右余量目测均等；滚动条不再盖住最后一列字符；
3. **缩放**：捏合放大 → 松手 → 再轻捏一次**不应**有任何字号变化（旧版残留
   累积会凭空 ±1sp）；连续缩放每步 1sp 稳定不回跳；
4. **会话切换**：开两个会话（如 LOCAL + Ubuntu），来回切换 —— 每个会话
   行宽都与视口一致（旧版第二会话 80 列右裁 + 内容浮在高视口中间）；
5. **输出格式**：`seq 1 200 | pr -t -3 -W 90` 三列对齐；`ls --color` 彩色
   列无错位；`watch -n1 date` 重绘不漂移；
6. **键盘条**：360dp 窄屏 KeyToolbar 左滑可达 ⌨→FN 全部 13 键；ExtraKeys
   宏行与 KeyToolbar 上下 padding 节奏一致；
7. **新输出药丸**：滚动回看时仅出现**一个**跳底浮标（画布内重复药丸已删）。
