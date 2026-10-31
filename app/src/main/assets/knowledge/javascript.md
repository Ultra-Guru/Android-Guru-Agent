<!-- kb: id=javascript | role=builtin_coding_javascript | title=JavaScript 专家知识库 | version=1 -->
# JavaScript 专家知识库

## 必看内容

- **事件循环心智模型**：ECMAScript 只定义微任务队列；「循环」本体属于宿主——
  浏览器 = 取一个宏任务 → **清空**微任务队列 → 渲染步（rAF→style→layout→paint）；
  Node = libuv 六阶段轮转，每个回调后排空 nextTick 再清微任务。`await` 只让到
  微任务检查点，**不释放主线程**（见 1-10 节）。
- **形状稳定性是 V8 性能的地基**：同序初始化字段、禁 `delete`、别让混合形状喂进
  同一个调用点；跨 realm 对象（iframe/Worker 回传）永远打不中同一个隐藏类（见 11-20 节）。
- **取消传播是一等公民**：公开异步 API 一律收 `{ signal }` 并贯穿 fetch/流/子任务；
  取消 ≠ 失败，AbortError 原样放行，别包进业务错误（见 28-30 节）。
- **队列必须有界**：无界缓冲 = 延迟型 OOM。能 backpressure 就 backpressure，
  不能就丢弃/采样并上报指标（见 31-35 节）。
- **错误链纪律**：包装必须带 `cause`，禁空 catch，`finally` 里别 `return`；
  全局兜底（unhandledrejection / uncaughtException）是监控面，不是业务逻辑面（见 79-86 节）。
- **2026 现代基线已落定**：Temporal（Firefox/Chrome/Safari 2025 年齐活，Node 26 默认开启）、
  `using` 资源管理（V8 13.6 / Chrome 134 / Node 24）、Iterator helpers（2024 Baseline）、
  `require(esm)`（Node 24.15 起稳定）——写新代码别再绕旧路（见 41-68 节）。
- **模块身份意识**：同一包经 CJS 与 ESM 两条解析路径会**实例化两次**——instanceof 失真、
  单例翻倍；`sideEffects` 字段一旦说谎，产物运行时直接缺功能（见 57-68 节）。
- **泄漏三高**：闭包捕获的是**作用域不是值**、分离 DOM 树、事件/总线监听器——治理靠
  AbortController 化的组件 teardown + 三快照法进 CI 门禁（见 21-27 节）。
- **性能方法论**：先 trace 后改，一次一条假设；微基准九成在骗你（JIT 预热、死代码消除、
  单形状运气）——用分配采样、deopt 日志、LoAF 归因说话（见 69-78 节）。
- **测试纪律**：等条件而不是 sleep；`forEach(async)` 与浮动 Promise 是错位事故之源；
  快照必须作为 PR 评审对象（见 103-107 节）。
- **安全底线**：不 eval、不裸拼 innerHTML、postMessage 必验 origin、子进程禁 `exec`
  拼接字符串命令（见 99-100 节）。

## 快速索引

| 想找什么 | 去哪一节 |
| --- | --- |
| 事件循环到底归谁管 | 1. 事件循环的真相：宿主循环与作业队列 |
| 微任务清空时机与渲染推迟 | 2. 微任务排空时机的实践后果 |
| 宏任务优先级、消息队列差异 | 3. 宏任务优先级与队列来源 |
| await 循环把页面卡死 | 4. 微任务饥饿：await 循环冻结页面 |
| await 了 thenable 会发生什么 | 5. await 的解包语义与 tick 成本 |
| async 函数抛错没被 try 抓住 | 6. async 函数的同步前缀与错误通道 |
| then(fn1, fn2) 为什么漏错 | 7. Promise 组合子语义精读 |
| 手动 resolve 外提模式 | 8. Promise.withResolvers 与手动 settle 纪律 |
| unhandledrejection 何时触发 | 9. unhandledrejection 的判定窗口 |
| setTimeout(0) 并不是 0ms | 10. 定时器的延迟谎言与嵌套节流 |
| 隐藏类与形状转换树 | 11. 隐藏类与转换树 |
| mono/poly/megamorphic 三态 | 12. 内联缓存三态：mono/poly/megamorphic |
| deopt 触发原因排查 | 13. 反优化触发器清单 |
| 构造器字段顺序纪律 | 14. 形状稳定性工程 |
| holey 数组与原型污染 | 15. 数组元素种类与 prototype 污染 |
| 字符串拼接到底贵不贵 | 16. 字符串内部表示与拼接成本 |
| Maglev/TurboFan/OSR 分层 | 17. 编译分层：Ignition 到 TurboFan 与 OSR |
| 热循环怎么写不挡 JIT | 18. 热函数编写纪律 |
| major GC 卡顿从哪来 | 19. 分代 GC 与停顿模型 |
| heap snapshot 怎么读 | 20. 堆测量工具箱 |
| 泄漏模式一览表 | 21. 泄漏模式全景速查 |
| detached node 怎么找 | 22. 分离 DOM 树与 retainer 追踪 |
| 闭包把大对象钉在堆上 | 23. 闭包持有堆：作用域捕获的成本 |
| WeakRef 能不能当缓存 | 24. WeakMap/WeakRef/FinalizationRegistry 纪律 |
| 万行表格内存策略 | 25. 大列表 DOM 策略与模板纪律 |
| iframe 移除后内存不降 | 26. iframe 与 Window 的双 realm 泄漏 |
| 泄漏定位标准工作流 | 27. 三快照法与堆对比工作流 |
| AbortSignal.any 组合信号 | 28. AbortController 架构与信号组合 |
| 取消怎么贯穿调用链 | 29. 取消传播的 API 设计约定 |
| 取消与失败怎么区分 | 30. throwIfAborted 与取消原因分类学 |
| push/pull 与有界队列 | 31. 背压总论：pull、push 与队列必须有界 |
| desiredSize/hwm 语义 | 32. ReadableStream 内幕：hwm 与 desiredSize |
| pipeTo 死锁排查 | 33. TransformStream 与 pipeline 组合 |
| 异步生产者消费者通道 | 34. 异步生成器与手动背压通道 |
| 并发上限怎么控制 | 35. 并发原语：信号量与 p-limit 模式 |
| all/allSettled/any 选型 | 36. Promise.all/allSettled/any 决策矩阵 |
| 指数退避与抖动 | 37. 重试架构：退避、抖动与幂等键 |
| 同步回调统一变 Promise | 38. Promise.try 与统一错误入口 |
| scheduler.yield 让出主线程 | 39. 长任务切分与 scheduler.yield |
| 监听器堆积怎么清 | 40. EventEmitter 纪律与异步监听器 |

## 1. 事件循环的真相：宿主循环与作业队列

「事件循环」不是 ECMAScript 的一部分——规范只定义微任务作业（Job）；循环本体
由宿主实现：浏览器按 HTML 规范跑「宏任务 → 清空微任务 → 渲染步」，Node 按 libuv
六阶段轮转。写出可预测的异步代码必须先分清这三层。

```javascript
// 浏览器一轮 tick 的概念骨架
const task = taskQueues.shift();   // 1) 取一个宏任务（多队列按优先级选）
run(task);                          //    本轮所有同步代码在这里执行
while (microtaskQueue.length) {     // 2) 微任务清空到零，期间入队的也要跑
  microtaskQueue.shift()();
}
renderSteps();                      // 3) rAF 回调 → style → layout → paint
```

- 微任务「全清」是硬语义：检查点期间新入队的微任务同样要执行，渲染可被无限推迟。
- `await` 只推进到微任务检查点：不产生宏任务、不触发渲染、不让出输入处理。
- Node 在每个 libuv 阶段之间、每个回调之后：先排空 `nextTick` 队列，再清微任务。
- 调试口诀：顺序怪异时先画三列（宏任务/微任务/渲染），把每个回调放回它的列。

## 2. 微任务排空时机的实践后果

- `Promise.resolve().then` 与 `queueMicrotask` 等价入队，但没有额外 Promise 包装，
  语义上更「裸」：适合纯排序用途，别拿来写业务流程（可观测性差）。
- 同步代码执行完 → 清微任务 → **才**轮到渲染；所以「一次交互内多次 then 链」
  仍然是同一个帧，DOM 只画一次——批量更新要利用这一点，而不是害怕它。
- Node 里 `process.nextTick` 队列优先于微任务队列：递归 nextTick 会**永久饿死**
  微任务与后续阶段（`RangeError: Maximum call stack` 只是幸运版本，坏情况是活锁）。
- `await` 链的深度不影响单 tick 内的顺序，但每个微任务都是独立栈帧起点（无栈增长），
  深链不会爆栈——但会推迟一切。
- 事故模式：MutationObserver 回调里改 DOM 再触发回调，形成「微任务风暴」，
  渲染永远排不上队；改用 rAF 或状态标记去抖（89 节观察器时序）。

```javascript
queueMicrotask(() => console.log('micro'));   // 排序用
Promise.resolve().then(() => console.log('same tick'));
console.log('sync');  // sync → micro → same tick（同一检查点、FIFO）
```

## 3. 宏任务优先级与队列来源

宏任务不是一个 FIFO 队列。浏览器维护多个任务源，用户输入源优先级高于网络回调，
计时器源最低；后台页面的任务还会被整体降级。

- 需要一个「尽快但让出主线程」的宏任务：`MessageChannel` 的 `port.postMessage`
  是标准技巧——不触发 setTimeout 的 4ms 嵌套钳制，也不占计时器配额。
- Node 的经典顺序题：主模块里 `setTimeout(fn,0)` 与 `setImmediate` 的先后
  **不确定**（timers 与 check 阶段的启动竞态）；但在 I/O 回调内部，
  `setImmediate` 一定先于下一轮 timers——清空逻辑依赖这一点是安全的。
- `scheduler.postTask(cb, { priority: 'user-blocking' | 'user-visible' | 'background' })`
  （Chrome 94+）显式声明优先级；没有它时所有 `setTimeout(0)` 都是最低优先级。
- 高频事件源合并：浏览器把同源 mousemove/scroll 对齐到帧（每帧至多一次）——
  在 scroll 处理里做重活仍要自己节流，事件合并只保证次数上限。

```javascript
const ch = new MessageChannel();
ch.port1.onmessage = () => doChunk();      // 无钳制的让出宏任务
function yieldToMainThread() { ch.port2.postMessage(null); }
```

## 4. 微任务饥饿：await 循环冻结页面

`while (running) await tick()` 这类纯微任务循环不会爆栈、不报错、CPU 正常——
但计时器、输入、渲染全部死亡。这是生产环境最难排查的一类「软死机」。

```javascript
// 事故形态：没有任何宏任务，永远出不了这轮 tick
while (queue.length) { await handle(queue.shift()); }   // 只走微任务
// 正确形态：每 N 项让出到宏任务边界
for (let i = 0; i < queue.length; i++) {
  await handle(queue[i]);
  if ((i & 63) === 63) await scheduler?.yield?.() ?? yieldToMainThread();
}
```

- 判定特征：Performance 面板里一个连续长任务横贯整个录制；同页
  `setInterval` 心跳完全停摆，但进程没崩、CPU 占用正常。
- `scheduler.yield()`（Chrome 129+）让出后以**原优先级**回来继续；
  `setTimeout(0)` 则降到最低优先级——用户交互期间二者响应性差距巨大。
- 心跳看门狗：每秒检查 `performance.now()` 与预期时刻的漂移，超阈值上报
  「事件循环饱和」，比用户报障早得多（Node 侧见 96 节 monitorEventLoopDelay）。
- 规则：凡是可能跑超过 50ms 的循环，把「让出点」写进 API 契约而不是靠调用方自觉。

## 5. await 的解包语义与 tick 成本

`await x` 不是「拿一个值」：x 是 thenable 就递归解包（调用 `then`，resolve 出来的
若还是 thenable 继续解包）；非 thenable 也要过一次微任务。现代 V8 对「await 原生
Promise」已消除历史上的多余 tick，但语义细节仍然咬人。

- `return await p` vs `return p`：在 `try` 块内二者**不等价**——只有前者能让
  当前函数的 catch/finally 看到结算过程；性能差异可忽略，语义优先。
- `new Promise(r => r(p))` 不会立即落定：p 被解包，额外引入约 2 个 tick；
  想透传就别包一层，直接 `return p`。
- thenable 陷阱：给对象加 `then` 方法会让 `await` 调用它——从 API 回来的
  「带 then 的鸭子」可能意外延迟或拒绝；await 边界前先固化数据。
- `await` 的个数影响后续代码相对其它微任务的**插入顺序**——多一个 await，
  就晚一轮微任务窗口；用「tick 计数」思维做顺序断言，别靠感觉。

```javascript
try {
  return await risky();   // 拒绝在这里被本帧捕获，finally 能善后
} finally {
  cleanup();
}
```

## 6. async 函数的同步前缀与错误通道

async 函数体在第一个 `await` 之前是**同步执行**的——包括抛出的异常：
它们不会同步冒泡，而是把返回的 Promise 置为 rejected。调用方「以为同步」的
try/catch 全部落空。

```javascript
async function load(id) {
  if (id == null) throw new TypeError('id required'); // 变成 rejection
  return (await api(id)).data;
}
load(null);                     // 不会当场炸；一个没人接的 rejection 漂走了
load(null).catch(e => fail(e)); // 正确姿势：永远是 Promise 消费方
```

- `new Promise(async (resolve) => {...})`：executor 内 async 抛错不会自动
  reject——异常被吞进 executor 自己的 Promise，外层 Promise 永远悬挂。
  这是反模式，禁止；要 async 逻辑就写 async 函数直接返回 Promise。
- 分层纪律：参数校验做成**同步纯函数**先行调用，让契约错误可以同步抛、同步测。
- async 函数即使不含 `await` 也返回新 Promise（一次分配）：对每秒百万次调用的
  路径，返回冻结的 resolved/rejected 常量（`Promise.resolve(x)` 可复用）。
- 测试里 `assert.throws(() => load(null))` 永远失败：改用 `assert.rejects(load(null))`。

## 7. Promise 组合子语义精读

- `p.then(onOk, onBad)`：`onBad` **不捕获 onOk 自己抛的错**——二参形态只覆盖
  p 本身的拒绝；链条继续传递。想全包就在 then 之后再 `.catch`。
- `.catch(f)` 只是 `.then(undefined, f)` 的语法糖；catch 里抛的错继续向后传——
  链上每一环都要想清楚「消化掉还是继续传」。
- `.finally(f)`：f 不接收参数；返回值被忽略，但 f 若抛错/返回 rejected Promise
  会**替换**原结算结果——finally 只做纯清理，别放可能失败的业务。
- 非函数参数会被「穿透」：`p.then(null).then(null, f)` 的 f 仍能拿到 rejection——
  这是 thenable 同化算法的基石，不是待修复的怪癖。
- `Promise.resolve(thenable)` 与 `new Promise(r => r(thenable))` 同化路径相同；
  `Promise.resolve(原生p)` 原样返回、零成本——防御性包装免费，但别当流程控制用。

```javascript
p.then(map).catch(handle)   // catch 覆盖 p 与 map 两级
 .finally(log);             // log 里的异常会顶掉前面的结果
```

## 8. Promise.withResolvers 与手动 settle 纪律

ES2024 落地 `Promise.withResolvers()`：resolve/reject 直接拿到手，
终结「let resolve 在闭包外先声明」的老仪式。它解决**可读性与作用域**，不是性能。

```javascript
const { promise, resolve, reject } = Promise.withResolvers();
worker.onmessage = (e) => e.data.ok ? resolve(e.data) : reject(new Error(e.data.msg));
try { return await promise; } finally { worker.onmessage = null; }
```

- settle-once 守卫：resolve 之后的 resolve/reject 被静默忽略——不报错。多路竞态里
  依赖「第二次调用无害」可以，但**清理逻辑**必须显式（关掉输了的那些资源）。
- 永不 settle 的 deferred = 悬挂 Promise：await 挂死、链上闭包被钉住；
  生产 API 要给 deferred 加超时兜底（`AbortSignal.timeout` + race）。
- 「resolve 外提」模式：把 settle 函数存进注册表（`Map<id, {resolve}>`），事件回调
  按 id 匹配结算——RPC-over-postMessage 的标准骨架；记得超时清理防 Map 泄漏。
- 反模式：用 deferred 模拟多次结算（流/队列语义）——Promise 是一次性的，
  多次结算就上 34 节的异步通道或 ReadableStream。

## 9. unhandledrejection 的判定窗口

「未处理」的判定时机 = 微任务检查点结束：此刻还没有任何 handler 的 rejection
触发宿主事件。判定是**基于当时**的，不基于未来。

- 晚 1ms 的 `.catch` 也算「已处理」，但事件已经发了：浏览器里仍可
  `event.preventDefault()` 撤掉控制台红字；Node 里对应 `rejectionHandled` 事件。
- Node 15+ 默认 `--unhandled-rejections=throw`：未处理 rejection **抛出并崩溃**。
  改成 `warn`/`none` 治不了病，只是把事故变成日志——先修代码。
- `p.catch(() => {})` 会让监控完全失明：需要「故意忽略」时也要记日志
  （`catch(e => log.ignore(e))`），否则线上排障少一条腿。
- `.then(onOk)` 无 catch：onOk 抛出的错是新 rejection，同样触发
  unhandledrejection——「我有 then」不等于「我处理了」。
- 聚合监控模式：全局 handler 统一上报（指纹去重、采样）+ 业务层照常抛；
  全局兜底不该成为业务错误处理的替代品。

```javascript
window.addEventListener('unhandledrejection', (e) => {
  report({ kind: 'unhandled', reason: normalize(e.reason) }); // 保持默认行为可见
});
```

## 10. 定时器的延迟谎言与嵌套节流

`setTimeout(fn, 0)` 从来不是 0ms：HTML 规定嵌套层级 ≥5 后最小 4ms；
隐藏标签页最低 1s；Chrome 的「密集节流」（隐藏超过 5 分钟的链式计时器）可到
**每分钟 1 次唤醒**。任何依赖 setTimeout 精度的设计都是错的。

- 后台节流豁免：Web Worker 里的计时器基本不受页面隐藏节流——但滥用耗电，
  Chrome 已在限制隐藏页的唤醒次数；真后台任务走 Service Worker / push。
- `setInterval` 的间隔基于「上次计划时间」重算仍会漂移；长任务后会追跑或合并——
  **别用 interval 驱动状态机**，状态机自己算下一次时刻。
- 需要单调精确间隔：用 `performance.now()` 差值自行补偿目标时刻；
  渲染对齐用 rAF 时间戳（91 节）；顺序保证用微任务/宏任务边界而非定时器。
- Node 的 `setTimeout(fn, 0)` 实际是 1ms 且要占住事件循环（持有 handle）：
  用 `timer.unref()`（不阻止进程退出）或换 `setImmediate`。
- 指纹防御说明：浏览器对 `performance.now()` 的精度做了粗化（非跨域隔离时
  ~0.1ms 级），高精度计时别指望 API 默认值。

```javascript
let next = performance.now();
function beat() {
  monitorDrift(performance.now() - next);   // 漂移量即饱和信号
  next += PERIOD;
  setTimeout(beat, Math.max(0, next - performance.now()));
}
```

## 11. 隐藏类与转换树

V8 给每个对象挂一个隐藏类（map/shape）：字段按**添加顺序**形成转换树；
两个对象走同一条添加路径才共享形状，共享形状才能命中内联缓存。

```javascript
function Point(x, y) {
  this.x = x;      // 形状 A → B（加 x）
  this.y = y;      // B → C（加 y）
}
const a = new Point(1, 2), b = new Point(3, 4); // 同形状 → 同 IC 命中
const c = { y: 1, x: 2 };                       // 不同路径 → 转换树分叉
```

- `delete obj.x` 大概率把对象踢进**字典模式**（属性表查找，无 IC）——删除语义
  用 `obj.x = undefined` 哨兵或改用 Map 表达。
- 分支不会合并：字段顺序不一致的两批对象各占转换树分支，共享 IC 无望——
  ORM/数据层把字段序列化顺序固定下来是硬要求。
- `Object.create(null)` 天生字典模式；`class` 实例/构造器对象是形状纪律最好的
  载体——库的公共数据类型优先用 class 定义。
- 诊断：`node --allow-natives-syntax` 下 `%HaveSameMap(a, b)`；`--trace-maps`
  打印转换树。生产不开启，测试里用断言守住形状契约。

## 12. 内联缓存三态：mono/poly/megamorphic

属性访问 `obj.x` 按**调用点**缓存反馈：单态（一种形状）最快；多态（≤4 种形状）
按形状分支执行；超过 4 种变**超多态**——退化成通用查找，且基本不会自动降回。

- 关键认知：IC 属于「代码位置 × 形状」而非函数——同一函数两处调用喂不同形状，
  各自独立计数；混喂数据的那一处自己烂掉。
- 经典恶化源：ORM 返回「同表不同列集」的对象、API 多版本响应、`Object.assign`
  拼接出的每条记录形状随机——在**边界处**归一化成固定 class 实例再进热路径。
- 修法一：按类型分循环（两个 for，各自单态）；修法二：适配层把异构输入一次性
  映射到统一样板（付一次代价，收益全程）。
