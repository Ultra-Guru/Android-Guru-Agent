# README v3 美化方案 —— 数据治理 × 素材激活 × 结构重组 × 视觉升级

> 状态：**Spec（待评审）**。README v2（现状）已是一份重度工程型长文：
> 1408 行 / 86.9KB / 43 枚徽章 / 23 张表 / 4 张 mermaid，锚点零死链、
> 尾部统计区完整——**覆盖率超过 95% 的中文 Android 开源项目**。但存在
> 三个结构性短板：① 有图表没画面（零截图/零 GIF/零视频，三套现成
> 素材全部闲置）；② 密度过载（深水内容未下沉）；③ 一致性失修（数字
> 口径打架 8 处、死链 5 个、CI 表漏 12 个工作流）。本篇给出 v3 的
> 四阶段改造方案，并标注每个阶段可参考的美化组件来源。
>
> 参考基准（用户指定的克隆/参考对象）：
> `ghost-in-the-droid/android-agent`（inline video + 可组合矩阵 + dashboard）·
> `shenhao-stu/GAgent`（双语入口 + 基准前置 + 编号步骤）·
> `KYRIE66nb/mobile-agent`（真机截图声明 + 对照矩阵）·
> `Jenesyx/pretty-github`（多主题 SVG 首屏）·
> `oil-oil/beautify-github-readme`（Agent Skill 自动重设计）·
> `HenryLok0/awesome-github-readme-tools`（美化组件 curated 集合）。
>
> 关联：`docs/agent-task-packages.md`（实施分解的任务包结构）·
> `.changelog/README.md`（片段规范）· `docs/pr-body.md`（PR 惯例）。

---

## 0. 一句话定位

v2 是「信息密集的工程文档」，v3 要成为「**能被 30 秒扫读、5 分钟上手、
深挖有据的落地页**」——先治数据（可信），再活素材（有画面），再瘦身
（可扫读），最后升视觉（有质感）。顺序不可颠倒：数字打架的 README
越美化越尴尬。

## 1. 现状审计（v2 全量核对结果）

### 1.1 资产盘点

| 资产 | 现状 | 判定 |
|------|------|------|
| `docs/assets/banner.svg` | 7.1KB / 900×260，深色渐变 + 双 radial glow + 网格 + 轨道原子徽标——**品牌级定制，非模板货** | ✅ 保留，仅做变体扩展 |
| typing SVG / skillicons dark-light / 43 徽章六组分层 | 已就位 | ✅ 保留（徽章去重见 §2） |
| 4 张 mermaid + 3 张 ASCII + 8 个 details + 13 个 GitHub Alerts + 32 锚点 + 26 个返回顶部 | 已就位 | ✅ 保留 |
| `docs/assets/viro-pet-preview.gif`（447KB） | **零引用** | ❌ 闲置 |
| `promo/promo-preview.gif` + `promo/android-guru-agent-promo.mp4`（96 秒 1080p60） | 存在且 `promo/README.md` 自述「可直接内嵌到主 README」 | ❌ 未嵌入 |
| `docs/benchmark.md` | 存在 | ❌ README 无任何引用 |
| 真机截图 | **一张都没有**（对 UI 重度 App 是最大视觉短板） | ❌ 缺失 |
| 尾部统计区（stats/streak/trophy/activity/Star History/贡献者/名片，tokyonight 统一主题） | 完整规范 | ✅ 保留 |

### 1.2 数据口径冲突（8 处，全部实锤核对）

| # | 冲突 | 位置 |
|---|------|------|
| 1 | 工具总数：对比表写 102，首屏/工具章/规模表写 111 | L245 vs L6/L65/L673/L1095 |
| 2 | terminal 工具数四说：25 / 25 / 16 / 18 | L284、L331、L632、L708 |
| 3 | browser 工具数三说：41 / 24（=15+9）/ 24 | L649、L700、L838 |
| 4 | tool-registry 内置数两说：66 / 42 | L678 vs L1065 |
| 5 | 模块数：README 说 20，`settings.gradle.kts` 实际 **22 个 include**（漏 `:vendor:backdrop`、`:core:code-native`） | L67/L318/L1094 |
| 6 | wrapper 自相矛盾：L955「未锁定 wrapper，现场生成」vs L1208「已提交 gradlew」——**实际已提交**（jar 43,583 字节在库） | L955 vs L1208 |
| 7 | 首屏维度混用：typing SVG「6 agent modes」与正文「四条思考逻辑线」两个维度并列出现 | L6 vs L10 |
| 8 | TESTING.md 自认过时（「撰写时 74 文件，主干现已 252，待同步」） | docs/TESTING.md |

### 1.3 死链（5 个）

