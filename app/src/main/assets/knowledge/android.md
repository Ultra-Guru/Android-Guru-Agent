<!-- kb: id=android | role=builtin_coding_android | title=Android 专家知识库 | version=1 -->
# Android 专家知识库

## 必看内容

- **心智模型先于 API**：Android 开发的三大心智模型 —— ①生命周期（组件有存活区间，回调有约定时序）；
  ②主线程唯一性（UI 线程串行，所有 UI 触碰归主线程）；③权限分级（install-time / runtime / 特殊）。
  任何代码落笔前先对齐这三个模型，90% 的"诡异 bug"是模型错位而不是 API 用错。
- **Compose 时代默认**：新 UI 一律 Jetpack Compose + Material3；View 体系只在
  互操作（WebView/地图/第三方 SDK）时引入。写 Compose 的第一纪律是
  **状态驱动**：`state → UI` 单向数据流，recomposition 是结果不是手段，
  禁止在组合期间写状态（副作用进 `LaunchedEffect`/`DisposableEffect`）。
- **协程纪律**：`viewModelScope` 是所有协程的父锚点；IO 切 `Dispatchers.IO`；
  网络请求永不阻塞调用线程；`StateFlow` 对 UI、`SharedFlow` 对事件；
  收集必须生命周期感知（`repeatOnLifecycle`）。
- **性能红线**：主线程禁止 IO/解码/JSON 解析（StrictMode 会咬你）；
  `RecyclerView/LazyColumn` 里禁止创建新对象级 lambda（remember 化）；
  启动路径禁止同步磁盘读；大图必须降采样。
- **兼容性口径**：`minSdk` 以下能力用 `Build.VERSION` 分支 + 无障碍降级；
  targetSdk 行为变化（分区存储/前台服务类型/通知运行时权限）按 target 版本生效，
  不是按设备版本。
- **真机验证纪律**：模拟器过的不算过；生产路径必须真机回归 ——
  尤其后台限制、厂商 ROM（小米/华为杀后台更狠）、权限弹窗时序。

## 快速索引

| 想找什么 | 去哪一节 |
| --- | --- |
| Gradle 依赖加不进来 / variant | 1. 环境与工具链 |
| 项目怎么分层 | 2. 项目结构 |
| recomposition 失控 | 4. Compose 心智模型 |
| 状态该放哪 | 5. Compose 状态管理 |
| 列表卡顿 | 6. Compose 性能 |
| Modifier 顺序不生效 | 7. Compose 布局 |
| 页面跳转传参 | 8. Compose 导航 |
| 协程怎么取消才对 | 9. 协程结构化并发 |
| Flow 在 UI 怎么收集 | 10. Flow 收集模式 |
| Hilt 怎么注入 | 11. Hilt 依赖注入 |
| Room 建库/升级 | 12. Room 持久化 |
| 定时/延迟任务 | 13. WorkManager |
| 键值存储选型 | 14. DataStore |
| ViewModel 什么时候清 | 15. 生命周期感知 |
| Compose 里嵌 View | 16. 互操作 |
| 运行时权限怎么写 | 17. 权限模型 |
| 后台任务被杀 | 18. 后台执行限制 |
| 网络层怎么搭 | 19. 网络层 |
| 图片加载/圆角 | 20. 图片加载 |
| 通知发不出来 | 21. 通知 |
| 启动慢/掉帧 | 22. 性能优化 |
| 怎么查崩溃 | 23. 诊断与调试 |
| 测试怎么写 | 24. 测试 |
| 动画怎么选 | 25. 动画 |
| 主题/深色模式 | 26. 主题与 Material3 |
| 数据安全/多进程 | 27. 多进程与安全 |
| 常见崩溃对照 | 28. 常见崩溃速查 |
| 各版本行为差异 | 29. API 级别差异 |
| 签名/发布 | 30. 发布与签名 |

## 1. 环境与工具链

- JDK 17 起步（AGP 8.x 要求）；本地与 CI 的 JDK 版本对齐，否则出现
  「本地绿 CI 红」的假阳性。
- AGP 8 的关键变化：`namespacedRClass` 默认开启、`buildConfig` 默认关闭
  （要 `BuildConfig` 得显式 `buildFeatures { buildConfig = true }`）。
- 依赖解析：`implementation` 不泄漏给消费者；`api` 才传递。
  版本冲突用 `resolutionStrategy` 或 BOM 统一（Compose BOM / kotlinx BOM）。
- variant（flavor × buildType）决定资源合并顺序：
  `main → flavor → flavorBuildType → buildType`，后覆盖前。
- 常用排障命令：

| 症状 | 命令 |
| --- | --- |
| 依赖树冲突 | `./gradlew :app:dependencies --configuration releaseRuntimeClasspath` |
| 谁引了这个库 | `./gradlew :app:dependencyInsight --dependency okhttp --configuration debugCompileClasspath` |
| 资源谁覆盖谁 | `./gradlew :app:assembleDebug --debug` 后看合并报告 `build/intermediates/incremental/mergeResources` |
| kapt 慢 | 迁 KSP（`com.google.devtools.ksp`），Hilt/Room 都已支持 |
| lint 卡死 | `./gradlew :app:lintDebug --continue` 单模块单问题跑 |

- Gradle 缓存/配置缓存：`org.gradle.caching=true` + `org.gradle.configuration-cache=true`
  提速明显；配置缓存与 `android.applicationVariants` 老 API 不兼容（AGP 8 里用新属性 API）。

## 2. 项目结构

Now-in-Android（NIA）风格是事实标准，本仓库也按此纪律：

- `core:*` 纯逻辑层（无 Android UI 依赖，可 JVM 单测）；
  `platform:*` 系统能力层（浏览器/终端/权限）；
  `app` 组装层（UI + DI 接线）。依赖方向**只能**上层指下层，
  反向引用在 code review 一票否决。
- 单一 Activity 架构：`MainActivity` 只做宿主，页面全 Compose；
  全局导航一个 `NavHost`，路由即单源。
- 资源命名：`<功能>_<类型>_<语义>`（`ic_web_core` / `bg_glass_panel`），
  禁止 `image1` 这类无语义名。
- 多模块的判据：**编译时间与职责边界**，不是"看着高级" ——
  新模块必须同时回答"为什么不能是同模块的包"。

## 3. Kotlin for Android 速查

协程三件套在 Android 的惯用形态：

```kotlin
// VM 里发起请求
fun load(): StateFlow<UiState> = repository.stream()
    .map { UiState.Data(it) as UiState }
    .catch { emit(UiState.Error(it.message ?: "unknown")) }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UiState.Loading)
```

- `WhileSubscribed(5000)`：屏幕旋转时 5 秒窗口内不重启上游 ——
  这是配置变更下"数据不闪"的关键参数。
- `Dispatchers.Main` 安全注入：`withContext(Dispatchers.IO)` 包 IO，
  返回主线自动恢复（不用手动 post）。
- `lifecycleScope` 只用于和生命周期同寿的一次性动作（进页面预取）；
  长寿逻辑一律上 `viewModelScope`。

## 4. Compose 心智模型

- 组合是**纯函数**：`(state) -> UI`。禁止在组合体里做 IO、写共享变量、
  改状态 —— 这些都是副作用，归 `SideEffect/LaunchedEffect/DisposableEffect`。
- recomposition 范围最小化：不稳定参数会让整棵子树重组。
  `@Stable`/`@Immutable` 标注或改用 `ImmutableList`（kotlinx-collections-immutable）
  是列表性能的第一杠杆。
- `derivedStateOf` 只包"读高频写低频"的派生值；滥用反而引入额外层。
- 三个常见反模式：

| 反模式 | 后果 | 正解 |
| --- | --- | --- |
| 组合里 `remember { mutableStateOf() }` 存跨屏状态 | 旋转丢状态 | `rememberSaveable` 或上提 ViewModel |
| lambda 内联在 `onClick` 且列表里 | 每项每帧新实例 | `remember` 化或方法引用 |
| `Modifier` 链顺序随手排 | padding/背景顺序不生效 | 按"外→内"语义排列（见 7 节） |

## 5. Compose 状态管理

分层口径：

| 状态形态 | 放哪 | 例 |
| --- | --- | --- |
| UI 瞬时（输入框内容） | 组合内 `rememberSaveable` | 文本、开关 |
| 屏级业务状态 | ViewModel + `StateFlow` | 列表数据、加载态 |
| 跨屏共享 | Repository 层单源 | 会话、主题 |
| 一次性事件 | `SharedFlow` + `SnackbarHostState` | toast、导航 |

- 事件收集：`LaunchedEffect` + `repeatOnLifecycle` 二选一；
  `collectAsState()` 只给 State，生命周期感知收集用
  `collectAsStateWithLifecycle()`（需要 lifecycle-runtime-compose）。
- 双向绑定陷阱：不要把 `TextField` 的 `value` 接 ViewModel 的
  `StateFlow` 再在 `onValueChange` 里回写 —— 每次重组都过一遍管道。
  输入态留在组合内，只有"提交"动作才上行。

## 6. Compose 性能

- 列表：`LazyColumn` + `key = { it.id }`（无 key 的增删会整项重建）；
  `contentType` 提示复用池。
- 读状态要窄：`val list by vm.items.collectAsStateWithLifecycle()` 会让
  本组合订阅整个流；在 item 内部再取子字段。
- 大图/长文本用 ` movableContentOf` 或分页（Paging3 的 `LazyPagingItems`）。
- 检测工具：Compose compiler metrics（`-P plugin:androidx.compose.compiler.plugins.kotlin:reportsDestination=`）
  看稳定性报告；Macrobenchmark 看帧率。
- 稳定性三招（按优先级）：① val + 不可变类型（List → ImmutableList）；
  ② `@Stable` 标注（要保证公开属性不可变）；③ 把 lambda 换成
  remember 的方法引用。

## 7. Compose 布局

- `Modifier` 是有序管线：先应用的在外层。`padding` 后 `background`
  = 背景不含 padding 区；`background` 后 `padding` = 含。
  惯例顺序：`布局尺寸 → 修饰（背景/边框） → 间距 → 内容内边距 → 交互`。
- 约束传递：`Column/Row` 测量子级两次（先测宽、再排高）；
  `fillMaxWidth()` 与 `weight(1f)` 冲突时 weight 赢。
- `Box` 层叠对齐用 `align`（`Modifier.align` 只能在 Box 作用域里写）。
- 滚动嵌套：`verticalScroll` 的父级再套 `LazyColumn` 会得到双滚动条
  和手势打架 —— 外层改 `heightIn` 或用 `NestedScrollConnection`。

## 8. Compose 导航

- Navigation-Compose 2.8+ 支持类型安全路由（`@Serializable` 对象做 route）；
  老字符串路由仍在大量代码里 —— 新代码一律类型安全。
- 传参三原则：小数据走路由参数（原始类型），大对象走
  `ViewModel + SavedStateHandle` 取 repository，禁止把对象序列化进路由。
- 返回结果：`savedStateHandle` 的 `getStateFlow("key")` 双向回传
  （旧 `previousBackStackEntry` 模式仍可用但生命周期更脆）。
- 深链：`manifest` 的 `intent-filter` 与 NavDeepLink 声明保持同源，
  测试深链必须真 adb intent 触发，不能只跑 Compose 测试。

## 9. 协程结构化并发

- 树形结构：父取消 → 子全取消；子失败（非 supervisor）→ 父取消兄弟。
- 两大容器：`coroutineScope`（错误上抛，常规并行）vs
  `supervisorScope`（子失败隔离，UI/服务侧多任务互不牵连）。
- Android 落点：`viewModelScope` 是 SupervisorJob + Main；
  所以 VM 里单请求失败不会连带杀掉其他请求 —— 但 `init` 里
  `scope.launch` 多个请求共享同一 VM，业务上仍要各自 try/catch 呈现错误。
- 取消是协作式的：阻塞调用（`read()`/`Thread.sleep`）不响应取消，
  换 `runInterruptible` 或挂起版 API；`ensureActive()` 在长循环里抽查。
- 超时分层：`withTimeout` 抛 `TimeoutCancellationException`；
  `withTimeoutOrNull` 返回 null。网络重试组合
  `retryWhen` + 指数退避，别裸 for 循环。

## 10. Flow 收集模式

```kotlin
// 生命周期感知收集（页面标准式）
lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
    vm.uiState.collect { render(it) }
}
// Compose 里等价物
val state by vm.uiState.collectAsStateWithLifecycle()
```

- 事件流的"一次性"语义：用 `SharedFlow(replay=0, extraBufferCapacity=1)`
  + `BufferOverflow.DROP_OLDEST` 防粘性；不要用 StateFlow 表达事件
  （同值不重发，会造成"第二次不触发"的错觉）。
- 冷流/热流：`flow{}` 冷，每收集者独立；`stateIn/shareIn` 转热
  （`SharingStarted.WhileSubscribed(5000)` 见 3 节）。