- 动态键 `obj[key]` 走 keyed IC：key 取值集合爆炸同样超多态；真动态查找用 `Map`
  ——哈希表天生为动态键设计，不吃形状红利也就不怕失去它。
- 诊断：`--trace-ic` 看站点迁移；火焰图里超多态站点表现为 property load 运行时
  比例异常高、且看不到内联。

```javascript
// 边界归一化：热路径只认一种形状
function normalize(raw) {
  return new Item(raw.id, raw.name ?? '', raw.tags ?? EMPTY, raw.weight ?? 0);
}
```

## 13. 反优化触发器清单

TurboFan 依据「运行时假设」生成机器码，假设被打破即 deopt：回退解释器、重收集
反馈、可能再优化。反复 deopt-reopt 循环最终会被 V8 判定「不再优化」。

| 触发器 | 说明 | 纪律 |
| --- | --- | --- |
| wrong map | 入参形状与优化时不同 | 热函数入参形状固定（12 节） |
| 字段后期添加 | 优化后改形状 | 构造器一次到位，含 `= undefined` 占位 |
| delete | 字典模式 | 用哨兵值替代删除 |
| arguments 泄漏 | `arguments` 逃出函数 | 用 rest 参数 `(...args)` |
| 调试器/devtools | 断点期间全局去优化 | 性能录制时关闭面板 |
| 动态改原型 | 形状链断裂 | 优化路径别碰 prototype |
| eval/with | 整个作用域失优 | 禁用（有替代品） |
| 数组种类意外转换 | SMI→Double→Object（15 节） | 同构数据进同构数组 |

- 观察：`node --trace-opt --trace-deopt app.js` 看每个函数升降级与原因——
  `reason: wrong map` 这一行就是线索本体。
- 软反优化（lazy deopt）：被标记后当前栈帧跑完才回退——表现为「这次快、下次
  慢」的抖动，别被单次采样骗到。
- `%GetOptimizationStatus(fn)` 只做诊断脚本用，不要进生产判断逻辑。

## 14. 形状稳定性工程

把「形状」当类型系统用：模块内约定数据入口形状，等于给 JIT 一个可执行契约。

- 构造器字段顺序**永远一致**，且覆盖所有可能字段（可空给 `undefined`）；
  条件性补字段（`if (opt) this.opt = opt`）会造出两个分支形状——改成
  `this.opt = opt`（可能为 undefined），形状只有一条。
- 不要在热路径上「顺手」给外部对象挂属性——污染别人的形状等于污染别人的 IC；
  缓存/挂载用 WeakMap 旁挂，不改原对象。
- 跨 realm 对象**永不共享隐藏类**：iframe 里的数组、Worker/structuredClone
  回传的对象都是全新形状——克隆进本 realm 再进热路径。
- 对象池复用要「清空字段为固定哨兵」而不是 delete——见 11 节；池对象出池时
  全字段重赋值，形状保持不变。
- 团队纪律：数据层出口类型 + `%HaveSameMap` 抽样断言（测试期）能在 CI 里
  抓住「DTO 又改了字段顺序」这类隐性性能回归。

## 15. 数组元素种类与 prototype 污染

V8 数组有元素种类：PACKED_SMI → PACKED_DOUBLE → PACKED_ELEMENTS，各有
HOLEY 版本；转换**只升不降**（SMI→Double 不可逆回 SMI）。

```javascript
const a = [1, 2, 3];        // PACKED_SMI_ELEMENTS
a[0] = 1.5;                  // → PACKED_DOUBLE：整套存储重排为双精度堆
a[0] = {};                   // → PACKED_ELEMENTS：所有 double 装箱，内存暴涨
const b = new Array(1000);   // HOLEY：无元素种类，读洞走原型链
const c = Array(1000).fill(0); // PACKED_SMI，先定种类再干活
```

- 洞的代价：holey 数组读洞要查原型链——如果有人写了 `Array.prototype[5] = x`
  （或被老 polyfill 污染），holey 数组能**读出原型上的值**，酿成数据事故；
  禁止给数组原型加可索引属性，安全审查要查这一条。
- `new Array(n)` 不初始化；要预分配：`Array.from({ length: n }, (_, i) => f(i))`
  或 `fill()`——两者都锁住元素种类。
- 混合值入数组（`[1, 'a', {}]`）一步到 OBJECT：数值计算数组保持同构，
  拆成多个数组而不是混装。
- 洞敏感语义：`includes` 视洞为 undefined 且**不查原型**；`indexOf` 查原型；
  `forEach`/`map` 跳过洞；`for...of` 读出 undefined——三种遍历混用写洞敏感
  逻辑必错，数据带洞先 `flat()`/`filter(Boolean)` 固化。

## 16. 字符串内部表示与拼接成本

V8 字符串有五种表示：Seq（连续存储，one-byte/two-byte）、Cons（绳树，拼接 O(1)）、
Sliced（子串 O(1)）、Thin（转发）、External（外包内存）。理解它能解释九成
「字符串性能玄学」。

- 循环里 `s += chunk` 在 V8 是**可接受**的：每步只是建 Cons 节点；
  真正成本在**展平**：第一次 `charCodeAt`/`indexOf`/正则匹配时整棵绳树
  压平为连续内存（O(总长)），只发生一次。
- 「array.join 更快」的老话只在「拼接后要多次随机访问」时成立；
  一次拼接一次输出，`+=` 与 join 差距在噪声内——写可读的那个。
- 子串 `s.slice(1, -1)` 是 O(1) 的 Sliced——但**薄切会钉住大母串**：
  从 10MB 文本上切 20 字节并长期保存，母串活多久它活多久；需要持久化时
  强制展平（`''.concat(s)`）再丢弃母串引用。
- one-byte 判定：全 Latin1 的字符串用一字节存储；混入一个中文/emoji 整串
  升 two-byte（内存翻倍）——纯 ASCII 协议数据别掺用户文本进同一变量。
- `.length` 是 UTF-16 码元数：emoji/增补平面字符占 2——按「字符数」截断用
  `[...s]`（码点迭代）或 Intl.Segmenter（用户可感知字符）。

## 17. 编译分层：Ignition 到 TurboFan 与 OSR

四层流水线：Ignition（字节码解释器，带反馈向量）→ Sparkplug（基线编译，
字节码 1:1 翻译，无优化假设，Chrome 91+）→ Maglev（中层优化，快速产出带
类型反馈的机器码，Chrome 117+）→ TurboFan（全优化）。

- 升级由「中断预算」驱动，预算与字节码大小相关——**大函数几乎永不升层**。
  巨型函数不是风格问题，是性能问题：拆出热循环与热小函数。
- OSR（栈上替换）：循环体热了，在**循环入口**装替换版本继续跑；OSR 版本的
  优化程度低于正常编译——「循环里快、循环外慢」是 OSR 的签名现象。
- 「第一次调用慢、后面快」不只是缓存，还有分层升级；微基准必须先跑足量
  预热（数万次）再计时，否则测的全是解释器。
- 判级诊断：`--trace-opt` 看 `optimizing ... using TurboFan/Maglev`；
  `--max-opt=1/2/3` 限制层级做归因实验。
- 分层后很多老结论已翻转：try/catch 在现代 TurboFan 路径早已不是 deopt
  触发器；对照 13 节清单实测，别拿 2015 年的优化黑名单写 2026 年的代码。

## 18. 热函数编写纪律

- 参数类型稳定：同一个位置别忽数字忽字符串忽对象；数字最好恒 SMI 范围
  （±2^31），溢出到 double 会丢掉整数特化路径。
- 闭包少建：循环内每次 `() => ...` 都是一次分配 + 上下文；提到循环外，
  或用普通 for 循环体直接写。
- rest 参数好于 `arguments`：`(...args)` 可被逃逸分析优化掉；
  `f.apply(null, args)` 在 TurboFan 下能内联，别手动展开循环调用。
- 热路径不抛异常：Error 构造（含栈抓取）很贵，栈越深越贵——可预期失败用
  返回哨兵/Option 对象表达，异常只留给不可预期情况。
- 内联不确定：跨函数调用是否内联取决于「被调函数大小 + 反馈质量」——
  热逻辑写在**小而稳定**的函数里，别指望 JIT 神农尝百草。
- 迭代形态：超热数组遍历用 `for (let i = 0; i < a.length; i++)`；
  `for...of` 在数组上有快速路径但生成器没有——生成器每次 yield 是一次
  协程切换，粒子级循环禁用。

## 19. 分代 GC 与停顿模型

V8 分代：新生代 Scavenger（半空间复制，短命对象极便宜，两次幸存晋升老年代）；
老年代 Mark-Compact（并发标记 + 增量 + 并行清除，Orinoco 架构）。真正的
生产问题是**停顿模式**而非总吞吐。

- 小心 major GC：200MB+ 的堆上标记-压缩可造成几十到上百毫秒停顿——动画掉帧、
  请求超时集中爆发；日志特征是「整批接口同时慢」，最容易误判成网络问题。
- 分配率是杠杆：新生代空间固定，分配越快、Scavenge 越频繁（单次便宜但挤占
  帧内时间）——每帧固定产生上千次分配的代码，本身就是 bug。
- `performance.measureMemory()`（Chromium）按上下文估内存，适合 CI 回归门禁；
  Node 用 `process.memoryUsage()`（heapUsed/external 分开看）+ `v8.getHeapStatistics()`。
- Node 调堆：`--max-old-space-size=2048`；OOM（`JavaScript heap out of memory`）
  几乎总是泄漏或无界缓冲，不是堆小——先抓快照（27 节），后调参。
- 基准测试用 `--expose-gc` + 显式 `gc()` 对齐 GC 状态消除样本污染；
  生产环境禁止该 flag（掩盖真实分配行为）。

## 20. 堆测量工具箱

| 工具 | 用途 | 开销/时机 |
| --- | --- | --- |
| Heap snapshot | 全量快照、retainer 链 | 大（几十 MB~GB 级），诊断期 |
| Allocation sampling | 按采样记录分配栈 | 低，可常驻生产（抽样） |
| Allocation timeline | 时间轴上标每笔分配 | 中，复现期 |
| performance.measureMemory | 上下文级内存总量 | 低，CI 门禁/趋势 |
| 三快照法 | 泄漏定位标准流程 | 见 27 节 |

- 快照读两列：**shallow size**（对象自身）与 **retained size**（释放它能顺带
  释放的总量）——泄漏定位永远沿 retainer 链走：谁牵着谁。
- 过滤器技巧：搜 `Detached` 找分离 DOM；按构造器排序看数量增长；
  「Objects allocated between A and B」过滤两次快照间的增量。
- 采样分配抓的是「分配栈」不是「泄漏栈」——增长最快的分配点先查，
  再顺着 retainer 确认存活原因。
- 心法：测量的是**趋势**不是单点：同 workload 采样两次对比；单快照看不出
  「谁在涨」，两个快照才看得出。

## 21. 泄漏模式全景速查

| 模式 | 机理 | 修复 |
| --- | --- | --- |
| 隐式全局 | 非严格模式裸赋值进 globalThis | 严格模式/ESLint no-undef |
| 遗忘计时器 | interval 持回调闭包 | clear + AbortSignal 化（28 节） |
| 监听器堆积 | on 了没 off | `{signal}` 注册、统一 teardown |
| 闭包钉堆 | 回调捕获大作用域（23 节） | 捕小不捕大、显式置 null |
| 分离 DOM | remove 后引用仍在（22 节） | WeakMap 旁挂数据、teardown 清引用 |
| 无界缓存 | Map 只进不出 | LRU+TTL、WeakMap 化 |
| 无界队列 | 生产快于消费（31 节） | 有界 + 背压/丢弃 |
| Promise 悬挂 | deferred 永不 settle | 超时兜底（8 节） |
| iframe 双挂 | 子窗引用互持（26 节） | 置空 contentWindow、断消息线 |
| Worker 不 terminate | 线程与整堆存活 | 生命周期管理、空闲退出 |

- 判定优先级：RSS 涨 + heapUsed 涨 → JS 泄漏；RSS 涨 + heapUsed 平 →
  native/Buffer/外部内存（WASM、Buffer、图片解码）——两类排查路线完全不同。
- 长会话应用（管理后台一开一周）是泄漏高发区：会话越长的页面，越要上
  自动化堆测试（27 节）而不是等用户报障。

## 22. 分离 DOM 树与 retainer 追踪

`node.remove()` 只是从文档摘下，**JS 引用还在就是整棵子树存活**——一个闭包
抓住根节点，几百 KB 的 detached 树就常驻堆里。

```javascript
// 高发写法：映射把节点钉住
const metadata = new Map();
rows.forEach(row => metadata.set(row, { src: row.dataset.src })); // 强引用 Node
row.remove();  // 分离，但 Map 让它活着
// 修复：节点→数据 用 WeakMap（key 死则条目死）
const weakMeta = new WeakMap();
```

- 检出：Heap snapshot 里过滤 `Detached`——Detached HTMLDivElement/Text 数量随
  操作线性增长即实锤；顺着 Retainers 面板找到持有者（Map/闭包/监听器）。
- `outerHTML = ''` 赋值也会静默分离整棵子树——DOM 清理别用字符串手术。
- 单页组件的通用保险：**每个组件一个 AbortController**，监听器一律
  `addEventListener(type, fn, { signal })`，unmount 时 `controller.abort()`
  一键剪断——比手动 off 靠谱一个数量级。
- 排查钩子：console 的 `getEventListeners(el)` 查看真实监听集合，
  找「remove 了还挂着」的对象。

## 23. 闭包持有堆：作用域捕获的成本

闭包捕获的是**变量绑定（作用域槽）**，不是「你用到的那个值」。函数内只要有
内层函数引用某变量，该变量进 context 对象；闭包活得久，context 就活得久，
context 里的大对象跟着陪葬。

```javascript
function render(report /* 5MB 大对象 */) {
  button.onclick = () => {
    navigator.clipboard.writeText(report.title); // 只用了 title！
  };
  // 修复：只把需要的字段解构成局部常量再捕获
  const title = report.title;
  button.onclick = () => navigator.clipboard.writeText(title);
}
```

- V8 只把被内层函数引用的变量 context 化——但只要同作用域有一个被引用，
  context 的布局就定死；「我没用它」救不了同作用域里被引用的邻居。
- 快照特征：retainer 链显示 `context` 对象——DevTools 能展开看 context 槽位，
  直指是哪个变量钉住了谁。
- 长生命周期 + 大临时量 = 红线组合：定时器回调、模块级缓存函数、事件总线
  handler——三者捕获的大对象是泄漏榜前三。
- 解法层级：① 需要的字段**解构成局部常量**再捕获；② teardown 显式置 null；
  ③ 大对象走 WeakRef/WeakMap（24 节）；④ 结构上缩短闭包寿命（任务化）。

## 24. WeakMap/WeakRef/FinalizationRegistry 纪律

- WeakMap：key 只收对象；**value 强引用 key 是允许的**（ephemeron 语义），
  所以「对象→其元数据」是最正统用法，对象死则条目自动消失。
- WeakRef 不是缓存正确性工具：`deref()` 何时返回 undefined 由 GC 决定，
  不可测试、不可依赖；合法场景是「尽力而为的复用」（同一 target 的派生物
  共享，死了就重建）。
- FinalizationRegistry：回调在 GC 后**某个不确定时刻**以任务形式执行——
  页面关闭前可能一次都不跑。只能做「原生资源兜底清理提示」（句柄 idempotent
  close），**主清理路径**必须是显式 try/finally 或 `using`（47 节）。
- registry token 陷阱：把 target 自己当 token → token 与 holder 同生共死，
  回调永不触发；token 用独立小对象（如 Symbol）。
- WeakMap 无迭代接口（否则可观测 GC）——需要枚举就别用 Weak，
  用 LRU 缓存库。

```javascript
const registry = new FinalizationRegistry(id => releaseNative(id)); // 兜底
function acquire(res) {
  const id = nativeAcquire(res);
  registry.register(res, id, Symbol('token')); // 主路径仍在 finally 里释放
}
```

## 25. 大列表 DOM 策略与模板纪律

DOM 节点单算几百字节起步，整行带监听器、样式计算后以 KB 计——万行表格不
虚拟化，卡的不是 JS 而是 style/layout 组合爆炸。

- 虚拟化只渲染视口 ± 缓冲区：行高固定用数学定位，动态行高用 IntersectionObserver
  驱动的测量缓存；`content-visibility: auto` + `contain-intrinsic-size` 是
  原生低配虚拟化（渲染跳过但 DOM 仍在——适合几千行，不适合十万）。
- 批量插入用 `DocumentFragment`（一次 reflow）；逐个 appendChild 在大列表上
  是 O(n²) 回流。行内更新只 patch 变化的单元格。
- 事件用**委托**挂容器（88 节）：一行一监听器 = 万行万闭包，泄漏与分配双杀。
- 模板纪律：`<template>` 元素 + `cloneNode(true)` 生成行；别用 innerHTML 拼
  用户数据（XSS + 解析成本 + 全子树重建三连击），真要拼字符串必须转义或上
  Trusted Types。
- 内建分页/骨架：十万行级结合「增量渲染」——先渲染首屏、滚动补段；切片粒度
  按一帧 5ms 预算倒推（39 节）。

## 26. iframe 与 Window 的双 realm 泄漏

移除 iframe 后内存不降，多半是**双向引用未断**：父窗持有 `contentWindow`/
子窗对象/闭包，子窗脚本持有父窗 DOM 或 listener——两个 realm 互相钉住，
谁也死不了。

- 标准拆除顺序：① 父侧先移除对子 DOM/Window 的引用与监听；② 子侧（可控时）
  自清定时器与 observer；③ 把 iframe.src 置为 `about:blank` 触发子文档销毁，
  再 remove 节点；④ 父侧把 contentWindow 字段置 null。
- postMessage 端口纪律：MessageChannel 的 port 转移给 iframe 后，父侧残留
  port 引用钉住整个通道——用完 `port.close()`。
- 快照特征：`Detached` Window/HTMLIFrameElement 数量随开关次数线性增长；
  retainer 常见是父窗模块级 `Map<id, iframeWin>` 注册表——给注册表加 dispose。
- 第三方嵌入（广告/播放器）不可控子侧：唯一可靠手段是彻底移除元素并重建
  容器，并监控第三方 iframe 数量上限。
- WebGL 上下文有全局上限（同源同时约 16 个）：超限触发最旧 context 丢失——
  长会话页面必须显式 `gl.getExtension('WEBGL_lose_context').loseContext()`
  或复用同一 canvas。

## 27. 三快照法与堆对比工作流

泄漏定位标准流程（Edge/Chrome 官方口径）：三张快照两两对比，把「应该死
却没死」的对象钉在具体构造器与 retainer 上。

```text
① 快照 A（基线）
② 执行可疑操作 N 次（开/关弹窗、切换路由 ×20）
③ 强制 GC（DevTools 内存面板垃圾桶按钮）
④ 快照 B → 快照 C
⑤ Summary 视图切 Comparison（C vs A）：
   按 "# Delta" 排序，数量线性增长 = 泄漏对象
⑥ 选中对象 → Retainers 面板 → 沿链找到业务代码持有者
```

- 为什么要强制 GC + 第三张：排除「还没来得及回收」的假阳性；B 与 C 的差
  才是「确认存活」的集合。
- 进阶：Allocation timeline 复录一遍，把「分配栈」与「存活对象」对上——
  从「谁在涨」走到「谁在分配」，两步定位到代码行。
- CI 化：puppeteer/playwright 脚本跑 N 轮操作 → `performance.measureMemory()`
  或 CDP HeapProfiler 采样 → 断言增量 < 阈值，作为回归门禁；Node 侧用
  `v8.writeHeapSnapshot()` 触发 + 离线 diff 工具比对。
- 常见误读：增长的是字符串/数组而非「业务对象」——通常是日志缓存或事件
  总线历史；按 21 节表格逐项归因，不要先猜。

## 28. AbortController 架构与信号组合

AbortController/AbortSignal 是平台级取消令牌：一个 controller 对一个 signal，
signal 只能 abort 一次、不可撤销。设计 API 时先想「谁有权取消」，再定接口形状。

```javascript
// 组合信号：用户取消 OR 超时，任一触发
const userCtl = new AbortController();
const signal = AbortSignal.any([userCtl.signal, AbortSignal.timeout(8000)]);
const res = await fetch(url, { signal });   // 8s 超时 + 用户主动取消
// AbortSignal.any：Firefox 124 起、Chrome 138 起可用（2025 年跨主流）
```

- `AbortSignal.timeout(ms)`：静态便捷超时信号——Node 里返回的 timer 已 unref，
  不会拖住进程退出；但它不可被阻止，别拿它做可取消超时。
- 自定义原因：`ctl.abort(new Error('user navigated away'))`——reason 会成为
  下游 rejection 的值；下游判断取消用 `signal.aborted` + reason 身份，
  别只认 `e.name === 'AbortError'`（自定义原因会击穿这类判断）。
- 信号是事件源：`signal.addEventListener('abort', fn)` 在长生命周期 signal 上
  必须清理；反过来给 `addEventListener` 传 `{ signal }` 是监听器清理的现代式。
- 每个「可取消操作」新建 controller；跨请求复用 controller 是语义错误
  （第二次请求永远拿到已 abort 的信号）。
