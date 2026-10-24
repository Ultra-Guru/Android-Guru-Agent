<!-- kb: id=ruby | role=builtin_coding_ruby | title=Ruby 专家知识库 | version=1 -->
# Ruby 专家知识库

## 必看内容

- **YJIT 是默认答案，但要带观测上线**：3.4 的 YJIT 相对解释器在官方跟踪基准上约 ~92% 提升、比 3.3.6 再快 5-7%；Rails 7.2+ 生产环境检测到 Ruby 3.3+ 自动开启（config.yjit）。纪律：显式钉死、--yjit-mem-limit 圈内存、RubyVM::YJIT.runtime_stats 看编译量与 side exit；puma 之外的 sidekiq/rake 进程最容易漏带参数（5-6 节）。
- **冷字符串是 3.4 最大的行为面变化**：没有 frozen_string_literal 注释的文件里，字面量变异会发弃用告警（chilled strings）——而告警默认不显示，必须 -W:deprecated 才看得见；未来某版将转为冻结甚至报错。先在 3.4 跑全量测试建告警基线，再按文件排期；连带 Symbol#to_s 已冻结，`sym.to_s << "x"` 直接 FrozenError（2-3、19 节）。
- **GVL 的现实**：线程只买到 IO 并发（等待重叠），CPU 并行只有三条路——Puma 进程、Ractor、外部服务；C 扩展不放行 GVL 等于全进程所有线程冻结，升级驱动前先审计它是否调用 rb_thread_call_without_gvl（45-46 节）。
- **Ractor 仍 experimental**：3.5 引入 Ractor::Port 收敛通信 API，但非主 Ractor 仍不能改类、Rails/AR 整体不可用；只有"单进程内纯数据 CPU 并行"才值得评估，其余一律用进程模型（53-56 节）。
- **Timeout 用错层会撕裂状态**：Timeout.timeout 靠伴随线程 Thread#raise，可以插进 ensure 的两步之间——半关的 IO、撕开的事务。铁律：超时设在驱动层（statement_timeout/read_timeout/queue pop 超时），Timeout 只做进程级看门兜底（51 节）。
- **rescue Exception 吞 SIGTERM = 部署卡死**：SignalException 被 rescue Exception 截住后，Puma 优雅停机、Solid Queue 停机全部失效，只能等超时强杀。应用代码 rescue 永远限定 StandardError 系；看门线程全捕后必须 re-raise（50、52、83 节）。
- **RSS 涨 ≠ 泄漏**：glibc 多 arena 碎片最常冒充泄漏；jemalloc 是 Ruby 内存第一杠杆——先 LD_PRELOAD jemalloc（常见 -20~30% RSS），再谈 RUBY_GC_* 环境变量；分辨顺序见分层手册（16、91 节）。
- **N+1 的 CI 级解是 strict_loading**：按模型开 strict_loading_by_default 分域推进，n_plus_one_only 降门槛防误伤；存量按 includes/preload/eager_load 语义表消化，includes+where 的静默切换是老账不清的根源（84-85 节）。
- **Rails 8 Solid 三件套改变运维口径**：job/cache/cable 落到数据库，省掉 Redis 但多了"业务库的兄弟 schema"——独立 schema、读库分流、行数水位巡检三件事必须做，否则大表反噬主库（71-75 节）。
- **Zeitwerk 差异债**：initializer 里引用 app 常量，dev 是惰性加载、生产是 eager——行为分叉的 bug 只在一边显形。纪律：initializer 只碰 lib/常量，app 常量的接线放 config.to_prepare（幂等，每次重载都跑）（87-88 节）。
- **动态执行就是攻击面**：字符串 eval、带用户输入的 send/const_get、Marshal.load、YAML.unsafe_load——五件套出现任意一处即 RCE 面；反射一律走白名单映射表（HASH.fetch），Psych 4 起 YAML.load 已默认安全化、要显式 unsafe_load 才恢复旧行为（37-38、105-106 节）。
- **度量三件套先于一切优化**：GC.stat 的 total_allocated_objects 差值（分配率）、memory_profiler（归因）、benchmark-ips + rbspy（分层验证）。没有数字的优化都是猜；微基准只给方向，生产结论必须用取样数据回证（15、21、59-60 节）。

## 快速索引

| 想找什么 | 去哪一节 |
| --- | --- |
| 3.4 新特性对生产的影响 | 1. Ruby 3.4 生产基线：it、Prism 与冷字符串 |
| 冷字符串告警怎么治理 | 2. 冷字符串迁移工程：从告警到冻结的节奏 |
| frozen literal 收益与雷区 | 3. frozen string literal 的真实收益与踩雷点 |
| 3.5 与下一代版本变化 | 4. Ruby 3.5 与下一代：Ractor::Port、内建 Set 与 nil splat |
| 要不要开 YJIT | 5. YJIT 采用决策：数据、成本与开关纪律 |
| YJIT 参数与取证 | 6. YJIT 调优与取证：exec-size、mem-limit 与 stats |
| ZJIT 现在什么状态 | 7. ZJIT 与 JIT 前沿：预览期预期管理 |
| 可替换 GC 与 MMTk | 8. Modular GC 与 MMTk：可替换 GC 的意义 |
| JRuby/TruffleRuby 选型 | 9. 运行时矩阵：CRuby、JRuby 与 TruffleRuby 选型 |
| 版本特性检测怎么做 | 10. 运行时自省与防御式特性开关 |
| 3.3→3.5 升级坑 | 11. 3.3→3.5 升级陷阱清单 |
| localhost 连不上（IPv6 优先） | 12. Happy Eyeballs 2：双栈解析的隐性事故 |
| GC 内部机制 | 13. Ruby GC 内幕：分代、写屏障与增量标记 |
| RUBY_GC_* 环境变量调优 | 14. RUBY_GC_* 调优矩阵 |
| GC.stat 与堆压实 | 15. GC.stat 诊断与 GC.compact 压实决策 |
| RSS 虚高与 jemalloc | 16. glibc malloc 碎片与 jemalloc 部署实战 |
| 堆槽与页模型 | 17. 堆槽增长模型：RVALUE 与页分配 |
| 字符串内存优化 | 18. 字符串内存经济学与驻留 |
| Symbol#to_s 冻结炸了 | 19. Symbol#to_s 冻结的连锁反应 |
| 热路径减分配手法 | 20. 热路径零分配纪律 |
| memory_profiler 用法 | 21. memory_profiler 热点归因工作流 |
| derailed 内存剖析 | 22. derailed_benchmarks 与内存膨胀复盘 |
| 堆 dump 找泄漏 | 23. ObjectSpace 堆取证与泄漏误判 |
| 方法查找顺序 | 24. 方法查找路径解剖：singleton、prepend 与 include 全序 |
| 方法缓存失效 | 25. 方法缓存失效模型与全局代价 |
| define_method 与 def 取舍 | 26. define_method 与 def 的性能内存账本 |
| singleton class 成本 | 27. singleton class 激活成本与 def self 的隐性差异 |
| Struct/Data/OpenStruct | 28. Struct、Data 与 OpenStruct 生产选型 |
| Data 类正确用法 | 29. Data 类深用：不可变值对象与 with |
| refinements 激活规则 | 30. refinements 激活语义：词法作用域深水 |
| refinements 盲区 | 31. refinements 盲区：send、respond_to? 与 eval |
| 常量查找与污染 | 32. 常量查找路径与 Object 污染 |
| 钩子写 DSL 骨架 | 33. inherited、included 与 extended：DSL 骨架钩子 |
| 元代码可维护性 | 34. 面向工具的对象模型：让 IDE 与 grep 看得见 |
| method_missing 契约 | 35. method_missing 契约：respond_to_missing? 与工具缺口 |
| miss 固化提速 | 36. method_missing 缓存化：把 miss 固化成方法 |
| eval 风险与替代 | 37. eval 家族风险与替代路径 |
| send 白名单纪律 | 38. send 越权面与 public_send 纪律 |
| 类宏 DSL 模板 | 39. 类宏 DSL 设计：配置面的工程化 |
| DSL 魔法预算 | 40. DSL 反模式：魔法浓度与可调试性预算 |
| 闭包把内存钉住了 | 41. define_method 闭包泄漏与 binding 生命周期 |
| 代码生成时机 | 42. 运行时生成与代码生成的时机选择 |
| 猴子补丁纪律 | 43. 猴子补丁纪律与 prepend 包装器 |
| Prism AST 迁移 | 44. Prism 工具链：AST 处理的正式 API |
| GVL 放行点 | 45. GVL 现实：放行点、时间片与 C 扩展责任 |
| 线程什么时候有用 | 46. GVL 下多线程收益模型：IO 与 CPU 分野 |
| Mutex 死锁与重入 | 47. Mutex 与 Monitor 深水：重入、死锁与自愈 |
| Queue 关闭与停机 | 48. Queue 与 SizedQueue：关闭语义与停机协议 |
| 队列背压设计 | 49. 无界队列与饥饿：背压设计 |
| 线程取消协议 | 50. Thread#kill 的危险与取消协议设计 |
| Timeout 安全性 | 51. Timeout 陷阱：Thread#raise 与 ensure 原子性 |
| 线程静默死亡 | 52. 线程静默死亡与 report_on_exception 看门 |
| Ractor 现状边界 | 53. Ractor 现状：experimental 的真实边界 |
| Ractor 隔离与拷贝 | 54. Ractor 隔离模型：可共享性与深拷贝代价 |
| Ractor::Port | 55. Ractor::Port：新一代通信原语 |
| Ractor 上不上生产 | 56. Ractor 生产架构决策：值不值得上 |
| Fiber 栈与泄漏 | 57. Fiber 深水：栈、泄漏与 blocking 检测 |
| Fiber scheduler 与 async | 58. Fiber scheduler 协议与 async/Falcon 架构取舍 |
| 微基准可信度 | 59. 微基准纪律：benchmark-ips 与失真源 |
| 生产性能取样 | 60. 生产取样：rbspy、rbtrace 与 sigdump 分工 |
| 方法调用开销 | 61. 调用开销模型与内联边界 |
| 字符串拼接成本 | 62. 字符串构建成本阶梯 |
| 正则回溯灾难 | 63. 正则深水：回溯灾难与 ReDoS |
| Regexp.timeout | 64. Regexp.timeout 与正则预算 |
| Set 与容器选型 | 65. 容器选型：内建 Set 与复杂度账本 |
| JSON/Oj 序列化 | 66. 序列化成本：JSON、Oj 与 MessagePack |
| IO 与 fsync 成本 | 67. IO 批处理与 fsync 成本 |
| YJIT 友好代码写法 | 68. 与 YJIT 协作写代码：分支形状与 bailout |
| C 扩展边界成本 | 69. C 扩展与 FFI 的边界成本 |
| 性能回归门禁 | 70. 性能预算与 CI 回归门禁 |
| Rails 8 生产基线 | 71. Rails 8 生产基线：Solid 三件套的架构意义 |
| Solid Queue 内幕 | 72. Solid Queue 内幕：轮询、supervisor 与进程拓扑 |
| Solid Queue 运维 | 73. Solid Queue 运维：独立 schema、堵塞与监控 |
| Solid Cache 内幕 | 74. Solid Cache 内幕：LRU 删除与键存储 |
| Solid Cable 形态 | 75. Solid Cable 与 ActionCable 生产形态 |
| SQLite 生产化 | 76. SQLite 生产姿态：PRAGMA 与 Rails 8 默认 |
| Kamal 部署 | 77. Kamal 2 部署模型：零停机与回滚 |
| Propshaft 管线 | 78. Propshaft 与资产指纹管线 |
| ActionText 与 N+1 | 79. ActionText 内幕：富文本存储与 N+1 联动 |
| Packwerk 模块边界 | 80. Packwerk 与模块化单体：边界契约 |
| Puma 参数决策 | 81. Puma 调优决策树：workers、threads 与 preload |
| Puma 线程堵塞 | 82. Puma 线程堵塞诊断与熔断 |
| 优雅停机与 rescue 惨案 | 83. 优雅停机全链路：信号与 rescue Exception 惨案 |
| N+1 强制方案 | 84. N+1 尸检：strict_loading 强制与修复路径 |
| includes 三兄弟语义 | 85. preload、eager_load 与 includes 语义表 |
| 缓存键与惊群 | 86. 缓存键策略与 Rails.cache 惊群控制 |
| Zeitwerk initializer 坑 | 87. Zeitwerk 陷阱：initializer 引用与 to_prepare 纪律 |
| dev/prod 加载差异 | 88. dev 惰性加载与生产 eager load 的差异债 |
| 启动慢怎么治 | 89. 启动时间工程：bootsnap 内幕与 precompile |
| worker 内存治理 | 90. Puma worker 内存：COW 衰减与 RSS 治理 |
| 内存膨胀诊断手册 | 91. 内存膨胀分层诊断手册 |
| 挂死诊断工具 | 92. 生产调试工具箱：sigdump、rbtrace 与 gdb |
| 作业积压复盘 | 93. 后台作业堵塞与积压复盘 |
| RSpec let 与泄漏 | 94. RSpec 深水：let 延迟与共享状态泄漏 |
| 事务测试边界 | 95. transactional tests 边界：线程、Fiber 与外部副作用 |
| fixtures vs factories | 96. fixtures 与 factories 决策矩阵 |
| 双例验证与接口漂移 | 97. verify doubles 与接口漂移防护 |
| 系统测试治理 | 98. 系统测试：并行化与 flaky 治理 |
| 测试提速与随机化 | 99. 测试性能债：启动、随机顺序与 ratchet |
| 异常树设计 | 100. 应用异常树设计与 wrap 规范 |
| retry 与重试预算 | 101. retry 语义与重试预算：抖动与幂等 |
| ensure 的坑 | 102. ensure 深水：return 覆盖与异常替换 |
| 异常链取证 | 103. Exception#cause 与异常链取证 |
| 静默失败审计 | 104. rescue 粒度与静默失败反模式 |
| YAML/Marshal RCE | 105. YAML 与 Marshal 反序列化：RCE 路径 |
| send 与 const_get 注入 | 106. send 与 const_get 注入面白名单 |
| mass assignment 面 | 107. mass assignment：strong params 之外 |
| SQL 拼接与 ReDoS | 108. SQL 拼接与 ReDoS 输入面收口 |
| 反模式清单 | 109. Ruby 反模式清单：memoize nil 与 rescue nil |
| p95 事故解剖 | 110. p95 飙升事故解剖流程 |
| OOM 事故解剖 | 111. OOM 与 RSS 爬升事故解剖 |
| 生产复盘三例 | 112. Rails 生产事故复盘三例 |
## 1. Ruby 3.4 生产基线：it、Prism 与冷字符串

- 3.4（2024-12-25 发布）四件大事：`it` 块参数引用、Prism 成为默认解析器、无魔法注释文件进入"冷字符串"过渡（2 节）、socket 引入 Happy Eyeballs 2 双栈连接（12 节）。每一次都不是语法糖，而是行为面变化。
- `it` 的规则：块**未声明任何参数**且体内引用 `it` 时，`it` = 第一个 yield 值。声明了参数、或存在同名局部变量/方法，`it` 回退为普通名字——同一个词在不同文件里语义不同。
- Prism 影响：语法错误信息更准（带位置），但所有基于 Ripper / RubyVM::AbstractSyntaxTree 的自研 lint、统计工具进入弃用轨道（44 节）；错误口径变了，CI 里的 grep 断言可能失效。

```ruby
data = { id: 1 }
[data].each { puts it }           # it => { id: 1 }（3.4）
[[1, 2]].each { p it }            # it => [1, 2]：单 yield 的 pair 数组
value = 5
[data].each { puts it }           # 若块外有 it 局部量则回退为普通变量
```

- 坑：老代码把 `it` 当普通局部量在块内赋值使用——3.4 下静默变成"引用块参数"，写出 `nil` 再引爆在远处；升级前 grep 块内的 `it` 赋值。
- 升级纪律：3.4 当"告警收集版"跑一个周期（测试 + CI 开 -W:deprecated），把冷字符串告警量、Prism 差异一次摸清，再冲 3.5。

## 2. 冷字符串迁移工程：从告警到冻结的节奏

- 机制：没有 frozen_string_literal 魔法注释的文件里，字符串**字面量**是 chilled——仍可变，但每次变异发弃用告警；插值字符串（`"#{x}"`）不 chilled。运行期旗标 `--disable-frozen-string-literal` 可全关告警，`--enable=frozen-string-literal` 可全冻结。
- 最大坑：告警属于 deprecated 类别，**默认不显示**——不加 `-W:deprecated`（或 Warning[:deprecated] = true）你根本看不见迁移压力，误以为"没问题"。
- 迁移节奏（生产打法）：①CI 全量开 -W:deprecated 建"告警基线"；②按文件量排序，先冻自己的 app/ 与 spec/，gem 的问题留给上游（升级 gem 版本解决）；③每冻一个文件跑该文件相关测试；④告警清零的文件加魔法注释，从此进"冻结语义"。

```ruby
# 无魔法注释的文件（3.4 行为）
s = "hello"
s << "!"    # 仍可变，但发弃用告警（chilled）
s = +"hello"  # +str：显式可变副本，无告警
s.frozen?  # => false：chilled 不是已冻结，只是"变异会喊"
```

- 反模式：为了消告警无脑 `str.dup`——在热路径引入分配；正确顺序是先让"确实不需要可变"的文件冻结，剩下的才用 +str/dup 显式化。
- 注意 `.dup` 与 `+str` 都复制内容但 `+str` 幂等（已是可变串则原样返回），热路径优先 `+str`。

## 3. frozen string literal 的真实收益与踩雷点

- 收益是真的：带魔法注释后，字面量进入 fstring 表全进程去重——AR 属性名、事件名、日志 tag 这类"到处出现的短字符串"从 N 份变 1 份；fstring 上缓存 hash 码，做 Hash 键与比较更快。常驻内存大应用常见数个百分点的直接下降。
- 扫雷清单：冻结前 grep 作用于字面量的变异惯用法——`<<`、`sub!`、`gsub!`、`strip!`、`tr!`、`capitalize!`、`next!`；还有把字面量当"可变返回值"的 `def tag = @name`（若调用方 `tag << "x"` 会炸）。
- 魔法注释是**文件级**且必须首行（`# frozen_string_literal: true`，或 `# -*- frozen-string-literal: true -*-` emacs 变体）；不能按方法/块局部开。RuboCop 的 Style/FrozenStringLiteralComment + 自动修正做存量。

```ruby
# frozen_string_literal: true
TAG = "payments"          # 驻留：进程唯一副本
def cache_key = "v2:#{TAG}"  # 插值结果仍是新建字符串（不冻结也不驻留）
def tag = +"payments"     # 确需可变返回值：+str 显式化
```

- 坑：`+=` 与 `<<` 不再等价——`+=` 永远新建（安全但分配），`<<` 原地变异（冻结下直接 FrozenError）。审计时把两者分开看。
- 坑：冻结后 FrozenError 可能从**依赖深处**抛出（gem 内部期望可变字符串），报错栈不在你改的文件——回溯到你的调用点再判断。
- 结算法：新文件一律带注释（生成器模板改掉），存量按告警基线消化。

## 4. Ruby 3.5 与下一代：Ractor::Port、内建 Set 与 nil splat

- 3.5 落地项（升级 changelog 必读）：`Ractor::Port` 作为 Ractor 间同步通信的新原语（55 节，旧 receive/yield 路线进入让位轨道）；Set 从 stdlib autoload 变为 C 重写的真核心类（65 节）；`**nil` 的语义简化为"不传任何关键字参数"——调用侧与定义侧行为收敛，把 `**nil` 当"显式拒绝 kwargs 护栏"用的老签名要复核；`String#tr` 接受 Hash 做多字符替换；Unicode 升至 15.1。
- 4.0 预览期信号（只观察、不进生产规划）：ZJIT 作为下一代实验 JIT（7 节）、Ruby Box 实验性隔离层——API 与开关名都可能再动，架构决策一律不引用预览行为。
- 升级影响面排序：冷字符串告警（2 节）> Ractor API 迁移 > `**nil` 语义 > 其余增项。

```ruby
opts = nil
call(**opts)   # 3.5：完全等价 call()；旧版本语义更微妙
call(**nil)    # 同上：显式写法
Set.new([1, 2])  # 3.5：核心类，无需 require "set"（require 仍兼容）
```

- 坑：依赖里若有 `def call(**nil)` 这类"签名护栏"，3.5 起护栏语义变化——跑测试矩阵（3.4 + 3.5）双轨一周期。
- 兼容策略：跨版本库用 `defined?(Ractor::Port)` 探测（10 节），不要比较版本字符串。

## 5. YJIT 采用决策：数据、成本与开关纪律

- 数据（Shopify yjit-metrics 2025-01）：YJIT 3.4 相对解释器在跟踪的 headline 基准上**约 ~92% 更快**，比 YJIT 3.3.6 再快 5-7%，且 3.4 更省代码内存。自 3.2 起 production-ready（Shopify 全量跑了几个版本，railsbench 当年就有 ~38% 档位）。
- Rails 7.2+ 已替你做决定：生产环境检测 Ruby 3.3+ 自动启用（config.yjit = true）。你要做的是把它**钉死并显式化**——配置漂移时别让 JIT 悄悄消失。
- 成本模型：机器码占内存（几十 MiB 量级随代码规模）、编译期 CPU 摊销、统计开启有额外开销。收益集中在"热的 Ruby 层"——纯 C 调用占比极高的进程（如只做 pg 拷贝流的 worker）收益小。

```ruby
# 开关三件：环境变量 / 启动旗标 / Rails 配置，择一显式化
# RUBY_YJIT_ENABLE=1（写入部署层，systemd/k8s env）
# ruby --yjit bin/rails server
if defined?(RubyVM::YJIT)
  Rails.logger.info("yjit=#{RubyVM::YJIT.enabled?}")
end
```

- 覆盖面纪律：YJIT 参数要跟**所有长驻进程**——puma 之外的 solid queue supervisor、sidekiq、任何 rake 常驻任务；漏一个进程就有一个"慢进程之谜"在等你。
- 反预期：冷代码占比高的小脚本/CLI 可能负收益（编译税没攒回），这类进程明确不开，别一刀切。

## 6. YJIT 调优与取证：exec-size、mem-limit 与 stats

- 旗标矩阵：`--yjit-exec-size`（代码区 MiB，给足别抠）、`--yjit-mem-limit`（YJIT 总内存上限 MiB，撞限后**停止编译新方法但存量继续跑**，不会崩但会退化）、`--yjit-cold-threshold`（编译门槛，默认 30 次调用——热代码才编译）、`--yjit-code-gc`（允许回收编译产物）、`--yjit-log`（记录编译/反编译事件）。
- runtime_stats 三看：`compiled_iseq_count`（编译覆盖度）、`side_exit_count` 与 invalidation（反优化信号）、`inline/outlined_code_size`（代码体积趋势）。stats 需要 stats 构建或 enable_stats!（有开销，临时开）。

```ruby
RubyVM::YJIT.enable_stats! if RubyVM::YJIT.respond_to?(:enable_stats!)
s = RubyVM::YJIT.runtime_stats
s.slice(:compiled_iseq_count, :side_exit_count, :invalidation_count,
        :inline_code_size, :outlined_code_size)
```

- mem-limit 语义要吃透：设太低 → 热方法编不进去 → 退化成解释器却仍付编译税；先观测无限制下的 yjit 内存水位，再设上限留余量。
- 取证路径：side exit 集中的方法就是"YJIT 不友好代码"清单——对照 68 节改分支形状，而不是调大内存硬扛。
- A/B 纪律：`--yjit-disable`（保留构建但关 JIT）做同机对照，升级 Ruby/改热代码后跑一轮，防"以为在 JIT 上"的配置漂移。

## 7. ZJIT 与 JIT 前沿：预览期预期管理

- 格局：MJIT 已在 3.4 移除——**YJIT 是当前唯一的生产 JIT**；ZJIT（新一代编译器）已合入主干作为实验通道，构建期显式开启、运行期带 --zjit 类旗标（具体开关名以 ruby 仓库 README 为准，预览期可变）。
- 定位差异：YJIT = 惰性、轻量、按调用频次挑热点；ZJIT 目标是更高峰值的整方法优化路线——长期看是接棒者，短期看是实验品。
- 预期管理三条：①不做容量与延迟承诺（性能形状未定）；②不进生产镜像；③yjit-metrics 社区会发对比数据，跟进即可，别自建 fork 赌特性。

```ruby
# 实验构建（开发者本机/预览容器）：
#   ./configure --enable=zjit && make -j && ruby --zjit bench.rb
# 生产口径：只写 --yjit/--yjit-mem-limit；ZJIT 数字仅供参考
defined?(RubyVM::YJIT) ? "yjit" : "unknown"   # 运行时自省
```

- 观察点：ZJIT 的启动开销、代码内存、与 YJIT 的稳态吞吐对比——这三项不收敛就轮不到你决策。
- 坑：预览期文章标题常写"快 X 倍"——对 microbench 成立对 Rails 不成立；一切等官方 yjit-metrics 基准。

## 8. Modular GC 与 MMTk：可替换 GC 的意义

- 3.4 起 GC 可替换：构建期 `--with-modular-gc`（static/dynamic 两种形态），把 GC 实现从 VM 里解耦出来；MMTk 绑定是官方指定的参考实现（研究级，带现代 GC 算法族）。目的是让 GC 研究不必改 VM 源码重编整个 Ruby。
- 随之而来 `GC.config` 出现——读写**当前 GC 实现**的配置；键集合是实现特定的、不保证跨版本稳定，别把它当稳定 API 写进业务代码。
- 生产结论：默认的 RGENGC（分代+写屏障+增量标记）仍是唯一稳妥选择；modular 通道是给做 GC 实验与前沿调优的人的，不是给你的生产部署的。

```ruby
GC.config                       # 当前实现的配置面（键实现相关）
GC.stat(:total_allocated_objects)  # 跨实现的通用口径仍是 GC.stat
GC.stat.keys.grep(/heap/)       # 堆指标族：碎片诊断入口（15 节）
```

- 何时值得关注：MMTk 路线成熟后的停顿/吞吐数据（大堆应用受益先）；在那之前你的 GC 工具链（GC.stat、GC.compact、RUBY_GC_*）一个都不变。
- 坑：社区 benchmark 里 MMTk 的数字常来自非默认构建——直接对比没有意义，注明构建形态再引用。

## 9. 运行时矩阵：CRuby、JRuby 与 TruffleRuby 选型

- 三角：CRuby+YJIT = 生态完备、启动快、稳态好（默认）；JRuby = JVM 上**真线程无 GVL**、warmup 后峰值高，但启动慢、C 扩展不可用（要 ffi 桥）、Rails 能跑但要 jdbc 适配；TruffleRuby = Graal 峰值最高、启动最慢、部分 C 扩展靠位码解释，适合常驻计算内核不适合短命令。
- 决策判据：只有"进程内 CPU 并行是刚需 + 依赖全纯 Ruby"才认真考虑 JRuby；任何 nokogiri/数据库驱动级 C 依赖基本把项目锁死在 CRuby。
- 迁移成本清单：C 扩展全部重审、线程安全债全显形（无 GVL 后共享可变状态全炸）、启动时间预算重谈（JVM）、部署物从 MB 级变百 MB 级。

