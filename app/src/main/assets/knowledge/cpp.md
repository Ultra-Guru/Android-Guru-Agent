<!-- kb: id=cpp | role=builtin_coding_cpp | title=C/C++ 专家知识库 | version=1 -->
# C/C++ 专家知识库

## 必看内容

- **版本策略按编译器不按标准年份**：新项目 `-std=c++20` 起步（GCC 12+/Clang 15+/
  MSVC VS2022 全量可用），C++23 特性靠 feature-test 宏逐个放行，C++26 反射/契约
  只做技术预研——GCC 15 起才有反射实现，MSVC 尚无（1-3 节）。
- **UB 不是"可能崩溃"，是"编译器有权删掉你的代码"**：release 行为异常先怀疑
  UB、再怀疑硬件；sanitizers 矩阵进 CI 是唯一防线——ASan+UBSan 常规跑、
  TSan 每夜跑并发用例、MSan 只给解析器（6、41 节）。
- **data race 即 UB，volatile 不救场**：编译器默认单线程假设，能把
  `while (!stop)` 里的读提到循环外；原子默认 `seq_cst`，降级必须测量+证明
  （13-19 节）。
- **移动三禁**：`return 局部变量` 禁加 `std::move`（杀 NRVO）；`const` 对象
  move 会静默退化成拷贝；转发引用禁止二次 forward（第二次偷到的是空壳）
  （26-32 节）。
- **拷贝省略是 C++17 的语义保证**：prvalue 返回零拷贝零移动，删光拷贝/移动
  构造的类型照样能按值返回——"性能优化"级的 move 有时是负优化（26-27 节）。
- **shared_ptr 的成本在引用计数本身**：增减是原子操作，热路径传
  `const shared_ptr&` 观察、只在实际延长生命周期时才按值拷贝；make_shared
  单分配的代价是 weak_ptr 活着内存就不还（43-45 节）。
- **enable_shared_from_this 有初始化窗口**：构造函数里调用必抛
  `bad_weak_ptr`；嵌套成员（Outer 里装 Inner）永远不会被挂钩——只能在
  最外层对象上生效（46 节）。
- **concepts 优先于 SFINAE，但同名 concept 才有 subsumption**：`requires A && B`
  的布尔拼接跨写法不蕴含；迁移期用宏门控双写（50-53 节）。
- **协程帧默认在堆上**：参数在首个挂起点前拷入帧，跨挂起点引用外部参数
  即悬垂；HALO 省略只在调用点内联成功时发生（62-63 节，下半场）。
- **编译时间先砍头文件再谈 modules**：收益顺序是删 include > unity/PCH >
  ccache > modules；modules 现实约束：CMake 3.28+ 且仅 Ninja/VS 生成器、
  BMI 永不分发（7-10 节）。
- **ABI 也是 API**：inline 函数体、导出符号、STL 类型过 so 边界都动不得；
  对外接口 pimpl + opaque，是唯一能承诺二进制兼容的形态（88-95 节）。
- **一切优化先测量**：perf c2c 定伪共享、ClangBuildAnalyzer 定编译时间、
  perf/valgrind 定分配——没拿到数字之前写的是想象（21、10、78 节）。

## 快速索引

| 想找什么 | 去哪一节 |
| --- | --- |
| 17/20/23/26 该钉哪个标准 | 1. 标准版本决策模型：17/20/23/26 生产矩阵 |
| C++26 反射什么时候能用上 | 2. C++26 前瞻：反射（P2996）与契约（P2900）落地时间轴 |
| C++23 哪些特性已经能上生产 | 3. C++23 生产就绪特性与采用顺序 |
| 同代码三个编译器行为不一致 | 4. 编译器矩阵：GCC/Clang/MSVC 差异与跨平台纪律 |
| 警告开到什么强度合适 | 5. 编译警告战斗集：-Wall -Wextra 之外的高价值警告 |
| sanitizer 各自开销与 CI 怎么排 | 6. sanitizer 四件套：成本模型与生产部署定位 |
| modules 到底能不能上、收益多大 | 7. C++20 modules 采用现实：BMI 工程与生成器约束 |
| 头文件代码怎么渐进迁到 modules | 8. modules 迁移期策略：全局模块片段与头文件单元 |
| CMake 写成现代 target 化的纪律 | 9. CMake target 化纪律：使用要求传播与 presets |
| 编译太慢从哪里下手 | 10. 编译时间战争：unity build、PCH 与 ccache 取舍 |
| vcpkg/Conan 选型与系统库坑 | 11. 依赖管理现实：vcpkg/Conan 与系统库陷阱 |
| import std; 现在能不能用 | 12. import std; 与包管理合流趋势 |
| happens-before 到底由什么构成 | 13. 内存模型地基：happens-before 的全部来源 |
| 多线程读写同一变量会怎样 | 14. data race 的精确定义与"竞争即 UB"的工程后果 |
| TSan 报竞争但线上跑得好好的 | 15. TSan 纪律：从 CI 矩阵到生产的竞争检出体系 |
| 六种内存序到底怎么选 | 16. 六种 memory order 的成本模型与选择树 |
| acquire/release 怎么配对才有效 | 17. acquire/release 配对与 release sequence 深水 |
| relaxed 原子什么时候合法 | 18. relaxed 原子的合法场景与越界后果 |
| seq_cst 到底贵在哪 | 19. seq_cst 总序的真实代价与 Dekker 病例 |
| compare_exchange weak 还是 strong | 20. RMW 原子：compare_exchange 弱强形态与失败序 |
| 多线程反而比单线程慢 | 21. false sharing：缓存行 ping-pong 与 perf c2c 取证 |
| 缓存行对齐怎么算才对 | 22. 对齐工程：alignas、干扰尺寸与 128 字节现实 |
| 原子地换 shared_ptr 配置 | 23. atomic<shared_ptr> 与原子智能指针内幕 |
| atomic 和 mutex 谁快 | 24. mutex 还是 atomic：决策树与测量反转案例 |
| 两把锁死锁怎么系统性预防 | 25. 锁层级纪律与 std::scoped_lock 组合学 |
| 返回值到底拷贝了几次 | 26. RVO/NRVO 与 C++17 保证拷贝省略的边界 |
| return std::move(local) 错在哪 | 27. return std::move(local) 反模式：亲手杀死省略 |
| 对象被 move 走之后还能用吗 | 28. moved-from 状态契约：有效但未指定的纪律 |
| move 后 vector 扩容变拷贝 | 29. const 对象 move 静默退化与成员级 move 手写 |
| 万能引用与右值引用怎么区分 | 30. 完美转发：转发引用的识别与 std::forward 语义 |
| 转发构造函数劫持了拷贝 | 31. 转发引用与重载/特化的交互陷阱 |
| std::move 到底生成了什么代码 | 32. std::move 只是_cast：零生成代码与误用图谱 |
| 自定义类型的 swap 怎么写 | 33. 自移动、std::swap 与 ADL 两步查找的现代写法 |
| reinterpret_cast 转指针读数据崩 | 34. 严格别名规则：合法别名清单与 char 豁免 |
| float/int 位重解释的合法写法 | 35. 类型双关正道：std::bit_cast 与 memcpy |
| placement new 之后旧指针还能用吗 | 36. 生命周期与指针失效：placement new 与 std::launder |
| 指针加法越界到底哪里 UB | 37. 越界、指针算术与隐式生命周期对象 |
| 整数溢出会出什么幺蛾子 | 38. 有符号溢出与前置进度：无限循环 UB |
| f(i++, i++) 现在定义良好吗 | 39. 求值顺序：C++17 修复了什么、还剩什么坑 |
| 改了 const 变量直接段错误 | 40. const 承诺与 .rodata 陷阱：改 const 即 UB |
| -O0 正常 -O2 崩溃怎么查 | 41. UB 的优化放大器：编译器敢删什么 |
| unique_ptr 数组零初始化拖慢 | 42. unique_ptr 零开销边界与 move-only 类型设计 |
| shared_ptr 原始指针构造两次崩 | 43. shared_ptr 控制块内幕与 make_shared 的取舍 |
| shared_ptr 拷贝的热点成本 | 44. shared_ptr 引用计数的原子税：dimov 内存序内幕 |
| 返回成员指针并保活父对象 | 45. aliasing constructor：合法用途与 weak_ptr 幽灵 |
| 构造函数里 shared_from_this 抛异常 | 46. enable_shared_from_this：初始化窗口与 bad_weak_ptr |
| weak_ptr 过期判断的正确姿势 | 47. weak_ptr 锁定纪律与观察者生命周期 |
| 接口参数用指针还是智能指针 | 48. 所有权语义 API 设计：参数与返回值选型 |
| 海量小对象换侵入式引用计数 | 49. 侵入式引用计数：intrusive_ptr 与 LLVM 模式 |
| concepts 怎么写才有 subsumption | 50. concepts 纪律：requires 位置、原子概念与 subsumption |
| concepts 与 type_traits 怎么共存 | 51. concept 语义学：与 traits 的共存与迁移策略 |
| vector{v, v} 推导成了二维 | 52. CTAD 现代写法：推导指引与花括号陷阱 |
| 老代码里 enable_if 看不懂 | 53. SFINAE 遗产解毒：维护旧模板代码 |
| if constexpr 两个分支都被编译 | 54. if constexpr 与丢弃语句的实例化规则 |
| 变参模板的折叠表达式写法 | 55. 折叠表达式与变参模板的现代形态 |
| 空类型成员把对象撑大了 | 56. [[no_unique_address]] 与空基类优化 |
| CRTP 的现代替代写法 | 57. deducing this：CRTP 的 C++23 正式替身 |
| std::function 为什么慢 | 58. 类型擦除内幕：std::function 的三重税 |
| constexpr 里能做什么不能做什么 | 59. constexpr 深水：常量求值边界与 UB 沙箱 |
| 模板把编译时间拖爆了 | 60. 模板实例化成本与 extern template 防火墙 |
| 编译期数据结构值不值得写 | 61. 编译期容器与模板爆炸控制 |
| 协程帧分配在哪里 | 62. 协程帧内幕：分配、HALO 与省略条件 |
| 协程参数引用悬垂 | 63. 协程参数生命周期：按值捕获纪律 |
| 协程链深了栈溢出 | 64. symmetric transfer：协程链的栈溢出防线 |
| 协程不要堆分配怎么做 | 65. promise_type 设计模板：无堆分配协程 |
| 协程里抛异常去哪了 | 66. 协程异常路径：unhandled_exception 与 noexcept 纪律 |
| detached 协程双重销毁 | 67. detached task 事故：协程句柄所有权 |
| std::generator 能不能用 | 68. std::generator 与协程库生态选型 |
| 缓存不命中的局部性改造 | 69. CPU 缓存层级：行粒度、预取与局部性改造 |
| [[likely]] 该不该加 | 70. 分支预测：[[likely]] 的真实价值与误导 |
| always_inline 与 LTO 的取舍 | 71. 内联工程：attribute、LTO 与 PGO 的边界 |
| malloc 频繁小分配怎么治 | 72. 分配器战争：malloc 内幕、arena 与线程缓存 |
| pmr 内存资源实战写法 | 73. pmr 实战：多态分配器与 monotonic_buffer_resource |
| string SSO 尺寸与 move 行为 | 74. SSO 与小缓冲优化：string/vector 的实际收益 |
| vector 扩容拷贝风暴 | 75. vector 扩容策略与 reserve 纪律 |
| 迭代器什么时候失效 | 76. 迭代器失效图谱与元素重排算法选择 |
| string_view 传参悬垂 | 77. string_view 悬垂：生命周期与调用边界 |
| 性能测量从哪里起步 | 78. 测量方法论：perf、tracy 与微基准陷阱 |
| 虚调用去虚拟化手段 | 79. 去虚拟化：final、CRTP 与手动分派 |
| 向量化为什么没自动发生 | 80. SIMD 纪律：std::simd 前夜与手写向量化审计 |
| 异常到底有没有运行时开销 | 81. 异常成本模型：零成本神话的边界与 SEH |
| 禁用异常的项目怎么写 | 82. -fno-exceptions 纪律与错误通道设计 |
| std::expected 的单子组合 | 83. std::expected 单子操作与组合纪律 |
| expected、异常、错误码怎么选 | 84. expected/异常/错误码三方决策树 |
| 强异常安全保证怎么设计 | 85. 异常安全等级：基本/强/nothrow 的契约设计 |
| noexcept 乱加直接 terminate | 86. noexcept 双向承诺与 terminate 风险 |
| std::unreachable 与 [[assume]] | 87. [[noreturn]]、std::unreachable 与 [[assume]] |
| 动态库对外接口怎么保 ABI | 88. ABI 稳定性工程：pimpl、隐藏符号与版本脚本 |
| inline 函数跨 so 出问题 | 89. inline 的 ODR 语义与跨 so 陷阱 |
| STL 类型能不能过 so 边界 | 90. STL 容器跨 ABI 边界：布局差异与 opaque 设计 |
| 给 C/Rust 暴露 C 接口的设计 | 91. C 接口设计：opaque handle 与所有权契约 |
| 智能指针跨界怎么不泄漏 | 92. 智能指针过 FFI：自定义 deleter 与错误映射 |
| 静态初始化顺序灾难 | 93. 静态初始化顺序灾难与 constinit 治理 |
| 符号导出怎么管控 | 94. 符号可见性与导出策略 |
| 什么改动会悄悄破坏 ABI | 95. 二进制兼容承诺：什么改动是 ABI 断裂 |
| 多维数组视图与 mdspan | 96. mdspan 与多维数据通路 |
| senders/receivers 要不要上 | 97. std::execution：senders/receivers 的取舍 |
| flat_map 什么时候更快 | 98. flat_map/flat_set：缓存友好的替代容器 |
| 反射做结构体转 tuple | 99. C++26 反射实战预演：成员遍历与枚举转字符串 |
| 契约检查怎么部署 | 100. 契约（P2900）与运行时检查部署 |
| C++26 小特性扫盲 | 101. C++26 语言小件：placeholder _、pack indexing 与 =delete("reason") |
| C++ 测试工程怎么组织 | 102. 单元测试架构：链接纪律与编译防火墙 |
| libFuzzer 怎么搭起来 | 103. libFuzzer 纪律：coverage-guided 模糊测试建设 |
| fuzz 语料库怎么运营 | 104. fuzz 语料库与字典运营 |
| fuzz 崩溃怎么最小化 | 105. 崩溃最小化与回归固化流程 |
| 微基准结果不可信 | 106. 微基准纪律：Google Benchmark 陷阱 |
| 评审 C++ 代码扫什么 | 107. C++ 代码评审高危清单 |
| 所有权/生命周期反模式清单 | 108. 所有权与生命周期反模式百科 |
| 并发/模板反模式清单 | 109. 并发与模板反模式百科 |
| 症状反查根因速查表 | 110. 事故模式速查表：症状→根因→修复 |
| 数据竞争/悬垂/ABI 事故复盘 | 111. 生产事故复盘三例：数据竞争、悬垂与 ABI |
| 伪共享/分配/逆优化复盘 | 112. 性能事故复盘三例：伪共享、分配风暴与逆优化 |
## 1. 标准版本决策模型：17/20/23/26 生产矩阵

- 选型只看**编译器可用性**不看标准发布年：GCC 12+ / Clang 15+ / MSVC VS2022 才算 C++20"全量可用"（concepts、ranges、协程、`std::format` 各家补齐时间
  差了两年）；C++17 是库/SDK 的现实地板（用户群最广）。
- 门控用 feature-test 宏而非 `__cplusplus`（MSVC 长期报 199711L 除非 `/Zc:__cplusplus`）； 特性粒度放行，"整个项目跳标准"是大爆炸式风险。

```cpp
#if defined(__cpp_lib_expected) && __cpp_lib_expected >= 202202L
  using Error = std::expected<int, std::error_code>;
#else
  #include <tl/expected.hpp>          // backport，接口同形
  using Error = tl::expected<int, std::error_code>;
#endif
```

- 库作者策略：源码兼容三个标准，公共头只用到最老标准；新特性藏在实现里 （`.cpp`/私有头），不进对外 API——否则用户的旧工具链直接编不过。
- C++20 是当代甜点位：结构化绑定、`string_view`、`span`、保证省略（26 节）、 `jthread`、`<chrono>` 全家桶已无争议；协程/modules 是"可用但要工程化"。
- 事故模式：为了 `std::format` 强升 C++20，结果老分支的 GCC 9 编不过—— **升标准先盘点构建矩阵，再谈特性**。

## 2. C++26 前瞻：反射（P2996）与契约（P2900）落地时间轴

- 2025 年定案：**反射（P2996）与契约（P2900R14）已进入 C++26 草案**；GCC 已把反射实现合入 trunk（随 GCC 15/16 发布），MSVC 截至 2026 初仍无公开 反射支持——"标准进了"≠"三个编译器都能编"。
- 同车入库：pack indexing、expansion statements（`template for`）、placeholder `_`、
  `= delete("reason")`、trivial relocation（P2786）、constexpr 异常、
  `std::inplace_vector`、`std::execution`、`std::hazard_pointer`/`std::rcu`。

```cpp
// C++26 反射雏形：枚举转字符串（示意，随编译器演进语法可能微调）
template <typename E> requires std::is_enum_v<E>
constexpr auto enum_name(E v) {   // splicer ^^[成员] + expansion statement
  template for (constexpr auto m : define_static_array(members_of(^^E))) {
    if (value_of<E>(m) == v) return identifier_of(m);   // 成员名即字符串
  }
  return {};
}
```

- 落地纪律：反射先做"能删样板"的工具域（序列化、枚举名、ORM 映射）， 现阶段用 magic_enum/手写 X-macro 过渡（99 节）；契约预演只进实验分支。
- 判断"能不能用"的唯一标准：CI 三个编译器 + -Werror 全绿，而不是提案状态。
- 时间轴预期：C++26 正式发布（2026-2027）→ GCC/Clang 1-2 年跟进 → MSVC 再晚 1-2 年；生产采用现实在 2029 前后，按此排 roadmap。

## 3. C++23 生产就绪特性与采用顺序

- 风险分级落地：**低风险立即用**——`std::expected`、`std::print/println`、 `string::contains/starts_with`、`ranges::to`、`std::unreachable`、
  `std::byteswap`、`[[assume]]`；**中风险**（要求全员理解）——deducing
  this（57 节）、`std::move_only_function`、`std::generator`；**按域采用**：
  `mdspan`（数值）、`flat_map`（98 节）、`stacktrace`（诊断日志）。

```cpp
auto r = std::print("{}", x);            // printf 级速度、类型安全
std::flat_map<std::string, int> cfg;     // 有序读密集场景替换 std::map
auto v = words | std::views::filter(pred)
                | std::ranges::to<std::vector>();  // C++23 前要写 3 行
```

- 标准库支持不同步：libstdc++ 覆盖最广（GCC 14/15 基本齐）、MSVC 跟进快、 libc++ 逐特性灰度——上线前用 `__cpp_lib_*` 逐项探测，别赌"整体 C++23"。
- `std::expected` 生态位：局部错误（83-84 节）；`std::generator` 注意首挂起点 才执行函数体（惰性），析构未消费的 generator 只清理不产出。
- 事故模式：在 GCC 13 上 `import <expected>;` 以为是正式版，结果是 `__cpp_lib_experimental_expected`——**宏探测必须看非 experimental 版本号**。

## 4. 编译器矩阵：GCC/Clang/MSVC 差异与跨平台纪律

- 三个 STL 不是同一家：`std::string` SSO 容量 libstdc++ 15 字节 / libc++ 22 字节 / MSVC 15 字节（74 节）；`std::list`/`std::function` 节点与内联缓冲
  尺寸各异——**凡 `sizeof` 参与逻辑的代码都是移植 bug**。

```cpp
static_assert(sizeof(void*) == 8);            // 可以
// static_assert(sizeof(std::string) == 32);  // 禁：libc++ 上是 24
static_assert(std::is_trivially_copyable_v<T>);  // 用 traits 问，别数字节
```

- `char` 符号性：x86 默认 signed、ARM/Linux 部分平台默认 unsigned—— `char c = 200; if (c == 200)` 在 ARM 上恒 false。跨平台代码写死 `signed char`/`uint8_t`。
- MSVC 历史坑：two-phase lookup 必须开 `/permissive-`（VS2017 15.5+ 默认）， 否则模板两阶段查找是"装样子"，代码在 GCC 下爆一屏错。
- 异常模型：Linux Itanium（表驱动）vs Windows x64 SEH、x86-32 funclet （81 节）——异常开销量级不同，别拿 Linux 的数字指导 Windows 的热路径。
- CI 纪律：三编译器（GCC/Clang/MSVC）+ 三 STL（libstdc++/libc++/MSVC STL） 至少两两组合各跑一个 job；"只在 GCC 下过"= 没过。

## 5. 编译警告战斗集：-Wall -Wextra 之外的高价值警告

- 高价值单品：`-Wshadow`（局部遮蔽——静默逻辑 bug 第一名）、 `-Wnon-virtual-dtor`、`-Woverloaded-virtual`（子类重载吞基类虚函数）、
  Clang `-Wuse-after-move`（move 后使用，真 bug 率极高）、
  `-Wpessimizing-move` / `-Wredundant-move`（27 节）、`-Wclass-memaccess`、
  `-Wnull-dereference`、GCC `-Wduplicated-cond`/`-Wduplicated-branches`、
  `-Wfloat-conversion`（精度静默丢失）。

```cmake
add_compile_options(
  -Wall -Wextra -Wpedantic -Wshadow -Wnon-virtual-dtor
  -Woverloaded-virtual -Wuse-after-move -Wcast-align
  -Werror)                      # CI 全红；本地允许 -Wno-error= 调试
```

- `-Werror` 棘轮治理：CI 必须 `-Werror`；豁免清单（`-Wno-error=xxx`）放 独立文件、只减不增、每条带 issue 号——否则新警告永远进不来。
- `-Wold-style-cast` 在老代码库是噪音爆炸区，新文件用 `#pragma GCC diagnostic` 局部开；头文件（要被 C 与老编译器消费的）单独降级。
- 事故模式：`-Wshadow` 关着，循环里 `int i` 遮蔽成员 `i_`，O2 下行为 与 O0 不同（UB 前奏）；一类"它显然对但就是错"的 bug 半数能被警告拦下。
- 警告是静态分析的最便宜形态：clang-tidy / cppcheck 跑全量，但 `-Wall -Wextra` 是地板而非天花板。

## 6. sanitizer 四件套：成本模型与生产部署定位

- 数字先记牢：**ASan** ~2x 慢、内存 2-3x（影子内存 = 地址/8）、`+1` 桩越界 与 use-after-free 都能抓；**TSan** 5-15x 慢、内存 5-10x，只支持
  Linux/macOS（Windows 无）；**MSan** ~3x，**所有依赖（含 libc++）必须同
  插桩**，专治未初始化读；**UBSan** 各检查项几乎零开销，trap 模式可直接
  进生产。

```bash
# CI 矩阵：每个 sanitizer 一个独立 job，别混
cmake -B b-asan -DCMAKE_CXX_FLAGS="-fsanitize=address,undefined -fno-omit-leaf-frame-pointer -g"
cmake -B b-tsan -DCMAKE_CXX_FLAGS="-fsanitize=thread -g"     # 全依赖都要插桩
cmake -B b-msan -DCMAKE_CXX_FLAGS="-fsanitize=memory -g"     # 配 MSan 版 libc++
# 生产 canary：UBSan 收集模式 / ASan 采样
clang++ -fsanitize=undefined -fno-sanitize-recover=undefined  # 违约即 SIGILL
```

- 关键纪律：**TSan/MSan 下没插桩的库会漏报甚至误报**——依赖要么源码级 编进项目，要么放弃该 sanitizer；MSan 官方只有 instrumented libc++ 方案。
- 符号化要 `llvm-symbolizer` 在 PATH，否则报告全是地址——排查效率天壤之别。
- 生产定位：UBSan-trap（零依赖、可长期挂）> ASan 灰度 canary （`halt_on_error=0` 只记录不杀）> TSan 永远只在 CI/staging （10x 开销生产扛不住）。
- 抑制（`__attribute__((no_sanitize("thread"))`）只给第三方不可修代码， 必须带注释+到期日；自家的 suppression 是债。

## 7. C++20 modules 采用现实：BMI 工程与生成器约束

- 硬约束一：**CMake 3.28+ 才支持 named modules，且仅 Ninja 与 VS 生成器** ——Makefiles 生成器直接出局；硬约束二：BMI（编译器叫 CMI/.ifc/.pcm）
  与编译器版本、flags、目标三元组强绑定，**永远不分发 BMI**（12 节）。
- 支持现状：MSVC 最成熟（VS2022 17.5+ 自带 `std.ixx` 模块）；GCC 15 模块 稳定；Clang 17-18 可用但 flags 仍在漂。xmake/build2 支持度高于 CMake。

```cmake
cmake_minimum_required(VERSION 3.28)
add_library(mylib)
target_sources(mylib PRIVATE
  FILE_SET CXX_MODULES FILES
    mylib.cppm        # 模块接口单元
    detail.cppm)
target_compile_features(mylib PRIVATE cxx_std_20)
set(CMAKE_CXX_SCAN_FOR_MODULES ON)       # 开启依赖扫描（P1689）
```

- 收益要实测：叶库 20-40% 编译提升常见；混合大仓里扫描开销会吃掉收益， 甚至更慢——先在单个 target 上试点拿数字。
- C++23 补丁（`std::module`、`import std;`）见 12 节；模块与 PCH 互斥于 同一 target（10 节）。
- 事故模式：模块接口改一个 `export`，全部 importer 重编——**接口文件保持 小而稳定**，实现细节放实现单元（8 节）。

## 8. modules 迁移期策略：全局模块片段与头文件单元

- 迁移方向只有一条路：**自底向上**（工具库→核心库→应用）。顶层模块 import 头文件库是常态；反过来（头文件库 import 模块）会把模块负担传染给所有 下游，等于锁死工具链。
- 全局模块片段（GUM）是过渡期逃生气闸：非模块化的 `#include` 全放这里。

```cpp
module;                      // GUM 开始：这里只能放预处理指令
#include <cstdio>
#include "third_party_legacy.h"
export module mylib.util;    // 模块真正开始
export namespace util {
int add(int a, int b) { return a + b; }
}
```

- 头文件单元（`import <vector>;`）定位尴尬：CMake 支持零散、收益依赖 头文件本身有 include guard，实践中**不如直接上 named module 或保持 include**，别把头文件单元当终点站。
- 宏不进模块：`#define` 不被模块"导出"——依赖宏做配置的库必须继续提供 头文件形态，或把配置改成常量/模板参数。
- 依赖放大定律：改接口 → 全部 importer 重建。对策：接口只导最小集合、 实现细节移入 module partition `mylib:impl`（internal linkage、不导出）。
- 验收指标：单 target 编译时间、BMI 总体积、"touch 接口文件后重编数量" 三个数字随迁移每阶段复测。

