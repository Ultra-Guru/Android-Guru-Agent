<!-- kb: id=kotlin | role=builtin_coding_kotlin | title=Kotlin 专家知识库 | version=1 -->
# Kotlin 专家知识库

## 必看内容

- **空安全是类型系统的事**：`T?` 与 `T` 是不同类型 —— 空**不会**在运行时
  意外流入非空区（平台类型例外）。写代码时第一反应不是 `!!`，而是：
  ① 能改源头非空吗；② `?.` 链；③ `?:` 兜底；④ `let`/`takeIf` 过滤；
  最后才允许局部 `!!`（且必须有不变式注释）。`lateinit` 只用于
  生命周期注入的字段（并配 `::x.isInitialized` 检查）。
- **不可变优先**：参数默认不可变（val），集合出口转只读（`toList()`）；
  可变集合只在构建期局部使用。data class 的 `copy` 替代就地修改。
- **表达式优先**：`if/when/try` 都是表达式 —— 用它们赋值，而不是
  先声明 var 再分支赋值。函数体单个表达式用 `=`。
- **K2 推断收紧**：Kotlin 2.x 的 K2 编译器对智能转换和重载解析更严格
  （见 71-80 节）：lambda 参数显式类型在复杂重载处更稳；
  `when` 主体分支类型不一致时显式声明函数返回类型。
- **协程纪律**（与 Android 库共用心智）：结构化并发 + 取消协作式；
  `runBlocking` 只出现在测试；`GlobalScope` 禁用。
- **惯用法对照 Java**：写 Kotlin 时禁肌肉记忆的 Java 式写法
  （见 61-70 节反模式表）—— 最常见的是 `if (x == null) return`
  满天飞、getter/setter、以及把 lambda 写成匿名内部类。

## 快速索引

| 想找什么 | 去哪一节 |
| --- | --- |
| val/var/const 区别 | 1. 变量与常量 |
| 空安全全家桶 | 2. 空安全 |
| 智能转换为什么失效 | 3. 智能转换 |
| 字符串模板/原始串 | 4. 字符串 |
| when 怎么用最顺 | 5. when 表达式 |
| 区间/循环 | 6. 区间与循环 |
| 运算符重载 | 7. 运算符 |
| 相等（==/===） | 8. 相等性 |
| 类型层次/泛型 | 9. 泛型与型变 |
| @JvmStatic/注解目标 | 10. 平台注解 |
| lambda 与 it | 11. lambda |
| 高阶函数设计 | 12. 高阶函数 |
| inline/noinline/crossinline | 13. 内联 |
| 作用域函数五件套 | 14. 作用域函数 |
| 尾递归/折叠 | 15. 递归与折叠 |
| data class 细则 | 16. 数据类 |
| sealed 层次 | 17. 密封类 |
| object/伴生 | 18. 单例与伴生 |
| 委托（by） | 19. 委托 |
| 解构与 componentN | 20. 解构 |

## 1. 变量与常量

形态语义不赘述，这里给的是交界成本与陷阱账：

- **`const val` 是编译期内联 —— 跨模块陷阱**：值直接拷进调用方字节码，
  改常量只重编定义方 = 调用方还在用旧值（多模块/SDK 场景经典事故）。
  改共享常量必须全量重编消费方；对外 SDK 里频繁变更的量用普通 `val` +
  伴生对象（运行时读取）而非 const。
- **`by lazy` 的三个模式与一个死锁**：SYNCHRONIZED（默认，双重检查，
  首访问有锁开销）/ PUBLICATION（多线程可并发初始化，谁先用谁赢，
  结果可能被丢弃 —— 初始化昂贵且要求纯函数时别用）/ NONE（无线程
  保证，仅单线程语境）。死锁形态：构造器里访问自己伴生的 lazy，
  而该 lazy 又回读实例状态 —— 初始化循环。
- **初始化顺序是真实风险**：属性与 init 块按声明序执行 ——
  前面的属性初始化器里引用后面的属性拿的是默认值（null/0），
  类型系统不拦（跨属性依赖编译期看不出）。纪律：属性初始化器里
  只读声明在前的属性，复杂装配进 init 末尾或工厂。
- **`lateinit var` 的边界**：只能 var + 非空类型；赋值前访问抛
  `UninitializedPropertyAccess`（报错时机是访问点 —— 比 nullable 的
  运行时 NPE 离根因更近）；注入字段用 `::x.isInitialized` 防御性检查
  是气味（生命周期保证注入顺序才是正解，例外才检查）。
- **自定义 getter 没有支撑字段**：每次访问重新求值 ——
  把昂贵计算写成 getter 表达式 = 每次读都付费且调试器单步时也触发；
  昂贵派生值用 `by lazy`（读多算少）或显式缓存字段（需失效策略）。
- `@JvmField` 只给"不需要版本演进的 val 字段"：它会把字段直接暴露给
  Java（后续改 getter 语义就是破坏性变更）。

## 2. 空安全

- **平台类型是唯一渗透面 —— 边界治理纪律**：Java 侧来的 `T!` 三态
  （T / T? / 两者皆可）—— 公开 API 入口显式选边：
  参数标 `T?` + `requireNotNull`（明确契约），或注解治理源头
  （jspecify `@NullMarked`/androidx `@NonNull` 注解让 Java 签名带类型）。
  "在深处到处 `?.`"是治理失败的信号 —— 消毒只该发生在边界。
- **`!!` 的审计纪律**：`!!` 合法存在的地方 = "编译器不知道但运行时
  有不变式保证"。要求：局部使用（不出公开 API）+ 行尾注释写明不变式
  依据。review 里裸 `!!` 无注释一律打回；`!!` 出现在链中间
  （`a!!.b!!.c`）是没做边界治理的重罪。
- **`lateinit` vs `T? + ?: error` vs Nullable**：
  生命周期必注入（DI/回调必达）→ lateinit（早失败、无装箱）；
  初始化可能失败/延迟 → 可空 + 显式状态（把"未初始化"建模成业务状态
  而不是 NPE）；不要用 `lateinit` 表达"可能永远不来"的状态。
- **集合双层空**：`List<T>?` vs `List<T?>` 的消毒链不同 ——
  `listOfNotNull()` 压平元素空；外层判空后交给 `orEmpty()` 出口。
  混淆两层会出现"判了外层爆在内层"的假阳性修复。
- **智能转换 × 空安全**：`if (x != null) use(x)` 里 x 必须是不可变
  引用（val 或不可变属性）才能智能转换 —— 可变属性要 `val local = x`
  快照（3 节表）；跨模块/自定义 getter 同理。
- 泛型边界：`T` 本身在 JVM 上可为 null（无 reified 保证）——
  库作者用 `T & Any`（definitely non-null，2.0+）在签名层钉死；
  业务代码给公共泛型 API 补 `requireNotNull` 或文档声明。

## 3. 智能转换

- 生效条件：val 局部 / 不可变属性 with 不可变类型 / is 检查后同作用域
  / when 分支 / elvis 链。
- 失效清单（高频）：

| 场景 | 原因 | 解法 |
| --- | --- | --- |
| 可变属性（var / 自定义 getter） | 调用间可能变 | 局部快照 `val x = this.x` |
| 跨模块的 open 属性 | 子类可改 | 局部快照或显式转换 |
| lambda 里用外部 var 智能 | 闭包捕获可变 | 快照进 lambda 前 |
| 集合元素类型 | List<T> 声明与运行时不符 | 泛型声明改准 |
| 平台类型 | 编译器不知道 | 消毒（2 节） |

## 4. 字符串

- **UTF-16 陷阱（比 API 更重要的事实）**：`s[i]`/`s.length` 按
  UTF-16 code unit 计 —— emoji/部分 CJK 扩展是代理对，
  `"😀".length == 2`；"截前 N 字"用 length 截会截出半个字符。
  正确姿势：按 code point（`s.codePoints()`）或用
  `BreakIterator.getCharacterInstance` 按用户感知字符（grapheme）切。
  用户名首字头像、列表首字缩略，都是这条雷的高发区。
- **大小写转换的 locale 语义**：Kotlin 的 `lowercase()`/`uppercase()`
  （无参）是 Unicode 不变映射（不受系统 locale 影响）；
  `lowercase(Locale.forLanguageTag("tr"))` 才是土耳其语语义
  （dotless i）；从 Java 移植时 `toLowerCase()`（已弃用）带默认 locale ——
  "只在部分用户机器上搜索匹配不上"的经典根因。
- **拼接成本模型**：`+` 链在循环里 = 每轮中间 String；
  `buildString(capacity)` / `joinToString` 预估容量免扩容；
  字符串模板本质是 `StringBuilder` + `toString`（编译器优化），
  但嵌套循环里的模板仍会退化 —— 热路径统一 buildString。
- **`String.format` vs 模板 vs MessageFormat**：
  面向用户的文案（有复数/性别/顺序）用资源 + format（资源层翻译可重排
  参数位）；纯日志用模板；金额/千分位用 `%,d`。
- `trimIndent/trimMargin` 不只是“多行好看”：让生成 SQL/JSON 的字符串
  不带缩进噪声 —— 配 `"""` 原始串写正则（42 节）免双反斜杠。
- 判空语义分级：`isEmpty`（长度0）/`isBlank`（含空白）/
  `isNullOrBlank`/`orEmpty()`（出口转非空）—— "业务上算没填"用 blank 系列。
  `" ".isEmpty()` 为 false 是语义特性不是 bug。

## 5. when 表达式

```kotlin
val label = when (shape) {
    is Circle -> "圆 r=${shape.r}"
    is Rect -> "矩形"
    Shape.None -> "空"
    else -> "其他"     // sealed 时不需要 else
}
```

- **穷尽机制的层次**：sealed/Boolean/enum 作 subject 且作表达式时
  强制穷尽；缺分支是编译错（2.x 起 when 语句对 sealed 的非穷尽也在
  逐步收紧为错误）—— 这不是风格偏好，是"加子类时全部处理点炸编译"
  的重构安全网。else 慎加：它把穷尽检查静音，新子类会被 else 吞。
- **guard 条件（2.1 稳定）**：`when (x) { is Int if it > 0 -> ... }` ——
  分支里不用再判 is + 比较，减少了"模式匹配后还嵌套 if"的样板；
  有 guard 的分支序列有优先级（自上而下），别与 else 组合埋隐藏分支。
- subject 捕获：`when (val v = compute()) { ... }` 把昂贵计算钉到局部
  —— 等价于先 val 快照（3 节），多分支复用首选这个形态。
- 无参形态（条件链）是 if-else 链的替代 —— 但记住它**没有穷尽检查**
  （else 必须显式），“换成 when 就安全”是错觉，安全来自 sealed subject。
- when 分支返回类型不一致时 K2 直接报 —— 显式函数返回类型是解法
  （不是绕过）；分支值如果包含 Nothing（error/TODO），
  推断会正确跳过它。
- 性能侧：enum 的 when 编译为表开关（无逐项比较）；
  sealed 分支数很大时才需要关心（几百分支级），常规规模无感。

## 6. 区间与循环

- **成本直觉（不是玄学）**：`for (i in 0..n)` 编译为原生索引循环
  （无迭代器分配、无装箱）；`forEach { }` 在 range/List 上是 inline
  扩开 —— 与手写 for 同价；但 `Iterable.forEach` 换成序列或装饰器
  链后每层都有开销。热路径的优先级：索引 for > iterator for >
  链式操作 > 序列（短数据时序列反而慢，22 节阈值）。
- **进度条（progression）细节**：`1..10 step 2` 在编译期构成
  IntProgression（步长进循环变量）；`step 0` 运行时抛
  IllegalArgumentException；`downTo` 是负步长语法糖；
  `1..<10`（rangeUntil，2.0 稳定）是半开区间的意图显式化 ——
  索引循环一律 `until`/`..<`，杜绝 off-by-one。
- **提前退出的正确姿势**：找元素用 `firstOrNull { }`（声明式，
  短路）；`forEach` 里不能 break —— 需要 break/continue 的复杂循环
  老实用 for + 标签（`outer@`）；`return@forEach` 只是继续，不是 break
  —— 这个误解写出来的 bug 静默且难查。
- `repeat(n) { }` inline 且带下标变体（`repeat(n) { i -> }`）——
  固定次数首选；带状态的累积用 `generateSequence`/`fold` 替代手写
  while（15 节）。
- 多重循环重排：内层小外层大 vs 相反 —— 数据局部性（cache 友好）
  在二维数组/矩阵场景是实打实的常数级差异；遍历 `List<List<T>>` 时
  按行遍历（内层连续）优于跳列。
- 字符/长整型区间：`'a'..'z'` 合法（Char progression）；
  `Long` 区间每步有装箱风险（索引用 Int，数量级用 Long 判断再循环）。

## 7. 运算符

- **设计纪律之外的三个硬约束**：
  ① `plusAssign`（`+=`）与 `plus` 同时可用时编译器报歧义
  （"Ambiguity: applicable candidates"）—— 同一类型只提供一套
  （可变集合适用 plusAssign，不可变适用 plus）；
  ② `compareTo` 契约：只需返回符号（-/0/+），但必须自反传递一致，
  且**与 equals 一致** —— 不一致时 `sortedBy` 结果与 `contains`
  （走 equals）判断相反，排序容器（TreeSet）里"排进去了但查不到"；
  ③ `equals` 通过 `==` 调用且签名固定为 `Any?` 参数 —— 参数不是
  `T` 也不是可空擦除问题，写成窄参数就是重载不是重写（编译器会警告，
  但手写时常见）。
