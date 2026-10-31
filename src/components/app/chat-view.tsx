'use client'

// 对话视图：工作区绑定 + 对话列表（当前工作区）+ 消息面板 + 输入区
// 核心行为（用户需求 #1）：
//  · 新对话永远创建在当前工作区
//  · 切换工作区 = 切换到该工作区的对话集合（自动选最近一条，或展示空态 CTA）
//  · 新建工作区后立即切换到它，开启全新的对话

import { useEffect, useMemo, useState } from 'react'
import { useQuery, useQueryClient, useMutation } from '@tanstack/react-query'
import { api, conversationGroup, timeAgo, type ConversationSummary, type WorkspaceSummary } from '@/lib/client'
import { useApp } from '@/lib/store'
import { ChatSidebar, NewWorkspaceDialog } from '@/components/app/chat-sidebar'
import { MessagePanel } from '@/components/app/message-panel'
import { Button } from '@/components/ui/button'
import { Sheet, SheetContent, SheetTitle } from '@/components/ui/sheet'
import { toast } from '@/hooks/use-toast'

export function ChatView() {
  const activeWorkspaceId = useApp((s) => s.activeWorkspaceId)
  const setActiveWorkspace = useApp((s) => s.setActiveWorkspace)
  const activeConversationId = useApp((s) => s.activeConversationId)
  const setActiveConversation = useApp((s) => s.setActiveConversation)
  const queryClient = useQueryClient()

  const { data: wsData, isLoading: wsLoading } = useQuery({ queryKey: ['workspaces'], queryFn: api.listWorkspaces })
  const workspaces = wsData?.workspaces ?? []

  // ── 工作区解析：优先存储的选择，否则第一个 ──
  const workspace: WorkspaceSummary | undefined = useMemo(
    () => workspaces.find((w) => w.id === activeWorkspaceId) || workspaces[0],
    [workspaces, activeWorkspaceId]
  )

  const { data: convData, isLoading: convLoading } = useQuery({
    queryKey: ['conversations', workspace?.id],
    queryFn: () => api.listConversations(workspace!.id),
    enabled: !!workspace,
  })
  const conversations = convData?.conversations ?? []

  // ── 切工作区后自动选中其最近对话；无对话则空态（CTA 开启第一个对话） ──
  useEffect(() => {
    if (!workspace || convLoading) return
    if (activeConversationId && conversations.some((c) => c.id === activeConversationId)) return
    const latest = conversations[0]
    setActiveConversation(latest ? latest.id : null)
  }, [workspace, convLoading, conversations, activeConversationId, setActiveConversation])

  // ── 新对话（落在当前工作区；模式继承当前选中对话的模式） ──
  const currentMode: 'agent' | 'coding' =
    conversations.find((c) => c.id === activeConversationId)?.mode ?? 'agent'
  const createConv = useMutation({
    mutationFn: async (mode: 'agent' | 'coding') => {
      if (!workspace) throw new Error('工作区未就绪')
      return api.createConversation(workspace.id, mode)
    },
    onSuccess: ({ conversation }) => {
      queryClient.invalidateQueries({ queryKey: ['conversations', workspace?.id] })
      queryClient.invalidateQueries({ queryKey: ['workspaces'] })
      setActiveConversation(conversation.id)
    },
    onError: (e) => toast({ title: '创建对话失败', description: (e as Error).message, variant: 'destructive' }),
  })

  const deleteConv = useMutation({
    mutationFn: (id: string) => api.deleteConversation(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['conversations', workspace?.id] })
      queryClient.invalidateQueries({ queryKey: ['workspaces'] })
      setActiveConversation(null)
      toast({ title: '对话已删除' })
    },
  })

  const [newWsOpen, setNewWsOpen] = useState(false)
  const [mobileNavOpen, setMobileNavOpen] = useState(false)
  const openMobileNav = () => setMobileNavOpen(true)

  if (wsLoading || (!workspace && workspaces.length === 0)) {
    return (
      <div className="flex-1 grid place-items-center text-muted-foreground text-sm font-mono">
        正在初始化工作区…
      </div>
    )
  }
  if (!workspace) return null

  const sidebar = (
    <ChatSidebar
      workspace={workspace}
      workspaces={workspaces}
      conversations={conversations}
      activeConversationId={activeConversationId}
      convLoading={convLoading}
      onSelectConversation={(id) => setActiveConversation(id)}
      onNewConversation={() => createConv.mutate(currentMode)}
      onSwitchWorkspace={(id) => setActiveWorkspace(id, { pickConversation: null })}
      onNewWorkspace={() => setNewWsOpen(true)}
      onDeleteConversation={(id) => deleteConv.mutate(id)}
    />
  )

  return (
    <div className="flex-1 min-w-0 flex">
      <aside className="hidden md:flex w-72 shrink-0 border-r border-border bg-sidebar">{sidebar}</aside>

      {/* 移动端：工作区 / 对话列表抽屉（受控开关，入口在面板头部与空态 Hero） */}
      <Sheet open={mobileNavOpen} onOpenChange={setMobileNavOpen}>
        <SheetContent side="left" className="w-80 p-0 bg-sidebar border-r border-border">
          <SheetTitle className="sr-only">工作区与对话</SheetTitle>
          {sidebar}
        </SheetContent>
      </Sheet>

      <MessagePanel
        key={activeConversationId ?? 'empty'}
        workspace={workspace}
        conversationId={activeConversationId}
        conversations={conversations}
        onNewConversation={(mode) => createConv.mutate(mode)}
        onModeChanged={() => {
          queryClient.invalidateQueries({ queryKey: ['conversations', workspace.id] })
        }}
        onTitleChanged={() => {
          queryClient.invalidateQueries({ queryKey: ['conversations', workspace.id] })
        }}
        onOpenMobileNav={openMobileNav}
      />

      <NewWorkspaceDialog
        open={newWsOpen}
        onOpenChange={setNewWsOpen}
        onCreated={(ws) => {
          // 乐观插入缓存再失效，避免切换回退闪烁
          queryClient.setQueryData<{ workspaces: WorkspaceSummary[]; globalRules: string }>(
            ['workspaces'],
            (old) => (old ? { ...old, workspaces: [ws, ...old.workspaces] } : { workspaces: [ws], globalRules: '' })
          )
          queryClient.invalidateQueries({ queryKey: ['workspaces'] })
          queryClient.invalidateQueries({ queryKey: ['rules'] })
          setActiveWorkspace(ws.id, { pickConversation: null })
          toast({ title: `已创建并切换到「${ws.name}」`, description: '开启一段全新的对话吧。' })
        }}
      />
    </div>
  )
}

export { conversationGroup, timeAgo }
export type { ConversationSummary }
