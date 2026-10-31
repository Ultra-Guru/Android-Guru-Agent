'use client'

// 设置：规则分级（软件级全局 / 项目级按工作区） + 工作区管理 + 数据
import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { api, timeAgo, type WorkspaceSummary } from '@/lib/client'
import { Button } from '@/components/ui/button'
import { Textarea } from '@/components/ui/textarea'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
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
import { useApp } from '@/lib/store'
import { NewWorkspaceDialog } from '@/components/app/chat-sidebar'
import {
  Settings2,
  ScrollText,
  Save,
  Loader2,
  RotateCw,
  Trash2,
  Download,
  Pencil,
  FolderPlus,
  Globe2,
  FileText,
  Info,
} from 'lucide-react'

// ═══════════ 规则区 ═══════════
function RulesSection() {
  const queryClient = useQueryClient()
  const activeWorkspaceId = useApp((s) => s.activeWorkspaceId)
  const { data } = useQuery({ queryKey: ['rules'], queryFn: api.getRules })
  const workspaces = data?.workspaces ?? []
  const [tabWsId, setTabWsId] = useState<string | null>(null)

  const selectedId = tabWsId || activeWorkspaceId || workspaces[0]?.id || null
  const selected = workspaces.find((w) => w.id === selectedId) ?? workspaces[0]

  // 软件级规则
  const [globalDraft, setGlobalDraft] = useState<string | null>(null)
  const globalValue = data?.globalRules ?? ''
  const globalDirty = globalDraft !== null && globalDraft !== globalValue

  // 项目级规则：草稿按工作区分键存储（切换标签自动隔离，无需 effect 重置）
  const [drafts, setDrafts] = useState<Record<string, string>>({})
  const projectDraft = selectedId && selectedId in drafts ? drafts[selectedId] : null
  const projectValue = selected?.projectRules ?? ''
  const projectDirty = projectDraft !== null && projectDraft !== projectValue
  const setProjectDraftFor = (wsId: string, value: string) =>
    setDrafts((d) => ({ ...d, [wsId]: value }))
  const resetProjectDraft = (wsId: string) =>
    setDrafts((d) => {
      const { [wsId]: _drop, ...rest } = d
      return rest
    })

  const saveGlobal = useMutation({
    mutationFn: () => api.saveRules({ scope: 'global', rules: globalDraft ?? globalValue }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['rules'] })
      queryClient.invalidateQueries({ queryKey: ['workspaces'] })
      setGlobalDraft(null)
      toast({ title: '软件级规则已保存', description: '对所有工作区、所有模式生效。' })
    },
    onError: (e) => toast({ title: '保存失败', description: (e as Error).message, variant: 'destructive' }),
  })

  const saveProject = useMutation({
    mutationFn: () => api.saveRules({ scope: 'workspace', workspaceId: selectedId!, rules: projectDraft ?? projectValue }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['rules'] })
      queryClient.invalidateQueries({ queryKey: ['workspaces'] })
      if (selectedId) resetProjectDraft(selectedId)
      toast({ title: `「${selected?.name}」项目规则已保存`, description: '仅在该工作区内生效，优先于软件级规则。' })
    },
    onError: (e) => toast({ title: '保存失败', description: (e as Error).message, variant: 'destructive' }),
  })

  return (
    <section aria-label="规则" className="space-y-4">
      <div className="flex items-center gap-3">
        <ScrollText className="size-5 text-primary" />
        <div>
          <h2 className="font-bold">规则</h2>
          <p className="text-xs text-muted-foreground">
            两级规则，注入优先序：安全约束 &gt; 项目级 &gt; 软件级 &gt; 记忆/画像
          </p>
        </div>
      </div>

      <div className="grid gap-4 lg:grid-cols-2">
        {/* 软件级 */}
        <div className="rounded-2xl border border-border bg-card p-4 sm:p-5 flex flex-col">
          <div className="flex items-center gap-2.5 mb-1">
            <div className="size-9 rounded-lg bg-primary/15 border border-primary/30 grid place-items-center">
              <Globe2 className="size-4 text-primary" />
            </div>
            <div className="min-w-0 flex-1">
              <h3 className="font-semibold text-sm">软件级规则</h3>
              <p className="text-[11px] text-muted-foreground">全局生效 · 所有工作区 · 所有模式</p>
            </div>
            <Badge variant="outline" className="font-mono text-[10px]">{globalValue.length} 字</Badge>
          </div>
          <Textarea
            value={globalDraft ?? globalValue}
            onChange={(e) => setGlobalDraft(e.target.value)}
            rows={10}
            maxLength={64000}
            placeholder={'全局通用的行为准则。例如：\n· 回答先给结论\n· 默认简体中文\n· 引用资料注明来源'}
            className="mt-3 font-mono text-xs leading-relaxed flex-1 resize-y"
            aria-label="软件级规则"
          />
          <div className="mt-3 flex items-center gap-2">
            <span className="text-[10px] font-mono text-muted-foreground">
              {(globalDraft ?? globalValue).length} 字
            </span>
            <div className="ml-auto flex gap-2">
              <Button
                variant="ghost"
                size="sm"
                className="h-9 gap-1.5"
                disabled={!globalDirty || saveGlobal.isPending}
                onClick={() => setGlobalDraft(null)}
              >
                <RotateCw className="size-3.5" /> 重置
              </Button>
              <Button
                size="sm"
                className="h-9 gap-1.5"
                disabled={!globalDirty || saveGlobal.isPending}
                onClick={() => saveGlobal.mutate()}
              >
                {saveGlobal.isPending ? <Loader2 className="size-3.5 animate-spin" /> : <Save className="size-3.5" />}
                保存
              </Button>
            </div>
          </div>
        </div>

        {/* 项目级 */}
        <div className="rounded-2xl border border-border bg-card p-4 sm:p-5 flex flex-col">
          <div className="flex items-center gap-2.5 mb-1">
            <div className="size-9 rounded-lg bg-primary/15 border border-primary/30 grid place-items-center">
              <FileText className="size-4 text-primary" />
            </div>
            <div className="min-w-0 flex-1">
              <h3 className="font-semibold text-sm">项目级规则</h3>
              <p className="text-[11px] text-muted-foreground">仅选中工作区生效 · 也可在编码输入框编辑</p>
            </div>
            <Badge variant="outline" className="font-mono text-[10px]">{projectValue.length} 字</Badge>
          </div>

          {workspaces.length > 1 && (
            <div className="mt-3 flex gap-1.5 flex-wrap" role="tablist" aria-label="选择工作区">
              {workspaces.map((w) => (
                <button
                  key={w.id}
                  role="tab"
                  aria-selected={w.id === selectedId}
                  onClick={() => setTabWsId(w.id)}
                  className={cn(
                    'h-8 px-3 rounded-lg text-xs font-medium border transition-colors truncate max-w-40',
                    w.id === selectedId
                      ? 'border-primary bg-primary/15 text-primary'
                      : 'border-border text-muted-foreground hover:text-foreground'
                  )}
                >
                  {w.emoji} {w.name}
                </button>
              ))}
            </div>
          )}

          <Textarea
            value={projectDraft ?? projectValue}
            onChange={(e) => selectedId && setProjectDraftFor(selectedId, e.target.value)}
            rows={10}
            maxLength={64000}
            placeholder={`「${selected?.name ?? '…'}」专属规则。例如：\n· TypeScript strict\n· 组件用 shadcn/ui\n· 提交信息用中文`}
            className="mt-3 font-mono text-xs leading-relaxed flex-1 resize-y"
            aria-label="项目级规则"
            disabled={!selected}
          />
          <div className="mt-3 flex items-center gap-2">
            <span className="text-[10px] font-mono text-muted-foreground">
              {(projectDraft ?? projectValue).length} 字 · {selected?.name}
            </span>
            <div className="ml-auto flex gap-2">
              <Button
                variant="ghost"
                size="sm"
                className="h-9 gap-1.5"
                disabled={!projectDirty || saveProject.isPending}
                onClick={() => selectedId && resetProjectDraft(selectedId)}
              >
                <RotateCw className="size-3.5" /> 重置
              </Button>
              <Button
                size="sm"
                className="h-9 gap-1.5"
                disabled={!projectDirty || saveProject.isPending || !selected}
                onClick={() => saveProject.mutate()}
              >
                {saveProject.isPending ? <Loader2 className="size-3.5 animate-spin" /> : <Save className="size-3.5" />}
                保存到该工作区
              </Button>
            </div>
          </div>
        </div>
      </div>

      <div className="rounded-xl border border-border bg-card p-4 flex gap-3">
        <Info className="size-4 text-primary shrink-0 mt-0.5" />
        <p className="text-xs text-muted-foreground leading-relaxed">
          <strong className="text-foreground">软件级</strong>规则写入全局设置，对所有工作区生效；
          <strong className="text-foreground">项目级</strong>规则存储在工作区上，只在该工作区的对话中注入——
          在编码模式输入框里写的规则同样落到这里（同一数据源）。
        </p>
      </div>
    </section>
  )
}

