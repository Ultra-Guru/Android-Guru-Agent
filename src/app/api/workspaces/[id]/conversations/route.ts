import { NextResponse } from 'next/server'
import { db } from '@/lib/db'

type Params = { params: Promise<{ id: string }> }

/** 列出该工作区的全部对话（工作区绑定：切工作区 = 切对话集合） */
export async function GET(_req: Request, { params }: Params) {
  const { id } = await params
  const conversations = await db.conversation.findMany({
    where: { workspaceId: id },
    orderBy: { updatedAt: 'desc' },
    include: { _count: { select: { messages: true } } },
  })
  return NextResponse.json({
    conversations: conversations.map((c) => ({
      id: c.id,
      title: c.title,
      mode: c.mode,
      updatedAt: c.updatedAt,
      createdAt: c.createdAt,
      messageCount: c._count.messages,
    })),
  })
}

/** 新对话永远落在当前工作区 */
export async function POST(req: Request, { params }: Params) {
  const { id } = await params
  const body = await req.json().catch(() => ({}))
  const mode = body?.mode === 'coding' ? 'coding' : 'agent'
  const title = (body?.title || '新对话').toString().slice(0, 60)

  const ws = await db.workspace.findUnique({ where: { id } })
  if (!ws) return NextResponse.json({ error: '工作区不存在' }, { status: 404 })

  const [conv] = await db.$transaction([
    db.conversation.create({ data: { workspaceId: id, mode, title } }),
    db.workspace.update({ where: { id }, data: { lastUsedAt: new Date() } }),
  ])
  return NextResponse.json({ conversation: { ...conv, messageCount: 0 } }, { status: 201 })
}
