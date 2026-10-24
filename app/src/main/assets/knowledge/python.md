<!-- kb: id=python | role=builtin_coding_python | title=Python 专家知识库 | version=1 -->
# Python 专家知识库

## 必看内容

- **GIL 正在消失，但按节奏来**：3.13 free-threaded 构建为 experimental，
  3.14 达到 PEP 779 "officially supported"（仍是**独立构建** `python3.14t`，
  单线程开销已收窄到 ~5-10%）。选型顺序：IO 密集 → asyncio；
  CPU 密集 → 进程池；只有"多线程共享大状态"才考虑 free-threading
  （见 1-3 节）。
- **取消是结构化并发的地基**：`CancelledError` 继承自 `BaseException`，
  `TaskGroup`/`asyncio.timeout` 全靠取消实现；任何 `except Exception`
  都可能把取消吞成"僵尸任务"——清理逻辑必须重新抛出（见 28-31 节）。
- **3.14 起注解默认延迟求值**（PEP 649/749）：手写 `__annotations__`
  反射、`typing.get_type_hints()` 的代码升级前必读第 5 节；
  `from __future__ import annotations` 字符串 hack 进入退役期。
- **fork 已死**：3.12 起多线程进程里 `os.fork()` 触发 DeprecationWarning，
  macOS/Windows 默认 spawn——所有 multiprocessing 代码按 spawn 心智写：
  模块级可导入 + `if __name__ == "__main__"` 保护（见 43-44 节）。
- **3.13 一次砍掉 20 个 stdlib 模块**（PEP 594）：`cgi`/`telnetlib` 等
  import 即 `ModuleNotFoundError`；在 3.12 CI 打开
  `-W error::DeprecationWarning` 提前一年暴露（见 8-9 节）。
- **uv 是事实标准**：`uv.lock` 进仓库、CI 用 `uv sync --locked` 精确复现、
  monorepo 用 workspace；解释器（含 free-threaded `3.14t`）也交给 uv 管
  （见 78-79 节）。
- **可变默认参数是最高频低级事故**：`def f(x, acc=[])` 的函数级共享状态；
  同族：闭包 late-binding（65）、类属性可变默认（74）、
  `cached_property` 失效（58）。
- **异常三禁**：禁裸 `except:`（吞 `KeyboardInterrupt`/取消）；
  禁吞异常不打日志不再抛；禁 `finally` 里 `return`/`break`
  覆盖进行中的异常（见 89-94 节）。
- **性能先测量后动手**：py-spy 免侵入 attach 生产进程、Scalene 分离
  Python/native 时间、memray 找泄漏；cProfile 的确定性开销会扭曲
  IO/锁竞争代码的结论（见 102-103、62 节）。
- **import 就是执行**：模块顶层代码在导入时运行，循环导入的
  AttributeError 只在特定顺序出现——重活下沉到函数、可选导入用
  `find_spec` 探测（见 10、85、113 节）。
- **类型严格度是渐进战役**：新代码 strict、老代码 per-module override、
  `# type: ignore[错误码]` 必须带码且总量只减不增（ratchet）
  （见 13、23-24 节）。
- **测试债大多来自隐式共享**：session fixture 状态泄漏、顺序依赖、
  mock 未还原——用 `pytest-randomly` + 独立 tmp/事务回滚自检
  （见 95-101 节）。

## 快速索引

| 想找什么 | 去哪一节 |
| --- | --- |
| 3.14 free-threading 官方支持 | 1. 3.14 free-threading 正式支持与决策模型 |
| free-threaded 构建单线程开销 | 2. free-threaded 构建的生产陷阱 |
| JIT 能不能上生产 | 3. JIT 编译器现状与预期管理 |
| t-string 模板字符串 | 4. t-string 模板字符串（PEP 750） |
| 注解延迟求值影响 | 5. 延迟注解求值（PEP 649/749） |
| subinterpreter 用法 | 6. subinterpreters（PEP 734） |
| 3.12 升级注意点 | 7. 3.12 关键变化与迁移 |
| cgi/telnetlib import 失败 | 8. 3.13 移除的死模块（PEP 594） |
| EOL/支持窗口决策 | 9. 版本升级纪律与支持矩阵 |
| 启动慢/导入耗时 | 10. 导入系统与启动开销 |
| PyPy 值不值得用 | 11. 运行时选择：CPython 之外 |
| 检测 GIL 是否启用 | 12. 运行时自省与条件分支 |
| mypy vs pyright 选谁 | 13. mypy/pyright 严格度阶梯 |
| list[Dog] 赋 list[Animal] 报错 | 14. 型变实战：协变、逆变与不变 |
| 鸭子类型怎么标类型 | 15. Protocol 与静态鸭子类型 |
| kwargs 形状类型化 | 16. TypedDict 进阶用法 |
| 装饰器保签名 | 17. TypeVar/ParamSpec/TypeVarTuple 三件套 |
| overload 分支怎么写 | 18. @overload 纪律 |
| 类型收窄守卫函数 | 19. TypeGuard/TypeIs/cast 筛选 |
| type 新语法 | 20. type 别名语句与泛型别名 |
| 回调参数怎么标 | 21. 可调用类型设计 |
| 循环导入的类型解法 | 22. TYPE_CHECKING 与运行时成本 |
| 依赖库没有类型 | 23. typeshed、py.typed 与缺失 stub 策略 |
| 逐步收紧 strict | 24. 类型驱动重构工作流 |
| 事件循环怎么调度 | 25. 事件循环内部机制 |
| coroutine never awaited | 26. 协程、Task、Future 的本质分层 |
| TaskGroup 怎么用 | 27. TaskGroup 结构化并发 |
| CancelledError 被吞 | 28. 取消语义深水区 |
| 取消时还要收尾 | 29. shield 与取消粒度控制 |
| wait_for 还是 timeout | 30. 超时纪律：timeout vs wait_for |
| gather 什么时候还用 | 31. gather 的适用边界 |
| 循环里跑同步代码 | 32. 阻塞调用识别与线程池卸载 |
| 异步上下文管理器 | 33. 异步上下文管理器协议 |
| uvloop 提速 | 34. uvloop 与事件循环替代品 |
| 优雅停机/信号处理 | 35. 优雅停机与信号处理 |
| async generator 清理 | 36. 异步生成器与 aclose |
| 队列积压 OOM | 37. asyncio 队列与背压 |
| 后台任务莫名消失 | 38. create_task 引用丢失事故 |
| 卡在哪看不出来 | 39. 异步调试工具链 |
| async 常见错误 | 40. 常见 async 反模式清单 |

## 1. 3.14 free-threading 正式支持与决策模型

- PEP 779 分阶段落地：3.13 = experimental（可用、无支持承诺）；
  3.14 = phase II officially supported（官方支持，但仍是**独立构建**
  `python3.14t`，需单独安装）。3.14 的 free-threaded 多线程吞吐比
  3.13 快 10-20%，单线程开销从 3.13 的 ~35-40% 收窄到 ~5-10%。
- 运行时检测：`sys._is_gil_enabled()`（3.13+）判断 GIL 是否真的关了；
  构建期用 `sysconfig.get_config_var("Py_GIL_DISABLED")` 区分构建类型。
- 决策树（按序）：IO 密集 → asyncio；CPU 密集 → 进程池；多线程
  共享大状态且 GIL 成瓶颈 → free-threading；依赖不兼容再等一版。
- 镜像纪律：`python3.14` 与 `python3.14t` 是两个解释器——CI 显式
  断言运行模式，防"我以为在跑 free-threaded"的自欺。

```python
import sys, sysconfig
ft_build = bool(sysconfig.get_config_var("Py_GIL_DISABLED"))
if ft_build:
    assert not sys._is_gil_enabled(), "free-threaded 构建但 GIL 在跑"
```

- 事故模式：线程安全代码在 GIL 构建上永远"正确"——数据竞争只在
  free-threaded 构建上显形（2 节）；ft 化上线前先补并发测试。

## 2. free-threaded 构建的生产陷阱

- 生态兼容是主瓶颈：所有 C 扩展必须**显式声明**支持 free-threading；
  numpy/scipy 自 2.1 起有 ft wheel，Cython/长尾库仍在补课。
  PyO3 支持 ft 但 **abi3 wheel 与 free-threading 不兼容**——ft 必须
  版本专用 wheel（109 节）。
- 死锁形态变化：ft 允许多线程同时 attach，但某些操作需要独占解释器
  状态——PyO3 社区已有 3.13 ft 死锁案例（多线程 attach + 单线程
  独占需求互锁）。挂死时先怀疑 C 扩展，再怀疑自己的锁（105 节取证）。
- 性能形态：每次对象操作带原子 refcount 开销；3.14 引入 biased
  refcounting 降低热点对象争用。纯单线程负载迁过去是负收益。
- GC 差异：ft 构建用 stop-the-world GC（3.13 为 mimalloc + STW），
  `gc.disable()` 的语义与 GIL 构建不同——迁移后重测 GC 停顿与
  大堆行为（60 节）。
- 部署：`uv python install 3.14` 可装 ft 解释器（78 节）；混部镜像
  里两个解释器并存，入口 shebang 与 systemd unit 要钉死绝对路径。

## 3. JIT 编译器现状与预期管理

- 3.13 引入 copy-and-patch JIT（PEP 744）：experimental、默认关闭，
  构建期 `--enable-experimental-jit`（或运行期 `PYTHON_JIT=1` 开）。
- 3.14 JIT 仍为 experimental 且持续改进；**free-threaded 构建不支持
  JIT**（两者互斥）——想要 ft 多核吞吐就别指望 JIT 叠加。
- 预期管理：当前 JIT 对宏观工作负载提升有限（个位数百分比），
  定位是长线基础设施；不要为"性能理由"切到 JIT 构建。
- 真正立竿见影的是解释器演进：3.11 特化自适应解释器（PEP 659，
  ~1.25x）+ 3.12 推导式内联（PEP 709）——升 3.11/3.12 的收益
  已经大于 3.13/3.14 的 JIT 选项。
- 决策：生产默认关 JIT；若开启，CI 加 JIT on/off 双跑矩阵
  （JIT 命中行为与异常路径交互仍在演进，别让差异漂进业务逻辑）。
- 与解释器缓存的关系：specializing adaptive interpreter 的
  inline cache 命中率决定 JIT 的边际收益——冷代码路径、
  分支密集路径的 JIT 优势更小，宏观收益看热点形状。

## 4. t-string 模板字符串（PEP 750）

- 3.14 新增 `t"..."` 模板字面量：求值结果不是 str，而是
  `string.tuplet.Template` 对象——静态字符串片段与插值表达式分离。
- 定位：**结构化插值**——消费库拿到 Template 后自行决定渲染策略
  （SQL 参数化、HTML 转义、日志模板），杜绝"先拼字符串再消毒"。
- 不是 f-string 替代品：`str(t)` 不会给出渲染结果，必须显式
  render；f-string 本体在 3.12（PEP 701）已完全语法化——可嵌套、
  可复用引号、f-string 内可用 walrus（73 节）。

```python
from string.tuplet import Template
t = t"SELECT * FROM users WHERE id={uid}"   # Template，不是 str
query, params = render_sql(t)               # 消费方决定参数化方式
```

- 迁移：自写 sql builder/HTML 转义器是第一批受益者；模板引擎
  （Jinja 等）不受影响——t-string 是语言层原语不是引擎。
- 事故模式：f-string 肌肉记忆把 Template 当 str（拼接/比较/写
  header）——类型层即可拦截（Template 不是 str 子类），strict 模式
  必报；运行期用 `isinstance(t, str)` 的防御代码也会正确失败。

## 5. 延迟注解求值（PEP 649/749）

- 3.14 起注解默认**惰性求值**：类/模块持有 `__annotate__` 函数，
  首次访问 `__annotations__` 才求值；`annotationlib.get_annotations(obj,
  format=Format.VALUE|FORWARDREF|STRING)` 提供三种口径。
- `from __future__ import annotations`（全字符串化）进入退役路径：
  新代码不需要；老代码它仍生效并强制 STRING 口径——升级 3.14 时
  统一删除并重测反射路径。
- 自引用类不再需要引号注解；`del` 前向引用的脏 hack 消失。
- 影响面清单：手写 `cls.__annotations__` 反射、按字符串解析注解的
  装饰器、`typing.get_type_hints()` 兼容层——升级前逐个过；
  pydantic/dataclasses/typer 走 annotationlib 正常工作。
- 陷阱：访问 `__annotations__` 现在**可能抛异常**（求值失败此刻才
  发生）——模块顶层别裸碰它；注解带副作用的代码（极少数）求值
  时点改变，测试补上"从未访问注解"的路径。

## 6. subinterpreters（PEP 734）

- 3.14 落地 `concurrent.interpreters` 模块 +
  `concurrent.futures.InterpreterPoolExecutor`：每个 interpreter
  独立 GIL（3.12 PEP 684 的 C-API 之后的 Python 层完成）。
- 隔离铁律：跨 interpreter 只能传 str/bytes/int/None 等不可变
  基本值（pickle 边界）；共享复杂对象是设计错误——"共享一切"的
  多线程心智在这里不成立。
- 定位：进程池的轻量替代（省内存/省启动），不是共享内存并行；
  当前最现实用法是**多租户/插件隔离**——每租户一个 interpreter，
  一个崩溃不拖垮宿主，故障域按租户切分。
- 生态缺口：C 扩展需声明 per-interpreter 支持（模块状态迁移），
  numpy 等大件未完全就绪——上生产前逐依赖验证（1 节矩阵同款流程）。
- 与 free-threading 的关系：互补不替代——ft 是"共享状态多线程"，
  subinterpreter 是"隔离并行"；同一系统按状态耦合度选边。

## 7. 3.12 关键变化与迁移

- 语言：PEP 701 f-string 语法化（嵌套同引号/多行/任意表达式）；
  PEP 695 `type X = ...` 与 `class C[T]` 新泛型语法（17、20 节）。
- 性能：推导式内联（PEP 709，~2x）；immortal objects（PEP 683，
  None/True/小整数不再 refcount 抖动，49 节）；整体比 3.11 快 ~5%。
- 破坏面：`distutils` 移除——依赖裸 `setup.py` 链路的构建直接挂，
  需显式依赖 setuptools；`asynchat/asyncore` 在 3.12 前已移除。
- 新 API：`itertools.batched()`（定长分批，分页/批处理少写一轮）、
  `pathlib.Path.walk()`、`sys.monitoring`（PEP 669，104 节）。
- 迁移纪律：3.12 是"安静的大版本"——多数代码无痛，但构建链
  （distutils）与 `-W error` 级弃用面要专门过一遍再切默认镜像。
- 3.12 迁移检查单：`distutils` 使用点、f-string 嵌套引号重写
  （PEP 701 后可读性升级）、旧 `TypeVar` 声明换 `type`/`class [T]`
  语法（17、20 节）、`itertools.batched` 替换手写分批。

## 8. 3.13 移除的死模块（PEP 594）

- 3.13 一次性移除 20 个 stdlib 模块：`cgi`、`cgitb`、`audioop`、
  `aifc`、`sunau`、`uu`、`xdrlib`、`telnetlib`、`nntplib`、`imghdr`、
  `sndhdr`、`chunk`、`crypt`、`pipes`、`mailcap`、`nis`、
  `ossaudiodev`、`spwd` 等（`msilib` 已在 3.12 移除）。
- 症状：`ModuleNotFoundError` 只在**运行时走到那条路径**才触发——
  单元测试全绿、生产周五夜炸。静态层全仓 grep 这 20 个 import
  名 + 动态层在 3.12 CI 开 `-W error::DeprecationWarning`。
- 官方接盘/替代：`audioop` → `audioop-lts`（PyPI 官方接手）；
  `crypt` → `bcrypt`/`argon2-cffi`；`telnetlib` → `telnetlib3`；
  `imghdr` → `puremagic`；`cgi` 表单解析 → `multipart`/框架自带。
- 镜像注意：老镜像若把 3.11 stdlib 残留打包，`import cgi` 能成
  功——换镜像就炸；用 `sys.stdlib_module_names` 校验一致性
  （12 节），CI 加一行断言目标模块不存在。

## 9. 版本升级纪律与支持矩阵

- 支持窗口：每版本 5 年；2024-10 EOL 3.8、2025-10 EOL 3.9、
  2026-10 EOL 3.10。生产最低支持 = 最新稳定 -2，落后即安全债
  （无补丁的运行时 = CVE 累积器）。
- 升级前置 gate（顺序执行）：
  ① 关键 C 扩展 wheel 矩阵——PyPI 文件页确认 cp3XX 存在
  （真实教训：tensorflow 的 3.13 wheel 迟到数月，强升 = 源码
  编译地狱）；② 依赖树在目标版本可解析（`uv lock --python 3.13`
  试锁）；③ `-W error::DeprecationWarning` 全量测试跑通（8 节）。
- `requires-python = ">=3.11"` 双端约束：CI 只跑最低 + 最高，
  中间版本插值不测——语义陷阱集中在两端（12 节条件分支）。
- 节奏：新版本先以 "allowed failure" 进 CI 观察一两周再转必绿；
  逐版本小步升（3.11→3.12→3.13），跳版本 = 破坏面叠加难归因。
- 沉淀纪律：每次升级把"阻塞项 + 解法 + 耗时"记进升级手册——
  下一版本的 gate 清单就有了底稿，团队记忆不靠口口相传。

## 10. 导入系统与启动开销

- import 就是执行：模块顶层在导入时跑；CLI 冷启动的 80% 通常是
  依赖树 import。测量：`python -X importtime app.py 2>import.log`，
  按 cumulative 列排序找大头。
