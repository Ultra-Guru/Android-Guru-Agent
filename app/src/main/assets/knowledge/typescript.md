<!-- kb: id=typescript | role=builtin_coding_typescript | title=TypeScript 专家知识库 | version=1 -->
# TypeScript 专家知识库

## 必看内容

- **类型只活在编译期，运行时是裸的**：类型全部擦除、无反射。一切外部输入
  （HTTP/ENV/localStorage/postMessage）必须在边界过 schema（zod/TypeBox），
  用 `z.infer` 反推类型 —— Parse, don't validate（63/70 节）。
- **严格阶梯分步爬**：`strict` 只是起点；生产基线叠加 `noUncheckedIndexedAccess`
  + `exactOptionalPropertyTypes` + `verbatimModuleSyntax`。每开一项先统计错误
  基线，用 ratchet（只减不增）落地（33-37 节）。
- **any 是债务，unknown 是纪律**：禁 `as any`/`@ts-ignore`；断言走 `unknown` +
  守卫或 `asserts x is T`，把"我不确定"压缩到可审计的少数几行（24/30/109 节）。
- **satisfies 优先于注解**：配置/路由/i18n 表用 `satisfies` 同时拿契约检查与
  字面量推断；判别联合 + `assertNever` 兜底，加成员必补分支（23/28 节）。
- **泛型只在该用的地方用**：类型参数须出现在 ≥2 个相关位置或约束到有意义的
  域；库的公开导出显式标注类型 —— 同时服务 isolatedDeclarations 与编译性能
  （15/94/95 节）。
- **类型体操要交税**：递归条件类型有实例化深度上限（TS2589，约 50 层，无开关）；
  大联合 × 递归 = 指数爆炸拖垮 tsserver。上线前 `--generateTrace` 实测，路径
  推导限 4-6 层（6/53/99 节）。
- **模块解析按运行时选**：`bundler`（vite/webpack）、`nodenext`（Node ESM）、
  `preserve` 规则各异；`paths` 只影响类型不影响产物 —— 发布的包绝不依赖别名
  （38/51 节）。
- **monorepo 用 Project References 划缓存边界**：`composite` + `tsc -b`；禁止
  跨包 import 源码（击穿增量图）；`extends` 的 `paths` 整体替换不合并
  （41/71-75 节）。TS 7（tsgo，~10x）落地后同架构直接提速（73 节）。
- **升级是破坏性的，即使 minor**：5.6→5.7 有类型破坏先例；锁精确版本，CI 跑
  最低/最高支持矩阵（tstyche/expectTypeOf），升级 PR 独立可回滚（91 节）。
- **enum 基本不选**：`as const` 对象 + `typeof` 取联合（零运行时、可摇树）；
  `const enum` 在 isolatedModules/bundler 下退化，是历史遗留（110 节）。
- **迁移永远渐进**：`allowJs` → `checkJs` + `@ts-nocheck` 债务标记 → 按依赖
  叶子逐文件转 .ts → ratchet 清债，禁止 big-bang（88-90 节）。

## 快速索引

| 想找什么 | 去哪一节 |
| --- | --- |
| 什么类型能悄悄赋值过去 | 1. 结构类型的隐性通道与可分配性深水 |
| 条件类型遇到联合的分配行为 | 2. 条件类型的分配律与分支语义 |
| infer 提取不出想要的类型 | 3. infer 的多位置推断与默认类型 |
| 元组拼接与变长参数建模 | 4. 变长元组类型实战 |
| 路径字符串的类型推导 | 5. 模板字面量类型与字符串路径 |
| TS2589 过深或无限实例化 | 6. 递归类型与实例化深度极限 |
| keyof typeof 内在类型边界 | 7. 内在类型与类型级运算的边界 |
| 函数参数保持字面量推断 | 8. const 类型参数 |
| 判别联合设计与 tag 选择 | 9. 判别联合的设计纪律 |
| nominal 品牌类型怎么做 | 10. 品牌类型与幽灵标记 |
| never 的过滤与兜底用法 | 11. never 的工程用法 |
| 自定义守卫写法与可靠性 | 12. 类型谓词与自定义守卫 |
| 协变逆变的工程判断 | 13. 型变的工程直觉与测量 |
| out in 型变标注怎么用 | 14. in out 型变标注 |
| 该不该加泛型参数 | 15. 泛型参数的设计决断 |
| extends 约束不生效的真相 | 16. 泛型约束与默认值的坑 |
| 条件类型不分配的写法 | 17. 抑制条件类型的分配行为 |
| strictFunctionTypes 的方法例外 | 18. 数组协变陷阱与方法双变 |
| 重载匹配顺序与设计 | 19. 函数重载的解析与设计 |
| 链式 builder 的类型 | 20. 多态 this 类型 |
| 泛型构造器的复用 | 21. 泛型实例化表达式 |
| 关联参数推断成联合丢失关联 | 22. 泛型关联参数模式 |
| satisfies 的实战场景 | 23. satisfies 深度用法 |
| asserts 断言函数 | 24. 断言函数 |
| 别名后收窄失效或生效 | 25. 控制流分析的别名边界 |
| in 操作符收窄 | 26. in 操作符与无属性收窄 |
| 判别键被 widened 成 string | 27. 判别键的字面量陷阱 |
| switch 穷尽性检查 | 28. 穷尽性检查与 never 兜底 |
| 智能收窄失效清单 | 29. 收窄失效清单与修复 |
| any 和 unknown 怎么选 | 30. unknown 与 any 的边界纪律 |
| 字面量 widening 规则 | 31. widening 行为与常量上下文 |
| 空值合并与可选链纪律 | 32. 可空联合处理范式 |
| strict 之上还该开什么 | 33. strict 之上的严格阶梯 |
| 可选属性不等于可 undefined | 34. exactOptionalPropertyTypes |
| arr[i] 取出 undefined | 35. noUncheckedIndexedAccess |
| noImplicitOverride 等小旗 | 36. 被低估的检查项 |
| 强制 import type | 37. verbatimModuleSyntax 与 isolatedModules |
| bundler 与 nodenext 怎么选 | 38. moduleResolution 三种模式 |
| target module lib 联动 | 39. target lib module 联动 |

## 1. 结构类型的隐性通道与可分配性深水

- TS 是结构类型：可分配性只看"形状够不够"。隐性通道清单：
  - **excess property check 只对对象字面量生效** —— 经变量中转/展开后
    检查消失，错误键名带病传播；
  - 目标带索引签名 `[k: string]: T` 时任何"成员兼容"的对象都放行；
  - **弱类型检测**（目标只有可选属性、双方无共同属性时报错）是唯一
    拦住纯拼写错误的机制。

```typescript
interface Point { x: number; y: number }
const bad: Point = { x: 1, y: 2, z: 3 }; // ❌ 字面量直赋：报错
const hold = { x: 1, y: 2, z: 3 };
const viaRef: Point = hold;               // ✅ 中转变量：z 静默漏过
```

- 事故模式：后端把 `userId` 改成 `uid`，前端 DTO 没改 —— 结构兼容照旧
  编译通过，`undefined` 淹到渲染深处才爆。
- 防御：① 边界 schema 校验（70 节）；② 关键结构加判别键（9 节）；
  ③ 公开 API 出现 `Record<string, X>` 先自问是否真放弃键检查（36 节）。

## 2. 条件类型的分配律与分支语义

- `T extends U ? X : Y` 中 **T 是裸类型参数时对联合分配**。推论都会咬人：
  - `never` 是空联合 → 分配结果为 never，条件分支根本不执行；
  - `boolean` 分配成 `true | false` → 分支可能两边都进；
  - 分配后成员独立实例化，**成员间关联丢失**（22 节）。

```typescript
type ToArrayDist<T> = T extends unknown ? T[] : never;
type R1 = ToArrayDist<boolean>;   // true[] | false[] —— 不是 boolean[]！
type ToArrayFlat<T> = [T] extends [unknown] ? T[] : never;
type R2 = ToArrayFlat<boolean>;   // boolean[] —— 元组包裹抑制分配
```

- 分工：分配版做"逐成员变换"（过滤/映射键），抑制版做"整体判断"
  （相等性、never 检测）。
- `IsAny` 技巧：`0 extends (1 & T) ? true : false` —— 只有 any 交叉后
  仍被一切类型兼容，用于防御库边界的 any 污染。
- 陷阱：公开类型上的分配条件类型，其联合结构可能随 TS 版本微调 ——
  公开面给稳定的显式别名（91 节）。

## 3. infer 的多位置推断与默认类型

- `infer` 绑定同一变量：多个**协变**位置 → 联合；**逆变**位置（函数参数）
  → 交叉。这是"推断结果与预期相反"的根源。
- `infer U extends X`（4.7+）给推断加约束；嵌套条件给"推断失败"兜底：

```typescript
type First<T> = T extends readonly [infer H, ...unknown[]] ? H : never;
type FnInput<P> = P extends { run: (input: infer I) => unknown } ? I : unknown;
type ElementOf<T> = T extends readonly (infer E)[] ? E : never;
```

- 实战三式：提取（`T extends Promise<infer V> ? V : T`，递归版注意 6 节
  深度）；兜底（`(...args: never[]) => infer R` 优先匹配任意函数）；
  借位（模板字面量里 `infer Head extends string` 做解析，5 节）。
- 陷阱：`infer` 只能出现在条件类型 extends 子句里；分配条件里每个联合
  成员独立 infer，联合数大时是编译热点（94 节）。
- 用"推断失败给默认"的嵌套条件代替 `any` 兜底 —— any 兜底让下游全体
  静默失效（30 节）。

## 4. 变长元组类型实战

- 变长元组（4.0+）让元组拼接/前置/去尾成为类型级一等操作 ——
  `curry`/`pipe`/`bind` 类型写对的基础设施。

```typescript
type Concat<A extends readonly unknown[], B extends readonly unknown[]> = [...A, ...B];
type Shift<T extends readonly unknown[]> = T extends readonly [unknown, ...infer R] ? R : [];
type Curried<A extends readonly unknown[], R> =
  <B extends readonly unknown[]>(...args: B) =>
    Concat<A, B> extends A ? R : Curried<Concat<A, B>, R>;
```

- 规则：rest 元素在末位；开头 rest（`[...A, X]`）匹配"前缀任意+已知尾部"；
  可选元素混进拼接语义复杂 —— 公开库别依赖边角。
- 陷阱：元组递归拼接的联合成员数是**笛卡尔积**，长参数列表触发 TS2589
  （6 节）—— 先固定长度再递归。
- 实战锚点：`useState` 旧式重载链、fetch 包装、`.bind` 类型；推导写不对
  就退回显式重载（19 节）—— 类型体操让位给正确性。

## 5. 模板字面量类型与字符串路径

- 模板字面量类型是"编译期字符串解析器"：`Uppercase/Lowercase`、
  `Capitalize/Uncapitalize` 四个内在函数只能在此上下文用。

```typescript
type EventName = `${'user' | 'order'}.${'create' | 'remove'}`;
type Join<K, P> = K extends string ? P extends string
  ? `${K}${'' extends P ? '' : '.'}${P}` : never : never;
type Prev = [never, 0, 1, 2, 3, 4, 5, 6, 7];        // 深度表：限 8 层
type Paths<T, D extends number = 5> = [D] extends [never] ? never :
  T extends object ? { [K in keyof T & string]:
    Join<K, Paths<NonNullable<T[K]>, Prev[D]>> }[keyof T & string] : '';
```

- 匹配陷阱：`A.${infer Rest}` 在 `a.b.c` 上取到 `b.c`（非贪婪，与 JS 正则
  直觉相反）—— 解析结构化字符串前先写类型测试（62 节）。
- 成本红线：`keyof T` 联合 × 模板展开 = 平方级；`Paths` 深度默认 4-6，
  超过必拖垮 IDE（53/97 节）。
- 替代：深层路径用 `const` 元组参数 `path(obj, ['a','b'] as const)` ——
  零字符串解析成本，还保住索引收窄。

## 6. 递归类型与实例化深度极限

- 硬上限：**深度约 50 层**（TS2589 "excessively deep"，深尾递归有放宽但
  别指望），**没有开关可调**。计数单位是"实例化步数"：一层里嵌套映射+
  条件可能消耗 3-4 步 —— 逻辑 15 层也可能爆。
- 减深三招：

```typescript
type Prev = [never, 0, 1, 2, 3, 4, 5, 6, 7, 8];  // ① 查表代替递归计数
type FlattenOnce<T> = T extends readonly unknown[] ? T[number] : T; // ② 具名别名
type Unwrap<T> = T extends Promise<infer V>       // ③ 提前短路
  ? V extends Promise<unknown> ? Unwrap<V> : V : T;
```

- interface 声明是惰性求值、type 别名偏急切：递归的**结构**用 interface/
  交叉，**条件类型**才真正踩上限。
- 事故模式：依赖升级引入 `DeepReadonly` 深递归 → 使用方在无关文件报
  TS2589（实例化深度跨文件累计）—— 递归类型必须配类型测试矩阵（62 节）
  与深度上界文档。

## 7. 内在类型与类型级运算的边界

- `keyof`/`typeof`/indexed access 是仅有的"反射"，边界行为要背：
  - `keyof (A | B)` = 公共键（分配）；`keyof any` = `string|number|symbol`；
    `keyof never` = never；
  - `(A | B)[K]` 对联合分配取值；`T[keyof T]` 是"值域联合"惯用法；
  - `typeof` 只能吃"值"（const/函数/enum/模块），不能吃类型名。
- 类型系统**没有**：相等比较、类型→运行时值、排序、算术（只有元组表）。
  想运行时拿类型的库（DI/ORM）都得 值+类型 双轨声明（115 节配方）。

```typescript
type Values<T> = T[keyof T];
type KeysOfType<T, V> = { [K in keyof T]: T[K] extends V ? K : never }[keyof T];
type Module = typeof import('./config');   // 不引入运行时依赖取模块类型
```

- 陷阱：数百键的 `as const` 大表 + keyof 每次实例化都重算 —— 拆模块或
  预提别名（94 节）。
- `unique symbol` 只能来自 `declare const`/`const x = Symbol()` 且经
  `typeof x` 使用 —— 品牌类型的基础（10 节）。

## 8. const 类型参数

- TS 5.0 的 `<const T>` 让参数**自动获得 as const 级字面量推断** ——
  调用方不再手写 `as const`（那是能被 review 漏掉的运行时不变式）。

```typescript
declare function defineRoutes<const T extends readonly RouteDef[]>(
  routes: T
): { match(path: string): T[number] };
defineRoutes([{ path: '/a', kind: 'static' }, { path: '/b/:id', kind: 'param' }]);
// T: readonly [{ readonly path: '/a'; ... }, ...] —— 路径字面量进类型层
```

- 语义细节（都反直觉）：传入**可变变量**不产生字面量（回退到声明类型）；
  数组推断为 readonly，接收方约束写 `readonly unknown[]`；const 只放在
  **会被推断**的参数上，与显式类型实参组合时不生效。
- 与 `as const` 分工：API 作者用 const 参数减少调用方噪音；调用方字面量
  仍建议 `satisfies` 保契约（23 节）。
- 陷阱：type-check <5.0 的 tsc 报语法错 —— 库声明最低支持版本并写进
  peerDependencies（92 节）；esbuild/swc 单文件转译不校验，只在
  `tsc --noEmit` 暴露。

## 9. 判别联合的设计纪律

- 设计纪律：判别键**单数、字面量、扁平**（`kind`/`type`/`status`），别用
  双键组合判别；payload 平铺在成员里；错误也建模进联合 ——
  `type Result<T> = { ok: true; value: T } | { ok: false; error: AppError }`。
- 多模块合并：各模块 export 成员联合，注册表聚合
  `AllEvent = AEvent | BEvent` —— 穷尽性检查天然覆盖新成员。

```typescript
type Shape =
  | { kind: 'circle'; radius: number }
  | { kind: 'rect'; w: number; h: number };
function area(s: Shape): number {
  switch (s.kind) {
    case 'circle': return Math.PI * s.radius ** 2;
    case 'rect': return s.w * s.h;
    default: { const unhandled: never = s; throw new Error(String(unhandled)); }
  }
}
```

- 判别失效三因：判别键被 widened（27 节）、判别键是对象/数组、成员间
  互为子类型（TS 按可分配性合并，判别失效）。
- 运行时同构：zod `z.discriminatedUnion('kind', [...])`（64 节）——
  schema 错误与类型层判别完全对齐。

## 10. 品牌类型与幽灵标记

- 结构类型里造 nominal 类型靠"幽灵属性"交叉：让 `UserId` 与 `OrderId`
  互不相容，把"传错 ID"从 review 降级到编译期。
- 两代做法：交叉幽灵（`string & { __brand: 'UserId' }`，keyof 泄漏幽灵键）
  与 `unique symbol` + interface 合并（键泄漏消失，现代推荐）。

```typescript
declare const Brand: unique symbol;
type Brand<T, B extends string> = T & { readonly [Brand]: B };
type UserId = Brand<string, 'UserId'>;
type OrderId = Brand<string, 'OrderId'>;
declare function getUser(id: UserId): void;
getUser('o_1' as OrderId);   // ❌ 编译期拦截 —— 品牌存在的意义
```

- 构造纪律：品牌值只经智能构造器产生 —— zod `.brand()`、
  `UserId.of = (s) => s as UserId`（唯一合法断言点，集中审计）。
- 分层：领域层用品牌，DTO 层用原始类型，schema 转换处上品牌（70 节）——
  品牌传染到边界会让 schema 无法直接生成。
- 陷阱：`x satisfies string` 把品牌擦成 string —— 判"是否 string"用
  `x is UserId` 守卫；品牌保护不了 `JSON.parse` 输出（那要 schema）。

## 11. never 的工程用法

- never 是底类型：可赋给一切、extends 一切、联合中吞并无痕。四大用法：
  联合过滤（`Exclude` 本质是成员落 never 再被吞）；不可达标注
  （`function fail(): never`）；穷尽断言（assertNever，24/28 节）；
  映射 `as` 子句回 never 删键（56 节）。
- 检测陷阱（高频）：`T extends never ? 1 : 0` 对 `T = never` 返回 **0** ——
  空联合分配后什么都不执行。正解：

```typescript
type IsNever<T> = [T] extends [never] ? true : false; // 必须 Suppress 分配（17 节）
type NoFalsy<T> = T extends false | 0 | '' | null | undefined ? never : T;
type Clean = NoFalsy<'a' | '' | 0 | true>;   // 'a' | true
```

- `never[]`/`Record<never, X>` 合法但语义微妙：`never[]` 的元素访问是
  never 不是 undefined —— 常是错误的泛型默认值产物，看到就要警觉。
- 事故模式：条件类型某分支意外产出 never，联合静默吞掉成员，调用方
  "少了成员"却零报错 —— 公开库把 never 分支显式具名，让意外 hover 可见。

## 12. 类型谓词与自定义守卫

- `function isFoo(x: unknown): x is Foo` 是**无校验的编译器承诺** —— TS
  不验证谓词逻辑，写错=埋雷；守卫是库中最常见的 unsound 来源。
- TS 5.5 起对简单箭头函数**自动推断类型谓词**：`.filter(x => x !== null)`
  终于把 `(T | null)[]` 收成 `T[]`；复杂守卫仍手写。

