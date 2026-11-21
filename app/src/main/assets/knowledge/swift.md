<!-- kb: id=swift | role=builtin_coding_swift | title=Swift 专家知识库 | version=1 -->
# Swift 专家知识库

## 必看内容

- **Swift 6 语言模式是"数据竞争安全检查全转 error"，不是新语法包**：迁移标准路径是
  v5 + StrictConcurrency（minimal→targeted→complete）清零后再切 v6，语言模式按
  target 粒度设置，先叶子模块后 UI（见 1、12 节）。
- **6.2 改写了隔离默认值**：Default Actor Isolation 让新 App target 默认 MainActor、
  SPM 包默认 nonisolated（刻意不对称）；nonisolated async 函数改为跟随调用方执行器，
  不再"下池再跳回"，纯计算要用 @concurrent 显式下池（见 3、4、43 节）。
- **actor 可重入是设计而非缺陷**：await 挂起即放其他消息插队，跨 await 的一切前提
  必须重验——"guard 检查后 await 再写"是双执行事故模板（见 39、40 节）。
- **取消是协作的标志位**：不抢占、不打断同步段；任何 catch 把 CancellationError
  吞掉都会造出僵尸任务，取消风暴时反噬全系统（见 37、34 节）。
- **CoW 的唯一性判定靠 isKnownUniquelyReferenced**：局部别名会把它弄成恒 false、
  ObjC 可见类永远 false；隐藏拷贝藏在 bridging、闭包捕获与 inout 里
  （见 13、14、103 节）。
- **existential 三成本（装箱+间接+缓存不友好）**：热循环里 `[any P]` 要分桶或
  泛型化；跨模块泛型想内联必须 @inlinable 三件套（见 54、53 节）。
- **@Observable 是"每属性订阅粒度"，不是"更少刷新"**：body 重评估粒度不变，
  省的是无关视图；高频 tick 属性被 body 读到照样风暴（见 69、78、80 节）。
- **weak/unowned 只按生命周期关系选，不按性能选**：unowned 的崩溃尸检是
  "对象释放后回调触发"；Task 闭包默认强持 self 到任务结束（见 25、26、27 节）。
- **Sendable 是证明义务**：@unchecked 每一处都要能一句话写清"由什么机制保护"，
  数量当技术债 ratchet 只减不增（见 7、12 节）。
- **阻塞合作线程 = 全池死刑**：池宽=核数，semaphore.wait/同步派发/跨 await 持锁
  会造成"UI 能动、所有 async 全停"的假死（见 49、50、43 节）。
- **SwiftData 未版本化 schema = 数据不可迁移**；@Model 不是 Sendable，跨 actor
  一律传快照 struct（见 91、92、93 节）。
- **库演化模式（BUILD_LIBRARY_FOR_DISTRIBUTION）杀死跨模块特化与内联**：
  为发布 XCFramework 付出前必须重测性能基线（见 62、109 节）。

## 快速索引

| 想找什么 | 去哪一节 |
| --- | --- |
| 6 语言模式怎么切、迁移顺序 | 1. Swift 6 语言模式与迁移决策模型 |
| 6.1/6.2 新特性与特性开关 | 2. Swift 6.1/6.2 演进与特性开关 |
| MainActor 默认隔离开不开 | 3. Default Actor Isolation 落地策略 |
| nonisolated async 在 6.2 的行为变化 | 4. Approachable Concurrency 与 nonisolated(nonsending) |
| 低部署目标能不能用并发 | 5. 并发回溯部署与运行时 shim |
| 为什么我的 struct 不是 Sendable | 6. Sendable 判定规则深水 |
| @unchecked Sendable 什么时候合法 | 7. @unchecked Sendable 责任清单 |
| sending 参数和区域隔离 | 8. Region-based isolation 与 sending 参数 |
| 全局 var 在 Swift 6 怎么办 | 9. nonisolated(unsafe) 与全局可变状态 |
| 并发报错怎么按模板修 | 10. 并发诊断错误分类速查 |
| if swift(>=6.2) 与版本探测 | 11. 编译期版本谓词与运行时特性探测 |
| 并发错误量下降工作流 | 12. 语言模式渐进收紧工作流 |
| 什么时候发生写时拷贝 | 13. Copy-on-Write 触发判定与成本 |
| isKnownUniquelyReferenced 为什么恒 false | 14. isKnownUniquelyReferenced 实战陷阱 |
| 手写 CoW 容器骨架 | 15. 自定义 CoW 容器与 ManagedBuffer |
| consume/borrowing 怎么用 | 16. move 语义与 consuming/borrowing 参数 |
| ~Copyable 资源类型 | 17. Noncopyable 类型实战 |
| 平凡元组 memcpy | 18. memcpyable tuples 与 POD 布局 |
| 定长数组消除堆分配 | 19. InlineArray 定长数组与零堆分配 |
| Span 借用怎么不悬垂 | 20. Span 借用与生命周期纪律 |
| String count 慢与桥接成本 | 21. String 内部表示与 bridging 成本 |
| Array 峰值内存与零拷贝构建 | 22. Array 容量策略与 ContiguousArray |
| 字典持 key 泄漏与哈希稳定性 | 23. Dictionary/Set 哈希内幕与陷阱 |
| lazy 序列重复计算 | 24. lazy 序列与多次求值陷阱 |
| weak 还是 unowned | 25. weak 与 unowned 选型规则 |
| 闭包捕获的是值还是引用 | 26. 闭包捕获语义与捕获列表 |
| 循环引用从哪查起 | 27. 循环引用尸检手册 |
| 循环内存峰值与 autorelease | 28. autoreleasepool 的 Swift 用武之地 |
| deinit 在哪个线程跑 | 29. deinit 的线程与隔离语义 |
| 指针存进属性后悬垂 | 30. withExtendedLifetime 与指针逃逸 |
| 属性顺序与内存布局 | 31. 实例内存布局与元数据成本 |
| weak 的成本与弱数组 | 32. weak 底层实现与 nil 化成本 |
| 内存问题用什么工具查 | 33. 内存问题诊断工作流 |
| Task{} 与结构化并发怎么选 | 34. Task 结构化纪律与上下文继承 |
| group 并发度限流 | 35. TaskGroup 纪律与并发度控制 |
| async let 未 await 被取消 | 36. async let 的适用边界与取消差异 |
| 取消为什么没生效 | 37. 取消的协作语义深水 |
| 优先级与 QoS 设置 | 38. 优先级与 QoS 映射机制 |
| actor 里 guard 后 await 被插队 | 39. actor 可重入与交错执行 |
| 并发请求重复计算去重 | 40. actor 缓存去重 in-flight 模式 |
| 主线程 Task 风暴卡顿 | 41. @MainActor 跳线程成本剖析 |
| 同步上下文进 MainActor | 42. MainActor 同步桥与 assumeIsolated |
| 线程池宽度与线程心智模型 | 43. Executor 内幕与合作线程池 |
| 把任务钉到专用线程 | 44. 自定义 TaskExecutor 实验特性 |
| Mutex/Atomic 标准库用法 | 45. Mutex 与 Synchronization 模块 |
| AsyncStream 内存爆炸 | 46. AsyncStream 背压与缓冲策略 |
| debounce/merge 官方算法包 | 47. AsyncSequence 与 async-algorithms 纪律 |
| continuation 桥接回调 API | 48. withCheckedContinuation 桥接纪律 |
| GCD 混用死锁 | 49. GCD 与 Swift 并发混用的死锁集 |
| async 里 semaphore 阻塞 | 50. 阻塞合作线程的死亡清单 |
| trace id 跨层传递 | 51. Task-local values 与上下文注入 |
| 并发卡顿用什么测 | 52. 并发性能诊断工具链 |
| 跨模块泛型不内联 | 53. 泛型特化与跨模块内联 |
| any P 装箱成本 | 54. Existential 成本模型与隐式打开 |
| 变长泛型 packs | 55. Parameter Packs 实战与元编程 |
| 特化没发生的排查 | 56. 特化丢失诊断与 @_specialize 修复 |
| 协议 witness 间接调用 | 57. Protocol witness 表与去虚化路径 |
| KeyPath 高级用法 | 58. KeyPath 进阶用法 |
| some 与 any 怎么选 | 59. Opaque Types 与反泛型决策 |
| enum 载荷大小与装箱 | 60. enum 载荷与内存布局 |
| primary associated types | 61. 关联类型与 primary associated types |
| 库演化模式杀性能 | 62. 泛型 ABI 与库演化韧性 |
| typed throws 用法 | 63. Typed throws 深用 |
| rethrows 高阶 API | 64. rethrows 家族与 throwing 传染 |
| 错误类型怎么设计 | 65. 错误类型设计纪律 |
| catch 的装箱与 NSError | 66. catch 匹配成本与 NSError 桥 |
| precondition 还是 throws | 67. precondition 家族与可恢复策略 |
| Result 与 throws 选型 | 68. 错误通道选型：Result 与 throws |
| @Observable 展开后是什么 | 69. @Observable 宏内部机制 |
| ObservableObject 混用迁移 | 70. Observation 与 ObservableObject 双轨陷阱 |
| 宏展开怎么调试 | 71. 宏展开模型与调试手段 |
| ViewBuilder 超过 10 个视图 | 72. Result Builder 深水与 DSL 编译成本 |
| 属性包装器存储与拷贝 | 73. Property Wrapper 陷阱链 |
| @Entry 环境值声明 | 74. @Entry 与环境值声明 |
| 宏还是代码生成 | 75. 宏与代码生成的选型成本 |
| store 与快照边界 | 76. 自定义观察容器与快照边界 |
| 视图身份与 ForEach id | 77. View 身份与 diff 管线内幕 |
| body 为什么反复执行 | 78. body 重评估成本模型 |
| 三种状态属性怎么选 | 79. @State/@StateObject/@ObservedObject 纪律 |
| @Observable 刷新过多 | 80. @Observable 接入与过度刷新陷阱 |
| AnyView 性能惩罚 | 81. AnyView 类型擦除代价 |
| 长列表掉帧预算 | 82. List 与 Lazy 容器的预算管理 |
| GeometryReader 布局崩坏 | 83. GeometryReader 与布局陷阱 |
| 自定义 Layout 协议 | 84. Layout 协议与自定义布局深水 |
| 动画卡顿与 Transaction | 85. 动画与 Transaction 管线 |
| 导航路径与状态恢复 | 86. NavigationStack 与状态持久化 |
| task 与 onAppear 取消 | 87. task/onAppear 生命周期与取消 |
| UIHostingController 混嵌 | 88. UIKit 互嵌性能边界 |
| 启动首帧优化 | 89. 冷启动与首帧预算 |
| SwiftUI 事故速查 | 90. SwiftUI 高频事故模式速查 |
| SwiftData 上生产前检查 | 91. SwiftData 生产陷阱清单 |
| @Model 跨 actor 传递 | 92. @Model 的 Sendable 边界与快照模式 |
| SwiftData schema 迁移 | 93. SwiftData schema 版本与迁移 |
| SwiftData 大批量写入卡死 | 94. SwiftData 性能与批量写入 |
| Core Data 与 SwiftData 混用 | 95. Core Data 与 SwiftData 并存纪律 |
| 选 SwiftData 还是 GRDB | 96. 持久层选型决策 |
| ObjC API 循环桥接成本 | 97. ObjC 桥接成本模型 |
| assumingMemoryBound 危险 | 98. C 指针纪律与别名假设 |
| inout 重叠访问 trap | 99. 独占访问法则与动态检查成本 |
| 指针生命周期与临时分配 | 100. 指针生命周期与 Span 整合 |
| swizzle/KVO 在 Swift 类 | 101. ObjC 运行时边界：swizzle/KVO/归档 |
| @objc 暴露代价 | 102. @objc 暴露的成本与语义 |
| NSArray 桥接后写时拷贝 | 103. 集合桥接的隐藏复制 |
| C++ interop 能不能用 | 104. C++ Interop 现状与风险 |
| swift-testing 怎么组织 | 105. swift-testing 采纳纪律 |
| 并发逻辑怎么测 | 106. 并发代码测试方法 |
| XCTest 迁移与 confirmation | 107. XCTest 迁移与 confirmation |
| 快照测试抖动 | 108. 快照测试与确定性 |
| SwiftPM 库演化与发布 | 109. SwiftPM 深水：库演化与 unsafe flags |
| 编译慢的宏与模块原因 | 110. 构建性能：WMO 与宏拖累 |
| 合法但坑的反模式 | 111. 反模式清单：语言合法但生产必坑 |
| 真实事故复盘 | 112. 生产事故复盘三例 |

## 1. Swift 6 语言模式与迁移决策模型

- Swift 6 语言模式不是"新语法"而是**数据竞争安全检查全部转 error**；编译器用
  6.x 与语言模式保持 v5 相互独立（SWIFT_VERSION 单独设）——升级工具链不等于开启 6 模式。
- 迁移标准路径：v5 + `StrictConcurrency=minimal → targeted → complete` 逐级清零，
  再切 v6；跳级会瞬间涌入上千条错误，士气与进度双杀。
- 语言模式粒度是 **target**（SPM target / Xcode target），不能按文件开；正确顺序
  是先纯模型/工具库，后 UI target——上游清完下游报错才稳定。
- 预算按周算：5 模式下"能跑"的共享可变状态全部要重新设计，这是设计债的显形，
  不是编译器找茬。

```swift
// Package.swift：每 target 独立设置
.target(name: "CoreModels", swiftSettings: [.swiftLanguageMode(.v6)])
.target(name: "AppUI", swiftSettings: [
    .enableUpcomingFeature("StrictConcurrency") // v5 模式下的渐进位
])
```

- 陷阱：切 6 后依赖库没更新版本，它缺失的 Sendable 标注会涌进你的错误列表——
  先升依赖（看其 6 模式支持声明）再切换，别把依赖债当自己的错。

## 2. Swift 6.1/6.2 演进与特性开关

- 6.2（2025-09-15 发布，配 Xcode 26）核心：Default Actor Isolation（SE-0466）、
  nonisolated(nonsending)（SE-0461）、InlineArray（SE-0453）、Span（SE-0447）、
  memcpyable tuples、`if swift(>=6.2)` 运行时谓词（SE-0474）。
- 特性旗标三级：UpcomingFeature（下个版本转默认，可提前吃）、Future（永不自动
  默认）、Experimental（ABI 随时变）——库代码只允许依赖前两类，且必须在
  Package.swift 显式声明让下游继承同一行为。
- traits（SE-0464）等预览特性走 `-enable-experimental-feature`；发布二进制前
  必须回退，实验特性没有迁移承诺。
- 升级纪律：每次小版本升级先读 swift.org release notes 的 migration 部分，
  再全量跑测试矩阵；CI 镜像钉 swift:6.2 而不是 latest。

```swift
// 下游继承实验/即将特性行为的唯一正道：显式声明
.target(name: "Lib", swiftSettings: [
    .enableUpcomingFeature("ExistentialAny")
])
```

- 陷阱：App 开了 upcoming feature 而被依赖的库没开——两边对同一类型推断的
  语义不同，出现"只在 App target 报错"的怪相（库与 App 各自开齐）。

## 3. Default Actor Isolation 落地策略

- SE-0466：target 级 `SWIFT_DEFAULT_ACTOR_ISOLATION = MainActor`；Xcode 26 新
  App 模板默认开，**SPM 包默认保持 nonisolated**——不对称是刻意的：库被 App
  依赖，库不应强迫每个调用方跳主线程。
- 打开后 target 内所有未标注类型/函数默认 @MainActor；纯逻辑、序列化、数据
  转换要么显式 nonisolated，要么接受被主线程化（卡 UI 的隐患转移进 UI target）。
- 已知事故形态：库作者在本地 App demo 里"顺手"开了 MainActor 默认再发布，
  下游每次调用都多一次 actor hop + 主线程占用（41、43 节成本模型）。
- 选型建议：App 主 target 开（UI 占比高、收益立现）；数据层/SDK 库保持
  nonisolated 默认，用 actor 显式圈并发单元。

```swift
// App target: SWIFT_DEFAULT_ACTOR_ISOLATION = MainActor
// 被默认隔离误伤的纯逻辑，逐个显式豁免：
nonisolated func parse(_ raw: [UInt8]) -> Message { ... }
```

- 陷阱：别以为加 nonisolated 就自动离开主线程——6.2 起 nonisolated async
  函数**跟随调用方执行器**（4 节），重计算还得 @concurrent 或下池手段。

## 4. Approachable Concurrency 与 nonisolated(nonsending)

- Swift ≤6.1 的旧行为：调用 nonisolated async 函数会被调度到并发池再跳回，
  两跳都是纯开销；6.2 反转为**留在调用方执行器上跑**（挂起恢复也回到调用方
  隔离），hop 数大幅下降。
- 代价随之而来：MainActor 里调 nonisolated async 的纯计算 = 计算占主线程，
  且没有任何告警——"我以为它在后台"是 6.2 新一代事故的母题。
- 两个新标注背语义：`nonisolated(nonsending)`（SE-0461）显式承诺"不会把工作
  发往其他执行器"，用于让编译器放行更多同步上下文调用；`@concurrent` 显式
  要求下池执行——CPU 密集 helper 从 UI 调用必须标它。
- 验证手段：Instruments Swift Concurrency 模板的 actor hop 计数，升级 6.2 后
  同一代码 hop 应显著下降；卡顿反而出现就查"计算留在主线程"类回归（52 节）。

```swift
@concurrent  // 6.2：显式下池，不跟随调用方隔离
func hashAll(_ chunks: [Data]) async -> [Digest] {
    chunks.map { digest($0) }   // 纯 CPU，别霸占 MainActor
}
```

- 陷阱：nonsending 不是 Sendable 豁免——函数内跨隔离访问仍要过发送检查；
  它只约束执行器归属，不约束数据所有权。

## 5. 并发回溯部署与运行时 shim

- async/await、actor、global actor、TaskGroup 自 Swift 5.5 起**回溯部署到
  macOS 10.15 / iOS 13**（Xcode 13.2+，链接期弱符号接内嵌 back-deploy 运行时）
  ——部署目标低不等于不能用语言并发。
- 不可回溯的是**系统 API**：URLSession.data(for:) 等要 iOS 15+。老目标三条路：
  AsyncCompatibilityKit、swift-async-algorithms、continuation 手工桥（48 节）；
  两套时序不同（delegate 回调 vs async 恢复），同一组测试覆盖两条分支。
- 真机优先：back-deploy 运行时线程行为与新版有差，最低部署版本真机过一遍并发测试，模拟器不算数。

```swift
func load(_ url: URL) async throws -> Data {
    if #available(iOS 15, *) {
        return try await URLSession.shared.data(from: url).0
    }
    return try await withCheckedThrowingContinuation { c in
        legacyDownload(url) { c.resume(with: $0) } // 恰好一次
    }
}
```

- 陷阱：把回溯能力错记成"老系统全套可用"——Observation 与 SwiftData 都要 iOS 17+，无回溯，别混进部署决策。

## 6. Sendable 判定规则深水

- 隐式合成两个前提：所有存储成员 Sendable；**public 非 frozen 结构不给**
  （未来加字段会打破承诺）。卡在报错上的解法：@frozen 锁布局，或显式
  `: Sendable` 自己承担演进责任。
- class 几乎不可能隐式：要 final + 仅 let + 成员全 Sendable；实战 class 走
  @unchecked（7 节清单）或改 actor/struct，别硬凑。
- 函数类型是最隐蔽杀手：`() -> Void` 与 `@Sendable () -> Void` 是不同类型；
  存储属性持有非 @Sendable 闭包类型会连累宿主 struct 不 Sendable——
  "莫名报错"最高频来源，沿存储属性向下找第一个非 Sendable 成员，八成是它。
- 元组按结构隐式判定（元素全 Sendable 即可）但没有显式声明可写；需要命名
  语义就升 struct。泛型 struct 要 Sendable 必须写条件 `where T: Sendable`。

```swift
final class Counter { var n = 0 }        // 非 Sendable
struct Handoff {                         // 连带不 Sendable
    let onStart: (() -> Void)?           // 罪魁：非 @Sendable 函数类型
    let counter: Counter                 // 罪魁二：可变内部引用
}
```

- 诊断技巧：报错指到最外层类型时别盯着它看——逐成员下钻；编译器"note"里
  提到的首个成员名几乎总是根因。

## 7. @unchecked Sendable 责任清单

- @unchecked = 把编译器的证明义务背给自己：每一处标注都要能一句话写清
  "可变状态由什么机制保护"——写不出来就是没想清楚，先别标。
- 合法名单（背下来）：锁/Mutex 保护的类；初始化后只读的引用快照；系统
  句柄封装且底层 API 自身线程安全（os_unfair_lock 包装、DispatchQueue 持有）。
- 非法高频三连：ObservableObject/VC 标 unchecked 后在后台线程改属性（UI
  防线是 MainActor）；"目前恰好单线程"的缓存标 unchecked（调用方一变就
  裸奔）；含 weak 字段的类忘了析构竞态。
- @unchecked 不豁免 deinit：锁保护字段析构同样要拿锁——编译器零检查，全靠评审。
- 优先级永远：actor > 值语义 struct > Mutex 值包装 > @unchecked，数量只减
  不增（12 节）；评审口诀：先找"保护机制在哪一行"，找不到一律打回。

```swift
final class TokenBucket: @unchecked Sendable {
    private let lock = NSLock()
    private var tokens: Double            // 唯一受保护字段
    func take(_ n: Double) -> Bool {
        lock.lock(); defer { lock.unlock() }
        if tokens >= n { tokens -= n; return true }; return false
    }
}
```


## 8. Region-based isolation 与 sending 参数

- SE-0414 给编译器"区域"概念：一个值连同它引用到的东西构成区域；能证明
  区域不外泄，就能安全跨界——比 Sendable 粗判细一档的静态证明。
- 最大红利：**值唯一引用时可把非 Sendable 送进任务**。`sending` 参数声明
  "调用后所有权归我"：调用方之后再用旧引用 = 编译错误——把"我以为拷贝了
  其实共享"的竞态直接变成编译期失败。
- 典型场景：大 buffer 分发给后台任务加工后丢弃。过去要么 Sendable 化
  （深拷贝），要么 @unchecked（裸奔），现在零拷贝转移所有权。
- 局限：推理在跨 await/任务边界退化，报错回退成"要求 Sendable"——别硬刚
  编译器，回值快照设计；withTaskGroup 的 body 天然享受区域推理（34 节）。

```swift
func process(_ job: sending AnalysisJob) async {
    await worker.run(job)      // 任务内独占，无锁无拷贝
}
var job = AnalysisJob(raw: data)
process(job)                   // OK：所有权转走
// job.read()  ← 编译错误：sending 之后原值已交出
```

- 心法：sending 用在 API 边界上表达"转移"，日常数据传递仍优先值快照 struct
  ——两者不冲突，一个管所有权一个管可读性。

## 9. nonisolated(unsafe) 与全局可变状态

- Swift 6 模式下模块级 `var` 直接编译错误；`let` 全局安全（dispatch_once
  式惰性初始化）——第一反应永远是"能不能变 let / 搬进类型再隔离"。
- 全局可变三个正解：搬进 @MainActor enum 命名空间的 static var（隔离保证）；
  Mutex/Atomic 包一层（45 节）；真正的遗留才用 `nonisolated(unsafe) var`。
- nonisolated(unsafe) 的纪律清单：注释写明访问线程模型、init 前后界线、
  配 TSan 与评审双保险——它比 @unchecked 更危险（无类型边界，任何 import
  都能摸到）。
