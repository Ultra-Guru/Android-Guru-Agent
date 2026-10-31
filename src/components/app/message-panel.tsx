'use client'

// 消息面板：空态（工作区 Hero + 双模式 CTA） / 对话（头部模式切换 + 消息流 + 输入区）
// 编码模式输入区带「项目规则」快捷编辑（写入当前工作区 projectRules —— 与设置页同一数据源）
import { useEffect, useRef, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import {
  api,
  type ChatMessage,
  type ConversationSummary,
  type WorkspaceSummary,
} from '@/lib/client'
import { Markdown } from '@/components/app/markdown'
import { Button } from '@/components/ui/button'
import { Textarea } from '@/components/ui/textarea'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover'
import { Badge } from '@/components/ui/badge'
import { cn } from '@/lib/utils'
import { toast } from '@/hooks/use-toast'
import {
  Send,
  Square,
  Copy,
  Bot,
  User,
  Sparkles,
  Code2,
  ScrollText,
  Info,
  BrainCircuit,
  FileText,
  Loader2,
  Menu,
} from 'lucide-react'

type Mode = 'agent' | 'coding'

// ═══════════ 空态 ═══════════
function EmptyState({
  workspace,
  onNewConversation,
  onOpenMobileNav,
}: {
  workspace: WorkspaceSummary
  onNewConversation: (mode: Mode) => void
  onOpenMobileNav: () => void
}) {
  const { data: rulesData } = useQuery({ queryKey: ['rules'], queryFn: api.getRules })
  const { data: profileData } = useQuery({ queryKey: ['profile'], queryFn: api.listProfile })
  const globalLen = rulesData?.globalRules?.length ?? 0
  const projectLen = workspace.projectRules?.length ?? 0
  const profileCount = profileData?.stats.total ?? 0

  return (
    <div className="flex-1 min-w-0 flex flex-col overflow-y-auto">
      <div className="shrink-0 md:hidden h-12 flex items-center px-2 border-b border-border/60">
        <Button variant="ghost" size="sm" className="h-9 gap-1.5 text-xs" onClick={onOpenMobileNav}>
          <Menu className="size-4" /> 工作区 / 历史
        </Button>
      </div>
      <div className="flex-1 flex flex-col items-center justify-center px-6 py-10">
      <div className="w-full max-w-xl text-center">
        <div className="text-6xl mb-4 select-none" aria-hidden>
          {workspace.emoji}
        </div>
        <h2 className="text-2xl font-bold tracking-tight">{workspace.name}</h2>
        <p className="mt-2 text-sm text-muted-foreground leading-relaxed max-w-md mx-auto">
          {workspace.description || '这个工作区还没有描述——可以在设置里补充，它会作为上下文注入对话。'}
        </p>

        <div className="mt-6 flex flex-wrap justify-center gap-2 text-[11px] font-mono">
          <Badge variant="outline" className="gap-1.5 font-mono font-normal">
            <ScrollText className="size-3" />
            软件级规则 {globalLen > 0 ? `${globalLen} 字` : '未配置'}
          </Badge>
          <Badge variant="outline" className="gap-1.5 font-mono font-normal">
            <FileText className="size-3" />
            项目级规则 {projectLen > 0 ? `${projectLen} 字` : '未配置'}
          </Badge>
          <Badge variant="outline" className="gap-1.5 font-mono font-normal">
            <BrainCircuit className="size-3" />
            用户画像 {profileCount > 0 ? `${profileCount} 条` : '待建立'}
          </Badge>
        </div>

        <div className="mt-8 grid sm:grid-cols-2 gap-3">
          <button
            onClick={() => onNewConversation('agent')}
            className="group text-left rounded-2xl border border-border bg-card p-5 hover:border-primary/60 hover:shadow-[0_0_24px_hsl(152_84%_60%/0.12)] transition-all"
          >
            <Sparkles className="size-6 text-primary mb-3" />
            <div className="font-semibold">智能体对话</div>
            <p className="mt-1 text-xs text-muted-foreground leading-relaxed">
              问答 · 分析 · 写作 · 规划。记忆自动沉淀到记忆页。
            </p>
          </button>
          <button
            onClick={() => onNewConversation('coding')}
            className="group text-left rounded-2xl border border-border bg-card p-5 hover:border-primary/60 hover:shadow-[0_0_24px_hsl(152_84%_60%/0.12)] transition-all"
          >
            <Code2 className="size-6 text-primary mb-3" />
            <div className="font-semibold">编码对话</div>
            <p className="mt-1 text-xs text-muted-foreground leading-relaxed">
              工程任务 · 代码方案。输入框可写项目规则；用户画像自动注入。
            </p>
          </button>
        </div>

        <p className="mt-6 text-[11px] text-muted-foreground/80 font-mono">
          新对话将创建于「{workspace.name}」；切换工作区 = 切换对话集合
        </p>
      </div>
      </div>
    </div>
  )
}

// ═══════════ 项目规则快捷编辑（编码模式输入区） ═══════════
function RulesQuickEdit({ workspace }: { workspace: WorkspaceSummary }) {
  const queryClient = useQueryClient()
  const [open, setOpen] = useState(false)
  const [saving, setSaving] = useState(false)
  const [draftByWs, setDraftByWs] = useState<{ id: string; value: string }>({
    id: workspace.id,
    value: workspace.projectRules,
  })

  // 渲染期对齐外部变更（工作区切换 / 设置页保存后失效刷新）——React 官方推荐模式，非 effect
  if (draftByWs.id !== workspace.id) {
    setDraftByWs({ id: workspace.id, value: workspace.projectRules })
  }
  const draft = draftByWs.id === workspace.id ? draftByWs.value : workspace.projectRules

  const save = async () => {
    setSaving(true)
    try {
      await api.updateWorkspace(workspace.id, { projectRules: draft })
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['workspaces'] }),
        queryClient.invalidateQueries({ queryKey: ['rules'] }),
      ])
      toast({ title: '项目规则已保存', description: '仅在本工作区的对话中生效。' })
      setOpen(false)
    } catch (e) {
      toast({ title: '保存失败', description: (e as Error).message, variant: 'destructive' })
    } finally {
      setSaving(false)
    }
  }

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <button
          className={cn(
            'flex items-center gap-1.5 h-8 px-2.5 rounded-lg text-[11px] font-mono transition-colors',
            workspace.projectRules
              ? 'text-primary hover:bg-primary/10 border border-primary/30'
              : 'text-muted-foreground hover:bg-accent border border-border'
          )}
          aria-label="编辑当前工作区的项目级规则"
        >
          <ScrollText className="size-3.5" />
          项目规则 {workspace.projectRules ? `${workspace.projectRules.length} 字` : '未配置'}
        </button>
      </PopoverTrigger>
      <PopoverContent align="start" className="w-96 p-4" sideOffset={8}>
        <div className="space-y-1 mb-3">
          <p className="text-sm font-semibold flex items-center gap-2">
            <ScrollText className="size-4 text-primary" /> 项目级规则 · {workspace.name}
          </p>
          <p className="text-[11px] text-muted-foreground leading-relaxed">
            仅在本工作区生效（优先于软件级规则）；也可在 设置 → 规则 里编辑。
          </p>
        </div>
        <Textarea
          value={draft}
          onChange={(e) => setDraftByWs({ id: workspace.id, value: e.target.value })}
          rows={8}
          maxLength={64000}
          placeholder={'例如：\n· 使用 TypeScript strict 模式\n· 组件优先 shadcn/ui\n· 注释用中文，标识符用英文'}
          className="font-mono text-xs leading-relaxed"
          aria-label="项目级规则内容"
        />
        <div className="flex items-center justify-between mt-3">
          <span className="text-[10px] font-mono text-muted-foreground">{draft.length} 字</span>
          <div className="flex gap-2">
            <Button variant="ghost" size="sm" onClick={() => setDraftByWs({ id: workspace.id, value: workspace.projectRules })} disabled={saving}>
              重置
            </Button>
            <Button size="sm" onClick={save} disabled={saving}>
              {saving ? <Loader2 className="size-3.5 animate-spin" /> : '保存'}
            </Button>
          </div>
        </div>
      </PopoverContent>
    </Popover>
  )
}

