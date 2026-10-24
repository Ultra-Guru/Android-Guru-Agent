<!-- kb: id=java | role=builtin_coding_java | title=Java 专家知识库 | version=1 -->
# Java 专家知识库

## 必看内容

- **版本基线**：新项目最低 Java 21 LTS；Java 25（2025-09-16 GA，18 个 JEP）是当前
  LTS。预览特性（结构化并发 505、Stable Values 502）不进生产主干 —— `--enable-preview`
  的字节码绑定预览 minor 版本，跨 JDK 升级直接拒载（见 8 节）。
- **虚拟线程纪律**（21 JEP 444 / 24 JEP 491）：IO 密集用 thread-per-request，
  **永不池化**虚拟线程；JDK 24 起 synchronized 不再 pin carrier，但 JNI 原生帧
  仍 pin；并发限界用 `Semaphore`，检测用 JFR `jdk.VirtualThreadPinned`（见 6/7 节）。
- **分配不是免费的**：分配速率是 GC 频率与延迟的第一自变量（20/37 节）；
  逃逸分析经常失效（15 节），别把性能寄托在 JIT 魔法上 —— 先 JFR 采样找分配热点。
- **JMM 纪律**：共享可变状态默认禁止；安全 = 不可变对象（全 final 字段 + 不让
  `this` 逃逸）或全套锁/CAS；`volatile` 只给可见性，`i++` 依然不原子（40-42 节）。
- **现代数据建模**：sealed interface + record + exhaustive switch = 代数数据类型；
  不写 default，让编译器在新增分支时报错 —— 这是 Java 的"穷尽性保护"（77-79 节）。
- **异常是异常**：`fillInStackTrace` 微秒级；C2 fast-throw 会丢栈 —— 生产
  "空栈 NPE"的元凶（28 节）；预期失败用 Optional/结果类型，别用异常做控制流。
- **线程池纪律**：禁 `Executors.newFixedThreadPool`（无界队列 OOM）；池必须有：
  有界队列 + 命名 ThreadFactory + 显式拒绝策略 + uncaughtExceptionHandler；
  `submit` 会把异常吞进 Future（见 111 节）。
- **CompletableFuture**：默认 commonPool，单核容器 parallelism=0 → 每任务新线程；
  `orTimeout` 只完成 future、不中断任务；重回调禁止挂在默认线程上（50 节）。
- **GC 选型**：默认 G1；大堆 + P99.9 亚 10ms → 世代化 ZGC（21+，24 起唯一模式），
  ZGC 用堆余量买延迟（SoftMaxHeapSize < Xmx）；调优先降分配率再谈 flag（30-37 节）。
- **诊断套路**：JFR 常开（默认档 <1% 开销）；heap dump 是分钟级 STW 的核弹，
  先想后扔；CPU 飙高三步：top -H → Thread.print 对线 → ExecutionSample 火焰图。
- **容器适配**：默认 MaxRAMPercentage=25% 起步；RSS > Xmx 是常态（Metaspace/
  CodeCache/DirectBuffer/线程栈），OOMKilled 先查堆外（38/39 节）。
- **反模式肌肉记忆**：可变静态、共享 SimpleDateFormat、double 存钱、`==` 比装箱、
  String.intern 滥用、无界队列/缓存/重试 —— 每条都对应真实事故（104-114 节）。

## 快速索引

| 想找什么 | 去哪一节 |
| --- | --- |
| 21 该不该升、升了用什么 | 1. Java 21 LTS 落地要点 |
| 22/23/24 每个版本有什么 | 2. Java 22-24 特性雷达 |
| 25 的升级动作清单 | 3. Java 25 LTS 升级清单 |
| --release 与 class 版本 65/69 | 4. 版本迁移与字节码陷阱 |
| 对象头 12B 变 8B 省内存 | 5. Compact Object Headers 与对象头成本 |
| 虚拟线程怎么用不翻车 | 6. 虚拟线程纪律与成本模型 |
| 虚拟线程卡死 / pinning | 7. 虚拟线程 pinning 事故与根治 |
| StructuredTaskScope 现在能用吗 | 8. 结构化并发 JEP 505 现状 |
| ThreadLocal 的替代品 | 9. Scoped Values 替代 ThreadLocal |
| 堆外内存 / MemorySegment | 10. FFM API 堆外内存纪律 |
| Unsafe 被移除怎么办 | 11. Security Manager 与 Unsafe 退场 |
| finalize 还有吗、Cleaner 怎么用 | 12. finalization 之死与 Cleaner 纪律 |
| 依赖冲突 / 类遮蔽排查 | 13. 模块系统与类路径地狱 |
| CodeCache 满了会怎样 | 14. 分层编译与 CodeCache 管理 |
| 逃逸分析为什么没生效 | 15. 逃逸分析与标量替换失效清单 |
| 方法多大还能被内联 | 16. 内联成本模型与阈值 |
| 虚调用为什么慢 | 17. 去虚化与 megamorphic 调用点 |
| 性能忽好忽坏 / 去优化 | 18. OSR、热身与去优化风暴 |
| 循环写法影响 JIT | 19. 分支预测与循环优化边界 |
| 对象分配的真实开销 | 20. 分配成本模型与 TLAB |
| 字符串拼接 / String dedup | 21. String 深水区与 dedup |
| 全 JVM 神秘卡顿 | 22. SafePoint 与 Time-To-Safepoint |
| 怎么看 JIT 干了什么 | 23. JIT 观测与诊断标志 |
| List<Integer> 为什么慢 | 24. 原始类型特化与数据结构设计 |
| 什么时候用堆外 | 25. on-heap 与 off-heap 决策 |
| Integer == 陷阱 | 26. Autoboxing 成本事故 |
| 不可变真的更快吗 | 27. 不可变对象与 JIT 优化 |
| 异常很热怎么办 | 28. 异常成本模型与 fast-throw 陷阱 |

## 1. Java 21 LTS 落地要点

- 定位：**现代 Java 的最低基线**。转正全家桶：JEP 444 虚拟线程、JEP 440 record
  patterns、JEP 441 switch 模式匹配、JEP 431 Sequenced Collections、JEP 452 KEM；
  JEP 439 世代化 ZGC 需显式 `-XX:+ZGenerational`（23 起默认，24 起唯一模式）。
- FFM（外部函数与内存）在 21 仍是第三次预览（442），22 才转正（454）——
  21 上不要写 FFM 生产代码，API 面与 22+ 不同。
- 升级前检查清单：字节码工具链（ASM/ByteBuddy/Javassist）必须支持 class 版本
  65；`--add-opens` 清单（17 起强封装）；Security Manager 相关代码开始清理（486 预告）。
- 生态矩阵经验：Spring Boot 3.2+ / Jakarta 系按 21 设计；旧 EE 8 栈（javax.*）
  升级先过 jakarta 包重命名这一关（见 84/110 节）。
- 运行时感知：helpful NPE 消息（14+）默认开；compact strings（9+）让 Latin-1
  字符串的内存/性能测量结果与老资料明显不同。
- 事故模式：升级后偶发 `UnsupportedClassVersionError` —— 制品里混着旧 JDK
  编译的第三方 jar；用 jdeps 扫依赖、enforcer 锁版本（13 节）。

## 2. Java 22-24 特性雷达

- 22：JEP 454 FFM 转正；456 未命名变量 `_`；423 G1 region pinning（JNI 临界区
  不再全堆停顿）；447 flexible constructor 首预览；459 String Templates 二预（后撤回）。
- 23：JEP 471 `sun.misc.Unsafe` 内存访问方法弃用待移除；474 ZGC 非世代模式
  弃用（世代化转正为默认）；476 模块导入预览；477 隐式类预览；479 移除 32 位
  Windows 移植；**javac 默认不再隐式启用注解处理器**（Lombok 需显式配置，86 节）。
- 24：JEP 484 Class-File API 转正；485 Stream Gatherers 转正；486 Security
  Manager 永久禁用；**491 synchronized 不再 pin 虚拟线程**；498 Unsafe 首次使用
  运行时警告；490 ZGC 非世代模式移除。
- 纪律：预览可能撤回 —— String Templates 两轮预览后被整体收回，押注预览 API
  的代码一年内可能白写；预览只做 spike，不做主干。
- AOT 路线：24 的 493（链接运行时镜像）→ 25 的 514（AOT 类加载/链接缓存），
  冷启动敏感服务开始受益（18 节）；配套 512（紧凑源文件/instance main）。
- 库作者注意：484 Class-File API 逐步替代内部 ASM —— 字节码工具链迁移，
  自研字节码操作别再绑 `jdk.internal.org.objectweb.asm`。

## 3. Java 25 LTS 升级清单

- GA 2025-09-16，18 个 JEP，其中 7 个转正：**506 Scoped Values**、510 KDF API、
  511 模块导入声明、512 紧凑源文件与 instance main 等；预览：502 Stable Values、
  505 结构化并发（第五轮）、507 primitive patterns、513 flexible ctor。
- **JEP 519 Compact Object Headers**：`-XX:+UseCompactObjectHeaders` 开启，
  对象头 96bit→64bit（12B→8B）；对象密集负载堆省 10-20%（上限约 30%），
  JDK 26 起默认（JEP 534）。依赖 header 位布局的黑代码与 JOL（需 0.17+）
  先审（5 节）。
- `import module java.base;`（511）一行导入整个模块导出包 —— 只适合脚本与
  示例代码；正式代码里具体 import 的可读性与冲突优先级更可控。
- JEP 509 JFR CPU-Time Profiling（实验）：按线程 CPU 时记账，IO 密集场景
  不再把"等锁/等 IO"误报成 CPU 热点（94 节）。
- Stable Values（502，预览）：JVM 可视为常量的惰性单值 —— 面向未来 AOT
  常量折叠，现阶段只跟踪，不进生产。
- 升级顺序建议：先跑 Unsafe/SM 警告清零（11 节清单）→ 灰度开 COH 压测
  （5 节）→ 再收 ScopedValue 重构红利（9 节）；预览项一律不动。

## 4. 版本迁移与字节码陷阱

- class 文件版本对照：61(17) 65(21) 66(22) 67(23) 68(24) 69(25)；
  `UnsupportedClassVersionError` = 消费方 JDK 比编译方老，先查运行端。
- **`--release` 才是正解**：它用历史 JDK 的 API 签名数据（ct.sym）校验，
  防止误用"编译时存在、运行时不存在"的方法；`-source/-target` 只控语法与
  版本号，仍会静默链接当前 JDK 私有 API → 运行时 `NoSuchMethodError`。
- 常见事故：shaded 依赖里的旧 ASM 在新 JVM 上拒绝新 class
  （`Unsupported class file major version 69`）—— 先升 agent/字节码库再升 JDK。
- nestmates（11+）：内部类私有互访走 NestHost 属性；老反射/序列化 hack 按
  InnerClasses 属性推断访问边界会出错。
- 多版本构建矩阵：统一 `maven.compiler.release`；CI 用**目标 JDK 运行**测试
  —— 编译通过 ≠ 老运行时行为一致（字符串/日期/浮点边缘、GC 默认值都不同）。
- 诊断命令：`jdeps --multi-release` 看依赖；`-verbose:class` 看真实加载来源。

## 5. Compact Object Headers 与对象头成本

- 机制：mark word 与 klass pointer 合并压缩进一个 64bit 字（类指针窄化、
  monitor 元数据外移）；配合 8B 对齐，小对象典型省 4-8B/个。
- 收益边界（JEP 519 实测口径）：对象密集负载（DTO 图、AST、缓存 value、
  小对象数组）堆 -10%~-20%，缓存命中带动 CPU 再省数个点；空大堆服务无感。
- 开启：25 `-XX:+UseCompactObjectHeaders`（产品 flag，默认关）；26 默认（534）。
  开关前后用同一负载跑 JFR 对比 `GCHeapSummary` live set 与对象计数。
- 代价清单：锁路径多一层间接（轻量锁信息不再全在 mark word）；任何假设
  "对象头 12B/字段偏移"的 Unsafe/反射 hack 直接失效；JOL 需 0.17+，
  堆转储分析工具升级后再做内存审计。
- 事故模式：内存优化上线后锁竞争热点吞吐劣化几个点 —— 高频短临界区的
  对象布局变化是真实代价；A/B 压测锁敏感路径不能省。

## 6. 虚拟线程纪律与成本模型

- 成本模型：栈是按需增长的 heap 对象（stack chunk，起步几百字节，随栈深增长）；
  mount/unmount 各约微秒级；百万线程内存可行，但 **ThreadLocal 大对象按线程
  复制** —— TL 必须瘦（52 节），否则百万线程 × 大 TL = 堆炸。
- 调度器：专用 ForkJoinPool（FIFO 模式），carrier 默认 = CPU 核数；
  CPU 密集任务放虚拟线程没有收益（不更快，还抢 carrier）。
- 三条铁律：**永不池化**（JDK 不为复用设计，栈与 TL 语义都假设短生命周期）；
  用 `Semaphore` 限并发（保护下游连接）；`Thread.ofVirtual().name("job-", i)`
  命名 —— 无名字的虚拟线程在生产里不可诊断（103 节）。
- 代码基线：
```java
try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
    IntStream.range(0, 10_000)
             .forEach(i -> exec.submit(() -> handle(requests.get(i))));
}   // close() 隐式 join：等待所有任务结束（结构化收尾）
```
- 继承开关：`Thread.ofVirtual().inheritInheritableThreadLocals(true)` 才拷贝
  ITL —— 默认关是故意设计（省拷贝、防脏值）。
- 事故模式：把 VT 当"免费平台线程"5 万并发打 DB（连接池 50）—— 全部卡在
  `getConnection`；限界工具是信号量，不是"池直觉"。

## 7. 虚拟线程 pinning 事故与根治

- 21 的病灶：`synchronized` 块内发生阻塞（IO/park）→ 虚拟线程无法 unmount，
  **钉死 carrier**；carrier 数 = CPU 数，几颗钉子就能让全服务吞吐塌方。
- 检测（21-23）：`-Djdk.tracePinnedThreads=full` 打印钉住栈；JFR 事件
  `jdk.VirtualThreadPinned`（默认 20ms 阈值）；`jcmd Thread.dump_to_file -format=json`
  看 carrier 关联。
- 24 根治（JEP 491）：monitor 所有权改为**按虚拟线程记账**，mount/unmount 时
  迁移所有权；synchronized 内阻塞正常让出 carrier。**官方已撤销 21 时代的
  "synchronized 换 ReentrantLock"建议** —— 24+ 新代码回归 synchronized。
- 仍然 pin 的场景：JNI 原生帧在栈上（native 方法内部阻塞）、FFM `critical`
  调用（10 节）—— 治法：限时、或隔离到平台线程池跑。
- 事故复盘样本：老 JDBC 驱动用 synchronized 包 socket write → 21 升级后整池
  carrier 卡死、上游 502；处置 = 升 24（首选）或驱动调用隔离线程池（临时）。
- 迁移纪律：压测环境全链路跑 tracePinnedThreads（驱动/客户端/日志 appender
  都要扫过）再切流量；上线后监控 pinned 事件计数趋零。

## 8. 结构化并发 JEP 505 现状

- 25 仍是**第五轮预览**：`StructuredTaskScope.open(Joiner, config)` +
  `scope.fork(...)` + `scope.join()`；`Joiner.allSuccessfulOrThrow()` /
  `anySuccessfulResult()` 等静态工厂定义聚合语义；子任务自动继承 ScopedValue。
- 生产判断：预览 API 每轮都在改（前四轮已重构三次）—— 核心链路不要
  `--enable-preview`：生成的 class 带 preview minor 版本，JDK 小版本一变
  就 `UnsupportedClassVersionError`，等于把 JDK 升级锁死。
- 稳妥替代：自封"shutdown-on-failure"薄层（ExecutorService + 异常传播 +
  超时），语义面小、可测；26+ 转正后再换芯。
- 语义要点：fork 只能在 join 前的作用域内；scope 用完即弃不可复用；
  取消传播是核心价值 —— 父任务失败/超时，子任务自动取消（裸 Future 组合
  没有"一起死"的语义）。
- 批判面（社区 2025）：第五轮仍在调整生命周期与取消细节 —— 印证
  "别在主干依赖"的纪律；但预研代码要开始写，转正日才接得住。

## 9. Scoped Values 替代 ThreadLocal

- 25 转正（JEP 506）。核心：`ScopedValue.where(KEY, v).run(runnable)` 有界绑定，
  作用域结束自动失效 —— 不需要（也没有）remove；结构化并发 fork 的子任务
  自动继承绑定。
- ThreadLocal 三宗罪对照：忘 remove 泄漏（52 节）/ 池化线程脏值 /
  InheritableThreadLocal 在池化下继承错乱 —— ScopedValue 用"不可变 + 作用域"
  一并解决，且虚拟线程百万级时不再按线程复制。
- 陷阱：绑定只在 run/call 的动态作用域内可见；同一 key 嵌套 `where(...).run(...)`
  是"遮蔽"不是"修改"，退出内层恢复外层值；非父子关系的线程读不到 ——
  那种场景老实传参或用队列。
- 典型场景：traceId / 租户上下文 / 请求级只读配置快照。
- 迁移法：网关或过滤器入口改成绑定点；框架内部读点改 `KEY.get()`；
  与 MDC 的桥接（日志带 traceId）自己封装 —— logback MDC 还是 ThreadLocal
  底座，混用时注意 91 节的上下文断链。

## 10. FFM API 堆外内存纪律

- 基座：`MemorySegment` + `Arena`（`ofConfined` 单线程 / `ofShared` 多线程 /
  `ofAuto` 跟随 GC）；**关闭后访问抛 IllegalStateException，不是 SIGSEGV** ——
  这是它对 JNI 的代差级安全属性。
- 生命周期铁律：显式 Arena + try-with-resources；`ofAuto` 只给生命周期无法
  结构化的胶水代码 —— "图省事"用 auto = 堆外泄漏不可见（heap dump 看不见，
  要靠 NMT/RSS，39 节）。
- downcall/upcall：`Linker.nativeLinker().downcallHandle(symbol, descriptor)`
  得 MethodHandle；upcall 回调 stub 的生命周期挂在创建它的 Arena —— Arena
  先关而 native 侧仍持有 stub 就是悬空，正确姿势是回调 Arena 与库使用期对齐。
- `Linker.Option.critical(true)`：调用期间全 JVM 停 safepoint —— 只允许
  短小、无锁、无 IO 的函数，否则拖爆 TTSP（22 节）；`isTrivial` 免线程状态
  切换但同样禁止阻塞。
- 对比 JNI：免 JNIEnv 开销与局部引用表管理；22 起 G1 region pinning（423）
  连 `GetPrimitiveArrayCritical` 都不再全堆停顿 —— JNI 迁移优先级：安全 > 性能。

## 11. Security Manager 与 Unsafe 退场

- JEP 486（24）：Security Manager 永久禁用 —— `System.setSecurityManager`
  抛 `UnsupportedOperationException`；`AccessController.doPrivileged` 变空操作，
  权限边界语义消失，别再拿它当安全边界写文档。
- Unsafe 时间表：471（23）内存访问方法"弃用待移除" → 498（24）首次使用
  运行时警告（stderr） → 计划 26 移除。迁移映射：字段访问 → VarHandle；
  CAS → VarHandle 原子族；堆外 → FFM（10 节）；`allocateInstance` → 反射或改设计。
- 排查：CI 与预发抓 "sun.misc.Unsafe" 警告清零 —— 老版本 ORM/JSON/缓存库
  常在静态初始化就触发；升级依赖是唯一正解，flag 压警告只是延期。
- 强封装（16/17 JEP 396/403）：反射进 JDK 内部默认拒绝；`--add-opens
  java.base/java.lang=ALL-UNNAMED` 是 Spring/序列化系的常规配置 —— 但要
  **精确到包**，别整模块开（面越大，未来 JDK 升级静默变化越多）。