## 9. CMake target 化纪律：使用要求传播与 presets

- 一切配置挂 target，禁止目录级 `include_directories`/`add_definitions`—— 它们对后续所有 target 泄漏，是"幽灵依赖"的根源；传播用使用要求：
  `PUBLIC`=自己+下游、`PRIVATE`=只自己、`INTERFACE`=只下游。

```cmake
add_library(net)
target_include_directories(net PUBLIC   include/)   # 下游要见头文件
target_compile_definitions(net PRIVATE  NET_INTERNAL=1)
target_compile_features(net PUBLIC      cxx_std_20)  # 标准要求随接口传播
target_link_libraries(app PRIVATE net fmt::fmt)      # 链什么用声明什么
```

- presets 是团队接口：`CMakePresets.json` 提交仓库（configure/build/test 三段、`inherits` 组合、`hidden` 内部基类），`CMakeUserPresets.json` 进
  .gitignore——新人第一天 `cmake --preset debug && cmake --build --preset debug`。
- `set(CMAKE_EXPORT_COMPILE_COMMANDS ON)` 供 clangd；`CONFIGURE_DEPENDS` 的 file(GLOB) 是反模式（每次构建都重新 glob 且 ninja 无法精确追踪）。
- FetchContent 用 `SYSTEM` 关掉第三方警告；第三方必须 `find_package` 优先、 FetchContent 兜底，顺序反了会吃满编译缓存。
- 事故模式：PUBLIC/PRIVATE 写错导致"链接传染"——app 没直接用 ssl 却 因传递依赖被要求装 openssl 的头；用 `cmake --graphviz` 每月审一次边。

## 10. 编译时间战争：unity build、PCH 与 ccache 取舍

- 顺序不是拍脑袋：**先 ClangBuildAnalyzer 拿到热点**（哪个头被 include 了 几千次、模板实例化占多少），再按"删 include > unity/PCH > ccache > modules"的性价比顺序动手。

```cmake
target_precompile_headers(app PRIVATE <vector> <string> "pch.h")
set_target_properties(app PROPERTIES UNITY_BUILD ON
                                     UNITY_BUILD_BATCH_SIZE 8)
set(CMAKE_CXX_COMPILER_LAUNCHER ccache)   # 配合 CCACHE_SLOPPINESS=time_macros
```

- unity build 副作用：同 batch 里重名静态符号冲突、匿名 namespace 串味、 `#pragma once` 依赖被放大——适合"叶子目标+测试二进制"，接口库慎用。
- PCH 与 modules 冲突（同一 target 二选一）；PCH 拖进大头的代价是 **所有 TU 都付**，哪怕只用 5%——PCH 内容要按真实 include 频率裁剪。
- ccache 命中率纪律：`-g` 全开（命中率键值友好）、别用 `__DATE__`、 `-frandom-seed` 之类的随机性要显式固定；modules 场景 ccache 帮不上扫描。
- 模板重实例化是隐形大头：`extern template` 防火墙见 60 节；一改 traits 头全库重编的，用显式实例化把热点类型固化到 .cpp。
- 目标口径：全量构建 < 5 分钟、增量 < 30 秒，超了先把"谁 include 谁" 的热力图挂出来再谈优化。

## 11. 依赖管理现实：vcpkg/Conan 与系统库陷阱

- vcpkg：manifest 模式（`vcpkg.json` + 版本约束）是唯一正确姿势，classic 模式已边缘化；binary caching + CI 缓存能把"重装依赖"从 40 分钟压到
  1 分钟。Conan 2：profile 驱动、适合多平台矩阵，两家选一别混用。
- **GLIBCXX 符号漂移**是最痛的坑：在 GCC 13 系统上编译的二进制拿到老系统 报 `GLIBCXX_3.4.32 not found`——原则：**发布二进制用最老支持工具链
  构建**（或静态链 libstdc++：`-static-libstdc++ -static-libgcc`，注意
  体积与安全补丁滞后代价）。

```bash
# 审计二进制对 libstdc++ 的符号要求，别等用户报错
objdump -T app | c++filt | grep -o 'GLIBCXX_[0-9.]*' | sort -Vu
strings libapp.so | grep -o 'GLIBCXX_[0-9.]*' | sort -Vu
```

- 系统包（apt 里的 openssl/zlib）版本随发行版漂移，跨版本构建必装 官方源/自编固定版本，并把 soname/ABI 检查放进 CI。
- vendoring 纪律：源进仓库必须带版本+patch 清单+升级 runbook；"顺手 fork 改两行"三年后就是没人敢动的僵尸依赖。
- 事故模式：CI 缓存里的依赖二进制比 Docker 基镜像旧一个 ABI 世代—— 缓存键必须包含基镜像 digest，否则在"半新半旧"里诡异链接失败。

## 12. import std; 与包管理合流趋势

- `import std;` 的价值：一次编译标准库为模块、所有 TU 复用，头文件解析 成本归零，且**宏泄漏被物理禁止**（std 的宏再也污染不了你的代码）。
- 支持现状（必须逐一探测）：MSVC VS2022 17.5+ 最先（`std.ixx`）；libstdc++ 随 GCC 15 提供；libc++ 随 Clang 18 起渐进可用；CMake 3.30 的
  `export import std;` 把 std 模块做成可传播 target。

```cmake
# CMake >= 3.30：C++23 模块化标准库 target
find_package(std REQUIRED IMPORTED)          # 实验性：import std;
target_link_libraries(app PRIVATE std)      # 用法：源文件里 import std;
```

- 规则红线：同一 TU 中 `#include` 与 `import` 混用时，include 必须全部在 GUM 里完成；`import std;` 之后再 include 头文件=未定义行为区。
- P1689 依赖扫描让构建系统"看懂" import 依赖；但 **BMI 不可分发**决定了 包管理只能分发源码——合流形态是"包管理器管源、构建器管 BMI"，别指望 conan/vcpkg 给你装 BMI。
- 落地建议：先在纯 C++23 的新 target 上开 `import std;`（收益最干净）， 与 8 节迁移策略同步推进；老代码吃 `_GLIBCXX` 老 ABI 宏的先隔离。

## 13. 内存模型地基：happens-before 的全部来源

- C++ 内存模型一句话：**两个线程的操作无 happens-before 关系且冲突 （至少一个是写、同一标量位置）= data race = UB**（14 节）。工程价值 在于背下 synchronizes-with（同步于）的完整清单，逐个对应到代码里。

```cpp
// 产生 synchronizes-with 的全部主流来源（背下来）：
std::mutex m;                        // unlock 同步于 下一次 lock
std::atomic<bool> ready{false};      // store(release) 同步于 读到它的 load(acquire)
std::thread t{run};                  // t 的创建 同步于 t 内首条语句
t.join();                            // t 的结束 同步于 join 返回
// + condition_variable notify/wait 配对、co_await 挂起/恢复、
//   静态局部量初始化守卫、seq_cst 栅栏配对
```

- happens-before 是 sequenced-before（单线程内）与 synchronizes-with（跨 线程）的**传递闭包**——推锁链是日常：`A线程: 写x; unlock(m)` →
  `B线程: lock(m); 读x` 合法，x 可以是普通变量。
- 高频误读①：原子操作只对**同一个对象**经由值传递建立同步—— `a.store(1, release)` 与 `b.load(acquire)` 不同对象，不构成任何同步。
- 高频误读②：happens-before ≠ 可见性时刻保证；它约束的是"看到旧值就 没有同步、看到新值才有"，不是实时性。
- 排查纪律：任何跨线程数据共享，先写出两端的 sw 边，写不出就是裸奔 （17 节是配对操作手册）。

## 14. data race 的精确定义与"竞争即 UB"的工程后果

- 定义三要素：两个动作、来自不同线程、冲突（至少一个写、同一标量内存 位置）且无 happens-before 排序 → **语言层 UB**。后果不是"可能读到旧值" 那么温柔：编译器按单线程假设做提升、重排、寄存器缓存。

```cpp
// 经典形态：debug 正常，-O2 下 while 永不退出（读被提到循环外）
bool done = false;                  // race：主线程写、worker 读
void worker() { while (!done) {} }  // UB：编译器有权变成 if(!done) for(;;);
// 正解（按需选序）：
std::atomic<bool> done{false};      // 16 节选 memory_order
```

- volatile 不是逃生门：volatile 只禁**该访问**的编译期消除/重排，不给 原子性、不给跨线程顺序、不约束其他变量的可见性——`volatile bool` 的 多线程同步是民间偏方，UB 依旧。
- 混用原子与非原子访问**同一位置**也是竞争：`atomic<int> x` 与裸 `int*` 指过去写，TSan 直接报。
- "良性竞争"是幻觉：bool 标志位侥幸"看起来没事"，是因为当前编译器 版本恰好没做激进优化——升级 GCC 大版本就是开盲盒。
- 工程后果清单：循环条件失效、写被合并（丢失中间态）、读永远命中寄存器 缓存。任何"偶现且与 -O 等级相关"的 bug，先把竞争假设排第一位（15 节）。

## 15. TSan 纪律：从 CI 矩阵到生产的竞争检出体系

- TSan 只抓**被执行到的调度**：跑得不够多 = 没测。体系 = CI 常驻 job （每次提交）+ 夜间长跑（压力场景各 30 分钟起）+ 并发测试必须多线程 压测入口（对同一结构开 N 线程打满，而不是单线程"并发单测"）。

```bash
# 全量插桩是硬要求：漏一个依赖库 = 漏报 + 可能误报
cmake -B b -DCMAKE_CXX_FLAGS="-fsanitize=thread -fPIE -g -O1"
./tests --gtest_repeat=200 --gtest_shuffle      # 调度压力
TSAN_OPTIONS="halt_on_error=0 history_size=7" ./app   # 多报告收集
```

- 报告读法：三段栈（读栈/写栈/前写栈）+ 对象地址；先看**是否同一逻辑 锁域**，再看是否真的无锁保护——三成"竞争"是测试里两个线程本该串行。
- 误报两大来源：信号处理器访问、TSan 不认识的汇编内存屏障——用 `__tsan_acquire/release` 注释标注，不要 suppression。
- suppression 纪律：只压第三方不可修代码、每条带原因+issue+**到期日**， 文件进仓库 review；自家代码 suppression = 把地雷记在本子上。
- TSan 顺带抓**锁序反转**（潜在死锁）——报告里的 "lock-order-inversion" 别当噪音划掉，那是免费的死锁预警（25 节）。
- 生产环境永远别开 TSan（5-15x）；staging 浸泡 + 影子流量是上限。

## 16. 六种 memory order 的成本模型与选择树

- 指令级映射（先有体感再谈选择）：x86-64 上 load 不论 relaxed/acquire 都是普通 mov，store 的 release 也是 mov，**只有 seq_cst store 变
  xchg/mfence（贵）**；ARM64 上 relaxed=ldr/str、acquire=ldar、
  release=stlr、seq_cst=stlr+ldar 组合——跨架构差距完全不同。

```cpp
// 选择树（背下来）：
// 1) 默认全部 seq_cst —— 正确优先
// 2) 生产者-消费者发布指针：store(release) + load(acquire)
// 3) 纯计数/统计：fetch_add(relaxed)
// 4) 引用计数增：relaxed；减到零：acq/rel（44 节 dimov 方案）
// 5) 永不写 memory_order_consume（语义废弃，实现按 acquire 对待）
std::atomic<uint64_t> hits{0};
hits.fetch_add(1, std::memory_order_relaxed);   // 统计计数合法用法
```

- 成本真相：贵的不是指令数，是**流水线 serialization**——seq_cst store 在 x86 上强制排空 store buffer（几十 cycle）；但只有在争用/热路径上 才可感知。
- 纪律：先把正确性写出来（seq_cst），perf 证明热点后才降级，降级 PR 必须附：为什么该序足够（推理）、TSan 全绿、microbenchmark 数字。
- 常见降级错误：把"发布数据"写成 relaxed、把"双线程互斥判决"写成 acq/rel（19 节 Dekker 病例）——降级错比不降级更危险，因为 bug 只在弱序架构（ARM/riscv）显形，x86 CI 全绿是假安全感。

## 17. acquire/release 配对与 release sequence 深水

- 配对规则：`store(release)` 发布"我之前的一切写"；`load(acquire)` **读到 那个 store 的值**才建立 synchronizes-with。读到旧值 = 无同步 = 看到的
  数据可能是垃圾——发布协议必须由"值本身"承载状态机（版本号/序号）。

```cpp
// SPSC 无锁队列骨架：索引带序，载荷裸读写（head 生产者写 / tail 消费者写）
std::atomic<uint32_t> head{0};
T buf[N];
void push(T v) {
  auto h = head.load(std::memory_order_relaxed);
  buf[h % N] = v;                                 // 载荷是普通写
  head.store(h + 1, std::memory_order_release);   // 发布 buf 写入
}  // pop 对称：head.load(acquire) 读到新值 ⇒ buf[(h-1)%N] 载荷可见
```

- C++20 收紧了 release sequence（P0982）：release store 之后**只有同对象 的 RMW** 能延续序列，"同线程的后续普通写"不再延续——老代码里 依赖"我再普通写一次也算发布"的，升级标准后就是竞争。
- mutex 是封装好的 acq/rel 对（unlock=release store，lock=acquire load）， 上面骨架本质是"手写锁的可见性半边"。
- 单写多读的发布结构，这套配对覆盖 90% 场景；还需要更多（栅栏、批量 发布）时用 `std::atomic_thread_fence(release)` + relaxed 载荷写组合， 栅栏别滥用——它便宜于 seq_cst 原子但昂贵于普通指令。

## 18. relaxed 原子的合法场景与越界后果

- relaxed 的语义 = **原子性有、顺序性无**：还是同一个对象上的竞争豁免 （不会撕裂），但不发布/不获取任何其他数据，也不保证别线程"多久能 看到新值"。

```cpp
// 合法：纯计数（丢失"顺序"无所谓，原子性已由 RMW 保证）
for (auto& r : requests) counter.fetch_add(1, std::memory_order_relaxed);

// 灾难：用 relaxed 发布数据指针（ARM 上 store-store 可重排）
Data* d = new Data{...};
ptr.store(d, std::memory_order_relaxed);   // 读者可能先见指针后见 Data
// 正解：ptr.store(d, std::memory_order_release) + 对端 acquire
```

- 合法清单要背：事件/性能计数器、单写者的"最新值"标签（读者可接受 任意旧值）、作为 `fetch_add` 生成唯一 ID（只要求不重复，不要求顺序）、 引用计数**递增**这一步（44 节）。
- 越界后果分级：可见性延迟（永远看不到？理论上可能，实践很快）→ 发布协议破产（读到半初始化对象）→ 双线程协议死锁（双方都读到旧 0）。
- 铁律：**relaxed 只用于"值本身即全部信息"的场合**；只要读到该值意味着 "别处的其他数据也可见"，就必须 release/acquire 或更强（17 节）。
- 审计技巧：grep `memory_order_relaxed`，每处写下"如果这个值被延迟 100ms 才可见，系统还正确吗？"——答不上来就是用错了。

## 19. seq_cst 总序的真实代价与 Dekker 病例

- seq_cst 保证：所有 seq_cst 操作存在**全局单一全序 S**，每个线程看到的 顺序都一致。acq/rel 只保证配对可见，不保证全局一致——两者的差别在 "双方互查"类算法上决定成败。

```cpp
// Dekker/Peterson 式互查：两个线程同时抢
std::atomic<bool> f0{false}, f1{false};
// T0: f0.store(true, seq_cst); if (f1.load(seq_cst)) /* 让位 */
// T1: f1.store(true, seq_cst); if (f0.load(seq_cst)) /* 让位 */
// 若换成 acq/rel：x86 store buffer 会让两个 load 都读到 false —— 双进
// 临界区。seq_cst store = xchg，排空 store buffer，才保证互查成立。
```

- 代价：x86 上 seq_cst store ≈ xchg（~20-40 cycle，还要序列化流水线）； ARM64 用 ldar/stlr 对；**无争用时**差距小，争用热点的 seq_cst 链是 真金白银的吞吐损失。
- 别急着降级：消息传递/发布类场景 acq/rel 与 seq_cst 在 x86 上**同价** （都是 mov），却在 ARM 上更便宜——降级收益主要在弱序平台。
- 降级前置条件（缺一不可）：算法分析证明不需要全序（单写者发布、 SPSC 这类"单向"协议）、TSan+压力测试、目标架构实测。互查/双写者 协议（锁的替代品、引用计数、无锁栈）**默认不许降**。
- C++17 还收紧过 seq_cst 与 acq/rel 混用的语义（P0668）——"混用降级一半" 的老代码升级后行为变了，别拿 2015 年的内存序博客当现行规范。

## 20. RMW 原子：compare_exchange 弱强形态与失败序

- `compare_exchange_weak` 允许**空败**（值相等也返回 false）——LL/SC 架构（ARM）的硬件特性；循环里必须用 weak（省一次重试语义），一次性 判定用 strong（自己不想写重试就别用 weak）。

```cpp
// 标准形态：CAS 循环 + 双序；expected 必须局部（全局会伪共享，21 节）
void push_lockfree(Node* n) {
  Node* expected = head.load(std::memory_order_relaxed);
  do { n->next = expected; } while (!head.compare_exchange_weak(
      expected, n, std::memory_order_acq_rel,   // 成功：发布 n->next
      std::memory_order_acquire));              // 失败：expected 刷新为最新
}
```

- 失败序约束：失败只能比成功弱或相等，且不能是 release/seq_cst（除非 成功也是 seq_cst）——编译器会硬报错，但要知道为什么：失败路径只是 load 语义。
- **ABA 问题**：值从 A→B→A，CAS 成功但世界已变。解法：指针带版本号 （64 位地址只用低 48 位，高 16 位放版本；或 `boost::atomic<T*>` 的
  tagged pointer）、hazard pointer/RCU（B 部分纵深）、或干脆退回带锁。
- `is_lock_free()` 必查：`atomic<BigStruct>` 超过机器字长会静默退化为 锁实现（libatomic），你以为的"无锁"其实带锁——`static_assert(x.is_lock_free())`。
- 用 `fetch_or/fetch_and` 做位标志位比 CAS 循环便宜一半（无重试）。

## 21. false sharing：缓存行 ping-pong 与 perf c2c 取证

- 机制：两线程写**不同变量**但落在同一 64B 缓存行 → MESI 一致性协议让 该行在核间反复失效/传输；症状极具欺骗性：**并行核数越多越慢**， CPU 时间全花在一致性流量上。

```cpp
// 病例：每线程统计挤在一个缓存行里
struct Stats { uint64_t hits{0}, misses{0}; };
Stats per_thread[8];                 // 8 个 Stats = 1 条缓存行，全场乒乓

// 修复：每计数独占一行
struct alignas(64) PaddedStat {      // C++17 起也可用 std::hardware_...
  uint64_t hits{0};                  //   但见 22 节：该常量并非处处可用
  char pad[64 - sizeof(uint64_t)];
} per_thread_padded[8];
```

- 取证：Linux `perf c2c record ./app` → `perf c2c report`，看 HITM （跨核命中修改态）热点行；没有 perf c2c 的环境用"逐字段 alignas 后 重测"二分定位。
- 数量级感：无伪共享的原子计数 ~几 ns；同一行乒乓时每次 RMW 变 数百 cycle——8 线程从 100% 加速跌到负加速是真实事故的常态曲线。
- 128 字节现实：Apple Silicon 预取粒度按行对取两行，64B 对齐不够， Folly 用 128B（`folly::kFalseSharingRange`）——跨平台库直接 128 起步。
- 审计位：所有"per-thread/per-core 累加器"数组、每元素带原子标志的 环形缓冲、`std::atomic` 成员塞在 hot struct 尾部——这三类是重灾区。

## 22. 对齐工程：alignas、干扰尺寸与 128 字节现实

- `alignas(64)` 只对**该成员起点**生效，后面的成员照样可能跨行——结构体 排布先把"需隔离的成员"前置 + 显式填充，别指望一条 alignas 全解决。
- 干扰尺寸 `std::hardware_destructive_interference_size` 是**可选**的，libstdc++ 一度长期缺位——用前必须 `__cpp_lib_hardware_interference_size` 宏门控 + 手工平台表。

```cpp
#ifdef __cpp_lib_hardware_interference_size
  constexpr size_t kLine = std::hardware_destructive_interference_size;
#else
  constexpr size_t kLine = 64;            // 手工平台表更稳
#endif
struct alignas(kLine * 2) HotPad {        // Apple 上预取对儿 → 直接 128
  std::atomic<uint32_t> seq{0};
  char pad[kLine * 2 - sizeof(std::atomic<uint32_t>)];
};
```

- `vector<alignas(64) T>` 不可行：分配器只保 alignof(max_align_t)=16——过对齐用 C++17 aligned new 或 pmr 上游资源（73 节）。
- 128B 的取舍：浪费内存带宽预算与 TLB 压力 vs 乒乓消除——**只在 perf 证明的行上做**，全库 128B 对齐是把缓存当圣诞树。
- 验证：`assert(uintptr_t(&x) % 64 == 0)` 进 debug 路径；>16B 对齐在 jemalloc/tcmalloc 走特殊 size class，收益连分配成本一起算。

## 23. atomic<shared_ptr> 与原子智能指针内幕

- C++20 的 `std::atomic<std::shared_ptr<T>>` **不是 lock-free**（典型实现 借内部自旋锁池）；且每次 `load()` 是一次 shared_ptr 拷贝 = 引用计数
  原子加——热路径逐次 load 等于每操作付一次原子 RMW + 潜在缓存行争用。

```cpp
// 模式：读多写少的配置热交换 —— 读者把 shared_ptr 缓存在请求/循环作用域
std::atomic<std::shared_ptr<const Config>> g_cfg;
void handle() {
  auto cfg = g_cfg.load(std::memory_order_acquire);  // 每请求一次，非每字段
  use(cfg->timeout, cfg->retries);                   // 之后全是普通读
}
void reload(std::shared_ptr<const Config> c) {
  g_cfg.store(std::move(c), std::memory_order_release);  // 写者换整体
}
```

- 旧 API `std::atomic_load(&sp)`（自由函数）已废弃——迁移到 atomic 对象 形态，顺手把 load/store 的 memory_order 写显式。
- 为什么必须 atomic 而不是裸赋值：两个 writer 并发 store 同一个 shared_ptr，控制块的增减会竞争（use-after-free 温床），atomic 保住 控制块操作的原子性。
- 尺寸陷阱：`atomic<shared_ptr<T>>` 内部通常锁池实现（无额外尺寸）， 但**别在 vector 里大量堆它**——本身就是重对象。
- 替代形态：写少到"只在 reload 时换"的，用 `std::atomic<std::size_t> version + 双缓冲`或 RCU 语义（B 部分纵深）；读极热的把配置改成 不可变小对象按值分发，绕开智能指针。

## 24. mutex 还是 atomic：决策树与测量反转案例

- 决策树起点：**状态是否单字长且语义简单**（标志/计数/版本号/单指针） → atomic 可选；多变量不变式（size 与 buffer 联动、多字段一致性） → mutex 别犹豫。
- 反转案例（真实发生）：把"每 op 一次 CAS"换成 mutex 提速 3 倍—— 争用场景下 CAS 循环是**忙等**（烧 CPU、总线流量），而 futex 的 mutex
  未争用时一次 CAS、争用时**睡眠让核**。atomic ≠ 快，"无锁"≠ 高性能。

```cpp
// 判例：这个该用 mutex —— 两字段不变式
struct Queue { size_t size; T* buf; };        // size 与 buf 必须一致变更
// 两个独立 atomic 会让读者看到 size 与 buf 脱节；锁一行解决：
std::mutex m_;  std::scoped_lock lk(m_);      // C++17，零死锁组合
```

- seqlock 补丁：读多写少的小 POD（如统计快照）可用版本号模式—— 读者 `seq(acquire)` → 拷贝 → `seq(acquire)` 校验偶数且相同；写者
  `seq.fetch_add(1)` 打开写、写完再 +1。等效于读端零同步开销的 RCU 味道。
- 测量反转的另一半：**无争用**时 atomic 拿下（一次 CAS ~几 ns）， mutex 也有开销（pthreads 一次 lock/lock ~20ns+）——但只有 profile 说了算，先写简单的（mutex），perf 指认后再上原子。
- `std::shared_mutex` 慎用：读侧也要原子 RMW 两个计数，未争用时比 mutex 还贵——读写锁只在"读临界区长且读线程多"时赢。

## 25. 锁层级纪律与 std::scoped_lock 组合学

- 死锁的系统性解法是**锁层级（lock hierarchy）**：给每把锁编层级号， 规则"持锁层级 L 时只能再取层级 > L 的锁"，code review 按号检查； TSan 的 lock-order-inversion 报告与此互为印证。

```cpp
enum class LockLevel { kConfig = 1, kCache = 2, kLog = 3 };  // 号小者先拿
std::mutex cfg_m, cache_m, log_m;
void f() {
  std::scoped_lock lk{cfg_m, cache_m};   // C++17：多锁按死锁规避算法获取
}                                        // 手写两把时永远 scoped_lock
// 持 cfg_m 期间只许再拿 cache_m/log_m；反向路径全部禁止
```

- `std::scoped_lock` 内部用 std::lock 算法（try + 退避循环），一次性拿 多把；`lock_guard` 单把场景、`unique_lock` 才是"要延迟/条件/解锁"的场景 ——用 unique_lock 当默认是坏味道。
- 高发死锁三形态：①同函数两序拿锁（scoped_lock 直接解决）；②持锁回调 ——锁内调用外部回调，回调再取别处锁，层级失控：**锁内只调无锁接口**； ③递归锁 `recursive_mutex` = 不变量未想清楚的味道，基本都可重构消除。
- 协程特别条款：std::mutex **不得跨挂起点持有**——挂起可能换线程恢复， 锁的持有期被拉长到不可分析，且 TLS 相关原语会错乱；临界区内禁止 co_await，锁的 RAII 作用域不得覆盖挂起点。
- 排查工具：TSan 报 lock-order-inversion；gdb 死锁现场 `thread apply all bt` 找"两线程各持一把等对方"的十字。

## 26. RVO/NRVO 与 C++17 保证拷贝省略的边界

- 两套规则要分开背：**URVO（prvalue 返回）自 C++17 起是强制省略**——返回对象直接在调用方存储里构造，零拷贝零移动，连移动构造函数都不需要存在；**NRVO（具名局部）仍是可选优化**，但三大编译器在"单一路径返回同一对象"时都会做。