- 重载面纪律：代数语义类型（Vec/Duration/Money）才配运算符；
  `get/set`（下标）用于键访问；`in` 用于成员判定；`invoke` 让对象
  像函数（验证器/查询 DSL），频率控制 —— 滥用的代价是"读不出语义"。
- `===` 不可重载（恒等引用）—— identity 语义永久保留，是 equals
  实现里的兜底快路径（`this === other` 直接返回 true）。
- 位运算以中缀函数存在（`and/or/xor/shl/shr/ushr`，102 节）——
  不是运算符重载：既有互操作一致性又避免了 C 式符号滥用。
- unsigned 类型（UByteArray/UInt）有全套运算符但与 signed 互转显式 ——
  跨界运算的装箱与溢出语义自己负责。

## 8. 相等性

- **相等/哈希契约的三条底线**：equals 相等 → hashCode 必相等；
  hash 相等不要求 equals（碰撞合法但要求分布均匀）；
  equals 必须等价（自反/对称/传递）+ 与 null 永假。
  最容易破的是对称性 —— 手写 equals 接受父类型参数与子类比较时
  （`Parent.equals(Child)` 真、`Child.equals(Parent)` 假），
  集合里表现为“a.contains(b) 真 b.contains(a) 假”。data class 自动
  生成不会破对称，手写才会 —— 所以能用 data 就别手写。
- **data class 继承陷阱**：生成的 equals 只比主构造属性 ——
  data class 继承自带状态的父类时，父类状态**不参与比较**
  （两个子类实例父字段不同却 equals —— 语义 bug）；
  正解：组合替代继承，或父类状态全部下放到子构造。
- **数值边界的相等语义**：Kotlin `==` 对 Double 走 equals 语义 ——
  `NaN == NaN` 为 true（与 IEEE 754 的 `!=` 相反）、
  `-0.0 == 0.0` 为 false（但 `compareTo` 视为相等）——
  数值算法里用 `==` 判零的写法要过这道检查；
  `BigDecimal("2.0") != BigDecimal("2.00")`（equals 含 scale）——
  金额比较用 `compareTo(...) == 0`。
- **数组与集合**：`==` 对 Array 是引用相等 —— 内容比较必须
  `contentEquals/contentDeepEquals`；List/Set/Map 的 `==` 是结构相等
  （List 保序敏感）。
- `equals` 性能：大 data class 每次作 key 查询都重算 hashCode
  （Kotlin 不缓存 hash，String 才内部缓存）—— 大对象做热路径 key
  时考虑包装成不可变小 key（id 字符串）而不是整对象。

## 9. 泛型与型变

- **型变设计实战（不是背诵 PECS）**：
  声明点型变（`out T`/`in T`）是给"库作者"的承诺 —— List<out T>
  因为不可变才能协变；使用点型变（Java 通配符）Kotlin 几乎不需要，
  代价是 API 声明处要想清楚。实践决策：生产者位置 out、消费者位置 in；
  两端都读写 → 不型变 + 明确文档；函数类型天然按位置型变
  （`(T) -> R` 在 T 上逆变）。
- **definitely non-null（`T & Any`，2.0+）**：
  泛型 T 未约束时可空 —— `fun <T> orDefault(t: T?): T & Any` 把
  可空泛型压回非空；给互操作边界（Java 侧 `@NonNull T`）提供
  表达力。这是 2.0 语言层补的可空型参缺口。
- **擦除成本与补法**：JVM 无单态化 —— 泛型函数每次调用同一字节码，
  类型检查靠强转插入；`inline + reified` 是"复制特化版字节码"的
  逃生舱（代价是字节码膨胀），`filterIsInstance<T>()` 靠它免显式传
  Class。价值面：DSL/序列化/反射桥 —— 业务热路径不需要。
- **`@UnsafeVariance`**：`List<out T>` 里的 `contains(t: @UnsafeVariance T)`
  —— 型变承诺与方法参数位置冲突时的库作者逃生舱；业务代码出现它
  = 设计混乱（先改型变声明，不是加注解）。
- star 投影 vs out 投影：`List<*>` 只读安全访问（拿 Any?）；
  `List<out T>` 表达已知关系。语义不同别混用 —— star 是"不关心"，
  out 是"我知道它生产"。
- 递归边界（F-bounded）：`<T : Comparable<T>>` 表达自比较 ——
  好处是 API 不接跨类型比较；滥用（多层递归边界）会让推断很难看。
- 性能：泛型集合 + 装箱（59 节）是真正的成本点，
  型变本身零运行时成本（全在签名层）。

## 10. 平台注解

互操作注解的实质是"控制生成字节码的形状"—— 三个高级面：

- **默认接口方法的生成时代变化（Kotlin 2.2.0 起）**：接口函数体
  默认直接编译成 JVM default method（不再走 DefaultImpls 桥接类）——
  Java 侧调 Kotlin 接口默认实现的字节码形态变了；
  老代码若靠 DefaultImpls 反射/字节码工具（旧版本 mock/代理库）
  会在 2.2 升级时炸 —— 升级前扫一遍对
  `-Xjvm-default` 敏感的依赖。
- `@JvmOverloads` 的展开边界：生成"去掉尾部默认参数"的逐级重载
  （最多 n+1 个方法）—— 构造器用它对接 View 体系很爽，但
  参数默认值改动 = 公开方法集合变动（二进制兼容雷）；
  库公开面慎用，app 内随意。
- `@JvmName` 的两个半隐藏点：getter/setter 命名规则（
  `is` 开头的 Boolean 属性不生成 get 前缀）影响 Java 侧属性访问；
  含特殊字符的名字（反引号属性）需要 `@JvmName` 提供合法字节码名。
- `@JvmStatic` 的双入口：生成静态桥 + 保留伴生实例方法 ——
  Java 侧两路都通；不加则必须 `X.Companion.f()`
  （三方 SDK 注解扫描器偶因这个找不到方法）。
- **nullability 注解生态**：Kotlin 编译器认三系注解
  （org.jetbrains.* / androidx.* / jspecify）—— 对 Java 依赖的
  空安全推断取决于它们的注解质量；无注解的依赖全平台类型（2 节）。
- `@JvmMultifileClass`：多文件顶层函数合并成一个 Java 类名 ——
  命名冲突时只能选一个文件名，注意生成顺序无关性。
- `@Throws` 只影响 Java 侧 checked 异常声明 —— 不加也能抛（
  Kotlin 无 checked），加了 Java 调用方才会被迫处理。

## 11. lambda

- **编译与分配内幕**：非捕获 lambda 编译为单例（同一实例复用，零分配）；
  每次捕获外部变量的 lambda = 每次调用新分配 Function 实例 +
  包装捕获值 —— 热路径里“每次新建 lambda”是 GC 压力主源之一
  （与 android 库 6 节强跳过的 === 陷阱同源：列表项内联 lambda）。
- **捕获语义**：闭包按引用捕获（Java 也一样）—— lambda 里改外部 var
  = 跨执行点共享可变状态，并发场景是数据竞争入口；纪律：
  lambda 只读捕获，需要写用返回值/回调显式传递。
  "lambda 持有外部 this（Activity）且被长寿命对象存着"
  = Android 监听器泄漏的标准形态。
- **return 语义的两层**：lambda 内裸 return 是非局部返回（直接返回
  外层函数 —— 只在 inline 函数的 lambda 里合法）；带标签
  `return@forEach` 返回 lambda 本身；匿名 fun 正常返回自身。
  把这三态混着用是代码评审的一票否决项（可读性塌力）。
- **SAM vs Kotlin 函数类型**：传给 Java SAM 参数可直接 lambda
  （目标类型明确时编译成该类）；但 Kotlin 函数类型（`(T) -> Unit`）
  在 JVM 上是 Function1 接口 —— Java 侧接不到 Kotlin "函数类型参数"
  的简洁写法（要 Function1 实例）。跨语言公开 API 用 fun interface
  （1.4+）而不是裸函数类型。
- 接收者 lambda（`T.() -> Unit`）与普通 lambda 是不同类型 ——
  DSL 层级隔离靠它（61/62 节）；但嵌套接收者会遮蔽外层 `this`
  —— 层级深了要 @DslMarker（62 节）防误调外层。
- `it` 纪律：单层用 it；两层以上必显名 —— "it 嵌套 it"的读错率远超
  打字节省。

## 12. 高阶函数

设计要点（成本与兼容双面账）：

- **inline 决策矩阵**：lambda 参数想非局部返回 / 需要 reified /
  真热路径（度量证明）→ inline；其余不 inline —— 公开 API inline
  会把函数体复制进所有调用方（后续改动即二进制变更）。
  内部实现细节的 private inline 随便用（`@PublishedApi` 配合打开
  internal 成员给 inline 函数用，是库的标准解法）。
- **函数类型的位置型变**：`(T) -> R` 在参数位逆变 ——
  需要 `(Animal) -> R` 的地方可传 `(Dog) -> R`（能处理狗的必能处理动物）
  这个天然性变让"回调集合/分发器"的 API 兼容性比 Java 好写得多。
- **fun interface 的价值面**：给 Java 互操作提供 SAM 转换 ——
  `fun interface Handler { fun onEvent(e: Event) }` 后 Java/Kotlin
  双侧都能 lambda 直传；比裸函数类型多一个名字（可读 + 可文档）。
- 尾 lambda 位置惯例：函数类型参数放最后 —— 但超过两个 lambda 参数时
  （如 onSuccess/onError 双回调）命名函数/分接口更可读，
  双尾 lambda 的调用点没人读得懂。
- **行为参数 vs 配置对象**：开闭面稳定的用 lambda（扩展点）；
  面会长的用 sealed/接口（编译期穷尽）—— "布尔旗标" 是两者之外的
  最差解（load(true) 的 true 是什么）。

## 13. 内联

| 修饰 | 效果 | 什么时候 |
| --- | --- | --- |
| `inline` | 函数体+lambda 内联进调用处 | 高频小函数 / 需要 reified / lambda 参数想非局部返回 |
| `noinline` | 个别 lambda 不内联 | 同函数里部分要存起来 |
| `crossinline` | 内联但禁止非局部返回 | lambda 被放进别的执行器 |

- inline 的隐性成本：字节码膨胀 —— 只对真热路径用；
  公开 API 滥用 inline 会让后续改动破坏二进制兼容。

## 14. 作用域函数

- **成本真相**：五件套全是 inline —— 无 lambda 分配；
  但 `apply/also` 返回对象本身，链中每层都持有 receiver 上下文。
  性能不是它们的分水岭，**可读性才是**。
- **决策不是背表，是问三个问题**：
  ① 要返回对象本身还是计算值？（apply/also vs let/run/with）
  ② receiver 该用 `this` 还是 `it`？（this 语义近、遮蔽多；it 显式、
  嵌套安全）③ 这一步是"配置"还是"顺带"？（apply 配置块；also 插桩）
  回答完选择自现 —— 这也是评审时讲解作用域函数选择的口径。
- **known 坑**：① `let` 链金字塔（`?.let { }?.let { }?.let { }`）——
  超两层必拆变量/提前返回；② `apply` 里访问其他属性时 this 遮蔽 ——
  命名冲突时必须显名；③ `with(obj) { }` 无空安全 —— 传可空就裸奔
  （`with` 的场景几乎都可被 `run` 替代）。
- **takeIf/takeUnless 的定位**：过滤"自身"（而非集合）——
  `x.takeIf { ok } ?: fallback`；它返回可空 —— 别把它当"更短的 if"
  （控制流用 if/when，不要用函数链表达复杂分支）。
- 习惯用法优先级（评审口径）：null 链式 `?.let` > 内联 if；
  构造后配置 `apply` > builder 手写；副作用插桩 `also`（链不打断）
  > 复制语句。出现"为了用而用"（单层对象包 apply）直接打回。

## 15. 递归与折叠

- **tailrec 的硬条件**：递归调用必须是**最后一个操作** ——
  递归调用后还有运算（`1 + fact(n-1)`）不是尾调用，编译器拒化；
  递归调用在 try/catch 里同样不可化（编译器会警告）。
  化不成循环的深递归（树遍历这类天然非尾形态）用手写栈/
  序列迭代替代 —— JVM 栈深几千层就炸。
- **fold vs reduce 的语义差异**：`reduce` 空集合抛（无初值无结果）——
  类型上无法给出单位元；`fold` 显式初值 —— 空集合返回初值。
  累加器类型可与元素不同（fold）；reduce 只能同型 ——
  需要"求和→金额类型"这类转换时 fold 是唯一解。
- **顺序与并行**：`fold`/`reduce` 是串行有序 —— 不可交换的运算
  （拼接/减法）不能并行化；可交换运算上并行折叠需要 fork-join
  分段策略（业务代码用 coroutines 分片后汇总，别手写双递归）。
- `runningFold/runningReduce` 保留中间态 —— 画累计图/增量指标场景
  直接用，别自己 for 循环复制。
- `generateSequence(seed) { next }` 是惰性无限源 ——
  必须有终结条件（take/first/find），忘终结 = 无限循环挂死；
  序列每次消费重新求值 —— 共享"已生成"结果要先 toList 落地（22 节）。
- 递归→迭代的重构信号：栈溢出报错 / 需要中途暂停（无法实现）/ 
  需要进度上报 —— 三个信号出现任一就该改序列/显式栈。

## 快速索引 · 续（16-30 节）

