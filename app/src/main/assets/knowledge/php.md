<!-- kb: id=php | role=builtin_coding_php | title=PHP 专家知识库 | version=1 -->
# PHP 专家知识库

## 必看内容

- **8.4/8.5 双窗口是当前基线**：8.4（2024-11）/8.5（2025-11）活跃维护，
  8.3 只剩安全修复；语言红利按序吸收——非对称可见性 > 惰性对象 >
  属性钩子 > 管道操作符；框架联动定底线（Symfony 8 要 8.4，
  Laravel 12 要 8.2）（见 1、12、101 节）。
- **属性钩子是"方法调用的语法糖"**：每次访问走隐藏 handler，热循环有
  代价；readonly 只能配 get 钩子；接口可声明 `{ get; }` 契约；
  子类重写钩子集合不能变形（见 2-4 节）。
- **惰性对象二分法**：ghost 保身份、initializer 可写 readonly 后备；
  proxy 换取 final 类兼容但要付转发层；未初始化 ghost 在析构时仍会
  触发 initializer（见 5-6 节）。
- **strict_types 只约束"调用点所在的文件"**：库声明 strict 管不住
  调用方；跨文件语义漂移是隐性 bug 源；8.1 起 null 传内部非空参数
  弃用是最大面积升级税（见 13-14 节）。
- **readonly 一次性重初始化只发生在 `__clone()` 内**：with 模式要走
  override 隧道；子类完全不能改属性类型（属性类型不变性）
  （见 9、15 节）。
- **OPcache 生产铁律**：validate_timestamps=0、三段 SHM 按实测扩容
  （内存/interned strings/keys）、盯 oom_restarts 与 hash_restarts；
  部署复位排序：蓝绿池 > reload > opcache_reset（见 33-35、44、103 节）。
- **JIT 预期管理**：jit_buffer_size=0 等价全关；web I/O 负载 ±0-5%，
  CPU 密集才显著；Xdebug 加载即失效；file_cache+JIT 组合有崩溃前科
  （见 36-37 节）。
- **FPM 先算内存预算再谈并发**：pm=static + max_children=可用内存/
  实测 RSS；max_execution_time 只计 CPU 时间，墙钟必须
  request_terminate_timeout 兜底（见 39-41 节）。
- **事件循环一阻塞全阻塞**：Fiber 不是线程；PDO/sleep/DNS/curl_exec
  都是阻塞点；黑名单 + loop lag 看门狗 + 进程池卸载三件套
  （见 45-46、51-52 节）。
- **长驻进程（Octane/Swoole/worker）= 状态泄漏放大器**：静态属性、
  单例可变态、auth 用户、DB 事务跨请求存活；scoped 绑定 + flush
  矩阵 + 串味测试是标配（见 50、97、110 节）。
- **Composer 生产三旗**：--no-dev --optimize-autoloader
  --classmap-authoritative（运行期生成类要先预生成或放弃
  authoritative）；CI 钉 validate --strict + 每日 audit
  （见 55-56、59 节）。
- **反序列化双雷**：unserialize 必须 allowed_classes 白名单 + 签名；
  phar:// 元数据反序列化可由 file_exists 触发、phar.readonly=1
  挡不住读路径（见 68-69、112 节）。
## 快速索引

| 想找什么 | 去哪一节 |
| --- | --- |
| 8.4/8.5 升级决策 | 1. PHP 8.4/8.5 基线与升级决策模型 |
| 属性钩子怎么写 | 2. 属性钩子：get/set 实战与接口属性 |
| 钩子重写与性能 | 3. 属性钩子重写兼容与性能边界 |
| public(set) 用法 | 4. 非对称可见性：public(set) 的组合拳 |
| 惰性对象 ghost/proxy 选型 | 5. 8.4 惰性对象：ghost 与 proxy 抉择 |
| 惰性对象的坑 | 6. 惰性对象深坑：readonly、克隆与身份 |
| 8.5 管道操作符 | 7. 8.5 管道操作符与表达式管线 |
| new 无括号与 FCC | 8. new 无括号与 first-class callable 边界 |
| readonly 与 with 克隆 | 9. readonly 语义演进与 8.3 克隆重初始化 |
| 枚举深水与序列化 | 10. 枚举内部机制与序列化边界 |
| #[\Deprecated] 治理 | 11. #[\Deprecated] 与弃用信号工程化 |
| 8.3→8.5 迁移清单 | 12. 8.3→8.5 迁移陷阱地图 |
| strict_types 作用域 | 13. strict_types 的文件作用域真相 |
| 松散比较陷阱 | 14. 弱模式数字串与 8.0 比较语义修正 |
| 型变规则矩阵 | 15. 型变矩阵：参数逆变、返回协变、属性不变 |
| never 返回类型 | 16. never 返回类型与 void 的层级关系 |
| 交集/DNF/独立类型 | 17. 交集、DNF 与 true/false/null 独立类型 |
| 类型化类常量 | 18. 8.3 类型化类常量与动态常量取值 |
| 接口契约与抽象钩子 | 19. 接口契约工程：接口属性与抽象钩子 |
| Attributes 反射陷阱 | 20. Attributes 反射：IS_INSTANCEOF 精确匹配陷阱 |
| PHPStan 泛型注解 | 21. PHPStan 泛型注解与条件返回类型 |
| baseline 棘轮 | 22. 基线棘轮与静态分析迁移工程 |
| zval 与 COW 内幕 | 23. zval 写时复制与引用计数内幕 |
| 数组 packed/hash | 24. 数组内部：packed 与 hash 的转换代价 |
| foreach 写陷阱 | 25. foreach 与大数组操作的 COW 陷阱 |
| interned strings | 26. 字符串驻留与二进制安全 |
| 循环 GC 策略 | 27. 循环引用 GC：阈值与 FPM/Octane 策略 |
| WeakMap 生命周期 | 28. WeakReference/WeakMap 生命周期契约 |
| 引用符隐藏税 | 29. 引用 & 的隐藏税与 COW 破坏 |
| 生成器 send/getReturn | 30. 生成器深用：send、getReturn 与委托 |
| 内存取证与预算 | 31. 内存取证与 FPM 内存预算 |
| clone 深浅拷贝 | 32. clone 语义与深浅拷贝纪律 |
| OPcache 容量规划 | 33. OPcache 共享内存结构与容量规划 |
| validate_timestamps 复位 | 34. opcache_validate_timestamps 与部署复位 |
| preload 预加载 | 35. 预加载：preload 设计与启动失败风险 |
| JIT 何时有用 | 36. JIT tracing 深水：何时真的有用 |
| JIT 与 file_cache 崩溃 | 37. JIT 缓冲区与 file_cache 崩溃案例 |
| realpath/字符串缓冲 | 38. interned strings 与 realpath cache |
| FPM pm 与 max_children | 39. PHP-FPM pm 模式与 max_children 计算 |
| FPM slowlog/status | 40. FPM 观测：status、slowlog 与墙钟保护 |
| max_execution_time 语义 | 41. max_execution_time 的 CPU 时间语义 |
| APCu 与序列化器 | 42. APCu 用户态缓存与序列化器选型 |
| 基准与画像工具 | 43. 基准纪律与生产画像工具链 |
| 冷启动预热 | 44. 冷启动与部署后预热策略 |
| Fiber 语义边界 | 45. Fiber 语义：不是线程也没有调度器 |
| 阻塞调用黑名单 | 46. 事件循环纪律：阻塞调用黑名单 |
| ReactPHP 与 Amp 选型 | 47. ReactPHP 与 Amp v3/Revolt 选型 |
| Swoole Hook 机制 | 48. Swoole/Swow 协程运行时与 Hook 机制 |
| FrankenPHP worker | 49. FrankenPHP worker 模式与常驻心智 |
| 长驻进程状态卫生 | 50. 长驻进程内存卫生与请求隔离 |
| CPU 并行卸载 | 51. CPU 密集卸载：amphp/parallel 与进程池 |
| 阻塞探测工具链 | 52. 阻塞调用探测工具链 |
| 协程数据库连接池 | 53. 协程数据库：连接池与慢查询放大 |
| 优雅停机与信号 | 54. 优雅停机：信号、在途回收与超时 |
| composer.lock 纪律 | 55. composer.lock 纪律与 validate --strict |
| classmap-authoritative | 56. classmap-authoritative 的收益与边界 |
| monorepo path 仓库 | 57. path 仓库与 monorepo 拆包策略 |
| composer bin 隔离 | 58. composer bin 与工具依赖隔离 |
| composer audit 供应链 | 59. composer audit 与供应链事件响应 |
| 版本约束陷阱 | 60. 版本约束与平台锁定的解析陷阱 |
| composer scripts 钩子 | 61. composer scripts 与部署钩子纪律 |
| conflict 与降级预案 | 62. conflict 防御与依赖降级预案 |
| 私有仓库与镜像 | 63. 私有仓库与镜像策略 |
| Phar 分发陷阱 | 64. Phar 分发与 phar.readonly 陷阱 |
| SQLi 残留面 | 65. SQLi 现代防线与原生查询残留 |
| PDO 预编译抉择 | 66. PDO 原生预编译与模拟预编译抉择 |
| 上传安全 | 67. 上传处理：类型欺骗与 Web 服务器注入 |
| unserialize 白名单 | 68. unserialize 白名单与 gadget 链 |
| PHAR 攻击面 | 69. PHAR 流包装器攻击面 |
| __serialize 迁移 | 70. __serialize/__unserialize 迁移纪律 |
| Argon2id 调参 | 71. password_hash 与 Argon2id 调参 |
| 会话安全矩阵 | 72. 会话安全：再生、SameSite 与 Cookie 矩阵 |
| hash_equals 与随机数 | 73. 时序安全比较与随机数纪律 |
| XSS 基线与上下文 | 74. XSS 基线：8.1 默认 flag 与逃逸边界 |
| header 与输出缓冲 | 75. 头部纪律与输出缓冲陷阱 |
| Throwable 家族 | 76. Throwable 家族地图与分界 |
| ValueError/TypeError 时代 | 77. 内部函数的 ValueError/TypeError 时代 |
| @ 抑制符债务 | 78. @ 抑制符债务与错误处理器交互 |
| finally 深水 | 79. finally 深水：吞异常、替换与生成器 |
| shutdown 兜底 | 80. shutdown 处理器与致命错误回收 |
| 异常分层设计 | 81. 异常层级设计：域异常与基础设施异常 |
| error_reporting 配置 | 82. error_reporting 生产配置与全局策略 |
| assert 生产语义 | 83. assert 与 zend.assertions 的生产语义 |
| 结构化日志 | 84. 结构化日志架构与 PSR-3 边界 |
| PHPUnit 11/12 迁移 | 85. PHPUnit 11/12 迁移硬点 |
| 快照测试纪律 | 86. 快照测试与 --updateSnapshots 纪律 |
| 数据库测试保真 | 87. 数据库测试：事务回滚与环境保真 |
| 时钟抽象纪律 | 88. 时钟抽象与测试时间纪律 |
| Infection 与 MSI | 89. 变异测试 Infection 与 MSI 门槛 |
| 并行测试污染 | 90. 并行测试与状态污染排查 |
| 特征测试安全网 | 91. 特征测试：遗留代码安全网 |
| HTTP 假件与契约 | 92. HTTP 假件与契约测试 |
| Laravel 容器深水 | 93. Laravel 容器与上下文绑定深水 |
| Eloquent N+1 解剖 | 94. Eloquent N+1 解剖与 preventLazyLoading |
| chunk/cursor/lazy | 95. chunk/cursor/lazy 分页语义差异 |
| 中间件与 terminate | 96. 中间件管道与 terminate 阶段 |
| Octane 泄漏矩阵 | 97. Octane 状态泄漏矩阵 |
| Symfony DI 与 lazy | 98. Symfony DI：编译容器与 lazy proxy |
| 内核事件/env 处理器 | 99. Symfony 内核事件与 env 处理器 |
| 队列幂等与背压 | 100. 队列幂等与背压设计 |
| 框架升级 LTS 窗口 | 101. 框架升级纪律与 LTS 窗口 |
| 模板逃逸保证 | 102. 模板引擎的逃逸保证与例外 |
| 部署原子性与复位 | 103. 部署原子性与 opcache 复位策略 |
| 不可变产物 | 104. 不可变产物与配置外置 |
| 健康探针设计 | 105. 健康检查与就绪探针设计 |
| 容器化镜像 | 106. 容器化：镜像分层与 FPM 入口 |
| 观测三件套接入 | 107. 观测接入点：日志、指标、追踪 |
| socket/TCP upstream | 108. Socket 与 TCP upstream 混部 |
| 反模式门禁清单 | 109. 高频反模式清单与评审门禁 |
| Octane 状态泄漏复盘 | 110. 事故复盘：Octane 状态泄漏连环案 |
| OPcache 容量螺旋复盘 | 111. 事故复盘：OPcache 容量螺旋案 |
| 反序列化 PHAR 复盘 | 112. 事故复盘：反序列化与 PHAR 组合案 |
## 1. PHP 8.4/8.5 基线与升级决策模型

- 支持矩阵：8.4（2024-11-21）与 8.5（2025-11-20）并行活跃维护，8.3 已过 bugfix 期仅剩安全修复——新项目最低 8.3、目标 8.4/8.5，别再为 8.2 写兼容层。
- 8.4 语言面清单：属性钩子、非对称可见性、惰性对象、`new` 无括号、`#[\Deprecated]`、array_find/any/all、mb_trim 家族、新 DOM API（`Dom\HTMLDocument`）、PDO 驱动子类（`Pdo\PgSQL` 等）。
- 8.5 清单：管道操作符 `|>`、URI 扩展（RFC 3986 与 WHATWG 双实现）、exit/die 表达式化、array_first/array_last、Serializable 硬弃用。

```php
// 部署守卫只放"硬依赖"，别把业务开关藏进版本判断
if (PHP_VERSION_ID < 80400) {
    error_log('runtime too old: ' . PHP_VERSION);
    http_response_code(503);
    exit(1);
}
```

- 决策口径：框架约束优先——Laravel 12 需 PHP ≥8.2、Symfony 8 需 ≥8.4；版本跟框架主线走，不裸奔追新特性。
- 升级次序：CI 双版本矩阵（最低支持版 + 最新版）→ 收集 E_DEPRECATED → 按池灰度，而非全量切换（12、103 节）。
- 回滚注意：扩展 ABI 随 PHP 版本重编译；opcache file_cache 与二进制绑定，降级前必须清空目录（37 节）。

## 2. 属性钩子：get/set 实战与接口属性

- 钩子把 getter/setter 折进属性声明：钩子体内 `$this->email` 访问的是后备存储，不会递归触发自身——这是官方语义不是约定。
- 虚拟/存储二分：只有 get 且不落存储 = 虚拟属性；带 set 必须有后备。接口与抽象类可声明 `public string $email { get; }` 作为只读契约，实现类必须给实现。

```php
interface HasEmail { public string $email { get; } }   // 8.4 接口属性
final class User implements HasEmail
{
    public string $email {
        get => $this->email;
        set (string $v) {                    // 钩子内写后备，不递归
            filter_var($v, FILTER_VALIDATE_EMAIL)
                or throw new InvalidArgumentException('bad email');
            $this->email = strtolower($v);
        }
    }
}
```

- `$u->email++` 等复合赋值走 get→set 两条钩子；readonly 只允许 get 钩子；带钩子属性禁止按引用取值；serialize 只看后备（对外统一 `__serialize`，70 节）。

## 3. 属性钩子重写兼容与性能边界

- 形状不变性：子类重写带钩子属性必须重声明同名钩子集合；父虚拟不能被子类落存储，反向同样致命——比类型不变性多一层约束（15 节）。
- 非对称可见性继承：set 侧只能收紧（protected(set)→private(set) 合法），放宽直接编译错误。
- 性能真相：每次访问编译为隐藏 handler 调用——比裸属性贵一次方法调用；热循环百万次访问可见差距，DTO/值对象优先裸属性。

```php
interface Timestamped { public \DateTimeImmutable $at { get; set; } }
final class Post implements Timestamped
{
    public \DateTimeImmutable $at {                  // 必须重声明同形钩子
        get => $this->at;
        set (\DateTimeImmutable $v) { $this->at = $v; }
    }
}
```
- `ReflectionProperty::isVirtual()` 区分虚拟/存储；trait 中定义钩子属性时后备冲突按类作用域裁决——上线前特征测试锁行为。
- 与 lazy objects 组合正常（访问触发初始化），但 var_export/序列化会暴露后备名——调试输出别外发。

## 4. 非对称可见性：public(set) 的组合拳

- 声明序固定：`public private(set) string $token;`（先读侧后写侧）；也可写成钩子里 `private set`。语义：类内随便写，外部写即 Error。
- 写操作全按写侧权限拦：unset、`$o->list[] =`、复合赋值都是"写"；读侧照常公开——这正是"公开只读、内部演化"的语义。

```php
final class Invoice
{
    public function __construct(
        public private(set) array $lines = [],      // 外部只读集合
        public protected(set) ?int $paidAt = null,  // 子类可改
    ) {}
    public function pay(): void { $this->paidAt = time(); }
}
```

- 与 readonly 的取舍：readonly 一次性初始化后类内也不可写；private(set) 允许类内状态机演化——聚合根/实体优先 private(set)，值对象用 readonly。
- clone 不受写侧限制：with 模式可用"clone + 类内写"实现，比 readonly 的 __clone 隧道（9 节）简单得多。
- 反模式：全字段 public(set) 只为省构造器——等于回到动态属性时代，评审直接打回。

## 5. 8.4 惰性对象：ghost 与 proxy 抉择

- 入口：`ReflectionClass::newLazyGhost($initializer)` / `newLazyProxy($initializer)`；两者都跳过构造器，initializer 首次触达时执行。
- ghost：对象本身就是真身，首次访问属性/方法时就地填充——身份（===）天然成立，适合实体、可变聚合与整树延迟。
- proxy：真身独立创建、占位对象转发访问——适合 final 类与需要构造器副作用的场景，代价是双份对象 + 每次访问过转发层。

```php
$r = new ReflectionClass(User::class);
$u = $r->newLazyGhost(function (User $u) use ($repo, $id) {
    $repo->hydrate($u, $id);   // 8.4 红利：可写 readonly 后备
});
$u->email;                     // 此刻才查库——之前零开销
```

- 8.4 特有：initializer 内允许写 readonly 属性（延迟初始化不算二次赋值），这是老 __get 代理做不到的。
- ORM 对接：Doctrine 等逐渐把魔法代理换成原生 ghost——final 类与代理开销两个老痛点一次解决。
- 判定工具：`ReflectionClass::isUninitializedLazyObject($u)`；初始化后普通反射完全看不出它是懒的。

## 6. 惰性对象深坑：readonly、克隆与身份

- 析构语义：未初始化的 ghost 在销毁时会触发 initializer（保证 __destruct 正确运行）；用 options `skipDestructors: true` 关闭——析构路径做重活会放大进程退出停顿。
- proxy 的 === 与真身永不相等：单元测试、缓存键、对象身份比较的系统中用 ghost，否则出现"同一实体两个身份"的诡异 bug。
- 已知边界：readonly 关联字段 + ghost 在 8.4 早期补丁有回写 bug 报告——钉最新补丁版，关键路径写特征测试锁行为。

```php
$r->markLazyAsInitialized($u);   // 手动标记已初始化
$r->resetLazyObject($u);         // 已填充对象重新变懒（内存战术回收）
assert($r->isUninitializedLazyObject($u));
```

- 反序列化/克隆会立即物化：跨进程/缓存传"lazy"没有意义——惰性是进程内语义，序列化前要么物化要么重建。
- `initializeAt` 类选项不存在——不要指望"析构前才初始化"这种精细控制，自己显式管理生命周期。

## 7. 8.5 管道操作符与表达式管线

- `表达式 |> callable`：左侧返回值作为唯一实参传给右侧，左结合；右操作数允许字符串 callable、闭包、箭头函数、first-class callable `strtoupper(...)`、静态方法引用。
- 语义是"传值"不是"传引用"：`$xs |> 'sort'` 得到 true（sort 的返回值）而不是排好的数组——按引用修改族必须包箭头函数。

```php
$slug = $title
    |> 'trim'
    |> 'strtolower'
    |> fn($s) => preg_replace('/[^\w]+/u', '-', $s)
    |> fn($s) => trim($s, '-');
// 坑：|> 'sort' 返回 bool；正确写法 fn($a) => [... 排序 ...]
```

- 适用边界：自由函数组合的转换管线最受益（字符串/集合/校验链）；对象自带流式 API 的场景保持方法链，混用优先一致性。
- 8.5 exit/die 表达式化后 `|> 'exit'` 合法——能跑但反直觉，评审禁用；exit 成为具名函数式调用，可出现在任意表达式位置。
- 管道右侧不可是语句（echo/require）；不会自动解包数组——需要时显式 `...` 展开在箭头函数里做。

## 8. new 无括号与 first-class callable 边界

- 8.4：`new Foo->bar()` 等价 `(new Foo)->bar()`，可继续链下去；但变量类名例外：`new $x->bar()` 仍是"实例化 `$x->bar` 所指的类"——新旧语法在变量场景含义相反，是真实事故点。
- FCC（8.1）边界：`strlen(...)`、`$this->m(...)`、`Foo::m(...)` 合法；字符串与数组 callable 不行——`'trim'(...)`、`[$o, 'm'](...)` 直接语法错误，用裸标识符或 `Closure::fromCallable`。

```php
$map = [
    'upper' => strtoupper(...),      // ✓ 裸标识符 FCC
    'fail'  => [Gateway::class, 'fail'](...), // ✗ 语法错误
];
// 正确：Gateway::fail(...) —— 但实例方法得到的是未绑定 $this 的闭包
$f = Gateway::instanceStep(...);     // 未绑定 $this：直接调用抛 Error
$f2 = Closure::bind($f, $gateway, Gateway::class); // 手动绑
```

- `new Foo(...)` 是"带 spread 的实例化"不是构造器 FCC；`Foo::__construct(...)` 语义怪异，构造器闭包用工厂函数替代。
- 迁移纪律：字符串 callable 全量换 FCC——反射可读、静态分析可追踪，还能消掉 `call_user_func` 的一层间接。

## 9. readonly 语义演进与 8.3 克隆重初始化

- readonly 一次性初始化；8.3 唯一豁免：`__clone()` 作用域内每个 readonly 属性可再初始化恰一次，二次赋值即 Error。with 方法不在 __clone 作用域——用静态覆盖表 + spl_object_id 把参数搬进 __clone，克隆后立即清理防 fiber 重入。

