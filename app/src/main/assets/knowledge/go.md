<!-- kb: id=go | role=builtin_coding_go | title=Go 专家知识库 | version=1 -->
# Go 专家知识库

## 必看内容

- **版本基线先钉死**：1.22 循环变量每轮新建（老闭包 bug 消失、`x := x` 惯用法退役）；
  1.23 range-over-func + Timer 无引用即回收；1.24 Swiss map / b.Loop / tool 指令；
  1.25 容器感知 GOMAXPROCS / synctest 转正 / WaitGroup.Go；1.26 Green Tea GC 默认 /
  new(expr) / errors.AsType（见 1-16 节）。
- **GOMEMLIMIT 是软限不是护栏**：覆盖 runtime 管理内存（堆+栈+运行时结构），
  不含二进制与 cgo 分配；设容器限额 ~90%，GOGC 保持 100；超限先触发 GC limiter
  （50% CPU 兜底、吞吐塌方）再轮到 OOMKill——两种"变慢/变死"要分得清（38、44 节）。
- **逃逸优先于调参**：GOGC/GOMEMLIMIT 治标，热路径减分配治本；
  `go build -gcflags='-m -m'` 看理由链，接口装箱/fmt/闭包捕获是三大逃逸源
  （39-40 节）。
- **取消不杀 goroutine**：ctx.Done() 只广播信号，退出责任在被取消方；
  for-select 忘配 `case <-ctx.Done(): return` 是头号泄漏源；
  每个 `go` 语句必须答得出"谁让它退、谁等它退"（51-55 节）。
- **typed nil 是接口二义性事故**：`(*T)(nil)` 装进 error 后 `err != nil` 为 true——
  接口值是 (type, data) 二字组，type 非空整体就非空；错误路径返回显式 `nil`
  （70 节）。
- **race detector 是检测器不是证明器**：happens-before + vector clock（TSan），
  只报真实跑到的交错、5-10x 开销、8192 goroutine 上限；CI 全量 `-race` +
  压测影子流量双管齐下；发现竞态 = P0，"加个锁试试"不叫修复（29-30 节）。
- **channel 九宫格矩阵背下来**：nil/正常/closed × send/recv/close 九种行为，
  闭着眼睛要能写；nil 分支禁用、closed 恒就绪（空转烧 CPU）、
  close 只属于唯一 sender（48-50 节）。
- **errors.Join 之后错误是树不是链**：Is/As 沿 Unwrap 走（含 `[]error` 树的
  DFS）；`%w` 别丢（1.20 起可多 `%w`），`%v` 断链只在刻意隐藏实现时用
  （61-63 节）。
- **pprof 先分 inuse/alloc 再说话**：alloc_space 定 GC 压力、inuse_space 定泄漏、
  `-base` 做前后差分；goroutine profile `debug=2` 是事故第一现场
  （77-80 节）。
- **benchmark 无统计不结论**：b.Loop（1.24）防死码消除 + setup 自动排除 +
  `-count=10` + benchstat 看 p 值；单次跑完直接发 PR 的数据全是噪声
  （82-83 节）。
- **GOMAXPROCS 超 cgroup 配额 = throttling 假死**：1.25 起默认感知 cgroup
  CPU 带宽限制（取整、下限 2）；但任何显式设置（含老库 automaxprocs）都会
  屏蔽感知——升级第一件事是删掉自己设的值（10、23 节）。
- **实验特性会消失**：GOEXPERIMENT 的东西（swissmap/htmap/jsonv2/greenteagc）
  不是兼容承诺，转正或移除都随版本发生；生产构建的实验开关必须集中在
  基础镜像 ARG 里统一治理（11、15-16 节）。

## 快速索引

| 想找什么 | 去哪一节 |
| --- | --- |
| 循环变量捕获 bug 与 1.22 迁移 | 1. Go 1.22 循环变量语义变革与迁移 |
| for range 整数语义 | 2. range over 整数与循环新形态 |
| range-over-func 与 Seq/Seq2 | 3. Go 1.23 range-over-func 迭代器模型 |
| iter.Pull 拉迭代器成本 | 4. iter.Pull 与 push 迭代器的成本 |
| 迭代器性能实测 | 5. 迭代器性能基准与选型 |
| Swiss Tables map 内存 | 6. Go 1.24 Swiss Tables：map 内存革命 |
| weak.Pointer 用法 | 7. weak pointer 与弱引用结构 |
| AddCleanup 替代 finalizer | 8. runtime.AddCleanup 与 finalizer 退役 |
| 泛型类型别名与 tool 指令 | 9. 泛型别名与 go.mod tool 指令 |
| GOMAXPROCS cgroup 感知 | 10. Go 1.25 容器感知 GOMAXPROCS |
| Green Tea GC 实验 | 11. Green Tea GC 实验特性评估 |
| Go 1.26 变化 | 12. Go 1.26 新纪元：Green Tea 默认与 new 表达式 |
| json/v2 迁移评估 | 13. encoding/json/v2 前瞻迁移 |
| WaitGroup.Go 用法 | 14. sync.WaitGroup.Go 与同步原语进化 |
| GOEXPERIMENT 生产治理 | 15. GOEXPERIMENT 治理与实验开关纪律 |
| Go 版本升级 SOP | 16. 版本升级纪律与兼容承诺 |
| GMP 结构速查 | 17. GMP 模型真实结构 |
| schedule 顺序与 runnext | 18. 调度循环与 runnext 语义 |
| work-stealing 偷取规则 | 19. work-stealing 与全局队列公平性 |
| 异步抢占 SIGURG | 20. 异步抢占：信号机制深水 |
| sysmon 职责清单 | 21. sysmon：retake 与 forcegc |
| 栈伸缩与 hot split | 22. goroutine 栈伸缩与热分裂 |
| syscall 时 P 去向 | 23. 系统调用路径：P 的让渡与回收 |
| netpoller 原理 | 24. netpoller 网络轮询器深水 |
| goroutine 泄漏排查 | 25. goroutine 泄漏取证 |
| 死锁与 runtime throw | 26. 死锁诊断与运行时解剖 |
| happens-before 条款 | 27. Go 内存模型核心条款 |
| atomic 使用纪律 | 28. sync/atomic 内存序纪律 |
| race detector 原理盲区 | 29. race detector 内部与盲区 |
| 竞态隐身形态清单 | 30. 数据竞态的隐身形态 |
| 安全发布规则 | 31. 安全发布与零值语义 |
| false sharing 缓存行 | 32. false sharing 与缓存行填充 |
| Mutex 饥饿模式 | 33. Mutex 与 Once 内部机制 |
| sync.Map 还能不能用 | 34. sync.Map 演进与 htmap 新形态 |
| 无锁结构性价比 | 35. 无锁结构纪律与原子指针 |
| 三色与写屏障 | 36. GC 架构：三色标记与混合写屏障 |
| GOGC 与 pacer | 37. pacer 数学与 GOGC 调参 |
| GOMEMLIMIT 调法 | 38. GOMEMLIMIT 生产用法与死亡螺旋 |
| 逃逸分析验证 | 39. 逃逸分析深水：判定规则与验证 |
| 热路径减分配 | 40. 减分配实战：热路径零分配手法 |
| sync.Pool 陷阱 | 41. sync.Pool 池化纪律 |
| size class 与碎片 | 42. 大对象、size class 与堆布局 |
| GC 停顿诊断 | 43. GC 停顿解剖与诊断指标 |
| 容器 OOM 排查 | 44. 容器内存治理与 OOM 取证 |
| Go 内存泄漏取证 | 45. 内存泄漏取证方法论 |
| hchan 内部 | 46. channel 内部结构 hchan |
| channel 所有权 | 47. channel 所有权与方向纪律 |
| channel 语义矩阵 | 48. nil 与 closed channel 语义矩阵 |
| select 陷阱 | 49. select 深水：随机性、顺序与开销 |
| close 广播模式 | 50. 关闭广播与 done 信号模式 |
| context 取消机制 | 51. context 取消传播内幕 |
| context 使用纪律 | 52. context 架构纪律 |
| context.Value 键纪律 | 53. context.Value 深水 |
| errgroup 用法边界 | 54. errgroup 群组并发与 SetLimit |
| go 语句审计 | 55. goroutine 生命周期审计与取消传播 |
| 优雅停机模板 | 56. 优雅停机与信号处理 |
| 并发上限与背压 | 57. 有界并发与背压设计 |
| pipeline 设计 | 58. pipeline 扇出扇入与 stage 设计 |
| singleflight 击穿护盾 | 59. singleflight 请求合并 |
| time.Timer 1.23 变化 | 60. 定时器深水与 1.23 语义变革 |
| errors.Is/As 机制 | 61. errors.Is/As 内部与包装链 |
| errors.Join 树 | 62. errors.Join 与错误树遍历 |
| %w 包装纪律 | 63. fmt.Errorf %w 包装纪律 |
| 错误分类设计 | 64. 哨兵错误与错误分类设计 |
| defer 清理聚合 | 65. 资源清理：defer 与错误聚合 |
| panic/recover 纪律 | 66. panic 纪律与 API 边界 recover |
| 错误日志分工 | 67. 错误与日志的分工架构 |
| 失败域设计 | 68. 失败域：错误的层次设计 |
| iface/itab/eface | 69. interface 内部：iface、itab 与 eface |
| typed nil 陷阱 | 70. typed nil 陷阱与防御 |
| 接口设计纪律 | 71. 接口设计：小接口与消费者定义 |
| gcshape 字典机制 | 72. gcshape：泛型实例化内幕 |
| 泛型约束坑 | 73. 泛型约束设计与常见坑 |
| 断言性能 | 74. 类型断言与类型开关性能 |
| 方法集规则 | 75. 方法集：值接收者与指针接收者 |
| 泛型接口 1.24 解禁 | 76. 隐式接口与泛型接口解禁 |
| pprof 上线姿势 | 77. pprof 生产接入 |
| 火焰图判读 | 78. CPU 火焰图判读深水 |
| inuse vs alloc | 79. heap profile：inuse 与 alloc 语义 |
| block/mutex 剖析 | 80. goroutine、block 与 mutex 剖析 |
| trace 调度延迟 | 81. execution trace 与调度延迟解剖 |
| b.Loop 基准 | 82. benchmark 纪律与 b.Loop |
| benchstat 统计 | 83. benchstat 与统计显著性 |
| 内联预算 | 84. 内联内幕与预算模型 |
| PGO 实战 | 85. PGO 配置导向优化 |
| string/[]byte 零拷贝 | 86. 字符串与字节切片零拷贝 |
| JSON 提速 | 87. JSON 性能工程 |
| 结构体对齐 | 88. 结构体布局与内存对齐 |
| 表驱动进阶 | 89. 表驱动测试高级形态 |
| t.Context/t.Chdir | 90. t.Context 与测试生命周期 |
| synctest 并发测试 | 91. testing/synctest 气泡时钟 |
| fuzzing 纪律 | 92. fuzz 模糊测试纪律 |
| mock 策略 | 93. 接口替身与 mock 策略 |
| goleak 集成 | 94. goroutine 泄漏测试 goleak |
| t.Parallel 坑 | 95. t.Parallel 深水 |
| 测试分层 tags | 96. 测试分层与构建标签 |
| 覆盖率哲学 | 97. 覆盖率哲学与断言纪律 |
| 项目布局 internal | 98. 项目布局与 internal 纪律 |
| MVS 与 replace | 99. go.mod 深水：MVS 与 replace 陷阱 |
| go.work 多模块 | 100. workspace 多模块协作 |
| govulncheck | 101. govulncheck 与供应链安全 |
| 构建标签与交叉 | 102. 构建矩阵：tags、trimpath 与交叉编译 |
| 静态编译边界 | 103. 静态编译与 CGO_ENABLED=0 边界 |
| http.Client 调优 | 104. net/http 客户端生产调优 |
| http.Server 纪律 | 105. net/http 服务端纪律 |
| sql.DB 池参数 | 106. database/sql 连接池纪律 |
| slog 架构 | 107. slog 结构化可观测架构 |
| 体积与启动 | 108. 二进制体积与启动优化 |
| cgo/linkname 危险区 | 109. cgo 深水与 linkname 危险品 |
| Go 反模式清单 | 110. 反模式清单：评审红线 |
| Go 事故复盘 | 111. 生产事故复盘三例 |
| Go 评审清单 | 112. 代码评审清单与交付纪律 |

## 1. Go 1.22 循环变量语义变革与迁移

- 1.22 前 `for` 声明的变量整个循环共享一份：`go func(){ use(i) }()` 捕获的是
  变量地址，输出全为最后一次值——十年来的头号 Go 事故。1.22 起每轮迭代
  新建一份变量，闭包捕获天然正确。
- 语义由 go.mod 的 `go` 行决定，不是工具链版本：`go 1.21` + 1.22 工具链编译
  仍是老语义。灰度期最大坑：本地"好了"、CI 没改 go 行、线上照旧。
- 迁移清单：grep 全部循环内捕获变量的 `go`/`defer`/`func(){}`；老代码里
  `i := i` 的防御写法不再必需但无害；行为差异按"修 bug"记录，别当回归。
- 工具链联动：`go vet` 的 loopclosure 检查在 1.22 语义下不再误报；
  golangci-lint 的 `exportloopref` 已废弃（1.22 后全是误报），从配置里删掉。

```go
// go 1.21: 可能输出 3 3 3；go 1.22: 0 1 2
for i := 0; i < 3; i++ {
    go func() { fmt.Println(i) }() // 1.21 前必须 go func(i int)
}
```

- 性能：未被闭包捕获时零额外成本；被捕获时每轮一次分配（语义要求的拷贝），
  热路径高频捕获要自己掂量。

## 2. range over 整数与循环新形态

- `for i := range n` 产生 0..n-1 共 n 个值；n<=0 时循环体零次执行——
  语义是"n 个"不是"到 n"，code review 高频笔误（要 1..n 得 `range n-1` 加一）。
- 与 `for i := 0; i < n; i++` 编译产物基本等价（range 形态对 i 不可写，
  少一个可变状态，评审时 i 被循环体内改写的老 bug 直接消失）。
- 下一步演进是 range over func（3 节）：整数 range 是这套语法的先导。
- 机械替换有风险：老循环体内有 `i++`、`break` 依赖比较语义的，改写要过测试。

```go
for i := range 5 { // 0,1,2,3,4
    _ = i
}
```

- 建议统一：计数循环一律用 range 形态，`for i := 0; ...` 只留给需要改 i 的场景。

## 3. Go 1.23 range-over-func 迭代器模型

- 签名 `func(yield func(...) bool)` 即迭代器：`iter.Seq[T]`/`iter.Seq2[K, V]`。
  yield 返回 false = 调用方 break 了，迭代器必须立刻收尾返回。
- panic 穿透：yield 里 panic 会同时炸掉消费方与生产方——迭代器不做异常边界。
- yield 只能在迭代器调用栈内同步使用；把 yield 存下来异步调用 = 崩溃，
  这是库作者最容易踩的协议红线。
- break 后还继续分配资源/持锁不返回 = 泄漏；`defer` 收尾 + 收到 false 即
  return 是标准写法。
- 标准库组合子：`slices.All/Values/Collect`、`maps.Keys/Insert`，自定义容器
  提供 Seq 方法即接入整个生态（5 节选型）。

```go
func Countdown(n int) iter.Seq[int] {
    return func(yield func(int) bool) {
        for i := n; i > 0; i-- {
            if !yield(i) { return }
        }
    }
}
```

## 4. iter.Pull 与 push 迭代器的成本

- `iter.Pull(seq)` 把 push 迭代器翻成 `(next func() (T, bool), stop func())`：
  实现 = runtime 新 coro 原语（栈切换），不是 goroutine——但持有独立栈与状态。
- `stop()` 必须 defer：不调用则协程栈与中途状态（持有的锁、打开的句柄）泄漏；
  半途退出的算法尤其要管。
- next 非 goroutine 安全：coro 切换假设同 goroutine 调用，跨 goroutine 用
  直接爆炸；要跨 goroutine 就老实回 channel。
- 拉取模式的价值：peek 一眼前看、分批消费、两个迭代器交错——这些算法用
  push 写不出来；普通遍历别套 Pull（平白多一层栈切换）。

```go
next, stop := iter.Pull(slices.Values(xs))
defer stop()
first, ok := next()
```

- 深迭代嵌套（Pull 套 Pull）会放大栈分配；对热路径做 benchmark 再上。

## 5. 迭代器性能基准与选型

- 公开基准与自测共识：range-over-func 比直接 `for ... range` slice 慢
  （yield 是间接调用 + 闭包 + 可能的装箱），热路径可达 2-5x 差距；
  冷路径、IO 密集路径无感。
- `slices.Collect/Sorted` 等组合子有中间分配：迭代链越长越要数 allocs/op。
- 选型规则：库的公共 API、通用容器、需要组合的表达层 → Seq（互操作价值
  大于纳秒）；CPU 热点内部实现 → 具体类型 + slices 包直调。
- 自测纪律：b.Loop + benchstat（82-83 节），别信"迭代器零成本"的宣传稿。

```go
func bench(b *testing.B) {
    for b.Loop() {
        for v := range Values(s) { _ = v }
    }
}
```

- 决策记入 ADR：哪些包暴露 Seq、哪些只留具体类型，避免后期 API 撕裂。

## 6. Go 1.24 Swiss Tables：map 内存革命

- 1.24 以 `GOEXPERIMENT=swissmap` 引入、1.25 转默认无开关回退：map 底层从
  桶数组 + 溢出链换成 Swiss table（8 槽一组 + 控制字节，SIMD 加速探测）。
- 实测收益形状：小到中型 map 大幅省内存（公开案例高流量服务最高 -70%，
  官方口径内存约 -30%+），CPU 小幅改善；大量小 map 的微服务最受益。
- 迭代顺序依旧故意随机化：依赖遍历顺序的代码（ diff 输出、哈希落盘）
  依旧是定时炸弹，与实现无关。
- 迁移注意：直接操作 runtime map 内部（linkname/unsafe 黑魔法）的库会碎；
  `make(map, hint)` 预分配的收益在新实现下依然成立且更稳。

```go
// 好处：内存；代价：内部结构变了，黑盒依赖全废
m := make(map[string]*Node, 1024)
```

- 验证方式：升级前后 heap profile 的 `/memory/classes/.../map` 项对比 +
  RSS 曲线，别只看 benchmark。

## 7. weak pointer 与弱引用结构

- `p := weak.Make(&obj)` 得 `weak.Pointer[T]`：obj 不可达后 p.Value() 返回
  nil；不阻止回收、不可复活——finalizer 的"复活漏洞"在设计上不存在。
- 正当场景：canonicalization map（同值共享一份）、不保活的缓存、
  观察者注册表（回调方生实则通知）。
- 标准库没有 weak.Map：自己用 `map[K]weak.Pointer[V]` 组合，配合
  runtime.AddCleanup 在值死亡时顺手删 key（8 节），否则 map 只增不减。
- weak.Make 必须在拿到对象指针时立即调用一次，之后 Value() 可反复查询；
  对非指针值先取地址装箱。

```go
type Cache struct{ mu sync.Mutex; m map[string]weak.Pointer[*Item] }
// Item 死亡时 AddCleanup 里 c.m.Delete(key)
```

- 坑：weak 指针不能用来做"软缓存兜底重建"而不加锁——Value() 返回非 nil
  之后到使用之间对象仍可能被回收，拿到指针即强引用才能用。

## 8. runtime.AddCleanup 与 finalizer 退役

- finalizer 三宗罪：SetFinalizer 可能复活对象（依赖链整体延迟回收）、
  全局队列串行执行且延迟不可控、一对象一 finalizer 难聚合难取消。
- `runtime.AddCleanup(ptr, cleanup, arg)`：ptr 不可达即调度 cleanup(arg)，
  arg 由 runtime 持有所以闭包不捕获 ptr → 无复活；同对象可挂多个清理。
