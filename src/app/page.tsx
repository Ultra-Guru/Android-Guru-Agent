'use client'

import { Providers } from '@/components/app/providers'
import { ChatView } from '@/components/app/chat-view'
import { MemoryView } from '@/components/app/memory-view'
import { PlaygroundView } from '@/components/app/playground-view'
import { SettingsView } from '@/components/app/settings-view'
import { CommandPalette } from '@/components/app/command-palette'
import { useApp, type ViewKey } from '@/lib/store'
import { useQuery } from '@tanstack/react-query'
import { api } from '@/lib/client'
import { MessageSquare, Brain, Gamepad2, Settings2, Sun, Moon, Search } from 'lucide-react'
import { useTheme } from 'next-themes'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { useSyncExternalStore } from 'react'

const NAV: Array<{ key: ViewKey; label: string; icon: React.ElementType }> = [
  { key: 'chat', label: '对话', icon: MessageSquare },
  { key: 'memory', label: '记忆', icon: Brain },
  { key: 'playground', label: '游乐场', icon: Gamepad2 },
  { key: 'settings', label: '设置', icon: Settings2 },
]

function ThemeToggle() {
  const { theme, setTheme } = useTheme()
  const mounted = useSyncExternalStore(
    () => () => {},
    () => true,
    () => false
  )
  if (!mounted) return <Button variant="ghost" size="icon" className="size-9" aria-label="切换主题" />
  const dark = theme !== 'light'
  return (
    <Button
      variant="ghost"
      size="icon"
      className="size-9 text-muted-foreground hover:text-primary"
      onClick={() => setTheme(dark ? 'light' : 'dark')}
      aria-label={dark ? '切换到亮色主题' : '切换到暗色主题'}
    >
      {dark ? <Sun className="size-4" /> : <Moon className="size-4" />}
    </Button>
  )
}

function AppShell() {
  const view = useApp((s) => s.view)
  const setView = useApp((s) => s.setView)
  const activeWorkspaceId = useApp((s) => s.activeWorkspaceId)
  const setPaletteOpen = useApp((s) => s.setPaletteOpen)

  const { data: wsData } = useQuery({ queryKey: ['workspaces'], queryFn: api.listWorkspaces })
  const { data: convData } = useQuery({
    queryKey: ['conversations', activeWorkspaceId],
    queryFn: () => api.listConversations(activeWorkspaceId!),
    enabled: !!activeWorkspaceId,
  })
  const workspace = wsData?.workspaces.find((w) => w.id === activeWorkspaceId) || wsData?.workspaces[0]
  const convCount = convData?.conversations.length ?? 0

  return (
    <div className="h-dvh flex flex-col overflow-hidden">
      <header className="h-14 shrink-0 border-b border-border bg-card/60 backdrop-blur supports-[backdrop-filter]:bg-card/40 z-20">
        <div className="h-full px-3 sm:px-4 flex items-center gap-3">
          <div className="flex items-center gap-2.5 select-none">
            <div
              className="size-2.5 rounded-[3px] bg-primary shadow-[0_0_14px_hsl(152_84%_60%/0.65)]"
              aria-hidden
            />
            <div className="leading-none">
              <div className="font-mono font-bold text-[15px] tracking-widest glow-text">GURU</div>
              <div className="text-[10px] text-muted-foreground tracking-[0.3em] mt-0.5 hidden sm:block">
                AGENT · WEB
              </div>
            </div>
          </div>

          <nav aria-label="主导航" className="ml-2 sm:ml-6 flex items-center gap-1 p-1 rounded-xl bg-muted/60">
            {NAV.map((item) => {
              const Icon = item.icon
              const active = view === item.key
              return (
                <button
                  key={item.key}
                  onClick={() => setView(item.key)}
                  aria-current={active ? 'page' : undefined}
                  className={cn(
                    'flex items-center gap-1.5 rounded-lg px-2.5 sm:px-3.5 h-9 text-[13px] font-medium transition-colors',
                    active
                      ? 'bg-primary text-primary-foreground shadow-[0_0_16px_hsl(152_84%_60%/0.35)]'
                      : 'text-muted-foreground hover:text-foreground'
                  )}
                >
                  <Icon className="size-4" />
                  <span className="hidden xs:inline sm:inline">{item.label}</span>
                </button>
              )
            })}
          </nav>

          <div className="ml-auto flex items-center gap-1">
            <span className="hidden md:inline text-[11px] font-mono text-muted-foreground mr-2 max-w-52 truncate">
              {workspace ? `${workspace.emoji} ${workspace.name} · ${convCount} 对话` : '初始化…'}
            </span>
            {/* 全局搜索 / 命令面板入口（Cmd+K） */}
            <Button
              variant="ghost"
              onClick={() => setPaletteOpen(true)}
              className="h-9 gap-2 px-2.5 text-muted-foreground hover:text-primary"
              aria-label="打开全局搜索与命令面板（快捷键 Command K）"
            >
              <Search className="size-4" />
              <kbd className="hidden sm:inline-flex items-center h-5 px-1.5 rounded border border-border bg-muted font-mono text-[10px] tracking-wider">
                ⌘K
              </kbd>
            </Button>
            <ThemeToggle />
          </div>
        </div>
      </header>

      <main className="flex-1 min-h-0 flex">
        {view === 'chat' && <ChatView />}
        {view === 'memory' && <MemoryView />}
        {view === 'playground' && <PlaygroundView />}
        {view === 'settings' && <SettingsView />}
      </main>

      {/* 全局命令面板（Cmd+K）：跨工作区深度搜索 + 快速动作 */}
      <CommandPalette />

      <footer className="h-8 shrink-0 border-t border-border bg-card/50 flex items-center gap-3 px-4 text-[11px] font-mono text-muted-foreground">
        <span className="text-primary/90">●</span>
        <span className="truncate">{workspace ? `${workspace.emoji} ${workspace.name}` : '—'}</span>
        <span aria-hidden>·</span>
        <span>{convCount} 个对话</span>
        <span aria-hidden className="hidden sm:inline">·</span>
        <span className="hidden sm:inline">
          {view === 'chat' && '新对话落在当前工作区'}
          {view === 'memory' && '记忆与用户画像'}
          {view === 'playground' && '自包含 HTML 作品'}
          {view === 'settings' && '软件级 + 项目级规则'}
        </span>
        <button
          onClick={() => setPaletteOpen(true)}
          className="ml-auto hidden sm:inline-flex items-center gap-1.5 hover:text-primary transition-colors"
          aria-label="打开全局搜索与命令面板"
        >
          <Search className="size-3" /> ⌘K 深度搜索
        </button>
        <span className="sm:ml-0 ml-auto">GURU WEB v1</span>
      </footer>
    </div>
  )
}

export default function Home() {
  return (
    <Providers>
      <AppShell />
    </Providers>
  )
}
