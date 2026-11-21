<!-- pr: update-downloader-scheme-check -->

- 更新下载入口新增 scheme 校验：仅接受 http/https 下载源，被篡改的更新清单注入本地路径或非网络 URI 时拒绝入队并留痕日志
