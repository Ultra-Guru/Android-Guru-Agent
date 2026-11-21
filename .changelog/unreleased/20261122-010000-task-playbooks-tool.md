<!-- pr: task-playbooks-tool -->

- 任务剧本 agent 工具通道：PlaybookQuery 工具三动作（list 剧本清单+挂载态 / stage 按需取幕子提示词与验收 / advance 自报幕完成）——agent 主动面补全，与指令注入被动面互补
- 幕推进双通道调和：回包章节头判定（被动）+ agent 工具自报（主动，显式工具调用语义信任直推）汇入同一控制器状态机；推进即系统消息 + extras 热重织
- PlaybookSessionStore 会话桥：控制器快照下行 / 工具命令上行（replay=1 不丢首令），工具线程永不直接改 VM 状态
- 工具注册挂在 PlaybookModule 供给链异步补注册（ToolModule 贴预算红线不碰，registryVersion 自增驱动 UI 快照失效）
- 末幕 advance 引导输出《剧本完成》收尾而非硬推；坏参数/越界/误报 id 全防御式报错
- 新增 PlaybookToolTest 10 例（三动作/快照桥/末幕引导/坏参数）+ 控制器手动推进与快照发布测试
