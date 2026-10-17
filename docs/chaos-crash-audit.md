# 混沌工程 Crash 审查报告（生命周期竞态 · 数据一致性 · 版本兼容性）

> **审查角色**：Android 资深质量架构师（专精 Crash 分析与边缘场景）
> **审查方法**：混沌工程思维 —— 假设"组件随时会被销毁 / 输入永远是恶意的 / 时序永远是最坏的"，对 `main@c3176ed` 全量逐行审查约 400 个 Kotlin 文件
> **审查维度**：① 生命周期竞态条件 ② 数据一致性与边界值陷阱 ③ 隐式依赖与版本兼容性
> **行号基准**：`main` 分支 commit `c3176ed`（本文档行号与修复 PR 中 `git diff` 行号一致）

---

## 一、审查结论总览

| 维度 | P0-Crash | P1 | P2 |
| :--- | :--- | :--- | :--- |
| 生命周期竞态 | 2 | 4 | 4 |
| 数据一致性与边界值 | 2 | 5 | 5 |
| 隐式依赖与版本兼容 | 1 | 4 | 4 |
| **合计** | **5** | **13** | **13** |

本次审查同步落地了 **6 个文件的最小化修复**（详见文末"已落地修复清单"），全部为低风险防御式代码，不改变任何业务语义。

---

## 二、第 1 类：生命周期竞态条件

### L-01【P0-Crash】TaskRuntime 互斥 `check()` 抛出的 IllegalStateException 未捕获 → 斜杠指令路径直接闪退

| 项目 | 内容 |
| :--- | :--- |
| **文件:行号** | `app/src/main/kotlin/com/apex/agent/ui/screen/agent/AgentChatViewModel.kt:1161` · `core/agent-engine/src/main/kotlin/com/apex/agent/core/engine/task/TaskRuntime.kt:147-149` |
| **问题代码** | ```kotlin // TaskRuntime.kt:147（同步抛出，非 Result 封装） check(tryClaim()) {   "TaskRuntime rejects concurrent execution: an active execution is already running" } // AgentChatViewModel.kt:1161（handleSlashCommand，原代码全链路无 try/catch） currentJob = viewModelScope.launch {     taskController.execute(UserInput.text(execute.agentPrompt)).collect { handleEvent(it) } } ``` |
| **触发条件** | ① 发送消息 A，引擎 claim 占用；② 点"停止"—— `abort()` 同步复位 `isLoading=false`，但 `claiming` 要等引擎 IO 流收尾才释放（窗口可达数秒）；③ 窗口期内发送 `/skill:xxx` → `check(tryClaim())` 抛 `IllegalStateException`，`viewModelScope.launch` 无任何 catch → 冒泡到 UncaughtExceptionHandler → **进程死亡**。对照：`runEngine`（普通消息）有 catch 不崩；`retry()/resume()` 有 `claiming.get()` 预检幸免 —— 唯独这条路径两头都没有。 |
| **后果** | `IllegalStateException: TaskRuntime rejects concurrent execution` 全进程闪退 |
| **修复** | 已落地 —— `handleSlashCommand` 内补齐与 `runEngine` 一致的 try/catch，`CancellationException` 重抛，其余降级为 `AgentUiMessage.Error` 卡片并复位 `isLoading`。 |

### L-02【P0-Crash】`BrowserScreenshot` 后台模式 100% 抛 IllegalArgumentException

| 项目 | 内容 |
| :--- | :--- |
| **文件:行号** | `app/src/main/kotlin/com/apex/agent/browser/BrowserEngine.kt:682` |
| **问题代码** | ```kotlin val wv = activeTab()?.webView ?: return@withContext null onProgress?.invoke(50, "正在渲染视口截图…") val bmp = Bitmap.createBitmap(wv.width, wv.height, Bitmap.Config.ARGB_8888)  // ← 无守卫 ``` |
| **触发条件** | 引擎是"后台无父"驱动：浮窗从未展示时 WebView 从未 layout，`wv.width == wv.height == 0`；而 `WAITING_HUMAN` 期间工具被锁死，于是任何会话的首次截图必然传入 0×0。`Bitmap.createBitmap(0, 0, …)` 直接抛 `IllegalArgumentException: width and height must be > 0`。 |
| **后果** | 工具能力 100% 失效（当前被 executor catch 兜住转为 Error 字符串）；任何绕过 executor 的直接调用即硬崩溃。 |
| **修复** | 已落地 —— 增加尺寸守卫：`width <= 0 || height <= 0` 时优雅返回 `null` 并上报"视口尚未布局"。 |

