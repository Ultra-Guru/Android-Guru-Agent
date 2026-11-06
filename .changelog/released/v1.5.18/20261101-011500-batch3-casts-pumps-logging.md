<!-- pr: batch3-casts-pumps-logging -->

- 修复看门狗 ActivityManager 不安全强转：极端 ROM 上 getSystemService 返回 null 时不再抛 ClassCastException，改为 Result.retry() 等待下次调度
- 修复 MCP stderr 泄放线程 BufferedReader 未显式关闭：对齐主 reader 的 #453 收尾口径，多次重连会话不再累积字符缓冲
- 工具 schema 清洗回退空对象时不再静默：限次日志留痕（首 3 次 + 之后每 50 条），工具调用能力被降级时可诊断
