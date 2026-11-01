import { NextResponse } from 'next/server'
import { db } from '@/lib/db'

/**
 * 全局深度搜索 —— 跨工作区匹配对话标题与消息内容。
 * 供命令面板（Cmd+K）调用：返回命中对话 + 上下文片段，
 * 前端据此完成「跨工作区跳转到对话」。
 */
export const dynamic = 'force-dynamic'

/** 从消息正文中截取关键词上下文片段（换行压平，首尾省略号） */
function makeSnippet(content: string, q: string): string {
  const idx = content.toLowerCase().indexOf(q.toLowerCase())
  const start = idx === -1 ? 0 : Math.max(0, idx - 16)
  const end = idx === -1 ? 64 : Math.min(content.length, idx + q.length + 48)
  const prefix = start > 0 ? '…' : ''
  const suffix = end < content.length ? '…' : ''
  return (prefix + content.slice(start, end) + suffix).replace(/\s+/g, ' ').trim().slice(0, 96)
}

export async function GET(req: Request) {
  const url = new URL(req.url)
  const q = (url.searchParams.get('q') || '').trim().slice(0, 80)

  if (!q) return NextResponse.json({ results: [] })

  const conversations = await db.conversation.findMany({
    where: {
      OR: [
        { title: { contains: q } },
        { messages: { some: { content: { contains: q } } } },
      ],
    },
    orderBy: { updatedAt: 'desc' },
    take: 24,
    include: {
      workspace: { select: { id: true, name: true, emoji: true } },
      _count: { select: { messages: true } },
    },
  })

  // 标题未命中的对话，补一条最新命中消息做片段预览（一次批量查询）
  const qLower = q.toLowerCase()
  const needSnippet = conversations.filter((c) => !c.title.toLowerCase().includes(qLower))
  const hitMessages = needSnippet.length
    ? await db.message.findMany({
        where: { conversationId: { in: needSnippet.map((c) => c.id) }, content: { contains: q } },
        orderBy: { createdAt: 'desc' },
        select: { conversationId: true, role: true, content: true },
      })
    : []
  const snippetByConv = new Map<string, { role: string; content: string }>()
  for (const m of hitMessages) {
    if (!snippetByConv.has(m.conversationId)) snippetByConv.set(m.conversationId, m)
  }

  const results = conversations.map((c) => {
    const snippetMsg = snippetByConv.get(c.id)
    return {
      conversationId: c.id,
      title: c.title,
      mode: c.mode === 'coding' ? ('coding' as const) : ('agent' as const),
      workspaceId: c.workspace.id,
      workspaceName: c.workspace.name,
      workspaceEmoji: c.workspace.emoji,
      updatedAt: c.updatedAt.toISOString(),
      messageCount: c._count.messages,
      hit: c.title.toLowerCase().includes(qLower) ? ('title' as const) : ('content' as const),
      snippet: snippetMsg ? makeSnippet(snippetMsg.content, q) : null,
      snippetRole: snippetMsg
        ? snippetMsg.role === 'assistant'
          ? ('assistant' as const)
          : ('user' as const)
        : null,
    }
  })

  // 标题命中优先，其次按更新时间（findMany 已按时间排序，稳定排序保留）
  results.sort((a, b) => (a.hit === b.hit ? 0 : a.hit === 'title' ? -1 : 1))

  return NextResponse.json({ query: q, results })
}