- 老环境兜底：手写 anySignal（监听多个 signal，先到先 abort，记得移除监听）；
  千万别共享同一 controller 去模拟 any。

## 29. 取消传播的 API 设计约定

取消不传播 = 取消失效。约定俗成的接口纪律，让整条链路像 Promise 一样可组合。

```javascript
async function syncProject(id, { signal } = {}) {
  const meta = await api(`/projects/${id}`, { signal });       // 传给 fetch
  const reader = (await fetch(meta.url, { signal })).body.getReader();
  try {
    for (;;) {
      signal?.throwIfAborted();                    // 每轮显式检查（30 节）
      const { done, value } = await reader.read();
      if (done) break;
      ingest(value);
    }
  } finally { await reader.cancel().catch(() => {}); } // 取消底层连接
}
```

- 公开异步 API 的 options 一律收 `{ signal }`；内部操作（定时器/子进程/流）
  全部接线；「部分支持」要写明，别让调用方以为可取消。
- `for await` 不会自动响应 signal：循环体内手动 `throwIfAborted()` 或用
  34 节的信号感知通道，否则取消后循环还会空转。
- 取消 ≠ 失败：catch 时先分流（AbortError/reason 原样上抛或静默返回），
  其余才走错误包装（80 节 cause 纪律）。
- 竞态收尾：取消发生时「已完成一半的副作用」要幂等——finalize 一次的
  守卫标志 + finally 清理，比撤销语义便宜得多。
- Node 侧的取消面：fetch（undici）、stream pipeline({signal})、
  child_process({signal})、`events.on(emitter, name, {signal})` 都已支持；
  老回调式 API 用包装器桥接（AbortSignal → clearTimeout/kill）。

## 30. throwIfAborted 与取消原因分类学

`signal.throwIfAborted()` 是同步检查点：已中止则抛出 reason——把它当成
循环与生成器里的「取消税」，一行保证取消可见。

- reason 默认是 `DOMException('This operation was aborted', 'AbortError')`，
  但 `abort(x)` 可以是**任意值**（甚至非 Error）——下游统一走
  `normalizeError(reason)` 防御（81 节），别假设 instanceof Error。
- 分类判别：`signal.aborted && reason === MY_CANCEL_REASON` 或自定义
  CancellationError 层级；只在最外层（UI/to-do 队列）把取消翻译成「无事发生」。
- 包装纪律：不要把 AbortError 包进业务错误再丢失身份——要加上下文时用
  `new Error('upload cancelled', { cause: reason })` 且**重新标记类别**，
  外层按 cause 链识别。
- `fetch` 的取消是 DOMException AbortError；undici/Node 同语义；
  `setTimeout` 包装取消时记得 finally 里 `clearTimeout`，否则 handle 拖住
  Node 进程退出。
- 测试：`AbortSignal.timeout(0)` + `assert.rejects(fn, { name: 'AbortError' })`
  即可全覆盖取消路径——不需要真的等网络。

```javascript
const sleep = (ms, { signal } = {}) => new Promise((res, rej) => {
  const t = setTimeout(res, ms);
  signal?.addEventListener('abort', () => { clearTimeout(t); rej(signal.reason); },
                          { once: true });
});
```

## 31. 背压总论：pull、push 与队列必须有界

生产快于消费时，push 模型会攒出无界缓冲——内存膨胀、延迟雪崩、GC 压力三连。
背压的本质：**消费者显式声明「我好了」，生产者必须等待**。

- pull 模型（消费者拉）：天然背压——生成器/迭代器/ReadableStream 都属此类；
  慢了自然不取，快了也不预取（除非你自己缓冲）。
- push 模型（生产者推）：事件、WebSocket、Observables——平台不管背压，
  你必须自己建有界队列 + 满时策略：等待（背压）、丢弃（采样/最新值）、
  报错（fail-fast）。
- 有界队列的等待实现：满时 `await 空位`，而不是 sleep 轮询——用信号量或
  「每取走一个 resolve 一个」的通道（34/35 节）。
- 指标必须有队列长度：`queue.size` 持续增长 = 背压设计失败或消费者挂了；
  这是比 CPU 更早的故障信号。
- 取舍表：日志/埋点 → 丢弃 + 计数；UI 状态 → 保留最新（coalesce）；
  财务/指令流 → 等待 + 上游超时；任何「全都要」的答案都是错的。

```javascript
class BoundedQueue {
  #q = []; #waiters = []; #capacity;
  constructor(capacity) { this.#capacity = capacity; }
  push(v) {           // 满 → 阻塞生产者（返回 Promise）
    if (this.#q.length < this.#capacity) return Promise.resolve();
    return new Promise(res => this.#waiters.push(res)).then(() => {});
  }
  // push 实际入队、shift 唤醒 waiter 的完整版见 34 节通道实现
}
```

## 32. ReadableStream 内幕：hwm 与 desiredSize

ReadableStream 的策略核心是 `desiredSize = highWaterMark − 队列长度`：
`pull()` 在 desiredSize > 0 时被调用——**不是**「队列空了才拉」。

```javascript
const src = new ReadableStream({
  async pull(controller) {
    const chunk = await readChunk();
    controller.enqueue(chunk);        // desiredSize 降到 0 → 停止预取
    if (eof) controller.close();
  },
}, { highWaterMark: 4 });             // 值流的默认 hwm=1（字节流=0）
```

- `getReader()` 后同一流不能再读；`releaseLock()` 有排队读请求时会拒绝
  ——现代语义允许带 pending releaseLock，但旧 polyfill 会抛，别依赖。
- `tee()` 是背压陷阱：两个分支共享上游，**慢分支的 chunk 会被无界缓冲**
  ——tee 后一个分支只消费头部（如 Content-Length 嗅探），必须尽快
  cancel 掉另一个或把嗅探改成 `peek + clone`。
- `cancel()` 与 `controller.close()` 不同：cancel 通知底层「别再生产了」
  （触发 underlying cancel 回调）；close 表示正常完流。
- 字节流（`type: 'byte'`）支持 BYOB（把调用方给的 buffer 填满）与零拷贝
  `transferToReadableStream`?（`response.body` 在 Chrome 可
  `transferable`）——大文件解析优先字节流 + `ReadableStream.from`？没有 from：
  用 `new Blob([stream])` 或手动封装。
- Node 桥：`Readable.toWeb(nodeStream)` / `Readable.fromWeb(webStream)`——
  fetch body 交给 Node stream 处理时的标准胶水（98 节）。

## 33. TransformStream 与 pipeline 组合

TransformStream 两侧各有一条队列：写侧满则 `writer.write()` 挂起（背压自动
传播）；transform 的 `flush()` 在 close 时收尾。死锁基本都出在「同流读写」。

- 排查口诀：`pipeTo` 挂死 → 检查两端 hwm（TransformStream 默认 hwm=0，
  `writable` 侧每次写都等读侧取走——正确的强背压，但「写后自己不读」就是死锁）。
- `flush()` 里的错误必须 `controller.error(e)` 往外送——transform 内 try/catch
  吞掉错误会让流「安静地少了尾巴」，比崩溃更危险。
- Node 的 `stream.pipeline()`（Promise 形态 `stream/promises`）：串联任意
  Readable/Transform/Writable，**任一环节出错→全部销毁→错误传到终点**；
  `pipe()` 不转发错误也不销毁对端——生产禁用裸 pipe。
- `pipeTo(dest, { signal })`：取消传播到整条管道；`stream.finished(stream)`：
  观察「完成或出错」而不消费——适合旁路监控。
- 组合出「带并发的 map」：Node 20+ `Readable.map(fn, { concurrency: 8 })`、
  `filter`/`take`/`drop` 已内置——先找平台组合子，再手写（35 节）。

```javascript
await pipeline(
  fs.createReadStream(input),
  zlib.createGzip(),
  fs.createWriteStream(output + '.gz'),
  { signal },
);  // 任一失败：三端全销毁，错误上抛
```

## 34. 异步生成器与手动背压通道

`for await (const x of gen)` 每轮 `next()` 都被 await——天然背压的拉模型。
但「向异步迭代器里推数据」没有平台原语，需要自建通道（channel/queue）。

```javascript
function makeChannel(cap = 16) {
  const q = [], wake = []; let closed = false;
  return {
    push(v) {                      // 满则等待（背压）；closed 则丢弃
      if (closed) return Promise.resolve();
      q.push(v); wake.shift()?.();
      return q.length > cap ? new Promise(r => wake.push(r)) : null;
    },
    async *[Symbol.asyncIterator]() {   // 简化：正式实现用 next()
      for (;;) {
        while (q.length) yield q.shift();
        if (closed) return;
        await new Promise(r => wake.push(r));
      }
    },
    close() { closed = true; wake.forEach(w => w()); },
  };
}
```

- 消费方 `break`/`return` 会触发 async generator 的 finally——清理逻辑
  （关连接、释放锁）写在 `try/finally` 里，与 47 节 using 语义互补。
- 通道必须支持 close 语义：生产者异常 → close + 侧信道传错（或 push 一个
  error 哨兵）；消费者提前退出 → `close()` + 唤醒所有等待者，防悬挂。
- `ReadableStream` 本身就是一个功能更全的 channel：需要 tee/取消/字节语义
  就用它；自建通道的价值在「同 tick 合并、无平台依赖」。
- 陷阱：async generator 内 `signal.throwIfAborted()` 要在每个 yield 后写
  ——生成器挂起时取消不会自动生效（29 节同因）。

## 35. 并发原语：信号量与 p-limit 模式

限制「同时在飞」的数量：信号量（计数 + 等待队列）是万金油，p-limit 把它
包装成「限流函数包装器」的惯用形态。

```javascript
function pLimit(concurrency) {
  let active = 0; const queue = [];
  const next = () => { active--; queue.shift()?.(); };
  return (fn, ...args) => new Promise((resolve, reject) => {
    const start = () => {
      active++;
      Promise.resolve().then(() => fn(...args)).then(resolve, reject).finally(next);
    };
    (active < concurrency) ? start() : queue.push(start);
  });
}
const limit = pLimit(4);
await Promise.all(urls.map(u => limit(() => fetch(u, { signal })))); // 4 在飞
```

- 等待者队列仍需有界 + 取消：`limit(fn)` 的调用方带着 signal 时，排队中
  被 abort 要能出队——生产实现（p-limit 4+/自研）必须支持 signal 参数。
- FIFO vs LIFO：LIFO 唤醒最新请求可提升缓存命中、饿死老请求——API 网关
  默认 FIFO，压测工具可用 LIFO 模拟激进调度。
- 别忘了「失败占坑」：finally 里释放（上面 `.finally(next)`），try 成功路径
  之外也要释放；漏掉 finally 是信号量泄漏的经典写法。
- 更高层原语：互斥（semaphore(1)）保护「检查-再行动」竞态；读写锁在
  单线程 JS 里只对「异步临界区」有意义（跨 await 的共享状态）。
- 平台替代：`Readable.map({ concurrency })`（98 节）、p-queue（带优先级/间隔），
  自研只在需要深度定制（配额、租期）时才值得。

## 36. Promise.all/allSettled/any 决策矩阵

| 组合子 | 成功 | 失败 | 副作用 | 选型 |
| --- | --- | --- | --- | --- |
| all | 全部 | 立即拒绝（首个） | **不取消兄弟** | 缺一不可的聚合加载 |
| allSettled | 汇总 | 不拒绝 | 不取消 | 批量操作报表（部分成功） |
| any | 首个成功 | 全败→AggregateError | 不取消 | 多 CDN/多源竞速 |
| race | 首个结算（无论正负） | 视结果 | 不取消 | 超时包装（配 30 节） |

- 高频误区：**all/race 的失败不会停掉兄弟任务**——fetch 继续烧流量、写入
  继续落盘；需要「一败俱停」就必须传 signal 并在 catch 里 abort（29 节）。
- all 失败时其余 rejection 无人认领 → unhandledrejection 噪音：生产包装
  `allWithCleanup`（每项挂 `.catch(noop)` 再 all）或改用 allSettled。
- `any` 的 `AggregateError.errors` 是**全部失败原因的数组**（按顺序）——
  日志聚合上报它，而不是只报第一条。
- race 超时包装的收尾：finally 里 `clearTimeout` + abort 输家（30 节），
  否则超时后后台请求仍在跑、缓存仍在写。
- 顺序即语义：`all` 结果顺序与输入一致（非完成序）——用结果下标回映射
  请求，别依赖完成时间。

## 37. 重试架构：退避、抖动与幂等键

重试系统三要素：**何时退**（退避曲线）、**如何错峰**（抖动）、**敢不敢重**
（幂等）。三缺一就是事故。

- 指数退避：`delay = min(base * 2^n, cap)`；必须加抖动——首选 full jitter
  `random(0, delay)`：高并发下 equal jitter 也可；无抖动 = 雪崩同步重击。
- 只重试幂等操作：GET 天然；POST 用**幂等键**（服务端按 key 去重的
  `Idempotency-Key` 头）——没有幂等保证的 POST 重试 = 重复下单。
- 尊重服务端节流：429/503 带 `Retry-After`（秒数或 HTTP 日期）时，它优先于
  任何本地曲线；日期格式要按 `Temporal.Instant.from` 解析（42 节）。
- 分类：可重试 = 429/500/502/503/504/网络抖动/超时；不可重试 = 4xx（除 429）
  ——把 401 当可重试是刷爆风控的经典路径。
- 断路器防重试风暴：滑动窗口失败率超阈值 → open（直接失败）→ 半开试探；
  重试预算（总重试占比上限）比固定次数更能保护下游。
- 与取消组合：sleep 用 30 节的可取消 sleep；用户取消时立刻放弃重试，
  别打完最后一发。

```javascript
for (let n = 0; ; n++) {
  try { return await fetch(url, { signal }); }
  catch (e) {
    if (n >= 5 || !retryable(e) || signal?.aborted) throw e;
    await sleep(jitter(base << n), { signal });
  }
}
```

## 38. Promise.try 与统一错误入口

`Promise.try(fn, ...args)`（Chrome 128+/主流新版本可用）：同步抛错自动转
rejection——把「可能是同步回调」的用户代码统一进 Promise 错误通道。

```javascript
// 中间件/插件边界：用户给的 handler 可能 throw 也可能返回 Promise
const result = await Promise.try(handler, req).then(transform).catch(wrap);
// 等价旧写法：(async () => handler(req))() —— 但 Promise.try 意图更直白
```

- 为什么不用 `new Promise(r => r(fn()))`：它同样能吞同步错，但多 1-2 个
  tick 且把值过一遍 thenable 解包（5 节）——`Promise.try` 是最小语义。
- 适用面：路由器、中间件、事件回调转发、`Array.fromAsync` 之外的
  「同步-异步混编」边界；不适合内部代码（内部本该统一 async）。
- 近亲 `Array.fromAsync(iterable)`：可吃「Promise 数组 / 异步可迭代 /
  同步可迭代（含 Promise 元素）」——注意它**没有 mapper 参数**：
  `Array.fromAsync(ids, async id => load(id))` 看似合法实则不是
  （第二参会被当 mapper 使用！ES 规范确实支持 mapFn——但 mapFn 是
  **同步应用**于每个元素**之后**的值，异步 mapper 的 Promise 会被数组
  元素化后 await）——结论：可以传 async mapper（元素本身也是 Promise 时
  先 await 元素再 apply）。保持简单：先 fromAsync 再 map，或直接 for await。
- 老运行时垫片：`Promise.try` 是三行 polyfill（try/catch + reject），
  值得内建到工具库。

## 39. 长任务切分与 scheduler.yield

长任务（>50ms）阻塞输入 → INP 崩坏。切分的第一原则：**让出要过宏任务边界**，
`await null` 只是微任务，切了个寂寞（4 节）。

```javascript
async function processAll(items) {
  const deadline = 12;                     // 每片预算 ms（帧预算的一半）
  for (let i = 0; i < items.length; ) {
    const t0 = performance.now();
    while (i < items.length && performance.now() - t0 < deadline) {
      handle(items[i++]);
    }
    await scheduler?.yield?.() ?? yieldToMainThread(); // Chrome 129+ / 兜底
  }
}
```

- `scheduler.yield()` 让出后**继承当前优先级**回来；`setTimeout(0)` 回来时
  已是最低优先级——交互期间用户输入会插队到你的续段前面，这是想要的。
- `scheduler.postTask(fn, { priority })` 是完整版：显式声明
  user-blocking / user-visible / background；长后台批任务用 background，
  让出后不再与输入抢跑。
- 兜底阶梯：`scheduler.yield` → `MessageChannel`（3 节）→ `setTimeout(0)`；
  三者都是宏任务边界，差别只在回程优先级。
- `requestIdleCallback` 是「顺带」而非承诺：持续负载下可能永不执行——
  预加载、日志冲刷可以用，关键路径禁用。
- React 思想借鉴：把可中断性与「已提交/未提交」状态划分开——切分前先想
  清楚中间状态是否可观察（否则并发改写共享中间态）。

## 40. EventEmitter 纪律与异步监听器

Node 事件发射器：emit 是**同步**的、**不等待**异步监听器——async 监听器里
的 rejection 变成无人认领的漂移 rejection。

```javascript
const bus = new EventEmitter({ captureRejections: true });
bus.on('job', async (job) => { /* async 监听器 */ });
// captureRejections:true → 监听器 rejection 转成 'error' 事件，不再漂移
// 更现代的姿势：async iterator 接口（自动清理 + 可中断）
for await (const [job] of events.on(bus, 'job', { signal })) {
  await handle(job);
}
```

- `events.on(emitter, name, { signal })`：返回 async generator——循环体
  自然逐事件执行（串行）；要并发就配合 35 节信号量手动拉取。
- maxListeners(10) 告警是**泄漏探测器**不是功能：看到告警先查「谁没 off」，
  别无脑 `setMaxListeners(0)` 灭火。
- emit 期间的 on/off：Node 会克隆监听器数组快照——嵌套 emit 安全但顺序
  有微妙差异；once 在调用前已移除自身，重入安全。
- `error` 事件特殊：无监听器则**抛出崩溃进程**——任何可能 emit('error')
  的对象（流、socket、子进程）必须挂 error 监听，哪怕只是记日志。
- 测试断言监听器泄漏：`emitter.listenerCount(name)` 在 teardown 后应为
  基线值——写进测试的 after 钩子。

## 快速索引 · 续（41-80 节）

| 想找什么 | 去哪一节 |
| --- | --- |
| 时间类型怎么选（Instant/ZDT/Plain） | 41. Temporal 类型学：Instant、ZonedDateTime 与 Plain 族 |
| RFC 9557 字符串与 DST 算术 | 42. Temporal 实战：解析、算术与显示 |
| 旧 Date 代码怎么换 | 43. Date 到 Temporal 的迁移策略 |
| 惰性管线代替中间数组 | 44. Iterator helpers 惰性流水线 |
| 迭代器二次消费为空 | 45. Iterator helpers 的消费陷阱 |
| break 时清理逻辑怎么触发 | 46. 可迭代协议 return() 与 Iterator.concat |
| using 与 RAII | 47. using 声明与资源生命周期语义 |
| 清理时的错误去哪了 | 48. using 的异常语义与 SuppressedError |
| DisposableStack/adopt/defer | 49. Symbol.dispose 封装与 DisposableStack |
| Map 原子 upsert | 50. Map Upsert 家族：getOrInsert |
| structuredClone 能拷什么 | 51. structuredClone 的语义边界 |
| JSON 拷贝 vs structuredClone | 52. 深拷贝决策矩阵与不可变替代 |
| Proxy 合法用途与撤销 | 53. Proxy 工程与元编程应用 |
| Proxy 包 class 实例炸了 | 54. Proxy 事故模式与私有字段 |
| getter 的 this 指错对象 | 55. Reflect 转发纪律与 receiver 陷阱 |
| 沙箱隔离怎么选型 | 56. ShadowRealm 现状与隔离替代方案 |
| ESM/CJS 本质差异 | 57. ESM 与 CJS 的语义鸿沟 |
| require 一个 ESM 包行不行 | 58. require(esm) 稳定后的互操作矩阵 |
| import 出来多个 .default | 59. default/named 互操作陷阱 |
| with { type: 'json' } | 60. import attributes 与 JSON modules |
| exports 子路径解析事故 | 61. exports 字段与条件导出解析 |
| require 循环拿到半初始化 | 62. 循环依赖在两套系统下的行为 |
| 顶层 await 卡住启动 | 63. 顶层 await 的图阻塞语义 |
| tree-shaking 删过头/删不动 | 64. tree-shaking 与副作用边界 |
| 同一实例被加载两次 | 65. 打包器 hoisting、chunk 与双重执行 |
| 双包发布的单例陷阱 | 66. 双包发布纪律与单例陷阱 |
| import() 部署后 404 | 67. 动态 import 与 import.meta |
| 写 loader 钩子/编译缓存 | 68. module.registerHooks 与编译缓存 |
| INP 与 JS 预算 | 69. 性能预算与 INP 时代的度量 |
| 热循环减分配 | 70. 热路径分配纪律 |
| 字节序与二进制解析 | 71. TypedArray 与 DataView 的字节纪律 |
| ArrayBuffer transfer/detach | 72. ResizableArrayBuffer 与 detach 语义 |
| Worker 间共享内存 | 73. Atomics.waitAsync 与跨线程协作 |
| 交互卡顿归因到脚本 | 74. 主线程长任务外科手术 |
| 分块解码 UTF-8 | 75. 字符串与正则热路径 |
| AoS/SoA 与装箱 | 76. 缓存友好的数据布局 |
| 何时值得上 WASM | 77. WebAssembly 边界的成本模型 |
| 微基准为什么不可信 | 78. 剖析方法论与微基准谎言 |
| 错误该怎么分类 | 79. 错误分类学与构造纪律 |
| cause 链怎么包 | 80. error.cause 与错误链封装 |

