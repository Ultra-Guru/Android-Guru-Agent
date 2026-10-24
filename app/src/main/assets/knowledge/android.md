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
  强跳过模式（Compose 编译器随 Kotlin 2.0.20+ 默认开）不豁免稳定性纪律：
  不稳定参数退化为按 `===` 实例比较，热路径每帧新建 List/lambda 依旧炸重组
  —— 热路径出口仍要 ImmutableList/remember（6 节）。
- **协程纪律**：`viewModelScope` 是所有协程的父锚点；IO 切 `Dispatchers.IO`；
  网络请求永不阻塞调用线程；`StateFlow` 对 UI、`SharedFlow` 对事件；
  收集必须生命周期感知（`repeatOnLifecycle`）。
- **性能红线**：主线程禁止 IO/解码/JSON 解析（StrictMode 会咬你）；
  `RecyclerView/LazyColumn` 里禁止创建新对象级 lambda（remember 化）；
  启动路径禁止同步磁盘读；大图必须降采样；release 必须配 Baseline Profile
  并用 Macrobenchmark 对照验证（官方口径 ~30% 首启收益是测出来的，
  不是升级白送的 —— 22 节）。
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

## 3. Kotlin×Android 交界工程

Kotlin 语言特性落到 Android 运行时的交界成本账（语法不赘述，只给工程判据）：

- **`WhileSubscribed(5000)` 的完整契约**：最后一个订阅者离开后 5 秒才停上游 ——
  "停"等于上游被取消（在途网络请求也被捘）；窗口内回到前台则原订阅续命
  （不重启）。代价模型：0 = 謢屏即重启（数据闪）；5000ms = 覆盖配置变更窗口；
  `Lazily` = 首订阅后永不停（后台仍跑，耗电）。参数不是抄来的，
  是"配置变更窗口 vs 上游存活成本"的权衡。
- **主线程安全契约**：suspend 函数要么内部自切 `withContext(Dispatchers.IO)`
  （调用方零心智），要么文档里写明 main-safe: false。分层纪律：
  Repository 对外一律 main-safe，VM 层不写 withContext。
- **调度器注入**：构造器注入 `CoroutineDispatcher`（Hilt 用 `@IoDispatcher`
  限定符），生产给 IO、测试给 StandardTestDispatcher —— 把 `Dispatchers`
  单例写死在可测代码里是测试卡死的根因（kotlin 库 52 节）。
- `lifecycleScope` 只用于和生命周期同寿的一次性动作（进页预取）；
  长寿逻辑一律 `viewModelScope` —— scope 的取消语义就是资源回收语义。
- **序列化交界**：kotlinx-serialization 生成的编解码器是编译期代码，
  无反射路径；但类型安全导航的 route 类是"仅反射引用"，
  release 会被 R8 裁掉（kotlinx.serialization#2825 实案）→ route 显式 keep。
- **`@JvmInline value class` 交界**：进泛型/可空位即装箱，性能收益归零；
  Android 侧只用作"语义标签"（防裸 Long 传错参），别当性能优化用。

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

分层不是背表格，是"状态归属的成本决策"：

- **状态持有者（state holder）类**：一屏瞬态超过 3 个变量时，停止散装
  `remember`，收敛成普通类 + `remember { ScreenStateHolder() }`：
  可 JVM 单测、字段变更点集中、迁移不碎。持有者不碰 Android 类型是硬条件。
- **提升判据**（自下而上找最低共享方）：两个 composable 读写同一状态 →
  提到最近公共父级；跨屏才上 ViewModel；跨 feature 才上 repository。
  多提一层 = 多一份管道与测试面；少提一层 = 双源漂移。
- **`rememberSaveable` 的边界**：值必须能进 Bundle（原语/String/Parcelable），
  自定义类型给 Saver（96 节）；越界内容（大列表/位图）归 VM/Repo ——
  Bundle 超限是 `TransactionTooLargeException` 的近亲。
- **事件/状态二分**：UiState 是"现在长什么样"（可重放、幂等渲染）；
  事件是"发生过一次的事"（消费即消失）。事件混进 StateFlow 会出现
  "同值不重发 → 第二次点击没反应"；事件走 SharedFlow(replay=0) 或 Channel。
- **可跳过性与读取粒度**：谁读了状态谁所在 scope 重组 —— 状态读取
  下放到叶子节点，比打 stability 注解更硬的优化。`derivedStateOf` 只适合
  "读高频写低频"的派生值（滚动阈值类）；读少写多场景用它反添一层订阅。
- 反模式：公开 `MutableState`（外部可写 = 双源）；state holder 里存
  `Context`/`Activity` 引用（泄漏 + 不可测）；导航参数存组合内状态
  而不是 SavedStateHandle（进程死亡丢参数）。

## 6. Compose 性能

- **强跳过模式（Strong Skipping，Compose 编译器随 Kotlin 2.0.20+ 默认开）**：
  ① 带**不稳定**参数的可重组函数也可跳过，但比较退化为**实例相等 `===`**；
  ② 组合内 lambda 自动 memoization（等价自动 remember）。
  后果链：每帧新建 `listOf(...)`/新对象 → `===` 不等 → 照样全量重组。
  强跳过救的是"类型系统标不出稳定但实例不变"的场景，救不了"每帧新实例"。
- 强跳过时代的稳定性优先级重排：
  ① 先消灭热路径新实例（上提创建、remember 化、状态读取下沉）；
  ② `ImmutableList`（kotlinx-collections-immutable）仍然值 —— 稳定类型
     可直接短路比较，免交 `===` 失败后的逐字段比较税；
  ③ `@Stable`/`@Immutable` 或 `compose_compiler_config.conf` 配置文件
     （对三方不可变类批量声明稳定，侵入最小）。
- 列表三件套不变：`key = { it.id }`（增删定位）、`contentType`（复用池分桶）、
  item 参数收窄（别把大 state 整个传进 item）。
- 检测闭环：Compose 编译器报告（`-P plugin:androidx.compose.compiler.plugins.kotlin:reportsDestination=`）
  + Layout Inspector 重组高亮 + Macrobenchmark frame 指标；只看报告不跑基准
  = 优化想象，只跑基准不看报告 = 盲猜。
- `movableContentOf`：同一内容在层级间搬家不丢状态（拖拽/双栏切换）；
  它是"身份搬运"不是实例共享，别当缓存用。分页大列表归 Paging3（34 节）。
- debug 构建的 LeaksCanary/检查器会拖帧率 —— 性能结论一律 release
  （或 profiling 构建）测出来才算数。

## 7. Compose 布局

- `Modifier` 顺序语义的高级版：每个 Modifier 节点在**测量/布局/绘制三阶段
  各挂一段回调**，所以 `drawBehind` 能读到最终 size、`layout {}` 能改写
  测量与放置 —— 顺序不是"谁包谁"的玄学，是回调挂载顺序。
- **Constraint 传递模型**：父传子 `Constraints(minW..maxW, minH..maxH)`。
  自定义布局三大雷：① 子忽略 min 约束（父要最小宽时给了 0 → 错位）；
  ② `fillMaxWidth()` 与 `weight(1f)` 同节点打架（weight 在父容器测量期
  才生效）；③ 无限约束（横向滚动里再竖向 fillMax 会塌）。
- **`Layout` vs `SubcomposeLayout`**：`Layout` 单次测量最便宜；
  `SubcomposeLayout` 能按需子组合（只组合存在的槽位），但子组合 = 多趟
  组合，热路径慎用 —— 每帧重复 subcompose 是卡顿常客。
- Intrinsic（固有尺寸）：`Modifier.height(IntrinsicSize.Min)` 触发额外测量轮；
  基线对齐（`alignByBaseline`）也走 intrinsic —— 列表项里大面积用要度量。
- 嵌套滚动：`verticalScroll` 父级再套 `LazyColumn` = 双滚动体手势打架；
  正解 `nestedScroll(NestedScrollConnection)` 透传剩余滚动量（62 节），
  或 `Modifier.scrollable` 自管手势。
- 跨子对齐用自定义 `AlignmentLine`（文本基线/分隔线对齐），
  别在父层手动算 offset —— 那是 View 时代老路。

## 8. Compose 导航

- Navigation-Compose 2.8+ 支持类型安全路由（`@Serializable` 对象做 route）；
  老字符串路由仍在大量代码里 —— 新代码一律类型安全。
- **类型安全路由的两个已知深坑**：
  ① route 类忘了 `@Serializable` —— 导航时才炸
  （data class 默认不可序列化，注解是显式 opt-in）；
  ② R8 裁剪：route 类在代码里"仅反射引用"，
  release 会被 shrinker 干掉（kotlinx.serialization#2825 实案）——
  route 显式 keep + release 冒烟全量深链（83 节）。
- 可选参数用默认值（`@Serializable data class Route(val id: String? = null)`）；
  带默认值的字段序列化器有特殊处理 —— `encodeDefaults=false` 环境
  下导航参数仍会写出（nav 自己管编解码，别拿业务 Json 配置去套）。
- 传参三原则：小数据走路由参数（原始类型），大对象走
  `ViewModel + SavedStateHandle` 取 repository，禁止把对象序列化进路由。
- 返回结果：`savedStateHandle` 的 `getStateFlow("key")` 双向回传
  （旧 `previousBackStackEntry` 模式仍可用但生命周期更脆）。
- 深链：`manifest` 的 `intent-filter` 与 NavDeepLink 声明保持同源，
  测试深链必须真 adb intent 触发，不能只跑 Compose 测试。

## 9. 协程结构化并发

- 树形规则之上先记一条：**取消 = 抛 `CancellationException`**，
  所以 `catch (e: Exception)` 会吞掉取消 —— 每个 catch 的第一行
  rethrow `CancellationException`（49 节清单同款纪律）。
- **`NonCancellable` 的正确使用面**：`finally` 里必须完成的善后
  （回滚事务、解锁、审计日志）包 `withContext(NonCancellable) { }`。
  注意 `mutex.withLock` 是可取消等待 —— 取消时锁已释放，finally 里再碰
  临界区资源就是裸奔，这是"取消泄漏"的标准形态。
- 启动模式差异（`CoroutineStart`）：`DEFAULT`（立即调度）→ `LAZY`
  （手动 start，配 join 防漏启动）→ `ATOMIC`（启动前不可取消，防竞态丢任务）
  → `UNDISPATCHED`（当前线程直接跑到首个挂起点）。
- 阻塞调用的三条出路（按优先级）：挂起版 API → `runInterruptible { }`
  （把阻塞 IO 变成可取消，代价是线程 interrupt）→ 独立池 + 文档写明。
  `viewModelScope` 是 SupervisorJob + Main：单请求失败不连坐兄弟，
  但 scope 本身仍会把异常往上抛 —— 业务侧就近 catch 是你自己的事。
