# Playground（游乐场插件街机）

游乐场是**插件化**的：每个游戏 = 一个自包含 HTML 文件 + 同目录
`game.json` manifest。本目录是游戏插件体系的规范源头与转化工具箱
（App 侧运行时见 `app/…/ui/screen/playground/`，架构文档见
[docs/playground-plugin-arcade.md](../docs/playground-plugin-arcade.md)）。

```
playground/
├── GAME-SPEC.md           # 游戏统一执行规范（设计语言 / 硬性契约 / 质量自检）
├── README.md              # 本文件：插件格式 + APK 转化指南
├── vendor/pretext.js      # vendored @chenglou/pretext 0.0.9（MIT，IIFE，window.Pretext）
└── apk-plugin-template/   # HTML → APK 插件的可拷贝转化模板（不参与构建）
```

十款内置游戏本体在 `app/src/main/assets/playground/games/<id>/`
（随 APK 打包；每个 index.html 自包含，内联 vendored pretext）。

## 一、HTML 游戏插件格式

一个插件 = 一个目录：

```
<id>/
├── game.json     # manifest（见下）
└── index.html    # 自包含游戏（全部 CSS/JS 内联；pretext 原样内联；零网络请求）
```

`game.json` 字段（宽松解析，未知字段不炸旧宿主）：

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

- `id`：小写字母数字连字符（目录名即 id）；
- `apis`：游戏**真实调用**的 pretext API（目录卡片的「测量即玩法」徽章，
  诚实工程：没调用的不写）；
- `engine`：测量引擎标识（内置十款均为 `pretext@0.0.9`）。

### 游戏硬性契约（摘自 GAME-SPEC.md，全量见该文件）

1. **单文件自包含**：不引用任何外部 URL（无 CDN / 网络字体 / 图片请求）；
2. **pretext 原样内联**：`vendor/pretext.js` 全文放进 `<script>`（IIFE 暴露
   `window.Pretext`），保留 MIT 头注释，禁止改写；
3. **真 pretext 驱动**：核心玩法依赖测量结果（宽度 / 行数 / 逐行路由），
   不是贴牌；pretext 加载失败才允许等宽兜底（并如实显示兜底标记）；
4. **触屏优先**（pointer events 统一处理，交互目标 ≥44px）、中文 UI、
   开始/结束遮罩、`visibilitychange` 自动暂停、`prefers-reduced-motion` 生效；
5. 战绩 localStorage 键：`aga.pg.<id>.hi`；
6. 测量字体 = 渲染字体（canvas font 串与 CSS 同栈同名同序）。
   注意：**游戏实体字符用比例字体（sans 栈）**——等宽字体下 i=W 同宽，
   「宽度即物理」玩法直接失效（实测教训）。

### 三种运行形态（同一份 HTML）

| 形态 | 入口 | 说明 |
| --- | --- | --- |
| App 内置 | `assets/playground/games/<id>/` | 随 APK 分发，不可卸载 |
| App 导入 | `filesDir/playground/games/<id>/` | 游乐场「导入游戏 HTML」即装即玩，可卸载 |
| 任意浏览器 | 直接打开 index.html | 单文件自包含 → 双击可玩，随意分发 |

## 二、HTML → APK 插件转化（毕业路径）

HTML 插件已经能玩、能分发、能导入。需要**原生能力**（JNI / 传感器 /
自有依赖）或「以代码身份覆盖内置」（REPLACE 权利）时，把它包成
WebView 壳 APK 插件：

1. 拷贝 `apk-plugin-template/` 到 `plugins/game-<你的id>/`；
2. 游戏放进 `src/main/assets/game.html`；改 `GamePluginService.kt`
   顶部三处身份常量（GAME_ID / GAME_NAME / GAME_DESC）；
3. settings.gradle.kts 加 `include(":plugins:game-<你的id>")`；
4. **与宿主同 keystore 签名**构建 —— 宿主 PluginManager 经
   `com.apex.agent.plugin.PLUGIN` action 发现、签名门校验后绑定，
   斜杠菜单出现 `game_play_<id>` 工具（启动全屏 WebView 壳）。

信任边界对齐 docs/plugin-system-v2.md：HTML 插件是**数据**（REJECT /
可导入 / 零签名），APK 插件是**代码**（同签名 REPLACE / 系统安装）。
模板细节见 [apk-plugin-template/README.md](apk-plugin-template/README.md)。

## 三、内置十款游戏速查

| 游戏 | id | pretext 锚点 |
| --- | --- | --- |
| 字符雨 Glyph Rain | `glyph-rain` | `prepare` + `measureNaturalWidth`（字符物理宽度即实测宽度） |
| 气泡狙击 Bubble Sniper | `bubble-sniper` | `prepareWithSegments` + `walkLineRanges`（收缩包裹宽度即气泡宽度） |
| 排版高尔夫 Layout Golf | `layout-golf` | `prepare` + `layout`（拖杆实时行数 = 热路径速度） |
| 等宽迷宫 Width Maze | `width-maze` | `prepare` + `measureNaturalWidth`（碰撞盒 = 实测宽度×行高） |
| 行宽拼图 Line Fill | `line-fill` | `prepareWithSegments`（词语瓦片宽度 = 实测词宽） |
| 行数竞猜 Line Quiz | `line-quiz` | `prepare` + `layout`（pretext 是唯一真相神谕） |
| 等宽配对 Width Match | `width-match` | `prepare` + `measureNaturalWidth`（**测量即关卡生成器**：配对由本机字体现场实测决定） |
| 排版俄罗斯 Kerning Tetris | `kerning-tetris` | `prepareWithSegments` + `measureNaturalWidth`（非等宽字块 + 实测宽度消行） |
| 文本引流 Flow Route | `flow-route` | `layoutNextLineRange` + `materializeLineRange`（**逐行变宽路由** —— pretext 独门能力） |
| 收缩塔 Shrink Stack | `shrink-stack` | `prepare` + `measureLineStats` + `layoutWithLines`（板材宽高全由排版尺寸决定） |

## 四、致谢

- [chenglou/pretext](https://github.com/chenglou/pretext)（MIT）—— 纯 JS
  多行文本测量与排版库，全部十款游戏的测量引擎。官网 demo：
  [chenglou.me/pretext](https://chenglou.me/pretext/)（accordion / bubbles /
  masonry / ellipsis / dynamic-layout / justification 等十个官方样例，
  本街机的玩法灵感来源）。
- vendored 构建保留上游 MIT 许可头；测量契约版本：`@chenglou/pretext@0.0.9`。
