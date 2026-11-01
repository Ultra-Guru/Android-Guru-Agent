<!-- pr: batch8-redactor-traceability -->

- 敏感参数脱敏器失败路径补留痕：redact 异常折叠为原样返回的防御契约不变，但 AppLogger.warn 留痕（runCatching 包裹）——脱敏静默失效不再无迹可循
