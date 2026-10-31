import { NextResponse } from 'next/server'
import { db } from '@/lib/db'

/** 导出全部数据（JSON 备份） */
export async function GET() {
  const [workspaces, conversations, memories, profile, apps, settings] = await Promise.all([
    db.workspace.findMany({ include: { conversations: { include: { messages: true } } } }),
    Promise.resolve(null),
    db.memoryItem.findMany(),
    db.profileItem.findMany(),
    db.playgroundApp.findMany({ where: { builtin: false } }),
    db.setting.findMany(),
  ])
  const globalRules = settings.find((s) => s.key === 'globalRules')?.value || ''
  return NextResponse.json(
    {
      exportedAt: new Date().toISOString(),
      version: 1,
      globalRules,
      workspaces,
      memories,
      profile,
      playground: apps,
    },
    {
      headers: {
        'content-disposition': `attachment; filename="guru-backup-${Date.now()}.json"`,
      },
    }
  )
}