```cpp
struct Immovable {                      // 删光拷贝/移动
  Immovable(int);  Immovable(const Immovable&) = delete;
  Immovable(Immovable&&) = delete;
};
Immovable make(int x) { return Immovable{x}; }   // C++17 起合法：prvalue 原地构造
Immovable make2(int x) { Immovable r{x}; return r; }  // 编不过：NRVO 是可选，
                                                      // 需要移动构造做后备
```

- NRVO 失效清单（记下，每条都否决省略）：①返回的是**函数参数**（参数不占被调者的自动存储，但会走隐式移动，27 节）；②多个 return 返回**不同对象**；③返回 `std::move(x)`（27 节）；④返回成员或 `*this` 的成员；⑤类型不一致需要转换。
- 实操口径：按值返回大对象是**零成本默认项**——别再写输出参数（`void f(T& out)`）这种 C++98 遗产，更别传 unique_ptr 当"高效返回"。
- 验证手段：在拷贝/移动构造里 `__builtin_trap()` 或 static_assert 计数，单测断言"热路径构造次数"；Clang `-Wpessimizing-move` 能拦一半事故。
- 工程后果：既然 URVO 免费，`make()` 返回 `std::optional<T>`/`std::expected<T, E>` 也是一次原地构造（83 节）——错误处理不必为性能妥协成错误码。

## 27. return std::move(local) 反模式：亲手杀死省略

- 惨案机制：`return std::move(r)` 把 `r` 变成 xvalue——NRVO 立刻出局，编译器被迫走移动构造；而 C++11 起对 `return r;` 本来就有**隐式移动**兜底，所以这个 move 只带来损失不带来收益。

```cpp
std::vector<int> f(bool wide) {
  std::vector<int> r = build();
  if (wide) return std::move(r);   // 反模式：杀 NRVO，只省了一次比较
  return r;                        // 正确：NRVO 或隐式移动自动接管
}
// 必须手写 move 的三个例外（implicit move 不覆盖）：
// ① return r.member;          → 成员访问不在隐式移动范围
// ② return cond ? std::move(a) : std::move(b);   // 条件表达式产生 glvalue
// ③ return std::move(*this).member;              // C++23 前 *this 同理
```

- 更狠的变体：`const` 局部变量上 `std::move` 得到 `const&&`，重载决议落到 **拷贝构造**——从"多一次移动"升级成"多一次深拷贝"，还全程无警告陪伴（-Wall 之外）。
- 编译器护栏：Clang/GCC 的 `-Wpessimizing-move`（该 move 反而更差）与 `-Wredundant-move`（该 move 多余）双开，进 CI 棒（5 节）。
- 评审话术："return 后面出现 std::move(局部名) 一律打回"，例外只有上面三条 + 模板代码里 `return std::forward<T>(t)`（转发不是 move，30 节）。

## 28. moved-from 状态契约：有效但未指定的纪律

- 标准库合同就一句：move 之后对象处于 **valid but unspecified** 状态——可以析构、可以赋新值、可以调用无前置条件的成员（size/clear），但**不能假设它为空**。

```cpp
std::string a = "0123456789012345";     // 16 字符：libstdc++ 恰好超过 SSO 阈值
std::string b = std::move(a);
assert(a.empty());                      // 错误假设！libstdc++ 的 SSO 短串
                                       // move 后常保留原值（缓冲被拷走而非交换）
a.clear();  a = "new";                  // 正确：只做"重置类"操作
```

- SSO 短串的 move 实为**逐字节拷贝**（源缓冲太小没有可窃取的堆指针），所以 moved-from 不空在 libstdc++/libc++ 都真实存在——`assert(s.empty())` 式代码在测试环境就能爆。
- 库作者义务：给自己的类型**文档化 moved-from 状态**；实践标准是对齐标准库——"有效但未指定"，并保证析构与赋值安全（28 节的契约即你 move 构造函数的验收标准）。
- 惯用法：move 构造用 `std::exchange` 把源成员置空（29 节），让"可观察为空"成为事实上的一致行为；同类型容器 move 后 `capacity()` 亦未指定——复用前先 `reserve`。
- 事故模式：日志链 `log(std::move(msg)); send(msg);`——第二跳读到的是未指定状态；把"move 当 no-op 转发"写代码的人，其实现是隐式赋值了"垃圾"。

## 29. const 对象 move 静默退化与成员级 move 手写

- `std::move(const_obj)` 的类型是 `const T&&`：拷贝构造 `const T&` 可以直接绑定它——于是 move 查无此人、**静默执行深拷贝**；典型病灶：const 成员函数里 `return std::move(member_)`、`const vector` 元素逐个 move。

```cpp
struct Buffer {
  std::vector<uint8_t> data_;
  Buffer(Buffer&& o) noexcept            // noexcept 必须写：vector 扩容
      : data_(std::exchange(o.data_, {})) {}   // 才肯走 move（见下）
  Buffer& operator=(Buffer&& o) noexcept {
    data_ = std::exchange(o.data_, {});  return *this;
  }
};
// std::exchange：取走旧值、留下指定新值，move 构造三件套的标准写法
```

- **noexcept 与扩容的隐秘联动**：`std::vector` 扩容时用 `std::move_if_noexcept`——移动构造不带 noexcept 的类型会**整批退回拷贝**；你的"能移动"在扩容路径上静默失效，这是负优化里最冤的一类。
- 成员级规则：编译器生成的 move 构造对每个基类/成员逐一 move，够用就 `= default`；只有持有裸资源（裸指针、fd、句柄）才手写，且必须 `std::exchange` 置空源——裸指针成员被"默认 move"后是**指针复制**，配合你手写的拷贝构造就是双 delete。
- 声明纪律：显式声明析构/拷贝任一会**抑制隐式 move**（得到的是拷贝）——要么零声明（五法则全交），要么五件套全声明，半手工状态是最常见的坑。

## 30. 完美转发：转发引用的识别与 std::forward 语义

- 转发引用只有两种形态：`template<class T> void f(T&&)` 且 T **由该调用推导**；`auto&&`。其余的 `X&&`（X 具体类型、`const T&&`、类成员函数的 `Widget&&` 形参）都是**纯右值引用**，不转发。

```cpp
template <class T, class... Args>
std::unique_ptr<T> make(Args&&... args) {          // Args&& → 转发引用
  return std::unique_ptr<T>(new T(std::forward<Args>(args)...));
}   // std::forward<U>(u) = 有条件 move：U 是左值引用则保持，否则转 xvalue
```

- 语义一句话：`std::forward` 是**有条件的 static_cast**，只负责恢复调用方的值类别，本身零指令；没有它，`Args&&` 折叠后的左体会把所有实参按左值传递（掉进拷贝）。
- 头号事故：**同一参数 forward 两次**——第一次已把对方的内容偷走，第二次静默转发"空壳"（moved-from，28 节），无警告无异常；多目标转发（log + 存储）时只在一个终点 forward。
- 花括号不可转发：`f(std::forward<Args>({1,2})...)` 语法上不成立——需要 `std::initializer_list` 显式传递；这是变参工厂构造聚合类型时的经典卡点。
- 二号事故：把转发引用当"万能 sink"传给重载族——见 31 节，它会吞掉你以为命中的别的重载。

## 31. 转发引用与重载/特化的交互陷阱

- 转发引用在重载决议里是**贪婪匹配**：`template<class T> void f(T&&)` 对任何实参（含 const 左值、字面量、派生类）都是精确匹配，压过需要转换的候选。

```cpp
class Widget {
 public:
  Widget(const Widget&) = default;             // 你想要的拷贝构造
  template <class T> explicit Widget(T&& t)    // 完美转发构造函数
      : impl_{std::forward<T>(t)} {}
};
Widget a;  Widget b{a};   // 危：命中模板！T=Widget& 推导出 Widget(Widget&)
                          // 比拷贝构造更"精确"，还可能递归自持
// 解药：加约束挡住"本类型及其派生"
template <class T> explicit Widget(T&& t)
    requires (!std::same_as<std::decay_t<T>, Widget>) : impl_{...} {}
```

- 家族事故同源：`emplace_back(v[0])`（把本 vector 的元素 emplace 回自身）——实参求值后可能引用已失效的存储；标准库用"分配器一致则先取值再扩容"防护，自家容器 API 要复刻该防护。
- 识别口诀：看到转发引用重载，先问三件事——会不会吞掉拷贝/移动？会不会吞掉基类转换？会不会把"本应报错"的调用默默接住（类型擦除后运行期才炸）？
- 工具护栏：`std::is_same_v<std::decay_t<T>, X>` 家族的 concept 约束（50 节）进 CI；Clang `-Wshadow` 抓不住这类，靠的是评审清单（107 节）。

## 32. std::move 只是_cast：零生成代码与误用图谱

- `std::move(x)` 的全部定义就是 `static_cast<std::remove_reference_t<T>&&>(x)`——**不移动任何东西**，只是把表达式标成 xvalue；真正的"移动"发生在随后被选中的移动构造/赋值里。

```cpp
void sink(std::string s);
std::string s = "hello";
std::move(s);          // 没有任何事发生：结果被丢弃，无构造被调
sink(std::move(s));    // 这里才发生移动：sink 的按值形参由移动构造
auto& r = std::move(s);  // 编不过（xvalue 不能绑左值引用），好事
```

- 误用图谱：①对已亡值重复 move（第二次是 moved-from 上的"空转"，28 节）；②`const` 上的退化拷贝（29 节）；③`return` 场景的 pessimizing（27 节）；④对 `*this` 全量 move 后继续用对象成员（自食其肢体）。
- self-move：`x = std::move(x)` 标准类型"有效但未指定"（libc++/libstdc++ 的 string/vector 实际做了防护，等价 swap 后自平衡）；自定义 move-assign 用 `std::exchange` 天然免疫，或显式 `if (this != &rhs)`。
- 排查工具：`-Wuse-after-move`（Clang，GCC 13+）+ `-Wredundant-move` + `-Wpessimizing-move` 三件套上 CI；把"move 是 cast"写进团队 wiki 的第一行，能省一半 code review 往返。

## 33. 自移动、std::swap 与 ADL 两步查找的现代写法

- 交换自定义类型要靠 **ADL 找到你的 swap**，正确咒语是两步：`using std::swap;` 引入基线，再裸调 `swap(a, b)`——让实参所在命名空间的重载赢；直接写 `std::swap(a,b)` 会把定制版本锁死。

```cpp
class BigBuffer {
 public:
  friend void swap(BigBuffer& a, BigBuffer& b) noexcept {  // hidden friend：
    using std::swap;                                        // 只在实参含
    swap(a.data_, b.data_);  swap(a.len_, b.len_);          // BigBuffer 时可见
  }
  BigBuffer(BigBuffer&& o) noexcept { swap(*this, o); }
  BigBuffer& operator=(BigBuffer&& o) noexcept { swap(*this, o); return *this; }
  // move-assign 用 swap：自移动天然安全（内容丢给将亡值，由其析构带走）
};
template <class T> void arch(T a, T b) { using std::swap; swap(a, b); }
```

- hidden friend 的双重收益：ADL 命中（不需要 `mylib::swap` 全限定）、不污染命名空间常规查找的候选集（少一重意外匹配）；标准 `std::swap` 对自定义类型会退化为三次移动，`std::list`/`std::array` 等容器有专门特化就是为此。
- **noexcept swap 是强异常安全的入场券**：copy-and-swap 惯用法（85 节）要求 swap 不抛，否则"强保证"降级；swap 声明里逐成员 noexcept 要能兑现，别嘴上说 noexcept 实际成员会抛（86 节 terminate）。
- C++20 起 `std::ranges::swap` 内置两步查找，模板代码里可直接用；自移动语义随 swap 实现自然安全（见上），不要画蛇添足加 `if (this == &o)` 后再 swap。

## 34. 严格别名规则：合法别名清单与 char 豁免

- 规则原文（[basic.lval]）：通过 glvalue 访问对象时，该 glvalue 的类型必须与对象的**动态类型"相似"**——工程化清单：同类型（允许 cv 差异）、signed/unsigned 兄弟（`int`↔`unsigned int`）、可平凡复制的类似布局（有争议，别赌）、以及全家桶豁免 `char`/`unsigned char`/`std::byte` **可以读写任何对象**。

```cpp
uint32_t bits = 0x3f800000;
float* fp = reinterpret_cast<float*>(&bits);  // 灾难：float* 访问 uint32 对象
*fp = 1.0f;                                   // UB：别名违规，非 float 对象
// -O2 下 GCC/Clang 假设 float* 与 uint32* 不重叠 → 排序/重排/向量化出错；
// 症状通常不是崩溃而是"计算结果悄悄错"（比崩溃更贵的事故）
```

- 为什么可怕：编译器的加载提升、store-store 重排、自动向量化全部建立在"不同类型指针不指向同一内存"上；违规的典型症状是 -O0 正确、-O2 出鬼（41 节），且**永远无法用调试器复现**。
- 没有 sanitizer 能查严格别名（UBSan 无此检查项）——防线是 `-Wstrict-aliasing=3` + 代码评审盯 `reinterpret_cast`（107 节）+ 把类型双关全部走 bit_cast/memcpy（35 节）。
- 逃生门：`-fno-strict-aliasing`（Linux 内核的选择）——是止血带不是免罪牌，它同时关掉了依赖别名分析的优化；网络协议、序列化、SIMD 代码是违规高发区，新代码零容忍。

## 35. 类型双关正道：std::bit_cast 与 memcpy

- `std::bit_cast<Dest>(src)`（C++20）：唯一 **constexpr 合法**的类型双关——要求两侧大小相同、都可平凡拷贝，位模式原样搬家；`float`↔`uint32_t`、指针位运算（打包版本号）都走它。

```cpp
constexpr uint32_t f2b(float f) { return std::bit_cast<uint32_t>(f); }
static_assert(f2b(1.0f) == 0x3f800000u);   // 编译期可断言，测试白送
// 大小不同/类型不平凡：退回 memcpy（编译器识别为双关，不会真拷贝）
uint64_t load64(const uint8_t* p) {        // 网络字节序读大端 64 位
  uint64_t v = 0;
  std::memcpy(&v, p, sizeof v);            // 合法：字节可读一切对象
  return std::byteswap(v);                 // C++23：<std> 里的编译器内建
}
```

- `union { float f; uint32_t u; }; u = bits;` 读 f——在 C 是合法双关，在 **C++ 是 UB**；MSVC 惯着它不代表标准站在你这边，跨编译器移植日爆雷。
- `memcpy` 是老编译器的万能牌：两边类型合法（目标侧真实存在对象）、字节宽度匹配即可；**性能焦虑不必有**——固定尺寸 memcpy 会被编译器折叠成单条 mov，零运行时成本。
- 序列化域的配套纪律：字节序统一用 `std::endian` 探测后显式转换（别写"本机就是小端"假设）；对齐不明的输入缓冲先 memcpy 到对齐局部变量，再取址运算——**未对齐访问在 ARM 上是 UB**，x86 上的"没事"是硬件溺爱。

## 36. 生命周期与指针失效：placement new 与 std::launder

- 对象在存储上诞生/销毁会**作废通过旧途径取得的部分指针**：placement new 复用存储后，指向旧对象的指针、引用、成员指针全部失效——重新访问必须经 `std::launder` 或"从 placement new 返回值重新取址"。

```cpp
alignas(T) std::byte buf[sizeof(T)];
T* p = new (buf) T{42};        // 对象 A 诞生，指向 A 的指针有效
p->~T();                       // A 死亡：所有指向 A 的指针失效
T* q = new (buf) T{7};         // 对象 B 在同一存储诞生
T* ok = std::launder(p);       // 老“名字”要经 launder 才能指 B
// 也就是：std::optional / std::variant 内部天天在做的事
// —— 你用高层库没炸，是因为库作者把 launder 写对了
```

- 实务边界：直接 `new (buf) T` 然后用**返回值**（如上面的 q）不需要 launder；需要 launder 的是"拿着旧指针想看新对象"的路径——常见于手写容器/对象池。
- `std::aligned_storage` 已废弃（C++23）：改用 `alignas(T) std::byte buf[sizeof(T)]` 或 union 成员；`std::start_lifetime_as<T>(ptr)`（C++23）给"隐式生命周期"类型提供标准开口。
- 微妙陷阱：`memset` 清整对象再 placement new 是可以的，但成员指针若在 memset 前缓存过、跨构造继续用——UB；**指针缓存必须以对象生命周期为界**重新建立，这条是内存池/环形缓冲事故的常驻根因（110 节速查）。

## 37. 越界、指针算术与隐式生命周期对象

- 指针算术的合法域：`p + n` 的落点必须在**同一数组**内（含 one-past-end 位置）；`p + (n+1)` 越过尾后一格 = UB——哪怕从不解引用；`nullptr + 0` 合法、`nullptr + 1` UB。

```cpp
int a[4];
int* e = a + 4;        // 合法：one-past-end，可比较、不可解引用
// int* bad = a + 5;   // UB：已越过尾后位置（编译器可据此折叠后续比较）
// 两个不同数组的指针做 < 比较 = UB（只在同数组内有序）
for (int* p = a; p < e; ++p) *p = 0;   // 正确的遍历姿势
static_assert(sizeof(size_t) == 8);    // 索引算术溢出见 38 节
```

- C++20 的 P0593 修正了一个历史悬案：**memcpy 到字节缓冲会隐式创建可平凡创建的对象**——`std::vector<T>` 对 trivial 类型从此不必逐个 placement new，你手写的"memcpy 反序列化成 POD 数组"也从 UB 转正为合法。
- 隐式生命周期家族：隐式生命周期类型（标量、数组、全 trivial 成员的类）在 `malloc`/`memcpy`/`operator new[]` 的字节存储里"自动诞生"；**非 trivial 类型永远不享受**——string/vector 别想 memcpy 建。
- 越界的取证难点：UB 后的"崩溃"往往发生在**合法代码**里（越界写把别的对象写坏了），ASan 的红区是唯一快速定位手段（6 节）；review 时盯 `p[i]` 的 i 来源是否有上界证明，没有就换 `std::span` 强制化。

## 38. 有符号溢出与前置进度：无限循环 UB

- 有符号溢出是 UB——编译器用"永不溢出"做代数：`x + 1 < x` 被折叠成 `false`、`a*2/2` 折叠成 `a`；由此产生的经典事故是**边界循环被优化成无限循环**（`for (int i = 0; i <= INT_MAX; ++i)`）。

```cpp
int clamp_add(int a, int b) {
  long r = (long)a + b;                  // 先升位再算：合法且可读
  return (int)std::clamp(r, (long)INT_MIN, (long)INT_MAX);
}
size_t idx = i * j;               // size_t 模 2^64：定义良好，回绕合法
                                  // 但要防"负数转 size_t 变天文数字"：
// int delta = -1;  v[size_t(delta)] = x;  // = v[巨大数]：越界 UB
```

- `size_t` 是模运算回绕（定义良好）——索引数学尽量全程 `size_t`；有符号数中途下转 size_t 是越界 UB 的高铁通道。
- 逃生门与代价：`-fwrapv` 让有符号回绕定义化（内核/老代码过渡用）；新代码用升位 + `__builtin_add_overflow`（GCC/Clang 家族，检测后走错误分支）。
- 前置进度：**无副作用无限循环是 UB**——编译器有权直接删除；依赖"死循环占住线程"的等待代码（忙等硬件寄存器、等中断置位）必须落 volatile 或 atomic（14 节）；C++26 P2809 已放行"平凡空无限循环"不按 UB 处理，但别拿未发布语义写当前代码。
- UBSan 检查项：`signed-integer-overflow` 默认在 `-fsanitize=undefined` 内、`unsigned-integer-overflow` 需显式加——后者不是 UB，但开了能抓逻辑 bug。

## 39. 求值顺序：C++17 修复了什么、还剩什么坑

- C++17 定案清单（终于和直觉一致）：`a.b`/`a->b`/`a[b]`/`a.*b` 中 a 先于 b 求值；`E1 << E2` 左先于右（`cout << f() << g()` 顺序确定）；赋值**右侧先于左侧**；**函数实参之间不可交错**（indeterminately sequenced）。

```cpp
std::map<int, int> m;
int key = 1;
m[key] = key;                       // C++17 OK：先算右侧 key
// f(i++, i++);                    // C++17 起不再 UB，但先后顺序未指明
// i = i++;                        // 仍是 UB：++ 与 = 的副作用无序
// a[++i] = i;                     // 仍是坑：[] 的 i 求值顺序复杂，别写
f(std::move(x), use(x));           // 实参间不可交错，但 move 走在后实参？
                                   // 顺序未指明 → 读 x 可能拿到空壳：禁写
```

- 修不好的残留：同表达式内**两次修改同一标量**（`i = i++`、`f(i, ++i)` 在 C++17 前的写法）依旧 UB 或未指明；解引用与副作用的组合（`a[i] = i++` 类）一律拆开写。
- C++17 实参"不可交错"的工程红利：`g(f1(), f2())` 里 f1、f2 谁先都可能，但**不会互相打断**——曾经"参数求值一半另一参数插入导致状态撕裂"的祖传悬案在 C++17 后消失。
- 纪律：一行一个副作用（107 节清单第 1 条）；`std::move` 与同一对象的其他引用同表达式出现 = 评审直接打回（30 节二次转发同族）。

## 40. const 承诺与 .rodata 陷阱：改 const 即 UB

- 两层承诺要分清：**const 对象**（定义时 const，如 `const int x = 5`）经 `const_cast` 后修改 = UB——典型后果是对象躺在 `.rodata` 段，写入直接 SIGSEGV；**const 指针/引用指向非 const 对象**的 const_cast 修改则完全合法。

```cpp
const int table[] = {1, 2, 3};
const_cast<int*>(table)[0] = 9;   // UB：大概率 .rodata → 段错误
int value = 5;
const int& r = value;
const_cast<int&>(r) = 6;          // 合法：底层对象本来可变

constexpr double kPi = 3.14159;   // 编译期折叠，更没有"写它"的余地
constinit static Config cfg{...};  // C++20：强制静态初始化，杜绝
                                   // 静态初始化顺序灾难（93 节）+ 运行期锁
```

- string literal 同理是常量数组：`char* s = "hi"; s[0] = 'H';` 自 C++11 起 编不过，但 `(char*)"hi"` 强转后写 = UB + rodata 崩溃——历史 API（execve 传参、老 C 接口）要先拷到栈缓冲。
- const 与优化：`const int` 编译期已知值会被折叠；文件级 `const` 变量默认内部链接（C++ 与 C 相反）；**mutable 成员不破坏 const 逻辑约束**，但破坏线程安全假设（const 成员函数在多线程下仍需同步，14 节）。
- constinit vs constexpr：constexpr 要求「能用于常量表达式」；constinit 只要求**初始化在编译期完成**（允许非 trivial 析构/运行期使用）——把全局对象从"首次调用时的锁保护魔法静态"升级为"装载期就绪"，是 93 节 SIOF 治理的核心武器。
## 41. UB 的优化放大器：编译器敢删什么

- 两个真实"删除"案例要背下来，建立敬畏：①**空检查删除**——函数内已解引用过 `p`，后文 `if (p) ...` 被整体删掉（解引用证明非空）；②**循环外提**——无同步的循环读 `while (!flag)` 被外提成读一次（14 节）。

```cpp
void g(Obj* p) {
  p->step();          // 编译器视角：p 已被证明非空
  if (p) cleanup();   // 整个 if 悄悄消失（GCC/Clang 都做）——
}                     // 你的"防御"没了，释放版行为与源码肉眼不同
// 诊断口径：-O0 与 -O2 行为差异 = UB 味道；缩小用 -fno-strict-aliasing /
// -fno-delete-null-pointer-checks / -fwrapv 逐个关掉定位是哪类 UB
```

- LLVM 的机制名：未初始化读 = **poison**，竞争访问 = undef；poison 流进条件分支后整个优化器"可以任意解释"——这也是 MSan/TSan 必须全插桩的原因（6、15 节）。
- 常见误读："UB 让编译器随机跳"——不，优化器每次都画出**逻辑合法**的结论，只是结论不合你意；所以 UB bug 的表现极其稳定地"错"，反而可复现可定位。
- 排查路径标准化：先 `-fsanitize=undefined,address` 跑（6 节）→ 不响再用标志位二分（strict-aliasing / null-check / 溢出）→ 最后手审 asm（`godbolt` 对照）；**永远不要用关闭优化的方式"修 UB"**。

## 42. unique_ptr 零开销边界与 move-only 类型设计

- 尺寸合同：无状态 deleter 的 `unique_ptr<T>` = 裸指针（1 字宽）；deleter 带状态（函数指针、捕获 lambda）就多一个字——**用无捕获 lambda 模板参数**把 deleter 编进类型，保持 1 字。

```cpp
auto closer = [](FILE* f) { if (f) ::fclose(f); };
using FilePtr = std::unique_ptr<FILE, decltype(closer)>;   // 无状态：1 字
FilePtr open(const char* p) { return {std::fopen(p, "r"), closer}; }

auto make(size_t n) {                       // 数组的两个语义（常踩）：
  return std::make_unique<uint32_t[]>(n);         // 值初始化：全清零
  return std::make_unique_for_overwrite<uint32_t[]>(n); // C++20：不清零
}   // 大缓冲初始化成本可观：按需选，别默认付 memset
```

- move-only 设计：删除拷贝后**务必检查移动是否被抑制**（声明析构会砍掉隐式 move，29 节）；按值传 `unique_ptr` = sink 语义，调用点必须 `std::move`（不写 move 编不过，正是想要的显式性）。
- 边界礼仪：函数要"借看不接管"就传 `T*`/`T&`（配文档约定非空/可空），别传 `const unique_ptr<T>&`——后者把"借用"和"智能指针生态"耦合，还不能接裸指针调用方；`unique_ptr<T>::get()` 的值**禁止存进成员**（生命周期幽灵，108 节）。
- `unique_ptr<void, D>` 合法（须给 deleter）——类型擦除所有权的入场形态（58 节）；`release()` 归你、`reset()` 归它，二者混用是双重销毁事故源。

## 43. shared_ptr 控制块内幕与 make_shared 的取舍

- 布局：`shared_ptr` = 2 字（对象指针 + 控制块指针）；控制块 = vtable 槽 + **强/弱两个原子计数** + deleter/allocator；`make_shared` 把对象**内联进控制块**——一次分配、对象与计数同缓存行。

```cpp
auto a = std::make_shared<Widget>("x");   // 推荐：1 次 allocation
std::shared_ptr<Widget> b(new Widget("x"));   // 2 次分配 + 潜在异常窗口
// 经典双重控制块（= double free 温床）：
struct W { void reg() { registry.push_back(std::shared_ptr<W>(this)); } };
auto w = std::make_shared<W>();  w->reg();   // 两个独立控制块都计数 1
// 两边各减到 0 → 析构两次。解药：enable_shared_from_this（46 节）
```