- 单例标准形态：`static let shared = Manager()` 惰性且线程安全；里面持有的
  可变状态才需要上面三条，别把单例本身做成 var。

```swift
enum AppPaths {                        // 命名空间 + 隔离的全局可变
    @MainActor static var recentURLs: [URL] = []
}
// 遗留逃生舱（必须注释：谁、在哪个线程、何时访问）：
nonisolated(unsafe) var legacyCache: [Int: UnsafeMutableRawPointer] = [:]
```

- 重灾区：测试 target 的全局 fixture 与 `var appState`——全部改成注入式
  （构造传参），测试并行化后全局可变状态是第一批爆雷点（105 节）。

## 10. 并发诊断错误分类速查

- 报错只有三大族，背下"族→修复模板"比逐条读错误快一倍：
  ①捕获族（capture of non-Sendable / mutation of captured var in @Sendable）
  ②跨界族（Sending 'X' risks causing data races）
  ③隔离族（call to main actor-isolated ... from nonisolated context）。
- 捕获族修复：被捕获 var 提前 let 拷贝；可变状态挪进 actor；闭包只留纯计算。
- 跨界族修复：传值快照 struct（92 节同款模式）；或 sending 转移（8 节）；
  或调用点显式 hop 到目标 actor——按"这个值归谁管"选，别机械加 @unchecked。
- 隔离族修复：调用方标 @MainActor / 被调函数 nonisolated 且参数值语义化；
  判据是"这段逻辑天然属于哪边"，UI 渲染逻辑默认 actor 化，纯算法默认去隔离。
- 每条错误的 "annotated ... here" 是**声明点**不是错误点：先读错误（使用点）
  再看注解（声明点），顺序反了会绕圈。

```swift
// 族②示范：跨界传活体 → 传快照
struct RowSnapshot: Sendable { let id: UUID; let title: String }
await renderer.render(rows.map(RowSnapshot.init))  // 传值不传对象
```

- 统计自己项目的高频族别，把修复模板写进 review checklist——错误信息是
  团队最好的并发教材。

## 11. 编译期版本谓词与运行时特性探测

- 两套版本语义别混：`#if swift(>=6)` 问**编译器**（编译期整块分支）；
  `if swift(>=6.2)`（6.2 新语法，SE-0474）问**运行时 stdlib**——用户设备
  的 Swift 运行时可能旧于你的工具链，库代码必须区分这两个世界。
- 探测三件套组合：`if swift(>=6.2)`（std 库新 API 分支）、`#if canImport(...)`
  （模块存在性）、`if #available(iOS 18, *)`（OS 框架可用性）；分支逻辑必须
  能用一句话解释"这条分支服务什么场景"，说不清就是抄来的。
- 库发布策略：`swift-tools-version:6.0` 是对工具链的强承诺；想让 5 模式
  消费者也能用，就要保证自己的 API 不依赖 6 模式语义（如严格 Sendable 检查）。
- App target 简化版：部署目标 + #available 就够，if swift() 留给库与
  跨平台 CLI（Linux/Windows 的 stdlib 版本与 OS 版本解耦）。
- 反模式：用 `#if swift(>=6)` 判断"是否启用并发检查"——语言模式与编译器
  版本无关，且语言模式没有官方谓词；要区分行为用显式编译条件（12 节）。

```swift
if swift(>=6.2) {
    buf.withSpan { consumeSpan($0) }    // 新运行时路径
} else {
    buf.withUnsafeBytes { consumeBytes($0) }
}
```

## 12. 语言模式渐进收紧工作流

- 顺序倒过来做：先叶子（无依赖的工具/模型库）→ 中间层 → UI target；上游的
  Sendable 标注稳定后，下游报错才不会"上游一改又全变"。
- 每步拉齐三件事：StrictConcurrency 升一级、编译错误清零、新增的
  @unchecked / nonisolated(unsafe) 记账（数量只减不增的 ratchet，进 CI 检查）。
- 6.2 捷径：App 层直接开 MainActor 默认隔离（3 节），报错从上千条降到
  "真并发点几十条"——剩余每条报错就是重构清单本体，按条消化。
- 库的额外纪律：声明 swiftLanguageMode(.v6) 的同时写清 default isolation；
  发布前用一个 v5 模式的下游工程编译验证兼容矩阵（1 节依赖债）。
- 度量化：`swift build 2>&1 | grep -c "data race"` 做 burn-down 曲线，每周
  必须下降——停滞说明在硬刚编译器而不是改设计，回去读 10 节模板。

```swift
// CI ratchet（伪码）：unsafe 债务只减不增
let debt = countMatches(patterns: ["@unchecked Sendable", "nonisolated(unsafe)"])
precondition(debt <= DebtBudget.current, "并发债务超标：\(debt)")
```

- 文化面：把"消灭一条 @unchecked"当 PR 描述里的成就写，比任何培训都管用。

## 13. Copy-on-Write 触发判定与成本

- 标准库值类型（String/Array/Dictionary/Set/Data）与"包着 class 存储的自定义
  struct"都走 CoW：赋值/传参 O(1)（只动引用计数），真拷贝发生在**写时且
  共享时**——`var b = a; b[0] += 1` 第二行才付费。
- 不触发拷贝：只读传递；原值引用唯一（14 节判定）；消费型传递提前斩断共享
  （16 节 consume）。
- 隐藏拷贝三来源：bridging（与 NSArray/NSString 共享过缓冲后的首写，103 节）、
  闭包引用捕获（26 节）、inout writeback（值语义 struct 被 inout 时全程独占）。
- 成本直觉：拷贝深度只有一层（引用字段只复制指针不深拷）；但 Array<Int> 的
  一层拷贝就是完整 memcpy——大数组在热路径的写时拷贝是真实事故源。
- struct 超过 3 个 class buffer 属性时传参虽 O(1)，但引用计数操作翻倍；
  热路径考虑 borrowing（16 节）或显式 Box。

```swift
var a = makeHugeArray()    // O(1)
let b = a                  // O(1)，共享缓冲，引用计数 +1
a[0] += 1                  // O(n)：首写 + 检测共享 → 整体复制
_ = consume(a)             // 明示结束，共享权完全交给 b
```

- 事故形态：循环内 `var local = shared` 后写 local，每轮一次 O(n)——
  Instruments Allocations 里看堆增长的锯齿（33 节工作流）。

## 14. isKnownUniquelyReferenced 实战陷阱

- 它只回答"此刻这个 class 引用是否唯一"：@objc 可继承的类**永远 false**，存在
  任何别名/其他持有也 false——CoW 判定里 false 只是"要复制"，但非常保守。
- 头号事故：把属性读进局部变量再判定——局部 + 属性两个引用恒 false，CoW
  名存实亡（每写必拷）。必须对**存储属性本身**取 inout 判定。
- 次号事故：判定通过后又调方法再写——方法内可能临时持有引用；正确顺序是
  "判唯一→立即改→中间不插调用"；-Onone 与 -O 结果可能不同（优化器提前
  release），分支必须对 true/false 都正确。

```swift
struct Vector {
    private var storage: Buffer          // final class，非 @objc
    mutating func normalize() {
        if !isKnownUniquelyReferenced(&storage) {
            storage = Buffer(copying: storage) } // 先唯一化再改
        storage.withMutableElements { $0.normalize() }
    }
}
```

- optional 包装：对 `var s: Buffer?` 判定的是 Optional 持有的引用——仍正确，但别同时还有另一个 Optional 副本游荡（13 节）。

## 15. 自定义 CoW 容器与 ManagedBuffer

- 骨架四件套：final class Buffer 持堆元素；外层 struct 包 buffer + count；
  mutating 写路径先 isKnownUniquelyReferenced 判定、false 就复制（14 节）；
  只读路径绝不触内部可变指针。
- ManagedBuffer<Header, Element> 把 header 与定长元素数组打进**一次分配**
  （省一次间接 + 元素连续）；withUnsafeMutablePointerToHeader/Elements 访问；
  容量需求不定时仍要自己管理增长与搬迁。
- 增长纪律：翻倍起步、超阈值后 1.5x；搬家用 initialize(from:) 后对旧缓冲
  deinitialize——这对配对没有编译器兜底，漏 deinit 泄漏、双析构直接崩。
- 什么时候别写：Array 够用就别造容器；正当理由只有"对外要暴露稳定指针语义
  （UnsafeBufferPointer）"或"特殊布局需求"（音视频 ring、SIMD 邻居表）。
- 测试三件套：CoW 语义单测（改 a 不动 b）、TSan 全绿、以及固化"局部别名"
  回归用例（14 节头号事故）——自定义容器 90% 的线上事故都在这三格里。

```swift
final class Stack: ManagedBuffer<Int, Node> {}
let buf = Stack.create(minimumCapacity: 16) { _ in 0 }
buf.withUnsafeMutablePointerToHeader { $0 += 1 }   // header 独立可变
```

## 16. move 语义与 consuming/borrowing 参数

- Swift 传值已是隐式 move：Array 传参本来 O(1)。consume 的真正收益是**提前
  结束旧引用的生命周期**——下一写不碰共享、缓冲立刻可释放；省的是 CoW
  检测与整拷贝，不是"拷贝本身"。
- SE-0366 consume 运算符：`use(consume x)` 之后 x 不可再用（编译期强制）；
  `x = consume y` 是惯用转移形态；`_ = consume x` 显式丢弃。
- SE-0377 参数修饰：`consuming` 承诺取走（调用方传后失效）；`borrowing`
  承诺不加引用计数地读。库 API 对大 struct 标注是零成本收益，调用方直接
  获得更少引用计数操作。
- 热路径惯用：大 struct 参数标 borrowing（读多写少）、返回前 consume 中间值
  让调用方直接收唯一引用——写库的人先标，用库的人白赚。
- 反模式：对小值到处 consume——消费语义的可读性代价远大于纳秒级收益；
  只在"之后确实不再用"时写 consume，让代码自己证明生命周期。

```swift
func export(_ payload: consuming Report) async -> URL {
    write(payload)                    // 之后 payload 生命周期结束
    return archivePath
}
let r = build()
export(r)                             // r 之后不可用，编译器盯着
```

## 17. Noncopyable 类型实战

- `struct Handle: ~Copyable` 关闭复制：赋值/传参/捕获全被编译拦截，把
  "fd 到底谁负责关"这类事故从运行期排查变成编译错误——资源类型都值得重写成它。
- 随之而来的义务：方法标注 consuming/borrowing（16 节）；**struct/enum 可以
  写 deinit**（Swift 首次支持值类型析构，兜底泄漏）；属性里存 ~Copyable 会
  传染宿主（宿主也 ~Copyable 或只能借）。
- 逃逸限制是特性不是缺陷：~Copyable 默认不能进逃逸闭包/Task/集合——
  逃逸意味着"存在第二个引用者"，正是要禁止的事；确要跨层就显式设计
  所有权转移（consume）。
- 泛型侧：`<T: ~Copyable>` 可写对所有可复制性都成立的算法；existential
  （any P）配合仍受限，每个小版本都在放宽——用到先探测支持面（11 节）。

```swift
struct File: ~Copyable {
    let fd: Int32
    consuming func close() { close(fd) }   // 取走即关
    deinit { close(fd) }                   // 兜底：fd 泄漏不再可能
}
var f = File(fd: open("/tmp/x"))
f.close()    // 之后 f 不可用：双关、忘关都不可能
```

## 18. memcpyable tuples 与 POD 布局

- 6.2 之前元组是二等公民：不能遵循任何协议、跨函数只能按 ABI 逐字段处理；
  6.2 的 memcpyable tuples 给"全平凡类型元组"（Int/Double/Bool/SIMD 等**无
  引用成员**）正式的 memcpy 身份——整块拷贝、整块传递有了类型系统背书。
- 直接收益一：`(SIMD3<Float>, SIMD3<Float>, Bool)` 这类图形/物理聚合不再
  有逐字段搬运惩罚，可以放心当寄存器组用；InlineArray/Span 的高效实现
  （19、20 节）正建立在它之上。
- 直接收益二：编译器可对小元组做整块寄存器传递的可靠优化；大平凡元组按
  一段连续内存 memcpy——跨函数传"一帧状态"不再拆散。
- 破功条件立验：聚合里放一个 class/String 引用立刻失去 memcpy 资格、布局
  膨胀——`MemoryLayout<(Int, String)>.stride` 对比 `(Int, Int)` 一目了然。
- 设计含义：per-vertex/per-sample 状态用平凡元组或全平凡 struct + InlineArray
  是 6.2 的正确姿势；老代码 `Array<(Float, Float)>` 的热点可以直接平移。

```swift
typealias Sample = (l: Float, r: Float, active: Bool)  // 全平凡
MemoryLayout<Sample>.size        // 12：紧凑、memcpyable
typealias Bad = (Float, String)  // 含引用：逐字段世界回归
```

## 19. InlineArray 定长数组与零堆分配

- SE-0453：`InlineArray<N, Element>` 长度进类型、元素**内嵌在值本体**——
  放进 struct/局部变量即零堆分配、零引用计数，缓存局部性拉满；音频 ring、
  定长查找表、网格邻居这类"尺寸编译期已知"的数据是它的主场。
- 与 C 互操作是隐藏主场景：导入头文件里的 `int name[16]` 直接映射，常量
  由头文件驱动而非手抄——追平了 C 结构体嵌定长数组的老习惯。
- 税则：含 InlineArray 的 struct 按值拷贝/赋值 = O(N) 整块 memcpy——大 N
  时的拷贝事故与 Array 写时拷贝同量级；跨函数传参标 borrowing（16 节），
  返回大 InlineArray 依赖 NRVO，别在中间层层转手。
- 访问路径：`.span`（20 节）借出连续视图读写，比逐下标更适合热循环；
  下标仍带边界检查——要极限性能的内核段用 span + 手动索引纪律。

```swift
struct Neighbors {
    var cells = InlineArray<8, Int32>(repeating: 0)
    mutating func bump(_ i: Int) { cells[i] += 1 }
}
// 32 字节内嵌在宿主值里：无堆分配、无引用计数操作
```

- 陷阱：值语义惯性缺失——两个变量各持一份、改一个不影响另一个；从 C 指针
  心智切过来的人第一周必踩，写两条单测固化语义。

## 20. Span 借用与生命周期纪律

- SE-0447：`Span<Element>`（含可变版）是对一段连续内存的**非所有权借用
  视图**：创建 O(1)、不分配不拷贝；定位是取代 withUnsafeBufferPointer 系
  的"可读可证明"形态，6.2 与 InlineArray/Array 配套。
- 铁律一：Span **不落属性、不逃逸出同步调用域**——它只是借来的，宿主
  （Array/InlineArray/分配块）死了 Span 就悬垂；当前编译器靠约定与部分
  诊断，不是完整借用检查（30 节工具补位）。
- 铁律二：同一时间至多一个可变 Span——两个可变借用重叠即互相看见对方的
  写，与独占访问法则（99 节）冲突的 UB。
- 标准姿势：作为**同步函数链的参数**向下传递，消费完即弃；要跨 await 或
  存起来 = 设计错误，回去用 Array/切片（异步持有一段内存=所有权问题）。
- 迁移顺手收益：把老代码 `withUnsafeBufferPointer` 的指针逃逸事故位（30 节）
  全部翻成 Span 参数传递——借用关系看得见，review 才有抓手。

```swift
func sum(_ s: Span<Int>) -> Int {       // 只借不持有
    var t = 0
    for i in s.indices { t &+= s[i] }   // 连续内存，缓存友好
    return t
}
let total = sum(values.span)            // values 全程活着
```

## 21. String 内部表示与 bridging 成本

- 三形态：small string（64 位平台 **≤15 UTF-8 字节内联**在值本体，spare
  bits 存 count，零分配）；native large（堆上类存储 CoW、UTF-8 连续）；
  bridged（包着 NSString）。日常字符串八成是前两种。
- 成本错觉来源：`count` 走图素簇遍历是 **O(n)**——判空用 isEmpty（O(1)）、
  长度预算用 `utf8.count`（O(1)）；`s[i]` 整数下标根本不存在，切分必须
  用 Index，这是 Swift 设计而不是缺 API。
- bridging：调 ObjC API 时 String→NSString 按需转换，native 形态可能要
  建桥接对象甚至拷贝；同一段 ObjC 调用循环里先 `as NSString` 一次复用，
  而不是每轮隐式重桥（97 节）。
- 构造纪律：高频拼接用 `String(unsafeUninitializedCapacity:)` + withUTF8
  直写缓冲；插值小串的开销主要是分配——能落在 small string 区间就别造
  中间 String。
- 图素语义：emoji/ZWJ 序列是一个 Character——协议层（长度、切片、传输）
  按 utf8 做，展示层按 Character 做，两层别混用。

```swift
if s.utf8.count > 15 { ... }     // O(1) 预算判断，别用 count
let ns = s as NSString           // 桥一次
for i in 0..<n { api.take(ns) }  // 循环内复用，杜绝重桥
```

## 22. Array 容量策略与 ContiguousArray

- 容量增长近似翻倍：append 均摊 O(1) 但**峰值内存 ≈ 2× 工作集**（扩容瞬间
  新旧两块并存）；已知规模先 `reserveCapacity(n)`，一行代码换掉一半峰值。
- 已知大小构建的零拷贝路径：`Array(unsafeUninitializedCapacity:)` 闭包内
  直接初始化缓冲并报告实际数量——免逐个 append 与反复扩容，导入/解码
  大数组的标配。
- ContiguousArray 的意义：保证连续存储且跳过 NSArray 桥接检查路径；纯
  Swift 数值热点里略快于 Array，且不能与 NSArray 桥（错误提前暴露）。
  默认仍写 Array，热点再换——两者互转成本低。
- 陷阱三连：循环里 removeFirst() 是 O(n)（改用索引窗口/双指针）；
  `arr = arr.filter {...}` 峰值内存双份；循环 append 大量元素不 reserve。
- [UInt8] vs Data：与系统 API 交互选 Data（非连续字节支持、NSData 零拷贝
  桥），纯算法内核用 [UInt8]/ContiguousArray——类型跟消费者走。

```swift
let copy = Array(unsafeUninitializedCapacity: src.count) { buf, cnt in
    buf.initialize(from: src)     // 单次 memcpy 级初始化
    cnt = src.count
}
```

## 23. Dictionary/Set 哈希内幕与陷阱

- 哈希种子**每进程随机**（SipHash 族）：hashValue 跨进程不稳定——把哈希值
  持久化、跨进程当 key、按 hashValue 分片全是事故；要稳定哈希自己实现
  FNV/xxHash 并写明协议。
- 结构：开放寻址 + 线性探测（缓存友好），负载因子约 3/4，超限整表 rehash
  ——大字典批量插入前 reserveCapacity(n)，一次 rehash 顶半天的"偶发卡顿"。
- 引用 key 双持陷阱：class 实例作 key 时 **key 与 value 都被强持有**——
  缓存变内存锚（想要的弱 key 要 NSMapTable 或自己封 weak 包装）；
  Dictionary(grouping:by:) 分组后整个元素集被收纳，大数据集内存翻倍是常态。
- NSObject 子类默认 identity 语义（hash=地址）：同一"值"两个实例不同
  hash，Set 去重悄悄失效——需要值语义就老实实现 Hashable/Equatable。

```swift
// 弱 key 缓存：系统容器不提供，NSMapTable 或自封
let table = NSMapTable<AnyObject, NSData>(
    keyOptions: .weakMemory, valueOptions: .strongMemory)
```

- 遍历顺序不稳定且随 rehash 变化——依赖顺序的输出要么排序要么换结构。

## 24. lazy 序列与多次求值陷阱

- LazySequence 的每次**终端消费都从头重算整个链**：`let m = arr.lazy.map(f)`
  之后两次 for-in = f 全量跑两遍；只有 `Array(m)` 物化后才算"结果"。
- 真正适用面：一次遍历 + 早退（prefix/findFirst）+ 单遍串联多个变换
  （map+filter+prefix 零中间数组）；要复用结果就老实物化成 Array。
- AnySequence/AnyIterator 的代价：类型擦除装箱 + 每元素一次动态派发——
  热点里把接口从 AnySequence 改成具体类型或泛型参数，一改一测。
- `sequence(state:next:)` 的闭包持有可变状态：两个消费者共享同一个
  generator 会互相吃掉对方的元素——按需新建，绝不共享。
- String 特例：`String(lazy.map(...))` 走的构造路径拿不到小串优化——高频
  小转换直接 for 循环 + reserveCapacity 拼接（21 节构造纪律）。

```swift
let hits = rows.lazy.filter { $0.active }   // 未物化
let a = Array(hits)      // 第一次全量计算
let b = Array(hits)      // 事故：第二次重新全量计算（数据源还可能已变）
```

## 25. weak 与 unowned 选型规则

- 唯一正确判据是**生命周期关系**，不是性能：对方可能先于自己释放（delegate、
  反向回调）→ weak；对象存续期覆盖回调触发窗（自己持有完整链条）→ unowned；
  同生共死 → 强引用合法，但要写明理由。
- unowned 尸检特征：崩溃栈停在回调入口、EXC_BAD_ACCESS——典型触发是动画
  完成块/延迟 Task 在 VC 释放后 fire；weak + guard let self 把崩溃变优雅退出。
- 性能谣言澄清：weak 取用带 side table 查询（32 节），但相对任何真实回调
  逻辑都是噪音——用性能理由选 unowned 的 review 一律打回。
- 协议属性弱引用要求：`weak var delegate` 需要 protocol 声明 `: AnyObject`
  （或 AnyObject 约束的 exist- 形式），忘写在 struct 上直接编译错误。
- Swift 6 加持：weak 同样受隔离纪律约束，跨 actor 回调先 hop 再调（42 节）。

```swift
final class Player {
    weak var delegate: (any PlayerDelegate)?  // 反向引用必弱
    func finish() {
        guard let delegate else { return }    // 已退场就静默退出
        delegate.playerDidFinish(self)
    }
}
```

## 26. 闭包捕获语义与捕获列表

- 两套捕获机制并存且语义相反：普通变量捕获是**引用装箱**（var 的盒子被
  闭包与外围共享，闭包内的修改外面看得见）；捕获列表 `[x, weak self]` 是
  **创建时立即拷贝/弱化**——事故都出在"以为是值拷贝"的 var 捕获上。
- @Sendable 闭包禁止捕获可变 var（编译错误）：正确修复不是绕检查，而是
  先 let 拷贝进局部，或在闭包外完成可变阶段、闭包只收结果。
- Task { } 捕获 self 默认强持有直到任务结束——长任务 = 变相泄漏窗口；
  视图发起的后台任务要么 [weak self] + guard，要么把任务上移到 store 层
  让生命周期与业务对齐（87 节），别让 UI 生死决定业务任务生死。
- `[self]` 显式强捕获（5.3+）：代码确实需要 self 时，显式强捕获比
  weak-then-guard 更诚实——滥用 weak 把生命周期 bug 掩盖成静默不工作。
- escaping 闭包捕获 inout 参数是编译错误（独占访问法则的延伸，99 节）；
  老代码迁移靠把值复制进局部变量再捕获。

```swift
var count = 0
let tick = { count += 1 }         // 引用捕获：改的是同一个盒子
tick(); tick()                    // count == 2
let frozen = { [count] in count } // 值拷贝：之后 count 变它不动
```

## 27. 循环引用尸检手册

