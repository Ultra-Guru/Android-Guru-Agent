'use client'

// 侧栏：工作区选择器 + 对话列表（仅当前工作区） + 新建入口
import { useMemo, useState } from 'react'
import {
  api,
  conversationGroup,
  timeAgo,
  type ConversationSummary,
  type WorkspaceSummary,
} from '@/lib/client'
import { useQueryClient } from '@tanstack/react-query'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog'
import { Input } from '@/components/ui/input'
import { Textarea } from '@/components/ui/textarea'
import { Label } from '@/components/ui/label'
import { ScrollArea } from '@/components/ui/scroll-area'
import { cn } from '@/lib/utils'
import {
  Plus,
  ChevronsUpDown,
  Check,
  MoreHorizontal,
  Trash2,
  Search,
  FolderPlus,
  Settings2,
  MessageSquare,
  Pencil,
} from 'lucide-react'
import { toast } from '@/hooks/use-toast'
import { useApp } from '@/lib/store'
import { Command } from 'lucide-react'

const EMOJI_CHOICES = ['🗂', '🚀', '🧪', '💡', '🧠', '⚡', '🎯', '🔧', '📦', '🌱', '🎨', '📡']

// ═══════════ 新建工作区对话框 ═══════════
export function NewWorkspaceDialog({
  open,
  onOpenChange,
  onCreated,
}: {
  open: boolean
  onOpenChange: (v: boolean) => void
  onCreated: (ws: WorkspaceSummary) => void
}) {
  const [name, setName] = useState('')
  const [emoji, setEmoji] = useState('🗂')
  const [description, setDescription] = useState('')
  const [submitting, setSubmitting] = useState(false)

  const submit = async () => {
    const trimmed = name.trim()
    if (!trimmed) return
    setSubmitting(true)
    try {
      const { workspace } = await api.createWorkspace({ name: trimmed, emoji, description: description.trim() || undefined })
      onOpenChange(false)
      setName(''); setDescription(''); setEmoji('🗂')
      onCreated(workspace)
    } catch (e) {
      toast({ title: '创建失败', description: (e as Error).message, variant: 'destructive' })
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>新建工作区</DialogTitle>
          <DialogDescription>
            新工作区拥有独立的对话集合与项目级规则；创建后将立即切换过去。
          </DialogDescription>
        </DialogHeader>
        <div className="space-y-4 py-1">
          <div className="space-y-2">
            <Label htmlFor="ws-name">名称</Label>
            <Input
              id="ws-name"
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="例如：智能客服项目"
              maxLength={40}
              autoFocus
              onKeyDown={(e) => e.key === 'Enter' && submit()}
            />
          </div>
          <div className="space-y-2">
            <Label>图标</Label>
            <div className="flex flex-wrap gap-1.5">
              {EMOJI_CHOICES.map((e) => (
                <button
                  key={e}
                  type="button"
                  onClick={() => setEmoji(e)}
                  aria-pressed={emoji === e}
                  className={cn(
                    'size-10 rounded-lg text-lg grid place-items-center border transition-colors',
                    emoji === e
                      ? 'border-primary bg-primary/15 text-primary'
                      : 'border-border hover:bg-accent'
                  )}
                >
                  {e}
                </button>
              ))}
            </div>
          </div>
          <div className="space-y-2">
            <Label htmlFor="ws-desc">描述（可选）</Label>
            <Textarea
              id="ws-desc"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              placeholder="这个工作区是做什么的？会作为上下文注入对话。"
              rows={2}
              maxLength={200}
            />
          </div>
        </div>
        <DialogFooter>
          <Button variant="ghost" onClick={() => onOpenChange(false)}>
            取消
          </Button>
          <Button onClick={submit} disabled={!name.trim() || submitting}>
            {submitting ? '创建中…' : '创建并切换'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

// ═══════════ 侧栏 ═══════════
export function ChatSidebar({
  workspace,
  workspaces,
  conversations,
  activeConversationId,
  convLoading,
  onSelectConversation,
  onNewConversation,
  onSwitchWorkspace,
  onNewWorkspace,
  onDeleteConversation,
}: {
  workspace: WorkspaceSummary
  workspaces: WorkspaceSummary[]
  conversations: ConversationSummary[]
  activeConversationId: string | null
  convLoading: boolean
  onSelectConversation: (id: string) => void
  onNewConversation: () => void
  onSwitchWorkspace: (id: string) => void
  onNewWorkspace: () => void
  onDeleteConversation: (id: string) => void
}) {
  const setView = useApp((s) => s.setView)
  const setPaletteOpen = useApp((s) => s.setPaletteOpen)
  const queryClient = useQueryClient()
  const [query, setQuery] = useState('')
  const [deleteTarget, setDeleteTarget] = useState<ConversationSummary | null>(null)
  const [renaming, setRenaming] = useState<ConversationSummary | null>(null)
  const [renameValue, setRenameValue] = useState('')
  const [renamingBusy, setRenamingBusy] = useState(false)

  const doRename = async () => {
    if (!renaming || !renameValue.trim() || renamingBusy) return
    setRenamingBusy(true)
    try {
      await api.updateConversation(renaming.id, { title: renameValue.trim() })
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['conversations'] }),
        queryClient.invalidateQueries({ queryKey: ['conversation', renaming.id] }),
      ])
      setRenaming(null)
      toast({ title: '对话已重命名' })
    } catch (e) {
      toast({ title: '重命名失败', description: (e as Error).message, variant: 'destructive' })
    } finally {
      setRenamingBusy(false)
    }
  }

  const filtered = useMemo(() => {
    if (!query.trim()) return conversations
    const q = query.trim().toLowerCase()
    return conversations.filter((c) => c.title.toLowerCase().includes(q))
  }, [conversations, query])

  const groups = useMemo(() => {
    const map = new Map<string, ConversationSummary[]>()
    for (const c of filtered) {
      const g = conversationGroup(c.updatedAt)
      const list = map.get(g) || []
      list.push(c)
      map.set(g, list)
    }
    return [...map.entries()]
  }, [filtered])

  return (
    <div className="w-full flex flex-col h-full min-h-0">
      {/* 工作区选择器 */}
      <div className="p-3 border-b border-border/70">
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <button
              className="w-full flex items-center gap-2.5 rounded-xl px-3 h-12 text-left hover:bg-accent transition-colors group"
              aria-label={`当前工作区：${workspace.name}，点击切换`}
            >
              <span className="text-xl leading-none">{workspace.emoji}</span>
              <span className="min-w-0 flex-1">
                <span className="block text-sm font-semibold truncate">{workspace.name}</span>
                <span className="block text-[11px] text-muted-foreground font-mono truncate">
                  {conversations.length} 个对话 · 项目规则 {workspace.projectRules ? '已配置' : '未配置'}
                </span>
              </span>
              <ChevronsUpDown className="size-4 text-muted-foreground shrink-0" />
            </button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="start" className="w-72">
            <DropdownMenuLabel className="text-[11px] text-muted-foreground font-mono tracking-widest">
              切换工作区（切换对话集合）
            </DropdownMenuLabel>
            {workspaces.map((w) => (
              <DropdownMenuItem
                key={w.id}
                onClick={() => w.id !== workspace.id && onSwitchWorkspace(w.id)}
                className="gap-2.5 py-2.5"
              >
                <span className="text-lg">{w.emoji}</span>
                <span className="flex-1 min-w-0">
                  <span className="block text-sm font-medium truncate">{w.name}</span>
                  <span className="block text-[11px] text-muted-foreground font-mono">
                    {w.conversationCount} 对话 · {timeAgo(w.lastUsedAt)}
                  </span>
                </span>
                {w.id === workspace.id && <Check className="size-4 text-primary" />}
              </DropdownMenuItem>
            ))}
            <DropdownMenuSeparator />
            <DropdownMenuItem onClick={onNewWorkspace} className="gap-2 py-2.5 text-primary">
              <FolderPlus className="size-4" /> 新建工作区
            </DropdownMenuItem>
            <DropdownMenuItem onClick={() => setView('settings')} className="gap-2 py-2.5">
              <Settings2 className="size-4" /> 管理工作区与规则
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      </div>

      {/* 新对话 + 搜索 */}
      <div className="p-3 space-y-2.5 border-b border-border/70">
        <Button onClick={onNewConversation} className="w-full justify-start gap-2 h-11 font-semibold" size="default">
          <Plus className="size-4" /> 新对话
          <span className="ml-auto text-[10px] font-mono font-normal opacity-70">落在当前工作区</span>
        </Button>
        <div className="relative">
          <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 size-3.5 text-muted-foreground" />
          <Input
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="搜索对话…"
            className="h-9 pl-8 text-[13px] bg-background/60"
            aria-label="搜索当前工作区的对话"
          />
        </div>
        {/* 深度搜索入口：跨工作区、命中消息内容 */}
        <button
          onClick={() => setPaletteOpen(true)}
          className="w-full flex items-center gap-1.5 text-[10px] font-mono text-muted-foreground/70 hover:text-primary transition-colors px-1"
          aria-label="打开全局深度搜索（快捷键 Command K）"
        >
          <Command className="size-3" />
          <span>K 全局深度搜索 · 跨工作区 · 含消息内容</span>
        </button>
      </div>

      {/* 对话列表：max-h + 滚动 */}
      <ScrollArea className="flex-1 min-h-0">
        <div className="px-2 py-3 max-h-[calc(100dvh-15rem)]">
          {convLoading ? (
            <p className="text-center text-xs text-muted-foreground py-8 font-mono">加载中…</p>
          ) : filtered.length === 0 ? (
            <div className="text-center py-10 px-4">
              <MessageSquare className="size-8 mx-auto text-muted-foreground/50 mb-3" />
              <p className="text-sm text-muted-foreground">
                {query ? '没有匹配的对话' : '这个工作区还没有对话'}
              </p>
              {!query && (
                <Button variant="outline" size="sm" className="mt-3" onClick={onNewConversation}>
                  <Plus className="size-3.5" /> 开启第一个对话
                </Button>
              )}
            </div>
          ) : (
            groups.map(([group, items]) => (
              <section key={group} className="mb-3">
                <h3 className="px-2 mb-1 text-[10px] font-mono tracking-[0.2em] text-muted-foreground/80">
                  {group.toUpperCase()}
                </h3>
                {items.map((c) => {
                  const active = c.id === activeConversationId
                  return (
                    <div
                      key={c.id}
                      className={cn(
                        'group relative rounded-lg mx-1 flex items-center gap-2 pr-1 transition-colors',
                        active ? 'bg-accent' : 'hover:bg-accent/60'
                      )}
                    >
                      <button
                        onClick={() => onSelectConversation(c.id)}
                        className="flex-1 min-w-0 text-left px-2.5 py-2.5"
                        aria-current={active ? 'true' : undefined}
                      >
                        <span className={cn('block text-[13px] truncate', active ? 'font-semibold' : '')}>
                          {c.title}
                        </span>
                        <span className="block text-[11px] text-muted-foreground font-mono">
                          {c.mode === 'coding' ? '⌨ 编码' : '◎ 智能体'} · {c.messageCount} 条 · {timeAgo(c.updatedAt)}
                        </span>
                      </button>
                      <DropdownMenu>
                        <DropdownMenuTrigger asChild>
                          <button
                            className="size-8 grid place-items-center rounded-md text-muted-foreground opacity-0 group-hover:opacity-100 data-[state=open]:opacity-100 hover:bg-accent"
                            aria-label={`对话操作：${c.title}`}
                          >
                            <MoreHorizontal className="size-4" />
                          </button>
                        </DropdownMenuTrigger>
                        <DropdownMenuContent align="end" className="w-44">
                          <DropdownMenuItem
                            onClick={() => {
                              setRenaming(c)
                              setRenameValue(c.title)
                            }}
                            className="gap-2"
                          >
                            <Pencil className="size-3.5" /> 重命名
                          </DropdownMenuItem>
                          <DropdownMenuItem onClick={() => setDeleteTarget(c)} className="gap-2 text-destructive focus:text-destructive">
                            <Trash2 className="size-3.5" /> 删除对话
                          </DropdownMenuItem>
                        </DropdownMenuContent>
                      </DropdownMenu>
                      {active && <span className="absolute left-0 top-2 bottom-2 w-0.5 rounded bg-primary" />}
                    </div>
                  )
                })}
              </section>
            ))
          )}
        </div>
      </ScrollArea>

      {/* 底部：新建工作区 */}
      <div className="p-3 border-t border-border/70">
        <Button variant="outline" onClick={onNewWorkspace} className="w-full h-10 gap-2 text-[13px]">
          <FolderPlus className="size-4" /> 新建工作区
        </Button>
        <p className="mt-2 text-[10px] text-muted-foreground/80 font-mono leading-relaxed px-1">
          新对话归属当前工作区；切换工作区即切换对话集合。
        </p>
      </div>

      <AlertDialog open={!!deleteTarget} onOpenChange={(v) => !v && setDeleteTarget(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>删除这个对话？</AlertDialogTitle>
            <AlertDialogDescription>
              「{deleteTarget?.title}」及其全部消息将被永久删除，无法恢复。
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>取消</AlertDialogCancel>
            <AlertDialogAction
              className="bg-destructive text-destructive-foreground hover:bg-destructive/90"
              onClick={() => deleteTarget && onDeleteConversation(deleteTarget.id)}
            >
              删除
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      <Dialog open={!!renaming} onOpenChange={(v) => !v && setRenaming(null)}>
        <DialogContent className="sm:max-w-sm">
          <DialogHeader>
            <DialogTitle>重命名对话</DialogTitle>
            <DialogDescription>修改标题后保存，立即生效。</DialogDescription>
          </DialogHeader>
          <div className="space-y-2 py-1">
            <Label htmlFor="rename-conv">标题</Label>
            <Input
              id="rename-conv"
              value={renameValue}
              onChange={(e) => setRenameValue(e.target.value)}
              maxLength={80}
              autoFocus
              onKeyDown={(e) => e.key === 'Enter' && doRename()}
            />
          </div>
          <DialogFooter>
            <Button variant="ghost" onClick={() => setRenaming(null)} disabled={renamingBusy}>
              取消
            </Button>
            <Button onClick={doRename} disabled={!renameValue.trim() || renamingBusy}>
              {renamingBusy ? '保存中…' : '保存'}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}