| 想找什么 | 去哪一节 |
| --- | --- |
| data class 全细则 | 16. 数据类 |
| sealed 用在哪 | 17. 密封类 |
| 单例怎么写 | 18. 单例与伴生 |
| 委托属性（by lazy/observable） | 19. 委托 |
| 解构声明 | 20. 解构 |
| List/Map/Set 选型 | 21. 集合总览 |
| 序列 vs 集合 | 22. 序列 |
| 集合操作符全家 | 23. 集合操作符 |
| Map 操作细则 | 24. Map |
| 数组与原语 | 25. 数组 |
| 协程入门口径 | 26. 协程基础 |
| 结构化并发 | 27. 结构化并发 |
| 调度器 | 28. 调度器 |
| Channel | 29. Channel |
| Flow 入门 | 30. Flow 入门 |

## 16. 数据类

```kotlin
data class Money(val cents: Long, val currency: String = "CNY") {
    operator fun plus(o: Money): Money {
        require(currency == o.currency) { "currency mismatch: $currency vs ${o.currency}" }
        return Money(cents + o.cents, currency)
    }
}
```

- equals/hashCode/toString/copy/componentN 全自动 ——
  这四个需求出现两个以上就该 data。
- 主构造属性参与相等；body 里的属性不参与（坑：以为比了其实没比）。
- 不要继承 data 类（语义混乱）；组合或 sealed。
- copy 是浅拷贝；嵌套可变集合要显式深复制。
- **继承与相等的隐患**（8 节展开）：父类带状态时生成的 equals
  忽略父状态 —— “两个不同配置的对象 equals”是静默逻辑 bug；
  同时 data 类继承 data 类直接禁（编译错），但继承普通类没人拦。
- **copy 与可见性**：copy 的构造可见性跟随主构造 ——
  private 构造 + public copy 是可用的工厂模式；但 copy 会绕过
  init 里的校验逻辑（copy 直接赋字段不重新走构造）——
  校验在 init 的类，拷贝后可能非法 —— 校验放 companion factory +
  init 双层时注意 copy 旁路。
- 序列化边界：data class + kotlinx-serialization 需显式 @Serializable；
  默认值字段是“可选字段”兼容策略的一部分（40/95 节）。
- 组件数上限：componentN 生成覆盖全部主构造属性；
  但解构超过 3-4 个就该怀疑建模（用命名属性访问而不是位置）。

## 17. 密封类

```kotlin
sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>
    data class Err(val code: Int, val msg: String) : ApiResult<Nothing>
    data object Loading : ApiResult<Nothing>
}
```

- 价值：when exhaustive（编译器保证 cover）—— 加新子类时
  所有 when 全部编译报错，逼你逐处处理（这是它比 enum 强的点）。
- 同模块/同包可见（K1）/同模块（K2 放宽到跨包编译组）；
  跨模块层次用 interface + 内部实现收口。
- `data object`（1.9+）：单例带 toString/equals。

## 18. 单例与伴生

- **object 的初始化时机是类加载**：首次访问时执行 static 初始化器 ——
  "首次访问"可能在主线程（首帧某处碰到它）——
  object init 里做重活（IO/网络/大对象）= 冷启动随机卡顿源。
  纪律：object 里只放轻初始化；重单例交给 DI（可控时机 + 可替换）。
- **object vs object expression（高频混淆）**：
  `object X { }` 是命名单例（类加载一次）；
  `val x = object { }` 是对象表达式 —— **每次求值新实例**（匿名类）。
  把后者当单例用（“为什么状态不共享”）是真实事故模式。
- **companion 的字节码真身**：内部类 + 静态 INSTANCE 字段 ——
  Java 侧看是 `X.Companion.f()`；`const val` 直接内联进调用方
  （1 节跨模块陷阱）；`@JvmField/@JvmStatic` 改变形状（10 节）。
- 伴生可实现接口（工厂模式的 Kotlin 形态）+ 可带名 + 可有扩展函数；
  但伴生不能访问外部类实例状态（它是静态语境）。
- **测试面**：object 不可替换 —— 依赖 object 的代码不可单测
  （除非把 object 实现接口再注入）；DI 单例的"测试可换"是它相对
  object 的本质优势，不只是装配便利。
- 多进程注意：每进程一个 object 实例（静态不跨进程）——
  "单例缓存跨进程共享"是错觉（android 库 27 节）。

## 19. 委托

```kotlin
val prefs: Config by inject()                        // DI 委托
val title: String by Delegates.observable("") { _, old, new -> log(old, new) }
val cached by lazy { expensive() }
class Repo(db: Db) : IRepo by dbImpl(db)             // 接口委托转发
```

- 标准库三件：lazy / observable / vetoable（observable 变更回调，
  vetoable 可否决新值 —— 表单校验属性的经典形态）。
- 接口委托（`IFoo by foo`）：转发样板消除器 —— 装饰器模式首选。
- **provideDelegate（初始化期拦截）**：属性初始化时（不是每次访问时）
  拿到 KProperty —— 可做"属性名即 key"的偏好存储/IOC 容器注册
  （`by preference()` 自动用属性名做存储键）；
  比每次 getValue 里读 KProperty.name 更干净。
- **代价模型精确版**：每次访问走 getValue + KProperty 传参 ——
  KProperty 实例被编译器缓存（不是每次分配），开销是一次接口派发 +
  委托内逻辑；热路径避开的是"委托里每次做重复工作"（每次读都查表），
  不是委托机制本身。
- 委托与序列化/反射边界：属性委托在反射下呈现为字段 + getValue 调用，
  序列化框架（如 kotlinx-serialization）不认委托属性 —— 需要显式
  serializer/自定义映射，别假设透明。
- `by map`（Map 委托）：键名取自属性名，缺键直接抛 ——
  结构化配置解析可用但缺键报错时机是访问点（延迟爆炸）；
  解析启动时全量校验比延迟炸好（fail-fast）。

## 20. 解构

```kotlin
val (code, msg) = result          // ApiResult 是 data class
for ((k, v) in map) { }           // Map.Entry 解构
val (head, rest) = list           // componentN 自定义
operator fun <T> List<T>.component2(): T = this[1]
```

- **机制账**：解构 = 调 component1..N —— data class 自动生成；
  Map.Entry 的 `(k, v)` 靠标准库扩展 component1/component2
  （真实调用 entry.key/value，不拷贝）。
- **热路径警告**：`for ((k, v) in map)` 在 HashMap 上仍会分配
  Entry 对象（Map 结构自持）—— LinkedHashMap/HashMap 遍历必然带
  Entry 分配；对分配敏感的循环用 `forEach { k, v -> }` 一样躲不开，
  真要免分配得换结构（ScatterMap 类，113 节）。
- **componentN 是公开 API**：给业务类型定义 componentN = 承诺位置
  语义永不改变（字段插入顺序变更 = 调用方静默错位）——
  "解构给谁用"要克制，不确定就用命名属性。
- 中缀 `to` 建 Pair 也有分配成本 —— 大循环里用
  `mapOf(k to v)` 等价写法不省；真正省的是构建期复用/原始索引。
- 跳位 `_`：解构声明里显式声明丢弃位；lambda 里的解构参数
  （`fold { acc, (k, v) -> }`）可读但注意它同样触发 componentN。
- 多返回值纪律：返回 data class（命名可读）优于 Pair（命名消失）——
  Pair 只允许出现在局部/立刻解构的临时场景。

## 21. 集合总览

- **只读 ≠ 不可变（机制层）**：`List` 接口背后可能是别人手里的
  `ArrayList` —— 出口 `toList()` 快照防"两个所有者"（返回的是新 ArrayList 拷贝；
  Kotlin 1.6+ `toList()` 空集合返回共享单例，非空拷贝）。
  真不可变需要 PersistentList（69 节）或 ImmutableList 包装。
- **增长与分配账（选型前置知识）**：
  `ArrayList` 扩容 1.5×（复制旧数组）—— 大小可预知时
  `ArrayList(n)`/`buildList(n)` 预设容量免反复拷；
  `HashMap` 扩容 ×2 且逐项重哈希，负载因子 0.75 ——
  预知 n 项给 `HashMap(n * 4 / 3 + 1)`；
  `LinkedHashMap` 每项额外双向链表指针（内存代价换插入序）。
- **构建器纪律**：`buildList { }` 构建期可变、出口只读（内部按需
  预分配）—— 替代"mutableListOf 一路传"；
  `listOf(a)`/`setOf(a)` 单元素有优化实例，`emptyList()` 恒单例。
- **出口快照的三个时机**（不是到处拍）：
  ① 跨线程边界（并发修改风险）；② 缓存/字段返回（调用方写入内部
  状态）；③ 进 Compose 参数（稳定性，android 库 6 节）。
  局部传递拍快照 = 白白拷贝。
- 可变集合只在构建期局部使用（必看纪律）—— 违反的信号：
  MutableList 出现在函数签名（入口要么收只读，要么明确叫 buildXxx）。
- 集合类型断言陷阱：`listOf(1,2)` 底层是 `Arrays.asList` 定长视图 ——
  `as? MutableList` 转型会成功，调 `add` 却抛
  `UnsupportedOperationException` —— "能转不等于能用"；
  需要可变副本就重建（`toMutableList()`），别转型碰运气。

## 22. 序列

- 集合操作链每步建中间集合；`asSequence()` 惰性单遍 ——
  链 ≥3 步且数据 >1k 时换序列。
- 序列末端操作（toList/first/sum）触发求值；
  无末端 = 不执行（副作用陷阱）。
- 无限序列 `generateSequence` 必须配 take/find 终结。
- 并发序列 `Sequence` 不是线程安全；用 Flow（30 节）表并发流。
- **一次性消费不变量**：序列不能重复迭代 —— 二次 collect 空或抛
  （`IllegalStateException: This sequence can be consumed only once`）；
  这使它适合"管道"不适合"数据集"。要把序列当数据集就先 toList 落地。
- **大小信息丢失**：序列无 size 预知 —— `toList()` 从空数组开始
  反复扩容；大数据落盘预估容量用 `toCollection(ArrayList(n))`。
- **sequence{} 构建器与 generateSequence 的差异**：
  `sequence { yield(...) }` 是无锁协程式状态机（可 yield 任意次/循环/
  条件分支），`generateSequence` 是纯函数链 —— 复杂生成逻辑用前者，
  简单映射用后者。
- 多遍 vs 单遍的真实分水岭：链短 + 数据小，中间集合比序列快
  （每元素的序列调度开销占大头）—— 22 节阈值是起点，
  真正的判据是基准（58 节），不是直觉。

## 23. 集合操作符

操作符清单不背 —— 背下面这些**语义陷阱**（它们才是评审要点）：

- **静默覆盖类**：`associateBy { key }` 键重复后者覆盖前者（无警告）；
  `mapOf(a, a)` 同键同覆盖 —— 需要检测重复用
  `groupBy`/`fold` 手写断言；`+` 合并 Map 右覆盖左。
- **顺序保证类**：`sortedWith/sortedBy` 是稳定排序（TimSort）——
  多键排序用 `sortedWith(compareBy({ a }, { b }))` 一趟完成，
  连叠两个 sortedBy 是两趟且中间结果白算；
  `groupBy` 结果保持插入序（LinkedHashMap）。
- **边界行为类**：`single()` 空或多于一个都抛（"必然恰一个"断言）；
  `first()` 空抛 vs `firstOrNull()` 空返 null —— 外部数据一律
  OrNull 系列；`reduce` 空抛（15 节）；`chunked/windowed` 越界
  参数组合有部分窗口开关（partialWindows）。
- **分配隐藏类**：`flatMap` 每元素建中间集合再 flatten ——
  大数据用 `buildList { }` 手写单遍；`windowed(size)` 当
  step >= size 时内部复用同一个缓冲列表（返回的是视图！）——
  要持有需 toList 拷贝，否则改一处全变。
- **去重类**：`distinct()` 保序去重（内部 HashSet）；
  自定义类型要求 equals/hashCode 成对（8 节）；
  `distinctBy { key }` 只比 key 不要求全字段相等。
- zip 系列：`zip` 取短（长则丢）；zip 后索引与原集合错位时
  用 `zipWithNext`（相邻对）而不是手动 index。
- 优先选可读性等价的内建：`partition`（一分为二）、
  `sumOf`/maxOf/average（专用聚合）、`joinToString`（拼接+分隔符）——
  它们都比手写 for 少一层索引/临时变量心智。

## 24. Map

```kotlin
val m = buildMap { put("a", 1) }
val merged = counts + ("b" to 2)                    // 合并（右覆盖左）
val v = m.getOrElse("x") { 0 }                      // 缺省读
m.forEach { (k, v) -> }
val byId = people.associateBy { it.id }             // 建索引
val grouped = orders.groupBy { it.status }
val freq = words.groupingBy { it }.eachCount()
```

- `to` 中缀建 Pair；`mapOf("a" to 1)`。
- 遍历时修改：ConcurrentModificationException ——
  要么先快照 `toList()`，要么 `removeIf`（可变集合）。

## 25. 数组

- `Array<T>` 装箱 vs `IntArray/LongArray...` 原语（性能敏感处用原语）。
- `arrayOf(1,2)` / `intArrayOf(1,2)`；
  二维 `Array(n) { IntArray(m) }`。
- 集合 ↔ 数组：`toList().toIntArray()`；出口拷贝语义。

## 26. 协程基础

```kotlin
fun main() = runBlocking {           // 只许 main/测试
    val job = launch { delay(100); println("done") }
    val v = async { compute() }.await()
    job.join()
}
```

- 挂起函数（suspend）由编译器改写成状态机（CPS）——
  "挂起"不占线程；恢复点在完成处指派的调度器上。
- 挂起函数只能在协程/其他挂起函数里调用；
  普通函数调 suspend 的唯一入口是启动器（launch/async/runBlocking）。

