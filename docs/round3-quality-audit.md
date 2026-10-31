# 第三轮质量审计：胶囊流式链路 + v1.4.3 新页面

> 审计范围：PR #194/#195/#196 与 v1.4.3 合入后的新增代码
> （core/code-engine stream/longtask、app stream UI 族、About/Glass/抽屉）
> 方法：三路并行深度审查（引擎状态机 / Compose UI / 页面生命周期），
> 每项发现均定位到行号并附触发条件；修复后引擎侧 48 单测 + longtask 49 单测全绿。

## 一、修复清单（按风险等级）

### P0（必现崩溃 / 功能死亡）

| # | 位置 | 问题 | 修复 |
|---|------|------|------|
| 1 | `CodeStreamSession.replaceAll` | 恢复检查点后 `entrySeq` 不回拨 → `nextId()` 从 e-1 重发 → 与恢复条目撞号 → LazyColumn `Key was already used` 崩溃（重启即必现） | `entrySeq = restored.maxOfOrNull { entrySeqOf(it.id) }`，`entrySeqOf` 解析 "e-42"/"cycle-1-e-43" 后缀 |
| 2 | `CodeStreamTimeline` showFab | `derivedStateOf` 闭包捕获首帧空 snapshot 的普通属性读取（不可追踪）→ FAB 永远不出现（死功能） | 派生态只依赖 `anchor.isAtBottom`；entries 判空移到组合处 |

### P1（特定条件触发的确认缺陷）

| # | 位置 | 问题 | 修复 |
|---|------|------|------|
| 3 | `closeOpenCycle` | 整段 subList 清空把轮内思考/迭代状态条目一并吞掉（时间轴数据丢失，几乎每个验证轮都触发） | 只折叠 `ToolCapsuleEntry`，非工具条目紧随轮次卡原位保留 |
| 4 | `beginRun`/`Aborted` | 中止后 `streamingAssistant/Thinking` 悬挂 → 下一轮文本拼进旧气泡 + 旧气泡永远「生成中」 | 双入口收口（isStreaming=false + 置空） |
| 5 | `restoreSessionSnapshot` else 分支 | 切到无快照工作区：时间轴泄漏进新工作区 + 污染新工作区落盘检查点（持久化跨工作区数据污染） | `streamSession.clear()` + `stream = CodeStreamSnapshot()` |
| 6 | `UnifiedDiffParser` | mini 格式首个变更落在文件头几行时无 `@@` 标记 → 内容行静默丢弃 → 文件顶部编辑（import/配置头）整块 diff 消失 | 见过文件头即隐式开 hunk；顺带修多文件 git diff 文件头误吸收 |
| 7 | `CodeScreen` 详情弹层 | 持有 `StreamToolCall` 对象副本（每 25ms 批次换新实例）→ 状态/耗时/Diff 冻结，耗时无限增长 | 存 id，每次重组从活快照重查（含轮次卡内折叠胶囊） |
| 8 | `StreamScrollAnchor.observe` | 只监听索引递减——长条目内部上翻（index 不变）被 auto-follow 拽回底部 | 方向检测加 offset 递减 + 程序化滚动抑制标志 |
| 9 | `snapToLast` | 锚到末条顶部而非尾部——长气泡流式时新内容永远在视口外 | 时间轴尾追加 1dp 哨兵条目 |
| 10 | `TerminalBody` | `fillMaxSize` 把 `heightIn(max=260dp)` 钉死成恒 260dp 黑块（一行输出也撑满） | 去掉 fillMaxSize；滚动状态提升过折叠开关 |
| 11 | `CodeDiffView` | 全量急切组合：3000 行 diff = 12000 Text 节点一次性构建（低端机 ANR 风险） | LazyColumn 逐 hunk 组合；详情体去同向嵌套滚动 |
| 12 | `FileChipsCard` | 普通 Row 放 6 个等宽 chip：≥4 个必溢出屏宽不可达，超出静默丢弃 | FlowRow 换行 + "+N 个" 显式提示；chip 改非交互 Surface（原空 onClick 是欺骗性可供性） |
| 13 | 终端折叠钮 28dp / 管道胶囊关闭钮 20dp | 违反 Material 48dp 触摸目标红线 | `minimumInteractiveComponentSize()` 保触区，Icon 缩视觉尺寸 |
| 14 | `AboutUpdatePanel` 补丁节省百分比 | `full.sizeBytes` 清单缺省 0 → Long 除零 `ArithmeticException`（组合期崩溃） | `> 0` 守卫 + `coerceIn(0,100)` |
| 15 | `UpdateChecker`/`MirrorSpeedProbe` | 每次进页新建 2 个 OkHttpClient（连接池/线程池堆积）；`check()` 阻塞调用不响应取消（离页仍占 IO 线程到超时） | 进程级 `UpdateHttp.client` 单例（测速 newBuilder 派生）+ `suspendCancellableCoroutine` + `invokeOnCancellation { call.cancel() }` |
| 16 | `ApexDrawerContent` | 订阅全量 `uiState`：流式期间每 token 重组整个抽屉（抽屉关着也重组，13 个玻璃导航项全量重执行） | VM 新增 `drawerBadges` 窄化流（三字段 + distinctUntilChanged） |
| 17 | `AboutHero` 四路无限动画 | 非 lazy 滚动页面滚出视口后动画仍逐帧 invalidate（含 haze 模糊重采样）| 滚动可见性门控：滚出 1.5 倍 Hero 高度切静态降级帧 |

