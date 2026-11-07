<!-- pr: permission-degradation-ladder-spec -->

- 权限诚实降级体系 spec：未授权 = 沿声明式降级阶梯（PrivilegeLadder 六域）走到最高可执行层级，走不到则以类型化错误如实告知（含修复指引）
- 四个缺口清偿：降级阶梯声明式化（现状散落在三处工具 if/else）/ 能力面统一（CapabilityReport 权限行 + 阶梯结论进 prompt 摘要）/ ShellRouter 选路归一（Detector 与 Manager 两套并行实现收敛，差异显式化为 FallbackPolicy）/ PrivilegeError 五码命名空间（对齐 TerminalError 惯例）
- 授权引导闭环：fixHint 单源话术（zh/en）+ 状态流回灌即刻刷新，模型不再反复撞墙
- 系列导读块补充（文档前面）：四份姊妹 Spec 全景表 / 推荐阅读顺序（与 T99 共享授权缓存的并行关系）/ 系列来源背景
