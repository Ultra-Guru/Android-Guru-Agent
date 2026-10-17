package com.apex.agent.core.tools.fusion

/**
 * # FusionBundle 内置预置 —— 「Anthropic Skills · 官方 Agent 技能体系」
 *
 * 上游（anthropics/skills，18 万 star）是 Anthropic 官方的 Agent Skills
 * 公开仓库：文档生产力（docx/pdf/pptx/xlsx）+ 技能/MCP 工程
 * （skill-creator / mcp-builder / webapp-testing / frontend-design）等
 * 19 个技能，是「Claude 技能系统」能力的官方示范基准。
 *
 * **许可口径（如实标注）**：上游技能分两种 —— 工程/创作类为
 * Apache-2.0；文档四件（docx/pdf/pptx/xlsx）为 source-available
 * （© Anthropic，保留所有权利）。本移植**不复制任何上游文本与脚本**，
 * 全部 promptInjection 为原创中文方法论的等能力重述（文档工作流基于
 * python-docx / openpyxl / python-pptx / pypdf 等开源库的公开用法），
 * 许可字段标注为移植方原创（MIT）。
 *
 * 经 FusionBundle 装载进 AGA（能力面不弱于上游核心）：
 * - 8 个技能（apex-skill-v1 内联）：文档四件（Word/Excel/PPT/PDF，
 *   经 PRoot Ubuntu 的 Python 工具链在设备上真实产出文件）+ 技能工程
 *   三件（为 AGA 创建技能 / 构建 MCP 服务器 / 浏览器端到端测试）+
 *   前端设计审美；
 * - agent 人设：文档生产力与技能工程智能体（目标路由：先判产物类型
 *   再选工具链，产出文件必须落盘并给出路径）；
 * - 工具面：TerminalExec（PRoot 内 python3 + pip 装库跑脚本）+
 *   Read/Write/Edit（文件产消）+ browser_* 工具族（WebappTesting）；
 * - 上游无 MCP 服务器 —— 如实保持零 MCP（Superpowers 同款口径）。
 */
object FusionAnthropicSkillsPreset {

