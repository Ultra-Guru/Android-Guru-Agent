<!-- pr: terminal-chroot-maturity-t99-spec -->

- T99 spec：chroot 后端生产化纵深方案 —— su 候选表统一（SuCandidateRegistry 单一事实源）/ 授权状态 30s TTL 缓存与负反馈修正 / mountpoint 三链守卫与孤儿挂载清理 / PRoot 冒烟探针与后端显式建议 / CA bundle 三链供给 / chroot 遥测旗标接活
- 六个 Agent 任务包（P1-P6）实施分解，JVM 侧全量可验证，依赖 DAG：P1→P2→(P3∥P4∥P5)→P6
- 系列导读块补充（文档前面）：四份姊妹 Spec 全景表 / 推荐阅读顺序（①方法论→②③运行时可并行→④展示层独立）/ 系列来源背景