- `supervisorScope` 细节：子失败不上抛兄弟，但 **scope 自身继续向上抛**
  （supervisor 只隔离不吞）—— "失败要知道但流程继续" = 子 launch +
  try/catch 就地消化。
- 超时分层：`withTimeout` 抛 `TimeoutCancellationException`（会真取消内部块）；
  网络重试组合 `retryWhen` + 指数退避 + 抖动，别裸 for 循环。

## 10. Flow 收集模式

```kotlin
// 生命周期感知收集（页面标准式）
lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
    vm.uiState.collect { render(it) }
}
// Compose 里等价物
val state by vm.uiState.collectAsStateWithLifecycle()
```

- `repeatOnLifecycle` 的机制账：每次进 STARTED 起新协程、退 STOP 取消
  —— 上游被反复重启是特性不是 bug。用它收冷流 = 每次回前台重拉；
  "只拉一次"的数据应在 VM 层 stateIn 转热，别和生命周期打架。
- **事件三选一**（一次性语义）：`SharedFlow(replay=0, extraBufferCapacity=1,
  DROP_OLDEST)`（广播、可丢）；`Channel` + `receiveAsFlow`（不丢、单消费）；
  直接 suspend 函数回值（最诚实，能用就用）。StateFlow 表达事件是反模式
  （同值吞发 → "第二次不触发"）。
- 背压：`buffer/conflate` 对 UI 无意义（UI 恒在 Main 串行）；
  它们属于"上游快生产"场景 —— 在数据层解决，别在收集层糟蹋语义。
- `collectAsState()` vs `collectAsStateWithLifecycle()`：差一个生命周期感知的
  收集重启；大流/重计算上游差距明显，小 StateFlow 可忽略 —— 但纪律统一用
  后者，评审免逐个判断。
- snapshotFlow：把 Compose 状态反向变 Flow（`snapshotFlow { scroll }`），
  读取集合被跟踪、写入帧后发射 —— 它是"状态世界→协程世界"的官方桥，
  别自己拿 observer 糊一个。

## 11. Hilt 依赖注入

- **生成机制内幕**：Hilt 在编译期为每个 Android 组件生成组件层级
  （Singleton → ActivityRetained → ViewModel → Activity → Fragment → View → Service），
  你的 Module 被织进 `_Hilt_Activity`/`DaggerXxxComponent` 的 builder；
  `@Inject` 字段注入发生在 `super.onCreate()` 的 super 调用链里 ——
  所以在 onCreate 早于 super 的代码里用注入字段会 NPE。
- **作用域纪律（作用域背压）**：`@Singleton` 持有 UI 状态 = 内存常驻 +
  测试不可替换。规则：Singleton 只放无状态服务（Repo/数据源/分析器）；
  屏级可变状态只在 `@ViewModelScoped`；跨配置变更状态用
  `@ActivityRetainedScoped` 且不持 Context。
- **辅助注入（assisted injection）**：运行时才能确定的参数（id、日期）
  不能走构造注入 —— `@AssistedInject constructor` + `@Assisted` 标注运行时参，
  再用 `@AssistedFactory` 出工厂。典型雷：把 assisted 参数硬塞进
  `@Provides`（拿不到）或用 @ApplicationContext 凑数。
- **EntryPoint**：非 Hilt 托管的类（ContentProvider、三方 SDK 回调）
  要拿依赖，定义 `interface XEntryPoint { fun get(): T }` +
  `@InstallIn(SingletonComponent::class)`，用
  `EntryPointAccessors.fromApplication(ctx, XEntryPoint::class.java)` 取。
  在 ContentProvider 里还得 `@EarlyEntryPoint`（它的初始化早于 Hilt 图）。
- **多绑定**：插件式实现集合用 `@IntoSet`/`@ElementsIntoSet`/`@IntoMap`，
  配 `@StringKey`/自定义 key —— 比手工 registry 少一个登记点。
- 测试纪律：VM 单测直接 new（Hilt 不进单测）；仪器测试
  `@HiltAndroidTest` + `HiltAndroidRule` + `@BindValue` 替换绑定；
  专项用 `@TestInstallIn` 覆盖生产 module（Fake 仓库全局生效），
  别在每个测试里散装 replacement。
- 高频报错：`DaggerMissingBinding`（接口没 @Binds/多实现没限定符）；
  旋转后状态重置（owner 错位，Dialog/BottomSheet 有自己的
  viewModelStoreOwner）；`UninitializedPropertyAccessException`（注入字段
  在 super 前访问）。

## 12. Room 持久化

- **查询计划纪律**：上索引前先看 `EXPLAIN QUERY PLAN`（Room 里用
  `db.query("EXPLAIN QUERY PLAN " + rawSql)`）—— "SCAN" 全表扫，
  "SEARCH ... USING INDEX" 才是命中。三个计划陷阱：
  ① `LIKE '%x'` 前缀通配不吃索引 → 要模糊搜索就上 FTS4/FTS5；
  ② 复合索引最左前缀（`INDEX(a,b)` 对 `WHERE b=?` 无效）；
  ③ 返回列不在索引里 → 回表，热查询给覆盖索引。
- **迁移架构**：`exportSchema = true` 把历史 schema JSON 全进版本库；
  `MigrationTestHelper` 按版本链回放（v1→v2→…→vN 每跳都要有测试，
  直跳会漏中间迁移的建表差异）。自动迁移（`AutoMigration(from,to)`）
  只适合纯加列/加表；列改名/类型变更要 `@RenameColumn`/规格文件。
- **降级策略分级**：缓存型库才允许 `fallbackToDestructiveMigration()`；
  用户数据用 `fallbackToDestructiveMigrationFrom(from, to)` 精确指定来源版本，
  或干脆不降级 + 启动时侦测旧版本备份表。
- **预打包数据库**：`createFromAsset("db/app.db")` 首次创建时拷贝；
  陷阱：资产库版本必须 ≥ 实体版本，且 Room 只在建库时拷一次 ——
  首启崩溃后二次启动不会重拷，发布前必须验证首启全流程。
- **写模式**：批量写一定 `@Transaction`（未包事务的循环 insert 每条
  一次 fsync，慢百倍）；`Flow` 查询依赖 InvalidationTracker，
  跨进程/多实例下失效通知不可靠 —— 单实例纪律。
- **KMP 方向（Room 2.7/2.8+）**：KMP 模式必须 `setDriver(SQLiteDriver)`
  （漏了直接 "Cannot create a RoomDatabase without providing a SQLiteDriver"）；
  `BundledSQLiteDriver` 把固定版本 SQLite 编进 app，各平台行为一致
  （框架驱动在 iOS 才是唯一选项）。Room 2.8 是双 API 兼容模式
  （SupportSQLite 与 Driver 并存），Room 3.0 起转向纯 Driver ——
  新代码 DAO 层别再碰 SupportSQLiteDatabase 私货。

## 13. WorkManager

- **后端内幕**：API 23+ 映射到 JobScheduler，老系统回退 AlarmManager+广播；
  你拿来的是统一 API，真正执行时受 App Standby 桶（活跃/工作集/频繁/罕见/受限）
  的配额节制 —— 桶越冷，周期任务被排得越稀。`dumpsys jobscheduler` 看真实
  排队与配额（82 节命令）。
- **加急（expedited）的配额账**：`setExpedited()` 不是"立即执行"，
  而是从滚动窗口配额里扣（按桶分级）；配额用尽自动降级普通任务 ——
  静默降级是设计，不是 bug。两个硬雷：
  ① 12 以下设备加急任务以 FGS 跑，必须实现 `getForegroundInfo()`，
  否则入队即抛 `IllegalStateException`；
  ② 持续重新入队（KEEP/REPLACE 轮询式滥用）会耗尽配额 + 耗电被系统标记。
- 唯一任务策略矩阵：`KEEP`（防重复入队，老参数继续跑）、
  `REPLACE`（新参数覆盖）、`APPEND`（链式顺序，前置失败断链）、
  `APPEND_ORIGINAL`（前置失败仍续跑）、`UPDATE`（更新参数但保留 enqueue 时序）。
- `Result.retry()` + `BackoffPolicy.EXPONENTIAL` 是任务内重试；
  与外层重试叠加会指数爆炸 —— 重试层级只允许一层。
- 观察用 `getWorkInfosForUniqueWorkFlow`/`WorkManager.getWorkInfosLiveData`，
  进度 `setProgress`（Data ≤10KB，大进度用通知或广播）；
  取消 `cancelAllWorkByTag` 要在入队时打好 tag。
- 测试：`workManagerTestInit` + `TestDriver`（`setInitialDelay=0`、
  `setAllConstraintsMet`、`setStopWork` 手动放行）；Doze/厂商限制
  只能真机验证（39 节）。周期任务最小 15 分钟是系统硬下限。

## 14. DataStore

- **写入机制内幕**：DataStore 每次写都生成新文件再原子重命名 ——
  每写一个 key 全文件重写。高频写（逐帧进度、埋点）是反模式，
  要么合并写入，要么那个数据就不该在 DataStore。
- **单例纪律**：同文件多实例直接抛 `IllegalStateException` ——
  多进程使用也不支持（没有跨进程锁）；`:remote` 进程读同一文件
  会拿到脏读/异常，跨进程配置分发走 ContentProvider 或 MESH 模式。
- **损坏恢复**：文件损坏时 `read` 抛 `CorruptionException`，
  用 `CorruptionHandler`（`ReplaceFileCorruptionHandler { emptyPreferences() }`）
  决定"清空还是崩溃" —— 用户可重建的数据选清空，
  Proto 有 schema 就能部分恢复（unknown fields 落在演进设计里）。
- **Proto vs Preferences 的真实分水岭**：不是"要不要类型"，
  而是演进 —— Preferences 加 key 零成本但无 schema 校验；
  Proto 有字段号/unknown field 机制，删除字段要保留号位，
  且 proto 生成代码带来构建依赖。选型看"这份配置要不要版本化演进"。
- **迁移纪律**：`SharedPreferencesMigration` 在首读时迁移 + 删旧文件 ——
  只能发生一次，且迁移期间读会阻塞；生产事故常客是
  "旧代码路径还在写 SP 文件"（双写竞争），迁移前必须全链路收口。
- 读取：`data.first()` 一次性、`data` Flow 响应式；写 `edit { }` 原子
  （内含读改写）。大 JSON 塞 Preferences 每次反序列化全 map —— 那该进 Room。

## 15. 生命周期感知

