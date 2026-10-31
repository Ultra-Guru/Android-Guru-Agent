import { NextResponse } from 'next/server'
import { db } from '@/lib/db'

const ASPECTS = ['tech-stack', 'coding-style', 'preferences', 'domains', 'habits', 'goals']

export async function GET() {
  const items = await db.profileItem.findMany({ orderBy: { confidence: 'desc' } })
  const lastDerived = await db.profileItem.findFirst({
    where: { source: 'derived' },
    orderBy: { updatedAt: 'desc' },
    select: { updatedAt: true },
  })
  return NextResponse.json({
    profile: items,
    stats: {
      total: items.length,
      covered: new Set(items.map((i) => i.aspect)).size,
      derived: items.filter((i) => i.source === 'derived').length,
      lastDerivedAt: lastDerived?.updatedAt || null,
    },
  })
}

export async function POST(req: Request) {
  const body = await req.json().catch(() => null)
  const content = (body?.content || '').toString().trim()
  if (!content || content.length > 200) {
    return NextResponse.json({ error: '内容必填（≤200 字）' }, { status: 400 })
  }
  const aspect = ASPECTS.includes(body?.aspect) ? body.aspect : 'tech-stack'
  const confidence = Math.min(1, Math.max(0.3, Number(body?.confidence) || 0.7))
  const item = await db.profileItem.create({
    data: { content, aspect, confidence, source: 'manual', evidence: null },
  })
  return NextResponse.json({ profileItem: item }, { status: 201 })
}

/** DELETE /api/profile?all=1 —— 清空全部画像 */
export async function DELETE(req: Request) {
  const url = new URL(req.url)
  if (url.searchParams.get('all') !== '1') {
    return NextResponse.json({ error: '缺少 all=1（按条删除请用 /api/profile/:id）' }, { status: 400 })
  }
  const deleted = await db.profileItem.deleteMany({})
  return NextResponse.json({ ok: true, deleted: deleted.count })
}
