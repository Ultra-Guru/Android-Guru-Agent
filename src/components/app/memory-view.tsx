'use client'

// 记忆页：两个 Tab —— 对话记忆（cs-mem 账本） + 用户画像（独立页面，Coding 模式专用）
import { useMemo, useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import {
  api,
  MEMORY_CATEGORIES,
  PROFILE_ASPECTS,
  timeAgo,
  type MemoryEntry,
  type ProfileEntry,
} from '@/lib/client'
import { Button } from '@/components/ui/button'
import { Textarea } from '@/components/ui/textarea'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Slider } from '@/components/ui/slider'
import { Badge } from '@/components/ui/badge'
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
import { cn } from '@/lib/utils'
import { toast } from '@/hooks/use-toast'
import {
  Plus,
  Trash2,
  Brain,
  Sparkles,
  Loader2,
  Wand2,
  MessageSquareQuote,
  Info,
  Pencil,
  Layers,
} from 'lucide-react'

// ═══════════ 对话记忆 Tab ═══════════
function MemoriesTab() {
  const queryClient = useQueryClient()
  const { data, isLoading } = useQuery({ queryKey: ['memory'], queryFn: api.listMemories })
  const memories = data?.memories ?? []
  const [filter, setFilter] = useState<string>('all')
  const [query, setQuery] = useState('')
  const [addOpen, setAddOpen] = useState(false)
  const [clearOpen, setClearOpen] = useState(false)

  const filtered = useMemo(() => {
    let list = memories
    if (filter !== 'all') list = list.filter((m) => m.category === filter)
    if (query.trim()) {
      const q = query.trim().toLowerCase()
      list = list.filter((m) => m.content.toLowerCase().includes(q))
    }
    return list
  }, [memories, filter, query])

  const autoCount = memories.filter((m) => m.source === 'auto').length

  const deleteMem = useMutation({
    mutationFn: (id: string) => api.deleteMemory(id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['memory'] }),
  })
  const clearMems = useMutation({
    mutationFn: () => api.clearMemories(),
    onSuccess: (r) => {
      queryClient.invalidateQueries({ queryKey: ['memory'] })
      toast({ title: `已清空 ${r.deleted} 条记忆` })
    },
    onError: (e) => toast({ title: '清空失败', description: (e as Error).message, variant: 'destructive' }),
  })

  return (
    <div className="max-w-4xl mx-auto w-full px-4 sm:px-6 py-6">
      {/* 统计 + 说明 */}
      <div className="flex flex-wrap items-center gap-2 mb-4">
        <Badge variant="outline" className="gap-1.5 font-mono font-normal">
          <Layers className="size-3" /> 共 {memories.length} 条
        </Badge>
        <Badge variant="outline" className="gap-1.5 font-mono font-normal">
          <Sparkles className="size-3" /> 自动沉淀 {autoCount}
        </Badge>
        <div className="ml-auto flex gap-2">
          <Button size="sm" variant="ghost" className="text-destructive hover:text-destructive" onClick={() => setClearOpen(true)} disabled={!memories.length}>
            <Trash2 className="size-3.5" /> 清空
          </Button>
          <Button size="sm" onClick={() => setAddOpen(true)}>
            <Plus className="size-3.5" /> 手动添加
          </Button>
        </div>
      </div>

      <div className="rounded-xl border border-border bg-card p-4 mb-4 flex gap-3">
        <Info className="size-4 text-primary shrink-0 mt-0.5" />
        <p className="text-xs text-muted-foreground leading-relaxed">
          记忆按 <span className="font-mono text-foreground">重要性 × 时间 × 频率</span> 综合评分召回注入对话
          （时间半衰 14 天）。对话结束后系统会自动提炼（带写入门禁：拒绝一次性细节与常识）。
        </p>
      </div>

      {/* 过滤 + 搜索 */}
      <div className="flex flex-wrap items-center gap-2 mb-4">
        <div className="flex gap-1 flex-wrap">
          {(['all', 'fact', 'state', 'milestone', 'preference'] as const).map((k) => (
            <button
              key={k}
              onClick={() => setFilter(k)}
              aria-pressed={filter === k}
              className={cn(
                'h-8 px-3 rounded-lg text-xs font-medium transition-colors border',
                filter === k
                  ? 'border-primary bg-primary/15 text-primary'
                  : 'border-border text-muted-foreground hover:text-foreground'
              )}
            >
              {k === 'all' ? `全部 ${memories.length}` : MEMORY_CATEGORIES[k].label}
            </button>
          ))}
        </div>
        <Input
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="搜索记忆…"
          className="h-8 w-48 sm:w-64 text-xs bg-background/60 ml-auto"
          aria-label="搜索记忆"
        />
      </div>

      {/* 列表 */}
      {isLoading ? (
        <p className="text-center text-xs text-muted-foreground font-mono py-10">加载记忆…</p>
      ) : filtered.length === 0 ? (
        <div className="text-center py-16">
          <Brain className="size-10 mx-auto text-muted-foreground/40 mb-4" />
          <p className="text-sm text-muted-foreground">{query || filter !== 'all' ? '没有匹配的记忆' : '还没有记忆'}</p>
          {!query && filter === 'all' && (
            <p className="text-xs text-muted-foreground/70 mt-2">去对话页聊几轮，记忆会自动沉淀；也可以手动添加。</p>
          )}
        </div>
      ) : (
        <ul className="space-y-2.5 max-h-[calc(100dvh-26rem)] overflow-y-auto pr-1">
          {filtered.map((m) => (
            <li
              key={m.id}
              className="group rounded-xl border border-border bg-card px-4 py-3.5 hover:border-primary/40 transition-colors"
            >
              <div className="flex items-center gap-2 mb-1.5">
                <Badge variant="secondary" className="font-mono text-[10px]">
                  {MEMORY_CATEGORIES[m.category]?.label ?? m.category}
                </Badge>
                {m.source === 'auto' && (
                  <Badge variant="outline" className="font-mono text-[10px] gap-1 text-primary border-primary/40">
                    <Sparkles className="size-2.5" /> 自动
                  </Badge>
                )}
                <span className="ml-auto text-[10px] font-mono text-muted-foreground/70">{timeAgo(m.createdAt)}</span>
                <button
                  onClick={() => deleteMem.mutate(m.id)}
                  className="opacity-0 group-hover:opacity-100 transition-opacity text-muted-foreground hover:text-destructive"
                  aria-label={`删除记忆：${m.content.slice(0, 12)}`}
                >
                  <Trash2 className="size-3.5" />
                </button>
              </div>
              <p className="text-sm leading-relaxed">{m.content}</p>
              <div className="mt-2 flex items-center gap-2">
                <div className="h-1 flex-1 max-w-40 rounded bg-muted overflow-hidden">
                  <div
                    className="h-full rounded bg-primary/80"
                    style={{ width: `${Math.round(m.importance * 100)}%` }}
                  />
                </div>
                <span className="text-[10px] font-mono text-muted-foreground">
                  重要度 {(m.importance * 100) | 0}% · 召回 {m.accessCount} 次
                </span>
              </div>
            </li>
          ))}
        </ul>
      )}

      <AddMemoryDialog
        open={addOpen}
        onOpenChange={setAddOpen}
        onAdded={() => queryClient.invalidateQueries({ queryKey: ['memory'] })}
      />
      <AlertDialog open={clearOpen} onOpenChange={setClearOpen}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>清空全部对话记忆？</AlertDialogTitle>
            <AlertDialogDescription>
              共 {memories.length} 条记忆将被删除（不影响用户画像），无法恢复。
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>取消</AlertDialogCancel>
            <AlertDialogAction
              className="bg-destructive text-destructive-foreground hover:bg-destructive/90"
              onClick={() => clearMems.mutate()}
            >
              清空
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  )
}

