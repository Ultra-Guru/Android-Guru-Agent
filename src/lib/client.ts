// 客户端数据访问层 —— 类型 + fetch 封装
'use client'

export interface WorkspaceSummary {
  id: string
  name: string
  emoji: string
  description: string | null
  projectRules: string
  conversationCount: number
  lastUsedAt: string
  createdAt: string
}

export interface ConversationSummary {
  id: string
  title: string
  mode: 'agent' | 'coding'
  updatedAt: string
  createdAt: string
  messageCount: number
}

export interface ChatMessage {
  id: string
  role: 'user' | 'assistant'
  content: string
  createdAt: string
}

export interface ConversationDetail {
  id: string
  workspaceId: string
  title: string
  mode: 'agent' | 'coding'
  createdAt: string
  updatedAt: string
  messages: ChatMessage[]
  workspace: { id: string; name: string; emoji: string; description: string | null }
}

export interface MemoryEntry {
  id: string
  category: 'fact' | 'state' | 'milestone' | 'preference'
  content: string
  importance: number
  accessCount: number
  lastAccessedAt: string
  createdAt: string
  source: 'manual' | 'auto'
  score?: number
}

export interface ProfileEntry {
  id: string
  aspect: string
  content: string
  confidence: number
  evidence: string | null
  source: 'manual' | 'derived'
  accessCount: number
  lastAccessedAt: string
  createdAt: string
  updatedAt: string
}

export interface ProfileStats {
  total: number
  covered: number
  derived: number
  lastDerivedAt: string | null
}

export interface PlaygroundAppSummary {
  id: string
  title: string
  description: string
  category: 'game' | 'tool' | 'viz' | 'animation'
  builtin: boolean
  version: number
  prompt: string | null
  createdAt: string
  updatedAt: string
}

export interface ContextSection {
  key: string
  title: string
  subtitle: string
  body: string
  chars: number
}

export interface ContextPreview {
  mode: 'agent' | 'coding'
  sections: ContextSection[]
  prompt: string
  stats: {
    totalChars: number
    memoryCount: number
    memoryPool: number
    profileCount: number
    globalRulesChars: number
    projectRulesChars: number
  }
}

export interface ImportStats {
  workspaces: number
  conversations: number
  messages: number
  memories: number
  profile: number
  playground: number
  skippedMemories: number
  skippedProfile: number
}

export class ApiError extends Error {
  status: number
  constructor(message: string, status: number) {
    super(message)
    this.status = status
  }
}

async function json<T>(res: Response): Promise<T> {
  const body = await res.json().catch(() => ({}))
  if (!res.ok) {
    throw new ApiError((body as { error?: string }).error || `请求失败（${res.status}）`, res.status)
  }
  return body as T
}

