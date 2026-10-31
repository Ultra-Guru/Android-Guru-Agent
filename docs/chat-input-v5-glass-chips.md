# 聊天输入 v5 — 流式玻璃 / 技能 Chip 内联 / 按钮防挤压 / 函数调用文案纠偏

> 状态：已实施 · 分支 `feat/stream-glass-chips-multiselect`
> 用户报告四项：① 流式输出的液态/毛玻璃在 Coding 与 Agent 两模式都没做好；② 部分图标/按钮「消失」；③ Agent 屏新对话与历史对话按钮不见了；④ 函数调用的文案写错了（工具本来就全部默认开启）——以及选技能/MCP 时弹层与输入区 UI 重叠。
> 前置：[liquid-glass-system.md](liquid-glass-system.md)（玻璃体系）· [hub-ecosystem.md](hub-ecosystem.md)（斜杠管线）· [tool-system-v4.md](tool-system-v4.md)（工具默认开启语义）

---

## 0. 一句话

v4 把斜杠选中项挂成输入栏上方的独立胶囊行；v5 把它**塞进输入框本身**——文字后追加的行内 chip，多选、去重、可删；同时把两工位的流式输出气泡接入玻璃体系，修掉「按钮被挤到 0 宽」的顶栏缺陷，并按用户口径纠偏函数调用文案。

## 1. 四项根因与修法

### 1.1 流式输出零玻璃（两模式）

| 现状（v4 前） | 根因 |
|---|---|
| Agent 屏：AI 回复气泡 = 不透明 `Surface` + 手绘描边 | 气泡从未接入 `GlassCard`；与输入栏/工具卡的玻璃语言割裂 |
| Coding 屏：结论气泡裸铺背景、输入条是死色 `surfaceContainerLow` | 屏内**没有任何 haze 源**——不是玻璃没做好，是玻璃系统在这屏根本没铺 |

**修法**：

- **Agent 屏**（`AgentChatMessages.kt`）：`AgentBubble` / `StreamingResponseBubble` / `ThinkingBubble` 三气泡统一换 `GlassCard(GlassStyle.Card, Frosted 档)`。流式与完成态**同 shape 同档**——流式结束的瞬间切换无容器跳变。Frosted 是诚实降级：气泡位于 hazeSource（消息列表）子树内，Haze 1.4 不支持嵌套采样（与展开态工具卡同档，1.5.0 才解禁）。
- **Coding 屏**（`CodeScreen.kt` + `CodeStreamCards.kt`）：整体重构为 **Agent 屏同款 overlay 模式**——时间轴（`CodeStreamTimeline`）成为 haze 源；底部栈（终端尾窗/提问卡/错误条/模式行/输入栏）悬浮于时间轴之上；输入栏 `GlassCard(GlassStyle.Floating)` 由此获得**真实 backdrop 采样**。时间轴 `contentPadding` 按悬浮栈实测高度（`lowerStackInsetPx`）补偿，最后一条不被遮挡。流式结论气泡与思考卡换 Frosted 档，与 Agent 屏同一套语言。

### 1.2 图标/按钮「消失」（Agent 顶栏 + Coding 工作区栏）

根因不是图标缺失，是**被挤到 0 宽**：角色胶囊 + 模式胶囊 + 思考菜单依次加入同一 Row 后，窄屏 340dp 被前序元素吃完，尾部两个 34dp 按钮（历史/新会话）被剩余空间 coerce 到近乎 0 宽——视觉上就是「按钮和图标没了」。Coding 屏 `WorkspaceBar` 同病（工作区名 + 下拉占满前段后，逻辑选择器/长任务/新会话被压没）。

**修法**（两屏同构）：前段选择器收进 `weight(1f)` 的横向滚动行（空间不够时**滚动**，永不挤压）；尾部按钮固定在行尾**永远可见**。窄屏行为从「按钮消失」变为「前段可横滑」。

### 1.3 选技能时 UI 重叠

根因：斜杠弹窗固定 `BottomStart` 向上生长；键盘弹出/小屏时上方可用高度不足，系统把弹窗钳位进可用区 → 面板直接盖住输入行。叠加独立胶囊行（`PipelineCapsuleRow`）自身的行高突变，观感是「糊在输入框上」。

**修法**（两刀）：

1. **弹窗空间翻转**（`SlashCommandButton.kt`）：实测锚点在窗口可视区（扣除 IME）内的上方空间，不足 320dp 自动**向下翻转**（TopStart）。
2. **技能 chip 内联进输入框**（新组件 `SkillChipInputField`）——见下节，独立胶囊行整个删除。

### 1.4 函数调用文案写错

旧文案把「函数调用」菜单当成能力开关暗示（`函数调用 (N)` / 「勾选的函数本轮必须被调用…不勾选则使用默认工具集」），与 v4 事实不符：**全部 ~110 个工具默认开启**，勾选只是 `tool_choice` 强制。用户口径：函数调用有用但被过度神化——它不是「让模型变聪明」的开关，而是模型**结构化请求外部操作**的协议；真正执行的是本地代码/引擎，模型只输出调用意图。

**修法**（`strings_chat.xml` en/zh 对称）：

- 删除误导键 `chat_function_calls` / `chat_function_calls_n`；
- `chat_function_forced_hint` 重写为：「全部工具默认开启，并可经目录按需检索——函数调用不是『让模型更聪明』的开关，而是模型结构化请求外部操作的一套协议。需要实时/私有数据或外部动作时有用；纯文本任务属过度设计。」
- 新增 `chat_function_force_section`（「强制调用以下函数（可选）」）作为勾选区块标题，与顶部语义提示呼应：勾选 = 强制，而非「不勾就不能用」。

## 2. 技能 Chip 输入框（SkillChipInputField）

