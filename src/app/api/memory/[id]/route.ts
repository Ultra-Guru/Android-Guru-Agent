import { NextResponse } from 'next/server'
import { db } from '@/lib/db'

type Params = { params: Promise<{ id: string }> }

export async function PATCH(req: Request, { params }: Params) {
  const { id } = await params
  const body = await req.json().catch(() => null)
  if (!body) return NextResponse.json({ error: '无效请求' }, { status: 400 })
  const data: Record<string, unknown> = {}
  if (typeof body.content === 'string') {
    const content = body.content.trim()
    if (!content || content.length > 300) {
      return NextResponse.json({ error: '内容无效' }, { status: 400 })
    }
    data.content = content
  }
  if (typeof body.category === 'string' && ['fact', 'state', 'milestone', 'preference'].includes(body.category)) {
    data.category = body.category
  }
  if (typeof body.importance === 'number') {
    data.importance = Math.min(1, Math.max(0.3, body.importance))
  }
  if (Object.keys(data).length === 0) {
    return NextResponse.json({ error: '没有可更新字段' }, { status: 400 })
  }
  try {
    const item = await db.memoryItem.update({
      where: { id },
      data: { ...data, lastAccessedAt: new Date() },
    })
    return NextResponse.json({ memory: item })
  } catch {
    return NextResponse.json({ error: '记忆不存在' }, { status: 404 })
  }
}

export async function DELETE(_req: Request, { params }: Params) {
  const { id } = await params
  try {
    await db.memoryItem.delete({ where: { id } })
    return NextResponse.json({ ok: true })
  } catch {
    return NextResponse.json({ error: '记忆不存在' }, { status: 404 })
  }
}
