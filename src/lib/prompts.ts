// 提示词构建 —— 规则分级对标 RulesProvider（Global Rules 软件级 / Project Rules 项目级）
// 用户画像与记忆注入对标 cs-mem v2；游乐场规范吸取 pretext 时代的教训后完全重写。

import type { Workspace } from '@prisma/client'

// ═══════════════ 身份 ═══════════════

const IDENTITY_AGENT = `你是「Guru」——一个务实、可靠的通用智能助手。
- 回答直接、结构清晰；不确定就说不确定，不编造。
- 用户要深入时给深入，要简短时给简短。
- 使用简体中文。`

const IDENTITY_CODING = `你是「Guru · 编码工位」——面向工程任务的编码智能体。
- 默认给出可执行的技术方案与代码；先讲结论，再讲依据。
- 代码给出关键路径与边界处理，不啰嗦注释。
- 涉及取舍时（库选择/架构/性能），列出选项与代价，给出你的推荐及理由。
- 使用简体中文。`

// ═══════════════ 规则段落 ═══════════════

/** 软件级规则 —— 全范围生效（所有工作区、所有模式） */
export function formatGlobalRules(rules: string): string | null {
  const text = (rules || '').trim()
  if (!text) return null
  return [
    '## Global Rules（软件级规则）',
    '以下规则由用户在设置中配置，对所有工作区、所有模式全局生效：',
    text.length > 32000 ? text.slice(0, 32000) + '\n（软件级规则超过 32000 字符，已截断）' : text,
  ].join('\n')
}

/** 项目级规则 —— 只在当前工作区生效，优先级高于软件级规则 */
export function formatProjectRules(workspace: Pick<Workspace, 'name' | 'projectRules'>): string | null {
  const text = (workspace.projectRules || '').trim()
  if (!text) return null
  return [
    '## Project Rules（项目级规则 · 当前工作区）',
    `以下规则属于工作区「${workspace.name}」，仅在本工作区内生效。`,
    '它们优先于 Global Rules 与一般偏好，但永远低于安全约束。',
    text.length > 32000 ? text.slice(0, 32000) + '\n（项目级规则超过 32000 字符，已截断）' : text,
  ].join('\n')
}

// ═══════════════ 用户画像（Coding 模式注入） ═══════════════

export const PROFILE_ASPECTS: Record<string, { label: string; hint: string }> = {
  'tech-stack': { label: '技术栈', hint: '常用语言、框架、工具链' },
  'coding-style': { label: '编码风格', hint: '命名/注释/结构偏好' },
  preferences: { label: '偏好', hint: '交互与输出偏好' },
  domains: { label: '关注领域', hint: '正在做的业务与技术领域' },
  habits: { label: '工作习惯', hint: '工作节奏、提交习惯、作息' },
  goals: { label: '目标', hint: '正在推进的短期/长期目标' },
}

export function formatUserProfile(
  items: Array<{ aspect: string; content: string; confidence: number }>
): string | null {
  if (!items.length) return null
  const byAspect = new Map<string, string[]>()
  for (const it of items) {
    const list = byAspect.get(it.aspect) || []
    list.push(`- ${it.content}（置信度 ${(it.confidence * 100) | 0}%）`)
    byAspect.set(it.aspect, list)
  }
  const blocks: string[] = []
  for (const [aspect, list] of byAspect) {
    const label = PROFILE_ASPECTS[aspect]?.label || aspect
    blocks.push(`### ${label}\n${list.join('\n')}`)
  }
  return [
    '## User Profile（用户画像）',
    '以下是对该用户的结构化画像（按置信度排序的顶部条目）。',
    '用它来个性化你的回答：默认技术栈、示例语言、讲解深度都应向画像靠拢；',
    '与画像冲突时以对话上下文为准。',
    blocks.join('\n\n'),
  ].join('\n')
}

// ═══════════════ 对话记忆注入 ═══════════════

export function formatMemories(
  items: Array<{ category: string; content: string }>
): string | null {
  if (!items.length) return null
  const CAT: Record<string, string> = {
    fact: '稳定事实',
    state: '近况',
    milestone: '里程碑',
    preference: '偏好',
  }
  const lines = items.map((m) => `- [${CAT[m.category] || m.category}] ${m.content}`)
  return ['## Relevant Memories（相关记忆）', '以下是按综合评分召回的记忆，供参考：', lines.join('\n')].join('\n')
}