- 四高发模板按频率排序：①Task/操作闭包强持 self 直到完成 ②delegate 声明成
  strong ③NotificationCenter block 版 observer：token 持闭包、闭包持 self
  ④Combine sink 进 cancellables：cancellables 持 sink、sink 持 self（对称环）。
- 尸检固定步骤：Memory Graph 过滤类名看引用入边 → 确认环 → 定断点（多半
  弱化 + deinit 清理）→ deinit 加日志确认释放 → 收工，十分钟内出结论。
- NotificationCenter 细节：闭包 observer 的 token 生命周期决定 self 生命
  周期，deinit 里 removeObserver 是标配；selector 版默认弱，别混着记。
- 完成回调属性 `var onFinish: (() -> Void)?` 持 self：完成后不置 nil = 环
  常驻；回调入口第一件事置 nil 再执行（先断环后调用，防重入）。
- 预防架构：跨层通信用 store/状态流，delegate 只留一层且必弱——环的治理靠结构。

```swift
final class VM {
    var onFinish: (() -> Void)?       // 风险点
    func complete() {
        let cb = onFinish
        onFinish = nil                // 先断环
        cb?()                         // 再执行，防重入
    }
}
```

## 28. autoreleasepool 的 Swift 用武之地

- Swift ARC 对象即时 release，但 **ObjC 工厂方法产物走 autorelease**：
  UIImage(data:)、NSData(contentsOf:)、大量 NSString/NSArray 桥接产物——
  纯 Swift 循环里每轮一个，池水只在 runloop 排空时放掉，峰值内存线性爬升。
- 识别信号：Allocations 曲线锯齿状爬升、量级与迭代次数成正比、leaks
  工具零报告（不是泄漏，是没排水的池）——三者齐备基本可以动手。
- 修法：循环体包 `autoreleasepool { }`；超大批量按 N 个一组分包。包太粗
  无效、太细增加池进出开销——分组大小以 Instruments 实测为准，不拍脑袋。
- async 上下文照常可用（池是线程本地的），但别包着 await：排水时机与
  挂起点交错会让语义模糊，先在同步段内消化 ObjC 产物再进异步世界。
- 边界澄清：池对强引用环（27 节）与 CoW 拷贝峰值（13 节）完全无效——
  三类内存问题三套工具，先分类再动手（33 节工作流）。

```swift
for batch in rows.chunked(into: 500) {
    autoreleasepool {
        for r in batch { thumbs.append(render(r)) }  // ObjC 产物
    }   // 每批排一次水，峰值被钉住
}
```

## 29. deinit 的线程与隔离语义

- deinit 在**最后 release 的线程**执行——任意线程都可能是"那个线程"；Swift 6
  里 deinit 默认 nonisolated，访问自己的隔离属性是编译错误：这是保护，绕过=造竞态。
- actor 的 deinit 也不保证在执行器上跑：isolated 清理要么用 isolated
  deinit（实验），要么显式 `func shutdown() async` 由拥有者调用。
- 迁移典型报错群：deinit 里停 Timer、移除 KVO、清 NotificationCenter——
  前两者合规（非隔离 API），触碰隔离状态的挪进 shutdown。
- 死锁暗雷：deinit 里同步等待与"自己相关"的任务（清观察者触发回调再进
  自己）——析构路径必须非阻塞、无同步等待、无对外调用链。
- 纪律总结：deinit 只做机械清理（断定时器、置 nil）；业务收尾放显式
  close/shutdown 由拥有者先调（17 节 ~Copyable 是同一思想的编译期版）。

```swift
actor Session {
    private var socket: Socket?
    func shutdown() async {          // 显式收尾：隔离内
        try? await socket?.close()
        socket = nil
    }
    deinit { socket?.cancelIO() }    // 只做非隔离机械清理
}
```

## 30. withExtendedLifetime 与指针逃逸

- 指针有效期 = **宿主值的生命周期 + withUnsafe 闭包域**；把指针存进属性、
  塞给逃逸闭包、return 出去 = 悬垂指针，且常"测试通过、上线崩溃"（释放
  时机取决于引用计数的巧合，不受你控制）。
- withExtendedLifetime 的正用：把使用域显式钉住——不分配、只保持引用，
  语义上是"借用界碑"；6.2 的 Span 把这类借用关系类型化（20 节），新代码
  优先 Span，老代码过渡期用 withExtendedLifetime 标注意图。
- 三高发事故位：①withUnsafeMutableBytes 的指针传给异步 API（回调时宿主
  可能已 copy/deinit）②UnsafePointer 缓存在 struct 里跨调用复用 ③循环外
  取的指针在循环内触发写时拷贝后继续用（13 节：旧缓冲已不是同一块）。
- 检测：ASan 能抓 use-after-free 大半，但 ARC 时序在 -Onone 下更宽松——
  指针代码必须在 -O + ASan + 真机回归全过才算数。
- 预防架构：对外 API 只暴露 Span/切片参数（同步函数链）；"异步持有一段
  内存"的需求 = 该拷贝或该用自己的 buffer 类——没有第三条路。

```swift
values.withExtendedLifetime {
    let p = values.withUnsafeBufferPointer { $0.baseAddress! }
    legacyC(p, values.count)     // 使用期间宿主保证存活
}
```

## 31. 实例内存布局与元数据成本

- Swift 会**重排存储属性**以减少 padding：源码顺序 ≠ 内存顺序，手算 offset
  全是错的——要偏移用 `MemoryLayout<T>.offset(of:)`，要紧凑靠类型设计
  （同宽字段聚在一起），别靠排列组合碰运气。
- lazy var 不是内联字段：一次堆上 indirect box + 初始化标志 + 首访问的
  线程安全检查——热路径大量 lazy 是隐藏分配源；用 Optional + 显式 init
  或计算属性替代。
- class 对象头 16 字节（isa + 引用计数）再加字段对齐；小对象海（<32B）的
  内存大头是头部 + malloc 元数据——数据结构选型先算"有效载荷占比"。
- 泛型与元数据：同族特化共享元数据；运行时 as?/is 走 metadata 比较（一次
  表查询）——高频类型检查下沉静态多态（57 节），别在每元素循环里 cast。
- 值传递 ABI：超过 3 字的 struct 按内存传参、大返回值走间接返回——热
  API 用 borrowing/inout 显式表达意图（16 节），"顺手返回大 struct"的
  固定成本比想象的高。

```swift
MemoryLayout<Payload>.size              // 实际紧凑大小
MemoryLayout<Payload>.offset(of: \.ts)! // 字段偏移的唯一正解
```

## 32. weak 底层实现与 nil 化成本

- weak 不是"会置 nil 的指针"那么轻：对象带 side table，弱引用登记在表里；
  **创建/销毁每个 weak 引用都是哈希操作**，dealloc 时遍历表 nil 化所有弱
  引用——弱引用越多的对象，死得越贵。
- 读取弱引用有原子成本：高频热路径（每帧读 delegate）先可选绑定到局部
  再用，别在循环体内反复解包同一个 weak。
- 弱数组没有原生支持：自包 `struct Weak<T: AnyObject> { weak var value: T? }`
  或 NSHashTable.weakObjects()；自包数组必须配清扫——每次遍历前
  `removeAll { $0.value == nil }`，否则只增不减虚胖。
- 大量观察者模型放大 dealloc 成本：更好的形态是注册 id + 字典 + 主动注销，
  weak 只留给"无法配对注销"的场景。
- 线程语义边界：nil 化原子，但取出来的对象用途要过 Sendable 检查——
  weak 不提供任何使用层面的线程安全（25 节隔离纪律照旧）。

```swift
struct Weak<T: AnyObject> {
    weak var value: T?
    init(_ v: T?) { value = v }
}
var watchers: [Weak<Observer>] = []
watchers.removeAll { $0.value == nil }   // 每轮遍历前清扫
```

## 33. 内存问题诊断工作流

- 分层定位法固定三步：**总量异常**用 Xcode Memory Graph（环、实例数爆炸
  直接可视化）→ **增长异常**用 Allocations generations（标记代际，看
  abandoned 块 = 已无用仍被持有）→ **起源**用 malloc stack logging
  （MallocStackLogging=1 环境变量，malloc_history 抓分配栈）。
- 澄清高频误区：leaks 工具对 Swift 循环引用**不报警**——环上每个对象都有
  强引用，技术上不是 leak；抓环只能靠 Memory Graph，别等 leak 报告。
- 生产侧闭环：MetricKit 收 jetsam/内存分位直方图，os_signpost 标注业务
  阶段（启动/进列表/导入），线上爬升段与线下同数据集复现对齐。
- dirty vs clean：只有 dirty 页（写过的）构成 jetsam 压力，VM Tracker 里
  盯 dirty——只读映射再大也廉价，优化方向永远是"少持有可写副本"。
- 常见结论速查：字体/图片缓存暴涨 = NSCache 用法错；WKWebView 内存算
  网络进程别背锅；Core Animation 突增 = 离屏渲染/快照残留。

```swift
let sp = OSSignposter(subsystem: "mem", category: "stage")
let st = sp.beginInterval("import")
doImport()                       // 线上线下同一套观测
sp.endInterval("import", st)
```

## 34. Task 结构化纪律与上下文继承

- 结构化的本质是**取消与生命周期随作用域传播**：async let / group 的子任务
  自动继承父任务的优先级、Task-local（51 节）与取消信号；父作用域结束前
  所有子任务必被收束（await 或取消）——这条树形纪律是取消风暴不出轨的地基。
- Task { } 是逃生舱不是默认：脱离结构、不随父取消；滥用后系统里充满孤儿
  任务，取消风暴时它们照常跑、照常烧电（37 节协作语义拿它们没办法）。
- Task.detached 更危险：连优先级与 Task-local 都不继承；唯一正当用途是
  "彻底逃离调用方上下文"（从隔离区启动全局维护任务），写它必须给出理由。
- 三条纪律：能用 group/async let 表达就别裸 Task；裸 Task 必须有**持有者**
  （存 handle，负责 cancel）；任务体第一行前先回答"谁在什么时候取消我"。

```swift
func refresh() async {
    await withTaskGroup(of: Void.self) { g in
        g.addTask { await loadA() }
        g.addTask { await loadB() }
    }   // 作用域即生命周期：返回前子任务全部收束
}
```

- 反面事故：视图 body 里裸 Task 持 self 跑十秒，视图早消失——泄漏 + 失控
  双杀（26、87 节各给了修法）。

## 35. TaskGroup 纪律与并发度控制

- group 的隐式规则：body 返回或抛错时**剩余子任务被取消并 drain**——
  早退自带取消传播；"完成即抛"的竞速写法是 while next + 边收边判。
- 并发度控制是必答题：全量 addTask 千级任务 = 连接池打爆 + 内存峰值。
  窗口模式：完成 N 个补 N 个，把在飞数钉在常数。

```swift
await withTaskGroup(of: Void.self) { g in
    var it = urls.makeIterator()
    for _ in 0..<8 { if let u = it.next() { g.addTask { await fetch(u) } } }
    while await g.next() != nil {          // 完成一个补一个
        if let u = it.next() { g.addTask { await fetch(u) } }
    }
}
```

- addTaskUnlessCancelled：取消后静默不补——把"要不要停"交给显式判断，
  而不是靠结果数组判空这种隐式信号。
- 结果有序化：next() 是**完成序**不是添加序；需要对应关系就让子任务返回
  (index, result) 再组装，别赌调度顺序。
- 抛错聚合：ThrowingTaskGroup 只抛第一个错、其余被取消；要收集全部错误，
  子任务内部 try? 转 Result，最后统一抛聚合错误（64 节 rethrows 消毒）。

## 36. async let 的适用边界与取消差异

- async let 是"固定元数"的结构化并行：绑定即启动子任务、作用域结束自动
  收束；2-5 个**编译期已知**的并行调用是甜蜜区，动态数量回到 group（35 节）。
- 独有语义必须背下：作用域退出时**未被 await 的绑定被隐式取消**——
  典型事故是条件分支里只 await 了部分绑定，另一个在作用域尾被静默砍掉。

```swift
async let a = load(x)      // 立即启动
async let b = load(y)
if flag {
    use(await a)           // 只消费了 a
}   // 作用域结束：b 被隐式取消——若 b 有副作用，就丢了
```

- 依赖副作用的任务禁止用 async let 表达（取消静默发生、无法观测）；改
  group 并显式 await 每个结果，让取消变成看得见的行为。
- 与裸 Task 的边界：async let 不能脱离作用域存活——"先启动、稍后某处再
  取"的需求，用 Task + handle 的结构化封装（持有者纪律，34 节）。
- 取消传播两层都要响应：父任务取消（外部超时）与作用域提前退出（分支
  短路）都会砍子任务，子任务内部按 37 节写协作检查点。

## 37. 取消的协作语义深水

- 取消 = **一个标志位 + 你的配合**：不抢占、不打断正在执行的同步段；
  长 CPU 循环不插检查点，"取消"就只是个永远不被阅读的温柔请求。
- 检查点三件套：`Task.isCancelled`（轮询）、`Task.checkCancellation()`
  （阶段入口抛 CancellationError）、`withTaskCancellationHandler`（把外部
  取消桥进旧世界 API，handler 在取消瞬间同步触发，可发 cancel 给底层）。
- 最重要的纪律：**catch 里识别出取消必须重新抛出**——吞掉 CancellationError
  的任务是僵尸任务：调用方以为取消了、它继续跑并"成功"返回；结构化取消
  （34 节）、去重（40 节）全部因此失效。

```swift
do { try await step() }
catch is CancellationError { throw }          // 铁律：透传
catch { throw Wrapped(step: id, cause: $0) }  // 其余错误才包装
```

- Task.sleep 是天然检查点（取消即抛）——把长任务拆成带 await 的阶段，
  取消响应能力免费获得；纯 CPU 段用 checkCancellation 分段。
- 不可取消段要显式声明：cancellationHandler + 局部屏蔽标志，并在 API
  文档写明"取消不中断本段"——取消语义是 API 契约的一部分，不是实现细节。

## 38. 优先级与 QoS 映射机制

- 优先级阶梯：userInteractive > userInitiated（=high）> medium（=default）
  > utility（=low）> background；与 GCD QoS 一一映射，MainActor 任务天然
  承接用户交互级别。
- 继承规则：async let / group 子任务继承父优先级；Task { } 继承当前任务
  上下文；Task.detached 不继承（显式传或落 default）——"后台批量活"必须
  显式 .utility/.background，否则继承来的 userInitiated 把它顶到前台级。
- 升级防反转：高优任务 await 低优任务时低优被**临时升级**——GCD 时代要
  手写的优先级继承这里免费；但已经阻塞在同步代码里的低优任务救不了
  （升级只在调度点生效），所以同步阻塞是双重罪（50 节）。
- 实战纪律：批处理/日志上报/索引重建显式低优；任何"低优持锁、高优等锁"
  的经典反转靠"不跨 await 持锁"消灭，而不是靠升级机制兜底。
- 观测口径：Instruments Swift Concurrency 按优先级分轨——满屏优先级升级
  链 = 依赖方向出问题，先查谁在等谁（52 节三视图法）。

```swift
Task(priority: .utility) { await vacuum() }  // 显式低优批量活
```

## 39. actor 可重入与交错执行

- 可重入是**设计决定**：actor 在 await 挂起时放别的消息插队，避免"等 IO 时
  整个 actor 陪葬"；代价是**跨 await 的前提全部失效**——不是编译器漏报。
- 经典事故模板（背下来）：`guard !busy else { return }` → await →
  `busy = true`——两个调用都能过 guard，交错后双双执行（支付双扣款、缓存
  双算全是变体，112 节复盘第一例）。
- 修复三板斧：①检查后**同步段内完成占位**再 await（act-then-wait）②把跨
  await 的不变量拷成局部值、回来重验 ③40 节 in-flight 表去重——把"检查+
  占位"压缩进挂起前的同步段。
- self 调用陷阱：同 actor 直接调用不挂起，但被调 **async** 方法内部仍可能 await——读被调源码确认挂起点。
- 重计算别霸占 actor：纯 CPU 大活会饿死其他消息——下池 @concurrent 或拆给专用 Task（44 节）。

```swift
actor Store {
    private var saving = false
    func save() async throws {
        guard !saving else { throw Busy() }
        saving = true; defer { saving = false }  // 同步段占位+复位
        try await disk.write(...)   // 挂起期间占位仍有效
    }
}
```

## 40. actor 缓存去重 in-flight 模式

- 可重入的直接后果：并发取同一 key 各自穿越 await，缓存未命中时**同一资源
  被下载/计算 N 次**；WWDC21 的标准解法是 in-flight 任务表——actor 缓存必修件。
- 模式三要点：命中缓存直接回；未命中查 in-flight 表复用 `Task.value`；
  新建后**立即在同步段登记**再 await（39 节纪律：占位先于挂起）。
- 取消语义两条：复用的 Task 不接受后来者取消（不能一人取消共享任务）——
  是特性，写进 API 文档；创建者被取消时 defer 摘表但 task 继续跑，严格去重要把摘表挪进 task 完成回调。

```swift
actor Images {
    private var inflight: [URL: Task<Data, Error>] = [:]
    func fetch(_ url: URL) async throws -> Data {
        if let t = inflight[url] { return try await t.value }
        let task = Task { try await download(url) }; inflight[url] = task // 同步登记
        defer { inflight[url] = nil }; return try await task.value
    }
}
```

- 表本身是可变状态：LRU 上限防内存失控（23 节警惕）；key 必须值语义，class
  key 重蹈引用 key 双持覆辙；"最后取消者取消任务"等精细化等真实需求再做。

## 41. @MainActor 跳线程成本剖析

- hop 的物理本质：resume 时把 job 入队主队列——一次加锁 enqueue + 主线程
  dequeue；单次微秒级，**频率才是问题**：每数据项一个
  `Task { @MainActor in ... }` = 千项千次 enqueue，主队列深度直接可视化成
  掉帧（41 节的成本模型同样适用于 MainActor.run）。
- 已在主线程时 @MainActor 调用是普通调用（运行时核对执行器身份，零入队）
  ——所以正确姿势是"同步调用链内零 hop"，而不是到处 spawn Task。
- 批量上屏三件套：后台聚合结果后**一次** hop（一个 MainActor.run 交整批）；
  高频状态用 AsyncChannel/AsyncStream 批消费（46 节）；帧驱动的 UI 更新
  用 TimelineView/专用 render loop，别拿 Task 当帧循环。
- 测量口径：Instruments Swift Concurrency 的 actor hop 计数 + 主线程
  sched 等待配对看；os_signpost 包住上屏段量化每帧 hop 数（52 节）。
- 6.2 加持：nonisolated async 不再"下池再跳回"（4 节），同代码 hop 数显著
  下降——升级后重测基线，别拿 6.1 时代的数字做今天的预算。

```swift
// 反例 → 正例：千次 hop → 一次 hop
for item in parsed { Task { @MainActor in render(item) } }  // ✗
await MainActor.run { renderAll(parsed) }                    // ✓
```

## 42. MainActor 同步桥与 assumeIsolated

- 三个桥的选择题：`MainActor.run`（挂起+入队，异步、能等）；
  `MainActor.assumeIsolated`（断言"我现在就在主线程"，错了当场 crash）；
  `DispatchQueue.main.async`（GCD 世界，绕过取消/优先级，最后手段）。
- assumeIsolated 的正当场景：**文档保证主线程调进的同步入口**——
  AppDelegate 回调、UIView 体系回调、老 SDK 同步 delegate；它把"信任我"
  变成一行可审计的声明：错线程立即崩，而不是数据悄悄坏掉。
- 禁区两条：assumeIsolated 里不能 await（同步桥）；后台回调里"赌一把"是
  自杀——`if Thread.isMainThread` 分流要么全 assume 要么全 run，禁止混合。
- Task { @MainActor in } 与 MainActor.run 的差别：前者是新任务（继承父
  取消上下文，34 节），后者是当前任务挂起恢复；优先级继承细节也不同
  （38 节）——修复 bug 时混用两者，会得到"时对时错"的幽灵。
- Swift 6 模式下"从 nonisolated 同步函数摸主 actor 状态"是编译错误——
  assumeIsolated 是少数合法出口，review 时逐条审"凭什么保证在主线程"。

```swift
final class Bridge: NSObject, SomeLegacyDelegate {
    func legacyDidUpdate(_ raw: Int) {     // 文档保证主线程回调
        MainActor.assumeIsolated { model.apply(raw) }
    }
}
```

## 43. Executor 内幕与合作线程池

- 合作池宽度 = **CPU 核心数**（不是 GCD 的每 QoS 层 64）：设计前提是任务
  会不停挂起让出线程；任何把池线程当专属线程用的代码都在掏空全局容量
  （50 节死亡清单）。
- 主 actor 的特殊性：不在池里、跑主线程/主队列；所以"池被阻塞"不卡 UI
  本身，但卡所有 async 进展——症状是"UI 还在动、所有加载全停"，极易
  误诊为网络问题（先查池再抓包）。
- 6.2 调度语义修正：nonisolated async 函数跟随调用方执行器（4 节），不再
  "下池-返回"两连跳；真正需要并发时仍会迁移——hop 降了，但"谁在哪条
  线程跑"的心智模型要整体更新一遍。
- 线程身份不稳定：同一 actor 方法的两次执行可能在两条池线程——Thread
  本地状态（autoreleasepool、objc 线程缓存）在 async 代码里没有连续性；
  跨层上下文用 Task-local（51 节），别用 Thread 字典。
- 排队公平性：同优先级 FIFO，但挂起恢复可能被新任务插队（可重入的另一面）
  ——对执行顺序敏感的设计全部改成显式串行化（channel 串行消费，46-47 节），
  别依赖调度器心情。

```swift
// 心智模型：actor ≠ 线程；是"串行化保证"而非"专属线程"
actor Log { func write(_ s: String) { /* 可能任一池线程 */ } }
```

## 44. 自定义 TaskExecutor 实验特性

- 存在动机：把任务钉到**指定线程**（音频回调、TLS 强依赖的 SDK、测试单
  线程确定性执行）——actor 只给串行化，不给"具体哪条线程"，需要线程身份
  的场景才轮到它。
- 形态：TaskExecutor / SerialTaskExecutor + `withTaskExecutorPreference { }`
  限定作用域内新建任务的执行器偏好；实验特性开关（TaskExecutors），API 与
  ABI 均未稳定——上生产前需要签名级评审（11 节探测 + 2 节旗标纪律）。
- 语义边界：preference 只影响**作用域内新建**任务；已运行任务不迁移；
  actor 默认仍用自己的执行器——混用规则细碎，逐条读当前版本的提案文本
  再动手，别凭印象写。
- 死法预告：SerialTaskExecutor 上跑阻塞/长任务 = 那条线程成为单点瓶颈，
  全部排队任务饿死（50 节同款结局）；测试里用它做确定性执行时禁混真实 IO。

```swift
// 实验开关 TaskExecutors；API 形态以当前版本提案为准
let audio = SerialTaskExecutor()
try await withTaskExecutorPreference(audio) {
    await render(buffer)   // 本作用域的新任务走专用执行器
}
```

- 替代评估：多数"钉线程"需求用串行队列 + continuation 桥（48 节）已够且稳定——自定义 executor 留给"要任务级 API 又要线程身份"的少数派。

## 45. Mutex 与 Synchronization 模块

- Swift 6 标准库 Synchronization 模块：`Mutex<T>`（值类型持锁持状态）与
  `Atomic<T>`（位级可复制类型）——**需要新 Swift 运行时**（iOS 18/macOS 15
  起）；老部署目标用 swift-atomics 包或自封 NSLock（回到 7 节责任清单）。