```typescript
function isErr<R>(r: Result<R>): r is { ok: false; error: string } {
  return (r as { ok: boolean }).ok === false;
}
const errs = results.filter(isErr);        // 元素级收窄
function hasKey<K extends string>(x: object, k: K): x is Record<K, unknown> {
  return k in x;                           // 与 in 收窄联动（26 节）
}
```

- 守卫工厂必须手写返回类型注解 —— `(tag: string) => (x: unknown):
  x is Tagged<tag>` 推断不出来。
- 纪律：手写守卫必须喂假值测试（62/113 节）；守卫只做结构检查，业务
  断言归断言函数（24 节）。
- 陷阱：`.filter(Boolean)` 不收窄；`.flatMap(x => x ?? [])` 是 5.5 前
  的去 null 惯用法；谓词里 `as any` 后返回 true 等于关灯开车。

## 13. 型变的工程直觉与测量

- 三句话直觉：**产出者协变（out）、消费者逆变（in）、容器不变（in out）**。
  数组协变是**刻意不健全**的历史决定（18 节展开）。
- 拿不准时**测量**，别猜：

```typescript
interface P<T> { get(): T }               // 只在返回位 → 协变
interface C<T> { consume(v: T): void }    // 只在参数位 → 逆变
interface I<T> { both(v: T): T }          // 双向出现 → 不变
declare const pd: P<Dog>;
const pa: P<Animal> = pd;                 // ✅ 成立即协变
```

- 陷阱：型变判断错误时，库使用者**无法正确特化**你的泛型 —— 报错出现在
  调用方而非你的代码，是"库作者转嫁成本"的典型事故。
- 设计顺序：先写使用方代码（怎么特化、怎么读写值），再定参数出现位置，
  最后上 in/out 标注锁死（14 节）。

## 14. in out 型变标注

- 4.7+ 可显式标注：`interface Producer<out T>`、`Handler<in E>`、
  `Box<in out T>`。**官方文档警告：标注必须与真实测得的型变一致，不能
  用来"强制"型变** —— 标错后的 assignability 结果不可预测。
- 正确用法是**锁契约**：公开泛型上标注后，重构悄悄改变参数用途（get 改
  set）编译期立刻炸 —— 防型变漂移。ElysiaJS 组合 `const in out`：
  const 保字面量、in out 锁不变性，用于路由推断管道。

```typescript
interface Emitter<in out E> {            // 不变：注册与派发两侧都依赖 E
  on<K extends keyof E>(k: K, f: (e: E[K]) => void): void;
  emit<K extends keyof E>(k: K, e: E[K]): void;
}
```

- 陷阱：`in`/`out` 是**类型参数修饰**不是协变运算符；`in` 参数出现在返回位
  直接报错（想要的早失败）。
- 方法简写仍双变（18 节），标注救不了方法形态 —— 先改属性形态再标注。
- 顺序：无标注实现 → 探针测量（13 节）→ 上标注 → 类型测试固化（62 节）。

## 15. 泛型参数的设计决断

- 加泛型前过两道门：**类型参数出现在 ≥2 个相关位置**（参数↔返回、键↔值、
  元素↔容器），或**约束到有意义的域**（`T extends DomainEvent`）。
  只出现一次的泛型 = 文档型泛型 = 反模式。

```typescript
function log<T>(msg: T): void { console.log(msg); }   // 反模式：T 只出现一次
function log2(msg: unknown): void { console.log(msg); } // 正解：unknown 更诚实
function first<T>(xs: readonly T[]): T | undefined { return xs[0]; } // 正当泛型
```

- 显式类型参数是调用方成本：每要求写 `<Foo>` 一次，就给 API 可读性记
  一笔债。能推断就不要求显式。
- "未来可能泛型化"不构成理由 —— 先具体，出现第二个真实场景再抽象；
  过早泛型化的库最后都长成 `T extends any` 坟场。
- 泛型默认值 `T = Default` 的合法场景只有一个：可选配置对象的部分推断
  （`Opts<T, R = Json>`）。用默认值掩盖推断失败是病征不是治疗（16 节）。

## 16. 泛型约束与默认值的坑

- 约束 ≠ 窄化：`T extends string` 只保证"调用时是 string"，**函数体内
  T 仍是 T** —— `.toUpperCase()` 不放行：

```typescript
function f<T extends string>(x: T): T {
  return x.toUpperCase() as T;    // ❌ 仍需断言 —— 约束不进体内窄化
}
function g<T extends string>(x: T): string { return x.toUpperCase(); } // ✅
function h<T extends string>(x: T): `${T}!` { return `${x}!`; }        // ✅ 保 T
```

- F-bounded（`<T extends Comparable<T>>`）只在框架级基类用；业务代码里
  它是最常被滥用的"聪明"模式，提示质量骤降。
- 多参数推断：`make<A, B>(a: A[], b: B[])` 各自独立推断（好）；但
  `route<T extends keyof R>(t: T, h: R[T])` 在 T 推断成联合时关联即失效
  （22 节）—— registry 型 API 的通病。
- 默认值与约束组合 `T extends X = Y`：Y 必须满足 X，报错位置离使用处极远；
  库公开签名不堆默认值链 —— 默认值是"调用方少写参数"，不是"推断失败
  的补丁"。

## 17. 抑制条件类型的分配行为

- 元组包裹 `[T] extends [U]` 是唯一的官方抑制姿势。必须抑制的三个场景：
  never 检测（11 节）；**相等性判断**
  （`Equal<A,B> = (<T>() => T extends A ? 1 : 2) extends (<T>() => T extends B ? 1 : 2) ? true : false`
  —— 借函数身份比较绕开联合/any 干扰）；**保持联合整体**（`Box<'a'|'b'>`
  与 `Box<'a'>|Box<'b'>` 运行时可赋值但穷尽性与提示完全不同）。

```typescript
type Dist<T> = T extends string ? Box<T> : never;       // 分配
type NoDist<T> = [T] extends [string] ? Box<T> : never; // 整体
type A1 = Dist<'a' | 'b'>;   // Box<'a'> | Box<'b'>
type A2 = NoDist<'a' | 'b'>; // Box<'a' | 'b'>
```

- 事故模式：把分配版当相等用 —— `EqualNaive`（双向 extends）对 any、
  联合、never 全给错答案；类型测试里一眼现形（62 节）。
- Review checklist 固定一条：**裸类型参数 + extends = 先问要不要分配**。

## 18. 数组协变陷阱与方法双变

- `strictFunctionTypes` 只管**函数类型属性**，**方法简写始终双变** ——
  兼容性豁免，也是最难排查的 unsound 源头。

```typescript
interface A { m(x: string): void }      // 方法简写：双变（宽松）
interface B { m: (x: string) => void }  // 函数属性：strict 下逆变检查
const b: A = { m: (x: string | number) => {} }; // 方法简写下不报错 → 污染路径
```

- 规则：**接口/类型里的回调一律写函数属性** `onChange: (e) => void`，
  不写 `onChange(e)`。库作者写错形态，使用方就被迫吞不兼容实现。
- 数组协变事故链：`const animals: Animal[] = dogs; animals.push(cat);
  dogs[0].bark()` —— 全程绿灯。`noUncheckedIndexedAccess` 拦不住；只有
  "可变容器不协变"的纪律能拦：**要协变就用 `readonly T[]`**
  （协变 + 不可写 = 健全）。
- 实战推论：公开 API 的数组参数默认 `readonly T[]`；内部需要改写才
  `T[]` —— 同时解决型变与"调用方数组被就地修改"两个问题。

## 19. 函数重载的解析与设计

- 重载按**声明顺序取首个匹配**，实现签名对外不可见。规则：顺序从特殊到
  一般；重载之间必须语义相关（TS 不检查，"同名不同义"是猜谜）；联合参数
  能表达的不拆重载 —— 联合提示更干净，也避免重载间推断差异。

```typescript
function pick(obj: readonly string[], keys: readonly number[]): string[];
function pick<T, K extends keyof T>(obj: T, keys: readonly K[]): Pick<T, K>[];
function pick(obj: unknown, keys: readonly unknown[]): unknown[] { /*...*/ }
declare const o: { a: 1; b: 2 };
const r = pick(o, ['a']);   // Pick<{a:1;b:2}, 'a'> —— 该分支保住键字面量
```

- 重载的真正价值：按分支保留不同推断路径（元组/字面量各走各的）。
- 陷阱：mock 库的 `.mockResolvedValue` 选中"最后一档重载"导致类型错乱
  （114 节）；5+ 档重载拖慢编译与补全 —— 换 options 对象或 builder。
- 实现签名必须兼容所有重载，参数写 `unknown` 是合法惯用；取舍细则在
  103 节，先读那节再决定要不要重载。

## 20. 多态 this 类型

- 返回 `this` 让基类方法返回"实际子类型"，链式 builder 不丢具体类型：

```typescript
class Base {
  option(k: string, v: unknown): this { /*...*/ return this; }
}
class Sub extends Base {
  onlyInSub(): this { return this; }
}
new Sub().option('a', 1).onlyInSub();   // 仍是 Sub —— this 类型保住链
```

- F-bounded（`interface Entity<T extends Entity<T>>`）只在框架级基类用；
  业务代码里是最常被滥用的"聪明"模式。
- 陷阱：箭头函数属性没有 this 类型（this 只属于方法）；把 `this` 传给
  独立 helper 后退化为声明类类型 —— helper 参数写 `this: Base` 显式收下；
  this 与映射类型组合时推断易塌成基类，类型测试兜底（62 节）。
- 复杂 builder 的正解不是 this，而是阶段接口
  （`Builder<HasName> → Builder<Ready>`，状态承载在类型参数，61 节同思路）。

## 21. 泛型实例化表达式

- 4.7 的实例化表达式让"特化后的构造器/函数"成为一等类型，不产生运行时：

```typescript
const ErrorMap = Map<string, Error>;   // 类型：new () => Map<string, Error>
const makeErrMap = () => new ErrorMap();
type Handler = typeof fetch;           // 从值反推类型
const JsonFetch = fetch as
  (input: RequestInfo, init?: RequestInit) => Promise<Response>;
```

- 典型场景：适配器/装饰器签名 —— `typeof fetch` 反推 + 特化，避免手写
  整串泛型签名；与 `InstanceType<typeof Class>` 组合是从值反推类型的正道。
- 陷阱：右侧必须是**值引用**（类/函数），不能是类型名；对重载函数使用
  时按"给出显式类型参数"的那一档截取，其余重载不可见 —— 公开 API 注明。
- 纪律：公开导出的实例化表达式显式标类型（isolatedDeclarations 下必须，
  95 节）；与 8 节互补 —— const 参数管调用方字面量，实例化表达式管
  提供方泛型特化。

## 22. 泛型关联参数模式

- 关联记录问题（TS#30581）：多参数共享同一类型参数时，T 一旦推断为联合，
  关联即解除 —— 每个参数独立取联合，运行时错配无告警。

```typescript
type Table = { user: { id: string }; order: { id: number } };
function handle<T extends keyof Table>(k: T, v: Table[T]): void {}
declare const key: 'user' | 'order';
handle(key, { id: 'u1' });    // v 的合法域变并集 —— 错配静默放行
// 正解：关联数据装进同一对象 —— 字面量上下文是关联推断唯一可靠场所
function handle2<T extends keyof Table>(e: { k: T; v: Table[T] }): void {}
handle2({ k: 'user', v: { id: 'x' } });  // ✅ 单点推断，错配立刻报
```

- 规则：**凡相关的值装进同一个对象字面量**。拆散的多参数关联 API 是
  历史 API 常见病（旧 EventEmitter、旧 ORM）。
- 例外：`keyof T` 参数 + `T[K]` 返回的 pluck 型在 T 为具体对象时完全
  可靠 —— 出问题的只有"T 是注册表/联合"场景，先问清调用形态。
- 5.4 的 `NoInfer` 是关联推断的控制阀（83 节）；缓解已有坏 API：包一层
  wrapper，内部 `as` 一次并写测试说明不变式。

## 23. satisfies 深度用法

- `satisfies` = **校验契约 + 保住字面量推断**，替代"注解吃掉字面量"：

```typescript
type Route = 'home' | 'detail';
const routes = {
  home: () => import('./Home'),
  detail: () => import('./Detail'),
} satisfies Record<Route, () => Promise<unknown>>;
// home/detail 键字面量保住：keyof typeof routes → 'home' | 'detail'
// 少写/多写路由都立刻报错 —— 契约不丢
```

- 三个高价值场景：配置对象（缺键/错型立刻红）、事件映射表（键即协议）、
  i18n 资源（与类型常量对齐）。
- 与 `as const` 组合：`as const satisfies X`（const 在前）—— 一个管只读
  字面量、一个管契约，键要派生联合时缺一不可。
- 已知边界：`satisfies` **不窄化变量本身**（社区最大抱怨，SO 高频题）——
  需要窄化用 `typeof x[keyof typeof x]` 技巧或谓词；属性访问
  `routes.home` 才是字面量类型。
- 陷阱：方法简写在 satisfies 下依旧双变（18 节）；getter/setter 能满足
  检查 —— 配置对象别用 getter 藏运行时逻辑。

## 24. 断言函数

- `asserts x is T`（4.0+）：调用后控制流按断言收窄，是替代 `!` 的可审计
  手段：

```typescript
function assertIsDefined<T>(v: T | null | undefined, msg?: string):
  asserts v is T {
  if (v == null) throw new Error(msg ?? 'unexpected null/undefined');
}
function assertNever(x: never): never {
  throw new Error(`unhandled: ${JSON.stringify(x)}`);
}
const el = document.getElementById('app');
assertIsDefined(el, '#app missing');   // 此后 el: HTMLElement，无 ! 污染
```

- 硬限制：**必须显式类型注解**，箭头函数赋值需显式函数类型；**async
  断言函数无法收窄**（await 切断控制流）—— 异步校验用返回守卫或抛错的
  parse（70 节）。
- `asserts this`：方法内断言自身状态（`asserts this is State<'ready'>`），
  状态机里比内部断言优雅（61 节）。
- 与 `!` 分工：`!` 是"此处我认为非空"（一处一次、需注释），断言是"系统
  不变式"（复用 + 报错信息 + 测试）。review 里无注释的 `!` 直接打回。

## 快速索引 · 续（40-79 节）

| 想找什么 | 去哪一节 |
| --- | --- |
| tsbuildinfo 增量缓存 | 40. incremental 与构建缓存 |
| extends 覆盖规则与陷阱 | 41. tsconfig 继承陷阱 |
| tsc 与 esbuild 分工 | 42. 类型检查与转译的双轨制 |
| ESM CJS 互操作 | 43. ESM 与 CJS 互操作深水 |
| exports 子路径类型解析 | 44. package.json exports 与类型解析 |
| import type 运行时行为 | 45. 类型导入的运行时语义 |
| 还在用 namespace 怎么办 | 46. namespace 的遗留处置 |
| 循环依赖类型与运行时 | 47. 循环依赖 |
| 动态导入类型 | 48. 动态导入与 import() 类型 |
| declare module 扩展三方库 | 49. 模块增强 declare module |
| import defer 延迟加载 | 50. import defer 与延迟模块求值 |
| paths 别名发布踩坑 | 51. 路径映射的生产陷阱 |
| barrel 文件性能 | 52. barrel 文件的代价 |
| 类型体操红线 | 53. 类型体操的工程红线 |
| DeepPartial 数组写错 | 54. DeepPartial 与 DeepReadonly 的边界 |
| 过滤键与可选键提取 | 55. 键运算的组合拳 |
| 映射类型 as 子句 | 56. 映射类型的高级形态 |
| curry pipe 参数推导 | 57. 函数参数建模 |
| get obj a.b.c 类型 | 58. 字符串路径推导 get |
| 递归推断编译变慢 | 59. 递归推断的成本控制 |
| 联合转元组 hack | 60. 联合与元组互转 |
| 编译期状态机 | 61. 类型级状态机 |
| expectTypeOf 类型测试 | 62. 类型测试纪律 |
| 类型擦除与运行时鸿沟 | 63. 静态与运行时的鸿沟 |
| zod 实战要点 | 64. Zod 工程用法 |
| TypeBox Ajv 性能 | 65. TypeBox 与 Ajv 高性能校验 |
| OpenAPI 生成类型 | 66. schema-first 工作流 |
| 校验库怎么选 | 67. 校验库选型谱系 |
| zod 与声明产出冲突 | 68. Zod 与 isolatedDeclarations 冲突 |
| transform 管道校验 | 69. transform 管道与解析器组合 |
| IO 边界消毒 | 70. IO 边界消毒纪律 |
| Project References 原理 | 71. Project References 原理 |
| composite 报错排查 | 72. composite 的坑 |
| tsgo TS7 迁移准备 | 73. tsgo 与 TypeScript 7 前瞻 |
| monorepo tsconfig 分层 | 74. monorepo tsconfig 层级设计 |
| 跨包导入纪律 | 75. 包边界纪律 |
| turborepo nx 类型检查编排 | 76. 构建编排与任务缓存 |
| 工作区循环依赖治理 | 77. 循环引用治理 |
| 幽灵依赖 | 78. 幽灵依赖与类型解析 |
| 发布包 types 字段 | 79. 发布产物的类型纪律 |

## 25. 控制流分析的别名边界

- TS 4.4 起**别名判别**：判别键取到 const 别名，对别名分支等于对原对象
  分支 —— "解构 + if"不再丢收窄：

```typescript
type Action = { type: 'add'; n: number } | { type: 'clear' };
function run(a: Action) {
  const { type } = a;          // const 别名：保留关联
  if (type === 'add') a.n;     // ✅ a 被窄化为 add 分支
}
```

- 生效条件：**const 别名 + 原属性不可变上下文**；`let` 别名、可变属性、
  经函数返回的副本都断链。
- 硬边界：收窄**不跨普通函数调用**（除守卫/断言函数，12/24 节）；`await`
  之后对**可变属性**的收窄作废 —— 中间可能被改。
- 纪律：跨 await 的不变式先快照 `const x = obj.x`，让分析对象变成不可变
  局部。
- 事故模式：`if (user.role === 'admin')` 后调 `refresh(user)` 再用
  `user.role` —— 参数对象属性的窄化在调用后即失效，多数人不理解为什么
  突然报错。

## 26. in 操作符与无属性收窄

- TS 4.9 起 `in` 能收窄**没有列出该属性**的类型（"unlisted properties"）：
  `'x' in obj` 把 obj 窄化为 `obj & Record<'x', unknown>` —— 可选键分支
  不再需要手写交叉断言。

```typescript
type A = { kind: 'file'; path: string };
type B = { kind: 'file'; url: string } | { kind: 'dir' };
function pick(f: A | B) {
  if ('url' in f) return f.url;   // ✅ 4.9+：B 分支被选出
  return (f as A).path;
}
type Opts = { retry?: number };
function go(o: Opts) {
  if ('retry' in o) o.retry.toFixed();  // ✅ o.retry: number（不再是 | undefined）
}
```

- 两种语义别混：对**联合**用 in 是在成员间选择；对**单一类型**用 in 是
  把键变"必有"—— 单一对象上 in 永真，写错条件静默通过。
- 与 12 节 `hasKey` 守卫组合可跨函数复用。
- 陷阱：in 收窄对索引签名类型不生效（`Record<string, unknown>` 任何键都
  "存在"）—— 先用判别联合建模，索引签名只做动态字典。

## 27. 判别键的字面量陷阱

- 判别联合失效的头号原因：**判别键被 widened 成 string**。四种保字面量
  的方式：`readonly` + 字面量、显式注解、`as const`、const 类型参数（8 节）。

