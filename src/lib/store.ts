// 应用状态 —— zustand（UI 态）；服务端数据走 TanStack Query
'use client'

import { create } from 'zustand'

export type ViewKey = 'chat' | 'memory' | 'playground' | 'settings'

interface AppState {
  view: ViewKey
  activeWorkspaceId: string | null
  activeConversationId: string | null
  mobileNavOpen: boolean
  paletteOpen: boolean
  setView: (v: ViewKey) => void
  setActiveWorkspace: (id: string | null, opts?: { pickConversation?: string | null }) => void
  setActiveConversation: (id: string | null) => void
  setMobileNavOpen: (open: boolean) => void
  setPaletteOpen: (open: boolean) => void
}

const LS_KEY = 'guru.activeWorkspaceId'

function readStoredWorkspace(): string | null {
  try {
    return localStorage.getItem(LS_KEY)
  } catch {
    return null
  }
}

export const useApp = create<AppState>((set) => ({
  view: 'chat',
  activeWorkspaceId: typeof window !== 'undefined' ? readStoredWorkspace() : null,
  activeConversationId: null,
  mobileNavOpen: false,
  paletteOpen: false,
  setView: (v) => set({ view: v, mobileNavOpen: false }),
  setActiveWorkspace: (id, opts) => {
    try {
      if (id) localStorage.setItem(LS_KEY, id)
      else localStorage.removeItem(LS_KEY)
    } catch {}
    // 切换工作区 = 切换对话集合：清空当前对话，由视图自动选中新工作区最近一条或空态
    set({ activeWorkspaceId: id, activeConversationId: opts?.pickConversation ?? null, mobileNavOpen: false })
  },
  setActiveConversation: (id) => set({ activeConversationId: id, mobileNavOpen: false }),
  setMobileNavOpen: (open) => set({ mobileNavOpen: open }),
  setPaletteOpen: (open) => set({ paletteOpen: open }),
}))