- 事故模式：JDK 26 一到，未清理的 Unsafe 路径全体引爆 —— 提前两个版本
  跑 498 警告清零就是排雷。

## 12. finalization 之死与 Cleaner 纪律

- 状态：JEP 421（18）弃用待移除，官方态度已定 —— 新代码零 finalizer；
  存量 finalizer 迁移到显式 close 或 Cleaner。
- Cleaner 真身：虚引用 + 低优先级清理线程，延迟不可控 → 它是**最后防线**，
  不是析构器；native 内存/文件句柄的及时回收绝不能指望它。
- 正确分层：资源生命周期显式化（AutoCloseable + twr，89 节）为主；
  Cleaner 只兜"调用方忘了 close"—— 注册动作要轻、幂等、无锁。
- 注册成本：每次 `Cleaner.register` 建对象+引用；热路径反复注册 = 制造垃圾；
  注册应发生在资源构造时，一次性。
- 事故模式：把 Cleaner 当 native 内存回收主力 → 分配速率一高清理线程跟不上
  → RSS 线性上涨；DirectByteBuffer 的 `Bits.reserveMemory` 触发 `System.gc()`
  等待，就是这套机制在挣扎的样子（39 节）。

## 13. 模块系统与类路径地狱

- classpath 的真相：扁平、有序、无隔离 —— 同名类两个 jar，**先者胜**，
  顺序取决于打包与部署机制 → 症状是远离现场的 `NoSuchMethodError`。
- 诊断：`-verbose:class` 打印每个类的来源 jar；jdeps 看依赖图；
  maven-enforcer（banned/upper-bound-deps）或 Gradle constraints 构建期拦截。
- JPMS 实战：库别急着模块化 —— automatic module 名取自文件名，一改名就是
  `requires` 崩；应用侧模块化收益主要是强封装与 jlink 定制运行时。
- shading 纪律：relocation 必须连 `META-INF/services` 一起重写，否则
  ServiceLoader 静默丢实现（102 节）；签名 jar 重打包 = SecurityException。
- 事故模式：两个版本 http 客户端共存，一半请求走旧版（没有你配的超时）——
  "偶发挂起"先查类遮蔽，再查业务逻辑。

## 14. 分层编译与 CodeCache 管理

- 五层编译：0 解释器 / 1 C1 无剖析 / 2 C1 少剖析 / 3 C1 全剖析 / 4 C2。
  触发量级：C1 千次调用上下，C2 万级（自适应阈值），循环靠 OSR 提前进入编译态。
- CodeCache：默认 `ReservedCodeCacheSize=240MB`，分段（non-method / profiled /
  non-profiled）；**满了之后 JIT 整体关闭**（日志 "CodeCache is full. Compiler
  has been disabled"）→ 性能断崖且不自愈，只能重启。
- 高危场景：大量动态代理/脚本引擎/MethodHandle 生成物 —— 每种调用点都占代码；
  监控 JFR `CodeCacheStatistics` 与编译队列长度事件。
- 压测纪律：JMH 与压测要 1-2 分钟以上进 C2 稳态再看数；只看前 30 秒
  相当于在解释器/C1 区间下结论（18 节）。
- 冷启动特例：Serverless/CLI 用 `-XX:TieredStopAtLevel=1`（只 C1：峰值低、
  启动快），再叠 CDS/AOT（25 的 514）砍类加载时间。
- 编译线程：`CICompilerCount` 随 CPU 缩放（有上下限）；小容器里编译线程
  也要抢配额 CPU，冷启动高峰别与流量高峰重叠。
## 15. 逃逸分析与标量替换失效清单

- EA 决定三种优化：标量替换（对象拆成局部变量）、锁消除、以及理论上不存在的
  "栈分配"（HotSpot 只有标量替换，没有真栈分配）。
- **失效清单**（每条都让对象回到堆上）：
  - 对象传给**未被内联**的方法（内联是 EA 的前提，16 节）；
  - 存入数组/字段 —— 哪怕只在冷分支存一次（C2 没有 partial EA，
    分支级逃逸分析的研究显示可再省约一半分配，但从未产品化）；
  - 两个构造分支在 phi 汇合后继续使用；
  - varargs 数组、`Object...` 参数、装箱入集合；
  - identity 敏感操作：`System.identityHashCode`、`==` 比较、`wait/notify`。
- 锁消除是 EA 收益里**最稳定**的部分（无争用 synchronized 整段消失），
  标量替换失败率远高于它 —— 调优别把宝押在标量替换上。
- 观测真值：JFR `ObjectAllocationInNewTLAB` 按调用点聚合（93 节）；
  `-XX:+PrintEliminateAllocations` 需 debug 版 JVM，产品线用不上。
- 纪律：热路径的迭代器/DTO/临时对象**自己显式复用**，用 JMH 验证；
  "EA 会帮我消除"是 2020 年代最大的性能玄学之一。

## 16. 内联成本模型与阈值

- 阈值量级（默认值，版本会微调）：`MaxInlineSize=35`（冷方法字节码上限）、
  `FreqInlineSize=325`（热方法上限）、`InlineSmallCode=2000`（已编译代码尺寸）。
  经验：热方法字节码超过 ~325 就别指望内联。
- 内联是**其它优化的前提**：锁消除、常量传播、EA 都要求跨方法边界看到内容；
  一个大方法不内联，往往连带周围三个优化全部失效。
- 诊断：`-XX:+UnlockDiagnosticVMOptions -XX:+PrintInlining`，输出
  "inline (hot) / too big / megamorphic / not inlinable" —— 用 JITWatch 可视化。
- 事故模式：热点方法里塞了日志字符串拼接（21 节）把字节码撑到 400+ →
  内联失败 → EA 失败 → 分配上升 → GC 频率上升，链式劣化。
- 治法：拆小方法（尤其把"冷"分支抽出去）；别手写循环展开（JIT 已做，19 节）；
  任何内联改动用 JMH 回归（112 节）。

## 17. 去虚化与 megamorphic 调用点

- 调用点剖析记录 receiver 类型：单态（monomorphic）→ 直接调用 + 内联；
  双态（bimorphic）→ C2 有特判仍可优化；三态及以上（megamorphic）→
  老老实实走 vtable/itable，内联链全断。
- 免费午餐：**final class、private/static 方法、sealed interface**（17+）在
  编译期就能证明单态 —— sealed 层次里的 switch 还能编成跳表（78 节）。
- CHA 依赖：C2 会按"当前类层次"做投机去虚化，之后加载新子类 → 去优化
  （deopt）重编 —— 上线后首次大流量"预热抖动"常源于此（18 节）。
- 事故模式：通用工具方法里 `List`/`Map` 接口调用点聚齐 ArrayList、LinkedList、
  ImmutableList、Collections.unmodifiable 包装 —— 工具方法性能离奇差，
  火焰图里全是虚调用；治法：热点路径针对具体类型特化分支。
- 观测：PrintInlining 的 "megamorphic" 行；JFR `CompilerStatistics`。

## 18. OSR、热身与去优化风暴

- OSR（栈上替换）：解释执行的循环中途切换到编译代码；OSR 编译质量低于正常
  编译（入口限制优化）—— 微基准里 OSR 命中的数据全是假象（JMH 用 fork 规避）。
- 去优化触发源：类加载打破 CHA 假设（17 节）；剖析漂移（receiver 类型分布变）；
  uncommon trap（null check/数组越界/除零走隐式异常路径 → deopt 回解释器）。
- **隐式异常机制**：C2 代码里 NPE/AIOOBE 不显式检查，靠信号陷入 JVM →
  热路径一旦真的抛，掉到解释器执行 catch，吞吐突降 10 倍量级。
- 热身纪律：压测 1-2 分钟起步；生产"预热线程"重复调用自己类的方法
  经常失效 —— JIT 剖析按调用点区分上下文，预热线程的调用点 ≠ 真实请求路径。
- 冷启动组合拳：CDS（`-XX:SharedArchiveFile`）→ 25 的 AOT 缓存（JEP 514）
  → `-XX:TieredStopAtLevel=1`（仅限 CLI/Serverless）；容器探针别在
  未热身时放量。

## 19. 分支预测与循环优化边界

- 成本模型：可预测分支近乎免费，误预测 ~15-20 cycles；分支模式比分支数量
  更重要 —— 先排序再过滤的"无意义排序"有时反而快，就是让分支变可预测。
- counted loop 特权：`for (int i = 0; i < arr.length; i++)` 能被 C2 认定为
  计数循环 → 边界检查消除、循环展开、剥离；**long 下标/复杂条件 = 非计数
  循环**，这些优化全部离场。
- 循环剥离/剥离出的 safepoint 轮询：11+ 的 loop strip mining 把长循环的
  safepoint 轮询放进外层 —— 22 节 TTSP 事故的官方解药。
- 二维数组：Java 是行主序展开（`a[i][j]` 连续）—— 内层走 j 尊重局部性；
  内层走 i = 每步一次缓存未命中的教科书事故。
- 手动优化边界：手工展开、预取、缓存局部变量在现代 C2 上多数无效或负收益；
  唯一稳定有效的是**数据布局**（原始类型数组、并行数组结构，24 节）。

## 20. 分配成本模型与 TLAB

- 分配本身近乎免费：TLAB 内 bump pointer ~10ns；**成本在 GC** —— eden
  装满就触发 young GC，频率 ≈ 分配速率 / eden 容量。
- 量级直觉：1GB/s 分配 + 4GB eden ≈ 每 4 秒一次 YG；把分配降到 200MB/s
  比 5 个 GC flag 都管用（37 节）。
- TLAB：线程私有缓冲，默认自适应大小；分配速率极高的线程会频繁 refill ——
  正常；`-XX:TLABSize` 只在 JFR 显示大量 refill 时才动。
- 大对象绕过 TLAB：G1 里 ≥ 半个 region 走 humongous 路径（32 节）——
  这是"分配成本模型"里唯一需要特别管理的分支。
- 隐形分配大户：装箱（26 节）、迭代器、lambda 捕获（72 节）、字符串拼接
  （21 节）、防御性拷贝、Stream 装箱管道（67 节）。
- 检测：JFR `ObjectAllocationSample`（默认 20ms 一采）按站点聚合，比
  heap dump 便宜两个数量级。

## 21. String 深水区与 dedup

- compact strings（9+）：内部是 `byte[] + coder`，纯 Latin-1 时一字符一字节；
  **混入一个非 Latin-1 字符整串升级 UTF-16** —— 大小写转换/规范化/用户输入
  都可能悄悄触发编码升级，内存测量要按内容分布来。
- 拼接内幕（9+）：`+` 编译为 invokedynamic `StringConcatFactory`（73 节），
  运行期生成混合器，普通场景快于手写 StringBuilder；但产物对逃逸分析不可见
  —— 循环拼接仍必须显式 StringBuilder + 预估容量。
- `String.format`/`MessageFormat`：解析格式串，慢一个数量级 —— 日志/展示层
  可用，热路径禁。
- **String deduplication**（G1 `-XX:+UseStringDeduplication`）：老年代字符串
  底层 byte[] 去重共享；适用 = 大量重复内容常驻（消息体、缓存 key、枚举名）；
  ZGC 17+ 同样支持。dedup ≠ intern：前者 GC 自动做、后者手动进符号表。
- intern 纪律：只用于有限且巨大的常量集合（协议名等）；循环内 intern =
  每次哈希 + 原生表操作；22 节的 dedup 能覆盖的别用 intern。
- 陷阱：`split` 单字符走快速路径、传正则元字符就编译正则；`trim` 只切
  ASCII ≤ U+0020，Unicode 空白要 `strip`。

## 22. SafePoint 与 Time-To-Safepoint

- 全局停顿 = **TTSP**（所有线程走到安全点）+ 操作本身；GC 日志只显示后者，
  TTSP 藏在 "safepoint sync" —— 单线程拖全体的事故主产区。
- 谁能拖慢 TTSP：非计数长循环（19 节）、超长 counted 循环的 strip mining
  空隙、JNI 临界区、FFM `critical` 调用（10 节）、大数组 intrinsic 拷贝/fill。
- 症状：GC 停顿"有时"几百 ms 且与堆大小无关；`-Xlog:safepoint` 看
  sync 时间；JFR `SafepointBegin/End` 事件。
- 治法：给可疑长循环加 int 计数轮询点（每 N 次迭代空转一步）、把大数组
  操作分块；JNI 临界区限时化。
- 相关停顿全景（排查时逐一排除）：GC、deopt 批量、jvmti 事件、revoke
  bias（历史，15 已移除偏向锁）、code cache flushing、线程转储本身。
- 纪律：容器 CPU 配额紧张时 TTSP 恶化 —— throttling 让"该跑的线程"跑不到
  轮询点（98 节）。

## 23. JIT 观测与诊断标志

- 工作流标配：`-XX:+PrintCompilation -XX:+UnlockDiagnosticVMOptions
  -XX:+PrintInlining` + JITWatch 离线分析；产品环境别开（日志量巨大）。
- JFR 侧（常开友好）：`CompilerStatistics`（编译/队列）、`CompilationFailure`
  （编译失败是性能事故的早期信号）、`CodeCacheStatistics`、`Deoptimization`。
- `-Xlog:jit+*`（新版统一日志）逐步替代 Print 系；版本升级后 flag 分组
  会漂移（diagnostic↔product），脚本要跟着改（100 节）。
- 看什么：① 热方法是否到 tier 4；② 内联失败原因（16/17 节）；③
  deopt 计数与原因分布；④ CodeCache 余量曲线。
- 反模式：不看 JFR 直接调 flag —— JIT 观测是"为什么慢"的证据链，
  没证据的调参是抽签（31 节同理）。
- 微基准配套：JMH `-prof perfasm` / `-prof jfr` 把代码级热点与 JIT 决策对上。

## 24. 原始类型特化与数据结构设计

- 泛型不能原始特化（Valhalla 之前）：`List<Integer>` 每元素 = 引用 4-8B +
  Integer 对象 16B + GC 扫描指针 + 缓存不命中 —— 比 `int[]` 差一个数量级。
- 首选阶梯：`int[]/long[]`（局部/内部数据）→ IntStream/LongStream（管道）→
  fastutil/Eclipse Collections（持久结构）；`List<Integer>` 只留在外围边界。
- SoA 模式：高频数值记录（tick、坐标、指标）拆成多个平行原始数组 ——
  缓存行利用与未来 SIMD 化（Vector API 508 仍孵化）都吃这个布局。
- 陷阱：map 键为 `Long` 装箱 —— 缓存区间 -128..127 内 `==` "恰好对"，
  出界后 == 翻车（26 节）；`OptionalInt` 与 `Optional<Integer>` 混用导致
  双重装箱。
- 量化习惯：内存与吞吐的推断都要用 JOL（布局）+ JMH（吞吐）落地，
  小对象数组实测常差 8-15 倍。

## 25. on-heap 与 off-heap 决策

- 堆内：GC 管理、安全、但护栏成本（边界检查 + 压缩指针解引用）；百 MB 级
  缓冲放堆内是常态。
- 堆外的真实理由：① 缓冲 >1GB（GC 压力/暂停不可接受）；② 零拷贝网络
  （netty DirectByteBuffer）；③ 跨进程共享内存；④ 与 native 库互操作。
- 技术选型：`ByteBuffer.allocateDirect`（Cleaner 兜底，受
  `-XX:MaxDirectMemorySize` 限制，默认 ≈ Xmx）→ FFM Arena（显式生命周期，
  10 节）→ Netty 池化分配器（jemalloc 思路，有指标）。
- 决策铁律：堆外必须有**生命周期归口**（池框架或显式 Arena/引用计数）；
  没有归口就上堆外 = 把泄漏从 heap dump 视野里挪走（39 节）。
- 事故模式：堆 4G 平稳、RSS 涨到 16G —— DirectByteBuffer 下发速度超过
  Cleaner 回收；`BufferPoolMXBean` 的 direct count/capacity 是第一现场。
- 迁移提示：JNI 时代的堆外代码优先迁 FFM（安全模型代差，10 节）。

## 26. Autoboxing 成本事故

- 缓存事实：`Integer.valueOf` 缓存 **-128..127**（`-XX:AutoBoxCacheMax` 可调
  上界）；`==` 比装箱在缓存区间内"恰好工作"→ 出界翻车 —— 资深者也踩。
- unboxing NPE 三连：`int v = map.get(k)`（null 拆箱）、三目运算符类型统一
  `cond ? 1 : boxedInt`（整条表达式拆箱）、`map.get(k) == someInt` 混合比较。
- 热路径成本：循环里 `Long sum += x`（每次 new Long）、集合遍历累加、
  泛型 Reduce —— JFR 分配采样里 Integer/Long 站点高频出现就是它。
- 修复阶梯：原始特化（24 节）→ 原始 Stream → 数组/原始容器库；
  装箱容器只放低频边界。
- 纪律：所有数值型 map 键在 code review 里默认追问"为什么不是原始特化"；
  equals 比较装箱值永远 `equals`（或先拆箱）。

## 27. 不可变对象与 JIT 优化

- 不可变≠自动快：真正价值是**免锁共享 + 安全发布**（41 节）；JIT 红利需要
  额外条件：字段 final、类型 final/sealed、构造后不被反射改。
- `final` 语义分层：局部 final 对 JIT 无意义（SSA 等价）；**字段 final**
  才参与常量传播；`static final` 原始/String 直接内联进调用点。
- 事故模式：号称 immutable 的对象里藏 `Date`/数组/可变集合字段 →
  "安全共享"变成竞态温床；record 数组字段 = 引用不可变、内容可变（76 节）。
- copy-on-write 成本意识：每次"修改"全拷贝 → 大对象频繁 with = 分配洪峰
  （85 节）；内部可变 + 出口冻结（`List.copyOf`）是折中。
- 证据链：`final` 字段 + 去虚化（17 节）+ EA（15 节）叠加时，不可变 DTO
  的 getter 全部消失 —— JITWatch 里"看不到 getter"就是优化的形态。

## 28. 异常成本模型与 fast-throw 陷阱

- 成本构成：`fillInStackTrace` 走栈 + `StackTraceElement[]` 分配 ≈ 微秒级；
  每秒万级异常 = 吞吐直接掉 + 分配洪峰；抛出还常伴随 deopt（18 节）。
- **fast-throw 陷阱**：C2 对热点重复抛 NPE/AIOOBE/除零会丢掉栈
  （`-XX:+OmitStackTraceInFastThrow` 默认开）→ 生产日志出现"空栈 NPE"，
  现场全丢；临时关 flag 复现一次即可拿到真栈 —— 排查 SOP 里必须有这条。
- 重抛成本：同一异常对象 catch 后再 throw 不重填栈；但每层包装都新增对象；
  cause 链纪律见 90 节。
- 控制流异常优化（框架内部可用）：覆写 `fillInStackTrace() { return this; }`
  省栈；解析器 fast-fail 等场景合法，业务代码禁用（可诊断性归零）。
- 21+ helpful NPE：消息含"谁为 null"（字节码分析），编译代码里默认开 ——
  但它救不了空栈（fast-throw 先把栈丢了）。
- 纪律：预期失败（解析 miss、缓存 miss、校验不过）一律 Optional/结果类型。

## 快速索引 · 续（29-56 节）