- 背压：`buffer`/`conflate` 只对快生产慢消费有意义；
  UI 收集永远在 Main，别在流里 `withContext(Main)`。

## 11. Hilt 依赖注入

- `@HiltViewModel` + `@Inject constructor(...)`；VM 参数全是 Hilt
  解析，测试里手动 new（Hilt 不进单测）。
- 作用域注解对应：`@Singleton` ↔ Application；
  `@ActivityRetainedScoped` 跨配置变更存活。
- `@Provides` 放不进构造注入（接口/第三方类）；
  `@Binds` 更省（无新实例），abstract module。
- 机器人规则：`@ApplicationContext context: Context` 只在提供层出现，
  业务类拿具体依赖不拿 Context（可测性）。
- 常见错误表：

| 报错 | 根因 |
| --- | --- |
| `@HiltViewModel` 无 `@Inject` 构造 | 缺注解或构造私有 |
| `DaggerMissingBinding` | 接口没 `@Binds`/多实现没 `@Named` 区分 |
| 旋转后状态重置 | VM 被重建 —— 检查 `viewModelStoreOwner` 是否错位（Dialog/BottomSheet 有自己的 owner） |

## 12. Room 持久化

- 三件套：`@Database`(version) / `@Dao` / `@Entity`；
  版本升级只加 `version+1` + `Migration` —— 自动迁移
  （`autoMigrations = [AutoMigration(from=1,to=2)]`）适合纯加列。
- 返回类型：查询一律 `Flow<List<T>>`（响应式）；
  写操作 `suspend`；`@Transaction` 保证多表一致。
- 主键策略：`@PrimaryKey(autoGenerate = true)` 自增 vs
  业务键（外部同步场景必须业务键，否则重复插入）。
- 陷阱：默认 `fallbackToDestructiveMigration()` 只能用于缓存型数据
  —— 用户数据用它等于"升级即清库"，事故级。
- DTO 投影：多表查询用 `data class` 投影（`@Embedded` + `@Relation`
  的对象图只在真的需要嵌套时用，1:N 大表会放大查询）。

## 13. WorkManager

- 定位：**可延期 + 可靠**的后台工作（同步/上传/清理）。
  精确闹钟不是它的活（`AlarmManager.setExactAndAllowWhileIdle`）。
- 约束组合：网络/充电/存储空间；`setExpedited(OUT_OF_QUOTA_AS_INITIATED_WORK)`
  加急只适合短任务，配额用尽自动降级。
- 唯一任务：`ExistingWorkPolicy` —— `KEEP`（防重复入队）、
  `REPLACE`（新参数覆盖）、`APPEND`（顺序链）。
- 进度：`setProgress` + `WorkInfo` 观察流；UI 用 `WorkManager` 的
  `getWorkInfosForUniqueWorkFlow`（lifecycle-aware）。
- 测试：`TestDriver`（setInitialDelay=0 手动放行）+
  `workManagerTestInit`；Doze 下的行为要真机验证。

## 14. DataStore

- 偏好存储：`Preferences DataStore`（键值）与 `Proto DataStore`（类型化）。
  `SharedPreferences` 只在维护旧代码时碰。
- 读：`data.first()` 一次性 / `data` Flow 响应式；
  写：`edit { }` 原子（内含读改写，Mutex 串行）。
- 迁移：`SharedPreferencesMigration` 一次性合并，迁移完删旧 SP 文件。
- 陷阱：DataStore 单例必须 per-file（同文件多实例抛异常）；
  大 JSON 塞 Preferences 会把整个 map 反复序列化 —— 那该进 Room。

## 15. 生命周期感知

- 组件存活表：

| 组件 | 创建 | 销毁 | 旋转 |
| --- | --- | --- | --- |
| Activity | 每次进入 | finish/系统回收 | 销毁重建 |
| Fragment 视图 | onCreateView | onDestroyView | 重建 |
| Fragment 实例 | add | remove（或栈清空） | 存活 |
| ViewModel | 首次索取 | owner 清空（finish） | 存活 |
| AndroidViewModel | 同上 | 同上 | 存活 |

- `repeatOnLifecycle(STARTED)` 收集、`launchWhenStarted`（已弃用倾向，
  因为它不取消只是暂停收集，协程仍持有）。
- `SavedStateHandle`：进程死亡恢复（`getStateFlow` + 默认值）；
  它存的值要可序列化（Bundle 支持类型）。

## 16. 互操作

- Compose 嵌 View：`AndroidView(factory, update)`；
  update 在每次重组调用 —— 状态桥要 remember 住 view 实例再 diff。
- View 嵌 Compose：`ComposeView` + `ViewCompositionStrategy`
  （默认 DisposeOnDetachedFromWindow；在 RecyclerView 里换
  `DisposeOnDetachedFromWindowOrReleasedFromPool` 防复用爆炸）。
- 混合时权限：互操作组件内部的点击监听是 View 事件，不走 Compose
  的语义（无障碍描述要在 View 上设 `contentDescription`）。

## 17. 权限模型

- 三类：普通（安装即给）/ 运行时（危险类）/ 特殊（`SYSTEM_ALERT_WINDOW`、
  通知、精确闹钟等需专门入口）。
- 标准流：`rememberLauncherForActivityResult(RequestMultiplePermissions)`
  → 结果里**逐项**检查（部分拒绝是常态），拒绝两次展示"去设置"引导
  （`shouldShowRequestPermissionRationale` 探测教育机会）。
- 后台位置：先前台授权（`while-in-use`），再申请 `ACCESS_BACKGROUND_LOCATION`
  系统会强制走设置页 —— 顺序反了会被 Play 拒审。
- 运行时权限检查必须"每次使用前"，不能缓存"上次给了"（用户可能刚撤销）。

## 18. 后台执行限制

- Doze/App Standby：网络禁用、Job 推迟（维护窗口批量放行）；
  `setExactAndAllowWhileIdle` 白名单例外要付费级权限。
- 前台服务：targetSdk 34 起**必须声明 `foregroundServiceType`** 且与
  manifest 权限匹配；类型有 `dataSync/location/mediaPlayback/camera/...`，
  错配直接 `SecurityException`。
- 启动限制：Android 12+ 从后台启动前台服务被禁（`ForegroundServiceStartNotAllowedException`），
  高优先级通知 + `setShowWhenLocked` 替代路径要真机验证。
- 厂商差异：小米/华为/Oppo 的自启管理是**额外的**门槛 ——
  引导页检测 `PowerManager.isIgnoringBatteryOptimizations` 并给出品牌化指引。

## 19. 网络层

- 栈选择：OkHttp + Retrofit（成熟生态）/ Ktor client（多平台）。
  本仓库内置浏览器另走 WebView 通道，两者互补。
- 标准分层：`Api(接口) → Repository(Flow 化) → ViewModel(stateIn)`；
  DTO 与领域模型分离（DTO 永不出 data 层）。
- 超时与重试：连接/读/写分别设；OkHttp 重试默认开（`retryOnConnectionFailure`），
  幂等 GET 可依赖；写操作必须应用层幂等键。
- 拦截器顺序：应用拦截器先于网络拦截器（重定向间只走网络层）；
  日志拦截器 release 关 `BODY` 级（敏感字段脱敏）。
- 离线优先：`NetworkMonitor`（`ConnectivityManager` 回调）暴露
  `StateFlow<Boolean>`；UI 层渲染离线横幅 + 本地缓存读取路径。

## 20. 图片加载

- Coil 为默认（Compose 原生 `AsyncImage`）。
- 必做：`size()` 明确目标尺寸（省内存解码）、`crossfade(true)`、
  错误/占位图三态齐备。
- 圆形/圆角：`Modifier.clip(shape)` 优先（渲染层裁剪），
  transformations 会烘焙进 bitmap（缓存友好但尺寸变了要重生成）。
- 长图/大图：`ContentScale.None + Modifier.verticalScroll` 或
  子采样库；`BitmapFactory` 直接解码原图是 OOM 头号来源。

## 21. 通知

- 渠道（API 26+）必须建后才能发；渠道重要性用户可改、代码不可改
  （改代码里的 importance 只影响新建渠道）。
- Android 13+：`POST_NOTIFICATIONS` 运行时权限 —— 没授权时
  `notify()` 静默失败（不抛错），必须先查 `areNotificationsEnabled()`。
- 全屏意图（来电/闹钟）：`USE_FULL_SCREEN_INTENT` 权限 + 
  `NotificationCompat.Builder.setFullScreenIntent`；锁屏展示要
  真机验证（厂商白名单）。
- 点击跳转：`TaskStackBuilder` 保证返回栈正确；通知的 PendingIntent
  flag 在 API 31+ 必须 `FLAG_IMMUTABLE`（缺了直接崩）。

## 22. 性能优化

- 启动：冷启动路径只做"进首屏"必需的事；重活 `reportFullyDrawn()`
  之后并行。基线：`strictmode` + `startup-profile`（Macrobenchmark）。
- Baseline Profiles：library profile（Jetpack 自带）+ 自家
  `baseline-prof.txt`（`androidx.profileinstaller`）；
  对 Compose 首帧收益显著。
- R8：`minifyEnabled` 后必须过一遍 mapping 反混淆崩溃栈；
  keep 规则按库官方文档加，禁止全局 `dontwarn`。
- 掉帧排查顺序：`adb shell dumpsys gfxinfo <pkg> framestats` →
  Perfetto/JankStats 定位帧 → 是布局（过度绘制）还是主线程阻塞。
- 内存：LeakCanary 集成进 debug；MAT/Profiler 查支配树；
  "内存泄漏"在 Android 语境多半是"生命周期持有"（见 15 节表）。

## 23. 诊断与调试

- Logcat 三板斧：`pid` 过滤 + 优先级 + 自定义 tag；
  崩溃现场找 `FATAL EXCEPTION` 的**完整栈**（含 caused by 链）。
- `adb bugreport` 给厂商级日志（电池/权限/活动栈）；
  `adb shell dumpsys activity top` 看当前栈顶组件。
- StrictMode：debug 构建开 `ThreadPolicy.detectAll().penaltyDeath()`
  —— 主线程 IO 类 bug 提前炸出来比上线炸好。
- ANR：`/data/anr/` trace 里看主线程在等什么；输入分发超时 5s /
  广播 10s / 服务 20s 三条线。

## 24. 测试

- 分层：纯 JVM（core 模块 `test`）最快最稳 —— 业务逻辑尽量下沉到
  能写 JVM 测试的层；Android 依赖层用 Robolectric 或
  `testDebugUnitTest`（本仓库模式）；UI 端到端 `androidTest`（真机）。
- Compose 测试：`createComposeRule().onNodeWithText()...assertIsDisplayed()`；
  语义树用 `onAllNodes` + 语义属性（`contentDescription` 是无障碍与测试共用锚点）。
- 协程测试：`runTest` + `StandardTestDispatcher` +
  `advanceUntilIdle()`；虚拟时间下 `delay` 免等待。
- Fake 优先于 Mock：`FakeRepository` 行为可控可读；
  mockito 风格只在验证调用序列时用。

## 25. 动画

- 选型：隐式状态动画（`animate*AsState`）覆盖 80% 场景；
  `AnimatedContent`/`Crossfade` 做内容切换；
  `Animatable` 手动驱动（手势跟随）；
  Lottie 放设计侧复杂动画。
- 时长纪律：默认 spring（自然）；显式 `tween` 只用于进出场门控场景，
  时长遵守 Material 标准（短 150-200ms）。
- 手势动画：`draggable` + `remember { Animatable() }`，
  松手 `animateTo`（`spring(dampingRatio = 0.8f)` 的物理感）。

## 26. 主题与 Material3

- `MaterialTheme(colorScheme, typography, shapes)` 单源；
  动态取色（Android 12+ `dynamicLightColorScheme`）作为**可选**，
  品牌色优先。
- 深色模式纪律：颜色资源双份（`values/` + `values-night/`）；
  `isSystemInDarkTheme()` 只用于渲染分支，不要存全局变量。
- 字体：`Typography` 覆盖需要的档位；中文场景默认字号放一档余量
  （系统字号缩放 ×1.3 也要可用 —— `sp` 单位不可省）。

## 27. 多进程与安全

- 多进程：`:remote` 子进程单独内存空间 —— 跨进程走 AIDL/Messenger；
  Application 会多跑一次（进程名区分初始化）。
- 数据不落明文：敏感 token 走 Keystore（`EncryptedSharedPreferences`
  或 Tink）；log 里脱敏（`%s` 替换）。
- 备份规则：`dataExtractionRules`（API 31+）显式排除敏感文件；
  `allowBackup=false` 是更保守的选项。
- 导出组件审计：`exported=true` 的组件是攻击面 ——
  每个都要有权限校验（` Binder.getCallingUid` 校验签名）。