- 镜像/部署优化：构建期 `python -m compileall .` 预生成 .pyc +
  只读运行层（或 `PYTHONPYCACHEPREFIX` 指可写卷）；
  `PYTHONDONTWRITEBYTECODE=1` 用于写不了 pyc 的只读容器。
- 惰性导入三板斧：PEP 562 模块级 `__getattr__`（重库延后到首次
  属性访问）、`TYPE_CHECKING` guard（22 节）、函数内延迟导入
  （同时也是循环导入解法，85 节）。
- 少用 `importlib.util.LazyLoader`：报错时机漂移到首次属性访问，
  排障成本高于收益——只留给"可选重依赖"。
- 事故模式：spawn 启动的多进程 worker 各自重 import（43 节）——
  worker 启动时间 = 进程池预热时间；大模型加载放 `initializer`
  而不是模块顶层（45 节）。

## 11. 运行时选择：CPython 之外

- PyPy：纯 Python CPU 密集热点最佳（JIT 3-10x），但经 cpyext 调
  C 扩展有重税——numpy 大数组操作常**更慢**（桥接吃掉收益）；
  需分钟级 warmup，短命 CLI 进程不适用。
- GraalPy：嵌入 Java 生态/多语言互操作有优势；兼容性矩阵独立
  维护；WASM 目标的微运行时面向边缘与沙箱，不是服务端选项。
- 决策法：拿**生产负载回放**测（107 节），不用合成 benchmark；
  切运行时 = 接受生态摩擦（C 扩展、调试器、profiler、free-threading
  全部重新评估）——收益必须 ≥2x 热点加速才值得立项。
- 可移植性纪律：代码里避免 CPython 实现细节依赖——refcount
  假设（49 节 sys.getrefcount）、`gc.set_threshold` 微调、
  `sys.intern`、frame locals hack，这些都是迁移时的地雷清单。
- 认真评估非 CPython 的三个前置信号：纯 Python 热点占比 >80%、
  C 扩展依赖面窄、进程长寿命可摊销 warmup——三条都满足
  才值得立项，否则收益不抵摩擦。

## 12. 运行时自省与条件分支

- 特性检测优先于版本比较：`hasattr(sys, "_is_gil_enabled")`、
  `importlib.util.find_spec("tomllib")`（None = 不可用）、
  `try: from asyncio import timeout`。
- `sys.version_info >= (3, 13)` 只在**语义行为差异**处用（如
  TimeoutError 合并），旁边必须注释原因——裸版本分支三个月后
  没人敢删。
- 打包矩阵：`sys.implementation.name`（cpython/pypy）+
  `sys.version` + `Py_GIL_DISABLED` 进启动日志/结构化 metrics——
  事故复盘的第一手数据。
- CI 钉住假设：ft 构建断言 `sys._is_gil_enabled() is False`；
  普通构建断言解释器与预期一致（1 节）——防镜像/标签漂移。
- 可选依赖统一入口：`find_spec` 探测 + 缺失时给可行动报错
  （"请安装 pkg[async]"），别把裸 ImportError 甩给用户。

## 13. mypy/pyright 严格度阶梯

- pyright 比 mypy 快 3-5x（大库体感明显）、推断更激进（双向
  推断、字面量推断、更准的泛型绑定）；mypy 默认更宽松、老库
  迁移摩擦小。新星：Astral `ty`（Rust、极快、功能补齐中）、
  Meta `pyrefly`；basedpyright = pyright 的 strict-by-default
  分叉 + 额外规则集。
- 严格度：pyright off → basic → standard → strict（strict 全开
  reportUnknown*/reportMissingTypeStubs）；mypy `strict = true` +
  `[[tool.mypy.overrides]] module="legacy.*" ignore_errors=true`
  按包放行。
- 纪律一：**一库一 checker**——两个 checker 对同一代码报不同
  口径的错，修不完且团队分裂；换 checker 是一次性迁移项目。
- 纪律二：`# type: ignore[错误码]` 必须带码；CI 统计 ignore 总数
  做 ratchet，只降不升（24 节）。
- 语义差异高频区：overload 歧义判定、泛型绑定推断、
  `__init__` 返回 None——跨 checker 迁移时人工过这三类。

## 14. 型变实战：协变、逆变与不变

- 三句话记牢：`Callable` 对参数逆变、对返回协变；`list[T]` 不变
  （能读能写）；`Sequence[T]` 协变（只读出口）。
- 接口设计准则：入参吃 `Iterable[T]`/`Sequence[T]`（接受更宽），
  出参给 `Sequence[R]`/`tuple[R, ...]`（承诺更窄）；真要可变集合
  才写 `MutableSequence`（不变，双向都要精确）。
- 自定义泛型的两套口径：老式 `TypeVar("T", covariant=True)` 显式
  声明型变——协变参数**禁止出现在方法参数位**（mypy/pyright 都报）；
  PEP 695（3.12+）`class Repo[T]` 改为**按使用位置自动推断型变**——
  混用两种口径是迁移期大坑，一个类只用一种。
- 为什么不变是安全默认：`list[Dog]` 赋给 `list[Animal]` 后
  `append(Cat)` 在运行时制造类型谎言——不是 checker 挑刺。
- 实战陷阱：返回类型想从 `list[T]` 改成迭代器时，出口声明
  `Sequence[T]` 一开始就留了余地；协变出口 + 内部不变集合时
  在边界一次性 cast 换接口，别让 cast 在业务代码里蔓延。

## 15. Protocol 与静态鸭子类型

- 结构类型：实现方**不需要显式继承** Protocol，匹配由类型检查器
  按属性形状比对；`Protocol` 类本身不能实例化。
- `@runtime_checkable` 的 isinstance 只检查**属性存在**，不查签名
  与类型——"静态满足"与"运行时通过 isinstance"是两个不同强度的
  命题，别拿 runtime_checkable 当类型担保。
- 回调优先用 callback protocol（带 `__call__` 的 Protocol）而不是
  `Callable[...]`：能表达关键字参数、默认值、overload（21 节）。
- 演进陷阱：给 Protocol **加方法** = 全部隐式实现者同时违约，
  且没有任何报错指向具体类——用新 Protocol 继承旧 Protocol +
  版本化命名（`SinkV2`），或提供默认实现 mixin。
- 显式声明意图：`class MySink(Sink, Protocol)` 让静态检查确认
  "我确实实现了这个形状"——防属性拼写错误静默通过；生产库
  推荐显式（文档 + 检查双收益）。

## 16. TypedDict 进阶用法

- 定位：dict 形状的静态描述——运行时就是普通 dict：不能
  isinstance、没有额外校验；要运行时校验上 pydantic/msgspec，
  TypedDict 只服务类型层。
- 键的全/缺：默认全 Required；`NotRequired[K]`（3.11+）标注可缺键；
  继承时 required 语义不可与父类冲突（收窄全键会报错）。
- 3.12+ `**kwargs: Unpack[TD]` 把关键字参数形状化——大量
  `**kwargs: Any` 的库出口从此可检查；配 `@overload` 表达变体。
- 陷阱：字面量里多出的键 pyright 会报（规范如此），mypy 默认
  不查 extra keys——双 checker 口径先统一再迁移（13 节）；
  `dict[str, X]` 与 `TD` 在类型收窄后互相赋值要显式 cast。
- 组合：把"必填核心 + 可选扩展"拆两个 TypedDict（`Required[
  Ext]`/`NotRequired` 组合），比一个巨型 TypedDict 好维护；
  嵌套结构用 `TD` 字段复用，别造字符串约定。

## 17. TypeVar/ParamSpec/TypeVarTuple 三件套

- PEP 695 新语法（3.12+）：`def mapped[T](xs: list[T], f:
  Callable[[T], T]) -> list[T]`、`class Repo[T: Entity]`——
  作用域局部，消灭模块级 `T = TypeVar("T")` 的命名污染；
  老语法仍可用，新代码统一新语法。
- `ParamSpec`（`**P`）为装饰器而生：保住被装饰函数完整签名
  （含 kwargs）；配 `Concatenate` 表达"装饰器吃掉前 N 个参数"
  （注入 request/context 的中间件）。
- `TypeVarTuple`（`*Ts`）：同质变长泛型——形状类型库（numpy 静态
  形状、dataclass 转换器）才用；业务代码出现它通常是过度设计。
- 陷阱：TypeVar 出现一次 = "任意但固定"，出现两次 = 必须一致；
  pyright 推断更激进（可能解出意外绑定），mypy 保守报
  `Never`/无法满足——跨 checker 迁移的高发差异区（13 节）。
- bound vs 枚举约束：`T: Entity`（上界）比 `T: (int, str)`（枚举）
  常用十倍；出现枚举约束时优先想想是不是该用 overload 分支。

## 18. @overload 纪律

- overload 是**给类型检查器的多态声明**，运行时只剩实现函数
  （实现体做 union 分发或 assert）；变体必须被实现完全覆盖，
  否则 checker 报 unreachable/不一致。
- 避免变体重叠歧义：pyright 取第一个匹配静默放过，mypy 可能
  报 overlap——按"最具体在前"排序并让变体互斥（Literal 分流）。
- 标准形态：`Literal["rb"]` → 二进制流类型、`Literal["r"]` →
  文本流类型；`str | Path` 语义不同时别硬塞一个签名。
- 装饰器 + overload：overload 声明在最外层，装饰器本体用
  ParamSpec 保形（17 节）。
- 隐藏实现：`del _impl` 让运行时命名空间只剩 overload 声明
  （typeshed 手法）；应用代码一般不这么干——实现直接可调用，
  overloads 只是它的说明书。
- 运行时自检：`typing.get_overloads(f)`（3.11+）在测试里断言
  overload 变体被实现完整覆盖——防"声明了但实现漂移"。

## 19. TypeGuard/TypeIs/cast 筛选

- `TypeGuard[X]`（3.10+）：守卫返回 True 时**正分支**收窄为 X，
  负分支**不收窄**（"不是 X"信息缺失）——半边语义，老代码里
  大量存在是历史原因。
- `TypeIs[X]`（3.13，PEP 742）：对称收窄——True 收窄 X，False
  尽力排除 X（要求返回 bool）；新代码一律 TypeIs。
- 守卫函数必须纯净：无副作用、确定性——checker 把它当谓词，
  抛异常 = 调用方出现双重错误路径；副作用挪出去。
- `cast(Target, val)`：零运行时检查、只改静态视图——专用于
  "我知道运行时类型但系统不知道"的边界（反序列化出口、
  C 互操作、测试构造）。
- 运行时保险优先级：`assert isinstance(x, T)`（-O 移除，调试期
  真检查）> cast > `# type: ignore[码]`；ignore 是最后手段。
- isinstance 与 TypeIs 组合：函数体可先廉价 isinstance 粗筛、
  再属性级精确判断——守卫是普通函数，两种手段自由组合。

## 20. type 别名语句与泛型别名

- 3.12 `type Alias[T] = Callable[[T], T]`：惰性求值（右式首次
  需要时才求值）+ 作用域安全（别名内引用外层名字不需要引号）；
  运行时对象是 `typing.TypeAliasType`。
- 与旧写法并存矩阵：字符串别名（全兼容、运行时反射可解析）、
  `X: TypeAlias = ...`（3.10+）、`type` 语句（3.12+）——新代码用
  `type` 语句；会被 `get_type_hints` 消费的运行时代码暂时留字符串。
- 泛型别名与 ParamSpec 组合：`type Handler[**P, R] = Callable[P,
  Awaitable[R]]`——复杂别名先 `reveal_type()` 确认两边 checker
  都能解（17 节差异区）。
- 命名纪律：别名是 API——公开别名要有语义（`type Seconds = float`
  优于 `type FloatT = float`）；私有别名过度抽象 = 没人敢删。
- 迁移探测：`isinstance(X, typing.TypeAliasType)`（3.12+）区分
  新旧别名——反射库按双轨处理并对旧形态发 deprecation 警告。

## 21. 可调用类型设计

- 表达力阶梯：`Callable[[int], str]`（位置参数、无默认值）→
  callback Protocol（kwargs/默认值/overload/方法语义）→
  `Callable[..., R]`（任意签名逃生门，strict 模式慎用）。
- 公开 API 用 callback protocol，内部回调用 Callable——
  protocol 名字本身成为文档（`class ProgressReporter(Protocol)`）。
- 返回可调用：类型写全 `Callable[[T], AsyncIterator[T]]`——
  别让 `Callable[..., Any]` 从工厂函数扩散进业务层（Any 传染）。
- `Concatenate[Ctx, P]` 标注"装饰器注入首参"——调用方看到签名
  少一个参数，是中间件/依赖注入的类型基础。
- 陷阱：`Callable[[A], R]` 不接受 `**kwargs` 传参——要表达
  "接受 A 且允许额外 kwargs"必须 protocol（配 16 节 Unpack）。
- 默认值表达：回调带默认参数时 `Callable` 写不出来——callback
  protocol 能把默认值写进签名，调用方的补全/提示才完整。

## 22. TYPE_CHECKING 与运行时成本

- `if TYPE_CHECKING:` 块**只在类型检查期执行**——专治循环导入的
  类型引用（85 节）与"只为注解而 import"的重库。
- 消费方判定规则：注解只给静态 checker 看 → TYPE_CHECKING 安全；
  注解会被运行时求值（pydantic/dataclasses/typer/click、
  `typing.get_type_hints`）→ 必须真导入，否则 NameError。
- 暗坑时间线：`from __future__ import annotations` 把一切注解
  字符串化，曾"意外"掩盖运行时求值需求——强库自愈（pydantic
  主动解析），自研反射装饰器直接炸；3.14 惰性注解后口径再变
  （5 节）：TYPE_CHECKING 里的名字默认解析不出，需 FORWARDREF
  口径显式处理。
- typing 导入成本本身很小（依赖树通常早已拉起）——别为省
  import 把注解写错；真正贵的是注解**求值**不是 typing 名字。
- 检验清单：注解会被运行时消费吗？会 → 禁 TYPE_CHECKING；
  导入重吗？重 → 延迟导入；是循环导入吗？→ 85 节三选一。

## 23. typeshed、py.typed 与缺失 stub 策略

- PEP 561：包带 `py.typed` marker = 声明"内联类型可信"；
  stub-only 包（`types-requests` 等）只为第三方补类型；内联优于
  stub-only——单一事实源，升级不漂移。
- checker 配置收敛：mypy 按包 override `ignore_missing_imports`/
  `follow_imports = skip`；**禁止全局 ignore_missing_imports**
  ——等于给整棵依赖树发 Any 豁免，Any 顺调用链传染。
- 手写 stub：`stubs/` 目录 + `mypy_path = "stubs"`（pyright:
  `stubPath`）；`stubgen -p pkg` 起稿后人工收敛签名——一次
  成本换类型安全，比满库 Any 便宜。
- 有害 py.typed：包带 marker 但注解全是 `Any`——比没有更糟
  （checker 静默采信）。体检法：strict 下看依赖代码的
  reportUnknown 报告量，爆表就上 stub 隔离。
- 锁 checker 行为：CI 里 pin checker 版本（pyright 更新会带来
  新规则报错），升级 checker 与升级依赖同一纪律（88 节）。

## 24. 类型驱动重构工作流

- 渐进战役标准节奏：新文件全 strict → 老模块 per-module override
  放行 → 每次重构"顺路"吞并 1-2 个文件 → ignore 计数 ratchet
  只降不升（CI 脚本统计 `# type: ignore` 并与基线比对）。
- `reveal_type(x)`（pyright/mypy 均识别）当"类型打印"用——
  修报错前先 reveal 现状，推断靠猜是重构事故之源。
- 重构安全网顺序：先给对外 API 补 Protocol（15 节）+ 装饰器
  签名（17 节），再动内部实现——类型错误成为实时回归测试。
- 机械改动用 AST 工具（libcst），不用正则——正则改代码必然
  漏多行注解、装饰器与字符串。
- 度量：strict 覆盖率（strict 通过的文件占比）进工程周报，
  与测试覆盖率同权重；ignore 数量做 burn-down 图。
- 度量三件套：strict 文件占比、ignore 总数、报错清零时长
  （issue 从开到关的中位时间）——类型债也要可视化还债。

## 25. 事件循环内部机制

- selector 循环：epoll/kqueue/IOCP（selectors 模块抽象）轮询 IO +
  ready 队列（`call_soon` 入队）+ 定时堆（`call_later/at` 按
  `loop.time()` 单调时钟排序）；`_run_once` = poll(最近到期
  间隔) → 处理就绪回调 → 跑到期定时器。
- 一切回调都在**循环线程**执行——跨线程只有两个合法入口：
  `loop.call_soon_threadsafe()` 与 `asyncio.run_coroutine_threadsafe()`
  （拿回 concurrent.futures.Future）；在别的线程裸 `create_task`
  是未定义行为（99% 表现为偶发丢任务）。
- 时钟纪律：定时一律 `loop.time()`/`time.monotonic()`——
  `time.time()` 的 NTP 跳变让定时器倒退、负 delay。
- debug 开关：`loop.set_debug(True)` + `loop.slow_callback_duration
  = 0.1`——慢回调告警是阻塞调用第一探测器（32 节）。
- Handle 取消：`call_later` 返回 Handle，`.cancel()` 撤销——
  重连/退避定时器旧 Handle 不取消会双触发（重试风暴，92 节）。

## 26. 协程、Task、Future 的本质分层

- 协程对象是**惰性**的：`async def f()` 调用只造对象不执行；
  `RuntimeWarning: coroutine 'f' was never awaited` 是最响的
  免费告警——CI 开 `-W error::RuntimeWarning` 逮它。
- Task = 驱动协程到完成的调度单元（协程 + 状态机）；
  `create_task` 只入队，真正开跑要等循环获得控制权——
  `await asyncio.sleep(0)` 显式让位（测试控制时序用）。
