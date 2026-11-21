# GAME-SPEC — pretext 游乐场插件街机 · 十连游戏统一规范

> 本文档是所有游戏子代理的**唯一执行规范**。产物是 Android 应用「游乐场」的
> HTML 游戏插件（WebView 运行）。你写的每个游戏必须同时满足：真 pretext 驱动、
> 单文件自包含、移动触屏优先、零网络请求。

---

## 0. 你在做什么（大背景）

- 仓库：`/home/z/Android-Guru-Agent`（Android 智能体应用，Kotlin Compose）。
- 游乐场正在插件化：**每个游戏 = 一个自包含 HTML 文件**，放在
  `app/src/main/assets/playground/games/<游戏id>/index.html`，
  同目录放 `game.json` manifest。App 用 WebView 加载运行；同一 HTML 可
  被拷贝出来单独分发、或包进 APK 插件壳（转化路径见 playground/README.md）。
- **pretext**（github.com/chenglou/pretext，MIT）是纯 JS 文本测量排版库：
  不碰 DOM reflow，用 canvas 测出每段文本的真实宽度，纯算术完成折行。
  官网 chenglou.me/pretext 有十个官方 demo。**每个游戏的核心玩法必须真正
  建立在 pretext 测量结果之上**（宽度/行数/行高就是游戏物理），不是贴牌。
- 风格基调：延续官网 PRETEXT 街机的「终端暗色霓虹」气质 —— 深色底、
  等宽字体、绿/琥珀/红终端色、克制的发光。拒绝 Google 恐龙式跑酷形态。

## 1. 硬性契约（违反即返工）

1. **单文件自包含**：`index.html` 一个文件包含全部 CSS/JS；不引用任何外部
   URL（无 CDN、无网络字体、无图片请求）。图标一律用内联 SVG 或字符。
2. **内联 pretext**：把 `/home/z/Android-Guru-Agent/playground/vendor/pretext.js`
   的**全部内容**原样放进 `<script>` 标签内（它是一个 IIFE，暴露
   `window.Pretext`）。保留文件头的 MIT 许可注释。禁止改写其内容。
3. **真 pretext 驱动**：游戏核心机制至少依赖一个 pretext API 的返回值
   （宽度表 / 行数 / 逐行路由 / 收缩宽度）。等宽兜底仅在 pretext 加载失败时
   启用（并如实显示「等宽兜底」标记）。
4. **零网络请求**：`fetch`/`XMLHttpRequest`/外链资源一律禁止。字体用系统栈：
   `--font-mono: ui-monospace, "JetBrains Mono", Menlo, Consolas, monospace;`
   `--font-sans: system-ui, -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif;`
   pretext 测量用的 canvas font 字符串必须与 CSS 实际渲染字体一致
   （例如 CSS 用 `var(--font-mono)` 的等宽栈，测量用 `'bold 22px ui-monospace, Menlo, Consolas, monospace'`；
   混合栈两端保持同序同名）。
5. **中文 UI**：界面文案中文（与 App 游乐场「屏内中文直写」先例一致），
   游戏名可附英文副题。
6. **移动触屏优先**：主交互必须支持 touch（拖动/点按）；桌面端鼠标同步可用
   （pointer events 统一处理）。交互目标 ≥44px。
7. **生命周期**：开始遮罩 → 游戏 → 结束/结算遮罩（重开按钮）。切后台
   （`visibilitychange`）自动暂停。
8. **本地战绩**：最高分存 `localStorage`，键名 `aga.pg.<游戏id>.hi`
   （另有细分布局键 `aga.pg.<游戏id>.stats`）。
9. **性能**：`requestAnimationFrame` 主循环，dt 钳制 ≤50ms；离开遮罩态
   停止实体更新（渲染循环可保留）。避免每帧 allocate 大对象。
10. **可访问性底线**：按钮/可点区域有 `aria-label`；HUD 数值有语义标签；
    `prefers-reduced-motion` 时关闭装饰性动画（保留必要的状态过渡）。
11. **体积**：单游戏文件 ≤ 60KB（含 48.6KB pretext，即自写代码 ≤ ~12KB 压缩前的可读源码）。