```typescript
const a = { kind: 'add' } as const;    // 'add' ✅
const b = { kind: 'add' } as { kind: 'add' };  // 显式注解，可变但字面量 ✅
const d = { kind: 'add' };             // ❌ string —— 判别失效
type Ev = { kind: 'add' } | { kind: 'del' };
function emit(e: Ev) {}
emit(d as Ev);   // 静默把 widened 值塞进联合 —— 运行时才暴雷
```

- 函数返回值里的对象字面量按返回类型检查（不 widen），但**中间变量**
  会 —— 大型 reducer 最常见：`const action = { type: X }` 后 dispatch，
  type 已是 string。
- boolean 判别键（`ok: true/false`）不 widen，是轻量判别的廉价选择。
- 自检清单：判别键 hover 应显示字面量；switch 后 default 走 never 才算
  真判别（28 节）。

## 28. 穷尽性检查与 never 兜底

- 两套穷尽机制，场景不同：
  - **switch/if 链**：default 放 `assertNever(x)`（24 节）—— 新增成员时
    错误精确落在漏掉的分支；
  - **映射表**：`satisfies Record<K, Handler>` 强制键全覆盖 —— 新增成员
    报"缺键"，比 switch 更适合注册表。

```typescript
type Ev = { type: 'add'; n: number } | { type: 'del'; id: string };
const handlers = {
  add: (e: Extract<Ev, { type: 'add' }>) => void e.n,
  del: (e: Extract<Ev, { type: 'del' }>) => void e.id,
} satisfies { [K in Ev['type']]: (e: Extract<Ev, { type: K }>) => void };
```

- 回调式穷尽（无 lint 也能跑）：参数表 `onUnhandled?: (x: never) => void`
  —— 想处理"剩余情况"就必须先处理掉每个成员。
- 陷阱：switch 的 default 返回值（不抛错）会**吞掉穷尽性** —— default
  只放 assertNever。
- 部分覆盖是合法需求：显式 `Partial<Record<K, Handler>>` + 运行时缺省
  回退，别为凑穷尽写空函数（假穷尽比不穷尽更危险）。

## 29. 收窄失效清单与修复

| 场景 | 为什么失效 | 修复 |
| --- | --- | --- |
| 可变 var/let 被闭包捕获 | 回调时机可变 | 捕获前快照 `const x = xx` |
| `filter(x => x !== null)`（旧版） | Boolean 无谓词 | 5.5+ 已推断；旧版 `filter(isNonNull)` |
| `obj.x` 检查后过 await | 中间可能被改 | 快照局部（25 节） |
| `find`/`arr[i]` 结果 | `T \| undefined` | 35 节 + 断言（24 节） |
| 谓词内 `x as any` | 守卫撒谎 | 喂假值测试（12 节） |
| 跨普通函数调用 | 分析不跨调用 | 守卫/断言函数（12/24 节） |
| `switch (true)` 模式匹配 | TS 不支持 | 显式 if 链或判别键 |

- 排查法：把表达式拆到临时 const，让分析对象变成不可变值 —— 90% 的
  "为什么没收窄"拆解后原因自现（25 节别名规则）。
- 事故模式：hover 看起来收窄了，重构引入可变字段后静默失效 —— 收窄
  依赖的类型细节没有测试保护，类型测试是唯一保险（62 节）。

## 30. unknown 与 any 的边界纪律

- any 的毒性是**双向**的：与任何类型互相可赋值、参与运算全员静默 —— 一个
  any 污染整条调用链。unknown 只能被 narrow 后使用，是安全的顶类型。
- 现代基线：strict 下 `catch (e)` 已是 unknown
  （`useUnknownInCatchVariables`）；API 签名里的 `any` 一律换 `unknown`
  + 守卫或泛型。

```typescript
function parse(raw: unknown): Config {    // 边界收 unknown
  if (hasKey(raw, 'port') && typeof raw.port === 'number') return { port: raw.port };
  throw new TypeError('bad config');
}
// 强制断言的纪律：unknown 中转 + 注释 + 工单号
const cfg = raw as unknown as Config; // FIXME(#123): 后端字段无文档，验证见 62 节
```

- any 四大来源与堵法：隐式 any 参数（noImplicitAny）；无类型三方库（105
  节手写 .d.ts）；依赖 d.ts 内的 any（提 issue 或 redeclare）；自己的
  `as any`（109 节清单）。
- `as unknown as T` 双跳不是禁忌，是**把单点风险显性化** —— 禁的是无注释
  断言；ESLint `no-unsafe-*` 规则族兜底。
- 陷阱：`JSON.parse` 结果经 `Record<string, any>` 中转会复活 any ——
  显式标 `Record<string, unknown>`。

## 31. widening 行为与常量上下文

- 字面量 widening 规则（决定判别联合存亡，27 节）：`const x = 'a'` →
  `'a'`；`let x = 'a'` → string；对象属性默认 widen；`readonly` 属性 +
  字面量初始化不 widen；`as const` 深度冻结所有层级。

```typescript
const arr = ['a', 1];            // (string | number)[]
const tup = ['a', 1] as const;   // readonly ['a', 1]
const ev = { type: 'x' } as const satisfies Ev;  // 保字面量 + 保契约
function f<const T extends readonly string[]>(xs: T): T { return xs; } // 8 节
```

- **evolving arrays** 是 widening 暗坑：`const x = []; x.push(1)` 让 x
  演化为 number[] —— 显式注解 `const x: number[] = []` 封死演化路径。
- fresh literal 类型只活在直接赋值瞬间（1 节 excess check 依赖它）——
  经变量中转即"陈旧化"，检查消失。
- 纪律：需要字面量的位置（判别键、路由表、事件名）三选一：`as const` /
  `satisfies` / 显式字面量注解；三者全缺席 = 靠运气。

## 32. 可空联合处理范式

- `??` 与 `||` 语义天差地别：`0 ?? 1` 是 0，`0 || 1` 是 1 —— 数字、空串、
  false 是 `||` 三大冤案。基线：空值合并用 `??`，布尔语义才 `||`。
- 语法雷：`a ?? b || c` 直接编译错误（必须括号）—— 设计者救你，别拆。

```typescript
function width(opts?: { w?: number }) {
  const w = opts?.w ?? 0;         // 参数默认值只捕获 undefined，不兜 null
  return w;
}
const len = (s: string | null | undefined): number => s?.length ?? 0;
const call = obj.fn?.();          // 可选链调用：fn 不存在时整体 undefined
```

- 与 exactOptionalPropertyTypes 联动（34 节）：`x?: T` 传 `{ x: undefined }`
  会被拒 —— 先决定"缺省"与"显式空"是不是两个语义（多数业务是）。
- 禁用模式：`x!` 连环（29 节）、`if (x) {}` 对 0/'' 的误杀、`x == null`
  与 `x === undefined` 混用。
- ESLint `no-unnecessary-condition` 是执行器：`??` 右侧永真、对非空值
  判空全部报警 —— typescript-eslint strict 预设必开。

## 33. strict 之上的严格阶梯

- `strict` 是打包开关；**不在 strict 里但该开**的，按性价比排序：

| 开关 | 抓什么 | 迁移成本 |
| --- | --- | --- |
| noUncheckedIndexedAccess | 索引越界/稀疏数组 undefined | 高（35 节） |
| exactOptionalPropertyTypes | `?:` 与 undefined 语义分离 | 中（34 节） |
| noPropertyAccessFromIndexSignature | 拼写错误的动态键访问 | 低 |
| noImplicitOverride | 重写漏 override | 低 |
| noFallthroughCasesInSwitch | switch 贯穿 | 低 |
| noImplicitReturns | 分支漏 return | 低 |

- 落地节奏：新项目 day-1 全开；存量项目先开低成本三件（36 节）；
  每开一项：`tsc --noEmit 2>&1 | wc -l` 记基线 → ratchet 钉住数字 →
  PR 只减不增 → 清零后转 lint 硬禁。
- 不要全开再"慢慢修"：半年清不掉的 flag 会成为永久装饰，还给了绕行者
  借口 —— 一次爬一格，每格清零。
- 巨石库可用 typescript-strict-plugin 目录级渐进；社区最严配置
  （whatislove.dev）可作对照基线。

## 34. exactOptionalPropertyTypes

- 开启后 `x?: T` 表示"可以缺，不能显式 undefined"；`{ x: T | undefined }`
  与 `{ x?: T }` 不再互通 —— 这是"缺省 vs 空值"语义的正确表达，也是
  开启后第一波报错的来源。

```typescript
interface Opts { retries?: number }
const a: Opts = { retries: 3 };         // ✅
const b: Opts = { retries: undefined }; // ❌ 显式 undefined 被拒
function merge(base: Opts, o: Partial<Opts>): Opts {
  return { ...base, ...o };             // ❌ o.retries 可能显式 undefined 覆盖
}
// 修复：条件展开，显式 undefined 不覆盖
const merged: Opts = {
  ...base, ...(o.retries === undefined ? {} : { retries: o.retries }),
};
```

- 高频事故：`Partial<T>` 与"清空语义"混用 —— 用户想"不传=用默认"，
  传 undefined 却覆盖成 undefined；条件展开或 `omitUndefined` 工具是
  标准修复。
- 与三方 API 磨合：旧库签名常写 `x?: T | undefined`（历史遗迹），开启后
  跨界报错 —— 本地 .d.ts patch 或升级依赖。
- React 场景：defaultProps 的"不传 undefined"约定与该 flag 完全同构；
  函数默认参数 `x: T = def` 只捕获 undefined，语义对齐。

## 35. noUncheckedIndexedAccess

- 开启后 `T[i]` 变 `T | undefined`，`Record<K, V>[key]` 同样 —— 越界、
  稀疏数组、字典缺键三大运行时崩溃类**整类消灭**。

```typescript
const xs: number[] = [1, 2, 3];
xs[10].toFixed();                 // ❌ number | undefined
for (const x of xs) x.toFixed();  // ✅ for-of / .map 不受影响
xs.at(-1)?.toFixed(2);            // ✅ .at() 配 ?.
function must<T>(v: T | undefined, msg = 'index miss'): T {  // 热路径不变式
  if (v === undefined) throw new Error(msg);
  return v;
}
must(xs[0]).toFixed();            // ✅ 断言函数收窄（24 节）
```

- 清理三板斧：遍历换 for-of；下标访问配 `?.`/`??`；不变式访问走模块内
  单个 `must()`。**禁止**遍地 `!`（等于关 flag）。
- 深坑：`Map.get` 本来就是 `| undefined`，与本 flag 无关 —— 别把 Map 的
  报错记到 flag 账上；`Array.find` 同理。
- 性能：对 tsc 检查速度影响可忽略；对代码质量影响是所有 flag 里最高的
  —— "Turn it on, see what breaks"（dev.to）的标准结论。

## 36. 被低估的检查项

- 低价高赏的小旗清单（多数团队没开）：

```jsonc
{
  "noImplicitOverride": true,            // 父类新增同名成员 → 子类立刻报错
  "noPropertyAccessFromIndexSignature": true, // Record 动态键须 obj['x'] 显式认领
  "noFallthroughCasesInSwitch": true,    // switch 贯穿（忘 break 经典 bug）
  "noImplicitReturns": true,             // 某分支漏 return 返回 undefined
  "allowUnreachableCode": false,         // 死代码（通常是逻辑错误的尸体）
  "noUnusedLocals": true,                // 未用变量（重构残留）
  "noUnusedParameters": true,            // 未用参数（签名漂移信号）
  "forceConsistentCasingInFileNames": true // 大小写敏感 import（5.0 起默认，显式防降级）
}
```

- `noPropertyAccessFromIndexSignature` 的价值：`obj.strng` 这种对索引签名
  的拼写错误从"运行时 undefined"变编译错误；方括号 `obj['strng']` 是作者
  显式承认动态性。
- `noImplicitReturns` 专抓"if 提前 return，末尾忘了 else return" ——
  返回 undefined 的函数是最阴的 bug。
- 开关顺序：本节全部先行（成本近零），再爬 34/35 两座大山。

## 37. verbatimModuleSyntax 与 isolatedModules

- `verbatimModuleSyntax`（5.0）：import/export **按书写原样保留**，类型
  擦除全靠 `import type` —— 产物与单文件转译器（esbuild/swc/babel）
  永远一致。它取代并废弃了 `importsNotUsedAsValues` 与
  `preserveValueImports`；迁移用 codemod（fix-verbatim-module-syntax）。
- `isolatedModules`：假设每个文件被单独转译 —— 禁止"只有类型层可见"的
  跨文件操作（re-export 类型不带 type、const enum 跨文件内联，110 节）。
  verbatim 满足其类型面，但 const enum 等运行时面仍靠它兜底 —— **两个都开**。

```typescript
import type { Config } from './config';       // 纯类型：彻底擦除
import { create, type Config2 } from './api'; // 混合导入：create 保留运行时
export type { Config2 };                       // re-export 类型必须 type
// 陷阱：instanceof / extends / 枚举值引用必须值导入，import type 直接报错
```

- 与 43 节联动：verbatim 下 CJS 的 `import x = require()` 按模块系统如实
  保留 —— ESM 文件误写会硬报错，好事。
- 新库（尤其过 swc/esbuild 管道的）day-1 开这两个，比事后补便宜一个量级。

## 38. moduleResolution 三种模式

- 按**运行时宿主**选，不是按喜好：

| 模式 | 配对 module | 规则要点 | 适用 |
| --- | --- | --- | --- |
| bundler | esnext | 允许无后缀；不区分 ESM/CJS 输出 | vite/webpack/esbuild |
| nodenext | nodenext | 按扩展名分 .ts/.mts/.cts；严格 ESM/CJS | Node 运行时 / 库 |
| preserve | preserve | bundler 解析 + 保留 import defer 等 | 非 tsc 工具链 |

- node10（classic）已被 5.8+ 弃用警告，TS 6 起走向移除 —— 迁移别再路过。
- 典型报错：nodenext 下无后缀导入 → "Relative import paths need explicit
  file extensions"；bundler 下报 CJS/ESM 混写 —— 宿主矛盾要显式裁决。
- 陷阱：**同一包 bundler 能过、nodenext 爆红**（CJS 里顶层 await、无后缀
  导入）—— 库必须在 nodenext 下测（92 节矩阵），因为你控制不了使用方
  的宿主。
- `--module preserve`（5.4+）是 tsc 的"只检查不转译立场"模式，与 Node
  type stripping（85 节）生态配合。

## 39. target lib module 联动

- target 决定**语法降级**，lib 决定**环境 API 类型**，module 决定模块产物
  形态 —— 三者错位是诡异报错之源。
- 高价值联动点：
  - `useDefineForClassFields`：target ≥ ES2022 时**默认翻转为 true**（define
    语义）—— 类字段从"赋值"变"defineProperty"，子类覆盖 + 声明不初始化
    的组合行为改变；迁移项目必须显式裁决这个 flag；
  - `target: es2022`+ 才有顶层 await、类字段、`error.cause`；
  - lib 与 target 默认联动，但 `dom`/`dom.iterable` 按宿主手选 ——
    Node 项目别带 dom（掩盖 `window` 误用）。

```jsonc
{
  "target": "es2022",
  "lib": ["es2023", "dom", "dom.iterable"],  // lib 可领先 target（有 polyfill）
  "module": "esnext",
  "moduleResolution": "bundler",
  "useDefineForClassFields": false,  // 显式裁决，别吃默认翻转的暗亏
  "esModuleInterop": true,           // 43 节基线常开
  "skipLibCheck": true               // 97 节提速；代价见 112 节案例
}
```

- `emitDecoratorMetadata` 只有 tsc/swc 支持（esbuild 不支持）—— NestJS
  legacy 装饰器栈因此钉死转译器选择（42/111 节）。
- 纪律：三件套永远一起改、一起 review；单改一项是事故起点（91 节矩阵）。

## 40. incremental 与构建缓存

- `incremental: true` 产出 `.tsbuildinfo`：文件图 + 每文件哈希 + 上次检查
  状态 —— 重启只重查变化闭包，大仓冷启动从分钟级到秒级。
- 要点与坑：
  - `composite` 强制 incremental（71 节）；应用项目可单开 incremental；
  - tsbuildinfo 与 include/rootDir 强关联 —— 移动项目、改路径后**先删
    tsbuildinfo**，陈旧图导致的"幻觉错误"排查极费时；
  - 5.x 允许 `noEmit + incremental`（纯检查也吃缓存）；tsBuildInfoFile 进
    .gitignore、进 CI 缓存键（80 节）；
  - 分支切换按内容哈希失效（正常）；**编译器版本变化**全量失效 ——
    升级 PR 首跑必慢，属正常。

```bash
tsc --noEmit --incremental --tsBuildInfoFile .cache/ts.tsbuildinfo
rm -rf .cache/ts.tsbuildinfo   # 遇到解释不了的类型错误先做这个
```

- 事故模式：CI 缓存键没含 tsconfig 哈希 → 改配置不换缓存 → 假绿 CI。
- 原则：本地增量救编辑器与热身，CI 缓存救流水线 —— 两套都要显式管理。

## 41. tsconfig 继承陷阱

- `extends` 是**整块覆盖**：`paths`、`include`、`exclude`、`types` 都不
  合并 —— 子包写一个 `paths` 就把 base 的全量干掉（turborepo #620 经典
  踩坑：monorepo 把 paths 放根配置的原因）。
- 相对路径基准：base 里 `compilerOptions.paths` 相对 **base 文件**；
  `include/files` 相对**声明它的文件**（即 base）—— base 里写 include
  是把 base 目录当根，子包全部失配。
- 铁律：base 只放纯 compilerOptions（不含 paths/include）；子包自写
  include/exclude/paths（74 节）。

```jsonc
// tsconfig.base.json —— 只放编译器选项
{ "compilerOptions": { "strict": true, "noUncheckedIndexedAccess": true } }
// packages/app/tsconfig.json
{
  "extends": ["../../tsconfig.base.json"],  // 5.0 起支持数组，后写者覆盖
  "compilerOptions": { "paths": { "@app/*": ["src/*"] } },
  "include": ["src", "tests"]
}
```

- `extends` 数组是多继承：strict 阶梯 + 框架基座分层组合的正统解法，
  顺序即优先级。
- `references` 不可被继承 —— solution 根 tsconfig 必须亲自列出全部
  子项目（71 节）。

## 42. 类型检查与转译的双轨制

- 现代管线分工：**tsc 只做检查**（`noEmit`），**esbuild/swc/babel 做转译**。
  前提是类型代码可被单文件转译器理解 —— 即 37 节两个 flag 的纪律。
- 双轨分歧清单（每条都有真实事故）：
  - esbuild 不支持 `emitDecoratorMetadata` → NestJS 项目必须 swc 或 tsc；
  - esbuild/swc 不做 `const enum` 跨文件内联（110 节）；
  - 转译器不校验类型 → `tsc --noEmit` 必须独立跑，且在 CI 与构建**并行**
    而非串行（80 节）。
- watch 双跑：bundler watch（产物）+ `tsc --noEmit --watch`（类型），
  各管各的 overlay；从"tsc 同时 emit + watch"迁出的项目普遍快一个量级
  （71/98 节）。
- 纪律：tsconfig 是**类型合同**，bundler 配置是**产物合同** ——
  target/alias/conditions 必须同步评审；配置 drift 是双轨制专属事故类。
