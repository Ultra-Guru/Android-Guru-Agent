import { NextResponse } from 'next/server'
import { db } from '@/lib/db'

/** GET /api/rules —— 软件级（全局）+ 各工作区项目级规则总览 */
export async function GET() {
  const setting = await db.setting.findUnique({ where: { key: 'globalRules' } })
  const workspaces = await db.workspace.findMany({
    orderBy: { lastUsedAt: 'desc' },
    select: { id: true, name: true, emoji: true, projectRules: true, _count: { select: { conversations: true } } },
  })
  return NextResponse.json({
    globalRules: setting?.value || '',
    workspaces: workspaces.map((w) => ({
      id: w.id,
      name: w.name,
      emoji: w.emoji,
      projectRules: w.projectRules,
      conversationCount: w._count.conversations,
    })),
  })
}

/** PUT /api/rules —— scope: "global" 软件级 | "workspace" 项目级（某个工作区） */
export async function PUT(req: Request) {
  const body = await req.json().catch(() => null)
  const scope = body?.scope
  const rules = (body?.rules ?? '').toString()
  if (rules.length > 64000) {
    return NextResponse.json({ error: '规则超长（≤ 64000 字）' }, { status: 400 })
  }

  if (scope === 'global') {
    await db.setting.upsert({
      where: { key: 'globalRules' },
      create: { key: 'globalRules', value: rules },
      update: { value: rules },
    })
    return NextResponse.json({ ok: true, scope: 'global' })
  }

  if (scope === 'workspace') {
    const workspaceId = (body?.workspaceId || '').toString()
    if (!workspaceId) return NextResponse.json({ error: '缺少 workspaceId' }, { status: 400 })
    try {
      const ws = await db.workspace.update({
        where: { id: workspaceId },
        data: { projectRules: rules },
      })
      return NextResponse.json({ ok: true, scope: 'workspace', workspaceId: ws.id })
    } catch {
      return NextResponse.json({ error: '工作区不存在' }, { status: 404 })
    }
  }

  return NextResponse.json({ error: 'scope 必须是 global 或 workspace' }, { status: 400 })
}