## 2. 统一设计语言（终端暗色霓虹）

CSS 变量统一（每个游戏照抄，可微调透明度）：

```css
:root {
  --bg: #0a0f0c;
  --bg-2: #0e1a12;
  --panel: rgba(255,255,255,0.045);
  --panel-border: rgba(255,255,255,0.09);
  --ink: #d7e4d9;
  --dim: #7d8f83;
  --green: #8fd6a0;
  --amber: #e0a54f;
  --red: #d98876;
  --font-mono: ui-monospace, "JetBrains Mono", Menlo, Consolas, monospace;
  --font-sans: system-ui, -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif;
}
```

- 背景：`--bg` 纯色 + 至多一层极淡径向光晕（`--green` 5% 透明度级别），**禁止大渐变**。
- 卡片/面板：`--panel` 半透明 + 1px `--panel-border` + 12-16px 圆角。
- HUD 条：顶部一行等宽字体读数（分数/关卡/生命），格式参考
  `SCORE 0120 · ROUND 3/10 · ♥♥♥`。
- 遮罩：全屏半透明黑（`rgba(4,8,6,0.82)`）+ 居中面板：游戏名大标题（等宽）、
  一段两行内的玩法说明、主按钮（`--green` 边框描线风格，按下发光）。
- pretext 徽章：HUD 右侧小字 `PRETEXT · <用到的 API 名>`（琥珀色），
  这是「测量即玩法」的诚实展示位。
- 触觉感的视觉替代：命中/得分时目标元素的 200ms 发光脉冲（`box-shadow` 过渡）。
- 不用 emoji 当游戏实体（代理对测量坑）；UI 小图标可用内联 SVG。

## 3. pretext API 速查（签名以 vendored 0.0.9 为准）

```js
const P = window.Pretext;
const prepared = P.prepare(text, 'bold 22px ui-monospace, Menlo, Consolas, monospace');
// 不透明句柄；同文本+同字体只 prepare 一次，复用句柄做热路径

P.layout(prepared, maxWidth, lineHeight)
// → { height, lineCount } —— 纯算术，可拖动中每帧调用

const preparedSeg = P.prepareWithSegments(text, font);
// → prepared + { segments: string[], kinds: [...], widths: number[] }

P.layoutWithLines(preparedSeg, maxWidth, lineHeight)
// → { height, lineCount, lines: [{ text, width, start, end }] }

P.measureLineStats(prepared, maxWidth)
// → { lineCount, maxLineWidth } —— 最宽行 = 收缩包裹（shrink-wrap）宽度

P.measureNaturalWidth(prepared)
// → number —— 只按显式换行的最宽行宽（单字符就是它的实测宽度）

P.walkLineRanges(prepared, maxWidth, line => { line.width, line.start, line.end })
// → number(行数)；逐行回调不分配字符串

let cursor = { segmentIndex: 0, graphemeIndex: 0 };
const range = P.layoutNextLineRange(prepared, maxWidth, cursor);
// → { width, start, end } | null —— 每行可用不同宽度（变宽路由）
const line = P.materializeLineRange(preparedSeg, range); // → { text, width, ... }
```

- `font` 字符串 = canvas font 简写，**必须与 CSS 渲染字体同栈同名**；
- `maxWidth` 传内容宽度（CSS px）；
- 单字符宽度：`P.measureNaturalWidth(P.prepare(ch, font))`；
- emoji/CJK 直接支持（grapheme 级测量）；
- 同一 (text, font) 的 prepare 结果可缓存复用；游戏启动时对字符集
  批量 prepare 一次建宽度表（参考官网 GLYPH STORM 的做法）。

## 4. game.json manifest（同目录，与 index.html 并列）

```json
{
  "id": "glyph-rain",
  "name": "字符雨",
  "nameEn": "Glyph Rain",
  "tagline": "实测宽度的字符从天而降，接住它们拼出目标词",
  "version": 1,
  "engine": "pretext@0.0.9",
  "apis": ["prepare", "measureNaturalWidth"],
  "controls": "拖动托盘 / 键盘←→",
  "scoreKey": "aga.pg.glyph-rain.hi"
}
```