- 返回的 cleanup token 可 Stop（在对象死前取消兜底，比如已显式 Close）。
- 定位纪律：cleanup 只做"泄漏监控 + 兜底"，不承担资源管理主责——
  fd/conn/锁必须 defer Close；把清理寄希望于 GC = 面试级错误。
- 运行时自身在 1.24/1.25 已把大量内部 finalizer 迁到 AddCleanup；
  新代码一律 AddCleanup，SetFinalizer 只在兼容老接口时出现。

```go
f, _ := os.Open(path)
c := runtime.AddCleanup(f, func(p string) {
    log.Warn("file never closed", "path", p)
}, path)
defer func() { _ = f.Close(); c.Stop() }()
```

## 9. 泛型别名与 go.mod tool 指令

- 1.24 泛型类型别名转正：`type Set[T comparable] = map[T]struct{}`
  ——别名与原类型完全等价（含类型参数推导），泛型 API 的"导出简化名"
  不再需要额外具名类型。
- `tool` 指令进 go.mod（1.24）：`go get -tool golang.org/x/tools/cmd/stringer`
  后直接 `go tool stringer`；版本进 go.mod、随仓库走、CI 可复现——
  tools.go 空 import hack 正式退役。
- tool 依赖对构建产物零影响，但 go.sum/go.mod diff 必须提交，
  评审时别当垃圾改动 revert。
- 别滥用：会进 PATH 的只有 `go tool` 前缀；构建期代码生成（mock/stringer）
  与 lint 都适合，二进制发布工具仍走 `go install pkg@version`。

```go
type Set[T comparable] = map[T]struct{}
// go.mod: tool ( golang.org/x/tools/cmd/stringer )
```

- 多模块仓库把 tool 统一收敛到一个 dev-tools 模块，避免各模块版本漂移。

## 10. Go 1.25 容器感知 GOMAXPROCS

- 背景：k8s CPU limit=2 的容器里，老版本默认 GOMAXPROCS=宿主核数（如 64）
  → 调度器、GC worker 全开 → CFS 配额瞬间烧光 → throttling：P99 尖刺、
  "CPU 没跑满但请求假死"。
- 1.25 起默认感知 cgroup CPU 带宽限制（向上取整，下限 2）；显式路径
  （环境变量 GOMAXPROCS 或 runtime.GOMAXPROCS 调用）仍然优先——
  这是给接管者留的门，也是老代码屏蔽新机制的坑。
- 升级动作：删掉 automaxprocs 之类的 init 时代码，否则感知永远不生效；
  保留的环境变量与运行时调用要在文档里钉住"谁设置的、为什么"。
- 验证：1.25 的 runtime 提供 cgroup 检测信息（ContainerInfo 族），
  启动日志打一行实际 GOMAXPROCS + 检测依据，告警面板盯它。

```go
// 升级审计：找到并删除这些"好心"代码
_ = runtime.GOMAXPROCS(4) // 1.25 起这行会屏蔽容器感知
```

- 注意 Fractional limit（如 2.5）会取整到 3——容量规划按配额算，别按
  GOMAXPROCS 反推"我们的核数"。

## 11. Green Tea GC 实验特性评估

- 1.25 以 `GOEXPERIMENT=greenteagc` 提供实验 GC：标记工作按 span（对象
  所在内存范围）分组、以组为单位窃取——解决旧 GC 全局工作队列的争用与
  糟糕局部性（对象按随机地址序扫描，cache 命中惨淡）。
- 收益分布不均匀：小对象密集的分配型服务 GC 开销降 10-40%；大堆、
  少量大对象、GC 本来占比低的负载可能持平甚至小幅回退——上线前必须
  用自己的负载 A/B。
- 评估口径别只看 GC CPU：还要看 P99（mark assist 减少）、RSS（GC 频率
  变化）、以及 CPU 核数扩展性（多核机器收益更大）。
- 实验期纪律：Dockerfile 的 GOEXPERIMENT 必须走构建 ARG 且记录在发布说明；
  1.26 转默认后第一时间删掉开关（15 节治理）。

```dockerfile
# 1.25 试点：ARG 而不是写死 ENV
ARG GOEXPERIMENT=greenteagc
ENV GOEXPERIMENT=${GOEXPERIMENT}
```

- 出问题回退成本低（运行时行为，不改语言），但两套 GC 的调参结论
  不能混用（37-38 节按新 GC 重测）。

## 12. Go 1.26 新纪元：Green Tea 默认与 new 表达式

- Green Tea GC 转默认；`GOEXPERIMENT=nogreenteagc` 保留回退开关——
  这个开关是给"实测回退的负载"准备的，不是给保守派的。
- `new(expr)`：内置 new 接受表达式，`p := new(cfg.Load())` 直接装箱——
  取值、取指针一条路，少一层临时变量样板。
- `errors.AsType[T](err) (T, bool)`：泛型版 errors.As，样板
  `var target *T; errors.As(err, &target)` 退役（61 节机制不变）。
- `slog.NewMultiHandler(h1, h2...)`：一次日志扇出多目的地，多 handler
  手写包装的散装方案被收编。
- 运行时：cgo 调用开销下降约 30%（109 节重评）；`go mod init` 默认写
  上一个 Go 版本（新项目生态兼容优先，别奇怪为什么 go.mod 落后一版）。

```go
var target *MyErr
if e, ok := errors.AsType[*MyErr](err); ok { use(e) }
p := new(cfg.Load()) // new 表达式形态
```

- 升级 SOP 不变（16 节）：先工具链后 go 行，benchstat 全量对比。

## 13. encoding/json/v2 前瞻迁移

- v1 的历史包袱是设计缺陷不是 bug：默认 HTML 转义（`<` `>` `&`）破坏
  URL/富文本直出、所有 number 进 float64 丢精度、错误无位置信息、
  Marshal 行为被生态当事实标准。
- v2（1.25 起 `GOEXPERIMENT=jsonv2`）：默认不转义、选项模式
  （json.Indent/Escape/Deterministic）、错误带 offset、附 jsonschema 包；
  同开关下 v1 在幕后由 v2 实现兼容层——切换后跑全量测试 + 金丝雀对比。
- 依赖 v1 内部行为钉死的代码（转义结果、字段顺序做签名、HTML 转义当
  "免费 XSS 防护"）是迁移雷区，先审计再切。
- 第三方高速库（sonic/easyjson）对新运行时跟进有窗口期：升级 1.25/1.26
  前查它们 issue 列表，别让"升级提速"变"线上 500"。

```go
// v2 形态示意（实验期）
b, err := json.Marshal(v, json.EscapeHTML(false))
```

- 迁移路线：新代码直接面向 v2 API 写；老代码按"v1 兼容层验证一批、
  切 v2 一批"滚动，行为差异表进测试断言。

## 14. sync.WaitGroup.Go 与同步原语进化

- 1.25 新增 `wg.Go(f)`：Add(1)/Done 样板消失，`defer wg.Done()` 的
  忘写事故（最经典：Done 放在错误分支后）从语法上根除。
- 语义细节：f 里 panic 直接 crash 进程（与手动 Add/Go 一致），不吞不转
  error——需要 panic→error 的边界自己 recover（66 节）。
- 进化脉络要有地图：1.19 泛型原子类型 → 1.20 `signal.NotifyContext` →
  1.24 `sync.Map` 重写 → 1.25 `WaitGroup.Go`；评审标准跟着版本更新。
- f 里再 `go` 出去的子 goroutine 不计入 wg：生命周期责任不变（55 节），
  "wg.Go 完就安全退出"是错觉。
- Wait 没有超时/ctx 版本：要"限时等待"换 errgroup（54 节），别硬凑。

```go
var wg sync.WaitGroup
for _, t := range tasks {
    wg.Go(func() { process(t) }) // 1.25+
}
wg.Wait()
```

- 评审红线：还有手写 `wg.Add(1); go func(){ defer wg.Done() ... }` 的新代码
  一律要求改写（除非版本 <1.25）。

## 15. GOEXPERIMENT 治理与实验开关纪律

- 机制：构建期环境变量，走"实验 → 转正或移除"生命周期（1.24 swissmap/
  htmap → 1.25 默认；1.25 greenteagc/jsonv2；历史上 cinis/arenas 直接
  死掉）——实验特性不在兼容承诺内，补丁版本行为可变。
- 生产纪律：禁止在散落的 Dockerfile/Makefile 里写 GOEXPERIMENT；统一收敛
  到基础镜像 ARG，值进发布说明与构建元数据（可追溯到二进制）。
- CI 矩阵：实验开/关双跑全量测试 + benchstat——实验特性下测试挂了
  要能一眼看出是开关导致的。
- 开关回收：每版 release notes 扫一遍 GOEXPERIMENT 段，转正的立刻删开关
  （留着无害但制造"我们在用什么"的假信息）；被移除的开关会让构建直接失败。

```bash
# 基础镜像里集中管理
ARG GOEXPERIMENT=""
ENV GOEXPERIMENT=${GOEXPERIMENT}
# CI: GOEXPERIMENT="" 与 jsonv2 双矩阵
```

- 团队文档维护一张"实验特性状态表"：用了什么、为什么、哪个版本到期。

## 16. 版本升级纪律与兼容承诺

- 兼容承诺范围要说准：语言与标准库 API 向后兼容（Go 1 兼容承诺），
  性能与内存形态明确不承诺——升级后吞吐回退是正常风险，需要
  benchstat 基线护航。
- 没有 LTS：只有最近两个大版本收安全补丁（今天 = 1.25/1.26），
  升级节奏必须固定为半年一跳，欠两版就是裸奔。
- 每版必读清单：release notes 的 Compiler/Tooling/Runtime 段 +
  "Reclassification"（工具行为变化）+ GOEXPERIMENT 段；跳着读必踩坑。
- toolchain 机制（1.21+）：go.mod `toolchain go1.26.x` 让不同开发者本地
  自动拉齐；CI 反向用 `GOTOOLCHAIN=local` 锁死防意外下载漂移。
- 升级 SOP：先升 toolchain 行（编译器新、语言语义旧）→ 全绿后升 `go` 行
  （语言语义切换点，重点回归闭包/循环变量相关测试）→ 基线对比 → 灰度。

```bash
go mod edit -toolchain=go1.26.0  # 第一步
go test ./... && go vet ./...
go mod edit -go=1.26.0           # 第二步（语义切换）
```

- 回归预算：每次升级准备一个"性能/内存对比报告"，P0 回退当场决策回滚
  还是调参，不留悬案。

## 17. GMP 模型真实结构

- G（goroutine）：含 gobuf（SP/PC 恢复现场）、atomicstatus（_Grunnable/
  _Grunning/_Gwaiting/_Gsyscall 等）、栈描述符；栈与 g 结构体是两块内存。
- M（OS 线程）：真正跑代码的实体；可远多于 P——阻塞在 syscall/cgo 的 M
  不占 P 配额，但吃线程数（默认上限 10000，超限 throw）。
- P（调度上下文）：GOMAXPROCS 个；持有本地 runq（256 槽环形队列）、
  mcache（无锁小对象分配）、timers、GC 状态——P 是一切"每核资源"的锚。
- schedt 全局：全局 runq（带锁）、空闲 M/P 缓存、sysmon 状态——
  全局队列是公平性兜底，不是主路径。
- 事故视角：Load 高 CPU 低 = 大量 G 卡在 chan/syscall/锁，不是调度器
  "不行"；先看 goroutine profile 分布再谈优化（25 节）。

```go
import _ "net/http/pprof"
// 事故第一跳: curl :6060/debug/pprof/goroutine?debug=2
```

- 调参直觉：GOMAXPROCS=配额核数（1.25 前手动对齐 cgroup，10 节）、
  goroutine 数量十万级正常、线程数千级要警惕 cgo。

## 18. 调度循环与 runnext 语义

- schedule() 找下一个 G 的顺序：本地 runnext → 本地队列（一次取一批
  减少锁频次）→ 全局队列（每 61 次调度强制查一次，防全局饿死）→
  netpoll → 偷其他 P。
- runnext 槽：刚被唤醒（goready）的 G 放这里，下轮调度优先执行——
  局部性优化（唤醒者与被唤醒者往往共享 cache）。但它是弱保证：新的
  runnext 会把旧的挤回本地队列。
- 61 = 素数目的：防与偷取节奏共振形成系统性偏袒。
- 业务含义：unblock 链长的工作流（chan 交接流水线）受益于 runnext；
  而锁竞争激烈的尾延迟尖刺常来自全局队列与临界区——trace 验证（81 节）。
- LockOSThread 的 G 绕过本地队列直接与 M 绑定（runtime 机制），
  使用场景：GUI/OpenAL/C 库亲和——滥用会破坏调度均衡。

```go
// 观察 runnable 等待: /sched/goroutines-goroutines 与 trace 泳道
// pprof goroutine?debug=2 里数 "chan receive" 栈
```

- 实务教训：微基准里唤醒-唤醒的模式在 trace 上"顺风顺水"，生产混合
  负载下公平性兜底路径（61 次、全局队列）才显形——别用微基准给调度下结论。

## 19. work-stealing 与全局队列公平性

- 本地队列取头（FIFO 保公平），窃取者从尾偷走一半（偷的是更新的一半）：
  4 轮随机起点尝试；偷空还查 netpoll、两轮失败后顺带扫 timer。
- 自旋 M 有上限（约 GOMAXPROCS 的一半）：避免"全核空转找活干"烧 CPU——
  偷取是节能模式不是全力模式，负载轻时唤醒延迟可感知。
- 本地队列 256 溢出走全局队列（加锁）：突发几十万 runnable G 时全局锁
  成为热点——trace 里表现为调度耗时块。
- 理解"随机起点"：防多个窃取者扎堆同一 P；哈希式分散是设计出来的。
- 实务：短任务风暴（每任务 µs 级）会让 work-stealing 空转收益归零，
  这类负载先批处理（一次领一批）再谈调度。

```go
// 短任务风暴的自救：一次领一批，让偷取者有东西可偷
for batch := range batches { // 每批 N 个
    for _, t := range batch { wg.Go(func() { do(t) }) }
}
```

- P 利用率条纹（有的 P 忙有的闲）在 trace 视图里是 work-stealing 失效的
  直观证据：通常是任务粒度太细或临界区太长。

## 20. 异步抢占：信号机制深水

- 1.14 前只有协作式抢占（函数序言栈检查）：`for {}` 紧循环零抢占点——
  GC 的 STW 被卡死数秒是历史级事故，Go 由此背上"GC 停顿大"的陈年冤名。
- 1.14 起异步抢占：sysmon 发现 G 运行超 10ms（forcePreemptNS）→
  preemptM 向线程发 SIGURG → 信号处理器在安全点注入 asyncPreempt 调用，
  把 G 打回 runnable。
- 安全点限制：运行时临界区、写屏障执行中、nosplit 函数不可抢占；
  条件随版本微调——"纯计算永不被卡死"从 1.14 起才是事实。
- SIGURG 是复用的（与 race 检测/raise 共用）：库代码注册信号处理器
  覆盖 SIGURG = 与运行时打架，症状是偶发卡死或崩溃；信号处理一律
  走 os/signal，别碰 syscall.Signal 汇编层。
- Windows 用 SuspendThread 注入，语义略异但效果等价；cgo 长调用期间
  M 不归 Go 管，无法抢占——cgo 密集服务的 10ms 目标本来就不成立。

```go
// 诊断：STW 应该 <1ms，超过就要查
// GODEBUG=gctrace=1 两个 STW 段；runtime/metrics: /gc/pauses:us
```

- 现实基线：现代版本两次 STW 合计常见 <100µs；毫秒级 STW = 有阻塞源
  （mlocked 线程、大量线程信号风暴），不当"正常波动"放走。

## 21. sysmon：retake 与 forcegc

- sysmon 是无 P 无 G 的独立监控线程，周期 20µs 起自适应（忙密闲疏，
  最长 10ms）；职责：retake 抢占/收 P、forcegc、netpoll 兜底、死锁检测。
- retake 两条线：M 卡在 syscall 超 10ms → 把 P 交出去给别人用（23 节）；
  G 连续运行超 10ms → 异步抢占（20 节）。
- forcegc：两分钟没有 GC 就强制来一轮——纯闲服务也定期有 GC 尾巴，
  gctrace 里看到周期 120s 的记录别惊讶。
- 死锁检测的边界：所有 goroutine 都睡着才触发 "all goroutines are
  asleep"——只要有任何 netpoll 活动（一个 listener 挂着）就永不触发；
  "半死服务"必须靠外部健康检查，运行时救不了你（26 节）。
- cgo 视角：cgo 调用按 syscall 计——长时间 cgo 调用会触发 P handoff，
  线程数随之膨胀（默认 10000 上限，超限 throw "too many threads"）；
  SetMaxThreads 调低让事故早暴露。

```go
// cgo 重服务的线程治理
runtime/debug.SetMaxThreads(4000) // 比默认早报警
```

- 观测：`GODEBUG=schedtrace=1000` 看周期性调度快照，配合 gctrace 读
  sysmon 行为影响。

## 22. goroutine 栈伸缩与热分裂

- 初始栈 1.19 起按历史均值自适应（典型 ~8KB，老版本 2KB）；不够时
  morestack 把栈倍增并把整栈（含指针）拷到新内存——goroutine 地址不稳定，
  任何缓存 goroutine 栈指针的行为都是雷。
- 热分裂（hot split）：栈深在扩容阈值附近震荡 → 每次调用都触发拷贝；
  runtime 有 slack 缓冲，但递归+大局部变量仍可稳定复现。症状：火焰图里
  morestack/copystack 占比可观。
- 缩栈：GC 时用量不足 1/4 则减半——栈抖动双向存在，深递归热点要固定
  深度测。
- `//go:nosplit` 跳过序言检查换取确定性，但硬上限（约 700B 余量）：
  超限编译报错是好事，绕过（加 big frame 中转）就是埋雷；只许运行时与
  极端热路径使用，业务代码见到就拒。

```go
//func f() // 禁止业务代码出现:
//go:nosplit
func f() { ... } // 评审直接打回，除非有基准证明
```

- 大局部变量（>64KB 数组）会直接压大帧或逃逸堆：热路径把巨型 buffer
  变成堆分配 + 池化（41 节），比指望栈强。

## 23. 系统调用路径：P 的让渡与回收

- 两级入口：`entersyscall`（可能很快回来）先保留 P，切走 G 的状态；
  `entersyscallblock`（明确慢/阻塞）直接交出 P。编译器按 syscall 类型
  选择，库作者用 RawSyscall 族可干预。
- sysmon 判定：P 闲置超 10ms（前 20µs 宽限）才被 handoff 给其他 M——
  快 syscall 全程独享 P，无 handoff 抖动；慢 syscall 让 P 不闲置。
- exitsyscall 回收路径：优先拿回旧 P（cache 还热）→ 拿任意空闲 P →
  都失败则 G 进全局队列、M 休眠。风暴期 syscall 返回的排队是延迟尖刺源。
- 网络系统调用是另一条路：fd 非阻塞 + EAGAIN 后 gopark 进 netpoller
  （24 节）——不占 P 不占 M，这是 Go IO 并发密度的根基。

```go
// 阻塞 C 调用要意识到：P 被让渡、M 被占用、可能被 sysmon handoff
C.slow_compute(buf) // 期间本 P 已服务他人
```

- 治理：高频短 syscall 服务对 GOMAXPROCS 敏感（1.25 前手动对齐 cgroup）；
  cgo 长调用密集时线程膨胀与 P handoff 同时发生，先测再调。

## 24. netpoller 网络轮询器深水

- 统一抽象：Linux epoll、BSD/macOS kqueue、Windows IOCP（1.22 切换完成）
  之上是统一的 pollDesc（fd 的读/写等待组 rg/wg）；net 包把 fd 设为
  非阻塞，EAGAIN 后 gopark 进 pollDesc。