## 27. 结构化并发

```kotlin
coroutineScope {
    val a = async { fetchA() }
    val b = async { fetchB() }     // 任一抛 → cancel 兄弟 + 上抛
    use(a.await(), b.await())
}
supervisorScope {                   // 失败隔离
    children.forEach { launch { runCatching { job(it) } } }
}
```

- 规则：父取消→子全灭；子失败→父取消（supervisor 除外）；
  scope 必须等所有子完成才返回。
- 生命周期锚：viewModelScope / lifecycleScope（Android 侧库）；
  纯 Kotlin 里用 CoroutineScope(SupervisorJob() + dispatcher) 显式建。

## 28. 调度器

| 调度器 | 用途 |
| --- | --- |
| `Dispatchers.Default` | CPU 密集（排序/解析） |
| `Dispatchers.IO` | 阻塞 IO（文件/网络/DB 驱动） |
| `Dispatchers.Main` | UI（仅 Android/Swing 库注入） |
| `Dispatchers.Unconfined` | 不换线程（测试/特殊桥接，生产少用） |
| `limitedParallelism(n)` | 子池限流（1.7+，防饥饿） |

- withContext 切换并回来；嵌套切换成本低于 1µs 级（别怕）。
- 自定义线程池场景：`Dispatchers.IO.limitedParallelism(4)` 优先于
  新建 Executors（复用 IO 池弹性）。

## 29. Channel

```kotlin
val ch = Channel<Int>(capacity = 4)
launch { repeat(10) { ch.send(it) }; ch.close() }
for (x in ch) println(x)            // close 后循环自然结束
```

- 缓冲策略：RENDEZVOUS(0) / fixed(n) / UNLIMITED / CONFLATED（只留最新）。
- `send` 满则挂起；`receive` 空则挂起 —— 天然背压。
- trySend/tryReceive 非挂起版（即时结果，用于实时场景）。
- 关闭纪律：生产者 close；消费端 for 循环自动终止。

## 30. Flow 入门

```kotlin
fun ticks(): Flow<Int> = flow {
    var i = 0
    while (true) { emit(i++); delay(1000) }
}
ticks().take(5).collect { println(it) }
```

- 冷流：每个 collect 都从头跑一遍 —— 无副作用才安全。
- 桥回调：`callbackFlow { }`（把第三方监听器转流，记得 awaitClose）。
- 热流转换：`stateIn/shareIn`（Android 库场景配 WhileSubscribed(5000)）。
- Flow 操作符默认顺序执行；`buffer` 引入并发（上游不停）。
- flowOn：改变上游执行调度器（下游保持收集者上下文）。

## 快速索引 · 续（31-45 节）

| 想找什么 | 去哪一节 |
| --- | --- |
| Flow 高级操作符 | 31. Flow 进阶 |
| StateFlow/SharedFlow | 32. 热流 |
| 并发原语（Mutex/Semaphore） | 33. 并发原语 |
| 原子性/可见性 | 34. 原子与可见性 |
| Java 互操作 | 35. Java 互操作 |
| 集合 Java 调 Kotlin 的坑 | 36. 互操作陷阱 |
| 注解处理/KSP | 37. KSP |
| 反射 | 38. 反射 |
| kotlin.time/Duration | 39. Duration |
| kotlinx.serialization | 40. 序列化 |
| 日期时间 | 41. 日期时间 |
| 正则 | 42. 正则 |
| 文件 IO（kotlin.io） | 43. 文件 |
| 标准库杂项 | 44. 标准库杂项 |
| 构建与工具链 | 45. 构建与工具链 |

## 31. Flow 进阶

```kotlin
searchQuery
    .debounce(300)
    .distinctUntilChanged()
    .flatMapLatest { q -> repository.search(q) }   // 新值取消旧
    .catch { e -> emit(emptyList()) }               // 上游捕获（不取消下游）
    .onCompletion { log("done") }
```

| 操作符 | 一句话 |
| --- | --- |
| `combine(a, b) { x, y -> }` | 任一更新就合（最新值语义） |
| `zip(a, b)` | 严格成对（短的终结） |
| `merge(a, b)` | 交错输出 |
| `transform` | map+filter 合体（可 emit 多次/不 emit） |
| `scan(init)` | 滚动累计 |
| `withIndex` | 带序号 |
| `retryWhen` | 重试（可看 attempt/cause） |
| `sample/timeout` | 采样/超时 |
| `kotlinx.flow.cancellable()` | collect 可被取消（含循环生产者） |

## 32. 热流

```kotlin
val state = MutableStateFlow(Loading)
val events = MutableSharedFlow<Event>(
    replay = 0, extraBufferCapacity = 1,
    onBufferOverflow = BufferOverflow.DROP_OLDEST
)
```

- StateFlow：必有当前值；`value` 可直读；同值不重发
  （`update { it + 1 }` 原子读改写）。
- SharedFlow：事件语义（无当前值）；replay 控制迟到的订阅者能看到几条。
- 事件不要用 StateFlow 表达（同值吞掉第二次）—— 32 节索引卡此坑。
- 热流泄漏：订阅者必须随生命周期移除（CompositeDisposable /
  repeatOnLifecycle 模式，Android 侧）。

## 33. 并发原语

| 工具 | 语义 | 用法 |
| --- | --- | --- |
| `Mutex` | 协程互斥（可挂起） | `mutex.withLock { }` |
| `Semaphore(3)` | 并发上限 | `semaphore.withPermit { }` |
| `CompletableDeferred` | 一次性结果同步点 | `await()` 等完成 |
| `Job` | 句柄 | cancel/join/isActive |
| `select` | 多路等待第一个 | `select<Unit> { a.onAwait {} }` |

- Mutex vs synchronized：前者挂起不阻塞线程；后者钉死线程。
- 限流并发请求：Semaphore + map（配 34 节）。

## 34. 原子与可见性

- @Volatile：保证可见性 + 禁重排（不保证原子复合操作）。
- AtomicXXX：CAS 原子（incrementAndGet / compareAndSet）。
- 协程世界优先：Channel/StateFlow 通信，少共享可变状态
  （"Don't communicate by sharing memory"——协程版同理）。
- 双检锁单例在 Kotlin 直接 object 化（语言级线程安全懒加载）。

## 35. Java 互操作

- Kotlin→Java 调用透明；平台类型（`T!`）在边界消毒（2 节）。
- Java 调 Kotlin：
  - 顶层函数 → `FileKt.method()`（@file:JvmName 改名）；
  - 伴生函数 → `X.Companion.f()`（@JvmStatic 直调）；
  - 默认参数 → 生成全参 + @JvmOverloads 展开重载；
  - 属性 → getX/setX（@JvmField 暴露字段）。
- SAM 转换：Kotlin lambda 可直接传 Java 函数式接口参数。

## 36. 互操作陷阱

| 陷阱 | 症状 | 解法 |
| --- | --- | --- |
| Java 侧修改"只读"List | ConcurrentModification / 逻辑错 | 出口拷贝 toList() / Java 侧收 List<?> |
| `Map.Entry` 变异 | 只读视图被写 | 不可变映射出口（java.util.Collections.unmodifiableMap） |
| 受检异常 | Java 调用方不知道要 catch | @Throws 显式声明 |
| 重载歧义（K2 更严） | 调用匹配两难 | 显式类型实参 / 改名区分 |
| 静态成员飘 | Companion 链 | @JvmStatic |

## 37. KSP

- kapt（注解处理走 javac stub 生成）慢且隔离差；
  KSP 直接在 Kotlin 前端分析（~2x 快，无 stub）。
- 迁移：处理器实现 KSP 符号 API（`SymbolProcessor` + Provider）；
  Hilt/Room/Moshi 官方均已支持 —— 依赖里 `kapt` → `ksp` 配置逐个换。
- 自研注解处理：KSP 是唯一推荐路径（kapt 已进维护模式）。

## 38. 反射

- kotlin.reflect：KClass/KProperty/KFunction；
  `::x` 引用、`x::class`、`obj.javaClass`。
- 代价：首次访问解析慢 + 混淆后字符串名漂移 —— 业务路径禁反射派发
  （本仓库 CI 反模式门禁同款纪律）。
- 合法场景：序列化器、测试工具、DI 扫描 —— 均为框架层。

## 39. Duration

```kotlin
val t: Duration = 250.milliseconds
val d = 1.5.hours
val budget = 3.days
t.inWholeSeconds          // 转秒
t.toString(DurationUnit.SECONDS, true)  // "0.25s"
delay(200.milliseconds)   // 协程直接吃 Duration
```

- 禁裸 Long 传时间（单位不明）；API 边界 Duration 或带单位命名。

## 40. 序列化

```kotlin
@Serializable
data class Profile(val id: Int, val name: String, @SerialName("avatar_url") val avatar: String? = null)

val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; isLenient = true }
val text = json.encodeToString(profile)
val back = json.decodeFromString<Profile>(text)
```

- @SerialName 对齐外部字段名；默认值 + `encodeDefaults=false` 省体积。
- 多态：`@JsonClassDiscriminator` + sealed 层次天然支持。
- 非法字段容错：`ignoreUnknownKeys`；坏类型默认抛
  （`coerceInputValues = true` 可宽进）。
- 与 Java 字段名不一致时，序列化模型独立于领域模型（DTO 分层）。

## 41. 日期时间

- 全部 java.time（LocalDate/Instant/ZonedDateTime）；
  格式化 DateTimeFormatter；时区只在世界观层做（存储 Instant）。
- kotlin-datetime 库用于多平台；JVM 单平台直接 java.time。
- 序列化约定：ISO-8601 字符串（Instant.toString）；
  人类展示按 locale 格式化。

## 42. 正则

```kotlin
val idCard = Regex("""\d{17}[\dXx]""")
if (idCard.matches(s)) { }
val m = Regex("""(\d+)-(\d+)""").find(s) ?: return
val (a, b) = m.destructured
s.replace(Regex("[aeiou]")) { it.value.uppercase() }
```

- 原始串 `"""..."""` 免双反斜杠 —— 正则一律原始串。
- 预编译 Regex 常量（热路径）；`matches` 全串 / `containsMatchIn` 子串。

## 43. 文件

```kotlin
File("a.txt").useLines { it.filterNotNull().take(10).toList() }   // 惰性行流
File("b.txt").bufferedWriter().use { it.write(s) }
dir.walkTopDown().filter { it.extension == "log" }
File("x").copyTo(File("y"), overwrite = true)
```

- use/useLines 自动关资源；目录遍历 walk 惰性。

## 44. 标准库杂项

- `TODO()` / `error("...")` / `require(x)` / `check(x)` 四件断言。
- `Pair/Triple`（返回处用 data class 替代）；
  `Comparable` 实现：`compareBy({ it.a }, { it.b })`。
- `Result<T>`：runCatching 包裹；`getOrElse/fold/getOrThrow`；
  禁吞 CancellationException（协程场景 rethrow）。
- random：`Random.nextInt(1..6)`；SecureRandom 用于安全场景。

## 45. 构建与工具链

- Kotlin 2.x：K2 编译器（前端重写：更快 + 推断更严）；
  Compose 编译器并入 Kotlin 主版本（2.0 起）。
- 工具链：`kotlin { jvmToolchain(17) }`；CI 与本地同 JDK。
- 编译器参数常见项：`-Xjvm-default=all`（接口默认方法生成）；
  `-opt-in=kotlin.RequiresOptIn` 标注自定义实验注解。
- 版本矩阵：本仓库 Kotlin 2.3 / serialization 1.9 / coroutines 1.10 ——
  coroutines 与 Kotlin 版本成对升级（见仓库 libs.versions.toml 单源）。

## 快速索引 · 续（46-60 节）

| 想找什么 | 去哪一节 |
| --- | --- |
| Java 式写法怎么转 | 46. 惯用法总表 |
| Kotlin 反模式 | 47. 反模式大全 |
| K2 推断为什么报错 | 48. K2 收紧清单 |
| K2 迁移注意 | 49. K2 迁移 |
| 风格细则 | 50. 风格细则 |
| 测试结构 | 51. 测试结构 |
| runTest 全解 | 52. runTest |
| Fake 与测试替身 | 53. 测试替身 |
| 断言对照 | 54. 断言 |
| 协程测试配方 | 55. 协程测试配方 |
| 集合性能数据 | 56. 集合性能 |
| 内联成本 | 57. 内联成本 |
| 基准测试 | 58. 基准 |
| 内存与装箱 | 59. 装箱 |
| 构建产物与混淆 | 60. 产物 |

## 46. 惯用法总表

Java 写法 → Kotlin 惯用（评审对照）：

| Java 式 | Kotlin 惯用 |
| --- | --- |
| `if (x != null) { use(x) }` | `x?.let { use(it) }` |
| 先声明再赋值 | `val x = if/when`（表达式） |
| getter/setter 对 | `val/var` 属性 |
| 静态工具类 | 顶层函数 / object |
| 匿名内部类 | lambda |
| StringBuilder 拼 | 字符串模板 |
| `Collections.unmodifiableList` | 只读类型出口 toList() |
| instanceof + cast | `is` 智能转换 |
| for i 遍历 | `for (x in xs)` |
| 工厂 + 单例 if null | `object` / `by lazy` |
| 多态 if 链 | sealed + when |
| Optional<T> | T?（语言级） |
| builder 链 | named args + apply |

## 47. 反模式大全