字段必填：id（小写-连字符）、name、tagline、version、engine、apis（真实用到的）。
id 必须与本规范第 5 节指定的游戏 id 完全一致。

## 5. 十个游戏逐个规格

> 每个游戏 300-600 行可读源码即可达标；宁可机制扎实、手感顺滑，不要堆功能。
> 分数/难度曲线：入门 30 秒内能得分；失误有明确反馈；递进不陡峭。

### 游戏 1 · glyph-rain 字符雨（Glyph Rain）

- **pretext 核心**：`prepare` + `measureNaturalWidth` —— 每个下落字符的
  物理宽度 = 实测宽度；底部「词盘」的每个卡槽宽度 = 目标字符实测宽度 + 内边距。
- **玩法**：目标词显示在 HUD；字符从顶部按各自宽度占位下落（i 窄 W 宽，
  下落列的排布用真实宽度对齐）；玩家左右拖动词盘接字符；接到目标词缺失的
  字符 → 落入对应卡槽（吸附动画）+10；接错字符 → 弹开 + 扣 combo；
  漏接目标字符 → 该字符加速重生；拼满整词 → 清屏奖励 +50，换下一词，
  下落速度 +8%。三命，漏满 3 个目标字符 = 结束。
- **词库**：8-12 个 4-6 字母英文词 + 3-4 个 2-3 字中文词（「智能体」「终端」），
  展示 CJK 满格宽度。
- **对照彩蛋**：设置里加「等宽近似」开关（所有宽度按 0.6em 假宽），
  开关切换立刻重开一局 —— 等宽思维手感立变笨拙（这是 pretext 的可玩论点）。

### 游戏 2 · bubble-sniper 气泡狙击（Bubble Sniper）

- **pretext 核心**：`prepareWithSegments` + `walkLineRanges` —— 每个气泡
  宽度 = 其文本在该宽度下的最宽行（收缩包裹），高度 = 行数×行高。
- **玩法**：聊天气泡从底部缓慢上浮（每屏同时 5-8 个，文本是 1-4 行的短句，
  混合中英与少量 emoji）；顶部狙击指令轮换：「狙掉 3 行的气泡」「狙掉最宽的
  气泡」「狙掉含 emoji 的气泡」「狙掉只有 1 行的气泡」。点按命中 → 气泡爆裂
  （碎片按真实行宽散开）+ 分数 = 剩余时间加成；点错 → -1 命 + 红闪。
  指令有 10 秒时限，超时换指令。20 条指令一局，三命。
- **气泡生成**：从 20+ 条预置短句池抽取，宽度约束随机 140-260px ——
  行数/宽度全部由 pretext 现场算出（不是写死的形状）。

### 游戏 3 · layout-golf 排版高尔夫（Layout Golf）

- **pretext 核心**：`prepare` + `layout` —— 拖动滑杆实时改变容器宽度，
  pretext 每帧返回行数与高度（展示 600× 热路径速度：拖动零卡顿）。
- **玩法**：一关给出一段文本（3-8 行的混合中英文本）+ 目标行数（如「恰好
  4 行」）；拖动底部宽度滑杆，实时渲染折行结果（pretext 算行数，浏览器
  同步真实换行作对照）；行数命中目标 → 「落杆」结算：本杆分 =
  100 − 调整次数×10 − 超时秒数；越少拖动越高分（高尔夫杆数思维）。
  10 关一局，文本越来越刁（长英文单词 / CJK / emoji / 标点悬挂）。
- **加分关**：同时要求高度落入指定 ±8px 区间（行高×行数，pretext 直出）。
- **必须展示**：拖动时 HUD 实时显示 `width px · N lines · H px`
  （每帧更新，证明 layout 热路径零 reflow）。

### 游戏 4 · width-maze 等宽迷宫（Width Maze）