export const api = {
  listWorkspaces: () =>
    fetch('/api/workspaces').then((r) => json<{ workspaces: WorkspaceSummary[]; globalRules: string }>(r)),

  createWorkspace: (data: { name: string; emoji: string; description?: string }) =>
    fetch('/api/workspaces', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(data),
    }).then((r) => json<{ workspace: WorkspaceSummary }>(r)),

  updateWorkspace: (
    id: string,
    data: { name?: string; emoji?: string; description?: string; projectRules?: string; touch?: boolean }
  ) =>
    fetch(`/api/workspaces/${id}`, {
      method: 'PATCH',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(data),
    }).then((r) => json<{ workspace: unknown }>(r)),

  deleteWorkspace: (id: string) =>
    fetch(`/api/workspaces/${id}`, { method: 'DELETE' }).then((r) => json<{ ok: boolean }>(r)),

  listConversations: (wsId: string) =>
    fetch(`/api/workspaces/${wsId}/conversations`).then((r) => json<{ conversations: ConversationSummary[] }>(r)),

  createConversation: (wsId: string, mode: 'agent' | 'coding') =>
    fetch(`/api/workspaces/${wsId}/conversations`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ mode }),
    }).then((r) => json<{ conversation: ConversationSummary }>(r)),

  getConversation: (id: string) =>
    fetch(`/api/conversations/${id}`).then((r) => json<{ conversation: ConversationDetail }>(r)),

  updateConversation: (id: string, data: { title?: string; mode?: 'agent' | 'coding' }) =>
    fetch(`/api/conversations/${id}`, {
      method: 'PATCH',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(data),
    }).then((r) => json<{ ok: boolean }>(r)),

  deleteConversation: (id: string) =>
    fetch(`/api/conversations/${id}`, { method: 'DELETE' }).then((r) => json<{ ok: boolean }>(r)),

  /** 发送消息（SSE 流）。onEvent 接收逐事件；regenerate=true 时重推末条回复；返回中止函数 */
  sendMessage: (
    convId: string,
    content: string,
    onEvent: (ev: { type: 'user' | 'delta' | 'done' | 'error'; [k: string]: unknown }) => void,
    opts?: { regenerate?: boolean }
  ): { promise: Promise<boolean>; abort: () => void } => {
    const controller = new AbortController()
    const promise = (async () => {
      const res = await fetch(`/api/conversations/${convId}/messages`, {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ content, regenerate: opts?.regenerate ?? false }),
        signal: controller.signal,
      })
      if (!res.ok || !res.body) {
        const body = await res.json().catch(() => ({}))
        onEvent({ type: 'error', message: (body as { error?: string }).error || '发送失败' })
        return false
      }
      const reader = res.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''
      for (;;) {
        const { done, value } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true })
        const lines = buffer.split('\n\n')
        buffer = lines.pop() ?? ''
        for (const line of lines) {
          const trimmed = line.trim()
          if (!trimmed.startsWith('data:')) continue
          try {
            onEvent(JSON.parse(trimmed.slice(5).trim()))
          } catch {
            // 忽略
          }
        }
      }
      return true
    })().catch((err: unknown) => {
      if ((err as Error).name === 'AbortError') {
        onEvent({ type: 'error', message: '已停止' })
      } else {
        onEvent({ type: 'error', message: '网络中断' })
      }
      return false
    })
    return { promise, abort: () => controller.abort() }
  },

  getRules: () =>
    fetch('/api/rules').then((r) =>
      json<{ globalRules: string; workspaces: Array<{ id: string; name: string; emoji: string; projectRules: string; conversationCount: number }> }>(r)
    ),

  getContextPreview: (workspaceId: string, mode: 'agent' | 'coding') =>
    fetch(`/api/context?workspaceId=${encodeURIComponent(workspaceId)}&mode=${mode}`).then((r) =>
      json<ContextPreview>(r)
    ),

  importData: (data: unknown) =>
    fetch('/api/import', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(data),
    }).then((r) => json<{ ok: boolean; stats: ImportStats; error?: string }>(r)),

  saveRules: (data: { scope: 'global' | 'workspace'; rules: string; workspaceId?: string }) =>
    fetch('/api/rules', {
      method: 'PUT',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(data),
    }).then((r) => json<{ ok: boolean }>(r)),

  listMemories: () => fetch('/api/memory').then((r) => json<{ memories: MemoryEntry[] }>(r)),

  addMemory: (data: { content: string; category: string; importance: number }) =>
    fetch('/api/memory', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(data),
    }).then((r) => json<{ memory: MemoryEntry }>(r)),

  updateMemory: (id: string, data: { content?: string; category?: string; importance?: number }) =>
    fetch(`/api/memory/${id}`, {
      method: 'PATCH',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(data),
    }).then((r) => json<{ ok: boolean }>(r)),

  deleteMemory: (id: string) => fetch(`/api/memory/${id}`, { method: 'DELETE' }).then((r) => json<{ ok: boolean }>(r)),

  clearMemories: () =>
    fetch('/api/memory?all=1', { method: 'DELETE' }).then((r) => json<{ ok: boolean; deleted: number }>(r)),

  listProfile: () => fetch('/api/profile').then((r) => json<{ profile: ProfileEntry[]; stats: ProfileStats }>(r)),

  addProfile: (data: { content: string; aspect: string; confidence: number }) =>
    fetch('/api/profile', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(data),
    }).then((r) => json<{ profileItem: ProfileEntry }>(r)),

  updateProfile: (id: string, data: { content?: string; aspect?: string; confidence?: number }) =>
    fetch(`/api/profile/${id}`, {
      method: 'PATCH',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(data),
    }).then((r) => json<{ ok: boolean }>(r)),

  deleteProfile: (id: string) => fetch(`/api/profile/${id}`, { method: 'DELETE' }).then((r) => json<{ ok: boolean }>(r)),

  clearProfile: () =>
    fetch('/api/profile?all=1', { method: 'DELETE' }).then((r) => json<{ ok: boolean; deleted: number }>(r)),

  distillProfile: () =>
    fetch('/api/profile/distill', { method: 'POST' }).then((r) =>
      json<{ ok: boolean; changed: number; note: string; error?: string }>(r)
    ),

  listPlayground: () => fetch('/api/playground').then((r) => json<{ apps: PlaygroundAppSummary[] }>(r)),

  getPlaygroundApp: (id: string) =>
    fetch(`/api/playground/${id}`).then((r) => json<{ app: PlaygroundAppSummary & { html: string } }>(r)),

  deletePlaygroundApp: (id: string) =>
    fetch(`/api/playground/${id}`, { method: 'DELETE' }).then((r) => json<{ ok: boolean }>(r)),

  generatePlayground: (data: {
    prompt: string
    category: string
    theme: string
    regenerateOf?: string
    title?: string
  }) =>
    fetch('/api/playground/generate', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(data),
    }).then((r) => json<{ ok: boolean; app: PlaygroundAppSummary; replaced: boolean; error?: string; details?: string[] }>(r)),
}