- Mutex.withLock 的价值是"锁+状态+Sendable 证明"三位一体：`Mutex<[Item]>`
  比裸 class + @unchecked 干净一个量级；闭包内**禁止 await**（编译器拦下）
  ——跨 await 持锁的经典死锁面（50 节）被类型系统直接消灭。
- 选型序：无共享 > 值语义/actor > Mutex > @unchecked class + NSLock。
  os_unfair_lock 的优先级继承在 Mutex 上没有：短临界区无感，持锁期间
  等资源（IO/锁中锁）的场景回 actor 重新设计。
- Atomic 的正当用法：标志位、计数器、double-checked 惰性初始化；把它当
  "万能无锁"是反模式——复杂不变量用锁或 actor，可证明性比微秒值钱。
- 与 GCD 混用边界：别用 Mutex 保护 DispatchQueue 回调路径——两套世界的
  死锁面叠加（49 节），一个模块只认一套同步原语。

```swift
let state = Mutex<[Entry]>([])            // 状态被锁封装
state.withLock { $0.append(entry) }       // 同步短临界区，无 await
```

## 46. AsyncStream 背压与缓冲策略

- AsyncStream 默认 `.buffering(.unbounded)`：生产快于消费就无界堆积——
  高频状态流用默认值 = OOM 标准剧本；**选缓冲策略就是选产品语义**。
- 决策表：`.bufferingNewest(n)` 丢旧保新（UI 状态、行情、游戏 tick）；
  `.bufferingOldest(n)` 丢新保旧（采样轨迹、审计片段）；"一步不能丢"=
  消费者太慢，改 AsyncChannel 挂起式背压（47 节）或先落持久队列。
- onTermination 必接：任务取消时停掉生产侧——不接的话生产闭包继续烧 CPU
  喂没人喝的管道，是"取消风暴下 CPU 反而升高"的常见根因（37 节）。
- yield 返回值（enqueued/dropped/terminated）可用于自适应降频：连续 dropped
  就合并/降采样——让生产端感知消费端，是背压最便宜的实现。
- 包装系统回调：离散回调转 stream 走 48 节规矩（恰好 yield、恰好 finish 一次），取消桥回底层 API（onTermination 里 cancel）。

```swift
let ticks = AsyncStream(
    Tick.self,
    buffering: .bufferingNewest(1)       // 只关心最新状态
) { continuation in
    let timer = startTimer { continuation.yield(Tick.now()) }
    continuation.onTermination = { _ in timer.cancel() }  // 防泄漏
}
```

## 47. AsyncSequence 与 async-algorithms 纪律

- swift-async-algorithms 是官方垫片层：merge/zip/debounce/throttle/chunks/
  retry 等组合子齐备，且 **clock 全部可注入**——生产代码禁止硬编码
  ContinuousClock 不透传，测试靠注入时钟快进（106 节的前提）。
- AsyncChannel 家族提供真背压：无缓冲 send **挂起生产者**直到消费者就绪；
  与 AsyncStream 的"缓冲+丢弃"是两种哲学——管道设计先回答"慢端怎么办"，
  再选容器，别按手感挑。
- 单消费者假设：for await 是串行拉取，AsyncSequence 没有多订阅语义；
  要广播自己封 share/中继模式；把 channel 当 EventBus 用 = 消息被随机
  一个消费者吃掉的事故模板。
- 与 Combine 桥接：AsyncPublisher 单向转入 async 世界就别回头；"async 转
  Combine 再转回"的双向桥双倍缓冲 + 时序漂移，只允许作为迁移期临时态，
  贴 TODO 限期消灭。
- 组合子执行位置：debounce/chunks 内部会 await——在 6.2 隔离语义下这些
  代码跟随调用方执行器（4 节）；重计算组合子别挂在 MainActor 上下文里。

```swift
let merged = merge(primary, fallback)                  // 官方组合子
    .debounce(for: .milliseconds(120), clock: clock)  // 注入时钟
for await s in merged { apply(s) }
```

## 48. withCheckedContinuation 桥接纪律

- 桥接三铁律：continuation **恰好 resume 一次**（多 resume 崩溃，少 resume
  永久挂起，Instruments 里是永不醒的任务）；resume 可以发生在 with 返回
  之前（回调同步立即来）；回调线程不受控——resume 线程安全，之后的位置由
  任务隔离决定。
- 选型：带 error 的回调用 Throwing 版；Unsafe 版省一点开销——**先 Checked
  上线，压测无异常再降级**，反过来是本末倒置。
- 高频事故：回调 API 有"成功/失败/取消"三路，代码只 resume 两路——第三路
  一触发任务就蒸发；"逐路核对 resume"是桥接代码的 review 必检项。
- 取消桥：withTaskCancellationHandler 包住，onCancel 对底层 API 发取消——
  外层取消停在 continuation 边界等于没取消（37 节契约完整性）。
- 三时序各一条单测：立即回调、延迟回调、取消后回调（106 节）——"偶发挂起"90% 在这三格。

```swift
func legacyFetch(_ id: Int) async throws -> Data {
    try await withTaskCancellationHandler {
        try await withCheckedThrowingContinuation { c in
            api.fetch(id) { c.resume(with: $0) }   // 恰好一次
        }
    } onCancel: { api.cancel(id) }                 // 取消透传
}
```

## 49. GCD 与 Swift 并发混用的死锁集

- 死锁集（每条都值得一次事故才会信）：①MainActor 上下文里
  `DispatchQueue.main.sync`——等自己；②异步上下文 sync 派发到"占用者也在
  等"的队列（GCD 任务里同步调 MainActor 函数属此类）；③semaphore/
  DispatchGroup.wait 阻塞池线程（50 节）；④串行队列任务里再 sync 回主线程。
- 替换表：sync 回主线程 → MainActor.run（能等）或 assumeIsolated（已在
  主线程）；semaphore → AsyncChannel/Mutex；DispatchGroup → TaskGroup；
  串行队列保状态 → actor（类型系统背书）；barrier 读改写 → actor 方法。
- 允许共存：GCD 管**它自己领域的源**（dispatch source、老定时器、IO），入口
  用 continuation 桥（48 节）转 async——咬人的都是"同一函数里两套原语各写一半"。
- 诊断特征：线程全 runnable 但 CPU 空、卡点栈里 GCD 与 async 交替出现——
  Instruments 线程视图找 blocked 的等待对象即可确诊（52 节）。
- 迁移纪律：新代码 async-only；动老文件时顺手把 GCD 段转 continuation 桥；
  一次 PR 只转一个函数，别大爆炸重构。

```swift
@MainActor func load() async {     // 已在主 actor
    let data = await fetch()       // async 世界的"等待"
    // DispatchQueue.main.sync { ... } ← 删除这行历史
}
```

## 50. 阻塞合作线程的死亡清单

- 全池宽度 = 核数（43 节），阻塞几条就永久少几条；八核设备八个
  semaphore.wait = 所有 async 全停、主线程还活着——"UI 能点、全都转圈"的
  经典假死，第一嫌疑人永远是它而不是网络。
- 死亡清单（异步函数里出现即事故）：semaphore/NSCondition/DispatchGroup
  的 wait、DispatchQueue.sync、同步网络与磁盘 API、Thread.sleep、跨 await
  持非递归锁（Mutex 被编译器拦，pthread 锁没人拦）、大 while 忙等。
- 逐项替代：等信号 → await channel/continuation；互斥 → Mutex 短临界区
  （不跨 await）；同步 IO → 异步 API 或专用队列+桥；sleep → Task.sleep。
- 自查手段：Instruments 线程视图找 blocked 且栈顶是 semaphore/lock 的池
  线程；代码检索 `\.wait\(|\.sync\(|Thread\.sleep` 出现在 async 文件里的每一处都是技术债条目，进 issue 而不是"顺手改"。
- 特例辨析：main 线程阻塞是另一档事故（直接卡 UI，剧痛早修）；池阻塞是
  **沉默谋杀——症状出现在离凶手最远的任务上**，只能靠检索清单前置拦截。

```swift
// 凶手样本：桥接老 SDK 的"同步等回调"
func convert(_ x: Input) async -> Output {
    let sem = DispatchSemaphore(value: 0)   // ✗ 池线程陪葬
    legacy.convert(x) { sem.signal() }
    sem.wait()                              // ← 死刑现场
}
```

## 51. Task-local values 与上下文注入

- `@TaskLocal` 是结构化上下文的正规军：声明为 static 属性、
  `withValue(...)` 圈定作用域、读操作贯穿整棵子任务树——trace id、请求
  id、A/B 分组、locale 这类"每请求一份、处处要读、不想层层传参"的横切
  信息的标准容器。
- 传播边界必须背：async let / group / Task { } 都继承；**Task.detached
  不继承**（detach 的语义就是切割上下文）——被 detach 的任务日志里
  trace id 突然消失，"排查半天的日志断链"八成是它。
- 非 HTTP 场景同样好用：模块边界 withValue 包一层，模块内部日志/指标
  自动携带上下文；读取成本纳秒级，别拿性能当借口层层传参。
- 反模式清单：拿它传业务参数（隐式依赖：测试不可见、重构必炸）；同层
  多次覆盖造成心智混乱（嵌套覆盖要能一眼看出谁是谁的子作用域）；
  把大对象塞进去当隐藏缓存用（生命周期跟着任务树走，比你想的长）。

```swift
enum Trace {
    @TaskLocal static var requestID: String?
}
try await Trace.$requestID.withValue("req-42") {
    await handle(request)   // 内部所有子任务可读 Trace.requestID
}
```

## 52. 并发性能诊断工具链

- Instruments Swift Concurrency 模板三视图：task graph 看父子/取消结构、
  挂起原因分布看"卡在等谁"、actor 活动看单 actor 是否成瓶颈——先看工具
  再谈优化，并发问题的直觉命中率极低（可重入、池阻塞全都是反直觉的）。
- os_signpost 是长期资产：beginInterval/endInterval 标出 fetch/parse/hop/
  render 阶段，Points of Interest 时间线上一眼看出 hop 频率与主线程占用
  段；线上靠 MetricKit hang 诊断 + signpost 导出形成闭环（33 节同款）。
- TSan 的位置：对 Swift 并发（actor 数据竞争、continuation 误用）覆盖
  持续改善——CI 开 TSan 跑测试矩阵是并发库的底线配置；TSan 报告的优先级
  高于一切"我这里应该没事"的主观判断。
- 挂起任务三联症：永不完成的 continuation（48 节少 resume）、被遗忘的
  group、channel 无消费者——Instruments 挂起任务视图 + 检索
  withCheckedContinuation 逐条审，是排查"偶发永远不返回"的固定套路。
- 帧级问题：SwiftUI 模板（Xcode 16 起）看 body 重评估与 diff 分布（78 节），
  Core Animation FPS 对齐主线程调度等待——两大杀手各配各的显微镜。

```swift
let sp = OSSignposter(subsystem: "pipeline", category: "async")
let st = sp.beginInterval("fetch", id: sp.makeSignpostID())
let data = await fetch()
sp.endInterval("fetch", st)     // 时间线上可数、可对比、可告警
```

## 53. 泛型特化与跨模块内联

- 特化 = 编译器按具体类型生成定制代码（去元数据调用、去 witness 间接、
  解锁内联）；条件是**函数体可见 + 类型可见**：同模块 WMO 免费发生，
  跨模块默认全灭——库的性能就死在这条边界上。
- 跨模块三件套：@inlinable（函数体导出）+ @usableFromInline（被引用的
  internal 放行）+ 类型布局稳定（@frozen，62 节）——缺一件都退回泛型间接。
- 代价面必须敬畏：@inlinable 让源码进入 ABI——函数体改动可能破坏下游二进制兼容预期，每次标注都是公开承诺。
- 手动干预：`@_specialize(exported: true, kind: full)` 请求导出特化（非
  官方稳定面，别当地基）；@inline(__always) 是内联不是特化——特化针对
  泛型参数，内联针对调用。
- 验证闭环：`swiftc -O -emit-sil x.swift | grep -A3 specialize`；release
  与 debug 差 10 倍以上的热点，先怀疑特化没发生（56 节），再怀疑算法。

```swift
@inlinable
public func map2<T, U>(_ a: [T], _ f: (T) -> U) -> [U] {
    var r: [U] = []; r.reserveCapacity(a.count)
    for x in a { r.append(f(x)) }
    return r
}   // 跨模块调用方可内联整段循环
```

## 54. Existential 成本模型与隐式打开

- any P 的物理形态：3 字内联缓冲 + 溢出堆装箱 + metadata 与 witness 表
  指针；方法调用 = 取 witness + 间接 call；装箱一次分配（小值进内联缓冲
  免分配）。三成本：分配、间接跳转、缓存不友好——集合元素用 existential
  时三连中。
- SE-0352 隐式打开：把 any P 传给泛型函数时编译器自动插"打开"代码，省掉
  手写 as? 降级——但**装箱还在**，热点里该泛型化还是泛型化，隐式打开
  是救API 人体工学的，不是救性能的。
- 决策树：单一底层类型 → 泛型参数（特化满速，53 节）；同模块多态 → 协议
  + 泛型约束；异构集合存储才 any；跨模块藏类型 → opaque some（59 节）
  优于 any（不装箱可特化）。
- 热循环罪名成立标准形态：`[any Drawable]` 每帧 draw——按类型分桶存储后
  直接派发，帧时间立降一截；分桶是十行代码的生意，别急着上架构。
- AnyHashable 是双层箱（外壳 + 内部键箱）：字典 key 高频取用的场景自己封
  类型安全包装，别让 AnyHashable 进热路径（103 节桥接叠加）。

```swift
for s in shapes /* [any Drawable] */ { s.draw() }    // 间接派发
let cs: [Circle] = shapes.compactMap { $0 as? Circle } // 分桶
for c in cs { c.draw() }                             // 直接派发
```

## 55. Parameter Packs 实战与元编程

- SE-0393（类型包）/SE-0399（值包）+ SE-0408（repeat each 迭代，6.0）：
  变长泛型让"任意个数的异构类型"成为一等泛型维度——zip 系 API、builder
  聚合、按字段生成校验，替代过去"十个重载 + 泛型塌方"的黑魔法。
- 心智模型：`each T` 是类型包，`repeat each` 是编译期展开循环——运行期
  零成本，代价全在**编译时间与二进制体积**（展开多少份就生成多少代码）。
- 甜蜜区：参数个数编译期已知、每个位置类型不同（回调聚合、多路 zip、
  per-field 元数据）；展开超过 ~10 个的用例先测编译时间净收益再上。
- 边界与坑：空包合法（展开为空，逻辑别假设至少一个）；包参数进 struct
  会传染宿主（宿主也带包参数）；与 result builder 组合时 DSL 推断复杂化
  ——先小后大，一次只加一个展开维度。
- 替代关系要清爽：小元数固定 → 手写泛型更直白；同质可变 → 普通 variadic
  参数；真异构任意元 → packs。三者混用的接口没人读得懂。

```swift
// SE-0408 经典形态：包展开进可变参数调用
func log<each V>(_ vs: repeat each V) {
    print("event:", repeat each vs)
}
log(1, "a", 3.14)   // 编译期展开成三段参数
```

## 56. 特化丢失诊断与 @_specialize 修复

- 症状识别三选一即进本节流程：debug 与 -O 性能差距巨大（>10x）；Instruments
  热点是 witness/metadata thunk 符号；-emit-sil 里泛型函数还在走 generic
  entry——先确认"特化没发生"再动手，别直接改算法白忙。
- 六大根因按命中率排序：①函数体跨模块不可见（没 @inlinable）②调用经
  existential 拿到值（54 节先拆箱）③闭包参数是 escaping 函数值（调用点
  无法内联）④泛型参数只用在别的非内联调用里 ⑤库演化模式全局压制（62
  节，先关它验证）⑥@objc/dynamic 强制消息发送（102 节）。
- 修复工具箱：@inlinable 补可见性；热循环下沉到具体类型方法（WMO 自由
  特化）；@_specialize 手动请求跨境兜底；hot path 拆成非泛型内核 + 泛型
  壳（内核可见性给足）。
- 验证闭环是纪律：改前改后各留一次 -emit-sil 输出与跑分数字进 PR——
  "感觉快了"不算数，SIL 证据 + 数字才算（52 节工具链配套）。
- 先后顺序：先跑分锁定热点（52 节），再看数据结构（22-24 节），最后才轮到特化——把特化当第一反应的人，多半在优化不热的代码。

```swift
// 根因③的修复：闭包参数泛型化，让调用点全特化
@inlinable public func each<T>(_ xs: [T], _ body: (T) -> Void) {
    for x in xs { body(x) }   // @inlinable + 泛型 = 调用方内联整段
}
```

## 57. Protocol witness 表与去虚化路径

- witness 表物理形态：每个 (类型, 协议) 一致性一张函数指针表——协议要求各占
  一槽，旁挂 associated type witness 与选定好的默认实现；泛型调用链 = 元数据
  查一致性 → 载入 witness → 间接跳转：进不了内联、icache 不友好，泛型热点
  第一嫌疑人（53 节特化是它的正解）。
- 去虚化三条正路（按收益排序）：同模块 WMO 特化把泛型参数换成具体类型（最
  彻底）；final 类/静态可证明的直接调用（编译器静态化）；SE-0352 existential
  隐式打开——作用域内按具体类型走、零装箱（54 节）。跨模块没 @inlinable 链
  全歇菜（56 节六根因清单）。
- as?/as! 走运行时 dynamicCast：元数据遍历 + 一致性缓存查询，热循环里反复判
  同一 existential = 每次付查询钱；打开一次存局部（打开后就是普通值）再进循环。
- **头号陷阱：非要求扩展方法不进 witness 表**。`extension P { func f() }`
  不是协议要求时，经 any P 调用永远命中扩展版本，具体类型的同名方法被静默
  旁路——事故特征："子类重写不生效"，且只在协议类型变量上复现。
- 评审规则一句话：要动态派发的方法必须声明成协议要求；扩展里只放"基于要求
  的组合实现"，别把扩展当声明位。

```swift
protocol Greeter { func greet() }        // 要求：占 witness 槽位
extension Greeter { func bye() { } }     // 非要求：静态派发，不在表里
final class Robot: Greeter {
    func greet() { print("beep") }
    func bye() { print("robot bye") }    // 经 any Greeter 永远调不到它
}
let g: any Greeter = Robot()
g.bye()    // 调的是 extension 版本：动态类型的 bye 根本不参与
```

## 58. KeyPath 进阶用法

- 家族四级按能力排：KeyPath（只读）→ WritableKeyPath（值语义根可写）→
  ReferenceWritableKeyPath（class 根可写）→ AnyKeyPath（根类型也擦掉）；
  PartialKeyPath<Value> 只保根类型（subscript 返回 Any）。`\.self` 是恒等
  路径 KeyPath<T, T>——ForEach(id: \.self) 的"值身份"语义来源（77 节）。
- 组合：appending(path:) 在运行时拼路径；类型逐级收窄，Partial 存"半成品"。
  KeyPath 是引用类型、组件编码进内部缓冲，超内联缓冲的组合走堆分配——热
  路径要拼路径就缓存成 `static let`，别每轮重建。
- 内部形态决定用法：class + 只读组件缓冲，Hashable/Equatable 全量实现——
  天然适合做依赖追踪字典的 key（依赖注入、表单引擎、diff 引擎都靠它吃饭）。
- 性能口径：keyPath 下标是运行时逐组件走——比闭包慢、比反射快；@objc 可见
  属性的 ReferenceWritableKeyPath 可桥 KVC 字符串，但 Swift 侧热点反向用
  字符串路径 = 丢类型检查 + 更慢，双输。
- 陷阱：KeyPath 能触达 private/@ObservationIgnored 存储（访问控制不拦
  keyPath 构造）——绕过观察系统（69 节）的"幽灵刷新缺失"从它开始。

```swift
struct User { var address: Address }
struct Address { var city: String }
let head = \User.address                  // KeyPath<User, Address>
let city = head.appending(path: \.city)   // 组合：KeyPath<User, String>
let p: PartialKeyPath<User> = city        // 降级：属性类型未知
users.sort { $0[keyPath: city] < $1[keyPath: city] }   // 下标走组件
```

## 59. Opaque Types 与反泛型决策

- some P 是"反泛型"：调用方被泛型化——拿到的是具体类型 + 真实元数据，只是
  名字不知道；零装箱零间接（any P 的三成本在它身上不存在，54 节）。热路径
  多态的默认答案是 some，不是 any。
- 两个位置语义不同：**返回位** some 隐藏实现类型（同一声明只允许一个底层
  类型，分支返回两种具体类型直接编译错）；**参数位** some（5.7 起）等价
  匿名泛型参数，纯语法糖零运行时差异——评审看到参数位 some 就当泛型读。
- primary associated types（SE-0341）：`some Sequence<Int>` 只锁关联类型
  不锁定整型，"全泛型太重、existential 太慢"的中间档；any Sequence<Int>
  也支持（带约束的 existential），用法相同语义不同（装箱仍在）。
- **底层型泄漏是 ABI 承诺**：公开返回 some P 后，底层类型进 ABI（客户端
  编译时展开真实元数据）——换底层类型 = 二进制断裂，只能 @available 旧版
  新路双轨；底层类型"恰好遵循"的协议调用方 as? 探得到，v2 删遵循即断。
- 存储位没有 some：属性/数组元素声明不了 opaque 类型（运行时必须有真实
  类型名）——存储用 any 或把容器泛型化；判定口诀"这个位置编译期要不要
  知道真实类型"，要 → 泛型/some，不要 → any。

```swift
public func makeEngine() -> some Engine {   // 底层型 = ABI 的一部分
    TurboEngine()
}
func draw(_ s: some Sequence<Shape>) {}     // 参数位 = 匿名泛型糖
let e = makeEngine()
e is TurboEngine   // true：探得到，但调用方代码不许依赖这个事实
// TurboEngine → RocketEngine = ABI 断裂：@available 双版本 + 迁移说明
```

## 60. enum 载荷与内存布局

- 尺寸公式：max 载荷 + 判别位；判别位优先**藏进载荷空闲位**——指针的 spare
  bits、对齐 padding 都能藏，Optional<引用> 与引用同尺寸（.none 用空指针
  位）即此原理；载荷无空闲位才加 tag 字节。多载荷按"最宽 case"分配。
- 验证靠测量不靠背诵：MemoryLayout<T>.size / stride 一行代码的事；struct
  内嵌 enum 的 stride 变化（padding 对齐）才是布局事故的常发位（31 节）。
- indirect = 装箱：case 标 indirect（或整个 indirect enum）后载荷进堆——
  一次分配 + 引用计数，递归类型必需；代价三件：构造成本、不可原地修改
  （深层节点修改 = 整路径重建）、默认 == 递归走整树（深树 O(n) 且爆栈）。
- 大载荷传参与 struct 同规：载荷超 3 words 走内存传参；`case .payload(let x)`
  的绑定对大载荷做拷贝——热循环先取字段或 consuming 匹配一次拿走（16 节）。
- 陷阱：indirect 的"两个内容相同的 .node 不是同一个箱子"——class 身份比较
  把同构树当不相等；要值语义 == 就显式实现（并决定深度比较语义）。