- **pretext 核心**：`prepare` + `measureNaturalWidth` —— 角色是一个字符，
  它的碰撞盒宽 = 实测宽度、高 = 实测行高。迷宫缝隙宽度以 px 设计，
  只有足够窄的字符才能穿过窄缝。
- **玩法**：底部字符托盘给出 4-6 个候选字符（如 `i`、`l`、`!`、`W`、`m`、
  「你」）；选中一个作为「棋子」拖入迷宫；迷宫是若干竖墙 + 门缝
  （缝宽 12px 到 30px 不等），拖动字符从起点走到终点；字符盒任何边缘
  碰墙 = 推回并闪烁。到终点得分 = 基础 100 + 剩余时间；某些关有多条路径
  （宽门远、窄门近）。5 关一局；每关开始重新测所选字符的宽度并画出
  其真实碰撞框（虚线）。
- **诚实细节**：HUD 显示当前字符实测宽度 `W = 21.3px`；pretext 徽章露出。

### 游戏 5 · line-fill 行宽拼图（Line Fill）

- **pretext 核心**：`prepareWithSegments` —— 词语瓦片的宽度 = 实测词宽；
  行的填充进度 = 已放瓦片累计宽度 / 行宽（全部真实测量）。
- **玩法**：一行目标宽度（如 300px，顶部一条边界线）；下方 8-10 个词语
  瓦片（中英混合，宽窄不一）；按任意顺序点选瓦片依次放入行内（紧密排列，
  间距 0）；放满后剩余空隙 ≤ 6px = 完美（+80），≤ 15px = 良好（+40），
  > 15px 按比例给分；放入会溢出的瓦片 = 该瓦片禁用（灰显）不惩罚，
  但「无瓦可放且空隙 > 15px」时收行结算。3 行一关，5 关一局。
  溢出保护：禁止放入会超界的瓦片（点击抖动提示）。
- **策略深度**：瓦片组合的选择近似子集和 —— 贪心 vs 最优可感。

### 游戏 6 · line-quiz 行数竞猜（Line Count Quiz）

- **pretext 核心**：`prepare` + `layout` —— pretext 是唯一的「真相神谕」。
- **玩法**：每轮：闪现一段文本（单行横向滚动展示，不给看折行结果）+
  容器宽度；10 秒内在 1-6 行按钮中押注行数（可选押注信心 ±：低信心 1 倍 /
  高信心 2 倍，错了扣一半）；倒计时结束 → pretext 现场折行揭示真相
  （逐行动画展开），猜中得分 ×连击。10 轮一局，连击显示。
- **题库**：12+ 段文本从易到狠：纯英文 → 中英混排 → emoji 混排 →
  极长单词（如 "internationalization"）→ CJK 无空格连续串。
- **诚实细节**：揭示时显示 `pretext layout() → { lineCount: 4 }` 原始返回。

### 游戏 7 · width-match 等宽配对（Width Match）

- **pretext 核心**：`prepare` + `measureNaturalWidth` —— **牌面是运行时
  实测生成的**：游戏启动时对 30+ 候选字符批量测量，按宽度两两配对
  （差值 < 0.75px 视为等宽对），再洗牌成 4×4 棋盘。不同设备字体不同，
  配对不同 —— 这是「测量即关卡生成器」的直接论点。
- **玩法**：经典记忆翻牌；翻开两张实测宽度相等（显示 px 数）→ 消除 +20
  并短暂高亮两者的宽度数字；不等 → 翻回。全部消除结算：步数越少分越高
  （`score = max(0, 300 − 步数×8)`）。两局制。
- **必配字符池**：拉丁窄宽（i l I ! | · W M m）、CJK（你我他好码终端）、
  标点（— … 。 ，）、全半角（ｍ vs m、（）vs ()）。
  若某设备凑不满 8 对（测量撞不出等宽对），自动放宽容差 1.5px 并如实显示
  本局容差值。

### 游戏 8 · kerning-tetris 排版俄罗斯（Kerning Tetris）

- **pretext 核心**：`prepareWithSegments` + `measureNaturalWidth` ——
  下落的「字块」是词语段，块宽 = 实测词宽；行消除判定 = 行内累计实测宽度
  占行宽 ≥ 92%。
