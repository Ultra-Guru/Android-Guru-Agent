# HTML 布局分析能力全景（静态引擎 + 视觉画像 + 组件识别 + 对比工具 + 动态采集 v2）

> 版本：v2（2026-10-25 · 第二轮 8 PR 完成后）· 关联模块：`core/code-tools/.../codetools/html/`（纯 JVM 静态分析）+ `platform/browser-agent/.../BrowserLayoutTools.kt`（WebView 动态采集）
> 起源：HTML 预览模块增强（用户规格「让 Agent 能分析页面布局」）→ 第二轮「组件抽象 / 视觉画像 / 验证闭环 / 动态可读性」

---

## 一、一句话定位

Agent 生成/修改 HTML 页面后，不再只能"渲染看一眼"——三条工具链把
**布局结构、视觉质量、改动影响**全部变成可检索的结构化报告：

| 工具 | 层 | 输入 | 拿到什么 |
| :--- | :--- | :--- | :--- |
| `HtmlAnalyzeLayout` | core 静态 | 沙箱内 HTML 文件路径 | 解析树 + 布局推断 + 40+ 类问题检测 + CSS 级联 + 视觉画像 + 组件模式 |
| `BrowserAnalyzeLayout` | platform 动态 | 当前 WebView 页面 | 真实渲染几何（getComputedStyle / getBoundingClientRect）+ v2 可读性四查 |
| `HtmlCompareLayouts` | core 对比 | 改前/改后两个文件 | 新增问题（回归）/ 消失问题（修复）/ 保留 / ERROR 对比 / 视觉与组件差异 |

三者构成闭环：**静态查（写完就查）→ 动态验（真机几何复核）→ 对比证（改了没改坏）**。

## 二、静态引擎链（core/code-tools，纯 JVM · 109 项测试）

### 2.1 解析与布局推断

- **LightweightHtmlParser**：状态机词法解析，三重防御上限（深度/属性/节点数），
  嵌套闭合按 HTML 规范「最近打开优先」（第二轮修复：此前栈底匹配会把兄弟
  节点错误提升到父级）。
- **HtmlLayoutModel**：display 推断 + Tailwind/Bootstrap 类名尺寸估算 +
  弹性/定位语义建模。
- **CssStylesheetParser**（第二轮 1/8）：`<style>` 块规则解析（类/ID/标签
  选择器、`@media` 按 720px 手机视口评估）并入 display/尺寸/定位/弹性推断 ——
  样式写在 style 块里的页面拿到正确布局画像；报告头部展示已并入的规则数。

### 2.2 分析与报告

- **HtmlLayoutAnalyzer**：40+ 类问题检测（横向溢出根因、无效层叠、语义
  landmark 缺失、标题断层、表单可访问性…）。
- **HtmlVisualProfile**（第二轮 2/8）：调色板提取（主导色/语义色猜测）、
  字体栈、间距刻度（4/8px 节奏判定）、圆角阴影语言、**WCAG 对比度检测**
  （静态侧：内联样式 + style 块颜色）。
- **HtmlComponentPatterns**（第二轮 3/8）：重复结构指纹（tag + class 前缀
  归一化切断数字/状态尾缀 + 直接子元素骨架）→ 同容器分组 → 组件种类猜测
  （列表项/表格行/导航项/卡片）→ 跨容器去重。Agent 一眼看到「12 张同构
  卡片，改模板一处全部生效」。
- **HtmlLayoutReport**：骨架括号速览（`div.card>(img+h2+p)×12`）+
  Markdown 全量报告。

### 2.3 对比工具（第二轮 4/8）

- **HtmlLayoutDiff**：问题对齐键 `(severity, category, message)` 三元组
  （evidence 行号随编辑漂移不参与对齐）→ 新增（回归）/ 消失（修复）/
  保留 / ERROR 前后 / 净值；骨架与规模深度增量；视觉差异（调色板增删/
  主题切换/字体/间距节奏）；组件差异（重复组件新增/移除/数量变化）。
- **HtmlCompareLayoutsTool**：`path_a` / `path_b` 双文件入参，改完页面
  自我验证「改对了没、改出新问题没」的工具化答案。

## 三、动态采集链（platform/browser-agent · WebView）

`BrowserAnalyzeLayout` 在**当前页面**执行纯表达式 IIFE 采集脚本（只读
DOM、无网络、无变更，扫描上限 4000 元素、每类截断，病态 SPA 页保持
KB 级）：

- **v1 基础几何**：视口与横向溢出、landmark 矩形、flex/grid 容器属性、
  absolute/fixed 定位与定位祖先判定、越出视口元素、z-index 失配、缺 alt
  图片、标题层级链。
- **v2 可读性四查**（第二轮 5/8）：
  - **文字截断**：直文本元素 `scrollWidth > clientWidth+1`（或纵向）→
    内容被裁切（省略号/overflow hidden 裁切定位）；
  - **触屏小点击目标**：a/button/input/select/textarea/summary/
    role=button/onclick 可见且 <44px —— 手机上点不准的物理根因；
  - **动态对比度采样**：WCAG 相对亮度 vs **背景链**（祖先半透明背景
    逐层 alpha 混合，≤6 层；无背景按暗色偏好兜底）——「灰字叠半透明
    卡片」这类静态分析看不见的真实可读性问题；大字（≥24px 或
    ≥18.66px 粗体）阈值 3.0，正文 4.5，只报不达标项；
  - **暗色模式适配**：prefers-color-scheme + meta[color-scheme] +
    body 背景亮度 → 「系统暗色但页面纯浅色」这类适配缺口的明确结论。

与静态侧互补分工：静态查"文件里写了什么"，动态查"浏览器真渲染成了什么"
（CSS 级联、JS 注入的样式、媒体查询命中——只有真实渲染才知道）。

## 四、典型工作流

```
Agent 写/改 HTML
  ├─ HtmlAnalyzeLayout(path, include_tree)     # 写完就查：结构+视觉+组件
  ├─ （用户预览：HtmlPreviewDialog Layers 开关可视化描边）
  ├─ BrowserNavigate → BrowserAnalyzeLayout    # 真机几何 + 可读性四查
  └─ HtmlCompareLayouts(before, after)          # 修改验证：回归/修复/净值
```

预览检查器（`HtmlPreviewDialog` Layers 开关 / 图例行 / 幂等描边注入 /
装载守卫）把静态报告的元素分类在人眼侧可视化，Agent 与用户看到同一套
布局语义。

## 五、工程纪律

- 静态引擎全链纯 JVM（kotlinc 镜像编译 + Gradle `:core:code-tools:test`
  双覆盖，109 项测试）；
- 采集脚本为纯表达式 IIFE（无网络、无 DOM 变更），元素扫描与列表截断
  双上限，防病态页面把上下文打爆；
- 工具输出走统一截断通道（12KB 上限），大页面报告不挤爆对话上下文；
- 动态侧本地验证：JS `node --check` 语法门 + DOM mock 功能检查 +
  formatReport 行为断言（旧载荷向后兼容不渲染新段落）。
