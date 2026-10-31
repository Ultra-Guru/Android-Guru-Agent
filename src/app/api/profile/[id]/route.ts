import { NextResponse } from 'next/server'
import { db } from '@/lib/db'

type Params = { params: Promise<{ id: string }> }
const ASPECTS = ['tech-stack', 'coding-style', 'preferences', 'domains', 'habits', 'goals']

export async function PATCH(req: Request, { params }: Params) {
  const { id } = await params
  const body = await req.json().catch(() => null)
  if (!body) return NextResponse.json({ error: '无效请求' }, { status: 400 })
  const data: Record<string, unknown> = {}
  if (typeof body.content === 'string') {
    const content = body.content.trim()
    if (!content || content.length > 200) return NextResponse.json({ error: '内容无效' }, { status: 400 })
    data.content = content
  }
  if (typeof body.aspect === 'string' && ASPECTS.includes(body.aspect)) data.aspect = body.aspect
  if (typeof body.confidence === 'number') {
    data.confidence = Math.min(1, Math.max(0.3, body.confidence))
  }
  if (Object.keys(data).length === 0) {
    return NextResponse.json({ error: '没有可更新字段' }, { status: 400 })
  }
  try {
    const item = await db.profileItem.update({
      where: { id },
      data: { ...data, lastAccessedAt: new Date() },
    })
    return NextResponse.json({ profileItem: item })
  } catch {
    return NextResponse.json({ error: '条目不存在' }, { status: 404 })
  }
}

export async function DELETE(_req: Request, { params }: Params) {
  const { id } = await params
  try {
    await db.profileItem.delete({ where: { id } })
    return NextResponse.json({ ok: true })
  } catch {
    return NextResponse.json({ error: '条目不存在' }, { status: 404 })
  }
}