## 41. Temporal 类型学：Instant、ZonedDateTime 与 Plain 族

Temporal（Firefox 139 / Chrome 139 / Safari 26 于 2025 年落地，Node 26 默认开启）
用类型系统取代 Date 的「一个类打天下」：先问三个问题——绝对时刻？墙上时间？
带时区的完整坐标？

| 类型 | 语义 | 无歧义? | 用途 |
| --- | --- | --- | --- |
| Instant | UTC 纳秒时间戳 | ✓ | 存储、排序、跨系统交换 |
| ZonedDateTime | Instant + 时区 + 日历 | ✓ | 用户可见的本地化事件 |
| PlainDate/PlainDateTime/PlainTime | 无时区墙上时间 | ✗（歧义） | 生日、营业时间、日历 |
| PlainYearMonth/MonthDay | 月/日历日 | ✗ | 信用卡有效期、周年 |
| Duration | 名义时长（年/月/日...） | ✗（需锚点换算） | 日历差值表达 |

- `Duration` 不能换算成毫秒：`Temporal.Duration.from('P1M').total('seconds')`
  会抛错——月份天数不定，必须给 `relativeTo` 锚点；这是设计约束不是缺陷。
- 持久化纪律：**存 Instant（epoch ns 或 RFC 9557）**，展示层才转 ZonedDateTime；
  把墙上时间当时间戳存是 DST 事故之源（43 节）。
- 时区只用 IANA 名（`Asia/Shanghai`）；固定偏移（`+08:00`）只表示「那一刻的
  偏移」，不承载规则——未来时间必须带 IANA 时区才能正确跨 DST。
- 老环境用 `@js-temporal/polyfill`，接口一致；检测 `'Temporal' in globalThis`。

## 42. Temporal 实战：解析、算术与显示

```javascript
const zdt = Temporal.ZonedDateTime.from('2026-10-21T09:00+08:00[Asia/Shanghai]');
zdt.toInstant().epochMilliseconds;          // → Date 互操作（唯一桥）
zdt.add({ days: 1 });                        // 日历加法：跨 DST 保持 9 点墙上时间
zdt.toPlainDateTime().until(other, { largestUnit: 'day' });
Temporal.Instant.fromRFC2822?.(s) /* 不存在：from 只吃 RFC 9557/ISO */
// 宽松解析真实脏数据：
Temporal.PlainDate.from('2026/10/21', { overflow: 'constrain' }); // 非标分隔符仍抛
```

- 解析容错用正则**归一化**成 RFC 9557 再 `from`；`from` 的 `overflow` 只管
  字段越界（如 31 日），不管格式——别指望它当万能解析器。
- `ZonedDateTime.from` 的 `offset` 选项（`'reject'` 默认/`'use'`/`'prefer'`/
  `'ignore'`）：真实世界历史数据偏移与规则不符时，`'ignore'` 按时区规则纠正。
- 算术双轨制：`add`（日历语义，跨 DST 保持墙上时间）vs 毫秒/秒加减
  （物理语义，恒定时长）——「明天同一时刻」与「24 小时后」在 DST 切换日
  **差一小时**，选错即事故。
- 取整：`round({ smallestUnit: 'minute', roundingMode: 'floor' })`——计费/分桶
  显式选 roundingMode（默认 halfExpand 四舍五入，计费常要 floor）。
- 显示交给 Intl：`new Intl.DateTimeFormat('zh-CN', { dateStyle: 'full', timeStyle:
  'medium' }).format(zdt)`——**别手拼月份名**，locale 数据在 Intl 里。

## 43. Date 到 Temporal 的迁移策略

绞杀者模式：新代码 Temporal-only，旧代码按「痛点优先级」替换——先替换
**算术**（DST bug 高发），后替换展示（低风险）。

- 死刑清单（迁移时一刀切禁止）：`new Date(string)` 非 ISO 解析（引擎间行为
  不一致，Safari 老版本直接 Invalid Date）；`+86400000` 式日期算术；
  `getMonth()` 0 起始；`getYear()`；`Date.parse`。
- 边界缝合：与老 Date 库（图表库、数据库驱动）交换用
  `Temporal.Instant.fromEpochMilliseconds(d.getTime())` 与
  `new Date(instant.epochMilliseconds)`——这两个桥是唯一合法通道。
- 序列化契约：API 层一律 RFC 9557（含偏移与时区括号）或 epoch 纳秒；
  墙上时间字段（生日/营业时间）显式标注 plain 并约定日历（ISO 8601 默认）。
- Lint 卡位：ESLint `no-restricted-properties` 封禁 Date 的算术方法；
  代码评审清单加「新增时间代码是否用 Temporal」（112 节）。
- moment 退役纪律：moment 已进维护模式；大库迁移用 luxon（API 形态接近
  Temporal）作中转，比直接一步到位的回归面小。

## 44. Iterator helpers 惰性流水线

Iterator helpers（Chrome 122 / Safari 18 / Firefox 131，2024 年 10 月 Baseline）
把 map/filter/take/drop/flatMap 变成**迭代器上的惰性组合**——不再为每步
生产中间数组。

```javascript
const big = fs.readSparseNumbers();   // 假想的无穷/超长序列
const top10 = Iterator.from(big)
  .filter(n => n % 2 === 0)
  .map(n => n * 2)
  .take(10)                 // 提前终止：上游只被消费到够 10 个
  .toArray();
// 等价数组链：[...big].filter().map().slice(0,10) —— 三个全长数组
```

- 提前终止会调用上游的 `return()`（46 节）——生成器里的 try/finally 清理
  能被正确触发，这是比手写 for 循环更可靠的资源安全。
- 帮助器对象既是 Iterator 又是 Iterable（`[Symbol.iterator]()` 返回自身）——
  可直接 `for...of`，但一次性的（45 节）。
- `take(n)`/`drop(n)` 对**无限迭代器**合法：生成器驱动的流式计算从此不需要
  「先收集再切片」。
- 终结方法：`reduce/toArray/forEach/some/every/find`——立即求值；
  `Iterator.from(非迭代器)` 抛 TypeError（鸭子判定走 next 方法检查）。
- 何时不如手写循环：三步以内、输入有限且小——管线每层有 next 间接层；
  长管线 + 大输入 + 早退出才是它的主场（78 节先量后换）。

## 45. Iterator helpers 的消费陷阱

- **一次性**：帮助器链消费完即耗尽，二次 `for...of` 得不到任何元素——
  需要多次消费就保留**源头可迭代对象**（数组/Map），每次 `Iterator.from(源)`
  重新建管线；迭代器≠可迭代对象，`[Symbol.iterator]` 返回自身的一次性实现
  只对帮助器对象合法。
- `Iterator.from(map)` 拿的是 map 的**那一个**迭代器；要新迭代器必须再调
  `map[Symbol.iterator]()`——把「可迭代对象」与「迭代器实例」当两个概念管。
- 副作用顺序交错：`.map(f).filter(g)` 每取一个元素按序过 f、g 再取下一个——
  日志顺序与数组链（先全 map 后全 filter）**不同**，调试时别按老直觉读日志。
- spread 会终结：`[...helper]`、`Array.from(helper)`、`{...}`（字符串/Map
  迭代器会产出索引键）都是终点操作；`JSON.stringify(iterator)` 得到
  `"Map Iterator"`——忘了终结是排查「结果是个怪字符串」的线索。
- 惰性管线里的异常：helper 不捕获映射函数抛错——错误在取到该元素的
  `next()` 时抛出，位置=消费处；对生成器用 `.return()`/`.throw()` 可把
  错误注入 yield 点（46 节）。

## 46. 可迭代协议 return() 与 Iterator.concat

协议三件套：`next()` 必须；`return(value)` 可选（提前终止时被 for-of 的
break/throw 调用）；`throw(e)` 生成器专用（在挂起点注入异常）。

```javascript
function* lines(fd) {
  try { for (const buf of fd.readLines()) yield buf.toString(); }
  finally { fd.close(); }        // take(3) 提前退出 → return() → 这里执行
}
// for...of 的 break、帮助器的 take/drop 截断、解构剩余——都会触发 return()
```

- 契约要求：可迭代对象的 `[Symbol.iterator]()` **每次调用都返回独立迭代器**——
  返回缓存的单例迭代器（或自身）会让「遍历两次」第二次为空；
  只有「迭代器自身作为可迭代对象」的场合（44 节帮助器）才允许返回 this。
- `Iterator.concat(...sources)`（V8 14.6 / Chrome 146 / Node 26）：惰性串联
  多个可迭代/迭代器，早退出时对**当前源**触发 return，未开始的源不触碰——
  替代手写 `yield*` 串联生成器的场景。
- 生成器是协程：`gen.throw(err)` 在挂起点抛出、可被生成器内 try/catch 接住、
  finally 兜底清理——把生成器当「带清理的暂停计算」设计（47 节 using 的前身）。
- 自定义迭代器最常漏 `return()`：包着句柄（游标/文件/网络流）的迭代器
  不实现 return → 消费方 break 后句柄泄漏；提供它，让协议替你善后。

## 47. using 声明与资源生命周期语义

显式资源管理（`using`/`await using`，V8 13.6 / Chrome 134 / Node 24 落地；
TS 5.2+ 可降级编译）：块级 RAII——**离开作用域自动 dispose，逆序执行**。

```javascript
async function parse(path) {
  await using handle = await fs.open(path, 'r');  // Symbol.asyncDispose
  using guard = new MutexGuard(lock);              // Symbol.dispose（同步）
  return JSON.parse(await handle.readFile('utf8'));
}   // 出块：guard 先 dispose（LIFO），handle 后 dispose（await 它）
```

- `using`（同步 dispose）与 `await using`（异步 dispose，要求 async 上下文）
  是两个声明；混排时依然全 LIFO——同步的立即执行、异步的排队执行。
- null/undefined 合法（跳过 dispose）——「可能拿不到资源」的分支不需要
  特判；非 nullish 且无 `[Symbol.dispose]` 的值直接 TypeError。
- 与 try/finally 的等价性：语义上就是编译器替你写 finally；但**finally 的
  三个坑**（84 节：finally 抛错顶掉原异常、return 覆盖、无法拿原错误）
  在这里被协议化解决（48 节 SuppressedError）。
- `await using` 里的 dispose 自己会挂起：给关键资源的 dispose 内部加超时
  （连接池关闭、WASM 释放都可能卡），资源封装层负责，不是使用方。
- Node 24 起 `fs.FileHandle` 等核心资源陆续实现 `[Symbol.asyncDispose]`——
  `await using fh = fs.open(...)` 直接合法，告别手写 close。

## 48. using 的异常语义与 SuppressedError

处置链上的异常有明确归宿：**主体抛错时，dispose 的错不丢**——两者合并为
`SuppressedError`（新错误类型），`.error` 是主异常、`.suppressed` 是被压掉的
dispose 异常。

```javascript
try {
  using c = connection();   // 假设 dispose 时网络已断 → dispose 抛错
  risky();                  // 主体也抛错
} catch (e) {
  if (e instanceof SuppressedError) {   // 两个都要看，主错误优先处理
    report(e.error, { suppressed: e.suppressed });
    throw e.error;
  }
  throw e;
}
```

- 多个 dispose 连环失败：形成 SuppressedError 链（suppressed 里再嵌
  SuppressedError）——上报序列化要递归展开（81 节 cause 同款处理）。
- 主体没抛错、只有 dispose 抛错：dispose 的错误**直接抛出**（无包装）——
  调用方视角「函数正常结束但抛了清理错」是正确语义，别把它吞成日志。
- 对照 try/finally：finally 的抛错会**顶掉**主异常（原错误彻底丢失）——
  这正是 using 存在的理由；存量代码审计见 finally 反模式（84 节）。
- TS 降级形态：`__addDisposableResource` + 外层 try/catch 生成等价结构，
  SuppressedError 由运行时 `__disposeResources` 合成——行为一致，
  但栈多了层，error message 前缀有差别，日志规则别依赖裸栈顶。

## 49. Symbol.dispose 封装与 DisposableStack

资源封装三件套：`[Symbol.dispose]`（同步）、`[Symbol.asyncDispose]`（异步）、
`DisposableStack`/`AsyncDisposableStack`（动态注册，解「资源数在编译期未知」）。

```javascript
using stack = new DisposableStack();
const db = stack.adopt(openDb(), db => db.close());   // adopt：外部资源+清理函数
const sub = stack.use(subscription());                // use：已实现 dispose 的对象
stack.defer(() => console.log('逆序执行'));            // defer：纯清理动作
stack.move();  // 转移所有权（交给调用方 using），本栈不再负责
```

- `adopt(res, fn)` 适配不支持 dispose 的老 API——这是渐进迁移的主力：
  不改第三方类，包一层就进 using 世界。
- `move()` 是所有权转移：库函数内部建栈、全成后移交给返回值栈——
  「失败自动全清、成功移交调用方」一步表达。
- 封装纪律：dispose 必须**幂等**（二次调用无害）——异常路径可能「主体
  手动关了一次 + using 又关一次」；内部加 closed 标志。
- 一次性互斥的惯用形态：`using g = lock.guard()`，guard 的 dispose 释放锁——
  早退/抛错路径的解锁不再靠人肉 finally。
- 陷阱：`using stack = new AsyncDisposableStack()` 写成同步 `using` 会
  TypeError（缺 Symbol.dispose）——async 栈必须配 `await using`。

## 50. Map Upsert 家族：getOrInsert

Upsert 提案（V8 14.6 / Chrome 146 / Node 26）落地 `Map.prototype.getOrInsert(key, val)`
与 `getOrInsertComputed(key, fn)`（WeakMap 同款四件）。

```javascript
// 老写法：双查找 + 竞态窗口（fn 执行期间状态可变）
if (!cache.has(k)) cache.set(k, expensive(k));
// 新写法：单次哈希，fn 同步执行期间天然原子（单线程无插入）
const v = cache.getOrInsertComputed(k, expensive);
```

- `getOrInsertComputed` 的 fn **同步**执行后才插入——fn 内部再次 getOrInsert
  **同一个 key** 会无限递归（值尚未写入）——递归展开/图遍历要显式
  哨兵（先插占位再算）。
- WeakMap 版本是「规范化注册表」的正解：对象→规范化实例的映射
  （intern 表），无 key 强引用，GC 友好（24 节同型场景）。
- 语义边界：只存在于 Map/WeakMap；Object 没有（动态字符串键的 upsert 仍需
  手写或换 Map）；默认值参数在 **miss 时才求值？**——不：`getOrInsert(k, v)`
  的 v 是先求值再传入（值形态），惰性需求请用 Computed 版本。
- 缓存穿透注意：fn 返回 undefined 也会**插入 undefined**——「计算失败」
  与「值为空」要区分时，别用 upsert 直接接业务函数，先包装成哨兵。

## 51. structuredClone 的语义边界

structuredClone 是**语言级**深拷贝（MessageChannel/postMessage 同款算法），
但它有明确的「能拷清单」——越界即抛或静默变形。

| 能拷 | 不能拷/变形 |
| --- | --- |
| 普通对象/数组/Map/Set/Date/RegExp | 函数、DOM 节点、Symbol → DataCloneError 抛 |
| TypedArray/ArrayBuffer（默认复制） | prototype 不保留：class 实例 → 纯 Object |
| Error（name/message/cause） | Error 的 **stack 丢失**、自定义属性丢失 |
| 循环引用、嵌套 any 层 | getter **不被调用**，accessor 属性直接消失 |
| Boolean/String/Number 装箱对象 | WeakMap/WeakSet → 抛 |
| SharedArrayBuffer（**按引用共享**） | Proxy：拷的是 target 的可枚举自有属性 |

- transfer 语义：`structuredClone(v, { transfer: [buffer] })` 后原 buffer
  **被夺走**（byteLength=0）——零拷贝移动大块数据的合法通道（72 节 detach）。
- class 实例被拍平是**头号事故**：跨 postMessage 后 `instanceof MyCls` 为
  false、私有字段全丢——跨边界传输用 DTO（plain 数据），两端各自重建。
- 正则的 lastIndex 会重置为 0；Date 精度完整；-0 保留（=== 判断时注意）。

## 52. 深拷贝决策矩阵与不可变替代

| 方案 | 循环 | 类型保真 | 原型 | 性能 | 适用 |
| --- | --- | --- | --- | --- | --- |
| JSON 往返 | ✗ 抛 | ✗（Date→串/Map→{}） | ✗ | 慢 | 纯 JSON 数据、跨语言边界 |
| structuredClone | ✓ | 大部分内置 | ✗ | 中 | 运行时对象、Worker 传输 |
| 手写递归 | 自定 | 自定 | ✓ 可 | 快/维 | 领域类型（配 51 节 DTO 重建） |
| 结构共享/Immer | ✓（draft） | ✓ | ✓ | 最快 | 状态更新的「伪可变」写法 |

- 90% 的「深拷贝需求」其实是**状态更新**：Immer `produce` 的写时复制
  （copy-on-write）让「直接改」的代码产出新引用——性能与语义双赢。
- 渲染路径里深拷贝是经典事故：每次输入把 100KB 状态 clone 一遍 →
  每键一次 major GC 级分配——改结构共享后帧预算立刻回血。
- 「克隆防护」（防止他人修改）优先用 `Object.freeze`（浅）+ 递归冻结工具，
  或干脆约定不可变；冻结对形状稳定还有 IC 副收益（但冻结对象赋值在非严格
  模式静默失败，严格模式抛错——统一严格模式）。
- 老的 MessageChannel 拷贝 hack 已无存在理由（structuredClone 就是那个算法
  的公开版）；`cloneInto`/FF 专有 API 也不需要了。

## 53. Proxy 工程与元编程应用

Proxy 的正当工程位：**边界处**的适配与防护——校验、默认值、负索引、
可撤销视图；不是业务逻辑容器。

```javascript
function portView(arr) {
  const { proxy, revoke } = Proxy.revocable(arr, {
    get(t, k, r) {
      if (k === 'destroy') return revoke;
      const i = (typeof k === 'string' && /^-?\d+$/.test(k)) ? Number(k) : NaN;
      return Number.isInteger(i) && i < 0 ? t[t.length + i]
           : Reflect.get(t, k, r);     // 其余一律 Reflect 转发（55 节）
    },
  });
  return proxy;
}
```

- `Proxy.revocable`：revoke 后任何操作抛 TypeError——「会过期的视图」：
  插件系统卸载、会话结束后的 API 面，比手动加 dead 标志可靠。
- 13 个 trap 各有不变量（54 节），规划时先读不变量表再选 trap 组合；
  最小 trap 集是美德：只拦 get 的代理别顺手拦 set。
- Vue3/响应式库用 get trap + WeakMap 依赖表实现细粒度订阅——可借鉴的
  架构：**代理只做拦截，状态外置**（WeakMap），代理对象保持形状不变。
- 虚拟属性（按需计算）注意：`ownKeys`/`has` 不拦会让 `k in proxy` 与
  `Object.keys` 看不到虚拟键——要么补全 trap，要么别虚拟。

## 54. Proxy 事故模式与私有字段

- **私有字段死刑**：`#x` 的访问在编译期绑定到**类体**，代理的 get trap 永远
  拿不到——被代理的类实例一调用带 `#x` 的方法就 `TypeError: Cannot read
  private member`。修复：方法内部用 `Object.getPrototypeOf` 前的目标引用、
  把私有状态外置 WeakMap（Vue3 早期踩过）、或不代理这类实例。
- 不变量违例：get trap 对「不可配置、不可写」的数据属性返回不同值 →
  TypeError；`ownKeys` 必须包含全部不可配置键、不可重复——这些错误**延迟到
  操作发生时**抛，离根源十万八千里，命名问题极难排查。
- `deleteProperty`/`defineProperty` 拦截后 `Object.defineProperty` 与赋值
  行为分叉；`has` 拦截影响 `in` 但不影响 `Object.prototype.hasOwnProperty`——
  「同一件事两个结果」是代理代码评审的固定考点。
- 跨 realm：代理的 target 与 handler 在同 realm 检查不变量，`instanceof`
  可被 getPrototypeOf trap 骗，但 `Object.prototype.toString` 走
  `Symbol.toStringTag`——深度防御别只看一层。
- 性能：代理操作无法吃上普通 IC 路径（每次过 C++ 运行时/慢速桩），
  热循环里慢一个数量级——代理止步于边界，热路径前先「落实」成普通对象。

## 55. Reflect 转发纪律与 receiver 陷阱

Proxy trap 的默认行为**必须**用 Reflect 转发——手写 `return target[k]` 在
继承访问器上会烧掉 `this` 语义。