```ruby
RUBY_ENGINE          # "ruby" / "jruby" / "truffleruby"
RUBY_DESCRIPTION     # 启动日志打一行：版本+构建，事故复盘第一证据
defined?(RubyVM::YJIT)   # 特性探测优于 RUBY_VERSION 比较（10 节）
```

- 坑：把"无 GVL"当免费午餐——JRuby 上你的 Hash 写并发、缓存对象共享全部裸奔，迁移先还并发债再谈收益。
- 混部现实：同一产品线按负载拆运行时（CLI 用 CRuby、批处理服务用 JRuby）可行，但要为两套跑两份 CI 矩阵。

## 10. 运行时自省与防御式特性开关

- 铁律：探测能力，不比较版本号。`defined?(RubyVM::YJIT)`、`respond_to?(Regexp, :timeout)`、`Fiber.respond_to?(:scheduler)`——版本号分支在补丁版本上就开始撒谎。
- 告警治理是升级前置：`Warning[:deprecated] = true`（3.0+ 类别开关）打开弃用类告警；`-W:deprecated` CLI 等效；CI 里把它接到失败条件（重载 Warning.warn 变异常），提前一个版本看见"下一版要炸"的点。

```ruby
# 测试引导：弃用告警即失败（提前一版暴露升级坑）
Warning[:deprecated] = true
def Warning.warn(msg, category: nil)
  raise DeprecationHit, msg if category == :deprecated
  super
end
```

- 注意：改 Warning.warn 是全局补丁——只在测试环境启用，生产禁用（告警风暴变 500）。
- `RUBYOPT` 批量注入：CI 统一 `-W:deprecated`、本地复现加 `RUBYOPT="--yjit"` 保证环境一致性；部署层与 CI 用同一份环境变量清单管理。
- 自省披露面：日志启动行打 RUBY_DESCRIPTION + 关键特性开关状态，事故时先对齐"到底跑的是什么"。

## 11. 3.3→3.5 升级陷阱清单

- ①冷字符串：3.4 告警基线法（2 节）——CI 全量 -W:deprecated，量出来再排期，别肉眼试。②Symbol#to_s 冻结：`rg 'to_s.*(<<|[a-z_]+!)'` 扫变异链（19 节）。③Happy Eyeballs 2：绑 127.0.0.1 的 DB/Redis + 客户端写 "localhost" → 连接被拒（12 节）。
- ④`it` 捕获：块内把 it 当普通局部量的代码静默变义（1 节）。⑤kwargs 遗留：`ruby2_keywords` 垫片、`*args` 转发与 `**nil` 新语义（4 节）一起复核——3.0 分离的老债在这里收口。⑥AST 工具：Ripper/RubyVM::AbstractSyntaxAST 的产出物迁移 Prism（44 节）。

```ruby
# 升级后一次性执行（Ruby 没有稳定 C ABI，原生扩展必须全部重编）
#   gem pristine --all
#   rm -rf tmp/cache/bootsnap*    # bootsnap 缓存按 Ruby 版本/指纹失效
# CI 矩阵：RUBYOPT="-W:deprecated" 下全量测试，弃用告警 = 红
```

- ⑦原生 gem 重编纪律：小版本升级后 `gem pristine --all`（或容器直接重建）；报"undefined symbol"九成是老 ABI 的 so 残留。
- ⑧依赖预演：先升 Ruby 再升 Rails/gem 的顺序反过来做——先把 gem 升到"声明支持目标 Ruby"的版本，再动 Ruby，减少双向变量。
- 上线节奏：3.4 停一周期收告警 → 3.5 收 API 变化（Port/nil splat），两跳分开出问题好定位。

## 12. Happy Eyeballs 2：双栈解析的隐性事故

- 3.4 起 Socket/Net::HTTP 按 RFC 8305 落地 HEv2：解析 AAAA 优先、先打 IPv6、约 250ms 未连上则**并行**回退 IPv4。行为翻转的经典位点：客户端写 `"localhost"`，服务只绑 127.0.0.1（DB/Redis/内部服务默认形态）→ 3.3 能连、3.4 报 connection refused（::1 上没人）。
- 症状画像：升级 Ruby 后"随机"连接失败、容器里更明显（很多镜像没配 ::1）、telnet 127.0.0.1 正常 telnet localhost 不通——都是同一件事。

```ruby
require "socket"
Socket.tcp("localhost", 6379)   # 3.4：可能先打 ::1 → ECONNREFUSED
Socket.tcp("127.0.0.1", 6379)   # 显式栈：行为确定，配置里写这个
Socket.tcp("::1", 6379)         # 验证对端是否真的缺 IPv6 绑定
```

- 修复优先序：配置显式写 `127.0.0.1`（配置改一行，比改环境强）> 服务端双栈绑定（listen :: + v4 映射）> 容器补 ::1 回环。
- 坑：开发机正常（macOS/新内核 localhost 双栈）而 CI/容器失败——把"127 显式化"直接写进新项目模板，一劳永逸。
- 关联面：HEv2 的并行连接让 SYN 量翻倍，极端 fan-out 下注意 conntrack 表；超时语义也变了（250ms 回退意味着失败更快暴露）。

## 13. Ruby GC 内幕：分代、写屏障与增量标记

- RGENGC：对象分新/老两代，新对象龄满晋升；老对象被改时靠**写屏障**记账（remembered set），于是 minor GC 只扫新生（便宜）、major 才扫全堆（贵，可达 10-100 倍差）。优化目标永远是"把 major 的频率压下来"。
- 增量标记（RINCGC）：major 的标记阶段拆成小片穿插进业务执行——单次停顿小了、总开销略增；感知上"没有长停顿但整体慢了一点"就是这么来的。
- malloc 触发线：槽外的缓冲（大 String/Array）走 malloc；分配累计超 malloc_limit（默认 16MiB）就触发 GC。分配大户应用=GC 被动挨打，减分配是治本（20 节）。

```ruby
GC.stat.fetch_values(:minor_gc_count, :major_gc_count,
                     :malloc_increase_bytes, :oldobjects)
# major/minor 比值是第一健康指标：major 占比高 = 晋升过快或常驻集过大
GC.start(full_mark: true, immediate_sweep: false)  # 生产禁用；参数含义没吃透别碰
```

- 生产禁 `GC.start`：一次全量 major + 全进程停顿（GVL 在 GC 关键段持着）；用它"治内存"只会治出延迟尖刺。
- 监控落点：把 minor/major 计数、malloc_increase、oldobjects 进时序库，与 p95 曲线叠图——GC 风暴与延迟尖刺的关联一眼可见。

## 14. RUBY_GC_* 调优矩阵

- 五个高频变量：`RUBY_GC_HEAP_INIT_SLOTS`（启动即备足堆，消启动堆增长抖动）、`RUBY_GC_HEAP_GROWTH_FACTOR`（默认 1.8，大堆应用调低减少过冲）、`RUBY_GC_MALLOC_LIMIT`（16MiB 默认，malloc 触发线）、`RUBY_GC_OLDMALLOC_LIMIT` 族（老代 malloc 线，major 触发器）、`RUBY_GC_HEAP_OLDOBJECT_LIMIT_FACTOR`（老对象占比触发 major）。
- 顺序纪律：**先 jemalloc 后环境变量**（16 节）——malloc 层不换，堆参数治标不治本；再先减分配（20 节）后调参数——参数兜底而不是掩盖。
- 调参流程：基线（GC.stat 快照 + APM）→ 单变量改动（一次只动一个）→ 同负载压测对比 → 固化进部署清单（与镜像同进同出）。

```ruby
# 部署层起点（先测再改；数值仅为常见起点不是处方）
#   RUBY_GC_HEAP_INIT_SLOTS=4000000    # 启动堆，boot 后 heap_allocated_pages 平滑
#   RUBY_GC_HEAP_GROWTH_FACTOR=1.2     # 大堆压过冲
#   RUBY_GC_MALLOC_LIMIT=64000000      # 分配大户抬高触发线
```

- 坑：把 INIT_SLOTS 拉到"全量常驻对象"那么大——启动内存翻倍、启动变慢；目标是"boot 期增长平滑"，不是一劳永逸。
- 坑：调参后 metrics 没回采=没调；所有 RUBY_GC_* 改动必须带着前后对比图进 PR 描述。
- 每个小版本重测一次默认值——GC 内部常数（页大小、晋升年龄）在演进，去年的最优值今年可能平庸。

## 15. GC.stat 诊断与 GC.compact 压实决策

- 最重要的一个数：`total_allocated_objects` 的**差值**。请求/任务开始结束各取一次，差值=该单元的分配量——这是把"GC 慢"定位到"哪个端点在分配"的最低成本手段，比 profiler 先跑一步。
- 碎片判定：`heap_allocated_pages` 高、但活对象（oldobjects + 新生存活）低 → 页大量空闲碎片化；压实后页数应明显下降、RSS 跟着降。
- `GC.compact` 成本：一次全量 major + 对象搬移（引用要修）——放启动尾（预热完成后、接流量前）或低峰窗。`GC.auto_compact = true`（3.1+）让它周期化，代价是周期性 major，吞吐敏感应用慎开。

```ruby
before = GC.stat(:total_allocated_objects)
result = yield
allocated = GC.stat(:total_allocated_objects) - before
logger.info { "alloc=#{allocated} pages=#{GC.stat(:heap_allocated_pages)}" }
```

- 坑：老 C 扩展未实现移动回调 → 部分对象被钉死、压实打折（compact 返回的 moved 统计可看效果）；升级扩展版本再看。
- 坑：`memsize_of` 是**浅尺寸**（不含引用的子对象），用它算"对象多大"永远偏小；总量估算用 dump（23 节）。
- 治理：分配差值进日志→按端点聚合上图表→阈值告警，让"分配回归"像测试失败一样可见（70 节）。

## 16. glibc malloc 碎片与 jemalloc 部署实战

- 机制：glibc 给每线程发 64MiB arena，多线程长寿进程的 free 空洞散布在众多 arena 里还不了给 OS → RSS 远超活对象、还只会慢慢涨。症状：`GC.stat` 的堆页稳定、ObjectSpace 活对象平稳，但 RSS 爬升——这就是"碎片冒充泄漏"的标准像。
- jemalloc：按尺寸类分配 + decay 机制归还空闲页；Ruby 圈实战数据（Nate Berkopec 系列）常见 RSS -20~30%，大内存应用更明显。它是 Ruby 内存工程里**单位成本最低的一步**。

```ruby
# 部署：LD_PRELOAD=/usr/lib/x86_64-linux-gnu/libjemalloc.so.2（或构建期 --with-jemalloc）
# 验证进程真的吃上了（启动日志/巡检脚本）：
lines = File.readlines("/proc/#{Process.pid}/smaps")
puts lines.grep(/jemalloc/).first || "STILL GLIBC"
```

- 退路：容器里装不了 jemalloc 时 `MALLOC_ARENA_MAX=2` 压 arena 数——治一部分，不如换库。
- 坑：LD_PRELOAD 写在 systemd unit 里的 Environment 前缀（LD_PRELOAD 需要在 exec 前注入）；k8s 用 init container 或镜像层装库，别指望运行时 cp。
- 验收：同负载压测前后 RSS 曲线对比，效果进部署文档；换了 Ruby 大版本后重测（分配形状变了红利会变）。

## 17. 堆槽增长模型：RVALUE 与页分配

- 物理模型：每个对象占一个 40B 的槽（RVALUE）；槽装在 16KiB 的堆页里（每页约 400 个槽）；小数组（几个元素内）与短字符串的**缓冲内嵌在槽里**，超限后缓冲独立 malloc、槽里只留指针。
- 推论①：一百万个小字符串 ≈ 40MB 槽 + 各自缓冲 + 碎片——短字符串的内存大头在"每个都是对象"而不是内容本身；驻留（fstring）与复用才是解（18 节）。
- 推论②：堆增长按页翻倍（growth factor），峰值流量下的页数会成为**常驻水位**——页进来了就很少自动还；所以"流量峰值"直接写进 RSS 基线，容量规划按峰值算。

```ruby
require "objspace"
ObjectSpace.count_objects
# => {:TOTAL=>..., :T_STRING=>..., :T_ARRAY=>..., :FREE=>...}
# T_STRING/T_ARRAY 占比告诉你"这进程在造什么"
ObjectSpace.memsize_of("a" * 100)   # 浅尺寸：槽+缓冲，不含引用
```

- 诊断顺序：count_objects 看类型分布 → GC.stat 看页数与空闲槽 → 必要时堆 dump（23 节）看"谁持有"。
- 坑：以为删了引用就该掉内存——页水位不降是正常的（灰页/空闲槽复用），别为"没降"开工单；盯活对象与页数趋势而不是瞬时 RSS。

## 18. 字符串内存经济学与驻留

- 双账本：一个 String = 40B 槽 + （超内嵌后）独立缓冲；`dup`/`+`/插值每次都两本都付；`<<` 原地扩容复用缓冲（免掉一对象一缓冲）；fstring 驻留把 frozen 字面量收敛成全进程一份。
- 实操阶梯：重复出现的"标签型"字符串（属性名、事件名、缓存键前缀）→ 常量 + frozen literal（驻留）；拼接结果一次性使用 → 插值即可；循环内逐步构建 → `<<` 到复用缓冲或 String.new 预分配。

```ruby
# frozen_string_literal: true
TAG = "billing"            # 驻留：进程唯一
def key(id) = "acct:#{id}" # 插值每次新对象（内容相关无法驻留）
parts = +""
rows.each { parts << _1.name }   # << 原地：一份缓冲一路到底
```

- 坑：`"#{TAG}"` 与 TAG 内容相等但**是不同对象**（插值不驻留）——别指望 frozen literal 让插值免费。
- 坑：把大字符串当参数在层间传递时反复 `dup`（防御性拷贝）——热路径上这是双倍分配；用冻结（freeze）表达"不可变契约"比 dup 便宜。
- 度量：memory_profiler 看 String 的 allocated by location（21 节），前十个位置通常吃掉一半优化收益。

## 19. Symbol#to_s 冻结的连锁反应

- 3.4 起 `Symbol#to_s` 返回冻结的驻留串——内部路径（AR 属性访问、序列化、日志）赚一笔可观的零分配红利；代价是所有"拿 to_s 结果当可变工作缓冲"的代码当场 FrozenError。
- 扫雷清单（升级 3.4 前）：`rg 'to_s\s*<<'`、`rg 'to_s\..*!'`（sub!/gsub!/strip!/tr! 等）、以及链式 `sym.to_s + "x"` 中靠 `<<` 改写的变体；gem 里的同款问题靠升级解决，报 issue 附复现。
- 修法对照：需要可变副本 → `+ sym.to_s`（String#+@，幂等且显式）；只是读 → 不用动；真的要驻留判断 → `sym.name` 同样返回冻结串，两者语义现在对齐。

```ruby
:kpi.to_s << "!"   # 3.4+：FrozenError（返回值是驻留冻结串）
name = +:kpi.to_s  # +str：可变副本，惯用修法
name << "!"        # 正常
```

- 顺带红利：拿 Symbol#to_s 的结果做 Hash 键/比较，hash 码已缓存——比 3.3 的路径快且零分配，热点里的 `sym.to_s` 别急着"优化掉"。
- 坑：`+str` 只保证可变不保证拷贝语义可预期传播——把副本再传给期望独占缓冲的 C 扩展前想清楚。

## 20. 热路径零分配纪律

- 心法：分配=GC 压力=延迟尖刺的原材料；热路径的优化目标从"快"先改成"不分配"，`total_allocated_objects` 差值就是仪表（15 节）。
- 手法箱：`merge!`/`concat` 原地改代替 `merge`/`+` 新建；`filter_map` 一趟替代 map+compact 两趟；循环外提常量（含 Regexp 常量化——循环内 `/x/` 每圈重编译）；`each_with_object` 复用累加器；插值换 `<<`；frozen literal 驻留标签串（3 节）。
- 审查流程：给每个热端点标"分配预算"（如 ≤2k 对象/请求），日志回采实际值，超预算就是红灯——比"慢了再看"早一个身位。

```ruby
before = GC.stat(:total_allocated_objects)
status = handle(request)
allocs = GC.stat(:total_allocated_objects) - before
statsd.histogram("req.alloc", allocs, tags: {route: request.route })
```

- 坑：零分配洁癖过头——把可读性牺牲在非热路径上；预算只挂在已被数据证明热的端点。
- 坑：测试环境测不出分配差异（对象图不同）——用生产回放（流量录制）或 staging 压测定预算。
- 与 YJIT 的关系：YJIT 会把很多"解释器时代的分配技巧"收益抹平，但 GC 压力它治不了——零分配纪律在 JIT 时代依然成立。

## 21. memory_profiler 热点归因工作流

- 用法核心：`MemoryProfiler.report { ... }` 产出四象限——**allocated**（执行期总分配）/ **retained**（GC 后仍存活）× 按 class / 按 file+gem 归因。allocated 大 retained 小 = GC 压力问题（改零分配，20 节）；retained 大 = 持有泄漏方向（查引用链，23 节）。四象限先看，再下钻。
- 采样纪律：包"代表性请求"而不是包"整个测试套件"——工厂造的假数据分布会把归因带偏；理想形态是请求级中间件开关（按采样率/白名单用户）在生产复制品上跑。

```ruby
require "memory_profiler"
report = MemoryProfiler.report { 50.times { Billing::Sync.call(sample) } }
report.pretty_print(to_file: "tmp/mem-#{Time.now.to_i}.txt",
                    scale_bytes: true)   # 人类可读尺寸
```

- 读报告顺序：allocated by gem（先看是不是依赖在烧）→ allocated by location 前十（自己的代码）→ String/Array 的复用型机会（18 节）→ retained 的类分布（对象被谁钉住）。
- 坑：报告本身分配巨大——别在高峰生产进程跑；复制品/低峰窗。
- 坑：`ignore` 参数降噪时别把可疑 gem 忽略了——归因结论要写"未忽略任何依赖"的完整版进附件。

## 22. derailed_benchmarks 与内存膨胀复盘

- 两把刀：`derailed exec perf:mem` 走 boot + 一组请求，给出启动与请求分配的分层画像（默认打 `/`——路由必须真实存在，或用环境变量指到压测脚本）；`derailed exec perf:library` 把**启动分配/耗时按 gem 归因**——依赖审计的金标准，谁在 boot 期烧内存一目了然。
- 标准复盘剧本：主进程 RSS 涨 30% → perf:library 对比三个月前（依赖 diff）→ 找出新升的重量级 gem → 决定砍/换/惰性化（require 推迟到 fork 之后，preload 场景另谈，89 节）。

```ruby
# Gemfile 里加 derailed_benchmarks（开发组）
# bundle exec derailed exec perf:library     # 启动成本按 gem 归因
# COUNT=100 bundle exec derailed exec perf:mem
# DERAILED_NUMBER_OF_REQUESTS=100 同义；脚本可用 DERAILED_TEST_SCRIPT 定制
```

- 常见结论形态：admin/devise/导出类 gem 是 boot 分配大户；可选功能 gem 该在"用到才 require"的边界后面（42 节的 require 时机纪律）。
- 坑：dev 环境 boot 画像和生产不同（eager_load 开关、bootsnap 命中）——用 `RAILS_ENV=production` 跑 derailed，别拿 dev 数字下结论。
- 治理：把 perf:library 输出存进仓库（docs/perf/）随版本更新，PR 审查时对 diff——新依赖的 boot 成本必须有人签字。

## 23. ObjectSpace 堆取证与泄漏误判

- 三 dump 差分法（比单 dump 可靠得多）：boot 预热后 / T+1h / T+4h 各 dump 一次 → 用 `heapy`（或 jq）按 generation/class 聚合 → **retained 且随时间单调增长的类**才是泄漏候选；单点 dump 只能看"现在多大"，看不出"谁在涨"。
- 误判清单：①RSS 涨但活对象平 → malloc 碎片，去 16 节；②活对象涨但是缓存本体 → 是配置不是泄漏（缓存上限策略）；③dump 里 memsize 是浅尺寸（17 节）——算"某类占多少"要用聚合口径而不是单对象相加的直觉。

```ruby
require "objspace"
ObjectSpace.dump_all(output: File.join(dir, "heap-#{Time.now.to_i}.jsonl"))
# 分析：heapy read heap-*.jsonl —— 按 generation/class/位置聚合
# 大堆注意：dump 文件可到 GB 级，且 dump 本身重分配——用复制品进程
```

- 执行环境：复制品/独立进程（`ObjectSpace.dump_all` 全进程暂停 + 巨量 IO）；生产主进程跑 dump 是事故。
- 闭环：定位类 → dump 条目里有 allocation_sourcefile/line（分配点）→ 结合 memory_profiler（21 节）看引用持有者 → 修复后重跑三 dump 验证斜率归零。
- 坑：generation 聚合时忽略"boot 代"——那批常驻对象是正常水位，泄漏证据只来自后续代的异常滞留。
## 24. 方法查找路径解剖：singleton、prepend 与 include 全序

- 全序（一次调用的真实走链）：接收者的 singleton class → 该单例类上的 prepend（逆序）→ 单例类本体 → 单例类上的 include → 类本体 → 类上 prepend 的模块（逆序）→ 类上 include 的模块（逆序）→ 父类链上重复此过程。`obj.class.ancestors` 看不到单例，`obj.singleton_class.ancestors` 才是调用视角的权威视图。
- 成本直觉：链越长一次 miss 越贵；AR::Base 的 ancestors 有 30+ 项——在它上面再叠 concern，是在给每次 miss 加税。方法未命中才走全链，热路径的命中靠缓存（25 节）。
- 同名方法在链上多处出现时"首中即停"：prepend > 类本体 > include 是最强排序工具——包装器用 prepend、可覆盖实现放类、共享默认放 include。

```ruby
klass = obj.singleton_class
while klass
  puts klass
  klass = klass.superclass          # 单例 → 类 → 父类全链
end
obj.method(:save).super_method&.owner   # super 落点：包装器调试第一招
```

- 坑：`module M; def x; end; end; class C; include M; def x; end; end`——C#x 赢过 M#x（类本体在前），但把 M 改成 prepend 则反转；升级依赖时 prepend 的出现会静默反转你靠"类赢"写的覆盖。
- 坑：给类再 include 同一个模块不会改变顺序（幂等无副作用），但 prepend 两份会双份执行——审计用 `ancestors.grep`。

## 25. 方法缓存失效模型与全局代价

- MRI 的两层缓存：全局方法缓存（带失效计数器）+ 调用点内联缓存。**任何**方法定义/删除/模块插入都会推失效计数器——越靠近根的改动，失效面越大：往 Object/Module/核心类上定义方法 = 全进程所有调用点重解析，一次看似无害的"补丁"就能让 p50 抬一截。
- 生产禁忌：请求期改类。真实反模式——"首次访问时给模型补个方法"、"per-tenant 定义方法"、"缓存未命中就 define_method"：每次改类全进程抖动 + 方法只增不减（内存单调涨）。元编程一律 boot 期完成（42 节时机序）。
- 与 bootsnap 的关系澄清：bootsnap 缓存的是 require 的编译产物与 $LOADED_FEATURES 查询（89 节），**救不了方法缓存**——boot 期大量定义仍然会互相失效，这解释了"为什么启动时就是慢"的一半。

```ruby
# 反面教材：请求期改类（每次调用都让缓存失效 + 方法集只增不减）
def cached_attr(name)
  return if respond_to?(name)
  define_method(name) { instance_variable_get("@#{name}") }  # 每请求改类
end
# 正面：boot 期一次性生成全部方法（36 节固化、42 节时机）
```

- 坑：include 进 Object（有些老 gem 这么干全局猴补）代价最大且难以追溯——用 43 节的 boot 审计清单抓出来。
- 判据：把"元编程是否只在 boot 期发生"写进 PR 检查项；`Module#instance_methods.size` 前后对比是廉价审计。

## 26. define_method 与 def 的性能内存账本

- def：静态编译进 iseq，调用走内联缓存，YJIT 可整体优化——默认形态。define_method：由块现场造方法，捕获定义处的 self 与局部量（env），调用开销略高，且 **YJIT 对其优化覆盖明显更弱**（热路径上差距放大）。
- 内存维度才是隐藏账：每个 define_method 挂着 Proc + 环境引用——在请求期定义方法并捕获大对象 = 方法永生 + 对象被钉（41 节堆取证）。
- 生产折衷：DSL 需要批量生成热点方法时，用 `module_eval` + **固定模板字符串**（插值只来自白名单常量）生成 def——性能与 def 等价、源码可 grep、比 eval 风险面小（37 节对照）。

```ruby
OPERATIONS.each do |op|   # OPERATIONS 是启动期冻结的白名单
  module_eval <<~RUBY     # 生成 def：YJIT 友好、调用缓存友好
    def #{op}(payload) = dispatch(:#{op}, payload)
  RUBY
end
```

- 坑：模板插值必须只来自代码常量（`OPERATIONS`），绝不接用户输入——否则这就是 37 节的 eval 惨案换了马甲。
- 什么时候 define_method 仍是对的：方法体需要闭包捕获**少量固定配置**且不在超热路径——清晰胜过微优化。
## 27. singleton class 激活成本与 def self 的隐性差异

- 关键差异：类的 singleton class **永远存在**（类本身就是对象且必有单例）——`def self.x` / `class << self` 是零额外分配的。而**实例**第一次被 `def obj.x` / `define_singleton_method` / `extend` 才会新分配一个 singleton class，且每个实例各一个。
- 万级对象各配单例 = 双输：内存上 N 份单例类，查找上每份都插进调用链。行级装饰（给每行挂个方法）是真实事故源。
- 替代：共享 Module + extend（方法表共享，只付每实例一个klass指针）；或干脆表驱动 `send(:"row_#{kind}")`（38 节白名单）。

```ruby
# 反面：N 行 N 个 singleton class（内存+查找双成本）
rows.each { |r| r.define_singleton_method(:total) { price * qty } }
# 正面：一个 Module，方法表共享
Totals = Module.new do
  def total = price * qty
end
rows.each { |r| r.extend(Totals) }
```

- 坑：`obj.extend(M)` 之后再 `obj.extend(M2)`——单例链上叠模块，顺序即查找序；调试时 `obj.singleton_class.ancestors` 一步看清。
- 审计：`ObjectSpace.each_object(Class).count` 前后对比能抓出"单例类爆炸"（frozen_string_literal 不影响这个，是对象图的锅）。

## 28. Struct、Data 与 OpenStruct 生产选型