- L890 `docs/VENDOR.md` → 实际在 `terminal-native/VENDOR.md`（路径错）；
- L1145/1148/1149/1150 文档索引表引用 `prompt-variables.md`、`message-branching.md`、`search-providers.md`、`persona-cards.md` → **全仓不存在**。

### 1.4 其它失修

- CI/CD 表（L1038）只列 4 类工作流，`.github/workflows/` 实有 **18 个**（漏 apk/release/rootfs/guard-rails/changelog-gate/media-results/mirror-to-gitee/modelscope-transaction/patch-backfill + 5 个 kit 同步流中的 3 个）；
- 「Agent 引擎」单节 157 行、七档思考参数表、cs-mem 别名桥等实现级细节滞留首屏文档（docs/ 已有 107+ 文档但索引只收 33 个）。

## 2. 四阶段方案

### 阶段 R1 数据治理 —— 单一事实源（先行，其它阶段的地基）

**原则**：README 里所有「会漂移的数字」一律改为**脚本生成**，人只改
脚本不改数字。

**落点**：新增 `scripts/readme_facts.py`（与既有 `scripts/changelog.py`
同风格，Python 标准库零依赖）：

```
数据源（读代码/配置，绝不硬编码）：
  工具总数        → 枚举 ToolRegistry 注册表 / tool-dedup-map 文档交叉校验
  各族工具数      → 同上按 prefix 分组（terminal/browser/…）
  模块数          → 解析 settings.gradle.kts 的 include 行数
  测试文件数      → find src/test -name "*Test.kt" | wc
  workflow 数     → ls .github/workflows/*.yml | wc
产出：
  docs/readme-facts.json    （机器可读事实表）
  校验模式：CI 步骤比对 README 中的 {{FACT:key}} 占位符与事实表，
  漂移即红（挂进既有 ci.yml 或 guard-rails，不新增 workflow）
```

**README 侧改造**：正文数字改用 `{{FACT:tool_count}}` 风格占位符 +
本地脚本渲染提交（GitHub 不跑模板，渲染后的数字进 commit；CI 只校验
不重写——**渲染后的 README 保持纯静态**，不依赖外部服务）。

**同步修复**（R1 顺手清偿，不另立包）：

- 8 处口径冲突按事实表刷新（#6 wrapper 矛盾按实际状态改 L955）；
- 5 个死链：VENDOR.md 改正确路径；4 个不存在的文档从索引表移除（或
  补最小占位文档——推荐移除，诚实优先）；
- CI/CD 表补全 18 工作流（按「发布链 / 质量链 / 同步链 / 运营链」分组）；
- TESTING.md 按事实表刷新并去掉「待同步」注释。

**验收**：`python3 scripts/readme_facts.py --check` 退出码 0；README
全文 grep 无 102/16/41/42/20 等旧口径残留（占位符渲染后核对）。

### 阶段 R2 素材激活 —— 零成本画面（最高性价比）

参考模式：`ghost-in-the-droid/android-agent`（inline video）+
`KYRIE66nb/mobile-agent`（真机截图声明）+ `shenhao-stu/GAgent`
（基准前置）。

**R2.1 宣传片内嵌**（首屏 banner 下方）：

```html
<details open>
<summary><b>🎬 96 秒看懂 — 设备上的自主智能体</b></summary>
<video src="promo/android-guru-agent-preview.mp4" 
       poster="promo/promo-preview.gif" 
       controls muted playsinline width="100%"></video>
<sub>▶ <a href="promo/">更高清版本 / 下载 mp4</a></sub>
</details>
```

- 用 `<details open>` 包裹：GitHub 流量友好（不自动拉 96 秒 1080p60），
  poster 用现成 GIF 保证折叠态也有画面；
- 视频下 `<sub>` 行给下载链接——android-agent 同款可访问性处理。

**R2.2 基准前置**（GAgent 模式：数据在首屏可一键验证）：

- 「一眼看懂」章节顶部追加基准卡：

```markdown
### 📈 基准数据（可复现）

| 维度 | 结果 | 复现入口 |
|------|------|----------|
| 工具执行成功率 | xx%（N=xxxx 次调用） | docs/benchmark.md |
| 端到端任务完成率 | xx%（AndroidBench 式场景集） | 同上 |
```

- 数据源：`docs/benchmark.md`（已存在但零引用）——若内容陈旧，R2 先
  以「现状 + 更新计划」诚实呈现，**不造数**（诚实哲学贯穿）。

**R2.3 截图墙**（mobile-agent 模式：「没有渲染图」声明）：

- 新增「📱 应用界面」章节的实物化：真机截图 6-8 张（Coding 屏 / 终端 /
  浏览器仪表盘 / 市场 / 设置 / 长任务中心），`docs/assets/screenshots/`
  目录 + `<picture>` dark-light 适配（截图本身分亮暗主题拍摄两组）；