// ═══════════════ 系统提示词组装 ═══════════════

export interface SystemPromptInput {
  mode: 'agent' | 'coding'
  workspace: Pick<Workspace, 'id' | 'name' | 'description' | 'projectRules'>
  globalRules: string
  profileItems: Array<{ aspect: string; content: string; confidence: number }>
  memoryItems: Array<{ category: string; content: string }>
}

export function buildSystemPrompt(input: SystemPromptInput): string {
  const parts: string[] = []
  parts.push(input.mode === 'coding' ? IDENTITY_CODING : IDENTITY_AGENT)

  const wsDesc = input.workspace.description?.trim()
  if (wsDesc) {
    parts.push(`## 工作区\n当前工作区：「${input.workspace.name}」——${wsDesc}`)
  }

  const global = formatGlobalRules(input.globalRules)
  if (global) parts.push(global)

  const project = formatProjectRules(input.workspace)
  if (project) parts.push(project)

  if (input.mode === 'coding') {
    const profile = formatUserProfile(input.profileItems)
    if (profile) parts.push(profile)
  }

  const memories = formatMemories(input.memoryItems)
  if (memories) parts.push(memories)

  parts.push(
    input.mode === 'coding'
      ? '规则优先级：安全约束 > 项目级规则 > 软件级规则 > 画像与记忆 > 一般偏好。'
      : '规则优先级：安全约束 > 项目级规则 > 软件级规则 > 记忆 > 一般偏好。'
  )
  return parts.join('\n\n')
}

// ═══════════════ 消息后提炼（批量蒸馏 + 写入门禁） ═══════════════

export const DIGEST_SYSTEM = `你是记忆与画像的提炼引擎。你只输出 JSON，不输出任何其他文字。

# 任务
从最近对话中提取【用户特异且可复用的稳定信息】，维护两本账：
1. memories —— 对话记忆（fact 稳定事实 / state 近况 / milestone 里程碑 / preference 偏好）
2. profile —— 用户画像（仅编码相关的维度：tech-stack 技术栈 / coding-style 编码风格 / preferences 偏好 / domains 关注领域 / habits 工作习惯 / goals 目标）

# 写入门禁（负面清单，违反即 skip）
- 不记：常识或公开定义、一次性任务细节、未来计划/TODO、助手自己的发言
- 只记用户特异且可复用的稳定信息；无信号输出 {"skip":true}
- 更新优先：已有条目是参照；同主题有演进必须 update（old 逐字复制原文），而不是 new
- new 限额：每次最多 3 条 memory + 3 条 profile；每条 ≤ 40 字；第三人称陈述
- state 类近况最多保留 1 条，新的替换旧的

# 输出格式（严格 JSON）
{"skip":true}
或
{"memories":[{"op":"new","category":"fact","content":"…","importance":0.7},{"op":"update","old":"逐字复制原文","content":"…"}],
 "profile":[{"op":"new","aspect":"tech-stack","content":"…","confidence":0.7,"evidence":"来源摘要≤20字"},{"op":"update","old":"逐字复制原文","content":"…"}]}
字段可省略（如只提画像就只给 profile）。importance/confidence 取 0.3~1.0。`

export function buildDigestUserMessage(input: {
  mode: 'agent' | 'coding'
  recentTurns: Array<{ role: string; content: string }>
  existingMemories: Array<{ category: string; content: string }>
  existingProfile: Array<{ aspect: string; content: string }>
}): string {
  const turnLines = input.recentTurns
    .map((t) => `${t.role === 'user' ? '用户' : '助手'}：${t.content.slice(0, 600)}`)
    .join('\n')
  const memLines = input.existingMemories.length
    ? input.existingMemories.map((m) => `- [${m.category}] ${m.content}`).join('\n')
    : '（暂无）'
  const profLines = input.existingProfile.length
    ? input.existingProfile.map((p) => `- [${p.aspect}] ${p.content}`).join('\n')
    : '（暂无）'
  return [
    `# 对话模式\n${input.mode === 'coding' ? '编码模式' : '智能体模式'}`,
    '# 最近对话（截断展示）',
    turnLines || '（无内容）',
    '# 现有记忆账本（参照，用于 update 判断）',
    memLines,
    '# 现有画像账本（参照，仅编码相关维度）',
    profLines,
    '# 要求',
    '按系统指令输出 JSON。',
  ].join('\n')
}

