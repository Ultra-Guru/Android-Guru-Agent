<!-- kb: id=rust | role=builtin_coding_rust | title=Rust 专家知识库 | version=1 -->
# Rust 专家知识库

## 必看内容

- **取消即 Drop**：async 任务在任意 await 点都可能被 drop（timeout/select/运行时停机都会触发），
  你的析构函数就是唯一的"取消处理器"。`mpsc::recv` 取消安全、`send().await` 不安全——
  每个 select! 分支先过取消安全审计（见 50-53 节）。
- **阻塞调用会饿死整个 runtime**：Tokio 不会从被阻塞的 worker 偷任务，一个 10ms 的
  同步磁盘读就能拖垮同核所有任务。长 CPU 循环、阻塞系统调用、持锁 await 是三种
  标准形态；修复只有 `spawn_blocking`/`block_in_place`（见 54-57 节）。
- **Pin 的心智模型**：`Box<T>` 无条件 Unpin（移动的是指针不是 pointee）；
  `!Unpin` 只出现在"编译器生成的自引用状态机"里，安全 Rust 造不出自引用结构。
  手动投影一律用 pin-project，别手写 unsafe（见 47-49 节）。
- **Arc 的原子不是免费的**：`clone` 走 Relaxed 自增，drop 走 Release 递减 + 归零
  Acquire fence；热点 Arc 的跨核缓存行乒乓能吃掉两位数百分比吞吐。读多写少
  考虑 ArcSwap，图结构考虑 arena（见 27-29 节）。
- **unsafe 与并发的 CI 底线是 Miri + loom**：Miri 抓别名/越界/UAF（只查跑过的
  路径），loom 穷举小原语的线程交织。没有这两样，你的 unsafe 代码只是"还没出事"
  （见 36-37、90-93 节）。
- **thiserror 归库、anyhow 归二进制**：公共 API 出现 anyhow::Result = 把错误
  语义决定权扔给了下游；库错误 enum 必须 `#[non_exhaustive]` + 稳定错误码
  （见 79-82 节）。
- **Edition 2024 已上线（1.85，2025-02）**：unsafe extern 块、unsafe 属性、
  `static_mut_refs` 拒绝、RPIT 捕获全部在域生命周期——迁移是机械的
  （`cargo fix --edition`），语义变化必须人工过（见 85-88 节）。
- **feature 统一是暗税**：同一个 crate 只编译一份 = 所有启用者特征的并集，
  你没开启的 serde/async 特性会被别的依赖"替你"打开，进了二进制
  （见 103、107 节）。
- **panic 穿过 extern "C" 边界 = abort**（1.81 起强制）：给 C 的回调入口必须
  catch_unwind 包裹，否则 C 侧拿不到现场（见 97、102 节）。
- **边界检查**：迭代器路径 LLVM 免费消除；索引路径靠循环前 `assert!` 给提示；
  `get_unchecked` 是最后手段。热循环请用 godbolt 确认检查真的没了
  （见 69 节）。
- **借用检查器死角用"重组"而非 unsafe 硬闯**：if-let scrutinee 临时值、
  持借用调方法、生命周期传染——索引分割/两步走/收窄作用域三招见 1、10-12 节。
- **单态化是编译时间头号杀手**：每个泛型实例化一份机器码，公共 API 上的
  泛型由下游付费。泛型下推到叶子、dyn 上移到边界，cargo-bloat 说话
  （见 21、78 节）。

## 快速索引

| 想找什么 | 去哪一节 |
| --- | --- |
| 借用检查器报错但逻辑没错 | 1. 借用检查器死角：NLL 之后仍会拒绝的模式 |
| 生命周期省略何时失效 | 2. 生命周期省略规则的角落案例 |
| 型变/不变型报错看不懂 | 3. 型变深水：协变、逆变与不变的实际后果 |
| T: 'static 到底要求什么 | 4. 'static 的真实边界：不是"活得久" |
| not general enough 报错 | 5. HRTB（for<'a>）实战：闭包与 trait 的交互 |
| 闭包怎么变成 FnOnce | 6. 闭包捕获深水：move、推导与 Fn trait 族 |
| 字段析构顺序/双重 panic | 7. Drop 顺序、drop 标志与 panic 期间的析构 |
| 大结构体 move 开销 | 8. Move 的微观成本：隐式 memcpy 不是免费的 |
| 手写可变迭代器 | 9. IterMut 窗口技术：迭代器与借用失效 |
| 一块数据两段 &mut | 10. split_at_mut 与多段可变借用工程化 |
| v.push(v.len()) 编译不过 | 11. 方法链中的借用死锁形态 |
| 加字段后生命周期扩散 | 12. 生命周期传染与公共 API 设计 |
| 关联类型还是泛型参数 | 13. 关联类型 vs 泛型参数的决策模型 |
| lending iterator 流式借用迭代 | 14. GATs 实战：lending iterator 与生命周期传染 |
| GAT 报 missing required bound | 15. GATs 已知坑：where Self: 'a 与 dyn 不兼容 |
| async fn in trait 限制 | 16. RPITIT 与 async fn in trait 的深水限制 |
| 对象安全/dyn 报错 | 17. dyn 兼容性清单与对象安全成本模型 |
| vtable 开销多大 | 18. vtable 与胖指针：dyn 分发的真实开销 |
| dyn 转父 trait/Any downcast | 19. trait upcasting 与 downcasting 纪律 |
| impl 冲突 E0119 | 20. 孤儿规则（coherence）与 newtype 逃生舱 |
| 泛型让编译变慢 | 21. 泛型单态化成本：编译时间与代码膨胀预算 |
| const 泛型不能用 &str | 22. const 泛型深水：约束与绕过 |
| PhantomData 用哪种写法 | 23. PhantomData：型变与 drop check 控制杆 |
| Send/Sync 自动推导变化 | 24. marker/auto trait 的自动传播与负面效应 |
| specialization 不可用怎么特化 | 25. specialization 现状与类型驱动替代方案 |
| Borrow vs AsRef 选谁 | 26. 标准转换 trait 惯例：From/TryFrom/AsRef/Borrow |
| Arc::clone 到底多贵 | 27. Arc 内部机制：refcount 原子操作成本模型 |
| Rc 能不能进 async 任务 | 28. Rc 与 Arc 的选择及跨线程判定 |
| 对象图互相引用导致泄漏 | 29. Weak 与循环引用：泄漏的定量分析 |
| Cow 值不值得用 | 30. Cow<T> 写时复制的适用边界 |
| OnceLock/LazyLock 选型 | 31. Cell/RefCell/OnceCell/OnceLock 选择矩阵 |
| Deref 模拟继承行不行 | 32. Deref 多态反模式与显式转换纪律 |
| Option<&T> 为什么不占空间 | 33. ZST 与 niche 优化：内存里的免费午餐 |
| repr(packed) 取引用报错 | 34. #[repr(C)]/repr(align)/repr(packed) 布局陷阱 |
| unsafe 注释怎么写 | 35. unsafe 安全证明：SAFETY 注释与不变量 |
| Miri 报别名违规 | 36. Stacked Borrows 与 Tree Borrows 别名模型 |
| Miri 能测什么 | 37. Miri 纪律：能测什么、测不到什么 |
| interior mutability 怎么保证正确 | 38. UnsafeCell 与内部可变性的证明义务 |
| packed 字段怎么取地址 | 39. 裸指针实战：addr_of!、offset 与别名陷阱 |
| transmute 替代方案 | 40. transmute 合法性边界与替代方案 |
| 宏递归超过限制 | 41. 声明宏进阶：tt muncher 与递归限制 |
| 宏里找不到 crate 路径 | 42. 宏卫生与 $crate：跨 crate 引用的保命符 |
| proc-macro 拖慢编译 | 43. proc-macro 编译成本模型与依赖隔离 |
| 写宏一定要 syn 吗 | 44. syn/quote 取舍：解析开销与 token 直通 |
| derive 宏泛型 bound 处理 | 45. derive 宏设计：属性解析与错误定位 |
| async fn 编译成什么 | 46. Future 状态机解剖：desugar 的真面目 |
| Pin 到底为什么存在 | 47. Pin/Unpin 内部：固定地址的真正原因 |
| pin-project 怎么用 | 48. Pin 投影：structural 与 non-structural |
| !Unpin 手动 Pin 纪律 | 49. !Unpin 类型与 Pin::new_unchecked 纪律 |
| 取消时资源怎么清理 | 50. 取消即 Drop：async 世界的根本语义 |
| 哪些操作取消安全 | 51. 取消安全判定清单与常见违章 |
| select! 丢数据 | 52. tokio::select! 的坑：分支丢弃与公平性 |
| select 循环重复执行 | 53. select! 循环模式的取消安全审计 |
| worker 数量怎么调 | 54. Tokio runtime 拓扑：worker、窃取与预算 |
| block_on 嵌套 panic | 55. spawn 与 block_on 的线程语义陷阱 |
| 什么时候用 spawn_blocking | 56. block_in_place 与 spawn_blocking 的边界 |
| 任务延迟飙高/线程饿死 | 57. 运行时饿死：阻塞调用的三种形态与取证 |
| 后台任务悄悄消失 | 58. JoinSet/JoinHandle 与任务生命周期管理 |
| 优雅停机怎么做 | 59. 优雅停机：shutdown token 与收割模式 |
| 通道积压内存爆 | 60. mpsc 有界通道与背压设计 |
| watch/broadcast 丢消息 | 61. watch 与 broadcast 通道语义深水 |
| 两把 Mutex 选哪把 | 62. tokio::Mutex 与 std::sync::Mutex 选择铁律 |
| async trait 里用 dyn | 63. async trait 的 dyn 兼容：async_trait 与替代 |
| trait future 不满足 Send | 64. Send bound 与生命周期噩梦：trait 返回 future |
| Drop 里不能 await | 65. async Drop 现状与替代模式 |
| async 测试时间抖动 | 66. 测试 async：time::pause 与确定性时间 |
| actor 邮箱模式 | 67. actor 模式与 select_loop 惯例 |
| 性能瓶颈怎么找 | 68. 性能测量纪律：工具链与统计噪声 |
| 热循环消除边界检查 | 69. 边界检查消除：迭代器、assert 与 LLVM 可证明性 |
| 换 jemalloc 有用吗 | 70. 分配器选择：system/jemalloc/mimalloc |
| 内联/LTO/PGO 配置 | 71. 内联预算：#[inline] 效力、LTO 与 PGO |
| 解析器避免分配 | 72. zero-copy 设计：借用驱动的解析管线 |
| 小数组免堆分配 | 73. SmallVec 与 inline storage：削减分配 |
| 多线程计数器伪共享 | 74. false sharing 与缓存行对齐 |
| SIMD 自动向量化条件 | 75. SIMD 自动向量化：安全写法与置信度 |
| 字符串处理分配策略 | 76. &str 切片与 String 分配决策 |
| 迭代器链 vs for 循环 | 77. 惰性迭代器与短路求值的平衡 |
| 编译时间太长 | 78. 编译时间工程：单态化瘦身与剖析 |
| thiserror/anyhow 选型 | 79. thiserror/anyhow 边界：库与二进制的分工 |
| 库错误类型怎么设计 | 80. 错误类型设计：可匹配性与信息保真 |
| anyhow 丢 context | 81. context 丢失事故：anyhow downcast 的代价 |
| enum 加变体算 breaking 吗 | 82. 库错误 semver 兼容与 #[non_exhaustive] |
| panic 要不要 catch | 83. panic 策略：catch_unwind 与边界防御 |
| ? 的自定义转换 | 84. ? 运算符进阶：FromResidual 与自定义传播 |
| 升 edition 2024 步骤 | 85. Edition 2024 迁移与 compatibility lint 全解 |
| edition 2024 破坏性变化 | 86. Edition 2024 语义变化：unsafe extern 与 unsafe 属性 |
| impl Trait 捕获生命周期 | 87. RPIT 捕获规则变化与 use<> 精确捕获 |
| resolver v3/MSRV 解析 | 88. MSRV 感知解析与 resolver 版本演进 |
| 工具链版本怎么钉 | 89. 工具链升级纪律与版本钉死策略 |
| 并发代码穷举测试 | 90. loom 并发模型检验实战 |
| loom 测不动大场景 | 91. loom 与真实并发测试的分工 |
| property test 写法 | 92. proptest/quickcheck 模糊测试纪律 |
| Miri 进 CI 配置 | 93. Miri 在 CI 中的编排 |
| benchmark 结果抖动 | 94. criterion 基准与统计噪声控制 |
| 测试组织/集成测试编译慢 | 95. 测试金字塔与 #[cfg(test)] 的边界 |
| doctest 拖慢 CI | 96. 文档测试（doctest）可维护性陷阱 |
| panic 跨 FFI 行为 | 97. panic 跨 FFI 边界：abort 语义与 extern "C-unwind" |
| Rust struct 给 C 用对不上 | 98. #[repr(C)] 跨界陷阱：字段顺序、padding 与类型对不上 |
| 字符串传给 C | 99. CString/CStr 字符串跨界约定 |
| C 回调里 panic | 100. 回调跨 FFI：panic、reentrancy 与 Send 义务 |
| 绑定生成工具选择 | 101. 绑定生成决策：cbindgen、uniffi 与 bindgen |
| FFI 边界防御 | 102. FFI 安全边界：不可信输入与 catch_unwind 包装 |
| feature 被别的依赖打开 | 103. feature 统一（unification）的坑与对冲 |
| 依赖漏洞扫描 | 104. 依赖审计：cargo-deny、cargo-audit 与供应链 |
| build.rs 安全问题 | 105. build.rs 的安全风险与最小化 |
| Cargo.lock 提交策略 | 106. Cargo.lock 与 cargo update 纪律 |
| workspace 版本对齐 | 107. workspace 依赖管理与 workspace-hack |
| 反模式自查 | 108. 生产反模式速查清单 |
| async 生产事故排查 | 109. 事故复盘 I：async 饿死与取消丢失 |
| 内存泄漏排查 | 110. 事故复盘 II：泄漏、循环引用与 drop 顺序 |
| 错误被吞掉的事故 | 111. 事故复盘 III：错误链断裂与吞错 |
| 评审 checklist | 112. Rust 专家代码评审清单 |

## 1. 借用检查器死角：NLL 之后仍会拒绝的模式

- NLL（2018 起）按控制流判借用，但本质是**位置敏感**：它不跟踪"这个值之后
  再也没用过"，只知道"这一行的借用还在不在"。
- 经典死角一：`if let Some(i) = v.iter().position(p)` 的 scrutinee 临时值
  （迭代器）在 edition ≤2021 活到整个 if-let 语句结束，then 分支里的
  `v.remove(i)` 撞 E0502。死角二：条件借用跨分支存活；死角三：trait
  方法签名比你的逻辑更保守（`&self` 全量借用）。

```rust
// 报错：iter() 临时值活到 if-let 末尾，remove 要 &mut
if let Some(i) = v.iter().position(|x| x.bad()) { v.remove(i) }
// 修复：借用在 let 行末结束，i 只是 usize
let i = v.iter().position(|x| x.bad());
if let Some(i) = i { v.remove(i) }
```

- 陷阱：用 RefCell/unsafe 硬闯这些死角是错误答案——先试三招：
  **返回索引代替引用**、**先算后改两步走**、**把借用收进最小块**。
- 下一个借用检查器 Polonius 多年未落地，"编译器以后会懂"不是今天的
  工程决策依据。

## 2. 生命周期省略规则的角落案例

- 三条省略规则（每个输入 ref 一个新参数；&self 单独绑定；输出取输入），
  坑都在"规则失效、编译器要求显式"的边界上。
- 角落一：无 `&self` 但有多个输入引用 → 输出无法省略，必须写 `'a`/`'_`。
- 角落二：`Box<dyn Trait>` 默认 `+ 'static`——想把带借用的实现方装进 box
  必须显式 `+ 'a`（17 节）。角落三：impl 块里的省略绑定到 `Self` 参数。

```rust
fn cut(s: &str, at: &str) -> &str            // ERROR: 返回借用谁？
fn cut<'a>(s: &'a str, _at: &str) -> &'a str { s }  // 明示取 s
struct Holder<'a>(&'a str);
fn get(h: &Holder) -> &str                    // elision → Holder 的 'a
```

- `'_` 在输出位置是"这里有被省略的生命周期"的**显式信号**（serde 用
  `&'_ str` 区分 owns/borrows）——比裸省略更可读，公共 API 推荐标注。
- 陷阱：多输入一输出时编译器不猜语义（longest/shortest 都不给）；
  泛型 `F: Fn(&str) -> &str` 还会把闭包推向 HRTB（5 节）。
- async fn 的省略经 desugar 走 RPIT 捕获规则，edition 2024 又变了
  （87 节）——省略规则不是"永远稳定"的背景知识。

## 3. 型变深水：协变、逆变与不变的实际后果

- `&'a T` 对 'a 与 T 协变（可被当成活得更短/更窄）；`fn(T) -> U` 对 T
  逆变；`&mut T` 与 `Cell<T>` 对 T **不变**。不变不是刁难，是唯一防止
  "短命值伪装长命"的类型级护栏：

```rust
fn evil<'a>(slot: &'a mut &'static str, short: &'a str) {
    // 若 &mut T 协变，'static 可被放宽成 'a：
    *slot = short; // 于是 slot 存进了活得更短的东西 → 悬垂
}
// 实际：&mut &'static str 不变 → 编译直接失败，护栏生效
```

- 实战症状：`&'a mut T` 跨函数传递时爆发 "declared lifetime may not
  live long enough"——通常不是真悬垂，而是**不变性在逼你把生命周期对齐**。
- 修法：把 T 里的生命周期一起收紧成 `'a` 全对齐（`&'a mut T<'a>`），
  或改不可变借用/值拷贝绕开。
- 陷阱：想放松型变时用 `PhantomData<fn() -> T>`（协变且不参与 drop
  check）而不是 `PhantomData<T>`（23 节）——选错会让 dropck 拒绝合法
  代码或放过非法代码。

## 4. 'static 的真实边界：不是"活得久"

- `T: 'static` 的语义是"**T 不含任何短于 'static 的借用**"——与值实际
  活多久无关。`String`、`Vec<u8>`、`i32` 都满足（owned 即自由身）。
- 所以 `Box<dyn Error + 'static>` 能装任何 owned 错误；`thread::spawn`
  要求 'static 不是要你 leak，是要闭包**不欠任何线程栈上的债**。
- 真正的 `&'static` 来自：字面量、`const`/`static`、`Box::leak`、
  进程级 OnceLock。`Box::leak` 是**故意的永久泄漏**，只适合初始化一次、
  活到进程尾的全局缓存。

```rust
fn spawn_owned() { std::thread::spawn(|| { /* 拥有一切 */ }); }
fn spawn_borrowed(s: &str) {
    std::thread::scope(|sc| sc.spawn(|| println!("{s}"))); // 1.63+ 借用线程
}
```

- 陷阱：`T: 'static` 与 `&'static T` 混读是评审高频错误——前者是能力
  证明、后者是具体引用类型。
- `thread::scope` 的 panic 语义：等全部子线程 join 后再继续 unwind，
  别在 scope 里吞 panic（拿不到 join 结果就丢了）。

## 5. HRTB（for<'a>）实战：闭包与 trait 的交互

- `for<'a> Fn(&'a str) -> usize` = 对**任意** 'a 都可调用。写泛型接收器
  `F: Fn(&str) -> usize` 时，F 实际背负的就是 HRTB bound。
- 坑一：**late-bound vs early-bound**。`fn f<'a>(x: &'a str)` 的函数
  项天然满足 HRTB；但 'a 出现在 trait 的 Self/关联类型里（early-bound）
  就转不过去，报错相当费解。
- 坑二（最出名）：把闭包**先绑定到局部变量**再传入期望 HRTB 的位置：

```rust
let f = |x: &str| x.len();                 // 被推断成某个具体 'a
fn use_hrtb<F: for<'a> Fn(&'a str)>(_: F) {}
use_hrtb(f);                               // ERROR: not general enough
use_hrtb(|x: &str| x.len());               // OK：就地按 HRTB 生成
```

- 原因：闭包变量被单态化成"某个选定生命周期"的 impl，丢了全称量化；
  传字面量则按 bound 直接生成 HRTB 实现。
- 对策：闭包**就地处参**；或把闭包写成独立 `fn`（fn item 天然
  late-bound）；或显式给闭包参数标注并放在 trait bound 下。

## 6. 闭包捕获深水：move、推导与 Fn trait 族

- 2021 起闭包按**不相交字段**捕获（`|| drop(self.a)` 只借 `self.a`），
  "闭包借一个字段、外面改另一个字段"大量免报错——但只在闭包**就地写**
  时成立，跨函数边界传出去就没有字段级宽限。
- 推导：只读捕获 → Fn；读+写 → FnMut；按值消耗（非 Copy 或 move 消耗）
  → FnOnce。`move` 强制按值捕获，但 Copy 类型按值=复制，move 闭包仍
  可能是 Fn。

```rust
let mut n = 0;
let mut inc = || n += 1;         // FnMut：独占 &mut n
inc(); inc();
let consumed = move || drop(n);  // n 非 Copy：FnOnce
```

- 陷阱：FnMut 闭包**递归调用自身**编译不过（可变借用被重复借走）——
  把状态外提或改成显式函数。
- 陷阱：`Box<dyn FnOnce()>` 1.35 起可调用，但三族不可互换；返回闭包
  用 `impl Fn()` 即可，别急着造 trait object。
- 跨返回边界捕获引用的生命周期在 2024 版本需要 use<> 显式化（87 节）。

## 7. Drop 顺序、drop 标志与 panic 期间的析构

- 结构体字段按**声明顺序** drop，局部变量按**声明逆序**。struct 里
  `lock` 与 `data` 的相对位置决定"先放锁还是先析构数据"——放锁在前，
  别的线程可能摸到析构一半的数据。
- 条件性 move 的值带**隐藏 drop 标志**（enum/Option 内 moved-out 值
  也能安全析构的原因）；`mem::forget` 跳过析构但不违反内存安全
  （可能泄漏）。
