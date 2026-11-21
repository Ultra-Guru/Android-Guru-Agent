<!-- pr: agent-task-packages-spec -->

- Agent Task Packages 规范：把「方案 → 实施」的翻译层标准化 —— 任务包六条硬约束（≤3 文件 / 验收可脚本化 / 回滚无残留 / 依赖显式 / ≤400 行 / device-required 标注）
- 分解方法四步（按数据流切 / DAG 并行标注 / 验收翻译成自查命令 / 风险自愈路径）+ 执行协议（AGENTS.md 注入 → 边界执行 → 单 commit 交付 → journal 恢复）
- 反模式清单六条（历史教训：半成品接口上 main / 不可 grep 的验收 / 一包跨 5+ 文件等）