- make_shared 的暗面：**weak_ptr 存活期间整块内存不还**（对象虽然析构，但对象体积陪着控制块等 weak 死光）——短生命周期大对象 + 长寿观察者组合，用手动 `shared_ptr(new T)` 反而省内存；取舍写进注释。
- 异常安全注脚：C++17 后 `f(shared_ptr<T>(new T), may_throw())` 的实参不交错、安全；但 C++14 及更老工具链下这是泄漏窗口——make_shared 是无脑正确的那个选择。
- `allocate_shared` 给自定义分配器留门（pmr 场景 73 节）；控制块的首 allocation 只发生一次——**从 raw 再造 shared_ptr 必然再开一块**，没有"并入"魔法（46 节）。

## 44. shared_ptr 引用计数的原子税：dimov 内存序内幕

- 内幕（值得背）：引用计数 **递增用 relaxed**（拿到引用不需要看到别人的写）；**递减用 relaxed + 落零时 acquire 栅栏**——最后一个放手的人必须看到倒数第二个人做过的所有修改，否则析构线程读对象是竞争。

```cpp
// 等价于 libstdc++ 内部（示意）：
void release(ControlBlock* cb) noexcept {
  if (cb->strong.fetch_sub(1, std::memory_order_relaxed) == 1) {
    std::atomic_thread_fence(std::memory_order_acquire);  // Dimov 方案
    cb->dispose();          // 此处能安全看到其他 owner 的全部写入
    if (cb->weak.fetch_sub(1, std::memory_order_relaxed) == 1)
      cb->destroy();        // 控制块自身的释放也走计数协议
  }
}
```

- 工程含义：**每一次 shared_ptr 拷贝/析构都是一次原子 RMW**——热循环里传值 `shared_ptr`（或循环里反复 `weak.lock()`）= 每迭代一次缓存行争用；观察型访问传 `const&`，仅"真的要延寿"时按值（48 节）。
- 与 23 节 `atomic<shared_ptr>` 区分：那是"指针本身的多写者原子性"；本节是**任何一次拷贝**都要付的基础税，二者叠加（atomic load 返回临时再存成员）会付两遍。
- 线程安全边界背一遍：**控制块线程安全、对象本体不**——多线程只读 OK，读写并发就得自己加锁（14 节），"shared_ptr 等于线程安全"是流传最广的民间谬误。
- 数据点：无争用原子加 ~几 ns，跨核争用时三位数 cycle 起——100M 次/秒的传递路径上，这个税直接进火焰图前三。

## 45. aliasing constructor：合法用途与 weak_ptr 幽灵

- 签名：`shared_ptr<T>(const shared_ptr<U>& owner, T* alias)`——**共享 owner 的控制块、指向 alias**；头号合法用途是"返回成员指针且让父对象活着"。

```cpp
struct Session { Timer timer; std::shared_ptr<Timer> timer() {
  return {shared_from_this(), &this->timer};   // 父活着 ⇒ 成员活着
} };   // 调用方拿到的 shared_ptr<Timer> 会把整个 Session 续命
```

- 幽灵一（生命周期意外放大）：观察者只想要 Timer，却把 Session（及其连接/缓冲）全部钉在内存里——**API 文档必须写明"此指针保活整个父对象"**，不然泄漏工单里全是"看不见的引用"。
- 幽灵二（二次包装双释放）：拿到 aliasing 指针的代码用 `get()` 再去构造 `shared_ptr<T>(raw)`——新开一个独立控制块，两个控制块各自减到零 → **double free**；评审盯 `.get()` 进构造函数的写法（42 节同族）。
- weak_ptr 从 aliasing 指针派生 = 观察的是**父对象的控制块**：`lock()` 成功 ⇔ 父活着；父死后 lock 为空、成员指针自然不再可用——语义上安全，但"`weak<Timer>` 实际追踪 Session"的心智负担要写进接口注释。
- 反模式警告（社区共识）：把 aliasing 指针当"通用成员访问器"乱撒，会让所有权图不可读；限定在**内部实现/桥接层**，公开 API 优先返回 `(shared_ptr<Owner>, 成员引用/索引)` 或专用视图类型。

## 46. enable_shared_from_this：初始化窗口与 bad_weak_ptr

- 机制：`shared_ptr` 构造时检测基类 `enable_shared_from_this`，往内嵌 `weak_ptr` 里**种入控制块**；两个禁区 = 首个 shared_ptr 诞生**之前**与对象析构**开始之后**——`shared_from_this()` 在这两个窗口抛 `bad_weak_ptr`。

```cpp
struct Node : std::enable_shared_from_this<Node> {
  Node() { auto self = shared_from_this(); }   // 禁区①：构造期必抛
  void start() { spawn([self = shared_from_this()] { run(); }); }  // 正确
};
struct Outer { Node inner; };
auto o = std::make_shared<Outer>();
// o->inner.shared_from_this() 抛！禁区③：嵌套成员不会被种入
// —— 挂钩只发生在"最外层对象"上，inner 的 weak_this 永远为空
```

- 禁区③（嵌套成员）是最隐蔽的：编译期全绿、首次运行才抛——修法：让内聚对象自己被 make_shared 独立持有，或把"要 self 引用"的注册动作挪到外层的 `init()` 两段式里。
- 构造期需要"自引用"的设计改用**两段式初始化**：`static std::shared_ptr<Node> create() { auto p = std::make_shared<Node>(); p->wire(); return p; }`——工厂函数是enable_shared_from_this 的官方搭档。
- const 重载：C++17 起常量成员函数里 `shared_from_this()` 返回 `shared_ptr<const T>`（两个重载自动选择）；想要可写版本要显式 const_cast 或设计成非常量操作。
- 虚继承/多基类下 enable_shared_from_this 会被"重复嵌入"（布局放大 + 歧义）——组合优于继承在这里同样成立；能用"外部注册表 + weak_ptr 映射"就不往对象里塞基类。

## 47. weak_ptr 锁定纪律与观察者生命周期

- 铁律：**判活只有一种姿势——`lock()` 后判空**；`expired()` + 后续使用是 TOCTOU 竞争（两步之间对象可能恰好死掉）。

```cpp
std::vector<std::weak_ptr<Listener>> listeners;
void emit(Event e) {
  for (auto& w : listeners) {
    if (auto l = w.lock()) l->on_event(e);   // lock 期间强引用在手上，
  }                                          // 回调里对象死不掉（安全窗）
}
// lock() 的成本：strong 计数 CAS（44 节）——高频事件×大量监听者会显形，
// 热路径考虑注册表 + 显式 disconnect（死亡通知主动剔除）替代轮询
```

- 观察者模式的腐烂点：①监听器死亡但 weak 留存 → 列表越长 lock 空手越多（要定期清扫）；②回调里**再入 emit/析构自己** → 迭代中修改容器 → 复制快照再遍历；③对象在回调里注销自己 → 用 index/标记延迟删除，别在遍历中 erase。
- 反环套路：父持 `shared_ptr` 孩子、孩子持 `weak_ptr` 父；**强边指向"生命周期被拥有者"**，弱边指向"可消失的上游"；两个方向都强 = 环 = 永不析构（内存图上叫"孤岛"）。
- 弱指针的其他合法用途：缓存（值可重建）、跨线程任务去重表、单例的"可能已死"探测；非法用途：把 weak 当"延迟参数传递"（每次用都要 lock，等于把所有权问题绕圈重演）。

## 48. 所有权语义 API 设计：参数与返回值选型

- 选型表（贴在评审手册上）：`T`（值，小且可拷贝）、`T&&`（罕见）、`const T&`（借读，可能别名）、`T&`（借写，非空）、`T*`（借、可空，**语义要注释**）、`std::unique_ptr<T>` 值（sink 独占）、`std::shared_ptr<T>` 值（sink 共享）、`unique_ptr<T>&`（重置出口，味道重）、`const shared_ptr<T>&`（只为了传给下游才用）。

```cpp
// 参数：接管与借用分开写清楚
void load(Config cfg);                       // 拷贝进来：cfg 就是我的
void process(std::unique_ptr<Blob> b);       // sink：调用点必须 std::move
void peek(const Blob& b);                    // 借读：不延长生命周期
void poke(Blob* b);                          // 借写、可空：文档写明 null 语义
// 返回值：裸指针/引用 = 借用，必须文档"谁保活"（唯一最大的坑）
Timer& Session::timer();          // 危：调用方存下引用，Session 一死全悬空
std::shared_ptr<Timer> timer();   // 保活返回（45 节 aliasing）
```

- 大忌：**返回借用 + 无文档**（`T&`/`T*`/`string_view`/`span`）——调用方"顺手"存成员，父对象一换血就是悬垂（77、108 节）；借用返回值要配 `[[nodiscard]]` + 命名约定（`*_view`/`*_ref`）。
- `string_view`/`span` 参数是"借"：接临时量的调用（`f(std::string("x"))`）编译过、运行期悬垂——评审口诀"view 参数 + 临时实参 = 打回或转值"。
- 跨边界（FFI/序列化/异步队列）一律**值或智能指针**，借用语义止步于同步调用栈内（92 节）；返回 `unique_ptr` 的工厂最通用（可升格 shared）。

## 49. 侵入式引用计数：intrusive_ptr 与 LLVM 模式

- 换一种税：把计数**搬进对象**（`std::atomic<uint32_t> refcnt_` 成员），智能指针只剩 1 字宽、一次分配、计数与对象同缓存行——LLVM `IntrusiveRefCntPtr`、Chromium/Skia 的 `sk_sp`、Boost `intrusive_ptr` 全是这个形态。

```cpp
template <class Derived> struct IntrusiveCount {
  void retain() const { refcnt_.fetch_add(1, std::memory_order_relaxed); }
  void release() const {
    if (refcnt_.fetch_sub(1, std::memory_order_acq_rel) == 1)
      delete static_cast<const Derived*>(this);   // CRTP 定向删除
  }
  mutable std::atomic<uint32_t> refcnt_{1};       // 生而持 1
};
using BufferRef = llvm::IntrusiveRefCntPtr<Buffer>;
// intrusive_ptr_add_ref/release 两个钩子自由函数可对接存量体系
```

- 内存序沿用 44 节方案：增 relaxed、减 acq_rel（fetch_sub 自带栅栏效果，比"relaxed + 手动 fence"多花一点、更省心）。
- 代价清单：①**没有 weak_ptr**——观察语义要自己造（句柄表/世代号）；②类型必须配合改造（侵入），第三方类接不上；③**Release 内自杀**：成员方法里 `this->release()` 后继续访问 = use-after-free——危险回调里用"延迟销毁队列"（LLVM/Chromium 的 Deferred destruction 模式）兜底。
- 选型：对象小且量大（AST 节点、纹理、tile）、指针要进 C ABI（1 字宽）、或框架本就 Retain/Release——intrusive 赢；需要 weak/多态 deleter/非侵入 → shared_ptr。

## 50. concepts 纪律：requires 位置、原子概念与 subsumption

- 三种合法写法要认全：`template <class T> requires C<T>`（子句）、`void f(C auto x)`（简写形参）、`requires requires (...) {}`（requires 表达式——第二个 requires 后面才是表达式本体）。

```cpp
template <class T>
concept Addable = requires(T a, T b) { a + b; };      // 原子概念：命名单独
template <class T> requires Addable<T> && Copyable<T>
T sum(const std::vector<T>& v);                        // 同拼写才有 subsumption
template <class T> requires std::integral<T> || std::floating_point<T>
T sum2(const std::vector<T>& v);
// sum 的受约束版在与「只要求 Copyable」的重载竞争时更特化（自动胜出）；
// 若写成 requires is_arithmetic_v<T>（裸 bool 表达式）则不与任何概念蕴含
```

- subsumption（约束蕴含）的判定基于**规范化后的原子约束**：两边都是"同名 concept + 相同实参"才可比较；`C<T> && D<T>` 能蕴含 `D<T>`，但 `requires C1<T>` 与 `is_same_v<...>` 型布尔表达式互不蕴含——**给约束起名，不要内联布尔**。
- 位置纪律：约束**尽量前移到签名**（错误在调用点爆，信息友好）；requires 表达式只描述"语法可编译"，**语义要求**（如 forward_iterator 的均摊 O(1)）写进注释/文档——概念只验形态不验复杂度。
- 禁忌清单：不要用 `requires true`/`requires false` 当开关（用宏或 tag 分发）；不要在 requires 表达式里塞副效应调用；同一约束的多种拼写在库里要收敛到唯一名字（否则 subsumption 悄悄失效、重载歧义）。

## 51. concept 语义学：与 traits 的共存与迁移策略

- concepts 不是 traits 的死刑：**接口层用 concepts、实现体里仍用 traits + if constexpr**；库要同时服务 C++17/20 时用宏门控双写。

```cpp
#if __cpp_concepts >= 201907L
  template <class T> requires std::integral<T>
#else
  template <class T, std::enable_if_t<std::is_integral_v<T>, int> = 0>
#endif
  T halve(T v) { return v / 2; }
// 受约束偏特化（C++20 新能力，SFINAE 做不到的干净度）：
template <class T> struct Hash;                     // 主模板
template <std::integral T> struct Hash<T> { static size_t f(T v); };
template <class T> requires std::ranges::range<T>
struct Hash<T> { static size_t f(const T& v); };    // 按约束特化分发
```

- 迁移策略：新增代码一律 concepts；存量 enable_if 按"改动即迁移"渐进（53 节解毒手册）；**外发 SDK 头保持 C++17 语法**直到用户群工具链升级——concepts 的诊断红利先吃到的是内部 API。
- 迭代器新世界：ranges 用 concepts 重建了迭代器分层（`std::random_access_iterator` 等），旧 tag dispatch（`iterator_category` + `std::advance` 分发）退役——自家容器接 `ranges::begin/end` 就能白嫖全算法族。
- 剩余的 traits 不可替代位：`is_trivially_copyable_v`（决定 memcpy 快路径）、`is_nothrow_move_constructible_v`（决定 vector 扩容策略，29 节）、`decay_t`/`common_type_t`——概念负责"能不能"，traits 负责"怎么样"。

## 52. CTAD 现代写法：推导指引与花括号陷阱

- 最贵的一课：`std::vector v{other, other};` 不是"两个元素的 vector<int>"——**花括号把每个实参当元素，推导成 `vector<vector<int>>`**；单元素同型特例 `vector v{other}` 反而推导为拷贝（元素推导被 P0702 排除）。

```cpp
std::vector<int> v = {1, 2, 3};
auto a = std::vector{v, v};     // vector<vector<int>>：两元素（坑！）
auto b = std::vector<int>{v};   // 显式模板实参：一元素拷贝语义的稳妥写法
std::pair p{1, 2.5};            // CTAD：pair<int, double>
std::lock_guard lk{m_};         // CTAD 拿掉类模板噪音
template <class T> struct Wrap { T v; };
Wrap w{42};                     // C++20 聚合 CTAD：无需手写指引
```

- `()` 与 `{}` 的语义分裂在 CTAD 下更危险：`vector<int> a(3, 5)` 是三个 5，`vector<int> a{3, 5}` 是两个元素——评审规则"构造实参里出现数字字面量时必须核对括号形态"。
- 手写推导指引的三个时机：①代理类型（iterator pair 推导需要从 `value_type` 逆推）；②聚合要"擦除"部分实参（`template <class T> Wrapper(T) -> Wrapper<decay_t<T>>`）；③禁止某推导（`explicit(true)` C++20 可标记指引）。
- 继承基类构造函数的 CTAD 在 C++20 被禁、C++23（P2582）恢复——跨版本代码对"从派生类聚合推导基类形参"的写法要保持保守（显式指引不依赖版本差异）。

## 53. SFINAE 遗产解毒：维护旧模板代码

- 先建立解剖图：enable_if 的三种位置——返回类型（成员函数惯用）、**多余模板形参默认值**（推荐，签名干净）、形参默认值（对内足够）；本质都一样：替换失败发生在"**立即上下文**"才可 SFINAE，模板体内部炸了就是硬错误。

```cpp
// 解毒对照表：读老代码的三件套
template <class T,
          std::enable_if_t<std::is_integral_v<T>, int> = 0>   // ①多余形参
T parse(T v);
template <class T>
std::enable_if_t<std::is_floating_point_v<T>, T> parse(T v);  // ②返回类型
template <class T, class = void> struct has_size : std::false_type {};
template <class T> struct has_size<T, std::void_t<
    decltype(std::declval<T&>().size())>> : std::true_type {}; // ③void_t 检测式
// 迁移：①②→ requires 子句；③→ requires 表达式（50 节）
```

- 高频地雷：①依赖基类成员不带 `this->` 在模板里不查名（"静默不 SFINAE"，直接裸奔到运行期）；②`decltype` 表达式里写了**硬 static_assert**——那不是 SFINAE，是替调用者自杀；③同一表达式的两种拼写不构成重载差异。
- 解毒顺序：先写单测钉住行为（SFINAE 分支选择可测：`is_invocable_v` 家族）→ 按对照表机械替换 → `-fconcepts-diagnostics-level` 检查诊断质量提升；禁止"顺手重构"式迁移。
- 什么不该迁：对外发布的 C++17 兼容头（51 节）、`enable_if` 承担"版本开关"而非类型约束的场合——后者换成 `if constexpr` 更诚实（54 节）。

## 54. if constexpr 与丢弃语句的实例化规则

- 红线认知：**丢弃语句不实例化"依赖于模板形参的部分"，但其它一切照常编译**——非模板函数里的 if constexpr 两边全量编译；模板里非依赖的调用也会被编译（想按平台裁剪用 `#if`，if constexpr 不背锅）。

```cpp
template <class T>
auto describe(const T& v) {
  if constexpr (std::is_integral_v<T>) {
    return std::to_string(v);            // T 为浮点时不实例化（安全）
  } else if constexpr (std::ranges::range<T>) {
    return "range"s;                     // 注意：字面量要 C++14 起
  } else {
    static_assert(sizeof(T) == 0 /*依赖=false*/, "unsupported");
  }   // C++23 P2593 前：static_assert(false) 在丢弃分支也硬炸，
}       // 老代码用 always_false<T> 依赖式；C++23 起可直接写 false
```

- 与模板实参解耦的三个真相：①被丢弃分支仍要求**语法正确**（括号、声明结构）；②lambda 在丢弃分支捕获模板变量——只要 lambda 本身不被实例化就安全，但 MSVC 的延迟解析有历史差异，别赌；③`else if constexpr` 是一个整体链，不是两个 if——重构时容易把链条拆断。
- 与 concepts 分工：if constexpr 管"同一函数内按类型选实现"，concepts/重载管"不同函数间的接口分派"——两者混用导致约束重复检查（50 节），以"外部看得到几种签名"为界。
- 实战组合拳：`if constexpr` + 折叠表达式（55 节）替代 tag dispatch；递归模板的终止条件放 `sizeof...(Ts) == 0` 分支——比偏特化终止干净一个量级。

## 55. 折叠表达式与变参模板的现代形态

- 四形态速记：`(pack op ...)` 右折叠、`(... op pack)` 左折叠、`(pack op ... op init)`、`(init op ... op pack)`；`std::cout << ... << args` 就是带初始值的左折叠。

```cpp
template <class... Args>
void log_all(Args&&... args) {
  (static_cast<void>(std::forward<Args>(args)), ...);  // void 逗号折叠：
}                        // 防"args 类型的 operator, 被意外征用"的经典坑
template <class... Args>
auto sum(Args... args) { return (args + ...); }
sum()               // 编不过：空包 + 折叠无缺省 → 给 init：(0 + ... + args)
sum(1, 2, 3);       // 6
// 空包缺省值（必须背）：&& → true；|| → false；, → void()；其余全部非法
```

- **operator, 劫持**是折叠的头号事故：若元素类型重载了逗号（Eigen/某些 DSL），`(f(args), ...)` 调的是重载版——`static_cast<void>` 强制丢弃返回值可防一半，最稳是逐元素 `f` 返回 void。
- 双包 zip 折叠不存在（语言级禁止）——要配对遍历用 `index_sequence` 展开或编译期 range；流插入方向要小心：`((std::cout << args) << ...)` 左折叠才是"从头到尾"的正确序。
- 编译成本意识：折叠完全展开实例化 N 份代码，几十个元素没问题、上千元素就是编译器/代码体积灾难（61 节）——大 packs 走运行期循环 + tuple 展开一次性桥接。

## 56. [[no_unique_address]] 与空基类优化

- 两件武器一张表：**EBCO**（空基类占 0 字节，多继承 policy 时白拿）；**[[no_unique_address]]**（C++20 成员版：空成员可与其它子对象共享地址）——标准库拿它压平 allocator/deleter/comparator，`unique_ptr<T, StatelessDeleter>` 因此保持 1 字（42 节）。

```cpp
template <class Alloc>
struct Store {
  [[no_unique_address]] Alloc alloc_;    // 空 allocator 不花一个字节
  std::vector<int, Alloc> data_;         // GCC/Clang：sizeof(Store)==24
};
struct alignas(16) Chunk {};             // 注意：非空类型标 no_unique_address
static_assert(sizeof(Store<std::allocator<int>>) == 24);   // 跨编译器哨兵
// MSVC 历史上忽略该属性（按普通成员排布）：这条 static_assert 曾长期
// 只在 GCC/Clang 上成立——2022 后期版本才真正压缩，必须用断言钉住
```

- 陷阱与哨兵：①MSVC 长期"装样子"（属性存在、布局不压缩）——**跨编译器代码必须用 `static_assert(sizeof(...))` 钉住期望布局**，把 ABI 假设变成编译期合同；②`[[no_unique_address]]` 与 `alignas` 成员组合可能迫使插入填充——空基类优化对"带对齐的空类型"同样失效。
- EBCO 实操：policy/allocator/比较器**优先做基类**（`private EmptyPolicy`）比做成员更早可用；多继承空基类彼此可重叠（都占 1 字的成员版做不到）。
- 库内幕背书：`std::string` 的 allocator、`unique_ptr` 的 deleter、`std::function` 的调用器都在用这套压缩——你的容器包装层不跟着用，就是平白比标准库多 8 字节/对象。

## 57. deducing this：CRTP 的 C++23 正式替身

- 显式对象形参：`auto f(this Self&& self)` 让 self 在调用点**推导为最终派生类型**——CRTP 想要的"基类代码操作派生对象"不再需要模板基类；同一份函数覆盖 const/非 const × 左值/右值四象限，老 CRTP 里四份重载归一成一份。

```cpp
struct Node {
  template <class Self>                        // 一份定义四象限通吃
  auto& at(this Self&& self, std::size_t i) {
    if (i >= self.size_) throw std::out_of_range{"at"};
    return self.data_[i];                      // 函数体内 this 不可用，只用 self
  }
  int* data_;  std::size_t size_;
};
auto fact = [](this auto self, int n) -> long long {  // 递归 lambda 原生化
  return n <= 1 ? 1 : n * self(n - 1);         // self 按值传闭包：大捕获的栈耗要算
};
```

- this 禁用与红利：显式对象成员函数里 `this` 不存在、不能 virtual、不能 const/ref 限定（写在 this 形参上）；其"成员指针"退化为**普通函数指针**（第一参是对象）——给回调适配层省掉整套成员指针样板。
- 迁移判据：CRTP 基类只为"调回派生实现"（无状态、无存储职责）的全部可换；承担存储/接口聚合/静态向下转型的 CRTP 留在原地。支持矩阵：MSVC 最先（VS 2022 17.2）、GCC 14、Clang 18——库代码用 `__cpp_explicit_this_parameter >= 202110L` 门控，对外 API 等三编译器齐再上。
- 高价值落地场景：递归 lambda、双向对称运算符（`operator+(this T, const U&)`）；评审红线：显式对象形参与模板成员组合后重载决议比 CRTP 时代更难读——签名旁注释推导结果。

## 58. 类型擦除内幕：std::function 的三重税

- 三重税：①CopyConstructible 约束——move-only 闭包（持有 unique_ptr 的 lambda）直接编不过；②SBO 太小——libstdc++ 内联缓冲仅 16B（两个指针），捕获 ≥3 个变量的 lambda 落堆；③**间接调用**——operator() 经函数指针跳转，被调体对调用方不可见，内联与常量传播全灭，热路径一次 std::function 参数能把 5ns 的计算变成 20ns。

```cpp
std::function<int(int)> f = [a,b,c](int x){ return a*x*x + b*x + c; };  // 3 捕获→堆
// 按类型传参：零擦除全内联（模板形参是唯一"免费"回调形态）
template <class F> long integrate(F&& g, double lo, double hi);
// 选型阶梯：模板形参 → function_ref（C++26，非拥有视图，borrow 生命周期）
//          → move_only_function（C++23，可移动）→ std::function（冷路径兜底）
```

- 堆分配判定按捕获字节数：libstdc++ 16B / libc++ 24B / MSVC 更大——"恰好挤进缓冲"的 lambda 在三家 STL 间行为不同，别按字节数赌；每请求构造 function 的高并发路径 = 分配风暴（72 节）。
- RTTI 侧税：`target()/target_type()` 需要 RTTI，-fno-rtti 项目里不可用（82 节配套纪律）；std::function 本体在 no-rtti 下照常工作，但别让"偶发可用"变成隐性依赖。
- 验收手段：perf 看擦除边界上的间接跳转数；godbolt 查 operator() 是否 direct call——**std::function 一旦出现在内层循环签名里，评审默认要求换型并出示测量**。
- function_ref 的坑与 string_view 同族：非拥有视图——挂参数位安全，**存成成员/异步使用必悬垂**；换来的是零分配零拷贝 + 强制调用方写清生命周期合同。
- 对齐税：SBO 缓冲按 void* 对齐落位——过对齐 callable（SIMD 成员/原子成员）直接落堆；擦除类型的排布永远按"最差情况"，别拿 SSO 尺寸做设计前提。

## 59. constexpr 深水：常量求值边界与 UB 沙箱

- UB 沙箱是核心红利：常量求值里的任何 UB（越界、未初始化读、溢出、坏转型）从"运行时随机爆炸"升级成**编译错误**——最凶的一类 UB 被关进编译期，错误直接带行号。

```cpp
constexpr int sum_sq(int n) {
  std::vector<int> v(n);                  // C++20：常量求值中可用容器
  for (int i = 0; i < n; ++i) v[i] = i * i;
  int s = 0; for (int x : v) s += x;
  return s;                               // 离开前堆内存按瞬态规则结算
}
static_assert(sum_sq(4) == 14);           // 沙箱里 UB 不是"可能"，是编不过
```