```swift
enum Tree {
    case leaf(Int)
    indirect case node(left: Tree, right: Tree)  // 此 case 载荷进堆箱
}
MemoryLayout<Optional<AnyObject>>.size   // 8：.none 藏在空指针位
// 深树高频比较：默认递归 == 会爆栈，改迭代 + 结构哈希缓存
```

## 61. 关联类型与 primary associated types

- associatedtype 是"协议里的泛型参数"：由遵循者的要求签名或显式 typealias
  推断；推断歧义报错时先读 candidates——十有八九是两个要求各推出一个不同
  类型（Element 同时从下标与迭代器推）。
- 约束分级记牢：`associatedtype T: Comparable` 是硬约束（遵循者必须满足）；
  extension 里的 `typealias T = Int` 只是**推断默认值**，遵循者随时可以
  覆盖——把它当约束用是最高频误读；缺约束补 where 子句，别指望 extension。
- PAT（SE-0341）：`protocol Sequence<Element>` 在声明位圈出 primary 关联
  类型，使用位 some/any Sequence<Int> 才合法；PAT 只该圈 1-2 个真正定义
  协议身份的关联类型——圈多了使用位全是噪音，不如留显式泛型。
- conditional conformance 陷阱：`extension Array: P where Element: P` 的
  条件遵循运行时按 Element 查询；**两个模块各自给同一 (类型, 协议) 声明
  遵循 = 运行时重复一致性撞车**，报错点在遥远的调用处——遵循声明只放
  类型属主模块，第三方适配用包装类型，别 retroactive。
- 与泛型协作：约束位 `T: Store<Int>`（PAT 写法）与 `T: Store where
  T.Value == Int` 等价但前者可读；继承子句对 PAT 支持有限，extension 的
  遵循声明位别依赖它。

```swift
protocol Store<Value> { associatedtype Value }    // PAT 声明位
struct MemStore<V>: Store { typealias Value = V } // 显式补推断
// 反例：别的模块再写 extension Array: Store = 重复一致性运行时撞车
// 正解：包一层 MyStore<Element> 转发，而不是 retroactive 遵循
```

## 62. 泛型 ABI 与库演化韧性

- resilience 是缺省：非 frozen 的 public struct 对外模块**隐藏布局**——
  字段访问编译成 accessor 调用、不能栈上构造；换来的自由是加字段不破坏
  二进制。代价是间接 + 全面压制特化——库的性能税从结构设计那天就定了。
- @frozen 锁布局：发布布局承诺，客户端直接吃字段、可内联可特化；加字段/
  改偏移 = ABI 断。规则：App target 内 frozen 无意义（同一次构建）；库的
  公开热路径值类型默认 @frozen，并接受"结构冻结"的纪律。
- @inlinable 三件套（53 节）：@inlinable 函数体进客户端编译；体内只能引用
  public 或 @usableFromInline；**inlinable + 非 frozen 字段访问的组合收益
  减半**（字段访问仍是 accessor，别指望全速）。
- 语义承诺别乱给：@inlinable 的旧函数体永远活在已编译的客户端里——修 bug
  客户端不重编就不生效；随手 inlinable 的库两年后面对"线上新旧两套行为
  并存"的工单，没有好答案，只能靠版本纪律提前避免。
- 库演化模式（BUILD_LIBRARY_FOR_DISTRIBUTION / .enableLibraryEvolution()，
  109 节）让一切 public 可演化 = 默认全税；发 XCFramework 才需要，开之前
  用真实基准重测（56 节流程），别为"以后可能发二进制"预付。

```swift
public struct Velocity: Sendable {    // 库的公开热路径值类型
    public var dx: Double             // 未 frozen：客户端走 accessor
    public init(dx: Double) { self.dx = dx }
}
@inlinable public func speed(_ v: Velocity) -> Double {
    v.dx * v.dx       // 非 frozen 字段：内联收益被 accessor 吃掉一半
}
```

## 63. Typed throws 深用

- 语义与收益：`throws(E)` 把错误类型钉死，`do throws(E) { } catch { }` 里
  error 静态就是 E——catch 免动态类型测试；错误值**不装箱 any Error**
  existential，小 enum 错误全程寄存器级——解析/校验循环的冷路径也变便宜。
- 泛型传播是新能力：`func with<E: Error>(_ body: () throws(E) -> Void)
  rethrows(E)`——rethrows 家族"抽象不了"的老大难（64 节）就此补全，高阶
  容器/组合子可以忠实传递错误类型而不必装箱。
- 演化规则背下来：**收窄兼容、放宽破坏**。throws(E) → 去掉 throws、或
  throws(Never)、或换成子类型，源码+ABI 双兼容；反向（无抛 → 有抛、窄 →
  宽、E1 → E2）全是破坏。API 设计从宽声明（无型 throws）、实现从窄收敛。
- 适用边界：域内确定性错误（解析、状态机、校验）收益最大；多源 IO/网络
  错误别硬钉类型——一个 catch 兜底转 any Error 的包装层，比错误类型膨胀
  务实。typed throws 是精确工具不是新教条。
- 互操作坑：暴露给 ObjC 的 typed throws 桥接语义受限（NSError 映射规则），
  跨语言边界的公开 API 保持无型 throws，类型化留在 Swift 内部层。

```swift
enum ParseError: Error { case badHeader(Int), truncated }
func parse(_ raw: [UInt8]) throws(ParseError) -> Config
do throws(ParseError) {
    _ = try parse(raw)
} catch {                       // error 已是 ParseError：零装箱、免 as?
    if case .truncated = error { requestResend() }
}
```

## 64. rethrows 家族与 throwing 传染

- rethrows 契约：只有当"函数类型参数"抛错时自己才算抛——map/forEach 这类
  人体工学的根基；体内抛错只能来自调用那个参数，自己直接 throw = 编译错
  （这条保证了"不传抛错闭包就不用 try"的推理可靠）。
- 致命限制：**rethrows 存不进类型**。赋给变量/属性、放进集合后类型退化成
  throws；想组合"接受一个 maybe-throws 高阶函数再包装一层"用 rethrows
  表达不了——正是 rethrows(E) + 泛型 E 的登场位（63 节），新代码直接上。
- 默认参数陷阱：带默认闭包参数的 rethrows 函数，**默认参数也算函数类型
  参数**——默认闭包抛错，则所有不传参的调用点都要 try；"默认不抛、显式
  传抛错闭包才抛"的设计别给默认参数标 throws。
- reasync 同族：async 版 rethrows，标准库 async map 靠它；限制同款（存不
  进类型、组合受限）。库提供 sync/async 双轨 API 时，rethrows/reasync 的
  签名矩阵（throws × async 四象限）用测试钉住，少一象限下游就强 try。
- 传染的现实：把回调 API 包成 async 函数时 rethrows 信息天然丢失（新函数
  自己决定 throws 与否）——包装层选 throws 是常态，调用方多打 try 是
  rethrows 的合理代价，别为省 try 发明绕过术。

```swift
func attempt<E: Error>(_ n: Int, _ body: () throws(E) -> Void)
    rethrows(E) {                  // 6.0：可组合的 rethrows
    for _ in 0..<n {
        do { try body(); return } catch { continue }
    }
}
attempt(3) { save() }              // save 不抛 → 调用点免 try
```

## 65. 错误类型设计纪律

- case 载荷化：`case quotaExceeded(limit: Int)`——禁止裸 case + 拿 userInfo
  字符串补细节；载荷就是错误契约，日志、断言、遥测、测试全部消费同一份
  结构，"错误变成数据"是排查效率的分水岭。
- 分层：DomainError（业务可恢复，typed throws/Result 携带）与
  InfrastructureError（IO/网络，**必须保留 cause 链**）分开声明；包装时
  带 underlyingError——"吞 cause 只留 message"是事后排查第一障碍。
- NSError 稳定映射：跨 ObjC/崩溃报告的世界里身份 = domain 字符串 + code
  数字——实现 CustomNSError（static errorDomain + errorCode），域字符串
  带模块前缀；**code 只增不改不复用**：重编号 = 打破已知问题匹配与看板。
- 展示信息走协议补丁：LocalizedError（errorDescription、
  recoverySuggestion）、RecoverableError——UI 文案不进错误载荷（本地化
  与复用双输），错误层只给结构化事实。
- 测试口径：错误类型全部 case 至少各触发一次（穷举 switch 强制编译器帮你
  记账）；错误进日志时反射 dump 全载荷，别只打 message。

```swift
enum SyncError: CustomNSError, LocalizedError {
    case offline(lastAttempt: Date)
    case quotaExceeded(limit: Int)
    static let errorDomain = "com.acme.sync"    // 稳定身份：带模块前缀
    var errorCode: Int {                        // 只增不改：看板兼容
        switch self { case .offline: return 1; case .quotaExceeded: return 2 }
    }
}
```

## 66. catch 匹配成本与 NSError 桥

- untyped throw 的真实成本结构：错误在 throw 点就装箱成 any Error
  existential（一次，不可避免）；**大头在跨进 ObjC 世界时**——转 NSError
  触发包装 + userInfo 建构，回调风暴里每错一次付一次；typed throws（63 节）
  连 throw 点装箱都省。
- catch 匹配 = 线性动态类型测试：从上到下逐个 is——**顺序就是语义**，窄
  分支在前、宽分支在后、catch-all 收尾；typed do-catch 直接跳过整套测试。
- 构造时抓栈的取舍：错误现场丢栈是通病；Thread.callStackSymbols 每次调用
  都是一次栈展开——只在诊断构建开（注入开关），生产在**抛点**用 os_log
  fault 补带上下文的一条日志（错误对象里别藏栈：内存+序列化双税）。
- 吞错反模式按危害排序：`catch {}`（静默黑洞）＞ `try?` 丢弃（错误压成
  nil，现场全丢）＞ catch 只 print（进不了看板）；底线是结构化日志 + 可
  计数；任何"catch 后不再抛"都要能回答"这个错误最终谁看见"。
- NSError 桥回 Swift：domain/code 完好，userInfo 键可能增删——双向映射用
  CustomNSError 测试钉死（65 节），别信运行时"看起来没丢"。

```swift
do { try sync() }
catch let e as SyncError {                   // 窄分支在前
    log.error("sync", metadata: ["case": "\(e)"])   // 结构化带载荷
} catch {
    log.error("sync-failed", metadata: ["err": "\(error)"])
    throw error        // 或显式转译；吞 = 事故报告里永远少一段
}
```

## 67. precondition 家族与可恢复策略

- 四层强度表：assert/assertionFailure（debug 专用，-O 消失）→
  precondition/preconditionFailure（-O 保留、-Ounchecked 消失）→
  fatalError / Never 返回（全档都在）。选择依据是"违例是 bug 还是环境"，
  不是写代码时的情绪。
- 不变式分类法：内部不变式（调用方无法触发的假设：索引、缓存一致性）用
  precondition——生产也保留（廉价分支 + 崩溃即取证）；**用户可触发的
  条件（文件缺失、输入非法）是业务流**，走 throws——把用户输入崩给
  崩溃监控是事故，把内部腐坏吞成错误是更隐蔽的事故。
- throws(Never) 标"全函数"：`func hostName() throws(Never) -> String`
  在类型上声明永不失败，配合泛型传播（63/64 节）让上层免 try——文档级
  注释没有这种强制力；total function 的新正统写法。
- 崩溃卫生：precondition 消息写"违反的不变式 + 上下文 id"；fatalError
  前用 os_log fault 留痕（崩溃报告不含控制台）；能自证根因的 precondition
  消息，省一次完整复现。
- -Ounchecked 现实：只在逐函数基准证明检查是热点后点状使用（几乎从不是）；
  全开换来的是"debug 能跑 release 坏"的 UB 定位地狱（99 节同款教训）。

```swift
func dequeue(at index: Int) -> Element {
    precondition(index < count,
        "index \(index) beyond \(count): queue corrupt, tag=\(tag)")
    return buffer[index]       // 内部不变式：生产保留检查
}
func cachedConfig() throws(Never) -> Config {
    Config.defaults           // 全函数：类型层声明，上层免 try
}
```

## 68. 错误通道选型：Result 与 throws

- 分界线一句话：**回调世界用 Result，async 世界用 throws**。completion
  handler 的调用方要"现在拿到一个结果值"，Result 是值、可存可传可组合；
  async/await 有原生异常通道，再包 Result 是双份语法噪音。
- 禁止双轨：同一 API 同时暴露 `fetch(completion: (Result<T, E>) -> Void)`
  与 `func fetch() async throws -> T` 本身没问题（后者是 adapter
  extension，48 节桥接纪律）；**业务代码里两条路径各自处理错误**才是
  双轨地狱——错误处理逻辑迟早分叉，adapter 把收敛点钉成一处。
- Result 的组合价值：map/flatMap 把"错误作为数据"的管道（校验链、重试
  策略、缓存写策略）写成纯值变换，测试不用异常机制；到边界再
  `.get()`（官方 Result→throws 桥）或 `try await r.get()`。
- Result<T, Never> 表达"不失败通道"（发布者、缓存命中）——类型上免 try，
  调用方解包一次；别用 Optional 冒充（丢语义）也别 Result<T, Error>
  （退回无型世界，等于白选）。
- 团队规范按层定：领域层错误进类型（63 节 typed throws）；进程边界（IPC、
  DB、序列化层）错误以 Result 存档传递；UI 层 catch 转用户消息；层不自创
  通道。

```swift
// 回调世界：Result 是值，可进缓存可进管道
api.fetch { (result: Result<[Item], FetchError>) in
    self.cache.store(result.map { $0.map(Snapshot.init) })
}
// async 世界：adapter 收敛双轨（48 节桥 + 恰好一次 resume）
extension LegacyAPI {
    func fetch() async throws -> [Item] {
        try await withCheckedThrowingContinuation { c in
            fetch { c.resume(with: $0) }   // Result → throws 一次转换
        }
    }
}
```

## 69. @Observable 宏内部机制

- 展开形态（5.9+，Observation 框架）：类获得 `@ObservationIgnored private
  var _registrar = ObservationRegistrar()`；每个被追踪存储属性变为计算
  属性——getter 里调 `_registrar.access(self, keyPath: \.x)`，setter 包
  在 `_registrar.withMutation(of: \.x) { }` 内写真实存储 `_x`。
- 记账语义：**getter 被谁读、读了哪个 keyPath** 登记进 registrar（线程
  安全，内部加锁）；SwiftUI 在 body 评估期间读 → 记 (视图, keyPath)，
  setter 的 withMutation 对登记方发通知——"谁读过谁被通知"的订阅粒度
  由这对调用机械地产生，没有任何魔法。
- @ObservationIgnored 剔除位：delegate、cancellables、缓存、非模型状态
  不进 registrar——改它们不该惊动 UI；忘标 = 空转通知协议，**误标追踪
  属性 = 观察"失明"**（改了不刷新，80 节头号事故）。
- 手写接入两档：单属性贴 @ObservationTracked（宏内部同款机制）；整套
  手动 = 自持 ObservationRegistrar + 属性读写手工包 access/mutate——
  自研响应式层/依赖收集器的作者路径，框架用户不必走。
- 陷阱：只有**存储属性**产生追踪；计算属性"被追踪"是因为读了存储属性——
  不读任何存储的纯函数计算属性，依赖变了也不刷新；getter 里做副作用
  （发通知、改状态）= access 递归与未知登记，必炸。

```swift
@Observable final class Model {
    @ObservationIgnored var cancellables = Set<AnyCancellable>()
    @ObservationIgnored private var cache: [URL: Data] = [:]
    var title: String      // 展开为 _title 存储 + access/mutate 计算属性
    var words: Int { title.split(separator: " ").count }  // 读 title 免费联动
}
```

## 70. Observation 与 ObservableObject 双轨陷阱

- "更高效"神话拆解：两套系统在"读到它的 body 都会重新评估"上**完全
  一致**；@Observable 省的是**通知范围**——objectWillChange 一响，观察
  该对象的**所有**视图全部重评估；@Observable 只通知"读过这个属性"的
  视图。粒度 = 通知范围，不是评估次数——body 里读了高频属性的，该重
  评估一次不少（78/80 节）。
- 共存规则：App 内可混用（不同子树不同系统），但**一棵子树只接一套**——
  @Observable 对象塞进 @StateObject/@ObservedObject 直接编译错（不遵循
  ObservableObject）；迁移按叶子视图逐个切，顶层切一半必留孤儿。
- ObservableObject 的剩余地盘：Combine 管道（objectWillChange 是
  Publisher，@Observable 不是）、需要"变更前通知"语义（objectWillChange
  fire 在 mutation 前）、存量代码。新代码默认 @Observable + @State/
  @Environment/@Bindable（79 节分工）。
- 语义差坑：objectWillChange 要求观察者在通知后**重新读**才能看到新值
  （"先 send 后改"是历史惯用法）；Observation 是 mutate 时对已登记
  keyPath 直接触发——老惯用法平移过来会做出"刷新了但读到旧值"的假象，
  迁移期 review 盯这一类。
- 迁移收益度量化：Instruments SwiftUI 模板对同一屏统计 body 评估数；
  真正的收益变量是"无关视图订阅数"——多子视图共读一个 store 的列表页
  收益最大，单视图页面几乎无感。

```swift
@Observable final class FeedStore { var items: [Item] = [] }   // 新系统
final class LegacyTimer: ObservableObject {                    // 老系统
    @Published var tick = 0
}
struct Screen: View {          // 双轨共存：App 可混，子树单轨
    @State private var feed = FeedStore()           // @Observable 用 @State
    @StateObject private var timer = LegacyTimer()  // ObservableObject 专用
}
```

## 71. 宏展开模型与调试手段

- 执行模型：宏是**编译器插件进程**——解析后、类型检查前，swift-syntax 把
  目标节点语法树喂给插件，插件回吐语法再走正常编译；展开代码参与完整类型
  检查，错误定位指向生成代码（行列号"穿越"到看不见的地方）。
- 调试第一工具 `-dump-macro-expansions`：swiftc / `swift build
  -Xswiftc -dump-macro-expansions` 编译时把每处展开源码 dump 出来；Xcode
  走 OTHER_SWIFT_FLAGS。工作流四步：dump → 贴进临时文件 → 单独编译复现
  错误 → 修宏再 dump——对着原文件猜宏展开是下策。
- 看不见的代价打包记：断点进不了展开代码内部（行号映射粗）、lint/format/
  git 历史全看不到生成物、"自动多出的成员"无法 review——宏的价值必须
  盖过这整包可观测性损失才值得写（75 节选型）。
- 信息边界：attached 宏拿得到**声明点语法 + 少量语义**（所在类型名、遵循
  的协议名）；拿不到整个模块、其他文件、宏展开结果的再展开——期望"宏读
  全工程"的需求是死路，该用代码生成。
- 测试宏：swift-macro-testing 的 assertMacroExpansion（原文件规范 + 缩进
  控制）钉住展开产物 + 对产物跑行为测试（展开后的类真的按语义工作）；
  宏测试很快，进 PR 必跑。

```swift
// 展开先于类型检查：错误落在生成代码上——先 dump 再定位，别盲改源文件
// CLI:  swift build -Xswiftc -dump-macro-expansions 2> expansions.txt
// Xcode: OTHER_SWIFT_FLAGS = -dump-macro-expansions，build log 里翻
```

## 72. Result Builder 深水与 DSL 编译成本

- buildBlock 元数墙：标准 buildBlock 家族至十参（ViewBuilder 顶配
  buildBlock 十个参数）；超过十个子视图**编译器不会自动嵌套**——用 Group
  分段或拆子视图。新手在第十一个元素撞墙后改 AnyView 是双输解法（81 节）。
- 分支税：if/else 走 buildEither(.true/.false) 产出
  _ConditionalContent<A, B>——类型尺寸翻倍、mangled 名膨胀；嵌套分支把
  类型复杂度指数堆高，编译时间与二进制体积双涨。深分支优先 if let/
  buildOptional（Optional 包装比双分支便宜）。
- 泛型爆炸机理：builder 把 body 编译成巨型嵌套泛型值——每层视图类型进
  父类型参数；千行 body = 千层类型名，类型检查超时是它、链接器超长
  mangled name 也是它。唯一解：拆 body（私有计算属性/小组件），让类型
  树浅而宽。
- 诊断武器：`-Xfrontend -debug-time-expression-type-checking` 按表达式
  打耗时，圈出 DSL 里最贵的表达式；CI 挂编译时间基线，DSL 增长曲线可见
  （110 节）。
- 自定义 builder 纪律：至少实现 buildBlock/buildOptional/buildEither/
  buildExpression；要支持 #available 记得 buildLimitedAvailability；
  builder 能力越多所有调用点越贵——每个能力都要值回编译时间。

```swift
var body: some View {
    VStack {
        Group { header; stats; chart }      // 第一段 ≤10 个
        Group { list; footer; toolbar }     // 第二段，编译器不自动嵌套
    }
}
// 千行 body 才是编译炸弹：拆私有 var/小组件，让类型树浅而宽
```

## 73. Property Wrapper 陷阱链

- 合成存储真相：`@Wrapper var x = v` 实际生成 `private var _x =
  Wrapper(wrappedValue: v)` + 计算属性 x；投影 $x 走 projectedValue；
  memberwise init 的参数标签仍是 x。访问级别跟随：public 属性意味着合成
  存储同语义——别在 wrapper 里藏"内部状态"。
- **class 包装器放 struct 上 = 共享存储**：struct 拷贝复制的是 wrapper 值
  （引用）——两份拷贝共享同一 class 实例，改一边两边动，值语义当场死亡；
  要引用共享就明写注释，要值语义 wrapper 必须是 struct（内部自己做 CoW，
  15 节骨架）。
- 嵌套顺序：`@A @B var x` 的访问链是 A(B(x))——前外后内；交换属性位置
  则 projectedValue 类型与语义全变（$x 拿到最外层的投影）；评审按"从里
  往外包装"念一遍再过。
- Codable 交互：合成 init(from:) 走 **_x**（wrapper 自身的 Codable），
  不是 wrappedValue——含 wrapper 的模型，反序列化行为完全取决于 wrapper
  实现；wrapper 没写 Codable = 该属性 decode 直接失败，报错绕得很远。
- 局部与协议限制：函数内 @Wrapper var 必须就地显式初始化，捕获进逃逸
  闭包后突变按值快照；协议声明里不能放包装属性（要"接口+包装"就上
  associatedtype + 泛型宿主）。

```swift
final class RefBox { var value = 0 }
@propertyWrapper struct Shared {              // struct 壳 + class 芯
    private var box = RefBox()
    var wrappedValue: Int {
        get { box.value } set { box.value = newValue }
    }
}
struct Panel { @Shared var count = 0 }
var a = Panel(); var b = a
b.count += 1        // a.count 也变：值语义死亡——共享就明写，否则改 CoW
```

## 74. @Entry 与环境值声明

- iOS 18（Xcode 16）起 @Entry 宏吃掉环境值三件套样板：key 结构体 +
  默认值 + 访问器——`extension EnvironmentValues { @Entry var theme:
  Theme = .auto }` 一行生成全部；同族还支持 Transaction / ContainerValues /
  FocusedValues，四类上下文值同一写法。
- 生成物：私有 Key 结构（遵循 EnvironmentKey、内置默认值）+ 计算属性读写
  self[Key.self]；因此**默认值写在声明位就是 API 契约**——改默认值 = 改
  全 App 缺省行为；先想"这个值有没有合理的全局缺省"再声明。