```php
final class Money
{
    private static array $ov = [];                  // 覆盖隧道表
    public function __construct(public readonly int $cents) {}
    public function with(int $cents): static {
        self::$ov[spl_object_id($this)] = ['cents' => $cents];
        try { return clone $this; } finally { unset(self::$ov[spl_object_id($this)]); }
    }
    public function __clone() {  // ✓ 8.3+：__clone 内一次性重初始化
        $this->cents = self::$ov[spl_object_id($this)]['cents'] ?? $this->cents;
    }
}
```

- readonly class（8.2）：全部实例属性自动 readonly、禁静态属性、禁止非 readonly 类继承 readonly 类——继承链必须同质。
- 复杂度警报：需要频繁 with 的模型（配置对象、消息体）用 private(set)（4 节）或构造器重建，别把 __clone 隧道玩成全家桶。
## 10. 枚举内部机制与序列化边界

- case 是单例对象：同一 case 全进程恒 ===、clone 直接 Error；WeakMap 可拿 case 当键且生命周期稳定——比 spl_object_id 表安全（id 会复用）。
- serialize 专用格式内嵌类名+case 名，unserialize 会校验 case 依然存在（删掉枚举值后旧缓存数据直接抛）；json：纯枚举输出 case 名、带值枚举输出标量。

```php
enum Status: string {
    case Active = 'active';
    case Draft  = 'draft';
    const Default = self::Active;   // 常量可引用 case
}
Status::tryFrom('x');               // null，原生实现非 try/catch
Status::from('x');                  // ValueError
```

- 枚举禁属性（实例与静态都禁），常量/方法自由；trait 进枚举不能带属性——"枚举注册表"用接口+静态映射表实现。
- cases() 顺序即声明顺序，可依赖但别把它当对外协议承诺（加 case 排序即变）。
- 接口+带值枚举是"类型化字典"标准形：match($status) 穷举 + #[\Deprecated] 标记弃用 case 双保险。

## 11. #[\Deprecated] 与弃用信号工程化

- 8.4 内置属性：标在函数/方法/类常量/枚举 case 上，调用触发 E_USER_DEPRECATED；message 自动追加"use X instead"，since 记录起始版本——库作者与内部团队的迁移动能来源。
- 双通道纪律：运行时信号（日志采集+统计）与静态通道（phpstan-deprecation-rules 报调用点）同时上，只留一个必漏。

```php
#[\Deprecated(message: 'use Currency::format()', since: '2.3')]
function money_fmt(float $amount): string { /* legacy */ }

// 全局弃用计数器 → 按 sprint 消化（弃用积分墙）
#[\Deprecated(since: '3.0')]
public const LEGACY_TTL = 3600;
```

- 与 @ 的相互作用是治理漏洞：@ 压得住弃用告警（78 节）——CI 里"零 @ 门禁"配套，防止弃用被静默吞掉。
- 采集侧把 E_USER_DEPRECATED 按调用方（debug_backtrace 第一帧）聚合出"债务 Top10"，比口头排期有效。

## 12. 8.3→8.5 迁移陷阱地图

- 8.4 大面积弃用：隐式可空参数 `function f(T $x = null)` 必须改 `?T $x = null`（生成代码/老骨架重灾区）；E_STRICT 常量弃用——按位组合它的 error_reporting 表达式会整体归零；filter.default INI 弃用（靠隐式过滤的老应用直接裸奔）。
- 8.4 移除/行为变化：strftime 族移除（8.1 弃用转正）→ 迁 IntlDateFormatter；bcmath 非法输入从 warning+0 改抛 ValueError。
- 8.5 硬弃用两项：Serializable（无论是否补 __serialize 都告警）；隐式 float→int 精度丢失从参数场景扩展到普通赋值/下标/运算。

```php
// 升级前哨：全量弃用采集，双版本矩阵跑
error_reporting(E_ALL);
set_error_handler(function (int $no, string $msg): bool {
    if ($no === E_DEPRECATED || $no === E_USER_DEPRECATED) {
        $this->collector->deprecation($msg, debug_backtrace(DEBUG_BACKTRACE_IGNORE_ARGS, 3));
    }
    return false;   // 继续走默认处理
});
```

- 迁移窗口策略：8.3/8.4 双跑一版（只采集不阻塞）→ 8.4 主力 → 8.5 金丝雀；扩展 ABI 重编译 + file_cache 清空（37 节）。
- 数据库里的弃用别忘：老序列化负载（Serializable 类）会在 8.5 日志刷屏——迁移脚本先跑再升级。

## 13. strict_types 的文件作用域真相

- 严格性由"调用语句所在的文件"决定：strict 文件里发起的调用一律严格（含内部调用）；弱文件调用 strict 库的函数照样弱模式——库声明 strict_types 管不住调用方。
- 推论：同库跨文件自调用语义可能不一致；契约测试必须覆盖"strict 调用方"与"弱调用方"两种情形，这是隐性 bug 源。

```php
// lib.php（无 declare）：
function half(float $x): float { return $x / 2; }
// caller.php 声明 declare(strict_types=1)：
half('2.5');   // TypeError（strict 调用点）
// caller2.php 无 declare：
half('2.5');   // 合法：数字串弱转（弱调用点）
```

- 数字串边界（弱模式）：整数字符串可转 int/float；"5.0"→int 允许；"5x" 两种模式都 TypeError；8.1 起非整 float→int 参数弃用（12 节）。
- 纪律：新文件 declare(strict_types=1) 永远放首行（全文件生效、不能局部开关）；存量按文件粒度渐进 strict 化。
- 棘轮：CI 禁止新增非 strict 文件，比全局一刀切安全且可持续。

## 14. 弱模式数字串与 8.0 比较语义修正

- 8.0 修正：`0 == "abc"` 为 false（非数字串按字符串比较）；但 `"10" == "1e1"`、`"10" == 10.0` 仍为真——in_array/array_search 默认松散比较全踩。
- 铁律两条：数字串 vs 数字 → 双转数字比；非数字串 vs 数字 → 双转字符串比（8.0+）。switch 匹配同样松散；match（8.0）严格比较——迁移顺带修松散 bug。

```php
in_array('1e1', ['10']);        // true（松散）
in_array('1e1', ['10'], true);  // false——集合查一律 strict
$diff = match($mode) {          // 严格比较，类型不匹配即 UnhandledMatchError
    'fast' => 1,
    default => 0,
};
```

- 数组键规范化陷阱："08" 是字符串键、"8" 归一为 int 键 8——同一数据源两次入库可能产生两个键（24 节）。
- 空 null 陷阱：== null 与 == 0 在弱模式语义不同（"" == 0 为 true）；判空用 === null 或 is_* 显式。
- 升级检视：8.0 改动让一批"碰巧为 true"的老比较翻 false——升级 diff 审计重点查 == 与 switch。

## 15. 型变矩阵：参数逆变、返回协变、属性不变

- 重写三规则：参数只能放宽（union 扩集）、返回只能收窄（union 缩集）、构造器豁免（不参与 LSP 校验）——这是 7.4+ 的既有语义，但多数团队只知一半。
- 属性类型不变性：子类完全不能改属性类型，连 widening 都不行——想要"协变字段"用虚拟属性（2 节）或抽象 getter，别指望 override。

```php
class P { public function cmd(int|string $id): void {} }
class C extends P {
    public function cmd(int|string|float $id): void {}  // ✓ 参数放宽
}
class P2 { public int $v; }
class C2 extends P2 { public int|float $v; }            // ✗ Fatal：属性不变
```

- private 方法不参与重写校验（同名即遮蔽）——父类改签名时子类静默分叉，抽象层别用 private 占坑。
- PHPStan level 6+ 会报型变违规；把"协变属性幻想"在 CI 阶段清零，比运行期 Fatal 强。
- 枚举实现接口、trait 方法要求（19 节）同样受该矩阵约束——契约收紧方向永远单向。

## 16. never 返回类型与 void 的层级关系

- never（8.3 正式）是底部类型：函数必须 exit/throw；任何类型都可以被 never 替换（重写收窄方向），反向不行——`void→never` 合法、`never→void` 非法。
- 用途排序：①控制流标注——静态分析据此消灭 unreachable 代码；②守卫/中间件早退契约（调用方知道此路不返）。

```php
function redirect(string $url): never {
    header("Location: $url", true, 302);
    exit;
}
function guard(User $u): never {
    throw new ForbiddenException('user ' . $u->id);
}
// redirect('/x'); doMore();  ← PHPStan 报 doMore() 不可达
```

- 禁区：never 不能进 union、不能做参数/属性/常量类型；never 函数里 yield 直接 fatal（生成器返回类型是 Generator 不是 never）。
- 老代码迁移：`function fail()` 无返回标注的应优先标 never 而不是 void——它同时给静态分析与调用方正确信号。
- exit 表达式化（8.5，7 节）让 never + exit 组合在管道/高阶场景也可用。

## 17. 交集、DNF 与 true/false/null 独立类型

- 交集类型（8.1）：`Countable&Iterator`——同一值满足全部约束；DNF（8.2）：`(A&B)|null` 是唯一合法形——union 分支要么纯类型要么纯交集，`(A|null)&B` 直接语法错误。
- 独立类型（8.2）：true/false/null 可单独出现——`function find(): User|false` 的老 API 现在能被类型系统表达；新代码仍应 `?User` + 异常，别把错误塞返回值。

```php
function locate(): (HasName&HasId)|null { /* ... */ }
function isVip(): true { ... }        // 表达"恒真"契约（含 never 同理的语义收紧）
function parse(): string|false { ... } // 兼容老 API 的显式化；新代码禁
```

- false 型的兼容价值：包住不抛错的内部函数（preg_* 族）时让静态分析精确收窄，比裸 `|false` 文档强。
- null 独立类型让"显式 null 分支"可表达；`?T` 与 `T|null` 等价——团队统一一种写法避免评审噪音。
- 运行时校验注意：DNF 没有单函数可验（is_a 组合判断），assert/守卫要拆分支写。
## 18. 8.3 类型化类常量与动态常量取值

- 8.3 起常量可标类型：`const int STATUS = 1;`（含 enum case、数组、DNF）；接口常量随之为真契约——实现类常量类型必须匹配或收窄（int→int 可以，int→string 不行）。
- 动态常量取值：`Foo::{$name}`（8.3）替代 `constant(Foo::class . '::' . $name)` 字符串拼接——反射安全且静态分析可见（配合 @var 注形状）。

```php
interface Rates {
    public const float VAT = 0.19;          // 实现类必须给兼容 float
}
$name = 'VAT';
$r = Rates::{$name};                        // 8.3+ 动态取值
// 常量 vs 枚举：模块级参数用常量，域内封闭集合用 enum（有 cases/match）
```

- 常量重写陷阱：子类常量类型必须与父/接口一致或收窄——"同名常量换类型"是 Fatal 而不是 warning。
- 枚举 case 本身可作常量默认值（10 节）；类型化常量与 readonly 搭配构成真正的不可变配置对象。
- 动态取值 + 用户输入 = 常量注入面：$name 必须白名单，别把反射钥匙交给请求参数（65 节同思路）。

## 19. 接口契约工程：接口属性与抽象钩子

- 8.4 接口属性是"行为契约"：`public string $email { get; }` 要求实现类提供 get 行为（存储或虚拟皆可）——接口第一次能约束属性而不只是方法。
- 抽象方法在 trait 里同样有强制力：trait 定义 abstract 方法 → use 它的类必须实现——"模板方法+钩子"的老派组合，配合属性钩子可以做成纯声明式。

```php
interface Auditable {
    public \DateTimeImmutable $changedAt { get; }   // 属性契约
    public function auditName(): string;            // 方法契约
}
trait Timestamps {
    /** 抽象钩子：逼实现类给出自己的存储位置 */
    abstract public function now(): \DateTimeImmutable;
}
```

- 收紧方向单向律：新接口默认方法只能给默认实现（向下兼容）；接口属性一旦发布就是永久契约——钩子形状不能后加（3 节形状不变性）。
- 设计纪律：接口描述"能力"不描述"存储"；需要暴露可写性用 `{ get; set; }`，只读面最小化 `{ get; }`。
- 审计点：接口被多个 BC 层实现时，用特征测试矩阵锁"每个实现的契约行为一致"（91 节）。

## 20. Attributes 反射：IS_INSTANCEOF 精确匹配陷阱

- getAttributes(Foo::class) 按类名字符串精确匹配——子类属性实例不命中；要命中整棵继承树必须传 `ReflectionAttribute::IS_INSTANCEOF`，这是属性系统被"静默吞注解"的头号事故点。
- 实参校验发生在 newInstance() 而非声明处：非法实参（非常量表达式）在读取时才抛——错误诊断迟到，声明处不报。

```php
$attrs = $ref->getAttributes(
    Validation::class,
    ReflectionAttribute::IS_INSTANCEOF   // 不传：子类注解全丢
);
foreach ($attrs as $a) { $a->newInstance(); }
```

- newInstance() 每次都新建实例、无缓存——热路径（每请求扫注解的框架）必须缓存到静态表；属性类设计成不可变+无副作用。
- 实参限制：常量表达式（字面量/常量/enum case/常量数组；8.1 起允许 new）；不能传变量/函数调用。
- isRepeatable 才能同目标重复标注；组合注解（元注解）逐层 IS_INSTANCEOF 取——框架层封装成 AttributeReader 别到处裸反射。

## 21. PHPStan 泛型注解与条件返回类型

- 三件套：`@template T` + `@param iterable<T>` + `@return list<T>` 表达泛型容器；array shapes `array{id: int, items: list<Item>}` 精确化关联数组；`non-empty-array`/`non-empty-string` 收紧空值。
- 条件返回与断言：`@return ($strict is true ? User : ?User)`；`@phpstan-assert !null $user` 让守卫函数真正收窄调用方类型——比一堆 is 检查后的手动 @var 干净。

```php
/**
 * @template T
 * @param callable(): iterable<T> $source
 * @return list<T>
 */
function drain(callable $source): array { /* ... */ }

/** @return ($id is null ? null : User) */
function find(?int $id): ?User { /* ... */ }
```

- treatPhpDocTypesAsCertain 默认 true：文档类型与运行时不符时可能掩盖真实错误——关键模块配运行期 assert 双保险（83 节）。
- Larastan/psalm-laravel 把 Eloquent 魔法（whereX、scope、relation）纳入类型；魔法越重的基类越依赖插件——超出插件能力就收敛魔法，别硬写 ignore。
- 团队纪律：PHPDoc 泛型只在"被多处复用的容器/工具"上投入，业务类用形状+标量类型即可。

## 22. 基线棘轮与静态分析迁移工程

- 迁移四步：①level 0 装上并保持绿 → ②--generate-baseline 固化存量 → ③CI 校验 baseline 条目"只减不增" → ④每轮升 level（4→6→8），新代码永远按最高 level 跑。
- 规则包按需叠加：phpstan-strict-rules（松散比较/空容器）、phpstan-deprecation-rules（弃用调用）、phpstan-phpunit（mock 类型）——先严类型（level 5-6）再上规则包，顺序反了误报淹没团队。

```yaml
# CI 棘轮（伪码）：baseline 条目数只许下降
count: $(grep -c 'message:' phpstan-baseline.neon)
run: ./vendor/bin/phpstan analyse --memory-limit=1G
gate: $count -le $(cat phpstan-baseline.count) || fail
```

- 误报处理次序：修代码 > refine PHPDoc > ignore——ignore 必须带错误码+原因注释，无码的 @phpstan-ignore-line 禁止新增。
- PR 增量门禁：触碰文件不允许新增 baseline 条目（按路径过滤），存量债不背锅但别加新债。
- 季度运营：删掉已失效的 ignore、重生成 baseline、公布 level 上升计划——棘轮的意义在"持续收"，不在"一次到位"。

## 23. zval 写时复制与引用计数内幕

- 变量即 zval（类型+值+refcount）：赋值只复制 zval 槽并 ++refcount，O(1)；真正拆分发生在"写"时（Copy-On-Write separation）——"传大数组很贵"是错觉，"改大数组才贵"是真相。
- immutable zval：字面量数组/字符串进 OPcache SHM 后 refcount 不可变、跨 worker 共享、读取零拷贝（33 节）——框架启动红利的一半来自这里。

```php
$a = range(0, 1_000_000);
$b = $a;        // O(1)：只 +refcount
$b[0] = 1;      // 此刻 O(n)：整表 separation（ hashtable 拷贝）
$mem = memory_get_usage();   // 拷贝前后对比可见台阶
```

- refcount 归零立即释放；只有环走 GC（27 节）；debug_zval_refcount/xdebug 观察 refcount 仅限开发环境。
- 函数按值传参 + 函数内首写 = separation 发生在被调方——想要零拷贝就传只读，或显式按引用（29 节代价）。
- 性能审计法：memory_get_usage 在可疑行前后打点，"没改却涨"的行往往藏着隐藏写（序列化/日志引用）。

## 24. 数组内部：packed 与 hash 的转换代价

- PHP 数组=有序 hashtable：packed 形态（0..n-1 连续 int 键）退化为 C 数组——内存小、迭代快；一旦稀疏/混字符串键/负键即转通用 hash，全表重排 O(n)。
- 修复姿势：`array_values($a)` 重新 packed；顺序表保持"纯 list 语义"（只 append），键查找才用 map。

```php
$a = [10, 20, 30];      // packed
unset($a[1]);           // 稀疏 → hash（一次 O(n) 转换）
$a2 = array_values($a); // 重新 packed：修复首选
isset($map[$k]);        // O(1)；in_array($v, $list) 是 O(n)——查存在性用键
```

- 键规范化：整数字符串键归一 int（"8"→8），"08" 保持字符串——同源数据两次写入可能分裂成两个键（14 节）。
- 哈希攻击面：用户可控的大量字符串键可制造碰撞链，让插入退化为 O(n²)——大键集合入表要限容与限速（68 节 unserialize 同源）。
- 大数组纪律：千万级数据的中间表本身就是反模式——生成器管道替代（30 节）。

## 25. foreach 与大数组操作的 COW 陷阱

- foreach 按值循环里写原数组：首次写触发整表 separation——"边遍历边改"的代价是 O(n) 双份内存+拷贝；按引用循环零拷贝，但循环后 $v 悬挂引用会误改末元素，用完 unset($v)。

```php
foreach ($rows as $k => $v) { $rows[$k] = trim($v); }   // COW 整表拷贝
foreach ($rows as &$v) { $v = trim($v); } unset($v);     // 原地改，零拷贝
$out = array_map('trim', $rows);                         // 新表：隐藏 O(n) 分配
```

- 数组函数分工：sort/ksort/shuffle 按引用原地改；array_map/filter/reduce 返回新表——每步都有隐藏分配，大表管道逐步核算。
- foreach 复制语义的另一面：循环体里 unset($rows[$k]) 会反映到原数组，但行为依赖 packed/hash 形态与循环体写点——这种"边界代码"在引擎版本间最脆弱，评审直接改写成显式循环。
- 排序族自 8.0 起稳定（相等元素保序）；但 usort 比较器返回非 int 8.0 起抛 ValueError——靠"碰巧返回 true/false"的老比较器全部爆。
- 千万行级加工用生成器管道：每步 yield 而非构建中间表（30 节），内存平掉一个量级。
## 26. 字符串驻留与二进制安全

- zend_string 携带长度：二进制安全（可含 \0），explode/substr/hash 全按长度语义——但 C 层老扩展可能按 NUL 截断，引入第三方扩展先拿二进制串过一遍冒烟。
- interned strings：标识符/字面量编译期驻留（去重+永不释放），进 OPcache SHM 后跨 worker 共享——字面量密集的框架启动显著受益，缓冲调参见 38 节。

```php
strcasecmp($a, $b) === 0;   // 大小写不敏感比较：避免 strtolower 双拷贝
hash('sha256', $rawBin);     // 二进制安全哈希
// 拼接复杂度：$s .= $x 均摊 O(1)（容量翻倍），但峰值内存≈2×终值
```

- 字符串键哈希缓存内嵌在 zend_string（hash 字段）——同名键重复查表零重算；把 array 当"对象注册表"用字符串键在热路径是合理选择。
- 大字符串纪律：str_repeat/一次性拼接大 JSON 的峰值=2 倍体积；流式输出用 generator + flush（75 节）。
- 编码纪律：mb_* 处理 UTF-8，binary 安全不等于编码安全——strlen 是字节数，mb_strlen 才是字符数。

## 27. 循环引用 GC：阈值与 FPM/Octane 策略

- 释放模型：refcount 归零立即释放；只有环（parent↔child、闭包捕获 $this）进 GC 根缓冲，默认攒约 1 万个可能根触发一次全图回收——触发点不可预测，长驻进程的尾延迟来源之一。
- FPM 短生命周期反向优化：请求结束整进程级释放，环也一起死——`zend.enable_gc=0` 关掉循环 GC 省扫描开销是老牌调优项（先压测，收益通常个位数百分比）。

```php
// 长驻 worker：必须开着 GC 且周期性主动收
if ($jobs % 500 === 0) {
    gc_collect_cycles();
    $s = gc_status();
    // 8.3+ gc_status 含 runs/collected/threshold/run_time——入指标
    $this->telemetry->gauge('gc.runs', $s['runs']);
}
```

- Octane/Swoole/queue worker 恰恰相反：容器单例与请求对象互相引用形成常驻环，关 GC = RSS 单调爬升（50 节）。
- 主动收的节奏：按任务数或内存增量触发，别按时间——空转 worker 不需要 GC。
- 判别泄漏 vs 环：gc_collect_cycles() 后 RSS 若不回落 → 静态/单例持有（真泄漏）；回落 → 环延迟回收（调 GC 策略）。

## 28. WeakReference/WeakMap 生命周期契约

- WeakReference 只给"对象还在吗"：get() 拿不到属性、不能阻止释放、不能复活——把它当"弱缓存值"用是设计错误。
- WeakMap（8.0）是对象键附注存储：条目随对象死亡自动消失——元数据/每请求上下文/记忆化的标准答案，替代 spl_object_id 手工表（id 复用会张冠李戴）。

```php
$metrics = new WeakMap();
$metrics[$request] = ['start' => microtime(true)];
// $request 释放时条目自动消失——无需 unset，无需 finalize
if (isset($metrics[$request])) { /* 生命周期内 */ }
```