- OpenStruct：method_missing + 内部 Hash 的"动态对象"——慢一个量级、内存重、3.0+ 首次实例化打性能告警。只配出现在"配置文本解析"这类一次性边缘场景，热路径出现即驳回。
- Struct：C 底实现，分配/访问都快，实例**可变**、按位置或 keyword_init: true 构造，适合"行记录/中间载体"。坑：Struct 可变 + 意外共享（塞进常量再改）；跨进程缓存 Struct 用 Marshal 有版本税（105 节）。
- Data（3.2+）：不可变值对象——成员式相等、each 无 setter、`with` 拷贝改造、天然可作 Hash 键；值语义的正确载体（29 节深用）。

```ruby
Money = Data.define(:amount, :currency)
Money.new(10, "EUR") == Money.new(10, "EUR")    # true：成员式相等
Point = Struct.new(:x, :y, keyword_init: true)
Point.new(x: 1, y: 2).x = 9                     # 可变：Struct 是记录不是值
```

- 决策速查：对外值对象（金额、区间、坐标）→ Data；内部可变记录 → Struct 或普通类；JSON 反射/动态键 → Hash/keyword Struct；OpenStruct → 默认写"否"。
- 坑：Data 与 Struct 都浅冻结/浅相等——嵌套可变成员的问题在 29 节展开。

## 29. Data 类深用：不可变值对象与 with

- `with` 的拷贝语义是**浅拷贝**：新实例的成员引用与原对象共享。嵌套可变容器（Array/Hash）时，`with(items: old.items << x)` 会先把原数组改掉、再让两个对象共享——一石二鸟的经典事故。
- 冻结也是浅的：Data 冻结自身，不冻结成员内容。要真值语义：成员入参前 `dup.freeze` / `Ractor.make_shareable`（54 节复用）/ 或约定成员只能是标量与 Data。

```ruby
Box = Data.define(:items)
b = Box.new(items: [])
b2 = b.with(items: b.items << 3)   # 反面：原数组已被改，b 也"变了"
# 正面：以新集合传入，或成员只接受冻结容器
b2 = b.with(items: b.items + [3])
```

- 等价与哈希：Data 按成员比较、hash 一致——放心做 Hash 键/去重键（比 Struct 的身份语义适合得多）；模式匹配 `deconstruct_keys` 原生支持，case/in 里直接解构。
- 坑：Data 不能当 AR 属性存储（值对象落库要显式序列化策略——列拆开或 JSONB，别 Marshal 整存）。
- 坑：类体可以继续定义普通方法（`class Money < Data.define(...)` 里写 `def euros?`）——但别塞持久化/IO 逻辑，Data 的契约是"纯数据 + 派生计算"。

## 30. refinements 激活语义：词法作用域深水

- 激活只有三处：顶层 `using`（作用到文件末）、类/模块体 `using`（作用到该体结束）、`refine` 块内部。**词法性**是全部理解钥匙：按代码文本位置生效——同一处文本才受影响，动态传进来的 proc/方法体（在别处写的）不继承。
- 不沿继承链传播：父类 using 的 refinement 子类拿不到；`using` 写进方法体直接报错（设计如此，防动态作用域魔法）。
- 正确用途：gem/应用内"局部猴补"——需要的文件显式 using，不污染全局；错误用途：给外部调用者提供魔法 API（31 节讲为什么必翻车）。

```ruby
module TimeMath
  refine Integer do
    def hours = self * 3600
  end
end
class Report
  using TimeMath          # 类体激活：仅本类词法范围
  def ttl = 2.hours       # 生效
end
# 顶层 2.hours → NoMethodError（未激活区域）
```

- 坑：把 using 放进被多个文件 require 的共享文件——激活面随 require 顺序漂移；refinement 的 using 必须出现在"用它的那个文件"里，一眼可查才符合词法设计。
- 坑：同文件里 using 之后又 include 了别的模块，refinement 只作用于**文本中 using 之后的调用**——插一行代码就可能改变前几行的语义边界。

## 31. refinements 盲区：send、respond_to? 与 eval

- 三个绕过点：`send`/`__send__` 不走 refinement（`1.send(:hours)` → NoMethodError）；`Symbol#to_proc`（`map(&:hours)`）内部经 send 通道 → 同样绕过；eval 的字符串代码不在任何词法范围 → 绕过。**凡是"动态分发"都看不到 refinement**。
- 内省撒谎链：refine 出的方法 `respond_to?` 返回 false、`method(:hours)` 抛 NameError、`instance_methods` 不列出——respond_to_missing? 也补不了（它只能回答"有没有"，救不了 refine 的可见性盲区）。序列化/反射/文档工具全数失明。

```ruby
[1, 2].map(&:hours)      # 绕过：Symbol#to_proc 走 send 通道 → NoMethodError
[1, 2].map { _1.hours }  # 生效：块文本在 using 的词法范围内
1.respond_to?(:hours)    # false —— refinement 不进内省
1.send(:hours)           # 同样绕过：动态分发看不到 refinement
```

- 结论：refinement 只服务"直接调用"的本地便利；对外 API、测试断言、序列化路径依赖的扩展一律走显式方法或模块（30 节的边界意识）。
- 坑：CI 里"绿"的用例到生产 NoMethodError——多半是测试帮助代码绕过了词法边界（自定义 matcher 里的 send）；测试也要按生产同款写法覆盖。

## 32. 常量查找路径与 Object 污染

- 解析顺序：先**词法嵌套**（Module.nesting 从内到外）再看 cbase 的祖先链——嵌套命名空间里的裸引用先命中词法同名的"近常量"，再命中链上的。这是"class 内裸 RATES 与 module 外的 RATES 不同值"这类 bug 的全部成因。
- 顶层常量就是 Object 上的常量 = 全局命名空间：任何 lib 往顶层 `const_set`/直接定义都在污染**所有**常量查找（还可能撞名）。lib 必须收进自己的命名空间；审计用 `Object.constants.grep(/^[a-z]/)` 找不该存在的顶层定义。

```ruby
module Billing
  RATES = { vip: 0.8 }
  class Invoice
    RATES = { vip: 0.9 }     # 词法优先：类内裸 RATES 命中这个
    def vip = RATES[:vip]    # => 0.9，不是外层的 0.8
  end
end
Billing::Invoice::RATES     # 显式限定才是消除歧义的写法
```

- Zeitwerk 时代纪律：目录即命名空间、别在 app 代码里 require_relative 绕过 loader（会绕出"半加载"状态）、7.1+ 用 `config.autoload_lib(ignore: %w(assets tasks))` 把 lib 纳入自动加载。
- 坑：initializers 里触碰 app 常量会触发"惰性加载冻结"（87 节）——常量体系与加载体系是一件事的两面，一起审。

## 33. inherited、included 与 extended：DSL 骨架钩子

- 四钩子是所有类宏的地基：`included(base)`（模块被 include）、`extended(base)`、`inherited(subclass)`（类被继承）、`prepended(base)`。AS::Concern 的 `included do ... end` 只是"把块延后到宿主上下文执行"的语法糖，并没有引入第四种钩子语义。
- 经典事故位：把"每个宿主一份"的状态存进**模块自身的实例变量**——所有 include 者共享一份，互相覆盖（多模型挂同一 concern 的配置串台就是这么来的）。状态必须存 base：`base.instance_variable_set` 或 base 的单例方法。
- inherited 做配置继承：父类在 hook 里把注册表 dup 给子类——子类后续修改不回灌父类（Railties/AR 的配置树都是这个骨架）。

```ruby
module Auditable
  def self.included(base)
    base.extend ClassMethods
    base.instance_variable_set(:@audit_fields, [])   # 状态在宿主上
  end
  module ClassMethods
    def audit(*fields) = audit_fields.concat(fields)
    def audit_fields = @audit_fields                 # 每宿主一份
  end
end
```

- 坑：hook 里再 require/常量引用会在 boot 早期触发加载序问题（87 节）；hook 只做登记，重活留给 boot 末物化（39 节）。
- 坑：`inherited` 在类体执行完之前就触发——hook 里读"类级配置"可能读到半成品；需要完整态的登记挪到 `inherited` 里 `trace`（子类方法定义回调）或物化阶段。

## 34. 面向工具的对象模型：让 IDE 与 grep 看得见

- 元编程的真实成本不在运行时（那点开销可测可调），而在**工具失明**：IDE 跳转断、grep 不到、Sorbet/Steep 推断不出、新人 onboarding 全靠口口相传。把这条写进成本模型，很多"聪明"设计自动出局。
- 纪律一：动态面最小化 + 静态外壳最大化——boot 生成的方法（26 节模板法）**同步生成 RBI**（tapioca/手写），让静态检查器看得见；魔法越少，`rg` 越有用。
- 纪律二：introspection 补全契约——respond_to_missing? 与生成方法同步维护（35 节），`Registry.all.keys` 类内省入口必须有，文档直接从它生成。

```ruby
# sorbet/rbi/registry.rbi —— 动态方法的静态影子（随生成脚本同步）
# class Order
#   sig { returns(String) }
#   def discount_amount; end     # OpsRegistry 在 boot 期生成
# end
```

- 评审规则：元编程 PR 必附"生成的 API 清单 + RBI 是否更新 + 内省入口验证"三项；缺一即打回——这比任何风格争论都有效。
- 坑：用"生成物进库"（42 节）后忘了 CI 同步校验——手改生成文件与再生成漂移；`git diff --exit-code` 守门。
- Sorbet/RBS 取舍：增量注解（新代码 strict、老代码打洞）比全量改造现实；注解最值钱的回报是**接口漂移在 CI 就炸**（97 节 verify doubles 是测试侧的同一思想）。

## 35. method_missing 契约：respond_to_missing? 与工具缺口

- 三件契约（缺一即 bug 库）：①**窄拦截**——只接明确前缀/表内名字，其余一律 `super`（BasicObject 会抛带正确接收者与参数的标准 NoMethodError——自己 raise 版本常常丢掉 kwarg 信息）；②`respond_to_missing?(sym, include_private = false)` 与拦截规则**逐字同步**——否则 respond_to?/marshal/yaml/to_json 的探测全撒谎；③拦截分支对参数形态做白名单判断（args/kwarg 数）再处理，防"错误转发"。
- 性能位次：每次 miss = 走满查找链 + 进 method_missing + 字符串判断——热点必炸（36 节固化是唯一解）；DSL 面越小越好。
- 工具缺口是契约的一部分：文档/yard 不会列出动态方法——维护一份"生成方法清单"文件并在 PR 里更新（34 节）。

```ruby
def method_missing(name, *args, **kwargs)
  if name.to_s.start_with?("render_") && args.empty?
    render(name.to_s.delete_prefix("render_"), **kwargs)
  else
    super                       # 标准异常：正确的 name/args 信息
  end
end
def respond_to_missing?(name, include_private = false)
  name.to_s.start_with?("render_") || super
end
```

- 坑：`super` 忘写 → 所有打错的方法名静默成功/返回 nil，NoMethodError 防线整个失效；这是比"慢"更隐蔽的债。
- 坑：method_missing 里再 `send(name)` 转发——若 name 恰好是私有方法（如 `system`）就越权执行（38 节）；转发前做可见性判断。

## 36. method_missing 缓存化：把 miss 固化成方法

- 补洞模式：miss 时按名字 `define_method` 出真方法再调用——第二次起走普通调用（缓存 + YJIT 全都生效），miss 只发生一次/方法。生成的"cache"要无闭包大对象（只引用常量与 ivar，41 节）。
- 有界纪律：生成量必须有界——外部输入能到达 method_missing 的地方，每个字符串都能造一个**永久方法**（内存单调涨 + 方法集不可审计，"方法炸弹"）。表驱动的白名单（`TEMPLATES[name]`）先拒绝，再谈生成。
- 失效：映射表（配置/DB 元数据）变化时 `remove_method` 对应生成物，或整体重放生成序列；boot 期能预生成的直接预生成（42 节时机序优于运行时补洞）。

```ruby
def method_missing(name, *args)
  tpl = TEMPLATES[name]
  return super if tpl.nil?                     # 白名单外：标准异常
  self.class.define_method(name) { |*a, **kw| render(tpl, *a, **kw) }
  send(name, *args)                            # 固化后立刻走真方法
end
```

- 坑：固化方法的参数签名要与调用约定对齐（arity 检查不再触发 method_missing）——生成 `|*a, **kw|` 再在内部校验，比固定 arity 稳。
- 坑：多线程并发首次 miss——两个线程同时 define_method 同名方法是幂等的（同方法体重定义），但要确认生成逻辑无副作用（计数、日志只做一次可用 `@generated.key?(name)` 双检）。
- 验收：固化后 `respond_to_missing?` 仍保留（老实例/反射路径），但 miss 计数应归零——加个计数器进指标。

## 37. eval 家族风险与替代路径

- 红线：`eval`/`instance_eval`/`class_eval` 的**字符串形态**接受任意 Ruby 代码——一个插值就是 RCE；`$SAFE` 已于 3.0 移除，MRI 进程内**没有沙箱**，"我只是 eval 一个表达式"不存在安全形态。
- 替代阶梯：表达动态行为 → block 形态 `class_eval { }`/`instance_exec`（无解析无注入）；批量定义方法 → 固定模板 module_eval（26 节）；解析用户内容 → Prism 解析成 AST 后按**节点类型白名单**校验再处理（44 节）——这是审计类工具（如策略 DSL）的安全套路。

```ruby
# 反面：RCE
instance_eval("puts #{params[:code]}")
# 正面：块形态，能力等价、零注入面
instance_exec(policy_target) { |t| t.allowed?(user) }
```

- 坑：模板字符串看似安全，插值点只要有一个接外部值就全盘皆输——code review 对 heredoc + eval 的组合默认亮红灯，要求插值源逐一指认。
- 坑：eval 的代码**看不到 refinement**（31 节词法盲区）、也绕过 bootsnap 编译缓存——eval 重的设计在启动与工具链上是双重负资产。
- 允许清单：eval 只许出现在"执行自己生成/冻结的字面代码"（如发布脚本），且函数边界处注明"输入已不可变校验"。

## 38. send 越权面与 public_send 纪律

- 事实：`send` 穿透 private/protected——`Kernel#system` 本就是 private，`obj.send(user_string)` 就是一台 RCE 转发机；`__send__` 同罪。`public_send` 挡住私有，但**公共方法面仍然任意**（destroy_all、eval 类公共包装等照打）。
- 纪律：反射调度一律白名单映射——`OPS.fetch(name)`（未命中 KeyError → 4xx），映射表冻结于 boot；"拿方法名当协议"的设计（`send("handle_#{event}")`）改成注册表模式（39 节宏收集）。

```ruby
OPS = {
  "up"   => ->(o) { o.increment },
  "down" => ->(o) { o.decrement },
}.freeze
op = OPS.fetch(params[:op]) { raise UnknownOp }   # 白名单即 API 契约
op.call(order)
```

- 坑：`public_send` 给人虚假安全感——公共面里有副作用大户；白名单同样必需，public_send 只当二道闸。
- 静态哨兵：RuboCop 禁"send 带非字面量参数"的写法；Brakeman 扫 Rails 面；Sorbet 对动态 send 降级报错——三层工具都指向同一句"别把用户输入当方法名"。
- 坑：method_missing + send 组合（35 节末）——转发前判断可见性，否则私有方法被远程可达。

## 39. 类宏 DSL 设计：配置面的工程化

- 生产级模式三步：**声明收集**（宏方法只登记：名字、选项、块——存进 base 单例的注册表，33 节钩子骨架）；**boot 末物化**（统一校验+生成方法/路由，fail-fast：用错在启动期爆，带文件行号，绝不拖到首个请求）；**内省暴露**（`Registry.all` 给文档/调试/测试）。
- 这个形状的好处：错误可定位（物化期一次性报全清单）、行为可预测（运行期零元编程）、可测试（注册表本身就是断言面）。

```ruby
class Api
  @routes = {}                          # 类单例：注册表宿主
  class << self
    attr_reader :routes
    def get(path, to:) = routes[path] = to   # 声明：只收集
  end
end
# boot 末（railtie/initializer 尾段）：
Api.routes.freeze                       # 物化：冲突/缺项此时全部爆掉
Api.routes.each_value { |to| Object.const_get(to) }  # 校验目标存在
```

- 坑：宏立即作用（登记时就 define_method）——中间态类被部分物化，boot 中途异常会留下半套方法；收集与物化分离后这个类问题整体消失。
- 坑：注册表不 freeze——部署后还能有人 reopen 追加（43 节猴补）；冻结即"DSL 只在 boot 期开放"的硬保证。
- 测试接口：断言 `Api.routes.keys` 与文档清单一致——DSL 的 API 面用数据测试，不必逐个调用验证。

## 40. DSL 反模式：魔法浓度与可调试性预算

- 反模式清单（评审对照用）：**隐式时序**——必须先调 A 再调 B，忘调就在十万八千里外 NameError（把依赖编码进签名：参数化或 block 供给）；**全量 method_missing 转发**——拦截面过宽（35 节）+ 每次 miss 的性能税；**隐式全局状态**——宏改类级 ivar 且允许运行期 reopen 追加（39 节冻结解）；**错误不指路**——"invalid config"不带字段与文件位置。
- 魔法预算：一个 DSL 每加一层间接，可调试性掉一档；团队规则——新 DSL 特性必须带 ①使用示例 ②内省入口（能打印当前注册结果）③两个以上真实用例，缺一不合。

```ruby
# 反面：隐式时序 + 错误不指路
setup_logger                 # 忘了调 → register 在远处 NameError
register :payment
# 正面：依赖显式进签名，配错当场指认
register :payment, logger: @logger
raise ConfigError, "payment.logger missing (set in #{__FILE__})" if @logger.nil?
```

- 坑："聪明"的 DSL 把 Ruby 关键字/标点重载（`method_missing` 处理 `!`/`?` 后缀的转义）——行为面无边界，永远测不完；语法糖的表面积就是 bug 的表面积。
- 坑：DSL 文档散在 README 与 issue 里——把"完整 DSL 面"固化成 RDoc/yard 任务（CI 生成），文档债与代码债一起还。

## 41. define_method 闭包泄漏与 binding 生命周期

- 机制：define_method 的方法体是**定义处的闭包**——捕获 self、局部变量、整条环境引用链，且方法几乎永生 ⇒ 被捕获的对象跟着永生。在请求/任务上下文里生成方法并捕获大响应体/大集合 = 教科书级"堆 dump 里对象被 iseq env 钉住"。
- 防御纪律：方法体只引用**常量与 self 的 ivar**，需要的数据调用时再取（传 id 而非对象）；define_method 只发生在 boot 期（25 节改类禁令的另一半理由）。

```ruby
# 反面：捕获请求级大对象 → 方法永生 ⇒ 对象永生
handler = define_method(:render) { @body = big_response.render }
# 正面：调用时取数，闭包零捕获
define_method(:render) { |id| @body = Store.fetch(id).render }
```

- 诊断路径：memory_profiler 的 retained by location（21 节）看到方法对象/定义点 → 堆 dump（23 节）反查持有链 `env -> iseq -> method`；确认后把生成挪到 boot 或改传 id。
- 关联坑：Method/UnbindedMethod 对象（`obj.method(:x)` 存进集合）同样钉住 self 与源环境——缓存 Method 对象前想清楚生命周期。
- 例外说明：boot 期、只引用小配置的闭包完全没问题——别把这条纪律扩大化成"禁 define_method"（26 节给了它的正确位置）。

## 42. 运行时生成与代码生成的时机选择

- 优先序（越左越稳）：**提交期生成**（rake codegen 产物进库：可 grep、零启动成本、diff 可审）> **boot 期生成**（define_method/模板，集中校验 fail-fast）> **请求期生成**（几乎总是错——缓存失效 25 节 + 闭包钉对象 41 节 + 无法预演故障面）。
- 提交期生成纪律：产物文件头打"GENERATED，勿手改"；CI 步骤"重新生成 + git diff --exit-code"守门——手改漂移当场红；生成器与产物同 PR 演进。

```ruby
# lib/tasks/codegen.rake
desc "从 schema 生成 API 类型文件"
task :codegen do
  File.write("app/generated/api_types.rb", ERB.new(TPL).result(schema_binding))
end
# CI：bundle exec rails codegen && git diff --exit-code -- app/generated
```

- 何时 boot 期生成是对的：面依赖运行期配置/环境（注册表按 feature flag 展开）、或产物必须精确反映部署实例状态；此时物化收口在初始化器尾部（39 节）。
- 坑：生成物忘了 RBI/文档同步（34 节）——静态影子与内省清单是生成管线的两个固定产出，不是可选项。
- 决策口诀：能给评审看 diff 的（提交期）不留给 boot，能 boot 定的不留给请求。

## 43. 猴子补丁纪律与 prepend 包装器

- 补丁形态选型：`prepend` 模块 + `super`（可叠加、可定位、可整体移除）是唯一现代形态；`alias_method` 链（旧 Rails 的 alias_method_chain 时代产物）不可叠加、顺序脆、必淘汰。refinements 做"局部行为"（30 节），全局补丁必须 prepend 且有姓名。
- 工程纪律：补丁集中目录（`lib/patches/`，一文件一意图）+ 自带测试（对被补对象的行为回归）+ boot 打印补丁清单（`Patched.ancestors` diff 进日志）留审计痕迹——线上行为异常先对补丁清单。

```ruby
module InstrumentedCreate                   # 名字表意：干什么一目了然
  def create(*args, **kwargs)
    ActiveSupport::Notifications.instrument("create.call") { super }
  end
end
SomeGem::Client.prepend(InstrumentedCreate)
# 审计：boot 后 puts SomeGem::Client.ancestors.first(2)
```

- 风险位：两个 gem 补同一方法 → 栈序决定行为，依赖顺序一改全变（Bundle 顺序就是运行时行为的一部分——最脆的隐式契约）；升级被补 gem 后补丁可能错位（方法改签名，super 参数不匹配）——gem 升级 PR 必须重跑补丁测试。
- 红线：不补 Object/Module 等根类（25 节全进程缓存代价）；发布的 gem 不对外部库做全局补丁（要补也得 refinements 或可选 require）。
- 坑：prepend 进单例类（`klass.singleton_class.prepend M`）对类方法生效——与 def self 的查找序交缠（27 节），调试看 `klass.singleton_class.ancestors`。

## 44. Prism 工具链：AST 处理的正式 API

- 3.4 起 Prism 是**默认解析器**且随 Ruby 发行（prism gem 与核心同源同版本）：`Prism.parse(src)` 返回 result——`.value`（AST）、`.errors`（带精确位置）、`.warnings`；错误信息质量比旧 Ripper 高一档。Ripper / RubyVM::AbstractSyntaxTree 进入弃用轨道——所有自研 lint/统计/迁移工具要在这一版周期里迁完（11 节升级清单⑥）。
- 迁移要点：Prism 节点是**类型化对象树**（CallNode/DefNode/...），不是旧 API 的裸数组/(:sym, line, ...) 三元组——重写遍历层而不是字段映射；visitor 模式（继承 Prism::Visitor 覆写 visit_xxx）替代手写递归。

```ruby
require "prism"
result = Prism.parse(File.read(path))
result.errors.each { |e| puts "#{e.message} @#{e.location.start_line}" }

class CallCounter < Prism::Visitor
  COUNTS = Hash.new(0)
  def visit_call_node(node) = COUNTS[node.name] += 1
end
result.value.accept(CallCounter.new)
```

- 性能面：Prism 比旧解析器快且线程安全（解析无共享可变态）——code review bot / RuboCop 后端类的工具直接受益；RuboCop 生态已在接 Prism 解析器（跟进其版本说明即可）。
- 坑：location 的列/偏移口径与编辑器/CI 注释格式对齐——迁移后行号差一多为"含 BOM/行尾口径"差异。
- 跨语言需求：Prism 有序列化 API（dump/load）——把解析放独立进程/服务，工具侧零解析成本。
## 45. GVL 现实：放行点、时间片与 C 扩展责任

- 一张表记牢：GVL（每进程一把，Ractor 各自独立）在**Ruby 字节码执行、VM 内部操作、GC 关键段**期间持有；在**阻塞 IO 等待**（read/write/select/sleep）、以及 C 扩展显式调用 `rb_thread_call_without_gvl` 期间放行；时间片约 100ms——CPU 密集线程再多也只是在轮流跑，总量不变。
- 最大隐形雷：C 扩展执行阻塞操作却**不放行 GVL**——整个进程所有线程（含 Puma 的其他请求线程、健康检查线程）全部冻结，表现为"整个 worker 假死"。老版本 tiny_tds/某些 DB 驱动历史上都犯过；升级驱动前 grep 其源码 changelog 确认 GVL 释放策略。

```ruby
# 快速验证驱动是否放行：另一线程的计数应持续走动
@running = true
heartbeat = Thread.new { n = 0; n += 1 while @running; n }
result = slow_driver.query!(big_sql)   # 计数几乎不走 => 驱动冻结全进程
@running = false
heartbeat.value
```

- 关联停顿源：`GC.start` 级的全量 major 持 GVL 时间随堆变大（13 节）——"大堆进程的 GC=全进程停顿"是容量规划输入。
- 坑：把"多线程跑得动"当并行证据——IO 等待掩盖了串行本质，CPU 一满就现形（46 节收益模型先算再写）。
- 审计位：进程的线程清单 `Thread.list` 打进启动日志；扩容前先确认你加的是"等待重叠"还是"切换开销"。

## 46. GVL 下多线程收益模型：IO 与 CPU 分野

- 唯一收益公式：多线程赚的是**等待重叠**（IO 并发），不是 CPU 并行。判据看每请求的 IO 等待占比：DB/HTTP/Redis fan-out 占比高 → 线程有真收益（Puma threads 提吞吐）；纯计算（大 JSON 解析、压缩、图像处理）→ 加线程零收益，还搭上上下文切换与栈内存，p99 变差。
- CPU 扩容只有三条路：Puma worker 进程（×核数）、Ractor（纯数据场景，56 节）、外置服务。选型前先量：单请求 CPU 时间 vs 等待时间，别靠感觉。

```ruby
# IO fan-out 的正确形态：线程数 ≈ 下游并发预算（不是越多越快）
paths = %w[a b c d]
pages = paths.map { |p| Thread.new { fetch(p) } }.map(&:value)
# CPU 密集这样写没有任何并行收益——只是换了个排队形状，还多付切换
```

- 量化落点：APM 的线程忙率与队列深度；`Thread.list.count` 与"run"态采样进指标；p95 随线程数上调反升就是过了拐点。
- 坑：线程数 > 数据库池大小 → 线程等连接比等 IO 还久（81 节 Puma×池尺寸联动）；每加一档 threads 就重审池与下游配额。
- 坑：fan-out 里混入一个 CPU 重活——整组被 GVL 串行化，长尾变全组长尾；重活下沉到专属队列/进程。

## 47. Mutex 与 Monitor 深水：重入、死锁与自愈