```javascript
const base = { get x() { return this.#secret; } };  // 访问器依赖 receiver
new Proxy(obj, {
  get(target, k, receiver) {
    return Reflect.get(target, k, receiver);   // ✓ getter 的 this = receiver
    // return target[k];                        // ✗ this 变成 target：
    //   子类/代理链上的 setter 分派全部被劫持，语义静默改变
  },
});
```

- receiver 是「这次读取的受体」——`super.x`、代理链、`Object.create` 的
  原型访问器都靠它对齐 `this`；转发链上丢 receiver = 埋语义雷。
- `Reflect.apply(fn, thisArg, args)`：用数组参数安全调用的标准式（替代
  `Function.apply` 且不因 fn 是 bound/proxy 而分叉）；`Reflect.construct`
  携带 newTarget——实现「类式继承」工具（自定义 extend/mixin）时的唯一
  正确通道。
- `Reflect.ownKeys` = 自有字符串键 + 自有 Symbol 键（按规整顺序）——
  迭代协议/深拷贝工具要「真·全部」就用它，`for...in` 会带继承且不保序。
- 元编程纪律：Reflect 与 Proxy 是**成对 API**——反射读、代理拦，二者共享
  同一套操作名（get/set/has/deleteProperty/ownKeys...）一一对应，
  记操作名表比记方法表更抗遗忘。

## 56. ShadowRealm 现状与隔离替代方案

ShadowRealm（TC39 Stage 3，截至 2026 年仍无引擎默认开启）：独立全局与
内建对象的同步隔离域——跨边界只有**wrapped function**（可调用包装，
参数/返回值按类 structuredClone 规则复制），无共享对象与共享原型。

- 必然后果：跨 realm `instanceof` 恒 false（两边是不同的 Object.prototype）；
  错误对象跨界即被复制降级（51 节规则）——错误通道要自己设计协议。
- 定位是**完整性隔离**（插件互不污染、全局不被覆盖），**不是安全边界**：
  恶意代码隔离官方答案是进程级：Worker/iframe（配 CSP、sandbox 属性）或
  独立容器。把 ShadowRealm 当沙箱写进安全方案 = 评审不过。
- 今天可用的替代矩阵：① Worker + postMessage/Comlink（真隔离，异步边界，
  clone 成本 93 节）；② sandboxed iframe + postMessage（同源策略 + CSP，
  浏览器级隔离）；③ QuickJS-wasm 沙箱（不可信代码、可限指令数/内存）；
  ④ Node `vm` 模块**不是**安全边界（官方文档原话，逃逸手法成熟）。
- 设计兼容 ShadowRealm 的库：不依赖可变全局单例、不 monkey-patch 内建、
  跨界传纯数据——这些约束同时是可测试性约束，值得无条件遵守。

## 57. ESM 与 CJS 的语义鸿沟

| 维度 | CJS | ESM |
| --- | --- | --- |
| 结构 | 运行时加载，值拷贝 | 静态链接，**活绑定**（live binding） |
| 缓存 | `require.cache` 可删可改 | 模块记录缓存，不可外部篡改 |
| 循环 | 半初始化 exports（undefined 兜底） | TDZ ReferenceError / 函数提升可用 |
| 严格模式 | 可选 | 永远严格 |
| this | module.exports | undefined |
| 顶层 await | ✗ | ✓（63 节图阻塞） |
| 入口判定 | main 字段/目录 index.js | exports 字段 + 显式扩展名 |

- ESM 的 import 是**绑定不是拷贝**：`export let count` 变化后导入方看到新值；
  导入方不能改写绑定（只读视图）——「模块单例可变状态」的读写协议和 CJS
  完全不同。
- Node ESM 的解析纪律：相对导入**必须带扩展名**（`./lib.js`），目录没有
  index.js 兜底——从 CJS 迁移的第一波报错几乎都是这个；想省后缀走
  bundler/自定义 loader（68 节）。
- 模块代码只执行一次；「重新加载」只有动态 `import(url + '?v=2')`（查询串
  使 URL 不同）或 Worker 里全新上下文——别模拟 require.cache 删除老技巧。
- 严格模式的连带影响：隐式全局赋值从「能用」变 ReferenceError——
  这类老代码迁移时暴露的不是噪音而是真 bug。

## 58. require(esm) 稳定后的互操作矩阵

require(esm)：从实验（Node 22 `--experimental-require-module`）到
默认开启（22.12/23），**Node 24.15（LTS）/25.4 起标记稳定**——CJS 代码
require ESM 包返回其模块命名空间对象。

```javascript
// CJS 文件里：
const ns = require('esm-only-pkg');     // → 命名空间（不是 default！）
const main = ns.default;                 // default 导出取 .default
const { helper } = ns;                   // named 导出直接可用
```

- 硬约束：目标 ESM 图里**任何顶层 await** → `ERR_REQUIRE_ASYNC_MODULE`
  拒绝加载（require 是同步的，TLA 无法同步等待）——含 TLA 的库仍需
  动态 import 或纯 ESM 项目。
- 反向（ESM import CJS）由 cjs-module-lexer 静态分析出 named 导出：
  只认 `exports.a = ...` / `module.exports = { a, b }` 字面量模式；
  动态计算（`module.exports = makeExports()`）**只剩 default**——
  库作者要么字面量导出，要么补 `Object.defineProperty(exports, '__esModule')`
  生态约定。
- 引擎面：这是 Node 的能力，**浏览器/打包器不相关**（bundler 有自己的
  interop，59 节）；jest 仍需 `--experimental-vm-modules` 走 ESM。
- 战略结论（2026）：Node 20 已于 2026-04 EOL，库可以**ESM-first**，
  再给 CJS 消费者留 require(esm) 通道（66 节策略三）。

## 59. default/named 互操作陷阱

转译器时代留下 `__esModule` 约定：CJS 产物互相伪装 default——
native ESM、TS/esbuild 产物、真 CJS 三者混布时就是「.default.default」
事故高发区。

```javascript
// 用户侧防御：任何来源的「default 优先」取值
async function importPkg(name) {
  const mod = await import(name);
  return mod.default && mod.default.__esModule ? mod.default
       : (mod.default ?? mod);      // 真 CJS：default 即整体
}
// 类型边界上的等价防御（TS）：esModuleInterop: true + allowSyntheticDefaultImports
```

- 症状速查：`pkg.default is undefined`（拿到的是命名空间）；
  `fn is not a function`（默认导出藏在 `.default` 里）；两行都出现 =
  双层包装（转译 CJS 又被当 native 处理）。
- Babel/TS 的 `_interopRequireDefault`：把无 `__esModule` 的 CJS 整体包成
  `{ default: mod }`——所以**转译代码必须成对出现**（消费方也有 interop）；
  native ESM 消费转译 CJS 包时 default=module.exports 本体，named 走 lexer。
- esbuild/rollup 输出格式差异：`format: 'cjs'` 会给导出加 `__esModule`，
  但「混合 cjs/esm 双入口」包的 default 语义在三种解析器（Node/bundler/Jest）
  下可能有三种结果——**导出形状写进包测试**（`assert(pkg.x)` 而非只 import）。
- 逃生舱：`import * as ns` + 运行时探测（上面代码），或干脆全站统一
  ESM——互操作债的唯一长期偿还方式。

## 60. import attributes 与 JSON modules

`import data from './config.json' with { type: 'json' }`——attributes 的
使命是**让宿主拒绝错误类型的模块**（JSON 当 JS 执行 = 安全漏洞面）。

- 关键字史：2023 年 `assert` 改名 `with`（旧关键字短暂并存后移除）；
  Chrome 123+ / Node 20.10+ 支持 `with`，TS 5.3+ 语法支持——新代码只写 `with`。
- 动态形式同规则：`await import('./a.json', { with: { type: 'json' } })`；
  缺 attribute → 抛错（Node ERR_IMPORT_ATTRIBUTE_MISSING）。
- JSON 模块的导入是**冻结**的命名空间（default = 解析后的对象）——
  拿不到「导出名字」，只有 default；深层配置拆分用多个 JSON 文件而非
  期待 named。
- 属性不只 type：宿主可定义更多键；Node 目前仅认 `type`，未知键**不**报错
  但被忽略——别拿它当传递通道。loader 钩子（68 节）能读到 attributes，
  自定义虚拟模块（`type: 'db-config'`）由此实现。
- 浏览器替代视角：小配置随包发布时，`with { type: 'json' }` 好过
  fetch + 手动缓存；**大**配置（>几百 KB）则 fetch 可并行/可缓存/可懒加载，
  静态 import 会进关键路径阻塞首屏——按体积选边。

## 61. exports 字段与条件导出解析

package.json `exports` 一旦出现，**取代 main 成为唯一子路径真源**——
老用户深路径 `require('pkg/lib/internal')` 立刻断（"Package subpath ... is
not defined by exports"），这是发包最常见破坏性变更。

```json
{ "exports": {
    ".": { "types": "./index.d.ts", "import": "./index.mjs",
           "require": "./index.cjs", "default": "./index.cjs" },
    "./utils": "./utils.js",
    "./legacy/*": "./dist/legacy/*.js" } }
```

- 条件**按序首个命中即止**：`types` 必须排第一（TS 约定）；
  `default` 兜底放最后；自定义条件（`development`/`react-server`）需要
  消费方 `--conditions=xxx` 或构建器注入——文档里写清楚，别指望猜。
- `require` 条件在 require(esm) 时代的新含义：指「同步加载通道」，可以是
  .cjs 也**可以是 ESM**（Node 24.15+ 同步加载的 ESM 图，58 节）——
  双入口不必再强绑 CJS。
- 通配符 `*` 单个、在文件段两侧任一出现；`imports` 字段（`#` 前缀）是
  包内别名——优先于相对路径，重构内部结构不动导入语句。
- 调试工具链差异：Node/Jest/bundler/Vite 的条件注入不同（Vite dev 注入
  `development` + browser），同一包在不同工具下解析到不同文件——发包前用
  `require.resolve`/`import.meta.resolve` 双端冒烟（67 节）。

## 62. 循环依赖在两套系统下的行为

循环不是「能不能跑」而是「拿到什么」：CJS 给你**半初始化的 exports**，
ESM 用 TDZ 逼你在设计上解环。

```javascript
// CJS：a → b → a
// b 里 require('./a') 拿到的是 a 执行到「当前点」的 exports 快照式引用
const a = require('./a');       // a.started = true 有了；a.ready 还没有
console.log(a.ready);           // undefined —— 但之后 a.ready 赋值能看见（同一对象）
```

- CJS 关键分界：循环点拿到的是 `module.exports` **当时的形状**——
  只要后续用**属性赋值**（exports.x =），晚绑定还能看到；
  若循环点之后 `module.exports = {...}` 整体换对象，对方**永远拿旧对象**
  （这是「改 module.exports 优化」在循环图里的死刑）。
- ESM：链接期建好全部绑定，求值按图深度优先——访问未求值的 `let/const`
  抛 ReferenceError（TDZ），函数声明可用（提升）——**把循环依赖限制在
  函数调用层**（运行期才取绑定）是唯一的稳定模式。
- 类型环不算环：TS 的 `import type` / verbatimModuleSyntax 下的纯类型导入
  被擦除，运行时图无此边——类型循环重构成本趋零，先擦类型环再治运行环。
- 解环三斧：抽共同依赖到第三模块；注册回调（依赖倒置）；延迟
  require/import（函数体内）。esbuild 会对「静态可证的循环 undefined」
  打警告——CI 里把它升级为 error 提前暴露。

## 63. 顶层 await 的图阻塞语义

TLA 是**阻塞整个导入链**的：谁 import 了含 TLA 的模块，谁就等它落定；
依赖它越多，阻塞面越大——库代码基本被排除（58 节 require 也进不来）。

```javascript
// config.js（TLA）：所有 import 它的入口都串行等这一步
export const config = await loadConfig();   // 网络/文件 IO
// app.js
import { config } from './config.js';       // ← 整条启动链卡在 loadConfig
```

- 死锁形态：TLA 模块动态 import 回「正在等它的模块」→
  Promise 死锁或 `ERR_REQUIRE_ASYNC_MODULE` 变体——TLA 与循环依赖同时
  出现基本等于死刑，静态图工具（madge）要在 CI 卡位。
- 性能模式：TLA 让启动**串行化**；能并行的是多个互不依赖的 TLA 模块
  （各自分支并行）——「一个 TLA 根模块」是结构信号，评审时画出等待图。
- 库纪律：库文件禁 TLA（消费方既有 CJS 也有打包器，全都会出问题）；
  应用入口的 TLA 限「必须的启动数据」，且加超时兜底（29 节 signal）。
- 未来解法：`import defer` 提案（延迟求值，运行到首用时才执行模块）
  正在推进——现阶段用动态 import + 显式缓存函数模拟同样的惰性。
- 打包器影响：含 TLA 的 chunk 必须异步初始化——webpack 输出会强制
  async chunk；esbuild 只允许 esm 输出——把 TLA 引入「被 CJS 包消费的库」
  直接破坏产物形状。

## 64. tree-shaking 与副作用边界

摇树的本质是「**可证无副作用**才敢删」——证明材料来自你，不来自打包器。

- 最大杠杆是 `"sideEffects": false`（或数组列出有副作用的文件，如
  `["*.css", "./src/polyfills.js"]`）——一个字段常常砍掉 30-60% 体积；
  但说谎的代价是**运行时缺功能**（polyfill 没执行、CSS 没注入）：
  发包时用 `pure` 标注粒度控制，不轻易全包 false。
- `/*#__PURE__*/` 注释：标注**调用表达式**无副作用（esbuild/rollup 识别，
  大小写敏感）；`export const x = /*#__PURE__*/ factory()` 才能被删——
  工厂函数的纯度承诺由你背书。
- 删不动的三大类：模块顶层副作用（赋值全局、prototype 补丁、类 static 块）、
  CJS（动态 exports 基本摇不动）、被 `export * from` 合并的命名空间
  （部分工具放弃分析）。
- 双检纪律：`rollup-plugin-visualizer`/`webpack-bundle-analyzer` 看产物
  里还有什么 + 运行时冒烟（打包后的 E2E 一条）——「编译过」不等于
  「该删的删了、该在的在」。
- `import` 的形态影响摇树：`import x from 'pkg'` 优于
  `import { deep } from 'pkg/dist/tree'`——子路径绕过 exports 主入口的
  sideEffects 作用域（61 节），也更容易撞上双重实例（65 节）。

## 65. 打包器 hoisting、chunk 与双重执行

- 模块提升合并：Rollup 把多个模块提进一个作用域，用重命名保唯一性——
  顶层 `var x` 撞名靠前缀解决，但**求值顺序**保持导入序；hoisting 不重排
  副作用。老 bug 形态（TDZ 撞名）在新工具已基本消失，风险转到下面两条。
- **同包双实例**：一个包被两条 specifier 链解析（`pkg` 与
  `pkg/dist/index.mjs`，或嵌套依赖的不同 major），bundle 里就是两份代码、
  两个模块状态—— instanceof 失败、单例重复注册、side effect 跑两遍
  （111 节事故复盘）。治理：resolve.alias 强制归一 + 依赖去重工具
  （bundleduplicates/`npm ls pkg`）+ `exports` 主入口纪律（64 节）。
- chunk 分裂的加载竞态：循环依赖横跨两个 chunk 时，加载顺序受网络影响，
  「先访问后初始化」会拿到 undefined——现代打包器用异步占位缓解，但
  跨 chunk 的循环仍是禁手；`manualChunks` 也要按「无环分层」切。
- import() 瀑布：chunk A 顶层 import chunk B，B 再 import C——网络串行
  三跳；合并 chunk 或用 `<link rel="modulepreload">` 预载整条链。
- dev/prod 差异（Vite：dev=真 ESM 按需、prod=打包）会让「双实例」只在
  生产出现——**生产形态的冒烟测试**（本地 build+preview）必须进 CI。

## 66. 双包发布纪律与单例陷阱

dual package hazard：CJS require 与 ESM import 各自缓存、**各建实例**——
`instanceof` 跨形态失真、单例（i18n store、主题注册表、全局互斥）翻倍，
且只在「依赖树里两种消费形态并存」时爆雷，本地永远复现不了。

```json
{ "name": "my-lib", "exports": {
    ".": { "require": "./index.cjs", "import": "./index.mjs" } } }
```
```javascript
// index.mjs —— 策略一（推荐）：ESM 壳转发到唯一 CJS 实现
import lib from './index.cjs';          // 状态只有一份（CJS 实例内）
export const { core, register } = lib;  // named 桥接（牺牲部分摇树）
export default lib;
```

- 策略二（2026 新解）：**ESM 单实现 + CJS 壳**：`module.exports = require('./index.js')`
  ——require(esm) 稳定后（58 节）这是唯一实现、零双实例；
  前提：ESM 图无 TLA、目标 Node ≥ 22.12。
- 状态归一边界：无论哪种策略，**可变状态只允许住在一个形态的实现里**，
  另一侧只转发绑定——把这条写进库的 README，消费方混用时也有据可查。
- 检测工具：`are-the-types-wrong`（attw）验证每种 TS/运行时组合的导出形状；
  `import fresh from 'pkg'` 双形态冒烟测试进 CI。
- 停止双发的时间表：Node 20 EOL（2026-04）后，纯 ESM 发布 + 24.15+ 的
  require(esm) 是默认答案；CJS-only 消费者只剩锁定老版本一条路。

## 67. 动态 import 与 import.meta

`import()` 是**语法**不是函数：`const i = import; i('x')` 直接 TypeError——
它拿到的是语法级求值，specifier 在原模块处解析（不认局部变量插值）。

```javascript
const mod = await import(`./locales/${lang}.js`);  // 模板串合法：构建器按模式打包
// 部署竞态兜底：老 HTML 引新 chunk 404 —— 重载一次并升级版本
async function loadChunk(spec) {
  try { return await import(spec); }
  catch (e) { if (isChunkError(e)) { location.reload(); } throw e; }
}
```

- 部署竞态（发版后旧页面拉新 chunk 404/内容漂移）是 SPA 高频事故：
  服务端给 chunk 加内容哈希 + **旧版本 chunk 保留 N 个版本** + 前端
  chunk 失败即整页刷新，三件套缺一不可。
- `import.meta`：`url`（浏览器）/`filename`+`dirname`（Node，经
  `fileURLToPath` 转换）/`resolve(spec)`（Node 20.6+ 同步版；浏览器
  Chrome 105+）——`resolve` 是「模块相对定位资源」的正规军，别再算
  `new URL('./x', import.meta.url)` 之外的 hack。
- `import.meta.env`（Vite 注入）/`import.meta.webpackHot`——构建器专用面：
  它们让代码**非通用**（裸 Node 下 undefined），访问前判空并集中封装。
- 动态 import 的双重语义：运行时加载 + 代码分割标记——同一次调用身兼
  「懒执行」与「切 chunk」；测试环境（Jest 默认转换）会把 import() 桥成
  require——测试永远过、生产才暴露 chunk 边界问题的教训：E2E 必须测
  构建产物。

## 68. module.registerHooks 与编译缓存

Node 的加载器定制两条路：`module.register()`（独立线程、可异步、老）与
**`module.registerHooks()`**（Node 24.15+，主线程同步钩子，无消息传递开销）。

```javascript
// loader.mjs —— 同步钩子：TS 源码即时剥离（amaro/tsx 的机制）
import { registerHooks } from 'node:module';
registerHooks({
  resolve(spec, ctx, nextResolve) { return nextResolve(spec, ctx); }, // 链式转发
  load(url, ctx, nextLoad) {
    return nextLoad(url, ctx);   // 改写 url/format 做虚拟模块；不能做异步 IO
  },
});
```

- 钩子纪律：`next*` 链式调用把控制权传给下层（内置解析/加载）——
  全量替换实现（不调 next）要自担全部路径/错误语义；
  registerHooks **不能做异步 IO**（同步限制），要网络加载回 `register()`。
- 用途矩阵：TS/JSX 剥离、mock.module 测试替身（101 节）、虚拟模块
  （配置注入、WASM 绑定）、import attributes 处理（60 节）——
  monkey-patch require 的老路（require.extensions）就此退役。
- **编译缓存**：`module.enableCompileCache()`（Node 24.15 起稳定）把编译后
  字节码持久化到 `NODE_COMPILE_CACHE` 目录——CLI/服务冷启动提速常见
  20-50%；它是「锦上添花」，先做懒加载再上缓存。
- 诊断：钩子 bug 的表现是「解析在 Node 对、在钩子环境错」——用
  `import.meta.resolve` 双端冒烟 + 把加载器版本写进诊断日志，三者可对齐问题归属。

## 69. 性能预算与 INP 时代的度量

INP 于 2024 年 3 月正式取代 FID（好 <200ms、差 >500ms）：测的是**整个
交互生命周期**（含后续帧），任何一段长任务都能拖爆它——JS 预算从
「首屏体积」升级为「任何交互路径上的任务长度」。

- RUM 先行：`web-vitals` 库（attribution build）拿现场归因（哪个元素、
  哪段交互）；实验室数据（Lighthouse）只是代理指标，别用它替代线上。
