<!-- pr: debug-output-applogger -->

- 4 处绕过统一日志框架的调试输出收口到 AppLogger：InputManagerImpl 未映射键告警与 RealVirtualTerminal 引擎关闭失败（System.err → warn）、DefaultPrivilegeManager Shizuku 监听注册失败与 ApexAccessibilityService 遍历子节点失败（Log.d → debug，语义不变）—— release 构建不再向 stderr/logcat 泄内部状态，日志查看器可按类别统一筛选
- platform:terminal 新增 core:logging 依赖（纯 JVM 模块，「纯 JVM 库」边界不变），TERMINAL_SDK_BOUNDARY 依赖图同步