| 反模式 | 后果 | 正解 |
| --- | --- | --- |
| `!!` 满天飞 | NPE 回潮 | 2 节消毒链 |
| `lateinit` 用于可能不注入的字段 | UninitializedPropertyAccess | nullable + 检查，或 DI 必达 |
| 返回 `MutableList` 出公开 API | 调用方可变破坏内部 | `List` 出口快照 |
| when 少 else（非 sealed） | 漏分支静默 | sealed 化或 else 显式 error |
| 空 catch 吞异常 | 排障黑洞 | catch 里至少 log 或 rethrow 分类型 |
| `Pair` 当返回值到处传 | 命名漂移 | data class |
| 公开 API 满默认参数 | 兼容性雷（加参数改默认） | 注解 @JvmOverloads 评估/少默认 |
| `as` 强转 | ClassCastException | `as?` + 处理 |
| 委托 everything | 调试跳转地狱 | 直白属性 |
| 热路径创建 lambda 捕获集合 | GC 压力 | 上提/复用 |

## 48. K2 收紧清单

K2（2.0+）比 K1 更严的点（迁移期高频报错）：

| 现象 | K1 | K2 | 处理 |
| --- | --- | --- | --- |
| lambda 参数多重重载歧义 | 宽松匹配首个 | 报 ambiguous | 显式类型 `{ x: Int -> }` |
| when 分支类型不一 | 悄悄 Any | 推断失败提示 | 声明函数返回类型 |
| 智能转换跨 getter 属性 | 有时过 | 收紧 | 局部 val 快照（3 节） |
| 展开操作符在泛型边界 | 宽 | 严 | 明确型参 |
| 递归类型推断深度 | 栈慢 | 更快但报错位置变了 | 按新提示补类型 |

## 49. K2 迁移

- 依赖侧先升（coroutines/serialization 成对），再升 Kotlin 主版本。
- 全量编译后 grep `warning:` 里 K2 新警告（`UNNECESSARY_SAFE_CALL` 等）
  —— 逐条清零再合入。
- 二进制兼容：K2 产物对 K1 消费者兼容（前端差异为主）；
  上游库换 K2 后下游不需要跟。

## 50. 风格细则

- 缩进 4 空格；行宽 120（本仓库 ktlint 口径）。
- 命名：类 PascalCase；函数/变量 camelCase；常量 UPPER_SNAKE；
  包名全小写单词。
- 可见性：默认最小（internal/public 显式标注在 API 上）。
- 注释：公开 API KDoc 必备（见仓库注释纪律：不得出现裸 `*斜杠` 序列）。
- TODO 带责任人与 issue 号：`// TODO(#123) 2026-11: …`。

## 51. 测试结构

- 命名：反引号句子（`` fun `withdraw rejects insufficient funds`() ``）——
  失败报告即人话。
- 结构 AAA：Arrange / Act / Assert 三段空行分隔。
- 一个测试一个行为点；setup 公共部分进 @Before 或 builder 函数。
- 边界集：空 / 单 / 多 / 极值 / 非法 —— 表驱动
  `ParameterizedTest` 或 Kotlin 的循环 + 断言。

## 52. runTest

```kotlin
@Test fun `retry backs off`() = runTest {
    val clock = mutableListOf<Long>()
    runCatching { withRetry { delay(99_000); error("x") } } // 虚拟时间：瞬时
    advanceUntilIdle()
}
```

- runTest 里 delay 免等待（虚拟时钟）；
  `advanceUntilIdle()` 排空任务；`runCurrent()` 只跑当前批。
- Main 调度器：MainDispatcherRule（Android 侧库）注入 StandardTestDispatcher。
- 真实时间场景（集成）：`runTest { withContext(Dispatchers.Default) { ... } }`
  少数路径放行 —— 默认永远虚拟时间。

## 53. 测试替身

| 替身 | 用途 |
| --- | --- |
| Fake（手写可用实现） | repository/时钟/随机源 —— 首选 |
| Stub（固定返回） | 边界依赖 |
| Spy（记录调用） | 验证交互次序 |
| Mock（框架生成） | 只在交互序列复杂时 |

- Fake 可复用可读；mock 大面积使用是设计气味（行为耦合过深）。
- 时间源注入：`Clock` 接口 → 测试里固定 Instant。

## 54. 断言

| 库形态 | 写法 |
| --- | --- |
| JUnit | `assertEquals(expected, actual)` / assertTrue |
| kotlin.test | `actual shouldBe expected`（若引入 kotest） |
| 自定义消息 | 第二参数传说明 |

- 断言对象相等用 data class（equals 自动）；
  浮点 `assertEquals(1e-9, a, b)` 容差重载。
- 失败消息要带上下文（输入构造、状态快照），不是只说 failed。

## 55. 协程测试配方

**配方 A：虚拟时间重试**

```kotlin
@Test fun `retry three times then succeed`() = runTest {
    var calls = 0
    val result = withRetry(times = 3) { calls++; if (calls < 3) error("boom") else "ok" }
    assertEquals("ok", result); assertEquals(3, calls)
}
```

**配方 B：取消传播**

```kotlin
@Test fun `parent cancel kills children`() = runTest {
    var cancelled = false
    val job = launch { try { awaitCancellation() } catch (e: CancellationException) { cancelled = true; throw e } }
    job.cancel(); job.join()
    assertTrue(cancelled)
}
```

**配方 C：Flow 测试（收集快照）**

```kotlin
@Test fun `debounce drops bursts`() = runTest {
    val src = flowOf(1, 2, 3).onEach { delay(10) }  // 10ms 内连发
    val out = mutableListOf<Int>()
    src.debounce(20).take(1).toList(out)            // 只剩 3
    assertEquals(listOf(3), out)
}
```

## 56. 集合性能

粗略量级（JVM，1M 元素级）：

| 操作 | List | Set | Map |
| --- | --- | --- | --- |
| contains | O(n) | O(1) | O(1)（键） |
| 去重 | O(n²)（distinct 建辅助 Set 反而 O(n)） | — | — |
| 索引 | O(1) | ✗ | ✗ |

- 惯性错误：`list.contains` 在循环里 → 建一次 Set（`list.toSet()`）
  热路径 O(n²)→O(n)。
- 序列长链 vs 集合短链：见 22 节阈值；真实分水岭以基准为准（58 节）。

## 57. 内联成本

- inline 展开省调用 + 传参装箱，代价是字节码膨胀；
  度量：`-Xdump-classes` 或字节码对比。
- 经验值：公开 API 内联保留给真热路径（度量证明 ≥2%）；
  其余让 JIT 自己决断。

## 58. 基准

- JVM 微基准：JMH（`jmh-gradle-plugin`）；
  坑：不预热的首轮数据全是谎言。
- 编写：State 注解分摊、Blackhole 消费结果、多轮取中位。
- Android 宏观：Macrobenchmark（启动/滚动场景）——
  微基准与宏基准结论冲突时信宏基准。

## 59. 装箱

- `Int?` 装箱成 Integer（堆分配）—— 热路径集合用
  `IntArray` / `HashMap<Int, V>` 注意键装箱（可用长整型原语数组自实现索引）。
- 泛型原语集合：kotlin 标准库只有数组；第三方（fastutil）按需评估。
- 度量：分配剖析（Profiler alloc 火焰图）看到 Integer 洪流即此因。

## 60. 产物

- 混淆对 Kotlin 特性：data class 的 componentN/copy 可能被 R8 裁
  （反射/序列化使用处加 keep）；
  lambda 编译成类 —— 名称规则 `-Xdont-name-lambda-parameters` 与
  崩溃栈可读性权衡。
- metadata（@Metadata）：跨模块 Kotlin 反射消费；
  混淆 keep 规则里库作者已配（consumer rules），业务侧关注 DTO。

## 快速索引 · 续（61-75 节）

| 想找什么 | 去哪一节 |
| --- | --- |
| DSL 设计 | 61. DSL 设计 |
| 类型安全构建器 | 62. 构建器 |
| 操作符滥用边界 | 63. 操作符设计 |
| 错误建模 | 64. 错误建模 |
| 结果类型 vs 异常 | 65. Result 取舍 |
| 协程最佳实践 | 66. 协程最佳实践 |
| Flow 最佳实践 | 67. Flow 最佳实践 |
| 多线程共享状态 | 68. 共享状态 |
| kotlinx 家族地图 | 69. kotlinx 生态 |
| 多平台差异 | 70. 多平台 |

## 61. DSL 设计

```kotlin
class RouteBuilder {
    var path: String = ""
    private val methods = mutableListOf<String>()
    fun get(block: Handler.() -> Unit) { methods += "GET" }
    fun build() = Route(path, methods)
}
fun route(init: RouteBuilder.() -> Unit): Route = RouteBuilder().apply(init).build()
// 调用侧：route { path = "/x"; get { } }
```

- 三件套：接收者 lambda（`T.() -> Unit`）+ apply 初始化 + build 出口。
- 约束：DSL 函数返回 Unit（链式误用防）；
  嵌套作用域用不同的 receiver 类型隔离词表。
- 典型参考：Gradle Kotlin DSL / kotlinx.html ——
  读它们的源码学接收者设计。

## 62. 构建器

- 简单配置用 named args + copy（不总是需要 builder）；
  真要分步构建：`buildList/buildMap`（标准库）或 61 节 DSL。
- 不可变对象 + copy 组合优于可变 builder 暴露。

## 63. 操作符设计

| 该重载 | 不该重载 |
| --- | --- |
| 数值语义（Vec/Duration/Money） | "把两个东西合并成列表" |
| 集合 get（键访问） | 用 + 做"添加"副作用 |
| in（成员判定） | compareTo 含义不明时 |

- invoke 重载让对象像函数（`validator(x)`）—— 频率控制，滥用难读。

## 64. 错误建模

- 层次：值错误（参数）→ IllegalArgumentException / require；
  状态错误 → IllegalStateException / check；
  业务预期分支 → 返回 sealed 结果类型（不抛）；
  意外故障 → 异常上抛（由顶层统一呈现）。
- 预期流（用户能看懂并处理的）用返回值；意外流用异常 ——
  两条通道别混（异常当 goto 是坑底）。

## 65. Result 取舍

| 维度 | Result<T> | 异常 |
| --- | --- | --- |
| 调用方可见性 | 类型强制处理 | 可能漏 catch |
| 栈成本 | 无 | 有（填充栈贵） |
| 表达分支语义 | 好（map/getOrElse 链） | 嵌套 try |
| 互操作（Java/序列化） | 差 | 中 |

- 深层传递用异常，边界（对 UI/对网络协议）转 Result 呈现；
  `runCatching` 注意吞 CancellationException（协程场景要 rethrow）。

## 66. 协程最佳实践

1. 结构化优先：任何 launch 都有可归因的 scope。
2. suspend 函数尽量主线程安全（内部自行切 IO）——
   调用方不必知道实现细节。
3. 不要用 suspend 修饰不需要挂起的函数（传染性）。
4. `withTimeout` 包外呼；重试带上限。
5. 取消传播别断链：catch (e: CancellationException) rethrow；
   finally 里清理。
6. 测试用虚拟时间（52 节）。

## 67. Flow 最佳实践

1. 冷流保持无副作用；有状态用 stateIn 显式转热。
2. 操作符链短小可组合；复杂逻辑抽具名函数。
3. flowOn 放生产侧；收集侧默认 Main（Android）。
4. 事件用 SharedFlow，状态用 StateFlow（32 节口径）。
5. catch 定位在离故障源最近的层，带恢复策略而非裸吞。

## 68. 共享状态

- 默认答案：不要共享（每协程私有 + Channel/Flow 交换）。
- 必须共享时阶梯：@Volatile（单值可见）→ Atomic（CAS 复合）→
  Mutex（多步临界区）→ 专用线程（Actor 模式，`Channel + 单消费者协程`）。
- 检测：`kotlinx-coroutines-debug` 的
  `DebugProbes`（dump 协程/检测阻塞）。

## 69. kotlinx 生态

| 库 | 用途 | 备注 |
| --- | --- | --- |
| kotlinx-coroutines | 协程核心 | 与 Kotlin 版本成对 |
| kotlinx-serialization | JSON/Proto/CBOR | 编译器插件形态 |
| kotlinx-datetime | 多平台日期 | JVM 可直接 java.time |
| kotlinx-collections-immutable | ImmutableList | Compose 稳定性搭档 |
| kotlinx-io | IO 抽象（多平台） | JVM 可用标准库 |
| kotlinx-html | DSL 生成 HTML | 服务端模板场景 |

## 70. 多平台

- KMP 共享层：`commonMain`；平台实现 `androidMain/iosMain/...`；
  `expect/actual` 对接平台 API。
- JVM 单平台项目（本仓库）不感知 KMP 开销 ——
  但公共库选择时留意是否 multiplatform-ready（跨端潜力）。
- 共享逻辑层纪律：UI 与平台 API 永不进 commonMain。

## 快速索引 · 续（71-85 节）

| 想找什么 | 去哪一节 |
| --- | --- |
| sealed 建模状态机 | 71. 状态机建模 |
| 事件建模 | 72. 事件建模 |
| 接口默认实现 | 73. 接口设计 |
| 扩展函数设计 | 74. 扩展设计 |
| 泛型 API 设计 | 75. 泛型 API |

## 71. 状态机建模

```kotlin
sealed interface OrderState {
    data object Idle : OrderState
    data class Selecting(val items: List<Item>) : OrderState
    data class Paying(val order: Order, val channel: PayChannel) : OrderState
    data class Done(val receipt: Receipt) : OrderState
    data class Failed(val reason: String, val retryFrom: OrderState) : OrderState
}
```

- 转移函数显式：`fun OrderState.pay(...): OrderState = when (this) { ... }`
  —— 非法转移走 else -> error("illegal transition from $this")。
