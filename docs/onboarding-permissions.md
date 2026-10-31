# Onboarding v2 — 权限全量引导 × 工作区选择

> 分支 `feat/onboarding-permissions`。新手引导从「四页 + 仅通知权限」升级为
> 「五页 + 八步权限引导 + 工作区选择」：首次下载/安装的用户在引导页即可
> 按需完成全部关键授权（每一步都可跳过），并把 Agent 的文件操作范围收敛
> 到指定文件夹（默认操控所有）。所有权限的判态/跳转实现逐项核对过
> developer.android.com 官方文档（依据矩阵见 §2）。

## 0. 一句话结论

引导第 3 页 = 一张可滚动的八步授权卡片列表（存储 / 所有文件访问 / 修改
系统设置 / 安装未知来源 / 忽略电池优化 / 悬浮窗 / 通知 / 无障碍进阶），
第 4 页 = 工作区范围二选一（操控所有【默认】 vs SAF 选定文件夹），
全部步骤零门控可跳过；完成标记版本化（`onboardingVersion`），升级用户
重看一次以获知新能力。

## 1. 页面结构（v1 → v2）

| 页 | v1（四页） | v2（五页） |
|----|-----------|-----------|
| 1 | 欢迎（Viro 吉祥物） | 不变 |
| 2 | 核心能力三卡 | 不变 |
| 3 | 权限：仅通知运行时权限一卡 | **八步授权列表**（OnboardingPermissionsPage） |
| 4 | 就绪（模型配置提示） | **工作区选择**（OnboardingWorkspacePage，引导序号 8） |
| 5 | — | 就绪（新增工作区选择摘要回显） |

交互约定（对齐官方 requesting-permissions 的「不阻断用户」原则）：

- 顶栏「跳过」仍直达收束页；底部「下一步」永不因未授权而禁用；
- 每张权限卡：未授权「去授权」→ 授权后自动回填「✓ 已授权」并停用按钮；
- 当前系统版本不适用的步骤显示「无需」且不计入顶部进度分母
  （如 API 33+ 的传统存储权限、API 30 以下的所有文件访问）；
- 从系统设置页返回（ON_RESUME）自动重检 —— 与抽屉权限页
  PermissionsScreen 的双重刷新模式同源。

## 2. 八步权限的官方依据矩阵

实现前逐项抓取核对了 developer.android.com 对应页面（KDoc 里也留了摘要）。

| # | 步骤 | 判态 API | 授权入口 | 官方文档 |
|---|------|---------|---------|---------|
| 1 | 存储（READ/WRITE_EXTERNAL_STORAGE，API < 33） | `Context.checkSelfPermission` | 运行时弹窗（RequestMultiplePermissions） | training/permissions/requesting |
| 2 | 所有文件访问（MANAGE_EXTERNAL_STORAGE，API 30+） | `Environment.isExternalStorageManager()` | `ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` + package Uri | training/data-storage/manage-all-files |
| 3 | 修改系统设置（WRITE_SETTINGS） | `Settings.System.canWrite()` | `ACTION_MANAGE_WRITE_SETTINGS` + package Uri | reference/android/provider/Settings |
| 4 | 安装未知来源（REQUEST_INSTALL_PACKAGES，API 26+） | `PackageManager.canRequestPackageInstalls()` | `ACTION_MANAGE_UNKNOWN_APP_SOURCES` + package Uri | reference（Android 8+ 按应用授权） |
| 5 | 忽略电池优化 | `PowerManager.isIgnoringBatteryOptimizations()` | `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` + package Uri | training/monitoring-device-state/doze-standby |
| 6 | 悬浮窗（SYSTEM_ALERT_WINDOW） | `Settings.canDrawOverlays()` | API 30+ 字面值 `android.settings.action.MANAGE_APP_OVERLAY_SETTINGS` + package Uri；以下 `ACTION_MANAGE_OVERLAY_PERMISSION` | reference/android/provider/Settings |
| 7 | 通知（POST_NOTIFICATIONS） | `NotificationManagerCompat.areNotificationsEnabled()` | API 33+ 运行时弹窗，否则 `ACTION_APP_NOTIFICATION_SETTINGS` | develop/ui/views/notifications/notification-permission |
| 进阶 | 无障碍（不占序号，可选） | AccessibilityManager 遍历已启用服务 | `ACTION_ACCESSIBILITY_SETTINGS` | 与抽屉权限页同款 |
| 8 | 工作区（独立成页） | 用户选择（无系统权限） | `ACTION_OPEN_DOCUMENT_TREE`（SAF） | guide/topics/providers/document-provider |

关键取舍（坑位记录）：

- **特殊权限不可弹窗**：2/3/4/5/6 全部只能跳系统设置页（官方 Special app
  access 语义），引导页负责解释为什么 + ON_RESUME 回填状态；
- **targetSdk 28 下的通知**：Android 13+ 对 targetSdk<33 的应用默认授予通知，
  但用户可关 —— `areNotificationsEnabled()` 统一覆盖两种形态，运行时弹窗
  仍显式发起（v1 既有正确做法，保留）；
