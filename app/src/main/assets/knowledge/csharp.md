<!-- kb: id=csharp | role=builtin_coding_csharp | title=C# 专家知识库 | version=1 -->
# C# 专家知识库

## 必看内容

- **版本纪律**：生产只上 LTS（.NET 8 → 10 交错节奏，.NET 9 这类
  STS 只有 18 个月支持、2026-05 EOL，只用于预迁移）；LangVersion
  跟 TFM 走，跨 TFM 用新语言特性前先查"feature depends on
  runtime"清单——编译过 ≠ 运行时有 helper（见 1-8 节）。
- **库代码 async 三律**：ConfigureAwait(false) 全覆盖（挂 CA2007
  分析器）、CancellationToken 传到底、绝不 Task.Run 包同步库调用
  （见 14、18、21 节）。
- **sync-over-async 零容忍**：`.Result`/`.Wait()` 只允许出现在
  Main/入口层；.NET Core 没有 UI 那种经典死锁，但线程池饥饿型
  假死更隐蔽、更难归因（见 15、87、109 节）。
- **ValueTask 单次消费**：二次 await / 二次 GetResult / 存字段
  再消费都是运行时炸弹（池化开启后必抛 InvalidOperationException）；
  需要多次消费先 AsTask()（见 16 节）。
- **取消与失败的语义分界**：抛 OperationCanceledException 必须带
  对应 token，Task 才进 Canceled 态；吞 OCE 转成"空结果成功"是
  语义破坏（见 18、86 节）。
- **热路径四件套**：Span/Memory 租约、ArrayPool、SearchValues 与
  Frozen 集合、LINQ 退场——分配率直接决定 GC 频率与尾延迟
  （见 28-42 节）。
- **装箱与拷贝是结构体双杀**：in 参数必须配 readonly struct，
  否则每次成员访问防御性拷贝；接口装箱、闭包字段提升、内插
  ToString 是三大隐形税（见 36-37、66 节）。
- **GC 是分配的影子**：先降分配再谈调参；容器里 Server GC 按核
  圈地 + 默认堆硬上限约 75% 是 OOMKilled 头号剧本（见 43-50 节）。
- **DI captive 依赖是头号事故**：单例吃进 scoped 服务 = 并发损坏
  + 静默泄漏；ValidateScopes/ValidateOnBuild 必开，修复走
  ScopeFactory 模式（见 69、111 节）。
- **AOT/裁剪从第一天设计**：按字符串找类型、MakeGenericMethod、
  Emit/DLR 在 NativeAOT 全死；[DynamicallyAccessedMembers] 修根与
  源生成改造越晚越贵（见 9-11、75-78、102-106 节）。
- **可空注解是 API 契约**：[NotNullWhen]/[MaybeNullWhen]/[AllowNull]
  是消灭 `!` 和 `#nullable disable` 的唯一正道；泛型 T? 在
  class/struct/无约束下是三种语义，先背熟再写公共 API
  （见 53-54 节）。
- **测试的确定化**：时间走 TimeProvider 注入、等待用 FakeTimeProvider
  推进、并发用 Channel/调度器替身——Thread.Sleep 与真时钟测试是
  CI 不稳定之源（见 26、95-101 节）。

## 快速索引

| 想找什么 | 去哪一节 |
| --- | --- |
| 版本矩阵与升级顺序 | 1. C# 12-14 与 .NET 8-10 版本矩阵与升级纪律 |
| params Span 免分配调用 | 2. C# 13 精要：params 集合与泛型反约束 allow ref struct |
| 扩展块/扩展属性怎么写 | 3. C# 14 扩展成员与扩展块（extension everything） |
| field 关键字半自动属性 | 4. C# 14 半自动属性：field 关键字实战 |
| a?.b = c 空条件赋值 | 5. C# 14 空条件赋值与复合赋值放宽 |
| 隐式转 Span、nameof(List<>) | 6. C# 14 一等 Span 转换与 nameof 非绑定泛型 |
| GDV 去虚化与 .NET 9 提速 | 7. .NET 9 运行时红利：GDV 深化与集合提速 |
| 栈分配对象与 WhenEach | 8. .NET 10 基线：逃逸分析与栈分配对象 |
| FDD/SCD/NativeAOT 选型 | 9. 发布形态决策矩阵：FDD、SCD 与 NativeAOT |
| IL2026/IL2070 裁剪警告 | 10. Trimming 治理：IL2xxx 警告消化流程 |
| 哪些反射在 AOT 下必死 | 11. 反射的 AOT 代价与替代路径 |
| RuntimeFeature 能力探测 | 12. 多目标编译与运行时能力探测 |
| 状态机编译产物长什么样 | 13. async/await 状态机编译产物精读 |
| ConfigureAwait 语义真相 | 14. SynchronizationContext 与 ConfigureAwait 语义真相 |
| .Result 死锁与池饥饿 | 15. sync-over-async 死锁形态与根治 |
| ValueTask 为什么只能 await 一次 | 16. ValueTask 纪律：单次消费与池化内幕 |
| WhenAll 只抛第一个异常 | 17. Task.WhenAll 的异常聚合与结果收割 |
| token 传播与 Register 泄漏 | 18. CancellationToken 生命周期与传播纪律 |
| TCS 延续内联事故 | 19. TaskCompletionSource 与延续内联事故 |
| 异步锁怎么选 | 20. 异步互斥：SemaphoreSlim 与 System.Threading.Lock |
| LongRunning 的滥用 | 21. 线程池真相与 LongRunning 的滥用 |
| WhenAny 的 O(n²) 陷阱 | 22. Task.WhenAny 的 O(n²) 陷阱与 WhenEach |
| EnumeratorCancellation 合并 | 23. IAsyncEnumerable 深水：EnumeratorCancellation 与消费纪律 |
| Channel 背压与完成语义 | 24. System.Threading.Channels：生产者消费者基础设施 |
| await using 处置纪律 | 25. IAsyncDisposable 与 await using 处置纪律 |
| 时钟注入与 PeriodicTimer | 26. TimeProvider、PeriodicTimer 与确定性时间基建 |
| async 反模式速查 | 27. 异步反模式速查与评审清单 |
| Span 为什么不能跨 await | 28. Span<T> 生存期规则与逃逸分析 |
| scoped 与 UnscopedRef | 29. ref struct 纪律：scoped 与 UnscopedRef |
| stackalloc 大小预算 | 30. stackalloc 安全窗口与栈预算 |
| Memory<T> 与租约所有权 | 31. Memory<T> 与 IMemoryOwner 租约模型 |
| ArrayPool 归还陷阱 | 32. ArrayPool<T> 租借归还纪律 |
| 内插字符串零分配 | 33. 字符串工程：String.Create 与内插处理器 |
| Frozen/SearchValues/AlternateLookup | 34. 集合性能阶梯：Frozen、SearchValues 与 AlternateLookup |
| LINQ 该不该手写替代 | 35. LINQ 分配剖析与手写替换决策线 |
| in 参数与防御性拷贝 | 36. 结构体拷贝经济学：in、readonly 与防御性拷贝 |
| 装箱在哪里发生 | 37. 装箱暗坑清单与消解路径 |
| Tier0/Tier1/OSR 分层 | 38. JIT 深水：分层编译、Tiered PGO 与 OSR |
| 边界检查消除与 SIMD | 39. 向量化友好写法与边界检查消除 |
| 对象池生命周期护栏 | 40. 对象池设计与生命周期护栏 |
| BenchmarkDotNet 测量反模式 | 41. BenchmarkDotNet 纪律与测量反模式 |
| 热路径分配审计流程 | 42. 热路径分配审计工作流 |
| Gen0 预算与分配率 | 43. 分代模型与 Gen0 预算经济学 |
| LOH 阈值与 POH | 44. LOH 与 POH：大对象与固定堆 |
| GC 模式与延迟预算 | 45. GC 模式选择与延迟预算 |
| DATAS 与容器 GC 调参 | 46. DATAS 与容器感知 GC 调参 |
| 内存泄漏取证三件套 | 47. 内存泄漏取证工具链 |
| Finalizer 与 SafeHandle | 48. Finalizer 纪律与 SafeHandle 替代 |
| fixed 固定与碎片 | 49. 固定缓冲策略与碎片治理 |
| OOMKilled 事故剧本 | 50. 容器内存限制下的 GC 事故 |
| 协变逆变生产坑 | 51. 泛型型变生产坑：协变、逆变与不变 |
| 泛型数学与 static abstract | 52. static abstract 接口成员与泛型数学 |
| NotNullWhen 注解家族 | 53. 可空注解纪律：NotNullWhen 三件套 |
| 泛型 T? 三态语义 | 54. 泛型可空性深水：T? 与 notnull 约束 |
| record 选型决策矩阵 | 55. record class、record struct 与 readonly struct 选型 |
| record 相等与 with 陷阱 | 56. record 深水：equality 继承与 with 陷阱 |
| required 成员与反序列化 | 57. init 与 required 成员的反序列化兼容 |
| 主构造函数捕获泄漏 | 58. 主构造函数捕获语义与泄漏 |
| 集合表达式性能语义 | 59. 集合表达式性能语义与展开 |
| using 别名任意类型 | 60. using 别名进阶与 any-type 别名 |
| 模式匹配与判别式建模 | 61. 模式匹配深水与判别式建模 |
| Equals/GetHashCode 契约 | 62. Equals 与 GetHashCode 契约 |
| 运算符与复合赋值 | 63. 运算符重载与 C# 14 复合赋值 |
| 类型推断暗角 | 64. 类型推断暗角：三元、lambda 与重载决议 |
| 接口默认实现菱形 | 65. 接口默认实现的菱形陷阱 |
| 闭包分配与 lambda 缓存 | 66. 闭包分配与 lambda 缓存复用 |
| ValueTuple 逃逸边界 | 67. 匿名类型与 ValueTuple 逃逸边界 |
| Flags 契约与枚举键 | 68. 枚举深水：Flags 契约与类型安全 |
| captive 依赖事故 | 69. DI 生命周期纪律与 captive 依赖 |
| keyed services 用法 | 70. keyed services 与多实现策略 |
| IOptions 家族选型 | 71. IOptions 家族选型与配置热更 |
| DI 解析成本内幕 | 72. DI 性能内幕：CallSite 与解析成本 |
| HttpClient 套接字治理 | 73. HttpClientFactory 深水与套接字治理 |
| 服务处置与 Scope 验证 | 74. IDisposable 服务处置与 Scope 验证 |
| STJ 源生成实战 | 75. System.Text.Json 源生成实战 |
| fast-path 与 metadata 分野 | 76. fast-path 与 metadata 模式的性能分野 |
| 多态鉴别器安全 | 77. JsonDerivedType 多态与鉴别器安全 |
| 自定义 Converter AOT 化 | 78. 自定义 Converter 的 AOT 兼容 |
| Newtonsoft 迁移差异 | 79. Newtonsoft 到 STJ 迁移差异清单 |
| 序列化契约版本化 | 80. 序列化契约版本化与 tolerant reader |
| 异常到底多贵 | 81. 异常成本模型与 throw helper |
| when 过滤器与 throw; | 82. 异常过滤器 when 与重抛语义 |
| AggregateException 解包 | 83. AggregateException 解包纪律 |
| Result 模式取舍 | 84. Result 模式的适用边界与反噬 |
| EDI 跨边界重抛 | 85. ExceptionDispatchInfo 与跨边界重抛 |
| UnobservedTaskException 兜底 | 86. 进程级兜底：UnobservedTaskException 与崩溃策略 |
| 线程池饥饿诊断 | 87. 线程池饥饿：注入速率与诊断 |
| ForEachAsync 并发上限 | 88. Parallel.ForEachAsync 与并发上限 |
| lock/Monitor 纪律 | 89. lock 深水：Monitor 线程亲和与锁对象选择 |
| 伪共享与缓存行 | 90. Interlocked、伪共享与缓存行 |
| volatile 边界 | 91. volatile 边界与 ECMA 内存模型 |
| GetOrAdd 双调用竞态 | 92. ConcurrentDictionary 与 GetOrAdd 竞态 |
| 有界 Channel 背压 | 93. Channel 进阶：有界容量与背压 |
| AsyncLocal 流动方向 | 94. AsyncLocal 与 ExecutionContext 传播 |
| xUnit 并行陷阱 | 95. xUnit 家族选型与并行陷阱 |
| 集成测试隔离 | 96. WebApplicationFactory 与集成测试隔离 |
| 确定性时钟与并发重放 | 97. 确定性时钟与并发测试重放 |
| 快照与契约测试 | 98. 快照测试与契约测试 |
| Mock 边界 | 99. Mock 边界与过度 Mock 反模式 |
| 覆盖率与变异测试 | 100. 覆盖率与变异测试经济性 |
| CI 性能门禁 | 101. CI 性能门禁与基准回归 |
| NativeAOT 迁移清单 | 102. NativeAOT 迁移约束清单 |
| 反射退场的源生成方案 | 103. 反射退场：source generator 替代 |
| LibraryImport 现代封送 | 104. LibraryImport 与 P/Invoke 现代化 |
| Expression 在 AOT 的死路 | 105. 委托与 Expression 在 AOT 下的死路 |
| ALC 卸载与插件边界 | 106. 动态代码、插件与 Collectible Assemblies 边界 |
| async void 事故解剖 | 107. async void 事故解剖 |
| 静态状态反模式 | 108. 隐式配置与静态状态反模式 |
| 饥饿雪崩复盘 | 109. 事故复盘一：线程池饥饿雪崩 |
| LOH 碎片事故复盘 | 110. 事故复盘二：LOH 碎片与 Gen2 停顿 |
| captive 泄漏复盘 | 111. 事故复盘三：captive 依赖静默泄漏 |
| PR 评审清单 | 112. C# PR 评审清单（专家版） |

## 1. C# 12-14 与 .NET 8-10 版本矩阵与升级纪律

- 版本配对：C# 12↔.NET 8（LTS）、C# 13↔.NET 9（STS，2026-05 EOL）、C# 14↔.NET 10（LTS）；生产基线只上 LTS，STS 用于预迁移验证。
- LangVersion 与 TFM 解耦：旧 TFM 配 `<LangVersion>14</LangVersion>` 能编译，但**新语言特性 ≠ 新运行时**——集合表达式、扩展块都需要 runtime helper，跨 TFM 编译通过、运行时 MissingMethodException 是典型翻车。
- 升级顺序：先升 TFM + `dotnet nuget why` 锁传递依赖 → 消化 SYSLIB1XXX 警告（行为变更先兆）→ 最后才动语言版本；upgrade-assistant 只做机械部分。
- 陷阱：把 LangVersion 钉死 `latest` 写进 Directory.Build.props 再多目标编译——旧运行时缺 helper 直接运行时崩；语言版本跟随主 TFM。
- 陷阱：.NET 10 file-based apps（`#:package`、`dotnet run app.cs`）是脚本场景专用，无 csproj = 无 CI 静态检查，别渗进正式工程。
- 双 LTS 并存期（.NET 8 与 10 同时在支持窗口）给第三方包留一个版本回退位：新特性用 Feature Flag 隔离，出问题按包降级而不是回滚整个运行时。
- 判断「能不能升」的硬指标：直接依赖里有无反射重灾库（AutoMapper/MediatR/动态序列化）——它们决定 AOT/裁剪与源生成迁移的工作量（11、103 节）。

```xml
<TargetFrameworks>net8.0;net10.0</TargetFrameworks>
<TreatWarningsAsErrors>true</TreatWarningsAsErrors>  <!-- syslib 警告别被淹没 -->
```

## 2. C# 13 精要：params 集合与泛型反约束 allow ref struct

- params 从"仅数组"放宽到任意集合：`params ReadOnlySpan<T>` 让调用点零分配（栈缓冲直传）——公共 API 变长参数的新默认。
- 新旧共存：保留旧 `params T[]` 重载并用 `[OverloadResolutionPriority(1)]` 把 span 版本顶到优先位——二进制兼容（旧编译绑定不破）+ 渐进提速。
- 反约束 `where T : allows ref struct`：Span/ref struct 终于能进泛型，配合 scoped 纪律（29 节）打开"零拷贝工具链"复用面。

```csharp
static int Sum(params ReadOnlySpan<int> values)
{ int t = 0; foreach (var v in values) t += v; return t; }
Sum(1, 2, 3);                    // 栈缓冲,无 new int[]

static void Handle<T>(T t) where T : allows ref struct => _ = t;
Handle(stackalloc int[8]);       // C# 13 前:ref struct 进不了泛型
```

- 陷阱：`allows ref struct` 只放宽"传进来"——T 仍不能装箱、不能进字段；泛型库内部要按最保守逃逸规则写。
- 陷阱：params span 重载的性能收益要等调用方**重新编译**才兑现，旧程序集仍走数组版。

## 3. C# 14 扩展成员与扩展块（extension everything）

- 扩展块把同一接收者的全部扩展聚进一个单元，成员从方法扩展到**属性、索引器、静态成员**；基元、数组、元组都能当接收者，旧 static class 扩展方法继续有效。
- 没有**扩展字段**：扩展属性只能由接收者现有状态推导或查静态表——"给类型挂状态"是设计错误，要用包装类型。
- 静态扩展成员按 `Type.Member` 形态调用——工厂/解析类 helper 不必再建伴生类。

```csharp
public static class OrderExt
{
    extension(Order order)              // C# 14 扩展块
    {
        public bool IsPaid => order.Status is PaidStatus;   // 扩展属性
        public decimal Net => order.Total - order.Discount; // 无状态推导
        static Order Parse(string s) => OrderJson.Parse(s); // 静态扩展成员
    }
}
```

- 陷阱：扩展成员与实例成员冲突时实例成员永远赢；两个扩展块同签名 = 歧义编译错误，用更具体的接收者/命名空间消解。
- 陷阱：C# 14 没有扩展运算符与扩展构造器——评审时拒绝"幻觉 API"代码。
- 陷阱：序列化/数据绑定框架靠反射发现属性，扩展属性行为与真实属性不同——框架层先验证再上。

## 4. C# 14 半自动属性：field 关键字实战

- `field` 关键字指代编译器合成的后备字段：校验式属性从"显式字段 + 完整属性"压缩成两行，后备字段名不再污染 this 公开面。
- 构造函数里给属性赋值会走 setter（校验生效）；`field` 只在同类访问器内可见，构造函数里写 `field = x` 不合法。
- 与 `init` 访问器组合成"校验 + 只读"——C# 14 前这个组合只能靠显式字段绕。

```csharp
public class Customer
{
    public string Name
    {
        get => field;                          // 直读后备字段
        set => field = value.Trim() ?? "";     // 写前校验,无显式字段
    }
}
```

- 陷阱：`field` 合成的后备字段名与 auto-property 的 `<Name>k__BackingField` 不同——反射摸后备字段的代码（序列化器/ORM/绑定）升级即碎，先全库搜 `k__BackingField`。
- 陷阱：类里已有名为 `field` 的标识符时语义冲突（上下文关键字）——重名代码先改字段名再升级。

## 5. C# 14 空条件赋值与复合赋值放宽

- `a?.b.c = value`、`a?[i] += delta` 合法化：接收者链只求值一次、非空才写——少一层判空，也消灭"检查后再访问"的 TOCTOU 空隙。
- 复合赋值放宽：`x op= y` 允许走"用户定义转换 + 用户定义运算符"组合，包装数值类型（Money/Quantity）终于能自然 `+=`。

```csharp
cust?.Address.City = "Beijing";      // cust 为 null:整条链静默跳过
map?[key] ??= defaultValue;          // 空条件 + null 合并赋值
total += rawAmount;                  // Money += decimal:走用户定义转换
```

- 陷阱：链**中段**为 null 时整条赋值被静默跳过——无日志无异常的关键写操作禁止空条件链，先显式判空并记录。
- 陷阱：`??=` 非原子——多线程懒初始化仍用 Lazy<T>/LazyInitializer，赋值运算符不是同步原语（90 节）。
- 空条件链求值顺序在重载运算符/属性副作用下更难追踪——链上出现非纯成员时整段改写为显式判空。

## 6. C# 14 一等 Span 转换与 nameof 非绑定泛型

- 此前用户定义转换的目标不能是 ref struct（CS0553 家族），C# 14 放开：包装类型可**隐式转换**到 ReadOnlySpan<char>/Span<T>——自研"字符串视图"不必再靠 AsSpan() 方法名。
- `nameof(List<>)`、`nameof(Dictionary<,>)` 合法：不指定实参取类型名，泛型工具类的错误消息不再手写字符串。

```csharp
public readonly struct PathText(ReadOnlySpan<char> value)
{
    public static implicit operator ReadOnlySpan<char>(PathText p) => p.value;
}
// 一等 Span 转换后:所有收 ReadOnlySpan<char> 的 API 直接吃 PathText
```

- 陷阱：隐式转换产物仍是 ref struct——逃逸规则照旧；评审盯"转换结果存字段/进闭包"的编译错误是不是真问题。
- 陷阱：`nameof` 非绑定泛型只产出名字文本，不做类型实参校验——别用它替代 typeof 的编译期检查。

## 7. .NET 9 运行时红利：GDV 深化与集合提速

- Dynamic PGO（.NET 8 默认开）在 .NET 9 扩大守护去虚化（GDV）覆盖面：接口/虚调用按 profile 插类型守卫直调——"面向接口"的热点税持续下降，sealed 层级让守卫更准（38 节）。
- 库侧增量：FrozenDictionary/FrozenSet 构建成本大幅下降（.NET 8 最大短板被补）、`AlternateLookup` 让字典按 ReadOnlySpan<char> 键查询零 string 分配（34 节）。
- 经验值：从 .NET 8 升 .NET 9/10 常见白拿 5-15% 吞吐 + 更低分配——**先升级再优化**，别对着旧运行时精调。

```csharp
var alt = dict.GetAlternateLookup<ReadOnlySpan<char>>();  // .NET 9
if (alt.TryGetValue(headerName.AsSpan(), out string? v)) { } // 无键分配
```

- 陷阱：GDV 依赖进程前几分钟的 profile——冷启动路径（网关/FaaS/CLI 单次跑）吃不到红利，用 ReadyToRun 或 AOT 单独评估（9、38 节）。
- 陷阱：大量一次性动态实现类（DI lambda 注册）会把类型 profile 打散、守卫命中率下降——注册面收敛到具体类型。
- `OverloadResolutionPriority` 同期可用：库作者调整重载优选级时要锁版本审计——调用方重新编译后行为切换是隐形破坏性变更。

## 8. .NET 10 基线：逃逸分析与栈分配对象

- .NET 10 的 JIT 基于**逃逸分析**把"证明不逃逸"的堆对象分配到栈上（配合 PGO 守卫克隆）：枚举器、临时包装对象堆分配消失——受益的不只是速度，是 Gen0 压力整体下降。
- 方法克隆：GDV 按实参类型/常量值克隆热方法并常量折叠——switch/枚举密集的分派代码受益最大。
- `Task.WhenEach` 替代 WhenAny 轮询（22 节）；file-based apps 服务脚本场景（1 节）。

```csharp
// WhenEach(.NET 10):按完成序逐个产出,一次建全量延续 O(n)
await foreach (var done in Task.WhenEach(tasks))
    Handle(done);
```

- 陷阱：栈分配的前提是"JIT 能证明不逃逸"——对象存字段、上抛、塞闭包都是在没收证据；生命周期局部化是 .NET 10 时代的新写法纪律。
- 陷阱：逃逸分析红利在 Debug/禁 PGO 下不存在——基准必须 Release + 默认运行时开关下跑（41 节）。

## 9. 发布形态决策矩阵：FDD、SCD 与 NativeAOT