- 归因工具：`PerformanceObserver` 监听 `longtask`（>50ms）与
  **LoAF**（`long-animation-frame`，Chrome 123+）——LoAF 带脚本 URL 与
  invoker，直接回答「哪段代码」；INP 排查从 LoAF 开始。
- 预算分层：关键路径 JS < 170KB gz（Google 阈值）；交互路径单任务 < 50ms；
  任何任务的「总阻塞时间贡献」进 dashboard 而不是只看 LCP。
- 回归门禁：bundle-size check（chunk 级）+ Lighthouse CI（阈值断言）+
  RUM 分位数告警（p75 恶化 10% 报警）——预算无门禁等于没有预算。
- Node 侧基线：`clinic doctor`（事件循环/GC/IO 三板斧）+ autocannon 压测；
  「无基线的优化 PR」评审直接打回（78 节方法论）。

## 70. 热路径分配纪律

分配率决定 Scavenge 频率：**每帧稳定产生分配的代码不需要别的理由就该改**。

```javascript
// 反面：每事件新建闭包 + 数组 + 对象三连
el.onpointermove = (e) => { process(e, { x: e.clientX, y: e.clientY }); };
// 正面：复用载荷对象（写入复用，读取方立即消费）
const payload = { x: 0, y: 0 };
el.onpointermove = (e) => { payload.x = e.clientX; payload.y = e.clientY; process(e, payload); };
```

- 高频事件路径（pointermove/scroll/rAF）零分配是硬指标：闭包提出去、
  载荷对象复用（fill 模式：`fn(target)` 原地写入）、避免 spread 与
  `Array.from`（都要新建数组）。
- 每次函数调用即两个隐性分配（可能被内联消除）——热循环内的函数调用
  越少越稳；但**先测**：逃逸分析常常比你悲观估计得好（18 节）。
- `for...of` 与闭包捕获会在「迭代器对象」上分配（数组快速路径除外）——
  超热循环退回索引 for；生成器在热循环里禁用（17 节）。
- 字符串拼接产物、模板串、`toFixed`/`toLowerCase` 都是分配——每帧日志
  拼字符串做「开发期才开」的开关，别默认开着跑生产。
- 度量闭环：Allocation sampling 跑 10s 窗口，看「per second allocations」
  与 retainer 增长；帧预算视角用 `performance.measureUserAgentSpecificMemory`?——
  实际用 LoAF + memory trend（20 节工具箱）。

## 71. TypedArray 与 DataView 的字节纪律

- 选型：同构数据用 TypedArray（元素访问可吃 IC，15 节种类纪律同款）；
  异构结构/显式端序用 DataView（`getFloat64(off, true)` 小端明确，别赌
  平台端序——网络协议一律显式 LE/BE）。
- 视图不拷贝：`new Uint8Array(buffer, byteOffset, len)` 是**零拷贝视图**；
  `slice()` 才拷贝、`subarray()` 是视图——传输大块数据用视图 + transfer
  （93 节），持久化要独立副本时 slice。
- `set(target, offset)` 是 memmove 级拷贝原语：批量搬运别写循环逐字节赋值。
- Uint8 算术**回绕**（255+1→0）且不抛错——协议字段累加前先校验范围；
  符号扩展读法：`Int8` 视图或 `(b << 24) >> 24`。
- **分块解码陷阱**：`TextDecoder` 处理网络分块必须
  `decoder.decode(chunk, { stream: true })`——多字节 UTF-8 字符被切在
  chunk 边界时，不带 stream 会产出 U+FFFD 乱码；收尾再 `decode()` 一次冲刷。

```javascript
const dec = new TextDecoder();
for await (const chunk of body) out(dec.decode(chunk, { stream: true })); // ✓
```

## 72. ResizableArrayBuffer 与 detach 语义

ES2024 的 RAB/growable SAB + length-tracking TypedArray，是「协议缓冲增长」
的原生解；配套的 `ArrayBuffer.prototype.transfer()`（Chrome 114+）让
**零拷贝移动**成为显式语义。

```javascript
const rab = new ArrayBuffer(0, { maxByteLength: 1 << 20 });
const view = new Uint8Array(rab);          // length-tracking：跟随 resize
rab.resize(4096);                           // view.length 变为 4096
const moved = rab.transfer();               // 移动所有权：rab 变空壳
rab.byteLength;                             // 0 —— 已 detach
```

- detach（被 transfer/structuredClone transfer 后）：TypedArray 读出
  `undefined`、写被忽略；DataView 直接 RangeError——同一次「转移后访问」
  两种 API 两种表现，封装层统一成显式错误。
- length-tracking 视图越界（读超过当前 size 的下标）返回 undefined——
  不是异常；要硬失败就用固定长度视图（越界即 undefined 也一样，固定视图
  在 resize 后可能整个失配——协议解析自己管 in-bounds 检查）。
- `transfer()` 与 `transferToFixedLength()`：后者转成不可扩 RAB/普通 AB，
  适合交给不支持 RAB 的旧 API（如部分 WASM 绑定）。
- growable SharedArrayBuffer 只能增不能缩，且增长对其它 agent 可见——
  与 Atomics 配合时**容量变更本身要有协议**（73 节状态信号）。

## 73. Atomics.waitAsync 与跨线程协作

主线程**不能** `Atomics.wait`（浏览器禁止阻塞）；`Atomics.waitAsync`
（Chrome 87+）返回 `{ async, value }`：async 分支拿到 promise——主线程
也能「等锁」而不冻结。

```javascript
// 主线程：等待 worker 把槽位填成非零
const res = Atomics.waitAsync(i32, SLOT, 0 /*期望值*/);   // 不阻塞
if (res.async) { const eaten = await res.value; /* 被唤醒或超时 */ }
// worker：干完活通知所有等待者
Atomics.store(i32, SLOT, 1); Atomics.notify(i32, SLOT, Infinity);
```

- futex 惯用式：`wait(槽, 期望值)` 带期望值防**丢失唤醒**（先改后 wait 的
  竞态）——「检查-等待」永远用带期望值的原语，别 sleep 轮询。
- 前置条件：`SharedArrayBuffer` 要 COOP/COEP 响应头（`crossOriginIsolated`）——
  `document.domain` 已死，凭据策略用 credentialless（COEP: credentialless）
  降低接入成本；上 SAB 前先看部署面能不能加头。
- ES2024 放宽：Atomics 的算术/交换族**也允许非共享 buffer**——单 Worker 内
  的确定性计数不再需要 SAB；wait/waitAsync 仍要 SAB。
- 无锁结构起步：counter/槽位信号 → 生产者消费者环（双指针 + notify）→
  真正的无锁队列谨慎——先 lock-free 计数器，别上来就 SPSC 队列全家桶。

## 74. 主线程长任务外科手术

流程固化：**LoAF 定位 → 归类 → 下刀**——不定位就优化的改法，九成在
治没病的代码。

| 归类 | 下刀点 |
| --- | --- |
| 大同步计算 | 拆片 + yield（39 节）/ 移 Worker（102 节） |
| 巨量 DOM 操作 | 虚拟化/分片渲染（25 节）/ fragment 批量 |
| 强制回流循环 | 读写分离（90 节） |
| 网络瀑布在交互里 | 预取/缓存（预算挪到空闲期） |
| 第三方脚本 | requestIdleCallback 延后 / 降级加载 |

- 拆片粒度按**时间预算**不按条数：`performance.now()` 每片 8-12ms
  （一帧 16.7ms 的一半留给系统与输入）。
- 移 Worker 的判定：单次 >50ms 且无法拆（算法整块）→ 移；拆得出就别移
  ——Worker 的序列化成本（93 节）可能吃掉收益，传大 buffer 用 transfer。
- 交互路径上的**第三方标签**（分析/AB）是隐藏长任务源：LoAF 的 script
  URL 归因直接点名；治理手段是延后加载与标签管理器调度（TMS）。
- 验收标准：改后再录 LoAF，同操作最长帧下降且 p75 INP 达标——
  「看起来快了」不是验收，两条曲线才是（69 节）。

## 75. 字符串与正则热路径

- 正则字面量在源码解析时**预编译**一次复用；`new RegExp(pattern)` 每次都
  编译——热路径把动态 pattern 建好后**缓存 RegExp 实例**（Map keyed by
  pattern 字符串）。
- `/g` 正则是**有状态对象**：`lastIndex` 跨调用点共享——同一个全局正则
  在两处 `exec`/`test` 会互相踩（经典跨污染 bug）；并发态用
  `String.prototype.matchAll`（要求 /g、每次新建迭代器、空匹配自动前进）。
- `search`/`match`（非 g）不动 lastIndex，`exec`（g）动——一次漏改
  lastIndex，下一轮 `exec` 从错误位置开始返回 null，循环提前终止。
- Unicode：`/u` 改变 `.`、量词与转义的语义；`/v`（ES2024，Chrome 112+）
  是 /u 的超集，加集合运算（`[\p{L}--\p{Latin}]`）——新代码直接用 /v。
- 热分派场景：几十个 case 以上的字符串分发，`Map<string, handler>` 优于
  switch（引擎对超大 switch 生成跳转表，但 Map 的哈希更可预期且可动态）；
  前缀分发可先 `s[0]` 粗筛再精配。
- 用户输入进正则必须 `RegExp.escape`（V8 13.6/Chrome 134/Node 24 原生）——
  手写 `input.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')` 的时代结束。

## 76. 缓存友好的数据布局

JS 对象默认是「指针串联的 AoS」：字段散在堆上、间接寻址密集——
数据密集型系统（几何、粒子、表格引擎）改 **SoA**：平行 TypedArray 每列一存。

```javascript
// SoA：遍历 x,y 坐标时内存连续，cache line 利用率高一个数量级
const px = new Float32Array(N), py = new Float32Array(N),
      vx = new Float32Array(N), vy = new Float32Array(N);
for (let i = 0; i < N; i++) { px[i] += vx[i] * dt; py[i] += vy[i] * dt; }
```

- 装箱逃逸：对象数组里的每个数字都是独立堆分配（box）——数值密集场
  TypedArray 是唯一正解；混合元数据（id→索引）用 Map 或外挂 Uint32 列。
- 打包位段：4 个 u8 塞一个 u32（`a | b<<8 | c<<16 | d<<24`）提高缓存密度；
  注意 JS 位运算是 32 位且 `>>` 是算术移位——符号位用 `>>>`。
- 局部性优先于一切微优化：连续 TypedArray 顺序遍历 > 对象数组 >
  Map/Set 迭代（哈希桶跳转）；随机 key 访问 Map 的缓存不友好是本性，
  热点实体建 id→slot 索引转成数组访问。
- 大 ArrayBuffer 的 GC 身份：外部内存记账（heapUsed 不涨、rss 涨），
  长生命周期大缓冲是「一次性投入」——别在帧循环里反复分配再丢。
- 验证工具：微基准 + `performance.now()` 差值（SoA vs AoS 常见 2-5×），
  配 78 节防 DCE（结果写回全局黑洞变量）。

## 77. WebAssembly 边界的成本模型

- 该上 WASM 的判据：已有 C/Rust 热内核（编解码/密码学/解析器）、
  或 V8 优化后仍差一个数量级的计算内核；「JS 写着慢」不成立——
  先证明 JS 版已按 11-18 节纪律优化。
- 边界成本两块：调用本身（现代引擎下亚微秒级，可忽略）+ **数据编组**
  （线性内存拷贝或共享视图）——大块数据用 SAB 视图直读直写，
  小而频繁的标量传递才是边界杀手（合并调用、批处理参数）。
- WasmGC（Chrome 119+）：带 GC 的结构体直接进宿主堆，无需线性内存
  序列化——Rust/Kotlin/Dart 新工具链首选目标，JS 边界传引用更顺。
- 实例化纪律：`WebAssembly.compileStreaming`（流式编译）+ 产物
  `.wasm` 进 Cache Storage；首次加载的编译时间常被漏算——
  E2E 测「冷启动」路径而不是只有热路径。
- JSPI（JS Promise Integration，2025 年在 Chromium 可用）：WASM 内部
  直接挂起等待 JS Promise，替代手写回调状态机——异步绑定面（DB/网络
  协议栈）从回调地狱里解放，但调试面尚新，先在非关键路径试点。
- 陷阱：linear memory 的增长是整段拷贝；无限增长的 WASM 堆在
  performance.measureMemory 里不可见（外部内存）——监控 rss。

## 78. 剖析方法论与微基准谎言

标准循环：**复现（真实数据规模）→ 录制 → 单假设 → 单改动 → 中位数+p95 →
回归门禁**。跳过第一步的优化一半在优化错误场景。

- 微基准四大谎言：① 无预热（测解释器）；② 结果被 DCE 吃掉（结果必须
  写入全局/黑洞）；③ 单形状运气（真实数据 megamorphic，测试全 mono）；
  ④ 跨函数内联泄漏（测试里 inlined，生产里调用点分开）。
- 正确姿势：N≥10 轮取中位数与方差；轮间 `--expose-gc` 下显式 gc()；
  mitata（现代基准库，统计口径好于老 benchmark.js）；
  `%NeverOptimizeFunction` 只用来构造对照组。
- deopt 巡检：`node --trace-opt --trace-deopt` 跑生产样例流量回放——
  「优化函数列表」对齐预期热函数清单，出现意料外的 deopt reason 立档。
- 火焰图读法：先看**宽度断层**（宽窄骤变处），再看**异常块**（GC/遍历/
  recompute style）；Chrome 的 JS profile 与 System trace 分开录，
  混录会互相干扰。
- 归档纪律：每次优化 PR 附带「前后火焰图 + 数据规模说明 + 复现命令」——
  下次回归时你只需要重跑，不需要重新推理。

## 79. 错误分类学与构造纪律

- 内建语义分层：TypeError=契约违约（该在边界处治，修的是调用方）；
  RangeError=参数范围（Array/ArrayBuffer/数字方法）；
  ReferenceError=名字解析（TDZ/拼写）；SyntaxError=解析期
  （eval/import 的语法错**可**被 catch——同文件源码错**不可**）。
- DOMException 的 name 体系：AbortError/NotAllowedError/SecurityError/
  InvalidStateError…——浏览器 API 的「异常即流程」面，catch 后按 name
  分支，`instanceof DOMException` + `e.name` 是标准判别式。
- AggregateError（`.errors` 数组）与 SuppressedError（`.error/.suppressed`，
  48 节）是 ES2021/2025 世代新类型——错误处理代码对它们做显式分支，
  别落进 `e instanceof Error` 一把梭。
- `Error.isError(x)`（V8 13.6/Chrome 134/Node 24）：跨 realm 判 Error 的
  官方解（instanceof 跨 iframe 必假）；仍要防鸭子（`{ name: 'Error' }）`——
  isErroor 是身份检查不是形状检查，正合适。
- 构造纪律：子类只按**taxonomy** 建（ValidationError/TimeoutError），不按
  「业务流程」建；message 用稳定前缀（`[AUTH_401] token expired`）——
  日志检索、告警指纹、文档锚点三受益。

## 80. error.cause 与错误链封装

`new Error(msg, { cause })`（ES2022）是错误链的官方形态：保留原始栈，
让包装层加上下文而不销毁证据。

```javascript
try { await syncUser(id, { signal }); }
catch (e) {
  if (isCancellation(e)) throw e;                    // 取消原样放行（30 节）
  throw new Error(`[SYNC] user ${id} sync failed`, { cause: e }); // 包装+因果
}
// 顶层打印：沿 cause 链递归输出
function renderError(e, depth = 0) {
  if (depth > 5) return '…';
  return e?.message + (e?.cause ? ` ⇐ ${renderError(e.cause, depth + 1)}` : '');
}
```

- 反模式罪名：`catch (e) { throw new Error('failed'); }`——丢 cause 的
  重抛在事故日等于毁尸灭迹；lint 可用 `no-unsafe-rethrow` 类规则卡位。
- 什么不该包装：取消（AbortError）、进程级致命错、已经带足上下文的
  库错误——「每层都包一层」的洋葱错误链让日志翻倍、信号衰减；
  在**边界**包（API 入口/IPC 通道），中间层透传。
- 栈的真相：cause 保存的是**构造时**的栈；异步链里每一跳都有自己的栈
  片段——完整轨迹要靠 cause 链拼接，日志系统要配 cause 递归序列化器
  （81 节）。
- 消息纪律：包装层加「操作 + 关键标识」（user id、batch no），不重复
  原始信息；机密值（token/PII）绝不进 message——82 节脱敏在前端兜底。

## 快速索引 · 续（81-112 节）

| 想找什么 | 去哪一节 |
| --- | --- |
| Worker 里错误怎么带回主线程 | 81. 跨边界错误序列化 |
| 崩溃上报与 PII 脱敏 | 82. 错误上报体系与脱敏 |
| forEach(async) 吞错 | 83. 异步边界的错误吞噬陷阱 |
| finally 里 return 的坑 | 84. finally 语义与清理顺序 |
| window.onerror / uncaughtException | 85. 全局错误捕获面 |
| 进程该不该崩 | 86. fail-fast 与 supervisor 模式 |
| passive 与滚动卡顿 | 87. 事件捕获冒泡与 passive 深水 |
| 委托什么时候失灵 | 88. 事件委托的边界与 shadow DOM |
| IO/RO/MO 回调时机 | 89. 观察器时序：IO、Resize、Mutation |
| 强制回流怎么排 | 90. 布局抖动与读写分离 |
| rAF 与 120Hz 屏 | 91. requestAnimationFrame 对齐与帧预算 |
| 隐藏标签页定时器死了 | 92. 后台节流与可见性纪律 |
| transfer 大缓冲 | 93. postMessage 与传输语义 |
| localStorage 卡主线程 | 94. 存储阻塞模型：localStorage 与 IndexedDB |
| Node 24/26 该用哪个 | 95. Node 版本地图：24 LTS 与 26 Current |
| fs 密集为何卡事件循环 | 96. libuv 阶段与线程池阻塞 |
| 请求上下文传递 | 97. AsyncLocalStorage 与 AsyncContextFrame |
| pipe 还是 pipeline | 98. stream pipeline 与现代组合子 |
| exec 注入事故 | 99. 子进程纪律与注入防御 |
| --permission 怎么配 | 100. Node 权限模型与供应链防线 |
| 内置测试跑法 | 101. node:test 与测试基建 |
| Worker 池与克隆开销 | 102. Worker threads 与克隆成本 |
| mock 边界与假时钟 | 103. 测试替身边界与假时钟 |
| rejection 报到别的用例头上 | 104. 异步测试的错位 rejection 陷阱 |
| 快照 diff 爆炸怎么治 | 105. 快照测试的腐蚀治理 |
| fast-check 收缩定位 | 106. 属性测试与模糊测试纪律 |
| Playwright 防 flake | 107. E2E 等待条件而非 sleep |
| 代码异味对照表 | 108. 生产反模式速查表 |
| 页面软死机案例 | 109. 事故复盘一：微任务饿死心跳 |
| 内存只涨不降案例 | 110. 事故复盘二：闭包持有大堆 |
| 埋点双倍计数案例 | 111. 事故复盘三：打包器双重执行 |
| JS PR 评审要点 | 112. 专家级代码评审清单 |

## 81. 跨边界错误序列化

Error 过不了 structuredClone 的完整保真（栈丢、自定义字段丢、原型拍平）——
跨 Worker/iframe/HTTP 的错误通道必须**显式协议化**。

```javascript
// worker → main 的标准信封
function serializeError(e, seen = new Set()) {
  if (e instanceof AggregateError) return { name: e.name, message: e.message,
    errors: e.errors.map(x => serializeError(x, seen)) };
  return { name: e?.name ?? 'Error', message: e?.message ?? String(e),
    stack: e?.stack, code: e?.code,
    cause: e?.cause && !seen.has(e.cause) ? serializeError(e.cause, seen) : undefined };
}
// main 端重建：按 name 查注册表 → new Registry[name](payload)（带 cause 递归）
```

- 反序列化端**永远不要 eval 栈或消息**——它来自另一个信任域；
  栈只做展示与指纹，不当数据用。
- DOMException 可以被 structuredClone（少数幸存者）——但 name 只保留
  标准 code；自定义字段照丢——统一信封最省心。
- HTTP API 侧：错误 = 机器可读 code（`AUTH_401`）+ 人读 message +
  correlation id；客户端按 code 分支、按 id 关联日志——**别用 HTTP
  status + 文案当错误类型系统**。
- 日志序列化：pino 式 err serializer 会沿 cause 递归展开并截断栈深——
  自研 loggger 没做 cause 递归，等于每天在丢一半的事故现场（80 节）。

## 82. 错误上报体系与脱敏

- 出口时序：错误 → 本地队列（批量/背压）→ `sendBeacon`（页面隐藏时）或
  `fetch(..., { keepalive: true })`；`unload` 不可靠且杀 bfcache——
  监听 `visibilitychange`（hidden）+ `pagehide`。
- 指纹去重：取栈顶 N 帧的「函数名+文件名」哈希（行号剔除——发版即漂移）；
  同 fingerprint 聚合计数，sentry 式 release 维度对比噪声。
- 采样与配额：每用户每日上限 + 按级别加权（fatal 全采、warning 1%）；
  队列满丢 warning 保 fatal——**别丢 fatal**。
