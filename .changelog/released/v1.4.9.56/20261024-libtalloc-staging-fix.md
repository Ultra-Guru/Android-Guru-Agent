# 修复：proot 的 libtalloc.so.2 SONAME staging —— git/MCP/marketplace 三条通道全线恢复

- **根因（真机实证，OPPO PLE110 / Android 16）**：proot 的 `DT_NEEDED` 是
  `libtalloc.so.2`，而 Android 打包要求 jniLibs 以 `lib*.so` 命名（真实文件
  只能叫 `libtalloc.so`）。此前四份拷贝的兜底循环只做「同目录软链」：遍历
  LD_LIBRARY_PATH 目录，仅在**含 libtalloc.so** 的目录（nativeLibraryDir）
  建 `libtalloc.so.2` 链接——staging 目录（filesDir/linux/bin，LD_LIBRARY_PATH
  首项、可写性有保证）里没有 libtalloc.so，永远拿不到链接；而
  nativeLibraryDir 建链在部分真机**静默失败**（`runCatching` 吞掉异常）。
  结果 git status/clone（经 apex-workspace-kit 的 GitEngine → 宿主
  ProotGitCommandRunner）、MCP 进程、marketplace 沙箱三条 proot 通道
  全线 `CANNOT LINK EXECUTABLE libproot.so: library libtalloc.so.2 not found`。
  用户在 filesDir 手工建链后 proot 立即恢复——实证根因。
- **修复**：新增共享助手 `PRootLibtallocStaging`（platform:terminal），
  幂等地在 **LD_LIBRARY_PATH 每个目录**重建 SONAME 入口，统一指向真实
  libtalloc.so——staging 目录从此也有可用链接（linker 按序搜索，staging
  在首位先命中）。四处拷贝（git / MCP / tools spawner / marketplace）
  全部收敛到该助手，同时：
  - **悬空链接修复**：app 升级后 nativeLibraryDir 路径变化（新 pkg hash
    目录），staging 旧链接指向已删除目录成为悬空链接——`File.exists()`
    对悬空链接返回 false，旧代码既删不掉也建不了（`createSymbolicLink`
    抛 FileAlreadyExistsException），`PRootHostEnvironment.prepare()` 在
    升级后**整体失败**（终端 proot 通道全断）。助手显式判
    `isSymbolicLink && !exists` → 删除重建。
  - **失败可观测**：不再静默吞异常，逐目录返回 FAILED + 异常详情，
    调用方 WARN 留痕——下次诊断日志直接可见真实异常，不必再靠手工
    实验反推。
- 回归测试：`PRootLibtallocStagingTest`（6 例）锁定：全目录建链、幂等、
  悬空链接重建、libtalloc.so 缺失的 FAILED 可观测、LD_LIBRARY_PATH
  缺失空操作、`prepare()` 悬空链接场景成功。