## 28. 常见崩溃速查

| 异常 | 高频根因 | 快速定位 |
| --- | --- | --- |
| `IllegalStateException: The application is attempting...` | 在后台启动前台服务（见 18 节） | 检查 targetSdk34 FGS 规则 |
| `NetworkOnMainThreadException` | 主线程网络/大 JSON | withContext(IO) |
| `SecurityException: no manifest permission` | FGS 类型未声明 / 权限缺 | manifest 对照 foregroundServiceType |
| `ActivityNotFoundException` | 隐式 intent 无接收者 | resolveActivity 预检 + try/catch |
| `TransactionTooLargeException` | Binder 传大 bitmap/字符串 | 传 URI 或分片 |
| `DeadObjectException` | 服务进程被杀后调用 | 捕获后重绑 |
| `CursorIndexOutOfBoundsException` | moveToFirst 没判 | `use { }` + if-null |
| `IllegalArgumentException: Required view was not present` | ViewBinding 找错布局文件 | 对应关系检查 |
| `RecyclerView: No adapter attached` | 先设布局后设适配器时序 | 顺序固定 |
| `WindowManager$BadTokenException` | 非 Activity 上下文弹 Dialog | 用 Activity context |

## 29. API 级别差异

| 版本 | 关键行为变化（target 生效） | 适配要点 |
| --- | --- | --- |
| 23 (M) | 运行时权限 | 17 节标准流 |
| 24 (N) | 多窗口 | 尺寸变化当作配置变更处理 |
| 26 (O) | 通知渠道 + 后台限制 | 渠道先建；JobScheduler 替代后台服务 |
| 28 (P) | 分区存储预览（apply） | requestLegacy 兜底 |
| 29 (Q) | 分区存储强制、WebView 多进程默认 | Scoped Storage + `MediaStore` API |
| 30 (R) | 包可见性过滤 | `<queries>` 声明要探测的包 |
| 31 (S) | PendingIntent IMMUTABLE、动态暗色 | FLAG_IMMUTABLE 全量加 |
| 33 (T) | 通知运行时权限、photo picker | POST_NOTIFICATIONS 申请 |
| 34 (U) | 前台服务类型强制、后台 activity 启动禁令 | FGS type + 行为开关审计 |
| 35 (V) | 16KB 页对齐、更强后台限制 | so 库 `-Wl,-z,max-page-size=16384` |

## 30. 发布与签名

- 签名：release keystore 进 CI 变量/加密存储，绝不进仓库；
  `v1` 签名（JAR）对老设备，`v2/v3` 必开（v3 支持密钥轮换）。
- 版本纪律：`versionCode` 单调递增；`targetSdk` 滞后 Play 窗口要提前一版规划。
- AAB 上 Play（Play 托管 split）；国内渠道直发 APK 时
  `splits { abi }` 出多包，注意 so 与 `extractNativeLibs` 一致性。
- 发布前清单：ProGuard mapping 归档（崩溃反混淆）、
  `adb shell dumpsys package` 核对权限列表、真机回归四大件
  （启动/后台/通知/深链）。

## 快速索引 · 续（31-50 节）

| 想找什么 | 去哪一节 |
| --- | --- |
| 状态模式可直接抄的代码 | 31. Compose 配方集 |
| 协程高级模式（超时/重试/并行） | 32. 协程配方集 |
| Room 升级/事务/测试代码 | 33. Room 配方集 |
| Retrofit 拦截器/缓存 | 34. 网络配方集 |
| 单测/Compose 测试模板 | 35. 测试配方集 |
| 白屏/黑屏/卡死怎么查 | 36. 调试案例集 |
| 上线前性能自检 | 37. 性能清单 |
| 上线前安全自检 | 38. 安全清单 |
| 小米/华为机器不跑 | 39. 厂商兼容清单 |
| 症状→动作决策树 | 40. 故障决策树 |
| flavor/渠道包怎么配 | 41. 构建变体与发布矩阵 |
| 读屏/大字体可用性 | 42. 无障碍清单 |
| 多语言/RTL | 43. 国际化与本地化 |
| 耗电被投诉 | 44. 电池与能耗 |
| 离线同步怎么做 | 45. 数据同步模式 |
| Compose 各种为什么 | 46. Compose FAQ |
| 名词看不懂 | 47. 术语表 |
| adb/gradle 命令大全 | 48. 工具速查表 |
| 代码评审查什么 | 49. 代码评审清单 |
| View→Compose/kapt→KSP 迁移 | 50. 迁移手册 |

## 31. Compose 配方集

**配方 A：屏幕级状态容器（标准式）**

```kotlin
sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Data<T>(val value: T) : UiState<T>
    data class Error(val message: String, val retry: Boolean = true) : UiState<Nothing>
}

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val repo: ProfileRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    val userId: String = savedStateHandle["uid"] ?: ""

    val uiState: StateFlow<UiState<Profile>> = repo.profile(userId)
        .map { UiState.Data(it) as UiState<Profile> }
        .catch { emit(UiState.Error(it.message ?: "load failed")) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState.Loading)

    fun retry() { /* 触发 repo 层重载流或冷流重启 */ }
}
```

**配方 B：一次性事件（toast/导航）**

```kotlin
private val _events = MutableSharedFlow<UiEvent>(
    replay = 0, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST
)
val events: SharedFlow<UiEvent> = _events.asSharedFlow()

// 收集侧（防重组重复消费：用 LatestValueOnReplay? 不——事件用 count 锚）
LaunchedEffect(Unit) {
    vm.events.collect { event ->
        when (event) {
            is UiEvent.ShowToast -> snackbarHostState.showSnackbar(event.text)
            is UiEvent.Navigate -> navController.navigate(event.route)
        }
    }
}
```

**配方 C：列表项展开/选中（saveable）**

```kotlin
@Composable
fun ExpandingList(items: List<Item>) {
    var expandedId by rememberSaveable { mutableStateOf<String?>(null) }
    LazyColumn {
        items(items, key = { it.id }) { item ->
            ItemRow(
                item = item,
                expanded = item.id == expandedId,
                onToggle = { expandedId = if (expandedId == item.id) null else item.id }
            )
        }
    }
}
```

**配方 D：双向输入但延迟上行（防抖）**

```kotlin
var text by rememberSaveable { mutableStateOf("") }
LaunchedEffect(text) {
    delay(300)                      // 防抖窗口
    if (text.length >= 2) vm.search(text)
}
TextField(value = text, onValueChange = { text = it })
```

**配方 E：derivedStateOf 收窄重组**

```kotlin
val listState = rememberLazyListState()
val showBackTop by remember {
    derivedStateOf { listState.firstVisibleItemIndex > 4 }
}
AnimatedVisibility(visible = showBackTop) { BackTopButton() }
```

## 32. 协程配方集

**配方 A：超时 + 重试 + 退避（网络标准式）**

```kotlin
suspend fun <T> withRetry(
    times: Int = 3,
    initialDelay: Long = 500,
    factor: Double = 2.0,
    block: suspend () -> T,
): T {
    var delayMs = initialDelay
    repeat(times - 1) {
        try { return block() } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        delay(delayMs); delayMs = (delayMs * factor).toLong()
    }
    return block() // 最后一次直接抛
}
```

**配方 B：并行取多源（单失败不连坐）**

```kotlin
suspend fun loadDashboard(): Dashboard = coroutineScope {
    val user = async { userRepo.me() }
    val orders = async { orderRepo.recent() }   // supervisorScope 在 VM 层已有
    Dashboard(user.await(), runCatching { orders.await() }.getOrElse { emptyList() })
}
```

**配方 C：顺序依赖链（flatMapLatest 搜索）**

```kotlin
val results = queryFlow
    .debounce(300)
    .distinctUntilChanged()
    .flatMapLatest { q -> repo.search(q).map { it as Result } }
    .catch { emit(Result.Error) }
```

**配方 D：手动取消一批（用户切换筛选）**

```kotlin
private var searchJob: Job? = null
fun onFilter(new: Filter) {
    searchJob?.cancel()
    searchJob = viewModelScope.launch { /* ... */ }
}
```

**配方 E：Channel vs SharedFlow 选型**

| 需求 | 用什么 |
| --- | --- |
| 生产消费一次性、不丢事件 | `Channel(capacity = Channel.CONFLATED)` 或 Buffer |
| 多观察者广播 | `SharedFlow` |
| UI 状态（含初值） | `StateFlow` |

## 33. Room 配方集

**配方 A：迁移写法（1→2 加列，2→3 建索引）**

```kotlin
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE profile ADD COLUMN avatar TEXT")
    }
}
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS index_orders_status ON orders(status)")
    }
}
@Database(entities = [Profile::class, Order::class], version = 3,
    autoMigrations = [], exportSchema = true)
abstract class AppDb : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun orderDao(): OrderDao
    companion object {
        fun build(ctx: Context) = Room.databaseBuilder(ctx, AppDb::class.java, "app.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .build()
    }
}
```

- `exportSchema = true` + 房间 schema 导出目录进版本库 ——
  迁移测试（`MigrationTestHelper`）拿它回放历史路径。

**配方 B：事务批量**

```kotlin
@Dao interface OrderDao {
    @Transaction
    suspend fun replaceAll(orders: List<OrderEntity>) {
        deleteAll(); insertAll(orders)
    }
}
```

**配方 C：搜索（FTS4 全文）**

```kotlin
@Fts4
@Entity(tableName = "msg_fts")
data class MsgFts(val text: String)

@Query("SELECT m.* FROM messages m JOIN msg_fts f ON m.id = f.rowid WHERE msg_fts MATCH :q")
fun search(q: String): Flow<List<Message>>
```

**配方 D：Room + 测试**

```kotlin
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getParameters(),
        AppDb::class.java)
    @Test fun migrateAll() {
        var db = helper.createDatabase("test", 1).apply { close() }
        db = helper.runMigrationsAndValidate("test", 3, true, MIGRATION_1_2, MIGRATION_2_3)
    }
}
```

## 34. 网络配方集

**配方 A：OkHttp 拦截器（token 注入 + 401 刷新）**

```kotlin
class AuthInterceptor(private val tokenStore: TokenStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request().newBuilder()
            .header("Authorization", "Bearer ${tokenStore.access}")
            .build()
        val resp = chain.proceed(req)
        if (resp.code == 401) {
            synchronized(this) {
                val fresh = tokenStore.refreshBlocking()   // 单飞刷新
                return chain.proceed(req.newBuilder()
                    .header("Authorization", "Bearer ${fresh}")
                    .build())
            }
        }
        return resp
    }
}
```

- 注意 `synchronized` 里不要做第二次 401 重试（死循环）：
  加 `if (resp.request.header("X-Retried") != null) return resp`。

**配方 B：离线优先仓库**

```kotlin
class FeedRepository(private val api: FeedApi, private val dao: FeedDao) {
    fun feed(): Flow<List<Feed>> = dao.observe()
        .onStart { emit(emptyList()) }
        // 网络刷新失败不中断本地流
    suspend fun refresh(): Result<Unit> = runCatching {
        dao.replaceAll(api.fetch().toEntities())
    }.onFailure { if (it is IOException) throw it }
}
```

**配方 C：分页（Paging3）**

```kotlin
val pager = Pager(PagingConfig(pageSize = 20, prefetchDistance = 5)) {
    FeedPagingSource(api)
}.flow.cachedIn(viewModelScope)
// Compose: val items: LazyPagingItems<Feed> = pager.collectAsLazyPagingItems()
```

## 35. 测试配方集

**配方 A：VM 单测（纯 JVM + Fake）**

```kotlin
class ProfileViewModelTest {
    @get:Rule val mainDispatcher = MainDispatcherRule()   // 标准 TestRule

    @Test fun `error state surfaces message`() = runTest {
        val vm = ProfileViewModel(FakeRepo(result = failure("boom")), SavedStateHandle())
        val states = mutableListOf<UiState<Profile>>()
        val job = launch { vm.uiState.take(2).toList(states) }
        advanceUntilIdle(); job.cancel()
        assertTrue(states.last() is UiState.Error)
    }
}
```

**配方 B：Compose UI 测试**

```kotlin
@Test fun toggle_expands_item() {
    composeTestRule.setContent { ExpandingList(sampleItems) }
    onNodeWithText("标题1").performClick()
    onNodeWithText("详情内容1").assertIsDisplayed()
}
```

**配方 C：Robolectric 场景（权限/Dialog）**

```kotlin
@Test fun `permission denied shows rationale`() {
    shadowsOf(application).denyPermissions(ACCESS_FINE_LOCATION)
    launchActivity<MainActivity>()
    onView(withText(R.string.perm_rationale)).check(matches(isDisplayed()))
}
```

## 36. 调试案例集