- unwind 期间再 panic → **abort**（双重 panic）。Drop 实现里的 unwrap
  是生产事故经典来源：析构路径必须 infallible 或显式吞错+日志。

```rust
struct Session { data: Vec<u8>, lock: MutexGuard<'static, ()> }
// 声明序：先 drop data 再放锁。若锁保护的是 data 的读者，
// 顺序反了 = 读者摸到半析构状态。评审对每个 struct 问：drop 序对吗？
```

- 陷阱：`drop(x)` 只是立即析构的普通调用，不改变作用域语义；要手动
  控制用 `ManuallyDrop` 并自担 forget 义务（35 节 SAFETY 注释）。
- panic=abort 构建下 unwind 推理全部作废——catch_unwind 变 no-op
  （83 节），别让架构依赖 unwind 清理。

## 8. Move 的微观成本：隐式 memcpy 不是免费的

- Rust 的 move = `memcpy(size_of::<T>())`，没有用户定义移动构造——
  大 struct move 的成本就是字面整块拷贝。
- 热路径规则：大于两个机器字的热 struct 传 `&T`/`&mut T`；只有小类型
  （≤16B 数量级）值得按值传。`[u8; 4096]` 在循环里 move 是 4KB memcpy，
  还会打断 LLVM 循环识别。
- async 专属：**状态机尺寸 = 跨 await 存活的所有局部之和**。async fn
  里持有大 buffer 且跨 await，等于每个任务背 4KB——spawn 几万任务时
  内存直接爆。

```rust
fn slow(buf: [u8; 4096]) -> [u8; 4096] { buf }   // 8KB memcpy/次
fn fast(buf: &mut [u8; 4096]) { buf.fill(0) }    // 零拷贝
// async 里跨 await 的大数据：let big = Box::new([0u8; 4096]);
```

- 陷阱：`#[inline]` 小函数内的 move 会被优化掉一部分，但**跨非内联
  函数边界**的 move 是真拷贝——用 cargo-show-asm 验证。
- Copy 类型赋值同价：`let a = b;`（[u8;4096]）没有 move 语义差别，
  成本一样真实；批量数据传切片引用。

## 9. IterMut 窗口技术：迭代器与借用失效

- 手写 `&mut` 迭代器的安全核心是**窗口收缩**：迭代器持有 `&mut [T]`，
  每次 `next` 用 `split_first_mut` 把头借走、剩余继续持有——借用检查器
  看到的是同一片数据在时间上被合法切开。适用面远超迭代器：任何"边
  迭代边交出可变借用"的 API（in-place 变换、流式消费）都适用。

```rust
struct Win<'a, T> { rest: &'a mut [T] }
impl<'a, T> Iterator for Win<'a, T> {
    type Item = &'a mut T;
    fn next(&mut self) -> Option<Self::Item> {
        let (head, tail) = self.rest.split_first_mut()?;
        self.rest = tail; Some(head)  // 尾部续持有，头部独立存活
    }
}
```

- 为什么不需要 unsafe：`&mut self` 上的 reborrow 按需切分，编译器
  全程在场——"用类型系统表达时间切分"的教科书。
- 替代品：按条件移除用 `Vec::retain_mut`（1.61+，零分配）。
- 陷阱：链表等非连续结构没有 split 对应物——安全 Rust 写不了链表的
  `IterMut`（too-many-lists 结论），改 index/slotmap 或 unsafe+Miri。

## 10. split_at_mut 与多段可变借用工程化

- `slice::split_at_mut(mid)` 是"一个数据、多段 &mut"的标准合法入口：
  std 内部用裸指针 + UnsafeCell 证明两半不重叠，你拿到的两段各自独占。
- 工程化场景：原地归并/分区（sort partition）、双缓冲交换、"头部当
  输出、尾部当输入"的流式变换。

```rust
fn partition<'a>(buf: &'a mut [u8], pred: impl Fn(u8) -> bool)
    -> (&'a mut [u8], &'a mut [u8]) {
    // 一趟分区：lo 专收真值段、hi 专收假值段，两个 &mut 同帧合法
    todo!() // let (lo, hi) = buf.split_at_mut(pivot); ..
}
// 双迭代器同步推进：a.iter_mut().zip(b.iter_mut())
```

- 常见变体：`split_first_mut`/`split_last_mut`（头尾拆）、`chunks_mut`
  （n 段）、`iter_mut().zip()`（同步推进）。
- 陷阱：两半生命周期来自**同一个** 'a——想存进同一个 struct 必须共享
  'a，又撞回 12 节的传染问题。
- 超过 2~3 段的可变拆分难维护：改用"返回索引区间"的 API，或 unsafe +
  结构化证明（35 节）。

## 11. 方法链中的借用死锁形态

- 家族一：`v.push(v.len())` —— push 要 `&mut v`、len 要 `&v`，同一
  表达式内不可能同时成立。修复：`let n = v.len(); v.push(n);`
- 家族二：`map.insert(k, map.get(&k).cloned())` 同理；本质是**方法接收者
  借用早于参数求值结束**。
- 家族三（最隐蔽）：match/while let 的 scrutinee 持有借用，arm 里调
  `&mut` 方法。解法永远是"把需要的信息**降级成值**（索引、Option），
  让借用在语句末自然结束"。

```rust
// scrutinee 持 position 的借用，arm 里 remove 违章：
match v.iter().position(|x| x.bad()) { Some(i) => v.remove(i), _ => {} }
let found = v.iter().position(|x| x.bad());   // 正确：两步走
if let Some(i) = found { v.remove(i) }
```

- 注意（联系 1 节）：`if let Some(i) = v.iter().position(..)` 的直接
  写法在 edition ≤2021 仍然编译失败——if-let scrutinee 临时值活到语句
  末；edition 2024 收紧了该作用域，跨版本迁移要重测（86 节）。
- 评审直觉：表达式里同时出现"接收者 &mut 的方法"与"读同一接收者的
  参数" → 先拆 let，别上 RefCell。

## 12. 生命周期传染与公共 API 设计

- 传染定律：struct 里加**一个**带 'a 的字段，'a 就成为整个 struct 的
  参数，并顺着 impl、返回值、下游 struct 一路扩散——常见于"给现有
  struct 加个 `&'a Config` 缓存字段"的"小改动"。
- API 对策（按优先级）：①换成 owned（Config 而非 `&'a Config`，冷路径
  clone 一次）；②把引用换成**索引/id**（usize、EntityId，访问时重查表）；
  ③把借用字段拆到独立"视图 struct"，让主 struct 保持无生命周期。

```rust
// 传染源：一个缓存字段让整个 App 带 'a
struct App<'a> { cfg: &'a Config, cache: HashMap<u32, &'a str> }
// 去传染：id + owned + 短命视图
struct App { cfg: Config, strings: Vec<String>, ids: HashSet<u32> }
struct AppView<'a> { app: &'a App }   // 借用集中在视图层
```

- 引用 vs id 的分水岭：数据**生命周期与宿主一致**用引用；数据要在宿主
  死后仍被别的结构引用/重组 → id（slotmap 为此而生）。
- 陷阱：struct 有两个生命周期参数后所有返回值都要显式对齐——公共
  API 的生命周期参数个数是评审指标，>2 通常意味着设计错了。
- 别用 `Box::leak` 制造 &'static 止血——那是把泄漏合法化（4 节）。

## 13. 关联类型 vs 泛型参数的决策模型

- 判据一句话：**每个 Self 只允许一个实现值**（Iterator::Item）用关联
  类型；**允许一族共存实现**（Add<Rhs> 的 Rhs、From<T> 的 T）用泛型参数。
- 关联类型的红利：下游不用 turbofish、方法调用处类型推断自动到位
  （`iter.next()` 推得出 Item）；泛型 trait 的方法每次都要标注。
- dyn 场景反转：关联类型在 `dyn Trait` 时必须**写死**在尖括号里
  （`Box<dyn Iterator<Item = u32>>`）；带泛型参数的方法直接不可 dyn
  （17 节）——两个限制方向相反。

```rust
trait Add<Rhs = Self> { type Output;
    fn add(self, rhs: Rhs) -> Self::Output; }
// 设计味觉测试：impl Add<u32> 与 impl Add<String> 同时合理 → 泛型
// Item 若能有两个值就不成其为 Iterator → 关联类型
```

- 陷阱：图省事把关联类型改成泛型参数会**碎掉下游所有推断**；反过来把
  泛型改关联类型会让"一族 impl"非法（semver breaking）。
- trait 同时有泛型参数和关联类型 = 职责混叠信号，考虑拆分。

## 14. GATs 实战：lending iterator 与生命周期传染

- GAT（1.65 stable）让关联类型带生命周期参数，解锁"每次 next 借出一块
  只在本次迭代有效的数据"——lending iterator：

```rust
trait LendingIterator {
    type Item<'a> where Self: 'a;  // 'a 是每次调用的新鲜生命周期
    fn next(&mut self) -> Option<Self::Item<'_>>;
}
// 用途：流式借出内部缓冲（每次覆盖）、零拷贝分帧、arena 迭代
```

- 标准 `Iterator` 做不到：`Item` 无生命周期参数，item 生命周期与迭代器
  解耦——`&mut self` 的借出窗口无法表达。
- 传染警报：`where Self: 'a` 会传染到**每一个**用到 `Item<'a>` 的泛型
  函数，下游全链路被迫加 bound——这是 GAT API 的税，库文档必须明示。
- 实战替代：很多场景用 `Iterator<Item = &[u8]>`（数据被外部拥有）或
  `Item = Vec<u8>` + 复用缓冲即可绕开；真正需要 lending 的标志是
  "想覆盖写同一块缓冲还要按迭代器交给别人"。
- 陷阱：GAT trait 不可 dyn（15 节）——需要 dyn 的 API 一开始就别用。

## 15. GATs 已知坑：where Self: 'a 与 dyn 不兼容

- 最著名报错：`missing required bound on Item`——编译器直接教你补
  `where Self: 'a`。这不是噪音：Self 里的引用必须活得比 'a 久，否则
  `Item<'a>` 可能借出悬垂。
- **GAT trait 不可 dyn**：vtable 无法表达"每个 'a 一个方法版本"，
  `dyn LendingIterator` 直接不可编译。要 dyn 就退回
  `Box<dyn Fn(&mut [u8]) -> ..>` 或 owned-item 迭代器。
- 变体受限：GAT 上的型变比普通泛型保守，某些协变模式在 GAT 上编译
  不过；跨 impl 的生命周期重写更容易撞 E0597/E0623。

```rust
trait Store { type Key<'a> where Self: 'a; }
impl Store for StringMap { type Key<'a> = &'a str where Self: 'a; }
// dyn Store 不可用；带 Key<'a> 的泛型函数每个都要转发 where Self: 'a
```

- 决策：公共 trait 除非必须别上 GAT——先问能不能用 owned item +
  外部缓冲表达；GAT 是最后手段，不是时髦装饰。
- 与 async 的历史：AFIT 稳定前的过渡方案正是 GAT+RPITIT 组合；1.75
  后官方 desugar 走 RPITIT，bound 传染的教训原样保留（16、64 节）。

## 16. RPITIT 与 async fn in trait 的深水限制

- 1.75 起 trait 方法可写 `async fn`/`-> impl Trait`（RPITIT）。desugar
  后返回**匿名类型**，捕获 `&self` 等参数——三条硬限制：
- 限制一：**不可 dyn**。匿名类型进不了 vtable；要 dyn 就回
  `#[async_trait]`（63 节）或手写 `-> Pin<Box<dyn Future + '_>>`。
- 限制二：**调用方加不了 Send**。spawn 需要返回 future 是 Send，但
  调用方无法给方法返回值追加 bound——必须在 **trait 定义处**写
  `Self: Sync` 或用 `#[trait_variant::make]` 生成 Send 版 trait；这是
  API 设计期决策，不是调用点决策。

```rust
trait Fetch { async fn fetch(&self, url: &str) -> Bytes; }
// 上述 trait：spawn(fetcher.fetch(..)) 无法证明 Send → 编译失败
// 修复：trait Fetch: Sync 加 bound，或 trait_variant 生成 Send 变体
```

- 限制三：edition 2024 起返回值捕获**所有在域生命周期**（87 节），
  2021 则不捕获泛型参数——跨版本行为不同，测试要双版本跑。
- 陷阱：trait 里 async fn 的默认实现被每个实现方独立编译——大默认
  实现 = 编译时间放大器（21 节）。

## 17. dyn 兼容性清单与对象安全成本模型

- 对象安全清单：方法不得带泛型参数（生命周期除外）；不得按值收 Self
  （除非 `where Self: Sized` 豁免出 vtable）；不得有关联常量；关联
  类型必须在使用处指定。async fn/RPITIT/GAT 都会让 trait 非 dyn-safe
  （15、16 节）。
- 设计模式：**拆分 trait**——dyn-safe 核心留在 `dyn Core`，泛型/Sized
  方法挪进扩展 trait：

```rust
trait Core { fn id(&self) -> u32; }            // dyn 可用
trait Ext: Core { fn map<T>(&self, f: impl Fn(u32) -> T) -> T; }
fn use_dyn(c: &dyn Core) { /* 多态核心；扩展走泛型入口 */ }
```

- 成本模型：每次调用一次间接跳转 + 无法内联；冷代码无所谓，热循环
  里是两位数百分比（18 节量化）。**边界用 dyn、叶子用泛型**。
- 陷阱：`Box<dyn Trait>` 默认 `+ 'static`，装借用的实现方要显式
  `+ 'a`（2 节），且 trait 方法签名里 'a 只能出现在 &self 之外。
- 1.71 起 "object safe" 改称 "dyn-compatible"——两套术语在文档里并存，
  报错信息用新词。

## 18. vtable 与胖指针：dyn 分发的真实开销

- `dyn Trait` 值是**胖指针**：`(data_ptr, vtable_ptr)`；vtable 存
  [drop_in_place, size, align, 方法指针…]。引用、Box、Pin 包着的
  dyn 都是两个机器字。
- 单次调用成本 = 间接跳转（预测良好时几个周期）+ **失去的内联**。
  真正的代价不是跳转，而是内联消失后调用边界处的优化机会全部蒸发
  （常量传播、逃逸分析、向量化中断）。
- 实测经验：热循环把 dyn 换成泛型/枚举常见 5-30% 差距，取决于内联
  能否发生；冷路径（一次请求一次）几乎测不出。

```rust
// 反模式：热循环里的 dyn
for item in items { handler.handle(item) }   // handler: &dyn H
// 修复（closed set）：枚举分发，调用点可内联、匹配可穷尽
enum H { Fast(FastH), Slow(SlowH) }
```

- closed set 优先 enum：零分配、可内联、穷尽检查；open set（插件）才
  用 dyn。
- 陷阱：`Box<dyn Fn>` 与 dyn 方法同价，但闭包捕获让堆块变大——热路径
  用泛型 `F: Fn` 接收（代价转嫁 21 节）。检查：cargo-asm 看内联、
  criterion A/B。

## 19. trait upcasting 与 downcasting 纪律

- upcasting（`dyn Sub → dyn Super`）1.86 稳定：开箱即用，不再需要
  `as_any` 旁路——旧代码里常见的 `fn as_any(&self) -> &dyn Any` 模式
  可以逐步退役。
- downcasting 依赖 `Any`：要求 `'static`（类型信息按具体类型存）。
  `downcast_ref::<T>()` 拿回 `Option<&T>`——**只能在数据源头层做**，
  链路中段做 downcast = 把静态类型系统降级成运行时试错。
- 错误处理是合法主场：`anyhow::Error::downcast_ref::<E>()`、在
  `err.source()` 链上找特定错误——边界层翻译错误时允许（81 节）。

```rust
let obj: Box<dyn Sub> = ..;
let sup: &dyn Super = &*obj;           // 1.86+ 原生 upcast
if let Some(e) = maybe.downcast_ref::<DbError>() { e.retryable() }
```

- 陷阱一：upcast 后 vtable 换了，`Any::type_id` 仍指向**原具体类型**，
  拿父 trait 的 type_id 做分发会翻车。
- 陷阱二：`Box<dyn Any>::downcast` 与 `&dyn Any::downcast_ref` 是两套
  API，签名写错常见。纪律：downcast 出现在业务代码 = 设计味道；
  出现在协议/序列化边界 = 正常。

## 20. 孤儿规则（coherence）与 newtype 逃生舱

- 孤儿规则：impl 合法当且仅当 trait 或**接收者类型**至少一个是本地的。
  `impl ForeignTrait for ForeignType` 永远非法——防止两个 crate 对同一
  (trait, type) 在下游相撞。
- 例外：`#[fundamental]` 类型（`&T`、`Box<T>`、`Arc<T>`…）可穿透：
  `impl MyTrait for Box<ForeignType>` 合法——但语义上你还是没动
  ForeignType 本身，别滥用。
- 逃生舱一：**newtype**（`struct MyF64(f64)`）——本地类型想 impl 什么
  都行；代价是转换样板（26 节 From 链）。
- 逃生舱二：**sealed trait**——用隐藏的 `Sealed` supertrait 声明"只许
  本 crate 实现"，后续加方法/默认实现都不算 breaking；库维护者标配。

```rust
pub trait Sealed {}
pub trait MyApi: Sealed { fn tick(&self); }  // 外部无法实现 Sealed
impl MyApi for u32 { fn tick(&self) {} }     // 只能由我们分发
```

- 陷阱：blanket impl（`impl<T: X> MyApi for T`）一旦发布就是永久
  承诺——上游未来给某个类型加 X 就会在下游 E0119 相撞。写 blanket
  前当成 semver 决策对待。

## 21. 泛型单态化成本：编译时间与代码膨胀预算

- 每个 `f::<T>()` 实例化一份独立机器码。公共 API 的泛型由**下游每种
  类型组合**付费——库作者看不见这笔账，用户全款支付。
- 三层症状：编译时间随实例化数线性涨；二进制膨胀（cargo-bloat 按
  符号排位，泛型函数常霸榜）；icache miss 上升——几份"同逻辑"代码在
  icache 里互相挤兑。
- 对策梯度：①叶子函数保持泛型（热路径内联红利），**主干/入口用 dyn**
  （17 节）；②trait bound 收窄自由度并文档化；③closed set 用 enum
  分发（18 节）。

```rust
// 反模式：主干泛型，实例化组合爆炸
pub fn pipeline<T: Codec, U: Sink>(t: T, u: U) { .. }
// 修复：热循环留在泛型叶子，主干收窄成 dyn 或固定组合
pub fn pipeline(c: &dyn Codec, s: &mut dyn Sink) { /* 调泛型叶子 */ }
```

- 库的纪律：derive 宏替下游生成泛型 impl 时同样在放大实例化（45 节）。
- 量化入口：`cargo build --timings`、cargo-bloat、`-Zprint-mono-items`
  （nightly 看实例化集合）。nightly 的 polymorphization 尚不可依赖。

## 22. const 泛型深水：约束与绕过

- 稳定面：只有**整型/bool/char** 可作 const 参数（`struct Arr<const N:
  usize>`）；`&str`/浮点/复合常量仍是 nightly。
- 表达力墙：参数只能是裸常量或常量表达式；`where N % 2 == 0` 这类
  谓词不支持——"按 N 分派实现"要靠关联常量开关 + 空 trait 技巧：

```rust
struct Matrix<const R: usize, const C: usize>([[f32; C]; R]);
trait Square<const N: usize> {}              // 用空 trait 编码 R == C
impl<const N: usize> Square<N> for Matrix<N, N> {}
impl<const R: usize, const C: usize> Matrix<R, C> {
    fn det(&self) -> f32 where Self: Square<R> { todo!() }
}
```

- 绕过清单：`std::array::from_fn`（闭包建 [T; N]，绕"无 Copy 建数组"）；
  `ArrayVec`/`heapless`（内联容量容器）；老式 generic_array 的类型级
  数字路线新代码别再走。
- 陷阱：const 参数在签名里 = 每个不同 N 是**不同类型**——`Arr<3>` 与
  `Arr<4>` 互不兼容，存容器/做 dyn 只能擦成 Vec。
- 陷阱：`[T; N]` 按值传递会全量复制+析构（8 节）；大数组按引用走。

## 23. PhantomData：型变与 drop check 控制杆

- PhantomData<T> 是"假装持有 T"的标记，影响三个系统：**型变**（继承
  T 的型变）、**drop check**（struct 析构可能 drop T → 借 T 的引用
  必须活得比 struct 久）、**auto trait**（T 的 Send/Sync 传播进来）。
- 选型速查（容器作者必背）：`PhantomData<T>` = 拥有（参与 dropck）；
  `&'a T` = 借用（协变、无 drop 义务）；`fn() -> T` = 只要型变；
  `*const T` = 强制 !Send + !Sync。

```rust
struct RawVec<T> { ptr: NonNull<T>, cap: usize, _own: PhantomData<T> }
// 没有 PhantomData<T>：dropck 认为 RawVec 不持有 T → 允许
// "Vec<&'short str> 活得更久" → 析构时悬垂。加上后编译器强制
// 'short outlives RawVec。
```

- 经典事故：自研容器忘了 PhantomData<T>，测试全绿（生命周期恰好都
  活着），上线后容器活得比借用长 → UB。Miri 能抓（37 节）。
- 反向坑：只要型变时写了 `PhantomData<T>`，dropck 拒绝合法用法，报错
  不说人话——看到 "may not live long enough" 先想 dropck。
- 陷阱：`PhantomData<fn(T)>` 是**逆变**——给内部可变类型用会打开 3 节
  的"短命伪装长命"之门；不确定就用 `fn() -> T`。

## 24. marker/auto trait 的自动传播与负面效应

- `Send`/`Sync` 是 **auto trait**：编译器按字段自动推导、无实现体——
  "加一个字段"可能**静默改变**整个类型的线程安全性，且不出现在 diff
  的语义审查里。
- 库的公共 struct：如果对外承诺了 Send/Sync，用**编译期断言**钉死，
  这是 semver 硬承诺：

```rust
const _: () = {
    const fn assert_send<T: Send>() {}
    const fn assert_sync<T: Sync>() {}
    assert_send::<MyPublicType>();
    assert_sync::<MyPublicType>();
};
```

