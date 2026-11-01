import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { memoryScore } from '@/lib/scoring'

export async function GET() {
  const items = await db.memoryItem.findMany({ orderBy: { createdAt: 'desc' } })
  const withScore = items
    .map((m) => ({ ...m, score: memoryScore(m) }))
    .sort((a, b) => b.score - a.score)
  return NextResponse.json({ memories: withScore })
}

export async function POST(req: Request) {
  const body = await req.json().catch(() => null)
  const content = (body?.content || '').toString().trim()
  if (!content || content.length > 300) {
    return NextResponse.json({ error: '内容必填（≤300 字）' }, { status: 400 })
  }
  const category = ['fact', 'state', 'milestone', 'preference'].includes(body?.category)
    ? body.category
    : 'fact'
  const importance = Math.min(1, Math.max(0.3, Number(body?.importance) || 0.6))
  const item = await db.memoryItem.create({
    data: { content, category, importance, source: 'manual' },
  })
  return NextResponse.json({ memory: { ...item, score: memoryScore(item) } }, { status: 201 })
}

/** DELETE /api/memory?all=1 —— 清空全部记忆 */
export async function DELETE(req: Request) {
  const url = new URL(req.url)
  if (url.searchParams.get('all') !== '1') {
    return NextResponse.json({ error: '缺少 all=1（按条删除请用 /api/memory/:id）' }, { status: 400 })
  }
  const deleted = await db.memoryItem.deleteMany({})
  return NextResponse.json({ ok: true, deleted: deleted.count })
}
