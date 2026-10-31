# T88 · PRoot `-E` 事故档案与 env trampoline 根治

> 状态：已修复（T88）。本文是根因档案 —— 防止未来任何人再把 guest 环境变量
> 经 proot 选项传入。

## 1. 事故现象

用户设备（真机，安装内置 rootfs 的 release APK）上 Ubuntu 引导失败：

```
bootstrap stage 'APT_UPDATE' failed: exit=1: proot error: unknown option '-E'
```

T87 已做过一轮"终端体验大修"（PR #199，CI 11/11 全绿、已合并），但该故障
在用户设备上**依旧复现** —— 说明上一轮没有命中真实根因。

## 2. 根因链（三层叠加）

### 2.1 直接原因：argv 里有一个 proot 根本不存在的选项

`PRootCommandBuilderImpl.build()`（PR #63 时代引入）把 guest 环境变量按
`-E KEY=VALUE` 逐个拼进 proot argv：

```kotlin
for ((key, value) in request.environment) {
    args.add("-E")
    args.add("$key=$value")
}
```

作者注释声称"guest 环境变量只经 argv 的 -E 传入" —— **proot 没有这个选项**。
上游 proot-me/proot 的 `src/cli/proot.h` 选项表（r/b/q/w/v/V/h/k/0/i/p/n/P/l/R/S/
kill-on-exit）里没有 `-E`；本项目捆绑的 Termux 打包版 5.1.107.92 也没有
（`strings libproot.so` 可见其报错模板 `unknown option '%s'.`）。

### 2.2 为什么 CI 全绿：测试适配层把 bug 藏起来了

CI 的 E2E（`UbuntuRootfsEndToEndIntegrationTest` / `ProotExecutorProotSmokeTest` /
`UbuntuTerminalRuntimeWiringTest`）都经由 `adaptForUpstreamProot` 把生产 argv
"适配"成宿主 proot 兼容形态 —— 适配逻辑里有一行：遇到 `-E` 就把它**剥出来
搬进宿主 env**（upstream proot 会把宿主 env 继承给 guest）。于是：

- CI 路径：`-E` 从未真正到达 proot → 测试通过；
- 设备路径：argv 原样 exec → proot 5.1.107 拒启 → `APT_UPDATE` 阶段失败。

**教训：适配层本身成了 bug 的共犯 —— 测试不再执行"生产 argv 形状"。**
T88 起适配层只剥 `--`（上游自研解析器不识别）与 `--kill-on-exit`
（upstream 5.1.0 不认；Termux 补丁支持），guest env 一律 trampoline 直通。

### 2.3 为什么两轮人工检查都没发现

- 捆绑二进制 5.1.107 与 CI 安装的 Debian proot 5.4.0 是**不同版本**，
  行为面差异没有机器可校验的契约（T88 起新增 `PRootArgvContract`，见 §4）。
- 版本探针（`NativeLibraryPRootBinaryProvider`）只把版本当"诊断信息"，
  不参与 argv 决策 —— 版本差异静默存在。

## 3. 根治方案：env trampoline（Termux proot-distro 生产同款）

guest 命令改为：

```
proot -r <rootfs> -0 --kill-on-exit -b … -w <cwd> \
  -- /usr/bin/env -i KEY=VALUE … <executable> <args…>
```

- `/usr/bin/env` 属于 coreutils（Ubuntu `required` 优先级）—— 任何
  ubuntu-base rootfs（含 `scripts/build_full_rootfs.sh` 产物）必然自带；
  Android 宿主 rootfs 场景用 `/system/bin/env`（toybox）。
- `env -i` 清空继承链：guest env 完全由 trampoline 决定，与 proot 版本无关
  （5.1.107 / 5.4 / 未来版本行为一致）。
- 对 upstream proot 5.4 同样合法 —— CI E2E 无需分叉逻辑。

实现：`platform/terminal/.../proot/PRootEnvTrampoline.kt`（单一事实源，
两处消费：`PRootCommandBuilderImpl` + `ProotMcpProcessLauncher` 的内联 argv）。

### 防注入（TM6 平移 + 加强）

- key 必须匹配 `[A-Za-z_][A-Za-z0-9_]*` —— 拒绝 `-` 前缀（`env -i -K=V`
  会被 env 当作自身选项）、拒绝含 `=`（env 只按第一个 `=` 切分）；
- value 禁止 `\n` / NUL（argv 边界完整性）；
- 变量顺序 = 调用方 Map 迭代序（golden 测试可精确断言）；
- **PATH 兜底注入**：`env -i` 后若调用方漏传 PATH，trampoline 注入
  `LinuxEnvironmentManager.GUEST_PATH`（无 PATH 的 guest 连可执行名都解析不了）。

## 4. 回归锁（防止 -E 回潮）

`PRootArgvContract`（同文件）固化"合法 argv 形状"为可执行检查：

- `LEGACY_INCOMPATIBLE_FLAGS`：捆绑 5.1.107 不支持的 flag 集
  （`-E` / `-R` / `-S` / `-L` / `-T` / `-M` / `-C` / `-P` / `-B` —— 后八个
  是 5.2.0+ 别名组，本仓库一律不用显式 `-b` 表达）；
- `legacyIncompatibleFlags(argv)`：单测逐元素扫描 argv 选项段；
- `hasEnvTrampoline(argv)`：校验 `--` 之后必须是 `env -i …` 形状。

锁点：

| 测试 | 锁什么 |
|---|---|
| `PRootCommandBuilderTest.environment never uses proot -E flag` | builder 全路径 argv 无 `-E`、有 trampoline |
| `LinuxPRootBackendTest.argv is proot … env-trampoline -- bash -i` | 交互会话 argv 契约 |
| `LinuxPRootBackendTest.guest env goes through env trampoline …` | guest env 在 trampoline 段内 + 无 -E |
| E2E `adaptForUpstreamProot` | 只剥 `--`/`--kill-on-exit`，env 直通（测试执行生产 argv 形状） |
| `NativePtyArgvInstrumentationTest` (androidTest) | 真机 argv 直接用 `/system/bin/env -i` |

## 5. 事故时间线（供后来者对照）

| 轮次 | 动作 | 结果 |
|---|---|---|
| PR #63 | `PRootCommandBuilderImpl` 引入 `-E KEY=VALUE` | 埋雷（CI 适配层掩盖） |
| T87 / PR #199 | 终端体验大修（配色/空白/字体/会话修复） | CI 全绿但 `-E` 未触及 —— 用户复现 |
| T88（本档） | env trampoline + PRootArgvContract + 适配层退役 + 测试进 CI | 根治 |

## 6. 维护纪律

1. **给 proot 加任何新选项前**：先 `strings libproot.so | grep -- '--选项'`
   确认捆绑二进制（5.1.107.92）支持；不支持的一律走等价显式表达
   （bind 用 `-b`、rootfs 用 `-r`、fake root 用 `-0`）。
2. **禁止在测试里"适配掉"生产 argv 语义** —— 适配层只允许消除宿主/目标
   环境的语法差异（如 `--` 分隔符），不允许偷换语义（如把 guest env 搬宿主）。
3. CI 与设备的二进制版本差异要进 `PRootArgvContract` 这类机器契约，
   不能靠文档注释。
