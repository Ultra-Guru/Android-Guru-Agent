<!-- pr: ssh-sftp-filesystem -->

- SSH/SFTP 远程文件系统（Operit §4.7 对标缺项补齐）：新增 RemoteSshFilesystem（SSHJ 0.38.0 纯 JVM 客户端），13 个操作覆盖 ping/read/write/append/list/stat/exists/mkdir/remove/move/copy/disconnect/fingerprint
- 二进制安全：读写全走 SFTP RemoteFile 字节通道（无 shell、无 base64 往返）；按 SshTarget 缓存 SSHClient，同一目标复用一条 TCP/SSH 连接
- 主机密钥安全默认：未知 host 拒绝（防 MITM），先 fingerprint 拿 SHA-256 指纹展示给用户，确认后一次性放行或持久信任
- 新增 TerminalSshFsTool：JSON-Schema 化 13 个 action + 结构化错误码（与 TerminalFsTool 同款 subaction 设计）
- DI 接线：TerminalModule 提供 KnownHostsStore + RemoteSshFilesystem；ToolModule 经独立 helper 注册（SRP 预算规避）