| 想找什么 | 去哪一节 |
| --- | --- |
| 正则回溯爆炸 | 29. 正则与字符串处理性能 |
| G1 怎么工作 | 30. G1 内部机制 |
| G1 调参顺序 | 31. G1 调优实战 |
| 大对象 / to-space exhausted | 32. Humongous 与 to-space exhausted |
| ZGC 原理（染色指针） | 33. 世代化 ZGC 内部机制 |
| ZGC allocation stall | 34. ZGC 调优与 allocation stall |
| G1/ZGC/Parallel 怎么选 | 35. 收集器选型决策树 |
| GC 日志读法 | 36. GC 日志与 JFR GC 事件解读 |
| 降 GC 压力的优先级 | 37. 分配速率治理 |
| THP/NUMA/K8s 内存 | 38. 大页、NUMA 与容器内存 |
| RSS 大于 Xmx | 39. 堆外内存核算与泄漏 |
| happens-before 边清单 | 40. JMM happens-before 精确化 |
| this 逃逸 / DCL | 41. final 字段与安全发布 |
| volatile 到底贵在哪 | 42. volatile 成本模型与语义边界 |
| 锁升级流程 | 43. synchronized 锁升级与膨胀 |
| AQS 原理 | 44. AQS 内部机制 |
| 锁降级怎么写 | 45. ReentrantLock 与锁降级 |
| 乐观读 | 46. StampedLock 乐观读 |
| CHM 底层结构 | 47. ConcurrentHashMap 内部结构 |
| computeIfAbsent 死锁 | 48. CHM 语义陷阱与 compute 原子性 |
| 高并发计数器选型 | 49. LongAdder 与 Striped64 内部 |
| CompletableFuture 超时坑 | 50. CompletableFuture 事故清单 |
| parallelStream 拖垮全局 | 51. ForkJoinPool 与 commonPool 陷阱 |
| ThreadLocal 内存泄漏 | 52. ThreadLocal 泄漏与池化线程 |
| 多核写同一字段慢 | 53. 伪共享与 @Contended |
| 死锁排查 | 54. 死锁、锁顺序与活锁 |
| 读多写少用什么 | 55. 并发容器与读写锁选型 |
| HashMap 树化条件 | 56. HashMap 内部与树化 |

## 29. 正则与字符串处理性能

- `Pattern` 必须预编译进 `static final`；`String.matches/replaceAll` 每次调用
  都全量编译 —— 热路径差 10-100 倍，是最常见的"隐形正则税"。
- **灾难回溯**：`(a+)+` 型嵌套量词在"几乎匹配又不匹配"的输入上指数爆炸；
  防御：占有量词 `a*+`、收紧分组、限制输入长度；regex DoS 是真实攻击面。
- 手写替代优先级：`indexOf` 循环（分隔/提取）> 简单状态机 > 正则；
  一次 `split` 处理 GB 级文本 = 事故预算。
- `String.split`：单字符非正则元字符走快速路径；`limit` 参数决定尾空串
  （默认丢弃，传 -1 保留）—— 解析协议字段时这个默认值咬人。
- Unicode 陷阱：`\s` 含部分 Unicode 空白但 `trim` 只认 ≤ U+0020；
  `lines()`/`strip()` 与 `split("\n")` 的空行/结尾行为不同 —— 选错就是
  解析 off-by-one。
- 纪律：正则进入代码库必须带输入上限 + 单元测试（含病态输入），
  复杂模式上 ratchet 基准（112 节）。

## 30. G1 内部机制

- 布局：堆切 region（1-32MB，2 的幂，默认 ≈ 堆/2048）；E/S/O + humongous；
  跨 region 引用由 RSet（points-into 结构 + 卡表）追踪，写屏障维护，
  常驻堆开销约 1-10%。
- 并发标记：SATB（开始时快照）—— 标记期间新建对象视为存活；
  由 IHOP（默认 45% 老年代占用，自适应）触发。
- young GC：全 STW 并行 evacuate；mixed GC：标记后挑存活率低（<85%，
  `G1MixedGCLiveThresholdPercent`）的老 region 与 young 一起搬。
- 停顿模型：`MaxGCPauseMillis`（默认 200ms）驱动 young 区大小的预测器 ——
  这不是"硬上限"而是"目标"，越调小 young 越小、GC 越频繁。
- full GC： evacuate 失败后的兜底（10+ 已并行化），分钟级 —— 出现即事故，
  32 节是它的两个主因。
- 关键监控量：region 尺寸、mixed GC 节奏（`G1MixedGCCountTarget=8`、
  `G1HeapWastePercent=5%`）、RSet 维护线程占用。

## 31. G1 调优实战

- 顺序铁律：先测分配速率与 live set（37 节）→ 固定 `-Xms=-Xmx`（防动态
  扩缩容抖动）→ 最后才动细粒度 flag；90% 的"调优"止步于前两步。
- `MaxGCPauseMillis` 反直觉：从 200ms 压到 50ms，young 变小 → YG 次数翻几倍
  → **总停顿时间上升**；先看停顿直方图（P99）再决定有没有必要压。
- `InitiatingHeapOccupancyPercent`：只有关闭自适应（或观察到标记总是过晚）
  才手调；升降 5% 一档，A/B 验证 mixed GC 是否及时。
- 高频事故：**一味压 pause → mixed GC 排空不及时 → 老年代缓涨 → Full GC**；
  正确信号是老年代占用曲线斜率，不是单次停顿。
- Region 尺寸：大对象多（32MB 缓冲）时升到 `G1HeapRegionSize=16/32m`，
  削 humongous（32 节）；小堆别乱升（region 数太少并发标记粗化）。
- 每次调参 = PR（100 节）+ 压测回归 + 停顿直方图前后对比，禁止"感觉更好"。

## 32. Humongous 与 to-space exhausted

- humongous 定义：≥ 半个 region 的对象 → 直接在老年代找**连续 region** 分配；
  阵发大对象 = 连续性碎片 + 并发标记压力。
- "to-space exhausted"：mixed/young 搬迁时目标区不足（预留 `G1ReservePercent`
  默认 10%）→ 搬迁半途而废 → 更快进入下一轮标记/full GC 的死亡螺旋。
- 日志指纹：`Pause Young (G1 Humongous Allocation)`、`to-space exhausted`、
  老年代占用高锯齿；JFR `GarbageCollection` 的 cause 字段。
- 事故样本：每单生成 64MB JSON byte[] → humongous 风暴 → 每小时多次
  Full GC；治法：流式序列化（边生成边发）+ region 升 16MB + 上限保护。
- 预防：分配审计（JFR allocation sample）里把 >8MB 的对象当"嫌疑对象"
  管控；输入大小无上限的接口必须有硬限制。
- 注意：ZGC 对大对象无此痛点（无 region 连续性约束）—— 大缓冲密集型
  服务选收集器时这是个决定性差异（35 节）。

## 33. 世代化 ZGC 内部机制

- 染色指针：64bit 指针里嵌 mark/remapped 等元数据位；**load barrier** 在
  引用加载时自愈与转发表查找 —— 标记、搬迁全程并发，STW 只剩极短的
  相位切换（亚毫秒，与堆大小无关）。
- 世代化演进：JEP 439（21，需 `-XX:+ZGenerational`）→ 23 起默认 →
  24 移除非世代模式；young/old 独立回收 + remembered set（屏障维护的
  跨代引用记录）→ allocation stall 概率大幅下降。
- 代价清单：引用加载屏障的常数开销（几个百分点 CPU）；元数据与 multi-mapping
  带来的内存开销；GC 线程默认更吃 CPU。
- 定位：百 GB 堆 + P99.9 <10ms 的硬延迟需求；吞吐型批处理反而可能
  输给 Parallel（35 节）。
- 与 JNI 交互：load barrier 只覆盖 Java 引用；native 侧持有堆内对象要靠
  handle/引用计数（22 的 G1 region pinning 不适用于 ZGC 语境）。
- 观测：JFR `ZAllocationStall`、`ZPageAllocation`；`-Xlog:gc` 的
  allocation stall 行（34 节）。

## 34. ZGC 调优与 allocation stall

- stall 机制：分配速率 > 回收速率时，分配线程被节流等内存（毫秒到秒级的
  "软停顿"）→ 尾延迟爆炸；Netflix 实测：**罕见但真实**，总与分配速率尖峰
  同现 —— 高突发流量服务要预设这个风险。
- 堆策略（核心中的核心）：`-XX:SoftMaxHeapSize` 设为活跃集的 ~1.5 倍
  （软目标，GC 尽力压在此内），`-Xmx` 再留余量（stall 缓冲）—— ZGC 是
  **用堆余量买延迟**的收集器，堆紧着用就别选它。
- `-XX:ZAllocationSpikeTolerance`（默认 2）：突发越猛调越高，让 GC 提前启动；
  GC 线程数在高核机器按需上调。
- 世代化的红利：young GC 高频小步跑 → stall 基本消失 —— 还在按非世代 ZGC
  的老资料调优是常见错误。
- 观测闭环：JFR 看 stall 事件与分配速率曲线的相关性；有 stall = 先降分配
  （37 节），再谈扩堆/调参。
- 事故模式：换 ZGC 但堆只给 1.2x live set → 大促流量一来 stall 连发，
  P99.9 飙到秒级 —— "低延迟收集器"救不了没余量的堆。

## 35. 收集器选型决策树

- 吞吐优先批处理（离线计算、ETL）→ Parallel：停顿长但总开销最低。
- 常规在线服务（堆 ≤ 几十 GB，P99 百 ms 级可容忍）→ G1 默认即可。
- 大堆（>16-32GB）或硬尾延迟（P99.9 <10ms）→ 世代化 ZGC；
  相近需求也可看 Shenandoah（注意 Oracle JDK 不含，Temurin/Red Hat 有；
  世代化 Shenandoah 仍在实验推进）。
- 判据细节：humongous 密集型负载 ZGC 更稳（32 节）；RSS 受限的小容器
  ZGC 的元数据开销要算进预算（38 节）。
- 迁移验证：同流量回放 A/B，对比停顿直方图 + GCCause 分布 + CPU 总量
  （"停顿换 CPU"是常态）+ 分配速率；只比 P50 会自我欺骗。
- 反模式：为"追新"换收集器 —— 收集器选型是被延迟 SLO 与堆预算倒逼的，
  不是版本新不新。

## 36. GC 日志与 JFR GC 事件解读

- 统一日志（9+）：`-Xlog:gc*:file=gc.log:time,uptime:filecount=5,filesize=20m`
  —— 滚动保留是生产标配；先看 `Pause` 行的 cause 再决定深挖方向。
- JFR 事件：`GarbageCollection`（cause/duration）、`GCPhasePause`（停顿分解）、
  `TenuringDistribution`/对象统计（配 heap 信息事件）—— 直方图永远比平均诚实。
- cause 分类直觉：`Allocation Failure`/`G1 Humongous Allocation`（32 节）、
  `System.gc()`（有人显式调 —— 查 RMI DGC 定时器：`sun.rmi.dgc.client.gcInterval`
  默认每小时触发一次 System.gc()，老服务常见"整点卡顿"元凶）。
- 显式 GC 治理：`-XX:+DisableExplicitGC` 一刀切 —— 但会连带伤害依赖
  `System.gc()` 回收 DirectByteBuffer 的老代码（39 节），先审计再切。
- 晋升速率：老年代占用斜率 = mixed GC 是否及时的先行指标（31 节）。
- 纪律：GC 日志与 JFR 常开进监控基线，事故时才有"前后对比"。

## 37. 分配速率治理

- 度量三件套：JFR `ObjectAllocationSample` 按站点聚合（谁是分配大户）、
  GC 日志推 eden 消耗速率（YG 间隔倒数 × young 容量）、`jstat -gcutil`
  的 Eden 变化率。
- 治理阶梯（按 ROI 排序）：① 消死分配 —— 装箱（26）、循环拼接（21）、
  迭代器/lambda 捕获（72）、无谓防御拷贝；② 缓冲复用（池化只给大对象）；
  ③ 集合容量预估（56/58 节）；④ 最后才是对象池与堆外。
- 大响应治理：全量 JSON 序列化 = 分配洪峰 → 流式（Jackson token 模式，
  110 节）+ 池化输出缓冲；一次 32MB 响应体的短命对象足以触发 young GC。
- 数字直觉：3GB/s → 300MB/s 后，young GC 间隔 ×10，P99 从 120ms → 12ms
  （G1 实测口径）—— 降分配率通常比任何 GC 调参收益都大。
- 事故模式：监控告警盯"GC 次数"却没人盯"分配速率" —— 病根在应用，
  告警却在 GC 层空转。

## 38. 大页、NUMA 与容器内存

- 大页：`-XX:+UseLargePages`（显式 hugepages）降 TLB miss；**THP（透明大页）
  与 JVM 是事故搭档** —— khugepaged 合并/拆分引发的内核停顿在多个发行版
  默认 `[always]` 下真实发生；要么显式预留大页，要么 THP 设 `madvise`。
- NUMA：G1/Parallel 认 `-XX:+UseNUMA`；ZGC 与 UseNUMA 组合的历史支持有限
  —— 多路机型先验证你的 JDK 版本行为再上。
- 容器内存账本：RSS ≈ 堆 + Metaspace（96 节）+ CodeCache（14 节）+
  线程栈（-Xss × 线程数）+ DirectBuffer（39 节）+ GC 元数据（G1 RSet、
  ZGC 染色映射）+ glibc 碎片 —— **K8s limit 只给 Xmx+一点 = 必然 OOMKilled**。
- 起手三件套：`-XX:InitialRAMPercentage=70 -XX:MaxRAMPercentage=70`（替换
  硬编码 Xmx）+ `-XX:+ExitOnOutOfMemoryError`（快速失败）+ NMT 常备（39 节）。
- cgroup：cgroup v2 自 15 起支持（8u372+ 补齐）；CPU 配额低时 availableProcessors
  缩水 → GC 线程、FJ 池、CHM 并行度全部跟着缩 —— 单核容器事故见 51 节。

## 39. 堆外内存核算与泄漏

- 核查工具链：`BufferPoolMXBean`（direct count/capacity，JMX 一行代码）→
  NMT：`-XX:NativeMemoryTracking=summary` + `jcmd VM.native_memory
  baseline/diff`（差分看涨项）→ pmap/gdb 只在确有 native 泄漏时。
- DirectByteBuffer 机制：分配受 `-XX:MaxDirectMemorySize`（默认 ≈ Xmx）约束；
  池耗尽时 `Bits.reserveMemory` 触发 `System.gc()` 并**等待** Cleaner 回收
  —— 老代码里偶发长停顿的常见出处（36 节）。
- Netty 侧：`PooledByteBufAllocator` 自管池（不看 MaxDirectMemorySize，
  看其 own metrics）；泄漏检测级别 `io.netty.leakDetection.level=SIMPLE`
  默认开 —— 日志里 `LEAK: ByteBuf.release()` 被漏调就是铁证。
- FFM 侧：Arena 显式生命周期（10 节）—— 审计方法是把"谁关 Arena"写成
  代码评审 checklist，而不是靠 auto。
- 事故模式：heap dump 干净、RSS 线性涨 —— 直接跳堆外三件套；90% 是
  DirectByteBuffer 泄漏或 Metaspace（96 节）。
- 纪律：堆外内存进容量规划表（每 GB 堆配多少堆外要有数），别靠"感觉够用"。

## 40. JMM happens-before 精确化

- 核心边清单（背下来）：① 监视器 unlock → 后续 lock；② volatile 写 →
  后续读；③ start() → 子线程内一切；④ 线程内全部 → join() 返回；⑤
  传递性闭包。**数据竞争 = 冲突访问间无 hb 边**，结果只允许 hb 保证的部分。
- 精确化误区："hb 保证顺序"≠"处理器不乱序"—— hb 是编译器+硬件共同兑现的
  抽象，屏障是实现细节（42 节）；"volatile 写立刻全局可见"是错的，只有
  后续的读有保证。
- roach motel：实现允许"比规范更强的排序"，所以某机器上"没加 volatile 也
  对"不能反推规范允许 —— 跨架构（x86→ARM）迁移时批量爆雷的经典来源。
- 64 位撕裂：非 volatile 的 long/double 允许两次 32 位读（JLS 17.7）——
  x86 上看不到，ARM/低配硬件上真实存在。
- 数据竞争的正确处理：要么消除共享（不可变/消息传递），要么全套同步 +
  文档；"加个 volatile 试试"不是工程，是抽签。
- 术语自检：多线程正确性 = 互斥（锁/CAS）+ 可见性（hb）+ 有序性，三者
  各自独立成立才算安全。
## 41. final 字段与安全发布

- final 语义（JLS 17.5）：构造器结束的 "freeze" 动作后，**哪怕对象引用经过
  数据竞争发布**，读线程只要看到非 null 引用，final 字段必然看到构造器写入
  的值 —— 这是唯一"免同步也安全"的字段语义。
- 破坏 freeze 的唯一途径是 `this` 逃逸：构造器里启动线程、注册监听器、
  调用可覆写虚方法 —— 三者都会把半构造对象递给别的线程，final 保护整体作废。
- 细节陷阱：final 只保证**它指向的引用**可见，不保证引用指向的可变内容
  （`final List<X> list` 的元素仍需另行同步）；同一 final 字段读两次，JMM
  允许"第一次读到旧值"的窗口存在 —— 值对象应缓存读数而不是反复读字段。
- DCL（双检锁）：instance 字段必须 volatile（42 节）；更稳的替代是 holder
  idiom（静态内部类）或 enum 单例 —— 前者靠类加载锁，后者免费防反射。
- 事故模式：构造器里 `listenerBus.register(this)` + 事件线程立刻回调读
  字段 → 偶发读到 0/null，加 final 都救不了 —— 先改掉 this 逃逸再说。
- 纪律：值对象全 final（record 默认如此）；可变对象配锁或并发容器；
  "final 就线程安全"只在无 this 逃逸 + 字段只读两个条件同时成立时为真。

## 42. volatile 成本模型与语义边界

- 语义边界（每次 code review 都会遇到的三个误区）：① 不原子 —— `v++`
  仍是三步，竞态照旧，要 `AtomicLong`/`LongAdder`；② 只保证该字段的
  可见性/有序，**不传递到普通字段的后续写**（要看 hb 边组合，40 节）；
  ③ volatile 数组只保证"数组引用"可见，元素写入不 magically 可见。
- 成本模型：x86 写 volatile = `lock addl`（store-load 屏障，~20-50 cycles）；
  读 volatile 在 x86 是普通 mov（免费），但会限制编译器重排；ARM 上读写
  都有真实屏障成本 —— 跨架构基准别拿 x86 数据外推。
- 精细替代：`VarHandle.getAcquire/setRelease`（x86 与 volatile 同价，ARM 便宜）、
  `getOpaque`（仅保 coherence，计数器/标志位够用）—— 性能敏感的并发结构
  用 VarHandle 而不是裸 volatile。
- 真实适用面：状态标志（关闭开关）、一次性发布引用、DCL；
  **多写计数器误用 volatile 是常见反模式**（无原子性，白付屏障成本）。
- 事故模式：读侧漏写 volatile —— 单线程测试全绿，压测偶发读旧值；
  排查顺序：字段声明 → 写点 → 是否有 hb 链（40 节）。

## 43. synchronized 锁升级与膨胀

- 偏向锁已死（15，JEP 374 移除）：现代路径 = 无锁（CAS 到栈上 lock record，
  轻量级）→ 自旋失败/等待 → 重量级 ObjectMonitor（等待队列、内核 futex 之路）。
- 膨胀触发：竞争自旋超限、`Object.wait`；**`System.identityHashCode`
  一旦计算，轻量锁无处放 hash → 直接膨胀** —— 热点锁对象上用 identity
  hash 是隐性锁劣化事故。
- JIT 加成：无争用 synchronized 块常被锁消除（15 节）；相邻锁块可能被
  **锁粗化**合并 —— 临界区比你源码写的大是 JIT 决策，别在粗化边界内
  塞慢操作（日志/IO 是重灾区）。
- 与虚拟线程：24 起 synchronized 内阻塞不再 pin（JEP 491，7 节）——
  synchronized 回归默认选择；ReentrantLock 只留给"需要中断/超时/公平/
  多条件"的明确场景（45 节）。
- 事故模式：锁内调用外部回调（alien method）→ 顺序倒挂成死锁（54 节）
  或临界区无限膨胀 —— open call 原则：锁内只摸自己的数据。

