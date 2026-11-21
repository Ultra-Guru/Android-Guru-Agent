# 浏览器 Runtime 性能基准协议

指标：工具派发延迟 / 导航→DOM_READY / selector 等待 / 快照生成时间 / 快照字符与 token 量 / 单任务模型往返次数 / 多 tab 并行吞吐 / 端到端任务耗时。
口径：每项 ≥20 次采样，报 **P50/P95**（不报单次最快）；分冷启动/热启动 × 简单静态页/复杂 SPA。
对比组：①阻塞 BrowserNavigate vs BrowserNavigateAsync+DocState(wait) 端到端；②BrowserTask 一趟 vs 等价单步工具 N 次往返（含模型推理间隔，取 tracer.durationMs 与会话时戳差）；③FULL vs focused/diff 快照字符量与 token 估算。
数据源：BrowserTracer 记录（durationMs/URL/state）+ 真机走查日志。基线数字待真机实测后回填本表（不预设"快百倍"口径，实测为准）。