- 可见性坑：@Entry 生成的 key 是私有——外部模块**拿不到 EnvironmentKey
  本体**做底层覆写（老手写 key 可以设 public）；标准注入（.environment
  (\.theme, x)）不受影响。需要测试覆写/深度定制个别值时回到手写三件套，
  宏与手写可在同一 App 共存（同一环境值别双轨声明）。
- 老系统兼容（< iOS 18）：手写三件套原样保留——private struct Key:
  EnvironmentKey + defaultValue + 访问器；低目标工程统一手写比按版本
  双声明省心（少一层 #available 分文件的面具）。
- 纪律：环境值是"依赖注入的值通道"，不是全局变量——放配置与横切服务
  （主题、logger、权限门面），业务状态进 store（76 节）；@Entry 让加值
  太便宜，滥用后环境层变隐形单例网，评审要按"这值为什么走环境"过问。

```swift
extension EnvironmentValues {
    @Entry var analytics: Analytics = .noop   // 一行 = key+default+访问器
}
struct Root: View {
    @Environment(\.analytics) private var analytics   // 读取端不变
}
// < iOS 18 手写：private struct Key: EnvironmentKey { ... } 三件套
```

## 75. 宏与代码生成的选型成本

- 三维决策表：**构建时间**——宏插件编译一次反复执行（增量友好），codegen
  每次构建跑进程写文件；**调试体验**——宏展开不可见（断点/历史/review
  全盲，71 节），codegen 产物是普通 .swift 可读可改可断点；**工具消费**——
  codegen 产物所有工具都吃（lint、doc、搜索、跨语言导出），宏只有编译器
  看得见。
- 信息需求定生死：宏只看**声明点语法 + 少量语义**；需要"读全模块再生成"
  （DTO 映射总表、mock 清单、资源索引）必选 codegen（Sourcery/SwiftGen
  有全工程视图）；只要声明就能展开的（观察、环境、样板初始化）宏更顺手。
- swift-syntax 税：宏插件链整个 swift-syntax——冷构建十秒级、产物几十 MB；
  宏少而稳 = 一个独立宏包编译一次全工程复用缓存；宏多而散 = 每个 target
  一份插件依赖，构建图不可收拾。
- 双轨混合红线：同一份"生成语义"别又写宏又写 codegen 各生成一遍——版本
  分叉后没人说得清哪边生效；迁移期用命名约定隔离（手写版加 .manual
  后缀），删完旧轨再收约定。
- 隐性大头是团队成本：宏排障要 swift-syntax 功底，codegen 是模板语言
  门槛；两样都不熟时先手写 + 协议 + 泛型扛住——生成手段解决的是重复，
  不是复杂，样板没痛到别先上工具。

```swift
// 选型速记（先问信息面，再问工具面，最后问团队）：
// 声明即可展开、产物私有          → 宏（@Observable/@Entry 型）
// 要全工程信息/跨语言/工具消费    → codegen（Sourcery 清单/mock）
// 重复只有三五行                  → 手写泛型/协议组合，别上生成
```

## 76. 自定义观察容器与快照边界

- 形态定式：@MainActor @Observable class Store（**唯一写者**）+ 导出
  Sendable 快照 struct——UI 同 actor 直接读 store 零成本；跨 actor/Task
  出口一律 snapshot()（值拷贝；8 节 sending 同理）；store 不暴露可变
  引用、不暴露非 Sendable 内部。
- 快照字段纪律：只带消费方要的字段（不是模型照搬）；派生值在快照里
  **算好**（快照是纯值，算一次到处用），store 里的计算属性负责追踪联动
  （69 节）；快照 Identifiable 用稳定 id（77 节身份）。
- 单向流闭环：事件 → store 方法（唯一写点）→ 状态变更 → 观察通知 →
  视图；从快照/视图回写必须走 store 方法——"改快照再 set 回去"是双向流
  伪装，快照退化为共享可变状态，整条纪律报废。
- 测试红利：快照是纯值——值相等断言、表驱动、快照基线（108 节）全铺开；
  store 单测只测"方法 → 状态迁移"，UI 单测只喂快照，两层天然并行。
- 反模式四连：store 里混 @Published（双观察系统，70 节）；快照持有模型
  对象引用（Sendable 撒谎）；每秒全量 snapshot 大结构（拷贝风暴，按消费
  裁字段）；多个 store 交叉写同一状态（唯一写者破产）。

```swift
@MainActor @Observable final class FeedStore {
    private(set) var items: [Item] = []
    struct Snapshot: Sendable, Equatable {    // 出口：值拷贝
        let ids: [Item.ID]; let unread: Int
    }
    func snapshot() -> Snapshot {
        .init(ids: items.map(\.id), unread: items.filter { !$0.read }.count)
    }
    func markRead(_ id: Item.ID) { /* 唯一写点 */ }
}
// worker 拿 Snapshot 干活，回写只走 store.markRead(id)
```

## 77. View 身份与 diff 管线内幕

- 两套身份记牢：**结构身份** = 视图值在树中的位置（类型+位置），ForEach/
  Group 改不了它；**显式身份** = .id(x) 与 ForEach 的 id 参数。@State 等
  存储**按身份分配**——身份变 = 存储销毁重建：状态丢失 + 动画断链一起
  到账（85 节）。
- diff 管线（WWDC "Understanding and Improving SwiftUI Performance"）：
  视图重评估后，SwiftUI 用**反射逐比较存储属性**决定子视图要不要再评估
  body。两条推论：巨型视图的属性比较本身有成本；自定义视图字段越少 diff
  越便宜——大状态放子视图/模型，视图只留必要输入。
- **分支 = 换身份**：if/else 两侧类型不同 → _ConditionalContent 两分支
  各自身份，切换即销毁重建（@State 清零）；同型分支（if c { Text(a) }
  else { Text(b) }）保身份。要"视觉切换但保状态"：同型包装 +
  opacity/条件 modifier，或状态上移 store。
- .id() 的合法用法只有"数据驱动的强制重置"（表单重置、播放器换曲）——
  它本质是状态销毁按钮；ForEach 里 id: \.self 是值身份（结构体可用），
  **id: UUID().uuidString 现算 = 每评估换身份**：滚动跳变 + 全量重建，
  头号身份事故。
- 定位口诀："状态丢失/滚动位置跳"先查身份链：ForEach id 稳不稳 → 有无
  分支切换 → 有无 .id(动态值)——三处覆盖九成案例（90 节速查）。

```swift
ForEach(items) { row in        // Item: Identifiable 用模型稳定 id
    RowView(item: row)
}
// id: \.self —— 值身份：值变即换身份（结构体 OK；引用类型=指针身份陷阱）
// id: UUID().uuidString —— 每次评估新身份：状态丢失 + 全量重建事故
```

## 78. body 重评估成本模型

- 触发源四类记全：①自身 @State/@StateObject 变 ②body 读过的观察属性变
  （@Observable per-keyPath / objectWillChange 粗粒度）③父视图重评估且
  输入 diff 有变 ④身份变化（77 节）。**重评估 ≠ 重绘**——body 只产出值
  树，渲染走 diff；但 body 里的计算每次都真金白银。
- `Self._printChanges()`：body 首行加 `let _ = Self._printChanges()`，
  控制台直出变更来源（"@State"、"_model"）——"谁触发我"的第一工具；它
  是 debug 打印，上线前删干净（每评估一次打印）。
- 成本三层拆解：body 执行本身（纯计算多贵）→ 产出的 diff（存储属性比较，
  77 节）→ invalidation 传播的子树大小。优化顺序永远：先缩传播范围（拆
  视图/下沉状态）→ 再减 body 计算（派生缓存/上移模型）→ 最后才抠微优化。
- 拆分即局部化：高频变化的状态收进小叶子视图（60Hz 进度条进 8 行的
  ProgressLabel，别让根视图读它）——单值变化只重评估小视图；子视图参数
  用值类型 + 少量字段，diff 便宜（22 节拷贝成本同口径）。
- 测量纪律：Instruments SwiftUI 模板看 body 次数与耗时分布；本地 signpost
  包可疑 body；"优化后变快"必须拿出前后两个数字（56 节 SIL 证据同款）。

```swift
var body: some View {
    let _ = Self._printChanges()          // 定位触发源，上线前删
    ItemList(items: model.items)          // 读 items 的是 ItemList 自身
}
// 反例：根视图直接读 items + 渲染——items 每 tick 变化都重评估整树
```

## 79. @State/@StateObject/@ObservedObject 纪律

- 所有权三分铁律：**@State = 视图身份拥有**（存储随身份走，init 值仅首次
  生效）；**@StateObject = 视图拥有一个 ObservableObject**（autoclosure，
  每身份只求值一次）；**@ObservedObject = 外部注入引用**（不拥有，谁创建
  谁保活）。两大错配事故：要拥有的对象放 @ObservedObject（父重建时对象
  被重造/释放）；注入对象放 @StateObject（视图私吞，注入语义死亡）。
- @State 的"多次 init"陷阱：父 body 每次重评估都会跑视图 init，@State
  初值表达式跟着跑——**init 必须便宜且幂等**；重活放 @StateObject 的
  autoclosure 或搬进 store 方法。
- @Observable 时代新分工：拥有 = @State（可直接持 @Observable class，
  @State 不再只是值语义）；注入 = @Environment(Store.self) / 普通属性 +
  @Bindable 取 binding；@StateObject/@ObservedObject 只服务
  ObservableObject 双轨存量（70 节）。
- @StateObject 陷阱链：autoclosure 里的副作用在**首次渲染**才执行（晚于
  init 早于 body）；对象 deinit 时机由框架决定——关键清理（取消任务、
  落盘）别押在 deinit，用 .task/.onChange 显式生命周期（87 节）。
- 检查表：每个包装属性答三问——谁创建？谁保活？何时销毁？有一个答不上
  = 所有权设计缺失，先补设计再写代码。

```swift
struct EditorView: View {
    @State private var draft = ""              // 拥有：便宜且幂等
    @State private var model = EditorModel()   // 拥有 @Observable：正统
    @Environment(FeedStore.self) private var feed   // 注入
    @Bindable var settings: Settings           // 注入 + binding
    // @ObservedObject var o = Factory.make()  ← 经典事故：每次重建
}
```

## 80. @Observable 接入与过度刷新陷阱

- 追踪边界三规则：①**只有存储属性被追踪**（计算属性靠读存储搭车）②
  @ObservationIgnored 属性不进通知（delegate/cancellable/缓存归位）③登记
  发生在"body 评估期间的读取"——没被读到的分支不构成依赖，条件翻转时
  刷新一次并就地重登记。
- tick 风暴机理：30/60Hz 属性（进度、时钟、动画帧）被宽 body 读到 → 每
  tick 全树重评估。组合拳：高频值下沉叶子视图（78 节）+ 源头限频
  （async-algorithms debounce/throttle，47 节）+ **展示粒度与存储粒度
  分离**（内部存毫秒，UI 只暴露整秒属性）。
- 线程纪律：registrar 线程安全，但**给 SwiftUI 的通知要落 MainActor**——
  后台线程直改 @Observable 属性 = 刷新调度未定义/丢失；worker 算完 hop
  回主再写（41 节），或 worker 只吐快照（76 节）。
- @ObservationIgnored 双刃：误标追踪属性 = 读它的 UI 永不刷新（"改了没
  反应"头号新来源）；忘标内部缓存 = 每次 cache 更新也走通知协议（空转
  开销 + 意外依赖注册）。
- withObservationTracking 逃生舱：SwiftUI 之外的一次性观察（日志、遥测、
  命令式桥）——handler 触发后自动注销，持续跟踪须在 handler 里重注册
  （重入语义小心，别把一次性 API 当订阅用）。

```swift
@Observable final class PlayerStore {
    var currentTime: Double = 0          // 60Hz：宽 body 不许读它
    @ObservationIgnored private var last = -1
    var wholeSecond: Int = 0             // UI 订这个
    func tick(_ t: Double) {
        currentTime = t
        if Int(t) != last { last = Int(t); wholeSecond = Int(t) }
    }
}
```

## 81. AnyView 类型擦除代价

- 真实代价三笔：diff 时**运行时比较底层类型**（静态类型已擦，SwiftUI 只能
  动态比）；擦除边界以内无法再特化/细化 diff（黑盒）；列表场景破坏复用
  友好的身份结构。单次成本不高，问题是它常出现在"每行 × 每帧"的位点。
- 替代四选（按序）：分支用 @ViewBuilder（_ConditionalContent 保住静态
  类型，72 节）；分组用 Group（不动类型，只提供 DSL 作用域）；helper
  返回 some View（59 节反泛型，保特化）；真异构集合用泛型包装或 enum +
  switch 建模（载荷携带视图参数）。
- 合法残留位：插件式/服务端下发的动态视图表、跨模块必须隐藏具体视图类型
  的边界、老接口只能存 [AnyView] 的桥——**擦除一次且只在外层**，绝不
  嵌套（AnyView 里再包 AnyView = 复合税）。
- 事故特征：Instruments 里 AnyView 相关行 body 耗时高、List 滚动 allocs
  陡增（每行一个新 box）；review 抓 `AnyView(` 出现密度，新代码出现即问
  "能不能 Builder/some"。
- 冷知识：if #available 分支不必手擦——buildLimitedAvailability 保类型
  走原生机制（72 节）；为它包 AnyView 是白付税。

```swift
// 优先 Builder 分支（保类型、可特化）：
@ViewBuilder func row(for item: Item) -> some View {
    if item.isAd { AdRow(item: item) } else { ContentRow(item: item) }
}
// AnyView 留给"运行才知道视图类型"的边界，且只在最外层擦一次
```

## 82. List 与 Lazy 容器的预算管理

- 两套"懒"是两种东西：**List = 复用池模型**（UIKit 列表组件背书：行
  销毁/复用，内存 O(可见)）；**LazyVStack/LazyGrid = 只造不弃**（滚到
  谁实例化谁、实例常驻，内存 O(滚过)）——长列表用 LazyVStack 是内存爬升
  事故的固定剧本；超长数据老实 List 或分页窗口。
- 行高与估算：List 依赖行高估算做滚动条与预取预算，不稳定的动态行高
  （图片异步到达后变高）= 跳动 + 反复重排；图片显式 .frame、行高可预算
  化，是长列表性能第一杠杆。
- 预取窗口：List 在滚动方向多渲染视口外若干行——每行 body/初始化成本
  都乘以窗口倍数；行 body 预算按"亚毫秒"管，行内避免 GeometryReader
  （83 节）与重 onAppear 链（87 节）。
- ForEach id 与复用耦合：id 不稳定 = 复用池里行身份每帧重建（77 节身份
  + 81 节擦除叠加）；List 的 id 必须稳定持久——用数据主键，别用数组
  下标（增删行时下标漂移 = 状态错行，更隐蔽）。
- 测量口径：Instruments SwiftUI 模板滚动场景看 body 数与 hitch；Allocations
  看连续滚 10 屏后的稳态内存——LazyVStack 出现爬升曲线，先换 List 再
  谈别的优化。

```swift
List(items) { item in            // 复用池：内存 O(可见)
    RowView(item: item)          // 行 body 预算 <1ms；图片 frame 定死高度
}
// ScrollView+LazyVStack 适用面：行数有界（几百内）或分页窗口
// 超长无限流 + LazyVStack = 滚 10 屏后内存一倍，换 List 或上分页
```

## 83. GeometryReader 与布局陷阱

- 行为两事实：①对父级**贪婪全占**——拿到多少 proposal 吃多少（VStack 里
  放它，剩余空间全被吃光、兄弟视图被挤扁，"布局莫名变大"九成是它）；②
  子视图全部堆在 topLeading、脱离常规对齐/填充语境——"测量"与"布局"
  被搅在一起，是它所有坑的根源。
- 局部化定式：量某个视图用 `.background { GeometryReader { ... } }` /
  overlay 包住——background 收到的 proposal 就是目标视图的尺寸，
  GeometryReader 不再抢别人的地；读到的值经 onChange 存状态上抛，**别在
  body 里即时反哺布局**（尺寸反馈环：子改尺寸 → 父重测 → 子再改，掉帧
  死循环）。
- 现代替代：iOS 18 `.onGeometryChange(for: CGSize.self) { $0.size }
  action: { ... }`——纯观察者，不参与布局、无贪婪副作用；测量类需求
  一律优先它，GeometryReader 只留给"内容必须按自身几何排布"的真布局。
- 常见事故位：列表行里逐行 GeometryReader（每行一次测量 pass，82 节预算
  爆炸）；嵌套 GeometryReader 互相喂（反馈环）；忘了 safeAreaInsets——
  geo.size 与全屏坐标系语境混乱，先说清要哪个坐标系的哪个量。
- 坐标纪律：frame(in: .global/.local/.named(x)) 三坐标系别混用；跨层级
  对齐用 .coordinateSpace 命名空间，别手算 global 偏移（safe area 和滚动
  偏移联手教你做人）。

```swift
// 局部化：background 里量目标视图，不抢兄弟空间
Text("measure me")
    .background {
        GeometryReader { g in
            Color.clear.onChange(of: g.size) { size in report(size) }
        }
    }
// iOS 18+：测量优先 onGeometryChange（不参与布局、零贪婪）
.onGeometryChange(for: CGSize.self) { $0.size } action: { size = $0 }
```

## 84. Layout 协议与自定义布局深水

- 两个必实现：`sizeThatFits(proposal:subviews:cache:)`（答"这个 proposal
  下我多大"）与 `placeSubviews(bounds:proposal:subviews:cache:)`（摆放）。
  **proposal 语义表**：宽或高可为 nil（= 要理想尺寸）、.zero（= 要最小）、
  .infinity（= 贪婪）——sizeThatFits 会被拿多个 proposal 反复问，四个
  极端组合都要有确定行为，漏一个就是随机布局。
- cache 是性能口不是垃圾桶：sizeThatFits 可能被问多次、placeSubviews 里
  还要用测量结果——Cache 关联类型把首趟测量存进去复用；cache 必须是
  "子视图的纯函数"，依赖外部状态/随机性的 cache = 布局不稳定（SwiftUI
  不保证重算时机，108 节快照会抓到你）。
- placeSubviews 里再调 subview.sizeThatFits 会触发二次测量（proposal 一
  变就重跑）——能从 cache 拿就别再问；`subview.place(at:anchor:proposal:)`
  的 proposal 决定子视图最终尺寸，传 nil/.zero 要有明确意图（理想尺寸/
  最小尺寸策略写进注释）。
- spacing 与对齐补全：实现 `spacing(subviews:cache:)` 让父容器取你的间距
  正确；对齐导轨（alignmentGuide）在自定义 Layout 里**不自动生效**——
  非默认对齐直接在 placeSubviews 按自己的几何算 anchor，别指望导轨叠
  加进你的布局。
- 组合与切换：AnyLayout（iOS 16）类型擦除布局实例，if/else 切换布局保
  结构，比包 AnyView 便宜一档（81 节口径）；调试用固定 frame 走查四个
  proposal 极端——Layout 是纯函数，SwiftUI 里最可测的组件。

```swift
struct Row: Layout {
    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews,
                      cache: inout ()) -> CGSize {
        var w: CGFloat = 0, h: CGFloat = 0
        for s in subviews {                    // 极端 proposal 也要能答
            let d = s.sizeThatFits(proposal)
            w += d.width; h = max(h, d.height)
        }
        return CGSize(width: w, height: h)
    }
    // placeSubviews 从 cache 取测量，不二次问询
}
```

## 85. 动画与 Transaction 管线

- 管线认知先立：SwiftUI 动画跑在渲染服务（Core Animation 侧），body 只
  产出起点/终点值 + 动画参数——**不要靠每帧改状态模拟动画**（60Hz body
  风暴，80 节）；真要每帧（粒子、仪表）用 TimelineView 且对 schedule
  精打细算，动画连续性与身份绑定（77 节）。
- `.animation(value:)` 必带值：老无参 `.animation(.spring())` 会把闭包里
  **一切**变更都动画化（含数据驱动跳变、第三方状态）——现代写法按触发值
  圈定范围；`.transaction { $0.animation = nil }` 给子树显式关动画，
  封住深链动画逃逸面。
- withAnimation / withTransaction 的边界：withAnimation 包住的变更整体挂
  一个 transaction，嵌套时内层覆盖外层；程序化变更想**禁**动画的正规写法
  是 `var t = Transaction(); t.animation = nil; withTransaction(t) { ... }`，
  不是想办法绕开 transaction。
- matchedGeometryEffect 身份要求：一对 (id, namespace) 同一时刻只能有一侧
  在树上——两侧同帧并存 = 几何互抢（闪烁/错位的经典）；标准用法 if/else
  换分支，顺带按 77 节想清楚状态归属。
- 首帧/初始化动画：onAppear 里 withAnimation 追加经常撞首帧竞态"跳一下"
  ——初始状态的动画用 .animation(_:value:) 绑定真值源，让 diff 机制自己
  驱动，别和首帧抢跑。

```swift
Toggle("sync", isOn: $sync)
    .animation(.easeOut(duration: 0.2), value: sync)  // 按值圈定
    .transaction { $0.animation = nil }   // 需要时子树封禁
// 程序化无动画变更：
var t = Transaction(); t.animation = nil
withTransaction(t) { store.applyBatch() }
```

## 86. NavigationStack 与状态持久化

- path 双形态：**强类型 [Route: Hashable]**——路由枚举，destination 集中
  switch、编译期穷举（新增 case 漏注册是编译错不是线上 crash）；
  **NavigationPath**——类型擦除、异构混装、带 codable 表示可做状态恢复。
  选型：自家路由一律强类型枚举；需要序列化恢复/跨栈透传才 NavigationPath。
- 注册纪律：`.navigationDestination(for:)` 一个栈内**一种类型只能注册
  一次**——重复注册直接运行时 crash（"A navigation destination for X
  was already declared"）；高发位是子视图自带同类型注册被复用进不同层。
  注册点收拢到栈根专用视图，其他层只 push 值不注册。
- 深链竞态：路径在 destination 注册完成前变更 = "pushed with no
  destination" 丢失（控制台警告即此）；根因是注册跟着懒加载视图走。修法
  固定两条：注册表常驻栈根（不懒）+ path 变更放 .task/onTask，让注册先
  于首次渲染完成。
- 状态持久化真相：path 是纯值数组，恢复 = 重建数组；但**视图级 @State 不
  随 path 恢复**（身份重建即丢）——深链要恢复的"页面状态"必须随路由载荷
  携带（Route.detail(id:) 带参重建），把 @State 当持久层是设计错位。
- iOS 18+：navigationDestination(item:) 单值推入/弹出的值语义糖；
  navigationTransition 缩放过渡留给大型层级切换，滥用 = 全 App "起飞感"。

```swift
enum Route: Hashable { case list; case detail(Item.ID); case settings }
NavigationStack(path: $path) {          // path: [Route]
    ListView()
        .navigationDestination(for: Route.self) { route in
            switch route {             // 强类型：漏 case 编译期报错
            case .list: ListView()
            case .detail(let id): DetailView(id: id)
            case .settings: SettingsView()
            }
        }
}   // 注册点只此一处：子层重复注册同类型 = 运行时 crash
```

## 87. task/onAppear 生命周期与取消

- 生命周期真相：**.task 挂在视图身份上**——出现启动、消失自动取消；
  `.task(id:)` id 变 = 取消旧任务重启新任务（数据键控加载的正解，替代
  手写取消检查）。裸 `Task {}` 脱离结构化取消（34 节纪律），onAppear 里
  Task{} 是"逃逸 + 复用触发"两宗罪叠加。