### P2（质量改进，节选）

- `onToolComplete` 幂等闸：已终态调用重放 Complete 不再重复自增 `failedToolCallCount`
- `ThinkingComplete/ResponseComplete` 无先导 Chunk 时全文不再静默丢弃（非流式 provider 路径）
- `affectedFiles` 对齐文档「本轮运行」语义（每 run 归零）；`replaceAll` 从最后 FileChipsEntry 回填（重启后 committedFiles 不清零）
- `lastEventId` 双重前缀（"e-e-42"）修正
- exit code 尾部 8 行内匹配（避免正文日志 "exit code 0 desired" 误命中）
- 代理对安全截断 `safeTakeLast`（emoji 不劈豆腐块）
- `cachedSnapshot` 死状态删除；grep 文件头判定精确化（ripgrep `path:line:` 形态，代码行含冒号不再误判）
- 骨架态真呼吸（0.3↔0.6 往返，原一次性淡入静止）；GlassLab 镜面扫掠 keyframes 真停顿（原 Restart+StartOffset 背靠背无停顿，与宣传文案不符）
- 状态徽标/exit code/状态枚举全部走 stringRes（中英双语）；胶囊加 `Role.Button` + `contentDescription`（TalkBack 可感知成败）
- `formatCapsuleDuration` 统一 Locale.US（防局部数字字形漂移）；深色主题 exit 0 绿改亮绿（对比度）
- 下载进度轮询 `DownloadManager.query` 收敛 Dispatchers.IO（原主线程 binder IPC 数千次）
- `formatMb` 一位小数（<1MB 不再显示 "0 MB"）
- 思考卡 remember key 修正（派生 Boolean key 会吞用户展开态）；`LaunchedEffect` 补 `isStreaming` key
- ANSI 清洗扩展 OSC 标题序列与 `\r` 进度条残留

## 二、验证

| 项 | 结果 |
|----|------|
| 引擎侧单测（stream 4 类） | 48/48 绿（新增 8 条回归：撞号/中止串台/折叠吞条目/隐式 hunk/幂等重放/非流式全文/affectedFiles 语义×2） |
| longtask 单测 | 49/49 绿 |
| diff 解析黄金样本 | mini 无先导 @@ / 多文件 git diff 新增 2 条 |
| 括号平衡 | 21 个改动文件全过 `kotlin_balance.py` |
| 文件行数门禁 | 737 main + 221 test 全在预算内 |
| 反模式门禁 | 无反射派发 / 无 printStackTrace |
| app 模块编译 | 依赖 CI（本地无 Android SDK） |

## 三、遗留（下轮候选）

- 终端面板接入 StreamScrollAnchor（当前手搓 ScrollState 效果，KDoc 声称「独立锚定」语义已对齐但实现未复用）
- `AboutUpdatePanel` 下载状态提升 ViewModel（跨页丢失校验/安装闭环）
- `ToolCallStatus.PARTIAL` 的「hunk 部分应用」语义无生产者（当前仅恢复中断态使用）
- `GlassLabDynamics`/`AboutHero` 动画修饰符族抽取（两处 ~90% 重复实现）
- 会话内 terminalBuffers LRU 淘汰（EPIC 级长会话内存上限）