- 三形态关键差：FDD 体积最小但吃目标机运行时（版本漂移风险）；SCD 自带运行时（~60-90MB，修 CVE 要自己滚动发版）；NativeAOT 单文件、启动 <50ms、内存最小，代价是砍掉反射/Emit/运行时加载。
- 决策树：CLI 工具/Serverless/边缘 → NativeAOT；容器长驻服务 → FDD（镜像分层复用基础镜像）；插件宿主 → 只能 FDD/SCD——AOT 不支持运行时加载程序集（106 节）。
- InvariantGlobalization 与 StackTraceSupport 是 AOT 体积两大开关：关 ICU 省 ~20MB+，关栈符号省 ~1MB——按目标平台实测再定。

```xml
<PropertyGroup>
  <PublishAot>true</PublishAot>
  <InvariantGlobalization>true</InvariantGlobalization>
  <StackTraceSupport>false</StackTraceSupport>
</PropertyGroup>
```

- 陷阱：`ReadyToRun` 不是 AOT——只预编译，运行时与完整反射全保留；把"R2R 过了"当"AOT 兼容验证"会漏掉全部裁剪问题。
- 陷阱：压缩单文件在冷容器里解压开销可能超过启动收益——启动敏感场景实测 publishtrimmed 单文件与压缩版本。

## 10. Trimming 治理：IL2xxx 警告消化流程

- 看懂编号：IL2026（成员被 [RequiresUnreferencedCode] 标注）、IL2067/IL2070（Type/Member 由不可见来源获得）、IL3050（AOT 不兼容调用）——每个警告都是"运行时可能找不到成员"的静态预测，不是风格问题。
- 消化顺序：`PublishTrimmed=true` 拿全量警告 → 逐条用 [DynamicallyAccessedMembers]/[DynamicDependency] 修根 → 改写代码（最优先）→ 最后才 TrimmerRootDescriptor 兜底（每条都是体积与维护税）。

```csharp
static T Create<[DynamicallyAccessedMembers(DynamicallyAccessedMemberTypes.PublicParameterlessConstructor)] T>()
    => Activator.CreateInstance<T>();   // 让分析器看到构造器根
```

- 陷阱：反射走字符串（`Type.GetType("Ns.T, Asm")`）分析器**看穿不了**——每个字符串反射路径配一条 AOT 冒烟用例，否则裁剪后才炸。
- 陷阱：NoWarn 关警告 = 把故障挪到生产；允许的只有"确认永不执行的分支"且有注释锚定。
- 陷阱：链接器版本间行为有差异（feature switch、Descriptor 格式）——CI 锁 SDK 版本，升级后重跑裁剪冒烟。
- 根覆盖面工具：`illinkanalyzer`/`dotnet-pgo` 不如直接对比 publish 目录符号——警告清零后仍要抽查“被裁掉的预期类型”清单，双向验证。

## 11. 反射的 AOT 代价与替代路径

- 必死清单：按字符串找类型、MakeGenericMethod（未特化）、运行时 Emit、Expression.Compile、dynamic/DLR——AOT 下抛或退化为解释器（105 节）；Attribute 反射与 typeof+nameof 形态大多可保活。
- 替代排序：源生成器（编译期固化，正解）> 显式注册表（手写 switch/字典）> [DynamicDependency] 保活（最后手段）。
- 重灾区库逐个确认 AOT 路线：AutoMapper/MediatR 已商业化且推源生成替代、Dapper 有 CommandTextCreateHandler、STJ 走 JsonSerializerContext——升级计划里把它们列成迁移工单而不是"应该兼容"。

```csharp
// 注册表替代字符串反射:多态基类 + 鉴别器
[JsonDerivedType(typeof(Circle), "circle")]
[JsonDerivedType(typeof(Square), "square")]
public abstract record Shape;
```

- 陷阱：try/catch 反射兜底在 AOT 下不是"降级"而是 ExecutionEngineException/MissingMethodException——兜底必须在编译期消灭，不是运行期 catch。
- 陷阱：测试程序集（反射友好）全绿 ≠ AOT 可用；`PublishAot` + 跑产物二进制必须是独立 CI 步骤（101 节）。

## 12. 多目标编译与运行时能力探测

- 能力探测用 `RuntimeFeature.IsSupported`、`OperatingSystem.IsOSPlatformVersionAtLeast`——按"能力"分流比 `#if NET10_0_OR_GREATER` 更接近本质，测试矩阵不爆炸。
- `#if` 只留给真编译差异（API 形状不同/类型不存在）；一段逻辑被两套 #if 各写一遍时，先问是不是该拆程序集或用扩展方法适配层。

```csharp
if (RuntimeFeature.IsDynamicCodeSupported)      // AOT 下为 false
    UseDynamicPath();
else
    UseSourceGeneratedPath();                   // 两套都编译,运行期分流
```

- 陷阱：`#if NET8_0_OR_GREATER` 对 net472 目标恒 false——老目标的 bug 只在特定矩阵暴露；TFM 越多越要把平台差异挡在入口适配层。
- 陷阱：CA1416 平台警告用 [SupportedOSPlatform] 标注化解而不是删除——给守卫方法写 Debug.Assert，分析器学习你的守卫后误报消失。

## 13. async/await 状态机编译产物精读

- 编译器把 async 方法拆成：存根方法（建状态机 + builder 启动）+ IAsyncStateMachine 实现——局部变量全部提升为字段，控制流变成 state 字段驱动的 switch。
- await 的本质：`GetAwaiter().IsCompleted == false` 时注册 MoveNext 回调并 return；同步完成路径**零分配**（builder 缓存 Task.CompletedTask）——"async 贵"贵在挂起后的状态机装箱，不是每次调用。
- 真实调用点藏在状态机字段链里：普通 stacktrace 只看到 MoveNext，排查要用 async-aware 工具（VS 并行任务窗口/dotnet-stack）。

```csharp
// await 的编译等价物(简化):
var awaiter = task.GetAwaiter();
if (!awaiter.IsCompleted)
{ state = 1; builder.AwaitUnsafeOnCompleted(ref awaiter, ref this); return; }
Continue(awaiter.GetResult());   // 同步完成:内联继续,不排队
```

- 陷阱：第一个 await 之前的代码**同步执行**——把昂贵 CPU/阻塞调用塞在 await 前，等于对调用方撒谎"立即让出"；用 Task.Yield 显式交还或把重活挪进 Task.Run。
- 陷阱：async 方法的参数会被提升为状态机字段——传大 struct 进 async 方法 = 挂起时装箱一份；async 签名的 struct 参数要小（36 节）。

## 14. SynchronizationContext 与 ConfigureAwait 语义真相

- await 捕获的是 SynchronizationContext **或** TaskScheduler（无 context 时）；ASP.NET Core/控制台没有 SyncContext，但 UI 线程（WPF/WinUI/MAUI）、自定义 TaskScheduler、部分测试框架仍捕获。
- `ConfigureAwait(false)` 真实含义：延续不回原上下文、直连线程池——收益是"不占原线程 + 消除死锁面"，不是魔法提速；.NET 8+ 的 `ConfigureAwaitOptions.None`（等价 false）与 ForceYielding 表达"立即让出"。

```csharp
// 库代码标准形:每个 await 点都要带
public async Task<byte[]> ReadAsync(string path, CancellationToken ct)
{
    await using var fs = new FileStream(path, FileMode.Open, FileAccess.Read,
        FileShare.Read, 64 * 1024, useAsync: true);
    var buf = GC.AllocateUninitializedArray<byte>((int)fs.Length);
    await fs.ReadAsync(buf, ct).ConfigureAwait(false);
    return buf;
}
```

- 陷阱：ConfigureAwait(false) 只影响**该 await 点**，不是整个方法——漏一个点照样回 UI 线程死锁；用 CA2007 分析器强制全库一致。
- 陷阱：AsyncLocal/ExecutionContext 的流动**不受** ConfigureAwait 影响（94 节）——"丢了 HttpContext/租户上下文"先查中间件与同步上下文，别错怪 ConfigureAwait。

## 15. sync-over-async 死锁形态与根治

- 经典死锁三件套：UI 线程 `.Result` + 延续依赖 UI 线程 = 互等；ASP.NET（旧 Framework）SyncContext 同构；自定义单线程上下文（游戏循环、Actor 框架）重演。
- .NET Core ASP.NET 无 SyncContext——`.Result` 不再必然死锁，改为**阻塞线程池线程**：并发一高演变成饥饿型假死（87 节），症状是延迟雪崩而非挂死，更难归因。

```csharp
var user = GetUserAsync(id).Result;    // 反模式:阻塞 + 潜在死锁 + 包装异常
// 根治顺序:1) 调用链全异步化  2) 顶层入口唯一允许 .GetAwaiter().GetResult()
//          3) Task.Run 包裹只解死锁不解饥饿,是过渡态不是方案
```

- 必须同步取结果的边界（Main/入口）：`task.GetAwaiter().GetResult()`——抛原始异常不包 AggregateException。
- 陷阱：带超时 `Wait(ms)` 返回 false 后任务**仍在跑**——超时不是取消，要配 CancellationToken 才有真停（18 节）。
- 检测：CI 挂分析器禁 `.Result`/.Wait()（VSTHRD002/CA1849 族），白名单只有 Program.cs 与适配层。

## 16. ValueTask 纪律：单次消费与池化内幕

- ValueTask<T> 两种形态：包装已完成的 Task/同步结果（零分配），或包装池化的 IValueTaskSource——**池化形态下二次 await/二次 GetResult 抛 InvalidOperationException 或读到别人的结果**，不是理论风险。
- 官方消费规则（Toub）：①await 一次 ②IsCompletedSuccessfully 后 GetResult() 一次 ③AsTask() 一次——之后本体作废，禁止再碰。
- 方法标注 `[AsyncMethodBuilder(typeof(PoolingAsyncValueTaskMethodBuilder<>))]` 让编译器池化状态机——库级热路径可开，业务代码默认不开（ValueTask 的 bug 比 Task 分配贵百倍）。

```csharp
ValueTask<int> vt = socket.ReceiveAsync(buffer, ct);
int n = await vt;                  // 唯一一次 await
// 需要多消费/存字段/传给他人:先 AsTask() 再用返回的 Task
Task<int> t = vt.AsTask();         // AsTask 也只能调一次
```

- 陷阱：把 ValueTask 存字段/塞 List 再统一 await 是重灾区——Task 的合法玩法、ValueTask 的非法玩法。
- 陷阱：`vt.Preserve()` 可解多消费但退化为包装 Task，默认别用——等于自废 ValueTask；真正多播需求用 Task.WhenAll。
- 陷阱：async 迭代器/IAsyncEnumerable 里返回 ValueTask 的 API 配 `await foreach` 时每个元素独立实例——规则仍按单次算，别被流式表象麻痹。

## 17. Task.WhenAll 的异常聚合与结果收割

- WhenAll 的 faulted 状态**只抛第一个异常**，其余全在 `task.Exception.InnerExceptions`——只 catch 顶层异常的日志会系统性丢故障，是 WhenAll 最常见事故。
- WhenAll 从不取消兄弟任务：一个失败其余照跑——要"一败全停"必须显式传 CancellationToken 且实现内部尊重取消（18 节）。

```csharp
var all = Task.WhenAll(jobs);
try { await all; }
catch (Exception first)
{
    var rest = all.Exception?.InnerExceptions ?? [];
    Log.Error(first, "plus {Count} suppressed", rest.Count - 1);
    foreach (var oce in rest.OfType<OperationCanceledException>())
        Log.Debug("cancelled sibling");      // 取消类按取消分支分流
}
```

- 结果收割：`await Task.WhenAll(ts)`（Task<T>[]）直接产出 T[]；分批收割用 WhenEach（.NET 10）或 Channel（24 节），避免 WhenAny 轮询（22 节）。
- 陷阱：`Task.WhenAll(...).Result` 在同步代码里双重错——死锁面 + AggregateException 再包一层；封装统一的收割扩展方法进工具库。
- 陷阱：异常聚合里的目标是**故障归因**不是重试——重试策略在单个 job 内部闭环，别在聚合层再做一层重试放大（108 节反模式）。

## 18. CancellationToken 生命周期与传播纪律

- 取消三要素：CancellationTokenSource（控制端）、Token（查询端）、OperationCanceledException（信号载体）；ThrowIfCancellationRequested 抛的 OCE **必须带该 token**，Task 才进 Canceled 而非 Faulted——Canceled 不算失败、不触发 Unobserved 语义，这是取消与异常的分界。
- 传播纪律：每个 async API 带 `CancellationToken ct = default` 且第一行就接入（HTTP/EF/Stream/Channel 原生支持）；不接 token 的旧库用 `.WaitAsync(token)`（.NET 6+）外包。

```csharp
using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(5));
using var linked = CancellationTokenSource.CreateLinkedTokenSource(ct, timeout.Token);
// linked/timeout 两个源都要 Dispose:定时器与回调槽才释放
try { await callAsync(linked.Token); }
catch (OperationCanceledException) when (timeout.IsCancellationRequested) { /* 超时分支 */ }
```

- 陷阱：token.Register 返回的 CancellationTokenRegistration 必须 Dispose；回调闭包持有大对象 = CTS 字典只增不减的泄漏形态（47 节取证）。
- 陷阱：CancelAfter 启动内部定时器，CTS.Dispose 才停——高频请求里忘 Dispose 会看到 Timer 数量线性上涨。
- 陷阱：吞 OCE 转成"成功空结果"是取消语义破坏——让 OCE 冒泡；清理逻辑放 catch 后的 finally 或专门的取消分支。

## 19. TaskCompletionSource 与延续内联事故

- SetResult 在调用线程**同步内联执行全部延续**：在定时器/IO 回调线程里 SetResult = 这条线程瞬间变成别人的代码——重入、锁内调用、回调线程被重活占住（定时器饥饿）三连。
- 库代码纪律：`new TaskCompletionSource<T>(TaskCreationOptions.RunContinuationsAsynchronously)` 强制延续排队，切断内联重入面；代价一次调度，极便宜。

```csharp
var tcs = new TaskCompletionSource<int>(TaskCreationOptions.RunContinuationsAsynchronously);
timer.Elapsed += (_, _) => tcs.TrySetResult(42);  // TrySet*:竞态下静默失败
var v = await tcs.Task;
```

- TrySetResult/TrySetException/TrySetCanceled 已完结后返回 false——"设值失败"不是错误，但**丢弃返回值**会让你查不出双写竞态。
- 用法边界：做"信号/一次性完成事件"用 TCS；做生产者-消费者直接上 Channel（24 节）——手写 TCS 队列（List<TCS> + 锁）是十年前老工艺，完成/取消/异常语义几乎必漏。
- 陷阱：测试里同步 SetResult 后立刻断言延续效果——RunContinuationsAsynchronously 把延续排到线程池，断言时序随机挂；测试用 AsyncTestSyncContext 或轮询等待。

## 20. 异步互斥：SemaphoreSlim 与 System.Threading.Lock

- `lock`/Monitor 是线程亲和且**编译器直接拒绝 lock 块内 await**——跨 await 持锁的正解只有 SemaphoreSlim（WaitAsync/Release）或 Channel 串行化。
- .NET 9 的 System.Threading.Lock：`lock (lk)` 编译成 EnterScope + Dispose，仍是线程亲和、仍不可跨 await——它替换 Monitor 而非 SemaphoreSlim；锁段里有 await 一律 SemaphoreSlim。

```csharp
private readonly SemaphoreSlim _gate = new(1, 1);
public async Task<T> ExclusiveAsync<T>(Func<Task<T>> work, CancellationToken ct)
{
    await _gate.WaitAsync(ct);              // 拿到才进 try
    try { return await work(ct); }
    finally { _gate.Release(); }
}
```

- 陷阱：WaitAsync 超时/取消返回 false 时**没拿到锁**——Release 必须以"WaitAsync 成功"为边界，try/finally 切在成功之后。
- 陷阱：跨 await 持锁放大串行化（IO 等待也在锁内）——先确认锁段里是否真有 await；纯同步短临界区回 Monitor/Lock，吞吐差一个量级。
- 陷阱：`SemaphoreSlim.AvailableWaitHandle` 是同步等待原语——异步路径出现它就是 sync-over-async 复发（15 节）。

## 21. 线程池真相与 LongRunning 的滥用

- 池是进程级共享 + 爬山算法动态调节；饥饿时注入新线程的速率量级是**每秒个位数**——队列被阻塞工作项塞满后，雪崩几十秒内成形（87、109 节复盘）。
- `TaskCreationOptions.LongRunning` = 为该任务开**专用线程**：它救不了饥饿（多线程多内存），合法场景只有长驻消息泵/专用事件循环。
- 金指标：dotnet-counters 的 `ThreadPool Queue Length` 持续 >0 且攀升 = 有东西在阻塞吃线程；吞吐同时下跌即可定性。

```csharp
// 饥饿自测样本(反模式,勿用于生产):200 个同步阻塞项的崩塌与恢复
for (int i = 0; i < 200; i++)
    _ = Task.Run(() => Thread.Sleep(1000));   // Task.Run 只是换地方阻塞
```

- 陷阱：Task.Run 包同步库调用 = "换个地方阻塞"，修复必须从换真异步 API 入手；`ThreadPool.SetMinThreads` 是止血带，不是设计。
- 陷阱：ASP.NET Core 请求路径 Task.Run 引入额外排队且 ExecutionContext 语义照旧流动——只在卸载 CPU 密集工作时用，别当"异步化"捷径。

## 22. Task.WhenAny 的 O(n²) 陷阱与 WhenEach

- N 个任务逐轮 WhenAny：每轮给所有未完成任务挂延续再摘头——总分配/调度成本 O(n²)；几千任务时 CPU 与 GC 双杀，"看起来正确"的扇出代码其实是慢动作事故。
- .NET 10 正解：Task.WhenEach 按完成序产出，一次建全量延续 O(n)；低版本用 Channel 手写完成网关（24 节）。

```csharp
// 旧 O(n²) 模式(新代码里拒绝出现):
List<Task> pending = [.. tasks];
while (pending.Count > 0)
{
    Task done = await Task.WhenAny(pending);   // 每轮挂 N 个延续
    pending.Remove(done);
}
// .NET 10: await foreach (var t in Task.WhenEach(tasks)) Handle(t);
```

- 陷阱：WhenAny 胜出后其余任务无人观察——异常进 UnobservedTaskException（86 节）+ 资源继续跑；WhenEach 天然全收割。
- 陷阱：WhenAny(task, Task.Delay(ms)) 的超时老模式被 `task.WaitAsync(timeout, ct)`（.NET 6+）替代——超时返回的是新任务视图，原任务继续跑，语义更准。

## 23. IAsyncEnumerable 深水：EnumeratorCancellation 与消费纪律

- token 有两个入口：GetAsyncEnumerator(token) 与 WithCancellation(token)——只有给枚举器参数标 `[EnumeratorCancellation]` 才会**自动合并**两个 token，任一取消都停。
- 生成器在 yield return 处挂起；取消时生成器的 try/finally 也要走完——依赖它释放连接/游标，所以消费者必须正确释放枚举器。

```csharp
public async IAsyncEnumerable<Item> PollAsync(
    [EnumeratorCancellation] CancellationToken ct = default)
{
    using var timer = new PeriodicTimer(TimeSpan.FromSeconds(1));
    while (await timer.WaitForNextTickAsync(ct))
        yield return await FetchOneAsync(ct);   // WithCancellation 的 token 也并入
}
```

- 消费纪律：`await foreach (var x in src.WithCancellation(token).ConfigureAwait(false))`——两个都别漏；不 ConfigureAwait 的异步流在 UI 上下文里每个元素回一次 UI 线程。
- 陷阱：消费方提前 break——`await foreach` 语法自动 DisposeAsync 枚举器，手动 MoveNextAsync 循环漏释放则生成器 finally 永不执行（连接泄漏）。
- 陷阱：生成器方法内 try/finally 包 yield 是合法的，但**不能** catch 后继续 yield（迭代器不允许 yield in catch）——错误处理用 yield break + 外层包装。

## 24. System.Threading.Channels：生产者消费者基础设施

- Channel 是无锁队列 + 异步背压的标准件；手写 ConcurrentQueue + SemaphoreSlim 组合在完成/取消/多生产者语义上几乎必漏——默认选 Channel。
- 有界优先：BoundedChannel + FullMode.Wait 形成真背压（写方异步挂起）；DropOldest 用于"只保最新"（行情/指标）；Unbounded 无背压、内存无上限——生产慎用。

```csharp
var ch = Channel.CreateBounded<Job>(new BoundedChannelOptions(256)
{ SingleReader = true, SingleWriter = false, FullMode = BoundedChannelFullMode.Wait });
await ch.Writer.WriteAsync(job, token);   // 满则挂起:天然背压
```

- 完成语义：Writer.TryComplete(err) 后**已入队数据仍可读**，Reader.Completion 在排空后才完成——"Complete 后立即等 Completion"是等全部积压消费完，这正是优雅停机的关键语义。
- 陷阱：SingleReader/SingleWriter 是**承诺不是检查**——违规是未定义行为；多读者显式 false。
- 陷阱：Complete(ex) 的异常只出现在 Reader.Completion，TryRead 端看不到——消费侧错误处理挂在 Completion 上，别等 ReadAsync 抛。

## 25. IAsyncDisposable 与 await using 处置纪律

- await using 的本质：DisposeAsync 完成后才继续；同步 using 遇 IAsyncDisposable-only 类型直接漏处置（编译器不自动降级）——Stream/HttpResponse 等 .NET 8+ 大量类型双接口实现，漏异步 = 缓冲未 flush。
- 声明纪律：持有异步资源的类型实现 IAsyncDisposable，同时保留 IDisposable（同步消费者兜底），双路径幂等防双 dispose。

```csharp
public sealed class Conn : IAsyncDisposable, IDisposable
{
    int _disposed;
    public async ValueTask DisposeAsync()
    { if (Interlocked.Exchange(ref _disposed, 1) == 0) await CoreAsync(); }
    public void Dispose()
    { if (Interlocked.Exchange(ref _disposed, 1) == 0) CoreSync(); }
}
```

- 陷阱：async 方法体内的同步 `using` 持异步资源 = 静默走同步路径；评审规则：async 函数体内只允许 await using。
- 陷阱：fire-and-forget `DisposeAsync()`（不 await）与后续代码竞态（连接没关就开新的）；DI 容器的异步处置只在 Host 停机链生效（74 节）。
- 陷阱：DisposeAsync 里抛异常会覆盖先抛的业务异常（后执行者赢）——处置代码不抛，错误降级为日志。

## 26. TimeProvider、PeriodicTimer 与确定性时间基建

- 生产代码**禁止**直读 DateTime.UtcNow / Environment.TickCount / Task.Delay——统一注入 TimeProvider（默认 TimeProvider.System 零开销直通）；测试用 FakeTimeProvider 推虚拟时间，等待类测试从分钟级变毫秒级。
- PeriodicTimer 取代 System.Threading.Timer 做"单消费者轮询"：WaitForNextTickAsync 天然串行、**回调不可重入**、周期按起点对齐漂移小；Threading.Timer 的回调重入是经典事故源。

```csharp
public class Scheduler(TimeProvider tp)
{
    public async Task LoopAsync(CancellationToken ct)
    {
        using var timer = new PeriodicTimer(TimeSpan.FromMinutes(1), tp);
        while (await timer.WaitForNextTickAsync(ct)) await TickAsync(ct);
    }
}
```