// ═══════════ 单条消息 ═══════════
function MessageBubble({ msg, interrupted }: { msg: ChatMessage; interrupted?: boolean }) {
  const isUser = msg.role === 'user'
  const [copied, setCopied] = useState(false)
  return (
    <div className={cn('flex gap-3 px-1', isUser ? 'flex-row-reverse' : '')}>
      <div
        className={cn(
          'size-8 shrink-0 rounded-lg grid place-items-center font-mono text-xs mt-1',
          isUser ? 'bg-muted text-muted-foreground' : 'bg-primary/15 text-primary border border-primary/30'
        )}
        aria-hidden
      >
        {isUser ? <User className="size-4" /> : <Bot className="size-4" />}
      </div>
      <div className={cn('min-w-0 max-w-[calc(100%-3rem)]', isUser && 'flex flex-col items-end')}>
        <div
          className={cn(
            'rounded-2xl px-4 py-3',
            isUser
              ? 'bg-primary text-primary-foreground rounded-tr-md'
              : 'bg-card border border-border rounded-tl-md'
          )}
        >
          {isUser ? (
            <p className="text-sm leading-relaxed whitespace-pre-wrap break-words">{msg.content}</p>
          ) : (
            <Markdown content={msg.content} />
          )}
        </div>
        <div className={cn('flex items-center gap-2 mt-1.5 px-1', isUser ? 'flex-row-reverse' : '')}>
          {interrupted && (
            <span className="text-[10px] font-mono text-destructive flex items-center gap-1">
              <Info className="size-3" /> 已中断 · 未保存
            </span>
          )}
          {!isUser && !interrupted && (
            <button
              className="text-[10px] font-mono text-muted-foreground hover:text-primary transition-colors flex items-center gap-1"
              onClick={() => {
                navigator.clipboard?.writeText(msg.content).then(() => {
                  setCopied(true)
                  setTimeout(() => setCopied(false), 1500)
                })
              }}
              aria-label="复制消息内容"
            >
              {copied ? '已复制 ✓' : <><Copy className="size-3" /> 复制</>}
            </button>
          )}
        </div>
      </div>
    </div>
  )
}

