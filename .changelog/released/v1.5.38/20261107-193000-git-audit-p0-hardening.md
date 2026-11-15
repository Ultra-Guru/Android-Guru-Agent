<!-- pr: git-audit-p0 -->

- **Git 审计 P0 安全热修**（基线 main@aa104f6，46 项问题中的 P0 批次）——凭据外传链根修：GitHub PAT 不再注入任何进程 env（旧实现把 GH_TOKEN 写进整个 `sh -c` 进程，`git pull && curl` 复合命令与恶意仓库 hooks 可直接读走），改为一次性 GIT_ASKPASS 脚本（写入沙箱 /tmp、只应答 github.com 的凭据提示、命令结束即删、终端通道 30 分钟惰性清扫兜底）——终端里 gh 命令因此失去认证（引导走 GitHub 工具族）
- `CodeGitRun` 命令级门禁（GitCommandPolicy，解析后 argv 纯函数分类）：`-c`/`git config` 配置逃逸**硬拒绝**（credential.helper/hooksPath 可执行任意程序）、交互式检测改 argv 位置无关（旧 startsWith 被 `add  -p` 双空格/Tab 击穿）、`git -C` 词法校验必须落在工作区内、clone/push 远程 URL 仅 https + github.com 白名单（file://、ext::、userinfo 内嵌凭据全拒）、引号未闭合严格拒绝
- 权限门/风险门按命令分类：DESTRUCTIVE（reset --hard / push --force / clean -f 等）与 OUTBOUND（push/pull/fetch/clone）在任何权限档**逐次确认**（含 BYPASS，不提供会话级放行、拒绝不写会话状态）；READ 命令各模式直通（DENY 规则仍优先）；LOCAL_WRITE 保持原有确认面
- 终端命令级高危表补 git 破坏性形态（TerminalExec 自由命令通道兜底防线，此前零 git 覆盖）
- MCP 令牌桥白名单（fail-closed）：`${GITHUB_TOKEN}` 占位符仅官方 github-official 预置（serverName + URL host 双校验）可解析——恶意市场配置借占位符窃取 PAT 的通路关闭；第三方含占位符配置升级后连接将被拒绝并给出指引
- GitHub API host 锁：分页 Link 跟随仅 https + api.github.com（Bearer 头不再可能发往任意主机/明文 http）；OkHttp 改 enqueue + invokeOnCancellation（协程取消毫秒级打断在途请求，旧同步 execute 叠加重试最长挂 3 分钟）；**POST/PUT 不再自动重试**（旧实现 429/5xx 重发会重复建 Issue/PR/提交）
- 私有仓写防线统一：REST（GithubWriteFile）与内置 MCP（create_or_update_file）共用 GithubWriteGuard（可见性探针 + confirm_private_write，fail-closed）——旧版 MCP 通路可绕过 #273/#274 防线
- GitHub Token 存储.fail-closed：加密存储初始化失败不再静默降级明文落盘（改纯内存、重启重连），启动时自动把历史明文残留迁回加密存储并删除；token 保存 trim；data_extraction_rules 补 file domain 排除（logs/ 审计日志、crash/、mcp_config/、rootfs/）
- GitHub 写工具显式风险元数据：GithubWriteFile / GithubCreateIssue / GithubCreatePullRequest = HIGH + openWorld + 非幂等 + sensitiveAction（BYPASS 档也保持逐次确认）
- 新增回归测试：GitCommandPolicy 分类矩阵、GitRunTool 策略集成、注入器/凭据签发器、API 白名单与幂等重试、MCP 令牌桥、双门禁 git 行为、终端高危表 git 模式
