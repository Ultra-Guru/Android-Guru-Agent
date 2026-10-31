<!-- kb: id=frontend | role=builtin_coding_frontend | title=前端专家知识库 | version=1 -->
# 前端专家知识库

## 必看内容

- **语义是合规底线**：先选对元素（button/a/nav/main），div+onclick
  模拟按钮在键盘/读屏/焦点上全塌（7 节深水）。语义错误的修复成本
  随交互复杂度指数上涨。
- **渲染管线定价**：每个样式决策先问触发哪一级——layout > paint >
  composite。动画只碰 transform/opacity；改动范围用 contain 与
  content-visibility 圈住，别让一个 class 改动重算整页（1/28 节）。
- **INP 纪律（FID 已死，2024-03 起换榜）**：交互 p75 <200ms；
  输入回调 >50ms 必须让出（scheduler.yield() 切片）；先量
  LoAF 再动手优化（43 节指标口径）。
- **容器优先响应式**：组件适配交给 @container + cqi 单位（27 节），
  页面断点只管壳层 —— 组件可移植性的分水岭。
- **状态驱动 + 失效预算**：类名/attr 表达状态，JS 改类名、CSS 管呈现；
  批量 DOM 写入后统一读几何，禁 layout thrashing（98 节）。
- **产物必过双查**：静态 HtmlAnalyzeLayout（骨架/响应式/a11y）+
  需要真实渲染时 BrowserAnalyzeLayout（动态几何）—— 溢出/重叠
  这类问题只有动态查得出。

## 快速索引

| 想找什么 | 去哪一节 |
| --- | --- |
| 解析器与样式失效内幕 | 1. 解析与失效内幕 |
| 元素语义深水陷阱 | 2. 语义深水陷阱 |
| 脚本调度与预加载扫描器 | 3. 加载调度内幕 |
| 地标/标题大纲进阶 | 4. 地标与大纲进阶 |
| 表单 API 深水 | 5. 表单深水 |
| 图片与媒体管线 | 6. 媒体与图片管线 |
| 链接/按钮深水 | 7. 链接与按钮深水 |
| 表格列表可达性深水 | 8. 表格与列表深水 |
| 内联语义与排版细节 | 9. 内联语义深水 |
| 隐藏/可见性语义矩阵 | 10. 可见性与隐藏 |
| 选择器匹配引擎内幕 | 11. 选择器引擎内幕 |
| 动态伪类与交互成本 | 12. 动态伪类 |
| 伪元素与无障碍 | 13. 伪元素深水 |
| 特异性工程 | 14. 特异性工程 |
| 继承关键字决策表 | 15. 继承深水 |
| 盒树与逻辑属性 | 16. 盒模型深水 |
| margin 折叠与 BFC | 17. margin 合并 |
| Flex 深水陷阱 | 18. Flex 深水 |
| Grid/subgrid/masonry | 19. Grid 深水 |
| 包含块与锚定定位 | 20. 定位深水 |

## 1. 解析与失效内幕

**解析器不是"读源码建树"这么简单**——元素表人人会背，这节给内幕：

- **两套扫描并行**：主解析器逐 token 建树；预加载扫描器
  （preload scanner）在源码流里前瞻抓 img/script/css 提前下载。
  推论：JS 动态插入的资源（document.write、运行时拼接 src）
  预加载器看不见 —— LCP 图片交给 HTML 声明，别交给运行时注入。
- **解析器会"改写"你的源码**：`<p><div>` 自动闭合 p；表格里
  杂散文本被 foster parenting 移出表格；嵌套交互元素
  （a 里套 button）被强制拆树 —— 检视 DOM 树而不是源码
  （devtools Elements 才是真身，静态分析器同理由）。
- **CSS 阻塞脚本执行**：`<script src>` 前有未就绪的样式表时，
  脚本等 CSSOM 才跑（防未样式闪烁的同步约束）。
  css → js → DOM 解析互相咬合，这是"JS 放头部也慢"的真因。
- **样式失效按集合算**：引擎给每个 class/attr 预计算失效集
  （invalidation sets）。`.active` 改动只重算相关子树；但选择器
  越"泛"（`.a .b .c` 长链、广域 `:has()`），失效集越大，
  改一次 class 重算半页。
- **过度泛化选择器是慢性毒**：Edge 109+/Chrome 的 Performance
  面板有 Selector Stats，列出每次 Recalculate Style 里
  最贵的选择器（匹配次数 × 耗时）。重构方向：用 `contain`
  /`content-visibility` 把失效范围圈进组件（28 节）。
- **DOM 写读交替 = layout thrashing**：`el.style.width=…;
  el.offsetWidth;` 每轮强制同步布局。纪律：批量写、集中读，
  或快照缓存几何值。读写分离是 98 节排查的第一刀。

## 2. 语义深水陷阱

LLM 都知道 strong/em 是什么；难的是这些"看起来对但机器读错"的坑：

- **label 双重关联**：`<label>` 同时 `for` 又包裹 input —— 语义
  重复不叠加；两个 label 争一个 input 会翻车。一控件一标签。
- **标题层级即大纲**：h1-h6 是唯一的大纲机制（HTML5 的 document
  outline 算法从未实现、已从规范删除）。跳级（h2→h4）读屏照报
  深度，但页面大纲断了 —— 修大纲改层级，别加 role。
- **address 不是通讯地址**：语义是"联系人/作者信息"；页脚塞
  公司地址是经典误用（SEO/读屏都不认账）。
- **figure/figcaption 位置约束**：figcaption 必须是 figure 的
  **首个或末个**子元素，夹中间会被移出图注语义。
- **ol 的法律语义**：编号属于内容本身（start/reversed 支持
  "第 3 条起"类条款）。ol 播报密度高（"第 x 项共 y 项"），
  顺序无关的长列表用 ul 减噪。
- **a 无 href ≠ 链接**：无 href 的 a 只是样式钩子（不可聚焦/
  无链接语义）；禁用态保留 Tab 序用 `<a aria-disabled="true">`。
- **hidden 与组件库 CSS 打架**：UA 的 `[hidden]{display:none}`
  特异性低，`.panel{display:flex}` 能顶回去 —— 规范级防御写法
  `[hidden]:not([hidden=until-found]){display:none!important}`。
- **alt 决策树**：信息图 alt 传达信息本身；纯装饰 alt=""
  （别给 title）；文字截图把文字写进 alt。判定题：去掉图后
  语境损失什么，alt 就补什么。
- **交互元素嵌套被拆树**：button 套 a、label 套 button 都会被
  解析器拆成兄弟（1 节）—— DOM 已变，样式再对也白搭。

## 3. 加载调度内幕

defer/async 的表谁都会背；调度内幕与 2024-2026 的正确姿势：

```html
<script type="module" src="app.js"></script>    <!-- module 默认 defer -->
<link rel="modulepreload" href="/chunks/x.mjs">   <!-- ESM 子图提前握手 -->
<img src="hero.avif" fetchpriority="high">        <!-- LCP 主资源提权 -->
<script type="speculationrules">…</script>         <!-- 预取/预渲染声明 -->
```

- **modulepreload ≠ preload**：前者提前**解析并实例化**模块图
  （含依赖），preload 只下载字节。碎片化 ESM 包不配
  modulepreload = 自造瀑布。
- **fetchpriority 三档**（high/auto/low）：与 loading=lazy 组合
  语义——LCP 图 `fetchpriority=high`；首屏以下 `lazy+low`。
  声明式永远比引擎猜快。
- **speculation rules（Chromium 生态可用）**：内联 JSON 声明
  prefetch/prerender 与触发条件（eagerness: conservative/moderate/
  /eager，或 document rules 的 href_matches/where）。对"登录后
  首跳"这类高置信路径 prerender ≈ 0ms 导航；其他引擎当渐进增强。
- **CSS 双阶段阻塞**：样式表既阻塞渲染也阻塞脚本执行（1 节）。
  关键 CSS 内联 + 其余异步（media="print" onload 切换）是老但稳
  的招；预加载扫描器看得见 head 里的样式表，看不见 JS 注入的。
- **103 Early Hints**：服务端先回 103（Link: rel=preload 头）再
  慢慢算正文 —— 浏览器在等首字节时就开始拉资源，SSR 慢页面
  白捡几百毫秒。
- **纪律**：head 串行样式表 = 渲染串行；HTTP/2+ 连接数限制消失
  但单流队头阻塞依旧要求首屏资源少而精（44 节加载策略对账）。

## 4. 地标与大纲进阶

地标表人人会查；实战里的裁剪与坑：

- **main 全页唯一**；nav/aside/footer 可多个但**必须可区分**：
  每个 nav 配 aria-label（"主导航"/"面包屑"），读屏地标列表里
  同名地标等于没标。
- **别用 ARIA 复述原生语义**：`<main role="main">` 冗余，
  `<nav role="navigation">` 同理。ARIA 第一律：原生可用就
  不补 role —— 补错比不补更糟（role 是覆盖不是叠加）。
- **视觉重排 ≠ 阅读重排**：flex/grid 的 order、direction 只改
  渲染序，读屏与键盘仍按 DOM 序。"右上角重要"就挪 DOM。
- **display:none 从无障碍树摘除**；`content-visibility:hidden`
  保留渲染状态（恢复免重排）—— 频繁开关的大段内容切换它
  比 display 快一个量级（10 节矩阵）。
- **skip link 先于一切**：`<a href="#main">跳到主内容</a>`
  放 body 首元素，聚焦才显形。地标跳转是第二层保险，不能替代。
- **阅读模式/正文抽取器（Readability 系）认的是**：article
  包裹 + 层级完整的 h* + 段落密度。整页 div 的"设计稿神还原"
  在阅读模式里直接裸奔。
- **header/footer 是区块作用域的**：嵌在 section/article 里
  就是该区块的头脚，不产生页面级地标 —— 想全局唯一就别嵌套。

## 5. 表单深水

表单元素表跳过；这节是 API 行为差与移动端实战：

- **submit() 跳过校验**：`form.submit()` 直接发请求（不触发
  constraint validation、不冒泡 submit 事件）；
  `form.requestSubmit()` 才是"用户点按钮"等价物（走校验+
  事件+submitter）。自动保存草稿别误用 submit()。
- **submitter 语义**：按钮的 name/value 只有**被点的那颗**进
  FormData —— 保存/发布多按钮靠它分流；回车提交没有按钮值，
  服务端别依赖它。
- **校验三件套**：checkValidity()（不显错）/reportValidity()
  （弹原生 UI）/setCustomValidity()（自定义规则，空串清除）。
  `:user-invalid` 只在交互过后才红，比 :invalid 少折磨人。
- **form 属性跨 DOM 关联**：input 在表单外用 `form="id"` 挂接
  （弹层里的控件也能进表单）；`formdata` 事件在提交前注入/
  改写字段（token、计算值）。
- **Enter 的隐式提交**：单行输入表单里 Enter 触发首个 submit
  按钮；没有 submit 按钮的表单部分浏览器直接提交。防误提：
  给非提交按钮显式 type="button"。
- **移动键盘三开关**：type 决定校验与语义，inputmode 决定键盘
  （numeric/decimal/search），autocomplete 决定自动填充 token
  （email/one-time-code/shipping postal-code…）。一次性验证码
  `autocomplete="one-time-code"` 在 iOS 直接弹建议填充。
- **checkbox 未勾不上报**：FormData 里没有未勾项 —— 服务端
  "缺字段=否"要写成显式约定（或配 hidden 哨兵）。
- **自定义控件进原生表单**：formAssociated: true +
  ElementInternals（setFormValue/setValidity）让 Web Component
  拥有原生校验与 FormData 上报 —— 别再用 hidden input 同步。

## 6. 媒体与图片管线

- **srcset 是"给预算"不是"下命令"**：浏览器按 DPR×显示尺寸算
  候选分；sizes 写错（与真实布局不符）会系统性选错档。
  sizes 必须与 CSS 实际宽度对账（BrowserAnalyzeLayout 量）。
- **宽高属性是 CLS 保证金**：width/height（或 aspect-ratio）
  在图片加载前占位；响应式图配 width/height 属性 +
  height:auto 纵横比公式照样成立。