### L-03【P1-内存泄漏】浮窗 `doShow` addView 成功后若后续步骤抛异常 → 僵尸窗口永不可移除

| 项目 | 内容 |
| :--- | :--- |
| **文件:行号** | `app/src/main/kotlin/com/apex/agent/browser/BrowserOverlay.kt:152-164` |
| **问题代码** | ```kotlin try {     windowManager.addView(root, params)     owner.onCreate(); owner.onStart(); owner.onResume()     rebindWebView()      // ← addView 成功后才执行，可能抛异常 } catch (e: Exception) {     rootView = null      // ← 只清引用，不 removeView！     ... } ``` |
| **触发条件** | `addView` 成功后，`owner.onCreate/onStart/onResume` 驱动 ComposeView 首次重组、或 `rebindWebView()` 对已被 `onRenderProcessGone`/`rebuildActiveWebView` 销毁的 WebView 执行 `host.addView` 时抛出任何异常。 |
| **后果** | 全屏 `TYPE_APPLICATION_OVERLAY` 窗口永久滞留屏幕；`doHide()` 因 `rootView == null` 直接 return —— 用户再也无法关闭，泄漏整棵 View 树 + ComposeView + WebView。 |
| **修复** | 已落地 —— catch 块先 `runCatching { windowManager.removeViewImmediate(root) }` + `owner.onDestroy()` 再清引用。 |

### L-04【P1-生命周期竞态】"取消 UI 收集 ≠ 中止引擎"：Stop 后 `isLoading` 提前复位 + 引擎后台复活

| 项目 | 内容 |
| :--- | :--- |
| **文件:行号** | `app/src/main/kotlin/com/apex/agent/ui/screen/agent/AgentChatViewModel.kt:377-378, 944-946` · `core/agent-engine/.../TaskRuntime.kt:173-207` |
| **问题代码** | ```kotlin // 只取消收集协程，引擎在 TaskRuntime 自有 IO scope 继续 currentJob?.cancel()   // TaskRuntime: engine.execute(input).collect { ... tap.send(event) }  // UNLIMITED Channel 无人消费也照单全收 ``` |
| **触发条件** | 任务 A 执行中直接再发消息 B（或 Stop 后立刻重发）→ A 的收集被取消但引擎 A 继续跑完并继续写 `conversationHistory`（跨轮串话）；B 被互斥拒绝弹错误卡但 User 气泡已插入 —— 用户以为 B 已发送，实际引擎从未收到。 |
| **后果** | 消息静默丢失 + UNLIMITED Channel 无界缓冲内存膨胀 + 引擎历史被"幽灵轮次"污染（后续请求可能因悬空 tool_calls 被 API 400 拒绝）。 |
| **修复建议** | `sendMessage`/`handleSlashCommand` 取消收集前调用 `taskController.cancel()` 等待 `streamDone`；`tap` Channel 改 `BufferOverflow.DROP_OLDEST` 有界缓冲；互斥拒绝时撤回已插入的 User 气泡。 |

### L-05【P1-ANR】每条消息发送都在 Main 线程做 JSON 序列化 + 文件写入 + `fd.sync()`

| 项目 | 内容 |
| :--- | :--- |
| **文件:行号** | `core/agent-engine/.../TaskRuntime.kt:171, 545-560` · `core/agent-engine/.../FileTaskStore.kt:48-72` |
| **问题代码** | ```kotlin // executeAsTask 运行在调用方线程 = Main.immediate persist(task, CheckpointBoundary.TASK_CREATED) // FileTaskStore: FileOutputStream(tmp).use { out ->     out.write(json.encodeToString(...).toByteArray())     out.fd.sync()   // 主线程 fsync } ``` |
| **触发条件** | 每次发送消息/斜杠指令/重试/恢复（`viewModelScope` 默认 `Dispatchers.Main.immediate`），`executeAsTask` 的"建任务+persist"段同步在主线程执行。 |
| **后果** | StrictMode 磁盘违规；eMMC/UFS 抖动时 fsync 几十~几百 ms，低端机连发消息直接卡顿乃至 ANR。 |
| **修复建议** | 把"建任务+persist"段下沉 `Dispatchers.IO`（`flowOn` 或首个事件前 await 就绪）。 |

### L-06【P1-内存泄漏】WebView destroy 后 `onPageFinished` 仍回调 `evaluateJavascript`