- onAppear 的复用真相：Lazy 容器滚动会**反复触发** onAppear（行滚回视口、
  导航往返），它不是"首载"是"出现"——副作用必须幂等；加载类工作全部
  搬进 .task(id:)，onAppear 只留轻量幂等埋点。
- 执行与阻塞：.task 继承视图 actor（MainActor）——首个 await 前的同步段
  在主线程跑，重解析/重解码先 hop（@concurrent 或 store 内下池，4 节）；
  while-true 轮询用 `try await Task.sleep` 承接取消（sleep 抛
  CancellationError 是取消传播路径，37 节三件套）。
- onChange 新签名（iOS 17）：两参 `.onChange(of: x) { old, new in }` 与
  零参版；旧单参版（newValue）进弃用序列；`initial: true` 让首值也回调，
  替代"onAppear + onChange"双订阅老模板；两参版按 Equatable 逐字段比，
  对象类型留意相等成本。
- 竞态复盘模板：连续换 id（快切 tab/搜索词）时旧任务被取消、新任务启动，
  但**网络层 in-flight 不自动取消**（URLSession 不看 Task.isCancelled）——
  数据返回后、写状态前必须落检查点（37 节），否则旧结果覆盖新请求。

```swift
DetailView(id: item.id)
    .task(id: item.id) {              // id 变：取消 + 重启，数据键控
        await store.load(id: item.id) // store 内部配合取消检查
    }
    .onAppear { analytics.screen("detail") }  // 只做幂等埋点
```

## 88. UIKit 互嵌性能边界

- UIHostingController 三坑：①固有尺寸随内容变化——内容依赖异步状态时
  尺寸跳变，用 `sizingOptions`（iOS 16+）显式声明尺寸策略（如
  .intrinsicContentSize），省掉全量 Auto Layout 往返；②把 hosting
  controller 当 cell 用 = 每 cell 一棵 SwiftUI 树，滚动即内存/重排双爆炸；
  ③内容状态变化要能触发 invalidateIntrinsicContentSize（包装层接状态
  通知）。
- cell 正解：UICollectionView 用 **UIHostingConfiguration**（iOS 16+）——
  hosting 内容进复用池、配置对象按身份 diff；深度定制才退回
  representable 手卷。
- UIViewRepresentable diff 纪律：updateUIView 在**每次**父 body 重评估
  都调——Coordinator 存 last applied 值，变了才动 UIKit 视图（自己 diff、
  最小应用）；reloadData 级操作绝不能进 updateUIView（闪烁 + 滚动位置
  丢失双杀）。
- 回调线程自证：UIKit delegate 多在主线程回调，但 WKScriptMessageHandler/
  URLSession/第三方 SDK 不保证——桥接点用 `MainActor.assumeIsolated`
  （42 节）：不成立当场崩（fail fast），把"恰好是主线程"的沉默竞态变
  成可定位事故。
- 性能线：热列表（高频复用）优先纯 UIKit cell，SwiftUI 内容留给低频/
  整页场景；互嵌边界就是性能边界，Instruments 双模板（SwiftUI + UIKit
  hitch）对照看。

```swift
func updateUIView(_ chart: ChartView, context: Context) {
    guard context.coordinator.series != series else { return } // 最小应用
    context.coordinator.series = series
    chart.apply(series)             // 只更新数据源，绝不 reload
}
final class Coordinator: NSObject, ChartDelegate {
    let onSelect: @MainActor (Series) -> Void
    func chartDidSelect(_ s: Series) {            // delegate 线程不保证
        MainActor.assumeIsolated { onSelect(s) }  // 自证主线程，错则即崩
    }
}
```

## 89. 冷启动与首帧预算

- 测量三板斧：**MetricKit MXAppLaunchMetric**（iOS 16+，真机冷/温启动
  直方图——生产分布才是真相）；**Instruments App Launch 模板**（pre-main
  → didFinishLaunching → 首帧三段归因）；**自定义 os_signpost** 从入口
  到首帧渲染（归因到自己 App 的粒度）。
- pre-main 成本：dyld 加载/rebase/bind、ObjC +load 与静态初始化器；dev
  构建跑 `DYLD_PRINT_STATISTICS=1` 把 pre-main 打出来——超几百 ms 就去
  抓 +load 与静态器清单，别猜。
- **单例链拆解**：典型冷启动事故 = 首屏一棵"懒单例树"（A 首访建 B，B
  首访拉配置，配置没网同步等）——把"首帧必需"与"可延后"两清单化：
  必需的同步做，其余首帧后 `Task(priority: .utility)` 点火（87 节 task
  纪律 + 38 节 QoS）。
- 首屏 body 分治：首屏只渲染骨架 + 占位（非激活 tab 延后装配）；重列表
  用分页窗口（82 节）；图片后台 downsampling 解码——首帧 body 里一个
  同步大图解码就是 30ms 没了。
- 对照纪律：Debug 构建不能当证据（无优化 + 调试运行时）；TestFlight/
  生产看 MetricKit 分位数（p50 与 p95 的差距 = 低端机陷阱），每次发版
  前对启动基线跑回归。

```swift
let poster = OSSignposter(subsystem: "app", category: "launch")
let state = poster.beginInterval("first-frame", id: poster.makeSignpostID())
firstFrameCallback = { poster.endInterval("first-frame", state) }
// 非必需初始化：首帧后低优先级点火（38 节）
scene.onFirstFrame = {
    Task(priority: .utility) { warmCaches(); prefetchFeed() }
}
```

## 90. SwiftUI 高频事故模式速查

- 先对号入座再深读：身份链 77 节、body 成本 78 节、观察系统 80 节、
  布局 83 节、生命周期 87 节——本表是分诊台不是终点。

| 事故 | 症状 | 一句话定位 | 一句话修复 |
| --- | --- | --- | --- |
| ForEach 身份抖动 | 滚动跳变、行状态丢失 | id 是否稳定持久 | 换模型主键 id，禁现算 UUID |
| onAppear 重入 | 请求/埋点重复触发 | Lazy 复用反复"出现" | 副作用移入 .task(id:) |
| GeometryReader 全占 | 布局莫名撑爆、兄弟挤扁 | 谁在栈里吃满剩余空间 | background/overlay 局部化 |
| 宽 body 订 tick | 掉帧、CPU 持续高位 | _printChanges 看触发源 | 高频值下沉叶子 + 限频 |
| 双观察系统混挂 | 编译错或刷新失明 | @Observable 进了老包装 | 子树单轨，@State/@Environment 接 |
| 分支切换状态重置 | 切换后输入/滚动清零 | if/else 类型分支即换身份 | 状态上移 store 或同型包装 |

- 复盘纪律：每起线上 SwiftUI 事故回填一行（症状写用户视角、修复写可执行
  动作）——这张表的行数就是团队的学费账本，只增不清零。

## 91. SwiftData 生产陷阱清单

- ①**未版本化 schema 直接上生产**（头号错）：没有 VersionedSchema，改
  次模型用户数据就不可迁移（93 节）；上线第一天就带版本枚举，哪怕只有
  v1。②**把 @Model 当普通类跨线程传**：非 Sendable、绑定创建队列，跨
  actor 传 = 6 模式编译错 / 5 模式未定义——快照 struct 或
  PersistentIdentifier（92 节）。
- ③**一次性 save 几千条**：~7000 条一批 save，主线程 hang 秒级（实测
  案例）；分批数百 + 后台 ModelActor（94 节）。④**大 blob 不标外部
  存储**：模型里塞 [Data] 数组/大 blob 内存暴涨（整档加载 + 数组归档
  放大）——@Attribute(.externalStorage) + 数组归一化成子实体。
- ⑤**把继承当语言特性随手用**：@Model 继承是 iOS 26 才进的（WWDC25
  291）——旧系统直接不可用；共享字段用组合（协议 + 扩展）或扁平化。
  ⑥**autosave 时点错觉**：主 context autosave 在"系统觉得合适"的时刻
  落盘——批量导入期开着 = 反复分段写盘；导入期 autosaveEnabled = false，
  收尾手动 save。
- ⑦**容器管理散装**：ModelContainer 初始化昂贵（打开 store + 迁移），
  每屏建容器 = 反复打开 + 文件锁竞争；App 单容器一处构造，派生 context
  走 ModelActor（45 节同款单例纪律）。
- CloudKit 前置约束（开同步即生效）：属性全 optional 或带默认值、无
  unique 约束、关系必须 optional——这些是 schema 设计期决定，上线后补
  = 迁移地狱（93 节）。

```swift
let container = try ModelContainer(   // App 单例：一次构造
    for: SchemaV1.self,               // VersionedSchema 从第一天开始
    configurations: ModelConfiguration(url: storeURL)
)
context.autosaveEnabled = false       // 批量导入期关闭
try importInChunks(items, into: context)  // 分批 save（94 节）
try context.save()
```

## 92. @Model 的 Sendable 边界与快照模式

- 编译器判决：@Model 宏生成的类非 Sendable（可变 + 绑定创建它的 context
  队列），Swift 6 模式跨 actor 发送直接 error——**这是设计不是缺陷**：
  模型身份绑持久层队列，线程自由 = 数据竞争。别用 @unchecked 硬绕（7 节
  非法名单）。
- 跨界三件套按序：**PersistentIdentifier 是 Sendable**（传 id，目标 actor
  重取对象）→ **快照 struct**（值拷贝字段，主力模式）→ **@ModelActor**
  宏生成专属 actor（内嵌 container/context，后台批处理正解）。
- 快照模式骨架：@Model 只当持久层形态；UI/worker 消费 `struct
  ItemSnapshot: Sendable, Identifiable, Equatable`；store 边界 snapshot()
  出、upsert(snapshot) 进（76 节同款单向流）；快照 id 用
  PersistentIdentifier 或业务主键。
- 读写队列纪律：模型对象只在创建它的 context 队列访问（主 context =
  MainActor；ModelActor 内 = 自己的 executor）；"主线程建的 model 拿去
  后台读"在 5 模式无告警、6 模式拦截——趁编译器在，把债还在设计期。
- 测试红利：快照让持久层测试解耦（内存容器 + 快照断言）；并发测试里
  worker 只见快照，TSan 干净；快照相等断言顺手钉住 schema 演化。

```swift
@Model final class Item { var title = ""; var read = false }
struct ItemSnapshot: Sendable, Identifiable, Equatable {
    let id: PersistentIdentifier; let title: String; let read: Bool
}
@MainActor func snapshot(_ m: Item) -> ItemSnapshot {
    .init(id: m.persistentModelID, title: m.title, read: m.read)
}
@ModelActor actor ImportWorker {       // 宏生成 executor + context
    func run(_ batch: [ItemSnapshot]) throws { /* 唯一写点 */ }
}
```

## 93. SwiftData schema 版本与迁移

- 骨架：`enum SchemaV1: VersionedSchema`（把当期全部 @Model 收进
  schemas 列表）+ `SchemaMigrationPlan([阶段...])`；阶段两档：
  `.lightweight(from:to:)`（自动）与 `.custom(from:to:willMigrate:
  didMigrate:)`（两阶段钩子跑自己的批处理）。**线性链纪律**：v1→vN 每一
  环都必须在 plan 里，跳版没有捷径。
- lightweight 边界（能自动就别手写）：加属性且带默认值、属性转 optional、
  加新实体、`@Attribute(originalName:)` / `@Relationship(originalName:)`
  做重命名映射；出界即 custom：类型变换、拆表并表、关系语义修改。
- 迁移执行时机：container 初始化时**同步执行**——大迁移要有进度 UI 与
  超时口径；迁移失败的回滚路径要演练（备份 store 文件在迁移前拷一份，
  是最后一道保险）。
- 测试纪律：仓库存"每版本种子 store"夹具 → 从 vK 打开 → 断言迁移后
  快照（92 节）→ 全链滚动断言；迁移测试进 PR 必跑——**线上第一次跑
  迁移的地方不该是用户手机**。
- CloudKit store 现实：同步体系下自定义/破坏性迁移几乎不可用——开了
  CloudKit 的库按"只加不改"设计 schema（91 节前置约束），把演化压力
  压到字段设计期。

```swift
enum SchemaV2: VersionedSchema {
    static let predecessor = SchemaV1.self      // 链条显式声明
    @Model final class Item { ... }
}
let plan = SchemaMigrationPlan([
    .lightweight(from: SchemaV1.self, to: SchemaV2.self),
    .custom(from: SchemaV2.self, to: SchemaV3.self,
            willMigrate: { ctx in try splitTable(ctx) },
            didMigrate: { ctx in try cleanup(ctx) })
])
```

## 94. SwiftData 性能与批量写入

- 事故画像（实测案例）：一次下载 ~7000 条 → 逐条 insert + 单次 save →
  主线程 hang 秒级。归因：save 是事务落盘 + 对象图去重/索引维护，输入
  规模放大效应明显；解法三件套：后台 ModelActor 干、**分批（每批数百）
  save**、批间 await 让出（64 节同款让出纪律）。
- predicate 下推判定：#Predicate **能编译成 SQL 才下推**（等值/比较/
  逻辑组合/部分 contains）；不能下推的（自定义函数、复杂 keypath 算术、
  部分 enum/optional 组合）= 全量取回再内存过滤——数据量一大就是隐形
  O(n)。验证：launch 参数 `com.apple.CoreData.SQLDebug=1` 看生成 SQL，
  或 Instruments Core Data 模板看行数。
- 关系遍历 N+1：跨 relationship 访问逐对象触发 fault，"每行读一个关系
  属性"= n 次小查询；改二次查询（取本页 ids 再集合查回）或把热属性
  冗余进本表字段。
- 大 blob：@Attribute(.externalStorage) 把大 Data 移出主表（91 节④的
  正解）；不标的后果 = 每次行加载带全 blob + [Data] 数组归档序列化
  内存放大——研究案例"数组大 Data 内存暴涨"即此。
- 计数与存在性用 fetchCount（不取对象）；@Query 视图注意"store 每次变
  更都重评估"的成本——列表页给 Query 配 predicate/sort 让结果集有界。

```swift
@ModelActor actor ImportWorker {
    func importAll(_ rows: [RowSnapshot]) throws {
        for chunk in rows.chunks(of: 300) {      // 分批
            for r in chunk { Item(model: r) }    // 建即入 context
            try modelContext.save()              // 每批一事务
            try Task.checkCancellation()         // 批间检查点（37 节）
        }
    }
}
```

## 95. Core Data 与 SwiftData 并存纪律

- 禁忌第一条：**同一 store 文件被两套栈同时开**（NSPersistentContainer 与
  ModelContainer 指向同一 SQLite）= 双协调器并发写、锁与缓存互不知晓——
  数据损坏级风险；迁移期也要"一写多读"，绝不同时写。
- 切换期单向流固定五步：读老栈导出快照 → 写新栈导入 → 校验（行数 +
  抽样快照比对）→ 灰度翻转读路径 → 老栈转只读退役；迁移放后台 context/
  ModelActor，幂等可重跑（按批次记录水位，断点续跑）。
- 互开兼容验证：SwiftData 直接打开既有 Core Data store 取决于实体/属性
  名与可选性映射——先写"打开冒烟测试"（读一行老 store），失败再走导出
  导入路径；**别让用户手机当第一次兼容性实验**。
- backgroundContext 对照记：Core Data 成熟件（newBackgroundContext、
  mergePolicy、transactionAuthor、persistent history）在 SwiftData 侧对应
  ModelActor + 显式 save 纪律（94 节）；混用期**写路径统一到一套**（推荐
  新栈写），读路径可短期双开。
- 观察边界：NSFetchedResultsController 与 @Query 各管各的通知——双栈读
  会看到**不一致的中间态**；UI 只订一套，另一套仅做迁移引擎内部消费。

```swift
// 单向流骨架（迁移期唯一写者 = 新栈）
let legacy = try NSPersistentContainer(name: "Old")  // 只读用
legacy.loadPersistentStores { _, _ in }
// export(legacy) → snapshots → swiftDataImport.run(snapshots)
// 校验：legacyCount == newCount && 抽样 snapshot 相等（92 节快照断言）
```

## 96. 持久层选型决策

- 决策流（按序排除）：部署目标 < iOS 17 → GRDB/Core Data；必须 CloudKit
  同步 → Core Data（最成熟）或 SwiftData（接受 schema 约束，91 节）；
  要手写 SQL/复杂报表 → GRDB；数据量小（<几百键）→ UserDefaults/文件，
  **别顺手建库**。

| 维度 | SwiftData | Core Data | GRDB |
| --- | --- | --- | --- |
| 最低系统 | iOS 17+ | 老系统全覆盖 | 低目标可用 |
| 声明风格 | 宏 @Model 声明式 | .xcdatamodeld 图形 | 手写 SQL + 迁移 |
| CloudKit | 原生开关（带约束） | NSPersistentCloudKitContainer 成熟 | 无官方同步 |
| 原生 SQL | #Predicate 有限下推 | fetch 模板有限 | 全量 SQL |
| 迁移成熟度 | 版本化 + 两档阶段 | 轻量/重型迁移二十年件 | 显式迁移脚本可控 |
| 大批量吞吐 | 靠分批纪律兜 | backgroundContext 成熟 | 事务批量最快 |
| 观察管道 | @Query / Observation | NSFetchedResultsController | 自接 Observation |

- SwiftData 甜蜜区：新 App、模型域清晰、iOS 17+、团队不想碰
  .xcdatamodeld；代价：迁移经验浅（93 节）、性能要纪律兜（94 节）、
  生态工具少于 Core Data——选中它是选"少样板"，不是选"更稳"。
- Core Data 值守场景：存量库、复杂 fetch/重型迁移、成熟 fetched results
  管道；"新功能用 SwiftData、存量留 Core Data"的混栈必须过 95 节并存
  纪律，别裸混。
- GRDB 边界：SQLite 直控（FTS、窗口函数、手工索引）与自研同步团队的最爱；
  没有对象图观察——自己接 Observation（76 节 store 模式）正好是纪律位。

## 97. ObjC 桥接成本模型

- String→NSString：small string（≤15B 内联）转 NSString 必须建对象；
  native large 可零拷贝共享缓冲；**高频 ObjC 调用循环**里每轮传 Swift
  String 都可能重桥——循环外 `as NSString` 一次复用（21 节同款）。
- Array→NSArray：as NSArray 惰性桥、共享缓冲零拷贝；桥完再在 Swift 侧
  写 = CoW 触发整拷（103 节）；[Any] 过 ObjC 边界逐元素装 __SwiftValue/
  NSError 箱——异构数组的桥接税按元素数付。
- **for-in over NSArray 的隐藏装箱**：直接遍历 NSArray（没转成 Swift 数组）
  走 NSFastEnumeration，每个元素回来都包成 Any——大数组热循环先 `as
  [String]` 一次性转换（付一次拷贝税、免逐元素装箱），或推动接口层返回
  原生类型。
- 字典/Set 桥接：NSDictionary→[String: Any] 键值逐个验型 + 装箱；
  AnyHashable 双层箱（103 节）。边界设计原则：接口给具体类型（[String]
  而非 NSArray），把桥接税收到一处。
- 量级感：单次桥接几十 ns 级（小串小集合），放进 60Hz × N 元素循环就是
  毫秒级——桥接成本永远乘以频率；Instruments 搜 bridge/containers 符号
  定位热点。

```swift
let ns = str as NSString          // 桥一次
for _ in 0..<count { objcAPI.take(ns) }   // 循环内复用，杜绝重桥
// NSArray 直遍 = 逐元素装箱：热循环前先 as [String] 一次转换
let words = nsArray as? [String] ?? []
```

## 98. C 指针纪律与别名假设

- 核心法：编译器对"内存里是什么类型"有假设（别名假设）；`assumingMemoryBound(to:)`
  是**断言不是转换**——内存真不是那个类型 = UB，-O 下优化器已按假设重排，
  行为随编译器版本漂移。合法三场景：自己 allocate<T> 又擦成 raw 后拿回；
  C API 契约声明返回类型已知；其余一律 withMemoryRebound。
- `withMemoryRebound(to:capacity:)` 的规矩：只在作用域内改判读、要求
  尺寸对齐全等、capacity 按**目标类型元素数**算（byteCount/stride）；
  跨 rebound 边界持有前类型指针再用 = 双重别名假设，UB。
- raw 正道优先：`load(fromByteOffset:as:)` / `storeBytes(of:as:)` 不做
  类型重绑定、不引入别名假设——慢一点但安全；类型已知且要批量操作才
  上 rebound（性能位点用基准证明值回票价）。
- init/deinit 配对账：typed allocate → initialize(from:)（不初始化就读
  = UB）；deinitialize(count:) → deallocate()；构造一半失败只 deinit
  已初始化的前缀——把"分配+初始化/销毁"封成工具函数，别让人手抄四步
  （15 节 ManagedBuffer 思路同源）。
- 团队守则：assumingMemoryBound 出现即触发评审三连问——"这块内存最初
  什么类型？谁写的？凭什么断言？"；答不出就改 raw load 或重新设计。

```swift
let raw = UnsafeMutableRawPointer.allocate(byteCount: 4 * n, alignment: 8)
defer { raw.deallocate() }
let first = raw.load(as: Int32.self)          // 单点读：不 rebind
raw.withMemoryRebound(to: Int32.self, capacity: n) { buf in
    for i in 0..<n { buf[i] &+= 1 }           // 批量：局部 rebind
}                                             // 退出即恢复原判读
```

## 99. 独占访问法则与动态检查成本

- 法则本体：一块内存在任一时刻——要么一个写者独占，要么多个读者共享。
  违规时 -Onone 由**运行期动态检查**直接 trap（"Simultaneous accesses
  to 0x… but modification requires exclusive access"）；-O 下检查消失
  转为**编译器假设**——违规代码在 release 是静默数据竞争（拿 UB 换了
  显形难度，debug 能跑 release 坏的固定剧本）。
- writeback 机制：对计算属性/下标/inout 传参，编译器生成临时变量 + 退出
  时拷回；嵌套访问同一地址（swap(&a[i], &a[j])、同一属性进两个 inout
  参数、闭包捕获 inout 转义）就是执法现场——trap 栈顶永远停在 writeback。
- 高发清单背下：数组下标双 inout、inout 属性再调方法、遍历中变更容器；
  修复全是同一招：**局部 var 拷出 → 算完 → 一次性写回**——没有任何一个
  嵌套访问值得保留。
- 性能口径：动态检查只在 debug 档付钱（writeback + 检查）；release 的
  "收益"是编译器按法则省掉别名防御——所以 -enforce-exclusivity=
  unchecked 是给基准证明后的点状豁免，全开 = 永久打开"debug 好使 release
  坏"的窗口，定位成本远超检查成本。
- 与新特性的关系：Span 借用（20 节）、consuming/borrowing（16 节）、
  ~Copyable 的所有权语义全部建在同一条法则上——先内化"独占写"，再谈
  所有权微优化，顺序反了就是拿优化赌 UB。

```swift
func swapAt(_ arr: inout [Int], _ i: Int, _ j: Int) {
    // 调用侧反例：swap(&arr[i], &arr[j]) → 独占访问 trap
    let (a, b) = (arr[i], arr[j])   // 值拷出：零嵌套访问
    arr[i] = b; arr[j] = a          // 一次性写回
}
```

## 100. 指针生命周期与 Span 整合

- 逃逸三事故位（背下来）：①把 withUnsafeBytes / withUnsafeMutablePointer
  闭包里的指针**存进属性或捕获进 Task / DispatchQueue.async**——闭包
  返回缓冲即亡，指针变野；②**返回指针**出闭包（经典 `return ptr`）；③
  传给"会留着指针"的 C API（回调型/注册型）——先 withExtendedLifetime
  钉住宿主（30 节三事故位同源）。