- `unsafe impl Send/Sync` 是**对编译器的承诺书**：证明所有字段（含
  auto trait 泄漏路径）跨线程安全——Rc 的 !Send 根因是非原子计数；
  !Sync 的唯一根因是 UnsafeCell（38 节）。
- 陷阱一：负 impl（`impl !Send`）未稳定——现在只能塞
  `PhantomData<*mut ()>` 显式禁用。
- 陷阱二：auto trait 穿过泛型传播：`struct Wrapper<T>(T)` 的 Send 跟
  着 T 走，文档承诺必须带条件（"当 T: Send 时"）；跨 FFI 的类型要
  显式声明线程义务（100 节）。

## 25. specialization 现状与类型驱动替代方案

- specialization 在 nightly 多年且有**已知健全性漏洞**（默认方法调用
  "将被特化覆盖"的项会读到错误答案），min_specialization 同样未稳定。
  生产代码把它当方案 = 把命运押给内部有分歧的实验特性。
- 替代一：**enum 分发**（closed set 首选）——零开销、可穷尽、无
  unsafe（18 节同款）。
- 替代二：**关联常量开关 + 默认方法**——把"特化点"表达为
  `const FAST: bool`，默认方法内部分支；两个 impl 各给常量，单态化后
  死分支被删，效果接近静态分派。

```rust
trait Encode { const DIRECT: bool;
    fn enc(&self, out: &mut Vec<u8>) {
        if Self::DIRECT { self.enc_direct(out) } else { self.enc_slow(out) }
    } }
// DIRECT=true 的类型走内联快路径——分支在编译期消除
```

- 替代三：**双 trait 显式拆分**——快路径与慢路径各一个 trait，把选择权
  交给调用方而不是让编译器猜。
- 替代四：类型驱动重构——多数"需要特化"的场景是 API 语义混叠（快慢
  路径输入不同却硬塞一个 trait），拆开输入类型就不再需要特化。
## 26. 标准转换 trait 惯例：From/TryFrom/AsRef/Borrow

- From/Into：无伤值转换。**只实现 From**（Into 自动有）；公共 API 收
  `impl Into<T>` 是宽松入口，但会传染泛型实例化（21 节）。
- TryFrom：可失败转换，错误类型独立可 match（80 节），别复用
  `Box<dyn Error>` 当 TryFromError。
- AsRef<T>：**零分配的引用视角转换**（Path/OsStr/[u8]）；参数收
  `impl AsRef<str>` 是"多种字符串都能喂"的惯例。
- Borrow<T> 比 AsRef 严格得多：**必须保证 Eq/hash/Ord 与原值一致**——
  这是 `HashMap<String,_>.get("key")` 能工作的根基。

```rust
struct CaseKey(String);
impl Borrow<str> for CaseKey { fn borrow(&self) -> &str { &self.0 } }
// 若返回大写化视图 → hash(str) != hash(CaseKey) → get 静默失配
impl PartialEq<str> for CaseKey { /* Eq 语义必须与 borrow 视图同步 */ }
```

- 陷阱：`Cow` 参数想吃 &str 和 String 用 `impl Into<Cow<'_, str>>`；
  Deref 不是转换 API（32 节）——给自定义类型 impl Borrow 违反一致性
  时 HashMap 查找静默失配。

## 27. Arc 内部机制：refcount 原子操作成本模型

- 布局：`ArcInner<T> { strong, weak: AtomicUsize, T }`——计数器与数据
  同分配；`Arc<T>` 本体是 2 字胖指针。`Arc::clone` = 复制指针 + Relaxed
  自增（clone 不建立 happens-before，无需同步）。
- drop 路径贵一截：Release 递减；归零时**再打 Acquire fence** 才析构
  T——这个 acquire 是"多线程写 T 后最后一个 drop 看得到全部写"的根基。
- 成本模型：无争用原子 op ≈ 一次 L3 访问；**争用时跨核缓存行乒乓**，
  多核同时 clone/drop 同一 Arc 互相挤兑——高扇出共享配置是重灾区。
  读多写少换 ArcSwap（读路径零原子写）。

```rust
fn handle(cfg: &Config) { .. }              // 首选：借用，零计数
fn entry(self: &Arc<Config>) { handle(&self) } // 必须共享时入口 clone 一次
let cfg = Arc::clone(&self.cfg);            // 惯用写法（非 .clone()）
```

- `Arc::unwrap_or_clone`（1.76）：独占时拿走值、共享时克隆——替代
  脆弱的 try_unwind 组合。`Arc::try_unwrap` 只看 strong，weak 不挡。
- 陷阱：`Arc<Mutex<T>>` 是共享可变状态的默认起点，但多数生产代码真正
  想要的是消息传递（60 节）——把 Arc<Mutex> 当需要辩解的代码。

## 28. Rc 与 Arc 的选择及跨线程判定

- Rc 的计数是**非原子**自增/递减——单线程比 Arc 快一档，代价是
  `!Send + !Sync`：编译器物理阻止跨线程。
- async 专属事故形态：current_thread runtime 里用 Rc 全绿；迁到多线程
  runtime（或有人 spawn 了这个任务）瞬间爆 `Rc<..> cannot be sent
  between threads safely`——spawn 要求 future: Send，捕获 Rc 一票否决。

```rust
let shared = Rc::new(RefCell::new(0));
tokio::spawn(async move { *shared.borrow_mut() += 1 }); // ERROR: !Send
// 单线程 runtime 的任务用 Rc 是合法优化：
tokio::task::spawn_local(async move { *shared.borrow_mut() += 1; });
// 需要 LocalSet；或者改 Arc 把线程亲和问题一次了断
```

- 判定流程：任务可能被 spawn 到 worker → Arc；确定锁死 current_thread
  + spawn_local → Rc 可用，但前提写进注释，否则半年后有人换 runtime 就炸。
- `thread_local!` + Rc 适合"每线程缓存"，比 Arc<Mutex<Map>> 快且无锁；
  跨线程聚合用 scoped thread（4 节）。
- 陷阱：`Rc::get_mut`/`Arc::get_mut` 要求 strong==weak==1（Weak 也占
  用）；高频 get_mut 失败就别用 Weak（29 节的逆权衡）。

## 29. Weak 与循环引用：泄漏的定量分析

- 强环（`Arc<A>` 持 `Arc<B>` 持回 `Arc<A>`）让双方计数永不归零：析构
  永不运行——**不是 bug 而是逻辑封闭**，编译器无能为力。
- 定量：每条环泄漏 = 环上全部节点 size + ArcInner 开销；长生命周期服务
  + 每请求建环（parent/child 互指）→ RSS 缓慢爬升的"GC 型 OOM"曲线。
- 修复：**back-reference 用 Weak**（child 持 `Weak<Parent>`，`upgrade()`
  拿 Option），把"拥有方向"与"引用方向"分层；或 arena 化（slotmap：
  id 代替指针，所有权单一）。

```rust
struct Child { parent: Weak<Parent> }     // 不参与强计数
let c = Child { parent: Arc::downgrade(&p) };
drop(p);
assert!(c.parent.upgrade().is_none());    // 回归测试：计数断言防回退
```

- 检测三件套：①调试端点定期打印 strong/weak count；②RSS 曲线报警；
  ③测试对关键图做 drop 计数断言——**泄漏的正确测试是计数断言**，
  不是"看起来没涨"。
- 注意：Weak 让 ArcInner 活到所有 Weak 消失（T 已析构、控制块还在）；
  海量长寿 Weak 同样是账单。新版 std `Weak::new` 已不分配。

## 30. Cow<T> 写时复制的适用边界

- `Cow<T>` = `Borrowed(&T) | Owned(T)`：读零成本、首次写 `to_mut()`
  克隆一次。适用判据：**读多写少且写只在异常分支**（规范化 URL/头部、
  路径归一化、默认值填充）。
- 坑一：调用方传 String 时**所有权被吸进 Owned**，后续还要用就得再
  clone——热路径变双倍克隆；传 `&str` + 需要 owned 时 `to_owned()`
  常更省。坑二：`to_mut` 之后**永远留在 Owned 态**（没有降回 API）。

```rust
fn normalize<'a>(base: Cow<'a, str>) -> Cow<'a, str> {
    if base.contains('%') {           // 判定后再付钱
        let mut s = base.into_owned();
        s.retain(|c| c != '%');
        Cow::Owned(s)
    } else { base }                   // 快路径零克隆
}
```

- 同族：`Arc::make_mut`（共享克隆、独占原地）——库作者复用该语义比
  自造 enum 好。陷阱：Cow 按值传递 = 最大变体宽，大 T 更贵；先
  benchmark 再上（68 节）。

## 31. Cell/RefCell/OnceCell/OnceLock 选择矩阵

- `Cell<T: Copy>`：按值进出、无借用检查、零运行时开销；`&Cell` 不可
  借出 `&T`——Copy 出去就完事，别名安全自动成立（38 节）。
- `RefCell`：运行时借用旗标，违章 = **panic**（borrow_mut 撞在读借上）；
  !Sync 单线程专用。事故形态：回调里再 borrow → 嵌套炸；策略点用
  `try_borrow_mut` 显式处理。
- `OnceCell`（单线程）/`OnceLock`（多线程，1.70）：一次性初始化、之后
  读 = 原子 load。**LazyLock（1.80）取代 lazy_static/once_cell**：std
  原生、无宏。全局配置/驱动句柄首选。

```rust
static CONFIG: LazyLock<Config> = LazyLock::new(load_config); // 1.80+
struct Cache { inner: OnceLock<HashMap<u32, Metric>> }
impl Cache { fn metrics(&self) -> &HashMap<u32, Metric> {
    self.inner.get_or_init(build) } }   // 并发下只有一个 builder 胜出
```

- 毒性对比：std::Mutex panic 后**中毒**（lock 返回 Err 逼你处理）；
  RefCell 不中毒、OnceLock 初始化 panic 可重试——选型时写明理由。
- 陷阱：RefCell guard 跨 await 让 future 变 !Send，且别的任务 borrow
  会 panic——跨 await 的可变共享直接 `Arc<Mutex>`（62 节）。

## 32. Deref 多态反模式与显式转换纪律

- Deref 的**合法户口**：智能指针家族（&、Box、Rc、Arc）、Vec→[T]、
  String→str、PathBuf→Path——语言承诺的强转通道。
- 反模式：自定义 Deref 给 struct 模拟"继承"。后果一：方法解析静默
  穿透到 Base——重名方法被遮蔽，重构 Base = 远程爆炸。后果二：只借出
  &T，没有安全的 DerefMut 多态可用。后果三：**泛型代码不吃 deref
  强转**（编译器不为 `T: Deref` 做类型级穿透）——你想模拟的多态在
  泛型/宏里全部失效。

```rust
struct Api { base: HttpClient }
impl Deref for Api { type Target = HttpClient; /* 反模式 */ }
a.get(url);     // 解析到 HttpClient::get —— 重名即灾难
// 正道：显式转发（pub fn get 调 self.base.get），调用方看清来源
```

- 显式转换纪律：语义转换写 `as_*`（免费视角）/`to_*`（昂贵拷贝）/
  `into_*`（消耗）——这三套动词在 std 有稳定语义，评审按动词审成本。
- 例外：真智能指针（自己管理内存的包装）impl Deref 是本分；例外清单
  之外的 Deref = 评审打回。

## 33. ZST 与 niche 优化：内存里的免费午餐

- ZST（size 0：`()`、`struct Marker;`、`PhantomData<T>`）：编译器当
  "什么都没发生"——`Vec<ZST>` 不分配（容量=usize::MAX）、按值传零拷贝、
  enum 里不占变体空间。
- ZST 工程用法：**typestate 标记**（`struct Connected;` 作状态泛型参数
  让非法状态不可表示）、容量型 token——运行时零成本。
- niche 优化：enum 用"不可能的位模式"编码判别——`Option<&T>`/
  `Option<Box<T>>` 与裸指针**同宽**；`Option<NonZeroU32>` 同宽。

```rust
use std::mem::size_of;
const _: () = assert!(size_of::<Option<&u8>>() == size_of::<&u8>());
const _: () = assert!(size_of::<Option<std::num::NonZeroU32>>() == 4);
// struct 尺寸自查用编译期 assert 进测试（24 节同款纪律）
```

- 实战红利："经常 Option 的字段"用 NonZero 包装，Option 化不再翻倍；
  `Option<Box<Node>>` 让链表节点零额外开销。
- 陷阱：只有 std 明确承诺的 niche 可依赖（引用、Box、NonZero、extern
  fn）；自定义 enum 的 niche 别在 ABI 上赌。ZST 的 Vec::push 永不分配
  但容量永远"满"——容量敏感代码得到反直觉结果。

## 34. #[repr(C)]/repr(align)/repr(packed) 布局陷阱

- 默认 `repr(Rust)`：编译器**可以重排字段**——任何依赖顺序的代码
  （unsafe 转换、序列化、网络协议）必须显式 `#[repr(C)]` 钉死。
- `repr(packed)`：去掉 padding，**对 packed 字段取引用 = UB**（未对齐
  &T）——必须 `addr_of!` + `read_unaligned`（39 节）。
- `repr(align(N))`：手工对齐（SIMD、缓存行隔离）；align 只能放大，
  缩小用 packed 或手排。`repr(transparent)`：单字段新类型 ABI 与字段
  完全相同——FFI 包装类型（`struct Meters(f64)`）必须加。

```rust
#[repr(packed)]
struct Pkt { ver: u8, seq: u64 }
let p = Pkt { ver: 1, seq: 9 };
// let s = &p.seq;   // UB：未对齐引用
let s = unsafe { std::ptr::addr_of!(p.seq).read_unaligned() };
#[repr(align(64))] struct Padded([u8; 64]); // 缓存行对齐（74 节）
```

- 陷阱：repr(C) enum 是 int 宽，Rust enum 是 tagged union——跨 FFI 传
  枚举要 C 风格定义 + 显式判别；repr(C) struct 里塞 Rust enum/Option
  是 ABI 定时炸弹（98 节）。packed 前确认 C 端也是 packed。

## 35. unsafe 安全证明：SAFETY 注释与不变量

- 纪律核心：**每个 unsafe 块必须有 SAFETY: 注释**，逐条列出"为什么
  这次安全"——引用具体不变量；clippy `undocumented_unsafe_blocks`
  可强制。
- 安全抽象分层：unsafe 只允许出现在**私有低层模块**；对外 safe API 必
  须封住所有违反不变量的入口。公共 unsafe fn 的文档 = 契约书：写明
  调用方义务（SAFETY 段落）。
- 不变量要**可检查**：写成 debug_assert + 测试，让 Miri/loom 覆盖
  （36-37 节）；写"这里不会有并发问题"而不指明机制的注释等于没写。

```rust
// SAFETY: `self.buf` 前 `len` 个元素由 `push` 保证已初始化，
// 且 `idx < len`（loom 测试见 tests/loom_push.rs）。
unsafe { self.buf.as_mut_ptr().add(self.len).write(item) }
```

- 评审三问：①这个 unsafe 块的不变量**谁维护**（哪个函数）？②公共
  API 能否破坏它？③Miri/测试有没有跑到这条路径？
- edition 2024 里 unsafe fn 内部的 unsafe 操作也要显式 unsafe 块
  （unsafe_op_in_unsafe_fn 加强）——提醒"进了 unsafe fn 不等于自动
  安全"（86 节）。

## 36. Stacked Borrows 与 Tree Borrows 别名模型

- 别名模型回答 std 文档不回答的问题：**裸指针怎么用才不是 UB**。
  Stacked Borrows（SB）：每处内存挂"借用标签栈"，引用享有"保护"
  地位——被保护标签下面派生的裸指针再解引用/写 = UB。
- Tree Borrows（TB，Miri 内置，`-Zmiri-tree-borrows` 切换）：标签栈
  改成**权限树**，对"裸指针改类型/改可变性"更宽容、对共享写更严格——
  一堆 SB 判 UB 的现实模式在 TB 下合法。官方趋势向 TB 迁移，写 unsafe
  以**两模型交集**为准最稳。

```rust
let mut x = 0u8;
let r = &mut x;
let raw = r as *mut u8;
let _ = *r;                 // 保护标签仍激活
unsafe { *raw = 1 }         // SB 违章（Miri 报）；TB 边角可能放行
// 稳妥写法：让 &mut 先退役（不再用 r）再动 raw
```

- 两条模型无关的铁律：`&mut` 活着期间裸指针别写；从 &mut 派生裸指针
  后先让引用退场再使用。"Miri 没报"≠安全（没跑到，37 节）；"报了"
  大概率是真 UB——别用 flag 关掉了事。

## 37. Miri 纪律：能测什么、测不到什么

- Miri 是解释执行器：不产生机器码、不优化，逐指令模拟 C11 内存模型 →
  能抓：越界、UAF、未初始化读取、别名违规（36 节）、**数据竞争**（自带
  全交织检测）。代价：慢 20-1000 倍。
- 测不到：真实优化器行为、内联汇编、真实 syscall（仅模拟常用 libc）、
  真多核时序、perf 数据。
- CI 编排：nightly 独立 job 只跑 unsafe 触碰面（unsafe 集中在一个
  crate、"Miri 可测"成设计约束）；`#[cfg(miri)]` 跳过不兼容用例；
  `-Zmiri-many-seeds=64` 加大交织覆盖。

```rust
#[cfg(miri)]             // Miri 下跳过真文件 IO
mod real_io { pub fn open() -> File { unimplemented!() } }
#[cfg(not(miri))]
mod real_io { pub fn open() -> File { std::fs::open("x").unwrap() } }
```

- 测试纪律：改 unsafe 就本地跑 Miri，CI 每 PR 全量；新 UB 报错出现时
  **先修测试再修代码**，防"把报错压下去"的假修复。
- 局限：Miri 只验证**执行过的路径**——与 loom（穷举交织）、proptest
  （造路径）成套使用（90-92 节）。

## 38. UnsafeCell 与内部可变性的证明义务

- `UnsafeCell<T>` 是**全语言唯一**"通过 &T 改数据"的合法通道（Cell/
  RefCell/Mutex 之祖）：编译器承诺其内部改变**不算**违反 noalias。
- 证明义务：①任何时刻外层 `&T` 与活跃 `&mut T` 不可重叠——Cell 靠
  "只 Copy 出去"、RefCell 靠旗标、Mutex 靠锁排他履行；②跨线程要证明
  两个写者永不同步并发（Sync 假设 unsafe impl + 证明）。

```rust
struct Flag { inner: UnsafeCell<bool> }
impl Flag {
    fn set(&self) { unsafe { *self.inner.get() = true } }
    // 义务：set 只在"读者知道可能被并发改"的协议下暴露；
    // 对外提供 fn get(&self) -> &bool = UB：借出未同步可变数据的引用
}
// 与 Cell<bool> 等价 → 能 std 就不 unsafe（35 节）
```

- 布局保证：`UnsafeCell<T>` 与 T 布局/ABI 相同（文档承诺），`Cell<T>`
  同样——FFI 场景可以穿透，但线程义务要显式（100 节）。
- `&self` 的语义合同：safe 方法出现 `&self` 即承诺"除 UnsafeCell 外
  不改"——用 interior mutability 翻转 Eq 语义是逻辑 UB（26 节）。

## 39. 裸指针实战：addr_of!、offset 与别名陷阱

- `addr_of!`/`addr_of_mut!`：对**未对齐/packed** 字段取地址必须用它
  （`&s.field` 立即 UB，34 节）；可能未初始化的位置同样用它。
- `ptr::offset`（`p + n`）：越界（超出 one-past-the-end）即 UB，**不
  解引用也违规**；`wrapping_offset` 不 UB 但解引用前必须拉回范围。
- strict provenance：`addr()`/`with_addr` 把指针来源与整数解耦——
  `usize as *mut T` 老写法在 Miri 新模型下越来越多 UB 判例。

```rust
let p = [0u32; 4].as_mut_ptr();
unsafe {
    let last = p.add(3);    // OK
    let _oob = p.add(4);    // one-past-the-end：不可解引用
    let _ = p.add(5);       // UB：形成即违规（Miri 必报）
    last.write(7);
}
```

- 别名纪律：`write` **不跑旧值的 Drop**——覆盖含 Drop 位置先
  `drop_in_place`。陷阱：不同分配的指针做大小比较是灰色地带——
  "同一分配内"用 offset 比较或 provenance API，别拿裸 usize 比。

## 40. transmute 合法性边界与替代方案

- `transmute` 四大前提全满足才可用：大小相同；**位模式在目标类型合法**
  （0u8→bool 构造即 UB，不等你使用）；别名/所有权不被破坏（transmute
  出的值按**目标类型** drop）；生命周期由你手工保证。
- 经典 UB：`&Vec<T>` transmute 成 `&[T]`（Vec 3 个机器字、布局无 ABI
  承诺）；`Vec<T>` → `Vec<U>`（drop glue 错挂）。合法区：`&[u8]` →
  `&[u8; 4]`（配 try_into 更好）、无 Drop 的 POD 互转。

```rust
// 正确姿势链：
let fixed: &[u8; 4] = buf[..4].try_into().unwrap();  // 运行时检查
let n = u32::from_ne_bytes(*fixed);                  // 字节序显式
let pixels: Vec<u32> = bytemuck::pod_collect_to_vec(&bytes); // Pod 约束
```

- 替代清单：`TryInto`（slice→数组）、`bytemuck`/`zerocopy`（Pod/
  Zeroable trait 编译期把关 + cast/cast_slice）、`from_ne_bytes`/
  `from_le_bytes`（显式字节序）、`serde` + zerocopy 系（初始化安全）。
- 库作者：公开 API 出现 transmute = 评审红灯；真需要就在内部封 named
  fn + SAFETY 注释（35 节）。泛型 transmute<T, U> 大小相同也可能合法
  性不同——宏特化到具体类型再 transmute。

## 41. 声明宏进阶：tt muncher 与递归限制

- tt muncher：宏"吃掉"一个 token、吐出剩余、递归处理——逗号列表校验、
  类型级 DSL 的标准武器。**递归上限默认 128 层**（`#![recursion_limit]
  = "256"]` 可调）；吃长列表用**增量式**（每层吃一半，log2 深度）而非
  逐个吃（线性深度，列表长了就撞上限）。