| 项目 | 内容 |
| :--- | :--- |
| **文件:行号** | `app/src/main/kotlin/com/apex/agent/browser/BrowserEngine.kt:160-172` |
| **问题代码** | ```kotlin override fun onPageFinished(view: WebView?, url: String?) {     url?.let { ... }                       // 这段有 tab.webView === view 保护     view?.evaluateJavascript(STEALTH_JS, null)   // ← 无任何保护！ } ``` |
| **触发条件** | `rebuildActiveWebView()`/`closeTab`/`onRenderProcessGone` 中 `webView.destroy()`，而已入队主线程消息队列的旧 WebView 回调在 destroy 之后继续派发。 |
| **后果** | 违反 WebView 契约（destroy 后 "No other methods may be called"）→ `IllegalStateException` 或 Chromium JNI NPE —— 典型"偶现、无法复现"线上崩溃源。 |
| **修复建议** | 第 171 行移入 `tab.webView === view` 守卫块内；维护 `Set<WebView> destroyed` 集合在回调入口判断。 |

### L-07【P1-Hang】`evaluateJavascript` 挂起无超时，RetryPolicy 的"WebView 无响应"保护是死代码

| 项目 | 内容 |
| :--- | :--- |
| **文件:行号** | `app/src/main/kotlin/com/apex/agent/browser/BrowserEngine.kt:733-741` · `browser/RetryPolicy.kt:22-33` |
| **问题代码** | ```kotlin private suspend fun evaluateBoolean(wv: WebView, js: String): Boolean =     suspendCancellableCoroutine { cont ->        // ← 无 withTimeout         wv.evaluateJavascript(js) { result -> cont.resume(result == "true") }     } // RetryPolicy 声明 WebViewNotRespondingException 可重试 —— 但全仓库从未 throw ``` |
| **触发条件** | 渲染进程假死 / 重定向瞬间 / destroy 后回调丢失 —— JS callback 永不到达。点击/输入/探针路径均无超时。 |
| **后果** | Agent 工具协程**永久挂起**（不是失败而是卡死），熔断器永不 OPEN，任务栏"运行中"永久假死。 |
| **修复建议** | `evaluateJson/evaluateBoolean` 包 `withTimeout(8_000)`，超时包装为 `WebViewNotRespondingException` 喂给重试器。 |

### L-08【P1-内存泄漏】脉冲 `ObjectAnimator(INFINITE)` 从不 cancel —— `clearAnimation()` 停不掉属性动画

| 项目 | 内容 |
| :--- | :--- |
| **文件:行号** | `app/src/main/kotlin/com/apex/agent/browser/CyberNeonBallManager.kt:150-158, 198-208`（修复前行号） |
| **问题代码** | ```kotlin ObjectAnimator.ofPropertyValuesHolder(pulseView, pX, pY, pA).apply {     duration = 1200     repeatCount = ObjectAnimator.INFINITE     // 引用未保留，无处 cancel } // else 分支只有 pulseRing.clearAnimation()  // 对属性动画无效！ ``` |
| **触发条件** | 状态进入 `NEED_HUMAN` 一次即启动；离开该状态 / `dismiss()` 后动画器仍在 `AnimationHandler` 无限运行。 |
| **后果** | 无限 ValueAnimator 强持有 `pulseRing` 及整颗球 View 树 → 球 dismiss 后整棵布局永久泄漏 + CPU/GPU 空转耗电。 |
| **修复** | 已落地 —— 保留 `pulseAnim` 引用，`applyState` 非脉冲分支与 `dismiss()` 统一 `cancelPulse()`。 |

### L-09~L-12【P2-竞态/兼容性组】

| # | 位置 | 问题 | 后果 |
| :--- | :--- | :--- | :--- |
| L-09 | `ToolkitRingButton.kt:374-391` | ActivityResult 回调主线程 `openInputStream` 读 content URI（对照 `SkillScreen.kt:186-198` 已修复同一问题） | 掉帧/ANR |
| L-10 | `TerminalViewModel.kt:175-189, 203-207` + `TerminalScreen.kt:234` | `observeScreenState()` 收集器无去重（每次调用新起 2 个永不取消的 collect）；"一键全装"期间单项安装仍可点击，两个 apt 并发共用同一 PTY 会话 | 收集器泄漏 / apt 输出交错 |
| L-11 | `core/agent-engine/.../UserQuestion.kt:78-109` | `submit()` 不校验 `answer.questionId`，超时/回答最后一刻竞态可跨问题注入答案 | Agent 拿错配答案继续执行破坏性操作 |
| L-12 | `core/agent-engine/.../ApexAgentEngine.kt:111, 1005` | `isRunning` 普通变量跨线程读写（无 `@Volatile`），Main 线程 `abort()` 写入对 IO 轮询循环无 happens-before 保证 | Stop 后多执行 1~N 个工具动作 |

---

## 三、第 2 类：数据一致性与边界值陷阱