### 2.1 方案选型

纯 Compose `BasicTextField` 无法把可删除 chip 内联进可编辑文本（无 Spannable 对等物）。组件走 **AndroidView + EditText + SpannableStringBuilder + ReplacementSpan**：

```
┌────────────────────────────────────────┐
│ 帮我查一下这个订单 [网页搜索] [物流]▏   │
└────────────────────────────────────────┘
```

- 每个 chip 在 buffer 里只占一个 `\uFFFC`（OBJECT REPLACEMENT CHARACTER）——**退格天然删除整个 chip**，无需按键拦截；
- 圆角胶囊底 + 居中文字由 `SkillChipSpan.draw` 自绘（`getSize` 定宽，宽度 = 标签测量宽 + 双侧 padding）；
- chip 文字 0.92 倍正文号 + 14 字符截断（超长技能名不撑爆行宽；上报 VM 保留全名）。

### 2.2 交互契约

| 动作 | 行为 |
|---|---|
| 斜杠菜单选流水线条目（skill/mcp/connector/plugin） | chip **追加到文字后面**（末尾非空白先补空格），菜单**保持展开**可继续多选 |
| 再点已选条目（菜单内 ✓ 态） | 摘除对应 chip |
| 普通指令条目（模板类） | 返回 false 选中即收起，走原有插入/路由 |
| 退格 | chip 只占一个字符 → 一次退格删整片（含 span） |
| 点击 chip | `ACTION_UP` 在 touchSlop 内 → `getOffsetForPosition` 命中 span 区间 → 连带相邻空格删除 |
| 发送 | 纯文本（剥 `\uFFFC` + 收敛连续空格、**保留换行**）与 chip 集合分通道上报；VM 拼 `/skill:` 等管线指令 |

去重键 `type:id`（追加前 `getSpans` 扫描 + VM 侧 `distinctBy` 双保险）。

### 2.3 状态同步（单向数据流 + 回声抑制）

- 组合态：`value`（纯文本草稿，VM）+ `chips`（VM 列表）；View 态：EditText buffer（混排）；
- View → 状态：TextWatcher 每次变更后提取纯文本/chip 集合，**与最近一次上报值不同才上报**（镜像存普通字段而非快照状态，避免 update lambda 反向写快照引发重组回环）；自己按键的回声不回写——保住中文输入法组合段；
- 状态 → View：update lambda 同步——外部变更（草稿恢复/发送清空/全屏编辑确认）才 `setText`（光标尽量保留）；chip 集合 diff 增删 span（新增追加、消失按区间删除）；
- factory 只跑一次，回调经 `ChipCallbacks` holder 转发、update 时刷新引用——规避 AndroidView 闭包过期经典坑。

### 2.4 键盘可靠性（自 AdaptiveInputField 移植的两轮 P0 修复）

焦点从无到有 → 显式 `showSoftInput`；`ACTION_DOWN` → 提前 show（不消费事件）；两者同时 `requestApplyInsets()`（弹层关闭后 stale IME insets 的标准对策，防输入栏不上抬）。字符计数（>200）、全屏分层编辑、发送键行为可配置（send/newline）全量保留。

### 2.5 退役清单

- `AdaptiveInputField.kt`（398 行）删除——被 SkillChipInputField 全面取代；
- `PipelineCapsuleRow.kt`（159 行）删除——胶囊行概念退役。

## 3. 多选管线（VM 双工位同构）

`pendingCommand: PendingPipelineCommand?` → `pendingCommands: List<PendingPipelineCommand>`：

- `addPendingCommand`（去重追加）/ `updateChips`（外部整体同步）/ `clearPendingCommands`；
- 发送时 `handleMultiChipPipeline(text, chips)`：N 个 chip 依次拼管线指令前缀，正文收尾；无 chip 走原直发路径；
- AgentChatViewModel 与 CodeViewModel 同构实现；发送按钮 enabled 条件 `draft.isNotBlank() || pendingCommands.isNotEmpty()`。

## 4. 质量门禁

- i18n 镜像：新增/修改键 en/zh 1:1（含斜杠菜单 9 键原硬编码中文的 i18n 化）；
- 结构质量：删除两文件净 -895 行，新增组件 637 行单文件（预算内）；
- 括号平衡（lexer-aware）全绿；App Module Compile / Build Debug APK / Static Analysis CI 全绿。

## 5. 文件索引

| 文件 | 职责 |
|------|------|
| `ui/component/SkillChipInputField.kt`（新） | chip 输入框全家：SkillChipSpan / appendChip / deleteSpan / 回声抑制 / 全屏编辑 |
| `ui/component/SlashCommandButton.kt` | v5 多选（onItemSelected 返回 Boolean 保持展开）+ ✓ 勾选态 + 空间翻转 + focusable=false 不抢 IME + BackHandler |
| `ui/screen/agent/AgentChatScreen.kt` | 顶栏防挤压重构 + chip 输入栏接线 + 胶囊行移除 |
| `ui/screen/agent/AgentChatMessages.kt` | 三气泡 GlassCard(Frosted) 化 |
| `ui/screen/agent/AgentChatViewModel.kt` | pendingCommands 多选态 + handleMultiChipPipeline |
| `ui/screen/agent/ToolkitRingButton.kt` | 强制调用区块标签（chat_function_force_section） |
| `ui/screen/code/CodeScreen.kt` | 玻璃悬浮层重构 + WorkspaceBar 防挤压 + chip 输入栏 |
| `ui/screen/code/CodeViewModel.kt` | 多选管线（同构） |
| `ui/screen/code/stream/CodeStreamCards.kt` | 结论/思考卡玻璃化 |
| `ui/screen/code/stream/CodeStreamTimeline.kt` | bottomInset + hazeState 参数化 |