- 前瞻：Node type stripping + `erasableSyntaxOnly`（85 节）正把"转译"
  挤出 Node 管线，双轨变单轨，检查仍归 tsc/tsgo。

## 43. ESM 与 CJS 互操作深水

- Node 官方语义：ESM import CJS 时 `default` = `module.exports`；named
  依赖 cjs-module-lexer 静态探测 —— **动态拼出的 named export 探测不到**；
  CJS require ESM 直接报错（只能 `await import()`）；`__esModule` 是历史
  方言，bundler 与 Node 解读不同。
- 主要事故源：**bundler 允许从 CJS 具名导入，Node nodenext 不允许** ——
  开发环境全绿、上 Node 服务器爆炸。库作者必须在 nodenext 下跑矩阵（92 节）。

```typescript
import mod from './legacy.cjs';       // default = module.exports
import { named } from './legacy.cjs'; // bundler ✅ / Node ESM ⚠️ 视探测而定
import legacy from './legacy.cjs';    // 安全姿势：default 解构
const { named2 } = legacy;
```

- tsc 侧：`esModuleInterop: true` 是事实基线；`export =` + `import x =
  require()` 是 CJS 时代声明形态，新代码只出现在 .d.ts（105 节）。
- `"type": "module"` 翻转的连带反应：.js 全体变 ESM、`__dirname`/`require`
  失效、动态 import 语义变化 —— 翻转 PR 必须独立、独立、独立。
- 双格式发布（.d.cts/.d.mts、exports 双条件）在 79/100 节。

## 44. package.json exports 与类型解析

- `exports` 是现代包的解析合同，对 TS 三条铁律：
  - **"types" 条件必须列在最前** —— 条件按序短路，类型不前置时 TS 读到
    运行时入口指向的 .js，报"找不到类型"；
  - 每个 subpath 成对声明类型与运行时入口；
  - 未列出的路径**整体不可导入**（deep import 被封）—— "Package
    subpath './x' is not defined by exports" 就是它。

```jsonc
{
  "name": "@org/ui",
  "exports": {
    ".": {
      "types": "./dist/index.d.ts",   // ← 永远第一
      "import": "./dist/index.mjs",
      "require": "./dist/index.cjs"
    },
    "./tokens": { "types": "./dist/tokens.d.ts", "import": "./dist/tokens.mjs" }
  }
}
```

- 旧消费端退路：顶层 `"types"` 字段 + `typesVersions`（给不认 exports 的
  老 tsc）—— 双保险发布（79 节）。
- 排查工具：`publint`（exports 元数据）、`arethetypeswrong/attw`（按
  解析模式矩阵验类型，100 节 CI 建议）。
- 陷阱：TS 对 exports 的支持依赖 `moduleResolution: node16/bundler` ——
  node10 下 exports 被无视，本地绿 ≠ 消费端绿；self-reference 要求
  exports 就绪。

## 45. 类型导入的运行时语义

- `import type`：**彻底擦除**，产物零痕迹；`import { type X }`：值导入里
  标类型成员。判据：`instanceof`、`extends`、枚举取值、`new` —— 用到
  运行时值就必须值导入，写 import type 直接报错（这个报错救过无数人）。
- verbatimModuleSyntax 下语义最干净：写什么留什么（37 节）。

```typescript
import type { Handler } from './types';  // 擦除：连副作用 import 都没有
import { handler } from './impl';         // 值：保留
import { type Handler2, handler2 } from './api';  // 混合单行
// 陷阱：import type 的类出现在 emitDecoratorMetadata 反射里会消失
```

- `export type * from './x'`（5.0）：显式声明"只再导出类型"—— 库的
  types 入口常用，避免 isolatedModules 报错。
- 陷阱三连：① 循环依赖里把值导入写成 type 导入 = 运行时 undefined
  （47 节反向事故）；② barrel re-export 忘 type → isolatedModules 报错
  （52 节）；③ DI 容器靠 `design:type` 元数据时，import type 的零痕迹
  恰好破坏注入（111 节）。
- 纪律：类默认值导入；纯类型一律 import type —— 给 85 节 type
  stripping 留好路。

## 46. namespace 的遗留处置

- 2025+ 合法残留只有三类：**ambient 声明**（给无类型 JS 全局对象写形状）、
  **UMD 全局**、**声明合并**（函数+命名空间表达重载组，jQuery 时代遗产）。
- 运行时 namespace 是 IIFE + 变量袋：**tree-shaking 杀手**、顺序敏感 ——
  业务代码出现 namespace = 技术债标记。

```typescript
declare global {
  interface Window { __APP_CONFIG__?: AppConfig }
}
export {};  // ← 保命行：让本文件成为模块，否则 declare global 报错
```

- 迁移手法：namespace 内 `export` 成员平移成模块导出；调用点
  `Validation.isEmail` → import；编译器逐点报"namespace 不存在"，
  顺着改即可。
- `declare global` 管全局对象、`declare module` 管模块形状（49 节）——
  用错作用域的报错极含糊，先查作用域再查拼写。
- 判据一句话：**类型层 namespace 可留（收敛到 .d.ts），运行时
  namespace 必拆**。

## 47. 循环依赖

- 类型环 vs 值环：`import type` 擦除后类型环**完全无害**（接口互引是惰性
  的，6 节）；值环按加载顺序产生"半成品对象" —— `undefined is not a
  function`、`Class extends value undefined`。
- 值环事故解剖：bundler 按序提升、Node require 部分缓存 —— **本地 vite
  正常、Node 线上炸**是标准剧本。

```typescript
import { bFn } from './b';    // 值环：运行时取值，bFn 可能 undefined
import type { B } from './b'; // 类型环：安全，TS 惰性解析
export const aFn = () => bFn();
```

- 治理：① 类型依赖全部改 `import type`（45 节）—— 一半环当场消失；
  ② 剩余值环重构：下沉共享模块、事件/注入反转、或函数体内延迟 import；
  ③ 工具门禁：dpdm（识别 import type，比 madge 更适合 TS 仓）进 CI
  （77 节）。
- TS 侧另一类"环"：条件类型互引 → TS2589 或"referenced directly or
  indirectly in its own initializer" —— 用接口或中间别名拆环（6 节）。

## 48. 动态导入与 import() 类型

- `import()` 的类型 = 模块的 typeof（命名空间，含 default）。陷阱：
  **变量路径丢类型** —— `import(path)` 推断为 any。模板字面量可部分
  建模（5 节），超过一层联合就放弃，改显式注册表：

```typescript
const loaders: Record<Route, () => Promise<Component>> = {
  home: () => import('./routes/Home'),
  detail: () => import('./routes/Detail'),
} satisfies Record<Route, () => Promise<Component>>;
const mod = await loaders[route]();  // 保有类型 + chunk 边界显式
```

- vite 的 `import.meta.glob('./routes/*.vue')` 返回 `Record<string,
  unknown>` —— 用泛型参数或 `as const` 收紧，否则所有 loader 都是 unknown。
- CJS 语境下 tsc 按 module 目标把 import() 降级为 require 包装；
  nodenext 保留真 ESM 语义 —— 两种产物对 `.default` 处理不同，升级
  module 时重点回归。
- 纪律：动态导入表 = 注册表模式（35 节 must 断言）；严禁把用户输入拼进
  import 路径（安全 + 类型双输）。

## 49. 模块增强 declare module

- 给三方库"补形状"的正统姿势：**在模块文件里**（有 import/export）写
  `declare module 'xxx'`，按接口合并扩展其导出类型。
- 头号陷阱：写在**脚本文件**（无 import/export）里的 declare module 不是
  增强而是**凭空声明新模块** —— 拼写错的模块名静默产生 any 形状，真库
  类型反被顶掉。规矩：增强文件顶部永远留 `export {}`。

```typescript
// types/express.d.ts
export {};  // 保命行
declare module 'express-serve-static-core' {  // 增强真实解析路径（不是 'express'）
  interface Request { user?: SafeUser }       // 只能增强 interface，type 不行
}
```

- 可增强性规则：**只有 interface 导出可合并**；type alias、类一律不可 ——
  设计库的扩展点时导出 interface 而不是 type alias（101 节）。
- `declare global {}`（46 节）管全局、declare module 管模块 —— 用错作用域
  报"Cannot redeclare"，先查作用域再查拼写。
- 增强只改类型零行为 —— 想改行为要 middleware/patch；两者漂移是隐性
  事故：增强说有 `user`、运行时 middleware 忘挂 → `req.user` undefined。
  契约测试锁两边（113 节）。

## 快速索引 · 续（80-115 节）

| 想找什么 | 去哪一节 |
| --- | --- |
| CI 缓存 tsbuildinfo | 80. tsbuildinfo 与 CI 缓存 |
| TS 5.0 新特性升级 | 81. TS 5.0 升级要点 |
| TS 5.1 到 5.3 变化 | 82. TS 5.1 到 5.3 要点 |
| NoInfer 与 isolatedDeclarations | 83. TS 5.4 与 5.5 要点 |
| TS 5.6 5.7 变化 | 84. TS 5.6 与 5.7 要点 |
| erasableSyntaxOnly | 85. TS 5.8 要点 |
| import defer 与 5.9 | 86. TS 5.9 要点 |
| TS 6 与 7 路线 | 87. TS 6.0 与 7.0 路线 |
| JS 项目渐进转 TS | 88. allowJs checkJs 渐进迁移 |
| JSDoc 类型写法 | 89. JSDoc 类型化 JavaScript |
| 大仓一次性迁移方案 | 90. 大规模迁移 playbook |
| TS 升级回归测试 | 91. 版本升级回归纪律 |
| 库支持哪些 TS 版本 | 92. 库作者的最低版本策略 |
| 编译慢怎么排查 | 93. 编译速度诊断 |
| 慢类型识别治理 | 94. 慢类型治理 |
| 声明并行产出 | 95. isolatedDeclarations 与并行声明产出 |
| d.ts 体积治理 | 96. 声明体积治理 |
| IDE 卡顿治理 | 97. IDE 延迟治理 |
| watch 模式加速 | 98. watch 热路径 |
| 递归类型编译成本 | 99. 递归类型的编译成本 |
| dts rollup 发布 | 100. bundle 时代的类型产出 |
| 导出类型表面积 | 101. 公开类型表面积控制 |
| 泛型嵌套过深 | 102. 实例化深度与泛型 API 设计 |
| 重载联合泛型取舍 | 103. 联合重载泛型的取舍 |
| 回调参数逆变设计 | 104. 回调参数的逆变设计 |
| 手写声明文件 | 105. 手写 .d.ts 纪律 |
| 类型破坏性变更 | 106. 类型版本化与破坏性变更 |
| 泛型命名与文档 | 107. API 类型的文档化 |
| 类型包拆分 | 108. 类型包拆分策略 |
| any 逃逸清单 | 109. any 逃逸路线清单 |
| enum 替代方案 | 110. enum const enum union 的决断 |
| namespace 装饰器遗留 | 111. namespace 与装饰器遗留 |
| 经典事故案例 | 112. 事故模式合集 |
| 测试里的类型纪律 | 113. 测试中的类型纪律 |
| vi.fn mock 类型 | 114. mock 与 spy 的类型安全 |
| API 客户端完整配方 | 115. 配方：类型安全 API 客户端 |

## 50. import defer 与延迟模块求值

- TS 5.9 落地 ECMAScript deferred module evaluation：`import defer * as
  ns from './heavy'` —— 模块**首次访问成员时才求值**，import 语句本身
  零成本。与 `import()` 的区别：defer 拿命名空间视图（属性访问触发
  求值），await import 是显式异步边界。

```typescript
import defer * as heavy from './heavy-init';  // 5.9+，需支持该语法的宿主
export function run() {
  heavy.doWork();   // 此刻才执行 ./heavy-init 顶层副作用
}
```

- 用途优先级：① 启动加速（重依赖推迟到首用）；② 循环依赖缓解（47 节
  取值时机问题的静态解）；③ CLI 冷启动。
- 纪律：defer 的模块**不要有顺序敏感的顶层副作用** —— 执行时机变得
  不确定；它与 `import type` 语义相反（永不求值 vs 按需求值），review
  时分开审。
- 兼容性：需要新版运行时 + module 支持（`preserve`/nodenext，38 节）；
  面向旧环境的库别放进公开 API，发布前 attw 矩阵验证（100 节）。

## 51. 路径映射的生产陷阱

- 铁律：**`paths` 只影响类型解析，不影响一行产物** —— tsc 原样输出
  import 语句。推论：app + bundler 可用（vite alias / tsconfig-paths
  配合）；**发布出去的包**用了自造别名 → 消费者解析不到 = 事故。
  别名必须映射到 package.json 真实依赖或 exports 子路径。

```jsonc
{ "compilerOptions": { "baseUrl": ".", "paths": { "~/*": ["src/*"] } } }
// bundler 层必须镜像（vite.config.ts）：
resolve: { alias: { '~': path.resolve(__dirname, 'src') } }
```

- 配对纪律：tsconfig paths 与 bundler alias **同 PR 同改动** —— 只改
  一边的类型/运行时分歧（42 节）极难排查。测试环境（jest
  moduleNameMapper）是第三处同步点。
- 陷阱：`paths` 相对路径基于 baseUrl；`./*/` 通配映射会隐式兜底吞掉
  拼写错误。
- 替代：monorepo 内用真实包名 + workspace 协议 + Project References
  （71 节）—— 包依赖只留一种事实来源；paths 只留给 app 内部短路径。

## 52. barrel 文件的代价

- barrel（index.ts 全量 re-export）是**编译图炸弹**：
  - `export *` 让 TS 加载整棵子树 —— 类型检查按图全量走（类型层没有
    tree-shaking），大仓 barrel 链能把程序拉到数万文件；
  - 循环依赖磁铁：子模块 → barrel → 子模块（47 节）；
  - bundler 时代运行时靠 tree-shaking 部分挽回，类型层全赔。
- 纪律分层：对外发布的包一个入口 barrel 合理（消费方体验），但**少而薄**
  （只 re-export 公开 API）；monorepo 内部包之间**禁止 barrel、用深路径**
  `@org/ui/button`（配合 exports 子路径，44 节）；测试代码无所谓。

```typescript
import { Button } from '@org/ui';       // ❌ 内部依赖经 barrel：全图展开+循环风险
import { Button } from '@org/ui/button'; // ✅ 深路径：图只含需要的模块
```

- 度量：删 barrel 后 `tsc --extendedDiagnostics` 的 Files/Types 常掉
  30%+（93 节方法论）；内部 barrel 加 lint（no-restricted-imports /
  boundaries）。
- `export type *`（45 节）在必须 barrel 类型时是安全形态。

## 53. 类型体操的工程红线

- 红线清单（每条都有真实翻车记录）：
  - 递归深度 > ~10 的推导进公开 API（6 节：TS2589 无开关）；
  - 成员数几百以上的派生联合（`Paths`/`Merge`）拖垮补全（97 节）；
  - **换不出运行时收益**的体操 —— 类型层算得再妙，运行时校验一行
    不能少（63 节）；
  - 依赖 TS 版本边角行为（推断顺序、联合去重时机）的推导 —— minor
    升级即破坏（91 节）。
- 工程化最低配置：体操封装在**具名别名 + 独立文件**，公开面只露结果
  类型；`expectTypeOf` 固化行为进 CI（62 节）；`--generateTrace` 实测
  成本并记录在文件头（93 节）。

```typescript
/** 一次推导处处复用；实测 tsc 成本 3ms（2025-06 基线，trace 见 CI） */
export type ApiRoutes = ExtractRoutes<typeof routeTable>;
```

- 替代判据：能用"值 + satisfies + typeof"表达的别用条件类型体操 ——
  值层方案对 IDE 友好且可单测（23 节）。
- 好的公开类型让使用者**感知不到体操存在** —— hover 可读、报错可懂；
  做不到就推倒重做。

## 54. DeepPartial 与 DeepReadonly 的边界

- 朴素递归版会写坏数组/函数：`Partial` 把数组方法也"可选化"、`Readonly`
  递归打乱方法 this 语境 —— 正确版必须**按形态分派**：

```typescript
type DeepPartial<T> =
  T extends (...a: infer A) => infer R ? (...a: A) => DeepPartial<R> :
  T extends readonly unknown[] ? { [K in keyof T]?: DeepPartial<T[K]> } :
  T extends object ? { [K in keyof T]?: DeepPartial<T[K]> } : T;
type DeepReadonly<T> =
  T extends (...a: infer A) => infer R ? (...a: A) => DeepReadonly<R> :
  T extends readonly (infer E)[] ? readonly DeepReadonly<E>[] :
  T extends object ? { readonly [K in keyof T]: DeepReadonly<T[K]> } : T;
```

- 分派顺序即语义：函数在数组前（数组也是对象）、元组在对象前 —— 顺序
  错了产物静默变形，类型测试覆盖四种形态（62 节）。
- 深度代价：宽对象 × 深递归是编译热点（99 节）；大对象更新用 Immer 的
  运行时代理，别硬上 DeepPartial。
- `DeepReadonly` 只约束编译期可读性，运行时不冻结 —— 真不可变用
  Object.freeze/immer。
- 反模式：用 DeepPartial<T> 做 PATCH API —— 它连方法签名都可选化；
  正确工具是显式 `Patch<T>` 或 zod partial schema（69 节）。

## 55. 键运算的组合拳

- 键级工具是类型体操里性价比最高的部分，标准积木：

```typescript
type OptionalKeys<T> = { [K in keyof T]-?:
  {} extends Pick<T, K> ? K : never }[keyof T];   // 可选键检测
type RequiredKeys<T> = Exclude<keyof T, OptionalKeys<T>>;
type Values<T> = T[keyof T];                       // 值域
type PickByValue<T, V> = { [K in keyof T as T[K] extends V ? K : never]: T[K] };
type OmitByValue<T, V> = { [K in keyof T as T[K] extends V ? never : K]: T[K] };
```

- `{} extends Pick<T, K>` 原理：可选键的 Pick 允许空对象 —— 这是检测
  "可选"的唯一稳定手法（`-?` 移除修饰符防映射吞 never）。
- 联合键陷阱：`keyof (A | B)` 取**公共键**（分配语义），`keyof (A & B)`
  取并集 —— 做注册表先想清楚要哪个。
- 陷阱：键名联合 >200 时 `Values<T>` 每次实例化重算整张映射，提别名缓存
  （94 节）；symbol/number 键要 `keyof T & string` 过滤才能进模板字面量。
- 应用锚点：精确 Partial/Required 变体（34 节语义分离）、表驱动列选择器、
  编译期路由参数提取（115 节）。

## 56. 映射类型的高级形态

- 三层语法分清：**homomorphic**（`[K in keyof T]`，保留 readonly/可选
  修饰）、**非 homomorphic**（`[K in Keys]`，修饰全丢）、**as 重映射**
  （4.1，改键名）。

```typescript
type Rename<T, M extends Partial<Record<keyof T, string>>> = {
  [K in keyof T as M[K] extends string ? M[K] : K]: T[K]  // as 改名，never 删键
};
type Api = { userId: string; createdAt: Date };
type Wire = Rename<Api, { createdAt: 'created_at' }>;    // snake_case 出口
type Mutable<T> = { -readonly [K in keyof T]: T[K] };    // 修饰符显式开关
type Getters<T> = { [K in keyof T as `get${Capitalize<K & string>}`]: () => T[K] };
```

- `as` 子句回 never = 删键；注意内置 `Omit` 是非 homomorphic（丢修饰符），
  要保修饰用按键过滤的 Pick 组合。