### D-01【P0-Crash】Room v1→v2 迁移 schema 校验必炸：`NOT NULL DEFAULT ''` vs 可空字段

| 项目 | 内容 |
| :--- | :--- |
| **文件:行号** | `platform/cs-mem/.../store/MemoryGraphDatabase.kt:44` ↔ `store/entity/NodeEntity.kt:59-60` |
| **问题代码** | ```kotlin // MIGRATION_1_2（修复前） db.execSQL("ALTER TABLE nodes ADD COLUMN app_version TEXT NOT NULL DEFAULT ''") // NodeEntity 期望 schema：可空！ @ColumnInfo(name = "app_version") val appVersion: String? = null ``` |
| **触发条件（模拟输入 = 数据库 version 1 的老用户升级）** | 迁移执行后 Room 用 `TableInfo` 对比实体 schema → `notNull` 不匹配（迁移后 notNull=1，实体期望 notNull=0）→ 首次访问 DB 抛 `IllegalStateException: Migration didn't properly handle nodes(...)`。`di/CsMemModule.kt` 未注册 `fallbackToDestructiveMigration`，校验失败不会静默重建。 |
| **后果** | 升级用户长期记忆库**每次操作都失败**（被 `runCatching` 吞成"记忆系统整体静默死亡"，未包裹路径直接崩溃），无法自愈。 |
| **修复** | 已落地 —— 迁移 SQL 改为 `ADD COLUMN app_version TEXT`（可空列，与实体声明完全对齐）。 |

### D-02【P0-Crash + P1 数据损坏】MIGRATION_2_3 创建的索引未在实体声明：升级路径炸、全新安装去重失效

| 项目 | 内容 |
| :--- | :--- |
| **文件:行号** | `platform/cs-mem/.../store/MemoryGraphDatabase.kt:97-100` ↔ `store/entity/EdgeEntity.kt:33-38` |
| **问题代码** | ```kotlin // MIGRATION_2_3（修复前）：索引名 index_edges_episode_label，实体未声明 db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_edges_episode_label ON edges(episode_id, edge_label)") // EdgeEntity.indices 只有 episode_id / source / target / type 四个单列索引 ``` |
| **触发条件** | ① v2→v3 升级用户：迁移后校验发现实体未声明的索引 → `IllegalStateException: Migration didn't properly handle edges ... Unexpected index`；② 全新安装（更隐蔽）：Room 按实体建 v3 表，该唯一索引根本不创建 → 注释声称的 "upsert 幂等去重" 完全失效，`EdgeDao.upsertAll(REPLACE)` 每帧插入重复边。 |
| **后果** | 升级路径 P0-Crash（记忆库不可用）；全新安装 P1-数据损坏（边表无限膨胀）。 |
| **修复** | 已落地 —— 实体补 `Index(value = ["episode_id", "edge_label"], unique = true)`，迁移 SQL 索引名同步改为 Room 默认命名 `index_edges_episode_id_edge_label`。 |

### D-03【P1-路径穿越】附件沙箱拷贝用原始 DISPLAY_NAME 拼路径

| 项目 | 内容 |
| :--- | :--- |
| **文件:行号** | `app/src/main/kotlin/com/apex/agent/ui/screen/agent/AttachmentManager.kt:298-300` · `attachment/PredictiveAttachmentPreprocessor.kt:95-96` |
| **问题代码** | ```kotlin val targetDir = java.io.File(context.filesDir, "attachments") val targetFile = java.io.File(targetDir, "${System.currentTimeMillis()}_$fileName") // fileName 来自 OpenableColumns.DISPLAY_NAME，无任何清洗 ``` |
| **模拟输入** | 恶意/被劫持的 ContentProvider 返回 `DISPLAY_NAME = "x/../../databases/apex_memory.xml"` → 词法解析后落到 `filesDir/databases/apex_memory.xml` → **覆盖应用私有数据库/SharedPreferences**。 |
| **后果** | 私有文件写穿（数据损坏/记忆投毒放大器）；显示层输入含 `/` 时 `FileNotFoundException`。 |
| **修复建议** | 文件名清洗 + canonicalPath 前缀校验：`File(targetDir, "${ts}_${sanitize(fileName)}")` + `check(targetFile.canonicalPath.startsWith(targetDir.canonicalPath))`（对照 `MarketInstallManager` 已有正确实现）。 |

### D-04【P1-数据损坏】SdkDownloader 断点续传 `.tmp` 不绑定 URL → 跨源续传出静默损坏文件

