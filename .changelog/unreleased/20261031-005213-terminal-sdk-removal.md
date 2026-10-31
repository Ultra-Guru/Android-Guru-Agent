<!-- pr: terminal-sdk-removal -->

- TerminalSdk 门面类删除（385 行）：零实例化点的 ATR 2.0「冻结公共 API」适配层 —— 生产路径一直是 TerminalRuntime + terminal.* 工具直连，门面从未接入；删除后 TerminalApi.kt 的冻结契约类型（TerminalSize / TerminalCapabilities / TerminalApiVersion 等被 Linux 运行时与 IPC 引用的活类型）保留不动