- 瞬态分配（transient allocation）：constexpr 中的 new/容器内存必须在**同一次常量求值内**释放，指针/引用不能逃逸到求值结果——C++20 的 constexpr vector 依赖此规则；求值产物要能"落成纯数据"（拷出/转 array）才有效。
- 禁区清单（常量求值必失败）：reinterpret_cast、void* downcast、修改静态存储、不终止循环、dynamic_cast；C++26 逐步开洞（constexpr 异常、placement new）——别把"现在编不过"写死成"永远不行"。
- consteval 强制立即求值：`consteval auto table()` 生成查找表，配 constinit 落 .data（93 节），治"静态初始化不确定"的根；成本意识：常量求值是解释执行、比运行时慢 1-2 个数量级，大表生成是编译时间税（61 节）。
- 撞线参数：Clang `-fconstexpr-steps`（默认 ~100 万步）、GCC `-fconstexpr-ops-limit` 会在大循环上先掐你——把规模当编译期预算管理。
- 常识纠偏：标 constexpr **不改变运行时代码**——它只是"可以常量求值"的许可；收益全部来自实际发生的常量求值（static_assert/constinit/模板实参），把 constexpr 当优化注解刷屏是纯仪式。

## 60. 模板实例化成本与 extern template 防火墙

- 放大机制：模板 foo<T> 被 40 个 TU include 并使用 = 40 份全量实例化；链接器去重只省磁盘与链接时间，**前端解析与实例化的 CPU 已经全付**——"头文件模板重度实现"的编译时间账单就在这里。

```cpp
// widget.h
extern template class std::vector<Widget>;   // 显式实例化声明：压制各 TU 隐式实例化
// instantiate.cpp（全库唯一一个 TU）
#include "widget.h"
template class std::vector<Widget>;          // 定义：集中付费一次
// 规则边线：对 std 模板显式实例化必须依赖程序自定义类型（Widget 合法；
// 对 int/裸指针做 std 模板实例化是 UB）——自家模板无此限制
```

- 测量优先：ClangBuildAnalyzer 的 "instantiation time" 排行——前十名模板常吃掉 30%+ 编译时间；templight/GCC `-ftime-report` 辅助；**先修热点模板（少展开、少嵌套）再谈全库 extern template**，反着做是无差别的仪式感。
- 防火墙模式：重模板实现放 .ipp/.txx 只被一个 TU include + 显式实例化集合（libstdc++ 内部同款）；对外只暴露瘦接口，模板爆炸不外溢给用户——库作者的编译时间是 API 的一部分。
- 边界事实：extern template 只压隐式实例化，函数级同理（`extern template void f<int>(int);`）；显式实例化会**实例化全部成员**，想只导出少数符号就别整类实例化。
- 增量防波堤：用户 TU 里自行 `template class std::vector<MyType>;` 会与库内 extern template 打架（重定义/可见性）——对外文档写死"库负责实例化以下类型清单"，让实例化职责像 ABI 一样可审计（95 节）。

## 61. 编译期容器与模板爆炸控制

- 编译期链表的真相：`List<T, List<U, Nil>>` 型 TMP 的取头/取尾都是新类型实例化，长度 n 的 fold ≈ O(n) 深度 × O(n) 宽度的实例化；50 个元素开始咬人，200 个吃满分钟级——**类型编码数据的成本是平方级**。

```cpp
// 反例：模板递归拼 string，每次 + 产生新类型（O(n²) 实例化 + 符号膨胀）
// 正解：constexpr 求值，数据是数据，类型只有一个
constexpr auto make_table() {
  std::array<int, 16> t{};
  for (std::size_t i = 0; i < t.size(); ++i) t[i] = static_cast<int>(i * i);
  return t;                              // 常量求值出纯数据（59 节沙箱）
}
constexpr auto kTable = make_table();    // 落静态存储，零模板实例
```

- 深度红线：模板实例化深度默认 900（GCC）~1024（Clang），`-ftemplate-depth` 提上限是止疼不是治病；变长参数包优先折叠表达式（55 节）而非头尾递归，pack indexing（101 节）落地后取头递归整条退役。
- 尺寸级联：编译期 string/树的"类型"携带全部数据 → 每个不同值是不同类型 → **符号表与调试信息爆炸**（一个编译期 map 能让 .o 膨胀十倍）；上线前查产物体积再决定"全编译期"。
- 折中线（多数项目的甜点位）：模板只承载形状（数量、tag、类型列表），数据 constexpr 算；只有"类型必须出现在签名/返回类型里"的分派才值得类型列表，其余全是过度元编程。
- 经验阈值：类型列表 ≤8 个随手用（index_sequence/tuple 足够）；>8 就写基准（实例化时间/产物体积）——元编程没有免费的"只是编译期"，ClangBuildAnalyzer 的数字是唯一裁判。

## 62. 协程帧内幕：分配、HALO 与省略条件

- 帧解剖：协程帧 = promise + 参数副本 + 跨挂起点的局部 + resume/destroy 指针头，尺寸编译期已知，运行时经 `operator new(size_t)` 分配——**默认走堆**，`co_await` 看似零成本，每次进协程先付一次 malloc。
- HALO（Heap Allocation eLision Optimization）：仅当调用点**内联成功**且编译器能证明帧生命周期不逃出调用者时才可能省分配——保守启发式下命中率不高；Clang 提供 coroutine_elidable 一族提示属性，仍属"惊喜"而非可依赖的机制。

```cpp
struct Task {
  struct promise_type {
    void* operator new(std::size_t) noexcept;   // 失败返回 nullptr 走空对象路径
    Task get_return_object();
    Task get_return_object_on_allocation_failure() { return Task{}; }  // 协议开关
    std::suspend_always initial_suspend{};
    std::suspend_never final_suspend() noexcept { return {}; }
  };
};
// new 的查找序：promise_type 的 operator new > 全局 new；声明成
// operator new(size_t, Args...) 还能吃到协程实参（把分配器随参数传入）
```

- 分配失败协议：promise 定义 `get_return_object_on_allocation_failure` + noexcept 的 operator new，才走"空返回对象"分支，调用方按 `explicit operator bool` 判空；否则 new 抛 bad_alloc 直接炸——嵌入式/高负载服务**必须**实现这条协议（65 节）。
- 审计手段：promise 内打点统计帧分配（new/delete 计数），或 LD_PRELOAD 拦截；预算按"每个协程一次堆分配"做，HALO 省掉是白捡，不是设计依据。

## 63. 协程参数生命周期：按值捕获纪律

- 规则本体：协程**参数在首个挂起点之前**被拷贝/移动进帧；引用参数进帧的是引用本身——挂起后调用方栈帧/临时对象销毁，协程醒来读到的就是悬垂引用。
- 事故形态（按命中率）：①`task f(std::string_view s)` 挂起后才用——调用方 `std::string` 临时全表达式结束即亡；②`f(obj.get_name())` 传回的临时 string；③成员协程的 this（"引用参数"的隐藏形态）——对象析构后协程还在跑 = use-after-free。

```cpp
Task handle(std::string_view path) {     // 反例：view 是"借"，帧里存的也是借
  co_await fs.read(path);                // 挂起点之后才用 → 悬垂
}
Task handle2(std::string path) {         // 正解：按值进帧，与调用方解耦
  co_await fs.read(path);
}
// 纪律：参数要么按值，要么进函数体立刻拷进局部再挂起；
// 大对象传 unique_ptr/shared_ptr 转移所有权，别传引用
```

- 库作者防线：promise 的 `await_transform` 拒绝不安全类型——`await_transform(std::string_view) = delete;` 让"跨挂起点借引用"从 review 事故降级为编译错误（cppcoro 有先例）。
- 例外契约：参数只在**首个挂起点之前**消费则引用安全——但这条契约极脆（后来者加一行 co_await 就破），纪律上仍按值；this 需要存活就 shared_from_this（46 节）或拷贝所需数据。
- 隐藏次序：promise 构造**先于**参数拷入帧，且 promise 构造函数吃到的是**原始实参**（不是帧内副本）——promise 里按引用存这些实参必悬垂；这条次序是 promise ctor 带参设计的暗坑，库作者要文档化。

## 64. symmetric transfer：协程链的栈溢出防线

- 病灶：协程 A 在 final_suspend 里 `handle.resume()` 恢复 B——这是普通**调用**，B 压在 A 之上、A 压在调用者之上；链长 1 万个协程 = 1 万层 C++ 栈，8MB 限额直接爆——"协程省栈"的话术在链式场景是反向的。

```cpp
struct TransferAwaiter {                 // 对称转移原语：返回"接棒者"给 resume
  std::coroutine_handle<> next;
  bool await_ready() { return !next; }
  std::coroutine_handle<> await_suspend(std::coroutine_handle<>) {
    return next;                         // 编译为尾跳转（jmp），栈深 O(1)
  }
  void await_resume() {}
};
// final_suspend 里 co_await TransferAwaiter{continuation_}：
// 写 handle.resume() 的旧式接棒，栈深 = 链长——10 万级任务链压测必爆
```

- 检测：ASan/valgrind 报 stack overflow 且调用栈是一条 resume 链 = 病征；修复后长链压测栈深恒定；任务库（cppcoro/folly/stdexec）早把对称转移内置——**自己手写协程库是唯一必须亲自懂它的场景**，用库则记住判别法："final_suspend 里的 co_await 是转移、resume() 是调用"。
- final_suspend 双条款：①必须 noexcept——协程体已结束，这里抛异常没有捕获者，直接 terminate；②接棒人负责 destroy（67 节句柄所有权），写错就是双销毁或泄漏。
- continuation 纪律：保存的接棒 handle 只在 final_suspend 用一次；中途修改 continuation（等待时被取消）要走原子交换并定义胜者，否则双 resume = UB。

## 65. promise_type 设计模板：无堆分配协程

- 四件套模板：①promise 内 `operator new/delete` 打到**尺寸分桶的线程本地池**（帧尺寸是编译期常量，按 16/32/64B 桶对齐）；②`get_return_object_on_allocation_failure` + noexcept new 实现失败路径（62 节协议）；③initial_suspend 决定惰性/立即；④final_suspend 决定接棒/自毁（64 节）。

```cpp
struct Task {
  struct promise_type {
    static void* operator new(std::size_t sz) noexcept;          // 线程本地自由链
    static void  operator delete(void* p, std::size_t sz) noexcept;  // 按尺寸回桶
    Task get_return_object_on_allocation_failure() { return Task{}; }
    std::suspend_always initial_suspend{};      // 惰性：造帧即回，不自动跑
    FinalAwaiter final_suspend() noexcept { return {}; }  // 对称转移续命
  };
  explicit operator bool() const { return static_cast<bool>(h_); }  // 判空协议
};
```

- Pigweed 模式（嵌入式无堆）：帧全部来自**静态池**，"最多 N 个并发协程"在编译期就是资源预算，池满返回空任务——分配从"可能失败的运行时服务"变成确定性容量规划。
- 线程本地池的坑：协程 A 线程创建、B 线程销毁 → 帧回错桶，跨线程复用引爆伪共享与所有权（21 节、67 节）；契约写死"谁 resume 谁 destroy"，池带线程亲和校验（debug 断言桶归属）。
- 尺寸预算：`sizeof(协程帧)` 可静态求出，一帧常 64-200B（随局部变量数增长）——10 万常驻协程 = 十几 MB 池；架构评审带上这个数，"协程轻如函数"是无池时代的幻觉。

## 66. 协程异常路径：unhandled_exception 与 noexcept 纪律

- 路径解剖：协程体内异常未被 catch → 被帧捕获 → `promise.unhandled_exception()` 被调用 → 协程走到 final_suspend，**帧还活着**（异常存在帧里）——把它取出来再抛是你的 promise 的义务，不是语言行为。

```cpp
struct promise_type {
  std::exception_ptr eptr;                 // 仓库：存，别扔
  void unhandled_exception() { eptr = std::current_exception(); }
  FinalAwaiter final_suspend() noexcept;   // noexcept 必须写：无捕获者
};
struct TaskAwaiter {                        // co_await 侧：还原异常
  std::coroutine_handle<promise_type> h;
  void await_resume() {
    if (h.promise().eptr) std::rethrow_exception(h.promise().eptr);
  }                                         // 语义对齐普通函数抛出
};
```

- terminate 三连：①unhandled_exception 里再抛（eptr 存储失败/手滑）→ 无处可接；②final_suspend 非 noexcept 且抛；③unhandled_exception 是**空实现** = 静默吞异常——比 terminate 更毒，事故在下游千里之外爆。
- fire-and-forget 的 unhandled_exception 必须落地成日志 + 计数（无处 rethrow），库留 set_error_handler 钩子（asio 模式）；"把协程整个标 noexcept 来要求不抛"会把一切违约升级成 terminate——正确姿势是 promise 显式分类（哪些搬运、哪些 terminate）并写进文档。
- review 红线：每个 promise_type 的 unhandled_exception 必须有可见行为（存储/终止/钩子三者之一）；"看起来没人会抛"不是理由——被调函数的完整闭包你审不完（86 节同款纪律）。

## 67. detached task 事故：协程句柄所有权

- 句柄本质：`coroutine_handle` 是**裸帧指针**——不拥有帧、不数引用、不自动销毁；`destroy()` 两次 = double free，从不 destroy = 泄漏，resume 一个 done 的协程 = UB；把它当智能指针用是协程事故第一来源。
- 双重销毁经典链：final_suspend 返回 suspend_always（等 continuation 收尸）+ 调用方又用"析构即 destroy"的 RAII 包装包住 → continuation 接棒后再 destroy 一次。

```cpp
// 所有权三态：每个 handle 变量必须能回答"谁负责 destroy"
struct Task {                    // (1) owned：RAII 独占，析构 destroy
  std::coroutine_handle<> h_;
  ~Task() { if (h_) h_.destroy(); }
  Task(Task&& o) noexcept : h_(std::exchange(o.h_, {})) {}
  explicit operator bool() const { return static_cast<bool>(h_); }
};
// (2) detached：已移交给调度器/事件循环，原持有者放手——注释必须写明
// (3) borrowed：只在别人所有权期内 resume，禁止 destroy
```

- detached 纪律：协程必须自我了断（final_suspend 返回 `std::suspend_never`，编译器在 co_return 落地后自动 destroy 帧），且"没有人在等我"写进注释；跨线程移交 handle 必须有 happens-before——对称转移自带（64 节），裸传指针没有。
- 结构化并发是正解：父 task 用 when_all/sink 收拢子协程，作用域退出 = 全部完成（folly coro、std::execution 的默认形态）；detached 只留给进程级 daemon 任务并挂全局清理清单。
- 审计：review 扫 `coroutine_handle` **成员变量**——每个必须带三态注释；裸 handle 出现在公共 API 签名直接打回。

## 68. std::generator 与协程库生态选型

- std::generator 三条语义：①**惰性**——调用生成器函数只造帧不执行，首次 `begin()` 才跑到第一个 co_yield；②默认按**引用**产出——`generator<T>` 的产出类型是 `T&`，要只读给 `generator<const T>`，要移动给 `generator<T&&>`；③单遍 input_range，二次 begin 与拷贝都是 UB。

```cpp
std::generator<const std::string> lines(std::filesystem::path p) {
  std::ifstream in(p);
  std::string line;
  while (std::getline(in, line)) co_yield line;   // 按引用借出 const 视图
}
for (auto&& s : lines("a.txt")) {                  // 天然接 views 管道
  auto head = std::string_view{s} | std::views::take(10);
}
// 嵌套委托：co_yield std::ranges::elements_of(inner);  —— 对称转移接棒
// （64 节），比手写 for(x:inner) co_yield x 少一层栈、快一截
```

- 析构即清理：提前 break 的 generator，析构时销毁帧并析构帧内局部（RAII 在协程里成立）——但别依赖"走到末尾"做副作用（flush 落在末尾 = break 路径漏掉）。
- 库选型表：标准库只有 generator（C++23；GCC 14 已齐、MSVC 跟进、libc++ 节奏偏后——`__cpp_lib_generator` 探测）；task/when_all/with 取消 → cppcoro（停滞，仅参考实现）、folly::coro（工业级、依赖重）、libunifex/stdexec（senders 路线，97 节）、asio awaitable（IO 密集默认项）。
- 选型先看**取消模型**（stop_token 是否贯穿全链）与**分配策略**（能否接 65 节池），语法甜度排最后；generator 与 task 库可以共存——前者管生产数据，后者管编排。

## 69. CPU 缓存层级：行粒度、预取与局部性改造

- 延迟表（刻进直觉）：L1 ~4 周期（~1ns）、L2 ~12 周期、L3 ~40 周期（~10-20ns）、DRAM ~60-100ns（跨 NUMA 再翻倍）——一次主存访问 = 100 次寄存器访问；一切数据结构改造都是在这张表上算账。
- 行粒度：硬件按 64B 行搬运——遍历 128B 的 struct 数组但只用一个 8B 字段 = **带宽浪费 16 倍**；SoA/热冷字段拆分把"每行利用率"提上去是第一刀。

```cpp
struct Particle { float x,y,z,pad; float vx,vy,vz,ax,ay,az; };  // AoS：128B/粒
// 力学积分只碰 v/a、渲染只碰 x/y/z → 拆成两个数组：
struct SimState { std::vector<Vec3> pos, vel, acc; };  // SoA：一行 5 个 Vec3
// 指针追赶是预取杀手：链表/树节点随机分布 → 预取器抓瞎；
// B+树/Eytzinger 布局让"下一次访问"大概率落在已预取的行内
```

- 预取器现实：硬件预取擅长**规则步长**与流，随机指针链无能为力；软件 `__builtin_prefetch` 只在"能提前 N 次迭代预知地址"的算法（B+树搜索、哈希探测）有净值，多数场景是负优化（多抢带宽）。
- NUMA 条款：跨 socket 访问 1.5-2 倍延迟 + 互联带宽上限；服务侧 numactl 绑节点/交错策略要显式化，容器 cpuset 截断拓扑后"无感知跨节点"是性能事故高发区。
- 度量序：`perf stat -e cache-misses,LLC-load-misses` 先看千指令比例，perf record 再定位行；没有计数器证据的"局部性优化"先上 perf 过一遍再信。
- TLB 记入账：大堆随机访问的 TLB miss 同样致命（4KB 页一表项）——大数组 huge page 化收益常被忽略；但 THP 后台合并（khugepaged）有偶发停顿，低延迟系统显式选 madvise 模式。

## 70. 分支预测：[[likely]] 的真实价值与误导

- 真实价值：`[[likely]]/[[unlikely]]`（C++20）只影响**代码布局**——把"预期不走"的路径挪出热区，改善 I-cache 命中与取指带宽；它**不改变预测器**对运行时数据的判断。错误路径标 unlikely 是正收益；给五五开的数据分支标 likely 是自欺。

```cpp
if (!ptr) [[unlikely]] {                   // 冷路径外移：正当用法
  log_error(...); return std::unexpected(Error::Bad);
}
auto v = table[key & mask];                // 无分支化：if 变查表/算术
// cmov 的暗面：两个分支体都会被执行——某一侧有除零/解引用/昂贵副作用时
// 编译器拒绝 cmov 或生成后引入故障；无分支化的前提是"两侧纯净"
```

- 预测器底色：TAGE 类预测器对规律分支 95-99% 命中，mispredict 罚 ~15-20 周期（流水线冲刷）；值不值得动手看 `perf stat` 的 branch-misses/branches（>5% 才立项）。
- 无分支化清单：查表、bool 参与算术、min/max、位技巧；前提是两侧无副作用不 fault——cmov 两侧都算，可观测语义必须等价。
- 微基准陷阱：单分支循环的 branch-misses 趋近零，"分支消除优化"在基准里完美、生产随机数据下归零——**基准必须喂真实分布**（78、106 节）。
- 概率从哪来：手写 [[likely]] 常来自直觉，PGO 的分支概率来自生产（71 节）——两处都写时 PGO 赢；纪律：likely 只标"语义上几乎必然"的路径（错误分支），统计倾向交给 PGO。

## 71. 内联工程：attribute、LTO 与 PGO 的边界

- 两个现实：编译器基本无视 `inline` 关键字（那是链接语义，89 节）；`always_inline` 是真命令——只用在跨擦除边界的热访问器、一行转发函数；滥用 = I-cache 崩盘，**先有 perf 证据再上**。跨 TU 内联必须 LTO：full LTO 全程序一体（内存/时间大），ThinLTO 按摘要两阶段、可扩展——大仓库默认 ThinLTO。

```bash
# 证据链三步：
clang++ -O2 -Rpass=inline -Rpass-missed=inline ...   # 谁没被内联、为什么
# PGO：插桩（-fprofile-generate → 跑真实负载 → -fprofile-use）精确；
# 采样（AutoFDO：perf 采样 + create_llvm_prof）省事但有偏
# 分布式构建配 -ffat-lto-objects 或统一 ThinLTO 缓存（键必须含 LTO 模式）
```

- PGO 的真实杠杆（按收益排）：热代码聚簇布局（I-cache）、分支概率自动化（喂对 likely 的正规军）、虚调用**推测性 devirt**（79 节）——比手写内联属性高一个量级；代价：profile 随代码变更陈旧，CI 要有重训流水线。
- 陷阱两条：PGO 插桩与 sanitizer 覆盖**不能同 build**（都吃 profile 机制，配置拆 job）；ccache/sccache 的缓存键要包含 LTO/PGO 标志，否则缓存投毒静默错编。
- 停止线：内联率不是 KPI，I-cache-misses 与剩余 call 数才是；"热点全 always_inline"的最常见结局是变慢（.text 膨胀 → 取指失禁）。
- MSVC 对偶：always_inline → `__forceinline`；ThinLTO → `/GL + /LTCG`，PGO 工具链（pgosweep 一族）完全另一套——属性宏与构建脚本双封装，别让语义在第三家漂移。
- 冷路径标注：noinline 是 always_inline 的镜像——给"要下断点/栈帧要稳定"的代价函数用（防止优化器把小函数折进调用链）；两侧都应是少数派，中间地带归编译器的成本模型。

## 72. 分配器战争：malloc 内幕、arena 与线程缓存

- glibc malloc 内幕：per-thread arena（线程各占堆区减锁碰撞）+ tcache（2.26 引入的线程本地空闲缓存）+ fastbin/smallbin 分级；**长跑服务的碎片**多来自 arena 随线程增删只增不减与非对称尺寸分配——RSS 只涨不降的排查从 `malloc_info()` 与 arena 计数开始。
- 替代品架构：tcmalloc/jemalloc 的核心是 **size class + 线程本地缓存**——分配向上取整到 class（24B 请求实付 32B），换 O(1) 无锁快路径与近零碎片；CPU 密集服务换 jemalloc 常见 RSS -20~40%，但先测后换。

```bash
# 分配风暴取证三件套
LD_PRELOAD=libjemalloc.so.2 + jemalloc profiling → jeprof 火焰图
heaptrack / perf record -e kmem:mm_page_alloc*    # 谁在按页吃内存
MALLOC_ARENA_MAX=2        # 容器里防 arena 增生（glibc），代价是锁竞争回来
# 高频小对象（>1e6 alloc/s）：换池/arena/deque-of-chunks，见 73 节
```

- size class 纪律：热点结构体尺寸踩进台阶（24→32、40→48）= 白送 25% 内存带宽与缓存占用；`static_assert(sizeof(Foo) <= 32)` 把尺寸钉进预算，pack 字段比换分配器先做。
- 分配风暴病例：每请求 50 个小 string/function/节点 → malloc 自旋时间超过业务逻辑；修法按序：对象池/pmr arena（73 节）→ 打平成单块分配 → 消灭分配（栈缓冲、SSO 落位、74 节）。
- 全局 new 重载与 LD_PRELOAD 是系统级干预——服务里优先 per-组件资源（73 节），全局替换是最后手段。
- 热与冷的不对称：free 也不免费——大批短命对象即使 tcache 全命中，**内存带宽**照付；对象池复用（根本不进 malloc）是把"分配+释放"两次账一次结清的唯一路径（73 节）。

## 73. pmr 实战：多态分配器与 monotonic_buffer_resource

- 心智模型：`std::pmr::vector<T>` 是**容器自带资源指针**的类型——与 `std::vector<T>` 是不同类型，互传要走显式转换/迭代器拷贝；"把 vector 换成 pmr::vector"是接口级改动，不是实现细节。
- monotonic_buffer_resource：deallocate 是 **no-op**——内存只涨不还，destroy() 整体释放；它是"作用域 arena"：请求解析、AST 遍历、帧渲染这类"一批分配同生共死"场景的主场（2-5× 分配吞吐常见）。

```cpp
struct Parser {                             // 每请求一块栈缓冲兜底
  Parser() : arena_(buf_, sizeof buf_), pool_(&arena_), nodes_(&pool_) {}
  char buf_[16 * 1024];                     // 栈上：热路径零 malloc
  std::pmr::monotonic_buffer_resource arena_;   // 不够时向缺省上游追加
  std::pmr::unsynchronized_pool_resource pool_{&arena_};
  std::pmr::vector<Node> nodes_;            // 节点全走 arena
};                                          // 析构：arena 整体放弃，无逐个 free
```

- 传播语义三坑：①pmr 容器 copy 构造**默认不传播资源**（拷出来挂到缺省资源，性能直觉失灵）；②`unsynchronized_pool_resource` 单线程专用，跨线程共享 = data race；③上游链要有向（栈 buf → global），环状/悬垂上游 = 崩在 destroy。
- 高水位认知：monotonic 里 `clear()` 不还内存，峰值内存 = 峰值分配——arena 生命周期对齐**事务边界**；长驻容器别用 monotonic，用 pool_resource。
- 落地序：先注入 `memory_resource` 接口（测试用统计资源验证泄漏/峰值），再换 monotonic/pool；`set_default_resource` 全局替换是银弹级副作用，最后才考虑。
- 类型分裂陷阱：pmr::vector 与 std::vector 是不同类型——序列化/反射按类型注册时静默裂成两套，重载与比较也各走各的；跨层传递要显式统一，别让"实现细节"渗成接口分裂。

## 74. SSO 与小缓冲优化：string/vector 的实际收益

- 三家 SSO 尺寸表（64 位）：libstdc++ `std::string` 32B、内联 15 字符；libc++ 24B、内联 **22** 字符；MSVC 32B、内联 15 字符——"短串零分配"的阈值跨库不同，`sizeof(std::string)` 参与逻辑 = 移植 bug（4 节）。
- SSO 字符串的 move 是**逐字节拷贝**（没有堆指针可偷，28 节）——短串 move 并不比 copy 便宜多少；vector 没有任何 SSO（标准容器仅 string 独享），小 vector 的堆分配逃不掉。

```cpp
static_assert(sizeof(std::string) == 32);   // 只在钉死 libstdc++ 的构建里合法
// 小 vector 的现实：标准库没有 small_vector——热路径"大概率小"的场景换
// folly::small_vector<T, N> / boost::container::small_vector<T, N>
// 或 std::array<T, N> + 计数：省的是"每次构造一次 malloc + 指针追逐"
// 热字符串初始化成 constexpr（static/常量区）绕开反复堆分配
```