- 内部规则分派（`($($t:tt)*) => { rules!($($t)*) }`）是控制匹配优先级、
  避免重复的正统手法：public 入口和内部 @stage 规则分开。

```rust
// 线性 muncher（慢：递归深度 = 参数个数）
macro_rules! count { ([]) => { 0 };
    ([$a:tt $($r:tt)*]) => { 1 + count!([$($r)*]) } }
// 32 个参数以内直接 $($arg:tt),* 展开 + const 泛型/const fn 计数即可
```

- 调试：`cargo expand`（需 nightly）看展开产物；宏错误报在**调用点**
  而非定义点——写宏时用 `compile_error!` 把消息生成到出错位置。
- 陷阱：`$expr` 匹配的 follow set 限制（后面不能紧跟某些 token）；
  宏体内一切跨 crate 路径用 `$crate::`（42 节）；宏无法对 token 做判断
  ——需要判断就分派到不同规则。

## 42. 宏卫生与 $crate：跨 crate 引用的保命符

- 声明宏卫生：宏体内局部变量与调用方**不同命名空间**（不意外捕获）；
  调用方也看不到宏内造的名字——"从宏里导出变量名"只能靠宏参数传 ident。
- `$crate` 是宏体内引用**定义 crate** 的唯一正确姿势——不用它，下游
  re-export 你的宏时路径解析就漂移。库作者必背第一条宏规则。
- `#[macro_export]` 把宏放到 **crate 根**（无视定义模块）；模块化导出
  用 `pub use my_crate::my_macro;`（宏可走 use 路径调用）。

```rust
#[macro_export]
macro_rules! metric {
    ($name:expr) => {
        // 错误：crate::REG —— 下游调用时 crate 指向下游自己
        $crate::registry::record($name);   // 正确：恒指本宏所属 crate
    };
}
pub use metric;   // 允许 this::module::metric!(..) 的路径式调用
```

- 陷阱：展开里用 `::std::...` 防下游 `mod std` 遮蔽；`macro_rules!`
  模块内定义即文本作用域，`#[macro_use]` 是老式喷洒——新代码用 use。

## 43. proc-macro 编译成本模型与依赖隔离

- proc-macro 是"编译期跑的普通程序"，固定成本大头不是你的代码，而是
  **syn/quote/proc-macro2 三件套的编译**——一次付出、全 workspace
  共享；之后每多一个 derive 的**增量**成本很小。"能用声明宏就别上
  proc-macro"的真正收益是把这坨固定成本从依赖图里抠掉。
- 隔离纪律：proc-macro crate 类型 `proc-macro`，但 syn 版本要全
  workspace 对齐（syn 1/2 分裂 = 编译两份）；关默认 feature、按需开：

```toml
[dependencies]
syn = { version = "2", default-features = false, features = ["parsing"] }
quote = "1"
proc-macro2 = "1"   # 不开默认 proc-macro feature 可脱离宏环境做单测
```

- 测试技巧：proc-macro2 让解析/生成逻辑能在**普通测试里跑**
  （stringify → parse → 断言 token 串），不必每次端到端编译。
- 陷阱：proc-macro 输出丢 Span → 报错全指到宏调用点，用户骂的就是你
  （44 节 span 纪律）。
- 陷阱：proc-macro crate 不能有普通运行时代码；要共享类型拆三个 crate
  （runtime / macro / re-export 入口）。

## 44. syn/quote 取舍：解析开销与 token 直通

- 判据：要**理解**语法结构（struct 签名、属性、where 子句）→ syn；
  只是**拼接/改写** token → proc-macro2 + quote 就够：编译更快。
- quote! 插值要求 ToTokens：`#var` 是**写出去**不是求值——想放运行时
  字符串要 `let lit = Literal::string(&s);` 再 `#lit`；直接 `#s` 生成
  ident。想输出字面 `#`（转发属性）用 `##[inline]` 转义。

```rust
#[proc_macro_attribute]
pub fn boxed_future(_a: TokenStream, item: TokenStream) -> TokenStream {
    let out = quote::quote!( #item );   // 直通改写：无 syn 依赖
    out.into()
}
// 报错定位：syn::Error::new_spanned(&tokens, "msg").to_compile_error()
```

- Span 纪律：所有错误用 syn::Error/compile_error! + 精确 span，让 rustc
  把红线画在**出错 token** 上；`Span::call_site()` 是兜底（指到宏调用
  处），滥用它 = 用户排障体验灾难。
- 陷阱：proc_macro::TokenStream 不能直接离开宏调用线程做单测——统一
  `.into()` 成 proc_macro2::TokenStream 再处理。

## 45. derive 宏设计：属性解析与错误定位

- derive 三板斧：解析 ItemStruct/Enum → 生成 impl → **泛型正确传播**。
  泛型结构 derive 时必须原样带上 generics + where 子句，只在确有必要时
  追加 bound——无脑加 `T: MyTrait` 会替下游收紧泛型（"helpful bounds"
  反模式，serde 之伤）。

```rust
// derive(MyTrait) 生成：原样转发 generics
let (impl_g, ty_g, where_c) = input.generics.split_for_impl();
quote! {
    impl #impl_g MyTrait for #name #ty_g #where_c { /* .. */ }
}
// 需要额外 bound 时的体面做法：按"每个字段用到的参数"加 bound
```

- 属性解析：辅助属性用 `parse_nested_meta` 解析（别再手写 Lit 解析）；未识别的属性
  **原样保留**，别吞别人的宏属性。
- 错误定位：辅助属性写错 → `syn::Error::new_spanned(meta, "..")` 指到那一行；proc-macro-error 已停维护，改用 proc-macro-error2。
- 测试：trybuild（UI 测试：编译错误快照）+ macrotest（展开快照）——
  derive 的错误信息也是 API，做回归。

## 46. Future 状态机解剖：desugar 的真面目

- `async fn` desugar 成**手工状态机**：enum 每 await 点一个变体，保存
  跨 await 存活的局部 + waker 槽；`poll` = match 状态 → 跑到下一个
  await → Ready 推进 / Pending 存 waker 返回。
- 推论：①**惰性**——不 poll 就不执行，"创建后忘了 poll" = 静默没跑
  （spawn/await 至少一个）；②future 尺寸 = 最大变体（8 节装箱由来）。
- poll 合同（手写 Future 必背）：**先注册 waker、再检查条件**——反过来
  必丢唤醒：检查完到注册之间状态变化，没人再叫醒你。

```rust
impl Future for MyFut {
    type Output = u32;
    fn poll(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<u32> {
        self.slot.register(cx.waker());       // 1) 先登记 waker
        match self.shared.take() { Some(v) => Poll::Ready(v), // 2) 再查
            None => Poll::Pending }
    }
}
```

- 陷阱：poll 里做重活（自旋等锁、长循环）会卡 worker——tokio 的 coop
  预算多次 poll 后强制让出（54 节）。返回 Pending 前没存 waker = 永久
  挂起；被唤醒无进展又返回 Pending = 空转死循环。

## 47. Pin/Unpin 内部：固定地址的真正原因

- 为什么需要 Pin：async 状态机里跨 await 持有的 `&local` 逻辑上指向
  **自己栈帧里的字段**（生成器自引用）——保证"被 poll 的值不再移动"
  后这些内部指针才不悬垂。`Pin<&mut T>` = "从此不许移动"的类型级记录。
- `Unpin` 是**解除承诺**的标记：几乎所有类型都 Unpin。**Box<T> 无条件
  Unpin**：移动 Box 只是移指针、pointee 原地不动——理解 Pin 第一坎。
- `!Unpin` 只出现在：编译器生成的自引用状态机（async 块）、显式
  `PhantomPinned`。**安全 Rust 造不出自引用结构**——Pin 不是为了让你
  手写自引用，是给编译器的生成物上保险。

```rust
let fut = async { let x = [0u8; 16]; foo(&x).await; };  // !Unpin
let mut pinned = std::pin::pin!(fut);   // 栈固定（1.68+ 安全宏）
pinned.as_mut().poll(&mut cx);
let boxed: Pin<Box<dyn Future>> = Box::pin(async { .. }); // 堆固定
```

- 陷阱：`Pin::new(&mut T)` 要求 `T: Unpin`——类型系统拦住"随便 Pin
  一个 !Unpin"；对 !Unpin 只能 Box::pin / pin! / Pin::new_unchecked
  （49 节）。
- 陷阱：`Pin<Box<!Unpin>>` 没有 DerefMut——拿 &mut 只能走投影（48 节）。

## 48. Pin 投影：structural 与 non-structural

- 被固定 struct 想把 `Pin<&mut Self>` 变成字段访问，先回答：**字段是否
  也遵守"不移动"承诺**？structural：承诺一致 → 可投影 `Pin<&mut
  Field>`，义务：①永不按值移出（mem::replace/swap 违禁）；②永不
  对外提供 `&mut Field`。全是 unsafe 纪律，手写极易漏。
- non-structural：字段不参与固定 → 投影成普通 `&mut Field`（`get_mut`
  组合），安全但有边界纪律。

```rust
// 生产标准答案：pin-project 生成两套安全投影 + Drop 保护
#[pin_project]
struct MyFuture {
    #[pin] inner: InnerFut,   // 投影出 Pin<&mut InnerFut>
    buf: Vec<u8>,             // 投影出 &mut Vec<u8>
}
#[pin_project::pinned_drop]
impl Drop for MyFuture { fn drop(self: Pin<&mut Self>) {} }
```

- Drop 与投影的冲突：structurally pinned 类型用普通 Drop（拿 `&mut
  self`）就能 mem::replace 固定字段 = 撕毁承诺——pin-project 因此强制
  `#[pinned_drop]`。这就是"不手写投影"的全部理由。
- 陷阱：`Pin::set`（整体替换固定值）99% 是错误用法；`Box::pin(x)` 后
  `*boxed = y` 一样违禁。

## 49. !Unpin 类型与 Pin::new_unchecked 纪律

- 固定的三个合法途径：①`Box::pin(x)` 堆固定（pointee 从此地址不变）；
  ②`std::pin::pin!(x)` 栈固定——宏用**影子变量**保证"之后没人能
  move"，安全无 unsafe；③`Pin::new_unchecked` 裸固定——unsafe。
- new_unchecked 承诺书：地址从固定起不变；值不会通过别名被移走
  （unsafe Deref/DerefMut 漏洞是重灾区）；**Drop 只能拿 Pin 视图**。

```rust
// pin! 的影子技巧：新绑定遮蔽旧名，外面拿不到 &mut 原值 → 承诺成立
macro_rules! pin {
    ($x:ident) => { let mut $x = $x;
        let $x = unsafe { Pin::new_unchecked(&$x) }; };
}
// 你的 unsafe 写不出这层遮蔽就别手写 —— 直接用 std::pin::pin!
```

- 陷阱：`Pin<Box<T>>` 字段本身是 Unpin——外层可移动（移指针不动
  pointee，承诺未破）；反过来 !Unpin 值直接塞 Box 到处传不构成固定。
- 陷阱：自造 Future 公开方法出现 `fn poll(&mut self)` + !Unpin 状态机 =
  API 设计错误，应签名 `Pin<&mut Self>`。堆固定用 `Pin<Box<dyn
  Future>>`；栈上热路径用 pin! 零分配。

## 50. 取消即 Drop：async 世界的根本语义

- Rust 没有 CancellationToken 级语言机制：**取消 = 在某个 await 点把
  future drop 掉**。timeout/select/关停都是语法糖。Drop 是你**唯一的
  取消回调**——同步、不可 await、必须快。
- 推论：①取消只在 await 边界——同步段不可打断；②被取消时跨 await 局部
  按逆序析构（7 节），RAII guard 是唯一可靠的撤销者；③**异步清理没有
  语言出口**：Drop 里不能 await，"释放分布式锁要发 RPC"没法用 Drop
  干净表达（65 节）。

```rust
struct TxnGuard<'a> { conn: &'a Conn }   // RAII：取消时回滚
impl Drop for TxnGuard<'_> {
    fn drop(&mut self) { self.conn.rollback_sync(); } // 只能同步！
}
async fn txn(conn: &Conn) {
    let _g = TxnGuard { conn };
    do_work(&conn).await;      // ← 这里被 drop：回滚由 guard 保证
}
```

- 关停语义：Runtime drop 时未完成任务在**下一个 await 点**被取消——
  部署时"任务凭空蒸发"多源于此（55、59 节）。陷阱：guard drop 里
  panic = 双重 panic 变 abort（7 节）——清理路径永远 catch + log。

## 51. 取消安全判定清单与常见违章

- 判据（tokio 文档口径）：**"drop 掉这个 future 再从头建一个"必须与
  "从未 drop"等效**。等效 = 取消安全；不等效（丢状态/丢数据/重做副
  作用）= 不安全。所有 select!/timeout 包裹的分支都过这条。
- 常见判定表：`sleep`/`interval.tick` 安全（纯等待）；`mpsc::recv` 安全
  （没收到就没状态）；`mpsc::send().await` **不安全**（等容量时被 drop，
  消息从未入队——生产端以为发了）；permit（`reserve`）安全（把等容量
  与投递分开）；`read/write` 半包**不安全**（读一半/写一半，重来粘包/
  丢流）——要"取消后能续"的流式协议得自己做重组缓冲。

```rust
// 违章：send 不取消安全 —— 超时丢消息
tokio::time::timeout(dur, tx.send(item)).await;
// 修复：permit 把"等容量"与"投递"分开
let permit = tokio::time::timeout(dur, tx.reserve()).await??;
permit.send(item);   // 同步、不 await、不丢
```

- 违章高发区：select 分支放非幂等副作用（自增计数、推进状态机）；循环
  每轮重建 op_future 隐性重跑（53 节）；持 MutexGuard 跨 await（62 节）。
  纪律：库的 async fn 文档标注取消安全性；评审逐个盘问"drop 中间态"。

## 52. tokio::select! 的坑：分支丢弃与公平性

- select! 三件套：**随机顺序轮询**各分支（默认）；某分支 Ready 执行其
  handler；**其余分支 future 立即 drop**。第三条是所有事故源头（51 节）。
- 坑一：循环里写 `select! { r = op() => .. }` —— `op()` 每轮**重建**，
  前一轮进度全部蒸发。op 是"分帧读协议"就永远卡在第一帧；op 有副作用
  就重复执行。修法：hoist 出循环 + fuse（53 节）。
- 坑二：默认随机轮询没有优先级；`biased;` 打开确定性（从上到下），但
  biased + 慢分支会饿死后面分支——选择理由写进注释。

```rust
// 错误范式：每轮重建 op
loop { tokio::select! { r = heavy_op() => use(r), } }
// 正确范式：外建 + fuse（FusedFuture 才允许 select 借用）
let mut op = std::pin::pin!(heavy_op().fuse());
loop { tokio::select! {
    r = op.as_mut() => { use(r); }       // 需要新 op 时显式重建
    _ = shutdown.cancelled() => break,
} }
```

- 坑三：handler 里的 await 不受 select 保护（保护只到 Ready）——内部
  再 await 照常可被取消（50 节）。坑四：两分支同时 Ready 只执行一个，
  一次性资源另一边可能**丢事件**——biased 先收关键分支或 select_all。

## 53. select! 循环模式的取消安全审计

- 审计流程（每个 select 循环走一遍）：①列出全部分支 future；②逐个回答
  "drop 它 = 什么状态蒸发"（51 节）；③标注循环内重建 vs 外复用；④
  shutdown 分支是否真打得断所有等待。
- 超时包装：`timeout(d, op())` 自身安全（sleep 安全），但**透传** op 的
  不安全性——op 半途蒸发照样丢状态。安全组合：超时只包等待不包副作用
  （permit 模式）；"必须完整跑完"的 op 用 spawn + 结果超时。

```rust
let h = tokio::spawn(read_frame(stream.clone()));   // op 不被半途 drop
match tokio::time::timeout(d, h).await {
    Ok(Ok(f)) => Some(f),
    Ok(Err(e)) => return Err(e),      // 任务 panic
    Err(_elapsed) => None,            // 任务仍在跑、结果被丢弃——语义写清
}
```

- fuse 纪律：循环外 future 必须是 FusedFuture，否则完成后再 poll =
  panic；select! 对 `&mut fused` 复用——hoist 的正规形态。陷阱：前置
  条件 false 的分支**直接不参与**——别把副作用放条件里；多路嵌套时
  取消是**整批 drop**，审计覆盖组合态。

## 54. Tokio runtime 拓扑：worker、窃取与预算

- multi_thread 默认 = 每逻辑核一个 worker；每 worker **本地队列**（约
  256 槽）+ 全局**注入队列**；找活次序：本地 LIFO 槽 → 本地 → 偷别队 →
  全局（LIFO 槽提升 wake-after-yield 局部性）。
- **coop 预算**：每任务每 poll 默认 128 个"操作点"（IO/定时动作扣减），
  耗尽后即使就绪也返回 Pending 强制让出——"明明没阻塞却变慢"多半是
  预算在轮转；`global_queue_interval` 调节防饿死。

```rust
let rt = tokio::runtime::Builder::new_multi_thread()
    .worker_threads(4)             // 容器限额场景：显式小于核数
    .max_blocking_threads(64)      // spawn_blocking 池上限（默认 512）
    .thread_keep_alive(Duration::from_secs(30))
    .global_queue_interval(31)
    .build()?;
```

- current_thread：无 worker 无窃取——任务全在 block_on 线程跑，**不
  block_on 就不前进**（55 节）；spawn_local + LocalSet 才容非 Send 任务。
- 容器纪律：K8s limit CPU=2 时默认仍按**节点核数**开 worker——显式
  worker_threads 对齐 limit。陷阱：线程名全默认 = 火焰图全叫
  tokio-runtime-worker，生产必配 `.thread_name()`。

## 55. spawn 与 block_on 的线程语义陷阱

- `#[tokio::main]` 展开 = "build runtime + **主线程** block_on(你的
  future)"——主线程不是 worker。推论：主 future 结束后**已 spawn 的
  后台任务不再执行**（main 返回 → runtime drop → 全部取消，50 节）。
- 陷阱一：**嵌套 block_on** —— async 上下文（worker/任务内）再调
  `Runtime::block_on` 直接 panic 或死锁。同步线程想进 runtime：先在
  runtime 内 `Handle::current()`，再在其他线程 `handle.block_on(..)`。
- 陷阱二：worker 里调 `std::thread::sleep`/重计算 = 饿死同核任务——
  Tokio **不会**从被阻塞 worker 偷任务（57 节）。

```rust
// 正确的"同步世界进 runtime"：
let handle = tokio::runtime::Handle::try_current().ok();
std::thread::spawn(move || {
    let h = handle.expect("runtime 已退");
    h.block_on(async { fetch().await });
}); // 错误：任务内再 block_on → panic: cannot block the current thread
```

- 陷阱三：`spawn` 的 JoinHandle 被 drop = **detach**（任务继续跑、结果
  丢弃）——与 JoinSet 的"drop 即 abort 全部"相反（58 节）。陷阱四：
  current_thread 上 spawn 后不 block_on = 任务永远排队——测试里常见
  "任务没跑完测试就过了"的假绿。

## 56. block_in_place 与 spawn_blocking 的边界

- `spawn_blocking`：把闭包扔进**独立阻塞线程池**（与 worker 分离，上限
  默认 512），返回 JoinHandle。适用：阻塞 syscall（同步文件、重 DB
  驱动、压缩库）、>10-100µs 的 CPU 尖刺。它是"预期阻塞"的正规出口。
- `block_in_place`：把**当前 worker 变性**为阻塞线程（同时安排替补
  worker）后同步执行。仅 multi_thread 可用（current_thread panic）；
  代价：线程变性开销、**打断 coop 预算**（54 节）、里面不能再 await。
- 判定：能改签名/能提前 spawn → spawn_blocking；在 async Drop、同步
  回调深处必须就地阻塞 → block_in_place；都放不下 → 架构在错层
  （阻塞下沉到专用线程 + 通道）。

```rust
// 常规：明确要阻塞 → spawn_blocking
let n = tokio::task::spawn_blocking(move || heavy_sync(&path)).await?;
// 少数死角：block_in_place（多线程 runtime 专属）
let r = tokio::task::block_in_place(|| rayon::join(a, b)); // 内嵌 CPU 框架
```

- 陷阱：spawn_blocking 池满后排队——高并发同步 IO 要显式压池（信号
  量/限流），否则 P99 尾巴来自排队。block_in_place 里可拿
  `Handle::current()`，但**别**在里面 block_on 新任务（55 节嵌套）。
## 57. 运行时饿死：阻塞调用的三种形态与取证

- 三种形态：①CPU 长循环（同步压缩、大 JSON 解析）②阻塞 syscall（同步
  文件 IO、getaddrinfo、老 DB 驱动）③持锁 await（62 节）。worker 无法
  被抢占——几个阻塞线程就够让队列积压、p99 飙升。
- 取证：tokio-console（poll/self 时间与 waker 次数）、samply 火焰图、
  `Runtime::metrics()`（阻塞池队列深度）。症状：CPU 不满但延迟高，
  或单核 100%。

```rust
// 明确阻塞 → spawn_blocking（池满会排队：高并发同步 IO 要压流，56 节）
let packed = tokio::task::spawn_blocking(move || zstd::bulk::compress(&buf, 3))
    .await                           // 双层 Result：JoinError + 压缩错误
    .expect("blocking 线程 panic")?; // panic 收敛纪律见 58 节
// 可分块的重循环：块间显式让出，兜住 coop 预算（54 节）
for c in data.chunks(1 << 20) { process(c); tokio::task::yield_now().await; }
```

- 修复梯度：阻塞明确 → spawn_blocking；可分块 → yield_now 分段；持锁
  await → 缩临界区或改消息架构（60、67 节）。
- 隐蔽源是依赖回调深处的同步操作（TLS 握手、日志刷盘、DNS）——新
  依赖到手先 grep `thread::sleep`；staging 常驻 tokio-console 防回归。