- **配置变更的机制账**：ViewModelStore 挂在 Activity 的
  `onRetainNonConfigurationInstance()` 上跨转屏存活；
  但**进程死亡时它彻底消失** —— 转屏活 ≠ 进程死后活。
  两个高混淆点：
  ① 开发者选项"不保留活动"≠进程死亡（只是销毁 Activity，进程还在）——
  测进程死亡用 `adb shell am kill <pkg>`（82 节命令清单）；
  ② Fragment 实例存活 ≠ 视图存活：`onDestroyView` 后 view 引用
  全是泄漏候选（listener/bind 挂 view 上的要在此清）。
- **Lifecycle 状态机**：INITIALIZED→CREATED→STARTED→RESUMED→DESTROYED
  （事件驱动、只进不退）；`repeatOnLifecycle(STARTED)` 的重启语义
  比"暂停"准确：退后台是取消，回前台是新协程。`launchWhenStarted`
  已弃用倾向的原因：它只挂起不取消 —— 协程与上游资源仍在后台持有。
- **折叠屏/多窗口**：多 resume 常态化（两个 Activity 同时 RESUMED），
  "onResume = 可见可交互"的假设失效；适配用
  `onTopResumedActivityChanged` 或 WindowLifecycleVisibilityCallback。
- **`LocalLifecycleOwner` 已搬家**：lifecycle 2.8+ 从
  `androidx.compose.ui.platform` 移到 `androidx.lifecycle.compose`，
  老 import 硬依赖会在升级时炸 —— 依赖图里同时装两个版本是常见事故。
- **SavedStateHandle 能力面**：`getStateFlow(key, default)`、
  `setSavedStateProvider`（惰性序列化大对象）；存的东西必须 Bundle 化，
  大列表/位图放不得（TransactionTooLarge）。
- `reportFullyDrawn()` 是启动指标的官方终点（TTI）—— 不调用它，
  Macrobenchmark/fused 语义里的 startup 段落就一直“没跑完”。

## 16. 互操作

- Compose 嵌 View：`AndroidView(factory, update)`；
  update 在每次重组调用 —— 状态桥要 remember 住 view 实例再 diff。
- View 嵌 Compose：`ComposeView` + `ViewCompositionStrategy`
  （默认 DisposeOnDetachedFromWindow；在 RecyclerView 里换
  `DisposeOnDetachedFromWindowOrReleasedFromPool` 防复用爆炸）。
- 混合时权限：互操作组件内部的点击监听是 View 事件，不走 Compose
  的语义（无障碍描述要在 View 上设 `contentDescription`）。

## 17. 权限模型

- **授权漏斗是产品指标**：弹窗 → 授权 → 使用中仍有效 → 不被自动撤销。
  每一段都会衰减，埋点要分段埋：拒绝后首次转向设置页的转化、
  一次性弹窗预算（每次冷启都弹 = 用户教育反噬）。
- **部分媒体访问（API 34+）**：用户可只授权部分照片 ——
  `READ_MEDIA_VISUAL_USER_SELECTED` 组合进三态判定：
  `READ_MEDIA_IMAGES`（全量）/ `VISUAL_USER_SELECTED`（部分）/
  `getGrantedPermissions()` 里两者同在即“部分模式”，UI 要提供
  "管理选择/选更多"入口（跳 photo picker 或设置页）。
  只按老权限判“没给”会把部分授权用户当成拒绝用户。
- **`shouldShowRequestPermissionRationale` 的三态**：从未问过（false）/
  问过被拒（true）/ 永久拒绝（false 且不再弹）—— 它没有“永久拒绝”
  显式标志，判永久拒绝 = 两次 false + 一次 rationale，状态自己记。
- **后台位置的特殊纪律**：先前台授权，再引导去设置页开
  `ACCESS_BACKGROUND_LOCATION` —— 顺序反了 Play 审核直接拒；
  且后台位置是分级敏感权限，声明用途文本要能过人工审。
- **自动重置（auto-reset）**：长期未用 + targetSdk 30+，
  运行时权限会被系统撤销 —— 依赖位置/通知的老场景要
  `isAutoRevoked` 检测并引导重新授权。
- while-in-use 语义：后台启动的服务即使有权限也拿不到
  位置/麦克风（31+）—— "权限给了但后台拿不到数据"的排查方向
  是生命周期不是权限（81 节 FGS）。
- 测试矩阵：`adb shell pm revoke`/`pm grant`、
  `adb shell pm reset-permissions`、部分照片模式（系统 photo picker）
  三态各跑一遍（82 节命令）。

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

- **OkHttp 内幕（影响排障方向）**：连接池默认 5 个空闲连接/5 分钟保活；
  HTTP/2 下同主机复用一条连接（多路复用）—— 大量并发同主机请求
  不会开多个 socket；`retryOnConnectionFailure` 只覆盖路由级失败
  （连接置废/SSL 握手失败），不重试非幂等的请求体。
  调 DNS/代理/证书问题先看网络拦截器层（应用拦截器看不到重试与路由）。
- **超时四层**：`connectTimeout`（TCP+TLS 握手）/`readTimeout`（两次字节间）/
  `writeTimeout`/`callTimeout`（整个调用含重试）—— 读超时不是总时长，
  慢速长下载不会触发它。弱网调优优先动 read/call，不是把 connect 拉长。
- **401 刷新的单飞纪律**：认证拦截器里 401 → 加锁单飞刷新 → 重放一次；
  必须带"已重试"标记防循环（两个 401 互刷新是死锁现场）；
  并发请求同时 401 时其他请求要等锁后复用新 token（不能各刷各的）。
- **kotlinx-serialization + R8**：编解码器编译期生成，但多态/`@JsonClassDiscriminator`
  生成的序列化器查找表是反射式可裁对象 —— release 冒烟必须过一遍
  多态 payload（34 节配方同款路径）。
- **传输层纪律**：`usesCleartextTraffic=false` + NetworkSecurityConfig
  细粒度白名单（debug 允许明文，release 不允许）；证书固定（CertificatePinner）
  评估轮换成本：域名换证书链时 pin 过期 = 全量网络不可用事故，
  新旧链并行 pin 一个周期后才可摘旧。
- 缓存：`Cache(cacheDir, 20MB)` 只对可缓存响应生效，
  服务端 Cache-Control 不给就是摆设 —— 离线优先靠本地库（Room）+
  NetworkMonitor，不要指望 HTTP 缓存。
- Ktor vs OkHttp：多平台（KMP）目标才用 Ktor；单 Android 用
  OkHttp+Retrofit 生态更厚（34 节配方集）。

## 20. 图片加载

- **Coil 内存缓存键** = URL + 尺寸 + 变换列表 —— 同图不同尺寸是两个
  缓存条目；"为什么没命中"排查先看请求 size 是否一致。
  `size()` 明确指定优于隐式（Constraint 测量不确定时隐式尺寸会落空）。
- **硬件位图（HARDWARE config）**：像素存 GPU 显存，省内存但不能
  读取像素/软件绘制 —— 任何需要 `bitmap` 操作（分享转文件、加 shader、
  保存）的请求要 `allowHardware(false)` 单独下线。
- 变换选择：圆角/圆形优先 `Modifier.clip(shape)`（渲染层裁切，
  缓存原图可复用）；`transformations()` 烘焙进位图（换形状 = 重下载重解码）。
- 大图纪律：`ContentScale.None + verticalScroll` 处理长图会解码全分辨率
  —— 超长图用子采样（BitmapRegionDecoder 思路或 TiledBitmap）；
  原尺寸解码是 OOM 头号来源不变。
- 预加载策略：`ImageLoader.enqueue(ImageRequest)` 在列表即将滚动到时
  预热内存缓存 —— 但预加载请求的 size 必须与真实渲染一致，否则预热无效。
- Coil 3.x（KMP）：跨平台网络层可插拔（okhttp/ktor fetcher），
  ImageLoader 生命周期与单例策略变了（跨平台全局默认实例）——
  升级 2→3 要过一遍 breaking changes。
- 内存压力：低 RAM 设备/后台被杀后重建，`memoryCachePolicy` 与
  `ImageLoader.Builder.memoryCache { }` 自定义策略（弱引用或按进程
  内存分档）是重度图片 app 的最后一道闸。

## 21. 通知

- **渠道的不可变铁律**：importance 创建后代码改不了，用户可改；
  删渠道再建同 id 渠道会“复活”旧设置（同 id 复用旧配置）——
  改错渠道的正解是**新 id + 迁移通知**，不是删除重建。
- **送达 != 授权 != 可见**：`areNotificationsEnabled()` 只查总开关；
  渠道被用户关、DND、省电模式都在其后 —— 关键告警类要给
  "收不到怎么办"的替代路径（角标/页面内横幅）。
- Android 13+ `POST_NOTIFICATIONS` 运行时权限：没授权时 `notify()`
  静默失败 —— 授权时机是产品决策（首启不要弹，触发点前弹）。
- **全屏意图（FSI）收紧**：Android 14+ 只有 Call/Alarm 类默认应用能
  真正全屏；其他 app 的 FSI 变成普通高优先通知 + 响铃 ——
  Play 上架还要声明用途。来电/闹钟之外的场景一律别用 FSI。
- **FGS 通知特殊性**：常驻且用户不能滑掉（系统保留区）；
  `setOngoing(true)` + `setOnlyAlertOnce(true)`；进度更新节流
  （每秒一次足够，高频 notify 会拖系统进程）。
- 事件语义：通知 id = 事件的持久身份 —— "替换式更新"用同 id，
  "累计式"用不同 id + `setGroup` 分组折叠，双列语义别混。
- 点击链路：`TaskStackBuilder` 保返回栈；PendingIntent 31+ 必须
  `FLAG_IMMUTABLE`；通知点击的深链参数当外部输入校验（77 节）。
- 测试：渠道矩阵（用户关掉 A 渠道/B 渠道）+ DND + 锁屏三态真机过；
  模拟器不显示厂商定制的不通知场景（39 节）。

## 22. 性能优化

- **Baseline Profile 工程（不是加一行依赖）**：
  ① 组成 = Jetpack 库自带的 library profile + 自家 `baseline-prof.txt`
  （Macrobenchmark `BaselineProfileRule` 从真机行为录制）；
  ② 生效链路：ProfileInstaller 在首启后台把规则交给 ART 预编译 ——
  官方口径 ~30% 首启提速，Duolingo/Meta 都报过同量级；
  ③ 验证纪律：Macrobenchmark `StartupBaselineProfile` 对照组实测，
  只生成不验证 = 盲目自信（错误变体/规则被合并丢掉是常事）；
  ④ 常见失效：debug 构建不生效、规则文件没进 APK、ProfileInstaller
  被裁。冷启动 + 首屏滚动是最吃收益的两段。