- 好处：穷尽 + 不可变快照（恢复/回放/测试都容易）。

## 72. 事件建模

- 事件是"发生的事实"（过去式命名：OrderPlaced / ItemAdded）；
  命令是"请求做某事"（PlaceOrder）—— 两者分开建模。
- 事件承载快照数据（不引用大对象），便于序列化入队/重放。

## 73. 接口设计

- 接口表达能力（can-do），抽象类表达骨架复用 ——
  多实现预期用接口；模板方法才用抽象类。
- 默认实现谨慎：接口演进默认实现是二进制陷阱源
  （实现方覆盖语义漂移）；@JvmDefault 处理 Java 互操作。

## 74. 扩展设计

- 扩展属于调用方命名空间 —— 跨模块的"语法糖"要克制
  （可发现性差）；核心 API 用成员。
- 扩展点选第三方类型：`fun String.toSlug()` 这类通用工具集中在
  一个 util 文件统一出口。
- 扩展可空接收者（`fun String?.orDefault()`）明确标注语义。

## 75. 泛型 API

- 参数型变先想清楚（9 节）；消费 in 生产 out。
- `reified` 只在 inline 函数可用 —— 需要类型信息的DSL/
  filterIsInstance 场景。
- 公开泛型 API 避免多参数推断纠缠：关键位置显式类型形参。

## 快速索引 · 续（76-90 节）

| 想找什么 | 去哪一节 |
| --- | --- |
| 编译器插件有哪些 | 76. 编译器插件 |
| 编译期校验注解 | 77. 编译期校验 |
| 脚本与 REPL | 78. 脚本 |
| CLI 应用 | 79. CLI |
| 与 Gradle Kotlin DSL | 80. Gradle DSL |
| 常见报错速查 | 81. 报错速查 |
| 性能优化清单 | 82. 性能清单 |
| 代码评审清单 | 83. 评审清单 |
| 术语表 | 84. 术语 |
| 场景速配卡 | 85. 速配卡 |

## 76. 编译器插件

| 插件 | 能力 | 使用面 |
| --- | --- | --- |
| serialization | @Serializable 生成编解码器 | JSON/网络模型 |
| all-open | 去掉 final（Spring 场景） | 框架代理 |
| no-arg | 生成无参构造（JPA） | 框架反射构造 |
| parcelize | Android Parcelable 生成 | Android 层 |
| compose | @Composable 状态系统 | UI 层 |
| power-assert | 断言失败图示 | 测试体验（第三方） |

## 77. 编译期校验

- 要求/检查常量表达：`const` 不可用运行时值。
- 自定义校验注解 + KSP 处理器：编译期报错
  （如 @NonEmpty 校验字符串常量）。
- 仓内纪律：业务校验进运行时 require/check（13/64 节分层）；
  编译期只做结构性约定。

## 78. 脚本

- `kotlinc -script x.kts`；主函数 main(args)。
- 依赖声明：`@file:DependsOn("com.squareup.okhttp3:okhttp:4.12.0")`
  （需 kotlin-scripting 依赖配置环境）。
- 一次性数据加工/冒烟脚本适用；生产逻辑不进脚本（不可测试）。

## 79. CLI

- 入口 `fun main(args: Array<String>)`；
  参数解析用 kotlinx-cli 或 manual（小工具）。
- 退出码：0 成功；非 0 失败分类（1 参数错 / 2 运行错）。
- stdout/stderr 分流：人读的信息 stderr，机器消费的 stdout。

## 80. Gradle DSL

- `build.gradle.kts`：类型安全（编译期检查依赖坐标）；
  `libs.versions.toml` 版本目录单源（本仓库纪律）。
- 常用片段迁移自 Groovy：`val x by lazy { ... }` 替代 `def`；
  `tasks.register("name") { }`。

## 81. 报错速查

| 报错关键词 | 根因方向 |
| --- | --- |
| Unresolved reference | 导入/作用域/多模块编译顺序 |
| Smart cast impossible | 可变属性/跨模块 open（3 节表） |
| Overload resolution ambiguity | K2 重载收紧（48 节）显式类型 |
| Platform type null | Java 边界消毒（2 节） |
| Captured values initialization | 初始化顺序循环，拆 init |
| suspend modifier misuse | 普通函数调 suspend（26 节） |
| ClassCastException | as 强转 → as? |
| ConcurrentModification | 遍历中改集合（24 节） |
| Nothing? 推断异常 | 分支缺返回值补 else |

## 82. 性能清单

- [ ] 热路径无装箱（59 节剖析确认）
- [ ] 列表 contains 已换 Set（56 节）
- [ ] 内联仅限度量过的热区（57 节）
- [ ] 序列长链用 asSequence（22 节阈值）
- [ ] 正则预编译（42 节）
- [ ] Duration 显式单位（39 节）
- [ ] 大字符串构建用 buildString
- [ ] 集合出口快照（21 节只读陷阱）
- [ ] 基准数据有预热与多轮（58 节）

## 83. 评审清单

- [ ] 空 安全消毒链完整（2 节），`!!` 需注释不变式
- [ ] 表达式优先（val 赋值分支）
- [ ] when 穷尽（sealed 化）
- [ ] 出口集合只读快照
- [ ] 异常分类正确（64 节四层）
- [ ] 协程：scope 可归因 / 取消不断链 / runBlocking 无（测试外）
- [ ] Flow：冷热分明（32 节口径）
- [ ] 命名反引号测试句（51 节）
- [ ] 公开 API KDoc + 无裸斜杠星序列（仓库注释门禁）
- [ ] 文件行数预算（仓库 1200/1600）

## 84. 术语

| 术语 | 含义 |
| --- | --- |
| CPS | 续体传递风格（suspend 状态机的底层形态） |
| Continuation | 挂起点恢复的回调载体 |
| Dispatchers | 协程执行的线程/池路由 |
| reified | 内联函数里保留类型信息 |
| Star-projection | `List<*>` 未知型参安全访问 |
| SAM | 单抽象方法接口 |
| inline class / value class | 零分配包装（编译期擦除） |
| data object | 无状态单例（1.9+） |
| K2 | Kotlin 2.x 新前端编译器 |

## 85. 速配卡

| 症状 | 节 |
| --- | --- |
| NPE / !! 崩 | 2, 47 |
| 智能转换失败 | 3, 48 |
| when 不穷尽编译错 | 17, 5 |
| 集合越界/并发改 | 23, 24 |
| 协程不取消 | 27, 66 |
| Flow 收集重复执行 | 30, 32 |
| 泛型型变报错 | 9, 75 |
| Java 互操作怪 | 35, 36 |
| 序列化字段不齐 | 40 |
| 正则不匹配 | 42 |
| 测试卡死/慢 | 52 |
| K2 新报错 | 48, 49 |
| 性能可疑 | 56-59, 82 |
| DSL 怎么写 | 61 |
| 状态机建模 | 71 |
| 错误分层 | 64, 65 |
| 共享状态选择 | 68 |
| 报错看不懂 | 81 |

## 86. 完整模板（可直接抄）

**模板 A：结果建模 + 状态机（服务层标准式）**

```kotlin
sealed interface Outcome<out T> {
    data class Ok<T>(val value: T) : Outcome<T>
    data class Err(val kind: ErrKind, val message: String) : Outcome<Nothing>
}
enum class ErrKind { VALIDATION, NOT_FOUND, CONFLICT, NETWORK }

interface OrderRepo {
    fun place(cmd: PlaceCmd): Flow<Outcome<Order>>
}

class PlaceOrder(private val repo: OrderRepo) {
    operator fun invoke(cmd: PlaceCmd): Flow<Outcome<Order>> = repo.place(cmd)
}
```

**模板 B：测试替身注入（时钟/随机）**

```kotlin
interface Clock { fun now(): Instant }
class FakeClock(var at: Instant = Instant.EPOCH) : Clock {
    override fun now(): Instant = at
    fun advance(ms: Long) { at += ms.ofMilliseconds }
}
```

**模板 C： DSL 构建器**

```kotlin
class TableBuilder {
    private val rows = mutableListOf<List<String>>()
    fun row(vararg cells: String) { rows += cells.toList() }
    fun build(): String = rows.joinToString("\n") { it.joinToString(" | ") { c -> c.padEnd(12) } }
}
fun table(block: TableBuilder.() -> Unit): String = TableBuilder().apply(block).build()
// table { row("name", "qty"); row("apple", "3") }
```

## 快速索引 · 续（87-95 节）

| 想找什么 | 去哪一节 |
| --- | --- |
| 语言深度问答 | 87. 深度问答·语言 |
| 集合深度问答 | 88. 深度问答·集合 |
| 协程深度问答 | 89. 深度问答·协程 |
| Flow 深度问答 | 90. 深度问答·Flow |
| 互操作深度问答 | 91. 深度问答·互操作 |
| 语言版本时间线 | 92. 版本时间线 |
| 扩展函数配方 | 93. 扩展配方 |
| 集合操作配方 | 94. 集合配方 |
| 序列化配方 | 95. 序列化配方 |

## 87. 深度问答·语言

| 问 | 一句话答案 |
| --- | --- |
| val 引用能不能指向可变内容？ | 能（val list = mutableListOf）—— val 锁引用不锁对象 |
| 顶层函数放哪？ | 文件即类（FileKt）；@file:JvmName 改对外名 |
| 尾递归为什么还栈溢出？ | 不是真尾调用位置（最后还有运算）—— 改循环 |
| 字符串 switch 等价物？ | when (s) 分支 + in 集合匹配 |
| `Nothing` 有什么用？ | 永不返回的表达式类型（error/TODO），让推断不抱怨 |
| 泛型擦除后怎么拿类型？ | reified + inline（75 节）；反射兜底（38 节） |
| 为什么属性初始化顺序重要？ | 字段按声明序执行；前面的引用后面会拿到默认值 |
| companion 能实现接口吗？ | 能（单例语义），工厂模式常用 |
| 扩展能被继承覆盖吗？ | 不能（静态解析按编译期类型） |
| operator 惯例在哪查？ | 官方惯例表（7 节精选）；随意造会读不懂 |

## 88. 深度问答·集合

| 问 | 一句话答案 |
| --- | --- |
| List.of vs listOf？ | 同为不可变语义；kotlin 版返回最优实现（可能共享单例 emptyList） |
| distinct 复杂度？ | O(n)（内部 HashSet）；对自定义类型先 override equals |
| groupBy 保序吗？ | 保插入序（LinkedHashMap） |
| associate 和 associateBy 差别？ | 前者 lambda 出 Pair；后者直接键选择 |
| 序列什么时候反而慢？ | 小数据 + 短链（每步调度开销 > 中间集合成本） |
| 取前 N 惰性吗？ | take 在序列上惰性；List 的 take 立即切片 |
| 嵌套扁平 flatten 深度？ | 一层；多层要递归 flatMap |
| Map 遍历删除安全姿势？ | iterator.remove（可变）/ 先快照后删 |
| 集合比较相等？ | List 顺序敏感；Set 无序等 |
| immutable 包装真的不可变？ | Java 侧可绕（36 节）—— 出口 Kotlin toList() 拷贝 |

## 89. 深度问答·协程

| 问 | 一句话答案 |
| --- | --- |
| suspend 函数切换线程了吗？ | 不一定；挂起只是可让出，withContext 才明确切 |
| await 阻塞线程吗？ | 不；挂起等完成 |
| cancel 后代码还跑吗？ | 到下一个挂起点才真正让出（协作式）—— 循环里 ensureActive |
| runBlocking 嵌套协程什么关系？ | 就是普通父 scope；死锁面大（生产禁） |
| async 不 await 会怎样？ | 异常在 await 时才暴露；泄漏到 scope 结束 |
| Job 和 Deferred 差别？ | Deferred 有结果（await） |
| supervisorScope 里子失败兄弟继续？ | 是；子自己善后（try/catch） |
| Mutex 和 synchronized 混用？ | 各自独立锁语义；别双锁嵌套（死锁） |
| 协程里的 ThreadLocal？ | asContextElement 携带（跨挂起保持）；用完清理 |
| 限流怎么做最简？ | Semaphore.withPermit 包请求（33 节） |

## 90. 深度问答·Flow

| 问 | 一句话答案 |
| --- | --- |
| 冷流里能发射自身变量吗？ | 可以（闭包），但多收集者各跑一份（30 节） |
| catch 之后下游收到什么？ | catch 里 emit 的值；不 emit 则终止 |
| flowOn 影响哪个区段？ | 上游（生产侧）换线程；下游收集者不变 |
| conflate 丢什么？ | 只留最新（慢消费者时中间值丢弃） |
| StateFlow 初值必给吗？ | 必给（构造参数） |
| SharedFlow 无订阅者会缓存吗？ | extraBufferCapacity 决定滞留条数 |
| collectLatest 什么时候用？ | 新值来了旧处理作废（搜索/渲染） |
| Flow 能重放历史吗？ | 冷流重收集即重放；热流靠 replay 参数 |
| flow{} 里 try/finally？ | onCompletion 更惯用（含取消通知） |
| 多 Flow 合并谁先到？ | merge 保到达序；zip 严格配对 |

## 91. 深度问答·互操作

| 问 | 一句话答案 |
| --- | --- |
| Kotlin 调 Java getter/setter？ | 直接属性语法（编译器映射） |
| Java 调 Kotlin 默认参数函数？ | 生成全参版本；@JvmOverloads 生成重载 |
| kotlin.Nullable 注解？ | Java 侧看到 T? 的线索（IDE/校验器用） |
| Java 反射调 Kotlin 属性？ | 走 getter/setX；@JvmField 直字段 |
| 数据类 equals 在 Java 侧？ | 正常 override 的 equals —— Objects.equals 即可 |
| 静态常量给 Java switch 用？ | const val 编译成静态 final —— 可 case |
| SAM lambda 传 Java？ | 直接传 { ... }（目标类型明确时） |