- await 同一协程两次 → RuntimeError（一次性迭代器）；存协程
  复用是设计错误，要复用存 Task 或 async 工厂。
- Future 是"结果占位符"，不含代码；`asyncio.gather` 返回聚合
  Future；底层库产出 Future、上层消费 Task——分界写进架构文档。
- `ensure_future` 是兼容层遗留，新代码统一 `create_task`
  （显式 Task 语义 + name 参数）。
- "已启动未让位"窗口：create_task 后立刻返回的协程，在首次
  await 前不响应取消——紧循环里批量创建再统一 await，
  会积累这个不可取消窗口（28 节语义的体感来源）。

## 27. TaskGroup 结构化并发

- 3.11+ 标准结构化并发：`async with asyncio.TaskGroup() as tg:
  tg.create_task(...)`——离开 with 前全部完成；任一任务抛错 →
  组内其余任务被**取消** → 以 `ExceptionGroup` 抛出（89 节）。
- 与 gather 的本质差异：gather 不取消兄弟任务（先出错也等其余
  跑完）、`return_exceptions=True` 吞结构；TaskGroup 默认
  fail-fast + 全组清理——"要么全成、要么全清"的业务默认心智。
- 命名：`tg.create_task(coro(), name="ingest-1")`——
  `asyncio.all_tasks()` 与取消告警可读（39 节）。

```python
async with asyncio.TaskGroup() as tg, asyncio.timeout(30):
    for src in sources:
        tg.create_task(sync(src), name=f"sync-{src}")
```

- 收尾预算：`asyncio.timeout` 包住整个 TaskGroup 触发全组取消
  ——"总 deadline"的标准写法（30、93 节）。
- 陷阱：捕获组异常用 `except*`——`except Exception` 捕不到
  ExceptionGroup（89 节）；吞错后必须 re-raise 保持失败语义。

## 28. 取消语义深水区

- `CancelledError` 继承 `BaseException`（3.8+）——`except Exception`
  不会误捕取消；但裸 `except:` 与 `except BaseException:` 仍会，
  它们是生产禁用写法。
- 取消是**协作式**的：`task.cancel()` 只设标志 + 在下一个 await
  点注入异常；没有 await 的纯 CPU 协程不可取消（32 节阻塞同罪）。
- 3.11+ 取消计数：`task.cancelling()`/`task.uncancel()`——库代码
  在 `except CancelledError` 里**吞取消**前必须 `uncancel()`，
  否则上层 timeout/TaskGroup 判定混乱（官方文档要求；漏掉 =
  超时不报超时的诡异 bug）。
- 库铁律：不吞取消。清理逻辑每步小 await（可中断）或不可中断
  收尾用 shield（29 节）。
- 事故模式：`try: await x() except Exception: log` 在取消时刻
  先进 except、清理 await 又被二次取消——清理半途死、半开连接
  泄漏（33 节 finally + shield 收尾）。

## 29. shield 与取消粒度控制

- `await asyncio.shield(cora)` 的准确语义：取消打到外层时，
  内部任务**继续执行**，但外层 await 点照样抛 CancelledError
  ——"保护任务不保护等待者"，一半的人理解错。
- 正确姿势（取消时也要完成的收尾）：把关键操作包成独立任务
  再 shield 等待；或 fire-and-forget + 强引用集合（38 节）+
  完成回调记日志——两者都别忘异常消费。

```python
async def shutdown(db):
    await asyncio.shield(db.commit())   # 取消也把事务落盘
```

- shield 后外层已取消退出：内部任务成为无主引用——挂进长生命
  周期引用集，否则被 GC（38 节）或被进程关闭截断。
- anyio 视角：cancel scope 提供"范围内取消 + 屏蔽"的显式层级，
  比裸 shield 可组合；跨 trio/asyncio 的库用 anyio 原语。
- 反模式：把 shield 当"忽略取消"装饰器全包业务——取消语义被
  系统性破坏，上层超时全部失效（30 节外层 timeout 永远等不到
  取消完成）。

## 30. 超时纪律：timeout vs wait_for

- `asyncio.timeout()`（3.11+）：`async with` 结构、可嵌套
  TaskGroup、开销小；超时触发内部取消并抛 `TimeoutError`
  （3.11 起 asyncio.TimeoutError 与内置合一）——**首选**。
- `asyncio.wait_for(task, t)` 语义差异：超时时**取消任务并等待
  它处理完取消**才抛——被等待方清理慢（finally 里多个 await），
  wait_for 就跟着慢；清理要单独预算就嵌套 timeout。
- 超时不是强杀：目标协程吞取消（28 节）就永远卡在
  `async with asyncio.timeout` 的退出上——库必须保证取消路径
  干净，这是调用超时 API 的隐含契约。
- 嵌套超时：内层（更短）先炸；deadline 传播用剩余时间：
  `asyncio.timeout(deadline - loop.time())`（93 节）。
- 3.10 及以下兼容：`wait_for` + 手动计时；`asyncio.timeout` 是
  3.11 硬边界，`requires-python` 如实声明（9 节）。

## 31. gather 的适用边界

- gather 仍然正确的场景：N 个**相互独立、错误聚合消费**的调用
  ——如并行读三个缓存/微服务，`return_exceptions=True` 手动分类
  重试（94 节），谁失败不影响谁。
- gather 不结构化：第一个异常抛出时**兄弟任务继续跑**（无取消），
  调用方拿到异常时其余结果丢弃、任务可能还在改共享状态——
  泄漏面要调用方自己管。
- `return_exceptions=True` 时 gather 永不抛——异常以值形式混在
  结果里，忘了检查 = 静默吞错（日志都没有）；聚合处必须有
  分拣代码。
- 低层 `asyncio.wait`（3.11+ 只接受 Task）用于 FIRST_COMPLETED/
  FIRST_EXCEPTION 轮询——竞速、任一成功即返回的模式。
- 升级路径：代码里每处 `gather(...)` 审一遍"失败时兄弟该不该
  死"——该死改 TaskGroup，独立才留 gather；评审时这是一条
  checklist（115 节）。

## 32. 阻塞调用识别与线程池卸载

- 判定标准：任何同步操作超过 ~100ms 都算阻塞——循环停摆期间
  所有定时器/心跳/其他任务延迟叠加。常见元凶：大 JSON parse、
  灾难回溯正则、大文件同步 IO、CPU 循环、同步 DB 驱动。
- 卸载通道：`asyncio.to_thread(fn, *a)`（3.9+，默认池
  `min(32, cpu+4)`）；长阻塞任务用自定义 ThreadPoolExecutor
  分组——默认池被占满会饿死其他 to_thread 用户（打满后无
  排队上限告警）。
- CPU 密集：线程池无益（GIL）→ `ProcessPoolExecutor` +
  `loop.run_in_executor`（43 节）；大 numpy 段实际在 C 里释放
  GIL，线程池可用（112 节验证释放）。
- 检测：`loop.set_debug(True)` 打 "Executing ... took X seconds"
  （阈值 `slow_callback_duration`）；py-spy 看循环线程栈长时间
  停在用户代码（102 节）。
- 文件 IO 别教条异步：aiofiles 只是线程池包装；本地 SSD 小文件
  同步 read 更快（省线程跳转）——量测后决定，别按信仰。

## 33. 异步上下文管理器协议

- 协议：`__aenter__`/`__aexit__`；`@asynccontextmanager` 生成器
  写法——yield 前是进入、后是退出；`AsyncExitStack` 动态组合
  （连接数随配置变的场景）。
- 取消撞上 `__aexit__`：收尾 await 可能被**二次取消**打断——
  关键收尾（提交/刷盘）shield（29 节），普通收尾设计为可重入
  （关两次无害）。
- 生成器 CM 铁律：GeneratorExit 注入后**不能再 yield**（
  RuntimeError: async generator ignored GeneratorExit）——finally
  里只能同步收尾或 shield await。
- 所有权转移模式：`AsyncExitStack.enter_async_context` 把资源
  从"获取者栈"转到"后台任务栈"——连接交接给后台 task 的标准
  姿势，避免双 close 竞态。
- 陷阱：`__aexit__` 抛异常会**替代**原始异常（除非返回 True）——
  收尾错误要 `raise ... from exc` 保留因果（91 节）。

## 34. uvloop 与事件循环替代品

- uvloop = 基于 libuv 的事件循环：`uvloop.run(main)` 或
  `asyncio.set_event_loop_policy(uvloop.EventLoopPolicy())`；
  TCP/定时器密集服务吞吐 2-4x，uvicorn `--loop uvloop` 一键启用。
- 差异面（可移植库必须知道）：不支持 Windows（IOCP 不可用）、
  部分 debug API 细节不同、信号/子进程支持差异——库代码留
  policy 后门：

```python
def run(main):
    try:
        import uvloop
        return uvloop.run(main)
    except ImportError:
        return asyncio.run(main)
```

- 收益分布：IO 重网络服务显著；CPU/DB 为主的服务收益微小——
  上线前后用 py-spy + 宏观压测对比（107 节），别按信仰切换。
- 与 free-threading 的关系：uvloop 的 GIL 释放窗口仍是优势；
  ft 构建上 uvloop 兼容性按版本验证（1 节生态矩阵）。

## 35. 优雅停机与信号处理

- Unix 信号接进循环：`loop.add_signal_handler(signal.SIGTERM, cb)`
  ——回调在**循环线程**执行（从信号处理器的任意线程直接执行
  是大坑）；Windows ProactorEventLoop 不支持 add_signal_handler，
  只能 `signal.signal`（主线程限制）——跨平台要封装。
- 标准停机序列：停止接入（关 listener socket）→ 在飞任务排空
  预算（TaskGroup + timeout，27/30 节）→ 关资源（连接池
  `close()` + await）→ loop 退出。
- `asyncio.run` 退出时取消剩余任务并等它们处理取消——没接住
  的取消会打 "unhandled exception during asyncio.run() shutdown"，
  它是泄漏证据，不是噪音，修掉它。
- K8s 配对：terminationGracePeriodSeconds > 排空预算 + 资源
  关闭时间；收到 SIGTERM 先让探针失败（LB 摘流量再排空）。
- 事故模式：`add_signal_handler` 在非主线程注册 → RuntimeError；
  supervisor 双重转发信号（wrapper 脚本）→ 信号永远到不了
  Python——容器入口用 exec 形式（`exec python app.py`）。

## 36. 异步生成器与 aclose

- 生命周期：异步生成器由循环的异步 GC 特殊处理——进程退出时
  循环统一 `aclose()`；看到 "async generator was ignored
  GeneratorExit"/"was garbage collected" 警告 = 你泄漏了未消费
  完的生成器——消灭它，别静音。
- 手动驱动：`aiter()`/`anext()`（3.10+）实现自定义消费节奏
  （背压、批量窗口）；`aclose()` 在挂起点注入 GeneratorExit
  ——finally 段收尾同 33 节铁律。
- 管道模式：async gen → gen → 消费者全链 O(1) 内存——替代
  `list(...)` 的自然流式；配 Queue + worker 池时用 37 节背压，
  别无界拉取。
- 陷阱：`async for` 中 break/异常退出后必须 `aclose()`——
  `contextlib.aclosing()`（3.10+）是标准写法；否则生成器活到
  GC 时刻才收尾，资源占用时间不可控。
- 混排注意：async gen 里 `yield from` 不可用——`async for` +
  逐个 yield 转发；产出普通生成器元素与 async 语义混淆是
  接口设计问题，用显式类型别名区分。

## 37. asyncio 队列与背压

- `asyncio.Queue(maxsize=N)`：`await put()` 在满时挂起生产者——语言
  级背压；**默认 maxsize=0 无限**，OOM 事故标配，必须显式定容。
- 3.13 新增 `Queue.shutdown()`：等待者立刻收到 `QueueShutDown`——
  优雅停机替代"灌 N 个哨兵"的脆弱 hack（哨兵数须配平的祖传坑）。
- worker 池标准形：N 个 worker 任务 + TaskGroup + 有界队列；
  停机序：生产者收口 → `queue.shutdown()` → worker 收到
  QueueShutDown 退出 → TaskGroup 汇合（27 节）。

```python
async def worker(q: asyncio.Queue[Item]):
    while True:
        try:
            item = await q.get()
        except asyncio.QueueShutDown:
            return
        await process(item)
```

- 观测：队列深度 + put 等待时间是循环健康度第一指标，暴露成
  metrics；p99 put-wait 上升 = 消费端变慢，先查下游。

## 38. create_task 引用丢失事故

- 官方文档明确警告：事件循环对任务只持**弱引用**——
  `async def main(): t = asyncio.create_task(bg())` 无强引用时，
  bg 可能在任意 GC 点被静默回收：无异常、无日志、无尸体。
- 标准样板（所有 fire-and-forget 必须走）：

```python
_bg: set[asyncio.Task[None]] = set()

def spawn(coro, name: str) -> asyncio.Task:
    t = asyncio.create_task(coro, name=name)
    _bg.add(t)
    t.add_done_callback(_bg.discard)   # 防引用集自身泄漏
    return t
```

- done callback 里先 `t.exception()`（消费异常）再 discard——否则
  "Task exception was never retrieved" 只在 GC 时打印，线上看不到。
- 工程化：项目统一 `spawn()` 封装（命名/引用/异常日志/并发上限），
  禁止裸 `create_task` 散落业务代码（40 节）。
- 同族：`loop.call_later` 的 Handle 丢失同理——回调闭包引用的任务死了，定时器还在空转。

## 39. 异步调试工具链

- 第一步永远：`PYTHONASYNCIODEBUG=1` / `loop.set_debug(True)`——
  慢回调告警（`slow_callback_duration` 默认 0.1s）、未 await
  协程警告、回调执行计时；它有开销，线上开关化（配置中心翻转）。
- 看在飞任务：`asyncio.all_tasks()`（带名字，27 节命名）；
  `t.get_coro()` + `t.print_stack(limit)` 打任务栈——通过暴露的
  小诊断端点或 aiomonitor 现场 dump。
- aiomonitor：附着运行中循环——telnet 进去查任务/队列/循环
  状态，生产事故的"asyncio 版 gdb"。
- "coroutine was never awaited" 三大来源：忘了 await；把协程
  函数当回调传（应传 `lambda: asyncio.create_task(...)`）；异常
  路径短路跳过 await。CI `-W error::RuntimeWarning` 常驻。
- 时序测试：`asyncio.sleep(0)` 显式让位 + 自定义 loop 控时；
  真实 sleep 的异步测试 = flaky 制造机（98 节）。

## 40. 常见 async 反模式清单

- 同步 IO 混进 async：`requests`/`time.sleep`/大文件 `open()`——
  用 httpx/aiohttp、`to_thread`（32 节）；ruff ASYNC 规则族
  能静态逮（84 节）。
- 串行 await 循环：`for u in users: await fetch(u)` = N×RTT——
  TaskGroup + `Semaphore`（并发上限）批量并行；无界 gather 十万
  任务 = 打爆下游连接数，信号量是标配。
- 每请求一个事件循环：handler 里 `asyncio.run(...)`——循环
  创建/销毁 + 无连接复用；常驻单循环 + 连接池。
- sync 装饰器包 async 函数：`@lru_cache`/`@retry` 不感知 await
  ——缓存的是协程对象、重试根本不生效；用异步版实现（58、92 节）。
- 驱动选错：psycopg2（同步）在 async 服务里 = 全线程池化甚至
  直接阻塞——asyncpg/psycopg3 async 原生驱动。
- 吞取消：一切 `except Exception` 边界重审（28 节）；裸
  `except BaseException` 更是禁用。

## 快速索引 · 续（41-80 节）

| 想找什么 | 去哪一节 |
| --- | --- |
| GIL 切换间隔 | 41. GIL 本质与切换间隔 |
| 什么时候用线程 | 42. 线程的真实适用边界 |
| fork/spawn/forkserver | 43. multiprocessing 启动方式对比 |
| fork 为什么弃用 | 44. fork 的死亡：3.12 起 |
| 子进程异常在哪 | 45. ProcessPoolExecutor 的异常语义 |
| 共享内存泄漏 | 46. shared_memory 使用纪律 |
| pickle 太慢 | 47. pickle 成本模型与替代 |
| 锁竞争优化 | 48. 锁竞争与线程安全清单 |
| 引用计数坑 | 49. PyObject 与引用计数 |
| is 比较整数陷阱 | 50. 小整数缓存与驻留 |
| dict 为什么快 | 51. dict 内部结构 |
| list 摊销/deque 选择 | 52. list/tuple 内部与摊销 |
| 字符串内存布局 | 53. str 内部表示与编码陷阱 |
| 内存不还给 OS | 54. pymalloc 与内存池 |
| slots 省内存 | 55. __slots__ 实战 |
| 推导式性能 | 56. 推导式与循环的字节码成本 |
| 属性访问热点优化 | 57. 属性访问成本与内联缓存 |
| lru_cache 陷阱 | 58. 缓存模式：lru_cache 与 cached_property |
| 字符串拼接性能 | 59. 字符串构建性能 |
| __del__ 不执行 | 60. __del__、引用循环与 gc |
| 弱引用缓存 | 61. 弱引用模式 |
| 内存泄漏定位 | 62. 内存剖析：tracemalloc 与 memray |
| 动态多个上下文 | 63. 上下文管理器工程学（ExitStack） |
| 生成器管道 | 64. 生成器双向通道与管道 |
| lambda 循环取值错 | 65. 闭包 late-binding 陷阱 |
| 装饰器保签名 | 66. 装饰器工程学 |
| property 原理 | 67. 描述符协议 |
| 插件注册模式 | 68. __init_subclass__ 与插件注册 |
| 什么时候元类 | 69. 元类的使用边界 |
| try 成本 | 70. EAFP 与异常成本模型 |
| 默认参数被共享 | 71. 可变默认参数事故 |
| match 捕获模式 | 72. match 语句进阶 |
| walrus 用法 | 73. walrus 操作符高级模式 |
| dataclass 配置 | 74. dataclass 进阶配置 |
| StrEnum 差异 | 75. 枚举进阶 |
| __getattr__ 递归 | 76. 属性协议与 getattr 边界 |
| pyproject 结构 | 77. pyproject.toml 全景结构 |
| uv 命令流 | 78. uv 工作流与 lockfile 纪律 |
| monorepo 拆包 | 79. uv workspace 与 monorepo |
| editable 原理 | 80. editable install 原理 |