- **LCP 图铁律**：首屏大图必须由 HTML 声明（预加载扫描器看得见）
  + fetchpriority="high" + 绝不 lazy；CSS background 装首屏
  主视觉 = 主动放弃预加载扫描器。
- **decode="async"**：大图解码移出主线程，插入 DOM 不卡帧；
  loading="lazy" 只做视口外懒加载（阈值不可调，别拿它驱动
  无限滚动 —— 142 节 IO 观察）。
- **格式梯子（2025 口径）**：AVIF 压缩比最高但编码慢（CDN 动态
  转码慎用）；`<picture><source type="image/avif"><source
  type="image/webp"><img>` 逐级回落。JPEG-XL 生态位已收缩
  （Chrome 移除支持），生产线 = AVIF+WebP+JPEG。
- **picture 管艺术方向，srcset 管密度**：同构图不同分辨率 →
  srcset/sizes；构图本身变（裁切/竖版）→ picture media。
- **video 纪律**：preload="none" + poster；autoplay 必须 muted+
  playsinline；iOS 上 load() 要用户手势。canvas 大图离屏化：
  OffscreenCanvas 把光栅移进 Worker。
- **iframe 三连**：loading="lazy"、title 必填（读屏报"框架"）、
  sandbox 最小面 —— 第三方内容加 credentialless（无凭证拉取，
  隔离 cookie）。

## 7. 链接与按钮深水

- **target="_blank" 现代语义**：现代浏览器已隐含 noopener
  （新页拿不到 window.opener），但**不隐含 noreferrer** ——
  外链要藏 referrer 显式写全 rel="noopener noreferrer"。
- **download 只对同源诚实**：跨源资源的 download 属性会被忽略
  （按导航处理）—— 真下载必须服务端配 Content-Disposition。
- **button 默认 type=submit 是表单内事故源**：表单里每个非提交
  按钮都显式 type="button"；提交分流用按钮上的
  formaction/formmethod/formnovalidate 改写。
- **disabled vs aria-disabled**：disabled 把控件移出 Tab 序、
  不触发事件、tooltip 不可达；aria-disabled 保留焦点与 hover
  解释（"为什么禁用"能被读到）。教育型禁用态选后者 +
  click 时说明原因。
- **焦点修复看归属**：自定义元素 shadow root 里点按钮默认焦点
  落 host —— delegatesFocus: true 让真按钮收焦点，否则
  :focus-visible 样式全打空（38 节）。
- **popover 与 invoker（2025）**：popovertarget 让按钮零 JS
  开关弹层（light dismiss 自动配）；command/commandfor
  （Invoker Commands API）把按钮语义化成命令声明 —— 能声明
  就不写 addEventListener。
- **href 必须是可分享 URL**：onclick 导航 = 右键复制链接/
  中键新开全废。SPA 路由也用 `<a href>` 真地址 + 拦截点击，
  别 span onclick。

## 8. 表格与列表深水

- **th 要 scope 才有映射**：单行表头 scope="col"，行头
  scope="row"；双级表头才用 headers 属性逐格指向（维护苦，
  能拆就拆）。
- **display 改写即语义蒸发**：`table{display:block}`（移动端
  "响应式表格"常用）会把表格语义从无障碍树摘除 —— 必须补
  role="table/row/cell" 全套，或改真列表/卡片区。
- **列表同理**：Safari 上 `ul{list-style:none}` 曾移除列表语义
  （Chrome 保留）—— 深度定制的导航加 role="list" 保险。
- **排序表头**：aria-sort="ascending/descending" 只放在**当前
  排序键**的 th 上；点击目标是包住文字的真 button。
- **宽表性能开关**：table-layout:fixed 跳过自动列宽测量
  （单元格内容不再参与布局求解）—— 千行级表格布局从几百 ms
  降到几十 ms 的关键。代价：溢出截断自己管。
- **sticky 表头结构约束**：外层 overflow 容器滚动 + th 上
  position:sticky；祖先链不能再有 overflow:hidden（59 节
  同坑）。sticky 表头 + table-layout:fixed 是宽表双保险。
- **数据表 vs 展示表**：面向机器的表（导出/剪贴）保完整语义；
  图表类可 role="presentation" + aria-describedby 旁路
  文字总结。

## 9. 内联语义深水

- **b/i 与 strong/em 不同物**：strong/em 进"朗读强调"（读屏
  变调），b/i 只是视觉遗留。文案关键词用 strong；纯排版加粗
  用 b + 注释说明意图。
- **abbr 只在首次出现挂 title**，且 title 悬浮不可达触屏 ——
  重要缩写第一次出现直接展开写全（"CSS（层叠样式表）"）。
- **time 的 datetime 必须机器可解析**：`<time>3 天前</time>`
  不合法；写 `<time datetime="2025-06-01">3 天前</time>`。
  时区：datetime 带偏移或 Z，别信"读者时区"。
- **用户生成内容包 bdi**：用户名混 RTL 字符会把整段标点反转
  —— 排行榜/评论区给不可信文本 `<bdi>`（或
  unicode-bidi:isolate）。
- **mark 在引用里是"原文没有的审校强调"**；搜索高亮是合法
  用法；装饰高亮用 span+背景色。
- **del/ins 带审计属性**：cite（改因链接）+ datetime ——
  法务/版本对照页的机器可读审计线。
- **装饰分隔符读出来很吵**：`<span aria-hidden="true">|</span>`
  让读屏跳过；反过来纯 CSS 生成的分隔符进不了复制粘贴 ——
  关键数据别放 ::before content。
- **断词先看 lang**：hyphens:auto 生效前提是 lang 正确
  （html lang 是 i18n 总开关）；CJK 微调用 word-break/
  line-break，中文别上 justify（末行标点悬挂各引擎不一致）。

## 10. 可见性与隐藏

六种"看不见"语义完全不同，选错就是事故：

| 手段 | 布局占位 | 焦点/交互 | 无障碍树 | 典型用途 |
| --- | --- | --- | --- | --- |
| display:none | 否 | 否 | 出树 | 彻底移除 |
| visibility:hidden | 是 | 否 | 出树 | 占位隐藏（可过渡） |
| opacity:0 | 是 | **是（还能点！）** | 在树 | 入场前态；禁点击补 pointer-events |
| content-visibility:hidden | 保留盒子 | 否 | 出树 | 大段内容开关（保渲染状态，恢复免重排） |
| hidden=until-found | 否（查找命中即展开） | 否 | 出树 | 可被页内查找展开的折叠内容 |
| inert | 是 | **整子树锁死** | 出树 | 模态背景冻结（键盘+读屏+指针全锁） |

- **模态正确姿势**：dialog.showModal() 自动给背景 inert ——
  手写半透明背景而背景仍可 Tab，是焦点越狱头号来源（38 节）。
- **content-visibility:auto**：视口外跳过渲染（保盒子与状态），
  配 contain-intrinsic-size 给预估高度防滚动条跳变（28 节）。
- **.sr-only 剪裁法**（视觉隐藏但读屏可达）：1px clip-path
  裁剪而非 display:none —— 图标按钮补文字全靠它。
- **选型口诀**：关语义用 inert；省渲染用 content-visibility；
  彻底删用 display:none —— 三者不可互换。

## 快速索引 · 续（21-34 节）

| 想找什么 | 去哪一节 |
| --- | --- |
| 层叠上下文与 z-index | 21. 层叠 |
| 显示类型 | 22. display |
| 尺寸单位 | 23. 单位 |
| 溢出处理 | 24. 溢出 |
| 响应式设计 | 25. 响应式 |
| 媒体查询 | 26. 媒体查询 |
| 容器查询 | 27. 容器查询 |
| 现代选择器 | 28. 现代选择器 |
| 变量（自定义属性） | 29. CSS 变量 |
| 计算与函数 | 30. CSS 函数 |
| 过渡与动画 | 31. 动画 |
| transform | 32. transform |
| 滤镜与混合 | 33. 滤镜 |
| 暗色模式 | 34. 暗色模式 |

## 21. 层叠

- z-index 只对"创建层叠上下文的元素"生效：定位+z、flex/grid
  子项、opacity<1、transform/filter/perspective、isolation、
  will-change、contain:paint、position:fixed/sticky（隐式）。
- **同上下文内的绘制顺序**：背景边框 → 负 z 子上下文 → 块级流 →
  浮动 → 行内 → z:auto/0 → 正 z 子上下文。"文字穿到背景上"
  类 bug 对照这张序表秒解。
- 排查"盖不住"三步：①两元素是否各自成上下文（比的是**上下文
  的 z**，不是元素 z）②父上下文的 z 先于一切子元素决胜
  ③DOM 顺序只在同 z 同层比较。DevTools 3D 视图直看上下文塔。
- z-index 值域 ±2147483647，越界回绕为负 —— 巨型 z 是时代眼泪，
  isolation 隔离 + @layer 重构才是解（14 节）。
- **containment/isolation 是便宜的隔离器**：组件根上加
  isolation:isolate，内部 z 永不外泄 —— 第三方组件共存
  （弹层/地图/播放器）的秩序底线。

## 22. display

| 值 | 行为 |
| --- | --- |
| block | 独占行 |
| inline | 行内（宽高无效） |
| inline-block | 行内可设尺寸 |
| flex/grid | 容器化（子项布局模式切换） |
| none | 移出（无障碍树也移除） |
| contents | 自身消失子项上位（少用） |
- visibility:hidden 保留布局；display:none 不保留。

## 23. 单位

- **vh 的移动端原罪**：地址栏伸缩让 100vh 抖动。
  dvh=动态视口（跟手但每次地址栏变化都重排）、svh=最小视口
  （固定 UI 的稳定选择）、lvh=最大视口（基本别用）。
  全屏容器 100dvh，吸底栏 100svh。
- **视口单位动画化代价**：dvh 变化触发整容器重排 —— 拿它做
  transition 等于每帧 layout。要动画的内高度用固定值 +
  svh 定界。
- **容器单位族（配 container-type）**：cqw/cqh/cqi/cqb ——
  字号/间距随**组件容器**缩放，配 clamp 实现"组件级流式排版"
  （27 节联动）；注意 @container 内的百分比基准也切到容器。
- **em 在媒体查询里是初值字号**：`@media (min-width: 40em)`
  的 em 基于初始 font-size（通常 16px），与 html 上改字号
  无关 —— 想随用户字号缩放断点的人常被这坑反咬。
- **行高无单位**：1.5 会被子元素**继承比例**再乘自身字号；
  1.5em 继承的是算完的绝对值 —— 多层嵌套文本用无单位。
- **px 不是物理像素**：CSS px 是参考像素，DPR/缩放下由设备
  换算；发丝线用 0.5px（iOS）或媒体查询分档。
- **rem 的可及性承诺**：用户字号设置通过 rem 生效 ——
  字号/间距 rem 化，边框/阴影 px 化，"缩放不破版"组合拳。

## 24. 溢出

| 组合 | 效果 |
| --- | --- |
| overflow:hidden | 裁切 |
| overflow:auto | 需要时出滚动条 |
| overflow-x/y | 单轴 |
| text-overflow:ellipsis | 单行省略（配 white-space:nowrap） |
- 纵向滚动容器给 -webkit-overflow-scrolling:touch（老 iOS 惯性）；
  overscroll-behavior:contain 防滚动链。

## 25. 响应式

- 断点参考（内容驱动，非设备驱动）：
  <480 手机 / 480-768 大手机 / 768-1024 平板 / >1024 桌面。
- 手机优先：base=手机样式，断点向上覆盖（min-width）。
- 图片响应式：srcset+sizes 或 picture（6 节）。
- 表格响应式：横向滚动容器（overflow-x:auto）优于拆卡（数据可比性）。

## 26. 媒体查询

```css
@media (min-width: 768px) { … }
@media (prefers-color-scheme: dark) { … }   /* 34 节 */
@media (prefers-reduced-motion: reduce) { 动画降级 }
@media print { 打印样式 }
```

- 断点值集中成 CSS 变量注释口径（团队统一）。

## 27. 容器查询

```css
.card-wrap { container-type: inline-size; }
@container (min-width: 480px) { .card { 横排 } }
```

- 组件级响应式（组件不再依赖页面宽度）—— 组件库时代的关键件。