- Mutex **不可重入**：同线程二次 synchronize → ThreadError（"deadlock; recursive locking"）。真实触发位：缓存刷新路径嵌套调用（fetch 里 compute 又调 fetch）——要么重构非重叠，要么换 Monitor（`require "monitor"`，MonitorMixin 可重入）。
- 死锁两形态：双锁交叉（不同顺序拿两把锁）→ 全局锁序纪律或 try_lock+超时退避重试自愈；**Fiber 同线程持锁**、另一 Fiber 等待——永远没有线程切换，直接挂死（async 代码里跨 yield 持锁即死，58 节）。
- 套件纪律：锁内不做 IO（持锁时长=阻塞面）；条件同步用 ConditionVariable（`wait(mutex)` 放锁）或直接 Queue（48 节），别手写轮询。

```ruby
require "monitor"
class Cache
  include MonitorMixin                # 可重入：嵌套 synchronize 安全
  def fetch(key)
    synchronize { @store[key] ||= compute(key) }   # compute 内再进不死
  end
  def compute(key)
    synchronize { expensive(key) }    # Mutex 的话这里就 ThreadError 了
  end
end
```

- 坑：`synchronize` 里再 `sleep`——放行 GVL 但不放锁，等待者全堵；节奏控制放锁外。
- 坑：把 Mutex 当跨进程锁用（它只在进程内有效）——多 worker 场景要 DB advisory lock/Redis 分布式锁，别在 Puma workers 间"锁了个寂寞"。

## 48. Queue 与 SizedQueue：关闭语义与停机协议

- `Queue#close` 语义三件：close 后 `push` 抛 ClosedQueueError；`pop` **先排干存量**再返回 nil；已空再 `pop(true)` 抛 ThreadError。这是优雅停机的官方原语——比"注入 nil 毒丸"更明确（毒丸与合法 nil 数据二义）。
- 停机时序（背下来）：生产者停 → `queue.close` → worker 循环 `while (job = queue.pop)` 自然排干退出 → `join(timeout)` 带超时 → 超时未退的线程记名上报（50 节取消协议接管）。

```ruby
queue = SizedQueue.new(100)           # 有界：背压从此开始
workers = 4.times.map do
  Thread.new do
    while (job = queue.pop)           # close 后：排干 → nil → 退出
      process(job)
    end
  end
end
queue.close
workers.each { |w| w.join(30) or report_stuck(w) }
```

- 坑：worker 里 `next if job.nil?` 跳过——nil 是"关厂信号"不是数据，`while` 条件挂 nil 才对；停机协议与消费循环必须成对评审。
- 坑：close 时机早于"生产者全部停"——最后的 push 抛 ClosedQueueError 丢单；close 前用生产者计数归零做前置断言。
- SizedQueue 的 push 阻塞（满时）是**背压的免费实现**——生产端阻塞传导上游限流（49 节）；只用无界 Queue 的设计等于没有过载方案。

## 49. 无界队列与饥饿：背压设计

- 无界队列的死亡路径：burst → 队列深涨 → 内存涨（OOM 先到）+ 处理延迟涨（用户已经不需要结果了）→ 恢复后还要"补做"一波过期货。三个失败叠加，一个对策：**有界 + 显式过载策略**。
- 过载策略三选（写进设计文档，不是临时拍）：①SizedQueue 非阻塞 push + 拒绝（`push(x, true)` 救 ThreadError，计数+抛限流异常，上游回 429/503）；②丢弃策略（丢新/丢旧+计数告警——日志类可丢、订单类必拒）；③上游限流（令牌/并发预算）从源头压。

```ruby
q = SizedQueue.new(1_000)
begin
  q.push(job, true)                    # 非阻塞：满即抛
rescue ThreadError
  statsd.increment("jobs.rejected")    # 显式拒绝优于静默积压
  raise Backpressure, "queue full — upstream should retry with jitter"
end
```

- 监控口径：队列**深度**与**驻留时间**（入队到出队），不是只看消费速率；深度涨+驻留涨=饥饿在发生（消费端慢），深度涨+驻留平=只是 burst。
- 优先级饿死：多队列 strict 优先级会让低优先级在高峰永远排不到——sidekiq strict 模式与轮询模式的差异同理；显式选型并写注释，别默认 strict。
- 坑：重试风暴与背压打架——被拒任务立即重试=放大 3 倍流量；拒绝路径必须带抖动退避（101 节重试预算）。

## 50. Thread#kill 的危险与取消协议设计

- 事实：`Thread#kill`/`Thread#raise` 从外部打断线程——ensure 会跑，但**原子性已碎**：半写的文件、改到一半的状态机、提交一半的事务；被杀点在哪是运气，不是设计。
- 著名次生灾害：杀持 AR 连接的线程 → ensure 被跳过或断在中间 → 连接带着未回滚事务回到池里 → 下一个请求拿到脏连接读到未提交数据（连接投毒）。修法：这种场景宁可 `clear_active_connections!` 整池丢弃重连，也别复用可疑连接。

```ruby
# 外部干预后的连接兜底：宁可重连不用脏连接
def after_thread_kill!
  ActiveRecord::Base.clear_active_connections!
  ActiveRecord::Base.connection.verify!   # 校验/重连（新 AR checkout 自带）
end
```

- 替代协议：协作取消——共享 `@cancel = true` 标志 + 循环边界检查 + IO 用带超时调用（51 节），线程自己走到安全点退出；cancel token 的完成态要可观察（`join` 或 done 队列）。
- 最后手段分级：取消无效 → 看门狗（52 节）升级为**重启整个进程**——比留一个半死线程在池里强；单线程级 kill 只用于诊断期取证。
- 坑：`Thread#kill` 在 ensure 里递归触发（kill 链）——看门设计要有"只杀一次"的闸。

## 51. Timeout 陷阱：Thread#raise 与 ensure 原子性

- 机制：`Timeout.timeout` 起伴随线程，到点往目标线程 `Thread#raise Timeout::Error`——异常可落在**任意两个字节码之间**，包括 ensure 的两步之间：文件关了一半、锁释放了但状态没清、事务 rollback 断在 journal 中。所有"用 Timeout 包一切"的代码都在赌概率。
- 安全面（可用）：可中断的纯循环（循环边界检查标志）、单步幂等操作。危险面（禁用）：多步资源序列、驱动内部调用（驱动有自己的超时通道）。

```ruby
# 层内超时（安全形态）：驱动在 IO 边界检查，状态机自洽
Net::HTTP.start(host, open_timeout: 2, read_timeout: 5) { |h| h.get(path) }
# Redis client: timeout 选项；PG: variables: { "statement_timeout": "3s" }
# 队列等待：q.pop(true) + sleep 抖动循环，或 sized push 超时
```

- 铁律：**超时设在层内**——DB statement_timeout/connect_timeout、HTTP read/open、Redis、队列操作原生超时；这些在安全位检查，不撕裂状态。Timeout 只保留"进程级看门"一职：兜底捕获后记日志+请求快速失败，不再做细粒度控制。
- 坑：层内超时值要形成预算链（connect < statement < request 总预算 < Puma worker_timeout），内层大于外层=白配。
- 坑：异常过滤时别把 Timeout::Error 误当业务错误重试——它是取消信号，应快速失败并归入"请求超时"桶（101 节可重试分类）。

## 52. 线程静默死亡与 report_on_exception 看门

- 默认行为链：非主线程未处理异常 → 只杀该线程；2.5+ 的 `report_on_exception` 会往 stderr 打一段"unhandled exception"——**打完就完**：不抛给主线程、不重启、不影响主流程。生产日志里这段 stderr 常被滚动吞掉，问题被掩埋到事故放大。
- 生产姿势：每个长命线程包**监督壳**——顶层 rescue 上报（指标+trace）后按需重建；`Thread#value`（join）会重抛异常，批处理线程组用 value 收割让失败显形；`abort_on_exception = true` 只用于"任何线程死都必须倒"的进程（worker/sidekiq 类）。

```ruby
def supervised(name)
  Thread.new do
    Thread.current.name = name            # 日志/堆栈里可认领
    yield
  rescue Exception => e                   # 看门壳：全捕上报，但必须重抛
    report_fatal(e, thread: name)         # 指标 + trace + 线程名单
    raise                                 # 让死亡可见（勿吞 SIGTERM，83 节）
  end
end
```

- 指标看门：`Thread.list.size` 与"各线程角色存活表"进 gauge——线程泄漏（越涨越多）与静默死亡（角色消失）都从这条曲线先露头；比事故日志早一个身位。
- 坑：rescue Exception 的看门壳必须 re-raise（如上）——否则 83 节的"吞 SIGTERM 部署卡死"从线程版重演。
- 坑：RSpec/Minitest 不会收割测试里起的孤儿线程异常——测试显形不了这个 bug；用 95 节的事务边界外知识（线程看不到未提交数据）反推线程行为差异。

## 53. Ractor 现状：experimental 的真实边界

- 状态（3.4/3.5 世代）：仍是 experimental——**使用即告警**，API 会动（3.5 引入 Ractor::Port 作为新通信入口，旧的 receive/yield 路线进入让位轨道，55 节）；但稳定性、调试器、与生态的兼容在持续收敛。
- 三条硬边界：①非主 Ractor **不能改已有类/模块**（定义方法即抛错）——意味着 monkey-patch 式库、Rails 的动态加载全不可用；②绝大多数 gem 未声明 Ractor-safe——Rails/AR 整体出局，进 Ractor 的代码要全链纯 Ruby + 显式数据进出；③调试/观测工具链薄弱（堆 dump、profiler 覆盖不全）。

```ruby
worker = Ractor.new do
  loop do
    chunk = Ractor.receive        # 消息入界即深拷贝（54 节代价模型）
    Ractor.yield transform(chunk) # 出口同样拷贝；Port 时代写法见 55 节
  end
end
```

- 适用画像（现在就成立的）：单进程内**纯数据 CPU 并行**——数据管道、批量转换、推理前处理：输入可序列化/可共享、无 IO 依赖、算法纯 Ruby。
- 不适用画像：web 请求处理、任何要碰 AR/文件句柄/全局缓存的路径——进程模型（Puma worker/独立 job 进程）在工程成本上碾压。
- 评估纪律：上 Ractor 前跑"依赖 Ractor-safe 审计"（gemspec 里搜 Ractor 声明 + 实测），任何一环不支持即回退进程模型。

## 54. Ractor 隔离模型：可共享性与深拷贝代价

- 隔离的物理形态：每个 Ractor 有独立对象堆 + 独立 GVL → 真并行（这是它存在的全部理由）。通信两态：**shareable 直传**（冻结对象、立即值、Class/Module）与**深拷贝**（整棵对象图复制，环已处理）——拷贝税是 Ractor 架构的第一成本项。
- `Ractor.make_shareable(obj)`：递归冻结成员使其可共享（含实例变量级 freeze）——不能含 IO/不可冻的句柄；`Ractor.move(obj)`：所有权转移（原端再触即错），大缓冲想免拷贝用 move，但生命周期要重新设计（发送方彻底放手）。

```ruby
payload = Ractor.make_shareable({ rows: rows, at: Time.now.to_i })
worker.send(payload)              # shareable：零拷贝直传
# 反面：worker.send(order_with_associations)
# —— 对象图深拷贝每发一次付一次，10MB 关联树 × 1000 任务 = 10GB 级搬运
```

- 架构法则：**传 id/分片，不传模型树**——Ractor 内自己查数据（只读副本/轻客户端）或纯函数式输入；拷贝税通常吃掉并行收益，这不是调优能救的，是接口形状问题。
- 坑：make_shareable 深冻一切——共享前想清楚"这数据以后还改吗"；共享后不可改是协议的一部分。
- 坑：主 Ractor 里的全局（$vars、类状态）在子 Ractor 不可达——每个 Ractor 自持配置副本；配置分发走消息，别指望全局单例。

## 55. Ractor::Port：新一代通信原语

- 3.5 的定位：Port 是官方给 Ractor 间通信的**新一代同步原语**——把"往哪个通道收发"从 Ractor 对象本身解耦出来；旧 receive/yield 双方法直连路线进入让位轨道（后续版本已开始移除旧方法、以 Port 体系收口）。新代码直接按 Port 写，别再扩旧 API 面。
- 设计红利：多生产者/多消费者共享同一通道成为一等公民（旧 API 里"一个 Ractor 一个收件箱"的拓扑限制被打破）；生命周期解耦——发送端不需要持有具体 worker Ractor 的引用。

```ruby
# 形态示意（API 细节以 3.5 NEWS 与 ri Ractor::Port 为准再落码）：
# inbox  = Ractor::Port.new   # 任务入口通道
# outbox = Ractor::Port.new   # 结果回收通道
# 8.times { Worker.new(inbox, outbox) }   # 池从 inbox 取活、往 outbox 回写
# inbox.send(chunk)
# result = outbox.receive
```

- 池化模式（主用例）：入口 Port + N 个 worker Ractor + 结果 Port；背压用入口的容量语义/队列深度指标外置实现（49 节背压纪律在 Ractor 侧同样成立）。
- 迁移纪律：旧 `send/receive` 对的代码收敛成 Port 对；耦合"发送方知道接收方"的写法是坏味道，重构窗口就在版本切换这一版。
- 坑：Port 的关闭/停机语义与 Queue#close（48 节）不是一套 API——worker 循环的退出协议要按 Port 文档单独设计，别把 Queue 的 while-pop 模板照抄。

## 56. Ractor 生产架构决策：值不值得上

- 决策序（先问进程）：①**多进程能不能解决？**（Puma worker × 核、独立 job 进程）——能解决就上进程：无隔离限制、全部 gem 可用、观测成熟；②单进程内 CPU 并行是刚需且数据可共享/可序列化 → Ractor 值得做；③依赖链里任何一个 gem 不 Ractor-safe → 回到 ①（53 节审计）。
- 成本核算：Ractor = 拷贝税（消息进出，54 节）但零序列化协议开销（原生对象拷贝，不走 JSON/Marshal）；进程 = IPC 序列化税 + 启动/内存复制成本。小数据高吞吐 → Ractor 优；大对象图 → 进程 + 传 id 优。

```ruby
# 判据速查（贴在设计文档首页）：
# ① 纯函数式数据变换（无 IO、无全局态）        => Ractor 候选
# ② 要碰 AR / 文件句柄 / 全局缓存 / 动态类定义   => 进程模型，没有第二个选项
# ③ 单条消息 > 100KB 或含大关联树               => 传 id 或分片，否则两边都亏
# ④ 需要 grep 得到的栈和成熟的火焰图             => 进程模型（观测工具先于性能）
```

- 落地护栏：灰度先跑**影子进程**（同输入、双实现对比输出）验证正确性与耗时；Ractor 数、通道深度、单任务耗时进指标；异常率与主进程分开看板。
- 何时重评：Ractor 转 stable、Port 体系定型、或你要的 gem（如 AR 的只读副本形态）声明支持——三条任一变化才重启评估，别按季度习惯性纠结。
- 反模式重申：为"跟上潮流"把 IO 密集 worker 改 Ractor——IO 并发线程/Fiber 就够（46、58 节），Ractor 的收益只在 CPU。
## 57. Fiber 深水：栈、泄漏与 blocking 检测

- 栈账本：每个 Fiber 独立 VM 栈 + 机器栈段，尺寸由 `RUBY_FIBER_VM_STACK_SIZE` / `RUBY_FIBER_MACHINE_STACK_SIZE` 环境变量控制（数量级几十到几百 KiB/纤）。“百万纤并发”的架构图先过内存关：10 万纤常驻就是数 GB 起步；**纤数预算必须写进设计文档**，和连接池预算同一张表。
- 泄漏形态①：**孤儿纤**——resume 后永久挂起（等待一个永远不会来的事件、事件源已关闭但没人 terminate 它）。挂起纤的整条栈被扫描，栈上引用的对象图全部保活：堆 dump 里“找不到 retainer”的泄漏，先查 `ObjectSpace.each_object(Fiber).count` 趋势（23 节差分法在纤维度再跑一遍）。
- 泄漏形态②：纤内捕获的 binding/块变量被外部持有（把纤的 Proc 存进注册表）——41 节的闭包税在纤上翻倍。
- `Fiber#blocking?` 是纪律入口：非阻塞纤（挂了 scheduler）里做原生阻塞调用 = 整个调度循环卡死。库作者守则——进入阻塞段前断言，fail-fast 而不是挂死（60 节 sigdump 里你会看到这种尸检现场）。

```ruby
# 库代码里的防御式断言（Fiber scheduler 环境下）
def fetch(url)
  if Fiber.current.respond_to?(:blocking?) && !Fiber.current.blocking?
    raise BlockingCallError, "blocking net call inside async fiber: #{url}"
  end
  Net::HTTP.get(URI(url))     # 只允许在 blocking 纤程 / 普通线程里跑
end
# 纤生命周期：永远有明确关闭协议，别造"永远 loop"的纤
worker = Fiber.new { loop { work or break } }   # break 是出口
worker.resume
```

- 坑：纤泄漏不报错、不涨异常率，只有 RSS 缓升（111 节判别树的一条分支）；纤数指标化，“只升不降”就是告警条件。
- 坑：纤内递归深度远小于主纤（栈小），深递归转迭代或显式加大 env 变量；`Fiber#raise` 从外部打断挂起纤（比 kill 线程干净），`dead?` 检查后再清理。
- 纪律：纤池 + 有界并发先行，无界纤生成等于无界内存；SSE/websocket 扇出、后台扫描是合理用例，“每请求一纤”的决策留给 58 节。

## 58. Fiber scheduler 协议与 async/Falcon 架构取舍

- 协议面（Ruby 3.1+）：`Fiber.set_scheduler(s)` 后，非阻塞纤里的 `io_wait/io_read/io_write/process_wait/kernel_sleep/address_resolve` 回调进 scheduler——scheduler 必须挂起当前纤、调度别的纤，绝不能阻塞宿主线程。`Fiber.schedule {}` 启动非阻塞纤；`Fiber.blocking {}` 显式回阻塞模式。
- **AR 连接池记账事故**（真实形态见过两次）：池按线程记账，async 的几百个纤全在同一个线程 → 池显示“1 个连接在用”，实际一个纤 checkout 后跨 `await` 持有，其余纤全部饿死；AR 内部的 Monitor 锁同理会把调度循环堵住。结论：**纤内不持 AR 连接跨 await**——DB 段用 `Fiber.blocking { }` 包裹，或把 DB 工作整体交给专用线程池。
- 选型判据：①纯 Ruby IO 聚合层（BFF 网关、爬虫、扇出）→ async gem / Falcon 收益直接；②Rails 单体 + AR → Puma 线程模型仍是正解：DB 驱动的阻塞读发生在 C 层、不走 Ruby IO 钩子，scheduler 拦不住，纤化只会放大 57 节的坑。

```ruby
# 合理用法：IO 聚合——三段等待重叠，总耗时≈最慢者
require "async"
Async do
  responses = ["a.com", "b.com", "c.com"].map { |h| Async::HTTP.fetch("https://#{h}") }
  bodies = responses.map(&:read)
end
# 反面：纤里跑 AR 查询再 await——连接被纤独占，池记账彻底失真
```

- Falcon = 建在 async 上的 Rack 服务器：每请求一纤，IO 并发不占线程；代价是生态假设——中间件与 gem 必须纤安全（`Thread.current[]` 在纤模型下全部变成共享 mutable，token/thread-local 缓存会串号）。
- 混合架构是主流答案：Puma 扛 Rails，Falcon/async 扛独立的无状态 IO 服务，两边 HTTP/队列缝合——各吃各的红利，互不污染线程模型。
- 坑：把“每连接一纤”照搬到有 DB 的 handler 上（连接池记账失真 + C 扩展阻塞双杀）；async 化改造的 PR 必须带连接持有分析，否则不许合。

## 59. 微基准纪律：benchmark-ips 与失真源

- 基本功：`Benchmark.ips` + `x.compare!`，看 mean 与误差区间——±区间重叠 = 无结论；`x.report` 命名可读；warmup 与采样时长给足（默认太短时手调 `time:`/`warmup:`）。
- **第二个指标是分配数**：`GC.stat(:total_allocated_objects)` 前后差值。耗时在纳秒级打平的两种写法，分配差 10 倍时，生产里的 GC 压力（13 节）会投票——只看时间的微基准只有半张底牌。

```ruby
require "benchmark/ips"
Benchmark.ips do |x|
  x.report("join")   { parts.join("-") }
  x.report("interp") { "#{parts[0]}-#{parts[1]}-#{parts[2]}" }
  x.compare!
end
# 分配口径必须配对记录（进同一份 CI 报告）
a0 = GC.stat(:total_allocated_objects)
work
a1 = GC.stat(:total_allocated_objects)
```

- 失真源清单：①微基准里 YJIT 全编译而生产只编译热区（结论系统性偏乐观——YJIT on/off 各跑一遍）；②字面量驻留/frozen 差异让循环里的字符串操作失真；③方法缓存全热（冷调用成本被抹掉）；④CI 容器 CPU 频率漂移与邻居噪声——专用 runner 或三次取中位（70 节门禁配套）。
- 结论边界（写给评审）：±20% 以内视为“等价”，不为 5% 重写代码，除非分配数差一个量级；“哪个框架快”永远不是微基准能回答的——请求级基准才有资格。
- 纪律：基准脚本进 repo（`bench/` 目录）带基线 JSON 与复现命令；不可复现的基准自动丧失评审资格。

## 60. 生产取样：rbspy、rbtrace 与 sigdump 分工

- 分工一句话：**rbspy 管“慢在哪”，rbtrace 管“现场问一句”，sigdump 管“卡死取证”**。三件都得在事故前就位：rbspy 装宿主机（容器要 `SYS_PTRACE` capability），rbtrace 必须 boot 期 `require "rbtrace"`（事后无法注入），sigdump 进 Gemfile。
- rbspy：外部取样（`rbspy record --pid PID`），零代码侵入、~1-2% 开销、火焰图直出。解读记着 GVL：CPU 侧火焰图天然偏向持锁线程，“采样多”不等于“真在干活”，等 GVL 的线程会在取锁点露头。
- rbtrace：按需对活进程求值与追踪——`rbtrace -p PID -e 'GC.stat'`、池水位、线程计数，还能挂自定义 fire 事件现场开闸；平时零成本，信号驱动；代价是 require 面广，敏感环境要评审。

```ruby
# sigdump：一行 Gemfile，事故当晚最便宜的真相（gem "sigdump"）
# 触发：kill -CONT <pid>   产出 /tmp/sigdump-<pid>.log：全线程 Ruby 栈 + GC.stat
# puma 坑：puma 占用若干信号，sigdump 默认 SIGCONT 恰好避开主流信号
# rbtrace 快问快答（表达式必须无副作用且快）
#   rbtrace -p PID -e 'ActiveRecord::Base.connection_pool.stat'
#   rbtrace -p PID -e 'Thread.list.map { |t| t.status }.tally'
```

- 顺序纪律：告警进门先 `sigdump`（10 秒判断“大家在等什么”），要深挖 rbspy 取样 60 秒，要动态数据（池水位、锁持有）用 rbtrace；三者是 APM 请求级追踪的下一层，不互相替代。
- 坑：rbtrace 求值在目标进程里执行——大进程别全量 `Thread.list.map(&:backtrace)`，先 count 再抽样；rbspy 对超大进程（RSS 几十 GB）开销放大，降采样率。
- 坑：容器里没配 `SYS_PTRACE` 时 rbspy/rbtrace 全哑火——部署清单里“可观测性 capability”是独立一行，不是 PR 里顺带的。

## 61. 调用开销模型与内联边界

- 解释器侧模型：每次 `send` 走 inline cache（调用点缓存上次命中的类/方法），单态调用点接近一次哈希查找；多态点（同一调用点喂 N 个类）退化为全量查找。深继承链/Concern 叠层/委托塔每层都是一次 send + 栈帧，AR callback 链是最贵的公共案例。
- YJIT 改写账本：小方法被内联（attr_reader → 直接取 ivar），“拆小方法”不再交调用税；真正惩罚 YJIT 的是**巨型方法**（编译体积爆炸、side exit 多，68 节）与**动态派发**（send + 拼出来的方法名、method_missing 转发层）。
- 热路径拆法：循环体内 attr/哈希读取提升为局部变量；循环内不新建闭包（块捕获即分配）；类型分派用值分派（symbol/integer 的 case）而不是 `is_a?` 链——前者对优化器形状更友好。

```ruby
# 反面：循环内 2N 次 attr send + 每迭代块分配
total = items.sum { |i| i.price * i.qty }
# 热循环正解：局部化（attr 读取经 YJIT 内联后寄存器驻留）
sum = 0
items.each do |i|
  price = i.price
  qty   = i.qty
  sum  += price * qty
end
```

- 内联边界纪律：**不要手工内联**——把逻辑灌进 300 行大方法“省调用”是负优化（25 节方法缓存与 68 节编译双输）；正确方向是小叶子方法 + 单态调用点。
- 诊断：stackprof（wall + cpu 双模式）看 self/cumulative——调用塔在 cumulative 里现形；`RubyVM::YJIT.runtime_stats` 的 side exit 指向多态分派点；grep 热路径上的 `define_method`/`send(`/`delegate`，这些是查找成本集中地。

## 62. 字符串构建成本阶梯

- 实测阶梯（量级）：`buf << x`（摊还 O(1)，容量倍增）≈ `array.join(sep)`（C 实现一次遍历）< 插值 `"#{a}#{b}"`（单次分配多段追加）< `format`/`sprintf`（每次调用解析格式串）≪ 循环里 `s = s + x`（每次新分配，总 O(n²)）。
- 工程规则：热路径 `String.new(capacity: n)` 预留 + `<<` 追加，或攒数组一次 `join`；`format` 只留冷路径（可读性/i18n 胜纳秒）；`+` 只许“两段小字符串拼一次”，出现在循环里即驳回。

```ruby
# CSV 行构建：反面（循环 +，二次方）与正解（预留容量 + 摊还追加）
bad  = ""; cols.each { |c| bad = bad + c + "," }
good = String.new(capacity: cols.sum(&:bytesize) + cols.size)
cols.each { |c| good << c << "," }
# 大对象序列化：缓存最终字节串，而不是每次重新 generate（66 节联动）
Rails.cache.fetch("api/v1/orders/#{order.cache_key}", expires_in: 10.minutes) do
  Oj.dump(OrderSerializer.new(order).serializable_hash)
end
```

- 坑①：编码——`<<` 拼接不同编码触发兼容性检查甚至转码（成本 + 静默 mojibake）；入口统一 UTF-8，二进制管道统一 `b` 后缀字面量 + `Encoding::BINARY`。
- 坑②：frozen_string_literal 下 `""` 是新分配的可变空串——`str = ""; str << x` 安全；`"#{''}"` 这类“聪明写法”无收益还降可读性。
- 坑③：`format`/`%` 的隐藏成本在格式串解析与参数装箱，热循环里与插值差距拉开一个量级。
- 验证：benchmark-ips + 分配差值双指标（59 节）；`memory_profiler` 的 string 归因（21 节）直接指出“每请求 3MB 字符串”来自哪个循环。