- **启动分层度量**：系统段（zygote→Activity，不可控 ~300ms）/
  Application 段（<150ms）/首帧段/`reportFullyDrawn()` 可交互段；
  只盯 TTFF 不看 TTI 会把"白屏快但点不了"当胜利（61 节）。
- R8：`minifyEnabled` 后 mapping 归档是底线；keep 规则按库官方
  consumer rules 叠加，禁止全局 `dontwarn`（83 节）。
- 掉帧排查顺序：`adb shell dumpsys gfxinfo <pkg> framestats` →
  Perfetto 定帧（主线程块 vs 渲染线程块 vs GPU）→
  再分是布局（过度绘制/Intrinsic）还是主线程阻塞（锁/IO/解码）。
- JankStats 进灰度做线上帧监控；宏指标（冷启/帧率/内存峰值）
  进发布门槄（37 节清单），而不是事后报告。

## 23. 诊断与调试

- **ANR 取证优先于猜测**：`/data/anr/` trace 看主线程栈的"waiting to lock
  <0x...> held by thread N"—— 锁被谁持有、等了多久都在里面；
  超时线：输入派发 5s、前台广播/服务 10-20s、后台广播 60s。
  结论必须能指出具体线程/锁/调用链，"可能太慢"不算诊断。
- **Native 崩溃**：`/data/tombstones/` + `adb bugreport`；
  栈是地址，需要带符号表的 so（`llvm-addr2line`/ndk-stack）——
  官方 so 保存在符号库（未 strip 的构建产物归档），
  没符号的 so 出了事就是盲人。
- **Perfetto 深挖**：SQL 查询比 UI 翻页快 ——
  `select * from slices where thread_name='main' and dur > 8e6` 直达长帧；
  帧轨迹（Frame Timeline）看 deadline missed 的具休帧。
- StrictMode：debug + 内部版开 `penaltyDeath()`（主线程 IO 直接炸）；
  生产版用 `penaltyLog()` 采样上报 —— 两套开关两套作用。
- **释放构建差异排查**：release 崩 debug 不崩 → R8 裁剪（83 节 retrace）
  /混淆后反射失配 / shrinker 移资源；只出现在用户机 → 厂商 ROM
  /abi 缺 so /内存档位（39 节）。
- `adb shell dumpsys activity top` 看栈顶组件；
  `adb shell am dumpheap` 拉 Java 堆；内存问题的标准链路是
  LeakCanary（常见生命周期持有）→ hprof 支配树 → 归类到 63 节表。

## 24. 测试

- **速度预算驱动分层**：JVM（core 模块，毫秒级）承担 80% 断言；
  Robolectric 给需要 Framework 的场景（资源/配置/权限拒绝流）；
  真机 `androidTest` 只留给集成与硬件相关（相机/后台/通知）。
  "能 JVM 就不 Robolectric，能 Robolectric 就不真机" —— 每下沉一层，
  维护成本与不稳定性升一个量级。
- **协程测试三件套**：`runTest`（虚拟时间）+ `StandardTestDispatcher`
  + `MainDispatcherRule`；坑：真 IO 混进测试 → 虚拟时间永远排不到 →
  10s dispatch timeout 炸出 "test did not complete"（kotlin 库 52 节）。
- Compose 测试：语义树查询（`onNodeWithText/WithTag`）；
  断言语义而非像素 —— 测试锚点（contentDescription/testTag）与
  无障碍语义共用一套，别为测试另建一套 selector。
- **截图测试**：Roborazzi/Paparazzi（JVM 侧）或 Compose Preview
  Screenshot Testing（官方 gradle 插件）；纪律：基线 diff 门槄 +
  字体渲染差异隔离（统一 runner 设备/字体，否则全是假阳性）。
- Fake 治理：共享 Fake 仓库放同一模块、Fake 也要有契约测试
  （与真实实现跑同套行为用例）—— Fake 漂移比没测试更骗人。
- 不稳定测试纪律：重试 ≠ 修复；隔离（quarantine 标签 + 一周内修）+
  根因归类（时序/并发/环境）—— 堆重试是债务滚雪球（101 节）。

## 25. 动画

- **中断与续接（专业感的分水岭）**：spring 动画被打断时不归零重启 ——
  新目标从当前速度/位置续接（物理连续）。所以手势跟随一律 `Animatable`
  （snapTo 立即跟手、animateTo 松手回弹）；`animate*AsState` 是
  "状态驱动的插值"，两者不可互替。
- 选型内幕：`animate*AsState`（值级，随状态自动起停）/
  `AnimatedContent`（内容切换，transitionSpec 定方向与速度）/
  `Animatable`（手动驱动，手势/物理首选）/
  `infiniteTransition`（循环—— 持续消费帧，列表里节制用）；
  设计侧复杂动效才用 Lottie。
- `AnimatedVisibility` 每次进出都创建动画实例成本 —— 列表项内嵌动画
  只对"当前项"做（93 节反模式）。
- 时长纪律：默认 spring 优先；显式 tween 只用于进出场门控/设计系统
  统一节奏，Material 短动效 150-200ms 红线不变。
- 共享元素转场（SharedTransitionLayout）解决"同一元素跨屏连续"的
  视觉问题（71 节）—— 别再用缩放/位移动画自己糊。
- 性能：动 scale/alpha/translation 走 `graphicsLayer`（RenderNode 层，
  不重组不重布局）；动 width/padding 每帧走三阶段 —— 长列表里这是
  标准卡顿源。
- 单一驱动原则：一个被动画的状态只有一个写入者；双驱动 = 抖动。

## 26. 主题与 Material3

- **设计令牌架构**：业务不直接写 `Color`/`Dp` —— 在 M3 之上包一层
  自有令牌（自定义 CompositionLocal + 主题工厂），
  换设计系统只改主题层，业务零改动；散写 `Color(0xFF...)`
  是评审一票否决项。
- **动态取色（12+ `dynamicLightColorScheme`）是可选而非默认**：
  动态色下色板跟壁纸走，品牌识别归零 —— 妥协方案：动态基底
  + 品牌主色覆盖（自定义 scheme merge）。
- **深色模式对比度纪律**：M3 深色靠色调高度（tonal elevation）
  区分层级而非纯黑叠灰；对比度 ≥ 4.5:1 要在深色下单独校验 ——
  同一组色值过线不代表深色下可读。
- 字体缩放纪律：全 `sp` + 容器自适应（`heightIn` 不写死）；
  系统字号 ×2 的大字体走查是发布清单项（42 节）；
  大字号下动画/布局若崩，优先怀疑写死的高度与单行假设。
- edge-to-edge 顶层处理（35+ 强制铺满）：状态栏图标对比度用
  `WindowInsetsControllerCompat.isAppearanceLightStatusBars`；
  顶部文字区加 scrim 渐变保可读 —— 别在主题层全局染底色。
- M3 Expressive（2025 组件/动效更新）：依赖升级后过一遍
  42 节无障碍 + 截图基线，再全量接受新组件 —— 视觉回归不靠眼晴。

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
| `ForegroundServiceDidNotStopInTimeException` | targetSdk 35+ dataSync/mediaProcessing FGS 超 6h/24h 被系统杀 | 拆片 + 监听 onTimeout（81 节） |
| `MissingForegroundServiceTypeException` | targetSdk 34+ startForeground 没带 type | manifest 与 startForeground(type) 双声明 |
| `SerializationException`/`ClassNotFoundException`（导航路由） | 类型安全路由被 R8 裁（仅反射引用） | route 类 keep + release 冒烟深链全量（83 节） |

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
| 35 (V) | **edge-to-edge 强制铺满**（可临时 opt-out）、**dataSync/mediaProcessing FGS 6h/24h 超时**、16KB 页对齐 | Insets 重排 + FGS 拆片监控 onTimeout |
| 36 (Baklava) | **预测性返回动画默认开**、edge-to-edge opt-out 移除、大屏（600dp+）强制可调整大小、MediaStore 版本锁 | 109 节适配 + 大屏回归 |

- 35/36 两版是"连续收紧链"：35 的 opt-out 属性只是过渡缓冲，
  36 直接移除 —— 升级计划要按 36 终态设计，不做 35 过渡方案。

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
| 渲染管线/掉帧到底在哪一段 | 47. 渲染管线内幕 |
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
| 为什么参数没变也重组？ | 强跳过下不稳定参数按 `===` 比较 —— 每帧新实例照样炸（6 节） |
| remember 里的值怎么不更新？ | remember 只在首次组合求值；依赖变化用 `remember(x) { }` 键控 |
| LaunchedEffect 和 SideEffect 区别？ | 前者带协程作用域+可取消；后者每次重组后同步执行（无挂起） |
| Modifier 顺序到底怎么排？ | 7 节管线语义：外层先（尺寸→修饰→间距） |
|重组中怎么改状态？ | 不许（组合是纯函数）—— 副作用进 LaunchedEffect |
| stateIn 的 5000 是什么？ | WhileSubscribed 的停止延迟：旋转窗口（3 节） |
| collectAsState 与 WithLifecycle 差别？ | 后者感知生命周期（后台停收集），列表/流大时收益明显 |
| Canvas 什么时候重绘？ | 读的 state 变化触发重组；invalidate 不存在（声明式，47 节渲染管线） |
| 两屏共享状态放哪？ | repository 单源；VM 只做屏级形态（5 节表） |
| Dialog 里的状态旋转丢了？ | Dialog 有自己的 saveScope —— 上提到调用方或 rememberSaveable |

## 47. 渲染管线内幕

了解掉帧到底发生在哪一段，排查才不是丢飞镖：

- **一帧的旅程**：VSYNC 信号 → Choreographer（主线程 doFrame：
  input→animation→measure→layout→draw 记录 DisplayList）→
  RenderThread（把 DisplayList 转成 GPU 命令，经 skia/OpenGL 提交）→
  SurfaceFlinger 合成上屏。三段任何一段超 16.6ms 都是掉帧，
  但原因与解法完全不同。
- **主线程段超**：业务逻辑/布局/绘制记录 —— 看 Perfetto 主线程跑道；
  RenderThread 段超：GPU 太重（阴影/大 blur/软件层）—— 看渲染线程跑道
  与 GPU 队列。
- **GPU 段**：过度绘制（同像素多次绘制）是省 GPU 带宽的经典项 ——
  开发者选项"Debug GPU overdraw"红区要清；但过犹不及：
  过度合并绘制层反而增加纹理内存。
- **硬件层（hardware layer）**：View 体系 `setLayerType(LAYER_TYPE_HARDWARE)`
  缓存 DisplayList；Compose 对应 `graphicsLayer` —— 只对
  "内容不变但位置/透明度变化"的动画才赚，内容频繁失效时是负优化。
