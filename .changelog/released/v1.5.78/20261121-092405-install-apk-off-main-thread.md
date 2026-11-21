<!-- pr: install-apk-off-main-thread -->

- 修复（#819 P0）：APK 安装的 cache 中转复制（arm64 约 50-117MB / universal 可达数百 MB）不再阻塞主线程 —— installApk 全链改挂起函数（IO 调度器执行复制），点击「立即安装」到拉起系统安装器全程 UI 流畅，彻底消除输入派发 ANR（本仓 AnrWatchdog 5s 阈值必命中的冻结源）
- 复制前空间预检：cache 剩余空间不足（文件体积 + 16MB 余量）直接跳过 cache 中转走 external-path 兜底，不再「复制中途 IOException 卡很久然后失败」
- 复制进度可见：UpdateCenter 新增安装拉起态（busy 防重入 + 0-100 进度流），关于页本地更新包常驻区顶部进度条、全量包就绪框与增量就绪框均展示复制百分比，按钮同步禁用防重入
- 每约 1MB 复制检查协程活性：页面退出/重复请求即时中止复制；协程取消不再被 runCatching 折叠成失败（CancellationException 单独重抛，取消传播不断链）