## 63. 正则深水：回溯灾难与 ReDoS

- 灾难形状只有两族：**嵌套量词**（`(a+)+`、`(a*)*`、`(\w+\s?)*`——外层每失败一次，内层重扫全部组合）与**交替前缀重叠**（`(a|a)*`、`(ab|a)*`）。指数级回溯对 30 字符的恶意输入就能吃满一个核几十秒——这是可用性攻击面，不是理论。
- 定位法：给存量 pattern 喂 `"a" * 30 + "!"` 型对抗串，超时即中招；评审规则——凡消费用户输入的正则必须过嵌套量词检查，或自带 64 节的超时预算。

```ruby
# 反面：灾难形状（外层失败时内层全组合重试）
BAD = /^(a+)+$/
# 修法一：原子组——匹配成功后禁止回头重试
OK1 = /^(?>a+)$/
# 修法二：占有量词（Onigmo 原生支持）
OK2 = /\Aa*+\z/
# 修法三：这根本不需要正则——字面量判断不用 pattern
str == "expected" || str.start_with?("prefix_")   # 无回溯宇宙
```

- 修法优先级：①先问“能不能不用正则”（`include?`/`index`/`start_with?`/strscan 逐 token）；②结构化数据用真解析器（JSON/CSV stdlib）——**正则做形状校验，不做解析**；③必须正则时：原子组/占有量词钉死回溯、`\A...\z` 锚定、捕获最小化。
- 输入面枚举（105-108 节同思路）：路由 constraint、格式校验、富文本渲染、日志抓取、爬虫 URL 规则——全部吃外部字符串，每处标注 owner 与预算。
- 坑：`Regexp.new(user_template)`（用户可配 pattern 的“灵活”功能）= 把 ReDoS 权限发放给用户；此类功能必须限白名单语法或独立进程 + 超时沙箱。

## 64. Regexp.timeout 与正则预算

- 语义（3.2+）：`Regexp.timeout = 0.5` 全局兜底（秒，对**每次 match 调用**计时）；`Regexp.new(pat, timeout: 2)` 单点覆盖——字面量 `/.../` 没有单点语法，需要长预算的 pattern 必须走 `Regexp.new`。超时在 match 调用点抛 `Regexp::TimeoutError`，引擎中断是安全的。
- 预算分层：全局保守（250ms-1s，覆盖所有无人认领的正则）；输入面（63 节清单）短预算；已知重型合法 pattern（大日志行扫描）单点放宽。**输入长度截断先行，超时是第二道墙**——5MB 行先切再匹配，别指望 timeout 替你做容量管理。

```ruby
Regexp.timeout = 0.5  # boot 期全局兜底（config/initializers）

HEAVY = Regexp.new(/\A(?<ts>\d+) .+ERROR (?<msg>.+)\z/, timeout: 3)

def match_line(line)
  line = line.byteslice(0, 65_536)          # 第一道墙：长度
  HEAVY.match(line)
rescue Regexp::TimeoutError                 # 在输入边界翻译，不是 500
  ErrorReporter.warn("regex_budget", input: line.byteslice(0, 200))
  nil
end
```

- 坑①：循环里多次 match，**每次调用都有新预算**——总时长 = 次数 × 预算，无界；要么限次数，要么对整批套外层超时兜底。
- 坑②：rescue 后在同一输入上重试 = DoS 自己（超时→重试→超时）；正确动作是降级（跳过 + 样本上报），并记录 pattern 与截断输入供调优。
- 坑③：TimeoutError 被上层当普通异常吞成 500（104 节），你就失去了“这是攻击面信号”的可见性——日志必须能按这类错误聚类告警。

## 65. 容器选型：内建 Set 与复杂度账本

- 时间线：3.2 起 Set 内建（不再 `require "set"`），3.5 起 C 重写——构造与 `include?` 显著提速；但**复杂度账本从 3.0 起就该按 O(1) 记**，选型决策不能等实现优化。
- 账本核心条目：`Array#include?` O(n)——放进循环就是 O(n×m)，生产里最常见的“函数都没错但整体是二次方”的来源；Set/Hash 查找 O(1) 均摊；`uniq` 保序去重一次过；`tally` 计数一次过（替代 group_by + size 组合）；一次性差/交/并内建已哈希化，别手写循环。

```ruby
# 反面：循环内 O(n) 成员检查（每行 × 每 tag 全表扫）
rows.select { |r| tags.include?(r.tag_id) }            # O(rows × tags)
# 正解：循环外一次建索引，循环内 O(1)
tag_ids = tags.to_set { |t| t.id }
rows.select { |r| tag_ids.include?(r.tag_id) }         # O(rows + tags)
# 大集合差集：先转 Set 再差，避免逐个 include? 的二次方回填扫描
need_backfill = Set.new(all_ids) - Set.new(done_ids)
```

- 改造纪律：热点路径 review 专门盯 `include?`/`index`/`delete` 出现在 `each` 体内；AR 场景配套 `pluck` 先取列再建 Set——别让每行一次 DB 查询的隐形 N+1 伪装成容器问题（84 节）。
- 坑①：`SortedSet` 在 3.0+ 已拆去 `sorted_set` gem——老代码 `require "set"` 后用 SortedSet 是隐藏依赖炸点（CI 装上了、生产镜像没装）。
- 坑②：Set 可变——跨请求缓存的 Set 必须 freeze；共享“配置 Set”被请求代码 mutate 是一类诡异并发 bug 的根因。
- 坑③：超大 Set 的内存与 Hash 同级（entries + 桶）；亿级 key 用 Set 不如 Bloom filter 或数据库索引——账本里要有内存列，不只时间复杂度。

## 66. 序列化成本：JSON、Oj 与 MessagePack

- 三者定位：stdlib JSON（C ext，够快、零依赖面）；Oj（generate/parse 再快一截、模式丰富）；MessagePack（二进制、体积小 30-50%，内部 RPC/缓存信封专用）。**Marshal 不在候选里**——它的位置只在进程内/极短生命周期 memo（105 节讲为什么禁外传）。
- `Oj.optimize_rails` 是行为面变更而非纯提速：接管 Rails 的 to_json 管线后，日期/BigDecimal/编码的输出细节可能与 AS encoder 有微差。上线门禁 = 全量测试套件 + 金样本 diff（录一批真实 API 响应，切引擎后逐字节比对），Oj 版本 pin 死。

```ruby
# 内部服务信封：msgpack + 显式类型注册 + string keys（跨语言契约）
FACTORY = MessagePack::Factory.new
FACTORY.register_type(0x01, Time, packer: :to_i, unpacker: ->(t) { Time.at(t) })
payload = FACTORY.dump({ "event" => "order.paid", "id" => id, "at" => now })
# 热点 API：缓存最终 JSON 字节，而不是缓存对象再每请求序列化
Oj.dump(OrderSerializer.new(order).serializable_hash)
```

- 分配视角（62 节同理）：`JSON.generate` 大树 = 一次性大分配；热 API 的正确缓存粒度是**序列化后的字符串**——命中路径零序列化、零分配，比缓存对象再序列化省一个数量级。
- 坑①：msgpack 的 key 是裸 bytes——没有 schema 纪律（约定 string key、版本号字段）就是无协议债；symbol key 进 msgpack 会整表展开。
- 坑②：跨部署缓存禁止 Marshal——类结构一改（加个 ivar），旧 blob 复活出来的对象半残（缺字段不报错、用时炸）；跨进程/跨版本一律 JSON/msgpack + 显式版本字段。
- 坑③：Oj 的 mode 语义显式声明（`:rails` vs `:compat` vs `:strict`）——升级时“默认 mode”漂移正是金样本 diff 要抓的东西。

## 67. IO 批处理与 fsync 成本

- 成本阶梯：`write`（进页缓存，µs 级）≪ `flush`（Ruby 用户态缓冲 → 内核，仍是 µs 级）≪ `fsync`（内核页缓存 → 持久介质，NVMe 亚毫秒到毫秒级，云盘再放大；比 write 慢约三个数量级）。**持久化的钱花在 fsync 上**——性能预算先数 fsync 次数。
- 第一戒律：不要“每记录一次 write + fsync”。批量合并：内存缓冲到 N 条或 T 毫秒再落一次盘；需要持久保证用 group commit（一批共享一次 fsync）——Redis AOF everysec 就是这个模式的教科书。

```ruby
# 日志管道：N 条缓冲 + 单 write；IO#sync=false 让用户态缓冲干活
LOG = File.open("events.log", "a").tap { |f| f.sync = false }
buf = []
flush = -> { LOG.write(buf.join); buf.clear }
records.each do |r|
  buf << format("%s|%s|%d\n", r.at.utc, r.kind, r.val)
  flush.call if buf.size >= 1000     # 批量阈值触发
end
flush.call
# 只有持久化关键路径才 fsync，且按组：LOG.fsync（每批一次，不是每条一次）
```

- 坑①：`sync = true` 的隐蔽税——每条日志一个 syscall（慢 10 倍以上）；某些合规要求开着它，那就先降日志量或走异步 logger（写线程 + 队列 + 显式 drop 策略）。
- 坑②：`flush` 不是持久化——进程死了数据还在页缓存（内核补落盘），宿主机断电才丢；真正的 durability 只认 fsync/fdatasync。
- 架构位：应用侧只管追加与落盘节奏，rotate/压缩/外发交给日志进程（vector/fluent）；把 fsync 责任交给本地 WAL 型组件（SQLite 76 节、消息队列）比自研双保险靠谱。
- 量化：日志管道吞吐上不去，strace 一分钟数每秒 write 与 fsync 次数——九成问题一眼可见。

## 68. 与 YJIT 协作写代码：分支形状与 bailout

- 三条对 YJIT 友好的纪律（都有机制依据）：①**小方法**——YJIT 按编译单元内联小方法，巨型方法撞 exec-size 上限直接放弃编译（bailout），永远跑解释器；②**单态调用点**——一个 send 喂一个类是 inline cache 命中，30 个类过同一个调用点 = megamorphic 回退慢路径；③**直呼其名**——`obj.publish` 让编译器看到常量方法名，`obj.send(name_var)` 的动态派发扼杀优化机会。
- side exit 归因：编译时只走 taken 分支，另一分支首次执行就 exit 回解释器。canary 上采样 `RubyVM::YJIT.runtime_stats`：side exit 率高且集中在某方法 → 那里的分支形状过多态/过稀疏；多态分派改数据驱动查表（Hash）常常比 case 链好。

```ruby
# 反面：多态上帝方法（30 个 handler 类过同一个 send——megamorphic）
def apply(event) = event.handler.call(context)
# 正面：注册表显式化 + 热路径小方法（YJIT 充分内联）
HANDLERS = { "order.paid" => PaidHandler, "order.shipped" => Shipped }
def fast_path(event)                      # 热路径三行
  HANDLERS.fetch(event.type) { Fallback }.new.handle(event)
end
```

- bailout 识别：`--yjit-stats`（仅 dev/canary）看 `compiled_iseq_count` 与拒绝原因桶；某核心方法永远不编译 → 查体积（拆方法）、查 define_method（def 定义的方法 YJIT 覆盖更好，26 节）、查超大 rescue 块。
- 纪律边界：不为 YJIT 写怪代码——“面向编译器编程”就过了；正确姿势是惯用的小方法 Ruby + stats 验证，每季度 canary stats 快照进基线（70 节门禁）。
- 常见误区：YJIT 开了不等于稳赢——大量 send/method_missing/闭包工厂的热路径收益打折；上线前用请求级基准（不是微基准）验证收益区间（5 节决策框架）。

## 69. C 扩展与 FFI 的边界成本

- 跨界税模型：每次 Ruby→C 调用付参数编列（Integer 即传即用、String 要编码检查 + 可能转码、Hash kwargs 最贵）+ 栈帧切换；单次几十纳秒，但“循环里逐条跨界”的写法会把 C 扩展用出比纯 Ruby 更差的表现（YJIT 时代尤甚）。**规则：要么把循环搬进 C（批量 API），要么留在 Ruby（YJIT 编译），不跨界逐条调。**
- FFI vs C 扩展：FFI 免编译矩阵、跨运行时（JRuby/TruffleRuby 可用），但每次调用过 libffi trampoline（比原生 C ext 慢一截）、拿不到 VALUE 级集成；C ext 快但背上每平台编译与符号面维护。选型先问“这个绑定要不要跨运行时/跨团队复用”。
- **GVL 责任**是最容易死人的点：长阻塞 native 调用不释放 GVL，整个 VM（所有线程）假死——外部库的 HTTP/压缩/数据库调用都算。C ext 必须用 `rb_thread_call_without_gvl` 包裹长段；FFI 侧把慢函数标注阻塞语义并放专用线程，别在请求线程里裸调。

```ruby
# TypedData 生命周期契约：native 资源必须挂 free 函数，VALUE 全局必须有 mark
const rb_data_type_t png_type = {
  .wrap_struct_name = "png",
  .function = { .dfree = png_free },   // 漏了 = native 内存泄漏（RSS 涨、堆不涨）
};
// 释放 GVL 的长操作骨架（所有 >1ms 的 native 段都要过这一关）
rb_thread_call_without_gvl(do_compress, &args, RUBY_UBF_IO, NULL);
```

- 对象生命周期守则：C 全局存 VALUE 必须有 mark 函数（否则 GC 后悬空——表现为随机 segfault，92 节取证）；TypedData + free 函数是底线模板。
- 内存观测断层：native 泄漏不进 Ruby 堆——`GC.stat` 平稳、ObjectSpace 干净、RSS 照涨（91 节判别树专用分支：valgrind/heaptrack 复现，或 `MALLOC_CONF=stats_print` 看分配面）。
- 坑：`FFI::Pointer` 手工管理——拿到的 buffer 记得显式释放；attach 的 C 函数崩了是整进程崩（不是异常），边界内做防御检查。

## 70. 性能预算与 CI 回归门禁

- 预算的三张表：①每路由类 p95 预算（读/写/导出分开）；②每请求**分配数**预算（`GC.stat` 中间件差值——比耗时稳定得多，是更好的回归指标，20 节同款埋点）；③热 gem 操作的微基准基线（62、66 节那类）。预算数字来自现状分布（p50/p95 实测），不是拍脑袋。
- CI 门禁实现：独立 runner（或大阈值容忍共享 runner 噪声）+ 三次取中位 + 与 main 的基线 JSON 对比；只对大幅回归失败（耗时 +15% / 分配 +10%），小幅波动只记趋势。请求级烟测：CI 里 boot 精简环境打几百次样例路由，量耗时中位与分配差值。

```ruby
# 请求级分配记账中间件（生产也开着：预算看板的数据源）
class AllocBudget
  def initialize(app) = @app = app
  def call(env)
    before = GC.stat(:total_allocated_objects)
    status, headers, body = @app.call(env)
    delta = GC.stat(:total_allocated_objects) - before
    ALLOC_HIST.labels(route_of(env)).observe(delta)
    [status, headers, body]
  end
end
# bench/*.rb 输出 JSON 与 baseline.json 比对（伪码）：
#   fail if ips/baseline < 0.85 || allocs > baseline_allocs * 1.10
```

- yjit-stats 基线：nightly canary 采一次 `runtime_stats`（inline_code_size、side exit 率、编译拒绝数）入库存档；升级 Ruby/大重构后对比——这是发现“代码形状退化”的唯一手段（68 节）。
- 告警动作预先写死：预算 breach → 自动 ticket + 标注最近部署（110 节解剖流程联动），当周 rota 处理；预算只许降不许无声抬升（ratchet，99 节同款文化）。
- 坑：CI 性能数字天生噪声大——单点阈值必误报；中位数 + 宽阈值 + 趋势图（缓慢爬升靠人看斜率，门禁只拦悬崖）。

## 71. Rails 8 生产基线：Solid 三件套的架构意义

- 架构事实：Rails 8 新应用默认 Solid Queue（Active Job）、Solid Cache（Rails.cache）、Solid Cable（ActionCable pub/sub）——三个全走数据库（默认 SQLite，也支持 MySQL/Postgres），**默认栈里 Redis 从必需组件降级为可选**。这不是“省一个 gem”而是运维面减半：没有 Redis 的 HA、驱逐策略、内存预算、集群拓扑要管。
- “DB 承载三职”的账本两面：收益是单引擎故事（备份、监控、恢复技能复用，单盒部署闭环）；代价是同一个 DB 引擎同时吃缓存写入、队列表轮询、pubsub 插入——**三职必须有独立连接与独立文件/schema**（Rails 8 wiring 就是三个独立 sqlite 文件），并各配一份水位监控（73、74 节）。

```ruby
# Rails 8 新应用默认形态（config 侧判读）
# config.cache_store = :solid_cache_store            # 缓存行进 DB
# config.active_job.queue_adapter = :solid_queue     # 队列表进 DB
# config.action_cable.cable = "solid_cable"          # pubsub 表进 DB
# 三职独立连接（storage/ 下三个文件，写并发互不拖累）
# config/solid_cache.yml => cache: storage/production_cache.sqlite3
# config/solid_queue.yml => queue: storage/production_queue.sqlite3
```

- **迁移判据**（Solid → Redis 方向，逐件套独立判断）：Solid Queue：吞吐 × 时延 SLA——轮询取件有亚秒固有延迟，万级/秒或秒级 SLA 场景 Sidekiq+Redis 仍是答案（72 节）；Solid Cache：缓存体量 × 命中模式——GB 级热缓存塞 Postgres 要掂量备份膨胀与读库压力（74 节），极高 QPS 的 KV 读 Redis 快一个量级；Solid Cable：扇出时延容忍——轮询延迟够 UI 通知，不够实时流（75 节）。
- 反向判据（Redis → Solid）：单盒/小团队、Redis 是唯一的多余组件、维护成本 > 性能差值——删 Redis 是运维净收益。
- 坑：把“默认”当“最优”——默认是给单盒起点的；三件套各自的逃生门（adapter 一行换回 Redis）才是 Rails 8 设计的真正卖点，架构评审把判据表挂出来，别让“新技术”替 SLA 做决定。

## 72. Solid Queue 内幕：轮询、supervisor 与进程拓扑

- 拓扑：`bin/jobs` 起 supervisor，supervisor fork 两类子进程——**dispatcher**（把 due 的 scheduled/blocked 作业移进 ready，按 priority/run_at 排序）与 **worker**（轮询认领 ready 作业并执行，每 worker 配 queues 与 threads）。心跳注册进 `solid_queue_processes` 表，supervisor 靠它做存活监督。
- 认领机制：多 worker 抢同一批 ready 行用 `FOR UPDATE SKIP LOCACED` 语义（Postgres）/等价原子更新，不会重复认领；执行中的行进 `claimed_executions`，完成落 `finished_executions`，失败链路接 100/101 节的重试与 discard。轮询是亚秒级可配的**拉模型**——没有 Redis push，这是时延与吞吐的第一性差异。

```yaml
# config/queue.yml：拓扑即配置（贴生产值，别默认裸跑）
production:
  dispatchers:
    - polling_interval: 0.5
      batch_size: 500
  workers:
    - queues: [realtime]
      threads: 3
      polling_interval: 0.1      # 延迟敏感队列加密轮询
    - queues: [default, low]
      threads: 5
      polling_interval: 2.0      # 批处理放松，省 DB 轮询写放大
```

- Puma plugin 形态（`plugin :solid_queue`）：supervisor 随 puma worker 进程活——零额外进程管理，单盒甜点；代价是**作业线程与 web 线程共享 CPU 配额与停机命运**（83 节：puma 停 → 队列停，claimed 作业释放重跑）。独立 `bin/jobs`（systemd/Kamal accessory）是规模上去后的正解：web 与 worker 独立伸缩、独立重启。
- 特性面对表（替代 Sidekiq 评估）：concurrency controls（semaphores 限并发）≈ Sidekiq unique/限流；recurring tasks ≡ Sidekiq-cron；pauses（运行期暂停队列）；数值 priority。逐条对，缺的项就是你的迁移风险。
- 坑①：threads 与外部依赖并发配套——5 线程 × 每 job 3 个上游连接 = 瞬时 15 连接，上游限流先炸（82 节熔断纪律同款）。
- 坑②：轮询即 DB 写放大——SQLite 上队列轮询频率与主库写并发分开核算（76 节单写者），别让轮询吃掉唯一写者的预算。

## 73. Solid Queue 运维：独立 schema、堵塞与监控

- 独立 schema 是生产标配：solid_queue 用独立数据库连接 + 独立 schema（安装任务自带 migration 落表），主应用 schema dump 不被队列表污染；新环境部署清单里“solid_queue 的库/文件已建”是高频翻车点（`rails db:prepare` 之外单独确认）。SQLite wiring 下就是独立文件（storage/production_queue.sqlite3），Postgres 下是独立 schema/库。
- 监控三层：①**Mission Control**（Rails 8 内置队列 UI 引擎）看深度、失败集、进程列表；②指标：深度（ready/scheduled 分开）、**最老 ready 作业年龄**（SLA 指标，深度只是滞后指标）、失败率、discard 数；③告警：深度 × 年龄乘积超阈（93 节积压方程）。

```ruby
# 堵塞 runbook 三步定位（顺序固定）
# 1) 进程在吗？——心跳表（supervisor 死 vs worker 死是两种事故）
SolidQueue::Process.where("last_seen_at < ?", 5.seconds.ago).pluck(:kind, :name)
# 2) 作业卡在哪段？ready 堆积 = 认领不动；claimed 堆积 = 执行太慢
SolidQueue::ReadyExecution.count
SolidQueue::ClaimedExecution.joins(:process).count      # 在跑数 vs threads 上限
# 3) 最老的多老了？——先修 SLA 再修吞吐
SolidQueue::ReadyExecution.minimum(:created_at)
```

- 堵塞根因速查（概率排序）：①worker 不在（部署失误/`bin/jobs` 没起）——心跳表一眼见；②执行变慢（外部依赖时延↑，5 线程全挂在 30s 的 job 上）——claimed 年龄与 threads 配置对不上；③semaphore 耗尽（并发上限锁死同类作业）——semaphores 表查上限与等待者；④retry 风暴放大负载（101 节预算没配）——失败集与 enqueued 增速同涨。
- 断路恢复：worker 进程死亡（OOM/部署强杀）后，supervisor 通过心跳过期发现并**释放其 claimed 作业**重新派发——作业必须幂等（93 节是合同条款不是建议）；手动兜底：清死行 + 触发重派。
- 坑：把 solid_queue 表和业务表放同库同 schema“省事”——迁移、锁、备份窗口互相拖累，堵塞排查时一张慢查询日志混进队列表噪声；独立性是它的设计前提，不是优化项。

## 74. Solid Cache 内幕：LRU 删除与键存储

- 存储模型：条目就是行——key（完整键字符串 + 命名空间前缀）、字节 payload、时间戳；读 = 索引查找。**没有逐键 TTL 的惰性删除**：过期靠清理器批量处理，容量靠 LRU 阈值清理——`max_entries`（行数/字节水位）触发时**按 LRU 批量删除**最旧条目，删除分批削峰防“清理风暴”（一次性 DELETE 百万行 = 锁表 + 主从延迟）。
- 因为是行存储，键设计的成本直接落进 DB：键长进索引尺寸、键基数进表容量；**键要短、稳定、按形状分命名空间**（"orders/feed:v2:u#{id}:p#{page}"），不要把原始 params 序列化进键（无界键空间 = 无界表，86 节键策略复用）。

```yaml
# config/cache.yml（生产姿态）
production:
  databases: [cache_primary, cache_replica]   # 读库分流：读走副本
  store: { max_entries: 50_000_000 }          # 行数水位先算清 DB 与备份容量
# 同库 SQLite 场景：清理任务错峰执行（低峰维护窗口），别与业务写抢锁
```

- **读库分流**是“DB 承载缓存职”能否成立的关键一票：Solid Cache 支持 connects_to 多库/副本——缓存读流量必须与 OLTP 隔离（挂副本或独立库），否则热键 KV 读直接挤占业务查询的 buffer pool。
- 行数水位治理三步：①容量先算（max_entries × 平均行宽 vs 磁盘/备份预算）；②水位告警（行数、清理任务耗时、删除批速率）；③定期维护（Postgres 高删除表的 autovacuum 参数单独调优；SQLite 文件只增不缩，低峰 VACUUM 或重建）。
- 坑①：TTL 语义差异——条目过期不等于立刻消失（批量清理才删），读命中按时间戳过滤；别把 Solid Cache 当精确 TTL 存储。
- 坑②：大值缓存（整页 HTML、序列化树）让行宽爆炸 + 单行锁竞争——配合 62/66 节：缓存最终字节、限制单条上限、超大对象分层（碎片缓存 + 组装）。

## 75. Solid Cable 与 ActionCable 生产形态

- 机制：broadcast = 往 solid_cable 消息表 INSERT（channel + payload）；订阅端按订阅集**轮询**该表取新消息，取走后清理。延迟画像 = 轮询间隔 + DB 往返：人机 UI（通知、协作状态）够用；亚百毫秒实时流（行情、协同编辑高频操作）不够——那是 Redis pubsub/NATS/Postgres LISTEN-NOTIFY 的地盘。
- 判据一行：**扇出时延 SLA < 轮询间隔 → 别用 Solid Cable**；消息速率 × 订阅数也要过算（N 订阅者 × M 消息的表扫描压力）——高频广播会把消息表变成最热的表（74 节水位治理复用）。

```yaml
# config/cable.yml
production:
  adapter: solid_cable            # 消息表进 DB
  polling_interval: 0.1.seconds   # 按产品 SLA 定，不是抄默认
  message_expiry: 1.day
# 超线判据（写进架构笔记）：
#   p99 端到端推送 SLA < 250ms？ 每秒广播 > 数百条？ 订阅连接 > 数千？
#   任一命中 → 换 Redis adapter 或 AnyCable，一行配置的事，别硬扛
```

- ActionCable 生产形态（adapter 无关）：①连接是驻留资源（线程/事件槽 + 订阅簿记）——连接上限、心跳超时、断线清理显式配；②规模先行分域：cable 独立进程（只跑 cable 的服务）而不是挂在 web 进程——广播风暴时 web 不陪葬；③每个 channel 的订阅回调里做鉴权（channel 是公开入口，漏鉴权一个 stream = 数据外泄）。
- 混合现实：很多 Rails 8 应用 web+queue+cache 用 Solid、cable 单独配 Redis——三件套本来就允许逐件选择（71 节判据），不要“全有或全无”。
- 坑①：消息表靠清理任务回收——高广播量下 expiry 过宽 = 表无限涨；给 solid_cable 配自己的水位告警。
- 坑②：job 进程里 broadcast 写 DB（比 Redis 慢但一致性强）；**别把 broadcast 放事务前置回调**（after_commit 纪律）——所有 adapter 共有的坑，DB 型 adapter 里更容易撞见。

## 76. SQLite 生产姿态：PRAGMA 与 Rails 8 默认