- RenderThread 的存在解释了为什么"主线程不卡还是掉帧":
  帧Deadline 是全链路共享预算。Profile GPU rendering 条形图三色
  就是三段预算的直观呈现 —— 调优时对着颜色段定位，别只盯主线程。
- 未知掉帧：`dumpsys gfxinfo framestats` 看各段耗时；
  SurfaceFlinger 合成超时的最后手段是检查多窗口/画中画叠加层数。

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
| 共享元素/转场进阶 | 71. 共享元素与转场进阶 |
| Modifier 自定义与性能 | 72. Modifier 内部机制与自定义 |
| 调度器/线程模型内幕 | 73. 调度器与线程模型内幕 |
| Flow 背压与内部契约 | 74. Flow 内部与背压工程 |
| Hilt 代码生成内幕 | 75. Hilt 组件生成与作用域内幕 |
| Room KMP 驱动选型 | 76. Room KMP 与驱动深水区 |
| Intent 安全/PendingIntent | 77. Intent 安全与跳转工程 |
| 进程死亡/生命周期深水区 | 78. 生命周期深水区与进程死亡 |
| 通知触达策略 | 79. 通知触达与策略工程 |
| 权限合规/部分媒体访问 | 80. 权限策略与合规深水区 |
| FGS 6小时超时等实战 | 81. FGS 深水区实战 |
| 系统状态注入命令 | 82. adb 深度诊断与状态注入 |
| R8 full mode/retrace | 83. R8 full mode 与崩溃还原工程 |
| 构建提速工程 | 84. 构建性能工程 |
| targetSdk 升级战役 | 85. targetSdk 升级战役手册 |

## 71. 共享元素与转场进阶

- **SharedTransitionLayout（Compose 1.7+）**：包在 NavHost 外层，
  两个目的地里的元素用 `Modifier.sharedElement(rememberSharedContentState(key), animatedVisibilityScope)`
  配对 —— 同 key 元素在导航转场时自动补间（位置/大小/圆角连续）。
  硬纪律：① key 全局唯一且稳定（业务 id + 语义后缀）；
  ② `sharedBounds`（容器补间）与 `sharedElement`（内容补间）选错会穿帮；
  ③ 预览/测试环境没有转场 scope —— 必须传 placeholder，否则崩溃。
- **动画对齐导航**：`AnimatedContent` + `transitionSpec` 里判方向
  （`initialState`/`targetState`）用 `slideInHorizontally { it }` 定向；
  与 NavHost 的 enterTransition/exitTransition 组合时注意
  zIndex/阴影叠加（两个页面同帧可见时 elevation 假）。
- **导航级转场**：NavHost(composable route) 支持 enter/exit/popEnter/popExit
  四口参数；Push 语义的 app 用滑动 + 淡入组合，
  Tab 语义用淡入 —— 转场即导航语义的一部分，不是装饰。
- **Lookahead（预布局）**：LookaheadScope + `Modifier.animateBounds` 能
  "布局变化补间"（元素在两个布局位置间动画）—— 注意它每帧跑两趟
  测量（预布局 + 真实布局），列表热路径慎用。
- 手势驱动转场：`AnimatedContent` 配合 `draggable` + `snapTo/animateTo`
  可做"跟手导航"；进阶版是接 predictive back 的进度回调（109 节）
  把系统返回手势进度喂给转场 —— 两套手势状态要单源。
- 底部抽屉进阶：`ModalBottomSheet` 的高度自适应与嵌套滚动要验
  `nestedScroll` 透传；sheet 内列表 + 键盘同场是回归重灾区。

## 72. Modifier 内部机制与自定义

- **Modifier 是链式元素（Modifier.Node）不是字符串拼接**：每个节点在
  布局/绘制/指针三阶段各挂回调；链的顺序 = 回调嵌套顺序。
  自定义行为的首选是 `Modifier.Node` API（1.7+）：轻量、可复用、
  无组合开销 —— 老的 `composed { }` 工厂每次组合都创建状态与节点，
  已被官方标为性能雷（新代码禁用）。
- **工厂纪律**：对外暴露的修饰符函数签名
  `fun Modifier.myEffect(x): Modifier = this.then(MyNode(x))`；
  可变入参要用 `Modifier.update myNode { ... }` 模式更新而不是重建链。
- **graphicsLayer 内幕**：把绘制提升为 RenderNode 层 ——
  scale/alpha/translation 变化不重绘内容；`compositingStrategy`
  控制离屏合成（阴影/blur 需要）。给列表项动画加 graphicsLayer
  是 25 节性能条款的底层依据。
- **链成本直觉**：每链 3+ 个同质修饰符可读性崩；
  `Modifier.layout` 能合并测量逻辑（链尾合并），
  常用组合（padding+clip+background）抽具名工厂 —— 复制粘贴的
  修饰符链是顺序 bug 的温床（7 节）。
- 拿链里的状态：`pointerInput` 的 key 变化会重启协程手势块 ——
  key 选错 = 手势状态丢失（拖拽一半 key 变了，手势断);
  这不是性能问题，是正确性问题。
- 调试：Layout Inspector 看节点树与每个节点测量/绘制耗时;
  Compose 内部自带 `RecomposeHighlight`（组合次数高亮）。

## 73. 调度器与线程模型内幕

- **Dispatchers.Main 的真身**：Android 上是 Handler 驱动的调度器
  （Looper.getMainLooper()）；`Dispatchers.Main.immediate` 跳过
  post 直接在当前线程执行 —— 在主线程上 withContext(Main.immediate)
  是零开销进出；普通 Main 会真的 post 一次队列（帧内多一层延迟）。
  Compose 快照提交、recycler 事件这类高频场景用 immediate。
- **IO 与 Default 共享底层调度器**（kotlinx-coroutines 1.7+）：
  线程在两个池间迁移，`Dispatchers.IO.limitedParallelism(n)` 是
  "共享线程但限并发配额"的视图，不是新开 n 个线程 ——
  所以它没有线程创建成本，是隔离阻塞调用的标准手段。
- **IO 的弹性上限**：默认并行上限 64（`kotlinx.coroutines.io.parallelism`
  可调）；大量阻塞型并发别指望无上限 —— 换业务侧限流（Semaphore）。
- **Unconfined 的险**：首个挂起点前在调用线程跑、之后在不定线程恢复
  —— 仅用于测试或桥接点，业务代码用了等于放弃线程模型保证。
- **ThreadContextElement**：`ThreadLocal.asContextElement(v)` 跨挂起点
  保持/恢复值 —— 每次挂起恢复都执行 set/restore（开销在长链路里可见）；
  追踪埋点（trace id）用它，但别在热路径存大对象。
- **runInterruptible**：把阻塞 IO 变成可取消（触发线程 interrupt）——
  只有响应 interrupt 的 API 才有效（InputStream/Socket 可以，
  不响应的阻塞调用依然挡不住）。
- 自定义池的纪律：`Executors.newFixedThreadPool(...).asCoroutineDispatcher()`
  用完要 `close()`（或 lifecycle 接管）—— 泄漏线程池比泄漏对象难发现得多。

## 74. Flow 内部与背压工程

- **操作符是装饰器链**：`flow.map{...}.filter{...}` 只在 collect 时
  一层层包装执行 —— 定义时零成本；有终结操作才执行
  （挂起 collector 全链）。
- **背压的真相**：Flow 无内置缓冲 —— 下游慢则上游 emit 挂起等待
  （天然背压）；`buffer(n)` 断开这个同步（上游独立协程跑）；
  `conflate` 是"只保留最新"；`collectLatest` 是"取消旧收集重启新"。
  UI 场景几乎永远用默认背压；下载/传感器高频流才需要 buffer/conflate。
- **flatMapMerge 的隐含并发**：默认并发度 16（DEFAULT_CONCURRENCY）
  —— 对外呼接口来说这是炸限流的默认值，显式传 concurrency 参数；
  保序需求用 flatMapConcat；搜索类用 flatMapLatest（新值取消旧）。
- **异常透明性**：`catch` 只接"上游"异常，collect lambda 里的异常不接
  —— 收集侧错误要么 try/catch 包 collect，要么收敛到 stateIn 前。
  `retry/retryWhen` 同样只重试上游。
- **上下文保持不变量**：`flow { }` 内禁止切线程发射
  （"Flow invariant is violated"），跨线程供给用 `channelFlow`/`flowOn`；
  `flowOn` 只改上游调度，下游永远在收集者上下文。
- **冷流取消检查**：`flow { while(true) emit(...) }` 在收集者取消后
  emit 处抛出 —— 但纯计算循环不 emit 就不会取消，加 `cancellable()`
  或 `currentCoroutineContext().ensureActive()`。
- 构建器选型：`flow`（单协程顺序）/`channelFlow`（多协程并发 send，
  有 channel 语义）/`callbackFlow`（桥回调，必须 awaitClose）。

## 75. Hilt 组件生成与作用域内幕

- **代码生成视角**：`@HiltViewModel class V @Inject constructor(repo: R)`
  → 生成 `V_Factory implements CreationExtras.ReusableFactoryKey...`
  （实际是 `ViewModelAssistedFactory`/`dagger.internal.DoubleCheck` 包装的
  Provider 链）—— 每个依赖边都变成一个 Provider；`@Singleton` 的
  实现是 `DoubleCheck.provider(...)`（DCL 语义），
  `@ViewModelScoped` 是组件实例内的缓存 —— 理解这条链才能解释
  "为什么 Singleton 里存的值跨进程不共享"（多进程 = 多套图）。
- **作用域实质是"缓存在哪个组件实例上"**：SingletonComponent
  （Application）→ ActivityRetainedComponent（跨转屏，无 Context）→
  ViewModelComponent → ActivityComponent（含 Context）。
  把带 Activity Context 的依赖标 Singleton = 内存泄漏 + 跨页串味。
- **限定符纪律**：同型多实现用语义限定符（`@Named("remote")` 可用但
  弱类型 —— 自定义 `@Annotation class Remote` + `@Qualifier` 更硬）；
  限定符是匹配键，名字错了编译期能抓（优于运行时串味）。
- **多绑定与集成测试**：`@IntoSet` 集合在测试里可用 `@BindValueIntoSet`
  临时追加 —— 初始化器/拦截器插件化首选，比手写 registry 少登记点。
- **增量编译友好**：Module 小而专（按功能域拆），
  "God module" 会让任何依赖改动全图重编 —— KSP/Hilt 生成代码的
  重编范围由绑定图连通性决定。
- **无 Hilt 的场景**（纯 JVM core 模块）：构造器注入即可，
  别为了对称引 DI 容器 —— core 模块 new + 手写工厂是设计而非退步。

## 76. Room KMP 与驱动深水区