- 键按对象身份（===）：clone 产生新键；枚举 case 可作键且全进程稳定（10 节）。
- 误用警报：把"请求对象"塞全局 WeakMap 期待自动清理——只有对象死后清；持有者不释放就永远不清。确定性回收用显式 unset。
- 长驻进程搭配：静态缓存改 WeakMap 附着能消掉一大类"忘清静态"泄漏（50、97 节），但会改变"缓存常命中"的假设——命中率监控补上。

## 29. 引用 & 的隐藏税与 COW 破坏

- &$x 把 zval 升级为 zend_reference（双间接）：读写多一层解引用，且拆掉 COW 共享——热路径"传引用省拷贝"经常反而更慢，先测再改。
- 传染性：数组任一元素被引用化（foreach &$v 写回）→ 整表升级引用包 → 后续任何复制都是深拷贝——"一处 & 全家拷"是真实事故模式。

```php
function tweak(array &$data): void { $data['n']++; }
// 调用后调用方数组已引用化：后续赋值副本全部深拷贝
// 现代替代：返回新数组（函数式）或按引用但注明生命周期契约
preg_match('/(\d+)/', $s, $m);   // &$matches 出参：表达式实参直接 Fatal
```

- 表达式禁入引用参：`tweak($a['b'] ?? [])` → Cannot pass expression by reference——接口设计时给引用参写明"必须传变量"。
- 出参现代化：多返回值用 list/数组解构；需要"就地写"的场景（资源句柄、生成器）才保留引用并文档化。
- 排查法：xdebug_scan_data? 不存在——用 debug_zval_refcount 观察 is_ref，或静态规则禁 & 于新代码。

## 30. 生成器深用：send、getReturn 与委托

- Generator 双通道：yield 产出值给消费者，send($v) 把值注入回 yield 表达式处；getReturn() 拿最终 return（生成器未完成时抛错）——协程库（Amphp/Fiber 时代之前）全靠这套原语。
- yield from 委托：子生成器透传 send/return，异常沿链传播——管道组装与树遍历的官方姿势。

```php
function pipeline(iterable $rows): \Generator
{
    $n = 0;
    foreach ($rows as $r) { $n += yield strtoupper($r); }
    return $n;                       // $gen->getReturn()
}
$g = pipeline($rows);
$g->current();                        // 启动到第一个 yield
$total = $g->send(1);                 // 注入并前进
```

- 资源陷阱：try/finally 包 yield 时，finally 延迟到生成器销毁才执行（79 节）——fclose/锁别指望作用域离开即触发，显式 `$gen->return()` 收尾。
- 千万行流式：PDO fetch + yield 逐行加工，内存平直；配合未缓冲查询才真省（95 节）；中途 break 必须收尾。
- 生成器不能克隆序列化；想"重放"就把产出物落数组/文件——生成器是一次性状态机。

## 31. 内存取证与 FPM 内存预算

- 三仪表：memory_get_usage(false)（脚本视角）/ (true)（真实系统分配，含引擎开销）/ memory_get_peak_usage(true)；长驻进程另盯 RSS（/proc/self/status VmRSS）——usage 平稳而 RSS 爬升=碎片或扩展泄漏。
- memory_limit 是保险丝不是配额：设太低随机 500，设太高掩盖泄漏；正确预算=worker 单峰值 × max_children ≤ 可用内存（39 节公式）。

```php
// 长驻 worker 自检：超阈值退出，supervisor 拉起（止血而非根治）
if ($jobs % 100 === 0) {
    $mb = memory_get_usage(true) / 1048576;
    $this->telemetry->gauge('worker.mem_mb', $mb);
    if ($mb > (float) ($_ENV['WORKER_MEM_LIMIT_MB'] ?? 256)) {
        exit(250);   // 约定的"内存退出"码，告警维度单列
    }
}
```

- 泄漏排查节奏：分层快照（每 1k 任务记录曲线）→ 对象图谱（gc_status + WeakMap 标记池核查持有者）→ 二分注释模块定位。
- pm.max_requests 是廉价止血：worker 定期重生回收堆碎片；真正的持有者仍要修，否则只是"重启周期"游戏。
- 区间画像：Xdebug + memprof（扩展）做分配热点，生产侧用 excimer + 周期 dump（43 节）。

## 32. clone 语义与深浅拷贝纪律

- clone 是语言级浅拷贝：逐属性复制 zval，对象属性仍共享引用；__clone() 是唯一钩子且不可带参——深拷贝语义必须自己写。
- 8.3+ readonly 属性在 __clone 内可一次性重初始化（9 节隧道）；克隆枚举 case 直接 Error（单例保护，10 节）。

```php
public function __clone()
{
    // 集合元素逐一深拷贝；漏一步=两个"副本"共享同一子对象
    $this->items = array_map(fn($i) => clone $i, $this->items);
    $this->id = null;          // 副本重开身份，防"克隆件"写回主记录
}
```

- DateTime 可变共享是经典暗雷：clone 时漏拷贝时间对象 → 两个实体联动改时间；值对象统一 DateTimeImmutable（88 节）。
- 原型-克隆性能路径：重初始化的昂贵对象用"原型实例 + clone"替代逐字段构造——构造函数做重活时收益显著。
- 反模式：用 clone 实现保存草稿——浅拷贝共享关系集合，草稿编辑污染主单；显式 toDraft() 映射才可审计。

## 33. OPcache 共享内存结构与容量规划

- 三段 SHM 各自打满都会重启（oom_restarts/hash_restarts）：字节码段 memory_consumption（默认 128M）、驻留串 interned_strings_buffer（默认 8M）、脚本键 max_accelerated_keys（默认 10000）——大框架三者全超是常态。
- 容量按实测不按拍脑袋：opcache_get_status()['memory_usage']（used/free/wasted）+ ['interned_strings']（used/free）；wasted 来自失效重编译，长期 >5-10% = 容量不足或部署过频。

```php
$s = opcache_get_status(false);
$m = $s['memory_usage']; $o = $s['opcache_statistics'];
printf("mem %.0f/%dMB waste %.1f%% | str %.0fKB | scripts %d | oom %d\n",
    $m['used_memory']/1048576, 0, $m['current_wasted_percentage']*1,
    $s['interned_strings']['used_memory']/1024, $o['num_cached_scripts'],
    $o['oom_restarts']);
```

- 经验带：Symfony/Laravel 全量（含 vendor）常见 150-300MB、20k-60k keys——按曲线 ×1.5 扩，键位 100000 起。
- 监控三计数器（oom/hash/manual restarts）每池每小时应恒为 0，越线即告警（107、111 节）。
- interned strings 满的形态：新串退化为进程私有（失去共享）+ hash restart 循环——大常量/大路由表应用先扩到 32M。

## 34. opcache_validate_timestamps 与部署复位

- 生产三连：validate_timestamps=0（每请求零 stat 开销）+ revalidate_freq 失效 + file_update_protection 只留部署窗口——代价：代码变更不会自动生效，必须显式复位。
- 复位方法排序：蓝绿池切换（新池新 SHM，旧池放干）> FPM reload（SIGUSR2，重启窗口短）> opcache_reset()（全池瞬间清空=线上冷编译风暴，只配管理面应急）。

```ini
opcache.validate_timestamps=0
opcache.restrict_api=/internal/opcache/   ; status/reset 只允许该路径调用
; 应急复位（限定路径）：
;   curl -fsS http://app/internal/opcache/reset?token=...
```

- opcache.restrict_api 必设：裸暴露 status/reset 等于给业务代码开后门（信息泄露+自我清缓存 DoS）。
- 混跑窗口：蓝绿切流期间两池并存 → 同一套 DB 双版本代码并行——schema 必须 expand-contract 先行（103 节）。
- 常见误诊："改了没生效"90% 是 validate_timestamps=0 没复位；诊断顺序：status 面板 num_cached_scripts → 手动复位 → 再查代码路径。

## 35. 预加载：preload 设计与启动失败风险

- preload 在 FPM 启动期由 master 执行：脚本 require/opcache_compile_file 的类/函数进 SHM 永久驻留，worker 免声明加载——大框架请求路径收益 10-30%。
- 硬风险：preload 脚本 fatal → FPM 起不来 → 部署回滚死循环；CI 必须预跑 preload 脚本 + 启动探针（105 节）双保险。

```php
// config/preload.php（Symfony 由框架生成 var/cache/prod/*.preload.php）
if (is_file($f = __DIR__.'/var/cache/prod/App_KernelProdContainer.preload.php')) {
    require $f;                       // 只做类/函数装载，禁止副作用
}
// php.ini / pool：
// opcache.preload=/srv/app/config/preload.php
// opcache.preload_user=www-data
```

- preload_user 静默陷阱：master 以 root 启动而不设它 → preload 直接跳过，不报错——"为什么没加速"的头号答案。
- 与 classmap-authoritative 天作之合：装载顺序确定、无运行期探测（56 节）；清单随依赖变化，升级后重建。
- 预加载无法预载条件符号（if 分支里的函数）与 ini 依赖行为——preload 脚本保持"纯装载"，业务逻辑一律不进。

## 36. JIT tracing 深水：何时真的有用

- 两键开启：opcache.jit=tracing + opcache.jit_buffer_size>0（buffer=0 等价全关）；只设 buffer 不设 jit 时默认 tracing——"我以为关了 JIT"与"我以为开了 JIT"双向误会是常态。
- 收益真实分布：纯 CPU 负载（数学循环、图像、加密、批处理）2-10×；典型 web I/O 负载 ±0-5%，p99 反而可能抖（编译发生在请求路径）。

```ini
opcache.jit=tracing
opcache.jit_buffer_size=128M     ; 与 opcache SHM 分列预算，防误占
opcache.jit_hot_loop=64          ; 热点阈值：默认值即生产起点，别盲调
```

- 决策顺序：先有 CPU 占比证据（excimer 画像，43 节）再开；开了 A/B 观测 p99 与错误率，别看平均。
- Xdebug 一加载 JIT 即失效——开发环境测不出 JIT 收益，测试必须在无 Xdebug 的镜像做。
- JIT 隐藏的引擎边界 bug 罕见但极难查：复现不了先关 JIT 复测，二分到底再报引擎。

## 37. JIT 缓冲区与 file_cache 崩溃案例

- jit_buffer_size 与 opcache SHM 是两笔预算：tracing 模式 128M 起（含去优化影子栈），重 CPU 应用 256M+；SHM 重启时 JIT buffer 一并重建（冷启动期热点重编译）。
- file_cache（磁盘二级缓存）加速冷启动；但 file_cache auto-refresh 与 JIT 同开曾有崩溃案例（GitHub issue 实锤）——组合上线前钉补丁版本+压测。

```ini
opcache.file_cache=/var/cache/opcache
opcache.file_cache_only=0        ; SHM 为主、磁盘为辅
; jit 开启时 file_cache 慎用：先升到最新补丁并全量回归
; file_cache 目录与 PHP ABI 强绑定：跨版本复用=加载错码
```

- 容器镜像注意：file_cache 打进镜像层后，同镜像跨 PHP 补丁版本复用会 fatal 杂音——构建期清空并重建。
- 降级排查序：关 JIT → 关 file_cache → 仍崩才是引擎/扩展问题；每步之间留观察窗口（崩溃常低频）。
- buffer 不足静默回落解释器（无告警）：用 opcache_get_status()['jit'] 看 buffer_size/正确性计数，别信"配置写了就在跑"。

## 38. interned strings 与 realpath cache

- interned_strings_buffer 只装"驻留候选"（标识符/字面量/常量名）：满了之后新字符串按进程私有处理并触发 hash restart——重启风暴在字符串量大的框架上明显（33 节面板可直接看到）。
- realpath cache 缓存路径解析结果：vendor 深层路径每请求数百次 stat 的应用，realpath_cache_size=4096k、TTL=600 是标配（默认 4M/120s 偏小偏短）。

```ini
realpath_cache_size=4096k
realpath_cache_ttl=600
opcache.interned_strings_buffer=32
; 验证：clearstatcache() 后对比 lstat 次数（strace）——收益看不见但占比 1-5%
```

- 容器只读文件系统：realpath miss 即完整目录 walk；overlayfs 层级深时更要放大缓存。
- 两个缓存都吃"稳定路径"：phar 内文件、动态 realpath 混用会污染缓存——部署产物用绝对路径固定形态（103 节）。
- 监控归口：把 interned strings used/free 与 realpath 命中率（需自行探针）纳入 107 节最小指标集。

## 39. PHP-FPM pm 模式与 max_children 计算

- 三模式：static（启动即定、零伸缩抖动、延迟最稳）> dynamic（spare 弹性）> ondemand（空闲省钱、冷启尖刺）——延迟敏感的生产默认 static；流量稀疏的内部工具才 ondemand。
- 公式：max_children =（可用内存 × 0.8）/ 单 worker 实测 RSS；RSS 取高峰流量下的均值而非启动值。

```bash
# 实测 worker 均值（高峰时段采样）
ps --no-headers -o rss -C php-fpm | awk '{s+=$1;n++} END {printf "avg %.0fMB\n", s/n/1024}'
# max_children = (总内存 - 系统预留 - 同居服务) × 0.8 / avg
```

- 超卖症状链：server reached pm.max_children（排队→503）与 swap 死亡螺旋（worker 换页→吞吐骤降）——宁可调小并发，也别让池进 swap。
- static 配套：pm.max_requests=1000 定期回收（防碎片/泄漏复利）；emergency_restart_threshold 监控连环 SIGKILL。
- 多应用分池隔离：独立 max_children/超时/ini——一池打满或泄漏不拖垮旁池（103 节蓝绿也靠双池）。

## 40. FPM 观测：status、slowlog 与墙钟保护

- pm.status_path=/status 暴露活跃/空闲/队列/慢请求计数；重点盯 "listen queue"——backlog 打满是瞬间 502 的前兆指标，比 CPU 先报警。
- slowlog 双阈值分工：request_slowlog_timeout=3s 记录 PHP 栈（谁慢）；request_terminate_timeout=30s 强杀兜底（墙钟，41 节）；慢日志出现 "killed" = 墙钟击杀。

```ini
pm.status_path=/status
slowlog=/var/log/php/$pool.slow.log
request_slowlog_timeout=3s
request_terminate_timeout=30s
; 阈值纪律：slowlog 阈值别低于 p99——否则每次采样都 dump 栈，自我 DDoS
```

- catch_workers_output=yes 收 stderr 进日志（现代版本开销可接受）；decorate_workers_output=no 保输出结构化可解析。
- status 面板进 Prometheus（php-fpm-exporter），按池告警：queue>0、slow requests 速率、max_children reach 事件。
- slowlog 按 URI 聚合出 Top 表驱动优化——单条慢日志没有价值，趋势才有。

## 41. max_execution_time 的 CPU 时间语义

- Linux 上 max_execution_time 默认 30s 只计 CPU 时间：sleep、IO 等待、socket 读全不计时——"卡死的请求永远不会被它杀"，这是必须全员纠偏的 P0 认知。
- 墙钟三层保：FPM request_terminate_timeout（强杀）→ 网关 fastcgi_read_timeout（断前端）→ 应用自设 alarm；数值递增排列，防误杀链。

```php
// CLI/worker 侧自保：异步信号 + alarm 墙钟（pcntl 不可用于 FPM 主路径）
pcntl_async_signals(true);
pcntl_signal(SIGALRM, function () { $this->log->error('wall limit'); exit(70); });
pcntl_alarm((int) ($_ENV['JOB_WALL_LIMIT'] ?? 60));
```

- 真正自旋（死循环/正则灾难）才归 max_execution_time；被杀 worker 由 master 重生，事件计入 slowlog/error log。
- 有意长任务（导出/报表）进队列 worker 或 fastcgi_finish_request 后台化（75、80 节），别占 web worker 等死。
- 三处超时对齐检查进部署清单：request_terminate_timeout > fastcgi_read_timeout > 应用内部超时（54 节同律）。

## 42. APCu 用户态缓存与序列化器选型

- APCu 是同池共享内存 KV：FPM 池内 worker 共享、跨池/跨机不共享——定位"本地 memo"（配置快照、编译产物、热点只读数据），不是分布式缓存，别当 source of truth。
- apcu_entry($key, $factory, $ttl) 自带同 key 单飞（原子生成）——缓存击穿的官方解；失效风暴用版本前缀整体切换。

```php
$cfg = apcu_entry('app.cfg.v' . APP_VERSION, fn() => load_heavy_cfg(), 300);
// 旧代清扫：APCUIterator 按前缀批量删
apcu_delete(new APCUIterator('/^app\.cfg\.v(?!' . preg_quote(APP_VERSION, '/') . ')/'));
```

- 序列化器按负载实测：默认 PHP serialize；igbinary 更小更快（数组深、对象多收益大），msgpack 次选——切换=全量失效一次。
- apc.enable_cli 默认 off：CLI/worker 各进程不共享 SHM——用前 apcu_enabled() 验证，别假设。
- 内存预算：apc.shm_size 计入池总内存（39 节公式）；满后写失败静默——监控 apc.sma 信息与 full counters。

## 43. 基准纪律与生产画像工具链

- 分层工具定位：采样（生产可常开）< 插桩（dev/预发精确归因）< 微基准（隔离对比）。生产采样首选 excimer（100Hz 低开销，Wikimedia 出品）；phpspy 类 ptrace 工具免扩展但只能离线抓。
- Xdebug profiler 的数绝对不可当性能数：JIT 互斥 + 10× 开销——只做调用结构归因；Blackfire 是低扰动插桩标杆（商业），开发预算内首选。

```php
// excimer 常开采样：1% 请求 + CPU/REAL 事件分开配
$prof = new ExcimerProfiler();
$prof->setPeriod(0.01);
$prof->setEventType(EXCIMER_REAL);
$prof->start();
register_shutdown_function(function () use ($prof) {
    $this->flames->ship($prof->stop()->getLog());   // 火焰图聚合
});
```

- 微基准纪律（phpbench）：JIT/OPcache 开关与生产一致；预热 ≥3 轮丢弃；看分布不看点值；WR 顺序随机化。
- 负载画像纪律：先 p50/p95/p99 曲线后均值；先真实流量回放后合成压测——"hello world URL 压测结论"迁移到业务是经典自欺。
- 每次优化前后各出一份火焰图进 PR——不可复现的"我优化了"不予合并。

## 44. 冷启动与部署后预热策略

- 冷启动三源：FPM 池起（preload 补齐，35 节）、OPcache 空（逐脚本编译）、路径/容器池空——蓝绿切换后新池必须预热达标再接流量。
- 预热判据可执行化：回放代表性请求集直到 opcache_get_status 的 num_cached_scripts 增长归零 + 新池 p99 回落基线——两条都满足才切 upstream。

```bash
# 切流前预热循环（伪码）：预热脚本带探针判据
for i in $(seq 1 300); do curl -sf -o /dev/null http://new-pool/warm/$i; done
php warm-check.php new-pool   # num_cached_scripts 稳定 + q99<基线 才 exit 0
```

- file_cache 让多池/容器共享磁盘字节码：冷编译→磁盘读（快一个量级）；镜像构建期跑 CLI 预热产物入库是容器时代标准解（104 节）。
- opcache_reset ≠ 预热：reset 后是"边跑边编译"的裸奔窗口——p99 双峰就是它（34 节）；预热是给"新池"，不是给"热池"。
- 预算意识：预热流量也是流量——打 DB/下游的预热集要脱敏（只读路由、影子库），别把冷启动成本转嫁给生产依赖。

## 45. Fiber 语义：不是线程也没有调度器

- Fiber（8.1）= 有栈可暂停执行流：Fiber::suspend() 让出、$fiber->resume()/throw() 恢复；语言不带调度器——谁 resume 谁说了算，调度永远在你或事件循环库手里。
- 纠偏三连：Fiber 无并行（一条 OS 线程同时只跑一个 fiber）；阻塞系统调用照样卡死整线程；切 fiber 救不了 CPU 密集。

```php
$f = new Fiber(function (): string {
    $data = Fiber::suspend('need-io');   // 挂起向调用方"要"数据
    return "got $data";
});
$req = $f->start();       // 'need-io'
$f->resume(do_io($req));  // 非阻塞 IO 完成后注入
$f->getReturn();          // 拿最终值（fiber 完成态）
```

- 规则：resume/throw 只能在挂起态调；根上下文里 suspend 抛 FiberError；未完成 fiber 在脚本结束时静默丢弃——挂起态持锁/连接=泄漏源。
- 定位：Fiber 是"库的实现细节"（Revolt/Amphp、react/async v3 都靠它让 await 像同步）——业务代码直接裸操作 Fiber 是越层，封装成 async/await 语义再用（47 节）。

## 46. 事件循环纪律：阻塞调用黑名单

- 铁律：循环线程上任一阻塞 = 全体阻塞——单请求卡 50ms，所有并发请求全部 +50ms。黑名单：PDO/同步 DB 客户端、sleep/usleep、file_get_contents(http)、curl_exec 同步模式、shell_exec、gethostbyname、bcrypt/图像等重 CPU、灾难回溯正则。
- 看门狗：loop lag 探针（计划 10ms 实跑 60ms 即有阻塞）；配 excimer REAL 采样抓"当时在跑什么"——两件套是事件循环的标配观测。

```php
// Revolt/Amp 通用 lag 探针（ReactPHP 可用 timer 同构实现）
$last = microtime(true);
Revolt\EventLoop::repeat(0.01, function () use (&$last) {
    $now = microtime(true);
    $lagMs = ($now - $last - 0.01) * 1000;
    $last = $now;
    if ($lagMs > 50) $this->telemetry->histogram('loop.lag', $lagMs);
});
```

- DNS 是隐形阻塞：gethostbyname 无超时控制，必走库内 async resolver 或 curl 异步解析——"偶尔卡 5s"查 DNS 先。
- 会话文件锁会把"异步服务"拖回串行：同用户并发被 session 锁排队（72 节）——异步栈里 read-and-close 立即释放。
- 落地：CI 静态规则禁循环路径调用黑名单函数（52 节），比事后 lag 告警早一个身位。

## 47. ReactPHP 与 Amp v3/Revolt 选型

- 两大用户态阵营：ReactPHP（promise/回调风格，组件最老牌，react/http、react/mysql 等）vs Amp v3（Fiber 原生，await 让异步长得像同步）；Revolt 是 Amp 抽出的通用事件循环内核，react/async v3 也提供 await() 桥接 Fiber。
- 选型口径：存量 React 生态 + 团队熟 promise → React；新项目要"同步脸异步心"与更现代并发原语 → Amp v3；混跑是红线——两个 loop 同进程互相抢 fd。

