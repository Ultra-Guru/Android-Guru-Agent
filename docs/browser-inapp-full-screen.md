# 内嵌浏览器（App 内全功能浏览器 / 三承载面互斥）

> 版本：v1.5.1（2026-10）；关联库 PR：apex-kit-browser#18（v1.5.0，
> `InAppChromeHost` + `InAppHostingArbiter`）；消费提交：本仓库
> `feat/browser-inapp-full-browser`。

## 背景：用户问「内置浏览器到底有没有」

v1.5.0 时浏览器能力其实完整（`BrowserEngine` 无头自动化 + `BrowserChrome`
完整外观 + 24 个 `browser_*` 工具），但**所有用户可见面都是系统悬浮窗**
（`TYPE_APPLICATION_OVERLAY`）：

| 可见面 | 依赖 | 无权限时 |
|--------|------|----------|
| 全屏接管浮窗 `BrowserOverlay` | SYSTEM_ALERT_WINDOW | 静默不出现 |
| 实时页面卡片 `LiveViewOverlay` | SYSTEM_ALERT_WINDOW | 静默不出现 |
| 悬浮球 `CyberNeonBallManager` | SYSTEM_ALERT_WINDOW（EasyFloat） | 静默不出现 |

`SYSTEM_ALERT_WINDOW` 是需到系统设置页授予的特殊权限——未授予时三处全部
**静默降级**，用户在 App 里什么都看不见：抽屉 → 浏览器页只有一屏状态卡
（旧 `BrowserScreen` 是观测仪表盘，没有可用的浏览器界面）。这就是
「内置浏览器到底有没有」的直接根源。另有同根源 P1：接管浮窗的 chrome 根
Column 恒刷不透明底色，把组合树外的 WebView 整个盖住——即使授了权限，
人工接管浮窗也只见地址栏 + 纯色页区（库侧 v1.5.0 `transparentPageArea`
已修）。

## 方案：App 内嵌完整浏览器（零权限基线）+ 承载互斥

### 库侧（apex-kit-browser v1.5.0）

- **`InAppChromeHost`**：把引擎活动 WebView 连同完整 `BrowserChrome` 嵌进
  宿主 App 自己窗口的控制器。四动作生命周期（全主线程）：
  - `attach(container)`：页面进入组合 —— 承载权 claim + `wiring.resync()`
    对齐 chrome 数据源（地址栏/标签/弹窗/下载）+ 换挂活动 WebView；
  - `detach()`：页面离开组合 —— WebView 归还引擎无头驱动（自动化坐标不丢）；
  - `pause()` / `resume()`：Activity ON_PAUSE / ON_RESUME —— 让出/恢复承载权
    （用户切去别的 App 时实时浮窗可接管跟看，回来继续内嵌浏览）；
  - 换挂走引擎 `ActiveTabListener` 多播（不与 `BrowserOverlay` 的 wiring
    单回调抢占）。
- **`InAppHostingArbiter`**：进程级「活动 WebView 承载权」裁决点。同一
  WebView 同一时刻只能有一个父容器，三承载面互斥：
  - in-app 承载期间：`BrowserOverlay` 的 WAITING_HUMAN 不再自动展开（进场
    收让/退场补展开）；`LiveViewOverlay` 转让 GHOST（换挂/自愈守卫拦下，
    不抢 WebView）；
  - in-app 退场（页面离开/Activity 暂停）：接管浮窗按 WAITING_HUMAN 补展开、
    实时卡片按引擎状态机重放——离开 App 后悬浮窗体验不变。
- **`BrowserChrome(transparentPageArea = true)`**：真实 WebView 在组合树外
  （兄弟 View 层）时根 Column 不刷不透明底色，页面区透出真实页面。

### App 侧（本仓库）

- **`BrowserScreen` 双页签**（默认浏览器页签）：
  - **浏览器页签 = `InAppBrowserPane`**：`AndroidView` 容器承载引擎 WebView
    （`InAppChromeHost` 管生命周期）+ `BrowserChrome` 全套外观（地址胶囊即
    进度条 / 标签条与总览 / 页内查找 / 下载面板 / JS 弹窗 / 网页权限浮层）+
    人工接管横幅（WAITING_HUMAN：Agent 工具锁定 + 「交还 Agent」）；
    空态（HIDDEN 且无标签）引导卡一键开会话（复用/新建标签 +
    `enterHandoffMode`）。
  - **仪表盘页签**：原观测/干预面板（会话状态/标签/最近动作/人工操作）；
    顶部新增**悬浮窗权限引导卡**（`!Settings.canDrawOverlays` 时不再静默，
    一键跳系统授权页，ON_RESUME 重检）。
- **`BrowserViewModel`**：注入 `ApexChromeWiring`（`gateway` 供 chrome 命令
  通路）与 `InAppChromeHost`；`openBrowser()`（HIDDEN → 复用/新建标签 +
  人工接管，非 HIDDEN 幂等）；「查看实时页面」= 切浏览器页签（不再直接
  驱动浮窗——in-app 承载仲裁让浮窗让位，页面在 App 内看）。

## 用户旅程（验收口径）

1. **无任何权限的全新安装**：抽屉 → 浏览器 → 浏览器页签空态引导卡 →
   「打开浏览器」→ 完整浏览器（地址栏可搜索/直达、标签、查找、下载）；
2. **Agent 驾驶**（对话里让它打开网页）：浏览器页签实时显示同一 WebView
   （零拷贝）；若用户在其他页面/其他 App：实时卡片自动弹出（需悬浮窗
   权限，未授权则浏览器页签与权限引导卡兜底，不再「什么都看不见」）；
3. **人工接管**：仪表盘「人工接管」或实时卡「接管」→ WAITING_HUMAN（横幅
   提示 + Agent 工具锁定 SYSTEM_LOCKED）→ 「交还 Agent」恢复自动化；
4. **悬浮球**：浏览器被使用即按需出现（透明玻璃核），点击切换实时卡片，
   长按结束会话；无权限时球与卡片静默缺失，但权限卡可见可修。

## 验证

- 库侧：`./test.sh gates` 全绿（含 `InAppHostingArbiterTest` 八项契约）；
  CI `Build & Test (3 modules)` + `Consumer Check (host app compiles)` 绿
  （PR #18，已合并 v1.5.0 发布列车）。
- App 侧：本地门禁六件（file_size / code_quality / comment_integrity /
  string_mirror / kotlin_balance / commit_messages）；CI 静态分析 + app
  编译（按 lock 检出 kit@8768894 composite build）+ 测试 + Debug APK。
- 真机回归项（AGENTS.md 纪律：Compose 编译过 UI 仍需真机验收）：浏览器
  页签打开/输入/搜索/换页、Agent 驾驶实时画面、接管/交还、离开页面后
  浮窗接管、权限卡跳转授权返回刷新。