- **驱动选型矩阵**（Room 2.7/2.8 KMP 起）：
  `BundledSQLiteDriver`（自带固定版本 SQLite，各平台行为一致，
  首选）；`FrameworkSQLiteDriver`（Android 系统驱动，零体积但版本随
  ROM 漂移）；iOS 只有 Bundled/系统 SQLite 两条路 ——
  FTS5/JSON 函数这类特性可用性要按驱动版本查，不能只查 Room 版本。
- **双 API 兼容期陷阱**（Room 2.8）：SupportSQLite 与 Driver 两套并存 ——
  Migration 里拿 SupportSQLiteDatabase 写老式 SQL 没问题，
  但混用两套 API 会在 Room 3.0（纯 Driver 化，官方已预告）变成硬迁移：
  新代码迁移一律写驱动兼容形式，别给自己埋 3.0 升级雷。
- **KMP 下 Flow 失效通知**：InvalidationTracker 在 Android 依赖系统服务，
  其他平台用驱动层触发 —— 复杂查询（JOIN/视图）在某些平台版本上
  失效信号有已知缺失，跨平台 DAO 别把 UI 正确性押在自动重发上
  （手动 `invalidationObservable`/重拉兜底）。
- **Bundled driver 的坑**： destructive migration 在 bundled 驱动上曾有
  native 崩（nativePrepare，Google issue 381518941）——
  升级 bundled 版本前先在真机过一遍"升上来的存量用户"路径。
- 预打包库 + 驱动：`createFromAsset` 与驱动无关但首启拷贝同样只一次；
  KMP 下资产路径各平台不同，用 expect/actual 提供资产源。
- 性能：`BundledSQLiteDriver(db, idleTimeout)` 连接复用可调；
  多读单写模型下 WAL 模式 + 单写连接是上限，
  并行写锁在驱动层不可配置 —— 别在应用层试图绕。

## 77. Intent 安全与跳转工程

- **Intent 重定向漏洞（top 泄漏面）**：exported 组件（Activity/Receiver/
  Provider）收到外部 intent 后又把其中携带的**内嵌 intent** 拿去
  startActivity —— 攻击者借你的身份跳任意组件。纪律：
  外部 intent 里的嵌套 intent 视为不可信，只取数据不取 intent；
  必须跳转时重建 intent（显式目标 + 固定 extras）。
- **PendingIntent 三要素**：requestCode（不同意图不同码，复用会串）、
  `FLAG_IMMUTABLE`（31+ 必填，防载荷篡改）+ `FLAG_UPDATE_CURRENT`
  （复用场景更新 extras）；同 intent 同码的系统缓存是"通知点了没反应"
  的经典根因（被旧 PendingIntent 缓存命中）。
- **隐式 intent 与包可见性**：30+ 默认看不见其他包 ——
  要探测的包列进 `<queries>`；跳转前 `resolveActivity()` 预检
  + `ActivityNotFoundException` 兜底，双保险一个不能省
  （预检过了仍可能被厂商策略拦）。
- **深链验证**：`android:autoVerify` + assetlinks.json 数字资产文件 ——
  未验证的 http 深链在部分场景变成"选择打开方式"弹窗，转化率腰斩；
  发布后用 `adb shell pm verify-app-links --reverify <pkg>` 复验。
- 导出组件审计清单（27/38 节联动）：每个 exported=true 都要回答
  "为什么导出 + 谁校验调用方"；无答案 = 加 permission 或关导出。
- 通知/小部件的点击链路是外部输入入口：深链参数全部校验后再入 VM
  （54 节剧本第 5 步），不校验的深链是注入面。

## 78. 生命周期深水区与进程死亡

- **进程死亡的完整面**：系统杀进程（低内存/LMK）→ 一切归零，
  只留 SavedInstanceState/`SavedStateHandle` 能接的东西；
  重建路径与冷启动相同，但多一份恢复包 —— 测试纪律：
  `adb shell am kill`（真进程死）与开发者选项"不保留活动"
  （只销毁 Activity，进程还在）两套都要跑，覆盖的 bug 面不同。
- **onSaveInstanceState 时序**：28+ 在 onStop 之后才回调 ——
  在 onStop 之后还敢碰 UI 的代码（Fragment 事务/弹层）会抛
  `Can not perform this action after onSaveInstanceState`；
  Fragment 事务统一收敛在 `Lifecycle.State.RESUMED` 下执行。
- **多 resume 与可见性**：折叠屏/分屏下多个 Activity 同 RESUMED ——
  "onResume 启动相机/传感器"必须配合 `onTopResumedActivityChanged`
  级别的可见性回调，否则抢资源冲突。
- **Fragment 双生命周期**（实例 vs 视图）：`onDestroyView` 后 view 层
  引用必须清（binding 置 null、listener 解绑）；返回栈恢复时视图重建
  但实例状态还在 —— 流收集挂 `viewLifecycleOwner`，别用 fragment this
  （跨视图生命周期泄漏）。
- 生命周期 × Compose：`LocalLifecycleOwner` 配
  `LifecycleResumeEffect`/`LifecycleStartEffect`（lifecycle-runtime-compose）
  —— 手写 DisposableEffect 对齐生命周期是重复造轮子且容易漏。
- 工具：`adb shell dumpsys activity processes` 看进程优先级 ——
  被降到 cached 的进程随时会被杀，后台逻辑别押在进程存活上。

## 79. 通知触达与策略工程

- **触达漏斗的四个杀手**（都查一遍再下结论）：授权（13+ 运行时权限）/
  渠道级静音（总开关开、渠道关）/DND 与勿扰白名单/厂商省电
  （39 节）。埋点在 `notify()` 后拿不到"是否真的亮屏可见" ——
  关键场景要接 `NotificationListener`/自家页内双通道验证。
- **渠道策略矩阵（规模化纪律）**：渠道 = 用户可控的静音分组 ——
  按业务语义命名（消息/进度/营销）而非技术层命名（push/push2）；
  营销类渠道单独建，importance 起步 DEFAULT（用户接受度高再升）；
  渠道组（group）做折叠。改重要性不可能（21 节铁律）——
  初始值定错只能靠新渠道迁移。
- **Doze 批量与时效**：Doze 下系统批量延后通知 —— 延迟类通知
  （定时提醒）要 `setExactAndAllowWhileIdle` 通道 + 通知只在唤醒后发；
  IM 实时消息走厂商推送通道（53 节）而不是本地 notify。
- **MessagingStyle + Person**：IM 类通知用 MessagingStyle（对话聚合
  + 直接回复 RemoteInput）；shortcut 关联 Person 提升气泡/常谈
  优先级 —— 没接的人名头像会被系统折叠成冷冰冰的模板。
- **FGS/进度类**：`setOngoing(true)`、进度更新节流到秒级、
  `setOnlyAlertOnce` 防响个不停；结束后必须 update 移除进度条
  （挂着的 100% 进度条是口碑杀手）。
- 测试矩阵：授权前/授权后关渠道/锁屏与弹出横幅/勿扰中/
  省电模式中 五态真机过（39 节 + 82 节注入命令）。

## 80. 权限策略与合规深水区

- **Play 政策三雷区**（技术对了也可能拒审）：
  ① 后台位置必须走"前台授权 → 设置页引导"两步式，声明视频用途；
  ② `SCHEDULE_EXACT_ALARM` 要声明闹钟场景（闹钟/提醒），
  倒计时类一律用 `setAndAllowWhileIdle`（非精确）;
  ③ 权限声明与隐私政策文档逐条对得上（`dumpsys package` 权限清单
  = 隐私声明收集清单，多一个都是审核风险）。
- **部分媒体访问（34+）是三态**：`READ_MEDIA_IMAGES`（全量）/
  `READ_MEDIA_VISUAL_USER_SELECTED`（部分，与全量同持时代表部分）/
  未授权；部分态 UI 必须提供"选更多照片"入口 ——
  把部分态当未授权处理会导致用户照片"消失"的工单。
- **特殊权限矩阵**（全部走专属设置页，无运行时弹窗）：
  `SYSTEM_ALERT_WINDOW`（ overlays）、`SCHEDULE_EXACT_ALARM`、
  `PACKAGE_USAGE_STATS`、无障碍服务、`MANAGE_EXTERNAL_STORAGE`
  （全文件访问 —— Play 审查最严，能不用就不用）。
- **权限自动重置**：长期未用的 app 权限被系统撤销（30+）——
  关键场景启动时检查并二次引导；权限检查必须进首屏逻辑，
  不能缓存"上次给了"的结论（用户可随时在设置里手动撤）。
- **一次性权限（29+）**：`ACTION_MANAGE_APP_PERMISSIONS` 里用户可设
  "仅此一次" —— 下次冷启动授权态回退，代码不能假设"给了就一直给"。
- 测试命令：`adb shell pm grant/revoke`、
  `adb shell pm reset-permissions`、部分照片态真机 photo picker 走查
  （17 节漏斗 + 82 节命令）。

## 81. FGS 深水区实战

- **34+ 类型双声明**：manifest `foregroundServiceType` 与
  `startForeground(id, n, type)` 两处都要对上；类型还要求对应
  `FOREGROUND_SERVICE_*` 权限 + 运行时权限联动
  （location 类 FGS 要定位权限在先）—— 少一层即 `SecurityException`。
- **35+ 的硬超时**：`dataSync`/`mediaProcessing` 类 FGS 每滚动 24h
  累计上限 6 小时，超时系统直接杀 —— `onTimeout(type)`
  （Service 回调）是唯一的预告窗口，必须在里面保存进度 +
  调 stopSelf；超时后同窗口内再启动直接拒。
  没有官方 API 查询剩余额度 —— 设计纪律：同步任务拆片
  （片间用 WorkManager 接力），不做"单服务扛到底"。
- **12+ 后台启动禁令**：应用在后台时 startForeground 抛
  `ForegroundServiceStartNotAllowedException`；豁免路径
  （高优先级 FCM、用户交互链、full-screen intent）各有限制，
  白名单当设计而非当保障。
- **shortService**：三分钟上限的快速收尾型（免专权限）——
  超时同样被杀；适合"保存现场后退出"的过渡场景，不能当通用 FGS。
- **specialUse**：兜底类型要写声明文案 + Play 审查会要求证明
  "为什么别的类型不适用"；滥用会在年度审核被踢。
- **电池优化联动**：省电模式下 FGS 预算更紧；厂商 ROM
  （39 节）另有一套自启/保活门槛 —— FGS 通知常驻不是免死金牌。
- 测试：onTimeout 保存逻辑用单测验证；"被杀后重建"用
  `adb shell am kill` 复现；6h 上限无法注入加速 —— 发版前真机
  全程压一轮长同步，灰度期监控 FGS 被杀率。