- 就绪注入时机：调度循环兜底 poll（找活干时）、sysmon 周期 poll（闲时
  兜底）、M 休眠前阻塞 poll（省 CPU）——三级驱动保证低延迟与低开销的平衡。
- timer 与 netpoll 融合：findRunnable 顺带检查本 P 的到期 timer——
  高频 ticker 会实实在在影响调度路径。
- fd 泄漏的形态：http Response.Body 不 Close、文件句柄未关 → fd 数
  线性上涨，最终 accept/read 失败 "too many open files"；
  lsof + pprof goroutine（卡在 read 的栈）定位。

```go
// 事故模板：只处理 err 就 return，Body 半路丢弃
resp, err := client.Do(req)
if err != nil { return err }
defer resp.Body.Close() // 忘了它，几小时后 fd 耗尽
```

- 与 cgo 的交互：C 库的阻塞 fd 不在 netpoller 里——集成时要么 goroutine
  包阻塞读，要么注册进 runtime poller（高级玩法，慎用）。

## 25. goroutine 泄漏取证

- 症状三联：RSS 缓涨、goroutine 数线性增长（监控必打点）、重启即"治好"。
  单个 goroutine 轻量，但泄漏者常占着锁/连接/缓冲不还。
- 取证标准动作：`curl :6060/debug/pprof/goroutine?debug=2` 拿全量栈 →
  按"同构栈"聚类数数量 → 分钟级无变化的栈即嫌疑犯 → 溯源到代码看退出
  路径缺了什么。
- 元凶清单：for-select 缺 `ctx.Done` 分支；unbuffered chan 写无人读/
  读无人写；WaitGroup 计数不归零；ticker 不 Stop（1.23 前泄漏、之后仍
  空转）；孙 goroutine 无人等。
- 压测后 dump 对比是确认手段；测试期拦截交给 goleak（94 节），
  上线前就把泄漏挡在 CI。

```go
// 修复模板：后台 goroutine 必有退出信号 + 有人等它退出
done := make(chan struct{})
go worker(ctx, done)
defer close(done) // 或 <-done 等待真正退出
```

- 基线纪律：每个服务的 goroutine 基线数写进文档，告警按"偏离基线 X%"
  而不是绝对值——不同服务的正常水位天差地别。

## 26. 死锁诊断与运行时解剖

- 运行时死锁检测的条件极苛刻：所有 goroutine 都睡、无 netpoll 活动才触发
  "all goroutines are asleep - deadlock!"——只要挂着一个 listener，
  检测永不启动；"半死"服务必须靠外部健康检查（超时探针）兜底。
- panic 与 runtime throw 是两回事：`concurrent map read/write`、
  `all goroutines asleep`、`too many threads`、栈溢出都是 throw——
  recover 救不了，进程直接退出并打印全 goroutine 栈。
- `GOTRACEBACK` 梯度：none（只打印 throw）→ single（默认）→ all
  （所有 goroutine 栈）→ system（含运行时栈与状态）→ crash（再 core dump
  交给外部调试器）。事故取证环境常备 `GOTRACEBACK=crash`。
- 取证三件套：goroutine profile 看谁等谁、mutex profile 看持锁者栈、
  trace 看时序——拼出等待图，任何环 = 潜在死锁，一律加超时降级为可观测。

```go
select { // 等待一律带超时: 死锁降级为可观测
case v := <-ch:
    use(v)
case <-time.After(5 * time.Second):
    log.Error("chan wait timeout")
}
```

## 27. Go 内存模型核心条款

- 承诺刻意极弱：无数据竞争的程序"看起来顺序一致"；存在竞争 = 未定义，
  不是"最多读到旧值"——map 并发读写直接 fatal，整数竞争也可能撕裂。
- happens-before 边清单（背下来）：`go` 语句先于新 goroutine 首条语句；
  chan 发送先于对应接收完成；chan close 先于收到零值；Mutex/RWMutex
  Unlock 先于后续 Lock；Once.Do 返回先于任何 Do 返回；sync/atomic 全体
  按顺序一致对待（1.19 文档明示）。
- WaitGroup.Wait 返回先于 Wait 之后任何读——把并发写结果安全交接给
  waiter 是 HB 保证的，不需要额外锁。
- "看着没锁也没事"的经典错觉：写方 goroutine 写完 `go` 出读方——go
  语句本身就是 HB 边，这是安全的；但反过来用共享变量 + sleep 等待
  就已经踩进未定义。
- 纪律：任何跨 goroutine 的共享读写，要么套 HB 边（chan/锁/atomic/
  Once/Wait），要么承认在设计上偷懒并准备好偶发灵异事故。

```go
// 合法交接: go 语句构成 happens-before
data := compute()
go func() { consume(data) }() // data 写入先于 consume
```

## 28. sync/atomic 内存序纪律

- Go 只有序列一致一种内存序：不需要学 C++ 的 acquire/release 词汇，
  代价是所有原子操作带全局顺序直觉——别按"弱序可优化"设计数据结构。
- 一律用泛型原子类型（1.19+）：atomic.Int64/Bool/Pointer[T] 替代
  裸字段+unsafe 转换；自带 8 字节对齐与 noCopy，32 位坑自动消失。
- CAS 循环模板与边界：`for { old := x.Load(); if x.CompareAndSwap(...) }`
  ——忙等要让出（runtime.Gosched 或退化为锁）；ABA 在带 GC 的 Go 里
  基本消失，但 sync.Pool 槽复用会让"同地址不同对象"复活（35 节）。
- 原子不是轻量锁：保护的是单字，复合不变式（len 与 buf 一致）原子
  根本表达不了——硬上等于把竞态藏得更深。

```go
if inFlight.Add(1) > limit { // 计数闸门
    inFlight.Add(-1)
    return ErrBusy
}
defer inFlight.Add(-1)
```

- 评审要点：看到裸 `int64` 被多 goroutine 读写（无锁无原子）= 立即打回，
  32 位平台连读写本身都不是原子的。

## 29. race detector 内部与盲区

- 实现是 ThreadSanitizer 的移植：shadow memory（约 4x 内存）+ vector
  clock 精确记录 happens-before，不做启发式——报出来的一定是真竞争
  （对已执行路径而言），代价是 CPU ~2-20x、内存 ~5-10x。
- 盲区一：只报跑到的交错。测试覆盖率决定发现率，永远测不到的路径
  静默；race 通过 ≠ 无竞态，是无"被证明的"竞态。
- 盲区二：goroutine 上限 8192——超限时新 G 直接脱检；大压测可能
  悄悄越过这条线，压测时监控 goroutine 数。
- 盲区三：cgo/非 Go 内存无覆盖；对"并发正确但顺序错误"的逻辑 bug
  （如先扣款后检查）也无感——它是竞态检测器不是正确性证明器。
- 生产姿势：CI 全量 `-race` + 压测环境挂 -race 跑影子流量；发现竞态
  当 P0 处理，修复必须附回归测试（用计数替身/通道同步复现交错）。

```bash
go test -race ./...
go run -race -gcflags=all=-d=checkptr ./cmd/load # 压测影子流量
```

- 别用锁大小调优推理 race 开销；优化 race 构建性能没有意义，
  它只服务于找错。

## 30. 数据竞态的隐身形态

- 全局单例懒初始化：`if instance == nil { instance = New() }` 无锁——
  双写、半初始化对象被另一 goroutine 读走；改 sync.Once 或
  init 期初始化 + 只读。
- `m[k]++`、`s = append(s, x)`、结构体多字段更新：复合操作天然多步，
  map 更是直接 throw；"只有一个 goroutine 写"但读者未同步 = 竞态。
- 闭包/回调捕获：time.AfterFunc 回调与主流程共享 request 对象；
  http handler 把 request 域对象交给后台 goroutine 继续用——
  生命周期越过请求边界的共享要显式交接（拷贝或 immutable）。
- 测试代码自己带毒：t.Parallel 子测试共享同一 map/slice 表格并原地
  清理；parallel 的包级全局 fixture 是 -race 误报大户（其实是真报）。
- "偶尔 fatal: concurrent map read and map write"不是玄学：就是竞态，
  加重试是掩耳盗铃——加锁、分片、或改用同步容器（34 节）。

```go
var once sync.Once
func Config() *Config {
    once.Do(func() { cfg = load() }) // 懒初始化唯一正确形态
    return cfg
}
```

## 31. 安全发布与零值语义

- 发布 = 引用跨 goroutine 可见且对象完整可见；只有经过同步原语
  （chan 收发、锁临界区、Once、atomic 写、go 语句）才算发布——
  先塞进全局变量再起 goroutine 去读就是竞态。
- 包级初始化天然安全：所有 init 按依赖序在 main 前串行跑完，
  init 写 + 并发读（不再写）构成 HB 链——只读全局配置的正确姿势。
- 零值可用是发布纪律的地基：sync.Mutex/sync.Once/bytes.Buffer 等
  零值即可用 → 不需要构造步骤 → 可以"声明即发布"；自研类型也应
  追求零值可用（避开"先 New 再共享"的两步雷）。
- 部分初始化陷阱：先把指针发布出去再逐字段填——就算每个字段有锁，
  无锁读者的读仍与写竞争；正确顺序：完全构造 → 一次原子发布。
- 装箱进 interface、塞进 map/slice 都不构成发布（容器本身的修改要
  同步）；发布是"对象引用+内容"一起过同步边。

```go
type Server struct{ mu sync.Mutex } // 零值可用
s := &Server{}
reg.Store(s) // atomic 发布: 之后任何 Load 到的 s 都是完整的
```

## 32. false sharing 与缓存行填充

- 缓存行 64B：两个核各写同一行里相邻字段 → 行在核间乒乓，
  原子操作表面无锁，总线级在互相残杀——`[N]atomic.Int64` 分片计数
  是教科书受害者（相邻元素同行）。
- 手法：字段间垫 pad（`_ [64 - unsafe.Sizeof(...)%64]byte`）或让每
  分片独占一行；验证用 unsafe.Alignof/Offsetof 打印布局，
  Linux 上 perf c2c 直接显示行争用。
- 分片计数的正确形态：每个分片是一个含 pad 的结构体数组，
  而不是原子类型裸数组；汇总时遍历相加，写侧只碰自己的分片。
- 别过早优化：先证明是带宽/争用问题（perf/mutex profile）再 pad；
  大多数业务代码碰不到这个层面，运行时与高性能库才会。
- 标准库内部大量手工 pad（mheap、sched 字段），语言层没有
  自动反 false sharing 承诺——一切以手工布局为准。

```go
type shard struct {
    n    atomic.Int64
    _pad [56]byte // n(8B) 补齐到 64B 缓存行
}
var stats [64]shard // 各核写各自行, 汇总时求和
```

## 33. Mutex 与 Once 内部机制

- Mutex 两模式：正常模式先自旋（active 4 轮 + 被动自旋）再排队，
  解锁时"叫醒但不移交"（被唤醒者要重新抢，新来的可能插队——吞吐好
  但饿死风险）；等待超 1ms 切饥饿模式：Unlock 直接把锁移交给队首，
  FIFO 公平，队首等待 <1ms 时退回正常。
- 底层 sema：全局 semtable 按信号量地址哈希分桶排队（不是每锁一个
  队列）；Unlock 唤醒后等待者 `goready`。锁竞争的诊断不是猜——
  mutex profile 直接给出持锁者栈（80 节）。
- 不可重入：同 goroutine 二次 Lock = 自锁死，且全体其他 G 活着时
  死锁检测不触发；需要重入说明设计错了（拆函数/传状态）。
- Once：快路径 atomic load done，慢路径锁 + double-check；
  f 执行 panic 也算 done（已执行过）。复制含锁结构 = 复制锁状态，
  go vet copylocks 抓——sync 家族内嵌 noCopy 就是为此。
- RWMutex：写者到达后新读者排队（防写饿死）；核多读少的锁反而让
  cache line 全核翻腾——实测常输给"读走 atomic 快照 + 写加锁"。

```go
mu.Lock()
defer mu.Unlock() // defer 不是装饰: 提前 return 路径全靠它
critical()
```

## 34. sync.Map 演进与 htmap 新形态

- 老实现（read/dirty 双 map）：只适合两种形态——key 集合稳定后读多
  写罕，或不相交 key 各写各的；通用缓存场景它比"一把锁 + 普通 map"
  更慢更贵。历史结论要背：默认别用 sync.Map。
- 1.24 引入 htmap（hash-trie）实验（GOEXPERIMENT=htmap），1.25 转
  默认实现：读写混合负载大幅改善、扩容代价平滑——旧基准全部作废，
  选型前用 1.25+ 重跑自己的负载。
- 依旧成立的纪律：value 不可变（读到的对象当只读，改 = 与其他读者
  竞态）；需要原子更新用 LoadOrStore/CompareAndSwap 语义而不是
  Load 后改。
- Range 是弱一致遍历（新老实现都不是快照语义）：遍历中插入的 key
  可能见也可能不见——别在 Range 里做全局不变式判断或计数对账。
- 每种形态都要有真实压测：分片锁 map（N 个锁各管一段）、atomic
  指针 + Copy-on-Write（读极多写极罕）、htmap——按读写比选。

```go
v, loaded := m.LoadOrStore(k, newVal) // 原子语义, 不是 Load+Store
if !loaded { use(v) }
```

## 35. 无锁结构纪律与原子指针

- Go 里无锁的实际收益窗口很窄：chan/mutex/atomic 已被运行时打磨，
  自写 MPSC 队列 + 接口装箱的版本经常更慢——先 benchmark 再动工，
  "无锁更快"是 C++ 直觉不是 Go 事实。
- publish 模式是正路：完全构造新对象 → atomic.Pointer.Store 发布 →
  读者 Load 后整个对象视为不可变（31 节安全发布）；Copy-on-Write
  配合这个模式覆盖"读多写罕"场景。
- ABA 与 GC：对象不被复用地址让经典 ABA 消失，但 sync.Pool 会让
  "同槽不同对象"复活——依赖地址比较的无锁算法遇 Pool 要重审。
- 门槛：复杂无锁结构（多生产者队列、跳表）上线前要过三关——
  -race 压测、goleak、形式化或模型检查（TLA+）；评审成本远高于
  锁版本，写不出正确性论证就退回锁。
- 运行时自用的无锁都在 runtime 包里且带详尽注释（如 timers、
  semtable）——抄实现之前先抄它的测试与证明文档。

```go
var snap atomic.Pointer[Config]
func Update(c *Config) { snap.Store(c) } // 构造完一次性发布
func Get() *Config { return snap.Load() } // 读者只读
```

## 36. GC 架构：三色标记与混合写屏障

- 标记流程：STW(sweep termination) → 并发标记（三色抽象）→
  STW(mark termination) → 并发清扫；两次 STW 现代版本合计常见
  <100µs——"Go GC 慢"的印象来自 1.10 之前，别再拿它当结论。
- 混合写屏障（1.8+，Yuasa 删除屏障 + Dijkstra 插入屏障）：并发标记
  期间 mutator 改指针不用重新扫描栈——屏障的赋税在写入路径上，
  写密集负载能感知到。
- 并发标记的人手：25% GOMAXPROCS 的后台 GC worker（专责标记）+
  mark assist——分配太快的 goroutine 被当场抓壮丁替自己买单，
  分配大户的延迟尖刺多来自 assist（43 节）。
- 清扫是惰性的：分配路径上按页还 sweep 债务；GC 后立刻的大分配
  峰值可能触发额外清扫工作，表现为"GC 后卡一下"。