## 28. 现代选择器

- **:has() 的性能画像**：引擎对 :has 做失效缓存（DOM 变化
  即失效重算）。窄化用法几乎免费：`:has(> img)`（只看子代）、
  `:has(+ .err)`（只看邻接）。**深后代 + 多分支**的
  `:has(.x .y)` 会把失效集膨胀成整树监视 —— 单页几十处就是
  Recalculate Style 的大头。
- **:is() 双刃**：①容错解析——参数里一个不认识的选择器不会
  废整组；②特异性取**参数里最贵者**——`:is(#a, .b)` 带着
  id 特异性；要归零用 :where()。
- **:nth-child(An+B [of S])**：先按 S 过滤再数序号——"第 3 个
  .card"不再依赖 .card:nth-child(3) 那种数兄弟全序的脆弱写法。
- **属性选择器 flags**：`[attr="Val" i]`（ASCII 不分大小写，
  class/rel 常用）、`[attr="Val" s]`（强制区分）。JS 侧校验
  记住 ES2024 RegExp `v` 标志（unicode sets：`[\p{L}--\p{ASCII}]`
  集合差/交）—— 但表单 pattern 属性只吃 JS 正则子集，
  两端不同构。
- **:user-valid/:user-invalid** 优于裸 :invalid：后者表单一
  打开就标红（值还空着）。校验 UI 时序详见 5 节。
- **:defined/:fullscreen/:modal**：自定义元素升级完成/全屏/
  模态打开才生效的态钩子 —— 少一个全局类就少一次全树失效。
- **containment 隔离失效**（与 11 节引擎内幕呼应）：
  contain:layout/paint/style 封住子树失效边界，长列表/
  仪表盘大页标配 `content-visibility:auto` +
  contain-intrinsic-size（10 节）。

## 29. CSS 变量

```css
:root { --brand: #3b82f6; --gap: 16px; }
.card { background: var(--brand); padding: var(--gap); }
@media (prefers-color-scheme: dark) { :root { --brand: #60a5fa; } }
```

- 约定：语义命名（--brand/--gap）不写值名（--blue）；
  作用域就近覆盖（主题/暗色/组件级）。

## 30. CSS 函数

| 函数 | 用途 |
| --- | --- |
| calc() | 运算 |
| min()/max()/clamp() | 尺寸弹性★ clamp(min, ideal, max) |
| var() | 变量引用 |
| color-mix() | 颜色混合（新） |
| env() | 环境值（safe-area-inset） |

## 31. 动画

| 属性 | 优先级 |
| --- | --- |
| transform/opacity | 合成层（60fps 友好）★ |
| top/left/width | 触发布局（尽量避免） |
- transition 控两个状态；@keyframes 控多帧；
  will-change 提示（用完移除）。

## 32. transform

| 函数 | 作用 |
| --- | --- |
| translate(x,y) | 位移（替代 top/left 动画） |
| scale() | 缩放 |
| rotate() | 旋转 |
| skew() | 扭曲 |
| translateZ(0) | 强制合成层（节流用） |

## 33. 滤镜

| 属性 | 用途 |
| --- | --- |
| filter | blur/grayscale/drop-shadow |
| backdrop-filter | 背景毛玻璃★ |
| mix-blend-mode | 混合模式 |
- backdrop-filter 性能敏感（低端机降级方案预留）。

## 34. 暗色模式

```css
@media (prefers-color-scheme: dark) {
  :root { --bg: #0f172a; --fg: #e2e8f0; }
}
/* 手动切换（跟随系统+手动覆盖）用 [data-theme] 属性 */
```

- 纪律：颜色全部变量化；图片暗色适配（filter 或双图）；
  不要硬编码色值（一处漏全盘崩）。

## 快速索引 · 续（35-48 节）

| 想找什么 | 去哪一节 |
| --- | --- |
| 可访问性总纲 | 35. a11y 总纲 |
| ARIA 速查 | 36. ARIA |
| 键盘可达 | 37. 键盘 |
| 焦点管理 | 38. 焦点 |
| 对比度 | 39. 对比度 |
| 文本缩放 | 40. 文本缩放 |
| 表单 a11y | 41. 表单 a11y |
| 动效无障碍 | 42. 动效 a11y |
| 性能指标 | 43. 指标 |
| 加载策略 | 44. 加载策略 |
| 关键渲染路径 | 45. CRP |
| 字体加载 | 46. 字体 |
| 图片优化 | 47. 图片 |
| JS 与页面 | 48. 脚本 |

## 35. a11y 总纲

- 语义 HTML 解决 80%（4 节地标/7 节按钮）；
  ARIA 只在语义不够时补（36 节）。
- 第一条规则：能不用 ARIA 就不用（ARIA 修复错用法比不用更糟）。

## 36. ARIA

| 属性 | 用途 |
| --- | --- |
| role | 角色（dialog/tab/alert） |
| aria-label | 无文本时的名字 |
| aria-labelledby | 指向现有文本当名字 |
| aria-describedby | 补充说明 |
| aria-expanded | 展开/收起态 |
| aria-current | 当前项（面包屑/分页） |
| aria-live | 动态内容播报（polite/assertive） |
| aria-hidden | 装饰性隐藏（焦点元素上禁用） |

## 37. 键盘

| 键 | 期望 |
| --- | --- |
| Tab | 顺序=阅读顺序（DOM 序） |
| Enter | 激活（button/a 天然支持） |
| Space | 激活/滚动 |
| Esc | 关闭弹层 |
| 方向键 | 自定义组件内导航（菜单/滑块） |
- 正面清单：所有操作可键盘完成；焦点永不丢失（38 节）。

## 38. 焦点

| 场景 | 动作 |
| --- | --- |
| 打开弹层 | focus 移入弹层首个可交互元素 |
| 弹层内 Tab | 焦点圈在层内（focus trap） |
| 关闭弹层 | 焦点归还触发器★ |
- inert 属性批量禁区（新标准）。

## 39. 对比度

| 文本 | 最低对比度 |
| --- | --- |
| 正文 | ≥四点五比一（4.5:1） |
| 大字(≥18pt) | ≥3:1 |
| 图形/UI 件 | ≥3:1 |
- 工具：对比度计算器；暗色模式单独校验。

## 40. 文本缩放

- 全 rem 布局（23 节）；用户放大 200% 不丢内容（可换行）。
- 禁用 px 字号（钉死用户偏好）。

## 41. 表单 a11y

- label 永远在；错误文案 aria-describedby 指向；
  提交后焦点移到第一个错误字段；
  自动填充不触发校验轰炸（change 后再校）。

## 42. 动效 a11y

- prefers-reduced-motion 降级（26 节）；
  闪烁频率 <3Hz（防光敏）；
  滚动触发动画（scroll-linked）给弱动画。

## 43. 指标

| 指标 | 线 |
| --- | --- |
| LCP | <2.5s |
| INP | <200ms |
| CLS | <0.1 |
| TTFB | <800ms |

## 44. 加载策略

| 手段 | 场景 |
| --- | --- |
| preload | 关键字体/首屏图 |
| prefetch | 下一页可能资源（空闲时） |
| lazy | 非首屏图/iframe |
- defer 脚本默认（3 节）；async 只用于独立统计类。

## 45. CRP

关键路径 = HTML → CSSOM + DOM → 渲染树 → 布局 → 绘制。
- CSS 阻塞渲染（放 head 且小）；
  JS 阻塞解析（defer 化）；
  关键 CSS 内联首屏 <14KB 其余异步。

## 46. 字体

```css
font-display: swap;             /* 先系统字后换（防不可见文本） */
unicode-range: …;               /* 中文字体子集化必做 */
```

- 子集化：中文字体按常用 3000 字子集（工具产出 woff2）。

## 47. 图片

| 手段 | 要点 |
| --- | --- |
| 格式 | avif/webp 优先（picture 兜底） |
| 尺寸 | srcset+sizes 按屏出图 |
| 占位 | 宽高属性（6 节）防 CLS |
| 懒加载 | loading=lazy（首屏外） |

## 48. 脚本

- defer 全默认；模块化（type=module 天生 defer）。
- 事件委托省监听器；长任务拆帧（requestIdleCallback）。

## 快速索引 · 续（49-62 节）

| 想找什么 | 去哪一节 |
| --- | --- |
| 文本属性全表 | 49. 文本属性 |
| 字体属性 | 50. 字体属性 |
| 颜色与背景 | 51. 颜色背景 |
| 边框与圆角 | 52. 边框 |
| 阴影 | 53. 阴影 |
| 交互指针 | 54. 指针 |
| 用户选择 | 55. 选择控制 |
| 布局速查（Flex） | 56. Flex 速查 |
| 布局速查（Grid） | 57. Grid 速查 |
| 间距系统 | 58. 间距系统 |
| 位置速查 | 59. 定位速查 |
| 常见布局配方 | 60. 布局配方 |

## 49. 文本属性

| 属性 | 常用值 | 备注 |
| --- | --- | --- |
| color | 颜色 | 继承 |
| font-size | rem | 23 节 |
| font-weight | 400/700（变量字重） | 数字优于关键词 |
| line-height | 无单位倍数（1.5）★ | 正文必设 |
| text-align | start/end（逻辑）★ | rtl 友好 |
| text-decoration | underline + color/thickness | 链接下划线可美化 |
| text-transform | uppercase | 只视觉 |
| letter-spacing | 紧排标题用 | em 单位 |
| white-space | nowrap/ pre-line | 折行控制 |
| text-overflow | ellipsis | 配 24 节三件 |
| word-break | break-all（长 URL） | 中文混排注意 |
| hyphens | auto（配 lang） | 断字 |
| text-indent | 首行缩进 | 中文排版 |
| direction | rtl | 逻辑属性配套 |

## 50. 字体属性

| 属性 | 值 |
| --- | --- |
| font-family | 栈（系统栈兜底）★ |
| font-style | italic |
| font-variant | numeric: tabular-nums（表格对齐数字）★ |
| @font-face | src/format/unicode-range |
- 系统字体栈（免下载）：
  system-ui, -apple-system, "Segoe UI", Roboto, "Noto Sans SC", sans-serif。

## 51. 颜色背景

| 属性 | 要点 |
| --- | --- |
| background-color | 纯色 |
| background-image | 多层叠放；渐变也是图 |
| background-size | cover（裁切保满）/ contain（完整留白） |
| background-position | 居中常用 |
| linear-gradient | 装饰/主题 |
| opacity | 整体透明（层叠上下文副作用，21 节） |
| color 值 | hex/rgb/hsl —— hsl 便于派生（暗色变亮度）★ |

## 52. 边框

| 属性 | 值 |
| --- | --- |
| border | width style color |
| border-radius | 圆角；胶囊=9999px |
| outline | 焦点环（不占布局）★ :focus-visible 配 |
| border-image | 少用 |
- 描边细线：1px 在高分屏发虚 → 用 0.5px 或 box-shadow 模拟。

## 53. 阴影

```css
box-shadow: 0 1px 2px rgb(0 0 0 / .08), 0 8px 24px rgb(0 0 0 / .12);
/* 双层：近实远虚（比单层真实） */
```

- 层次语义：海拔越高阴影越散（设计 token 化）。

## 54. 指针

| 场景 | cursor |
| --- | --- |
| 可点 | pointer |
| 等待 | progress（有结果）/ wait（纯等） |
| 文本 | text |
- 自定义组件记得补（原生控件免费，div 需要手动）。

## 55. 选择控制

| 属性 | 用途 |
| --- | --- |
| user-select | 禁选代码块外的误选 |
| pointer-events | 让装饰层可穿透（点击落到下面）★ |
- touch-action 控制手势（防双击缩放）。

## 56. Flex 速查

容器：

| 属性 | 常用 |
| --- | --- |
| display:flex | 入口 |
| flex-direction | row/column |
| flex-wrap | wrap（换行） |
| gap | 间距★ |
| justify-content | 主轴分布（start/center/space-between） |
| align-items | 交叉轴对齐（center/stretch） |
| align-content | 多行打包对齐 |

子项：

| 属性 | 语义 |
| --- | --- |
| flex: 1 | 填满剩余 |
| flex: 0 0 120px | 固定宽 |
| align-self | 覆盖父对齐 |
| order | 排序（视觉序） |
| min-width: 0 | 允许收缩小于内容★（忘写=溢出元凶） |