| 项目 | 内容 |
| :--- | :--- |
| **文件:行号** | `app/src/main/kotlin/com/apex/agent/environment/SdkDownloader.kt:51-57` |
| **模拟输入** | destFile 同名但源切换（镜像/版本更新）+ 磁盘残留旧 `.tmp` → 服务器返回 206 → 旧文件前半段 + 新文件后半段拼接；未传 `expectedSha256` 时无任何校验。 |
| **后果** | 安装介质静默损坏，排障报错点与真实原因脱节（解压时才炸）。 |
| **修复建议** | `.tmp` 命名绑定下载会话（`"${name}.${url.hashCode()}.tmp"`），续传前校验源一致性。 |

### D-05【P1-数据损坏】SharedPreferences 会话记忆：无锁读-改-写 + 解析失败即整库清空

| 项目 | 内容 |
| :--- | :--- |
| **文件:行号** | `app/src/main/kotlin/com/apex/agent/di/SharedPrefsConversationMemory.kt:33-48` |
| **模拟输入** | 两个写者（ReAct 循环 append / TaskRuntime.repairDanglingInMemory save）交错：A load → B load → B save → A save → B 的消息永久丢失；schema 演进或脏数据 → `catch { clear() }` → 全部对话历史静默清零。丢失 ToolResult 还会留下悬空 tool_calls → 下一次请求被 OpenAI 兼容端 400 拒绝（正是 `DanglingToolCallRepair` 要修的问题，又被竞态造出来）。 |
| **修复建议** | 所有写操作收敛到同一 `Mutex` / `limitedParallelism(1)`；`catch` 时把坏串隔离到 `corrupt/` 键而非清空。 |

### D-06【P1→P2 数据中毒放大链】transitionsJson 解码失败 → 空转移表 → 坏宏"自动成功"并晶化

| 项目 | 内容 |
| :--- | :--- |
| **文件:行号** | `platform/cs-mem/.../store/MemoryGraphStoreImpl.kt:401-406` + `bypass/BypassExecutionEngine.kt:112-116, 158` |
| **模拟输入** | DB 中 `transitions_json` 被截断/跨版本字段变更（`FSMTransition` 四字段全无默认值）→ 解码失败 `catch { emptyList() }` → 空宏入库 → bypass 匹配到它 → 0 动作记 `success=true` → 能量提升 → 达到 `shouldCrystallize` 后被晶化 → **永久不可删除的假技能**（晶化后免疫剪枝）。 |
| **修复建议** | 解码失败丢弃该宏而非 `emptyList()`；`executeMacro` 对空转移表返回 `Failed`；`Json` 补 `coerceInputValues = true`。 |

### D-07~D-12【P2 组】

| # | 位置 | 模拟输入 → 崩溃/损坏路径 |
| :--- | :--- | :--- |
| D-07 | `MemoryWriterActor.kt:69-72, 118-123` | 并发 `start()`×2（check-then-act 非原子）→ 双消费者瓜分 Channel；`stop()` 后任何 `send` 抛 `ClosedSendChannelException` 且 `scope.cancel()` 后永久不可复活 |
| D-08 | `CsMemRecallTools.kt:44-48` + `NodeDao.kt:28` | LLM 传 `{"limit": -1}` → SQLite `LIMIT -1` 语义为无上限 → 全表返回撑爆上下文；`{"query":"100%_free"}` → LIKE 通配符未转义 → 记忆召回错乱。修复：`limit.coerceIn(1, 100)` + `ESCAPE '\\'` 转义 |
| D-09 | `CsMemSessionManager.kt:163-165` + `MemoryImmuneSystem.kt:61-63` | TOCTOU `previousGraph!!` → KNPE（被 runCatching 吞成丢帧）；非线程安全 `HashSet` 并发 add → 桶损坏死循环/`ArrayIndexOutOfBoundsException` |
| D-10 | `TaskRuntime.kt:506, 254` + `TraceDistiller.kt:237` | 输入 = 79 ASCII + 1 emoji → `.take(80)` 劈开代理对 → 孤立代理落盘 → 下游 LLM API 渲染 � 或 4xx。修复：codePoint 感知截断 |
| D-11 | `NodeDao.kt:22-23` + `MemoryGraphStoreImpl.kt:128-130` | 输入 = 空指纹列表 → Room 生成 `IN ()` → `SQLiteException: near ")": syntax error`（当前调用点恰好都有守卫，属潜伏契约缺陷） |
| D-12 | `GithubApiService.kt:276-283` | GitHub 返回的 issue 缺 `title` → `MissingFieldException` 使**整页**解码失败（一坏全坏）；`content.replace("\n","")` 漏 `\r` → 严格 Base64 解码抛 `IllegalArgumentException`。修复：响应字段补默认值 + `getMimeDecoder()` |