- 键模板化（`get${Capitalize<K>}`）是生成器家族的根基（5 节联动）。
- 陷阱：`keyof (A|B)` 公共键进映射后产物联合塌掉；映射内嵌条件时每键
  一次实例化，键多 = 慢（99 节）。
- 纪律：映射类型是"声明式转换"，改键/删键/换值全部显式 —— 魔法越少，
  六个月后你越能看懂。

## 57. 函数参数建模

- `Parameters<T>`/`ReturnType<T>` 是函数手术刀，深水在**参数增删与管线**：

```typescript
type AppendArgs<F extends (...a: never[]) => unknown, X> =
  F extends (...a: infer A) => infer R ? (...a: [...A, X]) => R : never;
type DropFirst<F> = F extends (x: never, ...r: infer R) => infer Res
  ? (...r: R) => Res : never;
```

- 实战取舍：完整 pipe 类型（fp-ts 级）的 variadic 递归成本高（4 节笛卡尔
  积）—— 业务管线限 8-10 段重载是主流折中；段数上限是工程决定不是
  技术决定。
- 重载函数上的 `Parameters<T>` 只取**最后一档** —— 而最后一档往往最
  宽泛，产物类型最弱；要精确就整体传 `typeof fn`（21 节）。
- 事件回调建模：`(e: E) => void` 参数逆变（18/104 节）—— 用户回调参数
  必须**窄于等于**真实事件，否则消费方代码骗自己。
- 纪律：动态增强函数签名（wrap/decorate）时保留 `this` 参数位 ——
  `Parameters` 会丢 this，反向工具是 `OmitThisParameter`。

## 58. 字符串路径推导 get

- `get(obj, 'a.b[0].c')` 的类型 = 模板字面量 + 递归拆分，限深限宽是硬
  纪律（5/6 节）：

```typescript
type Get<T, P extends string> =
  P extends `${infer K}.${infer Rest}` ? Get<T[K & keyof T], Rest> :
  P extends keyof T ? T[P] : never;
declare function get<T, P extends Paths<T>>(obj: T, path: P): Get<T, P>;
get(store, 'user.addresses[0].city'); // string —— 拼错路径直接编译错
```

- 实现细节决定可用性：`Paths<T>` 与 `Get<T,P>` 必须**同源生成**（同一份
  键图），否则补全提示与运行时查找不一致；宽对象 × 深路径的联合展开是
  平方级（深度 5 × 每层 20 键 ≈ 320 万路径联合，IDE 直接锁死）——
  上深度表（6 节）+ 每层键数上限；可空层级要 `NonNullable` 断链。
- 替代：`as const` 元组路径（5 节）零解析成本、无联合爆炸 —— 新 API
  一律元组路径；字符串版仅兼容已有调用。
- 事故模式：lodash.get 的"路径拼对但值 undefined"消失后，团队才发现
  过去有多少 typo 靠 `?? ''` 藏了十年。

## 59. 递归推断的成本控制

- 编译器对每个**唯一实例化**做记忆化 —— 成本 ≈ 唯一组合数 × 单次展开
  成本。控制术：
  - **砍扇出**：递归每层的联合宽度是乘数 —— 键过滤前置（先 Pick 再递归）；
  - **砍深度**：深度表 + 提前短路（6 节）；
  - **提具名别名**：中间产物具名后同形复用缓存 —— 这是"看起来一样的
    类型一个快一个慢"的真相；
  - **接口化结构**：递归的结构用 interface（惰性），只有"计算"用条件类型。

```typescript
// 慢：匿名内联，每处使用重新实例化整串
type Slow = { [K in keyof Big]: DeepCheck<Big[K]> };
// 快：具名导出，DeepCheck 每个唯一成员只算一次
export type Cached<T> = DeepCheck<T>;
```

- 度量闭环：`--generateTrace` + `@typescript/analyze-trace` 看毫秒分布
  （93 节）—— 没有度量的优化是玄学。
- 经验阈值（起步值，以 trace 为准）：递归深度 ≤6、联合 ≤100 成员、
  键数 ≤300 的映射，普通笔记本不肉。
- tsgo（73 节）把阈值抬高一个量级，但**设计坏账不因编译器变快而变好**
  —— 类型复杂度本身仍是维护成本。

## 60. 联合与元组互转

- TS 没有官方"联合转元组"：联合是集合语义（无序、去重）—— 一切转换
  都是 hack，**不能依赖顺序**。

```typescript
type UnionToIntersection<U> =
  (U extends any ? (k: U) => void : never) extends (k: infer I) => void ? I : never;
// 原理：逆变位置的 infer 交叉（3 节）—— 联合成员塞进参数位变交叉
type A = UnionToIntersection<{ a: 1 } | { b: 2 }>;  // { a: 1 } & { b: 2 }
type Elem = ['x', 'y'][number];                     // 元组→联合：官方且零成本
```

- 可靠子集：元组 → 联合用 `T[number]`；对象 → 联合用 keyof/`Values<T>`
  （7 节）；联合 → 元组的递归 pop 写法存在但**顺序无保证、成员 >10
  深度爆、never 输入翻车**（6 节）。
- 事故模式：把"联合转元组"产物当**有序**结构消费（CSV 列序、路由优先级）
  —— 同版本碰巧稳定，minor 后顺序漂移即炸（91 节矩阵抓得住）。
- 正解：有序需求在**值层**表达（`as const` 数组），类型层用 `typeof`
  反取 —— 值是顺序事实，类型是集合抽象，别让抽象背顺序的锅。

## 61. 类型级状态机

- 类型参数承载状态，非法迁移编译期消失 —— 三个件：状态联合、迁移表
  类型、`asserts this`（24 节）：

```typescript
type S = 'idle' | 'loading' | 'ready' | 'failed';
const TRANS = {
  idle: ['start'], loading: ['ok', 'err'],
  ready: ['reload'], failed: ['retry'],
} as const satisfies Record<S, readonly string[]>;
class Machine<St extends S> {
  constructor(private st: St) {}
  fire(t: TransitionOf<St>): Machine<Next<St>> { /*...*/ return this as never; }
}
// Next/TransitionOf 由 TRANS 表派生：idle 态调用 retry → 编译错误
```

- 设计要点：迁移表是**值 + satisfies**（23 节）—— 运行时真表、类型派生
  自表，单一事实源；复杂网状迁移（>20 边）放弃条件推导改查表
  `Next<St,Ev> = TransitionMap[St][Ev]` —— O(1) 且可读（59 节成本观）。
- 陷阱：`as never` 过渡的实现体必须窄、有测试（113 节）—— 状态机类型层
  一旦撒谎，调用点的"编译期安全"全是幻觉。
- 收益上限：协议/库层收益最高；业务组件层维护成本常超过收益 ——
  先迁协议，再谈页面。

## 62. 类型测试纪律

- 类型是行为，行为要测试。三件套：
  - **vitest typecheck + expectTypeOf**：`toEqualTypeOf<Y>`（严格相等）与
    `toExtendWith`（可赋值）语义必须分清 —— 用错断言全绿假象；
  - **@ts-expect-error 双向纪律**：报错消失时 TS 报 "Unused
    '@ts-expect-error' directive"，自动防陈旧断言；每条 expect-error
    **带注释说明期待的错误码**；
  - **tstyche**：类型测试跨 TS 版本矩阵执行 —— 5.6 过 5.7 红的破坏，
    单版本测试发现不了（91 节）。

```typescript
test('parse 类型契约', () => {
  expectTypeOf(parse).returns.not.toBeAny();      // 第一行：防 any 假绿
  expectTypeOf(parse).returns.toEqualTypeOf<Config>();
});
// @ts-expect-error TS2345 路径不在 Paths 联合中
get(store, 'user.nmae');
```

- CI 形态：`vitest --typecheck` 独立 job 与单测并行；矩阵至少「最低支持
  版 + 最新稳定版」两头（92 节）。
- 反模式：只测"能编译"不测"产物形状" —— 能编译是底线，形状才是契约；
  泛型库公开类型必须测**实例化后**的结果而非别名本身。

## 63. 静态与运行时的鸿沟

- TS 类型运行时**零存在**：无反射无元数据（legacy `design:type` 是特殊
  豁免且仅 tsc/swc，111 节）。一切"类型驱动运行时"的库（DI/ORM/序列化）
  都是双轨架构：装饰器/注册表存运行时事实，类型层从同一事实派生。
- 鸿沟清单（schema 必须先于类型的地方）：HTTP 响应、ENV、localStorage、
  postMessage/websocket、CLI 参数、文件读写、三方回调 payload ——
  **边界 = 不信任面**。
- 两种建模方向：**schema-first**（zod/TypeBox 定义 → `z.infer`/`Static<T>`
  派生，类型永远追着事实跑）与 **type-first**（手写类型+手写 schema，
  双份维护靠 satisfies/类型测试兜，只给无法 schema 化的抽象用）。

```typescript
const EnvSchema = z.object({ PORT: z.coerce.number().default(3000) });
const env: z.infer<typeof EnvSchema> = EnvSchema.parse(process.env);
// 进程内此后只见 Env 类型 —— 不信任面压缩到 parse 一行（70 节）
```

- 事故模式：把 interface 当运行时校验（"我定义了类型所以数据一定对"）
  —— 界外数据从不读你的 interface。
- 审查反问：这段代码拿到的 JSON 若是 `{}`，第一个崩在哪？崩在 parse
  附近 = 好架构；崩在渲染深处 = 鸿沟没堵。

## 64. Zod 工程用法

- 核心红利：schema 是唯一事实源，`z.infer` 反推类型。工程要点：
  - `z.discriminatedUnion` 走快路径（比普通 union 校验快数倍）—— 判别
    联合一律用它（9 节同构）；
  - `.default()` 语义是**输出可选、输入必有** —— `z.input` ≠ `z.output`，
    表单建模必须分清两个方向；
  - `.brand<'UserId'>()` 直通品牌类型（10 节）—— parse 出口即品牌。

```typescript
const UserSchema = z.object({
  id: z.string().brand<'UserId'>(),
  email: z.string().email(),
  role: z.enum(['admin', 'user']),
}).strict();   // strict：多余键报错（1 节结构性缺陷的补丁）
type User = z.infer<typeof UserSchema>;
const res = UserSchema.safeParse(raw);
if (!res.success) report(res.error.issues);   // issues 结构化错误（勿吞）
```

- 大 schema 成本：`z.infer` 面前是大对象递归（99 节慢源之一）—— >50
  字段的对象拆成子 schema 组合。
- zod 4（2025）重写核心：错误 API 统一（`error.issues`）、性能与包体积
  显著改善；生态插件（devtools/resolver）兼容矩阵要核。
- 陷阱：`.refine` 不做类型窄化（运行时检查、类型层不知情）—— 需要
  "类型也变"用 `.transform` + 显式输出（69 节）。

## 65. TypeBox 与 Ajv 高性能校验

- TypeBox 定位：**JSON Schema 原生** —— 类型即 schema 对象，`Static<T>`
  反取类型，与 OpenAPI/文档/代码生成天然同构（66 节）。
- 性能真相（2024-2026 多方基准共识）：TypeBox 本体不校验 —— 配 Ajv
  （编译成 JS 函数）是**最快组合**，高 QPS 服务端默认选择；zod 强在
  DX/生态/错误信息，但 parse+深拷贝路径开销随对象变大增长快；选型
  句式：**边缘热路径 TypeBox+Ajv，应用表单层 zod**，两套类型通过共享
  JSON Schema 或 codegen 对齐，别双写。

```typescript
import { Type, type Static } from '@sinclair/typebox';
import { Value } from '@sinclair/typebox/value';
const UserSchema = Type.Object({
  id: Type.String(), email: Type.String({ format: 'email' }),
});
type User = Static<typeof UserSchema>;     // 类型 ← schema
const ok = Value.Check(UserSchema, raw);   // 便捷校验；高性能走 Ajv（Fastify 内建）
```

- 陷阱：`Type.Optional` 默认在类型层产生 `field?: T | undefined` —— 与
  exactOptionalPropertyTypes（34 节）语义要对齐；`additionalProperties:
  false` + 手动修剪。
- 递归 schema 用 `Type.Recursive`；跨 schema 引用靠 `$id`/registry ——
  命名稳定，产物 diff 才可 review（66 节契约快照）。

## 66. schema-first 工作流

- 适用面远超"表单校验"：**API 契约**（OpenAPI → `openapi-typescript` +
  `openapi-fetch`，路径字面量级精确）、RPC（`ts-proto`）、GraphQL
  （`graphql-codegen`/`gql.tada`）—— 规范文件是事实源，TS 类型全部生成。
- 纪律：
  - **手写 API 类型 = 违规**。规范可由服务端生成（Fastify+TypeBox 自带
    OpenAPI、Nest+zod-to-openapi），但消费端类型必须来自生成器；
  - 生成物进 repo（不进 .gitignore）—— PR diff 即契约变更的 review
    材料，比"CI 里悄悄生成"更可控；
  - CI 快照：规范变化 → 生成物 diff → 计划外 drift 直接红。

```bash
npx openapi-typescript ../api/openapi.json -o src/api/schema.gen.ts
# 配 openapi-fetch：createClient<paths>() —— 路径、参数、响应全自动
```

- 陷阱：生成器版本锁定（生成物 diff 噪音多半来自生成器升级而非契约
  变化）；schema 与 zod 双轨并存的仓用 TypeBox 单轨化（65 节）。
- 收益排序：类型安全 > 文档真实 > 代码量 —— schema-first 一次拿三样，
  type-first 只拿到第一样还是手搓的。

## 67. 校验库选型谱系

| 库 | 强项 | 代价 | 何时选 |
| --- | --- | --- | --- |
| zod 4 | DX/错误信息/生态（表单、devtools） | parse 路径开销、大 schema 编译慢 | 应用层、表单、中小服务 |
| TypeBox+Ajv | JSON Schema 原生、最快 | API 偏底层 | 高 QPS 边缘、OpenAPI 同构 |
| valibot | 树摇友好（bundle ~1/20 zod） | 生态小 | 客户端/边缘函数 |
| effect Schema | 编解码一体、effect 生态 | 引入 effect 心智 | 已用 effect 的团队 |
| arktype | 语法像 TS、性能好 | 生态小 | 新服务端项目 |
- 决策轴：**运行时预算**（每请求 µs 级敏感 → TypeBox/arktype）与 **DX
  预算**（表单/前端 → zod）；编译时成本（zod 大 schema 拖慢 tsc，64/99
  节）是第三条隐藏轴。
- 混合策略（常见）：**一套核心 schema（TypeBox）+ 应用层适配器**，zod
  只出现在表单；类型从 TypeBox 单源派生，杜绝双写漂移。
- 反模式：按"团队熟悉度"选型后在热路径加缓存层掩盖校验开销 —— 选型期
  就该压测（wrk/k6 打 parse 路径），数字说话。
- 公共底线：**schema 必须进边界层（70 节）**，散落业务逻辑的校验是下个
  事故的种子。

## 68. Zod 与 isolatedDeclarations 冲突

- isolatedDeclarations（83/95 节）要求导出声明**自带类型** —— 但 zod
  schema 类型是推断产物（`z.ZodObject<{...}>` 巨型泛型），手写注解痛苦
  且脆。冲突本质：**推断驱动的库 vs 无 checker 的声明产出**。
- 三种落地策略（按推荐序）：

```typescript
// ① schema 留内部模块（不导出 schema 值），只导出类型与 parse 函数
import { UserSchema } from './schema.internal';   // internal 分区不开 isolated
export type User = z.infer<typeof UserSchema>;
export const parseUser = (raw: unknown): User => UserSchema.parse(raw);
// ② 显式注解（能写就写）
export const UserSchema: z.ZodType<User, z.ZodTypeDef, UserInput> = ...;
// ③ schema 库先不启用 isolatedDeclarations，别硬凹
```

- 实践共识：**应用层开 isolatedDeclarations、schema 模块豁免**（分区
  tsconfig，74 节）—— 既拿并行声明产出性能（95 节），又不与 zod 拧着。
- 陷阱：`export const S = z.object(...)` 未注解时，`z.infer<typeof S>` 在
  d.ts 里展开全量泛型 —— 消费端体积暴涨（96 节）且跨版本脆；永远
  `export type` 一个干净的具名类型。
- TypeBox 此处占优：schema 是纯 JSON 字面量，`Static<T>` 在声明产出里
  稳定得多（65/67 节）。

## 69. transform 管道与解析器组合

- 校验的真实形态是**三段管道**：parse（形状）→ transform（归一）→
  refine（业务不变式）。zod 原生支持在 schema 内完成：

```typescript
const SlugSchema = z.string()
  .transform((s) => s.trim().toLowerCase())
  .refine((s) => /^[a-z0-9-]+$/.test(s), 'invalid slug');
type In = z.input<typeof SlugSchema>;   // string
type Out = z.output<typeof SlugSchema>; // string（值已变但型未窄）
const TagSchema = z.string().transform((s): Brand<string, 'Tag'> =>
  s.trim() as Brand<string, 'Tag'>);    // 需要"类型也窄"：显式输出注解（10 节）
```

- 纪律：**transform 幂等**（重试/重放安全）；transform 不抛错 —— 错误走
  refine/issue 通道才结构化；`z.coerce` 只用于标量转换，"原始输入形态怪异"
  用 `z.pipeline`/`preprocess`。
- 错误聚合：默认 fail-fast；表单要"一次收集全部错误"用 `.safeParse()
  .error.issues` 自己聚合（或 effect Schema 的全量报告）。
- 陷阱：跨字段校验（a>b）写在字段级 refine 拿不到兄弟值 —— 用对象级
  `.superRefine`；复杂不变式归领域层，schema 只管形状。

## 70. IO 边界消毒纪律

- "Parse, don't validate"：validate 是**检查后原值放行**（unknown 继续
  流动），parse 是**产出新类型的新值**（unknown 进，品牌 Domain 出）。
  差异决定事故率。
- 边界消毒五律：
  - 每个入口一个 `parseX(raw: unknown): X`，**返回类型与入口同文件**；
  - 品牌类型（10 节）作 parse 出口 —— 未消毒数据类型层无法流入领域层；
  - 入口函数是**唯一**允许 unknown 的层；业务模块 import schema 即 lint
    违规（no-restricted-imports 按路径限制 zod 导入深度）；
  - ENV 启动即 parse（63 节），失败拒绝启动（fail-fast 优于运行中猜）；
  - mock/fixture 必须过同一 schema（113 节）—— 测试数据漂移与生产同罪。

```typescript
export async function getUser(id: UserId): Promise<User> {
  const raw: unknown = await fetchJson(`/users/${id}`);
  return UserSchema.parse(raw);   // 出口即 User，含品牌
}   // 领域层永远 import type { User }，拿不到 unknown
```

- 事故模式：parse 只做形状没做**归一**（日期字符串进来、Date 语义散落
  各处）—— 归一必须发生在 parse 内（69 节 transform）。
- 度量：`unknown` 出现在 src（边界目录除外）的次数 = 鸿沟泄漏数，lint
  计数进看板，趋势只许降。

## 71. Project References 原理

- 本质：把单一大程序切成**多张有向无环的程序图**。每包一份 tsconfig +
  tsbuildinfo，`tsc -b` 按拓扑序构建，下游只读上游产出的 .d.ts ——
  增量边界 = 缓存边界 = 协作边界。