## 57. Grid 速查

容器：

| 属性 | 常用 |
| --- | --- |
| display:grid | 入口 |
| grid-template-columns | repeat(auto-fill, minmax(220px, 1fr))★ 自适应卡墙 |
| grid-template-rows | 显式行 |
| gap | 行列间距 |
| grid-template-areas | 命名区布局（可读性★） |
| justify-items/align-items | 单元内对齐 |

子项：

| 属性 | 语义 |
| --- | --- |
| grid-area | 进命名区 |
| grid-column: 1 / -1 | 跨满行 |
| justify-self | 自身覆盖 |

## 58. 间距系统

- 设计 token 化：4 的倍数（4/8/16/24/32/48）；
  组件内小、组件间中、区块间大 —— 三个层位就够。
- 禁随手 13px/17px（视觉抖动源）。

## 59. 定位速查

| 值 | 行为 | 场景 |
| --- | --- | --- |
| static | 默认流 | — |
| relative | 相对自身偏移（原位保留） | 微调 + 定位锚 |
| absolute | 相对最近定位祖先 | 装饰/徽标 |
| fixed | 相对视口 | 吸顶栏 |
| sticky | 滚动到阈值后钉住 | 表头/侧栏★ |
- 吸顶父级不能有 overflow:hidden（sticky 失效元凶）。

## 60. 布局配方

**A：圣杯（页头/主+侧/页脚）**

```css
body { display: grid; min-height: 100dvh;
  grid-template: "hd hd" auto "sd mn" 1fr "ft ft" auto / 220px 1fr; }
header { grid-area: hd } aside { grid-area: sd } main { grid-area: mn } footer { grid-area: ft }
```

**B：自适应卡墙**

```css
.cards { display: grid; gap: 16px;
  grid-template-columns: repeat(auto-fill, minmax(min(220px, 100%), 1fr)); }
```

**C：完美居中**

```css
.center { display: grid; place-items: center; min-height: 100dvh; }
```

**D：双栏等高**

```css
.row { display: flex; align-items: stretch; }
```

**E：粘性页脚**

```css
body { display: flex; flex-direction: column; min-height: 100dvh; }
main { flex: 1 }
```

## 快速索引 · 续（61-74 节）

| 想找什么 | 去哪一节 |
| --- | --- |
| 组件模式·弹层 | 61. 弹层 |
| 组件模式·标签页 | 62. 标签页 |
| 组件模式·手风琴 | 63. 手风琴 |
| 组件模式·下拉 | 64. 下拉 |
| 组件模式·面包屑 | 65. 面包屑 |
| 组件模式·分页 | 66. 分页 |
| 组件模式·骨架屏 | 67. 骨架屏 |
| 组件模式·toast | 68. toast |
| 组件模式·模态 | 69. 模态 |
| 组件模式·表单错误 | 70. 表单错误 |
| CSS 工程化 | 71. CSS 工程化 |
| BEM/命名 | 72. 命名法 |
| 预处理器 vs 现代原生 | 73. 预处理器 |
| Tailwind 口径 | 74. Tailwind |

## 61. 弹层

```css
.popover { position: absolute; inset: auto; }
/* JS 定位：锚 rect + 容器边界翻转（上放下放切换） */
```

- 约束：挂 body 防被父层裁切；Esc 关闭（37 节）；点击外关闭。

## 62. 标签页

- 语义：role=tablist/tab/tabpanel + aria-selected；
  方向键切换（37 节自定义导航）。

## 63. 手风琴

- 原生 details/summary 免 JS★；需要动画时再加 JS（grid-template-rows 技巧）。

## 64. 下拉

- 原生 select 起步（a11y 免费）；自定义必须补键盘全链
  （↑↓ Enter Esc typeahead）。

## 65. 面包屑

```html
<nav aria-label="面包屑"><ol><li><a>首页</a></li><li aria-current="page">详情</li></ol></nav>
```

## 66. 分页

- 当前页 aria-current="page"；
  上千万级数据改为"加载更多/无限滚"（配虚拟列表）。

## 67. 骨架屏

```css
.skeleton { background: linear-gradient(90deg,#eee,#f5f5f5,#eee);
  background-size: 200% 100%; animation: shimmer 1.2s infinite linear; }
@keyframes shimmer { to { background-position: -200% 0 } }
```

- 尺寸与真实内容一致（防 CLS）。

## 68. toast

- aria-live=polite；3-5s 自动消失；不打断焦点★。

## 69. 模态

- dialog 元素（原生 focus trap + Esc）★；
  ::backdrop 遮罩；打开时 body overflow:hidden 防背景滚。

## 70. 表单错误

| 项 | 口径 |
| --- | --- |
| 展示时机 | 失焦或提交，别边打边骂 |
| 位置 | 字段下方（视觉关联） |
| 语义 | aria-describedby 指向错误文本 |
| 焦点 | 提交后跳第一个错处 |

## 71. CSS 工程化

| 方案 | 取舍 |
| --- | --- |
| 原生 + 变量 | 零依赖，新项目首选 |
| CSS Modules | 作用域隔离 |
| Tailwind | 原子类（74 节） |
| CSS-in-JS | 运行时成本，退潮中 |

## 72. 命名法

```css
.card {}            /* 块 */
.card__title {}     /* 元素 */
.card--featured {}  /* 修饰 */
.is-open {}         /* 状态（JS 切） */
```

## 73. 预处理器

- 嵌套已进原生（2023+）；变量用 CSS 变量；
  预处理器剩余价值：mixin/循环（在减少）。

## 74. Tailwind

- 优点：约束内一致性/无命名负担/产物可剪裁。
- 纪律：定制 token 进配置（别堆 arbitrary 值）；
  组件层抽 @apply/组件类收口；语义 HTML 与 Tailwind 正交（都要做）。
- 本仓库生成的 HTML 产物常带 Tailwind CDN —— 布局分析器
  （HtmlAnalyzeLayout）按其口径估算尺寸。

## 快速索引 · 续（75-88 节）

| 想找什么 | 去哪一节 |
| --- | --- |
| DOM 基础 | 75. DOM |
| 事件体系 | 76. 事件 |
| 事件委托 | 77. 委托 |
| 浏览器存储 | 78. 存储 |
| 网络请求 | 79. fetch |
| 跨端通信 | 80. postMessage |
| 历史与路由 | 81. history |
| 拖放 | 82. 拖放 |
| IntersectionObserver | 83. IO |
| MutationObserver | 84. MO |
| requestAnimationFrame | 85. rAF |
| 模块化 | 86. 模块 |

## 75. DOM

| API | 用途 |
| --- | --- |
| querySelector(All) | 选择（CSS 语法）★ |
| closest | 向上找（事件委托配）★ |
| createElement | 建节点 |
| append/prepend | 插入 |
| classList.toggle | 状态切换★ |
| dataset | data-* 读写 |
| matches | 选择器判定（委托配） |

## 76. 事件

| 知识点 | 口径 |
| --- | --- |
| 冒泡 | 子 → 父（委托基础） |
| 捕获 | 父 → 子（拦截用） |
| target vs currentTarget | 谁触发/谁监听★ |
| preventDefault | 阻默认（表单/链接） |
| stopPropagation | 阻冒泡（少用） |
| once | 一次性监听 |
| passive | 声明不改默认（滚动性能）★ |

## 77. 委托

```js
list.addEventListener("click", (e) => {
  const btn = e.target.closest("button[data-id]");
  if (!btn) return;
  remove(btn.dataset.id);
});
```

- 一处监听管万项（列表性能/动态内容必用）。

## 78. 存储

| API | 容量 | 场景 |
| --- | --- | --- |
| localStorage | ~5MB | 偏好（非敏感） |
| sessionStorage | ~5MB | 会话级 |
| IndexedDB | 大 | 结构化离线数据 |
| cookie | 4KB | 会话（73 节属性） |
- 敏感值不进 storage（可被 XSS 读）。

## 79. fetch

```js
const r = await fetch(url, {
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify(payload),
});
if (!r.ok) throw new Error(`HTTP ${r.status}`);
const data = await r.json();
```

- fetch 4xx/5xx 不抛 —— 手动判 r.ok★（最常见坑）；
  超时要 AbortController 自配。

## 80. postMessage

```js
iframe.contentWindow.postMessage(data, targetOrigin);
// 接收方校验 event.origin ★
```

## 81. history

| API | 作用 |
| --- | --- |
| pushState | 进路由（不刷新） |
| replaceState | 替换当前 |
| popstate | 后退监听 |
- 路由三原则：能进 URL 的都进 URL；深链可达；后退符合直觉。

## 82. 拖放

- dataTransfer 传数据；dropzone 阻默认（dragover preventDefault★）。
- 无障碍：拖拽永远配等效按钮/菜单路径（37 节红线）。

## 83. IO

```js
new IntersectionObserver(cb, { rootMargin: "200px" })
  .observe(el);   // 懒加载/入场动画/无限滚
```

## 84. MO

监听子树变化（第三方脚本注入感知/自动格式化）——
避免当胶水滥用（性能观察者）。

## 85. rAF

- 动画节拍器：下一帧前调（60fps 对齐）；
  页面隐藏自动暂停（比 setInterval 强）★。

## 86. 模块

```html
<script type="module" src="app.js"></script>  <!-- 天生 defer + 作用域 -->
```

- import/export 静态；import() 动态（分包入口，85 节配合）。

## 快速索引 · 续（87-100 节）

| 想找什么 | 去哪一节 |
| --- | --- |
| JS 核心·类型判别深水 | 87. 类型判别深水 |
| 相等语义三件套 | 88. 相等语义三件套 |
| 数组方法与引擎表示 | 89. 数组方法与引擎表示 |
| 解构与展开深水 | 90. 解构与展开深水 |
| 异步模式 | 91. 异步 |
| Promise 组合 | 92. Promise 组合 |
| 错误处理 | 93. 错误 |
| this 与箭头函数 | 94. this |
| 闭包 | 95. 闭包 |
| 常见 JS 坑 | 96. JS 坑 |

## 87. 类型判别深水

- **跨 iframe/Realm 的 instanceof 必假**：两个 window 各有
  Array 构造器，`iframe.contentWindow.Array !== 本域 Array`。
  判数组一律 Array.isArray（跨 Realm 安全）；判 TypedArray 用
  ArrayBuffer.isView。
- **品牌判别用 Object.prototype.toString 或 #private**：
  `Object.prototype.toString.call(x)` 拿内置标签（跨 Realm 稳）；
  自定义类用 `#field in obj`（真品牌检查，子类骗不过；Proxy
  上做 `#field in` 会 throw）。
- typeof 的边界：document.all 是唯一 typeof 得 "undefined" 的
  对象（历史兼容）；async function 得 "async function"；
  判可调用最稳也是 toString 系。
- 鸭子辨型陷阱：arguments 是类数组（Symbol.toStringTag 为
  "arguments"）；NodeList 可迭代但不是数组 ——
  Array.from(x) 之前别做 Array.isArray 断言。

## 88. 相等语义三件套

规范里相等有**三种**，选错语义就是 bug：

| 语义 | NaN | -0 | 使用者 |
| --- | --- | --- | --- |
| IsStrictlyEqual（===） | 不等 | 相等 | 一切显式比较 |
| SameValue（Object.is） | 相等 | 不等 | 属性键查重/defineProperty |
| SameValueZero | 相等 | 相等 | Map/Set 键、includes、from |

- Map/Set 的键去重用 SameValueZero：`NaN === NaN` 为假但
  `new Set([NaN]).size === 1`；反过来 -0 在 Map 里与 0 同键
  （坐标/方向缓存会撞，Object.is 才能区分）。
- == 的合法场景只剩 `x == null`（null/undefined 双检）；
  其他全按 ===。对象判空 Object.keys(x).length。
- 深比较别手挫（循环引用/键序/Symbol）——用
  assert.deepStrictEqual 语义（node:test/vitest 内建）。

## 89. 数组方法与引擎表示