// ═══════════ 工作区管理 ═══════════
function WorkspacesSection() {
  const queryClient = useQueryClient()
  const { data } = useQuery({ queryKey: ['workspaces'], queryFn: api.listWorkspaces })
  const workspaces = data?.workspaces ?? []
  const setActiveWorkspace = useApp((s) => s.setActiveWorkspace)
  const activeWorkspaceId = useApp((s) => s.activeWorkspaceId)

  const [createOpen, setCreateOpen] = useState(false)
  const [renaming, setRenaming] = useState<WorkspaceSummary | null>(null)
  const [renameValue, setRenameValue] = useState('')
  const [deleteTarget, setDeleteTarget] = useState<WorkspaceSummary | null>(null)

  const deleteWs = useMutation({
    mutationFn: (id: string) => api.deleteWorkspace(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['workspaces'] })
      queryClient.invalidateQueries({ queryKey: ['rules'] })
      setDeleteTarget(null)
      if (deleteTarget?.id === activeWorkspaceId) setActiveWorkspace(null, { pickConversation: null })
      toast({ title: '工作区已删除', description: '其下对话与项目规则一并移除。' })
    },
    onError: (e) => toast({ title: '删除失败', description: (e as Error).message, variant: 'destructive' }),
  })

  const doRename = async () => {
    if (!renaming || !renameValue.trim()) return
    try {
      await api.updateWorkspace(renaming.id, { name: renameValue.trim() })
      queryClient.invalidateQueries({ queryKey: ['workspaces'] })
      queryClient.invalidateQueries({ queryKey: ['rules'] })
      setRenaming(null)
      toast({ title: '已重命名' })
    } catch (e) {
      toast({ title: '重命名失败', description: (e as Error).message, variant: 'destructive' })
    }
  }

  return (
    <section aria-label="工作区管理" className="space-y-4">
      <div className="flex items-center gap-3">
        <FolderPlus className="size-5 text-primary" />
        <div className="flex-1">
          <h2 className="font-bold">工作区</h2>
          <p className="text-xs text-muted-foreground">{workspaces.length} 个工作区 · 删除会级联删除其对话</p>
        </div>
        <Button size="sm" className="h-9 gap-1.5" onClick={() => setCreateOpen(true)}>
          <FolderPlus className="size-3.5" /> 新建
        </Button>
      </div>

      <ul className="space-y-2.5">
        {workspaces.map((w) => (
          <li
            key={w.id}
            className={cn(
              'group rounded-2xl border bg-card p-4 flex items-center gap-3 transition-colors',
              w.id === activeWorkspaceId ? 'border-primary/50' : 'border-border hover:border-primary/30'
            )}
          >
            <span className="text-2xl" aria-hidden>{w.emoji}</span>
            <div className="min-w-0 flex-1">
              <p className="font-semibold text-sm flex items-center gap-2 flex-wrap">
                {w.name}
                {w.id === activeWorkspaceId && <Badge className="text-[9px] py-0 font-mono">当前</Badge>}
              </p>
              <p className="text-xs text-muted-foreground truncate">{w.description || '（无描述）'}</p>
              <p className="mt-1 text-[10px] font-mono text-muted-foreground/70">
                {w.conversationCount} 对话 · 项目规则 {w.projectRules.length} 字 · {timeAgo(w.lastUsedAt)}
              </p>
            </div>
            <div className="flex gap-1.5 shrink-0">
              <Button
                variant="outline"
                size="sm"
                className="h-9 w-9 p-0"
                aria-label={`重命名 ${w.name}`}
                onClick={() => { setRenaming(w); setRenameValue(w.name) }}
              >
                <Pencil className="size-3.5" />
              </Button>
              <Button
                variant="outline"
                size="sm"
                className="h-9 w-9 p-0 text-destructive hover:text-destructive"
                aria-label={`删除 ${w.name}`}
                onClick={() => setDeleteTarget(w)}
              >
                <Trash2 className="size-3.5" />
              </Button>
            </div>
          </li>
        ))}
      </ul>

      <NewWorkspaceDialog
        open={createOpen}
        onOpenChange={setCreateOpen}
        onCreated={(ws) => {
          queryClient.invalidateQueries({ queryKey: ['workspaces'] })
          setActiveWorkspace(ws.id, { pickConversation: null })
          toast({ title: `已创建并切换到「${ws.name}」` })
        }}
      />

      <Dialog open={!!renaming} onOpenChange={(v) => !v && setRenaming(null)}>
        <DialogContent className="sm:max-w-sm">
          <DialogHeader>
            <DialogTitle>重命名工作区</DialogTitle>
            <DialogDescription>修改名称后保存，立即生效。</DialogDescription>
          </DialogHeader>
          <div className="space-y-2 py-1">
            <Label htmlFor="rename-ws">名称</Label>
            <Input
              id="rename-ws"
              value={renameValue}
              onChange={(e) => setRenameValue(e.target.value)}
              maxLength={40}
              autoFocus
              onKeyDown={(e) => e.key === 'Enter' && doRename()}
            />
          </div>
          <DialogFooter>
            <Button variant="ghost" onClick={() => setRenaming(null)}>取消</Button>
            <Button onClick={doRename} disabled={!renameValue.trim()}>保存</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <AlertDialog open={!!deleteTarget} onOpenChange={(v) => !v && setDeleteTarget(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>删除工作区「{deleteTarget?.name}」？</AlertDialogTitle>
            <AlertDialogDescription>
              其 {deleteTarget?.conversationCount} 个对话、消息与项目级规则将一并删除，无法恢复。
              软件级规则不受影响。
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>取消</AlertDialogCancel>
            <AlertDialogAction
              className="bg-destructive text-destructive-foreground hover:bg-destructive/90"
              onClick={() => deleteTarget && deleteWs.mutate(deleteTarget.id)}
            >
              删除
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </section>
  )
}

// ═══════════ 数据区 ═══════════
function DataSection() {
  const queryClient = useQueryClient()
  const [confirmKind, setConfirmKind] = useState<'memory' | 'profile' | null>(null)
  const { data: memData } = useQuery({ queryKey: ['memory'], queryFn: api.listMemories })
  const { data: profData } = useQuery({ queryKey: ['profile'], queryFn: api.listProfile })

  const clear = async () => {
    if (!confirmKind) return
    try {
      const r = confirmKind === 'memory' ? await api.clearMemories() : await api.clearProfile()
      queryClient.invalidateQueries({ queryKey: confirmKind === 'memory' ? ['memory'] : ['profile'] })
      toast({ title: `已清空 ${r.deleted} 条${confirmKind === 'memory' ? '记忆' : '画像'}` })
    } catch (e) {
      toast({ title: '清空失败', description: (e as Error).message, variant: 'destructive' })
    } finally {
      setConfirmKind(null)
    }
  }

  return (
    <section aria-label="数据" className="space-y-4">
      <div className="flex items-center gap-3">
        <Download className="size-5 text-primary" />
        <div>
          <h2 className="font-bold">数据</h2>
          <p className="text-xs text-muted-foreground">备份与清理（删除不可恢复）</p>
        </div>
      </div>

      <div className="grid gap-3 sm:grid-cols-3">
        <div className="rounded-2xl border border-border bg-card p-4">
          <p className="text-xs font-mono text-muted-foreground">对话记忆</p>
          <p className="text-2xl font-bold font-mono mt-1">{memData?.memories.length ?? 0}</p>
          <Button
            variant="outline"
            size="sm"
            className="mt-3 w-full h-9 text-destructive hover:text-destructive"
            disabled={!memData?.memories.length}
            onClick={() => setConfirmKind('memory')}
          >
            <Trash2 className="size-3.5" /> 清空记忆
          </Button>
        </div>
        <div className="rounded-2xl border border-border bg-card p-4">
          <p className="text-xs font-mono text-muted-foreground">用户画像</p>
          <p className="text-2xl font-bold font-mono mt-1">{profData?.stats.total ?? 0}</p>
          <Button
            variant="outline"
            size="sm"
            className="mt-3 w-full h-9 text-destructive hover:text-destructive"
            disabled={!profData?.stats.total}
            onClick={() => setConfirmKind('profile')}
          >
            <Trash2 className="size-3.5" /> 清空画像
          </Button>
        </div>
        <div className="rounded-2xl border border-border bg-card p-4">
          <p className="text-xs font-mono text-muted-foreground">完整备份</p>
          <p className="text-2xl font-bold font-mono mt-1">JSON</p>
          <Button
            variant="outline"
            size="sm"
            className="mt-3 w-full h-9"
            onClick={() => window.open('/api/export', '_blank')}
          >
            <Download className="size-3.5" /> 导出全部数据
          </Button>
        </div>
      </div>

      <AlertDialog open={!!confirmKind} onOpenChange={(v) => !v && setConfirmKind(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>
              清空{confirmKind === 'memory' ? '全部对话记忆' : '全部用户画像'}？
            </AlertDialogTitle>
            <AlertDialogDescription>删除后无法恢复；对话内容本身不受影响。</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>取消</AlertDialogCancel>
            <AlertDialogAction className="bg-destructive text-destructive-foreground hover:bg-destructive/90" onClick={clear}>
              清空
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </section>
  )
}

// ═══════════ 视图 ═══════════
export function SettingsView() {
  return (
    <div className="flex-1 min-w-0 flex flex-col overflow-hidden">
      <div className="shrink-0 border-b border-border bg-card/50 px-4 sm:px-6 h-14 flex items-center gap-3">
        <Settings2 className="size-4 text-primary" />
        <h1 className="font-bold text-sm">设置</h1>
        <span className="hidden sm:inline text-[11px] font-mono text-muted-foreground">
          规则 · 工作区 · 数据
        </span>
      </div>
      <div className="flex-1 min-h-0 overflow-y-auto">
        <div className="max-w-4xl mx-auto w-full px-4 sm:px-6 py-8 space-y-10">
          <RulesSection />
          <WorkspacesSection />
          <DataSection />
        </div>
      </div>
    </div>
  )
}