## 92. 版本时间线

| 版本 | 关键特性 |
| --- | --- |
| 1.1 | 协程（实验）、typealias |
| 1.2 | 多平台雏形 |
| 1.3 | 协程 stable、inline class 实验 |
| 1.4 | SAM 转换 Kotlin 接口 |
| 1.5 | JVM records 互操作、unsigned 稳定 |
| 1.6 | 构建器 buildList/Map、彻底的 exhaustive when |
| 1.7 | 稳定版 context receiver 前 final 化、definitely non-null |
| 1.8 | 可迭代对象 toList 提升 |
| 1.9 | data object、稳定 enum entries |
| 2.0 | K2 编译器（前端重写：速度与推断严格度） |
| 2.1 | context parameters 预览、guard 条件 in when（`when(x) { is Int if it > 0 -> }`） |
| 2.2+ | 持续收紧推断与 KSP 联动 |

- 仓库当前 Kotlin 2.3 —— 新特性用之前过一遍 92 节时间线确认版本边界。

## 93. 扩展配方

```kotlin
// A: 可空链式默认值
fun String?.orDash(): String = this ?: "—"
// B: 逐词处理
inline fun String.mapWords(transform: (String) -> String) =
    split(" ").joinToString(" ") { transform(it) }
// C: 集合分页视图
fun <T> List<T>.page(no: Int, size: Int): List<T> =
    drop(no * size).take(size)
// D: try 结果管道
inline fun <T> tryOf(block: () -> T): Result<T> = runCatching(block)
// E: 时间戳可读
fun Long.asMills(): Duration = "${this}ms".toDuration()  // 简化示意
```

## 94. 集合配方

```kotlin
// A: topN by key
val top3 = items.sortedByDescending { it.score }.take(3)
// B: 分组计数
val byCat = items.groupingBy { it.category }.eachCount()
// C: 差集（在 A 不在 B）
val fresh = a.filter { it.id !in bIds }
// D: 找第一个匹配或 null + 默认
val pick = items.firstOrNull { it.ok } ?: defaultItem
// E: 展平键值对
val pairs = map.flatMap { (k, v) -> listOf(k to v) }
// F: 双列 zip 成对象
val objs = names.zip(ages) { n, a -> Person(n, a) }
// G: 累计折行
val running = amounts.runningFold(0L) { acc, x -> acc + x }
```

## 95. 序列化配方

```kotlin
// A: 未知字段宽容 + 默认值省输出
val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    isLenient = true
}
// B: 多态标签
@Serializable
sealed interface Event { val id: String
    @Serializable @SerialName("click") data class Click(val x: Int, override val id: String) : Event
    @Serializable @SerialName("view") data class View(override val id: String) : Event
}
// C: 数字精度（金额用字符串传）
@Serializable data class Money(val cents: Long) {
    val yuan get() = cents / 100.0
}
// D: 版本演进（新增字段带默认值=前向兼容）
@Serializable data class Cfg(val a: Int = 0, val b: String? = null)
```

## 快速索引 · 续（96-108 节）

| 想找什么 | 去哪一节 |
| --- | --- |
| 协程进阶配方 | 96. 协程配方续 |
| Flow 配方续 | 97. Flow 配方续 |
| 测试配方续 | 98. 测试配方续 |
| 反模式 FAQ | 99. 反模式 FAQ |
| 与本仓库联动 | 100. 本仓库 Kotlin 纪律 |
| 数字与精度 | 101. 数字与精度 |
| 位运算 | 102. 位运算 |
| 枚举进阶 | 103. 枚举 |
| 密封 vs 枚举 | 104. sealed vs enum |
| 值类（value class） | 105. value class |
| 委托进阶 | 106. 委托进阶 |
| 维护规范 | 107. 维护规范 |
| 使用回执 | 108. 使用回执 |

## 96. 协程配方续

**配方 D：单飞（首个结果胜出）**

```kotlin
suspend fun fetchWithFallback(primary: suspend () -> Data, backup: suspend () -> Data): Data {
    val primaryResult = async { primary() }
    val backupResult = async(start = CoroutineStart.LAZY) { backup() }
    return try {
        primaryResult.await()
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        backupResult.await()
    } finally {
        primaryResult.cancel()
        backupResult.cancel()
    }
}
```

**配方 E：限流并发批处理**

```kotlin
suspend fun <T, R> parallelMap(
    items: List<T>, concurrency: Int = 4,
    transform: suspend (T) -> R,
): List<R> = coroutineScope {
    val gate = Semaphore(concurrency)
    items.map { item ->
        async {
            gate.withPermit { transform(item) }
        }
    }.awaitAll()
}
```

**配方 F：超时分级降级**

```kotlin
suspend fun loadProfile(): Profile =
    withTimeoutOrNull(2_000.milliseconds) { api.profile() }
        ?: withTimeoutOrNull(1_500.milliseconds) { cache.profile() }
        ?: Profile.placeholder()
```

## 97. Flow 配方续

**配方 D：带状态的扫描（去抖+累计）**

```kotlin
events
    .scan(0 to 0L) { (count, _), e -> (count + 1) to System.nanoTime() }
    .filter { it.first >= 10 }        // 采样后处理
```

**配方 E：条件熔断**

```kotlin
stream.retryWhen { cause, attempt ->
    (attempt < 3 && cause is IOException).also { willRetry ->
        if (willRetry) delay((attempt + 1) * 500.milliseconds)
    }
}
```

**配方 F：两个流首值竞速**

```kotlin
val first = flowOf(a, b).map { it.toString() }
val result = select<String> {
    first.onEach { }.onAwait { it }
    timer(1_000.milliseconds).onAwait { "timeout" }
}
```

## 98. 测试配方续

**配方 D：断言异常类型+消息**

```kotlin
assertFailsWith<IllegalArgumentException> {
    Money.of(-1)
}.message.also { assertTrue(it!!.contains("amount")) }
```

**配方 E：参数化表驱动**

```kotlin
@TestFactory fun `partition boundaries`() = listOf(
    -1 to "neg", 0 to "zero", 1 to "pos",
).map { (input, expected) ->
    DynamicTest.dynamicTest("$input -> $expected") {
        assertEquals(expected, classify(input))
    }
}
```

## 99. 反模式 FAQ

| 问（现象） | 根因 | 正解 |
| --- | --- | --- |
| "为什么测试里 delay 卡住" | runTest 里嵌了真 sleep/IO | 虚拟时间纪律（52 节） |
| "lambda return 把外层断了" | 非局部返回 | return@label 或匿名 fun |
| "修改集合抛 ConcurrentModification" | 遍历中改 | 快照/removeIf |
| "when 分支类型错" | 表达式值类型不一致 | 显式函数返回类型 |
| "扩展函数找不到" | 导入它所在的包（扩展需 import） | 用成员/移入同包 |
| "泛型 Array<String> 转 List<String> 报" | 原语数组差异 | toList() 适配 |
| "序列没有执行" | 没有终结操作 | toList/first 触发 |

## 100. 本仓库 Kotlin 纪律

（随仓库 AGENTS.md 同步；此处是 Kotlin 侧摘录）

- 纯 JVM 模块（core:*）禁 Android 依赖 —— 协程/serialization 版本
  走 libs.versions.toml 单源。
- main 源码禁反射派发 / printStackTrace（CI 反模式门禁）；
  注释里禁裸 `星斜杠` 序列（注释完整性门禁）。
- 测试纪律：runTest + 假钟注入；runBlocking/Thread.sleep 只降不升
  （棘轮基线）。
- 文件预算 main ≤1200 / test ≤1600；超限沿职责缝拆文件。
- 提交规范 `type(scope): 摘要`；代码 PR 带 .changelog 片段。

## 101. 数字与精度

- 金额：整数最小单位（分）存储 Long；展示层格式化 ——
  浮点禁用于钱（0.1 + 0.2）。
- 比较浮点：容差（8 节）。
- 整数溢出：Int 上限 2^31-1（≈21.4 亿）—— 毫秒时间戳 2038 前后问题
  用 Long。
- 除法：`/` 整型截断；浮点转换显式 `.toDouble()`；`floorDiv`。

## 102. 位运算

- `and/or/xor`（中缀函数名即操作）；`shl/shr/ushr` 移位；
  `inv()` 取反。
- 高频坑：Int 是有符号——ushr 才是逻辑右移；
  颜色值打包：`(a shl 24) or (r shl 16) or (g shl 8) or b`。

## 103. 枚举

```kotlin
enum class Planet(val gravity: Double) {
    EARTH(9.81), MARS(3.71);
    fun weight(mass: Double) = mass * gravity
    companion object { fun of(name: String?) = entries.firstOrNull { it.name.equals(name, true) } }
}
```

- entries（1.9+）替代 values()（返回共享列表）。
- 枚举可实现接口、有构造参数；不能继承类（单继承让位）。

## 104. sealed vs enum

| 维度 | enum | sealed |
| --- | --- |
| 实例数 | 固定单例集 | 有限子类型，子类可带状态 |
| 携带数据 | 全员同构字段 | 每个成员异构（data class/独立字段） |
| when 穷尽 | ✓ | ✓ |
| 扩展（同模块加成员） | 加常量 | 加子类 |
| 序列化天然 | name 稳定 | 需 @SerialName 判别标签 |

- 口诀：状态同构选 enum；状态异构选 sealed。

## 105. value class

```kotlin
@JvmInline
value class UserId(val raw: Long)
fun find(id: UserId) { }     // 调用处零包装成本（编译期擦除为 Long）
```

- 场景：防"裸 Long 传错参"（类型安全标签）；不增加分配。
- 限制：只能一个属性；不能继承；反射/序列化注意（擦除后无类型痕迹）。

## 106. 委托进阶

```kotlin
class PrefRepo(prefs: KeyValueStore) : KeyValueStore by prefs {
    // 只转发 + 补充：装饰器零样板
    fun cached(key: String): Flow<String?> = observe(key)
}
// 自定义属性委托骨架
class Once<T>(private val init: () -> T) : ReadOnlyProperty<Any?, T> {
    private var v: T? = null
    override fun getValue(thisRef: Any?, prop: KProperty<*>): T =
        v ?: init().also { v = it }
}
```

## 107. 维护规范

- 结构三段式恒定（必看/索引/编号章节）—— 服务按此解析。
- 惯例以官方文档为准绳；版本边界标参考时点（92 节）。
- 新增坑必须有"症状→根因→正解"三要素。
- 与仓库强相关的纪律（100 节）随 AGENTS.md 同步演化。

## 108. 使用回执

完成任务后如某节救了你，在回复里点名该节 ——
供知识库 vNext 评估扩写优先级；发现错误直接指出（带节号），
48 小时内修订并 bump version。

## 快速索引 · 续（109-120 节）

| 想找什么 | 去哪一节 |
| --- | --- |
| 语言进阶问答 | 109. 深度问答·进阶 |
| 测试问答 | 110. 深度问答·测试 |
| 惯用法示例集 | 111. 惯用法示例 |
| 协程调度怎么选 | 112. 调度决策 |
| 集合怎么选型 | 113. 集合选型 |
| Flow 怎么选型 | 114. Flow 选型 |
| 错误怎么处理 | 115. 错误决策 |
| 可见性怎么定 | 116. 可见性设计 |
| Kotlin/Java 对照 | 117. 双语对照 |
| 重构手法 | 118. 重构手法 |
| 性能实战案例 | 119. 性能案例 |
| 总速查卡 | 120. 总速查卡 |

## 109. 深度问答·进阶

| 问 | 一句话答案 |
| --- | --- |
| value class 和 data class 差别？ | 前者零分配标签（单属性）；后者全功能载体（105 节） |
| 为什么扩展函数是静态解析？ | 编译期按接收者声明类型绑定（74 节） |
| 泛型函数能内联化开销吗？ | inline + reified 消类型擦除成本（75 节） |
| object 初始化线程安全吗？ | 是（类加载锁） |
| 接口属性能带默认实现吗？ | 能（getter 默认体）—— 二进制演进注意 73 节 |
| 尾 lambda 为什么能移出括号？ | 语法糖：最后一个函数类型参数可外置（12 节） |
| @JvmStatic 和 companion 共存？ | 生成两个入口（伴生 + 静态桥） |
| 字符串模板能调用函数吗？ | 能（${obj.f()}）—— 保持简单表达式，复杂逻辑先算 |
| 标签 return 的标签名怎么定？ | lambda 传入的函数名或自定义 `run { }` 处标签 |
| 嵌套函数（局部函数）能用吗？ | 能；只在小范围复用时用（可读性边界） |
| operator invoke 链能编译过但难读 | 设计味道：限制到验证器/查询 DSL |
| typealias 能泛型化吗？ | 能（typealias Handler<T> = (T) -> Unit）—— 纯命名无新类型 |

## 110. 深度问答·测试

| 问 | 一句话答案 |
| --- | --- |
| 测私有函数？ | 通过公开行为测；需要测私有时=拆类信号 |
| Fake 状态怎么构造？ | 构造参数注入（列表预设/时钟/随机）+ 可变状态字段 |
| 断言消息怎么写好？ | 带输入与期望的完整句子（54 节） |
| 协程测试为什么偶尔 hang？ | 没排空任务（advanceUntilIdle）或真 IO 混入 |
| 属性测试什么时候用？ | 边界难以枚举的纯函数（round-trip/不变量） |
- 优先级：行为正确 > 边界 > 性能回归 > 覆盖率数字。