- 陷阱：FakeTimeProvider.Advance 同步执行到期回调——大步推进可能递归爆栈，测试里小步走。
- 陷阱：PeriodicTimer 单实例只允许一个等待者；要广播就配 Channel/事件，别开多份 timer 各自漂移。
- 陷阱：`DateTime.Now`（本地时区+更慢）进生产代码一律打回——内部一律 UTC，出边界才转显示时区。
- 测时长用 TimeProvider.GetTimestamp()（单调钟）而不是 UTC 相减——NTP 校时会让"时长"倒流；超时用相对 ms，日志用绝对 UTC，两类时间别混算。

## 27. 异步反模式速查与评审清单

- 速查（见到即拒）：async void（107 节）、.Result/.Wait（15 节）、Task.Run 包同步库（21 节）、丢 await（CS4014 即 bug）、一元 async/await、不传 CancellationToken、unbounded 队列、fire-and-forget 无日志无兜底。

```csharp
// 一元 async:多一层状态机,零收益
public async Task<Foo> GetAsync() => await _cache.GetAsync(key);
// 等价且更便宜:直接返回 Task,让调用方决定是否 await
public Task<Foo> GetAsync() => _cache.GetAsync(key);
// 但 if/try/using 包裹时别手工拆——正确性优先于微优化
```

- 评审口诀——每个 async 方法问三句：取消怎么进来？挂起后上下文还回得去吗？异常谁观察？
- 陷阱：手工"去一元 async"改返回 Task.FromResult 时引入共享 Task——Task 完成后不可复用场景（带异常缓存语义）要小心；默认保持 async 让编译器兜，Profile 之后再动。
- 陷阱：`async` lambda 与非 async lambda 重载决议（如 Where vs WhereAsync 风格 API）——传错重载把异步当同步执行，编译器不总报警。
- fire-and-forget 不是禁止而是要包装：SafeFireAndForget 统一接异常→日志→计数（107 节），裸 `_ = DoAsync()` 是评审红灯。

## 28. Span<T> 生存期规则与逃逸分析

- Span<T> 是 ref struct：两个字段（ref + 长度，64 位 16 字节）永不进堆；一切限制源于一条公理——**它指向的内存不能比它本身活得久**。
- 编译器按逃逸分析执行：不能做类字段、不能装箱、不能捕获进 lambda/匿名类型、不能跨 await 存活（状态机字段化 = 泄漏窗口）。

```csharp
// 好:全在栈窗口内消费
static int Sum(ReadOnlySpan<int> s) { int t = 0; foreach (var v in s) t += v; return t; }

// 坏:跨 await 持有 Span → 编译错误 CS8175 家族
static async Task<long> BadAsync(byte[] a)
{
    var span = a.AsSpan();     // ref struct 捕获进状态机:拒绝编译
    await Task.Yield();
    return span[0];            // 数组可能已被 GC 搬移/归还
}
```

- 陷阱：GC 搬移是设计根源——Span 指向的对象在压缩时移动，堆上的 Span 字段无法同步更新，所以"进堆"被语法级封死；用 Memory<T> 表达"可驻堆"（31 节）。
- 陷阱：第三方 API 返回 Span 时的"隐式生存期契约"：默认可逃逸到调用方栈；如果它内部指向池化 buffer，文档不写清就是排障地狱（32 节）。
- string 天然是 ReadOnlySpan<char> 的宿主：AsSpan()/切片零拷贝——协议解析先用 span 定位，最后一步只对需要片段 ToString。

## 29. ref struct 纪律：scoped 与 UnscopedRef

- ref struct 形参默认"可逃逸出调用"——`scoped` 参数显式收紧（绝不逃逸），编译器据此放行更多组合（如把形参折叠进另一个 ref struct 字段）。
- [UnscopedRef] 反向放宽"方法内 ref 的逃逸窗口"——业务代码出现它 = 你在向编译器承诺它无法验证的生存期，每处都要评审锚点注释。

```csharp
static int Sum(scoped ReadOnlySpan<int> s)   // 声明:不逃逸出本调用
{ int t = 0; foreach (var v in s) t += v; return t; }

// ref struct 包装类型:字段必须是 ref struct 允许的形态
readonly ref struct Line(ReadOnlySpan<char> text)
{
    public bool IsEmpty => text.IsEmpty;     // C# 13:readonly ref struct 字段合法
}
```

- 陷阱：想返回形参的切片（如 ref 结构上的 Substring）时必须**去掉 scoped**——逃逸窗口扩大后重新人工论证底层内存生存期。
- 陷阱：lambda/局部函数捕获 ref struct 仍禁止——用"传入消费委托"（Action<ReadOnlySpan<T>>）代替捕获，把消费留在栈窗口内。
- C# 13 起 `allows ref struct` 泛型反约束（2 节）+ 逃逸规则改进，ref struct 工具链（包装类型、解析器）终于可复用——但 API 面公开 ref struct 仍要三思（调用方被传染限制）。
- C# 13 起 ref struct 可实现接口（配合 allows ref struct 约束消费）——但接口引用不能装箱持有，"实现"只为进泛型算法，别当成多态用。

## 30. stackalloc 安全窗口与栈预算

- stackalloc 分配在当前栈帧，帧退出即失效——只能"当表达式在当前作用域消费"；禁止赋给字段、跨方法保存、跨 await。
- 预算经验值：单帧 stackalloc 控制在 ~1KB 量级；更大走 ArrayPool（32 节）。主线程栈默认 1MB，线程池线程更小——深递归 + stackalloc = StackOverflowError（进程死，**无法 catch**）。

```csharp
// 标准形:小帧栈分配,大缓冲走池
Span<byte> buf = len <= 128 ? stackalloc byte[128] : ArrayPool<byte>.Shared.Rent(len);
buf = buf[..len];                    // 用真实 len 裁剪(Rent 返回更长)
// 解析后立即消费完毕,buffer 出作用域
```

- 陷阱：`Span<T> s = stackalloc T[n]` 的 n 来自外部输入时先乘 sizeof(T) 评估——超预算输入直接进池路径，别写死阈值在两处。
- 陷阱：嵌套 async/迭代器里出现 stackalloc 直接编译错误——别想着用 unsafe 绕；把窗口拆成同步小函数。
- 崩溃现场特征：栈顶在深层、无法捕获、dump 显示 stack limit hit——先查 stackalloc/递归，再查线程栈大小配置。
- StackOverflowError 不可 catch——宿主环境（IIS/容器）线程栈可能更小，开发机正常的深递归在 Prod 直翻车；已知深路径设深度护栏。

## 31. Memory<T> 与 IMemoryOwner 租约模型

- Memory<T> 是"可驻堆的 Span 句柄"（object + index + length，24 字节）：能进字段、跨 await、进状态机——代价是与底层内存的同步由 GC 保证（数组搬移时句柄跟着更新），不能像 Span 零成本。
- IMemoryOwner<T> = 所有权显式化：谁 Dispose 谁归还（池/本地内存）；对外返回"带租期的内存"优先返回 IMemoryOwner 而不是裸 Memory——调用方用完必须交还，契约在类型上。

```csharp
using IMemoryOwner<byte> owner = MemoryPool<byte>.Shared.Rent(4096);
Memory<byte> mem = owner.Memory[..4096];    // 记得裁真实长度
int n = await stream.ReadAsync(mem);         // 跨 await:Memory 合法
```

- 陷阱：Memory ≠ Span：每次取 `.Span` 都是一次安全检查后的视图；热点循环里取一次存局部变量，别在循环条件里反复 `.Span`。
- 陷阱：传给 native/P-Invoke 前必须 Pin——`mem.Pin()` 返回 MemoryHandle（fixed 块禁跨 await），MemoryManager<T> 自定义实现者违约时消费方毫无防御（49 节）。
- 陷阱：租约跨 await 持有时，池可能在别处被误归还——租约的生存期文档写清"最小持有原则"，归还点集中在 finally。
- 切片不切所有权：交出去的 Memory 切片仍占着整份底层租约——别把"部分视图"传成长期持有，下家一 Dispose 全体完蛋。

## 32. ArrayPool<T> 租借归还纪律

- Rent(n) 返回**长度 ≥ n** 的桶数组（按 2^n 分桶）——用返回数组的**前 n 个下标**；把 array.Length 当真实长度是第一大错（越界读旧数据、序列化多出脏尾部）。

```csharp
byte[] buf = ArrayPool<byte>.Shared.Rent(8 * 1024);
try
{
    int n = await stream.ReadAsync(buf.AsMemory(0, 8 * 1024));
    Parse(buf.AsSpan(0, n));
}
finally { ArrayPool<byte>.Shared.Return(buf); }  // 引用元素池要 clearArray:true
```

- 陷阱：双重 Return → 同一数组双租客同时写 = 数据竞争；use-after-return 是排障地狱——归还后立即把变量置 null，租借作用域最小化。
- 陷阱：归还含引用类型元素的数组必须 clearArray:true 或手动清——否则下家间接持有你的对象图（"幽灵泄漏"把对象压进 Gen2）。
- 陷阱：长租不放会饿死池（池按需新建，退化为无池）——租借时长要短且可预测；池水位进监控（40 节）。
- ArrayPool.Shared 是进程级——跨测试残留脏数据会让单测随机挂；要确定性的测试用 ArrayPool<T>.Create() 私池。

## 33. 字符串工程：String.Create 与内插处理器

- C# 10 内插进阶：`$"..."` 只有传给**插值处理器友好参数**（string.Create、StringBuilder.Append 已改造的日志 API）才零拼串——传给普通 string 参数仍是先 Format。
- 热点日志正解：MEL 6+ 的结构化重载在级别关闭时不拼接不装箱；固定模板用 CompositeFormat 缓存 + string.Create(format) 免每次解析。

```csharp
// 零分配路径(级别关闭时不格式化):
logger.LogDebug("user {UserId} did {Action}", userId, action);

// 热点拼接:CompositeFormat 解析一次
private static readonly CompositeFormat Fmt = CompositeFormat.Parse("total {0:N2} of {1}");
string s = string.Create(CultureInfo.InvariantCulture, Fmt, total, count);

// UTF-8 字面量:静态数据,零分配(网络协议/常量头)
ReadOnlySpan<byte> proto = "GET /health HTTP/1.1\r\n"u8;
```

- 陷阱：String.Create(size) 的 size 要预计算准——拼接分支多时宁可用 StringBuilder 池（40 节），别为省一次分配引入复杂度。
- 陷阱：`$"{struct}"` 未覆写 ToString 时装箱 + 默认实现反射名字——readonly record struct 自动生成 ToString/Equals 一并解决（37、55 节）。
- `u8` 字面量是 ReadOnlySpan<byte>：静态、零分配、不能隐式转 string——协议常量全用它；要 string 的地方走 Encoding，编译错误反而是保护。

## 34. 集合性能阶梯：Frozen、SearchValues 与 AlternateLookup

- 阶梯：启动构建、之后只读 → FrozenDictionary/FrozenSet（.NET 8+；读比 Dictionary 快数倍，构建贵、内存大，.NET 9 构建成本大幅下降）；动态读写 → Dictionary/ConcurrentDictionary；"线程安全只读快照"≠ ImmutableList（慢，别用错场景）。

```csharp
private static readonly SearchValues<char> s_sep =
    SearchValues.Create(",;\t ".ToCharArray());
int next = line.AsSpan().IndexOfAny(s_sep);       // 向量化扫描

var alt = dict.GetAlternateLookup<ReadOnlySpan<char>>();  // .NET 9
bool hit = alt.TryGetValue(headerName.AsSpan(), out string? v); // 键零分配
```

- SearchValues<T>（.NET 8）：把候选字符/字节集预计算成向量化查表——分隔符、白名单、协议解析的热点必换。
- 陷阱：Frozen* 构造后冻结，Add 抛异常——它不是"更快的字典"而是"只读读优化结构"；数据会变就别上。
- 陷阱：AlternateLookup 只对声明支持交替键的类型/键组合可用（string 键字典 + char span 最常见）；自定义键类型要实现 IAlternateKey 契约，别想当然。
- Frozen 内部按数据量/键型选布局——读性能不是无脑快，键分布怪或小集合时可能输给普通 Dictionary；换装前用真实数据基准。

## 35. LINQ 分配剖析与手写替换决策线

- LINQ 每个操作符链一次迭代器分配 + 委托调用不可内联——Where().First() 在百万级热路径比手写循环慢数倍并稳定分配；决策线：**每请求/每消息热路径禁 LINQ 聚合**，启动/一次性代码保留 LINQ 求可读性。
- 快路径知悉：Count() 对 ICollection 走属性；First/Any 不枚举到底；OrderBy().First() 应换 MinBy（.NET 6+，O(n) 免全排序）。

```csharp
var top = items.MinBy(i => i.Score);      // O(n),不排序
bool any = items.Any(i => i.Flag);         // 短路,不枚举全量

// 热路径替换形:span + for,零委托零分配
int sum = 0;
foreach (var v in buffer) sum += v;
```

- 陷阱：延迟执行被多播——IEnumerable 在两个消费者间各枚举一次（副作用/IO 双跑）；共享边界 .ToList() 定型，边界内继续流转。
- 陷阱：GroupBy 是分配大户；多次遍历同一分组换 ToLookup 一次构建；SelectMany+ToList 大集合注意中转放大。
- PLINQ 只服务 CPU 密集 + 大 N；IO 并发用 Task.WhenAll/ForEachAsync（88 节）——AsParallel 包 IO 调用是经典误用。

## 36. 结构体拷贝经济学：in、readonly 与防御性拷贝

- 结构体赋值/传参/返回都是逐字段拷贝；>4 字段或 >64B 传参用 `in`（传引用）——但 in 参数配**非 readonly struct** 会引入防御性拷贝：JIT 无法证明成员调用不改字段，每次访问复制整份。
- 铁律：in 参数必须配 readonly struct（或 readonly 成员）；大 struct 的属性访问器也要标 readonly（C# 8 readonly 成员），否则"优化"变"三倍拷贝"。

```csharp
public readonly struct BigVec(double X, double Y, double Z)
{
    readonly double LenSq() => X * X + Y * Y + Z * Z;  // readonly 成员防防御拷贝
    public double Dot(in BigVec other) => X * other.X + Y * other.Y + Z * other.Z;
}
// 陷阱形态:readonly 字段(非 readonly struct)上访问成员同样防御拷贝
```

- 陷阱：泛型容器是拷贝放大器——`list[i].X = 1` 是"取出副本、改副本、写回"三拷贝；原地修改用 `CollectionsMarshal.AsSpan(list)`（.NET 5+，期间禁 Add/扩容，否则 span 悬空）。
- 陷阱：async 方法签名上的大 struct 参数会在挂起时随状态机装箱（13 节）——异步边界的载荷类型优先 class 或 ref struct 禁入。
- 检测：[MemoryDiagnoser] 对比拷贝成本；反汇编看 `covocop`/副本局部变量即可实锤防御拷贝。
- Dictionary 大值类型原地更新：CollectionsMarshal.GetValueRefOrAddDefault/AsSpan 拿 ref 直接写——免"取出-改-写回"三拷贝，期间禁扩容。

## 37. 装箱暗坑清单与消解路径

- 装箱触发清单：struct 经接口变量调用、非泛型集合存 struct、`$"{struct}"` 未覆写 ToString、非泛型 `Equals(object)`、GetType()、枚举做老式字典键走 object 比较器、`where T : class` 强约束、闭包捕获 struct 局部提升为显示类字段。
- 消解路径：API 泛型化（T 替 object）、struct 实现 IEquatable<T> + 覆写 ToString、枚举键用泛型路径字典（.NET 8 键比较已走泛型特化）、闭包捕获改传参。

```csharp
// 检测实锤:!dumpheap -stat 里 System.Int32/小 struct 行数暴涨
// = 装箱税;分配率正常但 Gen0 频率畸高是它的指纹
foreach (object o in new ArrayList { 1, 2, 3 }) _ = o;   // 三次装箱
foreach (int i in new List<int> { 1, 2, 3 }) _ = i;      // 零装箱
```

- 陷阱：接口约束 + struct 实参在未特化泛型（共享泛型代码）下仍可能装箱——热点泛型类型让 JIT 特化（值类型实参自动特化，混合引用实参则共享）。
- 陷阱：可空枚举/Nullable<T> 参与接口调用同样装箱（Nullable 装箱成 boxed T 或 null）——协议层别用"object + 枚举"老组合。
- 泛型代码生成模式：纯值类型 T 被特化（零装箱），混合引用实参走共享代码 + 装箱——热点泛型路径先确认 JIT 特化与否再谈优化。

## 38. JIT 深水：分层编译、Tiered PGO 与 OSR

- 分层执行：Tier0 快速编译 + 插桩计数 → 热点升级 Tier1（全优化 + PGO 数据）；阈值由运行时策略管理（量级：几十次调用/上千次循环迭代）——**冷代码永远 Tier0**，短生命周期进程（CLI/FaaS）吃不到 Tier1。
- Dynamic PGO（.NET 8 起默认开）：分支概率/类型实况反馈进 Tier1，GDV 插守卫直调后可内联——sealed 类型层级是在给 profile 喂证据（7 节）。
- OSR（.NET 6 引入、.NET 7 默认开）：长循环的 Tier0 代码**中途**换 Tier1 版本——"死循环方法永远慢"的悬崖消失，也让基准预热语义改变。

```csharp
for (int i = 0; i < 100_000; i++) HotPath(data);   // 触发升级/OSR
// 诊断开关(勿入生产):DOTNET_JitDisasmSummary 看是否内联/去虚化
```

- 陷阱：Debug/附加调试器会禁分层与 PGO——性能结论必须 Release、无调试器、独立进程（41 节）。
- 陷阱：启动延迟敏感用 ReadyToRun 预热 Tier1 段，**别**用禁分层换启动（丢 PGO 得不偿失）；`DOTNET_TieredPGO` 环境开关只做对照实验。
- 生产可用的证据链：DOTNET_JitDisasmSummary 对比 Tier1 反汇编 + Profile 以 [DisassemblyDiagnoser] 深挖——"应该更快了"必须有汇编级出处。
- 内联预算：方法超阈值不内联，AggressiveInlining 只对小方法有效——热路径把大方法拆小函数，是在替 JIT 赚内联机会。

## 39. 向量化友好写法与边界检查消除

- 边界检查消除（BCE）条件：索引由循环变量驱动且范围可证（`for i < s.Length` + 直接 span[i]）、切片对齐——写朴素 for 而不是 foreach+indexer 混用；不确定就 JitDisasm 看 `call [BoundsCheck]` 残留。
- SIMD：显式 Vector256/Vector128 intrinsics 处理数值批量；memcpy 型操作 JIT 自动向量化——先写朴素循环看反汇编，别抢 JIT 的活。

```csharp
static uint XorSum(ReadOnlySpan<uint> s)
{
    Vector256<uint> acc = Vector256<uint>.Zero;
    ref uint r0 = ref MemoryMarshal.GetReference(s);
    for (nint i = 0; i + Vector256<uint>.Count <= s.Length; i += Vector256<uint>.Count)
        acc ^= Vector256.LoadUnsafe(ref r0, (nuint)i);
    return Vector256.Dot(acc, Vector256<uint>.One);    // 收尾归约
}
```

- 陷阱：尾部处理（length % Vector.Count）是越界 bug 高发区——尾段标量循环兜底，LoadUnsafe 越界读是 UB 级现场。
- 陷阱：Vector256.IsHardwareAccelerated 为 false 时走标量分支保留；.NET 9 起 Arm SVE、.NET 10 起 AVX10v1 硬件路径扩展——条件编译不如运行时探测（12 节）。
- 写码顺序：朴素循环 → 反汇编确认是否已自动向量化 → 只对"没向量化且占比高"的段手写 intrinsics；先写 SIMD 后看汇编是本末倒置。

## 40. 对象池设计与生命周期护栏

- 池化对象三要素：可重置（Reset 清一切引用防滞留）、可验证（租出断言状态干净）、可观察（池水位/命中率进指标）。Microsoft.Extensions.ObjectPool 通用够用，热路径手写 ConcurrentQueue 池少一层 policy 开销。
- 护栏：租还都走 try/finally；归还前退订外部事件——池化对象订阅静态事件是双料泄漏（不还漏对象、还了还会收到别人的事件）。

```csharp
public sealed class PooledBuffer : IDisposable
{
    byte[] _buf = null!;
    public PooledBuffer Rent(int n) { _buf = ArrayPool<byte>.Shared.Rent(n); return this; }
    public Span<byte> Span => _buf;   // 归还后 _buf 置 null → NRE 暴露 use-after-return
    public void Dispose() { ArrayPool<byte>.Shared.Return(_buf); _buf = null!; }
}
```

- 陷阱：池对象被序列化/跨线程携带后再归还——归还点必须是"所有权唯一且生命周期结束"的确定位置，所有权契约写进 XML 注释。
- 陷阱：池无上限 = 无背压（内存峰值不可控）；封顶后明确降级路径（新建对象/拒绝/排队）并压测。
- 分工线：对象池池"重状态的壳"（解析器/生成器），ArrayPool 池"裸缓冲"——包了 byte[] 的类再建对象池 = 双重池化反模式。

## 41. BenchmarkDotNet 纪律与测量反模式

- 基线配置：[MemoryDiagnoser] 必开；Job 钉运行时版本；迭代数用默认自适应（手动压样本量 = 结论随机）；每次对比带 BaselineColumn 看比率而不是绝对值。
- 反模式清单：Debug 编、附加调试器、被测方法返回值不消费（**死码消除直接删光你的代码**）、基准里 Console 输出、共享可变状态跨迭代。

```csharp
[MemoryDiagnoser]
public class SumBench
{
    private int[] _data = null!;
    private int _sink;                                // 防 DCE 的 sink
    [GlobalSetup] public void Setup() => _data = Enumerable.Range(0, 100_000).ToArray();
    [Benchmark(Baseline = true)] public void Linq() => _sink = _data.Where(x => x > 0).Sum();
    [Benchmark] public void Loop() { int s = 0; foreach (var v in _data) s += v; _sink = s; }
}
```

- 陷阱：手写 Stopwatch 计时循环没有 tiered JIT 预热——结论必歪；BenchmarkDotNet 默认处理预热与升级，别重复造轮子。
- 陷阱：微基准 2x ≠ 宏观收益——方法只占请求 0.1% 时无感；用 dotnet-trace 佐证热点占比再动手（42 节）。
- 噪声纪律：同机同电源计划、空转预热、多 Job 对照；比率的置信区间（Ratio column 的 CI）不重叠才算赢。
- 基准进 CI 的最小配置：Job 钉 CPU 档位 + Baseline 固定 main 历史二进制——环境漂移是性能门禁假阳性头号来源（101 节）。

## 42. 热路径分配审计工作流

- 三步流：①dotnet-counters 建 `GC Allocation Rate` 与 gen0-gc-count 基线 → ②BenchmarkDotNet [MemoryDiagnoser] 定位单操作分配 → ③反汇编/装箱清单复核（37 节）。
- 目标量级：每请求分配从 MB 级压到几十 KB 在 .NET 8+ 常见（Span 化 + 池化 + LINQ 退场）；Gen0 频率从每秒几十次到个位数，尾延迟直接受益。

```csharp
// 中间件分配审计:线程级精确计数
app.Use(async (ctx, next) =>
{
    long before = GC.GetAllocatedBytesForCurrentThread();
    await next();
    _alloc.Observe(GC.GetAllocatedBytesForCurrentThread() - before, ctx.Request.Path);
});
```

- 陷阱：GetAllocatedBytesForCurrentThread 在 await 换线程后读到**错误线程**——审计中间件要么同步段测量（无 await 的窄段），要么断言线程号一致。
- 陷阱：只看字节数会漏对象图深度——小对象撑过两次 GC 进 Gen2 同样致命（43 节"中命对象"）；把 time-in-gc 与 Gen0/Gen2 计数一起看。
- 把分配预算当 SLO：按端点记录 P95 分配字节并排序曝光——预算爆表的端点进优化队列，比"全员优化"务实一个量级。