- **玩法**：经典落块玩法但块是**非等宽词语**（从词库抽 2-7 字符的词）；
  左右移动 + 加速下落（点按底部按钮或键盘）；落定的块按其真实宽度占位
  （渲染 = 实测宽度，视觉上排不齐 —— 这就是玩法）；某行累计宽度达
  行宽 92% → 该行消除 +100×行数连乘；堆到顶部 = 结束。10 级速度递增。
- **对照彩蛋**：「等宽近似」开关（块宽全按 0.6em）—— 切换后手感明显
  「假」，行消除阈值也按假宽度计算，玩法失衡可感。

### 游戏 9 · flow-route 文本引流（Flow Route）

- **pretext 核心**：`prepareWithSegments` + `layoutNextLineRange` +
  `materializeLineRange` —— **每行可用宽度不同**（障碍物占据处行变窄），
  这是 pretext 独门能力（DOM 做不到逐行变宽折行）。
- **玩法**：一篇文章（10-16 行的短文）要从顶部流入一页固定高度的「版面」；
  版面里有 2-3 个可拖动的障碍盒（模拟插图）；拖动障碍 → 文本实时重排
  （layoutNextLineRange 逐行路由，行宽 = 版宽 − 该行与障碍的重叠宽）；
  目标：让文章末行恰好落在版面内（不溢出、且底部空隙 ≤ 1 行）；
  达成 → 结算 `200 − 拖动次数×15`。5 关一局，障碍数与版高递进刁钻。
- **必须实现**：拖动中每帧全篇重路由仍流畅（pretext 热路径）；
  HUD 显示 `末行 y = xxx / 版高 yyy`。

### 游戏 10 · shrink-stack 收缩塔（Shrink Stack）

- **pretext 核心**：`prepare` + `measureLineStats` —— 每块「板材」是短文本
  卡片：宽 = 收缩宽度（最宽行 maxLineWidth），高 = lineCount×行高。
- **玩法**：板材从顶部缓落（左右漂移），点按让其在当前 x 落塔；
  塔规则：新块必须比其正下方块**窄**（或宽差 ≤ 8px 容差）—— 否则滑落
  扣一命（三命）；块越「方」越稳（宽高比接近 1 加成 +10）；
  每 5 层速度 +10%。塔高（累计块高）即分数基数 × 完美层数系数。
- **生成**：短句池（2-14 字符中英混合）现场测量 → 天然宽窄高低分布，
  板材形状完全由真实排版决定。

## 6. 质量自检清单（每个游戏交付前必须全绿）

- [ ] 浏览器直开（file:// 双击打开）无 console 报错、无网络请求（DevTools Network 0 请求）
- [ ] pretext 真实参与核心机制（删掉 Pretext 调用游戏无法正常玩）
- [ ] 触屏可玩：手机宽度（360px 视口）布局不破、字号不小于 12px、可点目标 ≥44px
- [ ] 开始/结束遮罩完整，结束可重开，最高分进 localStorage
- [ ] `visibilitychange` 暂停；回来继续不跳帧（dt 钳制）
- [ ] `prefers-reduced-motion` 生效（装饰动画关闭）
- [ ] game.json 字段齐全且 apis 与实际调用一致
- [ ] 文件 ≤60KB；pretext 内联完整（diff 校验未改动）
- [ ] 无 emoji 作为游戏实体（UI 文案中可用但克制）

## 7. 交付流程（子代理必读）

1. 先读 `/home/z/my-project/worklog.md` 了解前序进展。
2. 写游戏到 `/home/z/Android-Guru-Agent/app/src/main/assets/playground/games/<id>/index.html`
   与同目录 `game.json`。
3. 用本机可用方式自测（可以用 `python3 -m http.server` 起本地服务 +
   agent-browser 打开验证交互与 console 无错）。
4. 完成后在 `/home/z/my-project/worklog.md` **追加**你的记录段
   （模板见文件内说明），注明 Task ID 与产出文件清单。
5. 不改动本规范文件、不改动其他游戏目录、不触碰 Kotlin 源码。