- 三个文件角色：根 solution tsconfig（`files: []` + references 列全，
  41 节）；每包 tsconfig（`composite: true` + 对依赖包的 references）；
  公共 base（只放 compilerOptions）。

```jsonc
// packages/api/tsconfig.json
{
  "extends": "../../tsconfig.base.json",
  "compilerOptions": { "composite": true, "rootDir": "src", "outDir": "dist" },
  "include": ["src"],
  "references": [{ "path": "../shared" }]  // tsc -b 依此拓扑排序
}
```

- 命令面：`tsc -b`（只建过期的）、`--watch`（级联，98 节）、`--clean`、
  `--dry`、`--force`（CI 排查）。
- 适用判据：>3 个包、或 CI 类型检查 >2 分钟、或 tsserver 常驻 >4GB ——
  满足其一就值得上。纯 bundler 应用（无包间 d.ts 消费）用不上，别为
  架构而架构。
- 与 tsgo（73 节）的关系：references 架构在原生并发下提速更显著
  （95 节并行声明产出）。

## 72. composite 的坑

- `composite: true` 强制项与高频报错对照：

| 报错 | 真因 | 修复 |
| --- | --- | --- |
| "Referenced project must have setting composite" | 下游忘开 | 补 composite |
| "File is not listed within the file list" | 引到未 include 的文件 | include 补全 |
| "Cannot write file ... would overwrite" | rootDir/outDir 重叠 | outDir 移出 src 树 |
| "Output file has not been built from source" | 裸 tsc 而非 -b | 用 tsc -b |

- 深坑：**跨边界 import 源码**（`import '../../shared/src/x'`）把上游源拉进
  下游程序 —— 增量图击穿、缓存全失效。门禁：eslint boundaries /
  dependency-cruiser（75 节）。
- `declaration` 是 composite 强制项 —— "没装 dist 就没类型"的本地体验
  问题：`tsc -b` 串进 dev 脚本首步，或 exports 的 types 直接指 src
  （internal 模式，75 节）。
- tsbuildinfo 陷阱：改 rootDir/include 后旧 buildinfo 幻觉报错 ——
  `tsc -b --clean` 一把梭（40 节同理）。
- 分支切换后偶发全量重建属内容哈希正常失效；**编译器版本变化**必然
  全量 —— 升级 PR 首跑变慢是预期（80 节）。

## 73. tsgo 与 TypeScript 7 前瞻

- 时间线事实：2025-03 微软官宣原生移植 **typescript-go（tsgo）** = TS 7
  编译器内核，预览包 `@typescript/native-preview`，宣称构建 ~10x、语言
  服务内存/延迟大幅优化。TS 6.0 是**过渡版**：与 5.9 API 兼容、集中落地
  breaking changes（87 节）。
- 立刻能做的四件准备（收益当下就有）：
  - **清除对 tsc 内部 API 的依赖**（ts-morph/自定义 transformer）—— 迁移
    阻力最大的一块；
  - **开 isolatedDeclarations**（95 节）：tsgo/oxc/tsup 的并行声明产出都
    吃这一口；
  - **references 架构就位**（71 节）：tsgo 以项目为并行单元；
  - CI 加 **tsgo canary job**（allow-failure）：比 6 个月后突击强百倍。
- 风险面：typescript-eslint 自带解析器不受影响；依赖 tsc JS API 的工具链
  要重适配；部分 CLI 行为差异在收敛 —— canary job 就是干这个的。
- 心态纪律：**别为 tsgo 重写架构** —— 坏架构在 10x 编译器下还是坏架构
  （53/94 节），慢类型治理照做。

## 74. monorepo tsconfig 层级设计

- 三层结构与职责（41 节是前置）：

```text
tsconfig.base.json     ← 全仓统一编译器选项（33 节严格阶梯、37 节 verbatim）
tsconfig.json（根）    ← solution style：files:[] + references 列全部包（71 节）
packages/*/tsconfig.json ← extends base；自带 include/paths；声明 references
```

- 铁律清单：
  - base 不含 paths/include —— 子包覆盖陷阱（41 节）；
  - **单一 TypeScript 版本**：根 package.json 统一 devDep，包级不声明
    （版本分裂 = 类型矩阵地狱，92 节）；
  - 测试/生产配置分体：主配置只含 src + `tsconfig.test.json` extends 加
    vitest 类型 —— 测试依赖挡出生产类型图（97 节）；
  - `types: []` + 按包显式 `types: ["node", "vite/client"]`：关闭全局
    @types 自动拾取 —— 幽灵类型事故消失（78 节）。
- 多包共享路径：根级 paths 映射各包 src（开发态），发布用 exports 映射
  真实产物 —— 两套映射职责不同但**成对维护**（51 节）。
- 审计动作：`tsc -b --dry` 输出即架构图，贴进 ADR；改了图不改 ADR 的
  PR 打回。

## 75. 包边界纪律

- monorepo 类型健康度 = 包边界完整度。两条硬规则：**禁止 import 兄弟包
  源码**（`@org/shared/src/util`，击穿 composite 增量图，72 节）；**禁止
  跨包深路径 import**，只允许包入口与 exports 声明的子路径（44 节）。
- 内部包两种合法形态：
  - **built 模式**：workspace 依赖指向 dist + d.ts —— 与真实发布一致，
    但 dev 时先构建（tsc -b 串进 dev 脚本）；
  - **internal 模式**：exports 的 types/import 直接指 src —— 零构建成本；
    代价是未编译形态进依赖图，要求 internal 包零运行时依赖、无复杂语法
    （tokens/utils 层常用）。

```jsonc
// packages/shared/package.json（internal 模式）
{ "exports": { ".": { "types": "./src/index.ts", "import": "./src/index.ts" } } }
```

- 门禁三件套：eslint-plugin-boundaries、dependency-cruiser（违规即 CI
  红）、`tsc -b --dry` diff 审查（74 节）。
- 事故模式：跨包源码 import 在 pnpm workspace 下"恰好能解析"—— 发包
  或 tsgo 迁移时爆炸；规则前置到 lint，别靠爆后排查。

## 76. 构建编排与任务缓存

- TS 任务在 turbo/nx 里的正确形态：
  - **typecheck 与 build 分任务**：app 走 `tsc --noEmit`（输入=src+
    tsconfig，输出=空 + tsbuildinfo）；库走 `tsc -b`（输出 d.ts 供下游）；
  - 任务依赖照 references 声明（`dependsOn: ["^build"]`）—— 编排器拓扑
    与 TS 程序图一致，错位 = 假缓存命中；
  - **tsbuildinfo 声明为输出**并进远程缓存（80 节）—— 不进缓存的
    incremental 在 CI 是纯开销。

```jsonc
// turbo.json 要点
{ "tasks": { "typecheck": {
  "inputs": ["$TURBO_DEFAULT$", "!**/*.test.ts"],
  "outputs": ["**/.cache/tsbuildinfo.json"] } } }
```

- 失败语义：typecheck 必须在下游 d.ts 构建完成后跑，否则 "Output file
  has not been built"（72 节）；本地 `turbo run typecheck --filter=...
  [origin/main]` 只查变更闭包，PR 迭代从分钟到秒。
- 反模式：把 tsc 塞进 vite build（串行化且 vite 不查类型）—— 双轨制
  （42 节）下各跑各的并行任务才是正解。
- 度量：CI 总时长 ÷ 变更包数 是编排质量指标；references 齐全后应稳定
  下降，不降回去查假缓存（80 节）。

## 77. 循环引用治理

- 工作区级循环 = 包间互 import（47 节是文件级）。治理分档：
  - **类型环**：允许（import type 擦除）；但持续增长预示"公共域模型"
    缺失 —— 超过 3 组就提取 types 包（108 节）；
  - **值环**：一律重构出环 —— 下沉共享模块（依赖倒置）、事件总线解耦、
    或动态 import 延迟取值（48 节）。
- 门禁：`dpdm --warning false --tree false --exit-code 1` 或 madge
  `--circular` 进 CI，环列表作为 issue 队列；先**冻结增量**（PR 级禁止
  新增环），再按拓扑债逐步清。
- 事故案例模板：发布日发现 A→B→A，bundler 提升序恰好可用、Node require
  顺序炸（47 节标准剧本）—— 修复成本是门禁成本的百倍量级，别赌。
- barrel 是环的最大放大器（52 节）：治环第一步永远是**拆内部 barrel**，
  多数"神秘环"当场消失。

## 78. 幽灵依赖与类型解析

- 幽灵依赖（phantom dependency）：import 了未声明于 package.json 的包
  —— npm 扁平化让它"恰好可用"，pnpm 隔离让它当场爆炸。**类型层幽灵更
  隐蔽**：`@types/xxx` 被传递依赖带入，d.ts 就悄悄生效。
- 三类事故剧本：本地（npm 扁平）绿 → CI（pnpm 严格）红 "Cannot find
  module"；类型来自幽灵 @types 旧版本 → 消费方（解析到不同版本）红 ——
  "works on my machine" 的 TS 特供版；发布的包依赖幽灵 → 消费者装包即
  缺依赖。
- 治理：
  - pnpm（默认禁止幽灵）或 npm + eslint `import/no-extraneous-dependencies`；
  - `types: []`（74 节）斩断全局 @types 自动拾取，环境类型显式声明；
  - **每包自查**：dependency-cruiser 或 knip 一键扫未声明 + 未使用
    依赖，进 CI。
- 与 skipLibCheck 交互：开着 skipLibCheck 时幽灵 @types 间的版本冲突被
  掩盖（112 节案例）—— 装包后偶发 `TS2304` 先查幽灵。

## 79. 发布产物的类型纪律

- 发布清单（缺一即返工）：
  - `exports` 每条路径 **types 条件第一**（44 节）；
  - 双格式（ESM+CJS）必须配 **.d.mts / .d.cts** —— nodenext 下消费者
    按后缀找类型，单一 .d.ts 会"一半入口有类型一半没有"（attw 专抓）；
  - `files` 白名单收口 dist；`sideEffects: false`（或精确列表）放行
    tree-shaking；
  - `typescript` peerDependencies **不用 caret**：`>=5.4 <5.9` 式区间
    （92 节）。

```jsonc
{
  "exports": { ".": {
    "types": "./dist/index.d.ts",
    "import": { "types": "./dist/index.d.mts", "default": "./dist/index.mjs" },
    "require": { "types": "./dist/index.d.cts", "default": "./dist/index.cjs" }
  } },
  "files": ["dist"], "sideEffects": false
}
```

- 校验管线（发版前置）：`publint`（exports 元数据）+ `attw --pack`（四
  种解析模式矩阵）+ `npm pack` 干净目录 install 再 tsc —— 5 分钟免掉
  90% "消费者装不上"工单。
- 陷阱：`main`/`module` 与 exports 并存时新解析器以 exports 为准、老
  node10 只看前者 —— 双写且保持一致，别删旧字段。
- internal 模式包（75 节）不发布 npm 可豁免大半清单，但团队内消费同样
  要求 exports 严谨（44 节）。

## 80. tsbuildinfo 与 CI 缓存

- tsbuildinfo 是增量的**全部状态**（文件哈希、符号引用、上次检查结果）。
  CI 缓存三原则：
  - **缓存键** = tsc 版本 + 全部 tsconfig + 源文件复合哈希 —— 少一样就是
    假命中（40 节"改配置不换缓存"的 CI 版）；
  - **缓存值** = tsbuildinfo + d.ts 产物一起进（只存 tsbuildinfo 时重建
    d.ts 收益减半）；
  - **恢复路径幂等**：恢复 job 在 tsc -b 前跑。

```yaml
- restore_cache: { keys: ['tsb-v3-{{ hashFiles("**/tsconfig*.json",
    "pnpm-lock.yaml") }}-typescript'] }
- run: pnpm exec tsc -b
- save_cache: { paths: ['**/*.tsbuildinfo', 'packages/*/dist'] }
```

- 陷阱：tsbuildinfo 已相对化路径，但**容器路径必须稳定**（固定
  WORKDIR），否则恢复后全量重建。
- 假绿最险形态：缓存键漏了 tsconfig → 配置改动不触发重查 → CI 绿、本地
  红 —— "本地必现 CI 全绿"第一怀疑对象就是这里。
- 度量：`tsc --extendedDiagnostics` 的 checkTime 记入 CI 趋势 —— 慢类型
  堆积（94 节）在图上一目了然，比事后考古便宜十倍。

## 81. TS 5.0 升级要点

- 5.0 是近年升级面最大的 minor：**const 类型参数**（8 节）与**装饰器
  Stage-3**（111 节，语义大改）；`verbatimModuleSyntax`（37 节）取代两个
  旧值导入开关；`moduleResolution: bundler` 与 `module: preserve`（38 节）；
  `extends` 数组（41 节）；**所有 enum 提升为联合 enum**（110 节推断面
  变化）；`forceConsistentCasingInFileNames` 默认开启。
- 升级动作：锁版本跑全量类型测试（91 节）→ 装饰器二选一显式裁决 →
  codemod 迁 type imports（37 节）→ 检查 enum 相关推断。
- 事故模式：装饰器栈（Nest/TypeORM）升 5.0 后**没有**自动切 Stage-3 ——
  `experimentalDecorators` 不显式设置时相关行为仍会变；显式裁决每个相关
  flag 才算升级完成。
- 纪律：5.0 的改动几乎都要求"显式化"（const、type、resolution）—— 趁势
  把 tsconfig 全文 review 一遍，别只解报错。

## 82. TS 5.1 到 5.3 要点

- 5.1：getter/setter 检查解耦（set 不再被 get 返回类型连带）；无返回值
  函数的 undefined 隐式 return 放宽 —— 老代码"突然不报错"属正常，别误判
  配置丢失。
- 5.2：`using`/`await using` 显式资源管理（Symbol.dispose）—— 连接池、锁
  有了语言级 RAII；装饰器 `Symbol.metadata` 进语言 —— 依赖元数据的 DI
  框架 Stage-3 化前奏（111 节）。
- 5.3：`import type ... with { 'resolution-mode': 'require' }` 双模式类型
  导入（43 节）；bundler 解析开始尊重 package.json `exports`（44 节）；
  switch 判别与联合/交叉归约的性能优化（93 节受益）。

```typescript
function withConn() {
  using conn = pool.acquire();   // 5.2：作用域结束（含抛错路径）自动 dispose
  conn.query('...');
}
```

- 事故模式：5.3 起 bundler 尊重 exports —— 之前"靠目录穿透 import 的包"
  开始报 "not exported"，按 44 节方案修，别降级 moduleResolution 了事。
- 纪律：每个 minor 至少读 release notes 的 "correctness fixes" 段 ——
  5.1-5.3 行为修正多于新语法。

## 83. TS 5.4 与 5.5 要点

- 5.4：**`NoInfer<T>`** —— 阻断某参数位反向喂类型，关联推断从此可控
  （22 节的官方解法）；闭包窄化保留（最后赋值后的不可变闭包捕获不再
  塌回宽类型，29 节清单缩短）。
- 5.5：**推断类型谓词**（12 节）；**`isolatedDeclarations`**（95 节）；
  `while (true)` 后可达性分析；JSDoc `@import`（89 节）；**正则语法
  检查** —— 正则字面量语法错误编译期报出。

```typescript
function on<T>(type: NoInfer<T> & string, handler: (e: T) => void) {}
// NoInfer 阻断 type 反向喂 T：handler 是唯一推断源（22 节终态解）
```

- 事故模式：5.5 谓词推断让一批旧守卫"看似不再需要"，但**复杂守卫推断不出
  谓词** —— 升级后误删手写 `is` 会在运行时炸；谓词推断只覆盖简单比较
  形态，review 确认覆盖面。
- 纪律：5.4+ 是"关联推断可正确表达"的最低版本 —— 库的最低支持版本决策
  （92 节）常以 NoInfer 为分水岭。

## 84. TS 5.6 与 5.7 要点

- 5.6：`--noCheck`（构建/检查分离的官方开关，42 节双轨制官方化）；
  **BuiltinIteratorReturn + strictBuiltinIteratorReturn**（iterator 协议
  收紧，strict 家族新成员）；永远为真/假的 truthy 检查直接报错；
  **失效的 @ts-expect-error 现在报错** —— 陈旧压制指令从噪音变必须清理。
- 5.7：`--target es2024`；`Node20` 解析模式名；`--rewriteRelativeImportExtensions`
  —— .ts 源内相对 import 产物改写 .js，"Node 裸跑 TS"（type stripping）
  时代的基建（85 节）；**未初始化 let 的窄化**（首赋值前视为 undefined）。

```typescript
let port: number;
if (process.env.PORT) port = Number(process.env.PORT);
console.log(port.toFixed());   // ❌ 5.7 起报"赋值前使用"（旧版是 number 撒谎）
```

- 事故模式：5.6 的 truthy 检查报错翻出一批"历史上从未生效的分支"——
  报错清单是**真实逻辑 bug 清单**，别用 expect-error 批量压平。
- 纪律：strictBuiltinIteratorReturn 随 strict 阶梯一起开（33 节）。

## 85. TS 5.8 要点

- **`--erasableSyntaxOnly`**：禁止一切"需要转译才能跑"的 TS 语法 —— enum、
  运行时 namespace、参数属性（`constructor(private x: X)`）、`import =`
  —— 只留可被类型剥离消灭的语法。这是 **Node 原生 type stripping**
  （`node --experimental-strip-types`）的配套纪律：开了它，.ts 就是
  Node 可直跑的。
- 语义变化：never-returning 函数的返回检查粒度化；`--module nodenext`
  与 Node 模块判定同步（require(esm) 对齐）；node10 解析进入弃用警告
  序列（38 节）。
- 性能：5.8 的程序建立与增量更新优化显著（官方 release notes 明示）——
  大仓升级几乎零风险还白拿速度。

```jsonc
{ "erasableSyntaxOnly": true }   // enum→as const 对象（110 节）、
                                 // namespace→模块（46 节）后此开关无痛