| 现象 | 排查步骤 | 高频根因 |
| --- | --- | --- |
| 冷启动白屏久 | adb logcat -s ActivityTaskManager；Macrobenchmark startup | Application 同步初始化过多 |
| 首页列表卡 | Perfetto 看主线程；检查 item lambda | 列表项每次重组整行 |
| 旋转后状态丢 | 检查状态存哪层（15 节表） | remember 未 saveable / VM 被重建 |
| 后台任务没跑 | dumpsys jobscheduler 查约束 | Doze + 充电约束永false |
| 通知不显示 | areNotificationsEnabled + 渠道存在性 | 13+ 没申请 POST_NOTIFICATIONS |
| 只在真机崩（模拟器好） | bugreport 对比 | 厂商 ROM/so 缺失/abi 过滤 |
| release 崩 debug 不崩 | mapping 反解 + 检查 keep 规则 | R8 裁掉了反射用到的类 |
| Webview 白屏 | onReceivedError 打印；混合内容开关 | http 资源被 MIXED_CONTENT 拦 |
| 图片 OOM | Bitmap 尺寸 vs 内存分析 | 未降采样（20 节） |
| ANR 输入超时 | /data/anr trace 主线程栈 | 主线程锁/IO |

## 37. 性能清单

上线前逐项打勾：

- [ ] 冷启动 < 1.5s（中端机基线）；StrictMode debug 构建无 red
- [ ] 列表滚动 jank < 1%（Macrobenchmark frame）
- [ ] Baseline profile 生成并验证（对比无 profile 数据）
- [ ] 大图全部走 Coil + size 指定；无 Bitmap 直接解码
- [ ] R8 开启 + mapping 归档；反射类 keep 齐全
- [ ] 主线程无 IO（文件/SP commit/JSON）—— 用 ANR-Watchdog 抽查
- [ ] 内存：LeakCanary debug 长跑无泄漏；堆峰值 < 设备可用 1/3
- [ ] 网络：OkHttp 连接池复用；图片 CDN 尺寸参数
- [ ] 电耗：后台 wake-lock 全审计（44 节）
- [ ] 包体：abi split；资源压缩（webp）；未用资源 lint 清理

## 38. 安全清单

- [ ] release 禁止明文 HTTP（`usesCleartextTraffic=false`）
- [ ] 敏感串不入仓库/日志；token 用 Keystore 加密
- [ ] export 组件逐一审计（intent 校验调用方）
- [ ] WebView：JS bridge 只暴露白名单方法；`@JavascriptInterface` 最小面
- [ ] 备份规则排除敏感文件（27 节）
- [ ] 日志系统双 release 开关（debug 级别）
- [ ] 第三方 SDK 的隐私合规声明（工信部口径）齐备
- [ ] 证书固定（okhttp CertificatePinner）按需评估 —— 注意轮换风险

## 39. 厂商兼容清单

| 厂商 | 常见坑 | 对策 |
| --- | --- | --- |
| 小米 | 自启/省电策略杀后台更狠 | 引导页检测 isIgnoringBatteryOptimizations + 跳自启设置页 |
| 华为/荣耀 | EMUI 后台服务 5 分钟强杀 | 前台服务 + 通知常驻；Huawei Gallery 上架要过检 |
| Oppo/Vivo | 锁屏断网 | 心跳用 WorkManager 周期 + 高优先级通知 |
| 鸿蒙 | 兼容 Android 包但推送走 HMS | 有推送需求时接 HMS Push 通道 |
| 三星 | 多窗口/分屏 resize | 28 节（多窗口当配置变更） |
| all | 国产 ROM 通知渠道默认关 | 引导逐项开启渠道开关 |

## 40. 故障决策树

症状 → 第一动作：

```text
崩了？
├─ 有栈？→ 28 节表对照 → 修
├─ 无栈（native）？→ tombstone/addr2line → so 符号表
└─ ANR？→ trace 主线程栈 → 锁/IO → 归 37 节
卡？
├─ 全局卡（所有页面）→ dumpsys gfxinfo → 渲染层（过度绘制/层级）
├─ 单页卡 → 该页重组范围（6 节）→ 稳定性/lambda
└─ 滚动卡 → 列表三件套：key/稳定参数/图片解码
不对（行为与预期不符）？
├─ 权限类 → 17 节流程逐步核对（尤其"给了又撤"）
├─ 时序类 → 生命周期表（15 节）重放
└─ 数据类 → 单源破坏（多处真相）→ 收敛到 repository
```

## 41. 构建变体与发布矩阵

- 维度设计：`flavorDimensions("channel")` —— 渠道维度 +
  buildType（debug/release）组合；
  每渠道差异（包名后缀/替换资源/渠道号）用 `sourceSets` 目录覆盖。
- 多渠道打包：`productFlavors { create("huawei") {...} }`；
  国内分发用 `splits.abi` 或 AAB 由商店托管。
- 发布矩阵纪律：`versionCode` 全渠道唯一递增；
  渠道号走 manifest meta-data（`UMENG_CHANNEL`）而不是包名变体。
- CI 触发：tag → release.yml；PR → ci.yml（app-compile + 9 步测试）；
  发布前 hot-fix 走 cherry-pick 专用分支模板。

## 42. 无障碍清单

- [ ] 所有可点元素有语义（text 或 contentDescription）
- [ ] 触控目标 ≥ 48dp（`minimumInteractiveComponentSize`）
- [ ] 焦点顺序符合阅读序（Compose 默认组合序，自定义走 `Modifier.semantics`）
- [ ] 动画可关（`Settings.Global.ANIMATOR_DURATION_SCALE == 0` 尊重）
- [ ] 文本对比度 ≥ 4.5:1（深色模式单独校）
- [ ] TalkBack 走查核心路径（登录/支付/设置）
- [ ] 大字体（sp 缩放 200%）不截断关键操作

## 43. 国际化与本地化

- 资源分层：`values/`（默认=英文）+ `values-zh/`；本仓库还有
  `values-zh/strings_*` 按域拆文件（镜像纪律：键 1:1，占位符一致 ——
  CI `check_string_mirror.py` 强制）。
- RTL：布局用 start/end 而非 left/right；图标方向（返回箭头）走
  autoMirrored。
- 复数：`<plurals>` 按语言规则；禁止手拼 "s"。
- 日期/数字：`java.time` + `DateTimeFormatter.ofLocalizedDate`；
  货币展示归 UI 层格式化（不要在数据层格式化）。

## 44. 电池与能耗

- WakeLock 纪律：持有时长 < 60s 常态；释放放 finally；
  长任务一律 WorkManager 化（系统调度合并唤醒）。
- 定位：`PRIORITY_BALANCED` 起步；导航场景才 `HIGH`；
  持续定位必须前台服务 + 用户可见通知。
- AlarmManager 精确闹钟要 `SCHEDULE_EXACT_ALARM`（12+）权限
  且用户可在系统设置关闭 —— 降级路径必须同版本可用。
- 测量：`dumpsys batterystats --charged <pkg>` 对比场景耗电；
  Background restriction 状态预检（`isBackgroundRestricted`）。

## 45. 数据同步模式

- 冲突口径：last-write-wins 起步；有业务语义（编辑冲突）才做
  三方合并 —— 同步协议里带 `updatedAt` + 设备 id。
- 增量：游标（`since`）分页 + ETag；断点续传把游标落 DataStore。
- 队列化：本地写立即更新 UI + 入队（Room 表），
  WorkManager 出队上传；网络回来 `NetworkMonitor` 触发冲队列。
- 幂等：服务端幂等键（客户端 UUID）防重复提交 ——
  重试与用户重复点击同路处理。

## 46. Compose FAQ

| 问 | 答 |
| --- | --- |
| 为什么参数没变也重组？ | 传入的是不稳定类型（List/lambda）→ 6 节三招 |
| remember 里的值怎么不更新？ | remember 只在首次组合求值；依赖变化用 `remember(x) { }` 键控 |
| LaunchedEffect 和 SideEffect 区别？ | 前者带协程作用域+可取消；后者每次重组后同步执行（无挂起） |
| Modifier 顺序到底怎么排？ | 7 节管线语义：外层先（尺寸→修饰→间距） |
|重组中怎么改状态？ | 不许（组合是纯函数）—— 副作用进 LaunchedEffect |
| stateIn 的 5000 是什么？ | WhileSubscribed 的停止延迟：旋转窗口（3 节） |
| collectAsState 与 WithLifecycle 差别？ | 后者感知生命周期（后台停收集），列表/流大时收益明显 |
| Canvas 什么时候重绘？ | 读的 state 变化触发重组；invalidate 不存在（声明式） |
| 两屏共享状态放哪？ | repository 单源；VM 只做屏级形态（5 节表） |
| Dialog 里的状态旋转丢了？ | Dialog 有自己的 saveScope —— 上提到调用方或 rememberSaveable |

## 47. 术语表

| 术语 | 含义 |
| --- | --- |
| Recomposition | 状态变化后重执行组合函数 |
| Stability | 类型是否可变/影响跳过重组的判定 |
| Slot table | Compose 的树存储结构（位置记忆） |
| Composition Local | 树形隐式传参（`LocalContext`） |
| Baseline profile | 预编译热点路径清单（启动/滚动提速） |
| Doze | 深度省电模式（网络/任务推迟） |
| App Standby | 闲置桶分级（活跃/工作集/频繁/罕见/受限） |
| FGS | 前台服务（带常驻通知） |
| AAB | Android App Bundle，Play 托管分发格式 |
| V2/V3 签名 | 整包签名方案；v3 支持密钥轮换 |
| FTS | Room 全文索引虚拟表 |
| Macrobenchmark | 宏观性能基准测试框架 |
| Hilt Singleton | Application 作用域单例 |

## 48. 工具速查表

| 目的 | 命令 |
| --- | --- |
| 清晰 logcat | `adb logcat -v time --pid=$(adb shell pidof -s pkg)` |
| 当前栈顶 | `adb shell dumpsys activity top \| head -40` |
| 帧统计 | `adb shell dumpsys gfxinfo pkg framestats` |
| 强 GC 后内存 | `adb shell am dumpheap -n pkg /data/local/tmp/h.hprof` |
| ANR trace | `adb bugreport`（zip 内 FA ANR） |
| 权限快照 | `adb shell dumpsys package pkg \| grep -A2 "runtime"` |
| 装多 ABI | `adb shell getprop ro.product.cpu.abilist` |
| 深链测试 | `adb shell am start -a android.intent.action.VIEW -d "https://x/y"` |
| 依赖树 | `./gradlew :app:dependencies --configuration debugCompileClasspath` |
| 单元测试 | `./gradlew :app:testDebugUnitTest --tests "SomeTest"` |
| 组合报告 | `./gradlew assembleDebug -P composeCompilerReports=...` |

## 49. 代码评审清单

- [ ] 依赖方向：上层→下层单向（2 节）；无 core 引 app
- [ ] 状态放置符合 5 节表；组合无副作用
- [ ] 协程：无 GlobalScope；IO 显式调度；取消传播不被吞（catch 后 rethrow CancellationException）
- [ ] Room 写在 `@Transaction`；迁移有测试
- [ ] 网络错误被建模（不吞成"成功空列表"）
- [ ] 字符串全部资源化（i18n 镜像键齐）
- [ ] 无障碍语义齐（42 节）
- [ ] 新公开 API 有 KDoc；坑写明（对照本库必看）
- [ ] 测试在正确的层（能 JVM 就不 Robolectric；能 Robolectric 就不真机）
- [ ] 文件行数预算：main ≤ 1200 / test ≤ 1600（超了拆，不提额）

## 50. 迁移手册

**View → Compose 渐进式**

1. 新页面直接 Compose；旧页面外围包 `ComposeView` 逐块替换
   （`ViewCompositionStrategy.DisposeOnDetachedFromWindow`）。
2. 状态桥：把 View 体系的数据源先统一到 Flow（ViewModel 层无 UI 假设），
   Compose 侧 `collectAsStateWithLifecycle` 消费 —— 一份数据两套 UI 过渡期共存。
3. 主题桥：`AndroidView` 里用 `MaterialTheme(M3 colors from view theme)`
   包住，避免视觉断裂。
4. 测试桥：espresso-on-compose 一套用例覆盖两种 UI（过渡期防回归）。

**kapt → KSP**

1. 加 KSP 插件；Hilt/Room 的 kapt 与 ksp 版本成对切换。
2. `kapt` 块删除；自定义注解处理器要自己出 KSP 符号处理器
   （没有的暂留 kapt，双跑一个 release 周期）。
3. 构建时间对照（预期 -30% 起步）；CI 的 compile 缓存重建。

**Groovy → Kotlin DSL**

1. `settings.gradle` → `settings.gradle.kts` 逐文件；
2. `def` → `val`；`apply from:` → 插件化；
3. `buildSrc` 收敛到 convention plugins（`build-logic` 目录）。

## 快速索引 · 续（51-70 节）