- withUnsafeTemporaryAllocation(of:capacity:)：临时块走池化分配、作用域
  内强类型——中转缓冲（编解码、与 C 交换一帧）的标配，替代手写
  allocate/deallocate 对（98 节配对账少一对是一对）。
- ASan 纪律：逃逸类 UB 只在缓冲真的被复用/释放后显形，而 ASan 构建常关
  优化——"ASan 过了"不等于 -O 安全；把 ASan × -O 组合进 CI 矩阵跑一轮
  （30 节同款教训），优化器的内联才会把逃逸窗口真正打开。
- Span 整合姿势：跨函数传"借来的一块内存"用 Span（20 节）——不落属性、
  不跨 await；与 C 边界交换时 Span 参数把"裸指针 + 长度"升级为"借用
  视图"，生存期锚在调用方栈上，review 有抓手。
- 团队守则：`Unsafe*Pointer` 出现即触发三连问——"为什么不是 Span/Array/
  值语义？"；例外清单（编解码内核、C 互操作适配层）显式登记，清单外的
  新增要评审特批。

```swift
func encode(_ frame: Frame) -> Data {
    withUnsafeTemporaryAllocation(of: UInt8.self, capacity: frame.size) { buf in
        let n = frame.write(into: buf)       // 作用域内填充
        return Data(bytes: buf, count: n)    // 拷出边界，指针不逃逸
    }   // 出闭包即回收，无配对手工账
}
```

## 101. ObjC 运行时边界：swizzle/KVO/归档

- swizzle 生存条件：目标方法必须走 objc_msgSend 且 runtime 可查——
  Swift 方法非 @objc 在 selector 世界根本不存在；@objc 但被 final/WMO
  **静态化的调用点**已直接派发，swizzle 只影响从 ObjC 进来的调用——
  "我 swizzle 了没效果"的标准答案（102 节去特化同根）。
- swizzle 上线前跑双路径矩阵：ObjC 调用入口 + Swift 调用入口都要验证；
  依赖 swizzle 的第三方 SDK（埋点、热修）与你的静态化优化天然相克——
  全量冒烟不是可选项。
- KVO 三前提：@objc 类、**dynamic** var、观察者 dealloc 前移除（重复
  remove = crash）；Swift 值语义代码用 Observation（69 节），KVO 留给
  ObjC 遗产（NSProgress 观察、第三方回调桥）。
- 归档决策：NSCoding 要求 @objc 类 + initWithCoder，对 struct/enum 直接
  判死刑——**新持久化一律 Codable**（值类型/枚举/Sendable 全支持）；
  边界必须收 ObjC 数据时用 NSSecureCoding 的 requiresSecureCoding 收紧
  （防反序列化注入面）。
- selector 生态位：#selector + @objc 的界面（target-action、Notification
  名、 timers）仍是 UIKit 集成面——保持 @objc **最小暴露面**，别为省事
  开放隐式导出（102 节清单）。

```swift
extension UIViewController {
    @nonobjc func logAppear() { }   // 防 selector 撞名：系统类别同名冲突
}
// KVO 前提清单：@objc class + dynamic var + dealloc 前移除观察
// 新代码观察走 Observation（69 节），NSKeyedArchiver 换 Codable
```

## 102. @objc 暴露的成本与语义

- 成本模型：@objc 方法调用 = objc_msgSend（方法表/缓存查找 + 间接跳转），
  单次不贵，但**彻底关死特化与内联**（56 节六根因之一）——消息密集循环
  里差距十倍级。边界原则：@objc 薄壳进、纯 Swift 内核干、结果出。
- 隐式暴露清单（不写 @objc 也算暴露）：override ObjC 方法、@IBAction/
  @IBOutlet/@NSManaged、遵循 ObjC 来源协议（UITableViewDelegate 等）；
  Swift 4+ 其余一律要显式 @objc——"我以为暴露了/没暴露"两个方向都是
  事故（selector 找不到 / API 意外进 ObjC 面）。
- @nonobjc 两个正当用途：斩断隐式导出（override 场景要"消失"于 runtime）；
  **防 selector 撞名**——给 UIKit 类写扩展时，私有方法名与系统分类撞
  selector = 奇诡崩溃，@nonobjc 一刀两断（101 节）。
- 硬边界是设计在拦你：泛型方法不可 @objc、struct/enum 不可、@objc 协议
  不能有泛型要求——这些编译错别找绕过姿势，改设计（泛型内核 + @objc
  壳）。
- 桥语义：@objc 方法抛的 Swift 错误自动桥 NSError（66 节成本）；参数/
  返回的 Swift 值类型走桥接税（97 节）——@objc 面的签名尽量用 ObjC 原生
  类型，把转换收在薄壳里。

```swift
@objc final class Bridge: NSObject {
    private var core = Core()          // 纯 Swift 内核：可特化可内联
    @objc func refresh() {             // 薄壳：进/出各转换一次
        core.refresh()                 // 热逻辑不在这里付 msgSend 税
    }
    @nonobjc func fastPath() { }       // 防 selector 撞名 + 不进 ObjC 面
}
```

## 103. 集合桥接的隐藏复制

- 零拷贝是有条件的：`nsArray as [Any]` 共享缓冲（不拷），但**首写触发
  整拷**——NSArray 侧还持着缓冲，isKnownUniquelyReferenced 恒 false
  （14 节机制）；"桥来的大数组要改"：首改 O(n) 拷贝 + 双倍峰值内存
  （22 节扩容窗口同款），Allocations 里一次陡增。
- NSMutableArray 单向门：Swift Array → NSMutableArray 要显式
  NSMutableArray(array:)（拷贝）；反向桥来后 Swift 侧写不回写
  NSMutableArray（CoW 各自为政）——两个世界的"同一份数据"只是幻觉，
  "改了没生效 / 生效了两份"先查桥。
- AnyHashable 双层箱：外壳 Box + 内层值，作字典 key 时哈希与相等走两层
  间接；String 与 NSString key 混存时值相等但类型不同——部分 C API 按
  ObjC 类判等，**键类型进容器前归一**（统一转 String）是唯一稳法。
- Data/NSString 同款：从 NSData/NSString 构造的 Data/String 与源共享；
  Swift 侧后续写 = CoW 拷贝脱离——大资产（图片字节、大 JSON）桥接后
  别反复 in-place 改。
- 设计守则：桥接点集中"一次性转换 + 类型归一"，转换结果以原生类型下传
  （97 节）；热路径反复 as 的写法，靠想象中的"桥接缓存"救命是错觉。

```swift
let shared = nsArray as [Any]       // 零拷贝共享（此刻）
var mine = shared
mine.append(obj)                    // 此刻 O(n) 整拷：NSArray 还持着缓冲
// 键归一：String/NSString 混存 → 进容器前统一成 String
dict[key as String] = value
```

## 104. C++ Interop 现状与风险

- 支持面（6.x）：可 import C++ 头——class/成员函数/构造、命名空间函数、
  enum class、重载算子；std::string/std::vector 等有官方映射；引用映射
  Unsafe(Mutable)Pointer。**模板不进 Swift**：只有 import 的具体声明可见，
  实例化全留在 C++ 侧。
- 所有权与传参：按值 C++ class 参数 = 拷贝构造进函数；**move-only 类型与
  移动语义**的映射靠 Swift 侧 consuming 配合、规则仍在演进（16 节所有权
  词汇是共同语言）；引用参数读改走 UnsafeMutablePointer 显式——所有权
  语义在边界失真，靠 C++ 侧薄封装把关。
- 异常红线：C++ 异常**跨进 Swift = std::terminate/未定义**——C++ 边界
  函数必须 catch-all 收敛成返回码/optional/Result；这条不是风格建议，
  是进程存活问题，CI 里加异常路径测试。
- 风险面板：experimental 位仍在动（命中编译器 bug 的概率高于纯 Swift
  项目）、调试器对 C++ 类型的可视化与 Swift 混栈仍糙、SPM 侧要
  swiftSettings: [.interoperabilityMode(.Cxx)] 且工具链配套——先行采用
  等于接受较新工具链绑定。
- 采用边界建议：写一层 C++ facade（平 API、值语义参数、错误码化），
  Swift 只 import facade 头；**禁止 std:: 类型漏进 Swift 公开 API**（泄漏
  实现与编译耦合）；先非核心模块试点 + TSan/ASan 矩阵，再扩面。

```swift
// SPM: .target(name: "Bridge", swiftSettings: [.interoperabilityMode(.Cxx)])
// C++ 侧 facade：catch-all 收敛异常、值语义参数、错误码返回
// Swift 侧只 import facade 头，公开 API 不出现 std:: 类型
```

## 105. swift-testing 采纳纪律

- 心智差异四条：**并行默认**（并发任务组跑，XCTest 是进程内串行——全局
  可变 fixture 第一批爆雷，9 节同款）；#expect 是宏（捕获整个表达式、
  失败打出子表达式值，比 XCTAssertTrue 强一代）；**参数化 @Test
  (arguments:) 一等公民**（多集合笛卡尔积，成对数据要 zip）；测试即
  函数（@Test 标注 + @Suite 分组，不继承基类）。
- traits 体系：@Test(.disabled(if:)、.timeLimit(.seconds(1))、
  .tags(.fast)、.bug("…", id:))；@Suite(.serialized) 局部关并行；tags
  搭 CI 快道/全道（快道必绿才合 PR）；timeLimit 把"超时"变成断言语义。
- 共存迁移：同一 target 双框架合法（Xcode 16+ 同跑）——新测试全用
  swift-testing，老测试"改到哪迁到哪"漂移；机械映射：XCTestCase→@Suite、
  testXxx→@Test、XCTAssertEqual→#expect、setUp→init/defer。
- 独有能力真用起来：参数化替代 for-in 断言循环（失败能定位到第几组
  输入）；`try #require(...)` 解包失败带源信息；await 直接写无仪式；
  并行默认暴露真实竞态——**别用 .serialized 掩盖**，修 fixture 隔离
  （106 节）。
- 快照基线（108 节）用 tags 圈禁快道；timeLimit 值要留 CI 机器余量，
  贴边必 flaky。

```swift
@Suite struct PaymentTests {
    @Test(arguments: [10, 99, 1000])         // 每组独立报告
    func fee(amount: Int) {
        #expect(Payment.fee(amount) == expected(amount))
    }
    @Test(.timeLimit(.seconds(2))) func fetch() async throws {
        let v = try #require(await store.fetch())   # require 解包
    }
}
```

## 106. 并发代码测试方法

- actor 直测：actor 方法本来就是 async——`await store.apply(...)` 直接
  断言，不需要绕道主线程；要测**交错**，把"可注入挂起点"设计进 API
  （await injectedSuspend()）复现"跨 await 前提失效"（39 节）——比祈祷
  时序复现可靠一百倍。
- 时钟注入：ContinuousClock 抽成泛型 Clock 参数，测试喂 TestClock/手动
  推进（swift-clocks 或自写 50 行）；**禁真实 sleep**——await
  Task.sleep 的测试 = 慢 + 竞态双输；超时语义测"advance 到点触发"，不是
  "等真到点"。
- 取消三时序模板（每条并发路径至少各一测）：①发起即取消（首 await 抛
  CancellationError）②执行中取消（检查点处抛，37 节三件套落位）③取消后
  清理（defer/收尾仍执行）；断言用 `await #expect(throws:
  CancellationError.self) { … }`。
- 死锁与假死回归：semaphore/同步派发死锁（50 节）在测试里表现为"永不
  结束"——timeLimit trait 兜底判失败，别让假死测试拖垮 CI（105 节）。
- TSan 是并发测试的一部分：CI 矩阵里并发 suite 必跑 ThreadSanitizer 档；
  TSan 红一定真红，TSan 绿不等于无竞态（盲区：未插桩的 ObjC/C 路径）——
  但"绿"仍是最低门槛。

```swift
@Test func midFlightCancellation() async {
    let job = Task { try await worker.run() }
    job.cancel()                    // 三时序之一：发起即取消
    await #expect(throws: CancellationError.self) { try await job.value }
}
// 执行中取消：注入挂起点复现跨 await 竞态，禁真实 sleep 碰运气
```

## 107. XCTest 迁移与 confirmation

- expectation 三去向：简单事件断言 → **confirmation**（`await
  confirmation("received") { confirm in … }`）；多事件/复杂序列 →
  AsyncAlgorithms 的 merge/collect 当值流断言；普通异步 → 干脆 await
  直接断言（大半 expectation 只是"等一下"的拐杖，迁移时顺手删掉）。
- confirmation 细节：`expectedCount` 显式写（默认 1——多事件场景忘写 =
  到 1 个就过的假绿）；confirm 在任意线程安全；闭包退出未达 count =
  失败——不用再写超时轮询。
- XCTest 存量守住：`await fulfillment(of: [exp], timeout:)` 是 async 原生
  等待（替代 runloop 阻塞的 wait）；没迁完的测试**一个测试一套体系**，
  混用 = 死锁新来源（XCTestExpectation.wait 阻塞线程 vs async 挂起）。
- weak 生命周期断言模板：`weak var w = obj; obj = nil; #expect(w ==
  nil)`——失败即环存在（27 节尸检入口）；@MainActor 测试 + 必要时
  autoreleasepool 包裹（28 节）再判 nil，偶发不 nil 先排 autorelease
  再查真环。
- 迁移顺序：先迁最红最慢的并发等待类（confirmation 直改，flaky 当场
  降）；纯同步断言批量机械迁；迁移 PR 里跑一次构建基线（110 节）顺带
  收编译收益。

```swift
@Test func eventsArrive() async {
    await confirmation("two events", expectedCount: 2) { confirm in
        sub.onEvent { _ in confirm() }    // 任意线程 confirm 都安全
    }
}
// XCTest 等价物：await fulfillment(of: [expectation], timeout: 1)
```

## 108. 快照测试与确定性

- 基线类型两档：**文本快照**（视图树 dump、JSON、结构化输出）优先——
  diff 可读、免渲染抖动、跑得快；**渲染快照**（ViewImageRenderer 产出
  位图，iOS 17+ API/pointfree 封装）只给真视觉回归——成本与抖动高一档。
- 确定性清单（抖动全从这来）：Date/UUID/随机 → 注入固定 clock/seed；
  locale/timezone/calendar → 钉 en_US_POSIX/UTC/gregorian；Set/Dictionary
  顺序 → 输出前 sort；设备形态/动态字体/暗色 → 矩阵显式化，不吃宿主机
  状态；动画中间态 → 快照前禁动画（85 节 transaction）。
- 基线更新纪律：record 模式重写基线**必须走 PR 审图**——禁止 CI 自动
  覆写（自我确认测试）；基线 diff 与代码 diff 同 PR 时评审责任 = 确认
  "这个变化是意图"；图像 precision 按视图类型定，全 1.0 = 抖动地狱。
- ViewImageRenderer 现实：离屏渲染不含真机 GPU 差异（字体微调、抗锯齿）
  ——图像快照在不同宿主 CI 上天然抖：钉一台跑、或降级文本树快照；
  快照 API 全 App 统一一种，混两套 = 两套基线两套 flaky。
- 节奏：进 PR 门禁的只放视觉关键页（<20 个）+ tags 圈禁快道（105 节）；
  追覆盖率式的全量快照 = 维护税，甜点区是"快、准、绿得稳定"。

```swift
withSnapshotTesting(record: .missing) {    // 缺基线=失败，不静默生成
    assertSnapshot(of: view, as: .image(precision: 0.99))
}
// 抖动源逐个钉死：注入 clock、固定 locale、集合先 sort、禁动画
```

## 109. SwiftPM 深水：库演化与 unsafe flags

- evolution 开关：`.enableLibraryEvolution()`（swiftSettings）让 target
  以"可演化"模式编译（62 节 ABI 语义：全面压制跨模块特化/内联）——
  SPM 发**源码**不需要它（下游重编译），做 XCFramework/二进制分发才
  需要；误开 = 白付性能税，评审见它就问"发的是不是二进制"。
- unsafeFlags 红线：`unsafeFlags(["-Onone"])` 之类**带进版本化 tag 的包
  直接被下游解析拒绝**；调试差异的正路：.define 条件编译、
  debugConfiguration/releaseConfiguration 按配置分发、条件目标依赖
  （.when）——而不是 unsafe flag。
- binaryTarget 两形态：`.path(...)` 本地 xcframework/artifactbundle（开发
  期）与 `.url(...) + checksum`（发布）；zip 目录布局有硬约定（顶层目录
  名 = 产物名），布局错 = 解析期莫名失败；二进制 target 不参与 ABI 演化
  检查——发版前用真实下游工程联编验证一遍。
- 包结构与下游成本：target 拆太细 = import 面与构建图爆炸；太粗 = 增量
  失效（110 节同权衡）。对外稳定 API 收进少数 target，内部实现用
  internal 传播；依赖链别绕环。
- tools 版本语义：swift-tools-version 决定 manifest API 集与**最低工具
  链**——钉"最低支持版本"而不是最新（下游 CI 未必升）；声明 6.0 实际
  只在 6.2 测过 = 下游在 6.0 爆编译错（11 节探测同理），矩阵 CI 补齐。

```swift
.target(name: "Core", swiftSettings: [
    .enableLibraryEvolution()    // 只为二进制分发开（62 节性能税）
])
// unsafeFlags(["-Onone"]) 进版本 tag = 下游解析拒绝
// 调试差异走 .when(condition:) / debugConfiguration，不走 unsafe flag
```

## 110. 构建性能：WMO 与宏拖累

- WMO 双面账：整模块优化让跨文件特化/内联起飞（56 节），代价是**改一
  个文件 = 重编整个 target**——大 monolithic target 的增量迭代变慢；
  正解不是关 WMO，而是**拆 target**：发布配置 WMO 全开，开发迭代吃模块
  粒度增量；debug 本来不做 WMO，别在 debug 追运行性能（67 节同口径）。
- 宏拖累三笔账：宏 target 链 swift-syntax（冷构建十秒级起步）；**每个
  宏标注文件的编译都要跑插件**（宏密度线性付费）；宏生成代码再放大下游
  类型检查（72 节 DSL 税）——结构级解法只有宏独立成包复用缓存（75 节）。
- 显式模块构建：Xcode 26 起对 C 族默认开启显式模块，Swift 侧加速跟进
  ——PCH/module 缓存类老调优以新形态回归；升级工具链后**重测构建基线**，
  别沿用三年前的 build settings 教条（109 节 tools 版本同源风险）。
- 诊断三件：`-Xfrontend -debug-time-function-bodies` / `-Xfrontend
  -debug-time-expression-type-checking`（哪个函数/表达式在吃类型检查）；
  干净构建全量日志按文件排序找长尾；CI 挂"干净构建 + 增量构建"双基线，
  回归超阈值报警。
- 债务清单化：把"编译最慢十个文件"贴出来每次发版更新——类型检查复杂度
  （72 节泛型爆炸）与宏密度是两大复利项，清一次管半年。

```swift
// 类型检查热点定位（DSL/泛型复杂度第一嫌疑人）：
//   swift build -Xswiftc -debug-time-function-bodies \
//               -Xswiftc -debug-time-expression-type-checking
// CI 双基线：干净构建 + 增量构建，超阈值报警；宏独立成包复用缓存
```

## 111. 反模式清单：语言合法但生产必坑

- **try! / as!**：崩溃定位在"某个用户某次数据"——发布物里 try! 只允许
  "编译期已知不可能失败"；内置 JSON 资源解析也算高危（格式改动无测试
  就炸）。lint 禁令 + 特批注释评审。
- **可变单例**：`static var shared` + 各处直写 = 隐形全局网（9 节），测试
  并行化第一批爆雷（105 节）；正解 actor/注入式 store（76 节）。
- **NotificationCenter 当事件总线**：userInfo 无类型契约、观察者生命周期
  手工管、排查靠全文搜索——域内事件改类型化回调/AsyncStream（46 节），
  系统级事件（旋转、内存警告）才留 NotificationCenter。
- **defer 里塞 await**：defer 块**不能含 await**（编译错），于是有人写
  `defer { Task { await cleanup() } }`——逃逸任务脱离取消与顺序（34 节），
  清理时序失控；正解 do/catch 显式路径或结构化包装（onRelease 型 API）。
- **Timer 轮询代替事件**：0.5s 轮询"状态变没变" = 电量 + 竞态双杀（间隔
  内的变更丢失）；有事件源用事件（Combine/AsyncStream/Observation，69
  节），系统态变化用 KVO（101 节）。
- 快打其余：热循环 String 逐个 += 拼接（用 reserveCapacity 构造，21 节）；
  Date() 当计时打点（用 ContinuousClock，106 节）；
  DispatchQueue.global().async 包一切（改结构化 task + 显式优先级，38
  节）；catch 后 return 默认值吞错（66 节反模式榜首）。

```swift
func run() async throws {
    defer { cleanup() }      // 同步收尾 OK
    // defer { Task { await asyncCleanup() } }  ← 反模式：逃逸任务失控
    try await work()
    await asyncCleanup()     // 显式路径，或包一层 onRelease 结构化 API
}
```

## 112. 生产事故复盘三例

- **例一 · actor 重入双扣费**：现象是同一订单毫秒级两次扣费；TSan 全绿
  （逻辑竞态不是数据竞争，39 节）。根因：checkout() 里 guard 状态 →
  await 支付 SDK → 写状态——await 让第二次提交插队，guard 早已失效。
  修复与固化：同步段 in-flight 占位 + 归位校验（40 节同款）；跨 await
  前提失效进 review 清单；插队三时序测试（106 节）。

```swift
func checkout(_ order: Order) async throws {    // 例一修复骨架
    guard inFlight[order.id] == nil else { throw Duplicate() }
    inFlight[order.id] = .paying               // await 前落位
    let r = try await pay(order)               // 挂起点：可能被插队
    guard inFlight[order.id] == .paying else { return }  // 归位校验
    inFlight[order.id] = nil
    commit(r)
}
```

- **例二 · semaphore 全池假死**：现象"UI 能动、一切 async 转圈"，低配机
  必现（核少池小）。取证：线程转储里合作线程池**全部 worker** 卡在
  semaphore.wait，等一个老回调库的结果——池宽=核数，全阻塞 = 无工可用
  （50 节死亡清单首案）。修复与固化：桥接改 withCheckedContinuation
  （48 节）；评审禁令"async 体内出现 semaphore.wait / 同步派发等待"；
  死亡八项进新人培训，CI 线程转储演练当消防演习。
- **例三 · @Observable tick 风暴掉帧**：播放器页滚动掉帧、CPU 持续 40%+，
  迁移 @Observable 后回归（70 节"更高效神话"的反面教材）。取证：Instruments
  SwiftUI 模板 body 计数 60 次/秒、_printChanges 指向 store.currentTime
  （78 节工具）。根因：高频进度属性被宽 body 读取（80 节风暴机理）。
  修复与固化：进度下沉叶子视图 + 存储粒度与展示粒度分离（uiSecond 模式）；
  迁移 checklist 加"高频属性谁在读"；播放器滚动进性能回归基线。
- 复盘模板（三例共用）：症状（用户视角）→ 证据（工具产出，78/52 节）→
  根因（对应本库章节）→ 固化（可执行项进 checklist/测试/门禁）——事故
  不复盘 = 团队还会再交一次学费。
