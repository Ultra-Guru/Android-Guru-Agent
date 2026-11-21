<!-- pr: mcp-stdio-reader-close -->

- 修复 MCP stdio 传输层资源回收：会话关闭时显式关闭 stdout reader，多次重连 MCP 服务器后不再累积字符缓冲内存
