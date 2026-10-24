<!-- kb: id=shell | role=builtin_coding_shell | title=Shell 专家知识库 | version=1 -->
# Shell 专家知识库

## 必看内容

- **严格模式是兜底，不是策略**：set -e 在条件、`&&`/`||`、`!`、trap 执行期
  全部豁免；`local x=$(false)` 掩蔽、`((i++))` 首圈暗杀、函数被条件调用时
  体内 -e 整体失效——每一处都有标准对策（11-19 节）。
- **转发只有 "$@"，命令只有数组**："$*" 连接成词，无引号扩展等于分词+glob
  双重劫持；argv 用数组攒、`rm -rf "${dir:?}"` 守卫空值——24-29 节是
  引号纪律的全集。
- **清理单出口**：信号 handler 只做 exit，一切清理进 EXIT trap；exec 会
  跳过 EXIT、kill -9 不可拦——锁优先 flock 自动释放兜底（34-37、62、106 节）。
- **容器 PID 1 的 TERM 是哑的**：内核关闭其默认处置且只发给主进程——
  entrypoint 必须 trap TERM 转发或直接 exec 交权；长跑容器还要 tini 收割
  孤儿（44-45 节）。
- **/bin/sh 是另一门语言**：Debian/Ubuntu 的 sh=dash：无 pipefail/[[/数组、
  echo 永远解释转义；macOS 冻结 bash 3.2：无关联数组/mapfile——版本底线
  由矩阵决定，不靠感觉（1-3、89-95 节）。
- **LC_ALL=C 是技术管线的默认**：人类 locale 的词典序会让 join 报错甚至
  静默错配、grep 慢数倍——sort/join/comm/awk 管线统一整流（55-56 节）。
- **单遍纪律**：grep、grep、sort 串联的每一级都是一次全量 IO 加一批 fork；
  awk 关联数组一次扫描完成过滤/聚合/去重（46-49、58、76 节）。
- **临时文件只有 mktemp，落盘只有 tmp+rename**：`$$` 拼出来的名字是
  竞态后门；`>` 原地截断写在崩溃时即损坏——原子换名才配叫写配置（80-81 节）。
- **秘密不进命令行**：args 经 ps 全员可见，env 限同 uid 读，fd 或 0600
  文件才是底线；xtrace 会把展开后的秘密打进日志（83、97-99 节）。
- **cron 三雷**：SHELL=/bin/sh 不是你交互用的 bash；% 在 crontab 里变成
  换行并开启 stdin；PATH 是极简集——每条都让"本地好好的"翻车（108 节）。
- **stdout 是产品，stderr 是旁白**：脚本当 API 用就要有退出码契约
  （sysexits 64-77、124/126/127/128+N 各就各位），数据流与日志流分离，
  ANSI 别进 cron 邮件（21、103、107 节）。
- **CI 里的 bash 不是你的 bash**：GitHub Actions 默认 `bash -e` 无 pipefail
  （显式 `shell: bash` 才是 `-eo pipefail`）、`${{ }}` 是注入直通车、
  BASH_ENV 是每步都 source 的隐藏启动文件（5、97-100、109 节）。

## 快速索引

| 想找什么 | 去哪一节 |
| --- | --- |
| 各发行版 bash 版本与底线选择 | 1. Bash 版本矩阵与生产底线决策 |
| macOS bash 3.2 缺什么 | 2. macOS 的 Bash 3.2 冻结世界生存 |
| dash 和 bash 差异 | 3. /bin/sh 不是 bash：dash 差异全清单 |
| zsh 和 bash 语义差异 | 4. zsh 交叉陷阱：1 起始数组与默认不切词 |
| BASH_ENV 注入与利用 | 5. POSIX 模式与 BASH_ENV/ENV 双刃剑 |
| Alpine/busybox 脚本兼容 | 6. BusyBox 与嵌入式环境生存 |
| shebang 怎么写才对 | 7. Shebang 纪律与解释器定位 |
| 运行时能力检查 | 8. 能力探测与优雅降级 |
| builtin 和外部命令差异 | 9. 内建与外部命令的边界 |
| too many open files 治理 | 10. ulimit 与文件描述符边界治理 |
| set -e 什么时候不生效 | 11. set -e 真实语义：失效位置全图 |
| local 赋值吞掉退出码 | 12. local/declare 掩蔽：最阴险的 -e 盲区 |
| pipefail 的坑 | 13. pipefail 深水：管道与命令替换边界 |
| ${var:-} 和 ${var-} 区别 | 14. set -u 与 ${var:-} 家族精确语义 |
| set -u 空数组报错 | 15. set -u 与数组：空数组扩展历史坑 |
| 脚本在循环里死了 | 16. ((i++)) 暗杀：算术返回值与 set -e |
| ERR trap 不进函数 | 17. ERR trap 继承：errtrace 与函数边界 |
| strict mode 标准块 | 18. 严格模式组合拳：可复用脚手架 |
| 哪些脚本别用 set -e | 19. 什么时候不该开严格模式 |
| die/usage 函数写法 | 20. 失败要响亮：die 与 usage 模式 |
| 脚本退出码规范 | 21. 退出码契约：sysexits 与 126/127/128+N |
| 管道中间命令失败检测 | 22. PIPESTATUS 取证与中段失败 |
| 脚本怎么测试 | 23. 严格模式可测试性：故障注入与 bats |
| word splitting 规则 | 24. 分词内部机制：IFS 的双重角色 |
| $* 和 $@ 的区别 | 25. "$*" vs "$@"：连接与独立的边界 |
| IFS 换行制表技巧 | 26. IFS=$'\n\t' 惯用法的收益与暗面 |
| 数组操作安全清单 | 27. 数组安全操作全谱：扩展、键、unset 引号 |
| declare -A 遍历顺序 | 28. 关联数组与遍历序不确定性 |
| ${var:?} 守卫 rm | 29. 参数扩展军火库：:-、:?、:+ 守卫 |
| 变量安全重新加引号 | 30. printf %q 与 ${var@Q} 再引号技术 |
| 不用 eval 怎么写 | 31. eval 的现代替代方案 |
| $( ) 的边界 | 32. 命令替换：换行剥离与字节边界 |
| heredoc 引号规则 | 33. here-doc/here-string 的引用规则 |
| EXIT trap 双跑 | 34. trap 基石：EXIT 单次执行与双触发 |
| 信号处理退出码 | 35. INT/TERM 处理：128+N 退出码纪律 |
| trap 什么时候执行 | 36. trap 执行时点与前台子进程 |
| 子 shell trap 继承 | 37. 子 shell 的 trap 重置真相 |
| DEBUG trap 用法 | 38. DEBUG/RETURN trap 与 -T 追踪 |
| 141 退出码处理 | 39. SIGPIPE：管道消费者的正常死亡 |
| 杀进程树 | 40. 进程组与 setsid：负 PID 组信号 |
| timeout 正确用法 | 41. timeout 分层杀法与退出码语义 |
| 后台任务退出会话存活 | 42. nohup/disown 与 SIGHUP 生命周期 |
| ssh 命令不返回 | 43. ssh 挂住：后台进程的 fd 纪律 |
| 容器 entrypoint 信号 | 44. PID 1 陷阱：容器中的信号转发 |
| wait 和僵尸进程 | 45. 子进程收割：wait 家族与孤儿 |
| awk 程序组织 | 46. awk 程序结构：BEGIN/模式/END 选型 |
| awk 字段重建陷阱 | 47. awk 字段机制：FS/NF/OFS 深水 |
| awk 两文件关联 | 48. NR==FNR：双文件 join 惯用法 |
| awk 聚合与去重计数 | 49. awk 关联数组：分组、计数、去重 |
| gawk 独有功能 | 50. gawk 扩展与可移植 awk 分界线 |
| sed hold space | 51. sed 模式空间与 hold space |
| sed 多行处理 | 52. sed 多行编辑：N/D/P 循环 |
| sed -i 两个平台差异 | 53. sed -i 跨平台雷区与可移植写法 |
| sort -k 字段写法 | 54. sort 深水：-k 语义、稳定性与 -S 调优 |
| LC_ALL=C 何时必设 | 55. LC_ALL=C：locale 整流的双重收益 |
| join/comm 用法 | 56. join/comm：关系操作与排序前提 |
| cut 多字符分隔符 | 57. cut 的单字符局限与替代 |
| grep 串联为什么慢 | 58. 单遍纪律：多级 grep 管道税 |
| 管道没有输出卡住 | 59. 管线活性：行缓冲与 64KB 阻塞 |
| tr 的用法边界 | 60. tr 与字节级转换战场 |
| xargs 并行怎么写 | 61. xargs -P 并行纪律与引号 |
| flock 用法选型 | 62. flock 三种形态与选型 |
| 不用 flock 怎么加锁 | 63. 无 flock 的锁：noclobber 与 mkdir |
| 后台任务管理 | 64. 后台作业表：jobs、wait 与 set -m |
| coproc 怎么用 | 65. coproc：双向协程管道 |
| mkfifo 使用 | 66. FIFO 模式：阻塞语义与超时读 |
| parallel 还是 xargs | 67. GNU parallel 的许可与部署 |
| <() 失败会怎样 | 68. 进程替换 >( ) <( ) 的失败感知 |
| 并行失败收集 | 69. 扇出限流与失败聚合 |
| pidfile 陈旧检测 | 70. kill -0 存活检测与 PID 复用竞态 |
| 循环里变量丢了 | 71. 循环内子 shell 消灭战 |
| read 参数纪律 | 72. read 纪律：-r、IFS= 与 -d '' |
| mapfile 用法 | 73. mapfile/readarray 与批量读 |
| exec {fd} 动态描述符 | 74. exec 与动态 fd 工程 |
| 少 fork 提速 | 75. fork 计数：内建优先与 EPOCHSECONDS |
| 大文件排序策略 | 76. 大文件：sort 外部合并与 awk 单遍 |
| 脚本性能怎么测 | 77. 微基准与 strace 取证 |
| bash 启动开销剖析 | 78. 启动成本与冷启动优化 |
| 变量注入怎么防 | 79. 引号即防线：注入场景复盘 |
| mktemp 正确用法 | 80. mktemp 纪律与 /tmp 竞态 |
| 原子覆盖写文件 | 81. 原子写：tmp+rename 与崩溃一致性 |
| 脚本文件权限 | 82. umask 与权限最小化 |
| 密码怎么传给命令 | 83. 秘密经行：args/env/fd 可见性矩阵 |
| curl 管道直执行风险 | 84. curl 管道直执行批判与替代协议 |
| sudo 重定向失败 | 85. sudo 边界与重定向陷阱 |
| 为什么脚本不能 setuid | 86. setuid 之死与 Shellshock 遗产 |
| 文件名当参数的安全 | 87. 不可信输入的验证与 -- 分隔 |
| 安装脚本规范 | 88. 安装脚本的供应链最低标准 |
| 改写为 POSIX sh | 89. bash→POSIX sh 移植清单 |
| [[ 和 [ 什么区别 | 90. [[ ]] vs [ ]：语义差异全表 |
| echo 为什么不能用 | 91. echo 地雷与 printf 迁移 |
| macOS 命令差异 | 92. GNU vs BSD 工具矩阵：macOS 战场 |
| date 跨平台计算 | 93. 算术与 date 的可移植性 |
| realpath 可移植方案 | 94. 路径处理的可移植深水 |
| 跨 shell 测试 | 95. 测试矩阵：dash/busybox 的 CI 策略 |
| 最小依赖脚本原则 | 96. 最小工具集与降级路径 |
| set -x 太乱怎么办 | 97. set -x 噪声治理与 PS4 增强 |
| xtrace 输出到文件 | 98. BASH_XTRACEFD：xtrace 分流到文件 |
| set -x 打出密码 | 99. xtrace 泄密与 CI 脱敏 |
| shellcheck disable 规范 | 100. shellcheck 指令纪律与豁免治理 |
| 函数级 trace | 101. DEBUG trap 与函数级追踪术 |
| bash 调试工具 | 102. trace 后处理与 bashdb |
| 脚本日志规范 | 103. 结构化日志与 ANSI 污染 |
| 脚本幂等怎么写 | 104. 幂等设计：收敛式变更 |
| 陈旧锁怎么处理 | 105. 锁生命周期与陈旧锁恢复 |
| trap 清理怎么不漏 | 106. trap 清理正确性：资源登记簿模式 |
| 脚本对外契约 | 107. 面向调用方的退出码 API |
| cron 脚本为什么失败 | 108. cron 环境：PATH 极简与 % 转义 |
| CI 里 bash 语义 | 109. CI shell 语义：GitHub Actions 深水 |
| 常见 shell 反模式 | 110. 高频反模式速查清单 |
| set -e 事故案例 | 111. 事故复盘：半吊子严格模式翻车 |
| cron 事故案例 | 112. 事故复盘：cron 并发与清理失效 |

## 1. Bash 版本矩阵与生产底线决策

- 发行版现实：RHEL 8 = bash 4.4.20、RHEL 9 = 5.1.8；Ubuntu 20.04 = 5.0、
  22.04 = 5.1、24.04 = 5.2；Debian 12 = 5.2；Alpine 默认只有 busybox ash。
- bash 5.2（2022-09）重写命令替换解析（递归调 bison parser），修掉一批
  嵌套 `$()` 老 bug；5.3（2025-07 发布）小步迭代（进程管理方向增强），
  语言层无破坏变化——升级风险低，但别指望新语法。
- 按特性定底线：declare -A 要 4.0、declare -g 与 printf %()T 要 4.2、
  nameref/wait -n/负下标要 4.3、${var@Q} 与 mapfile -d 要 4.4、
  EPOCHREALTIME 要 5.0、wait -p 要 5.1——逐条对表，别拍脑袋。

```bash
# 特性门控：低于 4.4 放弃 ${var@Q}，退回 printf %q
if ((BASH_VERSINFO[0] > 4)) || { ((BASH_VERSINFO[0] == 4)) && ((BASH_VERSINFO[1] >= 4)); }; then
  quoted=${var@Q}; else quoted=$(printf '%q' "$var"); fi
```

- 事故形态：开发机全绿，客户老机器 `declare -A` 直接 not found——
  shebang 表达不了版本要求，防线只有门控 + CI 矩阵（95 节）。
- 版本探测启动期做一次存变量即可；热循环反复解析 --version 是自造
  性能黑洞（75 节）。

## 2. macOS 的 Bash 3.2 冻结世界生存

- Apple 冻结在 bash 3.2.57（4+ 是 GPLv3，Apple 不带）：/bin/bash 与
  /bin/sh 都是这台 2007 年级解释器，zsh 才是默认登录 shell（4 节）。
- 3.2 缺什么：declare -A、mapfile、${var,,}、负下标、wait -n、
  exec {fd}>、BASH_XTRACEFD、&>>、|&、${var@Q}、printf %()T、declare -g
  ——函数里声明全局只能裸赋值。
- 3.2 的 [[ =~ ]] 有著名正则雷：带引号的分组/元字符行为错乱（4.0 修）
  ——正则一律先存变量再匹配（90 节）。
- Homebrew bash 分芯片：Apple Silicon 在 /opt/homebrew/bin/bash，Intel
  在 /usr/local/bin/bash；shebang 别指 brew 路径（没装即废）。

```bash
# 3.2 安全写法
last=${args[$(( ${#args[@]} - 1 ))]}    # 负下标 ${args[-1]} 要 4.3+
[[ ${map[key]+x} ]] || map[key]=dft    # [[ -v map[key] ]] 数组支持要 4.3+
```

- 策略三选一：只写 POSIX sh（3 节）/ 写 3.2 安全子集 / 文档明说"需
  brew bash"；禁止静默依赖 4+ 特性。realpath 等工具面差异归 92 节。

## 3. /bin/sh 不是 bash：dash 差异全清单

- Debian/Ubuntu 的 /bin/sh = dash（ash 系）；Alpine = busybox ash
  （6 节）；RHEL 系 /bin/sh = bash --posix（5 节）——同句 shebang 三个世界。
- dash 没有的：pipefail、[[ ]]、数组、here-string、进程替换、${var,,}、
  read -a/-t/-N、wait -n；$() 与 read -r 是安全区。
- 语义差比缺失更阴：dash 的 echo 永远解释反斜杠转义且关不掉（printf
  是唯一出路，91 节）；dash 的 [ ] 不认 ==（POSIX 等号是 =）。
- `local` 不在 POSIX 但 dash/ash/busybox 全支持——实践安全；数组没有
  等价物，移植成本集中在这（89 节）。

```bash
# 骑墙三连死（#!/bin/sh 下）：
# [ "$x" == y ]    → dash: unexpected operator
# set -o pipefail  → dash: invalid option
# echo -e "a\tb"   → bash 打字面 -e；dash 反而解释了转义
[ "$x" = y ] && printf 'a\tb\n'      # 两边通吃
```

- 纪律：要么整文件 POSIX（CI 跑 dash），要么明写 env bash，别骑墙；
  "先 bash 写完再移植"是反模式。
- bashism 检查交给 shellcheck --shell=sh（100 节）+ CI dash 跑 bats
  （95 节）；人眼审 300 行必漏。

## 4. zsh 交叉陷阱：1 起始数组与默认不切词

- 场景：终端默认 zsh，人肉把 bash 片段粘进交互窗、或把 bash 函数塞进
  .zshrc——shebang 脚本本体不受影响，受伤的是"交互式借用"。
- 最大语义反转：zsh 默认不做 word splitting——`args="a b"; $args` 在
  bash 是切词执行，在 zsh 把整串当一个参数；数组下标从 1 起。
- 其他高频差：read 进数组是 read -A（bash 是 -a）；mapfile 无对应物；
  $0 在函数内语义不同。SH_WORD_SPLIT 能恢复切词——别开，那是续命开关。

```bash
args="a b c"
print -r -- $args        # zsh: 一个参数 "a b c"；bash: 三个词
print -r -- "${=args}"   # zsh 显式切词
print -r -- "${arr[1]}"  # zsh 的首元素（bash 是 ${arr[0]}）
```

- 纪律：运维脚本带 shebang 进版本库，禁止"粘到终端跑"；接手现场先
  `emulate -L zsh` 收敛选项，再查有没有人全局开过 SH_WORD_SPLIT。

## 5. POSIX 模式与 BASH_ENV/ENV 双刃剑

- bash 以 sh 名义启动（或 set -o posix）进 POSIX 模式，差异一整批：
  echo 收回 flag、特殊内建前的赋值退出后保持、time 关键字不可用——
  "sh 里跑 bash 写法"多栽在模式差而非解释器差。
- BASH_ENV：非交互 bash 启动先 source 它指向的文件——cron/CI 注入严格
  模式与函数库的官方通道；GitHub Actions 就是靠它把 setup action 的
  export 带进每一步（109 节）。
- 同一通道即攻击面：能改你环境的人就能让任何 `bash -c` 先跑他的代码
  ——BASH_ENV 文件 root 属主、0644、路径硬编码，绝不放可写目录。
- ENV 是 POSIX sh 对应物但只对交互式生效：dash 非交互什么都不读——
  "我在 .bashrc 配了别名为什么 cron 不认"的根源（108 节）。

```bash
# crontab 顶部：BASH_ENV=/etc/company/ci.bash
cat > /etc/company/ci.bash <<'EOF'
set -Eeuo pipefail
shopt -s inherit_errexit 2>/dev/null || true
EOF
```

- 反向注意：往 BASH_ENV 里 printf 秘密，等于让秘密进每个 bash 步骤的
  xtrace（99 节）——环境文件只放开关与路径，不放值。

## 6. BusyBox 与嵌入式环境生存

- Alpine、initramfs、OpenWrt：/bin/sh = busybox ash；一个二进制，所有
  applet 靠 argv[0] 或 `busybox CMD` 进入。
- 选项面窄：GNU 长选项（--null、--line-buffered）大多没有；sed -i 有
  但后缀行为按版本浮动——短选项 + POSIX 语义，部署前 `CMD --help`
  实测，别信记忆。
- busybox awk 是精简 awk：无 asort/gensub/strftime/PROCINFO（50 节）；
  grep 无 -P；反而 timeout/flock/mktemp 常有——探测而不是猜。
- distroless/scratch 镜像根本没有 shell：要跑脚本就 COPY --from=busybox
  静态 busybox，或改无 shell 的 ENTRYPOINT 数组。

```bash
for t in flock timeout mktemp; do command -v "$t" >/dev/null || need "$t"; done
command -v gawk >/dev/null && AWK=gawk || AWK=awk   # gawk 特性确认后再启用
```

- musl 与 glibc 差异藏在细节：iconv 字符集缺、正则库边界不同——同一段
  正则两边匹配不同时先怀疑 libc/引擎。CI 放一个 alpine job 跑同一套
  bats（95 节）。

## 7. Shebang 纪律与解释器定位

- `#!/usr/bin/env bash` 走 PATH：NixOS、brew 必须用；代价是 PATH 污染时
  选错解释器（conda 先装老 bash 的经典事故）。`#!/bin/bash` 绝对路径：
  Linux 可预测，NixOS 没有。Linux-only 用绝对路径，跨平台用 env。
- 内核 shebang 只传一个参数：`#!/bin/bash -eu` 合法（-eu 一个词），
  `-euo pipefail` 不可能——标准姿势：shebang 裸写 + 体内第一行
  set -Eeuo pipefail（18 节），还兼容 `bash script.sh` 直跑。
- `#!/usr/bin/env -S bash -euo pipefail` 用 GNU env -S 拆参（8.30+）：
  可用，评审时当"已知怪癖"。

```bash
# 入口守卫：直接执行才跑 main，被 source 只暴露函数（source 时 exit 会杀调用者）
if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  main "$@"
fi
```

- `sh bash脚本.sh`、`bash posix脚本.sh` 都是自找的语义漂移；部署文档把
  执行命令写死成一行，比注释里十句警告都管用。

## 8. 能力探测与优雅降级

- 存在性探测用 command -v（POSIX 内建）：which 是外部命令且行为各异，
  淘汰；分类用 type -t（file/function/builtin/alias）。
- 版本门控用 BASH_VERSINFO 数组，不解析 $BASH_VERSION 字符串；外部工具
  版本启动期测一次缓存——热循环反复 `--version | awk` 是自造性能黑洞。
- GNU/BSD 探测惯例：`sed --version 2>&1 | head -1 | grep -q GNU`；macOS
  fallback 到 g 前缀（gsed/gtimeout/greadlink），文档写明 brew
  coreutils 是基线（92 节）。
- 探测失败要响亮：need() 缺依赖打 stderr + exit 69（EX_UNAVAILABLE，
  21 节），别让下游拿空值跑出离谱结果。

```bash
need() { command -v "$1" >/dev/null 2>&1 || { printf 'missing dependency: %s\n' "$1" >&2; exit 69; }; }
need jq
[[ ${DRY_RUN:-0} == 1 ]] && RUN=(echo) || RUN=()   # 布尔开关标准化（14 节）
```

- 坑：bash 缓存命令的 PATH 查找（hash 表）——脚本中途替换同名二进制
  后仍跑旧路径，`hash -r` 清缓存；自更新型 CI 脚本常客。

## 9. 内建与外部命令的边界

- 内建的价值：零 fork（75 节）+ 语义完整。kill 内建认作业号 %1，
  /bin/kill 不认；printf/echo/test/[ 是内建，与 PATH 上外部版行为可能
  不同——`xargs echo`、`find -exec echo` 走的都是外部 echo。
- 查找顺序：函数 → 内建 → PATH。`command cmd` 跳过函数（绕开包装函数
  调真身）、`builtin cmd` 只认内建、`enable -n cmd` 禁用内建。
- time 是关键字不是命令：能修饰整条管道，能用大括号计时 `time { a; b; }`；
  TIMEFORMAT='%3R' 调精度（77 节）。`sudo cd` 永远无效——ulimit/umask/
  cd/export 只能是内建，这类组合进禁用清单。

```bash
type -t kill          # builtin
type -a printf        # builtin + /usr/bin/printf 都列出
kill %1               # 只有内建 kill 认作业号
command rm -rf "$x"   # 显式绕开可能存在的 rm 包装函数
```

- `export -f fn` 把函数导出给子 bash 进程（xargs worker 继承，61 节）；
  dash 子进程收不到——跨 shell 传逻辑只有环境变量 + case 分发（31 节）。

## 10. ulimit 与文件描述符边界治理

- Linux 常见默认：nofile 软限 1024。并行扇出（xargs -P 30 × 每任务 3 个
  fd）+ 常驻日志 fd + ssh 复用通道同时开时，"Too many open files" 是
  必然不是意外。
- `ulimit -n 4096` 提软限（不超硬限，硬限常见 4096~1M）；服务化进程交给
  systemd LimitNOFILE=，比脚本自我提升更可审计。
- `ulimit -u`（nproc）是防爆叉护栏；`ulimit -v` 别碰——地址空间记账会
  误伤 JVM/静态分配型程序。
- fd 泄漏自查：`ls /proc/$$/fd | wc -l` 关键节点前后对比；循环里
  `exec 3<file` 不关闭 = 每圈一个泄漏，关闭是 `exec 3<&-`（74 节）。

```bash
ulimit -n      # 软限；ulimit -Hn 看硬限
ulimit -n 8192 2>/dev/null || printf 'nofile capped at %s\n' "$(ulimit -Hn)" >&2
ls /proc/$$/fd | wc -l    # 当前进程打开数
```

- 容器里改 ulimit 要在 entrypoint 内做：PID 1 的限制决定子进程（44 节）；
  fd 预算与并发设计联动（43 节 ControlMaster 复用）。

## 11. set -e 真实语义：失效位置全图

- 豁免区（规范级）：if/elif/while/until 条件体、`&&`/`||` 列表中"非
  实际执行的最后一条"、`!` 取反后的命令、管道非末段（13 节）、**任何
  trap 动作执行期间**——失败不退出，只把状态往外传。
- 短路推演法：`false && echo x` 不死（echo 没跑）；`true && false` 死
  （最后实际执行的是 false）。评审按"最后实际执行的那条"逐句推。
- 上下文感染：函数在条件位置被调用（`if safe_div; then`），体内 -e
  全程失效——"开了 set -e 函数里 false 却没死"的标准答案。
- 命令替换：`x=$(false)` 让赋值失败 → -e 触发；但 local/declare/export
  前缀掩蔽（12 节）；cmdsub 内部不吃外层 -e，除非
  `shopt -s inherit_errexit`（4.4+，默认关）。

```bash
set -e
false && echo never        # 不死：短路 + 非最后执行
true && false              # 死：最后执行的是 false
if false; then echo no; fi # 不死：条件豁免
foo() { false; echo alive; }
if foo; then :; fi         # 函数体内 -e 失效：alive 被打印
```

- 结论：-e 是"未捕获异常兜底"，不是错误处理——关键步骤要么条件外
  裸调，要么显式 `|| die`（20 节）。

## 12. local/declare 掩蔽：最阴险的 -e 盲区

- `local x=$(false)`：local 自身返回 0，命令替换的失败被整个吞掉——
  set -e 全盲。同罪：declare、export、readonly、typeset；shellcheck 的
  SC2155 专抓这一条（100 节）。
- declare 在函数里还有作用域陷阱：函数内 `declare x=1` 建的是局部变量
  ——想设全局用 `declare -g`（4.2+）或裸赋值；local 在函数外是语法
  错误，declare 不是。
- `export VAR=$(cmd)` 双雷同体：既掩蔽失败、又在子 shell 语义下求值；
  PATH 组装型引导脚本的高频翻车点。

```bash
bad()  { local now=$(date +%s); }        # date 失败？没人知道
good() { local now; now=$(date +%s); }   # 失败 → 赋值失败 → -e 触发
```

- 连锁：pipefail 也救不了它——管道状态先被"声明符赋值"吞掉，才轮到
  -e 看见 0。评审规则：凡"声明符 + $( )"同行，必改。

## 13. pipefail 深水：管道与命令替换边界

- 语义：管道返回"最右边一个非零"的退出码，全零才是零。没它，管道只看
  末段——`curl | tar` 里 curl 死掉被 tar 的成功整段掩盖，是管道时代
  最大的谎言。
- 141 噪声：`grep pat big.log | head` 里 grep 被 head 提前关管 → SIGPIPE
  141 → pipefail 下整条"失败"。逻辑没错时点名豁免 `|| true`，或换
  `sed -n '1,Np'`（sed 全量读不早退，39 节）。
- find 遇权限错退出 1：老管道 `find / | cpio` 在 pipefail 下重新"学会
  报错"——改造旧脚本时预算这批新增失败，别当回归。
- cmdsub 叠加：`out=$(cmd1 | cmd2)` 管道失败传给赋值——只要别用 local
  同行（12 节），-e 能接到。dash 无 pipefail（3 节）。

```bash
set -o pipefail
curl -fsSL "$url" | tar -xz                # curl 死不再被掩盖
grep -m1 fatal huge.log | head -1 || true  # 141 是预期：点名豁免
```

- 与 && 链正交：pipefail 改的是"管道"返回值，不改 && 豁免规则（11 节）
  ——两套规则叠乘，评审时拆开逐段推。

## 14. set -u 与 ${var:-} 家族精确语义

- ${var:-} 与 ${var-} 不是一个东西：前者 unset **或空**都给默认，后者
  只管 unset。分不清会把"显式空值"当"没配置"重置——配置处理的经典
  回归（显式关闭 vs 默认是两个语义）。
- 探测三件套：`[[ -n ${var:-} ]]` 非空才走；`[[ ${var+set} ]]` 只判
  set（空串也算 set）；`${var:?msg}` unset 或空即死并打印 msg。
- 位置参数：set -u 下 `"$1"` 未传即炸——统一 ${1:-} 或先 `(( $# )) ||
  usage`；剩余参数转发只有 "$@"（25 节）。

```bash
: "${API_URL:?API_URL required}"    # 没有就死，消息即文档
TOKEN="${TOKEN:-}"                   # 允许显式空（后续按空语义分支）
[[ ${DEBUG+set} == set ]] && set -x  # 只认"设没设"，空也算设
```

- ${!indirect} 在 -u 下目标未设 → unbound 报错；nameref（declare -n，
  31 节）是更可读的替代。数组另有一章（15 节）。

## 15. set -u 与数组：空数组扩展历史坑

- bash < 4.4：已声明但空的数组 `arr=()`，在 set -u 下 `"${arr[@]}"` 报
  "unbound variable"（4.4 才修）。目标含 macOS 3.2 或老 RHEL bash 的
  代码必须防这一手。
- 双保险咒语 `${arr[@]+"${arr[@]}"}`：外层只测 set 与否（不触发扩展
  报错），set 了才安全内层展开。丑，但 3.2~4.4 全通吃。
- 未声明数组仍应报错（那是真 bug）——双保险只在"可能合法为空"的位置
  出现，别当全局习惯。
- ${#arr[@]} 与 ${!arr[@]} 同受管辖：空数组键列表同样要 4.4+ 才不炸；
  空数组判定用 `((${#arr[@]}))`。

```bash
set -u
files=()
for f in "${files[@]}"; do :; done               # bash 3.2/4.2 在此翻车
for f in ${files[@]+"${files[@]}"}; do :; done   # 全版本安全
((${#files[@]})) || printf 'no files\n' >&2
```

- 迁移注记：升到 4.4+ 后补丁是无害冗余；CI 矩阵留着 3.2/4.2 通道，
  它就一天不能删（1、95 节）。

## 16. ((i++)) 暗杀：算术返回值与 set -e

- 算术命令的返回值 = 表达式值是否非零：值 0 → 退出码 1。i=0 时
  `((i++))`：后缀取旧值 0 → 状态 1 → set -e 当场杀循环。症状极具
  特征："跑完第一圈就死"或"第一圈都没跑完"。
- 修法三选一：`((++i))`、`i=$((i+1))`、`((i+=1))`。团队统一一种，防
  评审噪音。
- 同族雷：裸 `((x))` 在 x=0 时也是状态 1（判零本身就是非零为真）——
  `if ((x))` 是正确用法；let 是算术命令的字符串马甲，同坑同药。
- for ((i=0;i<n;i++)) 三段式在条件上下文，完全安全——坑只在"语句
  位置的自增"；计数器先显式初始化（-u 不管算术里的未设值）。

```bash
set -e
i=0
((i++))                             # 死在这：值 0 → 退出码 1
((++i)); i=$((i+1)); ((i+=1))       # 三种正确姿势
if ((count)); then echo nonzero; fi # 判零放条件上下文
```

- 取证口诀：脚本死在循环第一圈、死行是自增——先查算术状态，再查逻辑。

## 17. ERR trap 继承：errtrace 与函数边界

- ERR trap 只在 -e 会触发的位置触发（豁免区同 11 节）——它不是"任何
  失败"的钩子，条件里的失败它一声不吭。
- 默认不进函数：`trap 'log' ERR` 之后调函数，函数内失败不触发——
  `set -o errtrace`（-E）才继承。严格模式标准序里 -E 的存在感就是
  这个（18 节）。
- 子 shell（管道段、$( )）一律重置 trap（37 节）：ERR/EXIT 都不继承；
  子 shell 的失败只能靠外层接状态（22 节）。
- ERR trap 的杀手锏是 $BASH_COMMAND + $LINENO：失败现场原样落日志，
  比 -e 裸死强一个数量级。

```bash
set -Eeuo pipefail
trap 'printf "FAILED: %s (line %s, status %s)\n" "$BASH_COMMAND" "$LINENO" "$?" >&2' ERR
```

- inherit_errexit（4.4+，默认关）：cmdsub 内部继承 -e，`x=$(false; true)`
  这类"内部假成功"当场死；开了它，12 节的声明掩蔽依然存在。
- trap 动作执行期间 -e 豁免：ERR trap 里再失败不会连环退出——trap 里
  不用写 `|| true`，清理逻辑可放心展开。

## 18. 严格模式组合拳：可复用脚手架

- -e 兜底崩溃、-u 拼写/漏传、pipefail 让管道诚实、-E 让 ERR 钩子进
  函数、IFS 兜底分词——任何一行被"临时注释掉"都要留 TODO 与恢复人。
- 助手护栏：die/usage（20 节）、need（8 节）、统一日志（103 节）——做成
  公司级 bootstrap 经 BASH_ENV 注入（5 节）或 vendor，别每人抄变体。
- 生态：shellharden 自动加引号（POSIX 的 ${var-val} 同源）；Oils/OSH 是
  修复 -e 空洞的方言方向——了解即可，生产仍是严格模式 + shellcheck
  双保险（100 节）。

```bash
#!/usr/bin/env bash
set -Eeuo pipefail                            # E=errtrace：ERR trap 进函数
shopt -s inherit_errexit 2>/dev/null || true   # 4.4+；老 bash 静默跳过
IFS=$'\n\t'                                    # 兜底：漏引号也大概率不切词（26 节）
```

- 脚手架之上仍要显式处理：-e 不是错误处理（11 节）——关键调用 `|| die`
  或裸调吃 -e；清理全部登记进 EXIT trap（106 节）。
- 老脚本迁移次序：shellcheck + ERR 日志（不退码）→ 开 -e/-u → 最后
  pipefail；一次全开 = 三倍事故面同日引爆（19 节）。

## 19. 什么时候不该开严格模式

- 容错批处理：N 个任务一个失败不该全停——每项 `if ! backup "$x"; then
  log_fail; ((fail++)); fi`，末尾按失败数给退码（107 节）。-e 全程关、
  显式检查，这是"检查式脚本"的正统形态。
- 包裹不可靠第三方：老安装器、抖动网络命令——`set +e; out=$(flaky
  2>&1); rc=$?; set -e` 定界捕获，或 `( set -e; ... )` 子 shell 收口
  状态，别让外层陪葬。
- 长驻守护脚本：信号驱动（36 节），-e 会让一次子命令抖动杀掉 daemon；
  用重试循环 + ERR trap 日志替代退出。
- 最危险的是半吊子：开了 -e 但函数全在 if 里调用 = -e 从未生效。要么
  真严格（11 节），要么文件头显式声明"本脚本显式检查，不依赖 -e"。

```bash
# 松散批处理的标准收口：失败聚合 + 阈值退码
fail=0
for p in "${projects[@]}"; do
  if ! deploy_one "$p"; then printf 'deploy failed: %s\n' "$p" >&2; ((fail++)); fi
done
((fail == 0)) || exit 70     # EX_SOFTWARE：批量失败聚合（21 节）
```

- 松散模式的纪律代价：每条命令后有状态处理或注释说明为何忽略——
  "懒得管"不算理由，评审逐行问。

## 20. 失败要响亮：die 与 usage 模式

- die 三原则：只进 stderr、带脚本名、一行一事实；usage 退出码用 64
  （EX_USAGE）不是 1——调用方按 sysexits 契约分支（21 节）。
- die 配 ERR trap 双保险：ERR 记位置（$BASH_COMMAND/$LINENO，17 节），
  die 记语义。die 里不做清理——清理归 EXIT trap（34 节），die 里 rm
  是双跑事故的起点。
- 早退结构：参数解析最先、`(( $# )) || usage` 兜底、root 检查用内建
  EUID 而不是 $(id -u) 每 fork 一次（75 节）。

```bash
die() { printf '%s: %s\n' "${0##*/}" "$*" >&2; exit 1; }
usage() { cat <<EOF
usage: ${0##*/} [-n] <project>
EOF
  exit 64; }
(( $# )) || usage
[[ $EUID -eq 0 ]] || die "root required"
```

- die 的信息带语境：失败原因 + 关键参数摘要；别只打 "error occurred"
  ——三个月后你自己就是看不懂日志的人。bats 断言 usage 见 23 节。

## 21. 退出码契约：sysexits 与 126/127/128+N

- 退出码是脚本的 API：cron/systemd/Makefile/上游脚本都靠它分支，全 1
  等于全没有。约定表：0 成功；2 通用错误（惯例）；64 EX_USAGE、65
  EX_DATAERR、66 EX_NOINPUT、69 EX_UNAVAILABLE、70 EX_SOFTWARE、74
  EX_IOERR、75 EX_TEMPFAIL、77 EX_NOPERM。
- shell 固有区段别占用：126 = 找到但不可执行、127 = 未找到、128+N =
  被信号 N 杀（130=INT、137=KILL、143=TERM）。
- exit 只取低 8 位：`exit 300` 实际退 44（300%256）——退码来自变量时
  `exit $((rc % 256))` 防御。
- 75（EX_TEMPFAIL）最值钱：锁冲突、上游 503、临时 IO——调用方与
  systemd Restart=on-failure 都能识别"暂时"，与永久失败分流。

```bash
readonly EX_OK=0 EX_USAGE=64 EX_SOFTWARE=70 EX_TEMPFAIL=75
flock -w 30 /run/lock/migrate.lock "$0" _worker "$@" || exit 75
```

- 把退码表写进 usage/README 并让 CI 断言（23 节）——契约没有测试
  等于没有契约（107 节）。

## 22. PIPESTATUS 取证与中段失败

- PIPESTATUS 是"用一次废一次"的数组：下一条命令立即覆盖。落地第一句
  就是 `st=("${PIPESTATUS[@]}")`，再谈别的。
- 中段失败取证：sort/awk 中段炸时 stderr 混流，PIPESTATUS 告诉你"哪
  一段"；配 ERR trap 打 BASH_COMMAND（17 节）定位到行。
- pipefail 开着 PIPESTATUS 仍在：前者决定整条状态，后者给全谱——调试
  期打印整个数组，别猜。
- dash 无 pipefail 也无 PIPESTATUS：关键命令挪末位，或拆两步用临时文件
  交接（81 节）。函数内管道 + 函数被条件调用 = -e 全豁免（11 节），
  状态在函数边界蒸发——函数内即时保存并显式 return。

```bash
tar cf - "$dir" | ssh "$host" 'tar xf - -C /srv'
st=("${PIPESTATUS[@]}")
(( st[0] == 0 )) || die "local tar failed: ${st[0]}"
(( st[1] == 0 )) || die "remote extract failed: ${st[1]}"
```

## 23. 严格模式可测试性：故障注入与 bats

- bats-core 是事实标准：@test 块、run 捕获、assert_output/assert_equal；
  `run -64 ./script` 直接断言退码——21 节的契约表逐条变成测试。
- mock 用 PATH 注入：临时 bin 目录放同名假命令（控制退码/输出），测试内
  `PATH="$stub:$PATH"`——比函数包装干净，覆盖真实子进程调用路径。
- 故障注入：桩脚本第 N 次调用失败（计数文件实现）——重试逻辑、75
  退码、降级分支被真实触发，而不是"看起来会走"。
- 清理正确性测试：故意 timeout -k 中途杀被测脚本，断言临时文件/锁
  消失（106 节）——比 happy path 值钱十倍。
- 矩阵化：同一 bats 在 ubuntu:24.04（bash 5.2）、20.04（5.0）、debian
  （dash as sh）、alpine（ash）各跑（95 节），差异在 CI 显形。

```bash
@test "usage exits 64" {
  run -64 ./deploy.sh
  assert_output --partial 'usage:'
}
@test "missing dep exits 69" {
  PATH="$BATS_TEST_TMPDIR/stub:$PATH" run -69 ./deploy.sh
}
```

- bats 本体是 bash：dash 专用场景用 docker 跑 `sh script` 再断言
  $status——测试器与被测对象分离。

## 24. 分词内部机制：IFS 的双重角色

- IFS 字符分两类，规则完全不同：空白类（空格/Tab/换行）连续折叠、
  首尾剥除；非空白类（冒号、逗号…）每个出现切一刀，相邻两个产生
  **空字段**——IFS=: 下 "a::b" → a、""、b。
- 生效范围：无引号的 $var、$( )、无引号数组扩展；引号内永不切分。
- 时序：参数扩展 → 分词 → pathname 扩展（glob）——无引号变量除了被切
  还可能被当通配符展开（87 节），双重灾难同源。
- 一次性 IFS：`IFS=: read -r a b c` 的 IFS 只作用于 read（内建前缀赋值
  作用域）——全局改 IFS 前先备份并尽快恢复。

```bash
line="root:x:0:0::/root:/bin/bash"
IFS=: read -r user _ uid gid _ <<<"$line"   # _ 吃掉多余字段
printf 'user=%s uid=%s\n' "$user" "$uid"
IFS= read -r raw < file                     # 保空白：IFS= 前缀 + -r（72 节）
printf '%s' "$IFS" | od -An -c              # 取证当前 IFS
```

- 坑：read 不带 IFS 前缀时按默认切 + 剥——原样读行必须 IFS= read -r，
  这是分词机制在 read 上的投影。

## 25. "$*" vs "$@"：连接与独立的边界

- "$*"：全部位置参数用 IFS 首字符（默认空格）拼成**一个词**；"$@"：
  每个参数各自成词。转发参数只有 "$@" 无损。
- 无引号形态 $*/$@ 都会再切词 + glob——新代码禁绝（SC2048/SC2086）。
- 零参数边界："$@" 展开为零个词（不是空串）——`cmd "$@"` 无参时等价
  `cmd` 不带参，这是包装器透明转发的根基；"$*" 恒为一个词。
- 数组镜像：${arr[*]}/${arr[@]} 同规则（27 节）；拼串正规姿势是临时改
  IFS：`IFS=,; joined="${arr[*]}"`。

```bash
set -- a "b c" d
printf '[%s]' "$*"     # [a b c d] —— 一个词
printf '[%s]' "$@"     # [a][b c][d] —— 三个词
exec cmd "$@"          # 唯一正确的转发（exec 交权见 34/44 节）
```

- 经验法则：日志打印用 "$*"（人读一行），执行转发用 "$@"（机器语义）
  ——两者同函数出现时加注释，防"顺手改错"。
- 一句话：* = 连接后单数、@ = 保持复数；引号是语义开关，无引号版本
  两个都不是。
## 26. IFS=$'\n\t' 惯用法的收益与暗面

- 兜底收益：设 `IFS=$'\n\t'` 后，无引号 `$var` 展开不再被空格/Tab
  切词——`for f in $files` 在文件名带空格时的惨案被兜住一层。
- 但它**只挡切词不挡 glob**：$var 里含 `*` 照样展开成文件列表——
  切词与 glob 是两个独立阶段（24 节），此招只关了前一道闸。
- read 语义漂移：该 IFS 存续期间 `read -r line` 会把行尾 Tab 一并
  剥掉——处理空白敏感数据（TSV、代码）时是**静默数据损坏**。
- 作用域纪律：只在函数内 local、或 `( IFS=$'\n\t'; ... )` 子 shell
  内生效；写在脚本顶部的全局修改会污染 source 它的一切后来者。
- 潜台词是"我在用字符串当列表"：现代写法 mapfile -t + 数组（27 节）
  才是正路，字符串循环是无结构数据的遗风。
- 评审成本真实存在：新人见到这行会困惑、会"顺手删掉"或到处复制
  ——要留就注释写明收益与边界，否则别留。

```bash
IFS=$'\n\t'                        # 只在局部作用域出现
for f in $untrusted_list; do ...; done   # 兜切词，不兜 glob
mapfile -t files < <(find . -type f | sort)
for f in "${files[@]}"; do ...; done     # 正路：无切词无 glob
```

- 一句话：收益真实、暗面更真实——防御性遗风，新代码能不用就不用。

## 27. 数组安全操作全谱：扩展、键、unset 引号

- 攒 argv 的唯一正形：`args=(--exclude "$f" --timeout 30)`，调用
  `cmd "${args[@]}"`——每个元素独立成词，空格/通配符原样传递。
- 长度对照："${#arr[@]}" 是元素个数，"${#arr}" 是 **arr[0]** 的长度
  ——拼写差一步语义差千里，review 重点盯防对象。
- `unset 'arr[1]'` 的引号是硬要求：无引号时下标先做 glob 展开，当前
  目录恰有名为 1 的文件时会删错下标——静默错位，纯靠评审拦截。
- 切片与负索引："${arr[@]:1}"、"${arr[@]: -2}"——负数前**必须空格**，
  否则 ${arr[@]:-2} 解析成 :- 默认值扩展（29 节），南辕北辙。
- 稀疏数组：arr=([0]=a [10]=b) 时 ${#arr[@]}=2 而最大下标是 10——
  需要真实键列表时用 "${!arr[@]}"，别假设下标连续。
- set -u 下的空数组：bash 4.4 之前 "${arr[@]}" 对空数组报 unbound——
  兼容写法 `${arr[@]+"${arr[@]}"}`；底线定 4.4+（95 节）就直用。

```bash
args=()
for f in "$@"; do args+=("--include=$f"); done
grep "${args[@]}" pattern file      # 结构传递，无再解析
unset 'args[0]'                     # 引号防下标 glob
first=("${args[@]:0:1}")            # 切片返回数组，别丢引号
```

- 数组不可导出：跨进程传数组只能走字符串（30 节 %q 再引号）或 NUL
  分隔流配 `mapfile -d ''` 接收。

## 28. 关联数组与遍历序不确定性

- `declare -A m` 是开关不是装饰：漏写 -A 时 m[key] 的 key 被当**算术
  表达式**求值——键名非数字时静默归零或算错，不报错。
- 遍历序 = 哈希序：同一段代码两次运行都可能不同序——凡是输出给人
  看、给下游 diff 的遍历，必须显式排序，这是硬性评审项。
- 键排序落地：`printf '%s\n' "${!m[@]}" | sort` 先排键再回查值；或
  `readarray -t ks < <(printf '%s\n' "${!m[@]}" | sort)` 留键数组。
- 存在性判空正形 `${m[k]+x}`：`[[ -n ${m[k]+x} ]]` 全版本可移植；
  [[ -v m[k] ]] 是 bash 4.3+。取值式 ${m[k]} 不建项，但算术上下文
  `((m[k]++))` 会凭空造出元素——一处之差。
- macOS bash 3.2 无关联数组（2 节）：降级方案是 "key<TAB>value"
  临时文件 + awk/while read，别硬写 bash 4 特性。
- 无法导出、无法直接传子进程：跨边界传键值对列表或序列化字符串
  （27 节末条同款约束）。

```bash
declare -A count
for w in "${words[@]}"; do ((count[$w]++)); done
for k in "${!count[@]}"; do
  printf '%s\t%d\n' "$k" "${count[$k]}"
done | sort -k2,2nr          # 排序后再见人
[[ -n ${count[danger]+x} ]] || echo "key absent"
```

## 29. 参数扩展军火库：:-、:?、:+ 守卫

- ${var:?msg} 是 rm 的法定保镖：var 为空/未设时打印 msg 并退出——
  `rm -rf "${root:?root 未设置}"` 把"空变量删全盘"挡在展开期，
  报错信息远比 set -u 的堆栈可读。
- ${var:-def} 借默认值不改 var；${var:=def} 顺手赋值（**不可用于
  $1..$9**：bash 直接报 "cannot assign in this way"）；${var-} 与
  ${var+x} 是判空/判设的成对组合件。
- ${var:+repl}："非空才吐 repl"——条件传参正形 `cmd ${verbose:+-v}`，
  引号写全 "${verbose:+-v}"，否则 verbose 含空格时又切词。
- 负偏移空格雷：${var: -3} 取尾 3 字符，空格不可省——写成 ${var:-3}
  就成了默认值 3，这对孪生是复制粘贴惯犯。
- ${var/pat/rep} 是 **glob 不是正则**：* ? [a-z] 语义，单 / 换首个、
  // 换全部；替换串里 & 不特殊（与 sed 相反）、无捕获组——要组上
  gawk gensub（50 节）或改走 sed。
- 大小写 operator bash 4+：${var^} ${var,,}——3.2 世界别碰（2 节）。

```bash
: "${CONFIG_DIR:=${XDG_CONFIG_HOME:-$HOME/.config}}"
rm -rf "${BUILD_DIR:?BUILD_DIR must be set}"/*
tar czf "${out:-backup.tar.gz}" "${src:?}"
rsync -a ${dry_run:+--dry-run} src/ dst/
tail3=${path: -3}            # 空格是语义的一部分
```

## 30. printf %q 与 ${var@Q} 再引号技术

- %q 输出的是 **bash 方言**：含控制字符时产 $'a\tb' 带 $ 前缀形态
  ——喂给 dash/sh 直接语法错，跨 shell 场景一票否决。
- ${var@Q}（bash 4.4+）输出 POSIX 单引号包裹：`'a b'`、内嵌单引号
  转成 '\''——任何 POSIX shell 都能安全 eval 回来，跨 shell 首选。
- 核心用途是**生成 shell 代码**：把运行期变量焊成安全字面量——远端
  脚本组装（43 节）、eval 兜底（31 节）、可重放的审计日志。
- 诊断回显：`printf '%q ' "$@"` 原样打印 argv，复制粘贴回终端即可
  精确重放——比肉眼拼引号的日志强一个量级。
- xtrace 内幕：set -x 打印的就是同源的再引号结果——看懂 xtrace 行
  里的引号，等于看懂 %q；BASH_XTRACEFD 单独引流见 38 节。

```bash
printf 'cmd: %q\n' "$@"            # 审计日志，可粘贴重放
q() { printf '%q ' "$@"; echo; }
eval "deploy $(q "$env" "$tag")"    # %q 兜底 eval（31 节）
ssh "$host" "SCALE=${scale@Q} bash -s" <<'EOS'
  docker service scale "web=$SCALE"
EOS
```

- 生成给非 shell 消费的东西别用 %q：JSON 上 `jq -Rs .`，各语言有各
  语言的转义，串用必炸。@Q 之外还有 @P/@A/@E 同族操作符，用到再查。

## 31. eval 的现代替代方案

- eval 的罪状是**二次解析**：字符串先展开再当代码跑，任何一层变量含
  元字符即成注入面——"我控制了输入"这个假设在运维脚本里从未成立。
- case 分发替代 eval "$cmd"：命令名单化、显式枚举、default 分支
  拒绝——可测、可审计、报错信息友好。
- 动态变量名用 nameref：`declare -n ref=$name; ref=value`（bash
  4.3+）替代 eval "$name=value"；只读间接引用 ${!name} 即可。
- 动态 argv 用数组（27 节）替代 eval "cmd $args"：参数按结构传递，
  根本不经过解析器，天堑变通途。
- 真·执行字符串用 argv 法：`bash -c 'cmd "$@"' bash "$@"`——代码与
  数据分离，cmd 内用 $1/$2 引用而不是拼字符串。
- 兜底仍是 %q：历史包袱型接口必须 eval 时，用 printf %q 把参数全部
  重新引号后再 eval（30 节），注释写明"受控二次解析"。

```bash
# 反例：eval "mount $src $dst" —— $src 含 ; 即任意代码执行
case "$action" in
  start) do_start "$svc" ;;
  stop)  do_stop "$svc" ;;
  *)     die "unknown action: $action" ;;
esac
bash -c 'docker run --rm "$@"' bash "$img" "${args[@]}"
```

## 32. 命令替换：换行剥离与字节边界

- $(...) 剥掉**全部**尾换行："a\n\n\n" 变成 "a"，不止剥一个——要
  保留尾部空行的数据走临时文件或进程替换，别指望 $()。
- 保尾换行惯用法：`x=$(cmd; echo x); x=${x%x}`——追加哨兵再砍掉，
  换行原样保留；md5/base64 对接的精密场景必备。
- NUL 是死线：输出含 \0 的命令过 $(...) 直接丢字节——find -print0
  的成果不能这样接，用 <(...) 管道流式消费（下方代码）。
- $(< file) 免 fork 读文件：等价 $(cat file) 但少一个 cat 进程，热
  路径收益明显；尾换行剥离规则同样适用。
- 赋值保留退码：`x=$(cmd)` 后 $? 即 cmd 的退出码——`if ! x=$(cmd)`
  的判断写法成立，不必拆成两步。
- 括号内禁写行尾注释：`$(cmd # note)` 的 # 会连右括号一起吞掉报
  unexpected EOF——注释写在替换外面。

```bash
ver=$(git describe --tags)        # 尾换行没了，正合适
x=$(printf 'a\n\n'); echo "[$x]"  # [a] —— 全剥
x=$(cmd; echo x); x=${x%x}        # 哨兵法保尾换行
while IFS= read -r -d '' f; do ...; done \
  < <(find . -name '*.log' -print0)   # NUL 流不过 $()
```

- 需要文件名而非字符串时用 <(...)：`diff <(sort a) <(sort b)`。

## 33. here-doc/here-string 的引用规则

- 定界符引号是**展开开关**：<<EOF 展开 $var/$(...)，<<'EOF' 全字面
  ——喂给别的解释器（SQL、Python、远端 sh）的正文一律加引号，把
  替换机会留给显式占位。
- <<- 只剥 **Tab**：缩进写成空格的编辑器里它什么也不剥，正文混着
  尾随 Tab 进解析器——团队要么全 Tab 要么不用 <<-，写进规约。
- <<< word 自带一个尾换行：`read -r a b <<<"$line"` 顺手，但字节
  精确的场景（计数、校验和）记住多出的 \n；$var 照常展开，要字面
  就 <<<'...'。
- sudo 交权模式：`sudo tee "$dest" >/dev/null <<EOF`——绕开 "sudo
  重定向不生效" 的经典误解，比 sudo sh -c 'echo ...' 干净。
- ssh 组装：here-doc 喂 `ssh host bash -s`，本地变量先 ${var@Q}/%q
  再引号嵌入（30 节），远端零解析惊喜。

```bash
mysql "$db" <<'SQL'               # 引号=不展开，防 $ 注入 SQL
SELECT * FROM users WHERE id = 100;
SQL
sudo tee /etc/sysctl.d/99.conf >/dev/null <<EOF
net.core.somaxconn = ${somaxconn}
EOF
read -r key val <<<"$line"
```

## 34. trap 基石：EXIT 单次执行与双触发

- EXIT trap 保证**至多一次**：正常结束、exit、set -e 触发、信号
  致死都只跑这一份——清理逻辑只写 EXIT 一处，别的 trap 负责"制造
  退出"，不负责打扫。
- 双触发经典形态：TERM handler 里直接调 cleanup，随后 exit 又触发
  EXIT trap 再跑一遍 cleanup——重复删文件/重复发通知。铁律：信号
  handler 只 `exit N`，打扫交给 EXIT trap 单点执行。
- trap 体内防爆：EXIT trap 内命令配 set -e 失败会**覆盖退出码**
  ——下游重试（75 码）据此误判；trap 首句 set +e 或 || true 兜底。
- exec 之后 trap 蒸发：exec 替换进程映像，一切 trap 消失——交接型
  脚本（44 节容器 exec）必须在 exec 前自己收尾。
- kill -9 不可拦：清理不能建立在"总有机会跑"上——幂等设计（锁带
  $$ 可追杀、临时目录可重用）+ 外部巡检兜底（106 节）。

```bash
tmp=$(mktemp -d)
cleanup() { rm -rf -- "$tmp"; }
trap cleanup EXIT
trap 'echo killed >&2; exit 143' TERM   # 制造退出，不打扫
exec 9>"$lock"; flock -n 9 || exit 75   # 险动作前一刻再装 trap
```

- 检验三连：kill -TERM 跑一遍、set -e 注错跑一遍、kill -9 后手查
  残留——三种死法各留下什么，写测试前先人肉回答。

## 35. INT/TERM 处理：128+N 退出码纪律

- 128+N 是给调用方的**死因报告**：130=INT(2)、143=TERM(15)、137=
  KILL(9)。CI 与包装脚本靠它区分"任务失败"与"任务被杀"，改写这个
  语义等于伪造病历。
- 默认很对，别画蛇添足：bash 被 INT 杀死本来退 130；一旦 trap 拦截
  且 handler 里 `exit 0`，外界看到的就是成功——拦截必须配对
  `exit 130`/`exit 143`，写进肌肉记忆。
- kill 用名字不用数字：kill -TERM "$pid"——信号编号跨平台有微差，
  名字是契约；bash builtin 两者都吃，脚本留名字。
- handler 最小化：TERM 标配 `trap 'cleanup; exit 143' TERM`；可中断
  循环场景 INT 只置标志位（36 节），业务循环每轮检查标志。
- 忽略态是特洛伊木马：脚本被 nohup/daemon 以忽略信号方式拉起时，
  对该信号 **trap 注册不上**（37/42 节）——关键信号处理要在 nohup
  环境下实测一次。

```bash
trap 'printf "terminated\n" >&2; exit 143' TERM
trap 'printf "interrupted\n" >&2; exit 130' INT
kill -TERM "$pid" || echo "kill failed: $?" >&2
wait "$pid"; st=$?    # 143=TERM 137=KILL 124=timeout(41节)
```

- systemctl/docker stop 的 "TERM→宽限→KILL" 就是本节语义的系统级
  应用（44 节）；wrapper 收到 143 应向上透传而非当失败重试。

## 36. trap 执行时点与前台子进程

- trap 不是中断，是**命令边界检查**：shell 等当前前台命令返回后才
  执行挂起的 trap——脚本停在 `sleep 3600` 里时 TERM 到了也得等它
  跑完，这就是"杀不动"的第一真相。
- 破局惯用法 sleep&wait：`sleep 3600 & wait $!`——wait 是内建且
  **可被信号打断**，trap 立即执行、wait 返回非零；长等待一律写成
  这个形状。
- read 同理可打断：`read -t 3600 -r answer` 被信号打断后 trap 执行、
  read 返回非零——交互等待不必轮询。
- 前台**外部命令**收不到你的 trap：信号只发给 shell 进程，正跑着的
  curl/tar 要么自己处理，要么 handler 里显式 `kill $!` 补刀（44 节
  转发模板的骨架）。

```bash
stop=0
trap 'stop=1' TERM INT
while (( stop == 0 )); do
  do_tick & wait $! || break   # wait 可被打断，循环立即感知
done
# 反例：trap 'stop=1' TERM; sleep 600   # 要等 600s 后才看见
```

- 诊断口诀："trap 没反应"先 `ps` 看当前前台进程是谁——答案几乎
  总是它。

## 37. 子 shell 的 trap 重置真相

- fork 规则一句话：**重置为默认，除非原本是忽略**——子 shell 继承
  的是信号处置位，自定义 handler 全部清空，`trap '' SIG` 的忽略位
  原样穿透。nohup 的全部魔法就是"先把 HUP 置忽略再 exec"。
- 命令替换/管道两端/显式 ( ) 全是子 shell：父脚本的 EXIT trap 不会
  在 $(...) 里跑，`foo | bar` 的 foo 侧也不会——锁、fd、临时文件
  的清理绝不能指望"子上下文自然带走"。
- 验证只要三行：`trap 'echo P' EXIT; ( echo sub ); echo back`——sub
  退出时没有 P，感官证据胜过手册引用。
- 反向利用：某段代码要信号免疫就 `( trap '' TERM; fragile_cmd ) `
  局部忽略，不污染父作用域；要后代进程免疫，忽略态靠 fork+exec
  自动传播，export 不了信号。
- 子 shell 里重新 trap 是允许的：孙辈 handler 自己装——管道消费段
  需要 SIGPIPE 兜底（39 节）时写在段内。
- EXIT/ERR 都不跨子 shell；DEBUG/RETURN 要 -T 才随函数继承（38 节）
  ——"这个 trap 到底在哪层生效"是评审必答题。

```bash
( trap '' HUP INT; long_task ) &    # 局部免疫，父作用域干净
result=$(child_setup)               # 父 EXIT trap 不进这里
ssh host 'nohup job </dev/null >log 2>&1 &'   # 37+42+43 三合一
```

## 38. DEBUG/RETURN trap 与 -T 追踪

- DEBUG trap 在**每条简单命令前**触发（for 头、条件、赋值都算）——
  自制 tracer 一行：`trap 'echo "$LINENO: $BASH_COMMAND" >&2' DEBUG`，
  零依赖的现场取证手段。
- RETURN trap 默认只在"来源层"有效：脚本里的 RETURN trap 看不见函数
  返回——要随函数继承必须 `set -o functrace`（-T），DEBUG 同理；
  忘了 -T 的"函数级追踪"全在裸奔。
- 性能税真实：每条命令多一次 trap 分发 + $BASH_COMMAND 展开，热循环
  肉眼可见地拖慢——追踪是**按需开关**，默认关，出事再开。
- 多数诉求 set -x 就够：配 `PS4='+ ${BASH_SOURCE[0]}:$LINENO: '` 让
  xtrace 带文件行号；xtrace 写 stderr，用 BASH_XTRACEFD 指到独立 fd
  落盘，不与业务输出互喂。

```bash
set -T                                   # DEBUG/RETURN 进函数
trap 'printf "[D] %s:%s %s\n" \
  "${BASH_SOURCE[0]}" "$LINENO" "$BASH_COMMAND" >&2' DEBUG
exec 7>trace.log; BASH_XTRACEFD=7; set -x # xtrace 单独落盘
trap - DEBUG; set +x                      # 用完全关
```

- extdebug 花活：DEBUG handler 返回非零可**跳过**该命令——单步
  调试器、条件断点的原料；用完记得整体关闭。

## 39. SIGPIPE：管道消费者的正常死亡

- `cmd | head -3`：head 退出后上游写 pipe 得 EPIPE → SIGPIPE → 死于
  141 (128+13)——这是**流控**不是故障，生产者本该为消费者止损。
- pipefail 下的 141 污染：整管道状态非零，"被正常截断"与"真崩"
  混在一起——消费端在前的管道要么豁免 `|| (( st == 141 ))`，要么
  消费端用 awk 找够就 exit（58 节单遍思路）。
- sed -n '1,10p' **不会**提前退：sed 习惯读完输入，10p 后继续吞全量
  ——要真截断写 sed '10q'，上游才收得到 SIGPIPE；head 优于 sed。
- trap '' PIPE 是反向操作：屏蔽后写端收 EPIPE 错误而非信号，程序
  各自报错退出，通常死得更难看——shell 层别碰这个开关。
- 合格的提前退消费者：grep -m1、awk 'NR==3{print; exit}'；sort 是
  全量读者，放管道中段永远吃满——大文件先想清楚谁读多少。
- 非壳生产者的 BrokenPipeError：python 等要自己捕获并退 141 才优雅
  ——shell wrapper 要容忍它的 141，而非当失败去重试。

```bash
curl -sL big.csv | head -5              # curl 侧 141 是预期内
set -o pipefail
bigproducer | head -3 || (( $? == 141 ))   # 豁免正常截断
tar cf - "$dir" | ssh host 'tar xf - -C /srv'  # 慢消费≠SIGPIPE
```

- 排查口诀：管道"莫名 141"先看消费端是否提前 exit——九成是它干得
  好，不是它坏了。

## 40. 进程组与 setsid：负 PID 组信号

- 组的存在意义：一个作业一个 pgid，`kill -- -PGID` 一次命中全组
  ——杀进程树的正规姿势；`kill $PGID`（正数）只发给 PID 恰好等于
  PGID 的那个进程，经常"杀了个寂寞"。
- 负号必须双横线或与数字相连：`kill -- -1234`——否则被当信号名解析
  报错，本节最常翻车的语法点。
- 脚本默认**不在自己的组**：非交互 shell 无作业控制，后台子进程与
  脚本同 pgid——想整组隔离、整组可杀，要么 `set -m` 开作业控制，
  要么起手就 setsid。
- setsid 换会话：新 sid=新 pgid、脱离控制终端、HUP 物理免疫（42 节）
  ——注意 setsid 多已 fork，紧随的 $! 常不是目标进程；要 PID 用
  pgrep -s 会话号或让目标自己写 pidfile。
- 孤儿组照样可杀：组长先死不撤销 pgid，kill -- -PGID 直到组内全灭
  都有效——树杀不依赖根活着。

```bash
setsid bash -c 'sleep 600 & exec sleep 600' &
pgid=$(ps -o pgid= -p "$!" | tr -d ' ')
kill -- -"$pgid"         # 孙进程一锅端
ps -o pid,pgid,sid,cmd -p $pid    # 动手前先看清地形
```

- CI runner/systemd 里脚本常已在独立会话——再 setsid 反而脱离监管
  关系；分层杀树前先看清 pid/pgid/sid 三件套再动手。

## 41. timeout 分层杀法与退出码语义

- 语义表：124=超时（TERM 后死了）；125=timeout 自身失败；126/127=
  命令不可执行/找不到；137=KILL 补刀；128+N=子进程死于信号。测试
  断言与重试策略全靠这张表对号入座。
- -k 是配对件不是可选件：`timeout -k 5 30 cmd`——TERM 后宽限 5 秒
  还活着才 KILL；裸 `timeout 30` 遇上吞 TERM 的老顽固就挂到天荒
  地老，因为无 -k 时只发一次 TERM 不补刀。
- --preserve-status 交还真相：子进程自己退 3 就报 3，真超时才 124
  ——要区分"业务失败"与"超时"的监控必须用它，否则一切失败都像
  超时。
- 组信号内幕：GNU timeout 默认自建进程组，超时信号发给**整个子组**
  （孙进程也吃）——"孙进程吞信号杀不干净"时先查版本再看 setsid
  （40 节）；--foreground 反向放弃组管理，仅交互场景用。
- POSIX 无 timeout：busybox 版缺 -k/--preserve-status（6 节）；手搓
  版 ( cmd & sleep; kill ) 的正确性被 36 节 wait 细节拖累——能依赖
  GNU 就在脚本头写明依赖。

```bash
timeout -k 5 30 curl -sf "$url" -o out     # TERM 不理就 KILL
timeout --preserve-status -k 2 10 ./e2e.sh
st=$?; (( st == 124 )) && echo TIMEOUT     # 143=被TERM 137=被KILL
timeout 10 bash -c 'sleep 5 & wait'        # 组内孙进程一并收
```

## 42. nohup/disown 与 SIGHUP 生命周期

- HUP 的传播路径：终端断开发全会话、会话首（sshd 侧）退出、**bash
  自己收到 HUP 时转发给所有作业**；交互 bash 正常 exit 默认**不**
  发 HUP（要 huponexit），非交互脚本退出从不发——"关终端就死"的
  真凶几乎都是前两条。
- 三档强度分清：nohup=启动时置 HUP 忽略（穿透 fork/exec，37 节）+
  默认落 nohup.out；disown=作业表除名（bash 不再转发、**不可再
  wait**，45 节）；setsid=换会话换组，物理绝缘（40 节）。
- 顺序不可逆：已在跑的进程无法补 nohup——要么它自己 trap '' HUP，
  要么当初就 setsid；disown 只断 bash 的"通知链"，终端级 HUP 若
  打到进程组（路径一），除名也救不了。
- 手工后台四件套是硬礼仪：`cmd >log 2>&1 </dev/null &`——stdin 不
  关可能拖住终端/ssh 不放（43 节同款病）；裸 nohup 静默写 nohup.out
  污染当前目录，评审直接打回。

```bash
nohup ./daemon.sh >"$LOG" 2>&1 </dev/null &   # 档1：忽略 HUP
disown -h %1                                   # 档1.5：可wait免死
setsid ./daemon.sh >"$LOG" 2>&1 </dev/null &   # 档3：换会话
kill -HUP $$     # 自测：检验谁的免疫是真免疫
```

- 长驻服务的正确答案从来不是 nohup：systemd unit、tmux、容器
  ——nohup 族只是"没有 supervisor 时的临时毯子"。

## 43. ssh 挂住：后台进程的 fd 纪律

- ssh 走不出去的头号原因：远端后台进程**继承了 sshd 给的 stdout/
  stderr**，ssh 要等这对管道的所有副本关闭——`nohup cmd &` 少了
  重定向照样挂死：nohup 只管 HUP 不管 fd（42 节）。
- 远端四件套：`nohup cmd >log 2>&1 </dev/null &`——fd 彻底脱钩，
  ssh 立即返回；</dev/null 最常被漏，远端命令一读 stdin 就吃掉你
  本地的输入流。
- 本地循环配 ssh 必加 -n：`while read -r host; do ssh "$host" cmd;
  done` 的经典翻车——ssh 抢走循环 stdin，一次读光主机清单；ssh -n
  或 </dev/null 断其念想。
- ControlMaster 提速一个量级：首连建 socket 后续免握手——批量主机
  脚本标配 ControlMaster/ControlPath/ControlPersist + BatchMode=yes。
- 远端脚本组装：整段单引号包裹 + 变量经 ${var@Q} 重新引号嵌入（30
  节）；或 `ssh host bash -s < script` 走 stdin——都比双引号里塞
  $ 的老写法安全。

```bash
ssh -n "$host" "nohup job >run.log 2>&1 </dev/null &"
while read -r h; do ssh -n "$h" 'uptime'; done < hosts.txt
ssh "$host" "TAG=${tag@Q} bash -s" <<'EOS'
  [ -n "$TAG" ] && docker run -d "app:$TAG"
EOS
```

## 44. PID 1 陷阱：容器中的信号转发

- 内核对 PID 1 关闭默认信号处置：**未注册 handler 的 TERM 直接被
  忽略**——shell entrypoint 没装 trap 时 docker stop 的十秒宽限
  全程无感，最后 KILL，"优雅关闭"全是幻觉。
- 信号只发 PID 1：shell 起的后台子进程收不到 TERM，宽限一到 KILL
  连片——转发要自己写：trap + kill + wait（36 节可中断 wait 骨架）。
- 第二宗罪：**不收割孤儿**——孤儿转投 PID 1 而 bash 只 wait 自己
  的作业，ps 一排 <defunct> 常驻，污染进程表与监控。
- 单进程容器标准姿势 exec：`exec java -jar app.jar` 顶替 PID 1：
  信号直达、退码透传、零僵尸；前提是运行时自己能 reap 孙进程。
- 必须多进程：上 init——tini/dumb-init 或 docker --init（转发信号
  +reap 一条龙）；手写 bash 转发模板可行但要三条全满足：信号转发、
  wait 收割、退码透传。

```bash
#!/bin/bash          # 手工转发模板（能过就换 tini）
app & app_pid=$!
trap 'kill -TERM "$app_pid" 2>/dev/null' TERM INT
wait "$app_pid"; st=$?
trap - TERM INT; wait; exit "$st"   # 收割余党+透传退码
```

- exec 与转发二选一别混用（exec 蒸发 trap，34 节）。体检：`docker
  exec c ps -o pid,ppid,stat,cmd` 查 PPID=1 与 Z 堆积，CI 冒烟断言。

## 45. 子进程收割：wait 家族与孤儿

- 不 wait 的代价：脚本退出时未收割的子进程孤儿化转投 init，**退码
  永久丢失**——批处理脚本的结构性结尾就是起完、wait 全收、再汇总。
- wait $pid 收割并返回其退码；wait（无参）等全部作业且**只返回 0**
  ——要逐个退码就记 PID 清单循环 wait，结构化记账是纪律。
- wait -n 等任意一个结束（bash 4.3+），-p var 顺带报 PID（5.1+）
  ——job pool / 满载调度模式的核心件。
- wait 非子进程/已收割者报 127——别与"命令找不到"混淆；判死活用
  kill -0 $pid，它对**僵尸也成功**；清僵尸=让父 wait 或杀父（44 节）。
- disown 后永远失去 wait 资格：保活（42 节）与收割不可兼得——设计
  时先定"谁负责收尸"，再定"怎么免死"；disown -h 是两全的窄门。

```bash
pids=()
for u in "${urls[@]}"; do fetch "$u" & pids+=($!); done
fail=0
for p in "${pids[@]}"; do
  wait "$p" || { echo "fetch failed: $p" >&2; fail=1; }
done
exit "$fail"
```

- 子 shell 里的后台作业父级是子 shell，退出即孤儿化——后台任务别
  起在 ( )/$( ) 里，评审高频雷（37 节）。

## 46. awk 程序结构：BEGIN/模式/END 选型

- 三段骨架：BEGIN（读数据前：设 FS/初始化/打表头）、`pattern
  {action}` 主体（每行跑一遍：pattern 省略=全行命中，action 省略=
  print）、END（收尾聚合）——99% 的 awk 程序就是这形状，先立骨架
  再填肉。
- 纯聚合只要 END：`awk 'END{print NR}'` 数行、`{s+=$1} END{print s}`
  求和——main 块可以不存在；NR/NF 在 END 里是终值，天然免"最后一
  行"特判。
- -v 与位置传参分界：-v 在 BEGIN **前**注入、BEGIN 里可用；位置参
  （awk '...' x=1）BEGIN 里拿不到；-v 会解释 \t 转义，值含反斜杠
  时改用 ENVIRON["VAR"]。
- progfile 化：awk -f prog.awk 把程序挪出引号地狱——shell/awk 双层
  转义超过两层就该文件化（49/50 节长程序的前置条件）。
- NR 全局累计、FNR 按文件重置——多文件统计与跨文件 join（48 节）
  全靠这对；裸 awk 聚合多文件是主场，别再叠 sort|uniq 管道税（58
  节）。

```bash
awk -v target="$env" '
  BEGIN { printf "service\tcount\n" }
  $3 == target { c[$1]++ }
  END   { for (k in c) printf "%s\t%d\n", k, c[k] }
' events.tsv | sort -k2,2nr      # 遍历序不定，排序再见人（49节）
```

## 47. awk 字段机制：FS/NF/OFS 深水

- $n 赋值触发**整行重建**：$1="x" 后 $0 用**当前 OFS** 重拼全部字段
  ——改 OFS 必须在任何字段赋值之前，否则同程序前后行分隔不一致，
  肉眼极难发现。
- FS=" " 是特例不是规则：单空格表示"任意空白折叠+剥首尾"（缺省即
  此）；其他单字符（含 "\t"）按字面切；FS 多字符按正则解析——一个
  选项三种语义，先看清数据再写 FS。
- 正则 FS 的首空段：FS=", *" 时行首逗号会产生空字段 $1——POSIX 允许
  实现自由处理，gawk/mawk/busybox 有微差，空行与表头务必进测试
  矩阵（50 节）。
- FS 改动不重切当前行：字段在读取时已切好，改 FS 只对下一行生效；
  NF-- 删尾字段是 gawk 习惯、POSIX 未定义——可移植写法是重建整行
  或显式列举要保留的字段。
- print 的逗号才有 OFS：print $1 $2 是无分隔拼接——逗号多打少打是
  awk 复制粘贴事故的头名；$1=$1 则是"强制按新 OFS 重建"的惯用咒语。

```bash
awk 'BEGIN{FS=","; OFS="|"} { $1=$1; print }' d.csv  # 重建+新OFS
# 顺序错了：先赋值后改 OFS → 首行仍旧分隔符
awk -F'\t' '{ $2=clean($2); print }' data.tsv
```

- 字段号是动态的：$NF 末字段、$(NF-1) 次末——按位置写死 $3 的脚本
  换数据源就碎，能写 $NF/$(NF-1) 就别写常量。

## 48. NR==FNR：双文件 join 惯用法

- 骨架与 next：`NR==FNR { a[$1]=1; next } !($2 in a) { print }`——NR
  全局累计、FNR 按文件清零，相等即"还在第一个文件"；**next 不可
  省**，漏了首文件行就落进第二块双计，结果荒谬但语法无错。
- 空首文件直接翻车：f1 零行时 NR==FNR 恒为假——表空、输出看似
  正常；防御：gawk 用 ARGIND，可移植用 FILENAME==ARGV[1]，END 里
  断言 length(a)>0。
- 值留几份要想清：a[$1]=$2 只存最后值；首文件键重复需全量时显式
  拼接 a[$1]=a[$1] "\n" $2，或上复合键（49 节 SUBSEP）。
- 内存纪律：**小表在前**建索引、大表流过第二段——两个 GB 级文件
  join 时文件顺序决定成败；awk 无外排序，索引膨胀只有死。
- 与 join(1) 分界：join 命令要求已排序（56 节）、O(n) 归并、近零
  内存；本模式免排序、查表快但吃内存——巨表已排好序 join 赢。

```bash
awk -v OFS='\t' '
  NR==FNR { seen[$1]=$2; next }
  !($1 in seen) { print }              # 反连接：在B不在A
' a.tsv b.tsv > b_only.tsv
awk 'FILENAME==ARGV[1]{k[$3]; next} $1 in k' map.dat stream.dat
```

- 两文件字段号不同是常态——两块各写各的 $1/$3 别复制粘贴混用；
  gawk ARGIND 更可读但锁平台（50 节分界线）。

## 49. awk 关联数组：分组、计数、去重

- 计数即聚合：`c[$1]++`，END 遍历输出——uniq -c 的可编程超集；配
  sum[k]+=$3、cnt[k]++ 双数组即可覆盖报表八成的聚合需求。
- in 不建项、引用会建项：`if (k in a)` 干净探测；`if (a[k])` 会把 k
  凭空写进数组（空串值）——END 里多出一堆假键就是这条深水；探测
  一律 in，取值才用下标。
- 单遍去重保序：`awk '!seen[$0]++'` ——比 sort -u 多保住首见顺序、
  比两遍扫描快一半；配合 58 节单遍纪律食用最佳。
- 复合键：a[$1,$2] 靠 SUBSEP(\034) 拼接——键值含 SUBSEP 才会撞，
  概率低但不为零；显式 a[$1 SUBSEP $2] 语义相同但自我文档化，拆键
  用 split(k, parts, SUBSEP)。
- 遍历序不定（28 节 bash 同款）：gawk 有 asorti/PROCINFO["sorted_in"]
  （50 节），可移植方案是输出后接 sort——"awk 内不排、出 awk 再排"
  是最省心的分工。

```bash
awk '{ sum[$1]+=$3; cnt[$1]++ }
     END { for (k in sum)
             printf "%s\t%.2f\n", k, sum[k]/cnt[k] }' sales.tsv \
  | sort -k2,2nr | head
awk '!seen[$0]++' dupes.txt      # 保序去重，一行顶 sort -u
```

## 50. gawk 扩展与可移植 awk 分界线

- gawk 独有清单（用到即锁平台）：asort/asorti（数组排序）、gensub
  （捕获组替换）、strftime/mktime（时间）、PROCINFO（自省/全局
  sorted_in）、split 第三参收分隔符、coprocess |&。
- 实现面面观：mawk 快但无 gensub/strftime、多维数组实现古怪、正则
  边界与 gawk 有微差；busybox awk 是 "one true awk" 血统小子集
  （6 节），asort/patsplit 全无；BSD awk 与 macOS 一致地保守。
- gensub vs sub/gsub：gensub(/(\w+)@/, "<\\1>", 1, s) 返回新串不动
  变量、\1 引捕获组——sub/gsub 只有 &（全匹配）；跨实现要组就换
  sed -E 或 split+substr，别赌实现。
- 产线纪律：脚本头注释声明 `# requires: gawk >= 5`；或运行时自检
  `gawk 'BEGIN{exit !(PROCINFO["version"]>="5")}'` 且降级路径明确
  ——静默依赖是最差依赖，跑到一半才炸比不跑更糟。

```bash
gawk '{ print strftime("%FT%T",$1), gensub(/(\w+)@/,"<\\1>",1,$2) }'
# 可移植替代：时间→date 预格式化注入；捕获组→sed 分两步
awk -v today="$(date +%F)" '{ print today, $1 }' data
command -v gawk >/dev/null || die "gawk required (README)"
```

- 把 gawk 程序收敛进 progfile（46 节）+版本守卫，比行内塞扩展再
  到处复制安全得多。

## 51. sed 模式空间与 hold space

- 两块工作内存：pattern space 是当前行加工区（默认每读一行覆盖），
  hold space 是跨行侧袋；命令五行诀：h 覆盖存、H 追加存、g 覆盖取、
  G 追加取、x 互换——背下它，sed 才从替换工具变成流状态机。
- 分支即循环：`:top; N; b top` 造 while，配 $ 地址与 t 条件跳转能写
  出图灵机——但可读性悬崖在两三层分支处，超过就转 awk（52 节）。
- 教科典配方读法：`sed '1!G;h;$!d'` 反转全文件——每行把 hold 积压
  G 到自己后面再存回，仅最后一行放行打印；能默写并解释它 = 真懂
  双缓冲。
- n/N、d/D、p/P 三对深水：小写作用于"下一输入行"，大写作用于
  "模式空间内的换行"——52 节展开。
- 改结构的程序一律 -n + 显式 p：关掉默认回显，否则每行双份输出；
  纯替换（s///）才留默认。

```bash
sed '1!G;h;$!d' file               # 反转：hold 累积标准题
sed -n '1h;1!H;${x;s/\n/;/g;p;}' f # 全文件并成一行（hold 收集）
sed '$!N;P;D' pair.txt             # 滑动窗口见 52 节
```

- 判断该不该用 sed：状态只一格（hold）、窗口只两三行、无计数需求
  ——三条全过才写 sed，任何一条不过上 awk，别让维护者考古。

## 52. sed 多行编辑：N/D/P 循环

- 三件套语义：N 把下一行**追加**进模式空间（中间留 \n）；D 删掉
  模式空间**到首个 \n 为止**并跳回脚本开头**不读新行**（滑窗！）；
  P 打印到首个 \n 为止。d 与 D 一字之差 = "读新行重启" vs "原地重启"。
- 滑窗配方：`sed '$!N;P;D'` 维持恒定两行窗口扫全文件——相邻行去重
  `sed '$!N;/^\(.*\)\n\1$/!P;D'` 的标准骨架，背下这一个就够用半生。
- N 的 EOF 边界：最后一行时 N 失败，GNU sed 继续处理、部分 BSD 实现
  不再执行后续命令——同一脚本两平台输出差一行；多行 sed 的测试
  矩阵必须含奇数行、单行、空文件三档（6 节思路）。
- GNU 的 1~2 步进、0,/re/ 起始地址不可移植——BSD/macOS 直接语法错；
  跨平台目标里见到 ~ 就改写成 awk（46 节）。
- 续行合并实战：`sed ':a;/\\$/{N;s/\\\n//;ba}'` ——反斜杠结尾行吞
  下一行，:a/ba 自造 while（51 节分支）。

```bash
sed '$!N;P;D' f                    # 稳定两行滑窗
sed '$!N;/^\(.*\)\n\1$/!P;D' f     # 相邻重复行折叠
sed ':a;/\\$/{N;s/\\\n//;ba}' wrapped  # 续行展平
```

- 窗口要三行以上、或要计数/查表，立即放弃 sed：awk 一个 prev 变量
  加 if 的可读性碾压 sed 状态机。

## 53. sed -i 跨平台雷区与可移植写法

- 一行必死题：GNU `sed -i 's/x/y/'` vs BSD/macOS `sed -i ''`——BSD
  的 -i **必须**带后缀参数，GNU 的 -i 不带；两边语法互为毒药，同一
  命令不可能原样通吃。
- 通用交集是 sed -i.bak：两边语义一致（原地+留 .bak），跑通确认
  diff 再删备份——把跨平台落在纪律上而不是运气上。
- -i 的真实实现是**写新文件再 rename**：符号链接被替换成普通文件
  （/etc 下 resolv.conf 类 symlink 配置当场变脸）；属主/权限按当前
  用户与 umask 重置——对系统文件动手前先 ls -l 记住三件套。
- mtime 副作用：sed -i 对**无匹配**的文件也会 rename——内容没变
  而 mtime 全变，增量同步/文件监控被大面积误触发；用 grep -l 前置
  只动真命中。
- xargs 送文件名：空格/换行名走 find -print0 + xargs -0（27 节）；
  GNU xargs 加 -r 防空输入跑 sed 无参报错。

```bash
# POSIX 保真版：保 inode 保权限，处理 symlink 不炸
tmp=$(mktemp) && sed 's/old/new/' "$f" > "$tmp" \
  && cat "$tmp" > "$f" && rm -f "$tmp"
grep -lZ 'old' -r . | xargs -0 sed -i.bak 's/old/new/g'
```

- 团队规范一句话：脚本里的 sed -i 一律带 .bak 后缀，CI 加跑 BSD
  容器一遍（95 节矩阵）——消灭九成"在我机器上是好的"。

## 54. sort 深水：-k 语义、稳定性与 -S 调优

- -k2 的真身是"从第 2 字段**到行尾**"：后续字符全掺进比较键，
  `sort -k2 -k1` 与 `sort -k2,2 -k1,1` 结果可以完全不同——多键排序
  不写终点等于白写；要"先按 2 再按 1"就得 -k2,2 -k1,1。
- 默认有**兜底整行比较**：主键相同时比整行（last-resort），叠加
  locale 折叠（55 节）后"看似稳定"其实藏着二级排序——要真稳定加
  -s 关兜底，次序完全交给输入序。
- -u 按**键**去重不是按行：`sort -k2,2 -u` 保留每个键的首见行——
  "每键取一条"的利器，也最常被误当整行去重；真整行去重别带 -k。
- -b 剥键内前导空白：数据源带对齐空格时没有 -b 的比较键里全是
  噪声字符；写键就配 -b，配 -s，配终点，三件套齐活。
- -S 与临时目录：内存放不下就溢写 /tmp 多路归并——大文件慢十倍的
  元凶；`-S 50%` + `--parallel=$(nproc)` + TMPDIR 指向本地 NVMe 是
  GB 级排序的标准调优。

```bash
sort -t$'\t' -k2,2nr -k1,1 -s -S 50% --parallel=8 big.tsv
sort -u -k2,2 file              # 键去重；整行去重别带 -k
LC_ALL=C sort -S 70% -T /data/tmp merged.log  # 55节配套
```

- -n 不认 "1e3" 与 "1,000"：科学计数/千分位先 awk/sed 洗键再排，
  别指望 -n 懂数学；-h（人类单位）与 -V（版本序）都是 GNU 面。

## 55. LC_ALL=C：locale 整流的双重收益

- 不设 locale 的排序是**环境依赖的**：UTF-8 collation 做重音折叠、
  大小写邻近（"a" 与 "A" 同段），sort 结果随会话环境漂移——CI 过、
  本机炸的 "file 2 is not in sorted order" 九成源自这里（56 节
  连锁事故）。
- 第一收益确定性：LC_ALL=C 按字节序排——同输入必同输出，任何机器
  任何环境；可复现是脚本质量的地基，比"排得好看"重要。
- 第二收益性能：单字节比较无 collation 查表，grep -F/sort 在 ASCII
  大输入常见数倍提速；grep 千万行慢，先补 LC_ALL=C 再谈换工具
  （58 节）。
- 优先级链一锤定音：LC_ALL > LC_COLLATE/LC_CTYPE... > LANG——脚本
  里 export LC_ALL=C 即可屏蔽用户环境一切花活；只改 LANG 是无效
  防御。
- 副作用与取舍：C 下 [a-z] 是纯 ASCII 区间（与直觉有别但可预测）；
  要保留 UTF-8 处理又要字节序时用 C.UTF-8（musl/老 glibc 支持面见
  6 节）——整流不等于扔掉多字节。

```bash
export LC_ALL=C                  # 脚本头：一锤定音
LC_ALL=C grep -F "$needle" huge.log          # 提速立竿见影
LC_ALL=C sort keys.tsv > s.tsv && LC_ALL=C join ...  # 56节前提
```

## 56. join/comm：关系操作与排序前提

- 两个命令共享一条铁律：**输入必须已按比较键排序**，且排序时与
  运行时 locale 一致——sort 裸跑而 join 被 LC_ALL=C 整流就是自埋
  "not sorted"；整流要全链一致（55 节）。
- join 的键默认两侧第一字段、空白分隔；-1 2/-2 3 指定字段、-t$'\t'
  定分隔符、-o '1.1,2.2' 按位格式化输出——SQL 式 select 的小型
  实现（-o auto 与 -e 补空是 GNU 面，50 节同款声明纪律）。
- 集合代数：-a1 左外、-a2 右外、-a1 -a2 全外、-v1 只留 A 独有
  （反连接）——`join -v1 A B` 比 grep -v -f 稳（无正则歧义）。
- 笛卡尔地雷：同键 A 两行 × B 三行 = 输出 **6 行**——join 不去重，
  爆炸规模随重复度相乘；键列先 sort -u（54 节按键去重）是入场券。
- comm 按整行比较且要 sorted：只比键得先 awk 加工；三列输出配
  -1/-2/-3 抑制——-12 交集、-23 B 独有；乱序只 warning 加错果。

```bash
export LC_ALL=C
sort -k1,1 a.tsv > A; sort -k1,1 b.tsv > B
join -t$'\t' -v1 A B                       # 反连接：在A不在B
join -t$'\t' -a1 -a2 -e NA -o auto A B     # 全外连（GNU）
comm -12 <(sort -u keys1) <(sort -u keys2) # 交集
```

- 表头处理：join 遇未排序头行即炸——`{ head -1 f; tail -n +2 f |
  sort | join ...; }` 先摘后接；已排序巨表 O(n) 归并完胜 awk 哈希
  （与 48 节互为镜像选型）。
## 57. cut 的单字符局限与替代

- 单字符铁律：cut -d 只收一个字符且连续分隔符不合并——
  `cut -d: -f2` 对 "a::b" 给空串而非 b；多空格日志同样错位。
- 按需升级：多字符定界换 `awk -F'::'`；"连续分隔符当一个"
  用 awk -F'[ \t:]+'；老 cut -c 在多字节文本按字节腰斩 UTF-8。
- --complement 反选列（GNU）省掉重算列号：删第 3 列 =
  `cut -f3 --complement`，比数出其余全部列更抗表结构变更。
- 带 内嵌引号的真 CSV cut 无解：gawk -v FPAT='([^,]*)|
  ("[^"]*")' 才是正路；sed 拼凑预处理遇转义引号必塌。
- 行界前提：cut 按 \n 切——NUL 流先 tr '\0' '\n'（60 节）
  转有损视图，无损走 72 节 read -d ''。

```bash
printf 'a::b::c\n' | awk -F'::' '{print $2}'   # b，cut 给空串
awk -F'[ \t]+' '{print $3}' access.log         # 连续空白合并
cut -d$'\t' -f2- file | cut -f3 --complement   # GNU 反选
gawk -v FPAT='([^,]*)|("[^"]*")' '{print $1}' x.csv
```

- 口诀：单字符定界、无内嵌、ASCII 时 cut 最快；三前提
  破一个就换 awk——预处理补丁是技术债。

## 58. 单遍纪律：多级 grep 管道税

- 管道税本质：grep A | grep B | grep C 每级对全量做一次
  完整读写——3GB 过三级就是 ~12GB IO，瓶颈在 memcpy 不在匹配。
- 单遍合成：`awk '/A/ && /B/ && $5>100'` 一次扫描完成全部
  条件；多份报表用哨兵变量聚合、END 统一输出（76 节同谱系）。
- 多模式单扫：grep -F -f terms.txt 把几百个关键词压进一次
  扫描；-E 'a|b|c' 交替等价，模式量大后 -F -f 更快更稳。
- 全缓冲节点是禁地：sort 必须吃完全部输入才出第一行，
  `tail -f | sort` 永假死（59 节）；过滤器串联前先排查。
- 量化习惯：改写前后各 time 一次，大型日志管道常见 2-5
  倍墙钟改善——省的是 IO 不是 CPU，硬件掩不住架构税。

```bash
awk '/ERROR/ && /timeout/ && $NF>1000 {c++} END{print c}' app.log
grep -Fhf critical_terms.txt *.log | sort -u   # 多模式单遍
grep -vFf noise.pat big.log > clean            # 反向白名单单遍
```

- 评审纪律：同一数据流连过两个以上过滤器就该质疑——
  要么 awk 合成，要么说明中间结果为何要落盘复用。

## 59. 管线活性：行缓冲与 64KB 阻塞

- 假死机理：管道缓冲默认 64KB；块缓冲程序攒满 4-8KB 才
  write(2)，下游"没数据"其实是数据躺在 stdio 缓冲区里。
- 四个开关各管一段：grep --line-buffered、sed -u、awk
  打印后 fflush()、通用 stdbuf -oL——stdbuf 靠 LD_PRELOAD，
  对静态链接或自 setvbuf 的程序无效。
- 提前退出涟漪：head 拿够即退、整链收 SIGPIPE，pipefail
  下变 141 噪声（13 节）；grep -m1 同族，常被误判崩溃。
- 诊断顺序固定：先 strace -T -e read,write 断定"没写"还是
  "没读"，再看 /proc/PID/fdinfo 管道积压——别上来就加开关。
- 活性设计：长链监控把每个节点声明为行缓冲/全缓冲——
  全缓冲节点只许出现在链尾聚合位，不许多实例串联。

```bash
tail -F app.log | grep --line-buffered ERROR | tee live.err
sed -u 's/^/[edge1] /' stream.log               # GNU sed
stdbuf -oL -eL ./slow_tool | while IFS= read -r l; do route "$l"; done
```

- 经验值：ssh 跨机、加密流、容器边界各引入一层缓冲——
  逐段 echo 心跳探针定位，别整链盲调。

## 60. tr 与字节级转换战场

- 单字节世界观：tr 的"字符"就是字节——`tr '，' ','` 在
  UTF-8 下输出乱码；多字节转换换 sed/awk，tr 只留 ASCII。
- 三板斧：-d 删字符集、-s 压缩连续重复、-c 取补集——
  `tr -cd '[:print:]\n'` 是洗二进制进可打印的经典一招。
- 字符类优于 a-z：[:space:]、[:alnum:] 在任何 locale 语义
  稳定；裸 a-z 在非 ASCII 序编码是字典序陷阱（55 节整流）。
- 最著名误用：tr -d 'foo' 删的是 f、o 三个字符的集合，
  不是字符串 "foo"——删串是 sed 的活，混用即丢数据。
- NUL 桥接是隐藏价值：find -print0 调试用 `tr '\0' '\n'`
  有损但直观；正式处理回 72 节 -d ''。

```bash
tr -s ' \t' ' ' < raw.log      # 归一空白（两集合等长）
tr -cd '[:print:]\n' < raw.bin > clean.txt
find . -print0 | tr '\0' '\n'  # 调试用有损视图
printf 'HELLO' | tr '[:upper:]' '[:lower:]'
```

- 定位：tr 是字节流整形专用件——映射、压缩、删除之外
  都换工具，当瑞士军刀必出多字节事故。

## 61. xargs -P 并行纪律与引号

- -0 配对是入场券：find -print0 | xargs -0 才安全；不带
  -0 的 xargs 按空白与引号重新解析——空格即错位、引号即注入。
- sh -c 惯用法分家代码与数据：`xargs -0 -n1 -P8 sh -c
  'process "$1"' _`——单引号里是唯一代码，数据占参数位。
- 退码契约：123=有子命令退 1-125，124=退 255 或被信号杀，
  125/126/127=跑不起来/找不到——哪个 job 挂了要自建状态目录（69 节）。
- 批次自动切分：单参数超 ARG_MAX 时自动分包，别假设"一行
  一调"；-n1 -P N 显式声明才是"每核一个"的标准形。
- -P 跟瓶颈走：CPU 密集取 $(nproc)，IO/网络密集减半甚至
  2-4——先看 77 节计数再定并发，别无脑 16。

```bash
find . -name '*.log' -print0 |
  xargs -0 -n1 -P"$(nproc)" sh -c 'gzip -9 -- "$1"' _
xargs -a list.txt -d '\n' -P4 -I@ process @   # GNU 换行分隔
```

- xargs -P 是无状态扇出的最优解；要失败聚合、逐作业参数、
  限流策略就升级 69 节 job 表，别在 xargs 上硬搭。

## 62. flock 三种形态与选型

- 形态一（包裹式）：`flock -n /run/app.lock cmd`——锁文件
  只是锚点，命令由 flock fork/exec；-n 立败、-w 300 超时。
- 形态二（fd 式，脚本自持）：`exec 9>lock; flock -n 9`——
  锁挂脚本 fd 上，退出或被 OOM 杀，内核关 fd 自动释放
  （105 节"永不陈旧"的本质优势）。
- 形态三（目录锚+读写锁）：锁锚可为目录免预建文件；-s
  共享读 + -x 独占写支撑"单写多读"扇入场景。
- fork 继承陷阱：fd 式锁被子进程继承——`daemon &` 拿走
  9 号 fd，脚本退出锁仍被持有；后台化必须 `daemon 9>&-`。
- NFS 警告：flock(2) 在 NFS 不可靠（老客户端谎报成功）；
  跨机互斥退 63 节 mkdir 原子性或外部分布式锁。

```bash
exec 9>/run/app.lock
flock -n 9 || { echo busy >&2; exit 75; }   # 107 节 EX_TEMPFAIL
flock -w 30 200 -c 'import_batch'           # fd 200 包裹+超时
my_daemon 9>&- &                            # 摘锁再后台化
```

- 选型：包装外部命令用形态一，脚本临界区用形态二，多读者
  用形态三；混用前先画 fd 归属图——锁归谁、何时释放要能
  被白板讲清楚。

## 63. 无 flock 的锁：noclobber 与 mkdir

- noclobber 原子性：`( set -C; : > lock ) 2>/dev/null` 由
  shell 以 O_CREAT|O_EXCL 打开——内核保证只有一个进程建成功。
- mkdir 原子性：`mkdir /run/app.lock` 一次成败且免预建锚；
  成功者向目录写 owner 文件（pid+hostname+起始时间）备查。
- noclobber 纪律：set -C 污染其后所有重定向——锁逻辑关进
  子 shell 局部开关；两种形态的存在判定 [ -e ]/[ -d ] 别混。
- 陈旧检测链自带竞态：读 owner → kill -0 → 强拆，"确认死了"
  与"新持有者接管"之间永远有窗口；强拆前二次读 owner 未变
  （70 节 PID 复用同族）。
- 判死三档递进：心跳文件 → 超时+告警 → 人工确认；自动强拆
  是双实例进场的经典开端，105 节把这类故障整个归零。

```bash
if mkdir /run/app.lock 2>/dev/null; then
  printf '%s %s %s\n' "$$" "$(hostname)" "$EPOCHSECONDS" \
    > /run/app.lock/owner
  trap 'rm -rf /run/app.lock' EXIT      # 106 节登记簿接管
else echo "held: $(cat /run/app.lock/owner)" >&2; exit 75; fi
```

- 零依赖是这类锁的全部价值；环境一旦有 flock 就回去——
  内核自动释放省掉的整类陈旧故障，比省的依赖更值钱。

## 64. 后台作业表：jobs、wait 与 set -m

- & 只 fork 不等人：脚本退出前必须 wait，否则退出码与输出
  尾部全丢；wait $pid 收单个、裸 wait 收全部、wait -n（4.3+）
  等"任意一个"先完成。
- 作业号 %1 是作业控制语境产物：脚本默认无 set -m，`kill
  %1` 报 no such job——脚本里只认 PID；jobs -p 才是可传递物。
- wait -n 是滑动窗口基石（69 节）；4.2 以下只能轮询 kill -0
  模拟——慢且自带竞态（70 节），应声明版本底线（1 节）。
- set -m 的进程组语义：子任务独立进程组后 `kill -- -PGID`
  可群杀子树；不开 -m，孙进程会逃出清理范围（106 节盲区）。
- 变量回传封锁：(cmd) & 里的赋值活在子 shell——计数与结果
  走状态目录（69 节）或改 < <( ) 结构（71 节）。

```bash
pids=()
for id in "${ids[@]}"; do slow_job "$id" & pids+=("$!"); done
for p in "${pids[@]}"; do wait "$p" || failed+=("$p"); done
wait -n; echo "first finisher rc=$?"    # bash>=4.3
```

- 三段心智模型：起（& 记 $!）—等（wait/wait -n）—收（退码
  归档）；只起不等的脚本在 CI 里制造"绿色但没干活"的假象。

## 65. coproc：双向协程管道

- 结构：`coproc SQL { psql -Atq mydb; }` 后 SQL[0] 读端、
  SQL[1] 写端、SQL_PID 对端进程；不命名用 COPROC 数组，且
  第二个 coproc 顶掉第一个——永远显式命名，单实例写进注释。
- 缓冲死锁是主死因：对端不按行 flush 你的 read 永远等不到；
  你写超 64KB 对端不消费则写端挂死——双向都按"小步+超时"设计。
- 安全读法只有带超时：`IFS= read -r -t 5 line <&"${SQL[0]}"`
  ——超时返回大于 1，据此区分"没回话"与"对端死了"。
- 生命周期：主 shell 退出管道关闭、对端读 EOF 自退；不
  wait "$SQL_PID" 会留僵尸——收尾写进 trap（106 节登记簿）。
- 诚实边界：适合与 bc、psql REPL、加密流这类请求-响应长驻
  进程对话；协议带状态机或要并发会话，换 expect/Python。

```bash
coproc SQL { psql -Atq mydb; }
printf 'SELECT count(*) FROM t;\n' >&"${SQL[1]}"
IFS= read -r -t 5 n <&"${SQL[0]}" || die "sql timeout"
kill "$SQL_PID"; wait "$SQL_PID" 2>/dev/null || true
```

- 调试技巧：先在终端手工过一遍协议、数清回包行数再固化——
  coproc 的错误几乎全是"以为一行回一条"的协议假设错。

## 66. FIFO 模式：阻塞语义与超时读

- 开口互等：fifo 的 open 只读阻塞到出现写者、只写阻塞到
  出现读者——`mkfifo f; cat f` 是在等对面不是死机；strace 定案。
- 双开破局：`exec 3<>fifo` 读写一起持有，open 立即返回、
  写端不因消费者短暂退出而 EOF——守护握手、日志扇入的标准舞步。
- 读方必须带表：`read -t 30` 或 `timeout 30 cat`，超时/EOF/
  读失败三态用 $? 区分；超时是对端死亡的唯一可靠信号（70 节）。
- 多读者不是广播：并发读同一 fifo 是竞争消费，每条数据只
  被一个读者拿到；要广播就 tee 出 N 条 fifo，或承认需求
  超出 shell 上真消息队列。
- 目录纪律：fifo 放 /run（tmpfs 重启自清）不放 /tmp——共享
  /tmp 有符号链接劫持面（80 节），残留 fifo 会把等待变永久悬挂。

```bash
mkfifo /run/req
exec 3<>/run/req                      # 双开：永不互等、不 EOF
while IFS= read -r -t 30 req <&3; do
  handle "$req" >&3 || log "worker unhealthy"
done
```

- 心智模型：fifo 是"带阻塞语义的 socketpair 文件"——开口、
  帧边界、超时都要显式设计，缺一个就是生产环境定时假死。

## 67. GNU parallel 的许可与部署

- 许可是真实成本：GPLv3 + 引用告示——`parallel --citation`
  要交互确认，CI 里要么 --will-cite 要么每跑看一段告示；
  商业产品引入前过法务，别等发版被追问。
- 语义增益：{} 默认不进 shell 重解析（比 xargs 安全）、{/}
  basename、{#} 作业号、::: 喂参数、--tag 输出打标、--joblog
  记成败与耗时——xargs 做不到或做得很丑的部分。
- --embed：把运行时打进自包含脚本、部署机零依赖——但生成物
  仍是 GPL 产物，分发即触发源码义务，"看不见 parallel"不等于豁免。
- 跨机 --sshlogin：要求每台目标机装 perl+parallel 且免密
  互通；运维与安全面常被低估到弃用，单机往往才是对的答案。
- 决策序：本地无状态扇出 xargs -P（61 节）够用；要参数重排、
  重试、joblog 用 parallel；跨机调度先算运维账再谈收益。

```bash
parallel --will-cite -j8 --tag --joblog run.jl \
  process ::: "${ids[@]}"
parallel --dryrun -j4 fetch ::: "${urls[@]}"   # 先排练再上
```

- 工程解：镜像构建期跑一次 --citation 完成确认并写进文档
  ——CI 用 --will-cite，README 附引用信息，合规与安静兼得。

## 68. 进程替换 >( ) <( ) 的失败感知

- 失败不传播是设计级坑：`diff <(gen a) <(gen b)` 里 gen 崩
  了，diff 只看到空输入照样退 0/1——退出码与生产者无关。
- >( ) 更阴险：写完不等消费者收尾——`tar c src >(
  gzip > f.tgz)` 脚本退出时 gzip 可能还没落盘；bash 4.4+ 用
  $! 抓进程替换 PID 显式 wait，老版本靠输出文件轮询兜底。
- 结构替代：要失败感知就改临时文件——`gen a > "$t" ||
  exit; diff "$t" ref`，退码链完整可判（80 节管 tmp 生命周期）。
- 可移植面：<( ) 是 bash/ksh/zsh 扩展，dash/busybox 无（89
  节）；zsh 默认等进程替换完成而 bash 不等——跨 shell 漂移常客。
- 与管道的语义差：cmd | consumer 的退码在 pipefail 语义里可
  感知（13 节）；<() 把 consumer 摘出管道，失败感知责任全归你。

```bash
diff <(gen a) <(gen b)             # gen 的失败被吞！
tar c src > >(gzip -9 > src.tgz)   # bash>=4.4 可 wait $!
gen a > "$tmp_a" || exit; diff "$tmp_a" ref   # 可感知版
```

- 经验法则：进程替换只喂"确定不会失败的生产者"；任何可能
  失败的外部命令退回 tmp 文件模式，可靠性摆在少一次落盘前面。

## 69. 扇出限流与失败聚合

- 滑动窗口骨架：窗口满则 wait -n，出空位即补——并发恒为 N；
  比之 xargs -P 的增益是逐作业参数、重试、超时策略。
- 状态目录即结果集：每作业 `echo $? > st/$id`、日志写
  log/$id——聚合扫目录出报告，比追交错的 stdout 可靠一个量级
  ；目录还天然是断点续跑的账本。
- fail-fast 显式声明：wait -n 非零即停补位、TERM 余下、聚合
  退非零——对账"跑完再报"、破坏性作业"立即止血"（107 节）。
- 退避放进作业内部：429、IO 抖动由作业自带指数退避+抖动重试
  ，外层只重试确定性失败——两层都重试会把下游打成雪崩。
- 限流≠并发数：CPU、磁盘 IO、远端 API 各有瓶颈——令牌窗口
  管并发、作业内节流管速率，双闸齐下；nproc 不是万能参数。

```bash
mkdir -p st log; MAX=8
for id in "${ids[@]}"; do
  while [ "$(jobs -rp | wc -l)" -ge "$MAX" ]; do wait -n || true; done
  ( job "$id"; echo $? >"st/$id" ) >"log/$id" 2>&1 &
done
wait; awk '$0!=0{n++} END{exit n?1:0}' st/*   # 聚合退出码
```

- 本质：把并发变成可审计数据——能回答"谁在跑谁挂了"的
  扇出才算生产级。

## 70. kill -0 存活检测与 PID 复用竞态

- 三态探针不是布尔：kill -0 返回 0=活着且可信号、EPERM=活着
  但不归你、ESRCH=不存在——把 EPERM 读成"死了"是事故级误读。
- 僵尸误判：未被 wait 的僵尸对 kill -0 依然返回 0——守护父
  不收割时"早退的上游"在你眼里还活着；复核 /proc/$pid/stat。
- 陈旧检测链：读 pidfile → kill -0 → 读 /proc/$pid/cmdline
  与期望 argv0 比对——少最后一层就是对 PID 复用敞开大门。
- 复用比想象快：PID 回绕再分配可短至毫秒级，检测与动作间
  的 TOCTOU 消不掉——动作建立在"我持有锁"（62/63 节）上，
  探测只出告警。
- 跨用户探测：非 root 对他人进程恒 EPERM，存在性判断用
  [ -d /proc/$pid ]；macOS 无 /proc 退化 ps -p（92 节矩阵）。

```bash
read -r pid < /run/app.pid || pid=
if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null &&
   [ "$(cat "/proc/$pid/comm" 2>/dev/null)" = "app" ]; then
  echo "alive: $pid"
else echo "stale pidfile (pid=${pid:-none})" >&2; fi
```

- 口诀：探测只回答"要不要报警"，互斥交给锁、交接交给协议
  ——把 kill -0 当互斥原语的脚本，迟早双实例。

## 71. 循环内子 shell 消灭战

- 头号案犯：`cat f | while read` ——while 跑在管道子 shell，
  counter 与 foo=bar 全部蒸发；"循环里变量丢了"九成是这条。
- 标准改法：`while ...; done < <(cmd)` ——循环回当前 shell，
  变量可回收；代价是 cmd 退码收不到（68 节），要退码落状态
  文件或改 here-string 喂入。
- 同族盘点：for f in $(ls) 是解析 ls（110 节）；(count=$
  ((count+1))) 子 shell 白算；done <<< "$x" 与 done < file 安全
  ——判别标准："循环在管道右侧吗"。
- find 管道同理：`find -print0 | while read -d ''` 照样丢变量
  ——改 `while ...; done < <(find ... -print0)` 配 72 节咒语。
- 调试技巧：循环内打印 $BASHPID 与主体的 $BASHPID 比对——
  代码跑在哪个进程一目了然，比瞪眼推断快。

```bash
# 反模式（n 永远为空）与修正：
cat ids | while read -r i; do n=$((n+1)); done; echo "${n:-0}"
n=0; while read -r i; do n=$((n+1)); done < <(cat ids); echo "$n"
while IFS= read -r -d '' f; do sz=$((sz+$(stat -c%s "$f"))); done \
  < <(find . -name '*.bin' -print0)
```

- 迁移口诀："循环产出要留给循环后用"的代码，把数据源从
  管道改成重定向——一行改动消灭一类幽灵 bug。

## 72. read 纪律：-r、IFS= 与 -d ''

- 完整咒语 `IFS= read -r line` 缺一不可：-r 保住反斜杠（裸
  read 把行尾 \ 当续行吞掉）、IFS= 保住行首尾空白（裸 read
  剥行首空格、压缩连续空白）——半截咒语=静默数据损坏。
- -d '' 切 NUL：与 find -print0 构成唯一无损遍历——空格、
  换行、引号文件名全部存活；忘 -d '' 等于把换行名当两个路径。
- 尾行保护：末行无换行时 read 返回非零但变量已填充——
  `|| [ -n "$line" ]` 救回半行；set -e 语境必抄全（11 节）。
- -a 入数组：`IFS=, read -r -a parts <<< "$csv"` 空字段保位
  （a,,b 产出空元素）——比 cut 诚实（57 节），配 27 节正好。
- 性能与防护：read -t 5 防对端卡死（66 节）、-N 100 读定长；
  每行一次内建调用，海量行换 73 节 mapfile——正确与速度都要。

```bash
while IFS= read -r line || [ -n "$line" ]; do
  printf '%s\n' "$line"
done < maybe_no_final_newline.txt
while IFS= read -r -d '' f; do process "$f"; done < <(find . -print0)
IFS=, read -r -a kv <<< "a=1,b=,c=3"; printf '<%s>\n' "${kv[@]}"
```

- 评审见裸 read 三问：-r 在吗、IFS 前缀在吗、尾行救了吗
  ——三个都答上，这行代码才算写完。

## 73. mapfile/readarray 与批量读

- 一次内建调用吃完整个文件：mapfile -t arr < file 比逐行
  read 快一个量级——省每行一次内建往返，万行级肉眼可见。
- NUL 面（bash 4.4+）：`mapfile -t -d '' arr < <(find ...)` 直接
  吞 find -print0——72 节无损遍历的批量版；老 bash 退回 while
  read -d '' 逐个 append。
- 内存纪律三参数：-n 1000 限量、-O "${#arr[@]}" 续填、
  -C 回调配 -c 2 每 2 行触发——分批处理不撑内存，回调打进度。
- 净化自理：mapfile 保留原样，CRLF 先 tr -d '\r'（60 节）或
  后处理 ${x%$'\r'}；-t 只剥行尾换行，不管 BOM 与空白。
- 改写去向：mapfile 后用 for 循环加工，全程当前 shell——
  变量修改可回收（71 节互为表里）："批量读+当前 shell"是
  "读文件进循环"话题的完整答案。

```bash
mapfile -t lines < servers.txt        # -t 剥行尾换行
mapfile -t -d '' files < <(find . -name '*.log' -print0)
mapfile -t -n 500 -O "${#buf[@]}" buf < batch2.txt   # 追加续填
printf '%s\n' "${lines[@]}"           # 原样回吐
```

- 版本门控：readarray 是 mapfile 别名，bash 4.0+；macOS 3.2
  两者皆无（2 节）——声明底线或探测降级（8 节 need 助手）。

## 74. exec 与动态 fd 工程

- 自动分配：`exec {fd}>log/app.log`（bash 4.1+）自动挑空闲号
  存进 fd 变量——多日志流免手抠 3/4/5 互踩；{a}>、{b}> 各拿各的。
- exec 改的是整张 fd 表：`exec > log 2>&1` 后全脚本输出改道
  ——先 `exec {save}>&1` 备份、结束恢复，是区域重定向的标准
  舞步；忘了备份就回不去。
- 同 fd 读写 <> 打开后无 seek：shell 没有 lseek 内建——"读
  一点写回去"的随机访问交给 dd/awk，fd 工程只做流式。
- 关闭纪律：`exec {fd}>&-` 显式关——不关则 fd 传给每个子进程
  ：flock 泄漏（62 节）、fd 耗尽（10 节）都从这条缝漏；收尾
  进 106 节登记簿。
- /dev/fd/N 是理解进程替换的钥匙：<( ) 给的 /dev/fd/63
  本质是"已打开管道的 fd"——68 节失败感知缺失正因绕开退码语义。

```bash
exec {save}>&1                 # 备份 stdout
exec {logfd}>logs/app.log
exec >&"$logfd" 2>&1           # 区域改道：之后全进日志
run_stage1; run_stage2
exec >&"$save" {save}>&- {logfd}>&-   # 恢复并关闭
```

- 心智模型：exec 是"给当前进程换接口"，粒度是整张 fd 表
  ——使用前先想清楚"谁会继承这张表"。

## 75. fork 计数：内建优先与 EPOCHSECONDS

- 隐藏税冠军是时间戳：$(date +%s) 每次调用 fork+exec 一个
  二进制，循环里每行 1-3ms——bash 5.0+ 用 $EPOCHSECONDS/
  $EPOCHREALTIME 零 fork，4.2+ 用 printf '%(%s)T' -1。
- 内建替代表：${#s} 替 $(wc -c <<<"$s")、$(( )) 替 $(expr)、
  [[ -f x ]] 替 $(test)、printf 替外部 echo——每处省一次 fork。
- 攒批结构降一个量级："每行一命令"改"攒一批一次跑"——xargs
  批处理（61 节）、awk 单遍聚合（58 节）、tee 分流三件套；
  fork 数从 O(n) 降到 O(n/批次)。
- 取证工具：strace -c -f bash s.sh 看 clone/execve 计数——
  几百以上就该动刀；-f 必带，否则漏掉子进程大头（77 节）。
- 别过早优化：先测再砍；一行 date 不值得重写，千行循环里的
  $(date) 才是被告——预算先行（77 节）。

```bash
for i in {1..1000}; do echo "$(date +%s)"; done     # 1000 forks
for i in {1..1000}; do echo "$EPOCHREALTIME"; done  # 0 forks
ts=$(printf '%(%s)T' -1)                            # bash>=4.2
```

- 审查清单化：热循环体内禁出现 $( ) 与外部命令——写进
  110 节反模式清单，评审按行勾。

## 76. 大文件：sort 外部合并与 awk 单遍

- sort 自带外部合并：内存放不下就分块落盘再归并——-S 40%
  内存预算、--compress-program=pigz 压临时块、-T/$_TMPDIR
  指定工作盘；网络盘 vs 本地 SSD 墙钟差几倍。
- --parallel=8（GNU）开多核归并，但 IO 是主瓶颈时加核无感
  ——先 strace 看读放大（77 节）再决定买内存还是买并行。
- 单遍聚合是第一策略：sum/count/top-k 一遍 awk 全出；"先
  sort 再 uniq -c"的两遍在 10GB 面前是 IO 翻倍原罪（58 节）。
- 去重对决：sort -u 外部排序 O(n log n) 内存可控；awk
  '!seen[$0]++' 哈希 O(n) 但吃内存——数据集超内存三分之一
  就选 sort -u（54 节谱系）。
- 预过滤是免费午餐：sort 前 grep -F 域过滤把 10GB 削成
  1GB，比任何调参都狠——先问"数据要不要全量进排序"。

```bash
export LC_ALL=C TMPDIR=/fast/tmp         # 55 节整流 + 快盘
sort -S 40% --parallel=8 --compress-program=pigz -u huge.log
awk '{s[$1]+=$5} END{for(k in s) print k, s[k]}' huge.log
```

- 两遍代价直观化：10GB 额外一遍顺序读多花几分钟到几十分钟
  ——单遍设计省下的时间够把整条管线重测三遍。

## 77. 微基准与 strace 取证

- time 是关键字不是命令：`time cmd` 测 shell 直接 fork 的
  整段，放进 $( ) 或后台就断——严肃测量用 TIMEFORMAT='%R
  %U %S %P' 配 `time { ...; } 2>bench`。
- strace -c 先看两行：clone/execve 多=进程税（75 节）、
  read/write 次数多=缓冲缺失（59 节）——汇总表比逐条 trace
  快；-T 看单次耗时，-f 必带。
- 纯 shell 剖析：PS4 带 $EPOCHREALTIME 与行号，配
  BASH_XTRACEFD 落文件（98 节），awk 算相邻时间戳差——
  无侵入逐行耗时分布（102 节后处理）。
- 基准纪律：跑 3 次取中位；热缓存会撒谎——对比要么都热
  要么都冷（清 page cache 需 root）；单次跑不做结论。
- 目标导向：先写"当前耗时 × 目标耗时"再动手——没有预算
  的优化是无休止的把玩，达标即停。

```bash
TIMEFORMAT='%R real %U user %S sys %P cpu'
{ time big_pipeline; } 2> bench.txt
strace -c -f bash script.sh             # syscall 汇总
strace -T -e trace=read,write -p "$pid" # 卡点取证
```

- 方法论口诀：strace -c 定性（fork 税还是 IO 税）→ PS4 剖析
  定位（哪一行）→ time 验收——顺序反了就在错误的层面优化。

## 78. 启动成本与冷启动优化

- 脚本不读 rc 链：/etc/bash.bashrc、~/.bashrc 是交互面，
  非交互脚本只认 BASH_ENV（5 节）——rc 里的 alias 脚本不认识。
- 冷启动常量：bash 约 2-5ms、dash 不到 1ms——单次无感，
  cron 每分钟、CI 每步、外层循环每轮的叠加才要命；纯 POSIX
  脚本换 #!/bin/sh 白捡三倍（89 节前提成立时）。
- 大头从来不是 bash 自己：每外部命令 1-3ms，10 个就 30ms
  ——先 strace -c 数 fork（75 节）再谈启动，否则在给 5% 的
  成本做优化还冒着改坏风险。
- 高频短任务改常驻：守护+fifo 指令队列（66 节）或 systemd
  socket 单元把启动摊薄为零；低频任务这样干是过度工程，
  边界用真实频率数据划。
- 测量姿势：hyperfine --warmup 3 跑百次对比，或 for 循环包
  time——单次启动的调度噪声比 bash 自身还大。

```bash
for sh in bash dash; do
  t0=$EPOCHREALTIME; "$sh" -c 'exit 0'; t1=$EPOCHREALTIME
  echo "$sh: $(echo "$t1 $t0" | awk '{printf "%.2fms",($1-$2)*1000}')"
done
```

- 预算公式：触发频率 × 单次冷启成本 = 年化浪费——拿这个数
  决定要不要常驻化，比"感觉慢"有说服力。

## 79. 引号即防线：注入场景复盘

- ssh 二次解析：`ssh h "deploy $tag"` 本地展开后整串再被
  远端 shell 解析——$tag 含 `; rm -rf ~` 即注入；ssh 把多
  参数用空格拼接后照样走远端 shell，拼接本身就是危机现场。
- 正确通道：数据走 stdin（printf '%s\n' "$tag" | ssh h
  'read -r t; deploy -- "$t"'）；或逐参数 printf %q 转义后组装。
- eval 剖析：eval "rm $path" 中 $path 含 $(cmd) 即执行任意
  代码——不得不 eval 时先转义：`eval "rm -- $(printf '%q'
  "$path")"`，bash 4.4+ 用 ${path@Q} 更直白。
- argv 传递优于拼接：sh -c 'f "$1"' _ "$data"——代码在单
  引号里恒定、数据占参数位（61 节同款），xargs/ssh/find
  -exec 通吃的内联脚本安全模板。
- 结构性预防：把命令名放变量再 $cmd 执行=代码通道——用
  case 分派函数表；shellcheck 报得了直接 eval，报不了"拼
  命令再执行"的中转变量，评审要人盯（100 节）。

```bash
printf '%s\n' "$tag" | ssh deploy@h 'read -r t; deploy -- "$t"'
cmd=(deploy --env prod); cmd+=("$tag"); "${cmd[@]}"  # 数组边界
eval "rm -- $(printf '%q' "$path")"                   # 不得不 eval
```

- 复盘一句话：引号决定"这段文本是代码还是数据"——每处省
  掉的引号，都是在把数据权升级成代码权。

## 80. mktemp 纪律与 /tmp 竞态

- 模板必须带 X（≥6 个）：mktemp 用内核 O_EXCL 试探唯一——
  自拼 $$/$RANDOM 是可预测竞态，PID 可枚举，攻击者抢先建名即赢。
- 目录优于文件：tmp=$(mktemp -d) 租整个工作区——清理点收敛
  为一个 rm -rf、登记在 106 节登记簿不散落，好对账十倍。
- trap 先于 mktemp 装且 tmp= 先置空——mktemp 与 trap 之间被杀
  会留孤儿；cleanup 判 [ -n "${tmp:-}" ]，重复触发也幂等。
- 符号链接劫持：共享 /tmp 预埋 ln -s /etc/passwd + "先
  [ -e ] 再写"两步法=被写穿；O_EXCL 不跟随符号链接，一步
  创建才是防线。
- systemd 私有 tmp：PrivateTmp=yes 给服务独立 tmpfs——跨
  进程握手文件放 /tmp 互不可见；跨服务接口放 /run（tmpfs、
  重启自清、root 属主）。

```bash
tmp=
cleanup() { [ -n "${tmp:-}" ] && rm -rf -- "$tmp"; }
trap cleanup EXIT
tmp=$(mktemp -d "${TMPDIR:-/tmp}/myapp.XXXXXXXX")  # 默认 0700
: > "$tmp/plan"                    # 目录私有，随便写
```

- 自检三问：模板有 X 吗、trap 先于 mktemp 吗、路径在 /run
  还是共享 /tmp 吗——三问过完，/tmp 竞态一类故障整体出清。

## 81. 原子写：tmp+rename 与崩溃一致性

- 正统四步：同目录 tmp=mktemp → 写内容 → chmod 保权限 →
  mv -f tmp target——rename(2) 原子换名，读者永远看到完整
  旧文件或完整新文件，绝无半截窗口。
- 同文件系统是硬前提：跨 fs 的 mv 退化 copy+unlink，中途被杀
  =目标已丢——tmp 必须建在目标同目录（80 节模板带前缀的意义）。
- O_TRUNC 是死穴：`> config` 打开即清空，写到一半断电或
  被 OOM 杀文件就是半截——所有"直接重定向覆盖配置"的写法
  在崩溃一致性面前都不合格。
- fsync 缺口：rename 前不落盘，崩溃后可能"名字换了、内容空"
  （元数据先持久化）——关键文件写完先 fsync 再 mv，交 python 兜底。
- symlink 版本化：ln -sfn releases/N current 原子换链——回滚
  =再换一链；配 rename 构成"版本目录+当前指针"发布底座（104 节）。

```bash
tmp=$(mktemp "$target.XXXXXX")          # 同目录！同文件系统
printf '%s\n' "$cfg" > "$tmp"
chmod --reference="$target" "$tmp" 2>/dev/null || chmod 600 "$tmp"
mv -f -- "$tmp" "$target"               # 原子换名
ln -sfn "releases/$rev" current         # 原子换链
```

- 验收：反复 kill -9 中断脚本，target 始终完整——这是原子写
  唯一可信的测试。

## 82. umask 与权限最小化

- 开局 umask 077：新建文件/目录默认仅属主——比"建完再
  chmod"少一个全员可读窗口；秘密与临时文件受益最大（80
  节 mktemp 600/700 是同类兜底）。
- 两步 chmod 竞态：touch f && chmod 600 f 之间有暴露窗口——
  一步原语优先：mkdir -m 700、install -m 600 都在创建时带权限。
- install 优于 cp+chmod：install -m 644 -o app src dst 创建
  时定权限属主、-D 连父目录一起建——部署脚本替代"cp 完再
  补权限"的经典两步。
- 审计一条龙：find secrets/ -type f -perm /077 非空即泄——
  组/他任一可读写都算失败；CI 对制品与状态目录跑一遍当
  门禁，配 100 节棘轮只紧不松。
- 目录 x 位语义：无 x 不能进入也不能 stat 子项——共享目录
  750 而非 777；umask 补不了已存在目录，权限列为终态检查（104 节）。

```bash
umask 077                                # 开局即最小
install -m 600 -o app secrets.env /etc/app/
mkdir -p /srv/share && chmod 750 /srv/share
find /etc/app -type f -perm /077 -print  # 审计：非空即失败
```

- 经验法则：权限是创建时的事不是创建后的事——所有"稍后
  再收紧"的代码路径，都默认自己不会被 kill -9。

## 83. 秘密经行：args/env/fd 可见性矩阵

- argv 是公告栏：ps auxw、/proc/*/cmdline 亮给全系统（非属主
  可读，除非 hidepid）——curl -H "Auth: Bearer $T" 进进程表与历史。
- env 半公开：/proc/PID/environ 仅属主与 root 可读——跨
  uid 保密、同 uid 不保；多租户与容器逃逸场景视同泄露。
- fd 信道最低暴露：here-string/管道喂 stdin——ps 看不见
  内容、/proc/PID/fd 只见 fd 号；"秘密只走 stdin 或 0400
  的文件描述符"，工具支持 stdin 就绝不给 argv。
- 边界泄漏源：ssh SendEnv、cron 环境快照、CI masked 变量
  （99 节）都会把 env 里的秘密带进不可控日志——生命周期
  "申请即用完即 unset"，不常驻。
- 自检动作：ps -ef | grep 常见 token 形态、tr '\0' '\n' <
  /proc/self/environ 再 grep token——发布前跑一遍，比事后
  轮转密钥便宜百倍。

```bash
curl -H "Authorization: Bearer $TOKEN" api   # 反模式：ps 可见
printf 'Authorization: Bearer %s\n' "$TOKEN" |
  curl -H @- api                              # 走 stdin
gpg --pinentry-mode loopback --passphrase-fd 3 3<<<"$PW" file
```

- 矩阵结论：argv 全可见 > env 同 uid 可见 > fd 不可见——
  降级顺序就是安全工程的优先级顺序，评审按此打分。

## 84. curl 管道直执行批判与替代协议

- 三宗罪：无校验直连（被劫 CDN 即得手）、半截执行（连接中断
  =脚本跑一半）、无审计回放——`curl | sh` 三罪全占的活化石。
- TOFU 不是安全：首次信任被劫即永久后门；最低标准=版本
  钉扎+独立信道校验和——sha256 清单来自另一 origin 或带
  签名，发布者身份链要能核对。
- 三段协议：下载进 mktemp（80 节，不落可执行位）→
  sha256sum -c / gpg --verify 校验 → 通过后才执行；失败
  路径必须"什么都没发生"——不留半截文件与半截安装。
- 执行阶段再验证：上游被改后重跑会被校验拦住——缓存制品
  也要复验，防供应链回滚；校验值 pin 在部署清单随代码评审。
- 企业基线：内部制品库+签名（cosign/gpg）替代公网直拉；
  安装脚本提供 --offline 用预置制品（88 节同源）——离线
  能力本身就是攻击面削减。

```bash
set -euo pipefail
tmp=$(mktemp -d)
curl -fsSLo "$tmp/app.tar.gz" "$URL"                # 只下不跑
echo "$SHA256  $tmp/app.tar.gz" | sha256sum -c -    # 独立信道
tar -xzf "$tmp/app.tar.gz" -C "$tmp" && "$tmp/install.sh" --offline
```

- 一句话：管道直执行把"获取"与"执行"焊成不可中断、不可校验、
  不可审计的动作——三段协议把三个决定权还你，代价五行脚本。
## 85. sudo 边界与重定向陷阱

- 第一课：`sudo cmd > /root/f` 里的重定向由**你的** shell
  执行——sudo 只提权命令不提权 fd 操作，报权限错误的是你。
- 正解 sudo tee：cmd | sudo tee /root/f >/dev/null（追加 -a）；
  二进制流用 sudo dd of=；tee 的 stdout 静音别忘，否则双输出。
- sudoers 参数匹配是伪安全：`mycmd '*'` 等于把 argv 拼接权
  交出去——"无参数白名单+脚本内部严格校验"（87 节）才闭环。
- env_reset 默认清场：sudo 起的环境近乎白纸（env_keep+secure_path）
  ——依赖 PATH/LANG 的命令在 sudo 下变样，调优先显式绝对路径。
- sudo -E 只对授权变量生效：秘密经 env 传给 sudo（83 节）默认
  被剥——密码走 stdin/fd；失败尝试还会进 auth.log，审计要看。

```bash
./gen-conf | sudo tee /etc/app/conf >/dev/null   # 追加加 -a
sudo install -m 644 -o root build.env /etc/app/
sudo -n true 2>/dev/null || die "需要免密配置或交互终端"
```

- 评审三连问：重定向谁执行？sudoers 通配符放宽过吗？env_reset
  剥掉的那个变量没它行不行？——答完，sudo 事故面基本收敛。

## 86. setuid 之死与 Shellshock 遗产

- 内核拒绝 setuid 脚本：shebang 程序以解释器为主体、解释器
  再打开脚本文件，两步之间存在换内容即提权的经典竞态——
  Linux 直接忽略脚本上的 setuid 位，这是根因不是惯例。
- setuid 二进制 + system() 同样致命：system() 呼 /bin/sh
  继承提权后的环境——自写 wrapper 基本必死；sudo/su 是靠
  彻底环境清理与集中审计才活下来，别低估背后工程量。
- Shellshock 复盘（2014）：bash 把导出函数编码进环境变量
  `foo=() { ...; }`，函数体**尾随的命令**在启动解析时执行
  ——CGI 把用户 HTTP 头原样塞进环境的部署大面积沦陷。
- 现状：patch 后函数名与 BASH_FUNC_ 前缀被强约束、尾随命令
  不再执行；但"环境即攻击面"永久有效——5 节 BASH_ENV、
  109 节 CI 注入面都是它的现代变体。
- 替代模型：sudo（授权+审计）、polkit、capabilities
  （setcap cap_net_bind_service=+ep 只放行单一能力）——按
  最小权限粒度选，整段提权是最后手段。

```bash
sudo setcap 'cap_net_bind_service=+ep' /usr/local/bin/myhttpd
find / -xdev -perm -4000 -type f 2>/dev/null   # setuid 资产盘点
```

- 审计习惯：定期盘点 setuid 清单并 diff——每次新增都要有
  理由与工单；沉默出现的 setuid 文件就是事故前兆。

## 87. 不可信输入的验证与 -- 分隔

- -- 终止符是文件名底线防御：`rm -rf -- "$dir"` 之后的参数
  一律按字面路径处理——没有它，"-rf" 或 - 开头输入会被当
  选项；所有接受路径的命令都该带 --。
- 白名单用 case 不用正则：`case $name in ''|*[!A-Za-z0-9._-]*)
  die;; esac`——glob 分支无回溯爆炸面，"拒绝异常"优于"修补
  异常"。
- realpath 归一：攻击者喂 ../../etc/passwd——realpath -m 解析
  后校验 ${p#"$base"/} 仍以 base 为前缀且无 ..；-m 允许
  "规划态"路径（94 节可移植面）。
- nullglob 配套：空目录时 rm -- $dir/*.log 会把字面量 *.log
  传下去——bash 用 shopt -s nullglob+数组判空，dash 没有
  （89 节），改 find 或逐个 [ -e ] 判定。
- 验证与使用分离：校验通过的值写进**新**变量，原值此后不用
  ——"中转变量又把原始输入拼回去"是注入修复最常见的回归。

```bash
die() { echo "invalid input: $1" >&2; exit 64; }   # EX_USAGE
case $name in ''|*[!A-Za-z0-9._-]*) die "$name";; esac
p=$(realpath -m -- "$base/$name")
[ "${p#"$base"/}" != "$p" ] || die "escape attempt"
rm -rf -- "$base/$name"
```

- 验证哲学：白名单圈住"形状"、-- 圈住"解释"、归一圈住
  "位置"——三层各挡一类攻击，缺一层就各留一道门。

## 88. 安装脚本的供应链最低标准

- 钉版本+校验和：URL 带 tag/hash，sha256 来自独立信道——
  "latest" 是供应链攻击最爱的目标；84 节三段协议是安装
  脚本的默认骨架而非可选项。
- 可离线：--offline/--local 模式跳过一切下载、用预置制品
  ——网络是增强不是依赖；air-gapped 与受限 CI 跑同一脚本
  才证明部署一致性。
- 失败不落盘：中途失败必须回滚半截安装并退非零——106 节
  登记簿管清理、87 节管 rm 守卫；"下次重跑会修好"不是回滚
  策略，是事故预告。
- 可审计+可卸载：每步动作打结构化日志（103 节）可回放；
  uninstall 与 install 是同一份清单的正反面——没有卸载
  路径等于把系统租给了它。
- 幂等：重跑=收敛到同一状态（104 节）——"装两次"是生产最
  常见事故源；CI 连跑两遍 diff 系统状态固化为门禁用例。

```bash
need() { command -v "$1" >/dev/null || die "missing: $1"; }
need sha256sum; need tar                    # 自检段 fail fast
[ "$1" = --offline ] && { [ -r "$PRESET" ] || die "无预置制品"; }
```

- 最低标准即评审清单：钉扎、离线、回滚、审计、幂等——五项
  缺一，这个脚本就不该进生产通道，与功能多寡无关。

## 89. bash→POSIX sh 移植清单

- 舍弃表按代价排序：[[ ]]→[ ]（90 节）、进程替换→临时文件或
  管道重排（68 节）、数组→IFS 串、<<<→here-doc、pipefail→重设结构（13 节）。
- 参数展开降级：${var//x/y}→sed/tr、负数下标与 ${var:2:3}
  →cut/awk、${var@Q}/%q→case 白名单+手工转义——每条都付
  "多一次 fork"的税，认了才能走。
- local 是实用例外：POSIX 未定义但 dash/busybox/ksh 都支持——
  注释声明"非 POSIX，实测见 95 节"；纯 POSIX 则全局变量前缀当命名空间。
- 测试驱动移植：dash -n 先过语法，再实跑样例集——移植不是
  翻译是行为保真，没有回归用例的移植等于盲改（95 节）。
- 先问"要不要移植"：第一步先装 bash 常比全文改写便宜——
  移植成本计入长期维护，不只是初次翻译工时。

```bash
# bash → dash 改写示例：
# [[ $x == *.log ]]  →
case $x in *.log) ;; *) die;; esac
# arr=(a b); "${arr[@]}"  →
IFS=' ' set -- a b; for i; do process "$i"; done
```

- 清单哲学：每放弃一个 bash 特性都要在"少一个依赖"与"多
  一类手工模拟 bug"之间记账——结论常是锁死 bash 4.2+，
  这同样是工程决策而非偷懒。

## 90. [[ ]] vs [ ]：语义差异全表

- 分词与 glob：[[ $var ]] 不分词、不做路径名展开——[ $var ]
  遇空串报参数过多、遇空格词数漂移、遇 * 被 glob 展开；这
  是 [[ 最大的安全收益。
- == 的模式匹配：[[ $f == *.tar.gz ]] 右侧按 glob 解释（引用
  则变字面量）；[ ] 里 == 是 GNU 扩展、POSIX 只认 =，右侧
  永远字面量——跨 shell 语义漂移常客。
- =~ 仅 [[ 有：右侧是 ERE——引用 RHS 会变字面量（版本间
  行为反复，macOS 3.2 有专属怪癖，2 节）；惯用法是正则存
  变量再 `[[ $s =~ $re ]]`。
- < > 双重人格：[[ 里是字典序比较，[ ] 里是重定向必须转义
  `\[`；九成移植事故出在两者混用（89 节清单）。
- 退出码语义：0/1 之外 [ ] 遇语法错常退 2——空参数的
  [ $x ] 退 2 而非 1，set -e 与 if 语境要区分（11 节）。

```bash
[[ $f == *.tar.gz ]]        # 模式匹配（右侧别加引号）
re='^[0-9]+$'; [[ $v =~ $re ]] && echo numeric
[ -f "$f" ] && [ -r "$f" ]  # POSIX 面：变量必引号
[[ a < b ]]                 # 字典序；[ a \< b ] 才是 POSIX
```

- 选型纪律：脚本顶部声明 shell 基线——bash 脚本全用 [[ ]]，
  POSIX 脚本全用 [ ] 且变量永远带引号；混用是各取最差。

## 91. echo 地雷与 printf 迁移

- 方言矩阵：bash 内建默认不解释转义（-e 开启）、dash echo
  无选项且**总是**解释转义、coreutils /bin/echo 要 -e、
  POSIX 干脆不定义——同一 echo 四种性格，输出数据等于掷骰子。
- 数据以 - 开头：echo "-n" 静默无输出——printf '%s\n' "-n"
  才稳；再叠加裸 $var 未引号，分词与 glob 一起上，事故成串。
- printf 迁移法：格式串**固定**、数据全走 %s 占位——变量
  永远当数据不当格式；%b 专门处理 \n 类转义（对应 echo -e）。
- printf 两个坑：格式串里的 % 要写成 %%；参数多于占位符时
  格式串循环重印——批量输出可利用（printf '%s\n' a b c
  一行三条），不知情则是惊吓。
- 日志统一：printf '%(%F %T)T %s\n' -1 "$msg"（bash 4.2+）
  时间戳零 fork——91 与 75 节在此合流。

```bash
printf '%s\n' "$var"              # echo "$var" 的唯一替身
printf '%b' 'line1\nline2\n'      # 需要解释转义时
printf '%(%F %T)T [%s] %s\n' -1 INFO "$msg"
printf '%s\n' "${list[@]}"        # 数组批量，一次调用
```

- 迁移纪律：新代码禁 echo（纯字面量除外）——团队 lint
  规则拦，老脚本按"碰到就改"渐进收敛，别专门立项重写。

## 92. GNU vs BSD 工具矩阵：macOS 战场

- 高频差异清单：sed -i 必带后缀参数（`sed -i ''`）、date 无 -d
  （epoch 用 -r、解析用 -j -f）、stat -f '%m' 而非 -c '%Y'、
  timeout/readlink -f/grep -P 老系统全缺——每条都是深夜事故。
- g 前缀策略：brew 装 coreutils 得 gsed/gtimeout/gsha256sum
  ——三选一定死：shebang 绝对路径、PATH 前置 gnubin、脚本内
  探测分派（8 节）；团队不统一比缺工具更糟。
- 探测优于猜测：`sed --version >/dev/null 2>&1` 是 GNU/BSD 最快
  判别（BSD 把 --version 当文件名）——封装成函数集中维护。
- 排序双重打击：BSD sort 无 -S/--parallel，大文件慢一个量级
  ——LC_ALL=C 两边都要（55 节），大数据进容器用 GNU（95 节）。
- 双冻结世界：macOS=bash 3.2（2 节）+BSD 工具——头部声明
  requires 并自检 fail fast（7 节），比跑一半死在怪报错体面。

```bash
sed --version >/dev/null 2>&1 && SED=(sed) || SED=(gsed)
"${SED[@]}" -i 's/a/b/' f            # BSD 原生 -i 语法不可移植
stat -f '%m' f 2>/dev/null || stat -c '%Y' f
date -j -f '%Y-%m-%d' 2024-01-01 +%s   # BSD 反向解析
```

- 经验法则：为 macOS 写脚本要么全程探测降级，要么声明
  "只在装了 coreutils 的 mac 上跑"——半个 GNU 半个 BSD 的
  中间态事故最难复现。

## 93. 算术与 date 的可移植性

- epoch 数学是唯一可移植层：$(date +%s) 全平台有、加减乘
  除交给 $(( ))——"日期先归一成 epoch 再算"是第一定律。
- GNU/BSD 互不认账：GNU date -d '@123'，BSD date -r 123 与
  -j -f——把对方的合法参数当"无效日期"报错；封装探测（92 节）。
- bash 内建时间面：printf '%(%Y-%m-%d %H:%M)T' ts（4.2+）
  零 fork 格式化、$EPOCHSECONDS/$EPOCHREALTIME（5.0+）零
  fork 取当前——75 节 fork 计数的时间戳正解，还绕开方言。
- 算术边界：$(( )) 是 POSIX；0xff、2#1010 是 bash/ksh 扩展；
  自增 i=$((i+1)) 最通用，let 与 ++ 是 ksh 方言（89 节清单）。
- 时区雷：date 跟系统 TZ 走，cron 的 TZ 可能与交互会话不同
  （108 节）——跨时区先 TZ=UTC 归一；月/年粒度日历交 python。

```bash
now=$(date +%s); week=$((now - 7*86400))
printf 'expired: %(%F %T)T\n' "$week"      # bash>=4.2
if date -d "@$week" +%F >/dev/null 2>&1; then
  echo "GNU: $(date -d "@$week" +%F)"
else echo "BSD: $(date -r "$week" +%F)"; fi
```

- 纪律：日期代码一律"输入 epoch、输出 epoch 或固定格式串"
  ——自然语言日期解析（-d 'next tuesday'）在生产脚本里属于
  禁用方言。

## 94. 路径处理的可移植深水

- realpath 碎片化：GNU 有 -m（允许不存在）、busybox 只有裸
  realpath、macOS 老版 readlink 没有 -f——"归一个还不存在
  的路径"跨平台没有单行答案，先探测再降级。
- cd+pwd -P 舞步（唯一全兼容法）：cd -- "$(dirname -- "$0")"
  && pwd -P 拿脚本物理目录；bash 用 ${BASH_SOURCE[0]}（$0 在
  source/管道时会撒谎），POSIX 只能 $0 加注释声明限制。
- 尾斜杠与拼接：dirname /a/b/ 按规范忽略尾斜杠，${a}/${b} 会
  造 a//b 与 a/./b——统一 ${p%/}；比较相等先归一，否则哈希不同。
- 大小写分裂：Linux 敏感、macOS APFS 默认不敏感——同名文件
  两端各是各的真相；缓存键与去重先同形归一（小写+realpath）。
- 空格与特殊字符是常态：拼接走引号+数组（27 节）；测试集
  必含空格、引号、换行、- 开头、中文文件名——95 节 evil
  names 是这一条的执行层。

```bash
cd -- "$(dirname -- "$0")" && here=$(pwd -P)
case $(uname -s) in Darwin) rp() { cd -P -- "$1" && pwd; };;
  *) rp() { realpath -m -- "$1"; };; esac
target=$(rp "${here}/../releases/$rev")
```

- 心法：路径不是字符串，是"解析器+文件系统语义"的组合体
  ——跨平台路径评审按这个复杂度对待，而不是当成拼接。

## 95. 测试矩阵：dash/busybox 的 CI 策略

- 最小矩阵四格：debian（dash+GNU）、alpine（busybox）、ubuntu
  LTS（新 bash）、最老目标环境——同一 smoke 集四格全跑才行。
- 静态门禁先于运行：bash -n、dash -n 秒级；shellcheck
  --shell=sh 拦 bashism（89 节），比运行期报错便宜百倍。
- 运行层用 bats/自写断言、输出 TAP；测试跑在容器内——宿主
  工具版本漂移会污染矩阵结论，这是搭矩阵的第一意义。
- evil 文件名集：空格、单双引号、换行、- 开头、中文、
  emoji（94 节）——遍历/删除/打包类脚本必须过这套样例；
  样例生成脚本进版本库，别临场手搓。
- 成本控制：语法与 shellcheck 每 PR 全跑，四镜像运行矩阵
  nightly+release 前跑——别让全量矩阵拖垮每次提交，测试
  预算也是预算。

```bash
for img in debian:bookworm alpine:3 ubuntu:24.04; do
  docker run --rm -v "$PWD:/t" -w /t "$img" sh -c './scripts/smoke.t'
done
shellcheck --shell=sh -S style scripts/*.sh
```

- 矩阵哲学：不是证明"哪里都能跑"，而是提前知道"哪里不能
  跑"——每个红格对应文档里一行支持声明，矩阵即文档的实证
  部分。

## 96. 最小工具集与降级路径

- 依赖预算：头部声明清单（requires: POSIX XCU + busybox awk），
  评审照单检查——"顺手 gawk -i"三年后变迁移拦路虎。
- busybox 子集现实：busybox awk 是受限实现（缺 gawk 的部分
  函数）、sed 老 -r 无 -E、grep 无 -P——目标含 Alpine 就按
  最小公倍数写，gawk 增强功能要探测后才可用。
- 纯内建回退阶梯：read var 代替 $(cat file)、${var%?} 代替
  cut 截尾、$(( )) 代替 expr、printf 代替外部 echo——降级
  优先内建，其次 busybox applet，最后 GNU 满血版。
- 探测三段式（8 节）：command -v 探存在 → --help 探选项 →
  小样例探行为——只探存在不够，"有 awk 但没那个函数"比
  "没有 awk"更晚爆炸。
- 无 shell 是新常态：distroless 没有 sh——运维逻辑做成
  sidecar 或 k8s Job；"目标机有 shell"这个前提本身要被质疑。

```bash
# 降级路径示例：行数统计
if command -v wc >/dev/null; then n=$(wc -l < f)
else n=0; while read -r _; do n=$((n+1)); done < f; fi
```

- 设计立场：最小依赖不是苦行，是把"部署面"当第一级需求
  ——能用 30 个 POSIX 工具解决的，就不引入第 31 个只在
  半数环境存在的命令。

## 97. set -x 噪声治理与 PS4 增强

- PS4 是 xtrace 的免费午餐：`PS4='+ ${BASH_SOURCE##*/}:$LINENO: '`
  带文件行号、加 $EPOCHREALTIME 变逐行剖析（77 节）——默认
  的 "+ " 什么都回答不了。
- 区域开关而非全局裸开：`{ set -x; target_fn; set +x; }
  2>trace` 包住目标函数——xtrace 走 stderr，区间重定向顺手
  收进文件，业务流保持干净（98 节的轻量版）。
- 噪声折叠：变量展开态一行几十个 + 是洪水——awk 过滤折叠
  重复噪声；trace 是给"检索"用的不是给"通读"用的（102 节）。
- BASH_COMMAND 联动 ERR trap：trap 里打印"刚失败的命令
  原文"——错误日志带命令文本与行号，比栈回溯实用；106
  节清理失败可用同一手法报现场。
- 常见误用：全局 set -x 让 stderr 混进 CI 日志解析、敏感
  展开落盘（99 节）——先定"谁看这份 trace、看完去哪"，再
  决定开关范围。

```bash
PS4='+ ${BASH_SOURCE##*/}:$LINENO: '
{ set -x; rebuild_cache; set +x; } 2>/tmp/rebuild.xtrace
trap 'echo "FAILED at: $BASH_COMMAND" >&2' ERR
```

- 治理原则：xtrace 的产出是给排障者的数据不是日志系统——
  范围最小化、信息密度最大化、事必脱敏，三者都要显式设计。

## 98. BASH_XTRACEFD：xtrace 分流到文件

- 机制：先 `exec {xt}>trace.log` 打开专属 fd，再
  BASH_XTRACEFD=$xt——xtrace 全部改道该 fd，stderr 不再被
  + 号洪水污染；fd 未打开就赋值，bash 只给警告且 trace 丢。
- 与 exec 协作（74 节）：先备份 stdout 再全局改道，XTRACEFD
  的 fd 独立成河——业务输出与 trace 两条线，事后各自分析。
- 子 shell 自动继承：扇出（69 节）trace 汇入同一文件——PS4
  加 $BASHPID 才能按进程分组，否则交错无法重放（102 节）。
- 翻车点：退出前不关闭 fd，缓冲里的最后几行 trace 可能没
  落盘——EXIT trap 里显式 `exec {xt}>&-`；这是"trace 少了
  最后一条恰好最关键"的元凶。
- 环境剥离失效：BASH_XTRACEFD 是普通变量，env_reset 的
  sudo、净化的 cron（108 节）拿不到——回退 97 节区间重定向。

```bash
exec {xt}>/tmp/job.$$.xtrace
BASH_XTRACEFD=$xt
PS4='+ $EPOCHREALTIME $$ ${BASH_SOURCE##*/}:$LINENO '
main "$@"
exec {xt}>&-        # 退出前显式关，防丢尾部 trace
```

- 设计意图：xtrace 分流不止"让日志干净"——它把 trace 变成
  可机器分析的数据资产：98 给原料，102 给流水线。

## 99. xtrace 泄密与 CI 脱敏

- xtrace 打展开后命令：TOKEN=secret curl -H "Auth: $SECRET"
  在 trace 里是**明文**——set -x 与秘密同场即泄（83 节矩阵）。
- 分层静默法：秘密操作包进 `set +x; ...; set -x` 区间——
  trace 只见开关行；或下沉到不 trace 的子 shell/helper。
- CI 脱敏边界：GHA secret 自动 mask、::add-mask:: 补——只作用
  于 runner 日志；98 节自管 trace、tee 出的日志不在范围，
  权限 600（82 节）+事后销毁是底线。
- 环境快照泄密：调试时 env、set（内建）dump 全部变量——先
  unset 秘密再 dump 或过滤后落盘；容器里调试尤其过这道闸。
- 制度面：允许 set -x 的生产脚本，评审必附"秘密出现位置
  清单"（110 节）——每个秘密变量标注"在 trace 里如何不可见"
  ，答不上不许合入。

```bash
set +x
TOKEN=$(read_secret)    # 这一区间不落 trace
curl -sfH "Authorization: Bearer $TOKEN" "$url"
set -x
```

- 复盘视角：泄密的 trace 比明文配置更危险——它是带时间戳
  的操作证据，外泄连攻击窗口都标好了；trace 是易腐品，
  用后即焚写进 runbook。

## 100. shellcheck 指令纪律与豁免治理

- 豁免带理由+最小作用域：disable=SC2086 # 有意分词——行级
  优于文件级；无理由=打回，全文件 disable 即退出体系。
- 两大高发先修后豁免：SC2155（local 吞码，12 节）、SC2086
  （裸变量分词，26 节）——九成豁免是偷懒不是必要。
- source= 优于 SC1091 豁免：`# shellcheck source=lib/common.sh`
  让跨文件符号可查——让工具看见结构，比让它闭嘴收益大一个量级。
- 严格项棘轮：--enable=require-variable-braces 等渐进开启
  ——豁免总数只降不升（CI 统计 disable 行数），新增不增豁免。
- 定位边界：shellcheck 是语法与模式级 linter，对管道死锁
  （59 节）、竞态（80 节）、协议假设（66 节）不报警——绿灯
  =过了底线，不等于正确。

```bash
# shellcheck source=lib/common.sh
source "$(dirname "$0")/lib/common.sh"
# shellcheck disable=SC2086  # 有意分词：喂给 xargs
xargs -n1 process $items
```

- 治理哲学：豁免是**债务台账**不是开关——每条有理由、有
  复查日期、有总数预算；台账膨胀到阈值就集中清偿，而不是
  继续加息。

## 101. DEBUG trap 与函数级追踪术

- 机制：`trap 'hook' DEBUG` 在每条命令执行前触发，BASH_COMMAND
  给命令文本——用前后差分自建追踪库（计时、计数、依赖记录）。
- 函数级计时：DEBUG 记 ${FUNCNAME[0]} 与时间戳、RETURN 算差值
  ——零改造得函数耗时表；前提 set -o functrace 才渗透函数体。
- 渗透第三代码：给库函数包 wrapper 记录调用与耗时，比 sed 改造
  安全；但 DEBUG 会被后装 trap 覆盖——要检查钩子存活。
- 坑：DEBUG 在子 shell 与命令替换内同样触发——记录必须带
  $BASHPID 区分进程，否则扇出（69 节）trace 交错成谜。
- 成本红线：每命令一次 hook，热循环慢 3-10 倍——生产常驻不开，
  采样诊断或 CI 跑全程；敏感路径用 77 节 PS4 剖析。

```bash
set -o functrace; _last=$EPOCHREALTIME
_debug() {
  d=$(awk -v a="$EPOCHREALTIME" -v b="$_last" 'BEGIN{print (a-b)*1000}')
  printf '[%s] %sms %s\n' "$BASHPID" "$d" "${BASH_COMMAND%% *}" >&2
  _last=$EPOCHREALTIME; }
trap _debug DEBUG
```

- 定位：DEBUG trap 是"给 bash 装 dtrace"的平民版——值得进
  工具库，但只在诊断会话装配，不当常驻中间件。

## 102. trace 后处理与 bashdb

- bashdb：真断点、单步、栈查看（DEBUG trap 实现）——交互
  排障专用；生产不可依赖它，但复现诡异 bug 时值回票价。
- PS4 日志 awk 化：+ 前缀字符数=嵌套深度（每层复制 PS4 首字符）
  ——统计深度分布、时间戳差分耗时、聚类热点函数，98 节文件
  在这里变资产。
- 时间轴重建：扇出作业 trace 混在一个文件（69 节）——PS4
  带 $BASHPID 后按进程分组、按时间戳排序，并发交错才可读；
  这是并发脚本排障的核心技术，没有它只能 println 猜。
- 最小事后工具箱：grep '^+' 找异常出口、awk 找最大时间 gap
  定位卡点、uniq -c 命令频次定位热点循环——三个 one-liner
  覆盖八成"脚本到底卡在哪"。
- 留痕纪律：trace 文件含敏感展开（99 节）——归档前过滤或
  销毁；debug 日志不是长期资产，按 80 节 mktemp 纪律当
  待清理临时产物管理。

```bash
awk '{d=gsub(/^\+/,""); print d}' job.xtrace | sort | uniq -c
awk '/^\+/ {split($2,a,"."); if(prev)print a[1]-prev; prev=a[1]}' \
  job.xtrace | sort -rn | head
```

- 方法论：trace 后处理是"把 shell 当可观测系统"——深度、
  耗时、频次、时间轴四类视图，把九成谜题变成数据问题。

## 103. 结构化日志与 ANSI 污染

- 分工铁律：stdout=数据（被管道消费）、stderr=日志（给人看）
  ——stdout 混日志则下游 jq/awk 全炸，"管道第一戒"（58 节同源）。
- 结构化选型：单行 JSON（level/ts/msg）可被 jq 聚合——自由
  文本六个月后没人读得懂；ts 用 printf %(...)T 零 fork（75 节）。
- ANSI 污染：彩色重定向进文件后 \033[ 满屏——发色前 [ -t 1 ]
  探测 tty、尊重 NO_COLOR；CI 诱导的颜色（GITHUB_ACTIONS）单独识别。
- logger 进 syslog：logger -t myapp -p user.info——systemd 用
  journalctl -t 聚合、-s 双写；比自建轮转便宜，消息要可 grep。
- 级别由调用方控制：LOG_LEVEL 环境变量+log() 比较后决定
  输出——硬编码 echo 的老脚本不可编排；这同时是 107 节
  契约面：怎么吵由调用方定。

```bash
log() { # usage: log INFO msg...
  local lvl=$1; shift
  [[ ",${LOG_LEVEL:-INFO}," == *",$lvl,"* ]] || return 0
  printf '%(%FT%T)T [%s] %s\n' -1 "$lvl" "$*" >&2
}
NO_COLOR=1 mytool > data.jsonl 2> run.log
```

- 验收法：`./tool | jq .` 能解析、`2>` 后 stdout 纯净——
  两条都绿，日志架构才算合格，别靠肉眼。

## 104. 幂等设计：收敛式变更

- 收敛优于重放：每次运行把系统推到**声明的目标状态**，
  而不是记录"做过哪些步骤"——重放日志与真实状态漂移是
  配置管理所有事故的共同祖先。
- 收敛原语集：mkdir -p、ln -sfn、install -m、mv -f 配 81 节原子
  写——"做一次=做十次"；反例：>> 累加、date 后缀命名、依赖
  上一步存在与否的分支。
- 标记要可验证：标记文件存在 ≠ 变更生效——终态校验读真实
  世界（端口、版本、哈希）；"标记说装了实际没装"是经典自欺。
- 检查-再执行窗口：`[ ! -f x ] && create` 两步间别人先建——
  原子原语一步收敛或 flock 缩窗（62 节）；并发部署（105 节）必爆。
- 幂等测试法：CI 连跑两遍 diff 系统状态（文件清单+哈希+
  权限）零变化才算达标——把"跑两遍"固化为门禁，幂等从
  口号变指标。

```bash
# 收敛式片段：不管当前什么状态，跑完都一样
mkdir -p /srv/app
install -m 644 config.new /srv/app/config   # 81 节原子写同律
ln -sfn "releases/$rev" /srv/app/current    # 声明目标指针
```

- 判别口诀：删掉所有 if 脚本还能保证终态吗——收敛式答
  "能"，重放式答"要看之前跑没跑过"；后者就该重构。

## 105. 锁生命周期与陈旧锁恢复

- flock 生命周期=fd 生命周期：进程死、内核关 fd、锁自动释放
  ——"忘了释放"这类事故在设计层被消灭（62 节是操作层）。
- pidfile 锁谱系：持有者被 OOM 杀、文件残留、下实例不敢进场；
  检测链（70 节）每环有竞态——自制锁比 flock 多一整类故障。
- 长任务心跳制：超十分钟的作业周期 touch 心跳——监控判"锁
  持有但心跳停"才报死，观察进展而非存在，比 PID 可靠。
- 超时+告警优于强拆：flock -w 300 报警等人——自动删锁是双实例
  的开端（63 节）；"宁可停一停，不可双双跑"是锁的价值观。
- 排队还是拒绝要显式：-n 拒绝（cron 单例）、-w 排队（手动触发）
  ——语义映射退出码 75/失败（107 节），调用方不该靠猜。

```bash
exec 9>/run/backup.lock
if ! flock -n 9; then echo "busy（心跳 $(cat /run/backup.heartbeat 2>/dev/null)）" >&2
  exit 75                    # EX_TEMPFAIL：请稍后重试
fi
touch /run/backup.heartbeat   # 长任务周期 touch
```

- 恢复策略分档：自动重试（75 语义）→ 告警人工确认 → 事后
  复盘改锁粒度——三档写进 runbook，"锁住不动"从现象变成
  有预案的已知状态。

## 106. trap 清理正确性：资源登记簿模式

- 登记簿模式：cleanup() 只遍历 CLEANUP 数组执行后清空；资源在
  **创建点**登记 `CLEANUP+=("rm -rf $tmp")`——清理不散落，评审
  从"全文找"变成"找登记行"。
- trap 覆盖是地雷：后 trap EXIT 覆盖前 trap——库与主脚本各装
  时一方清理被吞；登记簿统一安装一次即免疫。
- 信号面要齐：trap cleanup EXIT HUP INT TERM——HUP 是 cron/
  tmux 常态；kill -9 是永久盲区——一致性只能靠 81 节原子写
  兜底，把"最坏半截状态"设计成可恢复。
- 清理要幂等：rm -rf "$tmp" 前判非空（80 节）、锁删前确认属主
  （105 节）——EXIT 与 TERM 竞速触发也不误删、不二次伤害。
- exec 盲区：trap 内 exec 替换进程、跳过其余清理——锁交接
  （105 节）高发；交接要么完成后再 exec，要么列出跨 exec 存活者。

```bash
CLEANUP=()
cleanup() { local c; for c in "${CLEANUP[@]}"; do eval "$c"; done; CLEANUP=(); }
trap cleanup EXIT HUP INT TERM
tmp=$(mktemp -d); CLEANUP+=("rm -rf $tmp")
```

- 自检法：对每个可能中途死掉的位置问"此刻谁收拾"——每个
  资源都能指到登记簿一行就是对的；指不到的，就是下一个
  事故复盘的主角。

## 107. 面向调用方的退出码 API

- 分层契约：0=成功；1=业务失败（文档化）；2=用法错误；64-78
  预留 sysexits.h——64 EX_USAGE、69 EX_UNAVAILABLE、75
  EX_TEMPFAIL、77 EX_NOPERM、78 EX_CONFIG，参数/数据/配置分家。
- 75 EX_TEMPFAIL 是隐藏宝石：瞬时故障（锁忙 105 节、429、DNS
  抖动）退 75——重试器知道"稍后再试"；瞬时/永久的区分决定自愈上限。
- 退出码是公共 API：改脚本不改语义——文档列每码与处置建议；
  255 留给内部意外（它同时是 bash 回收值），别当业务语义。
- 三通道各司其职：stdout=结果、stderr=诊断、退出码=机器判定
  （103 节）——只 grep stderr 的调用方是脆弱契约。
- 子进程退码透传：EXIT trap 里 `exit $?` 保住退出码（106
  节清理不吞码）；die() 封装统一出口、case 分派映射业务码
  ——出口收敛了，契约才管得住。

```bash
die() { local rc=$1; shift; echo "E: $*" >&2; exit "$rc"; }
usage() { die 64 "usage: $0 --id ID"; }         # EX_USAGE
flock -n 9 || die 75 "another instance running" # EX_TEMPFAIL
[ -r "$conf" ] || die 78 "config unreadable"    # EX_CONFIG
trap 'rc=$?; cleanup; exit $rc' EXIT            # 清理不吞码
```

- 契约测试：mock 三个失败路径断言退出码——没有退出码断言
  的集成测试，等于没测"调用方该怎么办"这件最重要的事。

## 108. cron 环境：PATH 极简与 % 转义

- PATH 是头号杀手：cron 默认只有 /usr/bin:/bin——node、nvm
  装的命令全炸；二选一写死：crontab 顶部 PATH= 或绝对路径。
- % 是换行符：`date +%F` 变成 `date +` 加一坨 stdin 文本，任务
  静默产出空文件——写 \% 转义，并写进 crontab 头注释提醒后人。
- SHELL=/bin/sh 默认：cron 不认 bashism（89 节）——crontab 设
  SHELL=/bin/bash，或任务行显式调 /bin/bash script.sh。
- 环境是快照非登录：无 .bashrc、TZ 不同——用 env -i HOME=$HOME
  /bin/sh -c 'task' 本地模拟排障，比对着 crontab 猜快十倍。
- 互斥与超时必配：cron 不等也不限——入口 flock -n（62 节）、
  锁忙退 75（107 节）、timeout 封顶（112 节原型）；MAILTO 治理，
  邮件疲劳会让真告警被忽略。

```bash
SHELL=/bin/bash
PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
# % 必须 \%% 转义；单例+超时+退码三件套：
10 2 * * * flock -n /run/backup.lock timeout 3600 \
  /usr/local/bin/backup.sh >> /var/log/backup.log 2>\&1
```

- 上线前自检五项：PATH、% 转义、SHELL、锁、超时——五项全
  勾才进 crontab；这是 112 节事故换来的清单，别用生产事故
  补课。

## 109. CI shell 语义：GitHub Actions 深水

- 默认有 -e 无 pipefail：`run:` 走 `bash -e {0}`——管道左侧失败
  被吞；显式 `shell: bash` 才是 `--noprofile --norc -eo pipefail
  {0}`；job 级 defaults 一次配齐，别每个 step 赌默认。
- ${{ }} 是编译期拼接：`run: deploy ${{ inputs.env }}` 织进
  不可信输入=eval 注入（79 节）——正解 env: 通道+$ENV 引用。
- BASH_ENV 机制：GHA 给 bash step 设 BASH_ENV 指向临时初始化
  脚本（5 节注入面）——既是钩子（统一 pipefail、PS4）也是审计
  点：echo "$BASH_ENV" 看平台替你注入了什么。
- secret mask 边界：自动 mask 只作用 runner 日志——自管 trace
  （98 节）、artifact、自建日志不在保护范围；xtrace 泄密（99
  节）在 CI 同样成立，且日志受众比你想的广。
- 工具链漂移：runner 的 GNU 皆最新，生产机未必（92 节）——
  矩阵加"最老目标环境"镜像（95 节），绿灯才有预测力。

```yaml
defaults: { run: { shell: bash } }   # -eo pipefail 全开
steps:
  - run: deploy --env "$DEPLOY_ENV"
    env: { DEPLOY_ENV: "${{ inputs.env }}" }  # 数据走 env
```

- 心法：workflow 当代码评审——run: 里每个变量都要回答"从哪
  来、能否被 PR 作者控制"；答不出的就是下一个安全公告。

## 110. 高频反模式速查清单

- 解析 ls：`for f in $(ls)` 空格、glob、换行三连炸——glob 或
  find -print0（71 节）；ls 输出给人看，列宽美化随时会加。
- 无守卫删除：`rm -rf $VAR` 在 VAR 空时变成 rm -rf /——
  `: "${VAR:?}"` 先行、-- 终止符跟上（87 节）；变量空即停
  是删除类脚本的法定第一行。
- sudo 重定向错觉：`sudo echo x > /root/f` 权限错误，重定向
  归你自己的 shell（85 节）——sudo tee；同族还有 `sudo cmd |
  grep` 里 grep 的权限幻觉。
- backtick 与旧语法：嵌套转义地狱、视觉歧义——$( ) 唯一；
  echo 变量（91 节）与 [ $x ] 裸变量（90 节）是同代遗产，
  printf 与引号收编。
- 幻觉类三件套：grep 零匹配退 1 被 -e 吞（11/111 节）、local
  x=$(cmd) 吞码（12 节）、trap 忘装（106 节）——评审逐条勾。

```bash
: "${TARGET:?TARGET must be set}"      # 删除前的法定断言
rm -rf -- "$TARGET/$sub"               # -- 双保险
for f in ./*.log                       # glob 代替解析 ls
printf '%s\n' "$list"                  # 代替 echo 杂技
```

- 使用法：这是 checklist 不是教材——每个反模式背后标着
  深度章节，勾到可疑处跳转阅读；价值在"评审十分钟扫完"。

## 111. 事故复盘：半吊子严格模式翻车

- 症状：凌晨批处理"静默跳过"近半任务——日志只有一行无输出
  的 grep 计数、退出码 0，调度毫无察觉；"退 0 但没干活"最危险。
- 取证：PS4+BASH_XTRACEFD 复跑（98 节），trace 显示在 grep 返回 1
  那行后直接走完 trap 退出——它在 for 体内，作者以为有 if 保护。
- 根因：只抄 `set -e` 一行就当"严格模式"——没配 if/|| 的显式
  错误归属，非零路径全是隐式地雷（11 节全图）；**-e 是契约不是
  开关**，启用即要求全脚本错误路径显式化，这个成本没付。
- 固化动作：封装 match() 返回布尔、循环内显式 `|| { fail+=(
  "$id"); continue; }`；CI 加 bats 复现"零匹配不炸"；评审清单加
  "每个可能非零的命令都有归属"（110 节）。
- 制度提级：三件套（-e -u -o pipefail）成套引入或成套不引入
  ——半吊子比不用更危险，"我们有严格模式"制造虚假安全感；
  上线前用零匹配、空文件、缺权限三类故障注入演练。

```bash
match() { grep -q "$1" "$2"; }        # 修复后：调用方只做布尔判定
for id in "${ids[@]}"; do
  match "$pat" "logs/$id" || { skipped+=("$id"); continue; }
  process "$id" || failed+=("$id")
done
```

- 一句话教训：set -e 的单位不是脚本而是**每个命令**——没
  被显式接管的非零返回值，都排在最不想要的时刻起爆。

## 112. 事故复盘：cron 并发与清理失效

- 症状：备份盘连三天告 85%，每晚 3-4 个备份实例互相覆盖 tmp、
  成品半截、旧 tmp 没人清——cron 每 5 分钟起一次、单次要 20
  分钟且从不等上一轮（108 节无互斥），叠罗汉常态化。
- 取证：ps 同命令多 PID 且起始时间各异；tmp 里多轮半截目录
  共存；日志被多进程交叉写入乱序——拼出"并发无锁+清理不可达"
  的组合故障，单看任何一个都会误判。
- 根因三层：入口无锁（实例自建 tmp 互相覆盖）；trap 在
  mktemp 后才装，被 OOM 杀的实例泄漏（106 节杀窗口）；cron 无
  超时、清理长在成功路径末尾失败即跳过——泄漏是设计注定。
- 固化动作：flock -n 锁入口、锁忙退 75（105/107 节）；trap
  先于 mktemp 装、登记簿清理（106 节）；timeout 封顶（108
  节）；tmp 按日期分桶、启动清旧桶——事故从复发变不可能。
- 清单化收尾：上线检查表=锁/超时/清理/退码/PATH 五项（108
  节），缺两项以上是系统性评审问题；cron 的"没人看着"是攻击面
  清单，缺的每一件都会变成凌晨三点的电话。