- 判据与测量：`capacity() <= 15`（按库）意味着 data() 在对象内部——栈上字符串数组、哈希 key 因此缓存友好；日志/路径拼接超过 SSO 阈值就转堆，热点把常量串提出来。
- ABI 暗雷：libc++(24B) 与 libstdc++(32B) 的 string 混布 = 崩在 so 边界（90 节）；跨 ABI 一律 char* + len；`__cpp_lib_string_resize...` 类探测不救布局差异。
- 不要按字节赌：凡序列化偏移/联合体布局依赖 sizeof(std::string)，要么 static_assert 钉死三编译器，要么改 string_view + 显式长度——这两个是唯一安全选项。
- data() 的用法陷阱：SSO 期间 data() 指向对象内部（栈上）——把 &s[0] 交给"长期保存"的 C API 是悬垂预支；跨边界字符串一律拷贝或走 91 节 char*+len 协议。

## 75. vector 扩容策略与 reserve 纪律

- 增长因子分裂：libstdc++/libc++ **2×**、MSVC **1.5×**——同一段代码在三家的重分配次数与内存峰值不同；按"最多 log₂(N) 次扩容"预算，别按某家的常数；1.5× 的辩护（旧块可复用）与 2× 的摊还优势都是对的，你只需要知道自己落在哪家。

```cpp
std::vector<Row> rows;
rows.reserve(estimate());                   // 有量级估计就一次到位（宁多勿少）
for (auto& r : src) rows.emplace_back(r);   // 原地构造，避免临时+移动
// 危险组合：Row 的移动构造漏标 noexcept → 扩容退化为逐元素拷贝
// （move_if_noexcept 机制：不是"慢一点"，是深拷贝整个数组）
static_assert(std::is_nothrow_move_constructible_v<Row>);
```

- move_if_noexcept 是 C++ 最贵的缺省：扩容迁移在"noexcept move 否则 copy"间选择——**漏标 noexcept = 扩容全程深拷贝**，还顺手破坏强保证的可行性（85 节）；迁移老代码时第一件事就是给 move 补 noexcept。
- reserve 三纪律：①循环前能估就 reserve；②reserve 后 push_back 摊还 O(1)，别用 resize+operator[] 预扫（白付一次值初始化）；③`shrink_to_fit` 是请求非命令（可能整块重建拷贝），冷启动收尾一次可以，热路径禁用。
- 概念边线：`reserve` 只改容量不构造元素，`resize` 构造元素——两者混用是越界/未初始化读的常见来源；`clear()` 不还容量，跨请求复用的容器 capacity 高水位常驻（可当池用，也是内存账目里的隐形项）。
- 峰值条款：扩容的迁移窗口内**旧 + 新两份内存同时存在**，峰值翻倍——超大 vector 的扩容要错峰（预分配、分片、或 pmr 上游限额）；这是容量规划里最容易被忽略的尖峰。

## 76. 迭代器失效图谱与元素重排算法选择

- 速查表（背"哪些失效"）：vector：插入点**之后**失效，扩容**全部**失效；deque：两端插入失效**全部迭代器**（元素引用仍稳），中间插入连引用全灭；list/map：只有被删元素；**unordered_map：rehash 失效全部迭代器，但指针/引用永活**（唯一"指针稳"的大容器，98 节用它反将一军）。

```cpp
// erase 循环的三种正确姿势（禁止手写 ++it 与 erase 混排）
for (auto it = v.begin(); it != v.end();)
  it = pred(*it) ? v.erase(it) : std::next(it);  // O(n²)：list 才配用
v.erase(std::remove_if(v.begin(), v.end(), pred), v.end());  // C++20 前惯用
std::erase_if(v, pred);                    // C++20：所有容器都有自由函数版
// stable_partition 保序但可能分配临时缓冲；partition 原地但打乱相对序
```

- remove-erase 本质：`remove_if` **不删元素**——把保留元素前移，尾部留"有效但未指定"的尸体，erase 才缩 size；对裸指针容器用 remove-erase = 泄漏（尸体里的指针没 delete）——先显式 delete 再 erase。
- 自插入家族：`v.push_back(v[0])` 扩容时读已失效的源；`v.emplace_back(v.size())` 传引用/指针指向自己同理——先取值拷贝或先 reserve（31 节）。
- 算法选择：稳定序需求用 stable_partition/stable_sort（有分配代价）；相邻去重 `unique`+erase、全量去重先 sort（O(n log n)）或哈希（大容器更快）；`partition` 后两段继续并行处理是免费的"零拷贝拆分"。
- unordered_map 特条款：遍历中 insert 触发 rehash，**本循环的迭代器全灭**——边遍历边收集、循环外插入；reserve(n) 已含 max_load_factor 换算，装载预算一次到位。
- deque 的中庸陷阱：两端 O(1) 但随机访问多一层块表间接、中间插入 O(n)——“用 deque 通用化 vector”每元素多付间接与 miss（69 节的账），先测后换。

## 77. string_view 悬垂：生命周期与调用边界

- 规则一句话：string_view 是**借条的拷贝**——多抄几份借条不会让债主多活一天。三类高危：①`std::string_view sv = std::string("x");` 临时立刻死，sv 指坟；②`return sv.substr(...)` 把函数内临时的 view 往外带；③**把 view 存成成员/容器元素**——调用点看似传值，生命周期从此与调用方代码长跑绑定。

```cpp
void parse(std::string_view sv);            // 参数位 = 安全（临时活到全表达式末）
std::string_view name() const { return name_; }  // 返回位 = 借 this：this 亡 view 亡
struct Cache { std::string_view key; };     // 存储位 = 事故温床：谁担保源的寿命？
// 变体反模式：set.insert(std::string_view{name + std::to_string(id)});
// 临时 string 语句末析构 → set 里是悬垂 view
```

- 成员 view 的隐性破坏者：`const std::string&` 成员上造 view，之后任何一次**非 const 操作**触发扩容/SO 逃逸 → data() 搬家，view 借的老地址裸奔；"const 引用延长临时生命"只对引用本身成立，view 是值拷贝，不享受这条。
- API 设计三条：①入参"只读不存"用 string_view（零拷贝、通吃 string/literal/char*）；②返回值默认 `std::string`，确要 view 就注释借用来源与并发条款；③对外 C ABI 一律 char* + len + 释放器（90 节），view 不出进程。
- 半衰家族：`span<T>` 同病；ASan 抓得到堆上悬垂，**栈/静态区的悬垂只能靠 review 清单**（107 节：view/span 成员字段 = 红笔项）；配套工具 `clang -Wdangling-gsl`（-Wlifetime 一族）能抓一部分，别当完备。
- 悬垂后"还能用"的骗局：string_view 的比较是 memcmp 语义不是指针比较——悬垂 view 常常照样比较相等、照样读出旧内容（堆没被复用），事故延迟到千里之外才爆；这就是 107 清单把它设为红笔项的原因。
- 编码与终结符敏感：view 不保证 NUL 结尾、不携带编码语义——被下游当 C 串用（printf %s / strcpy）是"越界 + 语义错"双事故；出边界前 to_string() 落拷贝，合同里写明。

## 78. 测量方法论：perf、tracy 与微基准陷阱

- 三层分工：perf（系统级：周期、缓存、锁、off-CPU 阻塞）、tracy（应用级：帧/请求时间线、锁等待、zone 嵌套，打点 + 网络实时连）、heaptrack/jeprof（内存级）。**先 perf 后 tracy**：先知道"慢在哪类资源"，再知道"慢在哪个业务阶段"，反着做是在时间线里捞针。

```bash
perf record -F 99 -g --call-graph dwarf ./bench   # DWARF 展开比 fp 准
perf report --no-children --sort=overhead         # 生产构建关省略帧指针的坑
# off-CPU（睡眠等锁/IO）不是 perf 默认视野：
perf record -e sched:sched_switch -p <pid>        # 或 bcc 的 offcputime
# 噪声治理：taskset 绑核 + performance governor / 关 turbo；报 p50 与 p99，
# 只报 mean 的微基准结论一律视为未完成
```

- 微基准四大陷阱（比工具更值钱）：①**死代码消除**——结果没人消费，编译器删光整段循环（DoNotOptimize/asm 假消费）；②**常量折叠**——输入编译期已知，测了个寂寞（运行期造输入）；③**内联逃逸**——被测函数被内联后测的是调用者；④**缓存/分支状态失真**——基准里全热、生产里全冷（工作集尺寸要仿真）。
- 流程纪律：测量→假设→单变量改动→同条件复测；收益必须能归因到计数器（IPC 升？LLC-miss 降？），归因不了的"变快"按噪声处理。
- 在线常态：perf 低频 attach 生产进程（-F 99 几乎无感）、tracy 分级打点（zone 预算约束）；**持续剖析（每分钟采 10 秒）比出事后拉 profiler 有价值一个数量级**——事故现场是瞬时的，趋势是持续的。
- 工具成本入账：tracy 每 zone 有百 ns 级开销——打点密度是预算出来的，热循环里逐条打 zone 是测锤效应；perf 的 -F 99 是每秒 99 样本，成本可忽略但覆盖也有限，深挖热点时换事件级采样。

## 79. 去虚拟化：final、CRTP 与手动分派

- devirt 触发条件：静态类型已知（栈对象/具体类型）、类型集合封闭（`final` 关死派生表）、编译器有证据（`-fdevirtualize` 类型分析、LTO 全程序、PGO 推测内联）。**给类标 final 是一秒钟的免费 devirt**——但它是源代码级断裂（下游不能再派生），库的稳定性政策要过审（95 节）。
- 推测性 devirt（PGO）：编译器插入"类型守卫 + 内联快路径 + 间接慢路径"——对 99% 单实现的虚调用收益接近硬内联；这解释了 **PGO 收益的大头常在 devirt**（71 节）而非分支布局。

```cpp
struct Circle final : Shape { ... };       // final：调用点敢直接静态绑定
// 手动分派：类型集合封闭且热时，tag switch + 静态分支（编译器转跳表）
double total(std::span<const Ref> v) {
  switch (v.front().kind()) {              // kind() 返回 enum，无间接跳转
    case Kind::Circle: return sum_impl<Circle>(v);
    case Kind::Rect:   return sum_impl<Rect>(v);
  }
  std::unreachable();                      // 87 节：封闭集合的正确收尾
}
```

- CRTP 的账：静态多态 = 每个派生类一份模板实例（代码体积、I-cache、编译时间），换零虚表零间接；**接口单实现且不在热路径 → 虚函数别折腾**；多实现 + 热路径 + 类型可编译期枚举 → CRTP/tag dispatch 值回票价。
- 手动分派现代形态：enum tag + switch（最直白）、`std::visit`（variant 分派，常编译成表但带异常分支）；测量指标是 branch-misses 与间接跳转计数，不是美学。
- 跨 so 的虚调用永远 devirt 不掉（类型在别的编译单元/别的 so）——对外接口先 pimpl/函数指针显式化（88 节），内部再谈 devirt。

## 80. SIMD 纪律：std::simd 前夜与手写向量化审计

- 自动向量化的拦路虎清单：可能别名（`a[i]` 与 `b[i]` 无法证明不重叠 → `__restrict`/局部拷贝破）、跨迭代依赖、非单位步长、循环内调用/early exit/除法、**逐元素分支**；先消拦路虎，再谈 SIMD 库——多数"没有向量化"的循环缺的是可证明性不是指令集。
- 审计手段：`-fopt-info-vec-all`（GCC）/ `-Rpass=loop-vectorize -Rpass-missed=loop-vectorize`（Clang）打印"哪个循环为什么没向量化"；Compiler Explorer 看汇编的 vmov/vfmadd 家族——**没审计过汇编的向量化优化不算完成**。

```cpp
void axpy(float* __restrict y, const float* __restrict x, float a, std::size_t n) {
  for (std::size_t i = 0; i < n; ++i) y[i] = a * x[i] + y[i];   // -O3 自动向量化
}
// std::simd（C++26，P1928）：可移植 SIMD 值类型，GCC libstdc++ 先行
//（std::experimental::simd 已服役多年），MSVC/libc++ 未齐——宏探测再上
// 注意：simd<T>::size() 是目标机器的自然宽，算法按 size() 分块，别写死 8/16
```

- std::simd 定位：降"手写 intrinsics 的可移植税"（一份代码编 AVX/NEON/SVE），不替你做算法——gather/scatter（随机索引）在多数 CPU 是慢路径，**数据布局改造（69 节 SoA）优先于 intrinsics**。
- 手写 intrinsics 纪律：只在自动向量化失败 + 测量证明热点后上；优先编译器内建与 std::bit_cast 打包；对齐按 32/64B 拳头打（22 节）。
- 数值陷阱：`-ffp-contract=fast` 下 a*x+y 自动合 FMA（精度/性能双变）；`-ffast-math` 破坏 NaN/Inf 语义，浮点敏感代码禁用——SIMD 审计清单必须有"浮点模式"一栏。
- 强制向化的代价：`#pragma clang loop vectorize` / GCC `#pragma GCC ivdep` 是**关掉编译器合法性检查**——别名/依赖错了就是静默错编；指令只应出现在已审计汇编的循环上，与 87 节 assume 同级的自伤武器。

## 81. 异常成本模型：零成本神话的边界与 SEH

- "零成本"的准确含义：Itanium ABI（GCC/Clang 全平台 + MSVC x64）**表驱动**——happy path 零额外指令，代价全在代码体积（LSDA 表 + landing pad，典型 +5~15% .text）与**抛出路径**（一次 throw+catch ~1-10 μs 量级，随栈深线性涨）；"异常只用于异常情况"的全部论证就在这两个常数。
- 对比组：Windows x86-32 的 funclet 模型有**进入 try 块的运行时开销**；MSVC x64 已是表驱动 SEH——同一段代码在 32 位 Windows 热路径上的异常税与 Linux 不同量级（4 节跨平台账）。

```cpp
// 高频失败路径的量化判据：失败率 f、每次 throw ~2μs
// f=1e-6 → 异常税可忽略；f=1%（百次 1 次）→ 平摊 20ns/op + 尾延迟尖刺
// → expected<T,E> 接管（83 节）
static double parse_or(const char* s, double def) noexcept {
  try { return std::stod(s); } catch (...) { return def; }  // 病灶：解析失败是常态
}
```

- 展开路径的隐性成本：栈展开要跑每层析构 + 查 LSDA——**深栈 + 重析构类型的 throw 是 P99 尾延迟尖刺的隐藏凶手**；低延迟/实时系统禁异常不是教条，是尾延迟预算。
- bad_alloc 特别条款：new 失败抛 bad_alloc——"OOM 即死"的服务里不如 `new (std::nothrow)` + 显式失败处理，把 OOM 从异常路径挪进检查路径（84 节）；同理 `std::terminate` handler 落栈转储（86、105 节）。
- 度量：异常计数进监控（每秒 throw 数 > 0 = 架构问题红灯）；-fno-exceptions 的 STL 语义突变见 82 节。
- noexcept 与展开的联动：throw 穿越 noexcept 帧 = terminate 而非"跳过"——内联/重构把调用点折进 noexcept 函数时，"可捕获"静默变"进程崩溃"；86 节的审计要跟着代码变更重跑。

## 82. -fno-exceptions 纪律与错误通道设计

- 开关的真实语义：`-fno-exceptions` 不是"删掉异常"而是"**抛出即 terminate**"——bad_alloc、`vector::at` 越界、`optional::value` 空值、`bad_cast` 全部变成进程死刑。选它的前提：进程死了可接受且死因可诊断（嵌入式/游戏/部分交易系统的常态）。
- 全局纪律三条：①自己代码不 throw 不 try（catch 块直接编不过）；②依赖库要么同构建，要么明确"它内部该抛的地方会 terminate"——**库的异常策略必须进包元数据**（vcpkg/Conan feature，11 节）；③错误通道**只有一种**（expected<T,E> 或状态码），"这个错误走哪条道"必须只有一个答案。

```cpp
// no-exceptions 世界里 STL 的红线：全部变 terminate
// vector::at / map::at / optional::value / any_cast / typeid(*ptr) / bad_alloc
std::expected<Conn, Err> dial(const Endpoint& ep) noexcept;   // 主错误通道
[[nodiscard]] auto r = dial(ep);
Conn c = r ? std::move(*r) : panic(r.error());  // 项目级 unwrap：内错即崩带栈
```

- ABI 面暗雷：no-exceptions 构建的库被 exceptions 程序链接 = 同一函数两个方言——**inline 头代码在两侧各自展开**，库里该抛的位置在你的构建里静默 terminate；混链策略（全禁 or 全开 + 边界翻译）要写进构建系统文档，CI 里 -fno-exceptions 全量编译是唯一防线。
- 三层通道设计：expected 管业务失败、assert 管内错防御、panic 宏管"不可能"——每层带日志与转储；`std::terminate` handler 保底（release 也留 `-fno-omit-frame-pointer`，105 节符号化依赖它）。
- 迁移现实：老库混合期允许"边界 catch-all 转 expected"过渡，但新代码一律通道化；红线函数清单（at/value/stod 一族）在 review 清单里标黄（107 节）。
- MSVC 语义矩阵：/EHsc（同步异常，标准姿态）、/EHa（连 SEH 一起 catch——**catch(...) 会吞访问违例**，把硬件错误伪装成软件异常，几乎总是错）、无 /EH* 即 no-exceptions 等价；异常 flags 表跨平台项目必写进构建文档。
- assert 与 no-exceptions 的关系：assert 不是异常机制、照常工作，但 NDEBUG 下消失的副作用（87 节）在 no-exceptions 世界是双倍红线——那可能是唯一防御层。

## 83. std::expected 单子操作与组合纪律

- 四个原语：`transform`（成功值映射 T→U）、`and_then`（成功续链，返回 expected）、`transform_error`（错误值映射）、`or_else`（失败续链）——**左链值、右链错、链中即传播**，三层缩进的 if 面条被一行管道消灭。

```cpp
std::expected<Row, DbErr> load(std::string_view id);
auto out = load("a1b2")
  .and_then(validate)            // 签名必须同走 expected 通道（返回 expected）
  .transform(&Row::summary)      // 值域映射，不碰错误域
  .transform_error(wrap_layer);  // 错误域归一（见下）
// 反模式：if (auto r = load(..)) { auto s = render(*r); if (s) ... else ... }
// —— 嵌套 if 就是单子操作要消灭的东西
```

- 组合纪律：①**E 全链一致**——跨层错误类型不同（ParseErr/DbErr/NetErr）先 transform_error 归一（variant/error_code），语言不替你转换；②and_then 的可调用**必须返回 expected**——里面塞个裸函数等于静默吞错；③expected<void,E> 的 transform 有特例（C++23 允许），void 值域用 map+or_else 组合更顺。
- 布局与性能：union 存储 + 判别标志，**无堆**；T、E 都 trivially copyable 时按寄存器传——但 `expected<int, BigErr>` 被 E 撑大：E 保持紧凑（enum/error_code），富错误用 `unique_ptr<const Error>` 或日志侧信道。
- C++23 前后：tl::expected 是事实标准 backport（接口同形，3 节宏门控）；**别把 expected 混进异常路径**——构造函数失败仍走工厂函数（84 节），单子通道与异常通道的边界画进架构文档。
- 管道缺口：std::expected 没有 tl::expected 的 operator| 自由函数——组合链要么嵌套调用、要么项目自写薄辅助（pipe(v, f, g)）；别为等齐 tl 语法宏变戏法，一层函数足够。

## 84. expected/异常/错误码三方决策树

- 四问判据（按序过）：①失败频率——常态失败（≥1%）→ expected/错误码（异常展开税摊不平，81 节）；罕见失败（<1e-3）→ 两可。②传播距离——穿 10 层无动作 → 异常免写 9 层样板；两层内终结 → expected。③错误是否"值"（可比较/可存储/可重试）→ expected 的值域语义（进 switch、进缓存、进指标）。④边界约束——C ABI/FFI（92 节）、fuzz 目标（103 节）、no-exceptions 构建（82 节）→ 只能码或 expected。

```text
混合是常态不是妥协：
外圈（进程/服务边界）  → 错误码/expected 显式化，可观测
内圈（不变式破坏）     → assert/terminate 快速失败
中间层（业务失败）     → expected 单子链（83 节）
第三方边界             → catch 转 expected：翻译层的唯一职责
恢复策略：预期可恢复 → 调用方分支处理（重试/降级/上报）
        预期不可恢复 → 顶层统一记录；不可预期 → 快死好过带病运行
```

- 反模式三连：①`catch (...) { return E::Unknown; }`——把 stack trace 与错误类型一起扔掉，下游无从处置；②`expected<optional<T>, E>`——"半失败"多一层语义，多数是建模错误；③同层混用两通道（一半抛一半返回）——调用方永远双防，review 必打回。
- fuzz/安全条款：fuzz 入口的异常显式 catch 转 return（libFuzzer 默认把未捕获异常当 crash——想抓崩溃就别在这个入口包 catch）；错误码/expected 必须 `[[nodiscard]]`，让"忘记检查"变成编译错误。
- 团队沉淀：错误处理政策一页纸（选择树 + E 类型清单 + 归一函数），新人照抄；每次"这里用哪个"的争论以这张纸为终审，防止风格漂移成三种方言。
- 上下文与错误值分离：expected 里塞大字符串上下文（每次失败复制几百字节）是隐性税——错误值保持小（码/id），上下文走日志侧信道用 trace id 关联；调试时 id 反查日志，两全。

## 85. 异常安全等级：基本/强/nothrow 的契约设计

- 三级一句话：**基本**——不变式未破、无泄漏、状态"有效但未指定"（可析构可赋值）；**强**——要么完全成功要么完全回滚（事务语义）；**nothrow**——保证不抛（析构/swap/move 的招牌等级）。每层函数把承诺写进注释（C++26 契约正是干这个的，100 节），review 按承诺查实现。

```cpp
// copy-and-swap：强保证的机械生产法（一切可能抛的事发生在副本上）
Widget& Widget::operator=(const Widget& rhs) {  // 自赋值/中途抛全稳
  Widget tmp(rhs);      // 可能抛——*this 未动
  swap(tmp);            // nothrow——原子提交
  return *this;         // tmp 析构旧资源
}
// 对照：逐字段赋值在第二个字段抛出时只剩基本保证（甚至半旧半新）
```

- move 时代的强保证坍塌：`push_back` 的强保证靠"扩容用 noexcept move 或回退 copy"（75 节）；**移动赋值/成员级 move 中途抛出 = 只剩基本**——把 move 全标 noexcept（86 节）是保住强保证的最低成本动作。
- 事务式提交（现代写法）：所有可能抛的工作先做完（构造新状态到栈/临时容器），最后用 nothrow 操作落地（swap/指针交接）——"构造期随意抛、提交期只许 nothrow"两段式，比 copy-and-swap 少一次整体拷贝。
- 别贬低基本保证：大服务里"基本 + 显式 reset/reload"通常比强保证便宜且够用（缓存损坏就重建）；**强保证留给外部可见的小 API**（金额、配置提交），全库强保证是性能负资产。
- 验收流水线：用"投掷炸弹"（第 N 次拷贝抛异常的测试类型）参数化遍历 N，断言每步状态可析构、无泄漏；ASan 泄漏检测 + 这类单测 = 强保证的机器验收，"人工心算"不算。
- swap 是强保证的建筑砖：copy-and-swap 的成立前提是 swap noexcept（33 节）——swap 抛则 tmp 析构路径都可能再抛，整栋楼塌回基本保证；noexcept 审计（86 节）必须覆盖 swap。

## 86. noexcept 双向承诺与 terminate 风险

- 双向性：noexcept 是**合同不是优化提示**——你向调用方承诺不抛（vector 据此敢 move，75 节、优化器据此删异常残骸）；违约即 `std::terminate`，**零展开**。加它之前问：函数体到最深被调的完整闭包，真的能静态排除 throw 吗？
- 高危面：①析构函数**隐式 noexcept**——写没写都一样，成员/被调析构抛 = terminate；②回调边界——把用户回调塞进 noexcept 函数，用户一抛全灭：接口要么不承诺、要么文档写死"回调抛即崩"；③`noexcept(noexcept(expr))` 是迁移期安全带，不是免检通道。

```cpp
struct Buf {
  Buf(Buf&& o) noexcept                 // 漏标 noexcept 的代价：vector<Buf> 扩容
      : data_(std::exchange(o.data_, nullptr)),
        n_(std::exchange(o.n_, 0)) {}   // 退化为逐元素深拷贝（75 节）
  void swap(Buf& o) noexcept { std::swap(data_, o.data_); std::swap(n_, o.n_); }
  ~Buf() { /* 隐式 noexcept：任何成员析构抛异常 → terminate */ }
  int* data_;  std::size_t n_;
};
```

- terminate 的代价：无展开 = 无 RAII 清理、core 位置无保证——配套 terminate_handler 落 backtrace（105 节符号化）；生产尸体的典型形态是"进程随机消失、日志停在半句话"。
- 审计三招：①review 查 noexcept 函数体内的**完整被调闭包**（virtual/函数指针一票否决）；②异常注入用例进测试矩阵（85 节炸弹类型）；③`-Wterminate` 拦"noexcept 函数体内直接 throw"（拦不住深处传播）。
- ABI 面：C++17 起 noexcept 进函数类型（mangling 变化）——**给导出函数加/去 noexcept 是 ABI 断裂**（95 节），so 边界上的 noexcept 决策跟符号版本一起进 ABI 政策。

## 87. [[noreturn]]、std::unreachable 与 [[assume]]

- 语义光谱：`assert`（debug 检查、release 删）→ `[[assume(cond)]]`（C++23：**永不求值**，编译器当优化事实）→ `std::unreachable()`（C++23：到达即 UB，通常编成 trap）→ `[[noreturn]]`（控制流注解：此函数不返回）。**从左到右，"写错时代的代价"从"测出来"升级到"静默错编"**。

```cpp
enum class Kind { A, B };
int cost(Kind k) {
  switch (k) {
    case Kind::A: return 1;
    case Kind::B: return 2;
  }
  std::unreachable();    // 枚举封闭成立时：正确且省掉假分支；
}                        // 调用方 cast<Kind>(99) 传进来 = UB，core 落你头上
// [[assume(n > 0)]]：运行期不检查不兜底；假设错了优化器删负数分支
// ——你的负数路径静默消失（无警告无 trap）
```