## 58. JoinSet/JoinHandle 与任务生命周期管理

- 语义分水岭：**JoinHandle drop = detach**（任务继续跑、结果与 panic
  丢失）；**JoinSet drop = abort 全部**——两个 drop 语义相反，混用
  即事故（以为出作用域就停 = detach 泄漏；以为只是遗忘 = 整批被杀）。
- 后台任务必须被看管：JoinSet + `join_next()` 收割，`JoinError` 区分
  `is_cancelled`/`is_panic`，payload 用 `into_panic()` 还原上报——
  detach 任务的 panic 只有默认 stderr，静默退化无人知晓。

```rust
let mut set = tokio::task::JoinSet::new();
for p in files { set.spawn(process(p)); }    // 直接收管，不 detach
while let Some(res) = set.join_next().await {   // None = 退出条件
    match res { Ok(v) => consume(v),
                Err(e) if e.is_panic() => report(e.into_panic()), // 收敛
                Err(_) => {} }                   // is_cancelled：正常
}
```

- abort 只在**下一个 await 点**生效：同步段跑的任务 abort 打不断——
  纯 CPU 任务要配合分块让出（57 节）才可中断。
- 生产 spawn 全走 `tokio::task::Builder::new().name(..)`（console 与
  panic 可定位）；`detach()` 每次都要写理由——裸 spawn 只许
  fire-and-forget 且永不 panic 的场景（59 节收割）。

## 59. 优雅停机：shutdown token 与收割模式

- 通知选型：`tokio_util::sync::CancellationToken`（`cancelled()`、
  `child_token` 级联取消）比裸 watch 语义化；停机信号只需"最新
  状态"，别用 broadcast（61 节）。
- 收割四步：①停接新（关 listener / drop 入口 Sender）②存量跑完或
  超时（join_next + timeout）③超时 abort 残留 ④Runtime drop。跳过
  ②③ 直接 drop = 任务在下一个 await 点蒸发（50、55 节）。

```rust
pub async fn serve(tok: CancellationToken, mut tasks: JoinSet<()>) {
    let listener = bind().await?;
    tokio::select! { _ = accept_loop(&listener) => {},
                     _ = tok.cancelled() => {} }   // SIGTERM → cancel()
    drop(listener);                                // ① 停接新
    let ok = tokio::time::timeout(GRACE, async {   // ② 收割（处理 Err）
        while tasks.join_next().await.is_some() {} }).await.is_ok();
    if !ok { tasks.abort_all(); }                  // ③ 强杀 ④ runtime 收尾
}
```

- K8s 对齐：`terminationGracePeriodSeconds` ≥ GRACE + preStop 预算；
  SIGTERM 用 `tokio::signal::unix::signal` 注册进 token。
- 陷阱：token 只挂 select 最外层（53 节 fuse 纪律）；深层任务靠"信箱
  Sender 全 drop → recv None"收敛——两级机制都要审计到。

## 60. mpsc 有界通道与背压设计

- `mpsc::channel(n)` 容量 = 缓冲**消息条数**；满则 `send().await` 挂起
  ——这就是背压链：生产者慢下来层层传导到 socket 读取，天然限流。
  `unbounded_channel` 没这道闸：生产 > 消费时无界增长 = OOM 源头。
- permit 模式解耦"等容量"与"投递"：`tx.reserve().await` 拿 permit 后
  `permit.send()` 同步投递不丢消息——select 循环里取消安全的标准
  修复（51 节）；1.44+ 另有 `send_timeout`。

```rust
let (tx, mut rx) = tokio::sync::mpsc::channel::<Job>(256);
tokio::spawn(async move {
    while let Some(job) = rx.recv().await {   // 全部 Sender drop → None 退出
        run(job).await;
    }
});
let permit = tx.reserve().await?;  // 等容量：可被 timeout/select 安全打断
permit.send(job);                  // 投递：同步、不 await、不丢消息
```

- 容量规划 = 峰值吞吐 × 可容忍排队延迟；用 `tx.capacity()` 暴露
  metrics——队列深度是最早的过载红灯，别拍 1024。
- 陷阱："关闭" = 全部 Sender drop；任务里长期持有 Sender 会让消费循环
  永远退不出（结构化停机要显式 drop，59 节）。

## 61. watch 与 broadcast 通道语义深水

- watch = **单槽最新值**：`send` 直接覆盖（没看也算送达，全部 Receiver
  掉线才 Err）；中间值合并丢失——是"当前状态"通道，不是事件队列。
  `changed().await` 必须配 `borrow_and_update()`：忘了 mark_seen 就
  等不到下一次唤醒。
- broadcast = **环形缓冲**：落后太多时 recv 返回 `RecvError::Lagged(n)`
  ——丢的是整段中间消息。把 Lagged 当一等事件：放弃增量、触发全量
  重同步，而不是当没事继续。

```rust
let (cfg_tx, mut cfg_rx) = tokio::sync::watch::channel(Config::default());
tokio::spawn(async move {
    while cfg_rx.changed().await.is_ok() {
        let c = cfg_rx.borrow_and_update().clone(); // 标记已读（勿用 borrow）
        reload(c).await;
    }
});
// watch 初始值必填且首轮 changed 不触发——启动同步放循环外；
// broadcast 每投递对每个订阅者 clone 一份：大消息装 Arc 再发
```

- broadcast 容量 = 峰值速率 × 最慢订阅者处理延迟：太小常态化 Lagged，
  太大掩盖背压（lagged 次数做成指标）。
- 陷阱：watch 的 `Sender::send` 在订阅者全掉时返回 Err——发送端要处理
  "没人看了"的语义（清理上游生产任务）。

## 62. tokio::Mutex 与 std::sync::Mutex 选择铁律

- 铁律（Tokio 官方口径）：临界区**不含 await** → `std::sync::Mutex`
  （更快、零 async 依赖）；必须持锁 await → `tokio::sync::Mutex`
  （guard 是 Send）。默认答案是 std；tokio::Mutex = "我需要跨 await
  持锁"的显式声明。
- 事故一：std guard 跨 await → future 变 !Send → spawn 编译失败（编译器
  在救你）；藏进 `block_on` → 死锁或 panic（55 节）。事故二：两把
  tokio::Mutex 交叉 await 持有 = 死锁，排队无优先级，头堵尾全堵。

```rust
// 快路径：std 锁、临界区内零 await
let v = { let g = cache.lock().unwrap(); g.get(&k).cloned() };
// 确需跨 await：先快照再放锁，绝不 await 在锁内
let data = tokio_lock.lock().await.snapshot();  // guard 立即释放
do_io(data).await;
// 毒化差异：std 会中毒（lock 返回 Err 逼你处理）；tokio 不会——
// 崩溃后共享状态一致性要自己兜
```

- 更 Tokio 风格的答案往往不是锁：单 owner 任务 + 通道（67 节）、读多
  写少用 ArcSwap（27 节）。
- 评审红线：tokio::Mutex 必须带"为什么必须跨 await"的注释；std 锁
  出现在 async fn 里要确认临界区无 await 藏匿。

## 63. async trait 的 dyn 兼容：async_trait 与替代

- `#[async_trait]` desugar 后方法返回 `Pin<Box<dyn Future + Send +
  'async_trait>>`——**每次调用一次堆分配**加 vtable 与 Box 双重间接。
  量级几十 ns/次：RPC/DB 层无感，每秒百万次的分帧层可测出。
- 需要 dyn 的三个选项：①async_trait（省事，分配税）②手写 `Pin<Box<
  dyn Future + Send + '_>>`（语义同款）③enum 分发（closed set 零
  分配）。不需要 dyn 时永远优先 AFIT/RPITIT + trait_variant（64 节）。

```rust
enum Fetcher { Http(HttpF), File(FileF) }   // ③ closed set：零分配免 dyn
impl Fetch for Fetcher {
    async fn get(&self, u: &str) -> Bytes {  // AFIT：静态分派（16 节）
        match self { Fetcher::Http(h) => h.get(u).await,
                      Fetcher::File(f) => f.get(u).await }
    }
}
```

- 陷阱：async_trait 返回的 future 生命周期绑 `&self`——把 future 存进
  self 结构（自引用）编译不过；单线程用 `#[async_trait(?Send)]`
  显式去掉宏默认加的 Send bound。

## 64. Send bound 与生命周期噩梦：trait 返回 future

- 核心裂缝：AFIT 返回的 future 是否 Send 由**实现方**捕获决定，trait
  签名默认不约束——调用方 `spawn(obj.fetch())` 因 !Send 编译失败，
  且调用点无法补救（16 节限制二）。
- 解法一：trait 定义处直接写 `-> impl Future<Output = T> + Send`
  （RPITIT + Send）——实现方被迫返回 Send future；代价：实现里捕获
  Rc/RefCell 的写法直接非法，约束泄漏给所有实现者。
- 解法二：`trait_variant::make` 生成 Send 变体 trait——双 trait 并存
  的官方姿势（本地版 + 可 spawn 版）。

```rust
#[trait_variant::make(FetchSend: Send)]  // 生成 FetchSend：返回值承诺 Send
pub trait Fetch {
    async fn fetch(&self, url: &str) -> Bytes;  // Fetch 留给本地 runtime
}
// tokio::spawn 的调用点一律要求 FetchSend；单线程用 Fetch
```

- 坑：trait 上加 `Self: Sync` 想间接保证 Send——**不充分**：future
  捕获的是方法局部状态，与 Self 的 Sync 是两回事。
- 坑：手写 `Pin<Box<dyn Future + Send + 'static>>` 把"不许借 self"塞
  进 ABI——实现方被迫克隆全部状态（63 节对照）。

## 65. async Drop 现状与替代模式

- 现状（2025 仍如此）：**AsyncDrop 未稳定**——nightly 有 trait 与
  async drop glue 设计（先 AsyncDrop 再回落同步 Drop），生产不可
  依赖。`Drop::drop` 里不能 await 是硬约束（50 节）。
- 模式一（首选）：**显式关闭约定**——`async fn shutdown(&mut self)`；
  Drop 只做同步兜底（记日志、置标志），文档写明"不 shutdown 就
  drop = 粗暴掐断"。
- 模式二：**守卫 + 清理通道**——Drop 里 `try_send`（非阻塞、永不
  panic）往清理任务发句柄，由它执行 async 清理。

```rust
struct ConnGuard { id: ConnId, cleaner: mpsc::Sender<ConnId> }
impl Drop for ConnGuard {
    fn drop(&mut self) {
        // try_send 非阻塞：满/关即降级——绝不 await、绝不 panic（50 节）
        if self.cleaner.try_send(self.id).is_err() { warn!("走粗暴关闭"); }
    }
}
// 常驻清理任务：while let Some(id) = rx.recv().await { graceful(id).await }
```

- 模式三：**作用域收集**——请求级对象统一进 `Scope`，结尾显式
  `scope.teardown().await`，Drop 只当保险丝。
- 陷阱三连：Drop 里 `Handle::current().block_on` = 死锁/panic；Drop 里
  `tokio::spawn` 清理任务 = runtime 停机时永远不跑（55、59 节）；Drop
  里阻塞 IO = 饿死 worker（57 节）。

## 66. 测试 async：time::pause 与确定性时间

- `tokio::time::pause()`（或 `#[tokio::test(start_paused = true)]`，需
  feature "test-util" + current_thread）：时钟冻结，runtime 空闲时
  **自动推进到下一个到期定时器**——sleep/interval/timeout 测试从
  "真等 30 秒"变 0 毫秒且完全确定性；手动推进用
  `tokio::time::advance(d).await`。

```rust
#[tokio::test(start_paused = true)]
async fn retry_backoff() {
    let svc = svc_with_failures(2);        // 内部 sleep(1s) 重试两次
    assert!(svc.get().await.is_ok());
    assert_eq!(tokio::time::Instant::now().elapsed(),
               Duration::from_secs(2));   // 虚拟时钟：零真实耗时
}
```

- 坑一：pause 只在 current_thread runtime 可用（multi_thread panic）。
- 坑二：真实 IO 不吃虚拟时钟——确定性测试必须 mock 掉 IO（trait 抽象
  / wiremock）；start_paused 下 `sleep(30s)` 瞬间到底，依赖"中途
  状态"的断言会失效。
- 真实时间用例：timeout 包裹 + 宽预算，时间敏感用例 `--release` 跑或
  标记 `#[ignore]` 手动跑。

## 67. actor 模式与 select_loop 惯例

- actor = 一个任务 + 一个**有界**信箱 + select 循环：状态私有、无锁、
  消息顺序天然串行化——"共享可变状态"的 Tokio 正统答案（62 节的
  上一级）。信箱必须有界（60 节）：unbounded 信箱 = OOM 放大器。
- 标准形：命令分支（mpsc recv）+ 停机分支（CancellationToken）+ 周期
  分支（`interval.tick()`——tick 永远取消安全，51 节）；回执用
  oneshot：actor 的 RPC 惯例。

```rust
enum Cmd { Get { key: String, reply: oneshot::Sender<Value> } }
async fn actor(mut rx: mpsc::Receiver<Cmd>, tok: CancellationToken) {
    let mut state = HashMap::new();
    let mut tick = tokio::time::interval(Duration::from_secs(10));
    loop { tokio::select! {                 // 命令/停机/周期三分支
        Some(Cmd::Get { key, reply }) = rx.recv() =>  // None → 分支禁用！
            send_reply(&mut state, key, reply),
        _ = tok.cancelled() => break,
        _ = tick.tick() => flush(&mut state).await,
    } }
}
```

- 生命周期可推导：`ActorHandle { tx }` drop → 信箱关闭 → actor 收敛
  退出（与 59 节停机分支双保险）。
- 坑：handler 内 await 期间信箱堆满 = 背压传导（特性不是 bug）；\
  `Some(x) = rx.recv()` 模式不匹配时该分支**永久禁用**——退出逻辑
  显式处理 None，别藏进 select 条件；嵌套 select 放大 53 节审计
  复杂度，一层循环就够。

## 68. 性能测量纪律：工具链与统计噪声

- 铁律一：debug 构建的测量**一律无效**（无内联、边界检查全在、指令
  序列与 release 无关）——`[profile.release] debug = true` 保符号再测。
  铁律二：吞吐与 p99 双指标——均值同时藏住"整体慢"与"尾部炸"。
- 工具链：Linux perf / cargo-flamegraph；samply（macOS 可用、跨平台）；
  内存 heaptrack / bytehound；分配计数 dhat（73 节）。perf stat 看
  IPC / branch-misses / LLC miss——比火焰图更早嗅出 icache 与分支病。

```bash
cargo flamegraph --release --bin server -- --load 10k   # CPU 热点
perf stat -e instructions,cycles,branch-misses,LLC-load-misses ./bench
hyperfine --warmup 3 './tool a' './tool b'              # CLI 端到端
```

- 噪声纪律：每变体 ≥5 次采样；warmup 丢首轮；看分布不只均值
  （criterion 离群点分类，94 节）；turbo/节能/容器 noisy-neighbor 都
  要记账，测量脚本可复现环境（`taskset` 固定 CPU 集）。
- 反模式：不测量先"顺手优化"；拿微基准结论改架构（分配与 cache 行
  行为要靠 macrobench 验证）。

## 69. 边界检查消除：迭代器、assert 与 LLVM 可证明性

- 迭代器路径免费消除：`for x in &v`、`zip`、`chunks_exact` 把长度
  证明交给 LLVM；索引路径 `v[i]` 每次付 `cmp + ja panic`。索引型热
  循环要么改迭代器，要么给 LLVM 喂证明。
- 喂证明三招：①循环前 `assert!(upto <= v.len())` 一次性收窄（LLVM
  据此删循环内检查）②`split_at` 先切再各跑各的 ③下标用 usize/u32
  ——i32/i64 下标在 64 位比较路径混入符号扩展指令。

```rust
fn sum_below(v: &[u32], upto: usize) -> u32 {
    assert!(upto <= v.len());        // 一次性证明，循环内检查消失
    let mut s = 0;
    for i in 0..upto { s += v[i]; }  // godbolt 验证：无 panic_bounds
    s
}
// 更优：v[..upto].iter().sum() —— 根本不进索引世界
```

- 验证纪律：godbolt / cargo-asm 确认检查真没了（找 panic_bounds 调用）；
  `-C panic=abort` 可再砍 panic 路径成本（权衡 83 节）。
- `get_unchecked` 是最后手段：SAFETY 义务 = 范围证明（35 节）；尾块用
  `chunks_exact` + `remainder()`（75 节向量化前置）。
- 陷阱：`assert!` 写进循环内 = 每轮一次白干；"传 &T + len"的 API 让
  证明链跨函数断裂——把不变量做进切片类型本身。

## 70. 分配器选择：system/jemalloc/mimalloc

- glibc malloc：per-thread arena 缓解锁争用，但高并发小对象仍有热点；
  碎片让长寿命服务 RSS 缓涨（free 不还 OS）。Windows NT 堆明显更慢
  ——换分配器在 Windows 的收益常大于 Linux。
- jemalloc：多 arena + size-class 分治，高并发小对象强；代价是保留
  内存（dirty-page 策略）→ RSS 偏高；内建 prof 可当取证工具（110 节）。
  mimalloc：线程本地 free-list、无锁快路径；元数据内存开销略高。

```rust
#[global_allocator]
static A: tikv_jemallocator::Jemalloc = tikv_jemallocator::Jemalloc;
// 或： static A: mimalloc::MiMalloc = mimalloc::MiMalloc;
```

- 铁律：**测了再换**——收益 workload 相关：服务端高并发小分配最
  受益（两位数百分比常见），批量大分配/低分配率几乎无感。
- 测量口径两维：分配吞吐（op/s）与 RSS/碎片曲线——只看吞吐会引入
  "快但吃内存"的分配器；线程创建密集程序还要看启动成本。
- 陷阱：分配器争议以自己的 macrobench 为准（68 节），别抄博客结论。

## 71. 内联预算：#[inline] 效力、LTO 与 PGO

- 事实基线：泛型函数（含 derive 展开）在调用 crate 单态化——跨 crate
  内联**免费**；非泛型 pub fn 默认不跨 crate 内联。小工具函数想跨
  crate 内联：标 `#[inline]`；`#[inline(always)]` 是膨胀税（icache +
  编译时间），只配"≤几行且确定热"；`#[inline(never)]` 用作调试锚点。

```toml
[profile.release]
lto = "thin"          # thin LTO：跨 crate 内联与布局起点；fat 更强更慢
codegen-units = 1     # 最终发布构建用：质量换编译时间
# PGO（cargo-pgo 封装 llvm-profdata 管线）：
#   cargo pgo bench --load 100k   → 采集代表负载轮廓
#   cargo pgo build --release     → 轮廓驱动分支/内联/布局（常见 5-15%）
```

- PGO 增益来自真实负载轮廓——负载不真实（只跑单测）= 白做；代码
  漂移后要重采。
- 验证：cargo-asm 看目标函数是否真内联；thin→fat 的边际收益用
  macrobench 验证（68 节）。
- 陷阱：LTO 与 incremental 不兼容（release 本来就关）；`#[inline]`
  滥用会放大编译时间——与单态化是同一种病（21、78 节）。

## 72. zero-copy 设计：借用驱动的解析管线

- 管线形态：`fn parse<'a>(buf: &'a [u8]) -> Result<Frame<'a>>`——
  Frame 借自输入：零分配零拷贝，栈上只有偏移量。代价：生命周期传染
  （12 节）；库 API 常配套 `parse_owned`（String/Vec 版）。
- `bytes::Bytes`：引用计数的不可变缓冲——`slice()` 只记偏移不复制、
  clone O(1)、跨任务分发网络缓冲的事实标准；`BytesMut::freeze()` 转
  化。单 owner 管线用 `&'a [u8]`，网络多播用 Bytes。

```rust
struct Frame<'a> { typ: u8, payload: &'a [u8] }
fn parse<'a>(b: &'a [u8]) -> Option<Frame<'a>> {
    let (&typ, rest) = b.split_first()?;
    let len_b = rest.first_chunk::<2>()?;          // 1.77+：定长前缀切片
    let n = u16::from_le_bytes(*len_b) as usize;
    let payload = rest.get(2..2 + n)?;   // 长度不可信：切片即验证（69 节）
    Some(Frame { typ, payload })                  // 全程零分配零拷贝
}
```

- serde 借用：`#[serde(borrow)]` + `&'a str` 字段让反序列化不分配；
  混合场景用 `Cow<'a, str>`（30 节）。对照：`bumpalo` arena 解析适合
  批处理大文档，Bytes 适合流式网络路径。
- 陷阱：`&str` 化必须先 `from_utf8` 校验——zero-copy 不是"跳过
  校验"（40 节）；对长度字段的信任 = 解析器漏洞。

## 73. SmallVec 与 inline storage：削减分配

- `SmallVec<[T; N]>`：N 个内联槽，溢出才上堆——元素 ≤N 时零分配；
  move SmallVec = 内联数据整块 memcpy（8 节）：`SmallVec<[u8; 64]>`
  按值传递就是 64B 拷贝，热路径按 `&mut` 传。
- 收益边界三判据：①元素大概率小（>80-90% 命中内联才有赚）②分配在
  热路径（一次分配 ~20-50ns，命中即省）③尺寸小（≤128B 内联，否则
  move 成本与栈压反噬）。

```rust
use smallvec::SmallVec;
fn tokens(line: &str) -> SmallVec<[&str; 8]> {  // ≤8 词：零堆分配
    line.split_whitespace().collect()
}
// 纪律：先用 dhat-rs 分配计数证明"分配是热点"再上 SmallVec，
// 改造前后各测一轮（68 节）——"感觉更快"不算证据
```

- 代价：spill 分支打断向量化（75 节）；spill 后与 Vec 同价但已多付
  内联拷贝——长度方差大的数据别用。
- 同族：`arrayvec`（无堆 fallback，满即错）、`tinyvec`（无 unsafe
  实现，可信度优先选它）；`Box<[T]>`/`Arc<[T]>` 是"容量字段也省"
  的另一刀。

## 74. false sharing 与缓存行对齐