- Rails 8 默认姿态：新应用生产环境默认 SQLite（适配器做了生产化：预编译语句、加载下的锁重试），配合三件套各占独立文件（storage/production.sqlite3 / queue / cache）。**独立文件不是洁癖**：单写者模型下，队列轮询写、缓存批量删除写、业务写挤同一个文件 = 互锁三输。
- 必配 PRAGMA 阈值：`journal_mode=WAL`（读写不互斥：多读 + 单写并行）、`synchronous=NORMAL`（WAL 下 fsync 只在 checkpoint——性能与持久性的务实平衡：最坏丢最后几笔已提交事务，换一个量级的写吞吐）、`busy_timeout`（锁等待显式化，5s 级而不是立刻报 SQLITE_BUSY）。

```yaml
# config/database.yml（生产基线；Rails 8 适配器默认已套 WAL，审计时核对）
production:
  adapter: sqlite3
  database: storage/production.sqlite3
# 三件套独立文件（默认 wiring，评审确认没被“合并省事”）：
#   storage/production.sqlite3 / production_queue.sqlite3 / production_cache.sqlite3
```

- 写并发边界写进架构约束：WAL 下仍是**单写者**——一条慢写事务（大导出、事务里夹外部调用）饿死所有写者，busy_timeout 帮饥饿不帮竞争；事务必须短（外部 IO 出事务）、批量写分批提交、checkpoint 频率按写量调（wal_autocheckpoint）。
- 持久化姿态：文件必须在真实磁盘（NFS/网络卷上的 SQLite 是自伤）；备份 = `VACUUM INTO` 快照或 **litestream** 持续复制到对象存储（SQLite 生产化的标配件，没有它别谈生产）；VACUUM 避开高峰（全库重写，独占）。
- 判据（何时不 SQLite）：多应用服务器共享写、写并发百级以上、长事务负载、需要网络多客户端直连——任一命中回 Postgres；单盒 + 读多写少 + 三件套分流，SQLite 是运维上最便宜的合法答案。

## 77. Kamal 2 部署模型：零停机与回滚

- 模型：宿主机常驻 **kamal-proxy**（Go 反代，占 80/443），应用跑容器；部署 = 拉新镜像 → 新容器在内部端口起来 → proxy 探活通过后**原子切换流量** → 旧容器排空退出。没有 nginx + 服务发现的经典配方，一个二进制 + 一个 deploy.yml。
- **回滚即重部署**：镜像不可变、层已缓存，`kamal rollback` 秒级把流量切回旧容器——回滚预案就是“上一版镜像还在不在仓库”，不是 git revert + 重 CI。这要求镜像 tag 严格按构建产物（SHA/序号），禁止复用 tag、禁止 latest。

```yaml
# config/deploy.yml（关键件的审计清单）
service: myapp
image: registry/myapp            # tag 由 kamal 版本号管理
servers: [web1, web2]
proxy:
  host: app.example.com           # 切换 = 探活 + 原子切，零停机由 drain 保证
boot:
  stop_timeout: 45                # SIGTERM 宽限 > puma 排空时长（83 节联动）
volumes:
  - "storage:/app/storage"        # SQLite/上传文件的宿主卷：不挂卷 = 部署丢库
accessories:
  litestream:                     # 备份 sidecar，不混进 web 容器
```

- 端口规划：proxy 占 80/443；每个 role 的容器端口由 kamal 分配（同机蓝绿需要两个端口）；宿主防火墙只开 proxy 端口——容器端口不外露是默认姿态。
- 排空细节联动 83 节：切换后旧容器收 SIGTERM，puma 停止接新连接等在途完成——**stop_timeout 必须大于最长请求**，否则 SIGKILL 半途掐死（在途 500 + 队列作业释放重跑，幂等合同生效）。
- 坑①：SQLite/本地文件应用必须显式 volumes——容器文件系统可抛弃，忘了挂卷的第一次部署就是数据恢复演练。
- 坑②：多机部署下 kamal-proxy 是每机一份、DNS 轮询入流量——全局负载均衡/健康摘除要外配；Kamal 不是完整 LB 故事。
- 坑③：资产与迁移：镜像内预构建（78 节 Propshaft 在 build 阶段出产物）→ 切换原子生效；迁移策略 = boot 前置任务 + 向后兼容迁移（112 节有反面教材）。

## 78. Propshaft 与资产指纹管线

- 哲学：Propshaft = 无转译管线——**指纹 + 路由**做完就收工。JS/CSS 由真正的构建器出（esbuild/bun，经 jsbundling/cssbundling 接线），Rails 只负责内容哈希指纹、dev 直出/prod 落 public/assets、视图中 digest URL 助手。Sprockets 的 `//= require` 指令与仓库内 Coffee/Sass 编译都不存在——把“打包”还给 JS 生态的架构决定。
- 指纹语义：内容哈希（不是 mtime）→ 内容不变 URL 不变 → `Cache-Control: public, max-age=31536000, immutable` 是免费午餐；改一字节 URL 变，浏览器零协商刷新。视图里一律 `stylesheet_link_tag`/`image_tag` 助手，**手写硬编码资产路径 = 绕过指纹 = 缓存幽灵**。

```erb
<%# 正解：永远走助手，URL 带 digest，CDN/浏览器可永久缓存 %>
<%= stylesheet_link_tag "application" %>
<%= image_tag "icons/paid.svg" %>
<%# 反面：硬编码路径——指纹机制失效，改了文件用户还看旧的 %>
<%# <img src="/assets/icons/paid.svg"> %>
```

- 生产形态：`assets:precompile` 在 **Docker build 阶段**执行（构建器 build → propshaft precompile → 产物进镜像）——Kamal 切换即原子生效（77 节）；运行时零编译、零 Ruby 参与资产。
- dev/prod 差异：dev 由中间件按需出资产（改完即生效、无预编译），prod 读 manifest——**指纹产物缺失（构建器没先跑）只会在生产显形**，CI 里跑一遍 `assets:precompile` 是最便宜的断言。
- Sprockets 迁移三步：①`//= require` 链改成构建器入口文件；②仓库里的 .coffee/.scss 迁到 JS 构建语系；③`config.assets.*` 旧配置清点删除。Propshaft 的 ERB compiler 仍可用，但别把新代码建立在它上面——依赖面越小升级越省。
- 坑：指纹只对经管线的文件负责——public/ 手放的文件没有 digest，更新要自带版本参数；CDN 回源按 digest 前缀配 immutable 缓存。

## 79. ActionText 内幕：富文本存储与 N+1 联动

- 存储模型：`has_rich_text :content` 挂独立表 `action_text_rich_texts`（多态：record_type/id + name + body HTML）。body 是 **Trix 的 HTML**，不是纯文本——查询、摘要、检索都不能直接用它；`to_plain_text` 是渲染级转换（贵），**列表/摘要场景必须冗余列**：before_save 抽 plain_text 存列，检索走全文索引。

```ruby
class Post < ApplicationRecord
  has_rich_text :content
  # N+1 的标准答案：列表查询必带预载 scope（生成 with_rich_text_<name>）
  #   Post.with_rich_text_content.each { |p| render p.content }   # 1+1 查询
  #   Post.all.each { |p| render p.content }                      # N+1
  before_save :sync_excerpt, if: :will_save_change_to_content?
  def sync_excerpt
    self.excerpt = content.to_plain_text.truncate(280)  # 冗余列：摘要/检索不碰富文本
  end
end
```

- **N+1 联动是 ActionText 最贵的隐藏账**：每条记录的 rich_text 是独立关联，列表渲染漏 preload = 每行一查；附件再加一层——`with_rich_text_content_and_embeds` 把内嵌 ActiveStorage 附件一并预载，漏了是每行每图查询。strict_loading（84 节）会在测试里把这两处直接揪出来。
- 附件链：Trix 内嵌附件是 `action-text-attachment` 标签带 **sgid**（签名全局 id）——渲染时解析 sgid → 找 attachment record → blob；宿主删除要处理 rich_text 的 dependent（孤儿 rich_texts 表膨胀是真实事故形态），附件宿主删除后渲染降级为空占位。
- 安全面：渲染走 ActionText 的 sanitize 白名单；**绝不**把存的 HTML 在别的上下文 `raw` 输出（邮件模板、导出页）——存储内容假设有恶意；扩白名单要过安全评审（105-108 节同思路）。
- 坑①：列表页把每行整段 HTML 拉出来（payload 巨大）——列表只取冗余摘要列，正文按需单查；同时救 DB 带宽和内存分配（20 节）。
- 坑②：`content.body.to_s`、`content.to_plain_text`、`content.render` 三种输出语义在评审里点名——拿错一个，要么 XSS 面、要么整页 HTML 进邮件标题。

## 80. Packwerk 与模块化单体：边界契约

- 定位：Packwerk 不做运行时隔离，只做**静态边界检查**——package（带 `package.yml` 的目录）间的常量引用必须声明在 dependencies，越界即 violation；`packwerk check` 全量检查，`package_todo.yml` 记录存量（ratchet 基线），CI 只拦**新增** violation。
- 渐进收紧既定路线：①给现有目录贴 package.yml（`enforce_dependencies: false`，先立地图）；②跑 check 生成 todo 基线；③CI `packwerk check` 阻止 todo 增长；④按季度消 todo（重构窗口随版本升级走）；⑤最后一层 `enforce_visibility`/layers：只有显式 public 的 API 可被依赖，包内自由重构。

```yaml
# app/packages/billing/package.yml —— 契约文件本身就是文档
enforce_dependencies: true
enforce_visibility: true          # 收紧终态：只有声明 public 的文件可见
dependencies:
  - "packages/shared_kernel"      # 依赖方向单向：billing -> kernel，反向即违规
public_folder: public/            # 对外 API 面：app/packages/billing/public/**
# CI 最简门禁：
#   bin/packwerk check && git diff --exit-code package_todo.yml
```

- 反模式（模块化单体两大死法）：**纳米包**（几百个碎片包，依赖图比代码还难读）与**万能中心包**（"shared"/"core" 被所有包依赖、无限膨胀——边界名存实亡）。守则：包数与团队结构对齐（Conway 优先），依赖方向必须 acyclic（layers 配置强制）。
- 契约的另一半是 API 面：`public/` 目录 = 对外承诺（签名稳定、有测试），包内其余随便重构——没有 visibility 收紧的 Packwerk 只防“引用错包”，不防“引用错层”。
- 坑①：todo.yml 膨胀无声——每周看行数趋势，增长即找作者；边界划分要跟 schema 与 team ownership 对表，否则每次变更都要跨包 PR，效率反噬。
- 坑②：Packwerk 只管常量引用，管不住运行时耦合（全局变量、YAML 共享配置、DB 外键跨包）——后三项进人工评审清单。

## 81. Puma 调优决策树：workers、threads 与 preload

- 决策树（按序回答）：①CPU 瓶颈（CPU 打满、p95 随流量线性涨）→ `workers = 物理核数`，threads 收到 1-4（GVL 下多线程帮不了 CPU）；②IO 瓶颈（CPU 低、p95 卡 DB/外呼）→ threads 16-32，**AR 池 = threads + 富余**（co-located Solid Queue 的连接另加）；③内存预算不够 workers → 先治单 worker RSS（90 节）再谈 workers 数。
- `preload_app!`：master 先加载应用再 fork workers → COW 共享（90 节）；代价 = **phased restart 不可用**（preload 下不支持）→ 用 `pumactl restart`（整组换血，短暂掉容量）。取舍公式：单体大应用（boot 30s+）→ preload 吃满 COW 红利；小应用/要平滑重启 → 不 preload 换 phased。

```ruby
# config/puma.rb（生产基线模板，注释即审计点）
workers Integer(ENV.fetch("PUMA_WORKERS", 4))    # RAM 预算反推：workers × 稳态RSS
threads_count Integer(ENV.fetch("RAILS_MAX_THREADS", 8))
threads threads_count, threads_count            # min=max：拒绝“先给少后扩”的延迟
preload_app!
# AR 池纪律：pool >= threads_count + (co-located solid_queue 的连接数)
# before_fork { ActiveRecord::Base.connection_pool.disconnect! }  # 按驱动核实
```

- workers 数也要按“单 worker 挂 = 掉多少容量”设计：2 workers 起步时一次 worker OOM（90 节）= 掉 50%，告警与重启预算按最坏值配。
- 信号语义速查（83 节全链路）：SIGTERM 优雅停；SIGUSR1 restart（preload 模式换血）；`phased-restart`（无 preload 时逐个换）；SIGUSR2 exec 热升级——容器时代几乎不用（Kamal 用蓝绿容器切换替代）。
- 坑①：threads 拉满但外呼没超时（82 节）= 池被慢外呼占满，调优白做——先修依赖超时再调线程。
- 坑②：min < max 的“弹性”在生产是延迟陷阱（低水位时新请求等线程生长）——生产 min=max 是保守但可预测的选择。
- 坑③：workers=0（单进程模式）跑生产是容量单点 + 停机即全停；哪怕单核机也 workers 1 起（隔离 + 独立重启单元）。

## 82. Puma 线程堵塞诊断与熔断

- jamming 症状（三件同现即确诊）：`Puma.stats` 的 `busy == max_threads` 持续、`pool_capacity == 0`、进程 CPU 却低（大家都在等）——外加反向代理侧队列增长/502。含义：线程池被阻塞调用占满，新请求在门口排队等线程。
- 取证顺序：①`Puma.stats`（控制端点/指标）确认池满；②**sigdump**（60 节）全线程栈——十几条栈都停在 `Net::HTTP#read`/`PG` 调用就是外呼/DB；③rbtrace 问池水位与依赖计数器；④关联最近变更（新外呼、新 gem、上游公告）。

```ruby
# 反面（能跑出整场事故的代码）：默认 60s 超时 × 8 线程
uri = URI("https://partner.example/api")
Net::HTTP.get(uri)      # open/read 默认 60s——上游挂起 = 线程钉死 60s
# 正面：全局外呼纪律（一段代码说清所有超时）
http = Net::HTTP.new(uri.host, uri.port)
http.open_timeout  = 2
http.read_timeout  = 5     # 预算按依赖 SLA 定，永远小于代理层超时
http.write_timeout = 5
# 池侧熔断：per-dependency 信号量限并发 + 健康开关（circuit box 模式）
```

- 熔断三层：①**超时兜底**——每外呼显式 open/read/write 超时，缺一不批（裸 `Net::HTTP.get` 在评审里当 bug 处理）；②**并发闸**——per-dependency 信号量：上游能扛多少放多少，超了快速失败而不是排队共沉；③**断路器**——连续失败开闸、半开探测恢复，上游半死时最先保住自己的线程池。
- 负载削减：代理层 `max_connections` 限制 + 应用中间件在 `pool_capacity == 0` 持续时直接 503（快速失败优于 30 秒队列后超时）；`queue_requests` 的排队行为显式过一遍。
- 指标固化：Puma 指标导出（busy/idle/backlog/pool_capacity）进 Prometheus，**告警条件 = pool_capacity==0 持续 2 分钟**（比“5xx 增多”早 10 分钟）；外呼侧 per-dependency 延迟直方图与它同图（110 节解剖复用）。
- 坑：长轮询/`rack.hijack`/大文件流式响应会合法占线程很久——这类路由单独起实例（75 节分域思路），别混进普通 API 池。

## 83. 优雅停机全链路：信号与 rescue Exception 惨案

- 全链路（Kamal/容器形态）：`docker stop` → SIGTERM → puma 停 listener（拒新）→ 排空在途 → worker 退出 → 宽限期到 → **SIGKILL**。任何一环卡住，终点都是 SIGKILL：在途请求半途而废、Solid Queue 的 claimed 作业释放重跑（幂等合同生效，73 节）。
- **rescue Exception 惨案**（亲手修过的形态）：入口层 `rescue Exception => e; log; end`——把 SIGTERM 引发的 `SignalException`/`SystemExit`/`Interrupt` 一并吞掉，停机处理永远不跑，puma 干等，宽限一到 SIGKILL 全体陪葬；同族形态还有 at_exit 里长等待、middleware 兜底 rescue。**铁律：rescue Exception 必须立刻转发信号类异常**（或干脆别 rescue Exception——104 节）。

```ruby
# 信号类异常转发模板（唯一合法的 rescue Exception 形态）
def call(env)
  handle(env)
rescue Exception => e           # 只有必须兜底 Exception 的边界才允许
  raise if e.is_a?(SignalException) || e.is_a?(SystemExit) || e.is_a?(Interrupt)
  report(e)
  [500, HEADERS, ["internal error"]]
end
# 停机预算两处对表：
#   Kamal boot.stop_timeout: 45   >   puma 排空时长（最慢路由 p95 × 2 + 余量）
```

- 排空时长 = 最长在途请求（慢接口、长轮询决定）；`stop_timeout` 必须大于它，否则设计上注定被 SIGKILL——把最慢路由的 p95×2 写进部署配置注释。
- 惯用模式三件：①停机钩子里只做**无外部依赖**的清理（关文件、flush 日志——别再调外呼，停机路径依赖越少越可靠）；②长任务可中断点（定期检查停机状态）；③co-located 队列停机即作业中断——依赖 73 节 liveness 重派，别自造“等作业跑完”的循环。
- 坑①：容器入口必须 exec 形态（`exec puma ...`）让 PID 1 直接收信号——shell 包装层吞信号是部署卡死的经典根因。
- 坑②：优雅停机要演练：预发压测中 `docker stop` 计时 + 在途请求断言（错误率必须为 0）——断言进部署 checklist，比文档可靠。

## 84. N+1 尸检：strict_loading 强制与修复路径

- 尸检顺序（发现→定责→修复→门禁）：①生产发现 = APM 的 N+1 追踪或查询计数中间件（“该路由 847 次查询”）；②测试复现 = strict_loading 打开后该用例直接抛 `ActiveRecord::StrictLoadingViolationError`（违规查询位置在栈里）；③修复排序看 85 节语义表；④回归门禁 = 该系统测试断言查询数上限。

```ruby
# 分域强制（Rails 7.1+）：按基类开，而不是全局一刀切
class ApplicationController < ActionController::Base
  self.strict_loading_by_default = true   # 主读路径全量强制
end
class Admin::BaseController < ApplicationController
  self.strict_loading_by_default = false  # 管理面暂缓，列进迁移清单
end
# 温和档：只拦集合型 N+1（belongs_to 单查放过）——第一台阶的好选择
Post.strict_loading(n_plus_one_only: true).limit(50)
```

- strict_loading 分域策略（踩出来的节奏）：①先 `n_plus_one_only: true`（只拦 has_many 逐行加载——belongs_to 单查成本可控，先放过）；②热点域 `strict_loading_by_default = true`（读路径基类）；③例外点显式豁免，每条豁免写注释记名。全量一步到位的团队通常在周五晚上把豁免全关掉——分域才是可持续版本。
- 修复路径优先级（按成本收益）：①**preload**（最常用，语义见 85 节）→ ②**批处理**（`find_each`/`in_batches` 防全表进内存）→ ③**counter_cache**（数子对象别逐行 COUNT）→ ④**聚合改写**（循环逐行 SUM → 一条 GROUP BY）→ ⑤修视图层（partial 每行摸关联是 N+1 的老家）。
- 坑①：**fragment 缓存掩盖 N+1**——测试态缓存关、代码全量跑；prod 缓存命中时查询不发生，缓存大面积失效（86 节惊群）瞬间显形——查询数断言必须跑在缓存关闭的测试态。
- 坑②：strict_loading 与 delegate/序列化器的间接访问也会炸——修法是预载 through 关联，不是加豁免；豁免清单当代码评审重点（每条豁免 = 一笔债务）。
## 85. preload、eager_load 与 includes 语义表

- 语义表（一行一档）：**preload** = 永远分查询（每关联一条），不能在 where/order 里引用关联列；**eager_load** = 单条 LEFT OUTER JOIN，可按关联列过滤/排序（字符串条件需 `references`）；**includes** = 调度器——默认走 preload，检测到条件引用关联表就**静默升级**成 eager_load。
- **静默切换是陷阱本体**：同一行 `includes(:items).where(...)` 在条件写法变化时从两条查询变成一条巨型 JOIN，查询形状/内存/耗时全变——评审时把"这里到底是哪条路"说清楚，拿不准就写死 preload/eager_load，别让 includes 替你做决定。

```ruby
# 行爆炸事故的标准形态：has_many JOIN + LIMIT
#   Shop.eager_load(:items).limit(10)
#   => 10 家店 × N 件商品 = 猜猜 LIMIT 截的是 JOIN 后的行（错的数据！）
# 正解：默认 preload；确要按关联列过滤才 eager_load，LIMIT 用子查询
Shop.preload(:items).limit(10)                          # 分查询，正确且可预测
Shop.where(items: { kind: "hot" }).eager_load(:items).distinct.limit(10)
# 字符串条件引用关联列必须 references（否则 Rails 拒绝/猜错）
Shop.includes(:items).where("items.kind = ?", "hot").references(:items)
```

- 决策矩阵：默认 preload（可预测）；where/order/group 涉及关联列 → eager_load + references；多级 has_many 串 → preload（eager_load 的笛卡尔积会把内存打爆）；聚合统计（COUNT/SUM 子对象）→ 谁都别用，直接 GROUP BY（84 节修复路径）。
- 坑①：eager_load 多个 has_many = 笛卡尔积行爆炸（M×N 行中间结果），2 个以上 has_many 出现在同一个 eager_load 里就该被评审拦下。
- 坑②：`references` 忘写时旧版本静默猜、新版本有的直接抛错——把 `references` 与字符串条件当一对评审；哈希条件会自动关联，不需要。
- 坑③：preload 的关联查询一次 IN 一大串 id——超长 id 列表（万级）要自己分批（`in_batches` + 逐批预载），别等 DB 拒绝。

## 86. 缓存键策略与 Rails.cache 惊群控制

- 键构造纪律：`cache_versioning` 默认开——键 = 稳定标识（`orders/123`），版本 = `cache_version`（updated_at 秒级截断）。**秒截断是真实坑**：同一秒内两次更新不换版本，高频写模型要额外加版本 token（touch 触发的 `touches` / 手动 `cache_version` 列）；键里绝不放 Time.now、随机数、无界 id 列表。
- 键稳定性三则：按**形状**命名（"orders/feed:v2:u#{id}:p#{page}"），版本号 v2 进键（schema 变更即换键）；params 进键前**排序规范化**（乱序 params 生成不同键 = 命中率塌方）；键基数要预算（每用户 × 每页 × 每筛选 = 表容量，74 节水位联动）。

```ruby
# 惊群控制四件套（成套出现，缺一不可）
Rails.cache.fetch(
  "orders/feed:v2:u#{user.id}:p#{page}",
  expires_in: 10.minutes,           # ①基础 TTL
  race_condition_ttl: 30.seconds   # ②过期后宽限：并发请求拿旧值，只有一人重算
) do
  Feed::Builder.new(user, page).to_payload
end
# ③TTL 抖动：expires_in 加 ±10% 随机，防同批键同时过期（部署后的同步失效潮）
# ④重算有界：块内超时/异常降级返回空集，别让重算者自己变风暴
```

- 惊群场景分级：**热键过期**（race_condition_ttl 解决——旧值服务并发，重算一次）vs **冷键并发**（没有旧值可用：全量请求同时重算）——后者用锁（缓存 `write lock`/DB advisory lock：抢到锁者重算，其余短等待后读结果），或预热脚本在部署后主动填热键。
- 版本连坐：子对象变更要 bump 父键版本——`belongs_to :order, touch: true`（updated_at 变 → cache_version 变）；漏 touch 是"缓存了旧数据"事故的第一大根因，评审时把"谁负责失效"写进每个缓存键的注释。
- 坑：把 `expires_in` 当正确性工具（数据错了等过期）——失效语义（版本/touch/显式 delete）与过期语义（容量管理）分开设计；正确定价：宁可键短 TTL + 版本失效，不要长 TTL + 赌运气。

## 87. Zeitwerk 陷阱：initializer 引用与 to_prepare 纪律

- 陷阱本体：**boot 期（initializer 里）引用 autoloadable 常量**——dev 里能跑（惰性加载按需触发），但 dev 的 reloading 会把该常量的类对象换掉，而 initializer 早已把旧类对象配置进全局（`MyClass.strategy = X` 之类）——改一行代码后行为半残，重启才好，"幽灵 bug"就是这么来的。生产 eager_load 模式下则可能出现加载序依赖（文件 A 在定义时引用文件 B，eager 的遍历序不保证 B 先加载）。
- **to_prepare 纪律**：所有"对 app 常量做配置"的代码进 `Rails.application.config.to_prepare`——它在 boot 后、以及 dev 每次重载后都跑一遍，所以**必须幂等**（赋值式配置 OK；往全局数组/注册表 append 的写法会在 dev 里无限累积，防：先 clear 或用 `=` 整体替换）。

```ruby
# 反面（dev 重载后指向被换掉的旧类）：
#   Rails.application.config.after_initialize do
#     Billing::Gateway.timeout = 5      # after_initialize 不随 reload 重跑
#   end
# 正面：
Rails.application.config.to_prepare do
  Billing::Gateway.timeout = 5          # 每次重载后重新生效，幂等赋值
end
# initializer 里只允许 require 非托管代码/纯配置，不引用 app/ 常量
```

- lib 纳管（Rails 7.1+）：`config.autoload_lib(ignore: %w[assets tasks])` 让 lib 进 Zeitwerk——旧习惯 `require "my_lib/thing"` 与 autoloader 双重加载（先 require 后 autoload 报 already-defined）；纳管后删掉对 lib 的手工 require，lib 文件按常量命名法整理。
- 检查工具：`bin/rails zeitwerk:check` 进 CI（eager 语义检查）；报 "expected file X to define constant Y" = 命名不匹配——**缩略词**（Api vs API）要在 `config/initializers/inflections.rb` 里声明 acronym（inflection initializer 必须先于任何 autoload 执行——放 initializers 里没问题，别懒加载它）。
- 坑：`require_dependency` 已死——老代码里出现即替换为自动加载；`app/services` 等新目录 Rails 自动纳管（凡是 app/ 下的一级目录），别再造 `app/lib` 与 lib/ 的双头蛇。

## 88. dev 惰性加载与生产 eager load 的差异债

- 差异债的三个形态：①**命名/加载序**——dev 惰性加载按"调用序"走，谁先被用谁先加载；生产 eager 按目录序全量加载——文件 A 定义期引用 B 而 eager 序里 B 未加载 = 生产 boot 崩，dev 无感；②**执行副作用**——某文件 load 时执行注册/初始化，dev 只在被用到时执行（部分功能"没开"），生产全执行（双注册、意外覆盖配置）；③**死代码**——eager 模式下所有文件都要能安全加载，dev 里从不 touch 的文件带 typo 也能活到生产首启。
- **启动期 fail 是最贵的发现方式**：容器起来 → crash loop → 部署红在最后一步。CI eager 断言把这一步挪到合并前。

