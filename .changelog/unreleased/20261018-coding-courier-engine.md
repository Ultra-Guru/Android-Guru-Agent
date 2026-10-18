# Coding 第四思考逻辑线：信使（Courier / 驿传调度范式）

- Coding 模式新增第四思考逻辑线「信使」：**收发匣投递**（steer 插队 /
  queue 排队 / 控制件自成边界，投递幂等）+ **趟次步进机**（一趟 = 一条
  助手消息，逻辑趟 vs 物理尝试，无本地调用即收束；同趟多工具并行纤程；
  终趟关调用留定义）+ **勘合门**（`*`/`?` 通配则例三态法则 + 后例覆盖 +
  always 记忆自动复评 + 复合命令逐段核验 + 拒付缺陷隧道）+ **档房卷宗
  压缩**（近期逐字保留 + 七标题模板 + 70/50/35 收缩阶梯）+ **示廊公告**
  （常设指令价值增量：epoch 基线 + 冻结更正行）+ **下站子驿委派**
  （courier_task 前台信封 / 后台完成合成输入唤醒）。
- 循环内核拆库为独立模块 `com.apex.courierkit:courier-dispatch`
  （仓库 Ultra-Guru/apex-courier-kit，纯 Kotlin JVM 零 Android 依赖，
  composite build + `gradle/courier-kit.lock` 钉版 + sync 工作流只跟绿
  提交）；宿主仅保留薄适配层 `CourierKitAdapter`（端口适配 + 21 型事件
  1:1 映射），与 standard-kit / loom-kit 同构。
- 四线任意切换：右上角档位选择器与 `/logic:courier`（别名 express）；
  独立会话记忆通道 `code_memory_courier`（切回现场不丢）。
- 权限执行器纪律（#230 同款）：信使线库内勘合门独占权限确认，宿主
  `@Named("courierEngineTools")` 仅环境门——不双弹窗。