## 43. 分代模型与 Gen0 预算经济学

- Gen0 预算是动态的：由分配速率、核数、存活率驱动（Server GC 每核独立堆）——本质是"分配满一页 Gen0 就触发 GC"，所以**降分配 = 降 GC 频率**，调参永远是次优先。
- 各代停顿量级：Gen0 亚毫秒、Gen1 毫秒级、Gen2 全量停顿毫秒到几十毫秒 + 并发标记——短命对象便宜、长寿对象贵、**中命对象最毒**（恰好撑过每次 GC 晋升）。

```bash
dotnet-counters monitor --process-id 1234 System.Runtime
# 盯:gc-heap-size / gen0-gc-count / time-in-gc / gc-allocated-bytes
# time-in-gc > 10% 就该动手降分配(42 节)
```

- 陷阱：定期 `GC.Collect()` "清内存"是反模式——把本该死在 Gen0 的对象强制晋升，亲手制造 Gen2 压力；只有测试基线与 NoGCRegion 边界允许显式触发。
- 陷阱：Gen0 存活率异常高（大量对象刚好活着）会让 Gen0/Gen1 膨胀——查"请求级对象被缓存/静态集合引用"的中命模式（47 节取证）。
- 大对象算术：单次分配超 Gen0 预算直接落 LOH/大段——"一次大分配"与"多次小分配"的 GC 代价不是线性关系，批处理设计按段预算（44 节）。
- time-in-gc 只计阻塞部分；并发标记在后台跑、吃的是 CPU 不算停顿——指标要分"停顿时间"与"GC CPU 占比"两个口径读。

## 44. LOH 与 POH：大对象与固定堆

- 85000 字节阈值直接进 LOH（可配 DOTNET_GCLOHThreshold）；LOH 默认**不压缩**——大缓冲频繁分配/释放 = 碎片 + Gen2 级停顿；正解是 ArrayPool 化（32 节）而不是调阈值。
- POH（.NET 5+）：`GC.AllocateArray<T>(size, pinned: true)` 分配在固定对象堆——对象永不搬家，P/Invoke/IO 场景替代 fixed/GCHandle，无碎片税。

```csharp
byte[] ioBuf = GC.AllocateArray<byte>(64 * 1024, pinned: true);   // POH
// 对照:fixed 是局部钉住,大范围 pin 卡住整个 Gen0/Gen1 压缩
```

- 陷阱：`fixed` 禁跨 await（编译错误）→ 有人换 GCHandle.Alloc(pinned) 忘 Free = **永久钉住**，碎片隐形来源；GCHandle 计数进监控。
- 大数组免清零：GC.AllocateUninitializedArray 跳过零化（脏数据可控时），配 Return(buf, clearArray:true) 成对使用——两侧语义必须一致。
- 陷阱：大 struct 数组按**总字节数**判段（new Big[10_000]）；double[10_600] 刚过 85K 就是 LOH 常客——阈值判断别只看 Length。
- POH 的边界：只对"分配后长期钉住"的缓冲划算；短命对象进 POH 反而放大不压缩区的压力——先池化，池化后仍长期 pin 才上 POH。
- 观察：GC.GetGCMemoryInformation().FragmentedBytes 与堆大小同步增长 = 压缩失灵史；fragmentation 比率持续 >15% 就该查 pin 来源（49 节）。

## 45. GC 模式选择与延迟预算

- 选型：吞吐/多核服务 → Server GC（每核一堆并行回收）；桌面/低核 → Workstation Concurrent；停顿敏感低吞吐 → WS + LatencyMode.LowLatency；极端短窗 → GC.TryStartNoGCRegion（配量预算）。
- 配置入口：runtimeconfig.json 的 System.GC.Server/Concurrent/HeapHardLimit* 家族；容器里优先打进镜像防环境漂移（50 节）。

```json
{ "configProperties": { "System.GC.Server": true, "System.GC.Concurrent": true } }
```

- 陷阱：Server GC 内存 = 每核一段——小容器/低核限制下内存直接翻倍，2 核容器开 Server GC 是常见 OOM 剧本（46、50 节）。
- 陷阱：SustainLowLatency 只在 Workstation Concurrent 有意义且推高内存上限；切模式前先拿 time-in-gc 基线，切换后再测——没有基线的调参是玄学。
- 停顿预算先行：先定 SLA（P99 停顿上限）再选模式；Server GC 并发标记期托管代码照跑，"Concurrent=无停顿"是误解——标记结束的短暂 STW 仍在。
- TryStartNoGCRegion 的量必须 ≤ 暂态段预算，调用后立刻做关键工作再 Stop——超量失败返回 false 要处理，别当无条件开关用。
- System.GC.RetainVM 控制释放段是否归还 OS——高抖动分配型服务开着它减少 page fault 风暴，代价是 RSS 虚高；容量规划看"活跃堆"不看 RSS。

## 46. DATAS 与容器感知 GC 调参

- .NET 9 起 Server GC 默认启用 DATAS（Dynamic Adaptation To Application Sizes）：按应用实际需求动态缩放堆/线程——容器内存随负载收敛，不再"一上来按核圈地"；退回旧行为用 DOTNET_GCDynamicAdaptationMode=0。
- 容器感知：内存限制下 GC 堆硬上限默认约 **75%**（COMPlus_GCHeapHardLimitPercent 可调）；逼近上限时 GC 越来越激进，再涨的部分多半是 native/池/映射文件——先分流诊断而不是调 GC。

```csharp
var info = GC.GetGCMemoryInformation();
Console.WriteLine($"heap cap ≈ {info.TotalAvailableMemoryBytes:N0} bytes total, " +
                  $"server={GCSettings.IsServerGC}, fragmented={info.FragmentedBytes:N0}");
```

- 陷阱：DATAS 降内存可能增 GC 次数——内存敏感与吞吐敏感的取舍用 A/B 压测定，不靠文档直觉。
- 陷阱：.NET 6 前容器内"可用内存"读的是宿主机物理内存——GC.GetGCMemoryInfo/Environment 的新 API 才认 cgroup 限制；升级 TFM 后必须重测基线。
- K8s 里配 `System.GCHeapHardLimitPercent` 时同步放宽 Pod memory limit 余量（GC 堆 + native + JIT 代码页 + 映射）；只调 GC 不调 limit 是伪修复（50 节）。
- DATAS 只作用于 Server GC——Workstation 压根不走这套；小实例"开 DATAS"的前提是先断言当前真是 Server GC（GCSettings.IsServerGC）。

## 47. 内存泄漏取证工具链

- 首诊分流：dotnet-counters 看 GC 堆 vs 进程 RSS——RSS 涨、堆不涨 = native/池/映射文件问题；堆也涨才进 GC 取证。
- 堆取证：dotnet-gcdump 抓两个稳态时点做 diff（按类型数量增量排序），比 full dump 小一个量级；增长类型沿"引用路径到根"追责。

```bash
dotnet-gcdump collect -p 1234 -o t1.gcdump    # 稳态采样点1
# ……跑 10 分钟负载……
dotnet-gcdump collect -p 1234 -o t2.gcdump
dotnet-gcdump report t2.gcdump | sort -k2 -nr | head -30
```

- 根因 Top3：静态缓存无上限（字典只增不删）、事件订阅忘退订（Publisher 活得比 Subscriber 长）、IDisposable 未触发（Timer 持宿主引用）。
- 陷阱：生产抓 dump 会 STW 数秒（大堆更久）——低峰执行、限次执行；gcdump 优先于 full dump。
- 陷阱：泄漏不在 GC 堆（ArrayPool 租不还、native buffer、内存映射）时 gcdump 看不见——换池化组件自身指标 + native 内存计数。
- 确认增长类型后：dotnet-dump 的 `gcroot` 追到根——经验序：静态事件 > 静态缓存 > 未退订定时器 > finalizer 链，按序排查最快。

## 48. Finalizer 纪律与 SafeHandle 替代

- Finalizer 的真实成本：可终结对象至少多活一次 GC（终结队列登记）、典型要活到 Gen2 才真正释放；终结线程是**单点**——一个 finalizer 阻塞，全进程 native 资源回收雪崩。
- 99% 场景用 SafeHandle：临界终结（进程退出兜底）+ 引用计数防 double-free + P/Invoke 原生融合（104 节）。

```csharp
public sealed class MyNativeRes : SafeHandle
{
    public MyNativeRes() : base(IntPtr.Zero, ownsHandle: true) { }
    public override bool IsInvalid => handle == IntPtr.Zero;
    protected override bool ReleaseHandle() => Native.Free(handle);
    // 纪律:所有收发句柄的 P/Invoke 用 MyNativeRes 参数,杜绝 IntPtr 裸传
}
```

- 禁区：finalizer 里不摸托管对象（目标可能已被收走）；Dispose 模式里 GC.SuppressFinalize(this) 必配；析构只做 native 清理。
- 陷阱：写 `~MyClass()` 后又"正确 Dispose 了"——类型仍可终结，分配进终结队列、晋升照旧；删掉 finalizer 或从一开始就 SafeHandle。
- 陷阱：终结名与 Dispose 竞态（GC 触发 finalizer 与用户线程 Dispose 并发）——SafeHandle 内部处理了；手写模式要 Interlocked 门闩。
- 异步处置与 SafeHandle 组合：Stream.DisposeAsync 是范本——自建 native 包装实现 IAsyncDisposable 时双路径都要汇聚到 ReleaseHandle，别写两套释放。

## 49. 固定缓冲策略与碎片治理

- Pin 四来源：GCHandle.Alloc(pinned)、fixed 语句、P/Invoke 传数组时 marshaller 自动 pin、异步 IO 缓冲钉住——任何一个都让 GC 压缩跳过该区域；**短命 + 分散**的 pin 是碎片制造机。
- 治理顺序：长命固定缓冲迁 POH（44 节）→ IO 池统一大块切片（减少 pin 次数）→ fixed 范围最小化（只包 native 调用，不包循环）。

```csharp
// 好:pin 只包住一次 native 调用
fixed (byte* p = buf) NativeWrite(p, len);
// 坏:pin 跨整个批处理循环——迭代里的 GC 全部无法压缩该区
```

- 陷阱：P/Invoke 传 byte[] 时 marshaller pin 整个数组——span 重载/LibraryImport 显式化后 pin 窗口归零（104 节）。
- 诊断：GC.GetGCMemoryInformation().FragmentedBytes 持续上升 = 压缩失败史；配合 dotnet-counters fragmentation 比率与 pin 计数定位元凶。
- GC.Collect(2, GCCollectionMode.Aggressive)（.NET 8+）可强制大压缩，用于低峰"整形"碎片——是手术不是日常保养，进维护窗口脚本而不是业务代码。

## 50. 容器内存限制下的 GC 事故

- 典型剧本：容器 limit=512MB，Server GC 按核圈地 + 堆硬上限 75%，负载高峰 LOH 碎片 + native 增长 → OOMKilled（exit 137）；根因常是"圈地 + 碎片 + 非托管"三合一。
- 修复顺序：①HeapHardLimitPercent 留 20% 余量 ②压 LOH 压力（ArrayPool 化大缓冲）③2 核小容器评估切 Workstation GC 或开 DATAS（46 节）④native 部分单独预算与监控。

```bash
# 事故现场特征链
kubectl describe pod app   # Last State: OOMKilled, Exit Code: 137
# 同期 dotnet-counters: gc-heap-size 长期贴近 limit*0.75,GC 疯狂自救
```

- 陷阱：CPU limit 限流（CFS throttle）会饿死 GC 并发线程，GC 时间雪崩——CPU limit 给 GC 留余量或调大时间片，别只盯内存。
- 陷阱：OOM 前最后一个动作常是**大响应序列化**——分配尖峰比稳态更致命；压测必须带突发（spike）模型，稳态基准不暴露这类风险。
- 陷阱：OOM 后别盲目加 limit"灭火"——加 limit 只是买排查时间，同一月二次 OOM 说明根因没动；按修复顺序推进而不是按告警推进。
- 容器里 `GCHeapHardLimit` 一旦显式设置，DATAS/动态缩放会受钳制——两者叠加时以更保守者生效；调参顺序固定：先 DATAS 观察，再考虑硬上限。

## 51. 泛型型变生产坑：协变、逆变与不变

- 协变 out 只覆盖"只读产出"位（IEnumerable<out T>）、逆变 in 只覆盖"只写消费"位（Action<in T>）；List<T> 不变是刻意设计——可写容器型变 = 类型系统溃坝，C# 数组协变就是历史反例（object[] 装着 string[]，写非 string 运行时才炸）。

```csharp
interface IRepo<out T> { T Get(int id); }       // OK:产出位
interface ISink<in T> { void Save(T item); }    // OK:消费位
IEnumerable<Animal> xs = new List<Cat>();       // 协变合法
// IRepo<out T> 加 void Save(T) → 编译错误:拆接口或分型变设计
```

- 陷阱：class 泛型不能型变（只有 interface/delegate）；struct 实参进型变接口即装箱——IEnumerable<int> 换 IEnumerable<object> 逐元素装箱（37 节）。
- 陷阱：型变接口的"隐式转换"只是引用换壳——同一对象两个视图，相等/哈希行为不变，但调试器显示的静态类型会骗人。
- 设计纪律：公共容器接口尽早决定型变方向；后期加型变 = 破坏性变更（实现方全部重编译）。

## 52. static abstract 接口成员与泛型数学

- C# 11 起接口可声明 static abstract/virtual 成员（运算符、工厂、属性）——泛型数学 INumber<T> 的基石；调用通过**泛型约束静态分派**，每个 T 特化一份代码。
- 深水：static 成员不进实例虚表——只能经 `where T : INumber<T>` 或显式接口名访问；具体类型直接调用走的是另一条（非泛型）绑定路径，性能特征不同。

```csharp
static T Sum<T>(ReadOnlySpan<T> xs) where T : INumber<T>
{
    T acc = T.Zero;                          // static abstract 属性
    foreach (var x in xs) acc += x;          // static abstract 运算符
    return acc;
}
// checked 语境配套:金融路径用 checked { acc += x; } 走 checked 运算符
```

- 陷阱：特化是按 T 展开的——T 种类多时 JIT 时间与 icache 双伤；工具库把数学泛型收窄到 2-4 种 T（int/long/double/decimal）。
- 陷阱：INumber<T> 约束下 int 溢出是 wrap、decimal 是抛——同一算法对两种 T 行为不同，checked 测试矩阵必须覆盖两种。

## 53. 可空注解纪律：NotNullWhen 三件套

- TryX 范式的正确注解是 `[NotNullWhen(true)] out T? value`——没有它，调用方在 true 分支仍被警告或被迫 suppress；三件套（NotNullWhen/MaybeNullWhen/NotNullIfNotNull）是"全库零 suppress"的根基。
- 家族分工：[AllowNull]（写允许 null、读不空——setter 归一化）、[DisallowNull]（反向）、[MaybeNull]（返回可能空但引用型参数标非空时）——属性上用错一个，下游 suppress 成灾。

```csharp
public static bool TryParse<T>([NotNullWhen(true)] out T? value) where T : notnull
{ value = default; return false; }            // true 分支调用方拿到非空视图

[AllowNull] public string Name               // 允许赋 null,读取仍非空
{
    get => _name;
    set => _name = value ?? "anon";
}
```

- 陷阱：注解的最大价值在**公共 API 面**——应用内部大量标注是过度工程；先注满对外契约。
- 陷阱：分析器不认识你的自定义 TryX 形态（返回 bool + out）时检查是否缺 NotNullWhen——报"明明判了 null 还警告"九成是漏注解而不是分析器笨。
- 陷阱：async 方法返回 Task<T?> 的注解要落在 Task 泛型参数上（Task<Thing?> 而不是 [MaybeNull] Task）——位置错了等于没标。

## 54. 泛型可空性深水：T? 与 notnull 约束

- T? 三态语义：class 约束下 = 可空引用；struct 约束下 = Nullable<T>；**无约束**下 = "可空值类型或可空引用的超集"（允许 default、赋值面收窄）——同一签名三种约束行为不同，是公共 API 陷阱。
- `where T : notnull` 给 T 上"非空"上限后 T? 才能按可空视图解释；无约束泛型里的 default 是"可能是 null 也可能是 0"——用 [MaybeNull] 比裸 default 诚实。

```csharp
static T? FirstOrDefault<T>(IEnumerable<T> xs) => xs.FirstOrDefault();   // 无约束:语义混
static T? FindRef<T>(IEnumerable<T> xs) where T : class => xs.FirstOrDefault(); // 明确可空引用
```

- 陷阱：无约束 T? 在 T=int 时返回 int?、T=string 时返回 string?——调用方处理逻辑两开花；能收窄约束就收窄，不能就在文档写明三态。
- `default` 约束（C# 13）：`where T : default` 表达"非可空值类型或 Nullable<T> 都收"——`struct` 约束明确排除 Nullable<T>，要收可空值类型实参就换 default；改约束等于放宽调用面，重新审计类型实参。
- 陷阱：泛型可空性是**编译期幻象**——运行时反射/序列化器看不到注解；序列化边界对 T 做运行时空检查，别指望注解兜底。
- [MaybeNull] out / T? 返回值在 IL 层是同一个签名——对外二进制兼容，但源码级重编译会改变调用方警告行为；公共库更新可空性属于"软破坏性变更"，要在 release note 标明。

## 55. record class、record struct 与 readonly struct 选型

- 决策矩阵：值语义 + ≤16B + 高频拷贝 → readonly record struct（生成 IEquatable）；值语义 + 继承/多态需求 → record class（唯一能继承）；身份语义/可变状态 → class；吞吐关键大载体 → readonly struct + in 传参（36 节）。
- record struct 默认可变——要真不可变必须 readonly record struct；record struct 不能继承（可实现接口）、无 sealed 概念。

```csharp
public readonly record struct Money(decimal Amount, string Currency);
public abstract record Shape { public abstract double Area(); }
public record Circle(double R) : Shape { public override double Area() => Math.PI * R * R; }
```

- 陷阱：record 相等按字段 EqualityComparer<T>.Default——double 的 NaN 与 NaN **相等**（与 == 相反）、+0/-0 相等；金融/哈希场景先测相等语义再选 record。
- 陷阱：record struct 的显式无参构造（C# 10+）与字段默认值交互——序列化器走无参构造时"默认值"来自构造器还是 default 取决于实现，跨库行为不一致（79 节）。
- record class 天生不可变但不含"不可变集合"——List 属性的 record 仍是共享可变状态；深不可变要配 Frozen/Immutable 集合字段，相等性也随之按引用比。

## 56. record 深水：equality 继承与 with 陷阱

- record class 相等带 EqualityContract：**不同层级永不相等**（字段全同也不行）——多态容器按值去重时，基类引用与派生实例永远"不等"，这是设计出来的隔离不是 bug。
- with 表达式走受保护拷贝构造 + 逐成员 init：with 保**运行时类型**——基类引用上 with 仍产出派生实例（成员完整），跨层修改字段用虚属性或显式下沉。

```csharp
record Person(string Name);
record Employee(string Name, int Id) : Person(Name);

Person p = new Employee("Ann", 1);
var copy = p with { };                       // 仍是 Employee:拷贝构造保类型
_ = p.Equals(new Person("Ann"));             // false:EqualityContract 层级隔离
_ = copy is Employee { Id: 1 };              // true:with 复制完整对象图
```

- 陷阱：record 的 ToString（PrintMembers）每次调用新串——异常消息/热点日志里的 `$"state {rec}"` 是分配大户；缓存格式化或手写关键路径。
- 陷阱：record + 非密封 + JSON 多态反序列化要配 JsonDerivedType（77 节）——with 的"保类型"与反序列化的"按鉴别器选型"是两条腿，缺一条就有惊喜。
- 陷阱：record struct 的 with 在修改大结构时全字段拷贝——热循环里 with 链等于每轮一份新副本；原地可变场景说明它不该是 record（55 节选型线）。

## 57. init 与 required 成员的反序列化兼容

- required 是双端契约：编译期强制构造点赋值，STJ 反序列化端同样强制（.NET 7 起缺成员即 JsonException）；[JsonRequired] 只作用于 STJ 一端、编译期不检查——两者语义不同，评审时别混用。
- [SetsRequiredMembers] 标注构造函数 = 向编译器承诺"构造器已赋值"；配 [JsonConstructor] 时 STJ 也豁免 required 检查——契约责任转移到"构造器参数 + JSON 属性"的绑定关系上。
- init 资产：STJ 能在反序列化中直接写 init-only 属性；Newtonsoft 老版本对 init 属性走构造器推断失败会静默跳过——双序列化共存期行为分叉（79 节）。

```csharp
public class Payment
{
    public required string Currency { get; init; }   // JSON 缺失 → JsonException
    public decimal Amount { get; init; }
    [JsonConstructor, SetsRequiredMembers]
    public Payment(string currency, decimal amount)  // 参数覆盖 → required 豁免
        => (Currency, Amount) = (currency, amount);
}
// 参数名与属性名必须一致(大小写不敏感);漂移 = 绑定关系静默失联
```

- 陷阱：required 与可空注解的组合语义——`required string? Name` 允许显式 null；`required string Name` 收到 null 抛 JsonException；"required 但可空"的建模先想清楚再写。
- 陷阱：required 不提供默认值——老数据缺新字段直接失败；"新字段向前兼容"要用可空 + 校验兜底，不是 required（80 节 tolerant reader）。
- 陷阱：ORM/映射库（Dapper 构造、AutoMapper）对 required 各有私有处理——公共 DTO 的构造器形状统一显式标注，别依赖跨库默认一致；源生成模式同样强制 required（75 节）。

## 58. 主构造函数捕获语义与泄漏

- 捕获规则：主构造函数参数被任何成员引用才提升为字段（未引用 = 零成本）；合成字段不可 readonly、整个类体可见——"看起来像参数，实际是可变实例字段"是全部陷阱的根源。
- 语义陷阱：任意成员都能给参数赋值——`void Rebind() => endpoint = fallback;` 改的是捕获字段，所有成员立刻可见；要真只读就显式 `private readonly Uri _ep = new(endpoint);`（重复存储换确定性）。
- base 主构造：基类主构造参数只在派生类 base(...) 链表达式可见、进不了成员体；基类把参数捕获进方法/lambda 后，派生类必须链到主构造，否则捕获字段属"半初始化"状态。

```csharp
public class Gateway(string endpoint)
{
    private readonly HttpClient _http = new() { BaseAddress = new Uri(endpoint) };
    public void Rebind() => endpoint = "https://fallback";  // 合法!改捕获字段
    static void Bad() => _ = endpoint;   // CS9101:静态成员禁用主构造参数
}
```

- 泄漏面：捕获参数（或 this）被带进长生命周期订阅/静态事件——闭包把构造现场钉进订阅方（66、108 节）；"订阅静态事件 + 主构造捕获"是双重泄漏组合拳。
- 陷阱：struct 的主构造参数捕获按值复制进字段——结构体拷贝后多个副本各改各的捕获字段，行为随拷贝漂移。
- 陷阱：主构造参数与同名属性冲突时的解析规则咬人——参数 camelCase、成员 PascalCase 强制分离，评审对同名对直接要求改名。
- 诊断：ILSpy 看合成字段（`<endpoint>P` 形态）确认真实捕获集——"以为没捕获"的每实例字段就是这样长出来的。

## 59. 集合表达式性能语义与展开