## 111. 惯用法示例

```kotlin
// 1) 校验并立即失败（参数层）
fun transfer(from: Account, to: Account, cents: Long) {
    require(cents > 0) { "amount must be positive, got $cents" }
    require(from.id != to.id) { "self transfer" }
}
// 2) when 作纯函数
fun fare(age: Int): Int = when {
    age < 0 -> error("negative age")
    age <= 6 -> 0
    age <= 18 -> 900
    else -> 1800
}
// 3) 解构 + 过滤链
val (ok, failed) = results.partition { it.isSuccess }
// 4) 构建只读快照出口
val snapshot: List<Item> = items.toList()
// 5) 标签循环
outer@ for (p in providers) {
    for (s in p.sources) {
        if (s.stale) continue@outer
        if (load(s)) break@outer
    }
}
// 6) 密封错误呈现
fun render(err: ErrKind): String = when (err) {
    ErrKind.VALIDATION -> "输入有误"
    ErrKind.NETWORK -> "网络不稳，稍后重试"
    else -> "服务异常（${err.name}）"
}
```

## 112. 调度决策

| 任务形态 | 选择 |
| --- | --- |
| 纯计算（解析/排序） | Default |
| 文件/DB 阻塞 | withContext(IO) |
| UI 更新 | Main（Android） |
| 混合（取数+算+展示） | 内部分段切换（函数内自管理） |
| 限流外部 API | IO + Semaphore(3) |
| 独占池（防饥饿） | limitedParallelism 独占配额 |

## 113. 集合选型

| 需求 | 结构 |
| --- | --- |
| 顺序遍历 | List |
| 判存在（去重） | Set |
| 键定位/计数 | Map |
| 键有序（LRU 类） | LinkedHashMap |
| 下标 O(1) | List/数组 |
| 原语密集 | IntArray 等（59 节） |
| 构建期可变出口只读 | buildList → List |

## 114. Flow 选型

| 需求 | 形态 |
| --- | --- |
| 每次订阅独立执行 | 冷 flow{} |
| 全局单值状态 | StateFlow |
| 一次性事件广播 | SharedFlow(replay=0) |
| 外部回调源 | callbackFlow |
| 合并多源实时值 | combine |
| 搜索（取消旧） | debounce + flatMapLatest |

## 115. 错误决策

| 情形 | 动作 |
| --- | --- |
| 调用方传错 | require（IllegalArgumentException） |
| 内部状态不对 | check（IllegalStateException） |
| 达不到（不可能路径） | error / TODO |
| 业务可预期分支 | sealed Outcome 返回 |
| 外部系统故障 | 异常上抛 + 边界转结果呈现 |
| 取消 | 永远 rethrow CancellationException |

## 116. 可见性设计

- 默认 public 只给"设计的 API 面"；其余 internal/private。
- 测试需要 internal：同模块 test 编译单元可见（无需 public 放宽）。
- internal + @JvmName 组合防 Java 侧误用。

## 117. 双语对照

```kotlin
// Kotlin
class Page(val url: String, private val cache: Cache) {
    fun render(): String = cached(url) ?: fetch(url).also { cache.put(url, it) }
}
```
```java
// Java 等价（IDE 生成视角）
public final class Page {
    private final String url;
    private final Cache cache;
    public Page(String url, Cache cache) { this.url = url; this.cache = cache; }
    public final String render() {
        String hit = cache.get(url);
        return hit != null ? hit : fetchAndStore();
    }
}
```

- 对照要点：final 默认 / 属性 vs 字段+访问器 / elvis vs 三元 +
  null 处理样板 —— Kotlin 消除的是样板，语义一一对应。

## 118. 重构手法

| 信号 | 手法 |
| --- | --- |
| 同一 if-null 三连 | 提函数 + 2 节消毒链 |
| when 长链重复 | sealed 化 + 穷尽（17 节） |
| Pair 漂过三层 | 升 data class 命名 |
| builder 满天飞 | named args + copy（62 节） |
| 工具类全是 static | 顶层函数 + 扩展集中 |
| 异常当流控 | sealed Outcome（64 节） |
| 集合链超 5 步 | asSequence + 拆命名步骤 |

## 119. 性能案例

**案例 A：列表判重 O(n²) → O(n)**

```kotlin
// before：每行 contains 全表扫
val dup = rows.filter { seen.contains(it.key) }
// after：一次建 Set
val seenKeys = rows.map { it.key }.toSet()
val dup = rows.filter { it.key in seenKeys }   // 语义同构需按业务定夺
```
度量：1M 行从 ~分钟级 → 毫秒级。

**案例 B：热路径装箱**

```kotlin
// before：Map<Int, Int> 键装箱洪流
// after：IntArray 索引 + 原语累计（离散小域值场景）
```
分配剖析确认 Integer 分配下降 ~90%。

## 120. 总速查卡

一屏版（打印贴墙）：

```text
空安全   : ?. ?: let !!(注释) 消毒链 (2)
智能转换 : val 快照 / sealed when / elvis (3)
表达式   : val x = if/when/try (5)
集合     : 出口快照 / Set 判存在 / 序列长链 (21-23)
协程     : scope 可归因 / 取消协作 / runBlocking 禁 (26-28)
Flow     : 冷热分明 / 事件 SharedFlow (30-32)
错误     : require-check-error-sealed 四层 (64)
测试     : runTest 虚拟时间 / Fake 优先 (51-55)
风格     : val 默认 / 表达式 / sealed (46-50)
互操作   : @JvmStatic/Overloads/Throws (35-36)
```

## 快速索引 · 续（121-130 节）

| 想找什么 | 去哪一节 |
| --- | --- |
| 标准库深挖 | 121. 标准库深挖 |
| 协程状态机 | 122. 协程内部 |
| 挂起与恢复 | 123. 挂起点 |
| Flow 内部 | 124. Flow 内部 |
| 风格示例库 | 125. 风格示例库 |
| 面试级问答 | 126. 面试问答 |
| 陷阱补遗 | 127. 陷阱补遗 |
| 设计模式 Kotlin 化 | 128. 模式映射 |
| 数值单位安全 | 129. 单位安全 |
| 补充速配 | 130. 补充速配 |

## 121. 标准库深挖

- `Result`： isSuccess/isFailure；`fold(onSuccess, onFailure)`；
  `mapCatching/getOrElse`。注意：Result 是值类型语义（不替代异常通道，65 节）。
- 比较：`compareBy` 链式（`thenBy`）；
  `nullsLast(naturalOrder())` 组合空序。
- `sequence {}` 与 `Iterator` 桥：`sequence { yield(...) }`。
- `Pair/Triple` 只做临时；`to` 中缀建 Pair（Map 构建惯用）。

## 122. 协程内部

- suspend 函数编译为带 Continuation 的状态机
  （label 跳转 + 每挂起点存现场）—— 反编译看 CPS 形态。
- 恢复调度：Continuation.resumeWith → Dispatcher 决定哪个线程执行下一步。
- 异常传播路径：child fail → Job.notifyCompletion → parent
  （supervisor 截断）—— 理解这条链才能解释 27 节规则。

## 123. 挂起点

- 挂起不是"线程让出"那么简单：状态保存（label/局部表）+ 
  完成回调入调度队列。
- 挂起间代码可能跑在不同线程（withContext 边界）——
  ThreadLocal 需 asContextElement（89 节）。

## 124. Flow 内部

- 冷流 = 每次收集执行一遍生产逻辑（30 节）；
  操作符即"收集时包装"（装饰器链）。
- stateIn 内部：多路复用 + 版本号广播（初始值即版本0）。

## 125. 风格示例库

```kotlin
// 差：可变累积
var total = 0
for (i in items) { if (i.active) total += i.price }
// 好：表达式链
val total = items.filter { it.active }.sumOf { it.price }
// 差：null 检查金字塔
if (user != null) { val addr = user.address; if (addr != null) { send(addr) } }
// 好：链式
user?.address?.let(::send)
// 差：布尔参数
load(true)  // true 是啥？？
// 好：命名参数/枚举
load(fromCache = true)
// 差：魔法字符串散落
// 好：常量集中
object Routes { const val HOME = "home" }
```

## 126. 面试问答

| 问 | 要点 |
| --- | --- |
| 协程 vs 线程？ | 挂起不占线程；调度单位轻量；结构化生命周期 |
| suspend 原理？ | CPS 状态机 + Continuation（122 节） |
| 结构化并发解决什么？ | 泄漏与取消传播的确定性（27 节） |
| Flow vs Rx？ | 相似内核；Flow 融入协程 + 语言级挂起 + 背压模型差异 |
| sealed 价值？ | 穷尽检查 = 编译期覆盖保证（17 节） |
| val 不变集合就是不可变吗？ | 否（只读视图，21 节出口快照） |
| K2 变了什么？ | 前端重写：推断严格 + 速度（48 节清单） |
| inline 何时亏？ | 膨胀 > 收益（度量，57 节） |
| 序列阈值？ | 链长×数据量（22 节+基准） |
| 平台类型怎么管？ | 边界消毒（2 节） |

## 127. 陷阱补遗

| 陷阱 | 解法 |
| --- | --- |
| `listOf()` 空推断成 List<Nothing> 显式泛型 | emptyList() |
| 数字字面量跨类型：`1 + 1L` 报 | 显式转换 toLong() |
- 字符比较 `==` 是值比较；`Char` 不是数字（toInt() 显式）。
- 字符串数字：`"42".toInt()` 抛 NumberFormatException ——
  `toIntOrNull() ?: 0`。
- `x in list` 对自定义类型要求 equals/hashCode 成对。

## 128. 模式映射

| 经典模式 | Kotlin 形态 |
| --- | --- |
| Builder | named args + copy / DSL（62 节） |
| Singleton | object |
| Factory Method | companion operator invoke |
| Decorator | interface by 委托转发（19 节） |
| Strategy | 高阶函数参数 |
| Observer | Flow/StateFlow |
| Command | 函数值 / data class 携参数 |
| Visitor | sealed + when（加成员不破坏） |
| Template Method | 高阶函数插槽 |
| Null Object | 默认值 + elvis（2 节） |

## 129. 单位安全

```kotlin
@JvmInline value class Millis(val v: Long)
@JvmInline value class KiloBytes(val v: Long)
fun wait(t: Millis) { }
fun alloc(size: KiloBytes) { }
// wait(500) 编译不过 —— 必须 Millis(500)：单位错误编译期拦下
```

- Duration（39 节）是标准库内置的成品方案；
  自定义 value class 用于领域单位（KB/ms/度）。

## 130. 补充速配

| 症状 | 节 |
| --- | --- |
| 数字/单位混用事故 | 101, 129 |
| 设计模式想写 Java 式 | 128 |
| 标准库不知道有没有 | 121, 44 |
| 想看内部原理 | 122-124 |
| 风格不统一 | 125, 50 |
| 面试/评审争论 | 126 |
- 使用回执同 108 节。

## 快速索引 · 续（131 节）

| 想找什么 | 去哪一节 |
| --- | --- |
| 服务层完整模板 | 131. 服务层模板（完整可抄） |

## 131. 服务层模板（完整可抄）

repository + 结果 + 状态机的完整组合（服务层标准式）：

```kotlin
sealed interface Outcome<out T> {
    data class Ok<T>(val value: T) : Outcome<T>
    data class Err(val kind: ErrKind, val message: String, val retryable: Boolean) : Outcome<Nothing>
    data object Loading : Outcome<Nothing>
}

interface OrderApi {
    suspend fun place(cmd: PlaceCmd): RawResp
    suspend fun get(id: String): RawResp
}

class OrderRepository(private val api: OrderApi) {
    /** 下单：网络错误可重试；业务冲突不可重试 */
    suspend fun place(cmd: PlaceCmd): Outcome<Order> = try {
        val raw = withTimeout(10_000.milliseconds) { api.place(cmd) }
        when (raw.code) {
            0 -> Outcome.Ok(raw.toOrder())
            409 -> Outcome.Err(ErrKind.CONFLICT, "订单已存在", retryable = false)
            in 400..499 -> Outcome.Err(ErrKind.VALIDATION, raw.msg, retryable = false)
            else -> Outcome.Err(ErrKind.NETWORK, "服务暂不可用", retryable = true)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: TimeoutCancellationException) {
        Outcome.Err(ErrKind.NETWORK, "请求超时", retryable = true)
    } catch (e: Exception) {
        Outcome.Err(ErrKind.NETWORK, e.message ?: "unknown", retryable = true)
    }

    fun stream(id: String): Flow<Outcome<Order>> = flow {
        emit(Outcome.Loading)
        emit(retryIO { get(id).toOrder() })
    }
}

inline fun <T> retryIO(
    times: Int = 3,
    initial: Duration = 500.milliseconds,
    block: () -> T,
): T {
    var delayMs = initial
    repeat(times - 1) {
        try { return block() } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        runCatching { delay(delayMs) }.onFailure { throw it }
        delayMs *= 2
    }
    return block()
}
```

要点：

- ErrKind + retryable 让调用方零猜（要不要重试是模型的一部分）。
- `catch (e: CancellationException) throw e` 三连 —— 所有 catch 的
  第一行纪律（66 节最佳实践）。
- 与 5 节 when、46 节惯用、64 节错误分层、96 节配方配套。
- Android 层直接复用：VM 里 `stateIn` 包这个 stream（Android 知识库 31 节配方 A）。