// ═══════════════ 用户画像 · 批量提炼（手动触发） ═══════════════

export const PROFILE_DISTILL_SYSTEM = `你是用户画像提炼引擎。你只输出 JSON，不输出任何其他文字。

# 任务
扫描编码模式对话，提炼【用户特异且长期稳定】的画像条目，维度：
tech-stack 技术栈 / coding-style 编码风格 / preferences 偏好 / domains 关注领域 / habits 工作习惯 / goals 目标

# 写入门禁
- 不记：常识、一次性任务细节、未来计划、助手发言
- 更新优先：同主题演进必须 update（old 逐字复制原文）；全新概念才 new
- 每条 ≤ 40 字，第三人称；evidence 是来源对话摘要（≤ 20 字）
- confidence 0.3~1.0；最多产出 8 个动作；无信号 {"skip":true}

# 输出格式（严格 JSON）
{"skip":true}
或
{"actions":[{"op":"new","aspect":"tech-stack","content":"…","confidence":0.8,"evidence":"…"},{"op":"update","old":"逐字复制原文","content":"…"}]}`

export function buildProfileDistillUserMessage(input: {
  transcript: string
  existingProfile: Array<{ aspect: string; content: string }>
}): string {
  const profLines = input.existingProfile.length
    ? input.existingProfile.map((p) => `- [${p.aspect}] ${p.content}`).join('\n')
    : '（暂无）'
  return [
    '# 编码模式对话汇总（多轮，截断展示）',
    input.transcript.slice(0, 12000) || '（无内容）',
    '# 现有画像（参照）',
    profLines,
    '# 要求',
    '按系统指令输出 JSON。',
  ].join('\n')
}

// ═══════════════ 游乐场 —— 自包含 HTML 规范（完全重写，弃用 pretext） ═══════════════

export const PLAYGROUND_STYLE_THEMES: Record<string, string> = {
  neon: '终端暗色霓虹（默认）：深底 #0a0f0c、等宽字体、绿/琥珀主色、克制的辉光',
  paper: '纸上极简：暖白底、衬线标题、墨色文字、一两种强调色、大量留白',
  candy: '软糖活力：亮底、圆角大卡片、高饱和渐变点缀、活泼微动效',
  brutal: '粗野主义：纯黑白、粗边框、硬阴影、无圆角、强烈的版式对比',
}

export function buildPlaygroundPrompt(input: {
  prompt: string
  category: string
  theme: string
  id: string
}): string {
  const themeDesc = PLAYGROUND_STYLE_THEMES[input.theme] || PLAYGROUND_STYLE_THEMES.neon
  const categoryLabel =
    { game: '小游戏（有计分、难度曲线、结算）', tool: '实用工具（有真实输入输出与状态）', viz: '数据可视化（有真实数据与交互）', animation: '生成艺术/动画（有参数可调）' }[input.category] || '交互作品'

  return `# 任务
为「Guru Web 游乐场」生成一个作品：**自包含单文件 HTML 应用**，直接输出完整 HTML 代码。
用户需求：${input.prompt}
作品类型：${categoryLabel}
视觉主题：${themeDesc}

# 硬性契约（违反即返工，不接受任何例外）
1. **单文件自包含**：一个 HTML 文件包含全部 CSS/JS；不引用任何外部 URL（无 CDN、无网络字体、无图片请求、无 fetch/XHR/import）。图标用内联 SVG 或字符。
2. **零网络请求**：document 里不得出现任何 http(s) 资源引用。
3. **字体**：系统字体栈。等宽：ui-monospace, "JetBrains Mono", Menlo, Consolas, monospace；无衬线：system-ui, -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif。
4. **中文 UI**：界面文案简体中文，可带英文副题。
5. **移动触屏优先**：主交互支持 pointer events（触摸与鼠标统一）；交互目标 ≥ 44px。
6. **生命周期**：就绪遮罩 → 运行 → 暂停/结算遮罩（可重开）。visibilitychange 自动暂停。
7. **本地战绩**：最高分/最佳记录存 localStorage，键名 \`guru.pg.${input.id}.hi\`；必须用安全包裹（见下）。
8. **存储安全包裹**（照抄）：const store={get(k){try{return localStorage.getItem(k)}catch(e){return null}},set(k,v){try{localStorage.setItem(k,v)}catch(e){}}};
9. **性能**：requestAnimationFrame 主循环；dt 钳制 ≤ 50ms；避免每帧大对象分配。
10. **可访问性**：可点区域有 aria-label；数值有语义标签；prefers-reduced-motion 时关闭装饰性动画。
11. **体积**：整个文件 ≤ 40KB；不写任何vendored第三方库——所有逻辑亲手写。
12. **质量门（最重要）**：拒绝玩具感。作品必须有真实深度：
    - 游戏：完整状态机、难度递增、连续反馈（粒子/震屏/连击提示）、结算面板含统计
    - 工具：真实输入校验、空态/错误态、结果可复制、键盘可用
    - 可视化：真实数据、图例、悬浮详情、至少一个可切换维度
    - 动画：可调参数面板、性能自适应（fps 低时降粒子数）
13. **音效（可选加分）**：用 WebAudio 振荡器合成，零素材；默认开启但提供静音开关。

# 输出格式
只输出一个完整 HTML 文件：以 <!DOCTYPE html> 开始，以 </html> 结束。
不要任何解释文字、不要 Markdown 代码围栏。`
}