function AddMemoryDialog({
  open,
  onOpenChange,
  onAdded,
}: {
  open: boolean
  onOpenChange: (v: boolean) => void
  onAdded: () => void
}) {
  const [content, setContent] = useState('')
  const [category, setCategory] = useState('fact')
  const [importance, setImportance] = useState(0.6)
  const [saving, setSaving] = useState(false)

  const submit = async () => {
    if (!content.trim()) return
    setSaving(true)
    try {
      await api.addMemory({ content: content.trim(), category, importance })
      onOpenChange(false)
      setContent('')
      onAdded()
      toast({ title: '已添加记忆' })
    } catch (e) {
      toast({ title: '添加失败', description: (e as Error).message, variant: 'destructive' })
    } finally {
      setSaving(false)
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>添加记忆</DialogTitle>
          <DialogDescription>手动记忆固定 source=manual，同样参与评分召回。</DialogDescription>
        </DialogHeader>
        <div className="space-y-4 py-1">
          <div className="space-y-2">
            <Label htmlFor="mem-content">内容</Label>
            <Textarea
              id="mem-content"
              value={content}
              onChange={(e) => setContent(e.target.value)}
              rows={3}
              maxLength={300}
              placeholder="例如：用户在筹备 11 月的发布会"
              autoFocus
            />
          </div>
          <div className="space-y-2">
            <Label>类别</Label>
            <div className="flex gap-1.5 flex-wrap">
              {Object.entries(MEMORY_CATEGORIES).map(([k, v]) => (
                <button
                  key={k}
                  onClick={() => setCategory(k)}
                  aria-pressed={category === k}
                  className={cn(
                    'h-9 px-3 rounded-lg text-xs font-medium border transition-colors',
                    category === k ? 'border-primary bg-primary/15 text-primary' : 'border-border text-muted-foreground'
                  )}
                >
                  {v.label}
                </button>
              ))}
            </div>
          </div>
          <div className="space-y-2">
            <Label>
              重要度 <span className="font-mono text-primary">{(importance * 100) | 0}%</span>
            </Label>
            <Slider
              value={[importance * 100]}
              min={30}
              max={100}
              step={5}
              onValueChange={(v) => setImportance((v[0] ?? 60) / 100)}
              aria-label="记忆重要度"
            />
          </div>
        </div>
        <DialogFooter>
          <Button variant="ghost" onClick={() => onOpenChange(false)}>
            取消
          </Button>
          <Button onClick={submit} disabled={!content.trim() || saving}>
            {saving ? <Loader2 className="size-4 animate-spin" /> : '添加'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

// ═══════════ 用户画像 Tab（独立页面） ═══════════
function ProfileTab() {
  const queryClient = useQueryClient()
  const { data, isLoading } = useQuery({ queryKey: ['profile'], queryFn: api.listProfile })
  const profile = data?.profile ?? []
  const stats = data?.stats
  const [editing, setEditing] = useState<ProfileEntry | null>(null)
  const [addingAspect, setAddingAspect] = useState<string | null>(null)
  const [distilling, setDistilling] = useState(false)

  const byAspect = useMemo(() => {
    const map = new Map<string, ProfileEntry[]>()
    for (const p of profile) {
      const list = map.get(p.aspect) || []
      list.push(p)
      map.set(p.aspect, list)
    }
    return map
  }, [profile])

  const distill = async () => {
    setDistilling(true)
    try {
      const r = await api.distillProfile()
      if (r.ok) {
        toast({ title: '提炼完成', description: r.note })
        queryClient.invalidateQueries({ queryKey: ['profile'] })
      } else {
        toast({ title: '提炼失败', description: r.error || '请稍后再试', variant: 'destructive' })
      }
    } catch (e) {
      toast({ title: '提炼失败', description: (e as Error).message, variant: 'destructive' })
    } finally {
      setDistilling(false)
    }
  }

  const deleteProfile = useMutation({
    mutationFn: (id: string) => api.deleteProfile(id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['profile'] }),
  })

  if (isLoading) {
    return <p className="text-center text-xs text-muted-foreground font-mono py-10">加载画像…</p>
  }

  return (
    <div className="max-w-5xl mx-auto w-full px-4 sm:px-6 py-6">
      {/* 头部：统计 + 提炼按钮 */}
      <div className="rounded-2xl border border-border bg-card p-5 mb-5">
        <div className="flex flex-wrap items-start gap-4">
          <div className="size-12 rounded-xl bg-primary/15 border border-primary/30 grid place-items-center shrink-0">
            <Brain className="size-6 text-primary" />
          </div>
          <div className="min-w-0 flex-1">
            <h3 className="font-bold text-lg leading-tight">用户画像</h3>
            <p className="text-xs text-muted-foreground mt-1 leading-relaxed">
              Coding 模式的专属上下文：按置信度注入编码对话的系统提示词，让回答默认贴合你的技术栈与习惯。
            </p>
            <div className="mt-3 flex flex-wrap gap-2">
              <Badge variant="outline" className="font-mono font-normal gap-1.5 text-[11px]">
                <Layers className="size-3" /> {stats?.total ?? 0} 条
              </Badge>
              <Badge variant="outline" className="font-mono font-normal gap-1.5 text-[11px]">
                覆盖 {stats?.covered ?? 0}/6 维度
              </Badge>
              <Badge variant="outline" className="font-mono font-normal gap-1.5 text-[11px]">
                <Sparkles className="size-3" /> 提炼 {stats?.derived ?? 0} 条
                {stats?.lastDerivedAt ? ` · ${timeAgo(stats.lastDerivedAt)}` : ''}
              </Badge>
            </div>
          </div>
          <Button onClick={distill} disabled={distilling} className="gap-2 shadow-[0_0_20px_hsl(152_84%_60%/0.25)]">
            {distilling ? <Loader2 className="size-4 animate-spin" /> : <Wand2 className="size-4" />}
            {distilling ? '提炼中（约 10~30 秒）…' : '从编码对话提炼'}
          </Button>
        </div>
      </div>

      {/* 说明卡 */}
      <div className="rounded-xl border border-border bg-card p-4 mb-5 flex gap-3">
        <Info className="size-4 text-primary shrink-0 mt-0.5" />
        <div className="text-xs text-muted-foreground leading-relaxed space-y-1">
          <p>
            <span className="text-foreground font-medium">来源一（自动）：</span>
            编码模式对话中，系统按写入门禁自动提炼（拒绝一次性任务细节 / 常识 / 未来计划）；
            同主题演进走更新而非重复新增。
          </p>
          <p>
            <span className="text-foreground font-medium">来源二（手动）：</span>
            点击各维度卡片的「添加」直接录入。两种来源都会参与注入（按置信度取顶部条目）。
          </p>
        </div>
      </div>

      {/* 维度卡片网格 */}
      <div className="grid gap-4 sm:grid-cols-2">
        {PROFILE_ASPECTS.map((a) => {
          const items = byAspect.get(a.key) ?? []
          return (
            <section
              key={a.key}
              aria-label={`${a.label}画像`}
              className="rounded-2xl border border-border bg-card p-4 flex flex-col min-h-44"
            >
              <div className="flex items-center gap-2.5 mb-1">
                <span className="text-xl" aria-hidden>{a.icon}</span>
                <div className="min-w-0 flex-1">
                  <h4 className="font-semibold text-sm">{a.label}</h4>
                  <p className="text-[11px] text-muted-foreground">{a.hint}</p>
                </div>
                <span className="text-[10px] font-mono text-muted-foreground/80">{items.length} 条</span>
              </div>

              <div className="flex-1 mt-2 space-y-2">
                {items.length === 0 ? (
                  <p className="text-xs text-muted-foreground/60 py-3 text-center">暂无条目</p>
                ) : (
                  <ul className="space-y-2">
                    {items.map((p) => (
                      <li key={p.id} className="group rounded-xl bg-muted/50 border border-border px-3 py-2.5">
                        <div className="flex items-start gap-2">
                          <p className="flex-1 text-[13px] leading-relaxed">{p.content}</p>
                          <div className="flex gap-1 opacity-0 group-hover:opacity-100 transition-opacity shrink-0">
                            <button
                              onClick={() => setEditing(p)}
                              className="text-muted-foreground hover:text-primary"
                              aria-label="编辑画像条目"
                            >
                              <Pencil className="size-3.5" />
                            </button>
                            <button
                              onClick={() => deleteProfile.mutate(p.id)}
                              className="text-muted-foreground hover:text-destructive"
                              aria-label="删除画像条目"
                            >
                              <Trash2 className="size-3.5" />
                            </button>
                          </div>
                        </div>
                        <div className="mt-2 flex items-center gap-2 flex-wrap">
                          <div className="h-1 w-24 rounded bg-muted overflow-hidden">
                            <div className="h-full bg-primary/80" style={{ width: `${(p.confidence * 100) | 0}%` }} />
                          </div>
                          <span className="text-[10px] font-mono text-muted-foreground">
                            置信 {(p.confidence * 100) | 0}%
                          </span>
                          {p.source === 'derived' ? (
                            <Badge variant="outline" className="text-[9px] font-mono gap-1 text-primary border-primary/40 py-0">
                              <Sparkles className="size-2.5" /> 提炼{p.evidence ? ` · ${p.evidence}` : ''}
                            </Badge>
                          ) : (
                            <Badge variant="secondary" className="text-[9px] font-mono py-0">手动</Badge>
                          )}
                        </div>
                      </li>
                    ))}
                  </ul>
                )}
              </div>

              <Button
                variant="outline"
                size="sm"
                className="mt-3 w-full h-9 text-xs"
                onClick={() => setAddingAspect(a.key)}
              >
                <Plus className="size-3.5" /> 添加{a.label}条目
              </Button>
            </section>
          )
        })}
      </div>

      <EditProfileDialog
        key={editing ? `edit-${editing.id}` : 'edit-none'}
        entry={editing}
        onOpenChange={(v) => !v && setEditing(null)}
        onSaved={() => queryClient.invalidateQueries({ queryKey: ['profile'] })}
      />
      <AddProfileDialog
        key={addingAspect ? `add-${addingAspect}` : 'add-none'}
        aspect={addingAspect}
        onOpenChange={(v) => !v && setAddingAspect(null)}
        onAdded={() => queryClient.invalidateQueries({ queryKey: ['profile'] })}
      />
    </div>
  )
}

function AddProfileDialog({
  aspect,
  onOpenChange,
  onAdded,
}: {
  aspect: string | null
  onOpenChange: (v: boolean) => void
  onAdded: () => void
}) {
  const [content, setContent] = useState('')
  const [confidence, setConfidence] = useState(0.7)
  const [saving, setSaving] = useState(false)
  const aspectMeta = PROFILE_ASPECTS.find((a) => a.key === aspect)

  const submit = async () => {
    if (!aspect || !content.trim()) return
    setSaving(true)
    try {
      await api.addProfile({ content: content.trim(), aspect, confidence })
      onOpenChange(false)
      setContent('')
      onAdded()
      toast({ title: `已添加到「${aspectMeta?.label}」` })
    } catch (e) {
      toast({ title: '添加失败', description: (e as Error).message, variant: 'destructive' })
    } finally {
      setSaving(false)
    }
  }

  return (
    <Dialog open={!!aspect} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>
            添加{aspectMeta?.label ?? ''}条目 <span aria-hidden>{aspectMeta?.icon}</span>
          </DialogTitle>
          <DialogDescription>{aspectMeta?.hint}</DialogDescription>
        </DialogHeader>
        <div className="space-y-4 py-1">
          <div className="space-y-2">
            <Label htmlFor="prof-content">内容（≤200 字）</Label>
            <Textarea
              id="prof-content"
              value={content}
              onChange={(e) => setContent(e.target.value)}
              rows={3}
              maxLength={200}
              placeholder="例如：主力 TypeScript + React，测试用 Vitest"
              autoFocus
            />
          </div>
          <div className="space-y-2">
            <Label>
              置信度 <span className="font-mono text-primary">{(confidence * 100) | 0}%</span>
            </Label>
            <Slider
              value={[confidence * 100]}
              min={30}
              max={100}
              step={5}
              onValueChange={(v) => setConfidence((v[0] ?? 70) / 100)}
              aria-label="置信度"
            />
          </div>
        </div>
        <DialogFooter>
          <Button variant="ghost" onClick={() => onOpenChange(false)}>
            取消
          </Button>
          <Button onClick={submit} disabled={!content.trim() || saving}>
            {saving ? <Loader2 className="size-4 animate-spin" /> : '添加'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}


function EditProfileDialog({
  entry,
  onOpenChange,
  onSaved,
}: {
  entry: ProfileEntry | null
  onOpenChange: (v: boolean) => void
  onSaved: () => void
}) {
  const [content, setContent] = useState(() => entry?.content ?? '')
  const [confidence, setConfidence] = useState(() => entry?.confidence ?? 0.7)
  const [saving, setSaving] = useState(false)

  const submit = async () => {
    if (!entry || !content.trim()) return
    setSaving(true)
    try {
      await api.updateProfile(entry.id, { content: content.trim(), confidence })
      onOpenChange(false)
      onSaved()
      toast({ title: '已更新画像条目' })
    } catch (e) {
      toast({ title: '更新失败', description: (e as Error).message, variant: 'destructive' })
    } finally {
      setSaving(false)
    }
  }

  const aspectMeta = PROFILE_ASPECTS.find((a) => a.key === entry?.aspect)

  return (
    <Dialog open={!!entry} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>
            编辑{aspectMeta?.label ?? ''}条目 <span aria-hidden>{aspectMeta?.icon}</span>
          </DialogTitle>
          <DialogDescription>调整内容与置信度，保存后参与编码模式注入。</DialogDescription>
        </DialogHeader>
        <div className="space-y-4 py-1">
          <div className="space-y-2">
            <Label htmlFor="edit-prof-content">内容</Label>
            <Textarea
              id="edit-prof-content"
              value={content}
              onChange={(e) => setContent(e.target.value)}
              rows={3}
              maxLength={200}
              autoFocus
            />
          </div>
          <div className="space-y-2">
            <Label>
              置信度 <span className="font-mono text-primary">{(confidence * 100) | 0}%</span>
            </Label>
            <Slider
              value={[confidence * 100]}
              min={30}
              max={100}
              step={5}
              onValueChange={(v) => setConfidence((v[0] ?? 70) / 100)}
              aria-label="置信度"
            />
          </div>
        </div>
        <DialogFooter>
          <Button variant="ghost" onClick={() => onOpenChange(false)}>
            取消
          </Button>
          <Button onClick={submit} disabled={!content.trim() || saving}>
            {saving ? <Loader2 className="size-4 animate-spin" /> : '保存'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

// ═══════════ 视图 ═══════════
export function MemoryView() {
  const [tab, setTab] = useState<'memories' | 'profile'>('memories')
  return (
    <div className="flex-1 min-w-0 flex flex-col overflow-hidden">
      <div className="shrink-0 border-b border-border bg-card/50 px-4 sm:px-6 h-14 flex items-center gap-4">
        <h1 className="font-bold text-sm flex items-center gap-2">
          <MessageSquareQuote className="size-4 text-primary" /> 记忆
        </h1>
        <div role="tablist" aria-label="记忆类型" className="flex p-1 rounded-xl bg-muted gap-1">
          <button
            role="tab"
            aria-selected={tab === 'memories'}
            onClick={() => setTab('memories')}
            className={cn(
              'h-8 px-3.5 rounded-lg text-xs font-semibold transition-all',
              tab === 'memories' ? 'bg-primary text-primary-foreground shadow' : 'text-muted-foreground hover:text-foreground'
            )}
          >
            对话记忆
          </button>
          <button
            role="tab"
            aria-selected={tab === 'profile'}
            onClick={() => setTab('profile')}
            className={cn(
              'h-8 px-3.5 rounded-lg text-xs font-semibold transition-all',
              tab === 'profile' ? 'bg-primary text-primary-foreground shadow' : 'text-muted-foreground hover:text-foreground'
            )}
          >
            用户画像
          </button>
        </div>
        <span className="ml-auto hidden sm:inline text-[11px] font-mono text-muted-foreground">
          {tab === 'memories' ? '重要性 × 时间 × 频率 · 评分召回' : 'Coding 模式专属 · 独立页面'}
        </span>
      </div>
      <div className="flex-1 min-h-0 overflow-y-auto">
        {tab === 'memories' ? <MemoriesTab /> : <ProfileTab />}
      </div>
    </div>
  )
}
