<!-- pr: ubuntu-dpkg-interrupted-autoheal -->

- 修复 Ubuntu 引导「dpkg was interrupted」死循环：apt 安装被中断（超时强杀/进程死亡）后，重试安装会在 dpkg 前置检查立即失败并永久挂起「降级就绪」注记；现在自动执行 `dpkg --configure -a` 完成半装包配置并重试一次，引导自愈为完整就绪（控制台退役后用户没有手动修复通道，这是唯一出路）
- 健康检查新增 `dpkg --audit` 半装状态检测：此前用 `dpkg --version` 判健康对半装完全无感，自动修复服务永不触发；现在归入可修复维度并给出半装包清单
- 引导实际执行后自动失效能力探测缓存：半装的 git 修复后立即从 BROKEN 翻为 AVAILABLE，不再被 5 分钟 TTL 缓存卡住旧状态
- 开发环境安装页的 apt 类安装命令前挂非交互 `dpkg --configure -a` 自愈前缀：环境安装按钮同样免疫 dpkg 中断死循环（干净态秒级 no-op，不污染交互会话）