```ruby
# CI 的最小 eager 断言（与生产同构的环境跑一次 boot）
# .github/workflows/ci.yml:
#   - run: bin/rails zeitwerk:check
#   - run: RAILS_ENV=production SECRET_KEY_BASE=dummy bin/rails runner
#          'Rails.application.config.eager_load = true; Rails.application.eager_load!'
# 更完整的：docker build 出镜像后跑 "bin/rails about" + GET /up 作为冒烟
```

- 分叉环境条件是差异债的放大器：`if Rails.env.development?` 的 define/require 分支让生产加载的内容在 dev 从未加载——CI 断言跑生产 env 就是为了让这类分叉在合并前炸出来。
- 纪律：每个 PR 的 CI 都跑 eager 断言（快：不需要完整测试栈，只 boot）；"只在生产 boot 失败"类的 issue 一律转化成 CI 门禁新增项（112 节复盘文化）。
- 坑：`config.eager_load` 在 production 默认 true，但**测试环境默认 false**——纯 test-env CI 会放过所有 eager 问题；断言必须显式用 production env 跑。

## 89. 启动时间工程：bootsnap 内幕与 precompile

- 内幕：bootsnap 两层缓存——①**路径缓存**（把 $LOAD_PATH 的目录扫描结果固化，require 的 O(paths×files) 查找变 O(1)）；②**编译缓存**（Ruby/JSON/YAML/Marshal 的解析产物按 mtime+size 失效缓存，require 不再每次解析+编译 iseq）。大 monolith 的 boot 时间大头就在这两项。
- **precompile**：`bootsnap precompile` 在 Docker build 阶段把 app/ 与 gemfile 的编译产物写进镜像内缓存目录——冷启动直接命中，容器扩容/重启不交"预热税"；缓存目录必须可读（镜像内只读也行，配 precompile 就够，dev 才需要可写回填）。

```dockerfile
# Dockerfile（构建期预热，运行期零冷启动税）
RUN bundle exec bootsnap precompile --gemfile app/ lib/
ENV BOOTSNAP_CACHE_DIR=/app/tmp/cache/bootsnap
# 失效机制：mtime+size——CI/镜像层的 mtime 齐平是已知坑，
# 层拷贝顺序变化时宁可清缓存也别调试"玄学慢"
```

- 收益边界：bootsnap 治"加载与解析"，治不了"你的 900 个 initializer 与启动期全量 gem require"——boot 预算超标的下一步是：砍 boot 期 require（延迟加载非必需 gem）、initiliazer 合并、测量每个 initializer 的耗时（`config.initialize_hook`? 用 boot 时序打点）。
- 度量进 CI：`time bin/rails runner 'puts "boot"'` 作为回归指标（70 节预算表加一行）；boot 变慢的 PR 会悄悄吃掉部署窗口与扩容速度——它是容量问题不是美观问题。
- 坑①：fork 前收益——bootsnap + preload_app 的组合（90 节）：master 少付一次 boot，workers fork 全部白嫖；不 preload 的部署形态里 bootsnap 对每个 worker 各付一次（好在缓存命中后也快）。
- 坑②：缓存损坏/陈旧时症状是"偶发 undefined method"类玄学（加载了半旧半新的 iseq）——runbook 里"清 bootsnap 缓存重启"排在玄学调试第一步。

## 90. Puma worker 内存：COW 衰减与 RSS 治理

- 模型：`preload_app!` + fork → Linux COW 让 workers 共享 master 的已加载页；但 Ruby GC 的写屏障/标记会逐步碰脏对象头 → 整页变私有——**COW 红利随运行时长衰减**（小时到天级），RSS 曲线是"阶梯上升后趋平"，稳态才是容量规划的输入。
- 治理三件套：①**定期回收**——`pumactl phased-restart`（无 preload）或按 worker 轮换的 restart 任务（有 preload 时整组 restart），低峰期把地址空间换新；②**jemalloc**（16 节）治 malloc 碎片——RSS 衰减的另一半根因在分配器不在 Ruby 堆；③**每请求少分配**（20 节）——分配速率直接决定碰脏速率。

```ruby
# 容量公式（用 24h 稳态，不是 5 分钟的新生值——这是最经典的错法）：
#   RAM 预算 = master + Σ(worker 稳态 RSS) + headroom(20-25%)
#   workers 上限 = (总 RAM - master - headroom) / 稳态 RSS
# 观测：smaps_rollup 的 shared/private 分列——private 增速 = 衰减斜率
#   grep -E 'Private|Shared' /proc/<pid>/smaps_rollup
# 告警：per-worker private 内存 24h 斜率（阈值按机型校准后收紧）
```

- 池尺寸权衡的隐藏项：更多 workers = 更多私有页累积 + 更多 DB 连接（每 worker 一套池）；扩 worker 前先问"单 worker RSS 降不下来吗"——jemalloc + 分配治理 + 稳态复算通常比加 worker 便宜。
- 坑①：按"刚启动的 RSS"做容量规划 = 凌晨三天后 OOM（稳态是新生的 1.5-3 倍）；所有容量数字标注采样时长。
- 坑②：`fork` 后 worker 里 re-seed（Kernel.srand、id 生成器、TLS 会话）——preload 与"fork 不安全"的 C 扩展/全局随机态是隐雷（重启才炸的那种）。
- 坑③：COW 数字骗人——smaps 的 Shared 只统计当前共享页；正确口径看 Pss（按比例分摊）估算真实占用。

## 91. 内存膨胀分层诊断手册

- 三层判别（先分类再动手）：**①malloc 碎片**——RSS 高但 Ruby 堆平稳（`GC.stat` heap_pages 稳、ObjectSpace memsize 小）→ glibc arena 碎片，上 jemalloc（16 节）；**②Ruby 堆增长**——`heap_live_num` 随请求爬升 → retained 泄漏，堆 dump 三点差分（23 节）；**③常驻大对象**——boot 即高/特定操作后跳档：gem 启动面、缓存对象、线程栈；外加隐藏层 **④C 扩展 native 泄漏**——两头都不涨 RSS 照涨（69 节，valgrind/heaptrack）。
- 工具阶梯（从便宜到贵）：请求级 `GC.stat` 差值（alloc/req 中间件，20 节）→ `memory_profiler` 代表性请求（allocated/retained 四象限，21 节）→ ObjectSpace dump 三点差分（heapy 归因，23 节）→ `derailed perf:library`（boot 常驻的 gem 审计，22 节）→ smaps/jemalloc stats（进程/分配器层）。

```ruby
# 分诊入口：一分钟分类（生产可跑的低开销采样）
baseline = GC.stat.slice(:heap_allocated_pages, :heap_live_slots, :malloc_increase_bytes)
50.times { RequestSampler.replay(hot_endpoint) }   # 或等待真实流量窗口
now = GC.stat.slice(:heap_allocated_pages, :heap_live_slots, :malloc_increase_bytes)
# 堆涨 + live 涨    => 层②：retained 泄漏，进 dump 差分
# 堆稳 + RSS 涨     => 层①/④：jemalloc 试验 或 native 泄漏排查
# 堆涨 + live 稳    => churn（分配速率问题，20 节），不是泄漏
```

- 动作表（每层对应一招，禁止散弹）：层① → LD_PRELOAD jemalloc 灰度 + smaps 验证；层② → dump 差分定位 retainer 类，修缓存上限/清理钩子；层③ → derailed 砍 gem boot 面 + 给缓存上容量上限；层④ → 复现机 valgrind，升级/替换涉事 C 扩展。
- 高频根因清单（按命中率）：无上限 memo（按 user/request 做键的 Hash 永不清理）、线程/纤累积（57 节孤儿纤；线程栈各 ~1MB 起）、Thread.current[] 在池化线程上不清、AR 结果集存进常量/单例、Marshal 缓存 blob 解析出的大图（66 节）、frozen 符号表/字符串驻留被用户输入灌爆（105 节同源）。
- 坑：`memsize_of` 浅尺寸陷阱（23 节）——单对象 40B 但引用树 MB 级；归因永远看"谁 retain"而不是"谁大"。

## 92. 生产调试工具箱：sigdump、rbtrace 与 gdb

- 挂死取证顺序：①**sigdump**（`kill -CONT`）——秒级拿到全线程 Ruby 栈 + GC.stat（60 节）；②**rbtrace** 现场求值（池水位、锁、队列长度）；③**gdb**——前两者不可用时（未装、进程假死在 C 层、只有 core 可看）：`gdb -p PID` + `thread apply all bt` 拿 C 栈；Ruby 层栈用 **rb_ps** 便利脚本（社区 gdb 帮手，把 CRuby 内部线程结构翻成 Ruby 栈），rbtrace 仓库/gist 可取，装进镜像的 `.gdbinit`。

```text
# 挂死取证三连（runbook 固定顺序）
1) kill -CONT <pid>; tail -40 /tmp/sigdump-<pid>.log     # Ruby 栈：等什么？
2) rbtrace -p <pid> -e 'Thread.list.size'                 # 活着吗？池满吗？
3) gdb -p <pid> -ex 'thread apply all bt' -batch | head -200
# live 进程快照（不动它，给取证据留时间）：
#   gcore -o core.<pid> <pid>     # 之后离线 gdb core.<pid> 分析
# 容器前提：pod spec 加 SYS_PTRACE capability + hostPath /proc/sys/kernel/yama
```

- core dump 流程：`ulimit -c unlimited` + `core_pattern` 指向 systemd-coredump/目录；崩溃后 `coredumpctl list/gdb`；**OOM kill（SIGKILL）不留 core**——OOM 取证走 dmesg + oom_score 重建（111 节），别等 core。镜像要留带符号的 ruby（或 debug 变体 tag），否则 core 只有数字。
- 部署纪律：把 ptrace 权限、gdb、sigdump、rbtrace 当**部署清单项**而非事后补装；调试优先在副本/金丝雀上做——主实例先 sigdump + gcore 留证，然后立刻 restart 恢复服务，取证在离线 dump 上继续（111 节同款纪律）。
- 坑①：`kernel.yama.ptrace_scope=1`（多数发行版默认）禁止非父子 attach——容器里没配 capability 时 gdb 秒拒，这是"为什么生产上没人能 attach"的标准答案。
- 坑②：gdb attach 会**全场暂停**目标进程数秒——高流量实例上先摘流量再 attach；`-batch` 模式限制命令数，别在 attach 态里交互式漫游。

## 93. 后台作业堵塞与积压复盘

- 积压方程（写进看板的三个量）：深度 D、完成吞吐 T、到达率 A；积压 ⇔ A > T 持续；**排队时延 ≈ D / T**（Little's law）——SLA 指标用"最老 ready 作业年龄"（73 节）而不是深度：深度 5000 但 T=500/s 时延才 10 秒，深度 50 而 T=0.5/s 时是 100 秒，看深度会误判。
- 堵塞根因树（概率序）：①worker 不在/不足（心跳缺失、CPU 打满）；②单 job 变慢（外部依赖时延↑：3 线程全挂在 30s job 上 = 吞吐除以 10）；③**retry 风暴**（失败重试放大负载：A_eff = A×(1+重试率)，101 节预算缺失时雪崩）；④锁/信号量耗尽（Solid Queue semaphores、应用级锁串行化）；⑤enqueue 洪峰（批量任务一次塞 50 万条）。

```ruby
# 复盘要固化的三张图（每次积压事故后补齐）：
#   1) 深度/年龄 + 吞吐 + 到达率同轴 —— 雪崩起点在哪、谁是先变的
#   2) per-queue 时延分解 —— 是单队列烂还是全场烂
#   3) per-job 耗时 p95 —— 单 job 变慢 vs 数量变多，处置完全不同
# 停机排空纪律（部署/维护窗口）：
#   ① 先停入口（feature flag 关 enqueue，不是直接 kill worker）
#   ② 等排空：深度归零或 claimed 清空（超时就接受中断）
#   ③ 中断必然发生时——幂等合同生效：作业重跑不双扣款、不重发通知
```

- 幂等重放三则：①作业带幂等键（业务键 + 状态检查先行："已处理过就 return"）；②Solid Queue 的 liveness 重派（73 节）意味着**任何作业都可能跑一半重来**——外部副作用先落状态行（事务内写"processing"标记）再执行；③重放演练：从失败集/死信集重投是常规操作（修完 bug 重放 2 万条），必须支持限速重放——否则重放就是对自己依赖的 DDoS。
- 坑①：用"加 worker"治所有积压——外部依赖打标时加 worker 是放大雪崩（82 节同病根：先修单 job 时延与超时）。
- 坑②：监控只看深度不看年龄——积压事故的 MTTR 差距就在这一行指标上。

## 94. RSpec 深水：let 延迟与共享状态泄漏

- let 语义三件：**惰性**（首次访问才执行）+ **memoized**（本 example 内缓存）+ 每例独立。`let!` = 定义即触发（before 里强制执行）。顺序陷阱：`before` 钩子里访问 let 没问题（首次访问会求值），但 **`before(:context)` 里不能用 let**（let 挂在 per-example 机制上）——要用就得 `before(:context)` 里自己建不可变数据。
- 泄漏源排序（真实事故经验）：①**before(:context) 的可变共享**——整组只跑一次，@ivar 跨 example 累积变异（每 example 新实例的保护在这里失效）；②类级/常量/全局配置（Time.zone、feature flag、I18n 在测试里改了没还）；③DB 行（非事务回滚的测试：system tests、外部连接写入）；④线程/后台任务没 join。

```ruby
# 反面：顺序依赖 + 共享变异（随机顺序下闪绿闪红）
before(:all) { @mock_gate = create(:gate, :open) }   # before(:context)
it "a" { @mock_gate.update!(open: false) }           # 污染下一个 example
it "b" { expect(@mock_gate).to be_open }             # b 依赖 a 没跑/先跑的顺序
# 正面：let 显式依赖 + 每例自建
let(:gate) { create(:gate, :open) }
it "b" { expect(gate).to be_open }                   # 顺序无关
# 自检纪律：rspec --bisect --seed <闪红seed> 找最小失败组合
```

- 实例变量债：规格里优先 let（依赖显式、可覆盖、惰性）；@ivar 把初始化顺序藏进钩子链，出问题只有"隐式 nil"一种表现形式。评审规则：@ivar 只允许出现在 `before(:context)` 的不可变 setup 里。
- 随机化自检：`config.order = :random`（默认开，**别在 .rspec 里 pin seed**——pin 住就是把顺序债冻进仓库）；闪红后 `rspec --bisect` 求最小复现集，修的是共享状态不是 seed。
- 坑：`subject` 与 `described_class` 的隐式耦合叠多了和魔法 DSL 一个味（40 节预算同款）——测试是文档，可读性预算比生产代码还低。

## 95. transactional tests 边界：线程、Fiber 与外部副作用

- 机制：`use_transactional_tests` 把每个 example 包进 DB 事务，结束回滚——**事务内的行只对本连接可见**。推论一：测试里起的 `Thread.new { Model.find(...) }` 用的是另一个连接——看不到 example 里 create 的行，还会把池里的连接 checkout 走（不 join 的线程 = 池泄漏，套件越跑越慢）。
- 推论二：真后台 worker 进程/独立服务（system tests 的浏览器请求走 server 线程的连接）一律看不到事务内数据——"我在测试里 create 了记录，页面却没有"就是这个机制，不是前端 bug。处置：跨连接场景显式 `use_transactional_tests = false` + teardown 显式清理（truncate/delete 策略），别用对象级 hack 绕。

```ruby
# 线程内的可见性修复：join + 共享连接语义显式化
it "runs job inline" do
  perform_enqueued_jobs { Order.finalize!(order) }   # Active Job 内联：同连接，可见
end
it "spawns thread" do
  thread = Thread.new do
    ActiveRecord::Base.connection_pool.with_connection do
      # 这里能看到的只有"测试事务提交后"的数据——事务内造数不可见！
    end
  end
  thread.join                                          # 必须 join：池卫生
end
# 外部副作用（Redis/HTTP/邮件）不回滚：每例清理，不要依赖"下个测试会覆盖"
```

- Active Job 双态：`perform_enqueued_jobs`（内联执行，同线程同连接：可见、可回滚）vs `perform_enqueued_later`/真 worker（异步：不可见 + 事务回滚后作业还排队——**作业引用的行回滚消失了**，worker 空转报错）；测试异步路径要么等作业执行后再断言，要么改内联。
- 外部副作用策略：Redis——每 example 独立 namespace（key 前缀注入）或 after 钩子 FLUSHDB；HTTP——WebMock/stub 代替打真服务；邮件——delivery 列表断言。原则：**事务回滚只管 DB**，其它副作用每例显式清。
- 老代码里的"连接共享 hack"（把 `ActiveRecord::Base.connection` monkey-patch 给所有线程）见过就删——它破坏池语义，掩盖真正的边界问题；正确方向是上面三条。

## 96. fixtures 与 factories 决策矩阵

- 决策矩阵（四个维度）：**复用度**（高频共享的"世界"数据 → fixtures：一次插入、整套件复用、速度碾压）vs **变体表达力**（被测对象的多形态组合 → factories：trait 组合就近声明）；**离断言距离**（fixtures 的老毛病：mystery guest——测试读起来正常，失败时要去 fixtures 文件里考古）；**速度**（fixtures 批量插入 + 事务回滚是零边际成本的；factories 每例 N 次插入 + 回调链）；**漂移**（factories 跟着模型走，fixtures 会烂在原地——需要 lint）。
- 混合策略（默认答案）：**fixtures 搭世界，factories 造被测对象**——users/accounts/参考数据用 fixtures（全套件共享的业务不变量），"本例的主角"及其变体用 factory trait 就地声明；system tests 偏 fixtures（量大速度敏感），单元测试偏 `build`（不落库就别 create——工厂的速度债一半来自无谓的 create）。

```ruby
# factories 纪律：只造断言需要的东西，关联深度显式化
factory :order do
  user                          # 反面：默认串起 5 层关联，只为断言一个字段
  # traits 显式表达变体，而不是调用处随手指
  trait :paid do
    status { :paid }
    paid_at { 1.day.ago }
  end
end
# CI 门禁：factory lint（所有 factory 合法）+ fixtures lint（schema 漂移检测）
#   rake factory_bot:lint -- 在独立任务里跑，别混进单元套件（慢）
```

- 工厂债治理：①lint 进 CI（factory 非法 = 第一时间红）；②关联默认值最小化（需要就传，不默认造）；③重 factory profile（test-prof）找出最贵的 build 链，`let_it_be`/`before_all` 上下文级共享（99 节性能债联动）。
- fixtures 债治理：业务命名（`paid_order` 而不是 `order_1`）；禁止测试里 mutate fixtures（逻辑耦合：改一处全套件崩）；ERB 动态 fixtures 能不用就不用（YAML 里的代码是调试黑洞）。
- 坑：二选一的圣战心态——真实代码库两个都要，判断标准是"这个数据是背景还是主角"，就这么简单。

## 97. verify doubles 与接口漂移防护

- 机制：`instance_double(RealClass)` 在 stub 时校验——方法存在 + 签名（arity）匹配，被 stub 的类一改签名，spec 当场红，而不是**悄悄测试了一个不存在的方法三年**（plain double 的默认命运）。`class_double`/`object_double` 各对应类级/实例引用；`verify_partial_doubles`（对真对象 allow）默认开，同保护覆盖到 partial。
- 降级路径（类无法在测试环境加载时）：优先 `object_double(经过 autoload 的常量)`；仍不行→ plain double + **契约测试补位**（对那个外部 SDK 写一个小型真调用 smoke，staging 定期跑）——测试逻辑还是双的，语义契约有真实验证兜底。禁止的模式："verifying double 报错好烦，全换 plain double"——把防护拆了是因为不喜欢防护。

```ruby
# 反面：plain double——上游改名三年后你还绿着
allow(payments).to receive(:refund).with(order) { ok }
# 正面：签名漂移当场暴雷
double = instance_double(Payments::Client)
allow(payments_client).to receive(:refund).and_return(Result.ok)
# 语义契约（verifying 管不到的部分）→ staging 冒烟：
#   真实 SDK 调一次 refund(测试商户)，断言返回形状
```

- verifying 的边界要说清：它校验**存在性与元数**，不校验**语义**（stub 返回 nil，真实返回 Struct——类型契约照漂）；语义漂移的防线是边界包装：外部 SDK 一律进自家 adapter（80 节 public API），adapter 有一小组真调用测试，其余代码只对 adapter double。
- 坑①：`instance_double` 触发 Zeitwerk 加载真实类——spec 里 double 一个"重"类（加载整条依赖链）会拖慢套件启动；解法是 adapter 化（double 面越窄，加载面越小，94/99 节启动债联动）。
- 坑②：stub 被测对象自身（`allow(subject).to ...`）= 测试的不是这个对象；自测要的是协作对象替身，subject 永远是真的。
- 坑③：`allow_any_instance_of` 是最后的手段——全场改一个类的行为，测试意图全部隐身；出现即要求改设计（注入协作对象）。

## 98. 系统测试：并行化与 flaky 治理

- headless Chrome 姿态：Selenium 4.6+ 自带 Selenium Manager（驱动自动管理，`webdrivers` gem 退役路线）；容器三件坑——`/dev/shm` 至少 2GB（不足时 Chrome 随机崩，CI 玄学第一源）、`--disable-dev-shm-usage` 慎用（swap 硬盘）、无沙箱标志显式化（容器里 `--no-sandbox` 常见但要知道自己在关什么）。
- 并行化：`parallelize(workers: N)`——进程级 fork，每 worker 独立测试库（Rails 自动分库 hooks + `PARALLEL_WORKERS`）；fork + 事务回滚让内存也吃 COW（90 节同款红利）；N 超物理核 = thrash，超 DB 连接预算 = 池耗尽——并行度按 min(CPU, 连接预算) 配。

```ruby
# flaky 治理的优先级表（按命中率排序修，别乱枪）
# ①等待缺失：用 Capybara 自动重试匹配器，禁 sleep
expect(page).to have_css(".order", text: "PAID")     # 自动等待/重试
sleep 2; expect(page).to have_css(".order")          # 反面：竞态没修，只是常中
# ②共享状态泄漏：非事务回滚的测试漏清理（95 节）
# ③动画/过渡：CI 关 CSS 动画（transition: none），消元素抖动期误判
# ④时间/随机：travel_to 固定时钟；随机数据种子化
# 失败自动取证：截图 + page.html + browser console 全量落盘（重放才有据可查）
```

- flaky 治理纪律：①失败自动留证（截图 + HTML + JS console log——**JS 报错测试根本没断言**，但它常是真根因）；②`--seed` 复现 + `--bisect`（94 节）；③隔离区机制：flaky 测试加 pending + ticket 进隔离列表，**列表只许清不许长**（每周预算消隔离），`rspec --retry` 只许做过渡手段并带注释；
- 坑①：并行 + 非事务测试（95 节）= 跨 worker 数据互踩——分库策略必须与事务策略同一次决策。
- 坑②：CI 与本地 chrome 版本漂移——锁定镜像里的 Chrome/chromedriver 版本对（Selenium Manager 免驱动管理，但不免浏览器漂移）。

## 99. 测试性能债：启动、随机顺序与 ratchet

- 性能债三源：**启动**（每个 worker 都要付一次 boot：gem require + spec_helper 膨胀——fork 并行下是 N 倍；test-prof 的 `rspec --profile`? 不，启动用 `time rspec spec/models/dummy_spec.rb` 度量纯 boot）；**example 排队**（最慢的 20 个 context 吃掉半壁——`rspec --profile 20` 指认，let_it_be/before_all 降每例 setup）；**DB 往返**（每例多次 factory create + 回滚）。
- 随机顺序的债：`.rspec` 里 pin seed = 冻结顺序债（94 节）；正确姿势：随机全开 + `--bisect` 修闪红 + 每周 flaky 预算；`config.order = :random` 保持，失败的顺序依赖当 bug 修不当代码绕。

```ruby
# test-prof 三板斧（不动测试语义的加速）
#   let_it_be(:user) { create(:user) }      # context 级共享，事务快照回滚
#   before_all { create_list(:order, 3) }   # 整组一次 setup
#   RSpectre 静态扫描：死 let / 未用 hook 直接删（没人敢手删的那种）
# ratchet 门禁（数字只许变好）：
#   - CI 记录：boot 秒数 / 套件总时长 / flaky 数 / 隔离区条数
#   - 回归阈值：boot +10% 或总时长 +15% => 失败，找 owner
#   - 趋势看板：周环比斜率，缓慢爬升也逃不掉
```

- ratchet 文化（99 与 70 节同根）：预算数字来自现状，**只收紧不放松**；每次豁免（"这周先放行，下周修"）必须带 ticket 和日期——无主的豁免在下季度自动升级为失败。
- 坑①：先买并行再治串行——并行化把 3 倍变 1.5 倍，但最慢 context 还是串行地板（Amdahl）；顺序永远是：profile → 砍最贵 → 并行收尾。
- 坑②：套件时长变长后 CI 悄悄截断（只跑变更目录）——这是用覆盖换速度的隐性决策，要么显式分层（快套件 per-PR / 慢套件 main-branch），要么别做。

## 100. 应用异常树设计与 wrap 规范

- 异常树设计：根 `AppError < StandardError`，按域分叉（`Payments::Error`、`Billing::Error`）；每层必须回答三问：**对客户端呈现什么**（稳定 error code + HTTP 状态映射表）、**可否重试**（retryable? ——101 节预算的判据来源）、**上下文带什么**（结构化 payload：业务 id、决策点，message 里不许只有 "failed"）。
- **wrap 规范**：边界层（adapter/service 门面）把第三方异常（Faraday::、PG::、Net::）包成域异常；wrap 必须保 cause（raise 时自动记录，103 节取证依赖它）；**不加信息就不 wrap**（纯转发的包是树污染）；域异常类名是 rescue 依赖的 API——改名是破坏性变更，评审时按公共接口对待。

```ruby
module Payments
  class Error < AppError; end
  class GatewayTimeout < Error; end    # retryable: true（超时可重试）
  class Refused < Error; end           # retryable: false（业务拒绝）

  def charge(order)
    gateway.capture!(order.amount_cents)
  rescue Faraday::TimeoutError => e
    raise GatewayTimeout.new("charge failed order=#{order.id}",
                             context: { order_id: order.id }, cause: e)
  rescue Faraday::ConnectionFailed => e
    raise Refused.new(...)              # 不该 wrap 的就别硬塞进同一个
  end
end
```

- 对上界面收敛：顶层 handler 只认树——`rescue_from AppError` 统一出 `{ error: { code, message, request_id } }`；code→状态映射一张表（payments.refused → 402，gateway_timeout → 503）；**未知异常 500 泛化**，message 不外泄、全链进日志（request_id 串联 103 节）。
- 反模式两条：一条 `Error` 类 + stringly `code` 属性（rescue 无处下手，控制流全靠 if）；method_missing 动态生成异常类（34 节 grep 不可见，监控聚类按类名分组直接瞎）。
- 坑：树太深太细（每 raise 一个类）——类是控制流工具，先想清楚"谁 rescue 它、然后干什么"，没有 rescue 者的类一律合并。