## 44. AQS 内部机制

- 骨架：volatile `state`（语义由子类定义：ReentrantLock 重入计数/
  Semaphore 许可/CountDownLatch 倒数）+ CLH 变体双链队列（节点 waitStatus
  的 SIGNAL 驱动前驱唤醒）。
- park/unpark 语义：`LockSupport.unpark` 是**先充值后消费的许可** ——
  先 unpark 再 park 不会丢信号（这是它相对 wait/notify 的根本优势：
  无 lost wakeup）；park 可能"虚假返回"，所以等待循环必须重查条件。
- 取消：cancel 节点被绕过而不是物理删除 —— 调试线程转储时看到
  CANCELLED 节点积压 = 某处 unlock/signal 路径有 bug。
- 公平模式：`hasQueuedPredecessors()` 检查队首；非公平 barging（新来者
  直接 CAS 抢）是吞吐差距的来源 —— 高吞吐选非公平，但会拉长尾延迟
  （饥饿是真实的，54 节）。
- 自研队列锁纪律：能继承 AQS 就别手写无锁队列；手写的 park 循环若
  少写一次条件检查，就是一次偶发挂死（113 节）。

## 45. ReentrantLock 与锁降级

- 相对 synchronized 的真实增量：可中断获取（`lockInterruptibly`）、
  超时获取（`tryLock(time)`）、公平模式、多 Condition（精准唤醒）、
  以及**锁降级**；现代性能差距已很小（17+ 的 synchronized 路径相当快）。
- 锁降级 pattern（写→读平滑过渡）：
```java
wl.lock();
try { data = recompute(); 
     rl.lock();            // 先拿读锁再放写锁
} finally { wl.unlock(); }
try { serveReaders(data);
} finally { rl.unlock(); }
```
  价值：写锁释放到读锁持有之间**不让其他写者插队**，避免缓存/快照抖动；
  顺序反了（先放写锁再拿读锁）= 竞态窗口，经典笔试坑也是生产坑。
- tryLock 纪律：拿不到就退避/降级，超时时间是**传播给上游的重试预算**
  —— 与上游超时联动设计，别拍脑袋。
- Condition：`await()` 自动释放锁并在 signal 后重新排队获取 —— 在
  while 循环里重查条件（虚假唤醒不是传说）；忘 signal = 线程永久滞留。
- unlock 必须放 finally 且与锁层级配对 —— 锁泄漏的表现是后续所有
  获取者饿死，线程转储里一个 owner 卡住一片。

## 46. StampedLock 乐观读

- 模型：读不加锁，`tryOptimisticRead()` 取版本戳 → 读字段 → `validate(stamp)`
  校验期间无写 → 直通；校验失败升级悲观 `readLock()` 重读。
- 正确模板（少一行就是错）：
```java
long stamp = sl.tryOptimisticRead();
double x = this.x, y = this.y;      // 先拷贝
if (!sl.validate(stamp)) {          // 校验在拷贝之后
    stamp = sl.readLock();
    try { x = this.x; y = this.y; }
    finally { sl.unlockRead(stamp); }
}
```
- 陷阱清单：**不可重入** —— 同线程持写锁再去 readLock/writeLock 直接
  死锁；乐观读块内不能调用可能阻塞的方法；validate 之前读到的字段是
  "可能过期"的，只能作为提示值用。
- 适用面：读多写少 + 读路径短（坐标/配置快照/路由表点查）；长读会反复
  作废重试，退回 ReentrantReadWriteLock。
- 收益边界：x86 上乐观读 ≈ 普通读 + 一次版本检查；ARM 更赚 —— 与
  真实读写比过再上，别凭直觉（112 节）。

## 47. ConcurrentHashMap 内部结构

- put 路径：空桶 CAS 建头节点；非空桶 **synchronized(头节点)** —— 锁粒度
  = 单桶；map 永不为空（懒建表用 CAS sizeCtl）。
- 树化条件：桶链长 ≥8 **且表长 ≥64**（否则先扩容）—— 小表上你观察不到
  红黑树，别误判；退化阈值 6（滞后带防抖）。
- size 真相：`baseCount + CounterCell[]` 分散计数（Striped64 思路）——
  **size() 是弱一致估计，不是某时刻快照**；监控图表锯齿、告警抖动常源于
  此；语义上要精确计数的场景自己做 AtomicLong。
- 扩容：多线程协同 transfer（每线程领 stride=16 个桶），无全局 STW；
  get 全程无锁（读 volatile 链/树）。
- 迭代/视图：弱一致（不抛 CME、可能看到也可能看不到并发更新）；
  `containsValue` 是 O(n) 全表扫描 —— 大 map 告警里高频调它是事故。
- 关键参数：初始容量 16/负载因子 0.75；预估大小要 `Map.newHashMap(n)`
  或 `(int)(n/0.75f)+1` 预算，避免上线即扩容。

## 48. CHM 语义陷阱与 compute 原子性

- `computeIfAbsent` 是"每键单次初始化"的正解（替代 check-then-put 双查）；
  **但 mapping function 持有该桶锁**：① 不得再更新同一 map（递归同 key
  = 自锁死锁，规范直接禁止）；② 不得做 IO/远程调用（锁桶 + 阻塞 = 吞吐黑洞）。
- 事故模式：computeIfAbsent 里 miss 时查 DB（慢）→ 同桶其它 key 的
  读写全部排队 → 上游超时雪崩；治法：map 只存占位，IO 移出锁外 + 二次检查。
- `merge/compute` 同理原子但同锁；`putIfAbsent` 返回旧值的语义拿来做
  幂等注册要小心 null（CHM 不允许 null key/value —— Optional 包装或
  哨兵对象）。
- null 禁令的连带坑：从 HashMap 迁移到 CHM 的代码里 null value 直接
  NPE，且常在压测才暴露 —— DTO 字段链里藏 null 是重灾区。
- 批量接口 `forEach/search/reduce`：并行切桶、不冻结快照 —— 适合
  聚合监控，不适合需要一致性的业务读。

## 49. LongAdder 与 Striped64 内部

- AtomicLong 的病灶：所有线程在**同一个缓存行上 CAS 自旋** —— 高并发
  写时缓存行乒乓，吞吐塌方（8 核以上肉眼可见）。
- LongAdder 拆解：无争用时只 CAS `base`；有争用时按探针哈希散到
  `Cell[]`（@Contended 填充防伪共享，53 节）—— 写路径空间换时间。
- **sum() 是瞬时弱一致值**：累加 base+cells 时不冻结写入 —— 用途限
  计数/监控（histogram、QPS），禁用于需要全序的场景（唯一 ID 生成、
  限流配额精确扣减 → 用 AtomicLong 或 CAS 循环）。
- 内存代价：每个 LongAdder 潜在一组 cell；**百万实例级的小对象计数器**
  别用 LongAdder（footprint 反噬），用原始类型数组统计。
- DoubleAdder/LongAccumulator 变体；`longAccumulate` 的扩容在
  饱和后停止（cells 上限 ≈ CPU 数）。
- 选型口诀：写多读少 → LongAdder；读多写少 → volatile/普通 + 定期
  精确化；读写都多 → 分片统计再聚合。

## 50. CompletableFuture 事故清单

- 默认执行器陷阱：`*Async` 不传 executor = commonPool；**parallelism<2
  （单核容器、-Djava.util.concurrent.ForkJoinPool.common.parallelism=0）
  时退化为每任务新线程** —— 容器里等于线程炸弹；显式传 executor 是纪律。
- **orTimeout/completeOnTimeout 不会取消底层任务**：只把 future 标成
  异常完成；任务继续跑、连接继续占 —— 资源池耗尽的元凶。任务内部要
  自查取消位，或用可中断包装（真正 `cancel(true)` 打断）。
- 同步回调线程风险：`thenApply/thenAccept` 在"完成方"线程执行 —— 完成
  方可能是 IO 线程、netty event loop、**或共享的超时调度线程**；重回调
  必须用 `thenApplyAsync(fn, myPool)`。
- 语义差（选错就吞错或重复处理）：`exceptionally` 吞异常返回替代值；
  `handle` 双参转换；`whenComplete` 透传原结果（不吞）—— 日志侧用
  whenComplete，降级侧用 exceptionally。
- join vs get：join 抛 unchecked `CompletionException`（cause 是原始异常）；
  get 抛 checked `ExecutionException` —— 混用两套 catch 会漏一半。
- allOf 返回 `CompletableFuture<Void>`：异常在 join 时才聚合抛出 ——
  allOf 后必须 thenApply 聚合结果，否则等于吞结果。

## 51. ForkJoinPool 与 commonPool 陷阱

- commonPool 规模：`availableProcessors - 1`（容器配额感知）—— **1 核容器
  parallelism=0**：并行流在调用线程同步执行、`*Async` 无参版每任务新线程
  （50 节）—— 低配容器上的"魔法性能悬崖"几乎都指向这里。
- 共享悲剧：所有并行流与无参 `*Async` 共用 commonPool —— 一个慢操作
  （IO、锁等待、慢网关）就能拖垮全 JVM 并行度；parallelStream 里做 IO
  是明令禁止的（68 节）。
- 阻塞的正确申报：FJ worker 内必须阻塞时用 `ForkJoinPool.managedBlock`
  （池会临时补偿线程），否则饥饿；更正确的答案是别在 FJ 池里阻塞。
- 自建池参数：`asyncMode=true`（FIFO）适合消息/异步任务，false（LIFO）
  适合分治；并行度 = CPU 密集任务数，IO 任务根本不该用 FJ。
- 显式并行流：`pool.submit(() -> list.parallelStream()...)` 把并行度
  纳入自己的池 —— 需要隔离时这是唯一正解；系统属性
  `java.util.concurrent.ForkJoinPool.common.parallelism` 是全局的，改它
  等于改别人的池。

## 52. ThreadLocal 泄漏与池化线程

- 结构：`ThreadLocalMap` 的 Entry = **弱引用 key + 强引用 value**；线程不死、
  后续操作不路过该桶，value 就永远不释放 —— 池化线程（Tomcat worker、
  netty event loop、FJ worker）上大 value = 经典堆泄漏。
- 探测式清理的盲区：只在与 key 同桶的遍历里顺手清 stale entry ——
  换个 key 继续写并不保证清旧桶；**finally remove() 是唯一可靠纪律**。
- InheritableThreadLocal 在池化下错乱：worker 是启动期创建的（复制一次
  建池时的父上下文），此后"继承"的是随机旧值 —— 上下文传递要用
  TransmittableThreadLocal/装饰 Runnable（拷贝-恢复模式）。
- 虚拟线程放大器：百万 VT × ThreadLocal 大对象 = 堆炸；迁移方向是
  ScopedValue（9 节）；VT 默认不继承 ITL 是故意的（6 节）。
- 诊断：heap dump 里按 Thread 对象看 retained（ThreadLocalMap 是入口，
  95 节）；MAT 的 "Thread Local" 视图直接列可疑值。
- 事故模式：请求入口 set、异常路径漏 remove → 内存锯齿上升 + 串号
  （用户 A 偶尔看到用户 B 的上下文）—— 两类事故同源。

## 53. 伪共享与 @Contended

- 机制：缓存行 64B（Apple Silicon 128B）；两个线程各写同行的相邻字段 →
  MESI 协议来回作废对方行，吞吐掉 5-10 倍 —— 单线程逻辑完全正确的代码
  在多核下"莫名变慢"。
- 高发区：自定义无锁结构的 head/tail 相邻、监控计数器数组、
  `AtomicLong` 数组统计；检测：perf c2c（Linux）或"多核写热点字段"的
  结构性怀疑。
- 解法阶梯：字段布局隔离（padding 8 个 long；Apple 上 padding 128B）
  → JDK 内部 `@jdk.internal.vm.annotation.Contended`（需
  `-XX:-RestrictContended`，框架内部可用）→ 用现成结构（LongAdder、
  JCTools、Agrona 已经垫好了）。
- 已内置的位置：`LongAdder` 的 Cell（49 节）、CHM 的 CounterCell ——
  所以"自己写个 AtomicLong 数组"通常劣于 LongAdder 不是玄学。
- 事故模式：自研环形缓冲的 producer 指针与 consumer 指针同行 → 压测
  吞吐腰斩；把两个指针分到两行，吞吐翻倍 —— 十分钟的布局改动值回一
  周的调参。

## 54. 死锁、锁顺序与活锁

- 预防三板斧：① 全局锁序（对象图建模，按 id/identityHashCode 破环，
  `System.identityHashCode` 一致即天然序）；② 最多持一锁调用外部代码
  （open call 原则）；③ 必须嵌套时用 `tryLock(timeout)` + 退避。
- 检测：`jcmd Thread.print` 只报**监视器环**（"Found one Java-level
  deadlock"）；ReentrantLock/CountDownLatch/线程池互等组成的环要人工
  看 ownable synchronizers 列表（99 节）+ JFR `JavaMonitorEnter` 的
  等待时间分布。
- 活锁：双方检测到冲突都退避、同节奏重试 → 永远不前进 —— 退避必须带
  随机 jitter；"礼让式重试"在重负载下退化成 livelock 的案例极多。
- 饥饿：非公平锁的 barging 让慢请求永远排队（44 节）；公平锁治饥饿但
  吞吐税重 —— 需要公平的场合通常是"必须可预测"的调度语义。
- 事故模式：DAO 锁 → service 锁的顺序在重构中反转（跨层锁序漂移），
  只有 code review 清单 + 压测死锁用例（113 节）能兜住。
- 跨池死锁：池 A 的任务等池 B 的结果，池 B 满员全是等池 A 的任务 ——
  容量规划要按"最坏嵌套深度"算，或用无条件队列（111 节）。

## 55. 并发容器与读写锁选型

- CopyOnWriteArrayList：写 = 全量拷贝（O(n) 分配 + GC 压力）—— 只适合
  "几乎只读的小集合"（监听器表、路由快照）；把它当通用并发 List =
  写一多分配率起飞（37 节）。
- ReentrantReadWriteLock：非公平模式读可插队 → **写饥饿**真实存在；
  写优先要靠公平模式（吞吐税）。适用：读操作本身贵（秒级）或键空间
  无法分片；否则 CHM/分片优先。
- ConcurrentSkipListMap：无锁跳表 + 弱一致视图，有序需求的唯一并发原语；
  `size()` 是 O(n) 遍历（监控里高频调它 = 事故）；范围视图（subMap）
  是弱一致的，别当快照用。
- 队列选型：`ArrayBlockingQueue`（单锁两条件，吞吐一般但稳定）vs
  `LinkedBlockingQueue`（读写两把锁分离，吞吐更高、每节点分配）vs
  `SynchronousQueue`（直传，配 CachedThreadPool/虚拟线程场景）；
  超高吞吐用 JCTools/Disruptor。
- 决策顺序：能不能不可变 + 换引用（最便宜）→ CHM/分片 → 读写锁 →
  自研无锁（最后手段，113 节配 JCStress）。

## 56. HashMap 内部与树化

- 关键参数：容量 2 的幂（tableSizeFor 取整）、负载因子 0.75、
  扩容阈值 = 容量×0.75；hash 扰动 `(h = key.hashCode()) ^ (h >>> 16)`
  把高位混进低位 —— 高位差异大的 key 集合反而分布差（已知反直觉点）。
- 树化：桶 ≥8 且表 ≥64 才红黑树化（否则扩容）；退化 <6。树化的意义是
  防 hash 碰撞 DoS（String 键攻击面），**不是**常规性能优化 —— 好哈希
  下永远见不到树。
- 扩容：容量×2 + 同桶 lo/hi 分链保序；**resize 后全表迭代序会变** ——
  任何依赖 HashMap 迭代序的代码（diff、序列化字段顺序）都是隐形炸弹。
- 预估容量：`new HashMap<>(expected/0.75f + 1)` 或 21+ 的拷贝构造已按
  size 优化 —— 上线即大 map 不再先扩三次。
- 与 21 SequencedCollection：LinkedHashMap/LinkedHashSet 维护双向链表
  保插入序 —— LRU 用 `accessOrder=true` + 覆写 removeEldestEntry 是
  标准款（但注意 get 也是写，多线程下不是线程安全的）。
- 记住：**HashMap 永远不是并发结构** —— 并发写连树指针都能环化（57 节）。

## 快速索引 · 续（57-84 节）

| 想找什么 | 去哪一节 |
| --- | --- |
| 可变键丢元素 | 57. HashMap 事故模式 |
| subList 血案 | 58. ArrayList 与 LinkedList 真实成本 |
| 有序 map / 范围查询 | 59. TreeMap 与 ConcurrentSkipListMap |
| 枚举集合提速 | 60. EnumMap 与 EnumSet 位向量 |
| getFirst/addLast/reversed | 61. SequencedCollection 语义 |
| List.of 与 unmodifiable 差别 | 62. 不可变集合工厂的成本 |
| 优先队列坑 | 63. PriorityQueue 与堆成本 |
| equals 契约怎么破 | 64. equals 与 hashCode 契约 |
| ConcurrentModificationException | 65. fail-fast 迭代器与 CME 事故 |
| 集合拷贝成本 | 66. 集合序列化与克隆成本 |
| Stream 到底慢多少 | 67. Stream 实现与成本模型 |
| parallelStream 什么时候翻车 | 68. 并行流事故 |
| 自定义 Collector 怎么写对 | 69. Collector 正确性（combiner 与特性） |
| toMap 重复 key / NPE | 70. Collectors.toMap 与 flatMap 陷阱 |
| 无限流要哪些短路 | 71. 短路求值与无限流 |
| lambda 分配从哪来 | 72. 捕获与非捕获 lambda 差异 |
| indy 拼接 / 日志白分配 | 73. invokedynamic 与字符串拼接内幕 |
| 什么时候别用 Stream | 74. 何时不该用 Stream |
| Optional 的边界 | 75. Optional 纪律 |
| record 数组字段 / 序列化 | 76. record 深度实践 |
| sealed + record 建模 | 77. sealed 与代数数据类型 |
| 解构模式怎么写 | 78. switch 模式匹配与 record patterns |
| when 守卫破坏穷尽性 | 79. 模式匹配陷阱（顺序/null/exhaustive） |
| var 的使用边界 | 80. var 推断纪律 |
| 文本块缩进规则 | 81. 文本块与字符串处理 |
| 桥方法 / 擦除事故 | 82. 泛型擦除与桥方法 |
| default 方法菱形冲突 | 83. 接口演化与 default 方法冲突 |
| 静态工厂怎么设计 | 84. 静态工厂与 API 设计纪律 |

## 57. HashMap 事故模式

- **可变键**：put 后改参与 hashCode 的字段 → 桶定位漂移 → get/contains
  永远 miss，但元素还"在"（遍历可见的幽灵元素）；治法：键只用不可变
  数据（record、String、业务 id）。
- equals/hashCode 不配：`a.equals(b) == true` 但 hashCode 不同 →
  两个"相等"的键各占一桶；Lombok `@EqualsAndHashCode(Exclude)` 用错字段
  是高发写法 —— 用 record 让编译器生成一劳永逸（76 节）。
- 并发使用：Java8+ 尾插法消灭了 CPU 100% 的成环死循环，但换来**静默丢
  数据/结构损坏** —— 更难查；永远别共享 HashMap，用 CHM（47 节）。
- null 语义：HashMap 允许 null key/value —— 迁移 CHM 时才炸（48 节）；
  JSON 序列化 null 字段策略要与其对齐（110 节）。
- 子类化 HashMap：覆写 put 漏了 computeIfAbsent/merge 路径 → 行为分裂；
  组合（包一个 Map 字段）永远优于继承 JDK 集合。
- `IdentityHashMap`：用 == 判等 —— 误当"去重 map"用会得到一屋子"重复"
  对象；它只该出现在序列化/深拷贝等跟踪同的场景。

