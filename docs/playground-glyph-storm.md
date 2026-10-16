# 游乐场 · 字符风暴（GLYPH STORM）

> 抽屉一级导航页，位于「玻璃实验室」正上方。
> 起源：用户指定以 [chenglou/pretext](https://github.com/chenglou/pretext)（MIT）
> 为灵感做一个「很有趣的游戏」，与 gh-pages 官网游乐场
> 《GLYPH STORM》构成一体两面 —— 官网版用 pretext 的
> measureNaturalWidth 实测字符宽度，App 版用 Compose TextMeasurer
> 完成同一契约。

## 一句话

pretext 实测排版宽度驱动的物理拼词街机：**字符不是等宽方块** ——
i 窄、W 宽、「你」满格，每个字符的物理半径都是真实排版宽度。

## 玩法

1. 按住屏幕拖动 = 引力井，漂浮字符被吸向指尖；
2. 把发光的目标字符吸进中央捕获环（空槽有幽灵字提示缺口）；
3. 拼满整词清场奖励，难度爬升（虫子更快更多）；
4. 虫子螺旋爬向环会咬血（三颗心），点按直接拍死 +15；
5. 连续捕获攒连击倍率，被咬断连击。

## pretext 契约（本游戏的灵魂）

| 层 | Web（gh-pages 游乐场） | Android（本屏） |
|----|----------------------|----------------|
| 测量 | pretext `measureNaturalWidth` | Compose `TextMeasurer.measure` |
| 消费 | 字符物理半径 + 卡槽宽度 | 同左（`GlyphStormEngine.glyphWidth`） |
| 对照 | 「pretext 实测 / 等宽近似」切换 | 同款切换（FilterChip） |

等宽近似模式：所有字符按 0.6em 假宽度参与物理（目标字符外画
虚线假宽框）—— 手感立变笨拙，等宽思维与实测排版的差距可玩可感。

## 架构

```
ui/screen/playground/
├── PlaygroundScreen.kt   # 页面壳：页头 / 模式切换 / 战绩 / pretext 展窗 / 玩法说明
├── GlyphStormGame.kt     # 街机柜：HUD + Canvas 绘制 + 手势 + 帧循环 + 阶段遮罩
└── GlyphStormEngine.kt   # 纯逻辑引擎：物理 / 生成 / 捕获 / 计分（无 Compose 运行时依赖）
```

- **帧循环**：`LaunchedEffect + withFrameNanos`；离开组合即停帧
  （切抽屉页 = 免费自动暂停），恢复时 dt 钳制 ≤50ms 物理不跳变；
- **绘制失效**：Canvas 读 `frameTick` 快照状态每帧失效；HUD 走
  结构性相等的 `StormHud` 快照（数值不变不重组）；
- **触觉**：捕获 / 拍虫 / 被咬 → `HapticFeedbackType.LongPress`
  （无音频权限负担的「音效」）；
- **持久化**：`SharedPreferences("playground")` —— 高分 / 场次 /
  累计清词 / 最长连击；
- **虫子**：矢量绘制（两节身体 + 四腿 + 触角），避免 emoji
  代理对的测量坑；
- **连续动画豁免**：与玻璃实验室 / 关于页 Hero 同属全应用少数
  允许连续动画的屏幕（游戏本体）；其余业务界面仍禁循环动画。

## i18n

抽屉入口 `drawer_playground`（values: Playground / values-zh: 游乐场），
中英 1:1 镜像（check_string_mirror.py 门禁）。屏内文案沿用玻璃实验室
先例：中文直写。

## 验收清单（诚实工程）

- [x] 卡槽宽度 = 字符实测宽度（等宽模式切换可见差异）
- [x] 引力井 / 捕获 / 弹开 / 互斥物理全部经宽度表
- [x] 切页自动暂停（LaunchedEffect 生命周期）
- [x] 战绩持久化（进程重建后仍在）
- [x] 三命局 / 游戏结束 / 重开闭环
- [x] 拍虫命中判定（30dp 触区）
- [ ] 真机 120Hz 手感调参（模拟器无法验收，留给实机回归）
