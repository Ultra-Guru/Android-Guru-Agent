'use client'

// ═══════════ 全局命令面板（Cmd/Ctrl+K） ═══════════
// 三类能力：
//  1. 快速动作：新对话（智能体/编码）、视图切换、主题切换
//  2. 工作区切换（键盘直达，与侧栏同源）
//  3. 深度搜索：跨工作区匹配对话标题与消息内容（/api/search），命中后跨工作区直达
// 过滤完全自管（Command shouldFilter=false）：动作/视图/工作区本地过滤，对话走服务端。

import { useEffect, useMemo, useRef, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useTheme } from 'next-themes'
import { api, timeAgo, type SearchResult } from '@/lib/client'
import { useApp, type ViewKey } from '@/lib/store'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Command, CommandGroup, CommandInput, CommandItem, CommandList } from '@/components/ui/command'
import {
  MessageSquarePlus,
  Code2,
  Sun,
  Moon,
  MessageSquare,
  Brain,
  Gamepad2,
  Settings2,
  CornerDownLeft,
  ArrowUp,
  ArrowDown,
} from 'lucide-react'
import { toast } from '@/hooks/use-toast'

/** 关键词高亮（大小写不敏感，命中片段用 <mark>） */
function Highlight({ text, q }: { text: string; q: string }) {
  const query = q.trim()
  if (!query) return <>{text}</>
  const idx = text.toLowerCase().indexOf(query.toLowerCase())
  if (idx === -1) return <>{text}</>
  return (
    <>
      {text.slice(0, idx)}
      <mark className="bg-primary/25 text-primary rounded-[2px] px-0.5 font-semibold">
        {text.slice(idx, idx + query.length)}
      </mark>
      {text.slice(idx + query.length)}
    </>
  )
}

const VIEW_ITEMS: Array<{ key: ViewKey; label: string; icon: React.ElementType }> = [
  { key: 'chat', label: '对话', icon: MessageSquare },
  { key: 'memory', label: '记忆与画像', icon: Brain },
  { key: 'playground', label: '游乐场', icon: Gamepad2 },
  { key: 'settings', label: '设置', icon: Settings2 },
]

