import { NextResponse } from 'next/server'
import { db } from '@/lib/db'

type Params = { params: Promise<{ id: string }> }

export async function GET(_req: Request, { params }: Params) {
  const { id } = await params
  const conv = await db.conversation.findUnique({
    where: { id },
    include: {
      messages: { orderBy: { createdAt: 'asc' } },
      workspace: { select: { id: true, name: true, emoji: true, description: true } },
    },
  })
  if (!conv) return NextResponse.json({ error: '对话不存在' }, { status: 404 })
  return NextResponse.json({ conversation: conv })
}

export async function PATCH(req: Request, { params }: Params) {
  const { id } = await params
  const body = await req.json().catch(() => null)
  if (!body) return NextResponse.json({ error: '无效请求' }, { status: 400 })
  const data: Record<string, unknown> = {}
  if (typeof body.title === 'string') data.title = body.title.trim().slice(0, 60) || '未命名对话'
  if (body.mode === 'agent' || body.mode === 'coding') data.mode = body.mode
  if (Object.keys(data).length === 0) {
    return NextResponse.json({ error: '没有可更新字段' }, { status: 400 })
  }
  try {
    const conv = await db.conversation.update({ where: { id }, data })
    return NextResponse.json({ conversation: conv })
  } catch {
    return NextResponse.json({ error: '对话不存在' }, { status: 404 })
  }
}

export async function DELETE(_req: Request, { params }: Params) {
  const { id } = await params
  try {
    await db.conversation.delete({ where: { id } })
    return NextResponse.json({ ok: true })
  } catch {
    return NextResponse.json({ error: '对话不存在' }, { status: 404 })
  }
}
