<!-- pr: issue-825-truncator-hot-update -->

- 修复：工具输出截断预算热更新失效（#825）——toolTruncator 冻结于引擎构造期的 maxToolOutputLength，设置页 patchConfig/updateConfig 热更新后截断阈值静默失效，深潜任务回灌的输出预算与设置页显示值脱节
- 预算变更时整体换 ToolOutputTruncator 实例（head/tail 收敛在构造期完成，不可中途改字段），updateConfig 与 patchConfig 两条配置路径均接上 syncTruncatorBudget