| 想找什么 | 去哪一节 |
| --- | --- |
| 从零搭页面全流程 | 51. 剧本·新页面 |
| 接 SDK 的标准动作 | 52. 剧本·三方 SDK |
| 接推送 | 53. 剧本·推送 |
| 实现深链 | 54. 剧本·深链 |
| 加一门语言 | 55. 剧本·多语言 |
| 线上崩溃应急 | 56. 剧本·热修复决策 |
| slot table 到底是什么 | 57. Compose 内部机制 |
| 快照/并发读状态 | 58. 快照系统 |
| Perfetto 怎么用 | 59. 性能剖析工作流 |
| keep 规则怎么写 | 60. R8 精编 |
| 启动分几段优化 | 61. 启动实战 |
| 列表深度优化 | 62. 列表实战 |
| 内存深挖 | 63. 内存实战 |
| 弱网/重试策略 | 64. 弱网实战 |
| 精确闹钟/休眠 | 65. 休眠实战 |
| 加固/防逆向 | 66. 安全实战 |
| WebView 混合深水区 | 67. WebView 深水区 |
| DataStore vs Room | 68. 存储选型深辨 |
| 依赖版本治理 | 69. 依赖治理 |
| 发布当天清单 | 70. 上线检查总表 |

## 51. 剧本·新页面

标准动作序列（照抄）：

1. 路由：类型安全 route 对象 + NavHost 注册（8 节）。
2. 状态：`UiState` sealed + VM `stateIn`（配方 31-A）。
3. UI：三态渲染（Loading/Content/Error）骨架先落地。
4. 数据：repository 方法 + Fake 供单测。
5. 测试：VM 单测（35-A）+ Compose 测试（35-B）。
6. 无障碍：语义补齐（42 节）+ TalkBack 走查。
7. 深色：双主题截图比对。
8. 性能：列表页加 `key/contentType`；进 62 节模板。

坑位提示：

- VM 构造参数新增后，Hilt 自动接线无需改调用点；但 preview 场景
  要给 `@PreviewParameter` 或 fake 构造。
- 页面参数走 `SavedStateHandle` 取（进程死亡恢复同源）。
- 转屏验证必须真机（模拟器 config 变更路径不同）。

## 52. 剧本·三方 SDK

接入决策顺序：官方文档依赖坐标 → 隐私合规评审 → 隔离层包装。

- **隔离层纪律**：SDK 类不外泄到业务层；包一层
  `XxxWrapper` + `interface`，测试可 Fake。
  版本升级只动 wrapper —— 业务无感。
- 初始化：`Initializer`（androidx.startup）统一注册顺序；
  延迟初始化的（推送 token 回来才需要）放首次使用点。
- 权限：SDK 清单合并进来的权限逐项审计
  （`./gradlew :app:dependencies` + manifest merger 报告），
  多余的用 `tools:node="remove"` 干掉。
- 冲突：`duplicate class` → BOM 对齐版本；
  `META-INF` 冲突 → packagingOptions exclude。

## 53. 剧本·推送

- 通道选择矩阵：

| 场景 | 通道 |
| --- | --- |
| 海外 Play 分发 | FCM |
| 国内多厂商 | 各厂商通道（华为/小米/Oppo/vivo）+ 聚合SDK（如自建） |
| App 前台运行中 | 自有长连接（WebSocket）最快 |

- 到达率现实：厂商通道在杀后台后仍可达（系统级），
  但需各自后台申请应用；自建通道只覆盖活着的进程。
- 通知点击路由统一：`NotificationRouter` 单点解析 payload →
  导航（深链 54 节）—— 防止各 SDK 各自为政打开不一致页面。
- 测试：厂商控制台发测试消息 + 真机锁屏/杀后台/Doze 三态验证。

## 54. 剧本·深链

1. Manifest：`<intent-filter>`（VIEW/BROWSABLE，scheme+host 固定写死列表）。
2. Nav：`deepLinks = listOf(navDeepLink { uriPattern = "https://x/y/{id}" })`。
3. 验证：`adb shell am start -d` 三处命中 ——
   冷启动（进程新起）/ 热启动（已运行）/ 锁屏直达。
4. 防串味：`launchSingleTop` + `popUpTo` 配置，避免深链堆栈爆炸
   （用户按返回应该回首页而不是退出）。
5. 延迟参数校验：深链进来的参数不可信（外部输入）——
   路由层校验后才入 VM。

## 55. 剧本·多语言

1. `values-zh-XX/strings.xml` 落盘；占位符与默认严格一致
   （CI string mirror 门禁会拦）。
2. 切换即时生效：`AppCompatDelegate.setApplicationLocales()`（13+）
   或 activity 重建广播路径（兼容库）。
3. RTL 目标语言上线前：force LTR 开发者选项过一遍全部页面。
4. 测试：伪语言（en_XA / ar_XB）渲染测试截屏比对。

## 56. 剧本·热修复决策

线上崩溃响应优先级：

| 现象 | 动作 |
| --- | --- |
| 单一机型/单一版本小面积 | 观察聚合（24h 内自然恢复率） |
| 全量 >1% 且功能不可用 | 紧急发版（走 hotfix 分支模板） |
| 启动即崩 | 回滚开关（服务端配置）/ 商店分阶段暂停 |
| 数据破坏型 | 先发停写配置，再修复，最后补偿脚本 |

热修复框架（Tinker/Shadow）只在有成熟基建时用；
没有的话 —— 紧急发版在 Play 分阶段推全量约 2-4h，
通常够用且无合规风险。

## 57. Compose 内部机制

- Slot table：组合期间的树以线性数组存（gap buffer），
  `remember` 的值就存在槽里 —— `remember(x){}` 的 x 变化时
  槽失效重算，这就是"位置记忆"的全部秘密。
- 组合/布局/绘制三阶段：组合（生成树）→ 布局（测量+放置，Constraint 传递）
  → 绘制。`Modifier` 在三阶段各有一段语义
  （`layout{}` 拦截布局，`drawBehind{}` 拦截绘制）。
- 重组的范围单位是"重启边界"（可跳过组）：
  不稳定参数让边界扩大 —— 这就是稳定性为什么影响性能
  （机制层面，不是玄学）。
- 无障碍/语义树在组合阶段随行生成（`semantics` merge 规则：
  合并边界 `mergeDescendants`）。

## 58. 快照系统

- `mutableStateOf` 的写会创建全局快照提交（Snapshot.commit），
  主线程帧边界统一应用（`Snapshot.sendApplyNotifications`）。
- 跨线程：后台线程写 state 合法（快照隔离），但 UI 读到的是
  已提交值 —— 不会撕裂。
- `Snapshot.withMutableSnapshot { }`：批量原子写（避免中间态被读到）。
- 全局快照监控：`Snapshot.registerGlobalWriteObserver` ——
  调试"谁在写状态"的利器（写完打 log 定位调用方）。

## 59. 性能剖析工作流

Perfetto 标准步骤：

1. 采集：`adb shell perfetto --config -o /data/misc/perfetto-traces/trace` 
   （或 Android Studio Profiler 录制；帧轨迹选 `Frame Timeline`）。
2. 打开 perfetto.ui：看主线程跑道的长任务（红色块）。
3. 定位类型：layout（测量）>8ms / draw / bitmap 解码 /
   未知（native so → addr2line）。
4. 对照修复后**复测**（同场景三次取中位）—— 单次数据是噪音。
- JankStats（运行时帧监控）接入灰度；
  Macrobenchmark 覆盖：startup / scroll / search 三场景。

## 60. R8 精编

- keep 三原则：反射用到的类/序列化模型/`@JavascriptInterface` 成员。
- 写法：宁可窄（具体类名+成员），不要宽（通配 **）：

```proguard
# 反射注册表（按需）
-keep class com.x.MessageDto { *; }        # 太宽 —— 只 keep @SerializedName 字段即可
-keepclassmembers class com.x.MessageDto { <fields>; }
```

- `-printusage` 输出被裁清单 → 逐项确认为"真没用到"。
- 规则来源可信度排序：官方文档 > 官方 consumer rules（自动带）>
  StackOverflow（要验证，过时重灾区）。
- 自检：release 构建跑完整冒烟（登录/支付/推送/深链），
  R8 问题 90% 出在这些反射路径。

## 61. 启动实战

分解四段，各段独立度量（reportFullyDrawn 为界）：

| 段 | 内容 | 预算 |
| --- | --- | --- |
| 冷启动系统段 | zygote → Activity onCreate | 不可控，~300ms |
| Application 段 | onCreate 同步部分 | <150ms（只留必要初始化） |
| 首帧段 | inflate/组合首屏 | <400ms |
| 可交互段 | 数据回来可点 | 目标 TTI <1.5s |

手段按收益排序：

1. Application 瘦身：非必须的挪 `reportFullyDrawn` 后/异步初始化器。
2. Baseline profile（22 节）。
3. 首屏组合最小化：骨架屏（Loading 真图）而不是空 Box。
4. 阻塞串行改并行：`coroutineScope { async {} }` 各初始化器。

## 62. 列表实战

优化顺序（先做前面的）：

1. `key` + `contentType`（无成本，先做）。
2. item 稳定性：入参 ImmutableList / @Stable。
3. 图片：Coil + size + 内存缓存（默认有）。
4. lambda：事件处理上提（父级方法）或 remember。
5. 测量：JankStats 定位帧 → Perfetto 看该帧栈。
- 大列表分页（Paging3）超过 ~500 项必须上。
- 嵌套滚动（横向条内纵向列表）：`nestedScroll` 修饰符透传。

## 63. 内存实战

- 三问定位法：谁持有（支配树）→ 为什么持有（引用链头）→ 归属哪类（下表）：

| 类别 | 表现 | 解法 |
| --- | --- | --- |
| 生命周期持有 | 退出页不释放 | 15 节表核对存放层 |
| 静态集合 | 单调上涨 | 注册表加移除路径；用 WeakHashMap 要谨慎 |
| Bitmap | 峰值尖刺 | 降采样 + inBitmap 复用 |
| 资源未关 | Cursor/流 | use{} 模板 |
| 监听器泄漏 | SDK 回调持有 Activity | wrapper 层持 application 上下文 |

- 工具：LeakCanary（debug）→ hprof 分析（Profiler/MAT）→
  灰度内存监控（`Debug.getMemoryInfo` 周期上报）。

## 64. 弱网实战

- 超时组合：connect 10s / read 30s / write 30s 起步；
  弱网场景 read 放宽并给 UI 进度感（否则用户以为死了）。
- 重试矩阵：幂等 GET 自动重试；非幂等走幂等键（45 节）；
  最大 3 次指数退避 + 抖动（jitter 防惊群）。
- 离线队列：Room 表存待发请求，WorkManager 出队；
  网络恢复事件冲队列（19 节 NetworkMonitor）。
- 测试：`NetworkShaping`（模拟器）设 200ms RTT / 5% 丢包跑核心流。

## 65. 休眠实战

- 精确闹钟：`canScheduleExactAlarms()` 预检（12+ 可被用户关）；
  关闭时降级 `setWindow` + 引导开权限（不是崩，是误差变大要告知）。
- Doze 白名单请求 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
  只对核心场景用（闹钟/IM），滥用会被 Play 拒。
- 心跳设计：WorkManager 周期最小 15 分钟（系统下限）；
  更细粒度必须前台服务（可见通知），这是 34+ 的硬规则。

## 66. 安全实战

- 传输：TLS 全站（cleartext false）；证书固定评估轮换成本（38 节）。
- 存储：Keystore 包密钥 + AES/GCM；
  `EncryptedSharedPreferences` 简单场景，Tink 复杂场景。
- 防逆向：R8 混淆（60 节）为主；加固壳（梆梆/360）按合规评估，
  注意加固壳与热修复框架的兼容矩阵。
- 动态校验：签名自校验（`PackageManager.GET_SIGNATURES` 比对摘要）
  —— 只增加门槛不是保险箱。

## 67. WebView 深水区

- JS bridge 面最小化：白名单方法 + `@JavascriptInterface`；
  参数当外部输入校验（不要 eval 传来的字符串）。
- 文件访问：`allowFileAccess` 只对已知目录；
  本仓库预览对话框的纪律可参考（同目录内放行、跨目录拦截）。
- 混合内容：`MIXED_CONTENT_COMPATIBILITY_MODE` 过渡期，
  目标 `NEVER_ALLOW` + 全站 https。
- 拦截请求：`shouldOverrideUrlLoading` 返回 true 时**必须**自己
  加载或显式跳过，否则白屏。
- 崩溃多进程：`android:process=":web"` 隔离 renderer 崩溃
  （主进程保活），代价是内存多一份。

## 68. 存储选型深辨