## 41. GIL 本质与切换间隔

- GIL 互斥的不是"代码"而是解释器状态（refcount + 内存分配器）；
  字节码执行持锁，每 `sys.getswitchinterval()`（默认 5ms）检查
  是否让位；IO/sleep/多数 C 扩展边界处主动释放。
- 纯 Python 双线程 CPU 密集 = 各拿半速**再减**切换开销（convoy
  effect：解释器状态/缓存抖动）——2 线程总吞吐低于 1 线程不罕见。
- `sys.setswitchinterval()` 调大减少切换但放大尾延迟（一个线程
  霸占更久）——生产几乎总是错；正确解法：拆进程、C 扩展释放
  GIL（112 节）、free-threading（1 节）。
- 观测 GIL 争用：py-spy `--gil` 采样 GIL 等待事件（102 节）；
  症状是 CPU 没满但吞吐上不去、单核 100%。
- 迁移 free-threading 前的认知：GIL 构建上"线程 + 共享可变对象"
  没炸是因为序列化救了你——ft 化之前先补锁/改消息传递（42、48
  节），否则数据竞争全部显形。

## 42. 线程的真实适用边界

- 线程适合的三类：必须用的阻塞库（老 DB 驱动、厂商 SDK）；
  C 扩展内部释放 GIL 的调用（hash/压缩/BLAS/numpy 大矩阵）；
  独立阻塞等待（GUI/信号/watchdog）。
- `to_thread` 默认池 `min(32, cpu+4)`：混跑长阻塞任务会饿死池
  （后来者无上限排队）——按任务类别拆自定义 ThreadPoolExecutor
  （max_workers 显式）+ 独立队列，池名进 metrics。
- 栈内存：默认 8MB/线程是**虚拟内存**，RSS 按页增长；千线程服务
  要么 `threading.stack_size(256*1024)` 调小，要么换协程——线程
  数是资源不是免费的。
- 关停纪律：daemon 线程在解释器退出时**硬杀**（不执行清理）——
  生产线程一律非 daemon + 停机 `threading.Event` + join 带超时
  （35 节序列）。
- 检查清单：线程间共享可变对象有没有锁/queue 保护？check-then-act
  拆开了吗？48 节竞态清单过一遍再上线。

## 43. multiprocessing 启动方式对比

- 三种 context：fork（Linux 默认；写时复制快启动）、spawn
  （Windows/macOS 默认；重新 import 一切）、forkserver（Linux
  折中：干净的 server 进程按需派生）。
- spawn 的成本与纪律：子进程重执行模块导入（10 节启动成本 ×
  进程数）；全局大对象需重建；连接/锁不可继承，必须子进程内
  重建；**`if __name__ == "__main__"` 保护是硬要求**——没有它
  子进程 import 主模块时递归 spawn 崩溃。
- 显式声明 context：`ctx = multiprocessing.get_context("spawn")`
  → `ctx.Pool()`/`ctx.Queue()`/`ctx.Lock()`——混用不同 context
  的原语是未定义行为（血泪高发）。
- 选择策略：追求一致性与可调试 → 全员 spawn；Linux 纯 CPU 短
  任务且进程数大 → forkserver；fork 只留给"确定单线程父进程 +
  短命批处理"（44 节审计）。

## 44. fork 的死亡：3.12 起

- 为什么死：fork 只复制**调用线程**——其他线程在 fork 瞬间持有
  的锁（malloc 内部锁、logging handler 锁、SSL 内部锁）在子进程
  里**永远锁着**，无堆栈死锁。
- 官方时间线：3.12 起在多线程进程中 fork 触发 DeprecationWarning
  （`os.fork()` 与 multiprocessing 的 fork context 都涉及）；
  macOS 上 fork + Objective-C runtime 早已不安全（所以 3.8 起
  spawn 默认）。
- 审计方法："我以为父进程单线程"通常是错觉——第三方库内部起的
  线程（DB 驱动心跳、APM agent、numpy/OpenBLAS 线程池）都算；
  fork 前 `threading.active_count()` 打印对比心智模型。
- 迁移清单（fork → spawn）：main guard、context 统一（43 节）、
  跨进程共享状态改 Manager/共享内存（46 节）或消息传递、模块
  顶层副作用清零（113 节）。
- 例外认知：`fork + exec`（subprocess 内部）不受此限——fork 后
  立刻 exec 不碰锁；但 macOS 仍建议 posix_spawn 路径（111 节）。

## 45. ProcessPoolExecutor 的异常语义

- worker 崩溃（OOM/段错误）→ 所有在飞 future 收到
  `BrokenProcessPool`，池**报废**——这不是普通业务异常路径：
  调用方必须有"捕获 + 重建池 + 重新提交"的架构，否则一次崩溃
  后续全部失败（半数团队第一次遇见是在生产）。
- pickle 失败的两个时刻：提交时（参数不可序列化）与回收时
  （返回值不可序列化）——报错栈指向 executor 而不是真实对象，
  认准 "cannot pickle ..." 里的对象名（47 节）。
- `max_tasks_per_child`（3.11+）：worker 处理 N 个任务后重启——
  防第三方库内存泄漏扩散；`initializer` 预热（加载模型/字典）。
- 任务粒度经济学：单任务 < ~10ms 时 IPC + pickle 开销占大头——
  上层批处理（chunk 聚合）是第一优化，不是换序列化格式。
- 取消限制：运行中任务不可取消（进程无协作式取消）——超时在
  上层做（93 节）；`future.cancel()` 只能拦还在排队的。

## 46. shared_memory 使用纪律

- 生命周期：`SharedMemory` 需要 `close()`（解除映射）+
  `unlink()`（真释放）——unlink 由**最后一个使用者**负责，否则
  /dev/shm 泄漏：容器里 = 吃宿主内存直到重启。
- resource_tracker：默认跟踪段归属，进程异常退出会警告
  "leaked shared_memory"；3.13 起支持 `track=False` 自管生命
  周期——长跑服务用它 + 显式 unlink（最后使用者协议/flock）。
- 数据布局要自己设计：共享块是裸内存——元数据（长度/dtype/
  序列号）走另一条 IPC（Manager dict / 小消息 Queue），buffer
  只放大块数据：`np.frombuffer(shm.buf, dtype=np.float32)`。
- 一致性：无内建锁——读写用 `multiprocessing.Lock`/`Event`
  同步，或单写多读 + 原子序号（双缓冲轮换）。
- 事故模式：fork 出的子进程继承映射（引用不计数）——父子都
  close 后 buffer 悬空仍可读写 = 段错误无栈（105 节取证）；
  resource_tracker 行为跨版本有修复差异，锁版本并测试。

## 47. pickle 成本模型与替代

- 成本模型：小对象单次 pickle ~微秒，但**件数**主导——进程池
  海量小任务的序列化时间超过计算本身；大 numpy 数组走 pickle
  有 2 倍内存峰值（序列化副本）——大块用共享内存（46 节）或
  joblib 自动 memmap。
- 优化顺序：① 别传数据（传键/索引/路径，worker 自己取）；
  ② 批处理（45 节）；③ 最后才是换格式。
- 格式替阵：msgspec（最快、schema 安全）、msgpack、protobuf
  （跨语言契约）；`cloudpickle` 处理 lambda/局部类（Dask 生态）。
- 自定义协议：`__reduce_ex__`/`__getstate__` 控制序列化面；
  加 schema 版本字段（`state["v"] = 2`）——跨版本反序列化
  （缓存/队列里的旧消息）才不会炸。
- 安全红线：pickle 反序列化 = **任意代码执行**——永不吃外部
  输入；连"内网可信"都要问一句为什么不用 msgspec。

## 48. 锁竞争与线程安全清单

- GIL 之下仍然会炸的竞态清单：check-then-act（`if k not in d:
  d[k] = v` 两步间可被打断）、读-改-写（`counter += 1` 在跨
  await/IO 边界不是原子的）、"先检查再调用"的 LBYR（并发下
  EAFP 不是风格是正确性，70 节）。
- 成本基准：无争用 `threading.Lock` acquire/release ~0.1µs；
  `list.append`/`d[k]=v` 单操作在 GIL 下事实原子（CPython 实现
  细节，勿写进契约）——复合操作没人替你原子。
- 优先消息传递：`queue.Queue`（线程）/asyncio.Queue（协程）交接
  所有权，竞态消失——能用队列别共享+锁。
- 死锁纪律：全局锁序（按名字字典序获取）、锁内不做回调/IO、
  RLock 显式用于重入；排查用 faulthandler dump 全线程栈（105
  节）直接看谁持谁等。
- 无锁工具箱：`itertools.count()`（原子递增 ID）、
  `collections.deque`（append/popleft 线程安全）、per-thread
  分片聚合 + 最终合并（shard 模式）。

## 49. PyObject 与引用计数

- 一切对象 = `PyObject { ob_refcnt, ob_type }` 起步；
  `sys.getrefcount(x)` 返回值**比真实多 1**（调用自身持引用）。
- 引用计数即时回收：函数退出、局部归零即释放（无环时）——
  内存曲线通常锯齿而不是台阶；依赖"退出即释放"是合理预期，
  依赖"何时释放"不是。
- 3.12+ immortal objects（PEP 683）：None/True/False/小整数/
  interned str 不再动 refcount——为 free-threading 铺路 + 消灭
  热点抖动；`gc.freeze()` 把存量对象移出分代（60 节）。
- `id()` 是地址（CPython 细节）；`is` 恒等 O(1)——语义上只用于
  单例与哨兵比较（71 节哨兵模式），值比较永远 ==（50 节）。
- 调试工具：`sys.getrefcount`（快照对比找泄漏持有者）、
  `gc.get_referrers(x)`（谁在引用，慎用——它本身持引用）、
  objgraph（62 节）。

## 50. 小整数缓存与驻留

- `[-5, 256]` 全局缓存共享单例——`a is 256` 为 True；`a is 257`
  依实现与编译期常量折叠而定（同一个函数里两个字面量可能同对象）
  ——**永远别用 is 比较数值**，Lint 规则 E711/E712 背后是血泪。
- 字符串驻留：标识符风格字符串编译期自动驻留；运行期产生的
  串要 `sys.intern(s)`——解析器/状态机/属性名热点收益大；
  普通业务串别驻留（驻留 = 泄漏到进程生命周期）。
- intern 的隐藏收益：同指针 str 相等比较走 identity 快路径——
  海量重复 key 的 dict（路由表/枚举名）有感；`dict` 的 key 查找
  先比指针再比内容（51 节）。
- 验证姿势：`id()`/`is` 实验在 REPL 做可以，写进测试断言不行
  ——实现细节跨版本/跨运行时漂移（11 节）。
- free-threading 下：immortal 对象不参与 refcount（49 节），
  intern 表有锁保护——解析热点在 ft 构建上收益重新测量。

## 51. dict 内部结构

- compact dict（3.6+）：稠密 entries 数组（插入序）+ 稀疏索引表
  （2^n 大小、装填因子 2/3 扩容）——**插入有序是数据布局的
  副产品**，语言 3.7 才承诺；别在序列化格式里依赖 key 顺序。
- 查找：hash → 索引探测（开放寻址 + perturb 混沌）→ entries
  稠密比较；hash 冲突的 dict 退化为链式扫描——自定义 `__hash__`
  必须分散（默认 id-based hash 对可变对象反而是雷）。
- 缺键成本：`d[k]` miss 走异常路径（构建 + 抛 KeyError，
  ~µs 级）比 `.get(k)` 慢一个数量级——70 节异常成本模型；
  命中时 `[]` 比 `.get` 快（少一次方法调用）。
- key 纪律：作 key 的对象必须 hash/eq 稳定 + 不可变——自定义
  实例作 key 但属性可变 = 经典自坑（hash 失效、键"丢失"）；
  用 frozen dataclass / tuple / str。
- `PYTHONHASHSEED` 随机化（3.3+ 默认）防 hash 洪水 DoS——对外部
  输入作 key 的热路径天然受益；别在多进程间依赖 hash 序稳定
  （分片路由要用显式排序）。
- 实例 `__dict__` 键共享（PEP 412）：同类千实例共享 keys 表——
  配 `__slots__`（55 节）是大数据结构省内存的两板斧。

## 52. list/tuple 内部与摊销

- list over-allocate：容量按 ~12.5% 余量增长（0,4,8,16,25,35,
  46,58…）；append 摊销 O(1)；`insert(0, x)`/`pop(0)` O(n)——
  队列/栈用 `collections.deque`（双端 O(1)）。
- tuple：不可变 → 可 hash 可作 key；全常量元组在编译期折叠成
  单个常量（LOAD_CONST 直载）——写 `(a, b)` 字面量而不是
  `tuple([a, b])`（后者运行期再建一次）。
- 切片是**拷贝**：O(k) 时间与内存；`a[i:j] = ...` 是就地 splice；
  负步长反转 `[::-1]` 也是拷贝——大数组用 numpy 视图（110 节）。
- `[[]] * 3` 是引用复制不是浅拷贝——三个元素同一 list 对象
  （71 节同族）；`[[ ] for _ in range(3)]` 才是三个独立 list。
- `sort` 是 Timsort：部分有序输入 O(n)、稳定排序；`key=` 一次
  预计算（别用 cmp）；"前 k 个/第 k 大"用 `heapq.nlargest` 或
  `bisect` 维护有序性，比全排序便宜。

## 53. str 内部表示与编码陷阱

- PEP 393 紧凑表示：按内容自动选 ASCII（1 字节/字符）/
  latin1（1）/BMP（2）/astral（4）——`len` O(1)，内存随内容跳变；
  切片结果重新紧凑化（切片 ASCII 后释放 4 字节布局）。
- 字符 vs 字节：`s[i]` 是字符不是字节；str/bytes 交互必须显式
  encode/decode——utf-8 mode 默认化在推进（PEP 686），但显式
  传 encoding= 仍是纪律（locale 依赖是部署雷）。
- 比较纪律：大小写无关比较用 `casefold()` 不是 `lower()`
  （德语 ß）；作为 key/去重前 `unicodedata.normalize("NFC", s)`
  ——用户输入"看起来一样"的字符串 NFC 后才真一样。
- 正则：`re.compile` 模块级复用（内部缓存 512 条，别赌）；
  灾难回溯（嵌套量词吃外部输入）用 `regex` 包的超时/possessive
  量词，或把解析换成分词状态机。
- 内部细节利用边界：`s.isascii()` 快速分支可读性收益 > 微优化；
  但"ASCII 走 bytes 处理更快"要 profile 佐证（107 节）。

## 54. pymalloc 与内存池

- 分层：arena 256KB → pool 4KB → 8B 对齐块；>512B 的对象直接
  走系统 malloc——大对象池化策略要按这条线设计。
- 关键事实：内存归还 OS 只在**整 arena 全空**时——大量中长
  生命周期交错对象 = arena 碎片 → RSS 只涨不跌（不是泄漏！）；
  cgroup 内存计费看 RSS，进程被 OOMKill 的"假泄漏"大多源于此。
- 调试姿势：`PYTHONMALLOC=malloc` + valgrind 找真越界；
  `tracemalloc`（62 节）看 Python 层分配；arena 利用率在 debug
  构建 `sys._debugmallocstats()` 直读。
- 实战对策：大数据结构用"预分配 + 复用池"（环形缓冲/对象池）
  替代反复创建；`del` 大列表后 RSS 不降是正常现象（arena 空置），
  别用"看起来没降"否定优化。
- 容器注意：glibc malloc 的 free 策略 + mmap 阈值可调
  （`M_MMAP_THRESHOLD`）；musl（Alpine）行为完全不同——同一
  服务的镜像切换基础镜像要重测内存曲线。

## 55. __slots__ 实战

- 效果：消灭实例 `__dict__`，属性进固定 offset 数组——万级实例
  场景省 ~30-40% 内存且属性访问略快；代价：加属性必须改类
  （动态性换内存，是决策不是免费）。
- 陷阱清单：① 外部 `setattr` 直接炸（库互操作/monkeypatch 受限）；
  ② 默认无 `__weakref__`——要弱引用显式加 `__weakref__` 槽
  （61 节失效）；③ 多继承两个带 slots 的类冲突；④ 继承链里有
  无 slots 基类 → 实例仍有 `__dict__`，收益清零——审计基类。
- `cached_property` 与 slots 冲突：它把值写实例 `__dict__`（58
  节）——slots 类直接 AttributeError，改普通 property + 手动缓存
  或 `field`。
- dataclass：`@dataclass(slots=True)`（3.10+）通过**重建类**实现
  ——装饰器在 slots 模式下与 `field(default_factory=...)` 正常；
  但与 `functools.cached_property` 组合要注意上一条。
- 何时不用：实例数量 < 千级、需要动态属性、被 ORM/序列化库
  反射的类——slots 的收益在"海量小对象"才兑现。

## 56. 推导式与循环的字节码成本