- **元素种类（Elements Kind）单向劣化**：packed-SMI →
  packed-double → packed → holey。`delete arr[i]` 造洞变
  holey（慢一档）；`arr[i] = 1.5` 整组降 double。去元素用
  splice 或赋 undefined（不造洞）。
- **map/filter 跳洞**：holey 数组上 map 不回调空洞（长度保持、
  洞保留）——`[,1].map(fn)` 只跑一次！要稠密先 Array.from(x)。
- **sort 是稳定的（ES2019+）且原地**；比较器不一致（NaN/
  返回值越界）结果实现定义。比较器纪律：`(a,b)=>a-b` 只对
  纯数字；字符串用 localeCompare 带语言参数（i18n 排序陷阱）。
- **不可变新贵（ES2023）**：toSorted/toReversed/toSpliced/with
  —— React/状态机首选；但 10^6 级数组它们仍 O(n) 复制，
  热路径留给原地 + 视图层 memo。
- species 陷阱：子类数组 map 默认构造子类（自定义构造器抛错
  连带 map 抛）—— 通用库用 Symbol.species 指回 Array。

## 90. 解构与展开深水

- **默认值只救 undefined**：null 不触发默认值
  （`const {a=1} = {a:null}` → a=null）——接口空值语义先约定
  再解构。
- 求值顺序左→右，默认值可引用**前面**已解出的变量
  （`{a, b = a * 2} = {a: 2}` → b=4）；引用后面的是 TDZ。
- rest 必须居尾；对象 rest 只收**自身可枚举**属性（原型与
  symbol 都不收）——继承字段悄悄丢；浅拷贝要全就
  Object.getOwnPropertyDescriptors。
- **展开的成本分级**：数组展开走迭代器（真数组有快路径，
  Set/自定义迭代器慢）；对象展开逐键 getter 求值——大对象
  高频 spread 是 V8 逃逸分析杀手，热路径手写赋值。
  函数签名 `...args` 会真建数组（arguments 不建，但 arrow
  没有 arguments）。
- **解构一切可迭代**：String/Map/Generator/Set 都能 [a,b]；
  自定义类实现 Symbol.iterator 后解构即"多返回值"。配
  ES2024 RegExp v 标志（unicode sets）的捕获分组做精确校验。

## 91. 异步

| 形态 | 用途 |
| --- | --- |
| async/await | 默认 |
| Promise.all | 并行全取（一败全败） |
| allSettled | 并行全知（不看败）★ |
| race | 竞速（超时配） |
| for await…of | 流式消费 |

## 92. Promise 组合

```js
const withTimeout = (p, ms) =>
  Promise.race([p, new Promise((_, rej) => setTimeout(() => rej(new Error("timeout")), ms))]);
```

## 93. 错误

| 场景 | 动作 |
| --- | --- |
| await 抛错 | try/catch 就近 |
| 网络错 | 分类提示（可重试/不可） |
| 全局兜底 | window.onerror 上报（带 sourceURL/line） |
- 禁空 catch（吞错黑洞）。

## 94. this

| 场景 | this |
| --- | --- |
| 方法调用 | 调用者 |
| 箭头函数 | 定义处（词法）★ |
| 事件监听 | 绑定元素 |
| class | 实例 |
- 箭头函数不能做构造器；需要 this 的回调别用箭头（如 DOM handler 要 e.currentTarget）。

## 95. 闭包

- 函数记住定义域变量 —— 模块私有/记忆化的原理。
- 泄漏面：长寿命对象引用大对象（解绑/置 null）。

## 96. JS 坑

| 坑 | 解 |
| --- | --- |
| 0.1+0.2 !== 0.3 | 浮点；金额用整数分 |
| ["1","2"].map(parseInt) | parseInt 收 (str, radix)；map 传 idx → 显式 Number |
| setTimeout(0) 不立即 | 事件循环：微任务先于宏任务 |
| var 提升/循环捕获 | 用 let/const（块作用域捕获）★ |
| sort 默认字典序 | [1,10,2] → 传 (a,b)=>a-b |
| 对象浅拷贝 | structuredClone 深拷（新） |
| for-in 带原型链 | Object.keys 遍历 |
| NaN 判断 | Number.isNaN |
| 隐式全局（漏 let） | 严格模式 use/module 域 |
| 迭代中改数组 | 先快照 |

## 快速索引 · 续（97-110 节）

| 想找什么 | 去哪一节 |
| --- | --- |
| 调试方法 | 97. 调试 |
| 性能排查 | 98. 性能排查 |
| 内存泄漏排查 | 99. 泄漏排查 |
| 白屏排查 | 100. 白屏 |
| 兼容性策略 | 101. 兼容性 |
| 移动端专项 | 102. 移动专项 |
| 测试策略 | 103. 测试策略 |
| 组件测试 | 104. 组件测试 |
| E2E 测试 | 105. E2E |
| 视觉回归 | 106. 视觉回归 |

## 97. 调试

| 手段 | 场景 |
| --- | --- |
| console.table | 结构化数据 |
| console.dir | DOM 节点 |
| debugger | 断点（留代码里=事故） |
| Sources 断点+监视 | 逻辑流 |
| Elements | 样式命中/覆盖链 |
- 纪律：调试代码不留库（CI 检查 debugger）。

## 98. 性能排查

| 层 | 工具 |
| --- | --- |
| 指标 | Lighthouse/Field（43 节） |
| 长任务 | Performance 面板看 Long Task |
| 渲染 | Rendering 面板（paint flashing） |
| 网络 | Network 面板（瀑布+瀑布解释） |

## 99. 泄漏排查

| 症状 | 常因 |
| --- | --- |
| 内存单调涨 | 全局缓存无上限/监听未解绑 |
| Detached 节点 | 移除后仍被引用（闭包握着） |
- Heap 快照三对比（操作前后+GC）。

## 100. 白屏

排查序：Console 第一错 → 资源 4xx/5xx（Network）→
  路由错配 → 渲染条件永远 false。
- 兜底：全局 error boundary 显示恢复指引（不是死白屏）。

## 101. 兼容性

| 策略 | 手段 |
| --- | --- |
| 渐进增强 | 基线功能全支持，新特性加强 |
| 检测 | @supports（CSS）/ in 运算（JS 特性检测） |
| 转译 | browserslist 目标驱动构建 |
- 禁 UA 嗅探分支（维护地狱）。

## 102. 移动专项

| 项 | 口径 |
| --- | --- |
| 视口 | dvh/svh 单位（23 节） |
| 安全区 | env(safe-area-inset-*) |
| 触控 | 目标 ≥44px |
| 输入 | font-size≥16px（防 iOS 聚焦缩放） |
| 滚动 | -webkit-overflow-scrolling/overscroll |
| 键盘 | visualViewport 监听 |

## 103. 测试策略

| 层 | 占比 | 工具 |
| --- | --- | --- |
| 单元（纯函数） | 多 | 任意 |
| 组件 | 中 | 测试库（可访问性查询） |
| E2E | 少 | Playwright 类 |
- 选择器优先语义（role/text）—— 与 a11y 双赢★。

## 104. 组件测试

```js
expect(screen.getByRole("button", { name: "提交" })).toBeEnabled();
fireEvent.click(screen.getByRole("button", { name: "提交" }));
await screen.findByText("成功");
```

## 105. E2E

- 只盖关键流（登录/下单/支付）；每条一目的；
  数据自播种（不依赖环境残留）。

## 106. 视觉回归

| 方案 | 取舍 |
| --- | --- |
| 截图 diff | 快但脆（抗噪设置难） |
| DOM 快照 | 稳但漏样式 |
- 关键页抽样跑（成本高）。

## 快速索引 · 续（107-120 节）

| 想找什么 | 去哪一节 |
| --- | --- |
| 深度问答·布局 | 107. 问答·布局 |
| 深度问答·CSS | 108. 问答·CSS |
| 深度问答·JS | 109. 问答·JS |
| 反模式大全 | 110. 反模式 |
| 剧本·从设计稿到页面 | 111. 剧本·设计稿 |
| 剧本·页面体检 | 112. 剧本·体检 |
| 剧本·排查布局 bug | 113. 剧本·布局 bug |
| 剧本·优化首屏 | 114. 剧本·首屏 |
| 剧本·组件抽取 | 115. 剧本·抽组件 |
| 剧本·深色适配 | 116. 剧本·暗色 |

## 107. 问答·布局

| 问 | 答 |
| --- | --- |
| 居中总歪 | 内容自身宽高不定 → grid place-items |
| 子项撑破容器 | flex 子 min-width:0（56 节★） |
| 等高对不齐 | align-items:stretch 默认就有 —— 检查谁覆盖了 |
| 吸顶失效 | 祖先 overflow:hidden（59 节） |
| 双向滚动打架 | 外层别包滚动容器；嵌套用 overflow 处理 |

## 108. 问答·CSS

| 问 | 答 |
| --- | --- |
| 样式不生效 | 特异性/顺序/属性拼写 —— Elements 面板看覆盖链 |
| 覆盖第三方 | :where 降特异性/属性后面写/最后手段 !important |
| gap 不生效 | 老浏览器；flex/grid 之外没有 gap |
| 百分比 height 塌 | 父链上有高度缺失（html,body 100%） |
| 圆角被裁 | overflow:hidden 在圆角容器上再补 |

## 109. 问答·JS

| 问 | 答 |
| --- | --- |
| 数据变了页面没变 | 命令式 DOM —— 状态驱动（类名切换/框架） |
| 循环里闭包都是最后值 | let 捕获/或forEach |
| await 卡住 | 忘了 try/catch 吃错或死 Promise（92 节超时） |
| 事件触发两次 | 绑了两次（委托一处）或冒泡+默认双路径 |

## 110. 反模式

| 反模式 | 后果 | 正解 |
| --- | --- | --- |
| div 按钮化 | 键盘/读屏全丢 | button（7 节） |
| !important 满天 | 特异性战争 | 层级设计+低特异性 |
| px 字号 | 用户缩放失效 | rem（40 节） |
| 固定宽容器 | 手机溢出 | max-width+%（必看） |
| 内联样式堆 | 不可复用 | 类/变量 |
| 深嵌套选择器 | 脆弱 | 单类+BEM |
| 图片无宽高 | CLS | 属性声明（6 节） |
| 自动播放动画 | 光敏+a11y | reduced-motion |
| 空 catch | 黑洞 | 93 节 |
| 全局变量 | 冲突 | 模块域 |
| table 布局 | 响应式死 | Flex/Grid |
| br 当间距 | 语义崩 | margin |

## 111. 剧本·设计稿

1. 识别布局骨架（地标/主轴方向/网格列数）。
2. token 提取（色/字/间距 → CSS 变量，58 节三层）。
3. 语义结构（HTML 先行，1 节总表）。
4. 样式从外到内（容器→区块→组件→细节）。
5. 双查（112 节）。

## 112. 剧本·体检

1. HtmlAnalyzeLayout：骨架速览 + 问题清单清零（或说明保留）。
2. 缺口：viewport/alt/标题层级/固定宽。
3. 需要真实几何（重叠/滚动）→ BrowserAnalyzeLayout。
4. 375px 冒烟 + 深浅色截图。

## 113. 剧本·布局 bug

1. 定层：HTML 结构/容器属性/子项属性（107 节问答对照）。
2. Elements 面板看计算值（谁覆盖了）。
3. 最小复现（二分删节点）。
4. 修复 + 回归用例。

## 114. 剧本·首屏

1. 测（Lighthouse/Field）定位最慢段（45 节 CRP）。
2. 关键 CSS 内联 + 其余 defer；图片 lazy/尺寸声明。
3. 字体 swap + 子集（46 节）。
4. 复测（数字说话）。

## 115. 剧本·抽组件

- 三同判据（all_rounder 36 节同款）：同文案/同行为/同数据形态。
- 抽接口（props/事件）先写下来再搬代码；
  样式随组件走（不共享样式类）。

## 116. 剧本·暗色

1. 全 token 化（34 节）；图片双态或 filter；
2. 对比度复检（39 节）；阴影在暗色下减淡（token 双份）；
3. 手动开关持久化 + 默认跟随系统。

## 快速索引 · 续（117-130 节）