- 声明行：`> 以下均为真机截图（Pixel/小米 各一台，Android 14/15），
> 无渲染图、无概念图`——mobile-agent 同款信任话术；
- **标注 device-required**：截图需真机采集，Agent 无法生成——本项是
  全 spec 唯一必须由仓库主人亲手完成的任务包。

**R2.4 吉祥物激活**：`viro-pet-preview.gif` 进「快速开始」收尾或
  FAQ 顶部做情绪调节（447KB 单 GIF，一次加载可接受；不上首屏）。

### 阶段 R3 结构重组 —— 瘦身与可扫读性

**目标**：README 从 1408 行降到 **≤900 行**，深水内容全部下沉 docs/
（首屏保留卖点 + 快速开始 + 架构速览 + 对比矩阵）。

**R3.1 下沉清单**（带去向）：

| 章节（现行号） | 行数 | 去向 |
|----------------|------|------|
| 七档思考参数表（L496-505）+ 思考线实现细节 | ~40 | `docs/thinking-levels.md`（已存在，内容合并） |
| cs-mem 拓扑别名桥（L409-415）+ 记忆管线深水 | ~30 | `docs/cs-mem-gaps-spec.md` / knowledge-system.md |
| 编排器 stateDiagram 全集（L443 附近） | ~25 | `docs/task-execution-architecture.md`（已有同款图） |
| 插件 SDK 双类型细节（L815-846） | ~20 | `docs/plugin-system-v2.md`（保留 README 入口表） |
| 工具 111 个逐项清单（L673-728 details 块） | ~55 | 独立成 `docs/tool-catalog.md`，README 留分组统计表 |
| FAQ 7 问中 3 个超长实现向问答 | ~40 | 拆为独立 troubleshooting 文档 |
| **合计** | **~210 行** | README 各节留一行链接 + 一句摘要 |

**R3.2 可组合能力矩阵**（android-agent 的三列矩阵模式——读者 5 秒
判断「我的场景能不能用」）：

| 平台（三选一） | 大脑（四选一） | 执行环境（二选一） |
|----------------|----------------|---------------------|
| arm64 真机 ✅ | 任意 OpenAI 兼容端点 | PRoot Ubuntu 24.04（免 Root） |
| x86_64 模拟器 ✅ | 本地小模型（llama.cpp 兼容服务） | chroot Ubuntu（需 Root，T98） |
| armv7 老设备 ⚠️（部分工具受限） | 云端大模型（推荐） | Android 宿主 shell（兜底） |

——落在「一眼看懂」之后，替换现 3×2 特性卡的信息冗余部分。

**R3.3 双语入口**（GAgent 模式）：首屏第一行追加
`简体中文 · [English](docs/README_EN.md) · [Quick Start](#quickstart) ·
[Benchmark](#benchmark) · [Docs](#docs-index)`——英文版 README 首期
只做**精简版**（≤300 行：定位/矩阵/快速开始/架构图/链接），标注
`Partial translation, full docs in Chinese`，**不做全文机翻**（质量
诚实优先）。

**R3.4 文档索引补全**：索引表从 33 → 收全 107+ 文档（按 1.1 的主题
分类分组），死链清零。

### 阶段 R4 视觉升级 —— 质感层（最后做，纯加分项）

**R4.1 多主题 banner 变体**（pretty-github 模式）：现有 banner.svg
拆出主题层（背景渐变 + glow 颜色），产出 dark / light 双版 +
`<picture media="(prefers-color-scheme)">` 适配（skillicons 已有同款
双主题先例，banner 跟齐）。

**R4.2 章节标题 SVG**（beautify-github-readme skill 的核心产出物）：
六大主章节（一眼看懂 / 架构 / 终端 / 工具 / 快速开始 / 对比）生成
同风格 SVG 小节头（品牌渐变 + 章节序号），与 banner 构成统一视觉
系统。**工具路径**：`npx skills add oil-oil/beautify-github-readme`
让 Agent 按品牌色（#3DDC84 → #00C2D1 → #7F52FF）自动生成，或参考
`awesome-github-readme-tools` 的 terminal-shields 做终端风格变体。

**R4.3 徽章收敛**：43 → ≤28 枚。去重原则：

- 版本徽章 9 枚（Gradle/JDK/Kotlin/AGP/Compose/Hilt/Room/OkHttp/…）→
  合并为 skillicons + 2 枚关键版本（Kotlin / minSdk）——技术细节已在
  构建章节有表；
- 仓库动态 11 枚（stars/forks/issues/PRs/…）→ 保留 4 枚高信息量
  （stars / forks / last-commit / repo-size），其余尾部统计区已有
  卡片，首屏不重复；
- 分享 4 枚 → 保留 2（X / Telegram），Reddit 与 Follow 挪尾部。

