# 游乐场 · APK 插件转化模板（HTML 游戏的「毕业」路径）

> 这是**可拷贝的模板目录**，不参与主仓库构建（未列入 settings.gradle.kts）。
> 用法：整目录拷贝到主仓库 `plugins/` 下，改名如 `plugins/game-mygame/`，
> 在 settings.gradle.kts 加一行 `include(":plugins:game-mygame")`，
> 把你的游戏 HTML 放进 `assets/game.html`，按需改 `game_id` / `game_name`，
> 用与宿主相同的 keystore 签名构建 APK，即可被宿主作为 APK 插件发现绑定。

## 什么时候需要这条路径

HTML 游戏插件（游乐场内 WebView 运行）已经能玩、能分发、能导入。
把它包成 **APK 插件** 的意义（对齐 docs/plugin-system-v2.md 的双类型体系）：

| 维度 | HTML 插件 | WebView 壳 APK 插件 |
| --- | --- | --- |
| 载体 | 单文件 index.html 数据 | 独立 APK 代码 |
| 代码执行面 | WebView 沙箱内 | 插件进程 + 原生层（可加 JNI/原生能力） |
| 分发 | 任意通道（市场 JSON / 直接拷贝） | 系统 PackageInstaller |
| 信任门 | 无需签名 | 宿主同 keystore 签名（v4 fail-closed） |
| 覆盖内置 | REJECT（数据不覆盖代码） | REPLACE（同签名信任锚点） |

一句话：**HTML 是游戏的统一创作载体；APK 插件是它需要原生能力或
「以代码身份覆盖内置」时的毕业形态。**

## 目录内容

```
apk-plugin-template/
├── README.md              ← 本文件
├── build.gradle.kts       ← 模块构建脚本（依赖 :plugin-sdk:plugin-api）
├── src/main/
│   ├── AndroidManifest.xml        ← service 声明 ACTION_PLUGIN + 元数据
│   ├── assets/game.html           ← 你的游戏（自包含单文件 HTML）
│   └── kotlin/com/apex/agent/plugin/game/
│       ├── GamePluginService.kt   ← AIDL 实现（元数据 + play_game 工具）
│       └── GameActivity.kt        ← WebView 壳（全屏运行 assets/game.html）
```

## 三步转化

1. **放游戏**：`assets/game.html` ← 你的自包含 HTML（含内联 pretext）；
2. **改身份**：`GamePluginService.kt` 顶部常量 `GAME_ID` / `GAME_NAME` /
   `GAME_DESC`（或读 manifest 式的 `meta-data`）；
3. **同签名构建**：用宿主 keystore（仓库 keystore/apex-release.jks）签名
   `assembleRelease` → 产物即 APK 插件。

宿主侧重装后经 `PluginManager`（queryIntentServices 发现 +
`com.apex.agent.plugin.PLUGIN` action + 版本门 + 签名门）自动绑定；
斜杠菜单出现该插件贡献的 `play_game` 工具（启动 GameActivity）。

## 契约锚点（改前必读）

- `PluginContract.HOST_API_VERSION = 2`：模板声明 `minHostVersion = 2`；
- action 常量：`com.apex.agent.plugin.PLUGIN`（与宿主双侧约定）；
- 工具 id 冲突策略：APK 插件 REPLACE（同签名前提）；本模板工具 id
  `game_play_<id>` 天然带命名空间，不与内置撞车；
- 游戏本体仍是无网络请求的自包含 HTML —— WebView 壳只做宿主，不注入
  外部资源（沙箱纪律与游乐场 HtmlGameScreen 一致）。
