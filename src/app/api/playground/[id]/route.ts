import { NextResponse } from 'next/server'
import { db } from '@/lib/db'

type Params = { params: Promise<{ id: string }> }

export async function GET(_req: Request, { params }: Params) {
  const { id } = await params
  const app = await db.playgroundApp.findUnique({ where: { id } })
  if (!app) return NextResponse.json({ error: '作品不存在' }, { status: 404 })
  return NextResponse.json({ app })
}

export async function PATCH(req: Request, { params }: Params) {
  const { id } = await params
  const body = await req.json().catch(() => null)
  if (!body) return NextResponse.json({ error: '无效请求' }, { status: 400 })
  const data: Record<string, unknown> = {}
  if (typeof body.title === 'string') {
    const title = body.title.trim()
    if (!title || title.length > 40) return NextResponse.json({ error: '标题无效' }, { status: 400 })
    data.title = title
  }
  if (typeof body.description === 'string') data.description = body.description.trim().slice(0, 300)
  if (typeof body.html === 'string' && body.html.length > 500) {
    data.html = body.html
    data.version = { increment: 1 }
  }
  if (Object.keys(data).length === 0) {
    return NextResponse.json({ error: '没有可更新字段' }, { status: 400 })
  }
  try {
    const app = await db.playgroundApp.update({ where: { id }, data })
    return NextResponse.json({ app })
  } catch {
    return NextResponse.json({ error: '作品不存在' }, { status: 404 })
  }
}

export async function DELETE(_req: Request, { params }: Params) {
  const { id } = await params
  try {
    const app = await db.playgroundApp.findUnique({ where: { id }, select: { builtin: true } })
    if (!app) return NextResponse.json({ error: '作品不存在' }, { status: 404 })
    if (app.builtin) {
      return NextResponse.json({ error: '内置示例不可删除（可隐藏其入口）' }, { status: 403 })
    }
    await db.playgroundApp.delete({ where: { id } })
    return NextResponse.json({ ok: true })
  } catch {
    return NextResponse.json({ error: '作品不存在' }, { status: 404 })
  }
}
