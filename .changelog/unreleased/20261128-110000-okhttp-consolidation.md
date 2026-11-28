<!-- pr: okhttp-consolidation -->

- OkHttp 连接池收敛为两级单源（AppHttpPool / ToolsHttpPool / LlmClientFactory base）：八处独立建造改 newBuilder 派生共享池 —— 同域请求连接复用、空闲线程/连接不再随站点数线性放大，各站点超时与重试语义原值不变（#835）
