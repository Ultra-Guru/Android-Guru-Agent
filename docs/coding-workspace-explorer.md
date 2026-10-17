# Coding 模式工作区浏览器（apex-workspace-kit 拆库）

> 用户规格收口（v1.6）：「coding 模式右上角增加一个文件的图标……选择工作
> 文件夹这个目录里的所有文件目录，点击具体文件也可以预览支持的格式要多，
> 增加一个实时功能按钮，能实时跟随 agent 的文件操作……这个功能是一个全新
> 的页面，需要点击叉号返回 coding……页面上方有长方形圆角切换栏：文件夹 /
> Git / 变更和产物（明细标注文件类型 + 变更行数）」。
> 库模块按用户决策拆独立仓库维护：[Ultra-Guru/apex-workspace-kit](https://github.com/Ultra-Guru/apex-workspace-kit)
>（composite build 消费，同 apex-browser-kit 拆库模式）。

## 1. 入口与导航

- Coding 屏 `WorkspaceBar` 右上角新增文件图标钮（`Icons.Outlined.FolderOpen`，
  48dp 触区，位于思考逻辑切换与 Git 面板入口之间）；
- 点击压栈进入全屏页 `DrawerDestination.WorkspaceBrowser`（**不进抽屉列表**
  —— 编码场景专属页；`NavigationBackStack` push/pop 语义，状态经
  SaveableStateProvider 隔离）；
- 页面内右上角叉号 = `onClose = navigateBack`（统一返回链，系统返回键同链路）；
- 页面自持头部（胶囊标签栏 + 跟随开关 + 叉号）——`ApexRoot` 的全局 TopAppBar
  与 ContextMeterBar 对该页排除（终端页同款先例）。

## 2. 页面结构（库仓库 workspace-explorer 模块）

顶部圆角胶囊切换栏三标签 + 实时跟随开关：

| 标签 | 内容 |
|------|------|
| 文件夹 | 工作区文件树（目录优先、展开/折叠、过滤搜索、git 变更状态点）+ 多格式文件预览；宽屏双栏 / 窄屏单栏 |
| Git | 分支 + ahead/behind、暂存/未暂存/未跟踪分组（行内 stage/unstage）、提交框（`-c user.name/email --no-verify` 身份注入，与 GitCommitTool 同源）、单文件 diff 面板（双行号 + 增删着色）、历史与分支、非仓库一键 init |
| 变更和产物 | 统计三卡（文件数/+增/-删）、变更文件行（类型徽标 + 状态 + **精确增删行数**，untracked 读磁盘计数、二进制显示 ±?）+ 内联 diff 懒加载、构建产物按目录分组（dist / build outputs / 散装 apk-jar-aar 等，深度 1-2 扫描）点击跳预览 |

**多格式预览**：文本/代码（行号 + 截断提示）、Markdown（轻量渲染/源码切换）、
JSON（美化/原始切换）、CSV、图片（coil + 双指缩放 1-5x）、SVG（SvgDecoder +
失败回退源码）、PDF（PdfRenderer 首页）、音频/视频（信息卡）、压缩包
（zip/apk/jar 条目清单 ≤200）、二进制（hex dump 前 256B）——约 110 个扩展名
+ 特殊文件名（Dockerfile/Makefile/.env/.gitignore/README 等）的类型表。

**实时跟随**（跟随开关开启时）：
- 轮询 watcher（2.5s 指纹快照 diff + 250ms 去抖合并，纯 JVM 无 inotify 依赖
  —— SkillDirectoryWatcher 先例）；
- agent 工具事件直推（`WorkspaceKitHolder.onAgentEvent` 挂在 CodeViewModel
  runEngine collect 循环）：code_write/write_file→CREATED、
  code_edit/edit_file→MODIFIED、delete_file→DELETED、copy_move_file→RENAMED、
  code_git_commit/code_git_branch→GIT_OP、terminal.exec/shell_execute→COMMAND
  （全量刷新兜底）；callId 幂等去重；
- 联动：文件树自动重扫、当前预览文件被改自动刷新、Git/变更标签标脏
  （当前可见则 300ms 去抖刷新，否则切回时刷新）、事件流悬浮窗（最近 6 条，
  agent 来源小圆点标记）。

## 3. 拆库架构（E1 迁移期，同 apex-browser-kit 模式）

```
Ultra-Guru/apex-workspace-kit（新仓库，MIT）
├── workspace-core      纯 Kotlin JVM，零 Android 依赖
│   ├── contract/       GitCommandRunner / WorkspaceRootProvider / KitLogger /
│   │                   CommitIdentity / WorkspacePaths（三级防线路径安全）
│   ├── git/            五解析器（porcelain=v1 -b / numstat -M / unified diff /
│   │                   log %x1f%x1e / for-each-ref）+ GitEngine（diffFor 按
│   │                   staged/unstaged/untracked 分路、pathspec 清洗）
│   ├── fs/             FileKindResolver（~110 扩展名）/ FileTreeScanner /
│   │                   ArtifactScanner / PreviewLoader
│   ├── watch/          轮询指纹 WorkspaceWatcher
│   └── WorkspaceExplorerController（StateFlow<ExplorerUiState> 单状态机）
└── workspace-explorer  Compose 库（compileSdk 35 / minSdk 26 /
                        resourcePrefix workspace_ / coil 2.6.0 同宿主）
```

**宿主消费**（本仓库改动，全部薄接线）：
- `settings.gradle.kts`：兄弟/嵌套目录条件 `includeBuild`（browser-kit 同构）；
- `gradle/libs.versions.toml`：`com.apex.workspace:{core,explorer}` 0.1.0 坐标
  （composite 命中时被本地构建替换）；
- `app/build.gradle.kts`：两个 implementation；
- `WorkspaceKitHolder.kt`（101 行）：git 执行器（PRoot 沙箱 runner 逐字段映射
  —— 恒定 bind 激活工作区为 guest /workspace，零新 PRoot 代码）+ 工作区根
  （CodeWorkspaceRoots.activeRoot 直通）+ AppLogger 转发 + 引擎事件映射；
- `WorkspaceBrowserHost.kt`（28 行）：hiltViewModel 同实例 → 库页面组合；
- `CodeViewModel` +12 行（lazy holder + collect 循环一行转发）；
  `CodeScreen` +17 行（图标钮与参数透传）；`ApexRoot` +10 行（destination/
  排除/分支）；`NavigationBackStack` +1 行（route 映射）；双语字符串 2 键。

**CI 接线**：`gradle/workspace-kit.lock`（pin 提交 SHA）+ ci.yml / apk.yml /
release.yml 三流水线的 pinned checkout 步骤（browser-kit 同款，嵌套路径命中
settings 兜底分支）。库仓库自带 CI：build-test（纯度门禁：core 零 Android
import；版本列车：两模块同号；117+15 测试；Maven Local 发布冒烟）+
consumer-check（宿主 composite 编译防崩 —— 本 PR 合入前自动跳过，合入后转
强校验）。

## 4. 验证

- 库仓库：`:workspace-core:test` 117 项全绿（解析器逐行为断言 / fs /
  watcher 虚拟时间 / controller 脚本回放 FakeGitRunner / **真 git 集成测试**
  —— TemporaryFolder 建仓提交改删全链路 + 隔离全局配置的身份注入验证）；
  `:workspace-explorer:testDebugUnitTest` 15 项；`publishToMavenLocal` 冒烟
  （jar/aar/pom 产物形状）；
- 宿主（本 PR）：`:app:compileDebugKotlin` 全绿（composite 替换生效 —— 构建日志
  含 :apex-workspace-kit:* 任务）；结构四门禁（文件预算 1147/1180/400 ≤1200、
  括号平衡、注释完整性、workflow YAML）全过；
- 行为对齐：git 命令口径与 CodeGitPanelController / GitCommitTool 逐项一致
  （porcelain=v1 -b、rename 取新路径、`-c` 身份注入、--no-verify、无 HEAD 降级），
  避免两套 git 语义漂移。

## 5. 兼容性

- 旧 `CodeWorkspacePanel`（v6 Git 底部面板）零改动保留 —— 新页面是全新入口，
  两者共享同一 PRoot GitCommandRunner 单例与数据真相；
- 无工作区/非仓库/空仓库/大文件/路径越界全防御态（引导卡 + Failure 预览态，
  绝不向上抛）；
- AGP 8.13.2 两侧严格一致（composite 硬约束）；库 Kotlin 2.2.21 / BOM
  2025.11.01 ≤ 宿主（F3 规则）。