```php
// Amp v3：并发抢三个接口，写法保持顺序观感
$responses = Amp\async(function () use ($urls) {
    $futures = [];
    foreach ($urls as $u) { $futures[] = Amp\async(fn() => $this->fetch($u)); }
    return Amp\awaitAll($futures);
});
// await 禁在事件循环回调里嵌套调用（死锁）——只在 async 上下文用
```

- DB 客户端共同短板：amphp/postgres、amphp/mysql 是纯 PHP 协议实现，特性对齐弱于原生扩展——复杂 SQL/存储过程/大结果集先 PoC 再上（53 节）。
- 生态判断信号：需要哪侧组件库（HTTP server？MQ？）比语言风格更常决定选型；两侧都缺的组件（如某云 SDK）只能进程隔离桥接（51 节）。

## 48. Swoole/Swow 协程运行时与 Hook 机制

- Swoole 是 C 扩展级协程运行时：Runtime::enableCoroutine($flags) 把阻塞调用改写为协程挂起——TCP/文件/sleep/PDO/CURL/DNS 各有 hook 位；写同步代码得到并发，代价是"看起来同步≠随处并发"的错觉。
- Hook 盲区是事故温床：未 hook 的 C 扩展调用（部分 redis/压缩/系统库）照样阻塞整个调度器——上线前用阻塞探测器（52 节）过关键路径，别信"反正套了协程"。

```php
use Swoole\Runtime, Swoole\Coroutine;
Runtime::enableCoroutine(
    SWOOLE_HOOK_TCP | SWOOLE_HOOK_FILE | SWOOLE_HOOK_SLEEP
    | SWOOLE_HOOK_PDO | SWOOLE_HOOK_CURL
);
Coroutine\run(function () {
    $results = Coroutine\parallel? // 用协程容器并发执行 IO 段
});
```

- 上下文纪律：请求级状态放 Coroutine\Context（按 cid 隔离），全局 static 在协程栈里就是共享可变态（50 节同病）。
- 连接池必须协程感知：取不到要挂起等待而非狂建连接（53 节）；Swoole\Table/Atomic 做跨协程共享，锁语义换成协程锁。
- Swow 是后发协程扩展（API 现代化、生态薄）；OpenSwoole 分叉维护反复——选型跟长尾依赖与团队储备，别只看 benchmark。

## 49. FrankenPHP worker 模式与常驻心智

- FrankenPHP 把 PHP 编进 Go/Caddy 单二进制：无 FPM、每请求一个 goroutine（真并行、ZTS 线程安全约束扩展），worker 模式让应用常驻——启动开销归零，状态泄漏等级与 Octane 相当（50、97 节）。
- worker 心智清单：静态、容器单例、DB 连接、文件句柄每轮请求后必须归位；worker.php 循环"收请求→处理→清理"由应用自己写（框架 runtime 有封装，但要逐项审清理表）。

```php
// FrankenPHP worker 骨架（Laravel/Symfony runtime 已封装，原理如此）
while (frankenphp_handle_request()) {
    try {
        $kernel->handle($req)->send();
    } finally {
        $this->resetRequestState();   // auth/容器 scoped/静态重置
    }
}
```

- ZTS 代价：非线程安全扩展直接不可用——扩展清单上线前逐个验证，老扩展（opcache 之外的第三方）踩雷率高。
- 观测面切换：FPM 的 status/slowlog 没了，换 Caddy metrics + 应用自埋点（107 节）——迁移成本常被低估。
- 适用画像：容器密度优先（单镜像全栈）、Early Hints 103、HTTP/3 边缘；传统 FPM 运维栈成熟度仍是 FrankenPHP 的短板。

## 50. 长驻进程内存卫生与请求隔离

- 长驻三大泄漏源：静态属性持大对象图、容器单例持可变请求态、事件监听器/闭包捕获请求对象——FPM"请求结束全清空"的隐性安全网在 Octane/Swoole/worker 下全部消失。
- 隔离四板斧：请求态走 scoped 绑定/协程 context；静态改 WeakMap 附着（28 节）；worker 定期回收（max_requests + 内存阈值自杀）；串味测试常态化——同进程连续两个"用户"请求断言无串味。

```php
// 串味测试：上 Octane/Swoole 后必有的回归用例
public function test_no_identity_bleed(): void
{
    $r1 = $this->actingAs($this->userA)->get('/me')->json('id');
    $r2 = $this->actingAs($this->userB)->get('/me')->json('id');
    $this->assertSame($this->userB->id, $r2);   // r1 残留即泄漏
    $this->assertNotSame($r1, $r2);
}
```

- 事务泄漏形态：异常路径未回滚 → 连接带 open transaction 回池 → 下个请求莫名锁表——finally 化回滚 + 回池前校验事务状态是硬要求。
- 内存曲线治理：每 N 请求记录 usage/RSS 双曲线（31 节），斜率>0 即建事件；先重启止血，再按"静态→单例→环"次序定位（27 节）。

## 51. CPU 密集卸载：amphp/parallel 与进程池

- 协程只救 I/O 不救 CPU：事件循环上 200ms 图像处理 = 全体并发停摆 200ms。CPU 密集必须出进程：amphp/parallel（进程池+Channel 通信）、Swoole Task/Process、或干脆外置队列 worker。
- 粒度原则：单任务 CPU ≥50ms 才值得 IPC 分片（序列化/进程启动是固定税）；否则批量合并后在当前进程做。

```php
// amphp/parallel 简式：分片并行，Future 汇合
$futures = [];
foreach (array_chunk($rows, 500) as $i => $chunk) {
    $futures[] = Amp\Parallel\run('transform_chunk', [$chunk]); // 进程执行
}
$results = array_merge(...Amp\awaitAll($futures));
// 序列化边界即性能边界：传引用是幻觉，大对象要 chunk 化或走文件/Redis
```

- 子进程数 = CPU 核 × (1~2)，与 FPM/worker 共享内存预算（39 节公式重算）——卸载池吃紧时先算总账。
- 子进程崩溃语义：ProcessPool 自动重生但任务丢失——任务必须可重放/幂等（100 节），把"分片失败"当成常规分支处理。
- 反模式：在 FPM worker 里 pcntl_fork 自建池——僵尸回收、信号处理、OPcache SHM 继承全是坑；卸载池独立部署。

## 52. 阻塞调用探测工具链

- 三层探测：静态层（PHPStan 自定义规则对循环路径禁调黑名单）、运行层（loop lag 看门狗 46 节 + excimer REAL 抓现行）、压力层（并发探针：两个 100ms 异步 IO 应 ~100ms 完成，200ms 即有阻塞）。

```php
// 并发探针测试：直接进 CI 套件
public function test_no_hidden_blocking(): void
{
    $t = microtime(true);
    \Amp\async(function () {
        \Amp\awaitAll([$this->client->get('/slow?a'), $this->client->get('/slow?b')]);
    });
    $this->assertLessThan(0.15, microtime(true) - $t);  // 串行=有阻塞
}
```

- 常见漏网：日志同步写盘（磁盘慢时爆 lag）、session 文件锁（同用户并发串行化）、偶发 DNS、慢 ext 调用——都不是"显式 sleep"却都是阻塞。
- Swoole 侧：Coroutine 内用阻塞检测（协程调度间隔监控/Swoole Tracker）；hook 覆盖审计要按位核对（48 节）。
- 数据闭环：loop lag p99 指标化（107 节），越线自动触发 excimer 采样并上传火焰图——从"偶发卡顿工单"进化为"有证据的修复项"。

## 53. 协程数据库：连接池与慢查询放大

- 协程下事故形状改变：慢查询从"这个请求慢"变成"占住池连接 → 全协程排队 → 级联雪崩"；池上限=数据库可承受并发（MySQL 经验 2-4×核数），不是"越大越好"。
- 池实现必须协程感知：取不到连接要挂起等待（带超时），否则要么饥饿报错要么连接风暴；归还前校验事务已关闭、连接健康。

```php
// 池爆的三个信号：等待数、等待 p99、使用率
[$waiting, $p99wait, $used, $max] = $pool->stats();
if ($waiting > 0 && $p99wait > 0.05) {
    $this->circuit->trip('db-pool');   // 熔断入队，防雪崩扩散
}
// SQL 必须带语句级超时（MySQL max_execution_time ms / PG statement_timeout）
```

- 超时纪律：数据库侧 statement timeout 是最后防线——应用侧"拿不到连接"永远比"数据库慢死"便宜。
- 事务边界：事务内做远程调用=持锁等待，池被"事务型连接"吃满——非 DB 工作一律挪出事务（100 节幂等同源）。
- 慢查询与协程调度叠加时先看池再看 SQL：把池 stats 打上监控再谈"优化这条查询"。

## 54. 优雅停机：信号、在途回收与超时

- 目标：停机窗口不丢请求不截响应。信号语义：SIGTERM/SIGQUIT=停接新→drain 在途→超时强杀；pcntl_async_signals(true) 替代 declare(ticks=1)（免全局 ticks 税）。
- 三层超时必须递增对齐：网关 drain 超时 ≥ FPM process_control_timeout ≥ 应用在途上限——不齐就会出现"网关已切、应用还在写"的 502 尾巴。

```php
pcntl_async_signals(true);
pcntl_signal(SIGTERM, function () use ($server) {
    $server->stopAccepting();            // 不再收新连接
    $server->drain(timeout: 25.0);       // 在途回收，墙钟上限
    $server->closeConnections();         // 残余断开
    exit(0);
});
```

- 队列 worker 停机=幂等审计点：kill 落在任务中段会重投（100 节 retry_after 陷阱）——停机钩子里"完成当前任务再退出"。
- k8s 顺序：preStop sleep（等 readiness 摘流）→ SIGTERM → drain → terminationGracePeriod 兜底；探针与信号不配合就是最大停机事故源（105 节）。
- FPM 自身：process_control_timeout 控制 worker 处理 SIGTERM 的宽限；过长拖慢滚动更新，过短截断在途——以 p99 时长定值。

## 55. composer.lock 纪律与 validate --strict

- lock 是部署契约：锁入仓库；CI 三步走 validate --strict（schema+lock 与 json 同步校验）→ install（不许动 lock）→ git diff --exit-code composer.lock（动了即失败）。
- 锁漂移高发点：本地顺手 `composer update`、merge 冲突手解 lock——冲突处理纪律是"回滚 lock 后重跑 `composer update --lock` 回归确定态"，不是手改 JSON。

```bash
composer validate --strict         # "lock is not up to date" → 非零退出
composer install --no-interaction --prefer-dist --no-progress
git diff --exit-code -- composer.json composer.lock || { echo "lock drift"; exit 1; }
```

- require-checker 补盲：lock 只锁依赖，不挡"用了没声明的扩展/函数"（运行期缺件）——composer-require-checker 进 CI。
- 平台锁定：config.platform.php 设最低支持版（如 8.2.0），update 按"地板"解析而开发机跑新版——消除"我机器上解析结果不同"。
- 升级事件（框架/大版本）单独立 PR：lock 大改 + 全量回归；日常 PR 严禁捎带 update（评审可辨度归零）。

## 56. classmap-authoritative 的收益与边界

- 生产装载三旗：--no-dev --optimize-autoloader --classmap-authoritative：PSR-4 目录探测全砍，类命中 map 直达；miss 直接 fail-fast（不再回退文件系统）——错误前置到冒烟而不是线上第一个请求。
- 禁区：运行期生成类（Doctrine 代理、编译模板、测试替身）不在 map = fatal。两条出路：部署期预生成（doctrine:generate-proxies 等）再 dump，或放弃 authoritative 退回 optimize。

```bash
composer install --no-dev --optimize-autoloader --classmap-authoritative
# 生成期类先落地再 dump 才进 map：
php bin/console doctrine:generate-proxies
composer dump-autoload --optimize --classmap-authoritative
```

- exclude-from-classmap 排除 tests/巨型 fixture：控制 map 体积与 preload 预算（35 节同源）。
- --apcu-autoloader 叠加：跨 worker 共享"接口/trait 解析"结果——authoritative 管类定位，APCu 管剩余符号，互补不互替。
- 冒烟验收：发布产物跑全路由 smoke——classmap miss 在部署流水线当场暴露，这是 authoritative 模式的配套纪律。
## 57. path 仓库与 monorepo 拆包策略

- path 仓库在开发机是 symlink：改子包代码即时生效，零 update——这是拆包迭代速度的全部来源；但 symlink 决不能进生产，发布必须走真实仓库的 dist，"path 依赖上了生产"就是拆包失败的定义。
- CI 是双面验证：path 装配跑测试 + 发布产物用 `--prefer-dist` 从真实仓库再装一遍——"path 里绿、dist 里红"（autoload 配置漂移、replace 缺失）只有第二层能拦住。

```json
{
  "repositories": [{"type": "path", "url": "packages/*", "options": {"symlink": true}}],
  "replace": {"acme/pdf": "self.version", "acme/pdf-bridge": "self.version"},
  "require": {"acme/pdf": "@dev"}
}
```

- versions 显式钉死：path 包在 CI shallow clone 下无法从 git 推断版本（无 tag 可读）→ 解析直接失败；每个子包 composer.json 写死 `version` 字段，这是 split 流水线最常见的翻车点。
- replace 防双装：根包 replace 全部子包，monorepo 内 require 组件时不会真的装第二份；忘写 replace = 同名包两份实体，autoload 覆盖顺序看运气。
- 发布顺序按依赖拓扑：被依赖方先行、统一 tag 同步推；中途失败整批回滚版本号，"半发布"状态过夜 = 下游 lock 解析地雷。
- split 工具选择次要、纪律主要：无论用现成 split 库还是自研脚本，唯一硬要求是"tag 推送与 dist 发布原子化"——发布脚本中断后的半状态比没有 split 更危险。

## 58. composer bin 与工具依赖隔离