## 58. ArrayList 与 LinkedList 真实成本

- 真实成本模型：ArrayList 尾插均摊 O(1)（1.5x 扩容 + Arrays.copyOf），
  随机访问 O(1)，中间插删 O(n) 但 memmove 极快；LinkedList 每节点 ~40B
  开销（3 引用 + 头）+ GC 指针扫描税 + 缓存处处 miss。
- 结论铁律：**1 万以内的任何"频繁插删"，ArrayList 几乎必胜**——缓存
  局部性碾压大 O；LinkedList 的唯一合理生态位是 ListIterator 顺序中改。
- 当队列用：`ArrayDeque` 严格优于 `LinkedList`（Deque 场景官方文档明示）
  —— `LinkedList` 当队/栈用是历史遗留习惯，直接禁。
- subList 血案三连：① 视图 —— 父表结构性修改后碰子视图 = CME（**单线程**
  也抛）；② 子视图持父引用 —— subList 塞进缓存 = 整个父表泄漏；
  ③ 序列化 subList 序列化的是视图语义。要快照就 `List.copyOf(sub)`。
- 预估容量：大小已知时 `new ArrayList<>(n)` 免三次扩容拷贝（37 节）；
  `trimToSize()` 促序列化前收身。
- Arrays.asList：定尺寸视图（set 可、add 不可）+ `List.of(arr)` 是拷贝
  —— 一字之差，事故面完全不同（62 节）。

## 59. TreeMap 与 ConcurrentSkipListMap

- TreeMap 核心价值不是"有序迭代"而是**导航查询**：first/last/floor/
  ceiling/headMap/tailMap/pollFirstEntry —— 区间扫描、时间轴索引、
 排行榜的标准结构。
- 比较器纪律：TreeMap 用 comparator 判等（不是 equals）—— 比较器与
  equals 不一致时 `containsKey` 与 `equals` 语义分叉（64 节），用
  Comparator.comparing 时明确它就是你的"相等"定义。
- 事故模式：subMap 边界含糊（inclusive 参数/无界 overload）→ 差一元素；
  headMap 返回视图 —— 键改动后视图行为跟着变；pollFirstEntry 非原子。
- ConcurrentSkipListMap：CAS 跳表；迭代与范围视图**弱一致**；size() 走
  全表 O(n)（监控禁用）；无锁 ≠ 更快 —— 常规读写比 CHM 慢数倍，只为
  "有序 + 并发"这个组合付费。
- 用法定位：延迟任务队列（按时间取队首）、限流滑动窗口边界、一致性
  索引 —— 需要排序 + 并发时它没有替代品；只排序不并发用 TreeMap。
- headMap/tailMap 的 concurrent 版语义：视图实时反映插入 —— 滑动窗口
  实现里"边插边视"正好，但跨视图 remove 的复合操作仍需外部协调。

## 60. EnumMap 与 EnumSet 位向量

- EnumMap：底层 `values()[ordinal]` 数组 —— 零装箱、零哈希、O(1) 直接
  索引；迭代序 = 枚举声明序（而非插入序）；null 键直接 NPE。
- EnumSet：≤64 常量 = 单个 long 位图（RegularEnumSet），contains/add
  是位运算；>64 走 long[]（JumboEnumSet）；of/range/complementOf 工厂
  是正确入口，别 new（包私有）。
- 性能差实测：状态机/转移表用 EnumMap 比普通 HashMap 快 5-10 倍且省
  内存 —— 枚举做键却不用 EnumMap/EnumSet 是"免费性能没拿"。
- 事故模式：**依赖 ordinal 当协议值**（写进报文/存库）—— 新增枚举
  改变全体语义；ordinal 只允许在 EnumMap/EnumSet 内部语义里出现。
- 序列化注意：EnumSet 反序列化与当前枚举声明比对；跨版本枚举增删 +
  存量序列化数据 = 治理问题（接口协议要显式编号字段，别用 ordinal）。
- EnumMap 的 values() 视图是可变的空槽数组包 —— 直接改它 = 破坏内部
  结构，只读使用。

## 61. SequencedCollection 语义

- 21（JEP 431）把 List/Deque/LinkedHashSet 统一进 SequencedCollection：
  `getFirst/getLast/addFirst/addLast/reversed()` —— 逆序遍历从手写
  index-for 变成一个视图。
- **reversed() 是视图不是拷贝**：遍历它 = 反向走原表；对它做结构性修改
  会打到原表 —— 拿 reversed 当"快照"存起来是理解错误。
- LinkedHashSet 的 sequenced 语义：`addLast(已存在元素)` = **移动**到尾
  （reposition），不是重复插入 —— 用它可实现 LRU 触碰（但记住它仍非
  线程安全，56 节）。
- 实现差异：List.of 的 addFirst 抛 UOE（不可变）；ArrayList.getFirst
  与 get(0) 等价但空表抛 NoSuchElementException 而非 IndexOutOfBounds
  —— 异常语义不统一，跨实现迁移要过一遍。
- API 目标版本坑：接口方法是 21 新增 —— 库代码若在 17 运行时会
  NoSuchMethodError；库作者按 `--release` 门槛控制（4 节）。
- 迭代中 remove 的正解：`reversed().iterator()` 从尾删同样受 fail-fast
  支配（65 节），别把 reversed 当并发工具。

## 62. 不可变集合工厂的成本

- `List.of/Map.of/Set.of`：专属紧凑类直接持有引用（ListN 等），比
  `unmodifiableList(new ArrayList)` 省约一半内存、迭代快（无 modCount
  套壳）；**一律不允许 null 元素**（直接 NPE）。
- `copyOf` 与 `toList()`（16+）：入参已是不可变集合时**零拷贝复用**；
  `Stream.toList()` 保留 null 元素且保持相遇序 —— 与 `Collectors.toUnmodifiableList()`
  的 NPE 语义不同，选错就是线上 NPE。
- **unmodifiable* ≠ immutable**：`Collections.unmodifiableList` 是视图，
  背靠可变集合实时跟随 —— "防御性拷贝"错写成视图包装是常见安全/并发
  事故；真不可变用 copyOf。
- 迭代序：List.of 保序；Map.of/Set.of 的迭代序**不保证且含 run 级盐值
  （防哈希碰撞 DoS）** —— 同代码不同进程顺序可不同；依赖其顺序做
  diff/签名的代码会间歇性失败。
- Map.ofEntry/entry：>10 对的 map 只能走 entry 路线；重复键抛 ISE
  （静态初始化期炸，属"好事"）。
- 事故模式：API 出口返回内部可变集合的 unmodifiable 视图 → 调用方
  序列化时同步到了并发修改 —— 出口一律 `List.copyOf(internal)`。

## 63. PriorityQueue 与堆成本

- 结构：二叉堆数组；offer/poll O(log n) sift 上/下浮；`remove(Object)`
  是 O(n) 线性找 + O(log n) 沉 —— "先 offer 再 remove"的取消模式在大
  队列上是隐藏 O(n²)。
- **堆序 ≠ 全序**：遍历（含 toString）顺序不定 —— 采样出队必须 poll
  循环或先拷贝排序；调试时打印 PQ "看起来有序/乱序"都别下结论。
- 容量策略：无界扩容（<64 翻倍，之后 1.5x）；已知规模预估容量省反复
  拷贝；元素数波动大的长驻队列要算稳态内存。
- 并发缺失：PriorityQueue 非线程安全 —— 并发优先队列 =
  PriorityBlockingQueue（**全局单锁**）→ 高吞吐场景改用 JCTools 或
  分片+合并；锁竞争下 PBQ 吞吐塌方是常被忽略的瓶颈点。
- 比较器纪律：与 equals 不一致 → "等价"元素重复入队（去重要外部做）；
  修改已入队元素的排序字段 = 堆序损坏（同 57 节可变键问题）。
- 事故模式：定时任务轮询队列头不 poll（只 peek 判断）→ 卡死整队；
  peek/poll 的语义差一行就是一个挂死。

## 64. equals 与 hashCode 契约

- 契约与爆炸点对照：**对称性破坏**（子类 instanceof equals）→ HashSet
  里同一逻辑元素两份（104 节展开）；**一致性破坏**（可变字段入 hash）→
  put 后改字段 get 丢失（57 节）；**传递性破坏**（带精度/单位的 equals）→
  分层包含关系错乱。
- hashCode 缓存的良性竞态：String 的 racy single-check 是合法的
  （幂等计算 + int 写原子）—— 复制这个模式的前提是"重算结果恒等 +
  字段类型原子写"，不满足就老实 volatile/synchronized。
- 生成纪律：record/IDE 生成 → 审字段（派生字段剔除、多对多字段用
  Objects.hash 组合）；Lombok 的字段选择要逐个 review，默认生成常错。
- BigDecimal 特例：equals 含 scale（2.0 ≠ 2.00）而 compareTo 不含 ——
  HashSet/HashMap 里"相等"金额分裂，TreeMap 反而正确 —— 金额建模
  在 106 节展开。
- 跨类 equals（getClass vs instanceof）：值语义（record）全字段；
  实体语义（JPA）用业务键 + getClass —— Hibernate 代理是子类，
  instanceof 反而更常用，但必须成对检查对称性。
- 纪律：改 equals/hashCode = 重跑"集合三件套"测试（HashSet 去重、
  HashMap 取回、排序容器一致性）。

## 65. fail-fast 迭代器与 CME 事故

- 机制：容器持有 modCount；结构性修改（add/remove，set 不算）+1；
  迭代器 next 时发现 modCount 变化 → CME。**"尽力而为"**：并发修改
  不保证抛 —— 错过窗口就静默产出脏结果；CME 是调试器，不是安全网。
- 单线程也抛的三种场景：迭代中（含 forEach 函数体内）add/remove；subList
  与父表交叉修改（58 节）；lambda/闭包逃逸出迭代作用域再回来改原表。
  注意 `ArrayList.sort` 也会 bump modCount —— 边迭代边 sort 同样是雷。
- 正确姿势清单：`iterator.remove()`（唯一安全删除）；`removeIf`（注意
  谓词抛异常 = 部分修改，非原子）；先 `toList()` 快照再改原表；
  并发场景换 CHM 弱一致迭代（47 节）。
- 事故模式：回调/监听器模式里遍历监听器列表而回调内部注册/注销 →
  偶发 CME 且不可复现 —— 监听器容器改 CopyOnWriteArrayList（55 节）
  或事件队列化。
- 反模式：catch (ConcurrentModificationException) 吞掉重试 —— 治标
  掩盖并发 bug，正确修法永远在容器选型与修改点。

## 66. 集合序列化与克隆成本

- JDK 集合自带 writeObject 剖开实现：HashMap/ArrayList 序列化**元素数
  而非容量**（避免把扩容冗余也写出去）—— 自定义持有集合的类别手工
  "剥容量"，JDK 已做；但自研容器序列化常忘记，dump 膨胀几倍。
- 克隆真相：`HashMap.clone()` 浅拷贝 —— 桶结构独立、**元素引用共享**；
  `super.clone()` 链里漏改可变字段 = 共享状态事故（107 节的序列化变体）；
  组合/工厂替代继承式 clone。
- 防御性拷贝边界：构造入口（防 this 逃逸期修改）、出口（防调用方改内部）
  —— 两端都做才算闭环；`List.copyOf` 是零拷贝捷径（62 节）。
- record 序列化：走 canonical 构造器 → 不变式在反序列化时仍然成立
  —— 这是它对老 readObject 攻击面的结构性防御（110 节）。
- 深图序列化成本：集合套集合的 deep clone = 反射走全图 —— 大 DTO
  图的"clone 一把"在压测里以百 ms 计；高频路径要扁平结构（24 节 SoA）。
- 事故模式：serialVersionUID 未固定 → 升级类定义后反序列化炸
  InvalidClassException —— 所有走 Java 序列化的类显式声明 UID 是
  最小纪律（更好：根本别用 Java 序列化，110 节）。

## 67. Stream 实现与成本模型

- 管道形态：head + StatelessOp/StatefulOp 抽象管道 + terminal 触发；
  每元素经历 N 层 accept 虚调用 + spliterator 遍历 —— 简单管道比 for
  循环慢 2-5 倍是正常物理，不是 bug。
- 装箱是最大税：`Stream<Integer>` 全链装箱（26 节）；`IntStream/LongStream`
  特化管道 + 数组源是量级差异；大数据量 ETL 先想特化再想并行。
- 特性位（characteristics）驱动优化：SIZED/SUBSIZED → collector 精确
  预分配；SORTED/DISTINCT 影响后续融合 —— 有状态操作（sorted/distinct）
  强制攒全量，内存峰值要预算。
- reduce vs collect：可变归并（collect + ArrayList::new）比反复新建
  Immutable 对象的 reduce 少分配；joining 用 collector 不用 reduce 字符串。
- 短路（71 节）与 limit 的组合语义：`skip+limit` 在无序流上更便宜
  （UNORDERED 特性释放）—— limit 大值 + ordered 源 = 排序缓存代价。
- 决策线：热路径预算 <1µs 用循环；声明式数据加工用 Stream；两者之争
  用 JMH 定量（74 节），不靠信仰。

## 68. 并行流事故

- 事故一号：**commonPool 共享**（51 节）—— 一个并行流里 IO/锁等待，
  全 JVM 并行任务陪葬；铁律：并行流只做 CPU 密集计算，IO 一律显式池。
- 事故二号：分裂不均 —— `Stream.generate/iterate`、iterator 包壳的
  spliterator 分裂质量差，并行反而慢；优质源 = 数组/ArrayList/IntStream.range。
- 正确性事故：`forEach` 并行乱序（要消费序就用 toList 后处理）；
  `reduce` 无结合律（减法/字符串拼接顺序敏感）→ 并行结果错且偶发；
  自定义 collector 的 accumulator 碰共享状态 = 数据竞争（69 节）。
- 收益判定：N_cores × 元素成本 ≫ 分裂开销才有戏 —— 实测门槛经验值
  是"每元素 ≥ 微秒级"或"元素数 ≥ 数万"；否则串行更快是常态。
- 池的显式化：`FJPool.submit(() -> stream.parallel()...)` 隔离并行度；
  平台侧压测 + JFR 看 commonPool 饥饿（`JavaMonitorEnter`/`ThreadPark`）。
- 排查记号：CPU 满 + 吞吐不涨 + 火焰图全是 ForkJoin 任务 = 分裂不均
  或阻塞在任务内的指纹。
## 69. Collector 正确性（combiner 与特性）

- 五元组一个都不能错：supplier（初始容器）/ accumulator（累加，禁副作用）/
  combiner（并行合并，**必须满足结合律且不碰共享状态**）/ finisher /
  characteristics（CONCURRENT/UNORDERED/IDENTITY_FINISH）。
- 特性错标 = 静默错：UNORDERED 让 ordered 流放弃相遇序优化（findFirst
  交互处结果漂移）；CONCURRENT 声明后 combiner 被跳过 —— accumulator
  必须线程安全且容器合一；没做到却标了 = 数据竞争。
- groupingBy 的并行本质：combiner 是 map merge —— 每个 worker 各建子 map
  再合并；downstream 嵌套（mapping/counting/summing）是表达力核心；
  `groupingByConcurrent` 换 CHM（无序，别拿它保序）。
- 事故模式：accumulator 里顺手往外部集合 add —— 串行测试全过，并行
  生产数据竞争（68 节）；修复原则：所有状态从 supplier 出。
- teeing（12+）双路归并、mapping + flatMapping 组合 —— 复杂聚合优先
  组合现成件，手写 collector 前先找有没有。
- 测试纪律：自定义 collector 必须写"并行与串行结果一致"用例（113 节）。

## 70. Collectors.toMap 与 flatMap 陷阱

- `toMap` 三雷：① **重复 key 无 merge 函数直接 IllegalStateException** ——
  压测数据一脏就炸，显式 `(a, b) -> a/b` 是纪律；② **value mapper 返回
  null → NPE**（底层 map.merge 语义）—— 想存 null 值就别用 toMap，改
  循环或 Collect 类库；③ value mapper 抛异常 = 中途夭折（外部副作用已发生）。
- 正确模板：
```java
Map<Long, User> byId = users.stream()
    .collect(Collectors.toMap(User::id, u -> u, (a, b) -> a)); // 幂等：旧值胜
```
- flatMap 陷阱：mapper 返回 null → NPE（用 `Stream.ofNullable`（9+）
  产空流）；每元素建子流有分配成本 —— 深层 flatMap 链在热路径要 JMH
  （67 节）；`flatMapToInt/Long/Double` 特化防装箱。
- groupingBy + counting 返回 `Map<K, Long>` —— 后续拆箱税（26 节）；
  只要计数时 `Collectors.summingLong(x -> 1L)` 也一样，接受它。
- 事故模式：toMap 的 key 重复在生产首次出现（灰度数据少）—— 修法是
  显式 merge 策略 + 单测构造重复键。

## 71. 短路求值与无限流

- 无限源（`generate/iterate`）**必须**配短路终端（limit/findAny/anyMatch/
  allMatch）；`iterate(seed, hasNext, next)`（9+）双参版自带谓词上限，
  比"无限 + takeWhile"更防失控。
- 顺序语义陷阱：`limit` 在 `sorted` 之前 = 只取前 N 再排序（快）；
  在之后 = 全排序再取 N（贵 N 倍）—— 管道顺序就是性能契约。
- takeWhile/dropWhile（9+）：依赖谓词触发 —— 无限流上谓词永不 false
  = 永不终止（takeWhile 不是超时机制）；上生产前给无限流加硬 limit。
- findFirst vs findAny：并行下 findAny 免序更快；findFirst 保序但并行
  退化；语义上"第一个"若无业务含义，写 findAny。
- allMatch/anyMatch 的空流边界：vacuous true —— 全匹配语义用在"权限
  全过"上，空集合全过经常是错的业务结果（应显式判空）。
- 事故模式：并行无限流（generate + 无短路）→ commonPool 全 worker
  空转 CPU 100% —— 51/68 节的复合事故。

## 72. 捕获与非捕获 lambda 差异

- 分配规则：非捕获 lambda（不引用外部变量）→ **单实例复用**（LambdaMetafactory
  缓存）；捕获（this、局部变量）→ 每次求值新对象 —— 热路径里捕获 lambda
  就是隐形分配站点（20 节 JFR 可见）。
- this 捕获：实例方法引用 `this::handle` 捕获 enclosing this —— 每对象
  一份；静态可复用的比较器/谓词抽成 `static final` 字段（别在循环里
  `new` lambda，也别重复求值捕获表达式）。
- invokedynamic 链接成本：**首次**调用生成 hidden class（毫秒级）——
  脚本/短命进程的冷启动税；serverless 用 CDS/AOT 缓解（18 节）；
  长服务忽略不计。
- 陷阱：lambda 体里改外部可变静态 = 并发炸弹；捕获的变量隐式 final
  —— 为绕开它复制变量的代码要警惕"读了旧值"的语义。
- 诊断：JFR 分配采样里 `LambdaMetafactory` 相关帧 + 你的 lambda 名；
  JMH `gc.alloc.rate` 对比字段提取前后的差值就是捕获成本。
- 纪律：高频小函数声明为 `private static final Function/Predicate` 字段
  或 method reference —— 可读性不减，分配归零。

## 73. invokedynamic 与字符串拼接内幕

- 拼接内幕（9+）：`a + "x" + b` 编译成 indy `StringConcatFactory.
  makeConcatWithConstants` —— 运行期按常量/变量布局生成混合器，比
  StringBuilder 链聪明（常量分离、部分消除）；日常拼接**直接用 `+`**。
- 但 indy 对 EA 不可见：concat 结果不参与标量替换（15 节）—— 循环内
  拼接、热路径日志拼接仍必须显式 StringBuilder + 预估容量。
