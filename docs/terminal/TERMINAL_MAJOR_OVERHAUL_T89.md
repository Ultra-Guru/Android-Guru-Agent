# T89 终端大修：Ubuntu 可用性 / 输入链 / 页面结构（用户反馈根治）

> **[已退役 · T96 终端大重构 / PR #474]** 本文档描述的旧交互终端已删除，现存唯一终端为 M3 控制台（真实 PTY 执行）；文中涉及的配色方案 / 扩展键 / 键栏 / VT 网格渲染等子系统随之移除，本文仅作历史设计存档。

> 用户原话：「这个终端现在需要大幅度的修改，你修复了很多次都没有修复完成，
> 页面也一坨，Ubuntu 就是使用不了，没法直接输入命令，APT 引导失败总是
> 会显示。」本文档是这次系统性重构的自证指南 —— 每个症状 → 根因 → 修复 →
> 验证点。

## 0. 修复总表（症状 → 根因 → 修复）

| 症状 | 根因（代码级） | 修复 |
|------|------|------|
| 「没法直接输入命令」 | ① Ubuntu 失败后**永不降级建会话** → TerminalView 根本没挂载，页面无输入面；② `onCheckIsTextEditor()` 未覆写 → tap-to-type 系统通道缺失；③ `showSoftInput(SHOW_IMPLICIT)` 被部分 ROM/中文 IME 忽略；④ 自动拉键盘只在「首帧 VT 快照到达」才触发（shell 无输出时永不拉）；⑤ IME 组合区是**替换语义**但按追加直通 → "aababc" 乱序上屏；⑥ PTY master termios 清了 ICRNL/ECHO → canonical 读者（read/cat）回车不提交 | VM LOCAL 降级兜底 + `onCheckIsTextEditor` + 显式 show(0)+SHOW_FORCED 兜底 + 挂载即拉键盘 + 组合区差分（sentComposing 镜像）+ termios 恢复行规程默认 |
| 「Ubuntu 就是使用不了」 | ① 镜像回滚备份/恢复的是 `etc/apt/sources.list`，而真实写入是 deb822 `etc/apt/sources.list.d/ubuntu.sources` → 回滚是 no-op，sources 钉死最后镜像；② apt 执行无 /proc /dev /sys 系统级 bind（交互会话有）→ dpkg maintainer script/探测路径行为漂移；③ 降级 READY 不稳定：derivePhase 把「rootfs 在 + bootstrap FAILED + previous READY」翻回 ROOTFS_READY，bootstrapNote 丢失 → UI 状态来回跳 | 回滚对齐 deb822 路径 + apt 系统级 bind（SystemBindProfile.STANDARD）+ derivePhase 降级 READY 稳定化（含矩阵测试）+ 失败原因 E: 行提取可读化 + 缺档报错用户友好化 |
| 「页面也一坨」 | ① 顶部 4~7 层条带堆叠（含与终端无关的 ContextMeterBar）；② drawer 套 drawer（终端设置侧抽屉嵌在 App 全局 ModalNavigationDrawer 内）；③ 三种弹层范式混学；④ notice 埋在 21dp 状态条里且**无会话时不可见**（错误恰恰发生在无会话时）；⑤ 45 键横向长滚 + 默认 8 宏键双行键区；⑥ UbuntuBusyStrip 浮条盖住终端底部；⑦ ≥5 套颜色常量并存（含蓝灰残留 0xE6263041）；⑧ 字号 8..32（UI）vs 8..24（VM 钳制）静默分叉 | 单一紧凑顶栏 + 2dp 环境进度线；设置改 bottom sheet（TerminalConsoleTheme 深色统一）；notice 恒可见横幅；KeyToolbar 主行 13 键 + FN 折叠；扩展键默认空；进度线不遮内容；TerminalConsoleTheme 单一色源；字号区间统一 8..24 |

## 1. 输入链修复细节

### 1.1 会话兜底（TerminalViewModel.init）

```
无存活会话
  ├─ Ubuntu READY → 建 Ubuntu 会话（原行为）
  └─ 未 READY → join ensureReady()
        ├─ Ready/AlreadyReady → READY 收集器自动建 Ubuntu（原行为）
        ├─ InProgress → 环境面板进度可见（原行为；「先用 Android Shell」可达）
        └─ ★ Failed/异常 → 自动建 LOCAL 会话 + notice
            「Ubuntu 环境暂不可用（原因）— 已切换 Android Shell，可直接输入命令」
```

LOCAL 会话零依赖（mksh + forkpty，无 root、无 proot、无网络）—— 终端
**永可用**，Ubuntu 恢复后不抢用户已用的会话（autoUbuntuSessionPending 语义）。

### 1.2 IME 拉起（TerminalView）

- `onCheckIsTextEditor() = true`：系统级 tap-to-type（点按自动聚焦拉 IME）；
- `showSoftInput(view, 0)`（显式请求）+ 失败回落 `SHOW_FORCED`；
- `onWindowFocusChanged(hasFocus && isFocused && imm.isAcceptingText)` → 重拉
  （Activity 切回/弹层关闭后 IME 被收走的经典场景；不抢用户主动收起）；
- 宿主挂载即拉（`LaunchedEffect(Unit)` + 120ms attach 缓冲），首帧快照到达
  后补拉一次 —— 旧版只在 render != null 时拉。

### 1.3 组合区差分（TerminalInputConnection）

`setComposingText` 是**替换整个组合区**语义（Gboard/中文 IME 每击键上报全量
"a"→"ab"→"abc"）。以 `sentComposing` 镜像已同步到 PTY 的组合串，每次下发
与新版差异（公共前缀 + 退格 + 增量）；`commitText` 同理差分替换（提交替换
组合区，旧实现组合期直通 + 提交期全量重发 = 双份上屏）。`\n`→`\r` 归一
保留。

### 1.4 PTY termios（pty_session.cpp）

master termios 恢复行规程默认的 ECHO 与 ICRNL（forkpty 默认；Termux 同款）。
旧实现清了这两位：bash readline（自设 raw）不受影响，但 canonical 读者
（`read` 内建、`cat`、非行编辑 shell）「敲字不回显 + 回车不提交行」。
保留：清 IXON/IXOFF（Ctrl+S 输出锁死坑）+ 置 IUTF8（CJK 码点退格）。

## 2. Ubuntu 引导链修复细节

### 2.1 镜像回滚 no-op（UbuntuBootstrapManager）

APT_UPDATE 镜像 fallback（tuna→ustc→aliyun）全失败后要恢复原始 sources ——
但旧实现读写 `etc/apt/sources.list`（Ubuntu Base 24.04 根本没有该文件），
而 `UbuntuSourcesList.apply` 实际写 deb822 `etc/apt/sources.list.d/ubuntu.sources`
→ 回滚永远 no-op，sources 钉死在 aliyun。现快照/恢复同一 deb822 文件。

### 2.2 apt 系统级 bind（UbuntuAptPackageManager）

`buildProotCommand` 的 binds 只有 home:/root —— 与交互会话
（LinuxPRootBackend + SystemBindProfile.STANDARD）不一致。/proc /dev /sys
缺失时：dpkg maintainer script、dpkg-query 探测（T84 离线短路的判据来源）、
/dev/urandom 熵源（python-ssl/curl）都会出现「只在 apt 侧复现」的诡异失败。
现注入 `systemBinds.toBinds()`（host 路径不存在时诚实过滤）。

### 2.3 降级 READY 稳定化（UbuntuLifecycleCoordinator.derivePhase）

```
旧：bootstrap FAILED + previous READY → ROOTFS_READY（note 丢失，UI 跳变）
新：bootstrap FAILED + previous READY → READY（bootstrapNote 保留）
```

runEnsureSteps 判「降级可用」→ 任何 refreshState（warmUp/repair 后必调）
不再翻回「待引导」。矩阵测试新增该分支断言。

### 2.4 报错可读化

- APT_UPDATE 失败：优先结构化 error.message；stderr 只提取 `E:` 行（apt 的
  人话行；W:/Ign: 是镜像噪音）前 3 行；兜底镜像链摘要 + 网络提示；
- 缺档（debug 构建未跑 fetch_rootfs.sh）：「APK 未内置 Ubuntu 环境
  （libubuntu-rootfs.so 缺失）— 请安装 Release 版 APK；自行构建需先运行
  scripts/fetch_rootfs.sh」—— 替代开发者黑话。

## 3. 页面结构重排（TerminalScreen / 弹层 / 键区）

```
旧（竖屏顶部 4~7 层 + 双行键区 + 浮条）：
  status inset / OfflineBanner / ContextMeterBar / ConsoleTopBar
  / SessionStrip / StatusStrip(#id STATE rows×cols 调试噪音)
  主区(终端) ← UbuntuBusyStrip 浮条盖内容
  ExtraKeysBar(默认 8 宏键) + KeyToolbar(45 键横滚)

新：
  status inset / OfflineBanner / ConsoleTopBar(副标题=状态) / [2dp 环境进度线]
  / [notice 横幅(恒可见)] / SessionStrip(≥2 会话)
  主区(终端)                     ← 无浮条
  ExtraKeysBar(默认空) + KeyToolbar(主行 13 键 + FN 折叠行)
```

- **ApexRoot**：终端页隐藏 ContextMeterBar（Agent token 仪表与终端无关）；
- **弹层范式统一**：设置侧抽屉 → bottom sheet（TerminalSettingsSheet），
  drawer 套 drawer 消失；新建会话保留 AlertDialog（快速二选一标准容器）；
- **TerminalConsoleTheme**（新）：终端域全部弹层的深色 Material 主题 ——
  浅色 App 主题下不再「白纸糊黑终端」；主屏 chrome 与弹层同源
  （ConsoleTheme 单一色源，清理 0xE6263041 蓝灰残留）；
- **notice 横幅**：恒渲染（无会话时也反馈）+ 8s 自动消隐 + 点击关闭 +
  错误/信息双色语义；
- **键区**：主行不滚动（⌨ ⌫ ESC TAB CTRL SHIFT ALT ←↑↓→ 粘贴 FN）；
  FN 展开控制码/导航/F 键横滚行；符号键删除（与软键盘重复）；
  扩展宏键新用户默认空（存量用户已存储布局不受影响）。

## 4. 清理（死代码）

- `TerminalAnsiRemapper`（+ 测试）：旧 LazyColumn 渲染链遗留，零引用；
- `LocalTerminalColorScheme` / `LocalTerminalBoldAsBright`：零生产零消费；
- 死字符串 ×8（term_banner_* / term_unpack / term_*_banner）；
- `InstallState.useMirror`：构造后永不更新；
- `TerminalView.handleImeText`：被差分桥取代。

## 5. 验证表

| 项 | 结果 |
|---|---|
| `scripts/compile_terminal_jvm.sh`（terminal-emulator + native + platform:terminal main+test） | ✅ 2384 classes, 0 error |
| `scripts/compile_terminal_view_jvm.sh`（terminal-emulator + terminal-view main+test，android.jar 类型检查） | ✅ 159 classes, 0 error |
| `scripts/kotlin_balance.py`（全部 18 个改动 .kt） | ✅ balanced |
| `scripts/check_file_size.sh`（803 main + 244 test） | ✅ 全部 ≤ 预算 |
| `scripts/check_code_quality.sh` | ✅ 双门禁通过 |
| derivePhase 矩阵测试新增「FAILED + previous READY → READY」分支 | ✅（CI `:platform:terminal:testDebugUnitTest`） |
| BundledRootfsSource 缺档文案测试（contains missing/ARCHIVE_INVALID） | ✅ 兼容 |
| ExtraKeysConfig DEFAULT_LAYOUT 预算测试 | ✅ 兼容（5 键 ≤ 上限） |
| app 模块编译 | CI `:app:compileDebugKotlin`（沙箱无 Android SDK，未本地执行） |

## 6. 真机回归清单（PR 合并前由仓库主人执行）

1. 冷启动进终端页 → **秒见可用终端**（Ubuntu READY → Ubuntu 会话；
   FAILED → LOCAL 会话 + 降级 notice）；
2. 点击终端任意位置 → IME 弹出 → 敲 `echo hi` + 回车 → 输出回显正确
   （无重复字符/无乱序）；
3. Gboard/中文 IME：连续输入拼音（组合）→ 选词 → 终端显示所选词（无拼音残影）；
4. `read x` + 回车（canonical 读）→ 敲字可见 + 回车提交；
5. 断网状态打开环境中心 → apt 引导失败 → 降级 READY + 可读原因
   （E: 行）+「完成初始化」重试按钮；恢复网络重试 → 引导成功、注记消失；
6. 多会话 ≥2 → 会话条出现、状态点/chip 本地化；关闭按钮 ≥32dp 可点；
7. 终端页无 notice 时无横幅残占位；有错误 → 红色横幅、点击可关；
8. 浅色 App 主题 → 终端弹层（设置/环境中心/配色/历史）全深色控制台风。