- 隔离动机是硬冲突：phpstan/infection/psalm 各自带一坨 symfony/*、nikic/* 约束，塞进主 require-dev 早晚把应用依赖顶翻——工具是工具，应用是应用，vendor 分家。
- bamarni/composer-bin-plugin 给每个工具独立命名空间（vendor-bin/<tool>/ 独立 composer.json + lock），主 lock 更新与工具升级彻底解耦。

```bash
composer require --dev bamarni/composer-bin-plugin
composer bin phpstan require phpstan/phpstan "^2.1"
composer bin infection require infection/infection
# 运行: vendor-bin/phpstan/vendor/bin/phpstan analyse
```

- CI 缓存分 key：vendor-bin/phpstan 与主 vendor 各自命中各自的缓存——主依赖升级不该击穿静态分析缓存，反之亦然；一个 key 全家桶等于缓存永远 miss。
- PHIVE 路线作为补充：官方发行 phar 的工具（带签名验证与自升级）走 PHIVE 管发，绕开 Composer 依赖面；两条路线都行，最糟的是"composer 装一份 + 全局再装一份"的双源。
- 主 composer.json 只留运行期与测试依赖；重构/格式化/静态分析类全部进 bin 命名空间——评审时主 lock 的 diff 干净度直接决定可评审性（55 节同源）。
- 工具的 lock 也提交：vendor-bin/<tool>/composer.lock 进仓库——"phpstan 版本随缓存漂移"导致的规则忽松忽紧，是最难排查的静默不一致。

## 59. composer audit 与供应链事件响应

- audit 不该挂在每 PR 上（结果与你的改动无关，纯噪音），而是每日独立 CI job + 弃维护包单独报表——供应链事件是"日历问题"不是"PR 问题"。
- 告警触发后的可达性评估三问：advisory 指到的函数在我调用图里吗？有公开 exploit 吗？修它的连带升级多大？答案决定"当晚热修"还是"排进本周"，而不是一律最高优先级——告警疲劳比漏告警更危险。

```bash
composer audit --locked --format=json    # 锁定态扫描, 不触发任何 update
composer audit --abandoned=report        # 弃维护包单列, 不与 CVE 混流
```

- 应急补丁路径：fork 官方仓库打 patch → 本项目 repositories 指向 fork + 分支约束 → 上游修复后立刻撤 fork——fork 是过桥不是房产，桥上待超过一个月的要在周会上解释。
- abandoned 预案：每季度过一遍报表，对每个弃包给三选一结论（接手维护/换包/社区 fork），禁止"弃包裸奔过季"——弃包的下一次 CVE 往往没有补丁可打。
- 事件的对外口径也进预案：客户问"你们是否受影响"的回答模板（受影响面/已修复/复检证据）提前写好——事件响应的失败一半在技术，一半在沟通。
- 响应演练：每年一次"故意注入"式演练（私有仓库里放个带 advisory 的包），验证 job→值班→升级链路真的通，而不是只在 runbook 里通。
- 情报面外挂：audit 只看 lock 内依赖；Composer 插件、PHP 扩展（.so）、基础镜像层的 CVE 走镜像扫描与 SBOM 通道——三层数据合并进同一响应流程，避免"扫描工具各说各话"。

## 60. 版本约束与平台锁定的解析陷阱

- `^0.x` 是最反直觉的约束：caret 对 0.x 只放行 patch（^0.9 永远吃不到 0.10）——团队按 1.x 直觉写约束，等不到期望的小版本；0.x 阶段要么显式放宽约束，要么接受钉死并写明。
- dev 稳定性传染：`minimum-stability: dev` 是全局开关，会把整个解析树拖进 dev 分支（连传递依赖都开始解析 dev）；正确姿势是单包加 `@dev` 稳定性旗标，全局保持 stable。

```bash
composer why-not psr/log 3.0             # 打印"谁挡住了这个版本"的完整链
composer update guzzlehttp/guzzle --with-dependencies  # 定点+受控连带
composer outdated --direct               # 盘点, 不是升级
```

- 定点 update 纪律：修复只 update 指名包；大范围 update 单独 PR + 全量回归（55 节）；`--with-dependencies` 是"受控连带"，连带清单必须出现在 PR 描述里。
- why-not 是排障第一命令：版本解不到时手推依赖图必错，让 solver 打印阻塞链——十次里有九次是某个老包钉了上界。
- 平台锁定（config.platform.php）是"地板"不是"描述"：开发机跑 8.4 也按 8.2 解析，防止本地解析结果与 CI/生产不同——但这要求地板真实（升级支持版本时同步抬）。
- 约束评审规约：新依赖必须回答"为什么不是更宽/更窄"，^ vs ~ vs >= 各有语义代价；把约束策略写进 CONTRIBUTING，比每个 PR 重新吵一遍快。

## 61. composer scripts 与部署钩子纪律

- `@php` 前缀用 composer 进程自身的 PHP_BINARY：容器多 PHP 共存时（CLI 8.4 + FPM 池混部）脚手架绝不跑错解释器；裸写 `php` 吃 PATH，CI 与生产分叉的事故都从这开始。
- 钩子只做"装配期"事情（package:discover、config 缓存）；预热类（opcache prime、路由预热）拆独立 deploy-warm，由发布编排控制次序与失败语义——钩子没有"失败重跑"模型。

```json
"scripts": {
  "post-install-cmd": ["@php artisan package:discover --ansi"],
  "post-autoload-dump": ["@php artisan package:discover --ansi"],
  "deploy-warm": [
    "@php artisan config:cache",
    "@php artisan route:cache",
    "@php bin/warm-opcache.php"
  ]
}
```

- 生产 install 一律 `--no-scripts --no-dev`：副作用由流水线显式调用——"装包即产生副作用"在回滚场景是灾难（回滚时你得先跑一遍钩子才知道它干过什么）。
- post-autoload-dump 的触发面比 post-install-cmd 大：任何 dump-autoload 都会触发；把"清缓存"这类重活放这里会在调试半路误伤，只放幂等的注册类动作。
- 脚本可调试性：每个 script 一行、可独立跑、退出码即语义；超过三行的逻辑进真正的 PHP 脚本文件，composer.json 里只留入口。
- scripts 不是 CI 编排：它没有重试/条件跳过/超时语义——编排归流水线，scripts 只做"装配原语"，这条分界能省掉无数"为什么 post-install 没跑"的考古。

## 62. conflict 防御与依赖降级预案

- conflict 是被低估的最强表达：版本组合"能解析但已知有运行期 bug"（不是解析冲突）时，用 conflict 让 composer 直接拒装——比文档里写"别一起装"可靠一个数量级，因为它在机器层执行。
- 典型场景：某支付 SDK 5.x 与你的 DBAL 版本共享一个底层序列化库的坏版本；某测试工具的老版本会静默吞掉特定断言。这类"踩过一次"的组合必须落成 conflict 字段。

```json
"conflict": {
  "phpoffice/phpspreadsheet": "<1.29",
  "legacy/queue-client": "*"
}
```

- 降级预案是升级 PR 的附件：每次依赖大版本升级，PR 里附"回滚 lock 归档"（旧 lock 进制品库）；出事时代码回退与旧 lock 同步走——只回代码不回 lock = 依赖错位，比不回还糟。
- fork 修复三件套：replace 原包名 + 版本号比上游前进 + repositories 指向 fork；忘 replace 会双装同名包、autoload 半随机覆盖——三件套要做成模板，而不是靠记忆。
- conflict 与 replace 组合的防御纵深：conflict 挡"危险组合进门"，replace 让"桥接 fork 顶替真包"；两者都进 CI 的 composer validate --strict 校验面（55 节）。
- conflict 要带可追溯的理由：字段注释写清工单/事故链接——三个月后没人记得为什么禁这个组合，"看似多余"的 conflict 被删掉的当天就是旧 bug 回归的日子。

## 63. 私有仓库与镜像策略

- Satis vs Artifactory 的分界：纯内部包索引 Satis 够用（轻、静态、无状态）；一旦要"镜像公网 + 防上游删包 + 拉取审计"，上 Artifactory/Nexus 类代理——left-pad 式删包事故只对有镜像的组织不存在。
- v2 协议是性能底线：metadata-url 按哈希增量拉元数据；私有仓库只支持 v1 会在大 lock 下拖慢解析一个数量级，选型时先问协议版本。

```bash
composer config repositories.packagist.org false        # 全私有: 切断公网解析
composer config repositories.acme composer https://pkgs.acme.com
COMPOSER_HOME=/opt/composer-template composer install \
  --prefer-dist --no-interaction --offline             # 构建期只信 lock+dist 缓存
```

- allowlist 准入：composer.json 出现未审仓库 = 供应链破口；CI 用固定 COMPOSER_HOME（只读 config 模板），禁 git 类型仓库（直接吃任意分支代码，绕过 dist 审计面）。
- `--offline` 双保险：lock 齐全 + dist 缓存命中 → 构建全程不碰网；上游删包/改名（typosquatting 前奏）在构建期当场暴露，而不是静默换源成功。
- 镜像不等于可信：镜像挡的是"消失"，不挡"投毒"——audit（59 节）与签名（64 节）仍然是独立防线，三者互不替代。
- 私有包的 autoload 与公共包同规：path/dist 混装时 autoload 配置差异（psr-4 大小写、files 入口）在镜像源下显形——CI 对发布产物跑一遍 dump-autoload -o 等于一次免费冒烟。

## 64. Phar 分发与 phar.readonly 陷阱

- phar.readonly 默认 1 是安全机制不是障碍：生产/运行容器绝不放开（写 phar 能力 = 持久化后门的门面），只在构建容器 `-d phar.readonly=0` 显式解禁——两个容器分离，不要图省事在运行镜像里全局放开。
- box 打包是事实标准：main 入口 + stub 定制 + 按文件 GZ 压缩 + OpenSSL 签名一条龙；压缩省的下载体积，换来的是解压 CPU 与"OPcache 更帮不上忙"（见下）。

```bash
php -d phar.readonly=0 vendor/bin/box compile   # 构建端显式解禁
box verify -- build/dep.phar                    # 签名验证进发布门禁
```

- OPcache 不缓存 phar 内部文件：phar 内的每个 require 都走流包装器实时展开，无 opcode 缓存收益——CLI 工具无所谓，Web 高 QPS 场景禁用 phar 分发，这是性能悬崖不是优化空间。
- 启动时间预算：大 phar 首次加载是解压+注册流的全套开销，CLI 工具把它算进"命令延迟"预算——超过 100ms 的工具 phar 该考虑按需子包或放弃 phar。
- 签名链纪律：私钥进 CI 保险库，公钥随分发；安装/启动时验签是供应链最后一闸——"内部分发的工具"也不例外，内部投毒正是现代供应链事件的主流形态。
- phar 与 69 节攻击面的关系：分发 phar 的项目同时要保证自己不被"用户输入路径触发 phar://"反打——打包者与被攻击者常常是同一个仓库。
- 打包内容显式审查：box 配置显式列 files/directories（拒绝"全仓打包"），构建时把"phar 里出现了什么"打成清单存档——供应链审计的第一手材料。
- 版本可追溯：分发的 phar 带 --version 与构建号，sha256 进发布记录——事件响应时才能回答"哪些机器在跑带洞版本"。

## 65. SQLi 现代防线与原生查询残留

- ORM 默认参数化把 WHERE 值污染堵住了，但"不能参数化的位置"是残留主战场：ORDER BY/LIMIT、表名列名、动态 DDL——这些位置的正确答案只有白名单，没有万能转义函数。
- IN 占位符展开是基本功：占位符数量与参数数组严格对齐，implode 出 `,?,?,?` 后逐个绑定——拼引号字符串的"IN 快捷方式"是审计必红项。

```php
$sortMap = ['price' => 'price', 'created' => 'created_at'];
$orderBy = $sortMap[$_GET['sort'] ?? ''] ?? throw new BadRequest('sort');
$dir     = ($_GET['dir'] ?? 'asc') === 'desc' ? 'DESC' : 'ASC';
$in      = implode(',', array_fill(0, count($ids), '?'));
$like    = addcslashes($q, "%_\\");
$sql = "SELECT ... WHERE id IN ($in) AND title LIKE ? ESCAPE '\\\\'
         ORDER BY {$orderBy} {$dir} LIMIT 20";
$stmt = $db->prepare($sql);
$stmt->execute([...$ids, "%{$like}%"]);
```

- LIKE 转义必须配 ESCAPE 子句：只转 `%` `_` 不声明转义符，用户输入里的反斜杠仍能吃掉你的转义——addcslashes + ESCAPE 是一对，拆开用等于没转义。
- LIMIT/分页参数强转 int 再拼（或 bindValue PARAM_INT）；字符串页码拼进 SQL 是低级错误但出现频率与团队规模成正比。
- 二阶注入是 ORM 团队最松懈的面：入库全参数化没问题，但报表工具/迁移脚本把"库里已存的数据"拼进动态 ORDER BY 或 DDL 时攻击才兑现——数据出口到 SQL 的边界要再白名单一次，而不是相信"存进来的都是干净的"。
- 动态标识符的另一半：表名/列名拼接同样必须白名单（常量表来自 migration 定义）；凡"列名来自请求参数"的设计，先问能不能改成固定查询+条件过滤。

## 66. PDO 原生预编译与模拟预编译抉择

- charset 必须走 DSN：`mysql:host=...;charset=utf8mb4` 一处声明；用 SET NAMES 设 charset 时连接层的转义表不同步，GBK 族多字节字符（0x5c 作尾字节）能吃掉转义符——注入经典族。
- 真预编译（EMULATE_PREPARES=false）把所有转义责任交给服务端；代价是每条语句一次 prepare 往返——一次性查询略亏，复用与类型敏感场景全赢。

```php
$pdo = new PDO('mysql:host=db;dbname=app;charset=utf8mb4', $u, $p, [
    PDO::ATTR_EMULATE_PREPARES => false,    // 真预编译: 转义进服务端
    PDO::ATTR_STRINGIFY_FETCHES => false,   // 取回类型保真 (大整型/小数)
    PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
]);
```

- 模拟预编译的类型边界问题：客户端转义把一切字符串化，LIMIT :n 在模拟模式下变 `LIMIT '20'` 直接语法错——要么 PARAM_INT 显式绑定，要么关模拟；金额/大整型同样受益于真预编译的类型保真。
- 决策表：一次性查询（无复用、参数边界简单）允许模拟省往返；参数边界敏感（金额/LIMIT/大 ID）或 charset 复杂（任何非 utf8mb4 历史）一律真预编译；全局默认 false，个别热路径显式开并标注理由。
- 检查现有连接的真实 charset：`SHOW VARIABLES LIKE 'character_set%'` 比任何配置评审都可靠——框架层偷偷 SET NAMES 的历史遗留不在 composer.lock 里。
- 框架默认也要审计：新项目默认配置在 8.x 时代大多做对了，但从 5.x 时代升上来的项目默认值未必跟——升级 PR 把连接配置当 diff 审查项，不当背景噪音。

## 67. 上传处理：类型欺骗与 Web 服务器注入

- 三验合一：finfo 按魔数验内容类型（不信客户端 Content-Type，不信 getimagesize 单独裁决）+ 扩展名白名单 + 存储名与用户名彻底解耦（bin2hex(random_bytes) 起名）——三条各挡一类绕过，缺一就是洞。
- 配额静默丢弃是体验与安全双坑：post_max_size 打穿时 $_POST/$_FILES 全空、脚本照常跑——业务层看到"用户没填表"，用户看到"提交没反应"；用 CONTENT_LENGTH 预判给 413。

```php
$fi = new finfo(FILEINFO_MIME_TYPE);
$mime = $fi->buffer(file_get_contents($tmpPath));
if (!in_array($mime, ['image/png', 'image/webp'], true)) {
    throw new UploadRejected($mime);
}
$stored = bin2hex(random_bytes(16)) . '.png';      // 用户文件名只进 DB, 不进磁盘
```

- Web 服务器二次执行是纵深问题：上传目录禁 PHP 引擎（nginx `location ^~ /uploads` 无 php handler / php_admin_value engine off）+ 独立静态域名（cookie 域隔离，inline 脚本即使打进 SVG 也回不到主域）。
- SVG 即 JS：SVG 内嵌 script 是合法内容，当图片 inline 渲染就是存储型 XSS——SVG 单独走 `Content-Disposition: attachment` + 独立域，或干脆不收。
- PATH_INFO 注入家族：Apache mod_cgi 家史（CVE-2012-1823）与 nginx fastcgi_split_path_info 配置错的变体，本质是"脚本路径被当成参数传进解释器"——cgi.fix_pathinfo=0 与 pathinfo 路由二选一时，先写清谁负责解析。
- 收件类型最小化：业务不需要 SVG 就别收——每多支持一种类型，本节三条防线就多一次评审与一次潜在的绕过研究；攻击面是按类型数线性长的。

## 68. unserialize 白名单与 gadget 链

- phpggc 是"弹药清单"不是理论：monolog/guzzle/symfony/laravel 全家桶都有现成 gadget 链——"我只反序列化自家类"不成立，链条只要触达这些依赖就能 RCE；unserialize 任何用户可控数据 = 把 phpggc 当攻击面清单。
- 双闸纪律：HMAC（完整性+来源）+ allowed_classes（最小面）缺一不可——只验签不设白名单，攻击者仍可构造你自家类的字段组合；只设白名单不验签，字段随便伪造。

```php
$mac = hash_hmac('sha256', $blob, $key);
if (!hash_equals($mac, $sig)) {                 // 时序安全比较 (73 节)
    throw new TamperedPayload();
}
$obj = unserialize($blob, ['allowed_classes' => [\App\CacheEntry::class]]);
// 白名单类自身也要过审: __destruct/__wakeup/__toString 链路不得触达用户数据
```

- 序列化炸弹（分配炸弹）：小 blob 展开成巨大结构（嵌套数组/字符串乘法）纯耗内存——unserialize 前 strlen 上限是硬闸；能换 json 的场景直接换 json（无对象复活语义，天然免疫本节全部问题）。
- 白名单不是终点：被允许的类要单独审计魔术方法调用链（destruct 触发的清理器若依赖字段值，等于把入口藏进析构）。
- 缓存键的数据源问题：缓存 blob 即使"只有自己写"，也要防"缓存存储被投毒"（Redis 被打穿后注入 blob）——HMAC 闸对内部通道同样必要。
- 大规模治理手法：全仓 serialize/unserialize 调用点先 grep 清单化（含 serialize() 出口面），按"用户可达/内部管道"分级收口——一次性收口比逐 PR 漏杀可靠得多。

## 69. PHAR 流包装器攻击面

- 触发面比直觉大得多：`file_exists()`、`getimagesize()`、`filesize()`、`is_dir()`、`file_get_contents()` 对 phar:// 路径都会解析 phar 元数据并 unserialize——不是"我不打开 phar 文件"就完事，是"任何接受路径参数的调用"都算触发点。
- phar.readonly 挡不住读：它只挡"写 phar"，读取与元数据反序列化照常发生——靠 ini 设防的思路对读攻击面完全无效。

```php
// 防线 1: 入口 scheme 白名单 (默认只放行本地路径)
$path = $_GET['file'];
if (preg_match('~^[a-zA-Z0-9+.\-]+://~', $path)) {
    throw new BadRequest('scheme 禁用');
}
// 防线 2: web SAPI 卸载 phar 包装器 (CLI composer 不受影响)
if (PHP_SAPI !== 'cli' && !stream_wrapper_unregister('phar')) {
    throw new Runtime('phar 包装器卸载失败');
}
```

- 卸载时机与代价：stream_wrapper_unregister('phar') 在请求早期执行；项目自身用 phar 分发工具（64 节）不受影响（那是 CLI），但 web 依赖里真有组件读 phar 的会炸——上线前全量冒烟，宁可发现依赖也不留攻击面常驻。
- 与 68 节联防：phar 只是 unserialize 的投递载具，gadget 闸照样要设；卸包装器是釜底抽薪，scheme 白名单是入口闸，allowed_classes 是最后兜底——三层对应链条的三段。
- 事故语境见 112 节：file_exists 预检 → phar 元数据 → gadget → RCE 的全链在真实事故里完整走过一遍。
- 检测配套：waf/日志规则对 phar:// 特征做告警（本可拦下 112 节事故的最便宜的一层）——防御与检测是两件事，评审时分开问。

## 70. __serialize/__unserialize 迁移纪律

- Serializable 弃用线：8.1 起 Serializable 老路进入弃用通道（实现接口而无新方法的组合直接报 deprecation）——拖着不迁的代码在下一个 major 直接 fatal，弃用窗口就是迁移窗口。
- 双轨期是迁移的真正难点：__unserialize 必须兼容两种 blob（Serializable 时代 serialize() 的输出 + 新格式）——先读后写、灰度窗口内新老共存，任何一侧读不懂旧数据都是缓存事故。

```php
public function __serialize(): array {
    return ['v' => 2, 'id' => $this->id, 'at' => $this->at?->format('c')];
}
public function __unserialize(array $d): void {
    if (($d['v'] ?? 0) < 2) {
        $d = $this->migrateV1($d);           // 旧 blob 升格, 无版本键的按形状猜
    }
    $this->id = $d['id'];
    $this->at = new DateTimeImmutable($d['at']);
}
```

- 版本键是唯一可靠的迁移手段：没有版本键的老缓存池只能整池失效重来；有版本键才有"写新读双→写新读旧→写新读新"的三步走次序——上线顺序写进 runbook，别靠口头。
- igbinary 选型判据：高频缓存序列化收益明确（体积与速度都优于 PHP serialize），但它有自己的跨版本兼容矩阵——升级 PHP/igbinary 时"缓存整池失效"必须先演练，否则大促当天全量冷缓存。
- 迁移完成后的退役：旧格式读取代码保留一个版本周期后删除，deprecation 计数归零作为退役信号（82 节通道提供数据）。

## 71. password_hash 与 Argon2id 调参

- bcrypt 72 字节截断是无声的：password_hash 只取前 72 字节、无告警——"长口令=强口令"的假设在 bcrypt 下塌缩；迁 Argon2id 后也不建议自行预哈希（口令 shucking 争议），正确做法是整体迁算法并重新定义长度策略。
- 内存反算 DoS：Argon2id 的 memory_cost 是每个哈希的常驻内存——64MB × 32 并发登录 = 2GB 瞬时；登录端点必须限速/队列化，把哈希并发钉进预算，threads 在 FPM 下吃不到真并行，p=1 即可。

```php
$opts = ['memory_cost' => 1 << 16, 'time_cost' => 4, 'threads' => 1];
// 目标: 单次 60-80ms, 按目标硬件标定后写死进配置 (换硬件要重标)
$hash = password_hash($pw, PASSWORD_ARGON2ID, $opts);
if (password_verify($pw, $row->hash)
    && password_needs_rehash($row->hash, PASSWORD_ARGON2ID, $opts)) {
    $db->update(hash: password_hash($pw, PASSWORD_ARGON2ID, $opts));
}
```

- needs_rehash 是唯一平滑升级通道：明文口令只在登录瞬间出现，全表重哈希物理不可能——参数升级（time_cost 3→4）挂登录路径顺带重算，用户无感；这是"每次登录顺带体检"模式的全部原理。
- 标定纪律：time_cost/memory_cost 按目标硬件调到 60-80ms 一档（安全性与 DoS 面的平衡点），写死进配置而非 env 可调——被随手调低等于静默降级。
- verify 失败与 needs_rehash 判定顺序不能反：先 verify 成功才谈 rehash；对失败尝试跑一次假哈希（dummy verify）抹平时序差，防用户枚举。
- bcrypt 存量迁移的现实次序：登录路径先双算法兼容 verify，再按 needs_rehash 逐用户升级——"停机窗口迁移口令哈希"是伪需求，登录瞬间本来就是唯一升级通道。

## 72. 会话安全：再生、SameSite 与 Cookie 矩阵

- use_strict_mode=1 是底线：拒绝未初始化的会话 ID（防 fixation 的第一闸）；use_only_cookies 同设，URL 携带会话的通路彻底关闭。
- 登录/提权瞬间 session_regenerate_id(true)：旧 ID 作废 + 新 ID 下发——提权边界没有再生的系统，fixation 只是时间问题。

```php
ini_set('session.use_strict_mode', '1');
ini_set('session.use_only_cookies', '1');
session_set_cookie_params([
    'lifetime' => 0, 'path' => '/',
    'secure' => true, 'httponly' => true, 'samesite' => 'Lax',
]);
// 登录成功提权瞬间:
session_regenerate_id(true);
```

- SameSite 矩阵按端点分层：Lax 是基线（顶级导航 GET 放行）；真有跨站回跳的路径（OAuth/支付回调）局部 None+Secure+短时效双 Cookie；全局 Strict 会杀死外链进入的会话保持——按端点配置而不是一刀切。
- 文件锁吞吐陷阱：files handler 对会话文件加排它锁，同一用户的并发请求（前端并行 fetch）被串行化——慢端点拖住整页；读侧尽早 session_write_close 释放锁，或换 Redis handler 配短锁。
- handler 失败静默：自定义 handler 的 read/write 返回 false 时 PHP 不抛不记——"会话莫名丢失"十有八九在这；handler 内部包 try/catch + 显式日志，别让登录态在坏 handler 上裸奔。
- 会话与缓存分存储：session 放 Redis 独立 db/实例且淘汰策略 noeviction——缓存抖动挤掉会话，是"用户全量掉线"事故的最短路径。

## 73. 时序安全比较与随机数纪律

- 时序差是真实可测的：字符串 `==` 在首字节差异处返回，远程按响应时间逐字节逼近 MAC——所有 MAC/token 比较一律 hash_equals，与哈希强度无关（再强的哈希也救不了比较函数的短路）。
- token 只存哈希：落库的是 HMAC（带独立 appKey），拖库后攻击者拿不到可用 token；明文只在创建响应里出现一次，"API token 可再次查看"的需求一律拒绝。

```php
$stored = hash_hmac('sha256', $token, $appKey);      // 入库形态
if (!hash_equals($stored, $row->token_hash)) {
    throw new AuthRejected();
}
// 随机分层: 业务随机可种子复现, 秘密永远 Secure
$lottery = new Random\Randomizer(new Random\Engine\PcgOneseq128XslRr64($seed));
$winner  = $lottery->getInt(1, $total);              // 可重放, 复盘可审
$secret  = new Random\Randomizer();                  // 默认 Secure 引擎
```

- Randomizer 引擎分层纪律：PcgOneseq128XslRr64/Xoshiro256** 可种子、可复现——抽奖/采样/风控做成可重放（审计与测试双赢，88 节同源）；Secure 引擎的输出永远不进可重放路径，复现性目标会污染熵源。
- mt_rand 的遗产：8.3 之前 mt_rand 是事实默认，8.3 起 Randomizer 才统一入口——审计存量代码里用 mt_rand/rand 做安全决策（token/密钥/订单号防猜）的位置，全部替换；这批代码 review 找不全，靠 grep。
- 短路防御清单：hash_equals（MAC）、in_array($x, $allow, true)（枚举白名单）；密码比较交给 password_verify（内部已恒时）。
- 种子与密钥分离：种子里没有秘密，秘密里没有种子——可重放引擎进测试与审计，Secure 引擎只签发；混用的团队最后两边都会输。

## 74. XSS 基线：8.1 默认 flag 与逃逸边界

- 8.1 把 htmlspecialchars/htmlentities 默认 flag 升到 ENT_QUOTES | ENT_SUBSTITUTE | ENT_HTML401——单引号属性逃逸这个十几年的老洞在默认层关闭；但默认升级只覆盖"元素内容+属性值"两个上下文，且只在"未显式传 flag"时生效，存量代码显式传了 ENT_COMPAT 的仍然裸奔。
- 五上下文各有自己的规则：HTML 体 / 属性 / JS / URL / CSS——htmlspecialchars 只管前两个；上下文错配（HTML 转义塞进 onclick）是最常见的逃逸形态。

```php
$name = htmlspecialchars($user, ENT_QUOTES | ENT_SUBSTITUTE, 'UTF-8');
// JS 上下文: json_encode 专属, htmlspecialchars 无效
printf(
    '<script>window.cfg = %s;</script>',
    json_encode($cfg, JSON_HEX_TAG | JSON_HEX_AMP | JSON_HEX_APOS | JSON_HEX_QUOT)
);
// URL 上下文: 参数 rawurlencode 后整串再走 HTML 转义
$href = 'https://x.example/u?' . http_build_query(['n' => $user]);
printf('<a href="%s">', htmlspecialchars($href, ENT_QUOTES));
```

- URL 上下文双规则：查询参数先 rawurlencode，整串进 href 再 HTML 转义；更关键的是 scheme 白名单——`javascript:` 伪协议的防御靠协议白名单，不靠过滤关键字（大小写/实体编码绕过是老朋友）。
- CSP 是纵深不是替代：逃逸纪律管"进对了门"，CSP 管"万一进错门别执行"——nonce-based + 禁 unsafe-inline，上线前 Report-Only 跑两周看真实违规分布，别直接 enforce 打瘫自己。
- 转义位置唯一化：渲染层转义（模板自动转义，102 节）或输出层统一 helper，二选一全局一致；"存进库前转义"是反模式——双编码事故与上下文错配都从它开始。
- 富文本需求单独立项：HTML 白名单净化是独立子系统，有自己的测试与监控——把"我们支持富文本"当成模板转义的一个开关来做，是 XSS 事故的标准开场。

## 75. 头部纪律与输出缓冲陷阱

- headers_sent 自检进框架层：header() 前统一 guard，报出"谁在哪一行已经开始输出"——比事后翻 nginx 日志找 "headers already sent" 快一个量级；静默吞掉这个错误的框架是帮凶。
- X-Accel-Redirect 卸载：PHP 只做鉴权与下发内部重定向头，文件由 nginx 直接发送——大文件下载不再占 FPM worker，鉴权逻辑仍在应用层，两个好处同时拿。

```php
if (headers_sent($file, $line)) {
    throw new LogicException("输出已开始: {$file}:{$line}");
}
header('X-Accel-Redirect: /protected/reports/' . $id . '.pdf');
header('Content-Type: application/pdf');      // nginx internal; 目录防直链
```

- 输出缓冲叠加陷阱：output_buffering=4096 + 代码里 ob_start 嵌套，让"提前输出"延迟爆发——测试环境 cookie 好用、生产关 buffering 就炸；环境对齐 output_buffering 是部署清单项。
- fastcgi_finish_request 的代价模型：响应先发给用户，后处理继续占 worker——对用户 0ms，对池容量是全额计费；重活（邮件/分析）挪队列（100 节），finish 只留写审计日志这种毫秒级动作。
- 转义相关性：header 值里的用户输入（下载文件名）同样要防 CRLF 与引号——filename 用 RFC 5987 编码，别 sprintf 裸拼。
- 下载类响应的完整头清单：Content-Disposition + X-Content-Type-Options: nosniff + Cache-Control——少一个头，浏览器就会替你"智能"猜一次类型。

## 76. Throwable 家族地图与分界

- Error/Exception 的分界是工程判断不是语法：Error = 代码坏了（TypeError/ValueError/ArithmeticError 家族），该死得响亮；Exception = 世界坏了（网络/磁盘/外部服务），可补偿可重试——catch 策略完全不同。
- 除零矩阵：`7 % 0` 与 `intdiv(7, 0)` → DivisionByZeroError；`7 / 0`（int/int）→ 同样 DivisionByZeroError（8.0 起）；但 `7.0 / 0.0` → INF、`0.0 / 0.0` → NAN 不抛任何东西；fdiv() 对全形态免疫——聚合口径下的"零分母空态"用它。

```php
try {
    $result = $svc->execute($order);
} catch (TypeError|ValueError $e) {
    $log->critical('code bug caught at boundary', ['e' => $e]);
    throw $e;                              // 缺陷信号不补偿, 让它死得响亮
} catch (TransientException $e) {
    $result = $svc->fallback($order, $e);  // 世界坏了: 降级路径
}
$avg = fdiv($totalCents, $count);          // 分母为 0 的空态是业务常态
if (!is_finite($avg)) { $avg = null; }
```

- catch (Exception) 接不到 Error 家族：DivisionByZeroError/TypeError 都是 Error 的孩子——"我以为兜住了"的事故全部来自这层误解；边界处必须显式兜 Throwable 转成 500 + 告警，域内只 catch Exception。
- 用 catch (Error) 做"容错"是反模式：TypeError 是缺陷信号，补偿它等于把 bug 藏进降级路径，下次以更贵的形态复发；只有进程边界（HTTP 入口/队列 worker/定时任务）有权兜 Throwable。
- finally 与清理的分工见 79 节：释放型资源放 finally，决策型（commit/rollback）绝不放。

## 77. 内部函数的 ValueError/TypeError 时代

- 8.0 起内部函数对非法参数从"warning + 返回 false"渐进改抛 ValueError/TypeError——"渐进"是关键词：同一个函数不同错误形态有的抛有的仍返回 false，凭直觉迁移必错，逐条查 changelog。
- `=== false` 防御在异常化后变死代码：explode('') / array_chunk($arr, 0) / sprintf 少参数——这些位置根本不再返回 false，异常直接飞过你的 if，测试碰不到那个分支，覆盖率虚高。

```php
try {
    $parts = explode($sep, $line);        // $sep='' → ValueError (8.0+)
    $bins  = array_chunk($rows, 0);       // size=0 → ValueError
    $label = sprintf('%d / %d', $a);      // 参数不足 → ArgumentCountError
} catch (ValueError|ArgumentCountError $e) {
    throw new LineParseError($line, $e);  // 翻译并挂 previous (81 节)
}
```

- 8.1 的 null→非空参数弃用最易被忽视：trim(null) / str_replace(null) 这类现在报 deprecation，下个 major 变 TypeError——新代码参数标 ?string 并显式处理，CI 把这批 deprecation 按错误跑（82 节单列通道）。
- 迁移工程做法：PHPStan 的 impossible check + deprecation 规则集把死分支与危险调用列成清单，一次性清理；靠人 review 记忆是不可能完成的任务。
- 防御方向反转：旧代码防御"返回值不可信"，新代码防御"异常不该被吞"——同一个函数两种防御姿态并存是 8.x 代码库的常态，评审时先问清它处于哪个时代。
- 死分支的清理是测试资产：if ($x === false) 分支删除时把"该路径抛什么异常"补成显式断言——迁移不是删代码，是把防御从死分支搬进活断言。

## 78. @ 抑制符债务与错误处理器交互

- @ 压的是诊断不压异常：警告/通知/弃用被压，ValueError/TypeError 照飞——"加了 @ 就稳了"的假设对 8.x 内部函数时代完全失效（77 节），反而掩盖了该迁移的调用。
- 自定义 error handler 的第一行决定 @ 语义：PHP 8 起，被 @ 抑制的错误发生时 error_reporting() 返回的是真实级别而非 0——PHP 7 时代 `if (error_reporting() == 0) return false;` 的早退惯用法失效，必须换成位与判断 `!(error_reporting() & $no)`。

```php
set_error_handler(
    function (int $no, string $msg, string $file, int $line): bool {
        if (!(error_reporting() & $no)) {
            return false;   // @ 抑制的错误: 交回标准处理, 不在这里翻案转异常
        }
        throw new ErrorException($msg, 0, $no, $file, $line);
    }
);
```

- handler 不判位掩码的经典事故：第三方库内部的 @filemtime() 全部被你的 handler 翻成异常——库在第一次碰到就死，而且报错位置在库里不在你代码里，排查极其昂贵。
- @ 债务治理：现存 @ 建 rector/PHPStan 清单，CI 禁新增；豁免只有"第三方 EOL 路径上的已知误报"，且必须行内注释 Owner 与退役日期——没有退役日期的豁免就是永久豁免。
- 折衷形态：@file_exists() 这类"探测型"调用可保留，但优先换成 error_reporting 局部开关的显式包装函数，让"我这里有意识地在压错误"成为可 grep 的代码。
- 团队红线建议：新代码禁 @、改显式包装——每个 @ 都是一个未来某天 error handler 迁移事故的候选，这笔债按条计息。

## 79. finally 深水：吞异常、替换与生成器

- finally 的替换语义是规范行为不是 bug：在 finally 里 return/throw 会顶掉 try 里正在飞行的异常，被顶掉的异常连 trace 都不剩——事务"提交"写进 finally 是经典自爆结构。
- 正确分工：释放型（close/unlock/dispose/rollBack-on-error）放 finally；决策型（commit）显式写在 try 成功路径——把决策藏进清理位置 = 把控制流藏进语法糖。

```php
// 自爆形态: $work() 抛异常时, finally 里的 commit 顶掉异常且事务未定
function broken(callable $work): mixed {
    $conn->beginTransaction();
    try { return $work(); }
    finally { $conn->commit(); }             // 吞掉进行中的异常
}
// 正确形态: 决策显式写 try; finally 只做无控制流的清理
try { $r = $work(); $conn->commit(); return $r; }
finally { if ($conn->inTransaction()) { $conn->rollBack(); } }
```

- 生成器的 finally 是延迟的：yield 出去之后提前 break / 抛进异常，finally 要等到生成器对象被销毁（引用归零）才执行——用完不显式释放，锁/游标就悬着；遍历端提前退场时保证生成器变量出作用域或显式 unset。
- 结构化结论：finally 里只允许"幂等且无控制流"的语句；出现 return/throw/continue 任何一种，评审直接打回，不讨论。
- 与 110 节的关联：Octane 泄漏事故里"finally 里 commit"与"finally 缺失导致事务悬挂"两种形态都出现过——同一语法糖的两个方向。

## 80. shutdown 处理器与致命错误回收

- E_ERROR 家族是不可捕获区：set_error_handler 收不到、catch 接不到（E_ERROR/E_PARSE/E_CORE_ERROR/E_COMPILE_ERROR）；register_shutdown_function 是唯一回收点——fatal 告警链必须建在这里，而不是框架异常处理器里（那里只有 Exception）。
- error_get_last 判真致命：正常退出时它可能带着旧 warning 残留——必须做 type 位与判断，与真 fatal 集合比对；否则每次优雅停机都触发 fatal 告警群发。

```php
register_shutdown_function(function () use ($log, $metrics): void {
    $e = error_get_last();
    $fatal = $e !== null
        && (bool)($e['type'] & (E_ERROR | E_PARSE | E_CORE_ERROR | E_COMPILE_ERROR));
    if ($fatal) {
        $log->critical('uncatchable fatal', $e);      // 唯一能抓 E_ERROR 的位置
        $metrics->incr('php.fatal');
    }
});
```

- 顺序事实：shutdown 回调按注册顺序（FIFO）执行，但该阶段的对象析构是 LIFO——清理次序有横向依赖（日志先于连接池死）时不要赌语言语义，自己显式排队。
- shutdown 里的崩溃没有 shutdown：回调自身 fatal 无人兜底——回调必须最短路径 + 零外部依赖（别在此时连数据库、别再用框架容器），容器被拆到一半的状态是常态。
- 与 FPM 生命周期的配合：max_requests 触发的正常退出不等于 fatal；区分"计划内重启"与"事故死亡"靠 worker 退出码 + fatal 计数两个信号，别只用一个。
- fatal 与常驻运行时：FPM 每次 fatal 回收 worker（请求隔离兜底），Octane/Swoole 靠 supervisor 检测退出再重生——重生窗口内该 worker 的并发请求一起死，致命计数的告警阈值要按这个窗口算。

## 81. 异常层级设计：域异常与基础设施异常

- 分层的唯一目的：让捕获者不用理解底层就能决策——域层只说业务语言（InsufficientBalance、DuplicateSku），基础设施词汇（PDOException/RedisException/GuzzleException）在仓储边界翻译成 TransientDataStoreError 这类领域可消费的形态。
- previous 链是排障生命线：翻译异常必挂原异常（throw new X(..., $e)），否则丢堆栈与 cause；Monolog/PSR-3 的 exception context 自动展开链——日志序列化时保留完整 chain 是日志架构的验收项（84 节）。

```php
interface Retryable {}                         // 幂等操作可重试标记 (语义见下)
final class TransientDataStoreError extends InfrastructureException implements Retryable {}

try {
    $repo->persist($order);
} catch (UniqueConstraintViolation $e) {
    throw new DuplicateSku($order->sku, $e);   // 翻译: 业务语言 + previous
} catch (DbalConnectionException $e) {
    throw new TransientDataStoreError('persist failed', 0, $e);
}
```

- Retryable 标记的边界：标记的前提是操作幂等可安全重放——网络瞬断可重试；"扣款成功但回包丢失"绝不能盲目重试（幂等键在 100 节解决）；把标记当免检通行证是队列双跑事故的标准前奏。
- 域层零基础设施词汇：catch (PDOException) 出现在 ApplicationService = 分层破产；反向同样成立：基础设施层不得吞域异常换成 500——翻译不等于吞噬。
- 析构禁 throw：析构中的异常在异常飞行期会升级为致命、在 shutdown 阶段会被静默忽略——清理失败只能记录（80 节通道），别把析构当控制流。

## 82. error_reporting 生产配置与全局策略

- 弃用单列是生产策略的核心：8.x 每个 minor 都在产弃用，直接混进主错误流会淹没真错误——主通道 E_ALL & ~E_DEPRECATED 走 stderr 进日志管道；E_DEPRECATED 用 set_error_handler 单独接管转指标（按消息聚合计数），只在升级窗口前提级为告警。
- php_admin_value vs php_value 是多租户边界：池级 admin 值 .htaccess/.user.ini 均不可覆盖；用 php_value 等于把配置权交还给用户——托管型 FPM 这是唯一可靠的租户隔离。

```ini
; FPM 池配置: php_admin_value 不可被应用层覆盖
php_admin_value[error_reporting] = E_ALL
php_admin_value[display_errors] = stderr
php_admin_value[log_errors] = On
php_admin_value[error_log] = /dev/stderr
php_admin_value[memory_limit] = 256M
php_admin_value[expose_php] = Off
```

- 容器纪律：display_errors=stderr + error_log=/dev/stderr，让 FPM 日志与 nginx/collector 在 stdout/stderr 汇流——写文件的 error_log 在容器里是黑洞（写进可写层，随 pod 消失，还可能撑爆磁盘）。
- deprecation 的治理动线：按 (包名.消息前缀) 聚合的弃用计数是升级排期的输入（101 节）——季度看板回答"哪条弃用最贵"，而不是升级周一次性还债。
- 基线声明：error_reporting 的值写进部署清单并纳入冒烟校验——被某个 .user.ini 或运维脚本悄悄降级的池子，等于静默盲飞。
- display_errors 的历史包袱：上游代理偶尔把 stderr 错误字符串泄进响应体——nginx 配 fastcgi_intercept_errors on 兜底转 502，别让 500 页面长出 PHP 错误原文。

## 83. assert 与 zend.assertions 的生产语义

- 三态语义：1=编译并执行（开发）、0=编译但跳过（可运行期切回 1 排障）、-1=不编译（零成本）；关键陷阱是 -1 不可逆——池子以 -1 启动后，本进程生命周期内无法再开断言，事故排障时才发现要重启的窘境要写进 runbook。
- 分工铁律：assert 表达"作者认为不可能为假"的内部不变量；面向用户的校验是业务规则，生产必须执行——校验写在 assert 里，在 -1 下直接蒸发，等于生产无验证。

```php
assert($order->state === State::Paid, new AssertionError('状态机不变量: 退款入口必已支付'));
assert($items->count() > 0);

// 用户输入永远走业务校验, 不进断言:
$qty = is_numeric($_POST['qty'] ?? null)
    ? (int)$_POST['qty'] : throw new InvalidQty();
if ($qty < 1 || $qty > 99) { throw new InvalidQty(); }
```

- 副作用禁令：assert(sendEmail(...)) 在 dev 触发、生产静默 = 双面系统（两边行为不一样，且生产端不可测）；断言体只读，评审看到 assert 里有赋值/调用即打回。
- 生产模式选择：默认 -1（真零成本）；选 0 的理由是"保留切回 1 的排障通道"，但要明白 0 下断言仍占编译产物体积；两种都要在文档里写明切换代价。
- assert 与测试的关系：断言是"文档化的不变量"，测试是"行为的证明"——断言红说明代码与作者意图背离，这类错误优先级永远高于测试红。
- 断言风格统一：assert($cond, new AssertionError('msg')) 带消息形态让 1/-1 切换时的诊断差异最小；团队只允许一种断言写法，grep 得出来才管得住。

## 84. 结构化日志架构与 PSR-3 边界

- PSR-3 的边界分工：message 是稳定模板（给人看的形态），context 是字段（给机器查的依据）——把 order_id 拼进 message 的日志等于放弃检索能力；interpolation 由 handler 做，Monolog JSON formatter 落盘即字段化。
- trace 贯通是架构不是配置：入口中间件生成/透传 trace_id → 请求级 context registry（FPM 用 per-request static、Octane 必须请求结束清理，97 节）→ 队列任务把 trace_id 写进 payload 接力——断一环，全链检索就断。

```php
$log->info('order placed', [
    'order_id' => $order->id,                // 检索键: 业务 ID
    'tenant' => $tenant->id,
    'amount_cents' => $order->amountCents,   // 金额整数化: 单位入字段名
    'trace_id' => Trace::current(),          // 贯通键 (全链检索的锚)
    'duration_ms' => $timer->elapsed(),
]);
// 采样与告警全部建在字段上, 不解析字符串
```

- fingers_crossed 的取舍：全量 debug 挤爆管道，关掉又缺案发现场——FingersCrossedHandler 缓冲 debug 级、错误触发时回放缓冲到错误通道，两头兼顾；必须设 buffer 上限与 passthrough 策略，防高错率流量雪崩日志量。
- 级别语义要写进团队文档并 CODEOWNERS 化：error=唤醒某人（带值班）、warning=可自动统计的趋势、info=审计级事实、debug=仅 fingers_crossed 缓冲——没有共识的级别 = 每人按心情填，告警永远对不上。
- 观测接入位次见 107 节：日志是三支柱之一，最小指标集（池重启/队列/慢请求）先行，追踪其次——顺序反了会得到"全链追踪很漂亮但没人看"的系统。
- 日志的 PII 治理：字段化才有脱敏抓手——按字段名配掩码（token/邮箱/姓名），message 模板里永不出现原始用户数据；这是字段化的第二个理由，第一个是检索。
## 85. PHPUnit 11/12 迁移硬点

- provider 静态化是硬线：非静态 data provider 在 10 报弃用、11 起直接移除——大仓库的 provider 全在 $this 上取工厂数据，机械改静态后 $this 依赖全断，先做"provider 自包含"重构再迁版本。
- 注解→属性是 11/12 的主旋律：@dataProvider/@depends/@group/@testWith 全部换 PHP 属性，11 弃用注解元数据、12 移除——用 rector 的 phpunit 规则集批量改，手工迁移在千级测试仓库上不可行。

```php
// provider 必须静态 (11 起强制); 注解元数据 → 属性
public static function thresholds(): iterable {
    yield [10, true];
    yield [0, false];
}
#[DataProvider('thresholds')] #[Group('billing')] public function testThreshold(int $v, bool $ok): void { ... }
// withConsecutive 已删: 回调手搓调用序
$repo->expects($this->exactly(2))->method('save')->willReturnCallback(
    fn (Order $o) => match ($o->id) { 1 => true, 2 => throw new DbLockTimeout() }
);
```

- withConsecutive 的替代不止一种：willReturnCallback+match 是显式序；更清晰的形态是断言"调用集合"而不是"调用次序"（mock 的 ->expects(...)->method(...) 逐次配置）——借迁移之机把"顺序耦合"测试改松，是这次迁移的隐藏收益。
- readonly 不可 mock：mock 生成器需要重写属性，readonly 类直接 fatal——值对象本来就"构造即终态"，直接 new 真实例测试；需要替身的场景抽接口，readonly 实现与 fake 实现双轨。
- 迁移次序纪律：先在当前大版本上修完弃用（provider/withConsecutive/注解三件），双绿后再动 major——一次跨两个 major 的 PR 评审不动，回滚也没锚点。

## 86. 快照测试与 --updateSnapshots 纪律

- 快照的价值是"锁定形状"，代价是"红得含糊"——diff 读得出改了什么，读不出该不该改；所以快照只用于稳定输出（导出/渲染/序列化格式），业务断言仍然显式写。
- 随机字段清洗要精确到字段：unset 白名单式剔除 created_at/uuid/trace_id，而不是裁掉整段——裁多了快照失去校验意义，裁少了必然红；配合钉死的工厂与冻结时钟（88 节），易变字段根本不进快照。

```php
$data = $exporter->row($order);
foreach (['id', 'created_at', 'request_id'] as $k) {
    unset($data[$k]);              // 白名单式清洗, 不裁整块
}
$this->assertMatchesSnapshot($data);   // 一行为一快照文件
```

- CI 禁 --updateSnapshots 必须是机制不是约定：流水线加参数白名单/脚本守卫，出现该 flag 直接失败——"测试红了顺手 update 提交"会把回归合法化；快照 PR 必须能指认到行为变更的证据（工单/设计变更）。
- 反巨石化：单个巨型 golden 文件把不相关变更全拉红，评审只能"看过就合"——按用例拆文件、文件名带场景，让 diff 面积与变更半径一致；巨石快照的拆分本身要专门排期做。
- 快照与特征测试（91 节）的分工：特征测试锁"遗留行为"，快照锁"输出形状"；两者都反哺不了断言意图——快照文件头部注释写明它锁定的是什么格式，防止半年后没人敢动。
- 格式变更评审材料化：导出/序列化格式的变更 PR 里，快照 diff 直接回答"这次改动影响哪些下游消费方"——不用靠记忆，文件清单即证据。
- 存储纪律：快照文件进仓库但按目录规整（一用例一文件），避免根目录爆炸；大二进制快照（PDF/图）走"哈希断言+样本归档"，别把二进制塞进 git。

## 87. 数据库测试：事务回滚与环境保真

- 回滚伞的经典破口是被测代码偷 commit：外层 beginTransaction 包一切，业务代码里 commit 把伞捅穿，数据落库静默污染后续用例——tearDown 断言事务嵌套层级回到 1，把"偷 commit"当测试失败处理，而不是无脑 rollBack 盖过去。

```php
protected function setUp(): void {
    parent::setUp();
    $this->conn->beginTransaction();                  // 外层伞
}
protected function tearDown(): void {
    if ($this->conn->getTransactionNestingLevel() === 0) {
        $this->fail('被测代码提交了外层事务, 回滚伞失效');   // 抓偷 commit
    }
    while ($this->conn->getTransactionNestingLevel() > 1) { $this->conn->rollBack(); }
    $this->conn->rollBack();
}
```

- sqlite 假信心的边界：跑得飞快但 JSON 函数/锁语义/严格模式/charset 行为与 MySQL 全不同——纯逻辑用例可以用；任何"测的就是 SQL 行为"的用例一律真引擎容器（CI 起一次性 MySQL，用完即弃），方言漂移的 bug 只能在真引擎上暴露。
- 查询数门禁是 N+1 的测试化：关键路径用例断言 maxQueryCount 上限（监听 SQL 事件计数），超限即红——比人工 review "这里是不是 N+1" 可靠，且升级/重构后自动有人盯着回归（94 节 CI 门禁同源）。
- 环境保真的其余项：时区固定 UTC（88 节）、外键检查全开、SQL 模式对齐生产 sql_mode——测试环境宽松一分，生产就多一类"只在生产炸"的 SQL。

## 88. 时钟抽象与测试时间纪律

- PSR-20 注入是唯一正解：ClockInterface 作为显式依赖进构造器，谁用谁注入——时间从"环境隐变量"变成"可替换的值"，冻结/拨快只影响被测对象。
- 禁 setTestNow 全局态：Carbon::setTestNow 是进程级静态，并行测试互相污染（90 节）、忘清理就传染下一个用例，而且并行 worker 下行为不确定——存量 setTestNow 的清除本身就是一项迁移工程。

```php
final class SystemClock implements ClockInterface {
    public function now(): DateTimeImmutable {
        return new DateTimeImmutable('now', new DateTimeZone('UTC'));
    }
}
$clock = new FrozenClock(new DateTimeImmutable('2025-06-30T23:59:59Z'));
$svc   = new SubscriptionRenewer($clock, $repo);
// 月末/闰日/DST 切换的边界, 直接构造时刻, 不再"睡到那天"
```

- UTC 铁律：存储与计算全 UTC，只在展示边界转 local——DST 的重复小时/缺失小时造成的账单事故，根因几乎都是"存了带本地时区的时间"；测试里时区固定，杜绝环境时区影响结果。
- 随机注入边界日期：固定钟之外，CI 随机抽（可种子，73 节引擎）月末/闰日/年终/时区切换时刻注入跑全量——时间 bug 只在边界显形，随机播种让它在上线前撞墙，且失败可按种子重放。
- "时间冻结"与"时间加速"是两个需求：断言"当时刻"用冻结钟；断言"超时/过期行为"用可控加速钟（VirtualClock）——一个 FakeClock 同时支持 now() 与 advance()，别在测试里 sleep。
- 时钟实现也进热路径审查：每请求多次 now() 的代码里，包装了时区转换的装饰钟别在循环里反复构造——把"取当前时刻"当热路径设计，而不是免费的魔法。

## 89. 变异测试 Infection 与 MSI 门槛

- 覆盖率的谎言：行覆盖只证明"代码执行过"，不证明"断言存在"——变异测试改语义看谁变红，才是断言强度的度量；覆盖率做下限门槛，MSI 做质量指标，两者不可互替。
- 门槛棘轮：min-msi 只升不降；PR 里降门槛必须人肉审批 + 写理由；新模块从当前水位起算——不允许"新代码拉低全仓再整体豁免"，这是变异测试制度死亡的标准路径。

```bash
vendor/bin/infection --min-msi=85 --threads=max --show-mutations
# MSI = killed / (killed + escaped + timed out)
# 覆盖率 100% 而 MSI 低 = 测试"走过没断言"的铁证
```

- mutant 三分法处置：killed=好；escaped=缺失断言，当真要修（对着 diff 写一条会红的断言）；timed out=性能敏感断言缺失或死循环候选，单独看——批量忽略 timed out 会把真死循环放走。
- 不追 100%：getter/DTO/纯配置的变异逃逸是噪音——按目录配 ignoreMsiWithNoMutators / 豁免规则，把精力留给业务复杂度高的模块；全员 100% 的团队最后都会把 Infection 关了。
- 运行成本预算：全仓跑一次按小时计——按"改动模块增量跑 + 每日全量跑一次"分频；增量变异只算 PR 触及的文件，CI 时长可控。
- MSI 的健康水位：业务核心模块 80+、基础设施工具类 60+ 是常见起点——水位低于 60 的模块先补断言再谈棘轮；"全仓一个数字"是伪治理。
- 逃逸样本库：把 escaped mutants 的修复案例（diff+新断言）归档成模式库——半年后团队会发现八成逃逸都是五类模式（边界值/null/异常路径/重复断言/状态未断言）。

## 90. 并行测试与状态污染排查

- TEST_TOKEN 纪律：paratest 给每个进程注入 TEST_TOKEN（1..N），数据库名/Redis db/上传目录全部带 token 后缀——否则 8 个进程互踩数据，失败形态是"偶发且换一台机器就不复现"。
- 随机序定位配对犯罪：状态污染是 polluter+victim 的配对（污染者先跑、受害者后爆），固定顺序下永远绿——随机序偶发红，拿到 seed 就能稳定复现，再二分缩到最小用例对。

```bash
vendor/bin/paratest --processes=8            # TEST_TOKEN 自动注入 1..8
# 状态污染复现 (PHPUnit 层):
vendor/bin/phpunit --order-by=random --seed=1732054 --stop-on-failure
# 拿着失败输出里的 seed 原样重跑 → 稳定复现 → 二分缩对
```

- 全局快照守卫：setUp 快照静态属性/容器绑定/超全局与 env，tearDown 对比并还原——守卫让"忘清状态"当场红在肇事测试上，而不是三天后某个无辜用例的玄学失败；Laravel 的 refreshApplication 是内置版，自建框架要自己写。
- 必须独占资源的用例（DDL/全局配置）标记进单独 suite 串行跑——并行框架不解决"物理上不能并行"的用例，硬塞进去是自找失败。
- 排查次序固化：先复现（seed）→ 二分定位配对 → 看污染者写了什么全局态 → 修复后把守卫规则补上——跳过守卫直接修，同款污染下个月再来一遍。
- TEST_TOKEN 的传播纪律：夹具工厂统一从 Env 读 token 后缀，测试代码绝不手拼连接串——"某用例自己 new 了一个无后缀连接"就是下一起偶发失败的起点。
- 并行度按套件分组：I/O 重的集成套件并行收益大，CPU 重的检查类套件并行反而抖动——分组设并行度，而不是一刀切 --processes=N。

## 91. 特征测试：遗留代码安全网

- 先锁现状再重构：特征测试断言"是什么"而不是"该是什么"——期望值从现状录制（含已知怪癖：负折扣、奇怪的四舍五入方向），重构期全绿 = 行为未漂移；想改行为，先改测试并挂 bug 编号，安全网永远优先于正义感。
- 录制动线：生产/预发采样真实输入输出落 fixtures（脱敏后），断言"输出不变"；采样覆盖要分层——高频路径、边界值、历史 bug 样本各占一档。

```php
/** @group characterization 锁现状: 重构安全网, 断言"是什么"不是"该是什么" */
public function testLegacyPricingMatrix(): void {
    foreach ($this->recordedCases() as $i => [$input, $expected]) {
        $this->assertSame($expected, LegacyPricing::calc($input), "case #{$i}");
    } // 期望值含已知怪癖——别修断言, 只锁; 改行为走工单+改测试
}
```

- 标注意图是纪律的一部分：特征测试必须打 group + 注释声明"锁的是现状"——否则后人看到断言里的怪行为会"顺手修断言"，安全网当场变事故网；CI 可以按 group 单独统计其占比。
- 与其他纪律的边界：不进 Infection（变异全灭是常态，纯噪音，89 节按目录豁免）；不算单元测试充数汇报；新抽象有自己的行为测试后，对应特征测试退役归档而不是永久供着。
- 重构完成的判据：特征测试全绿 + 新抽象的行为测试全绿 + 特征测试可删——三件齐了才叫"重构完成"，只有第一件叫"重构没坏"。
- 录制盲区显式化：采样录不到的分支（超低频/超高管路径）要么用合成输入补录，要么显式标记"不在保护范围"——假装覆盖比承认盲区更危险。

## 92. HTTP 假件与契约测试

- 桩建在应用自己的客户端接口/PSR-18 层，不是 socket 层：桩位越贴近被测逻辑越有价值；太低（拦 socket）会让"请求构造是否合法"完全脱管——外部服务的契约错误要在测试里炸，而不是联调时。
- 序列而非单响应：外部依赖的真实风险是"前两次好第三次 429"——单响应桩测不出重试风暴与熔断打开条件；序列桩 + 断言调用次数与退避节奏，才是把弹性逻辑测真的方式。

```php
$fake = new SequencedFakeClient([
    new Response(200),
    new Response(429, ['Retry-After' => '2']),
    new Response(500),                       // 序列驱动重试/熔断路径
]);
$svc = new BillingApi($fake, $clock, $retryPolicy);
$this->assertSame(2, $fake->calls());        // 重试上限真的生效
$this->assertRequestMatches($fake->lastRequest(), 'billing.openapi.yaml');
```

- 契约校验双向：请求按 OpenAPI 校验（防客户端漂移：多传/错型/漏必填），响应也校验（防上游变更静默穿透）——上游加字段无害，改字段类型会在测试里红，这正是想要的效果。
- 录制型桩（PSR-18 代理录一次真流量回放）用于回归基线：比手写序列真实，但录像会过期——定期对真环境刷新（夜间 job diff 新旧录像），过期录像比没有录像更危险（它给了假信心）。
- 假件与真实集成测试分层：假件跑全部单元/功能用例（快、稳定、覆盖错误路径）；每周一次对真实 staging 的冒烟——两级都要，用高频换深度、用低频保真。
- 契约文件的归属与版本化：OpenAPI schema 由服务方与消费方共同评审变更、版本化进仓库——"上游改了字段没通知"在契约测试面前会变成一次红色 CI，这正是它的全部意义。

## 93. Laravel 容器与上下文绑定深水

- singleton 与 scoped 的分界在 Octane 下致命：singleton 绑定的生命周期是 worker（跨请求存活！），scoped 才是请求级——从 FPM 迁 Octane 时没过这一条，等于每个请求在共享一个实例（97 节五源之首）。
- 上下文绑定是"给谁用什么"的精确表达：when(PhotoController)->needs(Filesystem)->give(...)；散落在多个 provider 的 when() 会拼出隐式依赖网——集中注册 + 测试断言关键组合，升级时这块最容易碎。

```php
// Octane 语境下的分界:
$this->app->singleton(ReportCache::class);   // worker 级 (跨请求存活!)
$this->app->scoped(TenantContext::class);    // 请求级, 每请求重建
// 上下文绑定: 按"谁要"给不同实现
$this->app->when(PdfExporter::class)
    ->needs(Filesystem::class)
    ->give(fn () => Storage::disk('s3'));