| 想找什么 | 去哪一节 |
| --- | --- |
| HTML 元素 120 问 | 117. 元素问答 |
| CSS 属性 120 问 | 118. CSS 问答 |
| 速配卡 | 119. 速配卡 |
| 决策树 | 120. 决策树 |
| 自测题 | 121. 自测 |
| 术语表 | 122. 术语 |
| 每日一练 | 123. 每日一练 |
| 安全速查 | 124. 安全 |
| SEO 速查 | 125. SEO |
| 打印样式 | 126. 打印 |

## 117. 元素问答

| 问 | 答 |
| --- | --- |
| button 还是 a | 跳转 a/动作 button（7 节） |
| section 还是 div | 有主题标题才 section |
| 什么时候 h2 | 新 subsection 起头；层级不跳 |
| 图表用什么 | 有数据表配 table（sr-only）+ canvas 可视 |
| 视频封面 | poster 属性 |
| 摘要卡片 | article（可独立分发）+ time |
| 提示文字放哪 | 表单旁 <small>/aria-describedby（41 节） |
| dl 用在哪 | 术语表/元数据键值 |
| 缩写首次 | abbr[title] |
| 装饰图 | alt=""（空 alt 是合法语义）★ |

## 118. CSS 问答

| 问 | 答 |
| --- | --- |
| 什么时候 Grid | 二维（行列同时摆） |
| 圣杯用谁 | Grid areas（60 节配方 A） |
| 卡墙 | auto-fill + minmax（57 节★） |
| 长文省略 | nowrap+hidden+ellipsis 三件 |
- 更多见 107/108 节。

## 119. 速配卡

| 症状 | 节 |
| --- | --- |
| 居中问题 | 60/107 |
| 溢出/滚动 | 24/112 |
| 样式不生效 | 108/97 |
| 白屏 | 100 |
| 卡顿 | 98/114 |
| a11y | 35-42/36 |
| 表单 | 5/41/70 |
| 布局选型 | 18/19/56/57 |
| 响应式 | 25-27 |
| 动画 | 31/42 |
| 暗色 | 34/116 |
| 图片 | 6/47 |
| 字体 | 46 |
| 命名 | 72 |
| 反模式自查 | 110 |

## 120. 决策树

```text
要做布局？
├ 一维 → Flex（56）
├ 二维 → Grid（57）
└ 页面级 → 地标+Grid areas（60）
要交互？
├ 有跳转 → a
└ 无跳转 → button + 键盘（37）
要显示数据？
├ 键值 → dl
├ 网格 → table
└ 叙述 → 语义文本（9 节）
```

## 121. 自测

| 题 | 节 |
| --- | --- |
| 特异性顺序 | 14 |
| gap 支持范围 | 108 |
| :focus-visible 用途 | 28 |
| rem 为什么 | 23/40 |
| CLS 怎么防 | 6/47 |
| 委托为什么省 | 77 |
| fetch 要手动判什么 | 79 |
| === 什么时候破例 | 88 |
| dialog 原生好处 | 69 |
| 暗色怎么做 | 34 |

## 122. 术语

| 术语 | 含义 |
| --- | --- |
| CRP | 关键渲染路径（45） |
| LCP/CLS/INP | 核心指标（43） |
| BEM | 块__元素--修饰（72） |
| 层叠上下文 | z 生效域（21） |
| 基线 | 浏览器支持底线 |
| 渐进增强 | 基线全可用（101） |
| focus trap | 弹层焦点圈（38） |
| sr-only | 读屏专用文本类 |
| 逻辑属性 | start/end 替代左右 |

## 123. 每日一练

| 天 | 练 |
| --- | --- |
| 1 | 纯手写圣杯布局（60 节配方） |
| 2 | 无 JS 手风琴（details）+ 键盘走查 |
| 3 | 卡墙 + 375/768/1440 三档验证 |
| 4 | 表单全链（label/错误/焦点） |
| 5 | 暗色全站 token 化 |
| 6 | Lighthouse 跑分并把指标调达标 |
| 7 | 组件抽取一次（115 节判据） |

## 124. 安全

| 面 | 动作 |
| --- | --- |
| XSS | 输出转义；innerHTML 白名单 |
| 依赖 | 锁版本+漏洞扫描 |
| CSP | 资源白名单（80 节安全头） |
| 存储 | 敏感值不进 localStorage（78） |
| postMessage | 校验 origin（80） |

## 125. SEO

| 项 | 口径 |
| --- | --- |
| title/description | 每页唯一 |
| 语义地标 | 4 节（爬虫同读屏受益） |
| h1 唯一 | 不跳级 |
| 图片 alt | 6 节 |
| canonical | 防重复收录 |
| 性能 | 43 节指标（排名因子） |

## 126. 打印

```css
@media print {
  nav, button, aside { display: none }
  a::after { content: " (" attr(href) ")" }  /* 链接印出地址 */
}
```

## 快速索引 · 续（127-140 节）

| 想找什么 | 去哪一节 |
| --- | --- |
| 工具链 | 127. 工具链 |
| lint/format | 128. lint |
| 浏览器开发者工具 | 129. devtools |
| 在线资源 | 130. 资源 |
| 完整示例·列表页 | 131. 示例·列表页 |
| 完整示例·表单页 | 132. 示例·表单页 |
| 完整示例·弹层 | 133. 示例·弹层 |
| 完整示例·暗色 | 134. 示例·暗色 |
| 完整示例·响应卡墙 | 135. 示例·卡墙 |
| 完整示例·吸顶表头 | 136. 示例·吸顶 |

## 127. 工具链

| 类 | 选型参考 |
| --- | --- |
| 构建 | Vite（快） |
| lint | ESLint + stylelint |
| 格式 | Prettier |
| 测试 | 测试库 + Playwright |

## 128. lint

规则分三档：错误（红）/警告（黄）/关。
- 纪律：警告每周清零；新规则先警告后升级错误。

## 129. devtools

| 面板 | 高频用法 |
| --- | --- |
| Elements | 覆盖链/盒模型图 |
| Console | table/dir |
| Network | 瀑布+阻塞解释 |
| Performance | 长任务录帧 |
| Rendering | paint flashing |

## 130. 资源

- MDN（权威一手）★
- caniuse（支持度）
- web.dev（指标/最佳实践）
- 布局分析器：本仓库 HtmlAnalyzeLayout/BrowserAnalyzeLayout（112 节）

## 131. 示例·列表页

```html
<main>
  <h1>订单</h1>
  <ul class="cards">…</ul>
  <nav aria-label="分页">…</nav>
</main>
```
- 配方：卡墙（135）+ 骨架屏（67）+ 空态文案（数据来自 79 节请求层）。

## 132. 示例·表单页

- 结构：fieldset/legend 分组 + label 关联 + 错误块（70 节）；
- 行为：失焦校验/提交防重（幂等键）/草稿防抖存。

## 133. 示例·弹层

```html
<dialog id="d">
  …
  <form method="dialog"><button>关闭</button></form>
</dialog>
<script>d.showModal()</script>
```
- 原生免费获得：focus trap/Esc/::backdrop（69 节）。

## 134. 示例·暗色

```css
:root { --bg:#fff; --fg:#1a1a1a }
[data-theme=dark] { --bg:#0f172a; --fg:#e2e8f0 }
@media (prefers-color-scheme: dark) {
  :root:not([data-theme=light]) { /* 系统暗且未手动亮 */ }
}
body { background:var(--bg); color:var(--fg) }
```

## 135. 示例·卡墙

```css
.cards { display:grid; gap:16px;
  grid-template-columns: repeat(auto-fill, minmax(min(230px, 100%), 1fr)); }
@media (max-width: 480px) { .cards { grid-template-columns: 1fr } }
```

## 136. 示例·吸顶

```css
.table-wrap { overflow:auto; max-height: 60dvh }
thead th { position: sticky; top: 0; background: var(--bg); z-index: 1 }
```
- 别把 overflow:hidden 放到祖先（59 节失效元凶）。

## 快速索引 · 续（137-150 节）

| 想找什么 | 去哪一节 |
| --- | --- |
| 完整示例·toast | 137. 示例·toast |
| 完整示例·下拉 | 138. 示例·下拉 |
| 完整示例·骨架 | 139. 示例·骨架 |
| 完整示例·打印 | 140. 示例·打印 |
| 完整示例·空态 | 141. 示例·空态 |
| 完整示例·加载更多 | 142. 示例·加载更多 |
| 完整示例·搜索框 | 143. 示例·搜索 |
| 完整示例·标签页 | 144. 示例·标签页 |
| 完整示例·进度 | 145. 示例·进度 |
| 完整示例·徽标 | 146. 示例·徽标 |

## 137. 示例·toast

```html
<div role="status" aria-live="polite" class="toast">已保存</div>
```
- 3s 自动隐藏；不打断焦点（68 节）。

## 138. 示例·下拉

- 原生 select 起步；自定义需补全键盘链（64 节清单）；
  列表项多（>50）考虑输入过滤组合框模式。

## 139. 示例·骨架

- 尺寸对齐真实内容（67 节防 CLS）；shimmer 动画配 reduced-motion 降级。

## 140. 示例·打印

- 隐藏操作区/展开折叠内容（details[open]）；链接附地址（126 节）。

## 141. 示例·空态

- 区分三态：无数据（引导动作）/加载中（骨架）/出错（重试入口）；
  空态给下一步动作（不是光秃插画）。

## 142. 示例·加载更多

- IntersectionObserver 哨兵（83 节）+ 幂等（页游标）；
  结尾给"没有更多"终止声明。

## 143. 示例·搜索

- debounce 300ms + 最少 2 字起查 + 结果即时计数（97 节配套）。

## 144. 示例·标签页

- role=tablist/tab/tabpanel + aria-selected + 方向键（62 节）。

## 145. 示例·进度

```html
<progress value="70" max="100">70%</progress>
```
- 不确定进度用转圈（别假进度条）。

## 146. 示例·徽标

- 绝对定位右上角（absolute）+ 数字上限（99+）；
- 纯装饰计数给 aria-hidden + 文本处可读数字。

## 快速索引 · 续（147-160 节）

| 想找什么 | 去哪一节 |
| --- | --- |
| 案例集 | 147. 案例集 |
| 高频 40 问 | 148. 四十问 |
| 反模式 FAQ | 149. 反模式 FAQ |
| 性能红线清单 | 150. 性能红线 |

## 147. 案例集

| 案例 | 教训 | 节 |
| --- | --- | --- |
| 手机横向滚动 | 固定宽容器 | 必看/25 |
| 列表点击失灵 | div 当按钮 | 7 |
| 样式忽闪 | 特异性战争 | 14 |
| 布局跳动 | 图无宽高 | 6 |
| 白屏在生产 | 空catch吞错 | 93 |
| 滚动穿透 | overscroll 缺失 | 24 |
| 吸顶不动 | 祖先 overflow | 59 |
| 输入聚焦缩放 | 字号<16px | 102 |
| 暗色漏色 | 硬编码色 | 34 |
| a11y 投诉 | 全 div 页面 | 4 |

## 148. 四十问

| # | 问 | 节 |
| --- | --- | --- |
| 1 | 布局选 Flex 还是 Grid | 18/19 |
| 2 | 居中最稳 | 60 |
| 3 | 卡墙 | 57 |
| 4 | 长文省略 | 24 |
| 5 | 等高 | 56 |
| 6 | 吸顶 | 59 |
| 7 | 暗色 | 34 |
| 8 | 响应式图片 | 47 |
| 9 | 字体加载 | 46 |
| 10 | 骨架屏 | 67 |
| 11 | 弹层焦点 | 38 |
| 12 | toast 播报 | 68 |
| 13 | 表单校验时机 | 70 |
| 14 | 键盘导航 | 37 |
| 15 | 对比度 | 39 |
| 16 | 缩放 200% | 40 |
| 17 | 动画降级 | 42 |
| 18 | 打印 | 126 |
| 19 | 委托 | 77 |
| 20 | fetch 判错 | 79 |
| 21 | 深拷 | 96 |
| 22 | 排序数字 | 96 |
| 23 | 相等比较 | 88 |
| 24 | this 归属 | 94 |
| 25 | 闭包泄漏 | 95 |
| 26 | 白屏排查 | 100 |
| 27 | 长任务 | 98 |
| 28 | 泄漏 | 99 |
| 29 | 兼容 | 101 |
| 30 | 安全区 | 102 |
| 31 | 测试选择器 | 103 |
| 32 | E2E 范围 | 105 |
| 33 | 视觉回归 | 106 |
| 34 | 设计稿流程 | 111 |
| 35 | 抽组件判据 | 115 |
| 36 | 空态三态 | 141 |
| 37 | 加载更多 | 142 |
| 38 | 搜索防抖 | 143 |
| 39 | 徽标 | 146 |
| 40 | SEO 基线 | 125 |