## 82. adb 深度诊断与状态注入

| 目的 | 命令 |
| --- | --- |
| 看 WM 排队与配额 | `adb shell dumpsys jobscheduler \| grep -A 5 <pkg>` |
| 手动放行任务（测约束） | `adb shell cmd jobscheduler run <pkg> <jobId>`；单测里用 TestDriver 更可控（13 节） |
| 压 Doze / 解除 | `adb shell dumpsys deviceidle force-idle` / `step` / `unforce` |
| 打入 standby 桶 | `adb shell am make-uid-idle <pkg>`（验证冷桶限流） |
| 看省电策略全貌 | `adb shell dumpsys deviceidle \| head -30`；`adb shell cmd appops ...` |
| 看后台数据策略 | `adb shell cmd netpolicy`（后台网络限制排查） |
| 进程死亡复现 | `adb shell am kill <pkg>`（LMK 路径）；`am crash <pkg>`（崩溃路径） |
| AOT 全量编译（验证 profile） | `adb shell cmd package compile -m speed -f <pkg>` |
| 窗口/密度注入 | `adb shell wm density 400`、`wm size 1080x1920`（多窗口/大屏回归） |
| 权限快照/重置 | `adb shell dumpsys package <pkg> \| grep -i granted`；`pm reset-permissions` |
| 深链重验证 | `adb shell pm verify-app-links --reverify <pkg>`（77 节） |
| 电池注入 | `adb shell dumpsys battery set level 15` / `reset`（测低电策略） |
| 旋转/键盘注入 | `adb shell settings put system accelerometer_rotation 0` + `content insert`（同旧法） |

- 纪律：改系统状态（doze/桶/密度）的测试后必须复位
  （`unforce`/`wm density reset`/`battery reset`），
  不干净的设备上一切性能与行为结论作废。

## 83. R8 full mode 与崩溃还原工程

- **R8 full mode（AGP 8 默认）更狠在哪**：类型擦除更激进（不保留
  额外接口桥）、`-keep class **` 不再隐式 keep 子类、默认参数合成方法
  可被内联 —— 老项目"full mode 下才炸"的反射路径（Gson/旧 ORM/
  自研注解扫描）一律要在 full mode 下回归，而不是关闭 full mode 求生存。
- **崩溃还原三件套**：
  ① 构建产出 `mapping.txt` 归档（崩溃平台映射上传自动化，
  版本号与 mapping 一一对应，丢了 mapping 的版本等于盲飞）；
  ② `-keepattributes SourceFile,LineNumberTable` 保留行号
  （不加它，retrace 只能给方法名不给行）；
  ③ 内联帧：R8 会内联小函数，栈上出现合成帧 ——
  `retrace`（R8 自带 CLI）能展开，但自写的"grep 行号"脚本会错。
- **裁剪审计**：`-printusage`/`whyareyoukeeping <class>` 逐项
  确认"真没用到"；消费方规则（consumer rules）优先于业务手写 keep ——
  手写一堆又装着库自带规则是双重维护反模式。
- 反射类清单化：反射用到的类/序列化模型/`@JavascriptInterface`/类型安全
  导航 route —— 维护一份 `proguard-reflect.txt` 清单文件（构建时
  合并），而不是散在各模块的 keep 里
- 大小 vs 启动：`-dontobfuscate` 只在体积不是瓶颈时考虑 ——
  混淆缩短标识符，dex 解析与验证更快（启动收益小但非零）。
- 发布前冒烟矩阵（必过）：登录/支付/推送 token/深链/多态序列化 ——
  R8 事故 90% 在这些反射路径上（37/70 节清单联动）。

## 84. 构建性能工程

- **配置缓存（configuration cache）**：`org.gradle.configuration-cache=true`
  —— 缓存配置阶段结果，二次构建跳过全部配置；硬门槛：
  老式 `applicationVariants.all { }`/`afterEvaluate` 满天飞会不适配
  （AGP 8 用新 Variant API / `androidComponents`），
  迁移期用 `--configuration-cache-problems=warn` 摸底。
- **非传递 R 类（AGP 8 默认）**：`android.nonTransitiveRClass=true`
  —— R 只含本模块资源，编译提速且依赖隔离；
  老代码 `R.string.xxx` 跨模块引用的要在迁移时改成各自模块 R。
- **KSP2（Kotlin 2.x）**：处理器跑在 Gradle 守护进程里，
  不再随编译器版本耦合 —— Hilt/Room 迁 KSP 后构建时间
  -30% 起步是常态；kapt 仅为兼容期保留（50 节迁移手册）。
- **缓存三件套**：本地 build cache（`org.gradle.caching=true`）+
  远程缓存（CI 全部产出可复用）+ KSP/Kotlin 的增量能力成对生效
  —— 增量链上任何一环退回全量（kapt 不支持增量），整体就退回全量；
  CI 全量构建与本地增量是两个优化面，分开度量。
- **Build Analyzer** 驱动决策：AS 内置（或
  `./gradlew --profile` 报告）看插件耗时 —— 老旧第三方插件
  （旧版 google-services 等）是配置阶段大户，升级或换懒加载。
- 基线纪律：每次只动一个构建开关并记录完整构建/增量构建两个数字 ——
  "感觉快了"不算指标（58 节基准纪律同源）。

## 85. targetSdk 升级战役手册

- **节奏**：Google 要求新 app/更新在一年窗口内跟上新 target
  —— 升级不是“要不要”而是“何时”；把升级当战役（1-2 周）而不是
  “改个数字提个版”。
- **战役步骤**（每步都有产物）：
  ① 读官方 behavior-changes 页（按目标版本）列改动清单；
  ② 底线：按**新 target 生效**不是按设备 —— 老设备上新 target 行为一样生效；
  ③ 基线：Macrobenchmark 冷启/帧率在升级前后各采一轮；
  ④ 行为面回归：FGS/通知/权限/深链四大件 + edge-to-edge 全页截图比对；
  ⑤ 灰度分阶段（5%→20%→100%）+ 回滚开关预留（服务端开关优先于热修）。
- **35 升级面**：edge-to-edge 强制（Insets 全面重排，是回归重头）；
  dataSync FGS 6h 超时（81 节）；16KB 页对齐（so 依赖链要全部过一遍
  `-Wl,-z,max-page-size=16384`，模拟器验不出，真机/Cloud 测试机验）；
  前台广播/服务超时线变化。
- **36 升级面**：预测性返回动画默认开（109 节）；edge-to-edge opt-out
  移除（35 还能靠 `windowOptOutEdgeToEdgeEnforcement` 缓冲，36 不能）；
  大屏（≥600dp）强制可调整大小与横竖屏自由 —— 锁屏/锁方向
  的设计在大屏直接失效；MediaStore 版本锁。
- 小版本（QPR minor release）不再改 target 行为 —— 行为风险集中在
  年度大版本，回归预算按年度节奏准备。

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
| 本仓库模块纪律 | 94. 仓库纪律速查（本仓库特有） |
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
| 状态恢复/Saver 定制 | 96. 状态恢复与 SavedState 深水区 |
| ViewModel 花式用法 | 97. ViewModel 进阶 |
| WM 后端/配额内幕 | 98. 调度后端与配额内幕 |
| OkHttp 内幕/网络调优 | 99. 网络栈内幕与调优 |
| 渠道策略矩阵 | 100. 通知渠道策略矩阵 |
| 测试基建/截图测试 | 101. 测试基建与稳定性工程 |
| 所有清单的总目录 | 102. 清单总目录 |

## 96. 状态恢复与 SavedState 深水区

- **Bundle 限额是硬约束**：saved 状态走 Binder 事务（约 1MB 共享预算，
  一个 app 实际安全上限几百 KB）—— 大列表/位图/长文本进 saved state
  是 TransactionTooLarge 的主因；分页只存"页码 + id"，内容回源重建。
- **自定义 Saver**：`rememberSaveable(stateSaver = listSaver<SaveObj, Any> {
  save = { listOf(it.a, it.b) }, restore = { SaveObj(it[0] as Int, ...) } })`；
  mapSaver 适合字段多但易错（键拼错静默丢数据 —— 单测覆盖 save/restore
  往返）；默认 AutoSaver 只接 Bundle 原语，自定义类不给 Saver 就是埋雷。
- **SavedStateHandle 的隐性能力**：`getStateFlow(key, default)`
  （可观察的路由参数）、`setSavedStateProvider`（恢复时才序列化，
  省启动时写放大）；键名命名空间纪律 —— 前缀带 feature（`feed:cursor`）
  防冲突。
- **恢复时序**：SavedStateHandle/Bundle 的写入发生在帧外（onSave）
  —— 组合中的状态与恢复包不是同一时刻快照，恢复后首帧可能看到
  旧值新值闪烁 —— UI 层设计成幂等渲染可免疫。
- **验证矩阵**：转屏 / `am kill` 进程死 / `stateLossException` 场景
  （28+ onSave 后事务）三套都要过；验证工具用 StateRestorationTester
  （compose-ui-test 提供 `setContentWithAutoRestore`）。
- 多实例注意：`rememberSaveable` 的 key 冲突（列表里相同 key 两项互相
  覆盖）—— 列表内 saveable 状态配 `key = "item_${id}"`。

## 97. ViewModel 进阶

- 构造瘦身：参数只进依赖（repo/用例），不进 UI 值；
  UI 值走方法参数或 SavedStateHandle。
- VM 间共享：**不要**互相引用 —— 通过共享 repository 单源达成；
  真要事件级联动，用顶层 `SharedFlow`（Application scope 持有）。
- 测试替身：VM 直接 new（Hilt 不进单测）；
  `MainDispatcherRule` 注入测试调度器（35 节）。
- AndroidViewModel(context) 只给真的需要 ctx 的场景
  （通知/资源），且拿 application 上下文。

## 98. 调度后端与配额内幕

- **WM 的三套后端**：API 23+ JobScheduler（主通道，受 App Standby
  桶配额）；老系统 AlarmManager+BroadcastReceiver（精度差）；
  以及首启前的 GreedyScheduler（app 存活时立即跑 —— 所以
  "入队后瞬间就跑了"是正常现象，不是 bug）。
- **配额全景**：桶越冷 → 任务越稀（罕见桶几乎不跑）；周期任务
  实际间隔 = max(设定值, 系统调度结果)，受维护窗口批量放行 ——
  "设定每小时为什么半天没跑"看 `dumpsys jobscheduler` 桶位与
  约束 satisfied 状态（82 节）。
- **加急配额**（13 节联动）：按桶给滚动窗口配额；耗尽静默降级 ——
  监控 UI 侧不要假设 expedited 必快，要有"降级后体验"方案。