- **电池优化与 MainActivity 一次性弹窗撞车**：v1.4.4 #4 的
  `maybeRequestBatteryOptimizationExemption()` 会在首启直接弹系统豁免
  对话框（盖在引导页上）。现在引导未完成时压制该一次性弹窗（引导第 5
  步负责），完成引导但跳过该步的用户由老逻辑下次冷启动兜底补问；
- **Play 政策注记**：官方文档明确 Google Play 限制 MANAGE_EXTERNAL_STORAGE
  与直接请求电池豁免的使用场景 —— 本应用为侧载发布（targetSdk 28 红线），
  不经 Play 分发，不受该政策约束；文档仍按官方推荐顺序排列步骤
  （普通运行时权限 → 特殊权限 → 通知）；
- **SAF 持久化授权**：选定文件夹后立即
  `takePersistableUriPermission(uri, READ or WRITE)` —— chaos-crash-audit
  C-09 的教训（附件 content URI 未持久化，重启即失效）不允许重演。

## 3. 工作区选择（引导第 8 步）

两个互斥选项，选择即时持久化（引导中途被杀进程也不丢）：

- **操控所有文件（默认）**：`workspaceScope = "all"`，依赖步骤 2 的
  所有文件访问权限；
- **指定工作区文件夹**：`workspaceScope = "folder"`，SAF 系统选择器选目录
  （`ActivityResultContracts.OpenDocumentTree`），持久化授权 tree URI 存
  `workspaceFolderUri`，显示名从 `DocumentsContract.getTreeDocumentId`
  解析（documentId 形如 `primary:Download/Foo`，取路径末段；framework
  API，无需引入 documentfile 依赖）存 `workspaceFolderName`。

跳过 = 维持默认「操控所有」；就绪页回显当前选择。

**Phase 2（后续 issue，本 PR 不含）**：引擎侧接线 —— 文件工具按
`workspaceScope` 收敛沙箱（folder 模式经 DocumentFile 桥读写 tree URI；
all 模式维持现状），并把选择注入 Agent 系统提示词的 Live Environment 段。
本 PR 先把选择记录为持久化配置 + 就绪页回显，避免一次性改动工具沙箱
执行链（RiskAwareToolGate / code_* 工具 / 终端 bind mount 三处语义）。

## 4. 完成标记版本化

`AgentSettings` 新增 `onboardingVersion: Int = 0`：

- 门控（MainActivity）：`onboardingCompleted && onboardingVersion >= 2`
  才进主界面 —— v1 完成老用户（version 0）升级后重看一次新引导，与
  v1「老用户重看一次」的布道语义保持版本化延续；
- 完成回调同时写 `onboardingCompleted = true` + `onboardingVersion = 2`；
- 存量 JSON 向后兼容：`ignoreUnknownKeys` + 缺省 0，无需迁移。

## 5. 代码结构

```
app/src/main/kotlin/com/apex/agent/ui/screen/onboarding/
├── OnboardingScreen.kt            五页 Pager 编排 + OnboardingFlow.CURRENT_VERSION（520→365 行，拆分后更小）
├── OnboardingPermissionSteps.kt   [新] 八步模型 + 判态 + 设置页 Intent 构建（纯 Kotlin，零 Compose）
├── OnboardingPermissionsPage.kt   [新] 授权步骤列表页（卡片 + 进度徽标 + ON_RESUME 回填）
└── OnboardingWorkspacePage.kt     [新] 工作区选择页（SAF + takePersistableUriPermission）
```

- 判态/Intent 收敛在 `OnboardingPermissionSteps.kt` 一个文件，与
  PermissionsScreen 的既有 API 用法同源（`MANAGE_APP_OVERLAY_SETTINGS`
  字面值等惯例一致）；
- 卡片骨架复用抽屉权限页 PermissionCard 形态（44dp 圆形图标 chip +
  StatusPill + 12dp 卡片半径），叠加引导序号徽章与「无需/进阶」态；
- Manifest 新增 `WRITE_SETTINGS` + `REQUEST_INSTALL_PACKAGES` 声明
  （均带用途注释；后者同时解锁 UpdateDownloader 的应用内自更新安装流）；
- 字符串 `onboarding_*` 新增 34 键，values 与 values-zh 严格 1:1 镜像，
  清理 5 个 v1 死键（onboarding_perm_title/desc/optional/request/retry）。

## 6. 验证

| 项 | 结果 |
|----|------|
| `scripts/check_file_size.sh` | ✅ 新文件最大 395 行（预算 1200） |
| `scripts/check_code_quality.sh` | ✅ 无反射派发 / 无 printStackTrace |
| `python3 scripts/kotlin_balance.py <改动文件>` | ✅ 括号平衡 |
| `:app:compileDebugKotlin` | CI（pr 触发 app-compile job） |
| `:app:testDebugUnitTest` | CI 同上 |

真机回归建议（CI 无法覆盖的路径）：

1. Android 13+ 真机：第 7 步通知弹窗 → 拒绝 → 再点 → 状态回填；
2. Android 11-12：步骤 1+2 逐项授权，从设置页返回进度徽标即时 +1；
3. Android 10 及以下：步骤 2 显示「无需」，步骤 1 走运行时弹窗；
4. 工作区 SAF 选择后杀进程重启 → 就绪页仍回显所选文件夹（持久化授权生效）；
5. 老版本升级（onboardingCompleted=true）→ 首启重看 v2 引导一次。