/** 游乐场 HTML 校验：返回违规列表（空数组 = 通过） */
export function validatePlaygroundHtml(html: string): string[] {
  const errors: string[] = []
  if (!html || html.length < 800) errors.push('文件过短（< 800 字符），疑似未完成')
  if (!/^\s*<!DOCTYPE html>/i.test(html)) errors.push('缺少 <!DOCTYPE html> 开头')
  if (!/<\/html>\s*$/i.test(html.trim())) errors.push('缺少 </html> 结尾')
  if (!/<body/i.test(html)) errors.push('缺少 <body>')

  // 网络引用：src/href/url() 指向 http(s)
  const netPatterns: Array<[RegExp, string]> = [
    [/\ssrc\s*=\s*["']https?:\/\//i, '存在外部 src 引用（违反零网络契约）'],
    [/\shref\s*=\s*["']https?:\/\//i, '存在外部 href 引用（违反零网络契约）'],
    [/url\(\s*["']?https?:\/\//i, 'CSS 引用了外部 url()（违反零网络契约）'],
    [/\bfetch\s*\(/, '出现 fetch(（违反零网络契约）'],
    [/new\s+XMLHttpRequest/, '出现 XMLHttpRequest（违反零网络契约）'],
    [/\bimport\s+["']https?:/i, '出现 https 模块导入（违反零网络契约）'],
    [/@import\s+["']?https?:/i, 'CSS @import 外链（违反零网络契约）'],
    [/<script[^>]*\ssrc\s*=/i, '存在带 src 的 script 标签（违反自包含契约）'],
    [/<link[^>]*\shref\s*=\s*["'][^"']*\.css/i, '存在外部样式表链接（违反自包含契约）'],
  ]
  for (const [re, msg] of netPatterns) {
    if (re.test(html)) errors.push(msg)
  }

  // localStorage 安全包裹：直接调用 getItem/setItem 且没有 try 包裹时给出提醒级错误
  if (/localStorage\.(get|set|remove)Item/.test(html) && !/function\s*\(|=>|try\s*\{/.test('')) {
    // 无法静态判断时跳过
  }
  if (html.length > 80 * 1024) errors.push(`文件过大（${(html.length / 1024) | 0}KB > 80KB 硬上限）`)
  return errors
}

/** 从 LLM 输出中提取 HTML（容忍代码围栏、前后废话） */
export function extractHtml(raw: string): string | null {
  if (!raw) return null
  // 优先：```html ... ``` 围栏
  const fence = raw.match(/```(?:html)?\s*\n?([\s\S]*?)```/i)
  const candidate = fence ? fence[1] : raw
  const start = candidate.search(/<!DOCTYPE html>/i)
  const endIdx = candidate.toLowerCase().lastIndexOf('</html>')
  if (start === -1 || endIdx === -1) return null
  return candidate.slice(start, endIdx + '</html>'.length).trim()
}