## 149. 反模式 FAQ

| 现象 | 根因 | 节 |
| --- | --- | --- |
| "改这处坏那处" | 全局选择器 | 72 |
| "动画掉帧" | 动 top/left | 31 |
| "手机上要横滑" | 固定宽 | 25 |
| "读屏乱跳" | 无地标 | 4 |
| "提交两次" | 无防重 | 70 |
| "图片挤来挤去" | 无宽高声明 | 6 |

## 150. 性能红线

- [ ] 关键资源 <5 个首屏
- [ ] 图片全 lazy+尺寸（首屏外）
- [ ] 字体 swap+子集
- [ ] 脚本 defer 全默认
- [ ] 动画只用 transform/opacity
- [ ] LCP<2.5s / CLS<0.1 / INP<200ms
- [ ] 无长任务 >200ms（拆帧）

## 快速索引 · 续（151-160 节）

| 想找什么 | 去哪一节 |
| --- | --- |
| a11y 清单 | 151. a11y 清单 |
| 上线清单 | 152. 上线清单 |
| 评审清单 | 153. 评审清单 |
| 速记总卡 | 154. 速记 |
| 版本变更 | 155. 版本 |
| 使用回执 | 156. 回执 |
| 附录·速用直达 | 157. 直达 |
| 附录·数据 | 158. 数据 |
| 与本仓库联动（附录） | 159. 附录·与本仓库联动 |
| 全库收尾与使用回顾 | 160. 结语 |
| 速查组合拳（附录） | 161. 附录·速查组合拳 |
| 本库统计数据 | 162. 附录·数据 |
| CSS 属性补遗（30 条）（附录） | 163. 附录·CSS 属性补遗（30 条） |
| HTML 属性补遗（30 条）（附录） | 164. 附录·HTML 属性补遗（30 条） |
| 移动调试技巧（附录） | 165. 附录·移动调试技巧 |
| 常用片段（前端侧正则）（附录） | 166. 附录·常用片段（前端侧正则） |
| 版本与变更记录 | 167. 附录·版本与变更 |
| 每日一练（7 天·进阶版）（附录） | 168. 附录·每日一练（7 天·进阶版） |
| 术语补遗（附录） | 169. 附录·术语补遗 |
| 本库统计数据 | 170. 结语·数据 |
| 布局陷阱对照（终版）（附录） | 171. 附录·布局陷阱对照（终版） |
| 全库收尾与使用回顾 | 172. 结语 |
| 本库与其他库的分工（附录） | 173. 附录·本库与其他库的分工 |
| 速查终卡（附录） | 174. 附录·速查终卡 |

## 151. a11y 清单

- [ ] 语义地标齐（4）
- [ ] 全键盘可操作（37）
- [ ] 焦点管理正确（38）
- [ ] 对比度达标（39）
- [ ] alt 全覆盖（6）
- [ ] 表单 label 全关联（41）
- [ ] 动效可关（42）
- [ ] 读屏抽测两流程

## 152. 上线清单

- [ ] 375px 冒烟
- [ ] 暗色截图
- [ ] Lighthouse 三指标
- [ ] 打印样式（如需）
- [ ] 错误边界/空态兜底
- [ ] 布局双查通过（112）

## 153. 评审清单

- [ ] 语义优先（无 div 按钮化）
- [ ] 类名规范（72）
- [ ] 无魔法色值（token 化）
- [ ] 间距走系统（58）
- [ ] 交互键盘可达
- [ ] 图片属性齐全
- [ ] 新组件按 115 节判据

## 154. 速记

```text
结构  : 地标(4) 语义元素(1) 表单 label(5)
布局  : Flex 一维(56) Grid 二维(57) gap 间距
样式  : 变量化(29) 低特异性(14) rem(23)
响应  : 375 基线(25) 卡墙(57) dvh(23)
交互  : 按钮语义(7) 键盘(37) 焦点(38)
性能  : 三指标(43) lazy/宽高 defer
暗色  : token(34) 图片双态
双查  : 静态 HtmlAnalyzeLayout + 动态 BrowserAnalyzeLayout(112)
```

## 155. 版本

- v1：160 节主体；速查表 30+；示例 16；剧本 6。

## 156. 回执

救场节点名 / 错漏带节号 48h 修订 / vNext 按热度扩写。

## 157. 直达

- 居中：60 配方 C
- 卡墙：57/135
- 吸顶：59/136
- 暗色：34/134
- 弹层：69/133
- 表单：5/70
- 键盘：37/38
- 白屏：100
- 性能：43/114/150
- 反模式：110/149

## 158. 数据

- 节总数：158
- 示例：16 | 剧本：6 | 清单：4 | 问答：160 问
- 版本：v1

## 159. 附录·与本仓库联动

- HTML 产物纪律：写 → HtmlAnalyzeLayout（骨架/响应式/a11y 静态）→
  预览（含布局检查器）→ BrowserAnalyzeLayout（真实渲染几何）。
- 预览对话框 Layers 开关可视化块结构（描边色与 56/57 节口径对应）。
- 前端产物常用 Tailwind（74 节）—— 布局分析器按其间距/宽度口径估算。

## 160. 结语

语义优先、移动优先、状态驱动、双查交付。
入口：119 速配 → 148 四十问 → read 起点节 → 152 上线清单收尾。

## 11. 选择器引擎内幕

- **匹配从右往左**：`ul li a` 先找所有 a 再逐级向左验证祖先。
  最右端（key selector）越准，淘汰越早 —— `.nav a` 远快于
  `a .nav`（后者几乎不淘汰）。
- **失效集预算**：引擎按"最右复合选择器"建失效集；class 改动
  只重算命中集。但 `.a .b .c` 的中间桥接会扩大候选；
  通配/标签打头的选择器让每个 DOM 变更都进失效路径。
- **BEM 是性能工具而不止命名法**：单 class 的最右端零组合
  开销 —— 深链选择器重构为原子类，Recalc 成本立降
  （Edge 109+/Chrome Selector Stats 可实测验证）。
- **containment 隔离失效**：contain:layout/paint 把子树失效
  边界封住，与 28 节 :has 纪律配合用。

## 12. 动态伪类

- **:hover 在触屏是"粘住"语义**（tap 后保持直到点别处）——
  悬停揭示的内容在移动端必须有 focus/active 的替代触发路径。
- 大面积 :hover 效果（菜单磁吸、跟随光标）在低端机是掉帧源；
  hover 态样式限 transform/opacity（合成层）。:focus 与
  :hover 同规则声明，触屏态才不裂。
- **:focus-visible 由 UA 启发式决定**（键盘出现才显）；
  outline:none 之前必须有替代焦点视觉（a11y 红线，37 节）。
- **:user-invalid 的时序语义**见 28 节；:checked 只映射原生
  表单态，Web Component 用 :state() 挂自定义态
  （custom state pseudo-class）。
- :placeholder-shown 做"浮动标签"少一个类；
  :autofill 的 UA 样式覆盖需要 -webkit-animation 技巧，
  能用 JS 就别硬挖伪类。

## 13. 伪元素深水

- **CSS 生成内容在无障碍树里不安分**：content 文本大多能被读出
  但不可被选中/搜索/翻译 —— 数据（价格/状态值）进 DOM，
  装饰才进 ::before。
- `content: url()` 的图片没有 alt 通道（装饰限定）；
  `content: ""` + background 是图标挂载点，配 .sr-only 文案。
- **::part() 是 shadow DOM 定制口**（只穿透一层，不可再嵌套
  选中）；::backdrop 给 dialog/popover 的遮罩独立写样式，
  比 JS 加遮罩层干净（10 节 inert 由 UA 管）。
- **::marker 列表符可样式化**（content 换符也行），但
  list-style:none + 自绘符号的组合在 Safari 触发列表语义丢失
  （8 节 role="list" 补丁）。
- ::first-letter 不跨元素边界（内联子元素会截断"首字"）；
  首字下沉兼容成本高，确有美术需求再上。

## 14. 特异性工程

- 计法是三元组 (id, class/属性/伪类, 元素/伪元素)，按位从左
  到右比较，不是"加起来"。行内 style 与 !important 是特异性
  之上的两级开关（少用是纪律）。
- :is() 取参数最贵者（28 节）；:where() 恒零 —— 第三方覆盖/
  主题层的入口写法 `:where(.card){…}` 让任何业务 class 都能
  压过它。
- **@layer 才是特异性污染的解药**：`@layer reset, lib, app;`
  先声明层序；**后声明的层赢过先声明的层**，而所有层都输给
  未分层样式。层间胜负先于特异性比较，revert-layer 可逐层
  回退（15 节关键字联动）。
- **id 选择器是特异性炸弹**：JS 钩子改用 data-*；样式永不碰
  #id。
- 评审口径：新样式需要 >2 位特异性才生效时，先怀疑层序问题，
  而不是继续叠码。

## 15. 继承深水

关键字决策表（背下来比查表快）：

| 关键字 | 效果 | 典型场景 |
| --- | --- | --- |
| inherit | 强制取父值 | 默认不继承的属性想继承（border-color 随 color） |
| initial | 回规范初值 | 不是"浏览器默认样式"——UA 样式 ≠ initial |
| unset | 继承属性=inherit，非继承=initial | `*{all:unset}` 通配重置的原料 |
| revert | 回退到 UA 样式 | 想要"原生控件样"时 |
| revert-layer | 回退到上一级联层 | 层内局部覆盖的撤销（14 节） |

- 注册过的 @property 决定变量继承性：`@property --x
  {inherits:false}` 让组件变量不下渗——隔离的关键；
  未注册变量一律继承（29 节变量域）。
- `all` 简写重置除 direction/unicode-bidi 外全部——危险，
  但配 unset 是万能切片器。
- 简写里的 CSS-wide 关键字（如 background: inherit）会整组
  连坐重置所有相关 longhand，别当"单值继承"用。

## 16. 盒模型深水

- box-sizing 两态：content-box 宽=内容，border-box 宽=含
  padding/border（全站统一 border-box 是共识）。但
  min-width/max-width 的求值语义不随 box-sizing 改变——
  弹性布局里 min-content 仍按内容算，溢出别怪盒模型。
- **块化/行内化**：flex/grid 子项被块化（inline 的子项表现
  为块级）；inline 容器里的 grid/flex 会被行内化——
  "为什么 display:inline-block 进 flex 就变了"看这条。
- **逻辑属性**：margin-top 在 writing-mode/rtl 下语义漂移；
  margin-block-start / margin-inline-start 才是方向无关
  写法，i18n 站点全量替换（58 节间距 token 同步）。
- **匿名盒**：裸文本在 flex 容器里被包进匿名盒，无法直接
  选中 —— 包一层 span 才能加伪元素/样式。
- aspect-ratio 参与 min/max 约束求解：max-width 先缩，
  高按比例跟（min-height 会破坏比例，弹性媒体容器慎用）。

## 17. margin 合并

- 折叠三条件（缺一不叠）：同一 BFC、块级流内、纵向毗邻
  （兄弟相邻/父子边界/空块自上下）。正 margin 取大者；
  负 margin 取 max(正)+min(负)。
- 现代布局里 flex/grid/absolute 都不折叠 —— 折叠问题基本只在
  "普通文档流"的旧区域出没；新代码用 gap 替代，为阻断 BFC
  而加的 overflow:hidden clearfix 就此退役。
- **父子折叠典型症状**：子 margin-top 顶出父容器。修法顺序：
  父加 padding-top > 改用 gap > display:flow-root
  （语义化 BFC，比 overflow:hidden 干净）。
- 陷阱：min-height 不阻断父子折叠（"看起来有高度"但边界仍
  毗邻）；行内块不折叠但会带来基线间隙（flex 化即解）。