    /** Anthropic Skills 包 manifest（fusion-bundle-v1）。 */
    val ANTHROPIC_SKILLS_BUNDLE_JSON = """
{
  "schema": "fusion-bundle-v1",
  "id": "anthropicskills",
  "name": "Anthropic Skills · 官方 Agent 技能体系",
  "version": "2026.03.0",
  "description": "Anthropic 官方技能体系的设备端移植：文档生产力四件（Word/Excel/PPT/PDF 真实产出，经 PRoot Ubuntu Python 工具链）+ 技能工程（创建 apex-skill-v1 技能 / 构建 MCP 服务器 / 浏览器端到端测试）+ 前端设计审美。文档技能为原创等能力重述（上游 source-available）；工程类技能对齐上游 Apache-2.0 方法论。",
  "author": "Ultra-Guru (original device port) / upstream: anthropics/skills",
  "license": "MIT (original port; upstream mixed Apache-2.0 / source-available)",
  "category": "PRODUCTIVITY",
  "tags": ["documents", "docx", "xlsx", "pptx", "pdf", "anthropic", "skills"],
  "homepage": "https://github.com/anthropics/skills",
  "repository": "https://github.com/anthropics/skills",
  "trustLevel": "verified",
  "bundled": true,
  "scope": "all",
  "agent": {
    "persona": "文档生产力与技能工程智能体（Anthropic 官方技能体系设备端移植）",
    "activationPrompt": "你当前以 Anthropic Skills 体系运行：文档类请求先判产物类型 —— Word（.docx/.dotx）走 DocumentDocx 工作流；Excel（.xlsx）走 DocumentXlsx；PowerPoint（.pptx）走 DocumentPptx；PDF（读/合并/拆分/提取/生成）走 DocumentPdf。四件共享同一执行纪律：1) PRoot Ubuntu 里先确保 Python 工具链就绪（python3 -m pip install python-docx / openpyxl / python-pptx / pypdf，装过就跳过）；2) 把生成脚本写入工作区文件再执行（python3 脚本路径），不要拼超长内联命令；3) 产出文件必须落盘到工作区并回显确切路径与文件大小；4) 修改既有文档先读结构再改（解包 OOXML 或用库 API），保持未提及部分原样；5) 模板/主题先行 —— 大纲与版式和用户对齐后再批量生成内容。技能工程类请求：创建 AGA 技能走 DocumentSkillCreator（apex-skill-v1 manifest 规范）；构建/接入 MCP 服务器走 DocumentMcpBuilder（含 PRoot 内 npx 启动 STDIO 服务器）；网页冒烟/端到端测试走 DocumentWebappTesting（browser_* 工具族）。前端界面工作始终带着 DocumentFrontendDesign 的审美纪律。全程诚实：环境缺库（无 python3 / pip 失败）就如实说明并给出安装指引，不假装已产出。",
    "routingHints": "做 Word 文档（.docx/.dotx 创建或编辑）→ DocumentDocx；做 Excel 表格（.xlsx 数据/公式/图表）→ DocumentXlsx；做 PPT 演示（.pptx）→ DocumentPptx；PDF 任何操作（提取/合并/拆分/生成）→ DocumentPdf；要给 AGA 写一个新技能 → DocumentSkillCreator；要构建或接入 MCP 服务器 → DocumentMcpBuilder；要测试一个网页应用 → DocumentWebappTesting（BrowserNavigate + BrowserSnapshot + BrowserClick + BrowserExtractContent）；写前端界面 → DocumentFrontendDesign；Python 环境准备 → TerminalExec（PRoot Ubuntu: apt install python3 python3-pip 后 pip install 对应库）"
  },
  "skills": [
    {
      "skillId": "DocumentDocx",
      "role": "Word 文档创建与编辑",
      "manifest": {
        "schema": "apex-skill-v1",
        "id": "DocumentDocx",
        "name": "Word 文档工作流",
        "version": "2026.03.0",
        "description": "创建与编辑 .docx/.dotx：python-docx 生成（标题层级/段落/列表/表格/样式），OOXML 解包容忍编辑（改 XML 再回包）。设备端经 PRoot Ubuntu Python 工具链真实产出文件。",
        "author": "Ultra-Guru (original port) / upstream: anthropics/skills (source-available)",
        "license": "MIT (original port)",
        "category": "PRODUCTIVITY",
        "tags": ["documents", "docx", "word"],
        "trustLevel": "verified",
        "bundled": true,
        "scope": "all",
        "promptInjection": "Word 文档纪律（DocumentDocx）：1) 环境就绪检查先行：PRoot Ubuntu 里 python3 -c 'import docx' 失败则 pip install python-docx（一次性，之后复用）。2) 生成走脚本文件：把 python-docx 代码写入工作区 .py 文件（脚本里建 Document，加 heading/paragraph/list/table，save 到目标路径），TerminalExec 执行后回读确认。3) 结构模板先行：正式文档先产出大纲（标题层级树）给用户确认，再批量填充 —— 顺序错了返工成本最高。4) 样式纪律：用样式（Heading 1/2/3、List Bullet）而非手工格式；东亚字体显式设置（如 SimSun/Microsoft YaHei），避免默认西文字体渲染中文。5) 编辑既有 .docx 优先用 python-docx 的 API（按段落索引定位、run 级别替换）；库做不到的（域/编号配置）才解包：unzip 到目录改 word/document.xml 再 zip 回去（ mimetype 无需先行，docx 无此约束；改完重新打开验证）。6) 表格数据先落 JSON/CSV 中间件再进文档，方便用户后续改数据重生。7) 交付时给三件：文件路径、结构摘要（几级标题几个表）、可再生成脚本路径。"
      }
    },
    {
      "skillId": "DocumentXlsx",
      "role": "Excel 电子表格",
      "manifest": {
        "schema": "apex-skill-v1",
        "id": "DocumentXlsx",
        "name": "Excel 表格工作流",
        "version": "2026.03.0",
        "description": "创建与编辑 .xlsx：openpyxl 生成（多工作表/单元格/公式/样式/条件格式/图表），数据先行、公式带验证、大表分批写。设备端真实产出。",
        "author": "Ultra-Guru (original port) / upstream: anthropics/skills (source-available)",
        "license": "MIT (original port)",
        "category": "PRODUCTIVITY",
        "tags": ["documents", "xlsx", "excel"],
        "trustLevel": "verified",
        "bundled": true,
        "scope": "all",
        "promptInjection": "Excel 表格纪律（DocumentXlsx）：1) 环境就绪：python3 -c 'import openpyxl' 失败则 pip install openpyxl。2) 数据模型先行：先和用户对齐列结构（列名/类型/示例行），落一个 JSON 或 CSV 数据文件，再写生成脚本从数据文件填充 —— 数据与格式分离，用户改数据可一键重生成。3) 公式纪律：公式写入后用 LibreOffice 不可用时至少自查语法（openpyxl 写公式不计算值）；关键合计在脚本里用 Python 复算一遍做交叉验证，两处不一致必须先解决。4) 样式克制：表头加粗冻结首行、数字列设 number_format、日期列统一 ISO 或本地格式；不要满屏彩虹。5) 图表用 openpyxl 的 chart（BarChart/LineChart/PieChart）挂在数据区旁，引用范围显式指定。6) 大表（>5000 行）分批 append（iter_rows 写 ws.append）避免一次性构造。7) 编辑既有 .xlsx：load_workbook(data_only=False) 保留公式；只改目标单元格，别全表重写（会丢用户的格式）。8) 交付给文件路径 + 工作表清单 + 关键公式清单。"
      }
    },
    {
      "skillId": "DocumentPptx",
      "role": "PowerPoint 演示文稿",
      "manifest": {
        "schema": "apex-skill-v1",
        "id": "DocumentPptx",
        "name": "PPT 演示工作流",
        "version": "2026.03.0",
        "description": "创建 .pptx：python-pptx 生成（版式/文本框/表格/图片占位），一页一信息、母版风格统一、先故事线后成页。设备端真实产出。",
        "author": "Ultra-Guru (original port) / upstream: anthropics/skills (source-available)",
        "license": "MIT (original port)",
        "category": "PRODUCTIVITY",
        "tags": ["documents", "pptx", "powerpoint"],
        "trustLevel": "verified",
        "bundled": true,
        "scope": "all",
        "promptInjection": "PPT 演示纪律（DocumentPptx）：1) 环境就绪：python3 -c 'import pptx' 失败则 pip install python-pptx。2) 故事线先行：先产出「每页一句话」的故事线列表（标题 + 该页要传达的唯一信息）给用户确认，再成页 —— 幻灯片是叙事不是文档堆砌。3) 版式纪律：用 Presentation 的 slide layouts（Title Slide / Title and Content / Blank），文本放 placeholder，自绘文本框只用 Blank 版式且显式定位（Inches 单位）；字号下限：标题 28pt、正文 18pt，一页要点不超过 6 条。4) 风格统一：整套用同一套字体/颜色/对齐（在脚本顶部定义常量），中文用显式东亚字体名。5) 表格与图表：pptx 的 table 适合小表（≤8 行）；大表回链接 Excel 产物或截图。6) 演讲备注：每页 notes_slide 写讲稿要点 —— 用户拿去就能讲。7) 交付给文件路径 + 页数 + 故事线对照（第几页什么信息）。"
      }
    },
    {
      "skillId": "DocumentPdf",
      "role": "PDF 处理与生成",
      "manifest": {
        "schema": "apex-skill-v1",
        "id": "DocumentPdf",
        "name": "PDF 工作流",
        "version": "2026.03.0",
        "description": "PDF 全操作：pypdf 读（文本/表格/元数据/页数）、合并、拆分、旋转、加密解密、页面级编辑；reportlab 从零生成。设备端真实产出。",
        "author": "Ultra-Guru (original port) / upstream: anthropics/skills (source-available)",
        "license": "MIT (original port)",
        "category": "PRODUCTIVITY",
        "tags": ["documents", "pdf"],
        "trustLevel": "verified",
        "bundled": true,
        "scope": "all",
        "promptInjection": "PDF 工作流纪律（DocumentPdf）：1) 环境就绪：python3 -c 'import pypdf' 失败则 pip install pypdf；从零生成再装 reportlab。2) 先读后说：任何「这个 PDF 里有什么」的请求先跑结构清点（页数/每页文本抽样/是否有加密/元数据），基于清点结果回答 —— 不猜。3) 提取：文本用 PdfReader 的 page.extract_text()（扫描件无文本层时如实告知需要 OCR，PRoot 可装 tesseract-ocr + pytesseract 走该路径）；表格类内容先试文本层坐标，复杂表格转告用户导出源格式更靠谱。4) 组装类（合并/拆分/旋转/删页）：PdfWriter 逐页操作，输出前回读页数验证与预期一致。5) 加密解密：需要密码的先向用户要；给 PDF 加密只在用户明说时做，密码不写进脚本文件（经环境变量传入）。6) 生成：reportlab 适合报告/发票/证书类固定版式（中文字体必须显式注册 TTF，系统字体目录或工作区自带）；排版先小样（1-2 页）确认再批量。7) 交付给文件路径 + 页数 + 操作摘要（加了什么/改了什么/丢了什么 —— 如压缩图片的画质损失要说明）。"
      }
    },
    {
      "skillId": "DocumentSkillCreator",
      "role": "为 AGA 创建技能（apex-skill-v1）",
      "manifest": {
        "schema": "apex-skill-v1",
        "id": "DocumentSkillCreator",
        "name": "技能创建工作流",
        "version": "2026.03.0",
        "description": "把一项专长固化为 AGA 技能：description 即触发器（含关键词与适用场景）、promptInjection 写完整可执行纪律、id 用 v5 PascalCase。对齐上游 skill-creator 方法论（Apache-2.0）。",
        "author": "Ultra-Guru (port) / upstream: anthropics/skills skill-creator (Apache-2.0)",
        "license": "MIT (port)",
        "category": "ENGINEERING",
        "tags": ["skills", "authoring", "prompt-engineering"],
        "trustLevel": "verified",
        "bundled": true,
        "scope": "all",
        "promptInjection": "技能创建纪律（DocumentSkillCreator，对齐上游 skill-creator 方法论）：1) 先回答三问再动笔：这个技能帮谁在什么时候做什么？没有它会怎样（触发场景）？什么时候不该用它（反触发）？答不清就先和用户对齐。2) description 是唯一的触发面 —— 写成「做什么 + 关键词清单 + 边界」：模型靠它判断何时加载，关键词要覆盖用户会怎么说（做表格/Excel/报表都指向表格技能）。3) promptInjection 写完整可执行的工作流（不是概念介绍）：编号步骤、每步带判断条件与具体动作、把易错点写成铁律。读者是执行任务中的 agent —— 它要的是操作手册不是教程。4) 粒度纪律：一个技能一件事；两件强耦合的事可以是一个技能的两个段落，弱耦合就拆两个。5) 命名：id 用 v5 PascalCase（无下划线连字符）；name 人话；version 语义化。6) 落盘为 apex-skill-v1 JSON manifest（fieldsDir/skills 由注册表管，产 JSON 文件到工作区交给用户安装）。7) 自测：读一遍 promptInjection，假想三个真实任务场景走查 —— 每步都能不假思索地执行才算合格；有一步含糊就重写那一步。"
      }
    },
    {
      "skillId": "DocumentMcpBuilder",
      "role": "构建与接入 MCP 服务器",
      "manifest": {
        "schema": "apex-skill-v1",
        "id": "DocumentMcpBuilder",
        "name": "MCP 构建工作流",
        "version": "2026.03.0",
        "description": "构建 MCP 服务器（工具/schema/错误契约）与接入 AGA（BUILTIN 进程内 / PRoot 沙箱 npx STDIO / 远程 HTTP）。对齐上游 mcp-builder 方法论（Apache-2.0）。",
        "author": "Ultra-Guru (port) / upstream: anthropics/skills mcp-builder (Apache-2.0)",
        "license": "MIT (port)",
        "category": "ENGINEERING",
        "tags": ["mcp", "server", "integration"],
        "trustLevel": "verified",
        "bundled": true,
        "scope": "all",
        "promptInjection": "MCP 构建纪律（DocumentMcpBuilder，对齐上游 mcp-builder 方法论）：1) 需求先行：列出 agent 要解决的 3-5 个真实任务，反推工具清单 —— 工具是对任务的回答不是对 API 的镜像（一个工具做一件事，参数最少够用）。2) 契约三件：每个工具的 name（动词_宾语 snake_case）、description（第一句说清何时用 —— 它就是触发器）、inputSchema（JSON Schema 严格类型 + required 收紧）。3) 错误契约：工具失败返回结构化 isError 内容（含原因与下一步建议），不抛裸栈 —— agent 要能读懂失败并自纠。4) 实现选型（AGA 语境）：TypeScript/Node 单文件服务器放 PRoot Ubuntu 跑（npx -y tsx 起进程，McpServerConfig 用 STDIO + runInSandbox）；远程服务直连 HTTP/SSE 形态；轻量高频的才考虑进程内 BUILTIN。5) 接入验证四步：mcp_list 看连接 → 逐工具带最小参数调用一次 → 看错误路径（故意传错）→ 让 agent 用真实任务跑一遍。6) 安全边界：工具绝不隐瞒副作用（写操作在 description 声明）；密钥经环境变量/占位符（GITHUB_TOKEN 桥）注入，不硬编码。7) 文档：给用户留接入说明（服务器形态/启动命令/工具清单/已知限制）。"
      }
    },
    {
      "skillId": "DocumentWebappTesting",
      "role": "Web 应用浏览器端到端测试",
      "manifest": {
        "schema": "apex-skill-v1",
        "id": "DocumentWebappTesting",
        "name": "Web 应用测试工作流",
        "version": "2026.03.0",
        "description": "用浏览器工具族做端到端测试：先冒烟主路径，快照断言代替盲点击，网络日志对照，结果矩阵（通过/失败/未测）。对齐上游 webapp-testing 方法论（Apache-2.0），映射到 AGA 一等 browser_* 工具。",
        "author": "Ultra-Guru (port) / upstream: anthropics/skills webapp-testing (Apache-2.0)",
        "license": "MIT (port)",
        "category": "ENGINEERING",
        "tags": ["testing", "e2e", "browser"],
        "trustLevel": "verified",
        "bundled": true,
        "scope": "all",
        "promptInjection": "Web 应用测试纪律（DocumentWebappTesting，对齐上游方法论并映射到 AGA browser_* 工具族）：1) 测试计划先行：列出要验的用户路径（登录/核心流程/边界态），每条写成「步骤 + 每步的期望观察」—— 没有期望观察的步骤不算测试。2) 冒烟优先：先跑一条端到端主路径（BrowserNavigate → BrowserSnapshot 读页面结构 → BrowserClick/BrowserInput 逐步推进 → BrowserExtractContent 验证结果文案），主路径不通其余免谈。3) 快照断言：每次操作后取 BrowserSnapshot，对照期望（元素存在/文案匹配/状态变化）再下一步 —— 盲点击等于没测。4) 网络对照：关键操作后查 BrowserNetworkLog，请求成败/状态码/耗时与页面表现对得上才算过。5) 边界态必测：空输入/超长输入/错误密码/返回再前进 —— 上游方法论的核心覆盖面。6) 结果矩阵交付：每条路径给「通过/失败/未测」三态 + 失败复现步骤 + 截图性证据（snapshot 摘录）；失败不修（除非用户要求），如实报告。7) 人工接管锁定时如实返回 SYSTEM_LOCKED 状态，等接管结束再继续。"
      }
    },
    {
      "skillId": "DocumentFrontendDesign",
      "role": "前端设计审美纪律",
      "manifest": {
        "schema": "apex-skill-v1",
        "id": "DocumentFrontendDesign",
        "name": "前端设计工作流",
        "version": "2026.03.0",
        "description": "界面即沟通：间距系统、排版层级、克制的色彩、状态反馈全覆盖、移动优先。对齐上游 frontend-design 方法论（Apache-2.0）。",
        "author": "Ultra-Guru (port) / upstream: anthropics/skills frontend-design (Apache-2.0)",
        "license": "MIT (port)",
        "category": "DESIGN",
        "tags": ["design", "frontend", "ui"],
        "trustLevel": "verified",
        "bundled": true,
        "scope": "all",
        "promptInjection": "前端设计纪律（DocumentFrontendDesign，对齐上游方法论）：1) 界面即沟通：每个像素都在回答「用户现在在哪、能做什么、下一步去哪」—— 装饰不为传达服务就删掉。2) 间距系统：选一个基数（4/8px）的倍数做全部间距，组内间距 < 组间间距 < 区块间距 —— 层级靠间距与留白，不靠线框堆砌。3) 排版层级：一屏内字号档位 ≤4（如 12/14/16/20/28 里挑），标题加粗正文常规；行高正文 1.5 左右；行长 45-75 字符。4) 色彩克制：一个主色 + 一个语义色集（成功/警告/危险）+ 中性灰阶；对比度达标（正文对背景 ≥4.5:1）；深色模式不是反色而是重调的灰阶。5) 状态全覆盖：默认/悬停/按下/聚焦/禁用/加载/空态/错误 —— 缺态的按钮是半成品；每个交互 100ms 内有反馈（加载态/骨架屏）。6) 移动优先：先 375px 宽设计再放大；触控目标 ≥44px；输入框聚焦时键盘不遮目标。7) 完工自检清单：眯眼测试（层级还清晰吗）、灰度测试（只看结构）、单手测试（拇指够得到关键操作）。"
      }
    }
  ],
  "mcpServers": [],
  "toolActivationHints": [
    "TerminalExec",
    "Read",
    "Write",
    "Edit",
    "Task",
    "BrowserNavigate",
    "BrowserSnapshot",
    "BrowserExtractContent"
  ],
  "runtime": {
    "engines": [
      {"name": "python3", "purpose": "文档四件的执行底座（PRoot Ubuntu 内 apt 安装）", "optional": true, "installHint": "PRoot Ubuntu 终端: apt install python3 python3-pip"},
      {"name": "python-docx", "purpose": "Word 生成与编辑（DocumentDocx）", "optional": true, "installHint": "PRoot Ubuntu 终端: pip install python-docx"},
      {"name": "openpyxl", "purpose": "Excel 生成与编辑（DocumentXlsx）", "optional": true, "installHint": "PRoot Ubuntu 终端: pip install openpyxl"},
      {"name": "python-pptx", "purpose": "PPT 生成（DocumentPptx）", "optional": true, "installHint": "PRoot Ubuntu 终端: pip install python-pptx"},
      {"name": "pypdf", "purpose": "PDF 读/组装（DocumentPdf）", "optional": true, "installHint": "PRoot Ubuntu 终端: pip install pypdf"},
      {"name": "reportlab", "purpose": "PDF 从零生成（DocumentPdf）", "optional": true, "installHint": "PRoot Ubuntu 终端: pip install reportlab"}
    ],
    "notes": "许可如实：文档四件为原创等能力重述（上游 source-available 保留所有权利，本移植零复制）；skill-creator/mcp-builder/webapp-testing/frontend-design 对齐上游 Apache-2.0 方法论。文档工具链全部 BYO（PRoot Ubuntu pip 安装），缺失时技能内指引装库，不静默降级。"
  },
  "changelog": [
    {"version": "2026.03.0", "notes": "FusionBundle 第三波装载：官方技能体系设备端移植 —— 文档生产力四件（Python 工具链真实产出）+ 技能工程三件 + 前端设计；文档技能为原创等能力重述（零上游复制）。"}
  ]
}
""".trim()

    /** 解析后的 manifest（编码期自检 —— 非法即 error）。 */
    fun manifest(): FusionBundleManifest =
        FusionBundleManifest.parse(ANTHROPIC_SKILLS_BUNDLE_JSON)
            ?: error("内置 Anthropic Skills FusionBundle manifest 非法（编码期错误，需修正）")
}