```

- 迁移判断：新库/新 Node 服务 day-1 开；存量库按 110/46 节清完再开 ——
  它本质是把"可直跑"变成硬约束的 lint。
- 陷阱：参数属性是最常被忽略的违规项 —— 改成显式字段赋值。

## 86. TS 5.9 要点

- **`import defer`**（50 节详述）：deferred module evaluation 提案落地。
- **Node.js ESM API 稳定化**：tsc 的 ESM 入口（transpileModule 等）标
  stable —— tsup/vite/CLI 封装升级依赖版本不再撞 experimental 警告。
- **`tsc --init` 现代化**：默认配置按 5.x 生态基线走（strict 阶梯、
  module 联动）—— 老项目 diff 一份新 init 输出，是"配置体检"的免费手段。
- 性能：5.9 延续编译速度优化（声明产出与检查路径）。
- 升级注意：defer 进公开 API 前确认消费方运行时支持（50 节）；**5.9 是
  TS 6.0 的 API 基线**（87 节）—— 锁 5.9 过渡期最稳，升 6.0 时以它为
  对照面跑回归（91 节）。
- 纪律：5.8→5.9 破坏面极小，但类型行为修正照旧存在 —— minor 升级纪律
  一次都不能省。

## 87. TS 6.0 与 7.0 路线

- 官方两步走（2025 公布）：
  - **TS 6.0 = 整备版**：与 5.9 API 兼容（官方明示），集中收编 breaking
    changes 与默认值现代化 —— 给生态一年窗口消化；
  - **TS 7.0 = tsgo 原生内核**（73 节）：Go 实现的编译器与语言服务，
    宣称 ~10x 构建速度与大幅降低的服务内存。
- 团队策略三档：应用团队锁 5.9/6.0 精确版本 + canary 跑 tsgo 预览
  （73 节）；库作者 6.0 出来后 90 天内完成兼容声明（peer 区间更新，
  92 节）—— 不依赖默认值的库迁移成本最低；工具链作者把 tsc 内部 API
  依赖列成清单一项项还。
- 陷阱：6.0 的"API 兼容"指**编译器 API**，不承诺零类型行为变化 —— 6.0
  内含破坏性类型检查修正，升级按 91 节全流程走。
- 纪律：5.9→6.0→7.0 当三段式迁移管理：每段独立 PR、独立回滚点、独立
  回归证据 —— 任何"顺手一起升"都是事故配方。

## 88. allowJs checkJs 渐进迁移

- 官方通路：`allowJs`（JS 进程序，不检查）→ `checkJs`（JS 也检查，
  JSDoc 提供类型）→ 逐文件改名 .ts。**改名 = 把 JSDoc 翻译成本机语法**，
  89 节的 JSDoc 是桥不是终点。

```text
第 0 周  tsconfig: allowJs + checkJs + strict 全家（对 TS 文件）
第 1 周  全部 .js 头部加 // @ts-nocheck —— 冷启动零报错
之后    每周按依赖叶子顺序转换：去 @ts-nocheck → 补 JSDoc → 改名 .ts
门禁    CI 统计 @ts-nocheck 文件数，ratchet 只减不增（90 节预算制）
```

- 顺序：**从叶子转起**（工具函数、常量表）—— 被依赖少的文件报错扇出小；
  先转"根"（被全员 import 的模块）会让全库瞬间千错。
- 陷阱：checkJs 只查仓内 JS（不连坐 node_modules）；`maxNodeModuleJsDepth`
  控制深入包内 JS 的层数，默认保守，别乱调。
- 纪律：转名 PR 与逻辑修改严禁混合（diff 不可 review）；转名用 `git mv`
  保历史。

## 89. JSDoc 类型化 JavaScript

- JSDoc 的类型能力被严重低估 —— **足以支撑整个生产库的检查**：
  `@type`/`@template`/`@typedef`/`@callback` 本地类型系统；`@import`
  （5.5+，`/** @import { Point } from './types.js' */` 替代
  `import('./types').Point` 全写）；`@satisfies`/`@overload` 新语法逐步
  有 JSDoc 镜像。

```javascript
/** @import { User } from './domain.js' */
/** @typedef {{ id: string, email: string }} UserRow */
/** @type {ReadonlyArray<UserRow>} */
const rows = load();
/** @type {(rows: UserRow[]) => User} */
const toUser = (rs) => ({ id: rs[0].id, email: rs[0].email });
```

- 永久方案场景：低迭代基础库、被非 TS 消费者使用的包 ——
  `allowJs + checkJs + declaration + emitDeclarationOnly` 可**从 JS 产出
  .d.ts 发布**（105 节），零改名享受类型红利。
- 能力边界（何时必须转 .ts）：无条件类型/映射类型/变长元组 —— 需要时
  写一个 .ts 的 types 模块，JSDoc `@import` 引用 —— 混合形态是合法的
  长期形态。
- 纪律：假 JSDoc（写了不生效的 tag）靠 review 与 `tsc --noErrorTruncation`
  全量输出排查。

## 90. 大规模迁移 playbook

- 千文件级战役清单（渐进日常见 88 节）：
  - **盘点先行**：分模块统计、`@types` 覆盖率检查（缺类型依赖是工期
    最大变数，105 节补位）；
  - **共享类型先行**：先建 types/ 放领域模型，业务转名时有稳定地基；
  - **codemod 起手**：ts-morph 批量做机械转换（JSDoc→注解、样板代码），
    机器做完剩下的才值得人工；
  - **风险分序**：按"变更频率 × 出错成本"排序 —— 热点路径最后动（有
    测试护栏再动）。
- strict 介入时机：转名 60% 前别开 strict 全量（报错噪音淹没真回归）——
  先以 typescript-eslint strict 规则按目录 ratchet，转名完合并成
  tsconfig strict。
- **any 预算制**是存活关键：CI 统计 `: any`/`as any`/@ts-ignore 数量入
  看板，PR 门禁只减不增 —— 没有预算制的迁移必然烂尾成 anyScript。
- 事故模式：冲刺期"顺手优化"引入行为改动 —— 迁移纪律"只翻译不改逻辑"
  由 diff size 门禁强制（单文件 >400 行改动的转名 PR 强制拆分）。

## 91. 版本升级回归纪律

- 事实基线（tstyche 维护者文档明示）：**5.6→5.7 这类 minor 升级也会破坏
  类型** —— 推断、归约、报错行为持续修正。"patch 随便升"对 TS 不成立。
- 升级 SOP：锁精确版本；升级 PR 只含 lockfile + 类型修复，**不与新功能
  混 PR**；回归证据三样 —— 全量 `tsc --noEmit` 错误清单 diff、类型测试
  绿（62 节）、最低/最高版本矩阵绿（92 节）；预读 release notes 的
  "correctness and breaking changes" 段。

```bash
npm i -D typescript@5.9 && pnpm exec tsc --noEmit 2>&1 | tee /tmp/after.txt
diff /tmp/before.txt /tmp/after.txt   # 错误集差异逐条归类：真回归 or 修正暴露
```

- 工具：tstyche 跨版本矩阵跑类型测试（`"typescript": ["<5.6", "latest"]`
  式声明）；expectTypeOf 断言固化关键公开类型形状。
- 事故模式：升级后"错误变少"无人细看 —— 错误消失可能是推断放松（如
  5.1 的 undefined-return 放宽），同样要 review；**错误清单 diff 双向
  都在审**。

## 92. 库作者的最低版本策略

- 三个决策点：
  - **peerDependencies 写区间不写 caret**：`"typescript": ">=5.4 <5.9"`
    —— TS 不是运行时依赖，但类型行为漂移对使用方是真实破坏；caret
    传递了错误的稳定性预期。
  - **最低版本由"用到的语言特性"决定**：NoInfer 最低 5.4、const 参数
    5.0、satisfies 4.9 —— 特性清单写进文档，升最低版本 = minor 升位
    （106 节 semver）。
  - **矩阵测试两头**：CI 跑最低支持版 + 最新稳定版（91 节），中间抽查
    —— tstyche/expectTypeOf 天然支持多版本执行。
- 兼容面细节：
  - .d.ts 里**不写**新版本语法（老 tsc 遇到不认识的语法全文件失效）——
    新语法只在源码层用，或发独立 major；
  - `typesVersions` 只解决老解析器找类型文件（44 节），解决不了新语法
    兼容 —— 别混为一谈。
- 支持窗口建议"最新 + 往回两个 major"，超出即 EOL 公告 —— TS 官方节奏
  近年稳定（5.0-5.9 约两年半，6.0/7.0 接续，87 节）。

## 93. 编译速度诊断

- 诊断三板斧（从轻到重）：

```bash
tsc --diagnostics                # Files/Lines/Identifiers/CheckTime 一屏
tsc --extendedDiagnostics        # 加 types 计数、instantiation 计数
tsc --generateTrace trace.json   # 全事件流 → @typescript/analyze-trace 火焰图
```

- 读数方法：
  - **Types 计数**是复杂度体温计：中型 app 正常 1-5 万，破 10 万必查
    （大概率 schema 库/巨型联合/深递归在展开，64/94/99 节）；
  - CheckTime 热点文件前 10 通常贡献 50%+ 时间 —— 逐个查是否触到条件
    类型/大 mapped type；
  - Program 建图慢 → include 面太宽（barrel、dist 被卷入）。
- 快速体检（成本排序）：skipLibCheck 开了吗；dist 在 include 里吗（产物
  d.ts 被重查）；测试与生产同 tsconfig 吗（97 节）；barrel 链多深
  （52 节）。
- 纪律：**先测量后手术** —— "感觉慢就重构类型"的团队通常把好类型改成
  坏 any；trace 数字进 CI 趋势（80 节），回归可见。

## 94. 慢类型治理

- **慢类型定义**（JSR 生态把"公开 API 缺显式类型"直接定为拒绝项）：
  依赖全程序推断才能定形的导出类型 —— 每个消费点现场重推，tsc/tsserver
  全体买单。
- 慢类型四特征与对治：
  - 导出函数**无返回类型注解** → 公开 API 一律显式返回类型；
  - 从参数/泛型链**推断复杂返回**（`ReturnType<typeof fn>` 套娃）→
    显式接口 + 手写注解；
  - 公开签名里的**巨型条件类型** → 内部化，公开面只留计算结果别名；
  - **as const 万行配置表**参与推导 → keyof 联合预提（7 节）并拆模块。

```typescript
// 慢：每个调用点都要解一个泛型谜题
export function makeThing<T extends Config>(cfg: T) { return {} as Complex<T>; }
// 快：公开面平实，复杂度藏在内部
export interface Thing { id: string }
export function makeThing(cfg: Config): Thing { ... }
```

- 与 isolatedDeclarations 同构：能过它的库天然无慢类型（95 节）—— 把
  "显式注解"当 lint 而不是建议。
- 例外：泛型库的类型体操是其存在意义 —— 用类型测试 + trace 实测锁住
  成本（62/93 节），不是一刀切禁掉。

## 95. isolatedDeclarations 与并行声明产出

- 5.5 的 `isolatedDeclarations`：**单文件足以产出 .d.ts**（不依赖程序级
  推断），否则报错。它不改变 tsc 自身的 emit（官方明示），改变的是
  "谁能产出声明"：oxc/tsup/vite-tsc/tsgo 都能**并行**生成 d.ts —— 构建
  管线串行瓶颈被拆除（42 节双轨制的最后一块拼图）。
- 开启代价 = 公开导出必须显式类型。典型报错与修法：导出 const 无注解
  （zod schema 尤甚，68 节）→ 显式 `z.ZodType<...>` 或分区豁免；导出的
  计算属性名/展开推导 → 改显式形状；内部推断类型泄漏进公开签名 →
  显式接口（94 节）。
- 收益分层：库 —— d.ts 并行产出 + 慢类型纪律 + tsgo 平滑路径（73 节）；
  应用不发布 d.ts，可不开。

```jsonc
// 库的分区豁免（74 节分区 tsconfig）
{ "compilerOptions": { "isolatedDeclarations": true } }
// packages/lib/src/schema/ → 独立 tsconfig 关掉它（68 节 zod 豁免）
```

- 纪律：新库 day-1 开；存量库按导出清单逐文件达标，别"先开再
  expect-error 遍地"—— 压制指令批量出现即该分区豁免。

## 96. 声明体积治理

- .d.ts 是发布物，且**所有下游的 tsc 都要完整解析它**。膨胀源与治理：
  - 未显式注解的导出把**内部推断链**写进 d.ts（`declare const x:
    ZodObject<{...300 行...}>`）—— 显式类型截断（94 节）；
  - barrel 转发让 d.ts 树全量挂出（52 节）—— 发布面瘦身；
  - `@internal` 成员 → `stripInternal: true` 从 d.ts 剥离；
  - 私有形状泄漏（公开签名引用未导出类型 → TS 内联展开）—— 显式导出
    具名类型，通常同时暴露设计问题（101 节）。
- 审查机制：**api-extractor 产出 rollup d.ts + .api.md 报告** —— 公开
  API 面成为 PR diff 材料（106 节抓破坏性变更）；或 dts rollup（tsup
  dts / rollup-plugin-dts，100 节）拿体积收益。
- 体积红线（经验值）：单入口 d.ts > 300KB 时下游 tsserver 开始可感知
  变慢（97 节）—— 超线先查膨胀源，别安慰自己"skipLibCheck 会救"。
- 陷阱：stripInternal 剥掉的类型若仍出现在公开签名里，TS 写出追不出
  口的 `import("...").Foo` —— 被 strip 的类型必须从公开签名退场。

## 97. IDE 延迟治理

- tsserver 卡顿 = **项目大小 × 类型复杂度 × 双重 watch**。治理按序：
  - **拆配置**：生产 tsconfig 只含 src；测试独立 `tsconfig.test.json`；
    根 solution 管编排 —— 编辑器常驻程序瘦一半（vitest 类型检查独立
    进程，62 节）；
  - **排除产物**：`exclude: ["dist", ".cache"]` —— 被卷进 include 的
    产物 d.ts 是隐形双倍程序（93 节）；
  - **types 白名单**：`types: ["node"]` 而不是全量拾取（74/78 节）；
  - **skipLibCheck: true**：d.ts 检查是编辑器预热大头 —— 开；代价是
    依赖坏类型被掩盖（112 节案例，发布前用一次性 noEmit 全查补课）；
  - **composite 切程序**：大仓 references 后编辑器按引用加载，跨包跳转
    也快（71 节）。
- 内存特征：tsserver > 4GB 常驻、每次保存全文件重查 → 八成是 93 节诊断
  的"巨型联合/深递归"在编辑器侧重演。
- 工具位：typescript-eslint hybrid mode + projectService 与 tsserver
  共享程序 —— 别让 ESLint 自建第二个 TS 程序（大仓编辑器 CPU 第一杀手）。
- 纪律：编辑器延迟是**工程问题**不是玄学 —— tsserver 日志（verbose +
  trace）定位，别靠重启续命。

## 98. watch 热路径

- 三种 watch 各司其职：`tsc -b --watch`（references 级联增量，71 节 ——
  monorepo 唯一正确的 tsc watch 形态）；bundler watch（产物，不查类型）；
  `vitest --typecheck --watch`（类型测试独立进程，62 节）。
- 大仓开关：`--assumeChangesOnlyAffectDirectDependencies` —— 默认每次
  变更重查整个传递闭包，此开关把影响面压到直接依赖 —— 上万文件时保存
  回包从秒级回毫秒级；代价是**通过类型层间接关联的错误延迟暴露**
  （如 satisfies 表的键联动，23 节），CI 全量兜底。
- vite 侧配套：`vite-plugin-checker` 把 tsc 挂进 dev server 并把类型
  错误打回 overlay —— bundler 不查类型的空白（42 节）在编辑时补上。
- 事故模式：本地 watch 全绿 + CI 红 —— watch 的增量视界与 CI 全量语义
  不同；**watch 是体验优化，CI 全量是唯一真相**（80 节）。
- 纪律：watch 命令进 package.json 固化（`dev:types`），别让每人手搓
  不同 flags —— 排查"我这不卡"的沟通成本远大于写脚本。

## 99. 递归类型的编译成本

- 与 6/59 节分工：6 节是深度极限（语义），59 节是设计手法；本节是
  **成本解剖与测量**。
- 成本模型：总成本 ≈ **唯一实例化数 × 单次展开成本 × 缓存未命中率**。
  三个因子各有杀器：唯一实例化数 —— 联合宽度进递归 = 笛卡尔积（4 节），
  先过滤再递归；单次展开 —— 映射内嵌条件 = 每键一次实例化（56 节），
  条件提出映射外、结果缓存成具名别名；缓存 —— interface/别名具名化让
  同形实例命中（59 节），匿名内联每处使用都展开。

```bash
tsc --generateTrace /tmp/trace.json && npx @typescript/analyze-trace /tmp/trace.json
# 盯 instantiateType/createType 顶部项：递归工具出现在前 20 名即候选治理对象
```

- 经验读数：健康业务仓的递归类型开销 < 总检查时间 5%。
- 5.5-5.9 官方持续优化实例化缓存与联合归约（84/86 节）—— 但这是
  **斜率改善**不是豁免：设计层面的指数项（联合×深度）任何编译器都救不了。
- 事故模式：递归工具类型进公共 types 包后**所有下游仓**开始变慢 ——
  成本向全体消费方收税；公共包里的递归类型 = 最高审查等级（53 节红线）。

## 100. bundle 时代的类型产出

- 运行时归 bundler，声明产出三条路线：
  - **tsc 分文件产出**（composite 强制，71 节）：结构与源对齐，深导入
    友好；文件多、体积碎；
  - **dts rollup**（rollup-plugin-dts / tsup dts）：合成单/少量 d.ts ——
    下游解析快、API 面一目了然；代价是深导入路径消失（除非按子路径
    分别 rollup，44 节）；
  - **api-extractor**：rollup + `.api.md` API 报告 —— 库的标准姿势
    （96 节审查 + 106 节变更检测一鱼两吃）。
- 双格式细节：ESM/CJS 双发布配 `.d.mts/.d.cts` 对（79 节）—— dts
  rollup 对双入口要分别处理。

```bash
npx publint                     # exports/类型元数据合法性
npx attw --pack .               # 四种解析模式的类型矩阵（44 节）
npm pack && (cd /tmp && npm i ../pkg-*.tgz && npx tsc --noEmit)  # 真装真查
```

- sourcemap：d.ts 也要（rollup-plugin-dts 支持）—— 下游"跳定义"落进
  .d.ts 而非源码时，文档体验崩塌。
- 陷阱：rollup d.ts 遇 `export =`、default 混合、augmentation（49 节）都
  可能静默丢形状 —— **rollup 后的 d.ts 必须重新过类型测试**（62 节
  矩阵跑在产物上而不是源码上）。

## 101. 公开类型表面积控制

- 公开类型 = API 合同。三原则：
  - **显式导出你想导出的**：非导出类型泄漏进公开签名时 TS 在 d.ts 里
    内联展开（96 节）—— 它是设计缺陷探测器：泄漏说明抽象没切干净；
  - **类型层只暴露构造器允许的形状**：智能构造器（10 节）配
    `private constructor` —— "怎么造"是实现细节，"长什么样"才是合同；
  - **接口优先于 type alias**：interface 可被消费方增强（49 节）、报错
    友好、惰性求值省编译 —— 对外一律 interface，alias 收敛内部。
- 命名纪律：导出类型带领域前缀（`RouteConfig` 不是 `Config`）—— 消费方
  `import type { Config }` 撞名地狱是你造成的；泛型参数命名见 107 节。

```typescript
export interface HttpClient {            // 合同：形状
  request<T>(req: TypedRequest<T>): Promise<TypedResponse<T>>;
}
class HttpClientImpl implements HttpClient { /* 内部不导出 */ }
export const createHttpClient = (): HttpClient => new HttpClientImpl();
```

- 删除导出成本极高（106 节）—— 宁可早期少导出（内部先 export，观察
  真实复用再提升到公共入口），不要"先全导出再说"。
- 审查机制：api-extractor 的 .api.md 是公开面的冻结快照，新导出/删除/
  参数窄化全部强制显式讨论（96/106 节）。

## 102. 实例化深度与泛型 API 设计

- 你的 API 让调用方写出 `Builder<Result<Parser<Lexer<Token>>>>` 之日，
  就是他们的 TS2589 之时 —— **实例化深度是调用方账单**（6 节是本方
  账单）。
- 设计防线：
  - **限链长的阶段接口**：builder 不用 this 无限链（20 节），改
    `Builder<HasName>` → `Builder<Ready>` 显式阶段 —— 链长上限天然成立；
  - **重载截断递归推断**：可变深度入口（compose/pipe）给 2-10 元组档位
    的显式重载（19/57 节）—— 超档位退化最宽签名，而不是堆栈爆炸；
  - **文档化最大嵌套**：泛型容器声明"嵌套 ≥N 层未测"，N 是类型测试
    （62 节）实际验证过的深度。

```typescript
type Result<T, E = Error> = { ok: true; value: T } | { ok: false; error: E };
type DeepOk<T, D extends number = 3> = Result<DeepOk<T, Prev[D]>>; // 深度表锁死
```

- 事故模式：monad 风格 `Result<Result<...>>` 在 3-4 层后开始报
  "excessively deep" —— 引导调用方扁平化，并把写法写进 API 文档。
- 性能联动：深嵌套泛型同时也是慢类型（94 节）—— 公开面的泛型复杂度
  既是正确性风险也是全体使用方的编译成本。

## 103. 联合重载泛型的取舍

| 需求特征 | 用什么 | 理由 |
| --- | --- | --- |
| 参数类型独立、返回固定 | 联合参数 | 提示干净、零重载维护成本 |
| 各分支推断路径不同（元组/字面量） | 重载 | 分支独立实例化（19 节） |
| 输出类型由输入**窄化**决定 | 泛型 | 联合/重载表达不了关联 |
| 输出关联 + 多参数 | 泛型 + 对象参数 | 22 节关联推断唯一可靠形态 |

- 高频反模式：**泛型参数 + 联合实参** —— `f<T extends 'a'|'b'>(x: T)`
  传联合变量时 T = 联合整体，下游按成员分支的意图全毁；正解是重载或
  对象参数收窄。
- 字面量自动补全：联合参数直接给补全；泛型版的补全依赖显式类型实参，
  体验差 —— 默认联合。
- 判据最后一问：**"我需要在返回类型里引用这个输入吗？"** —— 不需要
  就别用泛型；需要再看关联是单参（泛型）还是多参（泛型+对象）。
- 混合时序：先写联合版本上线，从真实调用形态再决定是否升级重载/泛型 ——
  过度建模与欠建模同样昂贵，现场数据最便宜。

## 104. 回调参数的逆变设计

- 回调是消费位：`(e: Event) => void` 的 Event 处于逆变位（13 节）——
  **回调参数类型必须窄于等于真实派发**，写宽 = 调用方被骗，写 any =
  下游全体失明。
- 三条设计律：
  - 事件表用映射 + 联合判别：`on<K extends keyof Events>(k: K, cb:
    (e: Events[K]) => void)` —— 每键回调各自精确（9 节同构）；
  - 回调一律**函数属性形态**（18 节）：`cb?: (e) => void` 而非方法简写
    —— strictFunctionTypes 的逆变检查才真正生效；
  - 域类型出口加品牌（10 节）：回调拿到的 `UserId` 不可与裸 string 混用
    —— 事件消费端最常见事故就是拿错 ID 串。

```typescript
interface Events {
  'user:updated': { id: UserId; patch: Partial<User> };
  'order:created': { id: OrderId };
}
interface Bus {
  on<K extends keyof Events>(k: K, h: (e: Events[K]) => void): void;  // 逆变位精确
  emit<K extends keyof Events>(k: K, e: Events[K]): void;              // 协变位精确
}
```

- 陷阱：`once` 返回 `Promise<Events[K]>` 时泛型联动多一层，类型测试必须
  覆盖（62 节）；异步回调内 throw 是运行时纪律，靠 bus 的 try/catch 兜底
  而不是类型。

## 105. 手写 .d.ts 纪律

- 触发场景：无类型三方库、CDN 全局脚本、内部 JS SDK。纪律：
  - **结构最小化**：只声明你用到的 API，别猜全量（猜出来的 any 和错误
    签名是持久债）；
  - CJS 库用 `export =`（43 节）、ESM 库具名导出、UMD 用
    `export as namespace`；
  - 入参一律 unknown/精确联合、返回显式 —— 手写 d.ts 里的 any 会经
    declare module **永久合法化**（109 节）。

```typescript
// types/legacy-sdk.d.ts
declare module 'legacy-analytics' {
  export interface TrackProps { [k: string]: string | number | boolean }
  export function track(event: string, props?: TrackProps): void;
  export function init(key: string, opts?: { debug?: boolean }): void;
}
```

- 检查闭环：手写 d.ts 必须在自己的 tsconfig（strictest + noImplicitAny）
  下编译过 —— ambient 声明默认逃逸检查，单独建 types 检查项目是唯一
  防线。
- 升级路径：给上游提 DefinitelyTyped PR —— 手写 .d.ts 是手术刀不是
  长期假肢；上游有了官方类型就**删本地版**（两套并存时谁真谁假全靠缘分）。
- 通配声明（`declare module '*.svg'`）收进 src/types 资产模块 —— 散落
  各处的 declare module 无人认领。

## 106. 类型版本化与破坏性变更

- 类型的 semver 比运行时更严：**加东西也可能 breaking** —— 新增必选成员
  显然 major；新增可选成员会炸 `satisfies Record<Keys>` 消费方、精确
  keyof 遍历（23/55 节）—— 视作 minor 但要跑生态检测；参数窄化 =
  major；返回宽化理论兼容，但**下游推断链**（ReturnType 传播）拿到的
  类型变了 —— 一样当变更审。
- 检测机制：api-extractor 的 .api.md diff 进 CI —— 类型面变化零豁免
  review（96 节）；major 候选变化跑 attw + 下游冒烟仓矩阵（100 节）。
- 温和下线三步走：`@deprecated` JSDoc（5.0+ 编辑器划线）→ 引入新名 +
  类型别名过渡 → 下个 major 删除。**别名过渡期是救消费方 CI 的关键步**，
  跳过它直接删是生态暴力。

```typescript
/** @deprecated 3.2 起，请用 createClient（4.0 移除） */
export const createHttpClient: typeof createClient;   // 别名过渡
export function createClient(opts: ClientOptions): HttpClient;
```

- 纪律：类型变更写进 CHANGELOG 独立小节（"Type changes"）—— 使用方
  升级时第一眼找的就是它（91 节流程的消费端）。

## 107. API 类型的文档化

- 类型是第一文档，写作纪律让 hover 即懂：
  - **泛型命名表**：`T` 单值、`K` 键、`V` 值、`E` 事件/错误、`S` 状态、
    `R` 返回 —— 偏离表的名字必须语义化（`TEvent extends keyof Events`）；
  - 每个公开导出一个短 JSDoc：一句"是什么/何时用"；`@example` 给
    **非平凡**调用（builder 链、判别收窄）—— IDE 会展开它，是性价比
    最高的文档；
  - 判别联合配注释：成员名自述 payload（`{ kind: 'conflict' }`）比
    JSDoc 更硬。

```typescript
/**
 * 安全取值：路径拼错编译期报错（58 节）。
 * @example
 * const city = get(store, 'user.addresses[0].city');
 */