| 维度 | DataStore(Prefs) | DataStore(Proto) | Room | 内存(不落盘) |
| --- | --- | --- | --- | --- |
| 数据形状 | 平铺键值 | 平铺类型化 | 关系/列表 | 任意 |
| 大小 | <1MB | <1MB | 任意 | 受堆限制 |
| 查询 | 键 | 键 | SQL/FTS | 代码 |
| 响应式 | Flow | Flow | Flow | StateFlow |
| 迁移成本 | SP 迁移器 | proto 演进 | schema 版本 | 无 |
- 判据：要"问问题"（条件查询/聚合）→ Room；
  要"读配置"→ DataStore；两者混合（配置+缓存）各归其位。

## 69. 依赖治理

- BOM 统一：Compose BOM / kotlin BOM / kotlinx BOM；
  版本单一来源（`libs.versions.toml`）。
- 升级纪律：小步（一次一个 major）；`dependencyGuard` 或
  lockfile 防隐式漂移；CI 对照 `gradle/libs.versions.toml` diff 审计。
- 弃用追踪：`./gradlew :app:buildHealth`（依赖健康报告插件）或
  版本目录 lint —— deprecated 依赖排期下线。

## 70. 上线检查总表

发布当天走查（发布人签字）：

- [ ] versionCode/versionName 与发布单一致
- [ ] mapping 文件归档（崩溃平台可反解）
- [ ] 权限 diff 与上一版对比（新增要有理由）
- [ ] targetSdk 合规（商店当期窗口）
- [ ] 四大件真机回归：启动/后台/通知/深链（39 节矩阵过厂商）
- [ ] 深浅色截图抽查核心 5 页
- [ ] 无障碍 TalkBack 抽查核心 2 流程
- [ ] 弱网模式（开发者选项）跑核心流
- [ ] 存储/隐私声明文本与实际收集项一致（38 节清单）
- [ ] 灰度计划：分阶段 5% → 20% → 100%，回滚触发条件写明

## 快速索引 · 续（71-85 节）

| 想找什么 | 去哪一节 |
| --- | --- |
| Compose 组件/Modifier 总表 | 71. Compose API 速查 |
| Modifier 按用途分类 | 72. Modifier 目录 |
| 协程 API 一页纸 | 73. 协程 API 速查 |
| Flow 操作符一页纸 | 74. Flow 操作符速查 |
| Hilt 注解全量 | 75. Hilt 注解速查 |
| Room 注解全量 | 76. Room 注解速查 |
| 常用 Intent 动作 | 77. Intent 速查 |
| 回调时序全图 | 78. 生命周期回调速查 |
| 通知构造模板 | 79. 通知 API 速查 |
| 危险权限分组 | 80. 权限常量表 |
| FGS 类型对照 | 81. 前台服务类型表 |
| 模拟事件命令 | 82. adb 模拟速查 |
| proguard 语法 | 83. ProGuard 语法 |
| kts 常用片段 | 84. Gradle DSL 片段 |
| 版本对应关系 | 85. 版本矩阵 |

## 71. Compose API 速查

| 组件 | 作用 | 关键参数 |
| --- | --- | --- |
| `Column/Row/Box` | 线性/层叠容器 | arrangement, alignment |
| `LazyColumn/Row` | 惰性列表 | `items(list,key,contentType)` |
| `Scaffold` | 脚手架 | topBar/bottomBar/snackbarHost |
| `NavHost` | 导航容器 | startDestination |
| `ModalBottomSheet` | 底部抽屉 | onDismissRequest |
| `Dialog/AlertDialog` | 弹层 | onDismissRequest |
| `AnimatedContent` | 内容切换动画 | transitionSpec |
| `Crossfade` | 淡入淡出 | targetState |
| `AsyncImage` | 网图 | model/contentScale |
| `OutlinedTextField` | 输入框 | value/onValueChange |
| `Surface` | 材质面 | tonalElevation/shape |
| `PullToRefresh` | 下拉刷新（M3 1.3+） | isRefreshing/onRefresh |

- 快速判断"用什么容器"：单向流→Column；横排一行→Row；
  覆盖叠层→Box；大量重复→Lazy*；脚手架组合→Scaffold。

## 72. Modifier 目录

| 类别 | 常用 | 备注 |
| --- | --- | --- |
| 尺寸 | fillMaxWidth/heightIn/requiredSize/weight | weight 只在 Row/Column 作用域 |
| 间距 | padding（外/内取决于位置） | 顺序语义见 7 节 |
| 背景修饰 | background/border/drawBehind | border 含内边距语义 |
| 裁剪 | clip/clipToBounds | clip 后再画内容 |
| 滚动 | verticalScroll/horizontalScroll/scrollable | 一次一根轴 |
| 焦点语义 | focusable/clickable/semantics | clickable 语义=按钮可加 role |
| 布局干预 | layout{}/onSizeChanged | 拦截测量 |
| 变换 | graphicsLayer | 缩放/旋转/透明度（渲染层） |
| 状态 | statusBarsPadding/navigationBarsPadding | edge-to-edge 必配 |
| 测试 | testTag | UI 测试锚点 |

## 73. 协程 API 速查

| API | 语义 | 陷阱 |
| --- | --- | --- |
| `launch` | 火 and 忘（Job） | 异常默认上抛父级 |
| `async` | 要返回值（Deferred） | await 时才暴露异常 |
| `coroutineScope` | 并行子作用域 | 任一子失败取消兄弟 |
| `supervisorScope` | 失败隔离 | 子要自己 try/catch |
| `withContext` | 切调度器再回来 | Main 安全 |
| `delay` | 虚拟可测 | 阻塞版是 Thread.sleep（禁） |
| `withTimeout` | 超时抛 | 会取消内部块 |
| `awaitAll/joinAll` | 等多个 | 单失败语义随 scope |
| `Mutex` | 协程互斥 | 跨 withContext 安全 |
| `Semaphore` | 并发限流 | 限并发请求数 |
| `Flow.collectLatest` | 新值取消旧收集 | 搜索类标配 |
| `channelFlow` | 冷流可 send | 桥回调世界 |

## 74. Flow 操作符速查

| 操作符 | 作用 | 记忆点 |
| --- | --- | --- |
| map/filter | 同 Rx | 顺序应用 |
| onEach | 副作用 | 不改值 |
| debounce | 防抖 | 输入搜索标配 |
| distinctUntilChanged | 去重 | StateFlow 内建 |
| combine/zip | 合流 | combine 最新合并 |
| flatMapLatest/Concat | 展平 | 搜索用 Latest |
| catch | 上游捕获 | 不接下游取消 |
| retryWhen | 重试 | 配指数退避 |
| buffer/conflate | 背压 | 快生产慢消费 |
| stateIn/shareIn | 转热流 | scope+started 必填 |
| onStart/onCompletion | 生命周期钩 | 可 emit |
| take/takeWhile | 截断 | 条件流 |
| scan | 累计 | 折叠历史 |
| merge | 多流交织 | 保序无 |

## 75. Hilt 注解速查

| 注解 | 标在哪 | 说明 |
| --- | --- | --- |
| `@HiltAndroidApp` | Application | 全树根 |
| `@AndroidEntryPoint` | Activity/Fragment/View | 注入宿主 |
| `@HiltViewModel` | ViewModel | + @Inject 构造 |
| `@Module @InstallIn` | 提供模块 | 作用域装哪 |
| `@Provides` | fun | 新实例（第三方/接口多实现） |
| `@Binds` | abstract fun | 实现绑定（推荐） |
| `@Singleton` | 与 InstallIn(Singleton) 配 | Application 存活 |
| `@ActivityRetainedScoped` | 跨旋转 | 配置变更存活 |
| `@ViewModelScoped` | VM 内共享 | 同 VM 单实例 |
| `@ApplicationContext` | 参数限定 | 只在 DI 层 |
| `@Named("x")` | 区分同型 | 语义名优先 |

## 76. Room 注解速查

| 注解 | 用途 |
| --- | --- |
| `@Entity(tableName, indices)` | 表声明 |
| `@PrimaryKey(autoGenerate)` | 主键 |
| `@ColumnInfo(name, defaultValue)` | 列定制 |
| `@Embedded(prefix)` | 平铺嵌套对象 |
| `@Relation(parentColumn, entityColumn)` | 对象图（1:N, N:M via junction） |
| `@Dao` + `@Query/@Insert/@Update/@Delete` | 数据操作 |
| `@Transaction` | 事务（含级联读一致） |
| `@Fts4` | 全文索引表 |
| `@Database(version, exportSchema)` | 库声明 |
| `@TypeConverter` | 类型映射（枚举/日期） |
| `AutoMigration(from,to)` | 自动迁移（纯加列/表） |

## 77. Intent 速查

| 目的 | 写法 |
| --- | --- |
| 打电话（拨号盘） | `Intent(ACTION_DIAL, "tel:10086")` |
| 发短信页 | `ACTION_SENDTO, "smsto:10086"`（要号码必须 sendto） |
| 网页 | `ACTION_VIEW, "https://..."` |
| 分享文本 | `ACTION_SEND, type text/plain, EXTRA_TEXT` |
| 拍照 | `ACTION_IMAGE_CAPTURE` + `MediaStore` URI |
| 安装 APK | `ACTION_VIEW` + `application/vnd.android.package-archive` + FLAG_GRANT_READ |
| 市场评分 | `market://details?id=pkg`（捕获 ActivityNotFound 兜底网页） |
| 设置详情页 | `ACTION_APPLICATION_DETAILS_SETTINGS, "package:pkg"` |
| 无障碍设置 | `ACTION_ACCESSIBILITY_SETTINGS` |

## 78. 生命周期回调速查

Activity 全序（正常走到销毁）：

```text
onCreate → onStart → onResume → [运行]
→ onPause → onStop（不可见） → onDestroy
旋转：onPause → onStop → onSaveInstanceState(与 onStop 顺序版本相关) 
  → onDestroy → onCreate(带 savedInstanceState) → onStart → onRestoreInstanceState → onResume
```

Fragment 视图与实例分离点：

```text
onAttach → onCreate → onCreateView → onViewCreated → onStart → onResume
→ onPause → onStop → onDestroyView（实例还在！视图没了）
→ onDestroy → onDetach
返回栈保留（FragmentManager）：只走 view 销毁段，实例存活
```

- 教训行：任何在 `onDestroyView` 后仍被持有的 view 引用 =
  Fragment 视图泄漏（`FragmentViewLifecycle` 的 watch out 点）。

## 79. 通知 API 速查

```kotlin
val n = NotificationCompat.Builder(ctx, "updates")
    .setSmallIcon(R.drawable.ic_notify)
    .setContentTitle(title)
    .setContentText(body)
    .setAutoCancel(true)
    .setContentIntent(pendingIntent)   // FLAG_IMMUTABLE 必须（31+）
    .setPriority(PRIORITY_DEFAULT)
    .build()
NotificationManagerCompat.from(ctx).notify(id, n)
```

- 渠道创建（一次）：`createNotificationChannel(NotificationChannel("updates", "更新", IMPORTANCE_DEFAULT))`
- 进行中通知（FGS）：`setOngoing(true)` + `setOnlyAlertOnce(true)`。

## 80. 权限常量表

| 组 | 权限 |
| --- | --- |
| 位置 | ACCESS_FINE / ACCESS_COARSE / ACCESS_BACKGROUND_LOCATION |
| 相机/麦克风 | CAMERA / RECORD_AUDIO |
| 联系人 | READ/WRITE_CONTACTS, GET_ACCOUNTS |
| 电话 | READ_PHONE_STATE, CALL_PHONE, READ_CALL_LOG |
| 短信 | SEND_SMS, READ_SMS（高危审查） |
| 存储（29+分区后） | READ_MEDIA_IMAGES/VIDEO/AUDIO（33+细粒度） |
| 传感器 | BODY_SENSORS, ACTIVITY_RECOGNITION |
| 通知 | POST_NOTIFICATIONS（33+，运行时） |
| 特殊 | SYSTEM_ALERT_WINDOW / SCHEDULE_EXACT_ALARM / ACCESSIBILITY（专设置页） |

## 81. 前台服务类型表（targetSdk 34+）

| type | 对应权限 | 场景 |
| --- | --- | --- |
| `camera` | FOREGROUND_SERVICE_CAMERA | 相机连拍/滤镜 |
| `connectedDevice` | ...DEVICE | 车机/穿戴 |
| `dataSync` | ...DATA_SYNC | 云盘同步 |
| `health` | ...HEALTH | 健康监测 |
| `location` | ...LOCATION + 定位权限 | 导航/轨迹 |
| `mediaPlayback` | ...PLAYBACK | 播放器 |
| `mediaProjection` | ...PROJECTION | 录屏 |
| `microphone` | ...MICROPHONE | 通话录音 |
| `phoneCall` | ...PHONE_CALL | VoIP |
| `remoteMessaging` | ...REMOTE_MESSAGE | 消息推送处理 |
| `shortService` | （无需专权限，≤3min） | 快速收尾 |
| `specialUse` | ...SPECIAL_USE + 声明用途 | 兜底（Play 审查要说明） |