- 3.12 PEP 709：列表/字典/集合推导式**内联进当前帧**（~2x），
  且推导式变量不泄漏到外层作用域（3.11 前只有 genexp 隔离）——
  "循环变量泄漏"这个老坑在 3.12 后消失，但老代码评审时仍要认得。
- 字节码：`[*a, *b]` → BUILD_LIST + LIST_EXTEND（C 层快路径），
  比 `a + b` 少拷贝且类型更宽（tuple 也能展开）；
  `{**a, **b}` 同理（后者覆盖前者键）。
- 嵌套推导可读性悬崖：双层以上 + 条件 + walrus → 普通 for
  循环更便宜（评审规则写进 115 节清单）。
- genexp 惰性 O(1) 内存，但每元素有生成器帧开销——小数据量
  `sum([...])` 反而快；"genexp 省内存"只在数据大时成立，
  量测（107 节）别背口诀。
- `map/filter` 与推导式：推导式通常更快（内联）且可读；map 只在
  "已有 C 层函数"（`map(str, xs)`）时有微弱优势。

## 57. 属性访问成本与内联缓存

- 3.11+ 特化自适应解释器（PEP 659）：LOAD_ATTR/LOAD_GLOBAL 带
  内联缓存（类型版本标签失效机制）——全局/属性访问比 3.10 快
  ~30%；"手动局部变量化"的收益变小但热点循环仍值得。
- 查找顺序：data descriptor（定义 `__set__`/`__delete__`）>
  实例 `__dict__` > non-data descriptor > `__getattr__` 兜底
  ——property 覆盖实例字典就是这个原因（67 节原理）。
- 方法调用：热循环里 `f = obj.method` 提出循环——LOAD_METHOD
  有优化但 bound method 创建在动态路径仍发生。
- MRO：C3 线性化，菱形继承的解析顺序可预测但别靠它写业务
  ——`cls.__mro__` 调试可见；`super()` 是协作式多继承协议，
  不是"调父类"。
- `__getattr__` vs `__getattribute__`：后者拦截一切（写它必须
  super() 调用否则无穷递归）——76 节；代理/ORM 用后者，懒加载
  用前者。

## 58. 缓存模式：lru_cache 与 cached_property

- `lru_cache`：参数整体作 key（哈希成本计入）；dict 参数不可
  哈希——传关键 tuple 或冻结 dataclass；`maxsize=None` = 普通
  memo；`typed=True` 区分 1 与 1.0（默认同一键）。
- 线程安全但**不单飞**：lru_cache 双查锁只保护缓存结构——
  N 线程同时 miss 会**并发重算**（cache stampede）：重算昂贵
  时要自实现 per-key 单飞（Future 模式：首线程算、其余等同一
  Future）。
- `cached_property`：值写进实例 `__dict__`（类描述符让位）——
  slots 类炸（55 节）；失效 = 删实例属性；每实例独立缓存
  （不是类级共享，别当全局缓存用）。
- 跨进程不共享：multiprocessing 各 worker 各自缓存 = N 倍后端
  流量 + 不一致——命中率/一致性要求高时上 Redis/memcached，
  进程内缓存只做 L2。
- 陷阱：`@lru_cache` 直接叠 async 函数 = 缓存协程对象（不 await
  的那次）——必须包一层 wrapper（40 节反模式）。

## 59. 字符串构建性能

- `"".join(parts)` O(n) 一次分配——标准答案；循环 `+=` 名义
  O(n²)，CPython 有 refcnt==1 原地扩容优化**但**一有别名、一换
  实现（PyPy 策略不同）就退化——教条写 join，别赌优化存在。
- io.StringIO：流式组装 + 中途读取；f-string 是渲染最快的格式化
  （编译期解析 + BUILD_STRING 特化），`str.format` 慢 ~2x，
  `%` 最老但简单场景仍有代码量优势。
- 大量小片段：list 累积 + join；MB 级拼接在 bytes 层 + memoryview
  切装（110 节）比 str 层省一次编码。
- 日志热路径：`log.info("x=%s", x)` 惰性格式化——参数已求值但
  **格式化**只在真输出时发生；f-string 日志在禁用级别仍全量
  格式化（106 节，热路径 3-10x 差距）。
- t-string（4 节）不是格式化加速——它把"渲染决策权"交给消费库，
  性能与 f-string 无关，别按性能选型。

## 60. __del__、引用循环与 gc

- `__del__` 调用时机不确定（引用归零/GC 收环/进程退出不保证）
  ——在 `__del__` 里做资源释放 = 偶发泄漏；正确姿势：上下文
  管理器显式 close，`__del__` 只做"忘了 close"的兜底 + 警告日志。
- 环与分代 GC：互相引用 → refcount 不归零 → 分代 GC
  （阈值 700/10/10 触发 0 代，逐代晋升）定期收——大堆服务的
  GC 停顿可感；`gc.freeze()` 把 fork 前存量冻结到永久代（44 节
  配套，forkserver/prefork 模型标配）。
- `__del__` 事故三连：抛异常（stderr 打印后吞掉）；resurrect
  （`__del__` 里把自己存全局）→ 第二次死亡**不再调** `__del__`；
  `__del__` 里 await/锁操作 → 死锁现场。
- 调优：`gc.disable()` 只对"无环 + 内存稳定"的服务安全；先量
  GC 频率（`gc.callbacks` 挂计数器进 metrics）再决定。
- 替代：`weakref.finalize`（61 节）语义可控（显式 detach、atexit
  保证），新代码一律用它替代裸 `__del__`。

## 61. 弱引用模式

- `weakref.ref(obj, cb)`、`WeakValueDictionary`（ID→重对象缓存
  不阻止回收）、`WeakKeyDictionary`（对象→元数据关联）、
  `weakref.WeakMethod`（绑方法：普通 ref 对 bound method 立即死
  ——bound method 是临时对象）。
- callback 在 GC 任意点执行：不许 resurrect、别做重活/加锁
  （死锁现场）；`finalize` 的回调由 atexit 兜底，语义更可控。
- `weakref.finalize(obj, cleanup, arg)`：比 `__del__` 可控
  （显式 `detach`/`atexit` 顺序）——资源生命周期绑定首选；
  finalize 保留 arg 强引用，别把 obj 自己传进去（复活环）。
- slots 类要 `__weakref__` 槽（55 节）；`__slots__` 忘加 =
  weakref 创建 TypeError，库代码 catch 后降级提示。
- 缓存语义边界：WeakValueDictionary 的"值"是弱引用——外部没
  人持有时缓存自动 miss，这是特性不是 bug；需要"至少保活 N 秒"
  用 TTL 缓存（cachetools.TTLCache）组合。

## 62. 内存剖析：tracemalloc 与 memray

- tracemalloc：Python 层分配追踪（按 traceback 的 nframe 采样）
  ——`take_snapshot()` + `compare_to(old, "lineno")` 差分找增长
  点；`Filter` 按文件收敛；开销 ~2x 分配路径，生产常驻不可取，
  做成开关（信号/配置翻转 + 有限窗口）。
- memray（Bloomberg）：进程内火焰图 + native 分配追踪（能抓
  C 扩展泄漏，3.12+ 原生栈更强）、attach 模式采样运行中进程
  ——tracemalloc 看不见的 native 层用它。
- objgraph 快筛：`objgraph.growth(limit=10)` 按类型增量排序，
  十秒定位"哪个类型的对象在涨"，再上 tracemalloc 定行。
- RSS 口径：psutil `memory_full_info().uss` 是"真持有"；fork 后
  共享页让 RSS 虚高（COW 未发生的假象）——cgroup 计费用
  memory.peak 对照，别拿单进程 RSS 说事（54 节）。
- 排查节奏：现象（曲线）→ objgraph 筛类型 → tracemalloc 定
  Python 行 → memray 兜 native → 复现脚本化进 CI（夜跑防回归）。

## 63. 上下文管理器工程学（ExitStack）

- 动态数量资源（循环开文件/连接）：`stack.enter_context(open(p))`
  ——LIFO 统一释放，"N 个资源 + 中途失败"不再漏关。

```python
with ExitStack() as st:
    conns = [st.enter_context(connect(h)) for h in hosts]
    ...
# 任一步抛错，已进入的全部按逆序关闭
```

- `stack.callback(fn, *a)`：注册裸清理函数；`stack.push(cm)`/
  `enter_async_context`（异步版 AsyncExitStack，33 节）。
- 复合 CM 模式：把多个 CM 合成一个类——`__enter__` 内
  enter_context，`__exit__` 委托栈释放；这是把"配置+撤销"内聚
  成对的标准手法（setup 函数返回 undo，callback 注册 undo）。
- 陷阱：callback 里抛异常会**掩盖**原始异常——清理代码用
  `try/except + log` 包裹，抑制型清理才用 contextlib.suppress
  （精确类型）。
- 排错：ExitStack 释放顺序反转打印——给关键栈命名（子类化
  加 `__repr__`），停机日志能看出"释放到第几个"。

## 64. 生成器双向通道与管道

- `send/throw/close` 三件套：生成器是可暂停函数 + 双向通道；
  `yield from sub()` 委托同时**捕获子生成器 return 值**
  （`result = yield from sub()`）——协程式组合的基石。
- 管道：gen → gen → 消费者全链 O(1) 内存——大 ETL 替代
  中间 list；每级职责单一，`itertools`（islice/takewhile/
  chain/groupby）拼装优于手写循环。
- `throw` 注入事务模式：生成器持事务、挂起点消费数据——
  外部 `gen.throw(Rollback())` 触发回滚、正常 `close()` 提交；
  这是 `@contextmanager` 的同款机制（yield 是 try 的 body）。
- 一次性语义：StopIteration 后生成器废——复用需求改"生成器
  工厂"（每次调用产新 gen），别缓存生成器对象。
- 3.14 注意：PEP 768 允许生成器在任意帧向生成器 send/throw
  （调试器接管生成器）——业务别依赖，理解它只为看懂调试器
  行为变化（104 节）。

## 65. 闭包 late-binding 陷阱

- 闭包捕获的是**变量**不是值：`[lambda: i for i in range(3)]`
  三个函数全部返回最后的 i——循环变量在调用时才求值。
- 修法：默认参固化 `lambda i=i: i`（求值发生在 def 时）或工厂
  函数闭包（每轮新作用域）；`functools.partial(f, x)` 同效果且
  保留 kwargs 语义。
- 同族陷阱：推导式里的 walrus 泄漏到外层作用域（73 节）；
  类体内推导式访问类属性 NameError（类体不是闭包作用域）——
  先赋局部变量再用。
- 调试：`f.__closure__` + `cell.cell_contents` 直接看捕获值；
  测试断言捕获快照是防回归的最便宜手段。
- 选型准则：partial 与默认参等价时按 API 选——partial 不改
  签名、默认参在签名上可见（inspect 可见即文档）；性能差异
  可忽略（不要按微基准选，107 节）。

## 66. 装饰器工程学

- `functools.wraps` 必备：拷贝 `__name__`/`__doc__`/`__wrapped__`
  ——`inspect.signature` 与文档工具沿 `__wrapped__` 链走；
  没有它，调试与序列化框架（按签名绑定参数的）全瞎。
- 带参装饰器 = 三层函数（参数层 → 装饰器层 → wrapper 层）；
  统一用 ParamSpec 保签名（17 节）+ @overload 声明变体（18 节）。
- 叠加顺序：`@a @b def f` = a(b(f))——**自下而上应用**；重试
  与缓存叠加时顺序决定语义（缓存套重试 = 缓存住重试结果；
  重试套缓存 = 每次重试穿透缓存）。
- `functools.singledispatch`：按第一参数类型注册 handler——
  开放扩展（第三方按类型注册）替代 isinstance 链；
  `singledispatchmethod` 处理方法（register 时注意 self 绑定）。
- 事故模式：装饰器在 **import 期执行**（66 的一切重活/网络禁入，
  113 节）；装饰器吞异常或吞取消（28 节）——wrapper 里 re-raise
  是铁律；装饰器返回非 callable 是最经典的 TypeError 现场。

## 67. 描述符协议

- 优先级规则：data descriptor（定义 `__set__` 或 `__delete__`）>
  实例 `__dict__` > non-data descriptor（只 `__get__`）>
  `__getattr__`——property 覆盖实例字典、方法被实例字典遮蔽
  都由这条推导（57 节查找顺序）。
- function 是 non-data descriptor：`func.__get__(obj, cls)` 产出
  bound method——"方法"的全部魔法；staticmethod/classmethod 是
  描述符包装器，不是关键字魔法。
- `__set_name__(self, owner, name)`：定义期拿到属性名——类型化
  字段/验证器/ORM 声明的基础（消灭字符串重复：字段名写一次）。
- 状态陷阱：描述符是**类级单例**——实例状态必须按 instance
  键存：`self.__dict__` 放不下（data descriptor 优先），用
  `instance.__dict__[self._name]` 直写或 WeakKeyDictionary。
- `__get__(None, owner)` 分支：类访问（`Cls.attr`）要返回 self
  而不是值——忘了这分支，类级访问返回描述符对象，工厂/元编程
  处处炸。

## 68. __init_subclass__ 与插件注册

- 定义在基类、每个**子类定义时**调用（`cls, **kwargs`）——校验/注册/
  注入零元类；配 `__set_name__` 覆盖 90% 元类历史用法（67 节）。
- 插件注册模式：`REGISTRY[cls.key] = cls`——key 冲突 fail-fast
  （重复注册是事故不是覆盖）；abstract 基类跳过注册
  （`inspect.isabstract` 或显式标记）。

```python
class Backend(ABC):
    REGISTRY: dict[str, type[Backend]] = {}
    key: ClassVar[str]
    def __init_subclass__(cls, **kw):
        super().__init_subclass__(**kw)
        if getattr(cls, "key", None):
            cls.REGISTRY[cls.key] = cls   # 定义即注册
```

- kwargs 透传声明式配置：`class S3(Backend, key="s3")`——
  忘 `super().__init_subclass__` 会断链（每个中间基类都显式转发）。
- 与依赖注入边界：注册表给"发现"，实例化与生命周期归 DI 容器
  ——注册表直接产单例 = 隐藏全局状态（100 节测试隔离灾难）。

## 69. 元类的使用边界

- 剩余适用面：`__prepare__`（定制类命名空间：记插入序、约束
  赋值）、定义期改写全体方法（框架核心）、强制全类结构契约。
- `__init_subclass__` 能做的别上元类——元类带来组合税：
  多继承的元类必须同源（"metaclass conflict" 在两个框架类
  相遇时爆雷，且报错晚在定义期）。
- 你已经在用元类：`enum.EnumMeta`、`abc.ABCMeta`、`Protocol`
  的实现、dataclass 的部分机制——继承它们时元类已在场，自研
  元类要与它们共存测试。
- 调试：`type(cls)` 看真实元类；monkey-patch 元类方法影响
  **之后**定义的类（时机敏感，测试里别用）。
- 招人现实：团队里能安全维护元类的人比能维护描述符的少一个
  数量级——复杂度预算是评审的一部分（115 节）。
- 演进路径：从 `__init_subclass__`（68 节）起步，真需要
  `__prepare__` 或定义期改写再上元类——把元类圈死在框架
  最核心的一处，其余代码永远别 import 它。

## 70. EAFP 与异常成本模型

- 3.11+ 零成本 try：不抛异常的 try/except 几乎免费（入口只设
  一条跳转标记）；**抛**仍然贵：unwind + traceback 构造 +
  `__context__` 链保留 ≈ µs 级——错误密集路径会毫秒化。
- 结论：常态路径 EAFP（`d[k]` + except KeyError、先拿锁）、
  LBYR 在并发下必错（48 节 TOCTOU）；异常当循环控制流的
  hot path（每元素 throw）改哨兵返回/迭代器协议。
- 热点 dict 技巧：大概率命中用 `d[k]`+except（免方法查找）；
  大概率 miss 用 `.get(k)`；把两种写法写进同一函数是坏味道。
- traceback 链成本：嵌套 except 层层重抛会积累 `__context__`
  链——深层服务一次错误路径可 ms 级；刻意 `from None` 压制前
  先打日志（91 节）。
- `sys.monitoring`（3.12，PEP 669）：把 settrace 的每事件回调
  换成全局禁用/局部启用——coverage/调试器开销降一个数量级
  （104 节），业务代码无感但值得知道你的 coverage 为什么快了。

## 71. 可变默认参数事故

- `def f(items=[])`、`def f(cfg={})`：默认值在 **def 时求值一次**
  并存进 `f.__defaults__`——跨调用共享；症状是"状态莫名复现/
  列表越滚越大"，与全局变量共享同源。
- 哨兵模式：`_UNSET = object()` + `if x is _UNSET`——当 None 是
  合法传入值时必须哨兵（`is` 比较单例对象，50 节）；
  dataclass 用 `field(default_factory=list)`。

```python
_UNSET = object()
def get(key, default=_UNSET):
    if default is _UNSET:
        return store[key]        # 真缺键抛 KeyError
    return store.get(key, default)
```

- 隐形同族：类属性可变默认（`class C: items = []` 被所有实例
  共享）；`[[]] * 3` 引用复制（52 节）；闭包捕获（65 节）——
  这四类事故在评审清单里是一条（115 节）。
- 静态拦截：ruff B006（可变默认）/B008（函数调用默认）已内置
  （84 节）——legacy 代码 review 时手查 `=[]`/`={}`/`=set()`。

## 72. match 语句进阶

- 捕获 vs 常量：裸名 `foo` 是**捕获模式**（永远匹配并绑定新名），
  点名 `Color.RED` 才是常量比较——用裸枚举成员名匹配是最高频
  错误（永远走该分支且 shadow 变量）。
- 结构解构：序列 `[first, *rest]`、映射 `{"type": "user", 
  "name": str() as name}`；序列模式**不匹配** str/bytes——
  字符串别当序列匹配。
