<!-- pr: task-playbooks-ui -->

- 任务剧本 app 接线：发送前识别（建议条「检测到 X 任务 → 挂载剧本？」，SUGGEST 默认档，可切 auto 静默/off 关闭）——`AgentSettings.playbookAutoMount` 持久化
- 挂载态 chip「剧本《X》·幕k/n」+ 幕进度弹层（各幕标题/状态、点击跳幕、卸载）；幕推进由回包章节头判定（诚实门：空洞产出不静默放行），推进即系统消息 + extras 热重织
- 剧本指令并入 sessionExtras 管道（零引擎接口变更，四条引擎线统一生效）；幕侦察附录随幕激活注入
- 模板工坊「发送到 Coding 屏」：生成结果手递手为 Coding 屏待发送条（[发送]/[丢弃]），补齐「使用模板」只有剪贴板的断环
- 新增组件：CodePlaybookController（识别/挂载/幕推进单点）+ PlaybookModule（playbooks.json 单例播种）+ UI 四件套；strings 双语镜像
