<!-- pr: searxng-local-search -->

- 全新「本地搜索（SearXNG）」开关：在内置 PRoot Ubuntu 沙箱中运行私有 SearXNG 元搜索实例（git 源码 + venv + pip 运行时安装，幂等断点续装），设置页一键启停，全程状态机可视（安装阶段进度 → 启动中 → 运行中绿点 → 失败重试）
- Agent 搜索优先本地：WebSearchTool 回退链链首插入 SearXNG 本地实例（127.0.0.1:8888 JSON API），就绪时优先聚合 Google/Bing/DDG 等 6 个可自选引擎，不可用时静默回退 DDG→DDG-Lite→Bing 远程链，用户与模型全程无感
- 守护进程可靠性：15s 周期健康看门狗 + 真实退出码观察者双通道自愈（连续 3 次失联自动重启，预算封顶防崩溃循环）；进程挂在 TerminalRuntime headless 会话上（specialUse 前台服务存续期间持久运行，服务收尾统一清场不留僵尸）
- 持久化与自恢复：开关意图落盘，App 重启 / 开机（BootReceiver 携带启动 action）/ 服务重建三路 reconcile 幂等对账恢复；引擎多选变更即时生效（运行中自动重启守护）
- 维护入口：设置页「排查与维护」二级区提供实例地址展示（F12 排查）与「清除已安装的搜索组件」（删除 /opt/searxng，约 300MB，偏好保留）；中文/英文文案全量镜像