- 脱敏在**出口**做一次，不在每处 throw 做：URL token/authorization 头、
  邮箱/手机号正则、请求体白名单（默认不上报 body）；
  脱敏规则有单测（往错误里塞假 PII 过一遍）。
- Sourcemap 纪律：产物公开线上**绝不**暴露 sourcemap——上传到上报平台
  私有存储、按 release 关联；CI 保证「sourcemap 产物不进 CDN」。
- Node 端出口：结构化日志（JSON lines）进收集器，**进程崩溃前同步 flush**
  （86 节 uncaughtException 里的 exitCode 流程）。

## 83. 异步边界的错误吞噬陷阱

- `forEach(async ...)` 是头号吞错位：forEach 不等 async 回调，回调 rejection
  变成无人认领的漂移 rejection（9 节），且迭代顺序不受 await 影响——
  修复：`for...of` + await，或 `await Promise.all(items.map(async ...))`。
- 浮动 Promise：事件处理器里调用 async 函数不 await——测试通过（断言先跑）、
  生产炸雷（rejection 晚到且无人接）；ESLint `no-floating-promises`（TS
  项目）是唯一系统防线。
- 空 catch 分级：`catch {}`（语法糖级的吞）绝对禁止；
  `catch (e) { log.warn(e) }` 要注明「为什么不上抛」——评审固定问题。
- Node stream 的 error 事件：没挂 listener 就**进程崩溃**——`pipeline()`
  （98 节）替你统一挂；手写 pipe 的旧代码必须有 per-stream error handler。
- EventEmitter 的 async 监听器：emit 不等待（40 节）——`captureRejections`
  或 events.on 兜底；否则监听器 rejection 直接变全局噪音。
- `await` 在 catch/finally 里：清理本身可能抛（关闭已断的连接）——关键
  清理包一层，主错误优先上报（84 节语义）。

## 84. finally 语义与清理顺序

finally 是「无论成败都执行」，但它有**三条能吞掉结果的暗道**——
2026 年的正确姿势是能用 `using` 就别手写 finally（47-48 节）。

```javascript
async function bad() {
  try { return await work(); }
  finally {
    return 'cleanup value';   // ← 顶掉 try 的 return/throw，静默丢结果
    // throw 一样顶掉；await 挂起则会延迟「异常到达调用方」的时间点
  }
}
```

- 暗道一：finally 里 return/throw 替换原结算——ES 规范行为，lint
  （no-unsafe-finally）必须开。
- 暗道二：finally 抛错顶掉主异常（原错误彻底消失）——using 时代的
  解决方案是 SuppressedError（48 节）；手写时代只能在 finally 里
  try/catch 把清理错误记日志不抛。
- 暗道三：finally 里 await 延迟结算——调用方晚拿到结果/错误；
  清理要 async 且不能慢时，改成「先 return 结果、fire-and-forget 清理
  + 监控」的显式注释模式。
- 多资源清理顺序：**LIFO**（后开的先关）——与 using 的逆序语义一致；
  每个清理加幂等守卫（closed 标志），重复清理静默通过。

## 85. 全局错误捕获面

- 浏览器双通道：`error` 事件（ErrorEvent：message/filename/lineno/.error）
  与 `unhandledrejection`（event.reason）；`reportError()` 供框架把
  「捕获后又想进全局管线」的错误交回全局处理器。
- 跨域脚本陷阱：第三方站脚本无 CORS + crossorigin 属性时，`window.onerror`
  拿到的是 `"Script error."` 空壳——script 标签加 `crossorigin="anonymous"`
  + 服务端 CORS 头才能拿到细节；上报系统按「空壳率」监控这个退化。
- `event.error` 可能是 null（跨域、栈耗尽）——序列化兜底用 message/位置。
- Node：`uncaughtException` 到达即**状态不可信**：记录 → flush → 
  `process.exitCode = 1` → exit；清理只允许同步最小动作；
  重启交给 supervisor（86 节）。
- `uncaughtExceptionMonitor`：只观察不改变默认行为——观察性探针优先用它；
  `unhandledRejection` 在 Node 15+ 默认与 throw 同级（见 9 节）。
- 测试纪律：全局 handler 在 setup 挂、teardown 卸并断言「本用例零全局错误」
  ——全局捕获面本身也是被测对象。

## 86. fail-fast 与 supervisor 模式

无状态服务的正确姿态是 **crash-only**：未知状态一律自杀，把恢复交给
supervisor（k8s/systemd/PM2）——「带病运行」比「短暂重启」贵得多。

- 代码分层：可预期失败 → 错误值/异常（正常处理）；不可预期失败（不变式
  破坏）→ 立即 crash + 带上下文的 fatal 日志；**别把 catch 当万能垫片**。
- 重启经济学：启动 < 2s（懒加载 + module.enableCompileCache（68 节）），
  探活 liveness /readyiness 分离（readiness false 挡流量，warmup 完成再开）。
- 熔断与舱壁（防「重启风暴」）：依赖故障率超阈值 → open（快速失败）→
  半开试探；每依赖独立并发池与队列上限——一个慢下游拖死全局的根源
  是共享无限队列（31 节同病）。
- 看门狗：进程内 `setInterval` 心跳检测自身漂移（4/96 节），超阈值
  主动上报/退出——比被动等 liveness 超时早一个量级发现卡死。
- 发布纪律：kill switch（特性开关即时关路径）、灰度 + p95 对比、
  回滚剧本演练——fail-fast 的另一半是 fast-recover。

## 87. 事件捕获冒泡与 passive 深水

- 三阶段：捕获（window→target）→ 目标 → 冒泡（target→window）；目标阶段
  上按注册顺序执行（捕获监听器不提前）——「同元素上混用捕获/冒泡」的
  顺序问题答案固定，别背错。
- passive：`wheel/touchstart/touchmove` 在 window/document 层默认 passive
  （Chrome 56+）——`preventDefault()` 无效且控制台告警；要自定义滚动
  劫持必须显式 `{ passive: false }`，而**更好的答案是不劫持**（交给
  CSS scroll-driven animations / scrollend）。
- `stopPropagation` vs `stopImmediatePropagation`：前者停传播，
  后者连**同元素剩余监听器**也停——第三方脚本在你之后注册同事件时，
  想确保自己的清理先跑，用 capture 阶段 + immediate。
- `{ once: true }`：一次即卸——现代清理习惯（等价于手动 removeEventListener
  但无「忘卸」风险）；配合 `{ signal }`（22 节）是监听器治理双保险。
- focus 家族分叉：`focus/blur` 不冒泡，`focusin/focusout` 冒泡——
  表单委托用后者；`scroll` 不冒泡（document 除外），element 级滚动
  监听挂捕获或用 `scrollend`。

## 88. 事件委托的边界与 shadow DOM

委托不是万能的：它建立在「事件能冒泡到你挂的那层 + target 信息可用」
两个前提上——shadow DOM 和第三方组件都会拆台。

- shadow DOM 重定向（retargeting）：事件跨 shadow 边界时 target 被**重写为
  宿主元素**，内部真实路径要 `event.composedPath()` 取；`composed: true`
  的自定义事件才能穿边界——发自定义事件时显式声明，否则外部永远收不到。
- 第三方 `stopPropagation` 会掐断你的委托：想在它之前拦截，用**捕获阶段**
  监听（捕获先于其冒泡处理器）——「委托在冒泡层、逃生通道在捕获层」双轨。
- 非冒泡事件（focus/blur/element scroll/load）委托不了——按 87 节换成
  冒泡版（focusin）或 scrollend。
- 动态列表是委托的主场（一行一个监听器的泄漏/分配双杀见 25 节）；
  静态少量元素直接挂，别为了「风格统一」到处代理。
- 委托的记忆寿命：document 级委托监听器**活到页面死**——SPA 路由级
  teardown（AbortController，22 节）依然需要；委托免的是「元素级泄漏」，
  不是「逻辑级泄漏」。

## 89. 观察器时序：IO、Resize、Mutation

| 观察器 | 回调时机 | 合并语义 | 经典坑 |
| --- | --- | --- | --- |
| IntersectionObserver | 布局后（渲染步骤前）按帧批量 | 只报跨阈值变化 | threshold 不连续监听 |
| ResizeObserver | 布局后、绘制前，可能一帧多次 | 目标级聚合 | 回调里改尺寸→循环告警 |
| MutationObserver | **微任务**检查点批量 | 记录合并、无顺序细节 | 回调里改 DOM→自我风暴 |

- IO 的 threshold 语义：只在「跨过阈值」时回调（0.5 从 0.49→0.51 触发一次），
  连续监视要 `threshold: buildSteps(n)` 或直接用 rootMargin 预载。
- RO 循环告警（"ResizeObserver loop completed with undelivered
  notifications"）：回调里读尺寸+改样式 → 再次触发；解法是**批量帧**：
  记脏标记，rAF 里统一应用样式；该错误不崩页面但是布局抖动的显性信号。
- MO 在微任务跑（2 节）：DOM 变更 → MO 回调 → 又变更 → 死循环且**渲染
  永不发生**——MO 驱动的同步器必须有变更指纹（跳过自己造成的变更）。
- `takeRecords()`：同步取走未投递记录（disconnect 前冲刷）——测试与
  「卸载前落账」用；`unobserve` 别忘（泄漏表 21 节）。

## 90. 布局抖动与读写分离

强制同步布局（forced reflow）：**写后读**布局属性（offsetWidth/
getBoundingClientRect/getComputedStyle 的布局相关键）迫使浏览器立刻
结算——循环里读写交替 = 每轮一次完整 layout（layout thrashing）。

```javascript
// 反面：N 次布局
for (const el of list) { el.style.width = target + 'px'; read(el.offsetWidth); }
// 正面：批量——先读光、再写光（读写分离）
const tops = list.map(el => el.getBoundingClientRect().top); // 全部读
list.forEach((el, i) => { el.style.transform = `translateY(${tops[i]}px)`; });
```

- 触发读清单：offsetTop/Width、scrollHeight、clientRects、
  `getComputedStyle().width`（部分键）、`window.getComputedStyle` 后读
  布局值；写清单：样式/class 改动、insert/remove。
- 动画只动 `transform/opacity`（合成器层，不进 layout/paint）；批量更新
  用 `DocumentFragment` 或统一 class 切换（25 节）。
- `will-change` 按需短用：常驻 will-change = 层爆炸（每层显存），
  动画前设置、结束后移除；Layers 面板查层数。
- 诊断：Performance 面板紫色 Layout 条纹成串 = 实锤；DevTools Rendering →
  Paint flashing + Layout Shift Regions 定位 CLS 元凶。

## 91. requestAnimationFrame 对齐与帧预算

rAF 在每帧「渲染步骤」最先执行（样式/布局/绘制之前）——天然的
「下一帧之前改 DOM」通道；但它的节拍假设常被打破。

- 120/144Hz 屏幕：rAF 按显示器刷新率跑（120fps 时每帧 8.3ms）——
  **按帧数做时间预算的动画在新设备上快一倍**：永远用回调的时间戳参数
  做时间驱动（`function step(t) { const dt = t - last; ... }`）。
- 隐藏标签页 rAF 全停：靠 rAF 驱动的状态机/轮询会冻结（92 节）——
  恢复时 dt 巨大，先 `dt = Math.min(dt, 100)` 钳制再积分（物理爆炸防呆）。
- `cancelAnimationFrame` 卫生：组件 teardown 取消未跑的帧——挂起的
  rAF 回调捕获闭包，是「最后一帧泄漏」常客（23 节）。
- 「等到绘制后」惯用式：双 rAF（`requestAnimationFrame(() =>
  requestAnimationFrame(fn))`）——第一帧排 DOM，第二帧确认已绘制；
  框架内部 measure 场景的标配。
- rAF ≠ 高优先级任务通道：它是渲染对齐，不是任务调度——后台工作
  交给 scheduler.postTask background（39 节），两者别混用。

## 92. 后台节流与可见性纪律

- 隐藏页面的节流阶梯：rAF 全停 → 计时器 ≥1s → Chrome 密集节流
  （隐藏 5 分钟后链式计时器 **1 次/分钟**）——任何「后台也要跑」的逻辑
  在隐藏页的 setTimeout 上都是幻想。
- 正确的后台模型：`document.visibilityState` 驱动——hidden 时暂停轮询/
  动画/上报节流，visible 时**重新同步状态**（数据可能已过期几十分钟）；
  配 `visibilitychange` 一次性监听。
- bfcache 时代的冻结态：页面可能被 freeze（往返缓存/系统休眠）——
  网络与计时器全停，resume 事件后校准 `Date`/`Temporal.Now` 与缓存时效；
  监听 `freeze`/`resume` 做状态落地。
- 计时正确性：恢复后别信「上次 tick 时刻 + 间隔」——统一用「目标时刻
  与当前时刻比较」的补偿模式（10 节）。
- 真后台任务的出路：页面不可靠就移出页面——Service Worker（事件驱动）、
  Worker 里的计时（受限但仍优于页面）、或服务端推送后唤醒页面。
- 电池与预算：后台轮询是耗电榜常客——产品层面给「后台刷新」开关，
  工程层面默认关闭。

## 93. postMessage 与传输语义

postMessage 默认 **structuredClone**（51 节语义全部适用）；
transfer 列表把 ArrayBuffer/MessagePort/ReadableStream/OffscreenCanvas
**零拷贝过户**——源头被 detach（72 节）。

```javascript
// 大结果回传：转移（O(1)）而不是克隆（O(n)）
const buf = results.buffer;
worker.postMessage({ done: true, buf }, [buf]);   // 主线程拿到所有权
worker.buf.byteLength;                            // 0——已过户，别再用
// worker→worker 直通：port 转移给对方，不经主线程中转
a.postMessage(port2, [port2]);  b.onmessage = ({ports}) => ports[0].postMessage(...);
```

- 消息协议纪律：信封 `{ type, id, payload }` + 请求响应表（8 节外提
  resolve）+ 超时清理；错误走 81 节信封；**origin/targetOrigin 校验**
  window.postMessage 不可省（安全底线）。
- 克隆成本模型：深对象图 ~ 50-200MB/s 量级——每帧 postMessage 大状态
  是掉帧源；> 1MB 或高频场景一律 transfer 或 SAB 视图。
- `MessageChannel` 与 worker 直通：把 port2 转移给另一个 worker，
  建立点对点管道——主线程不再当中转站（也是 iframe 双向通信的
  标准解法，26 节）。
- `MessagePort` 生命周期：close() 才释放——泄漏形态是「开着没人的通道」；
  onmessage 不设置时消息堆积（在 port 上积压），别用消息队列当存储。

## 94. 存储阻塞模型：localStorage 与 IndexedDB

- localStorage 是**同步** API：5MB 配额、字符串、主线程上 serialize+IO——
  每次写都阻塞事件循环，冷启动读一堆 key 是「白屏 300ms」的经典来源；
  只放启动必需的小配置，其余去 IndexedDB。
- Safari ITP：脚本可写存储在部分场景 7 天过期——「下次回来还登录着」
  的承诺不能建立在 localStorage 上（配 cookie/token 策略）。
- IndexedDB 的事务陷阱：**事务在微任务/事件循环空转时自动提交**——
  循环里 `await` 非数据库 Promise 后再继续用事务 →
  `TransactionInactiveError`；正确模式：一个事务内的操作链不跨 await，
  或显式分批提交。
- IDB 用法骨架：open → onupgradeneeded 建索引（查询靠索引而非游标全扫）；
  游标逐条 `cursor.continue()`（每条一个请求，天然异步分片）；
  键查询用 getAll 而不是循环 get。
- OPFS（Origin Private File System）：大文件/高性能读写的现代位——
  `navigator.storage.getDirectory()`；**专用 Worker 里可用同步访问句柄**
  `createSyncAccessHandle()`（零异步开销的文件 IO）——数据库引擎
  （SQLite WASM）的选择。
- 容量纪律：`navigator.storage.estimate()` 查配额 + `persist()` 申请
  免驱逐；「静默驱逐用户数据」是 PWA 一级事故。

## 95. Node 版本地图：24 LTS 与 26 Current

- 节奏：奇数号 Current 只活半年，偶数号 10 月转 LTS；**生产只上偶数 LTS**。
  当前坐标：Node 22 'Jod'（EOL 2027-04）、**Node 24 'Krypton'（2025-05
  发布，LTS 至 2028-04）**、Node 26 Current（2026-05，Temporal 默认开）；
  Node 20 已于 2026-04 EOL。
- Node 24 关键变化：V8 13.6（`using`/Float16Array/RegExp.escape/Error.isError/
  WASM Memory64）、npm 11、`--permission` 替代实验期 flag、URLPattern 全局、
  test runner 子测试自动等待、AsyncLocalStorage 默认 AsyncContextFrame、
  url.parse() 运行时弃用告警（41-48/100/101 节全覆盖）。
- Node 24.x 后半场（2025Q4+）：`require(esm)` 稳定（24.15）、模块编译缓存
  稳定（24.15）、`module.registerHooks` 同步加载器钩子（58/68 节）。
- Node 26：Temporal 默认启用（V8 14.6：`Map.getOrInsert` 家族 +
  `Iterator.concat`）、Undici 8——写「跨版本兼容库」时用
  `import.meta.resolve`/特性检测而不是硬编码版本。
- 运维纪律：`engines` 字段钉版本 + volta/fnm 管多版本；corepack 已不再
  随新 Node 分发（包管理器用自带脚本安装）；安全补丁看 LTS 点版本
  （如 24.22.x 的 2026-10 安全批次）再升级。

## 96. libuv 阶段与线程池阻塞

六阶段循环：timers → pending callbacks → idle/prepare → **poll** →
check（setImmediate）→ close callbacks；每个回调后：nextTick 队列 →
微任务队列。

- 阶段顺序陷阱：主模块里 setTimeout(0) vs setImmediate 顺序不定；
  I/O 回调内 setImmediate 必先——清空逻辑（flush before next turn）用
  setImmediate（3 节）。
- **同步 fs** 毒化一切：`fs.readFileSync` 占住主线程，连 microtask 都不跑；
  更隐蔽的是 `dns.lookup`（getaddrinfo）与 crypto.pbkdf2 这类
  **线程池任务**：默认池 4 线程，并发 fs 一高、所有线程池 API（含 zlib）
  排队——`UV_THREADPOOL_SIZE=8` 是 fs 密集服务的常见调参，但先测：加大
  线程对同一块盘不增产，瓶颈在磁盘不在池。
- 事件循环滞后监控：`perf_hooks.monitorEventLoopDelay({ resolution: 20 })`
  开直方图 → `percentile(99.9)` 进 metrics——比 p95 响应时间更早暴露
  「内部卡顿」（外部 RT 还没变，lag 先涨）。
- 判定「卡在 JS 还是卡在 IO」：CPU 高 + lag 高 = JS 热循环（4 节）；
  CPU 低 + lag 低 + 吞吐低 = 排队（线程池/下游）；CPU 低 + lag 高 =
  同步等待（错用 sync API / CPU 密集 native 调用）。

## 97. AsyncLocalStorage 与 AsyncContextFrame

ALS = 不改函数签名的「请求作用域」：trace id、租户、用户态贯穿整条
异步链；Node 24 起默认 **AsyncContextFrame** 实现（开销显著低于旧机制）。

```javascript
import { AsyncLocalStorage } from 'node:async_hooks';
const ctx = new AsyncLocalStorage();
server.on('request', (req, res) => {
  ctx.run({ traceId: newTraceId(), tenant: req.headers['x-tenant'] }, () => {
    handler(req, res);   // 内部所有 await 链都能 ctx.getStore()
  });
});
// 深层：const { traceId } = ctx.getStore();
```

- 捕获时机 = **资源创建时**：在 `run` 外创建的定时器/监听器不带 store——
  池化资源（预热连接、预注册监听）在 run 之前创建，读不到当前请求上下文；
  惰性注册或显式传参兜底。
- **池复用串号**是安全级事故：任务队列 worker 先后处理不同租户，
  ALS 在 async 边界自动切换，但**同步缓存的 store 副本**（`const store =
  ctx.getStore()` 存到对象字段）会把上一租户带进下一任务——store 用完即弃。
- 不跨线程：ALS 不进 Worker threads——跨线程传播自己封信封（93 节）；
  跨进程走显式头（traceparent）。
- `diagnostics_channel`：不打 monkey-patch 的观测钩子（发布订阅式）——
  APM 探针优先用它，配 ALS 取上下文，双原语构成 Node 观测骨架。

## 98. stream pipeline 与现代组合子

- 生产底线：**pipeline，不是 pipe**——`pipe()` 不转发错误、不级联销毁：
  源出错时目标挂着等数据，进程表现「安静地什么都不干」；
  `stream/promises` 的 `pipeline(src, ...transforms, dest, { signal })`
  一行解决错误、销毁、取消（33 节）。
- Node 20+ 组合子：`Readable.map(fn, { concurrency })`、`filter/take/drop`
  ——流上的迭代器语义（带并发参数），多与 `stream.compose` 连用；
  用它们替代手写 Transform 的场景占九成。
- Web 桥：`Readable.toWeb(nodeReadable)` / `Readable.fromWeb(webStream)`——
  fetch body ↔ Node stream 的唯一合法通道；方向搞反直接 TypeError。
- objectMode 陷阱：`null` 是流结束哨兵——objectMode 里推 `null` 值会**截断
  流**；数据里的 null 先过滤/映射成哨兵对象。