- 类模式：`Point(x=0, y=0)` 按属性匹配，`Point(0, 0)` 走
  `__match_args__` 位置匹配——自定义类要声明后者才有位置形态；
  guard（`if`）在**绑定之后**执行，可用上一步捕获的变量。
- 性能认知：match 不比 if-elif 快（同是顺序测试）；价值在解构
  表达力 + 穷尽心智——深度嵌套 match 不如 visitor/多级函数。
- 语义细节：失败的模式匹配**可能留下部分绑定**（PEP 634 明示
  "实现可以保留失败点之前的捕获"——CPython 就会）——match 后
  别用分支内变量，即使"看起来应该没绑定"；`case _` 是万金油，
  放太前面会吞掉一切（顺序即语义）。

## 73. walrus 操作符高级模式

- 定位：把"计算 + 赋值 + 测试"压进一处且作用域正确——
  `while (chunk := f.read(8192)):`、`if (n := len(items)) > 10:`；
  循环前无需初始化哨兵。
- 推导式内合法且**绑定到外层函数作用域**（PEP 572 有意为之）：
  `[y for x in data if (y := f(x)) is not None]`——y 在推导式后
  仍可读；这是特性也是评审注意点（读代码的人未必预期泄漏）。
- 禁区清单：增强赋值（`x := 1` 合法但 `x += 1` 形态不行）、
  函数参数默认值、lambda 参数；f-string 内 3.12（PEP 701）前
  不可用、3.12+ 可以——版本敏感写法要 gate（12 节）。
- 可读性判据：一行内能讲清"赋什么、测什么"则用；两个效果
  埋进复杂表达式（嵌套调用中段）则拆开——walrus 滥用比重复
  表达式更难读。
- 与 genexp 组合：`(last := x)` 在 `any()`/`all()` 里留"最后
  检查值"——短路后拿 last 判断是惯用小技巧。

## 74. dataclass 进阶配置

- 关键开关：`slots=True`（3.10+，重建类实现——55 节收益与限制）、
  `frozen=True`（不可变 + hashable，改值抛 FrozenInstanceError——
  值对象/消息的标准形态）、`kw_only=True`（3.10，解决继承链
  "有默认字段后不能再有无默认字段"的顺序死结）。
- 字段元数据：`field(init=False, repr=False, compare=False)`
  显式排除派生字段；`InitVar[T]` 传构造参数不入字段（配合
  `__post_init__` 做归一化）。
- frozen 下的派生初始化：`__post_init__` 里 `object.__setattr__(
  self, "computed", ...)`——frozen 只拦正常 setattr。
- 事故点：`asdict()` 是**递归深拷贝**（嵌套 dataclass/list 全
  copy）——热路径用 `vars(obj)`/手动或 msgspec；`replace()` 复制
  改字段与 frozen 配套，但 `init=False` 字段不可 replace。
- 继承 + 默认值：子类加有默认字段会触发父类无默认字段的
  TypeError——`kw_only=True` 是官方解，比调整字段顺序可靠。

## 75. 枚举进阶

- 3.11 `StrEnum`：`str(member)` 返回**值**；`class C(str, Enum)` mixin
  返回 "C.RED" 形态——序列化差异是真实事故源，新代码一律 StrEnum。
- 查找语义：`Color["RED"]`（按名）/`Color("r")`（按值，抛 KeyError/
  ValueError）——解析统一入口 + `_missing_` 宽容处理，返回成员或 None。

```python
class Color(StrEnum):
    RED = "red"
    @classmethod
    def _missing_(cls, value: object):
        if isinstance(value, str):
            return cls(value.strip().lower())  # None 则继续抛
```

- `IntFlag` 位组合 + `auto()`；`__members__` 含别名，去重用
  `set(Color)`；成员是单例但对外语义用 == 与 `.value`（50 节）。
- 序列化纪律：JSON 出口传 `.value`；入口统一函数吞错转默认
  + 告警计数——裸 ValueError 爬到 API 层是脏错误分类（94 节）。
- 枚举不是 enum 的场景：值会变/来自数据库的动态集合——
  用常量模块或 lookup 表，假枚举是维护陷阱。

## 76. 属性协议与 getattr 边界

- 分工：`__getattr__` 只在**正常查找失败**（AttributeError 路径）
  后调用——懒加载/委托的首选；`__getattribute__` 拦截**一切**——
  写它必须 `super().__getattribute__(name)` 兜底否则无穷递归。
- `hasattr` 语义：内部 getattr + 吞 AttributeError——**其他
  异常照抛**（TypeError/KeyError 击穿 hasattr）；`__getattr__`
  里抛非 AttributeError 的库会击穿所有协议探测（pickle/copy/
  mock 全炸）——`__getattr__` 里异常要么 AttributeError 要么
  先兜住。
- 委托陷阱：`__getattr__` 返回任何请求 → pickle/copy/deepcopy
  探测的 `__reduce_ex__`/`__deepcopy__` 全被代理劫持——按
  前缀白名单（`if name.startswith("_fw_")`），dunder 一律不代理。
- `__dir__`：补全/自省可见性——动态属性不写 `__dir__` 就在
  tab 补全与 `dir()` 里隐身（可用性 bug）。
- 与 mock 的互动：mock/monkeypatch 大量依赖属性协议——99 节
  边界纪律同样适用于"被代理的对象"。

## 77. pyproject.toml 全景结构

- 三段式：`[build-system]`（requires + build-backend：setuptools/
  hatchling/maturin/scikit-build-core）、`[project]`（PEP 621
  元数据：name/version/requires-python/dependencies/
  optional-dependencies）、`[tool.*]`（ruff/pytest/mypy/pyright/
  uv 全部收编——单文件是迁移收益的一半）。
- `dynamic = ["version", "readme"]` 声明构建期回填——版本从
  git tag（hatch-vcs/setuptools-scm）或 `__about__` 读取，
  消灭"改代码忘改版本"。
- src-layout（`src/pkg/...`）：防"从 cwd 意外 import 未安装
  包"——测试必须跑**安装后的包**，否则你测的是工作区旧拷贝
  （这是隐藏最深的项目结构 bug）。
- backend 选择：纯库 setuptools（惯性）或 hatchling（现代、
  零配置）；含 Rust → maturin（109 节）；C/C++ 二进制 →
  scikit-build-core；动态语言插件包 → flit（最简）。
- 元数据纪律：`requires-python` 与 CI 矩阵一致（9 节）；
  classifiers 补 `Programming Language :: Python :: 3.14`——
  生态按它判断支持。

## 78. uv 工作流与 lockfile 纪律

- uv 管四层：**解释器 + 环境 + 依赖 + 锁**。`uv python install
  3.13`（含 `3.13t` free-threaded、pypy 等都能装）；`uv add/
  remove` 同步改 pyproject + uv.lock + 环境；`uv sync` 按锁造
  环境；`uv run` 先确保环境同步再执行——CI/脚本入口全部
  `uv run` 化，告别 activate 与"本地能跑"。
- 锁纪律：`uv.lock` 提交进 git（含哈希的跨机可复现）；
  CI `uv sync --locked`——锁与 pyproject 漂移立即失败；
  主动升级走 `uv lock --upgrade`（或按包 `--upgrade-package`）。
- 兼容面：`uv pip` 子命令处理 legacy requirements.txt；
  `uv export --format requirements-txt` 给无 uv 环境（Docker
  构建器/Lambda/运维脚本）。
- 缓存：uv 的内容寻址缓存（硬链接回填）——CI 缓存 key 按
  uv.lock 哈希；本地 `uv cache clean` 排障（损坏缓存的症状是
  玄学 ImportError）。
- 陷阱：`uv run` 会**自动同步**（隐式改环境）——生产部署用
  `uv sync --frozen && uv run --no-sync`（部署绝不改锁）。

## 79. uv workspace 与 monorepo

- 声明：`[tool.uv.workspace] members = ["packages/*"]`；跨包
  依赖 = 正常 `[project.dependencies]` 写版本 + `[tool.uv.sources]
  mylib = { workspace = true }` 指向本地——发布时 sources 自动
  剥离（开发态本地解析、发布态走版本）。
- 单锁整仓：`uv lock` 作用于全 workspace；`uv run --package
  svc-a` 在指定成员上下文执行；`uv sync --package pkg` 只装该
  成员的依赖组——按需装配，别全家桶。
- 坑位清单：根包要有独立名字（root 也能被成员依赖）；
  workspace 覆盖只在开发态——成员间版本约束仍要兼容**真实
  发布版本**（否则发版即断）；dev 依赖用 PEP 735
  `[dependency-groups]`，别混进 optional-dependencies（那会被
  终端用户装上，82 节）。
- 发布流：成员各自 `uv build --package x`；版本/changelog 按包
  管理；CI 按 package 维度裁剪 job（改 libs/foo 只测依赖它的
  服务）。
- 何时别用：两三个无共享代码的仓库硬凑 monorepo 没收益——
  workspace 的价值在**共享内部库 + 单锁一致性**。

## 80. editable install 原理

- PEP 660 editable wheel：`uv sync` 默认把**当前项目/workspace
  成员**装成 editable——机制 = wheel 里放 .pth 文件/import
  钩子重定向到源码树，site-packages 只是指路牌。
- 严格模式：setuptools/hatch 支持 strict editable（用
  MetaPathFinder 只暴露**声明的**文件）——防"包外模块"意外可
  导入（拼错路径也能 import 成功的隐形 bug）。
- 验证姿势：`import pkg; pkg.__file__` 必须指向源码树——指向
  site-packages = editable 没生效（双环境/重复安装）；改一行
  代码不重装就生效是 editable 的可观测行为。
- 坑：subprocess/多进程继承的 sys.path 依入口——console script
  的 wrapper 不带 `PYTHONPATH`，spawn 子进程（43 节）走解释器
  重导入，一切正常；但手动 `sys.path.insert` 的老代码与 editable
  的钩子叠加会出现**双份模块**（同模块两个实例，isinstance 跨
  模块失败）。
- Docker：volume 挂载源码 + editable = 开发容器热改；生产镜像
  **不用 editable**（构建 wheel 装正式版，81 节）。

## 快速索引 · 续（81-115 节）

| 想找什么 | 去哪一节 |
| --- | --- |
| wheel 平台标签 | 81. wheel vs sdist 与 abi3 |
| extras/marker 设计 | 82. 依赖标记与 extras 设计 |
| console_scripts/插件端点 | 83. 入口点机制 |
| lint 规则集 | 84. Ruff 与 lint 纪律 |
| 循环导入解法 | 85. import 排序与循环导入治理 |
| CI 加 free-threaded | 86. CI 的 Python 矩阵与 free-threading 测试 |
| PyPI 发布安全 | 87. 发布纪律与 Trusted Publishing |
| 依赖漏洞扫描 | 88. 依赖审计与 SBOM |
| except* 用法 | 89. 异常组与 except* 语义 |
| 业务异常设计 | 90. 异常层次设计 |
| 异常链与吞没 | 91. raise from 与异常吞没 |
| 重试策略 | 92. 重试与退避架构 |
| deadline 传播 | 93. 超时架构分层 |
| 错误分类表 | 94. 错误分类：可重试、不可重试与致命 |
| fixture 作用域 | 95. pytest fixture 作用域与组合 |
| 工厂 fixture | 96. factory-as-fixture 模式 |
| 参数化用例 ID | 97. parametrize 进阶与 ID 治理 |
| async 测试事件循环 | 98. async 测试与事件循环策略 |
| mock 用还是 fake | 99. mock 边界纪律 |
| 测试顺序依赖 | 100. 测试隔离与全局状态 |
| 覆盖率怎么看 | 101. 覆盖率的正确用法 |
| 生产 CPU 高 | 102. py-spy 生产剖析 |
| 行级内存热点 | 103. Scalene 行级剖析 |
| 生产断点 | 104. 调试器与断点工程 |
| 段错误/卡死取证 | 105. faulthandler 与崩溃取证 |
| 日志配置纪律 | 106. logging 生产纪律 |
| 基准测试方法 | 107. 基准测试纪律 |
| FFI 选型 | 108. ctypes vs cffi 选型 |
| Rust 扩展 | 109. PyO3 与 Rust 扩展 |
| 零拷贝数组 | 110. 缓冲区协议与 numpy 互操作 |
| 子进程死锁 | 111. subprocess 纪律 |
| C 调用阻塞一切 | 112. C 扩展中释放 GIL |
| import 期炸 | 113. 导入期事故模式 |
| 症状对照原因 | 114. 生产事故模式速查 |
| 评审 checklist | 115. 高级 Python 评审清单 |

## 81. wheel vs sdist 与 abi3

- 安装经济学：wheel = 免编译免 build 隔离直接解压；纯 Python
  打 `py3-none-any`；C 扩展打 `cpXX-abi3`（绑定最低支持版本，
  如 cp39-abi3 一份 wheel 通吃 3.9+）或逐版本 cpXX。
- abi3 边界：使用的 C API 必须全部在 stable ABI 内；**PyO3 的
  abi3 与 free-threading 不兼容**（ft 必须专用 wheel，109 节）；
  用了不稳定 API 就老实逐版本构建。
- 平台标签：manylinux2014/manylinux_2_28（PEP 600 系列）、
  musllinux（Alpine）、macOS universal2——auditwheel repair
  修 tag 与依赖库捆绑；cibuildwheel 是矩阵构建标准工具（86 节）。
- sdist 仍要发布：发行版打包者（Debian/conda）与源码构建兜底
  需要；`pip download --no-binary` 验证 sdist 可构建——坏了
  就"pip install 失败"的 issue 刷屏。
- 消费侧纪律：容器基础镜像与平台标签匹配（Alpine 装不了
  manylinux wheel → 源码编译地狱）；`--only-binary=:all:` 在
  CI 验证关键依赖全平台有 wheel（9 节升级 gate 同源）。

## 82. 依赖标记与 extras 设计

- 环境标记：`'numpy>=1.26; python_version >= "3.13"'`——比较是
  **字符串语义**：`python_version >= "3.10"` 满足但 `< "3.13"`
  要写区间另一端；平台 `platform_system == "Windows"`；
  组合 `and`/`or` 慎用（解析器差异）。
- extras 设计：`pkg[async]` 惰性特性包——包内配套运行时探测
  （12 节 find_spec）+ CI 矩阵**每种 extra 各跑一轮**；
  聚合用自引用 `pkg[full] = {pkg[a], pkg[b]}`。
- 冲突治理：互斥方案（两种驱动/两种序列化后端）拆成两个
  extras，靠用户选择——依赖解算器不处理"装了再说"。
- dev 依赖归位：PEP 735 `[dependency-groups] dev = [...]`——
  uv 原生支持；塞进 optional-dependencies 会被终端用户装上
  （真实事故：用户环境里多出一堆 pytest）。
- 上界策略之争：库少 pin 上界（信任 semver + 用户锁文件），
  应用严格锁（uv.lock 是唯一真相）；折中是只在**已知会破**
  的主版本上加上界并写 changelog 说明。

## 83. 入口点机制

- `[project.scripts] cli = "pkg.module:main"`：安装时生成可执行
  wrapper，运行时 importlib 导入 `pkg.module` 并调 `main`——
  **模块导入失败 = CLI 启动失败**（113 节控制顶层依赖是入口点
  的隐含契约）；入口函数保持零参数（argv 自己 parse）。
- 插件端点：`[project.entry-points."pkg.plugins"] name =
  "mod:attr"`；宿主用 `importlib.metadata.entry_points(group=
  "pkg.plugins")`（3.10+ 直接关键字过滤）；`.load()` 才真正
  import——**延迟导入**让启动时间与插件数量解耦（10 节）。

```python
from importlib.metadata import entry_points
PLUGINS = entry_points(group="myapp.plugins")
for ep in PLUGINS:
    plugin = ep.load()      # 首次真正 import
```

- 契约纪律：group 名是生态公共 API——重命名 = 断所有插件；
  重复 name 的解析行为未定义——CI 校验唯一性。
- 调试：`pip show -f pkg` 或直接查 `*.dist-info/entry_points.txt`
  ——入口不生效（命令不存在）先看这里，再怀疑 PATH。

## 84. Ruff 与 lint 纪律

- 一个工具替换 flake8 + isort + pyupgrade + 部分 pylint（lint
  与 format 双模式）；配置全收 `[tool.ruff]`：`select` 规则族、
  `per-file-ignores`、`line-length`——与 77 节单文件原则一致。
- 高价值规则族：`B`（bugbear：B006 可变默认/B008 函数调用默认/
  B012 finally 里 return/B904 raise from）；`ASYNC`（async 里
  出现 requests/time.sleep——40 节反模式的静态门卫）；
  `S`（bandit 系安全：assert/eval/subprocess 注入面）；
  `PT`（pytest 惯法）；`PERF`（性能反模式）。
- 工作流：`ruff check --fix` 安全自动修（幂等可重复跑）、
  `ruff format`（black 兼容输出）；pre-commit 两个 hook；
  CI `--exit-non-zero-on-fix` 防本地没跑就提交。
- 采纳节奏：先 `ruff check --statistics` 看分布 → 一次 fix
  baseline → 不可自动修的进 ignore（带原因注释）→ 新增代码
  立即拦截（增量收紧，24 节同款 ratchet 思路）。
- 边界认知：lint 抓的是**模式**不是正确性——并发/取消/资源
  泄漏大多要人审（115 节清单），别把绿 lint 当质量上限。

## 85. import 排序与循环导入治理

- 根因：模块顶层互相引用 + import 即执行；症状：
  `ImportError: cannot import name 'X' from partially initialized
  module`（顺序敏感：测试收集顺序决定谁先炸）。