- 目标类型决定构造路径：同一 `[a, b, c]` 在 span/数组/List/接口目标下生成完全不同的代码——热路径先确认目标类型再写集合表达式，分配语义随目标翻转。
- span 目标：常量元素 → 数据直接嵌进程序集（零分配、天然静态）；非常量小规模 → 栈上 inline array；含 `..spread` 且展开源非 span → 中转数组 + 拷贝（分配回来了）。
- List 目标：元素数静态已知 → 按容量构造后填充（免扩容）；`..` 展开 IEnumerable → 枚举 + 缓冲，容量不可预知。

```csharp
ReadOnlySpan<int> a = [1, 2, 3];        // 嵌入只读数据,零分配
Span<byte> b = [x, y, z];               // 非常量:栈上 inline array(小规模)
int[] c = [.. a, .. b.ToArray()];       // spread 混合 → 中转分配
List<int> d = [1, 2, 3, .. more];       // 已知容量 + 枚举展开
_ = TakesArgs([1, 2, 3]);               // 目标不明确 → 编译错误,不是静默数组
```

- 嵌套展开成本：`[[1,2],[3,4]]` 到 List<List<int>> 逐层构造、到 int[][] 逐数组分配——大常量表提升为静态字段缓存，别在热路径反复构造。
- params 组合（C# 13）：`params ReadOnlySpan<T>` 接收集合表达式 = 调用点栈缓冲直传零分配——库 API 变长参数的演进方向（2 节）。
- 陷阱：目标是 `IEnumerable<T>` 时每次求值物化新容器——"延迟执行/共享"语义全无，要共享先存局部变量。

## 60. using 别名进阶与 any-type 别名

- C# 12 起别名可指向任何类型：元组 `(int X, int Y)`、数组 `double[]`、深层泛型可空组合 `Dictionary<string, List<int>?>`、指针 `int*`（unsafe 上下文）——复杂签名的集中治理点。
- 作用域铁律：using 别名是文件级的——跨文件要么每文件重复声明，要么 `global using Xxx = ...` 收进 GlobalUsings.cs 统一维护；文件内 using 与 global using 冲突时文件内赢。

```csharp
global using Point   = (int X, int Y);
global using JsonMap = System.Collections.Generic.
    Dictionary<string, System.Text.Json.JsonElement?>;
global using Row     = double[];

// 别名是擦除:typeof(Point) 是 ValueTuple<int,int>,元素名只在编译期
void M(Point p) { }          // 与 void M((int X, int Y) p) 签名冲突(CS0111)
```

- 陷阱：别名不产生新类型——用它区分重载/签名毫无效果（底层同一类型）；它只治理可读性，不治理类型安全。
- 陷阱：元组元素名是元数据（TupleElementNames），反射/序列化看不见（67 节）——别名元组进公共 API 返回值或 JSON 契约是自欺。
- 别名不能引入新泛型参数：`using Pair<T> = ...` 非法——只有封闭构造可命名；开放泛型场景建包装 record。
- 价值定位：把"三层泛型 + 可空"压成领域名词（JsonMap/Point）是别名唯一但实在的战场；给基本类型起别名只会降低可搜索性。

## 61. 模式匹配深水与判别式建模

- 判别式联合在 C# 的正解形态：abstract record 基类 + sealed 派生 + 类型模式 switch——sealed 封闭类型全集（同时给 GDV 喂去虚化证据，7 节）；**编译器不做层级穷尽检查**，基类兜底臂永远要写（防新增派生静默漏）。
- 穷尽性真相：switch 表达式只对可证全集免 default——enum 全成员覆盖被当穷尽，但运行时进来未定义值会命中编译器插入的兜底抛（.NET 8+ 是 UnreachableException）；层级类型缺 `_`/基类臂是编译错误。

```csharp
static string Render(Shape s) => s switch      // Shape: abstract record,派生全 sealed
{
    Circle c      => $"r={c.R}",
    Rect { W: 0 } => "degenerate",             // 属性+关系模式组合
    Rect r        => $"{r.W}x{r.H}",
    _             => throw new UnreachableException()  // 兜底:新派生 = 显式炸
};
// list pattern:[1, .., var last] / .. var mid 切段;对 span/array/List 走
// 计数+索引快路径,对 IEnumerable 走枚举缓冲——热路径先备好数组
```

- 陷阱：when 守卫按臂序短路求值且副作用可见——守卫里放 IO/状态修改 = 顺序耦合；守卫保持纯谓词。
- 陷阱：`is` 模式链对 object 载荷每次类型测试都伴随开箱拷贝——密集匹配先拆箱一次再测。
- 组合纪律：`and/or/not` + 关系模式能消灭嵌套 if，但模式链超三层就该下沉为方法或查表——"聪明"代码的评审折旧最快。

## 62. Equals 与 GetHashCode 契约

- 两条硬契约：相等的对象必须同哈希（违反 = 字典随机丢条目，无异常无日志）；对象充当键期间哈希输入不可变——改键字段后条目"失联"（桶按旧哈希定位），连 Remove 都找不到它。
- struct 默认 Equals 走 ValueType 反射路径（慢且可能装箱）；进字典/比较的 struct 必须实现 IEquatable<T>——EqualityComparer<T>.Default 优先走它；record struct 自动生成是最低配置（55 节）。

```csharp
public readonly struct TaxId(string value) : IEquatable<TaxId>
{
    public bool Equals(TaxId other) => value == other.value;
    public override bool Equals(object? o) => o is TaxId t && Equals(t);
    public override int GetHashCode() => value.GetHashCode(StringComparison.Ordinal);
    // string.GetHashCode() 每进程随机化——哈希绝不持久化/跨进程比较
}
```

- HashCode.Combine 是标准生成器（自带扰动与展开）；手写哈希只有"基准证明 Combine 是热点"这一种合法出场。
- 陷阱：NaN/-0.0 的相等语义由 EqualityComparer<double>.Default 统一（NaN 相等、±0 相等且同哈希）——record 生成的相等沿用它，与 == 相反（55 节）；哈希场景专门测边界值。
- 陷阱：派生类给基类类型加强类型 Equals 重载造成不对称——比较契约保持对称或整个层级用 record（56 节 EqualityContract 隔离）。
- 真引用哈希：RuntimeHelpers.GetHashCode——覆写 Equals 后仍要按身份归组的场景用它，两个世界别混。

## 63. 运算符重载与 C# 14 复合赋值

- C# 14 复合赋值放宽：`a op= b` 允许 a 先按用户定义转换到 T、再走 T 的 op 运算符——包装数值类型（Money/Frequency）终于能自然 `+=`；注意隐式转换可能在每轮复合赋值里都跑一遍。
- checked 配套（C# 11）：checked 语境调 `operator checked +`、unchecked 调普通版——金融/计数类型两个都实现，溢出 wrap 与抛的两条路径都要显式测试。

```csharp
public readonly struct Money(decimal amt, Currency cur)
{
    public static Money operator +(Money a, Money b)
        { Check(a, b); return new(a.amt + b.amt, a.cur); }
    public static Money operator checked +(Money a, Money b)
        { Check(a, b); return new(checked(a.amt + b.amt), a.cur); }
    public static implicit operator Money(decimal raw) => new(raw, Currency.USD);
}
checked { total += rawAmount; }   // C# 14:转换成 Money 后进 checked 运算符
```

- 陷阱：重载 == 必须同步覆写 Equals + GetHashCode——"== 按值、Equals 按引用"的不一致让字典与断言各说各话；编译器警告只是下限。
- 陷阱：`x == null` 在重载了 == 的类型上走运算符而非引用判空——判空统一 `is null`/`is not null` 绕开运算符。
- 陷阱：隐式转换是复合赋值陷阱的放大器——`sum += rawAmount` 每轮转换 + 每轮新 struct 拷贝；热点循环先归一类型再算。
- 设计红线：运算符语义必须与类型直觉一致（无共识的比较别定义）；运算符禁止 IO/日志副作用——排障地狱。

## 64. 类型推断暗角：三元、lambda 与重载决议

- 三元融合规则：两臂找最佳公共类型——数值提升（int 与 long → long）、字面量 null 可融进引用/可空、引用取最近公共基类（C# 9 起免显式转型）；融不出即编译错误：`flag ? 1 : null` 必须写 `flag ? 1 : (int?)null`。
- lambda 自然类型（C# 10）：完整类型签名的 lambda 才有自然类型（`(int x) => x` → Func<int,int>）；参数无类型 → 无自然类型 → `var f = x => x;` 非法；三元里两个不同签名 lambda 只能靠目标类型救。

```csharp
int? v = flag ? 1 : (int?)null;                 // 显式目标:融合才成立
var pick = flag ? (int x) => x : (int x) => -x; // 同自然类型 → Func<int,int>
Task.Run(async () => await WorkAsync());        // 绑 Func<Task>:async+await
Task.Run(async () => { Do(); });                // 绑 Action:async 无 await!
```

- 重载暗雷：目标同时有 Action 与 Func<Task> 重载（Task.Run 一族）时，**无 await 的 async lambda 绑 Action**——async void 语义跑进任务、异常逃向线程池（86、107 节）；评审规则：async lambda 里必须有 await，否则删 async。
- 目标类型 new：`return new(...)`、字段、三元臂都能推断；`var x = new()` 无目标非法——报错是在帮你。
- 方法组转换（C# 11+）与零捕获 lambda 都有调用点缓存（66 节）——别按"每次 new 委托"的老经验手写缓存层，先反汇编确认。
- 陷阱：三元臂一侧 ThrowExpression 时类型取另一臂——`x is null ? throw new() : x.Value` 合法但可读性让位显式 if。

## 65. 接口默认实现的菱形陷阱

- DIM 只能经接口引用调用：类实现带默认成员的接口后，成员**不出现在类的公开面**——`((IFeature)obj).M()` 才分派到默认实现；"类里看得见调不到"是第一现场事故。
- 最具体实现决议：类实现 IB、IC（都扩展有默认 M 的 IA）——仅 IB 覆写 M 则 IB.M 胜（更接近类）；IB、IC 都覆写 = 歧义编译错误，类必须自己实现裁决。

```csharp
interface IA { void M() { Console.WriteLine("IA"); } }
interface IB : IA { void IA.M() => Console.WriteLine("IB"); }
interface IC : IA { }
class C : IB, IC { }
IA a = new C(); a.M();        // 输出 "IB":唯一覆写最具体
// new C().M() 编译错误:C 上没有 M —— DIM 不是免费基类方法
```

- reabstraction：接口显式把继承的默认成员重声明为 abstract 可强制实现类提供——演进接口时比"悄悄加默认"更诚实。
- 陷阱：struct 经接口引用调用 DIM 必装箱（接口分派要对象头）——值类型热点上的隐形装箱点（37 节）。
- 陷阱：给已发布接口追加 DIM 对旧二进制运行时兼容（未实现者经接口调用命中默认），但对**枚举类成员的框架（mock/序列化/映射）是行为变化**；接口演进仍按破坏性变更评审。
- 陷阱：接口里放 private static 字段（DIM 的合法搭配）= 所有实现共享的隐藏全局状态——评审红灯（108 节）。

## 66. 闭包分配与 lambda 缓存复用

- 分配分档：零捕获 lambda → 静态缓存委托（同调用点只 new 一次，C# 9 `static` 修饰锁死无捕获）；仅捕获 this → 实例级缓存字段；捕获任意局部变量 → 显示类实例 + 委托**每次执行都分配**。
- 热路径纪律：委托参数按"捕获面"审——`Where(x => x.Id == target)` 捕获 target = 闭包 + 委托每请求双份；谓词参数化进字段、预构委托，或 LINQ 退场手写循环（35 节）。

```csharp
class Scanner
{
    private string? _needle;                                   // 实例字段代捕获
    private bool Match(string s) => s.Contains(_needle);
    internal static readonly Func<int, bool> s_pos = static x => x > 0;
    // static lambda 引用实例成员 → 编译错误:这正是要它的原因
}
```

- for 循环变量捕获（C# 5 只修了 foreach）：`for (int i...) capture(i)` 的 i 全轮共享——延迟执行的委托全部看到终值；需要每轮快照就在循环体内 `int j = i;`。
- 泄漏面：闭包把整个外层方法状态钉进订阅方——长生命周期事件/缓存里的 lambda 是经典泄漏根（47、58 节）；退订必须成对。
- 陷阱：闭包捕获 struct 局部 = 拷贝进显示类字段——闭包内外两个世界互不可见地分叉；要共享改用类或数组包装。
- 陷阱：构造函数里 `handlers.Add(Handle)` 每实例一份委托——量大时改调用点直调或按需缓存（结合 64 节方法组缓存判断）。

## 67. 匿名类型与 ValueTuple 逃逸边界

- 匿名类型是 internal 的程序集本地类：同形匿名类型跨程序集是**两个类型**——跨程序集传（返回值、契约、序列化）直接断裂；LINQ 投影内部是它唯一安全区，出方法边界换命名类型/record。
- ValueTuple 是结构 + 结构相等 + 元素名擦除：`(X: 1, Y: 2)` 的名字只活在编译期（TupleElementNames 元数据），反射/序列化看到 Item1/Item2——公共 API 返回命名元组 = 把字段名契约甩给调用方记忆。

```csharp
var a = new { Name = "x", Qty = 1 };
// 同程序集内 a.Equals(new { Name = "x", Qty = 1 }) == true(结构相等)
var k = (Region: "eu", Shard: 3);
var k2 = (region: "eu", shard: 3);   // 名字不同,同一类型:k.Equals(k2)
dict[k] = payload;                   // 结构哈希键:便宜且正确(元素不可变时)
```

- 复合键场景：ValueTuple 结构相等 + IComparable 天然适合作字典/排序键——比匿名类型（class + 跨边界断裂）适合内部管道；前提是元素不可变（62 节改键失联）。
- 陷阱：8 元组起嵌套 Rest——相等、ToString、序列化在嵌套层的行为都偏离直觉；宽行数据老实用 record。
- 陷阱：匿名类型进 JSON——属性名即成员名的隐式契约，重构改名直接破坏线上载荷（80 节）；对外 JSON 一律显式 DTO。
- 陷阱：ValueTuple 是可变 struct——做键前拷贝，或直接 readonly record struct 锁死（55 节）。

## 68. 枚举深水：Flags 契约与类型安全

- enum 是"带名字的整数"不是封闭集合：`(Access)0b1001` 永远合法——边界入口（Parse/JSON/DB）都要 IsDefined 或位掩码校验兜底；.NET 7+ 的泛型 `Enum.IsDefined<TEnum>(v)` 走免装箱快路径。
- Flags 契约：None=0、成员占独立位、常用组合显式命名（`ReadWrite = Read | Write`）；"合法组合"没有内建校验——`(v & ~All) == 0` 掩码检查自己写。

```csharp
[Flags] public enum Access
{ None = 0, Read = 1, Write = 2, Exec = 4, All = Read | Write | Exec }

static bool Valid(Access v) => (v & ~Access.All) == Access.None;
Enum.TryParse<Access>("Read, Exec", out var a);   // Flags 名字串可解析
// HasFlag 自 .NET Core 2.1 是 JIT 内联原语——老文档的装箱警告已过期
```

- 陷阱：给已发布 enum 追加成员——二进制兼容但消费方"穷尽" switch 语义破坏（61 节兜底炸）；对外 enum 演进按破坏性变更评审。
- 枚举做字典键：.NET 5+ EqualityComparer<TEnum>.Default 走特化比较器（免反射装箱）；object 键老路径仍装箱（37 节）。
- 陷阱：enum 序列化默认写数字——未定义组合变成裸数字进日志/契约；配 [JsonStringEnumConverter] 换可读性，但字符串模式下未定义组合仍落数字，两端策略显式约定（80 节）。
- 热点转换：`Unsafe.As<TEnum, int>(ref v)` 零指令重解释（底层宽度一致才准）；枚举 helper 约束 `where TEnum : struct, Enum` 管住泛型面。

## 69. DI 生命周期纪律与 captive 依赖

- captive 三形态：①单例构造注入 scoped（DbContext）——并发损坏 + 语义随首个请求固化；②长服务吃 transient IDisposable——一次性依赖被永久持有；③把 HttpContext/User 等请求态进闭包或缓存——跨请求串数据。共同点：编译期全过，运行期慢性病。
- 验证器现实：ValidateScopes 拦"scoped 从根解析"、ValidateOnBuild 启动期建全调用图——**但工厂 lambda 对两者都是黑盒**：`AddSingleton(sp => new S(sp.GetRequiredService<Db>()))` 里的 captive 全看不见。

```csharp
public sealed class ReportSvc(IServiceScopeFactory scopes)
{
    public async Task<Report> BuildAsync(Guid id, CancellationToken ct)
    {
        await using var scope = scopes.CreateAsyncScope();    // .NET 6+
        var db = scope.ServiceProvider.GetRequiredService<AppDb>();
        return await db.Reports.SingleAsync(r => r.Id == id, ct);
    }
}
```

- 修复纪律：单例要 scoped 数据一律 IServiceScopeFactory + 每操作 CreateAsyncScope + await using——把错误生命周期显式转成显式租约；直接注入 DbContext 的单例 PR 一票否。
- 陷阱：每次操作开 scope 有真实成本——高频路径合并到批处理边界，或该服务本身改 scoped（评审时问"租约粒度"）。
- 陷阱：漏 await using = 泄漏 scope 在容器根上累积，gcdump 形态是"DbContext 只增不减"（47、111 节）。
- 预防线：staging 用生产同构组合开 ValidateScopes + ValidateOnBuild（111 节）；架构测试（NetArchTest）断言"DbContext 不得注入单例"进 CI。

## 70. keyed services 与多实现策略

- .NET 8 keyed 家族：AddKeyedSingleton/Scoped/Transient + [FromKeyedServices("name")] 注入 + [ServiceKey] 拿自己被解析时的键——把"命名实现/多租户"从手写字典工厂收编进 DI 图（可验证、可组合）。
- 键语义：object 键按相等匹配；KeyedService.AnyKeyKey 注册 = 通配兜底；**keyed 解析不回落非 keyed 注册**，GetServices<T>() 也不含 keyed——两套注册互不可见，静默空集合是第一事故。

```csharp
builder.Services.AddKeyedSingleton<IGateway, StripeGw>("stripe");
builder.Services.AddKeyedSingleton<IGateway, FallbackGw>(KeyedService.AnyKeyKey);

public class Checkout([FromKeyedServices("stripe")] IGateway gw)
{ /* 同键注入同一实例(singleton);键字符串散落 = 配置腐化起点 */ }

var gw = sp.GetRequiredKeyedService<IGateway>(tenantRegion);  // 运行期键
```

- 选型线：实现集合**封闭已知**（支付渠道、固定区域）→ keyed（声明式、图可见）；**开放/条件化**（按配置加权、灰度）→ 工厂策略注册——工厂逻辑显式但 DI 图失明，用一条集成测试补偿。
- 陷阱：[ServiceKey] 参数使该服务只能被 keyed 解析——想 keyed/非 keyed 双用要拆注册或上 AnyKey。
- 陷阱：枚举/Type 做键比字符串安全（编译期可查、无拼写漂移）；字符串键集中 const 管理。
- 动态键（租户 ID）必须 GetRequiredKeyedService 手动解析——注入端拿不到运行期信息，这条路径丢掉构造器验证，补集成测试。

## 71. IOptions 家族选型与配置热更

- 三件套分工一句话定生死：IOptions<T> 启动算一次永不热更（值快照）；IOptionsSnapshot<T> 每 scope 重算（scoped 生命周期，进单例即 captive）；IOptionsMonitor<T> 单例 + CurrentValue + OnChange 热更——"谁消费"决定"用哪个"。
- 热更纪律：读值点直接 `monitor.CurrentValue`；启动时拷贝进 readonly 字段 = 热更死掉（108 节）；OnChange 里只做失效标记/原子换引用，重活交给消费点。

```csharp
builder.Services.AddOptions<RateOpts>().Bind(config.GetSection("Rates"))
    .ValidateDataAnnotations().ValidateOnStart();   // 启动期 fail-fast
public sealed class RateCache(IOptionsMonitor<RateOpts> mo) : IDisposable
{
    private volatile RateOpts _cur = mo.CurrentValue;
    private readonly IDisposable _sub = mo.OnChange(v => _cur = v);
    public void Dispose() => _sub.Dispose();        // OnChange 订阅必须释放!
}
// .NET 8 起配置绑定源生成:partial Options 类 + 编译期绑定,反射退役(AOT)
```

- 陷阱：OnChange 可能连发多次（配置系统不保证去抖）——回调幂等；返回的 IDisposable 不释放 = 泄漏 + 陈旧回调堆积。
- 陷阱：Snapshot 每 scope 重算绑定有真实成本——高频 scoped 服务反复 Get 也在重算；热数据用 Monitor + 缓存（如上），Snapshot 只给"每请求新值可接受"的场景。
- 验证三层最低配：ValidateDataAnnotations（声明式）+ IValidateOptions<T>（跨字段规则）+ ValidateOnStart（启动炸而不是首请求炸）——96 节配置漂移门禁的地基。
- named options（Get("Stripe")）解决同型多份；.NET 8 后它与 keyed services 是两套宇宙——选一套统一，别混管。

## 72. DI 性能内幕：CallSite 与解析成本

- 解析内幕：首次解析构建 CallSite 树（循环依赖在此检出并抛）并编译为解析器；之后单例走根缓存 O(1)、瞬时每次执行委托链新建整棵对象图——**成本 ∝ 构造参数数 × 图深度**，不是"字典查一下"。
- 瞬时链放大：A(transient)→B(transient)→C(transient)，每解析 A 新建 3 对象 + 各自构造副作用；"事实单例"注册成 transient 是隐形税——生命周期按消费真相写，不按"安全默认"写。

```csharp
// 慢路径:ActivatorUtilities 每次反射匹配,热路径禁区
var h = ActivatorUtilities.CreateInstance<Handler>(sp, extra);
// 快路径:显式工厂 + GetRequiredService(调用图可见,ValidateOnBuild 不瞎)
services.AddTransient<Handler>(
    sp => new Handler(sp.GetRequiredService<Parser>(), extra));
```

- 陷阱：解析 IEnumerable<T> 每次物化新数组 + 逐个瞬时实例——热路径"遍历所有 handler"要么缓存数组（注册面静态），要么把多实现显式建模进组合根。
- 开放泛型 `Add(typeof(IRepo<>), typeof(Repo<>))`：每个封闭构造首次解析建独立 CallSite 并缓存——封闭构造种类爆炸时 ValidateOnBuild 的启动成本上涨，要度量。
- 陷阱：`using var scope = sp.CreateScope()` 出现在逐项循环里——scope 创建/释放按批合并；批量处理边界外提。
- 诊断：Host 启动耗时分段（ValidateOnBuild 开销单列）；运行期无 DI 计数器，靠解析热点打点采样定位"解析风暴"。

## 73. HttpClientFactory 深水与套接字治理

- 两层治理分清：factory 管 **handler 池与旋转**（默认 HandlerLifetime 2 分钟，到期换新池）；SocketsHttpHandler 管**连接池语义**（PooledConnectionLifetime = 单连接寿命上限 = DNS 刷新节拍）。现代基线：HandlerLifetime 拉长，DNS 时效交给 PooledConnectionLifetime——"短 HandlerLifetime 解决 DNS"是旧姿势（频繁重建连接池反伤吞吐）。
- 命名 vs 类型化：类型化客户端 = 编译期类型安全 + 可注入依赖，推荐默认；命名客户端字符串键易腐化；裸 HttpClient 单例（DNS 陈旧）/每请求 new（套接字耗尽）是两大反模式。