// ── 共享常量 ──
export const MEMORY_CATEGORIES: Record<string, { label: string; badge: string }> = {
  fact: { label: '稳定事实', badge: '事' },
  state: { label: '近况', badge: '近' },
  milestone: { label: '里程碑', badge: '碑' },
  preference: { label: '偏好', badge: '好' },
}

/** 规则模板：一键插入常用规则片段（设置页与编码输入框共用） */
export const RULE_TEMPLATES: Array<{ label: string; text: string }> = [
  {
    label: '语言口吻',
    text: '· 默认使用简体中文回答；专业术语保留英文原文\n· 回答先给结论，再给依据',
  },
  {
    label: '代码风格',
    text: '· 代码使用 TypeScript strict 模式\n· 标识符用英文，注释用中文\n· 优先复用现有组件，不重复造轮子',
  },
  {
    label: '输出格式',
    text: '· 长回答用小节标题组织\n· 关键结论加粗，结尾给出可执行的下一步',
  },
  {
    label: '安全约束',
    text: '· 不修改与任务无关的代码\n· 涉及删除/覆盖类操作时，先说明影响再给出方案',
  },
]

export const PROFILE_ASPECTS: Array<{ key: string; label: string; hint: string; icon: string }> = [
  { key: 'tech-stack', label: '技术栈', hint: '常用语言、框架、工具链', icon: '⌨' },
  { key: 'coding-style', label: '编码风格', hint: '命名/注释/结构偏好', icon: '✎' },
  { key: 'preferences', label: '偏好', hint: '交互与输出偏好', icon: '◈' },
  { key: 'domains', label: '关注领域', hint: '正在做的业务与技术领域', icon: '◎' },
  { key: 'habits', label: '工作习惯', hint: '工作节奏、提交习惯、作息', icon: '⏱' },
  { key: 'goals', label: '目标', hint: '正在推进的短期/长期目标', icon: '◇' },
]

export const PLAYGROUND_CATEGORIES: Record<string, { label: string; icon: string }> = {
  game: { label: '小游戏', icon: '◆' },
  tool: { label: '实用工具', icon: '⚒' },
  viz: { label: '数据可视化', icon: '▤' },
  animation: { label: '生成艺术', icon: '✦' },
}

export const PLAYGROUND_THEMES: Array<{ key: string; label: string; swatch: string }> = [
  { key: 'neon', label: '终端霓虹', swatch: 'linear-gradient(135deg,#0a0f0c 30%,#2ee66b 30%,#2ee66b 45%,#ffb454 45%,#ffb454 60%,#0a0f0c 60%)' },
  { key: 'paper', label: '纸上极简', swatch: 'linear-gradient(135deg,#f7f4ec 30%,#2b2620 30%,#2b2620 45%,#b3541e 45%,#b3541e 60%,#f7f4ec 60%)' },
  { key: 'candy', label: '软糖活力', swatch: 'linear-gradient(135deg,#fff1f6 30%,#ff5c8a 30%,#ff5c8a 45%,#ffd166 45%,#ffd166 60%,#fff1f6 60%)' },
  { key: 'brutal', label: '粗野主义', swatch: 'linear-gradient(135deg,#fff 35%,#000 35%,#000 50%,#ffe11a 50%,#ffe11a 65%,#fff 65%)' },
]

export function timeAgo(iso: string): string {
  const t = new Date(iso).getTime()
  const diff = Date.now() - t
  if (diff < 60_000) return '刚刚'
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)} 分钟前`
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)} 小时前`
  if (diff < 7 * 86_400_000) return `${Math.floor(diff / 86_400_000)} 天前`
  const d = new Date(t)
  return `${d.getMonth() + 1}月${d.getDate()}日`
}

export function conversationGroup(iso: string): string {
  const d = new Date(iso)
  const now = new Date()
  const startOfDay = (x: Date) => new Date(x.getFullYear(), x.getMonth(), x.getDate()).getTime()
  const diffDays = Math.floor((startOfDay(now) - startOfDay(d)) / 86_400_000)
  if (diffDays <= 0) return '今天'
  if (diffDays === 1) return '昨天'
  if (diffDays < 7) return '7 天内'
  if (diffDays < 30) return '30 天内'
  return '更早'
}