- 解法优先级：① 重构把共享下沉到第三模块（结构解）；
  ② `if TYPE_CHECKING:` 只还类型债（22 节）；③ 函数内延迟导入
  （运行时才付成本，常兼启动优化）；④ `__init__.py` 只做
  re-export 不写逻辑（113 节）。
- 检测工具：`python -X importtime`（耗时图）、pydeps（依赖图）；
  **import-linter** 声明"层"契约（domain 不许 import api、
  utils 不许 import 一切）——契约进 CI，循环导入从"偶发"变
  "构建失败"。
- 深夜事故形态：异常路径里的 `except ImportError: import
  fallback` ——平时不炸（主路径正常），依赖坏了才走 fallback
  而它循环——fallback 导入也要进测试路径。
- 排序规则：`src`/标准库/第三方/本地四段（isort 语义，ruff
  `I` 规则族），`force-single-line` 按团队约定——风格统一比
  具体风格重要。

## 86. CI 的 Python 矩阵与 free-threading 测试

- 版本轴：最低支持版 + 最高稳定版（行为差异最大的两端，9 节）；
  加 free-threaded：`uv python install 3.14t` + `uv run --python
  3.14t pytest`（先串行全绿，再用 `pytest-run-parallel` 插件
  压并发找数据竞争）。
- 警告即错误：`-W error::DeprecationWarning` 管自己代码，
  依赖噪声用 `filterwarnings` 带 module 限定 ignore（pytest.ini
  形式）——全量 error 再白名单，方向别反。
- wheel 构建：cibuildwheel 覆盖平台 × abi3 × ft（81 节标签）；
  发布前 `pip install pkg==x --only-binary=:all:` 全矩阵冒烟
  （缺失 wheel = 用户源码编译地狱的预防针）。
- 速度工程：uv 缓存 key 按 lock 哈希；push 全量矩阵、PR 只跑
  两端（最低+最高）+ 受影响包（79 节裁剪）；矩阵 job 命名带
  `py3.13t` 这类后缀，报告一眼可读。
- 断言运行模式：ft job 里 `sys._is_gil_enabled() is False`、
  普通 job 断言正常构建（1 节）——防止 job 配错悄悄变摆设。

## 87. 发布纪律与 Trusted Publishing

- Trusted Publishing（PyPI OIDC）：GitHub Actions 环境 → PyPI
  项目绑定，无 token 可泄漏；uv `uv publish --trusted-publishing
  automatic`；传统 API token 只留给无法 OIDC 的环境。
- 签名趋势：Sigstore 生态（`sigstore-python`）成为 PyPI 包
  签名方向，CPython 官方发行版 3.14 起提供 Sigstore 签名
  （供应链可验证性是平台级动作）——库作者关注但不强上。
- 版本纪律：库走 semver（0.x 期宽松语义但要写清）；版本号
  唯一来源 pyproject（dynamic + git tag，77 节）——手改版本
  的仓库必然漂移；坏版本用 yank（不删除、默认解析屏蔽）。
- 流程：tag → `uv build`（wheel + sdist 都出）→ 校验（
  `twine check`/uv 自检）→ publish → 冒烟 `pip install`；
  绝不从工作区直接发（未构建测试的发布是事故姿势）。
- 权限最小化：发布 workflow `permissions: id-token: write,
  contents: read`；tag 保护规则防误推 tag 触发发布。

## 88. 依赖审计与 SBOM

- `pip-audit`（OSV/CISA 数据源）扫环境或 lockfile——本地 +
  CI 定时任务（漏洞库在更新，PR 时扫不到的漏洞下周出现）；
  uv 项目用 `uv export --format requirements-txt | pip-audit
  --requirements -`。
- SBOM：cyclonedx-python 产出 json/yaml 交平台（合规/采购要）；
  SBOM 的价值在"事发时反查谁受影响"（xz 式上游投毒的现实
  响应流程）——生成要进发布流水线而不是事后补。
- 风险面排序：构建工具链与低维护传递依赖 > 高星直接依赖；
  `uv tree --depth 3` 定期人工过（新冒出来的传递依赖要有人
  认领）。
- 私有镜像：`[[tool.uv.index]]` + 凭据走环境；镜像不同步 =
  解析漂移——CI 与生产**同源**（同 index + 同 lock 哈希）。
- 锁即安全边界：uv.lock 含下载哈希（供应链完整性）；
  `--no-deps`/离线环境的全量导出要显式（`uv export --no-emit-
  project`）——漏导出 = 生产环境解析自由发挥。

## 89. 异常组与 except* 语义

- 3.11+：TaskGroup（27 节）与并发边界抛 `ExceptionGroup`（包
  BaseException 的变体是 `BaseExceptionGroup`）；`except* T` 从组里
  **抽取匹配子组**处理，多个 except* 分支可命中同一组的不同部分。
- 硬规则：同一个 try 里 `except` 与 `except*` **不能混用**；
  except* 分支即使不匹配也参与组结构判定——语义是"分流"不是
  "顺序测试"。
- 组操作：`eg.split(TypeError)`/`eg.subgroup(...)` 过滤、
  `eg.derive(excs)` 重造同结构组、`eg.exceptions` 平铺遍历
  （树形嵌套，写递归 util 打印）。
- 传播纪律：处理不完就 `raise`（保留整组）或 `raise New() from eg`
  （91 节因果链）；把组打散只抛第一个 = 丢掉兄弟任务的失败现场。
- 设计边界：库对外**只抛单异常**（内部聚好转译）；ExceptionGroup
  留给"真多错并行"的边界（TaskGroup 出口、fan-out 收集器）——
  业务 API 里抛组是接口设计味道。

## 90. 异常层次设计

- 域异常骨架：`AppError(Exception)` 携 `code`/`context` 结构化
  字段；具体子类（`ConfigError`/`UpstreamError`/`ValidationError`）
  ——错误**分类靠类型不靠 message 解析**（message 是给人看的）。
- 可序列化：异常 `args` 必须全 picklable——multiprocessing 回传
  （45 节）、任务队列、重试中间件都会搬运异常；不可序列化字段
  （连接对象/锁）在 `__reduce__` 里置 None 或转 repr。
- 捕获纪律：按**语义层**捕获（Retryable/Permanent，94 节）而不是
  逐个具体类 except——异常类改名/合并时调用方不碎。
- 库异常是公共 API：重命名/移除 = 破坏性变更，走 deprecation
  周期（新类 + 旧类别名留一版）；对外文档列"本库会抛什么"清单。
- 反模式：继承 `SystemExit`/`KeyboardInterrupt`（把它们当"快速
  出口"会击穿 28 节取消语义与 CLI 框架）；空异常体 `pass`——
  至少 `logger.debug("...", exc_info=True)`。

## 91. raise from 与异常吞没

- `raise NewError(...) from e`：显式因果（`__cause__`）；except 里抛新
  异常会隐式链 `__context__`——压制才 `from None` 且必须先打日志
  （吞异常的唯一合法形态）。
- bare `raise`：except 里原样重抛、traceback 完整保留——比
  `raise e` 强（后者重置 traceback 起点）；记日志后再抛是标准三行：

```python
try:
    await client.call()
except TimeoutError:
    logger.warning("call timeout, will surface", exc_info=True)
    raise                        # 保留原始 traceback
```

- 吞异常白名单：`contextlib.suppress(FileNotFoundError)`——精确
  类型；宽 `except Exception: pass` 必须配日志 + 计数（事故现场零证据）。
- finally 三禁：finally 里 return/break/continue 会**丢弃**进行中的异常
  （ruff B012 拦）；finally 抛新异常同样掩盖——重清理挪到 except。
- 链的日志成本：嵌套链越长 traceback 越大（70 节）——深层服务
  对每层重抛要有克制。

## 92. 重试与退避架构

- 指数退避 + **全抖动**：`sleep = random.uniform(0, base * 2**n)`
  ——无抖动 = 同步重试风暴（凌晨一点所有实例同时醒来是分布式
  经典事故）；tenacity：
  `retry=retry_if_exception_type(Retryable), wait=wait_exponential_jitter,
  stop=stop_after_attempt(5)`。
- 只重试幂等：GET/PUT 天然幂等；POST 靠幂等键（服务端去重）或
  改"查询结果状态"——不幂等的重试 = 重复下单。
- 位置：重试装饰在**客户端 stub 层**（每个下游一个 retry 策略），
  别散在业务函数——散装重试会叠加（92 与 93 节组合失控）。
- 预算与熔断：全局重试预算（每分钟上限）防放大——1 次失败 ×3
  层 ×5 次 = 上百倍流量；熔断器在重试之上（连续失败开闸直接
  fail fast，半开探测恢复）。
- 可观测：每次重试打 metric（下游 × 操作 × attempt），重试率
  上升是下游变慢的前兆告警；无限重试是事故配置，`stop_after_*`
  必填。

## 93. 超时架构分层

- 三层各司其职：connect/read（传输层——连接池/驱动级）、
  per-operation（客户端级——单次调用）、request deadline
  （业务级——整请求预算）。
- deadline 传播：外层用**绝对时间戳**（monotonic，25 节）沿调用
  链下传（header/context），内层算剩余时间：
  `asyncio.timeout(deadline - loop.time())`（30 节）——相对时间
  逐层衰减会让总时长 = 各层之和（超预算）。
- 嵌套语义：距 deadline 最近的一层先炸；捕获内层 TimeoutError
  后还能用剩余时间试下一个实例（failover）——超时不是异常是
  控制信号。
- 默认值纪律：每个出站调用必须有默认超时——没写 = 挂到对端
  TCP 超时（分钟级）拖垮线程池/协程；默认收紧（1-5s）、按调用
  显式放宽。
- 观测：超时事件按下游 × 操作打 metrics + trace 串联重试
  （92 节）——"谁在超时"是容量规划的第一输入。

## 94. 错误分类：可重试、不可重试与致命

- 三桶分类学：transient（网络抖动/限流/超时 → 退避重试）、
  permanent（4xx 参数错/配置错/校验失败 → fail fast + 告警修
  代码）、fatal（OOM/磁盘满/依赖完全不可用 → 崩溃让编排器拉起，
  别带病服务）。
- 分类实现在异常上：`class Retryable(AppError)`/`Permanent(AppError)`
  mixin（90 节），调用点单分派 `isinstance(e, Retryable)`——
  分类逻辑集中一处，新错误类型默认进 permanent（保守）。
- 不重试清单：不可幂等操作、已提交事务、取消（CancelledError
  不是业务错误，28 节）、以及"用户取消"语义的异常。
- HTTP 映射：transient → 502/503 + Retry-After；permanent →
  4xx（带可行动 message）；fatal → 503 + 全局健康检查失败
  （K8s 探针联动，35 节）。
- 反模式：把所有异常当 transient 无脑重试（放大故障）；把 OOM
  当可重试（每次重试都是一次新的 OOM 风暴）；错误分类只写在
  wiki 不写在类型里。

## 95. pytest fixture 作用域与组合

- 作用域成本阶梯：function → class → module → package → session；
  升作用域省时间**但引入共享状态**——session 只放真正全局只读
  （配置/容器/密钥），可变状态一律 function 级。
- yield fixture = setup/teardown 一体（finally 语义）；teardown
  抛异常会**掩盖测试失败**（报告成 error 而非 failure）——清理
  失败要 try/except + log 包裹。
- conftest 层级：fixture 按目录就近可见；公共 fixture 提到顶层
  conftest 但用**前缀命名空间**（`db_`/`http_`）防撞名——两年
  后没人记得哪个 conftest 里有哪个 fixture。
- 组合方式：fixture 参数引用其他 fixture（依赖注入）；比
  `usefixtures` marker 显式；跨作用域引用（function 引 session）
  合法，反方向非法。
- 反模式三连：fixture 链深过 5 层（读测试要追 5 跳）；fixture
  里做业务断言（该拆出来）；fixture 返回裸 MagicMock（该给
  Protocol 型 fake，99 节）。

## 96. factory-as-fixture 模式

- 动机：一次测试需要**多个**对象或每测试要**唯一**数据——固定
  fixture 只能给一份；工厂模式让测试自产自销：

```python
@pytest.fixture
def make_user(db):
    made: list[User] = []
    def _make(**over):
        u = User(name=fake.unique.name(), **over)
        db.add(u); made.append(u)
        return u
    yield _make
    db.rollback()          # 或按 made 显式清理
```

- 默认值哲学：合理默认 + 关键字段 override
  （`make_user(role="admin")`）——测试只写"在乎的字段"，噪音藏工厂里。
- 唯一性：随机字段（faker+unique）防测试间串数据/唯一索引撞（100 节）。
- cleanup：工厂登记产出物，yield 后统一回收/回滚，免每测试手动删。
- 参数化边界：给工厂 fixture 加 params 会×所有用例——先评估爆炸面（97 节）。

## 97. parametrize 进阶与 ID 治理

- ID 是 API：默认 ID 是参数 repr（对象 repr = 乱码）——
  `pytest.param(case, id="empty-payload-422")` 显式命名；
  队友天天 `pytest -k "422"` 过滤，命名质量 = 可用性。
- 结构化用例：参数打包成 case 对象
  （`Case = namedtuple("Case", "payload want_status reason")`）
  ——比裸元组可读；reason 字段进失败输出，看失败报告不用翻代码。
- 组合爆炸治理：多装饰器相乘是笛卡尔积——先列维表数（3×4×5
  = 60 用例是否必要）；大矩阵用 pairwise/标签抽样，保留全量在
  nightly。
- marks 挂在 param 上：`pytest.param(..., marks=pytest.mark.xfail(
  reason="upstream bug #123", strict=True))`——strict xfail 防止
  "永远 xfail 的绿"（修好了没人知道）。
- lazy fixture 引用：pytest-lazy-fixtures 让 parametrize 里引用
  fixture 值——按需引入，别当默认（隐式依赖又回来一层）。

## 98. async 测试与事件循环策略

- pytest-asyncio 0.24+：`asyncio_mode = "strict"` + 每个测试显式
  `@pytest.mark.asyncio`（auto 模式静默包装一切 async 测试——
  掩盖循环策略与模式差异）；`loop_scope` 控制循环生命周期。
- 循环错配事故（最高频）：session 级资源（asyncpg 连接池）绑在
  session 循环，测试用 function 循环 → "attached to a different
  loop"——池的 loop_scope 与测试 loop_scope 必须对齐
  （资源 session + 测试 function 的组合要显式设计）。
- anyio 插件：`@pytest.mark.anyio` + `anyio_backend` fixture
  参数化 asyncio/trio——**库**要跨后端必配；**应用**选单后端
  就 pytest-asyncio，两个插件同装会打架。
- 时钟与时序：`time-machine`/freezegun 冻结时间测超时逻辑；
  异步超时测试用极小 timeout 断言快速失败，**绝不真实 sleep**
  等待——flaky 的第一产地。
- 取消路径测试：显式测"任务被取消后资源清理"（28/33 节）——
  `asyncio.timeout` 里跑被测协程、断言 CancelledError 与
  资源已关；这条路径默认零覆盖（101 节盲区）。

## 99. mock 边界纪律

- 优先级：真实现 > 手写 fake（实现 Protocol，15 节）> mock。
  mock 只贴在**你拥有的边界**（自己的 client/adapter 层）——
  直接 mock 第三方库内部 = 升级即碎（私有方法变签名）。
- `monkeypatch`（pytest 内建，测试结束自动还原）优于
  `mock.patch` 装饰器——漏 unpatch 的 mock 泄漏到其他测试，
  顺序依赖的著名制造机（100 节）。
- `spec=` 强制形状：无 spec 的 mock 什么都接受——拼写错误
  静默通过（`mock.assert_called_once` vs `assert_called_once_with`
  的细节错误也常见）；`spec=RealClass` + autospec=True。
- AsyncMock：await 语义独立计数——`assert_awaited_with`/
  `await_count`（call_count 不含 await）；漏检 await = 
  fire-and-forget bug 静默通过（38 节）。
- 契约测试补位：mock 掉的边界要有**对真实现的独立契约测试**
  （对着 staging/容器跑）——全 mock 的测试套件测的是你的
  假设不是世界。

## 100. 测试隔离与全局状态

- 全局状态清单（每项都要 fixture 化）：环境变量
  （monkeypatch.setenv）、cwd（tmp_path + os.chdir fixture）、
  TZ/locale、随机种子（random.seed(0) 或 faker fixture）、
  模块级缓存/单例（fixture 里重置）、asyncio 事件循环（98 节）。
- 顺序依赖检测：`pytest-randomly`（随机 order + 每次打印种子）
  跑一周——炸的测试修隔离而不是按顺序排（排顺序 = 把债滚到
  夜里）；复现用种子一键重放。
- 网络零容忍：`pytest-socket`（默认禁网）——单元套件碰网 =
  依赖外网的 CI 是玄学；DB 用每测试事务回滚（begin/rollback
  fixture）快于 truncate。
- 文件与并行：`tmp_path` 每测试独立目录；仓库相对路径在
  `pytest-xdist` 并行下必炸——所有路径从 fixture 出。
- 全局单例对策：库里的 `REGISTRY`/缓存——测试 fixture 里
  `REGISTRY.clear()` + 用 contextvar/fixture 注入替代模块级
  单例（68 节注册表的测试形态）。

## 101. 覆盖率的正确用法

- 定性：覆盖率是**回归保护面**指标，不是质量指标——
  分支覆盖 > 行覆盖；`--cov-fail-under` 做只升不降 ratchet
  （与 24 节 ignore 数同一玩法）。
- 排除要显式带因：`# pragma: no cover -- 仅 3.13 路径`；
  大段 exclude_lines 是把问题扫进地毯下。