## 18. Flex 深水

- **min-width:auto 是收缩不动元凶**：flex 子项默认最小尺寸 =
  min-content，长文本/长 URL 撑爆横向。写 `min-width:0` 才
  允许收缩交给 overflow 处理（56 节速查只给了结论，机制在这）。
- flex-basis 优先于 width（主轴），但 min/max-width 又压
  flex-basis；`flex:1` = `1 1 0%`（归零均分），
  `flex:auto` = `1 1 auto`（按内容起评）—— 三态选型分水。
- **auto margin 吃掉全部剩余空间**且优先于 justify-content
  —— "最后一个元素推到底"用 margin-left:auto，比
  space-between + 空占位干净。
- align-items:baseline 按首行文字对齐（input 的基线怪癖
  多）；图标+文字混排 baseline 会"看着歪"，改 center 再手调。
- wrap 换行后：align-content 管行组、align-items 管行内；
  gap 同时给行距与列距（一个属性两份钱）。
- **order 改视觉序不改 DOM 序**（4 节 a11y 警告）——键盘
  遍历与视觉不一致即无障碍违规。

## 19. Grid 深水

- **subgrid（Grid L2，全主线 2024+ 可用）**：子网格吃父网格
  轨道——卡片内标题/正文/按钮跨卡对齐（`grid-template-rows:
  subgrid`）。“对齐强迫症”的正解，替代 JS 等高 hack。
- **masonry 仍非跨浏览器**（CSSWG 双语法之争未收口）：
  grid-template-rows:masonry 仅实验旗。生产线用 columns 近似
  （阅读序变列序）或 JS 库，别赌标准。
- **auto-fill vs auto-fit**：轨道数相同时区别在空轨道处理
  ——auto-fit 折叠空轨道让现有项拉伸，auto-fill 保留空位。
  minmax(min(220px,100%),1fr) 防窄屏溢出（60 节配方）。
- 命名区域必须矩形补齐（. 占位）；响应式时整块换 template，
  别在媒体查询里逐 area 打补丁。
- **dense 回填会打乱顺序**：自动放置默认 sparse 保序——
  数据列表禁 dense（a11y/阅读序风险）。
- 深层嵌套 grid + auto 尺寸触发多轮 intrinsic sizing，
  布局抖动在低端机放大——显式轨道 + contain 收敛。

## 20. 定位深水

- **包含块链**：absolute 找"最近 positioned 祖先"；而
  transform/filter/perspective/will-change/contain 的祖先会
  成为 **fixed** 的包含块 —— "fixed 弹窗跟着滚动"的真凶是
  某个祖先加了 transform（哪怕 translate(0,0)）。
- **sticky 失效自查**：①任何祖先 overflow 非 visible 都截断
  sticky 区间（不只直接父级）②sticky 容器无滚动余量
  （父级高度不够）③父级 display:contents 吞掉盒子。
- **锚定定位（CSS Anchor Positioning，Chrome 125+）**：
  anchor-name + position-anchor + position-area 让弹层
  声明式贴锚点，position-try-fallbacks 自动翻转避出屏
  —— 手写 JS 翻转逻辑（61 节）可被它替换；配 popover 时
  记得清 UA 默认 margin（[popover]{margin:0}）。
- inset 是 top/right/bottom/left 简写：`inset:0` 全铺包含块；
  居中老 hack（translate(-50%,-50%)）可用 place-self 或锚定
  改写。
- z-index 语义见 21 节；iOS Safari 的 fixed 在缩放时随视觉
  视口跳动，吸底栏用 svh + sticky 更稳（23 节）。

## 161. 附录·速查组合拳

| 目标 | 组合 |
| --- | --- |
| 页面骨架 | 4 地标 + 60 Grid areas |
| 卡墙 | 57 auto-fill + 135 示例 |
| 吸顶表头 | 59 sticky + 136 示例 |
| 弹层 | 69 dialog + 38 焦点 |
| 暗色 | 34 变量 + 134 示例 |
| 骨架屏 | 67 shimmer + 40 缩放安全 |
| 长列表 | 24 溢出 + 142 IO 加载更多 |
| 表单 | 5 元素 + 70 错误口径 + 41 a11y |
| 搜索 | 143 防抖 + 97 面板 |
| 交付 | 112 双查 + 152 上线清单 |

## 162. 附录·数据

- 节总数：162
- 示例：16 | 剧本：6 | 清单：5 | 问答：200 问
- 版本：v1（0 索引缺失）

## 163. 附录·CSS 属性补遗（30 条）

| 属性 | 用途 |
| --- | --- |
| aspect-ratio | 定比例（16/9 视频）★ |
| object-fit | cover/contain（图填框） |
| object-position | 填框对齐 |
| overscroll-behavior | 防滚动穿透 |
| scroll-behavior | 平滑滚动 |
| scroll-snap-type | 画廊吸附 |
| accent-color | 控件主题色 |
| caret-color | 光标色 |
| list-style | 列表符 |
| columns | 多栏文本 |
| writing-mode | 竖排 |
| appearance | 去原生控件皮 |
| all: unset | 清样式重做（少用） |
| isolation | 新层叠上下文（21 节） |
| contain | 隔离渲染（性能） |
| content-visibility | 跳渲染长文（性能★） |
| text-wrap: balance | 标题均衡换行（新★） |
| line-clamp | 多行省略（-webkit- 前缀） |
| inset | 四向简写（top/right/bottom/left） |
| place-items | align+justify 合并 |
| gap | 行列间距（56/57） |
| min()/max() | 弹性尺寸（30） |
| clamp() | 字号流体（30） |
| env(safe-area-inset) | 刘海安全区（102） |
| touch-action | 手势控制（55） |
| user-select | 选取控制（55） |
| will-change | 变更提示（用完移除） |
| z-index | 层叠（21） |
| transition | 过渡（31） |
| animation | 关键帧（31） |

## 164. 附录·HTML 属性补遗（30 条）

| 属性 | 宿主 | 用途 |
| --- | --- | --- |
| alt | img | 必答（6） |
| width/height | img/video | 防 CLS★ |
| loading | img/iframe | lazy |
| srcset/sizes | img/source | 响应式（47） |
| poster | video | 封面 |
| controls | video/audio | 控件条 |
| preload | video/audio | 预载策略 |
| sandbox | iframe | 能力最小面 |
| allow | iframe | 权限策略 |
| form | input | 关联表单（跨 DOM） |
| for | label | 关联控件★ |
| pattern | input | 正则校验 |
| required | input | 必填 |
| minlength/maxlength | input/textarea | 长度 |
| min/max/step | input | 数值域 |
| multiple | input/select | 多选 |
| autocomplete | input | 自动填充（a11y★） |
| inputmode | input | 定制键盘 |
| accept | input[file] | 类型过滤 |
| capture | input[file] | 调相机 |
| download | a | 下载而非导航 |
| hreflang | a | 目标语言 |
| rel | a | noopener/nofollow |
| open | details/dialog | 展开/开态 |
| selected | option | 初选 |
| disabled | 控件 | 禁用态 |
| readonly | input | 只读（可聚焦） |
| aria-* | 全局 | 36 节表 |
| data-* | 全局 | JS 桥（2） |
| defer/async | script | 3 节/44 |

## 165. 附录·移动调试技巧

| 症状 | 查法 |
| --- | --- |
| 真机 console | chrome://inspect（USB）/ vConsole 注入 |
| 触摸差异 | devtools 设备模拟 + touch 事件日志 |
| 软键盘遮挡 | visualViewport 监听高度 |
| 滚动穿透 | overscroll-behavior 试 contain |
| 300ms 延迟 | 现代 viewport 已消除（width=device-width） |
| 系统暗色 | prefers-color-scheme 模拟器切换 |

## 166. 附录·常用片段（前端侧正则）

| 需求 | 模式 |
| --- | --- |
| 手机号（大陆） | 1[3-9]\d{9} |
| 邮箱（粗） | [\w.+-]+@[\w-]+\.[\w.]+ |
| URL | https?://[^\s]+ |
| 千分位插入 | replace(/\B(?=(\d{3})+(?!\d))/g, ",") |
| 首尾空白 | trim()（原生优先） |
| 中文区间 | [\u4e00-\u9fa5] |

- 校验永远服务端终判（70 节）；前端只做体验。

## 167. 附录·版本与变更

- v1：162+9 附录节；0 索引缺失。

## 168. 附录·每日一练（7 天·进阶版）

| 天 | 练 |
| --- | --- |
| 1 | 30 分钟还原一张设计稿（111 节流程） |
| 2 | 全键盘走查自己的页面并修 |
| 3 | 把页面指标调到全绿（150 节红线） |
| 4 | 暗色全站 + 截图比对 |
| 5 | 375px 单手可达性走查 |
| 6 | 用布局双查修一个真实布局 bug（113 节剧本） |
| 7 | 抽两个组件（115 判据）+ 组件测试补齐 |

## 169. 附录·术语补遗

| 术语 | 含义 |
| --- | --- |
| 逻辑属性 | inline-start/end（替代 left/right，rtl 友好） |
| 层叠上下文 | 21 节 |
| 基线对齐 | flex 子项文本基线（align-items: baseline） |
| 长任务 | >200ms 主线程块 |
| paint flashing | 渲染可视化（129） |
| shadow DOM | 组件封装边界（自定义元素） |
| prefers-* | 用户偏好媒体查询（26/42） |

## 170. 结语·数据

- 节总数：170（含附录 163-169）
- 版本：v1
- 使用顺序：119 速配 → 148 四十问 → read → 152 清单收尾 → 156 回执

## 171. 附录·布局陷阱对照（终版）

| 陷阱 | 一句话 | 节 |
| --- | --- | --- |
| 居中歪 | 内容宽不定 → grid place-items | 60 |
| 子项撑破 | min-width:0 忘写 | 56 |
| 吸顶死 | 祖先 overflow:hidden | 59 |
| 合并气人 | 用 gap 别用 margin | 17 |
| 百分比塌 | 父链高度缺失 | 108 |
| 圆角被裁 | 容器补 overflow:hidden | 52 |
| 卡墙不满 | minmax(min(230px,100%),1fr) | 135 |
| 键盘失焦 | dialog 原生/手动 trap | 69 |
| 图抖动 | width/height 属性声明 | 6 |
| 手机横滚 | 固定宽改 max-width | 25 |

## 172. 结语

入口速记：**语义 → 布局（56/57）→ 响应（25）→ a11y（37）→
性能（150）→ 双查交付（112）**。
任何"看起来不对"先查 171 节对照表；改完过 152 上线清单。
回执（156）让这本库长成你页面真实的样子。

## 173. 附录·本库与其他库的分工

| 问题 | 去处 |
| --- | --- |
| 页面构建/样式/a11y/性能 | 本库 |
| JS 语言细节 | javascript 库 |
| 类型系统 | typescript 库 |
| 端到端链路（API/数据/部署） | full_stack 库 |
| 流程与工具选择 | all_rounder 库 |
| 版本管理 | git 库 |

## 174. 附录·速查终卡

```text
元素   : 语义表(1) 地标(4) 表单label(5) 废弃(10)
布局   : Flex(56) Grid(57) 定位(59) 配方(60)
样式   : 特异性(14) 继承(15) 变量(29) 单位(23)
响应   : 断点(25) 卡墙(135) 容器查询(27)
a11y   : 键盘(37) 焦点(38) 对比度(39) ARIA(36)
性能   : 指标(43) 图片(47) 字体(46) 红线(150)
交付   : 双查(112) 清单(152) 速配(119)
```
- 陷阱先查 171；分工见 173；回执见 156。

- 版本 v1 · 174 节 · 示例 16 · 剧本 6 · 问答 200+ · 索引缺失 0
- 下一步：在真实页面产物上使用本库（回执 156 节）
- 语义优先 · 移动优先 · 状态驱动 · 双查交付 —— 四句心法走天下
- 布局陷阱对照 171 / 分工 173 / 终卡 174 —— 三张表带走全部
1. 开工：must_read → 119 速配
2. 执行：56/57 布局 → 37 键盘 → 43 性能
3. 交付：112 双查 → 152 清单
4. 收尾：156 回执