```

- env() 禁区：config:cache 之后 env() 只在 config 文件里可靠，任意业务类里直接 env() 读到 null——本地没缓存永远绿，上线才炸；静态分析加自定义规则禁用类内 env() 调用，比口头规范可靠。
- defer provider 的坑：延迟提供者依赖"接口→实现"注册表可发现，容器被 flush（Octane）时触发次序难预期——常驻运行时下干脆禁用 defer，全量即时注册，启动成本换确定性。
- 容器即 API：绑定方式（singleton/scoped/when/give）是公共契约，重构 provider 时按"会破坏什么测试"来评审，而不是"能跑就行"。
- 解析结果缓存的边界：热路径上反复 make() 同一接口可用装饰器缓存实例引用——但缓存本身就是状态，Octane 下它跟着 worker 活，串味风险重新回到 97 节矩阵。

## 94. Eloquent N+1 解剖与 preventLazyLoading

- shouldBeStrict 三合一：preventsLazyLoading + preventsAccessingMissingAttributes + preventsSilentlyDiscardingAttributes——开发与 CI 全开硬失败，把"忘了 with"变成当场红，而不是日志里的猜谜。
- 生产只采集不断请求：硬抛会拿真实流量当代价——生产模式改为 handleLazyLoadingViolationUsing 转 report()，按 (model.relation) 维度聚合计数告警，修复排期；两模式用 env 显式切换。

```php
// 开发/CI: 全开 (shouldBeStrict 全家)
Model::shouldBeStrict();
// 生产: 只采集
Model::preventLazyLoading(! app()->isProduction());
Model::handleLazyLoadingViolationUsing(
    fn ($model, $relation) => report(new LazyLoadingViolation($model, $relation))
);
$posts = Post::with(['author:id,name,tenant_id'])
    ->withWhereHas('comments', fn ($q) => $q->where('approved', true))
    ->paginate(50);
