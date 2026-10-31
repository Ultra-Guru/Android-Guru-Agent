# T92 · 终端交互与 VT 语义精修 —— 滚动手感 / 鼠标链 / 输入法 / 引导韧性

> **[已退役 · T96 终端大重构 / PR #474]** 本文档描述的旧交互终端已删除，现存唯一终端为 M3 控制台（真实 PTY 执行）；文中涉及的配色方案 / 扩展键 / 键栏 / VT 网格渲染等子系统随之移除，本文仅作历史设计存档。

> 承接 T89（页面结构）/ T90（渲染层）。本轮聚焦**交互链与 VT 状态机语义**：
> 触屏滚动余量、外接鼠标、中文 IME 组合通道、欧式键盘死键/AltGr、
> TUI 程序依赖的 VT 边界语义（滚区/DECOM/CAN/SUB/C1），以及依赖安装
> 引导链的韧性（超时/死会话/按钮灰死）。方法学同前两轮：对照 Termux
> （termux-app）`TerminalView`/`TerminalEmulator`/`KeyHandler` 的成熟实现
> 与 xterm ctlseqs 规范，逐项定位本项目代码级根因。

## 症状 → 根因 → 修复对照表

| # | 症状 | 根因（旧行为） | 修复 |
|---|------|----------------|------|
| 1 | 慢速拖动「纹丝不动」，只有甩动才滚 | `handleScrollDelta` 逐事件取整 —— 单帧 MOVE 增量（5~15px）< 行高（~48px），余量每帧丢弃 | **滚动余量累加器**（Termux `mScrollRemainder` 同款）：跨事件累加，满一行滚一行 |
| 2 | 蓝牙/DeX 鼠标完全无响应 | 未实现 `onGenericMotionEvent` | **滚轮三档语义**：本地滚动 ×3 行；鼠标报告模式发 WHEEL 事件（vim/tmux 翻内部缓冲）；**报告位置取最近触点**（分屏下路由正确 pane） |
| 3 | vim 开鼠标模式后拖动能翻、一甩却滚本地历史 | fling 一律滚本地 scrollback | 鼠标模式下 fling = **滚轮连发**（速度成比例，限半屏） |
| 4 | vim 点击定位要等 ~300ms 双击窗 | 单击必走双击确认窗 | **即时点击模式**：`immediateTapEnabled`（鼠标报告开启即置位，动态跟随）—— UP 立即派发 Tap |
| 5 | 长按起选只有单 cell 高亮 | `beginSelectionAt` 只落单格 | **长按 = 词选起手**（Termux 长按扩词同款）+ 触觉反馈 |
| 6 | 双击选词后想拖扩 —— 动一下变滚屏 | 双击候选 + 移动 = 废双击进滚动 | 双击候选 + 移动 = **拖动扩选**（不进滚动） |
| 7 | 拖选到视口边缘就钉死 | `extendSelectionAt` 的 `cellAt` 端点被钉在视口内 | **边缘自动翻屏**（Termux updatePosition 同款）：拖出上/下沿逐 MOVE 滚一行，选区端点跟随 |
| 8 | 中文 IME 打不出中文 | `IME_FLAG_FORCE_ASCII` 请求 IME 切 ASCII 直通 —— 与组合区差分管线（专为中文设计）自相矛盾 | **删除 FORCE_ASCII**；`ACTION_NONE→GO`（部分键盘回车失效修复） |
| 9 | 欧式键盘重音字符被吞 | `COMBINING_ACCENT` 高位标记落不进 printable 区间 | **死键组合**（`getDeadChar` 合成 é/à/ñ） |
| 10 | 德/法布局 AltGr+Q 打出 ESC@（bash Meta 序列） | 右 Alt 也置 ALT 修饰位 | **AltGr 豁免**：纯右 Alt 不构成 xterm ALT（Termux 同款） |
| 11 | 主屏想翻 scrollback 无从下手（Shift+PgUp 被 vim 吃掉） | 未拦截 | **Shift+PgUp/PgDn = 本地翻页**（不透传 PTY，Termux 拦截同款） |
| 12 | tmux 底栏重绘把主内容卷走 | LF/IND/NEL 旧判据 `row++ > bottom`：光标在滚区**外**（DECSTBM 后定位区外）也触发滚屏 | **Termux doLinefeed 对齐**：光标在区内且位于底边才滚；区外只下移停屏底 |
| 13 | Tab 布局型 TUI 跳位错乱 | `CSI I`（CHT）缺失被静默吞 | **CHT 实现**（前移 Ps 个制表位） |
| 14 | vim 假定 `?6h/l` 后光标在 home | DECOM 只改标志不动光标 | **DECOM 置位/复位归位**（DEC STD 070：新 origin home） |
| 15 | less/emacs 变体不恢复光标 | `CSI ?1048` 缺失 | **1048 保存/恢复**（与 DECSC/DECRC 同语义） |
| 16 | vim/MC 数字键盘加号变导航键 | DECKPAM 下 `+` 发 SS3 'l'（'l' 是分隔符） | **小键盘表修正**：`+` = SS3 'k'（xterm/DEC 表） |
| 17 | `ESC[3␘Hello` 丢 H | CAN/SUB 被吞进 CSI_IGNORE，后续 final 字节被吃 | **CAN/SUB = 序列终止符**（立即清态回 GROUND） |
| 18 | CR/LF 混进 CSI 参数被吞 | 序列态不执行 C0 | **C0 在任何序列态立即执行**（xterm 同款） |
| 19 | `ESC]0;hi ESC[2J` 把 "hi" 设成标题 | OSC 被 ESC 打断照样应用半截串 | **畸形 OSC 丢弃**半截字符串 |
| 20 | 标题带 emoji 变乱码 / CR/LF 污染标题 | `appendString(cp.toChar())` 截断星面码点；C0 拼进标题 | **码点级 append**（appendCodePoint）+ C0 不拼串 |
| 21 | 列宽收缩 resize 后光标下方整段内容消失 | Reflow「光标行留屏」锚定把光标窗口以下输出**静默丢弃** | **底部锚定零丢弃**（Termux resize 同款）：屏 = 最新 newRows 行，其余回灌 scrollback；尾部默认样式空行先裁剪再锚定 |
| 22 | 擦行重绘 + resize 后空行与下一逻辑行粘连 | `eraseRow` 全宽擦除不断软换行接续链 | **全宽擦除断链**（wrapFlags 同步 false） |
| 23 | 环境中心安装按钮灰死到重启 | 循环中任一异常杀死协程 → `runningId` 永不复位 | **try/finally 兜底**（installAll/installAndroidGroup/runCommand） |
| 24 | apt 慢网被硬杀留半安装状态、日志误报「等待超时」 | 120s 后直接 SIGKILL | **300s + SIGTERM 温和终止**（与 Provisioner 对齐） |
| 25 | 会话死亡后写死 PTY → job 挂到超时 | `ubuntuSessionId`/`depSessionId` 缓存永不过期 | **实时存活校验**（终态黑名单直查 runtime）—— 死亡即清缓存重建 |
| 26 | 关闭会话掉帧 | native close（HUP→TERM→KILL 串行 sleep）在主线程 | close 移入 `Dispatchers.IO` |
| 27 | 按住退格/方向键要狂点 | 工具条键无连发 | **长按连发**（按住 400ms 后 60ms 周期；⌫/方向/HOME/END/PGUP/PGDN） |

## 修复明细

### 1. VT 状态机与内核（`terminal-emulator`）

**`VtParser`（状态机对齐 xterm）**
- `RAW_C1_TAG`（bit 24）：Utf8Decoder 透传**原始 8 位 C1 字节**时打标 ——
  仅原始字节走 C1 控制语义；**合法解码产物落在 C1 区（U+0080..U+009F）
  直接丢弃**（xterm ctlseqs：「It is not possible to use a C1 control
  obtained from decoding the UTF-8 text」；Termux 同款忽略）；
- CAN(0x18)/SUB(0x1A)：任何序列态立即作废回 GROUND（后续文本正常落屏）；
- C0 控制在任何序列态立即执行（BEL/LF 混进 CSI 参数照常生效）；DEL 在
  序列态忽略；
- OSC/DCS 字符串：C0 不拼串（CR/LF 不得污染标题）；**码点级追加**（emoji
  标题不被 toChar 截断）；OSC 被 ESC + 非 ST 字节打断 = 畸形序列，半截
  字符串丢弃。

**`TerminalCore`（DEC 语义）**
- LF/VT/FF、IND、NEL：光标在滚区内且位于底边距 → 滚屏（∩ 左右边距窗口）；
  光标在滚区外 → 只下移停屏底（tmux 底栏/vttest 必踩的旧判据修正）；
- CHT（`CSI I`）：前移 Ps 个制表位；
- DECOM（`?6h/l`）：置位/复位归位新 origin home（滚区顶/屏顶 + 左边距）；
- `CSI ?1048h/l`：保存/恢复光标 + 字符集（ANSI.SYS 形式）；
- `KeySequenceTables`：DECKPAM 下 NUMPAD_ADD = `SS3 k`。

**`Reflow`（resize 零丢弃）**
- 底部锚定：屏 = 重排输出的**最新 newRows 行**，之前的行回灌 scrollback
  （旧「光标行留屏」锚定把光标窗口以下内容静默丢弃）；
- 光标行越出窗口顶 → 钳屏顶；
- 尾部「默认样式空白行」先裁剪再锚定（空白行仅在后续有非空行时才回插；
  带样式空白行保留）。

**`ScreenBuffer`**
- `eraseRow` 全宽擦除（fromCol≤0 且 last≥cols-1）同步断软换行接续链。

### 2. 交互链（`terminal-view`）

**`TerminalView`**
- `scrollRemainderPx`：拖动滚动余量累加器（跨事件累加，满一行滚一行）；
  DOWN/UP/CANCEL/POINTER_UP 清零（跨手势不残留）；
- `onGenericMotionEvent`：外接鼠标滚轮 —— 本地滚动（每档 3 行）或鼠标
  模式 WHEEL 报告（位置 = 最近触点/指点位置，tmux 分屏路由正确 pane）；
- 鼠标模式 fling = 滚轮连发（行数 ∝ 速度，限半屏）；
- `immediateTapEnabled` 动态跟随快照的 mouseMode（vim 开关鼠标报告即
  生效）；首个带光标快照到达即排闪烁（旧要等窗口焦点变化）；
- 长按 = 词选起手 + `performHapticFeedback(LONG_PRESS)`；
- 拖选边缘自动翻屏（视口上/下沿一行动作）；
- 引擎 reset（scrollbackBase 回退 = 快照代际重置）时清残留选区；
- `dispatchHardwareKeyEvent`：Shift+PgUp/PgDn 本地翻页（不透传 PTY）；
  死键组合重音（`KeyCharacterMap.COMBINING_ACCENT`/`getDeadChar`）；
  AltGr（纯右 Alt）不置 ALT 修饰位；
- 捏合缩放：选区激活时豁免（选区中双指误触不再 ±1sp）。

**`TerminalGestureModel`**
- `immediateTapEnabled`：鼠标模式 UP 即派发 Tap（跳过双击窗）；
- 双击候选 + 移动超 slop = 拖选（DragStart/DragMove），不进滚动。

**`TerminalInputConnection`**
- 删除 `IME_FLAG_FORCE_ASCII`（中文 IME 组合通道杀手）；`IME_ACTION_NONE
  → GO`（TV/第三方键盘回车失效）。

**`TerminalCanvasRenderer`**
- BLOCK 光标反色字符：命中 run 以背景色在光标 cell clip 内重绘（旧 0.9
  alpha 色块直接盖字 —— Termux invertCursorTextColor 对齐）；
- `runWidthCache`：同代 run 文本宽度缓存（闪烁/滚动帧零 measureText）；
  runCache/runWidthCache 同代际失效；
- 渐隐着色器调色板代际（换肤/OSC 10/11 动态色后重建，旧版只按尺寸失效）。

### 3. 引导与集成韧性（`app`）

**`TerminalViewModel`**
- `installAll`/`installAndroidGroup`/`runCommand`：try/finally 兜底
  `runningId` 复位（异常不再灰死全部安装按钮）；
- `execAndAppend`：会话拉起（含 proot 探测/forkpty）移入 IO；apt 等待
  120s→**300s**、超时 SIGKILL→**SIGTERM**；
- `depSessionId` 存活校验改实时直查 runtime（旧用 2s 轮询快照，死亡后
  最长 2s 误判存活）；
- `closeSession`/`closeActiveAndRestart`：close 移入 IO（native 串行
  sleep 掉帧）。

**`EnvironmentProvisioner`**
- `ensureUbuntuSession`：缓存会话**实时存活校验** —— 死亡（终态黑名单）
  即清缓存重建（旧版写死 PTY → job 挂到超时 → 日志误报）。

**`TerminalKeyToolbar`**
- `repeatOnHold`（纯 pointerInput，无自定义协程作用域）：按下即触发、
  400ms 后 60ms 周期连发、绝对时间戳调度（抖动事件不饿死计时）；
  应用于 ⌫/方向键/HOME/END/PGUP/PGDN。

### 4. SRP 拆分与测试工具

- `TerminalKeyEventRouter.kt`（新）：硬件键纯决策（Shift+PgUp 翻页判定/死键
  状态机/AltGr 豁免）—— TerminalView 保留副作用胶水；
- `TerminalWordGeometry.kt`（新）：词选几何纯函数（列↔字符索引/词边界）；
- `MotionEvent.toTouchSample()` 扩展（TerminalGestureModel.kt）：事件纯映射；
- 菜单工厂（`defaultSelectionMenuItems`/`urlMenuItems`，TerminalViewClient.kt）；
- 新增 `scripts/run_terminal_tests_jvm.sh`：JVM 全量 JUnit（emulator +
  view 模型），CI 之外本地可复现的回归入口。

## 验证

- `compile_terminal_view_jvm.sh`：**170 classes，0 error**；
- `compile_terminal_jvm.sh`：**2386 classes，0 error**；
- `run_terminal_tests_jvm.sh`：**634 tests OK**（601 存量 + 33 新增）；
- `check_file_size.sh`：TerminalView.kt 因 T92 新增一度达 1295 行（预算 1200）
  —— 按门禁自身指引沿职责接缝拆出上述三个新文件后 1198 行达标；
- 三门禁：code_quality / file_size / kotlin_balance 全过；
- app 模块编译由 CI `:app:compileDebugKotlin` 兜底。

**新增回归测试**
- `T92TerminalSemanticsTest`（22 用例）：LF/IND/NEL 滚区外语义 ×3、CHT、
  DECOM 置位/复位、1048、CAN/SUB 作废（CSI/OSC）、C0-in-CSI 立即执行、
  原始 8 位 CSI 仍解析、解码 C1 丢弃、OSC emoji 码点/畸形中止/C0 不拼串、
  eraseRow 断链、Reflow 底部锚定（零丢弃/最新行/空行不推挤）×3；
- `T92GestureInteractionTest`（5 用例）：即时点击（零延迟/两次独立/默认
  仍走窗）、双击后拖动 = 扩选、普通拖动仍滚动；
- `T92WordGeometryTest`（6 用例）：窄/宽字符列↔字符索引双向映射、逆映射
  往返恒等、词边界双向扩、空白段选择、空行拒绝；
- `KeySequenceTablesTest`：NUMPAD_ADD 期望更新为 `SS3 k`。

## 真机回归清单

1. **滚动手感**：终端里慢速拖动（每帧几像素）应持续滚动 —— 不再只有
   fling 才动；快速滚动突然停止不跳行；
2. **外接鼠标**（DeX/蓝牙）：滚轮上下滚 scrollback；`vim` + `set mouse=a`
   下滚轮翻 vim 内部缓冲；点击定位零延迟（不等双击窗）；
3. **中文输入**：Gboard 中文模式下正常打中文 + 组合区差分上屏正确；
4. **死键/AltGr**（欧式布局）：´ + e = é；AltGr+Q = @（非 ESC@）；
5. **Shift+PgUp**：less/vim 里翻本地 scrollback 而非 TUI 内部翻页；
6. **tmux**：底栏状态下主区输出不把底栏卷走；`vttest` DECSTBM 系列
   滚区断言通过；
7. **vim 视觉模式**：双击选词 → 不抬手直接拖扩选；长按词选起手有振动；
   拖到屏顶/底自动翻屏继续选；
8. **resize**：竖屏↔横屏（列宽 2× 变化）后光标下方内容完整（可上翻
   找回全部旧输出）；
9. **环境中心**：安装依赖中途断网/失败 → 按钮恢复可点（不再灰死）；
   apt 超 300s 温和终止无半安装残留；
10. **会话韧性**：Ubuntu 会话里 `exit` 退出后再点安装 → 自动重建会话
    （日志出现「已退出——重建」），不再挂到 300s 超时；
11. **工具条**：按住 ⌫ 连发删字符；按住 ↓ 连续导航；
12. **BLOCK 光标**：光标盖住字符可读（反色重绘）。
