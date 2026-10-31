import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { ensureSeed } from '@/lib/seed'

export async function GET() {
  await ensureSeed()
  const workspaces = await db.workspace.findMany({
    orderBy: { lastUsedAt: 'desc' },
    include: { _count: { select: { conversations: true } } },
  })
  const globalRules = (await db.setting.findUnique({ where: { key: 'globalRules' } }))?.value || ''
  return NextResponse.json({
    workspaces: workspaces.map((w) => ({
      id: w.id,
      name: w.name,
      emoji: w.emoji,
      description: w.description,
      projectRules: w.projectRules,
      conversationCount: w._count.conversations,
      lastUsedAt: w.lastUsedAt,
      createdAt: w.createdAt,
    })),
    globalRules,
  })
}

export async function POST(req: Request) {
  const body = await req.json().catch(() => null)
  const name = (body?.name || '').toString().trim()
  if (!name || name.length > 40) {
    return NextResponse.json({ error: '工作区名称必填（≤40 字）' }, { status: 400 })
  }
  const emoji = (body?.emoji || '🗂').toString().slice(0, 8)
  const description = (body?.description || '').toString().trim().slice(0, 200) || null
  const ws = await db.workspace.create({ data: { name, emoji, description } })
  return NextResponse.json({ workspace: { ...ws, conversationCount: 0 } }, { status: 201 })
}
