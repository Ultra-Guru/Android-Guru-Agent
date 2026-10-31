<!-- pr: update-check-mirrors -->

- 检查更新不再需要梯子：version.json / patches.json / changelog.json 三份清单改走国内镜像回退链（ghfast.top / ghproxy.net / gh-proxy.com 依次尝试，全败自动回退直连，海外与代理环境行为不变）
- 坏镜像防御：镜像返回 HTML 挑战页（伪 200）时自动降级下一候选，镜像档 4 秒短超时快速降级，不让死节点拖慢检查