---

## 四、第 3 类：隐式依赖与版本兼容性

### C-01【P0-Crash】`android.permission.VIBRATE` 从未声明 → "人工接管"这一核心交互必崩

| 项目 | 内容 |
| :--- | :--- |
| **文件:行号** | `app/src/main/kotlin/com/apex/agent/browser/CyberNeonBallManager.kt:210-218`（修复前行号）· `app/src/main/AndroidManifest.xml`（权限区，修复前无 VIBRATE） |
| **问题代码** | ```kotlin private fun triggerVibration() {     val vibrator = appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return     if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {         vibrator.vibrate(VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE))     } ... } ``` |
| **触发条件** | 全部 5 个 AndroidManifest（含 EasyFloat AAR 清单）均未声明 `VIBRATE`（grep 零命中）。用户授予 SYSTEM_ALERT_WINDOW → 引擎进入 `WAITING_HUMAN`（Agent 请求人工接管）→ 主线程 `mainHandler.post { applyState(NEED_HUMAN) }` → `triggerVibration()` 抛 `SecurityException`。 |
| **后果** | 异常发生在 Main Looper 的 post runnable 内，未捕获 → 全局 crash handler 记日志后 re-throw → **进程死亡**。浮球正常显示时，每次"人工接管"（核心 UX）必崩。 |
| **修复** | 已落地 —— manifest 补 `<uses-permission android:name="android.permission.VIBRATE" />`；`triggerVibration` 包 `runCatching`（防勿扰模式等 ROM 限制）+ API 31+ 走 `VibratorManager.defaultVibrator` + `hasVibrator()` 检查。 |

### C-02【P1-兼容性/功能阻断】内置 LLM Provider 默认 `http://localhost`，targetSdk 28+ 明文流量默认封锁

| 项目 | 内容 |
| :--- | :--- |
| **文件:行号** | `core/llm-adapter/.../ModelProfile.kt:232-236` + `app/src/main/AndroidManifest.xml`（无 `networkSecurityConfig`） |
| **问题代码** | ```kotlin provider("ollama", "Ollama", "http://localhost:11434/v1", ...), provider("lmstudio", "LM Studio", "http://localhost:1234/v1", ...), provider("vllm", "vLLM", "http://localhost:8000/v1", ...), ``` |
| **触发条件** | minSdk 26 范围内所有设备，用户选 Ollama/LM Studio/vLLM 本地推理或任意 `http://` 自定义端点。Android 9+ `NetworkSecurityPolicy` 默认禁止明文。 |
| **后果** | `UnknownServiceException: CLEARTEXT communication not permitted` → 聊天功能完全不可用（被上层 catch 吞掉时表现为"静默无响应"）。 |
| **修复建议** | 新增 `res/xml/network_security_config.xml`，仅对 `localhost`/`127.0.0.1`/`10.0.2.2` 放行 cleartext，外部域名保持 HTTPS。 |

### C-03【P1】后台路径启动前台服务：Android 12+ 强制限制，保活链路静默失效

| 项目 | 内容 |
| :--- | :--- |
| **文件:行号** | `platform/persistence/.../PersistenceEngine.kt:146-157`（WatchdogWorker 每 15 分钟） |
| **问题代码** | ```kotlin if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {     applicationContext.startForegroundService(intent)   // API 31+ 后台启动 FGS 不在豁免清单 } ... catch (_: Exception) {}   // ForegroundServiceStartNotAllowedException 被静默吞掉 ``` |
| **触发条件** | Android 12+ 设备，应用在后台、主进程被杀后 Worker 唤醒。Worker 与 AccessibilityService 回调都不在 FGS 后台启动豁免清单（仅 `BOOT_COMPLETED`/`MY_PACKAGE_REPLACED` 豁免 —— `BootReceiver` 路径反而是对的）。 |
| **后果** | 不崩，但"Layer 1 前台服务保活"在 12+ 上**每次都失败且无日志** —— 最危险的静默降级：保活指标看起来正常，实际从未生效。 |
| **修复建议** | 改用 `setExpedited` 的 CoroutineWorker；`catch` 内至少上报警 metric。 |

### C-04【P1-Security】security-crypto 1.1.0-alpha06（已废弃 alpha）+ Keystore 失败静默降级明文存储

