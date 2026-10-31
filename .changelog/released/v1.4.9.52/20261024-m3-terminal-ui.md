# M³ 现代终端（Material 3 Terminal UI）+ main 编译断裂修复（browser-kit 锁回退）

- **M³ 现代终端页**（新模块 `feature/terminal-m3`，自包含 Compose 库）：Material 3
  终端 REPL —— 28dp 全圆角 OutlinedTextField（prefix "$ " / FilledIconButton·Send /
  IME Send 回车提交 / placeholder「输入命令…」）+ 24dp 圆角终端卡片
  （surfaceContainerHigh 面 + 1dp 描边）；**单源输入同步**——输入框与终端卡片
  「当前输入行」共用 ViewModel 同一 `mutableStateOf` 状态（onValueChange →
  onInputChange 落回，终端末行 "$ + 文本 + 闪烁光标 ▌" 同帧刷新，零双状态零
  回调同步）；命令 token 级**代码高亮**（prompt 绿 / 命令名亮白加粗 / 旗标与
  引号串橙 / 参数弱灰）+ 行类型着色（✓ 成功绿 / 错误红 / 执行中灰斜体）；
  GitHub Dark 恒暗配色（#0D1117/#161B22/#E6EDF3/#8B949E/#30363D/#7EE787/
  #FF7B72）+ 全站等宽字体 13sp/20sp；内置 help / clear·cls / echo / date /
  未知命令 500ms 模拟执行（PENDING 行原位替换为 SUCCESS，键稳定不跳滚）；
  空输入与执行中双守卫；LazyColumn 新行自动滚底；SelectionContainer 长按复制。
  入口：抽屉「终端 · M³」（DrawerDestination.TerminalM3），与 PRoot Ubuntu
  沙箱终端并存 —— 重型终端与轻量命令台两种工作流各取所需。
- **main 编译断裂修复**：`platform:browser-agent` 的 KSP 因 `BookmarkStore` /
  `DownloadCenter` / `EngineDisplayController`（apex-kit-browser v1.7.0 会话
  增强组件）无法解析而全红 —— 根因是 sync 工作流把 `gradle/browser-kit.lock`
  bump 到更名后的 kit main HEAD（f28647b1），而 v1.7.0 分支（365bc0e6）当时
  未合入 kit main。kit 仓 PR #20/#29 已把 v1.7.0 会话增强列车合回 kit main
  （94ef6e40，含 Consumer Check 绿灯），sync 工作流已把锁 bump 至该 SHA（7ee75fa7），本 PR 合并与之对齐 —— 坐标
  com.apex.browser 三层未随更名变动，编译即恢复，且与 sync 工作流的后续
  bump 自然衔接（同为 kit main HEAD，无再踩风险）。
- **main 质量门禁红灯修复（记忆库拆分善后）**：a58dc7ff 拆库提交使测试纪律
  棘轮超基线（runBlocking 698 > 696，issue #276）—— `KnowledgeGraphStoreTest`
  8 处 runBlocking 转 runTest（MemKit.create 仅同步 store.load()、写管道须
  显式 start() 才启动，与 apex-memory-kit 库内 MemKitFacadeTest 同款 runTest
  口径），存量降至 690 ≤ 基线 696，绿灯。本 PR 合入即恢复 main 的 CI /
  Quality Gate / Release 三线全绿。