// ═══════════ 面板 ═══════════
export function MessagePanel({
  workspace,
  conversationId,
  conversations,
  onNewConversation,
  onModeChanged,
  onTitleChanged,
  onOpenMobileNav,
}: {
  workspace: WorkspaceSummary
  conversationId: string | null
  conversations: ConversationSummary[]
  onNewConversation: (mode: Mode) => void
  onModeChanged: () => void
  onTitleChanged: () => void
  onOpenMobileNav: () => void
}) {
  const current = conversations.find((c) => c.id === conversationId) || null
  const mode: Mode = current?.mode ?? 'agent'
  const queryClient = useQueryClient()
  const [draft, setDraft] = useState('')
  const [streamBuf, setStreamBuf] = useState<string | null>(null)
  const [streamInterrupted, setStreamInterrupted] = useState<ChatMessage | null>(null)
  const [sending, setSending] = useState(false)
  const abortRef = useRef<(() => void) | null>(null)
  const scrollRef = useRef<HTMLDivElement>(null)
  const pinnedRef = useRef(true)

  // 加载对话消息（直接从 query 派生，避免本地拷贝）
  const { data: convData, isLoading: convLoading } = useQuery({
    queryKey: ['conversation', conversationId],
    queryFn: () => api.getConversation(conversationId!),
    enabled: !!conversationId,
  })
  const messages: ChatMessage[] = convData?.conversation?.messages ?? []

  // 自动滚动（用户手动上翻则不打扰）
  useEffect(() => {
    const el = scrollRef.current
    if (el && pinnedRef.current) el.scrollTop = el.scrollHeight
  }, [messages, streamBuf])
  const onScroll = () => {
    const el = scrollRef.current
    if (!el) return
    pinnedRef.current = el.scrollHeight - el.scrollTop - el.clientHeight < 140
  }

  const switchMode = async (m: Mode) => {
    if (!conversationId || m === mode || sending) return
    try {
      await api.updateConversation(conversationId, { mode: m })
      onModeChanged()
      toast({
        title: m === 'coding' ? '已切换到编码模式' : '已切换到智能体模式',
        description:
          m === 'coding'
            ? '注入：项目级规则 + 软件级规则 + 用户画像'
            : '注入：项目级规则 + 软件级规则 + 相关记忆',
      })
    } catch (e) {
      toast({ title: '切换失败', description: (e as Error).message, variant: 'destructive' })
    }
  }

  const send = () => {
    const content = draft.trim()
    if (!content || !conversationId || sending) return
    setDraft('')
    setSending(true)
    setStreamBuf('')
    pinnedRef.current = true
    let buffer = ''
    const { promise, abort } = api.sendMessage(conversationId, content, (ev) => {
      if (ev.type === 'user') {
        // 用户消息已落库：失效查询使其立即显示
        queryClient.invalidateQueries({ queryKey: ['conversation', conversationId] })
        if (ev.title) onTitleChanged()
      } else if (ev.type === 'delta') {
        buffer += String(ev.text)
        setStreamBuf(buffer)
      } else if (ev.type === 'done') {
        setStreamBuf(null)
        queryClient.invalidateQueries({ queryKey: ['conversation', conversationId] })
        setSending(false)
        onTitleChanged()
      } else if (ev.type === 'error') {
        if (buffer.trim()) {
          setStreamInterrupted({
            id: 'local-interrupted',
            role: 'assistant',
            content: buffer,
            createdAt: new Date().toISOString(),
          })
          setStreamBuf(null)
        } else {
          toast({ title: '发送失败', description: String(ev.message) || '请重试', variant: 'destructive' })
          setStreamBuf(null)
        }
        setSending(false)
      }
    })
    abortRef.current = abort
    promise.then((ok) => {
      if (!ok) setSending(false)
      abortRef.current = null
    })
  }

  if (!conversationId || !current) {
    return <EmptyState workspace={workspace} onNewConversation={onNewConversation} onOpenMobileNav={onOpenMobileNav} />
  }

  return (
    <section className="flex-1 min-w-0 flex flex-col bg-background/40" aria-label="对话面板">
      {/* 头部：移动端菜单 + 标题 + 模式切换 */}
      <header className="shrink-0 h-14 border-b border-border bg-card/50 flex items-center gap-2 sm:gap-3 px-2 sm:px-4">
        <Button
          variant="ghost"
          size="icon"
          className="md:hidden size-9 text-muted-foreground"
          onClick={onOpenMobileNav}
          aria-label="打开工作区与对话列表"
        >
          <Menu className="size-4" />
        </Button>
        <div className="min-w-0 flex-1 flex items-center gap-2">
          <h2 className="font-semibold text-sm truncate">{current.title}</h2>
          <span className="text-[10px] font-mono text-muted-foreground/70 shrink-0">
            {messages.length} 条
          </span>
        </div>
        <div
          role="tablist"
          aria-label="对话模式"
          className="flex p-1 rounded-xl bg-muted gap-1"
        >
          <button
            role="tab"
            aria-selected={mode === 'agent'}
            onClick={() => switchMode('agent')}
            className={cn(
              'flex items-center gap-1.5 h-8 px-3 rounded-lg text-xs font-semibold transition-all',
              mode === 'agent' ? 'bg-primary text-primary-foreground shadow' : 'text-muted-foreground hover:text-foreground'
            )}
          >
            <Sparkles className="size-3.5" /> 智能体
          </button>
          <button
            role="tab"
            aria-selected={mode === 'coding'}
            onClick={() => switchMode('coding')}
            className={cn(
              'flex items-center gap-1.5 h-8 px-3 rounded-lg text-xs font-semibold transition-all',
              mode === 'coding' ? 'bg-primary text-primary-foreground shadow' : 'text-muted-foreground hover:text-foreground'
            )}
          >
            <Code2 className="size-3.5" /> 编码
          </button>
        </div>
      </header>

      {/* 消息流 */}
      <div
        ref={scrollRef}
        onScroll={onScroll}
        className="flex-1 min-h-0 overflow-y-auto"
        role="log"
        aria-live="polite"
        aria-label="消息列表"
      >
        <div className="max-w-3xl mx-auto w-full px-4 py-6 space-y-6">
          {convLoading && messages.length === 0 ? (
            <p className="text-center text-xs text-muted-foreground font-mono py-10">加载对话…</p>
          ) : (
            messages.map((m) => <MessageBubble key={m.id} msg={m} />)
          )}
          {streamInterrupted && <MessageBubble msg={streamInterrupted} interrupted />}
          {streamBuf !== null && (
            <div className="flex gap-3 px-1">
              <div className="size-8 shrink-0 rounded-lg grid place-items-center bg-primary/15 text-primary border border-primary/30 mt-1" aria-hidden>
                <Bot className="size-4" />
              </div>
              <div className="min-w-0 max-w-[calc(100%-3rem)] rounded-2xl rounded-tl-md px-4 py-3 bg-card border border-border">
                {streamBuf ? (
                  <Markdown content={streamBuf} />
                ) : (
                  <span className="inline-flex gap-1 items-center text-muted-foreground text-sm">
                    思考中
                    <span className="animate-bounce inline-block size-1.5 rounded-full bg-primary [animation-delay:0ms]" />
                    <span className="animate-bounce inline-block size-1.5 rounded-full bg-primary [animation-delay:150ms]" />
                    <span className="animate-bounce inline-block size-1.5 rounded-full bg-primary [animation-delay:300ms]" />
                  </span>
                )}
              </div>
            </div>
          )}
          {messages.length === 0 && streamBuf === null && !streamInterrupted && !convLoading && (
            <div className="text-center py-10">
              <p className="text-sm text-muted-foreground">
                {mode === 'coding' ? '提出一个工程任务开始编码对话。' : '随便聊点什么开始这段对话。'}
              </p>
            </div>
          )}
        </div>
      </div>

      {/* 输入区 */}
      <div className="shrink-0 border-t border-border bg-card/50">
        <div className="max-w-3xl mx-auto w-full px-4 py-3">
          {mode === 'coding' && (
            <div className="flex items-center gap-2 mb-2.5 flex-wrap">
              <RulesQuickEdit workspace={workspace} />
              <span className="text-[10px] font-mono text-muted-foreground/80">
                输入框里写的规则属于项目级 · 仅工作区内生效
              </span>
            </div>
          )}
          <div className="relative">
            <Textarea
              value={draft}
              onChange={(e) => setDraft(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
                  e.preventDefault()
                  send()
                }
              }}
              rows={2}
              maxLength={24000}
              placeholder={
                mode === 'coding'
                  ? '描述工程任务…（Shift+Enter 换行）'
                  : '发消息…（Shift+Enter 换行）'
              }
              aria-label="消息输入框"
              className="pr-24 pb-11 resize-none min-h-[88px] bg-background/70"
            />
            <div className="absolute bottom-2.5 right-2.5 flex items-center gap-2">
              <span className="text-[10px] font-mono text-muted-foreground/70 hidden sm:inline tabular-nums">
                {draft.length > 0 && `${draft.length}`}
              </span>
              {sending ? (
                <Button size="sm" variant="destructive" onClick={() => abortRef.current?.()} className="h-9 gap-1.5">
                  <Square className="size-3.5" /> 停止
                </Button>
              ) : (
                <Button
                  size="sm"
                  onClick={send}
                  disabled={!draft.trim()}
                  className="h-9 gap-1.5 shadow-[0_0_16px_hsl(152_84%_60%/0.3)]"
                  aria-label="发送消息"
                >
                  <Send className="size-3.5" /> 发送
                </Button>
              )}
            </div>
          </div>
          <p className="mt-2 text-[10px] font-mono text-muted-foreground/70">
            {mode === 'coding'
              ? '编码模式注入：项目级规则 > 软件级规则 > 用户画像 > 记忆'
              : '智能体模式注入：项目级规则 > 软件级规则 > 记忆'}
          </p>
        </div>
      </div>
    </section>
  )
}