- **链式失败语义**：`beginWith(a).then(b)` —— a 失败则 b 不跑；
  `ExistingWorkPolicy.APPEND` 的链在前置失败后整链挂死 ——
  重试策略用 `Result.retry()`（BackoffPolicy）而不是无限重建链。
- **唯一任务的新姿势**：`UPDATE`（改参数不重排队，保留时序）与
  `APPEND_ORIGINAL`（拿前一次的输出接着跑）—— 下载续传/分批处理
  用这两个，别手写文件锁去重。
- **观察成本**：`getWorkInfosLiveData/Flow` 观察的是数据库表（WorkManager
  内部 Room 库）—— 高频查询自身有成本，UI 层收集后去重再渲染。
- 测试铁三角：`workManagerTestInit` + `SynchronousExecutor`（同步执行）+
  `TestDriver`（放行约束/延迟/停止）；Doze 与厂商层只能真机（39 节）。

## 99. 网络栈内幕与调优

- **连接池/复用**：OkHttp 默认保持 5 个空闲连接、5 分钟保活；
  HTTP/2 同主机一条连接多路复用 —— 大并发反而省连接；
  但不同 host 的 API 网关若都在同一 ALB 后面，`connection coalescing`
  可能复用到意外主机 —— 特殊环境（企业代理/证书固定）要显式
  `ConnectionSpec` / `proxySelector` 管住。
- **超时四层**（19 节）：connect（握手）/ read（字节间隔）/ write /
  call（总时长含重定向）；慢速大下载不触发 read 超时 ——
  卡死检测靠 callTimeout。
- **拦截器两级纪律**：应用拦截器看一次逻辑请求（含你改的头）；
  网络拦截器看每一次真实网络尝试（重定向/重试后）；
  日志放网络层看真实流量，认证/重试放应用层。
- **DNS**：默认走系统 DNS（JVM 缓存 TTL —— 移动网络换网不感知）；
  `DnsOverHttps` 换 DoH 防劫持，但自建 DoH 端点本身要高可用
  （DNS 全坏了 app 全断）；多 CDN 场景 DoH 可能破坏地理就近调度。
- **Retrofit suspend + kotlinx-serialization**：错误面两层 ——
  `HttpException`（HTTP 状态）与 `SerializationException`（体不匹配）；
  版本兼容纪律（新字段可缺省）+ `ignoreUnknownKeys`。
- MockWebServer：测试里模拟超时/重置/慢响应 —— 重试与降级逻辑
  只在故障注入下测试过才算存在（64 节）。
- 上传/下载：`@Streaming` + 进度 RequestBody 包装；大文件下载
  `Response.body.byteStream()` 边读边写盘，禁止 `bytes()` 全量进堆。

## 100. 通知渠道策略矩阵

- **渠道设计的三个粒度**：渠道（静音单位，用户视角）→
  渠道组（设置页折叠分组，如"聊天"组下多渠道）→
  通知类别（`Notification.CATEGORY_*`，系统勿扰/驾驶模式按它豁免）。
  只用渠道不用组 → 设置页被你铺满；用户找不到关那个的开关就直接关总开关。
- **importance 初始值的博弈论**：系统初始声量越高，用户越容易当场关渠道。
  营销/非关键类从 DEFAULT 起步；真正高优的（来电/闹钟）才 HIGH ——
  "全渠道 HIGH"等于全渠道静音。创建后 importance 不可代码改（21 节）。
- **迁移模式**（渠道语义改了/建错了）：新建新 id 渠道 →
  在旧渠道发一条"搬家"通知 → 旧渠道静默保留一段时间后不再使用；
  直接删除旧渠道会让用户设置一并消失（不可恢复），是禁忌。
- **勿扰兼容**：CATEGORY_ALARM/CATEGORY_CALL + `setCategory` 才能过
  系统勿扰白名单语义；`setBypassDnd` 已被渠道策略取代 ——
  别押在旧 API 上。
- **可折叠长文本/大图**：BigTextStyle/BigPictureStyle 只影响展开态；
  折叠态第一行是唯一必读位 —— 把关键信息放前 40 字。
- 埋点：渠道粒度的送达/打开/静音率进产品看板 —— 渠道策略是运营面
  不是纯技术面。

## 101. 测试基建与稳定性工程

- **不稳定测试治理（flaky discipline）**：重试 ≠ 修复；
  一次重试通过掩盖时序 bug，十次重试通过掩盖设计 bug ——
  隔离标签（quarantine）+ 一周内修复 SLA + 趋势看板（周 flake 率），
  超期未修的下架删用例比留着骗 CI 更诚实。
- **截图测试选型**：Compose Preview Screenshot Testing（官方 gradle
  插件，写法跟 preview 同源）适合组件库；Roborazzi（JVM）
  适合屏幕级回归；Paparazzi 适合像素级把控 —— 共同纪律：
  统一 runner 环境（字体渲染随设备差异）+ diff 门槄宽紧分级
  （布局类严/摄影类松）。
- **测试提速三板斧**：并行分片（Gradle max-workers/JUnit 分片）+
  重测试免 Robolectric 启动（每个 Robolectric 类的首次环境成本秒级）+
  常驻守护进程（Gradle daemon + 配置缓存，84 节）。
- **协程/Flow 测试专用件**：`runTest` + `backgroundScope`
  （订阅者活满整个测试体）；`Turbine`（流断言库）可入选但别同时
  引两套断言语义。
- **CI 基准纪律**：Macrobenchmark 跑在物理机（模拟器帧数据无意义），
  热噪声控制（充电状态、后台清空、多次取中位）；
  基准指标当门槄不当目标（预算法，34 节 vs 22 节区分）。
- 契约测试：Fake 与真实实现跑同一套行为用例 —— Fake 漂移
  是比缺测试更危险的静默错误。

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
| 全库反向速配 | 107. 场景速配卡（全库反向索引） |
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
| 状态提升到哪层为止？ | 最低共享方所在层；再高就是过度设计（5 节提升判据） |
| UseCase 层要不要？ | 小项目直接 repo→VM；逻辑复用 ≥2 处再抽 |
| 接口回调和 Flow 怎么选？ | 新代码一律 Flow；桥遗留回调用 callbackFlow（74 节） |
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

热路径的选型不是“用什么”，是"每帧预算里付得起什么"——
以下是成本模型与决策树（不背 API，背账）：

- **分配账**：`ArrayList` 扩容 1.5×（复制整个数组）；
  `HashMap` 每项一个 Node + 扩容 ×2 重哈希；
  `LinkedHashMap` 再加双向链表指针（LRU 用 accessOrder 顺手实现，
  代价是每次读都写链表）；装箱键（`Map<Int, V>`）每项再一个 Integer。
  帧内建 1000+ 项的 Map = GC 抖动预订单。
- **Android 特供（比 JVM 默认更划算）**：
  `ArrayMap/SparseArray/LongSparseArray`（无装箱、二分查找，
  <几百项时更省内存，大集合反而输给 HashMap —— 分水岭自己压测）；
  `androidx.collection` 的 `ScatterMap/ScatterSet/ObjectList`
  （无 Entry 分配、缓存友好的开放寻址，重写自 R8 内部结构，
  高频小集合首选）；`LruCache`（内部 LinkedHashMap accessOrder）。
- **不可变结构化共享**：kotlinx `PersistentList/PersistentMap`
  改一需 O(log n) 但零拷贝 —— “频繁小改 + 多版本快照”场景
  （撤销栈/时间旅行调试）远胜每改一次 toList() 全拷。
- **热路径决策树**（自上而下第一命中）：
  ① 帧内能复用吗？→ 提出循环/上提到字段；
  ② 键是 int/long？→ SparseArray/IntArray 索引（免装箱）；
  ③ 小集合高频读写？→ ScatterMap/ObjectList；
  ④ 需要 Compose 稳定性？→ 出口 toImmutableList()（kotlinx-collections-immutable）；
  ⑤ 需要并发？→ 先问能不能改设计为单写者 + Flow（68 节），
  再谈 ConcurrentHashMap。
- 兼纪律：`ArrayList.subList()` 是视图（持有父数组，泄漏/失效陷阱）；
  排序稳定性：`sortedWith(compareBy(...))` 是稳定排序 —— UI 列表
  预期一致的前提；`CopyOnWrite*` 只在“读极多写极少”才赚，
  事件总线高频写用它 = 每次写全拷。

## 快速索引 · 续（109 节）

| 想找什么 | 去哪一节 |
| --- | --- |
| 预测性返回适配 | 109. 预测性返回与手势导航 |

## 109. 预测性返回与手势导航

返回导航的范式转移：从"点了返回才告诉 app"变成"手势进行中就预告"。

- **平台链路**：Android 13（API 33）引入 `OnBackInvokedCallback`
  （窗口级 API）；manifest 开关 `android:enableOnBackInvokedCallback="true"`
  才接入；**Android 16（targetSdk 36）起系统预测性返回动画默认开**
  —— 不适配的 app 会出现"手势无预览/老动画残留"的过渡态。
- **回调四部曲**：`onBackStarted`（手势起）→ `onBackProgressed`
  （持续进度，可驱动自定义动画）→ `onBackInvoked`（手势提交，执行返回）
  / `onBackCancelled`（手势放弃，动画回弹）。
  注意：不开 manifest 开关时这些回调根本不注册，
  适配完整度 = manifest + 代码 + 动画三件全齐。
- **优先级语义**：注册回调有优先级；`PRIORITY_SYSTEM_NAVIGATION_OBSERVER`
  给"只想观测不拦截"的埋点/分析用（16+）—— 观测者不该吃掉返回事件。
- **Compose 侧**：`BackHandler`（常规拦截，同步语义）与
  `PredictiveBackHandler`（androidx.activity 1.8+，拿到手势进度流）
  都走 OnBackPressedDispatcher —— 自定义"跟手关闭抽屉/滑动销毁"
  用后者把 progress 喂给 Animatable（25 节）。
- **迁移雷区**：① 老代码里重写 `onBackPressed()` 与新回调
  同场双响应 —— 迁移时必须成对清理；② Dialog/BottomSheet 有
  自己的返回拦截路径，嵌套时消费顺序自内向外，逐层验；
  ③ 手势导航设备上返回键仍存在（无手势的用户/键盘设备），
  两套路径都要测。
- **测试**：开发者选项"predictive back animations"开关真机预览；
  Espresso/Compose 测试用 `pressBack()`（旧路径），进度语义
  只能真机手势验 —— 转场进度是系统窗口层动画，单测覆盖不了。
- 无障碍与教育：返回手势无可见按钮 —— 可发现性文案/引导层
  （首次启动提示）是产品适配的一部分，不是可选项。