| 项目 | 内容 |
| :--- | :--- |
| **文件:行号** | `app/src/main/kotlin/com/apex/agent/github/GithubTokenManager.kt:23-36` · `gradle/libs.versions.toml:18` |
| **问题代码** | ```kotlin try {     val masterKey = MasterKey.Builder(context).setKeyScheme(AES256_GCM).build()     EncryptedSharedPreferences.create(...) } catch (e: Exception) {     context.getSharedPreferences("github_prefs_fallback", Context.MODE_PRIVATE)  // 明文兜底！ } ``` |
| **触发条件** | Keystore 损坏设备（恢复出厂/备份还原/锁屏凭据变更，低端机与企业 MDM 机型实测概率不低）。security-crypto 已官方废弃于 alpha06，不再维护。 |
| **后果** | GitHub PAT 明文落盘，且 `allowBackup="true"` 意味着明文 token 还会进云备份 —— 安全失败变成安全降级。 |
| **修复建议** | 失败时禁用 GitHub 集成而非降级明文；中期迁移 DataStore + Tink。 |

### C-05【P1-兼容性】`POST_NOTIFICATIONS` 只声明不请求：Android 13+ 前台服务通知全隐藏

| 项目 | 内容 |
| :--- | :--- |
| **文件:行号** | `app/src/main/AndroidManifest.xml:8` · `ui/screen/permissions/PermissionsScreen.kt:193-205` |
| **触发条件** | Android 13+（targetSdk 35 下为运行时权限）；全仓库运行时请求 0 处（只有跳系统设置页）。 |
| **后果** | `ApexCoreService` 常驻通知与下载完成通知全部不显示；用户对"AI 正在跑"零感知。 |
| **修复建议** | PermissionsScreen 通知卡片改为 `rememberLauncherForActivityResult(RequestPermission())` 标准运行时请求。 |

### C-06~C-10【P2 组】

| # | 位置 | 问题 |
| :--- | :--- | :--- |
| C-06 | `EasyFloat 2.0.4`（AAR 字节码级核验） | AAR 清单 targetSdk 29、2021 年停更；`PermissionUtils` 按 ROM 反射（Miui/Huawei/Oppo/Meizu）新 ROM 失败误判；无 `layoutInDisplayCutoutMode` → 刘海屏遮挡。建议以 `BrowserOverlay.buildLayoutParams()` 为蓝本自研替换，消除 JitPack 单点 |
| C-07 | `app/build.gradle.kts:112-115` + `ci.yml:220-237` | `lockAllConfigurations()` 但 lockfile 从不入库、CI 每次 `--write-locks` 重写 —— 依赖锁定是"仪式性的"，零复现性保障。修复：提交 `app/gradle.lockfile` 并去掉 `--write-locks` |
| C-08 | `app/build.gradle.kts:99-100` | Shizuku 硬编码 `13.1.0` 与版本目录 `13.1.5` 分裂 —— 按 Gradle 最高版本解析，IDE 对齐与运行时版本漂移。修复：改用 `libs.shizuku.api` |
| C-09 | `ui/screen/agent/AttachmentManager.kt:199` 等 | `rememberLauncherForActivityResult(GetContent)` 返回的临时 URI 未 `takePersistableUriPermission`，重启后缩略图 URI 失效 |
| C-10 | `AttachmentCleanupManager.kt:43` | 用墙钟 `System.currentTimeMillis` 做清理 cutoff —— 用户改时钟导致误删/漏删，建议 mtime/`elapsedRealtime` |

**✅ 兼容性核查通过的项**：`PendingIntent` 已用 `FLAG_IMMUTABLE`；FGS 已声明 `specialUse` + subtype（Android 14 合规）；`BootReceiver` 未后台启动 Activity；全仓库无 `registerReceiver`/`RuntimeShader`/`RenderScript` 风险调用；`RootfsExtractor` zipSlip 防御完整；WebView 安全四项设置正确；OkHttp 4.12.0 无自定义 TrustManager。

---

## 五、必现崩溃测试用例（复现 C-01，P0）

**用例名**：`verify-neon-ball-vibrate-crash` —— 验证"人工接管震动"在权限缺失时的必现 SecurityException

### 方式 A：ADB 命令行复现（无需改代码即可验证修复前行为）

```bash
# 0. 前置：设备 API 26+，已安装 release/debug 包
adb install -r app-debug.apk

# 1. 授予悬浮窗权限（浮球显示的前提）
adb shell appops set com.apex.agent SYSTEM_ALERT_WINDOW allow

# 2. 主动撤销 VIBRATE 权限，模拟"未在 manifest 声明"的运行时状态
#    （修复前：manifest 未声明 → 系统按未授权处理）
adb shell pm revoke com.apex.agent android.permission.VIBRATE

# 3. 启动浏览器 Agent 并驱动引擎进入 WAITING_HUMAN（人工接管）：
#    任意触发 BrowserShow / 网页权限申请路径即可，例如通过 Agent 对话注入：
#    "打开 https://example.com 并调用 BrowserShow"
adb shell am start -n com.apex.agent/.MainActivity
# （在 UI 中发送上述指令，等待霓虹球变琥珀金 NEED_HUMAN 状态）

# 4. 观察崩溃：
adb logcat -s AndroidRuntime:E | grep -m1 "SecurityException"
# 修复前预期输出：
#   java.lang.SecurityException: Requires android.permission.VIBRATE permission
#     at android.os.Vibrator.vibrate(Vibrator.java:XXX)
#     at com.apex.agent.browser.CyberNeonBallManager.triggerVibration(...)
```