- [[assume]] 三律：①只写编译器无法自证、且**上游真检查过**的先验（check-then-assume：上游 if 炸、下游 assume 赚）；②assume 内禁副作用（不求值，副作用直接消失 = 神秘 bug）；③每个 assume 配注释"由谁保证、何时验证"——无主 assume 是定时炸弹引信。
- unreachable 的滑坡点：**新枚举加值那天**，unreachable 的 switch 从防御变陷阱——CI 开 `-Wswitch-enum` 强制全覆盖警告；`abort()` 保运行期兜底但阻断路径消除，unreachable 拿性能赌正确性，选型写明。
- [[noreturn]] 条款：标它的函数真返回了 = UB；调用后代码被当死代码删除——**别在 noreturn 调用后写"以防万一"的清理**，它真的会消失。
- 一句话选择树：内错 assert、外错 expected（84 节）、不可达 unreachable、性能先验 assume、控制流 noreturn——五个占位不互相兼职。

## 88. ABI 稳定性工程：pimpl、隐藏符号与版本脚本

- 默认姿态：对外 so 的**一切 C++ 类型都是易碎品**——布局（STL 差异，90 节）、inline 函数体（89 节）、虚表序（95 节）都随重编漂移；能承诺二进制兼容的形态只有一种：**opaque 指针 + C++ 类型不跨边界**。
- pimpl 三条款：①成员 `std::unique_ptr<Impl> impl_` 且**构造/析构必须在 .cpp 定义**（头文件里 Impl 不完整，=default 会 static_assert 爆）；②Impl 内存库侧分配——里面加成员、重排随便动；③**const 不穿透**——impl_ 是 const 指针但 pointee 不是，const 方法纪律自己保（或 double-pimpl）。

```cpp
// engine.h —— 对外只见不透明指针
class Engine {
 public:
  static Engine* create(const Config&);      // 工厂 + destroy 成对
  void destroy() const;                       // __attribute__((visibility("default")))
 private:
  Engine();  ~Engine();                       // 外联定义，布局锁死在库内
  struct Impl;  std::unique_ptr<Impl> impl_;  // sizeof(Engine) 永远 8 字节
};
// 版本脚本：EXPORT { global: Engine_*; local: *; }; —— 导出面进 CI 快照
```

- 版本脚本与可见性：`-fvisibility=hidden` 默认全藏 + `ld --version-script` 白名单放行（比逐个 annotate 抗漏）；`nm -D --defined-only` 的 diff 进 CI——**多导出一个符号就是多背一份未来动不了的合同**（94 节）。
- dlopen 边界：插件式加载要共用同一套 libstdc++/运行库；跨 dlopen 抛异常/dynamic_cast 要求两侧同版本同 ABI 的运行时且 typeinfo 可见性为 default——**插件系统的官方姿势是 C 边界 + 内部各自 C++**（91 节）。
- 验收流水线：`abidiff`（libabigail）比对老新 so，CI 红灯 + 95 节断裂清单做人工裁决依据——"感觉没改接口"和"ABI diff 为空"是两件事。

## 89. inline 的 ODR 语义与跨 so 陷阱

- 词义校正：`inline` 与内联无关（71 节），它是 **ODR 豁免**——多 TU 各持一份定义、链接期合并为一个符号（vague linkage）；推论：**改 inline 函数体 = 改 ABI**（所有内联了它的二进制持有旧逻辑与旧布局假设，95 节清单第④条）。
- 跨 so 场景：so 与主程序都 include 了 inline 函数 → 两份副本，动态链接合并到**先加载者**——纯函数无所谓；**带 static 局部变量的 inline 函数**在 hidden 可见性下各 so 持有一份状态——"配置改了不生效"的灵异事故多半是两份 static。

```cpp
inline Config& global_config() {           // 头文件单例外观的病灶
  static Config instance;                   // vague linkage：跨 so 可能两份
  return instance;
}
// 修法：定义搬进唯一 .cpp（非 inline），头文件只留声明；
// 符号走版本脚本导出（88 节）→ 全进程一份
```

- inline 变量（C++17）：`inline int counter = 0;` 跨 TU 合并，**跨 so 依旧两份**（hidden 时必两份）；跨 so 单例要么集中定义、要么进显式 registry——全局可变状态本身已是味道，跨 so 是它的放大器。
- ODR 违规的静默形态：两处定义因不同宏/包含顺序而不完全一致 → 哪个被选未定义——**unity build 与正常构建行为不同**的常见病因（10 节）；ASan 自带全局 ODR 检测（`detect_odr_violation=2`）专抓重复定义。
- 实操清单：头函数默认 inline 没问题（模板/constexpr 天然是），但**有状态的一律搬 .cpp**；修 inline 函数体按 ABI 断裂走流程，哪怕"只改了个常数"。
- interposition 暗面：default 可见的 inline 函数在 so 内部调用也可能被 LD_PRELOAD/符号抢占劫持（经 PLT）——安全敏感代码用 hidden 可见性锁死内部调用（94 节），这是 -fvisibility=hidden 的安全红利。

## 90. STL 容器跨 ABI 边界：布局差异与 opaque 设计

- 为什么 STL 不能过边界：`std::vector<T>` 的布局含内部指针与分配器假设——**三家 STL 各画各的图**（libc++ string 24B、libstdc++ 32B，74 节）；libstdc++ 自己都有 dual ABI（`_GLIBCXX_USE_CXX11_ABI`，GCC 5 切换，符号藏在 `__cxx11` inline namespace）——同库名不同 ABI 的两份 so 链进同一进程是经典翻车现场。
- mangled 名也是接口：`void f(std::vector<int>)` 的符号把模板参数全编进去；改自定义 allocator 的默认模板参数 = 所有调用点符号名变 = 链接期爆（这算**好的失败**——静默成功的那种更可怕）。

```cpp
// 对外 C 边界：STL 一律翻译成裸数据 + 明确释放器
extern "C" {
  size_t widget_list(engine_t*, widget_desc* out, size_t cap);  // 借出指针+len
  void widget_list_free(widget_desc*, size_t);                   // 谁分配谁释放
}
// so 内部：std::vector<Widget> 随便用——不跨界就没人管你
// 同工具链插件例外：主程序与插件同编译器/STL/flags，并在文档钉死版本矩阵
```

- 合法例外的合同：游戏引擎/内部插件全链同工具链时 STL 类型可以过边界——但必须落在发行文档（版本矩阵 + flags 清单），并加**启动期指纹校验**（编译器/ABI 宏哈希不符拒载），把"隐含约定"变"显式守卫"。
- string 特条款：跨 C API 传字符串 = char* + len + 释放函数三件套；`c_str()` 的 NUL 保证只在 std::string 上成立（string_view 不保证，77 节）；异常/RTTI 跨界见 94 节。
- 工具链漂移防线：对外 so 用**最老支持工具链**构建是铁律（11 节）——你的用户拿着 5 年前的 g++ 链你的库；新工具链构建的产物只放内部。

## 91. C 接口设计：opaque handle 与所有权契约

- 不透明指针最小集：`typedef struct engine_t engine_t;`——头文件只声明 tag，**定义永不出头文件**；调用方拿 `engine_t*` 只能转交不能解引用（sizeof 都编不过）——从根上杜绝布局依赖（88 节）。
- 所有权契约五条（逐条写进头文件注释，缺一条就是使用方在猜）：①谁创建谁销毁（create/destroy 成对）；②destroy 双指针置空（`void engine_destroy(engine_t**)`，double-free 防御）；③**借用 vs 转移**逐参数标注（`const char*` 借用、`buffer_t*` 谁释放写死）；④线程条款（哪些调用可并发、handle 绑定哪个线程）；⑤失败语义统一。

```c
// 错误码协议：统一 0=OK、负数=错误类（挑一套钉死，禁止每函数自创）
typedef enum { OK = 0, ERR_BAD_ARG = -1, ERR_OOM = -2, ERR_IO = -3 } status_t;
status_t engine_submit(engine_t* e, const uint8_t* in, size_t n);
const char* engine_last_error(void);  // thread-local，仅紧贴失败调用后有效
// 回调约定：user_data 永远第一参数（全库统一），文档写明"回调内禁调本库 API"
typedef void (*progress_cb)(void* user_data, int percent);
```

- 回调与可重入：回调里能不能再调库函数是死锁/递归事故源头——默认禁止并文档化，要支持就显式分 reentrant 子集；**回调抛异常**在 C 边界 = terminate（92 节同病），C++ 实现侧 catch-all 转错误码。
- 句柄类型强化：`void*` 是事故温床（什么都能塞）——每个概念一个不透明 typedef，静态分析可查混用；`size_t size` 首字段模式（调用方传 `sizeof(自己的结构)`，库按 min 填）让**加字段不破老二进制**，C ABI 的头号扩展技巧。
- 版本协商：`engine_get_abi_version()` + 能力位协商，调用方按版本选路径——比"默认参数悄悄变"可靠一个数量级。
- 句柄命名技巧：`typedef struct engine_t* engine_t;`（tag 与指针同名的自引用 typedef）让 C 侧 `engine_t h` 直接可写，绑定生成器也少一层混乱；C++ 侧配 forward declare 包装类 + 92 节 RAII。

## 92. 智能指针过 FFI：自定义 deleter 与错误映射

- 铁律：**跨界即裸指针**——shared_ptr/unique_ptr 从不出现在 C 接口签名；边界类型在 C++ 侧重新包上 deleter，deleter 里调**对面的释放函数**（不是 C++ delete——对面的内存可能来自对面的分配器）。

```cpp
struct EngineDeleter {                       // deleter 挂在边界类型上
  void operator()(engine_t* p) const { engine_destroy(&p); }  // 91 节双指针协议
};
using EngineHandle = std::unique_ptr<engine_t, EngineDeleter>;  // 无状态→1 字
EngineHandle open(const Config& c) { return EngineHandle{engine_create(c)}; }
// Rust 侧对偶：只有对侧真用 Box 分配才可用 Box::from_raw；否则 extern "C"
// 释放回调——引用计数跨语言必经 ref/unref 回调，不做假 shared_ptr
```

- 异常与错误映射：**C++ 异常绝不穿过 C 边界**（穿过 = terminate）；每个 extern "C" 入口包 try/catch-all → 返回码 + 日志；C 侧"码 + 线程本地消息"（91 节）进 C++ 侧再升级为 expected/异常（84 节翻译层）。
- deleter 四坑：①有状态 deleter（捕获 lambda）把 unique_ptr 变 2 字——ABI 尺寸敏感时用函数指针 + `[[no_unique_address]]` 压（56 节）；②deleter 在**已卸载的 so 里**：插件卸载后析构残留句柄 = 跳进 unmap 的代码——卸载前强制清空句柄注册表；③数组形释放要长度（deleter 带不了 n）→ 自定义结构或成对 API；④deleter 跑在哪个线程（最后一个释放者）要写契约。
- 共享所有权：跨语言真共享 = ref/unref 回调协议 + C++ 侧 aliasing shared_ptr 把 unref 挂在 deleter（45 节）——**先问能不能不共享**（改转移/借用），共享是最后手段。
- 泄漏的审计面：边界类型每次"裸指针逃离"（get()/release()）都在 grep 清单里登记（107 节模式）——FFI 泄漏的九成是某个角落忘了重新包装，登记制比人脑可靠。

## 93. 静态初始化顺序灾难与 constinit 治理

- SIOF 本体：命名空间级对象**跨 TU 初始化顺序未指定**——`A.cpp` 的全局对象构造时读 `B.cpp` 的全局，链接顺序/构建形态不同结果不同；"偶发、只在某些构建下"的空指针/半初始化事故十有八九是它。
- constinit 治理：`constinit` 强制**常量初始化**（编译期完成、进 .data、零运行时代码），任何需要动态初始化的写法直接编不过——配合 constexpr 构造，把"启动前的全局"变成"编译期就绪的只读数据"。

```cpp
constinit std::array<int, 256> g_table = make_table();  // 59 节：编译期算完
// 反模式：构造即注册——跨 TU 静态对象依赖另一个 TU 的注册表
static const bool registered = registry().add(make_widget);  // registry 可能
// 还没构造！改成显式启动序列：bool init_module(Registry&);  main 里按序调
// 函数局部 static 顺序免疫（首次调用初始化、C++11 起线程安全）：
const Registry& registry() { static Registry r; return r; }
```

- 函数局部 static 的账：magic static 的守卫变量让**每次访问付一次 acquire load**——热路径全局改 constinit 只读、或启动期一次解析成局部引用；跨 so 的函数局部 static 还有 vague linkage 双份问题（89 节）。
- 检测工具：Clang ASan 初始化顺序检查（`ASAN_OPTIONS=check_initialization_order=1`，配 `strict_init_order=1`）抓"读未初始化全局"；`-Wglobal-constructors`（Clang）把每个动态初始化全局点名为警告，进 CI 当清单用。
- 政策一页纸：①可 constinit 一律 constinit；②动态初始化全局 = review 红笔（要写"为什么不能避免"）；③顺序敏感的注册显式化进启动序列；④"用一次显式调用"取代"自动注册"——初始化也是架构，不是编译器的心事。
- constinit 不隐含 const：`constinit int counter{};` 是**可修改**的零运行时初始化全局——"常量初始化"与"只读"是两个正交承诺；要只读加 const，要可变就只写 constinit（初始化时机照旧编译期）。

## 94. 符号可见性与导出策略

- 默认反转：`-fvisibility=hidden` 全库默认藏，**显式导出**白名单（导出宏/版本脚本，88 节）；与"默认全导出、想藏再藏"相反的原因：漏藏一个内部符号（模板实例、内部类）= 永久背上 ABI 负担，而漏导出在**链接期就爆**（快速失败是最便宜的失败）。
- 可见性与动态特性联动：`dynamic_cast`/`catch` 跨 so 匹配类型要求 **typeinfo 符号 default 可见且进程内唯一**——hidden 可见性下 vague linkage 各持一份 → 抛出的异常在对侧 catch 不住，静默穿透到 terminate；异常表（.eh_frame）不可 strip；`-fvisibility-inlines-hidden` 顺手开。

```bash
# 导出面 CI 快照：这个 diff 就是 ABI 评审的输入（88 节）
nm -D --defined-only libengine.so | sort > exported.txt && git diff exported.txt
# 三层冗余白名单：版本脚本（链接期）+ __attribute__((visibility("default")))
# 导出宏（源码级）+ CMake 开关（构建级）——一层漏两层兜
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_VISIBILITY_INLINES_HIDDEN 1)
```

- 静态库特别条款：.a 不裁符号，但**装进 so 的静态库**会把它全部符号带进导出面——`-Wl,--exclude-libs,ALL` 或版本脚本藏掉；真实翻车：内部 .a 的符号被客户程序 symbol interpose，日志与回调全混。
- strip 纪律：发行 so 保留动态符号表与 .eh_frame（异常/展开依赖），debug 符号拆独立文件（`objcopy --only-keep-debug` + build-id 关联，105 节符号化要用）；**全 strip 的 so = 崩溃现场无法符号化**。
- MSVC 对偶：`__declspec(dllexport/dllimport)` 导出宏（CMake `generate_export_header`）、.def 白名单——概念一一对应，跨平台项目把导出策略写成宏/脚本双实现，别让两家规则漂移。
- 链接期守门：`-Wl,--no-undefined`（或 -z defs）让 so 里的未解析符号在**链接期**爆而不是运行时 dlopen 爆；配合导出面快照，so 的"完整性"检查形成闭环（88 节 CI）。

## 95. 二进制兼容承诺：什么改动是 ABI 断裂

- 断裂清单（任一命中 = 所有既有二进制要重编）：①改类成员（增/删/换类型/重排），含基类与虚基类布局；②增删虚函数、改虚函数签名、**虚表中间插入**（槽位全移；末尾追加也改 vtable 长度，一律按断裂处理——111 节例三的实弹）；③改模板参数/默认参数（mangled 名变）；④改 **inline 函数体**（调用方已内联旧逻辑，89 节）；⑤改 enum 底层类型；⑥给导出函数加/去 noexcept（86 节）。
- 不断裂（但也别滥用）：加非虚成员函数、加新类/新重载、改默认实参（调用点编译期展开）、改私有实现算法（语义面不变时）。

```cpp
// 防御性接口：槽位 + 版本，扩展只加不改
struct EngineVTable {
  void (*destroy)(Engine*);              // 前 4 槽永固
  status_t (*submit)(Engine*, const Buf*);
  size_t (*abiver)(Engine*);             // 能力协商：调用方按版本选路径
  void* reserved[8];                     // 新函数只填空不改序，老二进制安全
};
// 新能力 = 新槽位；reserved 为 NULL 时调用方判空降级
```

- 政策落地：Qt/KDE 的 "ABI promises" 文档是范本——每个导出类标 stability 等级（stable/frozen/internal）；pimpl 把"可自由演进区"圈在 so 内（88 节）；**符号版本**（`GLIBC_2.x` 式 version node）让老程序绑老版本、新程序用新符号，共存不互斥。
- CI 纪律：`abidiff`/abi-compliance-checker 全量比对上一个 release（每个对外 so 一份基线入库）；diff 非空 = 人工裁决，豁免留 record（谁批、为什么、用户迁移指引在哪）。
- 心法：**ABI 是单向门**——发布过的符号面只会变大；每次"顺手重构"过一遍清单，比发布后写迁移公告便宜三个数量级。
- 版本号三处一致：soname、包版本、abidiff 基线编号——任一漂移就是"用户链接旧 so、跑出新语义"；发布脚本统一生成三处（88 节导出面快照挂同一个号）。

## 96. mdspan 与多维数据通路

- 心智定位：`std::mdspan<T, Extents>`（C++23）是**多维 non-owning 视图**——把"裸指针 + 三个 stride 变量"的口头契约变成类型：下标、布局、访问器全部显式化；数值代码跨模块传参的第一选择（传值一个指针的价，零生命周期问题，77 节家族里最安全的一员）。
- 布局策略三选：`layout_right`（行主，C 数组默认）、`layout_left`（列主，Fortran/BLAS）、`layout_stride`（显式步长——**带 padding 的行主**用它表达 leading dimension）；布局错配 = 每次访问跨行跳，缓存全灭（69 节）。

```cpp
using Matrix = std::mdspan<double, std::dextents<std::size_t, 2>,
                           std::layout_right>;
Matrix view(double* data, std::size_t r, std::size_t c) {
  return Matrix{data, r, c};              // 零拷贝借出全矩阵
}
// C++26 套件：submdspan 切子视图（保 stride）、mdarray（owning 版）、
// std::linalg（BLAS 绑定，mdspan 为数据接口）——一整条数值管线
```

- 落地矩阵：libstdc++（GCC 14+）、MSVC 已齐、libc++ 跟进中——`__cpp_lib_mdspan` 探测；自家内核的纪律：**运算按布局的连续方向访存**（行主按行），别让 mdspan 的便利掩盖访存模式。
- 陷阱清单：①`operator[]` **不查边界**（零开销定位，debug 用自带检查的 accessor 策略）；②extents 按值传，别当大对象；③不拥有数据：源缓冲区 realloc/释放 = view 悬垂（span 同病）；④C++23 时代 owning 场景 = `vector` + mdspan 视图组合，mdarray 等编译器。
- 桥接既有代码：`mdspan{vec.data(), r, c}` 一行接进数值世界；反向落回 vector 走显式 `ranges::copy`——**转换点即审计点**，集中写在一处。
- leading dimension 实例：layout_stride 表达"每行 N 元素但有效 M 列"（对齐 padding）——submdspan 切出的 tile 保持 stride，BLAS 的 ldb 参数直接从 extents 取，**对齐与切分不再靠口头约定**。

## 97. std::execution：senders/receivers 的取舍

- 心智模型：P2300（C++26）把异步表达为**惰性 sender 管道**——组合子表达依赖与三个完成通道（set_value/set_error/set_stopped），**标准只给协议与代数，不附带运行时**：scheduler 来自实现（stdexec/NVIDIA 参考实现、libunifex）——"装了标准还要挑实现"是它 2026 年前的常态。
- 与老三样的对比账：裸协程/线程池手搓 = 控制流与取消全手工；asio awaitable = IO 域内生态最好、跨域编排与结构化取消弱；std::execution = **结构化取消（stop_token 贯穿全链）+ 错误通道类型化 + 多域编排**，代价是模板元编程重度（60 节的账全在这）与陡峭学习曲线。

```cpp
using namespace std::execution;              // C++26 语义（示意，实现在 stdexec）
auto work = schedule(cpu_sched)              // 从哪开始
  | then([=] { return parse(buf); })         // 值通道
  | continues_on(gpu_sched)                  // 迁移执行上下文
  | then([=](Parsed p) { return infer(p); })
  | upon_error([=](std::exception_ptr e) { return fallback(); });  // 错误通道
auto [r] = std::this_thread::sync_wait(std::move(work)).value();
```

- 采纳判据（按序）：①**多执行域编排**（CPU 池 + GPU + IO 事件环）→ 价值最大；②全链结构化取消（超时预算、用户取消）→ 老方案写得最痛的处；③团队与编译预算撑得住 → 小团队别硬上，asio/自研池够用很多年。
- 现实路线：2025-2027 用 **stdexec** 做试点域、隔离在内部接口后面（不进公共 ABI，95 节）；三编译器 std::execution 齐后再逐步替换；**别把 senders 立为全公司标准**——它是并发编排语言，不是默认线程池。
- 陷阱预告：sender 惰性——造了管道不连接不执行（忘 sync_wait/spawn = "什么都没发生"事故）；错误通道里 exception_ptr 传值有拷贝税；awaitable 互转要经适配层（P2300 自带一族）。

## 98. flat_map/flat_set：缓存友好的替代容器

- 收益机制：底层是**有序 vector**——O(log n) 二分的每步都顺缓存行，std::map 的节点是随机指针追逐（69 节预取杀手）；大 N 查找快 2-5× 是常态，且每元素一个槽（vs 节点 + 头部三指针），内存紧凑。
- 代价机制同样硬：插入/删除 O(n) **搬移**（元素大/非平凡时是真拷贝构造链）；引用与迭代器稳定性全无——插入一处全部失效（76 节，与 std::map 相反）。**写多、或要存元素地址的场景直接出局**。

```cpp
std::flat_map<std::string, Route> routes;   // 配置/路由表：启动构建、读为主
routes.reserve(table.size());               // 批量装载先 reserve（75 节）
for (auto& [k, v] : table) routes.emplace(k, v);
// C++23 adaptor：GCC 15 / libc++ 20（flat_map 先行、flat_set 未齐）/ MSVC
// 节奏不一 —— __cpp_lib_flat_map 逐项探测，别赌"整体 C++23"
// 微调：探测热点可手搓 sort + equal_range（少一层 adaptor），先测后换
```

- 使用判据表：读密集 + 一次性构建（配置、字典、路由、枚举映射）→ 首选；读多写少且写可批量 → "重建新表 + swap"绕开逐个插入的 O(n²)；插入删除查找混跑 → 回 std::map/unordered_map。
- 混合负载病例：flat_map 上每秒几千次插入 = 每次搬半个数组，perf 里 memmove/析构构造大片——**发现即改判**：写路径改 append + dirty 标记、读前 lazy_sort；写多于读时换回 map。
- 迭代器档次红利：flat_map 是**随机访问迭代器**（底层 vector）——lower_bound/equal_range/范围扫描全缓存友好；std::map 只有双向迭代器，同语义算法物理差百倍——算法选择面也是选型理由。
- 冷知识：要按 key 存稳定指针/引用（观察者、句柄表），用 `std::deque` 做底层（flat_map 的容器模板可换）——牺牲部分局部性换**元素地址稳定**，兼顾两者的顺手组合。

## 99. C++26 反射实战预演：成员遍历与枚举转字符串

- 能力面：P2996 进 C++26（2 节时间轴）——`^^T` 取反射值、`members_of` 拿成员序列、`identifier_of` 产名字、`define_static_array` 把编译期序列物化进静态数组、`template for`（expansion statement）展开；**反射值只能常量求值**（59 节沙箱）——它是"编译期描述程序、生成代码"，不是运行时反射。

```cpp
template <class T>                          // struct → 成员名数组（核心件）
constexpr auto field_names() {
  return define_static_array([]<auto... I>(std::index_sequence<I...>) {
    return std::array{identifier_of(members_of(^^T)[I])...};
  }, std::make_index_sequence<members_of(^^T).size()>{});
}
// 枚举转字符串：遍历成员比对 value_of——magic_enum 的头号退役项
// 序列化/ORM/结构化日志的样板层从"宏 + 手写访问器"换成"反射生成"
// 语法仍会微调（GCC trunk 已合入实现）：一律 __cpp_reflection 门控
```

- 与 magic_enum 对照（今天能用 vs 明天更好）：magic_enum 是 C++17 模板扫描技巧，限制真实——枚举默认扫 ±128、重复值只报一个、成员数受限；反射无扫描限制、可作用于任意聚合与类成员。**迁移判据**：`__cpp_reflection` 三编译器齐（GCC 率先、MSVC 未定）才删 magic_enum，之前双轨。
- 工程姿势：反射用法**封在代码生成层**（序列化、字段遍历、枚举名、测试工厂），别让 `^^` 语法散进业务代码——定稿前可能微调，集中一层才能整体跟进；生成的表用 define_static_array 落 .rodata（40 节），零运行期成本。
- 相邻能力一并看：`template for` 让成员循环成为语言结构；pack indexing（101 节）替代元编程取头；`std::meta` API 面仍在演进——每个反射函数当实验 API 标注，留升级窗口。

## 100. 契约（P2900）与运行时检查部署

- 模型：`pre`（入口不变式）/`post`（出口不变式）/`contract_assert`（中途检查点）+ **违反处理器** + 编译期分档（关闭/观察/强制一类措辞，最终档位以标准文本为准）；契约把"散落的 assert + 注释里的 @pre"升级为**语言级可机械处理**的检查；谓词应当纯——违反处理依赖它。

```cpp
int divide(int a, int b)
  pre (b != 0)                       // 调用方违约即触发违反处理
  post (r : r * b <= a)              // 实现方自证（post 名 r 绑返回值）
{
  contract_assert(b != std::numeric_limits<int>::min());
  return a / b;
}
// 等级链：assert(内错) < contract(跨模块不变式) < unreachable(不可达, 87 节)
// 契约不是输入校验：外部数据仍走 expected（84 节），契约管"模块间信任"
```

- 部署矩阵（按环境分档）：fuzz/夜班 = 全开 + 强制（违约即崩，103-104 节语料最大化）；生产冷路径 = 观察（违约进处理器 → 计数/日志不中断）；生产热路径 = 关闭但**契约留在源码**——下次事故一键重开就是取证现场。
- 违反处理器纪律：全局唯一（库不得私装）、只做记录与原子计数（**不得再抛**——部分模式下返回即 terminate）；跨 so 的处理器归属写进 ABI 文档（94 节）。
- 迁移策略：先把 assert 里"其实是接口契约"的条目升级为 pre/post（编译器支持以 GCC trunk/P2900R14 进 C++26 为准）；落地前用宏层模拟同构语法（`MY_PRE(cond)`），未来一键切真契约。
- 陷阱预告：谓词带副作用 = 关检查后行为变化（87 节 assume 同病）；post 引用参数（old 值语义）写错 = 全库误检；契约检查的**性能档位**进 CI 基准跟踪，防止"忘了关"的热路径回归。

