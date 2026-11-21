<!-- pr: remove-android-shell -->

- 后端收敛：生产注册表仅剩 linux-ubuntu（TerminalModule DI 移除 LocalShellBackend 装配）；LocalShellBackend 类移入测试源集作为朴素 shell 夹具（argv/env golden 形态保留，契约/IPC/端到端测试继续可用），androidTest 同步复制
- 路由与契约：TerminalRuntime.create() 默认 backendId "local"→"linux-ubuntu"、shell 参数随 shellHint 一并移除（TerminalCreateTool schema/解析同步收敛，backend 枚举仅 linux-ubuntu）；BackendRuntimeType 枚举移除 ANDROID_LOCAL；workspaceId 仅 LINUX 后端的死守卫移除（全后端皆 LINUX）；BackendStatus/CreateResult 缺省值与 TerminalSdk/TerminalService/TerminalIpcController 默认后端全部对齐 linux-ubuntu；LegacyTerminalManager 兼容层（唯一 shell 位置消费者）删除
- UI 收口：TerminalScreen 四处「先用 Android / Android 会话」入口移除（空态覆盖层统一「重试/安装 Ubuntu」引导）；会话标签回落标签统一 Ubuntu；EnvironmentCenterSheet Android 本地 Shell 卡删除（三卡→双卡）；TerminalViewModel 本地 mksh profile 注入与 init 自动降级本地会话移除，restartActiveSession 一律回 linux-ubuntu
- 降级路径收口：TerminalDepCenter 依赖安装会话路由移除 local 降级（Ubuntu 不可用如实报错引导安装）；EnvironmentProvisioner.sessionForApt 移除 local 兜底（apt 只在 Ubuntu 可用）
- SessionRegistry：BACKEND_LOCAL/BACKEND_AGENT 常量删除，无创建方记录的会话回落 linux-ubuntu，inferRuntimeType 未知 shell → UNKNOWN
- 字符串双语清理：term_use_android_first / term_new_android_session / term_tab_android / term_android_shell_* / term_notice_fallback_android / term_notice_fallback_session / term_builtin 八键（values/ 与 values-zh/ 镜像同步）
- 测试同步：ExecutionBackendGoldenTest 删除（golden 对象已是测试夹具）；TerminalRuntimeBackendTest 语义重排（显式 local 路由 + 默认 linux-ubuntu + workspace 死守卫用例删除）；契约/压力/控制面/观察/协议/端到端/IPC 测试构造补夹具注册表；UbuntuTerminalRuntimeWiringTest W4 更名夹具语义
