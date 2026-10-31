import { NextResponse } from 'next/server'
import { db } from '@/lib/db'

type Params = { params: Promise<{ id: string }> }

export async function PATCH(req: Request, { params }: Params) {
  const { id } = await params
  const body = await req.json().catch(() => null)
  if (!body) return NextResponse.json({ error: '无效请求' }, { status: 400 })

  const data: Record<string, unknown> = {}
  if (typeof body.name === 'string') {
    const name = body.name.trim()
    if (!name || name.length > 40) return NextResponse.json({ error: '名称无效' }, { status: 400 })
    data.name = name
  }
  if (typeof body.emoji === 'string') data.emoji = body.emoji.slice(0, 8)
  if (typeof body.description === 'string') data.description = body.description.trim().slice(0, 200) || null
  if (typeof body.projectRules === 'string') data.projectRules = body.projectRules.slice(0, 64000)
  if (body.touch === true) data.lastUsedAt = new Date()

  if (Object.keys(data).length === 0) {
    return NextResponse.json({ error: '没有可更新字段' }, { status: 400 })
  }
  try {
    const ws = await db.workspace.update({ where: { id }, data })
    return NextResponse.json({ workspace: ws })
  } catch {
    return NextResponse.json({ error: '工作区不存在' }, { status: 404 })
  }
}

export async function DELETE(_req: Request, { params }: Params) {
  const { id } = await params
  try {
    await db.workspace.delete({ where: { id } })
    return NextResponse.json({ ok: true })
  } catch {
    return NextResponse.json({ error: '工作区不存在' }, { status: 404 })
  }
}