## 101. C++26 语言小件：placeholder _、pack indexing 与 =delete("reason")

- placeholder `_`（P2964）：`auto [ok, _] = parse();` 的"我不在乎"显式化——解构终于能跳字段；语义细节：`_` 可重复出现（每个都是新占位、互不冲突）、不是真变量（取地址/decltype 无意义）；比"起个 dummy 名"强在 review 一眼看出"此处故意忽略"。

```cpp
auto [code, _] = http_get(url);            // 只关心 code；_ 不产生未使用警告
template <class... Ts> using Head = Ts...[0];   // pack indexing：取包内成员，
Head<int, char> h;                              // 头尾递归元编程整条退役
HttpServer(const HttpServer&) = delete("持有线程池与监听套接字；用 spawn() 新建实例");
// =delete("reason")：拒绝理由直达编译诊断，替代"疑难杂症报错 + 注释各说各话"
```

- pack indexing（P2663）：`Ts...[I]` 直接取包成员——元编程里 `front<Ts...>/back<Ts...>` 加偏特化终止条件的整条链（61 节主角之一）就此退休；配合折叠表达式（55 节），变参模板的"递归税"清零。
- =delete("reason")：被拒调用的**理由**进错误信息——拷贝删除标"为什么"（资源语义）、错误重载标"该用哪个"（`wait(ms) = delete("单位歧义；用 wait(milliseconds)")`）；比注释强在**跟报错一起出现**，比 deprecate 强在不给运行期余地。
- 组合场景速记：接口改版用 =delete + reason 指路（`use(legacy) = delete("v2: dispatch(task)")`）；解构多字段结构只取关心的（99 节反射生成的 tuple 陪跑）；包内选类型（`using Ptr = Types...[2]`）替代 index_sequence 舞蹈。
- 采用纪律：三件都是零依赖语言件，但一律 `__cpp_*` 宏门控（编译器矩阵：GCC 14/15+ 领先、MSVC 看 2 节时间轴）；齐之前各留等价 shim（`_` 用 `[[maybe_unused]] auto`，reason 用注释 + static_assert 禁用），别让可移植性为甜点买单。

## 102. 单元测试架构：链接纪律与编译防火墙

- 第一敌人是**链接时间**：模板重度库的单个巨型测试二进制，链接 30s × N 套测试 = 开发节奏崩盘；按模块切多个测试目标（CMake 每 module 一个 executable），公共 fake/工具下沉到一个 test_utils 静态库，**每个测试只链自己的模块**。
- 编译防火墙：被测模块对外接口窄（pimpl/接口类，88 节），测试经接口注入 fake（时钟/随机/文件系统/网络）——"隔离真实时钟与随机"是硬规矩：`Now()` 静态直呼全部改成注入 `Clock&`，否则时间敏感用例永远 flaky（随机同理：seed 注入 + 可重放）。

```cpp
// 可测试性的形状：依赖全部显式入参，禁静态/单例直呼（93 节的测试面）
struct Retrier {
  Retrier(Clock& c, Rng& r, Backoff p);        // 假实现可注入
};
TEST(Retrier, BacksOff) {
  FakeClock clock{std::chrono::milliseconds{100}};   // 时间即数据
  DeterministicRng rng{0.5};                   // 重试序确定 → 断言精确
  Retrier r{clock, rng, Backoff{1ms, 100ms}};  r.attempt(FailTwice{});
  ASSERT_EQ(clock.now(), FakeClock{std::chrono::milliseconds{150}});
}
```

- 结构分层：单元测试只链模块静态库 + fakes（毫秒级、全并发跑）；集成测试单独目标链真依赖（DB/网络，tag 隔离、夜班跑）；**同一用例不写两遍**——fixture 参数化切环境，CI 时间预算按层分配。
- 工程细节税：gtest 死亡测试的子进程退出码、sanitizer 与 -fprofile 的链接参数冲突（71 节）、测试目录的警告等级**不得低于**生产代码——生产干净、测试放水的团队，fuzz 与 review 纪律都在漏水。
- 度量与维护：测试二进制链接时间进 CI 报表（>30s 就是切分信号）；flaky 用例进隔离区（quarantine）+ 修复期限——长期红着的用例会把整个红绿灯变成背景噪音。

## 103. libFuzzer 纪律：coverage-guided 模糊测试建设

- 起步三件套：`-fsanitize=fuzzer`（插桩 + 驱动）+ 入口 `LLVMFuzzerTestOneInput(const uint8_t*, size_t)`（**入口必须最薄**：解码 → 调被测函数 → 返回）+ 种子语料目录；libFuzzer 自己进化输入，你只负责把入口做小、做纯、做成"无环境依赖"。

```cpp
extern "C" int LLVMFuzzerTestOneInput(const uint8_t* d, size_t n) {
  if (n < 4) return 0;                    // 早退便宜：预算省给深路径
  FuzzedDataProvider f(d, n);             // 结构化取数：语法覆盖率↑
  auto a = f.ConsumeIntegral<std::int32_t>();
  try { Parser::parse({d, n}); }
  catch (const BadInput&) { /* 预期失败通道：吞掉不算 crash（84 节）*/ }
  return 0;                               // 返回 -1 = 拒绝该输入（不进语料）
}
```

- 构建组合拳：fuzz 二进制永远 **ASan+UBSan 双开**（`-fsanitize=fuzzer,address,undefined`）；另建 TSan fuzz job 跑并发入口；被测代码与生产**同源同 flags（只多 sanitizer）**——专用"fuzzable 构建"编出的另一份代码 = 白测。
- 预算与字典：`-max_len` 钉住协议包上限、`-dict=proto.dict`（文本协议关键字、二进制 magic/长度模板——收益一个数量级）、`-max_total_time` 给 CI job（60-300s/目标起步）、`-jobs=N -workers=N` 吃满机器。
- 稳定性纪律：入口内禁真实时钟/网络/随机（102 节同源）；禁不可重入的全局状态（用例互相污染 = 疯狂假阳性）；crash 自动最小化 + 固化回归（105 节）；语料库防"只增不删"的熵增（104 节）。
- 隔离与预算：`-fork=N` 每 worker 独立进程（OOM/崩溃互不传染）；ASan+libFuzzer 组合的 RSS（影子内存 2-3×）要给 CI 容器留够，不然 OOM 报告淹没真 crash。

## 104. fuzz 语料库与字典运营

- 语料生命周期：**种子**（真实抓包/单测输入/历史 bug 复现文件——质量决定前 30 分钟收益）→ **进化**（新覆盖输入落 corpus 目录）→ **最小化**（`-merge=1 min/ fat/` 只留覆盖等价最小集）→ **回归固化**（固定语料每次 CI 全量重放，crash 文件转单测 fixture）。
- merge 的账：每周/每 release 最小化一次——语料只增不删会拖慢启动（fuzzer 启动时逐个跑语料重建覆盖表）；1 万文件缩到 2 千等覆盖文件，job 启动从分钟级回秒级；**删掉的文件归档**（CI artifact，不进 git），防"新代码需要老输入"的回退。

```bash
./fuzzer -max_total_time=300 -jobs=8 corpus/     # 进化：新输入落 corpus
./fuzzer -merge=1 corpus_min/ corpus/            # 最小化：以 corpus_min 为准
./fuzzer corpus_min/ -runs=0                     # 回归态：全语料重放校验
# 字例运营：协议新字段进版本 = 新 token 进 dict；文本协议给关键字对
#（Content-Length、\r\n），二进制给 magic/长度模板——fuzz 探索半径由它定
```

- 存储纪律：种子语料（人肉挑的）进 git；进化语料走 CI artifact/对象存储——**进 git 的大语料 = clone 时间的定时炸弹**；语料条目命名带来源（`seed_real_packet_0012`），排障可溯源。
- 覆盖率对账：定期 diff 覆盖报告（`-print_coverage=1`/sancov）——**哪些代码 fuzz 从没碰过**（错误注入分支、深层协议状态）是下一轮投入的靶子；new-cov 曲线平了 = 换字典/换入口/加结构化 provider，而不是加时长。
- 合规条款：真实抓包进语料库前脱敏（token/PII）——语料随代码分发等于把生产数据写进仓库；语料的审计要求与测试 fixture 一致，CI 加扫描器（gitleaks 一类）防倒灌。

## 105. 崩溃最小化与回归固化流程

- 流水线五步：**复现**（crash 文件 + 精确二进制 + sanitizer 环境，能 100% 复现才继续）→ **最小化**（`./fuzzer crash-file -minimize_crash=1` 自动缩；缩不动上 afl-tmin/手工 delta 二分，保 crash 签名不变）→ **符号化**（ASan 自动走 llvm-symbolizer；release 配 `-fno-omit-frame-pointer` + build-id debug 文件）→ **根因**（读栈 + sanitizer 的"谁分配/谁释放/谁竞争"三段，41 节诊断套路）→ **固化**。

```cpp
// 固化双层：fuzz 入口回归（永不再犯）+ 人类可读单测（根因常青）
TEST(Parser, FuzzRegression_2341) {
  const uint8_t input[] = {0x00, 0x01, 0xFF, 0xFE};   // 最小化后的崩溃种
  EXPECT_NO_FATAL_FAILURE(Parser::parse(input));      // 注释带原始 crash-id
}
// 事故档案三行进测试注释：症状 / sanitizer 报告要点 / 根因一句话；
// release-only 崩溃还要把 sanitizer 单测钉进常规 CI 矩阵（6 节）
```

- 最小化的真正价值是"根因显形"：原始 crash 输入常是几百 KB 乱码，最小化后的 4 字节让"越界写发生在长度字段 = 0xFFFE"一眼可见——省的是根因分析时间，不只是测试数据体积。
- 符号化陷阱：stripped 二进制 + 无 debug 文件 = 栈是地址海洋——**发行构建永远保留独立 debug 文件**（objcopy 拆分 + build-id 索引，94 节）；跨机器复现要同 libc/libstdc++（11 节最老工具链原则的另一面）。
- 去重与账本：crash 按栈顶 N 帧/sanitizer 指纹聚类——同根因的 100 个文件是一件事；账本（crash-id → 根因 → 修复 commit → 回归测试链接）反过来喂 110 节速查表。
- release-only 崩溃的复现策略：-O0 跑不出别死磕二分——直接在 sanitizer 构建重放同输入（常能以另一种形态触发）；41 节的 UB 怀疑链在这里闭环，二分只留给确实复现不了的少数。
- 时间线纪律：**先固化"症状级"回归**（crash 输入进 corpus + 单测 no-crash），再补根因修复——"等理解了再加测试"的队永远排不上。

## 106. 微基准纪律：Google Benchmark 陷阱

- 四大失真源（每个都能把数字变小说）：①**死代码消除**——`benchmark::DoNotOptimize(x)`（编译器屏障）+ `ClobberMemory()`（写屏障）双保险，只 DoNotOptimize 结果仍可能整体蒸发；②**常量折叠**——输入全 constexpr 化后测的是编译器（运行期造输入）；③**内联逃逸**——被测函数被内联 = 测了个空循环（opaque 包装，代价自知）；④**状态污染**——上轮预热了缓存/分支预测器（78 节），报告必须带分布。

```cpp
static void BM_Lookup(benchmark::State& s) {
  Table t = build_random(s.range(0));      // setup 在迭代外
  uint64_t sink = 0;                       // 消费累积值，防逐次比较折叠
  for (auto _ : s) {
    sink += t.lookup(next_key());          // 运行期输入流：真实分布
  }
  benchmark::DoNotOptimize(sink);          // 收尾屏障：整条链存活
  s.SetItemsProcessed(s.iterations());     // 吞吐口径别自己拿表算
}
BENCHMARK(BM_Lookup)->Arg(1 << 14)->Arg(1 << 18)->Complexity();
```

- 统计纪律：`--benchmark_repetitions=10 --benchmark_report_aggregates_only=true` 看 mean±std 与中位数；方差 >5% 的结果不可用（查 turbo/绑核/容器噪声）；报告**必须带机器/编译器/flags 元数据**——三个月后自己都复现不了。
- 设计扭曲（最隐蔽的坑）：给基准特调——参数区间挑在缓存全中的尺寸、对手算法喂冷数据；评审基准 PR 的第一问："两个实现在同一份数据分布、同一预热态下吗"；`->Arg` 阶梯必须跨 L1/L2/LLC 边界（69 节），否则结论只对"全命中"的世界有效。
- 汇编审计是终点：热点循环贴 Compiler Explorer 确认（80 节同款）——vfmadd 在不在、调用还剩几个、有没有意外 memset；**没看过汇编的微基准结论降级为猜想**。

## 107. C++ 代码评审高危清单

- 一页清单（照扫，命中必问；括号是深水节）：**悬垂**——返回局部引用、string_view/span 成员、临时物化的 view 传出（28、77）；lambda 按引用捕获且闭包出作用域（67 同族）。
- **生命周期**——容器存指向自己元素的指针/引用（76 扩容即失效）；`get()` 出的裸指针存成成员（42-43 所有权漂移）；span/view 从临时容器构造（77）。
- **竞争**——非原子共享读写（14："只读"不免疫）；double-checked 无原子（109）；锁内调用回调/分配/IO（25）；std::mutex 跨 co_await（25、62）。
- **UB 家族**——self-move/self-insert（31-33）；越界与指针算术（37）；有符号溢出进下标（38）；别名双关（34-35）；"-O2 删代码"怀疑链（41）。

```cpp
// 评审红旗实例（每行一个真实事故基因）
auto& front();                       // 返回谁的引用？扩容后谁还拿着它？(76)
cache[key] = cb(shared);             // 值语义还是延长生命周期？(43-44)
std::lock_guard lk(m_); on_event();  // 锁内回调：死锁预备役 (25)
if (!inited_) { init(); }            // 无原子的 lazy init (109)
return std::move(local_);            // 杀 NRVO (27)；const 上 move 退化拷贝 (29)
v.push_back(v[0]);                   // 自插入：扩容读失效源 (31、76)
```

- **异常/契约**——noexcept 面积无凭据、析构被调链未审（86）；强保证承诺与实现不符（85）；错误双通道混用（84）。**编译期/模板**——模板全头文件扩散（60-61）、SFINAE 遗产新写（53）、CTAD 花括号歧义（52）、丢弃分支仍需语法正确（54）。
- 用法：新人入职第一天背"每条对应一节"；PR 模板嵌清单勾选；**每条命中要求评论点名不变式**（"这里谁保证 X"）——不写不变式的"应该没问题"不算回答。
- 维护节奏：每月复盘"本月事故根因是否已在清单"——不在就加一行（110 节速查表对流）；行数只增不降不是膨胀，是事故学费的复利。

## 108. 所有权与生命周期反模式百科

- **十案速查**（症状→基因→修法）：①`return member_;` 成员引用返回——对象被 move/销毁后调用方继续用；成员引用只在"对象不亡"的合同下合法（48 节 API 合同要写）。
- ②vector 元素地址存进外部表，扩容全悬垂（76）→ 存索引/deque 底层/stable 容器。③`raw = sp.get(); sp.reset();` 裸指针与智能指针脱钩（42-43）→ aliasing shared_ptr 或显式借用合同 + weak 观察（47）。
- ④`unique_ptr.release()` 忘接手 = 双泄漏（对象没释放 + 指针没接住）（42）→ release 只出现在"转移给裸所有权 API"的边界行，配注释。⑤观察者注册 this、析构序反转 → 回调进坟场（47）→ weak_ptr 通知或显式 detach。

```cpp
subject.attach(this);                 // this 的生命周期谁担保？
// ⑥ string_view/span 成员字段：结构体寿命 > 所有调用方 (77)
// ⑦ vector<bool> 的 operator[] 返回代理：auto& x = v[i] 编不过，
//    auto x = v[i] 拿到拷贝/临时代理——以为是引用实为值（性能+语义双坑）
// ⑧ 异步队列存捕获 this 的 lambda，对象先走一步 (67)
// ⑨ std::span<int> s{v.begin(), 3};——v 是临时，全表达式末即悬垂 (77)
// ⑩ 跨 so 返回 const std::string&（内部缓存）：so 升级换 STL 布局，
//    指针能读语义全错 (90) → char* + len + 释放器
```

- 共同病理：**所有权合同没人写**——每个"裸的借用"背后是一个隐含假设（"调用期间没人动容器"、"对象活得比回调久"）；修法不是背十案，是**每个指针/引用参数落地成合同**（注释/类型/47-48 节语义选型），review 按合同问。
- 工具面：堆悬垂靠 ASan、竞争靠 TSan（14-15 节）；栈/静态区悬垂只有 review 一道防线——107 节红笔项就是这十案的浓缩，两表配套使用。

## 109. 并发与模板反模式百科

- **并发五案**：①double-checked locking 无原子——首次发布用普通 store，另一线程的 acquire 读没有 synchronizes-with（13-14 节），看到"半初始化对象"是最好结局 → `std::call_once`/函数局部 static（93 节）/外普通内 acquire-CAS 的正确双检。
- ②全局配置用普通赋值热交换 shared_ptr——写写竞争炸控制块（23）→ `atomic<shared_ptr>` 或版本号 + 双缓冲。③锁内调用用户回调（25）——死锁预备役 + 持锁时长不可控 → 先拷贝数据再出临界区调用。④recursive_mutex 掩盖重入设计缺陷（25）→ 重构"锁内再调自己"的路径。⑤"读多不写就随便读"——写一旦存在，纯读也是 data race（14：撕裂读只是开始，UB 才是主刑）。

```cpp
std::thread(worker).detach();         // ⑥ fire-and-forget 的账单在进程退出：
//    detached 线程活过 main → 静态析构竞速崩溃（93 节）→ 显式 join/jthread
auto f = std::async(std::launch::async, work);  // ⑦ future 被丢弃：
(void)f;                              // 析构阻塞等完成——"异步"静默变同步
while (!done.test_and_add()) {}       // ⑧ 自旋锁无退避：争用时烧总线 + 饿死
//    （示意）—— 正解 test-test-and-set + yield/backoff，或干脆上 mutex(24 节)
```

- **模板五案**：⑨一切头文件化（60：实例化放大 + 编译时间爆炸）→ 重实现下 .ipp + 单 TU 显式实例化防火墙。⑩新代码仍写 SFINAE（53）→ concepts 机械迁移没走完就是债。⑪CTAD `vector{v, v}` 推成二维（52）。⑫折叠表达式上千元素全展开（55、61）。⑬"模板参数 20 个、默认值 8 层"的接口没人能正确调用（60 的接口面成本账）。
- 病理共性：并发案 = **合同缺失**（谁初始化、谁持锁、谁等谁）；模板案 = **成本外溢**（编译时间/代码体积不进 review 视野）。修法：并发靠 25 节锁层级 + 14 节纪律；模板靠 60 节防火墙 + 编译时间进 CI 指标。
- 成本可见化：把 ClangBuildAnalyzer 摘要链接进 PR 检查（模板时间 diff 超阈值报警）——模板反模式靠工具拦，靠人脑记编译时间不现实。
- 这份百科与 107-108 联动：三张清单合成 review 的"红旗扫描表"，命中即引章节展开——**不是让 reviewer 记住一切，是让清单替他记**。

## 110. 事故模式速查表：症状→根因→修复

- 速查表（症状 → 第一嫌疑 → 一发取证；行尾是深水节）：**"-O0 正常、-O2 崩"** → 别名/溢出/生命周期 UB 被优化放大器利用（41）→ UBSan+ASan 复现 → 按 34-41 分类修，UB 不是"重试能好"。
- **"偶发 0x0/野指针"** → data race 的 use-after-free 或发布未同步（23）→ TSan 全量 + ASan → 原子/锁补 synchronizes-with（13-19）。**"挂死（CPU 0%）"** → 死锁（锁序反转，25）/条件变量丢唤醒 → `thread apply all bt` 找十字锁链 → 锁层级纪律。

```text
症状 → 第一嫌疑 → 一发取证
"多线程比单线程慢" → false sharing (21) → perf c2c 找 HITM 行 → alignas(64)
"CPU 100% 无进展"  → 自旋无退避/futex 风暴 (109) → perf 看 futex 占比 → 退避/换粒度
"内存只涨不降"     → arena 碎片/weak_ptr 续命 (43,72) → pmap+malloc_info
                    → 换 jemalloc/池化/清 weak
"升级编译器后偶崩" → 新警告/新优化踩中老 UB (5,41) → 老 flags 二分回滚
                    → 把新警告修成 -Werror 资产
"测试偶发失败"     → 时间/时钟/迭代序依赖 (102) → 随机种子定位 → fake 时钟/显式序
"偶发 assert 但 release 崩" → assert 带副作用被 NDEBUG 删 (87)
                    → grep assert 里的赋值/调用 → 拆出检查逻辑
```

- **"perf 热点是 malloc/线程栈"** → 分配风暴（72）/线程创建风暴 → heaptrack/jeprof → 池化 + 线程池（73）。**"锁等待列第一"** → 临界区过大 → `perf lock`/mutex 等待时间 → 缩临界区 + 分片。
- **"启动/静态初始化相关崩溃"** → SIOF（93）→ check_initialization_order → constinit/显式启动序。**"dlopen 后虚调用崩"** → 跨界 ABI/可见性（88、94）→ nm 查导出面 → C 边界化。
- 用法：事故第一分钟照表取行、第二分钟跑取证命令、第三分钟已在深水节——**速查表把"症状→章节"的回忆成本压到零**；每次新事故修复后回来加一行（105 节账本的对流层）。

## 111. 生产事故复盘三例：数据竞争、悬垂与 ABI

- **例一（数据竞争）**：症状——统计计数器每分钟"丢"1-3%，高峰更明显，无崩溃；直觉是"加法偶尔丢一次"，TSan 夜班直接报 race（两线程对非原子 `uint64_t hits` 的 `++`）；根因——`++` 是读-改-写，写写重叠即丢更新，且按 14 节这是 UB；修复——`fetch_add(1, std::memory_order_rel)`（18 节：纯计数无同步语义，relaxed 足够），10 分钟上线。
- **例二（string_view 悬垂）**：症状——配置热加载后日志字段偶发乱码，ASan 抓到 heap-use-after-free；取证——Config 成员 `std::string_view` 指向"构造时传入的临时 std::string"（77 节第③类：view 存成员）；根因——reload 路径 `Config c{fmt::format(...)}` 临时串死在语句末；修复——成员改 `std::string`（一次拷贝换命）+ "view 成员 = 红旗"进 107 清单。

```cpp
// 例一复盘三问的答案：为何迟发现？"统计不准"没有告警，月报对账才暴露；
// 防线固化：计数器一律封装 Counter 类型（内部 atomic），review 见裸计数即打回；
// TSan 夜班补统计路径用例（15 节）——三件固化全部可执行，"加强意识"不算
```

- **例三（ABI 断裂）**：症状——插件 SDK 小版本升级后，客户插件 20% 崩在虚调用；取证——`abidiff` 显示基类**末尾加了虚函数**，作者以为"末尾追加安全"；根因——vtable 长度变化 + 编译器对析构/保留槽排布差异，跨编译器构建的插件全部错位（95 节清单第②条的实弹）；修复——回滚发布 + 上线 vtable 槽位 + reserved 扩展模式（95 节）+ `engine_get_abi_version()` 协商（91 节）。
- 复盘骨架（事故模板，三例通用）：**时间线**（发现→止损→根因→修复）→ **取证证据**（TSan/ASan/abidiff 的具体输出）→ **防线为何没接住**（清单没这条/sanitizer 没跑这路径/ABI 无 CI）→ **固化项**（一个类型封装/一条 CI 检查/一条清单）——固化项必须可执行可验收，"加强意识"不算产出。
- 三例共性：症状全是**偶发**（竞争丢更新、热加载乱码、20% 崩溃）——偶发事故的三大高发根因（竞争/悬垂/ABI）正好就是这三例；把偶发事故当"环境噪声"结案，等于把复发概率留给下个季度。

## 112. 性能事故复盘三例：伪共享、分配风暴与逆优化

- **例一（伪共享）**：症状——8 线程并行比 4 线程慢 15%，CPU 满载无进展；取证——`perf c2c` 单一缓存行 HITM 占 60%+（21 节），定位到每 worker 的 progress 计数器相邻排布；根因——64B 行内 8 个写热点，MESI 乒乓；修复——`alignas(64)` 分行（22 节）+ 计数本地化（每线程累加、结束合并），8 线程线性扩展回归。
- **例二（分配风暴）**：症状——解析路径 47 次 malloc/请求，perf 里 malloc 占 31% CPU，RSS 随连接数线性涨；时间线特点是**回归在一周内缓慢发生**（新特性加了逐字段 string），没有单点提交可指认，靠 heaptrack 基线对比才看清；修复分层——pmr monotonic arena 每请求一块（73 节）→ 剩 3 次；热字段 SSO 落位（74 节）→ 剩 1 次；malloc 占比 2%。

```cpp
// 例二度量固化：每请求分配数进常驻监控指标 + 阈值告警
// （防"新代码悄悄带回风暴"——78 节持续剖析是它的系统化版本）
// 例三取证关键：汇编审计（106 节流程）发现检查被删——
//   if (idx <= size) { /* 边界检查 */ }   idx:int, size:size_t
//   → 比较含隐式转换 + 上游有符号溢出 UB（38 节）
//   → 优化器据此证明分支恒真 → 检查静默消失
// 修复：size_t 化 + UBSan 进 CI 全量（6 节矩阵）——"重构下标检查"不是无害提交
```

- **例三（逆优化/编译器删代码）**：症状——release 吞吐 -30%，diff 里只有"重构了下标检查"；取证——perf 显示热点循环少了一整段，汇编审计确认检查消失；根因——有符号比较 + 上游溢出 UB 让优化器证明分支恒真（38、41 节）；教训——**任何"纯重构"PR 都要带同一二进制基准的前后对比**（106 节），否则逆优化无声通过。
- **性能复盘终审三问**：①修复前的基线数字与取证证据在哪——没数字的"感觉变快/变慢"一律打回；②修复是否**把问题搬到别处**（池化的内存高水位、无分支化的副作用）——次级指标一并看；③防线固化成什么可执行物（常驻指标阈值/CI 检查/汇编审计步骤/78 节剖析节律）——**没有固化项的性能复盘，六个月后同一事故再来一遍**。
- 例三的推广：同类病不只在下标——比较链、隐式转换、未初始化读都能被优化器"证明"掉（38、41 节）——**"纯重构跑同一基准"的流程就是为此存在的**，PR 模板里缺了它就是缺。