- 观察工具链：GODEBUG=gctrace=1 每轮一行（两个 STW 段、assist 时长、
  堆大小）；/gc/* runtime/metrics 族做长期监控（43 节读法）。

```bash
GODEBUG=gctrace=1 ./svc 2>&1 | grep gc
# ... 0.021+2.4 ms clock, 42% goal ... 每字段都有官方文档
```

## 37. pacer 数学与 GOGC 调参

- 触发公式：下一轮目标堆 = 存活堆 × (1 + GOGC/100)；GOGC=100 即
  "堆翻倍时开 GC"。pacer 的任务是让标记结束时恰好堆到目标——
  不提前（浪费 CPU）不超调（破目标）。
- 调节手段：触发时机（目标比例）+ assist 比例（谁替 GC 干活）；
  两个旋钮耦合，GODEBUG=gcpacertrace=1 可看内部决策（调试用）。
- 调参直觉：GOGC 调大（200-400）→ GC 次数减半、CPU 省、常驻内存
  涨；内存富余的服务优先调 GOGC 而不是减分配改代码（性价比）。
- GOGC=off 禁止裸用（必爆 OOM），只许与 GOMEMLIMIT 成对出现
  （38 节批处理模式）；GODEBUG 或环境变量全局生效，进程内运行时
  调试接口可改。
- ballast 反模式退役：1.19 前拿大字节数组骗 pacer 抬目标，
  现在用 GOGC/GOMEMLIMIT 直接表达意图——见到 ballast 代码就是
  待迁移标记。

```go
debug.SetGCPercent(300) // 内存富余服务: 少 GC 换 CPU
// 留 GOMEMLIMIT 保底, 两旋钮永远成对评审
```

## 38. GOMEMLIMIT 生产用法与死亡螺旋

- 覆盖范围要说准：runtime 管理的一切（堆 + 栈 + 全局/运行时结构），
  不含二进制映射、cgo 分配、内核态开销——容器预算要给这三块留位。
- 标准配方：GOMEMLIMIT = 容器内存限额 × ~90%，GOGC 保持 100；
  达到 limit 前 pacer 会自动提前/加密 GC——本质是"内存换 GC 次数"
  的自动挡。
- 死亡螺旋形态：存活堆本身逼近 limit → GC 连轴转收益归零 →
  GC limiter 介入（GC 最多吃 50% CPU）→ 吞吐直接砍半但进程活着：
  表现为"服务慢两倍"，不是 OOM。红灯指标：/gc/limiter/last-enabled:
  cpu-fraction（1.21+）非零即触发过。
- 与 OOMKill 分清：cgroup 超限被内核杀 = GOMEMLIMIT 没覆盖住的
  部分（cgo/二进制/漏设）——诊断顺序：limiter 计数 → RSS 曲线 →
  live heap 是否真泄漏（45 节）。
- 批处理模式：GOGC=off + GOMEMLIMIT=X 让 job 用满预算、全程不浪费
  GC——吞吐最优组合，只在一次性进程里用。

```bash
GOMEMLIMIT=4GiB GOGC=100 ./svc   # 常驻服务标准配方
GOMEMLIMIT=8GiB GOGC=off ./batch # 批处理吞吐配方
```

## 39. 逃逸分析深水：判定规则与验证

- 逃逸铁律清单：返回局部变量指针 → 堆；装箱进 interface → 堆
  （fmt/log 全家都是装箱大户）；发送到 channel 的指针 → 堆；
  闭包按引用捕获且逃逸 → 堆；存入被共享的结构/map/slice → 堆；
  reflect/unsafe 碰过 → 默认堆。
- 验证唯一手段：`go build -gcflags='-m -m' ./...`，两个 -m 输出
  理由链（"moved to heap: ... flows to ..."）；光猜必错，理由链
  会推翻你的直觉（尤其是内联联动）。
- 逃逸分析跑在内联之后：小函数被内联后实参不再逃逸——
  "把函数砍小"经常是免逃逸的正解（84 节预算）。
- 版本漂移：逃逸判定随版本精化（1.21/1.22 有过行为变化），
  热路径优化结论要 pin Go 版本，升级必重测（16 节 SOP 一环）。
- 常见误判：sync.Pool 里的对象本来就在堆（池化是复用不是免堆）；
  `&s` 传给不逃逸的函数不一定堆分配——让 -m 说话。

```bash
go build -gcflags='-m -m' ./hotpath/... 2>&1 | grep -E "escapes|moved"
```

## 40. 减分配实战：热路径零分配手法

- 预分配三件套：make slice 带 cap（append 级数扩容是分配大户）、
  map 带 hint（Swiss 实现下依然成立）、strings.Builder.Grow。
- 热路径禁装箱：fmt.* / log.* / error 拼接 / interface 参数——
  换 strconv.AppendInt/AppendQuote 写进 []byte 复用缓冲；
  错误信息在冷路径构造（err 才构造）而不是每次判断都拼。
- 复用：sync.Pool 挠痒（41 节）、值接收者小结构、避免返回 slice
  （迭代器回调 / out 参数写调用方缓冲）。
- 量化纪律：b.Loop + reportAllocs 拿 allocs/op，热路径给硬预算
  （如每请求 <10 allocs），预算超了先查逃逸再查 fmt。
- 组合子警告：slices.Collect/Sorted、strings.Join 中间分配；
  热路径手写循环 + 预分配 buffer。

```go
var buf []byte // 池化或预分配
buf = strconv.AppendInt(buf[:0], n, 10)
buf = append(buf, ','...)
sink.Write(buf) // 0 次分配的编码路径
```

## 41. sync.Pool 池化纪律

- 机制：每 P 一个 private + shared 双端链表；Get 优先本 P private →
  本 P shared → 偷其他 P → victim 缓存（1.13 起：GC 时清一代、
  存活一代，且清池不再引发 STW）→ New。
- 定位：压住"短命大对象"的 GC 峰值（buffer/编解码器/解析器），
  不是对象池框架：无上限、无监控、无引用管理——要对象池自己写。
- 头号事故：对象带状态没复位就 Put → 下个请求读到上个请求的脏数据
  （buffer 未清长度、map 残留旧 key）——Put 前复位是铁律，
  复位逻辑要跟构造逻辑一起评审。
- 池驻留抬峰值：两轮 GC 才清光 → 大 buffer 池让 RSS 上台阶；
  尺寸分档（按大小多个池）或给池化对象设"超过 X 释放"策略。
- Get 的对象出了作用域就 Put（defer），别攒着批量还——攒 = 等于
  自己重新发明一个没并发的池。

```go
b := bufPool.Get().(*bytes.Buffer)
b.Reset() // 铁律: Get 之后立刻复位(防串味)
defer func() {
    if b.Cap() <= 64<<10 { bufPool.Put(b) } // 超大不还池防驻留
}()
```

## 42. 大对象、size class 与堆布局

- 小对象（≤32KB）按 67 级 size class 走 mcache（P 本地无锁）→
  mcentral（按 class 加锁）→ mheap（页分配器）；>32KB 直接 mheap
  大对象路径——大对象分配天然贵且碎片化。
- 踩档浪费：分配 68B 落到 72/80B 档，浪费 6-17%；批量小对象聚成
  大 slice 一次分配（指针或索引切片）能显著省 RSS 与分配次数。
- append 的几何扩容 + class 对齐：预估不准时 cap 序列 1,2,4,8...
  各踩一档；能预估就 make(cap)，不能就 benchmark 决定档位。
- RSS 与 live heap 的差值来自：碎片（span 空洞）、sweep 债务、
  归还 OS 的延迟（scavenger 慢还）；GODEBUG=scavtrace=1 观察
  归还节奏，内存压力大的服务用 GOMEMLIMIT 逼 runtime 早还。
- 大缓冲服务（音视频/AI tensor）：预分配大 []byte 自己切分治理，
  绕开 size class——历史上 arenas 实验没转正，手工 slab 是现实解。

```bash
curl :6060/debug/pprof/heap?debug=1 | head # 按 size class 看
# runtime/metrics: /memory/classes/heap/objects:bytes 等
```

## 43. GC 停顿解剖与诊断指标

- 指标入口：runtime/metrics 的 /gc/pauses:us 直方图（STW 分布，
  1.19+ 官方姿势，替代手算 ReadGCStats）；/gc/cycles:total 计数；
  自定义 /gc/heap/allocs:bytes 算分配速率。
- 停顿构成：两次 STW（µs 级）+ mark assist 时间（摊在业务
  goroutine 上，不进 STW 统计但进请求延迟）——"GC 停顿小但 P99
  尖刺"的真凶常是 assist。
- assist 的业务形状：分配大户在 GC 周期里被抽走 CPU 补标记进度——
  低分配请求几乎无感、高分配请求显著变慢 → P99 与 P50 背离是
  assist 的指纹；trace 里直接可见 assist 时长条。
- 缓解顺序：先减分配（40 节，治本）→ 调 GOGC/GOMEMLIMIT（37-38）→
  最后才考虑 Green Tea/实验（11 节）；顺序反了是给自己埋新坑。
- 警惕冒名顶替：网络抖动、锁竞争、cgroup throttling 都长得像
  "GC 卡顿"——先看 pauses 直方图与 limiter 指标再定性。

```go
import "runtime/metrics"
// 采样 /gc/pauses:us 直方图打到监控, 告警盯 p99
```

## 44. 容器内存治理与 OOM 取证

- RSS 构成拆解：runtime（堆+栈+元数据，可被 GOMEMLIMIT 约束）+
  二进制映射（只读段+data/bss）+ cgo/第三方分配（malloc 不归 Go）；
  四块各记账才能定预算。
- 取证工具链：/proc/self/smaps_rollup 看进程口径、
  /sys/fs/cgroup/memory.current 看容器口径、
  runtime/metrics /memory/classes/* 看 runtime 口径——三个数对不上
  的部分就是"cgo/系统"吃掉的。
- 标准预算公式：GOMEMLIMIT = (限额 - 二进制 - cgo 峰值) × 90%；
  算不出的 cgo 峰值用压测拿，拍脑袋 = 撞限被 OOMKill。
- OOMKill vs limiter 症状对照：被内核杀（退出码 137 / OOMKilled
  事件）= 硬限击穿；活着但慢两倍 + limiter 指标红灯 = 软限死亡
  螺旋（38 节）——处置完全不同，先判对再动手。
- k8s 细节：swap 默认禁用，container_memory_working_set_bytes 是
  驱逐依据（含 page cache）——Go 服务自身 RSS 曲线要对着这个口径
  告警，别对着 limit 硬算百分比。

```bash
cat /sys/fs/cgroup/memory.current   # 容器实际用量
curl :6060/debug/pprof/heap?debug=1 # runtime 口径对账
```

## 45. 内存泄漏取证方法论

- Go 的"泄漏"是"全局可达但不该留"：没有 free，一切泄漏都是引用
  被谁多攥着——万恶之源是全局 map 注册表（handler/订阅者/缓存无
  TTL、断连不清 key）。
- 方法论：稳态压测 → heap profile (inuse_space) 快照 →
  隔 N 分钟再快 → `go tool pprof -base old new` 差分 →
  看增长项的引用链（who points to）→ 定位到字段/闭包。
- goroutine 泄漏伪装成内存泄漏：每 G 至少几 KB 栈 + 持有的捕获
  对象；先看 goroutine 数是否线性涨（25 节），再查堆。
- 常见误判清单：队列积压（背压设计内的正常持有）、sync.Pool 驻留
  （41 节）、碎片（42 节）、debug 信息（`%#v` 拼进日志的大对象）。
- 工程化：pprof 常驻 + 定时 heap dump 的差分是发现回归的最好手段
  （Parca/Pyroscope 连续剖析自动化此事）；每个服务标注"稳态 RSS
  基线"，偏离告警。

```bash
go tool pprof -base heap_t0.pb.gz heap_t1.pb.gz
# 差分图里涨的都是嫌疑对象, -inuse_objects 数个数
```

## 46. channel 内部结构 hchan

- 结构：hchan{ buf 环形队列（dataqsiz/sendx/recvx），sendq/recvq 两个
  sudog 等待队列，lock }——chan 的"无锁神话"不存在，只是临界区极短。
- 快路径优化：无缓冲或有等待者时直接栈拷贝——发送者把数据写进
  接收者 sudog 指向的栈内存，跳过 buf；unbuffered chan 因此是
  严格的同步交接点。
- 满缓冲 + 有等待发送者：先从 buf 取走一个，再把队首发送者数据补进
  buf——微妙的顺序保证了 FIFO 公平，这些语义用户可见（48 节矩阵）。
- 唤醒路径：goready 把等待者放进 runnext（18 节）——被唤醒的
  goroutine 下轮调度优先跑，唤醒链短的流水线天然受益。
- 容量语义要背：buffered chan 消除的是偶发时序抖动，不是架构并发度；
  拿它当队列/限流用 = 满了之后发送方全体阻塞（57 节背压正解）。

```go
done := make(chan struct{}) // 信号: 空结构体最省
data := make(chan T, 8)     // 8 是缓冲不是并发度
```

## 47. channel 所有权与方向纪律

- 所有权三问：谁创建（拥有 close 权）、谁写、谁读——答案必须唯一；
  API 把双向 chan 抛出去 = close 权与写权同时送人，事故只是时间问题。
- 签名自文档：返回 `<-chan T`（只读）、参数收 `chan<- T`（只写）——
  类型系统替你把关；裸 `chan T` 参数必须注释说明谁管生命周期。
- close 只属于唯一 sender，且只在"再无数据"时发生——close 是
  广播"数据完了"不是"释放资源"；用 close 释放资源是语义错配。
- 泄漏形态：worker 内 range 生产者 chan，生产者 panic 提前退出没人
  close → worker 全家永眠；统一出口 defer close + errgroup 包装（54 节）。
- 分工：请求域取消用 ctx；stage 级生命周期用 done chan + defer close owner 制（50 节）。

```go
func Stream(ctx context.Context) <-chan Item { // 只读出
    ch := make(chan Item)
    go func() { // 唯一 writer: defer close 保证 panic 也广播
        defer close(ch)
        pump(ctx, ch) // select ctx.Done 的发送循环
    }()
    return ch
}
```

## 48. nil 与 closed channel 语义矩阵

- 九宫格必背：nil——send/recv 永久阻塞、close panic；open——正常；
  closed——send panic、recv 立即返回（缓冲排空后零值+ok=false）、
  再 close panic。
- nil 的妙用：select 里把不活跃分支置 nil 即"临时摘除"——ctx 与业务
  chan 动态切换的标准技巧；反过来的事故：忘了 make 的 chan 零值
  nil → 静默阻塞无任何报错。
- closed 恒就绪：select 挂着已关闭 chan 就永远选中它——该分支若
  不是 return 就是 100% CPU 空转风暴，必须显式摘除（置 nil）。
- `v, ok := <-c` 的 ok=false 唯一含义是 closed 且缓冲已空；
  range chan 与其语义一致，close 后自然退出。

```go
case v, ok := <-ch2:
    if !ok {
        ch2 = nil // 关闭分支自禁用, 防空转
    }
```

- 取消场景双保险：range + ctx.Done 都能退出，依赖单一机制的
  生产者代码九成会在重构里漏一处。

## 49. select 深水：随机性、顺序与开销

- 就绪分支随机挑（运行时洗 pollorder）：防饿死的公平设计——
  "写在上面优先"是错觉，要优先级就嵌套 select 先查优先分支。
- 双 select 取消优先：先查 ctx.Done（带 default）再处理业务，防"已取消却消费新数据"。
- 每次进 select 都注册/注销 sudog：热循环多路 select 开销可测，
  改成事件聚合器（单 goroutine 分发）或降低轮询频率。
- `for { select { case ...: default: } }` 是 100% CPU 忙轮询反模式：
  阻塞等待或退避，default 只许做"顺手试一次"。
- time.After 在热循环里每轮建 timer：1.23 后不泄漏但分配仍在——热路径用 NewTimer+Reset 复用（60 节）。

```go
select { // 双 select: 先确认取消, 再消费新数据
case <-ctx.Done():
    return ctx.Err()
default:
}
select {
case v := <-work:
    handle(v)
}
```

## 50. 关闭广播与 done 信号模式

- close(ch) 是广播：所有阻塞接收者同时放行、后续接收恒就绪——
  "通知 N 个消费者退出"只有这一个正确机制；往 chan 发一个值只能
  叫醒一个接收者，"叫醒 N 个"的发送循环是设计错误。
- 值信号广播必丢：`done <- code` 给多接收者只有一人拿到——要携带
  数据的广播用 sync.Cond（要配共享状态与 Lock 纹理）或人手一个 chan。
- owner 制模板：owner 创建 chan、`defer close(done)` 收尾、消费者只读
  ——close 权不出函数边界，defer 保证 panic 路径也广播（47 节）。
- 与 ctx 分工：请求域取消（带值与 deadline）用 ctx；进程/任务/stage
  生命周期用 done；混用（把 done 塞进 ctx）两边不讨好。
- 三件套组合：done 广播退出 + WaitGroup 等收尾 + ctx 传取消原因——
  就是 55 节生命周期审计的落地件。

```go
done := make(chan struct{})
var wg sync.WaitGroup
wg.Add(1)
go worker(done, &wg) // worker 内 select done / 业务
close(done) // owner 广播: 全员同刻收到
wg.Wait()   // 然后等收尾
```

## 51. context 取消传播内幕

- cancelCtx 结构：done chan（懒建 + atomic 缓存）、children map（加锁
  维护）、err；Cancel = 关 done + 递归取消 children + 从父注销。
- 传播注册（propagateCancel）：父已取消则子立即取消，否则挂进父的
  children；父是自定义 Context（非标准 Done）→ 起监听 goroutine——
  每层一个，深层嵌套自定义 ctx 是 goroutine 放大器。
- 取消只向下传播；Err() 在 Done 关闭后返回 Canceled 或
  DeadlineExceeded——区分"谁先超时"查各层 Err。
- Deadline() 是预算接口：层层把剩余 deadline 报给下游切分预算——
  根节点一个大超时一刀切浪费了整套机制，DB/外部调用各拿小预算。
- 1.21 新件：WithoutCancel（继承值断开取消——请求后异步收尾的
  正确姿势）、AfterFunc（取消触发回调，免手写监听 goroutine）。

```go
ctx, cancel := context.WithTimeout(ctx, 2*time.Second)
defer cancel() // 漏 defer = 子链挂在父上不注销(真泄漏)
```

## 52. context 架构纪律

- ctx 是函数第一参数（命名 ctx），只传不存：存进结构体字段 = 生命
  周期错配（struct 活得比请求长）+ 隐式依赖；lint 必开 containedctx。
- WithCancel/WithTimeout 的 cancel 必须 defer——漏掉 = 子 ctx 在父的
  children 里注册不注销，高频创建路径就是内存缓涨（监控可见）。
- nil ctx 禁止：待接的真实 ctx 用 context.TODO()，根用 Background()；
  把 Background 当"默认参数"传进去再被覆盖 = 取消链断在中间。
- errgroup.WithContext 的组 ctx 只服务组内成员——传给组外后台任务
  会在 Wait 后被取消，任务鬼叫；跨界用 WithoutCancel 显式断开。
- 域边界规则：HTTP 进来、消息消费、定时任务各自建新根——别让一个
  请求的取消链意外挂上进程级资源（55 节）。

```go
func Do(ctx context.Context) error { // 纪律一: 第一参数
    ctx, cancel := context.WithTimeout(ctx, budget)
    defer cancel() // 纪律二: 创建者负责取消
    return work(ctx)
}
```

## 53. context.Value 深水

- 键必须自定义非导出类型：`type tenantKey struct{}`；string/int 键
  跨包冲突是真实事故（两个库都用 "user" 互踩）；值也建议自类型，
  让断言不可能静默错型。
- 查找是链上 O(depth)：valueCtx 一层层向上问——十层嵌套在热路径有
  可测开销；把 Value 当依赖注入容器（塞十几个键）是反模式。
- 断言失败静默：`v, ok := ctx.Value(k).(T)` 类型对不上只是 ok=false；
  取值代码与存值代码同包（私有类型键自然强制），升级改值类型
  才能编译期暴露。
- 只放请求域元数据（traceID/tenant/认证身份）；业务参数必须进
  函数签名——"ctx 里捞参数"评审直接打回。

```go
type traceIDKey struct{} // 私有类型键: 冲突不可能
func TraceID(ctx context.Context) string {
    if v, ok := ctx.Value(traceIDKey{}).(string); ok {
        return v
    }
    return "" // ok=false 静默降级, 不 panic
}
```

## 54. errgroup 群组并发与 SetLimit

- `g, ctx := errgroup.WithContext(ctx)`：首个非 nil error 触发组取消；
  Wait 只返回首错——其余被吞，全量错误 mutex+slice 收集后 errors.Join（62 节）。
- g.SetLimit(n)（1.20+）是入口信号量：并发满时 g.Go 阻塞调用方——
  管"进入"不管"完成"；上游堵在 g.Go 是背压，须有意为之（57 节）。
- panic 不转 error：g.Go 里 panic 直接 crash；库边界 recover 自己做（66 节）。
- 取消纪律靠人传：任务体内不 select ctx.Done 就永远取消不了（55 节）；
  纯并行等收尾用 WaitGroup.Go（14 节），手搓 wg+error chan 趁重构收编。

```go
g, ctx := errgroup.WithContext(ctx)
g.SetLimit(8) // 入口背压: 并发满时 g.Go 阻塞
for _, t := range tasks {
    g.Go(func() error { return process(ctx, t) }) // 首错触发组取消
}
if err := g.Wait(); err != nil { // Wait 只返回首错
    return fmt.Errorf("batch: %w", err)
}
```

## 55. goroutine 生命周期审计与取消传播

- 每条 `go` 语句的两问：谁让它退出（信号源）？谁等它退出（收尾人）？
  答不出 = 泄漏候选；两问答案写进注释是团队最低标准。
- 取消断链点：任务先入队后消费（取出时 ctx 已取消却照跑——取队后
  先查 ctx）；ctx 存结构体字段（52 节）；该用 WithoutCancel 却 Background。
- 监控三指标：NumGoroutine 打点（粗）、pprof label（细，pprof.Do）、
  业务池计数（水位）——告警按基线偏离百分比（25 节），不按绝对值。
- owner 模式落地：owner 持 done+wg，子 goroutine select done/业务信号，
  退出前 close(done) 再 wg.Wait——进程级即优雅停机（56 节）。

```go
go func() {
    defer wg.Done() // 两问模板: 信号源 ctx/done, 收尾人 wg+owner
    select {
    case <-ctx.Done():
    case job := <-q:
        if ctx.Err() != nil { return } // 断链点: 取到任务先验取消
        process(ctx, job)
    }
}()
```

## 56. 优雅停机与信号处理

- 标准链路：NotifyContext(SIGTERM) 即取消 → 摘流量（关 listener/
  LB 摘除/停消费）→ server.Shutdown 等在处理请求 → 收尾退出。
- Shutdown 盲区：hijacked 连接（WebSocket/长轮询）不受管——hijack 前
  把 ctx 传导给连接生命周期；ReadHeaderTimeout 是慢连接防线（105 节）。
- 超时分层：terminationGracePeriod（默认 30s）> 内部 shutdown 超时 >
  单请求 ctx——内层必须小于外层，否则被 SIGKILL 时还在"优雅"。
- 经典翻车：close(done) 后不等 wg.Wait 直接 return（半写状态退出）；
  defer 顺序反了（先 Shutdown 后关 listener）；停机路径要压测演练。
- 验收唯一标准：滚动发布上游错误数 = 0（迁移/in-flight/offset 提交在流量下重放）。

```go
ctx, stop := signal.NotifyContext(ctx, syscall.SIGTERM)
defer stop()
go func() { _ = srv.ListenAndServe() }()
<-ctx.Done() // 1 信号到
shutCtx, cancel := context.WithTimeout(ctx, 25*time.Second)
defer cancel()
_ = srv.Shutdown(shutCtx) // 2 摘流+等待
wg.Wait()                 // 3 后台任务收尾
```

> Stage A 预写的 57-60 节（可选素材）：Stage B 可直接采用，也可按自己
> 风格重写；标题以 /tmp/kb_go_outline.md 为准，不得改动。

## 57. 有界并发与背压设计

- 无界并发是头号模板事故：`for _, t := range items { go process(t) }`
  ——百万 items 就是百万 goroutine，内存、下游、限流全爆。
- 有界三选型：errgroup.SetLimit（任务异构、首错即止）、
  buffered chan 信号量、worker 池（同构长跑、复用重资源）。
- 背压终点是拒绝不是堆积：队列满立刻 503/429，让上游重试预算生效；
  TryAcquire 快速失败配拒绝率指标——拒绝是背压在工作。
- 边界在"任务"定义处：任务内部再 go 出子任务 = 上限失效，预算贯穿到叶子层。

```go
sem := make(chan struct{}, 32) // 有界并发
for _, t := range tasks {
    select {
    case sem <- struct{}{}:
        wg.Go(func() { defer func() { <-sem }(); process(ctx, t) })
    case <-ctx.Done():
        return ctx.Err() // 排队让位于停机
    }
}
```

## 58. pipeline 扇出扇入与 stage 设计

- stage 退出三件事：输入关闭（range 自然结束）→ 输出关闭（唯一
  owner defer close）→ 取消传导（每处收发 select ctx）。
- 缺一件就是泄漏或丢数据；pipeline 评审就按这三行打勾。
- 扇出：N 个 worker 共享输入 chan（并发 range 安全）；扇入 merge：
  WaitGroup 等全 worker 退出后由唯一 owner 关输出。
- 容量 0 相邻 stage + 每级单 goroutine = 整条流水线串行退化；
  瓲颈级加 worker 才是正解，瓶颈用 bench/压测找。
- 多数业务场景 for 循环 + errgroup + 批处理更短更清楚；pipeline
  只在流式、高吞吐各 stage 独立扩展时才值。时序竞态测试交给
  synctest（91 节），不用 sleep 赌运气。

```go
var wg sync.WaitGroup
wg.Add(len(ins))
for _, in := range ins {
    go func(in <-chan T) { defer wg.Done(); relay(ctx, in, out) }(in)
}
go func() { wg.Wait(); close(out) }() // 唯一 owner 关闭
```

## 59. singleflight 请求合并

- `g.Do(key, fn)`：同 key 并发只执行一次 fn，其余共享结果——缓存
  击穿（热 key 失效瞬间万请求打穿数据源）的标准护盾。
- 结果全员共享 → fn 不能返回请求特定内容；单飞层只放"key 同则
  结果同"的纯查询。
- panic 直接传导给所有等待者：一个下游炸 = 一批请求一起炸；
  fn 内 recover 转 error 是配套纪律。
- fn 慢时等待无限堆积——外层 DoChan + select ctx.Done 让等待方
  可放弃；放弃的是等待不是执行（singleflight 无取消语义）。
- 组合拳：singleflight(miss → 回源) + TTL + 负缓存；击穿验证 =
  并发开到峰值 3 倍看回源 QPS 是否恒为 1。

```go
v, err, shared := g.Do(key, func() (any, error) {
    if hit, ok := cache.Get(key); ok {
        return hit, nil
    }
    return loadFromOrigin(ctx)
})
```

## 60. 定时器深水与 1.23 语义变革

- 1.23 两刀：无引用的 Timer/Ticker 立即可回收（time.After 热循环
  堆积的历史泄漏消失）；timer 通道改无缓冲（同步发送语义）。
- Stop/Reset 老"排干"纪律退役：新语义下不再必须；但多 goroutine
  共享同一 timer 接收依然是乱的——一 timer 一 owner，接收者唯一。
- 老代码审计：1.22 前的排干代码升级后有害无益；time.After 泄漏
  警告已成历史，但每轮分配还在，热路径仍用 NewTimer+Reset 复用。
- timer 堆 per-P（1.14 起），高频 ticker 实打实影响调度路径
  （24 节）；大量 ticker 的服务 trace 里能看到条带。

```go
t := time.NewTimer(interval)
defer t.Stop() // Ticker 同理必须 Stop: 泄漏不随 1.23 消失
t.Reset(interval) // 1.23 起无需排干; 一 owner 一接收者
select {
case <-t.C:
    tick()
case <-ctx.Done():
    return
}
```
## 61. errors.Is/As 内部与包装链

- Is 判定顺序：先 `err == target` 直接等值（哨兵快路径），再依次问链上
  元素 `e.Is(target)`，再 Unwrap 下潜——自定义 Is 定义在"链上元素"身上
  （包装器声称自己等价于某哨兵），不是定义在哨兵上，方向别搞反。
- 链由外向内：从手里这颗错误逐层 Unwrap 到 nil；树形态（`Unwrap() []error`）
  时前序 DFS + visited 防环——errors 包自带防环，自己手写遍历要学它。
- As 硬规则：target 必须是实现了 error 的类型的非 nil 指针，违反是直接
  panic 不是返回 false；`var e *MyErr; errors.As(err, &e)` 少套一层 & 是
  运行期才炸的笔误；命中第一个即停，没有"最精确匹配"语义。
- 1.26 的 `errors.AsType[T](err)` 只是这段样板的语法糖，机制不变（12 节）。
- 成本：O(链长) 指针追逐、零分配；热路径分类一次可接受，循环里对同一
  颗错反复 Is 是浪费——分类一次存下来用。

```go
// 包装器"声称等价"的正规姿势: Is 定义在链上元素
type notFound struct{ error }
func (notFound) Is(target error) bool {
    return target == domain.ErrNotFound // 驱动错误翻译层的等价声明
}
// 调用方 errors.Is(err, domain.ErrNotFound) 即命中
```

- 坑位收尾：As 到 `**T` 双重指针；跨包比较未导出哨兵——库要么导出
  哨兵、要么提供 IsNotFound() 辅助，二选一（64 节）。

## 62. errors.Join 与错误树遍历

- Join 过滤 nil 成员再打包成 joinError，全 nil 时返回 nil——"有没有错"
  的判断不变；但 `Join(err, nil) != err`：多包了一层 joinError，==
  比较与类型断言全部翻车，从此认错只走 Is/As。
- `Unwrap() []error` 是树的分叉口：Is/As 自动 DFS（61 节），fmt 时每个
  成员按 %v 展开用换行连接——错误消息是多行不是 bug，是官方格式。
- 正确场景：errgroup 首错即止（54 节）之外需要全量留痕的场合——并行
  任务收集所有失败、表单校验聚合全部字段错误、清理阶段把每个 Close
  的错误都带出来（65 节）。
- 结构化输出要自己做：树可以嵌着长（Join 套 Join），对外消息仍是拼接
  全文；日志字段想要"错误列表"就定义自己的聚合类型实现 error，再
  提供字段访问器（68 节层次设计）。
- 隐式契约：DFS 前序 + 首个命中即停 = "外层优先"，包装顺序会影响
  As 的结果——多层包装的顺序是 API 决策，不是顺手事。

```go
var errs []error
for _, f := range files {
    errs = append(errs, process(ctx, f)) // 全量收集不短路
}
return errors.Join(errs...) // 全 nil → nil; 调用方照常 Is/As
```

## 63. fmt.Errorf %w 包装纪律

- %w 产出的 wrapError 实现了 Unwrap——Is/As 的链靠它续命；%v 只是把
  文本拼进去，链当场断。判断标准：调用方将来要不要用 Is/As 识别这层？
  要就 %w；刻意隐藏实现细节（内部错误不出域）才允许 %v（68 节）。
- 消息纪律：小写开头、无句末标点、动词+主语+上下文
  （`load config %q: %w`），包装链读出来是一整句；"failed to"连环
  复读与每层重抄下层动词，都是评审退回项。
- 1.20 起一个 fmt.Errorf 可放多个 %w：产出多错误包装（Unwrap 到
  []error，62 节），两个占位各自保留上下文——比 Join 少一行还带消息。
- 同罪清单：`fmt.Errorf("%s", err)` 与 `errors.New(err.Error())` 都是
  文本化断链；go vet 的 printf 检查查不出这种语义丢失，只能靠纪律。
- 样板收敛：包装动词统一进本层一个 wrap helper——将来统一加错误码
  或前缀只改一处，%w 不许散落全库。

```go
if err := load(path); err != nil {
    return fmt.Errorf("load config %q: %w", path, err)
}
return fmt.Errorf("validate: field %s: %w; schema: %w", f, e1, e2)
```

## 64. 哨兵错误与错误分类设计

- 哨兵 = 导出的包级 `var ErrX = errors.New(...)`，调用方用 errors.Is
  认领。它是公共 API：文本与存在本身都是兼容承诺，增删按 API 变更
  管理——"随手加个哨兵"= 给所有调用方埋雷。
- 哨兵表达状态，类型化错误表达带数据的失败：`*ValidationError{Field,
  Msg}`、`*QuotaError{Limit, RetryAfter}`——调用方 As 出结构化字段做
  精细处理（HTTP 状态映射、降级决策），比字符串匹配文明一个世代。
- 聚合哨兵靠自定义 Is：一个 ErrRetryable 同时匹配超时/连接重置/限流
  三类底层错误——分类逻辑长在错误类型自己身上（61 节），调用方只需
  errors.Is(err, ErrRetryable) 一句。
- 层次纪律：驱动/SDK 的错误类型禁止裸穿上层——仓储边界翻译成领域
  词汇且保因果链（翻译+包装，不是替换，68 节）。
- 坑：`err == io.EOF` 在任何 %w 包装后全灭——评审见到 == 认错直接改
  errors.Is；哨兵的 Error() 文案被日志方当协议分流也是隐性 API，
  别给别人机会依赖。

```go
var ErrNotFound = errors.New("not found")
type NotFoundError struct{ Key string } // 带数据: As 提取字段
func (e *NotFoundError) Error() string { return "not found: " + e.Key }
```

## 65. 资源清理：defer 与错误聚合

- defer 是 LIFO 栈，注册顺序即清理顺序——"打开的逆序关闭"天然正确；
  手写一串顺序清理的代码每次改动都要重排，是事故温床，统一 defer。
- Close 的错误不能丢：只读场景忽略尚可，写路径的 Close 错误（缓冲
  未刷、fsync 失败）才是"写成功没有"的最终答案——命名返回值+defer
  聚合是标准形态。
- 循环内 defer 摆到函数返回才执行：万次循环攒一万个 defer（开销 1.14
  后很小但语义在攒着）——循环体提成函数或即时清理，别攒。
- defer 改返回值只对命名返回值有效：`return err` 先赋值再跑 defer，
  defer 里改 err 才生效；这也是 recover 转 error 的唯一通道（66 节）。
- 多资源清理的错误全量 Join（62 节）——第一个 Close 失败掩盖第二个
  是清理路径的常见丢信息；清理失败同样要进可观测（67 节）。

```go
func persist(path string) (err error) {
    f, oerr := os.Create(path)
    if oerr != nil { return oerr }
    defer func() {
        if cerr := f.Close(); err == nil && cerr != nil {
            err = fmt.Errorf("close: %w", cerr) // Close 错误不丢
        }
    }()
    return writeAll(f)
}
```

## 66. panic 纪律与 API 边界 recover

- panic 只给"程序员的错"：不可能到达的分支、被破坏的不变量、内部
  数据结构的必然合法索引——外部输入（网络/文件/用户）永远走 error；
  把 panic 当异常用的库会拖累所有调用方（110 节反模式联动）。
- recover 只配出现在边界：每个新 goroutine 的入口（recover 不跨
  goroutine，主 goroutine 的救不了子 goroutine）、消息消费循环、
  进程 facade——中间层禁止吞。
- net/http 在每个 handler 外自带 recover（记日志+断连接），但 handler
  里 go 出去的 goroutine panic 照旧全进程死——"框架兜底"不是免死金牌。
- 转换纪律：recover 到的 any 必须带栈转成错误（defer 里抓
  runtime/debug.Stack()），只 `fmt "%v"` 是毁尸灭迹；转 error 还是
  原样 re-panic，取决于这层是不是边界。
- panic(nil) 自 1.21 起变成 *runtime.PanicNilError——靠 nil panic 做
  软信号的老黑魔法已死，GODEBUG=panicnil 只是临时逃生门。

```go
defer func() {
    if r := recover(); r != nil {
        err = fmt.Errorf("panic in %s: %v\n%s", op, r, debug.Stack())
    }
}() // 栈随身带走; 非边界层原样 re-panic
```

## 67. 错误与日志的分工架构

- 处理一次原则：一颗错误要么处理掉（日志+终止传播），要么包装上抛
  ——又记又抛 = 同一颗错在 N 层日志复读，告警计数 N 倍放大，排查满屏
  回声。"包一层记一层"是新手代码的第一噪音源。
- 正确分工：中间层只 %w 补上下文（63 节），顶层（HTTP handler/消息
  循环/命令入口）统一日志——只有那里凑得齐 traceID、租户、耗时等
  完整现场；底层能就地消化的返回 nil，别再上抛。
- slog 字段约定：错误永远走结构化字段 `slog.Any("err", err)`，键名全
  组织统一（err/error 二选一写进规范）；msg 用稳定短语——告警按 msg
  聚合、检索按字段，把错误拼进 msg 两头全废（107 节）。
- 级别纪律：error=要人介入/影响用户，warn=自愈但异常——把可预期
  失败（校验不过、缓存 miss）打成 error 的系统没法看，信噪比决定
  告警体系的生死。
- 度量与日志分家：错误计数分类走 metrics 维度，日志只留实例+现场；
  "从日志数错误"是反模式（采样/丢弃之后不可信）。

```go
// 顶层唯一日志点; 中间层只 %w 上抛, 不再重复记
log.ErrorContext(ctx, "checkout failed",
    "err", err, "order_id", id) // 结构化字段, 键名组织统一
return // 处理完毕, 不再上抛
```

## 68. 失败域：错误的层次设计

- 每层一套错误词汇：领域层不认识 *pgconn.PgError/*net.OpError——
  仓储边界把存储错误翻译成领域词汇（NotFound/Conflict/Unavailable），
  翻译时 %w 保因果（64 节）：上层用领域词汇决策，取证沿链下钻到驱动
  细节，两头都要。
- 可重试是接口不是清单：错误类型实现 `Retryable() bool`（或
  Timeout()/Transient()），边界统一 errors.As 探测——重试逻辑写一次，
  新错误类型实现接口自动纳入；散落各处的 if 链是维护地狱。
- 重试的边界：只重试"未产生副作用"的失败——连接失败可重试，响应已
  开始的失败重试=重复下单；幂等键是外部世界的事实，与重试分类必须
  一起设计（57 节背压联动）。
- 层次短路：对外错误越来越粗（unavailable/conflict/invalid），细节
  留在链里；HTTP 500/409/422 映射表是失败域设计质量的试金石——
  映射表写不顺 = 层次切错了。
- 失败域即发布域：包/服务的公共 API 只暴露自产错误类型；内部类型
  泄漏出去 = 每次依赖升级都变成公共 API 变更事故。

```go
func (r *Repo) Get(ctx context.Context, k string) (*Item, error) {
    it, err := r.q.Get(ctx, k)
    if errors.Is(err, pgx.ErrNoRows) {
        return nil, fmt.Errorf("item %q: %w", k, domain.ErrNotFound)
    }
    return it, err // 其他错误原样上抛, 取证可沿链下钻
}
```

## 69. interface 内部：iface、itab 与 eface

- 两个字：空接口是 eface{_type, data}，非空接口是 iface{tab, data}；
  data 指向装箱后的值（或本身就是指针）。接口赋值=两个字的拷贝，
  接口比较=两个字都参与（70 节二义性的根源）。
- itab{接口类型, 具体类型, hash, fun[]}：fun 是方法地址表，接口调用=
  一次 itab 定位（编译期已知偏移）+间接调用；hash 缓存具体类型哈希，
  类型断言/switch 用它快速比较（74 节）。
- itab 全局缓存：每对 (接口, 具体类型) 的方法表只算一次，之后原子读
  缓存——首次装箱/断言贵（算方法集），之后便宜；热循环反复装箱同一
  类型不会重算 itab，但每次装箱都可能堆分配。
- 装箱逃逸：值→接口需要堆上的盒子（值拷进去）；逃逸分析若证明接口
  不逃逸可栈上装箱——"-m"输出里"装箱进接口"就是逃逸理由（39 节），
  热路径的解法是具体类型/泛型，不是怪接口。
- 性能含义：接口调用吃分支预测红利但封死内联（编译器基本不做去
  虚化，84 节），热点被接口层层包裹=火焰图一串 call 而非内联展开——
  架构上允许、热路径上消灭（71 节窄接口就是退路）。

```go
var w io.Writer = os.Stdout // *os.File 的 itab 已在全局缓存
_ = w.Write(b)              // itab 定位+间接调用, 失去内联
// 装箱: *os.File 本身是指针, data 直存; 值类型才需要堆盒子
```

## 70. typed nil 陷阱与防御

- 病根在表示：接口是 (type, data) 二字组，`(*T)(nil)` 装箱后 type=*T
  非 nil、data=nil——整体 != nil。"返回 *MyErr 的函数在失败分支返回
  nil 指针、调用方装进 error 接口"是标准事故剧本。
- 防御一：错误路径返回显式 `return nil`，绝不 `return err`（err 是
  typed nil 指针变量时）；写包装器时别把中转类型定成 *T。
- 防御二：泛型/反射层转发错误时要么 errors.As 解包判定，要么
  reflect 判 IsNil——先把 70% 事故用"显式 nil"解决，再谈高级手段。
- 连坐场景：JSON 反序列化 helper 返回 typed nil；测试断言分叉——
  `require.Nil(err)` 走反射深判、`err == nil` 看接口二字组，对 typed
  nil 结论相反——选断言库前先统一团队口径，别让 CI 教你 Go。
- 哨兵永远 errors.New：`var ErrX *MyErr = nil` 式"空哨兵"是把二义性
  写进 API；需要带数据就构造非 nil 值（64 节）。

```go
func find() error {
    var e *MyErr // nil 指针
    if miss {
        return e // 事故: 装箱后 err != nil 为 true
    }
    return nil // 唯一正确: 显式 nil
}
```

## 71. 接口设计：小接口与消费者定义

- "接口越大，抽象越弱"：单方法接口是组合的原子——io.Reader/io.Writer
  一方法起步，io.ReadWriteCloser 是组合出来的大接口；导出接口 1-3 个
  方法是健康区间。
- 消费者定义：接口声明在使用它的包里（函数参数形态），生产者返回
  具体类型——依赖方向天然正确（用的人依赖抽象），mock 缝隙自然长
  出来（93 节）；生产端先造 IService 的 Java 风格在 Go 里是反模式。
- 第二实现出现前不抽接口：为"以后可能"写的接口是负资产（YAGNI）；
  真要双实现时再抽，Go 工具链让这个重构很便宜。
- 为 mock 把公共 API 全改接口 = 送出装箱成本（69 节）与类型信息，
  换"测试方便"的幻觉——正确姿势：业务包依赖消费侧窄接口，公共 API
  返回具体类型。
- 实现关系编译期锁死：`var _ store = (*PostgresStore)(nil)` 放实现
  文件里，比运行期才发现少实现了方法体面得多。

```go
// 消费者包内声明: 用到什么定义什么
type store interface {
    Get(ctx context.Context, k string) (*Item, error)
}
func New(s store) *SVC { /* *PostgresStore 直接传入 */ ... }
```

## 72. gcshape：泛型实例化内幕

- Go 不做 C++ 式逐类型特化：相同 gcshape 共享一份机器码+一张字典
  （类型描述符与方法实现的间接表）；所有指针类型同属一个 gcshape
  ——`T=*A` 与 `T=*B` 共享代码，`T=int` 与 `T=float64` 各自实例化。
- 字典成本：经类型参数调方法=查字典间接跳转且不可内联；约束里没有
  字段，碰 T 的内部只能走方法——"泛型容器+值类型+每步调方法"在
  热路径最吃亏。
- 经验形状：纯算法泛型（比较/交换/切片原语）接近零损失；容器存值
  类型+外部函数操作无字典直调，通常无感；方法重的泛型 API 才有可测
  差距——benchstat 定夺（82-83 节），别信"泛型慢"的 folklore。
- 值 vs 指针：T=值类型每形状一份代码（二进制变大），T=指针共享代码
  但每次访问多一层间接+GC 要扫指针；中型结构用值类型反而常赢。
- 数组形状含长度：[4]T 与 [8]T 是不同 gcshape——传数组类型参数时
  形状随长度爆炸，泛型 API 收切片不收数组是有内幕原因的。

```go
func Swap[T any](a, b *T) { *a, *b = *b, *a }   // 指针: 一份代码
func Sum[T ~int | ~float64](s []T) (t T) {      // 值: 每形状一份
    for _, v := range s { t += v }
    return t
}
```

## 73. 泛型约束设计与常见坑

- 约束写行为不写类别：`~int | ~string` 是数值/基础类型家族用的，
  "可比较可打印"这类能力要写成方法集——约束即文档；命名约束导出
  复用，匿名大 union 别散落在签名里。
- union 元素不能带方法（`~T | M()` 非法）：要行为用方法集，要家族用
  union，两者兼得就拆类型参数或接口嵌套。
- 零值 `var zero T` 合法且常用；T{} 复合字面量非法——构造走约束方法
  （New() T）或构造函数参数，这是容器库 API 的标准形状。
- 类型参数上的类型 switch：`switch v := any(x).(type)` 先转 any 是正路；
  `case T:`（类型参数本身）合法，兜底 `case any:` 收尾。
- 推断边界：无类型 nil 推不出 T（必须显式 F[int](nil)）；1.21 推断
  大升级后，untyped 常量进泛型结构体字面量仍会因 T 未定而失败——
  这两条铁障年年咬人。
- comparable 陷阱：接口值满足 comparable（编译期过），运行期装箱了
  map/slice 再 == 直接 panic——拿 comparable 做 map 键约束前，先掂量
  调用方会不会塞接口进来。

```go
func Zero[T any]() T {
    var zero T // 唯一的零值获取姿势
    return zero
}
// 坑: type Set[T comparable] map[T]struct{} — 接口做键运行期才炸
```

## 74. 类型断言与类型开关性能

- 成功路径成本：接口→具体 = itab 指针比较；接口→接口 = 方法集包含
  判定（assertI2I，缓存命中后仍是查表比较）——个位数纳秒量级，但
  调用点从此失去内联（69 节）。
- 失败路径贵一档：未命中走 runtime 现算方法集/查全局表——"断言失败
  是常态分支"的设计（用断言探测可选能力）在热路径要重新掂量：探测
  频率 × 失败成本。
- 类型开关代码生成：少量 case 线性比较，多 case 按类型哈希二分/跳表
  ——十几个 case 的错误分类 switch 不是性能问题，可读性才是变量。
- 热循环纪律：同一值的断言提出循环外存具体类型；切片逐元素断言先
  type switch 分桶再各走具体类型直调——一次断言，N 次直接调用。
- 断言成本低于 map 查找与函数调用，先消灭大头；真要精确到纳秒，
  b.Loop + benchstat（82-83 节），单次数据不作数。

```go
// 分桶: 一次 switch, 后续全是具体类型直调(可内联)
for _, e := range events {
    switch t := e.(type) {
    case *OrderEvent: handleOrder(t)
    case *PayEvent:   handlePay(t)
    }
}
```

## 75. 方法集：值接收者与指针接收者

- 规则一句话：*T 的方法集=值方法+指针方法；T 的方法集=值方法。
  "T 没实现接口而 *T 实现了"的编译报错，病根永远在方法集不在接口。
- 地址性是隐藏条件：值不可寻址（map 元素、函数返回值、接口装箱值）
  时调不了指针方法——m[k].Inc() 编译不过；先落地局部变量再调。
- 接口装箱是拷贝：T 装进接口时值复制进盒（69 节），之后经接口调指针
  方法改的是盒内副本——"改了没生效"的经典来源；大结构还有拷贝成本，
  接口里默认放指针。
- 一致性铁律：一个类型的接收者风格必须统一，混用=方法集在调用方
  眼里随机缺失；要改状态或结构体较大用指针接收者，其余跟队走。
- vet 是底线：copylocks 抓"锁被值拷贝"（Mutex 值接收者、值嵌入后被
  拷贝）——死锁与竞态的编译期疫苗，CI 必开（110 节清单联动）。

```go
type Counter struct{ mu sync.Mutex; n int }
func (c *Counter) Inc() { c.mu.Lock(); c.n++; c.mu.Unlock() }
// 反面: func (c Counter) Inc() — vet: passes lock by value
// 锁已失效 + Counter 值的方法集永远缺 Inc
```

## 76. 隐式接口与泛型接口解禁

- 1.24 前的枷锁：参数化接口只能当约束，`func f(x Iterator[int])`
  这种"接口当普通类型"的写法非法——泛型库被迫 any+断言或暴露具体
  类型；1.24 解禁，参数化接口可做变量/参数/字段类型。
- 解锁的形态：多态迭代器接口（容器包定义 Iterator[T] 与 range-over-
  func 适配，3 节）、类型安全的注册表/服务目录、约束接口的组合复用
  （type C interface{ cmp.Ordered; fmt.Stringer }）。
- 隐式满足依旧隐式：泛型接口不改变结构化类型系统——第三方类型
  "碰巧"满足就满足，无需声明；这也是替身/缝隙的来源（71/93 节）。
- 仍然没有的：方法不能引入自己的类型参数——需要"方法级泛型"的场景
  退化为包级函数+参数收窄，设计 API 前先认清这块地形。
- 性能提醒：经泛型接口的调用照走 itab（69 节），约束方法多的接口在
  热路径合并回具体类型/泛型函数（72 节字典成本同源）。

```go
type Iterator[T any] interface{ Next() (T, bool) }
func Drain[T any](it Iterator[T]) { // 1.24+: 合法普通类型用法
    for {
        v, ok := it.Next()
        if !ok { return }
        use(v)
    }
}
```

## 77. pprof 生产接入

- 接入形态：独立内部端口+自建 mux——`import _ "net/http/pprof"` 会把
  handler 注册进 DefaultServeMux；主服务若用了 DefaultServeMux，
  profile 端点就进了公网（事故级：debug 端点漏内存内容、profile 可被
  当免费压测器打挂服务）。验收：对公网地址 curl /debug/pprof/ 必 404。
- 权限模型：内部端口只绑管理面/localhost，前置 token 或 mTLS——
  "只在内网"不是权限模型，内网横向移动的第一站就是 pprof。
- 持续剖析：Pyroscope/Parca/云厂商 always-on 采样（CPU 1-5% 开销）、
  按版本存档火焰图——回归不用等事故，diff 两版直接看热点漂移；事故
  后才采样，丢的永远是"事故前基线"（78 节）。
- 运行时开关：CPU profile 按需 30-60s 窗口；heap 采样率
  MemProfileRate 是启动时定的（79 节），运行中只调"要不要 dump"，
  别指望事后调精度。
- block/mutex 剖析默认关：启动时一次性 SetBlockProfileRate/
  SetMutexProfileFraction（80 节）——接入做进清单，别临时抱佛脚。

```go
mux := http.NewServeMux()
mux.HandleFunc("/debug/pprof/", pprof.Index) // 显式挂内部 mux
go func() { _ = http.Listen("127.0.0.1:6060", mux) }()
// 主服务用另一个 mux: 绝不共享 DefaultServeMux
```

## 78. CPU 火焰图判读深水

- 宽度=采样窗口内的 CPU 份额（统计量）：<1% 的帧可能是噪声，可疑小
  帧拉长窗口再采；0.5% 与 0.3% 的差异没有统计意义——先问显著性再
  谈优化（83 节同款纪律）。
- 平顶宽帧（flat 高）=自身热点：算法/拷贝/序列化的直接证据，优先
  打击；又高又窄下面挂一大片（cum 高 flat 低）=调用链路过宽——沿
  callees 找真正的叶子工作。
- 内联帧是逻辑帧：内联后 callee 显示为独立帧（pprof list 标 inlined），
  行号归属跳变不是 bug；"源代码直觉"与"火焰图形状"打架时，以内联后
  的真实调用链为准（84 节）。
- 对比才是杀手锏：改动前后各采一份，pprof -base / diff 火焰图找
  "变多了"的帧——绝对图回答"谁在烧"，diff 回答"谁变了"，后者直接
  指向变更集。
- 姿势：低流量时段采的图没有判决力；窗口覆盖完整业务周期（定时
  任务、缓存过期潮）；GC 占比交叉验证（36 节：GC CPU 高先看分配率，
  不是先改业务代码）。

```bash
curl -s ':6060/debug/pprof/profile?seconds=60' > cpu.pprof
go tool pprof -http=:8080 -base=old.pprof cpu.pprof # diff 找回归
```

## 79. heap profile：inuse 与 alloc 语义

- 四视图两条问题：alloc_space/alloc_objects=累计分配（GC 压力与分配
  率——"谁在制造垃圾"）；inuse_space/inuse_objects=存活（"谁拿着
  不还"）。内存涨看 inuse，GC 频繁看 alloc——视图选错，结论全错。
- 采样本质：MemProfileRate 默认每 ~512KB 采一次，所有数字都是外推
  估计——小对象账单有系统性误差，结论要用 RSS 与 runtime/metrics
  交叉验证（44 节）。
- 快照时机：heap profile 在 GC 时刷新——刚分配未 GC 的部分不在图上；
  事故现场先 `heap?gc=1` 强制一轮再 dump；两份快照用 pprof -base 看
  delta，比单张绝对图有力得多。
- 判读红线：inuse 高≠泄漏（缓存/池本来就该持有）；alloc 高+inuse 平
  =压力不是泄漏；inuse 单向爬+goroutine 数同步爬=先查泄漏 goroutine
  持有的引用（25/45 节），堆本身往往无罪。
- "谁持有"比"是什么"值钱：inuse 模式下对可疑帧 peek/list 看引用
  来源——定位到注册表/缓存/闭包捕获，修复点就出来了。

```bash
curl -s ':6060/debug/pprof/heap?gc=1' > h2.pprof
go tool pprof -base=h1.pprof -sample_index=inuse_space h2.pprof
```

## 80. goroutine、block 与 mutex 剖析

- goroutine profile 是事故第一现场：`goroutine?debug=2` 全量栈+每栈
  数量与最长存活时间——按同构栈聚类，"3 万条卡在 chan receive"
  就是案情摘要（25 节）。
- block profile（等待方视角）：SetBlockProfileRate(n) 单位纳秒，阻塞
  超过 n 的事件被采样（权重=阻塞时长）；生产给 100ms 级粗门槛，n=1
  全记录只在压测用。回答"goroutine 的时间花在等什么"。
- mutex profile（持锁方视角）：SetMutexProfileFraction(1) 全采样（写
  100 表示百抽一）——记录"持锁者栈 × 别人累计等了多久"；修锁竞争看
  它：等的人无罪，压着锁不放的栈才该拆。
- 三件都默认关：block/mutex 启动时一次性打开，开销可控（门槛/分母
  自己定）；block 与 mutex 答案互补——block 说"在等"，mutex 说"等谁"。
- 常态化：池化/队列服务的 mutex profile 天天看——竞争随流量渐进
  演化，别等 P99 图见尖刺才想起它存在。

```go
// 启动时一次性配置, 生产可接受
runtime.SetBlockProfileRate(100_000_000) // 100ms 门槛
runtime.SetMutexProfileFraction(5)       // 1/5 采样持锁事件
```

## 81. execution trace 与调度延迟解剖

- 分工：pprof 是聚合（谁烧了 CPU），trace 是时间线（谁在何时运行/
  等待、P 怎么用、GC 何时来）——"平均很快、偶尔 500ms"的时序问题
  只有 trace 能回答。
- 三视图判读：G 视图看单请求生命周期（runnable→running 的间隙=调度
  延迟；等锁/等 chan/等 GC 都有色块标注）；P 视图看利用率条纹（大片
  空白=P 闲着但 G 在等：全局锁、cgroup throttling（10 节）、netpoll
  延迟）；MMU 曲线看最坏情况保证。
- 黄金指标：runnable 未运行时间——trace 里短时间大量 runnable 堆积
  是延迟尖刺的调度器侧解释；把 /sched/goroutines:goroutines 按状态
  打点进监控，比 CPU 使用率诚实。
- 采集：1.21 重写的 tracer 开销大降且支持 flight recorder（环形
  buffer 常开、事故时导出最近 N 秒，golang.org/x/exp/trace）——生产
  常开+按需取；测试里 go test -trace 覆盖并发路径。
- 陷阱：trace 自身就是负载，绝对时长不作数（看结构与相对关系）；
  数值结论回到 benchmark（82-83 节）。

```go
import xtrace "golang.org/x/exp/trace"
r := xtrace.NewFlightRecorder(30 * time.Second)
r.Start()
defer r.Stop()
// 事故分支: r.WriteTo(f) — 最近 30 秒现场
```

## 82. benchmark 纪律与 b.Loop

- b.Loop（1.24）一次修掉两个祖传 bug：body 的结果被强制存活（老模式
  `for i := 0; i < b.N; i++ { f(x) }` 里 f(x) 结果没人用，整段可被
  编译器优化掉——测了个寂寞）；setup 自动排除计时（b.Loop 前的代码
  只跑一次，老模式要手动 ResetTimer 还容易忘）。
- 纪律组合：b.ReportAllocs（allocs/op 与 B/op 是性能评审硬通货，40
  节的目标值挂在这）+ b.RunParallel 测真并发（锁竞争成分单线程测不
  出）+ -benchtime 收敛（ns 级操作默认时长跑太久）。
- 状态泄漏：跨轮复用 map/缓存="越跑越快"假象——每轮状态放 setup
  重建；benchmark 的宿命是与真实负载脱节（缓存局部性/内联上下文都
  不同），微基准只筛明显劣势，最终结论回集成基准+trace（81 节）。
- CI 门槛：关键路径 allocs/op 设预算（超基线 X% 挂 PR），benchstat
  报告附进 PR——"没退化"要有证据链（83 节）。

```go
func benchParse(b *testing.B) {
    data := loadFixture() // setup: b.Loop 语义下只执行一次
    b.ReportAllocs()
    for b.Loop() {
        _, _ = Parse(data) // 结果被 b.Loop 保活, 不被优化掉
    }
}
```

## 83. benchstat 与统计显著性

- 单次 benchmark 是随机数：机器噪声/频率漂移/后台进程轻松摇进 ±10%
  ——"快了 8%"的单次结论默认全是噪声；-count=10 起步，benchstat 出
  分布对比；工具本身用 go.mod tool 指令管理
  （`go get -tool golang.org/x/perf/cmd/benchstat`，9 节），版本随
  仓库走、全员一致。
- 读表三件事：delta（中位变化）、±（置信区间）、p 值（<0.05 才可
  宣布显著）；~ 标记=无显著差异——看到 ~ 就闭嘴，别讲"其实快一点"
  的故事。
- 环境纪律：同机同频同载（锁性能模式、关后台、必要时 taskset 钉
  核）；跨机器对比一律无效；笔记本散热策略比你的代码改动影响大。
- 流程：改动前后各 -count=10 → benchstat old new → 显著且量级值得
  才合入；关键 PR 必附输出（82 节 CI 门槛）。
- 噪声治理：离群轮次不靠肉眼剔除（benchstat 的统计处理替你兜底），
  剩余异常先怀疑 setup 状态泄漏，再怀疑机器。

```bash
go test -bench=. -count=10 -run='^$' ./hot > new.txt
go tool benchstat old.txt new.txt # delta ± p; <0.05 才算数
```

## 84. 内联内幕与预算模型

- 模型：编译器给每个函数算内联成本（近似 IR 节点数，调用与控制流
  贵），超过 80 预算拒绝内联；小叶子函数（getter/简单包装）进 body
  零阻力，1.12 起中层内联沿预算递减展开。
- 循环至今是禁区：含 for 循环的函数不被内联（长期 open 的演进议题）
  ——热循环里的小函数拆成无循环形态才是正路，别指望循环体被展开进
  调用方。
- 闭包：函数字面量可被内联（捕获变量走环境传递），"闭包必分配"是
  过时认知，逃逸分析说了算（39 节）；defer 闭包是另一条路。
- 内联改变逃逸结论：内联发生在逃逸分析之前——被内联函数的参数
  逃逸按调用点重算，"参数 leaking"的理由会随内联成立/失败翻转；
  推论：关内联（-l）做的逃逸调优是自欺，最终构建 flags 下验证。
- 判读与实验：-gcflags='-m -m' 看成本（"cost 42: can inline"）；
  //go:noinline 只许出现在"验证内联收益"的对照实验里；PGO 给热点
  调用发预算加成（85 节）——默认进不去的函数被 profile 点名后能进。

```bash
go build -gcflags='-m -m' ./... 2>&1 | rg 'can inline|inlining cost'
# 对照实验: -gcflags=all='-l' 关内联, 仅用于验证
```

## 85. PGO 配置导向优化

- 工作流：代表性负载下采 CPU profile（≥30s、覆盖完整业务周期，78 节）
  → 命名 default.pgo 放主包目录 → go build 自动应用，-pgo=off 做对照；
  profile 文件进版本库，当代码资产管——它和 benchmark 基线一样重要。
- 两条机制：热点调用按 profile 加内联预算（84 节——默认进不去的函数
  在热点处展开，消调用开销+解锁后续优化）+ 热点代码布局重排（icache
  与分支预测局部性）。官方口径典型收益 2-14%，白捡但要测。
- 热点漂移：profile 里的热点在当前代码还存在吗？重构后老 profile 的
  收益归零甚至为负——按发布节奏重采（每版一次），default.pgo 不许
  变成没人敢删的化石。
- 陷阱：拿 idle/测试环境的 profile 应用到生产负载 = 无效甚至有害；
  5 秒的 profile 噪声主导；收益必须 benchstat 复核（82-83 节），
  PGO 不豁免统计纪律。
- 边界：PGO 是 CPU 侧工具——瓶颈在锁/IO/分配时先修那头（80/79 节），
  别对着 30% 的 GC 时间开 PGO 指望奇迹。

```bash
curl -s ':6060/debug/pprof/profile?seconds=60' > default.pgo
go build -pgo=off ./...  # 对照组
go build ./...           # default.pgo 自动生效
```

## 86. 字符串与字节切片零拷贝

- []byte(string) 与 string([]byte) 都是 O(n) 拷贝；编译器只免少数
  场景：map 索引 m[string(b)]、比较 string(b)=="x"、len(string(b))
  ——其余照拷，循环里"顺手转一下"就是每轮一次分配+拷贝。
- unsafe.String(ptr, len)/unsafe.StringData(s)（1.20）是零拷贝视图：
  得到的 string 永不修改（字符串不可变是 map 键、intern、runtime
  优化共同的地基）；底层 buffer 的生命周期/并发/释放要有书面约定。
- 内存放大陷阱：从大 buffer 视图切小字符串 = 几字节的头钉住整块
  MB 级 buffer 不放——解析器/协议头场景必须"小字段拷出来、大块
  payload 才用视图"，否则 RSS 曲线讲鬼故事。
- 常规武器优先：strings.Builder 拼接（增长 amortize）、strconv.
  AppendInt/AppendQuote（append 风格零分配）、map 键 intern（重复键
  去重再入表，省内存又稳哈希）。
- 评审红线：unsafe.String 出现必须附所有权注释+覆盖"底层被复用"
  的测试——这里出 bug 是静默数据竞争，race detector 未必救（29 节）。

```go
// 大 buffer 解析: 小字段拷走, 大 payload 才用视图
func parse(b []byte) (magic, payload string) {
    magic = string(b[:4]) // 4B: 拷出去, b 之后可被回收复用
    payload = unsafe.String(&b[4], len(b)-4) // 视图: b 冻结只读
    return
}
```

## 87. JSON 性能工程

- v1 成本结构：反射编解码+字段名缓存 lookup、number→interface{} 装箱
  （float64+分配）；map[string]any 反序列化是单位价值最差路径——
  "解析成 map 再取字段"在大报文上是分配风暴，改具体结构体永远第一。
- v1 工程手段：流式 Encoder/Decoder（避免整批 []byte 驻留；Encoder
  复用注意 Encode 会追加换行）、SetEscapeHTML(false)（确认输出面
  无风险后省扫描+更小输出）、json.RawMessage 透传不认识的字段、
  大数组用 Decoder 流式消费。
- 选型梯子：标准库优先（零依赖、版本兼容最稳）→ easyjson（codegen，
  生成代码进仓库是维护税）→ sonic（JIT，amd64 深优化、跨架构回退，
  依赖汇编与运行时内幕——109 节风险联动，升级 Go 前查兼容矩阵）。
- json/v2（1.25 GOEXPERIMENT，13 节）：反射成本与分配设计上就低、
  错误带 offset、选项替代全局行为；迁移先跑自己的 payload 集合建
  行为差异表再灰度；转正前的生产决策仍是"标准库 or 编译期生成"。
- 度量先行：JSON 在火焰图的真实占比（78 节）决定投入——占比个位数
  先去治分配与 GC（40 节），别在 5% 的地方卷。

```go
enc := json.NewEncoder(&buf)
enc.SetEscapeHTML(false) // 确认输出面无风险后启用
for _, v := range items {
    _ = enc.Encode(v) // 流式: 不为整批建巨型 []byte
}
```

## 88. 结构体布局与内存对齐

- 规则：字段对齐到自身大小的整数倍（bool 1/int32 4/int64 8），结构体
  大小向最大字段对齐取整——{bool, int64, bool}=24B 而 {int64, bool,
  bool}=16B；字段按对齐降序排，fieldalignment lint 进 CI 守门。
- 切片是乘法题：百万级元素每省 8B=省 8MB——批处理/网络 buffer 结构体
  逐个排；unsafe.Sizeof 断言进测试，防字段增删悄悄翻倍。
- 64 位原子字段：32 位平台要求 8B 对齐，堆分配对象首字保证对齐但切片
  逐元素不保证——直接用 atomic.Int64（自带对齐标记，28 节）替裸
  int64 字段是唯一稳妥姿势。
- false sharing 联动（32 节）：[]atomic.Int64 连排=相邻计数器同缓存行
  乒乓；高竞争计数器按 64B（Apple 平台 128B）隔离或分片。
- GC 联动（36 节）：指针字段=标记成本——热数据结构把指针拆出去
  （[1e6]float64 而非 [1e6]*float64；ID 数组替指针数组），标记扫描
  直接受益。

```go
type bad struct{ ok bool; n int64; dirty bool }  // 24B 含 padding
type good struct{ n int64; ok, dirty bool }      // 16B
const _ = 16 - unsafe.Sizeof(good{}) // 超 16B 编译期直接报错
```

## 89. 表驱动测试高级形态

- 列设计进阶项：name（失败输出主键）、input、want、wantErr（错误
  哨兵或匹配函数，不是 bool——bool 只验"有错"，放过"错对了"）、
  builder（构造器注入：每 case 独立构置被测对象与依赖，杜绝共享
  状态）。
- wantErr 用 errors.Is 哨兵或 func(error) bool 精确匹配——"返回了
  个错也算过"的表驱动是覆盖率幻觉（97 节）；不该错的 case 显式
  wantErr: nil，别靠"没有 case"兜底。
- 子测试隔离：t.Run 每 case 独立失败域、名字稳定可 grep；大数据
  构造放 case 内闭包（惰性构造），表字面量保持可扫读；清理挂
  t.Cleanup（90 节生命周期）。
- 可演进性：加列不动行；行数>20 或列>8 拆成按行为组的多张表——
  一张巨型表什么都验 = 每次改动全表陪跑 + 定位困难。
- 并行联动（95 节）：1.22 后表格直接 t.Parallel；老模块先确认 go 行
  再放开——行内可变字段（共享 slice/map fixture）依旧是雷。

```go
cases := []struct {
    name    string
    in      string
    want    int
    wantErr error // nil=必须无错; 哨兵=精确验
}{
    {"empty", "", 0, nil},
    {"neg", "-7", -7, nil},
    {"junk", "x1", 0, ErrSyntax},
}
// for _, tc := range cases { t.Run(tc.name, ...) }
```

## 90. t.Context 与测试生命周期

- 1.24 t.Context()：测试完成后自动取消——被测代码的"取消后行为"
  （清理、中断传播、Done 分支）第一次有了零样板测法；新测试默认
  用它替代 context.Background()，别再让取消路径裸奔。
- 生命周期顺序背下来：测试体返回 → t.Context 取消 → Cleanup 按注册
  逆序执行 → 测试结束。Cleanup 里看到的 ctx 已取消——收尾需要存活
  ctx（调远程注销）就在测试体内自建 ctx 捕获进 Cleanup 闭包。
- t.Chdir（1.24）：临时切换工作目录、Cleanup 自动还原；并行测试里
  调用直接 panic——工作目录是进程全局态，物理上与并行不兼容；想
  并行的目录敏感逻辑改注入路径（t.TempDir()）。
- 子测试继承：t.Context 对子测试透明传递；Chdir 效果持续到子测试
  结束；TestMain 才是进程级入口（容器/大 fixture 在此起收）。
- 红线：把 t 塞进 ctx 传递、测试结束后再用 t（必 panic）、在非测试
  goroutine 里 t.Fatal——FailNow 走 runtime.Goexit 只杀那个
  goroutine，测试挂起；失败信息用通道回传。

```go
func TestShutdown(t *testing.T) {
    ctx := t.Context() // 测试结束自动取消
    svc := New(ctx)
    go svc.Run(ctx)
    t.Cleanup(func() { <-svc.Done() }) // 等真退出(94 节好搭档)
}
```

## 91. testing/synctest 气泡时钟

- 1.24 实验、1.25 转正：synctest.Run 把圈内 goroutine 关进 bubble，
  time 全虚拟化——全员阻塞时逻辑时钟直接跳到下一个 timer 到期点。
  重试/超时/心跳逻辑毫秒内确定性跑完，time.Sleep 与"运气驱动"的
  flaky 一起退场。
- Wait 语义：等到 bubble 内全部阻塞——把异步收敛点写显式；全员阻塞
  且没有任何 timer → panic "all goroutines in bubble are blocked"：
  死锁从"挂 10 分钟超时"变"当场红牌"，这是它最值钱的交付物。
- 气泡边界纪律：bubble 内禁止真实世界 IO——网络/文件阻塞时全员卡死
  触发假死锁 panic；成员不能被外部 goroutine 唤醒。外部接缝全部换
  假实现（93 节），bubble 里只放纯逻辑+虚拟时钟。
- 时钟细节：bubble 时钟起点=进入时刻的真实时间，此后纯逻辑推进；
  time.Now() 读虚拟值——断言"重试 3 次、间隔指数退避"直接数调用
  次数、对虚拟时刻，测试再不怕机器时间快慢。
- 陷阱：bubble 里 go 出去的逻辑没被 Wait 覆盖 → 测试先于逻辑结束；
  一个 bubble 一组时序，别塞互不相干的多组场景。

```go
func TestRetryBackoff(t *testing.T) {
    synctest.Run(func() {
        var n int
        r := NewRetry(3, time.Second) // 1s 退避: 虚拟, 瞬间走完
        _ = r.Do(func() error { n++; return errors.New("boom") })
        if n != 3 { t.Errorf("n = %d", n) }
    })
}
```

## 92. fuzz 模糊测试纪律

- 双模式：`go test` 只跑 f.Add 种子与 testdata 语料（秒级回归）；只有
  `-fuzz=FuzzX -fuzztime=60s` 才进变异引擎（覆盖率导向）——CI 两条
  腿：每次 push 跑种子，夜间/合并前给时间预算。
- 崩溃产物进版本库：失败输入自动写 testdata/fuzz/<FuzzName>/ 并转
  种子（下次 go test 永久回归）；线上坏输入脱敏后 f.Add 是把事故
  变资产的正路（111 节同款方法论）。
- fuzz 函数纪律：任意输入要么过、要么确定性失败——非确定性（时间
  依赖、并发、map 遍历序）放进来全是假阳性；语料参数只能是基本
  类型/string/[]byte，复杂输入在函数内构造。
- 选靶子：解析器/协议解码/UTF-8 处理/正则是高产区；业务逻辑配性质
  断言（round-trip、幂等、不变量）才有效——纯 fuzz 瞎逛收益低。
- 语料治理：testdata 自然膨胀，定期 -fuzzminimize 压最小复现；种子
  文件是评审对象——每份都讲得出"防的是哪次事故"。

```go
f.Fuzz(func(t *testing.T, b []byte) {
    v, err := Decode(b)
    if err != nil { return } // 解析失败是正常路径, 不 fail
    if re, rerr := Encode(v); rerr == nil && !bytes.Equal(b, re) {
        t.Fatalf("round-trip mismatch: %q", b) // 性质断言
    }
})
```

## 93. 接口替身与 mock 策略

- 保真度铁律：真实现 > fake > 手写桩 > mock 框架——顺序即行为信心。
  能跑真逻辑的 fake（内存版存储/总线）给行为级信心；mock 给交互级
  断言，而交互断言会随重构成片假红。
- 消费者窄接口（71 节）是可测性真源：替身=实现小接口的 20 行结构体，
  根本用不上代码生成；gomock 官方仓已归档（社区接力在
  go.uber.org/mock）——新项目先问"真的需要 mock 框架吗"，八成不需要。
- HTTP 边界用 httptest.Server：真 HTTP 往返（真实序列化/头部/状态码
  语义）+可编程 handler 注入错误与延迟——比 mock http.Client 保真
  一个量级，还能测超时与重试路径（104 节联动）。
- 别 mock 不属于你的类型：sql.DB/http.Client/云 SDK client——包一层
  自己的窄接口再替身那个；直接 mock 第三方具体类型 = 把它的每次
  升级变成你的测试地狱。
- 替身工程化：桩带调用计数+捕获参数（断言"传了什么"优于"调了几
  次"）；错误注入是桩的配置项；共享替身放 internal/test（98 节）。

```go
type fakeStore struct {
    mu  sync.Mutex
    m   map[string]*Item
    hit atomic.Int32 // 计数+捕获: 少量交互断言
}
func (f *fakeStore) Get(_ context.Context, k string) (*Item, error) {
    f.hit.Add(1)
    f.mu.Lock()
    defer f.mu.Unlock() // 并行测试安全(95 节)
    if it, ok := f.m[k]; ok { return it, nil }
    return nil, domain.ErrNotFound // 行为级: 真实语义
}
```

## 94. goroutine 泄漏测试 goleak

- 防线位置：goleak.VerifyTestMain(m) 挂 TestMain——全部测试结束后
  重复采样对比启动基线，残留 goroutine 连栈打印判负；并发组件（池/
  消息循环/后台任务）的单测再加 VerifyNone(t) 即时拦截。
- 基线豁免：有意常驻的任务用 goleak.IgnoreTopFunction("pkg.func")
  按栈顶精确豁免——豁免清单是评审对象，每行注明"谁、为何常驻"；
  新泄漏依旧会暴露（豁免不是全量放行）。
- 慢收尾先收敛：优雅退出要时间的组件，测试里显式等完（<-done/
  Wait）再交给 goleak——它默认只重试约 1 秒；等不起的慢清理用
  虚拟时钟测试（91 节），不是加 goleak 重试。
- 失败解读：goleak 打出的栈是泄漏者身份，但"谁负责它退出"回到 55
  节两问——测试失败暴露的是设计缺口，不是"goleak 误报"。
- 与生产互补：CI goleak 拦新代码引入的泄漏，生产 goroutine 基线
  打点拦流量养出来的泄漏（25 节）——两道防线缺一不可。

```go
func TestMain(m *testing.M) {
    goleak.VerifyTestMain(m,
        goleak.IgnoreTopFunction("pkg/janitor.(*Janitor).run"),
    ) // 常驻任务显式豁免+注明理由; 新泄漏照样抓
}
```

## 95. t.Parallel 深水

- 语义精确版：t.Parallel() 把测试暂停挂起，父测试体继续；挂起的兄弟
  测试在父体返回后并行恢复，并发度由 -parallel（默认 GOMAXPROCS）
  裁决——"父 defer 清理资源"与"并行子测试还没跑"在同一条时间线上
  冲突：父 defer 在父体返回时已执行。共享资源用 t.Cleanup（它等所有
  子测试结束），不用 defer。
- 顺序盲区：并行子测试执行顺序随机——任何"先 A 后 B"的隐式依赖
  都是 flaky 之源；要顺序就写顺序测试，并行留给无依赖用例。
- 表驱动+并行：1.22 循环变量每轮新建（1 节），捕获 tc 不再需要
  tc := tc；但表行里的可变字段（slice/map/共享 fixture）仍会被并发
  触碰——只读表+每例自建数据的纪律不变（89 节）。
- 全局态互斥：t.Setenv、t.Chdir 与并行互斥（直接 panic，90 节）——
  环境/目录敏感用例单独成串行测试或抽象注入。
- CI 价值与代价：套件时长近似除以核数，但共享 fixture 的隐式耦合
  让"随机红、重跑绿"成为日常——并行是纪律达标的奖励，不是默认人权。

```go
for _, tc := range cases { // 1.22+: tc 每轮新建, 免拷贝
    t.Run(tc.name, func(t *testing.T) {
        t.Parallel()      // 父体返回后才真正开跑
        got := run(tc.in) // 表只读; 数据每例自建
        if got != tc.want {
            t.Errorf("%s: got %d", tc.name, got)
        }
    })
}
```

## 96. 测试分层与构建标签

- 分层判据是"要不要真实依赖"，不是文件放哪：unit（毫秒级、无 IO，
  push 必跑）/ integration（真 DB/MQ/容器，//go:build integration）/
  e2e（打真部署，独立模块或仓库）。
- 标签纪律：//go:build integration 单行表达式（老 // +build 已死，
  gofmt 迁移）；测试与共享 helper 双双进标签——漏标的 helper 在无标
  构建里引用带标类型直接炸编译；CI 双跑 go test ./... 与
  go test -tags=integration ./...（go vet 同理加 tags）。
- -short 的位置：testing.Short() 跳"慢但无外部依赖"的用例；真依赖用
  hard tag——-short 跳过的测试是绿色测试，掩盖比失败危险。
- 时间预算：unit <5min 全量 push；integration 合并队列跑（服务容器
  testcontainers/compose）；e2e 夜间+发布前。flaky 进隔离 tag 限期
  修复，别污染主干信号。
- 缓存陷阱：go test 结果缓存按二进制+输入哈希——integration 依赖的
  外部状态不参与哈希，CI 一律 -count=1 强制真跑。

```bash
go test -race ./...                             # push: unit
go test -race -tags=integration -count=1 ./...  # merge: 免缓存
go test -short ./...                            # 本地: 快反馈
```

## 97. 覆盖率哲学与断言纪律

- 行覆盖度量"执行过"不是"验证过"：无断言的测试也能拉满覆盖——
  覆盖率是未测代码的地图（哪些分支从没进过），不是质量分数；门禁盯
  "新增代码有没有测"，别盯总数（会被无断言测试稀释成安慰剂）。
- 值得追的是难覆盖分支：错误路径（故障注入/桩，93 节）、并发交错
  （synctest，91 节）、超时与时序（虚拟时钟）——这些是事故来源地；
  happy path 的覆盖率增长是装饰品。
- 1.20+ 集成覆盖：go build -cover -coverpkg=./... 产出的覆盖二进制在
  真环境跑，GOCOVERDIR 收集，go tool covdata textfmt 合并——协议层/
  部署路径终于有账可查；有常驻开销，灰度一台起步。
- 断言纪律：失败信息自带定位（got/want/输入，不重跑就能改）；表驱动
  用 t.Errorf 连续报全量 case，setup 失败用 t.Fatal（继续没意义）；
  禁 t.Log 当断言——日志绿的测试是伪绿。
- 反模式：`if err != nil {}` 空分支刷覆盖、无 message 的 assert、
  测试间共享状态、为覆盖率拆函数制造测试点。

```go
if got != tc.want {
    t.Errorf("%s: Parse(%q) = %d, want %d",
        tc.name, tc.in, got, tc.want) // 一次失败=一条可行动信息
}
```

## 98. 项目布局与 internal 纪律

- internal 是编译器执法的边界：internal/ 里的包只有父目录树内可
  import，连同模块的"外人"也不行——默认一切 internal，根包/显式
  api/ 只放愿意背兼容承诺的东西；导出是负债，internal 是免费护栏。
- cmd/<app>/main.go 只做装配：读配置、建依赖、调 app、翻译退出码
  ——逻辑全在 internal/app；main 薄到不用测，但退出语义（信号、
  清理顺序，56 节）必须在 app 层可测。
- 按能力域分包不按技术层：internal/order、internal/billing 各自高
  内聚；utils/common/shared 是引力黑洞——东西进去就没了归属，跨域
  耦合在这里悄悄发生。真通用的件独立成模块，让 import 成本逼你三思。
- 循环依赖的正确解法不是挪文件：依赖倒置——消费者定义窄接口（71
  节）注入回来；挪文件只是把环换个平面再摔一次。
- 测试基建：internal/test 存共享替身与断言（93 节）；export_test.go
  把内部符号只对测试导出——测私有行为不必把私有变成公共 API。

```go
// cmd/app/main.go: 装配层薄到不需要测
func main() {
    if err := app.Run(wire(config.MustLoad())); err != nil {
        os.Exit(1) // 信号/清理/退出码语义全部在 app 层可测(56 节)
    }
}
```

## 99. go.mod 深水：MVS 与 replace 陷阱

- MVS 与 npm 直觉相反：每个依赖的最终版本=模块图里所有人声明版本的
  最大值，没有最近优先、没有锁文件——你 go.mod 里写的是下限不是
  钉死；要抬被传递压住的依赖，只能把它写进自己 go.mod 提下限
  （go get dep@v1.5.0），没有"锁版本"这回事。
- replace 只在主模块生效、永不传递——库作者写的 replace 对下游全部
  失效（对下游是谎言）；正当用途=本地联调（replace ../foo）与紧急
  跳版（上游损坏强制换实现），发布前清理，go mod tidy 不替你背锅。
- go.sum 是校验不是锁：哈希防投毒（公开模块过 sum.golang.org），
  CI 里 go mod verify；GOPRIVATE=私仓前缀（同时豁免 proxy 与 sumdb
  的糖），GONOPROXY/GONOSUMDB 是它的两个分项——语义分清再配。
- 1.17+ 模块图剪枝：依赖 go.mod 只列直接依赖、加载延迟化——看不见
  的传递依赖更多了，go mod graph 与 go mod why -m 是取证的手术灯。
- 大版本升级=换路径（/v2 后缀是全新模块）；go get -u ./... 是全量
  抬级的重构级操作——升级点名做，单独 PR 单独审（16 节升级 SOP）。

```bash
go mod graph | rg 'x/sync'      # 谁把旧版本拉进来的
go mod why -m golang.org/x/sync # 这依赖凭什么存在
go mod edit -replace=github.com/x/order=../order # 联调, 发布前删
```

## 100. workspace 多模块协作

- go.work 的本质：把 use 列出的目录在本地构建时直接取代版本引用
  （效果=批量 replace），而各模块 go.mod 一个字不改——库与服务同改
  的联调互踩问题就此终结，fake 版本号与忘删的 replace 退场。
- 工作流：修库 → 服务立即用新代码跑测试；go work sync 把 workspace
  的解析结论回写各模块 go.mod 的 requirement——让"workspace 里能跑"
  的组合留下可复现痕迹。
- 最大的坑：workspace 掩盖真实版本组合——本地永远"恰好一起改好"，
  发布出去按 go.mod 的老版本跑。CI 用 GOWORK=off 跑全量（模拟下游
  视角），合并门禁必含"关掉 workspace 也绿"。
- go.work 提交与否二选一并写进规范：提交（团队共享联调环境）或
  gitignore（各自本地）；半提交状态=依赖地狱复刻。vendor 目录与
  workspace 互相干扰的场景，用 GOWORK=off 验证发布构建。
- 拆模块时机：编译墙（全量重编太慢）/发布节奏不同/所有权边界才值得
  拆——每模块都是独立版本化+依赖维护成本，"看着清爽"不值；先试
  内部分包（98 节）。

```bash
go work init ./ ./libs/order # use 目录取代版本引用
go work sync                 # 解析结论回写各模块 go.mod
GOWORK=off go test ./...     # CI: 摘滤镜跑真实版本组合
```

## 101. govulncheck 与供应链安全

- 与漏洞扫描器的本质区别：符号级可达性——用调用图分析你的代码是否
  真调到漏洞符号（CVE 常只影响一个函数："依赖带 CVE"≠"你暴露"）；
  报告分 reachable 与 informational，门禁策略：reachable 高危挂 CI，
  不可达进 backlog 限期升级。
- 数据源是 OSV（Go 漏洞库）；-mode source 静态分析、-mode binary 查
  制品（无源码场景）；离线环境 -db 指自建镜像（漏洞库定期同步）——
  审计 CI 不该有"连不上外网就算了"这个选项。
- CI 编排：push 级 reachable 检查（分钟级）+夜间全量——新披露漏洞
  打老代码是常态，今天绿≠明天绿；被漏洞逼出的破坏性升级走独立 PR
  （16 节升级纪律）。
- 私仓与依赖混淆：GOPRIVATE 让私有模块绕开公共 proxy/sumdb；
  GOPROXY 指公司代理+白名单放行——go.mod 的路径声明与代理放行两道
  闸一起防"抢注内部模块名"攻击（真实攻击面，不是理论）。
- 生态分工：govulncheck 管运行时依赖；工具链自身漏洞跟版本升级纪律
  （16 节）；制品完整性=可复现构建+go version -m 审计（102 节）。

```bash
govulncheck ./...               # push: reachable 门禁
govulncheck -mode binary ./app  # 制品库存量二进制巡检
go mod verify                   # 模块内容哈希核验(防篡改)
```

## 102. 构建矩阵：tags、trimpath 与交叉编译

- //go:build 是唯一事实源：单行布尔表达式（goos.goarch、cgo、自定义
  tag 组合），必须在 package 声明前；文件名后缀（_linux.go、_amd64.go）
  本身也是构建约束——后缀与显式标签并存时先想清楚谁在生效，别两套
  机制互相打架。
- -trimpath 双重价值：二进制不嵌构建机绝对路径（栈与 pprof 显示
  module@version/file.go——比机器路径还好用）+构建可复现（同输入
  同哈希，制品审计与缓存双赢）；配 -buildvcs=false 切断 .git 状态
  掺入。生产构建默认开。
- 交叉编译矩阵：GOOS/GOARCH/GOARM 组合在 CI 至少 build-only 全编译
  ——路径分隔符、syscall 差异、32 位对齐（88 节）都是编译期才露馅；
  纯 Go 矩阵零成本没有理由不跑；cgo 交叉要目标工具链（zig cc 省心）。
- 编译过≠行为对：信号、终端、文件锁在 GOOS 间各有脾气——关键平台
  至少一个真容器/真机 e2e（96 节分层）。
- 制品三件套纪律：-trimpath、CGO 策略显式（103 节）、go version -m
  审计进流水线（构建元数据可追溯，101 节联动）。

```bash
GOOS=windows GOARCH=amd64 go build -trimpath -o app.exe ./cmd/app
GOOS=linux GOARCH=arm64 CGO_ENABLED=0 go build -trimpath ./...
go version -m app.exe # 模块版本/构建标志审计
```

## 103. 静态编译与 CGO_ENABLED=0 边界

- 纯静态收益：CGO_ENABLED=0 出无动态链接依赖的二进制——scratch/
  distroless 镜像、与宿主 glibc/musl 彻底解耦、无基础镜像漂移问题；
  服务端默认就该这么发。
- 代价一 DNS：换纯 Go 解析器——不走 libc/NSS：nsswitch 非常规源
  （LDAP 等）、resolv.conf 部分 option 不受支持；k8s（ndots:5+search
  域）下两代解析器的查询顺序差异曾是集群级 DNS 超时事故的放大器
  ——短名解析行为要显式验证（ndots 调整或 FQDN 化）。
- 代价二 用户与组：os/user 直读 /etc/passwd（无 SSSD/LDAP）——企业
  环境用户查不到是"升级后突然坏了"的常客；时区敏感就用 time/tzdata
  显式内嵌，别赌宿主。
- 真需要 cgo 的场景：-race（必须 cgo）、无纯 Go 驱动的硬件/数据库、
  交付 c-archive/c-shared 给别家嵌入——其余一律 CGO_ENABLED=0 且
  显式写进构建脚本（102 节矩阵里锁死）。
- 取证：file/ldd 看链接形态；GODEBUG=netdns=go|cgo 强制切换解析器
  做 A/B；"本地好好的、容器里 DNS/用户全炸"先怀疑这两处，历史九成是它。

```bash
CGO_ENABLED=0 go build -trimpath -o app ./cmd/app
ldd app                  # "not a dynamic executable" = 纯静态
GODEBUG=netdns=go ./app  # 强制纯 Go 解析器复现行为差异
```

## 104. net/http 客户端生产调优

- 零值 http.Client 没有任何超时——对端挂死=永久挂起的请求+堆积的
  连接：client.Timeout 全链路兜底必设；精细治理用 Transport 分阶段
  超时+请求级 ctx（取消传播、deadline 归属清晰）——粗护栏与细预算
  两层都要。
- 复用是第一纪律：进程共享一个 Transport（连接池是它的心脏）——每
  请求新建 Transport=连接永不复用+TLS 握手风暴+fd 抖动；自定义从
  http.DefaultTransport.Clone() 起步，别从零值写起（会丢掉默认值里
  的隐性经验）。
- MaxIdleConnsPerHost 默认 2 是隐形杀手：对单依赖高 QPS 时大量连接
  用完即弃（关而复开）；上调到峰值并发量级，配 MaxIdleConns 全局
  上限与 IdleConnTimeout。h2 多路复用后数学变了（一条连接跑流），
  别按 h1 经验抄参数。
- 响应体纪律：不排干就 Close=连接作废不复用——
  io.Copy(io.Discard, resp.Body) 再关；防恶意超大响应加 io.LimitReader。
- 重试语义：只重试"请求未生效"的失败（连接失败、空闲连接被踢）；
  响应已开始的重试=幂等性赌博（68 节）；429/Retry-After 要尊重，
  重试预算与下游限流对齐（57 节）。

```go
tr := http.DefaultTransport.(*http.Transport).Clone()
tr.MaxIdleConnsPerHost = 64           // 默认 2: 单依赖高 QPS 必调
tr.IdleConnTimeout = 90 * time.Second
client := &http.Client{Transport: tr, Timeout: 10 * time.Second}
```

## 105. net/http 服务端纪律

- 超时按阶段设防：ReadHeaderTimeout（慢连接攻击的解药——头部必须
  按时到）、ReadTimeout（含 body 读取）、WriteTimeout（响应写出；
  流式/长响应注意它会砍活着的连接——SSE/大下载显式另设）、
  IdleTimeout（keep-alive 空闲）。全默认=挂死连接慢慢吃光 fd。
- Server 超时是粗护栏，细预算归 ctx：handler 内 WithTimeout 定业务
  预算（51/55 节）；优雅停机链路（NotifyContext→摘流→Shutdown→收尾）
  在 56 节，被等的是活跃 handler 走完。
- h2 事实：TLS 服务自动经 ALPN 协商 h2、单连接多流——ReadHeader 超时
  与 h2 的流语义不完全对齐（帧层面），压测要用真 h2 客户端；代理
  后面的 h2c 是代理的事，Go 侧别自己拉。
- 每连接状态用 ConnContext：连接建立时注入随连接存活的 ctx——
  websocket 升级跟踪、连接级限流挂它；别再拿 RemoteAddr 当 map 键
  （NAT/代理下重复+泄漏双坑）。
- 公网还要 MaxHeaderBytes 调小与 http.MaxBytesReader 限请求体——
  默认行为对公网太宽容。

```go
srv := &http.Server{
    ReadHeaderTimeout: 5 * time.Second,  // 慢连接攻击解药
    ReadTimeout:       30 * time.Second,
    WriteTimeout:      30 * time.Second,
    IdleTimeout:       120 * time.Second,
    ConnContext:       injectConnState, // 连接级状态唯一正解
}
```

## 106. database/sql 连接池纪律

- 默认值全是坑：MaxOpenConns=0 无上限（突发流量打爆数据库端连接数）；
  MaxIdleConns=2——稳态并发超 2 时连接用完即弃：开-关风暴，连接数
  抖动直线上天。起步姿势：MaxOpen=配额份额，MaxIdle=MaxOpen（突发
  后连接不扔）。
- ConnMaxLifetime 管外部世界：LB/DNS/代理静默掐连接、云数据库强制
  断开——30 分钟级上限让连接主动换代；ConnMaxIdleTime 收闲置连接
  省两端资源；两个都要，只有 lifetime 不够（idle 照样占坑）。
- 超池即等待：超过 MaxOpen 的调用阻塞等连接（不是报错）——没有
  ctx 的等待是无底洞：一律用 *Context 方法族；db.Stats() 的
  WaitCount/WaitDuration 上监控，池饥饿的第一指标。
- rows 纪律：不迭代到底/不 Close=该连接一直忙（驱动还在后台拉数据）
  → 池容量慢性失血；defer rows.Close() + 循环后查 rows.Err()。Tx
  独占一条连接整段持有——长事务=池缩容，事务里别做慢 IO。
- Stmt 真相：db.Prepare 的语句按连接各自准备（每条新连接首次执行都
  要 prepare）——高频简单查询直接 QueryContext 常常更省；prepare
  省的是复杂 SQL 的解析成本，不是连接成本。

```go
db.SetMaxOpenConns(50)
db.SetMaxIdleConns(50)                 // = MaxOpen: 突发后不扔
db.SetConnMaxLifetime(30 * time.Minute)
db.SetConnMaxIdleTime(5 * time.Minute)
// 监控: db.Stats().WaitDuration — 池饥饿主指标
```

## 107. slog 结构化可观测架构

- 分层模型：Logger 不可变，Handler 独占格式与出口（生产 JSONHandler、
  本地 TextHandler）；logger.With 派生预置属性的子 logger——请求
  入口打点 traceID/租户，往下自动携带，别在每条日志手抄字段。
- 热路径守卫：昂贵属性先 logger.Enabled(ctx, slog.LevelDebug) 再
  构造；LogValuer 让懒求值进类型系统——Attr 装的是承诺，handler
  真要写才展开。
- 字段纪律：msg 是稳定短语（告警聚合主键），事实全进字段——变量拼
  进 msg 同时杀死聚合与检索；错误键全组织统一（"err"），
  slog.Any("err", err) 保住类型信息；顶层唯一日志点的分工在 67 节。
- 1.26 NewMultiHandler：官方扇出多目的地（stdout JSON + 采样到
  OTLP/文件），1.25 前手写的多路 wrapper 有了替身；采样/限流仍要
  自定义 Handler 在 Emit 前丢弃——日志风暴是观测系统自身的 OOM。
- ctx 的用途：handler 从 ctx 提取分布式元数据（traceID），中间件
  注入一次全线受益——调用方永远不手传。

```go
log := slog.Default().With("svc", "checkout") // 预置, 不再手抄
if log.Enabled(ctx, slog.LevelDebug) {
    log.DebugContext(ctx, "cart dump", "cart", lazyDump(c)) // 贵值守卫
}
```

## 108. 二进制体积与启动优化

- -ldflags "-s -w" 第一档（符号表+DWARF 去掉，约省四分之一）：Go
  运行时符号化走 pclntab，pprof 照常出函数名——生产镜像大胆去；
  delve 调试需要 DWARF——debug 版与 release 版都进制品库（102 节
  可复现构建保证可对应）。
- 体积去向先测量再砍：go tool nm / bloaty / go-size-analyze 看段落
  与包贡献——真相常是"一个 SDK 连带 30MB 依赖网"；go mod why -m
  找元凶，依赖瘦身比 strip 收益大一个量级。
- upx 的账：磁盘换内存与启动（解压进 RSS、冷启动变慢、杀软误报、
  部分容器运行时拒载）——CLI 分发偶有用武，服务端几乎总是负收益。
- 启动成本藏在 init：GODEBUG=inittrace=1 逐包打印 init 耗时——配置
  解析、大 map 构建、驱动注册、连接预热排排坐；重活移出 init
  （sync.Once 惰性化，110 节），启动到可服务的时长进监控。
- 固定底座认了：runtime+net 的 ~2MB 砍不掉；极限场景才考虑 TinyGo
  （另一套运行时语义，服务端先想清楚再上）。

```bash
go build -ldflags="-s -w" -trimpath -o app ./cmd/app
GODEBUG=inittrace=1 ./app 2>&1 | head -20 # init 逐包账单
```

## 109. cgo 深水与 linkname 危险品

- 成本与线程账本：一次 cgo 调用=两次栈切换+信号掩码处理，开销是纯
  Go 调用的百倍量级（1.26 降约 30%，仍是重量级）；调用期间 M 被 C
  独占、长调用触发 P handoff（23 节）——密集 cgo 服务的线程膨胀
  （21 节 SetMaxThreads 报警）+调度毛刺，两笔账都进监控。
- 指针规则背下来：Go 指针可以传 C，但 C 不得在调用返回后保留它；
  传给 C 的 Go 内存不得包含 Go 指针（结构体/数组字段逐层算）——
  违规症状是随机崩溃。GODEBUG=cgocheck=2（1.21 起为
  GOEXPERIMENT=cgocheck2）开严格校验压测。
- 内存归属分家：C.malloc 的内存 Go GC 永远不收——成对的 free 写进
  同一个 wrapper 的 defer；C 里注册回调必须走 //export 桥（runtime
  注册的句柄），乱传函数指针=崩。
- //go:linkname 是危险品：把代码绑到 runtime/stdlib 未导出符号——
  1.21 起编译器持续收紧，靠它取巧的库随小版本连环碎；真要用：锁死
  工具链版本（16 节）+升级矩阵单独验证（sonic 这类 JIT 库同属风险
  名单，87 节）。
- 诊断：混合栈要 GOTRACEBACK=system/crash 才完整（26 节）；C 侧数据
  竞争 race detector 全盲（29 节）——C 代码的并发测试靠 ASan/valgrind
  另开战场。

```go
p := C.malloc(C.size_t(n))
defer C.free(p) // 归属清晰: C 的内存 C 释放, defer 收口
C.process((*C.uint8_t)(p), C.size_t(n))
```

## 110. 反模式清单：评审红线

- init 干重活：解析配置、建连接、跑迁移——import 顺序变成隐式启动
  协议，测试 import 连坐；驱动注册式"纯登记"是 init 唯一正当场景。
  重活 sync.Once 惰性化，谁用谁触发。
- 包级可变状态：全局 map/slice 被并发读写=隐藏耦合+竞态温床（30
  节），测试被迫串行清场。依赖进结构体；包级只放常量与构造器。
- 错误吞噬家族：`_ = err`（errcheck）、出错返回 nil（nilerr）、
  log.Error 后再上抛同一颗（67 节复读机）、err.Error() 文案匹配
  （64 节）——四连出现，评审直接退回。
- any 参数化：`func Do(x any)` 逼调用方装箱（69 节）+运行期断言税；
  该泛型给泛型、该具体给具体。同族：返回值用接口"保持灵活"。
- goroutine 无主（55 节）、defer 进循环（65 节）、锁被值拷贝（75 节
  vet 抓）、ctx 存字段（52 节）、panic 当流程控制（66 节）——每条
  都对应一条 lint；//nolint 必须带理由与清偿期限，nolint 总量 CI
  ratchet 只减不增。

```go
// 反面教材三连
var cache = map[string]any{} // 全局可变: 竞态+测试地狱
func Do(x any) error {       // any 化: 装箱+断言税
    _ = save(x)              // 吞错: 静默失败
    return nil
}
```

## 111. 生产事故复盘三例

- 例一 GC limiter 假死：GOMEMLIMIT=容器上限+注册表 map 泄漏把 live 堆
  顶到限额 → GC 转入极限模式（limiter 封顶约 50% CPU 兜底，38 节）
  → 服务"活着但请求十秒起步"，误诊"数据库慢"两天。破案钥匙：
  runtime/metrics 的 /gc/limiter/last-enabled 拉响+gctrace 连续 GC+
  heap profile inuse 指认泄漏 map（45 节）。修复：修泄漏、GOMEMLIMIT
  回 90%、GC CPU 占比进告警。
- 例二 CFS throttling 假死：k8s limit=2 核节点上老版本按宿主 64 核起
  GOMAXPROCS——每 100ms 配额在前几毫秒烧光，其余时间全员 throttled：
  P99 尖刺、健康检查超时、重启风暴（10 节）。铁证是容器指标
  throttled 率；修复=对齐 GOMAXPROCS/升 1.25，CPU throttle 率进
  告警——别拿"加机器"治配额病。
- 例三 timer 泄漏 OOM：1.22 前的代码在每请求 select 里 time.After(5s)
  ——10k QPS 下堆上常驻 5 万+未到期 timer 及闭包引用，RSS 爬到
  OOMKill，被当"Go 内存泄漏"查了一周（GC 无罪）。破案：heap profile
  的 time 命名空间+grep time.After（60 节）。修复：NewTimer+Reset
  复用/升 1.23；"热路径禁 time.After"进评审清单（112 节）。
- 方法论：时间线（变更/指标/告警对齐）→根因五问到机制层（不是
  "人失误"）→行动项必须落门禁（lint/测试/告警/预算），否则三个月后
  同款重演。三例共性：缺的不是数据，是"该看的指标"。

```go
// 三例的通用解: 机制级指标常驻监控, 不是等下次事故
// /gc/limiter/last-enabled | CPU throttle 率 | timer 存活数(pprof)
```

## 112. 代码评审清单与交付纪律

- 四线清单（并发/错误/资源/性能，每条 30 秒可答）：并发——每个 go
  答得出两问（55 节）、chan close 归属唯一（47 节）、WithCancel 必配
  defer、共享状态有 happens-before 边（27 节）；错误——%w 不丢链
  （63 节）、不吞不复读（67 节）、可重试判定长在类型上（68 节）；
  资源——Open 后下一行 defer Close、Body 排干再关（24/104 节）、
  rows.Close、ticker.Stop；性能——热路径 allocs/op 有数（40/82 节）、
  map 预分配、热循环无 fmt/装箱、性能声称附 benchstat（83 节）。
- 工具是地板：go vet（go test 自带）+golangci-lint 基线（staticcheck/
  errcheck/nilerr/copylocks/containedctx/gosec）；nolint 带理由带
  期限，总量 ratchet 只减不增（110 节）。
- 发布前 gate（缺一不合）：全量 -race 绿（29 节）、goleak 常驻绿
  （94 节）、govulncheck reachable 清零（101 节）、go mod tidy 无
  diff、构建可复现（102 节）、性能回归报告、体积/行数预算（108 节）。
- 评审文化：小 PR、注释讲为什么；每条红线对应一次真实事故——清单
  是团队的伤疤汇总；新红线只有复盘会能新增（111 节），没有事故背书
  的红线会被"下次特例"慢慢溶解。

```bash
# 交付 gate 的最小脚本形态
go vet ./... && go test -race ./...  # goleak 已在 TestMain 常驻(94)
govulncheck ./... && test -z "$(gofmt -l .)"
# benchstat old.txt new.txt 的输出附进 PR 描述(83 节)
```
