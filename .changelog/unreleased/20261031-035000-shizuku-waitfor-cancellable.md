<!-- pr: shizuku-waitfor-cancellable -->

- Shizuku 命令等待支持协程取消：waitFor 包裹 runInterruptible，工具策略超时/用户中断不再钉死 IO 线程到全程超时，取消异常按结构化并发纪律向上传播
