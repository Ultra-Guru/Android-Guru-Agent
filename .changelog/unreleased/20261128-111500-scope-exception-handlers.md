<!-- pr: scope-exception-handlers -->

- 应用层 37 处自建 CoroutineScope 统一经 AppScopes 工厂建造 —— 未捕获 launch 异常一律留痕（AppLogger error + 站点名）不再杀死进程（SupervisorJob 只隔离子协程取消传播，不吞未捕获异常；此前仅 3 处手工补过 handler，其余零防护）（#834 第一波：app 模块全量）