## 101. retry 语义与重试预算：抖动与幂等

- 语义第一坑：`retry` 重跑的是**整个 begin 体**——失败点之前已执行的副作用（扣款、发消息）会再来一遍；retry 只许出现在 rescue 内 + 带计数/条件（无限 retry 的循环在凌晨的日志洪峰里见）；副作用前置的 begin 体根本不能 retry——把幂等段和副作用段拆开。
- 分类表（写进 100 节的异常树属性）：**可重试** = 超时、5xx、锁竞争、连接重置；**不可重试** = 4xx 校验错、鉴权失败、格式坏数据（重试不改变答案，纯放大流量）；**有条件可重试** = 写操作（必须幂等键）。分类在边界 wrapper 统一做，不许每个调用点自选。

```ruby
# 退避 + 全抖动 + 全局预算（三件套缺一即雪崩放大器）
def with_retry(retryable:, budget: RETRY_BUDGET, &)
  attempts = 0
  begin
    yield
  rescue AppError => e
    raise unless e.retryable? && attempts < 3 && budget.consume!(e.class)
    attempts += 1
    sleep(rand * (0.25 * 2**attempts))    # full jitter：削平同步重试潮
    retry
  end
end
# 幂等键：写操作带键，服务端 dedupe（唯一索引/幂等表）——重试两次=一次效果
# Idempotency-Key: <uuid>（客户端生成，随 retry 复用同一个键）
```

- 重试预算是全局资源：per-dependency 令牌桶（每分钟最多 N 次重试）——依赖半死时全体调用方同步重试 = 重试风暴，预算桶最先保护的是依赖方；预算耗尽快速失败交给断路器（82 节）开闸。
- 幂等实现：幂等键 + 结果表（键唯一索引，竞态请求拿到同一结果）；job 级幂等 = 执行前"已处理？"检查 + 处理中状态行（93 节合同的落地形态）。
- 坑：backoff 无抖动（所有实例同一时钟表同一失败）——定时炸弹；重试次数配在业务代码里而预算配在基础设施里，两边只看一边必出事。

## 102. ensure 深水：return 覆盖与异常替换

- 两大深坑都是**静默**的：①`ensure` 里 `return`/`break`/`next` 会**吞掉进行中的异常**——`begin; raise; ensure; return 1; end` 的异常凭空消失，调用方拿到 1；②`ensure` 里 `raise` 会**替换**原异常（cause 链还在，但上层只见替换者——原始根因从主流程里蒸发，103 节取证时才追得回）。
- 纪律：ensure 体只做清理；清理代码自带 begin/rescue（记录清理失败）后**重抛原异常**；ensure 内的流程控制语句当 lint 错误处理（RuboCop `Lint/EnsureReturn` 之类，配上评审红线）。

```ruby
# 反面：两个坑同框——原异常被吞，锁没释放成功还被掩盖
def with_lock(key)
  lock.acquire!
  yield
ensure
  return lock.fallback_status   # 坑①：进行中异常被 return 吞
  lock.release!                 # 死代码；release! 若 raise 即坑②
end
# 正面：清理失败要记录，但绝不遮住原异常
def with_lock(key)
  lock.acquire!
  yield
ensure
  begin
    lock.release!
  rescue => e
    Logger.error("lock release failed key=#{key}: #{e.class}")  # 记，不抛
    raise if $!                  # 有进行中异常则保持原样冒泡
  end
end
```

- 嵌套次序：ensure 按 begin 嵌套 LIFO 执行（内层先），每层都可能吞/换——middleware 叠 5 层 ensure 时，"顶层看到的异常"可能已被换了 3 次；审计模板：对关键链路打印 cause 链（103 节）验证没被中途调包。
- `else` 分支语义：`rescue ... else ... ensure`——else 只在无异常时跑，且在 ensure 之前；else 里 raise 会正常走 ensure。
- 高频事故形态：ensure 里调外部依赖（rollback！/close！/notify！）而依赖此时也坏了——原异常被依赖的异常替换；停机/崩溃路径（83 节）与这里的纪律同源：清理路径零依赖。

## 103. Exception#cause 与异常链取证

- 隐式 cause：rescue/ensure 块内 raise 的新异常，`cause` 自动指向"正在处理的那个"——`raise MyError, "..."` 时不需要手工传；显式 `Exception.new(msg, cause: e)` 用于跨作用域拼接（回调/线程边界）。链可多级（A→B→C），**根因常在链尾**而不是链头。
- 取证纪律：日志/Sentry 上报必须打**全链**——只打 `e.message` 的日志是取证废料：上层域异常的 message 是"charge failed"，真正的根因（链尾的 `PG::UniqueViolation`）被藏了三层。结构化序列化：沿 cause 链收 class/message/backtrace，request_id 串联。

```ruby
# 异常链完整打印（进日志工具类，别在每个救援点手写）
def log_exception(e, level: :error, **tags)
  chain = []
  c = e
  while c
    chain << { class: c.class.name, msg: c.message, bt: Array(c.backtrace)[0, 8] }
    c = c.cause
    break if chain.size > 10            # 循环链保险
  end
  Logger.public_send(level, { tags:, chain:, request_id: Current.request_id })
end
# 裸 raise 保原栈；raise e 保原栈；raise e.class, e.message 丢栈丢 cause——禁止
#   begin; raise A; rescue => e; raise e; end          # OK
#   begin; raise A; rescue => e; raise e.class, "x"; end # 反面：链断
```

- wrap 链读法（100 节配套）：每包一层问一句"这层加了什么信息"——message 里应该能读出穿透路径（"Payments::GatewayTimeout ← Faraday::Timeout ← charge order=123"）；层级包裹不增值时（102 节清理异常），链上看得出"这层是噪声"。
- 坑①：`raise e.class, e.message` 形态的"重抛"丢 backtrace 丢 cause（见上）——评审见一次毙一次。
- 坑②：cause 链在序列化（JSON）时默认消失——自研上报格式必须显式带 chain 字段；Sentry/bugsnag 原生支持，但要验证 SDK 配置没把 cause 裁掉。
- 坑③：线程边界的异常不带隐式 cause（`Thread.new` 里 raise 的异常不自动链到父级）——跨线程用显式 cause 或 Future/Concurrent 的包装。

## 104. rescue 粒度与静默失败反模式

- 裸 rescue 的税单：`rescue`（= StandardError）吞掉 NoMethodError/NameError/TypeError——**typo 变成 nil 传播**，炸点离根因八个调用层，这是 Ruby 代码库里单类最贵的 bug 形态；`rescue Exception` 更吞 SystemExit/SignalException/NoMemoryError（83 节停机惨案的开关）。裸 rescue 在评审里当 bug 处理，不当风格问题。
- 粒度规则：rescue **你能处理的最窄异常**——"处理"的定义是"接下来有个动作"（重试/给默认值/映射成用户错误 422）；"记个日志然后返回 nil"**不是处理**——那是把异常转成静默数据损坏；日志 + re-raise 归边界层（100 节）统一做。

```ruby
# 审计四连（CI grep 固化，白名单文件 + 注释豁免）：
#   ① rescue nil            —— 吞错典型；命中即驳（除非白名单+issue 链接）
#   ② rescue => e; nil      —— 换皮的 rescue nil
#   ③ 空 rescue 井          —— rubocop Lint/SuppressedException 兜底
#   ④ 循环内 rescue + next   —— 静默部分处理：不计数不告警 = 数据暗损
# 循环处理的正确形态（部分失败必须显性化）：
failed = []
items.each do |item|
  process(item)
rescue MalformedRecord => e        # 最窄 + 有后续动作
  failed << [item.id, e.message]
end
raise PartialFailure, "processed=#{items.size - failed.size} failed=#{failed.size}" if failed.size > threshold
```

- 静默失败的系统性防线：①RuboCop（Lint/SuppressedException、Style/RescueStandardError）+ 自定义 cop 禁 `rescue nil`；②brakeman/semgrep 扫 rescue 面；③白名单文件集中豁免（每条带 issue 链接与到期日——99 节 ratchet 文化）；④可观测兜底：每处"有意吞"必须落计数器/日志标签，dashboard 能看到"吞了多少"。
- 反模式收尾：rescue → 返回默认值（调用方分不清"算出来的默认"和"失败"——给 Result 对象或 raise 映射）；rescue + 重试无上限（101 节）；顶层 rescue Exception 且不转发信号（83 节）。
- 坑：`rescue SomeError, AnotherError => e` 多类并列是合法且推荐的窄化写法；但 `rescue => e` 省略类名的写法视觉上和裸 rescue 太像——统一风格：要么显式 StandardError 加注释，要么禁用省略式。

## 105. YAML 与 Marshal 反序列化：RCE 路径

- Psych 4（Ruby 3.1+）起的默认反转：`YAML.load` = safe 语义（不实例化任意类、不开 aliases），显式危险面改为 `YAML.unsafe_load`。但**风险面没消失，只是搬了家**：老代码升级 Psych 后靠 `unsafe_load` "保持兼容"，审计重点从 `YAML.load` 换成 `YAML.unsafe_load` / `safe_load(permitted_classes:)` 的许可清单。
- RCE 路径一句话：`!ruby/object:` 标签触发任意类实例化 + 属性注入——通用 gadget 链（Gem/Net/ERB 系）常年存在；**Marshal 同罪更凶**：二进制不透明、gadget 面同级，且跨部署还有类形状漂移问题（66 节）。铁律：**两者都不过信任边界**。

```ruby
# 输入面枚举后逐个收口（上传、webhook、MQ 消息、共享缓存、配置热加载）：
# 需要少量类型：safe_load + 白名单（Symbol/Date 显式列出，连 aliases 都按需开）
data = YAML.safe_load(blob, permitted_classes: [Symbol, Date, Time],
                      aliases: false)
# 配置文件确需对象语义：改数据形态（纯 hash/数组）而不是发 unsafe_load
# 共享 Redis/DB 缓存：值只存 JSON（攻击者拿到缓存写权限 = 拿到 RCE，别给他Marshal）
# CI 固化：Psych 语义回归测试
#   assert_raises(Psych::DisallowedClass) { YAML.load("!ruby/object:File {}") }
```

- 缓存投毒是内部面最容易被忽略的一条：Redis/Memcached 被攻破（或内部误写）→ 应用 `Marshal.load` 缓存值 → gadget 执行——"反正在内网"不是边界论证，是事故复盘的开场白。
- 修复策略优先级：①换格式（跨边界一律 JSON——schema 显式、版本化）；②必须 YAML 时 safe_load + 最小 permitted 清单（Symbol 也要显式给）；③遗留 unsafe_load 逐个消灭并挂 issue（105 节清单化），别"统一封装一个 load_yaml"——封装会把危险面藏进一个 grep 不到的调用点。
- 坑：psych 大版本升级会动默认语义（3.x→4.x 就是前车之鉴）——Gemfile 里 psych 的 major bump 要过安全评审；`YAML.load_file` 同样换了语义，文件读取路径一起审计。

## 106. send 与 const_get 注入面白名单

- 风险模型：`obj.send(user_string)` / `Object.const_get(user_string)` / Rails 的 `constantize`——用户指定的**名字**变成**代码引用**：能摸到任意公开/私有方法（含 `system` 的可达链、`destroy_all`、`password_digest` 读侧信道），能定位任意类再继续反射。这不是"理论上"，是 CTF 之外真实渗透路径里的标准一步。
- 修法一律**白名单映射**：用户字符串 → 预定义 symbol 表；表外一律 ArgumentError 映射 4xx。参数化反射（`public_send(SUPPORTED.fetch(param))`）替代裸反射；`public_send` 只防"摸到私有"，防不了"公开面里的危险方法"（38 节）——白名单仍是唯一正解。

```ruby
# 反面（/controller、/method 全来自参数）：
#   Object.const_get(params[:namespace]).send(params[:action])   # 全反射链
# 正面：调度表白名单 + 显式校验 + 反向兜底
ACTIONS = {
  "publish" => :publish!, "archive" => :archive!
}.freeze
def dispatch(action_name, record)
  method = ACTIONS.fetch(action_name) { raise UnsupportedAction, action_name }
  record.public_send(method)          # 表内 symbol，参数化反射
rescue UnsupportedAction
  Head[422]                           # 用户错误，不是 500
end
# const_get 场景：常量映射表（字符串→常量）字面写死在代码里，一样审查得动
```

- **静态哨兵**（防回归）：rubocop 禁插值 send/`constantize`/`const_get`（参数来源不可证即报）；brakeman taint 分析过 CI（置信度分级：high 必修，medium 带注释豁免）；grep 审计 `params\[.*\]\.send`、`\.constantize` 出现在 controller/lib 的位置——新出现的引用进评审队列。
- 坑①：`SUPPORTED.fetch(x) { default_method }` 的 fallback 若给成真实方法 = 白名单旁门——fallback 永远是报错。
- 坑②："管理后台内部用，用户够不着"——内部工具的注入面照样能横向（触发任意方法 = 内网 RCE 跳板，105 节缓存投毒同款推理）；一样白名单。

## 107. mass assignment：strong params 之外

- strong params 只覆盖"controller 边界的 permit 面"——绕过点清单：①**服务层裸传**：`update(params.to_h)`、`Model.create(payload)`（payload 从 webhook/内部 API 深传进来，permit 边界早被穿透）——域层入口要么收显式关键字参数，要么用带 schema 的对象（Typed struct）拒绝裸 Hash；②`assign_attributes` 直呼；③`Model.new(request.json)` 在非 controller 层（ Grape/grape-api、GraphQL resolver、admin 表单 builder）——这些框架没有 strong params 的默认保护。
- **Devise 面**：`devise_parameter_sanitizer` 的 permit 清单是特权升级高发地——sign_up 参数里混进 `:admin`/`:role`（配合 User 上的 attr_accessor 或直接列），一次注册即提权；审计所有 sanitizer 追加项；注册/资料页表单不要"全列回显"（页面里隐藏字段就是攻击者的说明书）。

```ruby
# 分层防线的三道墙：
# ① 域层显式命令（列名白名单 = 方法签名，IDE/grep 双可见）
class PromoteUser
  def call(user, role:)           # 只有两个参数，admin 不在面上
    user.update!(role: role)      # assign 层不碰 request 结构
  end
end
# ② ActiveRecord 兜底：attr_readonly 冻结敏感列（create 后改即抛）
class User < ApplicationRecord
  attr_readonly :tenant_id        # 跨租户改写 = mass assign 高危面
end
# ③ Devise sanitizer 审计点（每个追加 permit 都要有正当性注释）
#   devise_parameter_sanitizer.permit(:sign_up, keys: [:name, :locale])  # OK
#   permit(:sign_up, keys: [:admin])                                     # 反面
```

- 状态类字段（status/role/state）即便用户可编辑也要过状态机方法（事件驱动：`order.pay!`）而不是列赋值——mass assignment 事故的一半是"合法字段、非法变迁"（用户直接把 order.status 从 paid 改 refunded）。
- 坑：`params.permit!`（全开）出现在"内部工具"controller 里 = 定时炸弹；`except` 黑名单式过滤（`except(:admin)`）在加新列时自动漏防——白名单结构化（permit 显式列）是唯一可维护方向。

## 108. SQL 拼接与 ReDoS 输入面收口

- 拼接即注入：`where("name = '#{q}'")` 是老段位；新段位藏在**列名/排序方向**里——`order(params[:sort])`、`group(params[:group])`、`pluck(params[:col])`、LIMIT/OFFSET 插值——占位符管不了标识符位置，白名单映射是唯一解（`SORTABLE = { "created" => "created_at", ... }.freeze`）。
- `Arel.sql` 是**信任标记**：把字符串标记为可信 SQL 片段——`Arel.sql(user_input)` 等于亲手关掉防线（文档明示的 footgun）；动态构造走 Arel 节点（`arel_table[:col].asc`）或白名单；Arel.sql 只许喂字面量/常量表。

```ruby
# 收口三件：列名白名单、值占位符、通配符转义
SORT = { "created" => "created_at", "name" => "name" }.freeze
scope = params[:desc] == "true" ? :desc : :asc            # 方向也白名单
Order.order(SORT.fetch(params[:sort], "created_at") => scope)  # 哈希形态
# LIKE：占位符防注入，但 % _ 是通配符注入（性能 + 逻辑）——sanitize_sql_like
Order.where("name LIKE ?", "%#{Order.sanitize_sql_like(q)}%")
# order/limit 全链路：limit(params[:per].to_i.clamp(1, 100))——数字位也钳制
```

- 白名单要覆盖**整条构造链**：order、group、having、select 别名、pluck、distinct on、窗口函数字符串——每处一个映射表，表和路由同文件放，评审一眼见全。
- ReDoS 输入面收口（63/64 节在此收账）：路由 constraint、校验层、日志抓取里的正则全部吃用户串——超时预算 + 长度截断 + 灾难形状审查三连；constraint 正则尤其要短（它在请求线上，最便宜的 DoS 面）。
- CI 门禁：rubocop Rails/SqlInjection（静态）+ brakeman（taint 流）双跑，high 置信必修，medium 豁免要 issue 链接；新增 `.order(`/`.group(`/`Arel.sql` 的 PR 自动加安全 reviewer。

## 109. Ruby 反模式清单：memoize nil 与 rescue nil

- `@x ||= compute` 双坑：compute 返回 nil/false 时**每次重算**（正确性碰巧没错，性能静默劣化——热路径上是隐藏的 3 倍）；以及**非线程安全的懒初始化**（并发下双份初始化：连接、注册器、文件句柄泄漏）。修法：`return @x if defined?(@x)` 惰性但准确；或 boot 期急切初始化（实例级懒加载十有八九不如启动时算清）。
- `rescue nil`（104 节个案在此收账）：modifier 形态 `x = risky rescue nil` 吞 StandardError 含 NoMethodError——**左手边的 typo 也被吞**（`x = typo_method rescue nil` 永远 nil），调试成本全队摊；nil 结果继续传播，炸在十个调用层之外。

```ruby
# 惯犯名单（评审 grep 表）：
@conn ||= build_conn                       # 坑：nil 重算 + 并发双建
x = risky rescue nil                       # 坑：吞 typo，静默 nil
v = config[:timeout]                       # 坑：[] 默认 nil 藏配置错字
v = config.fetch(:timeout)                 # 修：KeyError = 配置错字的探测器
send("#{mode}_run")                        # 坑：动态派发（38/106 节）
for i in items do ... end                  # 坑：for 泄漏/共享变量，块作用域才安全
# while 循环里 define_method/lambda：共享同一作用域变量（块才有每次新鲜绑定）
i = 0
while i < 3; procs << -> { i }; i += 1; end # 三个 proc 全看 3（经典闭包陷阱）
```

- 时间与时区泄漏三连：`Time.now`（服务器时区，业务代码禁——`Time.current` + 应用时区）；`Date.today`（时区盲的日期边界——西部用户的前端后半夜报表跨日）；跨 DST 的日期算术（`+1.day` 是 86400s，跨令时日历日漂移——日历语义用 `advance(days: 1)`，时间轴语义用 duration，评审问一句"要日历还是要秒数"）。
- 剩余惯犯速射：`Hash#[]` 藏配置错字（fetch 或 dry-config）；`@@` 类变量（继承共享 surprises，一律换类级实例变量）；`Proc#return` 语义（proc 的 return 从**定义它的方法**返回——DSL 块里一颗雷，要早退用 `next` 或改 lambda）；`Struct.new` 写进方法体（每次调用重定义常量 + 方法缓存失效，25 节）。
- 纪律：反模式清单季度更新（事故复盘 112 节的固定产出），每条配 grep 命令进 CI 扫描——清单不进 lint 等于不存在。

## 110. p95 飙升事故解剖流程

- **排除序（固定，不按感觉走）**：①**变更关联**（30 分钟内回答"改了什么"：部署时间线、feature flag、配置、gem 版本、上游公告——相关即优先回滚，回滚永远快于根因英雄主义）；②**流量形态**（总量↑？新客户端批量轮询？先按端点分解：全域涨 = 基础设施层（DB/网络/GC），单点涨 = 代码路径）；③**GC 与分配**（`GC.stat` cycles/请求↑？major GC 风暴——malloc 阈值、新 gem 在热路径分配（13/14/20 节），分配数看板直接指认）；④**锁与队列**（Mutex 争用、**连接池耗尽**（82 节：pool 等待时间）、外部依赖时延、缓存失效风暴（86 节惊群：版本 bump × 流量峰））；⑤**环境干扰**（同宿主邻居、DB 备份窗口、cron 撞车、在线迁移持锁（112 节第三案））。

```text
# 解剖 runbook（时间轴纪律比结论更重要）：
#   T+0    定角色：一人看指标、一人看日志/追踪、一人通报——先分工再诊断
#   T+5    留证：sigdump/rbspy 快照（92 节）+ APM p95 最差 trace 列表导出
#   T+15   排除序走完 ①②，输出"最可能变更面"
#   T+30   决策点：回滚/开关降级 vs 继续深挖——回滚不必等根因
#   之后   修复后复盘：5 Whys 落到"哪个门禁漏了它"（70 节预算补一条）
# 记录物：时间轴 + 指标截图 + 决策理由——写进事故档案（112 节模板）
```

- 指标纪律：p95 看板必须能**一键切维度**（端点 × 实例 × 依赖 × 时间）——事故现场最贵的浪费是"等一个能切维度的看板"；部署标记直接画在所有时序图上（相关性自查靠眼见为实）。
- 坑①：全场慢时集体盯应用层——先看 DB 与网络（一个慢查询能演全场事故，84 节 N+1/112 节锁案例）。
- 坑②：平均值骗人（双峰分布：一半请求 50ms、一半 2s，均值 1s 谁也不像）——p50/p95/p99 三线同看，双峰即"两个问题混在一个桶"。
- 坑③：重启当药吃——重启清了证据（连接状态、内存、缓存），症状缓解根因蒸发，下周同一时间再见；重启前先留证（92 节）。

## 111. OOM 与 RSS 爬升事故解剖

- 判别树（91 节手册的事故态用法）：**第一刀：Ruby 堆涨不涨**——`GC.stat[:heap_live_slots]` 趋势：堆涨 → 层②泄漏 → 堆 dump 三点差分指认 retainer；堆稳 RSS 涨 → 看 smaps：malloc 碎片（`[heap]` 大段 → jemalloc 候选，16 节）/ mmap 段（IO 缓冲、大 String 容量预留）/ native 泄漏（69 节 C 扩展，valgrind）；**第三刀：进程结构**——线程数（每栈 ~1MB，`ls /proc/PID/task | wc`）、纤数（57 节孤儿纤）、FD 数（`ls /proc/PID/fd`——连接池 checkout 泄漏）。
- **止血优先于根因**（OOM 是分钟级事故，取证是小时级工作）：①worker 回收（`pumactl phased-restart`/restart——地址空间换新，买数小时）；②降 worker 数 + 摘流量（保主实例）；③关嫌疑 feature flag（最近变更关联，110 节第一问）；④设 oom_score_adj 保护关键邻居（SQLite 同机部署时 DB 死最后，76 节）；⑤所有止血动作记录时间——重启前 sigdump + gcore 留证（92 节）。

```text
# 判别树速查（runbook 首屏）：
#   RSS 涨 + heap_live 涨     => dump 差分（heapy）→ retainer 类/文件 → 修上限/清理
#   RSS 涨 + heap 稳 + [heap] => jemalloc 灰度一台验证（smaps before/after）
#   RSS 涨 + heap 稳 + mmap   => 大对象/IO 缓冲审计（21 节 memory_profiler retained）
#   RSS 涨 + 堆稳 + FD 涨     => 连接/句柄泄漏：池配置、未 join 线程
#   死后取证（OOM = SIGKILL 无 core）：dmesg -T | grep -i oom 重建受害序
```

- 堆 dump 差分要点（23 节的事故节奏）：三时点（T0/T+30/T+60）同进程采样比"一个快拍"有效十倍——看**增量**归因（heapy 的 generation 视图）；diff 指认 top 增长类后用 `ObjectSpace.reachable_objects_from` 反查 retainer——记住 memsize 浅尺寸陷阱，看谁引用，不看谁大。
- 固化（每次 OOM 复盘的三件产出）：①RSS/heap_live 斜率告警（阈值告警永远晚一拍，斜率告警提前小时级）；②大 gem 升级的"24 小时内存 canary"（升级部署单实例观察）；③回到 91 节手册补一条根因条目——手册是活的，事故是它的食物。

## 112. Rails 生产事故复盘三例

- **例一：缓存版本 bump × 流量峰 = 惊群雪崩**。时间轴：14:03 部署（模型加了 `touch` 传播，一整族 cache_version 翻新）→ 14:05 热点页 p95 从 120ms 走到 8s → DB active 连接打满、连带其它端点连锁超时。取证：APM 显示同一秒缓存命中率从 97% 掉到 4%，DB 慢查询全是"惊群重算"那条重查询；代码侧确认 fetch 没配 `race_condition_ttl`。根因：失效语义变更没和容量语义一起评审（86 节）。固化：①race_condition_ttl 成为 fetch 的默认参数（lint 强制）；②部署后预热脚本（主动填 top 键）；③缓存命中率与重算耗时进部署看板（每次部署盯 10 分钟）。

```text
# 例二：外部依赖静默挂起 → Puma 线程池 jam（82 节实战版）
# 时间轴：上游网关 TCP 半开（SYN 通、不回数据）→ Net::HTTP 默认 60s read
#   → 8 线程 60s 钉死 → pool_capacity=0 持续 → nginx 502 洪峰
# 取证：sigdump 十几条栈全在 net_http read_nonblock；Puma.stats busy==max
# 处置：热修外呼超时（2s/5s）+ 信号量限并发 + 断路器开关（feature flag 下线依赖）
# 固化：①全部外呼超时预算表（评审必填项）②pool_capacity==0 告警（2 分钟阈值）
#   ③依赖故障演练（半开态注入）进季度演练清单
```

- **例三：在线迁移持锁 → 全站请求排队**。时间轴：热点表 `add_index`（无 CONCURRENTLY）拿 AccessExclusiveLock → 排队写请求吃满 AR 池 → web 全线 p95 飙（110 节排除序第一问就撞上）。取证：`pg_stat_activity` 里一串 `wait_event_type=Lock`；Kamal 部署日志显示迁移在 boot 前置任务里同步执行。处置：杀迁移连接解锁、改用 CONCURRENTLY + 分批 backfill；过程中确认部署顺序（先代码后迁移的兼容纪律）。固化：①strong_migrations 进 CI（危险迁移直接红）；②大表变更走 playbook（concurrent 索引、分批回填、双写窗口）；③迁移执行时长进部署输出（>1s 即标红提醒评审）。
- 三例共同骨架（复盘模板）：**取证（工具+时间轴）→ 根因（机制而非人）→ 固化（门禁/告警/演练，写明落到哪一节）**；任何一条"以后注意"没转成 lint/告警/清单项的复盘，都算没做完——事故档案的验收标准是"同样的坑被门禁拦住"，不是"大家深受教育"。
