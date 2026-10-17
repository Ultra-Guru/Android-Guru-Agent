# 游乐场插件街机（Playground Plugin Arcade）

> 状态：本 PR 落地（2026-10）
> 主题：游乐场插件化 —— 游戏即 HTML（自包含单文件 + game.json manifest），
> 十款 pretext 内置插件 + 运行时导入 + 「HTML → WebView 壳 APK 插件」转化路径。
> 前置：[playground-glyph-storm.md](playground-glyph-storm.md)（原生街机先例）
> · [plugin-system-v2.md](plugin-system-v2.md)（双类型插件体系）
> · [chenglou/pretext](https://github.com/chenglou/pretext)（MIT，测量引擎）

---

## 0. 一句话

**每个游戏都是一个自包含 HTML 文件**：内联 vendored pretext（0.0.9），
零网络请求，浏览器双击可玩；App 里经目录（assets 内置 + filesDir 导入）
在 WebView 街机屏运行；需要原生能力时经 `playground/apk-plugin-template/`
三步包成同签名 APK 插件 —— HTML 是游戏的统一创作载体。

## 1. 需求背景与恐龙游戏排查（诚实工程）

用户诉求：游乐场插件化（游戏 = HTML，可转 APK 插件）；内置 pretext 游戏
质量不满意，要求重做十款供挑选；删除「Google 小恐龙游戏」。

**恐龙游戏排查结论**：对全仓库所有分支、gh-pages 全部历史提交
（7 个）与线上部署页做过 `dino / trex / 恐龙 / 小恐龙` 全量检索 ——
**从未存在过任何 Google 恐龙式游戏**（唯一命中是二进制 promo.gif 与
旧分支生物学科普 JSON 的英文单词）。因此「删除」无可执行对象，本 PR
如实记录该结论；同时十款新游戏**明确不采用恐龙跑酷形态**（无跑酷 /
跳跃躲避机制），从设计源头杜绝「又一个恐龙克隆」。

## 2. 架构总览

```
                 游乐场目录（PlaygroundScreen · LazyColumn 目录卡）
                                │ 点开游戏卡
                    ┌───────────▼───────────┐
                    │  HtmlGameScreen       │ 顶栏（返回/标题/引擎徽章/重载）
                    │  （WebView 街机屏）    │ + WebView 全幅
                    └───────────┬───────────┘
                                │ file:// 入口 URL（两形态同构）
        ┌───────────────────────┴───────────────────────┐
        │ BUILTIN：assets/playground/games/<id>/        │
        │   index.html（自包含） + game.json             │
        │ INSTALLED：filesDir/playground/games/<id>/    │
        │   「导入游戏 HTML」→ catalog.installHtmlGame   │
        └───────────────────────────────────────────────┘
                                │ 毕业路径（需要原生能力 / REPLACE 权利）
                    ┌───────────▼───────────┐
                    │ playground/apk-plugin-template/  │
                    │  WebView 壳 APK 插件（同签名）    │
                    │  → PluginManager AIDL 绑定        │
                    └───────────────────────────────────┘
```

### 模块落点

| 层 | 文件 | 职责 |
| --- | --- | --- |
| 契约 | `app/…/playground/PlaygroundModels.kt` | GameManifest（宽松 @Serializable）+ PlaygroundGame + 来源枚举 |
| 目录 | `app/…/playground/PlaygroundCatalog.kt` | assets / filesDir 双源扫描合并；installHtmlGame（冲突 REJECT / 原子写）；uninstall |
| 运行时 | `app/…/playground/HtmlGameScreen.kt` | WebView 街机屏（JS + DOM storage + 同目录 file:// 白名单 + onRelease destroy） |
| UI | `app/…/playground/PlaygroundScreen.kt` | 目录态 / 游戏态 / 原生详情态三态切换；导入启动器（SAF GetContent）；卸载确认门 |
| 原生孪生 | `app/…/playground/GlyphStormSection.kt` | 原 PlaygroundScreen 的字符风暴区块原样迁出（降位为目录中「原生」卡） |
| 资产 | `app/src/main/assets/playground/games/` | 十款内置插件（<id>/index.html + game.json） |
| 规范 | `playground/GAME-SPEC.md` | 统一执行规范（设计语言 / 硬性契约 / 质量自检清单） |
| 转化 | `playground/apk-plugin-template/` | 可拷贝 WebView 壳 APK 插件模板（不参与构建） |
| vendored | `playground/vendor/pretext.js` | @chenglou/pretext 0.0.9（MIT 头保留），游戏内联源 |

## 3. 信任边界（对齐 Plugin System v2 的分界哲学）

| 维度 | HTML 游戏插件 | APK 插件（壳模板产物） |
| --- | --- | --- |
| 载体 | index.html **数据** | 独立 APK **代码** |
| 代码执行面 | WebView 沙箱内（JS + DOM storage） | 插件进程 + 原生层 |
| 签名门 | 不适用（无原生代码） | 必须（宿主同 keystore，fail-closed） |
| id 冲突 | 导入侧 **REJECT**（内置优先，数据不冒充内置） | REPLACE（同签名信任锚点） |
| 崩溃半径 | manifest 损坏 = 目录如实跳过 | 插件进程死亡 → 宿主降级 |
| 卸载 | 目录删除（filesDir 私有） | 系统卸载 |

WebView 沙箱三纪律（HtmlGameScreen）：JS + DOM storage 开启；file://
导航仅放行**同插件目录**（防越目录读取）；http(s) 放行留 CDN 兜底但
内置十款实测零请求。

## 4. 十款游戏：测量即玩法（每款锚定不同 pretext API）

| 游戏 | 机制 | pretext 锚点 |
| --- | --- | --- |
| 字符雨 | 实测宽度字符下落，拖词盘接字符拼词；等宽对照开关 | `prepare` + `measureNaturalWidth` |
| 气泡狙击 | 收缩包裹宽度的气泡上浮，按指令狙爆（3 行的 / 最宽的 / 含 emoji 的） | `prepareWithSegments` + `walkLineRanges` + `materializeLineRange` |
| 排版高尔夫 | 拖宽度滑杆让文本折成恰好 N 行，越少调整分越高 | `prepare` + `layout`（拖动中每帧重排 = 热路径速度可玩） |
| 等宽迷宫 | 拖字符棋子穿门缝：碰撞盒 = 实测宽×行高，窄字穿窄缝 | `prepare` + `measureNaturalWidth` |
| 行宽拼图 | 实测词宽瓦片拼满一行，空隙 ≤6px 完美（子集和思维） | `prepareWithSegments`（分段宽度） |
| 行数竞猜 | 押注文本折几行，pretext 现场折行揭示真相 | `prepare` + `layout`（唯一神谕） |
| 等宽配对 | 记忆翻牌：**牌面运行时实测生成**（宽度差 <0.75px 配对），不同设备配对不同 | `prepare` + `measureNaturalWidth`（测量即关卡生成器） |
| 排版俄罗斯 | 非等宽词语块下落，行内累计实测宽 ≥92% 消行；等宽对照开关 | `prepareWithSegments` + `measureNaturalWidth` |
| 文本引流 | 拖障碍盒逐行变宽重路由文章，末行恰好落进一页 | `layoutNextLineRange` + `materializeLineRange`（逐行变宽 = pretext 独门） |
| 收缩塔 | 收缩宽度板材落塔，新块必须比下方块窄 | `prepare` + `measureLineStats` + `layoutWithLines` |

设计语言统一：终端暗色霓虹（深底 #0a0f0c / 等宽 HUD / 绿-琥珀-红），
中文 UI，触屏优先，`visibilitychange` 暂停，`prefers-reduced-motion`
生效，战绩 `aga.pg.<id>.hi` 落 localStorage。全部经
`python3 -m http.server` + agent-browser 逐个实测（加载零 console 错误、
开始/核心交互/结算闭环、`window.Pretext` 真实调用）。

## 5. 实测教训（写进 GAME-SPEC 的两条硬约束）

1. **游戏实体字体必须用比例字体（sans 栈）**：等宽字体下 i 与 W 实测
   同宽，「宽度即物理」玩法直接失效（首版八款踩坑，已全部修正为
   `system-ui, "PingFang SC", "Microsoft YaHei", sans-serif`，测量串与
   CSS 渲染两端同栈）；
2. **vendored 0.0.9 的 `layoutNextLineRange` 参数序是
   `(prepared, cursor, maxWidth)`**（光标第二参）—— 与当前 npm README
   的 `(prepared, maxWidth, start?)` 相反。文本引流首版按新签名调用
   全程返回 null（零行路由），实测抓出后已修正。上游向后兼容升级
   vendored 版本时此调用点需要回归。

## 6. 与官网 gh-pages 游乐场的关系

gh-pages 的《GLYPH STORM》+ 终端街机（snake/bug/mem）保持不动（另
属网站发布线）。App 侧插件街机与它共享同一测量理念（pretext 实测宽度
驱动玩法）；后续如需官网也上十款 HTML 插件，直接拷贝
`app/src/main/assets/playground/games/` 即可 —— 单文件自包含的设计
让同一 HTML 在任何宿主零改动运行。

## 7. 验收清单（诚实工程）

- [x] 十款游戏 agent-browser 逐个实测：加载零报错、开始/交互/结算闭环、
      pretext 真实调用（宽度差 i=6.9 / W=22.1 / 你=20.0 实测可查）
- [x] 目录双源扫描（assets + filesDir）与合并去重
- [x] 导入链路：SAF 选取 → 读取 → 冲突门（REJECT）→ 原子写 → 目录对账
- [x] 卸载链路：确认门 → 递归删除 → 目录对账（内置不可卸载）
- [x] WebView 沙箱：同目录 file:// 白名单、DOM storage、onRelease destroy
- [x] 原生《字符风暴》降位为目录「原生」卡，本体零改动（迁出文件）
- [x] 仓库门禁：括号平衡 / 注释完整性 / 文件预算 / i18n 镜像（无新增字符串键）
- [x] 恐龙游戏全仓检索：无可删除对象（见 §1），新游戏零跑酷形态
- [ ] 真机 120Hz WebView 手感回归（模拟器无法验收，留给实机）
- [ ] `:app:compileDebugKotlin` CI 编译验证（本环境无 Android SDK，
      桌面检查代替；见 PR 验证表）

## 8. 路线图（向后兼容，均不改契约）

| 阶段 | 扩展点 | 接线位置 |
| --- | --- | --- |
| P1 | 市场分发游戏插件（apex-plugin-hub 的 games 货架，单 HTML 即商品） | HubSource + MarketBrowseTabs |
| P1 | agent 自生成游戏（agent 写 HTML → 安装确认门 → installHtmlGame） | 复用 MarketInstallManager 三件套 |
| P2 | 游戏战绩聚合面板（读 `aga.pg.*.stats` 键做游乐场总战绩卡） | PlaygroundScreen 头部 |
| P3 | vendored pretext 升级（0.0.9 → 最新，注意 §5-2 参数序回归） | playground/vendor + 十游戏内联同步 |
