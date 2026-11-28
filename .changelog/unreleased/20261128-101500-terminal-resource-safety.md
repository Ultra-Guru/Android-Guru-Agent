<!-- pr: terminal-resource-safety -->

- 终端会话数量上限 16（SessionLimitReached 结构化报错）+ 满额驱逐最旧 IDLE（EXITED 优先于 READY，在用会话绝不驱逐）—— 失败循环反复建会话不再内存无界增长（#839）
- onTrimMemory 接入终端子系统（ApexCoreService 转发）：RUNNING_LOW+ 驱逐超出保留数 4 的 IDLE 会话，与 close 同一回收链
- PtySession 关闭补齐 setsid 守护孙进程清扫：信号梯度前 /proc children 递归快照后代，梯度后逐 pid SIGKILL 兜底（host 测试真机验证 setsid 守护进程被收编；util-linux setsid 孤儿形态为 pid 树不可达，与 Termux 同款边界如实记录）（#842）
