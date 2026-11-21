<!-- pr: ssh-sftp-fs-compile-fix -->

- fix(terminal): 修复 SSH/SFTP 远程文件系统 5 处 Kotlin 编译错误阻断 CI 的问题：
  1. 修正 `RemoteSshFilesystem.kt` package 声明大小写/路径错误，统一为 `com.apex.agent.platform.terminal.fs`
  2. 移除 `SshFsResult.Err` 构造时错误添加的泛型参数（Err 是非泛型类）
  3. 修正参数位置错误：移除误传给 `cause: Throwable?` 位置的 `Long` 类型字面量 `0L`
  4. 修正 SSHJ API 调用：`connectionKeepAlive` 改为正确的 `keepAlive.keepAliveInterval` 设置
  5. 更新 `TerminalSshFsTool.kt` 中对应 import 路径匹配修正后的 package
- fix(ci): 恢复 `:platform:terminal:compileDebugKotlin` 编译通过，解除 Debug APK 构建阻塞
