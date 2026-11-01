'use client'

// 游乐场：作品画廊 + 生成对话框（严格 HTML 规范）+ 沙箱预览
// 弃用 pretext 测量引擎 —— 生成链路带硬性契约校验 + 一次带反馈重试
import { useEffect, useMemo, useRef, useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import {
  api,
  PLAYGROUND_CATEGORIES,
  PLAYGROUND_THEMES,
  timeAgo,
  type PlaygroundAppSummary,
} from '@/lib/client'
import { Button } from '@/components/ui/button'
import { Textarea } from '@/components/ui/textarea'
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
  Gamepad2,
  Loader2,
  Play,
  RotateCw,
  Trash2,
  Download,
  Maximize2,
  X,
  RefreshCw,
  ExternalLink,
  Wand2,
  Info,
  FileCode2,
} from 'lucide-react'

type Category = keyof typeof PLAYGROUND_CATEGORIES

// ═══════════ 生成对话框 ═══════════
function GenerateDialog({
  open,
  onOpenChange,
  onCreated,
  regenerateOf,
}: {
  open: boolean
  onOpenChange: (v: boolean) => void
  onCreated: (app: PlaygroundAppSummary, replaced: boolean) => void
  regenerateOf: PlaygroundAppSummary | null
}) {
  const [prompt, setPrompt] = useState('')
  const [category, setCategory] = useState<Category>('game')
  const [theme, setTheme] = useState('neon')
  const [busy, setBusy] = useState(false)
  const [phase, setPhase] = useState<'idle' | 'generating' | 'validating'>('idle')

  useEffect(() => {
    if (open && regenerateOf) {
      setPrompt(regenerateOf.prompt || '')
      setCategory((regenerateOf.category in PLAYGROUND_CATEGORIES ? regenerateOf.category : 'game') as Category)
    } else if (open) {
      setPrompt('')
    }
  }, [open, regenerateOf])

  const submit = async () => {
    if (!prompt.trim() || busy) return
    setBusy(true)
    setPhase('generating')
    try {
      const r = await api.generatePlayground({
        prompt: prompt.trim(),
        category,
        theme,
        ...(regenerateOf ? { regenerateOf: regenerateOf.id, title: regenerateOf.title } : {}),
      })
      if (r.ok) {
        onOpenChange(false)
        onCreated(r.app, r.replaced)
      } else {
        toast({
          title: '生成未通过质量校验',
          description: (r.details || []).slice(0, 3).join('；') || r.error,
          variant: 'destructive',
        })
      }
    } catch (e) {
      toast({ title: '生成失败', description: (e as Error).message, variant: 'destructive' })
    } finally {
      setBusy(false)
      setPhase('idle')
    }
  }

  return (
    <Dialog open={open} onOpenChange={(v) => { if (!busy) onOpenChange(v) }}>
      <DialogContent className="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <Wand2 className="size-4 text-primary" />
            {regenerateOf ? `重新生成「${regenerateOf.title}」` : '新建作品'}
          </DialogTitle>
          <DialogDescription>
            产出一个自包含单文件 HTML：零网络请求、移动触屏优先、终端暗色霓虹设计语言，
            生成后自动做硬性契约校验（外链 / 截断 / 体积），失败会带反馈重试一次。
          </DialogDescription>
        </DialogHeader>

        <div className="space-y-4 py-1">
          <div className="space-y-2">
            <label htmlFor="pg-prompt" className="text-sm font-medium">
              想要什么？<span className="text-muted-foreground font-normal">（描述玩法 / 功能 / 数据）</span>
            </label>
            <Textarea
              id="pg-prompt"
              value={prompt}
              onChange={(e) => setPrompt(e.target.value)}
              rows={4}
              maxLength={2000}
              placeholder={'例如：一个 2048，带撤销、最佳分纪录、结算统计；支持滑动操作'}
              disabled={busy}
              autoFocus
            />
          </div>

          <div className="space-y-2">
            <span className="text-sm font-medium">类型</span>
            <div className="flex flex-wrap gap-1.5">
              {(Object.keys(PLAYGROUND_CATEGORIES) as Category[]).map((c) => (
                <button
                  key={c}
                  onClick={() => setCategory(c)}
                  disabled={busy}
                  aria-pressed={category === c}
                  className={cn(
                    'h-10 px-3.5 rounded-xl text-xs font-semibold border transition-all',
                    category === c
                      ? 'border-primary bg-primary/15 text-primary shadow-[0_0_12px_hsl(152_84%_60%/0.2)]'
                      : 'border-border text-muted-foreground hover:text-foreground'
                  )}
                >
                  <span className="mr-1.5 font-mono" aria-hidden>{PLAYGROUND_CATEGORIES[c].icon}</span>
                  {PLAYGROUND_CATEGORIES[c].label}
                </button>
              ))}
            </div>
          </div>

          <div className="space-y-2">
            <span className="text-sm font-medium">主题</span>
            <div className="flex flex-wrap gap-1.5">
              {PLAYGROUND_THEMES.map((t) => (
                <button
                  key={t.key}
                  onClick={() => setTheme(t.key)}
                  disabled={busy}
                  aria-pressed={theme === t.key}
                  className={cn(
                    'flex items-center gap-2 h-10 pl-2 pr-3.5 rounded-xl text-xs font-semibold border transition-all',
                    theme === t.key
                      ? 'border-primary bg-primary/15 text-primary'
                      : 'border-border text-muted-foreground hover:text-foreground'
                  )}
                >
                  <span className="size-5 rounded-md" style={{ background: t.swatch }} aria-hidden />
                  {t.label}
                </button>
              ))}
            </div>
          </div>

          {busy && (
            <div className="rounded-xl border border-primary/30 bg-primary/5 px-4 py-3 flex items-center gap-3">
              <Loader2 className="size-4 animate-spin text-primary" />
              <div className="text-xs leading-relaxed">
                <p className="font-semibold text-primary">
                  {phase === 'generating' ? '生成中…' : '校验中…'}
                </p>
                <p className="text-muted-foreground">完整作品通常 30 ~ 90 秒，请保持页面打开。</p>
              </div>
            </div>
          )}
        </div>

        <DialogFooter>
          <Button variant="ghost" onClick={() => onOpenChange(false)} disabled={busy}>
            取消
          </Button>
          <Button onClick={submit} disabled={!prompt.trim() || busy} className="gap-2">
            {busy ? <Loader2 className="size-4 animate-spin" /> : <Wand2 className="size-4" />}
            {busy ? '生成中…' : regenerateOf ? '重新生成（版本 +1）' : '开始生成'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

// ═══════════ 沙箱预览 ═══════════
function PreviewOverlay({
  app,
  onClose,
  onRegenerate,
  onDeleted,
}: {
  app: PlaygroundAppSummary | null
  onClose: () => void
  onRegenerate: (app: PlaygroundAppSummary) => void
  onDeleted: (id: string) => void
}) {
  const { data, isLoading } = useQuery({
    queryKey: ['playground-app', app?.id],
    queryFn: () => api.getPlaygroundApp(app!.id),
    enabled: !!app,
    staleTime: 60_000,
  })
  const iframeRef = useRef<HTMLIFrameElement>(null)
  const shellRef = useRef<HTMLDivElement>(null)
  const [reloadKey, setReloadKey] = useState(0)
  const [confirmDelete, setConfirmDelete] = useState(false)

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  if (!app) return null
  const html = data?.app?.html

  const download = () => {
    if (!html) return
    const blob = new Blob([html], { type: 'text/html;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `${app.title.replace(/[\\/:*?"<>|]/g, '_')}.html`
    a.click()
    URL.revokeObjectURL(url)
    toast({ title: '已下载 HTML 文件', description: '双击即可在浏览器直接运行。' })
  }

  const openTab = () => {
    if (!html) return
    const blob = new Blob([html], { type: 'text/html;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    window.open(url, '_blank')
    setTimeout(() => URL.revokeObjectURL(url), 30_000)
  }

  return (
    <div
      ref={shellRef}
      className="fixed inset-0 z-50 bg-background/95 backdrop-blur-sm flex flex-col"
      role="dialog"
      aria-modal="true"
      aria-label={`预览：${app.title}`}
    >
      <header className="h-14 shrink-0 border-b border-border bg-card/70 flex items-center gap-2 px-3">
        <FileCode2 className="size-4 text-primary shrink-0" />
        <div className="min-w-0">
          <h2 className="text-sm font-bold truncate">{app.title}</h2>
          <p className="text-[10px] font-mono text-muted-foreground">
            {PLAYGROUND_CATEGORIES[app.category]?.label} · v{app.version} · {app.builtin ? '内置示例' : `生成于 ${timeAgo(app.createdAt)}`}
          </p>
        </div>
        <div className="ml-auto flex items-center gap-1.5 flex-wrap justify-end">
          <Button variant="ghost" size="sm" className="h-9 gap-1.5" onClick={() => setReloadKey((k) => k + 1)}>
            <RefreshCw className="size-3.5" /> 重载
          </Button>
          <Button variant="ghost" size="sm" className="h-9 gap-1.5" onClick={() => shellRef.current?.requestFullscreen?.()}>
            <Maximize2 className="size-3.5" /> 全屏
          </Button>
          <Button variant="ghost" size="sm" className="h-9 gap-1.5" onClick={openTab}>
            <ExternalLink className="size-3.5" /> 新标签
          </Button>
          <Button variant="ghost" size="sm" className="h-9 gap-1.5" onClick={download} disabled={!html}>
            <Download className="size-3.5" /> 下载
          </Button>
          <Button variant="outline" size="sm" className="h-9 gap-1.5 text-primary" onClick={() => onRegenerate(app)} disabled={app.builtin}>
            <RotateCw className="size-3.5" /> 重新生成
          </Button>
          {!app.builtin && (
            <Button variant="ghost" size="sm" className="h-9 gap-1.5 text-destructive hover:text-destructive" onClick={() => setConfirmDelete(true)}>
              <Trash2 className="size-3.5" /> 删除
            </Button>
          )}
          <Button size="sm" className="h-9 w-9 p-0" onClick={onClose} aria-label="关闭预览">
            <X className="size-4" />
          </Button>
        </div>
      </header>

      <div className="flex-1 min-h-0 bg-[#0a0f0c] relative">
        {isLoading || !html ? (
          <div className="absolute inset-0 grid place-items-center text-muted-foreground">
            <Loader2 className="size-6 animate-spin text-primary" />
          </div>
        ) : (
          <iframe
            key={`${app.id}-${reloadKey}`}
            ref={iframeRef}
            title={`预览：${app.title}`}
            srcDoc={html}
            sandbox="allow-scripts"
            className="w-full h-full border-0 bg-[#0a0f0c]"
          />
        )}
      </div>

      <AlertDialog open={confirmDelete} onOpenChange={setConfirmDelete}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>删除「{app.title}」？</AlertDialogTitle>
            <AlertDialogDescription>作品及其 HTML 将被永久删除。</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>取消</AlertDialogCancel>
            <AlertDialogAction
              className="bg-destructive text-destructive-foreground hover:bg-destructive/90"
              onClick={() => onDeleted(app.id)}
            >
              删除
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  )
}

// ═══════════ 视图 ═══════════
export function PlaygroundView() {
  const queryClient = useQueryClient()
  const { data, isLoading } = useQuery({ queryKey: ['playground'], queryFn: api.listPlayground })
  const apps = data?.apps ?? []
  const [genOpen, setGenOpen] = useState(false)
  const [regenerateOf, setRegenerateOf] = useState<PlaygroundAppSummary | null>(null)
  const [previewId, setPreviewId] = useState<string | null>(null)
  const previewApp = useMemo(() => apps.find((a) => a.id === previewId) ?? null, [apps, previewId])

  const deleteApp = useMutation({
    mutationFn: (id: string) => api.deletePlaygroundApp(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['playground'] })
      setPreviewId(null)
      toast({ title: '作品已删除' })
    },
    onError: (e) => toast({ title: '删除失败', description: (e as Error).message, variant: 'destructive' }),
  })

  const builtinCount = apps.filter((a) => a.builtin).length

  return (
    <div className="flex-1 min-w-0 flex flex-col overflow-hidden">
      <div className="shrink-0 border-b border-border bg-card/50 px-4 sm:px-6 h-14 flex items-center gap-4">
        <h1 className="font-bold text-sm flex items-center gap-2">
          <Gamepad2 className="size-4 text-primary" /> 游乐场
        </h1>
        <span className="hidden sm:inline text-[11px] font-mono text-muted-foreground">
          自包含单文件 HTML · 零网络 · 沙箱预览
        </span>
        <Button size="sm" className="ml-auto h-9 gap-2 shadow-[0_0_20px_hsl(152_84%_60%/0.25)]" onClick={() => { setRegenerateOf(null); setGenOpen(true) }}>
          <Plus className="size-4" /> 新建作品
        </Button>
      </div>

      <div className="flex-1 min-h-0 overflow-y-auto">
        <div className="max-w-5xl mx-auto w-full px-4 sm:px-6 py-6">
          <div className="rounded-xl border border-border bg-card p-4 mb-5 flex gap-3">
            <Info className="size-4 text-primary shrink-0 mt-0.5" />
            <p className="text-xs text-muted-foreground leading-relaxed">
              每个作品都是<strong className="text-foreground">一个自包含 HTML 文件</strong>：
              无外部依赖、无网络请求，可下载后双击直接运行。生成链路执行硬性契约校验（外链 / 截断 / 体积 / 生命周期），
              未过审会带反馈自动重试。
              {builtinCount > 0 && <span className="text-primary"> 顶部 {builtinCount} 件是内置示例——手工打造的质量标杆。</span>}
            </p>
          </div>

          {isLoading ? (
            <p className="text-center text-xs text-muted-foreground font-mono py-10">加载作品…</p>
          ) : apps.length === 0 ? (
            <div className="text-center py-16">
              <Gamepad2 className="size-10 mx-auto text-muted-foreground/40 mb-4" />
              <p className="text-sm text-muted-foreground">还没有作品</p>
              <Button className="mt-4" onClick={() => setGenOpen(true)}>
                <Wand2 className="size-4" /> 生成第一个
              </Button>
            </div>
          ) : (
            <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
              {apps.map((a) => (
                <article
                  key={a.id}
                  className="group rounded-2xl border border-border bg-card p-4 flex flex-col hover:border-primary/50 hover:shadow-[0_0_24px_hsl(152_84%_60%/0.1)] transition-all"
                >
                  <div className="flex items-center gap-2 mb-2 flex-wrap">
                    <Badge variant={a.builtin ? 'default' : 'secondary'} className="font-mono text-[10px] gap-1">
                      {a.builtin ? '★ 内置' : '◆ 生成'} v{a.version}
                    </Badge>
                    <Badge variant="outline" className="font-mono text-[10px]">
                      {PLAYGROUND_CATEGORIES[a.category]?.icon} {PLAYGROUND_CATEGORIES[a.category]?.label ?? a.category}
                    </Badge>
                  </div>
                  <h3 className="font-bold text-[15px] leading-snug">{a.title}</h3>
                  <p className="mt-1.5 text-xs text-muted-foreground leading-relaxed line-clamp-3 flex-1">
                    {a.description || '（无描述）'}
                  </p>
                  <p className="mt-3 text-[10px] font-mono text-muted-foreground/70">
                    {timeAgo(a.updatedAt)}
                  </p>
                  <div className="mt-3 flex gap-2">
                    <Button size="sm" className="h-9 flex-1 gap-1.5" onClick={() => setPreviewId(a.id)}>
                      <Play className="size-3.5" /> 打开
                    </Button>
                    {!a.builtin && (
                      <>
                        <Button
                          size="sm"
                          variant="outline"
                          className="h-9 w-9 p-0"
                          aria-label={`重新生成 ${a.title}`}
                          onClick={() => { setRegenerateOf(a); setGenOpen(true) }}
                        >
                          <RotateCw className="size-3.5" />
                        </Button>
                        <Button
                          size="sm"
                          variant="outline"
                          className="h-9 w-9 p-0 text-destructive hover:text-destructive"
                          aria-label={`删除 ${a.title}`}
                          onClick={() => deleteApp.mutate(a.id)}
                        >
                          <Trash2 className="size-3.5" />
                        </Button>
                      </>
                    )}
                  </div>
                </article>
              ))}
            </div>
          )}
        </div>
      </div>

      <GenerateDialog
        open={genOpen}
        onOpenChange={setGenOpen}
        regenerateOf={regenerateOf}
        onCreated={(app, replaced) => {
          queryClient.invalidateQueries({ queryKey: ['playground'] })
          setRegenerateOf(null)
          toast({
            title: replaced ? `「${app.title}」已更新（v${app.version}）` : `「${app.title}」生成完成`,
            description: replaced ? '原作品被替换，战绩键保持不变。' : '已通过硬性契约校验。',
          })
          setPreviewId(app.id)
        }}
      />

      <PreviewOverlay
        app={previewApp}
        onClose={() => setPreviewId(null)}
        onRegenerate={(a) => { setPreviewId(null); setRegenerateOf(a); setGenOpen(true) }}
        onDeleted={(id) => deleteApp.mutate(id)}
      />
    </div>
  )
}