### 方式 B：UI 自动化步骤（Espresso / 手工 60 秒复现）

1. 冷启动 App → 设置页授予"显示悬浮窗"；
2. 对 Agent 发送 `浏览 https://example.com 并展示悬浮接管面板`；
3. 等待霓虹球出现并切换到**琥珀金脉冲态**（NEED_HUMAN）；
4. **修复前**：步骤 3 的瞬间 App 必崩（`SecurityException` 发生在主线程 post runnable，无法被业务层 catch）—— 崩溃对话框弹出，`logcat` 可见第 4 步堆栈；**修复后**：球体脉冲 + 震动正常，或震动被静默跳过，进程存活。

> **混沌验证注记**：该 P0 的隐蔽性在于 —— 权限是**编译期零报错、Lint 不拦截（VIBRATE 属 normal 级权限，未声明不告警）、首次安装后只要不触发 NEED_HUMAN 就永远不崩**。只有把"Agent 请求人工接管"这一主路径压上去才会 100% 复现，是典型的隐式依赖型必现崩溃。

---

## 六、已落地修复清单（本 PR diff）

| # | 文件 | 修复内容 | 对应发现 |
| :--- | :--- | :--- | :--- |
| 1 | `app/src/main/AndroidManifest.xml` | 补 `<uses-permission android:name="android.permission.VIBRATE" />` | C-01 |
| 2 | `browser/CyberNeonBallManager.kt` | `triggerVibration` 包 `runCatching` + API 31 `VibratorManager` 分支 + `hasVibrator()` 检查；脉冲 `ObjectAnimator` 保留引用并 `cancel`（修复 INFINITE 动画泄漏）；`dismiss()` 同步取消动画 | C-01 / L-08 |
| 3 | `browser/BrowserEngine.kt` | `screenshot` 增加 WebView 视口 0×0 守卫，后台模式优雅返回 null | L-02 |
| 4 | `browser/BrowserOverlay.kt` | `doShow` catch 块补 `removeViewImmediate` + `owner.onDestroy()`，消除僵尸浮窗 | L-03 |
| 5 | `ui/screen/agent/AgentChatViewModel.kt` | `handleSlashCommand` 补 try/catch（与 `runEngine` 模式一致），消除互斥 check 闪退 | L-01 |
| 6 | `platform/cs-mem/.../MemoryGraphDatabase.kt` | MIGRATION_1_2 列改可空对齐实体；MIGRATION_2_3 索引名对齐 Room 默认命名 | D-01 / D-02 |
| 7 | `platform/cs-mem/.../EdgeEntity.kt` | 补 `(episode_id, edge_label)` 唯一索引声明 | D-02 |
| 8 | `docs/chaos-crash-audit.md` | 本报告 | — |

**未落地但建议排期的 Top 5**（按风险收益比）：C-02 networkSecurityConfig（一行 XML 消除本地 LLM 全阻断）→ L-04 取消语义对齐（消息静默丢失）→ D-03 附件路径穿越清洗（安全向）→ D-05 SharedPrefs 单写者化（悬空 tool_call 制造机）→ C-05 POST_NOTIFICATIONS 运行时请求。

---

## 七、表现良好的点（混沌注入无发现）

- 全仓库 UI 收集**全部**使用 `collectAsStateWithLifecycle()`，无裸 `collectAsState()` / `observeForever` / `GlobalScope` 滥用；
- `FileTaskStore` 的 tmp + fsync + rename 原子写与 corrupt/ 隔离区设计；
- `RootfsExtractor` 完整的 zipSlip / canonical path 防御；
- `SlashCommandParser` 的 sh 词法解析器（引号/转义处理严谨）；
- `MarketInstallManager` 的 2MB 读取上限与 canonicalPath 检查；
- `GithubApiService` 的重试 / Retry-After 封顶 / 204 空体处理；
- `PendingIntent` FLAG_IMMUTABLE、FGS `specialUse` 声明、BootReceiver 合规 —— Android 12+ 三大雷区均已规避。