## 82. adb 模拟速查

| 目的 | 命令 |
| --- | --- |
| 旋转 | `adb shell settings put system accelerometer_rotation 0` 后 `content insert --uri content://settings/system --bind name:s:user_rotation --bind value:i:1` |
| 撤权限 | `adb shell pm revoke pkg android.permission.CAMERA` |
| 看运行时授权 | `adb shell dumpsys package pkg \| grep -i granted` |
| 压 Doze | `adb shell dumpsys deviceidle force-idle` |
| 模拟返回键 | `adb shell input keyevent 4` |
| 点击坐标 | `adb shell input tap 540 1200` |
| 输入文本 | `adb shell input text "hello"`（空格用 %s） |
| 截屏 | `adb exec-out screencap -p > s.png` |
| 杀进程（测冷启） | `adb shell am force-stop pkg` |
| 只允许省电 | `adb shell dumpsys battery set level 15` |

## 83. ProGuard 语法

```proguard
-keep class com.x.Dto { <fields>; }      # keep 类与字段
-keepclassmembers class com.x.Msg {      # 仅成员（类本身可被混淆）
    <fields>;
    void onEvent(**);                    # 反射回调方法
}
-keepnames class com.x.**                # 防重命名（栈可读）
-dontwarn okhttp3.**                     # 定点豁免（少用）
-printusage usage.txt                    # 裁剪清单
```

- 规则冲突时：keep 联合生效（更宽者赢）—— 审计以最终 mapping 为准。

## 84. Gradle DSL 片段

```kotlin
android {
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        create("staging") { initWith(getByName("debug")); matchingFallbacks += listOf("debug") }
    }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.activity.compose)
    debugImplementation(libs.leakcanary)
}
```

## 85. 版本矩阵

| Android API | 对应 Kotlin 惯例 | 备注 |
| --- | --- | --- |
| 26 (O) | 通知渠道 | minSdk 常见下限 |
| 29 (Q) | 分区存储 | 26→29 之间需 legacy 兜底 |
| 31 (S) | PendingIntent IMMUTABLE | 全量补 |
| 33 (T) | 通知运行时权限 | 收集用户同意新链路 |
| 34 (U) | FGS 类型强制 | 81 节表 |
| 35 (V) | 16KB 页对齐 | so 构建参数 |

工具链：AGP 8.13 / Gradle 8.14 / Kotlin 2.3（本仓库矩阵）；
KSP2 与 Kotlin 版本成对升级；Compose 编译器已并入 Kotlin（2.0+），
不再单独配 composeOptions.kotlinCompilerExtensionVersion。

## 快速索引 · 续（86-95 节）

| 想找什么 | 去哪一节 |
| --- | --- |
| 接支付 | 86. 剧本·支付 |
| 聊天列表怎么搭 | 87. 剧本·IM 会话 |
| 搜索页全套 | 88. 剧本·搜索页 |
| 设置页双向绑定 | 89. 剧本·设置页 |
| 图表选型 | 90. 剧本·图表 |
| 性能反模式 | 91. 性能反模式 |
| 协程反模式 | 92. 协程反模式 |
| Compose 反模式 | 93. Compose 反模式 |
| 本仓库模块纪律 | 94. 仓库纪律速查 |
| 知识库怎么维护 | 95. 维护规范 |

## 86. 剧本·支付

- 流程骨架（客户端只做引导，真源在服务端）：

```text
选商品 → 服务端建单（返回支付参数）
→ 拉起 SDK（参数透传）→ 回调/轮询
→ 服务端查询订单状态（不信任客户端回包）→ UI 落定
```

- 纪律：
  - 金额永远服务端算；客户端不传金额。
  - 支付结果以**服务端查询**为准（回调可伪造/丢失）。
  - 幂等：订单号防重复拉起；重复回调去重（服务端唯一索引）。
  - 弱网：轮询间隔 2s 起 ×10 次后转手动"查询中"态。
- 测试：沙箱环境全流程 + 中断注入（支付中杀 App /
  断网恢复 / 双开重入）。

## 87. 剧本·IM 会话

- 消息列表倒序：`reverseLayout = true` + `key = msgId`；
  历史加载用 `LazyListState` 到顶触发（`firstVisibleItemIndex == 0`）。
- 已读回执：UI 进入可视区收集 id → 批量上报（本地先标已读，
  乐观 UI）。
- 发送中状态：本地消息三态（sending/sent/failed）+ 重试入口；
  失败消息长按重发（幂等：同 clientMsgId 覆盖）。
- 输入区：键盘 `imePadding()`；`WindowInsets` 联动防遮挡。
- 排序键：时间戳用服务端时间（本地钟不可信）；
  同秒消息用序号二级排序。

## 88. 剧本·搜索页

- 状态机：`Idle → Typing(debounced) → Loading → Results/Empty/Error`；
  debounce 300ms（32 节配方 C）。
- 历史与热词：本地 Room（历史去重上限 20 条）+ 服务端热词接口。
- 高亮：结果渲染层 `buildAnnotatedString` 标 keyword span；
  高亮逻辑纯函数化可单测。
- 转场：搜索框 `Modifier.focusRequester()` 进页自动聚焦；
  返回清空（防脏状态复用）。

## 89. 剧本·设置页

- 双向绑定纪律：开关类控件本地 state 即时生效 + `LaunchedEffect`
  落 DataStore（写防抖 200ms 批量）。
- 大项分组渲染：`LazyColumn` + `stickyHeader`；
- 危险项（清数据/退出）二次确认（`AlertDialog`）；
  执行中不可返回（`BackHandler(enabled = busy)`）。
- 偏好迁移：老 key 读取一次后写新 key 删旧（一次性 Migration）。

## 90. 剧本·图表

| 需求 | 方案 |
| --- | --- |
| 折线/柱状标准图 | Vico（Compose 原生） |
| 大数据量/交互复杂 | WebView + ECharts（67 节深水区纪律） |
| 简单进度/占比 | Canvas 手绘（`drawArc`）成本最低 |
| 地图热力 | 地图 SDK（合规评估） |

- 判据：数据点 <1k 用 Compose 组件；>1k 或要 tooltip/缩放 → WebView。
- 主题联动：图表色取 `MaterialTheme.colorScheme` 传给前端（桥接 JSON）。

## 91. 性能反模式

| 反模式 | 症状 | 替代 |
| --- | --- | --- |
| Application 同步全家桶 | 冷启动白屏久 | 异步初始化器 + 按需 |
| 主线程 SP commit | 卡顿/ANR | apply() 或 DataStore |
| 每帧创建对象（onDraw/组合） | GC 抖动 | remember/缓存实例 |
| 嵌套权重（weight 套 weight） | 多次测量爆炸 | 固定尺寸或 IntrinsicSize |
| 集合在热路径 filter 链 | CPU 白烧 | 序列化处理或索引结构 |
| 图片原尺寸解码 | OOM | Coil size/降采样 |
| 日志字符串拼接（release 也拼） | 微耗 | isLoggable 判断 + 常量 tag |
| 每项新 lambda | 列表卡 | 上提/remember |

## 92. 协程反模式

| 反模式 | 后果 | 正解 |
| --- | --- | --- |
| GlobalScope.launch | 泄漏 + 无取消 | viewModelScope/lifecycleScope |
| runBlocking 在生产 | 死锁/卡 UI | 只许测试里出现 |
| catch(Exception) 吞掉 | 取消被吞（竞态错乱） | 单独 rethrow CancellationException |
| async 里 try/catch 少 | await 时才炸，难定位 | 尽早 catch 就近处理 |
| Flow 收集放 init | 后台也在收集 | repeatOnLifecycle 限定 |
| delay 当同步屏障 | 时序脆弱 | 用事件/Channel 显式信号 |
| Mutex 内再切线程 | 死锁面扩大 | 锁内不做 IO |

## 93. Compose 反模式

| 反模式 | 后果 | 正解 |
| --- | --- | --- |
| 组合里写状态 | 无限重组/崩溃 | 副作用 API |
| remember 键缺失 | 旧数据残留 | remember(dep){} 键控 |
| 传 ViewModel 进 Composable | 测试性归零 | 传 state + lambda（stateless 化） |
| Modifier 公共串复制粘贴 | 顺序坑复现 | 抽 @Composable 修饰符工厂 |
| Dialog 内部持有逻辑 | 状态重复 | 状态上提，Dialog 只渲染 |
| key 用 index | 增删错位 | 稳定业务 id |
| AnimatedVisibility 套列表项 | 每项动画开销 | 只对当前项动画 |

## 94. 仓库纪律速查（本仓库特有）

- 模块边界：core（纯 JVM）/ platform（系统能力）/ app（组装）；
  依赖单向 core ← platform ← app，反向引用评审一票否决。
- 工具注册：ToolModule 家族文件按 § 编号分区；新工具先查
  `check tool ID uniqueness`（CI 静态步骤）。
- 文件预算：main ≤1200 / test ≤1600；超限拆文件不提额。
- i18n：values 与 values-zh 的 strings_* 键 1:1 镜像（CI string mirror）。
- 注释纪律：KDoc 里不得出现 MIME 通配之类的 `*斜杠` 序列
  （`check_comment_integrity.py` 会拦）。
- 提交规范：`type(scope): 摘要`；代码 PR 带 .changelog 片段。

## 95. 维护规范

本知识库的演化规则：

- 结构三段式恒定：必看内容 / 快速索引 / 编号章节 ——
  知识库服务（KnowledgeBaseService）按此解析，新增节保持编号递增。
- 内容大改 version+1 并在必看内容区顶部加"本版变更"三行摘要。
- 每条坑/配方必须有"症状→根因→正解"三要素之一完整，
  拒绝"注意 XX"式空话。
- 与仓库强相关的纪律（如 94 节）随仓库 AGENTS.md 同步演化；
  通用 Android 知识以官方文档为准绳，版本号标注参考时点。
- 行数下限 2000：不是凑数 —— 索引覆盖全部章节（缺的章节删或补），
  每节信息密度优先于篇幅。

## 快速索引 · 续（96-102 节）

| 想找什么 | 去哪一节 |
| --- | --- |
| 状态 API 用哪个 | 96. 状态 API 对照 |
| ViewModel 花式用法 | 97. ViewModel 进阶 |
| WorkManager 参数全表 | 98. WorkManager 速查 |
| OkHttp/Retrofit 全表 | 99. 网络速查 |
| 渠道重要性对照 | 100. 通知重要性表 |
| 测试注解速查 | 101. 测试注解速查 |
| 所有清单的总目录 | 102. 清单总目录 |

## 96. 状态 API 对照

| API | 场景 | 旋转存活 | 进程死恢复 |
| --- | --- | --- | --- |
| `mutableStateOf` + remember | 组合内瞬态 | ✗ | ✗ |
| `rememberSaveable` | UI 输入 | ✓ | ✓（Bundle 型） |
| `SavedStateHandle` | VM 内路由参数 | ✓ | ✓ |
| `StateFlow` in VM | 屏级业务态 | ✓ | 冷启动重建 |
| repository 单源 | 跨屏共享 | ✓ | 持久层决定 |
| `derivedStateOf` | 派生值 | 随依赖 | 随依赖 |

- 混合判据（自上而下第一命中者赢）：
  ① 跨屏？→ 单源；② 进程死要恢复？→ saveable/handle；
  ③ 旋转要活？→ VM；④ 都不用 → remember。

## 97. ViewModel 进阶

- 构造瘦身：参数只进依赖（repo/用例），不进 UI 值；
  UI 值走方法参数或 SavedStateHandle。
- VM 间共享：**不要**互相引用 —— 通过共享 repository 单源达成；
  真要事件级联动，用顶层 `SharedFlow`（Application scope 持有）。
- 测试替身：VM 直接 new（Hilt 不进单测）；
  `MainDispatcherRule` 注入测试调度器（35 节）。
- AndroidViewModel(context) 只给真的需要 ctx 的场景
  （通知/资源），且拿 application 上下文。

## 98. WorkManager 速查

| 项 | API/值 | 说明 |
| --- | --- | --- |
| 一次性 | `OneTimeWorkRequestBuilder<T>()` | setInitialDelay/约束 |
| 周期 | `PeriodicWorkRequestBuilder<T>(1, TimeUnit.HOURS)` | 最小 15 分钟 |
| 加急 | `setExpedited(OUT_OF_QUOTA_AS_INITIATED_WORK)` | 12+ 走加急通道 |
| 唯一 | `enqueueUniqueWork(name, KEEP/REPLACE/APPEND, req)` | 防重复入队 |
| 链 | `beginWith(a).then(b).then(c).enqueue()` | 失败断链 |
| 输入 | `Data.Builder().putString("k", v).build()` | ≤10KB |
| 进度 | `setProgress(Data)` + `WorkInfo.progress` | UI 观察 |
| 观察流 | `getWorkInfosForUniqueWorkFlow(name)` | Flow API |
| 测试 | TestDriver + workManagerTestInit | setInitialDelay=0 放行 |
| 结果标签 | `addTag("sync")` | 批量取消 `cancelAllWorkByTag` |