export function CommandPalette() {
  const open = useApp((s) => s.paletteOpen)
  const setOpen = useApp((s) => s.setPaletteOpen)
  const setView = useApp((s) => s.setView)
  const activeWorkspaceId = useApp((s) => s.activeWorkspaceId)
  const setActiveWorkspace = useApp((s) => s.setActiveWorkspace)
  const setActiveConversation = useApp((s) => s.setActiveConversation)
  const { theme, setTheme } = useTheme()
  const queryClient = useQueryClient()

  const [q, setQ] = useState('')
  const [debouncedQ, setDebouncedQ] = useState('')
  const creatingRef = useRef(false)

  // ── 全局快捷键：Cmd/Ctrl + K ──
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault()
        setOpen(!useApp.getState().paletteOpen)
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [setOpen])

  // ── 防抖搜索词 ──
  useEffect(() => {
    const t = setTimeout(() => setDebouncedQ(q.trim()), 220)
    return () => clearTimeout(t)
  }, [q])

  // ── 打开时重置输入 ──
  useEffect(() => {
    if (open) setQ('')
  }, [open])

  // ── 工作区（与页面同源缓存；面板打开时确保新鲜） ──
  const { data: wsData } = useQuery({
    queryKey: ['workspaces'],
    queryFn: api.listWorkspaces,
    enabled: open,
  })
  const workspaces = wsData?.workspaces ?? []
  const resolvedWsId = workspaces.find((w) => w.id === activeWorkspaceId)?.id ?? workspaces[0]?.id ?? null

  // ── 当前工作区最近对话（空态快速跳转） ──
  const { data: convData } = useQuery({
    queryKey: ['conversations', resolvedWsId],
    queryFn: () => api.listConversations(resolvedWsId!),
    enabled: open && !!resolvedWsId,
  })
  const recentConversations = useMemo(() => (convData?.conversations ?? []).slice(0, 6), [convData])

  // ── 深度搜索（跨工作区 · 含消息内容） ──
  const { data: searchData, isFetching: searching } = useQuery({
    queryKey: ['search', debouncedQ],
    queryFn: () => api.searchAll(debouncedQ),
    enabled: open && debouncedQ.length > 0,
    staleTime: 20_000,
  })
  const searchResults: SearchResult[] = searchData?.results ?? []

  const dark = theme !== 'light'
  const qLower = q.trim().toLowerCase()
  const match = (s: string) => !qLower || s.toLowerCase().includes(qLower)

  const searchPending = debouncedQ.length > 0 && (searching || (searchData?.query ?? '') !== debouncedQ)

  // ── 动作 ──
  const createConversation = async (mode: 'agent' | 'coding') => {
    if (!resolvedWsId || creatingRef.current) return
    creatingRef.current = true
    try {
      const { conversation } = await api.createConversation(resolvedWsId, mode)
      await queryClient.invalidateQueries({ queryKey: ['conversations', resolvedWsId] })
      await queryClient.invalidateQueries({ queryKey: ['workspaces'] })
      setOpen(false)
      setView('chat')
      setActiveConversation(conversation.id)
    } catch (e) {
      toast({ title: '创建对话失败', description: (e as Error).message, variant: 'destructive' })
    } finally {
      creatingRef.current = false
    }
  }

  const jumpToConversation = (r: { conversationId: string; workspaceId: string }) => {
    setOpen(false)
    setView('chat')
    if (r.workspaceId !== resolvedWsId) {
      // 跨工作区直达：切换工作区并锁定目标对话
      setActiveWorkspace(r.workspaceId, { pickConversation: r.conversationId })
    } else {
      setActiveConversation(r.conversationId)
    }
  }

  const switchWorkspace = (id: string) => {
    setOpen(false)
    setView('chat')
    setActiveWorkspace(id, { pickConversation: null })
  }

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="overflow-hidden p-0 sm:max-w-xl gap-0" showCloseButton={false}>
        <DialogHeader className="sr-only">
          <DialogTitle>全局搜索与命令面板</DialogTitle>
          <DialogDescription>
            跨工作区搜索对话标题与消息内容，或执行快速动作。↑↓ 选择，回车确认，Esc 关闭。
          </DialogDescription>
        </DialogHeader>
        <Command shouldFilter={false} className="bg-popover">
          <CommandInput
            value={q}
            onValueChange={setQ}
            placeholder="搜索对话、消息内容，或输入命令…"
          />

          <CommandList className="max-h-[60vh] py-2">
            {/* ── 深度搜索结果（有输入时优先展示） ── */}
            {debouncedQ.length > 0 && (
              <>
                <CommandGroup
                  heading={
                    <span className="flex items-center gap-1.5">
                      对话命中
                      <span className="text-muted-foreground/70 font-normal">
                        跨工作区 · 含消息内容{searchResults.length > 0 && ` · ${searchResults.length} 条`}
                      </span>
                    </span>
                  }
                >
                  {searchPending ? (
                    <div className="py-6 text-center text-xs font-mono text-muted-foreground">
                      正在搜索「{debouncedQ}」…
                    </div>
                  ) : searchResults.length === 0 ? (
                    <div className="py-6 text-center text-xs font-mono text-muted-foreground">
                      没有命中「{debouncedQ}」的对话或消息
                    </div>
                  ) : (
                    searchResults.map((r) => (
                      <CommandItem
                        key={`s-${r.conversationId}`}
                        value={`search-${r.conversationId}`}
                        onSelect={() => jumpToConversation(r)}
                        className="flex flex-col items-stretch gap-0.5 py-2.5 cursor-pointer"
                      >
                        <span className="flex items-center gap-2 min-w-0">
                          {r.mode === 'coding' ? (
                            <Code2 className="size-4 shrink-0 text-primary/80" />
                          ) : (
                            <MessageSquare className="size-4 shrink-0 text-primary/80" />
                          )}
                          <span className="text-sm font-medium truncate flex-1">
                            <Highlight text={r.title} q={q} />
                          </span>
                          {r.hit === 'content' && (
                            <span className="shrink-0 text-[10px] font-mono text-muted-foreground/80 border border-border rounded px-1">
                              内容命中
                            </span>
                          )}
                          {r.workspaceId !== resolvedWsId && (
                            <span className="shrink-0 text-[10px] font-mono text-primary/90 border border-primary/30 rounded px-1">
                              跨区
                            </span>
                          )}
                        </span>
                        {r.snippet && (
                          <span className="text-[11px] font-mono text-muted-foreground leading-relaxed line-clamp-2 pl-6">
                            <span className="text-muted-foreground/70">
                              {r.snippetRole === 'assistant' ? '助手: ' : '用户: '}
                            </span>
                            <Highlight text={r.snippet} q={q} />
                          </span>
                        )}
                        <span className="text-[10px] font-mono text-muted-foreground/70 pl-6">
                          {r.workspaceEmoji} {r.workspaceName} · {r.messageCount} 条 · {timeAgo(r.updatedAt)}
                        </span>
                      </CommandItem>
                    ))
                  )}
                </CommandGroup>
                {/* 输入时仍保留命中的动作/工作区，便于直达 */}
                <QuickActions match={match} dark={dark} setTheme={setTheme} onNew={createConversation} setView={(v) => { setOpen(false); setView(v) }} />
                {workspaces.filter((w) => match(w.name)).length > 0 && (
                  <CommandGroup heading="切换工作区">
                    {workspaces
                      .filter((w) => match(w.name))
                      .map((w) => (
                        <CommandItem
                          key={`ws-${w.id}`}
                          value={`ws-${w.id}`}
                          onSelect={() => switchWorkspace(w.id)}
                          className="cursor-pointer"
                        >
                          <span className="text-lg leading-none">{w.emoji}</span>
                          <span className="flex-1 min-w-0">
                            <span className="block text-sm truncate">{w.name}</span>
                            <span className="block text-[10px] font-mono text-muted-foreground">
                              {w.conversationCount} 对话 · {timeAgo(w.lastUsedAt)}
                            </span>
                          </span>
                          {w.id === resolvedWsId && <CornerDownLeft className="size-3.5 text-primary" />}
                        </CommandItem>
                      ))}
                  </CommandGroup>
                )}
              </>
            )}

            {/* ── 空态：动作 + 视图 + 工作区 + 最近对话 ── */}
            {debouncedQ.length === 0 && (
              <>
                <QuickActions match={match} dark={dark} setTheme={setTheme} onNew={createConversation} setView={(v) => { setOpen(false); setView(v) }} />
                {match('视图') || VIEW_ITEMS.some((v) => match(v.label)) ? (
                  <CommandGroup heading="视图">
                    {VIEW_ITEMS.filter((v) => match(v.label)).map((v) => {
                      const Icon = v.icon
                      return (
                        <CommandItem
                          key={`view-${v.key}`}
                          value={`view-${v.key}`}
                          onSelect={() => {
                            setOpen(false)
                            setView(v.key)
                          }}
                          className="cursor-pointer"
                        >
                          <Icon className="size-4" />
                          <span className="text-sm">{v.label}</span>
                        </CommandItem>
                      )
                    })}
                  </CommandGroup>
                ) : null}
                {workspaces.filter((w) => match(w.name)).length > 0 && (
                  <CommandGroup heading="切换工作区（切换对话集合）">
                    {workspaces
                      .filter((w) => match(w.name))
                      .map((w) => (
                        <CommandItem
                          key={`ws-${w.id}`}
                          value={`ws-${w.id}`}
                          onSelect={() => switchWorkspace(w.id)}
                          className="cursor-pointer"
                        >
                          <span className="text-lg leading-none">{w.emoji}</span>
                          <span className="flex-1 min-w-0">
                            <span className="block text-sm truncate">{w.name}</span>
                            <span className="block text-[10px] font-mono text-muted-foreground">
                              {w.conversationCount} 对话 · {timeAgo(w.lastUsedAt)}
                            </span>
                          </span>
                          {w.id === resolvedWsId && <CornerDownLeft className="size-3.5 text-primary" />}
                        </CommandItem>
                      ))}
                  </CommandGroup>
                )}
                {recentConversations.length > 0 && (
                  <CommandGroup heading="最近对话（当前工作区）">
                    {recentConversations.map((c) => (
                      <CommandItem
                        key={`rc-${c.id}`}
                        value={`rc-${c.id}`}
                        onSelect={() => jumpToConversation({ conversationId: c.id, workspaceId: resolvedWsId ?? '' })}
                        className="cursor-pointer"
                      >
                        {c.mode === 'coding' ? (
                          <Code2 className="size-4 shrink-0 text-primary/80" />
                        ) : (
                          <MessageSquare className="size-4 shrink-0 text-primary/80" />
                        )}
                        <span className="flex-1 min-w-0">
                          <span className="block text-sm truncate">{c.title}</span>
                          <span className="block text-[10px] font-mono text-muted-foreground">
                            {c.messageCount} 条 · {timeAgo(c.updatedAt)}
                          </span>
                        </span>
                      </CommandItem>
                    ))}
                  </CommandGroup>
                )}
              </>
            )}
          </CommandList>

          {/* 快捷键提示条 */}
          <div className="border-t border-border px-3 py-2 flex items-center gap-3 text-[10px] font-mono text-muted-foreground/80">
            <span className="flex items-center gap-1">
              <ArrowUp className="size-3" />
              <ArrowDown className="size-3" /> 导航
            </span>
            <span className="flex items-center gap-1">
              <CornerDownLeft className="size-3" /> 确认
            </span>
            <span>esc 关闭</span>
            <span className="ml-auto hidden sm:inline">搜索范围：全部工作区 · 标题与消息内容</span>
          </div>
        </Command>
      </DialogContent>
    </Dialog>
  )
}