```

- preventLazyLoading 的覆盖边界：拦的是"属性访问触发后续查询"；whereHas 子查询、集合上 filter 后逐条 save 这类"N 条 update"不算 lazy load——单点工具有盲区，查询数上限才是总闸（87 节门禁）。
- with 的列裁剪默认化：`with(['author:id,name,tenant_id'])` 只取需要的列——eager load 本身省的是往返数，列裁剪省的是水化内存，大表导出场景两者都是瓶颈项。
- whereHas 与 withWhereHas 的分工：只要"有评论的文章"用 whereHas（子查询 EXISTS）；既要过滤又要预载用 withWhereHas——写错成"先 whereHas 再 with"会拉回双查询，性能差一截还查不出毛病。

## 95. chunk/cursor/lazy 分页语义差异

- 四种遍历语义完全不同：chunk（offset 翻页）遍历中删行→跳过、插行→重复；chunkById（主键游标）稳定但每块一次重查；cursor 单查询生成器，省的是水化内存、省不了结果集内存；lazy 底层是 chunkById 的集合化，内存有界。
- "未缓冲"才是真流式：cursor 的默认实现里 mysqlnd 仍缓冲整个结果集到驱动层——千万行导出必须显式未缓冲模式，且独占连接（见下），否则内存曲线照样爬满。

```php
User::chunk(500, $cb);        // offset: 边跑边写 → 漂移/重复
User::chunkById(500, $cb);    // 主键游标: 稳, 每块重查
User::cursor()->each($cb);    // 单查询生成器: 驱动层仍缓冲全结果集
User::lazy()->each($cb);      // LazyCollection: 底层 chunkById

// 真流式: 独占连接 + 未缓冲, 取完前该连接禁做其他任何查询
$pdo = DB::connection()->getPdo();
$pdo->setAttribute(PDO::MYSQL_ATTR_USE_BUFFERED_QUERY, false);
```

- 同连接并发禁令：未缓冲查询没取完前，同一连接再发查询直接报错（"Commands out of sync"族）——流式作业开专用连接，主业务连接绝不掺和；连接池配置里给它单独留一个池位。
- 页式漂移的工程结论：处理任务一律 chunkById，且只信单调递增主键；分页接口给用户的"offset 页"另算（那是语义问题不是正确性问题）。
- 每块重查的一致性代价：chunkById 的每块都是新查询，看到的都是"当下"的数据——要"某时刻一致快照"的报表场景，流式+事务快照隔离一起上，别指望遍历工具自己保证。
- 端到端测内存曲线：真流式 + 逐行写入 + 定期 flush 才省到大头——常见翻车是"遍历工具换对了，落盘又把全量数据堆回内存"，只测中间点不算验证。

## 96. 中间件管道与 terminate 阶段

- middlewarePriority 的边界：只有列进清单的中间件参与强制排序，未列者按注册顺序"挤进去"——新增全局中间件后 auth 提前于 session 的静默重排，会让会话再生在错误阶段突然炸；管道顺序要用快照测试钉死。
- terminate 在响应发出后跑、worker 未释放：用户体感 0ms，FPM/Octane worker 全额计费——重 terminate 任务（分析/上报）挂多了，池吞吐隐性下滑；大头挪队列（100 节），terminate 只留毫秒级采集。

```php
class RequestMetrics {
    public function terminate(Request $req, Response $res): void {
        Metrics::observe('http.duration', microtime(true) - START);
    }   // 响应已发出, 但 worker 仍被本回调占用 (FPM/Octane 全额计费)
}
// 管道顺序快照测试: 顺序是行为契约的一部分
public function test_middleware_order_frozen(): void {
    $this->assertSame(['throttle', 'auth', 'audit'],
        app(Router::class)->gatherRouteMiddleware($this->getRoute('orders.show')));
}
```

- 顺序快照的价值：中间件顺序决定"谁先看请求、谁先改响应"，是隐式契约——快照测试让"顺手调个顺序"必须显式过评审，比任何文档都管用。
- terminate 与 Octane 的组合坑：worker 常驻下 terminate 忘清理/抛异常会污染下一请求——terminate 内异常必须被捕获并 report（下个请求的起点不能是脏状态，97 节矩阵）。
- terminate 的注册面容易被遗忘：它只在通过全局管道的路径生效，队列任务/命令行路径没有 terminate——把关键审计只写在 terminate 里，等于在非 HTTP 路径留了个审计盲区。

## 97. Octane 状态泄漏矩阵

- 泄漏五源清单：静态属性持请求态、容器 singleton 持可变态、运行时改写 config/env、DB 事务与锁未清、静态门面里缓存的请求引用（Auth/Request 类静态化）——每一源都能在代码里 grep 出来，评审就按这五条过。
- 四防线必须逐条落实：scoped 替代 singleton（93 节）、Octane flush/listener 显式登记清理、静态只放 immutable 的团队禁令、串味测试进 CI 常态跑——防线有测试兜着才算防线，"约定"不算。

```php
// config/octane.php: 防线 2 —— 请求级清理登记
'listeners' => [
    RequestReceived::class  => [ResetTenantState::class, ...],
    RequestTerminated::class => [RollbackStrayTransactions::class, ...],
    TaskReceived::class     => [ResetTenantState::class],
],
// 防线 4 —— 串味测试 (50 节模板): 双租户连续打同一路由断言隔离
```

- 最阴险的一源是事务：请求 A 异常未回滚 + worker 常驻 → 请求 B 捡到 open transaction，表现是"偶发锁等待/读脏数据"，离"串号"症状十万八千里（110 节同根）——RequestTerminated 钩子强制 rollback 是最后闸。
- Task/Tick 路径同罪：队列任务与定时器共享 worker 状态，listener 只挂 HTTP 请求等于半裸——TaskReceived/TimerTicked 事件同样要登记清理。
- flush 不是全能的：Octane 自动 flush 容器里"请求期注册"的绑定，但你静态属性、外部进程状态（文件锁）、全局单例（单例化的服务客户端）不在容器里——flush 之外的世界要靠防线 3 与测试兜底。
- Swoole 不是 Octane 的超集：协程上下文类替代静态属性的正确姿势、连接池的并发语义都要单独过一遍矩阵——"Octane 上干净"不自动等于"Swoole 上干净"。

## 98. Symfony DI：编译容器与 lazy proxy

- frozen 语义：生产容器编译后冻结，运行期 set() 直接炸（BadMethodCallException）——想运行期注入的必须声明 synthetic service（如与 legacy 全局代码桥接）；这不是限制是保护：能编译的都是确定的，不确定的进不了产物。
- 传统 lazy 代理的成本：每个懒服务生成"全方法拦截"代理类，反射开销 + 调试栈被代理层污染；Symfony 7.3+ 可选原生 lazy ghost（PHP 8.4 机制，5 节）——无代理类，代价小得多，但注意 ghost 的身份语义与 readonly 交互。

```yaml
# 编译期决定一切; 运行期容器只读 (frozen)
App\Billing\Client:
    lazy: true              # 传统: 运行时代理类 (重)
# Symfony 7.3+ / PHP 8.4: 原生 ghost, 免代理生成
App\Heavy\Importer:
    lazy: ghost
```

- lazy 的判据不是"启动慢"而是"依赖图深"：构造链条里某个重服务只在少数分支用到，lazy 有收益；为了几十个服务都 lazy，代理层反而吃掉收益——按测量做决策，不按感觉。
- warmup 进镜像：cache:warmup 在镜像构建期执行（容器 dump、注解/序列化缓存全进产物），容器启动零编译——忘了 warmup，第一个请求背上全部编译成本，就绪探针超时误判（105 节）。
- 编译期与运行期的清晰分界是这个体系的全部哲学：env 处理器在编译期固化（99 节）、lazy 在编译期决策、运行期只读——评审配置时先问"这条在哪个期生效"。
- 服务 ID 稳定性纪律：别名/接口绑定关系可以变，但服务 ID 别乱改——warmup 产物引用着 dump 后的容器，ID 漂移 = 启动期死链接，冒烟时才爆。

## 99. Symfony 内核事件与 env 处理器

- 事件序即行为：kernel.request 按优先级串行，任何 listener 返回 Response 即短路——防火墙在路由前重定向/拒绝、维护模式比谁都先跑；新 listener 的优先级决定它看到的世界，这类变更必须带行为测试。
- kernel.terminate 与 96 节同坑：响应发出后 worker 仍占——重活挪 messenger（100 节），terminate 只做轻采集；terminate 里的异常同样要兜住（messenger worker 的下一消息起点不能脏）。

```yaml
# config: env 处理器在容器编译期把"读取+转换"固化进产物
parameters:
    db_port: '%env(int:default::9000:DB_PORT)%'      # int 转换 + 默认值
    api_key: '%env(trim:file:API_KEY_FILE)%'         # 文件注入 + trim (秘钥挂载)
    features: '%env(json:FEATURE_FLAGS)%'            # json 解码