## 99. 网络速查

| 项 | 惯用值/写法 |
| --- | --- |
| 超时 | connect 10s / read 30s（弱网放宽 read） |
| 拦截器 | 应用层先于网络层；日志 release 降级 BASIC |
| Retrofit 接口 | `@GET("path") suspend fun f(@Query("q") q: String): Resp` |
| 序列化 | kotlinx-serialization converter（同仓技术栈） |
| 上传 | `@Multipart @Part("file") file: MultipartBody.Part` |
| 下载进度 | OkHttp 进度拦截器 + ResponseBody 包装 |
| 缓存 | `Cache(ctx.cacheDir, 20MB)` + `CacheControl` header 协商 |
| 证书固定 | CertificatePinner（评估轮换） |
| mock | MockWebServer（`testImplementation`） |

## 100. 通知重要性表

| Importance | 行为 | 用途 |
| --- | --- | --- |
| HIGH (4) | 弹横幅 + 响声 | 消息/提醒 |
| DEFAULT (3) | 响声不横幅 | 常规更新 |
| LOW (2) | 静默 | 策略性后台 |
| MIN (1) | 无提示仅列表 | 纯状态（同步中） |
| NONE (0) | 完全静默 | 特殊（少用） |

- 渠道创建后重要性不可代码改（用户主权）——
  起名分组要让用户在设置里能自主管理。

## 101. 测试注解速查

| 注解/规则 | 用途 |
| --- | --- |
| `@Test` + `runTest` | 虚拟时间协程测试 |
| `MainDispatcherRule` | 替换 Main 调度器（自定义 Rule） |
| `TemporaryFolder` | 文件类测试 |
| `@RunWith(AndroidJUnit4)` | Robolectric/仪器 |
| `@get:Rule composeTestRule` | Compose UI 测试 |
| `createComposeRule()` | 无 Activity 组合测试 |
| `androidx.test.ext.junit.runners.AndroidJUnit4` | 真机 |
| `advanceUntilIdle()` | 排空虚拟任务 |
| `onNodeWithText/onNodeWithTag` | 查找节点 |
| `performClick/performScrollTo` | 交互 |
| `assertIsDisplayed/assertDoesNotExist` | 断言 |

## 102. 清单总目录

分散在 37-42/70/91-93 节的检查项，发布前总走查：

- 性能（37/91）：启动/帧率/内存/包体四段度量
- 安全（38/66）：传输/存储/导出面/日志四级
- 无障碍（42）：语义/触控/对比度/TalkBack
- i18n（43/55）：镜像键/RTL/伪语言截图
- 厂商（39）：自启/通知渠道/Doze 三态
- 发布（70）：mapping/权限 diff/灰度计划
- 反模式三表（91-93）：性能/协程/Compose —— 评审时对照勾
- 仓库特有（94）：模块边界/文件预算/提交规范

知识库使用回执（给 Agent 的元指令）：完成任务后如某节救了你，
在回复里点名该节 —— 供知识库 vNext 评估扩写优先级。

## 快速索引 · 续（103-108 节）

| 想找什么 | 去哪一节 |
| --- | --- |
| 架构深度问答 | 103. 深度问答·架构 |
| 并发深度问答 | 104. 深度问答·并发 |
| UI 深度问答 | 105. 深度问答·UI |
| 系统机制问答 | 106. 深度问答·系统 |
| 全库反向速配 | 107. 场景速配卡 |
| 集合选型 | 108. 实用数据结构 |

## 103. 深度问答·架构

| 问 | 一句话答案（展开见对应节） |
| --- | --- |
| 为什么 ViewModel 旋转不丢？ | 配置变更只销毁 Activity，ViewModelStore 存活于 NonConfigurationInstances（15 节表） |
| 单 Activity 好在哪？ | 跨页共享作用域统一、转场可控、无多 Activity 栈管理的坑（2 节） |
| repository 为什么要单源？ | 多份"真相"是状态漂移之源；测试也要单源可 Fake（5 节表） |
| DTO 为什么不出 data 层？ | 展示模型绑上传输格式后，接口改版牵动 UI（19 节分层） |
| 依赖注入省掉什么？ | 手写工厂的装配漂移与测试替换成本（11 节） |
| 模块化的第一判据？ | 编译时间与职责边界，不是层级好看（2 节） |
| 状态提升到哪层为止？ | 最高共享方所在层；再高就是过度设计（96 节判据） |
| UseCase 层要不要？ | 小项目直接 repo→VM；逻辑复用 ≥2 处再抽 |
| 接口回调和 Flow 怎么选？ | 新代码一律 Flow；桥遗留回调用 callbackFlow（73 节） |
| 为什么禁止 God file？ | review 不可读、合并冲突面大（仓库 94 节预算纪律） |

## 104. 深度问答·并发

| 问 | 一句话答案 |
| --- | --- |
| 取消怎么传播？ | 父 Job.cancel 递归子级；阻塞调用不响应 —— 换挂起 API（9 节） |
| supervisor 何时用？ | 多任务互不连坐（列表多请求）；VM 的 scope 自带 supervisor（9 节） |
| StateFlow 与 SharedFlow 本质差？ | 有无"当前值"语义；事件不要用 StateFlow（10 节） |
| collectAsStateWithLifecycle 何时必要？ | 后台页面停止收集省资源；小流差异不大（10 节） |
| Mutex vs synchronized？ | 协程互斥可挂起；synchronized 阻塞线程（73 节表） |
| flatMapLatest 防什么？ | 旧请求竞态覆盖新结果（88 节搜索） |
| withContext 返回后在哪线程？ | 调用时所在的上下文（通常 Main） |
| 并发上限怎么控？ | Semaphore 或队列；外部 API 限流场景（73 节） |
| 为什么禁止 runBlocking 生产？ | 阻塞调用线程 + 死锁面（92 节反模式） |
| 计时器怎么做？ | `flow { while(true){ emit; delay } }` 或 ticker；组合生命周期停收 |

## 105. 深度问答·UI

| 问 | 一句话答案 |
| --- | --- |
| 重组为什么跳不过？ | 参数不稳定 → 边界失效放大范围（6 节三招） |
| Modifier 顺序的本质？ | 装饰器管线，先应用者在外层包后者（7 节） |
| remember 与 rememberSaveable？ | 后者接 Bundle（进程死/系统回收也恢复）（96 节表） |
| LazyColumn 怎么知道复用？ | key 定位 + contentType 分池（62 节） |
| 焦点顺序谁定？ | 组合顺序即遍历序；自定义走 semantics（42 节） |
| 深色模式颜色怎么组织？ | 双资源目录 + 主题单源，禁止散落硬编码（26 节） |
| 字体缩放怎么做健壮？ | 全 sp + 容器自适应（heightIn 不写死）（43 节） |
| 动画卡在半路怎么办？ | Animatable.snapTo 收尾；可见性用 AnimatedVisibility 管生命周期（25 节） |
| 为什么组合里不能写状态？ | 组合可能随时重放，副作用会放大（4 节心智） |
| preview 怎么不连真数据？ | PreviewParameterProvider 注入样例（35 节） |

## 106. 深度问答·系统

| 问 | 一句话答案 |
| --- | --- |
| Doze 到底限制什么？ | 网络禁用 + 任务推迟到维护窗口（18 节） |
| 前台服务为何必须常驻通知？ | 用户主权：可见的后台才被允许（81 节） |
| 分区存储后还能碰相册吗？ | 走 MediaStore/SAF，不能裸路径读别人目录（29 节表） |
| PendingIntent 为什么要 FLAG_IMMUTABLE？ | 防外部改写 intent 载荷（31+ 强制）（79 节） |
| 应用冷启动系统做了什么？ | zygote fork → Application → Activity（61 节四段） |
| 进程优先级谁定？ | 前台>可见>服务>缓存，按此回收（18 节） |
| Bundle 传大对象会怎样？ | TransactionTooLargeException（28 节表） |
| 配置变更不重启怎么声明？ | android:configChanges 定向声明 —— 但 Compose 时代基本不需要（16 节互操作例外） |
| 16KB 页对齐影响谁？ | so 库的链接参数，模拟器跑不出（35 版）（85 节矩阵） |
| 包可见性是什么？ | 30+ 默认看不见其他包，要 <queries> 声明（29 节表） |

## 107. 场景速配卡（全库反向索引）

把"我现在遇到 X"直接映射到节号：

| 症状 | 节 |
| --- | --- |
| 白屏/启动慢 | 36, 61 |
| 列表卡 | 6, 62, 91 |
| 状态丢失 | 5, 15, 96 |
| 后台不跑 | 13, 18, 65 |
| 通知不显 | 21, 100 |
| 权限被拒路径 | 17, 80 |
| 网络错/弱网 | 19, 64 |
| 图片问题 | 20, 63 |
| 内存泄漏 | 63, 92 |
| 崩溃速查 | 28, 56 |
| 布局/MOdifier 顺序 | 7, 72 |
| 导航/深链 | 8, 54 |
| 协程取消/竞态 | 9, 32, 92 |
| Flow 用法 | 10, 74 |
| DI 报错 | 11, 75 |
| Room 迁移 | 12, 33, 76 |
| 定时任务 | 13, 65, 98 |
| 测试怎么写 | 24, 35, 101 |
| 动画 | 25, 46 |
| 主题/深色 | 26, 43 |
| 安全 | 27, 38, 66 |
| 厂商 | 39, 65 |
| 版本差异 | 29, 85 |
| 发布 | 30, 41, 70 |
| 反模式速查 | 91, 92, 93 |
| 本仓库纪律 | 94 |

## 108. 实用数据结构

Android 日常的集合/结构选型（比算法题更常用）：

| 场景 | 结构 | 理由 |
| --- | --- | --- |
| 去重 + 查存在 | Set（LinkedHashSet 保序） | O(1) 判在 |
| 频次统计 | HashMap<K,Int> | 计数模式 |
| 消息 id → 消息 | HashMap + 双向列表（LRU） | 最近访问 |
| 固定窗口并发计数 | AtomicInteger/AtomicLong | 无锁 |
| 事件总线去重 | CopyOnWriteArraySet | 读多写少 |
| 顺序敏感遍历 | List（不可变 ImmutableList） | Compose 稳定性 |
| key-value 常量 | enum / const object | 编译期 |
| 大量字符串拼接 | buildString | 避免中间 String |
| 分组 | groupBy（结果 Map<K,List>） | 一行完成 |
| 排序稳定 | sortedBy（Kotlin 默认稳定） | UI 列表预期一致 |

- 陷阱：`ArrayList` 直接暴露给 Composable 会破坏稳定性 ——
  出口处 `toImmutableList()`（kotlinx-collections-immutable）。

## 快速索引 · 续（109 节）

| 想找什么 | 去哪一节 |
| --- | --- |
| 屏幕三态渲染完整模板 | 109. 三态渲染模板 |

## 109. 三态渲染模板（完整可抄）

屏幕级 Loading/Content/Error 的标准实现，新页面直接套：

```kotlin
@Composable
fun ProfileScreen(
    vm: ProfileViewModel,
    onBack: () -> Unit,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val retryLabel = stringResource(R.string.retry)

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.profile)) }, navigationIcon = { BackButton(onBack) }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                is UiState.Loading -> FullScreenLoading()
                is UiState.Error -> ErrorPane(
                    message = s.message,
                    canRetry = s.retry,
                    onRetry = { vm.retry() },
                )
                is UiState.Data -> ProfileContent(
                    profile = s.value,
                    onEdit = vm::startEdit,
                )
            }
        }
    }
}

@Composable
private fun FullScreenLoading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ErrorPane(message: String, canRetry: Boolean, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(message, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        if (canRetry) {
            Spacer(Modifier.height(16.dp))
            Button(onClick = onRetry) { Text(stringResource(R.string.retry)) }
        }
    }
}

@Composable
private fun ProfileContent(profile: Profile, onEdit: () -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "header") { HeaderCard(profile) }
        items(profile.recentOrders, key = { it.id }, contentType = { "order" }) { order ->
            OrderRow(order)
        }
        if (profile.recentOrders.isEmpty()) {
            item(key = "empty") { EmptyHint() }
        }
        item(key = "edit") {
            OutlinedButton(onClick = onEdit, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.edit))
            }
        }
    }
}
```

要点回顾：

- `collectAsStateWithLifecycle`（后台停收集）；`when` 分支用 `is` 智能转换。
- 列表 key 全量稳定 id；空态显式 item，不靠"恰好没有"。
- 事件（toast）走 SharedFlow + snackbarHostState，不混进 UiState。
- 本模板与 31 节配方 A、96 节状态表配套使用。