- 热路径日志白分配：`log.debug("a=" + a)` 在 debug 关闭时**照样拼接**
  —— 用占位符或 `isDebugEnabled` 守卫（91 节）；`-XDstringConcat=inline`
  可回退旧策略做对照实验（诊断用，别长期开）。
- lambda 同为 indy：生成的 hidden class 没有源文件名可断点 —— 调试时
  依赖方法名 + 行号属性；反射反推 lambda 类型不可靠（109 节）。
- 序列化陷阱：lambda/方法引用默认不可序列化（好事）—— 需要时显式
  Serializable 函数接口组合，代价是额外的桥接类。
- 观测：JFR `ObjectAllocationInNewTLAB` 里 String 比例高 → 先查日志拼接
  与字符串模板（21 节）再查业务。

## 74. 何时不该用 Stream

- 硬性不该用的场景：需要 checked exception（lambda 里的 sneaky-throw
  反模式）；双指针/索引交错算法；循环内多次 break/continue 复杂出口；
  深装箱管道（`Stream<Integer>` —— 先特化 24 节）；超热路径（<1µs 预算，
  67 节成本模型）。
- 语义信号：for 循环体里在改 2 个以上本地变量 → 那是状态机，不是映射；
  硬塞 forEach + 外部累加器 = 并行不可 + 可读性反噬（68 节正确性陷阱）。
- 该用的场景：声明式数据加工（ETL/报表/转换）、嵌套结构展平、
  可组合的过滤映射链、配合 record/模式匹配的领域管道。
- 团队纪律：Stream 与 for 之争以 JMH 数据裁决（112 节）；code review
  关注"装箱次数"与"短路机会"两个硬指标，不辩论风格。
- 迁移技巧：先写 for 版本（正确），JMH 换 Stream 版对比 —— 两种都留
  单测，性能不亏才有资格谈可读性。

## 75. Optional 纪律

- 定位：**返回类型专用** —— 表达"可能没有返回值"；禁用作字段（序列化
  不支持 + 内存税）、方法参数（拆调用方可读性）、集合元素。
- 分配成本：Optional 是包装对象（16B）；热路径原始特化用
  `OptionalInt/Long/Double`（同时避免装箱双重税）。
- 反模式清单：`isPresent() + get()`（get 已标记 Discouraged）→ map/
  ifPresentOrElse/orElseThrow；`map` 里返回 Optional 套娃 → flatMap；
  `orElse(expensive())` 无条件求值 → `orElseGet(Supplier)`。
- 等价改造：`return opt.orElse(null)` 出现在内部层 = 边界翻译（外发 API
  JSON 需 null）—— 收口在边界层做，别渗透到核心域。
- 构造纪律：可能为 null 的来源用 `Optional.ofNullable`；`Optional.of`
  用于断言非空（NPE 快速失败）；**返回 Optional 的方法永不返回 null** ——
  违反这条就毁掉全部意义。
- 事故模式：`Optional.get()` 无检查 → 21+ 换 `orElseThrow()` 拿到清晰
  异常；字段 Optional 序列化炸（Jackson 报错或丢字段，110 节）。

## 76. record 深度实践

- 语义底座：隐式 final + 全 final 字段 + 自动 equals/hashCode/toString；
  **紧凑构造器是校验/归一化的唯一正确入口** —— 反序列化也走 canonical
  构造器，不变式全程成立（对比老 readObject 攻击面，110 节）。
- 数组字段两宗罪：equals/hashCode 对数组用**引用相等**；getter 返回内部
  数组 = 可变逃逸。治法：紧凑构造器里防御性拷贝 + List 替代数组。
- hashCode 无缓存：record 每次调用重算全字段哈希 —— 大字段/深组合
  record 高频作 HashMap 键时，自己覆写 hashCode 加缓存字段（保持
  equals 契约不变）。
- 建模纪律：DTO/值对象/消息/事件/多重返回值 → record；**JPA 实体禁**
  （需要无参构造、可变、代理子类）；本地 record（方法内）配合 Stream
  中间结构是 21+ 最顺手的小工具。
- 静态工厂 + record：校验进紧凑构造器、命名进静态工厂（`Money.cents`
  / `Money.of`），显式语义代替万能构造器。
- 嵌套组合：record 里 record + sealed 层次（77 节）—— ADT 建模的
  完整形态；避免"扁平大 record"（20+ 字段 = 参数对象的失控前兆，84 节）。

## 77. sealed 与代数数据类型

- 组合拳：`sealed interface` + `permits` + record 实现 = sum type；
  变体数有限、每变体自带数据 —— 与"枚举 + 字段污染"相比是代差建模。
- exhaustive switch 是核心收益（78/79 节）：**不写 default** —— 新增
  一个 permitted 子类，所有消费方编译期报错；这正是"让编译器替你枚举
  未来"的位置。
- 边界纪律：sealed 与子类必须同模块（无名模块则同包）；`non-sealed`
  打开分支是有意决策（开放扩展点要注释）；反射 `getPermittedSubclasses`
  支持工具化检查。
- 建模模式：领域事件（OrderPlaced/Paid/Shipped）、结果类型
  （Success/Retryable/Fatal —— 替代异常做**预期**分支，28 节）、
  状态机迁移表；每个变体 = record，行为放外部 switch（数据与行为分离）。
- 陷阱：sealed class 的子类各自要管 equals（record 自动、普通类手写漏）；
  模式匹配顺序子类在前（79 节）；序列化（Jackson 多态白名单，110 节）。
- 反模式：把 sealed 当"访问控制"用 —— 它的语义是"封闭变体集"，不是
  私有继承；想隐藏实现用包私有 + 接口（84 节）。

## 78. switch 模式匹配与 record patterns

- 21（JEP 441/440）转正：`case 类型 模式变量`、record 解构
  `case Point(int x, int y)`、`when` 守卫；switch 表达式强制穷尽 + yield。
- 解构威力：嵌套 record 一次拆到叶子 ——
```java
return switch (msg) {
    case Payment(Point(var x, var y), Money(var amt)) when amt > 0
         -> new Transfer(x, y, amt);
    case Payment p                    -> reject(p, "amt<=0");
    case Refund r                     -> r;
};
```
- 类型模式顺序：子类型必须先于父类型（dominance 编译错会拦）；
  常量 case 与类型模式混排时注意匹配优先级 —— 写不出就拆成两个 switch。
- 泛型模式：`case List<String> l` 允许（模式匹配放开了非具化类型限制），
  但 erasure 后运行时只查原始类型 —— 元素类型靠编译器语义，别在
  case 里假设元素类型已验。
- 性能面：sealed 层次让编译器编成类型检查跳转链/表 —— 替代访问者模式
  的样板代码，性能不差（17 节去虚化联动）。
- 表达式 vs 语句：新代码一律 switch 表达式（穷尽性强制）；旧 switch
  语句只有落在模式匹配上时才保留。

## 79. 模式匹配陷阱（顺序/null/exhaustive）

- **exhaustive 的守卫漏洞**：`case X when cond` **不计数** —— 即使 sealed
  全类型都写了带守卫的 case，编译器仍要求 default；一旦写 default，
  新增子类时编译器不再报错 —— 穷尽性保护失效。治法：default 里抛
  明确异常（fail-fast 于 92 节语义），或把守卫拆成不带守卫的类型
  case + 内部 if。
- **null 语义**：模式 switch 对 null selector 直接 NPE —— `case null`
  从未转正（预览期被撤）；null 检查保留在 switch 之外，或包一层
  `Optional`/包装类型。
- 运行时失配：调用方旧字节码 + 提供方新增变体 → switch 表达式抛
  `MatchException`、switch 语句**静默落空**（更危险）—— sealed 层次与
  消费方必须同版本发布（模块化/版本对齐，13 节）。
- instanceof 模式作用域：模式变量只在"保证匹配"的路径可见（编译器流
  分析）—— else 分支/短路后半段引用它 = 编译错，别硬绕。
- 顺序细节：常量模式先于类型模式（数字字面 case 在 `Number n` 前）；
  guarded case 的 when 里别做重活 —— 它在匹配失败后反复求值。
- 测试纪律：sealed 层次加"全变体遍历"参数化测试，防止新变体没有
  消费路径（113 节）。

## 80. var 推断纪律

- 适用判据一句话：**右边的类型在行内一眼可见**（`new ArrayList<>()`、
  工厂方法、字面量）才用 var；返回值依赖 API 签名、链式调用、菱形
  推断复杂场景显式写类型 —— 推断错误是远程的，阅读成本是即时的。
- 静态类型不变：var 只是语法糖，接口期望 `List` 而写 `var l = new
  ArrayList<>()` 后 `l.add` 与泛型/后续赋值按 ArrayList 静态类型走 ——
  review 关注"静态类型被窄化"这一点。
- var + 匿名类：拿到**交集类型**（匿名类同时实现多接口时）—— 后续
  赋值回不出来；这是 var 的合法妙用（本地标签接口组合），也是维护
  炸弹，注释必须跟上。
- 禁区：var x = null（推不出）；字段/参数声明（至今不支持）；
  var 在 lambda 参数（11+）仅为了挂注解 `@Foo var x` —— 用不用无所谓。
- 泛型陷阱：`var list = List.of(1, 2.0)` 推成 `List<? extends Number
  & Comparable<...>>` 交集 —— 后续 add 直接编译错；交集类型是 javac
  行为不是 bug，换显式声明。
- 团队规约：离散类型用 var、远距离类型显式 —— 一行规矩可执行，
  比"酌情使用"有效十倍。

## 81. 文本块与字符串处理