```csharp
builder.Services.AddHttpClient<PayClient>(c => c.BaseAddress = new Uri(cfg.Base))
    .ConfigurePrimaryHttpMessageHandler(() => new SocketsHttpHandler
    {
        PooledConnectionLifetime = TimeSpan.FromMinutes(5),   // DNS 节拍
        PooledConnectionIdleTimeout = TimeSpan.FromMinutes(2)
    })
    .SetHandlerLifetime(TimeSpan.FromHours(1))               // 旋转退居二线
    .AddResilienceHandler("std", h => h.AddRetry(retryOpts)); // 官方弹性管道
```

- 陷阱：类型化客户端注册成单例 = captive（handler 永不换 + 拖进 scoped）——类型化客户端按 scoped/transient 用，贵的在 handler 不在客户端壳。
- 陷阱：factory 出的 HttpClient Dispose 安全（handler 归 factory 管）但每请求 using 毫无收益——客户端当长寿命外观。
- 套接字取证：TIME_WAIT 计数（`ss -s`）与 ActiveHandlerCount/旋转节奏对表；连接池打满时请求排队在"等连接"，症状像下游慢——SocketsHttpHandler 的超时与连接上限要显式预算。
- 弹性标准件：Microsoft.Extensions.Http.Resilience（Polly v8 管道：重试/熔断/超时分级）替代手搓 wrapper——手写重试层是重复造轮 + 配置散落（108 节）。

## 74. IDisposable 服务处置与 Scope 验证

- 追踪边界：容器只追踪"它解析出的"实例——根容器追踪单例 + 从根解析的 transient 可释放物（活到容器 Dispose！）；**工厂 lambda 里 new 出来的嵌套可释放资源不被追踪**，这是最大泄漏缺口。

```csharp
services.AddSingleton<Cache>(sp =>
{
    var inner = new Connection();               // 不追踪:容器不知道它存在
    return new Cache(inner, sp.GetRequiredService<ILogger<Cache>>());
});
// 正解:inner 走容器注册,或 Cache 显式承担 inner 的生命周期契约

await using var scope = scopes.CreateAsyncScope();   // 异步处置从入口贯通
```

- 陷阱：transient IDisposable 从**根**解析（单例里顺手 GetRequiredService 一个 transient 服务）→ 挂在根上直到进程退出——scope 验证不查这个；纪律：transient IDisposable 只在 scope 内解析。
- 同步 Dispose 撞上"仅实现 IAsyncDisposable"的服务会抛 InvalidOperationException——手动 scope 一律 CreateAsyncScope + await using，与 Host 请求管道行为对齐。
- 单例的处置时机 = 根容器 Dispose = Host 关停末段——要"更早释放"的资源（连接、缓存落盘）自己实现 IHostedService.StopAsync 管，别指望容器。
- 关停期日志陷阱：后台服务停机时 logger provider 可能已随根容器释放——关停关键日志用独立最简 sink（本地文件、同步写）。
- 验证矩阵：dev + staging 开 ValidateScopes + ValidateOnBuild；生产至少 ValidateOnBuild（启动成本换 fail-fast，111 节）。

## 75. System.Text.Json 源生成实战

- 基本形态：partial JsonSerializerContext + [JsonSerializable] 逐类型注册，调用时把 `Context.Default`（或其 Options）传进 Serialize/Deserialize——**不传 context 的调用走反射默认**，源生成完全没参与（性能与 AOT 双输），这是"我开了源生成却没用上"的头号事故。

```csharp
[JsonSourceGenerationOptions(PropertyNamingPolicy = JsonKnownNamingPolicy.CamelCase)]
[JsonSerializable(typeof(Order)), JsonSerializable(typeof(Order[]))]
public partial class AppJson : JsonSerializerContext { }

string json = JsonSerializer.Serialize(order, AppJson.Default);   // 生成路径
Order o = JsonSerializer.Deserialize<Order>(json, AppJson.Default)!;
// JsonSerializer.Serialize(order) —— 反射默认,与 AppJson 毫无关系
```

- 依赖闭包：可达类型必须全部注册——生成器对未注册引用类型给编译诊断（SYSLIB10xx 家族），NoWarn 消掉 = 把 AOT 死亡推迟到运行期。
- Options 冻结：context 的 Options 首次使用后不可变——要不同配置就声明多个 context（各自 [JsonSourceGenerationOptions]）；运行期往 Default.Options 塞 converter 抛异常。
- 可见性：[JsonInclude] 让非 public 成员进契约；internal 类型可注册（同程序集可见）；跨程序集 DTO 保持 public——生成器不做跨边界魔法。
- .NET 8+ 的 TypeInfoResolver 支持链式组合（默认 context + 自定义 context 串联）——多模块契约拼装用它，不复制 options。
- 与 76 节分工：注册面决定模式面——能 fast-path 的自动 fast-path，多态/converter 支配的类型回落 metadata。

## 76. fast-path 与 metadata 模式的性能分野

- 两模式生成物不同：fast-path（Serialization 模式）生成强类型 Write/Read 方法（writer 调用直排、数字/日期直写）；metadata 模式生成 JsonTypeInfo<T> 走通用引擎。fast-path 显著更快、分配更低——但覆盖面有限。
- fast-path 硬限制（官方文档口径）：**异步序列化 API 不走 fast-path**；多态（JsonDerivedType）、自定义 converter 主导、引用环（Preserve）等情形回落 metadata——设计热路径"同步 + 单态"才吃满 fast-path。

```csharp
[JsonSourceGenerationMode(JsonSourceGenerationMode.Serialization)]
[JsonSerializable(typeof(TelemetryPoint))]      // 纯 fast-path context
public partial class FastCtx : JsonSerializerContext { }

// Metadata 模式:JsonTypeInfo<T> + 通用引擎,支持多态/converter/引用环
// Default(两者兼备)是常规选择:能 fast-path 的类型自动受益
```

- 选型实操：纯写热路径（遥测打点、缓存写）→ Serialization 模式 + 同步 API；读写都要 + 多态 → Default；全 converter 生态 → Metadata。
- 陷阱："用了源生成就快"是错觉——回落 metadata 的类型 + 异步调用面组合下收益只剩 AOT 安全；性能敏感路径用 [MemoryDiagnoser] 实测（41 节），别按文档直觉记账。
- 陷阱：自定义 converter/自定义数字与日期写策略会把类型踢回 metadata——契约策略与性能策略在类型粒度折衷，注册面评审时一起看。
- 验证手法：EmitCompilerGeneratedFiles 看生成代码（Utf8JsonWriter 调用直排 = fast-path 证据）；与 MetadataOnly 版本跑对比基准量化差距。

## 77. JsonDerivedType 多态与鉴别器安全

- 多态三要素：基类标 [JsonDerivedType(typeof(X), "tag")]（鉴别器名可换 TypeDiscriminatorPropertyName）；序列化按运行时类型写鉴别器；反序列化按鉴别器选派生——未注册派生序列化直接抛，契约面以注册表为准。
- 未知鉴别器策略：默认抛 JsonException；UnknownDerivedTypeHandling.FallBackToBaseType 把"未来新增派生"降级读成基类——旧读者读新消息流的正规开关（80 节）；FallBackToNearestAncestor 按层级就近回退。

```csharp
[JsonPolymorphic(TypeDiscriminatorPropertyName = "$kind",
                 IgnoreUnrecognizedTypeDiscriminators = false)]  // 拒绝要显式
[JsonDerivedType(typeof(Circle), "circle")]
[JsonDerivedType(typeof(Square), "square")]
public abstract record Shape;
// 收到未注册 "triangle" → 默认 JsonException:静默降级必须显式选择
```

- 安全红线：**鉴别器必须是数据（短字符串/整数）**——任何"拿类型全名当鉴别器"的自定义实现都是 Newtonsoft TypeNameHandling 的 RCE 复刻（反序列化面 = 任意类型实例化攻击面）；STJ 刻意不支持类型名多态，别用自定义 converter 绕。
- 陷阱：多态与 required/init 组合——派生层成员缺失在派生层抛（57 节），错误消息类型名是派生不是基类，排障别看错层。
- 接口多态：.NET 9 起接口可直接标注 JsonDerivedType——契约建模少建一层抽象类。
- 陷阱：手写 converter 时鉴别器必须先写（读取器对"头部鉴别器"有优化路径），放尾部 = 全对象缓冲后才能分派。

## 78. 自定义 Converter 的 AOT 兼容

- 三条注册路径 AOT 语义不同：[JsonConverter] 标类型上（生成器可见，契约进 context）> options.Converters.Add（运行期注册，metadata 模式可用）> TypeInfoResolver 定制（最灵活）。**converter 一支配类型，fast-path 让位 metadata**（76 节）——性能预算先算这笔账。
- 工厂转换器（JsonConverterFactory）AOT 死角：运行期 MakeGenericType 造闭式 converter 的老写法在 NativeAOT 全断——重写为"每个闭式类型显式注册"或无反射泛型单类。

```csharp
public sealed class FlexConverter : JsonConverter<Flex>   // 契约式:不摸反射
{
    public override Flex? Read(ref Utf8JsonReader r, Type t, JsonSerializerOptions o) {
        if (r.TokenType != JsonTokenType.StartObject) throw new JsonException("expected object");
        // 逐 token 解析;不嵌套 JsonSerializer.Deserialize(选项错配+再入开销)
    }
    public override void Write(Utf8JsonWriter w, Flex v, JsonSerializerOptions o)
        => w.WriteStringValue(v.ToWire());               // 直写原语,零子管线
}
```

- 陷阱：converter 里再调 JsonSerializer 传错 options——嵌套调用透传同一个 options，别 new 默认的（命名策略/converter 集合瞬间分裂）。
- 陷阱：反射式 converter（按属性名打表）必须 [RequiresUnreferencedCode] 明示并接受 AOT 裁剪命运——否则 MissingMethodException 在生产才爆（10 节流程）。
- 设计契约：converter 只做形态翻译不做业务校验；异常一律 JsonException（读端看到行/字节位置），抛业务异常会被包装且丢上下文。
- 测试矩阵：converter × {AOT 冒烟、裁剪发布、fast-path/metadata}——最容易"单测全绿、AOT 全黑"的层。

## 79. Newtonsoft 到 STJ 迁移差异清单

- 迁移不是换包是换契约——六个默认行为直接翻转：①STJ 属性匹配默认**区分大小写**；②尾逗号/注释默认拒绝；③NaN/Infinity 默认拒绝（JsonNumberHandling.AllowNamedFloatingPointLiterals 开）；④字典非 string 键要策略（枚举键默认数字）；⑤私有成员默认不序列化；⑥构造器绑定规则不同。

```csharp
var json = new JsonSerializerOptions(JsonSerializerDefaults.Web)
{   // Web defaults:camelCase + 大小写不敏感 + 尾逗号宽容 = 迁移缓冲带
    NumberHandling = JsonNumberHandling.AllowNamedFloatingPointLiterals,
    ReferenceHandler = ReferenceHandler.Preserve,   // 循环引用替代方案
    DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull
};
// 注意:Preserve 写出 $id/$ref —— 消费方不是 STJ 时是破坏性载荷变化
```

- 缓冲带策略：迁移期 JsonSerializerDefaults.Web 近似 Newtonsoft 体验——但它是缓冲带不是终点，目标态是显式命名策略 + 严格匹配。
- 构造器语义差异：Newtonsoft"公共构造器 + 剩余属性回填"（宽松）；STJ 参数名匹配属性名（大小写不敏感）——同名双构造器行为分叉是迁移期最阴的坑（57 节 [JsonConstructor] 钉死）。
- 陷阱：Newtonsoft 的 DefaultSettings 全局默认在 STJ **没有对应物**——options 必须显式穿层（DI 注入）；散落的 new JsonSerializerOptions() 是配置漂移源（108 节）。
- DateTime 语义：Newtonsoft 做时区转换（DateTimeZoneHandling），STJ 严格 ISO-8601 不隐式转换——时间戳边界在影子期必测。
- 迁移工法：双写双读影子期（新旧 options 各跑 + 差异日志）→ golden 文件契约锁定（98 节）→ 切换；禁止全库替换 + 祈祷。

## 80. 序列化契约版本化与 tolerant reader

- 演进操作分级：加**可选**成员（新字段容忍旧数据）= 绿；加 required = 破坏老生产方（57 节）；改名/改类型 = 红线——走"新字段并存 + 旧字段只读兼容一个弃用窗口"再删。
- tolerant reader 纪律：读端未知成员默认跳过（保持）；缺成员走默认值 + 校验层报结构化错误（错误码比 JsonException 可运营）——**不在反序列化层抛"缺字段"**。

```csharp
public sealed class Envelope
{
    public int SchemaVersion { get; init; } = 2;   // 显式版本钉子
    public string? Name { get; init; }
    public string? LegacyName { get; init; }       // v1 兼容读窗口,弃用期内双读
}
// dev/CI 严格模式抓漂移,prod 宽容:
var strict = new JsonSerializerOptions
{ UnmappedMemberHandling = JsonUnmappedMemberHandling.Disallow };
```

- 陷阱：整数宽度演进（int→long）对旧读者是 JsonException——契约从第一天用宽类型或字符串载荷；double→decimal 是语义翻转不是升级。
- 陷阱：enum 加值——读端"未知枚举"策略（默认分支 or 拒绝）写进契约文档；字符串枚举的可读性红利要用"成员永不改名"纪律换（79 节）。
- 多态版本链：鉴别器注册表跨版本只增不删 + FallBackToBaseType 兜未来派生（77 节）——"基类读法永远可用"是多态消息流的向前兼容底座。
- 门禁工法：golden 文件（98 节）+ 严格模式的构建味在 CI 回放全量历史样例——契约漂移在合入前爆，不在消费方工单里爆。

## 81. 异常成本模型与 throw helper

- 成本解剖：一次 throw+catch 量级在微秒上下（远高于分支/分配），贵在三处——栈展开、异常对象构造（消息格式化）、堆栈捕获；深栈 + 消息拼接再放大一个量级。异常是"每次故障付费"模型，触发频率就是预算表。
- 落地结论：预期失败路径（校验不过、not-found、解析失败）用 TryX/Result 语义（84 节）；异常只留给"设计外真故障"——控制流用异常是热路径税，不是风格问题。

```csharp
internal static class Throw
{
    [DoesNotReturn] public static void ArgumentNull(string name)
        => throw new ArgumentNullException(name);
    [DoesNotReturn] public static void InvalidState(string why)
        => throw new InvalidOperationException($"invalid state: {why}");
}
void Frob(Frob? f) => (f ?? Throw.ArgumentNull(nameof(f))).Run();
// DoesNotReturn 教会可空分析器"此后必抛":调用点零警告;小方法被 JIT 内联
```

- ThrowHelper 纪律：方法体一行 throw + [DoesNotReturn]；消息延迟到抛点才拼——不抛的路径也别构造诊断串（`throw new Ex(BuildDiag())` 的 BuildDiag 每次进方法都跑）。
- 陷阱：异常做循环退出（"解析到非法字符就抛"）——脏数据把 CPU 烧在栈展开上；先扫后解析（SearchValues/IndexOfAny 定位，34 节）。
- 陷阱：主动摸 ex.StackTrace/Environment.StackTrace 极贵——.NET 堆栈捕获本就惰性，诊断聚合别在每层 catch 重复抓（85 节）。
- 陷阱：`catch (Exception) { }` + 高频触发 = 白付完整异常成本还吞线索——82/83 节重抛与解包纪律。

## 82. 异常过滤器 when 与重抛语义

- 核心机制：`catch (E ex) when (cond)` 的过滤器在**栈展开之前**执行——原栈帧完好；过滤器里日志/埋点看到真实故障现场，返回 false 继续找下一个 catch——"零成本观察点"（不吞、不展开、不重抛）。
- 重抛三分法：`throw;` 保原始栈（原始抛点在）；`throw ex;` 清栈重置（现场销毁，禁止）；EDI 跨时间/线程/队列保栈重抛（85 节）。

```csharp
try { await PublishAsync(evt, ct); }
catch (Exception ex) when (LogAndRethrow(ex)) { }     // 过滤器日志:栈完整
catch (OperationCanceledException) when (ct.IsCancellationRequested) { }
catch (OperationCanceledException)
    => throw new TimeoutException("coincidental OCE");// 他人路径漏出的 OCE

static bool LogAndRethrow(Exception ex) { Log.Error(ex, "publish failed"); return false; }
```

- OCE 分流惯用法（第二 catch）：区分"真取消"（带的就是我们的 token）与"别处漏出的 OCE"——18 节取消语义在 catch 层的收口；吞错 OCE 会把超时伪装成成功。
- 陷阱：过滤器里抛异常 = 按 false 处理且**异常被吞**——没人知道发生过；过滤器保持纯谓词，复杂判断进 catch 体。
- 陷阱：类型匹配先于过滤器按序走——宽类型 + 恒真过滤器会截胡后面的窄分支；排序按"最具体优先"。
- 性能事实：过滤器失败不引发栈展开，比 catch { throw; } 便宜一档——审计日志挂在 when 上是白赚。

## 83. AggregateException 解包纪律

- 三原语分工：Flatten() 拍平嵌套聚合（task 套 WhenAll 套异步委托的俄罗斯套娃）；Handle(predicate) 标记已处理、未处理集合重新聚合抛出——批量错误分类的唯一安全口；Unwrap() 专治 Task 包装的单一内层。
- 与 WhenAll 配合（17 节）：await 只抛第一个，其余全在 task.Exception.InnerExceptions——收割逻辑封装成统一扩展，别在每个调用点手写。

```csharp
public static async Task CollectAsync(this IEnumerable<Task> tasks)
{
    var all = Task.WhenAll(tasks);
    try { await all; } catch
    {
        var rest = all.Exception?.InnerExceptions ?? [];
        if (rest.Count > 0 && rest.All(e => e is OperationCanceledException)) throw; // 全取消
        throw new BatchException([.. rest.Where(e => e is not OperationCanceledException)]);
    }
}
```

- 陷阱：`catch (AggregateException)` 挂在 await 调用上是死代码——await 从不抛聚合；同步阻塞 API（Wait/Result，15 节）才抛——两种世界的 catch 面分开写。
- 陷阱：Handle 的 predicate 里再抛异常 = 处理标记语义全乱——predicate 只返回 bool 且无副作用；Flatten 后又见嵌套聚合 = 上游有 Wait/Result 边界（15 节分析器该拦），顺手补工单。
- 陷阱：聚合层不分类 OCE 就重抛——"取消兄弟"被记成故障，SLO 污染（17、86 节）；取消/失败二分在聚合层完成。

## 84. Result 模式的适用边界与反噬

- 适用线只有一道题：这个失败是"业务分支"（高频、预期、调用方必须处理）还是"需要栈与告警的故障"？前者 Result<T,E>（校验、解析、not-found、限流拒绝），后者永远异常（IO 死、不变量破坏、bug）。
- 反噬三症状：①Result 感染全库——每层签名跟着变，Map/Bind/Match 组合子膨胀；②E 类型泛滥——错误联盟比领域模型还大；③遥测退化——栈没了、位置丢了，错误只剩一个字符串，排障效率倒退。

```csharp
public readonly record struct ParseResult<T>(T? Value, string? Error)
{
    public bool IsOk => Error is null;
    public static ParseResult<T> Ok(T v) => new(v, null);
    public static ParseResult<T> Fail(string e) => new(default, e);
}
// 反模式:catch (Exception ex) { return ParseResult<T>.Fail(ex.Message); }
// —— 把栈与告警贱卖成一个字符串;预期失败才配 Result,不是全部异常
```

- 反模式清单：程序员错误 Result 化（null 解引用被洗白成 Error 返回 = bug 消失）；Task<Result<T>> 里再嵌 Result 交换（组合双倍糖）；跨服务边界把 Result 类型直接序列化（错误码契约化是另一回事）。
- 折衷工法：模块边界层 Result（公开 API 签名）+ 内层异常直达 + 边界统一 catch 转 Result 且**保住证据**（错误对象带调用点/上下文进日志，81 节成本模型别丢）。
- C# 现实：没有 do-notation——组合子链超三层可读性崩，退回早返回 + 局部函数；Result 是工具不是架构。

## 85. ExceptionDispatchInfo 与跨边界重抛

- EDI = "把异常连同完整栈封装成可搬运对象"：Capture(ex) 定格原始栈，Throw() 在任何时间/线程/上下文重抛且栈从原始抛点连续——队列、定时器、后台 worker、fire-and-forget 包装的保栈标准件（107 节的地基）。
- 已内建的地方别重复造：Task 基础设施内部就是 EDI 语义——await faulted task 抛的异常带原始栈；只有手写 `catch (Exception ex) { q.Enqueue(ex); }` + 消费端裸 throw 的搬运路径才需要显式 EDI。

```csharp
ExceptionDispatchInfo? pending = null;
try { await RiskyAsync(ct); }
catch (Exception ex) { pending = ExceptionDispatchInfo.Capture(ex); }   // 栈定格
if (pending is not null)
{
    Log.Debug("replaying captured failure");
    pending.Throw();        // 此处抛出:栈从原始抛点连续到消费现场
}
```

- 陷阱：EDI 用于重试时栈会逐轮追加帧（捕获-重抛-再捕获）——重试包装里看到"爆炸栈"属正常，别当成新故障升级。
- FirstChanceException 纪律：诊断订阅对**每一个**异常（含将被吞的、过滤器里的）都触发——高频异常 + 里面做 IO = 雪崩 + 递归日志死锁；只做低频采样与计数。
- 陷阱：`throw ex;` 在"已捕获又想保栈"的场景永远错误——EDI.Throw 是唯一正解；CI grep `throw ex` 应为零。
- 跨进程不保：EDI 只在进程内有效——跨进程搬运（崩溃 dump、sidecar）走结构化错误序列化 + 崩溃报告（86 节）。

## 86. 进程级兜底：UnobservedTaskException 与崩溃策略

- 真实语义：faulted task 无人观察（无 await/Wait/没摸过 Exception）且被 GC 回收时触发 UnobservedTaskException——**GC 定时、可能永不发生**；它是最后的遥测线不是处理机制，e.SetObserved() 只做抑制。
- .NET 4.5 起未观察任务异常默认不再击杀进程——"没处理的 Task 异常会崩进程"是老 Framework 记忆，别拿它当设计依据。

```csharp
TaskScheduler.UnobservedTaskException += (s, e) =>
{
    Metrics.UnobservedFault(e.Exception);   // 只做遥测:计数+采样日志
    e.SetObserved();                        // 不做业务恢复——恢复位置根本不存在
};
AppDomain.CurrentDomain.UnhandledException += (s, e) =>
{
    CrashLog.SyncWrite(e.ExceptionObject);  // 同步最小路径:本地文件,无网络无锁
};
```

- 崩溃策略二分：**可恢复故障**（单请求失败）→ 观察并记录（SafeFireAndForget 107 节）+ 请求级隔离；**进程级腐坏**（状态机破损、OOM、关键单例死）→ fail-fast——吞进程级异常换 uptime 是把确定性事故换成不可解释事故。
- 崩溃取证配置先行：Linux 上 `DOTNET_DbgEnableMiniDump=1`（配 DOTNET_DbgMiniDumpType）让运行时在崩溃点自产 dump；容器接 core_pattern 落盘卷——没 dump 的服务等于复盘裸奔（109-111 节全依赖它）。
- 陷阱：UnobservedTaskException 里做"自动重试"——触发时机在 GC，与故障现场隔了一次回收，重试语义与副作用全是猜的。
- 关停期噪声：取消风暴（预期 OCE）在停机时冲进 Unobserved——统计把停机窗口单独打标，SLO 与告警剔出去（18 节）。

## 87. 线程池饥饿：注入速率与诊断