- 机制：缓存一致性以 64B 行为单位（Apple Silicon 有 128B 预取对——
  保险对齐 128）；两核各写**同一行**的不同字节 → 行在核间来回失效
  （MESI 乒乓）。代码单线程视角完全正确，吞吐却随核数下降。
- 高发位：`counters[cpu_id]` 紧挨排布的 per-thread 计数器；原子变量
  与热点只读数据挤同一行。只读共享**不**产生乒乓——只有写才炸。

```rust
use crossbeam_utils::CachePadded;
struct Sharded { hits: [CachePadded<AtomicU64>; NCPU] } // 每份独占一行
// 等价手写：#[repr(align(64))] struct Padded(AtomicU64);
// CachePadded 在 aarch64/apple 自动放大到 128 ——别自己拍 64
```

- 检测：Linux `perf c2c` 直接定位冲突行；症状学：加核反而变慢、
  perf stat 的 LLC miss / remote-hit 异常（68 节）。
- 纪律：只 pad 有**写争用**的字段（内存换并发，全 pad 是浪费）；
  分片计数器聚合用 `Relaxed` 累加（27 节成本模型）。
- 陷阱：`#[repr(align(64))]` 在数组上只保证**起点**对齐——元素间仍
  可能同行；元素级隔离必须用 CachePadded 数组。

## 75. SIMD 自动向量化：安全写法与置信度

- LLVM loop-vectorizer 入场条件：连续访问（stride 1）、长度可推导、
  **无可能 panic 的路径**——边界检查与溢出检查都挡路（69 节是前置）；
  debug 构建永不向量化（68 节）。
- 写法基线：迭代器 / `chunks_exact(16)` + fold；类型宽度显式 widen
  （u8→u32 累加写 `as u32`）；尾块 `remainder()` 单独处理；对每元素
  调用的非内联函数 = 向量化直接放弃（71 节配合）。

```rust
fn count_hits(d: &[u8], n: u8) -> u32 {
    let (main, tail) = d.split_at(d.len() / 32 * 32);
    let mut acc = [0u32; 32];            // 32 路独立累加器：无跨迭代依赖
    for c in main.chunks_exact(32) {
        for (a, &b) in acc.iter_mut().zip(c) { *a += (b == n) as u32; } }
    acc.iter().sum::<u32>() + tail.iter().filter(|&&b| b == n).count() as u32
}
```

- 置信度检查：`RUSTFLAGS="-Cremark=loop-vectorize"` 打向量化报告
  （missed 带理由）；godbolt 找 `paddb/vpmov` 族指令——**别猜**。
- `target-cpu=native` 打开 AVX2/512：分发型二进制用
  `is_x86_feature_detected!` + `#[target_feature(enable)]` 写多版本
  （memchr 路线）；`core::simd` 未稳定，库别依赖；`windows(2)`
  重叠读限制向量化——数据布局先改后求 SIMD。

## 76. &str 切片与 String 分配决策

- 子串免费：`&s[a..b]` 是胖指针，零分配零拷贝——需要**所有权/可变/
  跨生命周期**时才造 String；先问"能不能保持 &str 走完整条管线"
  （12 节传染、72 节 zero-copy 同源）。
- 数字→字符串：`format!("{}", n)` 走全局 Formatter（慢且必然分配）；
  `itoa::Buffer::new().format(n)` 栈缓冲零分配快 5-10×，浮点用 ryu。
  拼接热路径：`String::with_capacity` + `push_str`/`write!`。

```rust
fn header(name: &str, n: u64, out: &mut String) {
    out.reserve(name.len() + 24);            // 预算一次，免倍增重分配
    let num = itoa::Buffer::new().format(n); // 栈缓冲，&str 视图零分配
    out.push_str(name);
    out.push(':');
    out.push_str(num);
}
// 跨类型查找键：HashMap<String,_>.get("k") 走 Borrow<str> 零分配（26 节）
```

- 陷阱：`s.to_owned()` 每次深拷贝——循环里对常量重复 to_owned 是分配
  放大器；`collect::<String>()` 内部多段分配，改 fold 进 with_capacity。
- 陷阱：字节索引切在 UTF-8 字符中间 → panic——不可信输入先
  `is_char_boundary` 验证；从 bytes 构造 String 必过 `from_utf8`
  （transmute 系捷径全是 UB，40 节）。

## 77. 惰性迭代器与短路求值的平衡

- 零成本抽象属实：`map/filter/take` 链不建中间集合，与手写 for 同价
  或更快（边界检查随迭代器消失，69 节）。短路家族 `find/position/
  any/all/take_while` 命中即停——全表扫描必须用它们：`filter().count()`
  是全量扫描的伪装。
- `by_ref()`：链消费到一半停下、剩余留给后面——零复制切换"流式
  头部 + 尾部"两种视角。

```rust
let mut it = lines.iter();
let header: Vec<&str> = it.by_ref().take(3).collect(); // 头三行
let rest_max = it.map(|l| l.len()).max();              // 剩余单遍扫完
// 对照：中途 collect 成 Vec 再两轮循环 = 多一次遍历 + 一次分配；
// 但 collect 后可二分/索引/复用——两遍方案有时反而快，
// cache 行为与并行化都可能翻盘，benchmark 说话（68 节）
```

- collect 位置决定成本：单遍可完成的事别拆两遍；"先物化再多次查询"
  的反转也常见——判定依据是查询次数与数据量，不是口味。
- 陷阱：昂贵副作用塞进 `map`（短路家族下只对命中元素付出，全消费
  链上是纯税）；`filter_map` 里 clone 大对象 = 隐藏分配热点。
- `fold` 与 for 等价但**没有中途 break**——需要短路就回迭代器家族，
  别在 fold 里造假短路。

## 78. 编译时间工程：单态化瘦身与剖析

- 测量：`cargo build --timings`（单元耗时与并行度，找串行长链）；
  `cargo check` 当快速反馈圈；把 clean build 时间当 CI 指标盯——回归
  超 10% 就修，不当玄学。
- 单态化瘦身（21 节执行版）：泛型下推到叶子、主干 dyn/enum 收口；
  derive 清单审查（serde 全家桶常见大头）；bound 越宽实例化空间越大。

```toml
# dev 档：依赖开优化（测试跑得快），自身保持低优化保增量速度
[profile.dev.package."*"]
opt-level = 2
[profile.dev]
opt-level = 1
# 结构杠杆 > 黑科技：拆 crate 并行编译、default-features=false 全依赖、
# workspace-hack 统一特征（107 节）、sccache/CI 缓存
```

- `#[derive]` 的税：每个 derive 的展开产物都要过类型检查——"derive
  了没人用的 trait"是纯浪费，定期清（43 节 proc-macro 成本）。
- 陷阱：`--timings` 只看本单元，跨 crate 实例化扩散配 cargo-bloat/
  `-Zprint-mono-items`（21 节）；release 无增量——别拿 release 增量
  时间当指标。

## 79. thiserror/anyhow 边界：库与二进制的分工

- 分工铁律：**库 = thiserror 造 typed enum**（下游可 match、可程序化
  分支）；**应用/二进制 = anyhow**（动态错误 + context 叙事）。库的
  公共签名出现 `anyhow::Result` = 把错误语义决定权扔给下游——设计
  失败，评审一票打回。
- anyhow 正确用法：`.context("语义")?` 把传播链变可读叙事；顶层打印
  用 `{:#}` 连 source 链一路打到底；`anyhow!` 只用于就地造裸错误。

```rust
#[derive(thiserror::Error, Debug)]
#[non_exhaustive]                       // 82 节：加变体不算 breaking
pub enum ConfigError {
    #[error("读取 {path} 失败")]
    Io { path: PathBuf, #[source] source: std::io::Error },
    #[error("端口 {0} 越界")]
    Port(u16),
}
pub type Result<T> = std::result::Result<T, ConfigError>; // 勿用 anyhow::Result
```

- 判据：错误内容是**API 契约**（下游要写代码应对）→ typed；是"给人
  看的诊断叙事"（应用顶层）→ anyhow。两者是分层不是竞争。
- 应用侧翻译点显式：收到库错误 → match 变体映射到应用级错误/用户
  文案——不许 `let _ = e`，也不许包 anyhow 后忘记分支（81 节）。

## 80. 错误类型设计：可匹配性与信息保真

- 可匹配性：变体带**类型化字段**而非 String——`InvalidPort(u16)` 可
  编程（打点、重试决策），`InvalidPort(String)` 只能给人看。String
  字段是"当时没想清楚"的化石，公共错误类型禁止。
- 信息保真：`#[source]` 保留底层错误，display 只写本层语义——`{:#}`
  能打到底；把底层错误 format 进自己的文案 = 下游取证断链。

```rust
#[non_exhaustive]
#[derive(thiserror::Error, Debug)]
pub enum StoreError {
    #[error("对象 {key} 不存在")] NotFound { key: ObjectId },
    #[error("etcd 事务失败（重试 {attempts} 次）")]
    Txn { attempts: u32, #[source] source: etcd::Error },
}
// impl StoreError { fn code(&self) -> &'static str }——"store.not_found"
// / "store.txn_failed"：变体可重排，code 永不变（82 节 semver 锚点）
```

- 错误码比"match 变体"更稳：变体重排/新增不破坏 code 语义——报警
  规则、文档、SDK 全引用 code（82 节 semver）。
- `#[from]` 掩蔽风险：自动 From 让 `?` 静默跨层转换——io::Error 直达
  顶级错误，层次感消失；只给"确属同层"的类型 #[from]。
- backtrace：`std::backtrace::Backtrace` 只在 RUST_BACKTRACE 开时有
  内容——库别默认抓（成本+噪音），应用顶层按需抓。

## 81. context 丢失事故：anyhow downcast 的代价

- 机制边界：`anyhow::Error::downcast_ref::<E>()` 只命中**进入 anyhow
  时的那个具体类型**（`.context()` 层透明可穿透）；被 `#[from]` 包在
  source 链**中间**的类型不可见——要 walk `err.source()` 逐层找。
- 事故形态：库返回 anyhow（79 节违例）→ 应用只能字符串匹配 display
  文案——文案一改（82 节"非 breaking"）下游分支全炸。

```rust
// 兜底姿势：链上逐层找（仅限集成/插件边界，19 节纪律）
let mut src: Option<&dyn std::error::Error> = Some(err.as_ref());
while let Some(e) = src {
    if let Some(io) = e.downcast_ref::<std::io::Error>()
        { if io.kind() == io::ErrorKind::TimedOut { return retry(); } }
    src = e.source();
}
// 正解：领域层 typed enum → 应用 match 变体，downcast 不出现
```

- `.context()` 的正确期待：给**人**的叙事，不是给**代码**的分发键
  ——需要程序分支的信息必须进 typed 字段（80 节）。
- 陷阱：`anyhow!("...")` 造的裸错误 downcast 什么都拿不到；老代码
  `Box<dyn Error>` 转进 anyhow 后具体类型沉底——逐层换 thiserror。
- 纪律：downcast 出现在业务代码 = 类型边界失守信号；合法户口只有
  边界翻译层与测试断言。

## 82. 库错误 semver 兼容与 #[non_exhaustive]

- 事实：给 pub enum **加变体**是 breaking（下游穷尽 match 碎掉）；
  给 struct 加字段、收紧泛型 bound、改默认 feature 同理。
  `#[non_exhaustive]` 强制下游留 `_ =>` 兜底——加变体降级为兼容。
- 用法：enum 整体标注 + 有字段的变体也标注；代价：下游不能用
  字面量构造——错误类型通常只读，正好。

```rust
#[non_exhaustive]
#[derive(Debug, thiserror::Error)]
pub enum ApiError {
    #[error("限流：剩余配额 {left}")] RateLimited { left: u32 },
    #[non_exhaustive]                       // 变体加字段也不 breaking
    #[error("上游错误（code {code}）")] Upstream { code: i32 },
}
// 下游 match 必须带 _ => 兜底；错误码（80 节）比变体名更稳定
```

- non_exhaustive 不是免死金牌：变体语义漂移、错误码重定义照样坑
  下游——changelog 写明"变体只增、code 永不重排"。
- 门禁：`cargo-semver-checks` 进 CI（250+ 条 lint：新增变体、bound
  收紧、默认 feature 变化），1.x 库每个 release 跑；breaking 走主版本。
- 陷阱：thiserror `#[error]` 文案变化**技术上非 breaking**，但下游
  可能正则匹配日志——重文案先发预告；`Box<dyn Error>` 进 API 后想
  改 typed = breaking（79 节别欠这债）。

## 83. panic 策略：catch_unwind 与边界防御

- 前提分叉：`panic = "unwind"`（默认）才有 catch_unwind；`panic =
  "abort"` 构建下 catch_unwind 是 no-op、panic 时 Drop 不再保证运行
  ——**架构不能依赖 unwind 恢复**，profile 差异进 CI 双轨。
- catch_unwind 合法户口：FFI 出口（97 节）、测试 harness、插件隔离
  ——是"隔离爆炸半径"，不是 try/catch；错误处理走 Result（79 节）。
  `UnwindSafe` 边界：MutexGuard 等带不变量的类型故意拦你，
  `AssertUnwindSafe` 是"我保证状态一致"的签字。

```rust
fn isolate<Out>(plugin: impl FnOnce() -> Out) -> Option<Out> {
    match std::panic::catch_unwind(std::panic::AssertUnwindSafe(plugin)) {
        Ok(out) => Some(out),                 // 正常路径
        Err(payload) => { report_panic(&payload); None } // 隔离爆炸半径
    }
}
// 任务侧：JoinError::is_panic + into_panic()（58 节）
// hook：set_hook 先上报再调原 hook，hook 内绝不 panic（双重 panic=abort）
```

- 库的纪律：panic 只留给"程序 bug"（不变量破坏）；可预期失败一律
  Result——`unreachable!`、索引、除零都要过这一问。
- `panic=abort` 的连带：panic 期间 Drop 不跑 → 50 节的取消清理路径
  受影响——评估发布 profile 时把清理依赖列成清单（97 节 FFI 同理）。

## 84. ? 运算符进阶：FromResidual 与自定义传播

- desugar 真相：`expr?` = `match expr { Ok(v) => v, Err(r) => return
  FromResidual::from_residual(r) }`——传播由 **Try/FromResidual** trait
  家族驱动；自定义 Try 类型在 nightly（try_trait_v2）之外不可用，
  稳定面 = Result/Option 双轨 + From 转换。
- 隐式转换：`Err(io_err)?` 在返回 `Result<T, MyError>` 的函数里自动走
  `From<IoError> for MyError`（thiserror #[from] 生成的正是它）；多跳
  From 链 = 多层静默翻译——source 保真自己盯（80 节）。

```rust
fn load(p: &Path) -> Result<Config> {          // Result<T, AppError>
    let raw = std::fs::read_to_string(p)
        .map_err(|e| AppError::Io { path: p.into(), source: e })?; // 手工翻译
    let Some(ver) = raw.lines().next() else {  // let-else 收窄+提前退出
        return Err(AppError::MissingHeader);
    };
    let n: usize = ver.parse().map_err(|_| AppError::BadVersion)?; // 惰性 err
    Ok(Config { n })
}
```

- Option 轨道：`ok_or(mk())` **急切**求值（每次构造 err 对象），热
  路径用 `ok_or_else`；`x.ok().context(..)?` 是 Option→anyhow 惯用桥。
- `let-else`（1.65）：收窄 + 提前返回比嵌套 match 贴流程；else 分支
  必须发散（return/break/continue/panic）。
- 自定义空间：写 `impl From<E> for MyError` 就是稳定可用的全部——
  别手写 FromResidual；main 可返回 `Result<(), E: Debug>`（退出码 0/1）。
## 85. Edition 2024 迁移与 compatibility lint 全解

- Edition 2024 随 1.85（2025-02）落地。迁移次序固定：**先升工具链**
  （新语法要新编译器解析）→ 在**旧 edition 上**跑 `cargo fix --edition`
  自动套用机械修复 → 才改 `edition = "2024"` → 全量测试。直接改字段
  再编译，是把几百个可以自动修复的错误一次砸到脸上。
- 迁移前预览成本：`cargo build -W rust-2024-compatibility` lint 组逐条
  报告未来不兼容点（static_mut_refs、unsafe 属性、gen 保留字、
  if-let 作用域……），可以提前在 2021 上清零、按季度消化，而不是
  一次性停机大迁移。

```rust
// Cargo.toml: [package] edition = "2021"（先不动）
// 三板斧次序：
//   1. cargo fix --edition --allow-dirty   // 机械迁移，幂等可重跑
//   2. 改 edition = "2024"                  // 然后才切换
//   3. cargo clippy --all-targets -- -D warnings  // 收尾扫一遍
#![deny(rust_2024_compatibility)] // 新 crate 直接钉住：倒退立刻爆红
```

- workspace 逐成员迁移：先叶子后根，混 edition 合法且是官方设计——
  2021 与 2024 并存可以平稳跑完一个发布周期再收官，不必 big-bang。
- 别漏 doctest 也吃 edition：文档里的老写法（`&mut STATIC` 之类）会
  在文档测试里爆；迁移 PR 单独跑一次 `cargo test --doc`（96 节的
  编译税在这一刻最疼）。

## 86. Edition 2024 语义变化：unsafe extern 与 unsafe 属性

- `unsafe extern "C" {}`：extern 块必须显式 unsafe——声明"块内签名
  契约由我背书"；条目默认 unsafe，**审过确实安全**的（如 strlen 这类
  纯函数）才显式标 `safe`。UB 义务从"隐式全责"变成逐条标记，SAFETY
  注释（35 节）有了语法落点。
- `#[unsafe(no_mangle)]`/`#[unsafe(export_name)]`/`#[unsafe(link_section)]`
  ：凡绕过编译器保证直接操纵符号的属性，一律包 `unsafe(...)`——
  和 extern 块同一逻辑：这些声明一旦写错就是 UB，那就该长着 UB 的脸。

```rust
unsafe extern "C" {
    safe fn strlen(s: *const libc::c_char) -> usize; // null 由调用方查
    fn qsort(base: *mut c_void, n: usize, sz: usize,
             cmp: Option<unsafe extern "C" fn()>, // Option niche=可空指针（98 节）
    );                        // 未标 safe：契约靠人，调用处要 unsafe
}
static mut BUF: [u8; 64] = [0; 64];
let p: *mut [u8; 64] = &raw mut BUF;   // static_mut_refs 的唯一正解
// let r = &mut BUF;                   // 2024 hard error：&mut = 独占别名承诺
```

- `static_mut_refs` 是本次唯一 **hard error** 级变化：对 mut static 取
  引用 = 声称独占别名，而任何线程随时可写——正解是 `&raw` + unsafe
  解引用（1.82+），或干脆换 `Mutex/AtomicU8`（新写的原子逻辑交给
  90 节 loom 盯）。
- 其余清单：`gen` 成为保留字（gen block / async gen 的前置铺垫）；
  `unsafe_op_in_unsafe_fn` 默认 warn——unsafe fn 里的裸操作也要显式
  unsafe 块；if-let scrutinee 临时值提前释放（1 节的 MutexGuard
  在 else 里死锁的形态由此根治）。

## 87. RPIT 捕获规则变化与 use<> 精确捕获

- 2024 起 RPIT（自由函数返回 impl Trait）**默认捕获作用域内全部
  泛型参数与生命周期**——与 RPITIT/AFIT 既有语义对齐。2021 时代
  "未提及的生命周期也被悄悄捕获"产生的玄学报错，现在变成显式、
  统一、可推理的规则；代价是老代码可能突然报"多捕获了什么"。
- `use<...>` 是精确捕获开关，双向可用：想返回**借用入参**的零拷贝
  迭代器 → 默认即可（什么都不加）；想要 'static/Send（能 spawn、
  能进缓存）就必须**关掉**生命周期捕获——两个需求用一个语法显式
  分家，API 契约从此写在签名上。

```rust
// 借用出参：默认捕获 'a，签名即承诺"活得不比入参久"
fn words<'a>(s: &'a str) -> impl Iterator<Item = &'a str> + use<'a> {
    s.split_whitespace()
}
// 自包含出参：use<> 关闭一切捕获 → 'static + Send，可跨线程搬运
fn schema() -> impl Schema + use<> { Schema::compile(STATIC_RULES) }
```

- 隐藏捕获事故形态：函数体内的 async 块/闭包意外捕获了本该丢弃的
  借用，调用方 spawn 时冒出 `!Send`；加 `use<>` 后错误位置反而精准
  ——**use<> 不是性能开关，是错误定位与 API 契约工具**。
- 迁移纪律：报"多捕获"时逐个加 use<>，别无脑全加通配把新规则改回
  老语义；每个 RPIT 公开函数在 review 时回答一句——这个返回值
  **借用入参吗**？答案应该一眼从 use<> 读出来。

## 88. MSRV 感知解析与 resolver 版本演进

- resolver 三代演进：v1（2021 前默认）全图统一 feature，dev-deps
  也搅进生产；v2（1.51，2021 edition 默认）按 target 分开统一、
  dev/build 依赖隔离——解决"为测试启的 feature 污染发布二进制"；
  **v3 = v2 + MSRV 感知**：解析时跳过 rust-version 高于当前工具链
  的版本，edition 2024 默认启用（1.84+）。
- 兜底语义要读准：依赖**未声明** rust-version 视为兼容；找不到任何
  兼容版本时回退选最新——MSRV 感知是"尽力偏好"，不是硬保证。所以
  MSRV 门禁必须**真编译**一次（`cargo +<msrv> check`），解析器只是
  第一道滤网。

```toml
[workspace]
resolver = "3"              # 非 2024 workspace 也要显式写上
[workspace.package]
rust-version = "1.79"       # MSRV 唯一事实源：CI 与 resolver 共用
[workspace.dependencies]
anyhow = "1"                # 要求留宽（caret），选择权交给 resolver
```

- 救命场景：上游 1.x 新版要求更新的工具链——v3 自动停在你能编译
  的最后一个版本，CI 不红、用户不炸；lockfile 在手时 resolver
  不动你，**只有重新解析才感知**（106 节：update 的粒度纪律）。