```

- env 处理器编译期固化：运行期改 putenv 不生效——这是特性（不可变产物，104 节）：同一个镜像可以放心跑所有环境，因为环境差异只在启动时的 env 与挂载文件里。
- file: 处理器配 secrets：密钥以挂载文件形式进容器（K8s secret/Docker secret），file: + trim 读取——密钥旋转 = 换文件 + 重启，不重发镜像，也不把密钥烧进镜像层。
- 事件短路的三种合法用法：安全拒绝、维护页、缓存命中直出——除此之外的短路（比如某个 listener 直接 return 404）会在排障时让人怀疑人生：响应有了却没进过控制器。
- env() 直呼的禁令（与 93 节 Laravel 同理）：env 处理器之外直接读环境变量，等于把"不可变产物"打了个洞——运行期行为的差异不再可预测，这比配置多一点少一点严重得多。

## 100. 队列幂等与背压设计

- 至少一次是事实不是选择：worker 崩溃/超时/重投都会重复——入队唯一性（ShouldBeUnique/uniqueId）只挡入队时的重复，失败重投照样双跑；消费端业务级幂等键（租期+金额+状态机迁移）才是承诺所在。
- 三数递减铁律：retry_after > --timeout > 任务真实 P99 时长——反了会出现"僵尸任务双跑"（worker 还在干活，retry_after 已到又发一份）；上线前把这三个数写进 runbook 互相校验。

```php
class RenewSubscription implements ShouldBeUnique {
    public $tries = 3;
    public $timeout = 25;                        // < retry_after (30s)
    public function uniqueId(): string {
        return "renew:{$this->subId}:{$this->period}";
    }
}
// 消费端再闸一道 (唯一锁只管入队, 不管重投):
if (!Dedup::acquire("job:{$this->jobId()}", ttl: 600)) { return; }  // 本条是重投
```

- 背压三阀按序开：深度告警（还来得及）→ 扩 worker（消化）→ 熔断/丢低价值任务（保命）——没有第三阀的队列，在风暴里会把 DB 连接池和 worker 内存一起拖死（111 节同型螺旋）；三阀的阈值写代码里，不写值班手册里。
- 失败语义显式化：tries/retry_after/backoff 三个参数决定"失败时的用户体验"，默认值是拿来兼容的、不是拿来用的——每个任务类声明自己的策略，未声明的进 CI 检查清单。
- 观测联动：队列深度、最老任务年龄、重投率三指标进最小指标集（107 节）——背压阀的触发阈值就按这三条曲线标定，拍脑袋的阈值等于没有阈值。

## 101. 框架升级纪律与 LTS 窗口

- 版本线事实先于一切计划：Symfony 8 押 PHP 8.4（引擎升级必须先行，1 节），Laravel 12 线在 PHP 8.2——两条升级轴（PHP/框架）显式排先后，同一 PR 双升 = 事故归因不可能，回滚也没锚点。
- 弃用清零是入场券：major 升级会把一直没清的弃用变成 fatal——把弃用当流水线硬门槛（82 节单列通道，季度清零），而不是升级周的"惊吓清单"；phpstan-deprecation-rules 静态兜底，双通道合围。

```bash
# 升级流水线纪律 (顺序不可换):
composer outdated --direct                  # 1) 盘点, 不盲跑 update
vendor/bin/phpstan analyse --level max     # 2) 弃用清零 + 死分支清理
vendor/bin/phpunit --order-by=random       # 3) 基线先绿 (90 节), 再动 composer
# 升级专支: 只含 composer/lock + 配置适配, 业务代码零夹带
```

- 双版本灰度：新旧两套镜像同时在线，按 Pod/流量比例切换 + 对比错误率与查询数（回归的 N+1 会先在查询数上露头，94 节门禁兜着）；旧镜像保留 N 天，回滚就是切权重，不是重新构建。
- 回滚清单先行：升级 PR 必须附"哪些变更不可回滚"（配置缓存/迁移/事件契约/队列 payload 格式）——迁移永远 expand-contract（103 节），保证代码可以双向走；payload 格式变更要带版本键（70 节同型思路）。
- LTS 窗口的日历管理：框架 LTS 到期日进团队日历，提前一个季度启动升级——"安全补丁结束"那天才开始升级的团队，做的是危机管理不是升级管理。
- 升级周的时间盒：预设"两周升不完就止损回滚"的硬线——拖成月度工程的升级最后都停在半途：新框架+旧弃用代码+未知行为漂移，比不升级更难维护。

## 102. 模板引擎的逃逸保证与例外

- 双引擎基线一致：Twig 默认全量 autoescape（html），Blade {{ }} 默认转义——逃逸保证的强度等于最弱的一个 raw/{!! !!}；所以 raw 必须清单化，逃逸纪律在模板层是"全局自动 + 例外显式"。
- 双编码事故形态：库里存的是历史系统转义过的 HTML（&amp;lt;），新引擎再转义一次 → 满屏实体——正确修法是入库侧一次性清洗迁移，而不是模板里到处 |raw（那是把修复成本转嫁给攻击面）。

```twig
{# Twig: 默认自动转义; raw 是显式开口, 必须进清单 #}
{{ user.bio }}                    {# 自动转义 #}
{{ admin_snippet|raw }}           {# 违例: 进 raw 清单, 评审盯 #}
{% if not app.debug %}{% endif %} {# 调试输出不上生产 #}
```

- raw 清单机制：CI grep 新增 raw/{!! !!} 即红，例外走登记表（文件+行号+负责人+理由）——没有登记的 raw 是潜伏的存储型 XSS；Blade 的 @verbatim、Twig 的 raw filter、verbatim 块全都在扫描范围。
- Twig Sandbox 用于用户可写模板（邮件/通知/CMS 片段）：白名单 tags/filters/方法调用，违规抛 SecurityError——不沙箱化的"用户模板"迟早变成 SSTI→RCE（68/112 节同族攻击面）。
- JS 上下文永远出模板：模板只嵌 JSON（74 节的 json_encode 五旗），不要在模板里拼 JS 字符串——模板引擎的 autoescape 只懂 HTML，它对 script 块里的语义一无所知。
- 组件接口不收预渲染 HTML：组件参数传结构化数据——一旦组件边界收 HTML 字符串，autoescape 语义就在边界处破产，raw 清单又得加一条。
- 邮件模板独立配置：邮件客户端的 HTML 支持参差（内联样式/无 script），转义与净化白名单单独配——Web 模板那套纪律平移过去，但白名单内容完全不同。

## 103. 部署原子性与 opcache 复位策略

- 复位次序的原理：opcache 按 realpath 键控，current symlink 切到新 release 后 reload 即增量编译（旧 worker 优雅退、新 worker 分散编译）；opcache_reset 是把整池 SHM 一次倒空——流量尖峰 + 全量重编译 = 自残（111 节螺旋）。
- 次序即策略：蓝绿池切换（全新池冷启动，可控）> reload（优雅滚动）> reset（最后手段）——每个层级都有明确的适用场景，跳级使用就是事故。

```bash
ln -sfn /srv/releases/2025-0612-abc /srv/current   # 原子切 symlink
sudo systemctl reload php8.4-fpm                    # 优雅回收 worker
# opcache_reset() 是最后手段: 全池瞬时回冷; 且 CLI 里的 reset 不影响 FPM 的 SHM
# FPM 内存指标与容量预算见 111 节 (三线面板)
```

- expand-contract 两阶段：迁移先加列（带默认值，可回填）→ 双写期 → 再删旧列；部署原子性的前提是"新旧代码同时能活"，数据库变更是它最大的破口——删列永远独立发布、永远晚于"旧代码全部下线"一个窗口。
- preload 是池级状态：opcache.preload 在 FPM 启动时编译类树，reload 会重跑——preload 脚本崩 = 全池起不来；preload 产物随 release 变更要冒烟（deploy-warm，61 节），这是 reload 路径上最贵的单点。
- symlink 路径烘焙陷阱：产物里别把绝对 release 路径写死（缓存键/日志路径/配置），切 release 后这些路径全部失效——一切路径引用走 /srv/current，realpath 差异交给 opcache。
- 滚动重叠期的算术：pm.max_spare_workers × 优雅退出时间 = 新旧代码共存窗口——窗口内两个 release 同时在跑，expand-contract 两阶段约束正是为它准备的。

## 104. 不可变产物与配置外置

- 一条铁律：同一镜像跑所有环境。把 config:cache/warmup 放进构建期 = 把构建机的 env 烧进二进制，"环境只差一个变量"的镜像就是两份产物——缓存/预热必须挪到容器入口（启动成本换确定性）。
- 多阶段构建是手段不是目的：build 阶段（composer + dev 工具 + warmup 试跑）→ runtime 阶段只拷产物层（无 git 历史、无 dev 依赖、无源密钥）——镜像层里翻不出 secrets 是审计硬指标。

```dockerfile
# ---- build stage ----
FROM php:8.4-cli AS build
COPY . /src
RUN composer install --no-dev --classmap-authoritative
# ---- runtime stage ----
FROM php:8.4-fpm
COPY --from=build /src /app
ENTRYPOINT ["/app/docker-entrypoint.sh"]   # 此刻才 config:cache + warmup
```

- 秘钥注入时机：镜像永远不含秘钥（历史层也翻不出来），运行期由 secret 机制挂载/注入（99 节 file: 处理器读挂载文件）；旋转 = 换文件 + reload，不重发镜像——把"旋转秘钥"变成"重新发布"的团队，最终会不旋转秘钥。
- 产物指纹：composer.lock 哈希、迁移版本、配置缓存一起构成产物指纹——部署系统按指纹取镜像回滚，回滚要的是"当时那个产物"，不是"当时那个代码重新构建一遍"（新构建引入新依赖 = 回滚掺假）。
- 启动钩子的时间预算：入口脚本（config:cache + warmup + 探针就绪）的时长决定 startup probe 阈值（105 节）——测量它、写进 probe 配置，别抄模板。
- 回滚兼容性是演练出来的属性：定期演练"回滚到 N-2 产物"并验证配置缓存兼容（新配置字段在旧代码里是未知项）——发布流程图里的"回滚"框，不代表回滚真的可用。

## 105. 健康检查与就绪探针设计

- 三层探针各管一段：startup（慢启动：warmup、preload、连接池预热，阈值宽）→ livez（进程活着，绝不做深检查）→ readyz（依赖就绪 + 迁移版本对齐，决定接不接流量）——三层混写是探针设计第一事故源。
- livez 的红线：它决定"要不要杀我"——深检查 DB 一抖 = 全副本重启风暴；依赖健康是 readyz 的职责，livez 只回答"本进程还能处理请求吗"。

```php
// /healthz (livez): 只证明进程活着, 不碰任何依赖
// /readyz: 依赖 + 版本对齐
$migrated  = (int) $this->db->scalar('SELECT MAX(version) FROM migrations');
$queueOk   = $this->redis->ping();
return ready(
    $migrated >= self::EXPECTED_MIGRATION
    && $queueOk
);   // 探针结果短 TTL 缓存 (1-2s): 探针流量打不死依赖
```

- 迁移版本对齐：readyz 校验"迁移已跑到本产物要求的版本"，未对齐不接流量——部署次序（迁移→切流量）由探针兜成机制，而不是靠 runbook 记忆；这也是 expand-contract（103 节）的执行保证。
- 探针零副作用：不写库、不发队列、不建会话（否则探针流量污染监控数据，session 锁还能拖垮吞吐，72 节）；探针路由绕过中间件栈的重量级部分，但它必须走真实 FPM 路径（探针测的是"这个池还活着吗"，不是"网关还活着吗"）。
- 降级就绪的语义决策：依赖故障时 readyz 失败 = 摘流量（不重启，等恢复）；要把"部分依赖可选"做成就绪降级（主库挂→只读模式→ready 但带 degraded 标记），这是产品决策，必须显式写在探针设计里。
- 迁移任务与探针共享版本语义：跑迁移的 job 与 Web 产物用同一个 EXPECTED_MIGRATION 常数——迁移没跑完就 ready 的池子，会把"部分迁移"暴露给流量。

## 106. 容器化：镜像分层与 FPM 入口

- musl 的账要算总收益：alpine 省的体积 vs musl 与主流扩展的二进制兼容面（iconv 编码集、DNS 解析行为、glibc 假设的扩展段错误）——生产镜像选 debian bookworm-slim；要极致小走 multi-stage + 精简 runtime 层，不换 libc。
- PID1 问题：FPM master 不收孤儿，pcntl_fork 出去的旁支（51 节卸载池）变 defunct 堆积——tini/dumb-init 收养或运行时 --init；K8s 下由容器运行时处理，自建部署仍要自带。

```dockerfile
FROM php:8.4-fpm-bookworm
RUN docker-php-ext-install opcache pdo_mysql
STOPSIGNAL SIGQUIT                  # FPM 优雅停: 干完手头请求再退
ENTRYPOINT ["/usr/bin/tini", "--", "docker-php-entrypoint"]
USER 33:33                          # www-data: root 只留构建期
```

- STOPSIGNAL=SIGQUIT + 优雅期：默认 SIGTERM 是快速杀，滚动发布时 502 一片就是它；配 terminationGracePeriodSeconds（≥ 最慢请求 P99）+ preStop sleep（先摘探针再收 SIGQUIT）。
- non-root 是底线不是加分项：runtime 层 USER www-data，文件权限构建期烘好；镜像内 root 运行 = 容器逃逸面直接翻倍，且 K8s securityContext 的 runAsNonRoot 校验会拒收。
- 日志与慢日志出口：access.log/slowlog 全走 /proc/self/fd/2（82 节 stderr 汇流），catch_workers_output 打开——容器里写文件的 FPM 日志等于没有日志。
- pm.status_path 的暴露面：只对 localhost 或经鉴权暴露，探针由 sidecar/网关代取——status 页里的连接与 worker 明细是给攻击者的免费侦察图。

## 107. 观测接入点：日志、指标、追踪

- 指标优先级压倒一切：FPM 池重启率（restarts/oom_restarts，111 节火警）、listen queue（背压前兆，108 节 backlog 对齐的裁判）、队列深度与最老任务年龄（100 节三阀输入）——三个数就能把大部分容量事故提前一小时看到，全链追踪是第二阶段投入。
- OTel bootstrap 的 PHP 事实：没有常驻 agent，自动注入靠 auto_prepend_file 在请求最早期挂 SDK——bootstrap 顺序错了丢前几条 span；FPM 每请求重建 exporter，出口采样必须按 trace_id 稳定哈希（同一链同一个判决，否则断链）。

```ini
; FPM 状态面: 最小指标集的采集源
pm.status_path = /fpm-status        ; listen queue / slow requests / restarts
pm.max_requests = 500               ; 计划内回收 (50 节内存卫生的闸)
; 慢请求双通道: request_slowlog_timeout + slowlog=stderr 进汇流
```

- 采样 profiler 动线：生产常开低频采样（Excimer/OTel continuous profiling），样本聚合火焰图归档；慢请求告警直接跳对应时间窗的火焰图定位——而不是临时装 Xdebug 复现（Xdebug 是排障器不是观测器，生产装它 = 性能事故）。
- 日志与指标的接缝：日志按字段聚合出指标（如 php.fatal 计数来自 80 节 shutdown 通道），指标触发告警，告警链接回日志检索（84 节 trace_id 贯通）——三支柱的闭环是设计出来的，不是买来的。
- 接入点纪律：观测代码（计数/打点）与业务代码解耦（中间件/装饰器/事件监听），生产可整体开关——观测本身出问题（打点阻塞）拖垮业务的事故，比没有观测更尴尬。
- 面板先于工具链：团队陷进"选哪个 APM"的会议时，先把三个最小指标的手绘仪表盘做出来——工具链可以等，没有指标的日子每一天都在裸奔。
- 采样率先算乘积：采样率 × 每请求 span 数 × QPS = 后端写入量——上线前算清这笔账，"先全量采集再优化"的账单会让 CFO 先于事故找上门。

## 108. Socket 与 TCP upstream 混部

- 选型判据：同机/同 pod = unix socket（省握手与 TIME_WAIT，小请求场景 P99 常见两位数百分比差距）；跨 pod/中心池才用 TCP——为"统一"全走 127.0.0.1 是白付协议栈，为"省事"全走 socket 是锁死拓扑，按位置选。
- keepalive 双侧缺一不可：nginx 侧（fastcgi_keep_conn on / upstream keepalive）+ 应用侧长连接支持，缺一侧就是每请求重建连接——高并发下 ephemeral port 耗尽与 TIME_WAIT 堆积是经典事故面。

```nginx
upstream fpm_local {                   # 同 pod: unix socket
    server unix:/run/php/app.sock max_fails=3;
    keepalive 32;
}
upstream octane_pool {                 # 跨节点共享池: TCP
    server 10.0.12.8:8000 max_fails=3 fail_timeout=2s;
    keepalive 64;
}
# fastcgi 场景还需: fastcgi_keep_conn on; (连接复用的另一半)
```

- backlog 三处对齐：FPM listen.backlog、nginx upstream、内核 somaxconn（发行版默认 128/4096 不等）取最小值生效——只调 FPM 不调内核等于白调；listen queue 指标（107 节）盯的就是这个瓶颈。
- 双池并存的运维价值：本地 socket 池跑常规流量，TCP 中心池（Octane/Swoole）跑长连接/推送类——按流量类型分池，故障域也分开；混部时探针（105 节）分别打两个池，"半个池死了"要能看出来。
- max_fails 的语义陷阱：它是健康检查的粗筛（连续失败才摘），不是探针替代——摘除后 fail_timeout 过了又回来，半死的 upstream 会反复横跳；真正的摘除交给主动健康检查（nginx plus/网关层）或探针联动。
- 混部的故障演练：定期拔掉中心池（iptables drop）验证本地池承接与降级路径——混部架构的"半死还活着"只有演练能证明，设计评审证明不了。

## 109. 高频反模式清单与评审门禁

- 十连禁（本 KB 浓缩版）：eval、用户数据 unserialize、超全局直读进 SQL、新增 @、静态属性存请求态、DSN 外设 charset、LIKE 不转义、模板 raw 未登记、FPM 内 pcntl_fork 自建池、catch(Throwable) 静默吞——每条在对应章节都有"为什么"，清单是结论的索引。
- 门禁机械化是唯一可持续形态：清单进 CI 扫描（新增命中即红，豁免走登记文件+负责人+理由）——人擅长判断例外，不擅长记得住规则；评审人的角色从"背规则"转成"审批例外"。

```yaml
# .ci/bans.yml —— 清单是代码, 门禁是机器 (新增命中即红)
banned:
  - { match: 'eval\s*\(',          why: 'RCE 面 (65 节)' }
  - { match: '\bunserialize\s*\(', why: 'gadget 链 (68)', exempt: ['src/Serialization/SafeGate.php'] }
  - { match: 'Carbon::setTestNow', why: '全局时钟 (88)', scope: ['src'] }
  - { match: 'static \$request',   why: 'Octane 泄漏 (97)', scope: ['src'] }
```

- 评审三问（打回标准，不需要更多流程）：①这段代码在 worker 常驻 60 分钟后是什么状态？②它失败时的第二跳是什么——日志/指标能不能定位到这次失败？③它给下一个改这里的人留了什么测试？——三问答不上来就打回，评审从此有抓手。
- 季度回填机制：事故复盘的产出物必须有清单条目（110-112 节全部进了清单）——没有回填机制的清单会随团队记忆衰退而失效，"上次那个坑"的口头传承在新人面前等于零。
- 例外治理：豁免文件里每条带 Owner 与退役日期，过期自动转红——永久豁免要升格为架构决策记录（ADR），而不是在 yml 里悄悄躺三年。
- 清单的度量：季度报告看"命中次数/豁免数/新增条目"三个数——零命中说明扫描器坏了（不可能真没人写 @），豁免暴涨说明规范脱离实际，两条都是治理失效信号。

## 110. 事故复盘：Octane 状态泄漏连环案

```
时间线 (复盘骨架, 症状双线并进是关键线索):
T+0     FPM → Octane 迁移上线 (性能压测通过, 未做串味评估)
T+2h    工单: 用户 A 看到 B 的订单页 (偶发, 客服无法复现)
T+5h    DBA 告警: order 表间歇性锁等待, 慢查询与索引均无异常
T+9h    关联发现: 锁等待会话与串号用户同 worker 同源 —— 合并调查
根因    TenantContext::setCurrent() 写入静态属性; FPM 时代请求结束即死,
        Octane 下跨请求存活: A 的租户上下文 + 未回滚事务被同 worker
        的 B 请求继承 (读面=串号, 写面=锁等待, 同一泄漏的两张脸)
```

- 排查方法论固化：多个"看似无关"的告警在同一时间窗爆发时，优先怀疑共享状态——单独修任何一个症状都会复发；串号（读面）与锁等待（写面）的归并调查是本次复盘里唯一做对的事，把它写进排查手册。
- 三层修复对应三道防线：代码层静态 → scoped 绑定（93/97 节）；事务层 finally 化回滚 + RequestTerminated 钩子强制 rollback（79/97 节）；回归层双租户串味测试进 CI（50 节模板，此后每次上常驻运行时必跑）。
- 流程固化：架构门禁新增"上常驻运行时必须过串味评估清单"；Octane 评估成为上线检查表的强制项——不是"性能压测过了就能上"。
- 复盘产出物验收：修复 PR + 回归测试 + 门禁条目（109 节清单新增 static $request 禁令）+ 排查手册更新，四件齐了才算复盘完成——只交 PR 的复盘等于没做。
- 二次验证的纪律：修复上线后跑一周"串味计数"指标（按 worker 维度统计租户切换异常）——修复验证不能只靠测试绿，生产计数才是终审。
- 案例的抽象资产化：这次事故的通用形态（"FPM 隐式安全网在常驻运行时下消失"）写进架构培训——每个从 FPM 迁 Octane/Swoole 的团队都会重新发明这个事故。

## 111. 事故复盘：OPcache 容量螺旋案

```
时间线:
T+0     发布: 新增 30 个注解路由大包, opcache.memory_consumption=256M 未动
T+3m    opcache SHM 打满 → oom_restarts 计数开始跳 (整池缓存原子清空重灌)
T+5m    全量重编译 CPU 尖峰 → 请求堆积 → 连接池打满 (背压三阀未设, 100 节)
T+8m    重灌再次打满: 螺旋自持, 服务实质雪崩
关键误判: 监控只画了"SHM 使用率"——FPM 的整池回收把曲线削回低位,
        图表一片祥和; 没人盯 oom_restarts, 火警器拆了当装饰
```

- 螺旋机理：SHM 打满 → 整池缓存原子清空（不是 LRU 逐条逐出！）→ 全部请求回到无缓存态重编译 → CPU 尖峰 + 延迟 → 请求堆积 → 更快填满 SHM——正反馈回路，一旦进入不会自愈，只有扩容或降流量能破环。
- 处置次序不可乱：扩 memory_consumption（计划窗口重启生效）→ 发布改蓝绿（新 release 的编译增量不压在存量池上，103 节）→ interned strings buffer 同步核（字符串驻留超限同样触发 restarts）——先给容量再谈优化，顺序反了就是二次事故。
- 告警语义修正：oom_restarts / restarts 计数 >0 即 page——它是"螺旋已发生"的火警，不是容量预警；容量预警要看"每发布版本编译体积"的前瞻指标（CI 抓 opcache_get_status 采样）。
- 容量面板固化三线：SHM 用量、interned strings 用量、版本编译体积（107 节最小指标集扩项）——让"下个发布会不会打满"变成可预算问题：发布前算预算，发布后对账。
- 制度产出：依赖大版本升级与"包体积膨胀"事件触发容量复算（35 节公式同源）；OOM 类指标进发布后 15 分钟护航观察窗——事故的第二个教训是"发布后没人看指标"。
- 与 39 节容量公式的联动：OPcache 预算本来就是 FPM 内存总账的一部分——只算 pm.max_children 不算 SHM 的容量公式，等于只算了工资没算房租。

## 112. 事故复盘：反序列化与 PHAR 组合案

```
攻击链 (附件接口起手, 四步全绿才成事):
1. 附件预检接口把用户可控的路径参数直接传给 file_exists($path)
2. 攻击者上传含恶意元数据的 phar 伪装图 (evil.png, 实为 phar 格式),
   路径参数改为 phar://uploads/evil.png/x.txt
3. file_exists 触发 phar 流包装器 → 元数据自动 unserialize
4. 项目依赖 guzzle+monolog → phpggc 现成 gadget 链 → 落地 webshell → RCE
纵深失守点: 无 scheme 白名单 / phar 包装器未卸载 / unserialize 无 HMAC 闸
```

- 链条精髓：触发点（file_exists）人畜无害，危险在 phar:// 触发的元数据反序列化，而 gadget 链是"装好弹药"的依赖组合——三段任意一段切断都拦得住，事故是三段全绿（69 节攻击面、68 节弹药、67 节入口各自失守）。
- 修复固化对应三段：入口 scheme 白名单（禁一切 `xxx://` 形态，只放行归一化本地路径）+ web SAPI 卸载 phar 包装器（stream_wrapper_unregister，64 节工具链不受影响）+ 全仓 unserialize 收口 SafeGate（HMAC + allowed_classes）——修复不是补丁堆叠，是按链条分段落闸。
- 长期纪律固化：依赖面审计纳入季度安全审（phpggc 所列包 = 自曝弹药清单，版本对表）；CI 违禁扫描禁裸 unserialize（109 节清单条目，SafeGate 之外全部豁免审批）。
- 检测面补课：本次是"外部报告"发现而非自检——waf 对 phar:// 特征、文件上传的魔数校验（67 节三验）都能各挡一段；复盘要求"每层防御都要有对应的检测能力"，纯靠纵深运气不是工程。
- 复盘对外价值：把"我们曾有 file_exists 直接吃参数"写成通用教训分享——这类链条的知识半衰期很长，攻击者和下一个受害者都在读同样的公开资料，防御方唯一的优势是先读完并且先修完。
- 修复验证的攻防两端：用 phpggc 无害 payload 现场验证（不再触发链条）+ 回归测试固化 scheme 闸——"修好了"要有红/绿两条证据链，攻击侧一条、防御侧一条。