- 注入节律：饥饿发生后池按节流策略补线程——老版本量级每秒个位数（雪崩成型要几十秒），.NET 6+ 饥饿检测改善（队列尖峰触发突发注入），但前提是工作项纯 CPU；阻塞 IO 把注入逻辑的"快速回填"假设打穿（21 节）。
- 指标三联征定性：ThreadPool Queue Length 持续 >0 攀升 + CPU 低 + 延迟雪崩而 GC 正常 = 饥饿实锤；对照下游指标（同涨 = 下游源头，不涨 = 自己阻塞）。

```bash
dotnet-counters monitor -p $PID System.Runtime
# 盯:threadpool-queue-length / threadpool-thread-count / cpu-usage
dotnet-stack report -p $PID
# 栈快照:数百线程聚集在同步 DB 读 / Task.Wait / 同步 HTTP 客户端 → 点名凶手
```

- dotnet-stack 连抓三份对比（瞬时抖动 vs 持续饥饿）：线程栈聚集模式是唯一一锤定音证据——比"重启试试"和"加机器"便宜一个数量级。
- 隐形阻塞源清单（审计按类扫代码）：同步 DB/HTTP 客户端、锁内长 IO、Task.Run 包同步库（21 节）、SemaphoreSlim.Wait 而非 WaitAsync（20 节）、限流器同步等待——四类占真实事故的九成。
- 止血顺序：SetMinThreads 临时抬底（写明 24h 拆除）→ 入口限流挡新流量放存量消化 → 替换阻塞调用为真异步（15、109 节）→ 压测复验。
- 陷阱：SetMinThreads 留进配置永久生效——预分配百线程的内存与调度代价在低负载白付；止血带必须有拆除条件。
- 陷阱：饥饿引发超时，重试层再放大并发——重试风暴与饥饿互为正反馈（108 节）；饥饿期熔断优先于重试。

## 88. Parallel.ForEachAsync 与并发上限

- 定位：.NET 6+ 的大规模**异步**扇出件——MaxDegreeOfParallelism 限制"同时在飞"的异步操作数（默认 ProcessorCount），awaited body 形成天然背压；与同步 Parallel.ForEach（CPU 分区模型）不是一个物种。
- 与 WhenAll 分界：WhenAll 无并发上限（全量在飞，内存与下游连接数双爆）；ForEachAsync 有界在飞——N 万任务流式处理、内存恒定、下游并发受控。批量大/体量大 → ForEachAsync；小固定集 → WhenAll。

```csharp
await Parallel.ForEachAsync(urls,
    new ParallelOptions { MaxDegreeOfParallelism = 20, CancellationToken = ct },
    async (url, itemCt) =>
    {
        await client.GetStringAsync(url, itemCt);  // body 尊重 ct:取消是协作式
        // 陷阱:这里 await 才计入 DOP;同步阻塞会占满全部 worker
    });
```

- 陷阱：body 里同步阻塞（同步 IO/长锁段）——worker 占满后退化为串行 + 队列积压；body 必须 await 到底（27 节纪律）。
- 聚合没有 local state：不像同步版有 localInit/localFinally——聚合走 per-任务闭包 + ConcurrentQueue 收集，或 Channel 分区聚合（93 节）。
- 枚举端瓶颈：源 IEnumerable 的 MoveNext 在拉取线程执行——enumerator 里有同步 IO（逐页读库）就把扇出卡在生产端；生产端全缓冲或改 Channel 解耦。
- 顺序需求：完成乱序是常态；要"结果有序"用原下标回填结果数组，而不是降并发保序。

## 89. lock 深水：Monitor 线程亲和与锁对象选择

- Monitor 三性质：线程亲和（Enter/Exit 必须同线程，.NET 9 的 System.Threading.Lock 依旧亲和）、可重入（递归计数）、不可跨 await（20 节）——换线程释放抛 SynchronizationLockException，想"异步释放"就是选错原语。
- 锁对象四禁令：lock(this)（外部能锁同实例 = 主动邀请死锁）、lock(typeof(T))（装载器锁交互 + 进程级共享）、lock(字符串字面量)（驻留共享）、lock(值类型)（编译错误——装箱后每次锁不同对象）。唯一合法形态：`private readonly object _gate = new();`

```csharp
public sealed class Store
{
    private readonly object _gate = new();          // 私有、只读、专用
    private readonly Dictionary<K, V> _map = new();
    public V GetOrLoad(K key) {
        lock (_gate) { if (_map.TryGetValue(key, out var v)) return v; }
        var loaded = LoadSlow(key);                  // 重活放锁外:锁段最小化
        lock (_gate) { _map[key] = loaded; return loaded; }
    }
}
```

- 陷阱：可重入掩盖设计错——"被锁调用的方法里再锁"不炸但把不变量依赖变成隐式假设；显式锁层级文档化 + 评审锁内调用清单。
- 竞争观测：dotnet-counters 的 Monitor Lock Contention Count 持续上涨 = 锁汇聚点；竞争激烈优先拆锁（分片/读写分离/换无锁结构），不是找"更快的锁"；TryEnter(timeout) 可当死锁探测器分层开关（dev 诊断异常、prod 按"假死比崩溃难处理"倒推），日志锚点必备。
- 读多写少替代序：ConcurrentDictionary 段锁 / Immutable+Volatile 换引用 / Frozen 快照（34 节）优先于 ReaderWriterLockSlim——RWLock 在现代 .NET 的收益面极窄。

## 90. Interlocked、伪共享与缓存行

- Interlocked 族是全栅栏原子（Add/Exchange/CompareExchange/And/Or）：原子性 + 顺序性双保证；陷阱在"以为普通 long++ 也原子"——++ 是读-改-写三步，64 位平台也要 Interlocked（91 节可见性）。
- 伪共享机制：两个逻辑无关的计数器落进同一缓存行（x86 64B、Apple Silicon 128B 预取对）——核间缓存行所有权乒乓，无锁代码慢 2-10 倍；"代码正确但基准诡异慢"先查这里。

```csharp
[StructLayout(LayoutKind.Explicit, Size = 128)]     // 每计数器独占一行
internal struct PaddedCounter { [FieldOffset(64)] public long Count; }
// 高竞争计数分片:per-slot 独立行,读时求和(写多读少场景的正解)
private readonly PaddedCounter[] _slots =
    new PaddedCounter[Environment.ProcessorCount];
```

- 检测：Linux `perf c2c` 直读缓存行争用，或 perf stat 看 HITM/LLC miss；.NET 内用多线程基准对比 padding 前后——有量级差即实锤。
- 陷阱：CompareExchange 做无锁发布——构造写入与发布之间需要正确栅栏；"双检"的读序错 = 半构造对象可见（91 节半栅栏语义）。
- 陷阱：结构体 long 字段可能未对齐——Interlocked 打未对齐字段抛或静默撕裂；计数器用类字段（自动对齐）或显式布局。
- 惰性初始化优先 Lazy<T>/LazyInitializer（内部正是 Interlocked + 正确栅栏）——手写 double-check 的意义只剩教学。

## 91. volatile 边界与 ECMA 内存模型

- 语义精确版：volatile 读 = acquire（后续访问不被提前）、volatile 写 = release（之前访问不被延后）——**半栅栏**；ECMA-335 是弱模型，.NET 运行时在 ARM 用 ldar/stlr 兜住真实指令序，x86 靠强内存模型"看起来更对"——跨架构验证别只看 x86。
- volatile 不是：不是原子（宽 struct/未对齐仍撕裂）、不是锁（`volatile x++` 仍竞态）、不是可见性万灵药（只保证被标记字段的序，不管同批其他字段）。

```csharp
private volatile bool _stopping;                    // 标志位:volatile 合法主场
public void Loop() { while (!_stopping) Process(); }
// Tier1 JIT 会把"非 volatile 循环读"提升出循环——不加 volatile,Release
// 下可能永远不退出;Debug/附加调试器掩盖问题,只在生产爆
private Config? _cfg;                               // 整图发布:volatile 单字段救不了
```

- 64 位撕裂窗口：32 位平台 long/double 赋值可撕裂；64 位平台对齐后单赋值原子但**复合操作永远不原子**——跨线程 64 位状态机一律 Interlocked（90 节）。
- 正解排序：标志位 → volatile；计数/引用发布 → Interlocked/Volatile.Write；不变量跨多字段 → lock；"我写了无锁队列" → 删掉换 Channel（24 节）。
- 陷阱：依赖 Thread.Start/Join 的隐式栅栏——换 Task.Run/池后行为漂移；跨线程发布显式用栅栏原语，不赌调度器。
- "Debug 好 Release 挂"的共享标志位九成是 volatile 缺失（38 节分层编译叠加提升优化）——复现纪律：Release + 无调试器独立进程。

## 92. ConcurrentDictionary 与 GetOrAdd 竞态

- GetOrAdd 竞态事实：工厂方法可能**多跑**（并发放行、只保一个结果）——工厂必须幂等、无副作用、不摸锁；"GetOrAdd 里初始化全局资源"在并发首访时双开。
- 值参数形态：GetOrAdd(key, value) 的 value 在调用点**无条件构造**——键命中时白造一次；热点写路径用 TryGetValue→TryAdd 双查或 Lazy 模式。

```csharp
private readonly ConcurrentDictionary<string, Lazy<Session>> _sessions = new();
public Session Get(string id) =>
    _sessions.GetOrAdd(id, _ => new Lazy<Session>(() => Connect(id))).Value;
// Lazy 惰性单执行:工厂双跑只是两个 Lazy 壳,Connect 保证一次
// Lazy<T> 默认 ExecutionAndPublication:.Value 并发安全,无锁等待可接受
```

- 原子性边界：AddOrUpdate 的 update 工厂持桶锁执行——`(_, v) => v + 1` 是安全计数器；add 工厂无锁可双跑（副作用放外面）；TryUpdate 是 CAS 原语——自定义读改写用它自旋。
- 枚举弱一致：并发修改不抛、可能漏掉/重复正在变的条目——用枚举做一致性审计（对账/清点）结果不可信；Count 也是全锁扫描，大表贵。
- 陷阱：工厂里再操作同一字典（嵌套 GetOrAdd）——语义混乱且"工厂运行时表里有 X"的断言随机挂；拆成两步显式化。
- 替代形态：读多写少 + 可整体重建 → 普通字典 + Volatile 发布新快照（34 节 Frozen 化），读路径完全无锁；ConcurrentDictionary 留给高频增量写。

## 93. Channel 进阶：有界容量与背压

- FullMode 决策表：Wait（写满挂起，真背压——管线/持久化必选）；DropOldest/DropNewest（保新鲜——行情、指标聚合）；DropWrite（丢弃并完成写入）。Unbounded = 无背压无上限——生产环境要给出"不可丢且永远消费得过来"的证明（24 节）。
- 容量定标用 Little's law：容量 ≥ 到达率 × 最坏处理延迟（另加突发余量）；过小 → 上游全挂在 WriteAsync（吞吐反降）；过大 → 延迟堆积掩盖消费端劣化。

```csharp
var ch = Channel.CreateBounded<Foo>(new BoundedChannelOptions(capacity: 512)
{   SingleReader = true, SingleWriter = false,
    FullMode = BoundedChannelFullMode.Wait });
// SingleReader 是承诺不是检查:违规未定义;快路径只在承诺成立时启用
var depth = new ChannelDepthGauge(ch);   // 自包装:深度/写入等待时长进指标
```

- 优雅停机序列（与 Complete 语义联动）：停生产者 → Writer.TryComplete() → 消费端 ReadAllAsync 排空 → await Reader.Completion → 才释放下游资源；跳步 = 丢在途数据或死等（24 节：Complete 后已入队仍可读）。
- 陷阱：DropWrite 下 WriteAsync"成功"但值被丢——写方无感知（TryWrite 返回 false 才显式）；丢弃计数是唯一事实源，没监控的 Drop 模式等于盲飞。
- 陷阱：并发规模变化后 SingleReader 承诺失守——随机重复/丢失等未定义行为；承诺按配置项管理，改动走评审。
- 阻塞读边界：带心跳的消费循环用 WaitToReadAsync + ct，别用 Task.WhenAny 组合超时（22 节 O(n²) 同族问题）。

## 94. AsyncLocal 与 ExecutionContext 传播

- 流动方向铁律：ExecutionContext（含 AsyncLocal）跨 await/Task.Run/池**向下游流动**，异步方法内对 Value 的修改**不回传**——方法返回时调用方自动还原；"设了为什么外面看不见"九成是这个方向性。
- 写法即语义：`ambient.Value = x` 后 await 的下游全可见；返回后自动 restore——做"scoped ambient"（每请求租户/trace）是正解，用它把结果传出去是误用。

```csharp
private static readonly AsyncLocal<string?> TenantId = new();
async Task HandleAsync(Request r)
{
    TenantId.Value = r.TenantId;                  // 下游全可见
    await Step1Async();                           // Step1 读到 ✓
}   // 返回后调用方的 TenantId.Value 自动还原(改动不外泄)
// IHttpContextAccessor 内部就是 AsyncLocal —— 后台线程拿 HttpContext
// 为 null 的根源;脱出前显式取值而不是把 ambient 传下去
```

- 陷阱：fire-and-forget 携带 AsyncLocal 大对象图——ExecutionContext 快照把每个脱出任务钉住引用（泄漏形态 + 每边界快照成本）；脱出前收窄（只带 ID）或 ExecutionContext.SuppressFlow 隔离。
- 陷阱：AsyncLocal 存可变对象（"ambient context"里塞 builder）——并行分支同时改一个对象 = 跨任务数据竞争；ambient 值一律不可变。
- 事实链路：System.Diagnostics.Activity（trace 标准件）内部就是 AsyncLocal——自研 correlationId 前先确认 Activity 不够用（108 节边界）。
- 性能面：无 AsyncLocal 时运行时执行流捕获走零成本快路径，第一个 AsyncLocal 出现后每次异步边界开始真实捕获/恢复——进程级"开没开"是二值的，库作者别随手 static AsyncLocal。

## 95. xUnit 家族选型与并行陷阱

- 并行模型背下来：xUnit 按 **collection** 并行、collection 内严格串行（不同测试类默认不同 collection = 默认全并行）；碰静态状态/文件/真库的测试类必须显式 [Collection("db")] 收进同串行域。
- v2 → v3 迁移点（2025 GA）：v3 默认**随机化测试顺序**（种子随程序集唯一 ID 稳定——防顺序耦合且 diff 后不乱）、TestContext.Current 拿当前测试管线状态、运行期 [Skip]、assembly fixture、ValueTask 测试支持——升级会把隐式顺序依赖全暴露，这是特性不是 bug。

```csharp
[Collection("database")]          // 同 collection 串行:真库测试护栏
public class OrderRepoTests : IAsyncLifetime
{
    public Task InitializeAsync() => ResetAsync();     // 异步种子/清理
    public Task DisposeAsync() => CleanAsync();
    [Fact] public async Task PingAsync() => await OkAsync();  // async Task ✓
}
```

- async 测试纪律：测试方法只允许 async Task/ValueTask（v3）——async void 测试体 runner 无法 await，**同步返回即假绿**；一元 async 同样没必要（27 节）。
- 家族选型线：xUnit v3（新项目默认）、NUnit 4（2024：Assert.That 主线 + API 现代化）、MSTest v4（2025：MTP runner、VSTest 退役方向）——团队一致 > 特性差异；混三家的基建成本远超收益。
- 陷阱：并行对单例宿主状态的测试 = 随机红（96 节）；collection 解决"互斥"不解决"污染"，清理永远挂 fixture 生命周期。
- 陷阱：v3 顺序随机化后有人关随机求稳定——顺序依赖是债务显形，修依赖（唯一键/自足种子）而不是冻结随机。

## 96. WebApplicationFactory 与集成测试隔离

- WAF 本质：内存 TestServer + 真实组合根（Program 全真跑）——它验证"启动 + 中间件 + 路由 + DI 全链"，不是控制器单测；替代 mock 面越窄越好，替换表进仓库文档。
- 隔离三敌：宿主是**进程内单例**（同 factory 的测试共享全部单例状态）、测试并行（95 节 collection）、环境差异（dev 配置漂进测试）。治理：测试类粒度建 factory、真共享资源（DB/文件）显式串行 + 每测试唯一键。

```csharp
public class ApiFactory : WebApplicationFactory<Program>
{
    protected override void ConfigureWebHost(IWebHostBuilder b) {
        b.UseEnvironment("Integration");                    // 独立配置层
        b.ConfigureTestServices(s => {
            s.AddAuthentication("Test").AddScheme<AuthOpts, TestAuthHandler>("Test", _ => { });
            s.RemoveAll<IDbSeeder>();                      // 摘掉碰真库的种子
        });
    }
}
```

- 陷阱：ConfigureTestServices 的替换发生在真实注册**之后**（顺序保证），但"部分替换"留下半真半假组合根（真缓存 + 假 DB）——每个替换服务都在文档里有一行"为什么"。
- 数据隔离分层：事务回滚（快但测不到提交语义）/ 每测试新 schema（Testcontainers，真但慢）/ 共享库 + 唯一键（最快但清理纪律重）——按断言深度选层，别一刀切。
- 陷阱：断言与资源——"再等等"式断言（Thread.Sleep/WhenAny+Delay）改显式轮询 + 超时（97 节），IHttpContextAccessor 的 AsyncLocal 在后台线程为空（94 节，要数据在边界显式取）；"TestServer 不占资源"是错觉——大请求/响应体照样全分配，集成套件单独配资源上限与并行度。

## 97. 确定性时钟与并发测试重放

- FakeTimeProvider 推进语义：Advance/AdvanceTo **同步执行**到期定时器与 Delay 的延续——大步推进可能递归触发到栈溢出（26 节且回调内联在调用线程）；测试模式：小步推进 + 中间 `await Task.Yield()` 让延续上池 + 断言。
- 时间类测试改造：所有 Task.Delay/超时走注入的 TimeProvider；"重试第 3 次成功"的用例推进虚拟钟即可——真实等待版（Thread.Sleep）是 CI 抖动与五分钟套件的来源。

```csharp
var time = new FakeTimeProvider();            // M.E.TimeProvider.Testing
var svc = new RetryService(time, Policy.Fixed(rounds: 3, ms: 100));
var op = svc.CallAsync(failsTwice);
time.Advance(TimeSpan.FromMilliseconds(200)); // 到期逻辑全部同步触发
var r = await op;                             // 毫秒级完成、零真等待
// 推进后立刻断言异步延续?中间补 await Task.Yield() 让池上延续跑完
```

- 并发重放写法：调度替身（自定义 TaskScheduler 排队回调，手动"放行"制造特定交错）复现竞争；随机种子纪律——一切随机测试注入固定 seed 且失败输出打印 seed（重现是修复前提）。
- 陷阱：FakeTimeProvider 只管注册进它的时间工作——真实 IO/线程竞争不归它管，"确定性"只在时间维度成立；并发正确性另用屏障对齐 + 注入延迟驱动交错窗口。
- 陷阱：轮询断言 `WhenAny(work, Task.Delay(3000))` 的真超时残留——换 `while (!done) { time.Advance(step); await Task.Yield(); }` 虚拟时间轮询，CI 时长归零。
- 重放资产：生产记录的请求/事件序列（脱敏）喂进确定性调度 harness = 回归即重现（98 节契约 + 本节调度合成）。

## 98. 快照测试与契约测试

- Verify 类快照的纪律：快照文件是**评审产物**（diff 进 PR review）不是"通过的凭证"——大快照 = 无人 diff = 腐烂；拆到单元意义粒度（单 API 响应、单消息），scrubber 归一 GUID/时间戳/端口/主机名，否则每次跑都假差异。
- 契约测试三层：序列化契约（golden 文件双向回放 + 严格模式，80 节）；API 契约（OpenAPI/路由快照，加接口先过 diff）；消费者驱动（Pact 类：provider CI 验证 consumer 期望，破坏契约在合入前红）。

```csharp
[Fact]
public Task Order_dto_wire_shape_is_stable() =>
    Verifier.Verify(new OrderDto(42, "ann", 9.90m));
// .received/.verified 文本进仓库;scrubber 清洗后 diff = 纯语义变化
[Fact] public void Wire_contract_admits_unknown_members()   // tolerant reader 守护网
{   // 未知字段必须被跳过而不是炸(80 节);删字段/改类型的红线由 golden 守
    var o = JsonSerializer.Deserialize<Order>("""{"id":1,"future":{}}""");
    Assert.Equal(1, o!.Id);
}
```

- 陷阱：快照对比环境相关数据——CultureInfo/时区/浮点 ToString 在 CI 与本机劈叉；测试宿主钉死 InvariantCulture + 浮点按位或定点量化。
- 陷阱：批量接受快照（accept-all 过 CI）——快照门禁的死亡方式；接受必须逐文件过人眼，CI 里禁 accept 别名。
- 契约漂移门禁形态：schema diff 工具（OpenAPI diff、JSON Schema 兼容检查）机械判定 breaking（删字段/改类型/改必填），人工只评审 additive——机械可判的别消耗人。
- 陷阱：行为不变的重构出现大快照 diff——先怀疑快照面太宽（把内部状态也序列化了），收紧快照而不是"顺手接受"。

## 99. Mock 边界与过度 Mock 反模式

- seam 先行原则：mock 的是"你拥有并显式设计出的边界"（注入的时钟、HTTP、存储适配器）；没有 seam 就想 mock 的代码（密封类/静态/扩展方法）说明该重构而不是该找 mock 神器——为可测性引接口加分，为 mock 造"无用接口"减分。
- 过度 mock 三症状：①测试里 Verify(Times.Exactly(3)) 调用计数断言（实现细节耦合，重构即红）；②mock 了 value object/DTO/内部类型；③mock 面超过被测逻辑——测试在描述"实现怎么调"而不是"行为是什么"。

```csharp
// 好:HttpMessageHandler 级替换(客户端逻辑真实跑)
var handler = new StubHandler(_ => new HttpResponseMessage
{   StatusCode = HttpStatusCode.OK,
    Content = new StringContent("""{"id":1}""") });
var client = new HttpClient(handler) { BaseAddress = new Uri("https://t") };
// 坏:Mock<HttpClient> —— HttpClient 不是你的边界,mock 它 = mock 传输细节
```

- 替代品优先序：真实现（值对象/纯函数）> 手写 fake（内存仓储/总线，一处维护全组复用）> mock 框架（协议边界/错误注入）> shims/神器（禁入 CI）。
- 陷阱：宽松 mock 默认返回 default（null/0/空）——被测代码静默走"空数据"分支假绿；契约敏感套件开 Strict（未配置调用即抛）。
- 陷阱：mock 掉协作者后再断言协作者行为——那是协作者自己的测试该做的事；单测断言**本单元**输出/状态，协议边缘（调用顺序/参数契约）才轮到 Verify。
- 三大高频 mock 区的固定 seam：HttpClient → handler 替换（73 节）、时间 → TimeProvider（26/97 节）、Guid/ID → 注入接口——新服务第一天就留好，别事后穿墙。

## 100. 覆盖率与变异测试经济性

- 覆盖率的两种正确用法：patch coverage 门禁（**新代码**行覆盖 ≥80%——增量不欠新债）；模块级趋势告警（核心域覆盖单边下跌 = 债在长）。错误用法：全局 80% 军备竞赛——为凑数生成断言空洞的测试（覆盖 ≠ 断言强度）。
- 变异测试（Stryker.NET）是"断言强度"的诚实度量：注入变异子（条件翻转、常量扰动、边界 off-by-one）后跑测试，杀不死的变异 = 断言缺口或等价变异；mutation score 比覆盖率难造假一个量级。