- 反模式：MSRV 只写在 README 而没有 `rust-version` 字段——下游 v3
  解析器看不见你的承诺；字段、README、CI 三处必须同源同改
  （89 节工具链纪律）。

## 89. 工具链升级纪律与版本钉死策略

- `rust-toolchain.toml` 提交进仓库根：channel 精确到版本号（不是
  "stable" 浮动名）、components、profile 钉死。价值在于**新人第
  一天与 CI 第 N 次构建拿到同一个编译器**——浮动 channel 的 CI
  是薛定谔的门禁，nightly 每周新增的 lint 随时全员爆红。

```toml
# rust-toolchain.toml
[toolchain]
channel = "1.85.0"            # 精确版本；升级 = 显式 PR diff
components = ["rustfmt", "clippy", "llvm-tools-preview"]
profile = "minimal"
```

- 升级节奏成文：stable 六周一发，发布后 T+2 天内升（让生态先踩
  坑）；MSRV 策略同窗评审（"最近 5 个 stable"或"目标平台发行版
  默认 rustc"）。**MSRV 只许升不许降**，写进 CONTRIBUTING 让
  用户投诉有据可依。
- CI 三件套：stable（门禁，全测试）、MSRV（`cargo +<msrv> check`，
  只 check 不 test——dev-deps 未必支持老工具链，88 节）、
  nightly（allow-fail，提前看六周后的雷与未来不兼容 lint）。
- nightly 只许活在隔离分支：`#![feature]` 集中在一个不进 main 的
  探索 crate；main 必须 stable 编译通过。纪律一句话——nightly
  断了不阻塞团队，stable 断了才是事故。

## 90. loom 并发模型检验实战

- loom 把你的并发原语**穷举执行**：线程操作全部插桩，探索器在
  C11 内存模型下枚举每一条可行交织（用 happens-before 剪枝），
  断言在"所有可能"而非"跑过的那几次"上成立。stress 跑一亿次
  不红，loom 三个线程十毫秒就红给你看——差的就是覆盖语义。
- 写法：`cfg(loom)` 下把 std 的 sync/atomic/thread 换成 loom 对应
  类型，测试放 src 内 `#[cfg(loom)] mod tests`，`cargo install
  cargo-loom && cargo loom` 驱动。规模红线：**≤3 线程、单对象、
  个位数操作**——状态数随操作数指数爆炸，跑一小时的大模型不叫
  覆盖，叫浪费。

```rust
#[cfg(loom)]
mod tests {
    use loom::{sync::Arc, thread};
    // 经典样本：双线程 clone/drop，验证引用计数协议无双重释放
    #[test]
    fn arc_drop_race() {
        loom::model(|| {
            let a = Arc::new(0u8);
            let b = a.clone();
            let h = thread::spawn(move || drop(b));
            drop(a);            // 两次 drop 的先后由探索器穷举
            h.join().unwrap();  // 计数协议错 = 某条交织上立刻失败
        });
    }
}
```

- loom 认真区分你声明的**内存序**：SeqCst 与 Relaxed 的语义差异
  会被逐交织验证——很多人在这里第一次亲眼看到 Relaxed 计数丢了
  更新。断言要指向最终可观察状态，"没 panic"不算断言。
- 模型替代不了两样东西：时间（模型里不能 sleep，用 `loom::thread::
  yield_now` 制造交织）与系统调用（loom 线程里没有真 IO）——所以
  loom 只测纯内存原语，系统交互交给 91 节的分工。

## 91. loom 与真实并发测试的分工

- 分工铁律：**loom 管"原语内"，压测管"原语间"**。新写或改动
  lock-free 队列、OnceCell 竞争、引用计数协议 → loom 穷举（90 节）；
  集成层（线程池 + 通道 + 业务状态机）状态空间早已爆炸 → 随机化
  压测。试图用 loom 测集成层，是让模型检查器替你承受组合爆炸。
- shuttle（随机化调度 + 种子复现）是中间档：比裸压测覆盖广——
  调度被真正打乱；比 loom 规模大——不穷举只采样。失败落一个
  seed，CI 重放即复现；压测最大的痛"本地跑不出来"由此根治。

```rust
let q = Arc::new(Queue::default());
let start = Barrier::new(N + 1);
let (pushed, drained) = (AtomicUsize::new(0), AtomicUsize::new(0));
thread::scope(|s| {
    for _ in 0..N { let (q, b) = (q.clone(), &start);
        s.spawn(move || { b.wait();          // 屏障齐发：错过竞争窗口=白跑
            for _ in 0..10_000 { match fastrand::u8(..) % 4 {
                0 => { q.push(1); pushed.fetch_add(1, Relaxed); }
                1 => if q.pop().is_some() { drained.fetch_add(1, Relaxed); }
                _ => { q.len(); }            // 混入读操作增熵
        }}});}
    start.wait();
});
assert_eq!(q.len() + drained.load(Relaxed), pushed.load(Relaxed)); // 守恒
```

- 压测三要素常被漏：**屏障齐发**（错峰起跑错过竞争窗口）、**随机
  操作混合**（固定模式只测到你写下的那条路径）、**join 后校验
  全局不变量**（守恒/无重复/无丢失），而不是靠"没 panic 就过"。
- 弱内存兜底：x86 全绿不代表 ARM 没事——CI 挂一个 aarch64 runner
  或 qemu 跑同一套压测；线上并发 bug 的终极复现是缩小成 loom
  模型，做不到就退而求 shuttle 种子测试，绝不让它只活在工单里。

## 92. proptest/quickcheck 模糊测试纪律

- 性质测试 = "对任意输入，不变量成立"：序列化 roundtrip、排序结果
  = 排列且有序、**解析器对任意字节流不 panic**——最后一类是解析
  库的命门，一堆 CVE 的出身就是"构造性输入没被喂过"。
- proptest 三纪律：①策略**组合**出贴近真实的恶意输入（空/超长/
  含 NUL/非 UTF-8/边界尺寸的 oneof 混流），生成器只产合法输入 =
  制造覆盖假象；②失败种子**自动持久化**（regression 文件与测试
  源文件同目录）——**必须提交进仓库**，此后每次 `cargo test` 先
  重放历史失败，回归套件免费获得；③读 **shrinking 后的最小样本**
  定位真问题，别盯着原始随机汤发呆。

```rust
proptest! {
    #![proptest_config(ProptestConfig::with_cases(1024))]
    #[test]
    fn parse_never_panics(b in any::<Vec<u8>>()) { // 任意字节流
        let _ = Parser::new(&b).parse();           // 只断言不 panic
    } // 失败 → 种子落盘 → 提交 → 永久回归；CI 先重放旧种子再抽新
}
```

- 状态化场景上 `proptest-state-machine`（模型 vs 实现：随机指令
  序列后逐项对账，队列/缓存/状态机类 bug 的收割机）；quickcheck
  更轻（Arbitrary 派生 + 自动 shrink），原型期够用，工程期以
  proptest 的可控策略为准。
- 与 cargo-fuzz 互补不互斥：libFuzzer 吃覆盖率反馈、找得深但要
  持续跑；proptest 确定性、进 CI 门禁。分工：fuzz 负责"没人想到
  的输入"，proptest 负责"每个 PR 都重放已知的恶"。

## 93. Miri 在 CI 中的编排

- Miri 是**解释执行的 UB 雷达**：别名违规（Stacked/Tree Borrows，
  36 节）、越界、未对齐、UAF、未初始化读取、非法 UTF-8——在
  **执行过的路径**上逐一拦截（37 节）。代价 20-1000× 慢，所以
  编排原则只有一句：独立 nightly job + 精选测试面，混进常规 job
  拖慢全队 = 团队迟早把它删掉。
- 三件编排：`rustup +nightly component add miri`；只跑 unsafe 触碰
  面（`#[cfg(miri)]` 门控 + `miri_` 命名前缀过滤）；`-Zmiri-many-seeds
  =16` 让地址与熵随机化多轮重跑；再加 `-Zmiri-tree-borrows` 与
  默认 Stacked Borrows **两模型都跑**——写"交集正确"的 unsafe
  才是对两个未来都兼容。

```yaml
# CI：nightly 独立 job，与 stable 门禁解耦
miri:
  toolchain: nightly
  script: |
    cargo +nightly miri setup
    cargo +nightly miri test miri_ -Zmiri-many-seeds=16
    cargo +nightly miri test miri_ -Zmiri-tree-borrows
  allow_failure: false   # UB 是 bug 不是风格问题，宁可红门禁
```

- Miri 跑不了半个 syscall 世界：文件/网络/信号大多靠 shim 或直接
  报错——这正是只测 unsafe 内存面的第二层理由；`-Zmiri-disable-
  isolation` 可放行真文件系统，仅局部按需打开，别全局放行。
- 心法：**Miri 红 = 一定有 UB**（近乎零误报，零容忍）；Miri 绿 ≠
  无 UB（没执行的路径不查）——绿线保持怀疑，交织盲区交给 loom
  （90 节），随机盲区交给 proptest/压测（91-92 节）。

## 94. criterion 基准与统计噪声控制

- criterion 的事实模型：预热 + 多轮采样 + 统计分析（MAD、离群点
  分类、t 检验），回答"这次变化是否超出噪声"。**基准是每台机器
  自己的事**：baseline 存本机（`--save-baseline`/`--baseline`），
  跨机器比均值是玄学——CI 墙钟门禁必须固定 runner 规格。
- 噪声治理三板：`std::hint::black_box` 防 DCE（忘写它，基准会显示
  "0ns 的惊人优化"）；`iter_batched` 把 setup 摘出测量窗（分配
  不算分）；`noise_threshold(0.02)` 把 2% 内的变化判为噪声——
  否则风扇转速就能给你的 PR 红灯。

```rust
fn bench_parse(c: &mut Criterion) {
    c.sample_size(200);                    // 尾部敏感用例加采样
    c.bench_function("parse/4k", |b| {
        b.iter_batched(
            || black_box(fixture_4k()),    // setup：构造不计入
            |buf| Parser::parse(&buf),
            BatchSize::SmallInput,
        )
    });
}
criterion_group!(benches, bench_parse);
```

- **确定性门禁换 iai-callgrind**：valgrind 数指令与缓存事件，零
  噪声、机器无关、可 diff——适合"基准回归阻塞合并"；criterion
  的墙钟数据进 dashboard 给人看趋势。分工一句话：审 PR 的应该是
  确定性数字，不是统计学家。
- 反模式全家桶：debug 构建 benchmark（68 节：无效测量）、只看
  mean 不看离群点分类（尾部才是事故现场）、手撸 `Instant::
  elapsed` 单次计时。每一条都在真实项目里坑过人。

## 95. 测试金字塔与 #[cfg(test)] 的边界

- 内联 `#[cfg(test)] mod tests` 的边界：能测私有项（白盒），但
  **binary-only crate 里够不着 main 之外的逻辑**——`tests/` 集成
  测试链接的是 lib target，bin 链不上。这逼出 Rust 的经典结构
  决策：`main.rs` 只留 `fn main() { run() }`，业务全部进 lib
  target——否则核心代码根本不可测，这不是风格是可测性底线。
- `tests/` 每个文件 = **一个独立 crate + 一次完整链接**：8 个
  文件就是 8 份测试二进制的编译开销（78 节 timings 图上的常驻
  税）。共享辅助放 `tests/common/mod.rs`（mod 目录不进测试
  清单）；写成 `tests/common.rs` 会被当第 9 个测试 crate 多链
  接一次——这个坑十年了还有人踩。

```text
tests/
  common/mod.rs      # ✓ 共享工具：不产生测试目标
  common.rs          # ✗ 反模式：多编译多链接一个空测试 crate
  api_roundtrip.rs   # 每个 = 独立二进制；按域聚合文件数
分层执行：单元(ms) → 集成(s) → 夜间 loom/Miri/压测(分钟)
          （90-94 节工具全在第三层，PR 门禁只跑前两层）
```

- 快慢分层的全部意义：**快的测试先红**。CI 预算永远不够，慢
  测试只配守夜；PR 门禁 5 分钟红线内塞进单测 + 关键集成，其余
  进 nightly job 并统计失败趋势。
- 别漏 `--release` 抽测：`debug_assert!` 只在 debug 拦人，溢出在
  两个模式下行为不同（debug panic / release 回绕）——只在 debug
  跑的测试矩阵等于只测了一半的编译器行为。

## 96. 文档测试（doctest）可维护性陷阱

- 每个 doctest = **一个独立二进制**：完整编译 + 链接 crate + 运行。
  200 条 doctest 的库在 CI 上就是几分钟的纯编译税，且不随常规
  增量缓存——它是文档的体面，也是 CI 账单上最贵的一行。
- 成本分档：纯语法展示用 ```text/```ignore 零成本；可编译不必
  运行用 ```no_run；`# ` 前缀行对读者隐藏 setup 样板（use、
  构造），文档只亮真正想讲的那一句。

````
```rust
/// 计数器从配置创建。
///
/// ```
/// # use mycrate::{Counter, Config};   // 隐藏行：读者不读，编译器要
/// # let cfg = Config::max(4);
/// let c = Counter::from_config(cfg);
/// assert_eq!(c.get(), 4);
/// ```
pub fn from_config(cfg: Config) -> Self { /* ... */ }
```
````

- CI 策略二选一：夜间 job 全量 `cargo test --doc`；或 PR 只跑
  `cargo test --doc --lib`。doctest 挂在文档上，文档坏了 = API
  契约坏了——它该挡合并，但挡在夜里，不是每一次 push。
- 陷阱四连：①doctest 里碰网络/时钟/睡眠 = 最恶劣的 flaky（文档
  测试 sleep 2 秒等超时，谁维护谁发疯）；②bin target 的 doctest
  根本不会被执行（95 节同款盲区，关键示例别写在 bin 文档里）；
  ③未使用告警刷屏 → `fn main() -> Result<()>` + `?` 惯用法既优雅
  又消警告；④要演示完整程序时用 `#![doc(test(no_crate_inject))]`
  关掉自动注入的 `extern crate`。

## 97. panic 跨 FFI 边界：abort 语义与 extern "C-unwind"

- 1.81 起规则定死：panic 展开逃出 `extern "C"` 边界 = **立即
  abort**（此前是实现定义行为，更早是 UB）。`extern "C-unwind"`
  是显式放行：允许展开跨过 C 帧——前提是**调用方真的接得住**
  （C++ 侧 catch，或 C 代码明确懂 landing pad）。默认留 "C"，
  让 abort 替你守住不可逃逸的边界，比任何注释都可靠。

```rust
#[unsafe(no_mangle)]                       // 2024 写法（86 节）
pub extern "C" fn process(input: *const u8, len: usize) -> i32 {
    let r = std::panic::catch_unwind(|| {  // 边界防御：一层，套娃无意义
        let s = unsafe { checked_slice(input, len)? }; // 102 节：校验先行
        do_work(s)                         // 业务代码随意 panic
    });
    match r { Ok(_) => 0, Err(_) => -1 }   // panic → 错误码，C 有得选
}
```

- 工程默认：**导出的每个 C ABI 入口都包 catch_unwind**（83 节
  AssertUnwindSafe 义务），panic 翻译成错误码/NULL——C 调用方
  面对 abort 没有任何补救余地；注册给 C 的回调函数体同样是
  "入口"（100 节），防御同构。
- C-unwind 的合法场景：Rust panic 穿过 C 帧到达 C++ 的 catch——
  音频/游戏插件桥的经典需求。两条铁则：**longjmp 永远不许跨过
  Rust 帧**（跳过析构 = UB，与展开是两码事）；两侧 ABI 声明必须
  一致——"C" 与 "C-unwind" 混用是未定义的链接期错误。
- 双重 panic = abort（7 节）：catch_unwind 闭包里再 panic 只会
  炸得更响。防御层是**一层**；想要报告，就在 83 节的 panic hook
  里做，别在防御代码里再写可能 panic 的逻辑。

## 98. #[repr(C)] 跨界陷阱：字段顺序、padding 与类型对不上

- repr(C) = 字段按声明序 + 自然对齐 padding；默认 repr(Rust)
  会重排字段省空间——跨界结构体忘写 repr(C)，x86 上"碰巧"能跑
  （布局恰好一致），换平台或加字段立刻错位。最阴险的是**错位
  不 crash、只算错**：读出的值是合法垃圾，下游统计悄悄全错。

```rust
#[repr(C)]
pub struct Header {
    pub magic:   u32,     // 与 C 头逐字段对齐；顺序即内存，别"美化"
    pub version: u16,
    pub flags:   u16,
}
const _: () = assert!(size_of::<Header>() == 8); // 布局钉进类型系统
// C 的 long：Win64=4 字节(LLP64)、Linux=8(LP64)——ABI 结构体
// 一律用 libc::c_long 或定宽 i32/i64，别用 isize 语义去猜
```

- 类型错位全家桶：C `char` 1 字节 ≠ Rust `char`（4 字节且要求
  合法标量值）；C 的 `_Bool` 写进非 0/1 的值、Rust 读成 bool =
  UB 级陷阱（bool 只许两态是语言保证）；C enum 的大小与符号
  实现定义——别用 Rust enum 镜像，ABI 层用 `u32` + 常量 +
  转换函数收口。
- 安全网两条：`static_assertions::assert_eq_size!`/编译期 assert
  把布局锁死（结构体一改就红）；或让 bindgen 从 C 头生成（101
  节）——手抄结构体 = 给未来埋雷，抄错没人审。
- 唯一值得背的例外：`Option<extern "C" fn(..)>` 有 niche 保证——
  与裸指针同宽、None=NULL，可安全镜像"可空函数指针"。但 FFI
  结构体里的 `Option<&T>`/`&T` 是越界的引用承诺（非空 + 别名，
  99 节），一律换裸指针。

## 99. CString/CStr 字符串跨界约定

- CString：owned + NUL 结尾堆分配。`CString::new` **拒绝内部
  NUL**——这不是缺陷是事实陈述：C 字符串到 `\0` 为止，含 `\0`
  的数据本质上不能走 C 字符串协议。三条出路：拒绝（Result 透传
  给调用方）、转义/替换、改 `ptr+len` 协议。写 unwrap 洗掉它 =
  把错误静默截断成数据损坏。

```rust
let c = CString::new(name)?;      // 策略①：拒绝，错误可见
call_c(c.as_ptr());               // 借出：生命周期跟 CStr，本调用内安全
let raw = c.into_raw();           // 交出所有权：Rust 已"遗忘"这块内存
unsafe { foreign_keeps(raw); }
let _ = unsafe { CString::from_raw(raw) }; // 回收必须原路原指针返回
```

- `into_raw`/`from_raw` 是**唯一合法通道对**：C 侧用 libc free
  释放 Rust 的分配、或把指针 +1 传回来，都是 UB（分配器混用 +
  释放错块）。协议写进头文件注释三问：谁分配？谁释放？可为
  NULL？——跨语言内存契约没有"显然"二字。
- 只读入参优先 `&CStr` 零分配（字面量用 `c"..."`，1.77+；字节
  切片用 `CStr::from_bytes_with_nul`）；"C 侧保存过夜"的场景才
  升级 owned CString。方向反过来（C 给 Rust）永远是
  `CStr::from_ptr` + 借用期审问：这块内存活多久？
- Windows 线：Win32 wide API 要 UTF-16 + NUL 终止；从 C 进来的
  字节**永远不假定 UTF-8**——边界处校验或显式 lossy，一次处理，
  内层代码保持 String 纯净。编码假设散落在业务层 = 排查地狱。

## 100. 回调跨 FFI：panic、reentrancy 与 Send 义务

- C 在**它自己的线程**上调你的回调，类型系统看不见这个事实：
  闭包状态 `Box::into_raw` 交给 C、回调里 from_raw 取回——从此
  **Send 是你手动背书的义务**。安全包装层收口：`F: Fn(..) +
  Send + 'static` 写进 Rust 侧签名，把"哪些状态能过线"变成
  可审计的类型事实，而不是注释里的祈祷。

```rust
// 队列转发模式：回调最小化，逻辑回自己的线程/运行时
extern "C" fn on_event(ev: Event, ctx: *mut c_void) {
    let r = std::panic::catch_unwind(|| {      // 97 节：panic 不外逃
        let q = unsafe { &*(ctx as *const mpsc::Sender<Event>) };
        if q.try_send(ev).is_err() {           // 非阻塞：满则丢弃
            DROPPED.fetch_add(1, Relaxed);     // 但必须记账（见下）
        }
    });
    let _ = r;
}
```

- 回调铁律三连：**快进快出**——音频/渲染回调里分配、加锁、IO =
  卡顿或死锁，只许 try_send 这类常量操作；**重入假设**——事件
  循环/信号可能在你持状态时重入回调，回调不碰共享可变态，架构
  级解耦到 67 节 actor；**panic 不外逃**——abort 语义见 97 节，
  防御层就一层。
- 析构线程错配：C 在任意线程调你注册的 destructor → drop glue
  跑在 C 线程上，内部若含 Rc、tokio 资源等非 Send 成员，轻则
  panic 重则 UB。协议写死"可从任意线程调用"（并真的做到），或
  把析构转发到归属队列/线程——**文档即契约**，立在头文件注释里。
- 事件丢失要有账：try_send 满了丢包不可耻，丢得没人数才可耻——
  计数器 + 慢消费告警。"图表上永远少几个点"这类事故的惯犯就是
  没记账的回调队列。

## 101. 绑定生成决策：cbindgen、uniffi 与 bindgen

- 三向决策按"方向"定工具：**导出 C ABI** → cbindgen（Rust 生成
  header.h；游戏/插件/嵌入式首选，零运行时魔法）；**导出高层
  绑定** → uniffi（UDL 或 proc-macro 定义接口，生成 Kotlin/
  Swift/Python——Android/iOS 业务模块的正解）；**消费 C** →
  bindgen（头文件 → unsafe Rust 声明）。别拿 uniffi 做插件 ABI，
  也别拿 cbindgen 伺候 Kotlin——错位的工具比手写更糟。