- 虚高盲区：mock 挡住的集成路径全绿但生产连线就挂——分层
  报告（unit 与 integration 分开统计）；subprocess 覆盖要
  `COVERAGE_PROCESS_START` + sitecustomize 钩子（子进程也采集，
  合并报告）。
- 强度升级：mutmut 抽样变异测试——"改一行逻辑，测试会不会
  挂？"比行覆盖诚实十倍；nightly 跑、按模块抽样。
- 运营化：覆盖率报告进 PR comment（diff 覆盖率优先于总覆盖率
  ——新代码必须高覆盖）；绝对值目标（"90%"）不如趋势目标。

## 102. py-spy 生产剖析

- 免侵入采样：`py-spy dump --pid PID`（瞬间抓全线程栈——卡死
  现场第一手）、`py-spy top`（实时 top）、`py-spy record -o
  flame.svg --pid PID`（火焰图）——不改代码、不停服务。
- 开销与权限：采样 ~1% 级开销（降采样频率更低）；只读 ptrace
  ——容器需要 `SYS_PTRACE` capability 或同 PID namespace 的
  sidecar；`/proc/sys/kernel/yama/ptrace_scope` 限制非父子进程
  attach（运维预配置）。
- `--native` 混入 C 栈：numpy/DB 驱动/native 挂死只有它能看见；
  `--gil` 采样 GIL 等待事件（41 节争用可视化）；
  `--subprocesses` 追多进程树（45 节 worker 各自采样）。
- 双 dump 判别法：隔 10s 两次 dump——栈**不变** = 卡（锁/IO
  挂死），栈**在变** = 热循环（record 采样细看）。
- 输出集成：`--format speedscope` 进 speedscope 分析；
  dump 文本贴进 incident 工单——排查 SOP 里写死第一条就是它。

## 103. Scalene 行级剖析

- 三合一行级：CPU（**Python 时间与 native 时间分列**）+ 内存
  （每行增长/收缩）+ GPU；`scalene app.py` 出 HTML 报告——
  开发期定位"哪一行在烧 CPU/分配内存"。
- 关键能力：分离 Python/native——"Python 慢"经常是 numpy 后面
  的一段纯 Python glue（循环包着 C 调用）；cProfile 把两边混在
  一起，Scalene 拆开看就知该优化哪层。
- 内存口径：行级定位**分配热点**（不是最终占用峰值）——泄漏
  与分配风暴是两种病：泄漏找 memray/objgraph（62 节），分配
  热点找 Scalene。
- memray 补充：attach 运行中进程、native 分配追踪（C 扩展
  泄漏）、火焰图与 live 模式——生产长跑采样场景比 Scalene 合适。
- 纪律：profiler 结果只看 Top-5 行占比（总量无意义）；用
  **生产级数据形态** profile（全 mock 的 profile 是自我安慰）；
  优化后重跑对比（107 节基准纪律）。

## 104. 调试器与断点工程

- `breakpoint()` 内建：`PYTHONBREAKPOINT=ipdb.set_trace` 切换实现、
  `PYTHONBREAKPOINT=0` 硬禁（生产镜像默认禁，防止误提交的断点
  拖住 worker）；`sys.breakpointhook` 可换成自定义（日志+回执）。
- 事后栈：ipdb 里 `pdb.pm()` 进最近异常现场；崩溃 dump 配 gdb +
  python 扩展（装对应 debug symbols）看 native 混合栈（108 节
  段错误的取证路径）。
- 远程：debugpy `listen(port)` + IDE attach（DAP 协议）——
  容器/远程开发的标准；调试端口不暴露公网（本地 port-forward）。
- 工具开销演进：`sys.settrace` 每事件回调（旧调试器/coverage 的
  性能税）→ 3.12 `sys.monitoring`（PEP 669）全局禁用 + 局部启用，
  coverage/调试器开销降一个数量级——选支持 monitoring 的工具。
- 条件断点慢的真相：解释器里逐次求值条件——热循环里改用
  `if cond: breakpoint()` + 日志 + py-spy（102 节）；生产问题的
  第一工具永远是 py-spy/日志，不是断点。

## 105. faulthandler 与崩溃取证

- 启动第一行就 `faulthandler.enable()`（或 `PYTHONFAULTHANDLER=1`）：
  段错误/abort 时打印**全线程栈**到 stderr——原生崩溃唯一的
  救命日志（C 扩展/ctypes 越界的现场证据，108 节）。
- 挂死看门狗：`faulthandler.dump_traceback_later(timeout=60,
  repeat=True)` 周期 dump——"偶发 hang"的定位（两次 dump 对比
  看 102 节判别法）；恢复后 `cancel_dump_traceback_later()`。
- 信号取证：`faulthandler.register(signal.SIGUSR2)` 运维一键
  dump（kill -USR2 pid）——比登机器装工具快十倍；注册去重防
  多 worker 双打印。
- fork 交互：子进程继承 faulthandler（43 节）——multiprocessing
  worker 也会 dump，日志聚合时按 pid 分流。
- 收集纪律：dump 走 **stderr**——容器/服务收集器必须收 stderr
  （只收 stdout = 丢掉全部崩溃证据）；fluentd/systemd 配置里
  常见的静默丢栈事故。

## 106. logging 生产纪律

- 结构三原则：每模块 `logger = logging.getLogger(__name__)`；
  库**零配置**（root 挂 NullHandler）；应用唯一入口 `dictConfig`
  （级别/格式/handler 全在配置文件，改动不发布）。
- 惰性格式化：`log.info("id=%s", oid)`——禁用级别零格式化；
  f-string 日志无条件格式化（热路径 3-10x 差距，59 节）且丢掉
  结构化字段（采集器只能正则）。
- 生产形态：JSON 结构化（structlog）输出 stdout → 采集器；
  `QueueHandler` + `QueueListener` 非阻塞（日志后端抖动不拖
  业务线程——同步 handler 的网络调用是隐形串行点）。
- 全栈语义：`logger.exception("msg")` = error 级 + exc_info=True
  （当前异常全栈）；手动传 `exc_info=e` 记捕到的异常——
  吞异常点必配（91 节）。
- 级别纪律：ERROR = 有人要被叫醒、WARNING = 自愈但要计数、
  INFO = 业务事件、DEBUG = 开发期——INFO 洪水让 ERROR 沉没是
  告警疲劳的第一来源；敏感字段（token/PII）在 formatter 层
  统一脱敏。

## 107. 基准测试纪律

- timeit 姿势：`number` 调到单次运行 ≥0.2s、`repeat` ≥5 取
  **min**（噪音只加分不减）；`--setup` 隔离准备代码；关闭 GC
  或显式 enable 两种口径都测（60 节 GC 成本不是常数）。
- 严肃微基准：pyperf（统计学显著 + 系统调优提示：性能核隔离/
  离线/mlock）；宏观：生产流量回放 + py-spy 前后对比——
  两级都要，微基准说谎（缓存/分支预测/分配状态）是常态。
- 五大陷阱：首次 import 计入（warmup 缺失）；全局缓存预热差异；
  `time.time()` 用作间隔（NTP 跳变，25 节——用 perf_counter/
  monotonic）；多进程基准混入 pickle 时间（45/47 节）；只测
  均值不看分布（p99 才是 SLO）。
- CI 回归门：benchmark job 基线对比 ±5% 预算（codspeed 或自建）；
  性能 PR 必须附基准输出（截图/表），无基准的"优化"不予合并。
- 环境一致性：同一台机器/同一 CPU 管理器配置对比；容器 CPU
  quota 会放大调度噪音——基准机不开超卖。

## 108. ctypes vs cffi 选型

- ctypes：纯 Python 声明（`CDLL("libx.so")` + argtypes），零编译
  门槛；但**每次调用**有参数转换开销（~µs 级）——热循环灾难；
  `CDLL` 调用**默认释放 GIL**（`PyDLL` 不释放）——长 C 调用不卡
  线程（112 节验证）。
- cffi 两模式：ABI 模式（运行期 dlopen，比 ctypes 快）/API 模式
  （编译期绑定 C 声明，接近手写扩展速度）——性能敏感一律 API
  模式（多一步编译，打包复杂度上升，81 节矩阵）。
- struct 内存：对齐/填充手算或 `struct.pack/unpack_from` 显式
  布局；ctypes 数组/指针越界 = 段错误无栈（105 节取证）。
- 生命周期雷：ctypes buffer 被 GC 而指针还在 C 侧 = 崩溃——
  显式 keepalive 引用规则（"谁传指针谁保活"，110 节同族）；
  回调函数对象同样要保活。
- 决策顺序：能用 Python 生态库就别 FFI；必须 FFI：一次性胶水
  → ctypes；长期性能路径 → cffi API 模式或 PyO3（109 节）。

## 109. PyO3 与 Rust 扩展

- 首选栈：`#[pyclass]`/`#[pymethods]` + maturin（PEP 517 backend，
  pyproject 原生，77 节）——纯 Rust 写扩展的工程化程度最高的
  路线（scipy 生态与众多性能库已采用）。
- wheel 矩阵：`abi3-py39` feature 出稳定 ABI wheel 缩矩阵；
  **free-threading 与 abi3 不兼容**——ft 支持要版本专用 wheel
  （PyO3 0.22+ 支持 ft 构建；PyO3 社区已有 3.13 ft 死锁案例，
  `Python::with_gil` 语义在 ft 下要重新审计，2 节）。
- GIL 纪律：跨 FFI 的长操作用 `py.allow_threads(|| ...)`（112
  节等价物）；**释放窗口里不许碰 PyObject**——引用计数无保护，
  越界 = 崩溃。
- Bound API（0.21+）：`Bound<'py, T>` 显式生命周期——防旧
  GILRef 风格的引用泄漏；升级 0.21+ 是 breaking（迁移指南过
  一遍，别用 `.clone()` 惯性硬顶）。
- 组合建议：扩展只做"纯计算内核"，IO/胶水留 Python——
  两者边界按 profile 数据划（103 节），Rust 化整个模块是
  过度工程。

## 110. 缓冲区协议与 numpy 互操作

- memoryview = 零拷贝视图：`memoryview(buf)[::2]` 切片免复制、
  跨函数传大数据免拷贝；`np.asarray(mv)` 免拷贝进 numpy 计算
  ——解析网络帧/文件块的标配。
- 头号陷阱：memoryview/切片**保持整个底层 buffer 存活**——
  "切 1 字节、持有 1GB"（mmap/大包场景经典）；要释放就把切片
  复制出去：`bytes(mv_slice)`。
- 二进制协议解析：`array` 模块 + `struct.unpack_from(fmt, buf,
  offset)` 显式布局；`int.from_bytes(b, "little")` 零对象开销；
  复杂协议上 construct/kaitai 而不是手写。
- 扩展交互：C 扩展走 `PyObject_GetBuffer` 协议拿 buffer——
  扩展侧必须 `PyBuffer_Release`（109 节）；Python 侧只管引用
  生命周期（keepalive 传参模式）。
- 判定收益：只在"数据大 + 拷贝频繁"时零拷贝有收益——小对象
  memoryview 反而慢（视图对象本身有成本），107 节量测再定。

## 111. subprocess 纪律

- 一次性格式：`subprocess.run([...], capture_output=True,
  timeout=...)`——timeout 触发后 kill 子进程并**等它退出**：
  子进程进不可杀的 D 态/僵尸态，run 照样卡——外层再套 watchdoc
  （105 节）。
- 管道死锁：Popen 双管道（stdout+stderr）不消费 → 64KB 缓冲
  满 → 子进程 write 阻塞 → 永挂；修法 `communicate()`（内部
  线程消费）或只开一根管道。
- `shell=True` 是注入面 + 转义地狱——默认参数列表
  （`["ls", "-l", path]`），日志显示用 `shlex.join`；确实要 shell
  特性（管道/glob）就 `executable=` 钉死解释器并审计输入来源。
- 树杀：Unix `start_new_session=True` 起新进程组 +
  `os.killpg(pgid, SIGTERM)`；Windows `taskkill /T /PID`——
  子进程的子进程不杀 = 资源泄漏（GPU/锁文件遗留）。
- 收尸：`Popen` 对象必须 `wait()`（或 context manager）——忘了
  = 僵尸堆积，容器 PID 压力 + psutil 看不见真进程；
  `close_fds` 默认 True 保持——继承 fd 是 fd 泄漏暗门。

## 112. C 扩展中释放 GIL

- C 宏模板：`Py_BEGIN_ALLOW_THREADS ... Py_END_ALLOW_THREADS`
  /PyO3 `py.allow_threads(|| ...)` /cffi ABI 调用（CDLL）默认
  释放——释放窗口里**不许碰任何 PyObject**（refcount 无保护，
  崩溃或数据竞争）。
- 该释放的：syscalls（read/poll）、密码学、压缩、DB 客户端
  阻塞等待、长计算内核；不该释放的：纯 Python 对象操作的微
  区段（锁开销 > 收益，阈值在 ~µs 级）。
- 高频事故：释放 GIL 后又调 Python API（设异常/回调）——崩溃
  或告警；扩展持有的裸指针在释放期被别的线程释放对象——
  先拿强引用/借 buffer 保活（110 节）再进释放区。
- 验证方法：线程池压测 + 旁路心跳线程（timeout 心跳是否仍按
  时触发）；py-spy `--native` 看释放段的等待形态（102 节）。
- free-threading 时代：`Py_BEGIN_ALLOW_THREADS` 的语义保留但
  必要性下降、`Py_mod_gil_slot` 声明成为新扩展的必修项
  （1-2 节生态矩阵的扩展侧动作）。

## 113. 导入期事故模式

- 表现：模块顶层建连接/读配置/起线程 → import 时网络抖动 =
  服务起不来；pytest **收集阶段**就 import 全部测试模块——
  顶层挂 = collect error（这是发现该事故的最常见入口）。
- 半初始化状态：import 失败的模块从 sys.modules 移除，但**已
  执行的副作用不回滚**（注册表残留、线程已起、atexit 已挂）
  ——上层"重试 import" = 双份资源（68 节注册表 + 一次幽灵线程）。
- `__init__.py` 大 re-export：`from .x import *` 链让"只 import
  一个名字"也执行全包——重初始化敏感库（DB 驱动/模型加载）
  放子模块，`__init__` 只做 `__all__` 与轻 re-export。
- 纪律落地：顶层只允许 def/class/常量/导入；重活进显式
  `init()`/工厂函数（连接池由调用方创建）；可选依赖 `find_spec`
  探测 + 缺失降级（12 节）——三连在评审是一票否决项（115 节）。
- 检测工具：`python -X importtime`（10 节）看耗时；把
  "import pkg" 的墙钟时间加进冒烟测试——import 时间回归是
  依赖膨胀的第一信号。

## 114. 生产事故模式速查

| 症状 | 高频根因 | 首查动作 |
| --- | --- | --- |
| RSS 只涨不跌 | arena 碎片假泄漏（54 节）或引用环/无界缓存 | objgraph 筛类型 → tracemalloc 差分（62 节） |
| 单核 100% 其余闲 | GIL 序列化（41 节）或锁风暴 convoy | py-spy top / --gil（102 节） |
| p99 毛刺 | GC 停顿（60 节）或阻塞调用进事件循环（32 节） | loop debug 慢回调日志 + gc 频率 metrics |
| OOMKill | 无界队列/缓存无 TTL（37、58 节）或 COW 失效 | 队列深度 metric + uss 曲线（62 节） |
| 服务起不来 | import 期副作用（113 节）或 wheel 缺失（9 节） | importtime + 镜像解释器断言 |
| 挂死无堆栈 | fork 死锁（44 节）/C 扩展未释放 GIL（112 节）/管道满（111 节） | faulthandler dump + py-spy dump（105 节） |
| 任务静默消失 | create_task 无强引用被 GC（38 节） | 引用集样板审计 + RuntimeWarning 转错误 |
| 偶发重试风暴 | 无抖动退避 + 层层重试叠加（92 节） | 重试 metrics 按 attempt 分布 |
| 跨进程数据错乱 | spawn 迁移后共享状态假设失效（43 节） | context 断言 + pickle 白名单审计（47 节） |
| 测试本地绿 CI 红 | 全局状态/顺序依赖（100 节）或 editable 双模块（80 节） | pytest-randomly 种子复现 |

- 使用法：值班手册第一页贴这张表——按症状行先做"首查动作"，
  再展开对应节；每个事故复盘回填新行（速查表是活的文档）。

## 115. 高级 Python 评审清单

- 并发正确性：await 之间共享了可变状态吗？取消路径有清理且
  re-raise 吗？fire-and-forget 进引用集了吗（38 节）？
  multiprocessing 默认 spawn 假设成立吗（43 节）？
- 资源生命周期：每个 open/连接/子进程都有 with/finally？
  `__del__` 只做兜底不做主清理（60 节）？超时都有默认值
  （93 节）？
- 类型与接口：新代码过 strict？ignore 带码且总数只降（24 节）？
  破坏性类型变更走 deprecation（90 节）？
- 错误路径：except 精确类型？吞异常有日志有计数（91 节）？
  重试幂等 + 带抖动（92 节）？取消不当业务错误（94 节）？
- 性能预算：热循环分配量与数据结构选择（51-56 节）？缓存
  stampede 防护（58 节）？无界结构（37 节）？
- 兼容矩阵：版本敏感写法有 gate 与注释（12 节）？新依赖
  审计 + 锁更新（88 节）？弃用警告会 `-W error` 爆吗（8 节）？
- 测试真实度：mock 只贴自有边界（99 节）？隔离无顺序依赖
  （100 节）？取消/超时路径有测试（98 节）？
- 供应链：publish 走 Trusted Publishing（87 节）？CI 矩阵含
  free-threaded（86 节）？入口点模块顶层零副作用（83、113 节）？