- 缩进规则（15+，JEP 378）：**incidental whitespace**（全体行的最小公共
  缩进）被剥离；`\` 行尾续行（拼掉换行）；`\s` 保留尾随空格 —— 文本块
  默认**剥尾随空格**，测协议文本时就是 off-by-one 事故源。
- 定界符细节：开定界 `"""` 后必须换行（首行内容算在第二行）；
  `formatted()` 参数化（背后还是 String.format 的成本，21 节）。
- Java 没有原始字符串：正则/JSON 里的转义照旧双反斜杠 —— 文本块只
  解决换行与引号，不解决转义；别把 SQL 字符串写成"以为是 raw"。
- 工程用途：测试 fixture、JSON/SQL 模板、错误消息 —— 生产模板需求
  （用户输入插值）必须走模板引擎：`formatted` 拼用户输入 = 注入面。
- 配套 API（11+）：`stripIndent/translateEscapes/strip` 与文本块语义
  呼应；多行协议解析用 `String.lines()`（拆分语义与 split("\n") 的
  尾空差异，29 节）。
- 事故模式：IDE 自动对齐"顺手"给文本块整体加缩进 → 剥离后字符串内容
  意外变化 —— fixture 断言用 `hasToString`/`isEqualTo` 全量比对，
  别用 contains。

## 82. 泛型擦除与桥方法

- 擦除后果清单：运行时无 `<T>`（不能 `new T()`/`T[].class`/`instanceof
  List<String>` 原生写法）；静态成员不随参数化变化（一个 Field 一份）；
  泛型数组只能 `(T[]) new Object[n]`（约定 unchecked，堆污染自负）。
- **heap pollution 的时间差**：错误类型混进泛型容器时**存入不炸**，
  取出强转才炸 ClassCastException —— 事故栈离污染源十万八千里；
  治法：边界处收口（unchecked 警告零容忍 + @SuppressWarnings 最小
  范围化）。
- 桥方法（bridge method）：子类覆写泛型父类方法时编译器合成
  `Object → T` 的桥 —— `getDeclaredMethods()` 会把它一起返回：
  注解处理器/序列化/AOP 按"方法名+参数"去重会漏（isBridge 过滤是
  必修动作）。
- varargs 堆污染：`@SafeVarargs` 只该标在"只读不存"的方法上；
  泛型 varargs 数组逃逸 = 制造延迟爆炸点（Effective Java Item 32 现在仍灵）。
- 擦除签名冲突：泛型方法与原始签名重载共存时（erasure 后同签名）
  编译错 —— 依赖泛型区分重载是设计错误，改名。

## 83. 接口演化与 default 方法冲突

- 解析规则（记结论）：类/父类胜过接口；**子接口胜过父接口**；
  两者都无更具体实现 → 编译错（被迫显式覆写，`Interface.super.m()` 选边）。
- 事故一号：向已有接口插 default —— 实现类**静默继承**新行为，
  语义漂移不报错；反面：修改已有 default 的方法体 = 二进制兼容但
  **行为不兼容**（老调用方没重编译就跑了新逻辑）—— default 的修改
  也要走破坏性变更评审。
- 演化优先级：加新接口/新静态工厂 > 加 default（需全员语义评审）>
  改 default 方法体（禁止轻改）> 改抽象签名（破坏）。
- 私有接口方法（9+）：default 之间的复用拆分；接口静态方法 = 工厂
  的家（84 节）；接口字段永远是 public static final。
- 多继承行为（trait 风格组合）滥用：default 调 this 抽象方法做骨架 ——
  小范围优雅，跨包组合复杂化后调试成本指数上升；行为组合用组合类。

## 84. 静态工厂与 API 设计纪律

- 静态工厂收益清单（Effective Java Item 1 的工程化）：命名表意
  （`of/valueOf/from/parse/getInstance/create`）；可缓存（`Boolean.valueOf`）；
  可返回接口或子类型；可隐藏实现（接口 + 包私有实现类，record 天然
  是这个形态的 value 版本）。
- API 边界纪律：参数**尽早校验**（构造器/静态工厂入口一次，内部信任
  传递 —— 92 节）；消息带值不带形容词（"latency=-1 must be >= 0"）；
  null 语义写进 javadoc 或用注解（JSpecify 优于 JSR305）。
- 返回出口：集合出口 `List.copyOf`（62 节）；可能缺 → Optional（75 节）；
  多值 → record（76 节）；**永不返回内部可变结构**。
- 参数对象化：6 参以上或同类型相邻参数（三个 String）合并为 record ——
  可读性 + 可演进（加字段不破坏调用方）。
- deprecation 纪律：`@Deprecated(since="x.y", forRemoval=true)` + javadoc
  写替代方案 + 保留两个版本周期；forRemoval=true 一出现，编译警告
  才会真正刺痛调用方。
- 反模式：getter/setter 全开的"结构类"暴露内部 —— 外部可变 = 并发与
  不变式双输（107 节）；API 面越小，事故面越小。

## 快速索引 · 续（85-114 节）

| 想找什么 | 去哪一节 |
| --- | --- |
| 不可变对象的"修改"模式 | 85. Builder 与 record 的 with 模式 |
| Lombok 与注解处理器 | 86. 注解处理与编译期验证 |
| 异常分层设计 | 87. 异常架构设计 |
| 吞异常 / 日志双打 | 88. 异常控制流反模式 |
| 资源关闭与 suppressed | 89. try-with-resources 与资源链 |
| cause 链包装规则 | 90. cause 链与错误传播纪律 |
| 日志规范 / MDC 断链 | 91. 日志纪律与结构化日志 |
| assert 与业务校验 | 92. 断言、校验与 fail-fast |
| JFR 事件清单与用法 | 93. JFR 深度用法 |
| async-profiler / 采样偏差 | 94. Profiler 选型与采样偏差 |
| heap dump 怎么读 | 95. 堆转储分析方法论 |
| Metaspace 涨爆 | 96. Metaspace 与类加载器泄漏 |
| CPU 100% 排查步骤 | 97. CPU 飙高排查路径 |
| P99 尖刺排查 | 98. 长尾延迟排查路径 |
| jstack / jcmd 线程分析 | 99. 线程转储与锁分析 |
| flag 审计与变更管理 | 100. JVM 参数管理纪律 |
| 双亲委派被破坏 | 101. 类加载器与双亲委派 |
| 同名类 / MR-JAR | 102. Jar Hell 与多版本 JAR |
| 百万虚拟线程怎么诊断 | 103. 虚拟线程下的诊断差异 |
| HashSet 里出现重复 | 104. equals 不对称与 Set 事故 |
| 时区 / tzdata / 单调钟 | 105. 时间与时区事故 |
| BigDecimal scale 陷阱 | 106. 金额精度与舍入事故 |
| 静态字段泄漏 / 单例分裂 | 107. 静态可变状态之罪 |
| 手写缓存吃掉老年代 | 108. 过度缓存与 GC 压力 |
| 反射性能 / --add-opens | 109. 反射与 MethodHandles 成本 |
| 反序列化攻击面 / Jackson | 110. 序列化与 Jackson 陷阱 |
| 无界队列 OOM | 111. 线程池配置事故复盘 |
| 基准为什么不可信 | 112. JMH 微基准纪律 |
| 并发 bug 怎么测 | 113. 并发代码测试纪律 |
| 复盘模板 | 114. 事故复盘 Runbook |

## 85. Builder 与 record 的 with 模式

- record 不可变 →"修改"= 拷贝：`with` 模式（`new Money(m.major(),
  m.minor() * 10)`）—— 手写 withXxx 或注解生成器；每个 with 是**全字段
  拷贝**，大 record 高频 with = 分配洪峰（20 节），内部可变 + 出口冻结
  是折中（`toFrozen()` 快照）。
- Builder 的正确生态位：**参数集大 + 有默认值 + 渐进构建**（配置对象）；
  record + builder 二象：builder 是可变草稿，`build()` 走紧凑构造器校验。
- Builder 陷阱清单：① 复用 builder 多次 build 但部件共享（List 引用未
  防御拷贝 → 两个"产品"联动）；② build 后继续改 builder 再 build =
  时间线混乱 —— build 设为一次性（置 built 标志）；③ builder 字段
  缺省值与 record 校验冲突（默认值本身非法）。
- toBuilder 模式（Jackson `@JsonPOJOBuilder`、JPA 更新路径）：
  从现 record 反推 builder 改一个字段 —— 谨防把它当 setter 用（
  大对象频繁 toBuilder().x().build() = 隐性 GC 压力，JFR 盯分配）。
- Lombok `@Builder` 与 record 冲突面：record 已有 canonical 构造器，
  @Builder 生成的是平行入口 —— 优先显式静态工厂；Lombok 在 23+ 还
  需要 `-proc` 显式开关（86 节）。
- 纪律：withXxx 保持与原 record 相同的校验路径（别绕过紧凑构造器）；
  with 命名禁 set 前缀 —— 语义（新值）不是副作用。

## 86. 注解处理与编译期验证

- 23 起的硬变化：**javac 默认不再从 classpath 隐式发现处理器** ——
  Lombok/MapStruct 需要 `-proc:full` 或显式 `-processor`；CI 突然
  "Lombok 失效"（getter 全消失）就是它。
- Lombok 的本质：编译期改写 AST 的非标准魔法 —— 与 javac 内部 API
  强耦合，**JDK 升级常踩**（版本矩阵必须锁）；record 覆盖了大半数据
  类场景，新代码先问"record 够不够"再上 Lombok。
- 生成代码纪律：生成的类进 source jar（IDE 不炸）；Gradle 增量编译
  的 annotationProcessor 配置与 scope 分离；生成物**必须**可 review
  （构建 diff 里看得见）。
- 自研处理器要点：`Messager` 报错（不要 throw，那会埋没错误位置）；
  ElementKind/TypeMirror 分层判断；Filer 输出路径由编译器管，别自己
  写文件；round 语义（多轮处理）+ 生成物再被处理的循环防护。
- 编译期验证收益：把"运行时才炸"的不变式（字段组合、注解组合、
  序列化兼容）前移 —— 注解 + processor 或 ErrorProne；
  代码生成（JSON 序列化器、映射类）胜过运行时反射（109 节性能差）。
- 事故模式：处理器执行顺序依赖（A 生成、B 才能处理）—— 用
  `getSupportedAnnotationTypes` 显式声明 + processorpath 的顺序控制，
  并写进构建文档；隐式顺序 = 不可重现构建。

## 87. 异常架构设计

- 两层分类：**技术异常**（网络/DB/序列化 —— 可重试与否是核心属性）与
  **领域异常**（业务规则拒绝 —— 不可重试，携带业务码）；层与层之间
  翻译（存储层 → 服务层的技术异常聚合为统一类型，Spring
  DataAccessException 是范式）。
- checked vs unchecked 现代共识：默认 unchecked（RuntimeException）；
  checked 只用于"调用方必须能恢复且属于 API 契约"的极少数场景 ——
  更常见的是预期结果用**结果类型**（sealed + record，77 节）而非异常。
- 包装三规则：必须保 cause（90 节）；Error / InterruptedException 不包
  （中断恢复语义不能吞，88 节）；包装层数 ≤2（洋葱异常栈可读性归零）。
- 全局兜底三件套：`Thread.setDefaultUncaughtExceptionHandler` + 每个池
  的 handler（111 节）+ Future 异常的显式消费 —— 少一件就有"消失的异常"。
- 事故模式：ErrorCodeException + 全局 catch 当流程控制 —— 异常驱动
  的每秒万级抛掷在 JIT 上放大（28 节），日志层全是重复堆栈。
- 领域异常 + 模式匹配：sealed 异常层次 + switch 分发 = 类型安全的
  错误处理（87 与 77 节的交汇点）。

## 88. 异常控制流反模式

- 吞异常三态（全错）：空 catch / 只 log / 转返回 null —— 调用方拿到
  null 后 NPE，栈离事故源 5 层；正确：要么处理（降级 + 指标），要么
  继续抛（带上下文，90 节），要么翻译成结果类型。
- log-and-rethrow 双打：每层各 log 一遍 → 日志里同一异常 N 条、
  告警 N 倍、根因归因混乱 —— **边界 log 一次**，其余层只包装。
- finally 四雷：finally 里 return（吞掉 try 的异常与返回值）；finally
  抛新异常（覆盖原异常，suppressed 都救不全）；finally 做重活（资源
  关闭也要快）；多资源非 twr 嵌套（89 节）。
- 吞 InterruptedException 的连锁反应：中断标志被吃 → 上层取消逻辑
  （超时/停机）失灵 → 停机挂死；标准动作：恢复中断
  `Thread.currentThread().interrupt()` 或直接上抛。
- 循环内异常驱动业务：parse 每条失败都 throw —— 分配 + deopt + 日志
  洪水（28 节）；预期失败返回 Optional/结果对象，异常只留给真异常。
- 检测手段：JFR `JavaExceptionThrow` 计数按站点聚合 —— 每秒 >1000 的
  业务站点都是控制流滥用嫌疑（93 节）。

## 89. try-with-resources 与资源链

- 编译产物语义：嵌套 try + **suppressed exceptions**（关闭时的异常挂在
  主体异常上，`getSuppressed()` 取）—— 诊断"关闭也炸"必须看 suppressed，
  只打印顶层消息会丢一半现场。
- 资源顺序：后开先关；声明顺序即关闭逆序 —— 有依赖的资源（连接 →
  语句 → 结果集）一次声明全在 twr 里，别手工分层。
- 9+ 的 effectively final 资源：已在 try 外声明的变量可直接用 ——
  但"try 外的赋值"意味着异常路径上可能未初始化，理解成本换糖吃，新
  代码仍推荐 try 内声明。
- 自研 AutoCloseable：close 幂等（可能被调多次）；close 尽量不抛
  checked（调用方finally 链会被卡住）；close 里再抛异常要有 suppressed
  语义。
- 陷阱：twr 里 `return` —— 编译器保证 return 前先 close（顺序正确），
  但 close 抛异常会**替换**你 return 的异常路径（suppressed 挂上）——
  单测覆盖"关闭失败"分支。
- 池化资源：close = 归还（连接、Netty ByteBuf）—— twr 语义正确但
  借出未还的统计（leak detector，39 节）仍是第一道防线。

## 90. cause 链与错误传播纪律

- 包装公式：`new XxxException("action=charge orderId=123", cause)` ——
  消息是**结构化键值**（机器可 grep/聚合），cause 保原始栈；
  `initCause` 只能调一次，重复 = 异常。
- 跨线程传播真相：ExecutionException/CompletionException 包着原始异常
  —— **cause 里才有真栈**；顶层 catch 只 log 包装类不展开 = 全员看到
  二手栈（50 节 join/get 差异）。
- 不要包的东西：InterruptedException（恢复中断语义）、VirtualMachineError
  等 Error、以及"同语义低信息量包装"（RuntimeException(e) 这种零增量
  包装只加深栈）。
- 错误码与异常并存：机器可读 code 放异常字段（枚举），日志输出
  `code=X msg=Y` —— 别把 code 编码进 message 字符串（日志可读但
  监控不可聚合）。
- 敏感数据红线：密钥/Token/PII 不进 message（日志合规）—— 上下文用
  id 引用（"userId=..."而不是整个 user JSON）。
- 检查清单：每个自定义异常都有 cause 构造器；每个 catch-wrapping 点
  三问（加信息了吗？保 cause 了吗？能合并进上层翻译吗？）。

## 91. 日志纪律与结构化日志

- 占位符纪律：`log.info("id={} op=charge", id)` —— 关闭级别时不拼接；
  但**参数仍会求值**：昂贵对象/方法调用要 `isDebugEnabled` 守卫或
  log4j2 的 `atDebug().addArgument(supplier)` 惰性求值（73 节的 indy
  拼接陷阱同源）。
- 级别语义（可执行版）：ERROR=需要人立刻动 / WARN=自动恢复但要留痕 /
  INFO=业务里程碑（每次部署后读得懂）/ DEBUG=推理细节（默认关）；
  ERROR 泛滥 = 告警疲劳 = 真事故没人看。
- 结构化：JSON encoder（logstash-logback）+ traceId 进 MDC；
  **MDC 是 ThreadLocal 底座** —— 线程池要 TaskDecorator 拷贝恢复，
  虚拟线程/异步链路会断（52/9 节）—— 这是"日志没 traceId"事故的
  第一嫌疑。
- 异步 appender：Disruptor 无锁队列换吞吐 —— 崩溃前缓冲区丢日志
  （neverBlock=true 时尤甚）；审计日志必须同步或确认落盘语义。
- 事故模式：catch 块里 `log.error(e.getMessage())` —— 丢栈 + 丢级别；
  正确 `log.error("charge failed orderId={}", id, e)`（异常永远做最后参数）。
- 一行一事件：多行"伪结构化"（换行分隔的字段）破坏 JSON 解析；
  栈跟踪作为字段附上，别手工 `printStackTrace()` 到 stdout。

## 92. 断言、校验与 fail-fast

- `assert` 的真实身份：默认关闭（-ea 才启用）—— **生产不生效**；
  它只该用于"私有不变式文档"（内部状态自洽性），业务校验永远用
  显式 throw。
- 边界校验集合：`Objects.requireNonNull`（null 断言）、
  `Objects.checkIndex/checkFromToIndex`（范围）、Spring 的 Assert 系；
  消息带值（92 节与 84 节同一纪律：错误可行动）。
- 校验位置学：入口（构造器/静态工厂/反序列化）**一次**，内部信任传递；
  惰性结构的校验延迟到"真正使用时"是 API 设计选择，但错误会远离
  污染点 —— 混合方案：构造期校验"形状"，使用期校验"内容"。
- fail-fast 的代价账：早抛 = 栈近现场（修得快）；晚抛 = 错误传播后
  模糊（调试成本 ×10）；但对外 API 的 fail-fast 要配清晰错误码 ——
  调用方程序化处理，不是给人读散文。
- record 紧凑构造器是校验的家（76 节）：跨字段不变式（`from <= to`）
  在 canonical 入口强制 —— 外部反序列化也绕不过。
- 事故模式：防御式编程过度 —— 每层都校验同一件事 = 代码噪音 + 真
  校验被稀释；用架构约定（"数据进域已消毒"）+ code review 清单守住。

## 93. JFR 深度用法

- 常开基线：`-XX:StartFlightRecording=filename=rec.jfr,settings=default,
maxage=1h,maxsize=250m` —— 默认档开销 <1%；事件环形缓冲按块写盘，
磁盘预算进容量规划。动态控制用 `jcmd JFR.start/stop/dump`。
- 事件选读（按问题域）：CPU → `ExecutionSample`；锁 → `JavaMonitorEnter/
  JavaMonitorWait/ThreadPark`；分配 → `ObjectAllocationSample`（21+ 采样
  版按站点）；GC → `GarbageCollection/GCPhasePause`；泄漏 → 
  `OldObjectSample`（配 heap dump 前的初筛）；异常滥用 → 
  `JavaExceptionThrow` 计数；虚拟线程 → `VirtualThreadPinned/Start/End`。
- 分析工具链：`jfr print --events` 快查；`jfr view`（新版自带视图）；
  JMC 深挖；转火焰图（async-profiler 发行版自带转换脚本）。
- 自定义事件：`@Name/@Category` + 继承 `jdk.jfr.Event`（duration 型
  begin/end 或 instant 型单 commit）—— 业务关键路径埋点进 JFR =
  统一时间轴（GC/锁/业务对齐，98 节的核心手段）。
- 采样纪律：ExecutionSample 默认 10-20ms —— 更细要权衡自干扰；
  事件丢失（chunk 满丢弃）计数要盯，丢事件的结论不可信。
- 陷阱：dump 瞬间的 STW（轻）与磁盘 IO；`settings=profile` 开销
  数倍于 default —— 只在排查窗口开。

## 94. Profiler 选型与采样偏差

- async-profiler：asyncGetCallTrace 采样，无 safepoint 偏差；
  `-e cpu`（on-CPU）与 `-e wall`（含等待）按问题选择 —— "CPU 高"
  用 cpu，"慢"用 wall + 线程状态交叉。
- **safepoint 偏差**史：老一代 hprof 只在 safepoint 采样 —— 只采到
  "能停的帧"，长循环/JNI 帧系统性失真（hprof 已随 9 移除，但"采样
  不可信"的印象源于它）；JFR 与 async-profiler 都免疫。
- 偏差二连：**on-CPU 偏差**（把自旋/等待锁的忙等算成 CPU —— 火焰图
  里锁自旋看起来像热点）；**粗采样漏短函数**（提高频率注意开销）。
- JDK 25 JEP 509（实验）：CPU-Time Profiling 按线程 CPU 时记账 ——
  解决"采样把等待误报成 CPU"的根问题，关注转正进度。
- 三件套分工：CPU 火焰图（为什么忙）、分配火焰图（GC 压力源，37 节）、
  锁/等待分析（谁堵谁，99 节）—— 一次排查往往需要两件互相印证。
- 纪律：profile 结论必须能被 JMH 或计数器复验（112 节）—— 采样是
  假设生成器，不是证明。
## 95. 堆转储分析方法论

- 采集代价认知：dump = 全停顿 full GC + 序列化，几十 GB 堆是分钟级
  STW —— **先想清楚再扔**；常规路径：JFR `OldObjectSample` 先做泄漏
  初筛（常开、免 STW），确有泄漏再定点 dump。
- 采集手法：`jmap -dump:live` 会先触发一次 full GC（live 语义）——
  想保留濒死对象对照时用非 live 版；OOM 自动兜底：
  `-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/pv/dump/`
  （PV 容量 ≥ 1.5×Xmx；容器被 OOMKilled 抢先就白配，38 节）。
- MAT 工作流：直方图（类级 retained 对比）→ **dominator tree**（谁扣着
  谁）→ leak suspects 报告（先看建议，但别只看建议）→ path to GC
  roots 时**排除 weak/soft 引用**（排除前的"泄漏"大多是伪泄漏）。
- 双 dump 对比法：间隔 30-60 分钟两份 dump，直方图增量排序 —— 单点
  dump 只见"大"、增量才见"涨"；涨的才是泄漏，大可能只是缓存设计。
- 高频泄漏形态速查表：ThreadLocal 挂在池化线程上（52 节）；
  ClassLoader 持类链（96 节）；无界缓存/队列（108/111 节）；
  监听器未注销；Thread 对象本身滞留（unjoin 的任务）。
- 合规注意：dump 含全量用户数据（PII）—— 落盘加密、共享需脱敏、
  保留期写进制度。

## 96. Metaspace 与类加载器泄漏

- 结构：Metaspace 在 native 内存（不占堆、不受 Xmx 管），上限
  `-XX:MaxMetaspaceSize`（默认无限）+ `-XX:CompressedClassSpaceSize`
  （默认 1GB）；耗尽 = `OutOfMemoryError: Metaspace`，服务起不来。
- 泄漏源清单：反射接口代理（WeakCache 软持有，高压下照样涨）、
  CGLib/ByteBuddy 生成类、脚本引擎（Groovy 每脚本一批类）、lambda
  hidden class（生命周期绑类加载器）、war 反复重部署（容器 child
  loader 滞留：线程/驱动/TCCL/静态注册表里都有它的引用）。
- 表征与度量：pmap 看 RSS 涨段、JFR `ClassLoaderStatistics`（按
  loader 看类数）、heap dump 里找重复的同名类（两个 loader 各一份）。
- 修复模式：JDBC 驱动放容器 lib 而非 webapp（JDK 的 DriverManager
  deregistration 陷阱，101 节）；TCCL 用完复位；应用关闭钩子里注销
  SPI/MBean/线程池。
- 止血不是治愈：调大 MaxMetaspaceSize 只延后爆炸；重部署循环压测
  （自动 redeploy ×50 + Metaspace 曲线）才是验收标准。
- 事故模式：每次热部署 Metaspace +30MB 涨而不落 —— "内存泄漏在
  堆外"的排查起点就是本节。

## 97. CPU 飙高排查路径

- 第一步分诊（30 秒内）：`top` 看 us/sy 比例（sy 高 = 内核态/锁自旋/
  上下文切换），`jstat -gcutil` 看 GC 时间占比 —— **GC 抖到 80%+ 的
  "CPU 飙高"是分配事故不是代码死循环**（37 节）。
- 三步法（真算力问题）：`top -H -p` 拿最热线程 → nid 转 16 进制 →
  在 `jcmd Thread.print` 对线找线程名 → JFR ExecutionSample 30 秒
  火焰图定位方法级热点（94 节）。
- 经典元凶排序（经验序）：① GC 疯狂（分配率/堆不够）② 业务死循环
  bug（边界条件写反、contains 退化成 O(n) 链）③ 正则回溯（29 节）
  ④ 锁自旋 + 忙等重试风暴（54 节）⑤ JIT deopt 风暴（18 节）。
- 容器特有：cgroup CPU quota 被打满 → throttling（throttled_periods
  计数）—— 表现是"CPU 没满但延迟暴涨"，看 `/sys/fs/cgroup/.../cpu.stat`
  （38/98 节）。
- 保留排查能力：镜像里预装 jcmd/async-profiler/JDK 内工具 + 无害化
  权限 —— 事故窗口 10 分钟，装工具占 9 分钟就废了。
- 纪律：先采样再重启 —— 重启销毁现场后只能等下一次复现（114 节）。

## 98. 长尾延迟排查路径

- 心法：P99 是**事件叠加**，不是单一原因 —— GC 停顿、分配 stall（34 节）、
  锁等待、JIT deopt（18 节）、容器 throttling（38 节）、重试风暴、
  冷缓存/冷路径，多个毫秒级事件串起来才是尾。
- 测量基建（没有这个免谈）：HdrHistogram 记录全量分布（不是平均）；
  客户端视角计时（服务端计时看不到队列/网络）；**coordinated omission**
  修正 —— 负载生成器同步等待时的样本缺失会让 P99 好看一倍（Gil Tene）。
- 分解法：请求内分段 nanoTime 计时（入口/DB/远程/计算）→ 与 JFR
  时间轴对齐（GCPhasePause、MonitorEnter、ThreadPark、自定义 JFR 事件
  —— 93 节）；一段一段排除。
- 上游放大器排查：超时设置是否短于下游 P99（100ms 超时 vs 下游 P99
  120ms = 必然重试雪崩）；重试有没有 backoff + jitter（54 节活锁）。
- 治理优先级：降分配率（37）→ 消锁与池竞争（55）→ GC/停顿工程（31/34）
  → 容器配额审计（38）→ 最后才是换硬件/堆大小。
- 事故模式：每次发版后 P99 尖刺 30 分钟 —— JIT 预热 + 冷 CDS 未命中的
  叠加；灰度放量节奏要给预热留窗（18 节）。

## 99. 线程转储与锁分析

- 采集：`jcmd <pid> Thread.print -l`（含锁地址与 ownable synchronizers）；
  多次采样（5s×10 份）看**状态迁移**，单份转储只有统计意义；
  JSON 版 `jcmd Thread.dump_to_file -format=json` 含虚拟线程（103 节）。
- 状态语义纠偏：**RUNNABLE ≠ 在算** —— socket 读写阻塞在内核也显示
  RUNNABLE（看栈里的 socketRead0）；BLOCKED 才是真锁等（带 "waiting to
  lock <0x...> held by"）；WAITING (parking) 是 FJ/池的常态等待。
- 死锁检测：内置 "Found one Java-level deadlock" 只识别监视器环 ——
  ReentrantLock 环要看 ownable synchronizers 列表（locked ownable
  synchronizers vs waiting to lock）人工连图；JFR `JavaMonitorEnter`
  的等待时长分布补时间维。
- 线程数健康度：线程数曲线 + 每 thread 的 -Xss 占用（38 节 RSS 账本）；
  线程名是**诊断接口** —— 池命名纪律在 111 节。
- 与火焰图互补：转储答"谁在等谁"（图），ExecutionSample 答"谁在烧
  CPU"（热点）—— 排查时两份都要。
- 陷阱：转储本身有轻 STW；高频转储（>1/s）会扰动被测系统 —— 采样
  密度与结论可信度取平衡。

## 100. JVM 参数管理纪律

- flag 三类：product（随时可改）/ diagnostic（`-XX:+UnlockDiagnosticVMOptions`）
  / experimental（`-XX:+UnlockExperimentalVMOptions`）；不同版本类目会
  **漂移**（23 节的示例）—— 升级后脚本与启动参数要过一遍适配。
- 审计基线：`-XX:+PrintFlagsFinal` 出全量生效值（含 ergonomics 推导）；
  `jcmd VM.flags` 查运行态；**每次 JDK 升级 diff 一份**入版本库 ——
  默认值悄悄变是升级事故的大头（G1/ZGC 的默认演进都发生过）。
- 配置归位：flag 定义在基础设施代码（helm chart/java env 文件），
  **每个 flag 旁边写为什么**（链接压测报告/issue）—— 没有 why 的
  flag 没人敢删，三年后变成祖传屎山。
- 变更流程：调参 = PR + A/B 压测报告（31 节）+ 灰度 + 回滚预案；
  "网上抄来一套参数"是事故的标准开场白 —— 每个 flag 只为解决你
  实测的某个问题。
- 反模式清单：无测量先调参（37 节先测分配）；参数越多越专业（参数
  越多越难解释）；用 flag 掩盖应用层问题（无界队列/疯狂分配）。
- 特殊项：`-XX:+DisableExplicitGC` 要先审计 DirectByteBuffer 依赖
  （36 节）；`-Xss` 调小前先查最深栈（递归解析器）。

## 101. 类加载器与双亲委派

- 委派模型的目的：**防伪 + 唯一** —— bootstrap 先加载核心类，应用层
  无法用同名类冒充 java.lang.String；同一 loader+同名 = 同一 Class。
- 破坏点全是历史必需：SPI 需要 TCCL（JDBC/JNDI 的 bootstrap 接口要
  回调应用实现）；容器 child-first（隔离优先于唯一）；框架动态代理。
- 事故一号：**同名类双 loader** → `instanceof` 为 false、cast 抛
  ClassCastException、单例分裂成两份 —— 排查靠 `clazz.getClassLoader()`
  逐层比对；根源是依赖被两个 loader 各加载一份（102 节）。
- 事故二号：`Class.forName(name)` 与 `loader.loadClass(name)` 差在
  **是否初始化**（静态块时机）—— SPI 探测时初始化副作用（建连接、
  起线程）会提前引爆。
- JDBC 老陷阱：4 之前 `Class.forName("driver")` 强制初始化注册；
  4+ ServiceLoader 自动注册 —— 老 try 块无害但误导后人。
- hidden class（15+）：lambda 与动态实现的实现形态 —— 有 Class 对象
  但无法按名查找，dump/反射里"隐形"，别指望反射能枚举它们。

## 102. Jar Hell 与多版本 JAR

- 类遮蔽：classpath 扁平有序，同名类**先者胜** —— `NoSuchMethodError`
  通常意味着赢的那个是旧版本；`-verbose:class` 看加载来源，一步定性。
- fat jar/shading 三雷：relocation 不完整（半重命名的类互相引用旧名）；
  `META-INF/services` 不合并（ServiceLoader 静默丢实现，错误极隐蔽）；
  签名 jar 重打包 = SecurityException（先去签名）。
- **MR-JAR**（9+）：`META-INF/versions/N/...` 按运行版本选类 —— 规则：
  所有版本类必须 API 兼容（签名一致）；javac `--release` 校验；
  风险：静态分析/IDE/部分字节码库读错版本 —— 用 MR-JAR 的库要专门
  测试矩阵，应用别轻易自造。
- 治理工具：maven-enforcer（banned/redundant/upper-bound）、
  Gradle constraints/resolutionStrategy；`jdeps` 画依赖图；
  jlink 定制镜像（封闭 classpath 从根上消灭 Jar Hell，13 节）。
- 事故模式：升级 A 库带入了它依赖的 B 旧版，遮蔽了你显式声明的 B
  新版 —— "明明 pom 里是新版本" 是classpath 语义的经典错觉；
  dependency:tree + `verbose:class` 双确认。

## 103. 虚拟线程下的诊断差异

- 线程转储失焦：`Thread.print` 面对**百万虚拟线程不可用** —— 换
  `jcmd Thread.dump_to_file -format=json`（21+，含虚拟线程）+ JFR
  事件（VirtualThreadStart/End/Submit/Pinned）；命名纪律（6 节）
  是一切诊断的前提。
- 火焰图语义变化：CPU 采样看到的是 **carrier 栈** —— 虚拟线程的
  业务帧在 unmount 后不可见；"看到调度器很热"可能是正常 mount/
  unmount，先看 pinned 事件再下结论（7 节）。
- 死锁形态进化：synchronized 争用在 24+ 转为正常让出（不再死锁，
  变成公平排队）—— 但 **JNI native pin 的死锁仍真实**；锁等待分析
  要区分 BLOCKED（platform）与 pinned（virtual）两种"卡"。
- 泄漏新形态：未 join 的发散 fork → stack chunk 常驻堆 —— 表现是
  堆里大量 `Chunk`/continuation 对象；结构化收尾（try-with-resources
  executor，6 节）是根治。
- 上下文追踪：ThreadLocal/MDC 断链（52/91 节）—— traceId 用
  ScopedValue 重构（9 节）；日志上下文与 VT 兼容性列入升级清单。
- 容量口径：虚拟线程本身不是线程泄漏（百万 OK），**"并发未完成数"
  才是新指标** —— 信号量计数 + JFR Submit/End 差值做监控。

## 104. equals 不对称与 Set 事故

- 病灶解剖：`equals` 用 `instanceof` 且允许跨继承层级 → 父对象
  equals 子对象为 true、子对象 equals 父对象为 false → **HashSet 里
  同一逻辑元素出现两份**（先放的与后放的落不同桶/不同比较结果）。
- 治法二选一：值语义 → 用 record（自动对称，76 节）或 `getClass()`
  严格比较；要支持子类代数 → 组合替代继承，equals 基于内容组件。
- 幽灵元素：可变字段入 equals/hashCode + 元素入 Set 后改字段 →
  `contains(x)` 找不回、迭代却见得到（57 节同源，Set 侧最疼）——
  键类必须不可变，或键类型只含不变字段。
- BigDecimal 特例：equals 含 scale → `new BigDecimal("2.0")` 与
  `new BigDecimal("2.00")` 在 HashSet 分裂、在 TreeSet/compareTo 相等
  —— 金额键用 `stripTrailingZeros()` 归一或 minor-unit long（106 节）。
- JPA 实体特例：代理子类使 `getClass()` 比较 false、id 未生成时
  equals 结果随生命周期变化 —— 实体 equals 用业务键 + instanceof
  慎重组合，永远别用 Hibernate 代理没填的字段。
- 检测纪律：值类型单测三件套（HashSet 双放去重、HashMap 取回、
  equals 对称矩阵）+ EqualsVerifier 类库拉满严格性。

## 105. 时间与时区事故

- 建模主线：**存储/计算一律 UTC Instant**；显示层转 zone；**未来的
  墙钟事件**（预约/闹钟）存 ZonedDateTime 或"墙钟+zone id"—— DST
  规则会改（tzdata 更新让历史/未来墙钟移动），存 Instant 的"未来
  本地 9 点"会在 tzdata 更新后错位。
- tzdata 三份不同步：OS / JDK 内置 / 数据库 —— 升级 JDK 小版本会
  换内置 tzdata（老 8 用 TZUpdater 手工打）；跨语言服务对时差要
  在联调里专门验证一次。
- 时钟语义：`System.currentTimeMillis` 是墙钟（NTP 可回拨 —— 算
  间隔会出负数）；**`System.nanoTime` 是单调钟**（测耗时唯一正确，
  可为负、不可跨 JVM/关机比较）；`Instant.now()` 有分配成本，
  高频路径用 `System.nanoTime` 差值。
- 老三样死刑名单：`SimpleDateFormat`（非线程安全 —— 共享静态字段
  = 翻位/年份错乱，换 `DateTimeFormatter`，不可变线程安全）；
  `java.util.Date`（可变 + 无 zone 语义）；`Calendar`（set 翻译期
  陷阱）。遗留字段一律 `toInstant()` 迁出。
- 测试纪律：注入 `java.time.Clock`（`Clock.fixed`），别 mock 静态
  currentTimeMillis；跨时区单测跑一次 UTC+14/UTC-12 的极端 case。
- 事故模式：JPA 映射 `LocalDateTime` 存的是服务器本地时区而读取方
  按 UTC 解释 —— 契约上明确"数据库列存 UTC + Instant 映射"。

## 106. 金额精度与舍入事故

- 基线：double 禁用于钱（0.1 不可精确表示，累计漂移肉眼可见）；
  两个正解 —— `long minor unit`（分/厘，整链路）或 BigDecimal（边界
  与第三方对齐时）。`new BigDecimal(0.1)` 陷阱：先产生 double 误差
  再固化 —— 一律 `BigDecimal.valueOf` / 字符串构造。
- **equals 含 scale 事故**：`2.0` 与 `2.00` equals 为 false ——
  HashMap/HashSet 语义下"相等金额"分裂（104 节）；比较/排序用
  `compareTo`；计算后立即 `setScale(2, RoundingMode.HALF_EVEN)` 归一。
- 舍入模式：HALF_UP（商户向用户展示）vs HALF_EVEN（银行家舍入 ——
  财务/利息惯例，避免系统性偏移）vs DOWN（平台手续费向上取整的反向）；
  **舍入次数**：中途每步舍入 vs 末端一次舍入结果不同 —— 契约里写死。
- 分摊尾差：总额按比例拆分后合计 ≠ 总额 —— 最大余额法调整尾差
  （最后一条吸收），账务对账靠它过关；单测必须含三除不尽的 case。
- 序列化红线：JSON 反序列化金额走 double（Jackson 默认 float 场景）
  —— 配 `USE_BIG_DECIMAL_FOR_FLOATS` 或 DTO 用字符串/long 分；
  数据库 DECIMAL 精度/scale 与实体一致，静默截断 = 少收钱事故。
- 建模：`record Money(long minor, Currency currency)` + 工厂校验 ——
  货币混算在类型上不可能（76 节不变式）。

## 107. 静态可变状态之罪

- 罪状清单（每条都有血案）：测试不可并行（静态状态串场）→ 测试矩阵
  时间膨胀；热部署泄漏（static 持类加载器，96 节）；竞态唯一性
  （静态 map 的 check-then-put）；"全局配置"被多租户互相污染。
- 三重罪典型：`static SimpleDateFormat` —— 静态 + 可变 + 非线程安全
  的合体（105 节）；同类还有共享 `Random`（种子竞争）、共享
  `ThreadLocalRandom` 误用、静态 Jackson ObjectMapper 配置漂移。
- 单例正确形态：enum（防反射/序列化）或 final 字段 + 私有构造 +
  静态工厂（84 节）；懒加载用 holder idiom（41 节）；DCL 必须
  volatile 且值得怀疑 —— 多数场景饿汉更便宜。
- 上下文传递用静态 currentXxx：虚拟线程并发下必然竞态 —— 迁移到
  ScopedValue（9 节）或显式传参。
- 静态块的隐形雷：类加载期做重活（读配置/拉远程）—— 类加载失败
  的诊断远比构造器失败难；静态块只做纯计算初始化。
- 纪律：静态 = 常量 + 无状态函数；状态归注入的实例（108 节的
  Caffeine 也要注入实例，不是 static map）。

## 108. 过度缓存与 GC 压力

- 手写缓存四宗罪：无界（老年代塞满，32 节 Full GC）；键可变（57 节
  丢失）；soft/weak reference 兜底被 GC 心情支配（soft 在堆压力下的
  行为不可依赖 —— "能用 soft 就自动省内存"是老黄历，CMS 时代设计）；
  无度量（命中率盲飞）。
- 正解底座：Caffeine —— size 上限 + TTL/TTI + weigher（按条目权重
  而不是条数）+ recordStats（接 Micrometer）；淘汰策略（W-TinyLFU）
  在热点倾斜负载下命中率实测高于 LRU。
- 内存账本纪律：缓存 value 引用**大对象图**时，size=10000 可能就是
  半个 live set —— 权重器用"近似字节"（字段长度和）而非"1"；
  缓存占堆预算要有显式百分比（如 ≤10% Xmx）并进监控。
- JIT 面：巨型缓存让调用点 megamorphic（17 节）—— "加缓存反而更慢"
  的案例多源于此 + 序列化 key 的成本。
- 事故模式：缓存击穿时的重建风暴（miss 洪峰打穿下游）→ 加载
  `AsyncCache`/单飞（computeIfAbsent 语义，48 节）+ 熔断；
  缓存与源不一致窗口 = 业务语义问题，TTL 不是一致性方案。
- 优先级反问：先消重复计算（预计算/位图）再谈缓存 —— 很多"需要
  缓存"的热点在 37 节分配治理后已经不热了。

## 109. 反射与 MethodHandles 成本

- 现状（18+ JEP 416）：核心反射用 MethodHandles 重实现 —— 旧时代的
  "15 次调用后生成 accessor 字节码"（inflation）机制已死；稳态性能
  相当于当年 best case，但仍有可见性检查、装箱与参数数组的固定成本。
- `Method.invoke` 热路径成本：每次 Object[] 装参 + 异常包装 ——
  高频调用（序列化/ORM 每字段）用 MethodHandle 或代码生成
  （编译期生成 accessor，86 节）替代。
- MethodHandle 纪律：**static final 字段持有** 正确类型的 MH，JIT
  可将其内联到接近直接调用；构造/asType/参数收集是纯开销 ——
  常驻复用；MH 的类型匹配在链接期，运行期零检查（这是它快的原因）。
- 强封装现状（11 节）：`setAccessible(true)` 打 JDK 内部默认拒绝 ——
  `--add-opens` 精确到包；框架升级潮就是因为这个收紧。
- VarHandle 替代 Unsafe 字段操作：getAcquire/setRelease 语义分级
  （42 节），比 volatile 细粒度；atomic 家族覆盖 CAS/位操作。
- 事故模式：启动期反射扫类 + 缓存 MetaData（框架常态）—— 缓存
  key 含 ClassLoader 引用 = 96 节泄漏；软键（Class 对象 weak 化）
  或按包预热是标准解。

## 110. 序列化与 Jackson 陷阱

- 立场：**新代码禁用 Java 原生序列化**（gadget chain 攻击面 + 跨版本
  脆弱）；必须保留的历史接口用 JEP 290 过滤器（`ObjectInputFilter`
  白名单）+ 17 的 context-specific filter（JEP 415）；record 序列化
  走 canonical 构造器，攻击面结构性缩小（76 节）。
- Jackson record 适配：需 `-parameters` 编译参数或 `@JsonProperty`
  标注（老版本）；单字段 record 的构造歧义用 `@JsonCreator` 指定；
  不可变集合字段（List.of）反序列化需 creator 适配（Jackson 2.12+ 有
  部分支持，验证你的版本）。
- 默认值陷阱三连：`FAIL_ON_UNKNOWN_PROPERTIES` 默认 true —— 新旧
  版本字段漂移 = 反序列化炸（契约测试）；日期默认时间戳表示
  （`WRITE_DATES_AS_TIMESTAMPS=false`）；浮点金额 →
  `USE_BIG_DECIMAL_FOR_FLOATS`（106 节）。
- 多态：`@JsonTypeInfo` + 默认 typing（基于类名）= 反序列化 gadget
  入口 —— 必须白名单 subtype；sealed 层次（77 节）配合显式 subtype id。
- 大 payload：TreeModel（JsonNode）全内存 = 分配洪峰（37 节）——
  流式 `JsonParser/JsonGenerator` + token 循环；HTTP 层大 JSON 同理。
- 纪律：DTO 与实体分离（76 节）；序列化契约（字段集/精度/时区）有
  golden-file 测试锁版本（105/106 节联动）。

## 111. 线程池配置事故复盘

- 两大"快捷方法"皆毒：`newFixedThreadPool` = 无界
  LinkedBlockingQueue —— 任务静默堆积直到 OOM（先表现为上游超时，
  队列还在涨）；`newCachedThreadPool` = 线程无界 —— 万级线程
  （1MB 栈 × N + 调度税）。
- 标准构造模板（一行都不能省）：
```java
new ThreadPoolExecutor(n, n, 60, SECONDS,
    new ArrayBlockingQueue<>(1000),                  // 有界队列
    namedFactory("pay-worker", uncaughtLog),         // 命名 + 记录未捕获异常
    new ThreadPoolExecutor.AbortPolicy());           // 拒绝显式化
```
- 拒绝策略语义表：Abort（默认，抛 REE 配监控）/ CallerRuns（背压 ——
  会阻塞提交线程，**netty event loop 上是灾难**）/ Discard（静默丢 =
  数据事故）/ DiscardOldest（滚最老的 —— FIFI队列语义破坏）；
  自定义 = 日志 + 指标 + 降级动作。
- **submit 吞异常**：异常进 Future 没人 get = 消失；修法：execute
  （异常走 uncaughtHandler）或包装 Future 强制消费 —— 这是史上
  最高频的"任务静默失败"。
- 优雅停机：`shutdown()` → `awaitTermination(超时)` → `shutdownNow()`
  （中断语义）；任务要**响应中断**（阻塞队列自动响应，socket IO 不
  响应 —— 需要关闭资源触发）；Spring 侧配 SmartLifecycle。
- 容量纪律：IO 池大小 ≈ 并发请求数（受下游连接数约束）；CPU 池 ≈
  核数；虚拟线程时代 IO 池直接换 `newVirtualThreadPerTaskExecutor`
  （6 节）+ 信号量限界 —— 池作为"限界工具"的角色让位给信号量。

## 112. JMH 微基准纪律

- 骨架四件套缺一不可：`@Fork`（进程隔离 —— 剖析/JIT 状态不跨基准
  污染）、`@Warmup`、`@State(Scope.Thread)`（每线程独立状态；并发
  争用测试才用 Group/Benchmark）、`Blackhole.consume`（防死代码消除
  与常量折叠 —— 没消费的结果会被 JIT 整段删除）。
- 经典假结果模式：基准里对象被**标量替换**（15 节）→ 生产里有分配
  而基准显示零分配；无争用锁被**锁消除**；循环计数被提升（循环体
  提出）—— 都会让"基准胜出"在生产中不成立。
- 方法轮（Iteration/RunToRun）误差大于 3 个标准差的不下结论；对比
  必须**同机同 JDK 同 flags**（容器里再加同 quota）。
- 宏观裁决：微基准只用于方向验证（"A 比 B 快"），吞吐/延迟结论以
  全链路压测为准 —— 被测代码在生产里的内联上下文完全不同（16 节）。
- 配套 profiler：`-prof gc`（分配率）、`-prof perfasm`（热点指令）、
  `-prof jfr`（对齐生产观测）—— JMH 数字 + profile 证据链才完整。
- 纪律：基准代码进仓库随业务代码评审（不然过期成废纸）；每次
  "优化"PR 附基准差异数据（100 节的调参 PR 同理）。

## 113. 并发代码测试纪律

- 可靠性分层：常规并发测试（高并发 + 中断注入 + 超时断言）→
  **JCStress**（理论模型检查，测 JMM 级正确性）→ 长时 soak（夜跑）。
  单元测试无法证明并发正确，只能证明"在这个交错下没错"。
- 三个替代反模式：`Thread.sleep` 同步等待 → **Awaitility/轮询断言 +
  超时上限**；"跑 1000 次没死锁" → JUnit 超时 + `jcmd` 死锁检测钩子
  （测试后自检 "Finding one Java-level deadlock"）；"断言不抛" →
  失败样本收集（不同线程的异常都要归拢）。
- 对拍法（必用）：同代码串行跑一遍、并行跑一遍，结果必须一致
  （顺序无关的等价断言）；并发度阶梯（1/2/8/32）暴露伪共享与
  争用劣化（53 节）。
- 上下文清理断言：ThreadLocal/ScopedValue 用例结束后必须"读回空值"
  —— 池化线程复用让脏值测试极容易写却常被漏（52 节）。
- 压测口径：夜跑高并发 + PR 低并发的双层预算；每次并发相关 PR
  至少一次 30 分钟 soak；随机 seed 固定 + 失败时打印 seed（可复现）。
- 覆盖率纪律：并发测试不看行覆盖（覆盖了不代表交错到了）—— 看
  状态迁移覆盖与同步点排列（JMM 测试方法论，JCStress 文档）。

## 114. 事故复盘 Runbook

- 黄金四问（每次复盘按序回答）：① 变了什么（发布/配置/流量/数据）；
  ② 证据链是什么（JFR/dump/日志/指标按时间轴对齐）；③ 放大器是什么
  （重试/超时级联/池耗尽 —— 单点故障不该变成全站故障，中间有放大）；
  ④ 为什么没更早发现（告警阈值/监控盲区）。
- 现场保护清单（平时就要备好）：OOM dump 落 PV（95 节）；jcmd/
  async-profiler/JFR 工具与权限预置（97 节）；JFR 常开（93 节）；
  日志带 traceId 且不被异步队列丢（91 节）；GC 日志滚动留存（36 节）。
- 时间线重建：指标（GC/锁/CPU/流量）+ JFR 事件流 + 访问日志三方
  对齐 —— 别靠记忆复盘，记忆会按叙事重排。
- 经验根因分布（用于故障树快速排查，非统计学真理）：无界资源
  （队列/缓存/重试/集合）> 配置漂移（flag/连接池/超时）> 版本升级
  （JDK/依赖字节码）> 竞态/锁顺序 > 纯代码 bug。
- 复盘产出三件（少一件是敷衍）：一条新监控/告警（防同类）、一个
  自动化检查（CI/启动自检，把人从流程里拿掉）、一页本知识库式总结
  （可检索、可复用）。
- 升级为制度：每季度把"事故反模式"回灌 code review 清单与压测
  用例库 —— 复盘的终点是让这类事故在代码进主干前就被拦下。