```rust
// uniffi：直接写 Rust，宏生成脚手架 + Kotlin/Swift 侧
#[derive(uniffi::Record)]
pub struct Config { pub timeout_ms: u64, pub retries: u8 }

#[derive(thiserror::Error, uniffi::Error)] // 错误→异常（79 节边界成立）
pub enum StoreError { #[error("io: {0}")] Io(String) }

#[uniffi::export]
pub fn open(path: String, cfg: Config) -> Result<Store, StoreError> {
    Store::open(&path, cfg).map_err(|e| StoreError::Io(e.to_string()))
}
```

- cbindgen 纪律：公开面只放 `#[repr(C)]` + 原始类型 + 不透明
  指针；`String/Vec<T>/Box<T>` 一律翻译成显式 ABI 类型（`{ptr,
  len, cap}` 三元组 + 成对的所有权函数）；生成头文件**提交进
  仓库**、用 xtask 再生成，不走 build.rs——消费者构建时不该
  替你跑生成器（105 节成本转嫁）。
- bindgen 纪律：allowlist 只留用到的符号（全量生成 = 几千行
  没人审的 unsafe 声明）；`rerun-if-changed` 挂上头文件（105
  节经典漏配：改头不重生，测试全绿线上崩）；产物全是 unsafe
  裸声明，**安全包装层必须手写**——bindgen 不懂线程语义与
  生命周期（100 节义务在你的包装层里兑现）。
- 无论哪条路，CI 挂 ABI 冒烟：cbindgen 产物用 C fixture 编译
  一次、uniffi 产物跑 foreign 侧生成测试——生成器与工具链
  升级是半年度例行事故源。

## 102. FFI 安全边界：不可信输入与 catch_unwind 包装

- `unsafe extern` 块里标 `safe fn`（86 节）= 对全世界宣布"任何
  C 代码以**任意参数**、任意线程调用它都不会 UB"——这是安全
  Rust 级承诺：null、悬垂、错误 len、并发调用全在票面内。逐条
  过刑再标；审不过就老实留 unsafe，让调用方继续背义务。
- 边界防御次序固定：**校验先行**（null、len 上限、编码检查）
  → catch_unwind 兜底 → 错误码/NULL 出口。导出函数里出现
  `unwrap` = 给 C 的 abort 按钮（97 节语义），review 一票否决。

```rust
/// 0=ok, -1=null, -2=bad input, -3=panic。语义整表写进头文件。
#[unsafe(no_mangle)]
pub extern "C" fn feed(h: *mut Feed, buf: *const u8, len: u32) -> i32 {
    let Some(h) = (unsafe { h.as_ref() }) else { return -1; };  // null 先查
    let Ok(bytes) = checked_slice(buf, len, 1 << 20) else { return -2; };
    match std::panic::catch_unwind(|| h.push(bytes)) {        // 83 节纪律
        Ok(true) => 0, Ok(false) => -2, Err(_) => -3,         // panic=代码
    }
}
```

- 不透明类型协议：Rust 导出空体 `pub struct Feed;`，C 只见
  `Feed*`；构造 `Box::into_raw`、析构 `Box::from_raw`——**成对
  出现在同一段头文件注释里**：谁 new 谁 free、哪个线程可调
  （100 节）。空体类型让 C 无法越界构造你的类型，是边界上最
  便宜的一道墙。
- 缓冲区语义四选一必须写死：C 分配 C 释放 / Rust 出三元组 +
  显式 free 函数 / 小数据直接复制进 Vec / 零拷借出 + 明示
  生命周期。"借用还是拥有"在 FFI 上没有默认答案——没写的
  协议，迟早有人按错的方向猜。
- Rust 内部安全包装同样设卡：`from_raw_parts` 的 align/len 证明、
  `from_utf8` 校验、SAFETY 注释（35 节）——Miri 测不到真 FFI
  （93 节盲区），这里的证明注释就是唯一防线。

## 103. feature 统一（unification）的坑与对冲

- Cargo 每个 crate 只编译**一份**，feature 取全依赖图**并集**
  （resolver v2 起按 target 分开、v3 叠 MSRV 感知，88 节）。三条
  推论：你在 A 处 `default-features = false`，B 处有人开了
  default——并集把你打回原形；"轻量依赖"的神话经常破灭在依赖
  图第三层；你没法"部分关闭"，只能全图一起谈。

```console
$ cargo tree -e features -i tokio::full   # 谁把 full 开进来的？逆向指认
$ cargo tree -f "{p} {f}" | rg ^tokio     # 每个 crate 最终解析出的 feature
$ cargo hack --each-feature check         # 每个 feature 单开可编译
$ cargo hack --feature-powerset --depth 2 # 小 crate 两两组合全检
```

- 典型事故三型：**体积膨胀**——某传递依赖开了 TLS/压缩后门，
  二进制 +2MB；**行为漂移**——同一 crate 因并集在不同构建里
  API 可用性不同，"我本地没有这个方法"的跨团队悬案；**编译
  时间**——union 触发重型代码路径，78 节 timings 上多出一坨。
- 对冲四招：全 workspace `default-features = false` + 显式加
  （107 节集中管理）；optional 依赖用 `dep:name` 精确语法，关掉
  "optional 自动暴露同名 feature"的隐式行为；CI 跑
  `cargo hack --each-feature`——feature 门控漏写（cfg 忘了查
  feature）只有单开测试才现形；`-e features -i` 指认进 PR 模板，
  谁加 feature 谁解释。
- 心法：feature 是**加法协议**——永远假设别人会把你没要的东西
  并进来。任何"关掉 X 才小/才安全"的承诺，要写成 CI 断言，
  不能写成文档段落。

## 104. 依赖审计：cargo-deny、cargo-audit 与供应链

- 每个 crate = 你构建机器上**以完整权限执行的别人代码**（105
  节）。底线工具链两件：**cargo-audit**——lockfile 对照 RustSec
  公告库（漏洞/未维护/yanked），`cargo audit fix` 给修复建议；
  **cargo-deny**——四类检查一网打尽：advisories（安全公告）、
  licenses（白名单——商业闭源里混进 GPL 是合规事故）、bans
  （重复版本与 wildcard 依赖）、sources（只许 crates.io——
  git/路径依赖进生产 = 不可复现不可审计）。

```toml
# deny.toml 摘录：门禁语义，CI 里 cargo deny check -D 全开
[advisories]
yanked = "deny"
[licenses]
allow = ["MIT", "Apache-2.0", "BSD-3-Clause", "Unicode-3.0"]
[bans]
multiple-versions = "warn"       # syn 五个版本：是噪音也是信号
[sources]
unknown-registry = "deny"        # 只信 crates.io
```

- 编排节奏：PR 门禁跑 `cargo deny check`（秒级、读 lock）；每周
  定时任务刷新公告库跑 audit；advisory 到达按**事故**处理——
  升级或 `--precise` 回滚（106 节），"先忽略等上游"必须带
  时限与责任人，无限期忽略等于没有防线。
- 加固梯队：cargo-vet（人工审计链 + 可信审计者委托——生态共享
  审计结果，流程重保证强）；cargo-auditable（把依赖清单嵌进
  二进制——"这个版本里有没有那个 crate"从事故响应考古变成
  一条查询）；SBOM（cyclonedx 导出）应付交付侧合规。
- 引入新依赖前的手动四查：维护活跃度/发布节奏、unsafe 面积、
  build.rs 干不干净、feature 克不克制——**加依赖是架构决策**，
  不是 `cargo add` 一下的事。

## 105. build.rs 的安全风险与最小化

- build.rs 在**每个依赖你的人的机器上以完整权限执行**：读写
  文件、网络、环境变量，无沙箱。供应链视角它是最贵的一行配置
  ——恶意 crate 的经典载荷就藏在 build.rs（104 节 sources 检查
  防的就是这个）。所以第一问永远是：**真的需要构建期代码吗？**
- 最小化三原则：能预生成的东西直接提交生成物（cbindgen 走
  xtask，101 节）——消费者零成本零风险；必须跑（bindgen、git
  hash、环境探测）则 `build-dependencies` 最小且可审计——在
  build-deps 里塞 syn/reqwest = 给供应链开大门还附赠编译时间；
  产物只写 `$OUT_DIR`，消费走 `include!(concat!(env!("OUT_DIR"),
  "/gen.rs"))`，绝不污染源码树。

```rust
// build.rs：一旦发出任何 rerun-if，就替换掉默认的"包内全量重跑"
fn main() {
    println!("cargo:rerun-if-changed=wrapper.h");  // 每个输入都要列
    println!("cargo:rerun-if-changed=build.rs");   // 别忘自己
    println!("cargo:rerun-if-env-changed=TARGET"); // 环境输入成对声明
    let b = bindgen::Builder::default().header("wrapper.h");
    b.generate().unwrap().write_to_file(out_dir.join("bindings.rs"));
}
```

- 两大经典事故：**漏挂 rerun-if-changed**——改了 .h 没重生，
  测试全绿、线上 FFI 崩，最后发现二进制里是旧布局（98 节的
  `assert_eq_size` 在这里救过命）；**默认重跑风暴**——一个字
  都不发的 build.rs 对包内任何改动全量重跑，CI 时间的隐形税
  （78 节 timings 看得见）。
- env 驱动的生成必须 `rerun-if-env-changed` 成对声明，读
  `CARGO_CFG_*` 不挂 rerun = 跨 target 缓存污染。第三方 build.rs
  要审计：`cargo metadata` 能列出谁在跑 build.rs、依赖什么——
  每一份都该有人看过一眼。

## 106. Cargo.lock 与 cargo update 纪律

- 铁律：**产出二进制的 workspace 必交 lock**——CI、发布、全员
  同一张依赖图，可复现是发布底线。纯库也建议随 workspace 交
  （不影响下游自己的解析，但让库自身 CI 可复现）。判断只剩
  一条：这个 workspace 有没有任何东西进生产？有 = 交。
- `cargo update` 的粒度就是事故响应的速度：无参 = 全图在 semver
  兼容内推进；`-p name` = 单点；**`--precise x.y.z` = 精确钉版**。
  公告/坏版本到达时的标准动作：`cargo update -p ring --precise
  0.17.12`——一行回滚、一次提交、一个可 review 的 lock diff。

```console
$ cargo update -p ring --precise 0.17.12  # 事故响应：单点精准回滚
$ cargo tree --duplicates                  # 多版本共存清单：谁拖旧版
$ git diff Cargo.lock | rg '^[-+](name|version)'  # lock diff 必读
# renovate：minor/patch 周批量 PR；major 单独 PR + 人工 review
```

- yanked 语义读准：lock 里已有的 yanked 版本**仍可构建**（registry
  尊重 lockfile），新解析才会拒绝——所以"发布后撤回"烧不到已
  锁死的构建；但下一次 `cargo update` 会自动跳过 yanked 并把
  你的 lock 悄悄推进一版，这个 diff 同样需要人眼。
- 纪律三连：lock diff 进 PR 模板（依赖变化必须有人过）；renovate
  分层节奏与 104 节公告窗口对齐；冲突合并手法——别手解 lock 的
  hunk，删掉冲突段跑 `cargo update -p <碰过的 crate>` 重生成，
  手解 lock 造成的幽灵不一致比冲突本身难查十倍。

## 107. workspace 依赖管理与 workspace-hack

- `workspace.dependencies` 在根清单集中声明版本 + feature，成员
  用 `workspace = true` 继承：30 个 crate 不再有版本漂移；feature
  集中托管（默认关、显式开）直接对冲 103 节的并集事故。
  `workspace.package/lints` 同理统一 license、rust-version、
  clippy 纪律——**一处改动全员生效**是 workspace 的全部价值。

```toml
[workspace.dependencies]
tokio = { version = "1", default-features = false,
          features = ["rt", "macros", "net"] }
[workspace.package]
rust-version = "1.79"          # 88 节：MSRV 唯一事实源
[workspace.lints.rust]
unsafe_code = "forbid"         # 默认禁 unsafe；需要的 crate 局部放开
[workspace.lints.clippy]
undocumented_unsafe_blocks = "deny"   # 35 节纪律进配置
```

- workspace-hack 解的是另一个问题：不同成员对同一依赖启用不同
  feature 组合 → 在成员间切换构建时**反复重编**（tokio/syn/clap
  的 feature 子集爆炸，增量缓存作废）。cargo-hakari 计算全图
  feature 并集、生成一个 hack crate 让所有成员依赖——每次构建
  看到同一 feature 集，缓存全 workspace 共享，切换不重编。
- hakari 三注意：它**只省编译时间不省体积**（并集还是那个并集，
  103 节的瘦身另想办法）；生成物提交进仓库，依赖变更后 `cargo
  hakari manage` 重新生成（忘跑 = 过期告警）；小 workspace
  （五个 crate 以下）不值得上——先看 `--timings`（78 节）再决定。
- 结构纪律：共享 target 目录让产物跨成员复用；公共 dev-deps
  （proptest、criterion）同样进 workspace.dependencies；拆分
  粒度跟编译单元与团队边界走——为了并行编译而拆是正收益，
  为了"看起来整洁"而拆是负收益。

## 108. 生产反模式速查清单

- 这张清单是 109-111 节事故的**病因表**：每一条都在真实
  postmortem 里出现过。review 时逐行对照，命中即叫停；真正的
  根因几乎都不是"不知道"，是"当时赶时间"——所以用清单而不是
  记忆兜底。

```text
01 clone 止借用    借用冲突没吃透就 clone 大结构；先审所有权再放行
02 unwrap 洗地     库代码里 unwrap/expect = panic 边界外溢（83 节）
03 Deref 继承      用 Deref 模拟 OOP 层级 → 方法解析迷宫（32 节）
04 Box<dyn Error>  库公共 API 用它 = 下游不可 match（79 节）
05 持锁 await      std guard 跨 await 点 → 毒化/死锁（62 节）
06 unbounded 通道  无背压 = 生产者 OOM 定时炸弹（60 节）
07 裸 spawn detach 任务蒸发：panic 与结果无人知晓（58 节）
08 Arc 环          缓存回指持有者，析构永不到来（29、110 节）
09 测试里 sleep    用延时代替同步，flaky 制造厂（66 节）
10 packed 取引用   repr(packed) 字段上取 & = UB 现行犯（34 节）
```

- 榜外三连同样高频：`let _ = tx.send(..)` 吞发送错误（111 节）；
  把 feature 并集当局部事实用（103 节）；edition/工具链升级后
  不跑 compatibility lint（85 节）。
- 用法：贴进 PR 模板与新人 onboarding。命中不是羞辱是流程——
  反模式清单存在的意义，就是让 review 有现成的、不带情绪的
  依据可引。

## 109. 事故复盘 I：async 饿死与取消丢失

- 故事 A（饿死）：上线后 p99 从 40ms 缓涨到 2s，CPU 只用了 30%。
  取证：tokio-console 里同一 worker 上的任务 poll 时间全体异常、
  单个任务 self time 巨大；samply 火焰图单核 100% 落在
  `getaddrinfo`——依赖深处的同步 DNS（57 节三形态之②）。
  修复：`spawn_blocking` 包住或换 async 解析器；回归：staging
  常驻 tokio-console + "任务 self time > 1s"告警。
- 故事 B（取消丢失）：`timeout(30s)` 偶发后，下游永远收不到这条
  消息。根因：select 分支里的 `send().await` 被取消时**许可已
  扣、消息未发**——半途状态无人回滚（51 节判据）。修复：
  permit API 把"等容量"与"发送"拆开；消费侧幂等键兜底未送达。

```rust
// 取消丢失的解剖与修复（51 节判据落地）
let permit = tx.reserve().await?;  // 取消点①：等容量，drop 安全
let msg = build()?;                // 取消点②：纯计算，可安全重来
permit.send(msg);                  // 非异步：发出即完成，无中间态
// 反例：tx.send(build()?).await —— build 后被取消 = 消息进黑洞，
// 下游按序消费的管道从此永远缺一条
```

- 复盘模板四栏：时间线（谁先发现？为什么不是告警先发现？）/
  根因链（代码层 + 流程层各至少一条）/ 修复（代码 + 回归测试 +
  告警）/ 清单增量（108 节追加一行）。**没有回归测试的修复视为
  未修复**——超时交织用 `start_paused` 确定性重放（66 节）。
- 延迟直方图是这类事故唯一诚实的仪表：平均值总是最先"恢复
  正常"，p99.9 才暴露取消丢失——告警挂尾延迟，永远别挂均值。

## 110. 事故复盘 II：泄漏、循环引用与 drop 顺序

- 故事 A（泄漏）：RSS 每天缓涨 200MB，无 crash 无告警。取证
  路径先分型：全局分配计数/`memory-stats` 区分"真泄漏"与"缓存
  增长"；tokio-console 任务数爬升指向未收割的 detach 任务
  （58 节）；Drop 里的计数原子指向 **Arc 环**（29 节：缓存
  entry 回指持有者，强计数永不归零）。修复：回指改 Weak；
  回归：构建→drop→断言存活计数归零，写进单元测试永续运行。

- 故事 B（drop 顺序）：停机瞬间日志爆发 "channel closed" 刷屏。
  根因：字段声明序 = 析构序（7 节）——结构体里 channel 字段与
  任务收管字段的析构顺序，和 59 节停机四步的正确顺序不一致；
  drop 顺序就是停机顺序，靠作用域魔法管理停机必然踩雷。

```rust
// Drop 顺序即锁序：字段声明序 = 析构序，可能死锁的排最后
struct Session {
    tasks: JoinSet<()>,                  // 先析构：abort 全部（58 节）
    inflight: Mutex<HashMap<Id, Stat>>,  // 再析构：还能安全收账
    log: Logger,                         // 最后：flush 时上面已清空
}
// 死锁形态：A 字段的 drop 想拿 B 字段的锁，B 的 drop 想拿 A 的
// ——解法：显式 shutdown() 把"析构"变"调用"，顺序成文可测
```

- 泄漏与死锁同根：都把生命周期管理**外包给了直觉**。工程解法
  一套打尽：资源持有图写进设计文档；`Arc::strong_count` 采样进
  metrics；停机路径显式化成函数而非作用域魔法；Drop 里禁一切
  可能阻塞的操作（析构路径无锁、无 IO）。
- 取证工具链：换 jemalloc 顺手拿堆 profiling（70 节）、heaptrack
  抓分配点、计数器断言钉回归。最贵的从来不是修，是**先复现**
  ——能进 CI 的泄漏测试（Weak 升级断言 + 存活计数断言）才算闭环。

## 111. 事故复盘 III：错误链断裂与吞错

- 故事：某报表三周静默输出空数据。考古：最底层的 `connection
  refused` 在第 4 层被 `map_err(|_| Internal)` 抹掉类型，第 6 层
  `unwrap_or_default()` 抹掉存在本身——**错误链在两处被斩断**，
  日志里只剩一条没人订阅的 warn。根因不是没打日志，是错误的
  **类型**死在了中间层。

```rust
// 双轨制：全局日志管可见性，Result 管正确性，各司其职
fn render(&self) -> Result<Report, RenderError> {   // typed（80 节）
    let data = self.fetch().map_err(RenderError::Fetch)?; // 保住类型
    // 反面教材博物馆（同一事故的三个共犯）：
    // let data = self.fetch().unwrap_or_default();    // ① 存在性吞没
    // let data = self.fetch().map_err(|_| Internal)?; // ② 类型斩断
    // let _ = tx.send(data);                          // ③ 发送吞错
    Ok(Report::from(data))
}
// 边界处一次成型：tracing::error!(?err) —— ? 才走 Debug 带全链
```

- anyhow 的 Display **只打最外层**——`tracing::error!("{}", err)`
  落到日志里只剩 "internal error"，source 链全丢；要用 `{:?}`
  或 tracing 的 `?err` sigil 才输出完整链（81 节 downcast 陷阱
  同源）。这一个小差值养活过无数起事故的排查。
- 吞错惯犯名单进 clippy/CI：`let _ = <must_use>`（restriction
  lint `let_underscore_must_use`）、空捕获的 `.ok()`、
  `unwrap_or_default` 一律 review 高亮；`map_err(|_| X)` 合法的
  场景是**刻意的类型收窄**——那就写 `map_err(|e| { debug!(?e);
  X })` 留下证据。
- 纪律三问：这个错误谁能处理？他能拿到类型吗？拿不到时日志里
  还有链吗？三问全过才许写 `?`。79-82 节是预防面，本节是它们
  的验尸报告——postmortem 每多一起，这三问就该多背一次锅。

## 112. Rust 专家代码评审清单

- 七板斧 checklist——大 PR 与发布前逐条对照，是全篇 KB 的收束
  点。每条都问"**证明在哪里**"：答案必须是测试或 SAFETY 注释，
  不是"我觉得"。

```text
□ 取消安全    每个 .await 可被 drop：中间态可回滚？（50-53 节）
               recv 安全 / send 不安全已审？permit 已拆分等待与发送？
□ 阻塞审计    async 上无长 CPU 循环 / 同步 IO / 持锁 await？（57、62 节）
               spawn_blocking 池满有压流？压测 p99 有预算说明？
□ unsafe 证明 每个 unsafe 块有 SAFETY 注释 + 不变量？（35 节）
               Miri 双模型、loom 穷举已进 CI？（90、93 节）
□ 锁与 await  std 锁临界区不跨 await？锁序成文？字段 drop 顺序无锁环？（7、62、110 节）
□ 错误映射    库 API typed / 应用 anyhow 各就各位？（79 节）
               source 链完整？无 unwrap_or_default、map_err(|_|) 吞错？（111 节）
□ 版本纪律    rust-version / edition / toolchain 三处同源？（85、88-89 节）
               lock diff 与 feature 变化已 review？（103、106 节）
□ 测试矩阵    单元 / 集成 / 夜间分层明确？（95 节）
               proptest 种子已提交？基准有 baseline 或已换 iai？（92、94 节）
```

- 评审的元规则：**先看测试证据，再看实现**——审四百行 diff 的
  正确性靠不住，审"这四百行配了哪四十行测试"才可靠。取消安全
  与阻塞审计是 async 代码的一票否决项，其余按改动权重裁剪。
- 输出惯例：每条意见带 KB 节号锚点（如"62 节"），让作者有据可
  查、有例可抄；清单本身每季度按 109-111 节的事故增量修订——
  **它不是规范，是活着的 postmortem 沉淀**。