/** 快速动作组（空态与搜索态共用） */
function QuickActions({
  match,
  dark,
  setTheme,
  onNew,
  setView,
}: {
  match: (s: string) => boolean
  dark: boolean
  setTheme: (t: string) => void
  onNew: (mode: 'agent' | 'coding') => void
  setView: (v: ViewKey) => void
}) {
  const items = [
    {
      key: 'new-agent',
      label: '新对话 · 智能体',
      hint: '落在当前工作区',
      icon: MessageSquarePlus,
      matchText: '新对话 智能体 agent new',
      run: () => onNew('agent'),
    },
    {
      key: 'new-coding',
      label: '新对话 · 编码',
      hint: '落在当前工作区',
      icon: Code2,
      matchText: '新对话 编码 coding new',
      run: () => onNew('coding'),
    },
    {
      key: 'theme',
      label: dark ? '切换到亮色主题' : '切换到暗色主题',
      hint: '',
      icon: dark ? Sun : Moon,
      matchText: '主题 亮色 暗色 theme 深色 浅色',
      run: () => setTheme(dark ? 'light' : 'dark'),
    },
  ]
  const visible = items.filter((it) => match(it.label) || match(it.matchText))
  if (visible.length === 0) return null
  return (
    <CommandGroup heading="快速动作">
      {visible.map((it) => {
        const Icon = it.icon
        return (
          <CommandItem key={it.key} value={it.key} onSelect={it.run} className="cursor-pointer">
            <Icon className="size-4" />
            <span className="text-sm flex-1">{it.label}</span>
            {it.hint && <span className="text-[10px] font-mono text-muted-foreground">{it.hint}</span>}
          </CommandItem>
        )
      })}
    </CommandGroup>
  )
}