```bash
dotnet stryker --project-path ./Core.csproj \
  --mutate '!**/Migrations/**' \      # 排除生成代码,钱花在刀刃
  --since --git-ref main \            # 增量模式:只变异 diff 内代码
  --baseline ./stryker.baseline.json  # 基线对比:只防回归不追历史债
```

- 经济边界：变异按 CPU 小时收费——只对"域核心 + 资金路径 + 解析器"开启；胶水层（controller/映射）patch coverage 足够；全库变异的结局是团队把它关掉。
- 幸存变异三分诊：真缺口（补测试）/ 等价变异（文档化排除 + Stryker 注释锚点）/ 低价值区（mutate 排除）——三分诊产出就是"哪些测试其实没测东西"的清单。
- 陷阱：mutation score 高但集成层全裸——变异分数只度量单测，与 96 节集成面互补不互替。
- 陷阱：flaky 测试污染变异结果（"没杀死"其实是测试挂了）——先治 flaky（97 节）再上变异；Stryker 超时按测试 P95 放宽。

## 101. CI 性能门禁与基准回归

- 门禁的统计形态：BenchmarkDotNet Ratio + 置信区间（CI 不重叠才算回归）——单次运行的原始毫秒数没有判决力；托管 runner 噪声 ±20-30%，裸机/独立性能节点是严肃门禁的前提。
- 分层执行：PR 快门禁（子集基准 × N 次，5-10 分钟预算）+ 每晚全量基准 + 趋势归档（结果 JSON 进库/看板，回归 bisect 定位提交）；门禁红线比看板红线保守——假阳性的代价是开发者把门禁关掉。

```bash
dotnet run -c Release --project bench/ --filter '*' \
  -- --job short --memory --export json --artifacts out/
# 性能节点纪律:固定频率策略(performance governor)、独占节点、容器 CPU
# limit=off —— CFS 限流会扰动 GC 与 JIT 计时(50 节同源问题)
```

- 噪声治理清单：固定电源/频率、独占机器（无同宿主邻居）、锁 SDK/运行时版本（38 节分层行为随版本变）、环境探针（CPU 型号断言不符即 fail——防"门禁跑在别人笔记本上"）。
- 陷阱：门禁只看均值——尾延迟/P95 才是 SLA 语言；基准对齐业务口径（每请求分配、P99）而不是纯微基准（41 节微基准≠宏收益）。
- 回归归因流：门禁红 → 拉基线产物对比（Ratio 表 + 反汇编 diff）→ 二分定位 → 带证据进 ticket（"此 PR 让 Parser.Decode 慢 18%，Ratio CI [1.14, 1.22]"）——没证据链的性能争论直接退回。
- 陷阱：被 skip/过滤的基准静默变绿（门禁假装跑了）——skip 必须红；结果与硬件指纹一起存档，跨机结果不可比。

## 102. NativeAOT 迁移约束清单

- 硬死清单（发布期就该红）：Assembly.LoadFile/Load(byte[])/字符串找类型、Reflection.Emit/DynamicMethod、DLR（dynamic）、Roslyn 运行时脚本、Fody 织机的运行时假设、TypeNameHandling 类动态反序列化、Expression.Compile（AOT 退解释器——功能在、性能死，105 节）。
- 软伤清单（逐项换路径）：Regex → [GeneratedRegex]（RegexOptions.Compiled 在 AOT 静默退解释模式）；配置反射绑定 → 源生成绑定（71 节）；STJ 反射模式 → JsonSerializerContext（75 节）；AutoMapper/MediatR → Mapperly/源生成 mediator（103 节）。

```xml
<PropertyGroup>
  <PublishAot>true</PublishAot>
  <StripSymbols>true</StripSymbols>
  <InvariantGlobalization>true</InvariantGlobalization>
</PropertyGroup>
<!-- 迁移顺序:PublishTrimmed 警告清零(便宜回路) → PublishAot 清余量
     → 跑产物二进制冒烟(测试程序集全绿 ≠ AOT 可用,11 节) -->
```

- EF Core 现实线：EF Core 10（2025-11，LTS）才开始实验性 Native AOT（compiled model + `dotnet ef dbcontext optimize --precompile-queries`，SQL Server 先行）——按"实验"定风险等级；轻量数据访问用 Dapper/原生 ADO.NET 更稳。
- 迁移工法固定顺序：普通部署先开 PublishTrimmed + 警告即错（90% 问题在此暴露且迭代快）→ 切 PublishAot → 产物冒烟与集成 → 收益复核（启动/内存/体积三指标入档）。
- 陷阱：`IsDynamicCodeSupported` 分流写成 `#if`——编译期常量在普通构建恒 true，AOT 分支从未被编译过；必须运行时属性分流（12 节）。
- 陷阱：AOT 下"优雅降级到反射"——反射路径本身可能死（11 节），降级分支要在 AOT 冒烟里真跑过才算存在。

## 103. 反射退场：source generator 替代

- 通用替代模型三件套：①注册表模式（attribute 扫描 + 增量 source generator 生成 switch/冻结字典——"编译期反射"）；②元数据特化（JsonSerializerContext 形态，75/77 节）；③显式工厂表（手写 static 冻结字典，无生成器依赖）。选型：现成生成器 > 自研增量生成器 > 手写表。

```csharp
[AttributeUsage(AttributeTargets.Class, AllowMultiple = true)]
public sealed class HandlesAttribute(string topic) : Attribute { }
// 增量生成器扫描 [Handles("orders")] 生成(示意):
// TopicRegistry.Map = FrozenDictionary.Create(
//     ["orders"] => static (Msg m) => new OrderHandler().Run(m));
// 运行期:TopicRegistry.Map[topic] —— 零反射、AOT 安全、启动零成本
```

- 生态替换表：AutoMapper → Mapperly；MediatR → 源生成 mediator 或手写 dispatch switch；Scrutor 自动注册 → 显式注册或注册生成器；字符串类型名 → nameof/常量表。迁移按"每库一 PR + 契约测试"推进，禁止大爆炸。
- 自研增量生成器纪律：缓存键的相等比较器必须正确（否则 IDE 全量重算卡死）；hint name 确定性唯一；EmitCompilerGeneratedFiles 调试生成物；生成器目标钉 netstandard2.0（编译器宿主兼容）。
- 陷阱：留一条"生成器没跑就回落反射"的兜底——兜底会变成唯一路径（11 节 AOT 死亡 + 测试全绿假象）；生成器没跑 = 构建 fail，不是 fallback。
- 陷阱：生成代码的测试盲区——生成物本身要快照测试（生成的注册表 vs 手工期望表，98 节）；生成器 bug 均匀污染所有生成路径，一处验证远便宜于到处排障。

## 104. LibraryImport 与 P/Invoke 现代化

- LibraryImport vs DllImport：源生成封送（partial static 方法 + 特性标注）——封送决策全部显式（字符串编码、数组方向、bool 表示）、生成代码可读可审、裁剪/AOT 安全；新代码一律 LibraryImport，DllImport 只留在冻结兼容层。
- DllImport 时代的坑别带过来：默认 ANSI 封送（代码页相关——非 Windows 直接 mojibake）、隐藏 pin、SetLastError 没开导致 GetLastWin32Error 读到陈旧值。

```csharp
internal static partial class Native
{
    [LibraryImport("mylib", EntryPoint = "parse", StringMarshalling = StringMarshalling.Utf8)]
    internal static partial int Parse(ReadOnlySpan<byte> input,   // pin 交给生成代码
                                      out uint flags);            // blittable 直传

    [LibraryImport("kernel32", SetLastError = true)]
    internal static partial nint Open(string name);   // 错误通道显式开
}
```

- 字符串经济学：字符串参数每调用分配 + 转码（Utf8 还要临时缓冲）——超热边界改传 ReadOnlySpan<byte>（调用方管编码）或预转换缓存；返回字符串按 CoTaskMem 释放约定——非 Windows 自定义分配器对不上就改出参 span。
- 回调与委托：delegate 参数走封送 thunk——高频回调改 `delegate* unmanaged`（函数指针零封送）；native 持有的托管回调必须保活（GCHandle/注册表），GC 不会因为 native 还在用就客气。
- 陷阱：LibraryImport 必须 partial + static 且无实现体（生成器负责）；EntryPoint 拼写错 = 运行时 EntryPointNotFoundException——AOT 冒烟必须真跑每条 P/Invoke。
- 结构体封送：只传 blittable（显式 LayoutKind）；bool 显式 [MarshalAs(UnmanagedType.U1)]（默认 4 字节 BOOL 是 Win32 约定，跨平台 native bool 是 1 字节——对不上就是间歇性损坏）。

## 105. 委托与 Expression 在 AOT 下的死路

- Expression 树在 NativeAOT 的真相：Compile() 退化为**解释执行**（不抛、慢一个量级）——"功能还在"的假象最危险：没有报错没有警告，只有一段神秘慢代码；用 IsDynamicCodeSupported 预判并在启动拒绝，好过静默慢。
- 三条死路清单：Expression.Compile 当动态代码生成（AOT 退解释器）；Delegate.CreateDelegate/MethodInfo.Invoke 打未保活成员（MissingMethodException/MetadataException）；dynamic/DLR（binder 在 AOT 缺位，直接不可用）。

```csharp
// 死路:运行期 Build Expression 再 Compile(动态 LINQ 全族同病)
// 活路:编译期固化的泛型委托 —— AOT 下就是直接调用
public static class Access<T> where T : class
{
    public static readonly Func<T, decimal> Total =
        static o => GetTotal(o);        // lambda 由编译器烧进方法表
}
// UnsafeAccessor(.NET 8+):私有成员零反射访问,替代"反射取值器"整条老路
```

- 替代设计：查询/投影场景 → 把操作空间枚举进枚举 + 生成 switch（用户组合"字段 + 谓词"而不是自由 lambda）；动态规则 → 手写小 DSL 解释器（解释自己的树，别借 Expression）；热反射取值 → 静态委托缓存或 UnsafeAccessor。
- 陷阱：LINQ 本体无害（Enumerable 是泛型真代码）——死的是"运行期 Build 再 Compile"的动态 LINQ 库与运行期表达式合成；EF 的翻译管线另算（102 节）。
- 陷阱：把 Expression 当"强类型反射"读属性名——编译期构造的常量树安全，运行期 Build 的树逐节点验证；两形态混用的库直接问清再引入。
- CI 防线：AOT 冒烟里专门跑 Expression.Compile 调用点 + 启动断言 IsDynamicCodeSupported（102 节联动）。

## 106. 动态代码、插件与 Collectible Assemblies 边界

- ALC 插件模型：AssemblyLoadContext 按插件隔离依赖（AssemblyDependencyResolver 读插件目录 deps.json）——每个 ALC 一套版本宇宙；**契约接口放共享契约程序集、由宿主默认 ALC 加载**，插件引它而不是自带副本——不同 ALC 的同名类型互不兼容，InvalidCastException 是新手第一课。

```csharp
var alc = new PluginAlc(path);                 // isCollectible: true 才可卸载
var entry = alc.LoadFromAssemblyPath(path)
                 .GetType($"{ns}.Entry")!;
var plugin = (IPlugin)Activator.CreateInstance(entry)!;   // 契约在宿主 ALC
plugin.Run();
alc.Unload();                                  // 只是"请求":GC 决定真卸载
```

- 卸载的现实：Unload() 后要等 GC（WeakReference 探测 IsAlive）——卡住的元凶清单：静态事件订阅、插件栈帧还压在后台线程/Task、Timer 回调、GCHandle、宿主侧缓存的 Type/委托/实例。gcdump 对比（47 节）找"谁还抓着插件程序集"。
- 陷阱：插件类型实例逃逸进宿主缓存——一个 object 钉死整个 ALC；宿主边界只过契约接口或序列化数据，宿主缓存禁止存插件类型。
- NativeAOT 下插件全灭：运行时不能加载托管程序集——插件架构改进程外（gRPC sidecar、消息总线契约）或嵌入式脚本引擎（Lua/JS，数据边界天然隔离）；设计期按部署形态选技术，别发布才撞墙（9、102 节）。
- MetadataLoadContext 是"只看不跑"的反射容器（设计时工具、契约扫描）——AOT 安全、不影响真实加载；插件元数据预检（依赖冲突/目标框架）用它。

## 107. async void 事故解剖

- 合法面只有一处：**事件处理器签名被框架钉死为 void**——除此之外的 async void 全是 bug；"我想发射后不管"用 Task + SafeFireAndForget，不用 void。
- 异常逃逸路径：async void 里抛的异常经当时的 SynchronizationContext.Post 回灌（UI：消息泵炸未处理弹窗）或直接线程池未处理（无上下文服务：**进程崩溃**，86 节）——没有任何 Task 让你观察它。

```csharp
// 事故形态:看起来无害的保存按钮
async void btnSave_Click(object? s, EventArgs e)
{
    await SaveAsync();   // 抛 → 无 awaiter → AppDomain.UnhandledException
}                        // 症状:偶发进程重启,崩溃与触发点无法关联
async void btnSave_Click(object? s, EventArgs e)      // 正解:一行转发
    => _ = SafeFireAndForget(SaveAsync(), origin: "btnSave");
static async Task SafeFireAndForget(Task t, string origin)   // 集中兜底(27 节)
{ try { await t; } catch (Exception ex) when (ex is not OperationCanceledException)
  { Log.Error(ex, "bg {Origin}", origin); Alarm.Count(origin); } }
```

- 事故解剖学：async void 崩溃的特征是"异常与触发点无关联"（栈在回调线程）——从崩溃日志的线程类别倒查 async void 面（CA2012/CS1998 分析器 + 自定义扫描进 CI）。
- 测试盲区：async void 测试体 runner 无法 await，**同步返回即假绿**（95 节）；事件处理器单测把逻辑抽到 Task 方法里测，处理器只剩一行转发。
- 陷阱：async void 签名收不进 CancellationToken，需要取消的"事件式"逻辑改 IAsyncEnumerable/显式命令对象（23 节）；`_ = SomeAsync()` 与 async void 的区别要讲清：前者可观察（异常进 Unobserved 还有遥测，86 节）、后者不可观察——评审口径：裸 `_ =` 必须包 SafeFireAndForget，async void 一律拒。

## 108. 隐式配置与静态状态反模式

- 静态可变状态 = 隐式全局依赖图：AppConfig.Current、static HttpClient、静态缓存字典——耦合不可见（调用方不知道自己依赖了什么）、线程安全存疑、测试只能靠"先设再测"的顺序魔法；并行测试（95 节）与环境差异让它成为随机性放大器。
- 配置纪律：配置读取**只在组合根发生**（启动绑定进 IOptions 家族，71 节）；业务代码深处冒出 Environment.GetEnvironmentVariable/IConfiguration = 评审直接退回——配置点集中才可验证、可热更、可测。

```csharp
// 反模式全家桶(常见于同一个文件):
static HttpClient s_http = new();                  // DNS 陈旧 + 无法 mock(73 节)
static readonly Dictionary<K, V> s_cache = new();  // 无锁并发损坏(89 节)
static string? s_conn;                             // 惰性初始化竞态(91 节)
var delay = Task.Delay(TimeSpan.FromSeconds(int.Parse(
    Environment.GetEnvironmentVariable("RETRY") ?? "3")));  // 配置泄漏进逻辑

// 治理:全部下沉为注入依赖(HttpClientFactory/IMemoryCache/IOptions)
```

- Ambient 上下文（AsyncLocal 系）的边界：trace/correlation 用 Activity 是标准（94 节）；业务语义（租户、用户）**禁止 ambient 化**——隐式参数让调用图失真、跨进程/队列边界必断；显式参数 + 边界处一次性填充。
- 测试盲区形态：隐式状态让测试"碰巧过"（本机环境变量存在、上一个测试设过静态值）——随机顺序（95 节）+ 显式重置 fixture 是解药；env-only 配置在 dev 全绿、首次部署炸——ValidateOnStart 是最后防线（71 节）。
- 治理序列：①清点静态可变清单 ②逐处三选一：转 DI 托管 / 转不可变快照 / 转显式参数 ③架构测试（NetArchTest）断言"业务程序集禁止静态可变字段"防复发。

## 109. 事故复盘一：线程池饥饿雪崩

- 现场重建：同步 DB 驱动埋在库底层（旧 System.Data.SqlClient 习惯迁移），流量高峰 → 池线程全阻塞在同步 Read → 队列长度爬升、注入速率（个位数/秒）追不上 → p50 尚可、p99 雪崩 → 客户端超时重试放大并发 → 连接池也耗尽（每个阻塞线程各持连接）→ 40 秒滚成服务假死，而 CPU 全程空闲。

```bash
# 取证链(保留进 runbook):
dotnet-counters monitor -p $PID System.Runtime   # queue-length↑↑ + cpu↓ + 线程缓涨
dotnet-stack report -p $PID                       # 300+ 线程聚集在 SqlClient/Wait
kubectl describe pod app | grep -A2 "Last State"  # 排除 OOMKilled(50 节症状重叠)
```

- 误诊分岔（每个都真实踩过）："DB 慢"（DB 指标正常，是客户端阻塞）；"GC 停顿"（time-in-gc 平稳）；"K8s 限流"（CPU 未达 limit）——三个排除后才是饥饿；dotnet-stack 的线程栈聚集模式是一锤定音证据。
- 止损顺序：SetMinThreads 临时抬底（写明 24h 拆除）→ 入口限流挡新流量、放存量消化 → 替换阻塞调用为真异步全链（15 节红线分析器进 CI）→ 压测复验（饥饿注入模型：5x 突发 + 300ms 下游延迟注入）。
- 防复发固化：每夜并发浸泡（10 分钟高并发 + queue-length 断言）；`.Result/.Wait` 分析器零容忍；架构评审加检查点"每层阻塞上限是多少"——饥饿从来不是更快的机器能修的。
- 复盘要点：雪崩成型时间 ≈ 队列增长时间 × 重试放大系数；把"注入速率 vs 阻塞增长"写成容量公式进 runbook，下次直接算不用猜。

## 110. 事故复盘二：LOH 碎片与 Gen2 停顿

- 现场重建：报表导出每请求分配 200KB-4MB 中型缓冲（大 JSON 字符串拼接、byte[] 组包）→ 全落 LOH（85K 阈值）→ LOH 默认不压缩 → 碎片累积数天 → Gen2 停顿从 10ms 涨到百 ms 级、p99 周期性尖刺 → 最终在容器 limit 内 allocation 失败（"有空间没连续段"的几何性 OOM）。

```bash
# 证据链:
dotnet-counters monitor -p $PID System.Runtime    # gen2-count↑ + time-in-gc↑
dotnet-gcdump collect -p $PID -o t1.gcdump        # 两时点 diff:byte[]/string 居首
dotnet-dump collect -p $PID
# > dumpheap -stat -min 85000                     # LOH 常客清单
# > gchandles                                     # 排除 pin 放大(49 节)
```

- 修复主线按分配率排序：中型缓冲 ArrayPool 化（32 节 Rent/Return 纪律）——LOH 分配率直接归零；大 JSON 输出改流式（Utf8JsonWriter 直写 Stream，不在内存拼整串）；固定 IO 缓冲迁 POH（44 节）——逐刀验证再进下一刀。
- 过渡期手段：维护窗口 `GC.Collect(2, GCCollectionMode.Aggressive)` 整形碎片（49 节：手术不是保养）；滚动重启买观察时间——都写明"临时"与退出条件，防止变成永久仪式。
- 陷阱：调 DOTNET_GCLOHThreshold 掩盖问题（碎片换成 Gen0/Gen1 压力）；"加内存"同样掩盖（碎片是几何问题不是容量问题）——两条在复盘里标记为"禁止的修复"。
- 防复发固化：LOH 碎片率与 LOH 分配率进面板（44 节 FragmentedBytes）；分配审计中间件（42 节）按端点曝光分配字节——单请求 >100KB 的端点自动进优化队列，别等下一次事故。

## 111. 事故复盘三：captive 依赖静默泄漏

- 现场重建：单例 ReportService 构造注入 AppDbContext（scoped）——dev 全绿（单用户串行）；生产三周后：偶发 `InvalidOperationException: A second operation started on this context`（并发冲突）、连接池耗尽（DbContext 的连接被单例长期持有，不随请求归还）、内存缓涨（change tracker 挂着历史实体图——它"缓存"了自己）。

```csharp
public sealed class ReportService(AppDb db)     // 病灶:AppDb 是 scoped → captive!
{
    public Task<Report> Build(Guid id) => db.Reports.FirstAsync(r => r.Id == id);
}
// 修复(租约,69 节):单例注入 IServiceScopeFactory,操作级开 scope
public async Task<Report> Build(Guid id)
{
    await using var scope = _scopes.CreateAsyncScope();
    return await scope.ServiceProvider.GetRequiredService<AppDb>()
                      .Reports.FirstAsync(r => r.Id == id);
}
```

- 为什么验证器没拦：dev 的 ValidateScopes 拦"从根解析 scoped"，但该服务经**工厂 lambda** 注册——lambda 是黑盒（69 节）；staging 用的是简化组合根，没有复刻生产注册面——教训：staging 必须跑生产同构注册。
- 证据链：gcdump 两时点 diff → DbContext 实例只增不减、gcroot 落到 ReportService（47 节）；连接池计数（MaxPoolSize 打满 + 连接 lifetime 超长）；"second operation" 异常的时间分布与并发上升曲线吻合。
- 修复验收 + 通用教训：修复 PR 带三件防复发——架构测试（NetArchTest：单例构造器禁注 scoped 服务）、staging 开 ValidateScopes + ValidateOnBuild 且组合根同构、连接归还率指标进监控；captive 的潜伏期 = 首个并发窗口，几乎总在上线后 N 周爆发——"dev 全绿"对生命周期缺陷零证明力，评审单例 PR 唯一问题是"它碰了什么生命周期"（112 节维度三）。

## 112. C# PR 评审清单（专家版）

- 五维检查（每维一句话定罪）：**异步**——ct 传到底了吗？库代码ConfigureAwait(false) 全覆盖吗？有 .Result/async void/Task.Run 包同步吗？ValueTask 被二次消费吗？**分配**——热路径 LINQ/闭包/装箱/内插 ToString 清单化了吗？span/pool 语义对吗？**生命周期**——IDisposable/IAsyncDisposable 对称吗？单例碰 scoped 了吗（111 节）？事件/定时器/channel 完成语义闭环吗？**AOT/裁剪**——反射路径有 11 节必死项吗？IL2xxx 是被 NoWarn 消掉的吗？**可空**——`!` 有逐处理由吗？公共 API 注解齐了吗？

```text
危险信号(见到即问):
.Result/.Wait  async void  new CTS() 无 using  static HttpClient  GC.Collect
lock 内 await  catch(Exception){} 无策略  #pragma disable IL
Task.Run(同步库)  Unbounded Channel  ref struct 进 public API
[UnscopedRef]/[SkipLocalsInit]/unsafe —— 逐处要注释锚点解释"为什么安全"
```

- 评审顺序纪律：先行为后风格、先正确后性能——五维是行为层的子清单不是全部；**性能断言必须有基准**（41 节：无 BenchmarkDotNet 输出的"这样更快"不进讨论）；并发改动必须附确定性测试设计（97 节）。
- 退回要模板化可执行：不是"感觉不好"，而是指向节——"见 15 节：此边界禁止同步等待；建议方案 X（改造点/预计工作量/验证方法）"；清单的价值是把专家直觉变成团队可执行规则。
- 清单维护：每起生产事故（107-111 节形态）复盘后必有一条增改——"第一个该拦住它的是哪条/缺哪条"；清单半年没动 = 团队完美或清单失效，多半是后者。