- `finished(stream, { error })`：旁路观察「完成/出错/销毁」——
  监控与清理钩子；`destroy(err)` 带 err 与不带的语义不同（err 会进
  error 事件），销毁链别乱传。
- 恒定内存纪律：任何「先收集完再处理」的流代码（`for await` 进数组
  再处理）都应被视为待审——背压存在感就是 pipeline 的意义（31 节）。

## 99. 子进程纪律与注入防御

- `exec`/`execSync` 走 shell 字符串拼接——**命令注入入口**，一律禁用；
  用 `execFile`/`spawn` + 参数数组（无 shell 解析）；
  参数里的「用户数据」不是转义问题是**结构问题**：数组参数没有 shell，
  转义失去意义。
- Windows 的 `.bat/.cmd` 逃逸面：它们必须经 shell 解释，Node 官方建议
  **不要用它们执行任何含用户输入的命令**；确需调用，白名单校验后
  `spawn` 显式 `shell: true` + 最小拼接。
- `maxBuffer`（默认 1MB）：输出超限杀进程报错——大输出场景改为消费
  `child.stdout` 流（98 节），而不是调大 buffer 装全量。
- 生命周期：`options.signal`（AbortSignal）→ abort 即 kill；`timeout` 选项
  + `killSignal` 控制超时；Windows 下 kill 只杀壳不杀树——要杀树用
  `taskkill /pid <p> /T /F`（Linux 靠进程组/PR_SET_PDEATHSIG 防孤儿）。
- `error` 事件不是异常：spawn ENOENT 走 'error' 事件——不挂监听直接
  uncaughtException（86 节）；close vs exit：exit 时 stdout 可能没排完，
  收尾逻辑挂 close。
- 管道回压：child stdout 是流——下游慢时要 pause/read，否则管道缓冲膨胀
  （31 节跨进程版）。

## 100. Node 权限模型与供应链防线

`--permission`（Node 24 起 flag 去实验前缀）：默认禁 fs 写/子进程/worker，
按最小授权放行——对「装了 1200 个依赖的服务」而言，它把**依赖作恶半径**
从「整个进程」压到「白名单路径」。

```bash
node --permission \
  --allow-fs-read=/app --allow-fs-write=/app/tmp \
  --allow-child-process --no-worker app.js
# 违规访问运行时抛 ERR_ACCESS_DENIED——CI 里用真实流量回放验证白名单完备性
```

- 威胁模型边界：权限模型管**核心 API**，**native addon 不受限**（直接
  系统调用）——有 addon 依赖时权限模型只防「其它纯 JS 依赖」；
  2026-10 的 CVE-2026-106073（realpathSync 权限检查绕过修复）说明模型
  在持续加固，但别当安全边界唯一层。
- 分层防线：`npm ci`（锁文件安装）+ lockfile-lint + 最小依赖树 +
  provenance 签名校验（`npm audit signatures`）+ `npm ci` 前 registry
  钉住；危险 API 静态扫描（eval/Function/child_process 的直接引用）。
- eval 面收口：CSP（浏览器）与 Node 的 `--disallow-code-generation-from-strings`
  类开关成对上——动态代码生成是依赖链攻击最爱（原型污染 → RCE 的
  最后一跳常是 eval）。
- 运行实践：权限模型配 CI 的「权限矩阵测试」（每个 allow 项一条用例）；
  升级 Node 大版本后白名单行为可能收紧——发布前在目标版本跑一遍。

## 101. node:test 与测试基建

`node --test`（Node 20+ 稳定）：零依赖的核心测试器——2026 年新项目
默认它，jest/vitest 按需加（ESM 原生支持不需要 `--experimental-vm-modules`
的替代方案）。

```javascript
import { test, describe, beforeEach, mock } from 'node:test';
import assert from 'node:assert/strict';
describe('sync', () => {
  beforeEach(() => seed());
  test('cancel propagates', { timeout: 1000 }, async (t) => {
    const fn = t.mock.fn();                       // 用例级 mock，自动还原
    t.assert.snapshot(await run());              // 内置快照（实验期→成熟中）
    const sub = t.test('sub waits automatically'); // Node 24：子测试自动等待
  });
});
```

- Node 24 的新行为：**子测试自动等待**——`t.test()` 返回的 Promise 不
  await 也会等它完成（老版本漏 await = 静默丢失，新版本消掉这个坑）。
- `mock.module(specifier, { namedExports })`：核心级 ESM 模块替身（实验
  期）：绕开「ESM 导出不可 stub」的千年难题（103 节）——依赖它的用例
  标注 experimental 并锁 Node 版本。
- 隔离模型：默认**每文件一个子进程**（--test-isolation=process）——
  进程级隔离挡掉单例串扰；`--test-isolation=none`（单进程全量）作为
  「顺序依赖探测」的冒烟档，专抓「必须隔离才过」的用例。
- 覆盖率：`node --test --test-coverage --test-coverage-exclude=...`；
  输出 spec（人）+ tap（CI 工具链），watch 模式本地循环。

## 102. Worker threads 与克隆成本

- 成本模型：创建 Worker ~ 毫秒级 + 每消息克隆 O(负载)；**池化**是唯一
  生产形态：piscina（池 + transfer 感知 + 基准成熟）或 workerpool；
  任务粒度 > 10ms 才值得进 Worker，小任务的管理开销反噬吞吐。
- 数据策略三档：标量/小对象 → 直接 postMessage（克隆即可）；
  大 ArrayBuffer → transfer 过户（93 节）；共享/双端读写 → SAB +
  Atomics（73 节，需 COOP/COEP）。
- `resourceLimits: { maxOldGenerationSizeMb: 256 }` 给 worker 圈内存栅栏——
  不受控算法（用户上传解析）失败在 worker 内（OOM 退出 → 'error' 事件），
  主进程稳住重启该 worker——比全进程陪葬好一个量级。
- 错误通道：worker 未捕获错误 → Worker 实例 'error' 事件；**exitcode
  非 0 而无 error** = 崩在 native/堆耗尽——两类都要监听，重建策略写清
  （86 节 supervisor 的 worker 版）。
- `postMessage` 的 env 陷阱：worker 继承创建时的 process.env **副本**，
  双向修改不传播——配置热更新要显式消息协议，别假设共享。

## 103. 测试替身边界与假时钟

- 替身四分类：stub（预制应答）/ spy（记录调用）/ mock（带期望的验证）/
  fake（可用的简化实现：假时钟、内存 DB）——**mock 你拥有的边界**，
  第三方内部（私有方法、内部模块）不 mock：包一层适配器，mock 适配器。
- ESM 不可 stub 的真相：模块命名空间是只读活绑定（57 节）——
  `sinon.stub(mod, 'fn')` 对 ESM 直接炸。正解三选：依赖注入、
  `mock.module`/loader 钩子（68/101 节）、msw 在**网络边界**拦截。
- **msw 优于 axios 适配器 mock**：它拦截的是 fetch/XHR 层，保真请求语义
  （header/流/错误形态）——用例测的是「与真实服务器的合约」而不是
  「与某个 HTTP 库的内部」。
- 假时钟陷阱：`vi.useFakeTimers()`/sinon `install()` 后**任何 pending
  Promise 也被冻结**——async 用例要 `await clock.tickAsync(n)`（推进
  计时器**并**冲刷微任务）；只 `tick()` 不 await 的用例测的是「时间没动
  的世界」。
- 假时钟在测试间**必须还原**（afterEach `uninstall()`）——残留假时钟是
  「下一文件偶发超时」的经典幽灵；toFake 列表显式（只 fake `setTimeout`
  时 `Date` 留真）。
- sinon 卫生：`sandbox.create()` + afterEach `sandbox.restore()`；
  getter/setter stub、`callsFake` 异步抛错这三处 API 行为特殊，用例前
  查文档而不是猜。

## 104. 异步测试的错位 rejection 陷阱

- 双协议混用：`test('x', async (done) => {...})`——done 永远不被调
  （或者既 await 又 done 双报错）：框架各自定义哪份是真理，
  一个用例只能一种终结方式。
- 浮动 Promise 的**跨用例污染**：A 用例里没 await 的 rejection 在
  B 用例执行期爆出——「B 挂了但 B 的代码没问题」就是签名；
  排查看时间戳与栈，别改 B 的代码。
- 错位归属：全局 unhandledRejection 处理器**晚于**用例终结才收到
  rejection，多数 runner 把它记到**当前正在跑**的用例头上——
  表象是随机失败；对策：runner 级「每用例后 assert 无全局错误」钩子
  + 用例级 `assert.doesNotReject` 包住已知边界。
- 超时杀用例不杀工作：timeout 只是放弃等待，后台 IO 还在跑并污染后续
  用例——beforeEach 建 AbortController、afterEach `abort()` 全家（22 节
  同款），把「测试内启动的一切」纳入可整肃范围。
- Node 的帮助：`node:test` 的子测试自动等待（101 节）+
  `--test-isolation=none` 冒烟档（101 节）专抓顺序依赖；
  确定性纪律：禁真实 sleep（改等条件，107 节）、禁真实网络（msw）、
  时间与随机注入为用例参数。

## 105. 快照测试的腐蚀治理

快照的价值 = 「变更的显式化」：它告诉**什么**变了，不判断**对不对**——
治理目标是把「对不对」还给评审。

- 规则一：快照 diff 必须进 PR 评审——CI 以 `--ci` 模式跑（新快照即失败），
  更新快照是**独立提交**且 commit message 说明语义变化；
  「快照更新扫地机」提交一律打回。
- 规则二：快最小稳定面——组件快照输出而非整页 DOM（DOM 受宿主样式与
  格式化影响全量漂移）；序列化器要**确定性**：对象键排序、日期冻结
  （假时钟）、随机注入固定 seed——否则快照天然 flaky。
- 规则三：别快照第三方的原始输出（依赖升级即全量 diff）——快照
  「我方包装层的结果」；快照里出现大 JSON dump = 坏味道，改断言关键字段。
- inline snapshot（`assert.inlineSnapshot`/`toMatchInlineSnapshot`）
  让 diff 留在源文件里，评审成本最低——小结果优先 inline。
- 孤儿快照：被删用例的快照文件要随删（工具的 `--ci` 会报 obsolete）；
  治理指标：快照文件周增率、单文件超 500 行告警。
- node:test 内置 `t.assert.snapshot`（实验期）覆盖核心场景；
  大厂级需求（多平台矩阵）再上专用库。

## 106. 属性测试与模糊测试纪律

fast-check（JS 生态标准）把「构造任意样本 + 断言不变式」自动化，
**收缩（shrinking）** 是它的王牌：失败后自动把反例缩到最小可复现。

```javascript
import fc from 'fast-check';
fc.assert(fc.property(
  fc.string({ maxLength: 64 }), fc.integer({ min: 0, max: 1e9 }),
  (s, n) => {
    const round = decode(encode(s, n));
    expect(round.text).toBe(s); expect(round.n).toBe(n); // 往返不变式
  }), { numRuns: 200 });
// 失败输出：最小反例如 s='\\uDC00', n=0 → 立刻定位到代理对边界 bug
```

- 不变式选型：往返律（encode/decode、serialize/parse）、幂等律（f∘f=f）、
  恒等律（reverse∘reverse=id）、**模型对照**（简单模型 vs 复杂实现的
  行为一致——缓存/状态机测法的黄金标准）。
- 生成器要「现实且带边」：`fc.integer()` 全域太散——
  `fc.constantFrom(0, -1, MAX_SAFE_INTEGER, NaN)` 混入边界值；
  自定义 record/arbitrary 组合出领域数据。
- `fc.commands`：状态机命令序列（push/pop/get 随机组合）+ 模型校验——
  专为「顺序性 bug」设计，纯单测摸不到。
- 种子纪律：失败时输出 seed——把 seed 固化成回归用例；
  CI 200 轮、夜间 10k 轮的双档预算。
- 覆盖引导模糊：Jazzer.js（JS 的覆盖率引导 fuzzer，OSS-Fuzz 支持）——
  解析器/协议实现的最终防线：喂随机输入断言「不崩 + 不死循环」。

## 107. E2E 等待条件而非 sleep

sleep 是 flake 之源：睡短了偶发失败、睡长了拖垮套件——**等待可观测的
世界状态**是唯一正解。

```javascript
// Playwright 的 web-first 断言：自动重试到可见/可点
await expect(page.getByRole('button', { name: '导出' })).toBeVisible();
await page.getByRole('button', { name: '导出' }).click();  // click 自带可操作性等待
await expect(page.getByTestId('done')).toHaveText('完成');   // 期望最终态
// 自定义条件：等应用级标志
await page.waitForFunction(() => window.__appState === 'ready');
// 反面：await page.waitForTimeout(2000) —— flake + 慢，代码评审禁词
```

- 等什么有讲究：**用户可见状态**（文案/元素）优于内部状态；网络就绪
  用 `waitForResponse`/route 拦截（mock 兜底保确定），而不是轮询 DOM。
- flake 治理流程：CI 参数 `--repeat-each=20` 量化 flake 率（>0 就修）；
  失败自动带 trace（playwright `trace: 'on-first-retry'`）——
  修复时看 trace 不看猜测。
- 选择器层级：getByRole（a11y 语义，顺带保住无障碍）> getByTestId >
  CSS 链（布局变更即碎）。
- 套件工程：storageState 复用登录态、shard 并行矩阵、
  `--project=chrome` 单浏览器 CI + 多浏览器定期档——把「全组合跑很慢」
  与「主路径必须快」分开预算。
- E2E 只测关键旅程：单测/集成覆盖的分支别再来一遍——E2E 的失败定位
  成本是单测的 50 倍，金字塔不是口号（108 节对照表）。

## 108. 生产反模式速查表

| 反模式 | 后果 | 正解（节） |
| --- | --- | --- |
| `forEach(async ...)` | 漂移 rejection、顺序失真 | for...of / all+map（83） |
| 顺序 await 独立 IO | 延迟叠加 | Promise.all + 并发上限（36/35） |
| then 里再 async | 双重包装、栈断裂 | 全链 async/await（7） |
| `new Promise` 包已有 Promise | 多余 tick + 吞错面 | 直接 return / Promise.try（38） |
| 空 catch / catch(e){} | 事故现场蒸发 | cause 包装 + 上报（80/82） |
| throw 非 Error | 丢栈、不可识别 | 一律 Error 子类（79） |
| fire-and-forget 不留痕 | unhandled 噪音 + 丢任务 | fireAndForget(p) 统一记录（83） |
| 循环里 push 进无界数组当缓冲 | 延迟型 OOM | 有界队列 + 背压（31） |
| await 在循环里改共享态无锁 | 异步竞态 | 信号量/互斥（35） |
| 深拷贝状态做「不可变」 | GC 暴涨 | 结构共享/Immer（52） |
| getMonth/getDay 做业务日历 | 0 起始 + 时区暗坑 | Temporal（41-43） |
| map 事件/节点 → 强引用 | 分离 DOM 泄漏 | WeakMap/AbortController（22） |
| 拿 setTimeout 做调度精度 | 节流下崩溃 | 时间补偿 + yield（10/39） |
| exec 拼用户输入 | 命令注入 | execFile 数组（99） |
| eval/Function(动态串) | RCE 面 + 失优 | JSON.parse/映射表（13/100） |

- 用法：评审对照、新人入职清单、与 112 节清单互补（这张治「写了什么」，
  那张治「漏了什么」）。

## 109. 事故复盘一：微任务饿死心跳

**现象**：监控大盘页放一晚，早晨所有图表冻结、按钮全无响应，但进程
活着、CPU 平稳、无报错；刷新即恢复，隔夜复现。

- 时间线还原：新「实时模式」用 `while (running) { await tick(); }`
  驱动订阅——tick 是纯 Promise 链，零宏任务；
  V8、渲染、计时器全被饿死（4 节），看门狗 setInterval 与业务同页同死，
  外部告警只在「隔壁页」可见。
- 为什么测试没拦住：单测用假时钟（假时钟不经过宿主循环，**结构上测不出
  饿死**）；E2E 只跑 30 秒交互路径。
- 修复：循环边界加 `await scheduler.yield()`（39 节）+ 看门狗移进
  Worker（92 节，与页面解耦）+ 事件循环饱和指标（monitorEventLoopDelay，
  96 节）进监控。
- 防复发：E2E 加「长跑 10 分钟页面仍响应」冒烟档；code review 清单固化
  「循环必须有让出点」（112 节）；lint 无法覆盖语义，靠用例。
- 教训：**await 是排序原语，不是让出原语**——「写异步就顺带让出 CPU」
  是本世纪 JS 最贵的误解。

## 110. 事故复盘二：闭包持有大堆

**现象**：文档编辑器内存每「打开-关闭」一个 20MB 文档涨 15-25MB，
三小时后 tab 5GB 被 OOM kill；三快照法（27 节）锁定 Detached DOM 树
数千棵，全部被 `context` 持有。

- 根因链：事件总线为每个节点注册 `bus.on('change', () => render(node))`——
  ① handler 闭包捕获 render 所在**整个作用域**（含 20MB 文档 buffer 的
  引用，23 节）；② 总线是模块级单例（长生命周期）；③ 关闭文档时只
  清了 DOM，没 off 总线（21 节监听器堆积）——三个条件合谋。
- 为什么堆快照平时没人看：CI 无内存门禁、人工排查只在出事后；
  「打开关闭 20 次」的自动化用例一直标 skip（太慢）。
- 修复：每文档一个 AbortController，注册全部走 `{ signal }`（22 节）；
  闭包只捕获节点 id 字符串，render 走 `byId.get(id)`（解耦捕获）；
  总线 handler 数量指标暴露（`bus.eventNames().length` 告警）。
- 防复发：CI 内存门禁（20 次开关 + measureMemory 增量 < 2MB）；
  「模块级单例上注册闭包」进评审必问项；总线库统一封装 signal 注册。
- 教训：闭包不是免费的——**捕获范围 × 闭包寿命**就是内存成本；
  长寿命总线上的匿名闭包等于「永生的大作用域」。

## 111. 事故复盘三：打包器双重执行

**现象**：埋点 PV 精确翻倍，仅生产环境出现；本地 dev、Storybook 均正常；
全局注册表 `window.__tracker` 存在两个实例（断言日志里两个 init）。

- 根因链：埋点 SDK 经两条 specifier 进入 bundle——业务代码 `import
  'tracker'`（走 package.json main → CJS），另一个新依赖深链
  `import 'tracker/dist/tracker.mjs'`（ESM 子路径）；
  bundler 不归并「解析结果不同的两个 specifier」（65 节）→
  模块代码执行两遍 → 两个单例、两份上报。
- 为什么 dev 正常：Vite dev 按 ESM 实文件逐个加载并归并（预打包依赖），
  prod 的 bundle 才显形——「dev 过 = prod 过」的错觉又一次成立反例。
- 修复：`resolve.alias` 把深链归一到 `'tracker'`；SDK 的 init 加幂等
  守卫（`if (globalThis.__tracker) return globalThis.__tracker`）——
  事故止血靠幂等，根治靠归一，两条都要。
- 防复发：bundle 分析器进 CI（重复包告警：同名同版本出现两次即 fail）；
  `exports` 字段收窄子路径（61 节，砍掉非法深链的可达性）；
  E2E 冒烟断言单例唯一（`window.__tracker.count === 1`）。
- 教训：**模块身份 = 解析后的 URL**——bundler 只信 specifier 结果，
  不信包管理器的「同一个包」直觉。

## 112. 专家级代码评审清单

| 维度 | 必问 | 关联 |
| --- | --- | --- |
| 异步 | 独立 IO 是否并行化？取消是否穿到底？await 是否全覆盖/有无浮动？ | 29/35/83 |
| 错误 | 包装带 cause 了吗？取消被吞了吗？catch 为空吗？ | 80/30/83 |
| 资源 | 监听器/定时器/句柄有 teardown 吗？能用 using 吗？清理幂等吗？ | 22/47 |
| 内存 | 闭包捕获了大对象吗？队列/缓存有界吗？Map 是否该 Weak？ | 23/31/24 |
| 形状 | 热路径入参形状统一吗？有无 delete/混型数组？ | 11/14/15 |
| 模块 | 新增循环依赖了吗？TLA 进库了吗？sideEffects 属实吗？双实例风险？ | 62-66 |
| 时间 | 日期/时区走 Temporal 了吗？墙上时间 vs 时刻分清了吗？ | 41-43 |
| 背压 | 生产快于消费时发生什么？有界队列满的策略是什么？ | 31-35 |
| 安全 | 用户输入进 shell/eval/innerHTML 了吗？postMessage 验 origin 了吗？ | 99/53/93 |
| 测试 | 有真实 sleep 吗？异步边界被 for...of 覆盖了吗？泄漏断言呢？ | 104/107 |
| 可观测 | 新错误类型有指纹前缀吗？关键路径有耗时打点吗？ | 79/82 |
| 契约 | 同步抛还是 reject？副作用幂等吗？默认参数安全吗？ | 6/38 |

- 用法：不是全查——按 diff 面勾选命中维度；「必问项」答不上 = 需要改；
  每季度按事故复盘（109-111 节）增删条目，让清单长在事故上而不是拍脑袋。