export function get<T, P extends Paths<T>>(obj: T, path: P): Get<T, P>;
```

- 反模式：签名里的"聪明类型"让 hover 展开十层泛型 —— 公开签名用**平实
  具名类型**（94/101 节），聪明留内部。
- `@deprecated` 带替代指路（106 节）；重载的每档 JSDoc 分开写，IDE 按
  选中档展示（19 节）。
- 错误消息设计：5.x 的 "type error label" hack（never 报自定义文案）可读
  性差且随版本不稳 —— 引导文案用 JSDoc + lint，别让下游看撒谎的类型
  猜意图。

## 108. 类型包拆分策略

- 什么时候值得拆 `@org/x-types`：**契约跨服务共享**（BFF 与客户端、
  多团队协议 —— 类型包是合同库，版本化即协议演进，106 节）；
  **运行时包巨大但多数消费方只要类型** —— `import type` 零成本（45 节）
  依赖类型包，不拖实现。
- 形态与陷阱：
  - **纯类型包**（无运行时）：exports 只有 types 或只发 .d.ts —— 消费方
    必须 `import type`（lint 强制）；`"sideEffects": false` + 无 main 是
    合法形态，attw 能验证（100 节）；
  - **版本锁死**：类型包与实现包同版本发布、peer 精确引用 —— 类型 1.2
    配实现 1.3 是漂移事故模板（79 节区间纪律）；
  - **环免疫**：类型包不得 import 任何实现包（77 节治理分界线）。

```text
packages/
  contracts/   ← @org/contracts：schema（TypeBox）+ 派生类型（63/65 节）
  api-server/  ← 依赖 contracts；实现不外泄
  web-app/     ← 依赖 contracts（import type）+ 按需依赖实现包
```

- schema 归属：契约包里放 schema（63-67 节），两侧从同源派生 —— 拆
  类型包不拆 schema 等于把鸿沟（63 节）搬进合同。
- 反模式：为"整洁"过早拆包 —— 双仓 PR + 双发版的变更是单仓 3 倍成本，
  没有跨团队协议需求就别拆。

## 109. any 逃逸路线清单

| 逃逸姿势 | 合法场景 | 替代 |
| --- | --- | --- |
| `as any` | 无（迁移期临时） | `as unknown as T` + 注释 + 工单（30 节） |
| `@ts-ignore` | 无 | `@ts-expect-error TSxxxx` + 描述 |
| `!` 非空断言 | 局部不变式 + 注释 | 断言函数（24 节） |
| `// @ts-nocheck` | 迁移债务标记（88 节） | ratchet 清零 |
| 无注解参数（隐式 any） | 无 | noImplicitAny 开启 |
| 手写 declare module 含 any | 三方无类型（105 节） | unknown/精确联合 |
| 泛型默认 `T = any` | 无 | unknown + 收窄 |
| 依赖 d.ts 里的 any（skipLibCheck 掩护） | 上游问题 | 提 issue / redeclare |
| `Record<string, any>` | 无 | `Record<string, unknown>` |

- ESLint 执行面（typescript-eslint strict 预设）：`no-explicit-any`、
  `ban-ts-comment`（require-description）、`no-unsafe-*` 四件套、
  `no-non-null-assertion`（allow-with-comment 可谈）—— 规则的意义是把
  "少数断言"变得**可统计**（90 节 any 预算制的采集端）。
- 纪律核心：any 不可清零，但必须**可数、有主、有到期日** —— 无主 any
  比有 any 更危险，因为它不在任何人的还款计划里。

## 110. enum const enum union 的决断

| 形态 | 运行时 | 风险 | 判据 |
| --- | --- | --- | --- |
| 联合 + as const 对象 | 一个对象字面量 | 几乎无 | **默认** |
| 字符串联合（裸） | 零 | 无穷尽方法时弱 | 纯参数域 |
| enum | 对象 + 反向映射 | 反向键污染序列化 | 需要键值互查 |
| const enum | 内联（理想） | isolatedModules 下退化 | **新代码禁用** |

- const enum 的原罪：内联依赖跨文件信息，**单文件转译器（esbuild/swc/
  babel）做不到** —— isolatedModules 下退化为普通 enum（对不齐是最温和
  结局，部分链路直接报错）。
- 标准姿势（背下来）：

```typescript
export const LogKind = { Info: 'info', Warn: 'warn', Error: 'error' } as const;
export type LogKind = (typeof LogKind)[keyof typeof LogKind];  // 'info'|'warn'|'error'
const COLOR = {
  info: 'gray', warn: 'amber', error: 'red',
} as const satisfies Record<LogKind, string>;   // 穷尽表双保险（28 节）
```

- enum 尚存的两席：需要 `E[k]` 反查 + `Object.values(E)` 迭代的适配层
  （转译器已锁定 tsc 的项目）；与既有一方 API 签名对齐。
- 陷阱：string enum 与裸字符串**不互通**（enum 名义化）—— 边界混用会
  生出类型断言坟场；边界用裸联合（70 节 schema 也只认联合/字面量）。

## 111. namespace 与装饰器遗留

- 两代装饰器的分裂现状：
  - **legacy**（`experimentalDecorators`）：NestJS/TypeORM 生态，依赖
    `emitDecoratorMetadata`（仅 tsc/swc 支持，esbuild 明确不做，42 节）
    —— 钉死转译器选择；
  - **Stage-3**（5.0+）：语言标准，无 metadata 反射（5.2 的
    Symbol.metadata 只是槽位）—— 依赖参数类型反射的 DI 迁不过去。
- 共存纪律：同一文件**不可混用两代**；装饰器 flag 与 `useDefineForClassFields`
  成对裁决（39 节）—— legacy 栈常见组合 `experimentalDecorators: true` +
  `useDefineForClassFields: false`，Stage-3 栈反之。
- namespace 侧：运行时 namespace 必拆（46 节）；`erasableSyntaxOnly`
  （85 节）把 enum/namespace 一并列为非法 —— 清完后只剩 `declare
  global`/augmentation 这些纯类型层残留，属合法。
- 迁移决策树：依赖 emitDecoratorMetadata？→ 是：锁 tsc/swc + legacy
  flag，规划框架升级；否：直接 Stage-3 + 关实验 flag，享受 Node 直跑
  生态（85 节）。
- 事故模式：`useDefineForClassFields` 默认翻转撞上 legacy 装饰器初始化
  顺序 —— 症状是"DI 的依赖全是 undefined"，根因在 tsconfig 两行之外。

## 112. 事故模式合集

| 症状 | 根因 | 防线 |
| --- | --- | --- |
| 线上 `undefined.toFixed` | 索引/稀疏数组越界 | 35 节 + must() 模式 |
| PATCH 把字段清成 undefined | `?:` 与 undefined 混同 | 34 节条件展开 |
| switch 收窄失灵、never 报错消失 | 判别键 widened 成 string | 27 节 as const 纪律 |
| 守卫返回 true 但运行时塌方 | 谓词撒谎无测试 | 12 节喂假值测试 |
| 升级依赖后 d.ts 错误被掩盖 | skipLibCheck 遮住上游破坏 | 96 节定期 noEmit 全查 |
| 发包后消费者 import 解析不到 | tsconfig paths 进了产物 | 51 节 alias 只留 app |
| 首屏 `undefined is not a function` | barrel 循环 + TDZ | 52/47 节深路径 |
| rebase 后幻影类型错误 | tsbuildinfo 陈旧 | 40/72 节 clean |
| instanceof 意外 false | 双格式包双实例（ESM+CJS） | 79 节单实例策略 |
| mock 后类型全变宽/any | vi.fn 未标泛型、重载取尾档 | 114 节类型化 mock |

- 复盘纪律：每次事故回填一行 —— **本表是活文档**，新人 onboarding 第一课
  就是读它；防线列必须引用具体节，防止"多加测试"式空话防线。
- 统计规律：TS 事故的 80% 落在"类型承诺与运行时事实脱钩"（63 节鸿沟）
  与"配置漂移"（39/41 节）两大族 —— 前者靠 schema 边界，后者靠 tsconfig
  进 review 与 CI 诊断输出（93 节）。

## 113. 测试中的类型纪律

- 测试代码的类型质量 = 生产类型质量的下限（宽松的测试放行宽松的生产
  代码）。纪律：
  - **fixture 工厂优于 Partial<T> 模拟**：`Partial<User>` 绕过必填不变式，
    测试全绿但现实里造不出这种对象 —— 工厂 + overrides 让非法状态在
    测试里也造不出；
  - fixture 过 schema：测试数据经 parse 出厂（70 节），与生产同一条消毒
    路径 —— schema 变更时测试数据错误自动暴露；
  - 关键公开契约配 expectTypeOf 断言（62 节），与运行时断言同文件 ——
    类型的"行为"和运行时行为一样需要回归保护。

```typescript
function makeUser(over: Partial<User> = {}): User {
  return UserSchema.parse({       // 过 schema 的出厂
    id: 'u_test', email: 't@x.dev', role: 'user', ...over,
  });
}
const admin = makeUser({ role: 'admin' });   // 合法 overrides 才能过
```

- 反模式清单：测试里 `as any`（用类型化 wrapper 而不是弃检）、
  `@ts-nocheck` 整文件压制、快照里躺着 any 的后代（类型断言没跑）。
- CI 形态：`vitest --typecheck`（类型测试随单测执行）+ 主 typecheck 任务
  **含 tests 目录** —— 测试代码逃出类型检查是慢性中毒。

## 114. mock 与 spy 的类型安全

- mock 是**有类型合同的重定向**，不是黑箱：
  - `vi.fn` 必须给函数类型：vitest 1.x+ 用 `vi.fn<(a: A) => B>()` 形态，
    产出 `Mock<(a: A) => B>`；
  - `vi.mock('./mod', factory)` 的返回值必须完整：用
    `satisfies typeof import('./mod')` 约束 factory —— mock 与真实模块
    漂移当场报错。

```typescript
vi.mock('./api', () => ({
  getUser: vi.fn<(id: UserId) => Promise<User>>()
    .mockResolvedValue(makeUser()),
})) satisfies { getUser: typeof import('./api').getUser };
```

- 老坑速查：
  - **重载函数的 mockReturnValue 命中最后一档**（19 节）—— 想精确控制
    某档：`as` 到单档函数后 mock，断言集中一处；
  - `mocked(obj)` 深度 mock 对 readonly/泛型方法返回 any 化结果 —— 只
    mock 需要的那几个方法，别整对象深度 mock；
  - `spyOn` 依赖原型方法真实存在 —— 被 spy 的方法不是 optional（否则
    spy 目标是 undefined）。
- 纪律：mock 类型宽于真实模块 = 测试通过 + 生产爆炸 —— `satisfies
  typeof import` 与"partial mock 用显式工厂"是两道断路器；mock 数据同
  样过 schema/工厂（113 节）—— 类型对 + 数据对，两条腿都要。

## 115. 配方：类型安全 API 客户端

- 全套纪律的落地形态：路由表单源 → 路径/参数类型派生 → schema 校验 →
  品牌 ID → Result 联合 → 穷尽错误处理。

```typescript
// ① 路由表：唯一事实源（值 + satisfies，23 节）
const ROUTES = {
  'GET /users/:id': { out: UserSchema },
  'POST /users': { in: CreateUserSchema, out: UserSchema },
} as const satisfies Record<string, { in?: SSchema; out: SSchema }>;
type Route = keyof typeof ROUTES;
// ② 路径解析：模板字面量拆参数（5/58 节，限深 2）
type PathOf<R extends Route> = R extends `${string} /:${infer P}` ? P : never;
// ③ 入口：const 参数保字面量（8 节）+ 品牌出口（10 节）+ 消毒（70 节）
async function api<const R extends Route>(
  route: R, init: RouteInit<R> = {} as RouteInit<R>,
): Promise<ApiResult<z.output<(typeof ROUTES)[R]['out']>>> {
  const raw: unknown = await doFetch(buildPath(route, init.params), init.body);
  const parsed = ROUTES[route].out.safeParse(raw);
  return parsed.success
    ? { ok: true, data: parsed.data }
    : { ok: false, error: toAppError(parsed.error.issues) };  // Result 联合（9 节）
}
// ④ 消费：穷尽分支（28 节），错配路由名直接编译错
const res = await api('GET /users/:id', { params: { id: userId } });
if (!res.ok) return handleErr(res.error);      // AppError 判别联合
```

- 逐层出处：路由表 satisfies（23）、模板路径（5）、const 参数（8）、
  schema 出厂（64/70）、品牌参数（104）、Result 判别（9）、穷尽消费
  （28/112）、mock 契约测试（113/114）—— 一个文件串起全套纪律，是新人
  理解"为什么每条规矩都存在"的最佳教材。
- 变体：TypeBox 版把 ① 换成 schema 即 JSON Schema（65 节，OpenAPI 同构）；
  server 版把 ① 作为导出、前后端共享契约包（108 节）。
- 维护要求：每层都有类型测试锚点（62 节）—— 配方不是一次性代码，是被
  CI 保护的产品。