**R4.4 交互式架构图**（mobile-agent 模式，可选 P2）：`docs/assets/
architecture.html`（支持缩放/路径追踪/暗色模式的静态单文件），README
架构节留 mermaid 速览 + 「📊 交互式大图」链接——**不内嵌 iframe**
（GitHub 不渲染），外链新标签打开。

## 3. 实施分解（Agent 任务包，对齐 `docs/agent-task-packages.md`）

依赖：R1 全部 →（R2.1 ∥ R2.2 ∥ R2.4 ∥ R3 并行）→ R4。
R2.3 截图采集独立于依赖链（仓库主人随时可做，做完 R3 接入）。

| 包 | 内容 | 改动 | 验收 | device-required |
|----|------|------|------|-----------------|
| `facts-script` | readme_facts.py + 占位符渲染 + CI 校验步骤 | scripts/ + ci.yml + README 全文数字 | `--check` 退出 0；口径冲突 8 处清零 | 否 |
| `dead-links` | 5 死链修复 + 索引表补全 107+ | README + 4 个文档删除/路径修正 | 全部相对链接 `ls` 可达（脚本核对） | 否 |
| `media-embed` | 宣传片 details 块 + viro-pet 激活 | README + promo/ 引用 | 渲染快照含 video 标签与 poster | 否 |
| `benchmark-card` | 基准卡 + benchmark.md 现状化 | README + docs/benchmark.md | 卡片数据与文档一致（无造数） | 否 |
| `restructure` | 下沉 210 行 + 可组合矩阵 + 双语入口精简版 | README + docs/ 5 个文档承接 | README ≤900 行；英文版 ≤300 行；锚点零死链（既有脚本复验） | 否 |
| `visual-pass` | banner 双主题 + 章节头 SVG + 徽章收敛 | docs/assets/ + README | 双主题 picture 快照；徽章 ≤28 | 否 |
| `screenshots` | 真机截图 6-8 张 + 声明行 | docs/assets/screenshots/ + README | 截图存在 + alt 文本完整 | **是**（仓库主人采集） |

**每包一个 PR**，changelog 片段照常（`<!-- pr: <slug> -->`）；
`screenshots` 包标注 NOT VERIFIED 语义反转——它是唯一「Agent 验不了」
的包，PR 模板 Testing 节如实勾选「需人工采集」。

## 4. 测试与验收总表

| 验收项 | 手段 | 覆盖阶段 |
|--------|------|----------|
| 数字口径 | `readme_facts.py --check`（CI 常驻步骤） | R1 起永久 |
| 链接有效性 | 相对链接存在性脚本（可并入 facts 脚本） | R1/R3 |
| 行数预算 | README ≤900 / EN ≤300（facts 脚本附带断言） | R3 |
| 锚点完整性 | 复用 v2 已有的锚点校验方式（32 锚点零死链的既有做法脚本化） | 全程 |
| 视觉回归 | 双主题 banner / 章节头 / video 标签的渲染快照（人工 review 为主，快照为辅） | R2/R4 |
| 诚实性 | 基准数据可溯源、截图声明真实、英文版标注 partial | R2/R3 |

## 5. 已知限制与取舍

1. **占位符渲染是本地脚本提交制**，不是 CI 重写——代价是改数字要多跑
   一步脚本；换来 README 纯静态零外部依赖（权衡自 `docs/hot-update-pipeline.md`
   一系的「构建期只读」哲学）；
2. **交互式 HTML 架构图**（R4.4）为外链形态——GitHub README 不渲染
   iframe/script，移动端体验打折（可接受：速览 mermaid 已在正文）；
3. **英文版是精简版**——全文双语维护成本在本仓人力下不可持续，精简版
   + partial 声明是诚实解；后续若社区有翻译贡献者再全文化；
4. **徽章收敛会移除部分社交分享入口**——按「首屏信息密度优先于推广
   面积」取舍，被移除的入口全部保留在尾部（信息不丢失，只挪位置）；
5. `readme_facts.py` 的工具总数数据源依赖 ToolRegistry 注册表形态——
   若工具注册后续迁库（如 apex-kit-*），脚本同步改解析目标（脚本头
   部注释标注数据源契约）。

## 6. 不做什么（防蔓延清单）

- 不引入 README 之外的站点（GitHub Pages / Docusaurus）——gh-pages
  分支已有其用途，不承担文档站；
- 不做打字机/粒子/鼠标跟随等重动画（首屏性能与 GitHub 兼容性优先，
  typing SVG 一个动效元素封顶）；
- 不用 shields.io 之外的动态徽章服务（CDN 依赖最小化：readme-typing-svg
  与 skillicons 已是存量，不再新增域名）；
- 不机翻任何文档（诚实优先，宁缺毋滥）。
